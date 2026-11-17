package com.overdrive.app.power;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * Voluntary self-shutdown when HV traction-battery state-of-charge drops to
 * an unsafe level during ACC=OFF surveillance.
 *
 * <p>Sources SoC from {@code BYDAutoStatisticDevice.onElecPercentageChanged(double)} —
 * the HV battery percentage, NOT the noisy 12V reading. The threshold defaults
 * to 10% and is overridable via the unified config under
 * {@code power.lowSocCutoffPercent}.
 *
 * <p>Behaviour at threshold:
 * <ol>
 *   <li>Wait {@code 60s} grace (debounce SoC drop).</li>
 *   <li>If SoC is still ≤ threshold: stop UI keep-alive, release wake-locks,
 *       call {@code PowerManager.goToSleep(uptime, reason, 0)} via reflection
 *       (reason = 13 on SDK ≥ 32, 9 otherwise),
 *       force-stop our own package, kill the daemon process, and
 *       {@code Runtime.exit(0)}.</li>
 * </ol>
 *
 * <p>The watchdogs respawn the daemons, so the cutoff is first latched in
 * {@code ParkedShutdown.SOC_CUTOFF_PATH}: the rest of the park then runs as "Vehicle ON only".
 *
 * <p>The "is the device currently charging" guard prevents shutdown when SoC
 * is rising — same SoC reading, opposite intent.
 */
public final class SocCutoffMonitor {

    private static final String TAG = "SocCutoffMonitor";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final int DEFAULT_CUTOFF_PERCENT = 10;
    private static final long GRACE_DELAY_MS = 60_000L;
    private static final long NOTICE_DRAIN_MS = 4_000L;

    private static volatile boolean running = false;
    private static volatile Double lastSocPercent = null;
    private static volatile long pendingShutdownTriggerAt = 0L;
    private static Object statisticListener;

    /**
     * Same-process {@link com.overdrive.app.monitor.BatterySocMonitor} instance.
     * That monitor properly subclasses {@code AbsBYDAutoStatisticListener}
     * and gets true callback granularity. We boot one here and let its
     * callback fan-out to {@link #notifyElecPercentage}. Cross-process
     * fan-out from {@code BydDataCollector} (in the cam_daemon process) is
     * also wired but only fires there — this in-process boot is what gives
     * acc_sentry-process callbacks.
     */
    private static com.overdrive.app.monitor.BatterySocMonitor socMonitor;

    private SocCutoffMonitor() {}

    /**
     * Caller-supplied context — usually the daemon's own
     * {@code appContext} (e.g. {@code AccSentryDaemon.getAppContext()}).
     * Doesn't reach into another daemon's class because the monitor must
     * work in whichever process boots it.
     */
    private static volatile Context appContext;

    public static synchronized void startMonitor(Context ctx) {
        if (running) return;
        appContext = ctx;
        running = true;
        logger.info("startMonitor: cutoff=" + cutoffPercent() + "%");
        registerStatisticListener();
        bootInProcessSocMonitor();
        seedFromCurrentReading();
    }

    public static synchronized void stopMonitor() {
        if (!running) return;
        running = false;
        unregisterStatisticListener();
        teardownInProcessSocMonitor();
        lastSocPercent = null;
        pendingShutdownTriggerAt = 0L;
        statisticListener = null;
    }

    private static void bootInProcessSocMonitor() {
        if (socMonitor != null) return;
        if (appContext == null) {
            logger.debug("bootInProcessSocMonitor: no app context (caller must pass one to startMonitor)");
            return;
        }
        try {
            socMonitor = new com.overdrive.app.monitor.BatterySocMonitor();
            socMonitor.init(appContext);
            socMonitor.start();
            logger.info("In-process BatterySocMonitor started — callbacks fan out via the listener");
        } catch (Throwable t) {
            logger.debug("bootInProcessSocMonitor failed: " + t.getMessage());
            socMonitor = null;
        }
    }

    private static void teardownInProcessSocMonitor() {
        if (socMonitor == null) return;
        try {
            socMonitor.stop();
        } catch (Throwable ignored) {}
        socMonitor = null;
    }

    // ── Public callback hook ────────────────────────────────────────

    /**
     * Entry point for live SoC callbacks from {@code BydDataCollector}'s
     * generic listener hub. Same rationale as {@code BatteryVoltageMonitorV2}:
     * the collector already subclasses {@code AbsBYDAutoStatisticListener}
     * once and dispatches via {@code onGenericCallback}; we piggyback on
     * that instead of trying to subclass an abstract class via Proxy.
     */
    public static void notifyElecPercentage(double pct) {
        if (!running) return;
        onElecPercentageChanged(pct);
    }

    // ── SoC handling ────────────────────────────────────────────────

    private static void onElecPercentageChanged(double pct) {
        if (!running) return;
        if (pct <= 0.0) {
            logger.debug("onElecPercentageChanged: invalid (" + pct + ")");
            return;
        }

        Double prev = lastSocPercent;
        if (prev != null && Math.abs(prev - pct) < 1e-6) {
            // identical reading — common
            return;
        }
        if (prev != null && prev < pct) {
            // SoC rose — vehicle is charging or alternator just kicked.
            // Cancel any pending cutoff.
            if (pendingShutdownTriggerAt != 0L) {
                logger.info("onElecPercentageChanged: SoC rising (" + prev + " -> " + pct
                        + "), cancelling pending shutdown");
                pendingShutdownTriggerAt = 0L;
            }
            lastSocPercent = pct;
            return;
        }

        int cutoff = cutoffPercent();
        if (pct <= cutoff) {
            if (isChargingOrPluggedIn()) {
                if (pendingShutdownTriggerAt != 0L) {
                    logger.info("LOW SoC " + pct + "% but charging — cancelling pending shutdown");
                    pendingShutdownTriggerAt = 0L;
                }
                lastSocPercent = pct;
                return;
            }
            long now = System.currentTimeMillis();
            if (pendingShutdownTriggerAt == 0L) {
                pendingShutdownTriggerAt = now + GRACE_DELAY_MS;
                logger.info("LOW SoC " + pct + "% <= cutoff " + cutoff
                        + "%, shutdown grace " + GRACE_DELAY_MS + "ms armed");
                scheduleGraceCheck();
            }
        }
        lastSocPercent = pct;
    }

    /**
     * A rising SoC is too slow a charging signal: whole-percent firmware on a slow AC charge can
     * hold the same value for minutes. Use the fused verdict, and a plugged-in gun as a backstop
     * (2=AC, 3=DC, 4=AC_DC; 5 is V2L, which discharges, so it does not count).
     */
    static boolean isChargingOrPluggedIn() {
        try {
            if (com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging()) return true;
        } catch (Throwable ignored) {}
        try {
            com.overdrive.app.byd.BydVehicleData d =
                    com.overdrive.app.byd.BydDataCollector.getInstance().getData();
            if (d != null) {
                int gun = d.chargingGunState;
                return gun == 2 || gun == 3 || gun == 4;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void scheduleGraceCheck() {
        Thread t = new Thread(() -> {
            try { Thread.sleep(GRACE_DELAY_MS); } catch (InterruptedException ignored) {}
            if (!running) return;
            if (pendingShutdownTriggerAt == 0L) {
                logger.info("grace expired but trigger cleared (charging or stopped) — abort");
                return;
            }
            Double soc = lastSocPercent;
            if (soc == null) {
                logger.warn("grace expired with null SoC — abort");
                return;
            }
            if (soc > cutoffPercent()) {
                logger.info("grace expired but SoC recovered to " + soc + "% — abort");
                pendingShutdownTriggerAt = 0L;
                return;
            }
            if (isChargingOrPluggedIn()) {
                logger.info("grace expired but vehicle is charging — abort");
                pendingShutdownTriggerAt = 0L;
                return;
            }
            performShutdown(soc);
        }, "SocCutoffGrace");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Voluntary self-shutdown sequence — order chosen to release the
     * head-unit's resources cleanly before our own exit.
     */
    private static void performShutdown(double finalSoc) {
        logger.warn("performShutdown: SoC=" + finalSoc + "% — beginning voluntary exit");
        latchForThisPark();
        try { com.overdrive.app.server.timeline.TimelineJournal.noteSync("power", "stopped", "soc_cutoff", null); } catch (Throwable ignored) { }
        // Before anything is torn down: the daemon that delivers the notice is about to stop.
        boolean noticeQueued = PowerNotice.send(PowerNotice.KIND_SOC_CUTOFF,
                "SoC " + Math.round(finalSoc) + "% (cutoff " + cutoffPercent() + "%)");
        if (noticeQueued) {
            // Push/Telegram deliver asynchronously and the force-stop below kills that daemon.
            try { Thread.sleep(NOTICE_DRAIN_MS); } catch (InterruptedException ignored) {}
        }

        // 1. Stop the V2 monitor's wake-lock + handler.
        try {
            BatteryVoltageMonitorV2.stopMonitorForShutdown();
        } catch (Throwable ignored) {}

        // 1a. Release the DiLink 5 parked keep-alive lease (sentry flags,
        // panorama heartbeat, AP token) while the HAL binders are still alive.
        // The force-stop below does not run the daemon's shutdown hook, and a
        // lease that outlives the process would defeat the whole point of this
        // cutoff. No-op unless the lease is installed and holding.
        try {
            Di5ParkedPowerHold.releaseForProcessExit("SoC cutoff");
        } catch (Throwable ignored) {}

        // 1b. Release the verified stealth-panel lock BEFORE teardown, but only
        // on platforms that can actually own that lock. Legacy Di3 darkening
        // uses the ordinary backlight path; calling StealthPanel.turnOn there
        // newly lights the panel immediately before goToSleep and can look like
        // a random boot animation during the low-SoC shutdown edge.
        boolean stealthPanelPlatform = false;
        try {
            stealthPanelPlatform =
                    com.overdrive.app.camera.dilink5.DiLink5Platform
                            .isDiLink4Selected()
                    || com.overdrive.app.camera.dilink5.DiLink5Platform
                            .isSelected();
        } catch (Throwable ignored) {}
        if (stealthPanelPlatform) {
            try {
                StealthPanel.turnOn(appContext);
            } catch (Throwable t) {
                logger.warn("Panel wake before shutdown failed: "
                        + t.getMessage());
            }
        }

        // 2. Ask the head unit to enter sleep — display off + system idle.
        // Uses the caller-supplied context (this monitor may run in any
        // daemon process; don't reach into another daemon's static).
        try {
            powerManagerGoToSleep();
        } catch (Throwable t) {
            logger.warn("powerManagerGoToSleep failed: " + t.getMessage());
        }

        // 3. Force-stop the app package (UI keep-alive, revival alarms). The
        //    shell-UID daemons survive it; their watchdogs respawn them into the latched park.
        try {
            String pkg = (appContext != null) ? appContext.getPackageName() : "com.overdrive.app";
            execShell("am force-stop " + pkg);
        } catch (Throwable t) {
            logger.warn("am force-stop failed: " + t.getMessage());
        }

        // 4. Belt-and-braces: if the daemon process survives the
        //    force-stop (different UID than the app, no parent activity),
        //    pkill the cam daemon explicitly. acc_sentry will Runtime.exit
        //    itself below.
        try {
            execShell("pkill -9 byd_cam_daemon 2>/dev/null");
        } catch (Throwable ignored) {}

        // 5. Final stop. exit() not halt() — gives finalizers a chance.
        logger.warn("Runtime.exit(0)");
        Runtime.getRuntime().exit(0);
    }

    // ── PowerManager.goToSleep reflection ───────────────────────────
    //
    // Sibling-app reasonId: SDK >= 32 → 13, else 9. flags=0.

    private static void powerManagerGoToSleep() throws Exception {
        Context ctx = appContext;
        if (ctx == null) return;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        Method goToSleep = PowerManager.class.getMethod(
                "goToSleep", long.class, int.class, int.class);
        int reason = (Build.VERSION.SDK_INT >= 32) ? 13 : 9;
        goToSleep.invoke(pm, SystemClock.uptimeMillis(), reason, 0);
        logger.info("PowerManager.goToSleep(uptime, reason=" + reason + ", flags=0) OK");
    }

    private static void execShell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] { "sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd) });
            p.waitFor();
        } catch (Throwable t) {
            logger.debug("execShell '" + cmd + "': " + t.getMessage());
        }
    }

    private static void latchForThisPark() {
        java.io.File latch = new java.io.File(
                com.overdrive.app.ui.model.ParkedShutdown.SOC_CUTOFF_PATH);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(latch, false)) {
            out.write(Long.toString(System.currentTimeMillis())
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (Throwable t) {
            logger.warn("cutoff latch write failed (" + latch.getPath() + "): " + t.getMessage());
            return;
        }
        // The app UID reads it through UnifiedConfigManager.isVehicleOnOnlyMode().
        latch.setReadable(true, false);
        logger.info("cutoff latched for this park: " + latch.getPath());
    }

    // ── BYD listener registration ───────────────────────────────────

    private static void registerStatisticListener() {
        // Live SoC callbacks land via BydDataCollector.onGenericCallback,
        // which fans out to {@link #notifyElecPercentage}. No direct
        // listener registration here.
        logger.info("registerStatisticListener: piggybacking on BydDataCollector generic hub");
    }

    private static void unregisterStatisticListener() {
        // No direct listener; nothing to release.
    }

    private static void seedFromCurrentReading() {
        // Resolve the BYDAutoStatisticDevice from our caller-supplied
        // appContext — same rationale as BatteryVoltageMonitorV2: going
        // through BydDataCollector.getInstance() returns a fresh empty
        // collector in any process other than cam_daemon. The in-process
        // BatterySocMonitor we boot also gives us listener-driven
        // callbacks, so the seed is just a fast-path for the first tick.
        if (appContext == null) {
            logger.debug("seedFromCurrentReading: no appContext");
            return;
        }
        try {
            Class<?> cls = Class.forName(
                    "android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
            java.lang.reflect.Method getInstance = cls.getMethod(
                    "getInstance", android.content.Context.class);
            Object stat = getInstance.invoke(null, appContext);
            if (stat == null) return;
            Object v = BydDeviceHelper.callGetter(stat, "getElecPercentageValue");
            if (v instanceof Number) {
                onElecPercentageChanged(((Number) v).doubleValue());
            }
        } catch (Throwable t) {
            logger.debug("seedFromCurrentReading: " + t.getMessage());
        }
    }

    // ── Config + helpers ────────────────────────────────────────────

    private static int cutoffPercent() {
        try {
            org.json.JSONObject root = com.overdrive.app.config
                    .UnifiedConfigManager.loadConfig();
            org.json.JSONObject section = root != null
                    ? root.optJSONObject("power") : null;
            if (section != null && section.has("lowSocCutoffPercent")) {
                int pct = section.optInt("lowSocCutoffPercent", DEFAULT_CUTOFF_PERCENT);
                // Clamp a hand-edited / corrupted value to a sane band. The UI
                // POST already clamps 0..30, but this read is the LAST line of
                // defence on a safety path: an out-of-band high value (e.g. 1000
                // from a bad edit) would make `pct <= cutoff` always true and
                // self-shut-down the head unit on the next SoC tick. Floor 0
                // preserves "0 = Off"; ceil 100 (a valid SoC max) is the highest
                // a cutoff can meaningfully be.
                if (pct < 0) pct = 0;
                if (pct > 100) pct = 100;
                return pct;
            }
        } catch (Throwable ignored) {}
        return DEFAULT_CUTOFF_PERCENT;
    }

    // resolveDevice(...) by collector-field is intentionally absent —
    // process-local resolve via appContext is what works in any daemon
    // process (see BatteryVoltageMonitorV2 for the same comment).
}
