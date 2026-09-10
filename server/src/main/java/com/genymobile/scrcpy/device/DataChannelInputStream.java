package com.genymobile.scrcpy.device;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;

public class DataChannelInputStream extends InputStream implements NativeEncoderBridge.DataCallback {
    private static final int MAX_BUFFERED_BYTES = 1048576;
    private final Deque<byte[]> queue = new ArrayDeque<>();
    private final Runnable overflow;
    private byte[] current;
    private int position;
    private int buffered;
    private boolean closed;
    public DataChannelInputStream() { this(() -> { }); }
    public DataChannelInputStream(Runnable overflow) { this.overflow = overflow; }
    @Override
    public void onData(byte[] data) {
        boolean exceeded = false;
        synchronized (this) {
            if (closed || data.length == 0) { return; }
            if (data.length > MAX_BUFFERED_BYTES - buffered) { close(); exceeded = true; }
            else { queue.addLast(data); buffered += data.length; notifyAll(); }
        }
        if (exceeded) { overflow.run(); }
    }
    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }
    @Override
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || off > b.length - len) { throw new IndexOutOfBoundsException(); }
        if (len == 0) { return 0; }
        while (!closed && (current == null || position == current.length) && queue.isEmpty()) {
            try { wait(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IOException("Control input interrupted", e);
            }
        }
        if (closed) { return -1; }
        if (current == null || position == current.length) { current = queue.removeFirst(); position = 0; }
        int count = Math.min(len, current.length - position);
        System.arraycopy(current, position, b, off, count);
        position += count; buffered -= count;
        return count;
    }
    @Override
    public synchronized void close() {
        closed = true; queue.clear(); current = null; buffered = 0; notifyAll();
    }
}
