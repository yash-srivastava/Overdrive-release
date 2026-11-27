package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class NetworkRefreshGateTest {
    @Test
    public void concurrentStaleReadersShareOneProbeAndCompletedSnapshot() throws Exception {
        burst(false);
    }

    @Test
    public void failedProbeIsSharedButLaterIndependentReadRetries() throws Exception {
        burst(true);
    }

    private static void burst(boolean fail) throws Exception {
        NetworkRefreshGate gate = new NetworkRefreshGate();
        AtomicBoolean stale = new AtomicBoolean(true);
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger snapshot = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable probe = () -> {
            probes.incrementAndGet();
            snapshot.set(-1); // intermediate value must never escape a reader
            entered.countDown();
            await(release);
            snapshot.set(42);
            if (!fail) stale.set(false);
        };
        List<FutureTask<Integer>> results = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                FutureTask<Integer> task = new FutureTask<>(
                        () -> gate.read(stale::get, probe, snapshot::get));
                Thread thread = new Thread(task);
                thread.setDaemon(true);
                results.add(task);
                threads.add(thread);
                thread.start();
                if (i == 0) {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                } else {
                    // The first probe owns the gate; each reader must actually have
                    // entered read() before we let that attempt complete.
                    awaitBlocked(thread);
                    assertFalse(task.isDone());
                }
            }
            release.countDown();
            for (FutureTask<Integer> task : results) {
                assertEquals(42, task.get(5, TimeUnit.SECONDS).intValue());
            }
            assertEquals(1, probes.get());
            gate.read(stale::get, probe, snapshot::get);
            assertEquals(fail ? 2 : 1, probes.get());
        } finally {
            release.countDown();
            for (Thread thread : threads) thread.join(5000);
        }
    }

    @Test
    public void forcedRefreshIsNotThrottledAndReadersWaitForItsSnapshot() throws Exception {
        NetworkRefreshGate gate = new NetworkRefreshGate();
        AtomicInteger value = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FutureTask<Void> force = new FutureTask<>(() -> {
            gate.force(() -> {
                value.set(-1);
                entered.countDown();
                await(release);
                value.set(7);
            });
            return null;
        });
        Thread writer = new Thread(force);
        writer.setDaemon(true);
        writer.start();
        FutureTask<Integer> read = new FutureTask<>(
                () -> gate.read(() -> false, () -> { throw new AssertionError("unexpected probe"); }, value::get));
        Thread reader = new Thread(read);
        reader.setDaemon(true);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            reader.start();
            awaitBlocked(reader);
            release.countDown();
            force.get(5, TimeUnit.SECONDS);
            assertEquals(7, read.get(5, TimeUnit.SECONDS).intValue());
            gate.force(value::incrementAndGet);
            gate.force(value::incrementAndGet);
            assertEquals(9, value.get());
        } finally {
            release.countDown();
            writer.join(5000);
            reader.join(5000);
        }
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(Thread.State.BLOCKED, thread.getState());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
