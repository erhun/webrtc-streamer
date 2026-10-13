package com.genymobile.scrcpy.video;

/** Samples raw frames before encoding, retaining fractional cadence (30 -> 24 must not become 15). */
public final class FrameRateLimiter {
    private double periodNs;
    private double nextFrameNs;
    private long lastTimestampNs = Long.MIN_VALUE;
    private float maxFps = -1;

    public synchronized void setMaxFps(float fps) {
        if (Float.isNaN(fps) || Float.isInfinite(fps) || fps < 0) {
            throw new IllegalArgumentException("Invalid frame rate: " + fps);
        }
        if (Float.compare(maxFps, fps) == 0) {
            return;
        }
        maxFps = fps;
        periodNs = fps > 0 ? 1_000_000_000.0 / fps : 0;
        nextFrameNs = 0;
        lastTimestampNs = Long.MIN_VALUE;
    }

    public synchronized boolean shouldRender(long timestampNs) {
        if (timestampNs == lastTimestampNs) {
            return false;
        }
        if (timestampNs < lastTimestampNs) {
            nextFrameNs = 0;
        }
        lastTimestampNs = timestampNs;
        if (periodNs == 0) {
            return true;
        }
        if (nextFrameNs == 0 || timestampNs - nextFrameNs >= periodNs) {
            nextFrameNs = timestampNs + periodNs;
            return true;
        }
        if (timestampNs + 1 < nextFrameNs) {
            return false;
        }
        nextFrameNs += periodNs;
        return true;
    }
}
