package com.overdrive.app.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class CameraExplicitStopContractTest {
    @Test
    public void explicitStopCancelsWarmupAndUsesTerminalDiLink5Stop()
            throws Exception {
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String server = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");
        String warmup = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/AvcHalWarmup.java");
        String controller = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/daemon/"
                        + "CameraDaemonController.kt");

        assertTrue(daemon.contains(
                "private static final java.util.concurrent.atomic.AtomicLong CAMERA_START_GENERATION"));
        assertTrue(daemon.contains(
                "startGeneration != CAMERA_START_GENERATION.get()"));

        int explicit = daemon.indexOf(
                "public static boolean stopAllCamerasForExplicitUserStop()");
        int generic = daemon.indexOf(
                "public static boolean stopCamera(int viewId)", explicit);
        String terminal = daemon.substring(explicit, generic);
        int invalidate = terminal.indexOf(
                "CAMERA_START_GENERATION.incrementAndGet()");
        int consumer = terminal.indexOf("stopCameraConsumerLocked()");
        int hardware = terminal.indexOf(".stopHardwareProcessForExit()");
        assertTrue(invalidate >= 0);
        assertTrue(consumer > invalidate);
        int oem = terminal.indexOf(".stopPipelineBeforePanoTeardown()");
        assertTrue(oem > consumer);
        assertTrue(hardware > oem);
        assertTrue(terminal.contains(
                "return consumerStopped && oemStopped && hardwareStopped;"));
        assertTrue(daemon.contains(
                "private static final AtomicBoolean CAMERA_EXPLICITLY_STOPPED"));
        assertTrue(daemon.contains(
                "&& !CAMERA_EXPLICITLY_STOPPED.get()"));
        assertTrue(daemon.contains(
                "if (CAMERA_EXPLICITLY_STOPPED.getAndSet(false))"));
        assertTrue(daemon.contains("gpuPipeline.stopAndConfirm("));
        assertTrue(pipeline.contains("public boolean stopAndConfirm(long timeoutMs)"));
        assertTrue(pipeline.contains("if (confirmedStopInProgress)"));
        assertTrue(pipeline.contains(
                ".isCameraTerminalStopInProgress())"));
        assertTrue(pipeline.contains("while (starting || stopping)"));
        assertTrue(pipeline.contains("&& !pipelineTeardownWedged"));
        assertTrue(pipeline.contains(
                "if (!com.overdrive.app.daemon.CameraDaemon.isRunning())"));
        assertTrue(warmup.contains(
                "DiLink5Platform.isSelected()"));
        assertTrue(controller.contains(
                "val diLink5Selected = isDiLink5ModeSelected()"));
        assertTrue(controller.contains(
                "sendShutdownCommand(), diLink5Selected)"));
        assertTrue(controller.contains(
                "if (!shutdownAccepted || !diLink5Selected) return \"\""));
        assertTrue(controller.contains(
                "DiLink5Platform.refreshActiveMode()"));
        assertTrue(controller.contains(
                "DiLink5Platform.isSelected()"));
        int disableWatchdog = controller.indexOf(
                "append(\"rm -f /data/local/tmp/start_cam_daemon.sh");
        int gracefulWait = controller.indexOf(
                "append(gracefulExitWait)", disableWatchdog);
        int forceKill = controller.indexOf(
                "psAwkKillLine(\"cam_daemon\")", gracefulWait);
        assertTrue(disableWatchdog >= 0);
        assertTrue(gracefulWait > disableWatchdog);
        assertTrue(forceKill > gracefulWait);

        int genericStop = daemon.indexOf(
                "public static boolean stopAllCameras(boolean forceStop)");
        int genericEnd = daemon.indexOf(
                "// GPU pipeline handles camera internally", genericStop);
        assertFalse(daemon.substring(genericStop, genericEnd)
                .contains("stopHardwareProcessForExit"));

        assertTrue(server.contains(
                "CameraDaemon.stopAllCamerasForExplicitUserStop()"));
        assertTrue(server.contains("HttpResponse.sendJson(out, 503"));

        int shutdown = daemon.indexOf(
                "private static void stopDiLink5HardwareForProcessExit");
        int shutdownEnd = daemon.indexOf(
                "private static boolean armTerminalShutdownDeadline", shutdown);
        String shutdownStop = daemon.substring(shutdown, shutdownEnd);
        assertTrue(shutdownStop.indexOf("stopCameraConsumerLocked()")
                < shutdownStop.indexOf("stopHardwareProcessForExit()"));
        assertTrue(daemon.contains(
                "Runtime.getRuntime().addShutdownHook(new Thread(() -> {\n"
                        + "                running.set(false);"));
    }

    @Test
    public void restartAndRemoteStopsUseTheSharedTerminalBarrier()
            throws Exception {
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String surveillance = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        String http = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");
        String tcp = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/TcpCommandServer.java");
        String client = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/client/CameraDaemonClient.java");

        assertTrue(daemon.contains(
                "private static final AtomicBoolean CAMERA_RESTART_PREPARED"));
        assertTrue(daemon.contains(
                "public static boolean stopAllCamerasForProcessRestart()"));
        assertTrue(daemon.contains(
                "public static void abortCameraRestartPreparation()"));
        assertTrue(surveillance.contains(
                "CameraDaemon.stopAllCamerasForProcessRestart()"));
        assertTrue(surveillance.contains(
                "CameraDaemon.abortCameraRestartPreparation()"));
        int prepare = surveillance.indexOf(
                "private static void handlePrepareRestart");
        int heatmap = surveillance.indexOf(
                "private static void sendHeatmap", prepare);
        assertFalse(surveillance.substring(prepare, heatmap)
                .contains("coldStartInProgress.compareAndSet"));
        assertTrue(http.contains(
                "path.startsWith(\"/api/stop/\")"));
        assertTrue(countOccurrences(
                http, "CameraDaemon.stopAllCamerasForExplicitUserStop()") >= 2);
        assertTrue(client.contains(
                "cmd.put(\"explicit\", cameraIds == null);"));
        assertTrue(tcp.contains(
                "CameraDaemon.stopAllCamerasForExplicitUserStop()"));
        assertTrue(tcp.contains(
                "response.put(\"status\", stopConfirmed ? \"ok\" : \"error\")"));
        assertTrue(tcp.contains(
                "CameraDaemon.stopAllCameras(forceStop)"));
        assertTrue(client.contains(
                "if (\"ok\".equals(response.optString(\"status\")))"));
    }

    @Test
    public void nativeCloseVerdictPropagatesToConfirmedStop()
            throws Exception {
        String backend = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/"
                        + "DiLink5QCarCamBackend.java");
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "GpuSurveillancePipeline.java");

        assertTrue(backend.contains("public boolean close()"));
        assertOrdered(
                backend,
                "released = nativeRelease(nativeHandle);",
                "if (released) {",
                "nativeHandle = 0;");
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private OwnerRetirementResult closeInternal(\n"
                                + "            boolean stopAvm,")),
                "stop();",
                "nativeRelease(nativeHandle)",
                "if (released || !ownsNativeSession)",
                "stopHardwareProcessForOwner(",
                "isCompleteOwnershipRelease(released, processStopped)",
                "if (cleanRelease) {\n"
                        + "            return OwnerRetirementResult.COMPLETE;",
                "return deadlineElapsedMs != Long.MAX_VALUE",
                "? OwnerRetirementResult.BUSY\n"
                        + "                : OwnerRetirementResult.FAILED;");
        assertTrue(camera.contains(
                "private boolean closeCameraForPath(Object cam)"));
        assertTrue(camera.contains(
                "boolean clean = glReleased\n"
                        + "                    "
                        + "&& backend.closeWithRetirementRetry("));
        assertTrue(camera.contains(
                "!preserveDiLink5Avm);"));
        assertTrue(camera.contains(
                "stopClean = closeCameraForPath(cameraObj);"));
        assertTrue(pipeline.contains(
                "cameraStopClean = camera.stop();"));
        assertTrue(pipeline.contains(
                "cameraStopClean = false;\n"
                        + "                    logger.warn(\"stop: camera.stop failed: "));
    }

    @Test
    public void startEpochAndTerminalChecksReachNativePublication()
            throws Exception {
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "GpuSurveillancePipeline.java");
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String backend = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/"
                        + "DiLink5QCarCamBackend.java");
        String recording = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/recording/"
                        + "RecordingModeManager.java");
        String surveillance = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "SurveillanceApiHandler.java");
        String http = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");
        String oem = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "OemDashcamApiHandler.java");

        assertTrue(daemon.contains("public static long captureCameraStartEpoch()"));
        assertTrue(daemon.contains(
                "public static boolean isCameraStartEpochCurrent(long startEpoch)"));
        assertTrue(daemon.contains(
                "gpuPipeline.start(!viewOnly, startGeneration);"));
        assertTrue(pipeline.contains(
                "public void start(boolean autoStartRecording, long startEpoch)"));
        assertTrue(pipeline.contains("camera.start(startEpoch);"));
        assertTrue(pipeline.contains(
                "requireCurrentCameraStartEpoch(startEpoch, \"pipeline publication\")"));
        assertTrue(camera.contains("public void start(long startEpoch)"));
        assertTrue(camera.contains(
                "requireCurrentStartEpoch(startEpoch, \"camera open\")"));
        assertTrue(camera.contains(
                "&& isCurrentStartEpoch(startEpoch)"));
        assertTrue(camera.contains(
                "new com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend(\n"
                        + "                            cameraId, activeStartEpoch);"));
        assertTrue(backend.contains(
                "private static boolean ensureHardwareProcess("));
        assertFalse(backend.contains(
                "private static synchronized boolean ensureHardwareProcess("));
        assertOrdered(
                backend.substring(backend.indexOf(
                        "startedProcess = processBuilder.start();")),
                "installHardwareSessionAfterSpawn(",
                "if (stopProcess(process)) {",
                "markCleanReleaseIfNoOwnership();",
                "return false;");
        assertTrue(backend.contains(
                "if (!isStartAllowed(startEpoch, acquisitionGeneration)) {\n"
                        + "                closeAfterCancelledAcquisition();\n"
                        + "                return false;"));
        assertTrue(recording.contains(
                "pipeline.start(false, cameraStartEpoch);"));
        assertFalse(recording.contains("pipeline.start(false);"));
        assertTrue(surveillance.contains(
                "pipeline.start(false, cameraStartEpoch);"));
        assertFalse(surveillance.contains("pipeline.start(false);"));
        assertTrue(http.contains(
                "pipeline.start(false, cameraStartEpoch);"));
        assertFalse(http.contains("pipeline.start();"));
        assertTrue(oem.contains(
                "pano.start(false, cameraStartEpoch);"));
        assertFalse(oem.contains("pano.start(false);"));
        assertTrue(oem.contains(
                ".isCameraStartEpochCurrent(cameraStartEpoch)"));
    }

    @Test
    public void selectedDiLink5SkipsLegacyAvcAtEntriesAndTicks()
            throws Exception {
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String warmup = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/AvcHalWarmup.java");

        int entry = daemon.indexOf(
                "public static void startAvcKeepAliveIfNeeded()");
        int initialization = daemon.indexOf(
                "com.overdrive.app.camera.AvcHalWarmup local", entry);
        assertTrue(entry >= 0);
        assertTrue(daemon.indexOf(
                "DiLink5Platform.isSelected()", entry) < initialization);
        assertTrue(warmup.contains(
                "public synchronized void startKeepAlive() {\n"
                        + "        if (isDiLink5Selected())"));
        assertTrue(warmup.contains(
                "&& !isDiLink5Selected()"));
        assertTrue(warmup.contains(
                "|| isDiLink5Selected())"));
        assertTrue(warmup.contains(
                "public static boolean ensureAvcAlive() {\n"
                        + "        if (isDiLink5Selected()) return false;"));
        assertTrue(countOccurrences(warmup, "isDiLink5Selected()") >= 9);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static void assertOrdered(String source, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int position = source.indexOf(needle, previous + 1);
            assertTrue("Missing or out of order: " + needle, position > previous);
            previous = position;
        }
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 6 && current != null;
                depth++, current = current.getParent()) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
