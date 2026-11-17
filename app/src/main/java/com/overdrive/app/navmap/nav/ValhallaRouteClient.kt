package com.overdrive.app.navmap.nav

import android.util.Log
import com.overdrive.app.navmap.NavMapConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches a driving route from the BYOK (bring-your-own-key) Valhalla endpoint
 * configured in [NavMapConfig].
 *
 * <p>Style mirrors [com.overdrive.app.navmap.RoadSenseHazardApiClient]: lazy
 * OkHttp client, SYNC method (the Activity runs it off the UI thread), and
 * NEVER throwing — any failure (no key, transport error, malformed response)
 * returns `null`. The caller treats `null` as "no route" and, when the key is
 * blank, prompts the user to add a routing key.
 *
 * <p>Routing can be slower than the hazard surface, so the read timeout is
 * raised to 10s (connect stays at 4s).
 */
object ValhallaRouteClient {

    private const val TAG = "ValhallaRouteClient"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Lazy so the OkHttpClient isn't built until the first route request.
    // Proxy-aware via MapNetworking (the BYOK endpoint is on the public internet,
    // so it must follow sing-box / Tailscale when present).
    // connectTimeout widened 4s→8s for the PROXIED path (the sing-box CONNECT handshake
    // on in-car mobile data needs more than 4s; the old budget tipped route requests
    // into a connect timeout while the proxy was actually fine — part of the "can't
    // search routes while sing-box on" report). readTimeout stays 10s (Valhalla compute
    // round-trip); bounded so a hung endpoint still fails gracefully to an empty route.
    private val http: OkHttpClient by lazy {
        MapNetworking.builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            // readTimeout matched to the geocoder's 12s: a Valhalla compute round-trip
            // THROUGH the sing-box proxy on in-car mobile data can exceed 10s for a
            // long/alternates route; the old 10s tipped those into a read timeout that
            // surfaced as the generic "route_failed" (now logged as TIMEOUT). Still
            // bounded so a hung endpoint fails gracefully.
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Compute an `auto` (driving) route from the start to the end coordinate.
     *
     * <p>Reads [NavMapConfig.fromUnifiedConfig] for the endpoint + BYOK key.
     * If the key is blank, returns `null` immediately (the caller should ask
     * the user to add a routing key). Otherwise POSTs a Valhalla `/route`
     * request and parses the first leg.
     *
     * @param startLat origin latitude in decimal degrees
     * @param startLng origin longitude in decimal degrees
     * @param endLat destination latitude in decimal degrees
     * @param endLng destination longitude in decimal degrees
     * @return a decoded [NavRoute] (polyline, maneuvers, totals), or `null` on
     *   any failure / missing key
     */
    fun route(startLat: Double, startLng: Double, endLat: Double, endLng: Double): NavRoute? =
        routesWithAlternates(startLat, startLng, endLat, endLng, 0).firstOrNull()

    /**
     * Why the LAST route attempt failed, so the caller can show a SPECIFIC message
     * instead of the catch-all "Could not find a route." Set on every routing path
     * (route / routeVia / routesWithAlternates) before it returns, read by the
     * Activity right after an empty result. Volatile + a single foreground routing
     * call at a time, so a plain field is sufficient (no per-call token needed).
     *
     *   NONE        — never attempted / last attempt SUCCEEDED
     *   NO_KEY      — no BYOK routing key configured
     *   AUTH        — provider rejected the key (HTTP 401/403) → the key is wrong/expired
     *   HTTP        — provider returned another non-2xx (4xx request / 5xx provider)
     *   TIMEOUT     — connect/read timed out (slow network or proxy CONNECT handshake)
     *   NETWORK     — other transport failure (DNS / connection reset / proxy down)
     *   EMPTY       — HTTP 200 but no usable route in the body (no path between points)
     */
    enum class RouteError { NONE, NO_KEY, AUTH, HTTP, TIMEOUT, NETWORK, EMPTY }

    @Volatile
    var lastError: RouteError = RouteError.NONE
        private set

    /** HTTP status of the last non-2xx routing response (0 if the failure wasn't an
     *  HTTP error). Surfaced in the snackbar so a 401 vs 404 vs 503 is distinguishable. */
    @Volatile
    var lastHttpCode: Int = 0
        private set

    /** Classify a caught transport throwable into TIMEOUT vs NETWORK for [lastError]. */
    private fun classify(t: Throwable): RouteError =
        if (t is java.net.SocketTimeoutException ||
            (t.message?.contains("timeout", ignoreCase = true) == true)
        ) RouteError.TIMEOUT else RouteError.NETWORK

    /**
     * Compute a route through an ORDERED list of locations: origin, optional
     * intermediate stops (waypoints), then destination. The first point is the
     * start and the last is the final destination; everything between is a
     * via-stop the route must pass through in order.
     *
     * Valhalla does NOT support `alternates` on multipoint routes, so this always
     * returns a single route (wrapped in a list for caller symmetry). Empty list
     * on failure / missing key / fewer than 2 points.
     *
     * @param points ordered [origin, stop1, stop2, …, destination]; ≥2 required
     * @param startHeading the way the car is going at the origin (a reroute), or null
     */
    fun routeVia(points: List<GeoPoint>, startHeading: Double? = null): List<NavRoute> {
        if (points.size < 2) return emptyList()
        return try {
            val cfg = NavMapConfig.fromUnifiedConfig()
            val key = cfg.routingApiKey
            if (key.isEmpty()) {
                Log.w(TAG, "routeVia skipped: no routing API key configured")
                fail(RouteError.NO_KEY)
                return emptyList()
            }
            val url = buildRouteUrl(cfg.routingEndpoint, key)
            val body = buildRequestBodyVia(points, startHeading)
            val req = Request.Builder().url(url).post(body.toRequestBody(JSON)).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logHttpFailure("routeVia", resp)
                    return emptyList()
                }
                val bodyStr = resp.body?.string() ?: run { fail(RouteError.EMPTY); return emptyList() }
                finish(parseRoutes(bodyStr))
            }
        } catch (t: Throwable) {
            logThrowable("routeVia", t)
            emptyList()
        }
    }

    /**
     * Compute the primary `auto` route PLUS up to [alternates] alternate routes
     * between the start and end coordinate. The returned list is ordered
     * [primary, alt1, alt2, …]; it is empty on any failure / missing key.
     * Valhalla may return fewer alternates than requested (or none) — that's
     * normal, the caller just gets what's available.
     *
     * @param alternates how many ALTERNATE routes to request (0 = primary only)
     * @return ordered routes (primary first), or empty list on failure
     */
    fun routesWithAlternates(
        startLat: Double, startLng: Double, endLat: Double, endLng: Double, alternates: Int,
        startHeading: Double? = null
    ): List<NavRoute> {
        return try {
            val cfg = NavMapConfig.fromUnifiedConfig()
            val key = cfg.routingApiKey
            if (key.isEmpty()) {
                Log.w(TAG, "route skipped: no routing API key configured")
                fail(RouteError.NO_KEY)
                return emptyList()
            }

            val url = buildRouteUrl(cfg.routingEndpoint, key)
            val body = buildRequestBody(startLat, startLng, endLat, endLng, alternates, startHeading)

            val req = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logHttpFailure("route", resp)
                    return emptyList()
                }
                val bodyStr = resp.body?.string() ?: run { fail(RouteError.EMPTY); return emptyList() }
                finish(parseRoutes(bodyStr))
            }
        } catch (t: Throwable) {
            logThrowable("route", t)
            emptyList()
        }
    }

    // ── Error-state bookkeeping + diagnostic logging ─────────────────────────────
    // The old client swallowed every failure into an empty list and logged only
    // "HTTP <code>", so a wrong key (401), a malformed request (4xx), a provider
    // outage (5xx), and a proxy/network timeout were INDISTINGUISHABLE — both in the
    // log and to the user (always "Could not find a route"). These helpers (a) record
    // a typed [lastError] + [lastHttpCode] the Activity reads to show a SPECIFIC
    // message, and (b) log the response body / throwable class so the next on-car
    // session is a definitive diagnosis, not another guessing round.

    private fun fail(err: RouteError, httpCode: Int = 0): List<NavRoute> {
        lastError = err
        lastHttpCode = httpCode
        return emptyList()
    }

    /** Record success / empty-but-200 from a parsed result list, then return it. */
    private fun finish(routes: List<NavRoute>): List<NavRoute> {
        if (routes.isEmpty()) { lastError = RouteError.EMPTY; lastHttpCode = 200 }
        else { lastError = RouteError.NONE; lastHttpCode = 200 }
        return routes
    }

    /** Log + classify a non-2xx routing response: 401/403 → AUTH (bad/expired key),
     *  anything else → HTTP. Logs the first ~300 chars of the body (the provider's
     *  error message — e.g. Stadia's "Invalid API key") which the old code discarded. */
    private fun logHttpFailure(tag: String, resp: okhttp3.Response) {
        val code = resp.code
        val snippet = try { resp.body?.string()?.take(300) } catch (_: Throwable) { null }
        Log.w(TAG, "POST $tag -> HTTP $code; body=${snippet ?: "<none>"}")
        when (code) {
            401, 403 -> fail(RouteError.AUTH, code)
            else -> fail(RouteError.HTTP, code)
        }
    }

    /** Log + classify a caught transport throwable (TIMEOUT vs NETWORK), including the
     *  exception CLASS so a SocketTimeoutException is distinguishable from a connect
     *  failure / reset in the log. */
    private fun logThrowable(tag: String, t: Throwable) {
        Log.w(TAG, "$tag failed: ${t.javaClass.simpleName}: ${t.message}")
        lastError = classify(t)
        lastHttpCode = 0
    }

    /**
     * Append the BYOK key to the endpoint as a query param per the Valhalla
     * cloud-provider convention (`?api_key=<key>`). Preserves any existing
     * query string on the endpoint. Pure function — exposed for unit testing.
     *
     * @param endpoint the configured routing endpoint (already includes the
     *   `/route` path per [NavMapConfig.DEFAULT_ROUTING_ENDPOINT])
     * @param apiKey the decrypted BYOK key
     */
    internal fun buildRouteUrl(endpoint: String, apiKey: String): String {
        val sep = if (endpoint.contains('?')) '&' else '?'
        return "$endpoint${sep}api_key=$apiKey"
    }

    /**
     * Build the Valhalla `/route` request body JSON. Pure function — exposed
     * for unit testing.
     *
     * @param startLat origin latitude (decimal degrees)
     * @param startLng origin longitude (decimal degrees)
     * @param endLat destination latitude (decimal degrees)
     * @param endLng destination longitude (decimal degrees)
     */
    internal fun buildRequestBody(
        startLat: Double, startLng: Double, endLat: Double, endLng: Double, alternates: Int = 0,
        startHeading: Double? = null
    ): String {
        val locations = JSONArray()
        val start = JSONObject().put("lat", startLat).put("lon", startLng)
        // Origin heading: on a reroute the car is already moving in a direction —
        // pass it (+tolerance) so Valhalla starts the new route IN that direction
        // instead of demanding an immediate U-turn back onto the old path.
        if (startHeading != null && !startHeading.isNaN()) {
            start.put("heading", ((startHeading % 360.0) + 360.0) % 360.0)
            start.put("heading_tolerance", 45)
        }
        locations.put(start)
        locations.put(JSONObject().put("lat", endLat).put("lon", endLng))
        val body = JSONObject()
            .put("locations", locations)
            .put("costing", "auto")
            // Language- + unit-aware: maneuver instructions in the user's app
            // language (Valhalla falls back to English for unsupported tags) and
            // distances in the user's Trips km/miles preference.
            .put("directions_options", JSONObject()
                .put("units", MapNetworking.valhallaUnits)
                .put("language", MapNetworking.valhallaLanguage))
        // "alternates" = how many ALTERNATE routes to also return (primary is
        // always returned separately). Only set when > 0.
        if (alternates > 0) body.put("alternates", alternates)
        // Lane guidance; Valhalla only honours this at the top level.
        body.put("turn_lanes", true)
        return body.toString()
    }

    /**
     * Build a multipoint Valhalla `/route` body from an ordered point list
     * (origin … via-stops … destination). Intermediate stops use
     * `type:"through"`-style break points (Valhalla defaults each location to a
     * break, which is what we want — the route stops/continues at each). Pure
     * function — exposed for unit testing.
     */
    internal fun buildRequestBodyVia(points: List<GeoPoint>, startHeading: Double? = null): String {
        val locations = JSONArray()
        points.forEachIndexed { i, p ->
            val loc = JSONObject().put("lat", p.lat).put("lon", p.lng)
            // (as for a single destination: the new route leaves the way the car is going)
            if (i == 0 && startHeading != null && !startHeading.isNaN()) {
                loc.put("heading", ((startHeading % 360.0) + 360.0) % 360.0)
                loc.put("heading_tolerance", 45)
            }
            locations.put(loc)
        }
        return JSONObject()
            .put("locations", locations)
            .put("costing", "auto")
            .put("directions_options", JSONObject()
                .put("units", MapNetworking.valhallaUnits)
                .put("language", MapNetworking.valhallaLanguage))
            .put("turn_lanes", true)
            .toString()
    }

    /**
     * Parse a Valhalla `/route` response into a [NavRoute].
     *
     * <p>Shape: each `trip.legs[i].shape` is a precision-6 encoded polyline (decoded
     * via [PolylineCodec]) and ALL legs are concatenated (a multi-stop trip has one
     * leg per via-stop — see [parseTrip]); `legs[i].maneuvers[]` carry `instruction`,
     * `type`, `begin_shape_index`, `length` (km -> *1000 m), `time` (s); the
     * maneuver lat/lng is taken from `points[begin_shape_index]`. Trip totals
     * come from `trip.summary.length` (km -> *1000 m) and `trip.summary.time`
     * (s). Returns `null` if the response has no usable leg. Pure function —
     * exposed for unit testing.
     *
     * @param json the raw Valhalla response body
     */
    internal fun parseRoute(json: String): NavRoute? =
        parseRoutes(json).firstOrNull()

    /**
     * Parse a Valhalla response into an ordered list of [NavRoute]s: the primary
     * `trip` first, then each `alternates[i].trip` (Valhalla returns alternates
     * as a top-level array, each entry wrapping a `trip` object with the same
     * shape as the primary). Routes that fail to parse are skipped. Pure
     * function — exposed for unit testing.
     *
     * @param json the raw Valhalla response body
     */
    internal fun parseRoutes(json: String): List<NavRoute> {
        return try {
            val root = JSONObject(json)
            val out = ArrayList<NavRoute>()
            root.optJSONObject("trip")?.let { parseTrip(it)?.let(out::add) }
            val alts = root.optJSONArray("alternates")
            if (alts != null) {
                for (i in 0 until alts.length()) {
                    val altTrip = alts.optJSONObject(i)?.optJSONObject("trip") ?: continue
                    parseTrip(altTrip)?.let(out::add)
                }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "parseRoutes failed: ${t.message}")
            emptyList()
        }
    }

    /**
     * Parse a single Valhalla `trip` object into a [NavRoute], or null if unusable.
     *
     * <p>CONCATENATES ALL LEGS. Valhalla splits a route at every `break` location,
     * and [buildRequestBodyVia] sends each via-stop as a break — so a multi-stop trip
     * comes back as N legs (origin→stop1, stop1→stop2, …, →destination). Reading only
     * `legs[0]` (the old behaviour) truncated a multi-stop route to origin→FIRST-stop:
     * the guidance engine's "destination" became the first via, so arrival fired at the
     * wrong place and the cluster mirrored a route that ended early. We now stitch every
     * leg's polyline into one continuous `points` list and every leg's maneuvers into one
     * ordered list, OFFSETTING each subsequent leg's `begin_shape_index` by the running
     * vertex count so the maneuver indices stay valid against the concatenated polyline.
     * The shared break vertex between consecutive legs (legN ends where legN+1 starts) is
     * de-duplicated so the polyline has no zero-length seam. Trip totals come from
     * `trip.summary` (already the whole-trip sum) with a per-leg fallback.
     */
    private fun parseTrip(trip: JSONObject): NavRoute? {
        val legs = trip.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null

        val points = ArrayList<GeoPoint>()
        val maneuvers = ArrayList<RouteManeuver>()
        var legSumDistanceM = 0.0
        var legSumDurationS = 0.0

        for (li in 0 until legs.length()) {
            val leg = legs.optJSONObject(li) ?: continue
            val legPts = PolylineCodec.decode(leg.optString("shape", ""), 1e6)
            if (legPts.isEmpty()) continue
            // Index in [points] where THIS leg's vertices begin. After the first leg,
            // drop the leg's first vertex IFF it coincides with the previous leg's last
            // vertex (the shared break point): appending it would create a duplicate/
            // zero-length segment. We test the coincidence rather than assuming it, so a
            // skipped (e.g. empty-shape) in-between leg — after which this leg's vertex 0
            // is NOT the previous appended vertex — appends all vertices instead of
            // dropping a real point and fabricating a gap. The maneuver offset uses the
            // matching base so indices stay aligned to this leg's true vertex 0.
            val base: Int
            val prevLast = points.lastOrNull()
            val first = legPts.first()
            val coincides = prevLast != null &&
                kotlin.math.abs(prevLast.lat - first.lat) < 1e-7 &&
                kotlin.math.abs(prevLast.lng - first.lng) < 1e-7
            if (prevLast == null) {
                base = 0
                points.addAll(legPts)
            } else if (coincides) {
                base = points.size - 1   // genuine shared break vertex → dedup vertex 0
                for (i in 1 until legPts.size) points.add(legPts[i])
            } else {
                base = points.size       // not adjacent (prior leg skipped) → append all
                points.addAll(legPts)
            }

            // Keep EVERY leg's maneuvers here (including each leg's terminal
            // "destination" maneuver, type 4/5/6); the via-stop dedup happens in a
            // single post-pass below. We do NOT use the loop index to decide which
            // destination maneuver is "the real arrival": a null/empty TRAILING leg
            // is skipped (continue, above) before reaching this block, so keying the
            // drop on `li == legs.length() - 1` would orphan the true arrival —
            // the previous (last vertex-yielding) leg would be treated as non-final
            // and its destination maneuver dropped, leaving the route with NO arrival
            // maneuver at all. Deferring the drop to a post-pass over the assembled
            // list makes the surviving terminal maneuver the route's true arrival
            // regardless of how many trailing legs were degenerate.
            val mArr = leg.optJSONArray("maneuvers")
            if (mArr != null) {
                for (i in 0 until mArr.length()) {
                    val m = mArr.optJSONObject(i) ?: continue
                    val beginIdx = m.optInt("begin_shape_index", 0) + base
                    // Clamp the (offset) index so a malformed/out-of-range value can't
                    // throw; pin to the nearest valid concatenated-route vertex.
                    val safeIdx = beginIdx.coerceIn(0, points.size - 1)
                    val mp = points[safeIdx]
                    maneuvers.add(
                        RouteManeuver(
                            instruction = m.optString("instruction", ""),
                            type = m.optInt("type", 0),
                            beginShapeIndex = safeIdx,
                            lengthMeters = m.optDouble("length", 0.0) * 1000.0,
                            timeSeconds = m.optDouble("time", 0.0),
                            lat = mp.lat,
                            lng = mp.lng,
                            // Enrichment (all optional in Valhalla output):
                            roundaboutExitCount = m.optInt("roundabout_exit_count", 0),
                            bearingBefore = m.optInt("bearing_before", -1),
                            bearingAfter = m.optInt("bearing_after", -1),
                            verbalPre = m.optString("verbal_pre_transition_instruction", ""),
                            verbalPost = m.optString("verbal_post_transition_instruction", ""),
                            streetNames = texts(m.optJSONArray("street_names")),
                            lanes = lanes(m.optJSONArray("lanes")),
                            exitNumber = signTexts(m.optJSONObject("sign"), "exit_number_elements").firstOrNull().orEmpty(),
                            towards = signTexts(m.optJSONObject("sign"), "exit_toward_elements")
                                .ifEmpty { signTexts(m.optJSONObject("sign"), "exit_branch_elements") }
                        )
                    )
                }
            }
            (leg.optJSONObject("summary"))?.let {
                legSumDistanceM += it.optDouble("length", 0.0) * 1000.0
                legSumDurationS += it.optDouble("time", 0.0)
            }
        }
        if (points.isEmpty()) return null

        // Via-stop dedup post-pass: the concat above kept EVERY leg's terminal
        // "destination" maneuver (type 4/5/6), but only the LAST one is the real
        // arrival — NavGuidanceEngine.maneuverImportance() rates 4/5/6 SIGNIFICANT,
        // so nextManeuver() would otherwise surface a via-stop's destination as a
        // false mid-route "you have arrived". Drop every type-4..6 maneuver EXCEPT
        // the last in the assembled (route-order) list, so the single surviving
        // arrival maneuver is the route's TRUE destination even when a trailing
        // leg was null/empty (and therefore its own arrival was never appended).
        val lastDestIdx = maneuvers.indexOfLast { it.type in 4..6 }
        if (lastDestIdx >= 0) {
            var i = maneuvers.size - 1
            while (i >= 0) {
                if (i != lastDestIdx && maneuvers[i].type in 4..6) maneuvers.removeAt(i)
                i--
            }
        }

        // Prefer the trip-level summary (whole-trip totals); fall back to the per-leg sum
        // when it's absent/zero (a multi-stop response may omit the top-level summary).
        val summary = trip.optJSONObject("summary")
        val summaryDistanceM = (summary?.optDouble("length", 0.0) ?: 0.0) * 1000.0
        val summaryDurationS = summary?.optDouble("time", 0.0) ?: 0.0
        val totalDistanceMeters = if (summaryDistanceM > 0.0) summaryDistanceM else legSumDistanceM
        val totalDurationSeconds = if (summaryDurationS > 0.0) summaryDurationS else legSumDurationS

        return NavRoute(
            points = points,
            maneuvers = maneuvers,
            totalDistanceMeters = totalDistanceMeters,
            totalDurationSeconds = totalDurationSeconds
        )
    }

    private fun texts(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    /** `sign.<key>` element texts in order; an empty or missing sign reads as none. */
    private fun signTexts(sign: JSONObject?, key: String): List<String> {
        val arr = sign?.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("text", "")?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }

    private fun lanes(arr: JSONArray?): List<RouteLane> {
        if (arr == null) return emptyList()
        val out = ArrayList<RouteLane>(arr.length())
        for (i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: return emptyList()
            out.add(RouteLane(l.optInt("directions", 0), l.optInt("active", 0), l.optInt("valid", 0)))
        }
        return out
    }
}
