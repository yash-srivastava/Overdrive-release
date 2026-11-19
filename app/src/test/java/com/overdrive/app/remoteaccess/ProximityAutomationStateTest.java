package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

/** Edge semantics independent of Android, engine and native Bluetooth readers. */
public class ProximityAutomationStateTest {
    @Test public void noAwayWithoutPriorNearVisitOrFullConsensus() {
        ProximityAutomationState state = new ProximityAutomationState();
        assertNull(state.observe(false, false));
        assertNull(state.observe(false, true)); // starting with all phones away is not departure
        assertEquals("near", state.observe(true, false));
        assertNull(state.observe(true, false));
        assertEquals("unknown", state.observe(false, false)); // second phone is unknown
        assertNull(state.observe(false, false));
        assertEquals("away", state.observe(false, true));
        assertNull(state.observe(false, true)); // stable away must not flicker or refire
        assertEquals("near", state.observe(true, false));
        assertEquals("away", state.observe(false, true));
    }

    @Test public void multiplePhonesRequireFullAwayConsensus() {
        java.util.List<MultiPhoneProximityPolicy.Observation> phones = java.util.List.of(
                new MultiPhoneProximityPolicy.Observation(true, true, true,
                        ProximityEvaluator.State.AWAY, 1_000),
                new MultiPhoneProximityPolicy.Observation(true, true, true,
                        ProximityEvaluator.State.NEAR, 1_000));
        assertEquals(false, MultiPhoneProximityPolicy.allAway(phones, 1_000, 5_000));
        assertEquals(true, MultiPhoneProximityPolicy.anyConfirmedNear(phones, 1_000, 5_000));
        ProximityAutomationState state = new ProximityAutomationState();
        assertEquals("near", state.observe(true, false));
        assertEquals("unknown", state.observe(false, false));
        phones = java.util.List.of(phones.get(0),
                new MultiPhoneProximityPolicy.Observation(true, true, true,
                        ProximityEvaluator.State.AWAY, 2_000));
        assertEquals(true, MultiPhoneProximityPolicy.allAway(phones, 2_000, 5_000));
        assertEquals("away", state.observe(false, true));
    }

    @Test public void resetCannotManufactureDeparture() {
        ProximityAutomationState state = new ProximityAutomationState();
        assertEquals("near", state.observe(true, false));
        state.reset();
        assertNull(state.observe(false, true));
        assertEquals("near", state.observe(true, false));
        assertEquals("away", state.observe(false, true));
    }
}
