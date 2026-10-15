# ADR-0001：会话统一持有媒体 peer，信令连接允许替换

状态：已在代码实现；设备集成验收待完成。

## 背景

原 Server 同时组装采集器、管理主/附加 bridge、路由控制和解析房间事件。
WebSocket 关闭会直接销毁媒体；同 clientId 替换路径跳过旧 peer 清理；附加 peer 失败可以停止整个服务。

## 决策

- 保留模块化单体和原 TCP 路线。
- 引入 StreamingSession 作为全部媒体 peer 的唯一所有者；取消主 bridge 特例。
- 信令协议状态由单线程 executor 管理，PeerLease 分离 socket owner 和逻辑媒体身份。
- 使用独立秘密授权恢复；重建媒体只针对页面身份变化或终止故障。
- 故障向最近资源所有者传播：peer 故障只回收 peer，采集故障才终止整个服务。
- room/client 复合身份用于路由，回调代次阻止重建前的协商污染新连接。
- 控制权、共享码率和编码配置分别由 ControlManager、BitrateAggregator、VideoAdaptationPolicy 决策。
- WebRTC 音频只保留内置 Opus 路线；统一编码输出所有权。

## 代价与限制

协议 v3 与 JNI 签名变化需要整体升级；默认鉴权和 loopback 监听会要求旧部署显式迁移。
保留 MediaCodec 预编码到 VideoStreamEncoder 的适配，避免在没有工具链与设备时盲改媒体核心。
这项适配的丢帧/参考链风险是独立发布门槛，不能凭本 ADR 或 mock 测试宣称解决。
控制者优先策略可能牺牲弱观看者体验；多档编码与 SFU 是后续独立能力。
