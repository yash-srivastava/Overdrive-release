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
                // v3 sidecar enrichment (item 7). Backwards-compat: legacy clips
                // simply have no sidecar and the recording is added unchanged.
                val enriched = enrichWithSidecar(recording)
                results.add(enriched)
                seen.add(file.name)
            }
        }
    }

    /**
     * Best-effort sidecar parse. Returns the recording unchanged on any error
     * so old / corrupt / missing sidecars never break the list.
     */
    private fun enrichWithSidecar(rec: RecordingFile): RecordingFile {
        return try {
            val sidecar = File(rec.file.absolutePath.replace(".mp4", ".json"))
            if (!sidecar.exists() || !sidecar.canRead()) return rec
            // Cap at 64KB to avoid pathological reads
            val capBytes = 65536L
            val length = sidecar.length().coerceAtMost(capBytes).toInt()
            val text = sidecar.bufferedReader().use { br ->
                val sb = StringBuilder(length)
                val buf = CharArray(4096)
                var read = 0
                while (read < length) {
                    val n = br.read(buf, 0, minOf(buf.size, length - read))
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                    read += n
                }
                sb.toString()
            }
            val root = org.json.JSONObject(text)
            val stats = root.optJSONObject("stats")
            val sev = stats?.optString("peakSeverity")?.takeIf { it.isNotEmpty() }
            val prox = stats?.optString("peakProximity")?.takeIf { it.isNotEmpty() }
            val person  = stats?.optInt("personCount", 0) ?: 0
            val vehicle = stats?.optInt("vehicleCount", 0) ?: 0
            val bike    = stats?.optInt("bikeCount", 0) ?: 0
            val animal  = stats?.optInt("animalCount", 0) ?: 0
            val heroName = root.optString("heroThumbnail").takeIf { it.isNotEmpty() }
            val heroFile = heroName?.let { File(rec.file.parentFile, it) }?.takeIf { it.exists() }
            // Class list for filter chips. Includes static actors so the chip
            // matches "did this clip contain a vehicle?" rather than "was a
            // vehicle moving in this clip?". The tracker's isStatic flag fires
            // after just 2 frames (~200ms at 10fps) of bbox stability — a
            // vehicle drifting laterally through a quadrant trips it even
            // though it's clearly moving, so excluding statics here drops
            // legitimate matches. Severity / proximity filters key off the
            // peak* fields which EventTimelineCollector aggregates from
            // non-static actors only — that "scenery doesn't escalate" rule
            // still holds where it matters. Mirrors the server-side fix in
            // RecordingsApiHandler.parseRecordingUncached.
            val classes = mutableListOf<String>()
            val actorsArr = root.optJSONArray("actors")
            if (actorsArr != null) {
                for (i in 0 until actorsArr.length()) {
                    val a = actorsArr.optJSONObject(i) ?: continue
                    val c = a.optString("class").takeIf { it.isNotEmpty() } ?: continue
                    classes.add(c)
                }
            }
            rec.copy(
                peakSeverity = sev,
                peakProximity = prox,
                personCount = person,
                vehicleCount = vehicle,
                bikeCount = bike,
                animalCount = animal,
                heroThumbnailFile = heroFile,
                actorClasses = classes
            )
        } catch (e: Exception) {
            rec
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
     * Delete a recording file, its JSON sidecar (event timeline), and cached thumbnail.
     */
    fun deleteRecording(recording: RecordingFile): Boolean {
        // Drop the web API's parse cache for this absolute path before the
        // file vanishes; otherwise /api/recordings would keep returning a
        // phantom row until the cache validator's mtime check finally fails.
        try {
            com.overdrive.app.server.RecordingsApiHandler
                .invalidateRecordingCache(recording.file.absolutePath)
        } catch (_: Throwable) {}

        val deleted = recording.file.delete()
        if (deleted) {
            // Also delete JSON sidecar (event timeline) if it exists
            val jsonFile = File(recording.file.absolutePath.replace(".mp4", ".json"))
            if (jsonFile.exists()) {
                jsonFile.delete()
            }

            // Delete cached thumbnail from the thumbs directory
            val sm = com.overdrive.app.storage.StorageManager.getInstance()
            val recordingsDir = sm.recordingsDir
            val baseDir = recordingsDir.parentFile
            if (baseDir != null) {
                val thumbFile = File(File(baseDir, "thumbs"), recording.file.name.replace(".mp4", ".jpg"))
                if (thumbFile.exists()) {
                    thumbFile.delete()
                }
            }

            // v3 (item 7): also delete the sibling hero JPEG and per-actor thumbnails.
            // Per-actor thumbs are named "thumb_<base>_a<id>(_<rel>).jpg"; iterate the
            // parent dir for any file matching this prefix.
            val parent = recording.file.parentFile
            if (parent != null && parent.canRead()) {
                val base = recording.file.name.removeSuffix(".mp4")
                val heroSibling = File(parent, "$base.jpg")
                if (heroSibling.exists()) heroSibling.delete()
                // Anchor with "_a" — sibling segments share the timestamp
                // base; their thumbs ("thumb_<base>_2_a*.jpg") would
                // otherwise be swept too. ThumbnailBuffer always writes the
                // actor suffix as "_a<id>...", so this anchor is safe.
                val perActorPrefix = "thumb_${base}_a"
                parent.listFiles { f ->
                    f.isFile && f.name.startsWith(perActorPrefix) && f.name.endsWith(".jpg")
                }?.forEach { it.delete() }
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
