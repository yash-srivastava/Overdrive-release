package com.overdrive.app.briefing.daemon

import com.overdrive.app.briefing.engine.Offer
import com.overdrive.app.briefing.engine.Routine
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

internal sealed class BriefingExtra {
    abstract val type: String
    abstract fun toJson(): JSONObject

    data class Range(val rangeKm: Int, val tripKm: Int) : BriefingExtra() {
        override val type get() = "range"
        override fun toJson(): JSONObject = JSONObject().put("type", type).put("rangeKm", rangeKm).put("tripKm", tripKm)
    }

    data class Hazards(val count: Int) : BriefingExtra() {
        override val type get() = "hazards"
        override fun toJson(): JSONObject = JSONObject().put("type", type).put("count", count)
    }

    data class Rain(val hour: Int) : BriefingExtra() {
        override val type get() = "rain"
        override fun toJson(): JSONObject = JSONObject().put("type", type).put("hour", hour)
    }
}

/**
 * At most one extra line per offer, highest priority first: range, hazards (both on the car), then rain (the
 * one online source, only when [online]). A failure or timeout yields null; the step never exceeds [budgetMs].
 */
internal class BriefingExtras(private val env: BriefingEnv, private val budgetMs: Long) {

    // Never interrupted: the hazard and track reads touch H2 stores, which close on an interrupted read.
    private val pool = ThreadPoolExecutor(
        0, MAX_THREADS, 30L, TimeUnit.SECONDS, SynchronousQueue(),
    ) { r -> Thread(r, "TripBriefingExtra").apply { isDaemon = true } }

    private val tracks = object : LinkedHashMap<Long, List<LatLng>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<LatLng>>?): Boolean =
            size > MAX_TRACKS
    }

    private class Job(val future: Future<BriefingExtra?>?, val cutoffNs: Long)

    fun compute(offer: Offer, routine: Routine?, origin: LatLng, nowMs: Long, local: Boolean, online: Boolean): BriefingExtra? {
        val startNs = System.nanoTime()
        val deadlineNs = startNs + budgetMs * 1_000_000L
        // Range is local and quick; settling it first spares the network call when it wins anyway.
        if (local) await(job(startNs, RANGE_MS) { range(offer) }, deadlineNs)?.let { return it }
        val jobs = listOfNotNull(
            if (local) job(startNs, HAZARDS_MS) { hazards(offer, routine, nowMs) } else null,
            if (online) job(startNs, RAIN_MS) { rain(origin) } else null,
        )
        for (j in jobs) {
            val r = await(j, deadlineNs)
            if (r != null) return r
        }
        return null
    }

    private fun job(startNs: Long, capMs: Long, block: () -> BriefingExtra?): Job {
        val future = try {
            pool.submit(Callable {
                try {
                    block()
                } catch (t: Throwable) {
                    env.warn("extra failed: ${t.javaClass.simpleName}")
                    null
                }
            })
        } catch (t: Throwable) {
            null
        }
        return Job(future, startNs + min(capMs, budgetMs) * 1_000_000L)
    }

    private fun await(j: Job, deadlineNs: Long): BriefingExtra? {
        val f = j.future ?: return null
        val waitNs = min(j.cutoffNs, deadlineNs) - System.nanoTime()
        return try {
            if (waitNs > 0) f.get(waitNs, TimeUnit.NANOSECONDS) else if (f.isDone) f.get() else null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun range(offer: Offer): BriefingExtra? {
        val oneWay = offer.medianDistanceKm
        if (!(oneWay > 0.0)) return null
        return rangeExtra(env.rangeKm() ?: return null, oneWay)
    }

    private fun hazards(offer: Offer, routine: Routine?, nowMs: Long): BriefingExtra? {
        if (!env.roadSenseEnabled()) return null
        val tripId = offer.lastTripId.takeIf { it > 0 } ?: routine?.lastTripId?.takeIf { it > 0 } ?: return null
        val track = trackFor(tripId) ?: return null
        val box = BriefingGeo.box(track, HAZARD_NEAR_M) ?: return null
        val count = hazardCount(env.hazardsIn(box) ?: return null, track, nowMs)
        return if (count > 0) BriefingExtra.Hazards(count) else null
    }

    private fun rain(origin: LatLng): BriefingExtra? {
        val slots = env.rainSlots(origin) ?: return null
        return rainExtra(slots)
    }

    private fun trackFor(tripId: Long): List<LatLng>? {
        synchronized(tracks) { tracks[tripId] }?.let { return it }
        val raw = env.tripTrack(tripId) ?: return null
        val track = BriefingGeo.simplify(raw.filter { BriefingGeo.usable(it.lat, it.lng) }, TRACK_STEP_M)
        if (track.size < 2) return null
        synchronized(tracks) { tracks[tripId] = track }
        return track
    }

    companion object {
        private const val MAX_THREADS = 8
        private const val MAX_TRACKS = 8
        private const val RANGE_MS = 1_000L
        private const val HAZARDS_MS = 3_000L
        private const val RAIN_MS = 2_000L
        private const val TRACK_STEP_M = 20.0
        private const val HAZARD_NEAR_M = 30.0
        private const val HAZARD_MIN_SEVERITY = 2
        private const val HAZARD_MAX_AGE_MS = 14L * 86_400_000L
        private const val RAIN_MIN_PCT = 60
        private const val RAIN_HOURS = 6

        /** Under 1.5x the round trip. [oneWayKm] is the routine's median distance. */
        fun rangeExtra(rangeKm: Double, oneWayKm: Double): BriefingExtra.Range? =
            if (rangeKm.isFinite() && rangeKm >= 0.0 && rangeKm < 1.5 * 2.0 * oneWayKm) {
                BriefingExtra.Range(rangeKm.roundToInt(), oneWayKm.roundToInt())
            } else {
                null
            }

        /** Severity 2+, first seen in the last 14 days, within 30 m of the track. */
        fun hazardCount(hazards: List<HazardPoint>, track: List<LatLng>, nowMs: Long): Int =
            hazards.count {
                it.severity >= HAZARD_MIN_SEVERITY &&
                    it.firstSeenMs >= nowMs - HAZARD_MAX_AGE_MS &&
                    BriefingGeo.distanceToPolylineM(it.lat, it.lng, track) <= HAZARD_NEAR_M
            }

        /** Hour of the first of the next 6 hourly slots at 60 % or more. */
        fun rainExtra(slots: List<RainSlot>): BriefingExtra.Rain? =
            slots.take(RAIN_HOURS).firstOrNull { it.probabilityPct >= RAIN_MIN_PCT && it.hour in 0..23 }
                ?.let { BriefingExtra.Rain(it.hour) }
    }
}
