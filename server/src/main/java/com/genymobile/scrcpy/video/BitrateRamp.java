package com.genymobile.scrcpy.video;

/** Encoder-thread target limiter: increase gradually, but obey a smaller budget immediately. */
public final class BitrateRamp {
    private static final double GROWTH_PER_SECOND = 1.25;
    private static final int MIN_INCREASE_BPS_PER_SECOND = 100_000;
    private static final long MAX_ELAPSED_MS = 1000;

    private double bitrate;
    private long lastUpdateMs = Long.MIN_VALUE;

    public BitrateRamp(int initialBitrate) {
        bitrate = Math.max(1, initialBitrate);
    }

    /** A pause must not accumulate credit for an immediate rate increase on resume. */
    public void pause() {
        lastUpdateMs = Long.MIN_VALUE;
    }

    public int update(int targetBitrate, long nowMs) {
        int target = Math.max(1, targetBitrate);
        long elapsedMs = lastUpdateMs == Long.MIN_VALUE || nowMs < lastUpdateMs
                ? 0 : Math.min(nowMs - lastUpdateMs, MAX_ELAPSED_MS);
        lastUpdateMs = nowMs;
        if (target <= bitrate) {
            bitrate = target;
        } else {
            double seconds = elapsedMs / 1000.0;
            double increased = Math.max(bitrate * Math.pow(GROWTH_PER_SECOND, seconds),
                    bitrate + MIN_INCREASE_BPS_PER_SECOND * seconds);
            bitrate = Math.min(target, increased);
        }
        return (int) bitrate;
    }
}
