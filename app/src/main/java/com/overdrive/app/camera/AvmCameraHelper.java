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

    /** Panoramic camera tags to try, in priority order. */
    private static final String[] PANO_TAGS = {"pano_h", "pano_l", "byd_apa", "apa"};

    private AvmCameraHelper() {}

    // ── Camera Discovery (REQ-1) ────────────────────────────────────────

    /**
     * Discovers the panoramic camera ID via BmmCameraInfo.getCameraId() reflection.
     * BmmCameraInfo reads the system property vehicle.config.cam_sort which maps
     * camera tags to IDs. Tries pano_h → pano_l → byd_apa → apa.
     *
     * @return camera ID (>= 0) or -1 if not found
     */
    public static int discoverPanoCameraId() {
        try {
            Class<?> bmmClass = Class.forName(BMM_CAMERA_INFO_CLASS);
            
            // Dump raw system property for debugging
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method get = sp.getMethod("get", String.class);
                String camSort = (String) get.invoke(null, "vehicle.config.cam_sort");
                logger.info("vehicle.config.cam_sort = " + (camSort != null && !camSort.isEmpty() ? "'" + camSort + "'" : "(empty/null)"));
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

            // Try panoramic tags in priority order
            for (String tag : PANO_TAGS) {
                Object result = getCameraId.invoke(null, tag);
                if (result instanceof Integer) {
                    int id = (Integer) result;
                    if (id >= 0) {
                        logger.info("Discovered panoramic camera: " + tag + " → ID " + id);
                        return id;
                    }
                }
            }
            logger.info("BmmCameraInfo: no panoramic camera found for any tag");
            return -1;
        } catch (ClassNotFoundException e) {
            logger.warn("BmmCameraInfo class not available on this device");
            return -1;
        } catch (Exception e) {
            logger.warn("BmmCameraInfo discovery failed: " + e.getMessage());
            return -1;
        }
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
