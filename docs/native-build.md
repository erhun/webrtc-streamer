> 当前实现与验证边界见 [streaming-fixes.md](streaming-fixes.md)。本文保留历史设计/调试记录，不能代替当前版本验收。

# Native 编译与部署（libwebrtc C++ passthrough）

> 本文记录 scrcpy-server 集成 libwebrtc（m150）C++ passthrough 的**完整可复现**流程：
> 工具链、编译、静态链接、系统应用化部署，以及所有踩过的坑。
>
> 最终状态：`libscrcpy_native.so` 在 arm64 Android 12 模拟器上以**系统应用（system UID）**方式运行，
> 端到端 WebRTC 信令（offer → answer，视频 H264 + 音频 Opus）验证通过。

---

## 1. 工具链

| 组件 | 路径 | 说明 |
|---|---|---|
| clang 24 | `/Users/gorilla/test/webrtc/clang-llvmorg-24-init-3796-g20e97c4b-2/bin/clang++` | **必须**与 libwebrtc 编译版本一致 |
| clang 24 lld | 同目录 `bin/ld.lld` | 链接器 |
| NDK 28.2 | `/Users/gorilla/Library/Android/sdk/ndk/28.2.13676358` | 只用它的 sysroot + builtins + libunwind |
| sysroot | `$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot` | |

**关键：不能用 Homebrew 的 clang 22。** libwebrtc m150 由 clang 24（`llvmorg-24-init-3796-g20e97c4b-2`）编译，
用了 clang 24 专属的 ABI 特性（`abi:nqn`、relative vtable），clang 22 会运行时崩溃（详见 §7 踩坑 #1/#2）。

clang 版本确认：
```bash
$CLANG --version
# clang version 24.0.0git (https://chromium.googlesource.com/a/external/github.com/llvm/llvm-project 20e97c4b113b79586c29ac57738548939a34d22e)
```

---

## 2. libwebrtc 产物位置

| 产物 | 路径 |
|---|---|
| 头文件树 | `webrtc_materials/include/`（含 `p2p/` `net/` `modules/` 已补齐） |
| abseil 头文件 | `webrtc_materials/include/third_party/abseil-cpp/` |
| `__Cr` libc++ 头文件 | `webrtc_materials/third_party/libc++/src/include` |
| libc++ 配置 | `webrtc_materials/buildtools/third_party/libc++`（`__config_site`） |
| 聚合静态库 | `webrtc_materials/static_libs/obj/libwebrtc.a` |
| 第三方 thin 库 | `webrtc_materials/static_libs/obj/third_party/`（659 个 `.a`） |
| buildtools thin 库 | `webrtc_materials/static_libs/obj/buildtools/` |
| Java 绑定（未用） | `webrtc_materials/libwebrtc.aar` |

---

## 3. 编译服务器：构建和导出 JNI

JNI 与 APK 分离构建。只有编译服务器需要匹配 libwebrtc 的工具链、NDK sysroot 和静态库。
本服务的 Gradle 不编译、不下载 JNI，也不依赖 `WEBRTC_ROOT`、`CLANG` 等环境变量。
当前使用内置 Opus 编码器；C++ 源码由构建脚本扫描。

编译服务器必须检出与 APK 服务匹配的 native 源码、Java JNI 桥和构建脚本。
请先按第 5 节配置编译器运行时；以下路径为历史机器示例，替换为实际位置：

```bash
export CLANG=/Users/gorilla/test/webrtc/clang-llvmorg-24-init-3796-g20e97c4b-2/bin/clang++
export CLANG_LD="$(dirname "$CLANG")/ld.lld"
export ANDROID_SYSROOT=/Users/gorilla/Library/Android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/sysroot
export WEBRTC_ROOT="$PWD/webrtc_materials"
# 填写生成静态库的真实 libwebrtc 源码提交号，不是本应用的提交号。
export WEBRTC_REVISION='<libwebrtc 源码提交号>'
python3 server/tools/build_native.py
python3 server/tools/native_bundle.py export --output /path/to/scrcpy-native-arm64.zip
```

构建脚本使用 `--no-undefined`，生成 `.so` 与 `native-build.json`。编译期间源码变化会使构建失败，
不发布该次产物。导出前再次校验源码与产物；ZIP 只包含 `libscrcpy_native.so` 和 `native-build.json`。
通过现有 CI 制品库或文件传输渠道把 ZIP 交付给 APK 服务。两端工作目录可以不同，清单只使用仓库相对路径。

## 4. APK 服务：导入、校验和部署

在本服务检出对应代码后，从编译服务器取得上述 ZIP，再运行：

```sh
python3 server/tools/native_bundle.py import /path/to/scrcpy-native-arm64.zip
python3 server/tools/prepare_native.py --check
./gradlew -p server assembleDebug
```

导入工具先验证成员名称、数量、大小、清单格式、源码文件集合及所有 SHA-256，验证失败不覆盖现有产物。
验证通过后写入：

- `server/src/main/jniLibs/arm64-v8a/libscrcpy_native.so`
- `server/native-build.json`

也可手动将编译服务器输出的这两个文件放到相同路径，再运行校验。
不能仅把旧 `.so` 放进新源码目录，也不能在消费端根据旧 `.so` 重新生成清单。
SHA-256 校验用于一致性检查；制品应通过可信的编译服务器和交付渠道获取。

`merge*NativeLibs` 依赖 `prepareNativeLibrary`，只验证预编译产物，失败时提示获取匹配的编译服务器制品。
即使设置了 native 工具链环境变量也不会触发 JNI 编译。合并库前还会再次核对哈希。
导入时的两个文件替换不构成文件系统事务；请先完成导入再启动打包。中断造成的不匹配会被打包校验拦截，重新导入即可。

`p4/deploy.sh` 和 `p4/start-server.sh` 是被 Git 忽略的本地脚本，仓库不能保证其构建和启动流程。
使用本地 `deploy.sh --build` 时，先导入匹配制品，再执行原命令即可；本服务无需配置 native 工具链。
不要让本地脚本在校验后再用 `tmp/native` 中的旧 `.so` 覆盖 APK 内的库。

### `Stale native library` 的处理

该报错表示头文件、C++ 源码、Java JNI 桥或产物与 `server/native-build.json` 不一致。
例如报错提到 `scrcpy_peer_connection.h`，说明清单记录的头文件与当前检出内容不同。
本次重构修改过 JNI 方法签名，旧库存在参数不兼容风险，不能绕过校验继续发布。

请让编译服务器使用匹配的代码重新编译并导出 ZIP，然后在本服务导入，再执行原部署命令。
如果编译服务器已经生成了匹配产物，只需重新交付和导入，无需重复编译。
删除清单、执行 `gradle clean`、手改哈希或关闭门禁都不能修复源码与二进制的不一致。
仅凭一个未附原始构建清单的 `.so`，本服务无法确认其对应源码。

`SIGNAL_TOKEN=""` 与 native 哈希报错无关。当前生产模式要求至少 32 字符的高熵凭证。
空凭证只允许显式启动 `signal_debug=true` 的隔离调试；不能假定本地部署脚本支持同名环境变量。
如果使用随机凭证，应通过受控方式交给 H5，不写入 URL 或日志。

---

## 5. clang 24 的 builtins + libunwind symlink（一次性）

clang 24 自己的 resource 目录没有 Android 的 builtins/libunwind，需 symlink 到 NDK：

```bash
CLANG_LIB=/Users/gorilla/test/webrtc/clang-llvmorg-24-init-3796-g20e97c4b-2/lib/clang/24/lib/aarch64-unknown-linux-android23
RTLIB="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/lib/clang/19/lib/linux"

mkdir -p "$CLANG_LIB"
ln -sf "$RTLIB/libclang_rt.builtins-aarch64-android.a" "$CLANG_LIB/libclang_rt.builtins.a"
ln -sf "$RTLIB/aarch64/libunwind.a" "$CLANG_LIB/libunwind.a"
```

---

## 6. 系统应用化部署（模拟器）

### 6.1 签名密钥

模拟器（`sdk_phone64_arm64`，`test-keys`）用的是 **AOSP 默认 platform 密钥**（公开）。下载：

```bash
mkdir -p tmp/aosp_keys && cd tmp/aosp_keys
BASE="https://raw.githubusercontent.com/aosp-mirror/platform_build/master/target/product/security"
for f in platform testkey shared media; do
  curl -sL "$BASE/${f}.pk8" -o "${f}.pk8"
  curl -sL "$BASE/${f}.x509.pem" -o "${f}.x509.pem"
done
```

验证（指纹应匹配 `SettingsProvider.apk` 的签名）：
```bash
openssl x509 -in tmp/aosp_keys/platform.x509.pem -fingerprint -sha256 -noout
# sha256 = C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8
```

### 6.2 Manifest 改造

`server/src/main/AndroidManifest.xml`：
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        android:sharedUserId="android.uid.system">
    <uses-permission android:name="android.permission.CAPTURE_VIDEO_OUTPUT" />
    <uses-permission android:name="android.permission.MANAGE_MEDIA_PROJECTION" />
    <application android:label="scrcpy-server">
        <service android:name="com.genymobile.scrcpy.ServerService" android:exported="true" />
    </application>
</manifest>
```

### 6.3 编译约束（AOSP 12）

- `server/build.gradle`：`compileSdk 31`（不是 36！基于 AOSP 12）
- `AndroidVersions.java`：API 32+ 常量**硬编码**（`compileSdk 31` 没有 `S_V2`/`TIRAMISU` 等符号）：

```java
public static final int API_31_ANDROID_12 = Build.VERSION_CODES.S;
public static final int API_32_ANDROID_12L = 32;
public static final int API_33_ANDROID_13 = 33;
public static final int API_34_ANDROID_14 = 34;
public static final int API_35_ANDROID_15 = 35;
```

### 6.4 打包 + 签名 + 部署

```bash
./gradlew -p server assembleDebug
zipalign -f 4 server/build/outputs/apk/debug/server-debug.apk tmp/apk/scrcpy-aligned.apk
apksigner sign --key tmp/aosp_keys/platform.pk8 \
               --cert tmp/aosp_keys/platform.x509.pem \
               --out tmp/apk/scrcpy-platform.apk tmp/apk/scrcpy-aligned.apk

adb root && adb remount
adb shell mkdir -p /system/priv-app/scrcpy
adb push tmp/apk/scrcpy-platform.apk /system/priv-app/scrcpy/scrcpy.apk
adb push libscrcpy_native.so /system/lib64/libscrcpy_native.so
adb reboot   # 重启让系统应用生效
```

已有平台签名 APK 和对应 SO 时，推荐使用带校验的部署脚本替代手动复制：

```bash
server/tools/deploy_system_app.sh \
    --serial "$SERIAL" \
    --apk "$TMP/apk/scrcpy-platform.apk" \
    --native "$TMP/native/libscrcpy_native.so" \
    --mode cloud
```

默认 `--strategy system` 保留系统底包部署：原子替换 APK/SO、恢复文件权限和 SELinux 标签、重启、等待新启动完成，
再验证实际生效的 APK 哈希、UID 1000、SYSTEM 和 PRIVILEGED 标记。云机需预先具备可写系统分区和 root adb 管理连接；
模拟器使用 `--mode emulator`，脚本执行 `adb root`、等待连接后再 remount。管理连接的 root 权限不会改变 APK 的系统 UID。

后续调试可加 `--strategy update`，通过 `adb install -r` 更新已识别的系统特权应用，不重启云机。
此模式要求同包名、同平台签名，以及 APK 内打包与 `--native` 完全一致的 arm64 JNI 库；它不改系统底包和全局 SO。
更新 APK 可以位于 `/data/app`，仍属于更新过的系统应用；不要因为路径在 data 就卸载整个包。
正式系统部署时，如果已有 `/data/app` 更新包，脚本先把它更新为同一份 APK，避免它覆盖新系统底包。
安装失败、校验失败和启动超时都会退出，不会静默报告成功，不使用 `pkill -f` 或删除应用数据。
脚本只部署，不自动启动串流；启动参数继续由下节的原启动命令管理。

校验脚本逻辑：`bash -n server/tools/deploy_system_app.sh` 和 `python3 tests/deploy_system_app_test.py`。
模拟测试不替代云机上的包签名、权限及媒体采集验证。

### 6.5 启动 + 验证

```bash
adb shell am startservice -n com.genymobile.scrcpy/.ServerService \
     --esa args '4.1,signal_port=10000,video_codec=h264'

# 验证 system UID + 权限
adb shell dumpsys package com.genymobile.scrcpy | grep -E 'userId=|granted'
# userId=1000  /  CAPTURE_VIDEO_OUTPUT: granted=true  /  MANAGE_MEDIA_PROJECTION: granted=true

# 验证 SignalServer 监听
adb shell ss -tlnp | grep 10000

# 验证 WebSocket 握手
adb forward tcp:10000 tcp:10000
curl -i -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
     http://localhost:10000/
# → HTTP/1.1 101 Web Socket Protocol Handshake
```

---

## 7. TURN 部署（浏览器联调必需）

浏览器在 mac 上，云机在模拟器 NAT 内网（`10.0.2.15`），媒体流（RTP）无法直连，需 TURN 中继。

### 7.1 网络拓扑

```
浏览器(mac 10.20.10.7) ──┐
                          ├──→ coturn (mac 0.0.0.0:3478, relay-ip=10.20.10.7)
云机(模拟器 10.0.2.15) ────┘     └──→ relay candidate 10.20.10.7:<port>
        │ 通过 10.0.2.2 访问 mac（模拟器 host 网关）
        │
        └── 信令 WebSocket：adb forward tcp:10000 tcp:10000
```

关键：**云机访问 TURN 用 `10.0.2.2:3478`，浏览器访问 TURN 用 `10.20.10.7:3478`**（同一个 coturn）。

### 7.2 安装 + 启动 coturn

```bash
brew install coturn
cat > tmp/turnserver.conf << 'EOF'
listening-port=3478
listening-ip=0.0.0.0
relay-ip=10.20.10.7          # ← 换成 mac 实际 IP（ipconfig getifaddr en0）
external-ip=10.20.10.7
fingerprint
lt-cred-mech
realm=scrcpy-cloud
user=turnuser:turnpassword
no-cli
verbose
EOF

/opt/homebrew/opt/coturn/bin/turnserver -c tmp/turnserver.conf
# 验证：lsof -iUDP:3478 应显示 turnserve 监听
```

### 7.3 云机侧 ICE servers（C++）

`ScrcpyPeerConnection::Initialize` 的 `RTCConfiguration` 加 TURN：

```cpp
webrtc::PeerConnectionInterface::RTCConfiguration config;
webrtc::PeerConnectionInterface::IceServer turn_server;
turn_server.uri = "turn:10.0.2.2:3478";       // 模拟器 host 网关 → mac 的 coturn
turn_server.username = "turnuser";
turn_server.password = "turnpassword";
config.servers.push_back(turn_server);
```

### 7.4 浏览器侧 ICE servers（`p4/web/index.html`）

```js
const pc = new RTCPeerConnection({
    iceServers: [
        { urls: 'turn:10.20.10.7:3478', username: 'turnuser', credential: 'turnpassword' }
    ]
});
```

### 7.5 验证 TURN relay

云机 offer/answer 后，ICE candidates 里应出现 `typ relay` 的 candidate（地址为 mac 的
`10.20.10.7:<port>`），浏览器端 `getStats` 里 `candidate-pair` 状态为 `succeeded` + `nominated=true`
即表示媒体流走 TURN 中继连通。

---

## 8. 踩坑记录（血泪史，务必读）

### #1 clang 版本必须 = libwebrtc 版本（clang 22 → 24）

libwebrtc m150 用 clang 24 编译，带 `[abi:nqn240000]`（non-qualified-name mangling）等 ABI 特性。
用 Homebrew clang 22 编译 + 链接虽然**符号级对齐**（未定义符号 = 0），但**运行时崩溃**：
`PeerConnectionFactoryDependencies` 析构时 delete libwebrtc 对象，虚析构跳进 `.text` 空洞（SIGBUS/SIGSEGV）。

**解决**：用与 libwebrtc 完全一致的 clang 24（`llvmorg-24-init-3796-g20e97c4b-2`，用户提供的 macOS 版）。

### #2 relative vtable（`-fexperimental-relative-c++-abi-vtables`）【最大坑】

libwebrtc 用 clang 24 的 **relative vtable**（32 位相对偏移代替 64 位绝对地址）。我们的 `.cc`
**必须加 `-fexperimental-relative-c++-abi-vtables`**，否则两边的 vtable 布局不一致：

- 不加 → `PeerConnectionFactoryDependencies` 析构时读错槽位，deleting destructor 跳进 `.text` 末尾 padding（0x8e5693 之类），SIGBUS。

症状：崩溃栈停在 `PeerConnectionFactoryDependencies::~PeerConnectionFactoryDependencies()` + `unique_ptr.h:74`。

### #3 `__Cr` vs `__ndk1` libc++ ABI

libwebrtc 用 Chromium 自带的 libc++（`std::__Cr` 命名空间），不是 NDK 的 `std::__ndk1`。
必须用 `-nostdinc++` + `-I webrtc_materials/third_party/libc++/src/include` + `-I buildtools/third_party/libc++`（`__config_site`）。

### #4 `-DNDEBUG` 消除 `ExpectationToString` 未定义

libwebrtc 静态库里 debug 分支引用了 `ExpectationToString`（非 inline），release（`-DNDEBUG`）分支内联。
链接报 `undefined symbol: webrtc::ExpectationToString` 时加 `-DNDEBUG`。

### #5 `EnableMedia()` 启用媒体

`PeerConnectionFactoryDependencies` 默认 `media_factory == nullptr`（媒体禁用）。
不调用 `webrtc::EnableMedia(*deps)` 会报 `Not configured for media (UNSUPPORTED_OPERATION)`。

### #6 dummy AudioDeviceModule

`EnableMedia` 后 WebRtcVoiceEngine 需要 `adm`（AudioDeviceModule）。云机端不采集真实音频设备，
用 dummy：

```cpp
webrtc::Environment env = webrtc::CreateEnvironment();
factory_dependencies->env = env;
factory_dependencies->adm = webrtc::CreateAudioDeviceModule(
        env, webrtc::AudioDeviceModule::kDummyAudio);
```

否则 `Check failed: adm_`（`webrtc_voice_engine.cc:534`）。

### #7 CreateVideoTrack 必须在 signaling thread 上调用【隐蔽坑】

`CreateVideoTrack` 内部创建 `VideoTrackSourceProxyWithInternal`，其 `primary_thread_` =
`Thread::Current()`。若在 **Java 线程**（JNI `nativeCreatePeerConnection`）上直接调用，
`Thread::Current()` 返回 null → proxy 的 `primary_thread_ = null` → 后续
`VideoRtpSender::SetSend()` 调 `needs_denoising()` 时 `Marshal(null)` 空指针崩溃
（SIGSEGV addr 0x0）。

**解决**：用 `BlockingCall` 在 signaling thread 上执行：

```cpp
webrtc::scoped_refptr<webrtc::VideoTrackInterface> video_track;
bool add_track_ok = false;
signaling_thread_->BlockingCall([&] {
    video_track = factory_->CreateVideoTrack(track_source, "video");
    if (video_track != nullptr) {
        add_track_ok = peer_connection_->AddTrack(video_track, {"video"}).ok();
    }
});
```

### #8 视频编码器 factory 要声明 profile-level-id

`ScrcpyVideoEncoderFactory::GetSupportedFormats()` 若只声明 `packetization-mode=1` 而缺
`profile-level-id`，H264 协商失败，answer 里 `m=video 0`（视频被拒）。补：

```cpp
return {webrtc::SdpVideoFormat("H264",
        {{"packetization-mode", "1"}, {"profile-level-id", "42001f"},
                {"level-asymmetry-allowed", "1"}})};
```

### #9 Android 12 采集走 SurfaceControl，不是 MediaProjection

4 参数 `MediaProjectionManager.getMediaProjection(int, String, int, boolean)` 是 **Android 13+** 才有的
隐藏 API。Android 12 上反射调用会 `NoSuchMethodException`。

**解决**：Android 12 用官方 scrcpy 的 `ScreenCapture`（`SurfaceControl` 路线），
`MediaProjectionCapture` 只用于 Android 13+。`Server.java` 采集选择统一走 `ScreenCapture`。

### #10 系统应用禁用 CleanUp fork

`CleanUp` 默认启用（`Options.cleanup = true`），会 fork `app_process` 子进程，子进程的
`CLASSPATH = Server.SERVER_PATH`（系统应用下不指向 jar）→ `ClassNotFoundException: CleanUp` → abort。

**解决**：系统应用方式（`context != null`）禁用 CleanUp：

```java
if (options.getCleanup() && context == null) {
    cleanUp = CleanUp.start(options);
}
```

### #11 SignalServer 断连竞态

客户端提前断连后，ICE gathering 异步回调 `sendIceCandidate` 仍会 `client.send()`，
抛 `WebsocketNotConnectedException`（RuntimeException，`catch(JSONException)` 捕获不到）→ 进程崩。
**解决**：`sendAnswer`/`sendIceCandidate` 检查 `client.isOpen()` + `catch (Exception)`。

### #12 模拟器 /data/local/tmp 重启即清空

用 app_process 方式时，jar/so 放 `/data/local/tmp` 会在重启后被清。系统应用方式不受影响
（`/system/priv-app/` + `/system/lib64/`）。

### #13 关键帧请求回调必须接线【浏览器画面黑屏/解码失败根因】

端到端信令 + ICE + DTLS + RTP 全部打通后，浏览器仍 `videoWidth=0`（无画面）。排查发现：
`getStats` 里 `inbound-rtp kind=video bytes` 持续增长（RTP 数据确实到达），但 `framesReceived=0`
（始终解码不出帧）。

根因是**关键帧（IDR）缺失**：

1. 启动时 MediaCodec 输出的第一个关键帧（含 SPS/PPS），在浏览器尚未连接（broadcaster 无 sink）时
   被直接丢弃。
2. 浏览器连接后，`VideoStreamEncoder` 注册为 sink，**必须收到一个 IDR 关键帧才能开始解码**，
   于是通过 `Encode` 的 `frame_types` 参数发起关键帧请求（`kVideoFrameKey`）。
3. 但 `ScrcpyPassthroughEncoder::Encode` 里检测到关键帧请求后，回调的是
   `g_key_frame_request_callback`（`SetGlobalCallbacks` 注册），它要 JNI 回调 Java 的
   `NativeEncoderBridge.Callback.onKeyFrameRequest()`。
4. 而 Java 侧 **`bridge.setCallback(...)` 从未被调用**（`g_callback == null`），
   `SurfaceEncoder` 也没实现 `onKeyFrameRequest()`，`requestKeyFrame()`（`PARAMETER_KEY_REQUEST_SYNC_FRAME`）
   无人触发 → 关键帧请求被吞掉，MediaCodec 始终不输出 IDR → 浏览器无画面。

症状特征：`Encode` 打印 `key=0 ... data=00 00 00 01 61`（`0x61` = 非 IDR slice），始终见不到
`0x67`（SPS）/`0x65`（IDR）。

**解决**：

1. `SurfaceEncoder implements AsyncProcessor, NativeEncoderBridge.Callback`，补 `onKeyFrameRequest()`：

```java
@Override
public void onKeyFrameRequest() {
    requestKeyFrame();   // PARAMETER_KEY_REQUEST_SYNC_FRAME
}
```

2. `Server.java` 里创建 `SurfaceEncoder` 后接线：

```java
SurfaceEncoder surfaceEncoder = new SurfaceEncoder(surfaceCapture, videoSink, options);
if (bridge != null) {
    bridge.setCallback(surfaceEncoder);
}
asyncProcessors.add(surfaceEncoder);
```

修复后浏览器连接 → `VideoStreamEncoder` 请求关键帧 → JNI 回调 → `requestSyncFrame` →
MediaCodec 输出 IDR（`0x67` SPS）→ 浏览器解码出画面（实测 `1080x2400`）。

### #14 浏览器端 offer 需 addTransceiver（接收端）

浏览器作为**接收端**，若 `RTCPeerConnection` 不声明 recvonly transceiver，`createOffer()` 生成
空 SDP（无 `m=` 行），ICE gathering 不启动（`iceGatheringState` 停在 `new`）。需：

```js
pc.addTransceiver('video', { direction: 'recvonly' });
pc.addTransceiver('audio', { direction: 'recvonly' });
```

---

## 9. 验证结果摘要

| 环节 | 结果 |
|---|---|
| 静态链接未定义 webrtc 符号 | 0 |
| 进程 UID | `system`（1000） |
| CAPTURE_VIDEO_OUTPUT | granted=true |
| SignalServer | `LISTEN *:10000` |
| WebSocket 握手 | `101 Web Socket Protocol Handshake` |
| offer → answer | 视频 `H264/90000` + 音频 `opus/48000/2` 均协商成功 |
| ICE + DTLS | connected（TURN relay nominated） |
| **视频画面** | **1080x2400，浏览器解码成功** |
| 崩溃 | 无（稳定运行） |
