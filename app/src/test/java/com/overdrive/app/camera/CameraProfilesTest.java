package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CameraProfilesTest {

    @Test
    public void atto3SelectedModelUsesFieldVerifiedCameraZero() {
        CameraProfile profile = CameraProfiles.infer("atto3");

        assertEquals(CameraProfiles.PROFILE_ATTO_3, profile.getId());
        assertEquals(0, profile.getPanoCameraId());
        assertEquals(5120, profile.getPanoWidth());
        assertEquals(960, profile.getPanoHeight());
        assertEquals(0, profile.getPanoSurfaceMode());
    }

    @Test
    public void atto3AliasesResolveToSameProfile() {
        assertEquals(CameraProfiles.PROFILE_ATTO_3,
                CameraProfiles.infer("BYD Atto 3").getId());
        assertEquals(CameraProfiles.PROFILE_ATTO_3,
                CameraProfiles.infer("atto-3").getId());
        assertEquals(CameraProfiles.PROFILE_ATTO_3,
                CameraProfiles.infer("Yuan Plus").getId());
    }

    @Test
    public void unknownSystemModelKeepsConservativeLegacyDefault() {
        CameraProfile profile = CameraProfiles.infer("BYD AUTO");

        assertEquals(CameraProfiles.PROFILE_LEGACY_SEAL_ATTO, profile.getId());
        assertEquals(1, profile.getPanoCameraId());
        assertEquals(2560, profile.getEncoderWidth());
        assertEquals(1920, profile.getEncoderHeight());
    }

    @Test
    public void dilink5RequiresExplicitProfileAndUsesNativeMosaicGeometry() {
        assertEquals(CameraProfiles.PROFILE_LEGACY_SEAL_ATTO,
                CameraProfiles.infer("BYD Sealion 7").getId());

        CameraProfile profile = CameraProfiles.get(
                CameraProfiles.PROFILE_DILINK5_SEALION7);
        assertEquals(0, profile.getPanoCameraId());
        assertEquals(1920, profile.getPanoWidth());
        assertEquals(1080, profile.getPanoHeight());
        assertEquals(1920, profile.getEncoderWidth());
        assertEquals(1080, profile.getEncoderHeight());
        assertEquals(PanoramicSlice.SLICE_4,
                profile.getDefaultRoleMappings().get(CameraRole.PANO_FRONT)
                        .getPanoramicSlice());
        assertEquals(PanoramicSlice.SLICE_1,
                profile.getDefaultRoleMappings().get(CameraRole.PANO_REAR)
                        .getPanoramicSlice());
    }

    @Test
    public void dilink100ProfileUsesCameraZeroAndFrontRightLeftRearSlices() {
        CameraProfile profile = CameraProfiles.get(CameraProfiles.PROFILE_DILINK100_AVM);

        assertEquals(CameraProfiles.PROFILE_DILINK100_AVM, profile.getId());
        assertEquals(0, profile.getPanoCameraId());
        assertEquals(5120, profile.getPanoWidth());
        assertEquals(960, profile.getPanoHeight());
        assertEquals(0, profile.getPanoSurfaceMode());
        assertEquals(PanoramicSlice.SLICE_1, profile.getDefaultRoleMappings()
                .get(CameraRole.PANO_FRONT).getPanoramicSlice());
        assertEquals(PanoramicSlice.SLICE_2, profile.getDefaultRoleMappings()
                .get(CameraRole.PANO_RIGHT).getPanoramicSlice());
        assertEquals(PanoramicSlice.SLICE_3, profile.getDefaultRoleMappings()
                .get(CameraRole.PANO_LEFT).getPanoramicSlice());
        assertEquals(PanoramicSlice.SLICE_4, profile.getDefaultRoleMappings()
                .get(CameraRole.PANO_REAR).getPanoramicSlice());
    }

    @Test
    public void dilink100DetectedFromVehicleTypeOnly() {
        assertTrue(DiLink100Platform.isDiLink100VehicleType("DiLink100_5.0UI"));
        assertTrue(DiLink100Platform.isDiLink100VehicleType(" dilink100_6.0 "));
        assertFalse(DiLink100Platform.isDiLink100VehicleType("DiLink50_5.0UI"));
        assertFalse(DiLink100Platform.isDiLink100VehicleType("Di5.0_SA8155"));
        assertFalse(DiLink100Platform.isDiLink100VehicleType(""));
        assertFalse(DiLink100Platform.isDiLink100VehicleType(null));
    }

    @Test
    public void selectedVehicleModelWinsOverGenericSystemModel() {
        assertEquals("atto3",
                CameraConfigResolver.preferSelectedVehicleModel("atto3", "BYD AUTO"));
        assertEquals("BYD AUTO",
                CameraConfigResolver.preferSelectedVehicleModel("", "BYD AUTO"));
        assertEquals("unknown",
                CameraConfigResolver.preferSelectedVehicleModel(null, null));
    }

    @Test
    public void dilink5ProfileCannotLeakIntoOtherCameraModes() {
        assertEquals(CameraProfiles.PROFILE_AUTO,
                CameraConfigResolver.profileForMode(
                        CameraProfiles.PROFILE_DILINK5_SEALION7, false));
        assertEquals(CameraProfiles.PROFILE_DILINK5_SEALION7,
                CameraConfigResolver.profileForMode(
                        CameraProfiles.PROFILE_DILINK5_SEALION7, true));
    }
}
