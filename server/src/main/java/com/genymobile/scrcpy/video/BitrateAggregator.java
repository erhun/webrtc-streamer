package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.device.NativeEncoderBridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BitrateAggregator {

    public interface Output {
        void onAggregatedBitrate(int bps, double fps);
        void onAggregatedKeyFrameRequest();
        /** 发送队列延迟过高，请求快速降档（不等 BWE 反馈）。 */
        default void onQueueDelayHigh(long nowMs) {}
        default void onAggregatedPeerReset() {}
    }

    private static final class Sample {
        final int bps;
        final double fps;
        Sample(int bps, double fps) {
            this.bps = bps;
            this.fps = fps;
        }
    }

    private final Map<NativeEncoderBridge, Sample> samples = new ConcurrentHashMap<>();
    private final Output output;

    public BitrateAggregator(Output output) {
        this.output = output;
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
        for (Sample sample : samples.values()) {
            if (sample.bps > 0) {
                return true;
            }
        }
        return false;
    }

    private void emitBudget() {
        int minBps = Integer.MAX_VALUE;
        double minFps = 0;
        for (Sample s : samples.values()) {
            if (s.bps > 0 && s.bps < minBps) {
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
