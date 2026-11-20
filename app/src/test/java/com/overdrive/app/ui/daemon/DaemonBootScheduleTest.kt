package com.overdrive.app.ui.daemon

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering invariants for the boot schedule.
 *
 * Deliberately narrow: these pin the *relative order* of the phases, which is the property the
 * Tailscale lead depends on and the one a careless edit to the constants would invert. They do NOT
 * assert the absolute values — those are tuning, not contract — and they cannot prove the lead is
 * long enough in the field (tailscaled's own start latency was measured between 10s and 40s on the
 * head unit, so the fixed lead narrows the race rather than closing it; see DaemonBootSchedule).
 */
class DaemonBootScheduleTest {

    @Test
    fun tailscaleLeadsTheCoreDaemons() {
        assertTrue(
            "tailscaled must be launched before the camera daemon starts MQTT",
            DaemonBootSchedule.TAILSCALE_LEAD_DELAY_MS < DaemonBootSchedule.CORE_DELAY_MS
        )
        assertTrue(DaemonBootSchedule.proxyLeadMs() > 0)
    }

    @Test
    fun phasesRunInTheDocumentedOrder() {
        assertTrue(DaemonBootSchedule.TAILSCALE_LEAD_DELAY_MS < DaemonBootSchedule.CORE_DELAY_MS)
        assertTrue(DaemonBootSchedule.CORE_DELAY_MS < DaemonBootSchedule.OPTIONAL_DELAY_MS)
        assertTrue(DaemonBootSchedule.OPTIONAL_DELAY_MS < DaemonBootSchedule.HEALTH_CHECK_DELAY_MS)
    }

    @Test
    fun handoffWithoutAdbRearmsAlreadyFiredStartsBeforeHealthSupervision() {
        val fired = setOf(
            DaemonBootSchedule.TAILSCALE_LEAD_DELAY_MS,
            DaemonBootSchedule.CORE_DELAY_MS,
            DaemonBootSchedule.CORE_DELAY_MS + DaemonBootSchedule.SENTRY_STAGGER_MS,
            DaemonBootSchedule.CORE_DELAY_MS + DaemonBootSchedule.ACC_SENTRY_STAGGER_MS,
            DaemonBootSchedule.OPTIONAL_DELAY_MS,
            DaemonBootSchedule.OPTIONAL_DELAY_MS + 15_000L,
        )
        val completed = DaemonBootSchedule.completedPhasesForHandoff(
            fired, adbAvailable = false, healthCheckRunning = false, optionalPending = true)
        assertTrue("ADB-blocked starts must replay on the next manager", completed.isEmpty())
    }

    @Test
    fun handoffWithAdbPreservesFiredStagesUnlessOptionalIsStillWaiting() {
        val camera = DaemonBootSchedule.CORE_DELAY_MS
        val optional = DaemonBootSchedule.OPTIONAL_DELAY_MS
        val fired = setOf(camera, optional)
        assertTrue(DaemonBootSchedule.completedPhasesForHandoff(
            fired, adbAvailable = true, healthCheckRunning = false, optionalPending = false) == fired)
        assertTrue(DaemonBootSchedule.completedPhasesForHandoff(
            fired, adbAvailable = true, healthCheckRunning = false, optionalPending = true)
            == setOf(camera))
    }

    @Test
    fun handoffAfterHealthCheckDoesNotRestartEarlierStages() {
        val camera = DaemonBootSchedule.CORE_DELAY_MS
        val health = DaemonBootSchedule.HEALTH_CHECK_DELAY_MS
        val fired = setOf(camera, health)
        assertTrue(DaemonBootSchedule.completedPhasesForHandoff(
            fired, adbAvailable = false, healthCheckRunning = true, optionalPending = false) == fired)
    }

    @Test
    fun bootStillStabilizesBeforeTheFirstLaunch() {
        // The lead must not drag the first daemon launch back to the very start of boot, where the
        // system is still settling — that is what the original 45s stabilisation delay was for.
        assertTrue(DaemonBootSchedule.TAILSCALE_LEAD_DELAY_MS > 0)
    }
}
