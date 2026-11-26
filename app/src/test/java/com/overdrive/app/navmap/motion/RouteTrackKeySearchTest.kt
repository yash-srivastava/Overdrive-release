package com.overdrive.app.navmap.motion

import com.overdrive.app.navmap.nav.GeoPoint
import com.overdrive.app.navmap.nav.NavRoute
import com.overdrive.app.navmap.nav.RouteManeuver
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max
import kotlin.random.Random

class RouteTrackKeySearchTest {
    private fun maneuver(index: Int, type: Int = 9) =
        RouteManeuver("", type, index, 0.0, 0.0, 0.0, 0.0)

    private fun check(route: NavRoute, queries: List<Double>) {
        val track = RouteTrack.of(route)!!
        val keys = route.maneuvers.filter {
            it.type !in listOf(0, 1, 2, 3, 7, 8, 22) && it.beginShapeIndex in route.points.indices
        }.map { track.sAtIndex(it.beginShapeIndex) }.sorted()
        for (s in queries) {
            // Original linear reference, including the strict next/inclusive previous split.
            var next = Double.POSITIVE_INFINITY
            for (k in keys) if (k > s + 0.5) { next = k - s; break }
            var prev = Double.POSITIVE_INFINITY
            for (k in keys) if (k <= s + 0.5) prev = s - k else break
            assertEquals(next.toBits(), track.distanceToNextKey(s).toBits())
            assertEquals(max(0.0, prev).toBits(), track.distanceSinceLastKey(s).toBits())
            // Exercise both call orders and repeated reads of the same bracket.
            assertEquals(max(0.0, prev).toBits(), track.distanceSinceLastKey(s).toBits())
            assertEquals(next.toBits(), track.distanceToNextKey(s).toBits())
        }
    }

    @Test fun exactThresholdsDuplicatesAndExcludedManeuvers() {
        val points = List(12) { GeoPoint(0.0, (it / 2) * 0.001) }
        val maneuvers = listOf(maneuver(11), maneuver(2), maneuver(3), maneuver(0),
            maneuver(8, 7), maneuver(4, 22), maneuver(-1), maneuver(12), maneuver(2))
        val route = NavRoute(points, maneuvers, 0.0, 0.0)
        val track = RouteTrack.of(route)!!
        val queries = points.indices.flatMap { i ->
            val boundary = track.sAtIndex(i) - 0.5
            listOf(boundary - 1e-8, boundary, boundary + 1e-8, track.sAtIndex(i))
        }
        check(route, queries + queries.reversed() + listOf(-100.0, 10000.0, Double.NaN,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY))
        check(route.copy(maneuvers = emptyList()), queries)
        check(route.copy(maneuvers = listOf(maneuver(3, 1))), queries)
    }

    @Test fun randomizedForwardReverseAndJumpQueries() {
        val random = Random(47)
        repeat(200) {
            val points = List(random.nextInt(2, 300)) { GeoPoint(0.0, (it / 2) * 0.0001) }
            val maneuvers = List(random.nextInt(0, 500)) {
                maneuver(random.nextInt(-1, points.size + 1), random.nextInt(0, 30))
            }
            val route = NavRoute(points, maneuvers, 0.0, 0.0)
            val length = RouteTrack.of(route)!!.length
            val queries = List(100) { random.nextDouble(-20.0, length + 20.0) }
            check(route, queries + queries.sorted() + queries.sortedDescending())
        }
    }

    @Test fun nonFiniteGeometryKeepsLinearComparisonSemantics() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001),
            GeoPoint(Double.NaN, 0.002), GeoPoint(0.0, 0.003))
        val route = NavRoute(points, points.indices.map { maneuver(it) }, 0.0, 0.0)
        check(route, listOf(0.0, 110.0, 120.0, Double.POSITIVE_INFINITY,
            Double.NaN, -0.5, Double.NEGATIVE_INFINITY, 0.0))
    }
}
