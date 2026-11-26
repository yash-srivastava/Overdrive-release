package com.overdrive.app.camera;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Structural contract for the decoupled encoder lane
 * (camera.decoupledEncoderLane — the BYD native-AVM starvation fix).
 *
 * Invariants protected here:
 *  1. FLAG-OFF INERTNESS: the flag defaults to false (BYD100F: true, see
 *     DecoupledEncoderLanePolicyTest), is restricted to the
 *     legacy ImageReader path, and the legacy PASS 1A block survives verbatim
 *     behind the flag branch — flag-off behaviour must stay bit-identical to
 *     the shipped zero-copy pipeline.
 *  2. BUFFER-LIFETIME SAFETY: camera-owned Image/HardwareBuffer pairs are
 *     closed only after their copy blit provably retired on the GPU (fence
 *     check, or glFinish on the drain paths) — closing earlier is the
 *     use-after-free class the cameraTextureLock comments document.
 *  3. CROSS-THREAD SLOT SAFETY: the EncoderLane pins ring slots before
 *     sampling and the ring writer's round-robin skips the pinned slot, so an
 *     encoder stall can never race a slot rewrite.
 *  4. OWNERSHIP PARITY: the lane is torn down before the camera close /
 *     parent EGL teardown in stop(), and the recorder's lifecycle stays with
 *     the pipeline.
 *  5. COLD-START SAMPLER PARITY: recorder/downscaler shader types are derived
 *     from the new camera instance, never from the null/stale pipeline field.
 */
public class DecoupledEncoderLaneContractTest {

    private static final String GPU =
            "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java";
    private static final String LANE =
            "app/src/main/java/com/overdrive/app/camera/EncoderLane.java";
    private static final String RING =
            "app/src/main/java/com/overdrive/app/camera/CopiedFrameRing.java";
    private static final String RECORDER =
            "app/src/main/java/com/overdrive/app/surveillance/GpuMosaicRecorder.java";
    private static final String PIPELINE =
            "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java";

    @Test
    public void flagDefaultsOffAndIsRestrictedToTheLegacyPath() throws IOException {
        String gpu = readRepositoryFile(GPU);

        // Config read through the shared policy (default off except BYD100F),
        // resolved once at construction like the other USE_* path selectors
        // (never hot-swapped).
        assertTrue(gpu.contains("return DecoupledEncoderLanePolicy.resolve(cam);"));
        assertOrdered(gpu,
                "private final boolean USE_DECOUPLED_ENCODER_LANE =",
                "!USE_OEM_SURFACE_TEXTURE_PATH",
                "&& !USE_DILINK5_QCARCAM_PATH",
                "&& resolveDecoupledEncoderLaneFromConfig();");

        // isTexture2D keeps the DiLink 5 guarantee and adds the lane as a
        // disjunct only.
        assertTrue(gpu.contains(
                "return USE_DILINK5_QCARCAM_PATH || USE_DECOUPLED_ENCODER_LANE;"));

        // renderLoop: the lane branch fronts the UNTOUCHED legacy PASS 1A
        // gate — flag-off falls through to the shipped block, including its
        // inline encoder-reinit machinery.
        assertOrdered(gpu,
                "if (USE_DECOUPLED_ENCODER_LANE) {",
                "runDecoupledLanePass(localRecorder);",
                "} else",
                "if (localRecorder != null && (recorderLaneEnabled || localRecorder.isRecording())) {",
                "localRecorder.drawFrame(cameraTextureId, windshieldTextureId,",
                "if (!localEncoder.release())");

        // The HAL buffer binds to the private OES texture only when the flag
        // is on; flag-off keeps binding cameraTextureId exactly as shipped.
        assertOrdered(gpu,
                "final int bindTargetTexture = USE_DECOUPLED_ENCODER_LANE",
                "? cameraOesTextureId",
                ": cameraTextureId;",
                "bindHardwareBufferToTextureNative(hwBuffer, bindTargetTexture);");
    }

    @Test
    public void cameraBuffersAreOnlyClosedBehindThePublishBarrier() throws IOException {
        String gpu = readRepositoryFile(GPU);

        // Per-frame order: copy the camera frame (copyFrom's contract now
        // includes the completion barrier — see the ring assertion below),
        // then close the camera-owned buffers, then publish the slot for
        // consumers, then hand the packet to the lane. A producer-side fence
        // alone is NOT sufficient: it covers buffer lifetime but not the
        // lane/AI contexts sampling a half-written slot (review, finding 3).
        String pass = method(gpu,
                "private void runDecoupledLanePass(GpuMosaicRecorder localRecorder) {",
                "\n    /**\n     * Closes the held camera");
        assertOrdered(pass,
                "camRing.copyFrom(cameraOesTextureId);",
                "closeHeldCameraBuffersAfterBarrier(",
                "synchronized (cameraTextureLock) {",
                "cameraTextureId = slotTex;",
                "lane.submit(");

        // The close helper documents and enforces the barrier precondition.
        String closer = method(gpu,
                "private void closeHeldCameraBuffersAfterBarrier(boolean wsCopiedThisFrame) {",
                "\n    /** Lazily creates the EncoderLane");
        assertOrdered(closer,
                "currentBoundHwBuffer.close();",
                "currentBoundImage.close();");

        // Teardown parity: a bind that never reached its copy is still owned
        // by releasePreviousBoundImage before the reader closes.
        String consumerRelease = method(gpu,
                "private void releaseCameraConsumer() {",
                "stFrameArrivalSeq.set(0);");
        assertOrdered(consumerRelease,
                "synchronized (cameraTextureLock) {",
                "releasePreviousBoundImage();",
                "cameraImageReader.close();");
    }

    @Test
    public void laneSamplesOnlyPinnedSlotsAndWriterSkipsThePin() throws IOException {
        String lane = readRepositoryFile(LANE);
        String ring = readRepositoryFile(RING);

        // Lane: gate parity with legacy PASS 1A (lane-enabled OR recording);
        // stride phase derives from the CAMERA FRAME SEQ (mailbox coalescing
        // must not raise the effective rate) and re-anchors on stride change;
        // a camera-slot pin REFUSAL skips the draw entirely (sampling
        // unpinned would race the writer's in-flight blit); GPU barrier
        // before unpin.
        assertOrdered(lane,
                "if (!recorderLaneEnabledSupplier.getAsBoolean()",
                "&& !localRecorder.isRecording()) {",
                "strideBaseSeq = f.seq;",
                "((f.seq - strideBaseSeq) % stride) == 0",
                "if (!camRing.pinForRead(f.camSlot)) {",
                "return;",
                "localRecorder.drawFrame(f.camTex,",
                "glFinish();",
                "unpinRead();");

        // Lane carries the ported encoder-surface-loss recovery, including
        // the release-verdict abort.
        assertOrdered(lane,
                "localRecorder.needsReinit()",
                "restartInProgress.compareAndSet(false, true)",
                "if (!localEncoder.release())",
                "localRecorder.clearReinitFlag();");

        // Ring: slot selection+mark and pin-check are serialized on the ring
        // monitor (a plain atomic pin was TOCTOU-racy at N-1-frame lane lag);
        // the write reservation stays held ACROSS the completion barrier —
        // glDrawArrays only submits, so clearing writingSlot before glFinish
        // left a window where a stale packet could pin a slot the GPU was
        // still writing (review round 2, finding 2); the pin refuses the
        // writer's in-flight slot; the default framebuffer binding is always
        // restored.
        assertOrdered(ring,
                "synchronized (this) {",
                "if (candidate == pinnedSlot) {",
                "writingSlot = slot;",
                "glDrawArrays",
                "GLES20.glFinish();",
                "writingSlot = NO_PIN;",
                "} finally {",
                "GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);");
        assertOrdered(ring,
                "public synchronized boolean pinForRead(int slot) {",
                "if (slot == writingSlot) {",
                "return false;",
                "pinnedSlot = slot;");
    }

    @Test
    public void laneShutsDownBeforeCameraCloseAndParentEglTeardown() throws IOException {
        String gpu = readRepositoryFile(GPU);
        String lane = readRepositoryFile(LANE);

        // stop(): the lane quiesces FIRST — while the encoder drainers are
        // still alive, so an in-flight eglSwapBuffers can complete — then the
        // drainer guard, then camera close. A wedged lane ABORTS the stop and
        // requests the trip-safe restart instead of letting the codec/EGL be
        // torn down under a live draw (release-blocker review, finding 2).
        assertOrdered(gpu,
                "laneToStop.shutdown(2000)",
                "requestUrgentCameraReleaseRestart(",
                "stopVerdictWedged = true;",
                "stopEncoderDrainersBeforeCameraClose(\"stop\", encoder, streamEncoder)",
                "stopClean = closeCameraForPath(cameraObj);");

        // A failed shutdown must NOT null the thread/context references —
        // that would advertise "lane gone" while a wedged thread still pins
        // the child EGL context and may still be drawing.
        assertOrdered(lane,
                "if (!exited || !cleanupRan) {",
                "return false;",
                "thread = null;");

        // releaseGl(): defensive lane shutdown precedes the AI-lane and
        // eglCore teardown (share-group ordering), and the lane's rings +
        // private OES texture are freed.
        String releaseGl = method(gpu,
                "private void releaseGl() {",
                "logger.info(\"OpenGL resources released\");");
        assertOrdered(releaseGl,
                "laneAtRelease.shutdown(1500);",
                "aiLaneGl.shutdown();",
                "releaseCameraConsumer();",
                "decoupledCamRing.release();",
                "GlUtil.deleteTexture(cameraOesTextureId);",
                "eglCore.release();");
    }

    @Test
    public void recorderWindshieldSamplerFollowsTheLaneFlag() throws IOException {
        String recorder = readRepositoryFile(RECORDER);
        String pipeline = readRepositoryFile(PIPELINE);

        // Shader declares the windshield sampler by type; the 4-arg ctor
        // pins the legacy default (false) so every existing caller is
        // bit-identical.
        assertTrue(recorder.contains(
                "String windshieldSampler = windshieldTexture2D"));
        assertTrue(recorder.contains(
                "this(quadrantStripOffsetX, viewportWidth, viewportHeight, isTexture2D, false);"));
        assertOrdered(recorder,
                "windshieldTexture2D ? GLES20.GL_TEXTURE_2D",
                ": GLES11Ext.GL_TEXTURE_EXTERNAL_OES,");

        // Pipeline passes the lane flag at every recorder construction site
        // and derives scaler sampler types from the camera (2D on DiLink 5
        // AND on the lane), with the platform check only as pre-camera
        // fallback.
        assertTrue(pipeline.contains("camera.isDecoupledEncoderLane())"));
        assertOrdered(pipeline,
                "? camera.isTexture2D()",
                ": com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected());");
    }

    @Test
    public void coldInitResolvesTextureContractFromNewCameraBeforeConsumers()
            throws IOException {
        String pipeline = readRepositoryFile(PIPELINE);
        String coldInit = method(pipeline,
                "private void initLocked(",
                "\n    /**\n     * Starts the GPU pipeline.");

        // Cold boot starts with pipeline.camera == null. Build the side-effect-
        // free next camera first, derive both sampler decisions from THAT
        // instance, then construct every texture consumer and finally publish
        // the camera. This prevents samplerExternalOES shaders from being
        // paired with decoupled-lane GL_TEXTURE_2D ring slots.
        assertOrdered(coldInit,
                "PanoramicCameraGpu nextCamera = new PanoramicCameraGpu(",
                "boolean isTexture2D = nextCamera.isTexture2D();",
                "boolean decoupledEncoderLane = nextCamera.isDecoupledEncoderLane();",
                "recorder = new GpuMosaicRecorder(",
                "isTexture2D,",
                "decoupledEncoderLane);",
                "downscaler = new GpuDownscaler(quadrantStripOffsetX, isTexture2D);",
                "camera = nextCamera;",
                "camera.setConsumers(recorder, downscaler, sentry);");

        // Never reintroduce lifecycle-dependent sampler selection. It made
        // cold-start recordings black but accidentally worked after ACC OFF
        // because stop() retained a stale camera reference.
        assertTrue(!coldInit.contains(
                "boolean isTexture2D = camera != null && camera.isTexture2D();"));
        assertTrue(!coldInit.contains(
                "camera != null && camera.isDecoupledEncoderLane()"));
    }

    @Test
    public void liveEncoderReconfigurationIsSerializedThroughTheLane() throws IOException {
        String pipeline = readRepositoryFile(PIPELINE);

        // reinitializeEncoder must not touch the recorder's GL from the
        // render thread when the lane owns it (release-blocker review,
        // finding 1): surface release routes through the lane, the legacy
        // render-thread post survives only as the flag-off branch, and the
        // recorder re-init + lane ref adoption happen as one serialized lane
        // operation whose failure propagates.
        assertOrdered(pipeline,
                "private void reinitializeEncoder() throws Exception {",
                "camera.isDecoupledEncoderLane() && recorder != null",
                "releaseRecorderEncoderSurfaceOnLane(recorder, 1000)",
                "requestProcessRestartPreservingTrip(",
                "throw new IllegalStateException(",
                "camera.getGlHandler() != null && recorder != null",
                "recorder.releaseEncoderSurface();",
                "camera.isDecoupledEncoderLane()",
                "reinitRecorderOnEncoderLane(recorder, encoder, 3000);",
                "throw laneErr;",
                "camera.getEglCore() != null",
                "recorder.init(camera.getEglCore(), encoder);");

        // A lane-sync timeout must ABORT, never proceed (review round 2,
        // finding 1): the lane marks the timed-out task abandoned so it can
        // never execute late against replaced state, and the caller escalates
        // instead of tearing the codec down beneath a possible in-flight draw.
        String lane = readRepositoryFile(LANE);
        assertOrdered(lane,
                "public boolean runOnLane(Runnable task, long timeoutMs) {",
                "if (abandoned.get()) {",
                "abandoned.set(true);",
                "return false;");
        assertTrue(lane.contains("EncoderLane recorder reinit timed out after "));
    }

    @Test
    public void toggleIsExposedUnderTheCameraProbeAndLegacyGated() throws IOException {
        String layout = readRepositoryFile(
                "app/src/main/res/layout/dialog_camera_mapping.xml");
        String strings = readRepositoryFile(
                "app/src/main/res/values/strings.xml");
        String activity = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");
        String resolver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/CameraConfigResolver.java");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");

        // Layout: the switch lives directly under the concurrent-probe block
        // in the camera-mapping dialog's advanced section.
        assertOrdered(layout,
                "@+id/swConcurrentAvmProbe",
                "camera_concurrent_probe_subtitle",
                "@+id/swDecoupledEncoderLane",
                "camera_decoupled_lane_subtitle");
        assertTrue(strings.contains("camera_decoupled_lane_label"));
        assertTrue(strings.contains("camera_decoupled_lane_subtitle"));

        // Dialog wiring: state read from the camera UCM section, switch
        // initialized BEFORE the listener attaches (no phantom save), gated
        // to the legacy camera mode, write-through persistence off the UI
        // looper with a guarded revert on failure.
        assertOrdered(activity,
                "DecoupledEncoderLanePolicy.resolve(cameraSection)",
                "decoupledLaneSwitch?.isEnabled = state.cameraMode == \"default\"",
                "decoupledLaneSwitch?.isChecked = state.decoupledEncoderLaneEnabled",
                "decoupledLaneSwitch?.setOnCheckedChangeListener",
                "saveDecoupledEncoderLane(checked)");

        // Resolver: single write-through UCM helper both the dialog and the
        // HTTP API share.
        assertTrue(resolver.contains(
                "public static boolean saveDecoupledEncoderLane(boolean enabled)"));

        // API: POST /api/surveillance/config accepts the key, surfaces
        // persistence failures instead of fake-success, and flags the config
        // as changed.
        assertOrdered(api,
                "configJson.has(\"decoupledEncoderLane\")",
                "saveDecoupledEncoderLane(laneEnabled)",
                "Could not persist camera config",
                "configChanged = true;");
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
