package com.overdrive.app.ui.view;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Bounded, ordered dispatch for the app's one active mirror. The UI only copies data
 * into this queue; the sender (including a stalled Binder call) always runs on a worker.
 * Consecutive moves may be replaced, but pointer transitions are never reordered.
 */
public final class ClusterTouchDispatcher {
    public interface Sender {
        boolean send(Frame frame);
        void cancel(long sessionId);
    }

    /** Immutable event data copied from a MotionEvent before Android recycles it. */
    public static final class Frame {
        public final long sessionId, eventTime, inputEpoch, layoutGeneration;
        public final int action, actionIndex;
        public final int[] ids;
        public final float[] xs, ys;

        public Frame(long sessionId, long eventTime, int action, int actionIndex,
                     int[] ids, float[] xs, float[] ys) {
            this(sessionId, eventTime, action, actionIndex, ids, xs, ys, 0L);
        }

        public Frame(long sessionId, long eventTime, int action, int actionIndex,
                     int[] ids, float[] xs, float[] ys, long inputEpoch) {
            this(sessionId, eventTime, action, actionIndex, ids, xs, ys, inputEpoch, 0L);
        }

        public Frame(long sessionId, long eventTime, int action, int actionIndex,
                     int[] ids, float[] xs, float[] ys, long inputEpoch, long layoutGeneration) {
            this.sessionId = sessionId;
            this.eventTime = eventTime;
            this.inputEpoch = inputEpoch;
            this.layoutGeneration = layoutGeneration;
            this.action = action;
            this.actionIndex = actionIndex;
            this.ids = ids.clone();
            this.xs = xs.clone();
            this.ys = ys.clone();
        }
    }

    private static final class Work {
        final long sessionId;
        final Frame frame; // null is a cleanup, which never expires.
        Work(long sessionId, Frame frame) { this.sessionId = sessionId; this.frame = frame; }
    }

    private final Object lock = new Object();
    private final ArrayDeque<Work> pending = new ArrayDeque<>();
    private final Executor executor;
    private final LongSupplier clock;
    private final Sender sender;
    private final int capacity;
    private final long maxAgeMs;
    private boolean running;
    private boolean accepting;
    private long acceptingSession;
    private long acceptingEpoch;
    private long acceptingLayoutGeneration;

    public ClusterTouchDispatcher(Executor executor, LongSupplier clock, Sender sender,
                                  int capacity, long maxAgeMs) {
        if (capacity < 2 || maxAgeMs <= 0) throw new IllegalArgumentException();
        this.executor = executor;
        this.clock = clock;
        this.sender = sender;
        this.capacity = capacity;
        this.maxAgeMs = maxAgeMs;
    }

    public boolean offer(Frame frame) {
        boolean accepted;
        boolean start;
        synchronized (lock) {
            if (frame.action == 0) {
                if (accepting) abortLocked(acceptingSession);
                acceptingSession = frame.sessionId;
                acceptingEpoch = frame.inputEpoch;
                acceptingLayoutGeneration = frame.layoutGeneration;
                accepting = true;
            } else if (!accepting || acceptingSession != frame.sessionId
                    || acceptingEpoch != frame.inputEpoch
                    || acceptingLayoutGeneration != frame.layoutGeneration) {
                return false;
            }
            Work last = pending.peekLast();
            if (frame.action == 2 && last != null && last.frame != null
                    && last.sessionId == frame.sessionId && last.frame.action == 2
                    && last.frame.inputEpoch == frame.inputEpoch
                    && last.frame.layoutGeneration == frame.layoutGeneration) {
                pending.removeLast();
                pending.addLast(new Work(frame.sessionId, frame));
                accepted = true;
            } else if (pending.size() >= capacity) {
                // Drop the complete pending stream, including any queued UP. CANCEL
                // closes the in-flight gesture once the transport becomes responsive.
                abortLocked(frame.sessionId);
                accepted = false;
            } else {
                pending.addLast(new Work(frame.sessionId, frame));
                accepted = true;
            }
            if (frame.action == 1 || frame.action == 3) accepting = false;
            start = !running;
            if (start) running = true;
        }
        if (start) startWorker();
        return accepted;
    }

    public void cancel(long sessionId) {
        boolean start;
        synchronized (lock) {
            abortLocked(sessionId);
            start = !running;
            if (start) running = true;
        }
        if (start) startWorker();
    }

    private void abortLocked(long sessionId) {
        abortLocked(sessionId, 0L, 0L, false);
    }

    private void abortLocked(long sessionId, long epoch, long layoutGeneration, boolean scoped) {
        if (acceptingSession == sessionId && (!scoped
                || (acceptingEpoch == epoch && acceptingLayoutGeneration == layoutGeneration))) accepting = false;
        pending.removeIf(work -> work.sessionId == sessionId
                && (!scoped || work.frame == null || (work.frame.inputEpoch == epoch
                    && work.frame.layoutGeneration == layoutGeneration)));
        if (pending.size() >= capacity) {
            // Other session IDs belong to mirrors already superseded/detached. Their
            // daemon lifecycle has its own CANCEL; they must not fill this queue.
            pending.clear();
            accepting = false;
        }
        pending.addFirst(new Work(sessionId, null));
    }

    private void startWorker() {
        try {
            executor.execute(this::drain);
        } catch (RuntimeException rejected) {
            synchronized (lock) {
                pending.clear();
                accepting = false;
                running = false;
            }
        }
    }

    private void drain() {
        while (true) {
            Work work;
            synchronized (lock) {
                work = pending.pollFirst();
                if (work == null) {
                    running = false;
                    return;
                }
            }
            if (work.frame == null) {
                try { sender.cancel(work.sessionId); } catch (RuntimeException ignored) {}
                continue;
            }
            boolean sent = false;
            long age = clock.getAsLong() - work.frame.eventTime;
            if (age >= -50L && age <= maxAgeMs) {
                try { sent = sender.send(work.frame); } catch (RuntimeException ignored) {}
            }
            if (!sent) {
                synchronized (lock) {
                    // A rejected/stale frame cannot become a delayed new gesture. Drop
                    // later frames for this session, then clean up before a future DOWN.
                    abortLocked(work.sessionId, work.frame.inputEpoch, work.frame.layoutGeneration, true);
                }
            }
        }
    }
}
