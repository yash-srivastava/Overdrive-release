package com.overdrive.app.surveillance

import android.os.Process
import android.util.Log
import com.overdrive.app.config.UnifiedConfigManager
import org.json.JSONObject
import java.io.File

/**
 * Configuration Manager - Persists SurveillanceConfig to JSON file.
 * 
 * SOTA: Now uses UnifiedConfigManager for cross-UID access.
 * Both app UI and shell daemon can read/write the same config.
 * 
 * Legacy paths are kept for migration purposes only.
 */
class SurveillanceConfigManager(
    private val configFile: File = getDefaultConfigFile()
) {
    companion object {
        private const val TAG = "SurveillanceConfigMgr"
        
        // Legacy paths (for migration only)
        private const val SYSTEM_CONFIG_PATH = "/data/data/com.android.providers.settings/sentry_config.json"
        private const val SHELL_CONFIG_PATH = "/data/local/tmp/sentry_config.json"
        
        // SOTA: Use unified config path
        private const val UNIFIED_CONFIG_PATH = "/data/local/tmp/overdrive_config.json"
        
        private fun getDefaultConfigFile(): File {
            val uid = Process.myUid()
            // Always use unified config path now
            return File(UNIFIED_CONFIG_PATH).also {
                Log.i(TAG, "Using unified config file: ${it.absolutePath} (UID=$uid)")
            }
        }
        
        // JSON keys
        private const val KEY_BLOCK_SIZE = "blockSize"
        private const val KEY_REQUIRED_BLOCKS = "requiredBlocks"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_FLASH_IMMUNITY = "flashImmunity"
        private const val KEY_TEMPORAL_FRAMES = "temporalFrames"
        private const val KEY_USE_CHROMA = "useChroma"
        private const val KEY_MIN_DISTANCE = "minDistanceM"
        private const val KEY_MAX_DISTANCE = "maxDistanceM"
        private const val KEY_CAMERA_HEIGHT = "cameraHeightM"
        private const val KEY_CAMERA_TILT = "cameraTiltDeg"
        private const val KEY_VERTICAL_FOV = "verticalFovDeg"
        private const val KEY_AI_CONFIDENCE = "aiConfidence"
        private const val KEY_MIN_OBJECT_SIZE = "minObjectSize"
        private const val KEY_DETECT_PERSON = "detectPerson"
        private const val KEY_DETECT_CAR = "detectCar"
        private const val KEY_DETECT_BIKE = "detectBike"
        private const val KEY_PRE_RECORD_SECONDS = "preRecordSeconds"
        private const val KEY_POST_RECORD_SECONDS = "postRecordSeconds"
        
        // V2 Pipeline keys
        private const val KEY_ENVIRONMENT_PRESET = "environmentPreset"
        private const val KEY_SENSITIVITY_LEVEL = "sensitivityLevel"
        private const val KEY_DETECTION_ZONE = "detectionZone"
        private const val KEY_LOITERING_TIME = "loiteringTimeSeconds"
        private const val KEY_CAMERA_FRONT = "cameraFront"
        private const val KEY_CAMERA_RIGHT = "cameraRight"
        private const val KEY_CAMERA_LEFT = "cameraLeft"
        private const val KEY_CAMERA_REAR = "cameraRear"
        private const val KEY_MOTION_HEATMAP = "motionHeatmap"
        private const val KEY_FILTER_DEBUG_LOG = "filterDebugLog"
        private const val KEY_SHADOW_FILTER = "shadowFilterMode"
    }
    
    /**
     * Load configuration from file.
     * SOTA: Uses UnifiedConfigManager for cross-UID access.
     */
    fun loadConfig(): SurveillanceConfig {
        // Try unified config first
        try {
            val unified = UnifiedConfigManager.loadConfig()
            val surveillance = unified.optJSONObject("surveillance")
            if (surveillance != null && surveillance.length() > 0) {
                Log.i(TAG, "Loaded config from unified config")
                return parseConfig(surveillance)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from unified config: ${e.message}")
        }
        
        // Fallback to legacy file if unified doesn't have surveillance section
        if (configFile.exists()) {
            try {
                val json = JSONObject(configFile.readText())
                Log.i(TAG, "Loaded config from legacy ${configFile.absolutePath}")
                return parseConfig(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config", e)
            }
        }
        
        // Try legacy fallback paths
        val fallbackFile = File(SHELL_CONFIG_PATH)
        if (fallbackFile.exists() && fallbackFile.absolutePath != configFile.absolutePath) {
            try {
                val json = JSONObject(fallbackFile.readText())
                Log.i(TAG, "Loaded config from fallback ${fallbackFile.absolutePath}")
                return parseConfig(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config from fallback", e)
            }
        }
        
        Log.i(TAG, "Config file not found, using defaults")
        return SurveillanceConfig()
    }
    
    /**
     * Save configuration to file.
     * SOTA: Saves ONLY to UnifiedConfigManager - no legacy file duplication.
     */
    fun saveConfig(config: SurveillanceConfig): Boolean {
        return try {
            val json = serializeConfig(config)
            val success = UnifiedConfigManager.setSurveillance(json)
            if (success) {
                Log.i(TAG, "Config saved to unified config")
            } else {
                Log.e(TAG, "Failed to save config to unified config")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
            false
        }
    }
    
    fun deleteConfig(): Boolean = if (configFile.exists()) configFile.delete() else true
    
    fun configExists(): Boolean {
        // Check unified config first
        if (UnifiedConfigManager.configExists()) {
            val surveillance = UnifiedConfigManager.getSurveillance()
            if (surveillance.length() > 0) return true
        }
        
        // Check legacy paths
        if (configFile.exists()) return true
        val fallbackFile = File(SHELL_CONFIG_PATH)
        return fallbackFile.exists()
    }
    
    private fun serializeConfig(config: SurveillanceConfig): JSONObject {
        return JSONObject().apply {
            put(KEY_BLOCK_SIZE, config.blockSize)
            put(KEY_REQUIRED_BLOCKS, config.requiredBlocks)
            put(KEY_SENSITIVITY, config.sensitivity.toDouble())
            put(KEY_FLASH_IMMUNITY, config.flashImmunity)
            put(KEY_TEMPORAL_FRAMES, config.temporalFrames)
            put(KEY_USE_CHROMA, config.isUseChroma)
            put(KEY_MIN_DISTANCE, config.minDistanceM.toDouble())
            put(KEY_MAX_DISTANCE, config.maxDistanceM.toDouble())
            put(KEY_CAMERA_HEIGHT, config.cameraHeightM.toDouble())
            put(KEY_CAMERA_TILT, config.cameraTiltDeg.toDouble())
            put(KEY_VERTICAL_FOV, config.verticalFovDeg.toDouble())
            put(KEY_AI_CONFIDENCE, config.aiConfidence.toDouble())
            put(KEY_MIN_OBJECT_SIZE, config.minObjectSize.toDouble())
            put(KEY_DETECT_PERSON, config.isDetectPerson)
            put(KEY_DETECT_CAR, config.isDetectCar)
            put(KEY_DETECT_BIKE, config.isDetectBike)
            put(KEY_PRE_RECORD_SECONDS, config.preRecordSeconds)
            put(KEY_POST_RECORD_SECONDS, config.postRecordSeconds)
            
            // V2 Pipeline settings
            put(KEY_ENVIRONMENT_PRESET, config.environmentPreset)
            put(KEY_SENSITIVITY_LEVEL, config.sensitivityLevel)
            put(KEY_DETECTION_ZONE, config.detectionZone)
            put(KEY_LOITERING_TIME, config.loiteringTimeSeconds)
            val cameras = config.cameraEnabled
            put(KEY_CAMERA_FRONT, cameras[0])
            put(KEY_CAMERA_RIGHT, cameras[1])
            put(KEY_CAMERA_LEFT, cameras[2])
            put(KEY_CAMERA_REAR, cameras[3])
            put(KEY_MOTION_HEATMAP, config.isMotionHeatmapEnabled)
            put(KEY_FILTER_DEBUG_LOG, config.isFilterDebugLogEnabled)
            put(KEY_SHADOW_FILTER, config.shadowFilterMode)
        }
    }
    
    private fun parseConfig(json: JSONObject): SurveillanceConfig {
        val config = SurveillanceConfig()
        
        if (json.has(KEY_BLOCK_SIZE)) config.setBlockSize(json.optInt(KEY_BLOCK_SIZE, 32))
        if (json.has(KEY_REQUIRED_BLOCKS)) config.setRequiredBlocks(json.optInt(KEY_REQUIRED_BLOCKS, 3))
        if (json.has(KEY_SENSITIVITY)) config.setSensitivity(json.optDouble(KEY_SENSITIVITY, 0.04).toFloat())
        if (json.has(KEY_FLASH_IMMUNITY)) config.setFlashImmunity(json.optInt(KEY_FLASH_IMMUNITY, 2))
        if (json.has(KEY_TEMPORAL_FRAMES)) config.setTemporalFrames(json.optInt(KEY_TEMPORAL_FRAMES, 3))
        if (json.has(KEY_USE_CHROMA)) config.setUseChroma(json.optBoolean(KEY_USE_CHROMA, false))
        if (json.has(KEY_MIN_DISTANCE) && json.has(KEY_MAX_DISTANCE)) {
            config.setDistanceRange(
                json.optDouble(KEY_MIN_DISTANCE, 2.0).toFloat(),
                json.optDouble(KEY_MAX_DISTANCE, 10.0).toFloat()
            )
        }
        if (json.has(KEY_CAMERA_HEIGHT) || json.has(KEY_CAMERA_TILT) || json.has(KEY_VERTICAL_FOV)) {
            config.setCameraCalibration(
                json.optDouble(KEY_CAMERA_HEIGHT, 1.4).toFloat(),
                json.optDouble(KEY_CAMERA_TILT, 0.0).toFloat(),
                json.optDouble(KEY_VERTICAL_FOV, 50.0).toFloat()
            )
        }
        if (json.has(KEY_AI_CONFIDENCE)) config.setAiConfidence(json.optDouble(KEY_AI_CONFIDENCE, 0.25).toFloat())
        if (json.has(KEY_MIN_OBJECT_SIZE)) config.setMinObjectSize(json.optDouble(KEY_MIN_OBJECT_SIZE, 0.12).toFloat())
        if (json.has(KEY_DETECT_PERSON)) config.setDetectPerson(json.optBoolean(KEY_DETECT_PERSON, true))
        if (json.has(KEY_DETECT_CAR)) config.setDetectCar(json.optBoolean(KEY_DETECT_CAR, true))
        if (json.has(KEY_DETECT_BIKE)) config.setDetectBike(json.optBoolean(KEY_DETECT_BIKE, false))
        if (json.has(KEY_PRE_RECORD_SECONDS)) config.setPreRecordSeconds(json.optInt(KEY_PRE_RECORD_SECONDS, 5))
        if (json.has(KEY_POST_RECORD_SECONDS)) config.setPostRecordSeconds(json.optInt(KEY_POST_RECORD_SECONDS, 10))
        
        // V2 Pipeline settings
        if (json.has(KEY_ENVIRONMENT_PRESET)) config.setEnvironmentPreset(json.optString(KEY_ENVIRONMENT_PRESET, "outdoor"))
        if (json.has(KEY_SENSITIVITY_LEVEL)) config.setSensitivityLevel(json.optInt(KEY_SENSITIVITY_LEVEL, 3))
        if (json.has(KEY_DETECTION_ZONE)) config.setDetectionZone(json.optString(KEY_DETECTION_ZONE, "normal"))
        if (json.has(KEY_LOITERING_TIME)) config.setLoiteringTimeSeconds(json.optInt(KEY_LOITERING_TIME, 3))
        if (json.has(KEY_CAMERA_FRONT)) config.setCameraEnabled(0, json.optBoolean(KEY_CAMERA_FRONT, true))
        if (json.has(KEY_CAMERA_RIGHT)) config.setCameraEnabled(1, json.optBoolean(KEY_CAMERA_RIGHT, true))
        if (json.has(KEY_CAMERA_LEFT)) config.setCameraEnabled(2, json.optBoolean(KEY_CAMERA_LEFT, true))
        if (json.has(KEY_CAMERA_REAR)) config.setCameraEnabled(3, json.optBoolean(KEY_CAMERA_REAR, true))
        if (json.has(KEY_MOTION_HEATMAP)) config.setMotionHeatmapEnabled(json.optBoolean(KEY_MOTION_HEATMAP, false))
        if (json.has(KEY_FILTER_DEBUG_LOG)) config.setFilterDebugLogEnabled(json.optBoolean(KEY_FILTER_DEBUG_LOG, false))
        if (json.has(KEY_SHADOW_FILTER)) config.setShadowFilterMode(json.optInt(KEY_SHADOW_FILTER, 2))
        
        return config
    }
}
