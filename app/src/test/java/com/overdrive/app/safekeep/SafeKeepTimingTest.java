package com.overdrive.app.safekeep;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-Java regression cases for SafeKeep elapsed-time gates (no device services). */
public class SafeKeepTimingTest {

    @Test
    public void thermalSampleIsDueImmediatelyAndAtExactThirtySecondBoundary() {
        assertTrue(SafeKeepTiming.intervalDue(0, -1, 30_000));
        assertFalse(SafeKeepTiming.intervalDue(29_999, 0, 30_000));
        assertTrue(SafeKeepTiming.intervalDue(30_000, 0, 30_000));
        assertFalse(SafeKeepTiming.intervalDue(60_029, 30_030, 30_000));
        assertTrue(SafeKeepTiming.intervalDue(60_030, 30_030, 30_000));
    }

    @Test
    public void thermalSampleRefreshesWhenElapsedClockResets() {
        assertTrue(SafeKeepTiming.intervalDue(1_000, 20_000, 30_000));
    }

    @Test
    public void recorderHoldIsAbsentUntilAnUnhealthySampleOccurs() {
        assertFalse(SafeKeepTiming.withinHold(0, -1, 10_000));
        assertFalse(SafeKeepTiming.withinHold(1_000, -1, 10_000));
    }

    @Test
    public void recorderHoldUsesTenSecondsSinceLastUnhealthySample() {
        assertTrue(SafeKeepTiming.withinHold(1_000, 1_000, 10_000));
        assertTrue(SafeKeepTiming.withinHold(10_999, 1_000, 10_000));
        assertFalse(SafeKeepTiming.withinHold(11_000, 1_000, 10_000));
        // A new unhealthy sample restarts the hold without wall-clock math.
        assertTrue(SafeKeepTiming.withinHold(11_000, 10_500, 10_000));
    }

    @Test
    public void recorderHoldFailsClosedOnImpossibleBackwardElapsedClock() {
        assertTrue(SafeKeepTiming.withinHold(1_000, 20_000, 10_000));
    }
}
