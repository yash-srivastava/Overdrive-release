package com.overdrive.app.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Exercise real attempt-state transitions, not source indentation or string literals. */
public class AutoUpdateAttemptStateTest {
    private static final long HOUR = 60L * 60L * 1000L;

    @Test public void repeatedDownloadDeferralCarriesForwardAcrossPendingChecks() {
        long now = 1_800_000_000_000L;
        String record = "";
        for (int i = 1; i <= 5; i++) {
            record = AutoUpdateAttemptState.nextFailureRecord(record, now);
            int count = Math.min(i, 4);
            long hours = Math.min(24L, 3L * (1L << (count - 1)));
            assertEquals(count + ":" + (now + hours * HOUR), record);
            assertFalse(AutoUpdateAttemptState.mayCheckAt(record, now + hours * HOUR - 1));
            assertTrue(AutoUpdateAttemptState.mayCheckAt(record, now + hours * HOUR));
            record = AutoUpdateAttemptState.nextSuccessRecord(record, true, now);
            assertEquals(count + ":" + (now + HOUR / 2), record);
        }
    }

    @Test public void cleanNoUpdateResetsRetriesAndKeepsSixHourInterval() {
        long now = 1_800_000_000_000L;
        assertEquals("0:" + (now + 6 * HOUR),
                AutoUpdateAttemptState.nextSuccessRecord("4:999", false, now));
        assertEquals("1:" + (now + 3 * HOUR), AutoUpdateAttemptState.nextFailureRecord("", now));
    }

    @Test public void unreadableOrCorruptCheckStateNeverGrantsAdmission() {
        long now = 1_800_000_000_000L;
        assertTrue(AutoUpdateAttemptState.mayCheckAt("", now));
        assertFalse(AutoUpdateAttemptState.mayCheckAt(null, now));
        assertFalse(AutoUpdateAttemptState.mayCheckAt("garbage", now));
        assertFalse(AutoUpdateAttemptState.mayCheckAt("-1:0", now));
        assertNull(AutoUpdateAttemptState.nextSuccessRecord(null, true, now));
        assertNull(AutoUpdateAttemptState.nextSuccessRecord("not a record", true, now));
        // A wildly future-skewed clock is allowed to recover rather than block forever.
        assertTrue(AutoUpdateAttemptState.mayCheckAt("1:" + (now + 25 * HOUR), now));
    }
}
