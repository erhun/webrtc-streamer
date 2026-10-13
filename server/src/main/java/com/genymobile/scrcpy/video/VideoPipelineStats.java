package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.util.Ln;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One monotonic interval for GL, codec and handoff counters. No dumpsys polling on capture threads. */
public final class VideoPipelineStats implements AutoCloseable {
    private ScheduledExecutorService sampler;
    private String mode = "starting";
    private long windowStartNs;
    private long glInput;
    private long glSubmitted;
    private long glSkipped;
    private long codecFrames;
    private long handoffFrames;
    private long updateNs;
    private long updateMaxNs;
    private long swapNs;
    private long swapMaxNs;
    private long handoffNs;
    private long handoffMaxNs;

    public synchronized void start() {
        if (!Ln.isEnabled(Ln.Level.DEBUG) || sampler != null) {
            return;
        }
        windowStartNs = System.nanoTime();
        sampler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "video-stats");
            thread.setDaemon(true);
            return thread;
        });
        sampler.scheduleAtFixedRate(this::report, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized void setMode(String value) {
        mode = value;
    }

    public synchronized void onGlInput(long elapsedNs) {
        glInput++;
        updateNs += elapsedNs;
        updateMaxNs = Math.max(updateMaxNs, elapsedNs);
    }

    public synchronized void onGlSkipped() {
        glSkipped++;
    }

    public synchronized void onGlSubmitted(long elapsedNs) {
        glSubmitted++;
        swapNs += elapsedNs;
        swapMaxNs = Math.max(swapMaxNs, elapsedNs);
    }

    public synchronized void onCodecFrame() {
        codecFrames++;
    }

    public synchronized void onHandoff(long elapsedNs) {
        handoffFrames++;
        handoffNs += elapsedNs;
        handoffMaxNs = Math.max(handoffMaxNs, elapsedNs);
    }

    private synchronized void report() {
        long now = System.nanoTime();
        double seconds = (now - windowStartNs) / 1_000_000_000.0;
        if (seconds <= 0) {
            return;
        }
        Ln.d(String.format(Locale.ROOT,
                "Video pipeline: monoStartUs=%d monoEndUs=%d mode=%s glInput=%.2ffps glSubmitted=%.2ffps glSkipped=%d "
                        + "codec=%.2ffps handoff=%.2ffps updateAvg=%.3fms updateMax=%.3fms "
                        + "swapAvg=%.3fms swapMax=%.3fms handoffAvg=%.3fms handoffMax=%.3fms",
                windowStartNs / 1000, now / 1000, mode, glInput / seconds, glSubmitted / seconds, glSkipped,
                codecFrames / seconds, handoffFrames / seconds,
                meanMs(updateNs, glInput), updateMaxNs / 1_000_000.0,
                meanMs(swapNs, glSubmitted), swapMaxNs / 1_000_000.0,
                meanMs(handoffNs, handoffFrames), handoffMaxNs / 1_000_000.0));
        windowStartNs = now;
        glInput = glSubmitted = glSkipped = codecFrames = handoffFrames = 0;
        updateNs = updateMaxNs = swapNs = swapMaxNs = handoffNs = handoffMaxNs = 0;
    }

    private static double meanMs(long ns, long count) {
        return count == 0 ? 0 : ns / (count * 1_000_000.0);
    }

    @Override
    public synchronized void close() {
        if (sampler != null) {
            sampler.shutdownNow();
            sampler = null;
        }
    }
}
