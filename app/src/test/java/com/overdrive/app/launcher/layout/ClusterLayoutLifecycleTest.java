package com.overdrive.app.launcher.layout;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClusterLayoutLifecycleTest {
    @Test public void cancellationRejectsLateAllSlotCommit() {
        ClusterLayoutLifecycle lifecycle = new ClusterLayoutLifecycle(40);
        long allocated = lifecycle.begin();
        assertTrue(lifecycle.owns(allocated));
        long stopped = lifecycle.cancel();
        assertTrue(stopped > allocated);
        assertFalse(lifecycle.commit(allocated));
        assertFalse(lifecycle.owns(allocated));
        assertTrue(lifecycle.busy());
    }

    @Test public void changingPresetImmediatelyInvalidatesOldInputIdentity() {
        ClusterLayoutLifecycle lifecycle = new ClusterLayoutLifecycle(0);
        long first = lifecycle.begin();
        assertTrue(lifecycle.commit(first));
        long replacement = lifecycle.begin();
        assertTrue(replacement > first);
        assertFalse(lifecycle.owns(first));
        assertTrue(lifecycle.owns(replacement));
        assertFalse(lifecycle.active());
        assertTrue(lifecycle.commit(replacement));
    }

    @Test public void pendingResourceRecoveryBlocksFreshLayouts() {
        ClusterLayoutLifecycle lifecycle = new ClusterLayoutLifecycle(0);
        long cancelled = lifecycle.begin();
        lifecycle.cancel();
        lifecycle.recovering();
        assertEquals(0, lifecycle.begin());
        assertFalse(lifecycle.commit(cancelled));
        lifecycle.idle();
        long fresh = lifecycle.begin();
        assertTrue(fresh > cancelled);
        assertTrue(lifecycle.commit(fresh));
    }

    @Test public void admissionTransferNeverOpensStandardStartGap() {
        assertTrue(LayoutModeGate.reserveLayout(500));
        try {
            assertNull(LayoutModeGate.enterStandard());
            assertTrue(LayoutModeGate.transferLayout(500, 501));
            LayoutModeGate.releaseLayout(500);
            assertNull(LayoutModeGate.enterStandard());
            assertFalse(LayoutModeGate.transferLayout(500, 502));
        } finally {
            LayoutModeGate.releaseLayout(500);
            LayoutModeGate.releaseLayout(501);
        }
        LayoutModeGate.StandardStart standard = LayoutModeGate.enterStandard();
        assertNotNull(standard);
        standard.close();
    }
}
