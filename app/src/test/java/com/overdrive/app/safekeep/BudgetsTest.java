package com.overdrive.app.safekeep;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.util.Locale;
import java.util.TimeZone;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Calendar-boundary regression tests for monthly mobile-data budget periods. */
public class BudgetsTest {

    private TimeZone originalZone;
    private Locale originalFormatLocale;

    @Before
    public void setUp() {
        originalZone = TimeZone.getDefault();
        originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(originalZone);
        Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale);
    }

    @Test
    public void resetOccursAtMidnightOnConfiguredDay() {
        assertPeriod(15, "2024-06-14T23:59:59.999Z", "2024-05-15", "2024-06-15");
        assertPeriod(15, "2024-06-15T00:00:00Z", "2024-06-15", "2024-07-15");
    }

    @Test
    public void periodAndNextResetCrossYearBoundary() {
        assertPeriod(15, "2023-12-31T23:59:59Z", "2023-12-15", "2024-01-15");
        assertPeriod(15, "2024-01-01T00:00:00Z", "2023-12-15", "2024-01-15");
        assertPeriod(15, "2024-01-15T00:00:00Z", "2024-01-15", "2024-02-15");
    }

    @Test
    public void dayTwentyEightWorksInLeapAndOrdinaryFebruary() {
        assertPeriod(28, "2023-02-27T23:59:59Z", "2023-01-28", "2023-02-28");
        assertPeriod(28, "2023-02-28T23:59:59Z", "2023-02-28", "2023-03-28");
        assertPeriod(28, "2024-02-29T12:00:00Z", "2024-02-28", "2024-03-28");
    }

    @Test
    public void outOfRangeResetDaysAreClamped() {
        assertPeriod(0, "2024-04-01T00:00:00Z", "2024-04-01", "2024-05-01");
        assertPeriod(-5, "2024-04-30T12:00:00Z", "2024-04-01", "2024-05-01");
        assertPeriod(31, "2024-04-27T12:00:00Z", "2024-03-28", "2024-04-28");
        assertPeriod(31, "2024-04-28T00:00:00Z", "2024-04-28", "2024-05-28");
    }

    @Test
    public void localMidnightRatherThanUtcMidnightStartsNewPeriod() {
        // Zurich is UTC+2 in July, so local July 15 starts on UTC July 14.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"));
        assertPeriod(15, "2024-07-14T21:59:59.999Z", "2024-06-15", "2024-07-15");
        assertPeriod(15, "2024-07-14T22:00:00Z", "2024-07-15", "2024-08-15");
    }

    @Test
    public void budgetKeysRemainAsciiUnderArabicFormattingLocale() {
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
        assertPeriod(15, "2024-06-15T12:00:00Z", "2024-06-15", "2024-07-15");
    }

    private static void assertPeriod(int resetDay, String instant, String period, String next) {
        long now = Instant.parse(instant).toEpochMilli();
        assertEquals(period, Budgets.periodKey(resetDay, now));
        assertEquals(next, Budgets.nextReset(resetDay, now));
    }
}
