package com.overdrive.app.abrp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pins the one SOH priority chain shared by every user-facing consumer. */
public class CanonicalSohResolverTest {

    @Test
    public void oemHealthIndexWinsOnBevOverMovingEnergyEstimate() {
        SohEstimator.ResolvedSoh result = SohEstimator.resolveDisplaySoh(
                false, 98.0, -1, 99.7, 96.5);

        assertEquals(98.0, result.getPercent(), 0.001);
        assertEquals("oem", result.getSource());
        assertEquals(98.0, result.getOemPercent(), 0.001);
    }

    @Test
    public void oemHealthIndexWinsOnPhevToo() {
        SohEstimator.ResolvedSoh result = SohEstimator.resolveDisplaySoh(
                true, 94.0, 91.0, 100.0, 92.0);

        assertEquals(94.0, result.getPercent(), 0.001);
        assertEquals("oem", result.getSource());
    }

    @Test
    public void bevFallsBackToEstimateThenCalibrationWhenOemIsUnavailable() {
        SohEstimator.ResolvedSoh live = SohEstimator.resolveDisplaySoh(
                false, -1, 90.0, 96.2, 95.0);
        SohEstimator.ResolvedSoh calibration = SohEstimator.resolveDisplaySoh(
                false, Double.NaN, 90.0, -1, 95.0);

        assertEquals(96.2, live.getPercent(), 0.001);
        assertEquals("live", live.getSource());
        assertEquals(95.0, calibration.getPercent(), 0.001);
        assertEquals("calibration", calibration.getSource());
    }

    @Test
    public void phevRetainsItsFallbackOrderingWithoutOem() {
        SohEstimator.ResolvedSoh frame = SohEstimator.resolveDisplaySoh(
                true, 0, 93.0, 100.0, 92.0);
        SohEstimator.ResolvedSoh calibration = SohEstimator.resolveDisplaySoh(
                true, 101.0, -1, 100.0, 92.0);

        assertEquals(93.0, frame.getPercent(), 0.001);
        assertEquals("frame_anchor", frame.getSource());
        assertEquals(92.0, calibration.getPercent(), 0.001);
        assertEquals("calibration", calibration.getSource());
    }

    @Test
    public void invalidSourcesResolveToUnavailable() {
        SohEstimator.ResolvedSoh result = SohEstimator.resolveDisplaySoh(
                false, 0, -1, 110.0, Double.NaN);

        assertEquals(-1.0, result.getPercent(), 0.001);
        assertEquals("unavailable", result.getSource());
        assertEquals(-1.0, result.getOemPercent(), 0.001);
    }
}
