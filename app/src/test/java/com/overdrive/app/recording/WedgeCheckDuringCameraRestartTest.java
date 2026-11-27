package com.overdrive.app.recording;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WedgeCheckDuringCameraRestartTest {

    @Test
    public void skipsOneTickWhileTheCameraRestarts() {
        assertTrue(RecordingModeManager.skipWedgeCheckForCameraRestart(true, 0));
        // A restart still running a full tick later is no longer excused.
        assertFalse(RecordingModeManager.skipWedgeCheckForCameraRestart(true,
                RecordingModeManager.MAX_WEDGE_CHECKS_SKIPPED_FOR_CAMERA_RESTART));
    }

    @Test
    public void noRestartNeverSkips() {
        assertFalse(RecordingModeManager.skipWedgeCheckForCameraRestart(false, 0));
    }

    @Test
    public void wedgeDetectionHonoursTheSkip() throws Exception {
        String rmm = read("app/src/main/java/com/overdrive/app/recording/RecordingModeManager.java");
        int wedge = rmm.indexOf("boolean wedgeDetected = modeActive");
        assertTrue(wedge > 0);
        int skip = rmm.indexOf("&& !skipWedgeForCameraRestart", wedge);
        assertTrue(skip > wedge && skip < rmm.indexOf(';', wedge));
        assertTrue(rmm.contains("cameraRestarting = pipeline.isCameraRestartInProgress();"));
        String pipeline = read(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");
        assertTrue(pipeline.contains("current.isCameraRestartInProgress()"));
        String gpu = read("app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        assertTrue(gpu.contains("public boolean isCameraRestartInProgress() {\n        return restartInProgress.get();"));
    }

    private static String read(String path) throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        if (!java.nio.file.Files.exists(p)) p = java.nio.file.Paths.get("..").resolve(path);
        return new String(java.nio.file.Files.readAllBytes(p), "UTF-8");
    }
}
