package com.overdrive.app.telemetry;

import org.junit.Test;

import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class OverlayValueTextTest {
    @Test
    public void exactFormattingAcrossChangesAndRepeatedReads() {
        OverlayValueText cache = new OverlayValueText();
        double[] edges = {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                -0.0, 0.0, -1.0, 0.5, 99.5, 12.345, -12.345, 45.123455,
                -45.123455, Double.MIN_VALUE, Double.MAX_VALUE};
        for (double edge : edges) {
            compare(cache, edge, edge, edge, -edge, true);
            compare(cache, edge, edge, edge, -edge, false);
        }
        Random random = new Random(7123);
        for (int i = 0; i < 3000; i++) {
            double soc = random.nextInt(101) + random.nextDouble();
            double volts = 10 + random.nextDouble() * 6;
            double lat = random.nextDouble() * 180 - 90;
            double lon = random.nextDouble() * 360 - 180;
            if (i % 17 == 0) lat = Double.longBitsToDouble(random.nextLong());
            if (i % 23 == 0) volts = Double.NaN;
            compare(cache, soc, volts, lat, lon, i % 7 != 0);
        }
    }

    @Test
    public void missingValuesAndRecoveryDoNotReuseStaleText() {
        OverlayValueText cache = new OverlayValueText();
        assertEquals("80%", cache.soc(80));
        assertEquals("--%", cache.soc(-1));
        assertEquals("81%", cache.soc(81));
        assertEquals("12.10V", cache.volts(12.1));
        assertEquals("--.-V", cache.volts(0));
        assertEquals("12.20V", cache.volts(12.2));
        assertEquals("-0.00000", cache.coordinate(-0.0, true, true));
        assertEquals("0.00000", cache.coordinate(0.0, true, true));
        assertEquals("lat --", cache.coordinate(0.0, false, true));
        assertEquals("lon --", cache.coordinate(0.0, false, false));
        assertEquals("1.23456", cache.coordinate(1.23456, true, true));
        assertEquals("-9.87654", cache.coordinate(-9.87654, true, false));
        assertEquals("1.23456", cache.coordinate(1.23456, true, true));
    }

    @Test
    public void measureAndDrawReuseIndependentStrings() {
        OverlayValueText cache = new OverlayValueText();
        String soc = cache.soc(84.6);
        String volts = cache.volts(12.51);
        String latitude = cache.coordinate(47.123456, true, true);
        String longitude = cache.coordinate(8.654321, true, false);
        for (int i = 0; i < 100; i++) {
            assertSame(soc, cache.soc(84.6));
            assertSame(volts, cache.volts(12.51));
            assertSame(latitude, cache.coordinate(47.123456, true, true));
            assertSame(longitude, cache.coordinate(8.654321, true, false));
        }
    }

    private static void compare(OverlayValueText cache, double soc, double volts,
                                double lat, double lon, boolean hasLoc) {
        String expectedSoc = Double.isNaN(soc) || soc < 0 ? "--%"
                : String.format(Locale.US, "%.0f%%", soc);
        String expectedVolts = Double.isNaN(volts) || volts <= 0 ? "--.-V"
                : String.format(Locale.US, "%.2fV", volts);
        String expectedLat = !hasLoc || Double.isNaN(lat) ? "lat --"
                : String.format(Locale.US, "%.5f", lat);
        String expectedLon = !hasLoc || Double.isNaN(lon) ? "lon --"
                : String.format(Locale.US, "%.5f", lon);
        for (int pass = 0; pass < 2; pass++) {
            assertEquals(expectedSoc, cache.soc(soc));
            assertEquals(expectedVolts, cache.volts(volts));
            assertEquals(expectedLat, cache.coordinate(lat, hasLoc, true));
            assertEquals(expectedLon, cache.coordinate(lon, hasLoc, false));
        }
    }
}
