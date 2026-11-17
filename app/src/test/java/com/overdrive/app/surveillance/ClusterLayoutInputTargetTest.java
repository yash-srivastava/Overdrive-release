package com.overdrive.app.surveillance;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClusterLayoutInputTargetTest {
    @Test public void hitTestExcludesGapAndSharedRightBottomBoundary() {
        ClusterLayoutInputTarget left = target(100, 20, 300, 200);
        assertTrue(left.contains(100f, 20f));
        assertTrue(left.contains(399.9f, 219.9f));
        assertFalse(left.contains(400f, 60f));
        assertFalse(left.contains(200f, 220f));
        assertFalse(left.contains(99.9f, 60f));
        assertFalse(left.contains(Float.NaN, 60f));
    }

    @Test public void mapsClusterOriginAndScalesToVirtualDisplay() {
        ClusterLayoutInputTarget slot = new ClusterLayoutInputTarget(
                9, 800, 40, 400, 600, 200, 300, 12L);
        assertEquals(0f, slot.mapX(800f), 0f);
        assertEquals(0f, slot.mapY(40f), 0f);
        assertEquals(100f, slot.mapX(1000f), 0f);
        assertEquals(150f, slot.mapY(340f), 0f);
    }

    @Test public void crossingAnotherPaneKeepsOriginalDestinationAndClamps() {
        ClusterLayoutInputTarget initial = target(100, 20, 300, 200);
        assertEquals(7, initial.displayId);
        assertEquals(299f, initial.mapX(900f), 0f);
        assertEquals(0f, initial.mapY(-50f), 0f);
        assertEquals(7, initial.displayId);
    }

    @Test public void stopOrReplacementInvalidatesPinnedGesture() {
        ClusterLayoutInputTarget initial = target(0, 0, 300, 200);
        assertTrue(initial.isCurrent(true, 5L));
        assertFalse(initial.isCurrent(false, 5L));
        assertFalse(initial.isCurrent(true, 6L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void neverAllowsHeadUnitAsDestination() {
        new ClusterLayoutInputTarget(0, 0, 0, 100, 100, 100, 100, 1L);
    }

    private static ClusterLayoutInputTarget target(int left, int top, int width, int height) {
        return new ClusterLayoutInputTarget(7, left, top, width, height, width, height, 5L);
    }
}
