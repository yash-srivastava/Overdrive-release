package com.overdrive.app.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccPowerLevelPolicyTest {

    @Test
    public void legacyNonAuthoritativeDefaultFallsThroughToHardware() {
        assertFalse(AccPowerLevelPolicy.shouldOverrideBodyworkWithOff(
                false, false, false));
    }

    @Test
    public void dilink5NonAuthoritativeFallbackStillShieldsKnownBadHal() {
        assertTrue(AccPowerLevelPolicy.shouldOverrideBodyworkWithOff(
                false, false, true));
    }

    @Test
    public void authoritativeOffAlwaysOverridesTheHal() {
        assertTrue(AccPowerLevelPolicy.shouldOverrideBodyworkWithOff(
                true, false, false));
    }

    @Test
    public void accOnNeverFabricatesAnOff() {
        assertFalse(AccPowerLevelPolicy.shouldOverrideBodyworkWithOff(
                true, true, false));
        assertFalse(AccPowerLevelPolicy.shouldOverrideBodyworkWithOff(
                false, true, true));
    }
}
