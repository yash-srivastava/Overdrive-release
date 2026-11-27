package com.overdrive.app.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RecordingDateSelectionTest {
    @Test
    fun pickerUsesUtcCivilDateAcrossTimeZones() {
        for (zone in listOf("America/Los_Angeles", "Pacific/Auckland")) {
            val month = calendar(zone, 2024, Calendar.FEBRUARY, 1)
            val millis = RecordingDateSelection.utcMidnightMillis(month, 29)
            assertEquals(1709164800000L, millis)
            val selected = RecordingDateSelection.fromPickerMillis(millis)
            assertEquals(Calendar.FEBRUARY, selected.get(Calendar.MONTH))
            assertEquals(29, selected.get(Calendar.DAY_OF_MONTH))
            assertEquals(0, selected.get(Calendar.HOUR_OF_DAY))
        }
    }

    @Test
    fun shiftCrossesMonthAndYearAndRejectsFutureDays() {
        val now = calendar("Europe/Zurich", 2025, Calendar.JANUARY, 1)
        val month = calendar("Europe/Zurich", 2024, Calendar.DECEMBER, 1)
        val next = RecordingDateSelection.shiftDay(month, 31, 1, now)!!
        assertEquals(2025, next.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, next.get(Calendar.MONTH))
        assertEquals(1, next.get(Calendar.DAY_OF_MONTH))
        assertNull(RecordingDateSelection.shiftDay(month, 31, 2, now))
        val previous = RecordingDateSelection.shiftDay(now, 1, -1, now)!!
        assertEquals(2024, previous.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, previous.get(Calendar.MONTH))
        assertEquals(31, previous.get(Calendar.DAY_OF_MONTH))
        assertEquals(1, now.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun shiftUsesCalendarDaysAcrossDaylightSavingTransition() {
        val now = calendar("Europe/Zurich", 2024, Calendar.APRIL, 1)
        val month = calendar("Europe/Zurich", 2024, Calendar.MARCH, 1)
        val next = RecordingDateSelection.shiftDay(month, 30, 1, now)!!
        assertEquals(Calendar.MARCH, next.get(Calendar.MONTH))
        assertEquals(31, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, next.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun headerClassifiesLocalDaysAndKeepsInputsUnchanged() {
        val now = calendar("Europe/Zurich", 2025, Calendar.JANUARY, 1)
        val month = calendar("UTC", 2024, Calendar.DECEMBER, 1)
        val originalNow = now.timeInMillis
        val originalMonth = month.timeInMillis
        val yesterday = RecordingDateSelection.dayHeader(month, 31, now)
        assertEquals(RecordingDateSelection.RelativeDay.YESTERDAY, yesterday.relativeDay)
        assertTrue(yesterday.canAdvance)
        val today = RecordingDateSelection.dayHeader(now, 1, now)
        assertEquals(RecordingDateSelection.RelativeDay.TODAY, today.relativeDay)
        assertEquals(RecordingDateSelection.startOfTodayMillis(now), today.dayStartMs)
        assertFalse(today.canAdvance)
        val tomorrow = RecordingDateSelection.dayHeader(now, 2, now)
        assertEquals(RecordingDateSelection.RelativeDay.OTHER, tomorrow.relativeDay)
        assertFalse(tomorrow.canAdvance)
        assertEquals(originalNow, now.timeInMillis)
        assertEquals(originalMonth, month.timeInMillis)
    }

    @Test
    fun timelineHeaderUsesCalendarYesterdayAcrossDaylightSaving() {
        val now = calendar("Europe/Zurich", 2024, Calendar.APRIL, 1)
        val day = calendar("Europe/Zurich", 2024, Calendar.MARCH, 31)
        val header = RecordingDateSelection.dayHeader(day.timeInMillis, now)
        assertEquals(RecordingDateSelection.RelativeDay.YESTERDAY, header.relativeDay)
        assertEquals(23L * 60 * 60 * 1000,
            RecordingDateSelection.startOfTodayMillis(now) - header.dayStartMs)
        assertEquals(header, RecordingDateSelection.dayHeader(day, 31, now))
    }

    @Test
    fun headersClassifyYesterdayWhenDaylightSavingStartsAtMidnight() {
        // Sao Paulo skipped 00:00 on 4 November 2018, starting that day at 01:00.
        val zone = "America/Sao_Paulo"
        for (todayDate in listOf(4, 5)) {
            val now = calendar(zone, 2018, Calendar.NOVEMBER, todayDate)
            val day = calendar(zone, 2018, Calendar.NOVEMBER, todayDate - 1)
            val header = RecordingDateSelection.dayHeader(day.timeInMillis, now)
            val dayStart = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            assertEquals(RecordingDateSelection.RelativeDay.YESTERDAY, header.relativeDay)
            assertEquals(dayStart.timeInMillis, header.dayStartMs)
            assertTrue(header.canAdvance)
            assertEquals(header, RecordingDateSelection.dayHeader(day, todayDate - 1, now))

            val today = RecordingDateSelection.dayHeader(now.timeInMillis, now)
            assertEquals(RecordingDateSelection.RelativeDay.TODAY, today.relativeDay)
            assertFalse(today.canAdvance)
        }
    }

    private fun calendar(zone: String, year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            clear()
            set(year, month, day, 12, 0, 0)
        }
}
