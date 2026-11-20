package com.overdrive.app.camera;

import java.util.function.BooleanSupplier;

/**
 * Single-flight, asynchronous ACC-on owner query. The caller observes the
 * result without waiting in Binder; a wedged vendor transaction cannot block
 * the reopen thread or spawn unbounded replacements. This does not cancel
 * Binder (an interrupt is not a reliable Binder cancellation mechanism).
 */
final class BoundedAccOwnerProbe {
    enum Result { PENDING, OWNER, CLEAR, UNKNOWN }

    static final class Ticket {
        private volatile Result result = Result.PENDING;

        Result result() { return result; }
    }

    private final BooleanSupplier query;
    private final String threadName;
    private Ticket last;

    BoundedAccOwnerProbe(BooleanSupplier query, String threadName) {
        this.query = query;
        this.threadName = threadName;
    }

    /** null means the preceding Binder transaction has not returned yet. */
    synchronized Ticket startIfIdle() {
        if (last != null && last.result() == Result.PENDING) return null;
        Ticket ticket = new Ticket();
        last = ticket;
        Thread worker = new Thread(() -> {
            Result outcome;
            try {
                outcome = query.getAsBoolean() ? Result.OWNER : Result.CLEAR;
            } catch (Throwable t) {
                // A firmware LinkageError must not strand the slot forever.
                outcome = Result.UNKNOWN;
            }
            ticket.result = outcome;
        }, threadName);
        worker.setDaemon(true);
        worker.start();
        return ticket;
    }
}
