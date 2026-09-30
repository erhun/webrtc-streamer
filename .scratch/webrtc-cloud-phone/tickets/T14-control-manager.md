# ControlManager 独占控制

**Blocked by**: T11

## 目标
实现 `ControlManager`，管理多客户端场景下的独占控制权：同一时刻只有一个客户端的控制消息被转发到 Android `Controller`，其余客户端的消息被丢弃。

## 涉及层
- [ ] 逻辑层（Java）：`ControlManager.java`（新增）— 持有 `currentController`（client_id）+ FIFO 等待队列 + 所有客户端 DataChannel 映射
- [ ] 逻辑层（Java）：`forwardControlMessage(clientId, data)` — 仅当 `clientId == currentController` 时转发到 `Controller.handleEvent()`
- [ ] 逻辑层（Java）：新客户端加入时默认为观看者（`currentController = null` 或首个请求者获得）
- [ ] 逻辑层（Java）：`Server.java` — 每个客户端的 DataChannel 消息先经过 `ControlManager` 过滤
- [ ] 测试：客户端 A 控制时 B 的触控被忽略

## 验收标准
- 客户端 A 获得控制权后，A 的触控操作在云机上有响应
- 客户端 B 同时发送触控消息，云机无响应（消息被丢弃）
- 无人控制时（`currentController == null`），所有客户端的触控都被丢弃（纯观看模式）
- `ControlManager` 线程安全（多线程并发调用不崩溃）
