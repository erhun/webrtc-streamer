package com.genymobile.scrcpy.signal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Recovery authority for one admitted peer. Accessed only by the signaling executor. */
public final class PeerLease<C> {
    public static final long RECOVERY_MS = 45000;
    private final String secret;
    private C owner;
    private String peerId;
    private long expiresAt = Long.MAX_VALUE;

    public PeerLease(C owner, String peerId, String secret) {
        this.owner = Objects.requireNonNull(owner);
        this.peerId = Objects.requireNonNull(peerId);
        this.secret = Objects.requireNonNull(secret);
    }

    public String getSecret() { return secret; }
    public C getOwner() { return owner; }
    public boolean owns(C connection) { return owner == connection; }

    public boolean authorize(String supplied, long nowMs) {
        return !expired(nowMs) && supplied != null && MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    public boolean attach(C connection, String nextPeerId) {
        boolean replaced = !peerId.equals(nextPeerId);
        owner = Objects.requireNonNull(connection);
        peerId = Objects.requireNonNull(nextPeerId);
        expiresAt = Long.MAX_VALUE;
        return replaced;
    }

    public boolean detach(C connection, long nowMs) {
        if (!owns(connection)) { return false; }
        owner = null;
        expiresAt = nowMs + RECOVERY_MS;
        return true;
    }

    public boolean expired(long nowMs) { return owner == null && nowMs >= expiresAt; }
}
