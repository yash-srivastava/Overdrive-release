package com.overdrive.app.telemetry;

import java.util.Locale;

/** Last-value numeric text cache owned by a single overlay raster worker. */
final class OverlayNumericLabels {
    private final Label soc = new Label("%.0f%%");
    private final Label voltage = new Label("%.2fV");
    private final Label latitude = new Label("%.5f");
    private final Label longitude = new Label("%.5f");

    String soc(double value) {
        return Double.isNaN(value) || value < 0 ? "--%" : soc.get(value);
    }

    String voltage(double value) {
        return Double.isNaN(value) || value <= 0 ? "--.-V" : voltage.get(value);
    }

    String latitude(double value, boolean hasLocation) {
        return !hasLocation || Double.isNaN(value) ? "lat --" : latitude.get(value);
    }

    String longitude(double value, boolean hasLocation) {
        return !hasLocation || Double.isNaN(value) ? "lon --" : longitude.get(value);
    }

    private static final class Label {
        private final String format;
        private long bits;
        private String text;

        Label(String format) {
            this.format = format;
        }

        String get(double value) {
            // Exact inputs, including signed zero, preserve Formatter rounding
            // and negative-zero output. Never quantize or cache raw glyph state.
            long key = Double.doubleToLongBits(value);
            if (text == null || bits != key) {
                text = String.format(Locale.US, format, value);
                bits = key;
            }
            return text;
        }
    }
}
