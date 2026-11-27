package com.overdrive.app.navmap.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassPoiCoordinatesTest {

    @Test
    fun skipsInvalidCoordinatesWithoutLosingValidStations() {
        val json = """
            {"elements":[
                {"lat":47.37,"lon":8.54,"tags":{"amenity":"charging_station","name":" First "}},
                {"lat":91,"lon":8,"tags":{"amenity":"charging_station"}},
                {"lat":-90.01,"lon":8,"tags":{"amenity":"fuel"}},
                {"lat":47,"lon":180.01,"tags":{"amenity":"fuel"}},
                {"lat":47,"lon":-181,"tags":{"amenity":"charging_station"}},
                {"lat":"Infinity","lon":8,"tags":{"amenity":"fuel"}},
                {"lat":47,"lon":"-Infinity","tags":{"amenity":"charging_station"}},
                {"lat":"NaN","lon":8,"tags":{"amenity":"fuel"}},
                {"lon":8,"tags":{"amenity":"fuel"}},
                {"lat":47,"tags":{"amenity":"charging_station"}},
                {"lat":null,"lon":8,"tags":{"amenity":"fuel"}},
                {"lat":47,"lon":"not a number","tags":{"amenity":"fuel"}},
                {"lat":47.4,"lon":8.6,"tags":{"amenity":"fuel"}}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(
                RoutePoi(PoiKind.CHARGING, "First", 47.37, 8.54),
                RoutePoi(PoiKind.FUEL, "", 47.4, 8.6)
            ),
            OverpassPoiClient.parseOverpass(json)
        )
    }

    @Test
    fun acceptsInclusiveGeographicBoundsAndZeroCoordinates() {
        val json = """
            {"elements":[
                {"lat":90,"lon":180,"tags":{"amenity":"fuel"}},
                {"lat":-90,"lon":-180,"tags":{"amenity":"fuel"}},
                {"lat":0,"lon":12,"tags":{"amenity":"charging_station"}},
                {"lat":12,"lon":0,"tags":{"amenity":"charging_station"}},
                {"lat":0,"lon":0,"tags":{"amenity":"fuel"}}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(
                RoutePoi(PoiKind.FUEL, "", 90.0, 180.0),
                RoutePoi(PoiKind.FUEL, "", -90.0, -180.0),
                RoutePoi(PoiKind.CHARGING, "", 0.0, 12.0),
                RoutePoi(PoiKind.CHARGING, "", 12.0, 0.0),
                RoutePoi(PoiKind.FUEL, "", 0.0, 0.0)
            ),
            OverpassPoiClient.parseOverpass(json)
        )
    }

    @Test
    fun returnsEmptyWhenEveryStationHasInvalidCoordinates() {
        val json = """
            {"elements":[
                {"lat":100,"lon":10,"tags":{"amenity":"charging_station"}},
                {"lat":10,"lon":200,"tags":{"amenity":"fuel"}}
            ]}
        """.trimIndent()

        assertTrue(OverpassPoiClient.parseOverpass(json).isEmpty())
    }
}
