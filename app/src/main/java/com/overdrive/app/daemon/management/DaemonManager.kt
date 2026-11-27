package com.overdrive.app.daemon.management

import android.content.Context
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.config.ConfigManager
import com.overdrive.app.config.DaemonConfig
import com.overdrive.app.config.DaemonType
import com.overdrive.app.logging.LogManager
import com.overdrive.app.util.DaemonHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized daemon lifecycle manager.
 * 
 * Manages all daemon processes with auto-start, retry logic, and state tracking.
 */
class DaemonManager private constructor(
    private val context: Context,
    private val configManager: ConfigManager,
    private val logManager: LogManager
) {
    
    companion object {
        @Volatile
        private var instance: DaemonManager? = null
        
        fun getInstance(
            context: Context,
            configManager: ConfigManager = ConfigManager.getInstance(context),
            logManager: LogManager = LogManager.getInstance()
        ): DaemonManager {
            return instance ?: synchronized(this) {
                instance ?: DaemonManager(context, configManager, logManager)
                    .also { instance = it }
            }
        }
        
        private const val TAG = "DaemonManager"
    }
    
    private val adbLauncher = AdbDaemonLauncher(context)
    private val daemonStates = ConcurrentHashMap<DaemonType, DaemonState>()
    
    init {
        // Initialize daemon states
        DaemonType.values().forEach { type ->
            val config = configManager.getDaemonConfig(type)
            daemonStates[type] = DaemonState(
                type = type,
                running = false,
                autoStart = config.autoStart
            )
        }
    }
    
    /**
     * Start a daemon.
     */
    fun startDaemon(type: DaemonType, callback: DaemonCallback? = null) {
        logManager.info(TAG, "Starting daemon: $type")
        
        val config = configManager.getDaemonConfig(type)
        
        when (type) {
            DaemonType.CAMERA -> startCameraDaemon(config, callback)
            DaemonType.SENTRY -> startSentryDaemon(callback)
            DaemonType.BYD_EVENT -> startBydEventDaemon(callback)
        }
    }
    
    /**
     * Stop a daemon.
     */
    fun stopDaemon(type: DaemonType, callback: DaemonCallback? = null) {
        logManager.info(TAG, "Stopping daemon: $type")
        
        when (type) {
            DaemonType.CAMERA -> stopCameraDaemon(callback)
            DaemonType.SENTRY -> stopSentryDaemon(callback)
            DaemonType.BYD_EVENT -> stopBydEventDaemon(callback)
        }
    }
    
    /**
     * Restart a daemon.
     */
    fun restartDaemon(type: DaemonType, callback: DaemonCallback? = null) {
        logManager.info(TAG, "Restarting daemon: $type")
        stopDaemon(type, object : DaemonCallback {
            override fun onStarted(type: DaemonType) {}
            override fun onStopped(type: DaemonType) {
                // Wait briefly then start
                Thread {
                    Thread.sleep(1000)
                    startDaemon(type, callback)
                }.start()
            }
            override fun onError(type: DaemonType, error: String) {
                callback?.onError(type, error)
            }
            override fun onLog(type: DaemonType, message: String) {
                callback?.onLog(type, message)
            }
        })
    }
    
    /**
     * Check if daemon is running.
     */
    fun isDaemonRunning(type: DaemonType): Boolean {
        return daemonStates[type]?.running ?: false
    }
    
    /**
     * Get daemon state.
     */
    fun getDaemonState(type: DaemonType): DaemonState {
        return daemonStates[type] ?: DaemonState(type, false)
    }
    
    /**
     * Get all daemon states.
     */
    fun getAllDaemonStates(): Map<DaemonType, DaemonState> {
        return daemonStates.toMap()
    }
    
    /**
     * Set auto-start for a daemon.
     */
    fun setAutoStart(type: DaemonType, enabled: Boolean) {
        val config = configManager.getDaemonConfig(type).copy(autoStart = enabled)
        configManager.updateDaemonConfig(type, config)
        
        daemonStates[type] = daemonStates[type]?.copy(autoStart = enabled) 
            ?: DaemonState(type, false, autoStart = enabled)
        
        logManager.info(TAG, "Auto-start ${if (enabled) "enabled" else "disabled"} for $type")
    }
    
    /**
     * Check if auto-start is enabled.
     */
    fun isAutoStartEnabled(type: DaemonType): Boolean {
        return daemonStates[type]?.autoStart ?: false
    }
    
    /**
     * Start all configured daemons (auto-start enabled).
     */
    fun startConfiguredDaemons() {
        logManager.info(TAG, "Starting configured daemons...")
        
        DaemonType.values().forEach { type ->
            if (isAutoStartEnabled(type) && !isDaemonRunning(type)) {
                startDaemon(type, object : DaemonCallback {
                    override fun onStarted(type: DaemonType) {
                        logManager.info(TAG, "Auto-started: $type")
                    }
                    override fun onStopped(type: DaemonType) {}
                    override fun onError(type: DaemonType, error: String) {
                        logManager.error(TAG, "Auto-start failed for $type: $error")
                    }
                    override fun onLog(type: DaemonType, message: String) {
                        logManager.debug(TAG, "[$type] $message")
                    }
                })
            }
        }
    }
    
    /**
     * Get daemon configuration.
     */
    fun getDaemonConfig(type: DaemonType): DaemonConfig {
        return configManager.getDaemonConfig(type)
    }
    
    /**
     * Update daemon configuration.
     */
    fun updateDaemonConfig(type: DaemonType, config: DaemonConfig) {
        configManager.updateDaemonConfig(type, config)
        daemonStates[type] = daemonStates[type]?.copy(autoStart = config.autoStart)
            ?: DaemonState(type, false, autoStart = config.autoStart)
    }
    
    // Private daemon-specific implementations
    
    private fun startCameraDaemon(config: DaemonConfig, callback: DaemonCallback?) {
        val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        
        adbLauncher.launchDaemon(outputDir, nativeLibDir, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                logManager.debug(TAG, "[CAMERA] $message")
                callback?.onLog(DaemonType.CAMERA, message)
            }
            
            override fun onLaunched() {
                updateDaemonState(DaemonType.CAMERA, true)
                callback?.onStarted(DaemonType.CAMERA)
            }
            
            override fun onError(error: String) {
                updateDaemonState(DaemonType.CAMERA, false)
                callback?.onError(DaemonType.CAMERA, error)
            }
        })
    }
    
    private fun stopCameraDaemon(callback: DaemonCallback?) {
        Thread {
            val prepareError = prepareCameraRestart()
            if (prepareError != null) {
                logManager.error(TAG, "[CAMERA] Stop aborted: $prepareError")
                callback?.onError(DaemonType.CAMERA, prepareError)
                return@Thread
            }

            adbLauncher.killDaemon(object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    logManager.debug(TAG, "[CAMERA] $message")
                    callback?.onLog(DaemonType.CAMERA, message)
                }

                override fun onLaunched() {
                    updateDaemonState(DaemonType.CAMERA, false)
                    callback?.onStopped(DaemonType.CAMERA)
                }

                override fun onError(error: String) {
                    abortCameraRestart()
                    callback?.onError(DaemonType.CAMERA, error)
                }
            })
        }.start()
    }

    /**
     * Returns null only after the daemon confirms its active trip and media
     * pipeline are restart-safe. Any transport or non-2xx result blocks SIGKILL.
     */
    private fun prepareCameraRestart(): String? {
        var connection: java.net.HttpURLConnection? = null
        return try {
            val conn = DaemonHttpClient.open(
                "/api/surveillance/prepare-restart", "POST", 3000, 10000)
            connection = conn
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val requestBody = org.json.JSONObject()
                .put("reason", "app_daemon_stop")
                .toString()
                .toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(requestBody) }
            val code = conn.responseCode
            if (code in 200..299) null else "prepare-restart returned HTTP $code"
        } catch (e: Exception) {
            "prepare-restart failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun abortCameraRestart() {
        Thread {
            var connection: java.net.HttpURLConnection? = null
            try {
                val conn = DaemonHttpClient.open(
                    "/api/surveillance/abort-restart", "POST", 2000, 2000)
                connection = conn
                conn.doOutput = true
                conn.outputStream.use { it.write(byteArrayOf()) }
                conn.responseCode
            } catch (_: Exception) {
                // Best effort: the original launcher error remains authoritative.
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }.start()
    }
    
    private fun startSentryDaemon(callback: DaemonCallback?) {
        adbLauncher.launchSentryDaemon(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                logManager.debug(TAG, "[SENTRY] $message")
                callback?.onLog(DaemonType.SENTRY, message)
            }
            
            override fun onLaunched() {
                updateDaemonState(DaemonType.SENTRY, true)
                callback?.onStarted(DaemonType.SENTRY)
            }
            
            override fun onError(error: String) {
                updateDaemonState(DaemonType.SENTRY, false)
                callback?.onError(DaemonType.SENTRY, error)
            }
        })
    }
    
    private fun stopSentryDaemon(callback: DaemonCallback?) {
        adbLauncher.stopSentryDaemon(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                logManager.debug(TAG, "[SENTRY] $message")
                callback?.onLog(DaemonType.SENTRY, message)
            }
            
            override fun onLaunched() {
                updateDaemonState(DaemonType.SENTRY, false)
                callback?.onStopped(DaemonType.SENTRY)
            }
            
            override fun onError(error: String) {
                callback?.onError(DaemonType.SENTRY, error)
            }
        })
    }
    
    private fun startBydEventDaemon(callback: DaemonCallback?) {
        // No app-side launcher remains for the standalone BYD event daemon.
        logManager.info(TAG, "BydEventDaemon start not implemented")
        updateDaemonState(DaemonType.BYD_EVENT, false)
        callback?.onError(DaemonType.BYD_EVENT, "Start not implemented")
    }
    
    private fun stopBydEventDaemon(callback: DaemonCallback?) {
        logManager.info(TAG, "BydEventDaemon stop not implemented")
        callback?.onError(DaemonType.BYD_EVENT, "Stop not implemented")
    }
    
    private fun updateDaemonState(type: DaemonType, running: Boolean) {
        val current = daemonStates[type] ?: DaemonState(type, false)
        daemonStates[type] = current.copy(
            running = running,
            startTime = if (running) System.currentTimeMillis() else current.startTime
        )
    }
}
