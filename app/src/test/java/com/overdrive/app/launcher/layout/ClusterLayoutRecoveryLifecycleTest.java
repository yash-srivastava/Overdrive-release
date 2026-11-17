package com.overdrive.app.launcher.layout;

import org.junit.Test;

import static org.junit.Assert.*;

public class ClusterLayoutRecoveryLifecycleTest {
    @Test public void unreadableMarkerCannotBecomeIdleAfterStopOrAccCancellation() {
        ClusterLayoutLifecycle state = new ClusterLayoutLifecycle(80);
        state.recoveringForStandardRestore();
        state.cancel();
        assertEquals(ClusterLayoutLifecycle.IdleCompletion.BLOCKED, state.finishIdle(false, false));
        assertTrue(state.busy());
        state.recoveringForStandardRestore();
        assertEquals(0, state.begin());
        assertEquals(ClusterLayoutLifecycle.IdleCompletion.BLOCKED, state.finishIdle(false, false));
    }

    @Test public void confirmedEmptyReadReplaysRecoveryExactlyOnce() {
        ClusterLayoutLifecycle state = new ClusterLayoutLifecycle(80);
        state.recoveringForStandardRestore();
        assertEquals(ClusterLayoutLifecycle.IdleCompletion.RECOVERED, state.finishIdle(true, false));
        assertFalse(state.busy());
        assertEquals(ClusterLayoutLifecycle.IdleCompletion.STOPPED, state.finishIdle(true, false));
    }

    @Test public void pendingResourcesOrMarkerKeepRecoveryAndReplayIntent() {
        ClusterLayoutLifecycle state = new ClusterLayoutLifecycle(80);
        state.recoveringForStandardRestore();
        assertEquals(ClusterLayoutLifecycle.IdleCompletion.BLOCKED, state.finishIdle(true, true));
        assertEquals(0, state.begin());
        assertEquals(ClusterLayoutLifecycle.IdleCompletion.RECOVERED, state.finishIdle(true, false));
    }

    @Test public void ordinaryStopAndItsCleanupRetriesDoNotAutoStartAnotherMode() {
        ClusterLayoutLifecycle state = new ClusterLayoutLifecycle(80);
        assertTrue(state.commit(state.begin()));
        state.cancel();
        state.recovering(); // A transient rehome or release failure needs another cleanup pass.
        assertEquals(ClusterLayoutLifecycle.IdleCompletion.STOPPED, state.finishIdle(true, false));
    }

    @Test public void accOffResourceCleanupRetainsReplayIntentUntilOwnershipIsReconciled() {
        ClusterLayoutLifecycle state = new ClusterLayoutLifecycle(80);
        long active = state.begin();
        assertTrue(state.commit(active));
        state.cancel();
        state.recoveringForStandardRestore();
        assertFalse(state.owns(active));
        assertEquals(ClusterLayoutLifecycle.IdleCompletion.BLOCKED, state.finishIdle(true, true));
        assertEquals(ClusterLayoutLifecycle.IdleCompletion.RECOVERED, state.finishIdle(true, false));
    }

    @Test public void staleCleanupCannotRetireAcceptedOrCommittedReplacement() {
        ClusterLayoutLifecycle state = new ClusterLayoutLifecycle(80);
        long accepted = state.begin();
        assertEquals(ClusterLayoutLifecycle.IdleCompletion.BLOCKED, state.finishIdle(true, false));
        assertTrue(state.commit(accepted));
        assertEquals(ClusterLayoutLifecycle.IdleCompletion.BLOCKED, state.finishIdle(true, false));
        assertTrue(state.owns(accepted));
    }

    @Test public void unknownReadKeepsTheStandardModeFenceUntilConfirmedCompletion() {
        ClusterLayoutLifecycle state = new ClusterLayoutLifecycle(901);
        assertTrue(LayoutModeGate.reserveLayout(901));
        try {
            state.recoveringForStandardRestore();
            state.cancel();
            if (state.finishIdle(false, false) != ClusterLayoutLifecycle.IdleCompletion.BLOCKED)
                LayoutModeGate.releaseLayout(901);
            assertNull(LayoutModeGate.enterStandard());
            state.recoveringForStandardRestore();
            assertEquals(ClusterLayoutLifecycle.IdleCompletion.RECOVERED, state.finishIdle(true, false));
            LayoutModeGate.releaseLayout(901);
            LayoutModeGate.StandardStart restored = LayoutModeGate.enterStandard();
            assertNotNull(restored);
            restored.close();
        } finally {
            LayoutModeGate.releaseLayout(901);
        }
    }
}
