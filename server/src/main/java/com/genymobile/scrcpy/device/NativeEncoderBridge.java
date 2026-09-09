package com.genymobile.scrcpy.device;

import java.nio.ByteBuffer;

public class NativeEncoderBridge {

    public interface Callback {
        void onBitrate(int bps, double fps);
        void onKeyFrameRequest();
    }

    public interface SignalCallback {
        void onAnswer(String sdp);
        void onIceCandidate(String sdpMid, int sdpMlineIndex, String sdp);
    }

    public interface DataCallback {
        void onData(byte[] data);
    }

    static {
        System.loadLibrary("scrcpy_native");
    }

    private long trackSourceHandle;
    private long audioEncoderHandle;
    private long peerConnectionHandle;
    private Callback callback;

    public void setCallback(Callback callback) {
        this.callback = callback;
        nativeSetCallbacks(callback);
    }

    public long createTrackSource() {
        trackSourceHandle = nativeCreateTrackSource();
        return trackSourceHandle;
    }

    public void destroyTrackSource() {
        if (trackSourceHandle != 0) {
            nativeDestroyTrackSource(trackSourceHandle);
            trackSourceHandle = 0;
        }
    }

    public void pushEncodedVideoFrame(ByteBuffer buffer, long ptsUs, boolean config, boolean keyFrame, int width, int height) {
        int len = buffer.remaining();
        byte[] data = new byte[len];
        buffer.get(data);
        nativePushEncodedVideoFrame(trackSourceHandle, data, ptsUs, config, keyFrame, width, height);
    }

    public long createAudioEncoder() {
        audioEncoderHandle = nativeCreateAudioEncoder();
        return audioEncoderHandle;
    }

    public void destroyAudioEncoder() {
        if (audioEncoderHandle != 0) {
            nativeDestroyAudioEncoder(audioEncoderHandle);
            audioEncoderHandle = 0;
        }
    }

    public void pushEncodedAudioFrame(ByteBuffer buffer, long ptsUs) {
        int len = buffer.remaining();
        byte[] data = new byte[len];
        buffer.get(data);
        nativePushEncodedAudioFrame(audioEncoderHandle, data, ptsUs);
    }

    public void setSignalCallback(SignalCallback callback) {
        nativeSetSignalCallback(callback);
    }

    public void setDataCallback(DataCallback callback) {
        nativeSetDataCallback(callback);
    }

    public boolean sendData(byte[] data) {
        return nativeSendData(peerConnectionHandle, data);
    }

    public long createPeerConnection() {
        peerConnectionHandle = nativeCreatePeerConnection(trackSourceHandle);
        return peerConnectionHandle;
    }

    public void destroyPeerConnection() {
        if (peerConnectionHandle != 0) {
            nativeDestroyPeerConnection(peerConnectionHandle);
            peerConnectionHandle = 0;
        }
    }

    public void onOffer(String sdp) {
        nativeOnOffer(peerConnectionHandle, sdp);
    }

    public void onIceCandidate(String sdpMid, int sdpMlineIndex, String sdp) {
        nativeOnIceCandidate(peerConnectionHandle, sdpMid, sdpMlineIndex, sdp);
    }

    private native void nativeSetCallbacks(Callback callback);
    private native long nativeCreateTrackSource();
    private native void nativeDestroyTrackSource(long handle);
    private native void nativePushEncodedVideoFrame(long handle, byte[] data, long ptsUs, boolean config, boolean keyFrame, int width,
            int height);
    private native long nativeCreateAudioEncoder();
    private native void nativeDestroyAudioEncoder(long handle);
    private native void nativePushEncodedAudioFrame(long handle, byte[] data, long ptsUs);
    private native void nativeSetSignalCallback(SignalCallback callback);
    private native void nativeSetDataCallback(DataCallback callback);
    private native long nativeCreatePeerConnection(long trackSourceHandle);
    private native void nativeDestroyPeerConnection(long handle);
    private native void nativeOnOffer(long handle, String sdp);
    private native void nativeOnIceCandidate(long handle, String sdpMid, int sdpMlineIndex, String sdp);
    private native boolean nativeSendData(long handle, byte[] data);
}
