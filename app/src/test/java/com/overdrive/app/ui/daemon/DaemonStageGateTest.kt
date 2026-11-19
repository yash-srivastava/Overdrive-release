package com.overdrive.app.ui.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercise the scheduling decisions with simulated DI5 boot and handoff times. */
class DaemonStageGateTest {
    @Test fun reconnectBeforeStagesNeverLaunchesFutureDaemons() {
        val phases = setOf(45_000L, 50_000L, 55_000L, 60_000L, 75_000L)
        var fired = emptySet<Long>()
        assertTrue(phases.all { !DaemonStageGate.maySupervise(it, fired, false) })
        fired = setOf(45_000L)
        assertTrue(DaemonStageGate.maySupervise(45_000L, fired, false))
        assertTrue(phases.minus(45_000L).all { !DaemonStageGate.maySupervise(it, fired, false) })
        assertTrue(phases.all { DaemonStageGate.maySupervise(it, fired, true) })
    }

    @Test fun handoffKeepsPendingTelegramAndSentryDeadlines() {
        val origin = 100_000L
        val handoffAt = origin + 65_000L
        assertEquals(0L, DaemonStageGate.remainingMs(origin, 60_000L, handoffAt))
        assertEquals(10_000L, DaemonStageGate.remainingMs(origin, 75_000L, handoffAt))
        assertEquals(25_000L, DaemonStageGate.remainingMs(origin, 90_000L, handoffAt))
        assertFalse(DaemonStageGate.maySupervise(75_000L, setOf(60_000L), false))
    }

    @Test fun lateHandoffClampsElapsedDeadlinesToZero() {
        assertEquals(0L, DaemonStageGate.remainingMs(1_000L, 45_000L, 100_000L))
        assertEquals(45_000L, DaemonStageGate.remainingMs(1_000L, 45_000L, 1_000L))
    }
}
