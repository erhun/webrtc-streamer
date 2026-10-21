import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.device.PacketSink;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.video.CaptureControl;
import com.genymobile.scrcpy.video.CaptureRefreshGate;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.VideoAdaptationPolicy;
import com.genymobile.scrcpy.video.VideoConstraints;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Actual feedback/configuration wiring with platform bindings; not an Android hardware test. */
public final class EncoderFeedbackTest {
    private EncoderFeedbackTest() {
    }

    private static final class Capture extends SurfaceCapture {
        @Override protected void init(VideoConstraints constraints) { }
        @Override public void release() { }
        @Override public void start(Surface surface) { }
        @Override public Size getSize() { return new Size(854, 480); }
        @Override public boolean isClosed() { return true; }
        @Override protected boolean applyNewVideoConstraints(VideoConstraints constraints) { return true; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    private static Field field(String name) throws Exception {
        Field field = SurfaceEncoder.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void apply(SurfaceEncoder encoder, MediaCodec codec, long now) throws Exception {
        SystemClock.now = now;
        Method method = SurfaceEncoder.class.getDeclaredMethod("applyFeedback", MediaCodec.class);
        method.setAccessible(true);
        method.invoke(encoder, codec);
    }

    /** True if applyFeedback ever fired PARAMETER_KEY_REQUEST_SYNC_FRAME on this (stub) codec. */
    private static boolean hasSyncRequest(MediaCodec codec) throws Exception {
        Field updates = MediaCodec.class.getField("updates");
        @SuppressWarnings("unchecked")
        java.util.List<Bundle> bundles = (java.util.List<Bundle>) updates.get(codec);
        for (Bundle bundle : bundles) {
            if (bundle.values.containsKey(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME)) {
                return true;
            }
        }
        return false;
    }

    private static SurfaceEncoder encoder(Options options, PacketSink sink) throws Exception {
        SurfaceEncoder encoder = new SurfaceEncoder(new Capture(), sink, options);
        field("videoConstraints").set(encoder, new VideoConstraints(options.maxSize, 2, null, options.fps));
        // Establish the already-connected state; bootstrap behavior is tested separately in StreamingTest.
        CaptureRefreshGate refresh = (CaptureRefreshGate) field("captureRefreshGate").get(encoder);
        refresh.onBitrate(500_000);
        refresh.consumeRefresh();
        return encoder;
    }

    public static void main(String[] args) throws Exception {
        Options fixed = new Options();
        fixed.adaptive = false;
        fixed.fps = 30;
        PacketSink sink = new PacketSink();
        SurfaceEncoder fixedEncoder = encoder(fixed, sink);
        CaptureControl fixedControl = (CaptureControl) field("captureControl").get(fixedEncoder);
        MediaCodec codec = new MediaCodec();
        SystemClock.now = 0;
        fixedEncoder.onBitrate(200_000, 30);
        apply(fixedEncoder, codec, 0);
        check(sink.bitrate == 200_000, "Low allocation must reach MediaCodec immediately");
        fixedEncoder.onBitrate(2_000_000, 30);
        apply(fixedEncoder, codec, 1);
        check(sink.bitrate == 2_000_000, "Recovered grant reaches the codec on its next poll, not after a second slow-start");
        for (long t = 3000; t <= 90_000; t += 1500) {
            apply(fixedEncoder, codec, t);
        }
        check(sink.bitrate == 2_000_000, "Stable allocation stays applied without further SetRates events");
        check(!fixedControl.isResetRequested(), "Fixed size/FPS mode never resets for adaptive tiers");
        check(field("adaptiveFps").getFloat(fixedEncoder) == 30, "Fixed 30fps remains fixed");
        fixedEncoder.onBitrate(0, 30);
        apply(fixedEncoder, codec, 91_000);
        check(field("suspended").getBoolean(fixedEncoder), "Zero pauses forwarding");
        fixedEncoder.onBitrate(1_000_000, 30);
        apply(fixedEncoder, codec, 92_000);
        check(!field("suspended").getBoolean(fixedEncoder) && sink.bitrate == 1_000_000, "Resume follows the current allocation");

        Options adaptive = new Options();
        PacketSink adaptiveSink = new PacketSink();
        SurfaceEncoder adaptiveEncoder = encoder(adaptive, adaptiveSink);
        CaptureControl control = (CaptureControl) field("captureControl").get(adaptiveEncoder);
        SystemClock.now = 0;
        adaptiveEncoder.onBitrate(8_000_000, 60);
        for (long t = 0; t <= 240_000; t += 1000) {
            apply(adaptiveEncoder, codec, t);
            control.consumeReset(); // Simulate completion of each requested codec reset.
        }
        // The ladder's top tier fps is the ceiling (read it, do not hardcode: the operator tier list changes).
        float topTierFps = VideoAdaptationPolicy.CLOUD_PHONE_LEVELS[VideoAdaptationPolicy.CLOUD_PHONE_LEVELS.length - 1].getFps();
        check(field("adaptiveFps").getFloat(adaptiveEncoder) == topTierFps, "Restored tier reaches SurfaceEncoder");
        MediaCodecInfo.maximumFps = 30;
        Method stream = SurfaceEncoder.class.getDeclaredMethod("streamCapture");
        stream.setAccessible(true);
        stream.invoke(adaptiveEncoder);
        check(MediaCodec.configuredFormat.values.get(MediaFormat.KEY_FRAME_RATE).equals(30), "Actual codec capability caps format FPS");
        adaptiveEncoder.onBitrate(8_000_000, 30);
        apply(adaptiveEncoder, codec, 241_000);
        check(!control.isResetRequested(), "A device limited to 30fps must not enter a 60fps reset loop");

        // Double-IDR regression: a restarted codec's own SPS/PPS+IDR supersedes a `suspended`-driven
        // sync request. WebRTC sends a bps=0 pause during the reconfigure (InitEncode on a resolution
        // change); the next positive grant must NOT fire PARAMETER_KEY_REQUEST_SYNC_FRAME inside the
        // post-reset grace window, or a SECOND IDR (~90KB burst) lands ~60ms after the reset's own and
        // collapses the GoogCC estimate right after the upgrade (the observed oscillation amplifier).
        Options grace = new Options();
        grace.adaptive = false;
        grace.fps = 30;
        PacketSink graceSink = new PacketSink();
        SurfaceEncoder graceEncoder = encoder(grace, graceSink);
        MediaCodec graceCodec = new MediaCodec();
        SystemClock.now = 0;
        graceEncoder.onBitrate(2_000_000, 30);
        apply(graceEncoder, graceCodec, 0);
        check(!hasSyncRequest(graceCodec), "A steady positive grant requests no sync frame");
        // The outer loop records codecStartedMs right after mediaCodec.start(); simulate that, then a pause.
        field("codecStartedMs").setLong(graceEncoder, 1000);
        graceEncoder.onBitrate(0, 30);
        apply(graceEncoder, graceCodec, 1000);
        check(field("suspended").getBoolean(graceEncoder), "A zero grant pauses forwarding");
        // Resume 50ms after the codec start: inside the grace window the redundant request is dropped.
        graceEncoder.onBitrate(2_000_000, 30);
        apply(graceEncoder, graceCodec, 1050);
        check(!field("suspended").getBoolean(graceEncoder), "Resume clears suspended");
        check(!hasSyncRequest(graceCodec), "The fresh codec's own IDR supersedes the resume sync request in the grace window");
        // A genuine pause/resume well outside the window still requests an IDR.
        graceEncoder.onBitrate(0, 30);
        apply(graceEncoder, graceCodec, 5000);
        graceEncoder.onBitrate(2_000_000, 30);
        apply(graceEncoder, graceCodec, 5050);
        check(hasSyncRequest(graceCodec), "A pause/resume outside the grace window still requests a keyframe");

        System.out.println("Encoder feedback passed (immediate grant application, fixed-mode isolation, zero/resume, 60fps, device cap/reset loop, post-reset double-IDR grace)");
    }
}
