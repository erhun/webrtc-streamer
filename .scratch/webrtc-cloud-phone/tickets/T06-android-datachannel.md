# Android 客户端：DataChannel + 控制消息序列化

**Blocked by**: T04

## 目标
创建 control DataChannel，实现 23 种 scrcpy `ControlMessage` 的字节序列化（复用 Web 端协议格式），确保消息可收发。

## 涉及层
- [ ] 逻辑层：`ControlMessageSerializer.java` — 23 种控制消息的大端二进制序列化（与 Web 端 `control-message.ts` 协议一致）
- [ ] 逻辑层：`DataChannelManager.java` — 创建/管理 control DataChannel，`onMessage()` 回调接收 server→client 消息
- [ ] 逻辑层：`PeerConnection` 创建时 `createDataChannel("control", init)` — 有序、可靠
- [ ] 测试：echo 验证 — 发送消息后服务端日志确认收到

## 验收标准
- DataChannel 状态回调显示 `OPEN`
- 发送 `injectTouch` 消息（type=2），服务端日志确认收到正确字节
- 23 种消息类型序列化与 Web 端 `control-message.ts` 输出一致（字节级对比）
- server→client 消息（剪贴板粘贴等）能接收并解析
