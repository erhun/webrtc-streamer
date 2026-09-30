# 视频帧广播

**Blocked by**: T10

## 目标
将 MediaCodec 编码帧 fan-out 到所有客户端的 `EncodedVideoTrackSource`，使多个客户端同时收到相同的视频流。

## 涉及层
- [x] 逻辑层（Java）：`BroadcastVideoSink`（新增，实现 `PacketSink`）— 持有 N 个 `NativeEncoderBridge` session 引用，`writePacket()` 时广播到所有 session
- [x] 逻辑层（Java）：`Server.java` — 视频 `PacketSink` 从 `WebRtcPacketSink` 替换为 `BroadcastVideoSink`
- [x] 逻辑层（Java）：新客户端加入时 `BroadcastVideoSink.addBridge(bridge)`，离开时 `removeBridge(bridge)`
- [x] 逻辑层（C++）：每个 session 的 `EncodedVideoTrackSource` 独立接收帧（无需改动 C++ 层广播逻辑）

## 验收标准
- 2 个 H5 客户端同时连接，均收到视频流，画面内容完全相同
- 新客户端加入时立即开始收到后续帧（无需等待关键帧即可显示，但首帧可能是花屏直到下一个 IDR）
- 客户端离开后不影响其余客户端的视频流
- SPS/PPS 正确 prepend 到每个客户端的 IDR 前
