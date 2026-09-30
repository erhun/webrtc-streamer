package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.util.Ln;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

public final class ControlManager {

    public interface ControlForwarder {
        void forward(byte[] data);
    }

    public interface ClientSender {
        void send(String message);
    }

    private final Object lock = new Object();
    private String currentController;
    private final Queue<String> waitingQueue = new ArrayDeque<>();
    private final Map<String, ControlForwarder> forwarders = new ConcurrentHashMap<>();
    private final Map<String, ClientSender> senders = new ConcurrentHashMap<>();
    private ControlForwarder controller;

    public void setController(ControlForwarder controller) {
        this.controller = controller;
    }

    public void registerForwarder(String clientId, ControlForwarder forwarder) {
        forwarders.put(clientId, forwarder);
    }

    public void registerSender(String clientId, ClientSender sender) {
        senders.put(clientId, sender);
    }

    public void unregisterForwarder(String clientId) {
        forwarders.remove(clientId);
        senders.remove(clientId);
        synchronized (lock) {
            waitingQueue.remove(clientId);
            if (clientId.equals(currentController)) {
                currentController = null;
                promoteNext();
                broadcastControlState();
            }
        }
    }

    public void handleControlData(String clientId, byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type");
            if ("REQUEST_CONTROL".equals(type)) {
                requestControl(clientId);
                broadcastControlState();
                return;
            } else if ("RELEASE_CONTROL".equals(type)) {
                releaseControl(clientId);
                broadcastControlState();
                return;
            }
        } catch (JSONException e) {
            // Not a JSON message, treat as regular control data
        }
        forwardControlMessage(clientId, data);
    }

    public void requestControl(String clientId) {
        synchronized (lock) {
            if (clientId.equals(currentController)) {
                return;
            }
            if (currentController == null) {
                currentController = clientId;
                Ln.i("Client " + clientId + " gained control");
            } else if (!waitingQueue.contains(clientId)) {
                waitingQueue.add(clientId);
                Ln.i("Client " + clientId + " added to waiting queue");
            }
        }
    }

    public void releaseControl(String clientId) {
        synchronized (lock) {
            if (clientId.equals(currentController)) {
                currentController = null;
                Ln.i("Client " + clientId + " released control");
                promoteNext();
            } else {
                waitingQueue.remove(clientId);
            }
        }
    }

    public void forwardControlMessage(String clientId, byte[] data) {
        ControlForwarder target;
        synchronized (lock) {
            if (currentController == null) {
                currentController = clientId;
                Ln.i("Auto-granted control to client " + clientId);
                broadcastControlState();
            } else if (!clientId.equals(currentController)) {
                return;
            }
            target = controller;
        }
        if (target != null) {
            target.forward(data);
        }
    }

    public String getCurrentController() {
        synchronized (lock) {
            return currentController;
        }
    }

    public void broadcastControlState() {
        synchronized (lock) {
            try {
                JSONObject state = new JSONObject();
                state.put("type", "CONTROL_STATE");
                state.put("controller", currentController != null ? currentController : JSONObject.NULL);
                List<String> queueList = new ArrayList<>(waitingQueue);
                state.put("queue", queueList);
                String message = state.toString();
                for (ClientSender sender : senders.values()) {
                    sender.send(message);
                }
            } catch (JSONException e) {
                Ln.e("Failed to build CONTROL_STATE", e);
            }
        }
    }

    private void promoteNext() {
        while (!waitingQueue.isEmpty()) {
            String next = waitingQueue.poll();
            if (forwarders.containsKey(next)) {
                currentController = next;
                Ln.i("Client " + next + " gained control from queue");
                return;
            }
        }
    }
}
