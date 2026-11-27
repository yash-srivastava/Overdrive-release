package com.overdrive.app.navmap.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardGeocoderCoordinatesTest {
    @Test
    fun photonAndStadiaSkipInvalidCoordinatesAndKeepValidResultsInOrder() {
        val response = """
            {"features":[
                {"geometry":{"coordinates":[8.5,47.3]},"properties":{"name":"First"}},
                {"geometry":{"coordinates":[12,91]},"properties":{"name":"Invalid north"}},
                {"geometry":{"coordinates":[12,-91]},"properties":{"name":"Invalid south"}},
                {"geometry":{"coordinates":[181,12]},"properties":{"name":"Invalid east"}},
                {"geometry":{"coordinates":[-181,12]},"properties":{"name":"Invalid west"}},
                {"geometry":{"coordinates":[12,"Infinity"]},"properties":{"name":"Infinite latitude"}},
                {"geometry":{"coordinates":["-Infinity",12]},"properties":{"name":"Infinite longitude"}},
                {"geometry":{"coordinates":[12,"NaN"]},"properties":{"name":"NaN"}},
                {"geometry":{"coordinates":[12]},"properties":{"name":"Missing latitude"}},
                {"geometry":{"coordinates":[12,0]},"properties":{"label":"Equator"}},
                {"geometry":{"coordinates":[0,12]},"properties":{"name":"Prime meridian"}},
                {"geometry":{"coordinates":[180,90]},"properties":{"name":"Upper bounds"}},
                {"geometry":{"coordinates":[-180,-90]},"properties":{"name":"Lower bounds"}},
                {"geometry":{"coordinates":[0,0]},"properties":{"name":"Origin"}}
            ]}
        """.trimIndent()

        assertEquals(expectedResults(), ForwardGeocoder.parsePhoton(response))
    }

    @Test
    fun nominatimSkipsInvalidCoordinateStringsAndKeepsValidResultsInOrder() {
        val response = """
            [
                {"lat":"47.3","lon":"8.5","display_name":"First"},
                {"lat":"91","lon":"12","display_name":"Invalid north"},
                {"lat":"-91","lon":"12","display_name":"Invalid south"},
                {"lat":"12","lon":"181","display_name":"Invalid east"},
                {"lat":"12","lon":"-181","display_name":"Invalid west"},
                {"lat":"Infinity","lon":"12","display_name":"Infinite latitude"},
                {"lat":"12","lon":"-Infinity","display_name":"Infinite longitude"},
                {"lat":"NaN","lon":"12","display_name":"NaN latitude"},
                {"lat":"12","lon":"NaN","display_name":"NaN longitude"},
                {"lon":"12","display_name":"Missing latitude"},
                {"lat":"12","lon":"not a number","display_name":"Malformed longitude"},
                {"lat":"0","lon":"12","display_name":"Equator"},
                {"lat":"12","lon":"0","display_name":"Prime meridian"},
                {"lat":"90","lon":"180","display_name":"Upper bounds"},
                {"lat":"-90","lon":"-180","display_name":"Lower bounds"},
                {"lat":"0","lon":"0","display_name":"Origin"}
            ]
        """.trimIndent()

        assertEquals(expectedResults(), ForwardGeocoder.parseNominatim(response))
    }

    private fun expectedResults() = listOf(
        SearchResult("First", 47.3, 8.5),
        SearchResult("Equator", 0.0, 12.0),
        SearchResult("Prime meridian", 12.0, 0.0),
        SearchResult("Upper bounds", 90.0, 180.0),
        SearchResult("Lower bounds", -90.0, -180.0),
        SearchResult("Origin", 0.0, 0.0)
    )
}
