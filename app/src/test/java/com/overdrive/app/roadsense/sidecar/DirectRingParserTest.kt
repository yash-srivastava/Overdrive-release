package com.overdrive.app.roadsense.sidecar

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class DirectRingParserTest {
    private data class Sample(val type: Int, val ts: Long, val slot: Int)

    private class Fixture {
        val bytes = ByteArray(DirectRingParser.SLOTS * DirectRingParser.RECORD_BYTES)
        private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var tornSlot = -1
        val parser = DirectRingParser(DirectRingParser.Reader { dst, offset, count ->
            System.arraycopy(bytes, offset, dst, 0, count)
            if (count == DirectRingParser.RECORD_BYTES && offset / count == tornSlot) {
                // Change the counter on re-read only, as if the slot were overwritten.
                ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN).putInt(12, -1)
            }
        }, 11, 12)

        fun put(slot: Int, ts: Long, type: Int) {
            val o = slot * DirectRingParser.RECORD_BYTES
            buf.putInt(o + 4, if (type == Sensor.TYPE_ACCELEROMETER) 11 else 12)
            buf.putInt(o + 8, type)
            buf.putInt(o + 12, slot + 1)
            buf.putLong(o + 16, ts)
            buf.putFloat(o + 24, slot.toFloat())
            buf.putFloat(o + 28, -slot.toFloat())
            buf.putFloat(o + 32, 0.5f)
        }

        fun poll(): List<Sample> {
            val output = ArrayList<Sample>()
            val count = parser.poll { type, ts, x, y, z ->
                assertEquals(-x.toInt(), y.toInt())
                assertEquals(0.5f, z, 0f)
                output.add(Sample(type, ts, x.toInt()))
            }
            assertEquals(output.size, count)
            return output
        }
    }

    @Test fun stableOrderAcrossBurstAndDuplicateTimestamps() {
        val random = Random(541)
        repeat(1500) { iteration ->
            val f = Fixture()
            val samples = ArrayList<Sample>()
            for (slot in 0 until DirectRingParser.SLOTS) {
                if (random.nextInt(8) == 0) continue
                val type = if (random.nextBoolean()) Sensor.TYPE_ACCELEROMETER else Sensor.TYPE_GYROSCOPE
                val ts = when (iteration % 4) {
                    0 -> (128 - slot).toLong()
                    1 -> ((slot + 77) % 128 + 1).toLong()
                    2 -> random.nextLong(1, 40)
                    else -> Long.MAX_VALUE - random.nextLong(128)
                }
                f.put(slot, ts, type)
                samples.add(Sample(type, ts, slot))
            }
            val expected = samples.sortedBy { it.ts }.distinctBy { it.type to it.ts }
            assertEquals(expected, f.poll())
            assertEquals(emptyList<Sample>(), f.poll())
            assertEquals(0L, f.parser.torn)
            assertEquals(0, f.parser.tokenMismatch)
            assertEquals(0, f.parser.unattributed)
        }
    }

    @Test fun tornEarlierDuplicateDoesNotSuppressLaterValidRecord() {
        val f = Fixture()
        val type = Sensor.TYPE_ACCELEROMETER
        for (slot in 0 until 100) f.put(slot, (200 - slot).toLong(), type)
        f.put(100, 5, type)
        f.put(101, 5, type)
        f.tornSlot = 100
        val expected = listOf(Sample(type, 5, 101)) +
            (99 downTo 0).map { Sample(type, (200 - it).toLong(), it) }
        assertEquals(expected, f.poll())
        assertEquals(1L, f.parser.torn)
        assertEquals(102L, f.parser.maxCounter)
        assertEquals(emptyList<Sample>(), f.poll())
        f.put(100, 201, type)
        f.tornSlot = -1
        assertEquals(listOf(Sample(type, 201, 100)), f.poll())
    }
}
