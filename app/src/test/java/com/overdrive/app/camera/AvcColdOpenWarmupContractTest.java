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
    public void releaseCoordinatorUsesTypedBinderAndRetryKeepsValidCache()
            throws IOException {
        String coordinator = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/BydCameraCoordinator.java");
        assertTrue(coordinator.contains("typedServiceProxy.getCurrentCameraUser()"));
        assertTrue(coordinator.contains("user.getPackageName()"));
        String rules = readRepositoryFile("app/proguard-rules.pro");
        assertTrue(rules.contains("-keep class android.hardware.IBYDCameraService$*"));
        assertTrue(rules.contains("-keep class android.hardware.IBYDCameraUser$*"));
        String warmup = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/AvcHalWarmup.java");
        String retry = method(warmup, "public static void requireFreshWarmupForAccRetry(",
                "    /** True only while");
        assertFalse(retry.contains("lastWarmupCompletedNanos = Long.MIN_VALUE"));
        assertTrue(retry.contains("onVendorOpenFailure("));
        String reopen = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        assertTrue(reopen.contains("!DiLink100Platform.isAvmPathActive()"));
        assertTrue(reopen.contains("earlyProbeFinished.get()"));
    }

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
                "AvcHandoffSettlePolicy.requiresLaunch(",
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

        // A receipt is issued only from the handoff worker after the original
        // 4 s settle and RUNNING post-probe. The ordinary pre-open pidof is the
        // only receipt validation probe, and UNKNOWN still launches on cold open.
        assertOrdered(warmup,
                "AvcProbeResult beforeLaunch = probeAvcProcess()",
                "receipt.proof.consumeIfEligible(",
                "AvcHandoffSettlePolicy.requiresLaunch(",
                "launchAvc()",
                "Thread.sleep(HAL_WARMUP_DELAY_MS)",
                "AvcProbeResult postWarmup = probeAvcProcess()",
                "lastWarmupCompletedNanos = System.nanoTime()",
                "receipt.proof.issueFromCompletedPrewarm(");
        assertTrue(warmup.contains("if (prewarmWorker && receipt != null && avcReady"));
        assertTrue(source.contains("prewarmForAccHandoff("));
        assertTrue(source.contains("requireFreshWarmupForAccRetry("));
        assertTrue(source.contains(
                "recoveryLaunch, beforeLaunch.state == AvcProbeState.RUNNING"));

        String reopen = method(readRepositoryFile(
                        "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java"),
                "public void reopenCamera() {",
                "\n    /**\n     * Legacy-only bounded AVMCamera open");
        assertOrdered(reopen,
                "AvcHalWarmup.newHandoffSettleReceipt()",
                "AvcHalWarmup.prewarmForAccHandoff(",
                "Started AVC HAL warmup alongside camera handoff",
                "openLegacyCameraWithHardTimeout(",
                "AvcHalWarmup.requireFreshWarmupForAccRetry(handoffSettle)");

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
