package com.overdrive.app.navmap.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGuidanceRecoveryScanAndroidTest {
    private class CountingPoints(private val data: List<GeoPoint>) : AbstractList<GeoPoint>() {
        var reads = 0
        override val size: Int get() = data.size
        override fun get(index: Int): GeoPoint {
            reads++
            return data[index]
        }
    }

    private fun route(points: List<GeoPoint>) = NavRoute(points, emptyList(), 100_000.0, 3600.0)

    @Test fun earlyOffRouteFixDoesNotProjectUnreachableTail() {
        val points = CountingPoints(List(100_001) { GeoPoint(0.0, it * 0.0001) })
        val guidance = NavGuidanceEngine()
        guidance.start(route(points))
        guidance.update(0.0, 0.0, 90.0, 10.0, 1000)
        points.reads = 0

        val state = guidance.update(0.001, 0.00005, 90.0, 10.0, 2000)
        // ~111 m off route: this triggers global recovery, but only the first
        // ~275 m of the 1,100 km polyline can satisfy the reachability gate.
        assertTrue(guidance.offRouteM > 100.0)
        assertEquals(0.0, state.snappedLat, 1e-10)
        assertEquals(0.00005, state.snappedLng, 1e-10)
        assertTrue("point reads: ${points.reads}", points.reads < 200)
    }

    @Test fun firstFixSkipsDistantBlocksWithoutChangingEarliestMatch() {
        val points = CountingPoints(List(100_001) { GeoPoint(0.0, it * 0.0001) })
        val guidance = NavGuidanceEngine()
        guidance.start(route(points))
        points.reads = 0
        val state = guidance.update(0.0, 0.0, 90.0, 10.0, 1000)
        assertEquals(0.0, state.snappedLng, 1e-10)
        assertEquals(0.0, guidance.offRouteM, 1e-6)
        assertTrue("point reads: ${points.reads}", points.reads < 100)
    }

    @Test fun lateRecoverySkipsDistantButReachablePrefix() {
        val points = CountingPoints(List(100_001) { GeoPoint(0.0, it * 0.0001) })
        val guidance = NavGuidanceEngine()
        guidance.start(route(points))
        guidance.update(0.0, 8.0, 90.0, 10.0, 1000)
        points.reads = 0
        val state = guidance.update(0.001, 8.0, 90.0, 10.0, 2000)
        assertEquals(0.0, state.snappedLat, 1e-10)
        assertEquals(8.0, state.snappedLng, 1e-10)
        assertEquals(111.32, guidance.offRouteM, 1e-6)
        assertTrue("point reads: ${points.reads}", points.reads < 300)
    }

    @Test fun firstMatchSlackStillPrefersEarlierParallelLeg() {
        val early = List(65) { GeoPoint(0.0002, it * 0.001) }
        val late = List(65) { GeoPoint(0.0, (64 - it) * 0.001) }
        val guidance = NavGuidanceEngine()
        guidance.start(route(early + late))
        val state = guidance.update(0.0, 0.0155, 90.0, 10.0, 1000)
        assertEquals(0.0002, state.snappedLat, 1e-10)
        assertEquals(0.0155, state.snappedLng, 1e-10)
        assertEquals(22.264, guidance.offRouteM, 1e-6)
    }

    @Test fun recoveryStillFindsReachableSegmentBeyondLocalWindow() {
        // ~1.1 m per segment. A 90 m move lies beyond the 16-segment window.
        val points = List(2000) { GeoPoint(0.0, it * 0.00001) }
        val guidance = NavGuidanceEngine()
        guidance.start(route(points))
        guidance.update(0.0, 0.0, 90.0, 10.0, 1000)

        val state = guidance.update(0.0, 0.0008, 90.0, 10.0, 2000)
        assertEquals(0.0008, state.snappedLng, 1e-10)
        assertEquals(0.0, guidance.offRouteM, 1e-6)
        assertTrue(guidance.progressM > 80.0)
    }

    @Test fun unreachableReturnLegCannotWinAndRepeatedVerticesAreAllowed() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0)) +
            List(1000) { GeoPoint(0.0, (it + 1) * 0.0001) } +
            List(1000) { GeoPoint(0.001, (1000 - it) * 0.0001) }
        val guidance = NavGuidanceEngine()
        guidance.start(route(points))
        guidance.update(0.0, 0.0, 90.0, 10.0, 1000)
        val state = guidance.update(0.001, 0.0001, 90.0, 10.0, 2000)
        assertEquals(0.0, state.snappedLat, 1e-10)
        assertEquals(0.0001, state.snappedLng, 1e-10)
        assertTrue(guidance.offRouteM > 100.0)
    }

    @Test fun malformedArcStillScansPastInvalidVertex() {
        // NaN poisons the prefix table, but existing geometry matching can still
        // recover on later valid segments. Do not truncate that fallback scan.
        val points = List(40) { GeoPoint(0.0, it * 0.0001) } +
            listOf(GeoPoint(Double.NaN, 0.0), GeoPoint(0.001, 0.0), GeoPoint(0.001, 0.001))
        val guidance = NavGuidanceEngine()
        guidance.start(route(points))
        guidance.update(0.0, 0.0, 90.0, 10.0, 1000)
        val state = guidance.update(0.001, 0.0005, 90.0, 10.0, 2000)
        assertEquals(0.001, state.snappedLat, 1e-10)
        assertEquals(0.0005, state.snappedLng, 1e-10)
        assertEquals(0.0, guidance.offRouteM, 1e-6)
    }
}
