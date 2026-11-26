package com.overdrive.app.navmap.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Compare the cached route scan with the original uncached local-meter projection. */
class NavGuidanceProjectionAndroidTest {
    private val engine = NavGuidanceEngine()

    private data class Projection(val lat: Double, val lng: Double, val t: Double, val distance: Double)

    private fun reference(p: GeoPoint, a: GeoPoint, b: GeoPoint): Projection {
        val scale = 111_320.0 * cos(Math.toRadians(a.lat))
        val dx = (b.lng - a.lng) * scale - 0.0
        val dy = (b.lat - a.lat) * 111_320.0 - 0.0
        val px = (p.lng - a.lng) * scale
        val py = (p.lat - a.lat) * 111_320.0
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= 1e-9) 0.0 else
            (((px - 0.0) * dx + (py - 0.0) * dy) / lenSq).coerceIn(0.0, 1.0)
        val x = 0.0 + t * dx
        val y = 0.0 + t * dy
        return Projection(a.lat + y / 111_320.0,
            if (abs(scale) < 1e-9) a.lng else a.lng + x / scale,
            t, sqrt((px - x) * (px - x) + (py - y) * (py - y)))
    }

    private fun start(points: List<GeoPoint>) {
        engine.start(NavRoute(points, emptyList(), 100_000.0, 3600.0))
    }

    private fun assertSnap(points: List<GeoPoint>, p: GeoPoint, candidates: IntRange) {
        val best = candidates.map { reference(p, points[it], points[it + 1]) }
            .filter { it.distance < Double.MAX_VALUE }.minByOrNull { it.distance }!!
        val actual = engine.snapToRoute(p.lat, p.lng)!!
        assertEquals(best.lat, actual.lat, 0.0)
        assertEquals(best.lng, actual.lng, 0.0)
        assertEquals(best.distance, actual.offsetM, 0.0)
    }

    @Test
    fun globalProjectionMatchesReferenceAcrossLatitudesAndReroutes() {
        for (baseLat in listOf(-75.0, 0.0, 47.0, 89.999)) {
            // Same-sized but different geometry on every start; include a duplicate vertex.
            val points = (0..40).map {
                val i = if (it == 11) 10 else it
                GeoPoint(baseLat - i * 0.0001, 8.0 + i * 0.0002)
            }
            start(points)
            for (i in -2..42) {
                val p = GeoPoint(baseLat - i * 0.0001 + 0.00003, 8.0 + i * 0.0002 - 0.00006)
                assertSnap(points, p, 0 until points.lastIndex)
            }
        }
        engine.stop()
        assertNull(engine.snapToRoute(0.0, 0.0))
        start(listOf(GeoPoint(0.0, 0.0)))
        assertNull(engine.snapToRoute(0.0, 0.0))
    }

    @Test
    fun renderWindowDoesNotJumpToLaterLeg() {
        val points = (0..40).map { GeoPoint(0.0, it * 0.001) }
        start(points)
        val first = engine.update(0.0, 0.0005)
        assertEquals(0.0005, first.snappedLng, 1e-12)
        // The first matched segment is zero; render scans only 0..16, even
        // when a point is much closer to a segment outside that window.
        assertSnap(points, GeoPoint(0.0002, 0.0305), 0..16)
        assertSnap(points, GeoPoint(-0.0002, 0.0025), 0..16)
    }

    @Test
    fun approachKeepsEarliestPassAndAlongRouteFloor() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(0.0, 0.001)
        val points = listOf(a, b, a, b)
        start(points)
        val length = engine.haversineMeters(a.lat, a.lng, b.lat, b.lng)
        val first = engine.approachAfter(0.0, 0.0005, 0.0)
        assertNotNull(first)
        assertEquals(length * 0.5, first!!.alongM, 1e-8)
        val later = engine.approachAfter(0.0, 0.0005, length * 2)
        assertNotNull(later)
        assertEquals(length * 2.5, later!!.alongM, 1e-8)
        assertEquals(first.lat, later.lat, 0.0)
        assertEquals(first.lng, later.lng, 0.0)
        assertNull(engine.approachAfter(0.0, 0.0005, length * 4))
    }

    @Test
    fun nonfiniteAndDegenerateSegmentsPreserveProjection() {
        val a = GeoPoint(0.0, 0.0)
        val points = listOf(GeoPoint(Double.NaN, 0.0), a, a, GeoPoint(0.0, 0.001))
        start(points)
        assertSnap(points, GeoPoint(0.0, 0.0005), 0 until points.lastIndex)
        assertSnap(points, a, 0 until points.lastIndex)
        start(listOf(GeoPoint(90.0, 0.0), GeoPoint(90.0, 1.0)))
        assertSnap(listOf(GeoPoint(90.0, 0.0), GeoPoint(90.0, 1.0)),
            GeoPoint(89.9999, 0.4), 0..0)
    }
}
