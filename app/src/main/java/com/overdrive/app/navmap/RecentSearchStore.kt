package com.overdrive.app.navmap

import android.content.Context
import android.util.Log
import com.overdrive.app.navmap.nav.SearchResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent "recent searches" store for the RoadSense map — Gmaps-style.
 *
 * Holds the user's most-recently-chosen navigation destinations so the search
 * field can surface them as a dropdown. The parent ([RoadSenseMapActivity]) wires
 * this in:
 *  - it shows [getAll] in the search dropdown when the field is focused and empty, and
 *  - it calls [add] whenever a destination is actually chosen (route started),
 *    promoting it to the top of the list.
 *
 * Dedupe rule: before prepending, any existing entry that is the "same place" is
 * removed first, so re-picking a destination just bumps it to the front rather
 * than creating a duplicate. Two entries are the same place when their labels
 * match (case-insensitive, trimmed) OR their coordinates are within
 * [COORD_EPSILON] (~0.0001 deg, ≈11 m) on both axes.
 *
 * Cap: the list is trimmed to the [MAX_RECENT] most-recent entries.
 *
 * Storage: a single SharedPreferences key ([KEY_RECENTS]) in a dedicated prefs
 * file ([PREFS_NAME]) holding a JSON array string. Each element is
 * `{"label":..,"lat":..,"lng":..}` plus optional `placeId`/`secondary`/`source`/`savedAtMs`.
 * Google-sourced entries expire after 30 days. The dedicated file avoids any collision with
 * the shared "overdrive_prefs" used by PreferencesManager. Reads/writes are
 * defensive: parsing never throws, and a corrupt/missing value yields an empty
 * list. SharedPreferences I/O is cheap and synchronous; the parent may safely
 * call these from a background thread.
 */
object RecentSearchStore {

    private const val TAG = "RecentSearchStore"
    private const val PREFS_NAME = "navmap_recent"
    private const val KEY_RECENTS = "recent_searches"

    /** Maximum number of recent destinations retained. */
    const val MAX_RECENT = 8

    /**
     * Coordinate match tolerance, in decimal degrees (~0.0001 ≈ 11 m). Two
     * results within this distance on both lat and lng are treated as the
     * same place for dedupe purposes.
     */
    private const val COORD_EPSILON = 0.0001

    private const val KEY_LABEL = "label"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"
    private const val KEY_PLACE_ID = "placeId"
    private const val KEY_SECONDARY = "secondary"
    private const val KEY_SOURCE = "source"
    private const val KEY_SAVED_AT = "savedAtMs"

    private const val SOURCE_GOOGLE = "google"
    /** Google Places terms allow caching place coordinates for at most 30 days. */
    private const val GOOGLE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** A stored recent and its wall-clock add time (0 for entries written before it was tracked). */
    internal class Entry(val result: SearchResult, val savedAtMs: Long)

    /**
     * Prepend [result] to the recent list (most-recent-first), removing any
     * existing entry for the same place (see dedupe rule in the class KDoc),
     * cap the list at [MAX_RECENT], and persist. Blank labels are ignored.
     * Never throws.
     */
    fun add(context: Context, result: SearchResult) {
        try {
            if (result.label.isBlank()) {
                return
            }
            if (!validCoordinates(result.lat, result.lng)) return
            val current = entries(context)
            // Re-picking the same Google place keeps its first-seen time so the 30-day limit still applies.
            val keptAt = current.firstOrNull {
                result.source == SOURCE_GOOGLE && it.result.source == SOURCE_GOOGLE &&
                    it.result.placeId != null && it.result.placeId == result.placeId &&
                    it.result.lat == result.lat && it.result.lng == result.lng && it.savedAtMs > 0
            }?.savedAtMs
            val deduped = current.filterNot { isSamePlace(it.result, result) }
            val updated = (listOf(Entry(result, keptAt ?: System.currentTimeMillis())) + deduped).take(MAX_RECENT)
            persist(context, updated)
        } catch (e: Throwable) {
            Log.w(TAG, "add failed: ${e.message}")
        }
    }

    /**
     * Return the recent destinations, most-recent-first. Never throws; a
     * missing or corrupt store yields an empty list.
     */
    fun getAll(context: Context): List<SearchResult> = entries(context).map { it.result }

    /** Stored entries with expired Google entries pruned (and the pruned list persisted). Never throws. */
    private fun entries(context: Context): List<Entry> {
        return try {
            val raw = prefs(context).getString(KEY_RECENTS, null)
            if (raw.isNullOrBlank()) {
                emptyList()
            } else {
                pruneExpired(context, parse(raw))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "getAll failed: ${e.message}")
            emptyList()
        }
    }

    private fun pruneExpired(context: Context, all: List<Entry>): List<Entry> {
        val now = System.currentTimeMillis()
        val fresh = all.filterNot {
            it.result.source == SOURCE_GOOGLE && it.savedAtMs > 0 && now - it.savedAtMs > GOOGLE_MAX_AGE_MS
        }
        if (fresh.size != all.size) persist(context, fresh)
        return fresh
    }

    /**
     * Remove a single recent entry matching [result] (same-place rule — see the
     * class KDoc / [isSamePlace]) and persist. A no-op if no entry matches.
     * Never throws.
     */
    fun remove(context: Context, result: SearchResult) {
        try {
            val current = entries(context)
            val updated = current.filterNot { isSamePlace(it.result, result) }
            if (updated.size != current.size) {
                persist(context, updated)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "remove failed: ${e.message}")
        }
    }

    /** Wipe all recent searches. Never throws. */
    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(KEY_RECENTS).apply()
            com.overdrive.app.briefing.app.TripBriefingSync.request(context)
        } catch (e: Throwable) {
            Log.w(TAG, "clear failed: ${e.message}")
        }
    }

    // ----- internals -----

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Two results are the same place if their place ids match, labels match (case-insensitive,
     * trimmed) OR coords are within [COORD_EPSILON] on both axes. Public so callers that
     * MERGE result sets (e.g. the map's local-saved-places-into-autocomplete
     * surfacing) can dedup against this same rule rather than re-deriving it.
     */
    fun isSamePlace(a: SearchResult, b: SearchResult): Boolean {
        if (a.placeId != null && a.placeId == b.placeId) return true
        if (a.label.trim().equals(b.label.trim(), ignoreCase = true)) {
            return true
        }
        return Math.abs(a.lat - b.lat) <= COORD_EPSILON &&
            Math.abs(a.lng - b.lng) <= COORD_EPSILON
    }

    /** Writes the optional provider fields of [r] (only those that are set); shared by the navmap stores. */
    internal fun putPlaceFields(obj: JSONObject, r: SearchResult) {
        r.placeId?.let { obj.put(KEY_PLACE_ID, it) }
        r.secondary?.let { obj.put(KEY_SECONDARY, it) }
        r.source?.let { obj.put(KEY_SOURCE, it) }
    }

    /** Builds a [SearchResult], reading the optional provider fields older entries lack. */
    internal fun readPlace(obj: JSONObject, label: String, lat: Double, lng: Double): SearchResult =
        SearchResult(label, lat, lng, optText(obj, KEY_PLACE_ID), optText(obj, KEY_SECONDARY), optText(obj, KEY_SOURCE))

    private fun optText(obj: JSONObject, key: String): String? =
        (obj.opt(key) as? String)?.takeIf { it.isNotBlank() }

    internal fun validCoordinates(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0

    /** Parse the stored array, skipping entries with malformed coordinates. */
    internal fun parse(raw: String): List<Entry> {
        val out = ArrayList<Entry>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val label = obj.optString(KEY_LABEL, "")
            if (label.isBlank()) {
                continue
            }
            // optDouble returns NaN for a missing/non-numeric value.
            val lat = obj.optDouble(KEY_LAT, Double.NaN)
            val lng = obj.optDouble(KEY_LNG, Double.NaN)
            if (!validCoordinates(lat, lng)) {
                continue
            }
            out.add(Entry(readPlace(obj, label, lat, lng), obj.optLong(KEY_SAVED_AT, 0L)))
        }
        return out
    }

    /** Serialize and write the list as a JSON array string. Never throws. */
    private fun persist(context: Context, items: List<Entry>) {
        try {
            val arr = JSONArray()
            for (item in items) {
                val obj = JSONObject()
                obj.put(KEY_LABEL, item.result.label)
                obj.put(KEY_LAT, item.result.lat)
                obj.put(KEY_LNG, item.result.lng)
                putPlaceFields(obj, item.result)
                if (item.savedAtMs > 0) obj.put(KEY_SAVED_AT, item.savedAtMs)
                arr.put(obj)
            }
            prefs(context).edit().putString(KEY_RECENTS, arr.toString()).apply()
            com.overdrive.app.briefing.app.TripBriefingSync.request(context)
        } catch (e: Throwable) {
            Log.w(TAG, "persist failed: ${e.message}")
        }
    }
}
