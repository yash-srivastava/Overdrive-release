package com.overdrive.app.remoteaccess;

import android.os.SystemClock;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Daemon-side owner of {@code ProximityCollectorService}.
 *
 * <p>While the car is authoritatively off and proximity can run, it keeps the app-process
 * collectors alive: one start at switch-off, then a restart when relayed phone observations stop,
 * or a periodic re-start for a key-fob-only setup (see {@link ProximityCollectorKeepAlivePolicy}). Driving, a disabled feature, Service Mode or
 * "Vehicle ON only" stops the service. A disabled feature costs one cached config read per tick.
 */
public final class ProximityCollectorWatchdog {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ProximityCollector");
    private static final long TICK_MS = 5_000L;
    private static final long EXEC_TIMEOUT_MS = 10_000L;
    private static final String COMPONENT =
            "com.overdrive.app/com.overdrive.app.services.ProximityCollectorService";

    private static final Object LOCK = new Object();
    private static ScheduledExecutorService scheduler;
    private static final ProximityCollectorKeepAlivePolicy policy = new ProximityCollectorKeepAlivePolicy();

    private ProximityCollectorWatchdog() {}

    /** Idempotent. Called once from daemon startup. */
    public static void attach() {
        synchronized (LOCK) {
            if (scheduler != null) return;
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ProximityCollectorWatchdog");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(ProximityCollectorWatchdog::tickSafely,
                    TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            logger.warn("tick failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Only the watchdog thread calls this, so the policy needs no further locking. */
    private static void tick() {
        Gate gate = gate();
        // An unreadable state keeps the previous decision rather than flapping the service.
        if (gate == null) return;
        long now = SystemClock.elapsedRealtime();
        long lastObservation = com.overdrive.app.server.RemoteAccessApiHandler
                .lastObservationRelayElapsedMs();
        ProximityCollectorKeepAlivePolicy.Action action =
                policy.decide(gate.wanted, gate.heartbeat, now, lastObservation);
        if (action == ProximityCollectorKeepAlivePolicy.Action.START) {
            String silence = lastObservation < 0 ? "none" : ((now - lastObservation) / 1000) + "s";
            logger.info("starting collector service: reason=" + policy.reason()
                    + " lastObservation=" + silence + " nextRetryMs=" + policy.retryMs());
            exec("am start-foreground-service " + com.overdrive.app.util.ShellUser.flag()
                    + " -n " + COMPONENT);
        } else if (action == ProximityCollectorKeepAlivePolicy.Action.STOP) {
            logger.info("stopping collector service: reason=" + policy.reason());
            exec("am stopservice " + com.overdrive.app.util.ShellUser.flag() + " -n " + COMPONENT);
        }
    }

    private static final class Gate {
        final boolean wanted;
        final boolean heartbeat;

        Gate(boolean wanted, boolean heartbeat) {
            this.wanted = wanted;
            this.heartbeat = heartbeat;
        }
    }

    /**
     * Wanted: parked, and an app-process collector (trusted phone or key fob) would run if its
     * process were alive. Heartbeat: a phone collector is configured, so its relays prove liveness.
     * Null when the state cannot be read this tick.
     */
    private static Gate gate() {
        try {
            if (!com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative()
                    || com.overdrive.app.monitor.AccMonitor.isAccOn()) return new Gate(false, false);
            if (com.overdrive.app.config.ServiceModeFeatureGate.isPaused("proximity")
                    || UnifiedConfigManager.isVehicleOnOnlyMode()) return new Gate(false, false);
            org.json.JSONObject settings = RemoteAccessSettings.read();
            boolean phone = ProximityCollectorKeepAlivePolicy.phoneCollectorConfigured(settings);
            boolean fob = ProximityCollectorKeepAlivePolicy.keyFobConfigured(settings);
            return new Gate(phone || fob, phone);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void exec(String cmd) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "exec " + com.overdrive.app.util.ScratchRoot.remap(cmd) + " >/dev/null 2>&1"});
            process.getOutputStream().close();
            if (!process.waitFor(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("am did not exit within " + EXEC_TIMEOUT_MS + "ms");
            } else if (process.exitValue() != 0) {
                logger.warn("am exited with code " + process.exitValue());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            logger.warn("exec failed: " + t.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }
}
