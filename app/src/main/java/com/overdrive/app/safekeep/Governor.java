package com.overdrive.app.safekeep;

import com.overdrive.app.safekeep.util.TokenBucket;

/**
 * Upload is the lowest-priority tenant: recording, detection, live view, blind spot and
 * voice always win. Consulted before dispatch and before every chunk (≈5 s of work).
 */
public final class Governor {

    private static final long RECORDER_LAG_PAUSE_MS = 1500;
    private static final int QUEUE_DEPTH_PAUSE = 180; // 30% of the 600-slot muxer queue
    private static final long HEALTHY_HOLD_MS = 10_000;

    private final TokenBucket bucket = new TokenBucket(0);
    // elapsedRealtime() cannot jump when the head unit corrects its wall clock.
    // -1 means the recorder has not reported an unhealthy interval.
    private volatile long unhealthyAtElapsed = -1L;
    private volatile long lastDrops = -1;

    public TokenBucket bucket() {
        return bucket;
    }

    /** Null when the chunk may proceed, else a reason code. Class 0 (KB-sized) is exempt from media pauses. */
    public String backpressure(SafeKeepConfig cfg, UploadSignals.Snap s, int pclass) {
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        boolean dropped = lastDrops >= 0 && s.drops > lastDrops;
        lastDrops = s.drops;
        if (s.writing && (s.writerLagMs > RECORDER_LAG_PAUSE_MS || s.queueDepth > QUEUE_DEPTH_PAUSE || dropped)) {
            unhealthyAtElapsed = nowElapsed;
        }
        if (SafeKeepTiming.withinHold(nowElapsed, unhealthyAtElapsed, HEALTHY_HOLD_MS)) {
            return "recorder_busy";
        }
        if (pclass >= 1) {
            if (s.eventActive) return "event_recording";
            if (cfg.limits.pauseForLiveView && s.liveView) return "live_view";
            if (s.interactive()) return "interactive";
        }
        return null;
    }

    /** Two transfers only on unmetered, parked, quiet links; otherwise one. */
    public int maxWorkers(SafeKeepConfig cfg, UploadSignals.Snap s) {
        boolean quiet = s.unmetered() && s.parked && !s.eventActive && !s.liveView && !s.interactive();
        return quiet ? cfg.limits.maxConcurrent : 1;
    }

    /** Chunk size targeting ~4 s of transfer, aligned to 1280 KiB (Drive 256 KiB × OneDrive 320 KiB). */
    public static int suggestChunk(double bytesPerSec) {
        final int unit = 1280 * 1024;
        final int min = 5 * 1024 * 1024;
        final int max = 20 * 1024 * 1024;
        // Unknown throughput: start small so the next gate check comes soon; later chunks resize.
        if (bytesPerSec <= 0) return min;
        long want = (long) (bytesPerSec * 4);
        long aligned = Math.max(min, Math.min(max, (want / unit) * unit));
        return (int) Math.max(min, aligned - (aligned % unit));
    }
}
