package com.overdrive.app.byd.cloud;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.byd.routing.VehicleCommandRouter.CommandResult;
/**
 * BYD Cloud Deterrent — fire-and-forget cloud commands on motion detection.
 * 
 * Singleton that integrates with SurveillanceEngineGpu. When motion is confirmed,
 * the surveillance engine calls onMotionDetected(). This class:
 * 1. Checks if a deterrent action is configured (not "silent")
 * 2. Enforces a cooldown period (default 60s)
 * 3. Dispatches the cloud command on a background thread
 * 4. Routes commands through the shared cloud router
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
        onMotionDetected(false, false);
    }

    /**
     * As {@link #onMotionDetected()}, with this event's confirmed classes so the user's
     * {@code deterrentTrigger} choice can hold the lights/horn back for motion alone.
     * The default {@code any} ignores both flags, which is the original behaviour.
     */
    public boolean onMotionDetected(boolean sawPerson, boolean sawMovingObject) {
        // Quick checks on the calling thread (no I/O)
        String action = getDeterrentAction();
        if ("silent".equals(action)) {
            return false;
        }
        if (!triggerAllows(getDeterrentTrigger(), sawPerson, sawMovingObject)) {
            return false;
        }

        // Check cooldown
        long now = System.currentTimeMillis();
        long lastTime = lastCommandTimeMs.get();
        long cooldownMs = getCooldownMs();
        if (now - lastTime < cooldownMs) {
            logger.debug("Deterrent cooldown active (" + (cooldownMs - (now - lastTime)) / 1000 + "s remaining)");
            return false;
        }

        // Prevent overlapping commands
        if (!commandInFlight.compareAndSet(false, true)) {
            logger.debug("Deterrent command already in flight");
            return false;
        }

        return true;  // Accepted for dispatch, not confirmation of hardware execution.
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
            VehicleCommandRouter.VehicleCommand command;
            switch (action) {
                case "flash_lights":
                    command = new VehicleCommandRouter.FlashLightsCommand();
                    break;
                case "find_car":
                    command = new VehicleCommandRouter.FindCarCommand();
                    break;
                default:
                    logger.warn("Unknown deterrent action: " + action);
                    return;
            }

            // The router owns capability discovery, remote-command
            // serialization, terminal confirmation, and timeout cancellation.
            CommandResult result = VehicleCommandRouter.getInstance().execute(command);
            lastCommandTimeMs.set(System.currentTimeMillis());
            logger.info("Deterrent " + action + " result=" + result.outcome
                    + " path=" + result.pathString());

        } catch (Exception e) {
            logger.warn("Deterrent execution failed: " + e.getMessage());
        }
    }

    // ── Config Readers ──────────────────────────────────────────────────

    private static String getDeterrentAction() {
        JSONObject surveillance = UnifiedConfigManager.getSurveillance();
        return surveillance.optString("deterrentAction", "silent");
    }

    /** {@code any} (default) | {@code person} | {@code person_or_vehicle}. */
    static boolean triggerAllows(String trigger, boolean sawPerson, boolean sawMovingObject) {
        if ("person".equals(trigger)) return sawPerson;
        if ("person_or_vehicle".equals(trigger)) return sawPerson || sawMovingObject;
        return true;
    }

    private static String getDeterrentTrigger() {
        JSONObject surveillance = UnifiedConfigManager.getSurveillance();
        return surveillance.optString("deterrentTrigger", "any");
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
        lastCommandTimeMs.set(0);
        commandInFlight.set(false);
    }
}
