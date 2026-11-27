package com.overdrive.app.trips;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;

public class ElevationEstimatorTest {
    @Test
    public void rollingMediansMatchSortedReference() {
        Random random = new Random(7812);
        ElevationEstimator.MedianWindow window = new ElevationEstimator.MedianWindow();
        ArrayDeque<double[]> reference = new ArrayDeque<>();
        long time = 1_000_000;
        for (int i = 0; i < 20_000; i++) {
            if (i % 1600 == 0) {
                window.clear();
                reference.clear();
            }
            // Dense bursts force growth; backwards timestamps exercise FIFO semantics.
            time += i % 1600 < 300 ? 0 : random.nextInt(400) - 20;
            if (i % 1301 == 0) time += 12_000;
            double altitude = i % 301 == 0 ? Double.POSITIVE_INFINITY
                    : i % 307 == 0 ? Double.NEGATIVE_INFINITY : random.nextInt(21) - 10;
            double accuracy = random.nextBoolean() ? 0 : 1 + random.nextInt(10);
            window.add(time, altitude, accuracy);
            reference.addLast(new double[]{time, altitude, accuracy});
            while (time - reference.peekFirst()[0] > ElevationEstimator.SMOOTHING_WINDOW_MS) {
                reference.removeFirst();
            }
            double[] altitudes = new double[reference.size()];
            double[] accuracies = new double[reference.size()];
            int n = 0, a = 0;
            for (double[] row : reference) {
                altitudes[n++] = row[1];
                if (row[2] > 0) accuracies[a++] = row[2];
            }
            assertEquals(Double.doubleToLongBits(median(altitudes, n)),
                    Double.doubleToLongBits(window.medianAltitude()));
            assertEquals(Double.doubleToLongBits(median(accuracies, a)),
                    Double.doubleToLongBits(window.medianVerticalAccuracy()));
        }
    }

    @Test
    public void exactTimeBoundaryAndAccuracyDuplicates() {
        ElevationEstimator.MedianWindow window = new ElevationEstimator.MedianWindow();
        window.add(0, 10, 8);
        window.add(1, 20, 8);
        window.add(10_000, 30, 0);
        assertEquals(20.0, window.medianAltitude(), 0.0);
        assertEquals(8.0, window.medianVerticalAccuracy(), 0.0);
        window.add(10_001, 40, 2);
        assertEquals(30.0, window.medianAltitude(), 0.0);
        assertEquals(5.0, window.medianVerticalAccuracy(), 0.0);
        window.add(10_002, 50, 0);
        assertEquals(40.0, window.medianAltitude(), 0.0);
        assertEquals(2.0, window.medianVerticalAccuracy(), 0.0);
        window.clear();
        window.add(10_003, 100, 0);
        assertEquals(100.0, window.medianAltitude(), 0.0);
        assertEquals(0.0, window.medianVerticalAccuracy(), 0.0);
    }

    @Test
    public void estimatorRetainsTotalsAcrossSourceAndGapResets() {
        ElevationEstimator estimator = new ElevationEstimator();
        estimator.addSample(0, 100, 0, false, true);
        estimator.addSample(11_000, 110, 0, false, true);
        estimator.addSample(22_000, 120, 0, false, true);
        assertEquals(10.0, estimator.getGainM(), 0.0);
        estimator.addSample(33_000, 200, 0, true, true);
        estimator.addSample(44_000, 190, 0, true, true);
        estimator.addSample(55_000, 180, 0, true, true);
        assertEquals(10.0, estimator.getLossM(), 0.0);
        estimator.addSample(66_000, 1000, 20, true, true);
        estimator.addSample(77_000, 1000, 0, true, false);
        estimator.addSample(88_000, 300, 0, true, true);
        assertEquals(10.0, estimator.getGainM(), 0.0);
        assertEquals(10.0, estimator.getLossM(), 0.0);
    }

    @Test
    public void infiniteAltitudeDoesNotPoisonClimbOrDescent() {
        for (double invalid : new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (int direction : new int[]{1, -1}) {
                ElevationEstimator estimator = new ElevationEstimator();
                // Finite negative altitudes remain valid (below sea level).
                estimator.addSample(0, -100, 0, true, true);
                estimator.addSample(11_000, -100 + direction * 10, 0, true, true);
                estimator.addSample(22_000, -100 + direction * 20, 0, true, true);

                estimator.addSample(33_000, invalid, 0, true, true);
                estimator.addSample(44_000, -100 + direction * 30, 0, true, true);

                assertEquals(direction > 0 ? 20.0 : 0.0, estimator.getGainM(), 0.0);
                assertEquals(direction < 0 ? 20.0 : 0.0, estimator.getLossM(), 0.0);
            }
        }
    }

    @Test
    public void rejectedAltitudeDoesNotChangeSourceOrTimestampState() {
        ElevationEstimator estimator = new ElevationEstimator();
        estimator.addSample(0, 100, 0, true, true);
        estimator.addSample(11_000, 110, 0, true, true);
        estimator.addSample(22_000, 120, 0, true, true);

        // A rejected fix must not reset the filter for a source flip or clock jump.
        estimator.addSample(1_000, Double.POSITIVE_INFINITY, 0, false, true);
        estimator.addSample(33_000, 130, 0, true, true);

        assertEquals(20.0, estimator.getGainM(), 0.0);
        assertEquals(0.0, estimator.getLossM(), 0.0);
    }

    private static double median(double[] values, int count) {
        if (count == 0) return 0;
        Arrays.sort(values, 0, count);
        return count % 2 == 1 ? values[count / 2]
                : (values[count / 2 - 1] + values[count / 2]) / 2.0;
    }
}
