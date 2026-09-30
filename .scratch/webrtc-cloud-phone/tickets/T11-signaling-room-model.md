# 信令房间模型

**Blocked by**: T10

## 目标
将 `SignalServer` 从单客户端模型重构为房间模型：支持多 WebSocket 客户端同时连接同一房间，room ID + token 验证，房间满时拒绝新客户端。

## 涉及层
- [x] 逻辑层（Java）：`SignalServer.java` — 去除单 `client` 字段，改为 `Map<String, Room>` 管理多房间
- [x] 逻辑层（Java）：`Room` 类（新增）— 持有 room ID、`maxPeers`、已连接的 `Map<String, WebSocket>` 客户端、token 验证
- [x] 逻辑层（Java）：`SessionAdmission` — 保留不变（新 SignalServer 使用内置 constant-time 比较，SessionAdmission 仍被回归测试覆盖）
- [x] 逻辑层（Java）：信令消息路由 — offer/answer/ICE candidate 按 roomId + clientId 路由到正确的 bridge
- [x] 逻辑层（Java）：`Server.java` — per-client bridge 管理，首个客户端使用 primary bridge，额外客户端按需创建

## 验收标准
- 客户端 A 发送 `{ type: "join", room: "instance-001", token: "xxx" }` 成功加入
- 客户端 B 同时加入同一房间，各自独立建立 PeerConnection
- 房间满（达到 maxPeers）时第 N+1 个客户端被拒绝（WebSocket 收到 error 消息后关闭）
- 客户端断开后房间自动清理（无客户端时删除房间）
- 现有单客户端场景（不传 room 参数）向后兼容
