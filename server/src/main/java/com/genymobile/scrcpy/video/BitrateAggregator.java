package com.genymobile.scrcpy.video;

import android.os.SystemClock;

import com.genymobile.scrcpy.device.NativeEncoderBridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BitrateAggregator {
    public enum Policy { MINIMUM, CONTROLLER }

    public interface Output {
        void onAggregatedBitrate(int bps, double fps);
        void onAggregatedKeyFrameRequest();
        /** 发送队列延迟过高，请求快速降档（不等 BWE 反馈）。 */
        default void onQueueDelayHigh(long nowMs) {}
        default void onAggregatedPeerReset() {}
    }

    /**
     * GoogCC reports roughly once per second. A disconnected peer (closed tab inside the 45s lease
     * recovery window, dead transport, frozen background tab) stops reporting and its last sample
     * otherwise stays in the MIN aggregation forever, dragging every surviving viewer down to the
     * dead peer's final estimate and starving tier upgrades. Samples older than this are excluded.
     */
    private static final long STALE_SAMPLE_MS = 3000L;

    private static final class Sample {
        final int bps;
        final double fps;
        final long observedAtMs;
        Sample(int bps, double fps) {
            this(bps, fps, SystemClock.elapsedRealtime());
        }
        Sample(int bps, double fps, long observedAtMs) {
            this.bps = bps;
            this.fps = fps;
            this.observedAtMs = observedAtMs;
        }
    }

    private final Map<NativeEncoderBridge, Sample> samples = new ConcurrentHashMap<>();
    private final Output output;
    private final Policy policy;
    private NativeEncoderBridge preferred;

    public BitrateAggregator(Output output) {
        this(output, Policy.MINIMUM);
    }

    public BitrateAggregator(Output output, Policy policy) {
        this.output = output;
        this.policy = policy;
    }

    public synchronized void setPreferredBridge(NativeEncoderBridge bridge) {
        preferred = bridge;
        emitBudget();
    }

    public synchronized void addBridge(NativeEncoderBridge bridge) {
        samples.put(bridge, new Sample(0, 0));
    }

    public synchronized void removeBridge(NativeEncoderBridge bridge) {
        samples.remove(bridge);
        if (!hasActivePeer()) {
            output.onAggregatedPeerReset();
        }
        emitBudget();
    }

    public synchronized void updateBitrate(NativeEncoderBridge source, int bps, double fps) {
        if (!samples.containsKey(source)) {
            return; // A late callback must not resurrect a disconnected peer's low allocation.
        }
        samples.put(source, new Sample(Math.max(0, bps), fps));
        emitBudget();
    }

    public synchronized void resetBridge(NativeEncoderBridge source) {
        if (!samples.containsKey(source)) {
            return;
        }
        samples.put(source, new Sample(0, 0));
        if (!hasActivePeer()) {
            output.onAggregatedPeerReset();
        }
        emitBudget();
    }

    private boolean hasActivePeer() {
        long nowMs = SystemClock.elapsedRealtime();
        for (Sample sample : samples.values()) {
            if (sample.bps > 0 && nowMs - sample.observedAtMs <= STALE_SAMPLE_MS) {
                return true;
            }
        }
        return false;
    }

    private void emitBudget() {
        Sample controller = preferred != null ? samples.get(preferred) : null;
        if (policy == Policy.CONTROLLER && controller != null) {
            output.onAggregatedBitrate(controller.bps, controller.fps);
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        int minBps = Integer.MAX_VALUE;
        double minFps = 0;
        for (Sample s : samples.values()) {
            if (s.bps > 0 && nowMs - s.observedAtMs <= STALE_SAMPLE_MS && s.bps < minBps) {
                minBps = s.bps;
                minFps = s.fps;
            }
        }
        if (minBps == Integer.MAX_VALUE) {
            minBps = 0;
            minFps = 0;
        }
        output.onAggregatedBitrate(minBps, minFps);
    }

    public void requestKeyFrame() {
        output.onAggregatedKeyFrameRequest();
    }

    /** 由发送侧/编码器侧检测到队列延迟过高时调用。 */
    public void notifyQueueDelayHigh(long nowMs) {
        output.onQueueDelayHigh(nowMs);
    }
}
