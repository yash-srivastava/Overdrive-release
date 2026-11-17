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
 */
public final class DiLink100Platform {

    /** BMM camera id of the 360 super-frame ({@code pano_h:0}). */
    public static final int PANO_CAMERA_ID = 0;

    private static volatile Boolean sByd100f;

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
