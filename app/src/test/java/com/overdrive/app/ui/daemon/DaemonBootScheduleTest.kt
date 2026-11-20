package com.overdrive.app.ui.daemon

import org.junit.Assert.assertTrue
import org.junit.Test

/** Ordering and handoff invariants for the pure boot policy.
 * Field timing and Android lifecycle behavior still require device validation.
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
    fun lateDiLink5ReplayFavorsCoreAndTailscaleOverPublicTunnels() {
        val priority = DaemonBootSchedule::replayPriority
        assertTrue(priority(com.overdrive.app.ui.model.DaemonType.CAMERA_DAEMON) <
            priority(com.overdrive.app.ui.model.DaemonType.SENTRY_DAEMON))
        assertTrue(priority(com.overdrive.app.ui.model.DaemonType.SENTRY_DAEMON) <
            priority(com.overdrive.app.ui.model.DaemonType.ACC_SENTRY_DAEMON))
        assertTrue(priority(com.overdrive.app.ui.model.DaemonType.ACC_SENTRY_DAEMON) <
            priority(com.overdrive.app.ui.model.DaemonType.TAILSCALE_TUNNEL))
        assertTrue(priority(com.overdrive.app.ui.model.DaemonType.TAILSCALE_TUNNEL) <
            priority(com.overdrive.app.ui.model.DaemonType.CLOUDFLARED_TUNNEL))
        assertTrue(priority(com.overdrive.app.ui.model.DaemonType.TAILSCALE_TUNNEL) <
            priority(com.overdrive.app.ui.model.DaemonType.ZROK_TUNNEL))
        assertTrue(DaemonBootSchedule.DEFERRED_REPLAY_STAGGER_MS < 5_000L)
    }

    @Test
    fun handoffAfterHealthCheckResumesOnlyNonDestructiveSupervision() {
        assertTrue(!DaemonBootSchedule.shouldResumeStagedBoot(
            bootStartPending = false, parkEnded = false))
        assertTrue(DaemonBootSchedule.shouldResumeStagedBoot(
            bootStartPending = true, parkEnded = false))
        assertTrue(DaemonBootSchedule.shouldResumeStagedBoot(
            bootStartPending = false, parkEnded = true))
    }

    @Test
    fun bootStillStabilizesBeforeTheFirstLaunch() {
        // The lead must not drag the first daemon launch back to the very start of boot, where the
        // system is still settling — that is what the original 45s stabilisation delay was for.
        assertTrue(DaemonBootSchedule.TAILSCALE_LEAD_DELAY_MS > 0)
    }
}
