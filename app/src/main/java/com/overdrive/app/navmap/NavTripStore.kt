package com.overdrive.app.navmap

import android.content.Context
import android.util.Log
import com.overdrive.app.navmap.nav.SearchResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the user's CURRENT trip itinerary (the ordered list of stops after the
 * origin: [stop1, …, destination]) so leaving the map and coming back RESTORES the
 * trip instead of dropping the user back at an empty search box.
 *
 * <p>Why a store and not just savedInstanceState: the user explicitly wants the
 * itinerary to survive *leaving* the Activity (back to the app shell, or even the
 * Activity being destroyed under memory pressure / a daemon restart), not only a
 * config change. So it's a small persisted JSON blob, written whenever the
 * itinerary changes and cleared when the trip is abandoned / arrived.
 *
 * <p>Only the itinerary stops are persisted — NOT the computed polyline. The
 * origin is always the live GPS fix, and the route is recomputed fresh on
 * restore (a stale polyline from an old origin would be wrong anyway). This keeps
 * the blob tiny and always-valid.
 *
 * <p>Mirrors [RecentSearchStore] / [SavedPlacesStore]: one JSON key in a dedicated
 * prefs file, all reads/writes defensive (never throw).
 */
object NavTripStore {

    private const val TAG = "NavTripStore"
    private const val PREFS_NAME = "navmap_active_trip"
    private const val KEY_STOPS = "trip_stops"
    /** Whether the persisted trip was actively navigating (vs just previewed). */
    private const val KEY_NAVIGATING = "trip_navigating"
    private const val KEY_SET_OUT = "trip_set_out"
    /** elapsedRealtime of a Trip Briefing yes, and the boot it was in: a one-shot request to start guidance. */
    private const val KEY_START_AT = "trip_start_at"
    private const val KEY_START_BOOT = "trip_start_boot"
    /** When guidance last ran for the saved trip (elapsedRealtime, its boot, and the wall clock across a reboot). */
    private const val KEY_TICK_AT = "trip_tick_at"
    private const val KEY_TICK_BOOT = "trip_tick_boot"
    private const val KEY_TICK_WALL = "trip_tick_wall"
    /** When guidance last stopped: the car was by its destination (no stop left), or standing by its next stop. */
    private const val KEY_LEFT_AT_DEST = "trip_left_at_dest"
    private const val KEY_LEFT_AT_STOP = "trip_left_at_stop"
    private const val KEY_LEFT_STANDING = "trip_left_standing"
    // where the car was when the trip's last stop was done with ("lat,lng")
    private const val KEY_STOP_LEFT_AT = "trip_stop_left_at"
    /** A reboot since guidance last ran counts as at least this long away. */
    private const val REBOOT_AWAY_MS = 15 * 60_000L

    private const val KEY_LABEL = "label"
    /** Intent extra: an open map restores the saved trip again (Trip Briefing saved one that asks to start). */
    const val EXTRA_RESTORE_TRIP = "com.overdrive.app.navmap.RESTORE_TRIP"
    private const val KEY_LAT = "lat"
    private const val KEY_LNG = "lng"

    /** A restored trip: the ordered itinerary + whether guidance was running. */
    data class Trip(
        val stops: List<SearchResult>,
        val navigating: Boolean,
        /** Guidance had got under way (part of the route behind the car) before it was saved. */
        val setOut: Boolean = false,
        /** How long ago guidance last ran for it, −1 when unknown (a reboot counts as a long while); and as measured. */
        val awayMs: Long = -1L,
        val measuredAwayMs: Long = -1L,
        /** When it stopped, the car was by the destination with no stop left / standing by the next stop. */
        val leftAtDest: Boolean = false,
        val leftAtStop: Boolean = false,
        /** …with the car standing then (parked, not passing). */
        val leftStanding: Boolean = false,
        /** Where the car was when the last stop was done with, or null. */
        val stopLeftAt: com.overdrive.app.navmap.nav.GeoPoint? = null
    )

    /**
     * Persist the current itinerary. [stops] is the ordered list AFTER the origin
     * ([stop1, …, destination]); an empty list clears the saved trip. [navigating]
     * records whether turn-by-turn was active so a restore can resume immersive
     * follow vs just re-show the route preview. Never throws.
     */
    fun save(context: Context, stops: List<SearchResult>, navigating: Boolean, startRequest: Boolean = false,
             setOut: Boolean = false) {
        try {
            if (stops.isEmpty()) { clear(context); return }
            val arr = JSONArray()
            for (s in stops) {
                if (s.label.isBlank()) continue
                if (s.lat.isNaN() || s.lng.isNaN()) continue
                val obj = JSONObject()
                    .put(KEY_LABEL, s.label)
                    .put(KEY_LAT, s.lat)
                    .put(KEY_LNG, s.lng)
                RecentSearchStore.putPlaceFields(obj, s)
                arr.put(obj)
            }
            val p = prefs(context)
            val edit = p.edit()
            // (where the car was left and when guidance last ran belong to the stops they were noted for)
            if (startRequest || p.getString(KEY_STOPS, null) != arr.toString()) {
                edit.remove(KEY_LEFT_AT_DEST).remove(KEY_LEFT_AT_STOP).remove(KEY_LEFT_STANDING)
            }
            // (a new trip's: none yet)
            if (startRequest) edit.remove(KEY_TICK_AT).remove(KEY_TICK_BOOT).remove(KEY_TICK_WALL).remove(KEY_STOP_LEFT_AT)
            edit.putString(KEY_STOPS, arr.toString())
                .putBoolean(KEY_NAVIGATING, navigating)
                .putBoolean(KEY_SET_OUT, setOut)
            // Any other save replaces the trip, and with it the request.
            if (startRequest) {
                edit.putLong(KEY_START_AT, android.os.SystemClock.elapsedRealtime()).putInt(KEY_START_BOOT, bootCount(context))
            } else {
                edit.remove(KEY_START_AT).remove(KEY_START_BOOT)
            }
            edit.apply()
        } catch (e: Throwable) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    /** Load the persisted trip, or null if none / corrupt / empty. Never throws. */
    fun load(context: Context): Trip? {
        return try {
            val raw = prefs(context).getString(KEY_STOPS, null)
            if (raw.isNullOrBlank()) return null
            val arr = JSONArray(raw)
            val stops = ArrayList<SearchResult>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val label = obj.optString(KEY_LABEL, "")
                if (label.isBlank()) continue
                val lat = obj.optDouble(KEY_LAT, Double.NaN)
                val lng = obj.optDouble(KEY_LNG, Double.NaN)
                if (lat.isNaN() || lng.isNaN()) continue
                stops.add(RecentSearchStore.readPlace(obj, label, lat, lng))
            }
            val p = prefs(context)
            if (stops.isEmpty()) null
            else Trip(
                stops, p.getBoolean(KEY_NAVIGATING, false), p.getBoolean(KEY_SET_OUT, false), awayMs(context),
                awayMs(context, measured = true),
                p.getBoolean(KEY_LEFT_AT_DEST, false), p.getBoolean(KEY_LEFT_AT_STOP, false),
                p.getBoolean(KEY_LEFT_STANDING, false), stopLeftAt(p.getString(KEY_STOP_LEFT_AT, null))
            )
        } catch (e: Throwable) {
            Log.w(TAG, "load failed: ${e.message}")
            null
        }
    }

    /** The saved trip carries a start request not yet taken (a briefing yes on its way to the map). Never throws. */
    fun hasStart(context: Context): Boolean = try {
        prefs(context).getLong(KEY_START_AT, 0L) > 0L
    } catch (e: Throwable) {
        false
    }

    /** True once, and only within [maxAgeMs] of it, for a trip saved with a start request. Never throws. */
    fun takeStart(context: Context, maxAgeMs: Long): Boolean = try {
        val p = prefs(context)
        val at = p.getLong(KEY_START_AT, 0L)
        val boot = p.getInt(KEY_START_BOOT, -1)
        if (at > 0L) p.edit().remove(KEY_START_AT).remove(KEY_START_BOOT).apply()
        // a monotonic clock, so a time fix at car start can't void (or revive) it; only within the same boot
        at > 0L && boot == bootCount(context) && android.os.SystemClock.elapsedRealtime() - at in 0L..maxAgeMs
    } catch (e: Throwable) {
        false
    }

    private fun bootCount(context: Context): Int = try {
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    } catch (e: Throwable) {
        -1
    }

    /** The trip's last stop was done with, the car at (lat, lng). Never throws. */
    fun stopLeft(context: Context, lat: Double, lng: Double) {
        if (lat.isNaN() || lng.isNaN()) return
        try {
            prefs(context).edit().putString(KEY_STOP_LEFT_AT, "$lat,$lng").apply()
        } catch (e: Throwable) {
            Log.w(TAG, "stopLeft failed: ${e.message}")
        }
    }

    /** No stop of the saved trip has been done with yet. Never throws. */
    fun clearStopLeft(context: Context) {
        try {
            prefs(context).edit().remove(KEY_STOP_LEFT_AT).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "clearStopLeft failed: ${e.message}")
        }
    }

    private fun stopLeftAt(raw: String?): com.overdrive.app.navmap.nav.GeoPoint? {
        val parts = raw?.split(',') ?: return null
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return com.overdrive.app.navmap.nav.GeoPoint(lat, lng)
    }

    /**
     * Guidance is running for the saved trip now (stamped every so often, so a restore knows how long it was away), or
     * just stopped with the car by its destination / standing by its next stop. Never throws.
     */
    fun touch(context: Context, leftAtDest: Boolean = false, leftAtStop: Boolean = false, standing: Boolean = false) {
        try {
            prefs(context).edit()
                .putLong(KEY_TICK_AT, android.os.SystemClock.elapsedRealtime())
                .putInt(KEY_TICK_BOOT, bootCount(context))
                .putLong(KEY_TICK_WALL, System.currentTimeMillis())
                .putBoolean(KEY_LEFT_AT_DEST, leftAtDest)
                .putBoolean(KEY_LEFT_AT_STOP, leftAtStop)
                .putBoolean(KEY_LEFT_STANDING, standing)
                .apply()
        } catch (e: Throwable) {
            Log.w(TAG, "touch failed: ${e.message}")
        }
    }

    // The monotonic clock within a boot. Across a reboot the wall clock, which may not be set yet: a reboot counts as
    // a long while anyway (the head unit was off, or restarted).
    private fun awayMs(context: Context, measured: Boolean = false): Long {
        val p = prefs(context)
        val at = p.getLong(KEY_TICK_AT, 0L)
        if (at <= 0L) return -1L
        val now = android.os.SystemClock.elapsedRealtime()
        // (with no boot counter, a clock behind the stamp is a reboot)
        val sameBoot = p.getInt(KEY_TICK_BOOT, -1) == bootCount(context) && (bootCount(context) != -1 || now >= at)
        if (sameBoot) return (now - at).takeIf { it >= 0L } ?: -1L
        val wall = p.getLong(KEY_TICK_WALL, 0L)
        val byWall = if (wall > 0L) (System.currentTimeMillis() - wall).takeIf { it >= 0L } ?: -1L else -1L
        return if (measured) byWall else maxOf(byWall, REBOOT_AWAY_MS)
    }

    /** Clear the saved trip (on arrival / cancel). Never throws. */
    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(KEY_STOPS).remove(KEY_NAVIGATING).remove(KEY_SET_OUT).remove(KEY_START_AT)
                .remove(KEY_START_BOOT).remove(KEY_TICK_AT).remove(KEY_TICK_BOOT).remove(KEY_TICK_WALL)
                .remove(KEY_LEFT_AT_DEST).remove(KEY_LEFT_AT_STOP).remove(KEY_LEFT_STANDING).remove(KEY_STOP_LEFT_AT).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "clear failed: ${e.message}")
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
