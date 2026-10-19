package com.genymobile.scrcpy.video;

/** Pure policy: network observations select a tier; only SurfaceEncoder may operate MediaCodec. */
public final class VideoAdaptationPolicy {
    public static final BitrateLadder.Level[] CLOUD_PHONE_LEVELS = {
        new BitrateLadder.Level(480, 10, 250000),
        new BitrateLadder.Level(640, 15, 500000),
        new BitrateLadder.Level(854, 24, 800000),
        new BitrateLadder.Level(960, 30, 1200000),
        new BitrateLadder.Level(1280, 30, 3000000),
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
        ladder = new BitrateLadder(allowed, initialLevel);
        // Ramp the encoder offer up from the initial tier's nominal rate, never past the operator ceiling.
        // Upgrades read the probe's validated capacity, not GoogCC's application-limited budget, so a
        // stably inflated estimate can no longer promote tiers the pipe cannot actually sustain.
        probe = new BitrateRampProbe(allowed[initialLevel].getBitRate(), maximumBps, new BitrateRampProbe.Config());
    }

    public synchronized void observe(int bps, long nowMs) {
        rawBps = Math.max(0, bps);
        probe.onBudget(rawBps, nowMs);
        // rawBps drives downgrades (congestion answered immediately); validatedBps gates upgrades.
        ladder.update(rawBps, probe.validatedBps(), nowMs, true);
    }

    public synchronized boolean tick(long nowMs) {
        return rawBps > 0 && ladder.update(rawBps, probe.validatedBps(), nowMs, false);
    }

    public synchronized BitrateLadder.Level current() { return ladder.current(); }
    /** Ramped encoder target: follows the probe's validated offer, bounded by the operator ceiling. -1 before any observation. */
    public synchronized int budget() {
        return rawBps < 0 ? -1 : Math.min(probe.probeBps(), maximumBps);
    }
    /** Compact one-line view of the control loop for on-device diagnosis: raw GoogCC budget vs the ramp probe vs the applied tier. */
    public synchronized String diagnostics() {
        return "raw=" + rawBps + " probe=" + probe.probeBps() + " validated=" + probe.validatedBps()
                + " tier=" + ladder.getCurrentLevelIndex() + "/" + ladder.current();
    }
    public synchronized void reset() {
        rawBps = -1;
        probe.reset();
        ladder.resetFeedback();
    }
}
