package com.genymobile.scrcpy.device;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class DataChannelInputStream extends InputStream implements NativeEncoderBridge.DataCallback {

    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

    private byte[] current;
    private int position;

    @Override
    public void onData(byte[] data) {
        queue.offer(data);
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : (one[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }

        if (current == null || position >= current.length) {
            try {
                current = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading DataChannel", e);
            }
            position = 0;
        }

        int available = current.length - position;
        int toRead = Math.min(available, len);
        System.arraycopy(current, position, b, off, toRead);
        position += toRead;
        return toRead;
    }
}
