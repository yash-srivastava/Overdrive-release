package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** Calendar-day history boundaries, independent of Android and the H2 store. */
public class DataUsageDayKeysTest {

    private static final TimeZone ZURICH = TimeZone.getTimeZone("Europe/Zurich");
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Test
    public void springForwardIncludesPreviousLocalDayEvenWhenOnly23HoursLong() {
        // 2026-03-30 00:30 CEST minus 24h is 2026-03-28 23:30 CET.
        // The two-calendar-day window must start on March 29, not March 28.
        long now = localTime(ZURICH, 2026, Calendar.MARCH, 30, 0, 30);
        assertEquals("2026-03-29", DataUsageDayKeys.firstDay(now, 2, ZURICH));
        assertEquals("2026-03-30", DataUsageDayKeys.format(now, ZURICH));
    }

    @Test
    public void fallBackIncludesPreviousLocalDayEvenWhenCurrentDayHas25Hours() {
        // 2026-10-25 23:30 CET minus 24h still lies on October 25 CEST.
        // The two-calendar-day window must start on October 24.
        long now = localTime(ZURICH, 2026, Calendar.OCTOBER, 25, 23, 30);
        assertEquals("2026-10-24", DataUsageDayKeys.firstDay(now, 2, ZURICH));
        assertEquals("2026-10-25", DataUsageDayKeys.format(now, ZURICH));
    }

    @Test
    public void threadLocalFormatterUsesTimeZoneSuppliedOnEveryCall() {
        long instant = localTime(UTC, 2026, Calendar.NOVEMBER, 12, 23, 30);
        assertEquals("2026-11-12", DataUsageDayKeys.format(instant, UTC));
        assertEquals("2026-11-13", DataUsageDayKeys.format(instant, ZURICH));
        // The same thread must not retain the timezone of its previous call.
        assertEquals("2026-11-12", DataUsageDayKeys.format(instant, UTC));
    }

    @Test
    public void oneDayWindowStartsOnTheCurrentLocalDate() {
        long now = localTime(ZURICH, 2026, Calendar.MARCH, 30, 0, 30);
        assertEquals("2026-03-30", DataUsageDayKeys.firstDay(now, 1, ZURICH));
    }

    private static long localTime(TimeZone zone, int year, int month, int day,
            int hour, int minute) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
