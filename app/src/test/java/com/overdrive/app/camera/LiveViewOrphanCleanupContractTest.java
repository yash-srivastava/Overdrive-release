package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source-level guards for abandoned Live View cold-start cleanup. */
public class LiveViewOrphanCleanupContractTest {

    @Test
    public void everyExternalColdStartGetsAnOwnershipWatchdog() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/StreamingApiHandler.java");
        String httpServer = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");

        assertTrue(source.contains("LIVE_VIEW_ORPHAN_START_WAIT_MS = 60_000L"));
        assertTrue(source.contains("LIVE_VIEW_ORPHAN_IDLE_MS = 30_000L"));
        assertTrue(countOccurrences(source, "armLiveViewOrphanWatchdog(") >= 4);

        String ensure = between(source,
                "private static boolean ensurePanoStartedNonBlocking(",
                "static void armLiveViewOrphanWatchdog(");
        assertOrdered(ensure,
                "pano.start()",
                "coldStart.start()",
                "armLiveViewOrphanWatchdog(pano)");
        assertFalse(ensure.contains("warmup.warmupAndWait()"));

        String watchdog = between(source,
                "static void armLiveViewOrphanWatchdog(",
                "public static boolean handle(");
        assertTrue(watchdog.contains(
                "liveViewOrphanWatchdogRequestSequence++"));
        assertTrue(watchdog.contains(
                "runLiveViewOrphanWatchdogLoop"));
        assertTrue(watchdog.contains(
                "isLiveViewOrphanWatchdogRequestCurrent("));
        assertTrue(watchdog.contains(
                "hasPendingLiveViewStartupOwner("));
        assertTrue(watchdog.contains(
                "relinquishLiveViewStartupOwner("));
        assertTrue(watchdog.contains(
                "live-view startup lease ended"));
        assertOrdered(watchdog,
                "&& !pipeline.isRunning()",
                "long generation = pipeline.getLifecycleGeneration()",
                "if (pipeline.isStreamingEnabled())",
                "stopIfLiveViewStartupOrphaned(generation)");
        assertFalse(watchdog.contains(
                "AccMonitor.isAccStateAuthoritative()"));
        assertFalse(watchdog.contains("AccMonitor.isAccOn()"));

        String websocket = between(httpServer,
                "private void streamH264ToWebSocket(",
                "private void sendWebSocketBinaryFrame(");
        assertOrdered(websocket,
                "if (!pipeline.isRunning())",
                "StreamingApiHandler.armLiveViewOrphanWatchdog(pipeline)",
                "pipeline.start(false, cameraStartEpoch)",
                "pipeline.enableStreaming(",
                "pipeline.registerExternalStreamClient(callback)");
    }

    @Test
    public void diagnosticsPreviewColdStartHasAnIdleLeaseAndOwnerSafeStop()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "SurveillanceApiHandler.java");

        assertTrue(source.contains(
                "CAMERA_PREVIEW_START_WAIT_MS = 60_000L"));
        assertTrue(source.contains(
                "CAMERA_PREVIEW_IDLE_LEASE_MS = 30_000L"));

        String coldStart = between(
                source,
                "private static boolean requestColdStartAsync(",
                "private static void noteCameraPreviewRequest()");
        assertOrdered(
                coldStart,
                "CameraDaemon.captureCameraStartEpoch()",
                "CameraDaemon.isCameraStartEpochCurrent(",
                "pipeline.start(false, cameraStartEpoch)",
                "coldStartInProgress.set(false)",
                "armCameraPreviewOrphanWatchdog(pipeline)");

        String watchdog = between(
                source,
                "private static void armCameraPreviewOrphanWatchdog(",
                "private static void sendWarmingUp(");
        assertTrue(watchdog.contains(
                "cameraPreviewWatchdogRequestSequence++"));
        assertTrue(watchdog.contains(
                "runCameraPreviewWatchdogLoop"));
        assertTrue(watchdog.contains(
                "isCameraPreviewWatchdogRequestCurrent("));
        assertTrue(watchdog.contains(
                "hasActiveCameraPreviewLeaseOwner("));
        assertTrue(watchdog.contains(
                "relinquishCameraPreviewLeaseOwner("));
        assertTrue(watchdog.contains(
                "diagnostics preview lease ended"));
        assertOrdered(
                watchdog,
                "&& !pipeline.isRunning()",
                "long generation = pipeline.getLifecycleGeneration()",
                "lastCameraPreviewRequestElapsedMs",
                "CAMERA_PREVIEW_IDLE_LEASE_MS",
                "synchronized (cameraPreviewLeaseLock)",
                "pipeline.getLifecycleGeneration()",
                "pipeline.stopIfLiveViewStartupOrphaned(generation)");
        assertTrue(watchdog.contains("watchdog.setDaemon(true)"));

        String send = between(
                source,
                "private static void sendCameraPreview(",
                "\n        if (jpegBytes == null || jpegBytes.length == 0)");
        assertOrdered(
                send,
                "noteCameraPreviewRequest()",
                "requestColdStartAsync(gpuPipeline)");
    }

    @Test
    public void nativeLaneRetriesOutlastLegacyColdStartAndUseMonotonicTime()
            throws Exception {
        String streaming = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/StreamingApiHandler.java");
        String blindSpot = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/roadsense/overlay/"
                        + "BlindSpotControl.kt");

        assertTrue(streaming.contains(
                "CAMVIEW_ARM_DEADLINE_MS = 60_000L"));
        assertTrue(blindSpot.contains(
                "REARM_DEADLINE_MS = 60_000L"));
        String retry = between(
                blindSpot,
                "private fun armWithRetry(gen: Int)",
                "/** GET /api/bs/status");
        assertTrue(retry.contains(
                "android.os.SystemClock.elapsedRealtime()"));
        assertFalse(retry.contains("System.currentTimeMillis()"));
    }

    @Test
    public void streamIdleShutdownUsesMonotonicTime() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/streaming/"
                        + "WebSocketStreamServer.java");

        assertTrue(source.contains("lastClientDisconnectElapsedMs"));
        assertTrue(countOccurrences(
                source, "android.os.SystemClock.elapsedRealtime()") >= 5);
        assertTrue(source.contains(
                "long idleTime = android.os.SystemClock.elapsedRealtime()"));
        assertFalse(source.contains("lastClientDisconnectTime"));
    }

    @Test
    public void boundedCameraLifecycleWaitsIgnoreWallClockAdjustments()
            throws Exception {
        String oem = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "OemDashcamApiHandler.java");
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "GpuSurveillancePipeline.java");
        String encoder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "HardwareEventRecorderGpu.java");
        String engine = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "SurveillanceEngineGpu.java");
        String sampler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/"
                        + "HighResPreviewSampler.java");

        assertMonotonicWait(between(
                oem,
                "private static boolean waitForEncoderFormat(",
                "/**\n     * Boot-time hook:"));
        assertMonotonicWait(between(
                pipeline,
                "private synchronized void scheduleStorageReadyRetry(",
                "private synchronized void cancelStorageReadyRetry()"));
        assertMonotonicWait(between(
                encoder,
                "public boolean waitForFormat(long timeoutMs)",
                "public void clearStreamCallback()"));
        assertMonotonicWait(between(
                encoder,
                "private boolean waitForFinalizers(long timeoutMs)",
                "public void flushAndClose()"));
        assertMonotonicWait(between(
                engine,
                "private boolean drainSegmentMetadata(long timeoutMs)",
                "public void enable()"));

        String samplerRelease = sampler.substring(
                sampler.indexOf("public void release()"));
        assertMonotonicWait(samplerRelease);
    }

    @Test
    public void orphanStopChecksEveryCameraOwnerAndPinsGeneration() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "GpuSurveillancePipeline.java");
        String cleanup = between(source,
                "public boolean stopIfLiveViewStartupOrphaned(",
                "private void disableStreamingLocked(");

        assertTrue(cleanup.contains("actualGeneration != expectedGeneration"));
        assertTrue(cleanup.contains("streamLifecycleLock.lock()"));
        assertTrue(cleanup.contains("bsLifecycleLock.lock()"));
        assertTrue(cleanup.contains("streamingEnabled"));
        assertTrue(cleanup.contains("currentMode != Mode.IDLE"));
        assertTrue(cleanup.contains("recordingMode"));
        assertTrue(cleanup.contains("recordingAdmissionInFlight"));
        assertTrue(cleanup.contains("surveillanceAdmissionInFlight"));
        assertTrue(cleanup.contains("pendingRecordingPrefix != null"));
        assertTrue(cleanup.contains("surveillance.isActive()"));
        assertTrue(cleanup.contains("blindSpotEnabled || bsEnabling"));
        assertTrue(cleanup.contains("camViewActive || camViewEnabling"));
        assertTrue(cleanup.contains("keepAlivePredicate"));
        assertTrue(cleanup.contains("hasExplicitCameraCommandOwner()"));
        assertTrue(cleanup.contains(
                "hasPendingLiveViewStartupOwner(this)"));
        assertTrue(cleanup.contains(
                "hasActiveCameraPreviewLeaseOwner(this)"));
        assertTrue(cleanup.contains(
                "hasOemCameraOwnerOrTransitionFailSafe("));
        assertTrue(cleanup.contains(
                "transient owner settled after "));
        assertTrue(cleanup.contains(
                "late owner settled after "));
        assertTrue(cleanup.contains("isDilink4ModeActiveStatic()"));
        assertTrue(cleanup.contains("DiLink5Platform.isEnabled()"));
        assertOrdered(cleanup,
                "hasLateNonLaneCameraOwnerFailSafe(",
                "stopping = true;",
                "streamLifecycleLock.unlock();",
                "stop();");
    }

    @Test
    public void releasedExternalOwnersTriggerOneGenerationSafeLegacyTeardownAudit()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "GpuSurveillancePipeline.java");
        String oemApi = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "OemDashcamApiHandler.java");

        assertTrue(source.contains(
                "OWNER_RELEASE_AUDIT_DELAY_MS = 2_000L"));
        assertTrue(source.contains(
                "OWNER_RELEASE_AUDIT_MAX_WAIT_MS = 60_000L"));
        assertTrue(source.contains(
                "thread.setDaemon(true)"));
        assertOrdered(source,
                "onGpuPipelineStopCommitted(this)",
                "cancelOwnerlessPipelineAudit()",
                "CameraDaemon.stopAvcKeepAlive()");

        String audit = between(source,
                "private void scheduleOwnerlessPipelineAudit(",
                "private void disableStreamingLocked()");
        assertOrdered(audit,
                "expectedGeneration = pipelineGen.get()",
                "ownerReleaseAuditSequence.incrementAndGet()",
                "android.os.SystemClock.elapsedRealtime()",
                "bsEnabling || camViewEnabling",
                "isCameraLifecycleTransitionInFlight()",
                "stopIfCameraOwnerless(expectedGeneration");
        assertTrue(audit.contains(
                "pipelineGen.get() != expectedGeneration"));
        assertTrue(audit.contains(
                "ownerReleaseAuditFuture.cancel(false)"));

        String streamRelease = between(source,
                "private void disableStreamingLocked()",
                "private void fireStreamStateChanged()");
        assertTrue(streamRelease.contains(
                "scheduleOwnerlessPipelineAudit(\"stream disabled\")"));

        String blindSpotRelease = between(source,
                "public void disableBlindSpot()",
                "private void teardownSharedLaneLocked()");
        assertTrue(blindSpotRelease.contains(
                "scheduleOwnerlessPipelineAudit(\"blind-spot disabled\")"));

        String camViewRelease = between(source,
                "private int disableCamViewInternal(",
                "private boolean isCamViewClusterTarget()");
        assertTrue(camViewRelease.contains(
                "scheduleOwnerlessPipelineAudit(\"camera-view hidden\")"));

        String oemPreviewStart = between(oemApi,
                "if (!pano.isRunning()) {",
                "// (1) Wait on the REAL precondition");
        assertOrdered(oemPreviewStart,
                "pano.start(false, cameraStartEpoch)",
                "} finally {",
                "pano.auditOwnerlessPipelineAfterExternalRelease(");
    }

    @Test
    public void explicitLegacyCameraCommandsAreOwnerTrackedAndFailureSafe()
            throws Exception {
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "GpuSurveillancePipeline.java");

        assertTrue(daemon.contains("CAMERA_EXPLICIT_PENDING_CLAIMS"));
        assertTrue(daemon.contains("CAMERA_EXPLICIT_OWNER_LOCK"));
        assertTrue(daemon.contains("cameraExplicitCommandAdopted"));
        assertTrue(daemon.contains(
                "CAMERA_EXPLICIT_PENDING_CLAIMS.remove(token)"));
        assertTrue(daemon.contains(
                "public static boolean hasExplicitCameraCommandOwner()"));
        assertTrue(daemon.contains(
                "adoptExplicitCameraCommandOwner(explicitOwnerToken)"));
        assertTrue(daemon.contains(
                "explicit camera command failed before adoption"));

        String start = between(daemon,
                "public static void startCamera(",
                "private static void startPipelineInternal(");
        assertOrdered(start,
                "explicitOwnerToken = claimExplicitCameraCommandOwner()",
                "isCameraStartEpochCurrent(startGeneration)",
                "warmupAndWait(",
                "startPipelineInternal(");

        String internal = between(daemon,
                "private static void startPipelineInternal(",
                "private static boolean stopCameraConsumerLocked()");
        assertOrdered(internal,
                "boolean ownerAdopted = false",
                "gpuPipeline.start(",
                "if (!gpuPipeline.isRunning())",
                "ownerAdopted =",
                "adoptExplicitCameraCommandOwner(explicitOwnerToken)",
                "abandonExplicitCameraCommandOwner(explicitOwnerToken)");

        String stop = between(daemon,
                "private static boolean stopCameraConsumerLocked()",
                "public static boolean stopAllCamerasForExplicitUserStop()");
        assertTrue(stop.contains(
                "clearExplicitCameraCommandOwners()"));
        assertTrue(pipeline.contains(
                "onGpuPipelineStopCommitted(this)"));

        String oemSetter = between(daemon,
                "public static void setOemDashcamPipeline(",
                "public static int getOemDashcamPipelineGeneration()");
        assertOrdered(oemSetter,
                "if (p == null)",
                "auditOwnerlessPipelineAfterExternalRelease(");
    }

    @Test
    public void blindSpotDisableInvalidatesAnEnableInsideItsUnlockedGlWait()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "GpuSurveillancePipeline.java");

        String enable = between(source,
                "public void enableBlindSpot(int mode)",
                "private void enableBlindSpotInternal(");
        assertOrdered(enable,
                "bsEnabling = true",
                "long enableEpoch = ++bsLifecycleEpoch",
                "enableBlindSpotInternal(enableEpoch)");

        String internal = between(source,
                "private void enableBlindSpotInternal(",
                "private boolean buildSharedLaneLocked()");
        assertOrdered(internal,
                "buildSharedLaneLocked()",
                "if (enableEpoch != bsLifecycleEpoch)",
                "releasePartialBsLane()",
                "blindSpotEnabled = true");

        String disable = between(source,
                "public void disableBlindSpot()",
                "private void teardownSharedLaneLocked()");
        assertOrdered(disable,
                "ownerIntentWithdrawn = blindSpotEnabled || bsEnabling",
                "bsLifecycleEpoch++",
                "if (!blindSpotEnabled)");
    }

    @Test
    public void newOwnerAdmissionCannotCrossTheFinalTeardownClaim()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "GpuSurveillancePipeline.java");

        String recording = between(source,
                "public void startRecording(java.io.File outputDir, String prefix)",
                "private void startRecordingAdmitted(");
        assertOrdered(recording,
                "synchronized (this)",
                "if (!running || stopping || confirmedStopInProgress)",
                "recordingAdmissionInFlight = true",
                "startRecordingAdmitted(outputDir, prefix)",
                "recordingAdmissionInFlight = false");

        String surveillance = between(source,
                "public void enableSurveillance()",
                "private void enableSurveillanceAdmitted()");
        assertOrdered(surveillance,
                "synchronized (this)",
                "if (!running || stopping || confirmedStopInProgress)",
                "surveillanceAdmissionInFlight = true",
                "enableSurveillanceAdmitted()",
                "surveillanceAdmissionInFlight = false");

        String blindSpot = between(source,
                "public void enableBlindSpot(int mode)",
                "private void enableBlindSpotInternal(");
        assertTrue(blindSpot.contains(
                "if (stopping || confirmedStopInProgress)"));

        String camView = between(source,
                "public boolean enableCamView(",
                "public void disableCamView()");
        assertTrue(camView.contains(
                "if (stopping || confirmedStopInProgress)"));

        String idle = between(source,
                "logger.info(\"WebSocket idle timeout - stopping streaming\")",
                "wsStreamServer.start()");
        assertTrue(idle.contains(
                "self.stopIfCameraOwnerless("));
        assertFalse(idle.contains("self.stop();"));
    }

    @Test
    public void recordingActivationOwnsCameraBeforeModePublication() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/recording/RecordingModeManager.java");
        String predicate = between(source,
                "pipeline.setKeepAlivePredicate(() -> {",
                "pipeline.setBsVisibilityListener(");
        assertTrue(predicate.contains("if (activatingCameraOwner) return true;"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
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
        throw new IOException("Could not locate " + relativePath);
    }

    private static String between(String source, String start, String end) {
        int startAt = source.indexOf(start);
        int endAt = source.indexOf(end, startAt + start.length());
        assertTrue("missing start marker: " + start, startAt >= 0);
        assertTrue("missing end marker: " + end, endAt > startAt);
        return source.substring(startAt, endAt);
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        for (int at = 0; (at = source.indexOf(needle, at)) >= 0; at += needle.length()) {
            count++;
        }
        return count;
    }

    private static void assertMonotonicWait(String source) {
        assertTrue(source.contains(
                "android.os.SystemClock.elapsedRealtime()"));
        assertFalse(source.contains("System.currentTimeMillis()"));
    }

    private static void assertOrdered(String source, String... needles) {
        int cursor = -1;
        for (String needle : needles) {
            int next = source.indexOf(needle, cursor + 1);
            assertTrue("missing or out-of-order marker: " + needle, next > cursor);
            cursor = next;
        }
    }
}
