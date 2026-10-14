package com.genymobile.scrcpy.device;

import java.io.IOException;
import java.io.OutputStream;

public class DataChannelOutputStream extends OutputStream {

    private volatile NativeEncoderBridge bridge;

    public DataChannelOutputStream(NativeEncoderBridge bridge) {
        this.bridge = bridge;
    }

    public void setBridge(NativeEncoderBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void write(int b) throws IOException {
        byte[] one = new byte[]{(byte) b};
        write(one, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        byte[] data = new byte[len];
        System.arraycopy(b, off, data, 0, len);
        NativeEncoderBridge current = bridge;
        if (current == null || !current.sendData(data)) { throw new IOException("Control channel closed or backpressured"); }
    }
}
