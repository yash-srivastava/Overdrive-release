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
    fun handoffRearmsFiredStartsWhileTheirAdbProbesAreUnconfirmed() {
        val lead = DaemonBootSchedule.TAILSCALE_LEAD_DELAY_MS
        val camera = DaemonBootSchedule.CORE_DELAY_MS
        val sentry = camera + DaemonBootSchedule.SENTRY_STAGGER_MS
        val accSentry = camera + DaemonBootSchedule.ACC_SENTRY_STAGGER_MS
        val telegram = DaemonBootSchedule.TELEGRAM_DELAY_MS
        val fired = setOf(lead, camera, sentry, accSentry, telegram)
        val delivered = setOf(lead, sentry)

        // The transport may be connected even though camera, ACC and Telegram
        // sentinel commands are still queued on the old manager.
        assertTrue(DaemonBootSchedule.completedPhasesForHandoff(
            fired, delivered, optionalPending = false) == delivered)
        assertTrue(DaemonBootSchedule.completedPhasesForHandoff(
            fired, emptySet(), optionalPending = false).isEmpty())
    }

    @Test
    fun handoffPreservesDeliveredStagesButRearmsUnfinishedOptionalTunnels() {
        val camera = DaemonBootSchedule.CORE_DELAY_MS
        val optional = DaemonBootSchedule.OPTIONAL_DELAY_MS
        val fired = setOf(camera, optional)
        assertTrue(DaemonBootSchedule.completedPhasesForHandoff(
            fired, fired, optionalPending = false) == fired)
        assertTrue(DaemonBootSchedule.completedPhasesForHandoff(
            fired, fired, optionalPending = true) == setOf(camera))
        assertTrue(DaemonBootSchedule.completedPhasesForHandoff(
            fired, setOf(optional), optionalPending = false) == setOf(optional))
    }

    @Test
    fun handoffAfterHealthCheckStillRearmsUndeliveredStages() {
        val camera = DaemonBootSchedule.CORE_DELAY_MS
        val health = DaemonBootSchedule.HEALTH_CHECK_DELAY_MS
        val fired = setOf(camera, health)
        assertTrue(DaemonBootSchedule.completedPhasesForHandoff(
            fired, setOf(health), optionalPending = false) == setOf(health))
        assertTrue(DaemonBootSchedule.completedPhasesForHandoff(
            fired, fired, optionalPending = false) == fired)
    }

    @Test
    fun bootStillStabilizesBeforeTheFirstLaunch() {
        // The lead must not drag the first daemon launch back to the very start of boot, where the
        // system is still settling — that is what the original 45s stabilisation delay was for.
        assertTrue(DaemonBootSchedule.TAILSCALE_LEAD_DELAY_MS > 0)
    }
}
