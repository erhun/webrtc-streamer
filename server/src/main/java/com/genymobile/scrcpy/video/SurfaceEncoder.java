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

import android.annotation.SuppressLint;
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
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";

    private static final int[] MAX_SIZE_FALLBACK = {2560, 1920, 1600, 1280, 1024, 800, 640, 480, 320};
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    private final SurfaceCapture capture;
    private final PacketSink streamer;
    private final String encoderName;
    private final List<CodecOption> codecOptions;
    private int videoBitRate;
    private final int maxSize;
    private final float requestedMaxFps;
    private final boolean downsizeOnError;
    private final int minSizeAlignment;
    private final boolean ignoreVideoEncoderConstraints;

    private boolean firstFrameSent;
    private int consecutiveErrors;

    private Thread thread;
    private final AtomicBoolean stopped = new AtomicBoolean();

    private final CaptureControl captureControl = new CaptureControl();

    private final VideoAdaptationPolicy adaptation;
    private final Object feedbackLock = new Object();
    private int pendingBitrate = -1;
    private int latestBudgetBps = -1;
    private final VideoPipelineStats pipelineStats = new VideoPipelineStats();
    private boolean pendingKeyFrame;
    private boolean pendingPeerRefresh;
    private boolean pendingCaptureRefresh;
    private final KeyFrameRequestGate keyFrameRequests = new KeyFrameRequestGate();
    private final CaptureRefreshGate captureRefreshGate = new CaptureRefreshGate();
    private int adaptiveMaxSize;
    private boolean suspended;
    private boolean waitingForKeyFrame;

    // Configured FPS changes rebuild the codec so its surface-input limit is updated.
    private float encoderFps;

    private VideoConstraints videoConstraints;

    public SurfaceEncoder(SurfaceCapture capture, PacketSink streamer, Options options) {
        this.capture = capture;
        this.streamer = streamer;
        this.videoBitRate = options.getVideoBitRate();
        this.adaptation = new VideoAdaptationPolicy(videoBitRate);
        if (options.getSignalPort() > 0) {
            // Conservative bootstrap; the configured video bitrate becomes the explicit adaptive ceiling.
            this.videoBitRate = Math.min(videoBitRate, 500000);
            this.adaptiveMaxSize = 640;
        }
        this.maxSize = options.getMaxSize();
        this.requestedMaxFps = options.getMaxFps();
        this.encoderFps = requestedMaxFps;
        if (options.getSignalPort() > 0) {
            this.encoderFps = requestedMaxFps > 0 ? Math.min(requestedMaxFps, 15) : 15;
        }
        this.codecOptions = options.getVideoCodecOptions();
        this.encoderName = options.getVideoEncoder();
        this.downsizeOnError = options.getDownsizeOnError();
        this.minSizeAlignment = options.getMinSizeAlignment();
        this.ignoreVideoEncoderConstraints = options.getIgnoreVideoEncoderConstraints();
        capture.setPipelineStats(pipelineStats);
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

        videoConstraints = new VideoConstraints(maxSize, alignment, null, encoderFps > 0 ? encoderFps : 30);
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
                Ln.d("Capture configure: resetReasons=" + resetReasons + " bitrate=" + videoBitRate
                        + " maxSize=" + adaptiveMaxSize + " maxFps=" + encoderFps);
                capture.prepare();
                Size size = capture.getSize();
                MediaFormat format = createFormat(codec.getMimeType(), videoBitRate, encoderFps, codecOptions);
                if (!capture.controlsFrameRate() && !format.containsKey(KEY_MAX_FPS_TO_ENCODER)) {
                    // Direct capture: limit raw surface input, never discard encoded H.264 reference frames.
                    format.setFloat(KEY_MAX_FPS_TO_ENCODER, encoderFps > 0 ? encoderFps : 30);
                }

                format.setInteger(MediaFormat.KEY_WIDTH, size.getWidth());
                format.setInteger(MediaFormat.KEY_HEIGHT, size.getHeight());
                Ln.d("Video format: " + format);

                Surface surface = null;
                boolean mediaCodecStarted = false;
                try {
                    mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    surface = mediaCodec.createInputSurface();

                    capture.start(surface);

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

                    try {
                        // start() may have allocated a GL runner before failing. Stop partial captures too.
                        capture.stop();
                    } finally {
                        try {
                            if (mediaCodecStarted) {
                                try {
                                    mediaCodec.signalEndOfInputStream();
                                } catch (Exception e) {
                                    // Already ended or abandoned.
                                }
                                try {
                                    mediaCodec.stop();
                                } catch (IllegalStateException e) {
                                    // Already stopped.
                                }
                            }
                            if (!stopped.get()) {
                                mediaCodec.reset();
                            }
                        } finally {
                            if (surface != null) {
                                surface.release();
                            }
                        }
                    }
                }
            } while (alive);
        } finally {
            try {
                capture.release();
            } finally {
                mediaCodec.release();
            }
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

    @SuppressLint("DefaultLocale")
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

                    if (!isConfig) {
                        pipelineStats.onCodecFrame();
                    }

                    boolean bootstrap = !isConfig && captureRefreshGate.consumeBootstrapFrame(key);
                    if (bootstrap) {
                        Ln.d("Peer bootstrap: forwarding IDR to initialize native encoder");
                    }
                    if (isConfig || bootstrap || (!suspended && (!waitingForKeyFrame || key))) {
                        long handoffStartNs = System.nanoTime();
                        streamer.writePacket(codecBuffer, bufferInfo);
                        if (!isConfig) {
                            pipelineStats.onHandoff(System.nanoTime() - handoffStartNs);
                        }
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
            Ln.d("Using default video encoder: '" + mediaCodec.getName() + "'");
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
        // 使用 WebRTC 请求的帧率（不再限制到 20fps）
        int targetFps = (maxFps > 0) ? (int) maxFps : 30;
        Ln.d("createFormat: maxFps=" + maxFps + " -> targetFps=" + targetFps);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // Periodic GOP; PLI may request an earlier IDR.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL);

        // Requested bitrate mode; actual frame cadence is controlled before encoding.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
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
            pipelineStats.start();
            try {
                streamCapture();
            } catch (ConfigurationException e) {
                // Ignore logged
            } catch (IOException e) {
                if (!IO.isBrokenPipe(e)) {
                    Ln.e("Video encoding error", e);
                }
            } finally {
                pipelineStats.close();
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
        synchronized (feedbackLock) {
            adaptation.observe(bps, SystemClock.elapsedRealtime());
            pendingBitrate = adaptation.budget();
            latestBudgetBps = pendingBitrate;
            // Observe every feedback event, even if codec reset coalesces multiple callbacks.
            // Otherwise a short budget dip/zero can disappear before applyFeedback sees it.
        }
    }

    @Override
    public void onKeyFrameRequest() {
        synchronized (feedbackLock) { pendingKeyFrame = true; }
    }

    /** A newly negotiated peer needs fresh raw input even when the shared screen is static. */
    public void onPeerAttached() {
        synchronized (feedbackLock) { pendingCaptureRefresh = true; }
    }

    @Override
    public void onPeerReset() {
        synchronized (feedbackLock) {
            pendingPeerRefresh = true;
            pendingKeyFrame = false;
            adaptation.reset();
            latestBudgetBps = -1;
            pendingBitrate = -1;
        }
    }

    /** Apply cached network budgets on the encoder thread; cadence changes reset the codec once. */
    private void applyFeedback(MediaCodec codec) {
        if (captureControl.isResetRequested()) { return; }

        int bps;
        boolean keyFrame;
        boolean peerRefresh;
        boolean captureRefresh;
        BitrateLadder.Level level;
        synchronized (feedbackLock) {
            // Advance downgrade debounce with the cached allocation so congestion still resolves during SetRates
            // gaps, but a cached tick is not a fresh observation and must never commit an upgrade (freshFeedback=false).
            boolean tierChanged = adaptation.tick(SystemClock.elapsedRealtime());
            bps = pendingBitrate;
            if (bps < 0 && tierChanged) {
                bps = latestBudgetBps;
            }
            pendingBitrate = -1;
            keyFrame = pendingKeyFrame;
            pendingKeyFrame = false;
            peerRefresh = pendingPeerRefresh;
            pendingPeerRefresh = false;
            captureRefresh = pendingCaptureRefresh;
            pendingCaptureRefresh = false;
            level = adaptation.current();
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

        boolean needCodecReset = false;

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

                // Apply the latest encoder budget directly within this tier. An application ramp
                // driven only by SetRates can stall forever when the allocation stops changing.
                // Resolution/fps upgrades remain guarded by BitrateLadder; RTP pacing stays in WebRTC.
                int targetBitrate = Math.max(1, Math.min(bps, level.getBitRate()));
                float nextFps = requestedMaxFps > 0
                        ? Math.min(requestedMaxFps, level.getFps())
                        : level.getFps();
                int targetMaxSize = maxSize > 0 ? Math.min(maxSize, level.getMaxSize()) : level.getMaxSize();

                // Surface-input FPS limits are codec configuration, not a supported setParameters hot update.
                if (adaptiveMaxSize != targetMaxSize || Float.compare(encoderFps, nextFps) != 0) {
                    String reason = targetMaxSize > adaptiveMaxSize || nextFps > encoderFps ? "UPGRADE" : "DOWNGRADE";
                    int delta = Math.abs(targetMaxSize - adaptiveMaxSize);
                    Ln.i("Video tier changed: " + adaptiveMaxSize + " -> " + targetMaxSize
                            + ", reason=" + reason
                            + ", delta=" + delta
                            + ", bps=" + bps
                            + ", targetBitrate=" + targetBitrate
                            + ", fps=" + encoderFps + " -> " + nextFps
                            + ", triggering codec reset.");
                    adaptiveMaxSize = targetMaxSize;
                    encoderFps = nextFps;
                    videoBitRate = targetBitrate;
                    videoConstraints = videoConstraints.withMaxFps(nextFps);
                    needCodecReset = true;
                } else {
                    // Only bitrate changes use a codec hot update.
                    if (videoBitRate != targetBitrate) {
                        videoBitRate = targetBitrate;
                        Bundle params = new Bundle();
                        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, videoBitRate);
                        codec.setParameters(params);
                        streamer.reportVideoBitrate(videoBitRate);
                    }
                }
            }
        }

        // ============ 合并 reset 判断，避免重复 reset ============
        if (needCodecReset || firstFrameRefresh || bootstrapRefresh || captureRefresh) {
            captureControl.reset(CaptureControl.RESET_REASON_BITRATE_CHANGED);
            synchronized (feedbackLock) { pendingKeyFrame = true; }
            return;
        }

        // ============ 动态请求关键帧 (Sync Frame) ============
        if (keyFrame && !captureControl.isResetRequested()) {
            if (!keyFrameRequests.allow(SystemClock.elapsedRealtime())) {
                synchronized (feedbackLock) { pendingKeyFrame = true; }
                return;
            }
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(params);
        }
    }
}
