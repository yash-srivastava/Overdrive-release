package com.overdrive.app.byd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5DirectTelemetryTest {
    @Test
    fun decodesOnlyACompletePlausibleDynamicsTuple() {
        val observation = DiLink5DirectTelemetry.decodeDynamics(
            DiLink5DirectTelemetry.RawRead(0, 42.4f.toBits()),
            DiLink5DirectTelemetry.RawRead(0, 17),
            DiLink5DirectTelemetry.RawRead(0, 9),
            DiLink5DirectTelemetry.RawRead(0, 4)
        )

        assertEquals(42, observation!!.speedKmh)
        assertEquals(17, observation.accelPercent)
        assertEquals(9, observation.brakePercent)
        assertEquals(4, observation.gear)
        assertEquals(0L, observation.ageMs)
    }

    @Test
    fun exactSpeedDecodePreservesFractionAboveSafetyLimit() {
        val decoded = DiLink5DirectTelemetry.decodeFloat(
            DiLink5DirectTelemetry.RawRead(0, 80.4f.toBits()), 0f, 400f)

        assertEquals(80.4f, decoded!!, 0.0001f)
        assertTrue(decoded > 80f)
    }

    @Test
    fun rejectsServiceErrorsSentinelsAndOutOfRangeValues() {
        assertNull(DiLink5DirectTelemetry.decodeFloat(
            DiLink5DirectTelemetry.RawRead(-1, 20f.toBits()), 0f, 400f))
        assertNull(DiLink5DirectTelemetry.decodeInt(
            DiLink5DirectTelemetry.RawRead(0, 65_535), 0, 100))
        assertNull(DiLink5DirectTelemetry.decodeInt(
            DiLink5DirectTelemetry.RawRead(0, 101), 0, 100))
        assertNull(DiLink5DirectTelemetry.decodeDynamics(
            DiLink5DirectTelemetry.RawRead(0, Float.NaN.toBits()),
            DiLink5DirectTelemetry.RawRead(0, 0),
            DiLink5DirectTelemetry.RawRead(0, 0),
            DiLink5DirectTelemetry.RawRead(0, 1)
        ))
    }

    @Test
    fun acceptsOnlyTheTurnSignalEnumDomain() {
        assertEquals(6, DiLink5DirectTelemetry.decodeTurn(
            DiLink5DirectTelemetry.RawRead(0, 6)))
        assertNull(DiLink5DirectTelemetry.decodeTurn(
            DiLink5DirectTelemetry.RawRead(0, 8)))
    }
}
