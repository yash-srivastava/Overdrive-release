package com.overdrive.app.briefing.engine

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceClustererTest {
    private val eps = 1e-9

    @Test
    fun emptyAndSingletonInputs() {
        val empty = PlaceClusterer.cluster(doubleArrayOf(), doubleArrayOf(), 100.0)
        assertEquals(0, empty.size)
        assertArrayEquals(intArrayOf(), empty.assign)
        assertArrayEquals(intArrayOf(), empty.visits)
        assertArrayEquals(doubleArrayOf(), empty.lng, eps)

        val single = PlaceClusterer.cluster(doubleArrayOf(45.0), doubleArrayOf(10.0), 100.0)
        assertEquals(1, single.size)
        assertArrayEquals(intArrayOf(0), single.assign)
        assertArrayEquals(intArrayOf(1), single.visits)
        assertEquals(45.0, single.lat[0], eps)
        assertEquals(10.0, single.lng[0], eps)
    }

    @Test
    fun densestLeaderWinsAndTiesUseInputOrder() {
        // Adjacent points are about 89 m apart; two steps exceed the radius.
        // The middle points each have three neighbours including themselves.
        // Index 1 wins the tie and claims 0, 1, 2; index 3 stays separate.
        val clusters = PlaceClusterer.cluster(
            DoubleArray(4), doubleArrayOf(10.0, 10.0008, 10.0016, 10.0024), 100.0,
        )
        assertEquals(2, clusters.size)
        assertArrayEquals(intArrayOf(0, 0, 0, 1), clusters.assign)
        assertArrayEquals(intArrayOf(3, 1), clusters.visits)
        assertArrayEquals(doubleArrayOf(0.0, 0.0), clusters.lat, eps)
        assertArrayEquals(doubleArrayOf(10.0008, 10.0024), clusters.lng, eps)
    }

    @Test
    fun antimeridianNeighboursHaveCentroidNearDateLine() {
        val clusters = PlaceClusterer.cluster(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(179.9998, -179.9998), 100.0,
        )
        assertEquals(1, clusters.size)
        assertArrayEquals(intArrayOf(0, 0), clusters.assign)
        assertArrayEquals(intArrayOf(2), clusters.visits)
        assertEquals(0.0, clusters.lat[0], eps)
        // Either representation of the date line is geographically equivalent.
        assertEquals(180.0, abs(clusters.lng[0]), eps)
    }

    @Test
    fun exactRadiusIsExcludedButSlightlyLargerRadiusIncludesBothPoints() {
        val lat = doubleArrayOf(45.0, 45.001)
        val lng = doubleArrayOf(10.0, 10.0)
        val distance = haversineM(lat[0], lng[0], lat[1], lng[1])
        val boundary = PlaceClusterer.cluster(lat, lng, distance)
        assertArrayEquals(intArrayOf(0, 1), boundary.assign)
        assertArrayEquals(intArrayOf(1, 1), boundary.visits)

        val inside = PlaceClusterer.cluster(lat, lng, distance + 0.01)
        assertArrayEquals(intArrayOf(0, 0), inside.assign)
        assertArrayEquals(intArrayOf(2), inside.visits)
        assertEquals(45.0005, inside.lat[0], eps)
    }

    @Test
    fun interleavedRepeatedVisitsAcrossTreeLeavesKeepAssignmentsAndCentroids() {
        // More than one tree leaf, with unequal visit counts and repeated coordinates.
        val groups = IntArray(24) { i ->
            when {
                i < 18 -> i % 3
                i < 22 -> 0
                else -> 1
            }
        }
        val lat = DoubleArray(groups.size) { 45.0 + groups[it] }
        val lng = DoubleArray(groups.size) { 10.0 + groups[it] }
        val clusters = PlaceClusterer.cluster(lat, lng, 100.0)

        assertEquals(3, clusters.size)
        assertArrayEquals(groups, clusters.assign)
        assertArrayEquals(intArrayOf(10, 8, 6), clusters.visits)
        assertArrayEquals(doubleArrayOf(45.0, 46.0, 47.0), clusters.lat, eps)
        assertArrayEquals(doubleArrayOf(10.0, 11.0, 12.0), clusters.lng, eps)
    }
}
