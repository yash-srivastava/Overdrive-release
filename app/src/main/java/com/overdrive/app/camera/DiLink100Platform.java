package com.overdrive.app.camera;

import com.overdrive.app.setup.VehicleSetupDetector;

/**
 * BYD100F DiLink 100 head units (Atto 3 EVO, Sealion 8, New E2; Android 14).
 *
 * <p>Field finding (New E2 GS X, #741): BYD's camera service refuses every Camera2 client while
 * {@code persist.sys.all_camera_for_3rdapp} is unset, but BYD's {@code android.hardware.AVMCamera}
 * (dilink-framework.jar, boot classpath) streams the 5120x960 super-frame through bmmcameraserver.
 * The static {@code AVMCamera.open(int)} rejects every id for the shell daemon only because its
 * {@code BmmCameraInfo} check reads {@code vendor.vehicle.config.cam_sort}, which shell cannot read;
 * {@code new AVMCamera(id, 0)} plus the instance {@code open()} skip that check.
 *
 * <p>The 360 camera's id is not the same on every BYD100F unit: the New E2 GS X has
 * {@code pano_h:0}, the Atto 3 EVO has {@code oms:0;dms:1;pano_h:2}, where id 0 is the 1920x1080
 * cabin (occupant monitoring) camera. The id is therefore found at open time: the first id that
 * opens with a 360 super-frame strip.
 */
public final class DiLink100Platform {

    /** Default 360 camera id (pano_h on the New E2); the profile and the search start from it. */
    public static final int PANO_CAMERA_ID = 0;
    /** Highest AVMCamera id tried when searching for the 360 camera (0..5 field-tested on two cars). */
    public static final int MAX_PANO_SEARCH_ID = 5;
    /** A 360 super-frame is a strip of four views; cabin/driver cameras are 16:9. */
    static final int MIN_PANO_WIDTH = 2560;

    private static volatile Boolean sByd100f;
    private static volatile int sResolvedPanoId = -1;

    private DiLink100Platform() {
    }

    /** DiLink 100 with a BYD100F build identity, classified like the vehicle setup card. */
    public static boolean isByd100f() {
        Boolean cached = sByd100f;
        if (cached != null) return cached;
        boolean result;
        try {
            result = VehicleSetupDetector.isByd100f(new VehicleSetupDetector.Evidence(
                    readProperty("ro.vehicle.type"), android.os.Build.MODEL,
                    android.os.Build.PRODUCT, android.os.Build.DEVICE, false));
        } catch (Throwable t) {
            result = false;
        }
        sByd100f = result;
        return result;
    }

    /** Default camera mode on a BYD100F unit: the legacy AVMCamera path serves the 360 view. */
    public static boolean isAvmPathActive() {
        return isByd100f()
                && !com.overdrive.app.camera.camera2.Camera2Platform.isSelected()
                && !com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                && !com.overdrive.app.camera.dilink5.DiLink5Platform.isDiLink4Selected();
    }

    /** True for a 360 super-frame strip (e.g. 5120x960), false for a single 16:9 camera. */
    static boolean isPanoSuperFrame(int width, int height) {
        return width >= MIN_PANO_WIDTH && height > 0 && width >= 4 * height;
    }

    /** The 360 camera id found by the last successful open in this process, or -1 before that. */
    public static int resolvedPanoCameraId() {
        return sResolvedPanoId;
    }

    static void setResolvedPanoCameraId(int id) {
        sResolvedPanoId = id;
    }

    private static String readProperty(String key) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Object value = props.getMethod("get", String.class, String.class).invoke(null, key, "");
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}
