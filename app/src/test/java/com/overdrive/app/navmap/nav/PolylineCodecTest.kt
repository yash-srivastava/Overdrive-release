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
}
