package com.overdrive.app.monitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Decision table for the inputs returned by shell Wi-Fi binder reads. */
public class WifiMeteringPolicyTest {
    @Test public void explicitOwnerMeteredAlwaysRefuses() {
        assertFalse(WifiMeteringPolicy.isUnmetered(1, false, false));
        assertFalse(WifiMeteringPolicy.isUnmetered(1, true, false));
    }

    @Test public void explicitOwnerUnmeteredOverridesBothHints() {
        assertTrue(WifiMeteringPolicy.isUnmetered(2, true, true));
        assertTrue(WifiMeteringPolicy.isUnmetered(2, null, true));
    }

    @Test public void automaticRequiresTwoNonmeteredHints() {
        assertTrue(WifiMeteringPolicy.isUnmetered(0, false, false));
        assertFalse(WifiMeteringPolicy.isUnmetered(0, true, false));
        assertFalse(WifiMeteringPolicy.isUnmetered(0, false, true));
        assertFalse(WifiMeteringPolicy.isUnmetered(0, null, false));
        assertFalse(WifiMeteringPolicy.isUnmetered(7, false, false));
    }
}
