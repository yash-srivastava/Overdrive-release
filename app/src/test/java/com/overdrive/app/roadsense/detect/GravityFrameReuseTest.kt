package com.overdrive.app.roadsense.detect

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class GravityFrameReuseTest {
    private fun same(expected: Float, actual: Float) {
        assertEquals(expected.toBits(), actual.toBits())
    }

    @Test fun magnitudeAndYawFollowEveryGravityUpdateAndReset() {
        val random = Random(55)
        val alpha = 0.004f
        val frame = GravityFrame(alpha)
        var gx = 0f; var gy = 0f; var gz = 0f
        var seeded = false
        var weight = alpha
        repeat(10_000) { i ->
            if (i % 101 == 0) {
                frame.reset()
                gx = 0f; gy = 0f; gz = 0f; seeded = false
                same(0f, frame.gravityMagnitude)
                same(-2f, frame.alongGravity(1f, 3f, -2f))
                same(0f, frame.pitchRate(1f, 3f, -2f))
            }
            if (i % 73 == 0) {
                val period = if (i % 2 == 0) 25f else null
                frame.setSamplePeriodMs(period)
                weight = if (period == null) alpha else GravityFrame.rescaleAlpha(alpha, period)
            }
            val ax = random.nextFloat() * 6f - 3f
            val ay = random.nextFloat() * 6f - 3f
            val az = random.nextFloat() * 4f + 7f
            if (!seeded) {
                gx = ax; gy = ay; gz = az; seeded = true
            } else {
                gx += weight * (ax - gx); gy += weight * (ay - gy); gz += weight * (az - gz)
            }
            frame.update(ax, ay, az)
            val m = sqrt(gx * gx + gy * gy + gz * gz)
            same(m, frame.gravityMagnitude)
            repeat(3) {
                val x = random.nextFloat(); val y = random.nextFloat(); val z = random.nextFloat()
                same((x * gx + y * gy + z * gz) / m, frame.alongGravity(x, y, z))
                // Recover the effective longitudinal unit using the unchanged
                // roll projection, then compute pitch afresh for each gyro.
                // This also checks invalidation when the gravity tilt changes.
                var expectedPitch = 0f
                if (frame.pitchAxisReady) {
                    val lx = frame.rollRate(1f, 0f, 0f)
                    val ly = frame.rollRate(0f, 1f, 0f)
                    val lz = frame.rollRate(0f, 0f, 1f)
                    val ux = gx / m; val uy = gy / m; val uz = gz / m
                    val sx = uy * lz - uz * ly
                    val sy = uz * lx - ux * lz
                    val sz = ux * ly - uy * lx
                    val sm = sqrt(sx * sx + sy * sy + sz * sz)
                    if (sm >= 1e-3f) expectedPitch = x * (sx / sm) + y * (sy / sm) + z * (sz / sm)
                }
                same(expectedPitch, frame.pitchRate(x, y, z))
            }
        }
    }

    @Test fun pitchUsesCurrentTiltWithLatchedLongitudinalAxis() {
        // A constant gravity estimate makes the longitudinal and lateral axes analytic.
        val frame = GravityFrame(0f)
        frame.update(0f, 0f, 10f)
        repeat(150) { frame.update(2f, 0f, 10f) }
        assertEquals(true, frame.pitchAxisReady)
        same(3f, frame.pitchRate(2f, 3f, 4f))
        same(2f, frame.rollRate(2f, 3f, 4f))
        repeat(2000) { frame.update(0f, 0f, 10f) }
        assertEquals(true, frame.pitchAxisReady)
        same(3f, frame.pitchRate(2f, 3f, 4f))
        frame.reset()
        frame.update(0f, 10f, 0f)
        repeat(150) { frame.update(2f, 10f, 0f) }
        assertEquals(true, frame.pitchAxisReady)
        same(-4f, frame.pitchRate(2f, 3f, 4f))
        same(2f, frame.rollRate(2f, 3f, 4f))
    }

    @Test fun degenerateAndNonfiniteGravityKeepLegacyFallbacks() {
        val frame = GravityFrame(1f)
        frame.update(0f, -0f, 0f)
        same(0f, frame.gravityMagnitude)
        same(-0f, frame.alongGravity(1f, 2f, -0f))
        frame.update(0f, 0f, 10f)
        same(10f, frame.gravityMagnitude)
        same(3f, frame.alongGravity(1f, 2f, 3f))
        frame.update(0f, 0f, 0f)
        same(0f, frame.gravityMagnitude)
        same(3f, frame.alongGravity(1f, 2f, 3f))
        same(0f, frame.pitchRate(1f, 2f, 3f))
        frame.update(Float.NaN, 0f, 0f)
        same(Float.NaN, frame.gravityMagnitude)
        same(Float.NaN, frame.alongGravity(1f, 2f, 3f))
        frame.reset()
        same(0f, frame.gravityMagnitude)
    }
}
