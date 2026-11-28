package com.overdrive.app.navmap.nav

/**
 * Decoder for the Google "Encoded Polyline Algorithm" as emitted by Valhalla.
 *
 * <p>IMPORTANT: Valhalla encodes route shapes at **precision 6** (coordinates
 * multiplied by 1e6), NOT Google's classic 1e5. Decoding a Valhalla shape with
 * the 1e5 factor yields coordinates off by 10x. The default [precisionFactor]
 * here is therefore `1e6`. Pass `1e5` only if decoding a classic Google shape.
 *
 * <p>Pure Kotlin, no Android/3rd-party dependencies, deterministic — safe to
 * unit test on the JVM.
 */
object PolylineCodec {

    /**
     * Decode an encoded polyline string into a list of [GeoPoint] (lat/lng in
     * decimal degrees), in order.
     *
     * @param encoded the encoded polyline string (Valhalla `legs[].shape`)
     * @param precisionFactor the coordinate scaling factor; Valhalla uses
     *   `1e6` (the default), classic Google polylines use `1e5`
     * @return decoded points up to the first malformed, truncated or out-of-range point
     */
    fun decode(encoded: String, precisionFactor: Double = 1e6): List<GeoPoint> {
        if (encoded.isEmpty() || !precisionFactor.isFinite() || precisionFactor <= 0.0) {
            return emptyList()
        }

        val points = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0L
        var lng = 0L

        // A signed 32-bit delta uses at most 32 unsigned zigzag bits.
        fun readDelta(): Long? {
            var shift = 0
            var result = 0L
            while (index < encoded.length && shift <= 30) {
                val b = encoded[index++].code - 63
                if (b !in 0..63 || (shift == 30 && b > 3)) return null
                result = result or ((b and 0x1f).toLong() shl shift)
                if (b < 0x20) {
                    return if ((result and 1L) != 0L) (result shr 1).inv() else result shr 1
                }
                shift += 5
            }
            return null
        }

        while (index < encoded.length) {
            val dLat = readDelta() ?: break
            val dLng = readDelta() ?: break
            // Accumulate before narrowing to coordinates to avoid Int wraparound.
            lat += dLat
            lng += dLng
            val latitude = lat / precisionFactor
            val longitude = lng / precisionFactor
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) break
            points.add(GeoPoint(latitude, longitude))
        }
        return points
    }
}
