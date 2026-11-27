package com.overdrive.app.navmap.google

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.overdrive.app.navmap.nav.SearchResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Google Places API (New) autocomplete, details and text search plus Geocoding reverse for the
 * RoadSense search box. Sync and never throws; null means "use the existing geocoder chain".
 */
object GooglePlacesClient {

    private const val TAG = "GooglePlaces"
    private const val SOURCE = "google"

    private const val AUTOCOMPLETE_URL = "https://places.googleapis.com/v1/places:autocomplete"
    private const val TEXT_SEARCH_URL = "https://places.googleapis.com/v1/places:searchText"
    private const val DETAILS_URL = "https://places.googleapis.com/v1/places"
    private const val GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json"

    private const val FIELD_MASK = "X-Goog-FieldMask"
    private const val AUTOCOMPLETE_MASK = "suggestions.placePrediction.placeId," +
        "suggestions.placePrediction.text,suggestions.placePrediction.structuredFormat"
    // Essentials-only fields: adding displayName bills the details call at Pro.
    private const val DETAILS_MASK = "location,formattedAddress,shortFormattedAddress"
    private const val TEXT_SEARCH_MASK = "places.id,places.displayName,places.formattedAddress," +
        "places.shortFormattedAddress,places.location"

    private const val BIAS_RADIUS_M = 50_000.0
    private const val MAX_PAGE_SIZE = 20
    private const val SESSION_IDLE_MS = 3 * 60_000L

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    // The Google budget lives in the app process; daemon callers (voice, assistant) keep the free chain.
    private val appProcess: Boolean = try {
        Process.myUid() >= Process.FIRST_APPLICATION_UID
    } catch (_: Throwable) {
        false
    }

    private val sessionLock = Any()
    private var sessionToken: String? = null
    private var sessionUsedAtMs = 0L

    // Per calling thread, so a concurrent autocomplete cannot flip a search's result.
    private val transportError = ThreadLocal<IOException?>()

    /** True when this thread's last call failed in transport (timeout, DNS, connect). */
    @JvmStatic
    val lastTransportFailure: Boolean
        get() = transportError.get() != null

    /** The exception behind [lastTransportFailure], for timeout vs network classification. */
    internal val lastTransportError: IOException?
        get() = transportError.get()

    @JvmStatic
    fun usable(): Boolean = try {
        appProcess && GoogleMapsConfig.cached().searchUsable && GoogleMapsBudget.latch(GoogleApi.PLACES) == null &&
            GoogleMapsBudget.hasRoom(GoogleSku.AUTOCOMPLETE) && GoogleMapsBudget.hasRoom(GoogleSku.PLACE_DETAILS)
    } catch (_: Throwable) {
        false
    }

    @JvmStatic
    fun reverseUsable(): Boolean = try {
        appProcess && GoogleMapsConfig.cached().reverseUsable && GoogleMapsBudget.latch(GoogleApi.GEOCODE) == null &&
            GoogleMapsBudget.hasRoom(GoogleSku.GEOCODE)
    } catch (_: Throwable) {
        false
    }

    /** Place predictions (coordinates NaN until [details]); null ⇒ fall back. */
    @JvmStatic
    fun autocomplete(query: String, limit: Int, focusLat: Double?, focusLng: Double?): List<SearchResult>? {
        beginCall()
        val q = query.trim()
        if (q.isEmpty() || !usable()) return null
        return try {
            val body = JSONObject()
                .put("input", q)
                .put("sessionToken", autocompleteToken())
            putLocale(body)
            latLngJson(focusLat, focusLng)?.let { body.put("locationBias", circle(it)) }
            latLngJson(focusLat, focusLng)?.let { body.put("origin", it) }
            body.put("includeQueryPredictions", false)
            val req = Request.Builder()
                .url(AUTOCOMPLETE_URL)
                .header(FIELD_MASK, AUTOCOMPLETE_MASK)
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            execute(req, "autocomplete")?.let { parseAutocomplete(it, limit.coerceAtLeast(1)) }
        } catch (_: Throwable) {
            null
        }
    }

    /** Resolves a prediction's coordinates and ends the autocomplete session; null on failure. */
    @JvmStatic
    fun details(result: SearchResult): SearchResult? {
        beginCall()
        val placeId = result.placeId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val token = endSessionToken()
        if (!usable()) return null
        return try {
            val url = DETAILS_URL.toHttpUrl().newBuilder()
                .addPathSegment(placeId)
                .apply {
                    token?.let { addQueryParameter("sessionToken", it) }
                    language()?.let { addQueryParameter("languageCode", it) }
                    region()?.let { addQueryParameter("regionCode", it) }
                }
                .build()
            val req = Request.Builder()
                .url(url)
                .header(FIELD_MASK, DETAILS_MASK)
                .get()
                .build()
            execute(req, "details")?.let { parseDetails(it, result) }
        } catch (_: Throwable) {
            null
        }
    }

    /** Text search with coordinates; null ⇒ fall back, empty only for a clean zero-hit answer. */
    @JvmStatic
    fun textSearch(query: String, limit: Int, focusLat: Double?, focusLng: Double?): List<SearchResult>? {
        beginCall()
        val q = query.trim()
        if (q.isEmpty() || !usable()) return null
        val n = limit.coerceIn(1, MAX_PAGE_SIZE)
        return try {
            val body = JSONObject()
                .put("textQuery", q)
                .put("pageSize", n)
            putLocale(body)
            latLngJson(focusLat, focusLng)?.let { body.put("locationBias", circle(it)) }
            val req = Request.Builder()
                .url(TEXT_SEARCH_URL)
                .header(FIELD_MASK, TEXT_SEARCH_MASK)
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            execute(req, "textSearch")?.let { parseTextSearch(it, n) }
        } catch (_: Throwable) {
            null
        }
    }

    /** Nearest address for a tapped point, pinned at the tapped coordinate; null ⇒ fall back. */
    @JvmStatic
    fun reverse(lat: Double, lng: Double): SearchResult? {
        beginCall()
        if (!lat.isFinite() || !lng.isFinite() || !reverseUsable()) return null
        return try {
            val url = GEOCODE_URL.toHttpUrl().newBuilder()
                .addEncodedQueryParameter("latlng", coord(lat) + "," + coord(lng))
                .apply { language()?.let { addQueryParameter("language", it) } }
                .build()
            val json = execute(Request.Builder().url(url).get().build(), "reverse") ?: return null
            val root = JSONObject(json)
            // Geocoding reports key and quota errors in a 200 body, so latch them here.
            when (root.optString("status")) {
                "OK" -> return parseReverseOk(root, lat, lng)
                "REQUEST_DENIED" -> GoogleMapsBudget.onHttpResult(GoogleApi.GEOCODE, 403, json)
                "OVER_QUERY_LIMIT", "OVER_DAILY_LIMIT" ->
                    GoogleMapsBudget.onHttpResult(GoogleApi.GEOCODE, 429, json)
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic
    fun resetSession() {
        synchronized(sessionLock) {
            sessionToken = null
            sessionUsedAtMs = 0L
        }
    }

    @JvmStatic
    fun warmUp() {
        try {
            if (usable()) GoogleHttp.warmUp(GoogleHttp.PLACES_HOST)
        } catch (_: Throwable) {
        }
    }

    internal fun parseAutocomplete(json: String, limit: Int): List<SearchResult>? {
        val root = try { JSONObject(json) } catch (_: Throwable) { return null }
        val suggestions = root.optJSONArray("suggestions") ?: return emptyList()
        val out = ArrayList<SearchResult>()
        for (i in 0 until suggestions.length()) {
            if (out.size >= limit) break
            val p = suggestions.optJSONObject(i)?.optJSONObject("placePrediction") ?: continue
            val placeId = text(p, "placeId") ?: continue
            val format = p.optJSONObject("structuredFormat")
            val label = format?.optJSONObject("mainText")?.let { text(it, "text") }
                ?: p.optJSONObject("text")?.let { text(it, "text") }
                ?: continue
            val secondary = format?.optJSONObject("secondaryText")?.let { text(it, "text") }
            out.add(SearchResult(label, Double.NaN, Double.NaN, placeId, secondary, SOURCE))
        }
        return out
    }

    internal fun parseDetails(json: String, result: SearchResult): SearchResult? {
        val root = try { JSONObject(json) } catch (_: Throwable) { return null }
        val (lat, lng) = latLng(root.optJSONObject("location")) ?: return null
        val secondary = text(root, "shortFormattedAddress") ?: text(root, "formattedAddress") ?: result.secondary
        return result.copy(lat = lat, lng = lng, secondary = secondary, source = SOURCE)
    }

    internal fun parseTextSearch(json: String, limit: Int): List<SearchResult>? {
        val root = try { JSONObject(json) } catch (_: Throwable) { return null }
        val places = root.optJSONArray("places") ?: return emptyList()
        val out = ArrayList<SearchResult>()
        for (i in 0 until places.length()) {
            if (out.size >= limit) break
            val p = places.optJSONObject(i) ?: continue
            val (lat, lng) = latLng(p.optJSONObject("location")) ?: continue
            val name = p.optJSONObject("displayName")?.let { text(it, "text") }
            val address = text(p, "shortFormattedAddress") ?: text(p, "formattedAddress")
            if (name != null) {
                out.add(SearchResult(name, lat, lng, text(p, "id"), address, SOURCE))
            } else {
                val label = text(p, "formattedAddress") ?: address ?: continue
                out.add(SearchResult(label, lat, lng, text(p, "id"), null, SOURCE))
            }
        }
        return out
    }

    internal fun parseReverseOk(root: JSONObject, lat: Double, lng: Double): SearchResult? {
        val first = root.optJSONArray("results")?.optJSONObject(0) ?: return null
        val label = text(first, "formatted_address") ?: return null
        return SearchResult(label, lat, lng, text(first, "place_id"), null, SOURCE)
    }

    // ----- internals -----

    private fun beginCall() {
        transportError.set(null)
    }

    /** The 2xx body, or null on a local budget rejection, an HTTP error or a transport failure. */
    private fun execute(req: Request, what: String): String? {
        return try {
            GoogleHttp.client().newCall(req).execute().use { resp ->
                when {
                    GoogleHttp.isLocalBudgetRejection(resp) -> null
                    !resp.isSuccessful -> {
                        Log.w(TAG, "$what -> HTTP ${resp.code}")
                        null
                    }
                    else -> resp.body?.string()
                }
            }
        } catch (e: IOException) {
            transportError.set(e)
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The live session token, minting a new one when none exists or it sat idle too long. */
    private fun autocompleteToken(): String = synchronized(sessionLock) {
        val now = SystemClock.elapsedRealtime()
        val live = sessionToken?.takeIf { now - sessionUsedAtMs <= SESSION_IDLE_MS }
        val token = live ?: UUID.randomUUID().toString()
        sessionToken = token
        sessionUsedAtMs = now
        token
    }

    /** Ends the session, returning its token while still live. */
    private fun endSessionToken(): String? = synchronized(sessionLock) {
        val live = sessionToken?.takeIf { SystemClock.elapsedRealtime() - sessionUsedAtMs <= SESSION_IDLE_MS }
        sessionToken = null
        sessionUsedAtMs = 0L
        live
    }

    private fun putLocale(body: JSONObject) {
        language()?.let { body.put("languageCode", it) }
        region()?.let { body.put("regionCode", it) }
    }

    private fun language(): String? = try {
        GoogleTileSessions.language().trim().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    private fun region(): String? = try {
        GoogleMapsBudget.appContext()?.let { GoogleTileSessions.region(it) }?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    private fun latLngJson(lat: Double?, lng: Double?): JSONObject? {
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return null
        return JSONObject().put("latitude", lat).put("longitude", lng)
    }

    private fun circle(center: JSONObject): JSONObject =
        JSONObject().put("circle", JSONObject().put("center", center).put("radius", BIAS_RADIUS_M))

    /** Proto3 JSON omits a zero field, so a present location with one key missing means 0. */
    private fun latLng(o: JSONObject?): Pair<Double, Double>? {
        if (o == null || (!o.has("latitude") && !o.has("longitude"))) return null
        // Default only omitted keys; a present but unreadable value must fail validation.
        val lat = if (o.has("latitude")) o.optDouble("latitude", Double.NaN) else 0.0
        val lng = if (o.has("longitude")) o.optDouble("longitude", Double.NaN) else 0.0
        if (!lat.isFinite() || !lng.isFinite() || lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) {
            return null
        }
        return Pair(lat, lng)
    }

    private fun text(o: JSONObject, key: String): String? =
        (o.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun coord(v: Double): String = String.format(Locale.US, "%.6f", v)
}
