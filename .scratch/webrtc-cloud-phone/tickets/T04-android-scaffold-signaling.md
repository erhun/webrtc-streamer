# Android 客户端：项目脚手架 + 信令 WebSocket

**Blocked by**: 无

## 目标
创建 `android-client/` Gradle 项目，集成 `org.webrtc` 库，实现信令 WebSocket 客户端（join/offer/answer/ICE 交换）。

## 涉及层
- [ ] 数据层：`android-client/` 目录结构 — `build.gradle` + `settings.gradle` + `AndroidManifest.xml`
- [ ] 逻辑层：`org.webrtc` 依赖配置（从自编译 libwebrtc 产物或 `google-webrtc` AAR）
- [ ] 逻辑层：`SignalingClient.java` — WebSocket 连接 + JSON 消息（join/offer/answer/ice-candidate）
- [ ] 逻辑层：`MainActivity.java` — 入口 Activity，输入服务器地址 + room ID + token，发起连接
- [ ] 测试：连接信令服务器，完成 login → join 握手，日志确认

## 验收标准
- `./gradlew assembleDebug` 编译通过
- 安装到 Android 设备，输入信令地址后成功连接
- 日志显示 WebSocket 握手成功 + join 消息发送 + 服务器响应
- 错误场景：服务器不可达时显示友好提示
