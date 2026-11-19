package com.overdrive.app.ui.daemon

/** Pure phase eligibility shared by reconnect recovery and health supervision. */
internal object DaemonStageGate {
    /** A reconnect before +90s may only recover phases already dispatched. */
    fun maySupervise(phase: Long, fired: Set<Long>, healthRunning: Boolean): Boolean =
        healthRunning || phase in fired

    /** Preserve the original monotonic clock origin across manager handoffs. */
    fun remainingMs(originMs: Long, phaseMs: Long, nowMs: Long): Long =
        (originMs + phaseMs - nowMs).coerceAtLeast(0L)
}
