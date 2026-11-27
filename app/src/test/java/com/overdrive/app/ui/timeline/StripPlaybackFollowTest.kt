package com.overdrive.app.ui.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StripPlaybackFollowTest {
    private val startNs = 10_000_000_000L
    private val wallMs = 1_800_000_000_000L

    @Test fun noReportUsesFallbackAndPausedReportDoesNotAdvance() {
        val follow = StripPhysics.Follow()
        assertFalse(follow.hasReport)
        assertFalse(follow.isPending)
        assertFalse(follow.extrapolating(startNs))
        assertEquals(42.0, follow.at(startNs, 42.0), 0.0)

        assertTrue(follow.report(wallMs, false, 4, startNs))
        assertTrue(follow.hasReport)
        assertFalse(follow.extrapolating(startNs))
        assertEquals(wallMs.toDouble(), follow.at(startNs + 5_000_000_000L, 42.0), 0.0)
    }

    @Test fun playingReportAdvancesAtSpeedOnlyUntilExtrapolationCap() {
        val follow = StripPhysics.Follow()
        assertTrue(follow.report(wallMs, true, 4, startNs))
        assertEquals(wallMs.toDouble(), follow.at(startNs - 100_000_000L, 0.0), 0.0)
        assertEquals(wallMs + 2_000.0, follow.at(startNs + 500_000_000L, 0.0), 0.0)
        assertTrue(follow.extrapolating(startNs + 1_199_000_000L))
        assertFalse(follow.extrapolating(startNs + 1_200_000_000L))
        assertEquals(wallMs + 4_800.0, follow.at(startNs + 1_200_000_000L, 0.0), 0.0)
        assertEquals(wallMs + 4_800.0, follow.at(startNs + 5_000_000_000L, 0.0), 0.0)
    }

    @Test fun playbackSpeedIsClampedAtBothEnds() {
        val follow = StripPhysics.Follow()
        assertTrue(follow.report(wallMs, true, 0, startNs))
        assertEquals(1, follow.speed)
        assertEquals(wallMs + 500.0, follow.at(startNs + 500_000_000L, 0.0), 0.0)
        assertTrue(follow.report(wallMs, true, 100, startNs))
        assertEquals(64, follow.speed)
        assertEquals(wallMs + 32_000.0, follow.at(startNs + 500_000_000L, 0.0), 0.0)
    }

    @Test fun pendingSeekRejectsContradictoryReportAndAcceptsToleranceBoundary() {
        val follow = StripPhysics.Follow()
        val target = wallMs + 60_000L
        follow.expect(target.toDouble(), startNs)
        assertFalse(follow.report(wallMs, false, 1, startNs + 100_000_000L))
        assertTrue(follow.isPending)
        // At 1x the tolerance is 2,000 ms; the boundary is inclusive.
        assertFalse(follow.report(target + 2_001L, false, 1, startNs + 200_000_000L))
        assertTrue(follow.isPending)
        assertTrue(follow.report(target + 2_000L, false, 1, startNs + 300_000_000L))
        assertFalse(follow.isPending)
        assertEquals(target + 2_000.0, follow.wall, 0.0)
    }

    @Test fun pendingSeekAcceptsPositionAdvancedByPlayingTime() {
        val follow = StripPhysics.Follow()
        val target = wallMs + 60_000L
        follow.expect(target.toDouble(), startNs)
        // After one second at 8x, the expected position is 8,000 ms ahead,
        // outside the 5,500 ms tolerance around the original seek target.
        assertTrue(follow.report(target + 8_000L, true, 8, startNs + 1_000_000_000L))
        assertFalse(follow.isPending)
    }

    @Test fun expireDropsStaleReportOnceAtTimeoutAndReturnsToFallback() {
        val follow = StripPhysics.Follow()
        follow.expect(wallMs + 60_000.0, startNs)
        assertFalse(follow.report(wallMs, true, 1, startNs + 100_000_000L))
        assertEquals(500.0, follow.pendingLeftMs(startNs + 1_000_000_000L), 0.0)
        assertFalse(follow.expire(startNs + 1_499_000_000L))
        assertTrue(follow.hasReport)
        assertTrue(follow.isPending)
        assertTrue(follow.expire(startNs + 1_500_000_000L))
        assertFalse(follow.hasReport)
        assertFalse(follow.isPending)
        assertFalse(follow.extrapolating(startNs + 1_500_000_000L))
        assertEquals(42.0, follow.at(startNs + 1_500_000_000L, 42.0), 0.0)
        assertFalse(follow.expire(startNs + 2_000_000_000L))
    }

    @Test fun reportAtTimeoutOrAfterExplicitClearIsAccepted() {
        val follow = StripPhysics.Follow()
        follow.expect(wallMs + 60_000.0, startNs)
        assertTrue(follow.report(wallMs, false, 1, startNs + 1_500_000_000L))
        assertFalse(follow.isPending)
        assertTrue(follow.hasReport)

        follow.expect(wallMs + 120_000.0, startNs + 2_000_000_000L)
        follow.clearPending()
        assertFalse(follow.isPending)
        assertTrue(follow.report(wallMs, false, 1, startNs + 2_100_000_000L))
    }
}
