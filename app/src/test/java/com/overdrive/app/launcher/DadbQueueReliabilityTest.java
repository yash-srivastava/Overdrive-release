package com.overdrive.app.launcher;

import dadb.MessageQueue;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the dependency's failure paths that our shared ADB connection relies on:
 * a peer waiting for stream data must finish when the reader fails or the connection closes.
 */
public class DadbQueueReliabilityTest {
    private static final int COMMAND = 42;

    @Test
    public void readerFailureWakesOtherStreamWaiter() throws Exception {
        BlockingQueue queue = new BlockingQueue();
        queue.startListening(1);
        queue.startListening(2);
        Waiter reader = new Waiter(queue, 1);
        Waiter peer = new Waiter(queue, 2);
        try {
            reader.start();
            assertTrue("reader never entered the socket read",
                    queue.readerEntered.await(2, TimeUnit.SECONDS));
            peer.start();
            awaitParked(peer.thread);
            queue.readGate.countDown();
            assertTrue("failed reader stayed blocked", reader.done.await(2, TimeUnit.SECONDS));
            assertTrue("peer was stranded after the reader failed",
                    peer.done.await(2, TimeUnit.SECONDS));
            assertTrue(reader.failure.get() instanceof IllegalStateException);
            assertTrue(peer.failure.get() instanceof IllegalStateException);
        } finally {
            queue.readGate.countDown();
            reader.stop();
            peer.stop();
        }
    }

    @Test
    public void connectionCloseWakesOtherStreamBeforeSocketReadFinishes() throws Exception {
        BlockingQueue queue = new BlockingQueue();
        queue.startListening(1);
        queue.startListening(2);
        Waiter reader = new Waiter(queue, 1);
        Waiter peer = new Waiter(queue, 2);
        try {
            reader.start();
            assertTrue(queue.readerEntered.await(2, TimeUnit.SECONDS));
            peer.start();
            awaitParked(peer.thread);
            queue.closeConnection();
            assertTrue("peer was stranded after connection close",
                    peer.done.await(2, TimeUnit.SECONDS));
            assertTrue(peer.failure.get() instanceof IOException);
            assertFalse("socket reader should still be blocked", reader.done.await(50, TimeUnit.MILLISECONDS));
        } finally {
            queue.readGate.countDown();
            reader.stop();
            peer.stop();
        }
    }

    private static void awaitParked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.WAITING
                    || thread.getState() == Thread.State.TIMED_WAITING) return;
            Thread.sleep(5);
        }
        throw new AssertionError("peer never parked waiting for stream data: " + thread.getState());
    }

    private static final class BlockingQueue extends MessageQueue<Integer> {
        final CountDownLatch readerEntered = new CountDownLatch(1);
        final CountDownLatch readGate = new CountDownLatch(1);

        void closeConnection() {
            signalClosed(new IOException("simulated connection close"));
        }

        @Override protected Integer readMessage() {
            readerEntered.countDown();
            try {
                readGate.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("simulated socket reader failure");
        }

        @Override protected int getLocalId(Integer message) { return message; }
        @Override protected int getCommand(Integer message) { return COMMAND; }
        @Override protected boolean isCloseCommand(Integer message) { return false; }
    }

    private static final class Waiter {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final Thread thread;

        Waiter(BlockingQueue queue, int streamId) {
            thread = new Thread(() -> {
                try {
                    queue.take(streamId, COMMAND);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    done.countDown();
                }
            }, "DadbQueueTest-" + streamId);
            thread.setDaemon(true);
        }

        void start() { thread.start(); }

        void stop() throws InterruptedException {
            thread.interrupt();
            thread.join(1_000);
        }
    }
}
