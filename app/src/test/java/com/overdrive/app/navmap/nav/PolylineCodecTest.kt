package com.overdrive.app.navmap.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineCodecTest {
    @Test fun completeSignedDeltasDecodeAtBothSupportedPrecisions() {
        // "??" = origin; "_A" = +32, "~@" = -32 in scaled coordinate units.
        for (factor in listOf(1e6, 1e5)) {
            assertEquals(
                listOf(GeoPoint(0.0, 0.0), GeoPoint(32 / factor, -32 / factor)),
                PolylineCodec.decode("??_A~@", factor)
            )
        }
    }

    @Test fun truncatedLongitudeKeepsOnlyCompletePoints() {
        for (factor in listOf(1e6, 1e5)) {
            val prefix = listOf(GeoPoint(0.0, 0.0))
            assertEquals(prefix, PolylineCodec.decode("??_A~", factor))
            assertEquals(prefix, PolylineCodec.decode("??_A_", factor))
            assertTrue(PolylineCodec.decode("_A~", factor).isEmpty())
        }
    }

    @Test fun missingLongitudeAndTruncatedLatitudeKeepCompletePrefix() {
        val prefix = listOf(GeoPoint(0.0, 0.0))
        assertEquals(prefix, PolylineCodec.decode("??_A"))
        assertEquals(prefix, PolylineCodec.decode("??_"))
        assertTrue(PolylineCodec.decode("").isEmpty())
    }

    @Test fun invalidAlphabetCharactersKeepCompletePrefix() {
        val prefix = listOf(GeoPoint(0.0, 0.0))
        for (factor in listOf(1e6, 1e5)) {
            assertEquals(prefix, PolylineCodec.decode("?? ?", factor))
            assertEquals(prefix, PolylineCodec.decode("??_A ", factor))
            assertEquals(prefix, PolylineCodec.decode("??\u007f?", factor))
            assertTrue(PolylineCodec.decode(" ?", factor).isEmpty())
        }
    }

    @Test fun overflowingAndOverlongVarintsKeepCompletePrefix() {
        val prefix = listOf(GeoPoint(0.0, 0.0))
        for (factor in listOf(1e6, 1e5)) {
            // The seventh group carries a bit beyond the 32-bit zigzag value.
            assertEquals(prefix, PolylineCodec.decode("??______C?", factor))
            assertEquals(prefix, PolylineCodec.decode("???______C", factor))
            assertEquals(prefix, PolylineCodec.decode("??_______??", factor))
        }
    }

    @Test fun geographicBoundsAreInclusiveButRejectOutOfRangeDeltas() {
        // Encoded (+90, +180), then a delta crossing to longitude -180.
        val cases = listOf(
            Triple(1e5, "_cidP_gsia@", "~ngtcA"),
            Triple(1e6, "_gdtjD_oiivI", "~~ssmT")
        )
        val boundary = listOf(GeoPoint(90.0, 180.0))
        for ((factor, encoded, westwardDelta) in cases) {
            assertEquals(boundary, PolylineCodec.decode(encoded, factor))
            assertEquals(boundary, PolylineCodec.decode(encoded + "A?", factor))
            assertEquals(boundary, PolylineCodec.decode(encoded + "?A", factor))
            assertEquals(
                boundary + GeoPoint(90.0, -180.0),
                PolylineCodec.decode(encoded + "?" + westwardDelta, factor)
            )
        }
    }

    @Test fun invalidPrecisionDoesNotProduceCoordinates() {
        for (factor in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(PolylineCodec.decode("??", factor).isEmpty())
        }
    }
}
