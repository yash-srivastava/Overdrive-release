package com.overdrive.app.ui.daemon
import com.overdrive.app.util.ScratchPaths

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy tests for the stop gate.
 *
 * The distinction that actually matters here is between the two entry points: startup must IGNORE
 * the parked-shutdown marker, and the health check must HONOUR it. Getting that backwards would let
 * the 30s health check resurrect a stack that "Vehicle ON only" deliberately tore down on park —
 * defeating the zero-compute park and draining the 12V battery. That is why the policies are named
 * functions rather than a boolean parameter: these tests pin each one to its caller's semantics.
 */
class DaemonStopGateTest {

    private val sentinel: String

        get() = ScratchPaths.path("tailscale.disabled")
    private val marker: String
        get() = ScratchPaths.path("overdrive_parked_shutdown")

    private fun present(vararg paths: String) = { p: String -> p in paths }

    // ---- startup policy: sentinel only ----

    @Test
    fun startup_allowsWhenNothingPresent() {
        assertFalse(DaemonStopGate.isStartBlocked(sentinel, present()))
    }

    @Test
    fun startup_blockedByUserStopSentinel() {
        assertTrue(DaemonStopGate.isStartBlocked(sentinel, present(sentinel)))
    }

    @Test
    fun startup_IGNORES_parkedMarker() {
        // The startup paths cannot run under a live marker (BootReceiver gates the boot path;
        // initializeOnAppLaunch clears it first), so a lingering marker must not block them.
        // This reproduces the previous ADB probe exactly: `test -f <sentinel>`, no marker term.
        assertFalse(DaemonStopGate.isStartBlocked(sentinel, present(marker)))
    }

    // ---- health-check policy: sentinel OR parked marker ----

    @Test
    fun relaunch_allowsWhenNothingPresent() {
        assertFalse(DaemonStopGate.isRelaunchBlocked(sentinel, marker, present()))
    }

    @Test
    fun relaunch_blockedByUserStopSentinel() {
        assertTrue(DaemonStopGate.isRelaunchBlocked(sentinel, marker, present(sentinel)))
    }

    @Test
    fun relaunch_HONOURS_parkedMarker() {
        // The regression that matters: without this the health check revives a parked stack.
        assertTrue(DaemonStopGate.isRelaunchBlocked(sentinel, marker, present(marker)))
    }

    @Test
    fun relaunch_blockedWhenBothPresent() {
        assertTrue(DaemonStopGate.isRelaunchBlocked(sentinel, marker, present(sentinel, marker)))
    }

    @Test
    fun relaunch_checksSentinelIndependentlyOfMarker() {
        // Guards against a short-circuit that only ever consults one of the two paths.
        assertFalse(DaemonStopGate.isRelaunchBlocked(sentinel, marker, present("/some/other/file")))
    }

    // ---- content-aware startup policy (Telegram): sentinel text discriminates user vs machine stop ----

    private val telegramSentinel: String

        get() = ScratchPaths.path("telegram_bot_daemon.disabled")
    private fun firstLine(map: Map<String, String?>) = { p: String -> map[p] }

    @Test
    fun contentAware_allowsWhenSentinelAbsent() {
        assertFalse(
            DaemonStopGate.isStartBlockedContentAware(
                telegramSentinel, present(), firstLine(emptyMap())
            )
        )
    }

    @Test
    fun contentAware_blocksOnUserStopText() {
        // A user stop leaves text that is NOT a machine marker → block.
        assertTrue(
            DaemonStopGate.isStartBlockedContentAware(
                telegramSentinel, present(telegramSentinel),
                firstLine(mapOf(telegramSentinel to "stopped by user 2026-08-02"))
            )
        )
    }

    @Test
    fun contentAware_allowsMachineStopText() {
        // The update sweep's write-if-absent "stopAllDaemons" and the ACC-off sweep are machine
        // stops, not user stops — a pref-enabled daemon must start.
        assertFalse(
            DaemonStopGate.isStartBlockedContentAware(
                telegramSentinel, present(telegramSentinel),
                firstLine(mapOf(telegramSentinel to "stopAllDaemons before update"))
            )
        )
        assertFalse(
            DaemonStopGate.isStartBlockedContentAware(
                telegramSentinel, present(telegramSentinel),
                firstLine(mapOf(telegramSentinel to "ACC-on edge"))
            )
        )
    }

    @Test
    fun contentAware_unreadableSentinelTreatedAsUserStop() {
        // Present but unreadable (null first line) → block, matching the old `grep` echoing STOPPED.
        assertTrue(
            DaemonStopGate.isStartBlockedContentAware(
                telegramSentinel, present(telegramSentinel),
                firstLine(mapOf(telegramSentinel to null))
            )
        )
    }

    // ---- the two policies must not be interchangeable ----

    @Test
    fun theTwoPoliciesDifferOnTheMarker() {
        val markerOnly = present(marker)
        assertFalse(
            "startup must ignore the park marker",
            DaemonStopGate.isStartBlocked(sentinel, markerOnly)
        )
        assertTrue(
            "health check must honour the park marker",
            DaemonStopGate.isRelaunchBlocked(sentinel, marker, markerOnly)
        )
    }
}
