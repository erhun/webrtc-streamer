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
        /**
         * Congestion memory (TCP-Reno ssthresh). On a break the offer that broke is multiplied by this and
         * remembered as a soft ceiling the fast ramp will not cross again. Without it the probe re-ramps to
         * the operator ceiling every cycle and re-crosses real capacity at the same point, so GoogCC cliffs,
         * the probe severe-breaks, the tier downgrades, and the whole climb repeats -- the sawtooth observed
         * on a stable network (offer rocketed to ~4M at 960p, estimate cliffed to ~400k, rinse/repeat ~80s).
         * Halving matches multiplicative-decrease: the breaking offer overshot real capacity, so half of it
         * lands near what the pipe actually sustains and the loop converges instead of oscillating.
         */
        public double ssthreshFactor = 0.5;

        public Config() {}

        public Config copy() {
            Config c = new Config();
            c.stepBps = stepBps;
            c.stepFraction = stepFraction;
            c.stepHoldMs = stepHoldMs;
            c.breakFactor = breakFactor;
            c.severeBreakFactor = severeBreakFactor;
            c.ssthreshFactor = ssthreshFactor;
            return c;
        }
    }

    private final int maxBps;
    private final int floorBps;
    private final Config config;

    private int probeBps;
    private int validatedBps;
    private int windowPeakBps;
    private int ssthreshBps;
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
        this.ssthreshBps = maxBps; // No congestion memory yet: the first climb may slow-start to the ceiling.
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
            rememberCongestion(probeBps);
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
            // The pipe sustained the offer: it is validated capacity, so probe one bounded step higher to
            // discover headroom -- but never past ssthresh, the offer that last congested the pipe. Capping
            // here is what stops the sawtooth: without it the probe re-climbs to the operator ceiling every
            // cycle and re-crosses real capacity at the same point.
            validatedBps = confirmed;
            int step = Math.max(1, Math.min(config.stepBps, (int) (probeBps * config.stepFraction)));
            int ceiling = Math.min(maxBps, Math.max(ssthreshBps, probeBps));
            probeBps = Math.min(ceiling, probeBps + step);
        } else {
            // The pipe could not sustain the offer: the measured peak is the real capacity. Remember the
            // breaking offer as the new soft ceiling and back off to what the pipe actually delivered.
            rememberCongestion(probeBps);
            validatedBps = confirmed;
            probeBps = Math.max(1, confirmed);
        }
        // The boundary sample was already counted in this window's decision; start the next window empty
        // so a stale high peak cannot mask a collapse that begins right after the boundary.
        windowPeakBps = 0;
        holdSinceMs = nowMs;
    }

    /**
     * Record the offer that just congested the pipe; the fast ramp will not cross half of it again.
     *
     * <p>Only a break while genuinely stressing the pipe (offer above the survival floor) carries ceiling
     * information. During the application-limited startup/idle phase GoogCC's estimate sits at the ~30 kbps
     * WebRTC floor because static content produces few bits, so {@code raw < probe*severeBreakFactor} fires
     * repeatedly at a tiny offer -- that is ALR noise, not congestion. Remembering it would crater ssthresh to
     * the floor and permanently cap the probe below the tier-upgrade threshold (fps stuck at 10). Skip those:
     * the probe cannot exceed the floor while ALR-pinned anyway, so real headroom is still discovered once
     * motion drives the estimate up.
     */
    private void rememberCongestion(int brokeOfferBps) {
        if (brokeOfferBps <= floorBps) {
            return;
        }
        int halved = (int) (brokeOfferBps * config.ssthreshFactor);
        ssthreshBps = Math.max(floorBps, Math.min(maxBps, halved));
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
        ssthreshBps = maxBps; // A new peer/epoch gets a clean slow-start; congestion memory does not carry over.
        holdSinceMs = -1;
        lastMs = Long.MIN_VALUE;
    }

    /** Soft ceiling from congestion memory; the fast ramp holds here until a peer reset clears it. */
    public int ssthreshBps() {
        return ssthreshBps;
    }
}
