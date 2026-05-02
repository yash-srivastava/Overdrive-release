package com.overdrive.app.ui.util

import android.content.Context
import android.util.Log
import com.overdrive.app.ui.model.RecordingFile
import java.io.File
import java.util.Calendar

/**
 * Simplified Scanner - Uses Direct File Access (SOTA Architecture).
 * Since App owns the directory, we trust the Disk, not the Database.
 * 
 * SOTA: Uses StorageManager as single source of truth for storage paths.
 * Scans ALL locations (internal + SD card) to ensure files are found
 * regardless of which storage is currently active.
 */
object RecordingScanner {
    private const val TAG = "RecordingScanner"
    
    // Legacy paths for backward compatibility (migration)
    private const val LEGACY_RECORDINGS_DIR = "/storage/emulated/0/Android/data/com.overdrive.app/files"
    private const val LEGACY_SENTRY_DIR = "$LEGACY_RECORDINGS_DIR/sentry_events"
    
    // Simple cache to prevent IO spam on UI refresh
    private var cachedRecordings: List<RecordingFile>? = null
    private var cacheTimestamp: Long = 0
    private const val CACHE_VALIDITY_MS = 5000L // 5 seconds
    
    /**
     * Scan all recordings directly from Disk.
     * SOTA: Scans both internal and SD card locations via StorageManager
     * to ensure files are found regardless of which storage is active.
     */
    fun scanRecordings(context: Context): List<RecordingFile> {
        val now = System.currentTimeMillis()
        
        // Return cache if still valid
        cachedRecordings?.let { cached ->
            if (now - cacheTimestamp < CACHE_VALIDITY_MS) {
                return cached
            }
        }
        
        // Use StorageManager as single source of truth for all storage locations
        val sm = com.overdrive.app.storage.StorageManager.getInstance()
        
        // Scan ALL locations for each type (active + alternate)
        val normal = mutableListOf<RecordingFile>()
        val seenNormal = mutableSetOf<String>()
        for (dir in sm.allRecordingsDirs) {
            scanDirectoryDedup(dir, RecordingFile.RecordingType.NORMAL, normal, seenNormal)
        }
        // Also scan legacy location
        val legacyDir = File(LEGACY_RECORDINGS_DIR)
        if (legacyDir.exists()) {
            scanDirectoryDedup(legacyDir, RecordingFile.RecordingType.NORMAL, normal, seenNormal)
        }
        
        val sentry = mutableListOf<RecordingFile>()
        val seenSentry = mutableSetOf<String>()
        for (dir in sm.allSurveillanceDirs) {
            scanDirectoryDedup(dir, RecordingFile.RecordingType.SENTRY, sentry, seenSentry)
        }
        // Also scan legacy sentry location
        val legacySentryDir = File(LEGACY_SENTRY_DIR)
        if (legacySentryDir.exists()) {
            scanDirectoryDedup(legacySentryDir, RecordingFile.RecordingType.SENTRY, sentry, seenSentry)
        }
        
        val proximity = mutableListOf<RecordingFile>()
        val seenProximity = mutableSetOf<String>()
        for (dir in sm.allProximityDirs) {
            scanDirectoryDedup(dir, RecordingFile.RecordingType.PROXIMITY, proximity, seenProximity)
        }
        
        val allFiles = (normal + sentry + proximity).sortedByDescending { it.timestamp }
        
        Log.d(TAG, "Direct Scan: Found ${allFiles.size} total videos " +
            "(normal=${normal.size}, sentry=${sentry.size}, proximity=${proximity.size})")
        
        cachedRecordings = allFiles
        cacheTimestamp = now
        return allFiles
    }
    
    /**
     * Scan a directory and add files, deduplicating by filename.
     * Files from the first scanned directory (active) take priority.
     */
    private fun scanDirectoryDedup(dir: File, type: RecordingFile.RecordingType, 
                                    results: MutableList<RecordingFile>, seen: MutableSet<String>) {
        if (!dir.exists() || !dir.canRead()) return
        
        val files = dir.listFiles() ?: return
        
        for (file in files) {
            if (!file.isFile || !file.name.endsWith(".mp4")) continue
            // Skip ghost files (0-byte stale entries from unmounted SD card)
            if (file.length() <= 0 || !file.canRead()) continue
            if (seen.contains(file.name)) continue
            
            val recording = RecordingFile.fromFile(file, type)
            if (recording != null) {
                results.add(recording)
                seen.add(file.name)
            }
        }
    }
    
    /**
     * Invalidate the cache (call after recording/deletion).
     */
    fun invalidateCache() {
        cachedRecordings = null
        cacheTimestamp = 0
    }
    
    /**
     * Delete a recording file and its JSON sidecar (event timeline) if present.
     */
    fun deleteRecording(recording: RecordingFile): Boolean {
        val deleted = recording.file.delete()
        if (deleted) {
            // Also delete JSON sidecar (event timeline) if it exists
            val jsonFile = File(recording.file.absolutePath.replace(".mp4", ".json"))
            if (jsonFile.exists()) {
                jsonFile.delete()
            }
            invalidateCache()
        }
        return deleted
    }
    
    // ==================== Directory Getters ====================
    
    /**
     * Get the active recordings directory (respects configured storage type).
     * Uses StorageManager as single source of truth.
     */
    fun getRecordingsDir(context: Context): File {
        return com.overdrive.app.storage.StorageManager.getInstance().recordingsDir
    }
    
    /**
     * Get the active sentry events directory (respects configured storage type).
     * Uses StorageManager as single source of truth.
     */
    fun getSentryEventsDir(context: Context): File {
        return com.overdrive.app.storage.StorageManager.getInstance().surveillanceDir
    }
    
    /**
     * Get the active proximity events directory (respects configured storage type).
     * Uses StorageManager as single source of truth.
     */
    fun getProximityEventsDir(context: Context): File {
        return com.overdrive.app.storage.StorageManager.getInstance().proximityDir
    }
    
    // ==================== Filtered Scans ====================
    
    fun scanNormalRecordings(context: Context): List<RecordingFile> {
        return scanRecordings(context).filter { it.type == RecordingFile.RecordingType.NORMAL }
    }
    
    fun scanSentryRecordings(context: Context): List<RecordingFile> {
        return scanRecordings(context).filter { it.type == RecordingFile.RecordingType.SENTRY }
    }
    
    fun scanProximityRecordings(context: Context): List<RecordingFile> {
        return scanRecordings(context).filter { it.type == RecordingFile.RecordingType.PROXIMITY }
    }
    
    // ==================== Date-based Queries ====================
    
    fun getRecordingsForDate(context: Context, year: Int, month: Int, day: Int): List<RecordingFile> {
        val calendar = Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis
        
        return scanRecordings(context).filter { 
            it.timestamp in startOfDay until endOfDay 
        }
    }
    
    fun getDatesWithRecordings(context: Context): Set<Long> {
        val calendar = Calendar.getInstance()
        return scanRecordings(context).map { recording ->
            calendar.timeInMillis = recording.timestamp
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        }.toSet()
    }
    
    fun getRecordingCountsByDate(context: Context, year: Int, month: Int): Map<Int, Int> {
        val rangeCalendar = Calendar.getInstance().apply {
            set(year, month, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfMonth = rangeCalendar.timeInMillis
        rangeCalendar.add(Calendar.MONTH, 1)
        val endOfMonth = rangeCalendar.timeInMillis
        
        return scanRecordings(context)
            .filter { it.timestamp in startOfMonth until endOfMonth }
            .groupBy { recording ->
                val dayCalendar = Calendar.getInstance()
                dayCalendar.timeInMillis = recording.timestamp
                dayCalendar.get(Calendar.DAY_OF_MONTH)
            }
            .mapValues { it.value.size }
    }
    
    // ==================== Size Queries ====================
    
    fun getTotalRecordingsSize(context: Context): Long {
        return scanRecordings(context).sumOf { it.sizeBytes }
    }
    
    fun getNormalRecordingsSize(context: Context): Long {
        return scanNormalRecordings(context).sumOf { it.sizeBytes }
    }
    
    fun getSentryRecordingsSize(context: Context): Long {
        return scanSentryRecordings(context).sumOf { it.sizeBytes }
    }
    
    fun getProximityRecordingsSize(context: Context): Long {
        return scanProximityRecordings(context).sumOf { it.sizeBytes }
    }
}
