package com.genymobile.scrcpy.video;

/** Pure policy: network observations select a tier; only SurfaceEncoder may operate MediaCodec. */
public final class VideoAdaptationPolicy {
    public static final BitrateLadder.Level[] CLOUD_PHONE_LEVELS = {
        new BitrateLadder.Level(480, 10, 250000),
        new BitrateLadder.Level(640, 15, 500000),
        new BitrateLadder.Level(854, 24, 800000),
        new BitrateLadder.Level(960, 30, 1200000),
        new BitrateLadder.Level(1280, 30, 2500000),
        new BitrateLadder.Level(1280, 60, 3000000),
        new BitrateLadder.Level(1600, 60, 4000000),
        new BitrateLadder.Level(1920, 60, 6000000),
    };
    private final BitrateLadder ladder;
    private final BitrateRampProbe probe;
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
        // Smooth media increases within the allocation. Transport probing belongs to WebRTC.
        probe = new BitrateRampProbe(allowed[initialLevel].getBitRate(), maximumBps, new BitrateRampProbe.Config());
    }

    public synchronized void observe(int bps, long nowMs) {
        rawBps = Math.max(0, bps);
        probe.onBudget(rawBps, nowMs);
        ladder.update(rawBps, probe.heldBps(), nowMs, true);
    }

    public synchronized boolean tick(long nowMs) {
        probe.tick(nowMs);
        // SetRates grants persist until superseded, paused or reset; they are not heartbeat samples.
        return rawBps > 0 && ladder.update(rawBps, probe.heldBps(), nowMs, true);
    }

    public synchronized BitrateLadder.Level current() { return ladder.current(); }
    public synchronized float frameRate(float requestedMaxFps) {
        return requestedMaxFps > 0 ? Math.min(requestedMaxFps, ladder.current().getFps()) : ladder.current().getFps();
    }
    /** Encoder target bounded by both the current allocation and operator ceiling. -1 before feedback. */
    public synchronized int budget() {
        return rawBps < 0 ? -1 : Math.min(rawBps, Math.min(probe.targetBps(), maximumBps));
    }
    /** Held allocation is debounce state, not measured network capacity. */
    public synchronized String diagnostics() {
        return "raw=" + rawBps + " target=" + probe.targetBps() + " held=" + probe.heldBps()
                + " tier=" + ladder.getCurrentLevelIndex() + "/" + ladder.current();
    }
    public synchronized void reset() {
        rawBps = -1;
        probe.reset();
        ladder.resetFeedback();
    }
}
