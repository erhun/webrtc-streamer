# Android 客户端：WebRTC 建链 + 视频渲染

**Blocked by**: T04

## 目标
建立 PeerConnection，完成 ICE/DTLS 协商，将远端视频流渲染到 `SurfaceViewRenderer`，实现首帧上屏。

## 涉及层
- [ ] 逻辑层：`WebRtcManager.java` — `PeerConnectionFactory` 初始化 + `PeerConnection` 创建 + `SdpObserver`/`PeerConnection.Observer` 回调
- [ ] 逻辑层：`VideoRenderer.java` — `SurfaceViewRenderer` 初始化 + `onAddStream()` 绑定视频 track
- [ ] UI 层：`activity_main.xml` — 全屏 `SurfaceViewRenderer`
- [ ] 逻辑层：信令流程串联 — offer/answer/ICE candidate 经 `SignalingClient` 交换
- [ ] 测试：端到端首帧上屏

## 验收标准
- Android 设备显示全屏视频画面，内容为云机实时画面
- 延迟在合理范围（< 500ms 首帧）
- ICE 连接状态变化在 UI 有反馈（连接中/已连接/断开）
- 横屏全屏显示，画面比例正确（无拉伸）
