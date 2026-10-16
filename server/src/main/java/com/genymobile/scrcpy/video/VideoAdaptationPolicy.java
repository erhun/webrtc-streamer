package com.genymobile.scrcpy.video;

/** Pure policy: network observations select a tier; only SurfaceEncoder may operate MediaCodec. */
public final class VideoAdaptationPolicy {
    public static final BitrateLadder.Level[] CLOUD_PHONE_LEVELS = {
        new BitrateLadder.Level(480, 10, 250000),
        new BitrateLadder.Level(640, 15, 500000),
        new BitrateLadder.Level(854, 24, 1200000),
        new BitrateLadder.Level(960, 30, 2000000),
        new BitrateLadder.Level(1280, 30, 4000000),
        new BitrateLadder.Level(1280, 60, 6000000),
        new BitrateLadder.Level(1600, 60, 8000000),
        new BitrateLadder.Level(1920, 60, 12000000),
    };
    private final BitrateLadder ladder;
    private final int maximumBps;
    private int rawBps = -1;
    private double smoothedBps = -1;
    private long lastSampleMs = -1;

    public VideoAdaptationPolicy(int maximumBps) {
        if (maximumBps <= 0) { throw new IllegalArgumentException("Video bitrate must be positive"); }
        this.maximumBps = maximumBps;
        int count = 1; // Retain the survival tier even below its nominal quality budget.
        while (count < CLOUD_PHONE_LEVELS.length && CLOUD_PHONE_LEVELS[count].getBitRate() <= maximumBps) {
            count++;
        }
        BitrateLadder.Level[] allowed = new BitrateLadder.Level[count];
        System.arraycopy(CLOUD_PHONE_LEVELS, 0, allowed, 0, count);
        ladder = new BitrateLadder(allowed, Math.min(1, count - 1));
    }

    public synchronized void observe(int bps, long nowMs) {
        // Keep observed headroom for hysteresis. Clamping observations to the operator
        // ceiling made its own top tier unreachable (e.g. 2M < 2M * 1.12).
        // Allowed tiers and budget() independently enforce the configured output ceiling.
        rawBps = Math.max(0, bps);
        if (rawBps == 0 || smoothedBps < 0 || lastSampleMs < 0 || nowMs < lastSampleMs) {
            smoothedBps = rawBps;
        } else {
            // One-second time constant. Behavior is independent of SetRates callback frequency.
            double alpha = 1 - Math.exp(-(nowMs - lastSampleMs) / 1000.0);
            smoothedBps += alpha * (rawBps - smoothedBps);
        }
        lastSampleMs = nowMs;
        ladder.update(rawBps, (int) smoothedBps, nowMs, true);
    }

    public synchronized boolean tick(long nowMs) {
        return rawBps > 0 && ladder.update(rawBps, (int) smoothedBps, nowMs, false);
    }

    public synchronized BitrateLadder.Level current() { return ladder.current(); }
    public synchronized int budget() { return Math.min(rawBps, maximumBps); }
    public synchronized void reset() {
        rawBps = -1;
        smoothedBps = -1;
        lastSampleMs = -1;
        ladder.resetFeedback();
    }
}
