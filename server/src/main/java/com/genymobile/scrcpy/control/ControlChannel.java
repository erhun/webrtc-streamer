package com.genymobile.scrcpy.control;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class ControlChannel {

    private final InputStream input;
    private ControlMessageReader reader; // control-thread only; reset() swaps it
    private final DeviceMessageWriter writer;

    public ControlChannel(InputStream in, OutputStream out) {
        this.input = in;
        reader = new ControlMessageReader(in);
        writer = new DeviceMessageWriter(out);
    }

    public ControlMessage recv() throws IOException {
        return reader.read();
    }

    public void send(DeviceMessage msg) throws IOException {
        writer.write(msg);
    }

    /** Drop any partially buffered message so a corrupt sender cannot desynchronize the stream forever.
     * The reader is only touched by the control thread; a plain reassignment keeps the reset race-free. */
    public void reset() {
        reader = new ControlMessageReader(input);
    }
}
