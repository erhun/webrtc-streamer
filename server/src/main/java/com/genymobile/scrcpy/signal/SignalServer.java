package com.genymobile.scrcpy.signal;

import com.genymobile.scrcpy.stream.PeerKey;
import com.genymobile.scrcpy.util.Ln;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Socket adapter and recovery protocol. Mutable protocol state belongs to one executor. */
public final class SignalServer implements AutoCloseable {
    public interface Listener {
        void onClientJoined(String roomId, String clientId);
        void onClientLeft(String roomId, String clientId);
        void onOffer(String roomId, String clientId, String sdp);
        void onIceCandidate(String roomId, String clientId, String mid, int index, String sdp);
        void onRoomEmpty(String roomId);
        default boolean onPeerReplaced(String roomId, String clientId) { return false; }
        default void onClientResumed(String roomId, String clientId) { }
        default void onClientSuspended(String roomId, String clientId) { }
        default void onServerStartFailed(Exception error) { }
    }
    private static final int MAX_SOCKETS = 64;
    private static final int MAX_MESSAGE_CHARS = 65536;
    private static final int MAX_QUEUED_MESSAGES = 64;
    private final WebSocketServer server;
    private final Listener listener;
    private final String signalToken;
    private final String allowedRoom;
    private final int maxPeers;
    private final PortMapConfig portMapConfig;
    private final SecureRandom random = new SecureRandom();
    private final Map<WebSocket, SocketSession> sockets = new HashMap<>();
    private final Map<PeerKey, PeerSession> peers = new HashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "signaling"));
    private final AtomicInteger queuedMessages = new AtomicInteger();
    private volatile boolean stopped;
    private volatile boolean listening;

    public SignalServer(int port, String token, int maxPeers, Listener listener) {
        this("0.0.0.0", port, token, "default", maxPeers, listener, null);
    }
    public SignalServer(int port, String token, int maxPeers, Listener listener, PortMapConfig portMapConfig) {
        this("0.0.0.0", port, token, "default", maxPeers, listener, portMapConfig);
    }
    public SignalServer(String bind, int port, String token, String room, int maxPeers, Listener listener, PortMapConfig portMapConfig) {
        this.listener = listener;
        signalToken = token;
        allowedRoom = room;
        this.maxPeers = maxPeers;
        this.portMapConfig = portMapConfig;
        server = new WebSocketServer(new InetSocketAddress(bind, port)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                dispatch(() -> {
                    if (sockets.size() >= MAX_SOCKETS) { conn.close(1008, "Server full"); }
                    else { sockets.put(conn, new SocketSession()); }
                });
            }
            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) { dispatch(() -> detach(conn)); }
            @Override
            public void onMessage(WebSocket conn, String message) {
                if (message.length() > MAX_MESSAGE_CHARS || queuedMessages.incrementAndGet() > MAX_QUEUED_MESSAGES) {
                    if (message.length() <= MAX_MESSAGE_CHARS) { queuedMessages.decrementAndGet(); }
                    conn.close(1008, "Signal overload"); return;
                }
                dispatch(() -> {
                    try { handleMessage(conn, message); }
                    finally { queuedMessages.decrementAndGet(); }
                });
            }
            @Override
            public void onError(WebSocket conn, Exception error) {
                Ln.e("Signal transport error", error);
                if (conn == null && !listening && !stopped) { listener.onServerStartFailed(error); }
            }
            @Override
            public void onStart() { listening = true; Ln.i("Signal server listening on " + bind + ":" + port); }
        };
        server.setMaxPendingConnections(MAX_SOCKETS);
    }
    private void dispatch(Runnable action) {
        if (stopped) { return; }
        try { executor.execute(action); }
        catch (java.util.concurrent.RejectedExecutionException e) { /* Shutdown won the race. */ }
    }
    public void start() {
        server.start();
        executor.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
    }
    private void expire() {
        long time = now();
        for (Map.Entry<WebSocket, SocketSession> entry : new ArrayList<>(sockets.entrySet())) {
            if (entry.getValue().peer == null && time - entry.getValue().connectedAt >= 5000) {
                reject(entry.getKey(), "Authentication timeout");
            }
        }
        for (PeerSession peer : new ArrayList<>(peers.values())) {
            if (peer.lease.expired(time)) { remove(peer); }
        }
    }
    public void sendAnswer(String roomId, String clientId, String sdp) { sendAnswer(roomId, clientId, 0, sdp); }
    public void sendAnswer(String roomId, String clientId, long generation, String sdp) {
        dispatch(() -> {
            PeerSession peer = peers.get(new PeerKey(roomId, clientId));
            if (peer == null || peer.generation != generation) { return; }
            peer.offerPending = false;
            peer.lastAnswer = sdp;
            send(peer, newMessage("answer", "sdp", sdp));
        });
    }
    public void sendIceCandidate(String roomId, String clientId, String mid, int index, String sdp) {
        sendIceCandidate(roomId, clientId, 0, mid, index, sdp);
    }
    public void sendIceCandidate(String roomId, String clientId, long generation, String mid, int index, String sdp) {
        dispatch(() -> {
            try {
                JSONObject msg = new JSONObject().put("type", "ice").put("sdpMid", mid).put("sdpMLineIndex", index)
                        .put("candidate", SdpRewriter.rewriteIceCandidate(sdp, portMapConfig));
                PeerSession peer = peers.get(new PeerKey(roomId, clientId));
                if (peer != null && peer.generation == generation) { send(peer, msg); }
            } catch (JSONException e) { Ln.e("Cannot serialize ICE", e); }
        });
    }
    public void disconnect(String roomId, String clientId) {
        dispatch(() -> {
            PeerSession peer = peers.get(new PeerKey(roomId, clientId));
            if (peer == null) { return; }
            WebSocket owner = peer.lease.getOwner();
            remove(peer);
            if (owner != null) { owner.close(1011, "Media peer ended"); }
        });
    }
    private void handleMessage(WebSocket conn, String text) {
        SocketSession socket = sockets.get(conn);
        if (socket == null || stopped) { return; }
        if (text.length() > MAX_MESSAGE_CHARS) { reject(conn, "Message too large"); return; }
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.getString("type");
            if (socket.peer == null) {
                if ("join".equals(type)) { join(conn, socket, msg); }
                else if ("resume".equals(type)) { resume(conn, socket, msg); }
                else { reject(conn, "AUTH_REQUIRED"); }
                return;
            }
            PeerSession peer = socket.peer;
            if (!peer.lease.owns(conn)) { reject(conn, "Stale connection"); return; }
            if ("ping".equals(type)) { conn.send("{\"type\":\"pong\"}"); }
            else if ("bye".equals(type)) { remove(peer); conn.close(1000, "Client bye"); }
            else if ("offer".equals(type)) {
                if (peer.offerPending) { reject(conn, "Offer already pending"); return; }
                peer.offerPending = true;
                peer.lastAnswer = null;
                listener.onOffer(peer.key.getRoom(), peer.key.getClient(), msg.getString("sdp"));
            } else if ("ice".equals(type)) {
                listener.onIceCandidate(peer.key.getRoom(), peer.key.getClient(), msg.getString("sdpMid"),
                        msg.getInt("sdpMLineIndex"), msg.getString("candidate"));
            } else { reject(conn, "Unknown message"); }
        } catch (Exception | LinkageError e) { Ln.e("Signaling operation failed", e); reject(conn, "Invalid signaling message"); }
    }
    private void join(WebSocket conn, SocketSession socket, JSONObject msg) throws JSONException {
        if (!signalToken.isEmpty() && !MessageDigest.isEqual(signalToken.getBytes(StandardCharsets.UTF_8),
                msg.optString("token").getBytes(StandardCharsets.UTF_8))) { reject(conn, "LOGIN_TOKEN_INVALID"); return; }
        String room = msg.optString("room", "default");
        if (!allowedRoom.equals(room)) { reject(conn, "ROOM_NOT_AUTHORIZED"); return; }
        String client = msg.optString("clientId");
        if (!validId(client)) { client = secret(16); }
        String peerId = msg.optString("peerId", client);
        if (!validId(peerId)) { reject(conn, "Invalid peer identity"); return; }
        PeerKey key = new PeerKey(room, client);
        if (peers.containsKey(key)) { reject(conn, "CLIENT_ID_EXISTS"); return; }
        if (peers.size() >= maxPeers) { reject(conn, "ROOM_FULL"); return; }
        PeerSession peer = new PeerSession(key, new PeerLease<>(conn, peerId, secret(32)));
        peers.put(key, peer);
        socket.peer = peer;
        listener.onClientJoined(room, client);
        ready(peer, false);
    }
    private void resume(WebSocket conn, SocketSession socket, JSONObject msg) throws JSONException {
        PeerSession peer = peers.get(new PeerKey(msg.optString("room", "default"), msg.optString("clientId")));
        String peerId = msg.optString("peerId");
        if (!validId(peerId)) { reject(conn, "Invalid peer identity"); return; }
        if (peer == null || !peer.lease.authorize(msg.optString("token"), now())) {
            reject(conn, "RESUME_TOKEN_INVALID"); return;
        }
        WebSocket previous = peer.lease.getOwner();
        boolean replaceMedia = peer.lease.attach(conn, peerId);
        socket.peer = peer;
        if (previous != null && previous != conn) {
            sockets.remove(previous);
            previous.close(1000, "Replaced by authenticated resume");
        }
        if (replaceMedia) {
            peer.generation++;
            peer.offerPending = false;
            peer.lastAnswer = null;
            if (!listener.onPeerReplaced(peer.key.getRoom(), peer.key.getClient())) {
                reject(conn, "Peer reset failed"); return;
            }
        }
        listener.onClientResumed(peer.key.getRoom(), peer.key.getClient());
        ready(peer, true);
        if (peer.lastAnswer != null) { send(peer, newMessage("answer", "sdp", peer.lastAnswer)); }
    }
    private void ready(PeerSession peer, boolean resumed) throws JSONException {
        send(peer, new JSONObject().put("type", "ready").put("roomId", peer.key.getRoom()).put("clientId", peer.key.getClient())
                .put("resumeToken", peer.lease.getSecret()).put("resumed", resumed).put("protocolVersion", 3));
    }
    private static JSONObject newMessage(String type, String key, String value) {
        try { return new JSONObject().put("type", type).put(key, value); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    private void send(PeerSession peer, JSONObject message) {
        if (peer == null) { return; }
        WebSocket conn = peer.lease.getOwner();
        if (conn != null && conn.isOpen()) {
            try { conn.send(message.toString()); }
            catch (RuntimeException e) { detach(conn); conn.close(1011, "Signaling send failed"); }
        }
    }
    private void detach(WebSocket conn) {
        SocketSession socket = sockets.remove(conn);
        if (socket != null && socket.peer != null && socket.peer.lease.detach(conn, now())) {
            PeerKey key = socket.peer.key;
            listener.onClientSuspended(key.getRoom(), key.getClient());
        }
    }
    private void reject(WebSocket conn, String reason) {
        SocketSession socket = sockets.remove(conn);
        if (socket != null && socket.peer != null && socket.peer.lease.owns(conn)) { remove(socket.peer); }
        conn.close(1008, reason);
    }
    private void remove(PeerSession peer) {
        if (peers.get(peer.key) != peer) { return; }
        peers.remove(peer.key);
        for (SocketSession socket : sockets.values()) { if (socket.peer == peer) { socket.peer = null; } }
        listener.onClientLeft(peer.key.getRoom(), peer.key.getClient());
        if (peers.isEmpty()) { listener.onRoomEmpty(allowedRoom); }
    }
    private String secret(int bytes) {
        byte[] value = new byte[bytes]; random.nextBytes(value);
        StringBuilder hex = new StringBuilder(bytes * 2);
        for (byte b : value) { hex.append(String.format("%02x", b & 0xff)); }
        return hex.toString();
    }
    private static boolean validId(String value) { return value.matches("[a-fA-F0-9]{32}"); }
    private static long now() { return System.nanoTime() / 1000000; }
    private static final class SocketSession {
        final long connectedAt = now();
        PeerSession peer;
    }
    private static final class PeerSession {
        final PeerKey key;
        final PeerLease<WebSocket> lease;
        long generation;
        boolean offerPending;
        String lastAnswer;
        PeerSession(PeerKey key, PeerLease<WebSocket> lease) { this.key = key; this.lease = lease; }
    }
    @Override
    public void close() {
        if (stopped) { return; }
        stopped = true;
        try {
            executor.submit(() -> {
                for (PeerSession peer : new ArrayList<>(peers.values())) { remove(peer); }
                for (WebSocket conn : sockets.keySet()) { conn.close(1001, "Server stopping"); }
                sockets.clear();
            }).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException e) { Ln.e("Signaling shutdown failed", e); }
        finally { executor.shutdownNow(); }
        try { server.stop(2000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
