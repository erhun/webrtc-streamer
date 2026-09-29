# scrcpy → WebRTC 云手机串流：接口级技术设计

> Status: revised（2026-09-29 新增 SDP rewriting NAT 穿越实现，验证通过；2026-09-24 经 grilling 二次收敛，更新采集/音频/阶梯/客户端/清理策略 + 新增多客户端串流 1:N）
> 范围：在 scrcpy-server（Android/Java）内集成 libwebrtc，替换自定义 TCP 传输协议为 WebRTC。采集链 + MediaCodec 硬件编码链完全复用。

## 最终共识（决策汇总）

| 维度 | 最终决策 |
|---|---|
| 硬件 | 一台 ARM 服务器（3×GPU + VPU，可增减）+ 50 个**定制 AOSP Android 实例**，1 用户 = 1 实例，峰值 50 |
| 内容 | 上线 = 轻量 2D 挂机；**三角洲 = 性能 benchmark（已加回）**——GPU 渲染解决后作为重度 3D + 低延迟验收标杆 |
| 采集 | scrcpy-server 改**系统应用** + **ScreenCapture/NewDisplayCapture**（~~MediaProjectionManager~~ 已放弃，见第 10 节） |
| 编码 | MediaCodec 硬件编码（**VPU 硬件编解码**，复用） |
| 传输 | WebRTC **C++ 路线**：视频 C++ passthrough（LiveKit 模式）+ 音频**双方案可切换**（PCM 直喂 ✅ 已实现 / Opus passthrough 待实现） |
| libwebrtc 来源 | Google 官方 webrtc（`google-webrtc`）源码构建，**含 C++ 头文件** |
| 控制 | DataChannel 承载 23 种控制消息（字节流原样搬移） |
| 弱网 | S1 自适应，**6 档阶梯**（见下，实际实现参数） |
| 平台 | 无独立 SFU，**P2P 直连 + SDP rewriting NAT 穿越**（~~TURN 兜底~~ 已验证不需要），信令 WebSocket；B2C 目标，**先聚焦串流质量**，多租户/复杂计费**后置到下一迭代** |
| 客户端 | Web 原生 RTCPeerConnection + Android 原生 App |
| 多客户端 | 一个云机实例支持 ~10 个客户端同时串流（多 PC + 房间模型 + 单点独占控制） |

### 6 档自适应阶梯（实际实现，2026-09-24 确认）

| 档 | 分辨率/帧率/码率 | 说明 |
|---|---|---|
| 1 顶级 | 1920×1080 60fps @ 12 Mbps | 网络极好 |
| 2 优秀 | 1600×900 60fps @ 8 Mbps | ~900p |
| 3 良好 | 1280×720 60fps @ 6 Mbps | 720p60 |
| 4 一般 | 1280×720 30fps @ 4 Mbps | 720p30 |
| 5 较弱 | 960×540 30fps @ 2 Mbps | ~540p30 |
| 6 极弱 | 854×480 24fps @ 1.2 Mbps | ~480p24 |

定义位置：`server/src/main/java/com/genymobile/scrcpy/video/BitrateLadder.java`

降档触发：WebRTC 带宽估计（`SetRates`）→ MediaCodec 动态码率（轻量）+ 跨档分辨率/帧率切换（`CaptureControl.reset` 重启 codec）。

### 前置任务（顺序依赖）

1. **自编译 libwebrtc**（`google-webrtc` 官方源码，含 C++ 头文件）—— 视频 C++ passthrough + 音频 A 的硬前置 ✅ **已完成**
2. **定制 AOSP 实例部署验证**—— 一台 ARM 服务器 + 50 实例 + GPU 渲染 + VPU 编码，先测 FPS/延迟基线
3. ~~**scrcpy-server 系统应用化**~~—— 采集层改用 ScreenCapture/NewDisplayCapture，**MediaProjectionCapture 已放弃**（见第 10 节）

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
| `DesktopConnection.java` | **保留**（TCP 兼容路径，WebRTC 稳定后清理） |
| `ControlChannel.java` | 抽象化：接受 `InputStream/OutputStream`，DataChannel 适配成流 |
| `Server.java` | 编排改为「建 PeerConnection → 建 processor → 启动」 |
| 新增 `NativeEncoderBridge.java` | JNI 桥：Java MediaCodec ↔ C++ passthrough encoder |
| 新增 C++ 层 | `scrcpy_passthrough_video_encoder.cc`、`scrcpy_pcm_audio_source.h`（✅）、`scrcpy_opus_audio_encoder.cc`（待实现）、JNI 绑定 |

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

**双方案并存，通过配置切换**（2026-09-24 决策更新）：

### 方案 B：PCM 直喂 libwebrtc 原生音频轨道（✅ 已实现，默认方案）

scrcpy 的 `AudioRecordReader` 已经读 48kHz/2ch/16-bit PCM（1024-sample 块），这正好是 libwebrtc 音频 track 的标准输入。放弃 MediaCodec 音频编码，把 PCM 喂给 libwebrtc，让它的 Opus 编码器（`AudioEncoderOpusImpl`）编码：

```
AudioPlaybackCapture → AudioRecordReader（PCM，复用）
  → PcmAudioSource (C++) → libwebrtc 音频 source（10ms/帧，480 samples）
  → libwebrtc 原生 Opus 编码（自带 FEC/DTX 可选）
  → RTP
```

- 优点：零 passthrough 复杂度、零自定义音频编码器、`AudioEncoderOpusImpl` 是 Google 维护的高质量实现，FEC 对弱网还更友好。
- 代价：软件 Opus 编码，CPU 开销比硬件编码高。
- 实现位置：`server/src/main/cpp/scrcpy_pcm_audio_source.h`

### 方案 A：MediaCodec Opus passthrough（待实现，CPU 敏感场景备选）

实现 `webrtc::AudioEncoder` 子类，`EncodeImpl(rtp_timestamp, pcm, encoded)` 里忽略 PCM、把预编码 Opus 写进 `encoded`，经 `AudioEncoderFactory` + `AudioEncoderFactoryFactory`（返回 native 指针）注册。

- 坑：libwebrtc 每 10ms 调一次 `Encode()`，而 scrcpy 的 MediaCodec Opus 默认 20ms 帧 → 需要每 2 次 `Encode()` 发一个包（空调用返回 `encoded_bytes=0, send_even_if_empty=false`），并保证 `encoded_timestamp` 对齐。
- 优点：硬件 Opus 编码，CPU 开销低，适合 50 路并发场景。
- 切换机制：通过 `Options` 配置项选择音频方案，`Server.java` 根据配置创建不同的音频处理链路。

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

> 详细实现状态和当前待办见第 11 节。

| 阶段 | 交付物 | 状态 |
|---|---|---|
| P0 | `Streamer` → `PacketSink` 接口拆分 + `SocketStreamer` 回归 | ✅ 已完成 |
| P1 | `WebRtcPacketSink` + 视频 passthrough encoder（C++）+ `NativeEncoderBridge` JNI | ✅ 已完成 |
| P2 | 音频：PCM 直喂 libwebrtc 音频 track | ✅ 已完成 |
| P2+ | 音频：Opus passthrough（方案 A，配置切换） | ❌ 待实现 |
| P3 | 控制通道 DataChannel（含输入映射） | ✅ 已完成 |
| P3+ | 动态码率（SetRates→setParameters）+ PLI→关键帧 + 6 档阶梯 | ✅ 已完成 |
| P4 | 信令 + ICE + NAT 穿越 + 会话准入 | ✅ 已完成 |
| P4+ | Android 原生客户端 | ❌ 待实现 |
| P5 | 会话调度 + 计费 | ❌ 下一迭代 |

## 9. 决策点（2026-09-24 更新）

1. **采集层**：✅ 系统应用 + **ScreenCapture/NewDisplayCapture**（~~MediaProjectionManager~~ 已放弃，见第 10 节）。
2. **音频方案**：✅ **双方案并存可切换**——PCM 直喂（已实现，默认）+ Opus passthrough（待实现，CPU 敏感场景备选）。
3. **C++ 工程**：✅ 接受，libwebrtc 用 `google-webrtc` 官方源码构建（含 C++ 头文件）。
4. **自适应阶梯**：✅ **6 档**（实际实现参数），不对齐原 spec 的 5 档。
5. **兼容路径**：✅ **DesktopConnection + FakeContext 保留**，WebRTC 稳定后清理。
6. **会话调度/计费**：✅ **后置到下一迭代**，当前聚焦串流质量。
7. **客户端**：✅ Web 客户端（已实现）+ **Android 原生客户端（现在实现）**。
8. **多客户端**：✅ 一个云机实例 ~10 个客户端，多 PC + 房间模型 + 单点独占控制 + 码率取 min。
9. **Simulcast**：✅ **不做**，后续按需引入。
10. **NAT 穿越**：✅ **SDP rewriting**（无 TURN），端口映射重写，2026-09-29 验证通过（见第 14 节）。

## 10. 系统应用化（采集层改造）

> **2026-09-24 更新**：`MediaProjectionCapture` 已放弃（见下方"放弃说明"）。实际使用 `ScreenCapture`/`NewDisplayCapture`，在云手机自有 ROM 下已有系统权限，能直调 GPU 渲染。`ServerService` + `AndroidManifest.xml` 已实现。

### 现状 → 目标（实际实现）

| 维度 | 原 scrcpy 现状 | 实际实现 |
|---|---|---|
| 打包 | `app_process` 跑裸 JAR（classes.dex） | platform 签名的真 APK |
| Manifest | 空 `<manifest />` | 完整（application + service + 权限） |
| 入口 | `Server.main(args)` | `ServerService`（真 Context 载体） |
| 进程身份 | shell UID 2000（`FakeContext` 伪装 `com.android.shell`） | 系统应用 UID + signature\|privileged 权限 |
| 采集 | 反射 `DisplayManagerGlobal.createVirtualDisplay` | **ScreenCapture/NewDisplayCapture**（SurfaceControl API） |
| 部署 | `adb push` + `app_process` | `/system/priv-app/` + init.rc 自启 |

### 已实现的改造点

**① `AndroidManifest.xml`**（从空变完整）

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.genymobile.scrcpy">
    <uses-permission android:name="android.permission.CAPTURE_VIDEO_OUTPUT" />
    <application android:label="scrcpy-server">
        <service android:name=".ServerService" android:exported="false" />
    </application>
</manifest>
```

**② 已实现 `ServerService.java`**（真 Context 载体）

```java
public class ServerService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> Server.scrcpy(this /* Context */, options)).start();
        return START_NOT_STICKY;
    }
}
```

### ~~③ MediaProjectionCapture~~（已放弃）

**放弃说明**（2026-09-24 确认）：

- 云手机自有 ROM 下，`ScreenCapture`/`NewDisplayCapture`（SurfaceControl API）已有系统权限，能直调 GPU 渲染，无需 MediaProjection
- MediaProjection 方案的价值是「规范化 + 反检测友好」，但实际反作弊收益有限（虚拟显示环境本身仍可被检测），且需维护额外的隐藏 API 适配
- 文档 `docs/streaming-fixes.md` 已明确记录："删除未连接和未验证的 MediaProjectionCapture"，"维护 ScreenCapture/NewDisplayCapture 路线"

### 部署链

1. 云手机 ROM 的 `platform.pk8` / `platform.x509.pem` 签名 APK
2. 装入 `/system/priv-app/ScrcpyServer/ScrcpyServer.apk`
3. init.rc 自启（或 `am startservice`）
4. 每台云手机实例 = 一个独立多开用户/容器内跑一个 `ServerService`

## 11. 实施优先级（2026-09-24 更新）

> 依赖方向：P0 → P2 → P3；P1 与 P0/P2 无耦合，可并行；P4 依赖 P2 完成。

### 实现状态总览

| 阶段 | 状态 | 说明 |
|---|---|---|
| P0 前置准备 | ✅ 已完成 | libwebrtc 自编译完成，部署脚本就绪 |
| P1 scrcpy 纯重构 | ✅ 已完成 | PacketSink 接口拆分、ServerService、Manifest |
| P2 WebRTC 集成 | ⚠️ 部分完成 | 视频 ✅、PCM 音频 ✅、DataChannel ✅；Opus passthrough 待实现 |
| P3 弱网核心 | ✅ 已完成 | 动态码率 ✅、PLI ✅、6 档阶梯 ✅ |
| P4 平台 | ✅ 已完成 | 信令 ✅、SDP rewriting NAT 穿越 ✅、Web 客户端 ✅；会话调度/计费后置、Android 客户端待实现 |

### 当前待实现（本轮工作）

| 任务 | 交付物 | 验证标准 |
|---|---|---|
| **Opus passthrough 音频编码器** | `ScrcpyOpusAudioEncoder`（C++ `webrtc::AudioEncoder` 子类）+ 配置切换 | 配置切换后浏览器出声，CPU 占用低于 PCM 方案 |
| **Android 原生客户端** | `android-client/` 目录，`org.webrtc` + DataChannel + 信令 WebSocket | 全链路端到端可用，功能对标 Web 客户端 |
| **多客户端串流（1:N）** | `PeerConnectionManager` + `ControlManager` + 信令房间模型 + 控制权协议 | 10 个客户端同时串流 + 控制权排队切换（见第 13 节） |

### 后置到下一迭代

| 任务 | 说明 |
|---|---|
| 会话调度 + 计费 | 实例分配 + 按串流时长计费，峰值 50 实例 |
| DesktopConnection + FakeContext 清理 | WebRTC 稳定后删除 TCP 兼容路径 |

### 历史阶段详情（已完成）

#### P0 前置准备（✅ 已完成）

| 任务 | 交付物 | 验证标准 |
|---|---|---|
| 自编译 libwebrtc | `google-webrtc` 源码构建产物（C++ 头文件 + native `.so`） | `webrtc_materials/` 目录完整 |
| redroid 部署验证 | ARM 服务器 + N 容器部署脚本 | `p4/deploy.sh` 可执行 |

#### P1 scrcpy 纯重构（✅ 已完成）

| 任务 | 交付物 | 验证标准 |
|---|---|---|
| `Streamer` → `PacketSink` 接口拆分 | `PacketSink` 接口 + `Streamer` 保留 | 现有 TCP 模式单元测试全绿 |
| 系统应用化 | `AndroidManifest.xml` + `ServerService` | system APK 可部署 |

#### P2 WebRTC 集成（⚠️ 部分完成）

| 任务 | 状态 | 交付物 |
|---|---|---|
| 视频 C++ passthrough | ✅ | `ScrcpyPassthroughEncoder` + `NativeEncoderBridge` JNI |
| 音频 PCM 直喂 | ✅ | `PcmAudioSource` (C++) |
| 音频 Opus passthrough | ❌ 待实现 | `ScrcpyOpusAudioEncoder` (C++) |
| 控制通道 DataChannel | ✅ | `DataChannelInputStream/OutputStream` + `ControlChannel` 改造 |

#### P3 弱网核心（✅ 已完成）

| 任务 | 交付物 |
|---|---|
| 动态码率 | `SetRates`→JNI→`SurfaceEncoder.applyFeedback()`→`setParameters(BITRATE)` |
| PLI→关键帧 | `kVideoFrameKey`→JNI→`setParameters(SYNC_FRAME)` |
| 6 档阶梯 | `BitrateLadder.java` + `CaptureControl.reset` 跨档切换 |

#### P4 平台（⚠️ 部分完成）

| 任务 | 状态 | 交付物 |
|---|---|---|
| 信令服务器 | ✅ | `SignalServer.java` + `SessionAdmission.java` |
| NAT 穿越 | ✅ | SDP rewriting（`SdpRewriter.java` + `PortMapConfig.java`），无 TURN |
| Web 客户端 | ✅ | `p4/web/`（TypeScript/Vite） |
| 会话调度 + 计费 | ❌ 后置 | — |
| Android 原生客户端 | ❌ 待实现 | — |

## 12. 完整技术架构图

### 端到端系统架构（总览）

```
┌──────────────────────────────────────────────────────────────────────────┐
│                              客户端层                                      │
│   ┌─────────────────────┐                 ┌──────────────────────┐        │
│   │      Web 浏览器      │                 │    Android 原生 App   │        │
│   │  RTCPeerConnection   │                 │    org.webrtc 库      │        │
│   │  + DataChannel       │                 │    + DataChannel      │        │
│   │  ✅ 已实现            │                 │    ❌ 待实现            │        │
│   └──────────┬──────────┘                 └──────────┬───────────┘        │
│              └── 输入映射(触屏/键鼠 → 23种控制消息字节流) ──┘                │
└──────────────┼─────────────────────────────────────────┼──────────────────┘
               │        WebRTC(SRTP) + DataChannel        │
               │         + WebSocket(信令)                 │
┌──────────────▼─────────────────────────────────────────▼──────────────────┐
│                          中心控制平面（B2C 平台）                            │
│  ┌───────────────┐   ┌──────────────┐   ┌────────────────────────────┐    │
│  │  信令服务器     │   │  SDP Rewriter │   │  会话调度 + 计费（后置）      │    │
│  │  (WebSocket)   │   │  (NAT 穿越)   │   │  · 实例注册 + 心跳健康检查    │    │
│  │  offer/answer  │   │  端口映射重写  │   │  · 用户 → 空闲实例分配       │    │
│  │  ICE 候选交换   │   │  ✅ 已实现    │   │  · 串流时长计费              │    │
│  │  ✅ 已实现      │   │  (无 TURN)   │   │  ❌ 下一迭代                 │    │
│  └───────────────┘   └──────────────┘   └────────────────────────────┘    │
└──────────┬───────────────────┬──────────────────────┬─────────────────────┘
           │ P2P 直连           │  SDP rewriting        │ 信令/控制
           ▼                   ▼                      ▼
    ┌──────────────┐    ┌──────────────┐         ┌──────────────┐
    │  云机实例 #1  │    │  云机实例 #2  │   ···   │  云机实例 #50 │   ← 峰值 50
    └──────────────┘    └──────────────┘         └──────────────┘
           ▲  ←────── 一台 ARM 服务器（3×GPU + VPU，可增减）──────→  ▲
```

### 单台云机内部（scrcpy-server 改造，核心）

```
┌─────────────────────────────────────────────────────────────────────────┐
│            定制 AOSP Android 实例（ScreenCapture 直调 GPU）                │
│  ┌──────────────────────────────────────────────────────────────┐      │
│  │      Android 应用层：轻量 2D 挂机（三角洲=性能 benchmark）      │      │
│  └──────────────────────────────────────────────────────────────┘      │
│  ┌────────── scrcpy-server（系统应用 · 改造后 · C++ 路线）────────┐      │
│  │  采集层(复用)         编码层(复用)            传输层(改造/新增)    │      │
│  │  ┌───────────────┐  ┌───────────────────┐  ┌────────────────┐ │      │
│  │  │ScreenCapture / │  │ SurfaceEncoder     │  │  WebRtcPacket   │ │      │
│  │  │NewDisplay      │→│ (MediaCodec        │→│  Sink           │ │      │
│  │  │Capture         │  │  H.264 硬编码      │  │  ·SPS/PPS 前置   │ │      │
│  │  │·SurfaceControl │  │  ← VPU 硬件编解码   │  │  ·kNative 包装   │ │      │
│  │  │·直调 GPU 渲染   │  │                   │  │                │ │      │
│  │  └───────────────┘  └───────────────────┘  └───────┬────────┘ │      │
│  │  ┌───────────────┐  ┌───────────────────┐          │          │      │
│  │  │AudioPlayback   │→│ AudioRawRecorder   │→ PCM 直喂       │      │
│  │  │Capture(内录)   │  │ (PCM 48kHz/2ch)   │  PcmAudioSource  │      │
│  │  └───────────────┘  └───────────────────┘  (或 Opus pass.)  │      │
│  │  ┌──────────────────────────────────────────────────────────┐│      │
│  │  │         libwebrtc (google-webrtc 源码构建 · C++ 层)        ││      │
│  │  │  EncodedVideoFrameBuffer(kNative) → VideoTrackSource      ││      │
│  │  │    → ScrcpyPassthroughEncoder(webrtc::VideoEncoder)       ││      │
│  │  │      → OnEncodedImage → H.264 packetizer → RTP            ││      │
│  │  │  PcmAudioSource → libwebrtc Opus → RTP                    ││      │
│  │  │  (或 ScrcpyOpusAudioEncoder → RTP)                        ││      │
│  │  │  PeerConnection (ICE/DTLS/SRTP)                           ││      │
│  │  └──────────────────────────────┬───────────────────────────┘│      │
│  │  ┌───────────────┐    ┌──────────▼───────────┐               │      │
│  │  │  Controller    │←── │  DataChannel(控制)    │               │      │
│  │  │ (控制分派,复用) │    │  23种消息字节流原样搬移 │               │      │
│  │  └───────────────┘    └──────────────────────┘               │      │
│  └──────────────────────────────────┬───────────────────────────┘      │
│                                      │  SRTP / ICE                       │
│                          P2P 直连（SDP rewriting NAT 穿越）                 │
└──────────────────────────────────────┼───────────────────────────────────┘
```

### 三条数据流 + 延迟预算

视频流（下行）：ScreenCapture/NewDisplayCapture(直调GPU渲染 10~16ms) → MediaCodec H.264 硬编码(VPU 5~15ms) → PacketSink → kNative 包装 → ScrcpyPassthroughEncoder → H.264 packetizer → RTP → 网络(10~50ms) → JitterBuffer(0~20ms) → 硬件解码+显示(10~30ms) = 端到端 35~130ms。

音频流（下行，方案 B 默认）：AudioPlaybackCapture(内录) → 48kHz PCM → PcmAudioSource(C++) → libwebrtc 内置 Opus 编码 → RTP。
音频流（下行，方案 A 备选）：AudioPlaybackCapture(内录) → MediaCodec Opus → ScrcpyOpusAudioEncoder(C++) → RTP。

控制流（双向）：用户输入 → 23种 ControlMessage 字节流 → DataChannel → ControlMessageReader → Controller（复用）。

### 弱网自适应闭环（6 档阶梯）

档1 1080p60@12M / 档2 900p60@8M / 档3 720p60@6M / 档4 720p30@4M / 档5 540p30@2M / 档6 480p24@1.2M。降档触发：SetRates → setParameters(BITRATE)（档内）/ CaptureControl.reset()（跨档）。PLI → Encode(frame_types=kVideoFrameKey) → requestKeyframe → SYNC_FRAME。

### 复用/改造/新增/删除边界（2026-09-24 更新）

复用：SurfaceEncoder、AudioEncoder/AudioRawRecorder、Controller、ControlMessage协议、AsyncProcessor、ScreenCapture/NewDisplayCapture。改造：Streamer→PacketSink、ControlChannel、Manifest、Server.scrcpy。新增：ServerService、ScrcpyPassthroughEncoder(C++)、PcmAudioSource(C++)、ScrcpyOpusAudioEncoder(C++ 待实现)、NativeEncoderBridge(JNI)、DataChannel适配器、SignalServer、SdpRewriter、PortMapConfig。保留（后续清理）：DesktopConnection、FakeContext。放弃：MediaProjectionCapture、TURN/coturn（SDP rewriting 替代）。

---

## 13. 多客户端串流（1:N，2026-09-24 新增）

> 一个云机实例同时支持 ~10 个客户端（H5 + Android 原生）同时串流。

### 13.1 架构总览

```
                         ┌──────────────────────────────────┐
                         │         云机实例（Android）         │
                         │                                    │
                         │  ScreenCapture → MediaCodec H.264  │
                         │         ↓ 编码帧                    │
                         │  ┌─────────────────────────────┐   │
                         │  │   PeerConnectionManager      │   │
                         │  │   · 维护 N 个 PeerConnection  │   │
                         │  │   · 编码流广播到所有 PC        │   │
                         │  │   · 码率取 min(所有 PC)       │   │
                         │  └──────┬──────┬──────┬────────┘   │
                         │         │      │      │             │
                         │  ┌──────┴──────┴──────┴────────┐   │
                         │  │   ControlManager             │   │
                         │  │   · 控制权锁（单点独占）       │   │
                         │  │   · 排队请求                   │   │
                         │  │   · N 个独立 DataChannel       │   │
                         │  └──────────────────────────────┘   │
                         └──────────┬──────────────────────────┘
                                    │ WebRTC (SRTP/ICE)
               ┌────────────────────┼────────────────────┐
               │                    │                    │
          ┌────┴────┐         ┌────┴────┐         ┌────┴────┐
          │ 客户端 1  │         │ 客户端 2  │   ...   │ 客户端 10│
          │ H5/Android│        │ H5/Android│        │ H5/Android│
          └─────────┘         └─────────┘         └─────────┘
```

### 13.2 决策汇总

| 维度 | 决策 |
|---|---|
| 拓扑 | 一个云机实例同时服务 ~10 个客户端（可配置） |
| 视频分发 | 多 PeerConnection（1 个 scrcpy-server 维护 N 个 PC） |
| 画质 | 所有客户端完全相同，编码器只输出一路 |
| 码率策略 | 取所有客户端带宽估计的**最小值** |
| 控制模式 | 单点独占 + 排队请求 |
| 信令模型 | 房间模型（room ID + token） |
| 客户端类型 | H5 + Android 原生 |
| Simulcast | **不做**（后续按需引入） |

### 13.3 核心改造点

#### ① PeerConnectionManager（新增，C++）

```cpp
class PeerConnectionManager {
 public:
  // 创建新的 PeerConnection，加入房间
  scoped_refptr<PeerConnectionInterface> CreatePeerConnection(
      const std::string& client_id, PeerConnectionObserver* observer);

  // 移除客户端
  void RemovePeer(const std::string& client_id);

  // 广播编码帧到所有 PeerConnection
  void BroadcastVideoFrame(const uint8_t* annexb, size_t len,
                           int64_t pts_us, bool keyframe,
                           int width, int height);

  // 广播 PCM 音频到所有 PeerConnection
  void BroadcastAudio(const int16_t* pcm, size_t samples, int sample_rate);

  // 码率聚合：取所有 PC 带宽估计的最小值
  int GetAggregateBitrate() const;

  // 当前连接数
  size_t PeerCount() const;

  // 最大连接数（可配置，默认 10）
  void SetMaxPeers(int max);

 private:
  std::map<std::string, scoped_refptr<PeerConnectionInterface>> peers_;
  int max_peers_ = 10;
};
```

关键点：
- 每个 PC 有独立的 `ScrcpyPassthroughEncoder` 实例（共享同一编码帧数据）
- 每个 PC 有独立的 ICE/DTLS/SRTP 通道
- 视频帧通过 `BroadcastVideoFrame` 广播到所有 PC 的 `EncodedVideoTrackSource`
- 音频 PCM 通过 `BroadcastAudio` 广播到所有 PC 的 `PcmAudioSource`

#### ② 码率聚合

```
PC1 SetRates → bitrate_1 = 5 Mbps
PC2 SetRates → bitrate_2 = 8 Mbps
PC3 SetRates → bitrate_3 = 2 Mbps  ← 最弱
...
PC10 SetRates → bitrate_10 = 6 Mbps

PeerConnectionManager::GetAggregateBitrate() = min(2, 5, 6, 8, ...) = 2 Mbps
→ MediaCodec.setParameters(PARAMETER_KEY_VIDEO_BITRATE, 2_000_000)
```

所有 PC 共享同一编码流，码率由最弱客户端决定。

#### ③ ControlManager（新增，Java）

```java
public class ControlManager {
    // 当前控制者 client_id（null = 无人控制）
    private String currentController;

    // 等待队列（FIFO）
    private final Deque<String> controlQueue = new ArrayDeque<>();

    // 所有客户端的 DataChannel 映射
    private final Map<String, DataChannel> dataChannels = new HashMap<>();

    // 请求控制权
    public synchronized void requestControl(String clientId) {
        if (currentController == null) {
            currentController = clientId;
            broadcastControlState();
        } else if (!clientId.equals(currentController)) {
            controlQueue.addLast(clientId);
            notifyQueuePosition(clientId);
        }
    }

    // 释放控制权
    public synchronized void releaseControl(String clientId) {
        if (clientId.equals(currentController)) {
            currentController = controlQueue.pollFirst();
            broadcastControlState();
        } else {
            controlQueue.remove(clientId);
        }
    }

    // 转发控制消息（仅当前控制者的消息被转发）
    public void forwardControlMessage(String clientId, ByteBuffer data) {
        if (clientId.equals(currentController)) {
            controller.handleEvent(data);  // 转发到 Android Controller
        }
        // 非控制者的消息被丢弃
    }

    // 广播控制权状态到所有客户端
    private void broadcastControlState() {
        // 发送 CONTROL_STATE 消息到所有 DataChannel
        // 内容：{ controller: clientId, queue: [clientId1, clientId2, ...] }
    }
}
```

#### ④ 信令扩展（房间模型）

```
客户端连接 WebSocket → 发送 { type: "join", room: "instance-001", token: "xxx" }
  → SignalServer 验证 token
  → 检查房间内 PC 数量 < max_peers
  → 分配 client_id
  → 创建 PeerConnection
  → 开始 offer/answer 流程
  → 加入控制队列（初始为观看者）

客户端断开 → 移除 PeerConnection → 如果该客户端是控制者，自动转给队列下一个
```

#### ⑤ 新增控制消息类型

| 消息 | 方向 | 说明 |
|---|---|---|
| `REQUEST_CONTROL` | client→server | 请求控制权 |
| `RELEASE_CONTROL` | client→server | 释放控制权 |
| `CONTROL_STATE` | server→client | 广播控制权状态（当前控制者 + 队列） |

### 13.4 客户端 UI 状态

| 状态 | 显示 | 可操作 |
|---|---|---|
| 观看中 | "观看中 · 队列第 N 位" | 请求控制按钮 |
| 控制中 | "你在控制" | 触控/按键有效，释放控制按钮 |
| 等待中 | "等待控制权..." | 取消请求按钮 |

### 13.5 明确不做

- **Simulcast 多质量层**：后续按需引入（取决于 VPU 多路编码能力）
- **SFU 中继**：当前多 PC 方案，后续如带宽瓶颈可引入
- **会话调度/计费**：已后置到下一迭代

### 13.6 验证标准

| 场景 | 预期 |
|---|---|
| 10 个 H5 客户端同时连接 | 全部收到视频流，画质相同 |
| 1 个客户端断网 | 其余 9 个不受影响，码率可能上升（min 值变大） |
| 客户端 A 请求控制 → B 也请求 | A 先获得，B 排队 |
| A 释放控制 | B 自动获得控制权 |
| 控制者断网 | 控制权自动转给队列下一个 |
| 10 个客户端 + 1 个尝试连接 | 第 11 个被拒绝（房间满） |

---

## 14. NAT 穿越：SDP Rewriting（2026-09-29 新增，已验证）

> 容器化部署场景下，WebRTC 媒体端口通过端口映射暴露到公网。服务端在发送 ICE candidate 前重写 SDP，将内网 IP:端口替换为公网 IP:映射端口，实现无 TURN 的 P2P 直连。

### 14.1 背景与决策

**问题**：云手机运行在 Docker 容器内（内网 192.168.0.x），通过端口映射对外暴露服务。WebRTC ICE 协商产生的 candidate 是容器内网地址，浏览器（外网）无法直连。

**方案选择**：

| 方案 | 优点 | 缺点 | 决策 |
|---|---|---|---|
| TURN/coturn 中继 | 标准化，NAT 穿透率最高 | 额外服务器成本、中继延迟、带宽瓶颈 | ❌ 放弃 |
| SDP rewriting | 零额外基础设施、零中继延迟、P2P 最低延迟 | 需要已知端口映射关系 | ✅ **采用** |

**决策依据**：端口映射是固定的（容器编排时确定），SDP rewriting 只需在信令层做字符串替换，无需 TURN 中继开销。

### 14.2 端口映射方案

```
容器端口          公网 IP 端口              用途
────────          ──────────────            ────
10000             183.240.23.14:20614       信令 WebSocket (TCP)
10001             183.240.23.14:20615       WebRTC 媒体 (UDP) min
10002             183.240.23.14:20616       WebRTC 媒体 (UDP)
10003             183.240.23.14:20617       WebRTC 媒体 (UDP)
10004             183.240.23.14:20618       WebRTC 媒体 (UDP) max
```

偏移公式：`externalPort = 20615 + (localPort - 10001)`

### 14.3 实现细节

#### ① `PortMapConfig.java`（端口映射配置）

```java
public final class PortMapConfig {
    private final String publicIp;
    private final int localMinPort;    // 10001
    private final int localMaxPort;    // 10004
    private final int externalMinPort; // 20615
    private final int externalMaxPort; // 20618

    public int getExternalPort(int localPort) {
        if (localPort < localMinPort || localPort > localMaxPort) return 0;
        int offset = localPort - localMinPort;
        int externalPort = externalMinPort + offset;
        if (externalPort > externalMaxPort) return 0;
        return externalPort;
    }
}
```

#### ② `SdpRewriter.java`（ICE candidate 重写）

ICE candidate SDP 格式：`candidate:<foundation> <component> <transport> <priority> <address> <port> typ <type> ...`

- tokens[4] = IP 地址（如 `192.168.0.113`）
- tokens[5] = 端口号（如 `10002`）

```java
public static String rewriteIceCandidate(String sdp, PortMapConfig config) {
    if (config == null || !config.isEnabled()) return sdp;
    if (sdp == null || !sdp.contains("typ host")) return sdp;

    String[] tokens = sdp.split(" ");
    if (tokens.length < 6) return sdp;

    String localIp = tokens[4];
    String localPortStr = tokens[5];
    int localPort = Integer.parseInt(localPortStr);
    int externalPort = config.getExternalPort(localPort);
    if (externalPort == 0) return sdp;

    String publicIp = config.getPublicIp();
    String result = sdp;
    result = result.replace(localIp, publicIp);
    result = result.replace(localPortStr, String.valueOf(externalPort));
    return result;
}
```

#### ③ `SignalServer.java` 集成

```java
public void sendIceCandidate(String roomId, String clientId, String mid, int index, String sdp) {
    String rewritten = SdpRewriter.rewriteIceCandidate(sdp, portMapConfig);  // ← 重写
    sendMessage(roomId, clientId, "ice", builder -> {
        builder.put("sdpMid", mid).put("sdpMLineIndex", index).put("candidate", rewritten);
    });
}
```

#### ④ C++ 侧配合

- 移除 `kRelay` ICE 策略（允许 host candidate）
- `set_min_port(10001)`（原为 10002），确保所有媒体端口可用

### 14.4 数据流

```
1. 浏览器连接 ws://183.240.23.14:20614 → 容器:10000（信令 WebSocket）
2. offer/answer 交换（SDP 中 ICE candidate 为内网地址）
3. 服务端收到本地 ICE candidate（如 192.168.0.113:10002）
4. SdpRewriter 重写为 183.240.23.14:20616
5. 服务端发送重写后的 candidate 给浏览器
6. 浏览器向 183.240.23.14:20616 发 STUN binding request
7. 端口映射转发到容器 192.168.0.113:10002
8. ICE 连通，WebRTC P2P 建立
```

### 14.5 验证结果（2026-09-29）

- **环境**：浏览器（10.20.8.151）↔ 容器（192.168.0.113）
- **结果**：视频 + 音频 + DataChannel 全部串流成功
- **延迟**：P2P 直连，无 TURN 中继开销
- **结论**：SDP rewriting 方案可行，生产环境无需 TURN

### 14.6 配置参数

启动脚本 `p4/start-server.sh` 新增参数：

```bash
PUBLIC_IP=183.240.23.14
LOCAL_MIN_PORT=10001
LOCAL_MAX_PORT=10004
EXTERNAL_MIN_PORT=20615
EXTERNAL_MAX_PORT=20618
```

通过 `Options` 传递到 `SignalServer`，构造 `PortMapConfig`。

### 14.7 局限性与后续

- **当前限制**：仅重写服务端 → 浏览器方向的 ICE candidate。浏览器 → 服务端方向依赖浏览器的 candidate 能被容器访问（当前测试环境可行，因浏览器在内网）。
- **生产环境**：如浏览器也在公网，可能需要双向 rewriting 或 STUN 穿透。
- **TURN 保留**：配置文件仍保留，作为极端 NAT 环境的兜底（当前未启用）。
