package com.overdrive.app.roadsense.warn

import org.json.JSONObject

/**
 * Daemon-memory home of the live [OverlayState]. The warn tick publishes here instead of
 * rewriting the settings file; daemon readers call [current], app overlays ask over the
 * loopback IPC ([COMMAND], answered by [reply]).
 *
 * The etag changes only when something the overlays draw changes (maturity counts by its
 * three pill buckets), so an unchanged state answers with just the publish time.
 */
object RoadSenseOverlayChannel {
    const val COMMAND = "ROADSENSE_OVERLAY"

    // A daemon restart must never match an etag an app saw from the previous daemon.
    private val epoch = java.lang.Long.toHexString(java.security.SecureRandom().nextLong())
    private val lock = Any()
    private var state: OverlayState? = null
    private var drawn: OverlayState? = null
    private var version = 0L

    fun publish(s: OverlayState) {
        synchronized(lock) { put(s) }
    }

    /** Before the first tick publishes: a stale (ts 0) state, so the pill shows Idle and the calibration dot. */
    fun seedIfEmpty(s: OverlayState) {
        synchronized(lock) { if (state == null) put(s) }
    }

    private fun put(s: OverlayState) {
        val d = drawn
        if (d == null || !looksSame(d, s)) { drawn = s; version++ }
        state = s
    }

    // Everything the overlays draw; maturity by its pill bucket, the timestamp ignored.
    private fun looksSame(a: OverlayState, b: OverlayState): Boolean =
        a.calLevel() == b.calLevel() && a.coverage == b.coverage &&
            a.hazardAhead == b.hazardAhead && a.nextHazardMeters == b.nextHazardMeters &&
            a.nextHazardRelBearingDeg == b.nextHazardRelBearingDeg &&
            a.nextHazardSeverity == b.nextHazardSeverity && a.nextHazardType == b.nextHazardType &&
            a.zoneCount == b.zoneCount && a.zoneLengthM == b.zoneLengthM &&
            a.zoneRough == b.zoneRough && a.pendingConfirm == b.pendingConfirm

    /** Latest published state (its updatedMs is the last warn-tick publish), or null. */
    fun current(): OverlayState? = synchronized(lock) { state }

    /** `{success, v, ts?, state?}`: no ts before the first publish, state only when [etag] is out of date. */
    fun reply(etag: String, out: JSONObject): JSONObject {
        val s: OverlayState?
        val v: String
        synchronized(lock) { s = state; v = "$epoch.$version" }
        out.put("success", true).put("v", v)
        if (s == null) return out
        out.put("ts", s.updatedMs)
        if (v != etag) out.put("state", s.toJson())
        return out
    }
}
