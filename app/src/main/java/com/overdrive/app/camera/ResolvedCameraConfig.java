package com.overdrive.app.camera;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fully resolved runtime camera configuration.
 */
public final class ResolvedCameraConfig {
    private static final PanoramicSlice[] LEGACY_SLICE_FALLBACK = {
        PanoramicSlice.SLICE_4, // PANO_FRONT
        PanoramicSlice.SLICE_3, // PANO_RIGHT
        PanoramicSlice.SLICE_1, // PANO_REAR
        PanoramicSlice.SLICE_2  // PANO_LEFT
    };

    private final CameraProfile profile;
    private final String selectedProfileId;
    private final boolean autoProfile;
    private final int panoCameraId;
    private final int panoWidth;
    private final int panoHeight;
    private final int panoSurfaceMode;
    private final boolean manualPanoOverride;
    private final boolean validated;
    private final boolean fallbackFromProbe;
    private final String panoCameraIdSource;
    private final String panoSurfaceModeSource;
    private final String panoGeometrySource;
    private final EnumMap<CameraRole, CameraSourceRef> roleMappings;

    public ResolvedCameraConfig(
            CameraProfile profile,
            String selectedProfileId,
            boolean autoProfile,
            int panoCameraId,
            int panoWidth,
            int panoHeight,
            int panoSurfaceMode,
            boolean manualPanoOverride,
            boolean validated,
            boolean fallbackFromProbe,
            Map<CameraRole, CameraSourceRef> roleMappings) {
        this(profile, selectedProfileId, autoProfile, panoCameraId, panoWidth,
                panoHeight, panoSurfaceMode, manualPanoOverride, validated,
                fallbackFromProbe, "unspecified", "unspecified", "unspecified",
                roleMappings);
    }

    /**
     * Constructs a resolved config with diagnostic-only provenance labels.
     * Labels do not participate in camera selection or stream validation.
     */
    public ResolvedCameraConfig(
            CameraProfile profile,
            String selectedProfileId,
            boolean autoProfile,
            int panoCameraId,
            int panoWidth,
            int panoHeight,
            int panoSurfaceMode,
            boolean manualPanoOverride,
            boolean validated,
            boolean fallbackFromProbe,
            String panoCameraIdSource,
            String panoSurfaceModeSource,
            String panoGeometrySource,
            Map<CameraRole, CameraSourceRef> roleMappings) {
        this.profile = profile;
        this.selectedProfileId = selectedProfileId;
        this.autoProfile = autoProfile;
        this.panoCameraId = panoCameraId;
        this.panoWidth = panoWidth;
        this.panoHeight = panoHeight;
        this.panoSurfaceMode = panoSurfaceMode;
        this.manualPanoOverride = manualPanoOverride;
        this.validated = validated;
        this.fallbackFromProbe = fallbackFromProbe;
        this.panoCameraIdSource = panoCameraIdSource;
        this.panoSurfaceModeSource = panoSurfaceModeSource;
        this.panoGeometrySource = panoGeometrySource;
        this.roleMappings = new EnumMap<>(CameraRole.class);
        if (roleMappings != null) {
            this.roleMappings.putAll(roleMappings);
        }
    }

    public CameraProfile getProfile() {
        return profile;
    }

    public String getSelectedProfileId() {
        return selectedProfileId;
    }

    public boolean isAutoProfile() {
        return autoProfile;
    }

    public int getPanoCameraId() {
        return panoCameraId;
    }

    public int getPanoWidth() {
        return panoWidth;
    }

    public int getPanoHeight() {
        return panoHeight;
    }

    public int getPanoSurfaceMode() {
        return panoSurfaceMode;
    }

    public boolean isManualPanoOverride() {
        return manualPanoOverride;
    }

    public boolean isValidated() {
        return validated;
    }

    public boolean isFallbackFromProbe() {
        return fallbackFromProbe;
    }

    /** Source of the resolved camera ID; not a guarantee that a stream opened. */
    public String getPanoCameraIdSource() {
        return panoCameraIdSource;
    }

    /** Source of the resolved preview index; not an observed frame mode. */
    public String getPanoSurfaceModeSource() {
        return panoSurfaceModeSource;
    }

    /** Source of the resolved dimensions; saved values may be profile-seeded. */
    public String getPanoGeometrySource() {
        return panoGeometrySource;
    }

    public EnumMap<CameraRole, CameraSourceRef> getRoleMappings() {
        return new EnumMap<>(roleMappings);
    }

    public int getDirectCameraIdForRole(CameraRole role) {
        CameraSourceRef ref = roleMappings.get(role);
        if (ref == null || ref.getKind() != CameraSourceKind.DIRECT || ref.getCameraId() == null) {
            return -1;
        }
        return ref.getCameraId();
    }

    /**
     * Resolves panoramic role → slice. Always returns a non-null slice for
     * every PANO_* role: profile defaults first, then user mappings (which may
     * override), then any unused slice as last-resort fallback. Guarantees
     * {@link #getQuadrantStripOffsetX()} can never NPE.
     */
    private EnumMap<CameraRole, PanoramicSlice> buildResolvedPanoramicSlices() {
        EnumMap<CameraRole, PanoramicSlice> resolved = new EnumMap<>(CameraRole.class);
        Set<PanoramicSlice> used = new HashSet<>();
        CameraRole[] panoRoles = new CameraRole[] {
                CameraRole.PANO_FRONT,
                CameraRole.PANO_RIGHT,
                CameraRole.PANO_REAR,
                CameraRole.PANO_LEFT
        };

        EnumMap<CameraRole, PanoramicSlice> defaults = new EnumMap<>(CameraRole.class);
        EnumMap<CameraRole, CameraSourceRef> profileDefaults = profile != null
                ? profile.getDefaultRoleMappings()
                : new EnumMap<>(CameraRole.class);
        for (int i = 0; i < panoRoles.length; i++) {
            CameraRole role = panoRoles[i];
            CameraSourceRef src = profileDefaults.get(role);
            PanoramicSlice slice = src != null ? src.getPanoramicSlice() : null;
            defaults.put(role, slice != null ? slice : LEGACY_SLICE_FALLBACK[i]);
        }

        // First pass: honor user role mappings if they point at a slice.
        for (CameraRole role : panoRoles) {
            CameraSourceRef mapped = roleMappings.get(role);
            PanoramicSlice slice = mapped != null ? mapped.getPanoramicSlice() : null;
            if (slice != null && !used.contains(slice)) {
                resolved.put(role, slice);
                used.add(slice);
            }
        }

        // Second pass: fill remaining roles from profile defaults if unused.
        for (CameraRole role : panoRoles) {
            if (resolved.containsKey(role)) continue;
            PanoramicSlice fallback = defaults.get(role);
            if (fallback != null && !used.contains(fallback)) {
                resolved.put(role, fallback);
                used.add(fallback);
            }
        }

        // Third pass: any role still unmapped grabs the next unused slice.
        for (CameraRole role : panoRoles) {
            if (resolved.containsKey(role)) continue;
            for (PanoramicSlice candidate : PanoramicSlice.values()) {
                if (!used.contains(candidate)) {
                    resolved.put(role, candidate);
                    used.add(candidate);
                    break;
                }
            }
        }

        // Final guard: if all slices are somehow exhausted (impossible with 4
        // roles + 4 slices but defensive), fall back to legacy mapping.
        for (int i = 0; i < panoRoles.length; i++) {
            if (!resolved.containsKey(panoRoles[i])) {
                resolved.put(panoRoles[i], LEGACY_SLICE_FALLBACK[i]);
            }
        }

        return resolved;
    }

    public PanoramicSlice getSliceForRole(CameraRole role) {
        return buildResolvedPanoramicSlices().get(role);
    }

    public float[] getQuadrantStripOffsetX() {
        EnumMap<CameraRole, PanoramicSlice> slices = buildResolvedPanoramicSlices();
        return new float[] {
            slices.get(CameraRole.PANO_FRONT).getStripOffsetX(),
            slices.get(CameraRole.PANO_RIGHT).getStripOffsetX(),
            slices.get(CameraRole.PANO_REAR).getStripOffsetX(),
            slices.get(CameraRole.PANO_LEFT).getStripOffsetX()
        };
    }

    /**
     * Returns 8 floats: {frontX, frontY, rightX, rightY, rearX, rearY,
     * leftX, leftY} — the top-left of each role's 0.5×0.5 corner in a
     * 2x2-native HAL frame. Used by GpuStreamScaler / FoveatedCropper /
     * HighResPreviewSampler when the camera is in DiLink 4 mode (HAL
     * emits a stitched 2x2 mosaic, no horizontal-strip rearrangement).
     */
    public float[] getQuadrantCornerOffsetsXY() {
        EnumMap<CameraRole, PanoramicSlice> slices = buildResolvedPanoramicSlices();
        PanoramicSlice f = slices.get(CameraRole.PANO_FRONT);
        PanoramicSlice r = slices.get(CameraRole.PANO_RIGHT);
        PanoramicSlice b = slices.get(CameraRole.PANO_REAR);
        PanoramicSlice l = slices.get(CameraRole.PANO_LEFT);
        return new float[] {
            f.getCornerX(), f.getCornerY(),
            r.getCornerX(), r.getCornerY(),
            b.getCornerX(), b.getCornerY(),
            l.getCornerX(), l.getCornerY()
        };
    }

    public JSONObject panoramicSlicesToJson() {
        EnumMap<CameraRole, PanoramicSlice> slices = buildResolvedPanoramicSlices();
        JSONObject out = new JSONObject();
        for (Map.Entry<CameraRole, PanoramicSlice> entry : slices.entrySet()) {
            putSafely(out, entry.getKey().getKey(), entry.getValue().toJson());
        }
        return out;
    }

    public JSONObject roleMappingsToJson() {
        JSONObject out = new JSONObject();
        for (Map.Entry<CameraRole, CameraSourceRef> entry : roleMappings.entrySet()) {
            putSafely(out, entry.getKey().getKey(), entry.getValue().toJson());
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
