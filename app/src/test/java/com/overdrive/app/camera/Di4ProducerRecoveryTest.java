package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class Di4ProducerRecoveryTest {

    @Test
    public void genuineArrivalClockDrivesDemandStaleness() {
        assertFalse(PanoramicCameraGpu.isDiLink4ProducerStale(
                false, 1_000L, 500L, 10_000L, 2_000L, 9_000L));
        assertFalse(PanoramicCameraGpu.isDiLink4ProducerStale(
                true, 9_000L, 500L, 10_999L, 2_000L, 9_000L));
        assertTrue(PanoramicCameraGpu.isDiLink4ProducerStale(
                true, 9_000L, 500L, 11_000L, 2_000L, 9_000L));
    }

    @Test
    public void firstFrameUsesWarmupGraceInsteadOfImmediateRecovery() {
        assertFalse(PanoramicCameraGpu.isDiLink4ProducerStale(
                true, 0L, 1_000L, 9_999L, 2_000L, 9_000L));
        assertTrue(PanoramicCameraGpu.isDiLink4ProducerStale(
                true, 0L, 1_000L, 10_000L, 2_000L, 9_000L));
        assertFalse(PanoramicCameraGpu.isDiLink4ProducerStale(
                true, 0L, 0L, 10_000L, 2_000L, 9_000L));
    }

    @Test
    public void visibleAndProfileConsumersParticipateInStallRecovery() {
        assertTrue(PanoramicCameraGpu.isDiLink4ConsumerStarved(
                true, false, false, false, false));
        assertTrue(PanoramicCameraGpu.isDiLink4ConsumerStarved(
                false, true, false, false, false));
        assertTrue(PanoramicCameraGpu.isDiLink4ConsumerStarved(
                false, false, true, false, false));
        assertFalse(PanoramicCameraGpu.isDiLink4ConsumerStarved(
                false, false, false, false, false));
    }

    @Test
    public void surfaceTextureCallbacksAreOffGlLooperAndGenerationFenced()
            throws IOException {
        assertTrue(PanoramicCameraGpu.shouldAcceptDiLink4SurfaceTextureCallback(
                7, 7, true, false));
        assertFalse(PanoramicCameraGpu.shouldAcceptDiLink4SurfaceTextureCallback(
                6, 7, true, false));
        assertFalse(PanoramicCameraGpu.shouldAcceptDiLink4SurfaceTextureCallback(
                7, 7, false, false));
        assertFalse(PanoramicCameraGpu.shouldAcceptDiLink4SurfaceTextureCallback(
                7, 7, true, true));

        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String create = method(camera,
                "private void createCameraSurfaceTexture() {",
                "\n    /** Create the DI4 callback looper");
        assertOrdered(create,
                "Handler callbackHandler = ensureDiLink4FrameCallbackHandler();",
                "SurfaceTexture created = new SurfaceTexture(cameraTextureId);",
                "consumerEpoch = ++diLink4SurfaceTextureEpoch;",
                "onDiLink4SurfaceTextureFrameAvailable(st, consumerEpoch)",
                "callbackHandler");
        assertFalse(create.contains("}, glHandler);"));

        String callbackThread = method(camera,
                "private Handler ensureDiLink4FrameCallbackHandler() {",
                "\n    /** Full-pipeline teardown only");
        assertOrdered(callbackThread,
                "if (!USE_DILINK4_AVM_PATH && !USE_AVM_SURFACE_TEXTURE_PATH)",
                "new HandlerThread(\"Di4-FrameCallback\")",
                "replacementHandler.getLooper() == glHandler.getLooper()");

        String callbackShutdown = method(camera,
                "private void shutdownDiLink4FrameCallbackThread() {",
                "\n    static boolean shouldAcceptDiLink4SurfaceTextureCallback(");
        assertOrdered(callbackShutdown,
                "if (USE_DILINK4_AVM_PATH || USE_AVM_SURFACE_TEXTURE_PATH)",
                "synchronized (diLink4SurfaceTextureStateLock)",
                "diLink4SurfaceTextureEpoch++;",
                "thread.quitSafely()");

        String callback = method(camera,
                "private void onDiLink4SurfaceTextureFrameAvailable(",
                "\n    /**\n     * Atomically retire the current SurfaceTexture");
        assertOrdered(callback,
                "synchronized (diLink4SurfaceTextureStateLock)",
                "shouldAcceptDiLink4SurfaceTextureCallback(",
                "cameraSurfaceTexture == callbackSurface",
                "dilink4LastGenuineFrameArrivalMs = System.currentTimeMillis();",
                "stFrameArrivalSeq.incrementAndGet();",
                "stFramePending = true;",
                "frameSync.notify();");

        String release = method(camera,
                "private void releaseCameraConsumer() {",
                "\n    private void resetCurrentTexMatrixToIdentity()");
        assertTrue(release.contains(
                "SurfaceTexture retired = retireCameraSurfaceTextureConsumer();"));

        // Non-DI4 paths retain their existing ingestion/encoder topology.
        assertTrue(camera.contains(
                "private final boolean USE_DECOUPLED_ENCODER_LANE =\n"
                    + "        !USE_OEM_SURFACE_TEXTURE_PATH\n"
                    + "        && !USE_DILINK5_QCARCAM_PATH"));
        String imageReader = method(camera,
                "private void createCameraImageReader() {",
                "\n    /** Idempotent teardown");
        assertTrue(imageReader.contains(
                "this::onHalImageAvailable, imageReaderHandler"));
    }

    @Test
    public void contentionReacquireUsesFreshDi4ConsumerGeneration()
            throws IOException {
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String yield = method(camera,
                "private boolean yieldCameraInternal(boolean preserveDiLink5Avm) {",
                "\n    /**\n     * GL-thread runnable for the camera re-acquire");
        assertOrdered(yield,
                "if (USE_DILINK5_QCARCAM_PATH)",
                "if (!USE_DILINK5_QCARCAM_PATH)",
                "consumerNeedsRecreation = true;");

        String reacquire = method(camera,
                "private void attemptReacquireOnGlThread() {",
                "\n    /**\n     * Spawns a daemon thread that polls");
        assertOrdered(reacquire,
                "if (cameraObj != null)",
                "closeCameraForPath(stale)",
                "if (USE_DILINK4_AVM_PATH)",
                "consumerNeedsRecreation = true;",
                "if (!USE_DILINK5_QCARCAM_PATH && consumerNeedsRecreation)",
                "recreateCameraSurface();",
                "consumerNeedsRecreation = false;",
                "startCamera();");
        assertFalse(reacquire.contains(
                "if (!USE_OEM_SURFACE_TEXTURE_PATH && consumerNeedsRecreation)"));
    }

    @Test
    public void recoveryOrderIsSoftProbeThenSameHandleRebindThenReopen()
            throws IOException {
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String verify = method(camera,
                "private void verifyDiLink4SoftRecovery(",
                "\n    /**\n     * Hand stage 1's callback watcher");
        assertOrdered(verify,
                "disarmPreviewCallbackKick(",
                "if (!surfaceRebound)",
                "postDiLink4SurfaceRebind(",
                "escalateDiLink4SoftRecovery(");

        String handoff = method(camera,
                "private boolean postDiLink4SurfaceRebind(",
                "\n    /**\n     * Rebuild only the BufferQueue consumer");
        assertOrdered(handoff,
                "isCurrentDiLink4SoftRecovery(",
                "isDiLink4ProducerStale(",
                "rebindDiLink4SurfaceTexture(",
                "postDiLink4RecoveryVerification(",
                "escalateDiLink4SoftRecovery(");

        String rebind = method(camera,
                "private DiLink4SurfaceRebindResult rebindDiLink4SurfaceTexture(",
                "\n    /** Attach a recreated SurfaceTexture");
        assertOrdered(rebind,
                "retiredSurface = retireCameraSurfaceTextureConsumer()",
                "detachSurfaceTextureFromCamera(expectedCamera, retiredSurface)",
                "createCameraSurfaceTexture()",
                "queryDiLink4PreviewState(",
                "Boolean.FALSE.equals(previewing)",
                "armPreviewCallbackKick(",
                "avmClass, cameraSurfaceMode, true, () -> {",
                "bindDiLink4ReboundSurfaceTexture(",
                "DILINK4_REBIND_CALLBACK_TIMEOUT_MS",
                "DiLink4SurfaceRebindResult.WAITING_FOR_CALLBACK");
        assertFalse(rebind.contains(
                "avmClass, cameraSurfaceMode, true, null"));
        assertFalse(rebind.contains("getDeclaredMethod(\"startPreview\")"));

        String bind = method(camera,
                "private void bindDiLink4ReboundSurfaceTexture(",
                "\n    private Boolean queryDiLink4PreviewState(");
        assertOrdered(bind,
                "cameraSurfaceTexture != expectedSurface",
                "\"addTexture\"",
                "\"setTexture\"");
        assertFalse(bind.contains("getDeclaredMethod(\"startPreview\")"));

        String watchdog = method(camera,
                "private boolean maybeRestartStalledDilink4Producer(",
                "\n    /**\n     * Dead-slot escape");
        assertTrue(watchdog.contains("isDiLink4ConsumerStarved("));
        assertTrue(watchdog.contains("dilink4FrameDemanded"));
        assertTrue(watchdog.contains("bsLayerVisible"));
        assertTrue(watchdog.contains("requestDiLink4ProducerRecovery("));

        String reopen = method(camera,
                "private boolean requestDiLink4CameraReopen(",
                "\n    private void requestDiLink4HalRecoveryEscalation()");
        assertTrue(reopen.contains("cameraObj != expectedCamera"));
        assertTrue(reopen.contains(
                "dilink4DeferredReopenPending.compareAndSet("));
        assertOrdered(reopen,
                "final int deferredEpoch",
                "dilink4DeferredReopenEpoch.get() != deferredEpoch",
                "cameraObj != expectedCamera",
                "isDiLink4ProducerStale(",
                "requestDiLink4CameraReopen(");

        String terminal = method(camera,
                "private void requestDiLink4HalRecoveryEscalation()",
                "\n    private volatile boolean previewKickFirstByteLogged");
        assertTrue(terminal.contains(
                "requestProcessRestartPreservingTrip("));
    }

    @Test
    public void modeNoneTerminalRecoveryCannotSilentlyNoOp() throws IOException {
        String rmm = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/recording/RecordingModeManager.java");
        String restart = method(rmm,
                "public void forceWarmupRestart(String reason) {",
                "\n    /**\n     * Force an immediate re-activation");
        assertOrdered(restart,
                "if (!acc || mode == Mode.NONE)",
                "if (pipeline.isRunning())",
                "CameraDaemon.requestProcessRestartPreservingTrip(");
        // Legacy AVMCamera keeps its own reopen ladder, so only that branch may no-op.
        assertTrue(restart.contains("if (isLegacyCameraMode()) {"));
        String nonLegacy = restart.replaceFirst(
                "(?s)if \\(isLegacyCameraMode\\(\\)\\) \\{.*?return;\\s*\\}", "");
        assertFalse(nonLegacy.contains("; nothing to restart"));
        assertOrdered(restart,
                "pipeline.stop();",
                "if (pipeline.isRunning())",
                "\"DI4 full camera/GL teardown did not stop: \"",
                "activateModeWithWarmup(");
        assertTrue(restart.contains(
                "\"DI4 full camera/GL teardown failed: \""));

        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");
        assertTrue(pipeline.contains("camera.onDiLink4AccOn();"));
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String accOn = method(camera,
                "public void onDiLink4AccOn() {",
                "\n    private volatile boolean previewKickFirstByteLogged");
        assertOrdered(accOn,
                "if (!USE_DILINK4_AVM_PATH) return;",
                "requestDiLink4ProducerRecovery(\"acc-on persistent handle\")",
                "reopenReleasedDiLink4Camera(");
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
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
