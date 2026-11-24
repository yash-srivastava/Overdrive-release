package com.overdrive.app.setup;

import static org.junit.Assert.*;

import com.overdrive.app.camera.AvmSurfaceTexturePlatform;
import org.junit.Test;

public class SurfaceTextureVehicleSetupTest {
    private static VehicleSetupDetector.Detection legacy() {
        return VehicleSetupDetector.detect(
            new VehicleSetupDetector.Evidence("DiLink50_5.0UI", "Di3.0", "", "", false));
    }

    @Test
    public void compatibilityTransportIsNeverInferredFromVehicleModel() {
        for (String model : new String[]{"atto2", "atto2-dmi", "atto3", "seal", "dolphin",
                "han", "tang", "shark", "sealion7", "atto3-evo", "unknown"}) {
            assertNotEquals(AvmSurfaceTexturePlatform.MODE, VehicleSetupPlan.modelDefaultMode(model));
            VehicleSetupPlan plan = VehicleSetupPlan.build(
                model, legacy(), false, "default", model, false, false, true, null);
            assertEquals("default", plan.cameraMode);
            assertFalse(plan.changeCameraMode);
            assertEquals(VehicleSetupPlan.Storage.NONE, plan.storage);
        }
    }

    @Test
    public void manualCompatibilityChoiceChangesOnlyRequestedCameraMode() {
        VehicleSetupPlan plan = VehicleSetupPlan.build(
            "atto2", legacy(), false, "default", "atto2", false, false, true,
            AvmSurfaceTexturePlatform.MODE);
        assertEquals(AvmSurfaceTexturePlatform.MODE, plan.cameraMode);
        assertEquals(VehicleSetupPlan.ModeSource.OVERRIDE, plan.modeSource);
        assertTrue(plan.changeCameraMode);
        assertFalse(plan.changeModel);
        assertEquals(VehicleSetupPlan.Storage.NONE, plan.storage);
    }
}
