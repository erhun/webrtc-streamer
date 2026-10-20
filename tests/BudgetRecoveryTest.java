import com.genymobile.scrcpy.video.VideoAdaptationPolicy;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

/** Allocation contract and logged-feedback replay, not a physical network capacity simulation. */
public final class BudgetRecoveryTest {
    private BudgetRecoveryTest() {
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    private static void replayLoggedBudgetWindow(String path) throws Exception {
        VideoAdaptationPolicy p = new VideoAdaptationPolicy(8_000_000);
        p.observe(30_000, 0);
        for (long t = 100; t <= 10_000; t += 100) { p.tick(t); }
        check(p.current().getMaxSize() == 480, "Begin the device trace in the observed survival tier");
        long tick = 10_100;
        int maximumSize = 480;
        int maximumTarget = 0;
        // Only the budget observations are retained. Unlogged callbacks and reactions to a changed
        // encoder output cannot be reconstructed; this is an open-loop policy regression.
        for (String line : Files.readAllLines(Paths.get(path))) {
            if (line.startsWith("#")) { continue; }
            String[] columns = line.split(" ");
            long time = 10_000 + Long.parseLong(columns[0]);
            int allocation = Integer.parseInt(columns[1]);
            while (tick < time) {
                p.tick(tick);
                maximumSize = Math.max(maximumSize, p.current().getMaxSize());
                maximumTarget = Math.max(maximumTarget, p.budget());
                tick += 100;
            }
            p.observe(allocation, time);
            tick = time + 100;
            maximumSize = Math.max(maximumSize, p.current().getMaxSize());
            maximumTarget = Math.max(maximumTarget, p.budget());
        }
        System.out.println("Logged allocation replay: maximum size=" + maximumSize + " target=" + maximumTarget + "bps");
        check(maximumSize > 480, "The logged multi-megabit windows must not be blocked by a second bitrate ramp");
    }

    private static void testAllocationAppliedWithoutSecondRamp() {
        VideoAdaptationPolicy p = new VideoAdaptationPolicy(8_000_000);
        check(p.budget() == -1, "No target before feedback");
        p.observe(49_000, 0);
        check(p.budget() == 49_000, "Low allocation");
        p.observe(2_892_000, 100);
        check(p.budget() == 2_892_000, "A new allocation must reach the codec without a second slow-start");
        p.observe(147_000, 101);
        check(p.budget() == 147_000, "Decreases remain immediate");
        p.observe(20_000_000, 102);
        check(p.budget() == 8_000_000, "Operator ceiling still applies");
        p.observe(0, 103);
        p.tick(20_000);
        check(p.budget() == 0, "Timer ticks cannot resume a pause");
        p.observe(1_000_000, 20_001);
        check(p.budget() == 1_000_000, "Resume follows the current grant, not a stale floor");
        p.reset();
        p.tick(30_000);
        check(p.budget() == -1, "Peer reset revokes allocation");
    }

    private static void testRateIncreaseDoesNotSkipTierDebounce() {
        VideoAdaptationPolicy p = new VideoAdaptationPolicy(8_000_000);
        p.observe(30_000, 0);
        p.tick(10_000);
        check(p.current().getMaxSize() == 480, "Sustained collapse reaches survival tier");
        p.observe(3_000_000, 11_000);
        p.tick(16_900);
        check(p.current().getMaxSize() == 480, "High codec target does not bypass the six-second tier window");
        p.observe(30_000, 16_950);
        p.observe(3_000_000, 17_000);
        p.tick(22_900);
        check(p.current().getMaxSize() == 480, "A short dip restarts the candidate");
        p.tick(23_000);
        check(p.current().getMaxSize() == 640, "Sustained grant promotes exactly one tier");
        p.tick(23_100);
        p.tick(29_100);
        check(p.current().getMaxSize() == 854, "A stable grant remains valid without SetRates heartbeats");
    }

    private static void testCeilingsFpsAndEpochs() {
        int[] ceilings = {250_000, 500_000, 800_000, 1_200_000, 2_500_000, 3_000_000, 4_000_000, 6_000_000};
        for (int ceiling : ceilings) {
            VideoAdaptationPolicy p = new VideoAdaptationPolicy(ceiling);
            p.observe(30_000, 0);
            p.tick(10_000);
            p.observe(ceiling, 11_000);
            for (long t = 12_000; t <= 180_000; t += 1000) { p.tick(t); }
            check(p.current().getBitRate() == ceiling, "Configured tier remains reachable at the operator limit");
            check(p.budget() == ceiling, "Target equals bounded allocation");
        }
        VideoAdaptationPolicy p = new VideoAdaptationPolicy(8_000_000);
        p.observe(8_000_000, 0);
        for (long t = 1000; t <= 180_000; t += 1000) { p.tick(t); }
        check(p.frameRate(0) == 60 && p.frameRate(60) == 60, "60fps remains available");
        check(p.frameRate(30) == 30 && p.frameRate(24) == 24, "User FPS limits remain authoritative");
        check(p.frameRate(120) == 60, "Policy maximum remains 60fps");
        p.observe(30_000, 181_000);
        p.tick(190_000);
        p.observe(8_000_000, 191_000);
        p.tick(194_000);
        p.tick(1);
        p.tick(5000);
        check(p.current().getMaxSize() == 480, "Clock rollback restarts the tier stability window");
        p.tick(6001);
        check(p.current().getMaxSize() == 640, "New clock epoch eventually recovers");
    }

    private static void testChangingAllocationsAlwaysBoundTarget() {
        Random random = new Random(0x60f05);
        VideoAdaptationPolicy p = new VideoAdaptationPolicy(8_000_000);
        int grant = 0;
        long now = 0;
        for (int i = 0; i < 20_000; i++) {
            now += random.nextInt(2000);
            if (i % 5 == 0) {
                grant = random.nextInt(12_000_001);
                p.observe(grant, now);
            } else { p.tick(now); }
            check(p.budget() == Math.min(grant, 8_000_000), "No overshoot or artificial ramp lag");
        }
    }

    public static void main(String[] args) throws Exception {
        replayLoggedBudgetWindow(args[0]);
        testAllocationAppliedWithoutSecondRamp();
        testRateIncreaseDoesNotSkipTierDebounce();
        testCeilingsFpsAndEpochs();
        testChangingAllocationsAlwaysBoundTarget();
        System.out.println("Budget recovery passed (logged trace, debounce, caps, pause, epochs, 20000 changing-budget steps)");
    }
}
