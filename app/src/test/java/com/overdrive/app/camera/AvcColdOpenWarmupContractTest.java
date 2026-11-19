package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class AvcColdOpenWarmupContractTest {

    @Test
    public void primaryCameraColdOpensShareOneWarmupBoundary() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String startCamera = method(source,
                "private void startCamera() throws Exception {",
                "\n    /**\n     * Opens camera via AVMCamera reflection.");

        assertOrdered(startCamera,
                "AvcHalWarmup.warmupBeforeColdOpen(",
                "() -> (running || legacyCameraInitializationInFlight.get())",
                "CameraDaemon.isCameraStartEpochCurrent(activeStartEpoch)",
                "requireCurrentStartEpoch(activeStartEpoch, \"post-warmup camera acquisition\")",
                "startCameraViaAvmReflection(cameraId);");
        assertTrue(source.contains("AvcHalWarmup.isColdOpenWarmupInProgress()"));

        String restart = method(source,
                "private void restartCameraAfterError() {",
                "\n    /**\n     * True while this pipeline holds an open AVMCamera handle.");
        assertOrdered(restart,
                "AvcHalWarmup.warmupBeforeColdOpen(",
                "restartInProgress.get()",
                "Thread cameraOpenThread = new Thread",
                "long openSoftTimeout = 2_000L;");

        String pipelineStart = method(source,
                "public void start(long startEpoch) throws Exception {",
                "\n    private void cancelPendingReacquireRetry");
        assertOrdered(pipelineStart,
                "if (USE_DILINK5_QCARCAM_PATH)",
                "initTimeoutMs = 35_000L",
                "else if (USE_DILINK4_AVM_PATH)",
                "initTimeoutMs = 20_000L",
                "AvcHalWarmup.coldOpenWarmupTimeoutMs()",
                "+ GL_THREAD_WARMUP_TIMEOUT_MS",
                "java.util.concurrent.TimeUnit.MILLISECONDS");
    }

    @Test
    public void oemCameraColdOpenUsesTheSameBoundary() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/OemDashcamPipeline.java");
        String open = method(source,
                "private void openCameraAndAttach(",
                "\n    private static boolean isLegacyAvmMode()");

        assertOrdered(open,
                "AvcHalWarmup.warmupBeforeColdOpen(",
                "isStartAdmissionCurrent(",
                "requireStartCurrent(",
                "\"post-warmup camera acquisition\"",
                "isLegacyAvmMode()",
                "openLegacyCameraAndAttachWithHardTimeout()",
                "openCameraAndAttachAfterWarmup()");

        String actualOpen = method(source,
                "private void openCameraAndAttachAfterWarmup() throws Exception {",
                "\n    private void installFrameCallback()");
        assertOrdered(actualOpen,
                "Class.forName(\"android.hardware.AVMCamera\")",
                "getDeclaredMethod(\"open\")");

        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "OemDashcamApiHandler.java");
        String startPipeline = method(
                handler,
                "private static com.overdrive.app.camera.OemDashcamPipeline "
                        + "startPipeline(",
                "\n    private static boolean waitForEncoderFormat(");
        assertFalse(startPipeline.contains("warmupAndWait()"));
        assertOrdered(startPipeline,
                "isLifecycleRevisionCurrent(expectedRevision)",
                "p.start(",
                "cameraStartEpoch",
                "() -> isLifecycleRevisionCurrent(expectedRevision)");
    }

    @Test
    public void warmupResolvesTheFirmwareLauncherAndCoalescesDuplicates()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/AvcHalWarmup.java");

        assertTrue(source.contains("\"android.intent.action.MAIN\""));
        assertTrue(source.contains("\"android.intent.category.LAUNCHER\""));
        assertTrue(source.contains("\"-p\", \"com.byd.avc\""));
        assertFalse(source.contains("\"com.byd.avc/.MainActivity\""));
        assertTrue(source.contains("private static final Object WARMUP_LANE"));
        assertTrue(source.contains("WARMUP_REUSE_WINDOW_MS"));
        assertTrue(source.contains("lastWarmupCompletedNanos"));

        String warmup = method(source,
                "public boolean warmupAndWait(\n"
                        + "            java.util.function.BooleanSupplier stillRequired)",
                "\n    // ==================== Keep-Alive Watchdog");
        assertOrdered(warmup,
                "isWarmupStillRequired(stillRequired)",
                "AvcProbeResult beforeLaunch = probeAvcProcess()",
                "suppressing launcher Activity",
                "beforeLaunch.state != AvcProbeState.RUNNING",
                "launchAvc()",
                "Thread.sleep(HAL_WARMUP_DELAY_MS)",
                "suppressing recovery relaunch",
                "AvcProbeResult postWarmup = probeAvcProcess()",
                "force-restart escalation",
                "forceRestartAvc(stillRequired)",
                "if (recoveryRestartAttempted)",
                "Thread.sleep(HAL_WARMUP_DELAY_MS)",
                "postWarmup = probeAvcProcess()");
        assertTrue(warmup.contains(
                "skipping foreground Activity launch"));
        assertTrue(source.contains(
                "COLD_OPEN_WARMUP_TIMEOUT_MS = 35_000L"));

        // #805 follow-up: a matching PID never substitutes for the real 4 s
        // settle. A receipt is earned only after that settle and its probe.
        assertTrue(source.contains("HAL_WARMUP_DELAY_MS = 4000"));
        assertOrdered(warmup,
                "AvcProbeResult beforeLaunch = probeAvcProcess()",
                "launchAvc()",
                "Thread.sleep(HAL_WARMUP_DELAY_MS)",
                "AvcProbeResult postWarmup = probeAvcProcess()");
        String postSettle = warmup.substring(
                warmup.indexOf("Thread.sleep(HAL_WARMUP_DELAY_MS)"));
        assertOrdered(postSettle,
                "AvcProbeResult postWarmup = probeAvcProcess()",
                "lastWarmupCompletedNanos = System.nanoTime()",
                "receipt.completedNanos = lastWarmupCompletedNanos");
        assertTrue(warmup.contains("current.pid == receipt.settledPid"));
        assertTrue(warmup.contains("receiptCheckRejected"));
        assertTrue(warmup.contains("beforeLaunch.state == AvcProbeState.UNKNOWN"));
        assertTrue(warmup.contains("isDiLink100AvmPath()) receipt = null"));
        assertTrue(source.contains("HANDOFF_SETTLE_RECEIPT_MAX_AGE_MS = 30_000L"));

        String reopen = method(readRepositoryFile(
                        "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java"),
                "public void reopenCamera() {",
                "\n    /**\n     * Legacy-only bounded AVMCamera open");
        assertOrdered(reopen,
                "AvcHalWarmup.newHandoffSettleReceipt()",
                "Started AVC HAL warmup alongside camera handoff",
                "openLegacyCameraWithHardTimeout(");
        assertTrue(reopen.contains(
                "\"ACC ON camera reopen\", handoffSettle"));
        assertTrue(reopen.contains(
                "\"ACC ON camera reopen retry\", handoffSettle"));

        String watchdog = method(source,
                "private void startKeepAliveWorkerLocked",
                "\n    private void scheduleKeepAliveHandoffLocked");
        assertOrdered(watchdog,
                "if (!isLegacyCameraConsumerActive())",
                "skipping AVC probe/launch",
                "AvcProbeResult probe = probeAvcProcess()",
                "confirmAvcAbsent(probe)",
                "|| !isLegacyCameraConsumerActive())",
                "boolean launched = launchAvc()",
                "AvcProbeResult postLaunch = probeAvcAfterLaunchDelay()",
                "|| !isLegacyCameraConsumerActive())",
                "forceRestartAvc(",
                "isKeepAliveWorkerCurrent(",
                "isLegacyCameraConsumerActive()");

        String consumerGate = method(source,
                "private static boolean isLegacyCameraConsumerActive()",
                "\n    private void resetKeepAliveLaunchBackoff()");
        assertTrue(consumerGate.contains(
                "camera consumer state unavailable"));
        assertTrue(consumerGate.contains("return false;"));
    }

    private static String method(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue("method start not found: " + start, from >= 0);
        assertTrue("method end not found: " + end, to > from);
        return source.substring(from, to);
    }

    private static void assertOrdered(String source, String... needles) {
        int cursor = -1;
        for (String needle : needles) {
            int next = source.indexOf(needle, cursor + 1);
            assertTrue("missing or out of order: " + needle, next > cursor);
            cursor = next;
        }
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
