package com.genymobile.scrcpy.device;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public class DataChannelInputStream extends InputStream implements NativeEncoderBridge.DataCallback {
    public interface OverflowListener {
        void onOverflow(String sender);
    }
    private static final int MAX_BUFFERED_BYTES = 1048576;
    private final Deque<Chunk> queue = new ArrayDeque<>();
    private final OverflowListener overflow;
    private byte[] current;
    private String currentSender;
    private int position;
    private int buffered;
    private boolean closed;

    public DataChannelInputStream() { this(sender -> { }); }
    public DataChannelInputStream(OverflowListener overflow) { this.overflow = overflow; }

    @Override
    public void onData(byte[] data) {
        onData(data, null);
    }

    /** The sender identity is kept per chunk so an overflow isolates the flooding viewer, not the session. */
    public void onData(byte[] data, String sender) {
        String flooding = null;
        synchronized (this) {
            if (closed || data.length == 0) { return; }
            if (data.length > MAX_BUFFERED_BYTES - buffered) {
                // Drop the incoming chunk and the flooder's backlog; the shared capture keeps running.
                if (sender == null) {
                    queue.clear();
                    current = null;
                    currentSender = null;
                    buffered = 0;
                } else {
                    drain(sender);
                }
                flooding = sender;
            } else {
                queue.addLast(new Chunk(data, sender));
                buffered += data.length;
                notifyAll();
            }
        }
        if (flooding != null) {
            overflow.onOverflow(flooding);
        }
    }

    private void drain(String sender) {
        for (Iterator<Chunk> it = queue.iterator(); it.hasNext(); ) {
            if (sender.equals(it.next().sender)) {
                it.remove();
            }
        }
        if (current != null && sender.equals(currentSender)) {
            current = null;
            currentSender = null;
            position = 0;
        }
        recomputeBuffered();
    }

    private void recomputeBuffered() {
        int total = 0;
        for (Chunk chunk : queue) { total += chunk.data.length; }
        buffered = total;
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
        if (current == null || position == current.length) {
            Chunk chunk = queue.removeFirst();
            current = chunk.data;
            currentSender = chunk.sender;
            position = 0;
        }
        int count = Math.min(len, current.length - position);
        System.arraycopy(current, position, b, off, count);
        position += count; buffered -= count;
        return count;
    }
    @Override
    public synchronized void close() {
        closed = true; queue.clear(); current = null; currentSender = null; buffered = 0; notifyAll();
    }

    private static final class Chunk {
        final byte[] data;
        final String sender;
        Chunk(byte[] data, String sender) { this.data = data; this.sender = sender; }
    }
}
