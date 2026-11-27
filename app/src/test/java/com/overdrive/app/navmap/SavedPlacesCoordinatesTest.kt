package com.overdrive.app.navmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlacesCoordinatesTest {
    @Test
    fun skipsMalformedCoordinatesWithoutLosingValidPlaces() {
        val places = SavedPlacesStore.parse(
            """[
                {"kind":"home","label":"Home","lat":47.0,"lng":8.0},
                {"label":"Invalid latitude","lat":91,"lng":8},
                {"label":"Invalid longitude","lat":47,"lng":-181},
                {"label":"Infinite latitude","lat":"Infinity","lng":8},
                {"label":"Infinite longitude","lat":47,"lng":"-Infinity"},
                {"label":"NaN","lat":"NaN","lng":8},
                {"label":"Missing latitude","lng":8},
                {"label":"Missing longitude","lat":47},
                {"label":"Invalid number","lat":47,"lng":"unknown"},
                {"kind":"work","label":"Work","lat":48.0,"lng":9.0,
                 "placeId":"test-id","secondary":"Example address","source":"google"}
            ]""".trimIndent()
        )

        assertEquals(listOf("Home", "Work"), places.map { it.result.label })
        assertEquals(listOf(SavedPlacesStore.KIND_HOME, SavedPlacesStore.KIND_WORK), places.map { it.kind })
        assertEquals(47.0, places[0].result.lat, 0.0)
        assertEquals(9.0, places[1].result.lng, 0.0)
        assertEquals("test-id", places[1].result.placeId)
        assertEquals("Example address", places[1].result.secondary)
        assertEquals("google", places[1].result.source)
    }

    @Test
    fun preservesGeographicBoundariesAndZeroCoordinates() {
        val places = SavedPlacesStore.parse(
            """[
                {"label":"North east","lat":90,"lng":180},
                {"label":"South west","lat":-90,"lng":-180},
                {"label":"Equator","lat":0,"lng":12},
                {"label":"Prime meridian","lat":12,"lng":0},
                {"label":"Origin","lat":0,"lng":0}
            ]""".trimIndent()
        )

        assertEquals(listOf(90.0, -90.0, 0.0, 12.0, 0.0), places.map { it.result.lat })
        assertEquals(listOf(180.0, -180.0, 12.0, 0.0, 0.0), places.map { it.result.lng })
        assertTrue(places.all { it.kind == SavedPlacesStore.KIND_CUSTOM })
    }
}
