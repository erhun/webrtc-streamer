import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.device.PacketSink;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.video.CaptureControl;
import com.genymobile.scrcpy.video.CaptureRefreshGate;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.VideoConstraints;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
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
        apply(fixedEncoder, codec, 1500);
        check(sink.bitrate > 200_000 && sink.bitrate <= 2_000_000, "Timer ramp reaches codec without more feedback");
        for (long t = 3000; t <= 90_000; t += 1500) {
            apply(fixedEncoder, codec, t);
        }
        check(sink.bitrate == 2_000_000, "Ramp eventually uses the granted allocation");
        check(!fixedControl.isResetRequested(), "Fixed size/FPS mode never resets for adaptive tiers");
        check(field("adaptiveFps").getFloat(fixedEncoder) == 30, "Fixed 30fps remains fixed");
        fixedEncoder.onBitrate(0, 30);
        apply(fixedEncoder, codec, 91_000);
        check(field("suspended").getBoolean(fixedEncoder), "Zero pauses forwarding");
        fixedEncoder.onBitrate(1_000_000, 30);
        apply(fixedEncoder, codec, 92_000);
        check(!field("suspended").getBoolean(fixedEncoder) && sink.bitrate == 500_000, "Resume has a useful bounded target");

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
        check(field("adaptiveFps").getFloat(adaptiveEncoder) == 60, "Restored tier reaches SurfaceEncoder");
        MediaCodecInfo.maximumFps = 30;
        Method stream = SurfaceEncoder.class.getDeclaredMethod("streamCapture");
        stream.setAccessible(true);
        stream.invoke(adaptiveEncoder);
        check(MediaCodec.configuredFormat.values.get(MediaFormat.KEY_FRAME_RATE).equals(30), "Actual codec capability caps format FPS");
        adaptiveEncoder.onBitrate(8_000_000, 30);
        apply(adaptiveEncoder, codec, 241_000);
        check(!control.isResetRequested(), "A device limited to 30fps must not enter a 60fps reset loop");
        System.out.println("Encoder feedback passed (timer application, fixed-mode isolation, zero/resume, 60fps, device cap/reset loop)");
    }
}
