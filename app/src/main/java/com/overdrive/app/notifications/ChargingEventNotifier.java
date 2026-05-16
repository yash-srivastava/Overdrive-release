package com.overdrive.app.notifications;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.server.Messages;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes vehicle.charging.* notifications:
 * <ul>
 *   <li>{@code vehicle.charging.started} — fires {@link #START_STABILIZE_MS}
 *       after the BMS enters CHARGING, so the kW reading has time to ramp
 *       up from 0. Cancelled if the state leaves CHARGING within the window
 *       (transient plug-in flicker → no notification).</li>
 *   <li>{@code vehicle.charging.stopped} — once when the BMS leaves CHARGING
 *       (debounced 10s to ride out brief gaps during DC handshakes).</li>
 *   <li>{@code vehicle.charging.full}    — once per session when SOC crosses
 *       {@link #FULL_SOC_THRESHOLD} while a session is active. Suppressed when
 *       the session began at or above the threshold (plugged-in-already-full).</li>
 *   <li>{@code vehicle.charging.fault}   — every distinct breakdown transition.</li>
 * </ul>
 *
 * <p>State changes hook the {@link BydDataCollector.ChargingStateListener}
 * edge stream — fires only on actual snapshot transitions, not on every poll.
 * SOC is sampled on a {@link #SOC_POLL_INTERVAL_MS} timer that runs only while
 * a session is active.
 *
 * <p>This notifier is purely a downstream consumer of the snapshot — it never
 * mutates {@code chargingState} or {@code chargingPowerKw}. ABRP, MQTT, and
 * the SOC-history graph all read the snapshot directly via {@code getData()}
 * / {@code getChargingState()} and are unaffected by this code path.
 */
public final class ChargingEventNotifier {

    private static final long STOP_DEBOUNCE_MS = 10_000L;

    /**
     * How long to wait after CHARGING begins before publishing "started".
     * BYD AC charging ramps from 0 to ~7 kW over 5–15s; DC fast charging
     * ramps to peak over 30–90s. 25s is a compromise that gets a stable
     * AC reading and a representative early-DC reading without making the
     * user wait too long. If the state leaves CHARGING during the window,
     * the publish is cancelled — transient flicker emits no notification.
     */
    private static final long START_STABILIZE_MS = 25_000L;

    /**
     * Threshold for "full" notification. Set below 100 because BYD often
     * plateaus at 99% during the final balancing/trickle phase; users want
     * the "ready to unplug" cue at this point, not the BMS-reported 100.
     */
    private static final double FULL_SOC_THRESHOLD = 99.5;

    private static final long SOC_POLL_INTERVAL_MS = 30_000L;

    private static volatile ChargingEventNotifier instance;

    private final BydDataCollector.ChargingStateListener listener =
            (prev, now) -> onChargingStateChanged(prev, now);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ChargingEventNotifier");
                t.setDaemon(true);
                return t;
            });

    // True once "started" has actually been published. Cleared after the
    // debounced "stopped" fires. Distinct from {@link #pendingStart} so the
    // SOC-full guard and stop debounce can tell pre-publish from post-publish.
    private volatile boolean sessionActive = false;

    // Pending start publish — cancelled if state leaves CHARGING within the
    // stabilization window, so a transient CHARGING flicker emits nothing.
    private volatile ScheduledFuture<?> pendingStart;

    // Pending stop debounce — cancelled if charging resumes before it fires.
    private volatile ScheduledFuture<?> pendingStop;

    // SOC polling task — runs only while sessionActive, cancelled on stop.
    private volatile ScheduledFuture<?> socPoller;

    // Already-fired guard for the "full" event in the current session.
    private volatile boolean fullFiredThisSession = false;

    // SOC at the moment the session became active. Used to suppress the
    // "full" notification when the user plugs in an already-full car.
    private volatile double sessionStartSoc = Double.NaN;

    private ChargingEventNotifier() {}

    public static synchronized void start() {
        if (instance != null) return;
        ChargingEventNotifier n = new ChargingEventNotifier();
        BydDataCollector.getInstance().addChargingStateListener(n.listener);
        instance = n;
    }

    private void onChargingStateChanged(int previousState, int newState) {
        ChargingStateData.ChargingStatus status = statusOf(newState);

        if (status == ChargingStateData.ChargingStatus.CHARGING) {
            // Resumed — cancel any pending stop. If we already published this
            // session, nothing else to do. Otherwise schedule a deferred start
            // so the kW reading has time to ramp.
            cancelPendingStop();
            if (sessionActive || pendingStart != null) return;

            BydVehicleData snap = BydDataCollector.getInstance().getData();
            sessionStartSoc = (snap != null) ? snap.socPercent : Double.NaN;

            final int stateAtScheduleTime = newState;
            pendingStart = scheduler.schedule(() -> {
                pendingStart = null;
                // Final guard: only fire if we're still in CHARGING. The
                // listener path also clears pendingStart on transition out,
                // but this catches the race where the cancel arrives just as
                // this task starts running.
                BydVehicleData s = BydDataCollector.getInstance().getData();
                if (s == null) return;
                ChargingStateData cs = new ChargingStateData(s.chargingState);
                if (cs.status != ChargingStateData.ChargingStatus.CHARGING) return;

                sessionActive = true;
                fullFiredThisSession = false;
                startSocPoller();
                publishStarted(stateAtScheduleTime);
            }, START_STABILIZE_MS, TimeUnit.MILLISECONDS);
            return;
        }

        if (status == ChargingStateData.ChargingStatus.ERROR) {
            // Faults are independent of session bookkeeping — always notify.
            cancelPendingStart();
            cancelPendingStop();
            stopSocPoller();
            publishFault(newState);
            sessionActive = false;
            return;
        }

        // FINISHED / TERMINATED / IDLE / READY / DISCHARGING.
        // If we never published "started" (transient flicker during
        // stabilization), drop the pending start silently.
        if (pendingStart != null) {
            cancelPendingStart();
            return;
        }
        // Debounced stop — only relevant once we've published "started".
        if (sessionActive && pendingStop == null) {
            final int finalState = newState;
            pendingStop = scheduler.schedule(() -> {
                pendingStop = null;
                if (!sessionActive) return;
                sessionActive = false;
                stopSocPoller();
                publishStopped(finalState);
            }, STOP_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelPendingStart() {
        ScheduledFuture<?> f = pendingStart;
        if (f != null) {
            f.cancel(false);
            pendingStart = null;
        }
    }

    private void startSocPoller() {
        stopSocPoller();
        socPoller = scheduler.scheduleWithFixedDelay(
                this::checkSocFull,
                SOC_POLL_INTERVAL_MS, SOC_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopSocPoller() {
        ScheduledFuture<?> f = socPoller;
        if (f != null) {
            f.cancel(false);
            socPoller = null;
        }
    }

    private void checkSocFull() {
        if (!sessionActive || fullFiredThisSession) return;
        BydVehicleData snap = BydDataCollector.getInstance().getData();
        if (snap == null) return;
        double soc = snap.socPercent;
        if (!isFinite(soc)) return;
        if (soc < FULL_SOC_THRESHOLD) return;

        // Plugged-in-already-full guard: only fire if the session crossed the
        // threshold while charging, not if it started at or above.
        if (isFinite(sessionStartSoc) && sessionStartSoc >= FULL_SOC_THRESHOLD) {
            fullFiredThisSession = true; // suppress further checks this session
            return;
        }

        fullFiredThisSession = true;
        publishFull(soc);
    }

    private void cancelPendingStop() {
        ScheduledFuture<?> f = pendingStop;
        if (f != null) {
            f.cancel(false);
            pendingStop = null;
        }
    }

    private void publishStarted(int stateCode) {
        BydVehicleData snap = BydDataCollector.getInstance().getData();
        double powerKw = (snap != null) ? snap.chargingPowerKw : Double.NaN;
        double socPercent = (snap != null) ? snap.socPercent : Double.NaN;

        StringBuilder body = new StringBuilder();
        if (isFinite(powerKw) && Math.abs(powerKw) >= 0.1) {
            body.append(formatKw(powerKw)).append(" kW");
        }
        if (isFinite(socPercent)) {
            if (body.length() > 0) body.append(" • ");
            body.append((int) Math.round(socPercent)).append("%");
        }

        JSONObject data = new JSONObject();
        try {
            data.put("stateCode", stateCode);
            if (isFinite(powerKw)) data.put("powerKw", powerKw);
            if (isFinite(socPercent)) data.put("socPercent", socPercent);
        } catch (Exception ignored) {}

        publish(new NotificationEvent(
                "vehicle.charging.started",
                NotificationEvent.Severity.INFO,
                Messages.get("notifications.charging_started"),
                body.toString(),
                "charging-session",
                null,
                data));
    }

    private void publishStopped(int stateCode) {
        BydVehicleData snap = BydDataCollector.getInstance().getData();
        double socPercent = (snap != null) ? snap.socPercent : Double.NaN;

        String reason = stateLabel(stateCode);
        StringBuilder body = new StringBuilder(reason);
        if (isFinite(socPercent)) {
            body.append(" • ").append((int) Math.round(socPercent)).append("%");
        }

        JSONObject data = new JSONObject();
        try {
            data.put("stateCode", stateCode);
            data.put("stateName", reason);
            if (isFinite(socPercent)) data.put("socPercent", socPercent);
        } catch (Exception ignored) {}

        publish(new NotificationEvent(
                "vehicle.charging.stopped",
                NotificationEvent.Severity.INFO,
                Messages.get("notifications.charging_stopped"),
                body.toString(),
                "charging-session",
                null,
                data));
    }

    private void publishFull(double socPercent) {
        JSONObject data = new JSONObject();
        try {
            data.put("socPercent", socPercent);
            data.put("threshold", FULL_SOC_THRESHOLD);
        } catch (Exception ignored) {}

        publish(new NotificationEvent(
                "vehicle.charging.full",
                NotificationEvent.Severity.INFO,
                Messages.get("notifications.charging_complete"),
                Messages.get("notifications.battery_ready_to_unplug",
                        (int) Math.round(socPercent)),
                "charging-full",
                null,
                data));
    }

    private void publishFault(int stateCode) {
        String label = stateLabel(stateCode);

        JSONObject data = new JSONObject();
        try {
            data.put("stateCode", stateCode);
            data.put("stateName", label);
        } catch (Exception ignored) {}

        publish(new NotificationEvent(
                "vehicle.charging.fault",
                NotificationEvent.Severity.CRITICAL,
                Messages.get("notifications.charging_fault"),
                label,
                "charging-fault",
                null,
                data));
    }

    private static void publish(NotificationEvent event) {
        try { NotificationBus.get().publish(event); } catch (Throwable ignored) {}
    }

    private static ChargingStateData.ChargingStatus statusOf(int stateCode) {
        return new ChargingStateData(stateCode).status;
    }

    private static String stateLabel(int stateCode) {
        return new ChargingStateData(stateCode).stateName;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static String formatKw(double kw) {
        return String.format(java.util.Locale.US, "%.1f", Math.abs(kw));
    }
}
