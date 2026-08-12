package com.overdrive.app.ui.model

import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a recorded video file.
 * SOTA: Supports both direct file access and MediaStore content URIs.
 */
data class RecordingFile(
    val file: File,
    val cameraId: Int,
    val timestamp: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val type: RecordingType,
    val contentUri: Uri? = null,  // SOTA: MediaStore content URI for cross-UID access
    // ---- v3 sidecar enrichment (item 7). Lazily populated; null on legacy clips. ----
    val peakSeverity: String? = null,    // "NOTICE" / "ALERT" / "CRITICAL"
    val peakProximity: String? = null,   // "VERY_CLOSE" / "CLOSE" / "MID" / "FAR"
    val personCount: Int = 0,
    val vehicleCount: Int = 0,
    val bikeCount: Int = 0,
    val animalCount: Int = 0,
    val heroThumbnailFile: File? = null,  // Sibling JPEG path or null
    val actorClasses: List<String> = emptyList(),  // ["person","vehicle",...] for filtering
    // ---- v3 sidecar geo enrichment ----
    val placeShortLabel: String? = null,    // "Cheras" / "Home" — chip text
    val placeMediumLabel: String? = null,   // "Cheras, Kuala Lumpur" — push body
    val placeDisplayName: String? = null,   // Long form, "Bandar Tun Razak, Cheras, KL, MY"
    val placeCountryCode: String? = null,   // ISO 3166-1 alpha-2, lowercased
    val placeSource: String? = null,        // "cache" / "nominatim" / "safezone" / "android-geocoder"
    val startLat: Double? = null,           // Recording-start GPS — used by "show on map"
    val startLng: Double? = null,
    // ---- Server-derived section header ----
    // Pre-formatted "Today" / "Yesterday" / "MMM d, yyyy" string emitted by
    // RecordingsIndex.bucketLabelFor(ts). Lets the section-header decoration
    // skip its own date math when results come from the API. Null when the
    // row originated from the direct-FS fallback path — decoration falls
    // back to its in-process grouping in that case.
    val bucketLabel: String? = null,
    // ---- Per-clip storage tag ----
    // "INTERNAL" / "SD_CARD" / "USB" — where the clip ACTUALLY landed. Set
    // server-side (RecordingsIndex via StorageManager.classifyStorageForPath)
    // and also derivable locally for the direct-FS fallback path. Surfaces the
    // silent SD→internal fallback (the SD card is bridged behind the USB power
    // rail, so cutting USB power unmounts it and clips fall back to internal).
    // Null = unknown/unclassified; the adapter omits the badge.
    val storageType: String? = null,
    // Stable server identity and action URLs. Null for direct-filesystem rows
    // and daemons predating the volume-aware v4 index.
    val recordingId: String? = null,
    val videoUrl: String? = null,
    val thumbnailUrl: String? = null,
    val deleteUrl: String? = null,
    val eventUrl: String? = null,
    val available: Boolean = true
) {
    // Secondary constructor for MediaStore results
    constructor(
        file: File,
        name: String,
        sizeBytes: Long,
        timestamp: Long,
        durationMs: Long,
        type: RecordingType,
        contentUri: Uri?
    ) : this(
        file = file,
        cameraId = extractCameraId(name),
        timestamp = timestamp,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        type = type,
        contentUri = contentUri
    )
    
    val name: String get() = file.name
    val path: String get() = file.absolutePath
    
    val formattedDate: String
        get() = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
    
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    
    val formattedDuration: String
        get() {
            val seconds = durationMs / 1000
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, secs)
            } else {
                String.format("%d:%02d", minutes, secs)
            }
        }
    
    val formattedSize: String
        get() = when {
            sizeBytes >= 1_000_000_000 -> String.format("%.1f GB", sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> String.format("%.1f MB", sizeBytes / 1_000_000.0)
            sizeBytes >= 1_000 -> String.format("%.1f KB", sizeBytes / 1_000.0)
            else -> "$sizeBytes B"
        }
    
    enum class RecordingType {
        NORMAL,         // Regular recordings (cam_*.mp4)
        SENTRY,         // Sentry event recordings (event_*.mp4)
        PROXIMITY,      // Proximity guard recordings (proximity_*.mp4)
        OEM_DASHCAM,    // OEM forward-sensor dashcam recordings (dvr_*.mp4)
        REPLAY          // Manual instant-replay clips (replay_*.mp4)
    }
    
    companion object {
        // Parse filename like: cam1_20251224_132630.mp4, cam_20251224_132630.mp4,
        // or segmented continuations cam_20251224_132630_2.mp4. Mirrors the
        // server-side RecordingsApiHandler.CAM_PATTERN so both surfaces see
        // every clip on disk.
        private val CAM_FILENAME_PATTERN = Regex("""cam(\d+)?_(\d{8})_(\d{6})(?:_\d+)?\.mp4""")
        // Manual instant-replay clips use their own namespace so their .tmp
        // can never collide with a live cam_* segment rotation.
        private val REPLAY_FILENAME_PATTERN = Regex("""replay_(\d{8})_(\d{6})(?:_\d+)?\.mp4""")
        // event_20251224_132630.mp4 or event_20251224_132630_2.mp4
        private val EVENT_FILENAME_PATTERN = Regex("""event_(\d{8})_(\d{6})(?:_\d+)?\.mp4""")
        // proximity_20251224_132630.mp4 or proximity_20251224_132630_2.mp4
        private val PROXIMITY_FILENAME_PATTERN = Regex("""proximity_(\d{8})_(\d{6})(?:_\d+)?\.mp4""")
        // dvr_20251224_132630.mp4 or dvr_20251224_132630_2.mp4 — OEM forward-sensor dashcam
        private val OEM_DASHCAM_FILENAME_PATTERN = Regex("""dvr_(\d{8})_(\d{6})(?:_\d+)?\.mp4""")

        /**
         * Parses the writer's `yyyyMMdd_HHmmss` filename stamp.
         *
         * Pinned to [Locale.US] for two reasons:
         *  1. The writer (GpuMosaicRecorder, SurveillanceEngineGpu, etc.) all
         *     format with Locale.US. Reading with `getDefault()` parsed those
         *     digits through whatever calendar the user's locale defaults to
         *     — Thai locale e.g. interprets the year as Buddhist Era and
         *     produced timestamps ~543 years off, dropping every clip out of
         *     "today" and breaking single-day filters.
         *  2. SimpleDateFormat is NOT thread-safe. The scanner runs from
         *     multiple workers (RecordingsFragment + RecordingLibraryFragment +
         *     DashboardInsight). A static-shared instance corrupts under
         *     concurrent parse() calls, returning null (→ mtime fallback) or
         *     wrong years intermittently — that was the "1 clip per day"
         *     symptom. Allocating per parse is far cheaper than the surrounding
         *     disk I/O.
         */
        private fun newDateFormat(): SimpleDateFormat =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        
        fun fromFile(file: File, type: RecordingType = RecordingType.NORMAL): RecordingFile? {
            // Try type-specific parsing first
            val result = when (type) {
                RecordingType.NORMAL -> parseNormalRecording(file)
                RecordingType.SENTRY -> parseSentryRecording(file)
                RecordingType.PROXIMITY -> parseProximityRecording(file)
                RecordingType.OEM_DASHCAM -> parseOemDashcamRecording(file)
                RecordingType.REPLAY -> parseReplayRecording(file)
            }

            // If type-specific parsing failed, try fallback parsing
            // This handles any .mp4 file that doesn't match expected patterns
            val parsed = result ?: parseFallbackRecording(file, type)
            // Tag the clip with where it actually lives, derived from its path.
            // Done once here (rather than in each per-type parser) so every
            // direct-FS row carries the storage badge, matching the API path's
            // server-side classification. Best-effort: a classifier/singleton
            // failure must never drop the row.
            return parsed?.copy(storageType = classifyStorage(file))
        }

        /** Classify a clip's storage volume from its path. Mirrors the
         *  server-side StorageManager.classifyStorageForPath so API rows and
         *  direct-FS fallback rows tag identically. Null on any failure. */
        private fun classifyStorage(file: File): String? {
            return try {
                com.overdrive.app.storage.StorageManager.getInstance()
                    .classifyStorageForPath(file.absolutePath)
            } catch (_: Throwable) {
                null
            }
        }
        
        /**
         * Fallback parser for .mp4 files that don't match expected patterns.
         * Uses file modification time as timestamp.
         *
         * <p>Reject files whose name prefix BELONGS to a different known type.
         * Without this guard, calling {@code fromFile(dvr_*.mp4, NORMAL)}
         * would land here (regex miss) and emit a NORMAL row for the file —
         * the SAME file would also be added by the OEM_DASHCAM scan pass,
         * producing a 2× double-count with corrupt metadata. The scanner
         * loops over all four types against {@code allRecordingsDirs}; only
         * fall back when the filename prefix doesn't claim a different type.
         */
        private fun parseFallbackRecording(file: File, type: RecordingType): RecordingFile? {
            if (!file.name.endsWith(".mp4")) return null

            // Reject mismatched prefixes. A file whose name claims a known
            // prefix is owned by THAT type — don't silently retag it as the
            // caller's type. Use prefix-only checks symmetrically across all
            // four types: previously the cam branch additionally required
            // CAM_FILENAME_PATTERN.matches, which let malformed cam-prefixed
            // files (cam.mp4, camcorder.mp4) fall through and get tagged
            // under whichever type the scanner asked for, double-counting
            // them across the NORMAL+OEM_DASHCAM passes over allRecordingsDirs.
            val name = file.name
            val matchedPrefix = when {
                name.startsWith("cam_") || name.matches(Regex("""cam\d+_.*""")) -> RecordingType.NORMAL
                name.startsWith("replay_") -> RecordingType.REPLAY
                name.startsWith("event_") -> RecordingType.SENTRY
                name.startsWith("proximity_") -> RecordingType.PROXIMITY
                name.startsWith("dvr_") -> RecordingType.OEM_DASHCAM
                else -> null
            }
            if (matchedPrefix != null && matchedPrefix != type) {
                return null
            }

            return RecordingFile(
                file = file,
                cameraId = 0,
                timestamp = file.lastModified(),
                durationMs = 0,
                sizeBytes = file.length(),
                type = type
            )
        }
        
        private fun parseNormalRecording(file: File): RecordingFile? {
            val camMatch = CAM_FILENAME_PATTERN.matchEntire(file.name) ?: return null
            val cameraId = camMatch.groupValues[1].toIntOrNull() ?: 0
            val dateStr = "${camMatch.groupValues[2]}_${camMatch.groupValues[3]}"
            val timestamp = try {
                newDateFormat().parse(dateStr)?.time ?: file.lastModified()
            } catch (e: Exception) {
                file.lastModified()
            }

            return RecordingFile(
                file = file,
                cameraId = cameraId,
                timestamp = timestamp,
                durationMs = 0, // Would need MediaMetadataRetriever to get actual duration
                sizeBytes = file.length(),
                type = RecordingType.NORMAL
            )
        }

        // replay_*.mp4 used to parse as NORMAL; it now carries its own type so
        // the recordings library can list instant replays in a dedicated
        // Replays segment instead of mixed with the dashcam loop. Mirrors the
        // server-side RecordingsIndex v3 classification.
        private fun parseReplayRecording(file: File): RecordingFile? {
            val match = REPLAY_FILENAME_PATTERN.matchEntire(file.name) ?: return null
            val dateStr = "${match.groupValues[1]}_${match.groupValues[2]}"
            val timestamp = try {
                newDateFormat().parse(dateStr)?.time ?: file.lastModified()
            } catch (e: Exception) {
                file.lastModified()
            }

            return RecordingFile(
                file = file,
                cameraId = 0,  // Replays remux the mosaic pre-record ring
                timestamp = timestamp,
                durationMs = 0,
                sizeBytes = file.length(),
                type = RecordingType.REPLAY
            )
        }
        
        private fun parseSentryRecording(file: File): RecordingFile? {
            val match = EVENT_FILENAME_PATTERN.matchEntire(file.name) ?: return null
            val dateStr = "${match.groupValues[1]}_${match.groupValues[2]}"
            val timestamp = try {
                newDateFormat().parse(dateStr)?.time ?: file.lastModified()
            } catch (e: Exception) {
                file.lastModified()
            }
            
            return RecordingFile(
                file = file,
                cameraId = 0,  // Sentry events are mosaic recordings
                timestamp = timestamp,
                durationMs = 0,
                sizeBytes = file.length(),
                type = RecordingType.SENTRY
            )
        }
        
        private fun parseProximityRecording(file: File): RecordingFile? {
            val match = PROXIMITY_FILENAME_PATTERN.matchEntire(file.name) ?: return null
            val dateStr = "${match.groupValues[1]}_${match.groupValues[2]}"
            val timestamp = try {
                newDateFormat().parse(dateStr)?.time ?: file.lastModified()
            } catch (e: Exception) {
                file.lastModified()
            }

            return RecordingFile(
                file = file,
                cameraId = 0,  // Proximity events are mosaic recordings
                timestamp = timestamp,
                durationMs = 0,
                sizeBytes = file.length(),
                type = RecordingType.PROXIMITY
            )
        }

        private fun parseOemDashcamRecording(file: File): RecordingFile? {
            val match = OEM_DASHCAM_FILENAME_PATTERN.matchEntire(file.name) ?: return null
            val dateStr = "${match.groupValues[1]}_${match.groupValues[2]}"
            val timestamp = try {
                newDateFormat().parse(dateStr)?.time ?: file.lastModified()
            } catch (e: Exception) {
                file.lastModified()
            }

            return RecordingFile(
                file = file,
                cameraId = 0,  // Single forward sensor; cameraId field is for AVM quadrant
                timestamp = timestamp,
                durationMs = 0,
                sizeBytes = file.length(),
                type = RecordingType.OEM_DASHCAM
            )
        }
        
        /**
         * Extract camera ID from filename.
         */
        private fun extractCameraId(name: String): Int {
            val match = CAM_FILENAME_PATTERN.matchEntire(name)
            return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }
}
