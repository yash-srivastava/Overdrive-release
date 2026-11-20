package com.overdrive.app.camera;

import java.util.function.BooleanSupplier;

/** Controls the DiLink 3 vendor open and its one permitted retry. */
final class LegacyAccOpenRetry {
    enum Result { OPENED, FAILED, TERMINAL_TIMEOUT }

    interface OpenAttempt {
        /** False means a vendor open remains in flight and must not be retried. */
        boolean open(String phase) throws Exception;
    }

    interface ErrorReporter {
        void report(String phase, Exception failure);
    }

    private boolean ran;

    boolean canRetryInCatch() {
        return !ran;
    }

    Result run(OpenAttempt attempt, BooleanSupplier mayRetry,
            ErrorReporter reporter) {
        ran = true;
        try {
            if (!attempt.open("ACC ON camera reopen")) return Result.TERMINAL_TIMEOUT;
            return Result.OPENED;
        } catch (Exception first) {
            reporter.report("Failed to reopen camera", first);
            if (first instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return Result.FAILED;
            }
        }
        if (!mayRetry.getAsBoolean()) return Result.FAILED;
        try {
            if (!attempt.open("ACC ON camera reopen retry")) {
                return Result.TERMINAL_TIMEOUT;
            }
            return Result.OPENED;
        } catch (Exception second) {
            reporter.report("Camera retry failed", second);
            if (second instanceof InterruptedException) Thread.currentThread().interrupt();
            return Result.FAILED;
        }
    }
}
