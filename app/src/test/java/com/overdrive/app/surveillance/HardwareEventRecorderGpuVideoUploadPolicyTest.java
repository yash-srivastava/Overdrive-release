package com.overdrive.app.surveillance;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HardwareEventRecorderGpuVideoUploadPolicyTest {

    @Test
    public void ordinaryOemDashcamClipStillUploadsAutomatically() {
        assertTrue(HardwareEventRecorderGpu.VideoUploadPolicy.AUTOMATIC
                .shouldAutoUpload("dvr_20260812_170103.mp4"));
    }

    @Test
    public void surveillanceOwnedOemMirrorWaitsForParentTierGate() {
        assertFalse(HardwareEventRecorderGpu.VideoUploadPolicy.SURVEILLANCE_GATED
                .shouldAutoUpload("dvr_20260812_170103.mp4"));
    }

    @Test
    public void panoramicSurveillanceClipNeverUsesGenericUploadPath() {
        assertFalse(HardwareEventRecorderGpu.VideoUploadPolicy.AUTOMATIC
                .shouldAutoUpload("event_20260812_170103.mp4"));
    }

    @Test
    public void proximityClipKeepsHistoricalAutomaticUpload() {
        assertTrue(HardwareEventRecorderGpu.VideoUploadPolicy.AUTOMATIC
                .shouldAutoUpload("proximity_20260812_153155.mp4"));
    }

    @Test
    public void continuousDashcamClipsNeverUploadAutomatically() {
        assertFalse(HardwareEventRecorderGpu.VideoUploadPolicy.AUTOMATIC
                .shouldAutoUpload("cam_20260905_175214.mp4"));
        assertFalse(HardwareEventRecorderGpu.VideoUploadPolicy.AUTOMATIC
                .shouldAutoUpload("cam2_20260905_175214.mp4"));
        assertFalse(HardwareEventRecorderGpu.VideoUploadPolicy.AUTOMATIC
                .shouldAutoUpload("replay_20260905_175214.mp4"));
    }

    @Test
    public void nullFileNameReturnsFalse() {
        assertFalse(HardwareEventRecorderGpu.VideoUploadPolicy.AUTOMATIC
                .shouldAutoUpload(null));
    }
}
