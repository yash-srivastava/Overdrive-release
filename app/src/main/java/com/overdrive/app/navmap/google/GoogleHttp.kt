package com.overdrive.app.navmap.google

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.overdrive.app.navmap.nav.MapNetworking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Key injection, budget gating and response accounting for every Google Maps Platform request. */
object GoogleHttp {

    const val TILE_HOST = "tile.googleapis.com"
    const val ROUTES_HOST = "routes.googleapis.com"
    const val PLACES_HOST = "places.googleapis.com"
    const val GEOCODE_HOST = "maps.googleapis.com"
    const val HEADER_LOCAL_BUDGET = "X-OverDrive-Budget"

    private const val HEADER_WARMUP = "X-OverDrive-Warmup"
    /** Set by GoogleRoutesClient when a computeRoutes call asks for congestion (Enterprise tier). */
    const val HEADER_SKU = "X-OverDrive-Sku"
    private const val HEADER_API_KEY = "X-Goog-Api-Key"
    private const val HEADER_ANDROID_PACKAGE = "X-Android-Package"
    private const val HEADER_ANDROID_CERT = "X-Android-Cert"
    private const val WARM_INTERVAL_MS = 4 * 60_000L
    private const val ERROR_PEEK_BYTES = 4096L
    private const val TRAFFIC_SNIFF_BYTES = 32L
    private const val TRAFFIC_SNIFF_TILES = 3
    private const val MAX_TRAFFIC_SESSIONS = 32

    private val GOOGLE_HOSTS = setOf(TILE_HOST, ROUTES_HOST, PLACES_HOST, GEOCODE_HOST)
    private val PLACE_DETAILS_PATH = Regex("^/v1/places/[^/:]+$")
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val HEX = "0123456789ABCDEF".toCharArray()

    /** [failingSinceMs]: when the current run of failures began (0 while the last tile request succeeded). */
    data class TileHealth(
        val consecutiveFailures: Int, val lastSuccessMs: Long, val lastFailureMs: Long, val failingSinceMs: Long = 0L
    )

    private val tileFailures = AtomicInteger(0)
    @Volatile private var tileLastSuccessMs = 0L
    @Volatile private var tileLastFailureMs = 0L
    @Volatile private var tileFailingSinceMs = 0L

    private val trafficSessions = ConcurrentHashMap<String, AtomicInteger>()
    private val trafficUnsupported = AtomicBoolean(false)

    private val identityLock = Any()
    @Volatile private var identityResolved = false
    @Volatile private var identity: Pair<String, String>? = null

    private val lastWarmMs = HashMap<String, Long>() // warm-up executor thread only

    private val INTERCEPTOR = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = interceptGoogle(chain)
    }

    private val sharedClient: OkHttpClient by lazy {
        MapNetworking.builder()
            .addInterceptor(INTERCEPTOR)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val warmExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "GoogleWarmUp").apply { isDaemon = true } }
    }

    @JvmStatic
    fun interceptor(): Interceptor = INTERCEPTOR

    @JvmStatic
    fun client(): OkHttpClient = sharedClient

    @JvmStatic
    fun apiFor(url: HttpUrl): GoogleApi? = when (url.host) {
        TILE_HOST -> GoogleApi.TILES
        ROUTES_HOST -> GoogleApi.ROUTES
        PLACES_HOST -> GoogleApi.PLACES
        GEOCODE_HOST -> GoogleApi.GEOCODE
        else -> null
    }

    @JvmStatic
    fun skuFor(url: HttpUrl, method: String): GoogleSku? {
        // Decoded path so a percent-encoded ':' cannot slip a billable call past the budget.
        val path = url.pathSegments.joinToString("/", prefix = "/")
        return when (url.host) {
            TILE_HOST -> if (path.startsWith("/v1/2dtiles/")) GoogleSku.TILES else null
            ROUTES_HOST -> if (path.contains(":computeRoutes")) GoogleSku.ROUTES else null
            PLACES_HOST -> when {
                path.endsWith(":autocomplete") -> GoogleSku.AUTOCOMPLETE
                path.endsWith(":searchText") -> GoogleSku.TEXT_SEARCH
                method.equals("GET", ignoreCase = true) && PLACE_DETAILS_PATH.matches(path) ->
                    GoogleSku.PLACE_DETAILS
                else -> null
            }
            GEOCODE_HOST -> if (path.startsWith("/maps/api/geocode/")) GoogleSku.GEOCODE else null
            else -> null
        }
    }

    @JvmStatic
    fun warmUp(host: String) {
        val h = host.trim().lowercase(Locale.ROOT)
        if (h !in GOOGLE_HOSTS) return
        try {
            warmExecutor.execute { warmNow(h) }
        } catch (t: Throwable) {
            // Warm-up is an optimisation only.
        }
    }

    @JvmStatic
    fun isLocalBudgetRejection(resp: Response): Boolean = resp.header(HEADER_LOCAL_BUDGET) != null

    @JvmStatic
    fun tileHealth(): TileHealth = TileHealth(tileFailures.get(), tileLastSuccessMs, tileLastFailureMs, tileFailingSinceMs)

    @JvmStatic
    fun markTrafficSession(token: String) {
        if (token.isEmpty()) return
        if (trafficSessions.size >= MAX_TRAFFIC_SESSIONS) trafficSessions.clear()
        trafficSessions.putIfAbsent(token, AtomicInteger(0))
    }

    @JvmStatic
    fun trafficOverlayUnsupported(): Boolean = trafficUnsupported.get()

    // ── internals ───────────────────────────────────────────────────────────

    private fun interceptGoogle(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val api = apiFor(url) ?: return chain.proceed(request)
        if (request.header(HEADER_WARMUP) != null) {
            return chain.proceed(request.newBuilder().removeHeader(HEADER_WARMUP).build())
        }
        val key = GoogleMapsConfig.cached().apiKey
        if (!GoogleMapsConfig.isUsableKey(key)) return localResponse(request, 401, "nokey")
        val baseSku = skuFor(url, request.method)
        val sku = if (baseSku == GoogleSku.ROUTES &&
            request.header(HEADER_SKU) == GoogleSku.ROUTES_TRAFFIC.wire) GoogleSku.ROUTES_TRAFFIC else baseSku
        if (sku != null && !GoogleMapsBudget.tryAcquire(sku)) return localResponse(request, 429, "exhausted")

        val builder = request.newBuilder().removeHeader(HEADER_SKU)
        if (api == GoogleApi.TILES || api == GoogleApi.GEOCODE) {
            builder.url(url.newBuilder().setQueryParameter("key", key).build())
        } else {
            builder.header(HEADER_API_KEY, key)
        }
        androidIdentity()?.let { (pkg, cert) ->
            builder.header(HEADER_ANDROID_PACKAGE, pkg).header(HEADER_ANDROID_CERT, cert)
        }

        val response = try {
            chain.proceed(builder.build())
        } catch (e: IOException) {
            // Cancelled tiles (panned out of view) are not failures.
            if (sku == GoogleSku.TILES && !chain.call().isCanceled()) recordTileFailure()
            throw e
        }
        account(api, sku, url, response)
        // Callers get the keyless request back: Response.toString() prints request.url.
        val out = response.newBuilder().request(request)
        // Tiles carry max-age=86400 beside an Expires equal to Date. MapLibre lets Expires win, so every cached
        // tile read as stale and, with must-revalidate, unusable until refetched; max-age is the rule (RFC 9111 5.3).
        // (Traffic overlay tiles keep theirs: they refresh on their own short cycle.)
        if (api == GoogleApi.TILES && response.header("Expires") != null &&
            response.header("Cache-Control")?.contains("max-age", ignoreCase = true) == true &&
            url.queryParameter("session")?.let { trafficSessions.containsKey(it) } != true) {
            out.removeHeader("Expires")
        }
        return out.build()
    }

    private fun account(api: GoogleApi, sku: GoogleSku?, url: HttpUrl, response: Response) {
        try {
            val code = response.code
            val errorBody = if (code >= 400) {
                try {
                    response.peekBody(ERROR_PEEK_BYTES).string()
                } catch (e: IOException) {
                    null
                }
            } else {
                null
            }
            GoogleMapsBudget.onHttpResult(api, code, errorBody)
            if (sku == GoogleSku.TILES) {
                if (code in 200..299 || code == 304) recordTileSuccess() else if (code >= 500) recordTileFailure()
                if (code == 200) sniffTraffic(url, response)
            }
        } catch (t: Throwable) {
            // Accounting never fails the request.
        }
    }

    private fun sniffTraffic(url: HttpUrl, response: Response) {
        if (trafficUnsupported.get()) return
        val token = url.queryParameter("session") ?: return
        val checked = trafficSessions[token] ?: return
        if (checked.incrementAndGet() > TRAFFIC_SNIFF_TILES) return
        val head = try {
            response.peekBody(TRAFFIC_SNIFF_BYTES).bytes()
        } catch (e: IOException) {
            return
        }
        if (isOpaqueImage(head) && trafficUnsupported.compareAndSet(false, true)) {
            GoogleMapsBudget.fireChanged()
        }
    }

    /** JPEG, or PNG with colour type 0 (grey) / 2 (RGB): no alpha, so it would hide the basemap. */
    private fun isOpaqueImage(b: ByteArray): Boolean {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) return true
        if (b.size < 26) return false
        for (i in PNG_SIGNATURE.indices) if (b[i] != PNG_SIGNATURE[i]) return false
        val colorType = b[25].toInt() and 0xFF
        return colorType == 0 || colorType == 2
    }

    private fun recordTileSuccess() {
        tileFailures.set(0)
        tileFailingSinceMs = 0L
        tileLastSuccessMs = System.currentTimeMillis()
    }

    private fun recordTileFailure() {
        val now = System.currentTimeMillis()
        // (also when a success raced in between and left the count up with no start)
        if (tileFailures.getAndIncrement() == 0 || tileFailingSinceMs == 0L) tileFailingSinceMs = now
        tileLastFailureMs = now
    }

    private fun localResponse(request: Request, code: Int, tag: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 401) "Google key not configured" else "Google budget exhausted")
            .header(HEADER_LOCAL_BUDGET, tag)
            .body("".toResponseBody(null))
            .build()

    private fun warmNow(host: String) {
        val now = SystemClock.elapsedRealtime()
        val last = lastWarmMs[host]
        if (last != null && now - last < WARM_INTERVAL_MS) return
        // No Google traffic at all unless the user configured Google (I1).
        val settings = GoogleMapsConfig.cached()
        if (!settings.enabled || !settings.hasKey) return
        lastWarmMs[host] = now
        try {
            val req = Request.Builder().url("https://$host/").head().header(HEADER_WARMUP, "1").build()
            sharedClient.newCall(req).execute().close()
        } catch (t: Throwable) {
            // Best-effort.
        }
    }

    /** Package name + uppercase SHA-1 of the signing cert, for Android-restricted keys; null when unknown. */
    private fun androidIdentity(): Pair<String, String>? {
        if (identityResolved) return identity
        val ctx = GoogleMapsBudget.appContext() ?: return null
        synchronized(identityLock) {
            if (!identityResolved) {
                identity = try {
                    resolveIdentity(ctx)
                } catch (t: Throwable) {
                    null
                }
                identityResolved = true
            }
        }
        return identity
    }

    @Suppress("DEPRECATION")
    private fun resolveIdentity(ctx: Context): Pair<String, String>? {
        val pkg = ctx.packageName ?: return null
        val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return pkg to sb.toString()
    }
}
