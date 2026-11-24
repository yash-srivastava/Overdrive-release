package com.overdrive.app.camera;

import com.overdrive.app.camera.dilink5.DiLink5Platform;

import java.util.EnumMap;
import java.util.Locale;

/** Explicit, camera-only opt-in for a SurfaceTexture-backed preview Surface. */
public final class AvmSurfaceTexturePlatform {
    public static final String MODE = "surfacetexture";
    /** Resolved (never selectable or persisted) profile carrying the BYD100F tile order. */
    public static final String PROFILE_BYD100F = "surfacetexture_byd100f";

    private static volatile Boolean byd100fBuild;

    private AvmSurfaceTexturePlatform() {}

    public static boolean isSelected() {
        return isMode(DiLink5Platform.currentActiveMode());
    }

    public static boolean isMode(String mode) {
        return mode != null && MODE.equalsIgnoreCase(mode.trim());
    }

    /** Preserve a manual selection or a frame-validated probe. */
    static boolean shouldResolvePanoramicTag(
            String mode, boolean autoProfile, boolean manual, boolean validated) {
        return isMode(mode) && autoProfile && !manual && !validated;
    }

    /** BYD100F AVMCamera super-frames are F;R;L;B; ro.vehicle.type is not required here. */
    static boolean usesByd100fTileOrder() {
        return isSelected() && isByd100fBuild();
    }

    private static boolean isByd100fBuild() {
        Boolean cached = byd100fBuild;
        if (cached != null) return cached;
        boolean result;
        try {
            result = (android.os.Build.MODEL + " " + android.os.Build.PRODUCT + " "
                    + android.os.Build.DEVICE).toLowerCase(Locale.ROOT).contains("byd100f");
        } catch (Throwable t) {
            result = false;
        }
        byd100fBuild = result;
        return result;
    }

    /** {@code base} with the BYD100F tile order; camera id and geometry are unchanged. */
    static CameraProfile byd100fProfile(CameraProfile base) {
        EnumMap<CameraRole, CameraSourceRef> mappings = base.getDefaultRoleMappings();
        PanoramicSlice[] slices = PanoramicSlice.values();
        for (int i = 0; i < 4; i++) {
            mappings.put(CameraProfiles.CAMERA2_DEFAULT_SLICE_ROLES[i],
                    CameraSourceRef.panoramicSlice(slices[i]));
        }
        return new CameraProfile(
                PROFILE_BYD100F,
                base.getDisplayName() + ", BYD100F tile order F;R;L;B",
                base.getPanoCameraId(),
                base.getPanoWidth(),
                base.getPanoHeight(),
                base.getPanoSurfaceMode(),
                base.getDirectPreviewWidth(),
                base.getDirectPreviewHeight(),
                mappings,
                new float[] {base.getVerticalFovDeg(0), base.getVerticalFovDeg(1),
                        base.getVerticalFovDeg(2), base.getVerticalFovDeg(3)},
                base.getEncoderWidth(),
                base.getEncoderHeight());
    }

    /** Effective role → slice map under the BYD100F tile order, or null outside it. */
    static EnumMap<CameraRole, CameraSourceRef> byd100fTileOrder() {
        if (!usesByd100fTileOrder()) return null;
        try {
            ResolvedCameraConfig resolved = CameraConfigResolver.resolve();
            if (!PROFILE_BYD100F.equals(resolved.getProfile().getId())) return null;
            EnumMap<CameraRole, CameraSourceRef> order = new EnumMap<>(CameraRole.class);
            for (CameraRole role : CameraProfiles.CAMERA2_DEFAULT_SLICE_ROLES) {
                order.put(role, CameraSourceRef.panoramicSlice(resolved.getSliceForRole(role)));
            }
            return order;
        } catch (Throwable t) {
            return null;
        }
    }
}
