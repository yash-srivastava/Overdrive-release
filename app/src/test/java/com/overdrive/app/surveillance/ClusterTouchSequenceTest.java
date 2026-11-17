package com.overdrive.app.surveillance;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClusterTouchSequenceTest {
    @Test public void pinchPreservesPointerIdsEvenWhenOrderChanges() {
        ClusterTouchSequence s = new ClusterTouchSequence();
        assertTrue(s.accept(0, 0, new int[] {7}));
        assertTrue(s.accept(5, 1, new int[] {7, 2}));
        assertTrue(s.accept(2, 0, new int[] {2, 7}));
        assertTrue(s.accept(6, 0, new int[] {2, 7}));
        assertTrue(s.accept(2, 0, new int[] {7}));
        assertTrue(s.accept(1, 0, new int[] {7}));
    }

    @Test public void lostPointerFrameCannotFabricateNewGesture() {
        ClusterTouchSequence s = new ClusterTouchSequence();
        assertFalse(s.accept(2, 0, new int[] {7}));
        assertTrue(s.accept(0, 0, new int[] {7}));
        assertFalse(s.accept(2, 0, new int[] {7, 2}));
        assertFalse(s.accept(6, 1, new int[] {7, 2}));
        assertTrue(s.accept(3, 0, new int[] {7}));
        assertFalse(s.accept(1, 0, new int[] {7}));
    }

    @Test public void malformedPointerTopologyIsRejected() {
        ClusterTouchSequence s = new ClusterTouchSequence();
        assertFalse(s.accept(0, 0, new int[] {1, 1}));
        assertFalse(s.accept(0, 0, new int[] {32}));
        assertTrue(s.accept(0, 0, new int[] {31}));
        assertFalse(s.accept(5, 1, new int[] {31, 31}));
        assertFalse(s.accept(5, 0, new int[] {31, 2}));
        assertTrue(s.accept(5, 1, new int[] {31, 2}));
        assertFalse(s.accept(1, 0, new int[] {31, 2}));
        s.reset();
        assertTrue(s.accept(0, 0, new int[] {2}));
    }
}
