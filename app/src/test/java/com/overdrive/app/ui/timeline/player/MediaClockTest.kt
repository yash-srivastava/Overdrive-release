package com.overdrive.app.ui.timeline.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClockTest {
    private var nowNs = 1_000_000_000L
    private val clock = MediaClock { nowNs }

    private fun elapsedMs(ms: Long) {
        nowNs += ms * 1_000_000L
    }

    private class FakeAudio(var position: Long) : AudioTimeSource {
        override fun audioMediaUs(): Long = position
    }

    @Test fun speedChangesPauseAndResumePreservePosition() {
        clock.seekTo(2_000_000L)
        clock.start()
        assertTrue(clock.running)
        elapsedMs(250)
        assertEquals(2_250_000L, clock.positionUs())

        clock.setSpeed(2f)
        assertEquals(2_250_000L, clock.positionUs())
        elapsedMs(250)
        assertEquals(2_750_000L, clock.positionUs())

        clock.pause()
        assertFalse(clock.running)
        elapsedMs(1_000)
        assertEquals(2_750_000L, clock.positionUs())
        clock.start()
        elapsedMs(100)
        assertEquals(2_950_000L, clock.positionUs())
    }

    @Test fun videoLimitHoldsClockUntilMoreFramesAreAvailable() {
        clock.setLimit(100_000L)
        clock.start()
        elapsedMs(200)
        assertEquals(100_000L, clock.positionUs())
        elapsedMs(100)
        assertEquals(100_000L, clock.positionUs())
        assertEquals(100_000_000L, clock.stalledForNs())

        clock.setLimit(500_000L)
        assertEquals(100_000L, clock.positionUs())
        assertEquals(0L, clock.stalledForNs())
        elapsedMs(150)
        assertEquals(250_000L, clock.positionUs())
    }

    @Test fun aheadAudioSynchronizesForwardAndBogusLateAudioIsIgnored() {
        val audio = FakeAudio(1_000_000L)
        clock.audio = audio
        clock.start()
        elapsedMs(100)
        assertEquals(1_000_000L, clock.positionUs())

        audio.position = 0L
        elapsedMs(100)
        assertEquals(1_100_000L, clock.positionUs())
        audio.position = AudioTimeSource.NONE
        elapsedMs(100)
        assertEquals(1_200_000L, clock.positionUs())
    }

    @Test fun lateAudioCorrectionCannotSlowPlaybackBelowQuarterSpeed() {
        clock.seekTo(1_000_000L)
        val audio = FakeAudio(1_000_000L)
        clock.audio = audio
        clock.start()
        assertEquals(1_000_000L, clock.positionUs())

        // A 400 ms lag would request an 80 ms correction over this 100 ms step.
        // The 75 ms slowdown cap leaves 25 ms of forward progress instead.
        audio.position = 700_000L
        elapsedMs(100)
        assertEquals(1_025_000L, clock.positionUs())
        audio.position = AudioTimeSource.NONE
        elapsedMs(100)
        assertEquals(1_125_000L, clock.positionUs())
    }

    @Test fun advanceIsForwardOnlyButSeekResetsPositionAndVideoLimit() {
        clock.seekTo(1_000_000L)
        clock.start()
        clock.advanceTo(2_000_000L)
        clock.advanceTo(1_500_000L)
        assertEquals(2_000_000L, clock.positionUs())

        clock.setLimit(2_000_000L)
        elapsedMs(100)
        assertEquals(2_000_000L, clock.positionUs())
        elapsedMs(100)
        assertEquals(100_000_000L, clock.stalledForNs())

        clock.seekTo(500_000L)
        assertEquals(500_000L, clock.positionUs())
        assertEquals(0L, clock.stalledForNs())
        elapsedMs(2_000)
        assertEquals(2_500_000L, clock.positionUs())
    }
}
