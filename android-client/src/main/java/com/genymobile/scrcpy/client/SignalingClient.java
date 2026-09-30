package com.genymobile.scrcpy.client;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class SignalingClient {

    private static final String TAG = "SignalingClient";
    private static final long PING_INTERVAL_MS = 3000;
    private static final long PONG_TIMEOUT_MS = 5000;
    private static final long AUTH_TIMEOUT_MS = 5000;

    private final OkHttpClient httpClient;
    private final SignalingClientListener listener;
    private final ScheduledExecutorService scheduler;

    private WebSocket webSocket;
    private String peerId;
    private String resumeToken;
    private String loginToken;
    private String serverUrl;
    private boolean authenticated;
    private boolean intentionalClose;
    private ScheduledFuture<?> pingTask;
    private ScheduledFuture<?> pongTimeoutTask;
    private ScheduledFuture<?> authTimeoutTask;

    public SignalingClient(SignalingClientListener listener) {
        this.listener = listener;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MINUTES)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        this.scheduler = new ScheduledThreadPoolExecutor(1);
        this.peerId = generatePeerId();
    }

    public void connect(String url, String token) {
        this.serverUrl = url;
        this.loginToken = token;
        intentionalClose = false;
        connectInternal();
    }

    private void connectInternal() {
        Request request = new Request.Builder().url(serverUrl).build();
        webSocket = httpClient.newWebSocket(request, new SignalingWebSocketListener());
        authTimeoutTask = scheduler.schedule(() -> {
            if (!authenticated) {
                Log.e(TAG, "Authentication timeout");
                listener.onError("Authentication timeout");
                disconnect();
            }
        }, AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public void reconnect() {
        if (serverUrl == null) {
            Log.w(TAG, "Cannot reconnect: no server URL");
            return;
        }
        stopHeartbeat();
        if (webSocket != null) {
            webSocket.close(1000, "Reconnecting");
            webSocket = null;
        }
        authenticated = false;
        intentionalClose = false;
        connectInternal();
    }

    public void disconnect() {
        intentionalClose = true;
        stopHeartbeat();
        if (authTimeoutTask != null) {
            authTimeoutTask.cancel(false);
            authTimeoutTask = null;
        }
        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
            webSocket = null;
        }
        authenticated = false;
    }

    public boolean isIntentionalClose() {
        return intentionalClose;
    }

    public String getResumeToken() {
        return resumeToken;
    }

    public void sendOffer(String sdp) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "offer");
            json.put("sdp", sdp);
            send(json);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build offer", e);
        }
    }

    public void sendIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "ice");
            json.put("sdpMid", sdpMid);
            json.put("sdpMLineIndex", sdpMLineIndex);
            json.put("candidate", candidate);
            send(json);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build ice candidate", e);
        }
    }

    public void sendBye() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "bye");
            send(json);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build bye", e);
        }
    }

    public String getPeerId() {
        return peerId;
    }

    private void send(JSONObject json) {
        if (webSocket != null) {
            webSocket.send(json.toString());
        }
    }

    private void startHeartbeat() {
        pingTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("type", "ping");
                send(json);
                pongTimeoutTask = scheduler.schedule(() -> {
                    Log.e(TAG, "Pong timeout");
                    listener.onError("Heartbeat timeout");
                    disconnect();
                }, PONG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to build ping", e);
            }
        }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (pingTask != null) {
            pingTask.cancel(false);
            pingTask = null;
        }
        if (pongTimeoutTask != null) {
            pongTimeoutTask.cancel(false);
            pongTimeoutTask = null;
        }
    }

    private static String generatePeerId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private class SignalingWebSocketListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            Log.i(TAG, "WebSocket connected");
            try {
                JSONObject json = new JSONObject();
                if (resumeToken != null) {
                    json.put("type", "resume");
                    json.put("token", resumeToken);
                } else {
                    json.put("type", "auth");
                    json.put("token", loginToken);
                }
                json.put("peerId", peerId);
                ws.send(json.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Failed to build auth message", e);
                listener.onError("Failed to authenticate");
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject json = new JSONObject(text);
                String type = json.getString("type");
                switch (type) {
                    case "ready":
                        handleReady(json);
                        break;
                    case "answer":
                        listener.onAnswer(json.getString("sdp"));
                        break;
                    case "ice":
                        listener.onIceCandidate(
                                json.getString("sdpMid"),
                                json.getInt("sdpMLineIndex"),
                                json.getString("candidate"));
                        break;
                    case "pong":
                        handlePong();
                        break;
                    default:
                        Log.w(TAG, "Unknown message type: " + type);
                        break;
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse message: " + text, e);
            }
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            Log.i(TAG, "WebSocket closing: code=" + code + " reason=" + reason);
            ws.close(1000, null);
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            Log.i(TAG, "WebSocket closed: code=" + code + " reason=" + reason);
            stopHeartbeat();
            authenticated = false;
            if (!intentionalClose) {
                listener.onConnectionDropped();
            }
            listener.onClosed(code, reason);
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            Log.e(TAG, "WebSocket failure", t);
            stopHeartbeat();
            authenticated = false;
            if (!intentionalClose) {
                listener.onConnectionDropped();
            }
            listener.onError("Connection failed: " + t.getMessage());
        }

        private void handleReady(JSONObject json) throws JSONException {
            resumeToken = json.getString("resumeToken");
            boolean resumed = json.getBoolean("resumed");
            String remotePeerId = json.getString("peerId");
            int protocolVersion = json.getInt("protocolVersion");
            authenticated = true;
            if (authTimeoutTask != null) {
                authTimeoutTask.cancel(false);
                authTimeoutTask = null;
            }
            startHeartbeat();
            Log.i(TAG, "Authenticated: resumed=" + resumed + " protocol=" + protocolVersion);
            listener.onReady(resumeToken, resumed, remotePeerId, protocolVersion);
        }

        private void handlePong() {
            if (pongTimeoutTask != null) {
                pongTimeoutTask.cancel(false);
                pongTimeoutTask = null;
            }
        }
    }
}
