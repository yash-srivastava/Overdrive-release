package com.overdrive.app.camera.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.camera.CameraProfile;
import com.overdrive.app.camera.CameraProfiles;
import com.overdrive.app.camera.CameraRole;
import com.overdrive.app.camera.PanoramicSlice;

import org.junit.Before;
import org.junit.Test;

public class DiLink5PlatformHelperTest {

    @Before
    public void reset() {
        DiLink5PlatformHelper.resetForTests();
    }

    @Test
    public void sharkSelectedModelIsShark() {
        assertTrue(DiLink5PlatformHelper.isSharkProfile("shark6"));
    }

    @Test
    public void sealionSelectedModelIsNotShark() {
        assertFalse(DiLink5PlatformHelper.isSharkProfile("sealion7"));
    }

    @Test
    public void dxfHardwareIdentityWinsOverPersistedSelections() {
        assertTrue(DiLink5PlatformHelper.inferShark(
                "sealion7", "", CameraProfiles.PROFILE_AUTO, "Di5.0_DXF_W"));
        assertTrue(DiLink5PlatformHelper.inferShark(
                "", "", CameraProfiles.PROFILE_DILINK5_SEALION7, "Di5.0_DXF_W"));
        assertTrue(DiLink5PlatformHelper.inferShark(
                "shark6", "", CameraProfiles.PROFILE_AUTO, "other"));
    }

    @Test
    public void unsetCosmeticModelIsNotAConfiguredVehicleHint() {
        assertEquals("", DiLink5PlatformHelper.resolveConfiguredModel(
                "sealion7", "unset", ""));
        assertEquals("sealion7", DiLink5PlatformHelper.resolveConfiguredModel(
                "sealion7", "user", ""));
        assertEquals("shark6", DiLink5PlatformHelper.resolveConfiguredModel(
                "shark6", "legacy", ""));
    }

    @Test
    public void dxfIdentifiesSharkWithoutExplicitSelection() {
        assertTrue(DiLink5PlatformHelper.isDxfVehicleType("Di5.0_DXF_W"));
        assertFalse(DiLink5PlatformHelper.isDxfVehicleType("Di5.0_XYZ_W"));
        assertTrue(DiLink5PlatformHelper.inferShark(
                "auto", "auto", CameraProfiles.PROFILE_AUTO, "Di5.0_DXF_W"));
        assertFalse(DiLink5PlatformHelper.inferShark(
                "auto", "auto", CameraProfiles.PROFILE_AUTO, "Di5.0_XYZ_W"));
    }

    @Test
    public void cameraMappingOverrideIsStrictlyValidated() {
        assertEquals("8,9,5,4", DiLink5QCarCamBackend.normalizeCameraMapping(" 8, 9,5,4 "));
        assertNull(DiLink5QCarCamBackend.normalizeCameraMapping("8,9,5"));
        assertNull(DiLink5QCarCamBackend.normalizeCameraMapping("8,9,5; reboot,4"));
        assertNull(DiLink5QCarCamBackend.normalizeCameraMapping("8,9,-1,4"));
    }

    @Test
    public void aisByteForViewModeMatchesDoc() {
        assertEquals(4, DiLink5PlatformHelper.aisByteForViewMode(0));
        assertEquals(0, DiLink5PlatformHelper.aisByteForViewMode(1));
        assertEquals(1, DiLink5PlatformHelper.aisByteForViewMode(2));
        assertEquals(2, DiLink5PlatformHelper.aisByteForViewMode(3));
        assertEquals(3, DiLink5PlatformHelper.aisByteForViewMode(4));
        assertEquals(6, DiLink5PlatformHelper.aisByteForViewMode(6));
        assertEquals(6, DiLink5PlatformHelper.aisByteForViewMode(9));
        assertEquals(4, DiLink5PlatformHelper.defaultAisCameraId());
    }

    @Test
    public void inferSharkModelUsesSharkProfile() {
        assertEquals(CameraProfiles.PROFILE_DILINK5_SHARK,
                CameraProfiles.infer("BYD Shark 6").getId());
        assertEquals(CameraProfiles.PROFILE_DILINK5_SEALION7,
                CameraProfiles.infer("Sealion 7").getId());
    }

    @Test
    public void dilink5LogicalMappingsUseMainMosaicSlices() {
        CameraProfile profile = CameraProfiles.get(CameraProfiles.PROFILE_DILINK5_SHARK);
        assertEquals(PanoramicSlice.SLICE_4,
                profile.getDefaultRoleMappings().get(CameraRole.PANO_FRONT).getPanoramicSlice());
        assertEquals(PanoramicSlice.SLICE_3,
                profile.getDefaultRoleMappings().get(CameraRole.PANO_RIGHT).getPanoramicSlice());
        assertEquals(PanoramicSlice.SLICE_1,
                profile.getDefaultRoleMappings().get(CameraRole.PANO_REAR).getPanoramicSlice());
        assertEquals(PanoramicSlice.SLICE_2,
                profile.getDefaultRoleMappings().get(CameraRole.PANO_LEFT).getPanoramicSlice());
    }

}
