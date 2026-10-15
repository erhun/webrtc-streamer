package com.genymobile.scrcpy.device;

import java.nio.ByteBuffer;
import com.genymobile.scrcpy.util.Ln;

/** Per-session JNI handle. Callback entry points never acquire its monitor. */
public final class NativeEncoderBridge implements AutoCloseable {
    public interface Callback {
        void onBitrate(int bps, double fps);
        void onKeyFrameRequest();
        default void onPeerReset() { }
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
    private long pcmPushCount;

    public synchronized void open(boolean audio, int audioScheme, String turn, String user, String password) {
        open(audio, audioScheme, turn, user, password, 0, 0);
    }
    public synchronized void open(boolean audio, int audioScheme, String turn, String user, String password, int minPort, int maxPort) {
        if (handle != 0) { throw new IllegalStateException("Session already open"); }
        if (audioScheme != 0) { throw new IllegalArgumentException("WebRTC audio requires PCM input"); }
        handle = nativeCreate(audio, audioScheme, turn, user, password, minPort, maxPort);
        if (handle == 0) { throw new IllegalStateException("Could not create WebRTC session"); }
    }
    public void setCallback(Callback value) { callback = value; }
    public void setSignalCallback(SignalCallback value) { signalCallback = value; }
    public void setDataCallback(DataCallback value) { dataCallback = value; }
    public void setClosedCallback(Runnable value) { closedCallback = value; }
    public synchronized void pushEncodedVideoFrame(ByteBuffer buffer, long pts, boolean config, boolean key, int width, int height) {
        if (handle != 0) { nativePushVideo(handle, copy(buffer), pts, config, key, width, height); }
    }
    /** Immutable for the duration of this synchronous JNI call; broadcast does not copy once per viewer. */
    public synchronized void pushEncodedVideoFrame(byte[] data, long pts, boolean config, boolean key, int width, int height) {
        if (handle != 0) { nativePushVideo(handle, data, pts, config, key, width, height); }
    }
    public synchronized void reportVideoBitrate(int bps) {
        if (handle != 0) { nativeVideoBitrate(handle, bps); }
    }
    private native void nativeVideoBitrate(long handle, int bps);

    public synchronized void pushPcm(ByteBuffer buffer, long pts) {
        if (handle != 0) { pushPcm(copy(buffer), pts); }
    }
    public synchronized void pushPcm(byte[] data, long pts) {
        if (handle != 0) {
            pcmPushCount++;
            if (pcmPushCount == 1 || pcmPushCount % 500 == 0) {
                // Diagnostic: check if audio is silence
                long sum = 0;
                for (int i = 0; i < data.length; i += 2) {
                    if (i + 1 < data.length) {
                        short sample = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
                        sum += Math.abs(sample);
                    }
                }
                int sampleCount = data.length / 2;
                long avgAmplitude = sampleCount > 0 ? sum / sampleCount : 0;
                Ln.d("NativeEncoderBridge: pushPcm #" + pcmPushCount + ", " + data.length + " bytes, avgAmplitude=" + avgAmplitude + ", handle=" + handle);
                nativePushPcm(handle, data, pts);
            } else {
                nativePushPcm(handle, data, pts);
            }
        } else {
            Ln.w("NativeEncoderBridge: pushPcm called but handle is 0");
        }
    }
    public synchronized boolean resetPeer() {
        try {
            boolean reset = handle != 0 && nativeResetPeer(handle);
            Callback target = callback;
            if (reset && target != null) { target.onPeerReset(); }
            return reset;
        }
        catch (UnsatisfiedLinkError e) { return false; } // APK packaged with an older JNI library.
    }
    private native boolean nativeResetPeer(long session);
    public synchronized void onOffer(String sdp) { if (handle != 0) { nativeOffer(handle, sdp); } }
    public synchronized void onIceCandidate(String mid, int index, String sdp) {
        if (handle != 0) { nativeIce(handle, mid, index, sdp); }
    }
    public synchronized boolean sendData(byte[] data) { return handle != 0 && nativeSend(handle, data); }
    public synchronized boolean isClosed() { return handle == 0; }
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
    private native long nativeCreate(boolean audio, int audioScheme, String turn, String user, String password, int minPort, int maxPort);
    private native void nativeDestroy(long session);
    private native void nativePushVideo(long session, byte[] data, long pts, boolean config, boolean key, int width, int height);
    private native void nativePushPcm(long session, byte[] data, long pts);
    private native void nativeOffer(long session, String sdp);
    private native void nativeIce(long session, String mid, int index, String sdp);
    private native boolean nativeSend(long session, byte[] data);
}
