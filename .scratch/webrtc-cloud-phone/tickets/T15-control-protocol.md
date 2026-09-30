# 控制权协议（REQUEST/RELEASE/STATE）

**Blocked by**: T14

## 目标
实现控制权的请求/释放/状态广播协议：客户端通过 DataChannel 发送 `REQUEST_CONTROL`/`RELEASE_CONTROL`，服务端广播 `CONTROL_STATE` 到所有客户端（当前控制者 + 队列位置）。

## 涉及层
- [ ] 逻辑层（Java）：`ControlManager.requestControl(clientId)` — 无人控制时直接授予，否则入队
- [ ] 逻辑层（Java）：`ControlManager.releaseControl(clientId)` — 释放后自动转给队列下一个
- [ ] 逻辑层（Java）：`ControlManager.broadcastControlState()` — 发送 JSON `{ type: "CONTROL_STATE", controller: clientId, queue: [...] }` 到所有 DataChannel
- [ ] 逻辑层（Java）：客户端断网时自动释放控制权 + 转移
- [ ] 逻辑层（Web）：`p4/web/` 客户端处理 `CONTROL_STATE` 消息，更新 UI 状态（观看中/控制中/等待中）
- [ ] 逻辑层（Web）：请求控制/释放控制按钮 → 发送 `REQUEST_CONTROL`/`RELEASE_CONTROL`
- [ ] 测试：A 释放 → B 自动获得；控制者断网 → 队列下一个接管

## 验收标准
- 客户端 A 点击"请求控制" → A 的 UI 显示"你在控制"，B 的 UI 显示"观看中 · 队列第 1 位"
- 客户端 B 也请求 → B 的 UI 显示"等待控制权..."
- A 释放控制 → B 自动获得，B 的 UI 更新为"你在控制"
- A 断网 → 控制权自动转给队列下一个（B），延迟 < 1 秒
- 队列位置实时更新（C 前面的人离开后，C 从第 3 位变为第 2 位）
