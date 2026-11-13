package com.overdrive.app.daemon;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class RecordingIndexEventQueueTest {
    private static final class ManualExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean reject;

        @Override public void execute(Runnable action) {
            if (reject) throw new RejectedExecutionException("closed");
            tasks.add(action);
        }

        void runNext() { tasks.remove().run(); }
    }

    private static class Listener implements RecordingIndexEventQueue.Listener {
        final List<String> applied = new ArrayList<>();
        int reconciles;

        @Override public void apply(String path, RecordingIndexEventQueue.Change change) {
            applied.add(path + ":" + change);
        }

        @Override public void reconcile() { reconciles++; }
    }

    @Test public void burstCoalescesByPathAndKeepsLastOperation() {
        ManualExecutor executor = new ManualExecutor();
        Listener listener = new Listener();
        RecordingIndexEventQueue queue = new RecordingIndexEventQueue(executor, listener, 8);
        for (int i = 0; i < 100; i++) {
            queue.submit("/sd/a.mp4", RecordingIndexEventQueue.Change.REFRESH);
        }
        queue.submit("/sd/b.mp4", RecordingIndexEventQueue.Change.REFRESH);
        queue.submit("/sd/a.mp4", RecordingIndexEventQueue.Change.REMOVE);
        assertEquals(1, executor.tasks.size());
        executor.runNext();
        assertEquals(Arrays.asList("/sd/a.mp4:REMOVE", "/sd/b.mp4:REFRESH"),
                listener.applied);
        assertEquals(0, listener.reconciles);
    }

    @Test public void overflowIsBoundedAndTriggersOneRepair() {
        ManualExecutor executor = new ManualExecutor();
        Listener listener = new Listener();
        RecordingIndexEventQueue queue = new RecordingIndexEventQueue(executor, listener, 2);
        queue.submit("/sd/a.mp4", RecordingIndexEventQueue.Change.REFRESH);
        queue.submit("/sd/b.mp4", RecordingIndexEventQueue.Change.REFRESH);
        for (int i = 0; i < 100; i++) {
            queue.submit("/sd/extra" + i + ".mp4", RecordingIndexEventQueue.Change.REFRESH);
        }
        executor.runNext();
        assertEquals(2, listener.applied.size());
        assertEquals(1, listener.reconciles);
        queue.submit("/sd/new.mp4", RecordingIndexEventQueue.Change.REFRESH);
        executor.runNext();
        assertEquals(3, listener.applied.size());
        assertEquals(1, listener.reconciles);
    }

    @Test public void eventWhileWorkerIsApplyingIsNotLost() {
        ManualExecutor executor = new ManualExecutor();
        List<String> operations = new ArrayList<>();
        AtomicInteger reconciles = new AtomicInteger();
        final RecordingIndexEventQueue[] holder = new RecordingIndexEventQueue[1];
        RecordingIndexEventQueue queue = new RecordingIndexEventQueue(executor,
                new RecordingIndexEventQueue.Listener() {
                    @Override public void apply(String path, RecordingIndexEventQueue.Change change) {
                        operations.add(change.name());
                        if (operations.size() == 1) {
                            holder[0].submit(path, RecordingIndexEventQueue.Change.REMOVE);
                        }
                    }
                    @Override public void reconcile() { reconciles.incrementAndGet(); }
                }, 2);
        holder[0] = queue;
        queue.submit("/sd/a.mp4", RecordingIndexEventQueue.Change.REFRESH);
        executor.runNext();
        assertEquals(Arrays.asList("REFRESH", "REMOVE"), operations);
        assertEquals(0, reconciles.get());
        assertEquals(0, executor.tasks.size());
    }

    @Test public void closeDropsPendingAndLateEvents() {
        ManualExecutor executor = new ManualExecutor();
        Listener listener = new Listener();
        RecordingIndexEventQueue queue = new RecordingIndexEventQueue(executor, listener, 2);
        queue.submit("/sd/a.mp4", RecordingIndexEventQueue.Change.REFRESH);
        queue.close();
        queue.submit("/sd/b.mp4", RecordingIndexEventQueue.Change.REMOVE);
        executor.runNext();
        assertEquals(0, listener.applied.size());
        assertEquals(0, listener.reconciles);
    }

    @Test public void rejectedExecutorRequestsRepairAndAllowsNextAttempt() {
        ManualExecutor executor = new ManualExecutor();
        Listener listener = new Listener();
        RecordingIndexEventQueue queue = new RecordingIndexEventQueue(executor, listener, 2);
        executor.reject = true;
        queue.submit("/sd/a.mp4", RecordingIndexEventQueue.Change.REFRESH);
        assertEquals(1, listener.reconciles);
        executor.reject = false;
        queue.submit("/sd/b.mp4", RecordingIndexEventQueue.Change.REFRESH);
        executor.runNext();
        assertEquals(Arrays.asList("/sd/b.mp4:REFRESH"), listener.applied);
    }

    @Test public void failedOperationDoesNotWedgeDrainAndTriggersRepair() {
        ManualExecutor executor = new ManualExecutor();
        Listener listener = new Listener() {
            @Override public void apply(String path, RecordingIndexEventQueue.Change change) {
                if (path.endsWith("a.mp4")) throw new IllegalStateException("FUSE read failed");
                super.apply(path, change);
            }
        };
        RecordingIndexEventQueue queue = new RecordingIndexEventQueue(executor, listener, 2);
        queue.submit("/sd/a.mp4", RecordingIndexEventQueue.Change.REFRESH);
        queue.submit("/sd/b.mp4", RecordingIndexEventQueue.Change.REFRESH);
        executor.runNext();
        assertEquals(Arrays.asList("/sd/b.mp4:REFRESH"), listener.applied);
        assertEquals(1, listener.reconciles);
    }
}
