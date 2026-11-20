package com.overdrive.app.camera;

import java.util.function.BooleanSupplier;

/** Execute the actual ACC-on legacy camera acquisition with at most one retry. */
final class LegacyAccOpenRetry {
    enum Result { OPENED, FAILED, TERMINAL_TIMEOUT }

    interface OpenAttempt {
        /** false means a vendor open might still be running; do not retry. */
        boolean open(String phase) throws Exception;
    }

    interface ErrorReporter {
        void report(String phase, Exception failure);
    }

    static Result run(OpenAttempt attempt, BooleanSupplier mayRetry,
            ErrorReporter reporter) {
        try {
            if (!attempt.open("ACC ON camera reopen")) return Result.TERMINAL_TIMEOUT;
            return Result.OPENED;
        } catch (Exception first) {
            reporter.report("Failed to reopen camera", first);
        }
        if (!mayRetry.getAsBoolean()) return Result.FAILED;
        try {
            if (!attempt.open("ACC ON camera reopen retry")) {
                return Result.TERMINAL_TIMEOUT;
            }
            return Result.OPENED;
        } catch (Exception second) {
            reporter.report("Camera retry failed", second);
            return Result.FAILED;
        }
    }

    private LegacyAccOpenRetry() { }
}
