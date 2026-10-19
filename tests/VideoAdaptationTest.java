import com.genymobile.scrcpy.video.BitrateLadder;
import com.genymobile.scrcpy.video.VideoAdaptationPolicy;

public final class VideoAdaptationTest {
    private VideoAdaptationTest() {
        // Utility class.
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static BitrateLadder ladder(int initialLevel) {
        // Exercise the production tier table (single source of truth) with test-owned debounce timing,
        // so the algorithm is validated against the real ladder independently of production tuning.
        // Upgrade to L+1 needs held >= min(levels[L+1]*1.12, operator ceiling);
        // downgrade while raw < levels[L]*0.78.
        BitrateLadder.Config config = new BitrateLadder.Config();
        config.upgradeStableMs = 5000;
        config.downgradeStableMs = 3000;
        config.severeDowngradeStableMs = 1200;
        return new BitrateLadder(VideoAdaptationPolicy.CLOUD_PHONE_LEVELS, initialLevel, config);
    }

    private static void hold(BitrateLadder ladder, int bps, long startMs, long endMs) {
        for (long time = startMs; time <= endMs; time += 100) {
            check(!ladder.update(bps, time), "Unexpected tier change at " + time);
        }
    }

    private static void testSpikeCannotSkipTiers() {
        BitrateLadder ladder = ladder(0);
        hold(ladder, 2_300_000, 0, 4900);
        check(ladder.update(14_000_000, 5000), "The next tier should be ready");
        check(ladder.getCurrentLevelIndex() == 1, "A single spike must not authorize the highest tier");
        hold(ladder, 14_000_000, 5100, 10_000);
        check(ladder.update(14_000_000, 10_100), "Each following tier needs its own full window");
        check(ladder.getCurrentLevelIndex() == 2, "Upgrade must be exactly one tier");
    }

    private static void testRecoveryUsesFullWindow() {
        BitrateLadder ladder = ladder(7); // top tier (1920)
        // Resolution downgrade is debounced now that the encoder bitrate tracks GoogCC continuously
        // (no codec reset): a single severe dip must not rebuild the codec, but a sustained collapse
        // still drops multiple tiers at once after the shorter severe window.
        check(!ladder.update(100_000, 0), "A single severe dip must not instantly reset the codec");
        check(ladder.update(100_000, 1300), "Sustained severe collapse lowers the tier after debounce");
        check(ladder.getCurrentLevelIndex() == 0, "Severe downgrade may skip tiers");
        hold(ladder, 14_000_000, 1400, 6300);
        check(ladder.update(14_000_000, 6400), "Recovery requires the full upgrade window");
        check(ladder.getCurrentLevelIndex() == 1, "Recovery must not jump back to the old tier");
    }

    private static void testShortDipRestartsWindow() {
        BitrateLadder ladder = ladder(0);
        hold(ladder, 700_000, 0, 4900);
        check(!ladder.update(400_000, 4950), "A short budget dip cancels the pending upgrade");
        hold(ladder, 700_000, 4960, 9860);
        check(ladder.update(700_000, 9960), "A new uninterrupted window allows the upgrade");
    }

    private static void testPauseAndPeerReset() {
        BitrateLadder paused = ladder(0);
        hold(paused, 2_300_000, 0, 4900);
        check(!paused.update(0, 4950), "Pause should not change the current tier");
        hold(paused, 2_300_000, 4960, 9860);
        check(paused.update(2_300_000, 9960), "Zero budget must restart the full upgrade window");

        BitrateLadder peer = ladder(0);
        hold(peer, 2_300_000, 0, 4900);
        peer.resetFeedback();
        hold(peer, 2_300_000, 5000, 9900);
        check(peer.update(2_300_000, 10_000), "A new peer must establish its own stability window");
    }

    private static void testDowngradeHysteresisAndBounds() {
        BitrateLadder ladder = ladder(2); // 854 tier (800k); 500k sits in [390k,624k) -> single-step down to 640
        hold(ladder, 500_000, 0, 2900);
        check(ladder.update(500_000, 3000), "Ordinary downgrade must retain its debounce");
        check(ladder.getCurrentLevelIndex() == 1, "Ordinary downgrade must select the lower tier");
        hold(ladder, 100_000, 3100, 6000);
        check(ladder.update(100_000, 6100), "Lowest tier should be reached after debounce");
        hold(ladder, 1, 6200, 12_000);
        BitrateLadder top = ladder(7); // top tier must never upgrade past the ladder bound
        hold(top, Integer.MAX_VALUE, 0, 10_000);
    }

    private static void testSparseFeedbackCanUpgrade() {
        BitrateLadder ladder = ladder(0);
        check(!ladder.update(8_000_000, 0), "First positive budget starts a candidate");
        check(!ladder.update(8_000_000, 3000), "Sparse feedback is not yet five seconds of stability");
        check(ladder.update(8_000_000, 6000), "SetRates gaps must not permanently block recovery");
        check(ladder.getCurrentLevelIndex() == 1, "Sparse feedback must still only upgrade one tier");
        check(!ladder.update(8_000_000, 9000), "The next tier needs a new window");
        check(!ladder.update(8_000_000, 12_000), "No inherited stability from the previous tier");
        check(ladder.update(8_000_000, 15_000), "Recovery continues with change-driven feedback");
        check(ladder.getCurrentLevelIndex() == 2, "Second upgrade is also single-step");
    }

    private static void testSparsePauseAndClockRegression() {
        BitrateLadder ladder = ladder(0);
        check(!ladder.update(8_000_000, 0), "Begin candidate");
        check(!ladder.update(0, 4000), "Explicit pause clears a candidate despite sparse feedback");
        check(!ladder.update(8_000_000, 10_000), "Recovery starts a new window");
        check(!ladder.update(8_000_000, 13_000), "The old candidate must not survive a pause");
        check(ladder.update(8_000_000, 16_000), "The new candidate can upgrade");
        check(!ladder.update(8_000_000, 17_000), "Start a second candidate");
        check(!ladder.update(8_000_000, 1000), "Clock regression restarts observations");
        check(!ladder.update(8_000_000, 4000), "New clock epoch still needs the full stable window");
        check(ladder.update(8_000_000, 6000), "New clock epoch can eventually upgrade");
    }

    private static void testTransientCollapseAbsorbedByBitrate() {
        // Production tuning (default Config): the encoder bitrate tracks GoogCC continuously with no
        // codec reset, so a transient IDR-induced collapse must be absorbed by bitrate alone. Dropping
        // the resolution tier would rebuild the codec and re-trigger the death spiral, so the debounce
        // window is deliberately long. Only a sustained collapse may downgrade, and then in one step.
        BitrateLadder ladder = new BitrateLadder(VideoAdaptationPolicy.CLOUD_PHONE_LEVELS, 3); // 960 tier
        check(!ladder.update(100_000, 0), "A transient severe dip only starts a downgrade candidate");
        check(!ladder.update(100_000, 2000), "Two seconds of collapse is inside the severe debounce");
        check(!ladder.update(100_000, 4000), "Four seconds of collapse is still inside the severe window");
        check(ladder.getCurrentLevelIndex() == 3, "A recovering transient must not drop the tier");
        check(!ladder.update(1_200_000, 4500), "Budget recovery cancels the pending downgrade");
        check(ladder.getCurrentLevelIndex() == 3, "No codec reset happened during the transient collapse");

        // A genuine sustained collapse (longer than the severe window) drops straight to the sustainable
        // tier in a single step, so at most one codec reset is incurred.
        BitrateLadder collapsed = new BitrateLadder(VideoAdaptationPolicy.CLOUD_PHONE_LEVELS, 7); // top tier
        for (long t = 0; t <= 4500; t += 500) {
            check(!collapsed.update(100_000, t), "Inside the severe window the tier must hold");
        }
        check(collapsed.update(100_000, 5100), "A sustained collapse finally downgrades once");
        check(collapsed.getCurrentLevelIndex() == 0, "A sustained collapse drops to the sustainable tier in one step");
    }

    public static void main(String[] args) {
        testSpikeCannotSkipTiers();
        testRecoveryUsesFullWindow();
        testShortDipRestartsWindow();
        testPauseAndPeerReset();
        testDowngradeHysteresisAndBounds();
        testSparseFeedbackCanUpgrade();
        testSparsePauseAndClockRegression();
        testTransientCollapseAbsorbedByBitrate();
        System.out.println("Video adaptation regressions passed (8 scenarios)");
    }
}
