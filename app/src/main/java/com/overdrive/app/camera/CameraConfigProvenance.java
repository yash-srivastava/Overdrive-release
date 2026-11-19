package com.overdrive.app.camera;

/**
 * Diagnostic labels for the resolved configuration, not camera-selection policy.
 * A configured value, HAL tag, or saved probe flag alone does not prove that
 * a frame was delivered, and saved geometry may have been seeded by a profile.
 */
final class CameraConfigProvenance {
    static final String MODE_PROFILE = "mode-profile";
    static final String PROFILE_FALLBACK = "profile-fallback-unverified";
    static final String SAVED_UNVERIFIED = "saved-unverified";
    static final String SAVED_MANUAL = "saved-manual";
    static final String SAVED_VALIDATED_FLAG = "saved-validated-flag";
    static final String HAL_BMM_TAG = "hal-bmm-tag";
    static final String HAL_TAG_PREVIEW_INDEX = "hal-tag-preview-index";

    private CameraConfigProvenance() {}

    /** Mirrors optNonNegative's acceptance rule, without filtering saved IDs. */
    static String savedOrProfile(boolean fixedMode, int savedValue,
                                 boolean manualFlag, boolean validatedFlag) {
        if (fixedMode) return MODE_PROFILE;
        if (savedValue < 0) return PROFILE_FALLBACK;
        if (manualFlag) return SAVED_MANUAL;
        if (validatedFlag) return SAVED_VALIDATED_FLAG;
        return SAVED_UNVERIFIED;
    }
}
