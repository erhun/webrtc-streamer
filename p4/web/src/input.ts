import {
  AndroidKeycode,
  KeyEventAction,
  MotionEventAction,
  serializeInjectKeycode,
  serializeInjectScrollEvent,
  serializeInjectTouchEvent,
  type Position,
} from './control-message';

const KEYCODE_MAP: Record<string, number> = {
  'Escape': AndroidKeycode.BACK,
  'Backspace': AndroidKeycode.BACK,
  'Enter': 66,
  'ArrowUp': 19,
  'ArrowDown': 20,
  'ArrowLeft': 21,
  'ArrowRight': 22,
  'Tab': 61,
};

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function mapToVideoCoords(
    video: HTMLVideoElement, clientX: number, clientY: number): { x: number; y: number } {
  const rect = video.getBoundingClientRect();
  const videoW = video.videoWidth;
  const videoH = video.videoHeight;
  if (videoW === 0 || videoH === 0) {
    return { x: 0, y: 0 };
  }
  const scale = Math.min(rect.width / videoW, rect.height / videoH);
  const contentW = videoW * scale;
  const contentH = videoH * scale;
  const offsetX = (rect.width - contentW) / 2;
  const offsetY = (rect.height - contentH) / 2;
  const x = (clientX - rect.left - offsetX) / scale;
  const y = (clientY - rect.top - offsetY) / scale;
  return { x: clamp(Math.round(x), 0, videoW), y: clamp(Math.round(y), 0, videoH) };
}

export class InputHandler {
  private readonly pointers = new Map<number, { lastX: number; lastY: number }>();

  constructor(private readonly send: (payload: Uint8Array) => boolean) {
  }

  private position(clientX: number, clientY: number, video: HTMLVideoElement): Position {
    const { x, y } = mapToVideoCoords(video, clientX, clientY);
    return { x, y, screenWidth: video.videoWidth, screenHeight: video.videoHeight };
  }

  handleTouchStart(event: TouchEvent, video: HTMLVideoElement): void {
    event.preventDefault();
    for (const touch of Array.from(event.changedTouches)) {
      this.pointers.set(touch.identifier, { lastX: touch.clientX, lastY: touch.clientY });
      const p = this.position(touch.clientX, touch.clientY, video);
      this.send(serializeInjectTouchEvent(MotionEventAction.DOWN, touch.identifier, p, 1, 0, 0));
    }
  }

  handleTouchMove(event: TouchEvent, video: HTMLVideoElement): void {
    event.preventDefault();
    for (const touch of Array.from(event.changedTouches)) {
      this.pointers.set(touch.identifier, { lastX: touch.clientX, lastY: touch.clientY });
      const p = this.position(touch.clientX, touch.clientY, video);
      this.send(serializeInjectTouchEvent(MotionEventAction.MOVE, touch.identifier, p, 1, 0, 0));
    }
  }

  handleTouchEnd(event: TouchEvent, video: HTMLVideoElement): void {
    event.preventDefault();
    for (const touch of Array.from(event.changedTouches)) {
      const last = this.pointers.get(touch.identifier);
      this.pointers.delete(touch.identifier);
      const x = last?.lastX ?? touch.clientX;
      const y = last?.lastY ?? touch.clientY;
      const p = this.position(x, y, video);
      this.send(serializeInjectTouchEvent(MotionEventAction.UP, touch.identifier, p, 0, 0, 0));
    }
  }

  handleMouseDown(event: MouseEvent, video: HTMLVideoElement): void {
    this.pointers.set(-1, { lastX: event.clientX, lastY: event.clientY });
    const p = this.position(event.clientX, event.clientY, video);
    this.send(serializeInjectTouchEvent(MotionEventAction.DOWN, -1, p, 1, 0, 0));
  }

  handleMouseMove(event: MouseEvent, video: HTMLVideoElement): void {
    if (!this.pointers.has(-1)) {
      return;
    }
    this.pointers.set(-1, { lastX: event.clientX, lastY: event.clientY });
    const p = this.position(event.clientX, event.clientY, video);
    this.send(serializeInjectTouchEvent(MotionEventAction.MOVE, -1, p, 1, 0, 0));
  }

  handleMouseUp(event: MouseEvent, video: HTMLVideoElement): void {
    if (!this.pointers.has(-1)) {
      return;
    }
    this.pointers.delete(-1);
    const p = this.position(event.clientX, event.clientY, video);
    this.send(serializeInjectTouchEvent(MotionEventAction.UP, -1, p, 0, 0, 0));
  }

  handleKeyDown(event: KeyboardEvent): void {
    const keycode = KEYCODE_MAP[event.key];
    if (keycode === undefined) {
      return;
    }
    event.preventDefault();
    this.send(serializeInjectKeycode(KeyEventAction.DOWN, keycode, 0, 0));
  }

  handleKeyUp(event: KeyboardEvent): void {
    const keycode = KEYCODE_MAP[event.key];
    if (keycode === undefined) {
      return;
    }
    event.preventDefault();
    this.send(serializeInjectKeycode(KeyEventAction.UP, keycode, 0, 0));
  }

  handleWheel(event: WheelEvent, video: HTMLVideoElement): void {
    event.preventDefault();
    const p = this.position(event.clientX, event.clientY, video);
    const hScroll = event.deltaX;
    const vScroll = event.deltaY;
    this.send(serializeInjectScrollEvent(p, hScroll, vScroll, 0));
  }

  sendBack(): void {
    this.send(serializeInjectKeycode(KeyEventAction.DOWN, AndroidKeycode.BACK, 0, 0));
    this.send(serializeInjectKeycode(KeyEventAction.UP, AndroidKeycode.BACK, 0, 0));
  }

  sendHome(): void {
    this.send(serializeInjectKeycode(KeyEventAction.DOWN, AndroidKeycode.HOME, 0, 0));
    this.send(serializeInjectKeycode(KeyEventAction.UP, AndroidKeycode.HOME, 0, 0));
  }
}
