# 帧率分阶段诊断与恢复

## 本次行为

- 镜像无需旋转、裁剪或角度变换时，虚拟显示直接接 MediaCodec；尺寸变化通过 `VirtualDisplay.resize()` 复用显示。
  需要变换时才接 `SurfaceTexture -> GL -> MediaCodec`，使用可录制 EGLConfig，请求 swap interval 0，并记录设备允许的最小值。
- 直接路径用 `max-fps-to-encoder` 控制编码前的 surface 输入。GL 路径先消费原始纹理，再按时间戳采样。
  30 Hz 输入采样为 24 fps 时保留分数周期，不会因“距上次接收至少 41.7 ms”变成 15 fps。
  调整帧率时重建编码器，更新输入限制；仅码率变化仍用 `setParameters()`。
  不在 passthrough 中任意丢弃已编码 H.264 P 帧。`KEY_FRAME_RATE` 是预期帧率提示，不能制造源画面。
- 删除依赖 SetRates 回调次数收敛的码率 EMA。每次原始预算反馈立即用于目标码率和档位观察，编码线程使用缓存预算推进稳定窗口。
  零预算、最后一个 peer 重置、peer 删除会清理或重新计算预算；迟到的已删除 peer 回调被忽略。
  升档仍逐级、每级 6 秒，保持时间与稳定时间并行。严重拥塞不会被升档保持期阻挡。
- APK 录音使用真实 Context，不运行 shell 前台录音兼容清理，也不覆盖真实 ActivityThread。
  Android 11 的 UID 2000 shell 入口才启用 HeapDumpActivity 兼容逻辑，清理包名固定为 `com.android.shell`。
  `forceStopPackage()` 在 debug 级别记录目标、PID 和调用栈。
- 系统 APK 必须由 `ServerService` 启动，使用真实应用 UID、包名和 Context，不调用 `setuid(2000)`。
  `RuntimeContext` 在服务创建时初始化，输入、剪贴板、相机、显示、设置、Activity 和音频统一使用它。
  缺少真实 Context 的系统/普通应用 UID 会明确报错，不回退到 shell；`FakeContext` 仅供旧 app_process 入口使用。
  启动日志 `Server identity: package=... uid=... mode=application` 可核对实际身份。
  Settings/provider API 的 userId 来自 UID 的 Android 用户部分，不把 UID 1000/2000 当作 userId。

## 查看每一阶段

启动参数加入 `log_level=debug`。每秒一条 `Video pipeline` 日志包含同一单调时钟窗口的：

| 字段 | 含义 |
| --- | --- |
| `mode` | direct / gl / new-display；直接路径没有可观察的 GL 输入回调，GL 计数为 0 不能解释为源不更新 |
| `glInput` | GL 消费输入纹理的回调频率；回调可能合并，不能代替源应用绘制计数 |
| `glSubmitted`, `glSkipped` | GL 成功提交率、编码前采样跳过次数 |
| `codec` | MediaCodec 实际输出的非配置帧率 |
| `handoff` | 通过启动/暂停/关键帧门控后完成 PacketSink 交接的帧率；不是网络送达率 |
| `updateAvg/Max`, `swapAvg/Max` | updateTexImage 与 eglSwapBuffers 的墙钟耗时 |
| `handoffAvg/Max` | 编码输出复制与 sink/JNI 交接等待，可能包括 bridge 锁与多个 peer 扇出 |

不再输出从未填充的输入时间戳映射推导的 `avgEncode=0ms`。
H5 另显示 JNI 有效 PTS 帧龄样本率、WebRTC `framesEncoded` 回调率、解码帧率和浏览器合成器呈现帧率。
JNI 样本率不包含无效/未来 PTS 帧，多个 peer 的 H5 数值应分别比较；WebRTC 回调率不等于 MediaCodec 重编码率。
帧率使用各自计数的真实时间增量，counter/SSRC/会话变化时先重新取基线。

源窗口频率仍须通过 gfxinfo/SF trace 观察，不从 codec 或 GL 输出反推。
在已建立 SSH 隧道、安装 adb 的机器上运行（不会重启服务、修改设置或清空日志）：

```bash
python3 server/tools/collect_frame_pipeline.py \
  --serial 127.0.0.1:46387 --app mark.via --seconds 10 --out frame-check-r20
```

`--app` 应改成实际产生画面的游戏/浏览器包名。第 1 轮和第 20 轮保持同一动态素材、分辨率、设备和启动参数。
采集时另保存同时间的 H5 面板截图。trace 若仍缺少应用内部事件，不据此断言 WebView 限帧。

## 判读与边界

- 源窗口约 15 fps：检查源视频、应用绘制/VSync 回调节奏；提高编码目标不会增加独立画面。
- 源窗口高、GL 提交低且 swap/update 等待高：定位 GL/缓冲/驱动等待，比较直接路径。
- GL 提交高、codec 输出低：检查 codec 输入限制、手动 codec_options、厂商编码器与缓冲。
- codec 输出高、handoff 低：检查暂停、关键帧门控和 JNI 阻塞。
- JNI 输入高、WebRTC 回调或 H5 解码低：检查发送丢帧、拥塞控制、接收恢复和缓冲。

不保证 15 fps 的源恢复为 30/60 fps，也不保证 `eglSwapInterval(0)` 被设备接受为实际 0。
本次没有新增 JNI 接口：H5 复用已有 `scrcpy.latency.v1` 和 outbound-rtp counters。
完整 APK 构建、设备编解码与弱网验证仍需 Android SDK/Gradle 和云机现场；Java 语法检查不能代替 Android 编译。

## 回归检查

```bash
python3 tests/run_video_adaptation.py
python3 tests/run_runtime_context.py
cd p4/web && npm run build
```

Java 检查覆盖分数帧率、重复时间戳、帧率变化、稀疏反馈恢复、零预算、peer 预算生命周期与 APK/shell 录音策略。
身份检查使用最小 Android 绑定运行真实 `RuntimeContext`，覆盖系统/普通应用缺失 Context、真实系统应用、非法 Context、旧 shell 和多用户。
这不替代设备上的 Binder 权限、平台签名或 SELinux 验证。
H5 counter 测试覆盖正常帧率、零帧停顿、SSRC 更换、计数回退和缺失字段。
