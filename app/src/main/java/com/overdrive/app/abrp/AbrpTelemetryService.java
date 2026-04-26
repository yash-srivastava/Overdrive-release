package com.overdrive.app.abrp;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.BatteryThermalData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.monitor.VehicleDataMonitor;

import org.json.JSONObject;

import java.lang.reflect.Method;
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

    // Adaptive intervals
    private static final int DRIVING_INTERVAL_SECONDS = 5;
    private static final int PARKED_INTERVAL_SECONDS = 30;

    // Backoff
    private static final int BACKOFF_BASE_SECONDS = 5;
    private static final int BACKOFF_CAP_SECONDS = 300;

    // Configuration and estimator
    private final AbrpConfig config;
    private final SohEstimator sohEstimator;

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
    
    // Weather temperature cache (fetched from Open-Meteo API using GPS coords)
    private volatile double cachedWeatherTemp = Double.NaN;
    private volatile long lastWeatherFetchTime = 0;
    private static final long WEATHER_CACHE_MS = 10 * 60 * 1000; // 10 minutes

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

        // Default client without proxy — proxy configured lazily on first upload
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * Get HTTP client with sing-box proxy if available.
     * Probes port 8119 once on first call, caches result.
     * Called from background thread only (never main thread).
     */
    private OkHttpClient getProxiedClient() {
        // Re-check proxy availability periodically (proxy may go up/down with ACC state)
        long now = System.currentTimeMillis();
        if (proxyChecked && (now - lastProxyCheckTime) < 60_000) return httpClient;
        proxyChecked = true;
        lastProxyCheckTime = now;
        
        boolean proxyAvailable = false;
        try {
            java.net.Socket probe = new java.net.Socket();
            probe.connect(new java.net.InetSocketAddress("127.0.0.1", 8119), 200);
            probe.close();
            proxyAvailable = true;
        } catch (Exception e) {
            // Proxy not available
        }
        
        if (proxyAvailable) {
            httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress("127.0.0.1", 8119)))
                .build();
            logger.info("Using sing-box proxy for ABRP uploads");
        } else {
            httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
            logger.info("No proxy, using direct connection for ABRP");
        }
        return httpClient;
    }

    /**
     * Initialize ABRP telemetry service.
     * Device access is now handled by BydDataCollector — no per-device reflection needed here.
     */
    public void init(Context context) {
        logger.info("ABRP telemetry service initialized (using BydDataCollector for vehicle data)");
    }

    // ==================== TELEMETRY COLLECTION ====================

    /**
     * Collect telemetry from all data sources and assemble ABRP Gold Standard payload.
     * Missing fields are omitted (ABRP accepts partial payloads).
     */
    public JSONObject collectTelemetry() {
        JSONObject payload = new JSONObject();

        try {
            // Read BYD data from cached snapshot (refreshed by BydDataCollector's 5s polling timer)
            com.overdrive.app.byd.BydDataCollector collector = com.overdrive.app.byd.BydDataCollector.getInstance();
            com.overdrive.app.byd.BydVehicleData vd = collector.isInitialized() ? collector.getData() : null;

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

            // power — from collector's enginePowerKw, fallback to charging monitor
            try {
                boolean powerSet = false;
                if (vd != null && !Double.isNaN(vd.enginePowerKw) && Math.abs(vd.enginePowerKw) > 0.1 && Math.abs(vd.enginePowerKw) <= 300) {
                    payload.put("power", vd.enginePowerKw);
                    powerSet = true;
                }
                if (!powerSet) {
                    // For charging power, prefer externalChargingPowerKw (InstrumentDevice)
                    // which is the real charger-reported power. ChargingDevice values are unreliable.
                    // Threshold 0.15 kW filters phantom 0.1 kW readings when charger is unplugged.
                    double chargingPower = 0;
                    if (vd != null && !Double.isNaN(vd.externalChargingPowerKw) && vd.externalChargingPowerKw > 0.15) {
                        chargingPower = vd.externalChargingPowerKw;
                    } else if (vd != null && !Double.isNaN(vd.chargingPowerKw) && vd.chargingPowerKw > 0.15) {
                        chargingPower = vd.chargingPowerKw;
                    }
                    
                    // Check charging state
                    ChargingStateData chargingData = vehicleDataMonitor.getChargingState();
                    boolean isChg = (chargingData != null && chargingData.status == ChargingStateData.ChargingStatus.CHARGING)
                                    || chargingPower > 0.15;
                    
                    payload.put("power", isChg && chargingPower > 0.15 ? -chargingPower : 0);
                }
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

            // is_charging
            ChargingStateData chargingState = vehicleDataMonitor.getChargingState();
            boolean isCharging = chargingState != null && chargingState.status == ChargingStateData.ChargingStatus.CHARGING;
            payload.put("is_charging", isCharging ? 1 : 0);

            // is_dcfc — gun state from collector
            if (vd != null && vd.chargingGunState != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE) {
                payload.put("is_dcfc", vd.chargingGunState == 3 ? 1 : 0);
                if (vd.chargingGunState == 4) payload.put("is_charging", 0); // V2L
            }

            // is_parked — gear from collector
            boolean isParked = false;
            if (vd != null && vd.gearMode != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE) {
                isParked = vd.gearMode == GearMonitor.GEAR_P;
            } else {
                isParked = gearMonitor.getCurrentGear() == GearMonitor.GEAR_P;
            }
            payload.put("is_parked", isParked ? 1 : 0);

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

            // soh
            if (sohEstimator.hasEstimate()) {
                payload.put("soh", sohEstimator.getCurrentSoh());
            }

            // capacity + feed SohEstimator
            // Use getBatteryRemainPowerKwh() which handles PHEV stuck values
            double remainingKwh = vehicleDataMonitor.getBatteryRemainPowerKwh();
            if (remainingKwh > 0 && soc > 0) {
                payload.put("capacity", remainingKwh / (soc / 100.0));
                sohEstimator.updateFromInstantaneous(remainingKwh, soc);
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

        } catch (Exception e) {
            logger.error("Error collecting telemetry: " + e.getMessage());
        }

        lastTelemetrySnapshot = payload;
        return payload;
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
                    consecutiveFailures = 0;
                    lastUploadTime = System.currentTimeMillis();
                    logger.info("Upload OK (HTTP " + response.code() + "): " + responseBody);
                    return true;
                } else {
                    failedUploads++;
                    consecutiveFailures++;
                    // Invalidate proxy cache on HTTP error — may need to switch proxy mode
                    proxyChecked = false;
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
            JSONObject payload = collectTelemetry();
            boolean success = uploadTelemetry(payload);

            long nextDelay;
            if (!success && consecutiveFailures > 0) {
                nextDelay = calculateBackoff(consecutiveFailures);
                logger.debug("Backoff: next upload in " + nextDelay + "s (failures: " + consecutiveFailures + ")");
            } else {
                nextDelay = getAdaptiveInterval();
            }

            scheduleNext(nextDelay);

        } catch (Exception e) {
            logger.error("Telemetry cycle error: " + e.getMessage());
            // Schedule retry even on unexpected errors
            scheduleNext(calculateBackoff(Math.max(1, consecutiveFailures)));
        }
    }

    /**
     * Get adaptive upload interval based on vehicle state.
     * 5s when driving (not parked AND not charging), 30s when parked or charging.
     */
    int getAdaptiveInterval() {
        boolean isParked = (gearMonitor.getCurrentGear() == GearMonitor.GEAR_P);
        boolean isCharging = false;

        ChargingStateData chargingState = vehicleDataMonitor.getChargingState();
        if (chargingState != null) {
            isCharging = (chargingState.status == ChargingStateData.ChargingStatus.CHARGING);
        }

        if (!isParked && !isCharging) {
            return DRIVING_INTERVAL_SECONDS;
        }
        return PARKED_INTERVAL_SECONDS;
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
            status.put("currentInterval", getAdaptiveInterval());
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
        long now = System.currentTimeMillis();
        
        // Return cached value if fresh
        if (!Double.isNaN(cachedWeatherTemp) && (now - lastWeatherFetchTime) < WEATHER_CACHE_MS) {
            return cachedWeatherTemp;
        }
        
        try {
            String url = String.format(java.util.Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m",
                lat, lon);
            
            Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "OverDrive-ABRP/1.0")
                .build();
            
            // Use short timeout, same proxy logic as ABRP uploads
            OkHttpClient weatherClient = getProxiedClient().newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();
            
            Response response = weatherClient.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                JSONObject json = new JSONObject(body);
                JSONObject current = json.optJSONObject("current");
                if (current != null) {
                    double temp = current.getDouble("temperature_2m");
                    cachedWeatherTemp = temp;
                    lastWeatherFetchTime = now;
                    logger.debug("ext_temp: weather API = " + String.format("%.1f", temp) + "°C");
                    return temp;
                }
            }
            response.close();
        } catch (Exception e) {
            logger.debug("Weather API failed: " + e.getMessage());
        }
        
        // Return stale cache if available, otherwise NaN
        return cachedWeatherTemp;
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
