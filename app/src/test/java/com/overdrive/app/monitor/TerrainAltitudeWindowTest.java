package com.overdrive.app.monitor;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class TerrainAltitudeWindowTest {
    // The former TerrainTracker algorithm, independent of the rolling index.
    private static final class Reference {
        final ArrayDeque<double[]> samples = new ArrayDeque<>();

        double add(long now, double altitude) {
            samples.addLast(new double[] {now, altitude});
            while (!samples.isEmpty() && now - samples.peekFirst()[0] > 5000) {
                samples.removeFirst();
            }
            double[] sorted = new double[samples.size()];
            int i = 0;
            for (double[] sample : samples) sorted[i++] = sample[1];
            Arrays.sort(sorted);
            int n = sorted.length;
            return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
        }
    }

    private static void compare(TerrainTracker.AltitudeWindow window, Reference ref,
                                long now, double altitude) {
        assertEquals("at " + now + " altitude " + altitude,
                Double.doubleToRawLongBits(ref.add(now, altitude)),
                Double.doubleToRawLongBits(window.add(now, altitude)));
    }

    @Test public void expiryBoundaryAndDuplicates() {
        TerrainTracker.AltitudeWindow window = new TerrainTracker.AltitudeWindow();
        assertEquals(10.0, window.add(0, 10), 0.0);
        assertEquals(15.0, window.add(5000, 20), 0.0);
        assertEquals(20.0, window.add(5001, 20), 0.0);
        assertEquals(20.0, window.add(5001, 30), 0.0);
        assertEquals(40.0, window.add(10002, 40), 0.0);
        window.clear();
        assertEquals(-10.0, window.add(10002, -10), 0.0);
    }

    @Test public void ringWrapGrowthAndSignedZero() {
        TerrainTracker.AltitudeWindow window = new TerrainTracker.AltitudeWindow();
        Reference ref = new Reference();
        for (int i = 0; i < 100; i++) compare(window, ref, i * 400L, i % 2 == 0 ? -0.0 : 0.0);
        // Increase the fix rate after the FIFO has wrapped; force growth while wrapped.
        for (int i = 0; i < 1024; i++) compare(window, ref, 40000L + i, (i % 41) - 20);
        for (int i = 0; i < 128; i++) compare(window, ref, 45000L + i * 200L, -0.0);
        compare(window, ref, 100000, -0.0);
    }

    @Test public void randomizedExactMedianWithResetsAndGaps() {
        TerrainTracker.AltitudeWindow window = new TerrainTracker.AltitudeWindow();
        Reference ref = new Reference();
        Random random = new Random(0x7e22a1L);
        long now = 1000;
        for (int i = 0; i < 20000; i++) {
            if (i % 241 == 0) {
                window.clear();
                ref.samples.clear();
            }
            now += i % 311 == 0 ? 8000 : random.nextInt(401);
            if (i % 197 == 0) now -= 1000; // FIFO semantics even with a clock discontinuity.
            double alt = i % 7 == 0 ? -0.0 : i % 11 == 0 ? 0.0
                    : i % 3 == 0 ? random.nextInt(10) : random.nextDouble() * 2000 - 1000;
            compare(window, ref, now, alt);
        }
    }
}
