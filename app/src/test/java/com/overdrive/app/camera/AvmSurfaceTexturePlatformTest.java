package com.overdrive.app.camera;

import static org.junit.Assert.*;

import org.junit.Test;

public class AvmSurfaceTexturePlatformTest {
    @Test
    public void transportRequiresExplicitSelection() {
        assertTrue(AvmSurfaceTexturePlatform.isMode("surfacetexture"));
        assertTrue(AvmSurfaceTexturePlatform.isMode(" SurfaceTexture "));
        for (String mode : new String[]{null, "", "default", "dilink4", "dilink5",
                "camera2", "atto2", "legacy", "auto"}) {
            assertFalse(mode, AvmSurfaceTexturePlatform.isMode(mode));
            assertFalse(AvmSurfaceTexturePlatform.shouldResolvePanoramicTag(
                mode, true, false, false));
        }
    }

    @Test
    public void halDiscoveryPreservesManualProfileAndFrameValidatedSelections() {
        String mode = AvmSurfaceTexturePlatform.MODE;
        assertTrue(AvmSurfaceTexturePlatform.shouldResolvePanoramicTag(
            mode, true, false, false));
        assertFalse(AvmSurfaceTexturePlatform.shouldResolvePanoramicTag(
            mode, true, true, false));
        assertFalse(AvmSurfaceTexturePlatform.shouldResolvePanoramicTag(
            mode, true, false, true));
        assertFalse(AvmSurfaceTexturePlatform.shouldResolvePanoramicTag(
            mode, false, false, false));
    }
}
