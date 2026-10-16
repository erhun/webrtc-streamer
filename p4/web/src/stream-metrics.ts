export type QualityLevel = '' | 'good' | 'fair' | 'poor';

// Packet-weighted rolling loss; never average percentages from unequal intervals.
export class ConnectionQualityWindow {
  private samples: { at: number; received: number; lost: number }[] = [];
  private level: QualityLevel = '';
  private candidate: QualityLevel = '';
  private candidateSince = 0;
  reset(): void { this.samples = []; this.level = this.candidate = ''; this.candidateSince = 0; }
  observe(now: number, received: number, lost: number, rtt: number | undefined,
      measured = true): { loss: number | undefined; packets: number; level: QualityLevel } {
    if (this.samples.length && now < this.samples[this.samples.length - 1].at) this.reset();
    this.samples = this.samples.filter(sample => now - sample.at < 5000);
    if (measured && Number.isFinite(received) && received >= 0 && Number.isFinite(lost)) {
      this.samples.push({ at: now, received, lost });
    }
    const total = this.samples.reduce((sum, sample) => ({ received: sum.received + sample.received,
      lost: sum.lost + sample.lost }), { received: 0, lost: 0 });
    // Negative lost deltas are late packet corrections, not counter resets.
    const missing = Math.max(0, total.lost), packets = total.received + missing;
    const loss = packets > 0 ? missing / packets * 100 : undefined;
    if (packets < 20 || rtt === undefined || !Number.isFinite(rtt) || loss === undefined) {
      this.level = this.candidate = '';
      return { loss, packets, level: '' };
    }
    const next: QualityLevel = loss >= 5 || rtt >= 250 ? 'poor' : loss >= 1 || rtt >= 100 ? 'fair' : 'good';
    if (!this.level) { this.level = next; this.candidate = ''; }
    else if (next === this.level) { this.candidate = ''; }
    else {
      const rank = { good: 0, fair: 1, poor: 2 };
      const worsening = rank[next] > rank[this.level];
      if (!this.candidate || (rank[this.candidate] > rank[this.level]) !== worsening) {
        this.candidate = next; this.candidateSince = now;
      } else if (worsening ? rank[next] < rank[this.candidate] : rank[next] > rank[this.candidate]) {
        // Alternating fair/poor is still continuously worse than good. Retain the
        // timer and commit only the least severe change supported by the window.
        this.candidate = next;
      }
      const holdMs = worsening ? 2000 : 4000;
      if (now - this.candidateSince >= holdMs) { this.level = this.candidate; this.candidate = ''; }
    }
    return { loss, packets, level: this.level };
  }
}

// Quality is an application estimate from RTT/loss, not radio RSSI.
export function mountMetrics(video: HTMLVideoElement, getPeer: () => RTCPeerConnection | null): void {
  const bar = document.createElement('div');
  bar.className = 'stream-metrics';
  bar.setAttribute('aria-label', '实时串流统计');
  const fields = ['解码帧率', '视频码率', '分辨率', '连接质量', '往返延迟', '丢包率（5s）', '呈现帧率'].map(label => {
    const node = document.createElement('span');
    bar.append(node);
    return { node, label };
  });
  fields[0].node.title = '浏览器 framesDecoded 的区间增量，不是 MediaCodec 编码帧率或屏幕呈现帧率';
  fields[6].node.title = 'requestVideoFrameCallback 的 presentedFrames 增量，表示提交到浏览器合成器的帧率';
  fields[3].node.className = 'quality';
  fields[3].node.title = '根据 RTT 与近 5 秒按包数加权的丢包率估算；至少 20 包，降级确认 2 秒、恢复确认 4 秒；不是无线信号强度或端到端体验评分';
  fields[5].node.title = '近 5 秒累计丢包增量 /（接收增量 + 丢包增量），包含迟到包对丢包计数的修正';
  video.closest('.stage')!.before(bar);
  const show = (values: string[], level = ''): void => {
    fields.forEach((field, i) => { field.node.textContent = `${field.label}：${values[i]}`; });
    fields[3].node.dataset.level = level;
  };
  type Sample = { timestamp: number; bytes: number; frames: number; received: number; lost: number };
  let previous = new Map<string, Sample>();
  const qualityWindow = new ConnectionQualityWindow();
  let owner: RTCPeerConnection | null = null;
  let busy = false;
  let presentedCount: number | undefined;
  let previousPresented: { count: number; at: number } | null = null;
  let presentationCallback = 0, stopped = false;
  if (typeof video.requestVideoFrameCallback === 'function') {
    const observe = (_now: number, metadata: VideoFrameCallbackMetadata): void => {
      presentedCount = metadata.presentedFrames;
      if (!stopped) presentationCallback = video.requestVideoFrameCallback(observe);
    };
    presentationCallback = video.requestVideoFrameCallback(observe);
  }
  show(['—', '—', '—', '未连接', '—', '—', '—']);
  const timer = window.setInterval(async () => {
    if (busy) return;
    const pc = getPeer();
    if (pc !== owner) { owner = pc; previous.clear(); previousPresented = null; qualityWindow.reset(); }
    if (!pc || pc.connectionState !== 'connected') {
      previous.clear();
      qualityWindow.reset();
      previousPresented = null;
      show(['—', '—', '—', pc?.connectionState === 'connecting' ? '连接中' : '未连接', '—', '—', '—']);
      return;
    }
    busy = true;
    try {
      const report = await pc.getStats();
      if (pc !== getPeer() || pc.connectionState !== 'connected') return;
      const next = new Map<string, Sample>();
      let fps = 0, bps = 0, received = 0, lost = 0, measured = false, lossMeasured = false;
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
          frames: stat.framesDecoded ?? 0, received: stat.packetsReceived ?? NaN, lost: stat.packetsLost ?? NaN };
        next.set(stat.id, sample);
        const old = previous.get(stat.id);
        if (!old || sample.timestamp <= old.timestamp || sample.bytes < old.bytes || sample.frames < old.frames
            || sample.received < old.received) return;
        const seconds = (sample.timestamp - old.timestamp) / 1000;
        bps += (sample.bytes - old.bytes) * 8 / seconds;
        fps += (sample.frames - old.frames) / seconds;
        if (seconds <= 5 && [sample.received, sample.lost, old.received, old.lost].every(Number.isFinite)) {
          received += sample.received - old.received;
          lost += sample.lost - old.lost;
          lossMeasured = true;
        }
        measured = true;
      });
      if (![...next.keys()].some(id => previous.has(id))) qualityWindow.reset();
      previous = next;
      const now = performance.now();
      const { loss, level } = qualityWindow.observe(now, received, lost, rtt, lossMeasured);
      const quality = level === 'good' ? '▂▄▆█ 良好' : level === 'fair' ? '▂▄▆ 一般'
        : level === 'poor' ? '▂ 较差' : '样本不足';
      const resolution = width && height ? `${width} × ${height}` :
        video.videoWidth && video.videoHeight ? `${video.videoWidth} × ${video.videoHeight}` : '—';
      const presentedFps = presentedCount !== undefined && previousPresented && presentedCount >= previousPresented.count
        && now > previousPresented.at ? (presentedCount - previousPresented.count) * 1000 / (now - previousPresented.at) : undefined;
      previousPresented = presentedCount === undefined ? null : { count: presentedCount, at: now };
      show([measured ? `${fps.toFixed(1)} fps` : '—', measured ?
        bps >= 1e6 ? `${(bps / 1e6).toFixed(2)} Mbps` : `${(bps / 1000).toFixed(0)} kbps` : '—',
        resolution, quality, rtt === undefined ? '—' : `${Math.round(rtt)} ms`,
        loss === undefined ? '—' : `${loss.toFixed(1)}%`, presentedFps === undefined ? '—' : `${presentedFps.toFixed(1)} fps`], level);
    } catch {
      if (pc !== getPeer()) return;
      previous.clear();
      qualityWindow.reset();
      previousPresented = null;
      show(['—', '—', '—', '统计暂不可用', '—', '—', '—']);
    } finally { busy = false; }
  }, 1000);
  window.addEventListener('pagehide', () => {
    stopped = true;
    clearInterval(timer);
    if (presentationCallback) video.cancelVideoFrameCallback(presentationCallback);
  }, { once: true });
}
