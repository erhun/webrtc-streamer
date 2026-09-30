package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.video.VideoCodec;

import android.media.MediaCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BroadcastVideoSink implements PacketSink {

    private final List<NativeEncoderBridge> bridges = new CopyOnWriteArrayList<>();
    private byte[] spsPps;
    private int width;
    private int height;

    public void addBridge(NativeEncoderBridge bridge) {
        if (!bridges.contains(bridge)) {
            bridges.add(bridge);
        }
    }

    public void removeBridge(NativeEncoderBridge bridge) {
        bridges.remove(bridge);
    }

    public int getBridgeCount() {
        return bridges.size();
    }

    @Override
    public void reportVideoBitrate(int bps) {
        for (NativeEncoderBridge bridge : bridges) {
            bridge.reportVideoBitrate(bps);
        }
    }

    @Override
    public VideoCodec getCodec() {
        return VideoCodec.H264;
    }

    @Override
    public void writeAudioHeader() throws IOException {
    }

    @Override
    public void writeVideoHeader() throws IOException {
    }

    @Override
    public void writeDisableStream(boolean error) throws IOException {
    }

    @Override
    public void writePacket(ByteBuffer buffer, long pts, boolean config, boolean keyFrame) throws IOException {
        writeVideoPacket(buffer, pts, config, keyFrame);
    }

    @Override
    public void writePacket(ByteBuffer codecBuffer, MediaCodec.BufferInfo bufferInfo) throws IOException {
        long pts = bufferInfo.presentationTimeUs;
        boolean config = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        writePacket(codecBuffer, pts, config, keyFrame);
    }

    @Override
    public void writeSessionMeta(int width, int height, boolean isClientResize) throws IOException {
        this.width = width;
        this.height = height;
    }

    private void writeVideoPacket(ByteBuffer buffer, long pts, boolean config, boolean keyFrame) {
        if (config) {
            spsPps = new byte[buffer.remaining()];
            buffer.get(spsPps);
            return;
        }
        for (NativeEncoderBridge bridge : bridges) {
            if (keyFrame && spsPps != null) {
                ByteBuffer combined = ByteBuffer.allocate(spsPps.length + buffer.remaining());
                combined.put(spsPps);
                buffer.mark();
                combined.put(buffer);
                buffer.reset();
                combined.flip();
                bridge.pushEncodedVideoFrame(combined, pts, false, true, width, height);
            } else {
                bridge.pushEncodedVideoFrame(buffer, pts, false, keyFrame, width, height);
            }
        }
    }
}
