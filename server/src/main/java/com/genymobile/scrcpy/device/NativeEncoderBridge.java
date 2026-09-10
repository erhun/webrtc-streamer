package com.genymobile.scrcpy.device;

import java.nio.ByteBuffer;

/** Per-session JNI handle. Callback entry points never acquire its monitor. */
public final class NativeEncoderBridge implements AutoCloseable {
    public interface Callback {
        void onBitrate(int bps, double fps);
        void onKeyFrameRequest();
    }
    public interface SignalCallback {
        void onAnswer(String sdp);
        void onIceCandidate(String mid, int index, String sdp);
    }
    public interface DataCallback {
        void onData(byte[] data);
    }
    static { System.loadLibrary("scrcpy_native"); }
    private long handle;
    private volatile Callback callback;
    private volatile SignalCallback signalCallback;
    private volatile DataCallback dataCallback;
    private volatile Runnable closedCallback;

    public synchronized void open(boolean audio, String turn, String user, String password) {
        if (handle != 0) { throw new IllegalStateException("Session already open"); }
        handle = nativeCreate(audio, turn, user, password);
        if (handle == 0) { throw new IllegalStateException("Could not create WebRTC session"); }
    }
    public void setCallback(Callback value) { callback = value; }
    public void setSignalCallback(SignalCallback value) { signalCallback = value; }
    public void setDataCallback(DataCallback value) { dataCallback = value; }
    public void setClosedCallback(Runnable value) { closedCallback = value; }
    public synchronized void pushEncodedVideoFrame(ByteBuffer buffer, long pts, boolean config, boolean key, int width, int height) {
        if (handle != 0) { nativePushVideo(handle, copy(buffer), pts, config, key, width, height); }
    }
    public synchronized void pushPcm(ByteBuffer buffer, long pts) {
        if (handle != 0) { nativePushPcm(handle, copy(buffer), pts); }
    }
    public synchronized void onOffer(String sdp) { if (handle != 0) { nativeOffer(handle, sdp); } }
    public synchronized void onIceCandidate(String mid, int index, String sdp) {
        if (handle != 0) { nativeIce(handle, mid, index, sdp); }
    }
    public synchronized boolean sendData(byte[] data) { return handle != 0 && nativeSend(handle, data); }
    @Override
    public synchronized void close() {
        callback = null; signalCallback = null; dataCallback = null; closedCallback = null;
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
    }
    private static byte[] copy(ByteBuffer buffer) {
        byte[] data = new byte[buffer.remaining()]; buffer.get(data); return data;
    }
    private void onNativeBitrate(int bps, double fps) {
        Callback target = callback; if (target != null) { target.onBitrate(bps, fps); }
    }
    private void onNativeKeyFrame() {
        Callback target = callback; if (target != null) { target.onKeyFrameRequest(); }
    }
    private void onNativeAnswer(String sdp) {
        SignalCallback target = signalCallback; if (target != null) { target.onAnswer(sdp); }
    }
    private void onNativeIce(String mid, int index, String sdp) {
        SignalCallback target = signalCallback; if (target != null) { target.onIceCandidate(mid, index, sdp); }
    }
    private void onNativeData(byte[] data) {
        DataCallback target = dataCallback; if (target != null) { target.onData(data); }
    }
    private void onNativeClosed() {
        Runnable target = closedCallback; if (target != null) { target.run(); }
    }
    private native long nativeCreate(boolean audio, String turn, String user, String password);
    private native void nativeDestroy(long session);
    private native void nativePushVideo(long session, byte[] data, long pts, boolean config, boolean key, int width, int height);
    private native void nativePushPcm(long session, byte[] data, long pts);
    private native void nativeOffer(long session, String sdp);
    private native void nativeIce(long session, String mid, int index, String sdp);
    private native boolean nativeSend(long session, byte[] data);
}
