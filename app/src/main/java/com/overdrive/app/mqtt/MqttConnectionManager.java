package com.overdrive.app.mqtt;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.monitor.SocHistoryDatabase;
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
    private volatile long lastCachedCabinExpiresAtMs = 0;
    private static final long TELEMETRY_CACHE_TTL_MS = 2000; // 2 seconds

    private static final class CollectedTelemetry {
        final JSONObject payload;
        final long cabinExpiresAtMs;

        CollectedTelemetry(JSONObject payload, long cabinExpiresAtMs) {
            this.payload = payload;
            this.cabinExpiresAtMs = cabinExpiresAtMs;
        }
    }

    private volatile boolean initialized = false;

    // One-way shutdown latch + lifecycle mutex. stopAll() is only ever called on
    // daemon shutdown (never followed by a restart of the same instance), but it
    // runs on the shutdown thread while add/update tasks queued on controlExecutor
    // may still be mid-startConnection(): without coordination, a publisher whose
    // connect() was in flight when stopAll() cleared the maps lands in the map
    // AFTER the clear — a live, connected client with no scheduler and nothing to
    // ever disconnect it. lifecycleLock serializes startConnection/stopConnection/
    // stopAll; the stopped flag makes any start that loses the race a no-op.
    // A dedicated lock (not the instance monitor) so it can't interact with
    // collectTelemetry()'s synchronized(this).
    private volatile boolean stopped = false;
    private final Object lifecycleLock = new Object();

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

        // Refuse new lifecycle work first (visible before we take the lock), then
        // stop accepting queued control tasks. shutdownNow() interrupts an idle
        // control thread; a task already inside startConnection() holds
        // lifecycleLock and is waited out below, then sees stopped and unwinds.
        stopped = true;
        controlExecutor.shutdownNow();

        synchronized (lifecycleLock) {
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
        }

        // All connections are down — now it's safe to clear the process-global
        // SOCKS proxy properties (individual disconnect() no longer does this, to
        // avoid one connection stomping a sibling's still-needed proxy routing).
        // Under the shared props lock so we can't stomp a connect that is mid
        // socket-creation elsewhere (e.g. the BYD cloud subscriber).
        synchronized (ProxyHelper.SOCKS_PROPS_LOCK) {
            System.clearProperty("socksProxyHost");
            System.clearProperty("socksProxyPort");
        }

        logger.info("All MQTT connections stopped");
    }

    // ==================== CONNECTION MANAGEMENT ====================

    /**
     * Start a single connection's publish loop.
     */
    private void startConnection(MqttConnectionConfig config) {
        synchronized (lifecycleLock) {
            // Lost the race against stopAll() (daemon shutdown) — don't create a
            // publisher nobody will ever tear down.
            if (stopped) {
                logger.info("Skipping start of " + config.name + " — manager is stopped");
                return;
            }

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
            long cadenceDelayMs = Math.max(1, config.minIntervalSeconds) * 1000L;
            scheduleNext(config.id, scheduler,
                    Math.min(cadenceDelayMs, currentCabinExpiryDelayMs()));
        }
    }

    /**
     * Stop a single connection.
     */
    private void stopConnection(String connectionId) {
        synchronized (lifecycleLock) {
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
            // re-asserts/clears authoritatively from the current proxy state. Under the
            // shared props lock so the clear can't land mid socket-creation of another
            // props-sensitive connect (e.g. the BYD cloud subscriber).
            if (publishers.isEmpty()) {
                synchronized (ProxyHelper.SOCKS_PROPS_LOCK) {
                    System.clearProperty("socksProxyHost");
                    System.clearProperty("socksProxyPort");
                }
            }
        }
    }

    /**
     * Schedule the next publish for a connection on its own scheduler.
     *
     * The scheduler is passed in (not looked up) so a trailing cycle from a scheduler that has
     * since been replaced by a restart can't queue work onto the new one — it simply no-ops.
     */
    private void scheduleNext(String connectionId, ScheduledExecutorService scheduler, long delayMs) {
        if (scheduler == null || scheduler.isShutdown()) return;
        // This scheduler was swapped out by a restart — drop the reschedule.
        if (schedulers.get(connectionId) != scheduler) return;

        try {
            ScheduledFuture<?> task = scheduler.schedule(() -> runPublishCycle(connectionId, scheduler),
                    Math.max(1L, delayMs), TimeUnit.MILLISECONDS);
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
        long payloadCabinExpiresAtMs = 0L;

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
            CollectedTelemetry telemetry = collectTelemetry();
            JSONObject payload = telemetry.payload;
            payloadCabinExpiresAtMs = telemetry.cabinExpiresAtMs;

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
        long nextDelayMs = Math.max(1, config.minIntervalSeconds) * 1000L;

        // Apply backoff if failing
        long backoff = publisher.getBackoffSeconds();
        if (backoff * 1000L > nextDelayMs) {
            nextDelayMs = backoff * 1000L;
        }

        nextDelayMs = Math.min(nextDelayMs,
                delayUntilCabinExpiryMs(payloadCabinExpiresAtMs, System.currentTimeMillis()));

        // Schedule next on the same scheduler this cycle ran on.
        scheduleNext(connectionId, scheduler, nextDelayMs);
    }

    private long currentCabinExpiryDelayMs() {
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            BydVehicleData data = collector.isInitialized() ? collector.getData() : null;
            return delayUntilCabinExpiryMs(cabinExpiryAtMs(data), System.currentTimeMillis());
        } catch (Throwable ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long delayUntilCabinExpiryMs(long expiresAtMs, long nowMs) {
        if (expiresAtMs <= nowMs) return Long.MAX_VALUE;
        return expiresAtMs - nowMs;
    }

    private static long cabinExpiryAtMs(BydVehicleData data) {
        if (data == null || data.insideTempReadAt <= 0L
                || (Double.isNaN(data.insideTempC) && Double.isNaN(data.insideTempCelsius))) {
            return 0L;
        }
        if (data.insideTempReadAt > Long.MAX_VALUE - BydVehicleData.CABIN_TEMP_MAX_AGE_MS) {
            return Long.MAX_VALUE;
        }
        return data.insideTempReadAt + BydVehicleData.CABIN_TEMP_MAX_AGE_MS;
    }

    // ==================== CRUD OPERATIONS (called from IPC) ====================

    /**
     * Add a new MQTT connection.
     * @return the added config (with generated ID), or null if max reached
     */
    /**
     * Enqueue lifecycle work on the control executor, tolerating the shutdown race:
     * stopAll() calls controlExecutor.shutdownNow(), after which execute() throws
     * RejectedExecutionException — and the IPC server can still dispatch MQTT CRUD
     * for a moment during daemon shutdown (stopAll() runs before the servers stop).
     * Uncaught, the REE aborted the CRUD method mid-way: deleteConnection() lost its
     * store.delete(), and add/update returned an error for a store change that had
     * already persisted. Dropping the LIVE-connection side effect is correct here —
     * the store change survives and takes effect on the next daemon start.
     *
     * @return true if enqueued, false if the manager is stopped (work skipped)
     */
    private boolean submitControl(Runnable task) {
        if (stopped) return false;
        try {
            controlExecutor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            logger.info("Manager stopped — skipping queued connection lifecycle work");
            return false;
        }
    }

    public MqttConnectionConfig addConnection(JSONObject configJson) {
        MqttConnectionConfig config = MqttConnectionConfig.fromJson(configJson);
        // Ensure fresh ID
        config.id = java.util.UUID.randomUUID().toString().substring(0, 8);

        MqttConnectionConfig added = store.add(config);
        if (added == null) return null;

        // Auto-start if enabled — off the caller's thread (connect() blocks).
        if (added.enabled && added.isConfigured()) {
            submitControl(() -> startConnection(added));
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
            submitControl(() -> {
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
        // submitControl (vs raw execute) so a shutdown race can't throw past the
        // store.delete below — the config removal must persist even when the live
        // teardown is skipped (stopAll() is tearing everything down anyway).
        submitControl(() -> {
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
        return collectTelemetry().payload;
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
    private synchronized CollectedTelemetry collectTelemetry() {
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
        if (lastCachedTelemetry != null && (now - lastCollectionTimeMs) < TELEMETRY_CACHE_TTL_MS
                && (lastCachedCabinExpiresAtMs <= 0L || now < lastCachedCabinExpiresAtMs)) {
            return new CollectedTelemetry(
                    withLiveGps(lastCachedTelemetry), lastCachedCabinExpiresAtMs);
        }

        JSONObject payload = new JSONObject();
        long cabinExpiresAtMs = 0L;

        try {
            // These are retained Home Assistant topics. Keep explicit tombstones in every
            // snapshot, including collector startup/failure, so an old cabin value cannot remain
            // visible while this publisher is online without a current vehicle snapshot.
            payload.put("cabin_temp", JSONObject.NULL);
            payload.put("inside_temp", JSONObject.NULL);

            // Read BYD data from cached snapshot (refreshed by BydDataCollector's 5s polling timer)
            BydDataCollector collector = BydDataCollector.getInstance();
            // Charging-derived state and its raw fields must come from one detector-stable
            // publication. A terminal edge between independent reads could otherwise pair a stopped
            // state with an older positive power/gun sample. When stability cannot be proved, retain
            // the raw fallback for unrelated telemetry but leave chargingState unavailable.
            VehicleDataMonitor.ChargingSnapshot chargingSnapshot =
                    vehicleDataMonitor.getChargingSnapshot();
            BydVehicleData vd = chargingSnapshot != null
                    ? chargingSnapshot.getVehicleData()
                    : collector.isInitialized() ? collector.getData() : null;
            ChargingStateData chargingState = chargingSnapshot != null
                    ? chargingSnapshot.getChargingState() : null;
            SocHistoryDatabase chargingDb = SocHistoryDatabase.getInstance();

            // utc
            payload.put("utc", now / 1000);

            // soc — vd.socPercent now carries decimal precision: BydDataCollector
            // registers a TYPED statistic listener whose onElecPercentageChanged(double)
            // feeds the true sub-integer SoC (the generic Proxy path never delivered it,
            // so SoC used to advance only on the coarse integer poll). Round to 1 decimal
            // for a stable published value. getBatterySoc() is only a fallback for the
            // AccSentry-process path where the collector snapshot may be absent.
            double soc = -1;
            if (com.overdrive.app.byd.DiLink5Platform.isActive()) {
                try {
                    double carSvcSoc = com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.socPercentValue();
                    if (!Double.isNaN(carSvcSoc) && carSvcSoc >= 0 && carSvcSoc <= 100) {
                        soc = carSvcSoc;
                    }
                } catch (Throwable ignored) {}
            }
            if (soc < 0 && vd != null && !Double.isNaN(vd.socPercent)) {
                soc = vd.socPercent;
            } else if (soc < 0) {
                BatterySocData socData = vehicleDataMonitor.getBatterySoc();
                if (socData != null) soc = socData.socPercent;
            }
            if (soc >= 0) payload.put("soc", Math.round(soc * 10.0) / 10.0);
            if (vd != null && vd.socTargetPercent >= BydDataCollector.SOC_TARGET_MIN
                    && vd.socTargetPercent <= BydDataCollector.SOC_TARGET_MAX) {
                payload.put("target_soc", vd.socTargetPercent);
            }

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
            // A CV taper IS charging: the BMS reports FINISHED while current still flows, so a bare
            // status test published is_charging=0 (and charge_power=0) for the whole tail.
            boolean isCharging = chargingState != null
                    && (chargingState.status == ChargingStateData.ChargingStatus.CHARGING
                        || chargingState.isTaperCharging);
            if (!isCharging && vd != null) {
                // AC_DC (4) is charging-capable and was missing here, so a combo-gun session that fell
                // through to this fallback reported not-charging. 5 is V2L (pack DISCHARGING) and stays
                // excluded. Same set the monitor's own gate uses.
                boolean gunConnected = vd.chargingGunState == 2
                        || vd.chargingGunState == 3
                        || vd.chargingGunState == 4;
                // Test for FLOW via the resolver, not for a non-zero raw value. Those raw fields may
                // hold a cumulative kWh counter on some firmware, and a counter stays non-zero after
                // the charge ends — so a bare "> 0.15" test kept reporting is_charging=1 on a
                // finished-but-plugged car for the rest of the session.
                boolean powerFlowing = chargingState != null
                        && !chargingState.isEstimated
                        && Double.isFinite(chargingState.chargingPowerKW)
                        && chargingState.chargingPowerKW > 0.15
                        && chargingState.chargingPowerKW <= 500.0;
                if (gunConnected && powerFlowing) isCharging = true;
            }
            payload.put("is_charging", isCharging ? 1 : 0);

            // is_dcfc — use the same guarded verdict as session pricing/cards. A raw gun==3
            // alone is not sufficient because some trims have reported it during ordinary AC.
            boolean v2l = false;
            if (vd != null && vd.chargingGunState != BydVehicleData.UNAVAILABLE) {
                // V2L is gun state 5 (VTOL), NOT 4. Per BYDAutoChargingDevice:
                // 2=AC, 3=DC, 4=AC_DC (a real combined charging gun), 5=VTOL. The
                // old `== 4` mislabelled genuine AC_DC charging as V2L — forcing
                // is_charging=0 (and, once charge_power gated on v2l, 0 kW) during a
                // real charge. Every other site (BydDataCollector isVtol, ChargingDetector
                // gunPlausible) correctly treats 5 as V2L and 4 as charging.
                if (vd.chargingGunState == 5) { payload.put("is_charging", 0); v2l = true; } // V2L (VTOL)
            }
            int dcGunState = vd != null
                    ? vd.chargingGunState : BydVehicleData.UNAVAILABLE;
            double dcEvidenceKw = isCharging && chargingState != null
                    && !chargingState.isEstimated
                    && Double.isFinite(chargingState.chargingPowerKW)
                    ? chargingState.chargingPowerKW : 0;
            int openSessionVerdict =
                    com.overdrive.app.monitor.ChargingTypeClassifier.UNKNOWN;
            if (isCharging) {
                try {
                    openSessionVerdict =
                            chargingDb.getOpenChargingSessionTypeVerdict();
                } catch (Throwable ignored) {}
            }
            int dcVerdict = com.overdrive.app.monitor.ChargingTypeClassifier.classifyLive(
                    dcGunState, dcEvidenceKw, openSessionVerdict);
            Integer dcFastFlag =
                    com.overdrive.app.monitor.ChargingTypeClassifier.toBinaryFlag(dcVerdict);
            if (dcFastFlag != null) {
                payload.put("is_dcfc", dcFastFlag.intValue());
            } else {
                // Home Assistant publishes this field on a retained per-key topic. Omitting an
                // unknown verdict would leave the previous session's AC/DC value retained
                // indefinitely. An explicit null clears that topic and keeps aggregate JSON honest.
                payload.put("is_dcfc", JSONObject.NULL);
            }

            // charge_power — resolved charging power into the pack (kW), from the same
            // ChargingStateData publication used by the UI, ABRP, history, and notifications.
            // Raw getter fields never enter MQTT directly.
            double chargeKw = 0;
            if (isCharging && !v2l) {
                // Prefer the RESOLVED figure over any raw getter. Raw accessors are stored
                // unscaled and their unit is decided at runtime (a value may be a cumulative kWh
                // counter rather than a rate), so only the resolver's output is guaranteed to be
                // kW. Publishing a raw getter here would send a counter reading to Home Assistant
                // as instantaneous power.
                //
                // !isEstimated keeps the nominal placeholder (3.3/7.0 kW) and the inferred
                // engine-power figure out of a feed that charts this as measured.
                //
                // Bound is the SDK's rate domain (500), not 300: a 350 kW DC session is real and
                // the old cap silently published 0 for it.
                if (chargingState != null
                        && !chargingState.isEstimated
                        && Double.isFinite(chargingState.chargingPowerKW)
                        && chargingState.chargingPowerKW > 0.1
                        && chargingState.chargingPowerKW <= 500) {
                    chargeKw = chargingState.chargingPowerKW;
                }
            }
            payload.put("charge_power", chargeKw);

            // Per-session energy is database-owned and must not disappear merely because the raw
            // vehicle snapshot is momentarily unavailable. Publish the resolved value and its
            // quality as one unit. The framework counter is only an explicitly incomplete fallback
            // while physical charging is confirmed and no database-owned baseline exists.
            SocHistoryDatabase.OpenChargingSessionEnergy sessionEnergy = null;
            long openSessionStart = -1L;
            try {
                openSessionStart =
                        chargingDb.getOpenChargingSessionStart();
                if (openSessionStart > 0) {
                    sessionEnergy =
                            chargingDb.getOpenChargingSessionEnergy();
                }
            } catch (Throwable ignored) {}
            if (sessionEnergy != null && sessionEnergy.isUsable()) {
                payload.put("charging_capacity_kwh",
                        Math.round(sessionEnergy.energyKwh * 1000.0) / 1000.0);
                payload.put("charging_capacity_incomplete",
                        sessionEnergy.incomplete ? 1 : 0);
                payload.put("charging_capacity_estimated",
                        sessionEnergy.estimated ? 1 : 0);
                payload.put("charging_capacity_source",
                        sessionEnergy.source);
            } else if (openSessionStart <= 0
                    && isCharging
                    && !v2l
                    && vd != null
                    && Double.isFinite(vd.chargingCapacityKwh)
                    && vd.chargingCapacityKwh >= 0
                    && vd.chargingCapacityKwh
                    <= com.overdrive.app.charging.ChargeCounterAccumulator
                            .COUNTER_FULL_SCALE_KWH) {
                payload.put("charging_capacity_kwh",
                        vd.chargingCapacityKwh);
                // No database-owned baseline exists, so this raw framework value cannot prove how
                // much belongs to the current physical session.
                payload.put("charging_capacity_incomplete", 1);
                payload.put("charging_capacity_estimated", 1);
                payload.put("charging_capacity_source",
                        "raw_counter_unowned");
            } else {
                payload.put("charging_capacity_incomplete",
                        sessionEnergy != null
                                && sessionEnergy.incomplete ? 1 : 0);
                payload.put("charging_capacity_estimated",
                        sessionEnergy != null
                                && sessionEnergy.estimated ? 1 : 0);
                payload.put("charging_capacity_source",
                        sessionEnergy != null
                                ? sessionEnergy.source
                                : openSessionStart > 0
                                        ? "unavailable"
                                        : "none");
            }

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

            // Only publish generic charge-limit state once the matching
            // charge-stop backend has been write/read-back verified. These
            // state topics drive HA's controllable entities and must never
            // inherit a PHEV SOC-hold or a raw unsupported register value.
            if (Boolean.TRUE.equals(collector.isChargeCapSupported())) {
                int capPercent = collector.getChargeCapPercent();
                int capEnabled = collector.getChargeCapEnabled();
                if (isVerifiedChargeCapState(Boolean.TRUE, capPercent, capEnabled)) {
                    payload.put("charge_cap_percent", capPercent);
                    payload.put("charge_cap_enabled", capEnabled);
                }
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
                long observedCabinExpiryAtMs = cabinExpiryAtMs(vd);
                boolean cabinTemperatureFresh = observedCabinExpiryAtMs > now;
                if (cabinTemperatureFresh) cabinExpiresAtMs = observedCabinExpiryAtMs;

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
                payload.put("cabin_temp",
                        cabinTemperatureFresh && !Double.isNaN(vd.insideTempCelsius)
                                ? vd.insideTempCelsius : JSONObject.NULL);

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
                payload.put("inside_temp",
                        cabinTemperatureFresh && !Double.isNaN(vd.insideTempC)
                                ? vd.insideTempC : JSONObject.NULL);

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
                // Steering-wheel heater readback (raw setting-HAL 2=on / 1=off) normalized to
                // 1/0 for the steering_heat control switch's state topic. Publish only a
                // confident read — getSteeringWheelHeatingState already rejects 0/65535 rails,
                // so anything else means "never answered" and the topic stays absent (the
                // switch's optimistic command echo is then its only feeder), rather than a
                // wrong retained "off" reverting a successful cloud toggle.
                if (vd.steeringWheelHeat == 2 || vd.steeringWheelHeat == 1)
                    payload.put("steering_wheel_heat", vd.steeringWheelHeat == 2 ? 1 : 0);

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
        lastCachedCabinExpiresAtMs = cabinExpiresAtMs;

        return new CollectedTelemetry(payload, cabinExpiresAtMs);
    }

    /**
     * Generic charge-cap values become telemetry only after the charge-stop backend has been
     * verified and both readable values are complete. This prevents raw unsupported/unprobed
     * register values from becoming Home Assistant state.
     */
    static boolean isVerifiedChargeCapState(Boolean supported, int percent, int enabled) {
        return Boolean.TRUE.equals(supported)
                && percent >= 50 && percent <= 100
                && (enabled == 0 || enabled == 1);
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
