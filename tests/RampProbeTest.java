import com.genymobile.scrcpy.video.BitrateRampProbe;
import com.genymobile.scrcpy.video.VideoAdaptationPolicy;

import java.util.Random;

/** Allocation/recovery invariants; these do not simulate or certify real network capacity. */
public final class RampProbeTest {
    private RampProbeTest() {
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static BitrateRampProbe ramp() {
        BitrateRampProbe.Config config = new BitrateRampProbe.Config();
        config.stepBps = 500_000;
        config.stepFraction = 1;
        config.stepHoldMs = 1000;
        return new BitrateRampProbe(500_000, 8_000_000, config);
    }

    private static void testBoundedGrowthAndImmediateReduction() {
        BitrateRampProbe p = ramp();
        p.onBudget(5_700_000, 0);
        check(p.targetBps() == 500_000 && p.heldBps() == 0, "Conservative startup");
        p.tick(999);
        check(p.targetBps() == 500_000, "Hold before increasing");
        p.tick(1000);
        check(p.targetBps() == 1_000_000 && p.heldBps() == 500_000, "One bounded increase");
        p.tick(100_000);
        check(p.targetBps() == 1_500_000, "A long gap never banks catch-up steps");
        p.onBudget(200_000, 100_001);
        check(p.targetBps() == 200_000 && p.heldBps() <= 200_000, "Drop immediately, not after a hold window");
        for (long t = 101_000; t <= 120_000; t += 1000) {
            p.tick(t);
            check(p.targetBps() <= 200_000, "Media must never probe above the allocation");
        }
    }

    private static void testPauseResumeAndEpoch() {
        BitrateRampProbe p = ramp();
        p.onBudget(8_000_000, 0);
        p.tick(1000);
        p.onBudget(0, 1500);
        p.tick(9000);
        check(p.targetBps() == 0 && p.heldBps() == 0, "Ticks cannot resume an explicit pause");
        p.onBudget(8_000_000, 10_000);
        check(p.targetBps() == 500_000, "Resume at startup rate, not one bit per second");
        p.tick(11_000);
        check(p.targetBps() == 1_000_000, "Resume ramp works");
        p.tick(1);
        check(p.targetBps() == 500_000 && p.heldBps() == 0, "Clock rollback restarts hold state");
        p.reset();
        p.tick(100_000);
        check(p.targetBps() == 0, "A reset revokes the old allocation");
        p.onBudget(100_000, 100_001);
        check(p.targetBps() == 100_000, "Resume is also capped by a small allocation");
    }

    private static void testRecoveredBudgetCannotRemainAtTenFps() {
        VideoAdaptationPolicy p = new VideoAdaptationPolicy(8_000_000);
        p.observe(8_000_000, 0);
        p.observe(8_000_000, 1500);
        p.observe(200_000, 2300);
        for (long t = 3300; t < 15_000; t += 1000) {
            p.tick(t);
        }
        check(p.current().getFps() == 10, "Sustained collapse reaches the survival tier");
        // One new grant, then no further SetRates events: the grant is valid until revoked.
        p.observe(8_000_000, 15_000);
        for (long t = 16_000; t <= 195_000; t += 1000) {
            p.tick(t);
            check(p.budget() <= 8_000_000, "Recovery remains bounded");
        }
        check(p.budget() == 8_000_000 && p.current().getFps() == 60,
                "No permanent ssthresh cap or callback-heartbeat deadlock");
        check(p.current().getMaxSize() == 1920, "Recovery reaches the top tier gradually");
    }

    private static void testOperatorCeilingIsReachable() {
        int[] ceilings = {250_000, 500_000, 800_000, 1_200_000, 2_500_000, 3_000_000, 4_000_000, 6_000_000};
        for (int ceiling : ceilings) {
            VideoAdaptationPolicy p = new VideoAdaptationPolicy(ceiling);
            p.observe(ceiling, 0);
            p.observe(100_000, 1000);
            for (long t = 2000; t <= 20_000; t += 1000) {
                p.tick(t);
            }
            p.observe(ceiling, 21_000);
            for (long t = 22_000; t <= 240_000; t += 1000) {
                p.tick(t);
                check(p.budget() <= ceiling, "Operator ceiling always bounds the encoder");
            }
            check(p.current().getBitRate() == ceiling, "Headroom margin must not make a permitted tier unreachable: " + ceiling);
        }
    }

    private static void testFpsLimitsAndPauseRevokeTicks() {
        VideoAdaptationPolicy p = new VideoAdaptationPolicy(8_000_000);
        check(p.budget() == -1, "No allocation before feedback");
        p.observe(8_000_000, 0);
        for (long t = 1000; t <= 240_000; t += 1000) {
            p.tick(t);
        }
        check(p.frameRate(0) == 60 && p.frameRate(60) == 60, "60fps is available");
        check(p.frameRate(30) == 30 && p.frameRate(24) == 24, "User caps remain authoritative");
        check(p.frameRate(120) == 60, "Policy capability ceiling is 60fps");
        p.observe(0, 241_000);
        for (long t = 242_000; t <= 300_000; t += 1000) {
            p.tick(t);
            check(p.budget() == 0, "An explicit pause cannot be overridden by timer ticks");
        }
        p.reset();
        p.tick(301_000);
        check(p.budget() == -1, "Peer reset invalidates allocation");
    }

    private static void testAllocationInvariantAcrossChangingNetwork() {
        Random random = new Random(0x60f05);
        VideoAdaptationPolicy p = new VideoAdaptationPolicy(8_000_000);
        int grant = 0;
        long now = 0;
        for (int i = 0; i < 20_000; i++) {
            now += random.nextInt(2000);
            if (i % 5 == 0) {
                grant = random.nextInt(12_000_001);
                p.observe(grant, now);
            } else {
                p.tick(now);
            }
            check(p.budget() >= 0 && p.budget() <= Math.min(grant, 8_000_000),
                    "Encoding must respect both the current allocation and the operator limit");
        }
    }

    public static void main(String[] args) {
        testBoundedGrowthAndImmediateReduction();
        testPauseResumeAndEpoch();
        testRecoveredBudgetCannotRemainAtTenFps();
        testOperatorCeilingIsReachable();
        testFpsLimitsAndPauseRevokeTicks();
        testAllocationInvariantAcrossChangingNetwork();
        System.out.println("Allocation ramp regressions passed (6 groups, including 20000 varying-budget steps)");
    }
}
