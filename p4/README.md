# P4 平台层：端到端联调说明

信令**内嵌在 scrcpy-server**（每个云机自己处理信令），客户端直接连云机，P2P 直连优先，失败走 TURN 中继。

## 架构

```
浏览器(Web 客户端) ←──P2P 直连/ TURN 中继──→ 云机(scrcpy-server)
        │                                          │
        └── WebSocket 信令(直接) ──────────────────┘
               客户端 → 云机: offer / ice
               云机 → 客户端: answer / ice
```

**无独立中心信令服务器**。信令交换直接发生在客户端 ↔ scrcpy-server 之间（scrcpy-server 内嵌 `SignalServer` WebSocket 服务器）。

## 组件

| 组件 | 位置 | 启动 |
|---|---|---|
| TURN | `p4/turn/turnserver.conf` | `turnserver -c turnserver.conf` |
| Web 客户端 | `p4/web/index.html` | 浏览器打开，填 `ws://云机IP:端口`，点 Connect |
| 云机 | scrcpy-server（改造后） | 见下方「云机侧」 |

## 云机侧（scrcpy-server）启动流程

1. `ServerService` 启动 → `Server.scrcpy(context, options)`（`--signal-port=PORT` 指定信令端口）
2. 采集（`MediaProjectionCapture`）→ MediaCodec 编码（VPU）
3. `NativeEncoderBridge.createTrackSource()` → 创建 `EncodedVideoTrackSource`
4. `NativeEncoderBridge.createPeerConnection()` → `ScrcpyPeerConnection.Initialize()`（注册 C++ factory + 建 PeerConnection + AddTrack）
5. `SignalServer.start()` → 内嵌 WebSocket 服务器监听 `signal-port`
6. 信令流程（自动）：
   - 客户端连 `ws://云机IP:port` → 发 `offer`
   - 云机 `SignalServer` 收 `offer` → `NativeEncoderBridge.onOffer(sdp)` → `ScrcpyPeerConnection.OnOffer`
   - `SetRemoteDescription` → `CreateAnswer` → `SetLocalDescription` → 序列化 answer → `onAnswer` 回调 → `SignalServer.sendAnswer`
   - ICE 双向：`PeerObserver.OnIceCandidate` → `onIceCandidate` 回调 → `sendIceCandidate`
7. 媒体流：`EncodedVideoTrackSource` → kNative buffer → `ScrcpyPassthroughEncoder` → RTP → 浏览器

## 消息格式（信令，JSON）

| 类型 | 方向 | 字段 |
|---|---|---|
| `offer` | 客户端 → 云机 | `{type, sdp}`（sdp 是字符串） |
| `answer` | 云机 → 客户端 | `{type, sdp}` |
| `ice` | 双向 | `{type, sdpMid, sdpMLineIndex, candidate}` |

## 端到端联调步骤

1. 部署云机：定制 AOSP + platform 签名 + `/system/priv-app` + init.rc 自启 `ServerService`
2. 启动云机：`ServerService` 带 `--signal-port=8080` 参数
3. （可选）部署 TURN：`turnserver -c turnserver.conf`
4. 客户端：浏览器打开 `p4/web/index.html`，填 `ws://云机IP:8080`，点 Connect
5. 验证：视频画面 + 弱网降档
