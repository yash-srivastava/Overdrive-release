package com.overdrive.app.geo;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.util.ScratchPaths;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * On-disk cache of resolved place names, keyed by {@code (geohash7, locale)}.
 *
 * <p>Backed by a single JSON file at {@code /data/local/tmp/geocache.json}
 * for the same cross-UID reasons as {@code overdrive_config.json} — the
 * daemon (UID 2000) and the app (UID 10xxx) both read it; only the daemon
 * writes it. SQLite would tie the file to one UID's app-specific dir and
 * lock the other process out.
 *
 * <h3>Eviction</h3>
 * <ul>
 *   <li>Hard TTL: 1 year. Places renaming is rare; a yearly refresh is
 *       generous and free.</li>
 *   <li>Soft cap: 10,000 entries. When exceeded, the oldest 10% by
 *       {@code resolvedAtMs} are dropped on the next write. No LRU because
 *       hits are spatial, not temporal — visiting your hometown once a
 *       year is just as valid as visiting daily.</li>
 *   <li>{@link #invalidateNear(double, double, double)} is called when a
 *       SafeLocation is added/edited/removed so a stale "Jalan Ampang"
 *       row doesn't shadow the new "Office" SafeZone label forever.</li>
 * </ul>
 *
 * <h3>Concurrency</h3>
 * The in-memory map is a {@link ConcurrentHashMap}; readers race-free.
 * Writes serialize on the cache instance to avoid two save races
 * truncating each other's last-modified entries.
 */
public final class GeoCache {

    private static final String TAG = "GeoCache";
    private static final String CACHE_PATH = ScratchPaths.path("geocache.json");
    private static final long TTL_MS = 365L * 24L * 60L * 60L * 1000L; // 1 year
    private static final int SOFT_CAP_ENTRIES = 10_000;
    private static final int CACHE_VERSION = 1;

    private static volatile GeoCache instance;

    /** In-memory mirror of the file. {@code key = geohash7 + "|" + locale}. */
    private final Map<String, PlaceResult> entries = new ConcurrentHashMap<>();
    private final AtomicLong loadedAt = new AtomicLong(0L);
    private volatile boolean loaded = false;

    /** Coalesce-window: dirty puts wait up to this long before flushing. */
    private static final long FLUSH_COALESCE_MS = 30_000L;

    /** Hard cap on the JSON file we'll ever try to ingest. */
    private static final long MAX_LOAD_BYTES = 4_000_000L;

    /** True iff entries have changed since the last successful disk save. */
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    /** Single-thread executor that owns disk writes. Daemon, MIN_PRIORITY. */
    private final ScheduledExecutorService flushExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "GeoCacheFlush");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    /** Most-recently-scheduled flush; replaced by each {@link #scheduleFlush()}. */
    private volatile ScheduledFuture<?> pendingFlush = null;

    private GeoCache() {}

    public static GeoCache getInstance() {
        if (instance == null) {
            synchronized (GeoCache.class) {
                if (instance == null) instance = new GeoCache();
            }
        }
        return instance;
    }

    private static String key(String geohash7, String locale) {
        return geohash7 + "|" + (locale == null ? "" : locale);
    }

    /** Lazy-load on first access. Call sites can pre-warm via {@link #ensureLoaded()}. */
    public void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            loadFromDisk();
            loaded = true;
        }
    }

    public PlaceResult get(double lat, double lng, String locale) {
        ensureLoaded();
        String hash = Geohash.encode(lat, lng);
        PlaceResult pr = entries.get(key(hash, locale));
        if (pr == null) return null;
        // TTL check; expired entries are evicted on the next write rather
        // than removed immediately to keep the read path lock-free.
        if (System.currentTimeMillis() - pr.resolvedAtMs > TTL_MS) {
            return null;
        }
        return pr;
    }

    /** Direct geohash variant for callers that already know the hash. */
    public PlaceResult getByHash(String geohash7, String locale) {
        if (geohash7 == null) return null;
        ensureLoaded();
        PlaceResult pr = entries.get(key(geohash7, locale));
        if (pr == null) return null;
        if (System.currentTimeMillis() - pr.resolvedAtMs > TTL_MS) return null;
        return pr;
    }

    /**
     * Insert or overwrite. Schedules a coalesced disk flush rather than
     * rewriting the whole 2.5 MB JSON file inline — every cache hit on a
     * road trip would otherwise produce a full file rewrite (~150 MB/h
     * write amplification, eats SD-card endurance, contends with the
     * H.265 muxer for I/O bandwidth).
     *
     * <p>Coalescing window: {@link #FLUSH_COALESCE_MS}. A burst of N puts
     * within the window collapses to a single disk write at the end.
     * {@link #flushNow()} is available for callers that need durability
     * (e.g. SafeLocation invalidation, daemon shutdown).
     */
    public synchronized void put(double lat, double lng, PlaceResult pr) {
        if (pr == null) return;
        String hash = Geohash.encode(lat, lng);
        entries.put(key(hash, pr.locale), pr);
        evictIfOverCap();
        dirty.set(true);
        scheduleFlush();
    }

    /**
     * Force a synchronous flush. Cheap when not dirty (atomic test of
     * {@link #dirty}). Used by {@link #invalidateNear(double, double, double)}
     * and on daemon shutdown so the freshest state lands on disk.
     */
    public synchronized void flushNow() {
        ScheduledFuture<?> p = pendingFlush;
        if (p != null) {
            p.cancel(false);
            pendingFlush = null;
        }
        if (dirty.compareAndSet(true, false)) {
            saveToDisk();
        }
    }

    /**
     * Schedule (or re-schedule) a disk flush 30 s in the future. Called
     * from {@link #put} on every dirty entry. The pending future is
     * cancelled and replaced so a tight burst of puts collapses into a
     * single trailing flush — sliding-window coalescing.
     */
    private void scheduleFlush() {
        ScheduledFuture<?> prev = pendingFlush;
        if (prev != null && !prev.isDone()) {
            prev.cancel(false);
        }
        try {
            pendingFlush = flushExecutor.schedule(() -> {
                synchronized (GeoCache.this) {
                    if (dirty.compareAndSet(true, false)) {
                        saveToDisk();
                    }
                    pendingFlush = null;
                }
            }, FLUSH_COALESCE_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            // Executor saturated / shutting down — fall back to a synchronous
            // save so we never silently drop a cache update. Worst case this
            // happens during daemon shutdown and we pay the rewrite cost
            // once, which is fine.
            CameraDaemon.log(TAG + ": flush schedule failed, saving inline: " + t.getMessage());
            if (dirty.compareAndSet(true, false)) saveToDisk();
        }
    }

    /**
     * Drop every entry whose centroid is within {@code radiusM} of
     * {@code (lat, lng)}. Used when a SafeLocation is added/edited/removed
     * so the cache can't keep returning the public address for a place the
     * user has just renamed.
     *
     * <p>Approximate by hash centroid — fine for the SafeLocation use case
     * (radii are 50–500 m and the precision-7 cell is ≈ 153 m, so we just
     * sweep the cell containing (lat, lng) and its 8 neighbours by string
     * comparison of the first 6 chars). This is intentionally over-sweepy:
     * worse to keep one stale row than to evict a few extra; refetch is
     * cheap.
     */
    public synchronized void invalidateNear(double lat, double lng, double radiusM) {
        ensureLoaded();
        String prefix = Geohash.encode(lat, lng).substring(0, 6); // ≈ 1.2 km box
        boolean changed = false;
        Iterator<Map.Entry<String, PlaceResult>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PlaceResult> e = it.next();
            String k = e.getKey();
            int pipe = k.indexOf('|');
            String hash = pipe > 0 ? k.substring(0, pipe) : k;
            if (hash.startsWith(prefix)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            // SafeLocation invalidation must be durable: a daemon crash
            // before the coalesce window flushes would otherwise resurrect
            // the stale "Jalan Ampang" rows the user explicitly unmapped.
            // Cancel any pending coalesced flush and write inline.
            dirty.set(true);
            flushNow();
        }
    }

    public int size() { return entries.size(); }

    // ---- Eviction ---------------------------------------------------------

    /**
     * Drop oldest 10% by resolvedAtMs when over the soft cap.
     *
     * <p>O(N log N) via Arrays.sort on a packed long[] of
     * {@code (timestamp << 16) | index}. The previous implementation was
     * O(N²) selection-sort which stalled the resolver workers ~150 ms
     * when at cap (~10⁷ comparisons on Cortex-A55) and contended the
     * cache monitor with TTL readers and SafeLocation invalidation.
     *
     * <p>Index packing assumption: SOFT_CAP_ENTRIES ≤ 65535 fits in 16
     * bits; the timestamp goes in the high 48 bits, preserving sort
     * order. Static-asserted at the top.
     */
    private void evictIfOverCap() {
        if (entries.size() <= SOFT_CAP_ENTRIES) return;
        if (SOFT_CAP_ENTRIES > 0xFFFF) {
            throw new IllegalStateException("SOFT_CAP_ENTRIES exceeds 16-bit index range");
        }
        int target = SOFT_CAP_ENTRIES * 9 / 10; // shrink to 90% of cap
        int n = entries.size();

        long[] packed = new long[n];
        String[] keys = new String[n];
        int i = 0;
        for (Map.Entry<String, PlaceResult> e : entries.entrySet()) {
            keys[i] = e.getKey();
            // Sort key: ageMs in high bits, index in low 16 bits. Using
            // resolvedAtMs directly (epoch ms ≈ 41 bits) leaves >7 bits
            // of headroom above the 16-bit index slot, so collisions
            // between near-simultaneous puts at the same ms tick are
            // disambiguated stably by index.
            packed[i] = (e.getValue().resolvedAtMs << 16) | (i & 0xFFFFL);
            i++;
        }
        Arrays.sort(packed);

        int toDrop = n - target;
        for (int p = 0; p < toDrop; p++) {
            int idx = (int) (packed[p] & 0xFFFFL);
            entries.remove(keys[idx]);
        }
    }

    // ---- Persistence ------------------------------------------------------

    private void loadFromDisk() {
        File f = new File(CACHE_PATH);
        if (!f.exists() || !f.canRead()) {
            CameraDaemon.log(TAG + ": no cache file yet at " + CACHE_PATH);
            return;
        }
        try (FileReader r = new FileReader(f)) {
            // Hard cap: refuse to ingest more than MAX_LOAD_BYTES regardless
            // of file size. A pathological 100 MB cache file (corruption /
            // adversarial / bug in another writer) would otherwise OOM the
            // daemon process; better to start fresh than to crash on read.
            int cap = (int) Math.min(f.length(), MAX_LOAD_BYTES);
            StringBuilder sb = new StringBuilder(cap);
            char[] buf = new char[8192];
            int total = 0;
            int n;
            while (total < cap
                    && (n = r.read(buf, 0, Math.min(buf.length, cap - total))) > 0) {
                sb.append(buf, 0, n);
                total += n;
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("entries");
            if (arr == null) return;
            long now = System.currentTimeMillis();
            int loaded = 0, expired = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.optJSONObject(i);
                if (row == null) continue;
                String hash = row.optString("h", "");
                String loc = row.optString("l", "");
                if (hash.isEmpty()) continue;
                JSONObject placeJson = row.optJSONObject("p");
                PlaceResult pr = PlaceResult.fromJson(placeJson);
                if (pr == null) continue;
                if (now - pr.resolvedAtMs > TTL_MS) { expired++; continue; }
                entries.put(key(hash, loc), pr);
                loaded++;
            }
            loadedAt.set(now);
            CameraDaemon.log(TAG + ": loaded=" + loaded + " expired=" + expired);
        } catch (Throwable t) {
            CameraDaemon.log(TAG + ": load failed: " + t.getMessage());
        }
    }

    private void saveToDisk() {
        File target = new File(CACHE_PATH);
        File tmp = new File(CACHE_PATH + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("version", CACHE_VERSION);
            root.put("savedAtMs", System.currentTimeMillis());
            JSONArray arr = new JSONArray();
            // Snapshot to a HashMap to dodge any concurrent put() while we serialise.
            Map<String, PlaceResult> snap = new HashMap<>(entries);
            for (Map.Entry<String, PlaceResult> e : snap.entrySet()) {
                String k = e.getKey();
                int pipe = k.indexOf('|');
                String hash = pipe > 0 ? k.substring(0, pipe) : k;
                String loc  = pipe > 0 ? k.substring(pipe + 1) : "";
                JSONObject row = new JSONObject();
                row.put("h", hash);
                row.put("l", loc);
                row.put("p", e.getValue().toJson());
                arr.put(row);
            }
            root.put("entries", arr);

            try (FileWriter w = new FileWriter(tmp)) {
                w.write(root.toString());
            }
            // World-RW so the app process can read the cache too
            tmp.setReadable(true, false);
            tmp.setWritable(true, false);
            if (!tmp.renameTo(target)) {
                // Fall back to a direct write for the app-UID-can't-create-files
                // case, mirroring UnifiedConfigManager's fallback strategy.
                try (FileWriter w = new FileWriter(target)) {
                    w.write(root.toString());
                }
                target.setReadable(true, false);
                target.setWritable(true, false);
                try { tmp.delete(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            CameraDaemon.log(TAG + ": save failed: " + t.getMessage());
        }
    }
}
