export const enum MessageType {
  INJECT_KEYCODE = 0,
  INJECT_TEXT = 1,
  INJECT_TOUCH_EVENT = 2,
  INJECT_SCROLL_EVENT = 3,
  BACK_OR_SCREEN_ON = 4,
  EXPAND_NOTIFICATION_PANEL = 5,
  EXPAND_SETTINGS_PANEL = 6,
  COLLAPSE_PANELS = 7,
  GET_CLIPBOARD = 8,
  SET_CLIPBOARD = 9,
  SET_DISPLAY_POWER = 10,
  ROTATE_DEVICE = 11,
  UHID_CREATE = 12,
  UHID_INPUT = 13,
  UHID_DESTROY = 14,
  OPEN_HARD_KEYBOARD_SETTINGS = 15,
  START_APP = 16,
  RESET_VIDEO = 17,
  CAMERA_SET_TORCH = 18,
  CAMERA_ZOOM_IN = 19,
  CAMERA_ZOOM_OUT = 20,
  RESIZE_DISPLAY = 21,
  SCAN_FILE = 22,
}

export const enum KeyEventAction {
  DOWN = 0,
  UP = 1,
}

export const enum MotionEventAction {
  DOWN = 0,
  UP = 1,
  MOVE = 2,
  CANCEL = 3,
}

export const enum AndroidKeycode {
  HOME = 3,
  BACK = 4,
  POWER = 26,
  VOLUME_UP = 24,
  VOLUME_DOWN = 25,
  MENU = 82,
  APP_SWITCH = 187,
}

export interface Position {
  x: number;
  y: number;
  screenWidth: number;
  screenHeight: number;
}

function u16FixedPoint(value: number): number {
  if (value >= 1) {
    return 0xffff;
  }
  return Math.max(0, Math.round(value * 0x10000));
}

function i16FixedPoint(value: number): number {
  if (value >= 1) {
    return 0x7fff;
  }
  if (value <= -1) {
    return -0x8000;
  }
  return Math.round(value * 0x8000);
}

class ByteWriter {
  private view: DataView;
  private offset = 0;

  constructor(length: number) {
    this.view = new DataView(new ArrayBuffer(length));
  }

  byte(value: number): void {
    this.view.setUint8(this.offset, value & 0xff);
    this.offset += 1;
  }

  short(value: number): void {
    this.view.setInt16(this.offset, value);
    this.offset += 2;
  }

  ushort(value: number): void {
    this.view.setUint16(this.offset, value);
    this.offset += 2;
  }

  int(value: number): void {
    this.view.setInt32(this.offset, value);
    this.offset += 4;
  }

  long(value: number): void {
    this.view.setBigInt64(this.offset, BigInt(value));
    this.offset += 8;
  }

  bytes(data: Uint8Array): void {
    new Uint8Array(this.view.buffer).set(data, this.offset);
    this.offset += data.length;
  }

  toArray(): Uint8Array {
    return new Uint8Array(this.view.buffer, 0, this.offset);
  }
}

function writePosition(w: ByteWriter, p: Position): void {
  w.int(p.x);
  w.int(p.y);
  w.ushort(p.screenWidth);
  w.ushort(p.screenHeight);
}

function emptyMessage(type: MessageType): Uint8Array {
  const w = new ByteWriter(1);
  w.byte(type);
  return w.toArray();
}

export function serializeInjectKeycode(
    action: KeyEventAction, keycode: number, repeat: number, metaState: number): Uint8Array {
  const w = new ByteWriter(1 + 1 + 4 + 4 + 4);
  w.byte(MessageType.INJECT_KEYCODE);
  w.byte(action);
  w.int(keycode);
  w.int(repeat);
  w.int(metaState);
  return w.toArray();
}

export function serializeInjectText(text: string): Uint8Array {
  const utf8 = new TextEncoder().encode(text);
  const w = new ByteWriter(1 + 4 + utf8.length);
  w.byte(MessageType.INJECT_TEXT);
  w.int(utf8.length);
  w.bytes(utf8);
  return w.toArray();
}

export function serializeInjectTouchEvent(
    action: MotionEventAction, pointerId: number, position: Position,
    pressure: number, actionButton: number, buttons: number): Uint8Array {
  const w = new ByteWriter(1 + 1 + 8 + 4 + 4 + 2 + 2 + 2 + 4 + 4);
  w.byte(MessageType.INJECT_TOUCH_EVENT);
  w.byte(action);
  w.long(pointerId);
  writePosition(w, position);
  w.short(u16FixedPoint(pressure));
  w.int(actionButton);
  w.int(buttons);
  return w.toArray();
}

export function serializeInjectScrollEvent(
    position: Position, hScroll: number, vScroll: number, buttons: number): Uint8Array {
  const w = new ByteWriter(1 + 4 + 4 + 2 + 2 + 2 + 2 + 4);
  w.byte(MessageType.INJECT_SCROLL_EVENT);
  writePosition(w, position);
  w.short(i16FixedPoint(hScroll / 16));
  w.short(i16FixedPoint(vScroll / 16));
  w.int(buttons);
  return w.toArray();
}

export function serializeBackOrScreenOn(action: KeyEventAction): Uint8Array {
  const w = new ByteWriter(2);
  w.byte(MessageType.BACK_OR_SCREEN_ON);
  w.byte(action);
  return w.toArray();
}

export function serializeExpandNotificationPanel(): Uint8Array {
  return emptyMessage(MessageType.EXPAND_NOTIFICATION_PANEL);
}

export function serializeExpandSettingsPanel(): Uint8Array {
  return emptyMessage(MessageType.EXPAND_SETTINGS_PANEL);
}

export function serializeCollapsePanels(): Uint8Array {
  return emptyMessage(MessageType.COLLAPSE_PANELS);
}

export function serializeRotateDevice(): Uint8Array {
  return emptyMessage(MessageType.ROTATE_DEVICE);
}
