package com.overdrive.app.server;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ThumbnailWorkQueueTest {
    @Test
    public void boundsOutstandingWorkAndAllowsRetry() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        ThumbnailWorkQueue queue = new ThumbnailWorkQueue(2, jobs::addLast);
        AtomicInteger completed = new AtomicInteger();
        assertTrue(queue.submit("a", () -> {
            // Running work still owns its slot and key.
            assertFalse(queue.submit("a", completed::incrementAndGet));
            assertFalse(queue.submit("c", completed::incrementAndGet));
            completed.incrementAndGet();
        }));
        assertFalse(queue.submit("a", completed::incrementAndGet));
        assertTrue(queue.submit("b", completed::incrementAndGet));
        assertFalse(queue.submit("c", completed::incrementAndGet));
        assertEquals(2, jobs.size());
        jobs.removeFirst().run();
        assertTrue(queue.submit("c", completed::incrementAndGet));
        jobs.removeFirst().run();
        jobs.removeFirst().run();
        assertEquals(3, completed.get());
        assertTrue(queue.submit("a", completed::incrementAndGet));
    }

    @Test
    public void failedJobReleasesItsSlotAndKey() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        ThumbnailWorkQueue queue = new ThumbnailWorkQueue(1, jobs::addLast);
        assertTrue(queue.submit("a", () -> { throw new IllegalStateException("decode failed"); }));
        try {
            jobs.removeFirst().run();
            fail("Expected decode failure");
        } catch (IllegalStateException expected) {
            assertEquals("decode failed", expected.getMessage());
        }
        assertTrue(queue.submit("a", () -> {}));
    }

    @Test
    public void rejectedSubmissionDoesNotReserveKey() {
        AtomicBoolean reject = new AtomicBoolean(true);
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        ThumbnailWorkQueue queue = new ThumbnailWorkQueue(1, job -> {
            if (reject.get()) throw new RejectedExecutionException();
            jobs.addLast(job);
        });
        assertFalse(queue.submit("a", () -> {}));
        reject.set(false);
        assertTrue(queue.submit("a", () -> {}));
        assertEquals(1, jobs.size());
    }
}
