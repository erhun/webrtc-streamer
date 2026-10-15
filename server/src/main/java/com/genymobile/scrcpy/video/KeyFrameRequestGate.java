package com.genymobile.scrcpy.video;

/** Coalesces repeated PLI/refresh requests across encoder polls, without suppressing them indefinitely. */
public final class KeyFrameRequestGate {
    private static final long MIN_INTERVAL_MS = 300;
    private long lastRequestMs = Long.MIN_VALUE;

    public boolean allow(long nowMs) {
        if (lastRequestMs != Long.MIN_VALUE && nowMs >= lastRequestMs && nowMs - lastRequestMs < MIN_INTERVAL_MS) {
            return false;
        }
        lastRequestMs = nowMs;
        return true;
    }
}
