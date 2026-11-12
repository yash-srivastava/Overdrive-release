package com.overdrive.app.geo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure-Java regression cases for the resolver's bounded lookup queue. */
public class GeocodeDiscardPolicyTest {

    @Test
    public void evictedOldestTaskIsCleanedAndNewestTaskRuns() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicInteger oldestRuns = new AtomicInteger();
        AtomicInteger oldestDrops = new AtomicInteger();
        AtomicInteger newestRuns = new AtomicInteger();
        AtomicInteger newestDrops = new AtomicInteger();
        ThreadPoolExecutor pool = newPool();
        try {
            pool.execute(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));

            pool.execute(task(oldestRuns, oldestDrops)); // occupies only queue slot
            pool.execute(task(newestRuns, newestDrops)); // evicts oldest
            assertEquals(1, oldestDrops.get());
            assertEquals(0, oldestRuns.get());
            assertEquals(0, newestDrops.get());

            releaseWorker.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, newestRuns.get());
        } finally {
            releaseWorker.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void rejectedTaskAfterShutdownCleansItsRegistration() {
        ThreadPoolExecutor pool = newPool();
        pool.shutdown();
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger drops = new AtomicInteger();
        pool.execute(task(runs, drops));
        assertEquals(0, runs.get());
        assertEquals(1, drops.get());
    }

    private static ThreadPoolExecutor newPool() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new GeocodeDiscardPolicy());
    }

    private static GeocodeDiscardPolicy.Droppable task(
            AtomicInteger runs, AtomicInteger drops) {
        return new GeocodeDiscardPolicy.Droppable() {
            @Override public void run() { runs.incrementAndGet(); }
            @Override public void onDropped() { drops.incrementAndGet(); }
        };
    }
}
