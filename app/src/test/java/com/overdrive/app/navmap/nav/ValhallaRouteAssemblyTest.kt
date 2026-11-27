package com.overdrive.app.navmap.nav

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValhallaRouteAssemblyTest {

    // Fixed precision-6 shapes: A -> B, B -> C -> D, and E -> F.
    private val a = GeoPoint(47.000, 8.000)
    private val b = GeoPoint(47.001, 8.000)
    private val c = GeoPoint(47.002, 8.001)
    private val d = GeoPoint(47.003, 8.001)
    private val e = GeoPoint(47.005, 8.003)
    private val f = GeoPoint(47.006, 8.003)

    private fun firstLeg() = leg("_{ssxA__hgNo}@?", 0.12, 15,
        maneuver(1, 0, "Start"), maneuver(4, 1, "Via stop"))

    private fun secondLeg() = leg("oyusxA__hgNo}@o}@o}@?", 0.28, 35,
        maneuver(1, 0, "Continue"), maneuver(10, 1, "Turn"), maneuver(5, 2, "Arrive"))

    private fun separateLeg() = leg("os}sxAozmgNo}@?", 0.14, 20,
        maneuver(1, 0, "Resume"), maneuver(6, 1, "Final arrival"))

    @Test
    fun joinsSharedVerticesAndKeepsOnlyFinalArrival() {
        val trip = trip(firstLeg(), secondLeg())
            .put("summary", summary(0.45, 55))
        val route = parse(trip)

        assertEquals(listOf(a, b, c, d), route.points)
        assertEquals(listOf(1, 1, 10, 5), route.maneuvers.map { it.type })
        assertEquals(listOf(0, 1, 2, 3), route.maneuvers.map { it.beginShapeIndex })
        assertEquals(listOf(a, b, c, d), route.maneuvers.map { GeoPoint(it.lat, it.lng) })
        assertEquals("Arrive", route.maneuvers.last().instruction)
        assertEquals(450.0, route.totalDistanceMeters, 1e-8)
        assertEquals(55.0, route.totalDurationSeconds, 1e-8)
    }

    @Test
    fun skipsEmptyTrailingLegWithoutLosingArrivalAndFallsBackToLegTotals() {
        val empty = leg("", 10.0, 900, maneuver(6, 0, "Not a real arrival"))
        val route = parse(trip(firstLeg(), secondLeg(), empty))

        assertEquals(listOf(a, b, c, d), route.points)
        assertEquals(listOf(5), route.maneuvers.filter { it.type in 4..6 }.map { it.type })
        assertEquals(3, route.maneuvers.last().beginShapeIndex)
        assertEquals("Arrive", route.maneuvers.last().instruction)
        assertEquals(400.0, route.totalDistanceMeters, 1e-8)
        assertEquals(50.0, route.totalDurationSeconds, 1e-8)
    }

    @Test
    fun doesNotDropFirstVertexOfNonCoincidentLegAfterSkippedLeg() {
        val route = parse(trip(firstLeg(), JSONObject(), separateLeg()))

        assertEquals(listOf(a, b, e, f), route.points)
        assertEquals(listOf(0, 2, 3), route.maneuvers.map { it.beginShapeIndex })
        assertEquals(listOf(a, e, f), route.maneuvers.map { GeoPoint(it.lat, it.lng) })
        assertEquals(listOf(1, 1, 6), route.maneuvers.map { it.type })
        assertEquals(260.0, route.totalDistanceMeters, 1e-8)
        assertEquals(35.0, route.totalDurationSeconds, 1e-8)
    }

    @Test
    fun keepsPrimaryBeforeUsableAlternatesAndSkipsEmptyTrips() {
        val response = JSONObject().put("trip", trip(firstLeg()))
            .put("alternates", JSONArray()
                .put(JSONObject().put("trip", trip()))
                .put(JSONObject().put("trip", trip(secondLeg())))
                .put(JSONObject())
                .put(JSONObject().put("trip", trip(separateLeg()))))
            .toString()
        val routes = ValhallaRouteClient.parseRoutes(response)

        assertEquals(listOf(listOf(a, b), listOf(b, c, d), listOf(e, f)), routes.map { it.points })
        assertEquals(routes.first(), ValhallaRouteClient.parseRoute(response))
        assertTrue(ValhallaRouteClient.parseRoutes(JSONObject().toString()).isEmpty())
        assertNull(ValhallaRouteClient.parseRoute(JSONObject().put("trip", trip()).toString()))
    }

    private fun parse(trip: JSONObject): NavRoute =
        requireNotNull(ValhallaRouteClient.parseRoute(JSONObject().put("trip", trip).toString()))

    private fun trip(vararg legs: JSONObject) = JSONObject().put("legs", JSONArray(legs.toList()))

    private fun leg(shape: String, km: Double, seconds: Int, vararg maneuvers: JSONObject) =
        JSONObject().put("shape", shape)
            .put("summary", summary(km, seconds))
            .put("maneuvers", JSONArray(maneuvers.toList()))

    private fun summary(km: Double, seconds: Int) =
        JSONObject().put("length", km).put("time", seconds)

    private fun maneuver(type: Int, index: Int, instruction: String) =
        JSONObject().put("type", type).put("begin_shape_index", index).put("instruction", instruction)
}
