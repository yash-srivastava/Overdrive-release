package com.overdrive.app.navmap.google

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import com.overdrive.app.server.LocaleManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class GoogleMapType(val wire: String) { ROADMAP("roadmap"), SATELLITE("satellite"), HYBRID("hybrid"), TERRAIN("terrain") }

data class TileSessionSpec(
    val mapType: GoogleMapType, val trafficOnly: Boolean, val stylesJson: String?,
    val language: String, val region: String, val hiDpi: Boolean
)

data class TileSession(val token: String, val expiryMs: Long, val tileWidth: Int, val tileHeight: Int, val imageFormat: String)

/** Map Tiles API session tokens: created via `createSession` (free), persisted per spec, reused until 1 h before expiry. */
object GoogleTileSessions {

    private const val TAG = "GoogleTileSessions"
    private const val PREFS = "navmap_google_sessions"
    private const val CREATE_URL = "https://tile.googleapis.com/v1/createSession"
    private const val VIEWPORT_URL = "https://tile.googleapis.com/tile/v1/viewport"
    private const val TILE_URL_PREFIX = "https://tile.googleapis.com/v1/2dtiles/{z}/{x}/{y}?session="
    private const val MIN_VALIDITY_MS = 3_600_000L
    private const val CREATE_INTERVAL_MS = 60_000L
    private const val REGION_TTL_MS = 600_000L
    private const val VIEWPORT_WARN_INTERVAL_NS = 60_000_000_000L
    private const val HEX = "0123456789abcdef"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val lock = Any()
    private val memory = HashMap<String, TileSession>()          // guarded by lock
    private val lastCreateMs = ConcurrentHashMap<String, Long>()
    private val specLocks = ConcurrentHashMap<String, Any>()
    @Volatile private var lastViewportWarnNs = 0L
    private val suppressedViewportFailures = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var prefsRef: SharedPreferences? = null
    @Volatile private var regionCache: String? = null
    @Volatile private var regionAtMs = 0L

    @JvmStatic
    fun cached(spec: TileSessionSpec): TileSession? = valid(lookup(keyOf(spec)))

    @JvmStatic
    fun ensure(spec: TileSessionSpec): TileSession? {
        val key = keyOf(spec)
        valid(lookup(key))?.let { return it }
        if (Looper.getMainLooper().isCurrentThread) return null
        synchronized(specLocks.getOrPut(key) { Any() }) {
            valid(lookup(key))?.let { return it }
            val now = SystemClock.elapsedRealtime()
            val last = lastCreateMs[key]
            if (last != null && now - last < CREATE_INTERVAL_MS) return null
            lastCreateMs[key] = now
            val created = create(spec)
            if (created == null) {
                // An interrupted create (controller shut down) must not hold the next one back.
                if (Thread.currentThread().isInterrupted) lastCreateMs.remove(key)
                return null
            }
            store(key, created)
            return created
        }
    }

    @JvmStatic
    fun invalidate(spec: TileSessionSpec) {
        val key = keyOf(spec)
        synchronized(lock) {
            try { prefs()?.edit()?.remove(key)?.apply() } catch (_: Throwable) {}
            memory.remove(key)
        }
    }

    @JvmStatic
    fun tileUrlTemplate(session: TileSession): String =
        TILE_URL_PREFIX + URLEncoder.encode(session.token, "UTF-8")

    @JvmStatic
    fun fetchCopyright(session: TileSession, zoom: Int, north: Double, south: Double, east: Double, west: Double): String? {
        if (Looper.getMainLooper().isCurrentThread) return null
        if (north.isNaN() || south.isNaN() || east.isNaN() || west.isNaN()) return null
        // Ordered and clamped so the request is always well-formed (a rotated camera can report any order).
        val n = maxOf(north, south).coerceIn(-90.0, 90.0)
        val s = minOf(north, south).coerceIn(-90.0, 90.0)
        val url = VIEWPORT_URL.toHttpUrl().newBuilder()
            .addQueryParameter("session", session.token)
            .addQueryParameter("zoom", zoom.coerceIn(0, 22).toString())
            .addQueryParameter("north", coord(n))
            .addQueryParameter("south", coord(s))
            .addQueryParameter("east", coord(wrapLng(east)))
            .addQueryParameter("west", coord(wrapLng(west)))
            .build()
        return try {
            GoogleHttp.client().newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val copyright = JSONObject(body).optString("copyright", "").trim().takeIf { it.isNotEmpty() }
                if (copyright != null) {
                    val suppressed = suppressedViewportFailures.getAndSet(0)
                    if (suppressed > 0) {
                        Log.i(TAG, "viewport recovered ($suppressed failure(s) suppressed)")
                    }
                }
                copyright
            }
        } catch (t: Throwable) {
            logViewportFailureThrottled(t.javaClass.simpleName)
            null
        }
    }

    internal fun shouldEmitViewportWarning(nowNs: Long, lastWarnNs: Long): Boolean =
        lastWarnNs == 0L || nowNs - lastWarnNs >= VIEWPORT_WARN_INTERVAL_NS

    private fun logViewportFailureThrottled(reason: String) {
        val nowNs = System.nanoTime()
        if (shouldEmitViewportWarning(nowNs, lastViewportWarnNs)) {
            val suppressed = suppressedViewportFailures.getAndSet(0)
            lastViewportWarnNs = nowNs
            val suffix = if (suppressed > 0) " ($suppressed similar suppressed)" else ""
            Log.w(TAG, "viewport failed: $reason$suffix")
        } else {
            suppressedViewportFailures.incrementAndGet()
        }
    }

    @JvmStatic
    fun region(context: Context): String {
        val now = SystemClock.elapsedRealtime()
        regionCache?.let { if (now - regionAtMs < REGION_TTL_MS) return it }
        val tm = try { context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager } catch (_: Throwable) { null }
        val resolved = countryOrNull { tm?.networkCountryIso }
            ?: countryOrNull { tm?.simCountryIso }
            ?: countryOrNull { Locale.getDefault().country }
            ?: "US"
        regionCache = resolved
        regionAtMs = now
        return resolved
    }

    @JvmStatic
    fun language(): String =
        (try { LocaleManager.get() } catch (_: Throwable) { null })?.trim()?.takeIf { it.isNotEmpty() } ?: "en"

    private fun valid(s: TileSession?): TileSession? =
        s?.takeIf { it.expiryMs > System.currentTimeMillis() + MIN_VALIDITY_MS }

    private fun prefs(): SharedPreferences? {
        prefsRef?.let { return it }
        val ctx = GoogleMapsBudget.appContext() ?: return null
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefsRef = it }
    }

    private fun lookup(key: String): TileSession? = synchronized(lock) {
        memory[key] ?: (try { prefs()?.getString(key, null) } catch (_: Throwable) { null })
            ?.let { decode(it) }
            ?.also { memory[key] = it }
    }

    private fun store(key: String, session: TileSession) {
        synchronized(lock) {
            memory[key] = session
            val p = prefs() ?: return
            try {
                val now = System.currentTimeMillis()
                val edit = p.edit().putString(key, encode(session))
                // Drops expired or unreadable entries so old specs never accumulate.
                for ((k, v) in p.all) {
                    if (k == key) continue
                    val old = (v as? String)?.let { decode(it) }
                    if (old == null || old.expiryMs <= now) edit.remove(k)
                }
                edit.apply()
            } catch (t: Throwable) {
                Log.w(TAG, "persist failed: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun create(spec: TileSessionSpec): TileSession? {
        val body = try {
            requestBody(spec).toString()
        } catch (t: Throwable) {
            Log.w(TAG, "createSession body invalid: ${t.javaClass.simpleName}")
            return null
        }
        val req = Request.Builder().url(CREATE_URL).post(body.toRequestBody(JSON_TYPE)).build()
        return try {
            GoogleHttp.client().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "createSession HTTP ${resp.code}")
                    null
                } else {
                    parseSession(resp.body?.string())
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "createSession failed: ${t.javaClass.simpleName}")
            null
        }
    }

    private const val TRAFFIC_ONLY_STYLES =
        "[{\"elementType\":\"labels\",\"stylers\":[{\"visibility\":\"off\"}]}," +
            "{\"featureType\":\"poi\",\"stylers\":[{\"visibility\":\"off\"}]}," +
            "{\"featureType\":\"transit\",\"stylers\":[{\"visibility\":\"off\"}]}]"

    private fun requestBody(spec: TileSessionSpec): JSONObject {
        val o = JSONObject()
        if (spec.trafficOnly) {
            o.put("mapType", GoogleMapType.ROADMAP.wire)
            o.put("layerTypes", JSONArray().put("layerTraffic"))
            o.put("overlay", true)
            // Overlay tiles need alpha; JPEG would hide the basemap.
            o.put("imageFormat", "png")
            // Without this the overlay also carries Google's road labels and POI icons (seen on live tiles).
            o.put("styles", JSONArray(TRAFFIC_ONLY_STYLES))
        } else {
            when (spec.mapType) {
                GoogleMapType.HYBRID -> {
                    o.put("mapType", GoogleMapType.SATELLITE.wire)
                    o.put("layerTypes", JSONArray().put("layerRoadmap"))
                }
                GoogleMapType.TERRAIN -> {
                    o.put("mapType", GoogleMapType.TERRAIN.wire)
                    o.put("layerTypes", JSONArray().put("layerRoadmap"))
                }
                else -> o.put("mapType", spec.mapType.wire)
            }
        }
        o.put("language", spec.language)
        o.put("region", spec.region)
        o.put("scale", if (spec.hiDpi) "scaleFactor2x" else "scaleFactor1x")
        o.put("highDpi", spec.hiDpi)
        if (!spec.trafficOnly && spec.mapType == GoogleMapType.ROADMAP && spec.stylesJson != null) {
            o.put("styles", JSONArray(spec.stylesJson))
        }
        return o
    }

    private fun parseSession(body: String?): TileSession? {
        if (body.isNullOrBlank()) return null
        return try {
            val o = JSONObject(body)
            val token = o.optString("session", "").trim()
            val expirySec = when (val e = o.opt("expiry")) {
                is Number -> e.toLong()
                is String -> e.trim().toLongOrNull()
                else -> null
            }
            if (token.isEmpty() || expirySec == null || expirySec <= 0L) {
                Log.w(TAG, "createSession: unexpected response")
                null
            } else {
                TileSession(
                    token, expirySec * 1000L,
                    o.optInt("tileWidth", 256), o.optInt("tileHeight", 256), o.optString("imageFormat", "png")
                )
            }
        } catch (_: Throwable) {
            Log.w(TAG, "createSession: unparsable response")
            null
        }
    }

    private fun encode(s: TileSession): String =
        "${s.token}|${s.expiryMs}|${s.tileWidth}|${s.tileHeight}|${s.imageFormat}"

    private fun decode(raw: String): TileSession? {
        val parts = raw.split('|')
        val n = parts.size
        if (n < 5) return null
        val token = parts.subList(0, n - 4).joinToString("|").takeIf { it.isNotEmpty() } ?: return null
        val expiry = parts[n - 4].toLongOrNull() ?: return null
        return TileSession(token, expiry, parts[n - 3].toIntOrNull() ?: 256, parts[n - 2].toIntOrNull() ?: 256, parts[n - 1])
    }

    private fun keyOf(spec: TileSessionSpec): String {
        val canonical = listOf(
            if (spec.trafficOnly) "v2" else "v1", spec.mapType.wire, spec.trafficOnly.toString(), spec.language, spec.region,
            spec.hiDpi.toString(), spec.stylesJson ?: ""
        ).joinToString("\u001F")
        val digest = MessageDigest.getInstance("SHA-1").digest(canonical.toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    private inline fun countryOrNull(read: () -> String?): String? {
        val v = try { read() } catch (_: Throwable) { null } ?: return null
        return v.trim().uppercase(Locale.ROOT).takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
    }

    private fun coord(v: Double): String = String.format(Locale.ROOT, "%.6f", v)

    private fun wrapLng(v: Double): Double {
        val w = ((v + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return if (w == -180.0 && v > 0.0) 180.0 else w
    }
}
