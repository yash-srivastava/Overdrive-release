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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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

    // Config store
    private final MqttConnectionStore store;

    // Active publishers: connectionId → publisher
    private final ConcurrentHashMap<String, MqttPublisherService> publishers = new ConcurrentHashMap<>();

    // Per-connection schedulers: connectionId → scheduler
    private final ConcurrentHashMap<String, ScheduledExecutorService> schedulers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    // Serial executor for connection lifecycle (connect/disconnect). Paho's connect blocks up to
    // ~10s and disconnect up to 5s; running them on the caller's thread would blow the IPC socket's
    // 3s read timeout (MqttApiHandler) and make add/update/delete look like they failed. Offloading
    // here lets the IPC call return immediately while the broker is (re)connected in the background.
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MQTT-control");
        t.setDaemon(true);
        return t;
    });

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

        // All connections are down — now it's safe to clear the process-global
        // SOCKS proxy properties (individual disconnect() no longer does this, to
        // avoid one connection stomping a sibling's still-needed proxy routing).
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");

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

        // Schedule publish loop at the min-interval floor.
        scheduleNext(config.id, scheduler, Math.max(1, config.minIntervalSeconds));
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

        // Once no connection remains, clear the process-global SOCKS proxy props so
        // unrelated daemon sockets (zrok, APK download, push) aren't routed through
        // sing-box by a leftover from a WS+proxy connection. While ≥1 connection is
        // live we leave them — a sibling may still need them, and each connect()
        // re-asserts/clears authoritatively from the current proxy state.
        if (publishers.isEmpty()) {
            System.clearProperty("socksProxyHost");
            System.clearProperty("socksProxyPort");
        }
    }

    /**
     * Schedule the next publish for a connection on its own scheduler.
     *
     * The scheduler is passed in (not looked up) so a trailing cycle from a scheduler that has
     * since been replaced by a restart can't queue work onto the new one — it simply no-ops.
     */
    private void scheduleNext(String connectionId, ScheduledExecutorService scheduler, long delaySeconds) {
        if (scheduler == null || scheduler.isShutdown()) return;
        // This scheduler was swapped out by a restart — drop the reschedule.
        if (schedulers.get(connectionId) != scheduler) return;

        try {
            ScheduledFuture<?> task = scheduler.schedule(() -> runPublishCycle(connectionId, scheduler),
                    delaySeconds, TimeUnit.SECONDS);
            scheduledTasks.put(connectionId, task);
        } catch (RejectedExecutionException ignored) {
            // Scheduler was shut down between the guard above and schedule() — connection is
            // being torn down; nothing to do.
        }
    }

    /**
     * Execute one publish cycle for a connection.
     */
    private void runPublishCycle(String connectionId, ScheduledExecutorService scheduler) {
        // Bail if this connection was restarted/stopped — our scheduler is no longer the live one.
        if (schedulers.get(connectionId) != scheduler) return;

        MqttPublisherService publisher = publishers.get(connectionId);
        if (publisher == null || !publisher.isRunning()) return;

        MqttConnectionConfig config = publisher.getConfig();

        // Active health check, decoupled from whether a publish is due. The change-gated
        // publish loop can skip idle cycles for up to maxIntervalSeconds, during which a
        // silently-dropped link (idle NAT timeout, ACC-OFF data blackout) would otherwise
        // go unnoticed — and QoS 0 means the eventual heartbeat publish can succeed into a
        // half-open socket without throwing, so reconnect never triggers. Polling
        // isConnected() each cycle lets us reconnect within ~keep-alive seconds of a drop.
        try {
            publisher.ensureAlive();
        } catch (Exception e) {
            logger.warn("Health check error for " + config.name + ": " + e.getMessage());
        }

        try {
            // Collect telemetry (shared across all connections)
            JSONObject payload = collectTelemetry();

            // Supply vehicle identity for Home Assistant discovery (cheap; updated each cycle
            // because VIN only appears once the BYD SDK has been read at least once).
            if (config.isHomeAssistant()) {
                String vin = payload.optString("vin", null);
                publisher.setHaMeta(vin, null, "OverDrive " + com.overdrive.app.BuildConfig.VERSION_NAME);
            }

            // Change-gated publish (per-field for HA, full snapshot for aggregate).
            publisher.publishTelemetry(payload);

        } catch (Exception e) {
            logger.error("Publish cycle error for " + config.name + ": " + e.getMessage());
        }

        // The cycle runs at the min-interval floor; the differ enforces the heartbeat
        // ceiling and skips idle cycles, so the old parked multiplier is no longer needed.
        long nextInterval = Math.max(1, config.minIntervalSeconds);

        // Apply backoff if failing
        long backoff = publisher.getBackoffSeconds();
        if (backoff > nextInterval) {
            nextInterval = backoff;
        }

        // Schedule next on the same scheduler this cycle ran on.
        scheduleNext(connectionId, scheduler, nextInterval);
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

        // Auto-start if enabled — off the caller's thread (connect() blocks).
        if (added.enabled && added.isConfigured()) {
            controlExecutor.execute(() -> startConnection(added));
        }

        return added;
    }

    /**
     * Update an existing connection.
     * @return true if updated
     */
    public boolean updateConnection(String id, JSONObject updates) {
        // Capture pre-update HA state BEFORE store.update() mutates the live config object.
        MqttConnectionConfig existing = store.getById(id);
        boolean wasHa = existing != null && existing.homeAssistantDiscovery;
        String oldPrefix = existing != null ? existing.discoveryPrefix : "homeassistant";

        boolean updated = store.update(id, updates);
        if (!updated) return false;

        // Apply the change by tearing the live connection down and rebuilding it from the updated
        // config (host/port/topic/auth/TLS can't be changed on a live Paho client). The store has
        // already been written, so the IPC reply is correct the instant it returns; the actual
        // disconnect/reconnect runs on the control executor to keep network I/O off the IPC thread.
        final MqttConnectionConfig config = store.getById(id);
        if (config != null) {
            final boolean retractHa = wasHa && !config.homeAssistantDiscovery;
            controlExecutor.execute(() -> {
                // If HA discovery was just turned off, retract the device while still connected.
                if (retractHa) {
                    MqttPublisherService pub = publishers.get(id);
                    if (pub != null) pub.removeDiscovery(oldPrefix);
                }
                stopConnection(id);
                if (config.enabled && config.isConfigured()) {
                    startConnection(config);
                }
            });
        }

        return true;
    }

    /**
     * Delete a connection.
     * @return true if deleted
     */
    public boolean deleteConnection(String id) {
        // Retract HA discovery (while the client is still connected) so deleting a connection
        // doesn't leave orphaned entities in Home Assistant, then tear the connection down —
        // both on the control executor so disconnect() doesn't block the IPC caller.
        MqttConnectionConfig cfg = store.getById(id);
        final boolean ha = cfg != null && cfg.isHomeAssistant();
        final String prefix = cfg != null ? cfg.discoveryPrefix : "homeassistant";
        controlExecutor.execute(() -> {
            MqttPublisherService pub = publishers.get(id);
            if (pub != null && ha) {
                pub.removeDiscovery(prefix);
            }
            stopConnection(id);
        });
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
     * Return a shallow copy of {@code base} with the position fields refreshed to the current GPS
     * fix. Used on a telemetry-cache hit so the GPS track publishes at the publish-cycle rate
     * (e.g. 1Hz) instead of being frozen to the 2s telemetry cache, while the expensive BYD SDK
     * fields stay cached. Mirrors the lat/lon/elevation/heading block in {@link #collectTelemetry()}.
     * Copies rather than mutating {@code base} because the cached object is shared across connections.
     */
    private JSONObject withLiveGps(JSONObject base) {
        try {
            JSONObject copy = new JSONObject();
            for (java.util.Iterator<String> it = base.keys(); it.hasNext(); ) {
                String k = it.next();
                copy.put(k, base.opt(k));
            }
            if (gpsMonitor != null && gpsMonitor.hasLocation()) {
                copy.put("lat", gpsMonitor.getLatitude());
                copy.put("lon", gpsMonitor.getLongitude());
                double alt = gpsMonitor.getAltitude();
                if (alt != 0) copy.put("elevation", alt);
                float heading = gpsMonitor.getHeading();
                if (heading > 0) copy.put("heading", heading);
            }
            return copy;
        } catch (Exception e) {
            return base; // on any failure, fall back to the cached snapshot unchanged
        }
    }

    /**
     * Collect telemetry from all data sources.
     * Same fields as ABRP Gold Standard payload for consistency.
     */
    private synchronized JSONObject collectTelemetry() {
        long now = System.currentTimeMillis();

        // If we collected data less than 2 seconds ago, return the cached copy immediately.
        // This protects the BYD hardware from being spammed by multiple MQTT threads.
        //
        // BUT position must not be frozen to that 2s cache: GPS comes from the in-memory GpsMonitor
        // (~1Hz, cheap to read — no BYD SDK hit), and serving the cached snapshot capped the
        // published track at ~0.5Hz and lagged the position (visible as corner-cutting + start/stop
        // gaps in HA). Refresh just the position fields onto a COPY of the cached snapshot so the
        // track publishes at the full publish-cycle rate. Copy, not mutate: the cached object is
        // shared with other connections that read it concurrently.
        if (lastCachedTelemetry != null && (now - lastCollectionTimeMs) < TELEMETRY_CACHE_TTL_MS) {
            return withLiveGps(lastCachedTelemetry);
        }

        JSONObject payload = new JSONObject();

        try {
            // Read BYD data from cached snapshot (refreshed by BydDataCollector's 5s polling timer)
            BydDataCollector collector = BydDataCollector.getInstance();
            BydVehicleData vd = collector.isInitialized() ? collector.getData() : null;

            // utc
            payload.put("utc", now / 1000);

            // soc — vd.socPercent now carries decimal precision: BydDataCollector
            // registers a TYPED statistic listener whose onElecPercentageChanged(double)
            // feeds the true sub-integer SoC (the generic Proxy path never delivered it,
            // so SoC used to advance only on the coarse integer poll). Round to 1 decimal
            // for a stable published value. getBatterySoc() is only a fallback for the
            // AccSentry-process path where the collector snapshot may be absent.
            double soc = -1;
            if (vd != null && !Double.isNaN(vd.socPercent)) {
                soc = vd.socPercent;
            } else {
                BatterySocData socData = vehicleDataMonitor.getBatterySoc();
                if (socData != null) soc = socData.socPercent;
            }
            if (soc >= 0) payload.put("soc", Math.round(soc * 10.0) / 10.0);

            // power — motor/propulsion power (kW). Positive = consuming, negative = regen.
            // Only meaningful while the car is on: the motor signal idles at ~-2 kW noise when
            // parked, so force 0 when ACC is off. Charge power is reported separately as
            // charge_power — the motor signal does not see the OBC→pack charge path.
            try {
                boolean accOn = false;
                try { accOn = com.overdrive.app.monitor.AccMonitor.isAccOn(); } catch (Throwable ignored) {}
                double motorKw = 0;
                if (accOn && vd != null && !Double.isNaN(vd.enginePowerKw)
                        && Math.abs(vd.enginePowerKw) <= 300) {
                    motorKw = vd.enginePowerKw;
                }
                payload.put("power", motorKw);
            } catch (Exception e) {
                payload.put("power", 0);
            }

            // speed — the bus speed signal freezes at its last sample when ACC is off
            // (BydDataCollector drops to a 90s poll and the speed listener stops firing), so a
            // stale non-zero value would keep publishing after parking and HA would think the car
            // is still moving. The car is parked when ACC is off, so force 0 — same handling as
            // power above. GPS stays a driving-only fallback for a momentary NaN bus speed.
            boolean accOnSpeed = false;
            try { accOnSpeed = com.overdrive.app.monitor.AccMonitor.isAccOn(); } catch (Throwable ignored) {}
            if (!accOnSpeed) {
                payload.put("speed", 0);
            } else if (vd != null && !Double.isNaN(vd.speedKmh)) {
                payload.put("speed", vd.speedKmh);
            } else if (gpsMonitor.hasLocation()) {
                payload.put("speed", gpsMonitor.getSpeed() * 3.6);
            }

            // lat, lon
            if (gpsMonitor.hasLocation()) {
                payload.put("lat", gpsMonitor.getLatitude());
                payload.put("lon", gpsMonitor.getLongitude());
            }

            // is_charging — BMS state primary, with gun-connected + power-flowing
            // as a fallback for PHEVs that leave BMS state at IDLE while charging.
            ChargingStateData chargingState = vehicleDataMonitor.getChargingState();
            boolean isCharging = chargingState != null
                    && chargingState.status == ChargingStateData.ChargingStatus.CHARGING;
            if (!isCharging && vd != null) {
                boolean gunConnected = vd.chargingGunState == 2
                        || vd.chargingGunState == 3;
                boolean powerFlowing = (!Double.isNaN(vd.externalChargingPowerKw)
                                && vd.externalChargingPowerKw > 0.15)
                        || (!Double.isNaN(vd.chargingPowerKw)
                                && vd.chargingPowerKw > 0.15);
                if (gunConnected && powerFlowing) isCharging = true;
            }
            payload.put("is_charging", isCharging ? 1 : 0);

            // is_dcfc
            boolean v2l = false;
            if (vd != null && vd.chargingGunState != BydVehicleData.UNAVAILABLE) {
                payload.put("is_dcfc", vd.chargingGunState == 3 ? 1 : 0);
                // V2L is gun state 5 (VTOL), NOT 4. Per BYDAutoChargingDevice:
                // 2=AC, 3=DC, 4=AC_DC (a real combined charging gun), 5=VTOL. The
                // old `== 4` mislabelled genuine AC_DC charging as V2L — forcing
                // is_charging=0 (and, once charge_power gated on v2l, 0 kW) during a
                // real charge. Every other site (BydDataCollector isVtol, ChargingDetector
                // gunPlausible) correctly treats 5 as V2L and 4 as charging.
                if (vd.chargingGunState == 5) { payload.put("is_charging", 0); v2l = true; } // V2L (VTOL)
            }

            // charge_power — DC charge power into the pack (kW). Prefer the direct
            // getChargePower() reading; when it's absent (dead on PHEV) fall back to
            // the resolved getChargingState().chargingPowerKW — the SAME value the app
            // UI and ABRP use (SOC-derived ring estimator on PHEV) — so all surfaces
            // agree. getChargePower() returns ~359 garbage when idle, so gate on the
            // charging state and a sane upper bound; 0 otherwise.
            double chargeKw = 0;
            if (isCharging && !v2l) {
                if (vd != null && !Double.isNaN(vd.chargePowerKw)
                        && vd.chargePowerKw > 0.1 && vd.chargePowerKw <= 300) {
                    chargeKw = vd.chargePowerKw;
                } else if (chargingState != null
                        // !isEstimated: keep the nominal placeholder (3.3/7.0 kW) and inferred
                        // engine-power figures out of the MQTT/Home-Assistant feed, which charts
                        // this as measured charge power. The raw HAL getter above is unaffected.
                        && !chargingState.isEstimated
                        && !Double.isNaN(chargingState.chargingPowerKW)
                        && chargingState.chargingPowerKW > 0.1
                        && chargingState.chargingPowerKW <= 300) {
                    chargeKw = chargingState.chargingPowerKW;
                }
            }
            payload.put("charge_power", chargeKw);

            // is_parked — gear==P, OR the car is powered off. When ACC is off the gear signal
            // isn't actively polled and carries forward its last value (e.g. R after backing into
            // a spot), so gear alone would wrongly report not-parked while the car sits switched
            // off. A powered-off car is always parked.
            // Gear source preference: the 5Hz GearMonitor poller (fresh within ~200ms) over the
            // 5s/90s collector snapshot — via the snapshot a P→D shift took 10-14s to reach
            // consumers. Snapshot stays as the fallback when the monitor isn't running.
            boolean isParked = false;
            if (gearMonitor.isActive()) {
                isParked = gearMonitor.getCurrentGear() == GearMonitor.GEAR_P;
            } else if (vd != null && vd.gearMode != BydVehicleData.UNAVAILABLE) {
                isParked = vd.gearMode == GearMonitor.GEAR_P;
            } else {
                isParked = gearMonitor.getCurrentGear() == GearMonitor.GEAR_P;
            }
            if (!isParked) {
                try { if (!com.overdrive.app.monitor.AccMonitor.isAccOn()) isParked = true; }
                catch (Throwable ignored) {}
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

            // soh — use the DISPLAYED (capped, anchored) value so MQTT agrees with
            // the dashboard/health card. The internal live median can differ from
            // the headline; getDisplaySoh is the single number every surface shows.
            if (sohEstimator != null && sohEstimator.hasDisplaySoh()) {
                payload.put("soh", sohEstimator.getDisplaySoh());
            }

            // capacity (remaining kWh) — single source of truth (SOC×nominal×SOH on
            // PHEV; gated raw on BEV). NEVER raw vd.remainKwh, which on PHEV is the
            // unreliable/frozen getter and would diverge ~35% from the UI.
            double capKwh = VehicleDataMonitor.getInstance().getBatteryRemainPowerKwh();
            if (capKwh > 0) {
                payload.put("capacity", capKwh);
            }

            // gear (extra field not in ABRP — useful for MQTT consumers)
            // ACC off → force P: the bus gear signal (and GearMonitor's poll) FREEZES at
            // the last driven gear when the car powers off (e.g. R after backing into a
            // spot), but the car physically auto-shifts to P at shutdown — it cannot sit
            // powered-off in R. Same stale-signal handling as speed→0 and power→0 above;
            // the ACC-edge flush ships the P within a couple of seconds of key-off.
            // While ACC is on: prefer the 5Hz GearMonitor poller (fresh within ~200ms)
            // over the 5s/90s collector snapshot — via the snapshot a P→D shift took
            // 10-14s to reach HA, and the gearbox SDK listener can't be used (crashes
            // as uid 2000). The snapshot stays as the fallback when the monitor isn't
            // running.
            boolean accOnGear = false;
            try { accOnGear = com.overdrive.app.monitor.AccMonitor.isAccOn(); } catch (Throwable ignored) {}
            if (!accOnGear) {
                payload.put("gear", GearMonitor.gearToString(GearMonitor.GEAR_P));
            } else if (gearMonitor.isActive()) {
                payload.put("gear", GearMonitor.gearToString(gearMonitor.getCurrentGear()));
            } else if (vd != null && vd.gearMode != BydVehicleData.UNAVAILABLE) {
                payload.put("gear", GearMonitor.gearToString(vd.gearMode));
            } else {
                payload.put("gear", GearMonitor.gearToString(gearMonitor.getCurrentGear()));
            }

            // ==================== EXTENDED TELEMETRY (BYD API overhaul) ====================
            if (vd != null) {
                // OEM SOH (raw value from BMS, separate from SohEstimator)
                if (!Double.isNaN(vd.sohPercent)) payload.put("soh_oem", vd.sohPercent);

                // Charging ETA
                if (vd.chargingRestTimeHours != BydVehicleData.UNAVAILABLE)
                    payload.put("charging_eta_hours", vd.chargingRestTimeHours);
                if (vd.chargingRestTimeMinutes != BydVehicleData.UNAVAILABLE)
                    payload.put("charging_eta_minutes", vd.chargingRestTimeMinutes);

                // Trip data
                if (!Double.isNaN(vd.currentTripMileageKm)) payload.put("trip_km", vd.currentTripMileageKm);
                if (!Double.isNaN(vd.currentTripTimeHours)) payload.put("trip_hours", vd.currentTripTimeHours);
                if (!Double.isNaN(vd.currentTripConsumptionKwh)) payload.put("trip_kwh", vd.currentTripConsumptionKwh);

                // Efficiency
                if (!Double.isNaN(vd.last50KmConsumption)) payload.put("consumption_50km", vd.last50KmConsumption);

                // Driving time
                if (!Double.isNaN(vd.drivingTimeHours)) payload.put("driving_time_hours", vd.drivingTimeHours);

                // Key battery
                if (vd.keyBatteryLevel != BydVehicleData.UNAVAILABLE) payload.put("key_battery", vd.keyBatteryLevel);

                // EV range
                if (vd.elecRangeKm != BydVehicleData.UNAVAILABLE) payload.put("ev_range_km", vd.elecRangeKm);

                // Cabin temp
                if (!Double.isNaN(vd.insideTempCelsius)) payload.put("cabin_temp", vd.insideTempCelsius);

                // ==================== FULL PARITY (every remaining BydVehicleData field) ====================
                // Identity
                if (vd.vin != null) payload.put("vin", vd.vin);

                // HV battery — pack/cell voltage (range-gated to filter phantom zeros / OBD glitches)
                if (!Double.isNaN(vd.hvPackVoltage) && vd.hvPackVoltage >= 100 && vd.hvPackVoltage <= 1000)
                    payload.put("hv_pack_v", vd.hvPackVoltage);
                if (!Double.isNaN(vd.highCellVoltage) && vd.highCellVoltage >= 2.0 && vd.highCellVoltage <= 4.5)
                    payload.put("cell_v_max", vd.highCellVoltage);
                if (!Double.isNaN(vd.lowCellVoltage) && vd.lowCellVoltage >= 2.0 && vd.lowCellVoltage <= 4.5)
                    payload.put("cell_v_min", vd.lowCellVoltage);
                double cellVDelta = vd.getCellVoltageDelta();
                if (!Double.isNaN(cellVDelta) && cellVDelta >= 0 && cellVDelta <= 1.0)
                    payload.put("cell_v_delta", cellVDelta);
                if (!Double.isNaN(vd.socHevPercent) && vd.socHevPercent >= 0 && vd.socHevPercent <= 100)
                    payload.put("soc_hev", vd.socHevPercent);
                if (!Double.isNaN(vd.capacityAh) && vd.capacityAh > 0 && vd.capacityAh <= 1000)
                    payload.put("capacity_ah", vd.capacityAh);

                // HV battery — temperature (max/min/avg + delta + auxiliary). Range gate matches batt_temp.
                if (!Double.isNaN(vd.highCellTempC) && vd.highCellTempC >= -40 && vd.highCellTempC <= 80)
                    payload.put("cell_t_max", vd.highCellTempC);
                if (!Double.isNaN(vd.lowCellTempC) && vd.lowCellTempC >= -40 && vd.lowCellTempC <= 80)
                    payload.put("cell_t_min", vd.lowCellTempC);
                if (!Double.isNaN(vd.avgCellTempC) && vd.avgCellTempC >= -40 && vd.avgCellTempC <= 80)
                    payload.put("cell_t_avg", vd.avgCellTempC);
                double cellTDelta = vd.getCellTempDelta();
                if (!Double.isNaN(cellTDelta) && cellTDelta >= 0 && cellTDelta <= 50)
                    payload.put("cell_t_delta", cellTDelta);
                if (!Double.isNaN(vd.waterTempC) && vd.waterTempC >= -40 && vd.waterTempC <= 130)
                    payload.put("coolant_temp", vd.waterTempC);
                if (!Double.isNaN(vd.bodyworkBattTempC) && vd.bodyworkBattTempC >= -40 && vd.bodyworkBattTempC <= 80)
                    payload.put("bodywork_batt_temp", vd.bodyworkBattTempC);
                // No band here: insideTempC is validated at the SOURCE for both of its producers —
                // the HAL read (BydDataCollector.readCabinTempC) and the cloud fallback in
                // mergeCloudData — which share one isPlausibleCabinTempC definition (sentinels
                // rejected, physical range enforced). A second, TIGHTER clip here made this
                // disagree with cabin_temp, which comes from the same reader with no band: a
                // genuinely hot parked cabin (85 C) was published as cabin_temp while inside_temp
                // silently held its last <=80 value, leaving two HA entities from one sensor
                // reporting different temperatures.
                if (!Double.isNaN(vd.insideTempC))
                    payload.put("inside_temp", vd.insideTempC);

                // 12V battery (voltage12v is already source-validated to 8.0–16.0V in BydDataCollector)
                if (!Double.isNaN(vd.voltage12v)) payload.put("volt_12v", vd.voltage12v);
                if (vd.voltageLevelRaw != BydVehicleData.UNAVAILABLE) payload.put("volt_12v_level", vd.voltageLevelRaw);
                if (vd.battery12vLevel != BydVehicleData.UNAVAILABLE) payload.put("batt_12v_level", vd.battery12vLevel);

                // Motor / drivetrain
                if (vd.frontMotorSpeed != BydVehicleData.UNAVAILABLE
                        && vd.frontMotorSpeed >= -25000 && vd.frontMotorSpeed <= 25000)
                    payload.put("motor_front_rpm", vd.frontMotorSpeed);
                if (vd.rearMotorSpeed != BydVehicleData.UNAVAILABLE
                        && vd.rearMotorSpeed >= -25000 && vd.rearMotorSpeed <= 25000)
                    payload.put("motor_rear_rpm", vd.rearMotorSpeed);
                if (!Double.isNaN(vd.frontMotorTorque)
                        && vd.frontMotorTorque >= -2000 && vd.frontMotorTorque <= 2000)
                    payload.put("motor_front_torque", vd.frontMotorTorque);
                if (vd.engineSpeedRpm != BydVehicleData.UNAVAILABLE
                        && vd.engineSpeedRpm >= 0 && vd.engineSpeedRpm <= 15000)
                    payload.put("engine_rpm", vd.engineSpeedRpm);
                if (vd.accelPercent != BydVehicleData.UNAVAILABLE
                        && vd.accelPercent >= 0 && vd.accelPercent <= 100)
                    payload.put("accel_pct", vd.accelPercent);
                if (vd.brakePercent != BydVehicleData.UNAVAILABLE
                        && vd.brakePercent >= 0 && vd.brakePercent <= 100)
                    payload.put("brake_pct", vd.brakePercent);
                if (!Double.isNaN(vd.steeringAngleDegrees)
                        && vd.steeringAngleDegrees >= -1080 && vd.steeringAngleDegrees <= 1080)
                    payload.put("steering_deg", vd.steeringAngleDegrees);
                if (!Double.isNaN(vd.slopeDegrees)
                        && vd.slopeDegrees >= -90 && vd.slopeDegrees <= 90)
                    payload.put("slope_deg", vd.slopeDegrees);

                // Energy / range / consumption
                if (vd.energyMode != BydVehicleData.UNAVAILABLE) payload.put("energy_mode", vd.energyMode);
                if (vd.operationMode != BydVehicleData.UNAVAILABLE) payload.put("op_mode", vd.operationMode);
                if (!Double.isNaN(vd.totalElecCon) && vd.totalElecCon >= 0) payload.put("total_elec_con", vd.totalElecCon);
                if (!Double.isNaN(vd.totalFuelCon) && vd.totalFuelCon >= 0) payload.put("total_fuel_con", vd.totalFuelCon);
                if (vd.fuelRangeKm != BydVehicleData.UNAVAILABLE
                        && vd.fuelRangeKm >= 0 && vd.fuelRangeKm <= 3000) payload.put("fuel_range_km", vd.fuelRangeKm);
                if (!Double.isNaN(vd.fuelPercent) && vd.fuelPercent >= 0 && vd.fuelPercent <= 100)
                    payload.put("fuel_pct", vd.fuelPercent);
                if (vd.bodyworkRangeKm != BydVehicleData.UNAVAILABLE
                        && vd.bodyworkRangeKm >= 0 && vd.bodyworkRangeKm <= 3000) payload.put("bodywork_range_km", vd.bodyworkRangeKm);
                if (vd.evMileageKm != BydVehicleData.UNAVAILABLE && vd.evMileageKm >= 0)
                    payload.put("ev_mileage_km", vd.evMileageKm);

                // Charging detail
                if (vd.chargingState != BydVehicleData.UNAVAILABLE) payload.put("charging_state", vd.chargingState);
                if (vd.chargerWorkState != BydVehicleData.UNAVAILABLE) payload.put("charger_state", vd.chargerWorkState);
                if (vd.chargingMode != BydVehicleData.UNAVAILABLE) payload.put("charging_mode", vd.chargingMode);
                if (vd.chargingGunState != BydVehicleData.UNAVAILABLE) payload.put("charging_gun", vd.chargingGunState);
                if (vd.chargingType != BydVehicleData.UNAVAILABLE) payload.put("charging_type", vd.chargingType);
                if (vd.chargingPercent != BydVehicleData.UNAVAILABLE
                        && vd.chargingPercent >= 0 && vd.chargingPercent <= 100)
                    payload.put("charging_pct", vd.chargingPercent);
                if (!Double.isNaN(vd.chargingCapacityKwh) && vd.chargingCapacityKwh >= 0
                        && vd.chargingCapacityKwh <= 1000)
                    payload.put("charging_capacity_kwh", vd.chargingCapacityKwh);
                payload.put("charging_v2l", vd.vtolCharging ? 1 : 0);
                if (vd.wirelessChargingLeftState != BydVehicleData.UNAVAILABLE) payload.put("wireless_charging_left", vd.wirelessChargingLeftState);
                if (vd.wirelessChargingRightState != BydVehicleData.UNAVAILABLE) payload.put("wireless_charging_right", vd.wirelessChargingRightState);
                if (vd.wirelessChargingStatus != BydVehicleData.UNAVAILABLE) payload.put("wireless_charging_status", vd.wirelessChargingStatus);

                // Tyres — flat per-corner keys (FL/FR/RL/RR). Pressure in kPa, gate to plausible 0–600 range
                // (an unset/error reading often returns 0 or a sentinel; skip those individually).
                if (vd.tyrePressure != null && vd.tyrePressure.length >= 4) {
                    String[] corners = {"tyre_p_fl", "tyre_p_fr", "tyre_p_rl", "tyre_p_rr"};
                    for (int i = 0; i < 4; i++) {
                        int p = vd.tyrePressure[i];
                        if (p > 0 && p <= 600) payload.put(corners[i], p);
                    }
                }
                if (vd.tyrePressureState != null && vd.tyrePressureState.length >= 4) {
                    payload.put("tyre_p_state_fl", vd.tyrePressureState[0]);
                    payload.put("tyre_p_state_fr", vd.tyrePressureState[1]);
                    payload.put("tyre_p_state_rl", vd.tyrePressureState[2]);
                    payload.put("tyre_p_state_rr", vd.tyrePressureState[3]);
                }
                if (vd.tyreAirLeakState != null && vd.tyreAirLeakState.length >= 4) {
                    payload.put("tyre_leak_fl", vd.tyreAirLeakState[0]);
                    payload.put("tyre_leak_fr", vd.tyreAirLeakState[1]);
                    payload.put("tyre_leak_rl", vd.tyreAirLeakState[2]);
                    payload.put("tyre_leak_rr", vd.tyreAirLeakState[3]);
                }
                if (vd.tyreSignalState != null && vd.tyreSignalState.length >= 4) {
                    payload.put("tyre_signal_fl", vd.tyreSignalState[0]);
                    payload.put("tyre_signal_fr", vd.tyreSignalState[1]);
                    payload.put("tyre_signal_rl", vd.tyreSignalState[2]);
                    payload.put("tyre_signal_rr", vd.tyreSignalState[3]);
                }
                // Per-tyre temperature: emit only corners with plausible readings.
                // Most BYD firmwares leave these UNAVAILABLE; some return 0 when stale.
                if (vd.tyreTemperature != null && vd.tyreTemperature.length >= 4) {
                    String[] tCorners = {"tyre_t_fl", "tyre_t_fr", "tyre_t_rl", "tyre_t_rr"};
                    for (int i = 0; i < 4; i++) {
                        int t = vd.tyreTemperature[i];
                        if (t != BydVehicleData.UNAVAILABLE && t >= -40 && t <= 120) {
                            payload.put(tCorners[i], t);
                        }
                    }
                }
                if (vd.tyreSystemState != BydVehicleData.UNAVAILABLE) payload.put("tyre_system_state", vd.tyreSystemState);
                if (vd.tyreTemperatureState != BydVehicleData.UNAVAILABLE) payload.put("tyre_temp_state", vd.tyreTemperatureState);

                // Doors / windows — array values at flat keys
                if (vd.doorLockStatus != null) {
                    JSONArray a = new JSONArray();
                    for (int s : vd.doorLockStatus) a.put(s);
                    payload.put("door_lock", a);
                }
                if (vd.windowOpenPercent != null) {
                    JSONArray a = new JSONArray();
                    for (int p : vd.windowOpenPercent) a.put(p);
                    payload.put("window_open", a);
                }

                // Lights
                if (vd.leftTurnState != BydVehicleData.UNAVAILABLE) payload.put("light_left_turn", vd.leftTurnState);
                if (vd.rightTurnState != BydVehicleData.UNAVAILABLE) payload.put("light_right_turn", vd.rightTurnState);
                payload.put("light_low_beam", vd.lowBeam ? 1 : 0);
                payload.put("light_high_beam", vd.highBeam ? 1 : 0);
                payload.put("light_rear_fog", vd.rearFog ? 1 : 0);
                payload.put("light_front_fog", vd.frontFog ? 1 : 0);
                payload.put("light_hazard", vd.hazard ? 1 : 0);
                payload.put("light_drl", vd.dayTimeLight ? 1 : 0);
                payload.put("ambient_colour", vd.ambientColour);
                // Ambient main switch: only published when actually readable, so a trim that
                // cannot report it leaves the entity unavailable instead of showing a wrong "off".
                if (vd.ambientEnabled != BydVehicleData.UNAVAILABLE) {
                    payload.put("ambient_enabled", vd.ambientEnabled);
                }

                // Climate
                if (vd.acStartState != BydVehicleData.UNAVAILABLE) payload.put("ac_on", vd.acStartState);
                if (vd.acCycleMode != BydVehicleData.UNAVAILABLE) payload.put("ac_cycle", vd.acCycleMode);
                if (vd.acWindMode != BydVehicleData.UNAVAILABLE) payload.put("ac_wind", vd.acWindMode);
                if (vd.acFanLevel != BydVehicleData.UNAVAILABLE) payload.put("ac_fan", vd.acFanLevel);
                if (vd.tempUnit != BydVehicleData.UNAVAILABLE) payload.put("temp_unit", vd.tempUnit);
                // Real dial readback. The climate entity's temperature_state_topic pointed at
                // `climate_setpoint`, which only ever carried an OPTIMISTIC echo of our own
                // write — so before OverDrive ever set the temperature HA showed nothing, and
                // turning the physical dial left the echo stale. Publishing the polled setpoint
                // to the same key makes it a true state topic; the echo now just fills the gap
                // until the next poll instead of being the only source.
                //
                // Published in CELSIUS. The dial is read in the head unit's display unit, but the
                // HA climate entity declares min_temp 17 / max_temp 33 and its command topic takes
                // Celsius — so a raw °F value (72) would render as 72 inside a 17..33 slider and
                // read as a wildly hot cabin. Converting here keeps the state and command sides in
                // the same scale; `temp_unit` above still tells a consumer what the car displays.
                if (vd.acSetpointDriver != BydVehicleData.UNAVAILABLE) {
                    payload.put("climate_setpoint", setpointToCelsius(vd.acSetpointDriver));
                }
                if (vd.acSetpointPassenger != BydVehicleData.UNAVAILABLE) {
                    payload.put("climate_setpoint_passenger", setpointToCelsius(vd.acSetpointPassenger));
                }

                // Seats
                if (vd.seatbeltStatus != null) {
                    // Per-seat UNAVAILABLE → null, not the raw sentinel. readSeatbeltPair returns
                    // null only when BOTH seats are unreadable, so a mixed pair (one seat
                    // UNAVAILABLE, the other real) is published — and -2147483648 on a SAFETY
                    // signal reads as a garbage/truthy "buckled" to an MQTT consumer.
                    JSONArray a = new JSONArray();
                    for (int s : vd.seatbeltStatus) {
                        a.put(s == BydVehicleData.UNAVAILABLE ? JSONObject.NULL : (Object) s);
                    }
                    payload.put("seatbelt", a);
                }
                if (vd.seatHeat != null) {
                    JSONArray a = new JSONArray();
                    for (int s : vd.seatHeat) a.put(s);
                    payload.put("seat_heat", a);
                }
                if (vd.seatCool != null) {
                    JSONArray a = new JSONArray();
                    for (int s : vd.seatCool) a.put(s);
                    payload.put("seat_cool", a);
                }

                // Bodywork
                if (vd.wiperState != BydVehicleData.UNAVAILABLE) payload.put("wiper_state", vd.wiperState);
                if (vd.sunroofState != BydVehicleData.UNAVAILABLE) payload.put("sunroof_state", vd.sunroofState);
                if (vd.sunroofPosition != BydVehicleData.UNAVAILABLE) payload.put("sunroof_pos", vd.sunroofPosition);
                if (vd.sunshadePercent != BydVehicleData.UNAVAILABLE) payload.put("sunshade_pct", vd.sunshadePercent);
                payload.put("drift_mode", vd.driftModeEnabled ? 1 : 0);

                // Engine (PHEV)
                if (vd.engineCoolantLevel != BydVehicleData.UNAVAILABLE) payload.put("engine_coolant_level", vd.engineCoolantLevel);
                if (vd.oilLevel != BydVehicleData.UNAVAILABLE) payload.put("oil_level", vd.oilLevel);
                if (vd.engineCode != null) payload.put("engine_code", vd.engineCode);

                // Safety / radar
                if (vd.passengerDetection != null) {
                    JSONArray a = new JSONArray();
                    for (int p : vd.passengerDetection) a.put(p);
                    payload.put("passenger_detection", a);
                }
                if (vd.emergencyAlarmState != BydVehicleData.UNAVAILABLE) payload.put("emergency_alarm", vd.emergencyAlarmState);
                if (vd.powerLevel != BydVehicleData.UNAVAILABLE) payload.put("power_level", vd.powerLevel);
                if (vd.mcuStatus != BydVehicleData.UNAVAILABLE) payload.put("mcu_status", vd.mcuStatus);
                if (vd.radarDistances != null) {
                    JSONArray a = new JSONArray();
                    for (int d : vd.radarDistances) a.put(d);
                    payload.put("radar_distances", a);
                }
                payload.put("speed_limit_warning", vd.speedLimitWarning ? 1 : 0);
                // Child Presence Detection: SDK reports 1=on, 2=off, 3=delay. Publish 1/0 for the
                // adas_cpd switch state_topic, treating delay(3) as on to match the REST read-back
                // (VehicleControlApiHandler: childPresenceDetection != 2). Only publish a known state
                // (1-3); 0/UNAVAILABLE means never polled, so leave it absent (HA shows unavailable).
                if (vd.childPresenceDetection >= 1 && vd.childPresenceDetection <= 3)
                    payload.put("child_presence_detection", vd.childPresenceDetection != 2 ? 1 : 0);

                // Air quality (negative readings are sensor errors)
                if (vd.pm25Inside != BydVehicleData.UNAVAILABLE && vd.pm25Inside >= 0 && vd.pm25Inside <= 1000)
                    payload.put("pm25_inside", vd.pm25Inside);
                if (vd.pm25Outside != BydVehicleData.UNAVAILABLE && vd.pm25Outside >= 0 && vd.pm25Outside <= 1000)
                    payload.put("pm25_outside", vd.pm25Outside);

                // Key proximity
                if (vd.keyStartState != BydVehicleData.UNAVAILABLE) payload.put("key_start_state", vd.keyStartState);
                if (vd.keyMissingInd != BydVehicleData.UNAVAILABLE) payload.put("key_missing", vd.keyMissingInd);
                if (vd.keyBtLowPowerMode != BydVehicleData.UNAVAILABLE) payload.put("key_bt_low_power", vd.keyBtLowPowerMode);
                if (vd.keyPowerLowInd != BydVehicleData.UNAVAILABLE) payload.put("key_power_low", vd.keyPowerLowInd);
                if (vd.keyDetectionReminder != BydVehicleData.UNAVAILABLE) payload.put("key_detection_reminder", vd.keyDetectionReminder);
                if (vd.smartKeyWarnState != BydVehicleData.UNAVAILABLE) payload.put("smart_key_warn", vd.smartKeyWarnState);

                // Snapshot timestamp (when BydDataCollector polled the SDK; differs from `utc` if cached)
                if (vd.timestamp > 0) payload.put("vd_timestamp", vd.timestamp / 1000);
            }

        } catch (Exception e) {
            logger.error("Telemetry collection error: " + e.getMessage());
        }

        // Tier 3: append curated CAN-backed car settings (setting_<key>) for HA read-back,
        // but only when some enabled connection actually exposes vehicle control — otherwise
        // we'd hit the carsettings provider for no consumer.
        if (anyControlEnabled()) {
            try {
                com.overdrive.app.byd.BydCarSettings.getInstance().snapshotInto(payload);
            } catch (Exception e) {
                logger.debug("Car settings snapshot failed: " + e.getMessage());
            }
        }

        // Update the cache
        lastCachedTelemetry = payload;
        lastCollectionTimeMs = now;

        return payload;
    }

    /** True if any enabled connection has vehicle control turned on. */
    /**
     * A dial setpoint (read in the head unit's DISPLAY unit) as whole Celsius, so the MQTT state
     * matches the HA climate entity's declared 17..33 bounds and its Celsius command topic.
     *
     * <p>The unit is decided by the value's own band rather than {@code temp_unit}: the two dial
     * ranges are disjoint (17..33 vs 64..91), so the reading identifies its own scale, and a
     * value already in Celsius must pass through untouched.
     */
    private static int setpointToCelsius(int setpoint) {
        if (setpoint >= BydDataCollector.AC_SETPOINT_MIN_F && setpoint <= BydDataCollector.AC_SETPOINT_MAX_F) {
            return (int) Math.round((setpoint - 32) * 5.0 / 9.0);
        }
        return setpoint;   // already Celsius (or outside both bands — publish verbatim)
    }

    private boolean anyControlEnabled() {
        try {
            for (MqttConnectionConfig cfg : store.getEnabled()) {
                if (cfg.isControlEnabled()) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ==================== GETTERS ====================

    public MqttConnectionStore getStore() { return store; }
    public boolean isInitialized() { return initialized; }
    public int getActiveCount() { return publishers.size(); }

    /**
     * Publish a message to every active connection — the fan-out the automation
     * "Publish MQTT" action calls. Each publisher scopes a relative topic under its own
     * base topic (see {@link MqttPublisherService#publishToTopic}). Returns the number of
     * connections that accepted the publish (0 when none are configured/connected, which
     * makes the action a clean no-op on a car with no MQTT setup). Never throws.
     *
     * @param topic   relative (scoped under each connection's base) or absolute ("/…")
     * @param payload the message body (any string; JSON or plain)
     * @param retain  whether the broker should retain it (HA state topics want true)
     * @return count of connections that published successfully
     */
    public int publishToAll(String topic, String payload, boolean retain) {
        int ok = 0;
        for (MqttPublisherService pub : publishers.values()) {
            try {
                if (pub.publishToTopic(topic, payload, retain)) ok++;
            } catch (Throwable ignored) { /* one bad connection never blocks the rest */ }
        }
        return ok;
    }
}
