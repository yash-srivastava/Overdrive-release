package com.overdrive.app.manager

import com.overdrive.app.client.CameraDaemonClient
import com.overdrive.app.config.ConfigManager
import com.overdrive.app.config.StreamMode
import com.overdrive.app.logging.LogManager

/**
 * Manages stream mode (private/public) and syncs with daemon.
 */
class StreamModeManager(
    private val configManager: ConfigManager,
    private val logManager: LogManager
) {
    
    companion object {
        private const val TAG = "StreamModeManager"
    }
    
    /**
     * Set stream mode and sync with daemon.
     */
    fun setStreamMode(mode: StreamMode, callback: StreamModeCallback) {
        logManager.info(TAG, "Setting stream mode: $mode")
        
        // Save to config immediately
        configManager.setStreamMode(mode)
        
        // Sync with daemon in background
        Thread {
            val success = sendModeToDaemon(mode)
            if (success) {
                val message = when (mode) {
                    StreamMode.PUBLIC -> "Public mode enabled - device will report online to VPS"
                    StreamMode.PRIVATE -> "Private mode enabled - local streaming only"
                }
                callback.onSuccess(mode, message)
            } else {
                callback.onError("Stream mode saved locally, daemon not running")
            }
        }.start()
    }
    
    /**
     * Get current stream mode.
     */
    fun getStreamMode(): StreamMode {
        return configManager.getStreamMode()
    }
    
    /**
     * Sync stream mode with daemon.
     */
    fun syncWithDaemon() {
        Thread {
            try {
                val client = CameraDaemonClient()
                if (client.connect()) {
                    // Get daemon mode
                    val statusResponse = client.sendCommand("""{"cmd":"getStreamMode"}""")
                    val daemonMode = statusResponse?.optString("mode", "private") ?: "private"
                    val daemonStreamMode = if (daemonMode == "public") StreamMode.PUBLIC else StreamMode.PRIVATE
                    
                    val savedMode = getStreamMode()
                    
                    if (daemonStreamMode != savedMode) {
                        // Sync daemon to saved preference
                        sendModeToDaemon(savedMode)
                        logManager.info(TAG, "Synced stream mode to daemon: $savedMode")
                    }
                    
                    client.disconnect()
                }
            } catch (e: Exception) {
                logManager.warn(TAG, "Daemon sync failed: ${e.message}")
            }
        }.start()
    }
    
    /**
     * Send mode to daemon via TCP command.
     */
    private fun sendModeToDaemon(mode: StreamMode): Boolean {
        return try {
            val client = CameraDaemonClient()
            if (client.connect()) {
                val modeStr = mode.name.lowercase()
                val response = client.sendCommand("""{"cmd":"setStreamMode","mode":"$modeStr"}""")
                client.disconnect()
                
                response?.optString("status") == "ok"
            } else {
                false
            }
        } catch (e: Exception) {
            logManager.error(TAG, "Failed to send mode to daemon", e)
            false
        }
    }
}

/**
 * Stream mode callback interface.
 */
interface StreamModeCallback {
    fun onSuccess(mode: StreamMode, message: String)
    fun onError(error: String)
}
