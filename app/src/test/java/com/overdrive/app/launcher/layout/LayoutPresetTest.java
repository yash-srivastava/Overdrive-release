package com.overdrive.app.launcher.layout;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class LayoutPresetTest {
    private static LayoutPreset.Slot slot(String id, String app, int x, int w) {
        return new LayoutPreset.Slot(id, app, x, 0, w, 10_000, 0);
    }

    @Test public void adjacentSlotsShareOnePixelBoundaryWithoutOverlapping() {
        LayoutPreset p = new LayoutPreset("drive", "Drive", Arrays.asList(
                slot("map", "com.overdrive.app", 0, 6667),
                slot("music", "com.example.music", 6667, 3333)));
        int[] a = p.slots.get(0).pixelBounds(1921, 720);
        int[] b = p.slots.get(1).pixelBounds(1921, 720);
        assertEquals(a[2], b[0]);
        assertEquals(1921, b[2]);
        assertEquals(720, b[3]);
    }

    @Test public void savedLayoutDoesNotFollowEditorMutations() {
        java.util.ArrayList<LayoutPreset.Slot> editor = new java.util.ArrayList<>();
        editor.add(slot("map", "com.overdrive.app", 0, 10_000));
        LayoutPreset p = new LayoutPreset("drive", "Drive", editor);
        editor.clear();
        assertEquals(1, p.slots.size());
        assertThrows(UnsupportedOperationException.class, () -> p.slots.clear());
    }

    @Test public void refusesAmbiguousOverlapAndDuplicateAppTargets() {
        assertThrows(IllegalArgumentException.class, () -> new LayoutPreset("drive", "Drive",
                Arrays.asList(slot("one", "com.example.one", 0, 6000),
                        slot("two", "com.example.two", 5000, 5000))));
        assertThrows(IllegalArgumentException.class, () -> new LayoutPreset("drive", "Drive",
                Arrays.asList(slot("one", "com.example.one", 0, 5000),
                        slot("two", "com.example.one", 5000, 5000))));
    }

    @Test public void refusesUnsafeDimensionsPackagesAndDensity() {
        assertThrows(IllegalArgumentException.class,
                () -> slot("slot", "com.example.app;am", 0, 10_000));
        assertThrows(IllegalArgumentException.class,
                () -> slot("slot", "com.example.app", 9000, 2000));
        assertThrows(IllegalArgumentException.class,
                () -> new LayoutPreset.Slot("slot", "com.example.app", 0, 0, 10_000, 10_000, 900));
        LayoutPreset.Slot small = slot("slot", "com.example.app", 0, 500);
        assertThrows(IllegalArgumentException.class, () -> small.pixelBounds(400, 720));
        assertThrows(IllegalArgumentException.class, () -> new LayoutPreset("bad/id", "Drive",
                Collections.singletonList(small)));
    }
}
