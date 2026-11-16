package com.overdrive.app.automation.condition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Verifies that automation polling consumes no periodic work while unreferenced. */
public class ConditionalPollerTest {

    @BeforeClass
    public static void configureLogger() {
        com.overdrive.app.logging.DaemonLogger.Config config =
                new com.overdrive.app.logging.DaemonLogger.Config();
        config.enableConsoleLog = false;
        config.enableFileLog = false;
        config.enableStdoutLog = true;
        com.overdrive.app.logging.DaemonLogger.configure(config);
    }

    @Test
    public void taskExistsOnlyWhileReferenced() throws Exception {
        AtomicBoolean referenced = new AtomicBoolean(false);
        AtomicInteger runs = new AtomicInteger();
        ConditionalPoller poller = new ConditionalPoller(
                "lifecycle test", 20L, referenced::get, runs::incrementAndGet);

        poller.refresh();
        assertFalse(poller.isScheduledForTest());
        Thread.sleep(60L);
        assertEquals(0, runs.get());

        referenced.set(true);
        poller.refresh();
        assertTrue(poller.isScheduledForTest());
        awaitAtLeastOneRun(runs);

        referenced.set(false);
        poller.refresh();
        assertFalse(poller.isScheduledForTest());
        // Let a tick already in flight finish, then verify no periodic work remains.
        Thread.sleep(40L);
        int stableRuns = runs.get();
        Thread.sleep(80L);
        assertEquals(stableRuns, runs.get());
    }

    @Test
    public void repeatedRefreshKeepsTheExistingScheduledTask() {
        AtomicBoolean referenced = new AtomicBoolean(true);
        ConditionalPoller poller = new ConditionalPoller(
                "refresh idempotency test", 20L, referenced::get, () -> {});

        poller.refresh();
        long scheduledGeneration = poller.generationForTest();
        assertTrue(poller.isScheduledForTest());

        poller.refresh();
        poller.refresh();

        assertTrue(poller.isScheduledForTest());
        assertEquals(scheduledGeneration, poller.generationForTest());

        referenced.set(false);
        poller.refresh();
        assertFalse(poller.isScheduledForTest());
    }


    @Test
    public void blockedSignalDoesNotStarveAnotherSignal() throws Exception {
        AtomicBoolean referenced = new AtomicBoolean(true);
        CountDownLatch blockedStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocked = new CountDownLatch(1);
        AtomicInteger independentRuns = new AtomicInteger();
        ConditionalPoller blocked = new ConditionalPoller(
                "blocked isolation test", 20L, referenced::get, () -> {
                    blockedStarted.countDown();
                    try {
                        releaseBlocked.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
        ConditionalPoller independent = new ConditionalPoller(
                "independent isolation test", 20L, referenced::get,
                independentRuns::incrementAndGet);
        try {
            blocked.refresh();
            independent.refresh();
            assertTrue(blockedStarted.await(1, TimeUnit.SECONDS));
            long deadline = System.currentTimeMillis() + 1000L;
            while (independentRuns.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue("independent signal was starved by blocked signal",
                    independentRuns.get() > 0);
        } finally {
            releaseBlocked.countDown();
            referenced.set(false);
            blocked.refresh();
            independent.refresh();
        }
    }

    @Test
    public void failedFirstSampleDoesNotConsumeSilentBaseline() throws Exception {
        AtomicBoolean referenced = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger silentRuns = new AtomicInteger();
        ConditionalPoller poller = new ConditionalPoller(
                "baseline retry test", 20L, referenced::get, () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("service not ready");
                    }
                    if (com.overdrive.app.automation.Automations.isSilentSeedActive()) {
                        silentRuns.incrementAndGet();
                    }
                });
        try {
            poller.refresh();
            long deadline = System.currentTimeMillis() + 1000L;
            while (silentRuns.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertEquals(1, silentRuns.get());
        } finally {
            referenced.set(false);
            poller.refresh();
        }
    }


    @Test
    public void oneSignalNeverRunsOverlappingReads() throws Exception {
        AtomicBoolean referenced = new AtomicBoolean(true);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        ConditionalPoller poller = new ConditionalPoller(
                "overlap isolation test", 10L, referenced::get, () -> {
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(45L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                    }
                });
        try {
            poller.refresh();
            Thread.sleep(180L);
            assertEquals(1, maximumActive.get());
        } finally {
            referenced.set(false);
            poller.refresh();
        }
    }

    private static void awaitAtLeastOneRun(AtomicInteger runs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1000L;
        while (runs.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue("referenced poller did not run", runs.get() > 0);
    }
}
