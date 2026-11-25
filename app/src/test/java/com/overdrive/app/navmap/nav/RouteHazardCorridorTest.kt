package com.overdrive.app.navmap.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.random.Random

class RouteHazardCorridorTest {
    private val guidance = NavGuidanceEngine()
    private val radius = 40.0

    private fun brute(points: List<GeoPoint>, p: GeoPoint): Boolean {
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (guidance.pointToSegmentMeters(p.lat, p.lng, a.lat, a.lng, b.lat, b.lng) <= radius) return true
        }
        return false
    }

    private fun check(points: List<GeoPoint>, queries: List<GeoPoint>) {
        val corridor = RouteHazardCorridor(points, radius)
        for (p in queries) assertEquals(brute(points, p), corridor.contains(p.lat, p.lng, guidance))
    }

    @Test fun shortRoutesAndBlockEndpoints() {
        val points = List(100) { GeoPoint(0.0, it * 0.001) }
        val delta = radius / 111_320.0
        for (n in listOf(0, 1, 2, 32, 33, 34, 64, 65, 66, 100)) {
            val route = points.take(n)
            val queries = listOf(0, 1, 31, 32, 33, 63, 64, 65, 99).flatMap { i ->
                listOf(GeoPoint(0.0, i * 0.001), GeoPoint(delta - 1e-10, i * 0.001),
                    GeoPoint(delta + 1e-10, i * 0.001), GeoPoint(-delta, i * 0.001))
            }
            check(route, queries)
        }
        val c = RouteHazardCorridor(points, radius)
        assertTrue(c.contains(delta - 1e-10, 0.032, guidance))
        assertFalse(c.contains(delta + 1e-10, 0.032, guidance))
    }

    @Test fun longitudePaddingAtHighLatitudes() {
        for (lat in listOf(-89.99, -80.0, -45.0, 0.0, 45.0, 80.0, 89.9)) {
            val points = List(97) { GeoPoint(lat + it * 0.00001, 10.0) }
            val p = points[64]
            val pad = radius / (111_320.0 * cos(Math.toRadians(p.lat)))
            val queries = listOf(0.0, 0.999, 1.0, 1.001, 2.0).flatMap { f ->
                listOf(GeoPoint(p.lat, p.lng + f * pad), GeoPoint(p.lat, p.lng - f * pad))
            }
            check(points, queries)
        }
    }

    @Test fun repeatedVerticesMalformedAndAntimeridianCoordinates() {
        val points = List(130) { i ->
            when (i) {
                35 -> GeoPoint(Double.NaN, 0.0)
                67 -> GeoPoint(91.0, 0.0)
                95 -> GeoPoint(0.0, Double.POSITIVE_INFINITY)
                else -> GeoPoint((i / 3) * 0.001, if (i < 64) 179.999 else -179.999)
            }
        }
        check(points, points + listOf(GeoPoint(0.0, 180.0), GeoPoint(0.0, -180.0),
            GeoPoint(0.020, 0.0), GeoPoint(91.0, 0.0), GeoPoint(Double.NaN, 0.0)))
        val polar = List(67) { GeoPoint(90.0, -170.0 + it * 5.0) }
        check(polar, listOf(GeoPoint(90.0, 179.0), GeoPoint(89.9999, -180.0), GeoPoint(89.0, 0.0)))
    }

    @Test fun randomizedCorridorsMatchOriginalScan() {
        val random = Random(78124)
        repeat(120) { trial ->
            val startLat = listOf(-89.0, -70.0, -20.0, 0.0, 35.0, 70.0, 89.0)[trial % 7]
            var lat = startLat
            var lng = -30.0
            val points = List(2 + random.nextInt(300)) {
                lat += (random.nextDouble() - 0.5) * 0.001
                lng += (random.nextDouble() - 0.5) * 0.01
                GeoPoint(lat, lng)
            }
            val queries = List(100) {
                val p = points[random.nextInt(points.size)]
                GeoPoint(p.lat + (random.nextDouble() - 0.5) * 0.004,
                    p.lng + (random.nextDouble() - 0.5) * 0.04)
            }
            check(points, queries)
        }
    }
}
