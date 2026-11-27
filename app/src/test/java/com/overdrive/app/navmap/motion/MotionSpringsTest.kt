package com.overdrive.app.navmap.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSpringsTest {
    @Test
    fun decayMatchesCriticalDampingAndIsIndependentOfStepSize() {
        val single = DecaySpring(2.0).apply { x = 10.0 }
        val split = DecaySpring(2.0).apply { x = 10.0 }
        single.step(1.0)
        repeat(10) { split.step(0.1) }

        // For zero initial velocity: x(t) = x(0)(1 + omega*t)*exp(-omega*t).
        assertEquals(4.060058497098381, single.x, 1e-9)
        assertEquals(-5.413411329464508, single.v, 1e-9)
        assertEquals(single.x, split.x, 1e-9)
        assertEquals(single.v, split.v, 1e-9)
    }

    @Test
    fun decayIgnoresNonPositiveStepsAndClearsWhenSettled() {
        val spring = DecaySpring(2.0).apply { x = 10.0; v = -1.0 }
        spring.step(0.0)
        spring.step(-0.5)
        assertEquals(10.0, spring.x, 0.0)
        assertEquals(-1.0, spring.v, 0.0)
        assertFalse(spring.settled(0.01))

        spring.step(30.0)
        assertTrue(spring.settled(0.01))
        assertEquals(0.0, spring.x, 0.0)
        assertEquals(0.0, spring.v, 0.0)
    }

    @Test
    fun followSpringSeedsAndResetsWithoutResidualVelocity() {
        val spring = FollowSpring(2.0)
        assertTrue(spring.value.isNaN())
        assertEquals(5.0, spring.step(5.0, 0.1), 0.0)
        assertEquals(0.0, spring.velocity, 0.0)
        assertEquals(5.0, spring.step(15.0, 0.0), 0.0)
        spring.step(15.0, 0.5)
        assertTrue(spring.velocity > 0.0)

        spring.snap(20.0)
        assertEquals(20.0, spring.value, 0.0)
        assertEquals(0.0, spring.velocity, 0.0)
        spring.clear()
        assertTrue(spring.value.isNaN())
        assertEquals(0.0, spring.velocity, 0.0)
        assertEquals(7.0, spring.step(7.0, 0.1), 0.0)
    }

    @Test
    fun followSpringAgreesAcrossFrameRatesForConstantTarget() {
        val single = FollowSpring(2.0).apply { snap(0.0) }
        val split = FollowSpring(2.0).apply { snap(0.0) }
        single.step(10.0, 1.0)
        repeat(20) { split.step(10.0, 0.05) }
        assertEquals(5.939941502901619, single.value, 1e-9)
        assertEquals(5.413411329464508, single.velocity, 1e-9)
        assertEquals(single.value, split.value, 1e-9)
        assertEquals(single.velocity, split.velocity, 1e-9)
    }

    @Test
    fun angleSpringCrossesNorthInBothDirectionsByTheShortArc() {
        val clockwise = AngleSpring(2.0).apply { snap(350.0) }
        val counterclockwise = AngleSpring(2.0).apply { snap(10.0) }
        assertEquals(1.879883005803238, clockwise.step(10.0, 1.0), 1e-9)
        assertEquals(358.12011699419676, counterclockwise.step(350.0, 1.0), 1e-9)
        assertTrue(clockwise.rateDegPerS > 0.0)
        assertTrue(counterclockwise.rateDegPerS < 0.0)
        assertEquals(20.0, AngleSpring.shortestArc(350.0, 10.0), 0.0)
        assertEquals(-20.0, AngleSpring.shortestArc(10.0, 350.0), 0.0)
        assertEquals(359.0, AngleSpring.norm(-1.0), 0.0)
        assertEquals(0.0, AngleSpring.norm(720.0), 0.0)

        clockwise.clear()
        assertFalse(clockwise.isSet)
        assertEquals(0.0, clockwise.hold(0.1), 0.0)
        assertEquals(10.0, clockwise.step(370.0, 0.1), 0.0)
        assertTrue(clockwise.isSet)
        assertEquals(0.0, clockwise.rateDegPerS, 0.0)
    }
}
