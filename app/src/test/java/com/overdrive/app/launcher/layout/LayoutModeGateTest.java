package com.overdrive.app.launcher.layout;

import org.junit.Test;
import static org.junit.Assert.*;

public class LayoutModeGateTest {
    @Test public void inFlightStandardStartPreventsLayoutReservation() {
        try (LayoutModeGate.StandardStart standard = LayoutModeGate.enterStandard()) {
            assertNotNull(standard);
            assertFalse(LayoutModeGate.reserveLayout(41));
        }
        assertTrue(LayoutModeGate.reserveLayout(41));
        try {
            assertNull(LayoutModeGate.enterStandard());
        } finally {
            LayoutModeGate.releaseLayout(41);
        }
    }

    @Test public void oldCleanupCannotReleaseANewerLayoutOwner() {
        assertTrue(LayoutModeGate.reserveLayout(42));
        LayoutModeGate.releaseLayout(41);
        assertNull(LayoutModeGate.enterStandard());
        LayoutModeGate.releaseLayout(42);
        try (LayoutModeGate.StandardStart standard = LayoutModeGate.enterStandard()) {
            assertNotNull(standard);
        }
    }

    @Test public void changingPresetsKeepsAdmissionReservedAcrossGenerations() {
        assertTrue(LayoutModeGate.reserveLayout(44));
        try {
            assertFalse(LayoutModeGate.transferLayout(43, 45));
            assertTrue(LayoutModeGate.transferLayout(44, 45));
            LayoutModeGate.releaseLayout(44);
            assertNull(LayoutModeGate.enterStandard());
        } finally {
            LayoutModeGate.releaseLayout(45);
            LayoutModeGate.releaseLayout(44);
        }
        try (LayoutModeGate.StandardStart standard = LayoutModeGate.enterStandard()) {
            assertNotNull(standard);
        }
    }

    @Test public void cameraAndCommittedLayoutMirrorRemainAvailableWithoutAdmittingOtherModes() {
        assertTrue(LayoutModeGate.reserveLayout(46));
        try {
            assertTrue(LayoutModeGate.allowsSustainedToken("camview", false));
            assertTrue(LayoutModeGate.allowsSustainedToken("camview", true));
            assertFalse(LayoutModeGate.allowsSustainedToken("viewmirror", false));
            assertTrue(LayoutModeGate.allowsSustainedToken("viewmirror", true));
            assertFalse(LayoutModeGate.allowsSustainedToken("map", true));
            assertFalse(LayoutModeGate.allowsSustainedToken("cast", true));
            assertTrue(LayoutModeGate.allowsSustainedToken("layout:46", false));
        } finally {
            LayoutModeGate.releaseLayout(46);
        }
        assertTrue(LayoutModeGate.allowsSustainedToken("map", false));
    }

    @Test public void closingStandardLeaseTwiceDoesNotPermitOverlap() {
        LayoutModeGate.StandardStart a = LayoutModeGate.enterStandard();
        LayoutModeGate.StandardStart b = LayoutModeGate.enterStandard();
        assertNotNull(a);
        assertNotNull(b);
        a.close();
        a.close();
        assertFalse(LayoutModeGate.reserveLayout(43));
        b.close();
        assertTrue(LayoutModeGate.reserveLayout(43));
        LayoutModeGate.releaseLayout(43);
    }
}
