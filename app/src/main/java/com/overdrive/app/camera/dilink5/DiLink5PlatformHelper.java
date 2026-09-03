package com.overdrive.app.camera.dilink5;

import com.overdrive.app.camera.CameraProfiles;
import com.overdrive.app.config.UnifiedConfigManager;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Shark 6 vs Sealion 7 DiLink 5 platform detection and AIS camera-id mapping.
 *
 * <p>Shark uses the {@code libais_capture.so} sidecar (linker64 from {@code /data/app/.../lib/arm64/}).
 * Sealion 7 keeps the legacy {@code qcarcam_test} + {@code LD_PRELOAD} hook path.
 */
public final class DiLink5PlatformHelper {

    private static volatile Boolean sharkProfile;

    private DiLink5PlatformHelper() {}

    /** True when this head unit should use the AIS sidecar instead of qcarcam_test hook. */
    public static boolean usesAisSidecar() {
        return isSharkProfile();
    }

    public static boolean isSharkProfile() {
        return isSharkProfile(null);
    }

    public static boolean isSharkProfile(String vehicleModelHint) {
        if (sharkProfile != null) {
            return sharkProfile;
        }
        synchronized (DiLink5PlatformHelper.class) {
            if (sharkProfile != null) {
                return sharkProfile;
            }
            sharkProfile = inferShark(vehicleModelHint);
            return sharkProfile;
        }
    }

    /** Reset cached inference (tests). */
    public static void resetForTests() {
        sharkProfile = null;
    }

    private static boolean inferShark(String vehicleModelHint) {
        String selected = readSelectedVehicleModel();
        String hint = preferHint(selected, vehicleModelHint);
        if (hint != null) {
            String n = normalize(hint);
            if (n.contains("shark")) {
                return true;
            }
            if (n.contains("sealion")) {
                return false;
            }
        }

        String profile = UnifiedConfigManager.loadConfig()
                .optJSONObject("camera")
                .optString("cameraProfile", CameraProfiles.PROFILE_AUTO);
        if (CameraProfiles.PROFILE_DILINK5_SHARK.equalsIgnoreCase(profile)) {
            return true;
        }
        if (CameraProfiles.PROFILE_DILINK5_SEALION7.equalsIgnoreCase(profile)) {
            return false;
        }

        String vehicleType = getSystemProperty("ro.vehicle.type", "");
        if (vehicleType.toUpperCase(Locale.US).contains("DXF")) {
            return true;
        }

        // libais_client present with no other hint → Sealion 7 (do not steal SL7)
        return false;
    }

    /** Default AIS camera id for live stream startup. */
    public static int defaultAisCameraId() {
        return isSharkProfile() ? 8 : 0;
    }

    /** Mosaic argv fragment for Shark ALL view. */
    public static String mosaicArg() {
        return "mosaic=8,9,5,4,0,-1";
    }

    /**
     * Map live-view UI mode to the single-byte sidecar command.
     *
     * <p>UI modes: 0=ALL, 1=Front, 2=Right, 3=Rear, 4=Left, 9=Cabin/DMS.
     */
    public static int aisByteForViewMode(int uiMode) {
        if (isSharkProfile()) {
            switch (uiMode) {
                case 0:
                    return 31;
                case 1:
                    return 8;
                case 2:
                    return 9;
                case 3:
                    return 5;
                case 4:
                    return 4;
                case 9:
                    return 0;
                default:
                    return defaultAisCameraId();
            }
        }
        // Sealion 7 hook path (legacy hookMode mapping)
        switch (uiMode) {
            case 0:
                return 4;
            case 1:
                return 0;
            case 2:
                return 1;
            case 3:
                return 2;
            case 4:
                return 3;
            default:
                return 0;
        }
    }

    private static String preferHint(String selected, String fallback) {
        if (selected != null && !selected.isEmpty() && !"auto".equalsIgnoreCase(selected)) {
            return selected;
        }
        return fallback;
    }

    private static String readSelectedVehicleModel() {
        try {
            return UnifiedConfigManager.loadConfig()
                    .optJSONObject("vehicle")
                    .optString("selectedModel", "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.US)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }

    private static String getSystemProperty(String key, String def) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method get = clazz.getMethod("get", String.class, String.class);
            Object result = get.invoke(null, key, def);
            return result != null ? result.toString() : def;
        } catch (Throwable ignored) {
            return def;
        }
    }
}
