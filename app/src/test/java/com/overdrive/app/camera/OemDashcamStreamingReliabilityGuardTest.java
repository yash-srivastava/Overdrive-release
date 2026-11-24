package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Source-level regression guards for DVR handoff and reconnect races.
 */
public class OemDashcamStreamingReliabilityGuardTest {

    @Test
    public void clientCancelsStaleDvrRequestsAndRejectsRouteFailures() throws IOException {
        String page = readRepositoryFile("app/src/main/assets/web/local/live-view.html");
        String shared = readRepositoryFile("app/src/main/assets/web/shared/stream.js");

        assertTrue(page.contains("const selectionToken = (this._viewSelectionToken || 0) + 1;"));
        assertTrue(page.contains("&& (sotaLive || legacyLive || this.streamStarted)"));
        assertTrue(page.contains("_pollViewUntilSettled(m, d, selectionToken)"));
        assertTrue(page.contains("reassertLatestRoute();"));
        assertTrue(page.contains("BYD.stream._requestViewRoute(m, selectionToken)"));
        assertTrue(shared.contains("_viewSelectionToken: 0,"));
        assertTrue(shared.contains("_viewMutationTail: Promise.resolve(),"));
        assertTrue(shared.contains("_enqueueViewMutation(selectionToken, mutation)"));
        assertTrue(shared.contains("_requestViewRoute(mode, selectionToken)"));
        assertTrue(shared.contains("_requestViewStatus(mode, selectionToken)"));
        assertTrue(shared.contains("_requestStreamDisable(selectionToken)"));
        assertTrue(shared.contains("_viewRouteClientId: null,"));
        assertTrue(shared.contains("_viewRouteUrl(mode, selectionToken)"));
        assertTrue(shared.contains("_viewStatusUrl(mode, selectionToken)"));
        assertTrue(shared.contains("?client=' + encodeURIComponent(this._viewRouteClientId)"));
        assertTrue(shared.contains("if (!res.ok) throw new Error('view route failed: ' + res.status);"));
        assertTrue(shared.contains("if (!res.ok) throw new Error('view status failed: ' + res.status);"));
        assertTrue(shared.contains("async _pollViewUntilSettled(mode, initialResponse, selectionToken)"));
        assertTrue(shared.contains("data = routeStillNeeded"));
        assertTrue(shared.contains(": await this._requestViewStatus(mode, selectionToken)"));
        assertTrue(shared.contains("this._viewSelectionToken = (this._viewSelectionToken || 0) + 1;"));
    }

    @Test
    public void dvrWarmupStatusIsReadOnlyAndSteadyStateIsIdempotent() throws IOException {
        String streamApi = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/StreamingApiHandler.java");

        assertTrue(streamApi.contains("path.startsWith(\"/api/stream/view-status/\")"));
        String statusHandler = between(
                streamApi,
                "private static void handleStreamViewStatus(",
                "private static void handleOemDashcamView(");
        assertTrue(statusHandler.contains("pano.isOemStreamSourceActive()"));
        assertFalse(statusHandler.contains("routeStreamToOemDashcam()"));
        assertFalse(statusHandler.contains("scheduleLifecycleRecalc()"));
        assertFalse(statusHandler.contains("enableStreaming("));
        assertFalse(statusHandler.contains("setStreamViewMode("));

        String routeHandler = between(
                streamApi,
                "private static void handleOemDashcamView(",
                "private static void sendOemViewSuccess(");
        int steadyState = routeHandler.indexOf(
                "pano.getStreamViewMode() == 6 && pano.isOemStreamSourceActive()");
        int routeMutation = routeHandler.indexOf("routeStreamToOemDashcam()");
        assertTrue("steady DVR requests must return before route mutation",
                steadyState >= 0 && routeMutation > steadyState);
    }

    @Test
    public void unavailableDvrNeverFallsThroughToPanoAndTeardownWaitsForDetach()
            throws IOException {
        String scaler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/streaming/GpuStreamScaler.java");
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");
        String oem = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/OemDashcamPipeline.java");

        assertTrue(scaler.contains("\"    if (uViewMode == 6) {\\n\" +"));
        assertTrue(scaler.contains("gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);"));
        assertTrue(pipeline.contains("!oemPipeline.isRouteReady()"));
        assertTrue(pipeline.contains("public boolean reattachOwnStreamCallback()"));
        assertTrue(pipeline.contains("getPendingOemSourceFenceGeneration()"));
        assertTrue(pipeline.contains("completeOemSourceFence(long generation)"));
        assertTrue(oem.contains("pano.reattachOwnStreamCallback();"));
        assertTrue(oem.contains("pano.getPendingOemSourceFenceGeneration()"));
        assertTrue(oem.contains("pano.completeOemSourceFence(fenceGeneration);"));
        assertTrue(oem.contains("GLES20.glFinish();"));
        assertTrue(oem.contains("boolean panoGpuQuiesced = detachFromPano();"));
        assertFalse(oem.contains("if (!detached) return true;"));
    }

    @Test
    public void restartAndWebSocketCallbacksAreLifecycleOwned() throws IOException {
        String oemApi = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/OemDashcamApiHandler.java");
        String quality = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/QualitySettingsApiHandler.java");
        String encoder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java");
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");
        String http = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");
        String streamApi = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/StreamingApiHandler.java");
        String wsServer = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/streaming/WebSocketStreamServer.java");

        assertTrue(oemApi.contains("forceRestartPipelineFromCurrentIntent()"));
        assertTrue(oemApi.contains("|| isAnyStreamingViewerActive()"));
        assertTrue(oemApi.contains("pano.activateOemStreamViewWhenReady();"));
        assertTrue(quality.contains("forceRestartPipelineFromCurrentIntent();"));
        assertTrue(encoder.contains("CopyOnWriteArraySet<StreamCallback> streamCallbacks"));
        assertTrue(encoder.contains("public void addStreamCallback(StreamCallback callback)"));
        assertTrue(encoder.contains("public void removeStreamCallback(StreamCallback callback)"));
        assertTrue(pipeline.contains("registerExternalStreamClient("));
        assertTrue(pipeline.contains("disableStreamingIfIdle("));
        assertTrue(http.contains("pipeline.registerExternalStreamClient(callback);"));
        assertTrue(http.contains("pipeline.unregisterExternalStreamClient(subscription);"));
        assertTrue(http.contains("headersReceived.get() || !gotKeyframe.get()"));
        assertFalse(http.contains("encoder.clearStreamCallback();"));
        assertTrue(pipeline.contains("StreamingApiHandler.getLastDesiredViewMode() != 6"));
        assertTrue(streamApi.contains("private static final class ViewRequest"));
        assertTrue(streamApi.contains("if (!request.applyIntent(viewMode))"));
        assertTrue(streamApi.contains("if (!ensureCurrentViewRequest(out, 6, request))"));
        assertTrue(streamApi.contains("latestViewSelections"));
        assertTrue(streamApi.contains("parseViewRequest(path)"));
        assertTrue(streamApi.contains("ensureCurrentViewRequest(out, 6, request)"));
        assertTrue(streamApi.contains("restoreLatestViewAfterSupersededOemRoute(pano)"));
        assertTrue(wsServer.contains("idleShutdownTriggered = false;"));
    }

    @Test
    public void staleLifecycleCannotStartRecordingAfterWarmup() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/OemDashcamApiHandler.java");

        int revisionBump = source.indexOf("lifecycleRevision.incrementAndGet();");
        int pending = source.indexOf("lifecyclePending.set(true);", revisionBump);
        int schedule = source.indexOf("if (lifecycleInFlight.compareAndSet", revisionBump);
        assertTrue(revisionBump >= 0 && pending > revisionBump && schedule > pending);

        int resolver = source.indexOf("public static void applyTriggerLifecycleFromUcm()");
        int capture = source.indexOf(
                "long expectedRevision = lifecycleRevision.get();", resolver);
        int apply = source.indexOf(
                "applyTriggerLifecycle(recordingDesired, keepWarm, expectedRevision);", capture);
        assertTrue(capture > resolver && apply > capture);

        int lifecycle = source.indexOf("private static void applyTriggerLifecycle(");
        int pipelineWarmup = source.indexOf(
                "lifecycle superseded during pipeline warmup", lifecycle);
        int pipelineGuard = source.lastIndexOf(
                "if (!isLifecycleRevisionCurrent(expectedRevision))", pipelineWarmup);
        int encoderWarmup = source.indexOf(
                "lifecycle superseded during encoder warmup", pipelineWarmup);
        int encoderGuard = source.lastIndexOf(
                "if (!isLifecycleRevisionCurrent(expectedRevision))", encoderWarmup);
        int startRecording = source.indexOf("current.startRecording()", encoderWarmup);
        assertTrue(lifecycle >= 0);
        assertTrue(pipelineGuard > lifecycle && pipelineWarmup > pipelineGuard);
        assertTrue(encoderGuard > pipelineWarmup && encoderWarmup > encoderGuard);
        assertTrue(startRecording > encoderWarmup);
    }

    @Test
    public void automaticOemLifecycleRequiresKnownAccAndPrivacyGatesFailClosed()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/OemDashcamApiHandler.java");
        String resolver = between(
                source,
                "public static void applyTriggerLifecycleFromUcm()",
                "private static boolean isLifecycleRevisionCurrent(");

        int streaming = resolver.indexOf(
                "boolean streamingDesired = isAnyStreamingViewerActive()");
        int authoritative = resolver.indexOf(
                "AccMonitor.isAccStateAuthoritative()", streaming);
        int trustworthy = resolver.indexOf(
                "AccMonitor.wasLastProbeTrustworthy()", authoritative);
        int unknownApply = resolver.indexOf(
                "applyTriggerLifecycle(false, streamingDesired, expectedRevision)",
                trustworthy);
        int configRead = resolver.indexOf(
                "UnifiedConfigManager.forceReload()", unknownApply);
        assertTrue(streaming >= 0);
        assertTrue(authoritative > streaming);
        assertTrue(trustworthy > authoritative);
        assertTrue(unknownApply > trustworthy);
        assertTrue(configRead > unknownApply);

        assertTrue(resolver.contains(
                "survSuppressed = true;"));
        assertTrue(resolver.contains(
                "parked surveillance gate unavailable"));
        assertTrue(resolver.contains(
                "|| streamingDesired;"));
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

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing start marker: " + startMarker, start >= 0);
        assertTrue("missing end marker: " + endMarker, end > start);
        return source.substring(start, end);
    }
}
