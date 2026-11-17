package com.overdrive.app.monitor;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Local calendar-day keys for persisted network usage history.
 *
 * <p>The sampler and HTTP handler use separate threads. Each gets its own
 * formatter because {@link SimpleDateFormat} is mutable and not thread-safe.
 * Callers pass the zone, so the key uses whatever zone they read.
 */
final class DataUsageDayKeys {

    private static final ThreadLocal<SimpleDateFormat> FORMATTERS =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.US));

    private DataUsageDayKeys() {}

    /** yyyy-MM-dd for an instant in the specified local time zone. */
    static String format(long epochMs, TimeZone zone) {
        SimpleDateFormat formatter = FORMATTERS.get();
        formatter.setTimeZone(zone);
        return formatter.format(new Date(epochMs));
    }

    /**
     * Inclusive first date in a "last N calendar days" window. Unlike subtracting
     * {@code 86400000L * (days - 1)}, this works when a local day is 23 or 25
     * hours long due to daylight-saving transitions.
     */
    static String firstDay(long epochMs, int days, TimeZone zone) {
        if (days < 1) {
            throw new IllegalArgumentException("days must be positive");
        }
        Calendar day = Calendar.getInstance(zone, Locale.US);
        day.setTimeInMillis(epochMs);
        day.add(Calendar.DAY_OF_MONTH, 1 - days);
        return format(day.getTimeInMillis(), zone);
    }
}
