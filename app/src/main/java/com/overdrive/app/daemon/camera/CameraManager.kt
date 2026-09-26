package com.overdrive.app.daemon.camera

import android.os.Handler
import com.overdrive.app.daemon.CameraDaemon
import com.overdrive.app.surveillance.GpuSurveillancePipeline
import com.overdrive.app.logging.DaemonLogger
import java.io.File

/**
 * Manages camera lifecycle using GPU pipeline.
 * 
 * Architecture:
 * - GPU pipeline handles mosaic recording (all 4 cameras in 2x2 grid)
 * - Individual camera streaming extracts from GPU texture (future enhancement)
 * 
 * For now, focuses on mosaic recording with GPU zero-copy.
 */
class CameraManager(
    private val mainHandler: Handler,
    private val outputDir: String
) {
    
    companion object {
        private val logger = DaemonLogger.getInstance("CameraManager")
    }
    
    private var gpuPipeline: GpuSurveillancePipeline? = null
    private var pipelineRunning = false
    private val activeStreamingCameras = mutableSetOf<Int>()
    
    // Frame callback for streaming
    private var streamCallback: ((Int, ByteArray, Int, Int) -> Unit)? = null
    
    fun setStreamCallback(callback: (Int, ByteArray, Int, Int) -> Unit) {
        streamCallback = callback
    }
    
    /**
     * Initialize GPU surveillance pipeline.
     */
    fun init() {
        try {
            val eventDir = File(outputDir, "sentry_events")
            val pipeline = GpuSurveillancePipeline(
                CameraConfiguration.PANO_WIDTH,
                CameraConfiguration.PANO_HEIGHT,
                eventDir
            )
            pipeline.init()
            
            gpuPipeline = pipeline
            logger.info("GPU surveillance pipeline initialized")
        } catch (e: Exception) {
            logger.error("Failed to init GPU pipeline", e)
        }
    }
    
    /**
     * Set streaming quality (HQ/LQ).
     */
    fun setStreamingQuality(quality: String) {
        val streamQuality = when (quality.uppercase()) {
            "HQ" -> com.overdrive.app.surveillance.GpuPipelineConfig.StreamingQuality.HQ
            "LQ" -> com.overdrive.app.surveillance.GpuPipelineConfig.StreamingQuality.LQ
            else -> com.overdrive.app.surveillance.GpuPipelineConfig.StreamingQuality.HQ
        }
        
        gpuPipeline?.setStreamingQuality(streamQuality)
        logger.info("Streaming quality set to: $quality")
    }
    
    /**
     * Set recording mode (Normal/Sentry).
     */
    fun setRecordingMode(mode: String) {
        val recordingMode = when (mode.uppercase()) {
            "NORMAL" -> com.overdrive.app.surveillance.GpuPipelineConfig.RecordingMode.NORMAL
            "SENTRY" -> com.overdrive.app.surveillance.GpuPipelineConfig.RecordingMode.SENTRY
            else -> com.overdrive.app.surveillance.GpuPipelineConfig.RecordingMode.NORMAL
        }
        
        gpuPipeline?.setRecordingMode(recordingMode)
        logger.info("Recording mode set to: $mode")
    }
    
    /**
     * Start GPU pipeline for mosaic recording.
     */
    fun startMosaicRecording() {
        if (pipelineRunning) {
            logger.info("GPU pipeline already running")
            return
        }
        
        try {
            val cameraStartEpoch = CameraDaemon.captureCameraStartEpoch()
            gpuPipeline?.start(false, cameraStartEpoch)
            pipelineRunning = true
            logger.info("GPU mosaic recording started")
        } catch (e: Exception) {
            logger.error("Failed to start GPU pipeline", e)
        }
    }
    
    /**
     * Stop GPU pipeline.
     */
    fun stopMosaicRecording() {
        if (!pipelineRunning) {
            return
        }
        
        try {
            gpuPipeline?.stop()
            pipelineRunning = false
            logger.info("GPU mosaic recording stopped")
        } catch (e: Exception) {
            logger.error("Failed to stop GPU pipeline", e)
        }
    }
    
    /**
     * Start a camera view (for compatibility - enables streaming for that camera).
     * 
     * @param viewId View ID (1-4)
     * @param enableStreaming Whether to enable streaming
     * @param viewOnly If true, only view without recording
     */
    fun startCamera(viewId: Int, enableStreaming: Boolean, viewOnly: Boolean) {
        if (viewId < 1 || viewId > 4) {
            logger.error("Invalid view ID: $viewId")
            return
        }
        
        logger.info("Starting camera $viewId (GPU-based, mosaic recording)")
        
        // Add to active streaming cameras
        if (enableStreaming) {
            activeStreamingCameras.add(viewId)
        }
        
        // Start GPU pipeline if not running
        if (!pipelineRunning) {
            startMosaicRecording()
        }
    }
    
    /**
     * Stop a camera view.
     * 
     * @param viewId View ID (1-4)
     * @param forceStop If true, stops even if recording
     */
    fun stopCamera(viewId: Int, forceStop: Boolean = false) {
        logger.info("Stopping camera $viewId")
        activeStreamingCameras.remove(viewId)
        
        // Stop pipeline if no more active cameras and forcing stop
        if (forceStop && activeStreamingCameras.isEmpty()) {
            stopMosaicRecording()
        }
    }
    
    /**
     * Force stop a camera, even if recording.
     */
    fun forceStopCamera(viewId: Int) {
        stopCamera(viewId, true)
    }
    
    /**
     * Stop all cameras.
     * 
     * @param forceStop If true, stops all cameras including recording ones
     */
    fun stopAllCameras(forceStop: Boolean = true) {
        logger.info("Stopping all cameras (force=$forceStop)")
        activeStreamingCameras.clear()
        if (forceStop) {
            stopMosaicRecording()
        }
    }
    
    /**
     * Get all virtual views (returns empty map for GPU pipeline).
     */
    fun getVirtualViews(): Map<Int, Any> = emptyMap()
    
    /**
     * Get a specific virtual view (returns null for GPU pipeline).
     */
    fun getVirtualView(viewId: Int): Any? = null
    
    /**
     * Check if any camera is active.
     */
    fun hasActiveCameras(): Boolean = pipelineRunning
    
    /**
     * Get list of recording cameras (returns all 4 if mosaic is recording).
     */
    fun getRecordingCameras(): List<Int> {
        return if (pipelineRunning) listOf(1, 2, 3, 4) else emptyList()
    }
    
    /**
     * Get list of streaming cameras.
     */
    fun getStreamingCameras(): List<Int> = activeStreamingCameras.toList()
    
    /**
     * Scan available cameras.
     */
    fun scanCameras() {
        logger.info("--- CAMERA SCAN ---")
        try {
            val infoClass = Class.forName("android.hardware.BmmCameraInfo")
            val mGetTags = infoClass.getDeclaredMethod("getValidCameraTag")
            mGetTags.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val tags = mGetTags.invoke(null) as? List<String>
            
            val mGetId = infoClass.getDeclaredMethod("getCameraId", String::class.java)
            mGetId.isAccessible = true
            
            tags?.forEach { tag ->
                val id = mGetId.invoke(null, tag) as Int
                logger.info("FOUND: [${tag.uppercase()}] -> ID: $id")
            }
        } catch (e: Exception) {
            logger.warn("Scan failed: ${e.message}")
        }
        logger.info("--- END SCAN ---")
    }
    
    /**
     * Create required directories.
     */
    fun createDirectories() {
        File(outputDir).mkdirs()
        File(CameraConfiguration.streamDir()).mkdirs()
        File(CameraConfiguration.APP_STREAM_DIR).mkdirs()
    }
}
