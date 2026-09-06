package com.overdrive.app.byd

import com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.logging.DaemonLogger

/**
 * Detects whether this device is running on BYD DiLink 5.0 hardware
 * (Qualcomm Snapdragon SA8155P — e.g. Sealion 7).
 *
 * This is the SAME two-part check [UnifiedConfigManager.resolveOemDashcamId]
 * already uses to gate DiLink5-only OEM-dashcam behavior: the user-configured
 * `camera.cameraMode` containing "dilink5", OR the native AIS/QCarCam probe
 * (`DiLink5QCarCamBackend.isSupported()`, which checks for
 * `/vendor/lib64/libais_client.so`) succeeding. Reusing it here — rather than
 * inventing a second detector — keeps every DiLink5 gate in the app agreeing
 * with the one already shipped and field-verified for the camera pipeline.
 *
 * [CarSvcTelemetry] uses this to gate its `dumpsys car_service` fallback
 * telemetry (gear / 12V battery / doors / charging) so that on every OTHER
 * platform — where the vendor HAL paths this fallback exists for are not
 * known to be broken — the new code path never runs at all.
 */
object DiLink5Platform {

    private val logger = DaemonLogger.getInstance("DiLink5Platform")

    /** True when this device is (or looks like) DiLink 5.0 / Sealion 7 class hardware. */
    @JvmStatic
    fun isActive(): Boolean {
        return try {
            val camera = UnifiedConfigManager.loadConfig().optJSONObject("camera")
            val mode = camera?.optString("cameraMode", "") ?: ""
            mode.contains("dilink5", ignoreCase = true) || DiLink5QCarCamBackend.isSupported()
        } catch (t: Throwable) {
            logger.debug("isActive() check failed, assuming not DiLink5: " + t.message)
            false
        }
    }
}
