package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.device.NativeEncoderBridge;
import com.genymobile.scrcpy.device.PacketSink;
import com.genymobile.scrcpy.model.Codec;
import com.genymobile.scrcpy.model.CodecOption;
import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.CodecUtils;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.LogUtils;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SurfaceEncoder implements AsyncProcessor, NativeEncoderBridge.Callback {

    private static final int DEFAULT_I_FRAME_INTERVAL = 10; // 10秒 GOP，依靠 RTC PLI 动态请求关键帧
    private static final int REPEAT_FRAME_DELAY_US = 1_000_000; // 静态画面 1 秒重复发帧保活
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";

    private static final int[] MAX_SIZE_FALLBACK = {2560, 1920, 1600, 1280, 1024, 800};
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    private final SurfaceCapture capture;
    private final PacketSink streamer;
    private final String encoderName;
    private final List<CodecOption> codecOptions;
    private int videoBitRate;
    private final int maxSize;
    private float maxFps;
    private final boolean downsizeOnError;
    private final int minSizeAlignment;
    private final boolean ignoreVideoEncoderConstraints;

    private boolean firstFrameSent;
    private int consecutiveErrors;

    private Thread thread;
    private final AtomicBoolean stopped = new AtomicBoolean();

    private final CaptureControl captureControl = new CaptureControl();

    private final BitrateLadder bitrateLadder = new BitrateLadder();
    private final Object feedbackLock = new Object();
    private int pendingBitrate = -1;
    private boolean pendingKeyFrame;
    private boolean pendingPeerRefresh;
    private final CaptureRefreshGate captureRefreshGate = new CaptureRefreshGate();
    private int adaptiveMaxSize;
    private final float requestedMaxFps;
    private boolean suspended;
    private boolean waitingForKeyFrame;

    private VideoConstraints videoConstraints;

    public SurfaceEncoder(SurfaceCapture capture, PacketSink streamer, Options options) {
        this.capture = capture;
        this.streamer = streamer;
        this.videoBitRate = options.getVideoBitRate();
        this.maxSize = options.getMaxSize();
        this.maxFps = options.getMaxFps();
        this.requestedMaxFps = maxFps;
        this.codecOptions = options.getVideoCodecOptions();
        this.encoderName = options.getVideoEncoder();
        this.downsizeOnError = options.getDownsizeOnError();
        this.minSizeAlignment = options.getMinSizeAlignment();
        this.ignoreVideoEncoderConstraints = options.getIgnoreVideoEncoderConstraints();
    }

    private void streamCapture() throws IOException, ConfigurationException {
        Codec codec = streamer.getCodec();
        MediaCodec mediaCodec = createMediaCodec(codec, encoderName);
        MediaCodecInfo.VideoCapabilities caps;
        int alignment;
        if (ignoreVideoEncoderConstraints) {
            caps = null;
            alignment = 1;
        } else {
            caps = mediaCodec.getCodecInfo().getCapabilitiesForType(codec.getMimeType()).getVideoCapabilities();
            assert caps != null;
            alignment = Math.max(caps.getWidthAlignment(), caps.getHeightAlignment());
            Ln.d("Video codec size alignment requirement: " + alignment + "px");
        }
        if (alignment < minSizeAlignment) {
            alignment = minSizeAlignment;
            Ln.d("Actual video size alignment: " + alignment + "px");
        }

        videoConstraints = new VideoConstraints(maxSize, alignment, null);
        capture.init(captureControl, videoConstraints);

        try {
            boolean alive;
            streamer.writeVideoHeader();
            int retainedResetReasons = 0;

            do {
                int resetReasons = captureControl.consumeReset();
                if ((resetReasons & CaptureControl.RESET_REASON_TERMINATED) != 0) {
                    break;
                }
                if (retainedResetReasons != 0) {
                    resetReasons |= retainedResetReasons;
                    retainedResetReasons = 0;
                }

                if (adaptiveMaxSize > 0) {
                    int limit = maxSize > 0 ? Math.min(maxSize, adaptiveMaxSize) : adaptiveMaxSize;
                    VideoConstraints next = videoConstraints.withMaxSize(limit);
                    if (!capture.applyNewVideoConstraints(next)) {
                        throw new IOException("Capture rejected adaptive size");
                    }
                    videoConstraints = next;
                }
                MediaFormat format = createFormat(codec.getMimeType(), videoBitRate, maxFps, codecOptions);
                Ln.d("Capture configure: resetReasons=" + resetReasons + " bitrate=" + videoBitRate
                        + " maxSize=" + adaptiveMaxSize + " maxFps=" + maxFps);
                capture.prepare();
                Size size = capture.getSize();

                format.setInteger(MediaFormat.KEY_WIDTH, size.getWidth());
                format.setInteger(MediaFormat.KEY_HEIGHT, size.getHeight());

                Surface surface = null;
                boolean mediaCodecStarted = false;
                boolean captureStarted = false;
                try {
                    mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    surface = mediaCodec.createInputSurface();

                    capture.start(surface);
                    captureStarted = true;

                    mediaCodec.start();
                    streamer.reportVideoBitrate(videoBitRate);
                    mediaCodecStarted = true;

                    captureControl.setRunningMediaCodec(mediaCodec);

                    if (stopped.get()) {
                        alive = false;
                    } else {
                        if (!captureControl.isResetRequested()) {
                            boolean isClientResize = (resetReasons & CaptureControl.RESET_REASON_CLIENT_RESIZED) != 0
                                    && (resetReasons & CaptureControl.RESET_REASON_DISPLAY_PROPERTIES_CHANGED) == 0;
                            streamer.writeSessionMeta(size.getWidth(), size.getHeight(), isClientResize);

                            encode(mediaCodec, streamer);
                        }
                        alive = !stopped.get() && !capture.isClosed();
                    }
                } catch (IllegalStateException | IllegalArgumentException | IOException e) {
                    if (IO.isBrokenPipe(e)) {
                        throw e;
                    }
                    Ln.e("Capture/encoding error: " + e.getClass().getName() + ": " + e.getMessage());
                    Ln.e(android.util.Log.getStackTraceString(e));
                    if (!prepareRetry(caps, size)) {
                        throw e;
                    }
                    retainedResetReasons = resetReasons;
                    alive = true;
                } finally {
                    captureControl.setRunningMediaCodec(null);

                    if (captureStarted) {
                        capture.stop();
                    }

                    if (captureStarted && mediaCodecStarted) {
                        SystemClock.sleep(50);
                    }

                    if (mediaCodecStarted) {
                        try {
                            mediaCodec.signalEndOfInputStream();
                        } catch (Exception e) {
                            // ignore
                        }
                        try {
                            mediaCodec.stop();
                        } catch (IllegalStateException e) {
                            // ignore
                        }
                    }
                    if (!stopped.get()) { mediaCodec.reset(); }
                    if (surface != null) {
                        surface.release();
                    }
                }
            } while (alive);
        } finally {
            capture.release();
            mediaCodec.release();
        }
    }

    private boolean prepareRetry(MediaCodecInfo.VideoCapabilities caps, Size currentSize) {
        if (firstFrameSent) {
            ++consecutiveErrors;
            if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                SystemClock.sleep(50);
                return true;
            }
        }

        if (!downsizeOnError) {
            return false;
        }

        if (caps != null && videoConstraints.getEncoderCapabilities() == null) {
            assert !ignoreVideoEncoderConstraints;
            Ln.i("Applying video encoder constraints");
            videoConstraints = videoConstraints.withCapabilities(caps);
            boolean accepted = capture.applyNewVideoConstraints(videoConstraints);
            if (accepted) {
                return true;
            }
        }

        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            return false;
        }

        int newMaxSize = chooseMaxSizeFallback(currentSize);
        if (newMaxSize == 0) {
            return false;
        }

        boolean accepted = capture.applyNewVideoConstraints(videoConstraints.withMaxSize(newMaxSize));
        if (!accepted) {
            return false;
        }

        Ln.i("Retrying with -m" + newMaxSize + "...");
        return true;
    }

    private static int chooseMaxSizeFallback(Size failedSize) {
        int currentMaxSize = Math.max(failedSize.getWidth(), failedSize.getHeight());
        for (int value : MAX_SIZE_FALLBACK) {
            if (value < currentMaxSize) {
                return value;
            }
        }
        return 0;
    }

    private void encode(MediaCodec codec, PacketSink streamer) throws IOException {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        boolean eos = false;
        boolean loggedFirstOutput = false;
        boolean loggedFirstKey = false;
        do {
            applyFeedback(codec);
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputBufferId < 0) {
                continue;
            }
            try {
                eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (bufferInfo.size > 0) {
                    boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!isConfig) {
                        firstFrameSent = true;
                        consecutiveErrors = 0;
                    }

                    ByteBuffer codecBuffer = codec.getOutputBuffer(outputBufferId);
                    boolean key = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (!isConfig && (!loggedFirstOutput || (key && !loggedFirstKey))) {
                        Ln.d("Codec output: key=" + key + " bytes=" + bufferInfo.size
                                + " suspended=" + suspended + " waitingForKeyFrame=" + waitingForKeyFrame);
                        loggedFirstOutput = true;
                        loggedFirstKey |= key;
                    }
                    boolean bootstrap = !isConfig && captureRefreshGate.consumeBootstrapFrame(key);
                    if (bootstrap) { Ln.d("Peer bootstrap: forwarding IDR to initialize native encoder"); }
                    if (isConfig || bootstrap || (!suspended && (!waitingForKeyFrame || key))) {
                        streamer.writePacket(codecBuffer, bufferInfo);
                        if (key) { waitingForKeyFrame = false; }
                    }
                }
            } finally {
                codec.releaseOutputBuffer(outputBufferId, false);
            }
        } while (!eos);
    }

    private static MediaCodec createMediaCodec(Codec codec, String encoderName) throws IOException, ConfigurationException {
        if (encoderName != null) {
            Ln.d("Creating encoder by name: '" + encoderName + "'");
            try {
                MediaCodec mediaCodec = MediaCodec.createByCodecName(encoderName);
                String mimeType = Codec.getMimeType(mediaCodec);
                if (!codec.getMimeType().equals(mimeType)) {
                    Ln.e("Video encoder type for \"" + encoderName + "\" (" + mimeType + ") does not match codec type (" + codec.getMimeType() + ")");
                    throw new ConfigurationException("Incorrect encoder type: " + encoderName);
                }
                return mediaCodec;
            } catch (IllegalArgumentException e) {
                Ln.e("Video encoder '" + encoderName + "' for " + codec.getName() + " not found\n" + LogUtils.buildVideoEncoderListMessage());
                throw new ConfigurationException("Unknown encoder: " + encoderName);
            } catch (IOException e) {
                Ln.e("Could not create video encoder '" + encoderName + "' for " + codec.getName() + "\n" + LogUtils.buildVideoEncoderListMessage());
                throw e;
            }
        }

        try {
            MediaCodec mediaCodec = MediaCodec.createEncoderByType(codec.getMimeType());
            Ln.d("Using video encoder: '" + mediaCodec.getName() + "'");
            return mediaCodec;
        } catch (IOException | IllegalArgumentException e) {
            Ln.e("Could not create default video encoder for " + codec.getName() + "\n" + LogUtils.buildVideoEncoderListMessage());
            throw e;
        }
    }

    private static MediaFormat createFormat(String videoMimeType, int bitRate, float maxFps, List<CodecOption> codecOptions) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, videoMimeType);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        int targetFps = (maxFps > 0) ? (int) maxFps : 60;
        format.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_24_ANDROID_7_0) {
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        }
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US);
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_23_ANDROID_6_0) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0); // 低延迟实时优先级
        }
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_26_ANDROID_8_0) {
            format.setInteger(MediaFormat.KEY_LATENCY, 1);   // 零延迟 pipeline 缓冲
        }
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        if (videoMimeType.equals(MediaFormat.MIMETYPE_VIDEO_AVC)) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        }
        if (maxFps > 0) {
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }

        if (codecOptions != null) {
            for (CodecOption option : codecOptions) {
                String key = option.getKey();
                Object value = option.getValue();
                CodecUtils.setCodecOption(format, key, value);
                Ln.d("Video codec option set: " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
            }
        }

        return format;
    }

    @Override
    public void start(TerminationListener listener) {
        thread = new Thread(() -> {
            Looper.prepare();
            try {
                streamCapture();
            } catch (ConfigurationException e) {
                // Ignore logged
            } catch (IOException e) {
                if (!IO.isBrokenPipe(e)) {
                    Ln.e("Video encoding error", e);
                }
            } finally {
                Ln.d("Screen streaming stopped");
                listener.onTerminated(true);
            }
        }, "video");
        thread.start();
    }

    @Override
    public void stop() {
        if (thread != null) {
            stopped.set(true);
            captureControl.reset(CaptureControl.RESET_REASON_TERMINATED);
        }
    }

    @Override
    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }

    @Override
    public void onBitrate(int bps, double fps) {
        synchronized (feedbackLock) { pendingBitrate = Math.max(0, bps); }
    }
    @Override
    public void onKeyFrameRequest() {
        synchronized (feedbackLock) { pendingKeyFrame = true; }
    }
    @Override
    public void onPeerReset() {
        synchronized (feedbackLock) {
            pendingPeerRefresh = true;
            pendingBitrate = -1;
            pendingKeyFrame = false;
        }
    }

    /**
     * 处理来自 WebRTC / 网络的拥塞控制与关键帧反馈 (在编码线程执行)
     */
    private void applyFeedback(MediaCodec codec) {
        if (captureControl.isResetRequested()) { return; }
        int bps;
        boolean keyFrame;
        boolean peerRefresh;
        synchronized (feedbackLock) {
            bps = pendingBitrate;
            pendingBitrate = -1;
            keyFrame = pendingKeyFrame;
            pendingKeyFrame = false;
            peerRefresh = pendingPeerRefresh;
            pendingPeerRefresh = false;
        }

        if (peerRefresh) {
            captureRefreshGate.newPeer();
            suspended = true;
            waitingForKeyFrame = true;
        }

        if (bps >= 0) { captureRefreshGate.onBitrate(bps); }

        boolean firstFrameRefresh = captureRefreshGate.consumeRefresh();
        boolean bootstrapRefresh = captureRefreshGate.consumeBootstrapRefresh(keyFrame);

        if (bootstrapRefresh) {
            waitingForKeyFrame = true;
            Ln.d("Peer bootstrap: refreshing capture before encoder rate feedback");
        }
        if (firstFrameRefresh) {
            waitingForKeyFrame = true;
            Ln.d("Peer first frame: positive send budget, refreshing capture");
        }

        boolean needResolutionReset = false;

        if (bps >= 0) {
            if (firstFrameRefresh || bps == 0) {
                Ln.d("Startup bitrate feedback: bps=" + bps);
            }

            if (bps == 0) {
                suspended = true;
                waitingForKeyFrame = true;
            } else {
                keyFrame |= suspended;
                suspended = false;

                bitrateLadder.update(bps);
                BitrateLadder.Level level = bitrateLadder.current();

                int targetBitrate = Math.max(1, Math.min(bps, level.getBitRate()));
                float nextFps = requestedMaxFps > 0 ? Math.min(requestedMaxFps, level.getFps()) : level.getFps();
                int targetMaxSize = level.getMaxSize();

                // 【核心优化点】判断是否需要真正的编码器冷重启 (仅当目标分辨率发生改变时)
                if (adaptiveMaxSize != targetMaxSize) {
                    Ln.i("Resolution tier changed: " + adaptiveMaxSize + " -> " + targetMaxSize + ", triggering codec reset.");
                    adaptiveMaxSize = targetMaxSize;
                    maxFps = nextFps;
                    videoBitRate = targetBitrate;
                    needResolutionReset = true;
                } else {
                    // 仅码率或帧率变化：无缝热更新，不重启编码器，彻底消除黑屏与卡顿！
                    if (videoBitRate != targetBitrate) {
                        videoBitRate = targetBitrate;
                        Bundle params = new Bundle();
                        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, videoBitRate);
                        codec.setParameters(params);
                        streamer.reportVideoBitrate(videoBitRate);
                    }
                    maxFps = nextFps; // 更新内存记录，Capture 采样层将平滑调帧
                }
            }
        }

        // 仅在明确需要修改采样分辨率或收到 Refresh 指令时，才执行耗时 Reset
        if (needResolutionReset || firstFrameRefresh || bootstrapRefresh) {
            captureControl.reset(CaptureControl.RESET_REASON_BITRATE_CHANGED);
            synchronized (feedbackLock) { pendingKeyFrame = true; }
            return;
        }

        // 动态请求关键帧 (Sync Frame)
        if (keyFrame && !captureControl.isResetRequested()) {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0); // 清理多余的 KEY_MIME
            codec.setParameters(params);
        }
    }
}