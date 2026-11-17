package com.overdrive.app.surveillance;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class ClusterLayoutGestureTest {
    @Test public void lossAfterDownCancelsPinnedDisplayWithoutFinishingOrReplayingGesture() {
        AtomicBoolean current = new AtomicBoolean(true);
        List<Integer> actions = new ArrayList<>();
        assertFalse(ClusterLayoutGesture.swipe(7, 10, 20, 50, 60, 200,
                current::get, () -> 100L, (action, down, time, x, y, display) -> {
                    assertEquals(7, display);
                    actions.add(action);
                    if (action == 0) current.set(false);
                    return true;
                }));
        assertEquals(Arrays.asList(0, 3), actions);
    }

    @Test public void failedUpCancelsWithoutASecondDown() {
        List<Integer> actions = new ArrayList<>();
        assertFalse(ClusterLayoutGesture.tap(8, 10, 20, () -> true, () -> 100L,
                (action, down, time, x, y, display) -> {
                    actions.add(action);
                    return action != 1;
                }));
        assertEquals(Arrays.asList(0, 1, 3), actions);
    }

    @Test public void missingGenerationRefusesEveryEvent() {
        assertFalse(ClusterLayoutGesture.tap(8, 10, 20, () -> false, () -> 100L,
                (action, down, time, x, y, display) -> {
                    fail("stale gesture was injected"); return true;
                }));
    }

    @Test public void successfulSwipeKeepsPinnedDisplayAndEndsAtRequestedCoordinate() {
        List<Integer> actions = new ArrayList<>();
        assertTrue(ClusterLayoutGesture.swipe(8, 10, 20, 50, 60, 32, () -> true, () -> 100L,
                (action, down, time, x, y, display) -> {
                    assertEquals(8, display);
                    assertEquals(100L, down);
                    actions.add(action);
                    if (action == 1) {
                        assertEquals(50, x); assertEquals(60, y); assertEquals(132L, time);
                    }
                    return true;
                }));
        assertEquals(Arrays.asList(0, 2, 2, 1), actions);
    }
}
