package com.overdrive.app.ui.timeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StripClusterFastPathTest {
    @Test fun singletonReuseMatchesGreedyClusteringAcrossZoomChanges() {
        val random = Random(1401)
        repeat(200) {
            val n = random.nextInt(0, 200)
            var time = 1_800_000_000_000L
            val times = LongArray(n) { time += random.nextLong(0, 10_000); time }
            val spacing = StripPhysics.minClusterSpacing(times, n)
            val starts = IntArray(n)
            val counts = IntArray(n)
            val centers = DoubleArray(n)
            val original = StripPhysics.cluster(times, n, 0.0, starts, counts, centers)
            assertEquals(n, original)
            val gaps = doubleArrayOf(0.0, spacing, 0.1, 1_000.0, 10_000.0, 100_000.0)
            for (gap in gaps) {
                val nextStarts = IntArray(n)
                val nextCounts = IntArray(n)
                val nextCenters = DoubleArray(n)
                val next = StripPhysics.cluster(times, n, gap, nextStarts, nextCounts, nextCenters)
                val reuse = StripPhysics.canReuseSingletonClusters(false, original, n, gap, spacing)
                assertEquals(next == n, reuse)
                if (reuse) {
                    assertArrayEquals(starts, nextStarts)
                    assertArrayEquals(counts, nextCounts)
                    assertArrayEquals(centers, nextCenters, 0.0)
                }
            }
        }
    }

    @Test fun thresholdUsesTheSameDoubleCoordinatesAndStrictComparison() {
        val times = longArrayOf(Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val spacing = StripPhysics.minClusterSpacing(times, 2)
        assertEquals(0.0, spacing, 0.0)
        assertTrue(StripPhysics.canReuseSingletonClusters(false, 2, 2, 0.0, spacing))
        assertFalse(StripPhysics.canReuseSingletonClusters(false, 2, 2, 1.0, spacing))
        assertFalse(StripPhysics.canReuseSingletonClusters(true, 2, 2, 0.0, spacing))
        assertFalse(StripPhysics.canReuseSingletonClusters(false, 1, 2, 0.0, spacing))
        assertEquals(10.0, StripPhysics.minClusterSpacing(longArrayOf(10, 20, 20), 2), 0.0)
    }

    @Test fun animationClockMatchesFullScanAndResetsBetweenPasses() {
        val random = Random(1402)
        val clock = ClusterAnimationClock()
        repeat(200) {
            val births = LongArray(random.nextInt(0, 200)) {
                if (random.nextBoolean()) 0L else random.nextLong(1, 10_001)
            }
            clock.reset()
            for (birth in births) clock.include(birth)
            val latest = births.maxOrNull() ?: 0L
            for (now in longArrayOf(10_000, 10_160, latest, latest + 159, latest + 160)) {
                assertEquals(births.any { it != 0L && now - it < 160L }, clock.isAnimating(now, 160L))
            }
        }
        clock.reset()
        clock.include(100L)
        assertTrue(clock.isAnimating(100L, 160L))
        clock.reset()
        clock.include(0L)
        assertFalse(clock.isAnimating(100L, 160L))
    }
}
