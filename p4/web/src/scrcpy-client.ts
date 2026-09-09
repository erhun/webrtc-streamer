interface IceServer {
  urls: string;
  username?: string;
  credential?: string;
}

export interface ConnectOptions {
  signalingUrl: string;
  iceServers: IceServer[];
}

type SignalMessage =
    | { type: 'offer'; sdp: string }
    | { type: 'answer'; sdp: string }
    | { type: 'ice'; sdpMid: string; sdpMLineIndex: number; candidate: string };

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

  constructor(private readonly callbacks: ScrcpyClientCallbacks) {
  }

  async connect(options: ConnectOptions): Promise<void> {
    this.callbacks.onStateChange('connecting');

    const pc = new RTCPeerConnection({ iceServers: options.iceServers });
    this.pc = pc;

    this.controlChannel = pc.createDataChannel('control', {
      ordered: true,
    });
    this.controlChannel.onopen = () => {
      this.callbacks.onDataChannelOpen(this.controlChannel!);
    };

    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });

    pc.ontrack = (event) => {
      this.callbacks.onVideoTrack(event.streams[0]);
    };

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        this.send({
          type: 'ice',
          sdpMid: event.candidate.sdpMid ?? '',
          sdpMLineIndex: event.candidate.sdpMLineIndex ?? 0,
          candidate: event.candidate.candidate,
        });
      }
    };

    pc.onicegatheringstatechange = () => {
      console.log('[scrcpy] iceGatheringState=' + pc.iceGatheringState + ', t=' + performance.now().toFixed(0));
    };

    pc.onconnectionstatechange = () => {
      switch (pc.connectionState) {
        case 'connected':
          this.callbacks.onStateChange('connected');
          break;
        case 'failed':
          this.callbacks.onStateChange('failed');
          this.callbacks.onError('WebRTC 连接失败');
          break;
        case 'closed':
          this.callbacks.onStateChange('closed');
          break;
      }
    };

    const ws = new WebSocket(options.signalingUrl);
    this.ws = ws;
    console.log('[scrcpy] WebSocket created, t=' + performance.now().toFixed(0));

    ws.onopen = async () => {
      console.log('[scrcpy] ws.onopen, t=' + performance.now().toFixed(0));
      try {
        const offer = await pc.createOffer();
        console.log('[scrcpy] createOffer done, t=' + performance.now().toFixed(0));
        await pc.setLocalDescription(offer);
        console.log('[scrcpy] setLocalDescription done, t=' + performance.now().toFixed(0));
        this.send({ type: 'offer', sdp: offer.sdp ?? '' });
        console.log('[scrcpy] offer sent, t=' + performance.now().toFixed(0));
      } catch (e) {
        this.callbacks.onError(`创建 offer 失败: ${e}`);
        this.callbacks.onStateChange('failed');
      }
    };

    ws.onmessage = async (event) => {
      const msg = JSON.parse(event.data as string) as SignalMessage;
      if (msg.type === 'answer') {
        await pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp });
      } else if (msg.type === 'ice') {
        await pc.addIceCandidate({
          sdpMid: msg.sdpMid,
          sdpMLineIndex: msg.sdpMLineIndex,
          candidate: msg.candidate,
        });
      }
    };

    ws.onerror = () => {
      console.log('[scrcpy] ws.onerror, t=' + performance.now().toFixed(0));
      this.callbacks.onError('信令连接错误');
      this.callbacks.onStateChange('failed');
    };

    ws.onclose = () => {
      console.log('[scrcpy] ws.onclose, t=' + performance.now().toFixed(0));
      this.callbacks.onStateChange('closed');
    };
  }

  sendControlMessage(payload: Uint8Array): boolean {
    const channel = this.controlChannel;
    if (channel == null || channel.readyState !== 'open') {
      return false;
    }
    channel.send(payload.slice().buffer);
    return true;
  }

  get peerConnection(): RTCPeerConnection | null {
    return this.pc;
  }

  get dataChannel(): RTCDataChannel | null {
    return this.controlChannel;
  }

  private send(msg: SignalMessage): void {
    if (this.ws != null && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  close(): void {
    this.ws?.close();
    this.pc?.close();
    this.ws = null;
    this.pc = null;
    this.controlChannel = null;
  }
}
