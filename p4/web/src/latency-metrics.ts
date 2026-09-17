// Cumulative counters are differenced per stream/session, never per wall clock.
export function meanDelta(sum: number, count: number, oldSum: number, oldCount: number, scale: number): number | null {
  if (![sum, count, oldSum, oldCount].every(Number.isFinite) || count <= oldCount || sum < oldSum) return null;
  return (sum - oldSum) / (count - oldCount) * scale;
}
type Stat = { id: string; type: string; kind?: string; mediaType?: string;
  totalPacketSendDelay: number; packetsSent: number; jitterBufferDelay: number; jitterBufferEmittedCount: number };
type Telemetry = { type: string; captureUs: number; captureFrames: number; outbound: Stat[] };
export function streamMean(rows: Stat[], previous: Map<string, Stat>, sending: boolean): number | null {
  let sum = 0, count = 0;
  const next = new Map<string, Stat>();
  for (const row of rows) {
    if ((row.kind ?? row.mediaType) !== 'video') continue;
    next.set(row.id, row);
    const old = previous.get(row.id);
    if (!old) continue;
    const s = sending ? 'totalPacketSendDelay' : 'jitterBufferDelay';
    const c = sending ? 'packetsSent' : 'jitterBufferEmittedCount';
    if (meanDelta(row[s], row[c], old[s], old[c], 1) === null) continue;
    sum += row[s] - old[s]; count += row[c] - old[c];
  }
  previous.clear(); next.forEach((row, id) => previous.set(id, row));
  return count ? sum / count * 1000 : null;
}
export function mountLatencyMetrics(video: HTMLVideoElement,
    getPeer: () => RTCPeerConnection | null, getChannel: () => RTCDataChannel | null): void {
  const bar = document.createElement('div');
  bar.style.cssText = 'display:flex;gap:20px;flex-wrap:wrap;padding:12px;color:#ddd;background:#19232e';
  const labels = ['采集帧龄', '发送排队', '接收缓冲'];
  const tips = ['屏幕 PTS 到服务端 JNI 入口，含编码；区间每帧均值',
    '视频 RTP 包进入发送缓冲到发出；区间每包均值，不含网络传输',
    '浏览器视频抖动缓冲驻留；区间每帧均值，不含后续显示'];
  const nodes = labels.map((_, i) => { const n = document.createElement('span'); n.title = tips[i]; bar.append(n); return n; });
  video.closest('.stage')!.before(bar);
  const show = (i: number, value: number | null): void => {
    nodes[i].textContent = `${labels[i]}：${value === null ? '—' : value.toFixed(1) + ' ms'}`;
  };
  let owner: RTCPeerConnection | null = null, channel: RTCDataChannel | null = null;
  let previous: Telemetry | null = null, lastReply = 0, requestedAt = 0, busy = false;
  const outbound = new Map<string, Stat>(), inbound = new Map<string, Stat>();
  const clear = (): void => { previous = null; outbound.clear(); inbound.clear(); lastReply = 0; requestedAt = 0; nodes.forEach((_, i) => show(i, null)); };
  const message = (event: MessageEvent): void => {
    if (typeof event.data !== 'string' || event.currentTarget !== getChannel() || owner !== getPeer()) return;
    try {
      const row = JSON.parse(event.data) as Telemetry;
      if (row.type !== 'scrcpy.latency.v1' || !Array.isArray(row.outbound)
          || !Number.isFinite(row.captureUs) || !Number.isFinite(row.captureFrames)) return;
      show(0, previous ? meanDelta(row.captureUs, row.captureFrames, previous.captureUs, previous.captureFrames, 0.001) : null);
      show(1, streamMean(row.outbound, outbound, true));
      previous = row; lastReply = performance.now(); requestedAt = 0;
    } catch { /* Binary control and unrelated messages do not affect metrics. */ }
  };
  clear();
  const timer = window.setInterval(async () => {
    const pc = getPeer(), dc = getChannel();
    if (owner !== pc || channel !== dc) {
      channel?.removeEventListener('message', message);
      owner = pc; channel = dc; channel?.addEventListener('message', message); clear();
    }
    if (!pc || pc.connectionState !== 'connected') { clear(); return; }
    const now = performance.now();
    if (lastReply && now - lastReply > 3500) { previous = null; outbound.clear(); show(0, null); show(1, null); }
    if (dc?.readyState === 'open' && dc.bufferedAmount < 16384 && (!requestedAt || now - requestedAt > 5000)) {
      try { dc.send('scrcpy.latency.v1'); requestedAt = now; } catch { show(0, null); show(1, null); }
    }
    if (busy) return;
    busy = true;
    try {
      const report = await pc.getStats();
      if (pc !== getPeer() || pc.connectionState !== 'connected') return;
      const rows: Stat[] = [];
      report.forEach(row => { if (row.type === 'inbound-rtp') rows.push(row as Stat); });
      show(2, streamMean(rows, inbound, false));
    } catch { inbound.clear(); show(2, null); }
    finally { busy = false; }
  }, 1000);
  window.addEventListener('pagehide', event => {
    if (event.persisted) { clear(); return; }
    clearInterval(timer); channel?.removeEventListener('message', message);
  });
}
