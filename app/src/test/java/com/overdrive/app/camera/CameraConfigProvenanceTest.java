package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Diagnostic-only labeling must not change legacy saved-value acceptance. */
public class CameraConfigProvenanceTest {
    @Test public void unvalidatedSavedIdsAreStillLabeledAsSaved() {
        assertEquals("saved-unverified",
                CameraConfigProvenance.savedOrProfile(false, 12, false, false));
        assertEquals("saved-unverified",
                CameraConfigProvenance.savedOrProfile(false, 0, false, false));
    }

    @Test public void flagsAreOnlyDescribedNotEnforced() {
        assertEquals("saved-manual",
                CameraConfigProvenance.savedOrProfile(false, 7, true, true));
        assertEquals("saved-validated-flag",
                CameraConfigProvenance.savedOrProfile(false, 6, false, true));
        assertEquals("profile-fallback-unverified",
                CameraConfigProvenance.savedOrProfile(false, -1, true, true));
    }

    @Test public void fixedModesNeverAttributeSavedValues() {
        assertEquals("mode-profile",
                CameraConfigProvenance.savedOrProfile(true, 3, true, true));
    }

    @Test public void previewAndGeometryLabelsAvoidFalseValidationClaims() {
        assertEquals("saved-unverified",
                CameraConfigProvenance.savedOrProfile(false, 5, false, false));
        assertEquals("profile-fallback-unverified",
                CameraConfigProvenance.savedOrProfile(false, -1, false, false));
        assertEquals("hal-bmm-tag", CameraConfigProvenance.HAL_BMM_TAG);
        assertEquals("hal-tag-preview-index", CameraConfigProvenance.HAL_TAG_PREVIEW_INDEX);
    }
}
