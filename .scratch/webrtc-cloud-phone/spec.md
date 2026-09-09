# scrcpy → WebRTC 云手机串流：接口级技术设计

> Status: accepted（2026-08-31 经 grilling 收敛）
> 范围：在 scrcpy-server（Android/Java）内集成 libwebrtc，替换自定义 TCP 传输协议为 WebRTC。采集链 + MediaCodec 硬件编码链完全复用。

## 最终共识（决策汇总）

| 维度 | 最终决策 |
|---|---|
| 硬件 | 一台 ARM 服务器（3×GPU + VPU，可增减）+ 50 个**定制 AOSP Android 实例**，1 用户 = 1 实例，峰值 50 |
| 内容 | 上线 = 轻量 2D 挂机；**三角洲 = 性能 benchmark（已加回）**——GPU 渲染解决后作为重度 3D + 低延迟验收标杆 |
| 采集 | scrcpy-server 改**系统应用** + MediaProjectionManager 免弹窗**直调 GPU**（AOSP 已修改，见第 10 节） |
| 编码 | MediaCodec 硬件编码（**VPU 硬件编解码**，复用） |
| 传输 | WebRTC **C++ 路线**：视频 C++ passthrough（LiveKit 模式）+ 音频 MediaCodec Opus passthrough（方案 A） |
| libwebrtc 来源 | Google 官方 webrtc（`google-webrtc`）源码构建，**含 C++ 头文件** |
| 控制 | DataChannel 承载 23 种控制消息（字节流原样搬移） |
| 弱网 | S1 自适应，**5 档阶梯**（见下） |
| 平台 | 无独立 SFU，**P2P 直连 + TURN 兜底**，信令 WebSocket；B2C 目标，**先聚焦串流质量**，多租户/复杂计费后置 |
| 客户端 | Web 原生 RTCPeerConnection + Android 原生 App |

### 5 档自适应阶梯（核心壁垒）

| 档 | 分辨率/帧率/码率 | 端到端延迟 |
|---|---|---|
| 1 网络好 | 1080p60 @ 10 Mbps | 60~90ms |
| 2 开始恶化 | 720p60 @ 5 Mbps | 80~120ms |
| 3 继续恶化 | 720p30 @ 2.5 Mbps | 100~150ms |
| 4 严重弱网 | 540p30 @ 1.5 Mbps | 120~180ms |
| 5 极端弱网 | 480p24 @ ~1 Mbps | 150~220ms |

降档触发：WebRTC 带宽估计（`SetRates`）→ MediaCodec 动态码率（轻量）+ 跨档分辨率/帧率切换（`CaptureControl.reset` 重启 codec）。

### 前置任务（顺序依赖）

1. **自编译 libwebrtc**（`google-webrtc` 官方源码，含 C++ 头文件）—— 视频 C++ passthrough + 音频 A 的硬前置
2. **定制 AOSP 实例部署验证**—— 一台 ARM 服务器 + 50 实例 + GPU 渲染 + VPU 编码，先测 FPS/延迟基线
3. **scrcpy-server 系统应用化**—— 采集层改造（见第 10 节）

---

## 0. 核心结论（决定整个设计的一句话）

libwebrtc **没有「往 track 里注入已编码帧」的 API**——Java 和 C++ 都没有。唯一的受支持 seam 是**编码器**：实现 `webrtc::VideoEncoder`（C++）或 `org.webrtc.VideoEncoder`（Java），注册进 `VideoEncoderFactory`，把 MediaCodec 的编码输出通过 `EncodedImageCallback::OnEncodedImage()` 交付。生产级验证过的做法是 **LiveKit 的 passthrough 模式**（`PassthroughVideoEncoder`）。

这个结论直接决定了下面所有接口的形态。

## 1. 改造总览

```
改造前（scrcpy 现状）                        改造后（WebRTC）
─────────────────────                        ─────────────────────
SurfaceCapture（虚拟显示采集，复用）            SurfaceCapture（不动）
   ↓ Surface                                     ↓ Surface
SurfaceEncoder（MediaCodec 硬件编码，复用）      SurfaceEncoder（不动）
   ↓ streamer.writePacket()                      ↓ 改为 push 到 passthrough 源
Streamer（自定义 12 字节头 → TCP socket）         EncodedVideoFrameBuffer（kNative 包装）
   ↓                                              ↓
DesktopConnection（3 × LocalSocket）              webrtc::VideoEncoder passthrough
                                                  ↓ OnEncodedImage()
ControlChannel（LocalSocket）                     libwebrtc RTP/RTCP → SRTP → ICE
                                                  ↓
                                                  DataChannel（控制消息字节流原样搬移）
```

改动清单（按文件）：

| 文件 | 改动 |
|---|---|
| `Streamer.java` | 拆成接口 `PacketSink` + 两个实现（`SocketStreamer` 保留 / `WebRtcPacketSink` 新增） |
| `SurfaceEncoder.java` | `encode()` 循环出口从 `streamer.writePacket()` 改为「包装 native buffer → push track source」；`videoBitRate` 改为可变 |
| `AudioEncoder.java` / `AudioRawRecorder.java` | 音频出口同步替换 |
| `DesktopConnection.java` | 删除（被 PeerConnection 取代） |
| `ControlChannel.java` | 抽象化：接受 `InputStream/OutputStream`，DataChannel 适配成流 |
| `Server.java` | 编排改为「建 PeerConnection → 建 processor → 启动」 |
| 新增 `NativeEncoderBridge.java` | JNI 桥：Java MediaCodec ↔ C++ passthrough encoder |
| 新增 C++ 层 | `scrcpy_passthrough_video_encoder.cc`、`scrcpy_opus_audio_encoder.cc`、JNI 绑定 |

## 2. 核心抽象：Streamer → PacketSink 接口

这是全设计最关键的一刀。现有 `Streamer` 是 concrete class，直接持有 `FileDescriptor`。把它抽象成接口，编码链对接口编程，实现可切换：

```java
// 新接口（包名 com.genymobile.scrcpy.device）
public interface PacketSink {
    Codec getCodec();

    void writeAudioHeader() throws IOException;
    void writeVideoHeader() throws IOException;
    void writeDisableStream(boolean error) throws IOException;

    // 核心出口：参数已与 webrtc::EncodedImage 字段一一对应
    void writePacket(ByteBuffer buffer, long pts, boolean config, boolean keyFrame) throws IOException;
    void writePacket(ByteBuffer codecBuffer, MediaCodec.BufferInfo bufferInfo) throws IOException;

    // 分辨率变化的信号（WebRTC 里对应 SDP renegotiation / 编码器重配）
    void writeSessionMeta(int width, int height, boolean isClientResize) throws IOException;
}
```

- `SocketStreamer` = 现有 `Streamer` 逻辑原封不动搬进去（保留向后兼容 / 测试对照）。
- `WebRtcPacketSink` = 新实现，`writePacket(buffer, pts, config, keyFrame)` 内部做：
  1. 若 `config` → 缓存 SPS/PPS（后面 prepend 到 IDR）
  2. 若 `keyFrame` → SPS/PPS + AU 拼成完整 IDR
  3. 包装成 `EncodedVideoFrameBuffer`，push 到 `VideoTrackSource`

> `SurfaceEncoder`/`AudioEncoder` 里 `Streamer` 类型的字段和构造参数只改类型名（`Streamer` → `PacketSink`），`encode()` 循环第 270 行的 `streamer.writePacket(codecBuffer, bufferInfo)` 一行都不动。这就是把 seam 切在正确位置的价值。

## 3. 视频 WebRTC 出口（passthrough encoder，LiveKit 模式）

### 3.1 数据流

```
MediaCodec 输出 H.264 Annex-B AU（含 PTS、keyframe 标志）
  → Java: ByteBuffer
  → JNI: 包装成 EncodedVideoFrameBuffer（webrtc::VideoFrameBuffer kNative 子类）
  → push 到自定义 VideoTrackSource（作为普通 VideoFrame）
  → libwebrtc VideoStreamEncoder 收到 frame
  → 调用 ScrcpyPassthroughEncoder::Encode(frame, frame_types)
  → unwrap EncodedVideoFrameBuffer，填 EncodedImage + CodecSpecificInfo
  → OnEncodedImage() → H.264 packetizer（原生识别 Annex-B）→ RTP
```

关键点：libwebrtc 的 H.264 packetizer 原生按 start code 切 NAL（`rtp_format_h264.cc`），所以 MediaCodec 的 Annex-B 码流不需要转 AVCC，只需保证 IDR 前有 SPS/PPS（见 3.4）。

### 3.2 接口签名（C++）

```cpp
// scrcpy_passthrough_video_encoder.h
class ScrcpyPassthroughEncoder : public webrtc::VideoEncoder {
 public:
  // 由 JNI 层调用，把 Java 侧 MediaCodec 的输出压入
  void OnEncodedFrame(const uint8_t* annexb, size_t len,
                      int64_t pts_us, bool keyframe,
                      int width, int height, int qp);

  // webrtc::VideoEncoder 重写
  int32_t InitEncode(const webrtc::VideoCodec* codec,
                     const webrtc::VideoEncoder::Settings& settings) override;
  int32_t Encode(const webrtc::VideoFrame& frame,
                 const std::vector<webrtc::VideoFrameType>* frame_types) override;
  void SetRates(const webrtc::RateControlParameters& parameters) override;
  int32_t RegisterEncodeCompleteCallback(
      webrtc::EncodedImageCallback* callback) override;
  int32_t Release() override;
};
```

`Encode()` 内部：unwrap `EncodedVideoFrameBuffer` → 取 Annex-B 数据 → `EncodedImage`（`SetRtpTimestamp(frame.rtp_timestamp())`、`_frameType`、`_encodedWidth/Height`）→ `callback_->OnEncodedImage()`。

### 3.3 为什么必须「包装 native buffer」而不是直接队列

直接开个队列、让 `Encode()` 从队列取 MediaCodec 帧会踩两个坑：

1. **RTP timestamp 对齐**：`Encode(frame, ...)` 收到的 `frame.rtp_timestamp()` 是 libwebrtc 统一分配的时钟。把编码帧包装成这个 `frame` 的内容，`EncodedImage` 就能直接用 `frame.rtp_timestamp()`，无需自己维护「MediaCodec PTS ↔ RTP 时间戳」的映射。这是 LiveKit 方案的正确性来源。
2. **编码节奏同步**：libwebrtc 的 `VideoStreamEncoder` 以自己的节奏调 `Encode()`，包装模式让编码帧天然跟随这个节奏，不会出现「队列堆积」或「空转」。

### 3.4 SPS/PPS 处理（对齐 HardwareVideoEncoder 的做法）

MediaCodec 把 SPS/PPS 单独作为 `BUFFER_FLAG_CODEC_CONFIG` 输出（`config=true`）。libwebrtc 的 `HardwareVideoEncoder` 的做法是：缓存 config buffer，prepend 到每个 IDR 前。照抄：

```java
// WebRtcPacketSink 内
byte[] spsPps; // 每次 writePacket(config=true) 时更新

void onKeyFrame(ByteBuffer idr) {
    ByteBuffer out = ByteBuffer.allocate(spsPps.length + idr.remaining());
    out.put(spsPps);       // SPS/PPS 在前
    out.put(idr);          // IDR NAL 在后
    pushToTrackSource(out);
}
```

### 3.5 决策：C++ passthrough vs 纯 Java passthrough

| | C++ passthrough（推荐） | 纯 Java passthrough |
|---|---|---|
| 音频 | 可做 Opus passthrough | 不能（`AudioEncoderFactoryFactory` 必须返回 native 指针） |
| timestamp | 干净（native buffer 模式） | 有坑：`VideoEncoderWrapper` 按 `captureTimeNs` 配对 |
| `CodecSpecificInfo` | 完整控制 | Java 层只能设空 marker |
| 构建 | 需自编译 libwebrtc（C++） | 可用官方预编译 `.aar` |
| 生产参考 | LiveKit | 无成熟参考 |

结论：选 C++ passthrough。商用 50 路需要音频 + 完整控制，纯 Java 省下的构建成本会被 timestamp/音频的坑加倍还回来。

## 4. 音频 WebRTC 出口

两条路，**已定 A**（2026-08-31 决策，配合 C++ 路线）：

### 方案 A：MediaCodec Opus passthrough（✅ 已选，保持编码链一致）

实现 `webrtc::AudioEncoder` 子类，`EncodeImpl(rtp_timestamp, pcm, encoded)` 里忽略 PCM、把预编码 Opus 写进 `encoded`，经 `AudioEncoderFactory` + `AudioEncoderFactoryFactory`（返回 native 指针）注册。

- 坑：libwebrtc 每 10ms 调一次 `Encode()`，而 scrcpy 的 MediaCodec Opus 默认 20ms 帧 → 需要每 2 次 `Encode()` 发一个包（空调用返回 `encoded_bytes=0, send_even_if_empty=false`），并保证 `encoded_timestamp` 对齐。

### 方案 B：PCM 直喂 libwebrtc 原生音频轨道（备选）

scrcpy 的 `AudioRecordReader` 已经读 48kHz/2ch/16-bit PCM（1024-sample 块），这正好是 libwebrtc 音频 track 的标准输入。放弃 MediaCodec 音频编码，把 PCM 喂给 libwebrtc，让它的 Opus 编码器（`AudioEncoderOpusImpl`）编码：

```
AudioPlaybackCapture → AudioRecordReader（PCM，复用）
  → libwebrtc 音频 source（10ms/帧，480 samples）
  → libwebrtc 原生 Opus 编码（自带 FEC/DTX 可选）
  → RTP
```

- 优点：零 passthrough 复杂度、零自定义音频编码器、`AudioEncoderOpusImpl` 是 Google 维护的高质量实现，FEC 对弱网还更友好。
- 代价：`AudioEncoder.java` 的 MediaCodec Opus 链路被替换（但 `AudioRecordReader` 采集链保留）。

## 5. 控制通道 → DataChannel

控制协议（23 种 `ControlMessage`，大端二进制）是纯字节流，零改动。只需把 `ControlChannel` 的底层从 `LocalSocket` 换成 DataChannel 适配的流：

```java
// 改造前
public ControlChannel(LocalSocket controlSocket) {
    reader = new ControlMessageReader(controlSocket.getInputStream());
    writer = new DeviceMessageWriter(controlSocket.getOutputStream());
}

// 改造后：解耦传输，接受任意流
public ControlChannel(InputStream in, OutputStream out) {
    reader = new ControlMessageReader(in);
    writer = new DeviceMessageWriter(out);
}
```

DataChannel 侧两个适配器（各约 40 行）：

```java
// DataChannel 的 ByteBuffer 消息 → InputStream（给 ControlMessageReader 用）
class DataChannelInputStream extends InputStream {
    private final BlockingQueue<ByteBuffer> queue = new LinkedBlockingQueue<>();
    void onMessage(DataChannel.Buffer buf) { queue.add(buf.data); }
    int read() { /* 跨 message 边界维护 position/limit */ }
}

// OutputStream → DataChannel.send（给 DeviceMessageWriter 用）
class DataChannelOutputStream extends OutputStream {
    void write(...) { dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(...), true)); }
}
```

DataChannel 配置：
- client→server（控制消息）：有序、可靠（默认），承载触控/按键，必须有序。
- server→client（device 消息：剪贴板/UHID 输出）：同一条 channel 反向即可。

> `Controller.control()` 循环（`controlChannel.recv()` 阻塞读 → `handleEvent()` 分派）完全不动，只是 `recv()` 背后的流换成了 DataChannel。

## 6. 动态码率 + PLI（弱网核心，新增反馈链路）

### 6.1 码率：SetRates → MediaCodec（轻量，无需重启）

```
libwebrtc 带宽估计（GoogCc/TWCC）
  → VideoStreamEncoder::SetRates(RateControlParameters)
  → ScrcpyPassthroughEncoder::SetRates(parameters)     // ← 我们的 seam
  → JNI: setTargetBitrate(parameters.bitrate.get_sum_bps(), parameters.framerate_fps)
  → MediaCodec.setParameters(PARAMETER_KEY_VIDEO_BITRATE)
```

对应 Java 桥：

```java
// NativeEncoderBridge（JNI）
public void setTargetBitrate(int bps, double fps) {
    Bundle b = new Bundle();
    b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps);
    mediaCodec.setParameters(b);   // 运行时改码率，无需重启 codec
}
```

> 注意：现有 `SurfaceEncoder` 的 `videoBitRate` 是 `final` 字段、写死在 `MediaFormat.KEY_BIT_RATE`。改造：`videoBitRate` 去 `final`，`setParameters` 动态覆盖即可。

### 6.2 PLI → 关键帧（新增）

```
远端丢包发 PLI/FIR
  → EncoderRtcpFeedback::OnReceivedIntraFrameRequest
  → VideoStreamEncoder::SendKeyFrame()  // 置 next_frame_types_ = kVideoFrameKey
  → ScrcpyPassthroughEncoder::Encode(frame, &frame_types)  // ← 检测
  → JNI: requestKeyframe()
  → MediaCodec.setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)
```

```java
public void requestKeyframe() {
    Bundle b = new Bundle();
    b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
    mediaCodec.setParameters(b);
}
```

### 6.3 分辨率自适应（重量级，复用现有机制）

当带宽跌到码率已无法继续降，需要降分辨率/帧率时，走 scrcpy 已有的 `CaptureControl.reset()` 重配链路：

```
SetRates 检测到带宽 < 阈值
  → surfaceCapture.getCaptureControl().reset(RESET_REASON_CLIENT_RESET)
  → SurfaceEncoder 的 EOS → 重配 MediaFormat（新分辨率/码率）→ 重启
```

新增一个 `RESET_REASON_BITRATE_CHANGED` 常量，让 `prepareRetry()` 里能区分「码率自适应」和「编码失败降级」两种重配语义。

三层自适应总结：

| 层 | 触发 | 机制 | 改动 |
|---|---|---|---|
| 码率 | `SetRates` 每次 | `setParameters(BITRATE)` | 新增（去 final） |
| 关键帧 | PLI | `setParameters(SYNC_FRAME)` | 新增 |
| 分辨率/帧率 | 带宽跌破阈值 | `CaptureControl.reset()` → 重配 | 复用 + 新增 reason |

## 7. 生命周期编排（Server.scrcpy 改造）

`Server.scrcpy()` 现有流程：`DesktopConnection.open()` → 建 Controller/AudioEncoder/SurfaceEncoder → `start()` 所有 AsyncProcessor → `Looper.loop()`。

改造后：

```java
private static void scrcpy(Options options) {
    // 1. 建 PeerConnection（替代 DesktopConnection.open）
    PeerConnectionFactory factory = buildFactory();          // 注册 passthrough encoder
    PeerConnection pc = factory.createPeerConnection(rtcConfig, observer);

    // 2. 建 DataChannel（替代 3 个 LocalSocket）
    DataChannel controlDc = pc.createDataChannel("control", init);

    // 3. PacketSink 用 WebRTC 实现（替代 new Streamer(fd, ...)）
    PacketSink videoSink = new WebRtcPacketSink(videoSource, options);   // 视频
    PacketSink audioSink = ...;                                          // 音频

    // 4. 采集/编码 processor 照旧（只换 PacketSink 类型）
    SurfaceEncoder surfaceEncoder = new SurfaceEncoder(surfaceCapture, videoSink, options);
    // ... Controller 用 new ControlChannel(dcInputStream, dcOutputStream)

    // 5. 信令：offer/answer 经外部信令服务器（WebSocket），ICE candidate 同理
    // 6. start() 所有 AsyncProcessor → Looper.loop()（不变）
}
```

关键点：`AsyncProcessor` 生命周期接口（start/stop/join）完全不用改，`SurfaceEncoder`、`AudioEncoder`、`Controller` 依然是 `AsyncProcessor`，编排骨架原样保留。唯一替换的是 `DesktopConnection` 这个连接层。

## 8. 分阶段落地（依赖顺序）

| 阶段 | 交付物 | 验证标准 |
|---|---|---|
| P0 | `Streamer` → `PacketSink` 接口拆分 + `SocketStreamer` 回归 | 现有 TCP 模式测试全绿 |
| P1 | `WebRtcPacketSink` + 视频 passthrough encoder（C++）+ `NativeEncoderBridge` JNI | 浏览器 `<video>` 能播 H.264，延迟达标 |
| P2 | 音频：PCM 直喂 libwebrtc 音频 track | 浏览器能出声，音画同步 |
| P3 | 控制通道 DataChannel（含输入映射） | 浏览器能触控/按键 |
| P4 | 动态码率（SetRates→setParameters）+ PLI→关键帧 | 弱网模拟下码率自适应、丢包可恢复 |
| P5 | 信令 + ICE + TURN + 会话编排 | 公网/跨 NAT 可连 |

## 9. 决策点（全部落定）

1. **采集层**：✅ 系统应用 + MediaProjectionManager 免弹窗（见第 10 节）。
2. **音频方案**：✅ A（MediaCodec Opus passthrough，C++ `webrtc::AudioEncoder`）。
3. **C++ 工程**：✅ 接受，libwebrtc 用 `google-webrtc` 官方源码构建（含 C++ 头文件）。

## 10. 系统应用化（采集层改造）

### 现状 → 目标

| 维度 | 现状 | 目标（系统应用） |
|---|---|---|
| 打包 | `app_process` 跑裸 JAR（classes.dex） | platform 签名的真 APK |
| Manifest | 空 `<manifest />` | 完整（application + service + 权限） |
| 入口 | `Server.main(args)` | `ServerService`（真 Context 载体） |
| 进程身份 | shell UID 2000（`FakeContext` 伪装 `com.android.shell`） | 系统应用 UID + signature\|privileged 权限 |
| 采集 | 反射 `DisplayManagerGlobal.createVirtualDisplay` | `MediaProjectionManager` 免弹窗 |
| 部署 | `adb push` + `app_process` | `/system/priv-app/` + init.rc 自启 |

### 四个改造点

**① `AndroidManifest.xml`**（从空变完整）

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.genymobile.scrcpy">
    <!-- MediaProjection 背后的 signature|privileged 权限，免弹窗的关键 -->
    <uses-permission android:name="android.permission.CAPTURE_VIDEO_OUTPUT" />
    <uses-permission android:name="android.permission.MANAGE_MEDIA_PROJECTION" />
    <application android:label="scrcpy-server">
        <service android:name=".ServerService" android:exported="false" />
    </application>
</manifest>
```

**② 新增 `ServerService.java`**（真 Context 载体）

```java
public class ServerService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> Server.scrcpy(this /* Context */, options)).start();
        return START_NOT_STICKY;
    }
}
```

**③ 新增 `MediaProjectionCapture extends SurfaceCapture`**（核心）

```java
public class MediaProjectionCapture extends SurfaceCapture {
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;

    @Override
    protected void init(VideoConstraints vc) {
        MediaProjectionManager mpm =
            (MediaProjectionManager) context.getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(          // @hide，需 MANAGE_MEDIA_PROJECTION
            Process.myUid(), context.getPackageName(),
            MediaProjectionManager.TYPE_SCREEN_CAPTURE,
            true /* permanentGrant */);
    }

    @Override
    public void start(Surface surface) throws IOException {
        virtualDisplay = projection.createVirtualDisplay(
            "scrcpy", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null);
    }

    @Override
    public void stop() { if (virtualDisplay != null) virtualDisplay.release(); }
    @Override
    public void release() { if (projection != null) projection.stop(); }
    // getSize / applyNewVideoConstraints / prepare 复用 ScreenCapture 逻辑
}
```

**④ `Server.java`**：`scrcpy()` 加 Context 参数，采集选择加分支

```java
SurfaceCapture surfaceCapture;
if (options.getCaptureMode() == CaptureMode.MEDIA_PROJECTION) {
    surfaceCapture = new MediaProjectionCapture(context, controller, options);
} else {
    surfaceCapture = new ScreenCapture(controller, options);   // 现状，保留
}
SurfaceEncoder encoder = new SurfaceEncoder(surfaceCapture, videoSink, options);  // 零改动
```

### 免弹窗机制

系统应用持有 `CAPTURE_VIDEO_OUTPUT` + `MANAGE_MEDIA_PROJECTION` 权限后，直接调隐藏方法：

```java
// android.media.projection.MediaProjectionManager 的 @hide 方法
public MediaProjection getMediaProjection(int uid, String packageName, int type, boolean permanentGrant)
```

- `permanentGrant=true` → 授权持久化，进程重启后仍有效
- 无弹窗、无用户交互，比 `FakeContext` + 反射 DisplayManager 更规范，也更不易被反作弊检测

### 部署链

1. 云手机 ROM 的 `platform.pk8` / `platform.x509.pem` 签名 APK
2. 装入 `/system/priv-app/ScrcpyServer/ScrcpyServer.apk`
3. init.rc 自启（或 `am startservice`）
4. 每台云手机实例 = 一个独立多开用户/容器内跑一个 `ServerService`

### 注意点

1. `getMediaProjection()` 的 @hide 签名随 Android 版本有差异，需在目标 ROM 的 `android.media.projection` 源码上核对后封装（复用 scrcpy 现有 `wrappers/` 反射模式）。
2. 云手机自有 ROM 下，现有隐藏 API 方案（`ScreenCapture`）本就能工作（系统权限更足）；MediaProjectionManager 方案的价值是「规范化 + 反检测友好」，代价是多一个采集实现 + 隐藏 API 适配。两者都喂同一个 Surface 给 MediaCodec，不影响 WebRTC 改造。
3. 反作弊风险仍存在：Delta Force 手游可能检测 MediaProjection/虚拟显示环境（见 FAQ 层面风险），系统免弹窗方案能降低「弹窗痕迹」，但无法消除「虚拟显示环境」本身。

## 11. 实施优先级（分阶段，带依赖边界）

> 依赖方向：P0 → P2 → P3；P1 与 P0/P2 无耦合，可并行；P4 依赖 P2 完成。

### P0 前置准备（可并行）

| 任务 | 交付物 | 验证标准 | 阻塞方 |
|---|---|---|---|
| 自编译 libwebrtc | `google-webrtc` 源码构建产物（`.aar` + C++ 头文件 + native `.so`） | 能编译一个继承 `webrtc::VideoEncoder` 的最小 C++ 工程 | 阻塞 P2 |
| redroid 部署验证 | ARM 服务器 + N 容器部署脚本 + 基线报告 | 50 容器软件渲染跑轻量 2D，FPS/CPU/内存达标 | 阻塞 P2 集成测试 |

### P1 scrcpy 纯重构（零风险，不依赖 libwebrtc）

| 任务 | 交付物 | 验证标准 |
|---|---|---|
| `Streamer` → `PacketSink` 接口拆分 | `SocketStreamer` + 接口定义 | 现有 TCP 模式单元测试全绿 |
| 系统应用化 | `AndroidManifest.xml` + `ServerService` + `MediaProjectionCapture` | system APK 在 redroid 容器内免弹窗采集出画面 |

### P2 WebRTC 集成（依赖 P0 libwebrtc）

| 任务 | 交付物 | 验证标准 |
|---|---|---|
| 视频 C++ passthrough | `ScrcpyPassthroughEncoder` + `NativeEncoderBridge` JNI | 浏览器 `<video>` 播 H.264，延迟达标 |
| 音频 Opus passthrough | `ScrcpyOpusAudioEncoder`（C++ `AudioEncoder`） | 浏览器出声，音画同步 |
| 控制通道 DataChannel | `DataChannelInputStream/OutputStream` + `ControlChannel` 改造 | 浏览器触控/按键走 DataChannel |

### P3 弱网核心（依赖 P2）

| 任务 | 交付物 | 验证标准 |
|---|---|---|
| 动态码率 + 5 档阶梯 | `SetRates`→`setParameters` + `CaptureControl.reset` 跨档切换 | 弱网模拟下 5 档自动切换、丢包可恢复 |

### P4 平台（依赖 P2）

| 任务 | 交付物 | 验证标准 |
|---|---|---|
| 信令 + TURN 兜底 | 信令服务器（WebSocket）+ TURN（coturn） | 公网/跨 NAT 可连，P2P 失败走 TURN |
| 会话调度 + 简单计费 | 实例分配 + 按串流计费 | 峰值 50 实例并发调度 |
| 客户端 | Web（RTCPeerConnection）+ Android 原生 App | 全链路端到端可用 |

## 12. 完整技术架构图

### 端到端系统架构（总览）

```
┌──────────────────────────────────────────────────────────────────────────┐
│                              客户端层                                      │
│   ┌─────────────────────┐                 ┌──────────────────────┐        │
│   │      Web 浏览器      │                 │    Android 原生 App   │        │
│   │  RTCPeerConnection   │                 │    org.webrtc 库      │        │
│   │  + DataChannel       │                 │    + DataChannel      │        │
│   └──────────┬──────────┘                 └──────────┬───────────┘        │
│              └── 输入映射(触屏/键鼠 → 23种控制消息字节流) ──┘                │
└──────────────┼─────────────────────────────────────────┼──────────────────┘
               │        WebRTC(SRTP) + DataChannel        │
               │         + WebSocket(信令)                 │
┌──────────────▼─────────────────────────────────────────▼──────────────────┐
│                          中心控制平面（B2C 平台）                            │
│  ┌───────────────┐   ┌──────────────┐   ┌────────────────────────────┐    │
│  │  信令服务器     │   │  TURN 中继    │   │     会话调度 + 简单计费      │    │
│  │  (WebSocket)   │   │  (coturn)    │   │  · 实例注册 + 心跳健康检查    │    │
│  │  offer/answer  │   │  P2P失败兜底  │   │  · 用户 → 空闲实例分配       │    │
│  │  ICE 候选交换   │   │              │   │  · 串流时长计费              │    │
│  └───────────────┘   └──────────────┘   └────────────────────────────┘    │
└──────────┬───────────────────┬──────────────────────┬─────────────────────┘
           │ P2P 直连(优先)     │  TURN 中继(兜底)      │ 信令/控制
           ▼                   ▼                      ▼
    ┌──────────────┐    ┌──────────────┐         ┌──────────────┐
    │  云机实例 #1  │    │  云机实例 #2  │   ···   │  云机实例 #50 │   ← 峰值 50
    └──────────────┘    └──────────────┘         └──────────────┘
           ▲  ←────── 一台 ARM 服务器（3×GPU + VPU，可增减）──────→  ▲
```

### 单台云机内部（scrcpy-server 改造，核心）

```
┌─────────────────────────────────────────────────────────────────────────┐
│            定制 AOSP Android 实例（MediaProjectionManager 直调 GPU）       │
│  ┌──────────────────────────────────────────────────────────────┐      │
│  │      Android 应用层：轻量 2D 挂机（三角洲=性能 benchmark）      │      │
│  └──────────────────────────────────────────────────────────────┘      │
│  ┌────────── scrcpy-server（系统应用 · 改造后 · C++ 路线）────────┐      │
│  │  采集层(改造)         编码层(复用)            传输层(改造/新增)    │      │
│  │  ┌───────────────┐  ┌───────────────────┐  ┌────────────────┐ │      │
│  │  │MediaProjection │  │ SurfaceEncoder     │  │  WebRtcPacket   │ │      │
│  │  │Capture         │→│ (MediaCodec        │→│  Sink           │ │      │
│  │  │·免弹窗预授权    │  │  H.264 硬编码      │  │  ·SPS/PPS 前置   │ │      │
│  │  │·直调 GPU 渲染   │  │  ← VPU 硬件编解码   │  │  ·kNative 包装   │ │      │
│  │  └───────────────┘  └───────────────────┘  └───────┬────────┘ │      │
│  │  ┌───────────────┐  ┌───────────────────┐          │          │      │
│  │  │AudioPlayback   │→│ AudioEncoder       │→ Opus passthrough  │      │
│  │  │Capture(内录)   │  │ (MediaCodec Opus) │          │          │      │
│  │  └───────────────┘  └───────────────────┘          ▼          │      │
│  │  ┌──────────────────────────────────────────────────────────┐│      │
│  │  │         libwebrtc (google-webrtc 源码构建 · C++ 层)        ││      │
│  │  │  EncodedVideoFrameBuffer(kNative) → VideoTrackSource      ││      │
│  │  │    → ScrcpyPassthroughEncoder(webrtc::VideoEncoder)       ││      │
│  │  │      → OnEncodedImage → H.264 packetizer → RTP            ││      │
│  │  │  ScrcpyOpusAudioEncoder(webrtc::AudioEncoder) → RTP       ││      │
│  │  │  PeerConnection (ICE/DTLS/SRTP)                           ││      │
│  │  └──────────────────────────────┬───────────────────────────┘│      │
│  │  ┌───────────────┐    ┌──────────▼───────────┐               │      │
│  │  │  Controller    │←── │  DataChannel(控制)    │               │      │
│  │  │ (控制分派,复用) │    │  23种消息字节流原样搬移 │               │      │
│  │  └───────────────┘    └──────────────────────┘               │      │
│  └──────────────────────────────────┬───────────────────────────┘      │
│                                      │  SRTP / ICE                       │
│                          P2P 直连（优先）/ TURN 中继（兜底）                │
└──────────────────────────────────────┼───────────────────────────────────┘
```

### 三条数据流 + 延迟预算

视频流（下行）：MediaProjectionCapture(直调GPU渲染 10~16ms) → MediaCodec H.264 硬编码(VPU 5~15ms) → PacketSink → kNative 包装 → ScrcpyPassthroughEncoder → H.264 packetizer → RTP → 网络(10~50ms) → JitterBuffer(0~20ms) → 硬件解码+显示(10~30ms) = 端到端 35~130ms。

音频流（下行）：AudioPlaybackCapture(内录) → 48kHz PCM → MediaCodec Opus → ScrcpyOpusAudioEncoder(C++) → RTP。

控制流（双向）：用户输入 → 23种 ControlMessage 字节流 → DataChannel → ControlMessageReader → Controller（复用）。

### 弱网自适应闭环（5 档阶梯）

档1 网络好 1080p60@10M 端到端60~90ms / 档2 开始恶化 720p60@5M 80~120ms / 档3 继续恶化 720p30@2.5M 100~150ms / 档4 严重弱网 540p30@1.5M 120~180ms / 档5 极端弱网 480p24@~1M 150~220ms。降档触发：SetRates → setParameters(BITRATE)（档内）/ CaptureControl.reset()（跨档）。PLI → Encode(frame_types=kVideoFrameKey) → requestKeyframe → SYNC_FRAME。

### 复用/改造/新增/删除边界

复用：SurfaceEncoder、AudioEncoder、Controller、ControlMessage协议、AsyncProcessor。改造：Streamer→PacketSink、ControlChannel、Manifest、Server.scrcpy。新增：MediaProjectionCapture、ServerService、ScrcpyPassthroughEncoder(C++)、ScrcpyOpusAudioEncoder(C++)、NativeEncoderBridge(JNI)、DataChannel适配器。删除：DesktopConnection、FakeContext。
