package com.overdrive.app.monitor;

import android.os.SystemClock;
import com.overdrive.app.util.ScratchPaths;

import com.overdrive.app.daemon.CameraDaemon;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * GPS Monitor - Receives location updates from LocationSidecarService via IPC.
 * 
 * Flow: LocationSidecarService → IPC (port 19877) → SurveillanceIpcServer → GpsMonitor.updateFromIpc()
 * 
 * Cache locations (daemon UID 2000 writes to these):
 * 1. /data/local/tmp/gps_cache.json (primary - daemon can write here)
 * 
 * Note: App data directory (/data/data/com.overdrive.app/) is NOT writable by daemon (UID 2000).
 * The LocationSidecarService (app UID) handles its own cache in app data directory.
 * 
 * On startup, loads cached GPS for immediate availability.
 */
public class GpsMonitor {

    private static final String TAG = "GpsMonitor";
    private static GpsMonitor instance;
    private static final Object lock = new Object();

    // Primary cache file (daemon uid 2000 can write to /data/local/tmp)
    private static final String CACHE_FILE = ScratchPaths.path("gps_cache.json");
    
    // Secondary cache file (app data directory - read-only for daemon, written by LocationSidecarService)
    private static final String CACHE_FILE_APP = "/data/data/com.overdrive.app/files/gps_cache.json";
    
    // Command to start the sidecar service
    private static final String START_CMD = "am start-foreground-service -n com.overdrive.app/.services.LocationSidecarService";

    private volatile GpsFixSnapshot fixSnapshot =
            GpsFixSnapshot.empty();
    private volatile boolean isRunning = false;
    private volatile long lastLoggedAt = 0;
    private static final long LOG_INTERVAL_MS = 30_000;
    private static final long LIVE_SPEED_FIX_MAX_AGE_MS = 5_000L;

    // Cache-write throttle. The on-disk cache exists ONLY for restart recovery
    // (all live consumers read the in-memory immutable snapshot, which is always
    // fresh). Writing it on every ~1-2 Hz IPC update is thousands of flash
    // writes per drive for no benefit. Persist at most every CACHE_WRITE_MIN_MS
    // OR when the fix moves > CACHE_WRITE_MIN_MOVE_M — so a parked car writes
    // rarely and a moving car keeps the recovered position reasonably current.
    // stop() forces a final flush so the freshest fix survives a clean shutdown.
    private static final long CACHE_WRITE_MIN_MS = 30_000;
    private static final double CACHE_WRITE_MIN_MOVE_M = 50.0;
    private volatile long lastCacheWriteAt = 0;
    private volatile double lastCachedLat = 0.0;
    private volatile double lastCachedLng = 0.0;

    private GpsMonitor() {}

    /**
     * One immutable publication of every field belonging to a GPS fix.
     * Consumers must retain one instance for the duration of a sample instead
     * of combining values obtained from separate IPC publications.
     */
    public static final class GpsFixSnapshot {
        public final double latitude;
        public final double longitude;
        public final float speed;
        public final float heading;
        public final float accuracy;
        public final double altitude;
        // Reported 1-sigma vertical accuracy (m); 0 = unreported by this fix
        // (older sidecar / HAL without the field). Elevation gate input.
        public final float verticalAccuracy;
        // True when `altitude` is MSL (geoid-corrected) rather than WGS84-
        // ellipsoidal. Per-fix: a source flip mid-trip is a datum step the
        // elevation pipeline must reset on, not integrate.
        public final boolean altitudeIsMsl;
        public final long lastUpdate;
        // MONOTONIC since-boot fix timestamp from the sidecar. A zero value
        // means an older sidecar or a cache-loaded, cross-boot fix.
        public final long fixElapsedMs;
        public final boolean loadedFromCache;

        private GpsFixSnapshot(
                double latitude, double longitude,
                float speed, float heading, float accuracy,
                double altitude, float verticalAccuracy, boolean altitudeIsMsl,
                long lastUpdate, long fixElapsedMs, boolean loadedFromCache) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.speed = speed;
            this.heading = heading;
            this.accuracy = accuracy;
            this.altitude = altitude;
            this.verticalAccuracy = verticalAccuracy;
            this.altitudeIsMsl = altitudeIsMsl;
            this.lastUpdate = lastUpdate;
            this.fixElapsedMs = fixElapsedMs;
            this.loadedFromCache = loadedFromCache;
        }

        private static GpsFixSnapshot empty() {
            return new GpsFixSnapshot(
                    0.0, 0.0, 0.0f, 0.0f, 0.0f,
                    0.0, 0.0f, false, 0L, 0L, false);
        }

        public boolean hasLocation() {
            return latitude != 0.0 || longitude != 0.0;
        }

        public boolean isMoving() {
            return speed > 1.0f;
        }
    }

    public static GpsMonitor getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) instance = new GpsMonitor();
            }
        }
        return instance;
    }

    public void init(android.content.Context ctx) {
        // Load cached GPS on init - try multiple locations
        loadFromCache();
        GpsFixSnapshot fix = fixSnapshot;
        CameraDaemon.log(TAG + ": Initialized (IPC mode)" + 
            (fix.hasLocation() ? " - cached: " + fix.latitude + ", " + fix.longitude
                    + " (loadedFromCache=" + fix.loadedFromCache + ")"
                    : " - no cached location"));
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;

        // Start the sidecar service
        try {
            Runtime.getRuntime().exec(START_CMD);
            CameraDaemon.log(TAG + ": Sidecar service started");
        } catch (Exception e) {
            CameraDaemon.log(TAG + ": Failed to start sidecar: " + e.getMessage());
        }
    }

    /**
     * Called by SurveillanceIpcServer when GPS update arrives via IPC.
     */
    public void updateFromIpc(double lat, double lng, float speed, float heading, float accuracy, long time, double altitude) {
        // Back-compat overload: no monotonic fix time → 0 sentinel → geo gate falls
        // back to send-time aging (prior behavior). New callers use the 8-arg form.
        updateFromIpc(lat, lng, speed, heading, accuracy, time, altitude, 0L);
    }

    public void updateFromIpc(double lat, double lng, float speed, float heading, float accuracy, long time, double altitude, long fixElapsedMs) {
        // Back-compat overload: no vertical accuracy / altitude source → 0 =
        // unreported, false = ellipsoidal (prior behavior).
        updateFromIpc(lat, lng, speed, heading, accuracy, time, altitude, fixElapsedMs,
                0.0f, false);
    }

    public void updateFromIpc(double lat, double lng, float speed, float heading, float accuracy,
                              long time, double altitude, long fixElapsedMs,
                              float verticalAccuracy, boolean altitudeIsMsl) {
        // Reject invalid coordinates (0,0 is in the ocean, not a real location)
        if (lat == 0.0 && lng == 0.0) {
            return;
        }

        GpsFixSnapshot fix = new GpsFixSnapshot(
                lat, lng, speed, heading, accuracy, altitude, verticalAccuracy, altitudeIsMsl,
                time, fixElapsedMs, false);
        this.fixSnapshot = fix;

        // Persist to cache file — throttled (see CACHE_WRITE_MIN_MS). Live
        // consumers read the in-memory fields above; the disk cache is only for
        // restart recovery, so it does not need per-update freshness.
        maybeSaveToCache(lat, lng);

        // SOTA: Notify SafeLocationManager for geofence checks
        try {
            com.overdrive.app.surveillance.SafeLocationManager.getInstance()
                .onLocationUpdate(lat, lng);
        } catch (Exception e) {
            // Don't let geofence errors break GPS flow
        }

        // Log periodically — once every LOG_INTERVAL_MS at most.
        // The previous `currentTimeMillis() % 10000 < 2000` trick fired
        // whenever a 2-second IPC update happened to land inside a fixed
        // 2s window, which produced bursts of identical log lines.
        long now = System.currentTimeMillis();
        if (fix.hasLocation() && now - lastLoggedAt >= LOG_INTERVAL_MS) {
            lastLoggedAt = now;
            CameraDaemon.log(TAG + ": GPS: " + lat + ", " + lng + " (speed=" + speed + "m/s)");
        }
    }

    /**
     * Throttled cache write: persist only if enough time has passed since the
     * last write OR the position moved far enough to matter for restart
     * recovery. Cheap equirectangular distance approximation (fine at the 50 m
     * scale). Runs on the IPC-server thread, same as the direct saveToCache path.
     */
    private void maybeSaveToCache(double lat, double lng) {
        long now = System.currentTimeMillis();
        boolean timeElapsed = (now - lastCacheWriteAt) >= CACHE_WRITE_MIN_MS;
        boolean movedFar = false;
        if (lastCacheWriteAt != 0 && (lastCachedLat != 0.0 || lastCachedLng != 0.0)) {
            double dLatM = (lat - lastCachedLat) * 111_320.0;
            double dLngM = (lng - lastCachedLng) * 111_320.0
                    * Math.cos(Math.toRadians(lat));
            movedFar = (dLatM * dLatM + dLngM * dLngM)
                    >= (CACHE_WRITE_MIN_MOVE_M * CACHE_WRITE_MIN_MOVE_M);
        }
        // First write after startup (lastCacheWriteAt==0) always persists so a
        // fresh fix is recoverable even if the daemon dies within 30 s.
        if (lastCacheWriteAt == 0 || timeElapsed || movedFar) {
            saveToCache();
            lastCacheWriteAt = now;
            lastCachedLat = lat;
            lastCachedLng = lng;
        }
    }

    private void saveToCache() {
        // Only save if we have a valid location
        GpsFixSnapshot fix = fixSnapshot;
        if (!fix.hasLocation()) return;
        
        try {
            JSONObject json = new JSONObject();
            json.put("lat", fix.latitude);
            json.put("lng", fix.longitude);
            json.put("speed", fix.speed);
            json.put("heading", fix.heading);
            json.put("accuracy", fix.accuracy);
            json.put("altitude", fix.altitude);
            json.put("vAcc", fix.verticalAccuracy);
            json.put("altMsl", fix.altitudeIsMsl);
            json.put("time", fix.lastUpdate);
            // Deliberately NOT persisting fixElapsedMs: elapsedRealtime resets at
            // reboot, so a value from a prior boot is meaningless to compare. A
            // cache-loaded fix is loadedFromCache=true and the geo gate rejects it
            // regardless, so it never needs a monotonic age.

            String content = json.toString();
            
            // Save to primary cache (daemon tmp) - daemon UID 2000 can write here
            saveToCacheFile(CACHE_FILE, content);
            
            // Note: Cannot write to app data directory from daemon (different UID)
            // LocationSidecarService handles its own cache in app data directory
            
        } catch (Exception e) {
            CameraDaemon.log(TAG + ": Failed to save GPS cache: " + e.getMessage());
        }
    }
    
    private void saveToCacheFile(String path, String content) {
        try {
            // Ensure parent directory exists
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            
            // Atomic write
            File tmp = new File(path + ".tmp");
            try (FileWriter writer = new FileWriter(tmp)) {
                writer.write(content);
            }
            if (!tmp.renameTo(file)) {
                // Fallback: direct write if rename fails
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(content);
                }
                tmp.delete();
            }
        } catch (Exception e) {
            // Silently ignore individual file failures
        }
    }

    private void loadFromCache() {
        // Try primary cache first (daemon tmp)
        if (loadFromCacheFile(CACHE_FILE)) {
            GpsFixSnapshot fix = fixSnapshot;
            CameraDaemon.log(TAG + ": Loaded GPS from primary cache: "
                    + fix.latitude + ", " + fix.longitude);
            return;
        }
        
        // Try secondary cache (app data directory - written by LocationSidecarService)
        if (loadFromCacheFile(CACHE_FILE_APP)) {
            GpsFixSnapshot fix = fixSnapshot;
            CameraDaemon.log(TAG + ": Loaded GPS from app cache: "
                    + fix.latitude + ", " + fix.longitude);
            return;
        }
        
        CameraDaemon.log(TAG + ": No GPS cache found at " + CACHE_FILE + " or " + CACHE_FILE_APP);
    }
    
    private boolean loadFromCacheFile(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                return false;
            }

            StringBuilder sb = new StringBuilder();
            try (FileReader reader = new FileReader(file)) {
                char[] buf = new char[1024];
                int n;
                while ((n = reader.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
            }

            JSONObject json = new JSONObject(sb.toString());
            double lat = json.optDouble("lat", 0.0);
            double lng = json.optDouble("lng", 0.0);
            
            // Always use cached location if valid — better than nothing
            // Fresh IPC updates from sidecar will overwrite this
            if (lat != 0.0 || lng != 0.0) {
                // No monotonic basis for a cache-loaded fix (elapsedRealtime is
                // cross-boot-incomparable); leave 0. The fix is loadedFromCache=true
                // so the geo gate rejects it without needing an age anyway.
                this.fixSnapshot = new GpsFixSnapshot(
                        lat,
                        lng,
                        (float) json.optDouble("speed", 0.0),
                        (float) json.optDouble("heading", 0.0),
                        (float) json.optDouble("accuracy", 0.0),
                        json.optDouble("altitude", 0.0),
                        (float) json.optDouble("vAcc", 0.0),
                        json.optBoolean("altMsl", false),
                        json.optLong("time", 0),
                        0L,
                        true);
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public void stop() {
        isRunning = false;
        // Force a final flush so the freshest in-memory fix survives a clean
        // shutdown even if the last throttled write was skipped.
        saveToCache();
        CameraDaemon.log(TAG + ": Stopped");
    }

    // ==================== PUBLIC GETTERS ====================

    public boolean isRunning() { return isRunning; }
    public GpsFixSnapshot getFixSnapshot() { return fixSnapshot; }
    public double getLatitude() { return fixSnapshot.latitude; }
    public double getLongitude() { return fixSnapshot.longitude; }
    public float getSpeed() { return fixSnapshot.speed; }
    public float getHeading() { return fixSnapshot.heading; }
    public float getAccuracy() { return fixSnapshot.accuracy; }
    public double getAltitude() { return fixSnapshot.altitude; }
    /** Reported 1-sigma vertical accuracy (m); 0 = unreported by the current fix. */
    public float getVerticalAccuracy() { return fixSnapshot.verticalAccuracy; }
    /** True when the current fix's altitude is MSL (geoid-corrected). */
    public boolean isAltitudeMsl() { return fixSnapshot.altitudeIsMsl; }
    public long getLastUpdate() { return fixSnapshot.lastUpdate; }
    /** Monotonic since-boot fix timestamp (elapsedRealtime ms) — what geo-tagging
     *  ages against the daemon's own elapsedRealtime(). 0 = no monotonic basis
     *  (older sidecar / cache-loaded); callers then fall back to send-time aging. */
    public long getFixElapsedMs() { return fixSnapshot.fixElapsedMs; }
    public String getProvider() { return "sidecar"; }
    public boolean isMoving() { return fixSnapshot.isMoving(); }

    /**
     * True while the current fix originated from the persisted cache file (loaded at
     * daemon init) and has NOT yet been overwritten by a live IPC update. Such a fix
     * may be from a previous drive/boot, so geo-tagging consumers must treat it as
     * stale and decline to tag (a parked sentry clip should not inherit yesterday's
     * address). Cleared the moment a real IPC update lands (see {@link #updateFromIpc}).
     * Cheaper than {@link #getLocationJson()} for the recorder hot path.
     */
    public boolean isLoadedFromCache() {
        return fixSnapshot.loadedFromCache;
    }

    public boolean hasLocation() {
        return fixSnapshot.hasLocation();
    }

    public JSONObject getLocationJson() {
        GpsFixSnapshot fix = fixSnapshot;
        JSONObject json = new JSONObject();
        try {
            json.put("lat", fix.latitude);
            json.put("lng", fix.longitude);
            json.put("speed", fix.speed);
            json.put("heading", fix.heading);
            json.put("accuracy", fix.accuracy);
            json.put("altitude", fix.altitude);
            json.put("lastUpdate", fix.lastUpdate);
            json.put("provider", "sidecar");
            json.put("isMoving", fix.isMoving());
            json.put("hasLocation", fix.hasLocation());
            
            // Location and speed have different freshness contracts. The one-second
            // sidecar keepalive intentionally refreshes lastUpdate even when the
            // provider has not produced a new fix, so receive age is suitable for
            // connection/location display but not for a live speed readout.
            long ageMs = System.currentTimeMillis() - fix.lastUpdate;
            long fixAgeMs;
            if (fix.loadedFromCache) {
                fixAgeMs = -1L;
            } else if (fix.fixElapsedMs > 0L) {
                fixAgeMs = Math.max(
                        0L, SystemClock.elapsedRealtime() - fix.fixElapsedMs);
            } else {
                // Compatibility with an older sidecar that did not send the
                // monotonic provider-fix timestamp.
                fixAgeMs = Math.max(0L, ageMs);
            }
            json.put("ageMs", ageMs);
            json.put("isStale", ageMs > 30000);
            json.put("isCached", ageMs > 60000 || fix.loadedFromCache); // Cached = no update in 60s OR loaded from cache file
            json.put("loadedFromCache", fix.loadedFromCache); // Explicitly indicate if loaded from persistent cache
            json.put("fixAgeMs",
                    fixAgeMs >= 0L ? fixAgeMs : JSONObject.NULL);
            json.put("isFixStale",
                    fix.loadedFromCache
                            || fixAgeMs < 0L
                            || fixAgeMs > LIVE_SPEED_FIX_MAX_AGE_MS);
        } catch (Exception e) {
            // Ignore
        }
        return json;
    }

    public String getGoogleMapsUrl() {
        GpsFixSnapshot fix = fixSnapshot;
        if (!fix.hasLocation()) return null;
        return "https://www.google.com/maps/dir/?api=1&destination="
                + fix.latitude + "," + fix.longitude
                + "&travelmode=driving";
    }
}
