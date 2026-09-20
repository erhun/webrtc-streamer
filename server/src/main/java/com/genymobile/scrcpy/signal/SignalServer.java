package com.genymobile.scrcpy.signal;

import com.genymobile.scrcpy.util.Ln;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SignalServer implements AutoCloseable {
    public interface Listener {
        void onOffer(String sdp);
        void onIceCandidate(String mid, int index, String sdp);
        void onClosed();
        boolean onPeerReset();
    }
    private final WebSocketServer server;
    private final Listener listener;
    private final SessionAdmission admission;
    private final Map<WebSocket, Long> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile WebSocket client;
    private volatile boolean stopped;
    private volatile boolean offerPending;
    private volatile boolean peerResetting;
    private volatile boolean sessionStarted;
    private final long createdAt = now();
    public SignalServer(int port, String token, Listener listener) {
        this.listener = listener;
        admission = new SessionAdmission(token, now());
        server = new WebSocketServer(new InetSocketAddress("0.0.0.0", port)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                if (stopped || pending.size() >= 8) {
                    conn.close(1008, "Session unavailable"); return;
                }
                pending.put(conn, now());
            }
            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                pending.remove(conn);
                synchronized (admission) {
                    if (admission.release(conn, now())) {
                        client = null;
                        Ln.i("Signaling disconnected: code=" + code + " remote=" + remote + "; waiting for recovery");
                    }
                }
            }
            @Override
            public void onMessage(WebSocket conn, String message) { handleMessage(conn, message); }
            @Override
            public void onError(WebSocket conn, Exception ex) {
                Ln.e("Signal error", ex);
                if (conn == null) { listener.onClosed(); }
            }
            @Override
            public void onStart() { /* No startup messages contain credentials. */ }
        };
        server.setMaxPendingConnections(8);
    }
    private static long now() {
        return System.nanoTime() / 1000000;
    }
    public void start() {
        server.start();
        timer.scheduleWithFixedDelay(() -> {
            if (admission.recoveryExpired(now()) || (client == null && now() - createdAt > 300000
                    && !sessionStarted)) {
                Ln.i("Signaling session expired without recovery");
                listener.onClosed(); return;
            }
            for (Map.Entry<WebSocket, Long> entry : pending.entrySet()) {
                if (now() - entry.getValue() > 5000 && pending.remove(entry.getKey()) != null) {
                    entry.getKey().close(1008, "Authentication timeout");
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
    public void sendAnswer(String sdp) {
        offerPending = false;
        try { send(new JSONObject().put("type", "answer").put("sdp", sdp)); }
        catch (JSONException e) { listener.onClosed(); }
    }
    public void sendIceCandidate(String mid, int index, String sdp) {
        try {
            send(new JSONObject().put("type", "ice").put("sdpMid", mid)
                    .put("sdpMLineIndex", index).put("candidate", sdp));
        } catch (JSONException e) {
            listener.onClosed();
        }
    }
    private void send(JSONObject message) {
        WebSocket target = client;
        if (stopped || peerResetting || target == null || !target.isOpen()) { return; }
        try { target.send(message.toString()); }
        catch (RuntimeException e) { target.close(1011, "Signaling send failed"); }
    }
    private void handleMessage(WebSocket conn, String text) {
        if (stopped) {
            return;
        }
        if (text.length() > 262144) {
            conn.close(1009, "Message too large"); return;
        }
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.getString("type");
            synchronized (admission) {
                if (!admission.owns(conn)) {
                    String peerId = msg.optString("peerId");
                    if (!peerId.isEmpty() && !peerId.matches("[0-9a-f]{32}")) {
                        conn.close(1008, "Invalid peer identity"); return;
                    }
                    boolean resumed = "resume".equals(type);
                    String rejection;
                    if (!pending.containsKey(conn) || (!resumed && !"auth".equals(type))) {
                        rejection = "AUTH_REQUIRED";
                    } else {
                        rejection = resumed ? admission.resumeRejection(msg.optString("token"), now())
                                : admission.claimRejection(msg.optString("token"), now());
                    }
                    if (rejection != null) {
                        Ln.w("Signaling admission rejected: mode=" + (resumed ? "resume" : "auth") + " reason=" + rejection);
                        conn.close(1008, rejection); return;
                    }
                    boolean accepted = resumed ? admission.resume(conn, msg.optString("token"), now())
                            : admission.claim(conn, msg.optString("token"), now());
                    if (!accepted) { conn.close(1008, "Authentication rejected"); return; }
                    Ln.i("Signaling admission accepted: mode=" + (resumed ? "resume" : "auth"));
                    boolean peerChanged = admission.updatePeerId(peerId);
                    peerResetting = resumed && peerChanged;
                    WebSocket previous = client;
                    pending.remove(conn);
                    client = conn;
                    sessionStarted = true;
                    if (previous != null && previous != conn) { previous.close(1000, "Signaling resumed"); }
                    if (peerResetting) {
                        try {
                            if (!listener.onPeerReset()) {
                                Ln.e("Peer reset failed: rebuild JNI and the Android server together");
                                conn.close(1008, "Peer reset failed");
                                listener.onClosed(); return;
                            }
                            offerPending = false;
                        } finally { peerResetting = false; }
                        Ln.i("Rebuilt media peer for refreshed page");
                    }
                    conn.send(new JSONObject().put("type", "ready").put("resumeToken", admission.getResumeToken())
                            .put("resumed", resumed).put("peerId", peerId).put("protocolVersion", 2).toString());
                    return;
                }
            }
            if ("ping".equals(type)) {
                conn.send("{\"type\":\"pong\"}");
            } else if ("bye".equals(type)) {
                Ln.i("Session stopped by client");
                listener.onClosed();
            } else if ("offer".equals(type)) {
                synchronized (this) {
                    if (offerPending) { conn.close(1008, "Offer already pending"); return; }
                    offerPending = true;
                }
                listener.onOffer(msg.getString("sdp"));
            } else if ("ice".equals(type)) {
                listener.onIceCandidate(msg.getString("sdpMid"), msg.getInt("sdpMLineIndex"), msg.getString("candidate"));
            } else { conn.close(1008, "Unknown message"); }
        } catch (Exception e) { conn.close(1008, "Invalid signaling message"); }
    }
    @Override
    public void close() {
        stopped = true; timer.shutdownNow(); pending.clear();
        try { server.stop(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
