package com.overdrive.app.navmap.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the actual guidance state machine, including its Android logging calls. */
class NavGuidanceDistanceAndroidTest {
    private val engine = NavGuidanceEngine()

    private fun route(points: List<GeoPoint>, target: Int): NavRoute {
        val end = points[target.coerceIn(0, points.lastIndex)]
        val maneuver = RouteManeuver("Arrive", 4, target, 0.0, 0.0, end.lat, end.lng)
        return NavRoute(points, listOf(maneuver), sum(points, 0, points.lastIndex), 3600.0)
    }

    private fun sum(points: List<GeoPoint>, from: Int, to: Int): Double {
        var meters = 0.0
        for (i in from until to) {
            val a = points[i]
            val b = points[i + 1]
            meters += engine.haversineMeters(a.lat, a.lng, b.lat, b.lng)
        }
        return meters
    }

    @Test
    fun longRouteMatchesDirectSummation() {
        // Uneven segment lengths; each exceeds first-match slack, avoiding an ambiguous snap.
        val points = (0..5000).map { GeoPoint(45.0 + it * 0.0005, 8.0 + it * 0.0008) }
        val r = route(points, 4500)
        engine.start(r)
        val a = points[0]
        val b = points[1]
        val state = engine.update((a.lat + b.lat) / 2, (a.lng + b.lng) / 2, 45.0, 15.0, 1000L)
        val partial = engine.haversineMeters(state.snappedLat, state.snappedLng, b.lat, b.lng)
        assertEquals(sum(points, 0, 1) * 0.5 + sum(points, 1, points.lastIndex), state.remainingDistanceM, 1e-6)
        assertEquals(partial + sum(points, 1, 4500), state.distanceToManeuverM, 1e-6)
        assertEquals(3600 * state.remainingDistanceM / r.totalDistanceMeters, state.etaSeconds, 1e-8)
        assertFalse(state.offRoute)
        assertFalse(state.arrived)
    }

    @Test
    fun shortRoutesDuplicatesAndOutOfRangeManeuver() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(0.0, 0.001)
        val c = GeoPoint(0.0, 0.002)
        val points = listOf(a, b, b, c)
        // A malformed maneuver past the last vertex used to stop at the route end.
        engine.start(route(points, 50))
        val state = engine.update(0.0, 0.0005)
        val expected = sum(points, 0, 1) * 0.5 + sum(points, 1, points.lastIndex)
        assertEquals(expected, state.remainingDistanceM, 1e-8)
        assertEquals(expected, state.distanceToManeuverM, 1e-8)

        engine.start(route(listOf(a, b), 1))
        val short = engine.update(0.0, 0.0005)
        assertEquals(sum(listOf(a, b), 0, 1) * 0.5, short.remainingDistanceM, 1e-8)
        assertEquals(short.remainingDistanceM, short.distanceToManeuverM, 1e-8)
        engine.stop()
        assertEquals(0.0, engine.update(0.0, 0.0).remainingDistanceM, 0.0)
    }

    @Test
    fun nonfinitePrefixDoesNotPoisonLaterFiniteDistances() {
        val points = listOf(GeoPoint(Double.NaN, 0.0), GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001), GeoPoint(0.0, 0.002))
        engine.start(route(points, points.lastIndex))
        val state = engine.update(0.0, 0.0005)
        val expected = sum(points, 1, 2) * 0.5 + sum(points, 2, 3)
        assertEquals(expected, state.remainingDistanceM, 1e-8)
        assertEquals(expected, state.distanceToManeuverM, 1e-8)
    }

    @Test
    fun progressAndArrivalOnFinalSegment() {
        val points = (0..4).map { GeoPoint(0.0, it * 0.001) }
        engine.start(route(points, points.lastIndex))
        var state = engine.update(0.0, 0.0, 90.0, 10.0, 1000L)
        assertFalse(state.arrived)
        for (i in 1..38) {
            state = engine.update(0.0, i * 0.0001, 90.0, 10.0, 1000L + i * 1000L)
        }
        assertTrue(engine.hasSetOut)
        assertTrue(state.arrived)
        assertFalse(state.offRoute)
        assertEquals(engine.haversineMeters(0.0, 0.0038, 0.0, 0.004), state.remainingDistanceM, 1e-8)
    }
}
