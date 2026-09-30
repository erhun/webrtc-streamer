# 音频广播 + 码率聚合

**Blocked by**: T10

## 目标
将 PCM 音频数据 fan-out 到所有客户端的 `PcmAudioSource`；实现码率聚合策略——取所有客户端带宽估计的最小值驱动 MediaCodec 编码器。

## 涉及层
- [ ] 逻辑层（Java）：`BroadcastAudioSink`（新增）— 持有 N 个 session 引用，`writeAudioPacket()` 时广播 PCM 到所有 session
- [ ] 逻辑层（Java）：`Server.java` — 音频 `PacketSink` 替换为 `BroadcastAudioSink`
- [ ] 逻辑层（C++）：`ScrcpyPassthroughEncoder::SetRates()` — 回调到 Java 层上报当前 PC 的带宽估计
- [ ] 逻辑层（Java）：`BitrateAggregator`（新增）— 收集所有 session 的带宽估计，取 min，回调 `SurfaceEncoder.applyFeedback(minBitrate)`
- [ ] 逻辑层（Java）：`NativeEncoderBridge` — 新增 `setBitrateFeedbackCallback()` 将 SetRates 回调传递到 Java
- [ ] 测试：3 个客户端同时串流，模拟最弱客户端降速，观察码率下降

## 验收标准
- 3 个客户端同时收到音频，播放正常
- 客户端 A 带宽 8Mbps、B 带宽 2Mbps、C 带宽 5Mbps 时，MediaCodec 码率降至 ~2Mbps
- 最弱客户端断网后，码率自动回升到剩余客户端的最小值
- 6 档阶梯仍然正常工作（跨档切换由 `CaptureControl.reset()` 处理）
