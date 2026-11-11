package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydDataCollector;

import org.junit.Test;

public class ScreenDeterrentDiLink5GuardTest {

    @Test
    public void unlockedVehicleStaysInOwnerHoldWithoutRequiringLockToUnlockEdge() {
        // Vehicle already unlocked at daemon start (lastLockState = INVALID/0) and past 2 minutes.
        long ts = ScreenDeterrent.updateDiLink5OwnerActivityTimestamp(
                300_000L,
                0L,
                false,
                false,
                BydDataCollector.DOOR_STATE_UNLOCK,
                BydDataCollector.DOOR_STATE_INVALID);
        assertTrue(ScreenDeterrent.isDiLink5OwnerHoldActive(
                300_000L,
                ts,
                false,
                BydDataCollector.DOOR_STATE_UNLOCK));
    }

    @Test
    public void lockingClosedVehicleExpiresHoldAfterShortWalkAwayGrace() {
        long unlockedAt = 500_000L;
        long lockEdgeAt = 510_000L;
        long adjustedTs = ScreenDeterrent.updateDiLink5OwnerActivityTimestamp(
                lockEdgeAt,
                unlockedAt,
                false,
                false,
                BydDataCollector.DOOR_STATE_LOCK,
                BydDataCollector.DOOR_STATE_UNLOCK);

        // During the 15s walk-away grace right after locking, owner hold remains active.
        assertTrue(ScreenDeterrent.isDiLink5OwnerHoldActive(
                lockEdgeAt + 10_000L,
                adjustedTs,
                false,
                BydDataCollector.DOOR_STATE_LOCK));

        // Once the walk-away grace elapses with car locked and doors closed, deterrent re-arms.
        assertFalse(ScreenDeterrent.isDiLink5OwnerHoldActive(
                lockEdgeAt + ScreenDeterrent.DILINK5_WALKAWAY_AFTER_LOCK_MS + 1L,
                adjustedTs,
                false,
                BydDataCollector.DOOR_STATE_LOCK));
    }

    @Test
    public void appSideCarAwakeAbortHoldsDaemonSuppressionWindow() {
        assertFalse(ScreenDeterrent.isRecentDiLink5AppAwakeAbort(100_000L, 0L));
        assertTrue(ScreenDeterrent.isRecentDiLink5AppAwakeAbort(120_000L, 100_000L));
        assertFalse(ScreenDeterrent.isRecentDiLink5AppAwakeAbort(
                100_000L + ScreenDeterrent.DILINK5_APP_AWAKE_HOLD_MS, 100_000L));
    }
}
