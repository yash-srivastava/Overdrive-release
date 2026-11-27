package com.overdrive.app.surveillance;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;

/**
 * GpuPipelineFactory - Factory for creating GPU surveillance pipelines.
 * 
 * Creates the profile-driven pipeline and resolves encoder geometry.
 */
public class GpuPipelineFactory {
    private static final String TAG = "GpuPipelineFactory";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    /**
     * Creates a complete GPU surveillance pipeline using the camera profile
     * resolved from the vehicle model + UnifiedConfigManager.camera section.
     *
     * Configuration:
     * - Camera: profile-driven (Seal=5120x960, Tang=5120x720) @ targetFps from config
     * - Encoder: derived from strip aspect (Seal=2560x1920, Tang=2560x1440)
     * - AI: 320x240 @ 2 FPS (idle), 5 FPS (active)
     * - Thermal protection enabled
     * - Adaptive bitrate enabled
     *
     * @param eventOutputDir Directory for event recordings
     * @return Configured GpuSurveillancePipeline
     */
    public static GpuSurveillancePipeline createDefault(File eventOutputDir) {
        com.overdrive.app.camera.ResolvedCameraConfig resolved =
            com.overdrive.app.camera.CameraConfigResolver.resolve();
        boolean passiveApa =
            com.overdrive.app.camera.CameraConfigResolver.isPassiveApaModeEnabled();
        int[] geometry = resolveEncoderGeometry(resolved.getProfile(), passiveApa);
        int encoderWidth = geometry[0];
        int encoderHeight = geometry[1];
        logger.info("createDefault: profile=" + resolved.getProfile().getDisplayName()
            + " (panoSize=" + resolved.getPanoWidth() + "x" + resolved.getPanoHeight()
            + ", encoderSize=" + encoderWidth + "x" + encoderHeight
            + (passiveApa ? ", passiveApa=true" : "") + ")");
        return new GpuSurveillancePipeline(
            resolved.getPanoWidth(),
            resolved.getPanoHeight(),
            encoderWidth,
            encoderHeight,
            eventOutputDir);
    }
    
    /** Resolves the panoramic recording encoder size before a pipeline exists. */
    public static int[] resolveEncoderGeometry(com.overdrive.app.camera.CameraProfile profile,
            boolean passiveApa) {
        return new int[] {
            passiveApa ? com.overdrive.app.camera.PassiveApaGeometry.WIDTH : profile.getEncoderWidth(),
            passiveApa ? com.overdrive.app.camera.PassiveApaGeometry.HEIGHT : profile.getEncoderHeight()
        };
    }
}
