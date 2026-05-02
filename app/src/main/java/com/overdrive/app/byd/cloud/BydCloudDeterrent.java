package com.overdrive.app.byd.cloud;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BYD Cloud Deterrent — fire-and-forget cloud commands on motion detection.
 * 
 * Singleton that integrates with SurveillanceEngineGpu. When motion is confirmed,
 * the surveillance engine calls onMotionDetected(). This class:
 * 1. Checks if a deterrent action is configured (not "silent")
 * 2. Enforces a cooldown period (default 60s)
 * 3. Dispatches the cloud command on a background thread
 * 4. Handles login, session refresh, and PIN verification lazily
 * 5. Never throws exceptions back to the caller
 * 
 * Deterrent actions:
 * - "silent" (default): no action
 * - "flash_lights": flash headlights via FLASHLIGHTNOWHISTLE
 * - "find_car": horn + lights via FINDCAR
 */
public final class BydCloudDeterrent {

    private static final String TAG = "BydCloudDeterrent";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final long DEFAULT_COOLDOWN_MS = 15_000; // 15 seconds

    // Singleton
    private static volatile BydCloudDeterrent instance;

    // Background executor — single thread, commands are serialized
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BydCloudDeterrent");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // State
    private final AtomicLong lastCommandTimeMs = new AtomicLong(0);
    private final AtomicBoolean commandInFlight = new AtomicBoolean(false);
    private volatile BydCloudClient client;
    private volatile String resolvedVin;

    // Path to Bangcle tables (daemon-accessible location)
    private static final String TABLES_PATH = "/data/local/tmp/bangcle_tables.bin";
    private static final String TABLES_ASSET = "byd/bangcle_tables.bin";

    private BydCloudDeterrent() {}

    public static BydCloudDeterrent getInstance() {
        if (instance == null) {
            synchronized (BydCloudDeterrent.class) {
                if (instance == null) {
                    instance = new BydCloudDeterrent();
                }
            }
        }
        return instance;
    }

    /**
     * Called by SurveillanceEngineGpu when motion is confirmed and recording starts.
     * This method returns immediately — all work happens on a background thread.
     */
    public void onMotionDetected() {
        // Quick checks on the calling thread (no I/O)
        String action = getDeterrentAction();
        if ("silent".equals(action)) {
            return;
        }

        // Check cooldown
        long now = System.currentTimeMillis();
        long lastTime = lastCommandTimeMs.get();
        long cooldownMs = getCooldownMs();
        if (now - lastTime < cooldownMs) {
            logger.debug("Deterrent cooldown active (" + (cooldownMs - (now - lastTime)) / 1000 + "s remaining)");
            return;
        }

        // Prevent overlapping commands
        if (!commandInFlight.compareAndSet(false, true)) {
            logger.debug("Deterrent command already in flight");
            return;
        }

        // Dispatch to background thread
        executor.execute(() -> {
            try {
                executeDeterrent(action);
            } catch (Exception e) {
                logger.warn("Deterrent failed: " + e.getMessage());
            } finally {
                commandInFlight.set(false);
            }
        });
    }

    /**
     * Execute the deterrent action (runs on background thread).
     */
    private void executeDeterrent(String action) {
        logger.info("Executing deterrent action: " + action);

        try {
            BydCloudClient c = ensureClient();
            if (c == null) {
                logger.warn("BYD Cloud not configured — skipping deterrent");
                return;
            }

            String vin = ensureVin(c);
            if (vin == null || vin.isEmpty()) {
                logger.warn("No VIN available — skipping deterrent");
                return;
            }

            boolean success;
            switch (action) {
                case "flash_lights":
                    success = c.flashLights(vin);
                    break;
                case "find_car":
                    success = c.findCar(vin);
                    break;
                default:
                    logger.warn("Unknown deterrent action: " + action);
                    return;
            }

            lastCommandTimeMs.set(System.currentTimeMillis());
            logger.info("Deterrent " + action + " " + (success ? "succeeded" : "dispatched"));

        } catch (Exception e) {
            logger.warn("Deterrent execution failed: " + e.getMessage());
            // Reset client on auth failures so next attempt re-authenticates
            if (e.getMessage() != null && e.getMessage().contains("Login failed")) {
                client = null;
                resolvedVin = null;
            }
        }
    }

    /**
     * Lazily initialize the BYD cloud client.
     */
    private BydCloudClient ensureClient() {
        if (client != null && client.isReady()) {
            return client;
        }

        BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
        if (!config.isConfigured()) {
            return null;
        }

        try {
            BydCloudClient c = new BydCloudClient(config);

            // Load Bangcle tables
            InputStream tablesStream = getTablesStream();
            if (tablesStream == null) {
                logger.warn("Bangcle tables not available");
                return null;
            }
            try {
                c.init(tablesStream);
            } finally {
                try { tablesStream.close(); } catch (Exception ignored) {}
            }

            // Login
            c.login();

            // Verify control PIN
            String vin = config.vin;
            if (vin.isEmpty()) {
                vin = c.fetchFirstVin();
            }
            c.verifyControlPassword(vin);
            resolvedVin = vin;

            client = c;
            return c;
        } catch (Exception e) {
            logger.warn("Failed to initialize BYD cloud client: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the VIN, using cached value or fetching from API.
     */
    private String ensureVin(BydCloudClient c) {
        if (resolvedVin != null && !resolvedVin.isEmpty()) {
            return resolvedVin;
        }

        BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
        if (!config.vin.isEmpty()) {
            resolvedVin = config.vin;
            return resolvedVin;
        }

        try {
            resolvedVin = c.fetchFirstVin();
            return resolvedVin;
        } catch (Exception e) {
            logger.warn("Failed to fetch VIN: " + e.getMessage());
            return null;
        }
    }

    // ── Config Readers ──────────────────────────────────────────────────

    private static String getDeterrentAction() {
        JSONObject surveillance = UnifiedConfigManager.getSurveillance();
        return surveillance.optString("deterrentAction", "silent");
    }

    private static long getCooldownMs() {
        JSONObject surveillance = UnifiedConfigManager.getSurveillance();
        int seconds = surveillance.optInt("deterrentCooldownSeconds", 60);
        return seconds * 1000L;
    }

    /**
     * Force reset (for testing or credential changes).
     */
    public void reset() {
        client = null;
        resolvedVin = null;
        lastCommandTimeMs.set(0);
        commandInFlight.set(false);
    }

    /**
     * Get an InputStream for the Bangcle tables.
     * 
     * Strategy:
     * 1. Check /data/local/tmp/bangcle_tables.bin (pre-extracted)
     * 2. Try to extract from APK assets via DaemonBootstrap context
     * 3. Return null if unavailable
     */
    private InputStream getTablesStream() {
        // Strategy 1: Already extracted to daemon-accessible path
        File tablesFile = new File(TABLES_PATH);
        if (tablesFile.exists() && tablesFile.length() > 0) {
            try {
                return new FileInputStream(tablesFile);
            } catch (Exception e) {
                logger.debug("Could not read tables from " + TABLES_PATH + ": " + e.getMessage());
            }
        }

        // Strategy 2: Extract from APK assets via DaemonBootstrap context
        try {
            android.content.Context ctx = com.overdrive.app.daemon.DaemonBootstrap.getContext();
            if (ctx != null) {
                android.content.res.AssetManager am = ctx.getAssets();
                if (am != null) {
                    InputStream assetStream = am.open(TABLES_ASSET);
                    // Copy to /data/local/tmp/ for future use
                    try {
                        copyStreamToFile(assetStream, tablesFile);
                        assetStream.close();
                        // Re-open from the extracted file
                        return new FileInputStream(tablesFile);
                    } catch (Exception e) {
                        logger.debug("Could not extract tables to " + TABLES_PATH + ": " + e.getMessage());
                        // Fall back to reading directly from asset stream
                        return am.open(TABLES_ASSET);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not load tables from assets: " + e.getMessage());
        }

        return null;
    }

    private static void copyStreamToFile(InputStream in, File out) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        } finally {
            fos.close();
        }
        // Make world-readable for cross-UID access
        out.setReadable(true, false);
    }
}
