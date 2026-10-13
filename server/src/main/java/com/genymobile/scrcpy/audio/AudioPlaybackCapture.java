package com.genymobile.scrcpy.audio;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.RuntimeContext;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.IBinder;
import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public final class AudioPlaybackCapture implements AudioCapture {

    private static final int SAMPLE_RATE = AudioConfig.SAMPLE_RATE;
    private static final int CHANNEL_CONFIG = AudioConfig.CHANNEL_CONFIG;
    private static final int CHANNELS = AudioConfig.CHANNELS;
    private static final int ENCODING = AudioConfig.ENCODING;
    // Hidden MediaProjectionManager.TYPE_PRESENTATION, retained for the existing audio-only path.
    private static final int TYPE_PRESENTATION = 2;

    private AudioRecord recorder;
    private AudioRecordReader reader;
    private MediaProjection mediaProjection;
    private final Context applicationContext;

    public AudioPlaybackCapture() {
        this(null);
    }

    public AudioPlaybackCapture(Context context) {
        if (context != null) {
            RuntimeContext.initialize(context);
        }
        RuntimeContext.get(); // Reject an application UID without a real Context before requesting MediaProjection.
        applicationContext = RuntimeContext.getApplicationContext();
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private MediaProjection createMediaProjection() throws AudioCaptureException {
        Context context = applicationContext != null ? applicationContext : RuntimeContext.get();
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getDeclaredMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "media_projection");
            if (binder == null) {
                Ln.e("media_projection service not found");
                throw new AudioCaptureException();
            }

            Class<?> iMgrStub = Class.forName("android.media.projection.IMediaProjectionManager$Stub");
            Method asInterfaceMgr = iMgrStub.getMethod("asInterface", IBinder.class);
            Object iManager = asInterfaceMgr.invoke(null, binder);

            // createProjection(int uid, String packageName, int type, boolean isSecure)
            // Returns IMediaProjection (not IBinder)
            // TYPE_PRESENTATION is the existing audio-only projection type; it is not TYPE_SCREEN_RECORDING.
            Method createProjection = iManager.getClass().getMethod("createProjection",
                    int.class, String.class, int.class, boolean.class);
            Object iProjection = createProjection.invoke(iManager,
                    android.os.Process.myUid(), context.getPackageName(), TYPE_PRESENTATION, false);
            if (iProjection == null) {
                Ln.e("createProjection() returned null");
                throw new AudioCaptureException();
            }

            // new MediaProjection(Context, IMediaProjection)
            Class<?> iProjInterface = Class.forName("android.media.projection.IMediaProjection");
            Constructor<?> mpCtor = MediaProjection.class.getDeclaredConstructor(
                    android.content.Context.class, iProjInterface);
            mpCtor.setAccessible(true);
            return (MediaProjection) mpCtor.newInstance(context, iProjection);
        } catch (AudioCaptureException e) {
            throw e;
        } catch (Exception e) {
            Ln.e("Failed to create MediaProjection", e);
            throw new AudioCaptureException();
        }
    }

    @SuppressLint("MissingPermission")
    private AudioRecord createAudioRecord(MediaProjection mp) {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build();

        AudioPlaybackCaptureConfiguration playbackConfig =
                new AudioPlaybackCaptureConfiguration.Builder(mp)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .build();

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
        int bufferSize = minBufferSize > 0 ? 8 * minBufferSize : 1 << 17;

        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .setBufferSizeInBytes(bufferSize);
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
            builder.setContext(applicationContext != null ? applicationContext : RuntimeContext.get());
        }
        return builder.build();
    }

    @Override
    public void checkCompatibility() throws AudioCaptureException {
        if (Build.VERSION.SDK_INT < AndroidVersions.API_30_ANDROID_11) {
            Ln.w("Audio disabled: AudioPlaybackCapture requires Android 11+");
            throw new AudioCaptureException();
        }
    }

    @Override
    public void start() throws AudioCaptureException {
        Context context = applicationContext != null ? applicationContext : RuntimeContext.get();
        Ln.i("AudioPlaybackCapture starting: package=" + context.getOpPackageName() + " uid=" + android.os.Process.myUid()
                + " projectionType=" + TYPE_PRESENTATION);
        try {
            mediaProjection = createMediaProjection();
            recorder = createAudioRecord(mediaProjection);
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Ln.e("AudioPlaybackCapture did not enter recording state");
                throw new AudioCaptureException();
            }
            reader = new AudioRecordReader(recorder);
            Ln.i("AudioPlaybackCapture started: session=" + recorder.getAudioSessionId() + " sampleRate=" + recorder.getSampleRate()
                    + " channels=" + recorder.getChannelCount() + " state=" + recorder.getRecordingState());
        } catch (AudioCaptureException | RuntimeException e) {
            stop();
            throw e;
        }
    }

    @Override
    public void stop() {
        AudioRecord record = recorder;
        recorder = null;
        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        try {
            if (record != null) {
                record.release();
            }
        } finally {
            if (projection != null) {
                projection.stop();
            }
        }
    }

    @Override
    @TargetApi(AndroidVersions.API_24_ANDROID_7_0)
    public int read(ByteBuffer outDirectBuffer, MediaCodec.BufferInfo outBufferInfo) {
        return reader.read(outDirectBuffer, outBufferInfo);
    }
}
