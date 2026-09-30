package com.genymobile.scrcpy.client;

public interface SignalingClientListener {
    void onReady(String resumeToken, boolean resumed, String peerId, int protocolVersion);
    void onAnswer(String sdp);
    void onIceCandidate(String sdpMid, int sdpMLineIndex, String candidate);
    void onError(String message);
    void onClosed(int code, String reason);
    void onConnectionDropped();
}
