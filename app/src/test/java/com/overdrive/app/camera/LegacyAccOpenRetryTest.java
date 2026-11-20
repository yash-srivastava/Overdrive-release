package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Exercises the exact open/retry driver called by the Di3 ACC-on reopen. */
public class LegacyAccOpenRetryTest {

    @Test
    public void normalOpenSucceedsOnceWithoutExtraBinderAttempts() {
        List<String> phases = new ArrayList<>();
        LegacyAccOpenRetry.Result result = LegacyAccOpenRetry.run(phase -> {
            phases.add(phase);
            return true;
        }, () -> true, (phase, error) -> { throw new AssertionError(error); });
        assertEquals(LegacyAccOpenRetry.Result.OPENED, result);
        assertEquals(Arrays.asList("ACC ON camera reopen"), phases);
    }

    @Test
    public void failedVendorOpenUsesOneRetryAndReportsTheFirstException() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> phases = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        LegacyAccOpenRetry.Result result = LegacyAccOpenRetry.run(phase -> {
            phases.add(phase);
            if (attempts.getAndIncrement() == 0) throw new Exception("first open failed");
            return true;
        }, () -> true, (phase, error) -> errors.add(phase + ": " + error.getMessage()));
        assertEquals(LegacyAccOpenRetry.Result.OPENED, result);
        assertEquals(Arrays.asList("ACC ON camera reopen",
                "ACC ON camera reopen retry"), phases);
        assertEquals(Arrays.asList("Failed to reopen camera: first open failed"), errors);
    }

    @Test
    public void timedOutVendorOpenNeverOverlapsASecondOpen() {
        AtomicInteger attempts = new AtomicInteger();
        LegacyAccOpenRetry.Result result = LegacyAccOpenRetry.run(phase -> {
            attempts.incrementAndGet();
            return false; // Worker is still blocked, process restart required.
        }, () -> true, (phase, error) -> { throw new AssertionError(error); });
        assertEquals(LegacyAccOpenRetry.Result.TERMINAL_TIMEOUT, result);
        assertEquals(1, attempts.get());
    }

    @Test
    public void retryMayBeRejectedAfterPartialHandleOrShutdown() {
        AtomicInteger attempts = new AtomicInteger();
        LegacyAccOpenRetry.Result result = LegacyAccOpenRetry.run(phase -> {
            attempts.incrementAndGet();
            throw new Exception("partial vendor handle");
        }, () -> false, (phase, error) -> { });
        assertEquals(LegacyAccOpenRetry.Result.FAILED, result);
        assertEquals(1, attempts.get());
    }

    @Test
    public void secondErrorStopsAfterExactlyTwoAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> failures = new ArrayList<>();
        LegacyAccOpenRetry.Result result = LegacyAccOpenRetry.run(phase -> {
            attempts.incrementAndGet();
            throw new Exception("no camera frames");
        }, () -> true, (phase, error) -> failures.add(phase));
        assertEquals(LegacyAccOpenRetry.Result.FAILED, result);
        assertEquals(2, attempts.get());
        assertEquals(Arrays.asList("Failed to reopen camera", "Camera retry failed"),
                failures);
        assertTrue(failures.contains("Camera retry failed"));
    }

    @Test
    public void retryTimeoutAlsoStopsWithTerminalSingleFlightFence() {
        AtomicInteger attempts = new AtomicInteger();
        LegacyAccOpenRetry.Result result = LegacyAccOpenRetry.run(phase -> {
            if (attempts.getAndIncrement() == 0) throw new Exception("temporary failure");
            return false;
        }, () -> true, (phase, error) -> { });
        assertEquals(LegacyAccOpenRetry.Result.TERMINAL_TIMEOUT, result);
        assertEquals(2, attempts.get());
    }
}
