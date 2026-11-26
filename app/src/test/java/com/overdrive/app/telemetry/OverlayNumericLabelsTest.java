package com.overdrive.app.telemetry;

import org.junit.Test;

import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class OverlayNumericLabelsTest {
    @Test
    public void unchangedInputsReuseTextAcrossMeasureAndDraw() {
        OverlayNumericLabels labels = new OverlayNumericLabels();
        String soc = labels.soc(82.5);
        String voltage = labels.voltage(12.345);
        String lat = labels.latitude(-12.345678, true);
        String lon = labels.longitude(98.765432, true);
        for (int i = 0; i < 7200; i++) {
            assertSame(soc, labels.soc(82.5));
            assertSame(voltage, labels.voltage(12.345));
            assertSame(lat, labels.latitude(-12.345678, true));
            assertSame(lon, labels.longitude(98.765432, true));
        }
    }

    @Test
    public void matchesOriginalFormattingForChangedAndRepeatedValues() {
        OverlayNumericLabels labels = new OverlayNumericLabels();
        Random random = new Random(34);
        double[] edges = {0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.MIN_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, 0.005, 0.015, 12.345, 99.5, -0.000005};
        for (double value : edges) compare(labels, value, true);
        for (int i = 0; i < 2000; i++) {
            double value = (random.nextInt(36000001) - 18000000) / 100000.0;
            boolean hasLocation = random.nextBoolean();
            compare(labels, value, hasLocation);
            compare(labels, value, hasLocation);
        }
    }

    @Test
    public void unavailableValuesNeverShowCachedReadings() {
        OverlayNumericLabels labels = new OverlayNumericLabels();
        String lat = labels.latitude(12.3, true);
        String lon = labels.longitude(45.6, true);
        assertEquals("lat --", labels.latitude(12.3, false));
        assertEquals("lon --", labels.longitude(Double.NaN, true));
        assertSame(lat, labels.latitude(12.3, true));
        assertSame(lon, labels.longitude(45.6, true));
        assertEquals("80%", labels.soc(80));
        assertEquals("--%", labels.soc(-1));
        assertEquals("81%", labels.soc(81));
        assertEquals("12.30V", labels.voltage(12.3));
        assertEquals("--.-V", labels.voltage(0));
        assertEquals("12.40V", labels.voltage(12.4));
        assertEquals("0.00000", labels.latitude(0.0, true));
        assertEquals("-0.00000", labels.latitude(-0.0, true));
    }

    private static void compare(OverlayNumericLabels labels, double value, boolean hasLocation) {
        assertEquals(Double.isNaN(value) || value < 0 ? "--%"
                : String.format(Locale.US, "%.0f%%", value), labels.soc(value));
        assertEquals(Double.isNaN(value) || value <= 0 ? "--.-V"
                : String.format(Locale.US, "%.2fV", value), labels.voltage(value));
        String coord = !hasLocation || Double.isNaN(value) ? null
                : String.format(Locale.US, "%.5f", value);
        assertEquals(coord == null ? "lat --" : coord, labels.latitude(value, hasLocation));
        assertEquals(coord == null ? "lon --" : coord, labels.longitude(value, hasLocation));
    }
}
