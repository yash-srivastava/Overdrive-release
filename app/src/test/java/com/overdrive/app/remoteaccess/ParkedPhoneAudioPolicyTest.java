package com.overdrive.app.remoteaccess;

import static com.overdrive.app.remoteaccess.ParkedPhoneAudioPolicy.Action.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure decision checks; no Bluetooth, settings or vehicle access. */
public class ParkedPhoneAudioPolicyTest {
    private static ParkedPhoneAudioPolicy.Input parked() {
        ParkedPhoneAudioPolicy.Input in = new ParkedPhoneAudioPolicy.Input();
        in.settingOn = true;
        in.monitorActive = true;
        in.power = 0;
        in.connected = true;
        in.nowMs = 100_000L;
        return in;
    }

    @Test public void parkedConnectedPhoneIsApplied() {
        assertEquals(APPLY, ParkedPhoneAudioPolicy.decide(parked()));
    }

    @Test public void settingOffNeverApplies() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.settingOn = false;
        assertEquals(NONE, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void powerOnRestores() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.applied = true;
        in.restorePending = true;
        in.power = 1;
        assertEquals(RESTORE, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void unknownPowerRestoresAndNeverApplies() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.power = -1;
        assertEquals(NONE, ParkedPhoneAudioPolicy.decide(in));
        in.restorePending = true;
        assertEquals(RESTORE, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void stoppedCollectorRestores() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.applied = true;
        in.monitorActive = false;
        assertEquals(RESTORE, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void settingTurnedOffRestores() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.applied = true;
        in.settingOn = false;
        assertEquals(RESTORE, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void crashRecordRestoresEvenWhenNotAppliedInThisProcess() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.power = 1;
        in.restorePending = true;
        assertEquals(RESTORE, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void failedRestoreRetriesOnlyAfterSpacing() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.power = 1;
        in.restorePending = true;
        in.lastRestoreAttemptMs = in.nowMs - 1_000L;
        assertEquals(NONE, ParkedPhoneAudioPolicy.decide(in));
        in.lastRestoreAttemptMs = in.nowMs - ParkedPhoneAudioPolicy.RETRY_INTERVAL_MS;
        assertEquals(RESTORE, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void powerOnRestoresImmediatelyAfterARecentApply() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.applied = true;
        in.restorePending = true;
        in.lastAttemptMs = in.nowMs;
        in.power = 1;
        assertEquals(RESTORE, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void disconnectedPhoneWaits() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.connected = false;
        assertEquals(NONE, ParkedPhoneAudioPolicy.decide(in));
        in.applied = true;
        assertEquals(NONE, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void failuresAndUnsupportedStopApplying() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.applyFailures = ParkedPhoneAudioPolicy.MAX_APPLY_FAILURES;
        assertEquals(NONE, ParkedPhoneAudioPolicy.decide(in));
        in.applyFailures = 0;
        in.unsupported = true;
        assertEquals(NONE, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void failedApplyRetriesAfterSpacingOrOnReconnect() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.applyFailures = 1;
        in.lastAttemptMs = in.nowMs - 1_000L;
        assertEquals(NONE, ParkedPhoneAudioPolicy.decide(in));
        in.reconnected = true;
        assertEquals(APPLY, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void appliedPhoneIsMaintainedOnCadenceAndReconnect() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.applied = true;
        in.restorePending = true;
        in.lastAttemptMs = in.nowMs - 1_000L;
        assertEquals(NONE, ParkedPhoneAudioPolicy.decide(in));
        in.lastAttemptMs = in.nowMs - ParkedPhoneAudioPolicy.MAINTAIN_INTERVAL_MS;
        assertEquals(MAINTAIN, ParkedPhoneAudioPolicy.decide(in));
        in.lastAttemptMs = in.nowMs - 1_000L;
        in.reconnected = true;
        assertEquals(MAINTAIN, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void clockGoingBackwardsIsTreatedAsDue() {
        ParkedPhoneAudioPolicy.Input in = parked();
        in.applied = true;
        in.lastAttemptMs = in.nowMs + 50_000L;
        assertEquals(MAINTAIN, ParkedPhoneAudioPolicy.decide(in));
    }

    @Test public void mediaDisconnectHasAPerParkBudget() {
        assertTrue(ParkedPhoneAudioPolicy.shouldDisconnectMedia(true, 0, false));
        assertFalse(ParkedPhoneAudioPolicy.shouldDisconnectMedia(false, 0, false));
        assertFalse(ParkedPhoneAudioPolicy.shouldDisconnectMedia(true,
                ParkedPhoneAudioPolicy.MAX_MEDIA_DISCONNECTS, false));
        assertFalse(ParkedPhoneAudioPolicy.shouldDisconnectMedia(true, 0, true));
    }
}
