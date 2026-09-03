package com.overdrive.app.util

import com.overdrive.app.camera.CameraProfiles

/**
 * Shared constants for the BYD Champ application.
 *
 * Panoramic dimensions default to the legacy Seal/Atto profile. Runtime
 * geometry is resolved per vehicle via CameraConfigResolver.
 */
object Constants {
    
    // Daemon Ports
    const val TCP_PORT = 19876
    const val HTTP_PORT = 8080
    
    // Directories (daemon scratch resolved at runtime via ScratchPaths)
    val STREAM_DIR: String get() = ScratchPaths.path("cam_stream")
    const val APP_STREAM_DIR = "/storage/emulated/0/Android/data/com.overdrive.app/files/stream"
    const val DEFAULT_OUTPUT_DIR = "/sdcard/DCIM/BYDCam"
    val LOG_DIR: String get() = ScratchPaths.getDir()
    
    // Camera Configuration (legacy Seal/Atto defaults)
    val PANO_WIDTH  = CameraProfiles.getLegacyDefault().panoWidth
    val PANO_HEIGHT = CameraProfiles.getLegacyDefault().panoHeight
    val VIEW_WIDTH  = PANO_WIDTH / 4
    val VIEW_HEIGHT = PANO_HEIGHT
    const val FRAME_RATE = 25
    const val BITRATE = 4_000_000
    const val KEYFRAME_INTERVAL = 2
    // Default clip segment length. The live value is configurable per-install
    // (2/5/10 min) via recording.segmentDurationMinutes — a single shared key
    // read by BOTH the ACC-on dashcam (GpuSurveillancePipeline) and the
    // ACC-off / OEM surveillance (OemDashcamPipeline) axes, mirroring how
    // recording.rectifyStrength is shared. This constant is only the seed
    // default; HardwareEventRecorderGpu carries the live mutable value.
    const val SEGMENT_DURATION_MINUTES = 2
    const val MIN_SEGMENT_DURATION_MINUTES = 2
    const val MAX_SEGMENT_DURATION_MINUTES = 10
    const val SEGMENT_DURATION_MS = SEGMENT_DURATION_MINUTES * 60 * 1000L
    
    // Streaming Configuration
    const val STREAM_WIDTH = 640
    const val STREAM_HEIGHT = 480
    const val STREAM_FPS = 15
    const val STREAM_BITRATE = 500_000
    const val STREAM_JPEG_QUALITY = 40
    const val STREAM_INTERVAL_MS = 100L
    
    // VPS Configuration - REMOVED (keeping only for reference)
    // const val VPS_API_URL = "http://35.211.235.83/api/device"
    // const val RTMP_BASE_URL = "rtmp://35.211.235.83:1935/live"
    // const val PUBLISHER_PASSWORD = "byd-cam-secret-2024"
    // const val HEARTBEAT_INTERVAL_MS = 30_000L
    
    // Timeouts
    const val DAEMON_START_TIMEOUT_MS = 10_000L
    const val DAEMON_STOP_TIMEOUT_MS = 5_000L
    const val CONNECTION_TIMEOUT_MS = 10_000L
    
    // Retry Configuration
    const val DEFAULT_RETRY_ATTEMPTS = 3
    const val DEFAULT_RETRY_DELAY_MS = 5_000L
    const val MAX_RETRY_BACKOFF_MS = 30_000L
}
