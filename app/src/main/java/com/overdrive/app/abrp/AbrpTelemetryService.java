package com.overdrive.app.abrp;

import android.content.Context;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.BatteryThermalData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.ChargingTypeClassifier;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.monitor.SocHistoryDatabase;
import com.overdrive.app.monitor.VehicleDataMonitor;
import com.overdrive.app.mqtt.TelemetryDiffer;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ABRP Telemetry Service - collects vehicle telemetry and uploads to ABRP API.
 *
 * Collects all ABRP Gold Standard fields from BYD vehicle monitors and reflection-based
 * device access, assembles JSON payloads, and POSTs them to the ABRP API at adaptive intervals.
 *
 * Runs as a scheduled thread inside CameraDaemon.
 */
public class AbrpTelemetryService {

    private static final String TAG = "AbrpTelemetryService";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String ABRP_API_URL = "https://api.iternio.com/1/tlm/send";
    
    // ABRP API key — hardcoded "Open Source" key for third-party/DIY apps.
    // This identifies the app to ABRP, NOT the user.
    // The user provides their own "token" via the UI (from ABRP "Link Generic").
    // Replace with your own key if you register at contact@iternio.com.
    private static final String PUBLIC_API_KEY = "42407443-7db2-4a3d-8950-0029ecb42a67";

    // Backoff
    private static final int BACKOFF_BASE_SECONDS = 5;
    private static final int BACKOFF_CAP_SECONDS = 300;
    private static final long ENGINE_POWER_FRESHNESS_MS = 15_000L;

    // Configuration and estimator
    private final AbrpConfig config;
    private final SohEstimator sohEstimator;

    // Change detection (report-by-exception) + "only while ABRP app is in use" gate
    private final TelemetryDiffer differ = new TelemetryDiffer();
    private AbrpAppPresence appPresence;

    // Data source references
    private final VehicleDataMonitor vehicleDataMonitor;
    private final GpsMonitor gpsMonitor;
    private final GearMonitor gearMonitor;

    // Reflection-accessed devices
    private Object engineDevice;        // BYDAutoEngineDevice
    private Method getEnginePowerMethod;
    private Object chargingDevice;      // BYDAutoChargingDevice
    private Method getChargingGunStateMethod;
    private Object instrumentDevice;    // BYDAutoInstrumentDevice
    private Method getOutCarTemperatureMethod;
    private Object statisticDevice;     // BYDAutoStatisticDevice
    private Method getTotalMileageValueMethod;
    private Object speedDevice;         // BYDAutoSpeedDevice
    private Method getCurrentSpeedMethod;
    private Object acDevice;            // BYDAutoAcDevice
    private Method getTempratureMethod;
    private Object gearboxDevice;       // BYDAutoGearboxDevice
    private Method getGearboxAutoModeTypeMethod;

    // HTTP client (proxy configured lazily on first upload)
    private OkHttpClient httpClient;
    private volatile boolean proxyChecked = false;
    private volatile long lastProxyCheckTime = 0;
    private volatile Boolean lastProxyAvailable = null;

    // Scheduler
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;

    // State
    private volatile boolean running;
    private int consecutiveFailures;
    private long lastUploadTime;
    private long totalUploads;
    private long failedUploads;
    private JSONObject lastTelemetrySnapshot;
    // One "Upload OK" per second drowned every other tag in the exported log
    // (10,153 of 15,383 lines on 2026-10-16). Successes now roll up; failures
    // stay at full volume because they are the diagnostic signal.
    private static final long OK_SUMMARY_INTERVAL_MS = 300_000;
    private long okSinceSummary;
    private long nextOkSummaryAt;

    // Weather temperature now comes from the shared WeatherTemperature helper
    // (one cache + network path for both ABRP ext_temp and the automation
    // temperature fallback). See getWeatherTemperature().

    public AbrpTelemetryService(AbrpConfig config, SohEstimator sohEstimator) {
        this.config = config;
        this.sohEstimator = sohEstimator;
        this.vehicleDataMonitor = VehicleDataMonitor.getInstance();
        this.gpsMonitor = GpsMonitor.getInstance();
        this.gearMonitor = GearMonitor.getInstance();
        this.running = false;
        this.consecutiveFailures = 0;
        this.lastUploadTime = 0;
        this.totalUploads = 0;
        this.failedUploads = 0;

        // Route via ProxyHelper.chainProxySelector() so ABRP uses sing-box (:8119)
        // when its upstream is healthy and falls back to DIRECT within the same call
        // if the proxy leg times out or dies.
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .proxySelector(com.overdrive.app.mqtt.ProxyHelper.chainProxySelector())
                .build();
    }
    
    /**
     * Get HTTP client backed by {@link com.overdrive.app.mqtt.ProxyHelper#chainProxySelector()}.
     * Evaluates egress proxy health per request with automatic DIRECT fallback while
     * preserving OkHttp connection pooling.
     */
    private OkHttpClient getProxiedClient() {
        return httpClient;
    }

    /**
     * Initialize ABRP telemetry service.
     * Device access is now handled by BydDataCollector — no per-device reflection needed here.
     */
    public void init(Context context) {
        this.appPresence = new AbrpAppPresence(config);
        logger.info("ABRP telemetry service initialized (using BydDataCollector for vehicle data)");
    }

    // ==================== TELEMETRY COLLECTION ====================

    static boolean isChargingForTelemetry(ChargingStateData state, BydVehicleData vd) {
        boolean charging = state != null
                && (state.status == ChargingStateData.ChargingStatus.CHARGING
                    || state.isTaperCharging);
        if (!charging && state != null
                && (state.status == ChargingStateData.ChargingStatus.IDLE
                    || state.status == ChargingStateData.ChargingStatus.UNKNOWN)
                && !state.isEstimated
                && Double.isFinite(state.chargingPowerKW)
                && state.chargingPowerKW > 0.15
                && state.chargingPowerKW <= 500.0) {
            // PHEV fallback: its BMS can remain IDLE while the independently resolved rate moves.
            charging = true;
        }
        if (vd != null && (vd.vtolCharging
                || vd.chargingGunState == 1 || vd.chargingGunState == 5)) {
            return false;
        }
        return charging;
    }

    static boolean canPublishEnginePower(BydVehicleData vd, long nowMs,
                                         boolean accOn, boolean charging) {
        if (vd == null || !Double.isFinite(vd.enginePowerKw)
                || Math.abs(vd.enginePowerKw) <= 0.1
                || Math.abs(vd.enginePowerKw) > 300.0
                || vd.enginePowerAtMs <= 0L) {
            return false;
        }
        long ageMs = nowMs - vd.enginePowerAtMs;
        if (ageMs < 0L || ageMs > ENGINE_POWER_FRESHNESS_MS) return false;

        if (vd.enginePowerKw < 0.0) {
            // Negative means energy entering the pack. Publishing it while is_charging=0 is the
            // frozen terminal -3 kW failure; driving regen is omitted rather than creating that
            // contradictory ABRP pair.
            return charging;
        }
        boolean chargingGunConnected = vd.chargingGunState == 2
                || vd.chargingGunState == 3 || vd.chargingGunState == 4;
        return accOn && !charging && !chargingGunConnected;
    }

    static double selectTelemetryPower(BydVehicleData vd, ChargingStateData chargingState,
                                       long nowMs, boolean accOn, boolean charging) {
        if (charging && chargingState != null
                && !chargingState.isEstimated
                && Double.isFinite(chargingState.chargingPowerKW)
                && chargingState.chargingPowerKW > 0.15
                && chargingState.chargingPowerKW <= 500.0) {
            return -chargingState.chargingPowerKW;
        }
        if (canPublishEnginePower(vd, nowMs, accOn, charging)) {
            return vd.enginePowerKw;
        }
        return 0.0;
    }

    /**
     * Collect telemetry from all data sources and assemble ABRP Gold Standard payload.
     * Missing fields are omitted (ABRP accepts partial payloads).
     */
    public JSONObject collectTelemetry() {
        JSONObject payload = new JSONObject();

        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            // The derived state and raw fields must come from one detector-stable publication. A
            // terminal edge between independent reads could otherwise pair stopped state with stale
            // negative power (or active state with terminal raw fields).
            VehicleDataMonitor.ChargingSnapshot chargingSnapshot =
                    vehicleDataMonitor.getChargingSnapshot();
            BydVehicleData vd = chargingSnapshot != null
                    ? chargingSnapshot.getVehicleData() : null;
            ChargingStateData chargingState = chargingSnapshot != null
                    ? chargingSnapshot.getChargingState() : null;
            boolean isCharging = isChargingForTelemetry(chargingState, vd);
            boolean accOn = collector.isAccOn();
            long telemetryNowMs = System.currentTimeMillis();

            // utc
            payload.put("utc", System.currentTimeMillis() / 1000);

            // soc
            double soc = -1;
            if (vd != null && !Double.isNaN(vd.socPercent)) {
                soc = vd.socPercent;
            } else {
                BatterySocData socData = vehicleDataMonitor.getBatterySoc();
                if (socData != null) soc = socData.socPercent;
            }
            if (soc >= 0) payload.put("soc", soc);

            // power — ABRP sign convention: positive = discharge, negative = charge. Selection and
            // is_charging share the exact same state observation so a concurrent terminal edge cannot
            // produce power=-3 with is_charging=0.
            try {
                payload.put("power", selectTelemetryPower(
                        vd, chargingState, telemetryNowMs, accOn, isCharging));
            } catch (Exception e) {
                payload.put("power", 0);
            }

            // speed — from collector, fallback to GPS
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

            payload.put("is_charging", isCharging ? 1 : 0);

            // is_dcfc — same guarded verdict used by session pricing and MQTT.
            int dcGunState = vd != null
                    ? vd.chargingGunState : BydVehicleData.UNAVAILABLE;
            double dcEvidenceKw = isCharging && chargingState != null
                    && !chargingState.isEstimated
                    && Double.isFinite(chargingState.chargingPowerKW)
                    ? chargingState.chargingPowerKW : 0;
            int openSessionVerdict = ChargingTypeClassifier.UNKNOWN;
            if (isCharging) {
                try {
                    openSessionVerdict = SocHistoryDatabase.getInstance()
                            .getOpenChargingSessionTypeVerdict();
                } catch (Throwable ignored) {}
            }
            int dcVerdict = ChargingTypeClassifier.classifyLive(
                    dcGunState, dcEvidenceKw, openSessionVerdict);
            Integer dcFastFlag = ChargingTypeClassifier.toBinaryFlag(dcVerdict);
            if (dcFastFlag != null) {
                payload.put("is_dcfc", dcFastFlag.intValue());
            }

            // is_parked — prefer GearMonitor's freshness-checked 5 Hz value.
            // The collector snapshot is updated far less often and deliberately
            // stops polling gearbox data with ACC off, so it can retain the last
            // driving gear indefinitely. That stale D made ABRP show Drive while
            // the dashboard (which uses GearMonitor) correctly showed Park.
            Boolean isParked = resolveCurrentParkedState(vd, accOn);
            if (isParked != null) {
                payload.put("is_parked", isParked ? 1 : 0);
            }

            // elevation, heading
            if (gpsMonitor.hasLocation()) {
                double alt = gpsMonitor.getAltitude();
                if (alt > 0) payload.put("elevation", alt);
                payload.put("heading", gpsMonitor.getHeading());
            }

            // ext_temp — from collector, fallback to weather API
            boolean tempSet = false;
            if (vd != null && !Double.isNaN(vd.outsideTempC)) {
                payload.put("ext_temp", vd.outsideTempC);
                tempSet = true;
            }
            if (!tempSet && gpsMonitor.hasLocation()) {
                double weatherTemp = getWeatherTemperature(gpsMonitor.getLatitude(), gpsMonitor.getLongitude());
                if (!Double.isNaN(weatherTemp)) payload.put("ext_temp", weatherTemp);
            }

            // odometer — from collector
            if (vd != null && vd.totalMileageKm != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE) {
                int raw = vd.totalMileageKm;
                payload.put("odometer", raw > 1_000_000 ? raw / 10.0 : (double) raw);
            }

            // Canonical OEM-first value so ABRP agrees with every app surface.
            double canonicalSoh = SohEstimator.getDisplaySohOrOem(sohEstimator);
            if (canonicalSoh > 0) payload.put("soh", canonicalSoh);

            // capacity payload uses the synthesized helper (UI-friendly), but
            // SOH is fed from RAW vd.remainKwh only — getBatteryRemainPowerKwh
            // synthesizes from currentSoh on PHEV / bad-BMS paths, so feeding
            // it to updateFromEnergy would lock the formula at its initial seed.
            double remainingKwh = vehicleDataMonitor.getBatteryRemainPowerKwh();
            if (remainingKwh > 0 && soc > 0) {
                payload.put("capacity", remainingKwh / (soc / 100.0));
            }
            // Feed the live SOH formula ONLY on BEV. On PHEV the raw getter is
            // unreliable (half/stale/frame-ambiguous), so SOH is driven solely by
            // the independent capacity-Ah + calibration anchors (see SocHistoryDatabase).
            // Feeding it here too would reintroduce the noisy/railed PHEV SOH.
            double rawRemainKwh = (vd != null && !Double.isNaN(vd.remainKwh)) ? vd.remainKwh : Double.NaN;
            double highCellV = (vd != null && !Double.isNaN(vd.highCellVoltage))
                ? vd.highCellVoltage : Double.NaN;
            boolean isPhevForSoh = false;
            try { isPhevForSoh = vehicleDataMonitor.isPhev(); } catch (Throwable ignored) {}
            if (sohEstimator != null && !isPhevForSoh && rawRemainKwh > 0 && soc > 0
                    && sohEstimator.getNominalCapacityKwh() > 0) {
                double impliedCap = rawRemainKwh / (soc / 100.0);
                double ratio = impliedCap / sohEstimator.getNominalCapacityKwh();
                if (ratio >= 0.5 && ratio <= 1.12) {
                    sohEstimator.updateFromEnergy(rawRemainKwh, soc, highCellV, false);
                }
            }

            // batt_temp — from collector (real cell temps), fallback to thermal monitor
            if (vd != null && !Double.isNaN(vd.getBestBatteryTemp())) {
                double battTemp = vd.getBestBatteryTemp();
                if (battTemp >= -40 && battTemp <= 80) payload.put("batt_temp", battTemp);
            } else {
                BatteryThermalData thermalData = vehicleDataMonitor.getBatteryThermal();
                if (thermalData != null && thermalData.hasData()) {
                    double battTemp = thermalData.getBestTemperature();
                    if (!Double.isNaN(battTemp) && battTemp >= -40 && battTemp <= 80) payload.put("batt_temp", battTemp);
                }
            }

            // est_battery_range — EV range in km (ABRP standard field)
            if (vd != null && vd.elecRangeKm != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE && vd.elecRangeKm > 0) {
                payload.put("est_battery_range", vd.elecRangeKm);
            }

            // Cabin temperature
            if (vd != null && vd.hasFreshCabinTemperature()
                    && !Double.isNaN(vd.insideTempCelsius)) {
                payload.put("car_temp", vd.insideTempCelsius);
            }

        } catch (Exception e) {
            logger.error("Error collecting telemetry: " + e.getMessage());
        }

        lastTelemetrySnapshot = payload;
        return payload;
    }

    /**
     * Resolve ABRP's parked flag without allowing an old collector gear to
     * override the live gear monitor. Unknown stays unknown (field omitted)
     * rather than being manufactured into Drive.
     */
    private Boolean resolveCurrentParkedState(
            BydVehicleData vd, boolean collectorAccOn) {
        int freshGear = BydVehicleData.UNAVAILABLE;
        try {
            freshGear = gearMonitor.getCurrentGearIfFresh();
        } catch (Throwable ignored) {
        }

        boolean accOffConfirmed = !collectorAccOn;
        try {
            if (AccMonitor.isAccStateAuthoritative()) {
                boolean accOn = AccMonitor.isAccOn();
                boolean accFreshEnough =
                        !com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                                || AccMonitor.isAccStateFreshForSafety();
                accOffConfirmed = !accOn && accFreshEnough;
            }
        } catch (Throwable ignored) {
        }

        int snapshotGear = vd != null
                ? vd.gearMode : BydVehicleData.UNAVAILABLE;
        return resolveParkedState(
                freshGear, snapshotGear, accOffConfirmed);
    }

    /**
     * Source precedence:
     *  1. fresh GearMonitor observation;
     *  2. confirmed powered-off state;
     *  3. slower collector snapshot;
     *  4. unknown.
     */
    static Boolean resolveParkedState(
            int freshGear, int snapshotGear, boolean accOffConfirmed) {
        if (GearMonitor.isValidGearMode(freshGear)) {
            return freshGear == GearMonitor.GEAR_P;
        }
        if (accOffConfirmed) {
            return Boolean.TRUE;
        }
        if (GearMonitor.isValidGearMode(snapshotGear)) {
            return snapshotGear == GearMonitor.GEAR_P;
        }
        return null;
    }

    // ==================== UPLOAD LOGIC ====================

    /**
     * Upload telemetry payload to ABRP API.
     * POST as form-urlencoded with token and tlm fields.
     *
     * @return true if upload succeeded, false otherwise
     */
    public boolean uploadTelemetry(JSONObject payload) {
        String token = config.getUserToken();
        if (token == null || token.isEmpty()) {
            logger.warn("No user token configured, skipping upload");
            return false;
        }

        try {
            // ABRP API: token and api_key as query params, tlm as POST form body
            // api_key = hardcoded public key (identifies the app)
            // token = user's personal token (identifies the car)
            String apiKey = config.getApiKey();
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = PUBLIC_API_KEY;
            }
            
            okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(ABRP_API_URL).newBuilder()
                    .addQueryParameter("token", token)
                    .addQueryParameter("api_key", apiKey)
                    .build();

            RequestBody formBody = new FormBody.Builder()
                    .add("tlm", payload.toString())
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .post(formBody)
                    .build();

            try (Response response = getProxiedClient().newCall(request).execute()) {
                totalUploads++;
                String responseBody = response.body() != null ? response.body().string() : "";

                if (response.isSuccessful()) {
                    boolean recovered = consecutiveFailures > 0;
                    consecutiveFailures = 0;
                    lastUploadTime = System.currentTimeMillis();
                    okSinceSummary++;
                    // Always log the first success and the one that ends a failure run;
                    // otherwise roll the rest up every OK_SUMMARY_INTERVAL_MS.
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (nextOkSummaryAt == 0 || recovered) {
                        logger.info("Upload OK (HTTP " + response.code() + "): " + responseBody);
                        okSinceSummary = 0;
                        nextOkSummaryAt = now + OK_SUMMARY_INTERVAL_MS;
                    } else if (now >= nextOkSummaryAt) {
                        logger.info("Upload OK x" + okSinceSummary + " (HTTP " + response.code()
                                + ") in the last " + (OK_SUMMARY_INTERVAL_MS / 1000) + "s");
                        okSinceSummary = 0;
                        nextOkSummaryAt = now + OK_SUMMARY_INTERVAL_MS;
                    }
                    return true;
                } else {
                    failedUploads++;
                    consecutiveFailures++;
                    // Invalidate proxy cache on HTTP error — may need to switch proxy mode
                    proxyChecked = false;
                    com.overdrive.app.mqtt.ProxyHelper.invalidateCache();
                    logger.warn("Upload failed: HTTP " + response.code() + " - " + responseBody);
                    return false;
                }
            }
        } catch (Exception e) {
            totalUploads++;
            failedUploads++;
            consecutiveFailures++;
            // Invalidate proxy cache on connection error — proxy state may have changed
            // (e.g., singbox started after we cached a direct connection)
            proxyChecked = false;
            com.overdrive.app.mqtt.ProxyHelper.invalidateCache();
            logger.error("Upload error: " + e.getMessage());
            return false;
        }
    }

    // ==================== SCHEDULER ====================

    /**
     * Start the telemetry upload scheduler with adaptive interval.
     */
    public void start() {
        if (running) {
            logger.warn("Already running");
            return;
        }

        if (!config.isConfigured()) {
            logger.warn("Cannot start: no user token configured");
            return;
        }

        if (!config.isEnabled()) {
            logger.warn("Cannot start: ABRP telemetry is disabled");
            return;
        }

        logger.info("Starting ABRP telemetry service...");
        running = true;
        consecutiveFailures = 0;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AbrpTelemetry");
            t.setDaemon(true);
            return t;
        });

        scheduleNext(0);
        logger.info("ABRP telemetry service started");
    }

    /**
     * Stop the telemetry upload scheduler gracefully.
     */
    public void stop() {
        if (!running) {
            return;
        }

        logger.info("Stopping ABRP telemetry service...");
        running = false;

        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }

        logger.info("ABRP telemetry service stopped");
    }

    /**
     * Schedule the next telemetry cycle after the given delay.
     */
    private void scheduleNext(long delaySeconds) {
        if (!running || scheduler == null || scheduler.isShutdown()) {
            return;
        }

        scheduledTask = scheduler.schedule(this::runCycle, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * Execute one telemetry cycle: collect → upload → schedule next.
     */
    private void runCycle() {
        if (!running) {
            return;
        }

        try {
            long now = System.currentTimeMillis();

            // Outer gate (optional): only stream while the ABRP app is actually in use.
            if (config.isGateOnApp() && appPresence != null) {
                if (!appPresence.isActive()) {
                    logger.debug("ABRP app not active (" + appPresence.describe() + ") — skipping upload");
                    scheduleNext(getMinInterval());
                    return;
                }
            }

            JSONObject payload = collectTelemetry();

            // Inner gate: change detection within the min/max interval window. The
            // big saving is when parked/idle — identical payloads no longer upload.
            long minMs = getMinInterval() * 1000L;
            long maxMs = Math.max(getMinInterval(), getMaxInterval()) * 1000L;
            Set<String> changed = differ.changedKeys(payload);
            boolean shouldSend = differ.shouldPublish(!changed.isEmpty(), config.isChangeOnly(), now, minMs, maxMs);

            boolean success = true;
            if (shouldSend) {
                success = uploadTelemetry(payload);
                if (success) differ.markAllSent(payload, now);
            } else {
                logger.debug("ABRP: no change within window — skipping upload");
            }

            long nextDelay;
            if (!success && consecutiveFailures > 0) {
                nextDelay = calculateBackoff(consecutiveFailures);
                logger.debug("Backoff: next upload in " + nextDelay + "s (failures: " + consecutiveFailures + ")");
            } else {
                nextDelay = getMinInterval();
            }

            scheduleNext(nextDelay);

        } catch (Exception e) {
            logger.error("Telemetry cycle error: " + e.getMessage());
            // Schedule retry even on unexpected errors
            scheduleNext(calculateBackoff(Math.max(1, consecutiveFailures)));
        }
    }

    /** Min-interval floor (seconds), clamped to >= 1. */
    private int getMinInterval() {
        int v = config.getMinIntervalSeconds();
        return v < 1 ? 1 : v;
    }

    /** Max-interval heartbeat (seconds), never below the floor. */
    private int getMaxInterval() {
        return Math.max(getMinInterval(), config.getMaxIntervalSeconds());
    }

    /**
     * Calculate exponential backoff delay: min(5 * 2^(N-1), 300) seconds.
     */
    static long calculateBackoff(int consecutiveFailures) {
        if (consecutiveFailures <= 0) {
            return BACKOFF_BASE_SECONDS;
        }
        long delay = BACKOFF_BASE_SECONDS * (1L << (consecutiveFailures - 1));
        return Math.min(delay, BACKOFF_CAP_SECONDS);
    }

    // ==================== STATUS ====================

    /**
     * Get service status as JSON for IPC responses.
     */
    public JSONObject getStatus() {
        JSONObject status = new JSONObject();
        try {
            status.put("running", running);
            status.put("totalUploads", totalUploads);
            status.put("failedUploads", failedUploads);
            status.put("lastUploadTime", lastUploadTime);
            status.put("consecutiveFailures", consecutiveFailures);
            status.put("currentInterval", getMinInterval());
            status.put("changeOnly", config.isChangeOnly());
            status.put("minInterval", getMinInterval());
            status.put("maxInterval", getMaxInterval());
            status.put("appGate", config.isGateOnApp());
            if (config.isGateOnApp() && appPresence != null) {
                status.put("abrp_app_state", appPresence.describe());
                status.put("abrp_app_active", appPresence.isActive());
            }
            if (lastTelemetrySnapshot != null) {
                status.put("lastTelemetry", lastTelemetrySnapshot);
            }
        } catch (Exception e) {
            logger.error("Error building status: " + e.getMessage());
        }
        return status;
    }

    // ==================== WEATHER API ====================

    /**
     * Get current temperature from Open-Meteo API using GPS coordinates.
     * Free, no API key required. Results cached for 10 minutes.
     * https://open-meteo.com/en/docs
     * 
     * @return temperature in °C, or NaN if unavailable
     */
    private double getWeatherTemperature(double lat, double lon) {
        // Delegate to the shared WeatherTemperature helper so the automation
        // temperature fallback and ABRP ext_temp share ONE cache + network path.
        // This runs on ABRP's own upload thread (off the telemetry hot path), so a
        // synchronous fetch is fine here.
        return com.overdrive.app.weather.WeatherTemperature.fetchNow(lat, lon);
    }

    /**
     * Check if the service is currently running.
     */
    public boolean isRunning() {
        return running;
    }

    // ==================== HELPERS ====================

    /**
     * Create a PermissionBypassContext for BYD device access.
     * Follows the same pattern as AccSentryDaemon and CameraDaemon.
     */
    private Context createPermissiveContext(Context context) {
        try {
            return new PermissionBypassContext(context);
        } catch (Exception e) {
            logger.error("Failed to create PermissionBypassContext: " + e.getMessage());
            return null;
        }
    }

    /**
     * Context wrapper that bypasses BYD permission checks.
     * Required for accessing BYD hardware services without signature permissions.
     */
    private static class PermissionBypassContext extends android.content.ContextWrapper {
        public PermissionBypassContext(Context base) {
            super(base);
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {}

        @Override
        public void enforcePermission(String permission, int pid, int uid, String message) {}

        @Override
        public void enforceCallingPermission(String permission, String message) {}

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
    }
}
