package com.genymobile.scrcpy.signal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** A token admits one connection once within five minutes of startup. */
public final class SessionAdmission {
    private final byte[] token;
    private final long expiresAtMs;
    public static final long RECOVERY_TIMEOUT_MS = 45000;
    private final String resumeToken;
    private long resumeUntilMs = Long.MAX_VALUE;
    private Object owner;
    private boolean consumed;
    private String peerId = "";
    public SessionAdmission(String token, long nowMs) {
        if (token == null || token.length() < 32) {
            throw new IllegalArgumentException("signal_token must contain at least 32 characters");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
        expiresAtMs = nowMs + 300000;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        StringBuilder hex = new StringBuilder(64);
        for (byte value : secret) { hex.append(String.format("%02x", value & 0xff)); }
        resumeToken = hex.toString();
    }
    public synchronized boolean claim(Object connection, String supplied, long nowMs) {
        if (claimRejection(supplied, nowMs) != null) { return false; }
        owner = connection;
        consumed = true;
        return true;
    }
    public synchronized String getResumeToken() { return resumeToken; }

    // Only the secret issued to the authenticated client can replace a stale socket.
    // The initial login token remains single-use.
    public synchronized boolean resume(Object connection, String supplied, long nowMs) {
        if (resumeRejection(supplied, nowMs) != null) { return false; }
        owner = connection;
        resumeUntilMs = Long.MAX_VALUE;
        return true;
    }

    public synchronized String claimRejection(String supplied, long nowMs) {
        if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            return "LOGIN_TOKEN_INVALID";
        }
        if (consumed) { return "LOGIN_TOKEN_USED"; }
        if (nowMs > expiresAtMs) { return "LOGIN_TOKEN_EXPIRED"; }
        return null;
    }

    public synchronized String resumeRejection(String supplied, long nowMs) {
        if (supplied == null || !MessageDigest.isEqual(resumeToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            return "RESUME_TOKEN_INVALID";
        }
        if (!consumed) { return "RESUME_NOT_AVAILABLE"; }
        if (nowMs > resumeUntilMs) { return "RESUME_EXPIRED"; }
        return null;
    }

    // Call only after authentication succeeds. Same-page socket recovery keeps
    // the peer; a refreshed page has a new identity and needs a new DTLS/SCTP peer.
    public synchronized boolean updatePeerId(String value) {
        boolean changed = !peerId.equals(value);
        peerId = value;
        return changed;
    }

    public synchronized boolean recoveryExpired(long nowMs) {
        return consumed && owner == null && nowMs > resumeUntilMs;
    }

    public synchronized boolean owns(Object connection) {
        return owner == connection && owner != null;
    }

    public synchronized boolean release(Object connection, long nowMs) {
        if (!owns(connection)) { return false; }
        owner = null;
        resumeUntilMs = nowMs + RECOVERY_TIMEOUT_MS;
        return true;
    }
}
