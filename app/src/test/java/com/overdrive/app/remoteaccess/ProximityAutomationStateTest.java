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

    @Test public void drivingSafetyGateUsesPowerAndClassifiedMovement() {
        assertEquals(true, ProximityAutomationState.safeForPresence(0, "state_unknown"));
        assertEquals(true, ProximityAutomationState.safeForPresence(2, null));
        assertEquals(false, ProximityAutomationState.safeForPresence(2, "not_park"));
        assertEquals(false, ProximityAutomationState.safeForPresence(2, "moving"));
        assertEquals(false, ProximityAutomationState.safeForPresence(2, "speed_unknown"));
        assertEquals(false, ProximityAutomationState.safeForPresence(-1, null));
        assertEquals(false, ProximityAutomationState.safeForPresence(0, "moving"));
    }

    @Test public void movingHoldsStateAndDoesNotRearmAwayOrRepeatNearOnPark() {
        ProximityAutomationState state = new ProximityAutomationState();
        assertEquals("near", state.observe(true, false));
        state.holdWhileMoving();
        assertNull(state.observe(true, false)); // Requalified parked Near must not re-fire.
        assertEquals("away", state.observe(false, true)); // Fresh Near rearmed departure.
        state.holdWhileMoving();
        assertEquals("unknown", state.observe(false, true)); // No stale Away episode survives motion.
    }

    @Test public void staleUnknownCannotTurnIntoAwayAfterCollectorRestart() {
        ProximityAutomationState state = new ProximityAutomationState();
        assertEquals("near", state.observe(true, false));
        state.forgetVisit(); // Bluetooth off, dead phone, stale collector lease.
        assertEquals("unknown", state.observe(false, false));
        assertNull(state.observe(false, true)); // Restart reports all Away an hour later.
        assertEquals("near", state.observe(true, false));
        assertEquals("away", state.observe(false, true));
    }

    @Test public void rosterChangeWithSameNearDoesNotPublishUnknownNear() {
        ProximityAutomationState state = new ProximityAutomationState();
        assertEquals("near", state.observe(true, false));
        state.forgetVisit(); // Invalidate the old roster's departure history only.
        assertNull(state.observe(true, false));
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
