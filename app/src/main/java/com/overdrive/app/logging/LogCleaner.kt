package com.overdrive.app.logging

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background worker for automatic log cleanup.
 * 
 * Runs periodically to delete old log files based on retention policy.
 */
class LogCleaner(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val logManager = LogManager.getInstance()
            val config = logManager.getConfig()
            
            val stats = performCleanup(config)
            
            logManager.info("LogCleaner", 
                "Cleanup complete: ${stats.filesDeleted} files deleted, ${stats.spaceFreeKB}KB freed")
            
            Result.success()
        } catch (e: Exception) {
            LogManager.getInstance().error("LogCleaner", "Cleanup failed", e)
            Result.retry()
        }
    }
    
    /**
     * Perform cleanup of old log files.
     */
    private fun performCleanup(config: LogConfig): CleanupStats {
        val logDir = File(config.logDir)
        if (!logDir.exists() || !logDir.isDirectory) {
            return CleanupStats(0, 0)
        }
        
        val now = System.currentTimeMillis()
        val retentionMs = config.retentionHours * 60 * 60 * 1000L
        val cutoffTime = now - retentionMs
        
        var filesDeleted = 0
        var spaceFreeKB = 0L
        
        // Find and delete old log files
        logDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".log")) {
                // Check file age
                if (file.lastModified() < cutoffTime) {
                    val sizeKB = file.length() / 1024
                    if (file.delete()) {
                        filesDeleted++
                        spaceFreeKB += sizeKB
                    }
                }
            }
            
            // Also check rotated files (.log.1, .log.2, etc.)
            if (file.isFile && file.name.matches(Regex(".*\\.log\\.\\d+"))) {
                if (file.lastModified() < cutoffTime) {
                    val sizeKB = file.length() / 1024
                    if (file.delete()) {
                        filesDeleted++
                        spaceFreeKB += sizeKB
                    }
                }
            }
        }
        
        return CleanupStats(filesDeleted, spaceFreeKB)
    }
    
    private data class CleanupStats(val filesDeleted: Int, val spaceFreeKB: Long)

    companion object {
        /**
         * Schedule periodic log cleanup.
         */
        fun schedule(context: Context, intervalHours: Long) {
            val cleanupRequest = PeriodicWorkRequestBuilder<LogCleaner>(
                intervalHours, TimeUnit.HOURS
            ).build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "log_cleanup",
                ExistingPeriodicWorkPolicy.REPLACE,
                cleanupRequest
            )
        }
        
        /**
         * Cancel scheduled cleanup.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("log_cleanup")
        }
    }
}
