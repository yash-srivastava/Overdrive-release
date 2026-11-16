package com.overdrive.app.trips;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TelemetrySampleGpsFixTest {

    @Test
    public void hasGpsFix_rejectsMissingAndOutOfBounds() {
        assertFalse(TelemetrySample.hasGpsFix(0, 0));
        assertFalse(TelemetrySample.hasGpsFix(0, 151.2));
        assertFalse(TelemetrySample.hasGpsFix(-33.8, 0));
        assertFalse(TelemetrySample.hasGpsFix(Double.NaN, 10));
        assertFalse(TelemetrySample.hasGpsFix(10, Double.POSITIVE_INFINITY));
        assertFalse(TelemetrySample.hasGpsFix(91, 0));
        assertFalse(TelemetrySample.hasGpsFix(0, 181));
    }

    @Test
    public void hasGpsFix_acceptsPlausibleFixes() {
        assertTrue(TelemetrySample.hasGpsFix(-33.8688, 151.2093));
        assertTrue(TelemetrySample.hasGpsFix(32.0853, 34.7818));
        assertTrue(TelemetrySample.hasGpsFix(0.0001, 10.5));
    }
}