package com.overdrive.app.navmap.nav

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Self-contained client for nearby points of interest (EV charging stations and
 * fuel/petrol stations) on the RoadSense map, backed by **free OpenStreetMap
 * data via the public Overpass API** ([overpass-api.de][1]).
 *
 * <p>No API key, no account, no per-request cost — Overpass serves OSM data
 * directly. In exchange the public instance is shared and rate-limited, so this
 * client is deliberately polite:
 * - It caps each query at ~50 results so a dense city bbox doesn't pull
 *   thousands of pumps/chargers (`out body 50`).
 * - [poisAlongRoute] pads the route bounding box by only ~0.02 deg
 *   (~2.2 km) so the query stays scoped to the corridor.
 * - A tiny in-memory cache (keyed by rounded bbox + kinds, short TTL) absorbs
 *   repeated calls for the same route. **The parent is still expected to cache
 *   per chosen route and NOT re-query on every map pan/zoom** — this cache is a
 *   last line of defense, not the primary one.
 *
 * <p>Coverage and naming quality depend entirely on OSM completeness in the
 * region: well-mapped areas return rich, named results; sparsely-mapped areas
 * may return few or unnamed POIs (hence [RoutePoi.name] is often empty).
 *
 * <p>Style mirrors [ForwardGeocoder] and [ValhallaRouteClient]: a lazy
 * [OkHttpClient] (built on first use), all methods SYNC (the Activity runs them
 * off the UI thread), and NEVER throwing — any failure (transport error, HTTP
 * error, malformed JSON, Overpass timeout) returns an empty list so the map
 * degrades gracefully. Per OSM/Overpass usage policy we send a descriptive
 * `User-Agent`.
 *
 * <p>Overpass can be slow under load, so the read timeout is generous (25s,
 * matching the `[timeout:25]` budget baked into the query); connect stays at 4s.
 *
 * [1]: https://overpass-api.de/api/interpreter
 */
object OverpassPoiClient {

    private const val TAG = "OverpassPoiClient"

    /** Public Overpass instance — free OSM data, no key required. */
    private const val OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"

    /** Required by OSM/Overpass usage policy (identifies the client). */
    private const val USER_AGENT = "OverDrive/1.0 (RoadSense navigation)"

    /** Max results pulled per query (keeps dense city bboxes bounded). */
    private const val RESULT_CAP = 50

    /** Padding (decimal degrees, ~2.2 km) added around a route's bbox. */
    private const val ROUTE_BBOX_PAD_DEG = 0.02

    /** Overpass server-side query budget, in seconds (also our read-timeout anchor). */
    private const val OVERPASS_TIMEOUT_S = 25

    /** Overpass expects the QL body as plain text. */
    private val PLAIN = "text/plain; charset=utf-8".toMediaType()

    // ---- tiny in-memory cache -------------------------------------------------

    /** How long a cached bbox result stays fresh. */
    private const val CACHE_TTL_MS = 60_000L

    /** Rounding applied to bbox edges when building a cache key (~110 m at the equator). */
    private const val CACHE_KEY_DEG = 0.001

    private data class CacheEntry(val tsMs: Long, val pois: List<RoutePoi>)

    private val cache = HashMap<String, CacheEntry>()
    private val cacheLock = Any()

    // Lazy so the OkHttpClient isn't built until POIs are first requested.
    // Proxy-aware via MapNetworking (Overpass is a public-internet endpoint).
    private val http: OkHttpClient by lazy {
        MapNetworking.builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            // Overpass can be slow; align with the server-side [timeout:25] budget.
            .readTimeout(OVERPASS_TIMEOUT_S.toLong(), TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Query OSM POIs of the requested [kinds] inside the given bounding box.
     *
     * <p>SYNC and NEVER throwing — returns an empty list on any failure, on an
     * empty [kinds] set, or on a degenerate bbox. Results are capped at ~50
     * (see [RESULT_CAP]). A short-TTL in-memory cache (keyed by rounded bbox +
     * kinds) short-circuits repeated identical calls.
     *
     * @param minLat south edge of the bbox (decimal degrees)
     * @param minLng west edge of the bbox (decimal degrees)
     * @param maxLat north edge of the bbox (decimal degrees)
     * @param maxLng east edge of the bbox (decimal degrees)
     * @param kinds which POI categories to fetch (e.g. both CHARGING and FUEL)
     * @return up to ~50 [RoutePoi]s within the bbox, or an empty list
     */
    fun poisNearBbox(
        minLat: Double,
        minLng: Double,
        maxLat: Double,
        maxLng: Double,
        kinds: Set<PoiKind>
    ): List<RoutePoi> {
        if (kinds.isEmpty()) return emptyList()
        // Normalize so a caller passing edges in either order still works.
        val south = minOf(minLat, maxLat)
        val north = maxOf(minLat, maxLat)
        val west = minOf(minLng, maxLng)
        val east = maxOf(minLng, maxLng)
        if (south == north || west == east) return emptyList()

        val key = cacheKey(south, west, north, east, kinds)
        cacheGet(key)?.let { return it }

        return try {
            val query = buildQuery(south, west, north, east, kinds)
            val req = Request.Builder()
                .url(OVERPASS_ENDPOINT)
                .header("User-Agent", USER_AGENT)
                .post(query.toRequestBody(PLAIN))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "POST overpass -> HTTP ${resp.code}")
                    return emptyList()
                }
                val bodyStr = resp.body?.string() ?: return emptyList()
                val pois = parseOverpass(bodyStr)
                cachePut(key, pois)
                pois
            }
        } catch (t: Throwable) {
            Log.w(TAG, "poisNearBbox failed: ${t.message}")
            emptyList()
        }
    }

    /**
     * Convenience: query POIs along a chosen route by computing the bounding box
     * of [points] (with a small ~0.02 deg padding) and delegating to
     * [poisNearBbox]. This is the entry point the map calls once a route is
     * picked.
     *
     * <p>SYNC and NEVER throwing — returns an empty list on an empty [points] or
     * [kinds] set, or on any downstream failure. Because this collapses a whole
     * route to ONE bbox query, the parent should call it once per chosen route
     * and cache the result, not on every pan.
     *
     * @param points the route polyline vertices (WGS-84)
     * @param kinds which POI categories to fetch
     * @return up to ~50 [RoutePoi]s near the route, or an empty list
     */
    fun poisAlongRoute(points: List<GeoPoint>, kinds: Set<PoiKind>): List<RoutePoi> {
        if (points.isEmpty() || kinds.isEmpty()) return emptyList()

        var minLat = Double.POSITIVE_INFINITY
        var minLng = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var maxLng = Double.NEGATIVE_INFINITY
        for (p in points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lng < minLng) minLng = p.lng
            if (p.lng > maxLng) maxLng = p.lng
        }
        // All-NaN / no finite points guard.
        if (minLat > maxLat || minLng > maxLng) return emptyList()

        return poisNearBbox(
            minLat - ROUTE_BBOX_PAD_DEG,
            minLng - ROUTE_BBOX_PAD_DEG,
            maxLat + ROUTE_BBOX_PAD_DEG,
            maxLng + ROUTE_BBOX_PAD_DEG,
            kinds
        )
    }

    /**
     * Build an Overpass QL query for the requested [kinds] inside the bbox.
     *
     * <p>Shape (one `node` clause per kind, combined in a single union):
     * ```
     * [out:json][timeout:25];
     * (
     *   node["amenity"="charging_station"](south,west,north,east);
     *   node["amenity"="fuel"](south,west,north,east);
     * );
     * out body 50;
     * ```
     * Pure function — exposed for unit testing.
     */
    internal fun buildQuery(
        south: Double, west: Double, north: Double, east: Double, kinds: Set<PoiKind>
    ): String {
        val bbox = "($south,$west,$north,$east)"
        val sb = StringBuilder()
        sb.append("[out:json][timeout:").append(OVERPASS_TIMEOUT_S).append("];(")
        // Deterministic ordering (CHARGING then FUEL) regardless of set iteration.
        if (kinds.contains(PoiKind.CHARGING)) {
            sb.append("node[\"amenity\"=\"charging_station\"]").append(bbox).append(';')
        }
        if (kinds.contains(PoiKind.FUEL)) {
            sb.append("node[\"amenity\"=\"fuel\"]").append(bbox).append(';')
        }
        sb.append(");out body ").append(RESULT_CAP).append(';')
        return sb.toString()
    }

    /**
     * Parse an Overpass `[out:json]` response into [RoutePoi]s.
     *
     * <p>Shape: a top-level `elements` array; each element has numeric `lat` and
     * `lon`, plus a `tags` object carrying `amenity` (charging_station|fuel) and
     * an optional `name`. Elements without a valid lat/lon or a recognized
     * amenity are skipped; a missing `name` maps to an empty string. Pure
     * function — exposed for unit testing.
     *
     * @param json the raw Overpass response body
     */
    internal fun parseOverpass(json: String): List<RoutePoi> {
        val out = ArrayList<RoutePoi>()
        try {
            val elements = JSONObject(json).optJSONArray("elements") ?: return out
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val lat = el.optDouble("lat", Double.NaN)
                val lng = el.optDouble("lon", Double.NaN)
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) continue

                val tags = el.optJSONObject("tags") ?: continue
                val kind = when (tags.optString("amenity", "")) {
                    "charging_station" -> PoiKind.CHARGING
                    "fuel" -> PoiKind.FUEL
                    else -> continue
                }
                val name = tags.optString("name", "").trim()
                out.add(RoutePoi(kind, name, lat, lng))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parseOverpass failed: ${t.message}")
        }
        return out
    }

    // ---- cache helpers --------------------------------------------------------

    /** Round bbox edges + kinds into a stable cache key. */
    private fun cacheKey(
        south: Double, west: Double, north: Double, east: Double, kinds: Set<PoiKind>
    ): String {
        val k = StringBuilder()
        if (kinds.contains(PoiKind.CHARGING)) k.append('C')
        if (kinds.contains(PoiKind.FUEL)) k.append('F')
        return "$k|${round(south)},${round(west)},${round(north)},${round(east)}"
    }

    private fun round(v: Double): Long = Math.round(v / CACHE_KEY_DEG)

    private fun cacheGet(key: String): List<RoutePoi>? {
        synchronized(cacheLock) {
            val e = cache[key] ?: return null
            if (System.currentTimeMillis() - e.tsMs > CACHE_TTL_MS) {
                cache.remove(key)
                return null
            }
            return e.pois
        }
    }

    private fun cachePut(key: String, pois: List<RoutePoi>) {
        synchronized(cacheLock) {
            // Drop stale entries opportunistically so the map never grows unbounded.
            if (cache.size > 32) {
                val now = System.currentTimeMillis()
                cache.entries.removeAll { now - it.value.tsMs > CACHE_TTL_MS }
            }
            cache[key] = CacheEntry(System.currentTimeMillis(), pois)
        }
    }
}
