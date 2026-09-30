# MediaCodec Opus → C++ 桥接 + 20ms/10ms 对齐

**Blocked by**: T02

## 目标
将 Java 侧 MediaCodec Opus 编码输出通过 JNI 桥接到 C++ `ScrcpyOpusAudioEncoder` 的内部队列，处理 MediaCodec 20ms 帧 vs libwebrtc 10ms 调用节奏的对齐问题。

## 涉及层
- [ ] 逻辑层（Java）：`Server.java` — `AudioCodec.OPUS` 时创建 MediaCodec Opus 编码器，输出通过 `WebRtcPacketSink` → `NativeEncoderBridge.pushOpusFrame()`
- [ ] 逻辑层（JNI）：`jni_bridge.cc` 新增 `nativePushOpusFrame()` — 将 Opus 帧字节推入 C++ 编码器队列
- [ ] 逻辑层（C++）：`ScrcpyOpusAudioEncoder::PushOpusFrame(data, len, pts_us)` — 入队，带 PTS
- [ ] 逻辑层（C++）：`EncodeImpl()` 出队时确保 `encoded_timestamp` 与 `rtp_timestamp` 对齐（20ms 帧 → 每 2 次 10ms 调用发 1 帧）
- [ ] 测试：端到端验证 — 配置 OPUS → 浏览器出声，CPU 占用低于 PCM 方案

## 验收标准
- 配置 `audioScheme=OPUS` 后浏览器能播放音频
- CPU 占用低于 PCM 方案（MediaCodec 硬件 Opus vs 软件 Opus）
- 音频无爆音、无断断续续（20ms/10ms 对齐正确）
- 切换回 PCM 配置后行为正常（双方案可切换）
