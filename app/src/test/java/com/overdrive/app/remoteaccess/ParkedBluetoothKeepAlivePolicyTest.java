package com.overdrive.app.remoteaccess;

import static com.overdrive.app.remoteaccess.ParkedBluetoothKeepAlivePolicy.Action.*;
import static com.overdrive.app.remoteaccess.ParkedBluetoothKeepAlivePolicy.RadioState.*;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Resource-free policy checks; no adapter, cloud, or physical vehicle is accessed. */
public class ParkedBluetoothKeepAlivePolicyTest {
    @Test public void knownOffAndConsentEnableAnOffRadio() {
        assertEquals(ENABLE, decide(true, false, true, false, false, false, OFF));
    }

    @Test public void disabledDefaultDoesNotChangeAnInitiallyOnRadio() {
        assertEquals(NONE, decide(false, false, true, false, false, false, ON));
    }

    @Test public void explicitOffDisablesAnInitiallyOnParkedRadio() {
        assertEquals(DISABLE, decide(false, false, true, false, false, true, ON));
    }

    @Test public void consentRevocationReleasesAnOwnedParkedRadio() {
        assertEquals(DISABLE, decide(false, false, true, false, true, false, ON));
        assertEquals(RELEASE_OWNERSHIP,
                decide(false, false, true, false, true, false, OFF));
    }

    @Test public void drivingHandsOffOwnershipWithoutDisablingNormalBluetooth() {
        assertEquals(RELEASE_OWNERSHIP,
                decide(false, false, false, true, true, true, ON));
        assertEquals(NONE, decide(false, false, false, true, false, true, ON));
    }

    @Test public void unknownPowerNeverEnablesOrDisablesAnOnAdapter() {
        assertEquals(NONE, decide(true, false, false, false, false, false, OFF));
        assertEquals(NONE, decide(false, false, false, false, true, true, ON));
    }

    @Test public void conflictingPowerReportsCannotEnableRadio() {
        assertEquals(NONE, decide(true, false, true, true, false, false, OFF));
    }

    @Test public void onOnlyGateReleasesOwnedParkedBluetooth() {
        assertEquals(DISABLE, decide(true, true, true, false, true, false, ON));
        assertEquals(NONE, decide(true, true, true, false, false, false, OFF));
    }

    @Test public void transitionsDoNotRepeatAcceptedCommands() {
        assertEquals(NONE, decide(true, false, true, false, true, false, TURNING_ON));
        assertEquals(NONE, decide(false, false, true, false, true, true, TURNING_OFF));
        assertEquals(DISABLE, decide(false, false, true, false, true, false, TURNING_ON));
    }

    @Test public void confirmedOffCanClearRevokedLeaseWithoutPowerRead() {
        assertEquals(RELEASE_OWNERSHIP,
                decide(false, false, false, false, true, false, OFF));
    }

    @Test public void safeZoneDisableWaitsForConfirmedDaemonDirectiveWhileParked() {
        assertEquals(DISABLE, decide(true, false, true, false, false, false,
                true, false, ON));
        assertEquals(NONE, decide(true, false, true, false, false, false,
                true, false, OFF));
        assertEquals(NONE, decide(true, false, false, false, false, false,
                true, false, ON));
        assertEquals(NONE, decide(true, true, true, false, false, false,
                true, false, ON));
    }

    @Test public void safeZoneDirectiveNeverActsOnUnknownAdapterState() {
        assertEquals(NONE, decide(true, false, true, false, false, false,
                true, false, UNKNOWN));
    }

    @Test public void noSafeZoneDirectiveKeepsExistingParkedPreference() {
        assertEquals(ENABLE, decide(true, false, true, false, false, false,
                false, false, OFF));
    }

    @Test public void safeZoneOwnedRadioIsRestoredWhenDirectiveClears() {
        assertEquals(ENABLE, decide(true, false, true, false, true, false,
                false, true, OFF));
        assertEquals(RELEASE_OWNERSHIP, decide(true, false, true, false, true, false,
                false, true, ON));
    }

    @Test public void safeZoneOwnedRadioIsRestoredWhenVehicleStarts() {
        assertEquals(ENABLE, decide(true, false, false, true, true, false,
                false, true, OFF));
        assertEquals(RELEASE_OWNERSHIP, decide(true, false, false, true, true, false,
                false, true, ON));
        assertEquals(ENABLE, decide(false, false, false, true, true, false,
                false, true, OFF));
    }

    @Test public void safeZoneOwnedRadioRespectsExplicitKeepAliveRevocation() {
        assertEquals(DISABLE, decide(false, false, true, false, true, true,
                false, true, ON));
        assertEquals(RELEASE_OWNERSHIP, decide(false, false, true, false, true, true,
                false, true, OFF));
    }

    private static ParkedBluetoothKeepAlivePolicy.Action decide(
            boolean configured, boolean onOnly, boolean vehicleOff, boolean vehicleOn,
            boolean owned, boolean forceDisable,
            ParkedBluetoothKeepAlivePolicy.RadioState state) {
        return ParkedBluetoothKeepAlivePolicy.decide(configured, onOnly, vehicleOff, vehicleOn,
                owned, forceDisable, state);
    }

    private static ParkedBluetoothKeepAlivePolicy.Action decide(
            boolean configured, boolean onOnly, boolean vehicleOff, boolean vehicleOn,
            boolean owned, boolean forceDisable, boolean safeZoneDisable, boolean safeZoneOwned,
            ParkedBluetoothKeepAlivePolicy.RadioState state) {
        return ParkedBluetoothKeepAlivePolicy.decide(configured, onOnly, vehicleOff, vehicleOn,
                owned, forceDisable, safeZoneDisable, safeZoneOwned, state);
    }
}
