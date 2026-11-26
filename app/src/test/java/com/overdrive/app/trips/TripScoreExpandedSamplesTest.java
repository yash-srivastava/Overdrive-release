package com.overdrive.app.trips;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

public class TripScoreExpandedSamplesTest {
    private static TelemetrySample sample(long time, int speed, int accel, int brake) {
        return new TelemetrySample(time, speed, accel, brake, brake > 0,
                4, 47, 8, 100 + time % 127, 3, true);
    }

    private static TripRecord trip() {
        TripRecord trip = new TripRecord();
        trip.distanceKm = 10;
        trip.durationSeconds = 600;
        trip.energyPerKm = 0.18;
        return trip;
    }

    private static void assertSameSummary(TripRecord expected, TripRecord actual) {
        assertEquals(expected.anticipationScore, actual.anticipationScore);
        assertEquals(expected.smoothnessScore, actual.smoothnessScore);
        assertEquals(expected.speedDisciplineScore, actual.speedDisciplineScore);
        assertEquals(expected.efficiencyScore, actual.efficiencyScore);
        assertEquals(expected.consistencyScore, actual.consistencyScore);
        assertEquals(expected.kinematicState, actual.kinematicState);
        assertEquals(expected.gradientProfile, actual.gradientProfile);
        assertEquals(expected.elevationGainM, actual.elevationGainM, 0);
        assertEquals(expected.elevationLossM, actual.elevationLossM, 0);
        assertEquals(expected.avgGradientPercent, actual.avgGradientPercent, 0);
        assertEquals(expected.avgSpeedKmh, actual.avgSpeedKmh, 0);
        assertEquals(expected.maxSpeedKmh, actual.maxSpeedKmh);
        assertEquals(expected.microMomentsJson, actual.microMomentsJson);
    }

    private static void assertEquivalent(List<TelemetrySample> rows) {
        List<TelemetrySample> eager = TripScoreEngine.diLink5At5Hz(rows);
        TripScoreEngine.ExpandedScoringSamples virtual = new TripScoreEngine.ExpandedScoringSamples(rows);
        assertEquals(eager.size(), virtual.count);
        assertEquals(TripScoreEngine.scoreableSeconds(eager), virtual.seconds, 0);
        TripScoreEngine.ExpandedScoringSamples.Cursor cursor = virtual.cursor();
        for (TelemetrySample expected : eager) {
            TelemetrySample actual = cursor.next();
            assertNotNull(actual);
            assertEquals(expected.timestampMs, cursor.timestamp);
            assertEquals(expected.speedKmh, actual.speedKmh);
            assertEquals(expected.accelPedalPercent, actual.accelPedalPercent);
            assertEquals(expected.brakePedalPercent, actual.brakePedalPercent);
        }
        assertNull(cursor.next());
        TripScoreEngine engine = new TripScoreEngine();
        // Compare the complete score and micro-moment output, including window times.
        assertSameSummary(engine.computeSummary(trip(), eager, 250, 20),
                engine.computeExpandedSummary(trip(), virtual, 250, 20));
        // Also exercise the estimator branch of the shared scoring loop.
        assertSameSummary(engine.computeSummary(trip(), eager),
                engine.computeExpandedSummary(trip(), virtual, Double.NaN, Double.NaN));
    }

    @Test public void emptySingleAndIrregularTimestamps() {
        assertEquivalent(Collections.emptyList());
        assertEquivalent(Collections.singletonList(sample(1_000, 30, 5, 0)));
        List<TelemetrySample> rows = new ArrayList<>();
        for (long t : new long[]{1000, 1200, 1401, 1801, 1801, 1600, 5000, 5300}) {
            rows.add(sample(t, 30, 5, 0));
        }
        assertEquivalent(rows);
        // Exact 200 ms boundaries and a final duplicate timestamp.
        assertEquivalent(Arrays.asList(sample(1000, 20, 10, 0), sample(1200, 30, 0, 0),
                sample(1200, 10, 0, 30)));
    }

    @Test public void randomizedScoringMatchesMaterializedStream() {
        long[] steps = {0, -100, 199, 200, 201, 400, 800, 1000, 1000, 1000, 5000};
        for (int seed = 0; seed < 100; seed++) {
            Random random = new Random(seed);
            long time = 1_000_000;
            List<TelemetrySample> rows = new ArrayList<>();
            for (int i = 0; i < 300 + seed; i++) {
                int phase = i % 50;
                int speed = phase < 5 ? 0 : 15 + random.nextInt(80);
                int accel = phase < 20 ? random.nextInt(75) : 0;
                int brake = phase > 40 ? 20 : 0;
                rows.add(sample(time, speed, accel, brake));
                time += steps[random.nextInt(steps.length)];
            }
            assertEquivalent(rows);
        }
    }

    @Test public void tenHourJournalReusesEveryOriginalRow() {
        List<TelemetrySample> rows = new ArrayList<>();
        for (int i = 0; i < 36_000; i++) rows.add(sample(1000L * i, 60, 20, 0));
        TripScoreEngine.ExpandedScoringSamples expanded = new TripScoreEngine.ExpandedScoringSamples(rows);
        assertEquals(180_000, expanded.count);
        assertEquals(36_000.0, expanded.seconds, 0);
        TripScoreEngine.ExpandedScoringSamples.Cursor cursor = expanded.cursor();
        for (TelemetrySample row : rows) {
            for (int i = 0; i < 5; i++) {
                assertSame(row, cursor.next());
                assertEquals(row.timestampMs + i * 200L, cursor.timestamp);
            }
        }
        assertNull(cursor.next());
    }
}
