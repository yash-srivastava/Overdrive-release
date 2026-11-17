package com.overdrive.app.navmap.nav

import com.overdrive.app.mqtt.ProxyHelper
import com.overdrive.app.server.LocaleManager
import okhttp3.OkHttpClient

/**
 * Shared networking policy for every RoadSense-map HTTP client and for the
 * MapLibre tile fetcher.
 *
 * <p>Two cross-cutting concerns are centralised here so they're applied
 * consistently and can't drift between clients:
 *
 * <ul>
 *   <li><b>Proxy-aware</b> — when the BYD head unit routes the internet through
 *       sing-box / Tailscale, public-internet calls (tiles, routing, geocoding,
 *       POIs) must go through it or they silently time out on bad mobile data.
 *       {@link ProxyHelper#getHttpProxy()} returns the live proxy or
 *       {@code NO_PROXY}. We attach it via OkHttp's per-call {@code proxySelector}
 *       so the choice is RE-EVALUATED every request (the proxy can come and go
 *       mid-session as sing-box/Tailscale start/stop), not frozen at client
 *       build time. The daemon-loopback client ({@code 127.0.0.1:8080}) must
 *       NEVER be proxied — it does not use this helper.</li>
 *   <li><b>Language-aware</b> — place search + routing should return results in
 *       the user's chosen app language. {@link LocaleManager#get()} is the single
 *       app-wide source of truth (a supported BCP-47 tag, resolving "auto" to the
 *       system locale). Exposed as {@link #lang} (the bare language subtag, e.g.
 *       "de" — what Photon/Nominatim/Valhalla expect) and {@link #acceptLanguage}
 *       (the full tag for the HTTP header).</li>
 * </ul>
 *
 * <p>All public-internet map clients build their {@link OkHttpClient} from
 * {@link #builder()} so they inherit the proxy selector + a descriptive
 * User-Agent (required by the OSM usage policy) uniformly.
 */
object MapNetworking {

    /** Required by OSM usage policy (identifies the client to public OSM services). */
    const val USER_AGENT = "OverDrive/1.0 (RoadSense navigation)"

    /**
     * A [okhttp3.ProxySelector] that defers to [ProxyHelper] on EVERY request, so
     * a proxy that appears or disappears mid-session is picked up without
     * rebuilding the client. Returns the sing-box/Tailscale proxy when available
     * — followed by a DIRECT fallback leg, because the loopback probe cannot
     * tell a general-egress proxy from the tailnet-only Tailscale SOCKS
     * listener (through which public tile/routing/geocoding hosts are
     * unreachable while the listener itself stays healthy). OkHttp falls
     * through to direct in the same call and remembers the dead leg.
     */
    private val dynamicProxySelector = object : java.net.ProxySelector() {
        override fun select(uri: java.net.URI?): MutableList<java.net.Proxy> =
            ProxyHelper.proxyRouteChain(ProxyHelper.getHttpProxy()).toMutableList()

        override fun connectFailed(
            uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?
        ) {
            // A failure through the proxy may mean it just died — let the next
            // probe re-evaluate instead of pinning a dead path.
            ProxyHelper.invalidateCache()
        }
    }

    /**
     * An [OkHttpClient.Builder] pre-wired with the dynamic proxy selector + a
     * User-Agent header interceptor. Callers set their own timeouts then build.
     * Use ONLY for public-internet calls — never for the daemon loopback.
     */
    fun builder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .proxySelector(dynamicProxySelector)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                )
            }

    /** Bare language subtag for query params (Photon `lang`, Valhalla `language`). */
    val lang: String
        get() = LocaleManager.get().substringBefore('-')

    /** Full supported tag for an `Accept-Language` header (e.g. "zh-CN"). */
    val acceptLanguage: String
        get() = LocaleManager.get()

    /**
     * Valhalla's directions language code. Valhalla expects a language tag from
     * its supported set; the bare subtag covers the common cases and Valhalla
     * falls back to English for anything it doesn't recognise, so this is safe.
     */
    val valhallaLanguage: String
        get() = lang

    // ── Distance units ──────────────────────────────────────────────────────
    // Reuse the SAME km/miles preference Trips uses (tripAnalytics.distanceUnit
    // in the unified config, "km" | "mi") so the map matches the rest of the app
    // rather than carrying its own setting.

    /** True when the user's distance unit is miles ("mi"); false ⇒ kilometres. */
    val useMiles: Boolean
        get() = try {
            com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                .optJSONObject("tripAnalytics")
                ?.optString("distanceUnit", "km") == "mi"
        } catch (_: Throwable) { false }

    /** Valhalla `directions_options.units`: "miles" or "kilometers". */
    val valhallaUnits: String
        get() = if (useMiles) "miles" else "kilometers"

    private const val M_PER_MILE = 1609.344
    private const val M_PER_FOOT = 0.3048

    /**
     * Format a distance in metres for display, honouring [useMiles]. Mirrors the
     * Gmaps/Waze cadence: feet/metres up close, then mi/km with one decimal.
     *   miles: <0.1 mi → whole feet; else "x.x mi"
     *   km:    <1 km   → whole metres; else "x.x km"
     */
    fun formatDistance(meters: Double): String {
        return if (useMiles) {
            val miles = meters / M_PER_MILE
            if (miles < 0.1) "${(meters / M_PER_FOOT).toInt()} ft"
            else String.format("%.1f mi", miles)
        } else {
            if (meters < 1000.0) "${meters.toInt()} m"
            else String.format("%.1f km", meters / 1000.0)
        }
    }

    /**
     * Format the LIVE distance-to-next-turn with consumer-nav BUCKETING, honouring
     * [useMiles]. Unlike [formatDistance] (literal, for trip totals), this rounds so
     * the banner reads stably ("Now", "50 m", "200 m") instead of exposing raw GPS
     * jitter as a flickering "237 m, 236 m, 241 m". Buckets:
     *   metric:   <10m "Now"; ≤50m → nearest 10m; <1km → nearest 50m; else "x.x km"
     *   imperial: <50ft "Now"; <0.1mi → nearest 50ft; else "x.x mi"
     */
    fun formatTurnDistance(meters: Double): String {
        if (useMiles) {
            val feet = meters / M_PER_FOOT
            return when {
                feet < 50 -> "Now"
                feet < 528 -> "${(Math.round(feet / 50.0) * 50).toInt()} ft"   // <0.1 mi
                else -> String.format("%.1f mi", meters / M_PER_MILE)
            }
        }
        return when {
            meters < 10 -> "Now"
            meters <= 50 -> "${(Math.round(meters / 10.0) * 10).toInt()} m"
            meters < 1000 -> "${(Math.round(meters / 50.0) * 50).toInt()} m"
            else -> String.format("%.1f km", meters / 1000.0)
        }
    }

    // One client for the process, so map opens reuse its connections. 20 calls per host as the SDK's own client
    // allows: OkHttp's default 5 queued visible tiles behind each other and behind offline-region downloads.
    private val tileClient: OkHttpClient by lazy {
        builder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 20 })
            .addInterceptor(VectorTileKeys.interceptor())
            .addInterceptor(com.overdrive.app.navmap.google.GoogleHttp.interceptor())
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Route MapLibre's OWN tile/style/glyph/sprite fetches through the proxy too.
     *
     * <p>MapLibre Native has its own internal OkHttp call factory; without this
     * it ignores the per-client proxy the route/search clients use, so on a
     * proxied head unit the basemap tiles would fail to load while search/routing
     * worked. {@link org.maplibre.android.module.http.HttpRequestUtil#setOkHttpClient}
     * swaps in our proxy-aware client (built once with generous tile timeouts).
     * Call AFTER {@code MapLibre.getInstance(...)} and before the first style load.
     * Idempotent — safe to call on every map open.
     */
    fun installMapLibreHttpClient() {
        try {
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(tileClient)
        } catch (_: Throwable) {
            // Best-effort: if the MapLibre http module isn't present/changes, fall
            // back to MapLibre's default client (direct) rather than crash the map.
        }
    }
}
