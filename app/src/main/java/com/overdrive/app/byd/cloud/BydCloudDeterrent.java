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

    /** Surveillance-config deadline (epoch ms) the ACC judge reads to confirm an ON reading seen right after our own command. */
    public static final String ACTUATION_UNTIL_KEY = "deterrentActuationUntilMs";
    private static final long ACTUATION_WINDOW_MS = 55_000; // covers a cloud fallback; replaced once the command returns
    private static final long ACTUATION_SETTLE_MS = 8_000;
    // Same deadline on the elapsed clock, for this (camera daemon) process's own ACC probes.
    private static volatile long actuationUntilElapsedMs = 0L;

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
    public void onMotionDetected(boolean sawPerson, boolean sawMovingObject) {
        onMotionDetected(sawPerson, sawMovingObject, Float.NaN);
    }

    /**
     * {@code nearestRow}: this tick's closest qualifying motion row (0 far .. 6 near), NaN when there is none.
     * @return true only when a command was handed to the worker; every gate that holds it back returns false.
     */
    public boolean onMotionDetected(boolean sawPerson, boolean sawMovingObject, float nearestRow) {
        // Quick checks on the calling thread (no I/O)
        String action = getDeterrentAction();
        if ("silent".equals(action)) {
            return false;
        }
        if (!triggerAllows(getDeterrentTrigger(), sawPerson, sawMovingObject)) {
            return false;
        }
        if (!zoneAllows(getDeterrentZone(), nearestRow)) {
            logger.debug("Deterrent: motion outside its zone");
            return false;
        }
        if (!scheduleAllowsNow()) {
            logger.debug("Deterrent outside its scheduled hours");
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

        // Dispatch to background thread
        try {
            executor.execute(() -> {
                try {
                    executeDeterrent(action);
                } catch (Exception e) {
                    logger.warn("Deterrent failed: " + e.getMessage());
                } finally {
                    commandInFlight.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // A rejected task never runs its finally: release the flag or no deterrent fires again.
            commandInFlight.set(false);
            logger.warn("Deterrent dispatch rejected: " + e.getMessage());
            return false;
        }
        return true;
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
            publishActuationWindow(ACTUATION_WINDOW_MS);
            // Unattended: an unconfirmed DI5 power state can mean someone is in the car.
            boolean powerConfirmed =
                    com.overdrive.app.byd.routing.DrivingSafetyGuard.isDiLink5PowerStateConfirmed();
            if (!powerConfirmed) logger.info("Deterrent " + action + " held: DI5 power state unconfirmed");
            // Stamped when sent (the router can take seconds to confirm and the clip may
            // close first), but never for a command the DI5 power gate held back.
            if (powerConfirmed) {
                com.overdrive.app.surveillance.EventTimelineCollector.recordDeterrentFired(
                        com.overdrive.app.surveillance.EventTimelineCollector.DETERRENT_CLOUD, action);
            }
            CommandResult result = powerConfirmed
                    ? VehicleCommandRouter.getInstance().execute(command)
                    : CommandResult.blocked(com.overdrive.app.server.Messages.get(
                            "vehicle_control.blocked_unconfirmed"));
            lastCommandTimeMs.set(System.currentTimeMillis());
            publishActuationWindow(ACTUATION_SETTLE_MS);
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

    /** {@code follow} (default) and {@code extended} add no filter; {@code close}/{@code normal} need motion in that zone's rows. */
    static boolean zoneAllows(String zone, float nearestRow) {
        if (!"close".equals(zone) && !"normal".equals(zone)) return true;
        return !Float.isNaN(nearestRow) && !Float.isInfinite(nearestRow)
                && nearestRow >= com.overdrive.app.surveillance.MotionPipelineV2.Config
                        .maxDistanceRowForZone(zone);
    }

    private static String getDeterrentZone() {
        JSONObject surveillance = UnifiedConfigManager.getSurveillance();
        return surveillance.optString("deterrentZone", "follow");
    }

    /** Daily window in minutes since local midnight; start == end means all day. Off or invalid = always. */
    static boolean scheduleAllows(boolean enabled, int startMin, int endMin, int nowMin) {
        if (!enabled || startMin == endMin) return true;
        if (startMin < 0 || startMin > 1439 || endMin < 0 || endMin > 1439) return true;
        return startMin < endMin
                ? nowMin >= startMin && nowMin < endMin
                : nowMin >= startMin || nowMin < endMin;
    }

    private static boolean scheduleAllowsNow() {
        try {
            JSONObject surveillance = UnifiedConfigManager.getSurveillance();
            if (!surveillance.optBoolean("deterrentScheduleEnabled", false)) return true;
            java.util.Calendar now = java.util.Calendar.getInstance();
            return scheduleAllows(true,
                    surveillance.optInt("deterrentScheduleStartMin", 1200),
                    surveillance.optInt("deterrentScheduleEndMin", 360),
                    now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE));
        } catch (Exception e) {
            return true;
        }
    }

    // Legacy units only: the key-fob style frame can bounce the parked power reading; DiLink 5 has its own ACC path.
    private static void publishActuationWindow(long durationMs) {
        try {
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) return;
            actuationUntilElapsedMs = android.os.SystemClock.elapsedRealtime() + durationMs;
            UnifiedConfigManager.updateValues("surveillance", java.util.Collections.singletonMap(
                    ACTUATION_UNTIL_KEY, System.currentTimeMillis() + durationMs));
        } catch (Throwable t) {
            logger.debug("Actuation window publish failed: " + t.getMessage());
        }
    }

    /** True while this process is sending, or just sent, a deterrent command (legacy units only). */
    public static boolean isActuationWindowLive() {
        long until = actuationUntilElapsedMs;
        return until != 0L && android.os.SystemClock.elapsedRealtime() < until;
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
