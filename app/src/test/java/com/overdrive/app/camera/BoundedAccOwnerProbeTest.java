package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Executes real worker scheduling and reentry, without needing a BYD Binder. */
public class BoundedAccOwnerProbeTest {

    @Test
    public void ownerAndClearQueriesRunAndCompleteOnTheWorker() throws Exception {
        AtomicInteger count = new AtomicInteger();
        BoundedAccOwnerProbe probe = new BoundedAccOwnerProbe(
                () -> count.getAndIncrement() == 0, "OwnerProbeTest");
        assertEquals(BoundedAccOwnerProbe.Result.OWNER,
                await(probe.startIfIdle()));
        assertEquals(BoundedAccOwnerProbe.Result.CLEAR,
                await(probe.startIfIdle()));
        assertEquals(2, count.get());
    }

    @Test
    public void aStuckBinderNeverSpawnsAConcurrentReplacementOnRerun()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BoundedAccOwnerProbe probe = new BoundedAccOwnerProbe(() -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        }, "StuckBinderTest");
        BoundedAccOwnerProbe.Ticket first = probe.startIfIdle();
        assertNotNull(first);
        assertTrue("worker did not enter", entered.await(2, TimeUnit.SECONDS));
        try {
            assertEquals(BoundedAccOwnerProbe.Result.PENDING, first.result());
            assertNull("a second ACC handoff must not overlap Binder",
                    probe.startIfIdle());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
        }
        assertEquals(BoundedAccOwnerProbe.Result.CLEAR, await(first));
        assertEquals(BoundedAccOwnerProbe.Result.CLEAR,
                await(probe.startIfIdle()));
        assertEquals(2, calls.get());
    }

    @Test
    public void linkageFailureReturnsUnknownAndReleasesTheSingleFlightSlot()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BoundedAccOwnerProbe probe = new BoundedAccOwnerProbe(() -> {
            if (calls.getAndIncrement() == 0) throw new NoSuchMethodError("OEM API mismatch");
            return true;
        }, "LinkageFailureTest");
        assertEquals(BoundedAccOwnerProbe.Result.UNKNOWN,
                await(probe.startIfIdle()));
        assertEquals(BoundedAccOwnerProbe.Result.OWNER,
                await(probe.startIfIdle()));
    }

    private static BoundedAccOwnerProbe.Result await(BoundedAccOwnerProbe.Ticket ticket)
            throws Exception {
        assertNotNull(ticket);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (ticket.result() == BoundedAccOwnerProbe.Result.PENDING
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return ticket.result();
    }
}
