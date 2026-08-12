package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CoalescingTaskRunnerTest {

    @Test
    public void burstBeforeExecutionSchedulesOnePass() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger runs = new AtomicInteger();
        CoalescingTaskRunner runner = new CoalescingTaskRunner(executor, runs::incrementAndGet);

        for (int i = 0; i < 100; i++) runner.request();

        assertEquals(1, executor.size());
        executor.runNext();
        assertEquals(1, runs.get());
        assertEquals(0, executor.size());
    }

    @Test
    public void requestsDuringPassBecomeOneFollowUpWithoutOverlap() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicReference<CoalescingTaskRunner> runnerRef = new AtomicReference<>();
        CoalescingTaskRunner runner = new CoalescingTaskRunner(executor, () -> {
            int nowActive = active.incrementAndGet();
            peak.accumulateAndGet(nowActive, Math::max);
            int pass = runs.incrementAndGet();
            if (pass == 1) {
                for (int i = 0; i < 100; i++) runnerRef.get().request();
            }
            active.decrementAndGet();
        });
        runnerRef.set(runner);

        runner.request();
        executor.runNext();

        assertEquals(2, runs.get());
        assertEquals(1, peak.get());
        assertEquals(0, executor.size());
    }

    @Test
    public void rejectedScheduleCanBeRetried() {
        ManualExecutor executor = new ManualExecutor();
        executor.reject = true;
        AtomicInteger runs = new AtomicInteger();
        CoalescingTaskRunner runner = new CoalescingTaskRunner(executor, runs::incrementAndGet);

        try {
            runner.request();
        } catch (RejectedExecutionException expected) {
            // Expected: caller owns logging; the runner must reset its gate.
        }
        executor.reject = false;
        runner.request();
        executor.runNext();

        assertEquals(1, runs.get());
    }

    private static final class ManualExecutor implements java.util.concurrent.Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean reject;

        @Override
        public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("test rejection");
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            Runnable next = tasks.remove();
            next.run();
        }
    }
}