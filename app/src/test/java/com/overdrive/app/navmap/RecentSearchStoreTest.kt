package com.overdrive.app.navmap

import com.overdrive.app.navmap.nav.SearchResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentSearchStoreTest {

    @Test
    fun matchingPlaceIdsIdentifyUnresolvedPredictions() {
        val a = SearchResult("First label", Double.NaN, Double.NaN, placeId = "place-1")
        val b = SearchResult("Updated label", 47.0, 8.0, placeId = "place-1")
        assertTrue(RecentSearchStore.isSamePlace(a, b))
        assertTrue(RecentSearchStore.isSamePlace(b, a))
    }

    @Test
    fun matchingLabelsIgnoreCaseAndSurroundingWhitespace() {
        val a = SearchResult("  Central Station ", 47.0, 8.0)
        val b = SearchResult("central station", 48.0, 9.0)
        assertTrue(RecentSearchStore.isSamePlace(a, b))
    }

    @Test
    fun coordinateToleranceIsInclusiveAndRequiredOnBothAxes() {
        // Use zero as the origin so subtracting the boundary value introduces no rounding.
        val origin = SearchResult("Origin", 0.0, 0.0)
        assertTrue(RecentSearchStore.isSamePlace(origin, SearchResult("Near", 0.00005, -0.00005)))
        assertTrue(RecentSearchStore.isSamePlace(origin, SearchResult("Boundary", 0.0001, -0.0001)))
        assertFalse(RecentSearchStore.isSamePlace(origin, SearchResult("North", 0.00011, 0.0)))
        assertFalse(RecentSearchStore.isSamePlace(origin, SearchResult("East", 0.0, 0.00011)))
    }

    @Test
    fun absentIdsAndUnresolvedCoordinatesDoNotMatchDifferentLabels() {
        val a = SearchResult("First", Double.NaN, Double.NaN)
        val b = SearchResult("Second", Double.NaN, Double.NaN)
        assertFalse(RecentSearchStore.isSamePlace(a, b))
        assertFalse(RecentSearchStore.isSamePlace(a.copy(placeId = "a"), b.copy(placeId = "b")))
        assertFalse(RecentSearchStore.isSamePlace(
            SearchResult("First", 47.0, 8.0), SearchResult("Second", 48.0, 9.0)
        ))
    }

    @Test
    fun providerMetadataSurvivesJsonRoundTrip() {
        val result = SearchResult("Station", 47.0, 8.0, "place-1", "City centre", "google")
        val json = JSONObject()
        RecentSearchStore.putPlaceFields(json, result)

        assertEquals("place-1", json.getString("placeId"))
        assertEquals("City centre", json.getString("secondary"))
        assertEquals("google", json.getString("source"))
        val restored = RecentSearchStore.readPlace(JSONObject(json.toString()), result.label, result.lat, result.lng)
        assertEquals(result, restored)
    }

    @Test
    fun absentAndMalformedMetadataRemainOptional() {
        val legacy = SearchResult("Station", 47.0, 8.0)
        val json = JSONObject()
        RecentSearchStore.putPlaceFields(json, legacy)
        assertFalse(json.has("placeId"))
        assertFalse(json.has("secondary"))
        assertFalse(json.has("source"))
        assertEquals(legacy, RecentSearchStore.readPlace(json, legacy.label, legacy.lat, legacy.lng))

        json.put("placeId", 42)
        json.put("secondary", "   ")
        json.put("source", JSONObject.NULL)
        assertEquals(legacy, RecentSearchStore.readPlace(json, legacy.label, legacy.lat, legacy.lng))
    }
}
