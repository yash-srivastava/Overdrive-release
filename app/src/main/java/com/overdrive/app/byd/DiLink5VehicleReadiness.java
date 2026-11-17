package com.overdrive.app.byd;

/** Bounded adapter acquisition and final admission for one DiLink 5 SDK mutation. */
public final class DiLink5VehicleReadiness {

    public enum Outcome {
        ACCEPTED,
        REJECTED,
        NOT_DISPATCHED,
        UNCERTAIN
    }

    @FunctionalInterface
    public interface Clock {
        long elapsedRealtime();
    }

    @FunctionalInterface
    public interface Cancellation {
        boolean isCancelled();
    }

    @FunctionalInterface
    public interface CurrentCheck {
        boolean isCurrent();
    }

    @FunctionalInterface
    public interface AdapterProbe<T> {
        T acquire();
    }

    @FunctionalInterface
    public interface AdapterCheck<T> {
        boolean isReady(T adapter);
    }

    @FunctionalInterface
    public interface SafetyCheck {
        boolean isSafe();
    }

    @FunctionalInterface
    public interface FinalPermit {
        boolean allow();
    }

    @FunctionalInterface
    public interface Setter<T> {
        Outcome dispatch(T adapter) throws Exception;
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis);
    }

    private DiLink5VehicleReadiness() {}

    public static <T> T awaitAdapter(
            int maxAttempts,
            long retryDelayMs,
            long deadlineElapsedMs,
            Clock clock,
            Cancellation cancellation,
            CurrentCheck current,
            AdapterProbe<T> probe,
            AdapterCheck<T> ready,
            Sleeper sleeper) {
        if (maxAttempts <= 0 || retryDelayMs < 0L || clock == null
                || cancellation == null || current == null || probe == null
                || ready == null || sleeper == null) {
            return null;
        }
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (!requestIsCurrent(deadlineElapsedMs, clock, cancellation, current)) {
                return null;
            }
            T adapter = null;
            try {
                adapter = probe.acquire();
            } catch (Throwable ignored) {
            }
            if (adapter != null && adapterIsReady(ready, adapter)
                    && requestIsCurrent(deadlineElapsedMs, clock, cancellation, current)) {
                return adapter;
            }
            if (attempt + 1 < maxAttempts) {
                try {
                    sleeper.sleep(retryDelayMs);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public static <T> Outcome awaitAndDispatch(
            int maxAttempts,
            long retryDelayMs,
            long deadlineElapsedMs,
            Clock clock,
            Cancellation cancellation,
            CurrentCheck current,
            AdapterProbe<T> probe,
            AdapterCheck<T> ready,
            SafetyCheck safety,
            FinalPermit finalPermit,
            Setter<T> setter,
            Sleeper sleeper) {
        if (safety == null || finalPermit == null || setter == null) {
            return Outcome.NOT_DISPATCHED;
        }
        T adapter = awaitAdapter(
                maxAttempts, retryDelayMs, deadlineElapsedMs, clock, cancellation,
                current, probe, ready, sleeper);
        if (adapter == null
                || !requestIsCurrent(deadlineElapsedMs, clock, cancellation, current)
                || !adapterIsReady(ready, adapter)
                || !safetyIsCurrent(safety)) {
            return Outcome.NOT_DISPATCHED;
        }
        try {
            if (!finalPermit.allow()) return Outcome.NOT_DISPATCHED;
        } catch (Throwable denied) {
            return Outcome.NOT_DISPATCHED;
        }
        if (!requestIsCurrent(deadlineElapsedMs, clock, cancellation, current)
                || !adapterIsReady(ready, adapter)
                || !safetyIsCurrent(safety)) {
            return Outcome.NOT_DISPATCHED;
        }
        try {
            Outcome outcome = setter.dispatch(adapter);
            return outcome == Outcome.ACCEPTED || outcome == Outcome.REJECTED
                    ? outcome : Outcome.UNCERTAIN;
        } catch (Throwable uncertain) {
            return Outcome.UNCERTAIN;
        }
    }

    private static boolean requestIsCurrent(
            long deadlineElapsedMs,
            Clock clock,
            Cancellation cancellation,
            CurrentCheck current) {
        try {
            return !cancellation.isCancelled()
                    && (deadlineElapsedMs <= 0L
                            || clock.elapsedRealtime() < deadlineElapsedMs)
                    && current.isCurrent();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static <T> boolean adapterIsReady(AdapterCheck<T> ready, T adapter) {
        try {
            return ready.isReady(adapter);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static boolean safetyIsCurrent(SafetyCheck safety) {
        try {
            return safety.isSafe();
        } catch (Throwable unavailable) {
            return false;
        }
    }
}
