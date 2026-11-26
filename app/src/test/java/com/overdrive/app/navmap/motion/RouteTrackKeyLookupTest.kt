package com.overdrive.app.navmap.motion

import com.overdrive.app.navmap.nav.GeoPoint
import com.overdrive.app.navmap.nav.NavRoute
import com.overdrive.app.navmap.nav.RouteManeuver
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max
import kotlin.random.Random

class RouteTrackKeyLookupTest {
    private fun route(points: List<GeoPoint>, indices: List<Int>, type: Int = 10): NavRoute {
        val maneuvers = indices.map { RouteManeuver("", type, it, 0.0, 0.0, 0.0, 0.0) }
        return NavRoute(points, maneuvers, 0.0, 0.0)
    }

    private fun compare(track: RouteTrack, keys: List<Double>, positions: Iterable<Double>) {
        for (s in positions) {
            val next = keys.firstOrNull { it > s + 0.5 }?.minus(s) ?: Double.POSITIVE_INFINITY
            var previous = Double.POSITIVE_INFINITY
            for (key in keys) {
                if (key <= s + 0.5) previous = s - key else break
            }
            assertEquals(next.toBits(), track.distanceToNextKey(s).toBits())
            assertEquals(max(0.0, previous).toBits(), track.distanceSinceLastKey(s).toBits())
            // Reverse call order on a warm cache, too.
            assertEquals(max(0.0, previous).toBits(), track.distanceSinceLastKey(s).toBits())
            assertEquals(next.toBits(), track.distanceToNextKey(s).toBits())
        }
    }

    @Test fun duplicateKeysAndBoundaryTolerance() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001),
            GeoPoint(0.0, 0.001), GeoPoint(0.0, 0.002))
        val indices = listOf(4, 2, 0, 3, 1, 2, -1, 5)
        val track = RouteTrack.of(route(points, indices))!!
        val keys = indices.filter { it in points.indices }.map(track::sAtIndex).sorted()
        val positions = keys.flatMap { listOf(it - 0.500001, it - 0.5, it - 0.499999, it, it + 0.5) }
        compare(track, keys, positions + positions.reversed() + listOf(Double.NaN,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -10.0, track.length + 10.0))
    }

    @Test fun emptyAndMalformedKeys() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001), GeoPoint(Double.NaN, 0.0))
        val indices = listOf(0, 1, 2)
        val track = RouteTrack.of(route(points, indices))!!
        val keys = indices.map(track::sAtIndex).sorted()
        val positions = listOf(0.0, 1.0, 200.0, Double.NaN, Double.POSITIVE_INFINITY, -1.0)
        compare(track, keys, positions)
        compare(RouteTrack.of(route(points, indices, type = 1))!!, emptyList(), positions)
    }

    @Test fun randomSeeksAndSequentialFramesOnLongRoute() {
        val points = List(10_001) { GeoPoint(0.0, it * 0.00001) }
        val random = Random(920)
        val indices = List(10_000) { random.nextInt(points.size) }
        val track = RouteTrack.of(route(points, indices))!!
        val keys = indices.map(track::sAtIndex).sorted()
        val positions = List(2_000) { random.nextDouble(-1.0, track.length + 1.0) }
        compare(track, keys, positions)
        compare(track, keys, positions.sorted())
        compare(track, keys, positions.sortedDescending())
    }
}
