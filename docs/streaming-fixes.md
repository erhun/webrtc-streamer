# 串流正确性修复（第 1～6 项）

此文描述本次源码变更。历史文档中的模拟器结果不代表此版本已通过设备验收。

## 当前实现

1. WebRTC 音频统一为 AudioRecord PCM → 10ms 分帧 → AudioTrack → libwebrtc 内置 Opus。
   TCP 音频编码选项保持原语义。删除未连通的外部 Opus 队列与工厂，消除失效引用及无界排队。
   PCM 为 48kHz、16-bit little-endian、双声道，最多保留一个不足 10ms 的片段。
   AudioRecord 时间戳通过 framePosition 映射到读取数据的起始采样点。
2. 编码线程用有界 dequeue 轮询接收反馈，独占动态码率和关键帧操作。
   档内码率不超过分配值及档位上限；跨档重新应用采集 maxSize 并重建 MediaFormat。
   尺寸/帧率不突破用户设置上限；升档持续窗口 5 秒，最短切档间隔 1 秒。
   零码率暂停视频转发，恢复请求并等待 IDR。厂商 codec 对参数是否生效仍须实测。
3. 视频保留单调采集时间，90kHz 换算改为微秒乘 9 除 100；修正重复或倒退 PTS。
   设备上仍须抓取实际 RTP 时间戳并验证长期音画同步。
4. 一个启动会话只接受一个认证 WebSocket。凭证至少 32 字符，启动后五分钟内一次性使用。
   未认证连接五秒关闭；第二连接不能覆盖 owner，非 owner 断线不能回收活动会话。
   信令默认只监听 127.0.0.1，远程连接必须通过受控 WSS 反向代理。
5. 保持 ScreenCapture/NewDisplayCapture 路线，删除未接入且隐藏 API 未经验证的 MediaProjectionCapture。
   不再宣称 MediaProjection 直调 GPU；系统应用入口与 GPU/VPU 容量需要分别验收。
6. Session 提供状态与停止入口，进程内拒绝重复服务会话，结束时退出会话 Looper 而非 Android 主 Looper。
   JNI 回调由会话独立持有，清除全局引用，关闭 PeerConnection 后停止 native 线程。
   控制队列有字节上限，关闭唤醒读者，溢出结束会话；断线释放按键/触点/UHID。
   清理信令、采集、Controller executor；OpenGL 线程允许下一会话重新创建。

## 接入变化

- 新增启动参数 `signal_token`、`turn_url`、`turn_user`、`turn_password`。
  调度端为每次启动生成高熵随机凭证（如 32 随机字节的十六进制表示），通过安全通道交给客户端。
  不复用，不放进 URL 或日志，不提交到仓库。
- `ServerService` 要求 `com.genymobile.scrcpy.permission.START_SERVER`（signature）。
  调度应用必须使用匹配签名；调试使用受控 root shell。普通应用不能启动 system UID 服务。
- 浏览器先发送 `{"type":"auth","token":"..."}`，收到 `ready` 后才发 offer/ICE。
  页面有凭证输入框；远程 URL 必须 WSS，localhost WS 只用于 adb forward 调试。
- 单次启动不接管新用户、不复用断开的 PeerConnection。断线回收完成后，由调度端重新启动并签发新凭证。
  浏览器连接超时 30 秒，已连接后的网络中断宽限 10 秒；关闭时清理旧回调，避免污染下一会话。
- TURN 不再硬编码模拟器地址及默认账号，需显式配置。
- 音视频 track 合入同一个浏览器 MediaStream；浏览器自动播放策略可能要求用户交互。

## 必须重新构建 JNI

JNI 接口已改变，旧 `.so` 不可用于本版本。APK 合并 native 库前校验源码和库的 SHA-256 清单；
缺失或不匹配会拒绝打包，避免新 Java 代码误配旧 JNI。

使用与 libwebrtc 完全一致的 Chromium clang、libc++、relative-vtable 配置。
准备 `docs/native-build.md` 所述材料后：

```sh
export WEBRTC_ROOT=/absolute/path/to/webrtc_materials
export CLANG=/absolute/path/to/matching/clang++
export ANDROID_SYSROOT=/absolute/path/to/ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot
export WEBRTC_REVISION=<实际编译libwebrtc的commit>
python3 server/tools/build_native.py
./gradlew -p server assembleDebug
```

脚本编译当前全部 `.cc`，静态链接 libwebrtc，输出 JNI 库与 `server/native-build.json`。
clang 的 Android builtins/libunwind 需按历史构建文档准备。CMake 入口委托给同一个脚本。
旧文档的音频透传、六文件列表、动态链接和 MediaProjection 版本断言已被此实现替代。
本次没有生成或部署 APK/so。

## 验证结果与限制

`python3 tests/run_regressions.py` 需要 JDK 17+、g++、Node 和前端 TypeScript 依赖。
已通过：独立 Java 回归、100 个 Java 文件语法解析、C++ UBSan、TypeScript 检查及浏览器信令 mock 回归。
覆盖凭证独占/过期/错误连接关闭、队列溢出/关闭、档位时间窗口、RTP 换算、PCM 分帧与声道、
认证顺序、提前到达的 ICE、SDP 异步串行化、旧连接回调和控制背压。

完整 Gradle test/lint/checkstyle 因 Gradle 下载网络失败未执行。缺少 WEBRTC_ROOT、匹配 clang 和 Android sysroot，
未编译完整 JNI，也未执行目标 Android 实机测试。语法解析不能代替 Android 类型检查，mock 不能证明真实媒体播放。

发布前需要在构建机/设备完成：
1. 完整 native、APK 构建及 test/lint/checkstyle；视频和声音实际播放。
2. 抓取 RTP 时间戳，至少 30 分钟音画同步和内存稳定性验证。
3. 逐档检查 SPS 尺寸、实际帧率、码率、重配停顿与 IDR，含零码率恢复。
4. 双连接竞争、重复启动、启动中停止、断网重连，核验线程/句柄/输入状态释放。
5. 按 1→5→10→25→50 实例做硬件压测；未实测前不承诺容量和延迟。

## 启动等待约 50 秒的诊断

浏览器页面现在显示从点击连接开始的阶段耗时：WebSocket、鉴权、Offer、Answer、
ICE、WebRTC connected、选中路径和实际视频首帧。收到 track 不代表已经解码或显示。
日志不记录会话凭证、TURN 密码、SDP 或候选 IP。也可在控制台按 [startup] 过滤。

视频默认静音自动播放，点击“开启声音”恢复音频；避免异步信令完成后有声自动播放被浏览器阻止。
播放失败会明确提示。此修正并不证明原来的 50 秒由自动播放导致。

如果耗时集中于 ICE checking，并且已确认模拟器必须走 TURN，可在现有带 turn/user/cred
配置的页面 URL 后追加 &ice=relay（没有查询参数时用 ?，且必须同时配置可达的 TURN）。
默认仍使用 all。relay 用于验证/指定中继路径，不保证比 all 更快；TURN 不可达时会失败，
不能通过简单延长超时解决。不要直接复制历史文档里的局域网 IP，应使用本机网络实际地址。

若 WebRTC connected 很快、首帧很晚，检查视频接收字节、解码帧数及 Android IDR/SPS/PPS 日志。
本分支连接超时为 30 秒，但该计时在传输 connected 后结束，不覆盖之后的首帧等待。
请同时提供实际运行的 git 提交号和上述阶段日志，以区分旧客户端与新客户端。

验证范围：TypeScript 编译和模拟信令回归；无法从开发容器访问用户的 localhost，
尚未复现或确认 50 秒等待的根因，也未完成浏览器/模拟器端到端计时。

## 静止画面首次连接

设备日志显示控制通道已打开后，视频 Encode 首次调用仍可能等待数十秒，
而采集初始关键帧早于客户端连接。增加传输 kConnected 时的一次关键帧请求，
不再依赖 Encode 收到画面后才触发反馈。首次关键帧请求在编码线程上重建采集，
确保静止画面也重新提交输入；后续 PLI 仍使用 MediaCodec 同步帧请求，不重复重建。
保留尚未处理的码率反馈。日志新增 Transport connected 和 Startup keyframe 时间点。

此修改涉及 C++ 和 Java，必须重新构建 JNI 与 APK。轻量回归通过，尚未完成
实际 native 构建和静止屏幕首帧验收，不能据此承诺首帧耗时。

首帧进一步优化：合并同一轮反馈中的首次采集刷新与码率/分辨率调整。
原流程先刷新，再重放已收到的码率，可能连续重建两次；现在先计算最新配置，
只提交一次重建。仍保留静止画面刷新及后续关键帧请求。若新的反馈稍后才到达，
仍可能需要后续调整，不承诺始终只有一次重建。轻量回归通过；需要实机对比
1026ms 首帧基线，尤其检查静止画面、低码率和零码率恢复场景。
