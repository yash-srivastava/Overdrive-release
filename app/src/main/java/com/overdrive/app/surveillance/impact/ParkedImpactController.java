package com.overdrive.app.surveillance.impact;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Daemon-side owner of the parked impact watch.
 *
 * <p>Runs the app-process {@link ParkedImpactSensorService} only while every condition holds:
 * the opt-in {@code surveillance.impactDetection} is on, ACC is authoritatively off, and sentry is
 * armed and running. Anything else stops it. A disabled feature costs one config read per tick.
 * Impact reports arriving outside that window are ignored, so a late report after ACC-on cannot
 * notify the driver about their own door.
 */
public final class ParkedImpactController {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ParkedImpact");
    private static final long RECONCILE_MS = 30_000L;
    /** Collapses a burst of reports from one incident into one notification. */
    private static final long NOTIFY_COOLDOWN_MS = 60_000L;
    /** Re-issue the (idempotent) start this often so an OS-killed app process is recovered. */
    private static final long REASSERT_MS = 5 * 60_000L;

    private static final Object LOCK = new Object();
    private static ScheduledExecutorService scheduler;
    private static boolean serviceRunning;
    private static String runningSensitivity;
    private static long lastStartMs;
    private static long lastNotifyMs;
    private static String sensorState = "waiting";
    private static String sensorName = "";
    private static long lastSensorReportMs;
    private static long lastSampleCount;

    private ParkedImpactController() {}

    /** Idempotent. Called once from daemon startup. */
    public static void attach() {
        synchronized (LOCK) {
            if (scheduler != null) return;
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ParkedImpactReconcile");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(ParkedImpactController::reconcileSafely,
                    RECONCILE_MS, RECONCILE_MS, TimeUnit.MILLISECONDS);
        }
    }

    public static boolean isEnabled() {
        try {
            return UnifiedConfigManager.getSurveillance().optBoolean("impactDetection", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static String getSensitivity() {
        try {
            String s = UnifiedConfigManager.getSurveillance().optString("impactSensitivity", "medium");
            return ("low".equals(s) || "high".equals(s)) ? s : "medium";
        } catch (Throwable t) {
            return "medium";
        }
    }

    /** Parked (ACC authoritatively off) with sentry armed and processing. */
    static boolean isWatchWindow() {
        try {
            return com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative()
                    && !com.overdrive.app.monitor.AccMonitor.isAccOn()
                    && com.overdrive.app.daemon.CameraDaemon.isSurveillanceEnabled()
                    && com.overdrive.app.daemon.CameraDaemon.isSurveillanceActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Re-evaluate now, e.g. after the settings change. */
    public static void reconcileNow() {
        ScheduledExecutorService s;
        synchronized (LOCK) { s = scheduler; }
        if (s == null) return;
        try {
            s.execute(ParkedImpactController::reconcileSafely);
        } catch (Throwable ignored) {}
    }

    private static void reconcileSafely() {
        try {
            reconcile();
        } catch (Throwable t) {
            logger.warn("reconcile failed: " + t.getMessage());
        }
    }

    private static void reconcile() {
        boolean want = isEnabled() && isWatchWindow();
        String sensitivity = getSensitivity();
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            boolean changed = !serviceRunning || !sensitivity.equals(runningSensitivity);
            if (want && (changed || now - lastStartMs >= REASSERT_MS)) {
                ParkedImpactSensorService.start(sensitivity);
                if (!serviceRunning) logger.info("watch started (sensitivity=" + sensitivity + ")");
                serviceRunning = true;
                runningSensitivity = sensitivity;
                lastStartMs = now;
            } else if (!want) {
                if (serviceRunning) {
                    ParkedImpactSensorService.stop();
                    serviceRunning = false;
                    runningSensitivity = null;
                    logger.info("watch stopped");
                }
                sensorState = "inactive";
                sensorName = "";
                lastSensorReportMs = 0L;
                lastSampleCount = 0L;
            }
        }
    }

    /** Record the app-process sensor heartbeat. */
    public static void onSensorStatus(String state, long samples, String name) {
        if (!"registered".equals(state) && !"sampling".equals(state)
                && !"unavailable".equals(state)) return;
        synchronized (LOCK) {
            sensorState = state;
            sensorName = name == null ? "" : name;
            lastSampleCount = Math.max(0L, samples);
            lastSensorReportMs = System.currentTimeMillis();
        }
    }

    /** Snapshot for the WebUI; stale heartbeats are never presented as healthy. */
    public static JSONObject getSensorStatus() {
        boolean enabled = isEnabled();
        boolean watchWindow = isWatchWindow();
        synchronized (LOCK) {
            long age = lastSensorReportMs == 0 ? Long.MAX_VALUE
                    : Math.max(0L, System.currentTimeMillis() - lastSensorReportMs);
            String state = age > 45_000L ? (enabled && watchWindow ? "stale" : "inactive")
                    : sensorState;
            JSONObject result = new JSONObject();
            try {
                result.put("enabled", enabled);
                result.put("watchWindow", watchWindow);
                result.put("state", state);
                result.put("sensor", sensorName);
                result.put("lastReportAgeMs", age == Long.MAX_VALUE ? JSONObject.NULL : age);
                result.put("samples", lastSampleCount);
            } catch (Exception ignored) {}
            return result;
        }
    }

    /**
     * Handle one confirmed hit from the app-side detector.
     *
     * @return true when the report was inside the watch window (whether or not it notified)
     */
    public static boolean onImpact(double peakMs2) {
        if (!isEnabled() || !isWatchWindow()) {
            logger.info("impact report ignored outside the watch window (peak=" + peakMs2 + ")");
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - lastNotifyMs < NOTIFY_COOLDOWN_MS) {
                logger.info("impact (peak=" + peakMs2 + ") within notify cooldown");
                return true;
            }
            lastNotifyMs = now;
        }
        logger.info("impact detected while parked (peak=" + peakMs2 + " m/s²)");
        try {
            JSONObject data = new JSONObject();
            data.put("peakMs2", Math.round(peakMs2 * 100.0) / 100.0);
            com.overdrive.app.notifications.NotificationBus.get().publish(
                    new com.overdrive.app.notifications.NotificationEvent(
                            "surveillance.impact",
                            com.overdrive.app.notifications.NotificationEvent.Severity.WARN,
                            com.overdrive.app.server.Messages.get("notifications.impact_title"),
                            com.overdrive.app.server.Messages.get("notifications.impact_body"),
                            "parked-impact",
                            "/events.html?filter=surveillance",
                            data));
        } catch (Throwable t) {
            logger.warn("impact notification failed: " + t.getMessage());
        }
        return true;
    }
}
