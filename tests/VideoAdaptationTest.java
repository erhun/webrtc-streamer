import com.genymobile.scrcpy.video.BitrateLadder;
import com.genymobile.scrcpy.video.BitrateRamp;

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

    private static void testPauseAndFeedbackGap() {
        BitrateLadder paused = new BitrateLadder(BitrateLadder.DEFAULT_LEVELS, 0);
        hold(paused, 2_300_000, 0, 4900);
        check(!paused.update(0, 4950), "Pause should not change the current tier");
        hold(paused, 2_300_000, 4960, 9860);
        check(paused.update(2_300_000, 9960), "Zero budget must restart the full upgrade window");

        BitrateLadder stale = new BitrateLadder(BitrateLadder.DEFAULT_LEVELS, 0);
        hold(stale, 2_300_000, 0, 4900);
        hold(stale, 2_300_000, 10_000, 14_900);
        check(stale.update(2_300_000, 15_000), "Missing feedback must not count as stable bandwidth");

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

    private static void testRampAndImmediateReduction() {
        BitrateRamp ramp = new BitrateRamp(2_000_000);
        check(ramp.update(4_000_000, 0) == 2_000_000, "An increased target must not step up immediately");
        check(ramp.update(4_000_000, 1000) == 2_500_000, "Increase should be limited to 25 percent per second");
        check(ramp.update(800_000, 1001) == 800_000, "Congestion must reduce the target immediately");
        check(ramp.update(900_000, 2001) == 900_000, "Ramp must never exceed the latest budget");
        ramp.pause();
        check(ramp.update(4_000_000, 30_000) == 900_000, "Pause must not accumulate growth credit");
        check(ramp.update(4_000_000, 60_000) == 1_125_000, "Missing updates must not permit an unlimited jump");
        check(ramp.update(4_000_000, 59_000) == 1_125_000, "A clock regression must not increase bitrate");
        ramp.pause();
        check(ramp.update(100_000, 61_000) == 100_000, "A lower resumed budget still applies immediately");
        check(ramp.update(1_000_000, 62_000) == 200_000, "Low bitrate recovery must make useful progress");
    }

    private static void testCombinedUpgradeDoesNotStepBitrate() {
        BitrateLadder ladder = new BitrateLadder(BitrateLadder.DEFAULT_LEVELS, 0);
        BitrateRamp ramp = new BitrateRamp(ladder.current().getBitRate());
        for (long time = 0; time <= 5000; time += 100) {
            int budget = time < 5000 ? 2_300_000 : 14_000_000;
            ladder.update(budget, time);
            int applied = ramp.update(Math.min(budget, ladder.current().getBitRate()), time);
            check(applied <= budget, "Applied target must obey the encoder budget");
            if (time == 5000) {
                check(ladder.getCurrentLevelIndex() == 1, "Spike should only select the next tier");
                check(applied > 1_200_000 && applied < 1_300_000, "The tier change must also ramp its bitrate");
            }
        }
        ladder.update(300_000, 5001);
        check(ramp.update(Math.min(300_000, ladder.current().getBitRate()), 5001) == 300_000,
                "Tier cooldown must never delay an urgent bitrate reduction");
    }

    public static void main(String[] args) {
        testSpikeCannotSkipTiers();
        testRecoveryUsesFullWindow();
        testShortDipRestartsWindow();
        testPauseAndFeedbackGap();
        testDowngradeHysteresisAndBounds();
        testRampAndImmediateReduction();
        testCombinedUpgradeDoesNotStepBitrate();
        System.out.println("Video adaptation regressions passed (7 scenarios)");
    }
}
