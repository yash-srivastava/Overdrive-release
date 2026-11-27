package com.overdrive.app.ui.timeline.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameScheduleTest {
    @Test
    fun specBecomesDueAtToleranceBoundaryIncludingNullSpec() {
        val schedule = FrameSchedule<String>()
        schedule.scheduleSpec(100_000_000L, "first")
        schedule.scheduleSpec(200_000_000L, null)

        assertFalse(schedule.takeDueSpec(97_999_999L))
        assertEquals(2, schedule.pendingSpecs)
        assertTrue(schedule.takeDueSpec(98_000_000L))
        assertEquals("first", schedule.dueSpec)
        assertTrue(schedule.takeDueSpec(198_000_000L))
        assertNull(schedule.dueSpec)
        assertFalse(schedule.takeDueSpec(300_000_000L))
    }

    @Test
    fun retimingReplacesLaterEntriesAndDroppedFramesUseNewestDueSpec() {
        val schedule = FrameSchedule<String>()
        schedule.scheduleSpec(100_000_000L, "first")
        schedule.scheduleSpec(200_000_000L, "obsolete")
        schedule.scheduleSpec(300_000_000L, "obsolete too")
        schedule.scheduleSpec(200_000_000L, "replacement")

        assertEquals(2, schedule.pendingSpecs)
        assertTrue(schedule.takeDueSpec(350_000_000L))
        assertEquals("replacement", schedule.dueSpec)
        assertEquals(0, schedule.pendingSpecs)
    }

    @Test
    fun queuesKeepOnlyEightNewestEntries() {
        val schedule = FrameSchedule<Int>()
        for (i in 1..10) {
            schedule.scheduleSpec(i * 10_000_000L, i)
            schedule.scheduleFade(i * 10_000_000L, i.toLong())
        }

        assertEquals(8, schedule.pendingSpecs)
        assertEquals(8, schedule.pendingFades)
        assertFalse(schedule.takeDueSpec(20_000_000L))
        assertEquals(-1L, schedule.takeDueFade(20_000_000L))
        assertTrue(schedule.takeDueSpec(30_000_000L))
        assertEquals(Integer.valueOf(3), schedule.dueSpec)
        assertEquals(3L, schedule.takeDueFade(30_000_000L))
    }

    @Test
    fun fadesReplaceFutureEntriesAndClampDuration() {
        val schedule = FrameSchedule<String>()
        schedule.scheduleFade(100_000_000L, -10L)
        schedule.scheduleFade(200_000_000L, 500L)
        schedule.scheduleFade(300_000_000L, 900L)
        schedule.scheduleFade(200_000_000L, 5_000L)

        assertEquals(2, schedule.pendingFades)
        assertEquals(0L, schedule.takeDueFade(100_000_000L))
        assertEquals(2_000L, schedule.takeDueFade(350_000_000L))
        assertEquals(-1L, schedule.takeDueFade(400_000_000L))
    }

    @Test
    fun freshSnapshotStartsFadeAndProgressStopsAtCompletion() {
        val schedule = FrameSchedule<String>()
        val at = 2_000_000_000L
        schedule.scheduleFade(at, 400L)
        assertFalse(schedule.wantsSnapshot(true, 499_999_999L))
        assertFalse(schedule.wantsSnapshot(false, 500_000_000L))
        assertTrue(schedule.wantsSnapshot(true, 500_000_000L))
        schedule.onSnapshot()

        assertTrue(schedule.endLatch(schedule.takeDueFade(at), at))
        assertFalse(schedule.snapshotFresh)
        assertTrue(schedule.fading)
        assertEquals(0f, schedule.fadeProgress(at), 0f)
        assertEquals(0.5f, schedule.fadeProgress(at + 200_000_000L), 0f)
        assertEquals(1f, schedule.fadeProgress(at + 400_000_000L), 0f)
        assertFalse(schedule.fading)
    }

    @Test
    fun heldPictureSurvivesUntilScheduledFadeAndDoesNotNeedNewSnapshot() {
        val schedule = FrameSchedule<String>()
        val at = 100_000_000L
        schedule.hold()
        schedule.scheduleFade(at, 200L)

        assertFalse(schedule.wantsSnapshot(true, 50_000_000L))
        assertFalse(schedule.endLatch(schedule.takeDueFade(50_000_000L), 50_000_000L))
        assertTrue(schedule.holdShown)
        assertFalse(schedule.endLatch(schedule.takeDueFade(at), at))
        assertFalse(schedule.holdShown)
        assertTrue(schedule.fading)
    }

    @Test
    fun noPendingFadeReleasesHoldAndZeroDurationDoesNotBlend() {
        val schedule = FrameSchedule<String>()
        schedule.hold()
        assertFalse(schedule.endLatch(-1L, 100_000_000L))
        assertFalse(schedule.holdShown)

        schedule.onSnapshot()
        assertFalse(schedule.endLatch(0L, 200_000_000L))
        assertFalse(schedule.snapshotFresh)
        assertFalse(schedule.fading)
        assertEquals(1f, schedule.fadeProgress(200_000_000L), 0f)
    }
}
