import com.genymobile.scrcpy.video.BitrateLadder;

public final class VideoAdaptationTest {
    private VideoAdaptationTest() {
        // Utility class.
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void hold(BitrateLadder ladder, int bps, long startMs, long endMs) {
        for (long time = startMs; time <= endMs; time += 100) {
            check(!ladder.update(bps, time), "Unexpected tier change at " + time);
        }
    }

    private static void testSpikeCannotSkipTiers() {
        BitrateLadder ladder = new BitrateLadder(BitrateLadder.DEFAULT_LEVELS, 0);
        hold(ladder, 2_300_000, 0, 4900);
        check(ladder.update(14_000_000, 5000), "The next tier should be ready");
        check(ladder.getCurrentLevelIndex() == 1, "A single spike must not authorize the highest tier");
        hold(ladder, 14_000_000, 5100, 10_000);
        check(ladder.update(14_000_000, 10_100), "Each following tier needs its own full window");
        check(ladder.getCurrentLevelIndex() == 2, "Upgrade must be exactly one tier");
    }

    private static void testRecoveryUsesFullWindow() {
        BitrateLadder ladder = new BitrateLadder(BitrateLadder.DEFAULT_LEVELS, 5);
        check(ladder.update(500_000, 0), "Severe congestion must immediately lower the tier");
        check(ladder.getCurrentLevelIndex() == 0, "Severe downgrade may skip tiers");
        hold(ladder, 14_000_000, 100, 5000);
        check(ladder.update(14_000_000, 5100), "Recovery requires five seconds, not two");
        check(ladder.getCurrentLevelIndex() == 1, "Recovery must not jump back to the old tier");
    }

    private static void testShortDipRestartsWindow() {
        BitrateLadder ladder = new BitrateLadder(BitrateLadder.DEFAULT_LEVELS, 0);
        hold(ladder, 2_300_000, 0, 4900);
        check(!ladder.update(2_000_000, 4950), "A short budget dip cancels the pending upgrade");
        hold(ladder, 2_300_000, 4960, 9860);
        check(ladder.update(2_300_000, 9960), "A new uninterrupted window allows the upgrade");
    }

    private static void testPauseAndPeerReset() {
        BitrateLadder paused = new BitrateLadder(BitrateLadder.DEFAULT_LEVELS, 0);
        hold(paused, 2_300_000, 0, 4900);
        check(!paused.update(0, 4950), "Pause should not change the current tier");
        hold(paused, 2_300_000, 4960, 9860);
        check(paused.update(2_300_000, 9960), "Zero budget must restart the full upgrade window");

        BitrateLadder peer = new BitrateLadder(BitrateLadder.DEFAULT_LEVELS, 0);
        hold(peer, 2_300_000, 0, 4900);
        peer.resetFeedback();
        hold(peer, 2_300_000, 5000, 9900);
        check(peer.update(2_300_000, 10_000), "A new peer must establish its own stability window");
    }

    private static void testDowngradeHysteresisAndBounds() {
        BitrateLadder ladder = new BitrateLadder(BitrateLadder.DEFAULT_LEVELS, 2);
        hold(ladder, 3_000_000, 0, 2900);
        check(ladder.update(3_000_000, 3000), "Ordinary downgrade must retain its debounce");
        check(ladder.getCurrentLevelIndex() == 1, "Ordinary downgrade must select the lower tier");
        hold(ladder, 100_000, 3100, 6000);
        check(ladder.update(100_000, 6100), "Lowest tier should be reached after debounce");
        hold(ladder, 1, 6200, 12_000);
        BitrateLadder top = new BitrateLadder(BitrateLadder.DEFAULT_LEVELS, 5);
        hold(top, Integer.MAX_VALUE, 0, 10_000);
    }

    private static void testSparseFeedbackCanUpgrade() {
        BitrateLadder ladder = new BitrateLadder(BitrateLadder.DEFAULT_LEVELS, 0);
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
        BitrateLadder ladder = new BitrateLadder(BitrateLadder.DEFAULT_LEVELS, 0);
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

    public static void main(String[] args) {
        testSpikeCannotSkipTiers();
        testRecoveryUsesFullWindow();
        testShortDipRestartsWindow();
        testPauseAndPeerReset();
        testDowngradeHysteresisAndBounds();
        testSparseFeedbackCanUpgrade();
        testSparsePauseAndClockRegression();
        System.out.println("Video adaptation regressions passed (7 scenarios)");
    }
}
