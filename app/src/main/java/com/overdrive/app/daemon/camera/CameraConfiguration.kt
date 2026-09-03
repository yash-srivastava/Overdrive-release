package com.overdrive.app.daemon.camera

import com.overdrive.app.camera.CameraProfiles
import com.overdrive.app.util.ScratchPaths

/**
 * Camera configuration constants.
 *
 * Extracted from CameraDaemon for better separation of concerns.
 *
 * Panoramic dimensions default to the legacy Seal/Atto profile. Runtime
 * geometry is resolved per vehicle via CameraConfigResolver — these
 * constants are only fallbacks for code that reads them directly.
 */
object CameraConfiguration {

    // Server ports
    const val TCP_PORT = 19876
    const val HTTP_PORT = 8080

    // Directories
    val STREAM_DIR = ScratchPaths.path("cam_stream")
    const val APP_STREAM_DIR = "/storage/emulated/0/Android/data/com.overdrive.app/files/stream"
    const val DEFAULT_OUTPUT_DIR = "/sdcard/DCIM/BYDCam"

    // Recording config defaults (legacy Seal/Atto profile)
    val PANO_WIDTH  = CameraProfiles.getLegacyDefault().panoWidth
    val PANO_HEIGHT = CameraProfiles.getLegacyDefault().panoHeight
    val VIEW_WIDTH  = PANO_WIDTH / 4
    val VIEW_HEIGHT = PANO_HEIGHT
    const val FRAME_RATE = 25
    const val BITRATE = 4_000_000
    const val KEYFRAME_INTERVAL = 2
    const val SEGMENT_DURATION_MS = 2 * 60 * 1000L
    
    // Streaming config (SIM-optimized)
    const val STREAM_WIDTH = 640
    const val STREAM_HEIGHT = 480
    const val STREAM_JPEG_QUALITY = 40
    const val STREAM_INTERVAL_MS = 100L
    
    // Stream modes
    const val STREAM_MODE_PRIVATE = "private"  // Local MJPEG only
    const val STREAM_MODE_PUBLIC = "public"    // Public access via tunnel
    
    // VPS configuration - REMOVED (keeping only for reference)
    // const val VPS_API_URL = "http://35.211.235.83/api/device"
    // const val PUBLISHER_PASSWORD = "byd-cam-secret-2024"
    // const val HEARTBEAT_INTERVAL_MS = 30_000L  // 30 seconds
    // const val DEFAULT_RTMP_BASE_URL = "rtmp://35.211.235.83:1935/live"
}
