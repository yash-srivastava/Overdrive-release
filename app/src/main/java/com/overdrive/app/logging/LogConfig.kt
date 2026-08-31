package com.overdrive.app.logging

import android.content.Context
import java.io.File
import com.overdrive.app.util.ScratchPaths

/**
 * Configuration for LogManager.
 * 
 * App logs go to app's cache directory (accessible by app).
 * Daemon logs go to /data/local/tmp (accessible by ADB shell).
 * 
 * @param logDir Directory where log files are stored
 * @param retentionHours How long to keep log files before cleanup (hours)
 * @param cleanupIntervalHours How often to run cleanup checks (hours)
 * @param maxFileSizeMB Maximum size of a single log file before rotation (MB)
 * @param rotationCount Number of rotated log files to keep
 * @param enableConsoleLog Whether to log to Android Log (logcat)
 * @param enableFileLog Whether to write logs to file
 */
data class LogConfig(
    val logDir: String = "",
    val retentionHours: Int = 24,
    // 4h: matches DaemonLauncher.LOG_POLL_INTERVAL_SEC so the app-side cleaner
    // and the daemon-side shell poller run on one coherent housekeeping cadence.
    val cleanupIntervalHours: Int = 4,
    // 5MB: matches DaemonLauncher.LOG_MAX_BYTES so a single log size policy
    // governs both app-context logs and daemon stdout-redirect logs.
    val maxFileSizeMB: Int = 5,
    val rotationCount: Int = 3,
    val enableConsoleLog: Boolean = true,
    val enableFileLog: Boolean = false,
    // Minimum level that is emitted at all. Mirrors DaemonLogger's existing
    // Config.minLevel gate (default INFO), which LogManager never had: without
    // it EVERY debug() call formatted a timestamp, built a string, hit logcat
    // and did a synchronously-FLUSHED file append under a process-global lock.
    // Background paths call debug() on every adb shell command, so an idle app
    // process was doing thousands of flushed disk writes a day for lines nobody
    // reads (the release channel strips them via R8 anyway). DEBUG is still
    // available by passing a config with minLevel = LogLevel.DEBUG.
    val minLevel: LogLevel = LogLevel.INFO
) {
    companion object {
        private var appLogDir: String? = null
        
        /**
         * Initialize with app context to get proper log directory.
         * Call this in Application.onCreate() before using LogManager.
         */
        fun init(context: Context) {
            val logsDir = File(context.cacheDir, "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            appLogDir = logsDir.absolutePath
        }
        
        /**
         * Get the app's log directory.
         */
        fun getAppLogDir(): String? = appLogDir
        
        /**
         * Default config with file logging enabled (requires init() first).
         */
        fun default(): LogConfig {
            val logDir = appLogDir
            return if (logDir != null) {
                LogConfig(
                    logDir = logDir,
                    enableConsoleLog = true,
                    enableFileLog = true
                )
            } else {
                // Fallback to console only if not initialized
                consoleOnly()
            }
        }
        
        /**
         * Console-only logging (no file writes).
         */
        fun consoleOnly() = LogConfig(
            logDir = "",
            enableConsoleLog = true,
            enableFileLog = false
        )
        
        /**
         * Daemon log directory (for processes running via ADB shell).
         * These have shell permissions and can write to /data/local/tmp.
         */
        val DAEMON_LOG_DIR = ScratchPaths.getDir()
    }
    
    /**
     * Whether a line of [level] survives the severity gate — the same comparison
     * LogManager.log() applies, kept here as a pure function so the policy is
     * testable without a Context or Android's Log.
     */
    fun emits(level: LogLevel): Boolean = level.ordinal >= minLevel.ordinal

    /**
     * Validate configuration values.
     *
     * Upper bounds matter: `maxFileSizeMB` is consumed as
     * `maxFileSizeMB * 1024 * 1024`. Even with the Long-arithmetic fix in
     * LogManager, an absurd value (multi-GB cap) defeats rotation entirely, so
     * we clamp it to a sane 1 GB ceiling here — the gate every persist path
     * (updateLoggingConfig / importConfig) already runs through.
     */
    fun isValid(): Boolean {
        if (enableFileLog && logDir.isEmpty()) return false
        return retentionHours in 1..(24 * 30) &&     // ≤ 30 days
               cleanupIntervalHours in 1..(24 * 7) && // ≤ 1 week
               maxFileSizeMB in 1..1024 &&            // ≤ 1 GB (no Int overflow)
               rotationCount in 1..100
    }
}
