# 音频配置开关 + 空编码器工厂

**Blocked by**: 无

## 目标
引入音频方案配置开关（PCM/Opus），创建空的自定义音频编码器工厂并注册进 PeerConnectionFactory，使后续 Opus passthrough 编码器有挂载点。

## 涉及层
- [ ] 数据层：`Options` 新增 `audioScheme` 枚举（PCM/OPUS），默认 PCM
- [ ] 逻辑层（Java）：`Server.java` 根据配置决定 `AudioCodec`（PCM 时仍为 RAW，Opus 时为 OPUS）
- [ ] 逻辑层（C++）：新增 `ScrcpyAudioEncoderFactory`（空壳），根据配置返回 builtin Opus encoder 或 custom encoder（当前仍 fallback 到 builtin）
- [ ] 逻辑层（C++）：`ScrcpyPeerConnection::Initialize()` 中替换 `CreateBuiltinAudioEncoderFactory()` 为自定义工厂
- [ ] 测试：日志验证工厂被调用

## 验收标准
- `Options` 可配置 `audioScheme`，默认值为 PCM
- 配置 PCM 时行为与当前完全一致（回归零影响）
- 配置 OPUS 时仍走 PCM 路径（fallback），但日志确认 `ScrcpyAudioEncoderFactory` 被 PeerConnectionFactory 调用
- `Server.java` 根据配置正确设置 `AudioCodec`
