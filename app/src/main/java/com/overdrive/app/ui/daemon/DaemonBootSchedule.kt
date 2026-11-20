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

    /** Positive lead gives the Tailscale SOCKS proxy time to precede camera MQTT. */
    fun proxyLeadMs(): Long = CORE_DELAY_MS - TAILSCALE_LEAD_DELAY_MS
}
