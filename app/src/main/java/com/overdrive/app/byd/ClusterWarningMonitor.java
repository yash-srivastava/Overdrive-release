package com.overdrive.app.byd;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;

/** Shares one on-demand hardware read between successive display frames. */
final class ClusterWarningMonitor {
    static final int FORWARD = 1;
    static final int REVERSE = 2;
    static final int STOPPED = 4;
    private static final long POLL_INTERVAL_MS = 200;
    private static final long MAX_AGE_MS = 650;
    static final int PCW_IDLE = 0x1000;
    static final int PCW_ASSERTED = 0x2000;

    private final IntUnaryOperator readWarnings;
    private final LongSupplier elapsedMs;
    private final Executor worker;
    private int eligibility;
    private long generation;
    private boolean reading;
    private long nextReadAt;
    private long validUntil;
    private int warnings;
    private boolean pcwIdle;
    private boolean pcwActive;
    private long lastSampleAt = -1;

    ClusterWarningMonitor(IntUnaryOperator readWarnings, LongSupplier elapsedMs) {
        this(readWarnings, elapsedMs, new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), task -> {
                    Thread thread = new Thread(task, "ClusterAdasWarnings");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    ClusterWarningMonitor(IntUnaryOperator readWarnings, LongSupplier elapsedMs, Executor worker) {
        this.readWarnings = readWarnings;
        this.elapsedMs = elapsedMs;
        this.worker = worker;
    }

    synchronized int current(int requested) {
        if (requested != eligibility) {
            boolean stillForward = (requested & eligibility & FORWARD) != 0;
            generation++;
            nextReadAt = 0;
            warnings &= stillForward ? BydDataCollector.FORWARD_COLLISION_BIT : 0;
            if (!stillForward) clearForward();
            eligibility = requested;
        }
        if (requested == 0) return 0;
        long now = elapsedMs.getAsLong();
        if (!reading && now >= nextReadAt) {
            reading = true;
            nextReadAt = now + POLL_INTERVAL_MS;
            long epoch = generation;
            worker.execute(() -> refresh(requested, epoch, now));
        }
        if (now >= validUntil) return 0;
        return pcwActive ? warnings | BydDataCollector.FORWARD_COLLISION_BIT : warnings;
    }

    synchronized void reset() {
        generation++;
        eligibility = 0;
        nextReadAt = 0;
        validUntil = 0;
        warnings = 0;
        clearForward();
    }

    private void clearForward() {
        pcwIdle = false;
        pcwActive = false;
        lastSampleAt = -1;
    }

    private void refresh(int requested, long epoch, long sampledAt) {
        int result = 0;
        try {
            synchronized (this) {
                if (generation != epoch) return;
            }
            result = readWarnings.applyAsInt(requested);
        } catch (RuntimeException | LinkageError unavailable) {
            // A failed SDK read must clear the previous sample and leave the next read eligible.
        } finally {
            synchronized (this) {
                if (generation == epoch) {
                    boolean contiguous = lastSampleAt >= 0 && sampledAt - lastSampleAt < MAX_AGE_MS;
                    pcwActive = (result & PCW_ASSERTED) != 0 && contiguous && (pcwIdle || pcwActive);
                    pcwIdle = (result & PCW_IDLE) != 0;
                    lastSampleAt = sampledAt;
                    warnings = result & ~(PCW_IDLE | PCW_ASSERTED);
                    validUntil = sampledAt + MAX_AGE_MS;
                }
                reading = false;
            }
        }
    }
}
