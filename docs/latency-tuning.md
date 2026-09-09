# 云手机串流延迟专项攻坚分析

> 目标：把 scrcpy 改造的 WebRTC 云手机串流压到 <50ms 端到端（竞技射击级），覆盖三个瓶颈：延迟、帧率稳定、输入反馈。

## 核心结论

**scrcpy 改造这套架构能支撑 <50ms 的攻坚，不需要全部重写。** 真正的壁垒不在 scrcpy（它已足够低延迟），而在三个容易忽略的配置点 + 网络 RTT。

## ⚠️ 最关键的隐藏事实

**libwebrtc 的视频 jitter buffer 默认不走低延迟**——只有 `min_playout_delay == 0` 且 `max_playout_delay <= 500ms` 才启用低延迟渲染，而**默认 max_playout_delay 是 10 秒**（`modules/video_coding/timing/timing.cc:33-35`）。

不显式配置 playout-delay，串流会默认带几十~上百 ms jitter buffer 延迟。

## 延迟预算（<50ms 目标）

| 环节 | 现状 | 攻坚后 | 手段 |
|---|---|---|---|
| 采集（SF vsync） | 8-17ms | 8-17ms（120Hz→4-8ms） | 硬件固定 |
| 编码（MediaCodec） | 5-15ms | 2-8ms | 强制 no-B-frame |
| jitter buffer | 默认几十~上百ms | 0ms | 强制 playout-delay |
| 解码（客户端 HW） | 5-15ms | 2-8ms | 客户端 |
| 显示（客户端 vsync） | 5-16ms | 5-16ms | 客户端 |
| 网络（RTT/2） | 距离决定 | <5ms（同城） | 边缘部署 |

攻坚后理论合计 ~22ms（同城极限），加裕量 <50ms 可达。

## 攻坚点（按优先级）

### 🥇 P0-1 强制 jitter buffer 0ms（libwebrtc，收益最大）

两个 field trial（零代码）：
```
WebRTC-ForceSendPlayoutDelay/min_ms:0/max_ms:0   (发送端)
WebRTC-ForcePlayoutDelay/min_ms:0/max_ms:0       (接收端)
```
或发送端带 playout-delay RTP header extension。

⚠️ 陷阱：浏览器 `RTCRtpReceiver.jitterBufferTarget` 设非零会禁用低延迟路径；设 0 也不启用。弱网下建议 `0:200`（保留抗抖动上限，仍满足 ≤500ms 阈值）。

### 🥇 P0-2 编码器 no-B-frame + CBR + 读回验证（scrcpy 侧）

`SurfaceEncoder.createFormat()`（line 310-348）里：
```java
format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.H264ProfileConstrainedBaseline);
format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
```
关键：configure 后读回 `getOutputFormat()` 验证 vendor 是否采纳 `KEY_LATENCY=1`。B 帧/lookahead 会插 1-2 帧重排序延迟。

### 🥈 P1-1 A/V 同步隔离

NetEQ 音频 delay manager 从 80ms 收敛到 ~20ms，且 **A/V 同步会把音频延迟静默推给视频**（`syncable_minimum_playout_delay_`）。游戏场景优先视频延迟，隔离音频同步。

### 🥈 P1-2 UHID 手柄输入

`UhidManager` 写 `/dev/uhid` 直达 kernel input stack，绕过 `injectInputEvent`（InputDispatcher ~1-5ms）。射击游戏摇杆/手柄走 UHID。

### 🥉 P2 客户端输入零缓冲

服务端输入已最优（ASYNC 注入，无批处理）。瓶颈在客户端事件 → 上行 → 注入 → 渲染 → 下行闭环，客户端侧要做「输入事件零缓冲直达 DataChannel」。

### 🥉 P2 120Hz 显示（可选）

vsync 量化 8-16ms → 4-8ms，但要整链 120fps（120 采集进 60 编码器会被丢帧，无益）。

## 帧率稳定

帧率稳定 = 帧间隔抖动小，不是平均 60fps。手段：
- `KEY_BITRATE_MODE = CBR`（VBR 码率尖峰 → 编码器吞吐不足 → 丢帧）
- 足够码率 + 长 I 帧间隔
- `KEY_INTRA_REFRESH_PERIOD`（分散关键帧，避免周期尖峰）
- 监控两个信号：`MediaCodec.Callback` 输出间隔（编码器阻塞）+ 虚拟显示帧回调间隔（SF 阻塞）

## 关键源码锚点

- 采集直连路径（无 GL filter）：`ScreenCapture.java:127-129` / `NewDisplayCapture.java:266-285`
- GL filter（crop/rotation 才插入，+0.5-3ms）：`OpenGLRunner.java:164-197`
- 编码器配置（`KEY_LATENCY=1`/`KEY_PRIORITY=0` 已设，缺 profile/CBR）：`SurfaceEncoder.java:310-348`
- 阻塞 drain loop（无 drop 策略）：`SurfaceEncoder.java:252-278`
- 输入注入（已最优 ASYNC）：`Controller.java:512-621` / `InputManager.java:48-73`
- UHID 手柄路径：`UhidManager.java:150-163`

## 诚实结论

| 问题 | 答案 |
|---|---|
| <50ms 可达？ | ✅ 理论可达（同城极限 ~22ms） |
| 卡在哪？ | 编码器 B 帧 + libwebrtc jitter buffer 默认 10s |
| 需要全部重写？ | ❌ 不需要，都是配置 + 几行代码 |
| 最大风险？ | 理论 vs 实测（vendor 支持、vsync、抖动需 systrace 验证） |

## 参考

- libwebrtc 低延迟旋钮详解：`docs/webrtc-low-latency-knobs.md`
- MediaCodec/虚拟显示延迟下限：AOSP `VirtualDisplaySurface.cpp`、`MediaFormat.java`
- 腾讯 START 云游戏（NSDI 2024）：目标 <20ms，生产端到端 50-70ms
