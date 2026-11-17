package com.overdrive.app.briefing.daemon

import com.overdrive.app.briefing.engine.DayType
import com.overdrive.app.briefing.engine.RoutineKey
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class LatLng(val lat: Double, val lng: Double)

internal data class GeoBox(val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double)

internal object BriefingGeo {

    private const val EARTH_R = 6_371_000.0
    private const val M_PER_DEG = EARTH_R * PI / 180.0

    /** False for NaN, out-of-range and the (0,0) "no fix" placeholder. */
    fun usable(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && abs(lat) <= 90.0 && abs(lng) <= 180.0 &&
            !(abs(lat) < 1e-6 && abs(lng) < 1e-6)

    fun distanceM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val s1 = sin((p2 - p1) / 2)
        val s2 = sin(Math.toRadians(bLng - aLng) / 2)
        val h = s1 * s1 + cos(p1) * cos(p2) * s2 * s2
        return 2 * EARTH_R * asin(sqrt(min(1.0, h)))
    }

    /** Shortest distance in metres from the point to the polyline, on a flat projection around the point. */
    fun distanceToPolylineM(lat: Double, lng: Double, line: List<LatLng>): Double {
        if (line.isEmpty()) return Double.MAX_VALUE
        val kx = cos(Math.toRadians(lat)) * M_PER_DEG
        var ax = (line[0].lng - lng) * kx
        var ay = (line[0].lat - lat) * M_PER_DEG
        var best = hypot(ax, ay)
        for (i in 1 until line.size) {
            val bx = (line[i].lng - lng) * kx
            val by = (line[i].lat - lat) * M_PER_DEG
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0.0) ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < best) best = d
            ax = bx
            ay = by
        }
        return best
    }

    /** Keeps both ends and every point at least [minStepM] from the previous kept one. */
    fun simplify(points: List<LatLng>, minStepM: Double): List<LatLng> {
        if (points.size <= 2) return points
        val out = ArrayList<LatLng>()
        var last = points[0]
        out.add(last)
        for (i in 1 until points.size - 1) {
            val p = points[i]
            if (distanceM(last.lat, last.lng, p.lat, p.lng) >= minStepM) {
                out.add(p)
                last = p
            }
        }
        out.add(points[points.size - 1])
        return out
    }

    fun box(points: List<LatLng>, padM: Double): GeoBox? {
        if (points.isEmpty()) return null
        var minLat = 90.0
        var maxLat = -90.0
        var minLng = 180.0
        var maxLng = -180.0
        for (p in points) {
            minLat = min(minLat, p.lat)
            maxLat = max(maxLat, p.lat)
            minLng = min(minLng, p.lng)
            maxLng = max(maxLng, p.lng)
        }
        val padLat = padM / M_PER_DEG
        val padLng = padM / (M_PER_DEG * max(0.01, cos(Math.toRadians(max(abs(minLat), abs(maxLat))))))
        return GeoBox(minLat - padLat, minLng - padLng, maxLat + padLat, maxLng + padLng)
    }

    /** The key encoded in an engine routine id, `oLat,oLng>dLat,dLng:DAYTYPE`; null when malformed. */
    fun parseRoutineId(id: String): RoutineKey? {
        val colon = id.lastIndexOf(':')
        if (colon <= 0) return null
        val dayType = DayType.entries.firstOrNull { it.name == id.substring(colon + 1) } ?: return null
        val ends = id.substring(0, colon).split('>')
        if (ends.size != 2) return null
        val o = ends[0].split(',')
        val d = ends[1].split(',')
        if (o.size != 2 || d.size != 2) return null
        val v = (o + d).map { it.trim().toDoubleOrNull() ?: return null }
        if (!usable(v[0], v[1]) || !usable(v[2], v[3])) return null
        return RoutineKey(v[0], v[1], v[2], v[3], dayType)
    }

    fun sameRoutine(a: RoutineKey, b: RoutineKey, radiusM: Double): Boolean =
        a.dayType == b.dayType &&
            distanceM(a.oLat, a.oLng, b.oLat, b.oLng) <= radiusM &&
            distanceM(a.dLat, a.dLng, b.dLat, b.dLng) <= radiusM
}
