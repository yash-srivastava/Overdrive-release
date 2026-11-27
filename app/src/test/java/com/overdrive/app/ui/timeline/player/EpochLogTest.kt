package com.overdrive.app.ui.timeline.player

import com.overdrive.app.ui.timeline.TlSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EpochLogTest {
    private fun epoch(
        id: String,
        start: Long,
        playFrom: Long,
        playTo: Long,
        tagBase: Long,
        firstClipUs: Long,
        mediaStartUs: Long,
        join: Join = Join.START,
    ): Epoch {
        val clip = TlSegment(
            id, "normal", start, playTo + 2000, "", true, null, 0, null,
            0, 0, id, "", "", "", null, emptyList(), emptyMap(), null,
            null, null, null, false,
        )
        return Epoch(1, PlanItem(clip, playFrom, playTo), tagBase, firstClipUs,
            mediaStartUs, join, 0, null, false)
    }

    @Test fun trimmedClipMapsDecoderTagsToMediaAndWallTime() {
        // The decoder starts before the visible slice (a preceding sync frame).
        val e = epoch("a", 10_000, 12_000, 16_000, 100_000, 1_500_000, 5_000_000)
        assertEquals(2_000_000L, e.offsetClipUs)
        assertEquals(6_000_000L, e.endClipUs)
        assertEquals(9_000_000L, e.plannedMediaEndUs)
        assertEquals(1_350_000L, e.tagOfClipUs(2_750_000))
        assertEquals(2_750_000L, e.clipUsOfTag(1_350_000))
        assertEquals(5_750_000L, e.mediaUsOfTag(1_350_000))
        assertEquals(12_750L, e.wallOfMediaUs(5_750_000))
        assertEquals(5_750_000L, e.mediaUsOfWall(12_750))
        assertEquals(12_000L, e.wallOfMediaUs(4_500_000))
        assertEquals(16_000L, e.wallOfMediaUs(10_000_000))
    }

    @Test fun joinedEpochsSwitchAtTheirOwnTagAndMediaBoundaries() {
        val log = EpochLog(1)
        assertTrue(log.isEmpty())
        assertNull(log.first())
        assertNull(log.last())
        assertNull(log.forTag(0))
        assertNull(log.atMedia(0))
        assertNull(log.wallAtMedia(0))

        val a = epoch("a", 10_000, 12_000, 16_000, 100_000, 1_500_000, 5_000_000)
        val b = epoch("b", 30_000, 30_000, 34_000, 10_000_000, 0, 9_000_000, Join.GAP)
        log.add(a)
        log.add(b)
        assertFalse(log.isEmpty())
        assertSame(a, log.first())
        assertSame(b, log.last())
        assertSame(b, log.after(a))
        assertNull(log.after(b))
        assertNull(log.forTag(99_999))
        assertSame(a, log.forTag(9_999_999))
        assertSame(b, log.forTag(10_000_000))
        assertSame(a, log.atMedia(4_000_000))
        assertSame(a, log.atMedia(8_999_999))
        assertSame(b, log.atMedia(9_000_000))
        assertEquals(15_999L, log.wallAtMedia(8_999_999)!!)
        assertEquals(30_000L, log.wallAtMedia(9_000_000)!!)
        assertEquals(30_250L, log.wallAtMedia(9_250_000)!!)
        assertEquals(34_000L, log.wallAtMedia(20_000_000)!!)
    }

    @Test fun effectiveEndHonorsNextEpochAndEarlierCut() {
        val log = EpochLog(1)
        val a = epoch("a", 10_000, 12_000, 16_000, 100_000, 1_500_000, 5_000_000)
        log.add(a)
        assertEquals(9_000_000L, log.mediaEndOf(a))
        val b = epoch("b", 30_000, 30_000, 34_000, 10_000_000, 0, 8_000_000, Join.GAP)
        log.add(b)
        assertEquals(8_000_000L, log.mediaEndOf(a))
        a.cutMediaUs = 7_500_000
        assertEquals(7_500_000L, log.mediaEndOf(a))
        assertEquals(14_500L, log.wallAtMedia(7_999_999)!!)
        assertEquals(30_000L, log.wallAtMedia(8_000_000)!!)
    }

    @Test fun quietPlaybackModeChangesAtMarkedTagsIncludingAfterGrowth() {
        val e = epoch("a", 10_000, 12_000, 16_000, 100_000, 1_500_000, 5_000_000)
        assertFalse(e.fastAt(100_000))
        e.markFast(110_000, true)
        e.markFast(120_000, true) // Repeated state does not create another transition.
        e.markFast(130_000, false)
        e.markFast(140_000, true)
        e.markFast(150_000, false)
        e.markFast(160_000, true)
        e.markFast(170_000, false)
        assertFalse(e.fastAt(109_999))
        assertTrue(e.fastAt(110_000))
        assertTrue(e.fastAt(129_999))
        assertFalse(e.fastAt(130_000))
        assertTrue(e.fastAt(140_000))
        assertFalse(e.fastAt(150_000))
        assertTrue(e.fastAt(160_000))
        assertFalse(e.fastAt(170_000))
        assertFalse(e.fastAt(200_000))
    }
}
