package com.overdrive.app.geo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-Java regression cases for Nominatim's process-wide one-token gate. */
public class NominatimTokenGateTest {

    @Test
    public void firstTokenIsAvailableWithNegativeNanoTimeOrigin() {
        NominatimTokenGate gate = new NominatimTokenGate(1_000_000_000L);
        assertFalse(gate.isThrottled(-4_000_000_000L));
        assertTrue(gate.tryAcquire(-4_000_000_000L));
        assertTrue(gate.isThrottled(-3_000_000_001L));
        assertFalse(gate.tryAcquire(-3_000_000_001L));
        assertFalse(gate.isThrottled(-3_000_000_000L));
        assertTrue(gate.tryAcquire(-3_000_000_000L));
    }

    @Test
    public void firstTokenIsAvailableWithZeroOrPositiveNanoTimeOrigin() {
        NominatimTokenGate atZero = new NominatimTokenGate(1_000_000_000L);
        assertFalse(atZero.isThrottled(0L));
        assertTrue(atZero.tryAcquire(0L));
        assertTrue(atZero.isThrottled(0L));

        NominatimTokenGate atPositive = new NominatimTokenGate(1_000_000_000L);
        assertTrue(atPositive.tryAcquire(42_000_000_000L));
        assertFalse(atPositive.tryAcquire(42_999_999_999L));
        assertTrue(atPositive.tryAcquire(43_000_000_000L));
    }

    @Test
    public void nonConsumingProbeDoesNotMoveDeadline() {
        NominatimTokenGate gate = new NominatimTokenGate(1_000_000_000L);
        assertTrue(gate.tryAcquire(10_000_000_000L));
        assertTrue(gate.isThrottled(10_400_000_000L));
        assertTrue(gate.isThrottled(10_800_000_000L));
        assertTrue(gate.tryAcquire(11_000_000_000L));
        assertTrue(gate.isThrottled(11_000_000_000L));
    }

    @Test
    public void elapsedSubtractionHandlesSignedNanoTimeWraparound() {
        NominatimTokenGate gate = new NominatimTokenGate(1_000_000_000L);
        long first = Long.MAX_VALUE - 500_000_000L;
        assertTrue(gate.tryAcquire(first));
        assertTrue(gate.isThrottled(first + 999_999_999L));
        assertFalse(gate.isThrottled(first + 1_000_000_000L));
        assertTrue(gate.tryAcquire(first + 1_000_000_000L));
    }

    @Test
    public void negativeElapsedAgeDoesNotGrantEarlyToken() {
        NominatimTokenGate gate = new NominatimTokenGate(1_000_000_000L);
        assertTrue(gate.tryAcquire(10_000L));
        assertTrue(gate.isThrottled(9_999L));
        assertFalse(gate.tryAcquire(9_999L));
    }
}
