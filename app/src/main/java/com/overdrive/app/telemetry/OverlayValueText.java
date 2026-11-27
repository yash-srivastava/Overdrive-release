package com.overdrive.app.telemetry;

import java.util.Locale;

/** Last-value text cache owned by one overlay raster worker. */
final class OverlayValueText {
    private final NumberText soc = new NumberText("%.0f%%");
    private final NumberText volts = new NumberText("%.2fV");
    private final NumberText latitude = new NumberText("%.5f");
    private final NumberText longitude = new NumberText("%.5f");

    String soc(double value) {
        return Double.isNaN(value) || value < 0 ? "--%" : soc.format(value);
    }

    String volts(double value) {
        return Double.isNaN(value) || value <= 0 ? "--.-V" : volts.format(value);
    }

    String coordinate(double value, boolean hasLoc, boolean isLat) {
        if (!hasLoc || Double.isNaN(value)) return isLat ? "lat --" : "lon --";
        return (isLat ? latitude : longitude).format(value);
    }

    private static final class NumberText {
        private final String pattern;
        private long bits;
        private String text;

        NumberText(String pattern) {
            this.pattern = pattern;
        }

        String format(double value) {
            // Exact key: signed zero and rounding boundaries must stay distinct.
            long key = Double.doubleToLongBits(value);
            if (text == null || key != bits) {
                text = String.format(Locale.US, pattern, value);
                bits = key;
            }
            return text;
        }
    }
}
