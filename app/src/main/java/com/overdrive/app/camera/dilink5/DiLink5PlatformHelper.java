package com.overdrive.app.camera.dilink5;

import com.overdrive.app.camera.CameraProfiles;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.config.VehicleModelSelection;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Shark 6 vs Sealion 7 DiLink 5 platform detection and live-view camera-id mapping.
 *
 * <p>Do not use {@code Build.MODEL} alone — field Shark units often report
 * {@code BYD AUTO}. {@code ro.vehicle.type} containing {@code DXF} is the
 * authoritative Shark hardware signal. Persisted model/profile values are
 * fallbacks because fresh installs may contain cosmetic Sealion defaults.
 *
 * <p>Logical FastCam indices: 0 front, 1 right, 2 rear, 3 left, 4 = 2x2 mosaic,
 * 5 = 4K mosaic, 6 = cabin/dashcam. Hardware remap is only via {@code --cams} /
 * {@code nativeSetCameraMapping}.
 */
public final class DiLink5PlatformHelper {

    private static volatile Boolean sharkProfile;

    private DiLink5PlatformHelper() {}

    public static boolean isSharkProfile() {
        return isSharkProfile(null);
    }

    /**
     * Cached once. {@code configuredModel} is an optional hint (e.g. modelId
     * from config) merged with the selected-model / profile / DXF order.
     */
    public static boolean isSharkProfile(String configuredModel) {
        if (sharkProfile != null) {
            return sharkProfile;
        }
        synchronized (DiLink5PlatformHelper.class) {
            if (sharkProfile != null) {
                return sharkProfile;
            }
            sharkProfile = inferShark(configuredModel);
            return sharkProfile;
        }
    }

    /** Reset cached inference (tests / after config or APK change). */
    public static void resetForTests() {
        sharkProfile = null;
    }

    /** Clear cached Shark/Sealion decision so DXF / config can be re-read. */
    public static void clearCachedProfile() {
        sharkProfile = null;
    }

    /** Hardware-only Shark check; safe while the unified-config lock is held. */
    public static boolean isSharkHardware() {
        String vehicleType = getSystemProperty("ro.vehicle.type", "");
        if (vehicleType == null || vehicleType.isEmpty()) {
            vehicleType = readPropViaGetprop("ro.vehicle.type");
        }
        return isDxfVehicleType(vehicleType);
    }

    static boolean isDxfVehicleType(String vehicleType) {
        return vehicleType != null
                && vehicleType.toUpperCase(Locale.US).contains("DXF");
    }

    private static boolean inferShark(String configuredModel) {
        String vehicleType = getSystemProperty("ro.vehicle.type", "");
        if (vehicleType == null || vehicleType.isEmpty()) {
            vehicleType = readPropViaGetprop("ro.vehicle.type");
        }
        String selected = readConfiguredModelHint();
        String profile = readCameraProfile();
        return inferShark(configuredModel, selected, profile, vehicleType);
    }

    /** Pure signal evaluation kept separate so precedence is unit-testable. */
    static boolean inferShark(
            String configuredModel,
            String selectedModel,
            String cameraProfile,
            String vehicleType) {
        // Hardware identity wins over stale/default persisted selections.
        if (isDxfVehicleType(vehicleType)) {
            return true;
        }

        // Fallback 1) selectedModel / hint
        String hint = preferHint(selectedModel, configuredModel);
        if (hint != null && !hint.isEmpty()) {
            String n = normalize(hint);
            if (n.contains("shark")) {
                return true;
            }
            if (n.contains("sealion")) {
                return false;
            }
        }

        // Fallback 2) camera.cameraProfile
        if (CameraProfiles.PROFILE_DILINK5_SHARK.equalsIgnoreCase(cameraProfile)
                || "dilink5_shark6".equalsIgnoreCase(cameraProfile)) {
            return true;
        }
        if (CameraProfiles.PROFILE_DILINK5_SEALION7.equalsIgnoreCase(cameraProfile)) {
            return false;
        }

        return false;
    }

    private static String readPropViaGetprop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/getprop", key});
            try (java.io.BufferedReader br =
                         new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line = br.readLine();
                p.waitFor();
                return line != null ? line.trim() : "";
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Default FastCam camera id for live stream startup (2x2 mosaic). */
    public static int defaultAisCameraId() {
        return 4;
    }

    /**
     * Map live-view UI mode to FastCam {@code setActiveCamera} logical index.
     *
     * <p>UI: 0=ALL→4, 1=Front→0, 2=Right→1, 3=Rear→2, 4=Left→3, 6/9=Cabin→6.
     */
    public static int aisByteForViewMode(int uiMode) {
        switch (uiMode) {
            case 0:
                return 4; // 2x2 mosaic
            case 1:
                return 0; // Front
            case 2:
                return 1; // Right
            case 3:
                return 2; // Rear
            case 4:
                return 3; // Left
            case 6:
            case 9:
                return 6; // Cabin / dashcam
            default:
                return defaultAisCameraId();
        }
    }

    private static String preferHint(String selected, String fallback) {
        if (selected != null && !selected.isEmpty() && !"auto".equalsIgnoreCase(selected)) {
            return selected;
        }
        if (fallback != null && !fallback.isEmpty() && !"auto".equalsIgnoreCase(fallback)) {
            return fallback;
        }
        return selected != null ? selected : fallback;
    }

    static String readConfiguredModelHint() {
        try {
            String id = UnifiedConfigManager.getSelectedVehicleModelId();
            if (id != null && !id.trim().isEmpty()) {
                return id.trim();
            }
        } catch (Throwable ignored) {}
        try {
            JSONObject vehicle = UnifiedConfigManager.loadConfig().optJSONObject("vehicle");
            if (vehicle == null) return "";
            String modelId = vehicle.optString("modelId", "").trim();
            String source = vehicle.optString("modelSource", "");
            String selectedModel = vehicle.optString("selectedModel", "").trim();
            return resolveConfiguredModel(modelId, source, selectedModel);
        } catch (Throwable ignored) {
            return "";
        }
    }

    static String resolveConfiguredModel(
            String modelId,
            String modelSource,
            String selectedModel) {
        String resolved = VehicleModelSelection.resolvedModelId(modelId, modelSource);
        if (resolved != null) return resolved;
        resolved = VehicleModelSelection.resolvedModelId(selectedModel, modelSource);
        return resolved != null ? resolved : "";
    }

    private static String readCameraProfile() {
        try {
            JSONObject camera = UnifiedConfigManager.loadConfig().optJSONObject("camera");
            if (camera == null) return CameraProfiles.PROFILE_AUTO;
            return camera.optString("cameraProfile", CameraProfiles.PROFILE_AUTO);
        } catch (Throwable ignored) {
            return CameraProfiles.PROFILE_AUTO;
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
