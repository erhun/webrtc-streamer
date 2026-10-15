package com.genymobile.scrcpy.stream;

import java.util.Objects;

/** A media peer belongs to one room; a client id alone is never a routing identity. */
public final class PeerKey {
    private final String room;
    private final String client;

    public PeerKey(String room, String client) {
        this.room = Objects.requireNonNull(room);
        this.client = Objects.requireNonNull(client);
    }

    public String getRoom() { return room; }
    public String getClient() { return client; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PeerKey)) { return false; }
        PeerKey key = (PeerKey) other;
        return room.equals(key.room) && client.equals(key.client);
    }

    @Override
    public int hashCode() { return Objects.hash(room, client); }

    @Override
    public String toString() { return room + "/" + client; }
}
