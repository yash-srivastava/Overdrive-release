package com.overdrive.app.trips;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class RangeModelCurveTest {
    @Test
    public void sparseDenseAndMissingBinsMatchReference() {
        Random random = new Random(4317);
        compare(Collections.emptyList());
        compare(Collections.singletonList(new RangeModel.Sample(1, 10, 2, 0.1,
                Double.NaN, Double.NaN)));
        List<RangeModel.Sample> dense = new ArrayList<>();
        for (int i = 0; i <= 200; i++) {
            dense.add(new RangeModel.Sample(i, i + 1, 0.12 * (i + 1), 0.1,
                    i % 111 - 50, i == 0 ? 0.2 : i));
        }
        compare(dense);
        for (int trial = 0; trial < 300; trial++) {
            List<RangeModel.Sample> samples = new ArrayList<>();
            int size = 1 + random.nextInt(500);
            for (int i = 0; i < size; i++) {
                double km = 1 + random.nextDouble() * 100;
                double temp = trial % 2 == 0 ? random.nextInt(5) + 15 : random.nextInt(141) - 65;
                double speed = trial % 2 == 0 ? random.nextInt(8) + 40 : random.nextInt(241);
                if (i % 7 == 0) temp = Double.NaN;
                if (i % 11 == 0) speed = Double.NaN;
                samples.add(new RangeModel.Sample(i, km, km * (0.1 + random.nextDouble() * 0.2),
                        0.1, temp, speed));
            }
            compare(samples);
        }
    }

    private static void compare(List<RangeModel.Sample> samples) {
        RangeModel.Curves actual = RangeModel.curves(samples);
        double[][] expected = reference(samples);
        for (int i = 0; i < 111; i++) {
            assertEquals(Double.doubleToLongBits(expected[0][i]),
                    Double.doubleToLongBits(actual.temp(i - 50, 1)));
        }
        for (int i = 0; i < 201; i++) {
            assertEquals(Double.doubleToLongBits(expected[1][i]),
                    Double.doubleToLongBits(actual.speed(i == 0 ? 0.2 : i, 1)));
        }
    }

    // Full-bin reference: retain the original sample and bin summation order.
    private static double[][] reference(List<RangeModel.Sample> samples) {
        double[] temp = new double[111], speed = new double[201];
        Arrays.fill(temp, 1);
        Arrays.fill(speed, 1);
        for (int round = 0; round < 2; round++) {
            temp = referenceCurve(samples, speed, true);
            speed = referenceCurve(samples, temp, false);
        }
        return new double[][] {temp, speed};
    }

    private static int bin(RangeModel.Sample sample, boolean temp) {
        if (temp) {
            long t = Math.round(sample.tempC);
            return Double.isNaN(sample.tempC) || t < -50 || t > 60 ? -1 : (int) t + 50;
        }
        long v = Math.round(sample.speedKmh);
        return sample.speedKmh > 0 && v <= 200 ? (int) v : -1;
    }

    private static double[] referenceCurve(List<RangeModel.Sample> samples, double[] other,
                                           boolean temperature) {
        int span = temperature ? 111 : 201;
        double[] used = new double[span], km = new double[span], curve = new double[span];
        double[] adjusted = new double[samples.size()];
        double usedSum = 0, kmSum = 0, sumSqKm = 0;
        Arrays.fill(curve, 1);
        for (int i = 0; i < samples.size(); i++) {
            RangeModel.Sample s = samples.get(i);
            int b = bin(s, temperature), o = bin(s, !temperature);
            if (b < 0) continue;
            adjusted[i] = s.used / (o < 0 ? 1 : other[o]);
            used[b] += adjusted[i];
            km[b] += s.km;
            usedSum += adjusted[i];
            kmSum += s.km;
            sumSqKm += s.km * s.km;
        }
        if (!(kmSum > 0) || !(usedSum > 0)) return curve;
        double mean = usedSum / kmSum, within = 0, between = 0;
        for (int i = 0; i < samples.size(); i++) {
            RangeModel.Sample s = samples.get(i);
            int b = bin(s, temperature);
            if (b < 0) continue;
            double dev = adjusted[i] / s.km / mean - used[b] / km[b] / mean;
            within += s.km * dev * dev;
        }
        int count = 0;
        for (int b = 0; b < span; b++) {
            if (km[b] == 0) continue;
            double dev = used[b] / km[b] / mean - 1;
            between += km[b] * dev * dev;
            count++;
        }
        double noise = within / kmSum * (sumSqKm / kmSum);
        double signal = between / kmSum - noise * count / kmSum;
        double prior = signal > 1e-6 ? Math.max(20, Math.min(1000, noise / signal)) : 1000;
        double sigma = temperature ? 5 : 10;
        for (int t = 0; t < span; t++) {
            double num = 0, den = 0;
            for (int b = 0; b < span; b++) {
                if (km[b] == 0) continue;
                int d = Math.abs(b - t);
                double k = Math.exp(-(double) d * d / (2 * sigma * sigma));
                num += k * used[b];
                den += k * km[b];
            }
            curve[t] = Math.max(0.5, Math.min(2.0, ((num + prior * mean) / (den + prior)) / mean));
        }
        return curve;
    }
}
