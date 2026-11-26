package com.overdrive.app.ui.util

import org.junit.Assert.assertEquals
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

    private fun calendar(zone: String, year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            clear()
            set(year, month, day, 12, 0, 0)
        }
}
