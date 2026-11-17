package com.overdrive.app.navmap.cluster;

import com.overdrive.app.launcher.layout.LayoutModeGate;
import org.junit.Test;
import static org.junit.Assert.*;

/** Layout admission must suspend vendor IPC even before any physical projection exists. */
public class ClusterGuidanceLayoutAdmissionTest {
    @Test public void acceptedLayoutSuspendsStripWriteBeforePhysicalOpen() {
        assertTrue(LayoutModeGate.reserveLayout(6401L));
        try {
            ClusterGuidanceVendorWriter writer = new ClusterGuidanceVendorWriter();
            ClusterGuidanceFrame frame = new ClusterGuidanceFrame("roadsense", 10, 300,
                    "Road", 1000, 120, 0);
            assertEquals("suspended_for_active_projection", writer.write(frame, false, () -> true).cluster);
        } finally {
            LayoutModeGate.releaseLayout(6401L);
        }
    }

    @Test public void pendingRecoveryDefersClearUntilItsAdmissionOwnerReleases() {
        assertTrue(LayoutModeGate.reserveLayout(6402L));
        try {
            // An earlier cleanup cannot authorize navigation during the retained recovery.
            LayoutModeGate.releaseLayout(6401L);
            ClusterGuidanceVendorWriter.Result result = new ClusterGuidanceVendorWriter().clear(false);
            assertTrue(result.stripClearDeferred);
            assertEquals("suspended_for_active_projection", result.cluster);
        } finally {
            LayoutModeGate.releaseLayout(6402L);
        }
    }
}
