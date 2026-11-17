package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class ClusterFreeformOwnershipTest {
    @Test
    public void recoveryRetainsExpectedAndNeverOverwritesExternalDrift() {
        assertEquals("expected",
                ClusterFreeformWindow.freeformRecoveryDecision("0", "1", "1"));
        assertEquals("restored",
                ClusterFreeformWindow.freeformRecoveryDecision("0", "1", "0"));
        assertEquals("external",
                ClusterFreeformWindow.freeformRecoveryDecision("0", "1", "2"));
    }

    @Test
    public void unreadableRecoveryInputsFailClosed() {
        assertEquals("unreadable",
                ClusterFreeformWindow.freeformRecoveryDecision(null, "1", "1"));
        assertEquals("unreadable",
                ClusterFreeformWindow.freeformRecoveryDecision("0", null, "1"));
        assertEquals("unreadable",
                ClusterFreeformWindow.freeformRecoveryDecision("0", "1", ""));
    }

    @Test
    public void diLink5BootSuppressionNeverSuppressesLegacyRetry() {
        assertTrue(ClusterFreeformWindow.shouldSuppressFreeform(true, true));
        assertFalse(ClusterFreeformWindow.shouldSuppressFreeform(false, true));
        assertFalse(ClusterFreeformWindow.shouldSuppressFreeform(true, false));
    }
}
