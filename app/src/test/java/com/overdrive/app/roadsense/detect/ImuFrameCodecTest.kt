package com.overdrive.app.roadsense.detect

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class ImuFrameCodecTest {
    // The previous encoder: reference for the existing daemon's decoded values.
    private fun legacyEncode(
        accel: List<ImuAccelSample>, gyro: List<ImuGyroSample>, periodMs: Float? = null,
    ): String {
        val a = JSONArray()
        for (s in accel) {
            a.put(JSONArray().put(s.tMs).put(s.ax.toDouble()).put(s.ay.toDouble()).put(s.az.toDouble()))
        }
        val g = JSONArray()
        for (s in gyro) {
            g.put(JSONArray().put(s.tMs).put(s.gx.toDouble()).put(s.gy.toDouble()).put(s.gz.toDouble()))
        }
        val obj = JSONObject().put("command", "IMU_BATCH").put("a", a).put("g", g)
        if (periodMs != null) obj.put("p", Math.round(periodMs * 10f) / 10.0)
        return obj.toString() + "\n"
    }

    private fun checkParity(
        accel: List<ImuAccelSample>, gyro: List<ImuGyroSample>, periodMs: Float? = null,
    ) {
        val encoded = ImuFrameCodec.encode(accel, gyro, periodMs)
        val expected = ImuFrameCodec.decode(legacyEncode(accel, gyro, periodMs))!!
        val actual = ImuFrameCodec.decode(encoded)!!
        assertEquals(expected, actual)
        assertTrue(encoded.endsWith('\n'))
        assertEquals(1, encoded.count { it == '\n' })
        val json = JSONObject(encoded)
        assertEquals("IMU_BATCH", json.getString("command"))
        assertEquals(accel.size, json.getJSONArray("a").length())
        assertEquals(gyro.size, json.getJSONArray("g").length())
        assertEquals(periodMs != null, json.has("p"))
        // All nonzero, finite readings must retain their original float bits.
        for ((original, decoded) in accel.zip(actual.accel)) {
            assertEquals(original.tMs, decoded.tMs)
            checkBits(original.ax, decoded.ax)
            checkBits(original.ay, decoded.ay)
            checkBits(original.az, decoded.az)
        }
        for ((original, decoded) in gyro.zip(actual.gyro)) {
            assertEquals(original.tMs, decoded.tMs)
            checkBits(original.gx, decoded.gx)
            checkBits(original.gy, decoded.gy)
            checkBits(original.gz, decoded.gz)
        }
    }

    private fun checkBits(original: Float, decoded: Float) {
        if (original != 0f) assertEquals(original.toRawBits(), decoded.toRawBits())
        else assertTrue(decoded == 0f) // Signed-zero parsing follows the existing JSON reader.
    }

    @Test fun emptyAndSingleSensorBatchesPreserveSchemaAndPeriod() {
        val a = listOf(ImuAccelSample(1L, 0f, -0f, 9.81f))
        val g = listOf(ImuGyroSample(2L, -0f, 0f, -0.1f))
        checkParity(emptyList(), emptyList())
        checkParity(a, emptyList(), 10.05f)
        checkParity(emptyList(), g, 2.04f)
        for (period in listOf(null, 1.9f, 2f, 500f, 500.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            checkParity(a, g, period)
        }
    }

    @Test fun extremeAndRandomFiniteFloatValuesRoundTrip() {
        checkParity(
            listOf(ImuAccelSample(Long.MIN_VALUE, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE)),
            listOf(ImuGyroSample(Long.MAX_VALUE, -Float.MAX_VALUE, java.lang.Float.MIN_NORMAL, 1f)),
        )
        val random = Random(2921)
        fun finite(): Float {
            while (true) {
                val v = Float.fromBits(random.nextInt())
                if (v.isFinite()) return v
            }
        }
        repeat(100) {
            val a = List(64) { ImuAccelSample(1_760_000_000_000L + it, finite(), finite(), finite()) }
            val g = List(64) { ImuGyroSample(1_760_000_000_000L + it, finite(), finite(), finite()) }
            checkParity(a, g, 10f)
        }
    }

    @Test fun capStillAppliesAtDecodeAndNonfiniteComponentsAreRejected() {
        val a = List(65) { ImuAccelSample(it.toLong(), 1f, 2f, 3f) }
        checkParity(a, emptyList())
        assertEquals(64, ImuFrameCodec.decode(ImuFrameCodec.encode(a, emptyList()))!!.accel.size)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            try {
                ImuFrameCodec.encode(listOf(ImuAccelSample(1, bad, 0f, 0f)), emptyList())
                fail("Nonfinite accelerometer component was encoded")
            } catch (_: JSONException) { }
            try {
                ImuFrameCodec.encode(emptyList(), listOf(ImuGyroSample(1, 0f, 0f, bad)))
                fail("Nonfinite gyro component was encoded")
            } catch (_: JSONException) { }
        }
    }

    @Test fun typicalFractionalReadingsUseLessWireData() {
        val a = List(10) { ImuAccelSample(1_760_000_000_000L + it * 10, 0.1234567f, -1.234567f, 9.876543f) }
        val g = List(10) { ImuGyroSample(1_760_000_000_000L + it * 10, -0.01234567f, 0.2345678f, 0.0003456789f) }
        checkParity(a, g, 10f)
        val old = legacyEncode(a, g, 10f).length
        val compact = ImuFrameCodec.encode(a, g, 10f).length
        assertTrue("compact=$compact legacy=$old", compact < old * 0.85)
    }
}
