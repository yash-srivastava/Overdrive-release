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
}
