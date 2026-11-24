package com.overdrive.app.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StreamIdleTimeoutPolicyTest {
    @Test
    public void armsOnlyWhenBothKindsOfClientsHaveGone() {
        assertTrue(StreamIdleTimeoutPolicy.shouldArm(false, 0, 0, false));
        assertFalse(StreamIdleTimeoutPolicy.shouldArm(false, 1, 0, false));
        assertFalse(StreamIdleTimeoutPolicy.shouldArm(false, 0, 1, false));
        assertFalse(StreamIdleTimeoutPolicy.shouldArm(false, 2, 3, false));
    }

    @Test
    public void terminalAndAlreadyTriggeredServersNeverRearm() {
        assertFalse(StreamIdleTimeoutPolicy.shouldArm(true, 0, 0, false));
        assertFalse(StreamIdleTimeoutPolicy.shouldArm(false, 0, 0, true));
    }

    @Test
    public void timeoutIsMeasuredFromMonotonicDisconnectDeadline() {
        assertEquals(30_000L, StreamIdleTimeoutPolicy.remainingMs(100L, 100L, 30_000L));
        assertEquals(1L, StreamIdleTimeoutPolicy.remainingMs(30_099L, 100L, 30_000L));
        assertEquals(0L, StreamIdleTimeoutPolicy.remainingMs(30_100L, 100L, 30_000L));
        assertEquals(0L, StreamIdleTimeoutPolicy.remainingMs(40_000L, 100L, 30_000L));
    }

    @Test
    public void clockRollbackCannotPrematurelyExpireTimer() {
        assertEquals(30_000L, StreamIdleTimeoutPolicy.remainingMs(100L, 200L, 30_000L));
        assertEquals(0L, StreamIdleTimeoutPolicy.remainingMs(100L, 200L, 0L));
    }
}
