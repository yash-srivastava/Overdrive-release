package com.overdrive.app.byd

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Sealion7DisplayPowerTest {
    private var now = 10_000_000_000L
    private var eligible = true
    private var dump: String? = row(92)
    private var reads = 0
    private val jobs = ArrayDeque<Runnable>()
    private val source = Sealion7DisplayPower(
        Executor { jobs.addLast(it) }, { eligible }, { reads++; dump }, { now }
    )

    private fun row(kw: Int, ts: Long = now) =
        "event count:41,lastEvent:Property:0x21402402,status: 0,timestamp:$ts,zone:0x0," +
            "floatValues: [],int32Values: [$kw]"

    private fun sample() = source.resolve(Double.NaN)

    private fun refresh() {
        sample()
        jobs.removeFirst().run()
    }

    @Test
    fun measuredPackIncludingZeroWinsWithoutRequestingADump() {
        assertEquals(0.0, source.resolve(0.0), 0.0)
        assertEquals(-22.75, source.resolve(-22.75), 0.0)
        assertTrue(jobs.isEmpty())
        refresh()
        assertEquals(92.0, sample(), 0.0)
        now += 4_000_000_000L
        assertEquals(3.25, source.resolve(3.25), 0.0)
        assertTrue(jobs.isEmpty())
    }

    @Test
    fun signedIntegerGaugeKeepsNativeKwAcrossZero() {
        for (kw in listOf(92, -55, 0, -1, 175)) {
            dump = row(kw)
            refresh()
            assertEquals(kw.toDouble(), sample(), 0.0)
            now += 2_100_000_000L
        }
    }

    @Test
    fun repeatedRequestsShareOneRefreshAndBackOffAfterMissingData() {
        dump = null
        repeat(20) { assertTrue(sample().isNaN()) }
        assertEquals(1, jobs.size)
        assertEquals(0, reads)
        now += 4_000_000_000L // the queued read stalled
        assertTrue(sample().isNaN())
        assertEquals(1, jobs.size)
        jobs.removeFirst().run()
        repeat(20) { assertTrue(sample().isNaN()) }
        assertTrue(jobs.isEmpty())
        assertEquals(1, reads)
        now += 2_000_000_000L
        dump = row(-55)
        refresh()
        assertEquals(-55.0, sample(), 0.0)
        assertEquals(2, reads)
    }

    @Test
    fun repeatedFreshDumpsCannotRenewAnOldGaugeEvent() {
        dump = row(1)
        refresh()
        now += 2_000_000_000L
        refresh()
        now += 1_000_000_000L
        assertEquals(1.0, sample(), 0.0)
        now++
        assertTrue(sample().isNaN())
    }

    @Test
    fun invalidEventCannotReplaceMissingPackAndFutureEventCannotBecomeValidLater() {
        val valid = row(-55)
        val invalid = listOf(
            null, "", valid.replace("2402", "2403"), valid.replace("0x0,", "0x1,"),
            valid.replace("status: 0,", ""), valid.replace("status: 0", "status: 1"),
            valid.replace("int32Values: [-55]", "int32Values: [1, 2]"),
            valid.replace("floatValues: [],int32Values: [-55]", "floatValues: [-55],int32Values: []"),
            valid.replace("timestamp:$now", "timestamp:bad"), row(65_535), row(-10_013),
            row(1, 0), "$valid\n$valid"
        )
        for (bad in invalid) {
            val display = Sealion7DisplayPower(Executor { it.run() }, { true }, { bad }, { now })
            assertTrue("unexpected value for $bad", display.resolve(Double.NaN).isNaN())
        }
        dump = row(1, now + 1)
        refresh()
        now++
        assertTrue(sample().isNaN())
    }

    @Test
    fun ineligibleModelNeverSchedulesAReadAndImmediatelyDropsFormerModelsPower() {
        eligible = false
        repeat(20) { assertTrue(sample().isNaN()) }
        assertTrue(jobs.isEmpty())
        eligible = true
        refresh()
        assertEquals(92.0, sample(), 0.0)
        eligible = false
        assertTrue(sample().isNaN())
        now += 2_100_000_000L
        assertTrue(sample().isNaN())
        assertTrue(jobs.isEmpty())
        assertEquals(1, reads)
    }

    @Test
    fun selectionChangedWhileQueuedSkipsReadingTheDump() {
        sample()
        eligible = false
        jobs.removeFirst().run()
        assertEquals(0, reads)
        assertTrue(sample().isNaN())
    }
    @Test
    fun ignitionBoundaryRejectsQueuedWorkAndPreIgnitionEventsInFreshDumps() {
        sample()
        source.invalidate()
        jobs.removeFirst().run()
        assertEquals(0, reads)
        now++
        refresh() // the same car_service row, read after the new cycle
        assertTrue(sample().isNaN())
        now += 2_100_000_000L
        dump = row(-55)
        refresh()
        assertEquals(-55.0, sample(), 0.0)
    }

}
