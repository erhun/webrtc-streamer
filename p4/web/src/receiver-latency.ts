// A best-effort target, not a hard cap on the browser's jitter buffer.
type LowLatencyReceiver = {
  jitterBufferTarget?: number | null;
  playoutDelayHint?: number;
};

export function configureVideoLatency(receiver: LowLatencyReceiver): string {
  if ('jitterBufferTarget' in receiver) {
    try {
      receiver.jitterBufferTarget = 50; // Standard API uses milliseconds.
      return '视频接收缓冲目标请求：50ms（jitterBufferTarget）';
    } catch { /* Try the older Chromium hint if the standard setter rejects. */ }
  }
  if ('playoutDelayHint' in receiver) {
    try {
      receiver.playoutDelayHint = 0.05; // Legacy API uses seconds.
      return '视频接收缓冲目标请求：50ms（playoutDelayHint）';
    } catch {
      return '视频接收缓冲目标设置失败，保留浏览器默认策略';
    }
  }
  return '浏览器不支持视频接收缓冲目标设置，保留默认策略';
}
