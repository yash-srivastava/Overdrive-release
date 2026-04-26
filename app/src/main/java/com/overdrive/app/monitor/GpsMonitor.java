package com.overdrive.app.monitor;

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
    private static final String CACHE_FILE = "/data/local/tmp/gps_cache.json";
    
    // Secondary cache file (app data directory - read-only for daemon, written by LocationSidecarService)
    private static final String CACHE_FILE_APP = "/data/data/com.overdrive.app/files/gps_cache.json";
    
    // Command to start the sidecar service
    private static final String START_CMD = "am start-foreground-service -n com.overdrive.app/.services.LocationSidecarService";

    private volatile double latitude = 0.0;
    private volatile double longitude = 0.0;
    private volatile float speed = 0.0f;
    private volatile float heading = 0.0f;
    private volatile float accuracy = 0.0f;
    private volatile double altitude = 0.0;
    private volatile long lastUpdate = 0;
    private volatile boolean isRunning = false;
    private volatile boolean loadedFromCache = false;
    private volatile long lastLoggedAt = 0;
    private static final long LOG_INTERVAL_MS = 30_000;

    private GpsMonitor() {}

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
        CameraDaemon.log(TAG + ": Initialized (IPC mode)" + 
            (hasLocation() ? " - cached: " + latitude + ", " + longitude + " (loadedFromCache=" + loadedFromCache + ")" : " - no cached location"));
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
        // Reject invalid coordinates (0,0 is in the ocean, not a real location)
        if (lat == 0.0 && lng == 0.0) {
            return;
        }
        
        this.latitude = lat;
        this.longitude = lng;
        this.speed = speed;
        this.heading = heading;
        this.accuracy = accuracy;
        this.altitude = altitude;
        this.lastUpdate = time;
        this.loadedFromCache = false; // We have live data now

        // Persist to cache file
        saveToCache();
        
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
        if (hasLocation() && now - lastLoggedAt >= LOG_INTERVAL_MS) {
            lastLoggedAt = now;
            CameraDaemon.log(TAG + ": GPS: " + lat + ", " + lng + " (speed=" + speed + "m/s)");
        }
    }

    private void saveToCache() {
        // Only save if we have a valid location
        if (!hasLocation()) return;
        
        try {
            JSONObject json = new JSONObject();
            json.put("lat", latitude);
            json.put("lng", longitude);
            json.put("speed", speed);
            json.put("heading", heading);
            json.put("accuracy", accuracy);
            json.put("altitude", altitude);
            json.put("time", lastUpdate);

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
            CameraDaemon.log(TAG + ": Loaded GPS from primary cache: " + latitude + ", " + longitude);
            loadedFromCache = true;
            return;
        }
        
        // Try secondary cache (app data directory - written by LocationSidecarService)
        if (loadFromCacheFile(CACHE_FILE_APP)) {
            CameraDaemon.log(TAG + ": Loaded GPS from app cache: " + latitude + ", " + longitude);
            loadedFromCache = true;
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
                this.latitude = lat;
                this.longitude = lng;
                this.speed = (float) json.optDouble("speed", 0.0);
                this.heading = (float) json.optDouble("heading", 0.0);
                this.accuracy = (float) json.optDouble("accuracy", 0.0);
                this.altitude = json.optDouble("altitude", 0.0);
                this.lastUpdate = json.optLong("time", 0);
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public void stop() {
        isRunning = false;
        CameraDaemon.log(TAG + ": Stopped");
    }

    // ==================== PUBLIC GETTERS ====================

    public boolean isRunning() { return isRunning; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public float getSpeed() { return speed; }
    public float getHeading() { return heading; }
    public float getAccuracy() { return accuracy; }
    public double getAltitude() { return altitude; }
    public long getLastUpdate() { return lastUpdate; }
    public String getProvider() { return "sidecar"; }
    public boolean isMoving() { return speed > 1.0f; }

    public boolean hasLocation() {
        return latitude != 0.0 || longitude != 0.0;
    }

    public JSONObject getLocationJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("lat", latitude);
            json.put("lng", longitude);
            json.put("speed", speed);
            json.put("heading", heading);
            json.put("accuracy", accuracy);
            json.put("altitude", altitude);
            json.put("lastUpdate", lastUpdate);
            json.put("provider", "sidecar");
            json.put("isMoving", isMoving());
            json.put("hasLocation", hasLocation());
            
            // Add staleness indicator - location is stale if no update in 30 seconds
            long ageMs = System.currentTimeMillis() - lastUpdate;
            json.put("ageMs", ageMs);
            json.put("isStale", ageMs > 30000);
            json.put("isCached", ageMs > 60000 || loadedFromCache); // Cached = no update in 60s OR loaded from cache file
            json.put("loadedFromCache", loadedFromCache); // Explicitly indicate if loaded from persistent cache
        } catch (Exception e) {
            // Ignore
        }
        return json;
    }

    public String getGoogleMapsUrl() {
        if (!hasLocation()) return null;
        return "https://www.google.com/maps/dir/?api=1&destination=" + latitude + "," + longitude + "&travelmode=driving";
    }
}
