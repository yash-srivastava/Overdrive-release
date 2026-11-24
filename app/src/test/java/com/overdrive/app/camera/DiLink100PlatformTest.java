package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiLink100PlatformTest {

    @Test
    public void panoSuperFrameAcceptsTheFourCameraStrip() {
        // New E2 GS X pano_h:0 and Atto 3 EVO pano_h:2 both stream 5120x960.
        assertTrue(DiLink100Platform.isPanoSuperFrame(5120, 960));
    }

    @Test
    public void panoSuperFrameRejectsSingleCabinCameras() {
        // Atto 3 EVO id 0 is the 1920x1080 occupant-monitoring (cabin) camera.
        assertFalse(DiLink100Platform.isPanoSuperFrame(1920, 1080));
        assertFalse(DiLink100Platform.isPanoSuperFrame(1280, 720));
        assertFalse(DiLink100Platform.isPanoSuperFrame(3840, 2160));
    }

    @Test
    public void panoSuperFrameRejectsMissingSizes() {
        assertFalse(DiLink100Platform.isPanoSuperFrame(0, 0));
        assertFalse(DiLink100Platform.isPanoSuperFrame(5120, 0));
        assertFalse(DiLink100Platform.isPanoSuperFrame(-1, -1));
    }
}
