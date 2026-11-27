package com.overdrive.app.ui.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TimelineLinksTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun millis(tz: TimeZone, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(tz).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun rejectsImpossibleCalendarDates() {
        for (date in listOf("20240230", "20230229", "20240431", "20241301", "20240100")) {
            assertNull(date, TimelineLinks.timeFromFilename("cam1_${date}_120000.mp4", utc))
        }
    }

    @Test
    fun acceptsLeapDayAndKeepsReplayBackoff() {
        val expected = millis(utc, 2024, 2, 29, 12, 0)
        assertEquals(expected, TimelineLinks.timeFromFilename("cam1_20240229_120000.mp4", utc))
        assertEquals(expected - 1000L, TimelineLinks.timeFromFilename("replay_20240229_120000_2.mp4", utc))
    }

    @Test
    fun preservesLenientLocalDstGapResolution() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val expected = millis(zone, 2024, 3, 10, 3, 30)
        assertEquals(expected, TimelineLinks.timeFromFilename("event_20240310_023000.mp4", zone))
    }

    @Test
    fun invalidFilenameDoesNotRedirectRememberedView() {
        val now = millis(utc, 2024, 3, 5, 12, 0)
        assertNull(TimelineLinks.route(null, null, null, "cam1_20240230_120000.mp4", true, now, utc))
    }
}
