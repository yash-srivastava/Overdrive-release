package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Drives the same retry controller used by the ACC reopen catch and open paths. */
public class LegacyAccOpenRetryTest {

    @Test
    public void successOpensOnceAndForbidsCatchRetry() {
        LegacyAccOpenRetry controller = new LegacyAccOpenRetry();
        AtomicInteger calls = new AtomicInteger();
        assertTrue(controller.canRetryInCatch());
        assertEquals(LegacyAccOpenRetry.Result.OPENED, controller.run(phase -> {
            calls.incrementAndGet();
            return true;
        }, () -> true, (phase, error) -> { throw new AssertionError(error); }));
        assertEquals(1, calls.get());
        assertFalse(controller.canRetryInCatch());
    }

    @Test
    public void firstOpenExceptionAllowsExactlyOneRetry() {
        LegacyAccOpenRetry controller = new LegacyAccOpenRetry();
        List<String> phases = new ArrayList<>();
        List<String> reports = new ArrayList<>();
        assertEquals(LegacyAccOpenRetry.Result.OPENED, controller.run(phase -> {
            phases.add(phase);
            if (phases.size() == 1) throw new IllegalStateException("vendor");
            return true;
        }, () -> true, (phase, error) -> reports.add(phase)));
        assertEquals(2, phases.size());
        assertEquals("ACC ON camera reopen", phases.get(0));
        assertEquals("ACC ON camera reopen retry", phases.get(1));
        assertEquals("Failed to reopen camera", reports.get(0));
        assertEquals(1, reports.size());
        assertFalse(controller.canRetryInCatch());
    }

    @Test
    public void terminalTimeoutNeverStartsAnOverlappingSecondOpen() {
        LegacyAccOpenRetry controller = new LegacyAccOpenRetry();
        AtomicInteger calls = new AtomicInteger();
        assertEquals(LegacyAccOpenRetry.Result.TERMINAL_TIMEOUT, controller.run(
                phase -> { calls.incrementAndGet(); return false; },
                () -> true, (phase, failure) -> { throw new AssertionError(failure); }));
        assertEquals(1, calls.get());
        assertFalse(controller.canRetryInCatch());
    }

    @Test
    public void preOpenExceptionStillHasCatchRetryButPostHelperExceptionDoesNot() {
        LegacyAccOpenRetry beforeGrace = new LegacyAccOpenRetry();
        assertTrue(beforeGrace.canRetryInCatch());
        LegacyAccOpenRetry afterOpen = new LegacyAccOpenRetry();
        assertEquals(LegacyAccOpenRetry.Result.FAILED, afterOpen.run(
                phase -> { throw new IllegalStateException("failed"); },
                () -> false, (phase, error) -> {}));
        assertFalse(afterOpen.canRetryInCatch());
    }

    @Test
    public void syntheticWarmupCancellationDoesNotInterruptTheAccThread() {
        // openLegacyCameraWithHardTimeout can throw without a real thread interrupt.
        assertFalse(Thread.currentThread().isInterrupted());
        AtomicInteger calls = new AtomicInteger();
        LegacyAccOpenRetry first = new LegacyAccOpenRetry();
        assertEquals(LegacyAccOpenRetry.Result.FAILED, first.run(phase -> {
            calls.incrementAndGet();
            throw new InterruptedException("warmup cancelled");
        }, () -> true, (phase, error) -> {}));
        assertEquals(1, calls.get());
        assertFalse(Thread.currentThread().isInterrupted());

        LegacyAccOpenRetry second = new LegacyAccOpenRetry();
        calls.set(0);
        assertEquals(LegacyAccOpenRetry.Result.FAILED, second.run(phase -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("vendor");
            throw new InterruptedException("worker epoch changed");
        }, () -> true, (phase, error) -> {}));
        assertEquals(2, calls.get());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    public void retryFailureAndRetryTimeoutAreBounded() {
        AtomicInteger failures = new AtomicInteger();
        LegacyAccOpenRetry failed = new LegacyAccOpenRetry();
        assertEquals(LegacyAccOpenRetry.Result.FAILED, failed.run(phase -> {
            throw new IllegalStateException("failure");
        }, () -> true, (phase, error) -> failures.incrementAndGet()));
        assertEquals(2, failures.get());

        AtomicInteger opens = new AtomicInteger();
        LegacyAccOpenRetry timeout = new LegacyAccOpenRetry();
        assertEquals(LegacyAccOpenRetry.Result.TERMINAL_TIMEOUT, timeout.run(phase -> {
            if (opens.incrementAndGet() == 1) {
                throw new IllegalStateException("vendor first open failed");
            }
            return false;
        }, () -> true, (phase, error) -> {}));
        assertEquals(2, opens.get());
    }
}
