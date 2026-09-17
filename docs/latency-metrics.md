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
