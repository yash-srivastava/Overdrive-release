package com.overdrive.app.camera;

/**
 * Source-aware selection of the legacy AVMCamera panoramic slot.
 *
 * A successful HAL tag lookup identifies a camera ID, not its frame geometry,
 * sensor orientation or four-lens order. These require independent evidence.
 * No camera is opened by this policy.
 */
public final class PanoramicCameraIdPolicy {
    public static final String MANUAL = "manual";
    public static final String VALIDATED_PROBE = "validated-probe";
    public static final String HAL_TAG = "hal-bmm-tag";
    public static final String PROFILE_FALLBACK = "profile-fallback-unverified";

    private PanoramicCameraIdPolicy() {}

    public static final class Selection {
        public final int cameraId;
        public final String source;

        private Selection(int cameraId, String source) {
            this.cameraId = cameraId;
            this.source = source;
        }

        public boolean usesSavedId() {
            return MANUAL.equals(source) || VALIDATED_PROBE.equals(source);
        }
    }

    public static boolean isValidCameraId(int id) {
        return id >= 0 && id <= PanoCameraFallbackOrder.MAX_CAMERA_ID;
    }

    public static Selection choose(int profileId, int savedId,
                                   boolean manual, boolean validated, int halId) {
        if (isValidCameraId(savedId)) {
            if (manual) return new Selection(savedId, MANUAL);
            if (validated) return new Selection(savedId, VALIDATED_PROBE);
        }
        if (isValidCameraId(halId)) return new Selection(halId, HAL_TAG);
        // Profile geometry and id are FALLBACKS, not observed HAL facts.
        return new Selection(isValidCameraId(profileId) ? profileId : -1,
                PROFILE_FALLBACK);
    }
}
