package com.genymobile.scrcpy.video;

/** Pure policy: network observations select a tier; only SurfaceEncoder may operate MediaCodec. */
public final class VideoAdaptationPolicy {
    public static final BitrateLadder.Level[] CLOUD_PHONE_LEVELS = {
        new BitrateLadder.Level(854, 20, 800000),
        new BitrateLadder.Level(960, 25, 1200000),
        new BitrateLadder.Level(1280, 30, 3000000),
    };
    private final BitrateLadder ladder;
    private final int maximumBps;
    private int rawBps = -1;

    public VideoAdaptationPolicy(int maximumBps) {
        if (maximumBps <= 0) { throw new IllegalArgumentException("Video bitrate must be positive"); }
        this.maximumBps = maximumBps;
        int count = 1; // Retain the survival tier even below its nominal quality budget.
        while (count < CLOUD_PHONE_LEVELS.length && CLOUD_PHONE_LEVELS[count].getBitRate() <= maximumBps) {
            count++;
        }
        BitrateLadder.Level[] allowed = new BitrateLadder.Level[count];
        System.arraycopy(CLOUD_PHONE_LEVELS, 0, allowed, 0, count);
        int initialLevel = Math.min(1, count - 1);
        BitrateLadder.Config config = new BitrateLadder.Config();
        config.maximumBps = maximumBps;
        ladder = new BitrateLadder(allowed, initialLevel, config);
    }

    public synchronized void observe(int bps, long nowMs) {
        rawBps = Math.max(0, bps);
        // GoogCC already controls the rate of increase and performs transport probes. A second Java
        // slow-start kept the encoder far below recovered allocations and blocked the tier gate.
        int allocatedBps = budget();
        ladder.update(allocatedBps, allocatedBps, nowMs, true);
    }

    public synchronized boolean tick(long nowMs) {
        // SetRates grants persist until superseded, paused or reset; they are not heartbeat samples.
        int allocatedBps = budget();
        return allocatedBps > 0 && ladder.update(allocatedBps, allocatedBps, nowMs, true);
    }

    public synchronized BitrateLadder.Level current() { return ladder.current(); }
    public synchronized float frameRate(float requestedMaxFps) {
        return requestedMaxFps > 0 ? Math.min(requestedMaxFps, ladder.current().getFps()) : ladder.current().getFps();
    }
    /** Encoder target bounded by both the current allocation and operator ceiling. -1 before feedback. */
    public synchronized int budget() {
        return rawBps < 0 ? -1 : Math.min(rawBps, maximumBps);
    }
    /** Network allocation is not a measured physical link capacity; tier changes remain debounced. */
    public synchronized String diagnostics() {
        return "raw=" + rawBps + " target=" + budget() + " upgradeBudget=" + budget()
                + " tier=" + ladder.getCurrentLevelIndex() + "/" + ladder.current();
    }
    public synchronized void reset() {
        rawBps = -1;
        ladder.resetFeedback();
    }
}
