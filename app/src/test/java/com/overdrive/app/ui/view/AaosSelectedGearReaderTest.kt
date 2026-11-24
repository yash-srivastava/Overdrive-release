package com.overdrive.app.ui.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AaosSelectedGearReaderTest {
    private fun sample(gear: Int?, property: Int = 289408000, area: Int = 0,
                       status: Int = 0, changed: Long = 1_000_000L) =
        AaosSelectedGearReader.parkObservation(property, area, status, gear, changed,
            readStartedMillis = 650_000L, nowNanos = 650_020_000_000L)

    @Test fun availableUnchangedParkIsFreshAtTheReadAndOnlyParkQualifies() {
        assertEquals(650_000L, sample(4))
        for (other in listOf(1, 2, 8, 16)) assertEquals(0L, sample(other))
        for (unknown in listOf(0, 3, 15)) assertNull(sample(unknown))
    }

    @Test fun unknownSourceStatusAreaOrClockCannotConfirmPark() {
        assertNull(sample(4, property = 289408001))
        assertNull(sample(4, area = 1))
        assertNull(sample(4, status = 1))
        assertNull(sample(4, status = 2))
        assertNull(sample(null))
        assertNull(sample(4, changed = 0))
        assertNull(sample(4, changed = 651_000_000_000L))
    }
}
