package com.overdrive.app.briefing.engine

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.zone.ZoneRules
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal const val EARTH_R = 6371000.0
internal const val DAY_MS = 86_400_000L
internal const val MIN_PER_DAY = 1440

/** Great-circle distance in metres. */
internal fun haversineM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val p1 = Math.toRadians(aLat)
    val p2 = Math.toRadians(bLat)
    val dp = p2 - p1
    val dl = Math.toRadians(bLng - aLng)
    val s1 = sin(dp / 2)
    val s2 = sin(dl / 2)
    val h = s1 * s1 + cos(p1) * cos(p2) * (s2 * s2)
    return 2 * EARTH_R * asin(sqrt(min(1.0, h)))
}

/** False for NaN, out-of-range and the (0,0) "no fix" placeholder. */
internal fun usablePoint(lat: Double, lng: Double): Boolean =
    !lat.isNaN() && !lng.isNaN() && abs(lat) <= 90.0 && abs(lng) <= 180.0 &&
        !(abs(lat) < 1e-6 && abs(lng) < 1e-6)

internal fun dayTypeOf(index: Int): DayType = if (index == 1) DayType.WEEKEND else DayType.WEEKDAY

internal fun routineId(o: BriefPlace, d: BriefPlace, dayType: DayType): String =
    String.format(Locale.US, "%.5f,%.5f>%.5f,%.5f:%s", o.lat, o.lng, d.lat, d.lng, dayType.name)

/** Circular distance between two minutes of the day. */
internal fun minuteGap(a: Int, b: Int): Int {
    val d = abs(a - b)
    return if (d > MIN_PER_DAY / 2) MIN_PER_DAY - d else d
}

/** Local wall-clock fields of epoch-ms instants. */
internal class LocalClock(zone: ZoneId, weekend: Set<DayOfWeek> = DEFAULT_WEEKEND) {
    // index 0 = Monday
    private val weekendDay = BooleanArray(7) { DayOfWeek.of(it + 1) in weekend }
    private val rules: ZoneRules = zone.rules
    private val fixedOffsetS: Int? =
        if (rules.isFixedOffset) rules.getOffset(Instant.EPOCH).totalSeconds else null

    fun localSec(ms: Long): Long {
        val sec = Math.floorDiv(ms, 1000L)
        return sec + (fixedOffsetS ?: rules.getOffset(Instant.ofEpochSecond(sec)).totalSeconds)
    }

    fun minuteOfDay(ms: Long): Int = (Math.floorMod(localSec(ms), 86_400L) / 60L).toInt()

    /** 0 = Monday … 6 = Sunday. */
    fun dayOfWeek(ms: Long): Int = Math.floorMod(Math.floorDiv(localSec(ms), 86_400L) + 3L, 7L).toInt()

    /** 0 = WEEKDAY, 1 = WEEKEND, as [DayType.ordinal]. */
    fun dayTypeIndex(ms: Long): Int = if (weekendDay[dayOfWeek(ms)]) 1 else 0
}

/** Median of the finite values; 0 when there are none. Sorts [v] in place. */
internal fun medianOf(v: DoubleArray, size: Int = v.size): Double {
    var n = 0
    for (i in 0 until size) if (v[i].isFinite()) v[n++] = v[i]
    if (n == 0) return 0.0
    v.sort(0, n)
    return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2.0
}

/**
 * Median minute of the day on the clock face: unwraps the sample at its largest gap, then takes
 * the ordinary median. Sorts [mins] in place.
 */
internal fun circularMedianMinute(mins: IntArray): Int {
    val n = mins.size
    if (n == 0) return 0
    mins.sort()
    var start = 0
    var widest = mins[0] + MIN_PER_DAY - mins[n - 1]
    for (k in 1 until n) {
        val gap = mins[k] - mins[k - 1]
        if (gap > widest) {
            widest = gap
            start = k
        }
    }
    fun at(t: Int): Int = if (start + t < n) mins[start + t] else mins[start + t - n] + MIN_PER_DAY
    val m = if (n % 2 == 1) at(n / 2).toDouble() else (at(n / 2 - 1) + at(n / 2)) / 2.0
    return Math.floorMod(floor(m + 0.5).toInt(), MIN_PER_DAY)
}
