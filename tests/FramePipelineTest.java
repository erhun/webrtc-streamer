import com.genymobile.scrcpy.audio.ShellAudioWorkaround;
import com.genymobile.scrcpy.video.BitrateAggregator;
import com.genymobile.scrcpy.video.BitrateLadder;
import com.genymobile.scrcpy.video.FrameRateLimiter;
import com.genymobile.scrcpy.video.VideoAdaptationPolicy;
import com.genymobile.scrcpy.device.NativeEncoderBridge;

public final class FramePipelineTest {
    private FramePipelineTest() {
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static int sample(float maxFps, int inputFps, int seconds) {
        FrameRateLimiter limiter = new FrameRateLimiter();
        limiter.setMaxFps(maxFps);
        int frames = 0;
        for (int i = 0; i < inputFps * seconds; i++) {
            if (limiter.shouldRender(1_000_000_000L + Math.round(i * 1_000_000_000.0 / inputFps))) {
                frames++;
            }
        }
        return frames;
    }

    private static void testRawFrameCadence() {
        check(sample(24, 30, 10) == 240, "30 Hz source must produce 24, not 15, sampled frames per second");
        check(sample(30, 60, 10) == 300, "60 -> 30 cadence");
        check(sample(60, 30, 10) == 300, "Never invent new source frames");
        check(sample(0, 60, 10) == 600, "Unrestricted raw input");
        FrameRateLimiter limiter = new FrameRateLimiter();
        limiter.setMaxFps(24);
        check(limiter.shouldRender(1_000_000_000L), "First raw frame");
        check(!limiter.shouldRender(1_000_000_000L), "Repeated callback must not duplicate a texture frame");
        check(!limiter.shouldRender(1_033_333_333L), "Early frame is skipped");
        limiter.setMaxFps(60);
        check(limiter.shouldRender(1_050_000_000L), "FPS increase takes effect immediately");
        check(limiter.shouldRender(1_066_666_667L), "New higher cadence");
        check(limiter.shouldRender(100), "Capture clock rollback begins a new epoch");
        check(limiter.shouldRender(5_000_000_000L), "Long idle must not generate catch-up frames");
    }

    private static void testCachedBudgetRecovery() {
        BitrateLadder ladder = new BitrateLadder(VideoAdaptationPolicy.CLOUD_PHONE_LEVELS, 0);
        // One external allocation, followed by encoder-thread clock ticks re-feeding that same cached budget.
        check(!ladder.update(8_000_000, 8_000_000, 0, true), "Fresh allocation starts a candidate");
        check(!ladder.update(8_000_000, 8_000_000, 6000, false), "A cached tick must not complete the upgrade window");
        check(!ladder.update(8_000_000, 8_000_000, 30_000, false), "Prolonged silence never promotes a stale budget");
        check(ladder.update(8_000_000, 8_000_000, 30_100, true), "A fresh allocation spanning the window upgrades");
        check(ladder.getCurrentLevelIndex() == 1, "Upgrade is single-step");

        // Congestion must stay responsive even when it arrives on a cached (non-fresh) encoder tick.
        // Resolution downgrade is now debounced (the encoder bitrate already absorbed the congestion
        // with no codec reset), but cached ticks still advance the downgrade window, so a sustained
        // collapse commits without waiting for a fresh SetRates event. Pin the debounce independently of
        // production tuning so this stays a cached-tick test, not a timing-constants test.
        BitrateLadder.Config congestedConfig = new BitrateLadder.Config();
        congestedConfig.severeDowngradeStableMs = 1200;
        BitrateLadder congested = new BitrateLadder(VideoAdaptationPolicy.CLOUD_PHONE_LEVELS, 3, congestedConfig);
        check(!congested.update(100_000, 100_000, 5000, false), "A single cached congestion tick must not reset the codec");
        check(congested.update(100_000, 100_000, 6300, false), "Sustained severe congestion downgrades on cached ticks alone");
        check(congested.getCurrentLevelIndex() == 0, "Severe congestion reaches the lowest tier");

        BitrateLadder paused = new BitrateLadder(VideoAdaptationPolicy.CLOUD_PHONE_LEVELS, 0);
        paused.update(8_000_000, 0);
        paused.update(0, 5000);
        check(!paused.update(8_000_000, 6000), "Raw zero cancels the old candidate");
        check(paused.update(8_000_000, 12000), "Resume needs its own complete window");
    }

    private static void testSmoothedSpikeDoesNotUpgrade() {
        // Reproduces the production limit cycle: WebRTC BWE swings as the encoder loads the pipe.
        // Upgrades read the EMA-smoothed budget, so a raw probe spike the smoothing has not confirmed cannot promote.
        BitrateLadder ladder = new BitrateLadder(VideoAdaptationPolicy.CLOUD_PHONE_LEVELS, 1); // 640 tier; upgrade needs smoothed > 896k
        for (long t = 0; t <= 12_000; t += 1000) {
            check(!ladder.update(6_800_000, 700_000, t, true), "A damped smoothed budget must not promote a tier");
        }
        check(ladder.getCurrentLevelIndex() == 1, "Raw spike alone cannot upgrade while the smoothed budget stays low");
        // A genuinely sustained high smoothed budget across fresh events still upgrades one step.
        check(!ladder.update(6_800_000, 6_800_000, 13_000, true), "First confirmed high sample starts a new window");
        check(ladder.update(6_800_000, 6_800_000, 19_000, true), "Sustained high smoothed budget upgrades after the window");
        check(ladder.getCurrentLevelIndex() == 2, "Upgrade is single-step");
    }

    private static void testPeerBudgetLifetime() {
        int[] output = {-1, 0};
        BitrateAggregator aggregator = new BitrateAggregator(new BitrateAggregator.Output() {
            @Override
            public void onAggregatedBitrate(int bps, double fps) {
                output[0] = bps;
            }
            @Override
            public void onAggregatedKeyFrameRequest() {
            }
            @Override
            public void onAggregatedPeerReset() {
                output[1]++;
            }
        });
        NativeEncoderBridge weak = new NativeEncoderBridge();
        NativeEncoderBridge strong = new NativeEncoderBridge();
        aggregator.addBridge(weak);
        aggregator.addBridge(strong);
        aggregator.updateBitrate(weak, 500_000, 15);
        aggregator.updateBitrate(strong, 8_000_000, 30);
        check(output[0] == 500_000, "Shared encoder respects the active weakest peer");
        aggregator.removeBridge(weak);
        check(output[0] == 8_000_000, "Removing a peer recomputes the budget immediately");
        aggregator.updateBitrate(weak, 100_000, 15);
        check(output[0] == 8_000_000, "Late callback cannot resurrect a removed peer");
        aggregator.resetBridge(strong);
        check(output[0] == 0 && output[1] == 1, "Last peer reset clears cached allocation and bootstrap state");
        aggregator.updateBitrate(strong, 3_000_000, 30);
        check(output[0] == 3_000_000, "Reset peer remains registered for new allocation");
        aggregator.removeBridge(strong);
        check(output[0] == 0 && output[1] == 2, "Empty room clears allocation and reopens IDR bootstrap for the next peer");
    }

    private static void testAudioEntryPoints() {
        check(ShellAudioWorkaround.isRequired(30, 2000, false), "Android 11 adb shell keeps its foreground workaround");
        check(!ShellAudioWorkaround.isRequired(30, 1000, true), "System APK never force-stops itself");
        check(!ShellAudioWorkaround.isRequired(30, 2000, true), "APK identity takes priority over UID");
        check(!ShellAudioWorkaround.isRequired(30, 0, false), "Root does not need the shell foreground workaround");
        check(!ShellAudioWorkaround.isRequired(31, 2000, false), "Android 12 skips Android 11 workaround");
        check("com.android.shell".equals(ShellAudioWorkaround.PACKAGE_NAME), "Cleanup package is fixed to shell");
    }

    public static void main(String[] args) {
        testRawFrameCadence();
        testCachedBudgetRecovery();
        testSmoothedSpikeDoesNotUpgrade();
        testPeerBudgetLifetime();
        testAudioEntryPoints();
        System.out.println("Frame pipeline regressions passed (5 scenario groups)");
    }
}
