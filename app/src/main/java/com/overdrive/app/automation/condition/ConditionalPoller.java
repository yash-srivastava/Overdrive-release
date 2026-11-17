package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * A fixed-delay poll that exists only while an enabled automation references its signal.
 *
 * <p>A small shared scheduler dispatches each signal to its own single-thread worker.
 * A blocked vehicle read therefore cannot starve another signal, and the in-flight guard
 * prevents overlapping reads even when a disabled poller is quickly re-enabled.
 */
final class ConditionalPoller {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final AtomicInteger threadIds = new AtomicInteger();
    private static final ThreadFactory threadFactory = runnable -> {
        Thread thread = new Thread(
                runnable, "AutomationPoll-" + threadIds.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    // The scheduler only dispatches work. Vehicle/OEM reads run on a per-signal worker so
    // one blocked service call cannot consume a scheduler thread or starve other signals.
    private static final ScheduledThreadPoolExecutor executor =
            new ScheduledThreadPoolExecutor(2, threadFactory);

    static {
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(5L, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
    }

    private final String name;
    private final long periodMs;
    private final BooleanSupplier shouldRun;
    private final Runnable task;
    private final boolean vehicleState;

    private ScheduledFuture<?> future;
    private ExecutorService worker;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicBoolean previouslyFailed = new AtomicBoolean(false);
    private long generation;

    ConditionalPoller(
            String name, long periodMs, BooleanSupplier shouldRun, Runnable task) {
        this(name, periodMs, shouldRun, task, true);
    }

    ConditionalPoller(
            String name, long periodMs, BooleanSupplier shouldRun, Runnable task,
            boolean vehicleState) {
        if (periodMs <= 0L) throw new IllegalArgumentException("periodMs must be positive");
        this.name = name;
        this.periodMs = periodMs;
        this.shouldRun = shouldRun;
        this.task = task;
        this.vehicleState = vehicleState;
    }

    /** Start, keep, or cancel this poll according to its current reference predicate. */
    void refresh() {
        boolean wanted = safelyWanted();
        synchronized (this) {
            if (!wanted) {
                cancelLocked();
                return;
            }
            if (future != null && !future.isDone() && !future.isCancelled()) return;

            long token = ++generation;
            AtomicBoolean first = new AtomicBoolean(true);
            // A dead periodic future leaves its worker behind; never leak it.
            if (worker != null) worker.shutdown();
            worker = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "AutomationSignal-" + name);
                thread.setDaemon(true);
                return thread;
            });
            ExecutorService signalWorker = worker;
            future = executor.scheduleWithFixedDelay(() -> {
                if (!isCurrent(token)) return;
                if (!safelyWanted()) {
                    cancel(token);
                    return;
                }
                // Fixed-delay scheduling alone does not prevent overlap when the task is
                // dispatched asynchronously. Keep one in-flight read across generations too,
                // because an OEM call may ignore interruption during disable/re-enable.
                if (!inFlight.compareAndSet(false, true)) return;
                try {
                    signalWorker.execute(() -> {
                        try {
                            if (!isCurrent(token) || !safelyWanted()) return;
                            Runnable currentTask = task;
                            if (vehicleState && DiLink5Platform.isSelected()) {
                                currentTask = () -> BydDataCollector.getInstance()
                                        .runWithDiLink5AutomationFreshness(task);
                            }
                            long startedAt = System.nanoTime();
                            if (first.get()) {
                                // Do not consume the baseline flag until sampling succeeds. If
                                // the OEM service is temporarily unavailable, the next successful
                                // read must still seed silently rather than manufacture an edge.
                                Automations.runSilentSeed(currentTask);
                                first.set(false);
                            } else {
                                currentTask.run();
                            }
                            if (previouslyFailed.compareAndSet(true, false)) {
                                Automations.recordPollerHealth(name, "recovered",
                                        TimeUnit.NANOSECONDS.toMillis(
                                                System.nanoTime() - startedAt), null);
                            }
                        } catch (Throwable t) {
                            logger.error(name + " poll failed", t);
                            if (previouslyFailed.compareAndSet(false, true)) {
                                Automations.recordPollerHealth(name, "failed", 0L, t);
                            }
                        } finally {
                            inFlight.set(false);
                        }
                    });
                } catch (RejectedExecutionException rejected) {
                    inFlight.set(false);
                    if (isCurrent(token)) logger.warn(name + " poll dispatch rejected");
                } catch (Throwable dispatchFailed) {
                    // e.g. the worker thread could not be created: release the slot so the
                    // next tick retries instead of the signal going silently dead.
                    inFlight.set(false);
                    logger.error(name + " poll dispatch failed", dispatchFailed);
                }
            }, 0L, periodMs, TimeUnit.MILLISECONDS);
        }
    }

    private boolean safelyWanted() {
        try {
            return shouldRun.getAsBoolean();
        } catch (Throwable t) {
            logger.warn(name + " reference check failed: " + t.getMessage());
            return false;
        }
    }

    private synchronized boolean isCurrent(long token) {
        return token == generation;
    }

    private synchronized void cancel(long token) {
        if (token == generation) cancelLocked();
    }

    private void cancelLocked() {
        generation++;
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        if (worker != null) {
            // Do not interrupt a vehicle API call: some OEM services are not interruption-safe.
            // The generation check prevents queued stale work from publishing after cancellation.
            worker.shutdown();
            worker = null;
        }
    }

    synchronized boolean isScheduledForTest() {
        return future != null && !future.isDone() && !future.isCancelled();
    }

    synchronized long generationForTest() {
        return generation;
    }
}
