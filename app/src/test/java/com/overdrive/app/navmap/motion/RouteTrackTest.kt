package com.overdrive.app.navmap.motion

import com.overdrive.app.navmap.nav.GeoPoint
import com.overdrive.app.navmap.nav.NavRoute
import com.overdrive.app.navmap.nav.RouteManeuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteTrackTest {
    private val a = GeoPoint(0.0, 0.0)
    private val b = GeoPoint(0.0, 0.001)
    private val c = GeoPoint(0.0, 0.002)
    // One thousandth of a degree along the equator, using a 6371 km earth radius.
    private val legM = 111.1949266
    private val toleranceM = 0.001

    private fun route(points: List<GeoPoint>, maneuvers: List<RouteManeuver> = emptyList()) =
        NavRoute(points, maneuvers, 0.0, 0.0)

    private fun maneuver(type: Int, index: Int) =
        RouteManeuver("", type, index, 0.0, 0.0, 0.0, 0.0)

    @Test fun routesNeedAtLeastTwoPoints() {
        assertNull(RouteTrack.of(null))
        assertNull(RouteTrack.of(route(emptyList())))
        assertNull(RouteTrack.of(route(listOf(a))))
    }

    @Test fun interpolatesAndClampsAcrossRepeatedVertices() {
        val track = RouteTrack.of(route(listOf(a, b, b, c)))!!
        assertEquals(2 * legM, track.length, toleranceM)
        assertEquals(legM, track.sAtIndex(1), toleranceM)
        assertEquals(track.sAtIndex(1), track.sAtIndex(2), 0.0)
        assertEquals(0.0, track.sAtIndex(-1), 0.0)
        assertEquals(track.length, track.sAtIndex(99), 0.0)

        val out = DoubleArray(2)
        // Query in both directions to exercise the cached segment lookup.
        for ((distance, expectedLng) in listOf(
            1.5 * legM to 0.0015,
            0.5 * legM to 0.0005,
            track.sAtIndex(1) to 0.001,
            -10.0 to 0.0,
            track.length + 10.0 to 0.002
        )) {
            track.pointAt(distance, out)
            assertEquals(0.0, out[0], 1e-9)
            assertEquals(expectedLng, out[1], 1e-9)
        }
    }

    @Test fun projectsToEitherSideOfAnEastboundRoute() {
        val track = RouteTrack.of(route(listOf(a, b)))!!
        val out = DoubleArray(2)
        track.project(-0.0001, 0.0005, legM / 2, legM, out)
        assertEquals(legM / 2, out[0], toleranceM)
        assertEquals(legM / 10, out[1], toleranceM)
        track.project(0.0001, 0.0005, legM / 2, legM, out)
        assertEquals(legM / 2, out[0], toleranceM)
        assertEquals(-legM / 10, out[1], toleranceM)
    }

    @Test fun projectionHintDistinguishesOverlappingOutboundAndReturnLegs() {
        val track = RouteTrack.of(route(listOf(a, b, a)))!!
        val out = DoubleArray(2)
        track.project(0.0001, 0.0005, legM / 2, 10.0, out)
        assertEquals(legM / 2, out[0], toleranceM)
        assertEquals(-legM / 10, out[1], toleranceM)
        track.project(0.0001, 0.0005, 1.5 * legM, 10.0, out)
        assertEquals(1.5 * legM, out[0], toleranceM)
        assertEquals(legM / 10, out[1], toleranceM)
    }

    @Test fun maneuverDistancesIgnoreUnframedAndOutOfBoundsEntries() {
        val track = RouteTrack.of(route(listOf(a, b, c), listOf(
            maneuver(1, 0), // start
            maneuver(7, 0), // continue
            maneuver(10, 1), // turn
            maneuver(4, 2), // arrival
            maneuver(10, 99)
        )))!!
        val turnS = track.sAtIndex(1)
        assertEquals(Double.POSITIVE_INFINITY, track.distanceSinceLastKey(0.0), 0.0)
        assertEquals(turnS, track.distanceToNextKey(0.0), toleranceM)
        assertEquals(0.0, track.distanceSinceLastKey(turnS), 0.0)
        assertEquals(track.length - turnS, track.distanceToNextKey(turnS), toleranceM)
        assertEquals(10.0, track.distanceSinceLastKey(turnS + 10.0), toleranceM)
        assertEquals(Double.POSITIVE_INFINITY, track.distanceToNextKey(track.length), 0.0)
    }

    @Test fun chordBearingFollowsDirectionOfTravel() {
        val east = RouteTrack.of(route(listOf(a, b)))!!
        val west = RouteTrack.of(route(listOf(b, a)))!!
        assertEquals(90.0, east.chordBearing(legM / 2, 10.0, 10.0), 1e-6)
        assertEquals(270.0, west.chordBearing(legM / 2, 10.0, 10.0), 1e-6)
    }
}
