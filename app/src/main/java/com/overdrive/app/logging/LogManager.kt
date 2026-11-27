package com.overdrive.app.logging

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized logging manager for app-context code (Kotlin).
 * 
 * App logs are written to the app's cache directory.
 * Daemon logs (via DaemonLogger.java) go to /data/local/tmp.
 */
class LogManager private constructor(@Volatile private var config: LogConfig) {
    
    interface LogListener {
        fun onLog(tag: String, message: String, level: LogLevel)
    }
    
    companion object {
        @Volatile
        private var instance: LogManager? = null
        
        @Volatile
        private var logListener: LogListener? = null
        
        private const val TAG = "LogManager"
        
        fun getInstance(config: LogConfig = LogConfig.default()): LogManager {
            return instance ?: synchronized(this) {
                instance ?: LogManager(config).also { instance = it }
            }
        }
        
        fun setLogListener(listener: LogListener?) {
            logListener = listener
        }
    }
    
    private val writers = ConcurrentHashMap<String, PrintWriter>()
    private val fileSizes = ConcurrentHashMap<String, Long>()
    private val writeLock = Any()
    // ThreadLocal: SimpleDateFormat is NOT thread-safe, and format() below runs
    // OUTSIDE writeLock, concurrently from the daemon-health-check looper, the
    // adb-shell executor and the tunnel-poll looper. A shared instance can throw
    // ArrayIndexOutOfBoundsException/NumberFormatException under that race.
    // DaemonLogger.java already uses exactly this fix; LogManager never got it.
    private val timestampFormat: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        // Snapshot the volatile config ONCE so the console gate, file gate, and
        // logDir all read a single consistent instance even if updateConfig()
        // swaps it on another thread mid-call.
        val cfg = config
        // Level gate for the EXPENSIVE sinks only (logcat + the synchronously
        // FLUSHED file append, plus the timestamp format + string build they need).
        // Background paths call debug() on every adb shell command, so an ungated
        // DEBUG did a flushed disk write under a process-global lock thousands of
        // times a day. Mirrors DaemonLogger's minLevel gate.
        //
        // The listener is deliberately OUTSIDE this gate: it feeds the in-app Logs
        // screen (MainActivity.setupLogListener → LogsViewModel.addLog), which has
        // its own DEBUG level and does its own filtering. Early-returning before it
        // would silently empty the DEBUG view — a user-visible regression, not an
        // optimisation. Notifying a listener is a cheap in-memory call.
        try {
            logListener?.onLog(tag, message, level)
        } catch (e: Exception) {
            // Ignore listener errors
        }

        if (level.ordinal < cfg.minLevel.ordinal) return
        val timestamp = timestampFormat.get()!!.format(Date())
        val logLine = "[$timestamp] [${level.name}] [$tag] $message"

        if (cfg.enableConsoleLog) {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, message)
                LogLevel.INFO -> Log.i(tag, message)
                LogLevel.WARN -> Log.w(tag, message)
                LogLevel.ERROR -> Log.e(tag, message)
            }
        }

        if (cfg.enableFileLog && cfg.logDir.isNotEmpty()) {
            writeToFile(tag, logLine)
        }
    }
    
    private fun writeToFile(tag: String, logLine: String) {
        synchronized(writeLock) {
            try {
                // Self-heal if the live <tag>.log was deleted out from under a
                // cached append writer. LogCleaner (a separate WorkManager job
                // in this same process) deletes any *.log past retentionHours,
                // including the live file of a tag that went quiet. Because the
                // writer holds an fd to the now-unlinked inode, further writes
                // would land on an orphaned inode invisible at the path and be
                // lost on process exit. Detect the missing path and drop the
                // stale writer so getOrCreateWriter reopens a fresh file. Safe
                // under writeLock; cheap (one stat) on a path already doing fs I/O.
                if (writers.containsKey(tag) &&
                    !File(config.logDir, "${tag.lowercase()}.log").exists()) {
                    writers.remove(tag)?.close()
                    fileSizes.remove(tag)
                }
                val writer = getOrCreateWriter(tag)
                writer.println(logLine)
                writer.flush()
                
                val currentSize = fileSizes.getOrDefault(tag, 0L) + logLine.length + 1
                fileSizes[tag] = currentSize

                // Long arithmetic: maxFileSizeMB is Int, so `maxFileSizeMB *
                // 1024 * 1024` would be computed in 32-bit and silently wrap
                // negative for maxFileSizeMB >= 2048 (→ rotate on every write).
                // .toLong() forces 64-bit. Matches DaemonLogger.java:343.
                if (currentSize >= config.maxFileSizeMB.toLong() * 1024L * 1024L) {
                    rotateLogFile(tag)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log: ${e.message}")
            }
        }
    }
    
    private fun getOrCreateWriter(tag: String): PrintWriter {
        return writers.getOrPut(tag) {
            val logFile = File(config.logDir, "${tag.lowercase()}.log")
            logFile.parentFile?.mkdirs()

            // If the pre-existing file is already over the cap (e.g. a prior
            // process wrote a huge log), rotate it now so the appended writer
            // doesn't start beyond the threshold. Must run BEFORE seeding the
            // size and opening the writer.
            if (logFile.exists() &&
                logFile.length() >= config.maxFileSizeMB.toLong() * 1024L * 1024L) {
                rotateExisting(tag)
            }

            // Seed the in-memory counter from the file we are about to APPEND
            // to (0 if it was just rotated away). Open in append mode — the
            // previous code used File.outputStream() which has no append flag
            // and truncated the file to zero on the first write after every
            // app process start, destroying the prior session's logs (exactly
            // the logs that would explain a crash-then-relaunch). Mirrors
            // DaemonLogger's FileOutputStream(logFile, true).
            fileSizes[tag] = if (logFile.exists()) logFile.length() else 0L
            PrintWriter(FileOutputStream(logFile, true).bufferedWriter(Charsets.UTF_8), true)
        }
    }

    /** Rotate an oversized log before opening its append writer. */
    private fun rotateExisting(tag: String) {
        try {
            rotateArchives(tag, skipMissing = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate pre-existing log file: ${e.message}")
        }
    }

    private fun rotateLogFile(tag: String) {
        try {
            writers[tag]?.close()
            writers.remove(tag)
            rotateArchives(tag, skipMissing = false)
            Log.i(TAG, "Rotated log file: ${tag.lowercase()}.log")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file: ${e.message}")
        }
    }

    /** Shift archives oldest-first, then move the live log to .1. */
    private fun rotateArchives(tag: String, skipMissing: Boolean) {
        val logFile = File(config.logDir, "${tag.lowercase()}.log")
        for (i in config.rotationCount downTo 1) {
            val oldFile = File(config.logDir, "${tag.lowercase()}.log.$i")
            if (i == config.rotationCount) {
                oldFile.delete()
            } else {
                val newFile = File(config.logDir, "${tag.lowercase()}.log.${i + 1}")
                if (!skipMissing || oldFile.exists()) oldFile.renameTo(newFile)
            }
        }
        val rotatedFile = File(config.logDir, "${tag.lowercase()}.log.1")
        logFile.renameTo(rotatedFile)
        fileSizes[tag] = 0L
    }

    fun debug(tag: String, message: String) = log(tag, message, LogLevel.DEBUG)
    fun info(tag: String, message: String) = log(tag, message, LogLevel.INFO)
    fun warn(tag: String, message: String) = log(tag, message, LogLevel.WARN)
    
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.message}"
        } else {
            message
        }
        log(tag, fullMessage, LogLevel.ERROR)
    }
    
    fun updateConfig(newConfig: LogConfig) {
        synchronized(writeLock) {
            if (newConfig.isValid()) {
                this.config = newConfig
            }
        }
    }
    
    fun getConfig(): LogConfig = config
    
    fun stop() {
        synchronized(writeLock) {
            writers.values.forEach { it.close() }
            writers.clear()
            fileSizes.clear()
        }
    }
}
