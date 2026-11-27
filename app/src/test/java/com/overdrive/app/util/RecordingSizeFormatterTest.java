package com.overdrive.app.util;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class RecordingSizeFormatterTest {
    @Test
    public void preservesDecimalThresholdsAndRounding() {
        assertEquals("-1 B", RecordingSizeFormatter.format(-1, Locale.US));
        assertEquals("0 B", RecordingSizeFormatter.format(0, Locale.US));
        assertEquals("999 B", RecordingSizeFormatter.format(999, Locale.US));
        assertEquals("1.0 KB", RecordingSizeFormatter.format(1_000, Locale.US));
        assertEquals("1000.0 KB", RecordingSizeFormatter.format(999_999, Locale.US));
        assertEquals("1.0 MB", RecordingSizeFormatter.format(1_000_000, Locale.US));
        assertEquals("1000.0 MB", RecordingSizeFormatter.format(999_999_999, Locale.US));
        assertEquals("1.0 GB", RecordingSizeFormatter.format(1_000_000_000, Locale.US));
        assertEquals("1500.0 GB", RecordingSizeFormatter.format(1_500_000_000_000L, Locale.US));
    }

    @Test
    public void preservesExplicitAndCurrentFormattingLocales() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            assertEquals("1,5 KB", RecordingSizeFormatter.format(1_500));
            assertEquals("1.5 KB", RecordingSizeFormatter.format(1_500, Locale.US));
            assertEquals("999 B", RecordingSizeFormatter.format(999));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    @Test
    public void localizesByteDigitsWithoutChangingUsApiLabels() {
        Locale arabic = Locale.forLanguageTag("ar-EG-u-nu-arab");
        assertEquals("\u0660 B", RecordingSizeFormatter.format(0, arabic));
        assertEquals("\u0669\u0669\u0669 B", RecordingSizeFormatter.format(999, arabic));
        assertEquals("\u0661\u0662\u0663 B", RecordingSizeFormatter.format(123, arabic));
        assertEquals("999 B", RecordingSizeFormatter.format(999, Locale.US));

        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, arabic);
            assertEquals("\u0669\u0669\u0669 B", RecordingSizeFormatter.format(999));
            assertEquals("999 B", RecordingSizeFormatter.format(999, Locale.US));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }
}
