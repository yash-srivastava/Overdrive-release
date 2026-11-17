package com.overdrive.app.camera;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of known camera profiles.
 *
 * Add new vehicle variants here. {@link #infer(String)} accepts either the
 * user-selected vehicle model ID or a system model string. BYD head units
 * often expose only a generic {@code ro.product.model} such as "BYD AUTO",
 * so field-verified selected-model mappings are preferred by the resolver.
 */
public final class CameraProfiles {
    public static final String PROFILE_AUTO = "auto";
    public static final String PROFILE_LEGACY_SEAL_ATTO = "legacy_seal_atto";
    public static final String PROFILE_ATTO_3 = "atto3";
    public static final String PROFILE_TANG_2022 = "tang_2022";
    public static final String PROFILE_DILINK5_SEALION7 = "dilink5_sealion7";
    public static final String PROFILE_CAMERA2_AVM = "camera2_avm";
    public static final String PROFILE_DILINK100_AVM = "dilink100_avm";

    /** Role shown in each super-frame slice (slice1..slice4) when the HAL names no order. */
    public static final CameraRole[] CAMERA2_DEFAULT_SLICE_ROLES = {
        CameraRole.PANO_FRONT, CameraRole.PANO_RIGHT, CameraRole.PANO_LEFT, CameraRole.PANO_REAR
    };

    private static final LinkedHashMap<String, CameraProfile> PROFILES = new LinkedHashMap<>();

    // Per-quadrant vertical FOV in degrees AFTER HAL dewarp.
    // Quadrant order: 0=front, 1=right, 2=rear, 3=left.
    //
    // Front and rear cameras are typically ultra-wide fisheyes mounted in
    // the BYD logo / rear plate looking down to capture the area
    // immediately around the bumpers (~115° vertical effective extent
    // after dewarp). Side cameras live in the mirror housings with
    // tighter optics to fit the housing geometry (~95° vertical).
    //
    // Numbers are derived from typical AVM hardware datasheets; the
    // distance-estimation math is robust to ±20% FOV error so these are
    // meaningfully better than a single global constant even without
    // per-vehicle calibration. See validation report in feedback memory
    // for the geometric analysis.
    private static final float[] FOV_DEG_DEFAULT = { 115f, 95f, 115f, 95f };

    static {
        EnumMap<CameraRole, CameraSourceRef> legacyMappings = new EnumMap<>(CameraRole.class);
        legacyMappings.put(CameraRole.PANO_FRONT, CameraSourceRef.panoramicSlice(PanoramicSlice.SLICE_4));
        legacyMappings.put(CameraRole.PANO_RIGHT, CameraSourceRef.panoramicSlice(PanoramicSlice.SLICE_3));
        legacyMappings.put(CameraRole.PANO_REAR, CameraSourceRef.panoramicSlice(PanoramicSlice.SLICE_1));
        legacyMappings.put(CameraRole.PANO_LEFT, CameraSourceRef.panoramicSlice(PanoramicSlice.SLICE_2));

        register(new CameraProfile(
                PROFILE_LEGACY_SEAL_ATTO,
                "Legacy panoramic (camera 1)",
                1,
                5120,
                960,
                0,
                1280,
                960,
                legacyMappings,
                FOV_DEG_DEFAULT));

        // Field-verified on a DiLink 3.0 Atto 3 (Android 10): the BMM HAL
        // advertises pano_h -> camera 0 at 5120x960. Camera 1 is invalid on
        // this unit and even crashes the vendor bmmcameraserver during the
        // failed close path, so Atto 3 must not inherit the camera-1 legacy
        // default.
        register(new CameraProfile(
                PROFILE_ATTO_3,
                "BYD Atto 3 (field verified)",
                0,
                5120,
                960,
                0,
                1280,
                960,
                legacyMappings,
                FOV_DEG_DEFAULT));

        EnumMap<CameraRole, CameraSourceRef> dilink5Mappings = new EnumMap<>(CameraRole.class);
        dilink5Mappings.put(CameraRole.PANO_FRONT, CameraSourceRef.panoramicSlice(PanoramicSlice.SLICE_4));
        dilink5Mappings.put(CameraRole.PANO_RIGHT, CameraSourceRef.panoramicSlice(PanoramicSlice.SLICE_3));
        dilink5Mappings.put(CameraRole.PANO_REAR, CameraSourceRef.panoramicSlice(PanoramicSlice.SLICE_1));
        dilink5Mappings.put(CameraRole.PANO_LEFT, CameraSourceRef.panoramicSlice(PanoramicSlice.SLICE_2));

        // DiLink 5 fast_cam_capture source: four 1920x1300 sensors are
        // center-cropped and composed into a native 1920x1080 2x2 mosaic.
        register(new CameraProfile(
                PROFILE_DILINK5_SEALION7,
                "BYD DiLink 5.0 (Sealion 7 / Snapdragon 8155)",
                0,
                1920,
                1080,
                0,
                1920,
                1080,
                dilink5Mappings,
                FOV_DEG_DEFAULT,
                1920,
                1080));

        EnumMap<CameraRole, CameraSourceRef> tangMappings = new EnumMap<>(legacyMappings);
        tangMappings.put(CameraRole.WINDSHIELD, CameraSourceRef.direct(0));
        register(new CameraProfile(
                PROFILE_TANG_2022,
                "BYD Tang 2022",
                2,
                5120,
                720,
                0,
                1280,
                720,
                tangMappings,
                FOV_DEG_DEFAULT));
    }

    private CameraProfiles() {
    }

    private static void register(CameraProfile profile) {
        PROFILES.put(profile.getId(), profile);
    }

    public static CameraProfile get(String id) {
        CameraProfile profile = PROFILES.get(id);
        return profile != null ? profile : getLegacyDefault();
    }

    public static CameraProfile getLegacyDefault() {
        return PROFILES.get(PROFILE_LEGACY_SEAL_ATTO);
    }

    /**
     * Camera2 360 super-frame: four 1280x960 views side by side. {@code sliceRoles[i]}
     * is the role in slice i+1; anything but a permutation of the four 360 roles
     * falls back to {@link #CAMERA2_DEFAULT_SLICE_ROLES}.
     *
     * <p>Deliberately NOT in {@link #PROFILES}: the mode selects it, so it must
     * stay out of the selectable list, and a stale persisted id resolves to the
     * legacy default instead of leaking 360 geometry into another mode.
     */
    public static CameraProfile camera2Avm(CameraRole[] sliceRoles) {
        CameraRole[] roles = isPanoRolePermutation(sliceRoles)
                ? sliceRoles : CAMERA2_DEFAULT_SLICE_ROLES;
        EnumMap<CameraRole, CameraSourceRef> mappings = new EnumMap<>(CameraRole.class);
        PanoramicSlice[] slices = PanoramicSlice.values();
        for (int i = 0; i < 4; i++) {
            mappings.put(roles[i], CameraSourceRef.panoramicSlice(slices[i]));
        }
        return new CameraProfile(
                PROFILE_CAMERA2_AVM,
                "BYD 360 via Camera2 (DiLink 100)",
                2,
                5120,
                960,
                0,
                1280,
                960,
                mappings,
                FOV_DEG_DEFAULT);
    }

    /**
     * BYD100F DiLink 100 in Default mode: BYD's AVMCamera camera 0 (pano_h) at 5120x960, slices
     * front, right, left, rear (field verified on a New E2, #741). Not in {@link #PROFILES} for the
     * same reason as {@link #camera2Avm}: only the head unit selects it.
     */
    public static CameraProfile dilink100Avm() {
        EnumMap<CameraRole, CameraSourceRef> mappings = new EnumMap<>(CameraRole.class);
        PanoramicSlice[] slices = PanoramicSlice.values();
        for (int i = 0; i < 4; i++) {
            mappings.put(CAMERA2_DEFAULT_SLICE_ROLES[i], CameraSourceRef.panoramicSlice(slices[i]));
        }
        return new CameraProfile(
                PROFILE_DILINK100_AVM,
                "BYD DiLink 100 (AVMCamera, field verified)",
                DiLink100Platform.PANO_CAMERA_ID,
                5120,
                960,
                0,
                1280,
                960,
                mappings,
                FOV_DEG_DEFAULT);
    }

    static boolean isPanoRolePermutation(CameraRole[] roles) {
        if (roles == null || roles.length != 4) return false;
        java.util.EnumSet<CameraRole> seen = java.util.EnumSet.noneOf(CameraRole.class);
        for (CameraRole role : roles) {
            if (role != CameraRole.PANO_FRONT && role != CameraRole.PANO_RIGHT
                    && role != CameraRole.PANO_REAR && role != CameraRole.PANO_LEFT) {
                return false;
            }
            if (!seen.add(role)) return false;
        }
        return true;
    }

    public static CameraProfile infer(String vehicleModel) {
        if (vehicleModel != null) {
            String normalized = vehicleModel.toLowerCase(Locale.US)
                    .replace("-", "")
                    .replace("_", "")
                    .replace(" ", "");
            if (normalized.contains("atto3") || normalized.contains("yuanplus")) {
                return get(PROFILE_ATTO_3);
            }
        }

        // Tang profile split remains disabled. Its separate profile caused
        // daemon hangs on the tested firmware, so unknown/Tang models retain
        // the camera-1 legacy fallback until a field-verified mapping exists.
        // if (vehicleModel != null) {
        //     String normalized = vehicleModel.toLowerCase(Locale.US);
        //     if (normalized.contains("tang")) {
        //         return get(PROFILE_TANG_2022);
        //     }
        // }
        return getLegacyDefault();
    }

    public static boolean isKnownProfile(String id) {
        return PROFILES.containsKey(id);
    }

    public static JSONArray toJsonArray() {
        JSONArray out = new JSONArray();
        JSONObject autoOption = new JSONObject();
        putSafely(autoOption, "id", PROFILE_AUTO);
        putSafely(autoOption, "label", "Auto detect");
        out.put(autoOption);
        for (Map.Entry<String, CameraProfile> entry : PROFILES.entrySet()) {
            out.put(entry.getValue().toJson());
        }
        return out;
    }

    private static void putSafely(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to write JSON field '" + key + "'", e);
        }
    }
}
