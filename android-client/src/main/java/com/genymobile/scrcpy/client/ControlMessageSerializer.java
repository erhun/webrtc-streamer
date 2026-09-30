package com.genymobile.scrcpy.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class ControlMessageSerializer {

    public static final int TYPE_INJECT_KEYCODE = 0;
    public static final int TYPE_INJECT_TEXT = 1;
    public static final int TYPE_INJECT_TOUCH_EVENT = 2;
    public static final int TYPE_INJECT_SCROLL_EVENT = 3;
    public static final int TYPE_BACK_OR_SCREEN_ON = 4;
    public static final int TYPE_EXPAND_NOTIFICATION_PANEL = 5;
    public static final int TYPE_EXPAND_SETTINGS_PANEL = 6;
    public static final int TYPE_COLLAPSE_PANELS = 7;
    public static final int TYPE_GET_CLIPBOARD = 8;
    public static final int TYPE_SET_CLIPBOARD = 9;
    public static final int TYPE_SET_DISPLAY_POWER = 10;
    public static final int TYPE_ROTATE_DEVICE = 11;
    public static final int TYPE_UHID_CREATE = 12;
    public static final int TYPE_UHID_INPUT = 13;
    public static final int TYPE_UHID_DESTROY = 14;
    public static final int TYPE_OPEN_HARD_KEYBOARD_SETTINGS = 15;
    public static final int TYPE_START_APP = 16;
    public static final int TYPE_RESET_VIDEO = 17;
    public static final int TYPE_CAMERA_SET_TORCH = 18;
    public static final int TYPE_CAMERA_ZOOM_IN = 19;
    public static final int TYPE_CAMERA_ZOOM_OUT = 20;
    public static final int TYPE_RESIZE_DISPLAY = 21;
    public static final int TYPE_SCAN_FILE = 22;
    public static final int TYPE_RELEASE_INPUTS = 23;

    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;

    public static final int COPY_KEY_NONE = 0;
    public static final int COPY_KEY_COPY = 1;
    public static final int COPY_KEY_CUT = 2;

    private ControlMessageSerializer() {
    }

    private static ByteBuffer newBuffer(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN);
    }

    public static byte[] serializeInjectKeycode(int action, int keycode, int repeat, int metaState) {
        ByteBuffer buf = newBuffer(1 + 1 + 4 + 4 + 4);
        buf.put((byte) TYPE_INJECT_KEYCODE);
        buf.put((byte) action);
        buf.putInt(keycode);
        buf.putInt(repeat);
        buf.putInt(metaState);
        return buf.array();
    }

    public static byte[] serializeInjectText(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = newBuffer(1 + 4 + utf8.length);
        buf.put((byte) TYPE_INJECT_TEXT);
        buf.putInt(utf8.length);
        buf.put(utf8);
        return buf.array();
    }

    public static byte[] serializeInjectTouchEvent(int action, long pointerId, int x, int y,
            int screenWidth, int screenHeight, float pressure, int actionButton, int buttons) {
        ByteBuffer buf = newBuffer(1 + 1 + 8 + 4 + 4 + 2 + 2 + 2 + 4 + 4);
        buf.put((byte) TYPE_INJECT_TOUCH_EVENT);
        buf.put((byte) action);
        buf.putLong(pointerId);
        buf.putInt(x);
        buf.putInt(y);
        buf.putShort((short) screenWidth);
        buf.putShort((short) screenHeight);
        buf.putShort((short) toU16FixedPoint(pressure));
        buf.putInt(actionButton);
        buf.putInt(buttons);
        return buf.array();
    }

    public static byte[] serializeInjectScrollEvent(int x, int y, int screenWidth, int screenHeight,
            float hScroll, float vScroll, int buttons) {
        ByteBuffer buf = newBuffer(1 + 4 + 4 + 2 + 2 + 2 + 2 + 4);
        buf.put((byte) TYPE_INJECT_SCROLL_EVENT);
        buf.putInt(x);
        buf.putInt(y);
        buf.putShort((short) screenWidth);
        buf.putShort((short) screenHeight);
        buf.putShort((short) toI16FixedPoint(hScroll / 16f));
        buf.putShort((short) toI16FixedPoint(vScroll / 16f));
        buf.putInt(buttons);
        return buf.array();
    }

    public static byte[] serializeBackOrScreenOn(int action) {
        ByteBuffer buf = newBuffer(2);
        buf.put((byte) TYPE_BACK_OR_SCREEN_ON);
        buf.put((byte) action);
        return buf.array();
    }

    public static byte[] serializeEmpty(int type) {
        ByteBuffer buf = newBuffer(1);
        buf.put((byte) type);
        return buf.array();
    }

    public static byte[] serializeGetClipboard(int copyKey) {
        ByteBuffer buf = newBuffer(2);
        buf.put((byte) TYPE_GET_CLIPBOARD);
        buf.put((byte) copyKey);
        return buf.array();
    }

    public static byte[] serializeSetClipboard(long sequence, String text, boolean paste) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = newBuffer(1 + 8 + 1 + 4 + utf8.length);
        buf.put((byte) TYPE_SET_CLIPBOARD);
        buf.putLong(sequence);
        buf.put(paste ? (byte) 1 : (byte) 0);
        buf.putInt(utf8.length);
        buf.put(utf8);
        return buf.array();
    }

    public static byte[] serializeSetDisplayPower(boolean on) {
        ByteBuffer buf = newBuffer(2);
        buf.put((byte) TYPE_SET_DISPLAY_POWER);
        buf.put(on ? (byte) 1 : (byte) 0);
        return buf.array();
    }

    public static byte[] serializeUhidCreate(int id, int vendorId, int productId, String name, byte[] reportDesc) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = newBuffer(1 + 2 + 2 + 2 + 1 + nameBytes.length + 2 + reportDesc.length);
        buf.put((byte) TYPE_UHID_CREATE);
        buf.putShort((short) id);
        buf.putShort((short) vendorId);
        buf.putShort((short) productId);
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        buf.putShort((short) reportDesc.length);
        buf.put(reportDesc);
        return buf.array();
    }

    public static byte[] serializeUhidInput(int id, byte[] data) {
        ByteBuffer buf = newBuffer(1 + 2 + 2 + data.length);
        buf.put((byte) TYPE_UHID_INPUT);
        buf.putShort((short) id);
        buf.putShort((short) data.length);
        buf.put(data);
        return buf.array();
    }

    public static byte[] serializeUhidDestroy(int id) {
        ByteBuffer buf = newBuffer(3);
        buf.put((byte) TYPE_UHID_DESTROY);
        buf.putShort((short) id);
        return buf.array();
    }

    public static byte[] serializeStartApp(String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = newBuffer(1 + 1 + nameBytes.length);
        buf.put((byte) TYPE_START_APP);
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        return buf.array();
    }

    public static byte[] serializeCameraSetTorch(boolean on) {
        ByteBuffer buf = newBuffer(2);
        buf.put((byte) TYPE_CAMERA_SET_TORCH);
        buf.put(on ? (byte) 1 : (byte) 0);
        return buf.array();
    }

    public static byte[] serializeResizeDisplay(int width, int height) {
        ByteBuffer buf = newBuffer(5);
        buf.put((byte) TYPE_RESIZE_DISPLAY);
        buf.putShort((short) width);
        buf.putShort((short) height);
        return buf.array();
    }

    public static byte[] serializeScanFile(String path) {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = newBuffer(1 + 4 + pathBytes.length);
        buf.put((byte) TYPE_SCAN_FILE);
        buf.putInt(pathBytes.length);
        buf.put(pathBytes);
        return buf.array();
    }

    private static int toU16FixedPoint(float value) {
        if (value >= 1f) {
            return 0xffff;
        }
        return Math.max(0, Math.round(value * 0x10000));
    }

    private static int toI16FixedPoint(float value) {
        if (value >= 1f) {
            return 0x7fff;
        }
        if (value <= -1f) {
            return -0x8000;
        }
        return Math.round(value * 0x8000);
    }
}
