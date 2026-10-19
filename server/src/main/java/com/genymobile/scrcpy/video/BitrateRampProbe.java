package com.genymobile.scrcpy.video;

/**
 * Uses the encoder bitrate itself as an active probe to discover the pipe's real capacity instead of
 * trusting GoogCC's application-limited budget.
 *
 * <p>The passthrough sender is application-limited: GoogCC's estimate only reflects the load we actually
 * offer, so if the encoder offers little the estimate decays toward the WebRTC floor (observed pinned at
 * 30 kbps with the offer tracking it) and the link never gets stress-tested -- a low self-reinforcing loop.
 * Feeding the raw estimate straight into the encoder keeps it stuck there; feeding it straight into
 * {@link BitrateLadder} instead promoted tiers on a phantom inflated estimate (observed ~5.7M against a
 * real ~1.1M pipe) and then flooded the pipe at the top tier.
 *
 * <p>This probe breaks the loop by ramping the offered encoder bitrate ({@link #probeBps()}) up in bounded
 * steps toward the operator ceiling -- deliberately offering MORE than the current estimate so GoogCC has
 * load to measure -- and only reports a capacity the pipe actually delivered ({@link #validatedBps()}).
 * Each offered rate is held for a full window before deciding, because GoogCC's estimate lags an offer
 * increase: reacting to an instantaneous dip would snap the probe back down before the pipe is measured.
 * At the window boundary the peak budget seen is compared against the offer: if the pipe sustained it, ramp
 * one more step; if not, that peak is the real capacity, so back the offer off to it. Upgrades gated on
 * {@code validatedBps} therefore settle at the tier the link genuinely sustains.
 *
 * <p>Not thread-safe; {@link VideoAdaptationPolicy} owns the lock.
 */
public final class BitrateRampProbe {

    public static final class Config {
        /**
         * Absolute ceiling on a single ramp step (bps). Bounds the additive increase at high rates so a
         * step never shocks the encoder VBV / pacer with a burst that trips GoogCC's delay-based overuse.
         */
        public int stepBps = 250_000;
        /**
         * Proportional ramp step; the smaller of this fraction and {@link #stepBps} bounds each ramp. Kept
         * deliberately gentle (~15%/window): an aggressive multiplicative jump (the prior 0.5) drove the
         * encoder target up 50-100% every window, and each jump burst-queued the pacer -> delay overuse ->
         * the budget collapsed back to the floor -> severe-break -> re-climb, a sawtooth that never let the
         * estimate converge on a fast link (observed pinned ~250k on 100Mbps WiFi). A smooth offer lets the
         * delay controller stay quiet so the estimate can climb toward real capacity (AIMD: gentle additive
         * increase, snap-down on break).
         */
        public double stepFraction = 0.15;
        /** How long an offered rate is held (and measured) before ramping further or backing off. */
        public long stepHoldMs = 1500L;
        /**
         * The window's peak budget must reach {@code offered * breakFactor} for the offer to count as
         * sustained; below that the pipe broke and the offer backs off to the measured peak. Loose enough
         * to ride out GoogCC jitter, tight enough to catch a real collapse.
         */
        public double breakFactor = 0.85;
        /**
         * A budget below {@code offered * severeBreakFactor} is an unambiguous collapse (not jitter), so the
         * offer backs off immediately rather than waiting out the window -- otherwise a real weak-network
         * crash would keep being fed the full pre-collapse offer for up to {@link #stepHoldMs}.
         */
        public double severeBreakFactor = 0.5;

        public Config() {}

        public Config copy() {
            Config c = new Config();
            c.stepBps = stepBps;
            c.stepFraction = stepFraction;
            c.stepHoldMs = stepHoldMs;
            c.breakFactor = breakFactor;
            c.severeBreakFactor = severeBreakFactor;
            return c;
        }
    }

    private final int maxBps;
    private final int floorBps;
    private final Config config;

    private int probeBps;
    private int validatedBps;
    private int windowPeakBps;
    private long holdSinceMs = -1;
    private long lastMs = Long.MIN_VALUE;

    /**
     * @param floorBps starting offered rate; a fresh probe ramps up from here rather than trusting the budget.
     * @param maxBps   hard ceiling on the offered rate (the operator bitrate limit); the probe never ramps past it.
     */
    public BitrateRampProbe(int floorBps, int maxBps, Config config) {
        if (floorBps <= 0 || maxBps <= 0) {
            throw new IllegalArgumentException("Probe floor and ceiling must be positive");
        }
        if (config == null) {
            throw new IllegalArgumentException("Config must not be null");
        }
        this.floorBps = floorBps;
        this.maxBps = maxBps;
        this.config = config.copy();
        this.probeBps = Math.min(floorBps, maxBps);
        this.validatedBps = 0;
        this.windowPeakBps = 0;
    }

    /**
     * Feed a fresh GoogCC budget. Read {@link #probeBps()} (the encoder target) and {@link #validatedBps()}
     * (the upgrade gate) afterwards.
     */
    public void onBudget(int rawBps, long nowMs) {
        if (lastMs != Long.MIN_VALUE && nowMs < lastMs) {
            // Capture-clock rollback: start a new epoch, offering only the conservative floor.
            reset();
        }
        lastMs = nowMs;

        if (rawBps <= 0) {
            // Pause/zero allocation: collapse the offer so nothing is encoded until a real budget returns.
            probeBps = 0;
            validatedBps = 0;
            windowPeakBps = 0;
            holdSinceMs = nowMs;
            return;
        }

        if (holdSinceMs < 0) {
            holdSinceMs = nowMs;
            windowPeakBps = rawBps;
        }

        // Accumulate the best budget seen while this rate is offered. Do NOT react to a dip mid-window:
        // GoogCC's estimate lags an offer increase, so an instantaneous break would abort a valid climb.
        windowPeakBps = Math.max(windowPeakBps, rawBps);

        // Fast path for an unambiguous collapse: the budget fell below half the offer, well past jitter.
        // Require half a window since the last step so GoogCC's lag right after a ramp-up is not mistaken
        // for a collapse; a genuine crash persists past that grace and is caught here, not a full window later.
        if (nowMs - holdSinceMs >= config.stepHoldMs / 2 && rawBps < probeBps * config.severeBreakFactor) {
            probeBps = Math.max(1, Math.min(rawBps, maxBps));
            validatedBps = probeBps;
            windowPeakBps = 0;
            holdSinceMs = nowMs;
            return;
        }

        if (nowMs - holdSinceMs < config.stepHoldMs) {
            return;
        }

        // Window settled: judge the offered rate against the peak budget the pipe delivered this window.
        int confirmed = Math.min(probeBps, windowPeakBps);
        if (windowPeakBps >= probeBps * config.breakFactor) {
            // The pipe sustained the offer: it is validated capacity, so probe one bounded step higher
            // toward the operator ceiling to discover headroom.
            validatedBps = confirmed;
            int step = Math.max(1, Math.min(config.stepBps, (int) (probeBps * config.stepFraction)));
            probeBps = Math.min(maxBps, probeBps + step);
        } else {
            // The pipe could not sustain the offer: the measured peak is the real capacity. Back off to it.
            validatedBps = confirmed;
            probeBps = Math.max(1, confirmed);
        }
        // The boundary sample was already counted in this window's decision; start the next window empty
        // so a stale high peak cannot mask a collapse that begins right after the boundary.
        windowPeakBps = 0;
        holdSinceMs = nowMs;
    }

    /** Current offered encoder bitrate target; ramps up gradually, backs off to measured capacity on a break. */
    public int probeBps() {
        return probeBps;
    }

    /** Capacity the pipe actually delivered for the last held offer; the conservative signal for tier upgrades. */
    public int validatedBps() {
        return validatedBps;
    }

    /** Drop all validated state and return to the conservative floor (peer reset / clock rollback). */
    public void reset() {
        probeBps = Math.min(floorBps, maxBps);
        validatedBps = 0;
        windowPeakBps = 0;
        holdSinceMs = -1;
        lastMs = Long.MIN_VALUE;
    }
}
