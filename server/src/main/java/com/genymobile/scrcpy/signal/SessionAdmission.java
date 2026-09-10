package com.genymobile.scrcpy.signal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** A token admits one connection once within five minutes of startup. */
public final class SessionAdmission {
    private final byte[] token;
    private final long expiresAtMs;
    private Object owner;
    private boolean consumed;
    public SessionAdmission(String token, long nowMs) {
        if (token == null || token.length() < 32) {
            throw new IllegalArgumentException("signal_token must contain at least 32 characters");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
        expiresAtMs = nowMs + 300000;
    }
    public synchronized boolean claim(Object connection, String supplied, long nowMs) {
        if (consumed || nowMs > expiresAtMs || supplied == null
                || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) { return false; }
        owner = connection; consumed = true; return true;
    }
    public synchronized boolean owns(Object connection) { return owner == connection && owner != null; }
    public synchronized boolean release(Object connection) {
        if (!owns(connection)) { return false; }
        owner = null; return true;
    }
}
