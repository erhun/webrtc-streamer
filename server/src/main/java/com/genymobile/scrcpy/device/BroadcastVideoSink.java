package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.video.VideoCodec;

import android.media.MediaCodec;

import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** One immutable encoded payload is shared across peers; new subscribers wait for a decodable IDR. */
public final class BroadcastVideoSink implements PacketSink {
    private final CopyOnWriteArrayList<Subscription> subscribers = new CopyOnWriteArrayList<>();
    private volatile Format format = new Format(null, 0, 0);

    public void addBridge(NativeEncoderBridge bridge) {
        removeBridge(bridge);
        subscribers.add(new Subscription(bridge));
    }
    public void removeBridge(NativeEncoderBridge bridge) {
        for (Subscription sub : subscribers) { if (sub.bridge == bridge) { subscribers.remove(sub); } }
    }
    public void restartBridge(NativeEncoderBridge bridge) {
        for (Subscription sub : subscribers) { if (sub.bridge == bridge) { sub.generation.incrementAndGet(); } }
    }
    public int getBridgeCount() { return subscribers.size(); }
    @Override
    public void reportVideoBitrate(int bps) { for (Subscription sub : subscribers) { sub.bridge.reportVideoBitrate(bps); } }
    @Override
    public VideoCodec getCodec() { return VideoCodec.H264; }
    @Override
    public void writeAudioHeader() { }
    @Override
    public void writeVideoHeader() { }
    @Override
    public void writeDisableStream(boolean error) { }
    @Override
    public void writeSessionMeta(int width, int height, boolean isClientResize) { format = new Format(null, width, height); }
    @Override
    public void writePacket(ByteBuffer buffer, long pts, boolean config, boolean keyFrame) {
        byte[] data = new byte[buffer.remaining()]; buffer.get(data);
        Format current = format;
        if (config) {
            format = new Format(data, current.width, current.height);
            return;
        }
        for (Subscription sub : subscribers) {
            long generation = sub.generation.get();
            boolean waitingForKey = sub.sentGeneration != generation;
            if (waitingForKey && !keyFrame) { continue; }
            if (current.config == null) { continue; }
            if (sub.sentFormat != current || waitingForKey) {
                if (!keyFrame) { continue; }
                sub.bridge.pushEncodedVideoFrame(current.config, 0, true, false, current.width, current.height);
                sub.sentFormat = current;
            }
            sub.bridge.pushEncodedVideoFrame(data, pts, false, keyFrame, current.width, current.height);
            sub.sentGeneration = generation;
        }
    }
    @Override
    public void writePacket(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        writePacket(buffer, info.presentationTimeUs, (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0,
                (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
    }
    private static final class Subscription {
        final NativeEncoderBridge bridge;
        final AtomicLong generation = new AtomicLong();
        long sentGeneration = -1;
        Format sentFormat;
        Subscription(NativeEncoderBridge bridge) { this.bridge = bridge; }
    }
    private static final class Format {
        final byte[] config;
        final int width;
        final int height;
        Format(byte[] config, int width, int height) { this.config = config; this.width = width; this.height = height; }
    }
}
