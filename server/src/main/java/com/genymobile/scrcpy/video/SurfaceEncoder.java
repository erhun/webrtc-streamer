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
    private static final int REPEAT_FRAME_DELAY_US = 1_000_000; // 静态画面 1 秒重复发帧保活
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";

    private static final int[] MAX_SIZE_FALLBACK = {2560, 1920, 1600, 1280, 1024, 800};
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    /**
     * 硬件编码器优先列表。
     * 顺序：Qualcomm → Samsung → MediaTek → 通用 OMX 命名。
     * 若设备不存在对应编码器，createByCodecName 会抛异常，自动跳过。
     */
    private static final String[] PREFERRED_HW_ENCODERS = {
            "c2.qti.avc.encoder",
            "c2.exynos.avc.encoder",
            "c2.mtk.avc.encoder",
            "OMX.qcom.video.encoder.avc",
            "OMX.Exynos.avc.enc",
            "OMX.MTK.VIDEO.ENCODER.AVC",
    };

    /**
     * BitrateLadder 更新节流间隔，避免高频 onBitrate 回调打乱防抖计时。
     */
    private static final long LADDER_UPDATE_INTERVAL_MS = 100L;

    private final SurfaceCapture capture;
    private final PacketSink streamer;
    private final String encoderName;
    private final List<CodecOption> codecOptions;
    private int videoBitRate;
    private final int maxSize;
    private float maxFps;
    private final float requestedMaxFps;
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
    private boolean suspended;
    private boolean waitingForKeyFrame;

    /**
     * targetFps：逻辑目标帧率，可在热更新路径动态调整。
     * encoderFps：创建 MediaFormat 时使用的帧率，仅在编码器重建时更新。
     * 二者分离，避免热更新污染编码器创建参数。
     */
    private float targetFps;
    private float encoderFps;

    /**
     * BitrateLadder 节流计时器。
     */
    private long lastLadderUpdateMs = 0;

    private VideoConstraints videoConstraints;

    public SurfaceEncoder(SurfaceCapture capture, PacketSink streamer, Options options) {
        this.capture = capture;
        this.streamer = streamer;
        this.videoBitRate = options.getVideoBitRate();
        this.maxSize = options.getMaxSize();
        this.maxFps = options.getMaxFps();
        this.requestedMaxFps = maxFps;
        this.targetFps = maxFps;
        this.encoderFps = maxFps;
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
                MediaFormat format = createFormat(codec.getMimeType(), videoBitRate, encoderFps, codecOptions);
                Ln.d("Capture configure: resetReasons=" + resetReasons + " bitrate=" + videoBitRate
                        + " maxSize=" + adaptiveMaxSize + " maxFps=" + encoderFps);
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

    @SuppressLint("DefaultLocale")
    private void encode(MediaCodec codec, PacketSink streamer) throws IOException {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        // ============ 统计字段 ============
        long lastOutputNs = 0;
        long windowStartNs = System.nanoTime();
        int windowFrameCount = 0;
        long totalEncodeCostNs = 0;
        long maxEncodeCostNs = 0;
        long dequeueBlockTotalNs = 0;
        long dequeueBlockMaxNs = 0;
        int dequeueBlockCount = 0;
        int slowFrameCount = 0;  // 编码耗时 > 33ms 的帧数

        // PTS → 输入时刻（System.nanoTime 基准）
        final java.util.concurrent.ConcurrentHashMap<Long, Long> inputTimestampMap =
                new java.util.concurrent.ConcurrentHashMap<>();

        boolean eos = false;
        boolean loggedFirstOutput = false;
        boolean loggedFirstKey = false;

        do {
            applyFeedback(codec);

            long dequeueStartNs = System.nanoTime();
            int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000);
            long dequeueCostNs = System.nanoTime() - dequeueStartNs;

            if (outputBufferId < 0) {
                dequeueBlockTotalNs += dequeueCostNs;
                if (dequeueCostNs > dequeueBlockMaxNs) dequeueBlockMaxNs = dequeueCostNs;
                dequeueBlockCount++;
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

                    // ============ 编码耗时统计 ============
                    if (!isConfig) {
                        long nowNs = System.nanoTime();

                        // 1. 帧间隔
                        if (lastOutputNs > 0) {
                            long interFrameNs = nowNs - lastOutputNs;
                            if (interFrameNs > 40_000_000L) {
                                Ln.d("Encode slow: inter-frame=" + (interFrameNs / 1_000_000) + "ms");
                            }
                        }
                        lastOutputNs = nowNs;

                        // 2. input→output 真实延迟
                        Long inputNs = inputTimestampMap.remove(bufferInfo.presentationTimeUs);
                        long encodeCostNs = -1;
                        if (inputNs != null) {
                            encodeCostNs = nowNs - inputNs;
                            totalEncodeCostNs += encodeCostNs;
                            if (encodeCostNs > maxEncodeCostNs) maxEncodeCostNs = encodeCostNs;
                            if (encodeCostNs > 33_000_000L) slowFrameCount++;
                        }

                        // 3. 窗口统计
                        windowFrameCount++;
                        if (windowFrameCount >= 30) {
                            long windowDurationNs = nowNs - windowStartNs;
                            double fps = windowFrameCount * 1_000_000_000.0 / windowDurationNs;
                            long avgEncodeNs = totalEncodeCostNs / windowFrameCount;

                            Ln.d(String.format(
                                    "Encode stats: frames=%d duration=%dms fps=%.1f "
                                            + "avgEncode=%dms maxEncode=%dms slowFrames=%d "
                                            + "dequeueBlockAvg=%dms dequeueBlockMax=%dms",
                                    windowFrameCount,
                                    windowDurationNs / 1_000_000,
                                    fps,
                                    avgEncodeNs / 1_000_000,
                                    maxEncodeCostNs / 1_000_000,
                                    slowFrameCount,
                                    dequeueBlockCount > 0 ? (dequeueBlockTotalNs / dequeueBlockCount / 1_000_000) : 0,
                                    dequeueBlockMaxNs / 1_000_000));

                            windowStartNs = nowNs;
                            windowFrameCount = 0;
                            totalEncodeCostNs = 0;
                            maxEncodeCostNs = 0;
                            dequeueBlockTotalNs = 0;
                            dequeueBlockMaxNs = 0;
                            dequeueBlockCount = 0;
                            slowFrameCount = 0;
                        }
                    }

                    boolean bootstrap = !isConfig && captureRefreshGate.consumeBootstrapFrame(key);
                    if (bootstrap) {
                        Ln.d("Peer bootstrap: forwarding IDR to initialize native encoder");
                    }
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

        // 硬件编码器优先探测
        if (MediaFormat.MIMETYPE_VIDEO_AVC.equals(codec.getMimeType())) {
            for (String hw : PREFERRED_HW_ENCODERS) {
                MediaCodec mc = null;
                try {
                    mc = MediaCodec.createByCodecName(hw);
                    String mime = Codec.getMimeType(mc);
                    if (!codec.getMimeType().equals(mime)) {
                        mc.release();
                        continue;
                    }
                    MediaCodecInfo info = mc.getCodecInfo();
                    // 排除软编（名称含 "android" 或 ".sw."）
                    if (info != null
                            && !info.getName().contains("android")
                            && !info.getName().toLowerCase().contains(".sw.")) {
                        Ln.d("Using preferred HW encoder: '" + hw + "'");
                        return mc;
                    }
                    mc.release();
                } catch (Exception ignored) {
                    // 该硬件编码器不存在或不可用，继续尝试下一个
                    if (mc != null) {
                        try { mc.release(); } catch (Exception ignored2) { }
                    }
                }
            }
            Ln.d("No preferred HW encoder available, falling back to default");
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

        // SurfaceEncoder.createFormat
//        if (videoMimeType.equals(MediaFormat.MIMETYPE_VIDEO_AVC)) {
//            // 从 Baseline 改为 Main
//            format.setInteger(MediaFormat.KEY_PROFILE,
//                    MediaCodecInfo.CodecProfileLevel.AVCProfileMain);
//            // 明确 Level
//            format.setInteger(MediaFormat.KEY_LEVEL,
//                    MediaCodecInfo.CodecProfileLevel.AVCLevel31);
//        }

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
            pendingKeyFrame = false;
            // 不清空 pendingBitrate：保留最新带宽反馈，让 applyFeedback 基于最新 bps 重新决策
        }
    }

    /**
     * 处理来自 WebRTC / 网络的拥塞控制与关键帧反馈 (在编码线程执行)
     *
     * 修复要点：
     * 1. BitrateLadder 更新节流到 100ms，避免高频回调打乱防抖计时
     * 2. 帧率热更新同步到 Capture（VideoConstraints），不触发 codec reset
     * 3. targetFps 与 encoderFps 分离，避免热更新污染编码器创建参数
     * 4. 增强档位切换日志（bps / reason / delta）
     * 5. 合并 firstFrameRefresh / bootstrapRefresh 的重复 reset
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

                // ============ BitrateLadder 节流更新 ============
                long now = SystemClock.elapsedRealtime();
                if (now - lastLadderUpdateMs >= LADDER_UPDATE_INTERVAL_MS) {
                    bitrateLadder.update(bps, now);
                    lastLadderUpdateMs = now;
                }
                BitrateLadder.Level level = bitrateLadder.current();

                int targetBitrate = Math.max(1, Math.min(bps, level.getBitRate()));
                float nextFps = requestedMaxFps > 0
                        ? Math.min(requestedMaxFps, level.getFps())
                        : level.getFps();
                int targetMaxSize = level.getMaxSize();

                // ============ 分辨率变化：冷重启编码器 ============
                if (adaptiveMaxSize != targetMaxSize) {
                    String reason = targetMaxSize > adaptiveMaxSize ? "UPGRADE" : "DOWNGRADE";
                    int delta = Math.abs(targetMaxSize - adaptiveMaxSize);
                    Ln.i("Resolution tier changed: " + adaptiveMaxSize + " -> " + targetMaxSize
                            + ", reason=" + reason
                            + ", delta=" + delta
                            + ", bps=" + bps
                            + ", targetBitrate=" + targetBitrate
                            + ", targetFps=" + nextFps
                            + ", triggering codec reset.");
                    adaptiveMaxSize = targetMaxSize;
                    targetFps = nextFps;
                    encoderFps = nextFps;
                    maxFps = nextFps;
                    videoBitRate = targetBitrate;
                    needResolutionReset = true;
                } else {
                    // ============ 仅码率 / 帧率变化：热更新 ============
                    if (videoBitRate != targetBitrate) {
                        videoBitRate = targetBitrate;
                        Bundle params = new Bundle();
                        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, videoBitRate);
                        codec.setParameters(params);
                        streamer.reportVideoBitrate(videoBitRate);
                    }

                    // 帧率热更新：同步到 Capture 采样层，不重启编码器
                    if (Float.compare(targetFps, nextFps) != 0) {
                        targetFps = nextFps;
                        maxFps = nextFps;
                        VideoConstraints next = videoConstraints.withMaxFps(nextFps);
                        if (capture.applyNewVideoConstraints(next)) {
                            videoConstraints = next;
                            Ln.d("Dynamic maxFps updated: " + nextFps);
                        } else {
                            Ln.w("Capture rejected maxFps update: " + nextFps);
                        }
                    }
                }
            }
        }

        // ============ 合并 reset 判断，避免重复 reset ============
        if (needResolutionReset || firstFrameRefresh || bootstrapRefresh) {
            captureControl.reset(CaptureControl.RESET_REASON_BITRATE_CHANGED);
            synchronized (feedbackLock) { pendingKeyFrame = true; }
            return;
        }

        // ============ 动态请求关键帧 (Sync Frame) ============
        if (keyFrame && !captureControl.isResetRequested()) {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(params);
        }
    }
}