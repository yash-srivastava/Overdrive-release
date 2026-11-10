package com.overdrive.app.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.overdrive.app.logging.DaemonLogger;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Best-effort preview capture for the diagnostics camera-mapping dialog.
 *
 * <p>Two paths:
 * <ul>
 *   <li><b>Direct AVMCamera</b> — opens the requested camera id via
 *       {@code AVMCamera} reflection, attaches a YUV_420_888 ImageReader,
 *       captures one frame, tears down. Reflection-only, no GL state.
 *       Restricted to the camera id the GL surveillance pipeline is currently
 *       holding. On-device verification showed BYD concurrency is
 *       pair-dependent: Tang camera 2 (360 strip) + camera 0 (windshield)
 *       can stream together, while camera 1 + camera 0 opens but the second
 *       stream delivers zero frames. Same-id multi-claim remains unsafe, so
 *       callers must check holding state via {@link #isCameraHeldByPipeline(int)}
 *       before invoking this one-shot preview path.</li>
 *   <li><b>Panoramic slice / virtual view</b> — never opens a second camera.
 *       Reads the surveillance engine's published mosaic JPEG and crops the
 *       requested quadrant on the caller (HTTP worker) thread. Zero GL or
 *       HAL impact.</li>
 * </ul>
 */
public final class CameraPreviewHelper {
    private static final DaemonLogger logger = DaemonLogger.getInstance("CameraPreviewHelper");
    // 800 ms = 8 frames at 10 fps. Tight enough that an HTTP worker holding
    // a slow direct-preview path doesn't deplete the daemon's worker pool
    // during a sustained Prev/Next session in the diagnostics dialog. The
    // dialog re-fires on user navigation; one missed frame on the head unit
    // is preferable to thread-pool starvation that blocks unrelated PWA
    // requests (e.g. live MJPEG to the dashboard).
    private static final int DEFAULT_TIMEOUT_MS = 800;

    // Process-wide single-flight gate for direct AVMCamera previews. BYD
    // camera concurrency is pair-dependent and same-id multi-claim is unsafe;
    // two HTTP clients dialing the same dialog from two phones (or Phone +
    // head unit) would race each other into AVMCamera.open and one of them
    // would lose with a confusing "returned false" / null result. Instead,
    // the second caller fast-fails to null and lets the API handler fall
    // through to the panoramic-slice cache path.
    private static final java.util.concurrent.atomic.AtomicBoolean directPreviewBusy =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean
        directPreviewTerminalRestart =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final long DIRECT_PREVIEW_HARD_TIMEOUT_MS = 12_000L;

    private CameraPreviewHelper() {
    }

    /**
     * True when the surveillance pipeline is running and has the given camera
     * id open. Direct previews against this id are unsafe — caller should
     * route through the panoramic-slice path or refuse the preview.
     */
    public static boolean isCameraHeldByPipeline(int cameraId) {
        try {
            if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend
                    .hasOwnedHardwareProcess()
                    && com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend
                    .ownsConfiguredInput(cameraId)) {
                return true;
            }
            com.overdrive.app.surveillance.GpuSurveillancePipeline p =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            if (p != null) {
                boolean running = p.isRunning();
                com.overdrive.app.camera.PanoramicCameraGpu cam = p.getCamera();
                int heldCamId = cam != null ? cam.getCameraId() : -1;
                boolean result = running && cam != null
                    && heldCamId == cameraId;
                logger.info("isCameraHeldByPipeline cam=" + cameraId
                        + " running=" + running + " heldCamId=" + heldCamId
                        + " → " + result);
                if (result) return true;
            }

            com.overdrive.app.camera.OemDashcamPipeline oem =
                com.overdrive.app.daemon.CameraDaemon
                    .getOemDashcamPipeline();
            if (oem != null && oem.isRunning()
                    && oem.getCameraId() == cameraId) {
                logger.info("isCameraHeldByPipeline cam=" + cameraId
                    + " → true (OEM dashcam owns same id)");
                return true;
            }
            return false;
        } catch (Throwable e) {
            // If the lookup itself fails we can't be sure — fail closed.
            return true;
        }
    }

    public static byte[] captureDirectPreviewJpeg(int cameraId, int preferredWidth, int preferredHeight) {
        return captureDirectPreviewJpeg(cameraId, preferredWidth, preferredHeight, 10, DEFAULT_TIMEOUT_MS);
    }

    public static byte[] captureDirectPreviewJpeg(int cameraId, int preferredWidth, int preferredHeight,
                                                  int fps, int timeoutMs) {
        if (preferredWidth <= 0 || preferredHeight <= 0) return null;
        if (isCameraHeldByPipeline(cameraId)) {
            logger.info("Refusing direct preview for cam=" + cameraId
                    + " — surveillance pipeline already holds this camera id");
            return null;
        }
        return captureWithSize(cameraId, preferredWidth, preferredHeight, fps, timeoutMs);
    }

    public static byte[] captureDirectPreviewJpegExact(int cameraId, int width, int height,
                                                       int fps, int timeoutMs) {
        if (width <= 0 || height <= 0) return null;
        if (isCameraHeldByPipeline(cameraId)) {
            logger.info("Refusing direct preview for cam=" + cameraId
                    + " — surveillance pipeline already holds this camera id");
            return null;
        }
        return captureWithSize(cameraId, width, height, fps, timeoutMs);
    }

    /**
     * Crop the panoramic mosaic JPEG into the slice quadrant. Single-flight,
     * zero camera open.
     *
     * <p>Two sources of the mosaic JPEG, in order of preference:
     * <ol>
     *   <li>Surveillance engine's pre-encoded {@code latestMosaicJpeg} —
     *       published every 15 frames in {@code processFrame}, but ONLY when
     *       sentry is active. Free read.</li>
     *   <li>Sync GL readback from {@code PanoramicCameraGpu.getLatestJpegFrame(0)}
     *       — works whenever the camera pipeline is running, regardless of
     *       sentry state. Costs ~150 ms (FBO render + glReadPixels + JPEG).
     *       This is the fallback path for proximity-guard mode where
     *       surveillance is idle but the camera is still streaming.</li>
     * </ol>
     * Returns null when neither source can produce a frame (pipeline cold).
     */
    public static byte[] capturePanoramicSliceJpeg(PanoramicSlice slice) {
        if (slice == null) return null;
        // Preferred: direct GL render at full output resolution. Passive
        // mode returns the whole native frame because no four-camera map is
        // assumed; mapped layouts return the requested camera tile.
        // DiLink 5: while the source is released or reopening, the engine's mosaic is not current.
        boolean diLink5Reopening = isDiLink5IdleSourceUnavailable();
        byte[] hiRes = highResSliceJpeg(slice);
        if (hiRes != null && hiRes.length > 0) return hiRes;
        if (diLink5Reopening || isDiLink5IdleSourceUnavailable()) return null;
        // Fallback: crop the engine's pre-encoded 640×480 mosaic. Smaller
        // and lower-quality but free when the engine is publishing.
        byte[] mosaicJpeg = engineMosaicJpeg();
        if (mosaicJpeg == null || mosaicJpeg.length == 0) return null;
        if (CameraConfigResolver.isPassiveApaModeEnabled()) return mosaicJpeg;
        if (com.overdrive.app.camera.camera2.Camera2Platform.isSelected()) {
            return cropMosaicJpegToSlice(mosaicJpeg, camera2MosaicQuadrant(slice));
        }
        return cropMosaicJpegToSlice(mosaicJpeg, slice);
    }

    private static boolean isDiLink5IdleSourceUnavailable() {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline p =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            com.overdrive.app.camera.PanoramicCameraGpu cam = p == null ? null : p.getCamera();
            return cam != null && cam.isDiLink5IdleSourceUnavailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The mosaic places views by role (front TL, right TR, rear BL, left BR); camera2
     * slices carry other roles, so map to the legacy slice that owns that quadrant.
     */
    private static PanoramicSlice camera2MosaicQuadrant(PanoramicSlice slice) {
        java.util.EnumMap<CameraRole, CameraSourceRef> mappings = CameraProfiles.camera2Avm(
                com.overdrive.app.camera.camera2.Camera2AvmBackend.cachedSliceRoles())
                .getDefaultRoleMappings();
        for (java.util.Map.Entry<CameraRole, CameraSourceRef> entry : mappings.entrySet()) {
            if (entry.getValue().getPanoramicSlice() != slice) continue;
            switch (entry.getKey()) {
                case PANO_FRONT: return PanoramicSlice.SLICE_4;
                case PANO_RIGHT: return PanoramicSlice.SLICE_3;
                case PANO_REAR: return PanoramicSlice.SLICE_1;
                case PANO_LEFT: return PanoramicSlice.SLICE_2;
                default: return slice;
            }
        }
        return slice;
    }

    /** Direct GL render of one slice at full per-camera resolution.
     *  Layout-aware: passes 2x2 corner XY + per-role flip when DiLink 4
     *  mode is active, 4-strip stripOffsetX otherwise. */
    private static byte[] highResSliceJpeg(PanoramicSlice slice) {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline p =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            if (p == null) return null;
            com.overdrive.app.camera.PanoramicCameraGpu cam = p.getCamera();
            if (cam == null) return null;
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isEnabled()) {
                return cam.samplePerQuadrantJpeg(
                    slice.getStripOffsetX(),
                    slice.getCornerX(),
                    com.overdrive.app.camera.dilink5.DiLink5MosaicLayout.tileV(slice.getCornerY()),
                    0.0f, 0.0f);
            }
            if (cam.getCameraLayoutMode() == 3) {
                // 2x2-native HAL on DiLink 4. Slice → role → Variant A
                // corner+flip mapping. Reads Dilink4Constants so this stays in
                // lockstep with recorder / stream / blind-spot / AI-downscaler /
                // cropper — this was a SIXTH hardcoded copy of the layout and it
                // carried the same upside-down bug they did (Front fy=1,
                // Right fy=1), so per-quadrant high-res JPEG grabs of front and
                // right came out vertically inverted.
                //   FRONT (slice4) → producer TL  X-mirror
                //   RIGHT (slice3) → producer BR  no flip
                //   REAR  (slice1) → producer TR  no flip
                //   LEFT  (slice2) → producer BL  no flip
                float cx, cy, fx, fy;
                switch (slice) {
                    case SLICE_4:
                        cx = Dilink4Constants.CORNER_FRONT[0]; cy = Dilink4Constants.CORNER_FRONT[1];
                        fx = Dilink4Constants.FLIP_FRONT[0];   fy = Dilink4Constants.FLIP_FRONT[1];
                        break; // Front
                    case SLICE_3:
                        cx = Dilink4Constants.CORNER_RIGHT[0]; cy = Dilink4Constants.CORNER_RIGHT[1];
                        fx = Dilink4Constants.FLIP_RIGHT[0];   fy = Dilink4Constants.FLIP_RIGHT[1];
                        break; // Right
                    case SLICE_1:
                        cx = Dilink4Constants.CORNER_REAR[0];  cy = Dilink4Constants.CORNER_REAR[1];
                        fx = Dilink4Constants.FLIP_REAR[0];    fy = Dilink4Constants.FLIP_REAR[1];
                        break; // Rear
                    case SLICE_2:
                        cx = Dilink4Constants.CORNER_LEFT[0];  cy = Dilink4Constants.CORNER_LEFT[1];
                        fx = Dilink4Constants.FLIP_LEFT[0];    fy = Dilink4Constants.FLIP_LEFT[1];
                        break; // Left
                    default:      cx = slice.getCornerX(); cy = slice.getCornerY();
                                  fx = 0.0f; fy = 0.0f; break;
                }
                return cam.samplePerQuadrantJpeg(
                    slice.getStripOffsetX(), cx, cy, fx, fy);
            }
            if (cam.getCameraLayoutMode() == 1) {
                return cam.sampleFullResMosaicJpeg();
            }
            return cam.samplePerQuadrantJpeg(slice.getStripOffsetX());
        } catch (Throwable t) {
            logger.warn("highResSliceJpeg failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Crop the published mosaic to one of the four virtual views (front /
     * right / rear / left). Like {@link #capturePanoramicSliceJpeg} but keyed
     * by virtual-view enum — the dialog uses this for the "panoramic" kind.
     */
    public static byte[] capturePanoramicViewJpeg(CameraVirtualView view) {
        if (view == null) return null;
        if (com.overdrive.app.camera.camera2.Camera2Platform.isSelected()) {
            return capturePanoramicSliceJpeg(camera2SliceForView(view));
        }
        return capturePanoramicSliceJpeg(PanoramicSlice.fromLegacyView(view));
    }

    /** camera2: the physical slice that holds this view under the run's tile order. */
    private static PanoramicSlice camera2SliceForView(CameraVirtualView view) {
        CameraRole role;
        switch (view) {
            case RIGHT: role = CameraRole.PANO_RIGHT; break;
            case REAR: role = CameraRole.PANO_REAR; break;
            case LEFT: role = CameraRole.PANO_LEFT; break;
            case FRONT:
            default: role = CameraRole.PANO_FRONT; break;
        }
        CameraSourceRef ref = CameraProfiles.camera2Avm(
                com.overdrive.app.camera.camera2.Camera2AvmBackend.cachedSliceRoles())
                .getDefaultRoleMappings().get(role);
        PanoramicSlice slice = ref != null ? ref.getPanoramicSlice() : null;
        return slice != null ? slice : PanoramicSlice.fromLegacyView(view);
    }

    /** Volatile read of the engine's pre-encoded mosaic JPEG. */
    private static byte[] engineMosaicJpeg() {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline p =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            if (p == null) return null;
            // Gate on pipeline being currently running. The engine's
            // latestMosaicJpeg is a volatile that survives across pipeline
            // stop/start cycles — without this gate, opening the dialog
            // after a stop returns a stale JPEG from the previous session
            // until the new pipeline publishes its first mosaic.
            if (!p.isRunning()) return null;
            com.overdrive.app.surveillance.SurveillanceEngineGpu engine = p.getSentry();
            if (engine == null) return null;
            return engine.getLatestMosaicJpeg();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fallback when the engine isn't running (proximity-guard mode): post a
     * sync GL readback to the camera's glHandler, encode JPEG. Costs ~150 ms
     * but works whenever the camera pipeline is alive.
     */
    private static byte[] liveDownscalerMosaicJpeg() {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline p =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            if (p == null) return null;
            com.overdrive.app.camera.PanoramicCameraGpu cam = p.getCamera();
            if (cam == null) return null;
            // cameraId=0 is the "full mosaic" code path in getLatestJpegFrame.
            return cam.getLatestJpegFrame(0);
        } catch (Exception e) {
            logger.warn("liveDownscalerMosaicJpeg failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Open AVMCamera at the requested id/size, capture one frame, tear down.
     * Mirrors the order PanoramicCameraGpu uses: open → setCameraFps →
     * addPreviewSurface → startPreview. Stop/close in reverse on every exit.
     *
     * <p>Process-wide serialized via {@link #directPreviewBusy}: only one
     * caller can have a direct AVMCamera open at a time. Concurrent callers
     * fast-fail to null so the API handler falls through to the panoramic-
     * slice cache path instead of racing AVMCamera open() and getting a
     * confusing single-claim rejection.
     */
    private static byte[] captureWithSize(int cameraId, int width, int height, int fps, int timeoutMs) {
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            logger.warn("Direct camera previews are unavailable in DiLink 5 mode");
            return null;
        }
        if (com.overdrive.app.camera.camera2.Camera2Platform.isSelected()) {
            logger.warn("Direct camera previews are unavailable in camera2 mode");
            return null;
        }
        if (directPreviewTerminalRestart.get()
                || com.overdrive.app.daemon.CameraDaemon
                    .isProcessRestartPending()) {
            logger.warn("Direct camera preview refused while process "
                + "retirement is pending");
            return null;
        }
        if (!directPreviewBusy.compareAndSet(false, true)) {
            logger.info("Direct preview busy — refusing concurrent open for cam=" + cameraId);
            return null;
        }
        try {
            if (!com.overdrive.app.camera.dilink5.DiLink5Platform
                    .isDiLink4Selected()) {
                return captureLegacyPreviewWithHardTimeout(
                    cameraId, width, height, fps, timeoutMs);
            }
            return captureWithSizeLocked(cameraId, width, height, fps, timeoutMs);
        } finally {
            if (!directPreviewTerminalRestart.get()) {
                directPreviewBusy.set(false);
            }
        }
    }

    private static byte[] captureLegacyPreviewWithHardTimeout(
            int cameraId, int width, int height, int fps, int timeoutMs) {
        AtomicReference<byte[]> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(captureWithSizeLocked(
                    cameraId, width, height, fps, timeoutMs));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "LegacyDirectCameraPreview");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (Throwable spawnFailure) {
            logger.warn("Could not start bounded direct-preview worker: "
                + spawnFailure.getMessage());
            return null;
        }

        long hardTimeoutMs = Math.max(
            DIRECT_PREVIEW_HARD_TIMEOUT_MS,
            Math.max(0L, (long) timeoutMs) + 5_000L);
        long deadline = android.os.SystemClock.elapsedRealtime()
            + hardTimeoutMs;
        boolean interrupted = false;
        while (worker.isAlive()) {
            long remaining =
                deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            try {
                worker.join(Math.min(remaining, 200L));
            } catch (InterruptedException waitInterrupted) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            directPreviewTerminalRestart.set(true);
            String reason = "Direct AVMCamera preview blocked for "
                + hardTimeoutMs + "ms (id=" + cameraId + ")";
            try {
                com.overdrive.app.daemon.CameraDaemon
                    .requestUrgentCameraReleaseRestart(reason);
            } catch (Throwable restartFailure) {
                logger.error(reason + "; restart request failed: "
                    + restartFailure.getMessage());
            }
            logger.error(reason + " — terminal process retirement armed; "
                + "direct-preview gate left latched");
            return null;
        }

        Throwable captureFailure = failure.get();
        if (captureFailure != null) {
            logger.warn("Direct preview worker failed cam=" + cameraId + ": "
                + captureFailure.getMessage());
            return null;
        }
        return result.get();
    }

    private static byte[] captureWithSizeLocked(int cameraId, int width, int height, int fps, int timeoutMs) {
        Class<?> avmClass;
        try {
            avmClass = Class.forName("android.hardware.AVMCamera");
        } catch (ClassNotFoundException e) {
            logger.warn("AVMCamera class unavailable — cannot capture preview");
            return null;
        }

        HandlerThread thread = null;
        ImageReader reader = null;
        Surface readerSurface = null;
        Object cameraObj = null;
        // Tracks whether startPreview has actually run on cameraObj. The BYD
        // HAL treats stopPreview-before-startPreview as undefined (random
        // NPE inside JNI), so the outer finally must skip stopPreview when
        // this is false.
        boolean previewStarted = false;
        AtomicReference<Image> imageRef = new AtomicReference<>();

        try {
            thread = new HandlerThread("AvmPreview-" + cameraId + "-" + width + "x" + height);
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            CountDownLatch latch = new CountDownLatch(1);

            reader = createImageReader(width, height);
            if (reader == null) return null;
            readerSurface = reader.getSurface();

            // Content gate: reject ALL-ZERO frames instead of latching the
            // first arrival. A camera whose AVM/ISP power rail is down (post
            // ACC-OFF — see AccSentryDaemon's rail-hold writes) opens fine
            // and delivers frames, but every byte is zero; YUV(0,0,0) encodes
            // to a confident SOLID GREEN JPEG (BT.601 maps it to ~RGB 0,135,0),
            // which the camera-mapping dialog then displays as if it were a
            // live feed. Skipping zero frames inside the existing timeout
            // window also self-heals HAL warmup (first frames after open are
            // often zero-filled); a camera that never produces content times
            // out to null, so the dialog shows its placeholder + bounded
            // retry instead of green. The check is fail-open — anything
            // unexpected counts as content, so a format surprise can never
            // break previews outright.
            final java.util.concurrent.atomic.AtomicInteger zeroFramesRejected =
                new java.util.concurrent.atomic.AtomicInteger(0);
            reader.setOnImageAvailableListener(imageReader -> {
                Image image = null;
                try {
                    image = imageReader.acquireLatestImage();
                    if (image != null && !hasNonZeroLuma(image)) {
                        if (zeroFramesRejected.incrementAndGet() == 1) {
                            logger.info("Direct preview cam=" + cameraId
                                + ": all-zero frame rejected (AVM/ISP rail down"
                                + " or HAL warmup) — waiting for real content");
                        }
                        return; // finally closes it; keep listening
                    }
                    if (image != null && imageRef.compareAndSet(null, image)) {
                        latch.countDown();
                        image = null;  // ownership transferred to imageRef
                    }
                } catch (Exception e) {
                    logger.debug("Preview image callback failed: " + e.getMessage());
                } finally {
                    if (image != null) {
                        try { image.close(); } catch (Exception ignored) {}
                    }
                }
            }, handler);

            // Open carefully: if mOpen.invoke throws (InvocationTargetException
            // from BYD HAL), cameraObj is non-null from the constructor but
            // never opened. The outer finally must NOT call stopPreview/close
            // on it. Use a try-finally to null cameraObj on any open failure.
            boolean openSucceeded = false;
            try {
                try {
                    Constructor<?> ctor = avmClass.getDeclaredConstructor(int.class);
                    ctor.setAccessible(true);
                    cameraObj = ctor.newInstance(cameraId);
                    Method mOpen = avmClass.getDeclaredMethod("open");
                    mOpen.setAccessible(true);
                    Object openResult = mOpen.invoke(cameraObj);
                    if (!(openResult instanceof Boolean) || !(Boolean) openResult) {
                        logger.warn("AVMCamera.open(" + cameraId + ") returned " + openResult);
                        return null;
                    }
                    openSucceeded = true;
                    logger.info("AVMCamera opened cam=" + cameraId);
                } catch (NoSuchMethodException nsme) {
                    Method mStaticOpen = avmClass.getDeclaredMethod("open", int.class);
                    mStaticOpen.setAccessible(true);
                    cameraObj = mStaticOpen.invoke(null, cameraId);
                    if (cameraObj == null) {
                        logger.warn("AVMCamera.open(" + cameraId + ") factory returned null");
                        return null;
                    }
                    openSucceeded = true;
                    logger.info("AVMCamera opened (static factory) cam=" + cameraId);
                }
            } finally {
                if (!openSucceeded) {
                    // Open failed mid-flight (exception or open()=false).
                    // Drop the reference so outer finally doesn't try to
                    // stopPreview/close a never-opened AVMCamera.
                    cameraObj = null;
                }
            }

            // SOTA TOCTOU defense: re-check pipeline holding state immediately
            // after open. If a sibling cold-start race grabbed the same id
            // between our pre-check and now, back out cleanly — multi-claim
            // crashes the BYD HAL (event 1002).
            if (isCameraHeldByPipeline(cameraId)) {
                logger.warn("Backing out direct preview cam=" + cameraId
                        + " — pipeline acquired it during our open (TOCTOU race)");
                // Close immediately — startPreview hasn't been called, so the
                // outer finally's stopPreview-before-close sequence is
                // undefined per the BYD HAL. Skip stopPreview; just close.
                tryInvoke(avmClass, cameraObj, "close");
                cameraObj = null;
                return null;
            }

            AvmCameraHelper.setCameraFps(cameraObj, fps);

            try {
                Method mAddSurface = avmClass.getDeclaredMethod(
                        "addPreviewSurface", Surface.class, int.class);
                mAddSurface.setAccessible(true);
                mAddSurface.invoke(cameraObj, readerSurface, 0);
            } catch (Throwable t) {
                logger.warn("addPreviewSurface failed cam=" + cameraId + ": " + t.getMessage());
                // Surface attach failed — close camera directly (skip
                // stopPreview which hasn't been started).
                tryInvoke(avmClass, cameraObj, "close");
                cameraObj = null;
                return null;
            }

            try {
                Method mStart = avmClass.getDeclaredMethod("startPreview");
                mStart.setAccessible(true);
                mStart.invoke(cameraObj);
                previewStarted = true;
            } catch (Throwable t) {
                logger.warn("startPreview failed cam=" + cameraId + ": " + t.getMessage());
                tryInvoke(avmClass, cameraObj, "close");
                cameraObj = null;
                return null;
            }

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                int zeros = zeroFramesRejected.get();
                logger.warn("No usable frame after " + timeoutMs + "ms cam=" + cameraId
                        + " size=" + width + "x" + height
                        + (zeros > 0
                            ? " (" + zeros + " all-zero frames rejected — AVM/ISP"
                              + " rail likely down; tile shows placeholder, not green)"
                            : ""));
                return null;
            }

            Image image = imageRef.get();
            if (image == null) return null;
            try {
                byte[] jpeg = imageToJpeg(image, 80);
                logger.info("Captured preview cam=" + cameraId
                        + " size=" + width + "x" + height
                        + " bytes=" + (jpeg == null ? 0 : jpeg.length));
                return jpeg;
            } finally {
                try { image.close(); } catch (Exception ignored) {}
                imageRef.set(null);
            }
        } catch (Exception e) {
            logger.warn("Direct preview capture failed cam=" + cameraId
                    + " size=" + width + "x" + height + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            // Teardown order matters:
            //   1. Stop / close the camera so HAL stops dispatching frames.
            //   2. Drain the HandlerThread so any in-flight
            //      OnImageAvailableListener callback completes BEFORE we
            //      close the ImageReader. Closing the reader before the
            //      drain races acquireLatestImage() against a closed reader
            //      (caught as IllegalStateException, but fragile).
            //   3. Close the reader. The Surface is owned by the reader and
            //      released by reader.close() — calling surface.release()
            //      separately would be a double-release.
            //   4. Close any leaked Image still held in imageRef.
            if (cameraObj != null) {
                // Only call stopPreview if startPreview actually ran. The
                // BYD HAL throws / NPEs JNI when stopPreview is called on a
                // never-previewing camera (e.g. setCameraFps threw between
                // open and startPreview).
                if (previewStarted) {
                    tryInvoke(avmClass, cameraObj, "stopPreview");
                }
                tryInvoke(avmClass, cameraObj, "close");
            }
            if (thread != null) {
                try {
                    thread.quitSafely();
                    thread.join(250);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            // NOTE: readerSurface.release() intentionally omitted — the
            // Surface is owned by the ImageReader and released when the
            // reader is closed. Calling release() here would be a
            // redundant release on an already-released surface.
            Image leaked = imageRef.getAndSet(null);
            if (leaked != null) {
                try { leaked.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * YUV_420_888 ImageReader. PRIVATE format works for the GL pipeline
     * because it samples via EGLImage, but we need CPU pixels for the JPEG
     * encode path. Bitmap.wrapHardwareBuffer is API 29+; the BYD head unit
     * runs API 28, so PRIVATE here would yield an undecodable frame.
     */
    private static ImageReader createImageReader(int width, int height) {
        try {
            return ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
        } catch (Throwable t) {
            logger.warn("ImageReader YUV_420_888 creation failed at " + width + "x" + height
                    + ": " + t.getMessage());
            return null;
        }
    }

    private static byte[] imageToJpeg(Image image, int quality) {
        int format = image.getFormat();
        if (format == ImageFormat.YUV_420_888) {
            return yuv420888ToJpeg(image, quality);
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            HardwareBuffer hwb = null;
            try {
                hwb = image.getHardwareBuffer();
                if (hwb == null) return null;
                Bitmap bmp = Bitmap.wrapHardwareBuffer(hwb,
                        android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB));
                if (bmp == null) return null;
                Bitmap copy = bmp.copy(Bitmap.Config.ARGB_8888, false);
                bmp.recycle();
                if (copy == null) return null;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                copy.compress(Bitmap.CompressFormat.JPEG, quality, out);
                copy.recycle();
                return out.toByteArray();
            } catch (Throwable t) {
                logger.debug("PRIVATE → JPEG conversion failed: " + t.getMessage());
                return null;
            } finally {
                if (hwb != null) {
                    try { hwb.close(); } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    private static void tryInvoke(Class<?> cls, Object instance, String method) {
        try {
            Method m = cls.getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(instance);
        } catch (Throwable ignored) {}
    }

    /**
     * True when the frame carries ANY real luma. Sparse-samples the Y plane
     * (≤256 probes) and accepts a single sample above the near-zero floor —
     * deliberately strict (>2, not >10) so a genuinely dark night scene,
     * which still carries sensor noise / IR gain in scattered pixels, is
     * never mistaken for the rail-down ALL-ZERO condition this gate exists
     * to catch. FAIL-OPEN: any surprise (non-YUV format, missing planes,
     * buffer exception) reports "has content" so previews can never be
     * broken by the gate itself.
     */
    private static boolean hasNonZeroLuma(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0 || planes[0] == null) {
                return true;
            }
            java.nio.ByteBuffer y = planes[0].getBuffer();
            if (y == null) {
                return true;
            }
            int limit = y.limit();
            if (limit <= 0) {
                return true;
            }
            int step = Math.max(1, limit / 256);
            for (int i = 0; i < limit; i += step) {
                if ((y.get(i) & 0xFF) > 2) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    private static byte[] yuv420888ToJpeg(Image image, int quality) {
        byte[] nv21 = yuv420888ToNv21(image);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), quality, out);
        return out.toByteArray();
    }

    /**
     * Crop a 2×2-mosaic JPEG into the requested {@link PanoramicSlice}
     * quadrant. Mosaic layout: TL=Front (slice4), TR=Right (slice3),
     * BL=Rear (slice1), BR=Left (slice2) — strip-offset based, matches the
     * GL mosaic shader's quadrant assignment.
     */
    public static byte[] cropMosaicJpegToSlice(byte[] mosaicJpegBytes, PanoramicSlice slice) {
        if (mosaicJpegBytes == null || mosaicJpegBytes.length == 0 || slice == null) return null;
        BitmapRegionDecoder decoder = null;
        Bitmap cropped = null;
        try {
            // Decode only the requested quadrant. Decoding the complete mosaic
            // first allocates four times as many pixels on memory-limited units.
            decoder = BitmapRegionDecoder.newInstance(
                    mosaicJpegBytes, 0, mosaicJpegBytes.length, false);
            if (decoder == null) return null;
            int width = decoder.getWidth();
            int height = decoder.getHeight();
            int qW = Math.max(1, width / 2);
            int qH = Math.max(1, height / 2);
            int x, y;
            switch (slice) {
                case SLICE_4: x = 0;  y = 0;  break;   // Front → TL
                case SLICE_3: x = qW; y = 0;  break;   // Right → TR
                case SLICE_1: x = 0;  y = qH; break;   // Rear  → BL
                case SLICE_2: x = qW; y = qH; break;   // Left  → BR
                default:      x = 0;  y = 0;  break;
            }
            x = Math.max(0, Math.min(x, width - 1));
            y = Math.max(0, Math.min(y, height - 1));
            int cropW = Math.min(qW, width - x);
            int cropH = Math.min(qH, height - y);
            cropped = decoder.decodeRegion(new Rect(x, y, x + cropW, y + cropH), null);
            if (cropped == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!cropped.compress(Bitmap.CompressFormat.JPEG, 82, out)) return null;
            return out.toByteArray();
        } catch (Exception e) {
            logger.warn("cropMosaicJpegToSlice failed for " + slice + ": " + e.getMessage());
            return null;
        } finally {
            if (cropped != null) {
                try { cropped.recycle(); } catch (Exception ignored) {}
            }
            if (decoder != null) {
                try { decoder.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private static byte[] yuv420888ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        byte[] out = new byte[width * height * 3 / 2];
        int dst = 0;

        ByteBuffer yBuffer = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        for (int row = 0; row < height; row++) {
            int rowOffset = row * yRowStride;
            for (int col = 0; col < width; col++) {
                out[dst++] = yBuffer.get(rowOffset + col * yPixelStride);
            }
        }

        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        for (int row = 0; row < chromaHeight; row++) {
            int uRowOffset = row * uRowStride;
            int vRowOffset = row * vRowStride;
            for (int col = 0; col < chromaWidth; col++) {
                out[dst++] = vBuffer.get(vRowOffset + col * vPixelStride);
                out[dst++] = uBuffer.get(uRowOffset + col * uPixelStride);
            }
        }
        return out;
    }
}
