package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.util.Ln;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/** Serializes authority changes with input delivery. Transport sends never run under the input lock. */
public final class ControlManager {
    public interface ControlForwarder { void forward(byte[] data); }
    public interface ClientSender { void send(String message); }
    public interface OwnerObserver { void changed(String id); }
    private final Object lock = new Object();
    private String currentController;
    private final Queue<String> waitingQueue = new ArrayDeque<>();
    private final Set<String> waitingSet = new HashSet<>();
    private final Map<String, ControlForwarder> forwarders = new HashMap<>();
    private final Map<String, ClientSender> senders = new HashMap<>();
    private ControlForwarder controller;
    private OwnerObserver ownerChanged = id -> { };
    /** Observer must be nonblocking; invoked with input serialization so budget authority cannot reorder. */
    public void setOwnerChanged(OwnerObserver observer) { synchronized (lock) { ownerChanged = observer; } }

    public void setController(ControlForwarder value) { synchronized (lock) { controller = value; } }
    public void registerForwarder(String id, ControlForwarder forwarder) {
        synchronized (lock) { forwarders.put(id, forwarder); }
    }
    public void registerSender(String id, ClientSender sender) { synchronized (lock) { senders.put(id, sender); } }
    public void unregisterForwarder(String id) {
        synchronized (lock) {
            forwarders.remove(id);
            senders.remove(id);
            waitingQueue.remove(id);
            waitingSet.remove(id);
            if (id.equals(currentController)) { releaseInputsLocked(); currentController = null; promoteNext(); }
            ownerChanged.changed(currentController);
        }
        broadcastControlState();
    }
    public void handleControlData(String id, byte[] data) {
        // Binary scrcpy commands start with a numeric type, so do not allocate a String/JSON for every motion event.
        if (data.length > 0 && data[0] == '{') {
            try {
                String type = new JSONObject(new String(data, StandardCharsets.UTF_8)).optString("type");
                if ("REQUEST_CONTROL".equals(type)) { requestControl(id); return; }
                if ("RELEASE_CONTROL".equals(type)) { releaseControl(id); return; }
            } catch (JSONException e) { return; }
            return; // Unknown JSON must not corrupt the binary control stream.
        }
        forwardControlMessage(id, data);
    }
    public void requestControl(String id) {
        synchronized (lock) {
            if (!forwarders.containsKey(id) || id.equals(currentController)) { return; }
            if (currentController == null) { currentController = id; }
            else if (waitingSet.add(id)) { waitingQueue.add(id); }
            ownerChanged.changed(currentController);
        }
        broadcastControlState();
    }
    public void releaseControl(String id) {
        synchronized (lock) {
            if (id.equals(currentController)) { releaseInputsLocked(); currentController = null; promoteNext(); }
            else { waitingQueue.remove(id); waitingSet.remove(id); }
            ownerChanged.changed(currentController);
        }
        broadcastControlState();
    }
    public void forwardControlMessage(String id, byte[] data) {
        boolean granted = false;
        synchronized (lock) {
            if (!forwarders.containsKey(id)) { return; }
            if (currentController == null) { currentController = id; granted = true; ownerChanged.changed(id); }
            if (!id.equals(currentController)) { return; }
            // Enqueue under the same lock as ownership changes: DOWN, release marker, then new owner's input.
            if (controller != null) { controller.forward(data); }
        }
        if (granted) { broadcastControlState(); }
    }
    public String getCurrentController() { synchronized (lock) { return currentController; } }
    private void releaseInputsLocked() {
        if (controller != null) { controller.forward(new byte[]{(byte) ControlMessage.TYPE_RELEASE_INPUTS}); }
    }
    private void promoteNext() {
        while (!waitingQueue.isEmpty()) {
            String next = waitingQueue.remove(); waitingSet.remove(next);
            if (forwarders.containsKey(next)) { currentController = next; return; }
        }
    }
    public void broadcastControlState() {
        String message;
        List<ClientSender> targets;
        synchronized (lock) {
            try {
                JSONObject state = new JSONObject().put("type", "CONTROL_STATE")
                        .put("controller", currentController != null ? currentController : JSONObject.NULL)
                        .put("queue", new ArrayList<>(waitingQueue));
                message = state.toString();
                targets = new ArrayList<>(senders.values());
            } catch (JSONException e) { Ln.e("Cannot serialize control state", e); return; }
        }
        for (ClientSender sender : targets) {
            try { sender.send(message); }
            catch (RuntimeException e) { Ln.w("Control-state delivery failed"); }
        }
    }
}
