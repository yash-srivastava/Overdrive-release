package com.overdrive.app.ui.daemon

/**
 * Pure boot-timing policy for the daemon startup sequence.
 *
 * Extracted from [DaemonStartupManager.initializeOnBoot] so the ordering rule is
 * unit-testable without a Looper, a Context, or an ADB connection.
 *
 * The rule that matters is the Tailscale lead. The MQTT publisher lives inside the
 * camera daemon, and when the tunnel is enabled a LAN broker is normally reachable
 * ONLY through the SOCKS proxy that tailscaled exposes. Starting the camera daemon
 * first means the publisher wakes, finds no proxy and — per issue #182 — defers.
 *
 * What that deferral costs is small, and NOT the 300s backoff an earlier version of
 * this comment claimed. The #182 fix is already in this branch's history, and its
 * whole point is that it does *not* bump `consecutiveFailures` while waiting on the
 * proxy: `MqttConnectionManager` therefore re-probes at the min-interval floor
 * (`DEFAULT_MIN_INTERVAL` = 5s) and connects the moment the listener binds. A few
 * seconds of ordering slip costs a few seconds of telemetry.
 *
 * The lead earns its keep at the TAIL, not the average. The deferral is bounded by
 * `PROXY_WARMUP_GRACE_MS` (60s), after which the publisher gives up waiting and falls
 * through to a direct dial — which off Wi-Fi can never reach a LAN address, so the
 * connection then churns until something restarts the daemon. With the core daemons
 * first, tailscaled is only *launched* at 60s and needs a further 10–40s, while the
 * grace window opens when MQTT first defers (~45–50s): that lands near the edge of
 * the window, and past it the failure is the bad one. Leading with tailscaled moves
 * the proxy up to ~40–70s, comfortably inside. Buying that margin is the point — not
 * the handful of seconds saved on a good boot.
 */
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

    // A timer firing does not mean its ADB-gated start succeeded. When the
    // connection was unavailable during a boot/Activity handoff, a new manager
    // must retry these already-fired stages at their original (now elapsed)
    // deadlines instead of waiting for the first periodic health tick.
    private val ADB_GATED_STAGES = setOf(
        TAILSCALE_LEAD_DELAY_MS,
        CORE_DELAY_MS,
        CORE_DELAY_MS + SENTRY_STAGGER_MS,
        CORE_DELAY_MS + ACC_SENTRY_STAGGER_MS,
        OPTIONAL_DELAY_MS + 15_000L, // Telegram has its own stage.
    )

    /** Return only phases the next manager can safely treat as completed. */
    fun completedPhasesForHandoff(
        fired: Set<Long>,
        adbAvailable: Boolean,
        healthCheckRunning: Boolean,
        optionalPending: Boolean,
    ): Set<Long> {
        var completed = fired
        if (!adbAvailable && !healthCheckRunning) completed = completed - ADB_GATED_STAGES
        if (optionalPending) completed = completed - OPTIONAL_DELAY_MS
        return completed
    }

    /**
     * How much head start tailscaled gets over the core daemons. Positive by
     * construction; asserted by [DaemonBootScheduleTest] so a future edit to the
     * constants can't silently invert the order.
     */
    fun proxyLeadMs(): Long = CORE_DELAY_MS - TAILSCALE_LEAD_DELAY_MS
}
