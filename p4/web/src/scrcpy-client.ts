import { readResumeSession, saveResumeSession, forgetResumeSession, newPeerId } from './session-resume';
import { configureVideoLatency } from './receiver-latency';
import { MessageType, MotionEventAction } from './control-message';
interface IceServer { urls: string; username?: string; credential?: string; }
export interface ConnectOptions { signalingUrl: string; sessionToken: string; iceServers: IceServer[]; iceTransportPolicy?: RTCIceTransportPolicy; }
export type ClientState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'failed' | 'closed';
export interface ScrcpyClientCallbacks {
  onStateChange: (state: ClientState) => void;
  onVideoTrack: (stream: MediaStream) => void;
  onDataChannelOpen: (channel: RTCDataChannel) => void;
  onError: (message: string) => void;
  onDiagnostic?: (message: string) => void;
}
const RECOVERY_MS = 30000;
const CONTROL_HIGH_WATER = 131072;
export class ScrcpyClient {
  private pc: RTCPeerConnection | null = null;
  private ws: WebSocket | null = null;
  private controlChannel: RTCDataChannel | null = null;
  private timeout: ReturnType<typeof setTimeout> | null = null;
  private recoveryInterval: ReturnType<typeof setInterval> | null = null;
  private socketTimeout: ReturnType<typeof setTimeout> | null = null;
  private heartbeat: ReturnType<typeof setInterval> | null = null;
  private controlQueue: Uint8Array[] = [];
  private controlBytes = 0;
  private resumeUrl = '';
  private resumeSecret = '';
  private authenticatedSocket = false;
  constructor(private readonly callbacks: ScrcpyClientCallbacks) { }
  async connect(options: ConnectOptions): Promise<void> {
    this.close();
    const invalid = (message: string): void => {
      this.callbacks.onStateChange('failed'); this.callbacks.onError(message);
    };
    const url = new URL(options.signalingUrl);
    const saved = readResumeSession();
    const restoredToken = saved?.url === url.href ? saved.token : '';
    if (!restoredToken && options.sessionToken.length < 32) { invalid('请输入有效的会话凭证'); return; }
    if (url.protocol !== 'wss:' && !(url.protocol === 'ws:')) {
      invalid('远程连接需要 wss:// 地址'); return;
    }
    if (options.iceTransportPolicy === 'relay' && !options.iceServers.some(server => /^turns?:/i.test(server.urls))) {
      invalid('强制中继需要配置 TURN'); return;
    }
    this.callbacks.onStateChange('connecting');
    const started = performance.now();
    const pc = new RTCPeerConnection({ iceServers: options.iceServers, iceTransportPolicy: options.iceTransportPolicy ?? 'all' });
    this.pc = pc;
    const current = (): boolean => this.pc === pc;
    const trace = (stage: string): void => {
      if (current()) { this.callbacks.onDiagnostic?.(`${Math.round(performance.now() - started)}ms ${stage}`); }
    };
    trace('连接开始');
    const fail = (message: string): void => {
      if (!current()) { return; }
      trace(message);
      this.close(); this.callbacks.onStateChange('failed'); this.callbacks.onError(message);
    };
    this.timeout = setTimeout(() => fail('连接超时，请申请新会话后重试'), 30000);
    let resumeToken = restoredToken;
    const peerId = newPeerId();
    let freshPeer = true;
    let retriedAuth = false;
    this.resumeUrl = url.href;
    this.resumeSecret = resumeToken;
    const remember = (): void => {
      if (resumeToken) { saveResumeSession(url.href, resumeToken); }
    };
    let ready = false;
    let recovering = false;
    let offering = false;
    let awaitingAnswer = false;
    let offerSentAt = -Infinity;
    let pendingIce: RTCIceCandidateInit[] = [];
    let messages = Promise.resolve();
    const recovered = (): void => {
      if (!current() || !ready || awaitingAnswer || offering || pc.connectionState !== 'connected') { return; }
      if (this.timeout) { clearTimeout(this.timeout); this.timeout = null; }
      if (this.recoveryInterval) { clearInterval(this.recoveryInterval); this.recoveryInterval = null; }
      if (recovering) { trace('网络连接已恢复'); }
      recovering = false;
      this.callbacks.onStateChange('connected');
      this.flushControl();
    };
    const offer = async (restart: boolean): Promise<void> => {
      const socket = this.ws;
      if (!current() || !ready || offering || !socket || socket.readyState !== WebSocket.OPEN) { return; }
      // TCP already preserves signaling delivery. Do not replace an offer while
      // its delayed answer is in flight; that answer belongs to the old ICE credentials.
      if (awaitingAnswer || (restart && performance.now() - offerSentAt < 5000)) { return; }
      const active = (): boolean => current() && this.ws === socket;
      offering = true;
      try {
        if (pc.signalingState === 'have-local-offer') { await pc.setLocalDescription({ type: 'rollback' }); }
        if (!active()) { return; }
        pendingIce = [];
        const description = await pc.createOffer({ iceRestart: restart });
        if (!active()) { return; }
        awaitingAnswer = true;
        offerSentAt = performance.now();
        await pc.setLocalDescription(description);
        if (!active()) { return; }
        socket.send(JSON.stringify({ type: 'offer', sdp: description.sdp ?? '' }));
        trace(restart ? 'ICE restart Offer 已发送' : 'Offer 已发送');
      } catch {
        if (active()) { fail('信令协商失败'); }
      } finally { offering = false; }
    };
    const recover = (reason: string): void => {
      if (!current() || recovering) { return; }
      recovering = true;
      trace(`${reason}，尝试恢复（最多 30 秒）`);
      this.callbacks.onStateChange('reconnecting');
      if (this.timeout) { clearTimeout(this.timeout); }
      this.timeout = setTimeout(() => fail('网络恢复超时，请申请新会话后重试'), RECOVERY_MS);
      this.recoveryInterval = setInterval(() => {
        if (!current()) { return; }
        if (!this.ws) { openSocket(); }
        else if (ready) {
          if (pc.connectionState === 'connected' && !awaitingAnswer) { recovered(); }
          else { void offer(true); }
        }
      }, 3000);
    };
    const channel = pc.createDataChannel('control', { ordered: true });
    this.controlChannel = channel;
    channel.bufferedAmountLowThreshold = 65536;
    channel.onbufferedamountlow = () => { if (current()) { this.flushControl(); } };
    channel.onopen = () => { if (current()) { this.callbacks.onDataChannelOpen(channel); this.flushControl(); } };
    // A closed SCTP channel cannot be revived by ICE restart.
    channel.onclose = () => fail('控制通道已关闭，请申请新会话后连接');
    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });
    const stream = new MediaStream();
    pc.ontrack = (event) => {
      if (current()) {
        if (event.track.kind === 'video') { trace(configureVideoLatency(event.receiver)); }
        trace(`收到 ${event.track.kind} 轨道（尚非首帧）`);
        stream.addTrack(event.track); this.callbacks.onVideoTrack(stream);
      }
    };
    pc.onicecandidate = (event) => {
      if (current() && ready && this.ws?.readyState === WebSocket.OPEN && event.candidate) {
        this.ws.send(JSON.stringify({ type: 'ice', sdpMid: event.candidate.sdpMid ?? '',
          sdpMLineIndex: event.candidate.sdpMLineIndex ?? 0, candidate: event.candidate.candidate }));
      }
    };
    pc.onicegatheringstatechange = () => trace(`ICE gathering: ${pc.iceGatheringState}`);
    pc.oniceconnectionstatechange = () => trace(`ICE: ${pc.iceConnectionState}`);
    pc.onicecandidateerror = (event) => trace(`ICE server error: ${event.errorCode}`);
    pc.onconnectionstatechange = () => {
      if (!current()) { return; }
      trace(`WebRTC: ${pc.connectionState}`);
      if (pc.connectionState === 'connected') { recovered(); }
      else if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') { recover('媒体连接中断'); }
      else if (pc.connectionState === 'closed') { fail('WebRTC 连接已结束'); }
    };
    const openSocket = (): void => {
      if (!current()) { return; }
      const ws = new WebSocket(options.signalingUrl);
      this.ws = ws;
      ready = false;
      this.authenticatedSocket = false;
      const active = (): boolean => current() && this.ws === ws;
      const clearSocketTimeout = (): void => {
        if (this.socketTimeout) { clearTimeout(this.socketTimeout); this.socketTimeout = null; }
      };
      const lost = (reason: string): void => {
        if (!active()) { return; }
        clearSocketTimeout();
        this.ws = null;
        ready = false;
        ws.close();
        if (!resumeToken) { fail(`${reason}，请申请新会话后连接`); return; }
        recover(reason);
      };
      // Covers both opening and authentication, including a silently blackholed socket.
      this.socketTimeout = setTimeout(() => lost('信令连接超时'), 5000);
      ws.onopen = () => {
        if (!active()) { return; }
        trace('WebSocket 已打开');
        ws.send(JSON.stringify({ type: resumeToken ? 'resume' : 'auth', token: resumeToken || options.sessionToken, peerId }));
      };
      ws.onmessage = (event) => {
        messages = messages.then(async () => {
          if (!active()) { return; }
          const msg = JSON.parse(event.data as string);
          if (msg.type === 'ready' && !ready) {
            clearSocketTimeout();
            if (freshPeer && resumeToken && msg.resumed && msg.peerId !== peerId) {
              fail('服务端不支持页面刷新恢复，请更新 Android 服务端后创建新会话'); return;
            }
            if (typeof msg.resumeToken === 'string') { resumeToken = msg.resumeToken; }
            this.resumeSecret = resumeToken;
            this.authenticatedSocket = true;
            remember();
            freshPeer = false;
            ready = true;
            if (this.heartbeat) { clearInterval(this.heartbeat); }
            this.heartbeat = setInterval(() => {
              if (!active() || !ready || this.socketTimeout) { return; }
              ws.send(JSON.stringify({ type: 'ping' }));
              this.socketTimeout = setTimeout(() => lost('信令心跳超时'), 5000);
            }, 3000);
            trace(msg.resumed ? '信令会话已恢复' : '鉴权完成');
            // Discard a negotiation whose answer may have been lost with the old socket.
            awaitingAnswer = false;
            offerSentAt = -Infinity;
            await offer(Boolean(msg.resumed));
          } else if (msg.type === 'pong' && ready) {
            clearSocketTimeout();
            remember();
          } else if (msg.type === 'answer' && ready && awaitingAnswer) {
            trace('Answer 已收到');
            await pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp });
            if (!active()) { return; }
            awaitingAnswer = false;
            trace('Answer 已应用');
            for (const candidate of pendingIce.splice(0)) { await pc.addIceCandidate(candidate); }
            recovered();
          } else if (msg.type === 'ice' && ready) {
            const candidate = { sdpMid: msg.sdpMid, sdpMLineIndex: msg.sdpMLineIndex, candidate: msg.candidate };
            if (pc.remoteDescription && !awaitingAnswer) { await pc.addIceCandidate(candidate); }
            else if (pendingIce.length < 256) { pendingIce.push(candidate); }
            else { throw new Error('Too many ICE candidates'); }
          } else { throw new Error('Unexpected signaling message'); }
        }).catch(() => { if (active()) { fail('信令协商失败'); } });
      };
      ws.onerror = () => lost('信令连接错误');
      ws.onclose = (event) => {
        if (!active()) { return; }
        trace(`WebSocket closed: code=${event.code}`);
        if (event.code === 1008) {
          if (event.reason === 'Peer reset failed') {
            fail('服务端连接重建失败，请重新编译 JNI 和 Android 服务端'); return;
          }
          if (!ready && resumeToken && !retriedAuth && options.sessionToken.length >= 32) {
            // A restarted server has a new session secret. Try an explicitly supplied
            // login token once; never make a consumed login token reusable server-side.
            clearSocketTimeout();
            forgetResumeSession(url.href);
            resumeToken = ''; this.resumeSecret = ''; retriedAuth = true;
            this.ws = null;
            openSocket();
          } else { fail('会话认证或协商被拒绝，请申请新会话后连接'); }
        }
        else { lost('信令连接中断'); }
      };
    };
    openSocket();
  }
  private flushControl(): void {
    const channel = this.controlChannel;
    if (!channel || channel.readyState !== 'open') { return; }
    while (this.controlQueue.length && channel.bufferedAmount + this.controlQueue[0].byteLength <= CONTROL_HIGH_WATER) {
      const payload = this.controlQueue[0];
      try { channel.send(payload.slice().buffer); } catch {
        this.close(); this.callbacks.onStateChange('failed');
        this.callbacks.onError('控制消息发送失败，请申请新会话后连接'); return;
      }
      this.controlQueue.shift(); this.controlBytes -= payload.byteLength;
    }
  }
  sendControlMessage(payload: Uint8Array): boolean {
    const channel = this.controlChannel;
    if (!channel || channel.readyState !== 'open') { return false; }
    // Shed motion/scroll updates under pressure, but preserve ordering and releases.
    const congested = this.controlQueue.length > 0 || channel.bufferedAmount + payload.byteLength > CONTROL_HIGH_WATER;
    if (congested && ((payload[0] === MessageType.INJECT_TOUCH_EVENT && payload[1] === MotionEventAction.MOVE)
        || payload[0] === MessageType.INJECT_SCROLL_EVENT)) { return false; }
    if (this.controlBytes + payload.byteLength > 65536) {
      this.close(); this.callbacks.onStateChange('failed');
      this.callbacks.onError('控制消息持续积压，连接已关闭以释放输入状态'); return false;
    }
    this.controlQueue.push(payload.slice()); this.controlBytes += payload.byteLength;
    this.flushControl();
    return this.controlChannel === channel;
  }
  get peerConnection(): RTCPeerConnection | null { return this.pc; }
  get dataChannel(): RTCDataChannel | null { return this.controlChannel; }
  // Page lifecycle teardown must not send bye or revoke the recovery credential.
  suspend(): void {
    if (this.resumeSecret) { saveResumeSession(this.resumeUrl, this.resumeSecret); }
    this.dispose(false);
  }
  close(): void { this.dispose(true); }
  private dispose(terminate: boolean): void {
    if (this.timeout) { clearTimeout(this.timeout); this.timeout = null; }
    if (this.recoveryInterval) { clearInterval(this.recoveryInterval); this.recoveryInterval = null; }
    if (this.socketTimeout) { clearTimeout(this.socketTimeout); this.socketTimeout = null; }
    if (this.heartbeat) { clearInterval(this.heartbeat); this.heartbeat = null; }
    const pc = this.pc, ws = this.ws, channel = this.controlChannel;
    this.pc = null; this.ws = null; this.controlChannel = null;
    this.controlQueue = []; this.controlBytes = 0;
    // Explicit close is terminal; a transient signaling loss uses lost() instead.
    if (terminate && this.authenticatedSocket && ws?.readyState === WebSocket.OPEN) {
      try { ws.send(JSON.stringify({ type: 'bye' })); } catch { /* Socket already lost. */ }
    }
    if (terminate && this.resumeUrl) { forgetResumeSession(this.resumeUrl); }
    this.resumeUrl = ''; this.resumeSecret = ''; this.authenticatedSocket = false;
    channel?.close(); ws?.close(); pc?.close();
    this.callbacks.onStateChange('closed');
  }
}
