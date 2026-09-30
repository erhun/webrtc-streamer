# Android 客户端：音频播放

**Blocked by**: T05

## 目标
接收 WebRTC 音频 track 的 Opus 解码 PCM 数据，通过 `AudioTrack` 播放到 Android 设备扬声器。

## 涉及层
- [ ] 逻辑层：`AudioTrack` 创建 — 48kHz/2ch/16-bit PCM，`MODE_STREAM`
- [ ] 逻辑层：`onAddStream()` 中提取 audio track → 注册 `AudioSource` 回调（或使用 WebRTC 内置 ADM 路由到 AudioTrack）
- [ ] 逻辑层：PCM 数据喂入 `AudioTrack.write()` 循环
- [ ] 测试：云机音频从 Android 设备扬声器输出

## 验收标准
- 云机播放音乐时 Android 设备扬声器有声音输出
- 音频无爆音、无明显延迟（< 200ms 音频延迟）
- 视频 + 音频同步（唇音同步无明显偏差）
- 静音场景：云机无音频输出时 Android 端无噪音
