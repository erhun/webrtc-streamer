# 会话重构：per-client Session + 共享 PeerConnectionFactory

**Blocked by**: 无

## 目标
将当前单客户端架构重构为 per-client session 模型：共享 `PeerConnectionFactory`（避免每客户端创建独立线程），每个客户端拥有独立的 `PeerConnection`/`EncodedVideoTrackSource`/`PcmAudioSource`。`NativeEncoderBridge` 支持多 handle。

## 涉及层
- [ ] 逻辑层（C++）：`ScrcpyPeerConnection` 拆分 — `PeerConnectionFactory` 提升为共享单例或外部注入，`ScrcpyPeerConnection` 只持有 per-client 状态（PC/track/audio source）
- [ ] 逻辑层（C++）：`jni_bridge.cc` — `Session` struct 改为 per-client，支持多 handle 注册表（`std::map<jlong, std::unique_ptr<Session>>`）
- [ ] 逻辑层（Java）：`NativeEncoderBridge` — 去除单 open 限制，支持 `createSession()`/`destroySession(handle)` 多实例管理
- [ ] 逻辑层（Java）：`Server.java` — 现有单客户端路径改为"创建 1 个 session"的特例，逻辑不变
- [ ] 测试：现有单客户端功能回归测试

## 验收标准
- 现有单客户端 WebRTC 串流功能完全正常（视频/音频/控制/动态码率/PLI）
- C++ 层可同时存在多个 `Session` 实例（通过 JNI 创建多个 handle）
- `PeerConnectionFactory` 只创建一次，所有 session 共享
- 销毁某个 session 不影响其他 session
