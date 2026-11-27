package com.overdrive.app.briefing.engine

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class BriefingClockTest {

    @Test
    fun minuteGapWrapsAcrossMidnight() {
        assertEquals(10, minuteGap(1435, 5))
        assertEquals(10, minuteGap(5, 1435))
        assertEquals(0, minuteGap(480, 480))
        assertEquals(720, minuteGap(0, 720))
    }

    @Test
    fun circularMedianKeepsMidnightRoutinesNearMidnight() {
        assertEquals(0, circularMedianMinute(intArrayOf(1435, 5)))
        assertEquals(1438, circularMedianMinute(intArrayOf(5, 1430, 1438)))
        assertEquals(480, circularMedianMinute(intArrayOf(490, 470, 480)))
        assertEquals(481, circularMedianMinute(intArrayOf(480, 481)))
        assertEquals(720, circularMedianMinute(intArrayOf(720)))
        assertEquals(0, circularMedianMinute(intArrayOf()))
    }

    @Test
    fun fixedOffsetUsesLocalDateForWeekendClassification() {
        val clock = LocalClock(ZoneOffset.ofHoursMinutes(5, 30))
        // Friday UTC, but Saturday in the selected offset.
        val beforeMidnight = epochMs("2024-01-05T18:29:59Z")
        val midnight = epochMs("2024-01-05T18:30:00Z")
        assertEquals(1439, clock.minuteOfDay(beforeMidnight))
        assertEquals(4, clock.dayOfWeek(beforeMidnight))
        assertEquals(DayType.WEEKDAY.ordinal, clock.dayTypeIndex(beforeMidnight))
        assertEquals(0, clock.minuteOfDay(midnight))
        assertEquals(5, clock.dayOfWeek(midnight))
        assertEquals(DayType.WEEKEND.ordinal, clock.dayTypeIndex(midnight))
    }

    @Test
    fun daylightSavingUsesOffsetAtEachInstant() {
        val clock = LocalClock(ZoneId.of("Europe/Zurich"))
        // Spring: 01:59 is followed by 03:00 on the local clock.
        assertEquals(119, clock.minuteOfDay(epochMs("2024-03-31T00:59:59Z")))
        assertEquals(180, clock.minuteOfDay(epochMs("2024-03-31T01:00:00Z")))
        // Autumn: two different instants have the same local 02:30.
        val first = epochMs("2024-10-27T00:30:00Z")
        val second = epochMs("2024-10-27T01:30:00Z")
        assertEquals(150, clock.minuteOfDay(first))
        assertEquals(150, clock.minuteOfDay(second))
        assertEquals(6, clock.dayOfWeek(first))
        assertEquals(6, clock.dayOfWeek(second))
    }

    @Test
    fun customWeekendOverridesDefaultSaturdayAndSunday() {
        val clock = LocalClock(ZoneOffset.UTC, setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))
        assertEquals(DayType.WEEKEND.ordinal, clock.dayTypeIndex(epochMs("2024-01-05T12:00:00Z")))
        assertEquals(DayType.WEEKEND.ordinal, clock.dayTypeIndex(epochMs("2024-01-06T12:00:00Z")))
        assertEquals(DayType.WEEKDAY.ordinal, clock.dayTypeIndex(epochMs("2024-01-07T12:00:00Z")))
    }

    @Test
    fun negativeEpochMillisecondsUseFloorRatherThanTruncation() {
        val clock = LocalClock(ZoneOffset.UTC)
        assertEquals(1439, clock.minuteOfDay(-1L))
        assertEquals(2, clock.dayOfWeek(-1L)) // Wednesday, 1969-12-31.
        assertEquals(0, clock.minuteOfDay(0L))
        assertEquals(3, clock.dayOfWeek(0L)) // Thursday, 1970-01-01.
    }

    private fun epochMs(value: String): Long = Instant.parse(value).toEpochMilli()
}
