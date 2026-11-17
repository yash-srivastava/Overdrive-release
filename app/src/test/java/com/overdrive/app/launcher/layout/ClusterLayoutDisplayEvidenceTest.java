package com.overdrive.app.launcher.layout;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClusterLayoutDisplayEvidenceTest {
    @Test public void fissionIdentityRequiresOnePositiveLivePhysicalDisplay() {
        assertEquals(3, ClusterLayoutDisplayEvidence.fissionDisplay(
                "mBaseDisplayInfo=DisplayInfo{\"fission\", displayId 3, layerStack 7, state ON}\n"
                + "mBaseDisplayInfo=DisplayInfo{\"shared_fission\", displayId 4, layerStack 8, state ON}"));
        assertEquals(-1, ClusterLayoutDisplayEvidence.fissionDisplay(
                "DisplayInfo{\"fission\", displayId 0, layerStack 0, state ON}"));
        assertEquals(-1, ClusterLayoutDisplayEvidence.fissionDisplay(
                "DisplayInfo{\"fission\", displayId 3, layerStack 7, state ON}\n"
                + "DisplayInfo{\"fission\", displayId 5, layerStack 9, state ON}"));
    }

    @Test public void aRecycledDisplayIdCannotAuthorizeRecoveryMove() {
        String dump = "DisplayInfo{\"headunit\", displayId 0}\n"
                + "DisplayInfo{\"another_app\", displayId 12}";
        assertEquals(-1, ClusterLayoutDisplayEvidence.displayIdentity(dump, 12, "overdrive_layout_42", ""));
        assertEquals(0, ClusterLayoutDisplayEvidence.displayIdentity(dump, 13, "overdrive_layout_42", ""));
        assertEquals(-1, ClusterLayoutDisplayEvidence.displayIdentity("permission denied", 13, "layout", ""));
    }

    @Test public void displayNamePrefixCannotMatchAnotherSlotOrRestart() {
        String dump = "DisplayInfo{\"overdrive_layout_42_123_map_11\", displayId 12}";
        assertEquals(-1, ClusterLayoutDisplayEvidence.displayIdentity(
                dump, 12, "overdrive_layout_42_123_map_1", ""));
        assertEquals(1, ClusterLayoutDisplayEvidence.displayIdentity(
                dump, 12, "overdrive_layout_42_123_map_11", ""));
    }

    @Test public void backgroundActivityAlsoPreventsPrematureDisplayRelease() {
        String dump = "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
                + "Display #0 (activities from top to bottom):\n"
                + "  mResumedActivity: com.example.music/.Main\n"
                + "Display #12 (activities from top to bottom):\n"
                + "  mResumedActivity: com.other.app/.Cover\n"
                + "  Hist #1: ActivityRecord{foo u0 com.example.music/.Main t17}\n"
                + "Display #13 (activities from top to bottom):\n"
                + "  com.example.music/.Main\n";
        assertEquals(Boolean.TRUE, ClusterLayoutDisplayEvidence.packageOnDisplay(dump, 12, "com.example.music"));
        assertEquals(Boolean.FALSE, ClusterLayoutDisplayEvidence.packageOnDisplay(dump, 12, "com.example.map"));
        assertEquals(Boolean.FALSE, ClusterLayoutDisplayEvidence.packageOnDisplay(dump, 14, "com.example.music"));
    }

    @Test public void unreadableTasksNeverCountAsSuccessfulRehome() {
        assertNull(ClusterLayoutDisplayEvidence.packageOnDisplay("Permission Denial", 12, "com.example.music"));
        assertNull(ClusterLayoutDisplayEvidence.packageOnDisplay(
                "ACTIVITY MANAGER ACTIVITIES\nunrecognized display format", 12, "com.example.music"));
        assertNull(ClusterLayoutDisplayEvidence.packageOnDisplay(
                "ACTIVITY MANAGER ACTIVITIES\nDisplay #0:\n", 0, "com.example.music"));
    }

    @Test public void recoveryOnlyAcceptsOurPrivateDisplayNameAndExactGeneration() {
        assertTrue(ClusterLayoutDisplayEvidence.ownedDisplayName("overdrive_layout_42_123_map_1", 42));
        assertFalse(ClusterLayoutDisplayEvidence.ownedDisplayName("overdrive_layout_42_123_map_1", 43));
        assertFalse(ClusterLayoutDisplayEvidence.ownedDisplayName("fission_bg_xdjaVirtualSurface", 42));
        assertFalse(ClusterLayoutDisplayEvidence.ownedDisplayName("shared_fission_bg_XDJAScreenProjection_0", 42));
        assertFalse(ClusterLayoutDisplayEvidence.ownedDisplayName("overdrive_layout_42_0_map_1", 42));
        assertFalse(ClusterLayoutDisplayEvidence.ownedDisplayName("overdrive_layout_42_123_map_0", 42));
    }
}
