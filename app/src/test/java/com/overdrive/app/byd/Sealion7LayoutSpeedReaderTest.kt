package com.overdrive.app.byd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Sealion7LayoutSpeedReaderTest {
    private val reading = Sealion7LayoutSpeedReader.LatestReading()
    private val feed = 49.0

    @Test fun onlyAnExplicitSealion7OnDiLink5IsEligible() {
        assertTrue(Sealion7LayoutSpeedReader.eligible("dilink5", "sealion7", "user"))
        for (source in listOf("auto", "legacy", "unset", null)) {
            assertFalse(Sealion7LayoutSpeedReader.eligible("dilink5", "sealion7", source))
        }
        assertFalse(Sealion7LayoutSpeedReader.eligible("dilink5", "seal", "user"))
        assertFalse(Sealion7LayoutSpeedReader.eligible("default", "sealion7", "user"))
        assertFalse(Sealion7LayoutSpeedReader.eligible(null, "sealion7", "user"))
    }

    @Test fun usesFreshFractionalSpeedAndAcceptsParkedZero() {
        assertEquals(feed, reading.valueOr(1000, feed), 0.0)
        val generation = reading.generation()
        reading.publish(45.71875, 1000, generation)
        assertEquals(45.71875, reading.valueOr(1100, feed), 0.0)
        reading.publish(0.0, 1200, generation)
        assertEquals(0.0, reading.valueOr(1200, feed), 0.0)
    }

    @Test fun aStalledReadFallsBackEvenIfItJustReturned() {
        reading.publish(28.0, 1000, reading.generation())
        assertEquals(feed, reading.valueOr(2000, feed), 0.0)
        assertTrue(reading.valueOr(2000, Double.NaN).isNaN())
    }

    @Test fun aLateResultCannotRestoreSpeedAfterDisconnectOrStop() {
        val first = reading.generation()
        reading.publish(28.0, 1000, first)
        reading.clear()
        reading.publish(30.0, 1100, first)
        assertEquals(feed, reading.valueOr(1100, feed), 0.0)
        reading.publish(32.0, 1200, reading.generation())
        assertEquals(32.0, reading.valueOr(1200, feed), 0.0)
    }

    @Test fun anInvalidSpeedNeverBecomesAReading() {
        for (invalid in listOf(-1.0, 65535.0, Double.POSITIVE_INFINITY, Double.NaN)) {
            val generation = reading.generation()
            reading.publish(18.0, 1000, generation)
            reading.publish(invalid, 1100, generation)
            assertEquals(feed, reading.valueOr(1100, feed), 0.0)
        }
    }
}
