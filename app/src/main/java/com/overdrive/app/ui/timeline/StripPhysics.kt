package com.overdrive.app.ui.timeline

import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure strip math (no Android types) so it runs unchanged in JVM harnesses. */
object StripPhysics {
    const val DECELERATION_RATE = 0.998
    const val RUBBER_BAND = 0.55
    const val RESPONSE_S = 0.35
    const val FLICK_DAMPING = 0.8
    const val MIN_SPAN_MS = 2.0 * 60_000.0
    const val MAX_SPAN_MS = 24.0 * 3_600_000.0

    private val STEPS_MIN = intArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 180, 240)
    private const val FALLBACK_STEP_MIN = 360

    fun omegaFor(responseS: Double): Double = 2.0 * PI / responseS

    /** Distance a release velocity travels under exponential deceleration (same length unit as the velocity, per second). */
    fun project(velocityPerS: Double, rate: Double = DECELERATION_RATE): Double =
        velocityPerS / 1000.0 * rate / (1.0 - rate)

    fun rubberBand(overshoot: Double, dimension: Double, c: Double = RUBBER_BAND): Double {
        if (dimension <= 0.0) return 0.0
        return overshoot * dimension * c / (dimension + c * abs(overshoot))
    }

    /** Raw overshoot that [rubberBand] displays as [shown]; |shown| stays below [dimension]. */
    fun rubberBandInverse(shown: Double, dimension: Double, c: Double = RUBBER_BAND): Double {
        if (dimension <= 0.0) return 0.0
        val a = minOf(abs(shown), dimension * 0.999)
        val r = a * dimension / (c * (dimension - a))
        return if (shown < 0.0) -r else r
    }

    /** Rubber-banded position of [raw] against [lo, hi]; px converts value units to pixels. */
    fun elastic(raw: Double, lo: Double, hi: Double, pxPerUnit: Double, dimensionPx: Double): Double {
        if (pxPerUnit <= 0.0) return raw.coerceIn(lo, maxOf(lo, hi))
        return when {
            raw < lo -> lo - rubberBand((lo - raw) * pxPerUnit, dimensionPx) / pxPerUnit
            raw > hi -> hi + rubberBand((raw - hi) * pxPerUnit, dimensionPx) / pxPerUnit
            else -> raw
        }
    }

    fun elasticInverse(shown: Double, lo: Double, hi: Double, pxPerUnit: Double, dimensionPx: Double): Double {
        if (pxPerUnit <= 0.0) return shown
        return when {
            shown < lo -> lo - rubberBandInverse((lo - shown) * pxPerUnit, dimensionPx) / pxPerUnit
            shown > hi -> hi + rubberBandInverse((shown - hi) * pxPerUnit, dimensionPx) / pxPerUnit
            else -> shown
        }
    }

    fun pickStepMinutes(pxPerMinute: Double, minLabelPx: Double): Int {
        for (s in STEPS_MIN) if (s * pxPerMinute >= minLabelPx) return s
        return FALLBACK_STEP_MIN
    }

    /** Unlabelled ticks at 1/5..1/4 of the labelled step. */
    fun minorStepMs(stepMinutes: Int): Long = when (stepMinutes) {
        1 -> 15_000L
        2 -> 30_000L
        5 -> 60_000L
        10 -> 120_000L
        15 -> 180_000L
        30 -> 360_000L
        60 -> 900_000L
        120 -> 1_800_000L
        180 -> 2_700_000L
        240 -> 3_600_000L
        else -> stepMinutes * 15_000L
    }

    /** Stiffness that starts a flick with zero acceleration, so the strip never speeds up after release. */
    fun flickOmega(speedTowardTarget: Double, distance: Double, damping: Double, minOmega: Double, maxOmega: Double): Double {
        if (distance <= 1e-9 || speedTowardTarget <= 0.0) return maxOmega
        return (2.0 * damping * speedTowardTarget / distance).coerceIn(minOmega, maxOmega)
    }

    class FlingPlan {
        var projected = 0.0
        var target = 0.0
        var snapIndex = -1
    }

    /** Projects a release, snaps to the nearest [snapTimes] entry within [snapRadius] of the landing, clamps to [lo, hi]. */
    fun planFling(
        out: FlingPlan, pos: Double, velocityPerS: Double, snapTimes: LongArray, snapCount: Int,
        snapRadius: Double, lo: Double, hi: Double
    ): FlingPlan {
        val p = pos + project(velocityPerS)
        out.projected = p
        val i = nearestWithin(snapTimes, snapCount, p, snapRadius)
        out.snapIndex = i
        val t = if (i >= 0) snapTimes[i].toDouble() else p
        out.target = t.coerceIn(lo, maxOf(lo, hi))
        if (i >= 0 && out.target != snapTimes[i].toDouble()) out.snapIndex = -1
        return out
    }

    /** First index whose value is >= t (ascending array). */
    fun lowerBound(a: LongArray, n: Int, t: Double): Int {
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun lowerBound(a: DoubleArray, n: Int, t: Double): Int {
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Index of the entry nearest to t within maxDist (ties prefer the earlier entry), or -1. */
    fun nearestWithin(a: LongArray, n: Int, t: Double, maxDist: Double): Int {
        if (n <= 0) return -1
        val i = lowerBound(a, n, t)
        var best = -1
        var bestD = maxDist
        if (i > 0) {
            val d = t - a[i - 1]
            if (d <= bestD) { best = i - 1; bestD = d }
        }
        if (i < n) {
            val d = a[i] - t
            if (d < bestD || (best < 0 && d <= bestD)) best = i
        }
        return best
    }

    /**
     * Greedy left-to-right clusters with a running-mean centre: an entry closer than [gap] to the current
     * centre joins it. [times] ascending; returns the cluster count written to the out arrays.
     */
    fun cluster(times: LongArray, n: Int, gap: Double, outStart: IntArray, outCount: IntArray, outCenter: DoubleArray): Int {
        var k = 0
        for (i in 0 until n) {
            val t = times[i].toDouble()
            if (k > 0 && t - outCenter[k - 1] < gap) {
                val c = outCount[k - 1] + 1
                outCenter[k - 1] += (t - outCenter[k - 1]) / c
                outCount[k - 1] = c
            } else {
                outStart[k] = i
                outCount[k] = 1
                outCenter[k] = t
                k++
            }
        }
        return k
    }

    /**
     * Ticks every [minorMs] of local wall time in [tz] across [t0, t1]; majors fall on multiples of [stepMs].
     * Local times that do not exist (DST gaps) are skipped. Returns the count, bounded by the buffers.
     */
    fun rulerTicks(
        tz: TimeZone, t0: Double, t1: Double, stepMs: Long, minorMs: Long,
        outUtc: LongArray, outLocal: LongArray, outMajor: BooleanArray
    ): Int {
        val start = floor(t0).toLong()
        val off0 = tz.getOffset(start).toLong()
        var local = Math.floorDiv(start + off0, minorMs) * minorMs
        var n = 0
        var guard = 0
        while (n < outUtc.size && guard < outUtc.size * 4) {
            guard++
            val a = local - off0
            val offA = tz.getOffset(a).toLong()
            var utc = a
            if (offA != off0) {
                val b = local - offA
                if (tz.getOffset(b).toLong() != offA) {
                    if (a > t1) break
                    local += minorMs
                    continue
                }
                utc = b
            }
            if (utc > t1) break
            outUtc[n] = utc
            outLocal[n] = local
            outMajor[n] = Math.floorMod(local, stepMs) == 0L
            n++
            local += minorMs
        }
        return n
    }

    /** HH:mm:ss of wall time [t] in [tz] into buf[0, 8). */
    fun formatHms(tz: TimeZone, t: Long, buf: CharArray) {
        val day = Math.floorMod(t + tz.getOffset(t), 86_400_000L)
        val h = (day / 3_600_000L).toInt()
        val m = ((day / 60_000L) % 60L).toInt()
        val s = ((day / 1000L) % 60L).toInt()
        buf[0] = '0' + h / 10
        buf[1] = '0' + h % 10
        buf[2] = ':'
        buf[3] = '0' + m / 10
        buf[4] = '0' + m % 10
        buf[5] = ':'
        buf[6] = '0' + s / 10
        buf[7] = '0' + s % 10
    }

    /** HH:mm of a local wall-clock instant into buf[0, 5). */
    fun formatHm(local: Long, buf: CharArray) {
        val day = Math.floorMod(local, 86_400_000L)
        val h = (day / 3_600_000L).toInt()
        val m = ((day / 60_000L) % 60L).toInt()
        buf[0] = '0' + h / 10
        buf[1] = '0' + h % 10
        buf[2] = ':'
        buf[3] = '0' + m / 10
        buf[4] = '0' + m % 10
    }

    const val PENDING_TOLERANCE_MS = 1_500.0
    const val PENDING_TIMEOUT_S = 1.5
    const val EXTRAPOLATE_CAP_S = 1.2

    /**
     * Player position reports, extrapolated while playing. While a position the user chose awaits the
     * player, reports that contradict it are refused until it is confirmed or [PENDING_TIMEOUT_S] passes.
     */
    class Follow {
        var wall = Double.NaN
            private set
        var reportNs = 0L
            private set
        var playing = false
            private set
        var speed = 1
            private set
        var pendingWall = Double.NaN
            private set
        var pendingNs = 0L
            private set

        val hasReport: Boolean get() = !wall.isNaN()
        val isPending: Boolean get() = !pendingWall.isNaN()

        fun expect(wallMs: Double, ns: Long) {
            pendingWall = wallMs
            pendingNs = ns
        }

        fun clearPending() {
            pendingWall = Double.NaN
        }

        /** Stores the report; false while it contradicts a pending position that has not timed out. */
        fun report(wallMs: Long, playing: Boolean, speed: Int, ns: Long): Boolean {
            wall = wallMs.toDouble()
            reportNs = ns
            this.playing = playing
            this.speed = speed.coerceIn(1, 64)
            if (pendingWall.isNaN()) return true
            val waited = (ns - pendingNs) / 1e9
            val expected = pendingWall + if (playing) waited * 1000.0 * this.speed else 0.0
            val tol = PENDING_TOLERANCE_MS + 500.0 * this.speed
            if (abs(wall - expected) <= tol || abs(wall - pendingWall) <= tol || waited >= PENDING_TIMEOUT_S) {
                pendingWall = Double.NaN
                return true
            }
            return false
        }

        /** True once, when a pending position times out; the stored report is dropped as stale. */
        fun expire(ns: Long): Boolean {
            if (pendingWall.isNaN() || (ns - pendingNs) / 1e9 < PENDING_TIMEOUT_S) return false
            pendingWall = Double.NaN
            wall = Double.NaN
            return true
        }

        fun pendingLeftMs(ns: Long): Double = (PENDING_TIMEOUT_S - (ns - pendingNs) / 1e9) * 1000.0

        fun at(ns: Long, fallback: Double): Double {
            if (wall.isNaN()) return fallback
            if (!playing) return wall
            return wall + ((ns - reportNs) / 1e9).coerceIn(0.0, EXTRAPOLATE_CAP_S) * 1000.0 * speed
        }

        fun extrapolating(ns: Long): Boolean = playing && !wall.isNaN() && (ns - reportNs) / 1e9 < EXTRAPOLATE_CAP_S
    }

    /** Exponential approach of current toward target with time constant tau seconds. */
    fun approach(current: Double, target: Double, dtS: Double, tauS: Double): Double {
        if (tauS <= 0.0 || dtS <= 0.0) return if (tauS <= 0.0) target else current
        return target + (current - target) * exp(-dtS / tauS)
    }

    /** Damped harmonic oscillator advanced in closed form, so long or uneven frames stay exact and stable. */
    class Spring {
        var value = 0.0
        var velocity = 0.0
        var target = 0.0
        var omega = omegaFor(RESPONSE_S)
        var damping = 1.0
        var active = false

        fun start(from: Double, v0: Double, to: Double, omega: Double, damping: Double) {
            value = from
            velocity = v0
            target = to
            this.omega = omega
            this.damping = damping
            active = true
        }

        fun stop() {
            active = false
            velocity = 0.0
        }

        fun step(dtS: Double) {
            if (!active || dtS <= 0.0) return
            val u0 = value - target
            val v0 = velocity
            val w = omega
            val z = damping
            val u: Double
            val v: Double
            if (z < 0.999) {
                val wd = w * sqrt(1.0 - z * z)
                val e = exp(-z * w * dtS)
                val c = cos(wd * dtS)
                val s = sin(wd * dtS)
                val b = (v0 + z * w * u0) / wd
                u = e * (u0 * c + b * s)
                v = e * ((b * wd - z * w * u0) * c - (u0 * wd + z * w * b) * s)
            } else if (z <= 1.001) {
                val e = exp(-w * dtS)
                val b = v0 + w * u0
                u = (u0 + b * dtS) * e
                v = (v0 - w * b * dtS) * e
            } else {
                val r = sqrt(z * z - 1.0)
                val r1 = -w * (z - r)
                val r2 = -w * (z + r)
                val c2 = (v0 - r1 * u0) / (r2 - r1)
                val c1 = u0 - c2
                val e1 = exp(r1 * dtS)
                val e2 = exp(r2 * dtS)
                u = c1 * e1 + c2 * e2
                v = c1 * r1 * e1 + c2 * r2 * e2
            }
            value = target + u
            velocity = v
        }

        fun settled(posEps: Double, velEps: Double): Boolean =
            abs(value - target) < posEps && abs(velocity) < velEps
    }
}
