package com.overdrive.app.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AacPresentationTimelineTest {

    @Test
    public void advancesAtExactAacLcSampleCadence() {
        AacPresentationTimeline timeline = new AacPresentationTimeline(1_000_000L, 48_000, 1024);

        assertEquals(1_000_000L, timeline.nextPtsUs());
        assertEquals(1_021_333L, timeline.nextPtsUs());
        assertEquals(1_042_666L, timeline.nextPtsUs());
        assertEquals(1_064_000L, timeline.nextPtsUs());
    }

    @Test
    public void delayedCallsDoNotInsertWallClockGaps() {
        AacPresentationTimeline timeline = new AacPresentationTimeline(7_500_000L, 48_000, 1024);
        long first = timeline.nextPtsUs();
        // Simulate an arbitrary pause in the caller. The timeline has no wall-clock input.
        long second = timeline.nextPtsUs();
        long third = timeline.nextPtsUs();

        assertEquals(21_333L, second - first);
        assertEquals(21_333L, third - second);
        assertTrue(third > second && second > first);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidSampleRate() {
        new AacPresentationTimeline(0L, 0, 1024);
    }
}
