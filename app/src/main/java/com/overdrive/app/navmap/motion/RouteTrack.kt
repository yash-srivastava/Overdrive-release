package com.overdrive.app.navmap.motion

import com.overdrive.app.navmap.nav.NavRoute
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Arc-length view of a route polyline. Distances are haversine sums over consecutive points, the
 * same measure [com.overdrive.app.navmap.nav.NavGuidanceEngine] reports as `alongRouteM`.
 */
class RouteTrack private constructor(val route: NavRoute) {

    private val lat = DoubleArray(route.points.size) { route.points[it].lat }
    private val lng = DoubleArray(route.points.size) { route.points[it].lng }
    private val cum = DoubleArray(route.points.size)
    /** Along-route positions of the maneuvers worth framing (turns, exits, roundabouts, arrival). */
    private val keyS: DoubleArray
    private var seg = 0
    private var keyBoundary = 0

    val length: Double

    init {
        for (i in 1 until lat.size) cum[i] = cum[i - 1] + haversine(lat[i - 1], lng[i - 1], lat[i], lng[i])
        length = if (cum.isEmpty()) 0.0 else cum[cum.size - 1]
        val ks = ArrayList<Double>()
        for (m in route.maneuvers) {
            if (!framed(m.type)) continue
            val i = m.beginShapeIndex
            if (i in cum.indices) ks.add(cum[i])
        }
        keyS = ks.sorted().toDoubleArray()
    }

    /** Writes the point at along-route distance [s] into [out] as (lat, lng). */
    fun pointAt(s: Double, out: DoubleArray) {
        val n = lat.size
        if (n == 1) {
            out[0] = lat[0]; out[1] = lng[0]; return
        }
        val c = s.coerceIn(0.0, length)
        val i = segmentFor(c)
        val segLen = cum[i + 1] - cum[i]
        val t = if (segLen > 0.0) (c - cum[i]) / segLen else 0.0
        out[0] = lat[i] + (lat[i + 1] - lat[i]) * t
        out[1] = lng[i] + (lng[i + 1] - lng[i]) * t
    }

    /** Bearing of the chord from s − [back] to s + [fwd]: continuous in s, so it rounds polyline corners. */
    fun chordBearing(s: Double, back: Double, fwd: Double): Double {
        var a = (s - back).coerceIn(0.0, length)
        var b = (s + fwd).coerceIn(0.0, length)
        if (b - a < 1.0) {
            a = max(0.0, b - 1.0)
            b = min(length, a + 1.0)
        }
        pointAt(a, pa)
        pointAt(b, pb)
        return bearing(pa[0], pa[1], pb[0], pb[1])
    }

    /** Along-route distance of route point [index] (clamped to the route). */
    fun sAtIndex(index: Int): Double = cum[index.coerceIn(0, cum.size - 1)]

    /** Distance from [s] to the next framed maneuver ahead, or +∞. */
    fun distanceToNextKey(s: Double): Double {
        val limit = s + 0.5
        val i = keyBoundaryFor(limit)
        // NaN keys sort last but never satisfy the original strict comparison.
        return if (i < keyS.size && keyS[i] > limit) keyS[i] - s else Double.POSITIVE_INFINITY
    }

    /** Distance travelled since the last framed maneuver at or behind [s], or +∞. */
    fun distanceSinceLastKey(s: Double): Double {
        val i = keyBoundaryFor(s + 0.5)
        return if (i > 0) max(0.0, s - keyS[i - 1]) else Double.POSITIVE_INFINITY
    }

    /** First key not <= limit. The camera usually stays between the same two keys for many frames. */
    private fun keyBoundaryFor(limit: Double): Int {
        val i = keyBoundary
        if ((i == 0 || keyS[i - 1] <= limit) &&
            (i == keyS.size || !(keyS[i] <= limit))) return i

        // The sorted array puts NaNs last; <= is false for those and for a NaN limit.
        var lo = 0
        var hi = keyS.size
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            if (keyS[mid] <= limit) lo = mid + 1 else hi = mid
        }
        keyBoundary = lo
        return lo
    }

    /**
     * Nearest point of the track to (lat, lng) within [window] metres of [hintS]: along-route distance
     * and signed lateral offset (positive = right of travel) written to [out].
     */
    fun project(pLat: Double, pLng: Double, hintS: Double, window: Double, out: DoubleArray) {
        val n = lat.size
        if (n < 2) {
            out[0] = 0.0; out[1] = 0.0; return
        }
        val lo = segmentFor((hintS - window).coerceIn(0.0, length))
        val hi = segmentFor((hintS + window).coerceIn(0.0, length))
        val kx = M_PER_DEG * cos(Math.toRadians(pLat))
        var bestD2 = Double.MAX_VALUE
        var bestS = hintS
        var bestSide = 0.0
        for (i in lo..hi) {
            val ax = (lng[i] - pLng) * kx
            val ay = (lat[i] - pLat) * M_PER_DEG
            val vx = (lng[i + 1] - pLng) * kx - ax
            val vy = (lat[i + 1] - pLat) * M_PER_DEG - ay
            val len2 = vx * vx + vy * vy
            val t = if (len2 > 0.0) (-(ax * vx + ay * vy) / len2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + vx * t
            val py = ay + vy * t
            val d2 = px * px + py * py
            if (d2 < bestD2) {
                bestD2 = d2
                bestS = cum[i] + (cum[i + 1] - cum[i]) * t
                // Cross product sign of the travel vector against (segment point → p): p on the right ⇒ +.
                bestSide = if (vx * (-py) - vy * (-px) < 0.0) 1.0 else -1.0
            }
        }
        out[0] = bestS
        out[1] = sqrt(bestD2) * bestSide
    }

    private fun segmentFor(s: Double): Int {
        val last = lat.size - 2
        var i = seg.coerceIn(0, last)
        if (s >= cum[i] && s <= cum[i + 1]) return i
        if (i < last && s > cum[i + 1] && s <= cum[i + 2]) {
            seg = i + 1
            return seg
        }
        var lo = 0
        var hi = last
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cum[mid] <= s) lo = mid else hi = mid - 1
        }
        i = lo.coerceIn(0, last)
        seg = i
        return i
    }

    private val pa = DoubleArray(2)
    private val pb = DoubleArray(2)

    companion object {
        private const val EARTH_R = 6_371_000.0
        private const val M_PER_DEG = EARTH_R * PI / 180.0

        /** Null for routes too short to follow. */
        fun of(route: NavRoute?): RouteTrack? =
            if (route == null || route.points.size < 2) null else RouteTrack(route)

        // Turns, keeps, exits, roundabouts and arrival; not start / continue / name changes.
        private fun framed(type: Int): Boolean = when (type) {
            0, 1, 2, 3, 7, 8, 22 -> false
            else -> true
        }

        fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
            return EARTH_R * 2 * atan2(sqrt(h), sqrt(1 - h))
        }

        fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lng2 - lng1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        /** Moves (lat, lng) by [east]/[north] metres; flat-earth, fine for offsets of a few hundred metres. */
        fun offset(lat: Double, lng: Double, east: Double, north: Double, out: DoubleArray) {
            out[0] = lat + north / M_PER_DEG
            out[1] = lng + east / (M_PER_DEG * max(cos(Math.toRadians(lat)), 0.01))
        }

        /** Point [dist] metres from (lat, lng) along [bearingDeg] (great circle). */
        fun destination(lat: Double, lng: Double, bearingDeg: Double, dist: Double, out: DoubleArray) {
            val d = dist / EARTH_R
            val br = Math.toRadians(bearingDeg)
            val p1 = Math.toRadians(lat)
            val l1 = Math.toRadians(lng)
            val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(br))
            val l2 = l1 + atan2(sin(br) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
            out[0] = Math.toDegrees(p2)
            out[1] = Math.toDegrees(l2)
        }
    }
}
