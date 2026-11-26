package com.overdrive.app.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TripScorePresetElevationTest {
    private static TripRecord trip() {
        TripRecord trip = new TripRecord();
        trip.distanceKm = 10;
        trip.durationSeconds = 600;
        trip.energyPerKm = 0.18;
        return trip;
    }

    private static List<TelemetrySample> samples(int count, boolean climb, Random random) {
        List<TelemetrySample> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int speed = i % 100 < 10 ? 0 : 40 + random.nextInt(20);
            int accel = i % 40 < 10 ? 0 : random.nextInt(50);
            int brake = i % 40 == 10 ? 15 : 0;
            double altitude = climb ? 100 + i * 0.5 : 100;
            result.add(new TelemetrySample(1_000_000 + 200L * i, speed, accel, brake,
                    brake > 0, 4, 47, 8, altitude, 3, true));
        }
        return result;
    }

    private static void assertSameSummary(TripRecord expected, TripRecord actual) {
        assertEquals(expected.elevationGainM, actual.elevationGainM, 0);
        assertEquals(expected.elevationLossM, actual.elevationLossM, 0);
        assertEquals(expected.avgGradientPercent, actual.avgGradientPercent, 0);
        assertEquals(expected.gradientProfile, actual.gradientProfile);
        assertEquals(expected.kinematicState, actual.kinematicState);
        assertEquals(expected.anticipationScore, actual.anticipationScore);
        assertEquals(expected.smoothnessScore, actual.smoothnessScore);
        assertEquals(expected.speedDisciplineScore, actual.speedDisciplineScore);
        assertEquals(expected.efficiencyScore, actual.efficiencyScore);
        assertEquals(expected.consistencyScore, actual.consistencyScore);
        assertEquals(expected.avgSpeedKmh, actual.avgSpeedKmh, 0);
        assertEquals(expected.maxSpeedKmh, actual.maxSpeedKmh);
        assertEquals(expected.microMomentsJson, actual.microMomentsJson);
    }

    @Test
    public void presetTotalsOverrideScoringStreamAltitude() {
        TripScoreEngine engine = new TripScoreEngine();
        List<TelemetrySample> climbing = samples(600, true, new Random(42));
        List<TelemetrySample> flat = samples(600, false, new Random(42));
        assertTrue(engine.computeSummary(trip(), climbing).elevationGainM > 0);
        for (double[] totals : new double[][]{{0, 0}, {250, 20}, {10, 250}, {60, 65}}) {
            TripRecord expected = engine.computeSummary(trip(), flat, totals[0], totals[1]);
            TripRecord actual = engine.computeSummary(trip(), climbing, totals[0], totals[1]);
            assertEquals(totals[0], actual.elevationGainM, 0);
            assertEquals(totals[1], actual.elevationLossM, 0);
            assertSameSummary(expected, actual);
        }
    }

    @Test
    public void missingGainUsesEstimatorAndMatchingPresetPreservesAllScores() {
        TripScoreEngine engine = new TripScoreEngine();
        for (int seed = 0; seed < 40; seed++) {
            List<TelemetrySample> rows = samples(80 + seed * 13, seed % 2 == 0, new Random(seed));
            TripRecord computed = engine.computeSummary(trip(), rows);
            // Gain, not loss, is the established sentinel for both totals.
            assertSameSummary(computed, engine.computeSummary(trip(), rows, Double.NaN, 999));
            assertSameSummary(computed, engine.computeSummary(trip(), rows,
                    computed.elevationGainM, computed.elevationLossM));
        }
    }

    @Test
    public void insufficientSamplesKeepExistingDefaultBehavior() {
        TripRecord input = trip();
        input.elevationGainM = 17;
        input.elevationLossM = 9;
        TripRecord result = new TripScoreEngine().computeSummary(input,
                samples(29, true, new Random(1)), 250, 100);
        assertEquals(17, result.elevationGainM, 0);
        assertEquals(9, result.elevationLossM, 0);
        assertEquals(50, result.getOverallScore());
    }
}
