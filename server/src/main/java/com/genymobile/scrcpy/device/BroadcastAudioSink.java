package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.model.Codec;
import com.genymobile.scrcpy.util.Ln;

import android.media.MediaCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BroadcastAudioSink implements PacketSink {

    private final List<NativeEncoderBridge> bridges = new CopyOnWriteArrayList<>();
    private final Codec codec;
    private long packetCount;
    private static final long LOG_INTERVAL = 500; // log every 500 packets (~5 seconds)

    public BroadcastAudioSink(Codec codec) {
        this.codec = codec;
    }

    public void addBridge(NativeEncoderBridge bridge) {
        if (!bridges.contains(bridge)) {
            bridges.add(bridge);
        }
    }

    public void removeBridge(NativeEncoderBridge bridge) {
        bridges.remove(bridge);
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
        if (config) {
            return;
        }
        if (bridges.isEmpty()) {
            Ln.w("BroadcastAudioSink: no bridges registered, dropping audio packet");
            return;
        }
        packetCount++;
        if (packetCount == 1 || packetCount % LOG_INTERVAL == 0) {
            Ln.d("BroadcastAudioSink: packet #" + packetCount + ", " + buffer.remaining() + " bytes, " + bridges.size() + " bridges, codec=" + codec);
        }
        for (NativeEncoderBridge bridge : bridges) {
            if (codec == AudioCodec.OPUS) {
                bridge.pushOpusFrame(buffer, pts);
            } else {
                bridge.pushPcm(buffer, pts);
            }
        }
    }

    @Override
    public void writePacket(ByteBuffer codecBuffer, MediaCodec.BufferInfo bufferInfo) throws IOException {
        long pts = bufferInfo.presentationTimeUs;
        boolean config = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        writePacket(codecBuffer, pts, config, false);
    }

    @Override
    public void writeSessionMeta(int width, int height, boolean isClientResize) throws IOException {
    }
}
