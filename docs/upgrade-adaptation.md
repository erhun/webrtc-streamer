# 升档控制与低帧率排查

## 当前控制策略

- 每次只升一级，下一次升级需要重新满足 5 秒候选窗口。严重降档后的恢复也使用完整窗口。
- 低于下一档升级阈值、显式零预算或 peer reset 会取消升档候选；保留普通降档的 3 秒防抖和严重降档的 1 秒冷却。
- 每次收到预算变化都更新梯子，避免编码线程合并反馈漏掉短暂低预算。
  编码线程在同一把锁下读取预算和档位快照，MediaCodec 操作仍在编码线程。
- MediaCodec 目标直接取 `min(SetRates 分配预算, 当前档位目标)`，下降立即应用，恢复预算也立即应用于当前档位。
  不再额外叠加 Java 码率爬升；分辨率和帧率档位的升级仍受单级升级和防抖约束。
- 不新增发送缓存，不改变 WebRTC 的拥塞控制、packet pacer、NACK 或 FEC。

## 撤回的两项限制及原因

`d7fcca1` 加入的 `BitrateRamp` 只在新的码率反馈到来时推进。`SetRates` 是配置变化通知，
不是周期心跳：上游 `VideoStreamEncoder::SetEncoderRates` 只在 rate control 参数改变时调用它。
所以目标恢复后，不能假定后续必定还有回调来完成斜坡。

已用原始 Java 类复现：当前 500 kbps，恢复到本档 1.2 Mbps 时，一次反馈只应用到 625 kbps；
若不再收到新的反馈，Java 不会继续提高码率。这是额外限速的缺陷，但尚不能证明它是实网 1 fps 的唯一原因。
该类现已移除，收到恢复预算时不再依赖下一次反馈才能达到本档目标。

同样，原来的 `maxFeedbackGapMs=2000` 错把参数没有变化当作反馈失效。
每 3 秒一次的 8 Mbps 反馈持续 30 秒，原实现仍停留在最低档，现已回归覆盖并修正。
回调间隔不再清空候选；静默本身也不触发定时升档，只有新的预算事件才能执行升档。
真实断连/暂停需要明确的连接状态或预算通知，不能用 SetRates 没有调用推断。

参考上游接口实现（最终以部署的 libwebrtc revision 为准）：
https://webrtc.googlesource.com/src/+/refs/heads/main/video/video_stream_encoder.cc

## 指标不能混为一谈

- `scrcpy_passthrough_encoder.cc` 的 `Encode: #N` 日志除开始几帧外每 10 帧打印一次。
  单会话中，#100 到 #110 相隔 350 ms 表示约 28.6 fps，不是 2.86 fps。
  `OnEncodedFrame` 的普通日志每 60 帧打印一次，`OnEncodedImage result` 每 30 次打印一次。
  这些计数器目前是静态变量，多会话时也不能当作某一路的独立帧率。
- 如果日志明确是 `Encode slow: inter-frame=350ms`，则是一次真实的出帧间隔；
  要判断持续低帧率，应看连续多个 `Encode stats: frames=30 duration=... fps=...` 窗口，且测试画面应持续运动。
- H5 原来的“帧率”来自 `framesDecoded` 增量，现改名“解码帧率”。它不是编码帧率，也不是实际屏幕呈现帧率。
- “发送排队”是 `ΔtotalPacketSendDelay / ΔpacketsSent`，表示已发出包的区间平均等待时间，
  不等于当前队列长度、最老包年龄或持续积压。H5 不再把超过 150 ms 直接判定成“发送积压”。
- “目标缓冲”是 `ΔjitterBufferTargetDelay / ΔjitterBufferEmittedCount`，
  不是实际缓冲驻留；应与“接收缓冲”“最低缓冲”同时观察。不能把发送等待和目标缓冲直接相加当作端到端实测延迟。
- “分配码率”是编码预算，“配置码率”是 MediaCodec 目标，“编码输出”是实际 JNI 视频字节速率。
  浏览器 candidate-pair 的 `availableOutgoingBitrate` 是浏览器发送方向的估计，不能当作服务端视频下行预算。
  码率低通常会减少发送负载，不能只凭低利用率就断言是发送队列积压的原因。
- 此次撤回的实现是每秒 25% 增长并设有低码率 100 kbps/s 下限，不是“每次最多增加 20%”。
  实际输出字节波动、跨多个采样间隔的增幅以及配置目标增幅也不能混用。

## 验证

```sh
python3 tests/run_video_adaptation.py
node p4/web/node_modules/typescript/bin/tsc -p p4/web/tsconfig.json
```

Java 脚本需要带 `jdk.compiler` 的 JDK 17 或更新版本，可用 `JAVA` 选择执行文件。
脚本使用项目真实类，以 `-Xlint:all -Werror` 编译，覆盖跨级尖峰、逐级候选窗口、严重降档恢复、
短暂低预算、零预算、peer reset、稀疏反馈、时钟回退及降档防抖，并解析全部服务端 Java 源码。
语法解析不代替 Android 编译。该测试已接入 `tests/run_regressions.py`。

构建环境具备 Android SDK 和 Gradle 时还需执行：

```sh
./gradlew -p server test checkstyle lint
./gradlew -p server assembleDebug
```

当前环境无法下载所需 Gradle，未完成 Android 构建或真机验证。
完整轻量回归另有既有的 `NativeEncoderBridge` / `Ln` 编译依赖缺失问题。
本次不修改 JNI，也不生成 APK；验证行为需要重新构建并部署服务端 Java 和 H5。

## 真机验收

使用同一设备、同一运动画面和同一网络条件，对比本次修复与 `d7fcca1`。
连续记录至少 30 秒：分配码率、配置码率、编码输出、Codec 的实际出帧统计、H5 解码帧率、
接收缓冲、最低缓冲、目标缓冲、发送包等待以及 NACK/PLI 增量。

1. 预算由低恢复后，配置码率应在下一次反馈处理时达到本档预算，而非停在一个中间值。
2. 分辨率依然逐级升级，短暂高预算不允许直接跳到最高档。
3. 如果持续运动时 Codec 的出帧确实只有 3 fps，检查采集、编码器输出、同步 JNI 调用耗时和反复 reset。
4. 如果 Codec 出帧正常而 H5 解码低，检查 passthrough 之前的 WebRTC 丢帧、参考帧依赖、RTP/RTX 和 PLI。
5. 如果解码正常但画面每秒只刷新一次，检查实际呈现、主线程阻塞、后台节流和音视频同步。

关键帧请求合并、真实采集帧率控制和 pacer 队列参与升档决策仍是独立的后续事项。
本次修复只消除已复现的额外控制回归，不宣称已定位或消除所有实网卡顿。
