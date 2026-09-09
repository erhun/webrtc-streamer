package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.model.Codec;

import android.media.MediaCodec;

import java.io.IOException;
import java.nio.ByteBuffer;

public class WebRtcPacketSink implements PacketSink {

    private final Codec codec;
    private final NativeEncoderBridge bridge;
    private final boolean video;

    private byte[] spsPps;
    private int width;
    private int height;

    public WebRtcPacketSink(Codec codec, NativeEncoderBridge bridge, boolean video) {
        this.codec = codec;
        this.bridge = bridge;
        this.video = video;
    }

    @Override
    public Codec getCodec() {
        return codec;
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
        if (video) {
            writeVideoPacket(buffer, pts, config, keyFrame);
        } else {
            writeAudioPacket(buffer, pts);
        }
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
        if (keyFrame && spsPps != null) {
            ByteBuffer combined = ByteBuffer.allocate(spsPps.length + buffer.remaining());
            combined.put(spsPps);
            combined.put(buffer);
            combined.flip();
            bridge.pushEncodedVideoFrame(combined, pts, false, true, width, height);
        } else {
            bridge.pushEncodedVideoFrame(buffer, pts, false, keyFrame, width, height);
        }
    }

    private void writeAudioPacket(ByteBuffer buffer, long pts) {
        bridge.pushEncodedAudioFrame(buffer, pts);
    }
}
