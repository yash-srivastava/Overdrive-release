package com.overdrive.app.navmap.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapCopyrightBackoffTest {

    @Test
    fun copyrightBackoffStepsEscalateAndClamp() {
        assertEquals(15_000L, GoogleMapController.copyrightBackoffMs(0))
        assertEquals(30_000L, GoogleMapController.copyrightBackoffMs(1))
        assertEquals(60_000L, GoogleMapController.copyrightBackoffMs(2))
        assertEquals(180_000L, GoogleMapController.copyrightBackoffMs(3))
        assertEquals(180_000L, GoogleMapController.copyrightBackoffMs(10))
    }

    @Test
    fun nextCopyrightWaitHonorsRetryBackoffOverMinimumDebounce() {
        assertEquals(3_000L, GoogleMapController.nextCopyrightWaitMs(nowMs = 10_000L, lastAttemptMs = 10_000L, retryAtMs = 0L))
        assertEquals(15_000L, GoogleMapController.nextCopyrightWaitMs(nowMs = 10_000L, lastAttemptMs = 10_000L, retryAtMs = 25_000L))
        assertEquals(0L, GoogleMapController.nextCopyrightWaitMs(nowMs = 30_000L, lastAttemptMs = 10_000L, retryAtMs = 25_000L))
    }

    @Test
    fun viewportWarningThrottleEmitsOncePerMinuteWindow() {
        assertTrue(GoogleTileSessions.shouldEmitViewportWarning(nowNs = 1_000L, lastWarnNs = 0L))
        assertFalse(GoogleTileSessions.shouldEmitViewportWarning(nowNs = 30_000_000_000L, lastWarnNs = 1_000L))
        assertTrue(GoogleTileSessions.shouldEmitViewportWarning(nowNs = 60_000_001_000L, lastWarnNs = 1_000L))
    }
}
