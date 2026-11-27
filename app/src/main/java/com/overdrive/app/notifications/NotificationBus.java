package com.overdrive.app.notifications;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-process pub/sub for {@link NotificationEvent}s.
 *
 * <p>v1 is in-process only (publishers and sinks both live in CameraDaemon).
 * If a future emit source lives in another process, an
 * {@code NotificationIpcServer} can sit in front of this and forward.
 */
public final class NotificationBus {

    public interface Sink {
        void onNotification(NotificationEvent event);
    }

    private static final NotificationBus INSTANCE = new NotificationBus();

    /**
     * Max events buffered while the notifications subsystem is still coming up
     * (the startup window between the vehicle notifiers starting and the async
     * notifications-init subscribing HistorySink/PushSink/TelegramSink). A real
     * hardware edge (charging fault, door opened) in that window would otherwise
     * be dropped silently — upstream of the IPC spool, so daemon-down spooling
     * can't recover it. Small + bounded (these are rare boot-window edges);
     * every buffered event is FLUSHED to EVERY subscribed sink by
     * {@link #sealPreSubscribeBuffer()} once init has wired up all sinks, so all
     * delivery paths (push, Telegram, history, log) see it — not just the first
     * sink to subscribe.
     */
    private static final int PRESUBSCRIBE_BUFFER_MAX = 16;

    private final CopyOnWriteArrayList<Sink> sinks = new CopyOnWriteArrayList<>();
    // Guarded by itself. Holds events published before the subsystem was sealed
    // (i.e. before initNotifications finished subscribing every sink). Flushed
    // to all sinks — once each — by sealPreSubscribeBuffer(), then stays empty.
    private final java.util.ArrayDeque<NotificationEvent> preSubscribeBuffer =
            new java.util.ArrayDeque<>();
    // Until sealed, publish() BUFFERS (does not dispatch) so that whether an
    // event is buffered-then-flushed or dispatched live is decided atomically
    // under the buffer lock at the seal boundary — an event is delivered via
    // exactly one path, never both (no dup) and never neither (no loss). Set
    // once on the init thread, read on every publish() (any emitter thread).
    private volatile boolean sealed = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NotificationBus");
        t.setDaemon(true);
        return t;
    });

    private NotificationBus() {}

    public static NotificationBus get() { return INSTANCE; }

    public void subscribe(Sink sink) {
        if (sink == null || sinks.contains(sink)) return;
        sinks.add(sink);
        // No replay here: boot-window events stay buffered until seal, which
        // flushes them to EVERY sink at once. Replaying per-subscribe instead
        // would race a concurrent publish (emit sources run on other threads
        // during the init burst) and double-deliver — the buffered event could
        // reach a sink both live and via replay. Seal-time flush avoids that
        // entirely (see sealPreSubscribeBuffer / publish).
    }

    public void unsubscribe(Sink sink) {
        sinks.remove(sink);
    }

    /**
     * Marks the pre-subscribe window closed. Under the buffer lock: flips the
     * bus to live-only dispatch and drains the buffered boot-window events; each
     * drained event is then fanned out to every currently-subscribed sink (once
     * each). Called by {@code CameraDaemon.initNotifications()} after all sinks
     * (History, Log, Push, Telegram) are subscribed.
     *
     * <p>Delivery is single-path by construction: an event published BEFORE the
     * seal grabbed the lock was appended to the buffer and is flushed here; one
     * published AFTER re-reads {@code sealed==true} and dispatches live. The
     * lock makes the boundary atomic, so no event is delivered twice or zero
     * times. Idempotent — a second call finds the buffer empty.
     */
    public void sealPreSubscribeBuffer() {
        java.util.List<NotificationEvent> toFlush = null;
        synchronized (preSubscribeBuffer) {
            sealed = true;
            if (!preSubscribeBuffer.isEmpty()) {
                toFlush = new java.util.ArrayList<>(preSubscribeBuffer);
                preSubscribeBuffer.clear();
            }
        }
        if (toFlush != null) {
            for (NotificationEvent e : toFlush) dispatchLive(e);
        }
    }

    public void publish(NotificationEvent event) {
        if (event == null) return;
        // Before seal, BUFFER (bounded) instead of dispatching, so a boot-window
        // hardware edge that fires before all sinks are wired isn't lost — it's
        // flushed to every sink at seal. The re-check under the lock closes the
        // race with sealPreSubscribeBuffer(): if seal won the lock first, we
        // fall through and dispatch live rather than appending to a buffer that
        // will never be flushed again.
        if (!sealed) {
            synchronized (preSubscribeBuffer) {
                if (!sealed) {
                    if (preSubscribeBuffer.size() >= PRESUBSCRIBE_BUFFER_MAX) {
                        NotificationEvent dropped = preSubscribeBuffer.pollFirst();
                        System.err.println("NotificationBus: pre-subscribe buffer full, dropped oldest: "
                                + (dropped != null ? dropped.category : "?"));
                    }
                    preSubscribeBuffer.addLast(event);
                    return;
                }
            }
        }
        dispatchLive(event);
    }

    /** Fan an event out to all currently-subscribed sinks on the bus executor. */
    private void dispatchLive(NotificationEvent event) {
        if (sinks.isEmpty()) return;
        try {
            executor.execute(() -> {
                for (Sink s : sinks) {
                    try {
                        s.onNotification(event);
                    } catch (Throwable t) {
                        // never let one sink kill the others
                        System.err.println("NotificationBus: sink error: " + t.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            // RejectedExecutionException at shutdown
            System.err.println("NotificationBus: publish failed: " + t.getMessage());
        }
    }
}
