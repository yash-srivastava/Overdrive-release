package com.overdrive.app.byd;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** One request's cancellation ticket, including cancellation before its remote worker registers. */
public final class LocalActuationCancellation {
    private boolean cancelled;
    private final List<Runnable> remoteCancellations = new ArrayList<>();

    /** Older callers expect one final reply and cannot consume a registration reply. */
    public static boolean registrationAllowed(boolean genericFrame, boolean callerOptedIn) {
        return genericFrame && callerOptedIn;
    }

    public synchronized boolean isCancelled() { return cancelled; }

    /** Linearizes a final setter with cancellation for this one request. */
    public synchronized <T> T callIfActive(Callable<T> action, T cancelledResult)
            throws Exception {
        if (cancelled || action == null) return cancelledResult;
        return action.call();
    }

    public void onRemoteCancellation(Runnable cancelRemote) {
        boolean alreadyCancelled;
        synchronized (this) {
            alreadyCancelled = cancelled;
            if (!alreadyCancelled && cancelRemote != null) remoteCancellations.add(cancelRemote);
        }
        if (alreadyCancelled) runCancellation(cancelRemote);
    }

    public void cancel() {
        List<Runnable> cancelRemote;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            cancelRemote = new ArrayList<>(remoteCancellations);
            remoteCancellations.clear();
        }
        for (Runnable cancellation : cancelRemote) runCancellation(cancellation);
    }

    private static void runCancellation(Runnable cancelRemote) {
        if (cancelRemote == null) return;
        try {
            cancelRemote.run();
        } catch (RuntimeException unavailable) {
            // The ticket stays cancelled if the remote process has already gone away.
        }
    }
}
