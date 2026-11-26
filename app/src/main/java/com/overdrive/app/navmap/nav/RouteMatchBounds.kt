package com.overdrive.app.navmap.nav

import kotlin.math.abs
import kotlin.math.cos

/** Conservative block bounds for the guidance engine's existing local-meter projection. */
internal class RouteMatchBounds(points: List<GeoPoint>) {
    private val segmentCount = (points.size - 1).coerceAtLeast(0)
    private val bounds = if (segmentCount <= BLOCK_SEGMENTS) DoubleArray(0)
        else DoubleArray(((segmentCount - 1) / BLOCK_SEGMENTS + 1) * FIELDS)

    init {
        for (block in 0 until bounds.size / FIELDS) {
            val start = block * BLOCK_SEGMENTS
            val end = start + minOf(BLOCK_SEGMENTS, segmentCount - start)
            var minLat = Double.POSITIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var minLng = Double.POSITIVE_INFINITY
            var maxLng = Double.NEGATIVE_INFINITY
            var valid = true
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
            val b = block * FIELDS
            if (!valid) {
                bounds[b] = Double.NaN // fail open on malformed geometry
                continue
            }
            bounds[b] = minLat
            bounds[b + 1] = maxLat
            bounds[b + 2] = minLng
            bounds[b + 3] = maxLng
            // All segment-start latitudes are inside the block. Use its smallest
            // longitude scale, even at the poles; no antimeridian wrapping because
            // the existing exact projection does not wrap longitude either.
            bounds[b + 4] = METERS_PER_DEGREE * cos(Math.toRadians(maxOf(abs(minLat), abs(maxLat))))
        }
    }

    /**
     * At a block boundary, return its exclusive end if every segment is strictly
     * farther than [cutoffM]. Otherwise return [segment]. Candidate ordering is unchanged.
     */
    fun skipBlock(segment: Int, lat: Double, lng: Double, cutoffM: Double): Int {
        if (bounds.isEmpty() || segment % BLOCK_SEGMENTS != 0 ||
            !cutoffM.isFinite() || cutoffM < 0.0 || !validCoordinate(lat, lng)) return segment
        val b = segment / BLOCK_SEGMENTS * FIELDS
        if (bounds[b].isNaN()) return segment
        // Either axis alone is a lower bound on the exact Euclidean distance.
        // Use a strict comparison and a small rounding margin; never discard ties.
        val limit = cutoffM + 0.01
        val latGap = maxOf(bounds[b] - lat, lat - bounds[b + 1], 0.0)
        val lngGap = maxOf(bounds[b + 2] - lng, lng - bounds[b + 3], 0.0)
        if (latGap * METERS_PER_DEGREE > limit || lngGap * bounds[b + 4] > limit) {
            return segment + minOf(BLOCK_SEGMENTS, segmentCount - segment)
        }
        return segment
    }

    companion object {
        const val BLOCK_SEGMENTS = 32
        private const val FIELDS = 5
        // Match NavGuidanceEngine.pointToSegment's local-meter projection.
        private const val METERS_PER_DEGREE = 111_320.0

        private fun validCoordinate(lat: Double, lng: Double): Boolean =
            lat in -90.0..90.0 && lng in -180.0..180.0
    }
}
