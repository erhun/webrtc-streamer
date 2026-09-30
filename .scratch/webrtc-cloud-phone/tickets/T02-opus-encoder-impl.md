# ScrcpyOpusAudioEncoder C++ 实现

**Blocked by**: T01

## 目标
实现 `webrtc::AudioEncoder` 子类 `ScrcpyOpusAudioEncoder`，从预编码 Opus 队列取帧，通过工厂注册到 PeerConnectionFactory。

## 涉及层
- [ ] 逻辑层（C++）：`scrcpy_opus_audio_encoder.h/cc` — `webrtc::AudioEncoder` 子类
- [ ] 逻辑层（C++）：`EncodeImpl(rtp_timestamp, pcm, encoded)` 从内部队列取预编码 Opus 帧填入 `encoded`
- [ ] 逻辑层（C++）：空调用（队列空时）返回 `encoded_bytes=0, send_even_if_empty=false`
- [ ] 逻辑层（C++）：`ScrcpyAudioEncoderFactory` 在 OPUS 配置时返回此编码器实例
- [ ] 测试：单元测试 — 喂入 Opus 帧 → `Encode()` 输出正确 `encoded_bytes` + timestamp 对齐

## 验收标准
- `ScrcpyOpusAudioEncoder` 实现 `webrtc::AudioEncoder` 接口
- 队列有帧时 `Encode()` 返回正确 Opus 数据，`encoded_bytes > 0`
- 队列空时 `Encode()` 返回 `encoded_bytes = 0`，`send_even_if_empty = false`
- `GetEncodedSamples()` 返回 0（passthrough 不消耗 PCM）
- 单元测试通过：连续喂入 5 帧 Opus → 取出 5 帧，timestamp 递增正确
