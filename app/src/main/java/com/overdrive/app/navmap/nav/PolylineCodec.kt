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
     * @return decoded points; a truncated tail contributes no incomplete point
     */
    fun decode(encoded: String, precisionFactor: Double = 1e6): List<GeoPoint> {
        if (encoded.isEmpty()) return emptyList()

        val points = ArrayList<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        try {
            while (index < len) {
                // Decode one delta-encoded signed varint for latitude, then longitude.
                var shift = 0
                var result = 0
                var b: Int
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20 && index < len)
                val dLat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                lat += dLat

                shift = 0
                result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20 && index < len)
                // A continuation bit at EOF means the longitude is incomplete.
                // Keep the complete prefix instead of appending a fabricated point.
                if (b >= 0x20) break
                val dLng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                lng += dLng

                points.add(GeoPoint(lat / precisionFactor, lng / precisionFactor))
            }
        } catch (_: IndexOutOfBoundsException) {
            // Truncated/malformed tail — return what we decoded so far.
        }

        return points
    }
}
