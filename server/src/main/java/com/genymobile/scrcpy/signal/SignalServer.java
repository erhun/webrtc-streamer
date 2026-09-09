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
    }
    private final WebSocketServer server;
    private final Listener listener;
    private final SessionAdmission admission;
    private final Map<WebSocket, Long> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile WebSocket client;
    private volatile boolean stopped;
    private boolean offered;
    private final long createdAt = now();
    public SignalServer(int port, String token, Listener listener) {
        this.listener = listener;
        admission = new SessionAdmission(token, now());
        server = new WebSocketServer(new InetSocketAddress("127.0.0.1", port)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                if (stopped || client != null || pending.size() >= 8) { conn.close(1008, "Session unavailable"); return; }
                pending.put(conn, now());
            }
            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                pending.remove(conn);
                if (admission.release(conn)) {
                    client = null;
                    if (!stopped) { listener.onClosed(); }
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
    private static long now() { return System.nanoTime() / 1000000; }
    public void start() {
        server.start();
        timer.scheduleAtFixedRate(() -> {
            if (client == null && now() - createdAt > 300000) { listener.onClosed(); return; }
            for (Map.Entry<WebSocket, Long> entry : pending.entrySet()) {
                if (now() - entry.getValue() > 5000 && pending.remove(entry.getKey()) != null) {
                    entry.getKey().close(1008, "Authentication timeout");
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
    public void sendAnswer(String sdp) {
        try { send(new JSONObject().put("type", "answer").put("sdp", sdp)); }
        catch (JSONException e) { listener.onClosed(); }
    }
    public void sendIceCandidate(String mid, int index, String sdp) {
        try {
            send(new JSONObject().put("type", "ice").put("sdpMid", mid)
                    .put("sdpMLineIndex", index).put("candidate", sdp));
        } catch (JSONException e) { listener.onClosed(); }
    }
    private void send(JSONObject message) {
        WebSocket target = client;
        if (stopped || target == null || !target.isOpen()) { return; }
        try { target.send(message.toString()); }
        catch (RuntimeException e) { listener.onClosed(); }
    }
    private void handleMessage(WebSocket conn, String text) {
        if (stopped) { return; }
        if (text.length() > 262144) { conn.close(1009, "Message too large"); return; }
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.getString("type");
            if (!admission.owns(conn)) {
                if (!pending.containsKey(conn) || !"auth".equals(type)
                        || !admission.claim(conn, msg.optString("token"), now())) {
                    conn.close(1008, "Authentication rejected"); return;
                }
                pending.remove(conn); client = conn;
                conn.send("{\"type\":\"ready\"}"); return;
            }
            if ("offer".equals(type)) {
                synchronized (this) {
                    if (offered) { conn.close(1008, "Offer already received"); return; }
                    offered = true;
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
