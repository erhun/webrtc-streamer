package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.model.Codec;

import android.media.MediaCodec;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface PacketSink {

    Codec getCodec();

    void writeAudioHeader() throws IOException;

    void writeVideoHeader() throws IOException;

    void writeDisableStream(boolean error) throws IOException;

    void writePacket(ByteBuffer buffer, long pts, boolean config, boolean keyFrame) throws IOException;

    void writePacket(ByteBuffer codecBuffer, MediaCodec.BufferInfo bufferInfo) throws IOException;

    void writeSessionMeta(int width, int height, boolean isClientResize) throws IOException;
}
