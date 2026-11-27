package com.overdrive.app.navmap.motion

import com.overdrive.app.navmap.nav.GeoPoint
import com.overdrive.app.navmap.nav.NavRoute
import com.overdrive.app.navmap.nav.RouteManeuver
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max
import kotlin.random.Random

class RouteTrackTest {
    private fun check(points: List<GeoPoint>, maneuvers: List<RouteManeuver>) {
        val track = RouteTrack.of(NavRoute(points, maneuvers, 0.0, 0.0))!!
        val keys = maneuvers.filter { it.type !in listOf(0, 1, 2, 3, 7, 8, 22) &&
            it.beginShapeIndex in points.indices }.map { track.sAtIndex(it.beginShapeIndex) }.sorted()
        var queryCount = 0
        fun compare(s: Double) {
            val next = keys.firstOrNull { it > s + 0.5 }?.minus(s) ?: Double.POSITIVE_INFINITY
            var last = Double.POSITIVE_INFINITY
            for (k in keys) if (k <= s + 0.5) last = s - k else break
            // Either public query can be called first, including after a jump.
            if (queryCount++ % 2 == 0) {
                assertEquals(next.toBits(), track.distanceToNextKey(s).toBits())
                assertEquals(max(0.0, last).toBits(), track.distanceSinceLastKey(s).toBits())
            } else {
                assertEquals(max(0.0, last).toBits(), track.distanceSinceLastKey(s).toBits())
                assertEquals(next.toBits(), track.distanceToNextKey(s).toBits())
            }
        }
        for (s in listOf(Double.NaN, Double.NEGATIVE_INFINITY, -1.0, -0.5, 0.0,
            track.length, track.length + 100, Double.POSITIVE_INFINITY)) compare(s)
        for (k in keys) {
            val boundary = k - 0.5
            for (s in listOf(Math.nextDown(boundary), boundary, Math.nextUp(boundary), k, k + 0.5)) compare(s)
        }
        val random = Random(1826)
        val length = if (track.length.isFinite()) track.length else 1000.0
        repeat(2000) { compare(random.nextDouble(-1.0, length + 1.0)) }
        for (i in 0..1000) compare(length * i / 1000)
        for (i in 1000 downTo 0) compare(length * i / 1000)
    }

    private fun maneuver(index: Int, type: Int = 10) =
        RouteManeuver("", type, index, 0.0, 0.0, 0.0, 0.0)

    @Test fun emptyAndRepeatedKeys() {
        val points = List(8) { GeoPoint(47.0, (it / 2) * 0.001) }
        check(points, emptyList())
        check(points, listOf(maneuver(0), maneuver(3), maneuver(2), maneuver(3),
            maneuver(7), maneuver(-1), maneuver(8), maneuver(1, 7)))
    }

    @Test fun longRoutesAndNonMonotonicQueries() {
        val points = List(4000) { GeoPoint(47.0, it * 0.0001) }
        check(points, points.indices.filter { it % 3 == 0 }.map { maneuver(it) }.reversed())
    }

    @Test fun nonFiniteDistancesRetainScanSemantics() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001),
            GeoPoint(Double.NaN, 0.0), GeoPoint(0.0, 0.002))
        check(points, points.indices.map { maneuver(it) })
    }
}
