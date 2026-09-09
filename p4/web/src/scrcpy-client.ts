interface IceServer { urls: string; username?: string; credential?: string; }
export interface ConnectOptions { signalingUrl: string; sessionToken: string; iceServers: IceServer[]; }
export type ClientState = 'idle' | 'connecting' | 'connected' | 'failed' | 'closed';
export interface ScrcpyClientCallbacks {
  onStateChange: (state: ClientState) => void;
  onVideoTrack: (stream: MediaStream) => void;
  onDataChannelOpen: (channel: RTCDataChannel) => void;
  onError: (message: string) => void;
}
export class ScrcpyClient {
  private pc: RTCPeerConnection | null = null;
  private ws: WebSocket | null = null;
  private controlChannel: RTCDataChannel | null = null;
  private timeout: ReturnType<typeof setTimeout> | null = null;
  constructor(private readonly callbacks: ScrcpyClientCallbacks) { }
  async connect(options: ConnectOptions): Promise<void> {
    this.close();
    if (options.sessionToken.length < 32) {
      this.callbacks.onError('请输入有效的会话凭证'); this.callbacks.onStateChange('failed'); return;
    }
    const url = new URL(options.signalingUrl);
    if (url.protocol !== 'wss:' && !(url.protocol === 'ws:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))) {
      this.callbacks.onError('远程连接需要 wss:// 地址'); this.callbacks.onStateChange('failed'); return;
    }
    this.callbacks.onStateChange('connecting');
    const pc = new RTCPeerConnection({ iceServers: options.iceServers });
    this.pc = pc;
    const current = (): boolean => this.pc === pc;
    const fail = (message: string): void => {
      if (!current()) { return; }
      this.close(); this.callbacks.onError(message); this.callbacks.onStateChange('failed');
    };
    this.timeout = setTimeout(() => fail('连接超时，请申请新会话后重试'), 30000);
    const channel = pc.createDataChannel('control', { ordered: true });
    this.controlChannel = channel;
    channel.onopen = () => { if (current()) { this.callbacks.onDataChannelOpen(channel); } };
    channel.onclose = () => fail('控制通道已关闭');
    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });
    const stream = new MediaStream();
    pc.ontrack = (event) => {
      if (current()) { stream.addTrack(event.track); this.callbacks.onVideoTrack(stream); }
    };
    const ws = new WebSocket(options.signalingUrl); this.ws = ws;
    const send = (message: unknown): void => {
      if (current() && ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify(message)); }
    };
    pc.onicecandidate = (event) => {
      if (event.candidate) {
        send({ type: 'ice', sdpMid: event.candidate.sdpMid ?? '',
          sdpMLineIndex: event.candidate.sdpMLineIndex ?? 0, candidate: event.candidate.candidate });
      }
    };
    pc.onconnectionstatechange = () => {
      if (!current()) { return; }
      if (pc.connectionState === 'connected') {
        if (this.timeout) { clearTimeout(this.timeout); this.timeout = null; }
        this.callbacks.onStateChange('connected');
      } else if (pc.connectionState === 'failed' || pc.connectionState === 'closed') { fail('WebRTC 连接已结束'); }
      else if (pc.connectionState === 'disconnected' && !this.timeout) {
        this.timeout = setTimeout(() => fail('网络断开，请申请新会话后重试'), 10000);
      }
    };
    ws.onopen = () => send({ type: 'auth', token: options.sessionToken });
    let ready = false;
    const pendingIce: RTCIceCandidateInit[] = [];
    let messages = Promise.resolve();
    ws.onmessage = (event) => {
      // WebSocket ordering does not serialize async setRemoteDescription calls.
      messages = messages.then(async () => {
        if (!current()) { return; }
        const msg = JSON.parse(event.data as string);
        if (msg.type === 'ready' && !ready) {
          ready = true;
          const offer = await pc.createOffer();
          if (!current()) { return; }
          await pc.setLocalDescription(offer);
          send({ type: 'offer', sdp: offer.sdp ?? '' });
        } else if (msg.type === 'answer' && ready) {
          await pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp });
          for (const candidate of pendingIce.splice(0)) { await pc.addIceCandidate(candidate); }
        } else if (msg.type === 'ice' && ready) {
          const candidate = { sdpMid: msg.sdpMid, sdpMLineIndex: msg.sdpMLineIndex, candidate: msg.candidate };
          if (pc.remoteDescription) { await pc.addIceCandidate(candidate); }
          else if (pendingIce.length < 256) { pendingIce.push(candidate); }
          else { throw new Error('Too many ICE candidates'); }
        } else { throw new Error('Unexpected signaling message'); }
      }).catch(() => fail('信令协商失败'));
    };
    ws.onerror = () => fail('信令连接错误');
    ws.onclose = () => fail('会话已结束，请申请新凭证后连接');
  }
  sendControlMessage(payload: Uint8Array): boolean {
    const channel = this.controlChannel;
    if (!channel || channel.readyState !== 'open') { return false; }
    if (channel.bufferedAmount + payload.byteLength > 262144) {
      this.close(); this.callbacks.onError('控制消息积压，连接已关闭'); this.callbacks.onStateChange('failed'); return false;
    }
    try { channel.send(payload.slice().buffer); return true; } catch { this.close(); return false; }
  }
  get peerConnection(): RTCPeerConnection | null { return this.pc; }
  get dataChannel(): RTCDataChannel | null { return this.controlChannel; }
  close(): void {
    if (this.timeout) { clearTimeout(this.timeout); this.timeout = null; }
    const pc = this.pc, ws = this.ws, channel = this.controlChannel;
    this.pc = null; this.ws = null; this.controlChannel = null;
    channel?.close(); ws?.close(); pc?.close();
    this.callbacks.onStateChange('closed');
  }
}
