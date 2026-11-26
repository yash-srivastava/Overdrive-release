package com.overdrive.app.navmap.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RouteMatchBoundsTest {
    private val guidance = NavGuidanceEngine()

    private fun check(points: List<GeoPoint>, query: GeoPoint, cutoff: Double) {
        val bounds = RouteMatchBounds(points)
        var i = 0
        while (i < points.size - 1) {
            val next = bounds.skipBlock(i, query.lat, query.lng, cutoff)
            if (next > i) {
                assertTrue(next <= points.size - 1)
                for (j in i until next) {
                    val a = points[j]; val b = points[j + 1]
                    val distance = guidance.pointToSegmentMeters(query.lat, query.lng, a.lat, a.lng, b.lat, b.lng)
                    assertTrue("skipped segment $j at $distance m, cutoff $cutoff", distance > cutoff)
                }
                i = next
            } else i++
        }
    }

    @Test fun boundariesTiesAndShortRoutes() {
        val points = List(98) { GeoPoint(0.0, it * 0.001) }
        for (n in listOf(0, 1, 2, 32, 33, 34, 65, 66, 98)) {
            val route = points.take(n)
            for (vertex in listOf(0, 31, 32, 33, 64, 65, 97)) {
                for (cutoff in listOf(0.0, 25.0, 50.0, 100.0)) {
                    check(route, GeoPoint(cutoff / 111_320.0, points[vertex].lng), cutoff)
                }
            }
        }
        val bounds = RouteMatchBounds(points)
        assertEquals(0, bounds.skipBlock(0, 0.0, 0.032, 0.0))
        assertEquals(32, bounds.skipBlock(0, 0.0, 0.09, 0.0))
        assertEquals(1, bounds.skipBlock(1, 0.0, 0.09, 0.0))
        assertEquals(97, bounds.skipBlock(96, 0.0, 0.0, 0.0))
    }

    @Test fun invalidInputsNeverSkipTheirBlock() {
        val points = List(100) { i ->
            when (i) {
                32 -> GeoPoint(Double.NaN, 0.0)
                80 -> GeoPoint(91.0, 0.0)
                else -> GeoPoint(0.0, i * 0.001)
            }
        }
        val bounds = RouteMatchBounds(points)
        for (i in listOf(0, 32, 64)) assertEquals(i, bounds.skipBlock(i, 45.0, 90.0, 0.0))
        assertEquals(0, bounds.skipBlock(0, Double.NaN, 0.0, 1.0))
        val valid = RouteMatchBounds(List(100) { GeoPoint(0.0, it * 0.001) })
        for (cutoff in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertEquals(0, valid.skipBlock(0, 45.0, 90.0, cutoff))
        }
        assertEquals(0, valid.skipBlock(0, 91.0, 0.0, 1.0))
    }

    @Test fun randomizedPolarAndAntimeridianBlocksAreConservative() {
        val random = Random(4518)
        for (base in listOf(-89.99, -70.0, 0.0, 45.0, 89.99)) {
            val points = List(197) { i ->
                GeoPoint((base + random.nextDouble(-0.005, 0.005)).coerceIn(-90.0, 90.0),
                    if (i < 98) 179.9 + random.nextDouble(0.0, 0.09) else -179.9 - random.nextDouble(0.0, 0.09))
            }
            repeat(100) {
                val p = points[random.nextInt(points.size)]
                val q = GeoPoint((p.lat + random.nextDouble(-0.005, 0.005)).coerceIn(-90.0, 90.0), p.lng)
                check(points, q, random.nextDouble(0.0, 500.0))
            }
        }
    }
}
