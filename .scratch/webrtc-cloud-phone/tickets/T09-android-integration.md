# Android 客户端：端到端集成

**Blocked by**: T07, T08

## 目标
将信令、视频、音频、触控集成为完整 Android 客户端，补充生命周期管理、重连逻辑、错误处理，功能对标 Web 客户端。

## 涉及层
- [ ] UI 层：连接状态 UI（连接中/已连接/断开/重连中）
- [ ] UI 层：悬浮控制栏（断开按钮、全屏切换）
- [ ] 逻辑层：`WebRtcManager` 生命周期 — `onResume()`/`onPause()`/`onDestroy()` 正确管理 PeerConnection 和 DataChannel
- [ ] 逻辑层：断线自动重连 — WebSocket 重连 + PeerConnection 重建 + ICE restart
- [ ] 逻辑层：错误处理 — 信令超时、ICE 失败、DataChannel 关闭等场景的用户提示
- [ ] 测试：全链路端到端可用

## 验收标准
- 启动 → 输入地址 → 连接 → 看画面 → 触控操作 → 听声音，全流程无阻断
- 断网 5 秒后恢复，自动重连成功，画面恢复
- 按 Home 键再返回，视频/音频正常恢复（不黑屏、不无声）
- 功能覆盖 Web 客户端所有核心功能（视频/音频/触控/剪贴板）
