package com.overdrive.app.ui.view;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ClusterTouchDispatcherTest {
    private static final class ManualExecutor implements Executor {
        Runnable task;
        public void execute(Runnable runnable) { task = runnable; }
        void drain() { Runnable current = task; task = null; current.run(); }
    }

    private static ClusterTouchDispatcher.Frame frame(long time, int action) {
        return new ClusterTouchDispatcher.Frame(42, time, action, 0,
                new int[] {7}, new float[] {time}, new float[] {2});
    }

    @Test public void coalescesMovesWhilePreservingPointerTransitions() {
        ManualExecutor worker = new ManualExecutor();
        List<Integer> actions = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        ClusterTouchDispatcher dispatcher = new ClusterTouchDispatcher(worker, () -> 100,
                new ClusterTouchDispatcher.Sender() {
                    public boolean send(ClusterTouchDispatcher.Frame f) {
                        actions.add(f.action); times.add(f.eventTime); return true;
                    }
                    public void cancel(long session) { fail("unexpected cancel"); }
                }, 8, 250);
        for (int[] value : new int[][] {{0,0}, {1,5}, {2,2}, {3,2}, {4,6}, {5,2}, {6,1}}) {
            assertTrue(dispatcher.offer(frame(value[0], value[1])));
        }
        worker.drain();
        assertEquals(Arrays.asList(0, 5, 2, 6, 2, 1), actions);
        assertEquals(Arrays.asList(0L, 1L, 3L, 4L, 5L, 6L), times);
    }

    @Test public void staleGestureIsCancelledInsteadOfReplayed() {
        ManualExecutor worker = new ManualExecutor();
        List<Long> cancelled = new ArrayList<>();
        ClusterTouchDispatcher dispatcher = new ClusterTouchDispatcher(worker, () -> 1000,
                new ClusterTouchDispatcher.Sender() {
                    public boolean send(ClusterTouchDispatcher.Frame f) {
                        fail("stale gesture replayed"); return false;
                    }
                    public void cancel(long session) { cancelled.add(session); }
                }, 8, 250);
        assertTrue(dispatcher.offer(frame(500, 0)));
        assertTrue(dispatcher.offer(frame(600, 2)));
        assertTrue(dispatcher.offer(frame(700, 1)));
        worker.drain();
        assertEquals(Arrays.asList(42L), cancelled);
        assertFalse(dispatcher.offer(frame(1000, 2)));
    }

    @Test public void overflowCancelsWholeGestureAndFreshDownCanRecover() {
        ManualExecutor worker = new ManualExecutor();
        List<Integer> delivered = new ArrayList<>();
        ClusterTouchDispatcher dispatcher = new ClusterTouchDispatcher(worker, () -> 10,
                new ClusterTouchDispatcher.Sender() {
                    public boolean send(ClusterTouchDispatcher.Frame f) {
                        delivered.add(f.action); return true;
                    }
                    public void cancel(long session) { delivered.add(3); }
                }, 3, 250);
        assertTrue(dispatcher.offer(frame(0, 0)));
        assertTrue(dispatcher.offer(frame(1, 5)));
        assertTrue(dispatcher.offer(frame(2, 5)));
        assertFalse(dispatcher.offer(frame(3, 6)));
        assertFalse(dispatcher.offer(frame(4, 2)));
        assertTrue(dispatcher.offer(frame(5, 0)));
        assertTrue(dispatcher.offer(frame(6, 1)));
        worker.drain();
        assertEquals(Arrays.asList(3, 0, 1), delivered);
    }

    @Test public void senderRejectionDropsRemainingFrames() {
        ManualExecutor worker = new ManualExecutor();
        List<Integer> delivered = new ArrayList<>();
        ClusterTouchDispatcher dispatcher = new ClusterTouchDispatcher(worker, () -> 10,
                new ClusterTouchDispatcher.Sender() {
                    public boolean send(ClusterTouchDispatcher.Frame f) {
                        delivered.add(f.action); return false;
                    }
                    public void cancel(long session) { delivered.add(3); }
                }, 8, 250);
        assertTrue(dispatcher.offer(frame(0, 0)));
        assertTrue(dispatcher.offer(frame(1, 2)));
        assertTrue(dispatcher.offer(frame(2, 1)));
        worker.drain();
        assertEquals(Arrays.asList(0, 3), delivered);
        assertFalse(dispatcher.offer(frame(10, 2)));
    }

    @Test public void copiesPointerArraysBeforeFrameworkCanReuseThem() {
        int[] ids = {7};
        float[] xs = {3}, ys = {4};
        ClusterTouchDispatcher.Frame f =
                new ClusterTouchDispatcher.Frame(42, 10, 0, 0, ids, xs, ys);
        ids[0] = 1; xs[0] = 99; ys[0] = 99;
        assertArrayEquals(new int[] {7}, f.ids);
        assertArrayEquals(new float[] {3}, f.xs, 0);
        assertArrayEquals(new float[] {4}, f.ys, 0);
    }

    @Test public void rejectedOldAttachmentDoesNotReplayOrDiscardFreshAttachment() {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong epoch = new AtomicLong(7);
        List<String> delivered = new ArrayList<>();
        ClusterTouchDispatcher dispatcher = new ClusterTouchDispatcher(worker, () -> 10,
                new ClusterTouchDispatcher.Sender() {
                    public boolean send(ClusterTouchDispatcher.Frame f) {
                        if (f.inputEpoch != epoch.get()) return false;
                        delivered.add(f.inputEpoch + ":" + f.action);
                        return true;
                    }
                    public void cancel(long session) { delivered.add("cancel"); }
                }, 8, 250);
        assertTrue(dispatcher.offer(new ClusterTouchDispatcher.Frame(42, 10, 0, 0,
                new int[] {7}, new float[] {1}, new float[] {1}, 7)));
        assertTrue(dispatcher.offer(new ClusterTouchDispatcher.Frame(42, 10, 1, 0,
                new int[] {7}, new float[] {1}, new float[] {1}, 7)));
        // The Fragment retains session id 42, but the mirror was detached/reattached.
        epoch.set(8);
        assertTrue(dispatcher.offer(new ClusterTouchDispatcher.Frame(42, 10, 0, 0,
                new int[] {7}, new float[] {2}, new float[] {2}, 8)));
        assertTrue(dispatcher.offer(new ClusterTouchDispatcher.Frame(42, 10, 1, 0,
                new int[] {7}, new float[] {2}, new float[] {2}, 8)));
        worker.drain();
        assertEquals(Arrays.asList("cancel", "8:0", "8:1"), delivered);
    }

    @Test public void stalledSenderDoesNotBlockUiEnqueueOrCancel() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService simulatedUi = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicLong now = new AtomicLong(10);
        try {
            ClusterTouchDispatcher dispatcher = new ClusterTouchDispatcher(worker, now::get,
                    new ClusterTouchDispatcher.Sender() {
                        public boolean send(ClusterTouchDispatcher.Frame f) {
                            entered.countDown();
                            try { release.await(5, TimeUnit.SECONDS); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            return true;
                        }
                        public void cancel(long session) { cancelled.countDown(); }
                    }, 8, 250);
            assertTrue(dispatcher.offer(frame(10, 0)));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(simulatedUi.submit(() -> {
                boolean accepted = dispatcher.offer(frame(10, 2));
                dispatcher.cancel(42);
                return accepted;
            }).get(1, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            simulatedUi.shutdownNow();
            worker.shutdownNow();
        }
    }

    @Test public void replacedLayoutDropsOldGestureAndKeepsNewDownOnSameAttachment() {
        ManualExecutor worker = new ManualExecutor();
        AtomicLong layoutGeneration = new AtomicLong(11L);
        List<String> delivered = new ArrayList<>();
        ClusterTouchDispatcher dispatcher = new ClusterTouchDispatcher(worker, () -> 10L,
                new ClusterTouchDispatcher.Sender() {
                    public boolean send(ClusterTouchDispatcher.Frame f) {
                        if (f.layoutGeneration != layoutGeneration.get()) return false;
                        delivered.add(f.layoutGeneration + ":" + f.action);
                        return true;
                    }
                    public void cancel(long session) { delivered.add("cancel"); }
                }, 8, 250L);
        assertTrue(dispatcher.offer(layoutFrame(11L, 0)));
        assertTrue(dispatcher.offer(layoutFrame(11L, 1)));
        layoutGeneration.set(12L);
        assertTrue(dispatcher.offer(layoutFrame(12L, 0)));
        assertFalse(dispatcher.offer(layoutFrame(11L, 2)));
        assertTrue(dispatcher.offer(layoutFrame(12L, 1)));
        worker.drain();
        assertEquals(Arrays.asList("cancel", "12:0", "12:1"), delivered);
    }

    private static ClusterTouchDispatcher.Frame layoutFrame(long generation, int action) {
        return new ClusterTouchDispatcher.Frame(42, 10, action, 0,
                new int[] {7}, new float[] {2}, new float[] {2}, 3, generation);
    }
}
