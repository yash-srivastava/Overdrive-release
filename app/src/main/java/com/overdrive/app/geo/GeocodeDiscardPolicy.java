package com.overdrive.app.geo;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Keep recent asynchronous geocoding requests without retaining the callback
 * registrations of queued tasks that will never run.
 *
 * <p>{@link ThreadPoolExecutor.DiscardOldestPolicy} removes an old Runnable
 * silently. A removed geocoding task must instead clear its entry in the
 * in-flight de-duplication map, allowing a later call to retry that location.
 */
final class GeocodeDiscardPolicy implements RejectedExecutionHandler {

    interface Droppable extends Runnable {
        /** Called only when this task cannot run because it was discarded. */
        void onDropped();
    }

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
        if (pool.isShutdown()) {
            notifyDropped(task);
            return;
        }

        // Under saturation prefer the newest lookup. A worker can free a
        // queue slot between execute() and this handler; in that case the
        // poll may return null and offering the new task is still safe.
        Runnable oldest = pool.getQueue().poll();
        if (oldest != null) notifyDropped(oldest);

        // Other submitters can refill the slot before our offer. Never leave
        // an unqueued task registered as in-flight, even under that race.
        if (!pool.getQueue().offer(task)) {
            notifyDropped(task);
        } else if (pool.isShutdown() && pool.remove(task)) {
            // A shutdown raced with our direct queue insertion. If the task
            // has not started, remove its now-orphaned registration too.
            notifyDropped(task);
        }
    }

    private static void notifyDropped(Runnable task) {
        if (task instanceof Droppable) {
            ((Droppable) task).onDropped();
        }
    }
}
