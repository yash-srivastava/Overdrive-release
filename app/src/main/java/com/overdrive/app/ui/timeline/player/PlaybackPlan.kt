package com.overdrive.app.ui.timeline.player

import com.overdrive.app.ui.timeline.TlMark
import com.overdrive.app.ui.timeline.TlRoad
import com.overdrive.app.ui.timeline.TlSegment
import java.util.TreeSet

/** Wall range [playFrom, playTo) of the timeline that is shown from [seg]. */
data class PlanItem(val seg: TlSegment, val playFrom: Long, val playTo: Long) {
    val durationMs: Long get() = playTo - playFrom
    val family: String get() = PlaybackPlan.familyOf(seg)

    /** Same file and same slice; stable across plan rebuilds. */
    fun sameSlice(o: PlanItem?): Boolean =
        o != null && o.seg.id == seg.id && o.seg.fp == seg.fp && o.playFrom == playFrom && o.playTo == playTo
}

/** Decoder configuration identity: items with equal configs share one running decoder. */
class CodecConfig(val mime: String, val width: Int, val height: Int, csd: List<ByteArray>) {
    private val csd: List<ByteArray> = csd.map { it.copyOf() }

    fun sameAs(o: CodecConfig?): Boolean {
        if (o == null || o.mime != mime || o.width != width || o.height != height) return false
        if (o.csd.size != csd.size) return false
        for (i in csd.indices) if (!csd[i].contentEquals(o.csd[i])) return false
        return true
    }

    override fun toString(): String = "$mime ${width}x$height csd=${csd.size}"
}

/**
 * Playback plan per spec 3.4, the same rule as the daemon's TimelinePlan: per wall instant the
 * best-ranked clip wins (rank, then earlier start, then id); an earlier clip of the same family that
 * ends within 1.5 s after the winner starts plays to its end; cross-family slivers are dropped.
 */
class PlaybackPlan private constructor(
    val items: List<PlanItem>,
    private val clips: List<TlSegment>,
    val prefer: String?,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** Index of the item covering [wall], or -1. */
    fun indexAt(wall: Long): Int {
        val i = lastStartingAtOrBefore(wall)
        return if (i >= 0 && wall < items[i].playTo) i else -1
    }

    fun itemAt(wall: Long): PlanItem? = indexAt(wall).let { if (it >= 0) items[it] else null }

    /** Item covering [wall], else the first item after it, else -1. */
    fun indexFrom(wall: Long): Int {
        val i = lastStartingAtOrBefore(wall)
        if (i >= 0 && wall < items[i].playTo) return i
        return if (i + 1 < items.size) i + 1 else -1
    }

    /** First item that starts after [after]. */
    fun next(after: Long): PlanItem? {
        val i = lastStartingAtOrBefore(after) + 1
        return if (i < items.size) items[i] else null
    }

    /** Last item that ends at or before [before]. */
    fun prev(before: Long): PlanItem? {
        var lo = 0
        var hi = items.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].playTo <= before) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (ans >= 0) items[ans] else null
    }

    fun indexOf(item: PlanItem): Int {
        val i = lastStartingAtOrBefore(item.playFrom)
        return if (i >= 0 && items[i].sameSlice(item)) i else -1
    }

    fun nextOf(item: PlanItem): PlanItem? {
        val i = indexOf(item)
        return if (i >= 0 && i + 1 < items.size) items[i + 1] else null
    }

    /** Families with playable footage at [wall], in display order. */
    fun familiesAt(wall: Long): List<String> {
        var mosaic = false
        var oem = false
        var i = lastClipStartingAtOrBefore(wall)
        while (i >= 0) {
            val c = clips[i]
            if (c.start < wall - MAX_CLIP_MS) break
            if (wall < (c.end ?: c.start)) {
                if (familyOf(c) == FAMILY_OEM) oem = true else mosaic = true
            }
            i--
        }
        return when {
            mosaic && oem -> listOf(FAMILY_MOSAIC, FAMILY_OEM)
            mosaic -> listOf(FAMILY_MOSAIC)
            oem -> listOf(FAMILY_OEM)
            else -> emptyList()
        }
    }

    private fun lastStartingAtOrBefore(wall: Long): Int {
        var lo = 0
        var hi = items.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].playFrom <= wall) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun lastClipStartingAtOrBefore(wall: Long): Int {
        var lo = 0
        var hi = clips.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (clips[mid].start <= wall) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    companion object {
        const val SEAM_MS = 1500L
        const val FAMILY_MOSAIC = "MOSAIC"
        const val FAMILY_OEM = "OEM"
        private const val MAX_CLIP_MS = 6L * 3_600_000L

        val EMPTY = PlaybackPlan(emptyList(), emptyList(), null)

        /** OEM single-camera footage is one family whatever type the index gives it. */
        fun familyOf(seg: TlSegment): String =
            if (seg.type == "oemDashcam" || seg.layout == "single") FAMILY_OEM else FAMILY_MOSAIC

        /** Daemon family key used for ranking: "oemDashcam" for OEM footage, else the type. */
        fun rankFamily(seg: TlSegment): String =
            if (seg.type == "oemDashcam" || seg.layout == "single") "oemDashcam" else seg.type

        /** "OEM" maps to "oemDashcam", "MOSAIC"/blank to none; other values match a type as the daemon does. */
        fun normalizePrefer(prefer: String?): String? {
            val p = prefer?.trim() ?: return null
            if (p.isEmpty() || p.equals(FAMILY_MOSAIC, true)) return null
            if (p.equals(FAMILY_OEM, true) || p.equals("oemDashcam", true)) return "oemDashcam"
            return p
        }

        fun rank(seg: TlSegment, prefer: String?): Int {
            val family = rankFamily(seg)
            if (prefer != null && prefer == family) return -1
            return when (family) {
                "normal" -> 0
                "sentry" -> 1
                "proximity" -> 2
                "oemDashcam" -> 3
                "replay" -> 4
                else -> 5
            }
        }

        fun playable(s: TlSegment): Boolean {
            val end = s.end ?: return false
            return s.available && end > s.start
        }

        fun contiguous(prevEnd: Long, nextStart: Long): Boolean = nextStart - prevEnd in -SEAM_MS..SEAM_MS

        fun contiguous(prev: PlanItem, next: PlanItem): Boolean = contiguous(prev.playTo, next.playFrom)

        fun compatible(a: CodecConfig?, b: CodecConfig?): Boolean = a != null && a.sameAs(b)

        fun build(segments: List<TlSegment>, prefer: String?): PlaybackPlan {
            val pref = normalizePrefer(prefer)
            val seen = HashSet<String>()
            val clips = ArrayList<TlSegment>()
            for (s in segments) if (playable(s) && seen.add(s.id)) clips.add(s)
            clips.sortWith(compareBy { it.start })
            if (clips.isEmpty()) return PlaybackPlan(emptyList(), emptyList(), pref)
            val ranks = IntArray(clips.size) { rank(clips[it], pref) }
            val ends = LongArray(clips.size) { clips[it].end ?: clips[it].start }
            val points = TreeSet<Long>()
            for (i in clips.indices) {
                points.add(clips[i].start)
                points.add(ends[i])
            }
            val out = ArrayList<PlanItem>()
            val active = ArrayList<Int>()
            var next = 0
            var current = -1
            var curFrom = 0L
            var curTo = 0L
            var previous: Long? = null
            for (point in points) {
                val a = previous
                if (a != null) {
                    while (next < clips.size && clips[next].start <= a) active.add(next++)
                    active.removeAll { ends[it] <= a }
                    val w = winner(clips, ends, ranks, active)
                    if (w < 0) {
                        if (current >= 0) out.add(PlanItem(clips[current], curFrom, curTo))
                        current = -1
                    } else if (w == current && curTo == a) {
                        curTo = point
                    } else {
                        if (current >= 0) out.add(PlanItem(clips[current], curFrom, curTo))
                        current = w
                        curFrom = a
                        curTo = point
                    }
                }
                previous = point
            }
            if (current >= 0) out.add(PlanItem(clips[current], curFrom, curTo))
            return PlaybackPlan(dropSlivers(out), clips, pref)
        }

        fun oem(seg: TlSegment): Boolean = familyOf(seg) == FAMILY_OEM

        /** A piece under SEAM_MS of the other family than both neighbours (within SEAM_MS) is a flicker: dropped. */
        fun dropSlivers(input: List<PlanItem>): List<PlanItem> {
            if (input.size < 3) return input
            val out = ArrayList<PlanItem>(input.size)
            for (i in input.indices) {
                val p = input[i]
                if (i > 0 && i < input.size - 1 && p.playTo - p.playFrom < SEAM_MS) {
                    val before = out.lastOrNull()
                    val after = input[i + 1]
                    if (before != null && p.playFrom - before.playTo <= SEAM_MS &&
                        after.playFrom - p.playTo <= SEAM_MS &&
                        oem(p.seg) != oem(before.seg) && oem(p.seg) != oem(after.seg)
                    ) {
                        continue
                    }
                }
                out.add(p)
            }
            return out
        }

        private fun better(clips: List<TlSegment>, ranks: IntArray, a: Int, b: Int): Boolean {
            if (ranks[a] != ranks[b]) return ranks[a] < ranks[b]
            val ca = clips[a]
            val cb = clips[b]
            if (ca.start != cb.start) return ca.start < cb.start
            return ca.id < cb.id
        }

        private fun winner(clips: List<TlSegment>, ends: LongArray, ranks: IntArray, active: List<Int>): Int {
            var best = -1
            for (c in active) if (best < 0 || better(clips, ranks, c, best)) best = c
            if (best < 0) return -1
            val bestStart = clips[best].start
            var seam = -1
            for (c in active) {
                if (c == best) continue
                val cs = clips[c].start
                val ce = ends[c]
                if (cs < bestStart && ce > bestStart && ce - bestStart <= SEAM_MS &&
                    oem(clips[c]) == oem(clips[best]) &&
                    (seam < 0 || better(clips, ranks, c, seam))
                ) {
                    seam = c
                }
            }
            return if (seam >= 0) seam else best
        }
    }
}

/** Wall spans within [PAD_MS] of a mark, road event or moment clip; "Skip quiet driving" plays the rest at 8x. */
class QuietZones private constructor(private val starts: LongArray, private val ends: LongArray) {

    /** True when no interesting moment lies within PAD_MS of any instant in [a, b]. */
    fun quietSpan(a: Long, b: Long): Boolean {
        var lo = 0
        var hi = ends.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ends[mid] < a) lo = mid + 1 else hi = mid
        }
        return lo >= starts.size || starts[lo] > b
    }

    companion object {
        const val PAD_MS = 10_000L
        val NONE = QuietZones(LongArray(0), LongArray(0))

        fun quietType(type: String): Boolean = type == "normal" || type == "oemDashcam"

        fun momentType(type: String): Boolean = type == "sentry" || type == "proximity" || type == "replay"

        fun build(segments: List<TlSegment>, marks: List<TlMark>, road: List<TlRoad>): QuietZones {
            val iv = ArrayList<LongArray>(marks.size + road.size + 16)
            for (m in marks) iv.add(longArrayOf(minOf(m.t0, m.t1) - PAD_MS, maxOf(m.t0, m.t1) + PAD_MS))
            for (r in road) iv.add(longArrayOf(r.t - PAD_MS, r.t + PAD_MS))
            for (s in segments) {
                if (momentType(s.type)) iv.add(longArrayOf(s.start - PAD_MS, (s.end ?: (s.start + 1000L)) + PAD_MS))
            }
            if (iv.isEmpty()) return NONE
            iv.sortBy { it[0] }
            val st = ArrayList<Long>()
            val en = ArrayList<Long>()
            for (x in iv) {
                if (st.isNotEmpty() && x[0] <= en[en.size - 1]) {
                    if (x[1] > en[en.size - 1]) en[en.size - 1] = x[1]
                } else {
                    st.add(x[0])
                    en.add(x[1])
                }
            }
            return QuietZones(st.toLongArray(), en.toLongArray())
        }
    }
}
