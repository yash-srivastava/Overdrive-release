package com.overdrive.app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoStartCheckpointPolicyTest {
    private static final long FIRST = 1_700_000_000_000L;
    private static final long HOUR = 60 * 60_000L;
    private static final long DAY = 24 * HOUR;

    @Test
    public void firstAndFinalCheckpointsAreSeparate() {
        assertEquals(HOUR, AutoStartCheckpointPolicy.delayUntilNextCheckpoint(FIRST, FIRST, false));
        assertEquals(1L, AutoStartCheckpointPolicy.delayUntilNextCheckpoint(
                FIRST, FIRST + HOUR - 1, false));
        assertEquals(0L, AutoStartCheckpointPolicy.delayUntilNextCheckpoint(FIRST, FIRST + HOUR, false));
        assertFalse(AutoStartCheckpointPolicy.completesFinalCheckpoint(FIRST, FIRST + HOUR, false));
        assertEquals(DAY - HOUR, AutoStartCheckpointPolicy.delayUntilNextCheckpoint(
                FIRST, FIRST + HOUR, true));
        assertFalse(AutoStartCheckpointPolicy.completesFinalCheckpoint(FIRST, FIRST + HOUR, true));
        assertEquals(0L, AutoStartCheckpointPolicy.delayUntilNextCheckpoint(FIRST, FIRST + DAY, true));
        assertTrue(AutoStartCheckpointPolicy.completesFinalCheckpoint(FIRST, FIRST + DAY, true));
    }

    @Test
    public void lateEligibleSessionCanCoverBothCheckpoints() {
        assertEquals(0L, AutoStartCheckpointPolicy.delayUntilNextCheckpoint(
                FIRST, FIRST + DAY + HOUR, false));
        assertTrue(AutoStartCheckpointPolicy.completesFinalCheckpoint(FIRST, FIRST + DAY + HOUR, false));
    }

    @Test
    public void backwardsClockDoesNotStrandPendingVerification() {
        assertEquals(0L, AutoStartCheckpointPolicy.delayUntilNextCheckpoint(FIRST, FIRST - 1, false));
        assertEquals(0L, AutoStartCheckpointPolicy.delayUntilNextCheckpoint(FIRST, FIRST - 1, true));
    }
}
