package com.overdrive.app.navmap.google

import com.overdrive.app.navmap.nav.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GooglePlacesCoordinatesTest {
    private val prediction = SearchResult("Destination", Double.NaN, Double.NaN, "place-id")

    private fun details(location: String): SearchResult? =
        GooglePlacesClient.parseDetails("""{"location":$location}""", prediction)

    @Test
    fun detailsRejectsPresentMalformedCoordinates() {
        for (invalid in listOf("null", "true", "{}", "[]", "\"unknown\"", "\"\"", "\"NaN\"", "\"Infinity\"")) {
            assertNull("Invalid latitude: $invalid", details("""{"latitude":$invalid,"longitude":8}"""))
            assertNull("Invalid longitude: $invalid", details("""{"latitude":47,"longitude":$invalid}"""))
        }
    }

    @Test
    fun detailsPreservesOmittedAndExplicitZeroCoordinates() {
        val cases = listOf(
            """{"longitude":8}""" to (0.0 to 8.0),
            """{"latitude":47}""" to (47.0 to 0.0),
            """{"latitude":0,"longitude":0}""" to (0.0 to 0.0),
            """{"latitude":"47.5","longitude":"8.5"}""" to (47.5 to 8.5),
            """{"latitude":-90,"longitude":180}""" to (-90.0 to 180.0)
        )
        for ((location, expected) in cases) {
            val result = details(location)
            assertNotNull(location, result)
            assertEquals(expected.first, result!!.lat, 0.0)
            assertEquals(expected.second, result.lng, 0.0)
            assertEquals(prediction.label, result.label)
            assertEquals(prediction.placeId, result.placeId)
        }
        assertNull(details("{}"))
        assertNull(details("""{"latitude":91,"longitude":8}"""))
        assertNull(details("""{"latitude":47,"longitude":-181}"""))
    }

    @Test
    fun textSearchSkipsMalformedEntriesWithoutConsumingResultLimit() {
        val json = """{
            "places": [
                {"id":"bad-lat","formattedAddress":"Bad latitude","location":{"latitude":"unknown","longitude":8}},
                {"id":"first","formattedAddress":"First","location":{"latitude":47,"longitude":8}},
                {"id":"bad-lng","formattedAddress":"Bad longitude","location":{"latitude":47,"longitude":null}},
                {"id":"second","formattedAddress":"Second","location":{"longitude":12}},
                {"id":"third","formattedAddress":"Third","location":{"latitude":13,"longitude":14}}
            ]
        }""".trimIndent()
        val results = GooglePlacesClient.parseTextSearch(json, 2)!!
        assertEquals(listOf("first", "second"), results.map { it.placeId })
        assertEquals(listOf("First", "Second"), results.map { it.label })
        assertEquals(47.0, results[0].lat, 0.0)
        assertEquals(8.0, results[0].lng, 0.0)
        assertEquals(0.0, results[1].lat, 0.0)
        assertEquals(12.0, results[1].lng, 0.0)
    }
}
