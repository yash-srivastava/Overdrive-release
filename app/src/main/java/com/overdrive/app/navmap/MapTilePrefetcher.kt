package com.overdrive.app.navmap

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionDefinition
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max

/**
 * MapTilePrefetcher — self-contained MapLibre offline tile prefetch helper for the RoadSense map.
 *
 * ## What this does
 * Around a given location (camera-idle / GPS fix) it creates a small MapLibre "offline region"
 * (a tile pyramid box a few km square, zoom 6..14) and downloads it into MapLibre's offline DB so
 * that subsequent pans/zooms in that area render from disk instead of re-fetching tiles over the
 * network. It also configures the ambient (LRU) tile cache size.
 *
 * ## Why NOT WorkManager
 * OfflineManager performs its downloads on its OWN background threads inside the MapLibre native
 * file source — there is nothing for WorkManager to "do". WorkManager is the wrong tool here:
 *   - It is session/constraint-scheduled and designed to *start later*; we want prefetch to ride
 *     along the live map session while the user is actually looking at this area.
 *   - A WJ job is meant to outlive the screen; an offline-region download is intrinsically tied to
 *     the map being open (we tear it down when complete and never want it resurrected in the bg).
 *   - This app targets SDK 25 with a deliberately minimal background posture; adding WorkManager
 *     scheduling here would fight that. This was an explicit design decision.
 * So we kick the download directly and let MapLibre's native threads own it.
 *
 * ## 20s debounce ({@link #schedulePrefetch})
 * The map fires camera-idle / location-fix events constantly. Prefetching on every one of them would
 * (a) contend with the live render for the same native file source and stutter the map, and (b)
 * re-create overlapping regions over and over. We post the real work to the main looper with a 20s
 * delay and cancel any pending schedule first, so prefetch only fires once the user has settled on an
 * area. We additionally skip if the new center is within ~200m of the last prefetched center.
 *
 * ## Metered-network gate
 * Auto-prefetch silently downloads several MB of tiles. On a metered (cellular / hotspot) connection
 * that is the user's data, so we skip the download entirely when {@link ConnectivityManager#isActiveNetworkMetered}
 * reports metered (controlled by {@link #allowMetered}, default false).
 *
 * ## LRU region cap
 * Offline regions accumulate in the DB forever otherwise. {@link #pruneRegions} keeps at most
 * {@code maxRegions} (default 8), deleting the oldest by their metadata timestamp before we add a new
 * one. The ambient cache (separate from regions) is bounded once via {@link #configureAmbientCache}.
 *
 * ## Threading / safety
 * Every MapLibre offline call is async and callback-driven; nothing here blocks. Every public entry
 * point is wrapped in try/catch and logs via android.util.Log — this helper MUST NEVER crash the
 * caller (the map Activity). All MapLibre interaction happens on the main thread (OfflineManager
 * delivers its callbacks on the main looper).
 *
 * ## Wiring (done by another task)
 * The RoadSense map Activity calls {@link #configureAmbientCache} once on map-ready, then
 * {@link #schedulePrefetch} on camera-idle / location-fix. This object only exposes that clean API;
 * it does not touch the Activity.
 */
object MapTilePrefetcher {

    private const val TAG = "MapTilePrefetcher"

    /** 256 MB ambient (LRU) tile cache. */
    private const val AMBIENT_CACHE_BYTES = 268435456L

    /** Half-extent of the prefetch box in degrees (~5.5 km N/S; E/W scaled by latitude). */
    private const val BOX_HALF_DEG = 0.05

    private const val MIN_ZOOM = 6.0
    private const val MAX_ZOOM = 14.0

    /** Debounce delay before a scheduled prefetch actually fires. */
    private const val DEBOUNCE_MS = 20_000L

    /** Skip a new prefetch if its center is within this distance of the last prefetched center. */
    private const val DEDUP_METERS = 200.0

    private const val DEFAULT_MAX_REGIONS = 8

    /** A frequent place's box (~5.5 km square): the streets around it, not the whole town. */
    private const val PLACE_HALF_DEG = 0.025
    /** At most this many frequent places kept offline (each a few MB of the ambient cache). */
    private const val MAX_PLACE_REGIONS = 12
    /** A place this near one already prefetched is covered by it. */
    private const val PLACE_SAME_M = 1_000.0
    /** A place's region is fetched again after this long (the basemap is updated weekly). */
    private const val PLACE_REFRESH_MS = 60L * 24 * 60 * 60_000L
    private const val TYPE_LOCATE = "roadsense"
    private const val TYPE_PLACE = "place"
    /** Regions saved under the build-free vector tile URLs (VectorTileKeys); older ones no longer match the map. */
    private const val KEYS = 1

    private const val METERS_PER_DEG_LAT = 111_320.0

    /** If true, prefetch is allowed on metered networks. Default false (don't burn cellular data). */
    @Volatile
    var allowMetered: Boolean = false

    // --- mutable state (all touched on the main thread) ---

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Token for the currently-scheduled (debounced) prefetch, so we can cancel it. */
    private var pendingPrefetch: Runnable? = null

    /** True while an offline-region download is in flight; gates against overlapping downloads. */
    @Volatile
    private var inFlight: Boolean = false

    /** Whether the ambient cache size has already been configured (idempotent). */
    @Volatile
    private var ambientConfigured: Boolean = false

    /** Last successfully-scheduled prefetch center, for the ~200m dedup. */
    private var lastCenterLat: Double = Double.NaN
    private var lastCenterLng: Double = Double.NaN

    // Frequent places still to fetch, one after another; and the boxes fully downloaded (for the offline fallback).
    private val placeQueue = ArrayDeque<Pair<Double, Double>>()
    private var placeStyleUrl: String = ""
    private var placePixelRatio: Float = 1f
    @Volatile
    private var doneBounds: List<LatLngBounds> = emptyList()

    /** Whether a fully downloaded region covers (lat, lng): the vector map has the streets there offline. */
    @JvmStatic
    fun coversOffline(lat: Double, lng: Double): Boolean =
        doneBounds.any { lat <= it.latitudeNorth && lat >= it.latitudeSouth && lng <= it.longitudeEast && lng >= it.longitudeWest }

    /**
     * Fetch the streets around frequent places ([places]: saved ones, recent destinations) not yet offline, so the
     * map shows them with no network. Only when [unmetered] (Wi-Fi that isn't a phone's hotspot); one region at a time,
     * after any download in flight. Main thread.
     */
    @JvmStatic
    fun prefetchPlaces(context: Context, styleUrl: String, places: List<Pair<Double, Double>>, pixelRatio: Float, unmetered: Boolean) {
        try {
            val appCtx = context.applicationContext
            refreshCoverage(appCtx)
            if (!unmetered || places.isEmpty() || isMetered(appCtx)) return
            OfflineManager.getInstance(appCtx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    try {
                        val now = System.currentTimeMillis()
                        val wanted = ArrayList<Pair<Double, Double>>()
                        for (pl in places) {
                            if (wanted.size >= MAX_PLACE_REGIONS) break
                            if (wanted.none { haversineMeters(it.first, it.second, pl.first, pl.second) < PLACE_SAME_M }) wanted.add(pl)
                        }
                        val kept = ArrayList<Pair<Double, Double>>()
                        for (r in (offlineRegions ?: emptyArray()).filter { typeOf(it) == TYPE_PLACE }) {
                            val m = meta(r)
                            val at = Pair(m.optDouble("lat", Double.NaN), m.optDouble("lng", Double.NaN))
                            val stillWanted = wanted.any { haversineMeters(it.first, it.second, at.first, at.second) < PLACE_SAME_M }
                            // (no longer a frequent place, a download that never finished, saved under old tile URLs, or old)
                            if (!stillWanted || !m.optBoolean("done", false) || m.optInt("keys", 0) != KEYS ||
                                now - m.optLong("ts", 0L) > PLACE_REFRESH_MS) {
                                deleteRegion(r)
                            } else {
                                kept.add(at)
                            }
                        }
                        placeStyleUrl = styleUrl
                        placePixelRatio = pixelRatio
                        placeQueue.clear()
                        wanted.filter { w -> kept.none { haversineMeters(it.first, it.second, w.first, w.second) < PLACE_SAME_M } }
                            .forEach { placeQueue.addLast(it) }
                        if (placeQueue.isNotEmpty()) Log.i(TAG, "prefetchPlaces: ${placeQueue.size} place(s) to fetch")
                        nextPlace(appCtx)
                    } catch (t: Throwable) {
                        Log.e(TAG, "prefetchPlaces.onList failed", t)
                    }
                }

                override fun onError(error: String) {
                    Log.w(TAG, "prefetchPlaces list error: $error")
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "prefetchPlaces failed", t)
        }
    }

    // The next queued place, unless a download is running (its completion comes back here) or the network turned metered.
    private fun nextPlace(context: Context) {
        try {
            if (inFlight || placeQueue.isEmpty()) return
            if (isMetered(context)) { placeQueue.clear(); return }
            val (lat, lng) = placeQueue.removeFirst()
            startRegion(context, placeStyleUrl, lat, lng, PLACE_HALF_DEG, placePixelRatio, buildMetadata(System.currentTimeMillis(), TYPE_PLACE, lat, lng))
        } catch (t: Throwable) {
            inFlight = false
            Log.e(TAG, "nextPlace failed", t)
        }
    }

    // The fully downloaded regions' boxes, for [coversOffline].
    private fun refreshCoverage(context: Context) {
        try {
            OfflineManager.getInstance(context.applicationContext).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    doneBounds = (offlineRegions ?: emptyArray())
                        .filter { meta(it).let { m -> m.optBoolean("done", false) && m.optInt("keys", 0) == KEYS } }
                        .mapNotNull { it.definition.bounds }
                }

                override fun onError(error: String) {
                    Log.w(TAG, "refreshCoverage list error: $error")
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "refreshCoverage failed", t)
        }
    }

    /**
     * Set the ambient (LRU) tile cache to 256 MB. Idempotent: a guard flag ensures we only issue the
     * native call once per process. Safe to call on every map-ready.
     */
    @JvmStatic
    fun configureAmbientCache(context: Context) {
        try {
            refreshCoverage(context)
            if (ambientConfigured) return
            ambientConfigured = true
            val appCtx = context.applicationContext
            OfflineManager.getInstance(appCtx)
                .setMaximumAmbientCacheSize(
                    AMBIENT_CACHE_BYTES,
                    object : OfflineManager.FileSourceCallback {
                        override fun onSuccess() {
                            Log.i(TAG, "ambient cache size set to $AMBIENT_CACHE_BYTES bytes")
                        }

                        override fun onError(message: String) {
                            // Reset so a later attempt can retry.
                            ambientConfigured = false
                            Log.w(TAG, "setMaximumAmbientCacheSize error: $message")
                        }
                    }
                )
        } catch (t: Throwable) {
            ambientConfigured = false
            Log.e(TAG, "configureAmbientCache failed", t)
        }
    }

    /**
     * Debounced entry point. Posts the real prefetch [DEBOUNCE_MS] later, cancelling any prior pending
     * schedule. Skips immediately (no schedule) if the new center is within [DEDUP_METERS] of the last
     * prefetched center. This is the method the map Activity should call on camera-idle / location-fix.
     */
    @JvmStatic
    fun schedulePrefetch(
        context: Context,
        styleUrl: String,
        lat: Double,
        lng: Double,
        pixelRatio: Float
    ) {
        try {
            if (isWithinDedup(lat, lng)) {
                Log.d(TAG, "schedulePrefetch: center within ${DEDUP_METERS}m of last; skipping")
                return
            }
            val appCtx = context.applicationContext
            pendingPrefetch?.let { mainHandler.removeCallbacks(it) }
            val task = Runnable {
                pendingPrefetch = null
                prefetchAround(appCtx, styleUrl, lat, lng, pixelRatio)
            }
            pendingPrefetch = task
            mainHandler.postDelayed(task, DEBOUNCE_MS)
            Log.d(TAG, "schedulePrefetch: queued for ($lat,$lng) in ${DEBOUNCE_MS}ms")
        } catch (t: Throwable) {
            Log.e(TAG, "schedulePrefetch failed", t)
        }
    }

    /**
     * Cancel any pending (debounced) prefetch that hasn't fired yet, removing its
     * runnable from the main-looper handler. Called from the map Activity's
     * onDestroy so a queued prefetch doesn't fire after the screen is gone. Safe to
     * call when nothing is pending; does NOT touch an already-running download (that
     * lives on MapLibre's native threads and tears itself down via its observer).
     */
    @JvmStatic
    fun cancelPending() {
        try {
            pendingPrefetch?.let { mainHandler.removeCallbacks(it) }
            pendingPrefetch = null
        } catch (t: Throwable) {
            Log.e(TAG, "cancelPending failed", t)
        }
    }

    /**
     * Immediately prefetch a tile region around ([centerLat], [centerLng]). Builds a box ~5.5 km square,
     * prunes old regions to stay under the cap, creates the region with small JSON metadata, and starts
     * the download. The region's observer flips the download back to inactive on completion and clears
     * the in-flight flag on completion / error / tile-count-limit.
     *
     * Normally callers use [schedulePrefetch]; this is public for direct/immediate prefetch.
     */
    @JvmStatic
    fun prefetchAround(
        context: Context,
        styleUrl: String,
        centerLat: Double,
        centerLng: Double,
        pixelRatio: Float
    ) {
        try {
            if (inFlight) {
                Log.d(TAG, "prefetchAround: a download is already in flight; skipping")
                return
            }
            if (isMetered(context)) {
                Log.i(TAG, "prefetchAround: active network is metered and allowMetered=false; skipping")
                return
            }

            val appCtx = context.applicationContext

            // Keep region count bounded before adding a new one.
            pruneRegions(appCtx, DEFAULT_MAX_REGIONS)

            startRegion(appCtx, styleUrl, centerLat, centerLng, BOX_HALF_DEG, pixelRatio,
                buildMetadata(System.currentTimeMillis(), TYPE_LOCATE, centerLat, centerLng)) {
                // Record the center only once the region is actually created/started.
                lastCenterLat = centerLat
                lastCenterLng = centerLng
            }
        } catch (t: Throwable) {
            inFlight = false
            Log.e(TAG, "prefetchAround failed", t)
        }
    }

    // A tile-pyramid region [halfDeg] each way of (centerLat, centerLng), downloaded now.
    private fun startRegion(
        context: Context, styleUrl: String, centerLat: Double, centerLng: Double, halfDeg: Double, pixelRatio: Float,
        metadata: ByteArray, onStarted: (() -> Unit)? = null
    ) {
        // Box around center. Longitude degrees shrink with latitude, so scale E/W by cos(lat) to
        // keep the box roughly square in meters.
        val lonHalf = halfDeg / max(0.01, cos(Math.toRadians(centerLat)))
        val bounds: LatLngBounds = LatLngBounds.from(centerLat + halfDeg, centerLng + lonHalf, centerLat - halfDeg, centerLng - lonHalf)
        val definition: OfflineRegionDefinition = OfflineTilePyramidRegionDefinition(styleUrl, bounds, MIN_ZOOM, MAX_ZOOM, pixelRatio)
        inFlight = true
        OfflineManager.getInstance(context.applicationContext).createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    try {
                        offlineRegion.setObserver(makeObserver(context.applicationContext, offlineRegion))
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                        onStarted?.invoke()
                        Log.i(TAG, "prefetch started around ($centerLat,$centerLng) z$MIN_ZOOM..$MAX_ZOOM")
                    } catch (t: Throwable) {
                        inFlight = false
                        Log.e(TAG, "onCreate: failed to start download", t)
                    }
                }

                override fun onError(error: String) {
                    inFlight = false
                    Log.w(TAG, "createOfflineRegion error: $error")
                }
            }
        )
    }

    /**
     * Region LRU cap. Lists offline regions; if more than [maxRegions] exist, deletes the oldest (by the
     * "ts" field in their JSON metadata) down to [maxRegions] - 1, leaving room for the one about to be
     * created. Fully async; safe and best-effort. Regions with unparseable metadata sort as oldest.
     */
    @JvmStatic
    fun pruneRegions(context: Context, maxRegions: Int = DEFAULT_MAX_REGIONS) {
        try {
            val appCtx = context.applicationContext
            OfflineManager.getInstance(appCtx).listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        try {
                            // (the Locate regions only: frequent places have their own cap)
                            val all = offlineRegions?.filter { typeOf(it) == TYPE_LOCATE } ?: return
                            // (saved under old tile URLs: they no longer match the map)
                            val (regions, stale) = all.partition { meta(it).optInt("keys", 0) == KEYS }
                            stale.forEach { deleteRegion(it) }
                            // Keep room for the new region we are about to create.
                            val keep = max(0, maxRegions - 1)
                            if (regions.size <= keep) return

                            val sorted = regions.sortedBy { tsOf(it) }
                            val deleteCount = regions.size - keep
                            for (i in 0 until deleteCount) {
                                deleteRegion(sorted[i])
                            }
                            Log.i(TAG, "pruneRegions: ${regions.size} regions, deleting $deleteCount to fit cap $maxRegions")
                        } catch (t: Throwable) {
                            Log.e(TAG, "pruneRegions.onList failed", t)
                        }
                    }

                    override fun onError(error: String) {
                        Log.w(TAG, "listOfflineRegions error: $error")
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "pruneRegions failed", t)
        }
    }

    // --- internals ---

    private fun makeObserver(context: Context, region: OfflineRegion): OfflineRegion.OfflineRegionObserver =
        object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                try {
                    if (status.isComplete) {
                        // Stop the download and release the in-flight gate.
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        inFlight = false
                        Log.i(
                            TAG,
                            "prefetch complete: ${status.completedResourceCount}/${status.requiredResourceCount} resources"
                        )
                        // (complete: it counts for the offline fallback) then the next place
                        markDone(context, region)
                        nextPlace(context)
                    }
                } catch (t: Throwable) {
                    inFlight = false
                    Log.e(TAG, "onStatusChanged failed", t)
                }
            }

            override fun onError(error: OfflineRegionError) {
                try {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                } catch (t: Throwable) {
                    Log.e(TAG, "onError: stop failed", t)
                } finally {
                    inFlight = false
                    // (a failed place waits for the next map session)
                    placeQueue.clear()
                    Log.w(TAG, "prefetch error: reason=${error.reason} message=${error.message}")
                }
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                try {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                } catch (t: Throwable) {
                    Log.e(TAG, "tileCountLimit: stop failed", t)
                } finally {
                    inFlight = false
                    placeQueue.clear()
                    Log.w(TAG, "prefetch stopped: tile count limit ($limit) exceeded")
                }
            }
        }

    private fun markDone(context: Context, region: OfflineRegion) {
        try {
            val m = meta(region).put("done", true)
            region.updateMetadata(m.toString().toByteArray(Charsets.UTF_8), object : OfflineRegion.OfflineRegionUpdateMetadataCallback {
                override fun onUpdate(metadata: ByteArray) {
                    refreshCoverage(context)
                }

                override fun onError(error: String) {
                    Log.w(TAG, "markDone error: $error")
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "markDone failed", t)
        }
    }

    private fun meta(region: OfflineRegion): JSONObject = try {
        JSONObject(String(region.metadata ?: ByteArray(0), Charsets.UTF_8))
    } catch (t: Throwable) {
        JSONObject()
    }

    // A region's kind; the oldest ones carry none: Locate's.
    private fun typeOf(region: OfflineRegion): String = meta(region).optString("type", TYPE_LOCATE)

    private fun deleteRegion(region: OfflineRegion) {
        try {
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    Log.d(TAG, "pruned offline region id=${region.id}")
                }

                override fun onError(error: String) {
                    Log.w(TAG, "delete region id=${region.id} error: $error")
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "deleteRegion failed", t)
        }
    }

    private fun buildMetadata(ts: Long, type: String, lat: Double, lng: Double): ByteArray {
        val json = JSONObject()
        json.put("type", type)
        json.put("ts", ts)
        json.put("lat", lat)
        json.put("lng", lng)
        json.put("keys", KEYS)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /** Parse the "ts" out of a region's JSON metadata; unparseable/missing sorts oldest (0). */
    private fun tsOf(region: OfflineRegion): Long {
        return try {
            val raw = region.metadata ?: return 0L
            val json = JSONObject(String(raw, Charsets.UTF_8))
            json.optLong("ts", 0L)
        } catch (t: Throwable) {
            0L
        }
    }

    private fun isWithinDedup(lat: Double, lng: Double): Boolean {
        if (lastCenterLat.isNaN() || lastCenterLng.isNaN()) return false
        return haversineMeters(lastCenterLat, lastCenterLng, lat, lng) < DEDUP_METERS
    }

    private fun isMetered(context: Context): Boolean {
        if (allowMetered) return false
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            cm.isActiveNetworkMetered
        } catch (t: Throwable) {
            // If we cannot tell, be conservative and treat as metered (skip prefetch).
            Log.w(TAG, "isMetered check failed; treating as metered", t)
            true
        }
    }

    /** Small-distance great-circle approximation in meters (good enough for the 200m dedup). */
    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * METERS_PER_DEG_LAT
        val dLng = (lng2 - lng1) * METERS_PER_DEG_LAT * cos(Math.toRadians((lat1 + lat2) / 2.0))
        return Math.hypot(dLat, dLng)
    }
}
