package com.overdrive.app.recording;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WedgeCheckDuringCameraRestartTest {

    @Test
    public void periodicTickSkipsOncePerRestartEpisode() {
        long last = 0L;
        assertTrue(RecordingModeManager.skipWedgeCheckForCameraRestart(true, 7L, last));
        last = 7L;
        // Same restart still running at the next periodic tick: checked as before.
        assertFalse(RecordingModeManager.skipWedgeCheckForCameraRestart(true, 7L, last));
        // A later restart gets its own skip.
        assertTrue(RecordingModeManager.skipWedgeCheckForCameraRestart(true, 8L, last));
    }

    @Test
    public void nonPeriodicCallersNeverSkipOrUseUpTheSkip() {
        assertFalse(RecordingModeManager.skipWedgeCheckForCameraRestart(false, 7L, 0L));
        // last stays 0 because the caller only records a skip it took: the periodic tick still skips.
        assertTrue(RecordingModeManager.skipWedgeCheckForCameraRestart(true, 7L, 0L));
    }

    @Test
    public void noRestartNeverSkips() {
        assertFalse(RecordingModeManager.skipWedgeCheckForCameraRestart(true, 0L, 0L));
        assertFalse(RecordingModeManager.skipWedgeCheckForCameraRestart(true, 0L, 5L));
    }

    @Test
    public void wedgeDetectionHonoursTheSkip() throws Exception {
        String rmm = read("app/src/main/java/com/overdrive/app/recording/RecordingModeManager.java");
        int wedge = rmm.indexOf("boolean wedgeDetected = modeActive");
        assertTrue(wedge > 0);
        int skip = rmm.indexOf("&& !skipWedgeForCameraRestart", wedge);
        assertTrue(skip > wedge && skip < rmm.indexOf(';', wedge));
        assertTrue(rmm.contains("cameraRestartEpisode = pipeline.cameraRestartEpisode();"));
        assertTrue(rmm.contains("\"periodic\".equals(reason), cameraRestartEpisode,"));
        assertTrue(rmm.contains(
                "if (skipWedgeForCameraRestart) lastCameraRestartEpisodeSkipped = cameraRestartEpisode;"));
        String pipeline = read(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");
        assertTrue(pipeline.contains("current.cameraRestartEpisode()"));
        String gpu = read("app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        // Every restart claim starts a new episode.
        assertFalse(gpu.replace(
                "private boolean claimRestart() {\n"
                        + "        if (!restartInProgress.compareAndSet(false, true)) return false;", "")
                .contains("restartInProgress.compareAndSet(false, true)"));
        assertTrue(gpu.contains("restartEpisode.incrementAndGet();"));
    }

    private static String read(String path) throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        if (!java.nio.file.Files.exists(p)) p = java.nio.file.Paths.get("..").resolve(path);
        return new String(java.nio.file.Files.readAllBytes(p), "UTF-8");
    }
}
