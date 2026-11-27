package com.overdrive.app.briefing.app

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TripBriefingSyncTest {
    @Test
    fun rejectsInvalidCoordinatesOnEitherAxis() {
        for (lat in listOf(-90.01, 90.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull("latitude $lat", TripBriefingSync.place("Place", lat, 8.0))
        }
        for (lng in listOf(-180.01, 180.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull("longitude $lng", TripBriefingSync.place("Place", 47.0, lng))
        }
    }

    @Test
    fun preservesValidCoordinatesAndTrimsLabels() {
        for ((lat, lng) in listOf(
            47.0 to 8.0, -90.0 to -180.0, 90.0 to 180.0,
            0.0 to 8.0, 47.0 to 0.0, 0.0 to 0.0
        )) {
            val place = TripBriefingSync.place("  Place  ", lat, lng)
            assertNotNull(place)
            assertEquals("Place", place!!.getString("label"))
            assertEquals(lat, place.getDouble("lat"), 0.0)
            assertEquals(lng, place.getDouble("lng"), 0.0)
        }
        assertNull(TripBriefingSync.place("   ", 47.0, 8.0))
    }

    @Test
    fun malformedEntryDoesNotPreventLaterPlacesFromBeingSerialized() {
        val recent = JSONArray()
        for ((label, lat, lng) in listOf(
            Triple("First", 47.0, 8.0),
            Triple("Invalid", Double.POSITIVE_INFINITY, 8.0),
            Triple("Out of range", 47.0, 181.0),
            Triple("Last", 46.0, 7.0)
        )) {
            TripBriefingSync.place(label, lat, lng)?.let { recent.put(it) }
        }
        val decoded = JSONArray(recent.toString())
        assertEquals(2, decoded.length())
        assertEquals("First", decoded.getJSONObject(0).getString("label"))
        assertEquals("Last", decoded.getJSONObject(1).getString("label"))
    }
}
