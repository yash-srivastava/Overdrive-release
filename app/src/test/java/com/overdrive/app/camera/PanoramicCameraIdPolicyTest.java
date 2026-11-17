package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PanoramicCameraIdPolicyTest {
    @Test public void manualWinsOverHalAndProfile() {
        PanoramicCameraIdPolicy.Selection s = PanoramicCameraIdPolicy.choose(1, 2, true, true, 0);
        assertEquals(2, s.cameraId);
        assertEquals(PanoramicCameraIdPolicy.MANUAL, s.source);
        assertTrue(s.usesSavedId());
    }

    @Test public void validatedProbeWinsOverHal() {
        PanoramicCameraIdPolicy.Selection s = PanoramicCameraIdPolicy.choose(1, 2, false, true, 0);
        assertEquals(2, s.cameraId);
        assertEquals(PanoramicCameraIdPolicy.VALIDATED_PROBE, s.source);
    }

    @Test public void unvalidatedSavedIdNeverOverridesSystem() {
        PanoramicCameraIdPolicy.Selection s = PanoramicCameraIdPolicy.choose(1, 2, false, false, 0);
        assertEquals(0, s.cameraId);
        assertEquals(PanoramicCameraIdPolicy.HAL_TAG, s.source);
        assertFalse(s.usesSavedId());
    }

    @Test public void invalidSavedAndInvalidHalFallBackToProfile() {
        PanoramicCameraIdPolicy.Selection s = PanoramicCameraIdPolicy.choose(1, 98, true, true, 9);
        assertEquals(1, s.cameraId);
        assertEquals(PanoramicCameraIdPolicy.PROFILE_FALLBACK, s.source);
        assertFalse(s.usesSavedId());
    }

    @Test public void noSystemMappingIsExplicitlyUnverified() {
        PanoramicCameraIdPolicy.Selection s = PanoramicCameraIdPolicy.choose(0, -1, false, false, -1);
        assertEquals(0, s.cameraId);
        assertEquals(PanoramicCameraIdPolicy.PROFILE_FALLBACK, s.source);
    }

    @Test public void savedGeometryMustBeDivisibleIntoFourEqualSlices() {
        assertTrue(CameraConfigResolver.isUsablePanoramicGeometry(5120, 960));
        assertTrue(CameraConfigResolver.isUsablePanoramicGeometry(5120, 720));
        assertFalse(CameraConfigResolver.isUsablePanoramicGeometry(5122, 960));
        assertFalse(CameraConfigResolver.isUsablePanoramicGeometry(5120, 721));
        assertFalse(CameraConfigResolver.isUsablePanoramicGeometry(0, 960));
    }

    @Test public void invalidProfileDoesNotTurnIntoAnArbitraryCamera() {
        assertEquals(-1, PanoramicCameraIdPolicy.choose(20, -1, false, false, 12).cameraId);
    }
}
