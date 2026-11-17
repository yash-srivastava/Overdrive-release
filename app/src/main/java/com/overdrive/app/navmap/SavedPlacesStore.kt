package com.overdrive.app.navmap

import android.content.Context
import android.util.Log
import com.overdrive.app.navmap.nav.SearchResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent "saved places" store for the RoadSense map — the Home / Work /
 * custom-label favourites a user can pin and re-navigate to with one tap
 * (Gmaps/Waze-style).
 *
 * <p>Two flavours of saved place share this store, distinguished by [SavedPlace.kind]:
 *  - [KIND_HOME] / [KIND_WORK] — the two SINGLETON shortcuts. Saving a new Home
 *    replaces the existing one (there is only ever one Home, one Work).
 *  - [KIND_CUSTOM] — any number of user-named favourites ("Gym", "Mum's", …),
 *    each keyed by its (case-insensitive, trimmed) label.
 *
 * <p>The parent ([RoadSenseMapActivity]) surfaces these above the recent-search
 * list when the (empty) search field is focused, and lets the user save the
 * current destination / a tapped place into one of the slots.
 *
 * <p>Storage mirrors [RecentSearchStore]: one JSON-array string under a single
 * key in a dedicated prefs file, all reads/writes defensive (never throw). The
 * dedicated file avoids any collision with the shared app prefs and with the
 * recent-search file. SharedPreferences I/O is cheap + synchronous, so the
 * parent may call these from a background thread.
 */
object SavedPlacesStore {

    private const val TAG = "SavedPlacesStore"
    private const val PREFS_NAME = "navmap_saved_places"
    private const val KEY_PLACES = "saved_places"

    /** The singleton "home" shortcut kind. */
    const val KIND_HOME = "home"
    /** The singleton "work" shortcut kind. */
    const val KIND_WORK = "work"
    /** A free-form, user-labelled favourite. */
    const val KIND_CUSTOM = "custom"

    /** Cap on the number of CUSTOM favourites (home/work are separate singletons). */
    const val MAX_CUSTOM = 16

    private const val KEY_KIND = "kind"
    private const val KEY_LABEL = "label"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"

    /**
     * One saved place: the underlying [SearchResult] plus its [kind]
     * ([KIND_HOME] / [KIND_WORK] / [KIND_CUSTOM]).
     */
    data class SavedPlace(
        val kind: String,
        val result: SearchResult
    )

    /**
     * Save (or replace) a place.
     *  - HOME / WORK are singletons → any existing entry of that kind is removed first.
     *  - CUSTOM is keyed by its label (case-insensitive, trimmed) → a repeat label
     *    updates the existing entry rather than duplicating; the custom list is
     *    capped at [MAX_CUSTOM] (oldest dropped). Blank labels are ignored.
     * Never throws.
     */
    fun save(context: Context, kind: String, result: SearchResult) {
        try {
            if (result.label.isBlank()) return
            if (result.lat.isNaN() || result.lng.isNaN()) return
            val current = getAll(context).toMutableList()
            when (kind) {
                KIND_HOME, KIND_WORK ->
                    current.removeAll { it.kind == kind }
                else ->
                    current.removeAll {
                        it.kind == KIND_CUSTOM &&
                            it.result.label.trim().equals(result.label.trim(), ignoreCase = true)
                    }
            }
            current.add(0, SavedPlace(kind, result))
            // Enforce the custom cap (home/work are unaffected — they're singletons).
            val customs = current.filter { it.kind == KIND_CUSTOM }
            if (customs.size > MAX_CUSTOM) {
                val drop = customs.drop(MAX_CUSTOM).toSet()
                current.removeAll(drop)
            }
            persist(context, current)
        } catch (e: Throwable) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    /** The Home shortcut, or null if not set. Never throws. */
    fun getHome(context: Context): SearchResult? =
        getAll(context).firstOrNull { it.kind == KIND_HOME }?.result

    /** The Work shortcut, or null if not set. Never throws. */
    fun getWork(context: Context): SearchResult? =
        getAll(context).firstOrNull { it.kind == KIND_WORK }?.result

    /** All custom favourites, most-recently-saved first. Never throws. */
    fun getCustom(context: Context): List<SearchResult> =
        getAll(context).filter { it.kind == KIND_CUSTOM }.map { it.result }

    /**
     * Remove a saved place. HOME / WORK match by kind; CUSTOM matches by label
     * (case-insensitive, trimmed). Never throws.
     */
    fun remove(context: Context, kind: String, label: String) {
        try {
            val current = getAll(context).toMutableList()
            when (kind) {
                KIND_HOME, KIND_WORK -> current.removeAll { it.kind == kind }
                else -> current.removeAll {
                    it.kind == KIND_CUSTOM &&
                        it.result.label.trim().equals(label.trim(), ignoreCase = true)
                }
            }
            persist(context, current)
        } catch (e: Throwable) {
            Log.w(TAG, "remove failed: ${e.message}")
        }
    }

    /** Every saved place (home/work/custom), in storage order. Never throws. */
    fun getAll(context: Context): List<SavedPlace> {
        return try {
            val raw = prefs(context).getString(KEY_PLACES, null)
            if (raw.isNullOrBlank()) emptyList() else parse(raw)
        } catch (e: Throwable) {
            Log.w(TAG, "getAll failed: ${e.message}")
            emptyList()
        }
    }

    // ----- internals -----

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun parse(raw: String): List<SavedPlace> {
        val out = ArrayList<SavedPlace>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val kind = obj.optString(KEY_KIND, KIND_CUSTOM)
            val label = obj.optString(KEY_LABEL, "")
            if (label.isBlank()) continue
            val lat = obj.optDouble(KEY_LAT, Double.NaN)
            val lng = obj.optDouble(KEY_LNG, Double.NaN)
            if (lat.isNaN() || lng.isNaN()) continue
            out.add(SavedPlace(kind, RecentSearchStore.readPlace(obj, label, lat, lng)))
        }
        return out
    }

    private fun persist(context: Context, items: List<SavedPlace>) {
        try {
            val arr = JSONArray()
            for (item in items) {
                val obj = JSONObject()
                obj.put(KEY_KIND, item.kind)
                obj.put(KEY_LABEL, item.result.label)
                obj.put(KEY_LAT, item.result.lat)
                obj.put(KEY_LNG, item.result.lng)
                RecentSearchStore.putPlaceFields(obj, item.result)
                arr.put(obj)
            }
            prefs(context).edit().putString(KEY_PLACES, arr.toString()).apply()
            com.overdrive.app.briefing.app.TripBriefingSync.request(context)
        } catch (e: Throwable) {
            Log.w(TAG, "persist failed: ${e.message}")
        }
    }
}
