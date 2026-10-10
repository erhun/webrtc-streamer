package com.genymobile.scrcpy.signal;

import org.java_websocket.WebSocket;

import java.util.HashMap;
import java.util.Map;

public final class Room {
    private final String roomId;
    private final int maxPeers;
    private final Map<String, WebSocket> clients = new HashMap<>();

    public Room(String roomId, int maxPeers) {
        this.roomId = roomId;
        this.maxPeers = maxPeers;
    }

    public String getRoomId() {
        return roomId;
    }

    public int getMaxPeers() {
        return maxPeers;
    }

    public synchronized int getClientCount() {
        return clients.size();
    }

    public synchronized boolean isFull() {
        return clients.size() >= maxPeers;
    }

    public synchronized String addClient(String clientId, WebSocket conn) {
        if (isFull()) {
            return "ROOM_FULL";
        }
        if (clients.containsKey(clientId)) {
            return "CLIENT_ID_EXISTS";
        }
        clients.put(clientId, conn);
        return null;
    }

    public synchronized void removeClient(String clientId) {
        clients.remove(clientId);
    }

    public synchronized WebSocket getClient(String clientId) {
        return clients.get(clientId);
    }

    public synchronized boolean isEmpty() {
        return clients.isEmpty();
    }
}
