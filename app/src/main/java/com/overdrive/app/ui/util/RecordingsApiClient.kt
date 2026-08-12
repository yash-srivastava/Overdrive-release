package com.overdrive.app.ui.util

import android.util.Log
import com.overdrive.app.ui.model.RecordingFile
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP client over the daemon's `/api/recordings` surface.
 *
 * <p>The daemon (UID 2000) owns an H2-backed RecordingsIndex; the app
 * (UID 10xxx) cannot open the H2 file directly across UIDs, so all reads
 * go via HTTP. This client lets {@link RecordingScanner} delegate to the
 * indexed SQL path, eliminating the legacy per-fragment 2-minute
 * filesystem walk.
 *
 * <p>All public methods are sync (caller is on a background executor) and
 * NEVER throw — failures map to null/false so {@link RecordingScanner} can
 * fall back to the direct-FS path. A short connect timeout (2s) keeps the
 * daemon-down case fast so the user doesn't wait when the index is
 * unreachable.
 *
 * <p>Daemon HTTP port = {@link com.overdrive.app.daemon.CameraDaemon#HTTP_PORT}
 * = 8080. Hardcoded here to keep the {@code com.overdrive.app.ui.util}
 * package free of daemon-process imports.
 */
object RecordingsApiClient {
    private const val TAG = "RecordingsApiClient"

    // Confirmed via CameraDaemon.java:54 — `public static final int HTTP_PORT = 8080;`.
    // We don't reference CameraDaemon.HTTP_PORT directly to keep the UI
    // utility package decoupled from the daemon class.
    private const val BASE_URL = "http://127.0.0.1:8080"

    // Lazy so the OkHttpClient isn't built until first use — saves a few
    // ms on cold start when the recordings UI isn't opened.
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            // We drive fallback explicitly — let one connect failure
            // bubble straight up so the caller flips to the direct-FS
            // path instead of OkHttp's silent retry burning the connect
            // budget.
            .retryOnConnectionFailure(false)
            .build()
    }

    // -------------------------------------------------------------------
    // Filter / page DTOs
    // -------------------------------------------------------------------

    /**
     * Mirror of {@link com.overdrive.app.server.RecordingsIndex.Filter}.
     * Empty sets / null fields disable that narrowing dimension.
     */
    data class Filter(
        /**
         * Single type: "normal" / "sentry" / "proximity" / "oemDashcam" / null=all.
         * Server auto-folds oemDashcam under "normal" for compat with web
         * clients. For multi-type queries (Dashcam segment = NORMAL +
         * PROXIMITY + OEM_DASHCAM together) use [types] instead — when
         * non-empty it supersedes [type] and the API handler emits the
         * CSV form.
         */
        val type: String? = null,
        /**
         * Multi-type filter. Empty = use [type]. When non-empty, sent to
         * the server as `type=a,b,c` CSV; server treats it as IN(...).
         * No auto-fold — caller must pass every wanted type explicitly.
         */
        val types: Set<String> = emptySet(),
        /** "yyyy-MM-dd" local; null = no date narrowing. */
        val date: String? = null,
        val classes: Set<String> = emptySet(),
        val severities: Set<String> = emptySet(),
        val proximities: Set<String> = emptySet(),
        /** Exact short-label match (chip selection). */
        val place: String? = null,
        /**
         * Free-text place substring search. Matches across short / medium /
         * displayName. Lowercased server-side. Stacks with [place] (both
         * must match). Use for search-box autocomplete or
         * "anything in Bay" style queries.
         */
        val placeContains: String? = null,
        /** ISO 3166-1 alpha-2 country, lowercased. */
        val country: String? = null,
        /**
         * Physical-volume filter: "INTERNAL" / "SD_CARD" / "USB". Empty = all
         * volumes (default; the index already spans every storage location).
         * Sent to the server as `storage=a,b` CSV.
         */
        val storages: Set<String> = emptySet()
    ) {
        /**
         * Build the query string for /api/recordings. CSV joins to match
         * the server's parseQuery (which splits on `,` and treats spaces
         * as `%20`).
         */
        /** Resolve which type param to send: types takes precedence over type. */
        private fun typeParam(): String? = when {
            types.isNotEmpty() -> types.joinToString(",")
            !type.isNullOrEmpty() -> type
            else -> null
        }

        fun toQuery(page: Int, pageSize: Int): String {
            val sb = StringBuilder()
            sb.append("page=").append(page)
            sb.append("&pageSize=").append(pageSize)
            typeParam()?.let { sb.append("&type=").append(enc(it)) }
            if (!date.isNullOrEmpty()) sb.append("&date=").append(enc(date))
            if (classes.isNotEmpty()) sb.append("&class=").append(enc(classes.joinToString(",")))
            if (severities.isNotEmpty()) sb.append("&severity=").append(enc(severities.joinToString(",")))
            if (proximities.isNotEmpty()) sb.append("&proximity=").append(enc(proximities.joinToString(",")))
            if (!place.isNullOrEmpty()) sb.append("&place=").append(enc(place))
            if (!placeContains.isNullOrEmpty()) sb.append("&placeContains=").append(enc(placeContains))
            if (!country.isNullOrEmpty()) sb.append("&country=").append(enc(country))
            if (storages.isNotEmpty()) sb.append("&storage=").append(enc(storages.joinToString(",")))
            return sb.toString()
        }

        /** Same as toQuery but without paging — for /places, /dates, /stats. */
        fun toContextQuery(): String {
            val sb = StringBuilder()
            typeParam()?.let { appendParam(sb, "type", it) }
            if (!date.isNullOrEmpty()) appendParam(sb, "date", date)
            if (classes.isNotEmpty()) appendParam(sb, "class", classes.joinToString(","))
            if (severities.isNotEmpty()) appendParam(sb, "severity", severities.joinToString(","))
            if (proximities.isNotEmpty()) appendParam(sb, "proximity", proximities.joinToString(","))
            if (!place.isNullOrEmpty()) appendParam(sb, "place", place)
            if (!placeContains.isNullOrEmpty()) appendParam(sb, "placeContains", placeContains)
            if (!country.isNullOrEmpty()) appendParam(sb, "country", country)
            if (storages.isNotEmpty()) appendParam(sb, "storage", storages.joinToString(","))
            return sb.toString()
        }

        private fun appendParam(sb: StringBuilder, k: String, v: String) {
            sb.append(if (sb.isEmpty()) "" else "&").append(k).append('=').append(enc(v))
        }
    }

    /** One page of /api/recordings, plus warmup state if the index is rebuilding. */
    data class Page(
        val recordings: List<RecordingFile>,
        val totalCount: Int,
        val totalPages: Int,
        val page: Int,
        val pageSize: Int,
        val warming: Boolean,
        val warmingDone: Int,
        val warmingTotal: Int,
        /**
         * Server-emitted hint that the index was empty for this query
         * but the filesystem actually has files (storage hot-plug,
         * type-switch, fresh boot). The daemon kicked an async reconcile;
         * caller should retry the same query after [retryAfterMs].
         */
        val reconciling: Boolean = false,
        val retryAfterMs: Long = 1500L,
        /**
         * Daemon reported its recordings index is down (H2 closed the store).
         * The clips are still on disk, so this is explicitly NOT an empty
         * library and NOT a reason to fall back to a direct-FS scan — the
         * caller should keep the current list and retry after [retryAfterMs].
         */
        val indexUnavailable: Boolean = false
    )

    /** /api/recordings/stats — flat numbers + warmup state. */
    data class StatsPayload(
        val totalCount: Long, val totalSize: Long, val totalToday: Long,
        val normalCount: Long, val normalSize: Long, val normalToday: Long,
        val sentryCount: Long, val sentrySize: Long, val sentryToday: Long,
        val proximityCount: Long, val proximitySize: Long, val proximityToday: Long,
        // Instant replays (replay_*). Zero on daemons predating type='replay'.
        val replayCount: Long, val replaySize: Long, val replayToday: Long,
        val warming: Boolean, val warmingDone: Int, val warmingTotal: Int,
        /**
         * Daemon reported its recordings index is down. Every counter in this
         * payload is zero and NOT authoritative — render "unknown", never "0".
         */
        val indexUnavailable: Boolean = false
    )

    /** /api/recordings/places row. */
    data class PlaceBucket(val key: String, val label: String, val count: Int)

    /** /api/recordings/dates row. */
    data class DateBucket(val date: String, val count: Int, val hasSentry: Boolean)

    // -------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------

    /**
     * Fetch one page. Returns null on any transport / parse failure so
     * the caller can fall back. A {@code warming=true} response is NOT a
     * failure — caller decides whether to surface the skeleton or wait.
     */
    fun fetchRecordings(filter: Filter, page: Int, pageSize: Int): Page? {
        return try {
            val url = "$BASE_URL/api/recordings?" + filter.toQuery(page, pageSize)
            val body = httpGet(url) ?: return null
            val root = JSONObject(body)
            // Index-down is a distinct, retryable state — surface it instead
            // of collapsing to null (which the caller treats as "daemon
            // unreachable" and answers with a latched direct-FS fallback).
            if (root.optBoolean("indexUnavailable", false)) {
                Log.w(TAG, "fetchRecordings: recordings index unavailable, retryable")
                return Page(
                    recordings = emptyList(),
                    totalCount = 0,
                    totalPages = 1,
                    page = root.optInt("page", page),
                    pageSize = root.optInt("pageSize", pageSize),
                    warming = false,
                    warmingDone = 0,
                    warmingTotal = 0,
                    retryAfterMs = root.optLong("retryAfterMs", 5000L),
                    indexUnavailable = true
                )
            }
            if (!root.optBoolean("success", false)) {
                Log.w(TAG, "fetchRecordings success=false: ${root.optString("error")}")
                return null
            }
            val warming = root.optBoolean("warming", false)
            val progress = root.optJSONObject("progress")
            val warmingDone = progress?.optInt("done", 0) ?: 0
            val warmingTotal = progress?.optInt("total", 0) ?: 0

            val arr = root.optJSONArray("recordings") ?: JSONArray()
            val list = ArrayList<RecordingFile>(arr.length())
            for (i in 0 until arr.length()) {
                val rec = arr.optJSONObject(i) ?: continue
                jsonToRecording(rec)?.let { list.add(it) }
            }
            Page(
                recordings = list,
                totalCount = root.optInt("totalCount", list.size),
                totalPages = root.optInt("totalPages", 1),
                page = root.optInt("page", page),
                pageSize = root.optInt("pageSize", pageSize),
                warming = warming,
                warmingDone = warmingDone,
                warmingTotal = warmingTotal,
                reconciling = root.optBoolean("reconciling", false),
                retryAfterMs = root.optLong("retryAfterMs", 1500L)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "fetchRecordings failed: ${t.message}")
            null
        }
    }

    /**
     * Page through everything matching `filter` in 200-row chunks.
     * Returns null on first failure; partial results discarded so the
     * caller knows to fall back. While `warming=true` returns an empty
     * list (caller should poll, NOT fall back during warmup).
     */
    fun fetchAllRecordings(filter: Filter): List<RecordingFile>? {
        val pageSize = 200
        val first = fetchRecordings(filter, 1, pageSize) ?: return null
        // Index down: return emptyList(), NOT null. null means "daemon
        // unreachable", and every RecordingScanner entry point answers that
        // with a full multi-dir direct-FS walk (sidecar-parsing every clip).
        // The dashboard alone would fire ~4 of those per onResume, and on
        // SD/USB volumes the walk returns nothing anyway because the app UID
        // can't read them — maximum cost, zero benefit. Same contract as the
        // warming case just below: empty now, correct after the retry.
        if (first.indexUnavailable) return emptyList()
        if (first.warming) return emptyList()
        if (first.recordings.isEmpty()) return emptyList()

        val total = first.totalCount
        val totalPages = first.totalPages
        if (totalPages <= 1) return first.recordings

        val all = ArrayList<RecordingFile>(total.coerceAtLeast(first.recordings.size))
        all.addAll(first.recordings)
        for (p in 2..totalPages) {
            val next = fetchRecordings(filter, p, pageSize) ?: return null
            // If the index began warming — or went down — mid-paging (rare),
            // bail to empty so the caller doesn't show a PARTIAL library as if
            // it were complete. Without the indexUnavailable clause an outage
            // starting on page 2 would return page 1's rows as an
            // authoritative full result (e.g. 200 of 500 clips, no error).
            if (next.warming || next.indexUnavailable) return emptyList()
            all.addAll(next.recordings)
        }
        return all
    }

    /** /api/recordings/stats. Null on failure. */
    fun fetchStats(): StatsPayload? {
        return try {
            val body = httpGet("$BASE_URL/api/recordings/stats") ?: return null
            val root = JSONObject(body)
            // Index down: report it explicitly rather than collapsing to null.
            // null means "daemon unreachable", which sends the caller down a
            // direct-FS aggregate that (on SD/USB, or when the walk finds
            // nothing) renders an authoritative "0 clips · 0 B" header — the
            // same lie this whole fix exists to remove, and it would sit
            // directly above the library's honest "index unavailable" card.
            if (root.optBoolean("indexUnavailable", false)) {
                return StatsPayload(
                    totalCount = 0, totalSize = 0, totalToday = 0,
                    normalCount = 0, normalSize = 0, normalToday = 0,
                    sentryCount = 0, sentrySize = 0, sentryToday = 0,
                    proximityCount = 0, proximitySize = 0, proximityToday = 0,
                    replayCount = 0, replaySize = 0, replayToday = 0,
                    warming = false, warmingDone = 0, warmingTotal = 0,
                    indexUnavailable = true
                )
            }
            if (!root.optBoolean("success", false)) return null
            val warmObj = root.optJSONObject("indexState")
            StatsPayload(
                totalCount = root.optLong("totalCount", 0),
                totalSize = root.optLong("totalSize", 0),
                totalToday = root.optLong("totalTodayCount", 0),
                normalCount = root.optLong("normalCount", 0),
                normalSize = root.optLong("normalSize", 0),
                normalToday = root.optLong("normalTodayCount", 0),
                sentryCount = root.optLong("sentryCount", 0),
                sentrySize = root.optLong("sentrySize", 0),
                sentryToday = root.optLong("sentryTodayCount", 0),
                proximityCount = root.optLong("proximityCount", 0),
                proximitySize = root.optLong("proximitySize", 0),
                proximityToday = root.optLong("proximityTodayCount", 0),
                replayCount = root.optLong("replayCount", 0),
                replaySize = root.optLong("replaySize", 0),
                replayToday = root.optLong("replayTodayCount", 0),
                warming = warmObj?.optBoolean("warming", false) ?: false,
                warmingDone = warmObj?.optInt("done", 0) ?: 0,
                warmingTotal = warmObj?.optInt("total", 0) ?: 0
            )
        } catch (t: Throwable) {
            Log.w(TAG, "fetchStats failed: ${t.message}")
            null
        }
    }

    /** /api/recordings/places. Null on failure. */
    fun fetchPlaces(filter: Filter): List<PlaceBucket>? {
        return try {
            val ctx = filter.toContextQuery()
            val url = "$BASE_URL/api/recordings/places" + (if (ctx.isEmpty()) "" else "?$ctx")
            val body = httpGet(url) ?: return null
            val root = JSONObject(body)
            if (!root.optBoolean("success", false)) return null
            val arr = root.optJSONArray("places") ?: JSONArray()
            val out = ArrayList<PlaceBucket>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                out.add(PlaceBucket(
                    key = r.optString("key", ""),
                    label = r.optString("label", ""),
                    count = r.optInt("count", 0)
                ))
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "fetchPlaces failed: ${t.message}")
            null
        }
    }

    /** /api/recordings/dates. Null on failure. */
    fun fetchDates(): List<DateBucket>? {
        return try {
            val body = httpGet("$BASE_URL/api/recordings/dates") ?: return null
            val root = JSONObject(body)
            if (!root.optBoolean("success", false)) return null
            val arr = root.optJSONArray("dates") ?: JSONArray()
            val out = ArrayList<DateBucket>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                out.add(DateBucket(
                    date = r.optString("date", ""),
                    count = r.optInt("count", 0),
                    hasSentry = r.optBoolean("hasSentry", false)
                ))
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "fetchDates failed: ${t.message}")
            null
        }
    }

    /**
     * DELETE /api/recordings/{filename}. Returns true on HTTP 200 with
     * {success:true}. Body of the DELETE response is otherwise ignored.
     */
    fun deleteRecording(filename: String): Boolean {
        return deleteRecordingUrl("$BASE_URL/api/recordings/${enc(filename)}", filename)
    }

    fun deleteRecording(recording: RecordingFile): Boolean {
        val id = recording.recordingId
        val url = if (!id.isNullOrEmpty()) {
            "$BASE_URL/api/recordings/id/${enc(id)}"
        } else {
            "$BASE_URL/api/recordings/${enc(recording.file.name)}"
        }
        return deleteRecordingUrl(url, recording.file.name)
    }

    private fun deleteRecordingUrl(url: String, label: String): Boolean {
        return try {
            if (label.isEmpty() || label.contains("..") || label.contains("/")) {
                return false
            }
            val req = Request.Builder()
                .url(url)
                .delete()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "deleteRecording HTTP ${resp.code} for $label")
                    return false
                }
                val body = resp.body?.string() ?: return false
                val obj = JSONObject(body)
                obj.optBoolean("success", false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "deleteRecording failed: ${t.message}")
            false
        }
    }

    // -------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------

    private fun httpGet(url: String): String? {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GET $url -> HTTP ${resp.code}")
                // 503 is the daemon's structured "recordings index is down"
                // reply and carries a JSON body ({indexUnavailable:true,
                // retryAfterMs}). Return it so callers can distinguish a
                // transient index outage — which should be RETRIED — from the
                // daemon being unreachable, which falls back to a direct-FS
                // scan. Dropping the body here made every index outage look
                // like an unreachable daemon: the library fragment latched
                // into no-pagination fallback mode with no retry, and on
                // SD/USB installs (unreadable from the app UID) that meant a
                // permanently empty library.
                if (resp.code == 503) return resp.body?.string()
                return null
            }
            return resp.body?.string()
        }
    }

    private fun enc(s: String): String =
        try { URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }

    /**
     * Map one JSON row from /api/recordings to a {@link RecordingFile}.
     * Sidecar fields are optional — legacy clips emit only the base keys.
     */
    private fun jsonToRecording(rec: JSONObject): RecordingFile? {
        val name = rec.optString("filename").takeIf { it.isNotEmpty() } ?: return null
        val absPath = rec.optString("path").takeIf { it.isNotEmpty() } ?: return null
        val typeStr = rec.optString("type", "normal")
        val type = when (typeStr) {
            "sentry" -> RecordingFile.RecordingType.SENTRY
            "proximity" -> RecordingFile.RecordingType.PROXIMITY
            "oemDashcam" -> RecordingFile.RecordingType.OEM_DASHCAM
            "replay" -> RecordingFile.RecordingType.REPLAY
            else -> RecordingFile.RecordingType.NORMAL
        }
        val cameraId = rec.optInt("cameraId", 0)
        val timestamp = rec.optLong("timestamp", 0L)
        val sizeBytes = rec.optLong("size", 0L)

        val peakSeverity = rec.optString("peakSeverity").takeIf { it.isNotEmpty() }
        val peakProximity = rec.optString("peakProximity").takeIf { it.isNotEmpty() }
        val personCount = rec.optInt("personCount", 0)
        val vehicleCount = rec.optInt("vehicleCount", 0)
        val bikeCount = rec.optInt("bikeCount", 0)
        val animalCount = rec.optInt("animalCount", 0)

        // heroThumbnailUrl is "/thumb/<heroName>" — extract the filename
        // and resolve to an actual File via the same dir-search the
        // direct-FS path uses. The hero JPEG sits next to the mp4, so we
        // first try the mp4's parent dir, then fall back to the storage
        // mirrors. If we can't find it on disk, leave heroThumbnailFile
        // null — adapters tolerate that and fall back to the daemon's
        // thumbnail endpoint anyway.
        val heroName = rec.optString("heroThumbnailName").takeIf { it.isNotEmpty() }
            ?: rec.optString("heroThumbnailUrl").takeIf { it.isNotEmpty() }
        val heroThumbnailFile = heroName?.let { resolveHeroJpeg(it, absPath) }

        val actorsArr = rec.optJSONArray("actors")
        val actorClasses = if (actorsArr != null && actorsArr.length() > 0) {
            val list = ArrayList<String>(actorsArr.length())
            for (i in 0 until actorsArr.length()) {
                val a = actorsArr.optJSONObject(i) ?: continue
                val c = a.optString("class").takeIf { it.isNotEmpty() } ?: continue
                list.add(c)
            }
            list
        } else emptyList()

        val placeObj = rec.optJSONObject("place")
        val placeShortLabel = placeObj?.optString("short")?.takeIf { it.isNotEmpty() }
        val placeMediumLabel = placeObj?.optString("medium")?.takeIf { it.isNotEmpty() }
        val placeDisplayName = placeObj?.optString("displayName")?.takeIf { it.isNotEmpty() }
        val placeCountryCode = placeObj?.optString("countryCode")?.takeIf { it.isNotEmpty() }
        val placeSource = placeObj?.optString("source")?.takeIf { it.isNotEmpty() }

        val startLat = if (rec.has("startLat") && !rec.isNull("startLat")) rec.optDouble("startLat") else null
        val startLng = if (rec.has("startLng") && !rec.isNull("startLng")) rec.optDouble("startLng") else null

        // Server-side bucket label ("Today" / "Yesterday" / "MMM d, yyyy"),
        // emitted by RecordingsIndex.bucketLabelFor(ts). Passing it through
        // lets RecordingSectionHeaderDecoration skip its own date math.
        val bucketLabel = rec.optString("bucketLabel", "").takeIf { it.isNotEmpty() }

        // Per-clip storage tag (INTERNAL / SD_CARD / USB), classified
        // server-side from the path. Absent on legacy daemon builds → null,
        // adapter omits the badge.
        val storageType = rec.optString("storage").takeIf { it.isNotEmpty() }

        return RecordingFile(
            file = File(absPath),
            cameraId = cameraId,
            timestamp = timestamp,
            durationMs = 0L,
            sizeBytes = sizeBytes,
            type = type,
            peakSeverity = peakSeverity,
            peakProximity = peakProximity,
            personCount = personCount,
            vehicleCount = vehicleCount,
            bikeCount = bikeCount,
            animalCount = animalCount,
            heroThumbnailFile = heroThumbnailFile,
            actorClasses = actorClasses,
            placeShortLabel = placeShortLabel,
            placeMediumLabel = placeMediumLabel,
            placeDisplayName = placeDisplayName,
            placeCountryCode = placeCountryCode,
            placeSource = placeSource,
            startLat = startLat,
            startLng = startLng,
            bucketLabel = bucketLabel,
            storageType = storageType,
            recordingId = rec.optString("id").takeIf { it.isNotEmpty() },
            videoUrl = rec.optString("videoUrl").takeIf { it.isNotEmpty() },
            thumbnailUrl = rec.optString("thumbnailUrl").takeIf { it.isNotEmpty() },
            deleteUrl = rec.optString("deleteUrl").takeIf { it.isNotEmpty() },
            eventUrl = rec.optString("eventUrl").takeIf { it.isNotEmpty() },
            available = rec.optBoolean("available", true)
        )
    }

    /**
     * Resolve "/thumb/<heroName>" against the mp4's parent dir first
     * (where ThumbnailBuffer writes), then storage mirrors. Returns null
     * when the JPEG isn't on disk — adapters tolerate null and use the
     * daemon's /thumb/ endpoint.
     */
    private fun resolveHeroJpeg(heroUrl: String, mp4AbsPath: String): File? {
        return try {
            val slash = heroUrl.lastIndexOf('/')
            val heroName = if (slash >= 0 && slash + 1 < heroUrl.length)
                heroUrl.substring(slash + 1) else heroUrl
            if (heroName.isEmpty() || heroName.contains("..") || heroName.contains("/")) return null

            // Try the mp4's parent first — most heros sit right next to it.
            val mp4Parent = File(mp4AbsPath).parentFile
            if (mp4Parent != null) {
                val sibling = File(mp4Parent, heroName)
                if (sibling.exists() && sibling.length() > 0 && sibling.canRead()) return sibling
            }

            // Fallback: storage mirrors. Same dir search the daemon does.
            val sm = com.overdrive.app.storage.StorageManager.getInstance()
            val all = sm.allRecordingsDirs + sm.allSurveillanceDirs + sm.allProximityDirs
            for (dir in all) {
                val f = File(dir, heroName)
                if (f.exists() && f.length() > 0 && f.canRead()) return f
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
