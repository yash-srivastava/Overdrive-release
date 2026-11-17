package com.overdrive.app.ui.timeline

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Sync client for the daemon's /api/timeline surface. Callers run on a
 * background executor; every method maps failures to a result, never throws.
 */
object TimelineApiClient {
    private const val TAG = "TimelineApiClient"
    private const val BASE_URL = "http://127.0.0.1:8080"
    private const val MAX_BODY_BYTES = 8L * 1024 * 1024

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    sealed class WindowResult {
        data class Ok(val window: TlWindow) : WindowResult()
        data class Failed(val offline: Boolean, val code: Int, val retryAfterMs: Long) : WindowResult()
    }

    fun fetchWindow(
        from: Long,
        to: Long,
        types: Set<String> = emptySet(),
        include: Set<String> = emptySet(),
    ): WindowResult {
        val sb = StringBuilder("$BASE_URL/api/timeline?from=").append(from).append("&to=").append(to)
        if (types.isNotEmpty()) sb.append("&types=").append(enc(types.joinToString(",")))
        if (include.isNotEmpty()) sb.append("&include=").append(enc(include.joinToString(",")))
        return try {
            val req = request(sb.toString())
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "timeline window HTTP ${resp.code}")
                    val retry = resp.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000L)
                    return WindowResult.Failed(false, resp.code, retry ?: 0L)
                }
                val body = resp.body ?: return WindowResult.Failed(false, resp.code, 0L)
                if (body.contentLength() > MAX_BODY_BYTES) return WindowResult.Failed(false, 413, 0L)
                val window = parseWindow(JSONObject(body.string()), from, to)
                WindowResult.Ok(window)
            }
        } catch (e: IOException) {
            Log.w(TAG, "timeline window unreachable: ${e.message}")
            WindowResult.Failed(true, 0, 0L)
        } catch (t: Throwable) {
            Log.w(TAG, "timeline window failed: ${t.message}")
            WindowResult.Failed(false, -1, 0L)
        }
    }

    /** Per-day footage summary; null when the daemon can't answer. */
    fun fetchDays(from: Long, to: Long): List<TlDay>? {
        return try {
            val body = httpGet("$BASE_URL/api/timeline/days?from=$from&to=$to") ?: return null
            val arr = JSONObject(body).optJSONArray("days") ?: return emptyList()
            val out = ArrayList<TlDay>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val day = str(o, "day") ?: continue
                out.add(
                    TlDay(
                        day = day,
                        footageMs = num(o, "footageMs")?.toLong() ?: 0L,
                        events = num(o, "events")?.toInt() ?: 0,
                        alerts = num(o, "alerts")?.toInt() ?: 0,
                    )
                )
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "timeline days failed: ${t.message}")
            null
        }
    }

    /** Sidecar JSON behind a segment's eventUrl; null when absent or unreadable. */
    fun fetchEventJson(eventUrl: String): JSONObject? {
        if (!eventUrl.startsWith("/api/events/")) return null
        return try {
            httpGet(BASE_URL + eventUrl)?.let { JSONObject(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "sidecar fetch failed: ${t.message}")
            null
        }
    }

    internal fun parseWindow(root: JSONObject, from: Long, to: Long): TlWindow {
        val segments = mapArray(root.optJSONArray("segments")) { o -> parseSegment(o) }
        val marks = mapArray(root.optJSONArray("marks")) { o ->
            val rec = str(o, "rec") ?: return@mapArray null
            val t0 = num(o, "t0")?.toLong() ?: return@mapArray null
            TlMark(
                rec = rec,
                t0 = t0,
                t1 = num(o, "t1")?.toLong()?.coerceAtLeast(t0) ?: t0,
                kind = str(o, "kind") ?: "motion",
                conf = num(o, "conf")?.toDouble(),
                cam = str(o, "cam"),
                alert = o.optBoolean("alert", false),
            )
        }
        val sessions = mapArray(root.optJSONArray("sessions")) { o ->
            val id = str(o, "id") ?: return@mapArray null
            TlSession(
                id = id,
                start = num(o, "start")?.toLong() ?: return@mapArray null,
                end = num(o, "end")?.toLong(),
                place = str(o, "place"),
                safeZone = str(o, "safeZone"),
                sentry = str(o, "sentry") ?: "unknown",
            )
        }
        val trips = mapArray(root.optJSONArray("trips")) { o ->
            TlTrip(
                id = num(o, "id")?.toLong() ?: return@mapArray null,
                start = num(o, "start")?.toLong() ?: return@mapArray null,
                end = num(o, "end")?.toLong(),
                distanceKm = num(o, "distanceKm")?.toDouble(),
            )
        }
        val states = mapArray(root.optJSONArray("states")) { o ->
            TlState(
                start = num(o, "start")?.toLong() ?: return@mapArray null,
                end = num(o, "end")?.toLong(),
                kind = str(o, "kind") ?: return@mapArray null,
                state = str(o, "state") ?: return@mapArray null,
                reason = str(o, "reason") ?: "unknown",
            )
        }
        val reaped = mapArray(root.optJSONArray("reaped")) { o ->
            val start = num(o, "start")?.toLong() ?: return@mapArray null
            TlReaped(
                start = start,
                end = num(o, "end")?.toLong()?.coerceAtLeast(start) ?: start,
                type = str(o, "type") ?: "",
                reason = str(o, "reason") ?: "removed",
            )
        }
        val road = mapArray(root.optJSONArray("road")) { o ->
            TlRoad(
                t = num(o, "t")?.toLong() ?: return@mapArray null,
                kind = str(o, "kind") ?: return@mapArray null,
                severity = str(o, "severity") ?: "minor",
            )
        }
        val live = mapArray(root.optJSONArray("live")) { o ->
            TlLive(
                type = str(o, "type") ?: return@mapArray null,
                since = num(o, "since")?.toLong() ?: return@mapArray null,
            )
        }
        return TlWindow(
            from = num(root, "from")?.toLong() ?: from,
            to = num(root, "to")?.toLong() ?: to,
            now = num(root, "now")?.toLong() ?: System.currentTimeMillis(),
            tzId = str(root, "tz") ?: java.util.TimeZone.getDefault().id,
            segments = segments.sortedBy { it.start },
            marks = marks.sortedBy { it.t0 },
            sessions = sessions,
            trips = trips,
            states = states,
            reaped = reaped,
            road = road,
            live = live,
            truncated = root.optBoolean("truncated", false),
        )
    }

    private fun parseSegment(o: JSONObject): TlSegment? {
        val id = str(o, "id") ?: return null
        val start = num(o, "start")?.toLong() ?: return null
        val sizeBytes = num(o, "sizeBytes")?.toLong() ?: 0L
        val mtime = num(o, "mp4Mtime")?.toLong() ?: 0L
        val classes = o.optJSONArray("classes")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotEmpty() && it != "null" } }
        } ?: emptyList()
        val counts = HashMap<String, Int>()
        o.optJSONObject("counts")?.let { c ->
            val keys = c.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val n = num(c, k)?.toInt() ?: continue
                if (n > 0) counts[k] = n
            }
        }
        return TlSegment(
            id = id,
            type = str(o, "type") ?: "normal",
            start = start,
            end = num(o, "end")?.toLong()?.takeIf { it >= start },
            spanSrc = str(o, "spanSrc") ?: "est",
            available = o.optBoolean("available", true),
            storage = str(o, "storage"),
            cameraId = num(o, "cameraId")?.toInt() ?: 0,
            layout = str(o, "layout"),
            sizeBytes = sizeBytes,
            mp4Mtime = mtime,
            fp = str(o, "fp") ?: "$sizeBytes-$mtime",
            videoUrl = str(o, "videoUrl") ?: "/video/id/$id",
            thumbUrl = str(o, "thumbUrl") ?: "/thumb/id/$id",
            eventUrl = str(o, "eventUrl") ?: "/api/events/id/$id",
            peakSeverity = str(o, "peakSeverity"),
            classes = classes,
            counts = counts,
            peakConfidence = num(o, "peakConfidence")?.toDouble(),
            cameras = str(o, "cameras"),
            place = str(o, "place"),
            parkingSessionId = str(o, "parkingSessionId"),
            watched = o.optBoolean("watched", false),
            indexing = o.optBoolean("indexing", false),
        )
    }

    private inline fun <T> mapArray(arr: JSONArray?, map: (JSONObject) -> T?): List<T> {
        if (arr == null) return emptyList()
        val out = ArrayList<T>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            map(o)?.let(out::add)
        }
        return out
    }

    // JSON null must stay null: Android's optString maps it to the text "null".
    private fun str(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optString(key).takeIf { it.isNotEmpty() }
    }

    private fun num(o: JSONObject, key: String): Number? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        return when (v) {
            is Number -> v
            is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun request(url: String): Request {
        val b = Request.Builder().url(url).get()
        cookie()?.let { b.header("Cookie", it) }
        return b.build()
    }

    private fun cookie(): String? = try {
        com.overdrive.app.util.DaemonHttpClient.sessionCookie()
    } catch (_: Throwable) {
        null
    }

    private fun httpGet(url: String): String? {
        http.newCall(request(url)).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code != 404) Log.w(TAG, "GET ${url.substringBefore('?')} -> HTTP ${resp.code}")
                return null
            }
            val body = resp.body ?: return null
            if (body.contentLength() > MAX_BODY_BYTES) return null
            return body.string()
        }
    }

    private fun enc(s: String): String =
        try {
            URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        } catch (_: Throwable) {
            s
        }
}
