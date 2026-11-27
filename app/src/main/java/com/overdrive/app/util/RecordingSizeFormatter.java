package com.overdrive.app.util;

import java.util.Locale;

/** Shared decimal byte-size labels for recordings and their storage statistics. */
public final class RecordingSizeFormatter {
    private RecordingSizeFormatter() {}

    /** Format using the device's current formatting locale (native UI). */
    public static String format(long bytes) {
        return format(bytes, Locale.getDefault(Locale.Category.FORMAT));
    }

    /** Format using an explicit locale (the recordings API uses Locale.US). */
    public static String format(long bytes, Locale locale) {
        if (bytes >= 1_000_000_000L) return String.format(locale, "%.1f GB", bytes / 1_000_000_000.0);
        if (bytes >= 1_000_000L) return String.format(locale, "%.1f MB", bytes / 1_000_000.0);
        if (bytes >= 1_000L) return String.format(locale, "%.1f KB", bytes / 1_000.0);
        return String.format(locale, "%d B", bytes);
    }
}
