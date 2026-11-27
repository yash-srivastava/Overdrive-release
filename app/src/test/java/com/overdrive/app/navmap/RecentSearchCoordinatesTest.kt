package com.overdrive.app.navmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentSearchCoordinatesTest {
    @Test
    fun coordinateValidationRejectsOutOfRangeAndNonFiniteValues() {
        for (lat in listOf(-91.0, 91.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(RecentSearchStore.validCoordinates(lat, 8.0))
        }
        for (lng in listOf(-181.0, 181.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(RecentSearchStore.validCoordinates(47.0, lng))
        }
    }

    @Test
    fun coordinateValidationAcceptsBoundariesAndZeroCoordinates() {
        for (lat in listOf(-90.0, 0.0, 90.0)) {
            for (lng in listOf(-180.0, 0.0, 180.0)) {
                assertTrue(RecentSearchStore.validCoordinates(lat, lng))
            }
        }
    }

    @Test
    fun parseSkipsInvalidCoordinatesAndPreservesOrderAndMetadata() {
        val entries = RecentSearchStore.parse(
            """[
                {"label":"First","lat":47,"lng":8,"placeId":"test-id",
                 "secondary":"Example address","source":"google","savedAtMs":123456789},
                {"label":"Invalid latitude","lat":91,"lng":8},
                {"label":"Invalid longitude","lat":47,"lng":-181},
                {"label":"Infinite latitude","lat":"Infinity","lng":8},
                {"label":"Infinite longitude","lat":47,"lng":"-Infinity"},
                {"label":"NaN","lat":"NaN","lng":8},
                {"label":"Missing latitude","lng":8},
                {"label":"Missing longitude","lat":47},
                {"label":"Invalid number","lat":47,"lng":"unknown"},
                {"label":"Equator","lat":0,"lng":12},
                {"label":"Boundary","lat":-90,"lng":180},
                {"label":"Origin","lat":0,"lng":0}
            ]""".trimIndent()
        )

        assertEquals(listOf("First", "Equator", "Boundary", "Origin"), entries.map { it.result.label })
        assertEquals(listOf(47.0, 0.0, -90.0, 0.0), entries.map { it.result.lat })
        assertEquals(listOf(8.0, 12.0, 180.0, 0.0), entries.map { it.result.lng })
        assertEquals("test-id", entries[0].result.placeId)
        assertEquals("Example address", entries[0].result.secondary)
        assertEquals("google", entries[0].result.source)
        assertEquals(123456789L, entries[0].savedAtMs)
        assertEquals(0L, entries[1].savedAtMs)
    }
}
