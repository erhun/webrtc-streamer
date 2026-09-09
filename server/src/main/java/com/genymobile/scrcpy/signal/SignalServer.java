package com.genymobile.scrcpy.signal;

import com.genymobile.scrcpy.util.Ln;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetSocketAddress;

public class SignalServer {

    public interface Listener {
        void onOffer(String sdp);
        void onIceCandidate(String sdpMid, int sdpMlineIndex, String sdp);
    }

    private final WebSocketServer server;
    private final Listener listener;
    private WebSocket client;

    public SignalServer(int port, Listener listener) {
        this.listener = listener;
        this.server = new WebSocketServer(new InetSocketAddress(port)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                client = conn;
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                client = null;
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                handleMessage(message);
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                Ln.e("Signal error", ex);
            }

            @Override
            public void onStart() {
            }
        };
    }

    public void start() {
        server.start();
    }

    public void sendAnswer(String sdp) {
        if (client == null || !client.isOpen()) {
            return;
        }
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "answer");
            msg.put("sdp", sdp);
            client.send(msg.toString());
        } catch (Exception e) {
            Ln.e("Signal error", e);
        }
    }

    public void sendIceCandidate(String sdpMid, int sdpMlineIndex, String sdp) {
        if (client == null || !client.isOpen()) {
            return;
        }
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "ice");
            msg.put("sdpMid", sdpMid);
            msg.put("sdpMLineIndex", sdpMlineIndex);
            msg.put("candidate", sdp);
            client.send(msg.toString());
        } catch (Exception e) {
            Ln.e("Signal error", e);
        }
    }

    private void handleMessage(String text) {
        if (listener == null) {
            return;
        }
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.getString("type");
            if ("offer".equals(type)) {
                listener.onOffer(msg.getString("sdp"));
            } else if ("ice".equals(type)) {
                listener.onIceCandidate(msg.optString("sdpMid"), msg.optInt("sdpMLineIndex"), msg.getString("candidate"));
            }
        } catch (JSONException e) {
            Ln.e("Signal error", e);
        }
    }
}
