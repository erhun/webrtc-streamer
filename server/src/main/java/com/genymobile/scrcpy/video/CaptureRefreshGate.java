package com.genymobile.scrcpy.video;

/** Encoder-thread state for bootstrap input and the subsequent send-ready refresh. */
public final class CaptureRefreshGate {
    private boolean pending = true;
    private boolean ready;
    private boolean bootstrapRefreshPending = true;
    private boolean bootstrapFramePending = true;

    public void newPeer() {
        pending = true;
        ready = false;
        bootstrapRefreshPending = true;
        bootstrapFramePending = true;
    }

    public void onBitrate(int bps) {
        ready = bps > 0;
        if (ready) {
            bootstrapRefreshPending = false;
            bootstrapFramePending = false;
        }
    }

    public boolean consumeBootstrapRefresh(boolean keyFrameRequested) {
        if (!bootstrapRefreshPending || !keyFrameRequested) { return false; }
        bootstrapRefreshPending = false;
        bootstrapFramePending = true;
        return true;
    }

    // VideoStreamEncoder may need input before it initializes its encoder and
    // calls SetRates. Feed one IDR to the source even while Java is suspended;
    // the native sender still enforces its own zero-bitrate transport pause.
    public boolean consumeBootstrapFrame(boolean keyFrame) {
        if (!bootstrapFramePending || !keyFrame) { return false; }
        bootstrapFramePending = false;
        return true;
    }

    public boolean consumeRefresh() {
        if (!pending || !ready) { return false; }
        pending = false;
        return true;
    }
}
