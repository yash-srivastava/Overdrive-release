package com.overdrive.app.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

public class ClusterDisplaySnapshotTest {
    private static String display(int id, String name, String unique, int stack, String state) {
        return "mBaseDisplayInfo=DisplayInfo{\"" + name + ", displayId " + id
                + "\", uniqueId \"" + unique
                + "\", app 1920 x 680, real 1920 x 720, largest app 3000 x 2000,"
                + " density 160 (160 x 160) dpi, layerStack " + stack + ", state " + state + "}";
    }

    @Test public void readsPrivateLogicalDisplayWithoutAFrameworkDisplayObject() {
        ClusterDisplaySnapshot snapshot = ClusterDisplaySnapshot.find(
                display(7, "fission_bg_xdjaVirtualSurface", "virtual:android:fission:21", 9, "ON"), 7);
        assertNotNull(snapshot);
        assertEquals("fission_bg_xdjaVirtualSurface", snapshot.name);
        assertEquals("virtual:android:fission:21", snapshot.uniqueId);
        assertEquals(1920, snapshot.width);
        assertEquals(720, snapshot.height);
        assertEquals(9, snapshot.layerStack);
        assertTrue(snapshot.usableFission());
    }

    @Test public void acceptsOemNameAndDisplayIdInSeparateFields() {
        ClusterDisplaySnapshot snapshot = ClusterDisplaySnapshot.find(
                "DisplayInfo{\"fission_bg_xdjaVirtualSurface\", displayId 7,"
                + " uniqueId \"v7\", real 1920 x 720, layerStack=9, density 160, state ON}", 7);
        assertNotNull(snapshot);
        assertEquals("fission_bg_xdjaVirtualSurface", snapshot.name);
        assertTrue(snapshot.usableFission());
    }

    @Test public void refusesDefaultAndMissingDisplays() {
        String dump = display(0, "Built-in Screen", "local:0", 0, "ON")
                + "\n" + display(10, "fission", "v10", 10, "ON");
        assertNull(ClusterDisplaySnapshot.find(dump, 0));
        assertNull(ClusterDisplaySnapshot.find(dump, -1));
        assertNull(ClusterDisplaySnapshot.find(dump, 1));
        assertNull(ClusterDisplaySnapshot.find("Permission Denial", 10));
    }

    @Test public void deviceOnlyEvidenceCannotAuthorizeALogicalDisplay() {
        assertNull(ClusterDisplaySnapshot.find(
                "DisplayDeviceInfo{\"fission, displayId 7\", real 1920 x 720, layerStack 9, state ON}", 7));
    }

    @Test public void ambiguousNameOrUniqueIdIsRejected() {
        String first = display(7, "fission", "v7", 9, "ON");
        assertNull(ClusterDisplaySnapshot.find(first + "\n"
                + display(7, "Other screen", "v7", 9, "ON"), 7));
        assertNull(ClusterDisplaySnapshot.find(first + "\n"
                + display(7, "fission", "recycled", 9, "ON"), 7));
    }

    @Test public void conflictingStacksCannotRetargetContent() {
        assertNull(ClusterDisplaySnapshot.find(display(7, "fission", "v7", 9, "ON")
                + "\n" + display(7, "fission", "v7", 0, "ON"), 7));
    }

    @Test public void laterOverrideGeometryKeepsTheStableIdentity() {
        String dump = display(7, "fission", "v7", 9, "ON")
                + "\nmOverrideDisplayInfo=DisplayInfo{\"fission, displayId 7\","
                + " real 1600 x 600, density 240, layerStack 9, state ON}";
        ClusterDisplaySnapshot snapshot = ClusterDisplaySnapshot.find(dump, 7);
        assertNotNull(snapshot);
        assertEquals("v7", snapshot.uniqueId);
        assertEquals(1600, snapshot.width);
        assertEquals(600, snapshot.height);
        assertEquals(240, snapshot.densityDpi);
    }

    @Test public void recycledIdentityCannotPassAnExistingLeaseOrSurfaceCheck() {
        ClusterDisplaySnapshot first = ClusterDisplaySnapshot.find(display(7, "fission", "v7", 9, "ON"), 7);
        ClusterDisplaySnapshot recycled = ClusterDisplaySnapshot.find(
                display(7, "fission", "different-instance", 9, "ON"), 7);
        assertFalse(first.sameIdentity(recycled));
        assertFalse(first.sameCompositorTarget(recycled));
    }

    @Test public void lostStableIdentityCannotPassVerification() {
        ClusterDisplaySnapshot first = ClusterDisplaySnapshot.find(display(7, "fission", "v7", 9, "ON"), 7);
        ClusterDisplaySnapshot unknown = ClusterDisplaySnapshot.find(display(7, "fission", "null", 9, "ON"), 7);
        assertEquals("", unknown.uniqueId);
        assertFalse(first.sameIdentity(unknown));
    }

    @Test public void changedGeometryOrStackCannotReuseANativeSlot() {
        ClusterDisplaySnapshot first = ClusterDisplaySnapshot.find(display(7, "fission", "v7", 9, "ON"), 7);
        ClusterDisplaySnapshot changedStack = ClusterDisplaySnapshot.find(
                display(7, "fission", "v7", 11, "ON"), 7);
        ClusterDisplaySnapshot changedSize = ClusterDisplaySnapshot.find(
                display(7, "fission", "v7", 9, "ON").replace("real 1920 x 720", "real 1600 x 600"), 7);
        assertFalse(first.sameCompositorTarget(changedStack));
        assertFalse(first.sameCompositorTarget(changedSize));
    }

    @Test public void sharedDi5AndInactiveDisplaysDoNotEnableTheDi3Backend() {
        for (String state : new String[] {"OFF", "UNKNOWN", "DOZE"}) {
            assertFalse(ClusterDisplaySnapshot.find(display(7, "fission", "v7", 9, state), 7).usableFission());
        }
        assertFalse(ClusterDisplaySnapshot.find(
                display(7, "shared_fission_bg_XDJAScreenProjection_0", "v7", 9, "ON"), 7).usableFission());
        assertFalse(ClusterDisplaySnapshot.find(
                display(7, "fission", "v7", 0, "ON"), 7).usableFission());
    }

    @Test public void missingOrInvalidRealDimensionsCannotCreateASurface() {
        String dump = display(7, "fission", "v7", 9, "ON");
        assertFalse(ClusterDisplaySnapshot.find(dump.replace("real 1920 x 720,", ""), 7).usableFission());
        assertFalse(ClusterDisplaySnapshot.find(
                dump.replace("real 1920 x 720", "real 1920 x 999999999999"), 7).usableFission());
    }

    @Test public void nestedHdrAndModeObjectsDoNotHideTheStackOrState() {
        String dump = display(7, "fission", "v7", 9, "ON").replace(
                " density 160", " modes [{id=1, width=1920, height=720}],"
                + " hdrCapabilities HdrCapabilities{mSupportedHdrTypes=[2], mMaxLuminance=500.0},"
                + " density 160");
        ClusterDisplaySnapshot snapshot = ClusterDisplaySnapshot.find(dump, 7);
        assertNotNull(snapshot);
        assertTrue(snapshot.usableFission());
        assertEquals(9, snapshot.layerStack);
    }

    @Test public void partialNestedDumpCannotAuthorizeProjection() {
        String partial = display(7, "fission", "v7", 9, "ON")
                .replace(", density 160", ", hdrCapabilities HdrCapabilities{nested={value=1}}, density 160");
        assertNull(ClusterDisplaySnapshot.find(partial.substring(0, partial.length() - 1), 7));
    }
}
