import { ScrcpyClient } from './scrcpy-client';
import { InputHandler } from './input';
import './style.css';

function queryParam(name: string): string | null {
  return new URLSearchParams(window.location.search).get(name);
}

const TURN_URL = queryParam('turn') ?? 'turn:10.20.10.7:3478';
const TURN_USER = queryParam('user') ?? 'turnuser';
const TURN_CRED = queryParam('cred') ?? 'turnpassword';

function el<K extends keyof HTMLElementTagNameMap>(
    tag: K, className?: string, text?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (className) {
    node.className = className;
  }
  if (text) {
    node.textContent = text;
  }
  return node;
}

function buildUi(): { video: HTMLVideoElement; status: HTMLSpanElement } {
  const root = document.getElementById('app')!;

  const connectBar = el('div', 'connect-bar');
  const urlInput = el('input', 'url-input') as HTMLInputElement;
  urlInput.placeholder = 'ws://云机IP:8080';
  urlInput.value = 'ws://localhost:8080';
  const connectBtn = el('button', 'connect-btn', '连接');
  connectBar.append(urlInput, connectBtn);

  const stage = el('div', 'stage');
  const video = el('video') as HTMLVideoElement;
  video.autoplay = true;
  video.playsInline = true;
  stage.append(video);

  const toolbar = el('div', 'toolbar');
  const backBtn = el('button', 'tool-btn', '返回');
  const homeBtn = el('button', 'tool-btn', '主页');
  const status = el('span', 'status');
  toolbar.append(backBtn, homeBtn, status);

  root.append(connectBar, stage, toolbar);

  return { video, status };
}

function main(): void {
  const { video, status } = buildUi();

  let client: ScrcpyClient | null = null;
  let input: InputHandler | null = null;
  let dataChannel: RTCDataChannel | null = null;

  const send = (payload: Uint8Array): boolean => {
    return client?.sendControlMessage(payload) ?? false;
  };

  const connect = async (url: string): Promise<void> => {
    if (!url) {
      return;
    }
    client = new ScrcpyClient({
      onStateChange: (state) => {
        status.textContent = state;
      },
      onVideoTrack: (stream) => {
        video.srcObject = stream;
      },
      onDataChannelOpen: (channel) => {
        dataChannel = channel;
      },
      onError: (message) => {
        status.textContent = `错误: ${message}`;
      },
    });
    input = new InputHandler(send);

    const urlInput = document.querySelector('.url-input') as HTMLInputElement;
    const connectBtn = document.querySelector('.connect-btn') as HTMLButtonElement;
    connectBtn.disabled = true;
    urlInput.disabled = true;

    await client.connect({
      signalingUrl: url,
      iceServers: [{ urls: TURN_URL, username: TURN_USER, credential: TURN_CRED }],
    });

    (window as unknown as { __pc: RTCPeerConnection | null }).__pc = client.peerConnection;
    (window as unknown as { __dc: RTCDataChannel | null }).__dc = client.dataChannel;
  };

  document.querySelector('.connect-btn')!.addEventListener('click', () => {
    const url = (document.querySelector('.url-input') as HTMLInputElement).value;
    void connect(url);
  });

  video.addEventListener('touchstart', (e) => input?.handleTouchStart(e, video), { passive: false });
  video.addEventListener('touchmove', (e) => input?.handleTouchMove(e, video), { passive: false });
  video.addEventListener('touchend', (e) => input?.handleTouchEnd(e, video), { passive: false });
  video.addEventListener('mousedown', (e) => input?.handleMouseDown(e, video));
  video.addEventListener('mousemove', (e) => input?.handleMouseMove(e, video));
  video.addEventListener('mouseup', (e) => input?.handleMouseUp(e, video));
  video.addEventListener('wheel', (e) => input?.handleWheel(e, video), { passive: false });
  document.addEventListener('keydown', (e) => input?.handleKeyDown(e));
  document.addEventListener('keyup', (e) => input?.handleKeyUp(e));

  const backBtn = document.querySelectorAll('.tool-btn')[0] as HTMLButtonElement;
  const homeBtn = document.querySelectorAll('.tool-btn')[1] as HTMLButtonElement;
  backBtn.addEventListener('click', () => input?.sendBack());
  homeBtn.addEventListener('click', () => input?.sendHome());

  void dataChannel;
}

main();
