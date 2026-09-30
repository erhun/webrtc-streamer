package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.device.NativeEncoderBridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BitrateAggregator {

    public interface Output {
        void onAggregatedBitrate(int bps, double fps);
        void onAggregatedKeyFrameRequest();
    }

    private final Map<NativeEncoderBridge, Integer> bitrates = new ConcurrentHashMap<>();
    private final Output output;
    private double latestFps;

    public BitrateAggregator(Output output) {
        this.output = output;
    }

    public synchronized void addBridge(NativeEncoderBridge bridge) {
        bitrates.put(bridge, 0);
    }

    public synchronized void removeBridge(NativeEncoderBridge bridge) {
        bitrates.remove(bridge);
    }

    public synchronized void updateBitrate(NativeEncoderBridge source, int bps, double fps) {
        bitrates.put(source, Math.max(0, bps));
        latestFps = fps;
        int min = Integer.MAX_VALUE;
        for (int value : bitrates.values()) {
            if (value > 0 && value < min) {
                min = value;
            }
        }
        if (min == Integer.MAX_VALUE) {
            min = 0;
        }
        output.onAggregatedBitrate(min, latestFps);
    }

    public void requestKeyFrame() {
        output.onAggregatedKeyFrameRequest();
    }
}
