package com.overdrive.app.remoteaccess;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Event bursts, deferred checks, cancellation and failure without Android timers. */
public class BluetoothRadioRecoveryEventsTest {
    private static final class Job {
        final Runnable task;
        final long at;
        boolean cancelled, done;
        Job(Runnable task, long at) { this.task = task; this.at = at; }
    }

    private static final class Scheduler implements BluetoothRadioRecoveryEvents.Scheduler {
        final List<Job> jobs = new ArrayList<>();
        long now;
        boolean reject;
        public synchronized BluetoothRadioRecoveryEvents.Cancellation schedule(Runnable task, long delay) {
            if (reject) throw new IllegalStateException("Scheduler unavailable");
            Job job = new Job(task, now + delay);
            jobs.add(job);
            return () -> { synchronized (this) { job.cancelled = true; } };
        }
        void until(long target) {
            while (true) {
                Job next = null;
                synchronized (this) {
                    for (Job job : jobs) {
                        if (!job.done && !job.cancelled && job.at <= target
                                && (next == null || job.at < next.at)) next = job;
                    }
                    if (next == null) { now = Math.max(now, target); return; }
                    next.done = true;
                    now = Math.max(now, next.at);
                }
                next.task.run();
            }
        }
        synchronized int pending() {
            int count = 0;
            for (Job job : jobs) if (!job.cancelled && !job.done) count++;
            return count;
        }
    }

    @Test public void noEventCreatesNoScheduledWork() {
        Scheduler scheduler = new Scheduler();
        new BluetoothRadioRecoveryEvents(scheduler, () -> fail("No event"));
        assertEquals(0, scheduler.pending());
    }

    @Test public void adapterEventChecksImmediatelyAndAgainAfterSettling() {
        Scheduler scheduler = new Scheduler();
        AtomicInteger checks = new AtomicInteger();
        BluetoothRadioRecoveryEvents events = new BluetoothRadioRecoveryEvents(
                scheduler, checks::incrementAndGet);
        events.onAdapterChanged();
        scheduler.until(0);
        assertEquals(1, checks.get());
        scheduler.until(499);
        assertEquals(1, checks.get());
        scheduler.until(500);
        assertEquals(2, checks.get());
        assertEquals(0, scheduler.pending());
    }

    @Test public void eventStormKeepsOnlyOneImmediateAndOneSettlingCheck() {
        Scheduler scheduler = new Scheduler();
        AtomicInteger checks = new AtomicInteger();
        BluetoothRadioRecoveryEvents events = new BluetoothRadioRecoveryEvents(
                scheduler, checks::incrementAndGet);
        for (int i = 0; i < 1_000; i++) events.onAdapterChanged();
        assertEquals(2, scheduler.pending());
        scheduler.until(500);
        assertEquals(2, checks.get());
        assertEquals(0, scheduler.pending());
    }

    @Test public void latestEventReplacesTheOldSettlingDeadline() {
        Scheduler scheduler = new Scheduler();
        AtomicInteger checks = new AtomicInteger();
        BluetoothRadioRecoveryEvents events = new BluetoothRadioRecoveryEvents(
                scheduler, checks::incrementAndGet);
        events.onAdapterChanged();
        scheduler.until(100);
        events.onAdapterChanged();
        scheduler.until(500);
        assertEquals(2, checks.get()); // Immediate checks for the two distinct events.
        scheduler.until(600);
        assertEquals(3, checks.get());
    }

    @Test public void settlingCheckUsesCurrentConsentRatherThanAnEventTimeDecision() {
        Scheduler scheduler = new Scheduler();
        AtomicInteger checks = new AtomicInteger(), enabledCommands = new AtomicInteger();
        boolean[] consent = {true};
        BluetoothRadioRecoveryEvents events = new BluetoothRadioRecoveryEvents(scheduler, () -> {
            checks.incrementAndGet();
            if (consent[0]) enabledCommands.incrementAndGet();
        });
        events.onAdapterChanged();
        scheduler.until(0);
        consent[0] = false;
        scheduler.until(500);
        assertEquals(2, checks.get());
        assertEquals(1, enabledCommands.get());
    }

    @Test public void eventDuringCheckStillGetsOneFreshSettlingCheck() {
        Scheduler scheduler = new Scheduler();
        AtomicInteger checks = new AtomicInteger();
        BluetoothRadioRecoveryEvents[] owner = new BluetoothRadioRecoveryEvents[1];
        owner[0] = new BluetoothRadioRecoveryEvents(scheduler, () -> {
            if (checks.incrementAndGet() == 1) owner[0].onAdapterChanged();
        });
        owner[0].onAdapterChanged();
        scheduler.until(500);
        assertEquals(2, checks.get());
        assertEquals(0, scheduler.pending());
    }

    @Test public void slowNativeCheckCannotRunASecondCheckConcurrently() throws Exception {
        Scheduler scheduler = new Scheduler();
        AtomicInteger checks = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        BluetoothRadioRecoveryEvents events = new BluetoothRadioRecoveryEvents(scheduler, () -> {
            if (checks.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("Check did not finish");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        });
        events.onAdapterChanged();
        Thread nativeCheck = new Thread(() -> scheduler.until(0));
        nativeCheck.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            events.onAdapterChanged();
            scheduler.until(500);
            assertEquals(1, checks.get());
        } finally {
            release.countDown();
            nativeCheck.join(2_000);
        }
        assertFalse(nativeCheck.isAlive());
        scheduler.until(1_000);
        assertEquals(2, checks.get());
        assertEquals(0, scheduler.pending());
    }

    @Test public void schedulingOrNativeFailureDoesNotPermanentlyDisableLaterChecks() {
        Scheduler scheduler = new Scheduler();
        AtomicInteger checks = new AtomicInteger();
        BluetoothRadioRecoveryEvents events = new BluetoothRadioRecoveryEvents(scheduler, () -> {
            if (checks.incrementAndGet() == 1) throw new IllegalStateException("Native service unavailable");
        });
        scheduler.reject = true;
        try {
            events.onAdapterChanged();
            fail("Scheduling should fail");
        } catch (IllegalStateException expected) { }
        scheduler.reject = false;
        events.onAdapterChanged();
        try {
            scheduler.until(0);
            fail("Native check should fail");
        } catch (IllegalStateException expected) { }
        scheduler.until(500);
        assertEquals(2, checks.get());
        events.onAdapterChanged();
        scheduler.until(1_000);
        assertEquals(4, checks.get());
        assertEquals(0, scheduler.pending());
    }
}
