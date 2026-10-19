import com.genymobile.scrcpy.video.BitrateRampProbe;
import com.genymobile.scrcpy.video.VideoAdaptationPolicy;

/**
 * The ramp probe replaces GoogCC's application-limited budget as the upgrade authority. These tests pin
 * the two properties that break the phantom-upgrade cascade: the offer rises one bounded step at a time
 * (never a jump to the budget), and a budget that drops below the offer snaps the probe back to reality.
 */
public final class RampProbeTest {
    private RampProbeTest() {
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static BitrateRampProbe.Config clean() {
        BitrateRampProbe.Config c = new BitrateRampProbe.Config();
        c.stepBps = 500_000;
        c.stepFraction = 1.0; // linear 500k steps keep the arithmetic obvious
        c.stepHoldMs = 1000L;
        c.breakFactor = 0.9;
        return c;
    }

    private static void testRampIsGradualNeverAJump() {
        BitrateRampProbe p = new BitrateRampProbe(500_000, 8_000_000, clean());
        p.onBudget(5_700_000, 0);
        check(p.probeBps() == 500_000, "No ramp before the first hold window elapses");
        check(p.validatedBps() == 0, "Nothing is validated until the first hold window settles");
        p.onBudget(5_700_000, 500);
        check(p.probeBps() == 500_000, "Still inside the hold window");
        p.onBudget(5_700_000, 1000);
        check(p.probeBps() == 1_000_000, "Exactly one bounded step per hold window");
        check(p.validatedBps() == 500_000, "Validation lags the offer by one window: it confirms the rate actually held");
        p.onBudget(5_700_000, 2000);
        check(p.probeBps() == 1_500_000, "Second bounded step");
        check(p.validatedBps() == 1_000_000, "The prior held offer is now validated");
        // A long idle must not bank catch-up steps: one fresh sample advances the offer by one step only.
        p.onBudget(5_700_000, 100_000);
        check(p.probeBps() == 2_000_000, "A long gap still advances one step, never a jump to the 5.7M budget");
        check(p.validatedBps() == 1_500_000, "Validation still only reflects confirmed held offers");
    }

    private static void testCeilingClamp() {
        BitrateRampProbe p = new BitrateRampProbe(500_000, 2_000_000, clean());
        for (long t = 0; t <= 20_000; t += 1000) {
            p.onBudget(9_000_000, t);
        }
        check(p.probeBps() == 2_000_000, "Sustained headroom ramps up to the operator ceiling");
        check(p.validatedBps() <= 2_000_000, "Validated capacity is bounded by the operator ceiling");
    }

    private static void testBreakSnapsToReality() {
        BitrateRampProbe p = new BitrateRampProbe(500_000, 8_000_000, clean());
        for (long t = 0; t <= 6000; t += 1000) {
            p.onBudget(5_700_000, t);
        }
        check(p.probeBps() > 2_000_000, "Climbed well above 2M under the phantom budget");
        p.onBudget(1_100_000, 7000); // budget collapses below offered * breakFactor
        check(p.probeBps() == 1_100_000, "A break snaps the offer down to the discovered capacity");
        check(p.validatedBps() == 1_100_000, "Validated capacity pins at the real ceiling, aborting the climb");
    }

    private static void testPauseAndClockRollback() {
        BitrateRampProbe p = new BitrateRampProbe(500_000, 8_000_000, clean());
        p.onBudget(5_700_000, 0);
        p.onBudget(0, 1000);
        check(p.probeBps() == 0 && p.validatedBps() == 0, "A zero allocation collapses the offer");

        BitrateRampProbe r = new BitrateRampProbe(500_000, 8_000_000, clean());
        r.onBudget(5_700_000, 5000);
        r.onBudget(5_700_000, 6000);
        check(r.probeBps() == 1_000_000, "Ramped once before the rollback");
        r.onBudget(5_700_000, 100);
        check(r.probeBps() == 500_000, "Clock rollback restarts the probe at the conservative floor");
    }

    private static void testPolicyRejectsPhantomOvershoot() {
        // The production failure: a stably inflated ~5.7M budget promoted six tiers in 44s, then uncapped
        // the encoder to the full phantom budget at the top tier and flooded the real ~1.1M pipe. Model the
        // closed loop: GoogCC reads a phantom 5.7M only while the encoder offer stays under real capacity;
        // once the ramped offer crosses it, the pipe congests and the budget collapses to reality.
        VideoAdaptationPolicy policy = new VideoAdaptationPolicy(8_000_000);
        int realCapacity = 1_100_000;
        long t = 0;
        for (int i = 0; i < 200; i++) {
            int offered = policy.budget();
            int observed = (offered > realCapacity) ? realCapacity : 5_700_000;
            policy.observe(observed, t);
            t += 1000;
        }
        check(policy.budget() <= realCapacity * 2,
                "Encoder offer settles near the real capacity, never at the 5.7M phantom");
        check(policy.current().getMaxSize() <= 960,
                "The tier climb aborts at the sustainable tier instead of cascading to the top");
    }

    private static void testProbeEscapesApplicationLimitedStall() {
        // The on-device failure: GoogCC is application-limited, so its estimate only reflects the offered
        // load -- an encoder offering little keeps the estimate pinned at the ~30 kbps floor and the tier
        // never leaves 480p/10fps. A probe capped at the current estimate can never escape. This probe must
        // offer ABOVE the estimate to discover headroom and settle at the real capacity.
        BitrateRampProbe p = new BitrateRampProbe(250_000, 8_000_000, clean());
        int realCapacity = 3_000_000;
        long t = 0;
        for (int i = 0; i < 60; i++) {
            int offered = p.probeBps();
            // Application-limited estimate tracks the offer until the offer exceeds real capacity.
            int observed = Math.min(realCapacity, (int) (offered * 1.1));
            p.onBudget(observed, t);
            t += 250; // sub-window sampling; clean() stepHoldMs = 1000
        }
        check(p.probeBps() > 1_000_000, "The probe climbs far above the pinned application-limited estimate");
        check(p.probeBps() <= realCapacity + 500_000, "The probe settles near the real capacity, not the ceiling");
        check(p.validatedBps() <= realCapacity, "Validated capacity never exceeds the real pipe");
    }

    public static void main(String[] args) {
        testRampIsGradualNeverAJump();
        testCeilingClamp();
        testBreakSnapsToReality();
        testPauseAndClockRollback();
        testPolicyRejectsPhantomOvershoot();
        testProbeEscapesApplicationLimitedStall();
        System.out.println("Ramp probe regressions passed (6 scenarios)");
    }
}
