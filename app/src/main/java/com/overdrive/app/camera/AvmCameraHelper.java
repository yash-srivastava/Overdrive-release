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
            for (String tag : DI4_PANO_TAGS) {
                Object result = getCameraId.invoke(null, tag);
                if (result instanceof Integer) {
                    int id = (Integer) result;
                    if (id >= 0) {
                        PanoCameraSelection selection =
                                new PanoCameraSelection(id, 0, tag);
                        cachedPanoSelection = selection;
                        logger.info("Discovered panoramic camera: " + tag
                                + " → ID " + id + ", previewIndex=0");
                        return selection;
                    }
                }
            }

            // DIPlus APA fallback: preview index 1.
            for (String tag : APA_FALLBACK_TAGS) {
                Object result = getCameraId.invoke(null, tag);
                if (result instanceof Integer) {
                    int id = (Integer) result;
                    if (id >= 0) {
                        PanoCameraSelection selection =
                                new PanoCameraSelection(id, 1, tag);
                        cachedPanoSelection = selection;
                        logger.info("Discovered panoramic camera fallback: "
                                + tag + " → ID " + id
                                + ", previewIndex=1");
                        return selection;
                    }
                }
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

    /**
     * Legacy ID-only discovery. Keep its historical fallback order unchanged
     * so enabling the DI4 path cannot alter older vehicle behavior.
     */
    public static int discoverPanoCameraId() {
        try {
            Class<?> bmmClass = Class.forName(BMM_CAMERA_INFO_CLASS);
            java.lang.reflect.Method getCameraId =
                    bmmClass.getDeclaredMethod("getCameraId", String.class);
            getCameraId.setAccessible(true);
            for (String tag : LEGACY_PANO_TAGS) {
                Object result = getCameraId.invoke(null, tag);
                if (result instanceof Integer && (Integer) result >= 0) {
                    int id = (Integer) result;
                    logger.info("Discovered panoramic camera: " + tag
                            + " → ID " + id);
                    return id;
                }
            }
            logger.info("BmmCameraInfo: no panoramic camera found for any tag");
        } catch (ClassNotFoundException e) {
            logger.warn("BmmCameraInfo class not available on this device");
        } catch (Exception e) {
            logger.warn("BmmCameraInfo discovery failed: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Resolve only the native panoramic tags for legacy DiLink 3.
     *
     * <p>Unlike the DIPlus APA fallback, the legacy recorder has no verified
     * preview-index contract for APA. Never substitute an APA camera ID here:
     * opening the wrong vendor camera can destabilize the native camera service.
     * This lookup does not open a camera or claim that a stream is validated.
     */
    public static int discoverDi3NativePanoCameraId() {
        try {
            Class<?> bmmClass = Class.forName(BMM_CAMERA_INFO_CLASS);
            java.lang.reflect.Method getCameraId =
                    bmmClass.getDeclaredMethod("getCameraId", String.class);
            getCameraId.setAccessible(true);
            for (String tag : DI4_PANO_TAGS) {
                try {
                    Object result = getCameraId.invoke(null, tag);
                    if (result instanceof Integer && (Integer) result >= 0) {
                        int id = (Integer) result;
                        logger.info("DiLink 3 HAL panoramic tag " + tag + " id=" + id);
                        return id;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    logger.warn("DiLink 3 HAL tag " + tag + " unavailable: " + e.getMessage());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warn("DiLink 3 HAL discovery unavailable: " + e.getMessage());
        }
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
