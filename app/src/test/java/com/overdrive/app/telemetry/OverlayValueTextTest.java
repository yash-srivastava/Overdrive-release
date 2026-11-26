package com.overdrive.app.telemetry;

import org.junit.Test;

import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class OverlayValueTextTest {
    @Test
    public void matchesOriginalFormattingAcrossValueChanges() {
        OverlayValueText cache = new OverlayValueText();
        double[] edges = {0.0, -0.0, 0.5, 1.005, -1.005, 12.345, 99.5,
                Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.MIN_VALUE, Double.MAX_VALUE, -180, 180, 0.000005, -0.000005};
        for (double v : edges) {
            check(cache, v, true);
            check(cache, v, false);
            check(cache, v, true);
        }
        Random random = new Random(24);
        for (int i = 0; i < 2000; i++) {
            double v = random.nextDouble() * 360 - 180;
            boolean hasLocation = random.nextBoolean();
            check(cache, v, hasLocation);
            check(cache, v, hasLocation);
        }
    }

    @Test
    public void reusesTextAndKeepsFieldsIndependent() {
        OverlayValueText cache = new OverlayValueText();
        String soc = cache.soc(56.75);
        String volts = cache.volts(12.345);
        String lat = cache.latitude(47.123456, true);
        String lon = cache.longitude(8.123456, true);
        for (int i = 0; i < 1000; i++) {
            assertSame(soc, cache.soc(56.75));
            assertSame(volts, cache.volts(12.345));
            assertSame(lat, cache.latitude(47.123456, true));
            assertSame(lon, cache.longitude(8.123456, true));
        }
        assertEquals("lat --", cache.latitude(47.123456, false));
        assertEquals("lon --", cache.longitude(8.123456, false));
        assertSame(lat, cache.latitude(47.123456, true));
        assertSame(lon, cache.longitude(8.123456, true));
        assertEquals("-0.00000", cache.latitude(-0.0, true));
        assertEquals("0.00000", cache.latitude(0.0, true));
        assertSame(lon, cache.longitude(8.123456, true));
        assertEquals("--%", cache.soc(Double.NaN));
        assertEquals("--.-V", cache.volts(0));
        assertSame(soc, cache.soc(56.75));
        assertSame(volts, cache.volts(12.345));
    }

    private static void check(OverlayValueText cache, double v, boolean hasLocation) {
        assertEquals(Double.isNaN(v) || v < 0 ? "--%" : String.format(Locale.US, "%.0f%%", v),
                cache.soc(v));
        assertEquals(Double.isNaN(v) || v <= 0 ? "--.-V" : String.format(Locale.US, "%.2fV", v),
                cache.volts(v));
        assertEquals(!hasLocation || Double.isNaN(v) ? "lat --" : String.format(Locale.US, "%.5f", v),
                cache.latitude(v, hasLocation));
        assertEquals(!hasLocation || Double.isNaN(v) ? "lon --" : String.format(Locale.US, "%.5f", v),
                cache.longitude(v, hasLocation));
    }
}
