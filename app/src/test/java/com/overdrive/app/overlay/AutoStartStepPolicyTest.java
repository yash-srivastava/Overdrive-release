package com.overdrive.app.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoStartStepPolicyTest {

    @Test
    public void byd100fWithoutTheManagerSkipsTheStep() {
        assertFalse(AutoStartStepPolicy.applies(false, true, false));
    }

    @Test
    public void byd100fThatHasTheManagerKeepsTheStep() {
        assertTrue(AutoStartStepPolicy.applies(false, true, true));
    }

    @Test
    public void otherFirmwareKeepsTheStepEvenWithoutTheManager() {
        // Some ROMs without the manager expose the switch on the app-info page instead.
        assertTrue(AutoStartStepPolicy.applies(false, false, false));
        assertTrue(AutoStartStepPolicy.applies(false, false, true));
    }

    @Test
    public void daemonPathKeepsTheStep() {
        assertTrue(AutoStartStepPolicy.applies(true, true, false));
    }
}
