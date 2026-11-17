package com.overdrive.app.camera;

import com.overdrive.app.logging.DaemonLogger;

/**
 * Utility methods for AVMCamera capabilities that aren't used elsewhere in the codebase.
 * 
 * Camera lifecycle (open/close/startPreview/addPreviewSurface) is already handled
 * inline in PanoramicCameraGpu and BydCameraCoordinator via reflection.
 * This class only adds genuinely new capabilities:
 * - BmmCameraInfo discovery (instant camera ID lookup)
 * - setCameraFps (frame rate control)
 */
public final class AvmCameraHelper {

    private static final DaemonLogger logger = DaemonLogger.getInstance("AvmCameraHelper");

    private static final String BMM_CAMERA_INFO_CLASS = "android.hardware.BmmCameraInfo";

    /**
     * DIPlus 1.3.8-beta18 camera-selection order.
     *
     * <p>The native panoramic tags expose preview index 0. Only the APA
     * fallback exposes preview index 1.
     */
    private static final String[] DI4_PANO_TAGS = {"pano_h", "pano_l"};
    private static final String[] APA_FALLBACK_TAGS = {"apa", "byd_apa"};
    private static final String[] LEGACY_PANO_TAGS =
            {"pano_h", "pano_l", "byd_apa", "apa"};
    private static volatile PanoCameraSelection cachedPanoSelection;
    // resolve() is also read by HTTP diagnostics. Bound repeated hidden-API probes
    // without caching a missing HAL mapping forever (the service can initialize late).
    private static final long PANO_ID_LOOKUP_CACHE_MS = 10_000L;
    private static long lastPanoIdLookupMs = -1L;
    private static int lastPanoIdLookupResult = -1;

    private AvmCameraHelper() {}

    // ── Camera Discovery (REQ-1) ────────────────────────────────────────

    /** HAL-selected panoramic camera and the preview index used for that tag. */
    public static final class PanoCameraSelection {
        private final int cameraId;
        private final int previewIndex;
        private final String tag;

        private PanoCameraSelection(int cameraId, int previewIndex, String tag) {
            this.cameraId = cameraId;
            this.previewIndex = previewIndex;
            this.tag = tag;
        }

        public int getCameraId() {
            return cameraId;
        }

        public int getPreviewIndex() {
            return previewIndex;
        }

        public String getTag() {
            return tag;
        }
    }

    /**
     * Discovers the panoramic camera and preview index via
     * BmmCameraInfo.getCameraId() reflection.
     *
     * <p>This mirrors the uninterrupted DIPlus panoramic recorder:
     * pano_h → pano_l use preview index 0; apa → byd_apa use preview index 1.
     *
     * @return the HAL selection, or {@code null} if no panoramic tag exists
     */
    public static PanoCameraSelection discoverDi4PanoCameraSelection() {
        PanoCameraSelection cached = cachedPanoSelection;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> bmmClass = Class.forName(BMM_CAMERA_INFO_CLASS);

            // Dump raw system property for debugging
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method get = sp.getMethod("get", String.class);
                String camSort = (String) get.invoke(null, "vehicle.config.cam_sort");
                logger.info("vehicle.config.cam_sort = "
                    + (camSort != null && !camSort.isEmpty() ? "'" + camSort + "'" : "(empty/null)"));
            } catch (Exception e) {
                logger.warn("Could not read vehicle.config.cam_sort: " + e.getMessage());
            }

            // Enumerate all known tags and their resolved IDs
            java.lang.reflect.Method getCameraId = bmmClass.getDeclaredMethod("getCameraId", String.class);
            getCameraId.setAccessible(true);
            
            String[] allTags = {"front", "rear", "rvs", "rf", "dms", "face",
                "pano_h", "pano_l", "apa", "byd_apa", "d954_h_m", "d954_h_s", "d954_l_m", "d954_l_s"};
            StringBuilder sb = new StringBuilder("BmmCameraInfo IDs:");
            for (String tag : allTags) {
                try {
                    int id = (Integer) getCameraId.invoke(null, tag);
                    if (id >= 0) sb.append(" ").append(tag).append("=").append(id);
                } catch (Exception ignored) {}
            }
            logger.info(sb.toString());

            // DIPlus pano_h/pano_l path: preview index 0.
            PanoCameraSelection selection = findDi4Selection(
                    tag -> getCameraId.invoke(null, tag));
            if (selection != null) {
                cachedPanoSelection = selection;
                logger.info("Discovered panoramic camera: " + selection.getTag()
                        + " -> ID " + selection.getCameraId()
                        + ", previewIndex=" + selection.getPreviewIndex());
                return selection;
            }
            logger.info("BmmCameraInfo: no panoramic camera found for any tag");
            return null;
        } catch (ClassNotFoundException e) {
            logger.warn("BmmCameraInfo class not available on this device");
            return null;
        } catch (Exception e) {
            logger.warn("BmmCameraInfo discovery failed: " + e.getMessage());
            return null;
        }
    }

    /** Use only known panoramic tags, preserving DIPlus preview-index semantics. */
    static PanoCameraSelection findDi4Selection(CameraTagLookup lookup) {
        String[][] groups = {DI4_PANO_TAGS, APA_FALLBACK_TAGS};
        for (int group = 0; group < groups.length; group++) {
            for (String tag : groups[group]) {
                try {
                    Object value = lookup.cameraIdFor(tag);
                    if (value instanceof Integer) {
                        int id = (Integer) value;
                        if (PanoramicCameraIdPolicy.isValidCameraId(id)) {
                            return new PanoCameraSelection(id, group, tag);
                        }
                        if (id >= 0) {
                            logger.warn("Ignoring out-of-range BmmCameraInfo " + tag + "=" + id);
                        }
                    }
                } catch (Exception | LinkageError e) {
                    logger.warn("BmmCameraInfo " + tag + " lookup failed: " + e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * Legacy ID-only discovery. Keep its historical fallback order unchanged
     * so enabling the DI4 path cannot alter older vehicle behavior.
     */
    public static synchronized int discoverPanoCameraId() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastPanoIdLookupMs >= 0 && now >= lastPanoIdLookupMs
                && now - lastPanoIdLookupMs < PANO_ID_LOOKUP_CACHE_MS) {
            return lastPanoIdLookupResult;
        }
        int discovered = discoverPanoCameraIdUncached();
        lastPanoIdLookupResult = discovered;
        lastPanoIdLookupMs = now;
        return discovered;
    }

    private static int discoverPanoCameraIdUncached() {
        try {
            Class<?> bmmClass = Class.forName(BMM_CAMERA_INFO_CLASS);
            java.lang.reflect.Method getCameraId =
                    bmmClass.getDeclaredMethod("getCameraId", String.class);
            getCameraId.setAccessible(true);
            return findPanoIdForTags(tag -> getCameraId.invoke(null, tag));
        } catch (ClassNotFoundException e) {
            logger.info("BmmCameraInfo class unavailable; panoramic ID is not system-discoverable");
        } catch (ReflectiveOperationException | SecurityException | LinkageError e) {
            logger.warn("BmmCameraInfo lookup unavailable: " + e.getMessage());
        }
        return -1;
    }

    /** Per-tag errors are independent: one bad vendor tag must not hide a later good one. */
    interface CameraTagLookup {
        Object cameraIdFor(String tag) throws Exception;
    }

    static int findPanoIdForTags(CameraTagLookup lookup) {
        for (String tag : LEGACY_PANO_TAGS) {
            try {
                Object value = lookup.cameraIdFor(tag);
                if (value instanceof Integer) {
                    int id = (Integer) value;
                    if (PanoramicCameraIdPolicy.isValidCameraId(id)) {
                        logger.info("BmmCameraInfo panoramic tag " + tag + " -> camera ID " + id);
                        return id;
                    }
                    if (id >= 0) {
                        logger.warn("Ignoring out-of-range BmmCameraInfo " + tag + "=" + id);
                    }
                }
            } catch (Exception | LinkageError e) {
                logger.warn("BmmCameraInfo " + tag + " lookup failed: " + e.getMessage());
            }
        }
        logger.info("BmmCameraInfo has no usable panoramic tag (pano_h/pano_l/APA)");
        return -1;
    }

    // ── Frame Rate Control (REQ-2) ──────────────────────────────────────

    /**
     * Sets the camera frame rate via AVMCamera.setCameraFps(int).
     * Must be called after open() and before startPreview().
     *
     * @param cameraObj the AVMCamera instance (from reflection open() call)
     * @param fps desired frames per second
     * @return true if set successfully
     */
    public static boolean setCameraFps(Object cameraObj, int fps) {
        if (cameraObj == null) return false;
        try {
            java.lang.reflect.Method m = cameraObj.getClass().getDeclaredMethod("setCameraFps", int.class);
            m.setAccessible(true);
            Object result = m.invoke(cameraObj, fps);
            boolean ok = result instanceof Boolean && (Boolean) result;
            if (ok) {
                logger.info("Camera FPS set to " + fps);
            } else {
                logger.warn("setCameraFps(" + fps + ") returned false");
            }
            return ok;
        } catch (NoSuchMethodException e) {
            logger.warn("setCameraFps not available on this AVMCamera version");
            return false;
        } catch (Exception e) {
            logger.warn("setCameraFps failed: " + e.getMessage());
            return false;
        }
    }

}
