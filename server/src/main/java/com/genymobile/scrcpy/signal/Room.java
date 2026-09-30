package com.genymobile.scrcpy.signal;

import org.java_websocket.WebSocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Room {
    private final String roomId;
    private final int maxPeers;
    private final Map<String, WebSocket> clients = new ConcurrentHashMap<>();

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

    public int getClientCount() {
        return clients.size();
    }

    public boolean isFull() {
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

    public WebSocket getClient(String clientId) {
        return clients.get(clientId);
    }

    public boolean isEmpty() {
        return clients.isEmpty();
    }
}
