package com.overdrive.app.trips;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class RangeModelCurveTest {
    @Test
    public void sparseHistoryMatchesOriginalCurveAndFit() {
        checkHistory(false,
                new double[]{1.0, 1.0000411075252258, 1.0089396511996470, 1.0244604047660810,
                        0.9930433797914430, 0.9763839621159445, 0.9966435841508734, 1.0},
                new double[]{1.0000494503906465, 1.0065563823964800, 1.0151206423132282,
                        0.9986205084953846, 0.9856652039228580, 0.9991205850487695, 1.0, 1.0},
                new double[]{0.10888368080164827, 0.11874338424536267,
                        0.6932723490425453, 0.001777893053385919});
    }

    @Test
    public void denseHistoryMatchesOriginalCurveAndFit() {
        checkHistory(true,
                new double[]{1.0408326880173490, 1.0144893244958961, 1.0145101175516555,
                        0.9921892396676895, 0.9907383713465670, 0.9914969846900514,
                        1.0196685310980487, 0.9816025300934254},
                new double[]{1.0155674564691963, 0.9967290932029891, 0.9861239710715325,
                        0.9956322865554678, 0.9889886873629572, 0.9961820626404658,
                        1.0036343977591216, 0.9760864381937780},
                new double[]{0.10683455778387206, 0.10695700730866747,
                        3.5966226582552454, 0.00013908890230533748});
    }

    @Test
    public void emptyUnknownAndNonPositiveHistoryKeepNeutralCurves() {
        checkNeutral(RangeModel.curves(Collections.emptyList()));
        checkNeutral(RangeModel.curves(Collections.singletonList(
                new RangeModel.Sample(1, 10, 2, 0.15, Double.NaN, Double.NaN))));
        checkNeutral(RangeModel.curves(Collections.singletonList(
                new RangeModel.Sample(1, 10, -2, 0.15, 20, 50))));
    }

    private static void checkNeutral(RangeModel.Curves curves) {
        for (int t = -50; t <= 60; t++) assertEquals(1.0, curves.temp(t, 1), 0.0);
        for (int v = 1; v <= 200; v++) assertEquals(1.0, curves.speed(v, 1), 0.0);
    }

    // Reference values from the original full-bin scan; tolerance allows JVM math differences.
    private static void checkHistory(boolean dense, double[] temp, double[] speed, double[] fit) {
        List<RangeModel.Sample> samples = new ArrayList<>();
        for (int i = 0; i < (dense ? 3000 : 100); i++) {
            double km = 2 + i % 37;
            double t = dense ? i % 111 - 50 : 8 + i % 12;
            double v = dense ? 1 + i % 200 : 35 + i % 25;
            double used = km * (0.10 + 0.001 * (i % 31)) + 0.02 * (i % 9);
            if (i % 13 == 0) t = Double.NaN;
            if (i % 17 == 0) v = Double.NaN;
            if (i % 19 == 0) used = -used;
            samples.add(new RangeModel.Sample(1700000000000L + i * 3600000L, km, used, .15, t, v));
        }
        RangeModel.Curves curves = RangeModel.curves(samples);
        int[] temperatures = {-50, -10, 0, 8, 15, 19, 30, 60};
        int[] speeds = {1, 20, 35, 47, 59, 80, 150, 200};
        for (int i = 0; i < temperatures.length; i++) {
            assertEquals(temp[i], curves.temp(temperatures[i], 1), 1e-12);
            assertEquals(speed[i], curves.speed(speeds[i], 1), 1e-12);
        }
        RangeModel.Fit actual = RangeModel.fit(samples, curves, 1711000000000L,
                new RangeCalibration(.18, .2, 300, 90, 1700010000000L));
        assertEquals(fit[0], actual.level, 1e-12);
        assertEquals(fit[1], actual.anchoredLevel, 1e-12);
        assertEquals(fit[2], actual.relVarPerKm, 1e-12);
        assertEquals(fit[3], actual.relVarAnchored, 1e-12);
    }
}
