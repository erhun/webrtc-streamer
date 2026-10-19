package com.genymobile.scrcpy.video;

/**
 * Smooths encoder increases within WebRTC's allocation; it does not probe network capacity.
 * WebRTC owns transport-feedback-based probing and congestion memory. A stable allocation remains valid
 * until replaced or explicitly paused/reset, so encoder-thread ticks may advance the ramp without a
 * SetRates heartbeat. The held rate is an upgrade debounce input, not measured/acknowledged throughput.
 * Not thread-safe; VideoAdaptationPolicy owns the lock. The class name is retained for existing callers.
 */
public final class BitrateRampProbe {
    public static final class Config {
        public int stepBps = 250_000;
        public double stepFraction = 0.15;
        public long stepHoldMs = 1500L;

        public Config copy() {
            Config c = new Config();
            c.stepBps = stepBps;
            c.stepFraction = stepFraction;
            c.stepHoldMs = stepHoldMs;
            return c;
        }
    }

    private final int maxBps;
    private final int startBps;
    private final Config config;
    private int allocatedBps;
    private int targetBps;
    private int heldBps;
    private long stepSinceMs = -1;
    private long lastMs = Long.MIN_VALUE;

    public BitrateRampProbe(int startBps, int maxBps, Config config) {
        if (startBps <= 0 || maxBps <= 0) {
            throw new IllegalArgumentException("Starting rate and ceiling must be positive");
        }
        if (config == null || config.stepBps <= 0 || Double.isNaN(config.stepFraction)
                || config.stepFraction <= 0 || config.stepFraction > 1 || config.stepHoldMs <= 0) {
            throw new IllegalArgumentException("Invalid bitrate ramp configuration");
        }
        this.startBps = Math.min(startBps, maxBps);
        this.maxBps = maxBps;
        this.config = config.copy();
    }

    public void onBudget(int bps, long nowMs) {
        boolean restart = allocatedBps == 0 || (lastMs != Long.MIN_VALUE && nowMs < lastMs);
        allocatedBps = Math.max(0, Math.min(bps, maxBps));
        if (allocatedBps == 0) {
            targetBps = 0;
            heldBps = 0;
            stepSinceMs = nowMs;
        } else if (restart) {
            // Resume at a useful bounded rate, never the old zero + one-bit ramp.
            targetBps = Math.min(startBps, allocatedBps);
            heldBps = 0;
            stepSinceMs = nowMs;
        } else if (targetBps > allocatedBps) {
            // Congestion is answered immediately; only increases are smoothed.
            targetBps = allocatedBps;
            heldBps = Math.min(heldBps, targetBps);
            stepSinceMs = nowMs;
        }
        tick(nowMs);
    }

    public void tick(long nowMs) {
        if (lastMs != Long.MIN_VALUE && nowMs < lastMs) {
            targetBps = Math.min(startBps, allocatedBps);
            heldBps = 0;
            stepSinceMs = nowMs;
        }
        lastMs = nowMs;
        if (allocatedBps == 0 || nowMs - stepSinceMs < config.stepHoldMs) {
            return;
        }
        heldBps = targetBps;
        int step = Math.max(1, Math.min(config.stepBps, (int) (targetBps * config.stepFraction)));
        targetBps = (int) Math.min(allocatedBps, (long) targetBps + step);
        // No catch-up burst after a long scheduling gap.
        stepSinceMs = nowMs;
    }

    public int targetBps() { return targetBps; }
    public int heldBps() { return heldBps; }

    public void reset() {
        allocatedBps = 0;
        targetBps = 0;
        heldBps = 0;
        stepSinceMs = -1;
        lastMs = Long.MIN_VALUE;
    }
}
