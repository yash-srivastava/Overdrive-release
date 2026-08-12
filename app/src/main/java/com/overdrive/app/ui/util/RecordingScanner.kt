package com.overdrive.app.ui.util

import android.content.Context
import android.util.Log
import com.overdrive.app.ui.model.RecordingFile
import java.io.File
import java.util.Calendar

/**
 * Recording scanner — thin client over the daemon's H2 RecordingsIndex
 * via {@link RecordingsApiClient}, with the legacy direct-filesystem
 * walk retained as a graceful fallback.
 *
 * <p>Every public method tries the API first; on transport failure it
 * falls back to {@code *Direct} (the original implementation, unchanged).
 * On warmup ({@code warming=true}) the API result is honored — caller
 * gets an empty list and is expected to poll, NOT fall back to the
 * 2-minute direct walk that was the original problem.
 *
 * <p>Threading: every call does sync HTTP + sync I/O, must be invoked
 * from a background executor.
 */
object RecordingScanner {
    private const val TAG = "RecordingScanner"

    // ==================== Public API ====================

    /**
     * Scan all recordings. Indexed SQL via {@link RecordingsApiClient};
     * direct-FS fallback only when the daemon is unreachable.
     */
    fun scanRecordings(context: Context): List<RecordingFile> {
        val apiResult = RecordingsApiClient.fetchAllRecordings(RecordingsApiClient.Filter())
        if (apiResult != null) {
            // Empty list during warmup is intentional — caller polls; do
            // NOT fall back to the slow walk.
            return apiResult
        }
        Log.w(TAG, "API unreachable, falling back to direct filesystem scan")
        return scanRecordingsDirect(context)
    }

    fun scanNormalRecordings(context: Context): List<RecordingFile> {
        val apiResult = RecordingsApiClient.fetchAllRecordings(
            RecordingsApiClient.Filter(type = "normal"))
        if (apiResult != null) return apiResult
        Log.w(TAG, "scanNormalRecordings: API unreachable, falling back")
        return scanRecordingsDirect(context).filter { it.type == RecordingFile.RecordingType.NORMAL }
    }

    fun scanSentryRecordings(context: Context): List<RecordingFile> {
        val apiResult = RecordingsApiClient.fetchAllRecordings(
            RecordingsApiClient.Filter(type = "sentry"))
        if (apiResult != null) return apiResult
        Log.w(TAG, "scanSentryRecordings: API unreachable, falling back")
        return scanRecordingsDirect(context).filter { it.type == RecordingFile.RecordingType.SENTRY }
    }

    fun scanProximityRecordings(context: Context): List<RecordingFile> {
        val apiResult = RecordingsApiClient.fetchAllRecordings(
            RecordingsApiClient.Filter(type = "proximity"))
        if (apiResult != null) return apiResult
        Log.w(TAG, "scanProximityRecordings: API unreachable, falling back")
        return scanRecordingsDirect(context).filter { it.type == RecordingFile.RecordingType.PROXIMITY }
    }

    fun scanOemDashcamRecordings(context: Context): List<RecordingFile> {
        val apiResult = RecordingsApiClient.fetchAllRecordings(
            RecordingsApiClient.Filter(type = "oemDashcam"))
        if (apiResult != null) return apiResult
        Log.w(TAG, "scanOemDashcamRecordings: API unreachable, falling back")
        return scanRecordingsDirect(context).filter { it.type == RecordingFile.RecordingType.OEM_DASHCAM }
    }

    /**
     * No-op now — the H2 index is server-driven and the local
     * {@code cachedRecordings} pre-cache it used to populate is gone.
     * Kept as a public symbol because callers (RecordingsFragment, etc.)
     * still call it after writes for symmetry; safe to keep.
     */
    fun invalidateCache() {
        // Server-driven cache; nothing to invalidate locally.
    }

    /**
     * Delete a recording and its sidecars. Tries the daemon's DELETE
     * endpoint first (so the H2 row drops eagerly); falls back to direct
     * FS delete when the daemon is unreachable. Either path also cleans
     * up local sibling thumbs that the daemon's sweep may not see when
     * the storage dir tree differs across UIDs.
     */
    fun deleteRecording(recording: RecordingFile): Boolean {
        // Try API first. The daemon's deleteSidecars() handles JSON,
        // cached thumb, hero JPEG, per-actor thumbs, and removes the H2
        // row — so on success we only need to mop up any sibling thumbs
        // that live under directories the daemon's StorageManager view
        // doesn't enumerate (rare, but harmless to do).
        val apiOk = RecordingsApiClient.deleteRecording(recording)
        if (apiOk) {
            cleanupLocalSidecars(recording)
            invalidateCache()
            return true
        }

        Log.w(TAG, "deleteRecording API failed, falling back to direct delete")
        val ok = deleteRecordingDirect(recording)
        invalidateCache()
        return ok
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
     */
    fun getSentryEventsDir(context: Context): File {
        return com.overdrive.app.storage.StorageManager.getInstance().surveillanceDir
    }

    /**
     * Get the active proximity events directory (respects configured storage type).
     */
    fun getProximityEventsDir(context: Context): File {
        return com.overdrive.app.storage.StorageManager.getInstance().proximityDir
    }

    // ==================== Date-based Queries ====================

    fun getRecordingsForDate(context: Context, year: Int, month: Int, day: Int): List<RecordingFile> {
        val ymd = String.format("%04d-%02d-%02d", year, month + 1, day)
        val apiResult = RecordingsApiClient.fetchAllRecordings(
            RecordingsApiClient.Filter(date = ymd))
        if (apiResult != null) return apiResult
        Log.w(TAG, "getRecordingsForDate: API unreachable, falling back")
        return getRecordingsForDateDirect(context, year, month, day)
    }

    fun getDatesWithRecordings(context: Context): Set<Long> {
        val buckets = RecordingsApiClient.fetchDates()
        if (buckets != null) {
            val out = HashSet<Long>(buckets.size * 2)
            val cal = Calendar.getInstance()
            for (b in buckets) {
                val parts = b.date.split("-")
                if (parts.size != 3) continue
                val y = parts[0].toIntOrNull() ?: continue
                val m = parts[1].toIntOrNull() ?: continue
                val d = parts[2].toIntOrNull() ?: continue
                cal.clear()
                // month is 0-based in Calendar; ymd is 1-based.
                cal.set(y, m - 1, d, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                out.add(cal.timeInMillis)
            }
            return out
        }
        Log.w(TAG, "getDatesWithRecordings: API unreachable, falling back")
        return getDatesWithRecordingsDirect(context)
    }

    fun getRecordingCountsByDate(context: Context, year: Int, month: Int): Map<Int, Int> {
        val buckets = RecordingsApiClient.fetchDates()
        if (buckets != null) {
            val out = HashMap<Int, Int>()
            val prefix = String.format("%04d-%02d-", year, month + 1)
            for (b in buckets) {
                if (!b.date.startsWith(prefix)) continue
                val day = b.date.substring(prefix.length).toIntOrNull() ?: continue
                out[day] = (out[day] ?: 0) + b.count
            }
            return out
        }
        Log.w(TAG, "getRecordingCountsByDate: API unreachable, falling back")
        return getRecordingCountsByDateDirect(context, year, month)
    }

    // ==================== Size Queries ====================

    fun getTotalRecordingsSize(context: Context): Long {
        val s = RecordingsApiClient.fetchStats()
        // indexUnavailable => counters are zeroed placeholders, not real sizes.
        if (s != null && !s.indexUnavailable) return s.totalSize
        Log.w(TAG, "getTotalRecordingsSize: API unreachable, falling back")
        return scanRecordingsDirect(context).sumOf { it.sizeBytes }
    }

    fun getNormalRecordingsSize(context: Context): Long {
        val s = RecordingsApiClient.fetchStats()
        if (s != null && !s.indexUnavailable) return s.normalSize
        Log.w(TAG, "getNormalRecordingsSize: API unreachable, falling back")
        return scanRecordingsDirect(context)
            .filter { it.type == RecordingFile.RecordingType.NORMAL }
            .sumOf { it.sizeBytes }
    }

    fun getSentryRecordingsSize(context: Context): Long {
        val s = RecordingsApiClient.fetchStats()
        if (s != null && !s.indexUnavailable) return s.sentrySize
        Log.w(TAG, "getSentryRecordingsSize: API unreachable, falling back")
        return scanRecordingsDirect(context)
            .filter { it.type == RecordingFile.RecordingType.SENTRY }
            .sumOf { it.sizeBytes }
    }

    fun getProximityRecordingsSize(context: Context): Long {
        val s = RecordingsApiClient.fetchStats()
        if (s != null && !s.indexUnavailable) return s.proximitySize
        Log.w(TAG, "getProximityRecordingsSize: API unreachable, falling back")
        return scanRecordingsDirect(context)
            .filter { it.type == RecordingFile.RecordingType.PROXIMITY }
            .sumOf { it.sizeBytes }
    }

    // ====================================================================
    // Direct-FS fallback path — ALL the original implementation, kept verbatim
    // (only renamed) so when the daemon is unreachable we still produce a
    // usable list. This is the 2-minute path; we only hit it when /api is
    // down.
    // ====================================================================

    private fun scanRecordingsDirect(context: Context): List<RecordingFile> {
        // Use StorageManager as single source of truth for all storage locations
        val sm = com.overdrive.app.storage.StorageManager.getInstance()

        // Scan ALL locations for each type (active + alternate)
        val normal = mutableListOf<RecordingFile>()
        val seenNormal = mutableSetOf<String>()
        for (dir in sm.allRecordingsDirs) {
            scanDirectoryDedup(dir, RecordingFile.RecordingType.NORMAL, normal, seenNormal)
        }
        val sentry = mutableListOf<RecordingFile>()
        val seenSentry = mutableSetOf<String>()
        for (dir in sm.allSurveillanceDirs) {
            scanDirectoryDedup(dir, RecordingFile.RecordingType.SENTRY, sentry, seenSentry)
        }
        val proximity = mutableListOf<RecordingFile>()
        val seenProximity = mutableSetOf<String>()
        for (dir in sm.allProximityDirs) {
            scanDirectoryDedup(dir, RecordingFile.RecordingType.PROXIMITY, proximity, seenProximity)
        }
        // OEM Dashcam clips (dvr_*.mp4) live in the same physical directory
        // as cam_*.mp4 (StorageManager.allRecordingsDirs), but parse as a
        // distinct type so the segmented control / library can show them
        // separately. The scanner is type-aware: parseOemDashcamRecording
        // only returns non-null for dvr_* files so the cam_* iteration
        // above doesn't claim them.
        val oemDashcam = mutableListOf<RecordingFile>()
        val seenOemDashcam = mutableSetOf<String>()
        for (dir in sm.allRecordingsDirs) {
            scanDirectoryDedup(dir, RecordingFile.RecordingType.OEM_DASHCAM, oemDashcam, seenOemDashcam)
        }

        // Instant replays (replay_*.mp4) also live in the recordings dirs —
        // same physical location as cam_*, own type so the Replays segment
        // can list them separately (mirrors the OEM dashcam arrangement).
        // Seed `seen` with the names the NORMAL/OEM passes already claimed:
        // parseFallbackRecording tags an UNKNOWN-prefixed .mp4 with whatever
        // type the pass asked for, so without the seed a foo.mp4 in these
        // shared dirs would be claimed a third time here. Well-formed
        // replay_* names are prefix-rejected by those passes and so are
        // never in the seed.
        val replay = mutableListOf<RecordingFile>()
        val seenReplay = (seenNormal + seenOemDashcam).toMutableSet()
        for (dir in sm.allRecordingsDirs) {
            scanDirectoryDedup(dir, RecordingFile.RecordingType.REPLAY, replay, seenReplay)
        }

        val allFiles = (normal + sentry + proximity + oemDashcam + replay).sortedByDescending { it.timestamp }

        Log.d(TAG, "Direct Scan: Found ${allFiles.size} total videos " +
            "(normal=${normal.size}, sentry=${sentry.size}, proximity=${proximity.size}, " +
            "oemDashcam=${oemDashcam.size}, replay=${replay.size})")

        return allFiles
    }

    /**
     * Scan a directory and add files, deduplicating by filename.
     * Files from the first scanned directory (active) take priority.
     *
     * Direct-FS fallback path: only fires when the daemon HTTP API is
     * unreachable. Routes through StorageManager.listMp4Files so
     * internal storage's FUSE-null edge case is handled. Note that on
     * SD/USB the app UID 10xxx may still get null listings even from
     * the shell-fallback (because Runtime.exec() runs as the app UID
     * and hits the same FUSE permission filter); the daemon path is
     * the canonical way to read SD/USB.
     */
    private fun scanDirectoryDedup(dir: File, type: RecordingFile.RecordingType,
                                    results: MutableList<RecordingFile>, seen: MutableSet<String>) {
        if (!dir.exists() || !dir.canRead()) return

        val files = try {
            com.overdrive.app.storage.StorageManager.getInstance().listMp4Files(dir)
        } catch (e: Exception) {
            // StorageManager not initialized in this process — fall back
            // to the bare listFiles() so the fallback isn't worse than
            // pre-rewrite behavior.
            dir.listFiles { f -> f.name.endsWith(".mp4") } ?: return
        }

        for (file in files) {
            if (!file.isFile) continue
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

            // v3 geo block — populated by EventTimelineCollector at sidecar
            // write time + asynchronously by SidecarGeoUpdater when the place
            // resolver completes. All fields are optional so legacy clips,
            // clips with no GPS fix, and clips written before geocoding was
            // enabled all read as nulls.
            val geo = root.optJSONObject("geo")
            val place = geo?.optJSONObject("place")
            val placeShort = place?.optString("district")?.takeIf { it.isNotEmpty() }
                ?: place?.optString("city")?.takeIf { it.isNotEmpty() }
                ?: place?.optString("displayName")?.takeIf { it.isNotEmpty() }
            val placeMedium = run {
                val d = place?.optString("district")?.takeIf { it.isNotEmpty() }
                val c = place?.optString("city")?.takeIf { it.isNotEmpty() }
                when {
                    d != null && c != null && d != c -> "$d, $c"
                    else -> placeShort
                }
            }
            val placeDisplay = place?.optString("displayName")?.takeIf { it.isNotEmpty() }
            val placeCC = place?.optString("countryCode")?.takeIf { it.isNotEmpty() }?.lowercase()
            val placeSrc = place?.optString("source")?.takeIf { it.isNotEmpty() }
            val startObj = geo?.optJSONObject("start")
            val startLat = startObj?.let {
                if (it.has("lat")) it.optDouble("lat") else null
            }
            val startLng = startObj?.let {
                if (it.has("lng")) it.optDouble("lng") else null
            }

            rec.copy(
                peakSeverity = sev,
                peakProximity = prox,
                personCount = person,
                vehicleCount = vehicle,
                bikeCount = bike,
                animalCount = animal,
                heroThumbnailFile = heroFile,
                actorClasses = classes,
                placeShortLabel = placeShort,
                placeMediumLabel = placeMedium,
                placeDisplayName = placeDisplay,
                placeCountryCode = placeCC,
                placeSource = placeSrc,
                startLat = startLat,
                startLng = startLng
            )
        } catch (e: Exception) {
            rec
        }
    }

    /**
     * Direct delete fallback — same logic as the original deleteRecording.
     * Drops the daemon's parse cache too, so when the daemon is back up
     * the next /api/recordings call doesn't return a phantom row.
     */
    private fun deleteRecordingDirect(recording: RecordingFile): Boolean {
        // Drop the web API's parse cache for this absolute path before the
        // file vanishes; otherwise /api/recordings would keep returning a
        // phantom row until the cache validator's mtime check finally fails.
        try {
            com.overdrive.app.server.RecordingsApiHandler
                .invalidateRecordingCache(recording.file.absolutePath)
        } catch (_: Throwable) {}

        val deleted = recording.file.delete()
        if (deleted) {
            cleanupLocalSidecars(recording)
        }
        return deleted
    }

    /**
     * Sweep local sibling files (JSON/SRT sidecars, cached thumb, hero
     * JPEG, per-actor thumbs). Called by both the API-success path (as
     * cross-UID belt-and-braces) and the direct-FS path. Mirrors the
     * legacy delete logic but extracted so both paths share it.
     */
    private fun cleanupLocalSidecars(recording: RecordingFile) {
        try {
            // JSON + SRT sidecars (event timeline + subtitles)
            val basePath = recording.file.absolutePath.removeSuffix(".mp4")
            for (ext in listOf(".json", ".srt")) {
                val sidecar = File(basePath + ext)
                if (sidecar.exists()) sidecar.delete()
            }

            // Cached thumbnail from the thumbs directory
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
        } catch (_: Throwable) {
            // Best-effort cleanup; never propagate.
        }
    }

    // -- Date-query fallbacks ---------------------------------------------

    private fun getRecordingsForDateDirect(context: Context, year: Int, month: Int, day: Int): List<RecordingFile> {
        val calendar = Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis

        return scanRecordingsDirect(context).filter {
            it.timestamp in startOfDay until endOfDay
        }
    }

    private fun getDatesWithRecordingsDirect(context: Context): Set<Long> {
        val calendar = Calendar.getInstance()
        return scanRecordingsDirect(context).map { recording ->
            calendar.timeInMillis = recording.timestamp
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        }.toSet()
    }

    private fun getRecordingCountsByDateDirect(context: Context, year: Int, month: Int): Map<Int, Int> {
        val rangeCalendar = Calendar.getInstance().apply {
            set(year, month, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfMonth = rangeCalendar.timeInMillis
        rangeCalendar.add(Calendar.MONTH, 1)
        val endOfMonth = rangeCalendar.timeInMillis

        return scanRecordingsDirect(context)
            .filter { it.timestamp in startOfMonth until endOfMonth }
            .groupBy { recording ->
                val dayCalendar = Calendar.getInstance()
                dayCalendar.timeInMillis = recording.timestamp
                dayCalendar.get(Calendar.DAY_OF_MONTH)
            }
            .mapValues { it.value.size }
    }
}
