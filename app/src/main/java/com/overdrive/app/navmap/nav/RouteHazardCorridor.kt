package com.overdrive.app.navmap.nav

import kotlin.math.abs
import kotlin.math.cos

/**
 * Coarse index for route-preview hazard counts. Each block bounds a contiguous run of
 * segments, padded conservatively in the same local-meter projection as
 * [NavGuidanceEngine.pointToSegmentMeters]. Only surviving segments need an exact test.
 * The route points must remain unchanged for the lifetime of this index.
 */
internal class RouteHazardCorridor(
    private val points: List<GeoPoint>,
    private val radiusM: Double
) {
    private val segmentCount = (points.size - 1).coerceAtLeast(0)
    // Short routes keep the linear scan. For longer routes, four doubles per block.
    private val bounds = if (segmentCount <= BLOCK_SEGMENTS) DoubleArray(0)
        else DoubleArray(((segmentCount - 1) / BLOCK_SEGMENTS + 1) * 4)

    init {
        require(radiusM.isFinite() && radiusM >= 0.0)
        for (block in 0 until bounds.size / 4) {
            val start = block * BLOCK_SEGMENTS
            val end = start + minOf(BLOCK_SEGMENTS, segmentCount - start)
            var minLat = Double.POSITIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var minLng = Double.POSITIVE_INFINITY
            var maxLng = Double.NEGATIVE_INFINITY
            var valid = true
            // Include the final endpoint as well as every segment start.
            for (i in start..end) {
                val p = points[i]
                if (!validCoordinate(p.lat, p.lng)) {
                    valid = false
                    break
                }
                minLat = minOf(minLat, p.lat)
                maxLat = maxOf(maxLat, p.lat)
                minLng = minOf(minLng, p.lng)
                maxLng = maxOf(maxLng, p.lng)
            }
            val b = block * 4
            if (!valid) {
                // Malformed coordinates must not let the index hide a segment.
                bounds[b] = Double.NEGATIVE_INFINITY
                bounds[b + 1] = Double.POSITIVE_INFINITY
                bounds[b + 2] = Double.NEGATIVE_INFINITY
                bounds[b + 3] = Double.POSITIVE_INFINITY
                continue
            }
            // The exact distance uses 111320 m/degree latitude and longitude scaled
            // by cos(segment-start latitude). The largest absolute latitude in this
            // block gives the smallest longitude scale, hence the widest padding.
            // A small extra margin covers rounding in the local projection.
            val paddedM = radiusM + 0.01
            val latPad = paddedM / METERS_PER_DEGREE
            val lngScale = METERS_PER_DEGREE * cos(Math.toRadians(maxOf(abs(minLat), abs(maxLat))))
            val lngPad = if (lngScale > 0.0) paddedM / lngScale else Double.POSITIVE_INFINITY
            bounds[b] = minLat - latPad
            bounds[b + 1] = maxLat + latPad
            bounds[b + 2] = minLng - lngPad
            bounds[b + 3] = maxLng + lngPad
        }
    }

    fun contains(lat: Double, lng: Double, guidance: NavGuidanceEngine): Boolean {
        if (bounds.isEmpty() || !validCoordinate(lat, lng)) {
            return scan(lat, lng, guidance, 0, segmentCount)
        }
        for (block in 0 until bounds.size / 4) {
            val b = block * 4
            if (lat < bounds[b] || lat > bounds[b + 1] ||
                lng < bounds[b + 2] || lng > bounds[b + 3]) continue
            val start = block * BLOCK_SEGMENTS
            val end = start + minOf(BLOCK_SEGMENTS, segmentCount - start)
            if (scan(lat, lng, guidance, start, end)) return true
        }
        return false
    }

    private fun scan(lat: Double, lng: Double, guidance: NavGuidanceEngine, start: Int, end: Int): Boolean {
        for (i in start until end) {
            val a = points[i]
            val b = points[i + 1]
            if (guidance.pointToSegmentMeters(lat, lng, a.lat, a.lng, b.lat, b.lng) <= radiusM) return true
        }
        return false
    }

    private companion object {
        const val BLOCK_SEGMENTS = 32
        // Keep in sync with NavGuidanceEngine.pointToSegment's local projection.
        const val METERS_PER_DEGREE = 111_320.0

        fun validCoordinate(lat: Double, lng: Double): Boolean =
            lat in -90.0..90.0 && lng in -180.0..180.0
    }
}
