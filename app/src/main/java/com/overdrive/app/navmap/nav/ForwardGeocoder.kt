package com.overdrive.app.navmap.nav

import android.util.Log
import com.overdrive.app.navmap.NavMapConfig
import com.overdrive.app.navmap.google.GooglePlacesClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Forward geocoder (free-text place name -> coordinates) for the RoadSense map
 * search box.
 *
 * <p>Resolution order, best-coverage-first, each step degrading gracefully to
 * the next so the search box NEVER gets worse than today:
 *
 * <ol>
 *   <li><b>Smart input (offline, no network)</b> — [CoordinateInputParser]
 *       resolves raw `lat,lng`, Plus Codes, and pasted map-share URLs directly.
 *       This is the "reach-any-place" path: it works even for places no geocoder
 *       has, because the user already supplied the coordinate. On submit
 *       ([search]) it ALSO expands shortened Google links (`maps.app.goo.gl`,
 *       `goo.gl/maps`) by following the redirect over the proxy-aware client.</li>
 *   <li><b>Stadia Maps geocoding (BYOK)</b> — when the user's routing endpoint is
 *       a Stadia host and a routing key is configured (the common case, since the
 *       default routing provider IS Stadia), the SAME key authenticates Stadia's
 *       Pelias geocoder. Better addressing / ranking / typo-tolerance than the
 *       public OSM services, at zero extra config. See [NavMapConfig].</li>
 *   <li><b>Photon</b> (Komoot, OSM-backed, free, autocomplete-friendly).</li>
 *   <li><b>Nominatim</b> (submit only — public policy forbids per-keystroke).</li>
 * </ol>
 *
 * <p>All network providers reach public OSM/Stadia services over the open
 * internet via [MapNetworking] so the calls are PROXY-AWARE (sing-box /
 * Tailscale) and LANGUAGE-AWARE (results in the user's chosen app language). Per
 * OSM usage policy a descriptive `User-Agent` is sent (added by
 * [MapNetworking.builder]).
 *
 * <p>Semantics mirror [com.overdrive.app.navmap.RoadSenseHazardApiClient]:
 * lazy OkHttp client, all methods SYNC (the Activity runs them off the UI
 * thread), and NEVER throwing — any failure returns an empty list so the
 * search box degrades gracefully.
 */
object ForwardGeocoder {

    private const val TAG = "ForwardGeocoder"

    /**
     * Why the LAST [search] returned no result, so the caller can show a SPECIFIC message
     * instead of a flat "No places found" (which is indistinguishable from a genuine
     * zero-hit query — the symptom that made a network/proxy failure read as "search never
     * works"). Mirrors [ValhallaRouteClient.lastError].
     *   NONE     — last search SUCCEEDED (≥1 result) / never run
     *   TIMEOUT  — a provider connect/read timed out (slow network or proxy hop)
     *   NETWORK  — other transport failure (DNS / connection reset / proxy down / blocked host)
     *   EMPTY    — every provider responded but none had a hit (a real zero-result query)
     * Best-effort + @Volatile (one foreground submit at a time, like ValhallaRouteClient).
     */
    enum class SearchError { NONE, TIMEOUT, NETWORK, EMPTY }

    @Volatile
    var lastError: SearchError = SearchError.NONE
        private set

    /** Set the transport error if NONE yet recorded for this search (don't downgrade a
     *  TIMEOUT to NETWORK or clobber an earlier provider's classification). */
    private fun noteTransportFailure(t: Throwable) {
        if (lastError == SearchError.NONE || lastError == SearchError.EMPTY) {
            lastError = if (t is java.net.SocketTimeoutException ||
                (t.message?.contains("timeout", ignoreCase = true) == true)
            ) SearchError.TIMEOUT else SearchError.NETWORK
        }
    }

    /** True once a TRANSPORT failure (not an HTTP non-2xx / clean-empty) has been recorded
     *  for the in-flight search — [search] short-circuits the remaining providers then, so a
     *  dead network path doesn't serialize three ~12 s provider waits (~36 s) before failing. */
    private fun hadTransportFailure(): Boolean =
        lastError == SearchError.TIMEOUT || lastError == SearchError.NETWORK

    private const val PHOTON_BASE = "https://photon.komoot.io/api/"
    private const val PHOTON_REVERSE_BASE = "https://photon.komoot.io/reverse"
    private const val NOMINATIM_BASE = "https://nominatim.openstreetmap.org/search"

    /** Stadia geocoding paths (autocomplete is v2; forward search is v1). */
    private const val STADIA_AUTOCOMPLETE_PATH = "/geocoding/v2/autocomplete"
    private const val STADIA_SEARCH_PATH = "/geocoding/v1/search"

    /** Hosts we recognise as Stadia (so a generic-BYOK endpoint elsewhere is NOT
     *  sent our key, and an EU customer keeps traffic on the EU host). */
    private val STADIA_HOSTS = setOf("api.stadiamaps.com", "api-eu.stadiamaps.com")

    /**
     * Photon focus-bias strength (0..1+). Photon already accepts a `lat`/`lon`
     * focus point but, without this, the bias is weak; ~0.4 meaningfully pulls
     * nearby matches up the ranking without drowning a strong far-away match.
     */
    private const val PHOTON_BIAS_SCALE = "0.4"

    /** Required by OSM usage policy (identifies the client). */
    private const val USER_AGENT = MapNetworking.USER_AGENT

    // Lazy so the OkHttpClient isn't built until search is first used. Proxy-aware
    // (dynamic proxy selector) + User-Agent come from MapNetworking.builder().
    //
    // Timeouts are sized for the PROXIED path (sing-box / Tailscale) on in-car mobile
    // data, NOT a clean direct connection. Measured on-device: Photon THROUGH the proxy
    // took ~2.4s even on good network — the old connect=4s/read=6s left almost no margin,
    // so a proxy hop on patchy mobile data tipped search into a timeout (empty results,
    // the "can't search routes while sing-box is on" symptom). Widened to connect=8s/
    // read=12s (still bounded so the search box doesn't hang indefinitely). connect
    // covers the proxy CONNECT handshake; read covers the upstream geocoder round-trip.
    // retryOnConnectionFailure stays OFF: search-on-submit, the caller degrades to an
    // empty list and the user can retry — we don't want a silent doubled latency.
    private val http: OkHttpClient by lazy {
        MapNetworking.builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            // Hard ceiling on the WHOLE call incl. redirects. The per-connect/read
            // timeouts bound ONE hop, but expandShortLink() follows OkHttp's default
            // redirect chain (up to ~20 hops) — without a callTimeout an adversarial /
            // slow chain could stack 20×(connect+read) and tie up the submit path for
            // minutes. Sized ABOVE one full slow-proxy hop (connect 8s + read 12s = 20s)
            // so it never clips a single valid-but-slow geocode/route GET through the
            // sing-box CONNECT handshake, while still bounding the redirect stack to a
            // couple of hops (a normal share-link expand is one or two fast 30x hops).
            .callTimeout(24, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Forward-geocode [query], returning up to [limit] results best-first.
     *
     * <p>Order: offline smart-input (coords / Plus Code / map URL, incl. short-link
     * expansion) → Stadia (if BYOK key on a Stadia endpoint) → Photon → Nominatim.
     * Each step falls through to the next on empty/error. Never throws — returns an
     * empty list on any failure or for a blank query.
     *
     * @param query the free-text place/address to search (e.g. "Eiffel Tower")
     * @param limit maximum number of results to return (default 5)
     * @param focusLat optional latitude (decimal degrees) to bias results
     *   toward (e.g. the current vehicle location); pass with [focusLng]
     * @param focusLng optional longitude (decimal degrees) focus bias
     * @return up to [limit] [SearchResult]s, or an empty list
     */
    fun search(
        query: String,
        limit: Int = 5,
        focusLat: Double? = null,
        focusLng: Double? = null
    ): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val n = if (limit < 1) 1 else limit
        // Reset the per-search error; providers set it via noteTransportFailure on a
        // transport throw. A clean all-empty walk finalizes to EMPTY at the end.
        lastError = SearchError.NONE

        // 1) Offline smart-input — exact coordinate the user already supplied.
        CoordinateInputParser.parse(q, focusLat, focusLng)?.let { return listOf(it) }
        // 1b) Submit-only: a shortened share link needs a redirect to reveal coords.
        if (looksLikeShortLink(q)) {
            expandShortLink(q)?.let { expanded ->
                CoordinateInputParser.parse(expanded, focusLat, focusLng)?.let { return listOf(it) }
            }
        }

        // 1c) Google Places text search (user's key); null falls through, transport failure fails fast.
        if (GooglePlacesClient.usable() &&
            com.overdrive.app.navmap.google.GoogleMapsBudget.hasRoom(com.overdrive.app.navmap.google.GoogleSku.TEXT_SEARCH)
        ) {
            GooglePlacesClient.textSearch(q, n, focusLat, focusLng)?.let {
                if (it.isEmpty()) lastError = SearchError.EMPTY
                return it
            }
            if (GooglePlacesClient.lastTransportFailure) {
                noteTransportFailure(GooglePlacesClient.lastTransportError ?: java.io.IOException("Google Places"))
                return emptyList()
            }
        }

        // 2) Stadia geocoding (reuses the routing BYOK key when the endpoint is Stadia).
        stadiaCreds()?.let { (base, key) ->
            val stadia = searchStadia(base, key, q, n, focusLat, focusLng)
            if (stadia.isNotEmpty()) return stadia
        }
        // FAST-FAIL: if Stadia hit a TRANSPORT failure (timeout/network — e.g. the proxy
        // can't reach the host), don't serialize Photon(≤12s)+Nominatim(≤12s) behind it —
        // the same network path will fail too. Surface the transport error now instead of
        // a ~36 s hang ending in "No places found". (An HTTP non-2xx / clean-empty from
        // Stadia does NOT set the flag, so a Stadia key/quota issue still falls through.)
        if (hadTransportFailure()) return emptyList()

        // 3) Photon, then 4) Nominatim — unchanged public-OSM fallbacks.
        val photon = searchPhoton(q, n, focusLat, focusLng)
        if (photon.isNotEmpty()) return photon
        if (hadTransportFailure()) return emptyList()

        val nominatim = searchNominatim(q, n)
        if (nominatim.isNotEmpty()) return nominatim

        // Every provider responded (or had a non-transport miss) but none had a hit, and no
        // transport failure was recorded → a genuine zero-result query. Mark EMPTY so the
        // caller shows "No places found" rather than a network message.
        if (!hadTransportFailure()) lastError = SearchError.EMPTY
        return emptyList()
    }

    /**
     * Type-ahead autocomplete for the search box. Offline smart-input first (so a
     * pasted coordinate / Plus Code / full map URL resolves with no network), then
     * Stadia autocomplete when configured, else Photon — **never Nominatim**
     * (Photon is purpose-built for "search as you type"; the public Nominatim
     * instance forbids per-keystroke querying). The Activity calls this on a
     * debounce (~300ms) with in-flight cancellation; this method stays SYNC +
     * never-throws like the rest. Short-link expansion is NOT done here (it needs
     * a network round-trip per keystroke) — that happens on submit in [search].
     *
     * @param query partial text typed so far
     * @param limit max suggestions (default 6 — a Gmap-style short list)
     * @param focusLat/[focusLng] optional location bias (current vehicle position)
     */
    fun autocomplete(
        query: String,
        limit: Int = 6,
        focusLat: Double? = null,
        focusLng: Double? = null
    ): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val n = if (limit < 1) 1 else limit

        CoordinateInputParser.parse(q, focusLat, focusLng)?.let { return listOf(it) }
        if (GooglePlacesClient.usable()) GooglePlacesClient.autocomplete(q, n, focusLat, focusLng)?.let { return it }

        stadiaCreds()?.let { (base, key) ->
            val stadia = stadiaAutocomplete(base, key, q, n, focusLat, focusLng)
            if (stadia.isNotEmpty()) return stadia
        }
        return searchPhoton(q, n, focusLat, focusLng)
    }

    /**
     * [result] with coordinates; Google predictions go through Place Details, then the free
     * geocoders on the prediction's text when Details is unavailable. Null when unresolvable.
     */
    fun resolve(result: SearchResult): SearchResult? {
        if (!result.needsResolve) return result
        GooglePlacesClient.details(result)?.let { return it }
        val q = listOfNotNull(result.label.takeIf { it.isNotBlank() }, result.secondary?.takeIf { it.isNotBlank() })
            .joinToString(", ")
        if (q.isEmpty()) return null
        val hit = freeLookup(q) ?: return null
        return SearchResult(result.label, hit.lat, hit.lng, secondary = result.secondary ?: hit.label)
    }

    /** First hit from the keyless/Stadia chain (no Google), for resolving a prediction without Details. */
    private fun freeLookup(q: String): SearchResult? {
        stadiaCreds()?.let { (base, key) ->
            searchStadia(base, key, q, 1, null, null).firstOrNull()?.let { return it }
        }
        searchPhoton(q, 1, null, null).firstOrNull()?.let { return it }
        return searchNominatim(q, 1).firstOrNull()
    }

    /** True while Google Places backs search (activity uses the shorter debounce). */
    fun googleActive(): Boolean = GooglePlacesClient.usable()

    /** Pre-opens the TLS connection to the Google search host; no-op without Google. */
    fun warmUp() = GooglePlacesClient.warmUp()

    /** Ends the Places autocomplete session; the next keystroke starts a new one. */
    fun endSession() = GooglePlacesClient.resetSession()

    // ── Stadia Maps (BYOK Pelias geocoder) ───────────────────────────────────

    /**
     * Resolve the Stadia geocoding base + key from the routing BYOK config, or
     * null if not usable. Usable ONLY when a routing key is set AND the configured
     * routing endpoint is a recognised Stadia host — generic BYOK lets the user
     * point routing at any Valhalla provider, and we must not send their key to
     * Stadia geocoding (it wouldn't work, and shouldn't be sent) unless it IS a
     * Stadia account. EU customers keep their host (api-eu.stadiamaps.com).
     */
    private fun stadiaCreds(): Pair<String, String>? {
        return try {
            val cfg = NavMapConfig.fromUnifiedConfig()
            val key = cfg.routingApiKey
            if (key.isEmpty()) return null
            // URI(...).host is null for a scheme-less endpoint ("api.stadiamaps.com/..."),
            // which a user may paste. Re-parse with a "//" authority prefix in that case so
            // a scheme-less Stadia endpoint still resolves its host (and Stadia geocoding
            // still activates) instead of silently falling through to Photon.
            val ep = cfg.routingEndpoint
            val host = (URI(ep).host ?: URI("//" + ep).host)?.lowercase(Locale.US) ?: return null
            if (host !in STADIA_HOSTS) return null
            Pair("https://$host", key)
        } catch (_: Throwable) {
            null
        }
    }

    /** Stadia forward search (`/geocoding/v1/search`). Never throws. */
    internal fun searchStadia(
        base: String, key: String, query: String, limit: Int,
        focusLat: Double?, focusLng: Double?
    ): List<SearchResult> = stadiaGet(
        buildStadiaUrl(base + STADIA_SEARCH_PATH, key, query, limit, focusLat, focusLng), "search"
    )

    /** Stadia autocomplete (`/geocoding/v2/autocomplete`). Never throws. */
    internal fun stadiaAutocomplete(
        base: String, key: String, query: String, limit: Int,
        focusLat: Double?, focusLng: Double?
    ): List<SearchResult> = stadiaGet(
        buildStadiaUrl(base + STADIA_AUTOCOMPLETE_PATH, key, query, limit, focusLat, focusLng), "autocomplete"
    )

    /**
     * Build a Stadia geocoding URL. Stadia/Pelias param names DIFFER from Photon:
     * `text` (not `q`), `focus.point.lat`/`focus.point.lon` (not `lat`/`lon`),
     * `size` (not `limit`), `lang`. Pure function — exposed for unit testing.
     */
    internal fun buildStadiaUrl(
        endpoint: String, key: String, query: String, limit: Int,
        focusLat: Double?, focusLng: Double?
    ): String {
        val sb = StringBuilder(endpoint)
            .append("?text=").append(enc(query))
            .append("&size=").append(if (limit < 1) 1 else limit)
            .append("&lang=").append(enc(MapNetworking.lang))
        if (focusLat != null && focusLng != null) {
            sb.append("&focus.point.lat=").append(focusLat)
                .append("&focus.point.lon=").append(focusLng)
        }
        sb.append("&api_key=").append(enc(key))
        return sb.toString()
    }

    /** Execute a Stadia GeoJSON GET and parse it (same FeatureCollection shape as Photon). */
    private fun stadiaGet(url: String, tag: String): List<SearchResult> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", MapNetworking.acceptLanguage)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 401/403 = wrong/expired key, 429 = quota — just fall through to
                    // Photon; the routing path already surfaces a key problem to the user.
                    Log.w(TAG, "Stadia $tag -> HTTP ${resp.code}")
                    return emptyList()
                }
                val bodyStr = resp.body?.string() ?: return emptyList()
                parsePhoton(bodyStr)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Stadia $tag failed: ${t.message}")
            noteTransportFailure(t)
            emptyList()
        }
    }

    // ── Short-link expansion (submit only) ───────────────────────────────────

    /** True for shortened Google Maps links that hide their coordinates behind a redirect. */
    internal fun looksLikeShortLink(s: String): Boolean {
        val l = s.lowercase(Locale.US)
        return l.contains("maps.app.goo.gl") || l.contains("goo.gl/maps")
    }

    /**
     * Follow a shortened share link's redirect(s) and return the FINAL expanded
     * URL (which carries the real `@lat,lng` / `!3d!4d` coordinates), or null. Uses
     * the proxy-aware client so it works behind sing-box/Tailscale. OkHttp follows
     * redirects by default, so the final [okhttp3.Response.request] URL is the
     * destination; we don't need the body. Never throws.
     */
    private fun expandShortLink(shortUrl: String): String? {
        return try {
            val req = Request.Builder()
                .url(shortUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                // The final request URL after following redirects is the expanded link.
                resp.request.url.toString()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "expandShortLink failed: ${t.message}")
            null
        }
    }

    /**
     * Query Photon directly. Exposed for callers that want to skip the
     * Nominatim fallback. Never throws — returns an empty list on failure.
     *
     * @param query free-text place/address
     * @param limit max results
     * @param focusLat optional latitude (decimal degrees) focus bias
     * @param focusLng optional longitude (decimal degrees) focus bias
     */
    fun searchPhoton(
        query: String,
        limit: Int = 5,
        focusLat: Double? = null,
        focusLng: Double? = null
    ): List<SearchResult> {
        return try {
            val sb = StringBuilder(PHOTON_BASE)
                .append("?q=").append(enc(query))
                .append("&limit=").append(limit)
                // Language-aware: return place names in the user's app language.
                .append("&lang=").append(enc(MapNetworking.lang))
            if (focusLat != null && focusLng != null) {
                sb.append("&lat=").append(focusLat).append("&lon=").append(focusLng)
                    // Strengthen the focus bias so nearby matches rank above distant
                    // same-name places (Photon's default bias is weak without this).
                    .append("&location_bias_scale=").append(PHOTON_BIAS_SCALE)
            }
            val req = Request.Builder()
                .url(sb.toString())
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", MapNetworking.acceptLanguage)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Photon -> HTTP ${resp.code}")
                    return emptyList()
                }
                val bodyStr = resp.body?.string() ?: return emptyList()
                parsePhoton(bodyStr)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "searchPhoton failed: ${t.message}")
            noteTransportFailure(t)
            emptyList()
        }
    }

    /**
     * Reverse-geocode a coordinate (lat/lng) to the nearest readable place label
     * via Photon's `/reverse` endpoint. Used by the map's "drop a pin" long-press
     * so a saved/navigated place gets a human name instead of bare coordinates.
     *
     * <p>SYNC + never-throws like the rest (the Activity runs it off the UI
     * thread). Returns null on any failure / no hit so the caller can fall back to
     * a generic "Dropped pin" label. The returned [SearchResult] carries the
     * TAPPED coordinate (not Photon's snapped one) so navigation routes to exactly
     * where the user pressed.
     *
     * @param lat latitude (decimal degrees) of the tapped point
     * @param lng longitude (decimal degrees) of the tapped point
     * @return the nearest place's label at the tapped coordinate, or null
     */
    fun reverse(lat: Double, lng: Double): SearchResult? {
        if (GooglePlacesClient.reverseUsable()) GooglePlacesClient.reverse(lat, lng)?.let { return it }
        return try {
            val url = StringBuilder(PHOTON_REVERSE_BASE)
                .append("?lat=").append(lat)
                .append("&lon=").append(lng)
                .append("&lang=").append(enc(MapNetworking.lang))
                .toString()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", MapNetworking.acceptLanguage)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Photon reverse -> HTTP ${resp.code}")
                    return null
                }
                val bodyStr = resp.body?.string() ?: return null
                // Reuse the forward parser (same FeatureCollection shape), then keep
                // only the label — pin the result at the TAPPED coordinate.
                val label = parsePhoton(bodyStr).firstOrNull()?.label ?: return null
                SearchResult(label, lat, lng)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "reverse failed: ${t.message}")
            null
        }
    }

    /**
     * Query Nominatim (fallback). Never throws — returns an empty list on
     * failure.
     *
     * @param query free-text place/address
     * @param limit max results
     */
    fun searchNominatim(query: String, limit: Int = 5): List<SearchResult> {
        return try {
            val url = StringBuilder(NOMINATIM_BASE)
                .append("?format=jsonv2")
                .append("&q=").append(enc(query))
                .append("&limit=").append(limit)
                // Language-aware: bias result names to the user's app language.
                .append("&accept-language=").append(enc(MapNetworking.acceptLanguage))
                .toString()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", MapNetworking.acceptLanguage)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Nominatim -> HTTP ${resp.code}")
                    return emptyList()
                }
                val bodyStr = resp.body?.string() ?: return emptyList()
                parseNominatim(bodyStr)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "searchNominatim failed: ${t.message}")
            noteTransportFailure(t)
            emptyList()
        }
    }

    /**
     * Parse a Photon/Stadia GeoJSON FeatureCollection. Each `feature.geometry`
     * is a Point with `coordinates = [lng, lat]`; `feature.properties` carries
     * name/street/city/state/country (Photon) or a ready-made `label` (Stadia/
     * Pelias) which we assemble into a readable label. Pure function — exposed
     * for unit testing.
     */
    internal fun parsePhoton(json: String): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        try {
            val features = JSONObject(json).optJSONArray("features") ?: return out
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val geom = feature.optJSONObject("geometry") ?: continue
                val coords = geom.optJSONArray("coordinates") ?: continue
                if (coords.length() < 2) continue
                val lng = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) continue

                val props = feature.optJSONObject("properties") ?: JSONObject()
                val label = buildPhotonLabel(props)
                if (label.isEmpty()) continue
                out.add(SearchResult(label, lat, lng))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parsePhoton failed: ${t.message}")
        }
        return out
    }

    /**
     * Build a readable label from feature `properties`. Stadia/Pelias supplies a
     * canonical pre-built `label` ("Eiffel Tower, Paris, France") — prefer it when
     * present. Otherwise (Photon has no `label`) assemble "name, city, country",
     * skipping blank/duplicate parts and falling back to street when there's no
     * name. Pure function — exposed for unit testing.
     */
    internal fun buildPhotonLabel(props: JSONObject): String {
        // Stadia/Pelias canonical display string — Photon never sets this, so this
        // branch only affects the Stadia path (and keeps Photon byte-identical).
        val ready = props.optString("label", "").trim()
        if (ready.isNotEmpty()) return ready

        val name = props.optString("name", "").trim()
        val street = props.optString("street", "").trim()
        val city = props.optString("city", "").trim()
        val state = props.optString("state", "").trim()
        val country = props.optString("country", "").trim()

        val parts = ArrayList<String>(4)
        val head = if (name.isNotEmpty()) name else street
        if (head.isNotEmpty()) parts.add(head)
        if (city.isNotEmpty() && city != head) parts.add(city)
        if (state.isNotEmpty() && state != city && state != head) parts.add(state)
        if (country.isNotEmpty()) parts.add(country)

        return parts.joinToString(", ")
    }

    /**
     * Parse a Nominatim `jsonv2` response (a JSON array). Each element has
     * `lat`, `lon` (strings) and `display_name`. Pure function — exposed for
     * unit testing.
     */
    internal fun parseNominatim(json: String): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                // Nominatim returns lat/lon as strings.
                val lat = item.optString("lat", "").toDoubleOrNull() ?: continue
                val lng = item.optString("lon", "").toDoubleOrNull() ?: continue
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) continue
                val label = item.optString("display_name", "").trim()
                if (label.isEmpty()) continue
                out.add(SearchResult(label, lat, lng))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parseNominatim failed: ${t.message}")
        }
        return out
    }

    private fun enc(s: String): String =
        try { URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }
}
