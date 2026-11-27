package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.monitor.GearMonitor;
import org.junit.Test;

public class CarViewStreamStateTest {

    private static final long GRACE = CarViewStreamHold.FRAME_RESUME_GRACE_MS;

    @Test
    public void eventPublishesAPendingWordWithoutFrames() {
        CarViewStreamState state = new CarViewStreamState();
        assertEquals(0L, state.read());
        assertFalse(state.pendingWithoutFrames());
        state.publishEvent(10_000L);
        long word = state.read();
        assertEquals(10_000L, CarViewStreamState.eventMs(word));
        assertFalse(CarViewStreamState.resumed(word));
        assertTrue(state.pendingWithoutFrames());
    }

    @Test
    public void frameInsideTheGraceDoesNotEndTheHoldButALaterOneDoes() {
        CarViewStreamState state = new CarViewStreamState();
        state.publishEvent(10_000L);
        assertFalse(state.onFrame(10_000L + GRACE - 1));
        assertTrue(state.pendingWithoutFrames());
        assertTrue(state.onFrame(10_000L + GRACE));
        assertTrue(CarViewStreamState.resumed(state.read()));
        assertFalse(state.pendingWithoutFrames());
        // Already resumed: no second transition.
        assertFalse(state.onFrame(10_000L + GRACE + 1));
    }

    @Test
    public void aNewEventReplacesAResumedOne() {
        CarViewStreamState state = new CarViewStreamState();
        state.publishEvent(10_000L);
        state.onFrame(20_000L);
        state.publishEvent(60_000L);
        long word = state.read();
        assertEquals(60_000L, CarViewStreamState.eventMs(word));
        assertFalse(CarViewStreamState.resumed(word));
        // A frame right after the new event is inside its grace and must not resume it.
        assertFalse(state.onFrame(60_000L + 1));
        assertTrue(state.pendingWithoutFrames());
    }

    @Test
    public void watchdogClearKeepsANewerEvent() {
        CarViewStreamState state = new CarViewStreamState();
        state.publishEvent(10_000L);
        long seen = state.read();
        state.publishEvent(15_000L); // arrives between the watchdog's read and its clear
        assertFalse(state.clear(seen));
        assertEquals(15_000L, CarViewStreamState.eventMs(state.read()));
        assertTrue(state.clear(state.read()));
        assertEquals(0L, state.read());
        assertFalse(state.clear(0L));
    }

    @Test
    public void frameMarkOnAStaleWordCannotResumeANewerEvent() {
        CarViewStreamState state = new CarViewStreamState();
        state.publishEvent(10_000L);
        state.publishEvent(30_000L);
        // A frame long after the first event but inside the second's grace leaves it pending.
        assertFalse(state.onFrame(30_000L + GRACE - 1));
        assertTrue(state.pendingWithoutFrames());
    }

    @Test
    public void errorRestartIsHeldOnlyWhileTheCarViewHoldIs() {
        CarViewStreamState state = new CarViewStreamState();
        int d = GearMonitor.GEAR_D;
        int r = GearMonitor.GEAR_R;
        assertFalse(CarViewStreamHold.errorRestartHeld(true, state.read(), d, 1_000L));
        state.publishEvent(10_000L);
        long word = state.read();
        assertTrue(CarViewStreamHold.errorRestartHeld(true, word, d, 10_000L + 1_000L));
        assertFalse(CarViewStreamHold.errorRestartHeld(false, word, d, 10_000L + 1_000L));
        long dCap = 10_000L + CarViewStreamHold.CAR_VIEW_MAX_MS;
        assertFalse(CarViewStreamHold.errorRestartHeld(true, word, d, dCap));
        assertTrue(CarViewStreamHold.errorRestartHeld(true, word, r, dCap));
        assertFalse(CarViewStreamHold.errorRestartHeld(true, word, r,
                10_000L + CarViewStreamHold.CAR_VIEW_R_MAX_MS));
        state.onFrame(10_000L + GRACE);
        assertFalse(CarViewStreamHold.errorRestartHeld(true, state.read(), r, 10_000L + 2_000L));
    }
}
