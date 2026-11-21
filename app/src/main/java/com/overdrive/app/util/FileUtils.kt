package com.overdrive.app.util

import java.io.File

/**
 * File operation utilities.
 */
object FileUtils {
    
    /**
     * Create directory if it doesn't exist.
     */
    fun ensureDirectoryExists(path: String): Boolean {
        return try {
            val dir = File(path)
            // An existing regular file cannot satisfy a directory request.
            // Recheck after mkdirs() to handle another caller creating it first.
            dir.isDirectory || dir.mkdirs() || dir.isDirectory
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Delete files older than specified age.
     * @param directory Directory to scan
     * @param maxAgeMs Maximum age in milliseconds
     * @return Number of files deleted
     */
    fun deleteOldFiles(directory: File, maxAgeMs: Long): Int {
        if (!directory.exists() || !directory.isDirectory) {
            return 0
        }
        
        val cutoffTime = System.currentTimeMillis() - maxAgeMs
        var deleted = 0
        
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoffTime) {
                if (file.delete()) {
                    deleted++
                }
            }
        }
        
        return deleted
    }
    
    /**
     * Get total size of files in directory.
     * @return Size in bytes
     */
    fun getDirectorySize(directory: File): Long {
        if (!directory.exists() || !directory.isDirectory) {
            return 0
        }
        
        var size = 0L
        directory.listFiles()?.forEach { file ->
            size += if (file.isFile) file.length() else getDirectorySize(file)
        }
        
        return size
    }
    
    /**
     * Format bytes to human-readable string.
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}
