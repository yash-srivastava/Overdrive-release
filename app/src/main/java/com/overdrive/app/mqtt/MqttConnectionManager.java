package com.overdrive.app.mqtt;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.monitor.VehicleDataMonitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates multiple MQTT connections with a single telemetry collection loop.
 *
 * Architecture:
 * - One shared telemetry collection (same data as ABRP)
 * - Fan-out to all enabled MqttPublisherService instances
 * - Each connection has its own publish interval and adaptive behavior
 * - Per-connection scheduler threads for independent timing
 *
 * Lifecycle: init() → startAll() → [runtime add/remove/update] → stopAll()
 */
public class MqttConnectionManager {

    private static final String TAG = "MqttConnectionManager";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Adaptive interval constants (same as ABRP)
    private static final int PARKED_MULTIPLIER = 6; // e.g., 5s driving → 30s parked

    // Config store
    private final MqttConnectionStore store;

    // Active publishers: connectionId → publisher
    private final ConcurrentHashMap<String, MqttPublisherService> publishers = new ConcurrentHashMap<>();

    // Per-connection schedulers: connectionId → scheduler
    private final ConcurrentHashMap<String, ScheduledExecutorService> schedulers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    // Data sources (set during init)
    private VehicleDataMonitor vehicleDataMonitor;
    private GpsMonitor gpsMonitor;
    private GearMonitor gearMonitor;
    private String deviceId;

    // SOH estimator reference (optional, for capacity/soh fields)
    private com.overdrive.app.abrp.SohEstimator sohEstimator;

    // Telemetry cache — prevents multiple MQTT threads from hammering BYD hardware concurrently.
    // Poll the car once, cache the result, let all publishers grab the cached JSON.
    private volatile JSONObject lastCachedTelemetry = null;
    private volatile long lastCollectionTimeMs = 0;
    private static final long TELEMETRY_CACHE_TTL_MS = 2000; // 2 seconds

    private volatile boolean initialized = false;

    public MqttConnectionManager() {
        this.store = new MqttConnectionStore();
    }

    // ==================== LIFECYCLE ====================

    /**
     * Initialize the manager with data source references.
     */
    public void init(String deviceId, com.overdrive.app.abrp.SohEstimator sohEstimator) {
        this.deviceId = deviceId;
        this.vehicleDataMonitor = VehicleDataMonitor.getInstance();
        this.gpsMonitor = GpsMonitor.getInstance();
        this.gearMonitor = GearMonitor.getInstance();
        this.sohEstimator = sohEstimator;

        // CRITICAL: Configure Paho MQTT logging BEFORE any Paho class is loaded.
        // Paho's static initializer tries to load resource bundles (logcat_en_US)
        // that don't exist in the app_process environment, causing ExceptionInInitializerError.
        // Must be done before MqttClient/MqttAsyncClient is ever referenced.
        initPahoLogging();

        store.load();
        initialized = true;

        logger.info("MqttConnectionManager initialized with " + store.size() + " connections");
    }

    /**
     * Disable Paho's internal logging to prevent MissingResourceException.
     * Called once before any Paho class is loaded.
     *
     * Paho's LoggerFactory checks the system property first, before trying to load
     * the logcat resource bundle. Setting this property BEFORE any Paho class is
     * referenced prevents the ExceptionInInitializerError entirely.
     */
    private void initPahoLogging() {
        try {
            // Set system property BEFORE any Paho class is loaded.
            // This tells LoggerFactory to use JSR47 (java.util.logging) directly,
            // bypassing the logcat resource bundle that fails in app_process.
            System.setProperty("org.eclipse.paho.client.mqttv3.logging.LoggerFactory",
                "org.eclipse.paho.client.mqttv3.logging.JSR47Logger");

            // Also suppress java.util.logging output for Paho (it's noisy)
            java.util.logging.Logger pahoLogger = java.util.logging.Logger.getLogger("org.eclipse.paho.client.mqttv3");
            pahoLogger.setLevel(java.util.logging.Level.WARNING);

            logger.info("Paho MQTT logging configured (JSR47 via system property)");
        } catch (Exception e) {
            logger.warn("Failed to configure Paho logging: " + e.getMessage());
        }
    }

    /**
     * Start all enabled connections.
     */
    public void startAll() {
        if (!initialized) {
            logger.warn("Cannot start: not initialized");
            return;
        }

        List<MqttConnectionConfig> enabled = store.getEnabled();
        logger.info("Starting " + enabled.size() + " enabled MQTT connections");

        for (MqttConnectionConfig config : enabled) {
            startConnection(config);
        }
    }

    /**
     * Stop all connections and release resources.
     */
    public void stopAll() {
        logger.info("Stopping all MQTT connections");

        for (Map.Entry<String, ScheduledFuture<?>> entry : scheduledTasks.entrySet()) {
            entry.getValue().cancel(false);
        }
        scheduledTasks.clear();

        for (Map.Entry<String, ScheduledExecutorService> entry : schedulers.entrySet()) {
            entry.getValue().shutdownNow();
        }
        schedulers.clear();

        for (Map.Entry<String, MqttPublisherService> entry : publishers.entrySet()) {
            entry.getValue().disconnect();
        }
        publishers.clear();

        logger.info("All MQTT connections stopped");
    }

    // ==================== CONNECTION MANAGEMENT ====================

    /**
     * Start a single connection's publish loop.
     */
    private void startConnection(MqttConnectionConfig config) {
        // Stop existing if running
        stopConnection(config.id);

        MqttPublisherService publisher = new MqttPublisherService(config, deviceId);

        // Attempt initial connection (non-blocking — will retry on first publish if fails)
        boolean connected = publisher.connect();
        logger.info("Connection " + config.name + " (" + config.id + "): "
                + (connected ? "connected" : "will retry on first publish"));

        publishers.put(config.id, publisher);

        // Create per-connection scheduler
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MQTT-" + config.id);
            t.setDaemon(true);
            return t;
        });
        schedulers.put(config.id, scheduler);

        // Schedule publish loop
        scheduleNext(config.id, config.publishIntervalSeconds);
    }

    /**
     * Stop a single connection.
     */
    private void stopConnection(String connectionId) {
        ScheduledFuture<?> task = scheduledTasks.remove(connectionId);
        if (task != null) task.cancel(false);

        ScheduledExecutorService scheduler = schedulers.remove(connectionId);
        if (scheduler != null) scheduler.shutdownNow();

        MqttPublisherService publisher = publishers.remove(connectionId);
        if (publisher != null) publisher.disconnect();
    }

    /**
     * Schedule the next publish for a connection.
     */
    private void scheduleNext(String connectionId, long delaySeconds) {
        ScheduledExecutorService scheduler = schedulers.get(connectionId);
        if (scheduler == null || scheduler.isShutdown()) return;

        ScheduledFuture<?> task = scheduler.schedule(() -> runPublishCycle(connectionId),
                delaySeconds, TimeUnit.SECONDS);
        scheduledTasks.put(connectionId, task);
    }

    /**
     * Execute one publish cycle for a connection.
     */
    private void runPublishCycle(String connectionId) {
        MqttPublisherService publisher = publishers.get(connectionId);
        if (publisher == null || !publisher.isRunning()) return;

        MqttConnectionConfig config = publisher.getConfig();

        try {
            // Collect telemetry (shared across all connections)
            JSONObject payload = collectTelemetry();

            // Publish
            publisher.publish(payload);

        } catch (Exception e) {
            logger.error("Publish cycle error for " + config.name + ": " + e.getMessage());
        }

        // Calculate next interval
        long nextInterval = config.publishIntervalSeconds;
        if (config.adaptiveInterval && isParked()) {
            nextInterval = config.publishIntervalSeconds * PARKED_MULTIPLIER;
        }

        // Apply backoff if failing
        long backoff = publisher.getBackoffSeconds();
        if (backoff > nextInterval) {
            nextInterval = backoff;
        }

        // Schedule next
        scheduleNext(connectionId, nextInterval);
    }

    // ==================== CRUD OPERATIONS (called from IPC) ====================

    /**
     * Add a new MQTT connection.
     * @return the added config (with generated ID), or null if max reached
     */
    public MqttConnectionConfig addConnection(JSONObject configJson) {
        MqttConnectionConfig config = MqttConnectionConfig.fromJson(configJson);
        // Ensure fresh ID
        config.id = java.util.UUID.randomUUID().toString().substring(0, 8);

        MqttConnectionConfig added = store.add(config);
        if (added == null) return null;

        // Auto-start if enabled
        if (added.enabled && added.isConfigured()) {
            startConnection(added);
        }

        return added;
    }

    /**
     * Update an existing connection.
     * @return true if updated
     */
    public boolean updateConnection(String id, JSONObject updates) {
        boolean updated = store.update(id, updates);
        if (!updated) return false;

        // Restart the connection to apply changes
        MqttConnectionConfig config = store.getById(id);
        if (config != null) {
            stopConnection(id);
            if (config.enabled && config.isConfigured()) {
                startConnection(config);
            }
        }

        return true;
    }

    /**
     * Delete a connection.
     * @return true if deleted
     */
    public boolean deleteConnection(String id) {
        stopConnection(id);
        return store.delete(id);
    }

    // ==================== STATUS ====================

    /**
     * Get status of all connections as a JSON array.
     */
    public JSONArray getAllStatus() {
        JSONArray array = new JSONArray();
        for (MqttConnectionConfig config : store.getAll()) {
            JSONObject entry = config.toSafeJson();
            MqttPublisherService publisher = publishers.get(config.id);
            if (publisher != null) {
                JSONObject status = publisher.getStatus();
                try {
                    entry.put("status", status);
                } catch (Exception ignored) {}
            } else {
                try {
                    JSONObject status = new JSONObject();
                    status.put("connected", false);
                    status.put("running", false);
                    status.put("totalPublishes", 0);
                    status.put("failedPublishes", 0);
                    entry.put("status", status);
                } catch (Exception ignored) {}
            }
            array.put(entry);
        }
        return array;
    }

    /**
     * Get status of a single connection.
     */
    public JSONObject getConnectionStatus(String id) {
        MqttConnectionConfig config = store.getById(id);
        if (config == null) return null;

        JSONObject entry = config.toSafeJson();
        MqttPublisherService publisher = publishers.get(id);
        if (publisher != null) {
            try {
                entry.put("status", publisher.getStatus());
            } catch (Exception ignored) {}
        }
        return entry;
    }

    /**
     * Get the latest telemetry snapshot (for UI preview).
     */
    public JSONObject getLatestTelemetry() {
        return collectTelemetry();
    }

    // ==================== TELEMETRY COLLECTION ====================

    /**
     * Collect telemetry from all data sources.
     * Same fields as ABRP Gold Standard payload for consistency.
     */
    private synchronized JSONObject collectTelemetry() {
        long now = System.currentTimeMillis();

        // If we collected data less than 2 seconds ago, return the cached copy immediately.
        // This protects the BYD hardware from being spammed by multiple MQTT threads.
        if (lastCachedTelemetry != null && (now - lastCollectionTimeMs) < TELEMETRY_CACHE_TTL_MS) {
            return lastCachedTelemetry;
        }

        JSONObject payload = new JSONObject();

        try {
            // Read BYD data from cached snapshot (refreshed by BydDataCollector's 5s polling timer)
            BydDataCollector collector = BydDataCollector.getInstance();
            BydVehicleData vd = collector.isInitialized() ? collector.getData() : null;

            // utc
            payload.put("utc", now / 1000);

            // soc
            double soc = -1;
            if (vd != null && !Double.isNaN(vd.socPercent)) {
                soc = vd.socPercent;
            } else {
                BatterySocData socData = vehicleDataMonitor.getBatterySoc();
                if (socData != null) soc = socData.socPercent;
            }
            if (soc >= 0) payload.put("soc", soc);

            // power
            try {
                boolean powerSet = false;
                if (vd != null && !Double.isNaN(vd.enginePowerKw) && Math.abs(vd.enginePowerKw) <= 300) {
                    payload.put("power", vd.enginePowerKw);
                    powerSet = true;
                }
                if (!powerSet) {
                    ChargingStateData chargingData = vehicleDataMonitor.getChargingState();
                    boolean isChg = chargingData != null && chargingData.status == ChargingStateData.ChargingStatus.CHARGING;
                    double monitorPower = chargingData != null ? chargingData.chargingPowerKW : 0;
                    payload.put("power", isChg && monitorPower > 0.1 ? -monitorPower : 0);
                }
            } catch (Exception e) {
                payload.put("power", 0);
            }

            // speed
            if (vd != null && !Double.isNaN(vd.speedKmh)) {
                payload.put("speed", vd.speedKmh);
            } else if (gpsMonitor.hasLocation()) {
                payload.put("speed", gpsMonitor.getSpeed() * 3.6);
            }

            // lat, lon
            if (gpsMonitor.hasLocation()) {
                payload.put("lat", gpsMonitor.getLatitude());
                payload.put("lon", gpsMonitor.getLongitude());
            }

            // is_charging
            ChargingStateData chargingState = vehicleDataMonitor.getChargingState();
            boolean isCharging = chargingState != null && chargingState.status == ChargingStateData.ChargingStatus.CHARGING;
            payload.put("is_charging", isCharging ? 1 : 0);

            // is_dcfc
            if (vd != null && vd.chargingGunState != BydVehicleData.UNAVAILABLE) {
                payload.put("is_dcfc", vd.chargingGunState == 3 ? 1 : 0);
                if (vd.chargingGunState == 4) payload.put("is_charging", 0); // V2L
            }

            // is_parked
            boolean isParked = false;
            if (vd != null && vd.gearMode != BydVehicleData.UNAVAILABLE) {
                isParked = vd.gearMode == GearMonitor.GEAR_P;
            } else {
                isParked = gearMonitor.getCurrentGear() == GearMonitor.GEAR_P;
            }
            payload.put("is_parked", isParked ? 1 : 0);

            // elevation, heading
            if (gpsMonitor.hasLocation()) {
                double alt = gpsMonitor.getAltitude();
                if (alt != 0) payload.put("elevation", alt);
                float heading = gpsMonitor.getHeading();
                if (heading > 0) payload.put("heading", heading);
            }

            // ext_temp
            if (vd != null && !Double.isNaN(vd.outsideTempC)) {
                payload.put("ext_temp", vd.outsideTempC);
            }

            // batt_temp
            if (vd != null && !Double.isNaN(vd.getBestBatteryTemp())) {
                double battTemp = vd.getBestBatteryTemp();
                if (battTemp >= -40 && battTemp <= 80) payload.put("batt_temp", battTemp);
            }

            // odometer
            if (vd != null && vd.totalMileageKm != BydVehicleData.UNAVAILABLE) {
                int raw = vd.totalMileageKm;
                payload.put("odometer", raw > 1_000_000 ? raw / 10.0 : (double) raw);
            }

            // soh
            if (sohEstimator != null && sohEstimator.hasEstimate()) {
                payload.put("soh", sohEstimator.getCurrentSoh());
            }

            // capacity (remaining kWh)
            if (vd != null && !Double.isNaN(vd.remainKwh) && vd.remainKwh > 0) {
                payload.put("capacity", vd.remainKwh);
            }

            // gear (extra field not in ABRP — useful for MQTT consumers)
            if (vd != null && vd.gearMode != BydVehicleData.UNAVAILABLE) {
                payload.put("gear", GearMonitor.gearToString(vd.gearMode));
            } else {
                payload.put("gear", GearMonitor.gearToString(gearMonitor.getCurrentGear()));
            }

        } catch (Exception e) {
            logger.error("Telemetry collection error: " + e.getMessage());
        }

        // Update the cache
        lastCachedTelemetry = payload;
        lastCollectionTimeMs = now;

        return payload;
    }

    /**
     * Check if the vehicle is currently parked.
     */
    private boolean isParked() {
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            BydVehicleData vd = collector.isInitialized() ? collector.getData() : null;
            if (vd != null && vd.gearMode != BydVehicleData.UNAVAILABLE) {
                return vd.gearMode == GearMonitor.GEAR_P;
            }
            return gearMonitor.getCurrentGear() == GearMonitor.GEAR_P;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== GETTERS ====================

    public MqttConnectionStore getStore() { return store; }
    public boolean isInitialized() { return initialized; }
    public int getActiveCount() { return publishers.size(); }
}
