package com.overdrive.app.ui.timeline

/** Everything [TimelineStripView] draws for one window; times are epoch ms, rendered in [tzId]. */
data class StripData(
    val segments: List<TlSegment>,
    val marks: List<TlMark>,
    val sessions: List<TlSession>,
    val trips: List<TlTrip>,
    val states: List<TlState>,
    val reaped: List<TlReaped>,
    val road: List<TlRoad>,
    val live: List<TlLive>,
    val now: Long,
    val minMs: Long,
    val maxMs: Long,
    val tzId: String,
    val momentTimes: List<Long>,
)

/** Mark kinds in the marks row; codes index the icon and label tables. */
internal object MarkKind {
    const val PERSON = 0
    const val VEHICLE = 1
    const val BIKE = 2
    const val ANIMAL = 3
    const val MOTION = 4
    const val DETERRENT = 5
    const val POTHOLE = 6
    const val BREAKER = 7
    const val ROUGH = 8
    const val REPLAY = 9
    const val PROXIMITY = 10
    const val OTHER = 11
    const val COUNT = 12

    fun ofMark(kind: String): Int = when (kind) {
        "person" -> PERSON
        "vehicle", "car" -> VEHICLE
        "bike" -> BIKE
        "animal" -> ANIMAL
        "motion" -> MOTION
        "deterrent" -> DETERRENT
        else -> OTHER
    }

    fun ofRoad(kind: String): Int = when (kind) {
        "pothole" -> POTHOLE
        "breaker" -> BREAKER
        else -> ROUGH
    }

    /** Key matched against the kinds passed to setFilter. */
    fun filterKey(code: Int): String = when (code) {
        PERSON -> "person"
        VEHICLE -> "vehicle"
        BIKE -> "bike"
        ANIMAL -> "animal"
        MOTION -> "motion"
        DETERRENT -> "deterrent"
        POTHOLE, BREAKER, ROUGH -> "road"
        REPLAY -> "replay"
        PROXIMITY -> "proximity"
        else -> "event"
    }
}

/** Sorted, disjoint interval arithmetic on parallel start/end arrays (end exclusive). */
internal object Intervals {
    /** Sorts by start and merges pieces whose gap is <= tolerance; returns new compact arrays. */
    fun merge(starts: LongArray, ends: LongArray, n: Int, tolerance: Long): Pair<LongArray, LongArray> {
        if (n == 0) return Pair(LongArray(0), LongArray(0))
        val order = (0 until n).sortedBy { starts[it] }
        val os = LongArray(n)
        val oe = LongArray(n)
        var k = 0
        for (i in order) {
            val s = starts[i]
            val e = maxOf(ends[i], s)
            if (k > 0 && s <= oe[k - 1] + tolerance) {
                if (e > oe[k - 1]) oe[k - 1] = e
            } else {
                os[k] = s
                oe[k] = e
                k++
            }
        }
        return Pair(os.copyOf(k), oe.copyOf(k))
    }

    /** a minus b; both sorted and disjoint. */
    fun subtract(aS: LongArray, aE: LongArray, bS: LongArray, bE: LongArray): Pair<LongArray, LongArray> {
        val rs = ArrayList<Long>()
        val re = ArrayList<Long>()
        var j = 0
        for (i in aS.indices) {
            var s = aS[i]
            val e = aE[i]
            while (j < bS.size && bE[j] <= s) j++
            var k = j
            while (k < bS.size && bS[k] < e) {
                if (bS[k] > s) {
                    rs.add(s)
                    re.add(bS[k])
                }
                if (bE[k] > s) s = bE[k]
                if (s >= e) break
                k++
            }
            if (s < e) {
                rs.add(s)
                re.add(e)
            }
        }
        return Pair(rs.toLongArray(), re.toLongArray())
    }

    /** Pieces of [lo, hi) not covered by the sorted disjoint set, keeping only pieces >= minLen. */
    fun complement(s: LongArray, e: LongArray, lo: Long, hi: Long, minLen: Long): Pair<LongArray, LongArray> {
        val rs = ArrayList<Long>()
        val re = ArrayList<Long>()
        var cur = lo
        for (i in s.indices) {
            if (e[i] <= cur) continue
            if (s[i] >= hi) break
            if (s[i] > cur && s[i] - cur >= minLen) {
                rs.add(cur)
                re.add(minOf(s[i], hi))
            }
            if (e[i] > cur) cur = e[i]
            if (cur >= hi) break
        }
        if (cur < hi && hi - cur >= minLen) {
            rs.add(cur)
            re.add(hi)
        }
        return Pair(rs.toLongArray(), re.toLongArray())
    }

    fun clip(s: LongArray, e: LongArray, lo: Long, hi: Long): Pair<LongArray, LongArray> {
        val rs = ArrayList<Long>()
        val re = ArrayList<Long>()
        for (i in s.indices) {
            val a = maxOf(s[i], lo)
            val b = minOf(e[i], hi)
            if (b > a) {
                rs.add(a)
                re.add(b)
            }
        }
        return Pair(rs.toLongArray(), re.toLongArray())
    }
}

/** Immutable drawing model, rebuilt once per setData and never inside onDraw. */
internal class StripModel(
    val lower: Long,
    val upper: Long,
    /** Latest time the open end may extrapolate to; unbounded when the window ends at "now". */
    val endCap: Long,
    /** upper is the data "now" and keeps moving with the clock. */
    val openEnd: Boolean,
    val footStart: LongArray,
    val footEnd: LongArray,
    val footDrive: BooleanArray,
    val seams: LongArray,
    val offStart: LongArray,
    val offEnd: LongArray,
    val armStart: LongArray,
    val armEnd: LongArray,
    val gapStart: LongArray,
    val gapEnd: LongArray,
    val gapSource: IntArray,
    val gapIndex: IntArray,
    /** Long.MIN_VALUE when nothing is recording. */
    val liveSince: Long,
    val markTime: LongArray,
    val markKind: IntArray,
    val markAlert: BooleanArray,
    val markRec: Array<String?>,
    val moments: LongArray,
) {
    val markCount: Int get() = markTime.size

    companion object {
        const val SEAM_TOLERANCE_MS = 1_500L
        const val MIN_GAP_MS = 1_500L
        const val UNKNOWN_END_MS = 1_000L
        const val NOW_SLACK_MS = 1_000L

        val EMPTY = StripModel(
            0L, 0L, 0L, false, LongArray(0), LongArray(0), BooleanArray(0), LongArray(0),
            LongArray(0), LongArray(0), LongArray(0), LongArray(0), LongArray(0), LongArray(0),
            IntArray(0), IntArray(0), Long.MIN_VALUE, LongArray(0), IntArray(0), BooleanArray(0),
            arrayOfNulls(0), LongArray(0),
        )

        private fun isAlertSeverity(s: String?): Boolean = s == "ALERT" || s == "CRITICAL"

        private fun segEnd(s: TlSegment): Long {
            val e = s.end
            return if (e == null || e <= s.start) s.start + UNKNOWN_END_MS else e
        }

        fun build(d: StripData): StripModel {
            val lower = d.minMs
            val maxMs = maxOf(d.minMs, d.maxMs)
            val upper = maxOf(lower, minOf(maxMs, d.now))
            val openEnd = d.now in lower..maxMs
            val endCap = if (openEnd && maxMs - d.now <= NOW_SLACK_MS) Long.MAX_VALUE else maxMs
            val segs = d.segments.sortedBy { it.start }

            val fS = ArrayList<Long>()
            val fE = ArrayList<Long>()
            val fD = ArrayList<Boolean>()
            val seams = ArrayList<Long>()
            var runS = 0L
            var runE = 0L
            var runDrive = false
            var open = false
            for (seg in segs) {
                if (!seg.available) continue
                val s = seg.start
                val e = segEnd(seg)
                val drive = seg.type == "normal" || seg.type == "oemDashcam"
                if (open && s <= runE + SEAM_TOLERANCE_MS) {
                    if (s >= runE - SEAM_TOLERANCE_MS && s > runS) seams.add(s)
                    if (e > runE) runE = e
                    runDrive = runDrive || drive
                } else {
                    if (open) {
                        fS.add(runS); fE.add(runE); fD.add(runDrive)
                    }
                    runS = s
                    runE = e
                    runDrive = drive
                    open = true
                }
            }
            if (open) {
                fS.add(runS); fE.add(runE); fD.add(runDrive)
            }

            val oS = ArrayList<Long>()
            val oE = ArrayList<Long>()
            for (seg in segs) {
                if (seg.available) continue
                oS.add(seg.start); oE.add(segEnd(seg))
            }
            val off = Intervals.merge(oS.toLongArray(), oE.toLongArray(), oS.size, SEAM_TOLERANCE_MS)

            val rawArmS = ArrayList<Long>()
            val rawArmE = ArrayList<Long>()
            for (s in d.sessions) {
                if (s.sentry != "armed") continue
                rawArmS.add(s.start); rawArmE.add(s.end ?: d.now)
            }
            val armMerged = Intervals.merge(rawArmS.toLongArray(), rawArmE.toLongArray(), rawArmS.size, 0L)
            val armClipped = Intervals.clip(armMerged.first, armMerged.second, lower, upper)
            val reapS = LongArray(d.reaped.size) { d.reaped[it].start }
            val reapE = LongArray(d.reaped.size) { d.reaped[it].end }
            val reapMerged = Intervals.merge(reapS, reapE, reapS.size, 0L)
            val arm = Intervals.subtract(armClipped.first, armClipped.second, reapMerged.first, reapMerged.second)

            var liveSince = Long.MIN_VALUE
            for (l in d.live) {
                if (l.since < d.now && (liveSince == Long.MIN_VALUE || l.since < liveSince)) liveSince = l.since
            }
            if (liveSince != Long.MIN_VALUE) {
                if (liveSince < lower) liveSince = lower
                if (liveSince >= upper) liveSince = Long.MIN_VALUE
            }

            val cS = ArrayList<Long>(fS.size + off.first.size + arm.first.size + 1)
            val cE = ArrayList<Long>(cS.size)
            cS.addAll(fS); cE.addAll(fE)
            for (i in off.first.indices) { cS.add(off.first[i]); cE.add(off.second[i]) }
            for (i in arm.first.indices) { cS.add(arm.first[i]); cE.add(arm.second[i]) }
            if (liveSince != Long.MIN_VALUE) { cS.add(liveSince); cE.add(d.now) }
            val cover = Intervals.merge(cS.toLongArray(), cE.toLongArray(), cS.size, 0L)
            val gaps = Intervals.complement(cover.first, cover.second, lower, upper, MIN_GAP_MS)
            val gapSource = IntArray(gaps.first.size)
            val gapIndex = IntArray(gaps.first.size)
            GapReasons.classify(d, gaps.first, gaps.second, gaps.first.size, gapSource, gapIndex)

            val tmp = ArrayList<MarkTmp>(d.marks.size + d.road.size + 16)
            for (m in d.marks) tmp.add(MarkTmp(m.t0, MarkKind.ofMark(m.kind), m.alert, m.rec))
            for (r in d.road) tmp.add(MarkTmp(r.t, MarkKind.ofRoad(r.kind), false, null))
            for (s in segs) {
                when (s.type) {
                    "replay" -> tmp.add(MarkTmp(s.end ?: s.start, MarkKind.REPLAY, isAlertSeverity(s.peakSeverity), s.id))
                    "proximity" -> tmp.add(MarkTmp(s.start, MarkKind.PROXIMITY, isAlertSeverity(s.peakSeverity), s.id))
                }
            }
            tmp.sortBy { it.t }

            val moments = d.momentTimes.sorted().distinct().toLongArray()

            return StripModel(
                lower = lower,
                upper = upper,
                endCap = endCap,
                openEnd = openEnd,
                footStart = fS.toLongArray(),
                footEnd = fE.toLongArray(),
                footDrive = fD.toBooleanArray(),
                seams = seams.toLongArray(),
                offStart = off.first,
                offEnd = off.second,
                armStart = arm.first,
                armEnd = arm.second,
                gapStart = gaps.first,
                gapEnd = gaps.second,
                gapSource = gapSource,
                gapIndex = gapIndex,
                liveSince = liveSince,
                markTime = LongArray(tmp.size) { tmp[it].t },
                markKind = IntArray(tmp.size) { tmp[it].kind },
                markAlert = BooleanArray(tmp.size) { tmp[it].alert },
                markRec = Array(tmp.size) { tmp[it].rec },
                moments = moments,
            )
        }
    }

    private class MarkTmp(val t: Long, val kind: Int, val alert: Boolean, val rec: String?)
}
