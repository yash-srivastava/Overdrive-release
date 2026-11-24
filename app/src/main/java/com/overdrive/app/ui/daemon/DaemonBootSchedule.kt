package com.overdrive.app.ui.daemon

/** Fixed boot deadlines shared by the boot and Activity managers. */
object DaemonBootSchedule {

    /** Tailscale goes first so its SOCKS listener is bound before MQTT starts. */
    const val TAILSCALE_LEAD_DELAY_MS = 30_000L

    /** Core daemons (camera — which owns MQTT — then the sentries). */
    const val CORE_DELAY_MS = 45_000L

    /** Stage the sentries 5 and 10 seconds after the camera instead of a launch burst. */
    const val SENTRY_STAGGER_MS = 5_000L
    const val ACC_SENTRY_STAGGER_MS = 10_000L

    /** Remaining optional daemons/tunnels. */
    const val OPTIONAL_DELAY_MS = 60_000L

    /** Periodic liveness check that relaunches anything that died. */
    const val HEALTH_CHECK_DELAY_MS = 90_000L

    /** Telegram follows the optional stage by 15 seconds. */
    const val TELEGRAM_DELAY_MS = OPTIONAL_DELAY_MS + 15_000L

    /** A fired timer is complete only if its guarded start was dispatched. */
    fun completedPhasesForHandoff(
        fired: Set<Long>,
        delivered: Set<Long>,
        optionalPending: Boolean,
    ): Set<Long> {
        val completed = fired intersect delivered
        return if (optionalPending) completed - OPTIONAL_DELAY_MS else completed
    }

    /** Fill gaps between five-second core starts with one-second tunnel starts. */
    const val DEFERRED_REPLAY_STAGGER_MS = 1_000L

    fun replayDelays(types: List<com.overdrive.app.ui.model.DaemonType>): Map<com.overdrive.app.ui.model.DaemonType, Long> {
        val core = listOf(
            com.overdrive.app.ui.model.DaemonType.CAMERA_DAEMON,
            com.overdrive.app.ui.model.DaemonType.SENTRY_DAEMON,
            com.overdrive.app.ui.model.DaemonType.ACC_SENTRY_DAEMON
        )
        val delays = linkedMapOf<com.overdrive.app.ui.model.DaemonType, Long>()
        var coreDelay = 0L
        for (type in core) {
            if (type in types) {
                delays[type] = coreDelay
                coreDelay += SENTRY_STAGGER_MS
            }
        }
        var tunnelDelay = if (delays.isEmpty()) 0L else DEFERRED_REPLAY_STAGGER_MS
        for (type in types.distinct().filterNot { it in core }.sortedBy { replayPriority(it) }) {
            while (tunnelDelay in delays.values) tunnelDelay += DEFERRED_REPLAY_STAGGER_MS
            delays[type] = tunnelDelay
            tunnelDelay += DEFERRED_REPLAY_STAGGER_MS
        }
        return delays
    }

    /** Only already-dispatched stages are complete after supervision starts. */
    fun hasUndeliveredStartupStages(
        fired: Set<Long>, delivered: Set<Long>, optionalPending: Boolean
    ): Boolean = optionalPending || (fired - delivered - HEALTH_CHECK_DELAY_MS).isNotEmpty()

    /** Keep critical core starts first; Tailscale precedes Cloudflared/Zrok. */
    fun replayPriority(type: com.overdrive.app.ui.model.DaemonType): Int = when (type) {
        com.overdrive.app.ui.model.DaemonType.CAMERA_DAEMON -> 0
        com.overdrive.app.ui.model.DaemonType.SENTRY_DAEMON -> 1
        com.overdrive.app.ui.model.DaemonType.ACC_SENTRY_DAEMON -> 2
        com.overdrive.app.ui.model.DaemonType.TAILSCALE_TUNNEL -> 3
        else -> 4
    }

    /** A finished health-check cycle resumes supervision, never startup stages. */
    fun shouldResumeStagedBoot(bootStartPending: Boolean, parkEnded: Boolean): Boolean =
        bootStartPending || parkEnded

    /** Positive lead gives the Tailscale SOCKS proxy time to precede camera MQTT. */
    fun proxyLeadMs(): Long = CORE_DELAY_MS - TAILSCALE_LEAD_DELAY_MS
}
