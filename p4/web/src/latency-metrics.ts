import { attachMetricsBar } from './metrics-view';

// Cumulative counters are differenced per stream/session, never per wall clock.
export function meanDelta(sum: number, count: number, oldSum: number, oldCount: number, scale: number): number | null {
  if (![sum, count, oldSum, oldCount].every(Number.isFinite) || count <= oldCount || sum < oldSum) return null;
  return (sum - oldSum) / (count - oldCount) * scale;
}
type Stat = { id: string; type: string; kind?: string; mediaType?: string;
  timestamp?: number; framesEncoded?: number;
  totalPacketSendDelay: number; packetsSent: number; jitterBufferDelay: number; jitterBufferEmittedCount: number;
  jitterBufferTargetDelay: number; jitterBufferMinimumDelay: number; totalDecodeTime: number; framesDecoded: number };
type NetworkStat = { id: string; type: string; selectedCandidatePairId?: string; availableOutgoingBitrate?: number };
type Telemetry = { targetBps?: number; bandwidthAllocationBps?: number; rateUpdatedUs?: number; network?: NetworkStat[]; type: string; captureUs: number; captureFrames: number; allocatedBps: number; configuredBps: number; encodedBytes: number; sampleUs: number; outbound: Stat[] };
export function encodedFrameRate(rows: Stat[], previous: Map<string, Stat>): number | null {
  let fps = 0, measured = false;
  const next = new Map<string, Stat>();
  for (const row of rows) {
    if ((row.kind ?? row.mediaType) !== 'video') continue;
    next.set(row.id, row);
    const old = previous.get(row.id);
    if (!old) continue;
    const rate = meanDelta(row.framesEncoded!, row.timestamp!, old.framesEncoded!, old.timestamp!, 1000);
    if (rate !== null) { fps += rate; measured = true; }
  }
  previous.clear(); next.forEach((row, id) => previous.set(id, row));
  return measured ? fps : null;
}
export function streamMean(rows: Stat[], previous: Map<string, Stat>, sending: boolean,
    metric: 'jitterBufferDelay' | 'jitterBufferTargetDelay' | 'jitterBufferMinimumDelay' | 'totalDecodeTime' = 'jitterBufferDelay'): number | null {
  let sum = 0, count = 0;
  const next = new Map<string, Stat>();
  for (const row of rows) {
    if ((row.kind ?? row.mediaType) !== 'video') continue;
    next.set(row.id, row);
    const old = previous.get(row.id);
    if (!old) continue;
    const s = sending ? 'totalPacketSendDelay' : metric;
    const c = sending ? 'packetsSent' : metric === 'totalDecodeTime' ? 'framesDecoded' : 'jitterBufferEmittedCount';
    if (meanDelta(row[s], row[c], old[s], old[c], 1) === null) continue;
    sum += row[s] - old[s]; count += row[c] - old[c];
  }
  previous.clear(); next.forEach((row, id) => previous.set(id, row));
  return count ? sum / count * 1000 : null;
}
export function outgoingEstimate(rows: NetworkStat[]): number | null {
  const byId = new Map(rows.map(row => [row.id, row]));
  for (const row of rows) {
    if (row.type !== 'transport' || !row.selectedCandidatePairId) continue;
    const pair = byId.get(row.selectedCandidatePairId);
    if (pair?.type === 'candidate-pair' && typeof pair.availableOutgoingBitrate === 'number'
        && Number.isFinite(pair.availableOutgoingBitrate) && pair.availableOutgoingBitrate >= 0) {
      return pair.availableOutgoingBitrate;
    }
  }
  return null;
}

export function telemetryIntervalMs(allocatedBps: number): number {
  return Number.isFinite(allocatedBps) && allocatedBps >= 0 && allocatedBps <= 128000 ? 5000
    : Number.isFinite(allocatedBps) && allocatedBps >= 0 && allocatedBps <= 512000 ? 2000 : 1000;
}

export function bufferHealth(send: number | null, receive: number | null,
    target: number | null, minimum: number | null): { text: string; warning: boolean } {
  if (send !== null && send > 150) return { text: '缓冲诊断：发送等待偏高', warning: true };
  if (receive !== null && receive > 200) return { text: '缓冲诊断：接收驻留偏高', warning: true };
  if ((target ?? 0) > 200 || (minimum ?? 0) > 200) {
    return { text: '缓冲诊断：目标/最低缓冲偏高', warning: true };
  }
  return { text: send === null || receive === null ? '缓冲诊断：采样中' : '缓冲诊断：区间等待较低', warning: false };
}

export function mountLatencyMetrics(video: HTMLVideoElement,
    getPeer: () => RTCPeerConnection | null, getChannel: () => RTCDataChannel | null, container?: HTMLElement): void {
  const bar = document.createElement('div');
  bar.className = 'stream-metrics';
  const labels = ['采集帧龄', '发送排队', '接收缓冲', '分配码率', '配置码率', '编码输出', '目标缓冲', '最低缓冲', '解码耗时',
    'JNI 输入样本率', 'WebRTC 帧率', '视频目标预算', '视频带宽分配', '反馈龄', '链路估计'];
  const tips = ['屏幕 PTS 到服务端 JNI 入口，含编码；区间每帧均值；服务端遥测随码率每 1/2/5 秒采样',
    '本区间已发出的视频 RTP 包的平均本地等待时间；不是当前队列长度或最老包年龄，不含网络传输',
    '浏览器视频抖动缓冲驻留；区间每帧均值，不含后续显示',
    'WebRTC SetRates.bitrate：经编码器超调修正后的预算，不等于原始视频目标或链路总带宽',
    'MediaCodec 最近成功配置的目标码率；暂停时可能保留旧配置',
    'JNI 收到的视频编码字节增量，含关键帧参数集，不含 RTP 开销',
    '浏览器统计的视频目标缓冲；区间每帧均值，不等于请求的 50ms',
    '浏览器估计的最低视频缓冲；区间每帧均值',
    '视频解码累计耗时增量除以解码帧数增量',
    '服务端 JNI 有效单调 PTS 帧龄样本的增量/时间；不包含无效 PTS 帧，不是源应用绘制频率',
    'outbound-rtp framesEncoded 增量/时间；passthrough 中表示 WebRTC 接收编码回调，不是 MediaCodec 重编码',
    'SetRates.target_bitrate：编码器超调修正前的视频目标；不是实际发送码率',
    'SetRates.bandwidth_allocation：给视频的带宽分配，可能高于编码目标；不是线路测速',
    '距最近一次 SetRates 回调的单调时间；回调按变化触发，反馈龄高不代表链路故障',
    '发送端当前选中 ICE candidate-pair 的 availableOutgoingBitrate，可能包含音频和其他发送媒体；不是线路测速'];
  const nodes = labels.map((_, i) => { const n = document.createElement('span'); n.title = tips[i]; bar.append(n); return n; });
  if (!attachMetricsBar(video, bar, container)) return;
  const show = (i: number, value: number | null): void => {
    nodes[i].textContent = `${labels[i]}：${value === null ? '—' : i === 9 || i === 10 ? value.toFixed(1) + ' fps'
      : ((i < 3 || (i >= 6 && i <= 8) || i === 13) ? value.toFixed(1) + ' ms' : (value / 1000).toFixed(0) + ' kbps')}`;
  };
  const health = document.createElement('span');
  bar.append(health);
  let sendDelay: number | null = null, receiveDelay: number | null = null;
  let targetDelay: number | null = null, minimumDelay: number | null = null;
  health.title = '区间均值和缓冲目标的诊断，不是采集到屏幕的端到端延迟；不同指标不能直接相加';
  const updateHealth = (): void => {
    const state = bufferHealth(sendDelay, receiveDelay, targetDelay, minimumDelay);
    health.textContent = state.text;
    health.style.color = state.warning ? '#ffb86c' : '#ddd';
  };
  let owner: RTCPeerConnection | null = null, channel: RTCDataChannel | null = null;
  let previous: Telemetry | null = null, lastReply = 0, requestedAt = 0, busy = false, telemetryInterval = 1000;
  const outbound = new Map<string, Stat>(), inbound = new Map<string, Stat>();
  const encodedFrames = new Map<string, Stat>();
  const detailCaches = [new Map<string, Stat>(), new Map<string, Stat>(), new Map<string, Stat>()];
  const clear = (): void => { previous = null; outbound.clear(); inbound.clear(); encodedFrames.clear(); detailCaches.forEach(cache => cache.clear()); lastReply = 0; requestedAt = 0; telemetryInterval = 1000; nodes.forEach((_, i) => show(i, null)); sendDelay = receiveDelay = targetDelay = minimumDelay = null; updateHealth(); };
  const message = (event: MessageEvent): void => {
    if (typeof event.data !== 'string' || event.currentTarget !== getChannel() || owner !== getPeer()) return;
    try {
      const row = JSON.parse(event.data) as Telemetry;
      if (row.type !== 'scrcpy.latency.v1' || !Array.isArray(row.outbound)
          || !Number.isFinite(row.captureUs) || !Number.isFinite(row.captureFrames)) return;
      show(0, previous ? meanDelta(row.captureUs, row.captureFrames, previous.captureUs, previous.captureFrames, 0.001) : null);
      sendDelay = streamMean(row.outbound, outbound, true);
      show(1, sendDelay);
      show(3, Number.isFinite(row.allocatedBps) && row.allocatedBps >= 0 ? row.allocatedBps : null);
      show(4, Number.isFinite(row.configuredBps) && row.configuredBps >= 0 ? row.configuredBps : null);
      show(5, previous ? meanDelta(row.encodedBytes, row.sampleUs, previous.encodedBytes, previous.sampleUs, 8000000) : null);
      show(9, previous ? meanDelta(row.captureFrames, row.sampleUs, previous.captureFrames, previous.sampleUs, 1000000) : null);
      show(10, encodedFrameRate(row.outbound.map(stat => ({ ...stat, timestamp: row.sampleUs / 1000 })), encodedFrames));
      show(11, typeof row.targetBps === 'number' && Number.isFinite(row.targetBps) && row.targetBps >= 0 ? row.targetBps : null);
      show(12, typeof row.bandwidthAllocationBps === 'number' && Number.isFinite(row.bandwidthAllocationBps)
        && row.bandwidthAllocationBps >= 0 ? row.bandwidthAllocationBps : null);
      show(13, typeof row.rateUpdatedUs === 'number' && row.rateUpdatedUs >= 0
        && Number.isFinite(row.sampleUs) && row.sampleUs >= row.rateUpdatedUs ? (row.sampleUs - row.rateUpdatedUs) / 1000 : null);
      show(14, Array.isArray(row.network) ? outgoingEstimate(row.network) : null);
      updateHealth();
      previous = row; lastReply = performance.now(); requestedAt = 0; telemetryInterval = telemetryIntervalMs(row.allocatedBps);
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
    if (lastReply && now - lastReply > Math.max(3500, telemetryInterval * 2 + 1000)) { previous = null; outbound.clear(); encodedFrames.clear(); show(0, null); show(1, null); [3, 4, 5, 9, 10, 11, 12, 13, 14].forEach(i => show(i, null)); sendDelay = null; updateHealth(); }
    if (dc?.readyState === 'open' && dc.bufferedAmount < 1024
        && (!lastReply || now - lastReply >= telemetryInterval) && (!requestedAt || now - requestedAt > 5000)) {
      try { dc.send('scrcpy.latency.v1'); requestedAt = now; } catch { show(0, null); show(1, null); [3, 4, 5, 9, 10, 11, 12, 13, 14].forEach(i => show(i, null)); sendDelay = null; updateHealth(); }
    }
    if (busy) return;
    busy = true;
    try {
      const report = await pc.getStats();
      if (pc !== getPeer() || pc.connectionState !== 'connected') return;
      const rows: Stat[] = [];
      report.forEach(row => { if (row.type === 'inbound-rtp') rows.push(row as Stat); });
      receiveDelay = streamMean(rows, inbound, false);
      show(2, receiveDelay);
      const metrics = ['jitterBufferTargetDelay', 'jitterBufferMinimumDelay', 'totalDecodeTime'] as const;
      metrics.forEach((metric, i) => {
        const value = streamMean(rows, detailCaches[i], false, metric);
        show(6 + i, value);
        if (i === 0) targetDelay = value;
        if (i === 1) minimumDelay = value;
      });
      updateHealth();
    } catch {
      if (pc !== getPeer()) return;
      inbound.clear(); detailCaches.forEach(cache => cache.clear());
      [2, 6, 7, 8].forEach(i => show(i, null)); receiveDelay = targetDelay = minimumDelay = null; updateHealth();
    }
    finally { busy = false; }
  }, 1000);
  window.addEventListener('pagehide', event => {
    if (event.persisted) { clear(); return; }
    clearInterval(timer); channel?.removeEventListener('message', message);
  });
}
