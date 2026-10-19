# 帧率恢复与全链路隔离验证

此版本修改 Java 与 C++，必须部署匹配的 JNI 制品和 APK。仓库已有 `.so` 不包含本次修改。
本文的主机回归不代表 SoC、MediaCodec、真实 libwebrtc 或网络已验收。

## 控制行为

- Java 编码配置始终不超过当前 SetRates 分配及 `video_bit_rate`，降预算立即收敛，增加每 1.5 秒最多
  15%/250kbps（取较小值）。不保留 Java ssthresh，不通过超发 H.264 验证链路容量。
- WebRTC 保留 ALR probing，负责基于传输反馈的探测和 pacing。协商后的 native 日志显示
  `Video sender configured: max_framerate=60 twcc_extension=...`。扩展存在不等于探测已经成功，
  还须从匹配版本的 WebRTC 日志/事件日志核对 probe cluster 与反馈结果；缺少反馈时不能宣称容量验证成功。
- 稳定分配在零预算/peer reset 前仍有效，编码线程 tick 可推进 ramp 和档位防抖。
  `Adapt`/`Adapt tick` 的 `held` 是配置保持状态，不是 ACK 吞吐。
- native 对源帧编号；在 Encode 前丢帧造成序号缺口时等待 IDR，不继续发送断裂参考链。
  当前 IDR 成功提交到 WebRTC 后可满足请求，不额外请求第二个 IDR；此处成功不代表接收端已确认解码。
  IDR 提交失败不解除等待，重试间隔至少 1 秒。RTP 层丢包仍依赖 NACK/PLI 恢复。
- `has_trusted_rate_controller` 仅关闭 media optimization 的丢帧；不能保证队列/cwnd 不丢帧。
  CBR 实际超调、IDR 峰值及硬件参数是否生效须实测。

## 构建和主机检查

在匹配 libwebrtc/Chromium 工具链的构建机配置 `WEBRTC_ROOT`、`CLANG`、`ANDROID_SYSROOT`、
`WEBRTC_REVISION`，执行：

```bash
python3 server/tools/build_native.py
python3 server/tools/native_bundle.py export --output /path/to/scrcpy-native-arm64.zip
```

在 APK 构建工作区导入原始制品清单，不生成替代清单或修改其哈希：

```bash
python3 server/tools/native_bundle.py import /path/to/scrcpy-native-arm64.zip
python3 server/tools/prepare_native.py --check
./gradlew -p server test checkstyle lint assembleDebug
```

APK 的 native 合并校验已恢复启用，缺少/过期清单会阻止打包，避免新 Java 配旧 `.so`。
轻量检查可独立运行：

```bash
python3 tests/run_video_adaptation.py
python3 tests/run_encoder_feedback.py
python3 tests/run_native_media.py
python3 tests/run_architecture.py
python3 tests/native_abi_test.py
```

`run_encoder_feedback` 执行真实 SurfaceEncoder，Android/MediaCodec 是绑定替身；
`run_native_media` 使用主机 g++、警告即错误及 UBSan，执行真实参考链门控/时钟/PCM 逻辑，未链接 libwebrtc/JNI。
JNI 声明检查也不等于 native 构建/ABI 实测。

## 设备测试矩阵

保留现有鉴权、信令和网络参数，只追加/替换下表的媒体参数。先单观看端、关闭音频，使用持续运动画面。
`max_size` 是最大边长，不是固定的 854×480。固定模式保留当前预算约束；它不会强迫链路承载指定码率。

| 场景 | 媒体参数 | 验证内容 |
| --- | --- | --- |
| 基础采集/编码 | `video_adaptation=false,max_size=854,max_fps=30,video_bit_rate=1500000,audio=false` | 在容量充足的已知链路上检查原始采集、codec/JNI/WebRTC/解码/呈现是否约 30fps，无策略切档 |
| 60fps 能力 | `video_adaptation=false,max_size=1280,max_fps=60,video_bit_rate=4000000,audio=false` | 显示/应用须实际产生 60fps；核对 MediaFormat、硬件支持上限、SDP/SPS profile/level 和接收端能力，不只看配置 |
| 自适应恢复 | `video_adaptation=true,max_size=1920,max_fps=60,video_bit_rate=8000000,audio=false` | 容量充足→约 200kbps 保持 15 秒→恢复容量充足并观察 180 秒，必须离开最低档，不依赖重连 |
| 静止→运动 | 同上 | 静止一分钟后恢复运动；检查 ALR probe/反馈，不能靠媒体超发起量 |
| 故障恢复 | 同上 | 受控丢包/中断后恢复；检查源序号缺口、关键帧请求、IDR、PLI/NACK，避免连续双 IDR 和 reset 循环 |
| 音频及多端 | 基础通过后依次开启音频、增加弱观看端 | 核对控制者/最低预算策略、共享源码率、handoff 阻塞与输入延迟 |

网络整形只在专用测试路由/网络命名空间执行，避免修改承载管理/ADB 的接口。
每段记录同一时间轴：`Adapt`、`Video tier changed`、`Capture configure`、`Encoder FPS capability`、
`Video pipeline` 的 GL/codec/handoff 帧率与耗时、native 参考链恢复/IDR 日志、发送队列、链路分配、
PLI/NACK/keyFrames、浏览器解码/呈现/freeze。设备厂商日志与 probe 事件须与码率突发对齐。
目标/最低 jitter buffer 不是实际缓冲驻留时间；几项均不能直接相加当作端到端延迟。

## 本次执行边界

预算/档位回归、真实 SurfaceEncoder 接线回归、主机 native 门控 UBSan、架构、JNI 声明及
制品交付模拟检查通过。完整回归在已有的 `tests/client_test.cjs:167` 远程 WS 断言处失败，
本次没有修改该前端行为。Gradle wrapper 9.3.1 下载遇到网络不可达，test/checkstyle/lint 未执行。
当前环境缺少 Android SDK、匹配 native 工具链及 ADB，未生成新 `.so`/APK、未执行上表设备测试。
