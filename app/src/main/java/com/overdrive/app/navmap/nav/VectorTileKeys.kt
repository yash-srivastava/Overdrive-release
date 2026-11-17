package com.overdrive.app.navmap.nav

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Keeps the vector basemap's cache across OpenFreeMap's weekly data builds.
 *
 * Its TileJSON names the build in every tile URL, so each new build used to miss every cached tile and every offline
 * region. The TileJSON handed to MapLibre carries one build-free URL instead, and tile requests for it are sent to
 * the current build; those tiles are cached for [TILE_MAX_AGE_S] and then checked again.
 */
object VectorTileKeys {

    const val HOST = "tiles.openfreemap.org"
    /** The path segment standing in for the build. */
    const val STABLE = "stable"

    private const val TILESET = "planet"
    private const val TILESET_URL = "https://$HOST/$TILESET"
    private const val TILE_MAX_AGE_S = 30L * 24 * 3600
    private const val MAX_TILEJSON_BYTES = 512L * 1024
    private const val LEARN_RETRY_MS = 30_000L
    private const val RELEARN_MS = 10 * 60_000L
    private const val PREFS = "navmap_tiles"
    private const val KEY_BUILD = "ofmBuild"

    private val BUILD = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val TEMPLATE = Regex("^https://tiles\\.openfreemap\\.org/planet/([^/]+)/\\{z\\}/\\{x\\}/\\{y\\}\\.pbf$")
    private const val STABLE_TEMPLATE = "https://$HOST/$TILESET/$STABLE/{z}/{x}/{y}.pbf"

    @Volatile private var build: String? = null
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var learnFailedAtMs = 0L
    @Volatile private var learnedAtMs = 0L
    private val learnLock = Any()

    private val INTERCEPTOR = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = handle(chain)
    }

    @JvmStatic
    fun interceptor(): Interceptor = INTERCEPTOR

    /** Remembers the build across restarts; without it the first tile of a session asks for the TileJSON. */
    @JvmStatic
    fun attach(context: Context) {
        try {
            val p = prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
            if (build == null) build = p.getString(KEY_BUILD, null)?.takeIf { BUILD.matches(it) }
        } catch (_: Throwable) {
            // Memory only.
        }
    }

    private fun handle(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        if (url.host != HOST || req.method != "GET") return chain.proceed(req)
        val segs = url.pathSegments
        if (segs.size == 1 && segs[0] == TILESET) return tileJson(chain, req)
        if (segs.size == 5 && segs[0] == TILESET && segs[1] == STABLE) return tile(chain, req)
        return chain.proceed(req)
    }

    private fun tileJson(chain: Interceptor.Chain, req: Request): Response {
        // Unconditional: a 304 would keep a cached TileJSON that still names a build.
        val resp = chain.proceed(req.newBuilder().removeHeader("If-None-Match").removeHeader("If-Modified-Since").build())
        if (resp.code != 200 || resp.header("Content-Encoding") != null) return resp
        val raw = try {
            resp.peekBody(MAX_TILEJSON_BYTES + 1).bytes()
        } catch (e: IOException) {
            return resp
        }
        if (raw.size > MAX_TILEJSON_BYTES) return resp
        val rewritten = rewrite(raw) ?: return resp
        val type = resp.body?.contentType()
        resp.body?.close()
        return resp.newBuilder()
            .removeHeader("Content-Length")
            .body(rewritten.toResponseBody(type))
            .build()
    }

    /** The TileJSON with its build-specific tile URLs replaced by the stable one; null when it doesn't have that shape. */
    private fun rewrite(raw: ByteArray): ByteArray? = try {
        val o = JSONObject(String(raw, Charsets.UTF_8))
        val found = buildOf(o)
        if (found == null) {
            null
        } else {
            learned(found)
            o.put("tiles", JSONArray().put(STABLE_TEMPLATE)).toString().toByteArray(Charsets.UTF_8)
        }
    } catch (_: Throwable) {
        null
    }

    // Every tile URL naming the same build, else null.
    private fun buildOf(o: JSONObject): String? {
        val tiles = o.optJSONArray("tiles") ?: return null
        if (tiles.length() == 0) return null
        var found: String? = null
        for (i in 0 until tiles.length()) {
            val b = TEMPLATE.find(tiles.optString(i, ""))?.groupValues?.get(1) ?: return null
            if (!BUILD.matches(b) || b == STABLE || (found != null && found != b)) return null
            found = b
        }
        return found
    }

    private fun tile(chain: Interceptor.Chain, req: Request): Response {
        // With no build known the stable path itself is asked for: the server answers an unknown build with the
        // current data (checked 2026-11-02), just not from its long-lived cache.
        val b = build ?: learn(chain, null) ?: return cached(chain.proceed(req), req)
        val resp = chain.proceed(onBuild(req, b))
        if (resp.code != 404 && resp.code != 410) return cached(resp, req)
        // Maybe a build the server no longer has: ask for the current one (the call can't run another request
        // while this answer is open, so it is read into memory first).
        val missing = detach(resp)
        val fresh = learn(chain, b)
        if (fresh == null || fresh == b) return missing.newBuilder().request(req).build()
        return cached(chain.proceed(onBuild(req, fresh)), req)
    }

    private fun detach(resp: Response): Response {
        val bytes = try {
            resp.peekBody(MAX_TILEJSON_BYTES).bytes()
        } catch (_: IOException) {
            ByteArray(0)
        }
        val type = resp.body?.contentType()
        resp.close()
        return resp.newBuilder().removeHeader("Content-Length").body(bytes.toResponseBody(type)).build()
    }

    private fun onBuild(req: Request, b: String): Request =
        req.newBuilder().url(req.url.newBuilder().setPathSegment(1, b).build()).build()

    // The server's 10-year lifetime belongs to one build's URL; under the stable key a tile is checked again monthly.
    private fun cached(resp: Response, req: Request): Response {
        val out = resp.newBuilder().request(req)
        if (resp.code == 200 || resp.code == 304) {
            out.removeHeader("Expires").header("Cache-Control", "public, max-age=$TILE_MAX_AGE_S")
        }
        return out.build()
    }

    /** The current build, read from the TileJSON when none is known or [stale] is the one known (not just confirmed). */
    private fun learn(chain: Interceptor.Chain, stale: String?): String? = synchronized(learnLock) { learnLocked(chain, stale) }

    private fun learnLocked(chain: Interceptor.Chain, stale: String?): String? {
        val known = build
        if (known != null && known != stale) return known
        val now = SystemClock.elapsedRealtime()
        if (learnFailedAtMs != 0L && now - learnFailedAtMs < LEARN_RETRY_MS) return known
        // (a tile that is simply missing must not fetch the TileJSON each time)
        if (known != null && learnedAtMs != 0L && now - learnedAtMs < RELEARN_MS) return known
        try {
            val r = chain.proceed(
                Request.Builder().url(TILESET_URL).header("User-Agent", MapNetworking.USER_AGENT).get().build()
            )
            r.use {
                val found = if (it.code == 200 && it.header("Content-Encoding") == null) {
                    val raw = it.peekBody(MAX_TILEJSON_BYTES + 1).bytes()
                    if (raw.size > MAX_TILEJSON_BYTES) null else buildOf(JSONObject(String(raw, Charsets.UTF_8)))
                } else {
                    null
                }
                if (found == null) {
                    learnFailedAtMs = now
                    return known
                }
                learnFailedAtMs = 0L
                learned(found)
                return found
            }
        } catch (_: Throwable) {
            learnFailedAtMs = now
            return known
        }
    }

    private fun learned(b: String) {
        learnedAtMs = SystemClock.elapsedRealtime()
        if (b == build) return
        build = b
        try {
            prefs?.edit()?.putString(KEY_BUILD, b)?.apply()
        } catch (_: Throwable) {
            // Memory only.
        }
    }
}
