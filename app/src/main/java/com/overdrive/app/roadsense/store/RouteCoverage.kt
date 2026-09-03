package com.overdrive.app.roadsense.store

import org.json.JSONObject
import java.io.File
import com.overdrive.app.util.ScratchPaths

/**
 * Tracks which ~50 m map tiles this device has actually DRIVEN THROUGH, so the
 * overlay can honestly distinguish two very different "no hazard ahead" states:
 *
 *   • "Road mapped, clear ahead" — we've driven this stretch before and found no
 *     hazard, so a clear readout is TRUSTWORTHY.
 *   • "New road" — we've never driven here, so "no hazard" means "no data yet",
 *     NOT "confirmed clear". The user shouldn't read an unmapped road as safe.
 *
 * This is a SEPARATE signal from VehicleCalibrator maturity: calibration is about
 * the CAR (is our severity judgment trustworthy), coverage is about the ROAD (have
 * we surveyed this stretch). Conflating them is why "Route calibrated" was
 * misleading — that label is really vehicle calibration; THIS is route coverage.
 *
 * ## Why tile pass-counts (not hazard rows)
 * Hazard confidence/observations only exist where hazards ARE. A smooth road
 * driven ten times has zero rows — indistinguishable from a never-seen road if we
 * only look at hazards. Coverage must be recorded for EVERY tile we pass, hazard
 * or not. So we keep a tile→passCount map: each tile we enter while DRIVING gets
 * its count bumped (once per entry, not per sample). A tile with ≥
 * [CONFIRMED_PASSES] passes is "mapped" (we've surveyed it enough to trust a clear
 * reading); 1..<CONFIRMED is "seen once"; 0 is "new".
 *
 * ## Cost / persistence
 * Persisted in its OWN file [COVERAGE_PATH], NOT in the shared overdrive_config.json
 * (audit storage HIGH-2): the tile→count map can reach ~240 KB at the cap, and the
 * shared config is rewritten in full on EVERY updateSection by ANY subsystem — so
 * keeping coverage there would make every unrelated config write drag the whole map,
 * and the daemon's ~3 s overlay heartbeat would rewrite 240 KB 20×/min. A dedicated
 * file means coverage is written only on its own throttle and bloats nothing else.
 * Capped at [MAX_TILES] (recency-aware prune, see [persistIfDirty]). Writes are
 * coalesced: persist only when a NEW tile is entered, and the caller further
 * throttles. All mutation is on the daemon tick/IPC thread; the map is guarded by
 * [lock] because [clear] can be called from the HTTP handler thread (delete-local).
 */
class RouteCoverage {

    private val lock = Any()
    /** tile → (passCount, lastSeenMs). lastSeenMs drives the recency-aware prune so a
     *  long one-way trip's fresh tiles aren't evicted in favour of stale high-count
     *  home tiles (audit storage MED-3). */
    private val passes = HashMap<Long, Entry>()
    private var loaded = false
    /** Recent tiles (most-recent-last) to dedupe boundary ping-pong: GPS jitter on a
     *  tile boundary can bounce A→B→A→B; bumping on each re-entry would falsely push a
     *  tile to MAPPED in one drive (audit accuracy S5). We only count a tile as a new
     *  entry if it's not among the last [RECENT_GUARD] tiles. */
    private val recentTiles = ArrayDeque<Long>()
    private var dirtySinceLoad = false

    private data class Entry(val count: Int, val lastSeenMs: Long)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val f = File(COVERAGE_PATH)
            if (!f.exists()) return
            val obj = JSONObject(f.readText())
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val tile = k.toLongOrNull() ?: continue
                // Value is "count:lastSeenMs"; tolerate a bare count from an older file.
                val v = obj.optString(k, "")
                val parts = v.split(':')
                val count = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val seen = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                passes[tile] = Entry(count, seen)
            }
        } catch (_: Throwable) { /* corrupt/missing → start empty */ }
    }

    /**
     * Record that we're driving through the tile containing [lat],[lng] at [nowMs].
     * Bumps the pass count ONCE per genuine tile ENTRY — a tile among the last
     * [RECENT_GUARD] tiles is treated as "still here / boundary bounce" and NOT
     * re-counted (audit S5). Returns true if a NEW tile was recorded (so the caller
     * can decide to persist). Cheap: HashMap + small deque, no I/O.
     */
    fun record(lat: Double, lng: Double, nowMs: Long): Boolean = synchronized(lock) {
        ensureLoaded()
        val tile = SpatialIndex.tileKey(lat, lng)
        if (recentTiles.contains(tile)) return false  // still here / boundary ping-pong
        recentTiles.addLast(tile)
        while (recentTiles.size > RECENT_GUARD) recentTiles.removeFirst()
        val prev = passes[tile]
        passes[tile] = Entry((prev?.count ?: 0) + 1, nowMs)
        dirtySinceLoad = true
        true
    }

    /** Coverage level for the tile containing [lat],[lng]: NEW / SEEN / MAPPED. */
    fun levelAt(lat: Double, lng: Double): Level = synchronized(lock) {
        ensureLoaded()
        val c = passes[SpatialIndex.tileKey(lat, lng)]?.count ?: 0
        when {
            c >= CONFIRMED_PASSES -> Level.MAPPED
            c >= 1 -> Level.SEEN
            else -> Level.NEW
        }
    }

    /** Persist the coverage map to its own file if it changed. Caller throttles. */
    fun persistIfDirty(): Unit = synchronized(lock) {
        if (!dirtySinceLoad) return@synchronized
        dirtySinceLoad = false
        // Recency-aware cap (audit MED-3): when over the cap, evict the LEAST-recently-
        // seen tiles, not the lowest-count ones — so the current trip's fresh tiles
        // survive even if they're count=1, and only genuinely old history is dropped.
        if (passes.size > MAX_TILES) {
            val keep = passes.entries.sortedByDescending { it.value.lastSeenMs }.take(MAX_TILES)
            passes.clear()
            keep.forEach { passes[it.key] = it.value }
        }
        try {
            val obj = JSONObject()
            for ((tile, e) in passes) obj.put(tile.toString(), "${e.count}:${e.lastSeenMs}")
            // Atomic-ish write: tmp + rename so a crash mid-write can't truncate the file.
            val tmp = File("$COVERAGE_PATH.tmp")
            tmp.writeText(obj.toString())
            tmp.renameTo(File(COVERAGE_PATH))
        } catch (_: Throwable) { /* best-effort */ }
    }

    /** Wipe coverage on delete-local (R-SET-5). HTTP handler thread → locked. */
    fun clear() = synchronized(lock) {
        ensureLoaded()
        passes.clear()
        recentTiles.clear()
        dirtySinceLoad = true
        persistIfDirty()
    }

    enum class Level { NEW, SEEN, MAPPED }

    companion object {
        /** Own file (NOT the shared overdrive_config.json) — see class doc. Sits in
         *  the same daemon-writable dir as the H2 stores. */
        private val COVERAGE_PATH = ScratchPaths.path("overdrive_roadsense_coverage.json")
        /** Tiles to remember for the boundary-bounce guard (audit S5). ~3 covers a
         *  GPS jitter ping-pong across one boundary without blocking a genuine
         *  re-entry after driving away and coming back later in the trip. */
        private const val RECENT_GUARD = 3
        /** Passes through a tile to consider it "mapped" (a clear reading there is
         *  trustworthy). 2 = driven at least twice — enough that a one-off GPS/sensor
         *  hiccup on a single pass doesn't mark a hazard-free stretch confirmed-clear.
         *  PROVISIONAL. */
        const val CONFIRMED_PASSES = 2
        /** Cap on the persisted tile map (~50 m tiles; 8000 ≈ a very large commute
         *  footprint). Mirrors TileCursor's bound. */
        const val MAX_TILES = 8000
    }
}
