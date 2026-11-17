package com.overdrive.app.launcher.layout;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClusterLayoutSelectionTest {
    @Test public void oldGestureCannotSelectInReplacementEvenWhenSlotIdsMatch() {
        ClusterLayoutSelection selection = new ClusterLayoutSelection();
        selection.initialize(1, "map");
        assertTrue(selection.select(1, "music"));
        selection.clear();
        selection.initialize(2, "map");
        assertFalse(selection.select(1, "music"));
        assertEquals("map", selection.selected(2));
        assertEquals("", selection.selected(1));
    }

    @Test public void stopImmediatelyRejectsKeysAndSelectionFromOldGesture() {
        ClusterLayoutSelection selection = new ClusterLayoutSelection();
        selection.initialize(5, "music");
        selection.clear();
        assertEquals("", selection.selected(5));
        assertFalse(selection.select(5, "map"));
    }
}
