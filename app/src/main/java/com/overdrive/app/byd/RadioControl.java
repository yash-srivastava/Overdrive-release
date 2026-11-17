package com.overdrive.app.byd;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.TimeUnit;

/**
 * Toggle the device radios (WiFi / Bluetooth / mobile-data) from an automation action
 * or a key-mapping. WiFi and mobile data use Android's {@code svc} command in the daemon.
 * Bluetooth is dispatched to the existing app-process actuator, where the framework
 * {@code BluetoothAdapter} API is available; {@code svc bluetooth} can exit successfully
 * on this firmware without changing the adapter.
 *
 * <p><b>WiFi keep-alive interaction (the reason this is a shared helper, not a raw
 * shell binding).</b> The app runs a WiFi keep-alive watchdog that re-asserts
 * {@code svc wifi enable} every ~10s while parked (AccSentryDaemon), plus one-shot
 * enables at daemon boot (SentryDaemon) and a settings-based OEM keep-alive
 * (ServiceLauncher). If a user binds "turn WiFi off", that watchdog would immediately
 * turn it back on. So a WiFi-OFF here first sets a persisted suppression flag
 * ({@link UnifiedConfigManager#setWifiKeepAliveSuppressed}) that every keep-alive site
 * checks BEFORE re-enabling; a WiFi-ON clears it so keep-alive resumes normally. With no
 * radio rule the flag stays false (default) and keep-alive is unchanged. Mobile data
 * gained the same parked keep-alive (AccSentryDaemon.ensureMobileDataEnabled, issue
 * #209), so it carries the analogous flag
 * ({@link UnifiedConfigManager#setDataKeepAliveSuppressed}). Bluetooth writes the
 * canonical Surveillance preference before dispatch, so proximity and the parked
 * watchdog honor the same explicit radio intent.
 *
 * <p>Ordering matters for WiFi-off: the flag is committed BEFORE {@code svc wifi disable}
 * runs, so the next keep-alive tick already sees the suppression and won't race the
 * disable. All methods are best-effort and never throw to the caller (an automation /
 * keypress has no user awaiting a synchronous result); failures are logged.
 */
public final class RadioControl {

    private static final DaemonLogger logger = DaemonLogger.getInstance("RadioControl");

    /** Radios this helper can toggle. The {@code svc} subcommand name matches the enum. */
    public enum Radio { WIFI, BLUETOOTH, DATA }

    private RadioControl() {}

    /**
     * Turn a radio on or off. For WiFi, keeps the keep-alive suppression flag in sync so
     * an explicit off is not auto-re-enabled. Returns true when the direct command or
     * app-process Bluetooth dispatch was accepted, false otherwise.
     *
     * @param radio  which radio to toggle
     * @param enable true = enable, false = disable
     * @return true on a clean shell exit, false on failure
     */
    public static boolean set(Radio radio, boolean enable) {
        if (radio == null) return false;
        // WiFi / mobile data: keep the suppression flag in lock-step with the user's
        // intent. Set it BEFORE disabling so the ~10s keep-alive tick already sees
        // "user wants off" and does not race our disable; clear it when enabling so
        // keep-alive resumes. (Data gained a parked keep-alive with issue #209, so it
        // now needs the same flag WiFi always had.)
        if (radio == Radio.BLUETOOTH) {
            try {
                if (!UnifiedConfigManager.setBluetoothKeepAliveEnabled(enable)) {
                    logger.warn("RadioControl: could not save shared Bluetooth intent");
                    return false;
                }
                if (enable) {
                    // A deliberate ON from Automations or Key Mapping wins over a stale
                    // parked safe-zone lock confirmation in this daemon process.
                    com.overdrive.app.server.RemoteAccessApiHandler.noteBluetoothExplicitlyEnabled();
                }
            } catch (Throwable t) {
                logger.warn("RadioControl: could not save shared Bluetooth intent: "
                        + t.getMessage());
                return false;
            }
        }
        if (radio == Radio.WIFI || radio == Radio.DATA) {
            try {
                if (radio == Radio.WIFI) {
                    UnifiedConfigManager.setWifiKeepAliveSuppressed(!enable);
                } else {
                    UnifiedConfigManager.setDataKeepAliveSuppressed(!enable);
                }
            } catch (Throwable t) {
                logger.warn("RadioControl: could not persist " + radio
                        + " suppression flag: " + t.getMessage());
                // Continue: even if the flag write failed, honour the immediate toggle.
                // Worst case the keep-alive re-enables later — never worse than today.
            }
        }
        String svc = svcName(radio);
        String cmd = radio == Radio.BLUETOOTH
                ? "am start-foreground-service -n "
                    + "com.overdrive.app/.services.VehicleActuatorService"
                    + " --es action bluetooth --ez enabled " + enable
                    + " --ez bluetooth_intent_persisted true"
                : "svc " + svc + " " + (enable ? "enable" : "disable");
        boolean ok = runShell(cmd);
        logger.info("RadioControl " + svc + " " + (enable ? "enable" : "disable")
                + " -> " + (ok ? "ok" : "failed"));
        return ok;
    }

    private static String svcName(Radio radio) {
        switch (radio) {
            case BLUETOOTH: return "bluetooth";
            case DATA:      return "data";
            case WIFI:
            default:        return "wifi";
        }
    }

    /**
     * Parse a radio id string ("wifi"/"bluetooth"/"data") to the enum, or null if
     * unrecognised. Accepts a couple of friendly aliases.
     */
    public static Radio parse(String id) {
        if (id == null) return null;
        switch (id.trim().toLowerCase(java.util.Locale.US)) {
            case "wifi":       return Radio.WIFI;
            case "bluetooth":
            case "bt":         return Radio.BLUETOOTH;
            case "data":
            case "mobile_data":
            case "mobiledata": return Radio.DATA;
            default:           return null;
        }
    }

    /** Run one shell command in the daemon, bounded so a wedged command can't hang a worker. */
    private static boolean runShell(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd)).redirectErrorStream(true).start();
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[1024];
                try { while (is.read(buf) != -1) { /* discard */ } }
                catch (Throwable ignored) { }
            }, "radio-svc-drain");
            drain.setDaemon(true);
            drain.start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            drain.join(300);
            return p.exitValue() == 0;
        } catch (Throwable t) {
            logger.warn("RadioControl shell failed (" + cmd + "): " + t.getMessage());
            if (p != null) { try { p.destroyForcibly(); } catch (Throwable ignored) {} }
            return false;
        }
    }
}
