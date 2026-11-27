package com.overdrive.app.trips;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class RangeModelTest {
    private static final long DAY_MS = 86_400_000L;
    private static final long NOW = 100 * DAY_MS;
    private static final long HALF_LIFE_MS = 45 * DAY_MS;
    private static final double EPS = 1e-9;

    private static RangeModel.Sample sample(long endMs, double km, double rate) {
        return new RangeModel.Sample(endMs, km, km * rate, 0.05, Double.NaN, Double.NaN);
    }

    private static RangeModel.Curves neutralCurves() {
        return RangeModel.curves(Collections.emptyList());
    }

    private static List<RangeModel.Sample> uniformHistory() {
        return Arrays.asList(sample(NOW, 20, 0.20), sample(NOW, 30, 0.20),
                sample(NOW, 50, 0.20));
    }

    @Test
    public void fitRequiresEnoughTripsAndDistance() {
        assertNull(RangeModel.fit(Arrays.asList(sample(NOW, 20, 0.20),
                sample(NOW, 20, 0.20)), neutralCurves(), NOW, null));
        assertNull(RangeModel.fit(Arrays.asList(sample(NOW, 3, 0.20),
                sample(NOW, 3, 0.20), sample(NOW, 3, 0.20)), neutralCurves(), NOW, null));
        RangeModel.Fit fit = RangeModel.fit(Arrays.asList(sample(NOW, 2, 0.20),
                sample(NOW, 3, 0.20), sample(NOW, 5, 0.20)), neutralCurves(), NOW, null);
        assertNotNull(fit);
        assertEquals(3, fit.trips);
        assertEquals(10.0, fit.km, EPS);
        assertEquals(0.20, fit.level, EPS);
    }

    @Test
    public void consumptionUsesDistanceAndRecencyRatherThanMeanOfTripRates() {
        List<RangeModel.Sample> history = Arrays.asList(sample(NOW, 20, 0.10),
                sample(NOW - HALF_LIFE_MS, 40, 0.30), sample(NOW, 40, 0.25));
        RangeModel.Fit fit = RangeModel.fit(history, RangeModel.curves(history), NOW, null);
        assertNotNull(fit);
        // The older 40 km trip contributes 20 weighted km and 6 weighted kWh.
        assertEquals((2.0 + 6.0 + 10.0) / 80.0, fit.level, EPS);
        assertEquals(100.0, fit.km, EPS);
        assertEquals(8.0 / 3.0, fit.effectiveTrips, EPS);
        assertEquals(1.0, fit.recentTempFactor, EPS);
        assertEquals(1.0, fit.recentSpeedFactor, EPS);
        assertFalse(fit.anchored);
    }

    @Test
    public void calibrationBlendsOnlyTripsCompletedAfterItWasSet() {
        long setAt = NOW - HALF_LIFE_MS;
        List<RangeModel.Sample> history = Arrays.asList(sample(setAt - DAY_MS, 200, 0.40),
                sample(setAt, 100, 0.40), sample(NOW, 50, 0.30), sample(NOW, 50, 0.15));
        RangeCalibration calibration = new RangeCalibration(0.10, 0.10, 400, 80, setAt);
        RangeModel.Fit fit = RangeModel.fit(history, neutralCurves(), NOW, calibration);
        assertNotNull(fit);
        assertTrue(fit.anchored);
        // A 300 km calibration aged one half-life contributes 150 weighted km.
        // Only the two post-calibration trips contribute 100 weighted km.
        assertEquals(0.6, fit.anchorShare, EPS);
        assertEquals((150 * 0.10 + 50 * 0.30 + 50 * 0.15) / 250, fit.anchoredLevel, EPS);
        assertTrue(fit.level > fit.anchoredLevel);
    }

    @Test
    public void unusableCalibrationLeavesHistoricalFitUnchanged() {
        RangeCalibration invalid = new RangeCalibration(Double.NaN, 0.10, 400, 80, NOW);
        RangeModel.Fit fit = RangeModel.fit(uniformHistory(), neutralCurves(), NOW, invalid);
        assertNotNull(fit);
        assertFalse(fit.anchored);
        assertEquals(0.0, fit.anchorShare, EPS);
        assertEquals(fit.level, fit.anchoredLevel, EPS);
    }

    @Test
    public void liveConsumptionOnlyInfluencesShownBandAtMinimumDistance() {
        RangeModel.Fit fit = RangeModel.fit(uniformHistory(), neutralCurves(), NOW, null);
        assertNotNull(fit);
        RangeModel.Band shortDrive = RangeModel.band(fit, Double.NaN, true, 9.9, 0, 0.32, 40);
        RangeModel.Band shown = RangeModel.band(fit, Double.NaN, true, 10, 0, 0.32, 40);
        RangeModel.Band historical = RangeModel.band(fit, Double.NaN, false, 10, 0, 0.32, 40);
        assertNotNull(shortDrive);
        assertNotNull(shown);
        assertNotNull(historical);
        assertEquals(0.0, shortDrive.liveShare, EPS);
        assertEquals(0.20, shortDrive.rate, EPS);
        assertEquals(1.0 / 6.0, shown.liveShare, EPS);
        assertEquals(0.22, shown.rate, EPS);
        assertEquals(0.0, historical.liveShare, EPS);
        assertEquals(0.20, historical.rate, EPS);
        assertTrue(shown.lowRate > 0 && shown.lowRate < shown.rate);
        assertTrue(Double.isFinite(shown.highRate) && shown.highRate > shown.rate);
    }
}
