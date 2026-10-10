package com.genymobile.scrcpy.signal;

import com.genymobile.scrcpy.util.Ln;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SignalServer implements AutoCloseable {
    public interface Listener {
        void onClientJoined(String roomId, String clientId);
        void onClientLeft(String roomId, String clientId);
        void onOffer(String roomId, String clientId, String sdp);
        void onIceCandidate(String roomId, String clientId, String mid, int index, String sdp);
        void onRoomEmpty(String roomId);
    }

    private final WebSocketServer server;
    private final Listener listener;
    private final String signalToken;
    private final int defaultMaxPeers;
    private final PortMapConfig portMapConfig;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<WebSocket, ClientSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean stopped;

    public SignalServer(int port, String token, int defaultMaxPeers, Listener listener) {
        this(port, token, defaultMaxPeers, listener, null);
    }

    public SignalServer(int port, String token, int defaultMaxPeers, Listener listener, PortMapConfig portMapConfig) {
        this.listener = listener;
        this.signalToken = token;
        this.defaultMaxPeers = defaultMaxPeers;
        this.portMapConfig = portMapConfig;
        server = new WebSocketServer(new InetSocketAddress("0.0.0.0", port)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                if (stopped || sessions.size() >= 64) {
                    conn.close(1008, "Server full");
                    return;
                }
                sessions.put(conn, new ClientSession());
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                ClientSession session = sessions.remove(conn);
                if (session != null && session.roomId != null) {
                    Room room = rooms.get(session.roomId);
                    if (room != null) {
                        room.removeClient(session.clientId);
                        Ln.i("Client left room: roomId=" + session.roomId + " clientId=" + session.clientId);
                        listener.onClientLeft(session.roomId, session.clientId);
                        if (room.isEmpty()) {
                            rooms.remove(session.roomId);
                            Ln.i("Room empty, removed: roomId=" + session.roomId);
                            listener.onRoomEmpty(session.roomId);
                        }
                    }
                }
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                handleMessage(conn, message);
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                Ln.e("Signal error", ex);
            }

            @Override
            public void onStart() { /* No startup messages contain credentials. */ }
        };
        server.setMaxPendingConnections(64);
    }

    public void start() {
        server.start();
        timer.scheduleWithFixedDelay(() -> {
            for (Map.Entry<WebSocket, ClientSession> entry : sessions.entrySet()) {
                ClientSession session = entry.getValue();
                if (session.roomId == null && now() - session.connectedAt > 5000) {
                    if (sessions.remove(entry.getKey()) != null) {
                        entry.getKey().close(1008, "Authentication timeout");
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void sendAnswer(String roomId, String clientId, String sdp) {
        sendMessage(roomId, clientId, "answer", builder -> {
            try {
                builder.put("sdp", sdp);
            } catch (JSONException e) {
                Ln.e("Failed to build answer", e);
            }
        });
    }

    public void sendIceCandidate(String roomId, String clientId, String mid, int index, String sdp) {
        String rewritten = SdpRewriter.rewriteIceCandidate(sdp, portMapConfig);
        sendMessage(roomId, clientId, "ice", builder -> {
            try {
                builder.put("sdpMid", mid).put("sdpMLineIndex", index).put("candidate", rewritten);
            } catch (JSONException e) {
                Ln.e("Failed to build ice candidate", e);
            }
        });
    }

    private interface MessageBuilder {
        void build(JSONObject builder) throws JSONException;
    }

    private void sendMessage(String roomId, String clientId, String type, MessageBuilder builder) {
        if (stopped || roomId == null || clientId == null) {
            return;
        }
        Room room = rooms.get(roomId);
        if (room == null) {
            return;
        }
        WebSocket conn = room.getClient(clientId);
        if (conn == null || !conn.isOpen()) {
            return;
        }
        try {
            JSONObject msg = new JSONObject().put("type", type);
            builder.build(msg);
            conn.send(msg.toString());
        } catch (JSONException e) {
            Ln.e("Failed to build signaling message", e);
        } catch (RuntimeException e) {
            conn.close(1011, "Signaling send failed");
        }
    }

    private void handleMessage(WebSocket conn, String text) {
        if (stopped) {
            return;
        }
        if (text.length() > 262144) {
            conn.close(1009, "Message too large");
            return;
        }
        ClientSession session = sessions.get(conn);
        if (session == null) {
            return;
        }
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.getString("type");

            if (session.roomId == null) {
                if (!"join".equals(type)) {
                    conn.close(1008, "AUTH_REQUIRED");
                    return;
                }
                handleJoin(conn, session, msg);
                return;
            }

            if ("ping".equals(type)) {
                conn.send("{\"type\":\"pong\"}");
            } else if ("bye".equals(type)) {
                Ln.i("Client disconnecting: roomId=" + session.roomId + " clientId=" + session.clientId);
                conn.close(1000, "Client bye");
            } else if ("offer".equals(type)) {
                listener.onOffer(session.roomId, session.clientId, msg.getString("sdp"));
            } else if ("ice".equals(type)) {
                listener.onIceCandidate(session.roomId, session.clientId,
                        msg.getString("sdpMid"), msg.getInt("sdpMLineIndex"), msg.getString("candidate"));
            } else {
                conn.close(1008, "Unknown message");
            }
        } catch (Exception e) {
            conn.close(1008, "Invalid signaling message");
        }
    }

    private void handleJoin(WebSocket conn, ClientSession session, JSONObject msg) throws JSONException {
        String token = msg.optString("token");
        if (!signalToken.isEmpty() && (token == null || !constantTimeEquals(signalToken, token))) {
            conn.close(1008, "LOGIN_TOKEN_INVALID");
            return;
        }
        String roomId = msg.optString("room", "default");
        if (roomId.isEmpty() || roomId.length() > 128 || !isValidRoomId(roomId)) {
            conn.close(1008, "INVALID_ROOM_ID");
            return;
        }
        
        // 调试模式下允许客户端指定 clientId（用于会话恢复）
        String clientId = msg.optString("clientId");
        if (clientId == null || clientId.isEmpty() || !isValidClientId(clientId)) {
            clientId = generateClientId();
        }

        Room room = rooms.computeIfAbsent(roomId, id -> new Room(id, defaultMaxPeers));
        
        // 如果 clientId 已存在，先移除旧连接（页面刷新场景）
        WebSocket existingConn = room.getClient(clientId);
        if (existingConn != null) {
            Ln.i("Replacing existing client: roomId=" + roomId + " clientId=" + clientId);
            room.removeClient(clientId);
            ClientSession oldSession = sessions.remove(existingConn);
            if (oldSession != null) {
                oldSession.roomId = null;
                oldSession.clientId = null;
            }
            existingConn.close(1000, "Replaced by new connection");
        }
        
        String rejection = room.addClient(clientId, conn);
        if (rejection != null) {
            conn.close(1008, rejection);
            if (room.isEmpty()) {
                rooms.remove(roomId);
            }
            return;
        }

        session.roomId = roomId;
        session.clientId = clientId;
        session.authenticated = true;

        Ln.i("Client joined room: roomId=" + roomId + " clientId=" + clientId);
        listener.onClientJoined(roomId, clientId);

        conn.send(new JSONObject()
                .put("type", "ready")
                .put("roomId", roomId)
                .put("clientId", clientId)
                .put("protocolVersion", 2)
                .toString());
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ab = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (ab.length != bb.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < ab.length; i++) {
            result |= ab[i] ^ bb[i];
        }
        return result == 0;
    }

    private static String generateClientId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    private static boolean isValidRoomId(String roomId) {
        for (int i = 0; i < roomId.length(); i++) {
            char c = roomId.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidClientId(String clientId) {
        if (clientId.length() != 32) {
            return false;
        }
        for (int i = 0; i < clientId.length(); i++) {
            char c = clientId.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static long now() {
        return System.nanoTime() / 1000000;
    }

    private static final class ClientSession {
        String roomId;
        String clientId;
        boolean authenticated;
        final long connectedAt = System.nanoTime() / 1000000;
    }

    @Override
    public void close() {
        stopped = true;
        timer.shutdownNow();
        sessions.clear();
        rooms.clear();
        try {
            server.stop(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
