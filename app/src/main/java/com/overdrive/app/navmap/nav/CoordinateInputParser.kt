package com.overdrive.app.navmap.nav

import com.google.openlocationcode.OpenLocationCode
import java.util.Locale

/**
 * Offline "smart input" parser for the RoadSense map search box.
 *
 * <p>This is the **reach-any-place** front door: before any network geocoder is
 * consulted, [parse] tries to resolve the typed/pasted text to an exact
 * coordinate WITHOUT a round-trip. It defeats the geocoder-coverage gap head-on
 * — if the user already knows where a place is (a copied Google/OSM map link, a
 * Plus Code, or bare lat/lng), we can route there even when OSM/Photon has no
 * entry for it. Recognised forms:
 *
 *  1. **Bare coordinates** — "27.175063, 78.042188", "27.175063 78.042188",
 *     "28.6139° N, 77.2090° E" (decimal degrees, optional `°` and N/S/E/W
 *     hemisphere markers; lat first, lng second).
 *  2. **Plus Codes** (Open Location Code) — full ("7JVW52GR+2V", "849VCWC8+R9")
 *     decoded offline; SHORT codes ("CWC8+R9", optionally trailed by a locality
 *     name) recovered against the [refLat]/[refLng] reference (the vehicle's
 *     current fix) — a short code is meaningless without a reference, so it
 *     resolves only when one is supplied.
 *  3. **Map share URLs** — Google Maps (`!3d…!4d…` place pin preferred, then
 *     `@lat,lng` viewport, then `q=`/`query=`/`destination=`/`ll=`/`sll=`/
 *     `center=`), OpenStreetMap (`mlat`/`mlon` or the `#map=z/lat/lng` hash),
 *     Apple (`ll=`), and `geo:lat,lng` URIs.
 *
 * <p>**Pure + framework-free + never-throwing**: no Android imports (JVM
 * unit-testable), no network, no disk. Any unparseable input returns `null` so
 * the caller falls through to the normal remote geocoder. All coordinates are
 * validated to lat ∈ [-90,90], lng ∈ [-180,180]; the produced [SearchResult]
 * label is i18n-neutral (a `Locale.US`-formatted "lat, lng", or the normalised
 * Plus Code itself) so it needs no translation and round-trips cleanly into
 * [com.overdrive.app.navmap.RecentSearchStore] /
 * [com.overdrive.app.navmap.SavedPlacesStore].
 *
 * <p>NOTE: shortened share links (`maps.app.goo.gl`, `goo.gl/maps`) carry NO
 * coordinates in the URL — they need an HTTP redirect to expand. That network
 * step is deliberately NOT here (it would break the pure/offline contract); it
 * belongs in [ForwardGeocoder] over the proxy-aware [MapNetworking] client.
 */
object CoordinateInputParser {

    /** Decimal-degrees magnitude bounds. */
    private const val LAT_ABS_MAX = 90.0
    private const val LNG_ABS_MAX = 180.0

    /**
     * A Plus-Code-shaped token: 2–8 code or padding chars, the `+` separator, then 0–7 code chars.
     * Case-insensitive; validated afterwards by [OpenLocationCode.isValidCode].
     *
     * <p>ANCHORED to the START of the input (after optional whitespace) and bounded
     * by end-of-string / whitespace / comma. A pasted Plus Code is ALWAYS the leading
     * token — bare ("849VCWC8+R9") or code-then-locality ("CWC8+R9 Mountain View, CA",
     * the canonical form Google emits for a SHORT code). Anchoring is the fix for the
     * hijack bug: an UNanchored `.find()` matched a code-shaped SUBSTRING buried inside
     * a real place name (e.g. "Cafe near 8FVC2222+22") and routed there offline instead
     * of geocoding the user's actual query. A code appearing mid-text now falls through
     * to the geocoder (safe), while a genuine leading code (± a locality suffix) still
     * resolves. Group 1 is the code token.
     */
    private val PLUS_CODE = Regex(
        "^\\s*([023456789CFGHJMPQRVWX]{2,8}\\+[23456789CFGHJMPQRVWX]{0,7})(?:\\s|,|\$)",
        RegexOption.IGNORE_CASE
    )

    /** Google Maps place-pin coordinates `!3d<lat>!4d<lng>` (most precise). */
    private val URL_PLACE_PIN = Regex("!3d([+-]?\\d+\\.?\\d*)!4d([+-]?\\d+\\.?\\d*)")

    /** Google Maps viewport `@<lat>,<lng>(,zoom)`. */
    private val URL_AT = Regex("@([+-]?\\d+\\.?\\d*),([+-]?\\d+\\.?\\d*)")

    /** `geo:<lat>,<lng>` URI (RFC 5870, ignoring any trailing `;`/`?` params). */
    private val URL_GEO = Regex("geo:([+-]?\\d+\\.?\\d*),([+-]?\\d+\\.?\\d*)", RegexOption.IGNORE_CASE)

    /** Query-param coordinate pairs: q / query / ll / sll / center / destination / daddr. */
    private val URL_QUERY = Regex(
        "[?&#](?:q|query|ll|sll|center|destination|daddr)=([+-]?\\d+\\.?\\d*),([+-]?\\d+\\.?\\d*)",
        RegexOption.IGNORE_CASE
    )

    /** OpenStreetMap marker params `mlat=…&mlon=…` (order-independent). */
    private val OSM_MLAT = Regex("[?&]mlat=([+-]?\\d+\\.?\\d*)", RegexOption.IGNORE_CASE)
    private val OSM_MLON = Regex("[?&]mlon=([+-]?\\d+\\.?\\d*)", RegexOption.IGNORE_CASE)

    /** OpenStreetMap hash view `#map=<zoom>/<lat>/<lng>`. */
    private val OSM_HASH = Regex("#map=\\d+(?:\\.\\d+)?/([+-]?\\d+\\.?\\d*)/([+-]?\\d+\\.?\\d*)", RegexOption.IGNORE_CASE)

    /**
     * Bare "lat[sep]lng" with optional `°` and hemisphere letters, e.g.
     * "27.17, 78.04" · "27.17 78.04" · "27.17° N, 78.04° E". The hemisphere
     * letters are captured so S/W can flip the sign.
     */
    private val LATLNG = Regex(
        "^\\s*([+-]?\\d{1,2}(?:\\.\\d+)?)\\s*°?\\s*([NSns])?\\s*[,;\\s]\\s*" +
            "([+-]?\\d{1,3}(?:\\.\\d+)?)\\s*°?\\s*([EWew])?\\s*$"
    )

    /**
     * Try to resolve [input] to an exact place offline. Returns `null` when the
     * text is not a coordinate / Plus Code / map URL (the caller then runs the
     * normal remote geocoder). Never throws.
     *
     * @param input the raw search-box text (typed or pasted)
     * @param refLat optional reference latitude (the current vehicle fix) used
     *   only to recover SHORT Plus Codes; full codes & coordinates ignore it
     * @param refLng optional reference longitude (paired with [refLat])
     */
    fun parse(input: String?, refLat: Double? = null, refLng: Double? = null): SearchResult? {
        val t = input?.trim().orEmpty()
        if (t.isEmpty()) return null
        return try {
            // A URL is unambiguous — resolve it to embedded coords or give up
            // (don't let URL text fall through to the coordinate/Plus-Code paths).
            if (looksLikeUrl(t)) return parseMapUrl(t)
            // Plus Code before bare coordinates: a code carries a '+' that a plain
            // coordinate never does, so this can't shadow a numeric pair.
            parsePlusCode(t, refLat, refLng)?.let { return it }
            parseLatLng(t)
        } catch (_: Throwable) {
            null
        }
    }

    /** True for strings we should treat as a map URL / geo URI rather than free text. */
    private fun looksLikeUrl(s: String): Boolean {
        val l = s.lowercase(Locale.US)
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("geo:") ||
            l.contains("google.") && l.contains("/maps") || l.contains("goo.gl") ||
            l.contains("openstreetmap.org") || l.contains("osm.org") || l.contains("apple.com/maps")
    }

    // ── Bare coordinates ──────────────────────────────────────────────────────

    /** Parse a bare "lat,lng" (decimal degrees, optional `°`/hemisphere). */
    internal fun parseLatLng(s: String): SearchResult? {
        val m = LATLNG.matchEntire(s) ?: return null
        var lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val latHemi = m.groupValues[2]
        var lng = m.groupValues[3].toDoubleOrNull() ?: return null
        val lngHemi = m.groupValues[4]
        // A hemisphere letter sets the sign explicitly (S/W negative); a typed
        // '-' alongside a hemisphere letter would be contradictory, so the letter
        // wins by taking the magnitude.
        if (latHemi.isNotEmpty()) lat = Math.abs(lat) * (if (latHemi.equals("S", true)) -1 else 1)
        if (lngHemi.isNotEmpty()) lng = Math.abs(lng) * (if (lngHemi.equals("W", true)) -1 else 1)
        return makeResult(lat, lng, coordLabel(lat, lng))
    }

    // ── Plus Codes ──────────────────────────────────────────────────────────

    /**
     * Parse a full or short Plus Code embedded in [s] (e.g. "849VCWC8+R9" or a
     * short "CWC8+R9 Mountain View"). Short codes require a [refLat]/[refLng]
     * reference to recover; without one (or if recovery/validation fails) returns
     * null.
     */
    internal fun parsePlusCode(s: String, refLat: Double?, refLng: Double?): SearchResult? {
        // Group 1 = the anchored code token (the regex strips leading space + the
        // trailing whitespace/comma/locality boundary).
        val token = PLUS_CODE.find(s)?.groupValues?.getOrNull(1)?.uppercase(Locale.US) ?: return null
        if (!OpenLocationCode.isValidCode(token)) return null
        val full: OpenLocationCode = when {
            OpenLocationCode.isFullCode(token) -> OpenLocationCode(token)
            OpenLocationCode.isShortCode(token) -> {
                // Can't recover a short code without a FINITE reference. Reject null AND
                // NaN/Infinity — recover() would otherwise clip a non-finite reference to
                // an in-range but bogus coordinate and route the car there.
                if (refLat == null || refLng == null ||
                    !refLat.isFinite() || !refLng.isFinite()) return null
                OpenLocationCode(token).recover(refLat, refLng)
            }
            else -> return null
        }
        val area = full.decode()
        val lat = area.centerLatitude
        val lng = area.centerLongitude
        // Label with the resolved full code — recognizable, i18n-neutral, and
        // re-findable if saved (the code IS the place identifier).
        return makeResult(lat, lng, full.code)
    }

    // ── Map URLs ──────────────────────────────────────────────────────────────

    /** Extract the best coordinate from a Google/OSM/Apple/geo map URL, else null. */
    internal fun parseMapUrl(url: String): SearchResult? {
        // Light percent-decode so an encoded `q=lat%2Clng` still matches the comma.
        val u = url.replace("%2C", ",").replace("%2c", ",")

        // Precedence by how strongly each form names the DESTINATION (not the map
        // view): explicit pin/marker/query coords first, then viewport centers last.
        //   1. `!3d!4d`  Google place pin (the actual place)
        //   2. `geo:`    explicit coordinate URI
        //   3. mlat/mlon OSM marker (explicit shared pin)
        //   4. q=/…      explicit query coordinates
        //   5. `@lat,lng` Google viewport CENTER (weaker — not the pin)
        //   6. `#map=`   OSM viewport hash (weakest — just the current view)
        URL_PLACE_PIN.find(u)?.let { return fromPair(it.groupValues[1], it.groupValues[2]) }
        URL_GEO.find(u)?.let { return fromPair(it.groupValues[1], it.groupValues[2]) }
        val mlat = OSM_MLAT.find(u)?.groupValues?.get(1)
        val mlon = OSM_MLON.find(u)?.groupValues?.get(1)
        if (mlat != null && mlon != null) return fromPair(mlat, mlon)
        URL_QUERY.find(u)?.let { return fromPair(it.groupValues[1], it.groupValues[2]) }
        URL_AT.find(u)?.let { return fromPair(it.groupValues[1], it.groupValues[2]) }
        OSM_HASH.find(u)?.let { return fromPair(it.groupValues[1], it.groupValues[2]) }
        return null
    }

    private fun fromPair(latStr: String, lngStr: String): SearchResult? {
        val lat = latStr.toDoubleOrNull() ?: return null
        val lng = lngStr.toDoubleOrNull() ?: return null
        return makeResult(lat, lng, coordLabel(lat, lng))
    }

    // ── shared ──────────────────────────────────────────────────────────────

    /** Validate the WGS-84 range and build the result; null if out of range / NaN. */
    private fun makeResult(lat: Double, lng: Double, label: String): SearchResult? {
        if (lat.isNaN() || lng.isNaN()) return null
        if (lat < -LAT_ABS_MAX || lat > LAT_ABS_MAX) return null
        if (lng < -LNG_ABS_MAX || lng > LNG_ABS_MAX) return null
        return SearchResult(label, lat, lng)
    }

    /** i18n-neutral "lat, lng" label (dot decimal, 5 dp ≈ 1.1 m). */
    private fun coordLabel(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.5f, %.5f", lat, lng)
}
