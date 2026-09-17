// Quality is an application estimate from RTT/loss, not radio RSSI.
export function mountMetrics(video: HTMLVideoElement, getPeer: () => RTCPeerConnection | null): void {
  const bar = document.createElement('div');
  bar.className = 'stream-metrics';
  bar.setAttribute('aria-label', '实时串流统计');
  const fields = ['帧率', '视频码率', '分辨率', '连接质量', '往返延迟', '丢包率'].map(label => {
    const node = document.createElement('span');
    bar.append(node);
    return { node, label };
  });
  fields[3].node.className = 'quality';
  fields[3].node.title = '根据往返延迟和接收丢包率估算，不是 Wi-Fi 信号强度';
  video.closest('.stage')!.before(bar);
  const show = (values: string[], level = ''): void => {
    fields.forEach((field, i) => { field.node.textContent = `${field.label}：${values[i]}`; });
    fields[3].node.dataset.level = level;
  };
  type Sample = { timestamp: number; bytes: number; frames: number; received: number; lost: number };
  let previous = new Map<string, Sample>();
  let owner: RTCPeerConnection | null = null;
  let busy = false;
  show(['—', '—', '—', '未连接', '—', '—']);
  const timer = window.setInterval(async () => {
    if (busy) return;
    const pc = getPeer();
    if (pc !== owner) { owner = pc; previous.clear(); }
    if (!pc || pc.connectionState !== 'connected') {
      previous.clear();
      show(['—', '—', '—', pc?.connectionState === 'connecting' ? '连接中' : '未连接', '—', '—']);
      return;
    }
    busy = true;
    try {
      const report = await pc.getStats();
      if (pc !== getPeer() || pc.connectionState !== 'connected') return;
      const next = new Map<string, Sample>();
      let fps = 0, bps = 0, received = 0, lost = 0, measured = false;
      let width = 0, height = 0;
      let rtt: number | undefined;
      report.forEach(stat => {
        if (stat.type === 'transport' && stat.selectedCandidatePairId) {
          const pair = report.get(stat.selectedCandidatePairId);
          if (typeof pair?.currentRoundTripTime === 'number') rtt = pair.currentRoundTripTime * 1000;
        }
        if (stat.type !== 'inbound-rtp' || (stat.kind ?? stat.mediaType) !== 'video') return;
        if (stat.frameWidth && stat.frameHeight) { width = stat.frameWidth; height = stat.frameHeight; }
        const sample = { timestamp: stat.timestamp, bytes: stat.bytesReceived ?? 0,
          frames: stat.framesDecoded ?? 0, received: stat.packetsReceived ?? 0, lost: stat.packetsLost ?? 0 };
        next.set(stat.id, sample);
        const old = previous.get(stat.id);
        if (!old || sample.timestamp <= old.timestamp || sample.bytes < old.bytes || sample.frames < old.frames
            || sample.received < old.received) return;
        const seconds = (sample.timestamp - old.timestamp) / 1000;
        bps += (sample.bytes - old.bytes) * 8 / seconds;
        fps += (sample.frames - old.frames) / seconds;
        received += sample.received - old.received;
        lost += Math.max(0, sample.lost - old.lost);
        measured = true;
      });
      previous = next;
      const loss = received + lost > 0 ? lost / (received + lost) * 100 : undefined;
      let quality = '评估中', level = '';
      if (loss !== undefined && rtt !== undefined) {
        level = loss >= 5 || rtt >= 250 ? 'poor' : loss >= 1 || rtt >= 100 ? 'fair' : 'good';
        quality = level === 'good' ? '▂▄▆█ 良好' : level === 'fair' ? '▂▄▆ 一般' : '▂ 较差';
      }
      const resolution = width && height ? `${width} × ${height}` :
        video.videoWidth && video.videoHeight ? `${video.videoWidth} × ${video.videoHeight}` : '—';
      show([measured ? `${fps.toFixed(1)} fps` : '—', measured ?
        bps >= 1e6 ? `${(bps / 1e6).toFixed(2)} Mbps` : `${(bps / 1000).toFixed(0)} kbps` : '—',
        resolution, quality, rtt === undefined ? '—' : `${Math.round(rtt)} ms`,
        loss === undefined ? '—' : `${loss.toFixed(1)}%`], level);
    } catch {
      previous.clear();
      show(['—', '—', '—', '统计暂不可用', '—', '—']);
    } finally { busy = false; }
  }, 1000);
  window.addEventListener('pagehide', () => { clearInterval(timer); }, { once: true });
}
