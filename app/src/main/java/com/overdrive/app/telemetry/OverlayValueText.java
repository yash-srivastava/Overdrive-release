package com.overdrive.app.telemetry;

import java.util.Locale;

/** Raster-worker-confined, last-value caches shared by measurement and drawing. */
final class OverlayValueText {
    private final LastValue soc = new LastValue("%.0f%%");
    private final LastValue volts = new LastValue("%.2fV");
    private final LastValue latitude = new LastValue("%.5f");
    private final LastValue longitude = new LastValue("%.5f");

    String soc(double value) {
        return Double.isNaN(value) || value < 0 ? "--%" : soc.format(value);
    }

    String volts(double value) {
        return Double.isNaN(value) || value <= 0 ? "--.-V" : volts.format(value);
    }

    String latitude(double value, boolean hasLocation) {
        return !hasLocation || Double.isNaN(value) ? "lat --" : latitude.format(value);
    }

    String longitude(double value, boolean hasLocation) {
        return !hasLocation || Double.isNaN(value) ? "lon --" : longitude.format(value);
    }

    private static final class LastValue {
        private final String pattern;
        private long bits;
        private String text;

        LastValue(String pattern) {
            this.pattern = pattern;
        }

        String format(double value) {
            // Exact value keys preserve Formatter rounding, including signed zero.
            long nextBits = Double.doubleToLongBits(value);
            if (text == null || bits != nextBits) {
                text = String.format(Locale.US, pattern, value);
                bits = nextBits;
            }
            return text;
        }
    }
}
