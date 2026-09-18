# 三段延迟统计

H5 每秒采样；第一个样本只建立基线。无增量、断线、不支持的字段显示“—”。服务端统计超过 3.5 秒未更新则清空，不保留过期值。

| 指标 | 测量范围 | 单位/计算 |
| --- | --- | --- |
| 采集帧龄 | Android 屏幕帧原始 PTS → JNI 视频入口；包含采集、MediaCodec 编码和 Java/JNI 传递 | Δ累计帧龄微秒 / Δ有效帧数 / 1000 |
| 发送排队 | WebRTC 视频 RTP 包发送缓冲 → 发出 | ΔtotalPacketSendDelay / ΔpacketsSent × 1000 |
| 接收缓冲 | 浏览器视频 jitter buffer | ΔjitterBufferDelay / ΔjitterBufferEmittedCount × 1000 |

采集使用 CLOCK_MONOTONIC 与 Android 屏幕原始 presentationTimeUs 比较，先于 MediaClock.Normalize；不跨机器比较时间。非屏幕来源必须先验证 PTS 时间基准。负帧龄或无效 PTS 不计入。

发送统计是每包平均，其他两项每帧平均，且区间不严格同步；三项不可直接相加当作端到端延迟。未涵盖网络单向传播、接收端最终呈现等环节。

协议：已鉴权会话的 control DataChannel 文本请求 `scrcpy.latency.v1`；文本 JSON 响应含 type、captureUs、captureFrames、outbound（仅 outbound-rtp 报告）。客户端按统计 id 分别计算差值，排除音频和重置计数。现有二进制控制消息不变。服务端单次异步 GetStats 未结束时不再启动第二次，通道积压时跳过响应。

部署需要同时更新 H5 和 libscrcpy_native.so；旧服务端不会提供新指标。此改动没有调整编码、码率、帧率或播放缓冲策略。

验证：在动态画面连续播放期间观察三项；屏幕无新帧时采集可能因重复帧继续采样，以有效 PTS 为准。重连后应重新建立基线，不混入前一会话。需要在实际 Android/WebRTC 构建环境编译 JNI 并做运行验证。

## 码率与积压观察

编码目标恢复为 min(WebRTC SetRates 视频预算, 当前档位码率)。零预算保留原有暂停行为；配置码率仍可显示暂停前最后一次成功配置，不表示正在产出。

新增分配码率（SetRates）、配置码率（MediaCodec start/setParameters 成功后上报）、编码输出码率（JNI 视频字节增量除以服务端单调时钟间隔，含 SPS/PPS，不含 RTP 头）。输出码率计量的是提交给 JNI 的编码视频，暂停期间被 Java 丢弃的帧不计入。新协议字段缺失时显示“—”。

播放延迟提示独立于原有 RTT/丢包连接质量：发送排队超过 150ms 标记发送积压；否则接收缓冲超过 200ms 标记缓冲偏高。这是诊断阈值，不是播放缓冲控制目标。

部署 Java APK、JNI .so 和 H5 后，在同一动态画面连续观察 30–60 秒：先确认配置码率随分配预算调整，再观察发送排队下降后接收缓冲是否逐步下降。保留升档条件和浏览器自适应缓冲，不强制提高分辨率、不通过丢弃已编码参考帧排空队列。若发送排队已低而接收缓冲仍高，记录音视频接收统计后单独处理接收端。此改动不承诺达到固定延迟，尚需设备验证。

### Video receiver target and detailed counters

Each new video receiver requests a 50 ms jitter buffer target via
`jitterBufferTarget`, falling back to Chromium's `playoutDelayHint = 0.05`
(seconds) when necessary. Unsupported/rejected setters do not fail the session;
the startup log reports the outcome. This is a request, not a hard upper bound.
See https://w3c.github.io/webrtc-extensions/#dom-rtcrtpreceiver-jitterbuffertarget.

The UI also shows target and minimum video buffer delay using interval deltas
of `jitterBufferTargetDelay` and `jitterBufferMinimumDelay`, divided by
`jitterBufferEmittedCount`. Decode time uses `totalDecodeTime / framesDecoded`
interval deltas. All three are converted from seconds to milliseconds.
Missing counters, counter resets, new streams and intervals without emitted or
decoded frames display “—”. Reconnection clears every baseline.

Compare the same content for at least five minutes with server audio still
disabled. Record actual/target/minimum buffer delay and decode time alongside
freeze behavior. A lower requested target does not guarantee lower actual delay.
Only the H5 client needs rebuilding for this change.
