package com.overdrive.app.camera;

import java.util.Locale;

/**
 * DiLink 100 head units (BYD Atto 3 EVO, New E2 and siblings; Android 14,
 * {@code ro.vehicle.type=DiLink100_5.0UI}).
 *
 * <p>Field finding on a New E2 GS X: Android's camera service refuses every
 * Camera2 client while BYD's {@code persist.sys.all_camera_for_3rdapp} is unset,
 * but BYD's own {@code android.hardware.AVMCamera} (dilink-framework.jar on the
 * boot classpath) still streams the 5120x960 four-camera super-frame through
 * bmmcameraserver. The static {@code AVMCamera.open(int)} returns null for the
 * shell daemon only because its {@code BmmCameraInfo} validation reads
 * {@code vendor.vehicle.config.cam_sort} ({@code byd_camera_prop}), which the
 * shell cannot read; the constructor plus the instance {@code open()} skip that
 * check and open camera 0 ({@code pano_h}).
 */
public final class DiLink100Platform {

    /** BMM camera id of the 360 super-frame ({@code pano_h:0}) on DiLink 100. */
    public static final int PANO_CAMERA_ID = 0;

    private static volatile Boolean sCached;

    private DiLink100Platform() {
    }

    /** True when {@code ro.vehicle.type} names a DiLink 100 head unit. */
    public static boolean isDiLink100() {
        Boolean cached = sCached;
        if (cached != null) return cached;
        boolean result = isDiLink100VehicleType(readVehicleType());
        sCached = result;
        return result;
    }

    static boolean isDiLink100VehicleType(String vehicleType) {
        return vehicleType != null
                && vehicleType.trim().toLowerCase(Locale.ROOT).startsWith("dilink100");
    }

    private static String readVehicleType() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Object value = props.getMethod("get", String.class, String.class)
                    .invoke(null, "ro.vehicle.type", "");
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}
