package com.overdrive.app.navmap.google

import com.overdrive.app.navmap.nav.CongestionLevel
import com.overdrive.app.navmap.nav.CongestionSpan
import com.overdrive.app.navmap.nav.GeoPoint
import com.overdrive.app.navmap.nav.RouteProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleRoutesClientTest {

    @Test
    fun multiStopRequestPreservesWaypointOrderAndDisablesAlternatives() {
        val body = JSONObject(GoogleRoutesClient.buildBody(
            origin = GeoPoint(47.0, 8.0), destination = GeoPoint(47.0, 8.004),
            intermediates = listOf(GeoPoint(47.0, 8.001), GeoPoint(47.0, 8.003)),
            alternatives = true, headingDeg = -90.0, quality = GoogleRoutesClient.Quality.BEST,
            congestion = true, language = "de", region = "ch", miles = false,
        ))
        val origin = body.getJSONObject("origin").getJSONObject("location")
        assertEquals(270, origin.getInt("heading"))
        val stops = body.getJSONArray("intermediates")
        assertEquals(2, stops.length())
        for ((i, lng) in listOf(8.001, 8.003).withIndex()) {
            val location = stops.getJSONObject(i).getJSONObject("location")
            assertEquals(47.0, location.getJSONObject("latLng").getDouble("latitude"), 1e-9)
            assertEquals(lng, location.getJSONObject("latLng").getDouble("longitude"), 1e-9)
            assertFalse(location.has("heading"))
        }
        val destination = body.getJSONObject("destination").getJSONObject("location")
        assertEquals(8.004, destination.getJSONObject("latLng").getDouble("longitude"), 1e-9)
        assertFalse(destination.has("heading"))
        assertFalse(body.getBoolean("computeAlternativeRoutes"))
        assertEquals("TRAFFIC_AWARE_OPTIMAL", body.getString("routingPreference"))
        assertEquals("TRAFFIC_ON_POLYLINE", body.getJSONArray("extraComputations").getString(0))
        assertEquals("de", body.getString("languageCode"))
        assertEquals("ch", body.getString("regionCode"))
        assertEquals("METRIC", body.getString("units"))
    }

    @Test
    fun directRequestAllowsAlternativesAndOmitsUnavailableOptions() {
        val body = JSONObject(GoogleRoutesClient.buildBody(
            origin = GeoPoint(47.0, 8.0), destination = GeoPoint(47.0, 8.004),
            intermediates = emptyList(), alternatives = true, headingDeg = Double.NaN,
            quality = GoogleRoutesClient.Quality.FAST, congestion = false,
            language = null, region = "", miles = true,
        ))
        assertTrue(body.getBoolean("computeAlternativeRoutes"))
        assertFalse(body.has("intermediates"))
        assertFalse(body.has("extraComputations"))
        assertFalse(body.has("languageCode"))
        assertFalse(body.has("regionCode"))
        assertFalse(body.getJSONObject("origin").getJSONObject("location").has("heading"))
        assertEquals("TRAFFIC_AWARE", body.getString("routingPreference"))
        assertEquals("IMPERIAL", body.getString("units"))
        assertEquals("DRIVE", body.getString("travelMode"))
        assertEquals("ENCODED_POLYLINE", body.getString("polylineEncoding"))
    }

    @Test
    fun parserScalesStepTimesFoldsSilentStepsAndAppendsArrival() {
        val route = routeJson().put("legs", JSONArray().put(JSONObject()
            .put("duration", "90s").put("staticDuration", "60s")
            .put("steps", JSONArray()
                .put(step(8.0, 100, "10s", "DEPART", "Head east\nToll road"))
                .put(step(8.001, 100, "20s"))
                .put(step(8.002, 200, "30s", "TURN_RIGHT", "Turn right")))))
        val parsed = parseRoutes(route).single()
        assertEquals(RouteProvider.GOOGLE, parsed.provider)
        assertEquals((0..4).map { GeoPoint(47.0, (800000 + 100 * it) / 1e5) }, parsed.points)
        assertEquals(400.0, parsed.totalDistanceMeters, 1e-9)
        assertEquals(90.0, parsed.totalDurationSeconds, 1e-9)
        assertEquals(30.0, parsed.trafficDelaySeconds, 1e-9)
        assertEquals(listOf("Head east", "Turn right", "Arrived"), parsed.maneuvers.map { it.instruction })
        assertEquals(listOf(1, 10, 4), parsed.maneuvers.map { it.type })
        assertEquals(listOf(0, 2, 4), parsed.maneuvers.map { it.beginShapeIndex })
        assertEquals(listOf(200.0, 200.0, 0.0), parsed.maneuvers.map { it.lengthMeters })
        assertEquals(listOf(45.0, 45.0, 0.0), parsed.maneuvers.map { it.timeSeconds })
    }

    @Test
    fun parserMergesSameLevelTrafficAndClampsToRouteGeometry() {
        val route = routeJson().put("travelAdvisory", JSONObject().put("speedReadingIntervals", JSONArray()
            .put(interval(2, 99, "TRAFFIC_JAM"))
            .put(interval(1, 2, "SLOW"))
            .put(interval(null, 1, "SLOW"))
            .put(interval(0, 4, "NORMAL"))
            .put(interval(4, 4, "SLOW"))))
        assertEquals(listOf(
            CongestionSpan(0, 2, CongestionLevel.SLOW),
            CongestionSpan(2, 4, CongestionLevel.JAM),
        ), parseRoutes(route).single().congestion)
    }

    @Test
    fun unusableRoutesAreSkippedWithoutLosingValidAlternatives() {
        assertTrue(GoogleRoutesClient.parse("not json", "Arrived").isEmpty())
        assertTrue(GoogleRoutesClient.parse("{}", "Arrived").isEmpty())
        assertTrue(parseRoutes(JSONObject()).isEmpty())
        val routes = parseRoutes(routeJson(), JSONObject(), routeJson().put("distanceMeters", 450))
        assertEquals(listOf(400.0, 450.0), routes.map { it.totalDistanceMeters })
        assertTrue(routes.all { it.maneuvers.single().beginShapeIndex == 4 })
    }

    private fun parseRoutes(vararg routes: JSONObject) = GoogleRoutesClient.parse(
        JSONObject().put("routes", JSONArray(routes.toList())).toString(), "Arrived",
    )

    // Precision-5 encoding of five eastbound points from (47, 8) to (47, 8.004).
    private fun routeJson() = JSONObject()
        .put("polyline", JSONObject().put("encodedPolyline", "_uz}G_oyo@?gE?gE?gE?gE"))
        .put("distanceMeters", 400).put("duration", "90s").put("staticDuration", "60s")

    private fun step(lng: Double, distance: Int, duration: String, type: String? = null, text: String? = null): JSONObject {
        val step = JSONObject().put("distanceMeters", distance).put("staticDuration", duration)
            .put("startLocation", JSONObject().put("latLng", JSONObject().put("latitude", 47.0).put("longitude", lng)))
        if (type != null && text != null) {
            step.put("navigationInstruction", JSONObject().put("maneuver", type).put("instructions", text))
        }
        return step
    }

    private fun interval(start: Int?, end: Int, speed: String): JSONObject {
        val interval = JSONObject().put("endPolylinePointIndex", end).put("speed", speed)
        if (start != null) interval.put("startPolylinePointIndex", start)
        return interval
    }
}
