package com.genymobile.scrcpy.video;

/** Encoder-thread state: refresh static capture once per peer, after a positive send budget. */
public final class CaptureRefreshGate {
    private boolean pending = true;
    private boolean ready;

    public void newPeer() {
        pending = true;
        ready = false;
    }

    public void onBitrate(int bps) { ready = bps > 0; }

    public boolean consumeRefresh() {
        if (!pending || !ready) { return false; }
        pending = false;
        return true;
    }
}
