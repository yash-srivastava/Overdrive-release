package com.overdrive.app.ui.timeline

import android.content.res.Resources
import com.overdrive.app.R

/** Explains a no-footage stretch: reaped > journal state > unarmed parking session > trip > none. */
object GapReasons {
    const val NONE = 0
    const val REAPED = 1
    const val STATE = 2
    const val SESSION = 3
    const val TRIP = 4

    /** A tier explains a gap only when its entries cover at least this share of it. */
    const val MIN_SHARE = 0.5

    private val UNARMED = setOf(
        "surveillance_off", "vehicle_on_only", "suppressed_safe_zone", "suppressed_schedule", "pipeline_down", "lock_wait",
    )

    fun overlap(a0: Long, a1: Long, b0: Long, b1: Long): Long {
        val s = maxOf(a0, b0)
        val e = minOf(a1, b1)
        return if (e > s) e - s else 0L
    }

    /** Writes, per gap, the winning tier and the index of its most-overlapping entry in that tier's list. */
    fun classify(d: StripData, gapStart: LongArray, gapEnd: LongArray, n: Int, outSource: IntArray, outIndex: IntArray) {
        if (n == 0) return
        // Build a tier only if a gap actually reaches it; small windows keep the linear path.
        val indexWindow = n >= 8
        var stateIndex: IntervalIndex? = null
        var sessionIndex: IntervalIndex? = null
        var tripIndex: IntervalIndex? = null
        val order = d.reaped.indices.sortedBy { d.reaped[it].start }
        val rStart = LongArray(order.size) { d.reaped[order[it]].start }
        var maxReap = 0L
        for (r in d.reaped) maxReap = maxOf(maxReap, r.end - r.start)
        for (g in 0 until n) {
            val gs = gapStart[g]
            val ge = gapEnd[g]
            val need = (ge - gs) * MIN_SHARE
            outSource[g] = NONE
            outIndex[g] = -1

            var sum = 0L
            var best = -1
            var bestOv = 0L
            var i = StripPhysics.lowerBound(rStart, rStart.size, (gs - maxReap).toDouble())
            while (i < rStart.size && rStart[i] < ge) {
                val r = d.reaped[order[i]]
                val ov = overlap(gs, ge, r.start, r.end)
                if (ov > 0) {
                    sum += ov
                    if (ov > bestOv) { bestOv = ov; best = order[i] }
                }
                i++
            }
            if (best >= 0 && sum >= need) { outSource[g] = REAPED; outIndex[g] = best; continue }

            if (stateIndex == null && indexWindow && d.states.size >= 32) {
                stateIndex = buildIndex(d.states, { it.start }, { it.end ?: d.now })
            }
            best = findTier(d.states, stateIndex, gs, ge, need, { it.start }, { it.end ?: d.now })
            if (best >= 0) { outSource[g] = STATE; outIndex[g] = best; continue }

            if (sessionIndex == null && indexWindow && d.sessions.size >= 32) {
                sessionIndex = buildIndex(d.sessions, { it.start }, { it.end ?: d.now }) { it.sentry in UNARMED }
            }
            best = findTier(d.sessions, sessionIndex, gs, ge, need, { it.start }, { it.end ?: d.now }) { it.sentry in UNARMED }
            if (best >= 0) { outSource[g] = SESSION; outIndex[g] = best; continue }

            if (tripIndex == null && indexWindow && d.trips.size >= 32) {
                tripIndex = buildIndex(d.trips, { it.start }, { it.end ?: d.now })
            }
            best = findTier(d.trips, tripIndex, gs, ge, need, { it.start }, { it.end ?: d.now })
            if (best >= 0) { outSource[g] = TRIP; outIndex[g] = best }
        }
    }

    private fun <T> buildIndex(
        entries: List<T>, start: (T) -> Long, end: (T) -> Long,
        include: (T) -> Boolean = { true },
    ): IntervalIndex {
        val order = entries.indices.filter { include(entries[it]) }.sortedBy { start(entries[it]) }
        return IntervalIndex(
            order.toIntArray(),
            LongArray(order.size) { start(entries[order[it]]) },
            LongArray(order.size) { end(entries[order[it]]) },
        )
    }

    private inline fun <T> findTier(
        entries: List<T>, index: IntervalIndex?, gs: Long, ge: Long, need: Double,
        start: (T) -> Long, end: (T) -> Long, include: (T) -> Boolean = { true },
    ): Int {
        if (index != null) return index.find(gs, ge, need)
        var sum = 0L
        var best = -1
        var bestOv = 0L
        for (k in entries.indices) {
            val entry = entries[k]
            if (!include(entry)) continue
            val ov = overlap(gs, ge, start(entry), end(entry))
            if (ov > 0) {
                sum += ov
                if (ov > bestOv) { bestOv = ov; best = k }
            }
        }
        return if (sum >= need) best else -1
    }

    /** Start-sorted intervals, with prefix maximum ends to retain nested/overlapping entries. */
    private class IntervalIndex(
        private val order: IntArray,
        private val starts: LongArray,
        private val ends: LongArray,
    ) {
        private val maxEnd = LongArray(ends.size)

        init {
            var latest = Long.MIN_VALUE
            for (i in ends.indices) {
                latest = maxOf(latest, ends[i])
                maxEnd[i] = latest
            }
        }

        fun find(gs: Long, ge: Long, need: Double): Int {
            // No interval before lo can end after the gap starts.
            var lo = 0
            var hi = maxEnd.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (maxEnd[mid] <= gs) lo = mid + 1 else hi = mid
            }
            var sum = 0L
            var best = -1
            var bestOv = 0L
            var i = lo
            while (i < starts.size && starts[i] < ge) {
                val ov = overlap(gs, ge, starts[i], ends[i])
                if (ov > 0) {
                    sum += ov
                    val k = order[i]
                    // Sorting must not change the original list-order tie break.
                    if (ov > bestOv || (ov == bestOv && k < best)) {
                        bestOv = ov
                        best = k
                    }
                }
                i++
            }
            return if (sum >= need) best else -1
        }
    }

    fun reasonRes(d: StripData, source: Int, index: Int): Int = when (source) {
        REAPED -> reapedRes(d.reaped[index].reason)
        STATE -> d.states[index].let { stateRes(it.kind, it.reason) }
        SESSION -> sessionRes(d.sessions[index].sentry)
        TRIP -> R.string.timeline_reason_driving
        else -> R.string.timeline_gap_no_footage
    }

    fun reapedRes(reason: String): Int = when (reason) {
        "user" -> R.string.timeline_reason_deleted
        "retention", "free_space", "relief" -> R.string.timeline_reason_overwritten
        else -> R.string.timeline_reason_removed
    }

    fun stateRes(kind: String, reason: String): Int = when (reason) {
        "user_off" -> R.string.timeline_reason_user_off
        "acc_on" -> R.string.timeline_reason_acc_on
        "owner_returned" -> R.string.timeline_reason_owner_returned
        "schedule_end" -> R.string.timeline_reason_schedule_end
        "safe_zone" -> R.string.timeline_reason_safe_zone
        "lease_revoked" -> R.string.timeline_reason_lease_revoked
        "pipeline_down" -> R.string.timeline_reason_pipeline_down
        "soc_cutoff" -> R.string.timeline_reason_soc_cutoff
        "lv_low" -> R.string.timeline_reason_lv_low
        "daemon_stop", "daemon_start" -> R.string.timeline_reason_daemon_stop
        "storage_unavailable" -> R.string.timeline_reason_storage_unavailable
        "storage_full" -> R.string.timeline_reason_storage_full
        else -> when (kind) {
            "daemon" -> R.string.timeline_reason_daemon_stop
            "storage" -> R.string.timeline_reason_storage_unavailable
            "surveillance" -> R.string.timeline_reason_user_off
            else -> R.string.timeline_reason_unknown
        }
    }

    fun sessionRes(sentry: String): Int = when (sentry) {
        "surveillance_off" -> R.string.timeline_sentry_surveillance_off
        "vehicle_on_only" -> R.string.timeline_sentry_vehicle_on_only
        "suppressed_safe_zone" -> R.string.timeline_sentry_suppressed_safe_zone
        "suppressed_schedule" -> R.string.timeline_sentry_suppressed_schedule
        "pipeline_down" -> R.string.timeline_sentry_pipeline_down
        "lock_wait" -> R.string.timeline_sentry_lock_wait
        else -> R.string.timeline_reason_unknown
    }

    fun durationText(res: Resources, ms: Long): String {
        val secs = (ms + 500L) / 1000L
        if (secs < 60L) return res.getString(R.string.timeline_duration_s, maxOf(1L, secs).toInt())
        val mins = (ms + 30_000L) / 60_000L
        if (mins < 60L) return res.getString(R.string.timeline_duration_m, mins.toInt())
        return res.getString(R.string.timeline_duration_hm, (mins / 60L).toInt(), (mins % 60L).toInt())
    }

    fun label(res: Resources, durationText: String, reasonText: String): String =
        res.getString(R.string.timeline_gap_fmt, durationText, reasonText)
}
