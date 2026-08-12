package com.overdrive.app.camera;

import com.overdrive.app.surveillance.HardwareEventRecorderGpu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OemDashcamTelegramUploadPolicyTest {

    @Test
    public void surveillanceOwnerSelectsTierGatedUpload() {
        assertEquals(HardwareEventRecorderGpu.VideoUploadPolicy.SURVEILLANCE_GATED,
                OemDashcamPipeline.uploadPolicyFor(true));
    }

    @Test
    public void resolverOwnerKeepsAutomaticUpload() {
        assertEquals(HardwareEventRecorderGpu.VideoUploadPolicy.AUTOMATIC,
                OemDashcamPipeline.uploadPolicyFor(false));
    }
}
