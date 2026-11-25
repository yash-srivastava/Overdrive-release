package com.overdrive.app.byd;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Package policy for BYD's built-in traffic monitor ({@code com.byd.trafficmonitor}).
 *
 * <p>Disabling it is OS package state ({@code pm disable-user}), not an OverDrive
 * preference. A firmware OTA re-scans the system partition and resurrects the
 * package, so the user's choice must be persisted separately and re-applied on
 * daemon boot — otherwise every update silently undoes it. This class owns both
 * halves plus the pm verbs so the app process (ADB) and the daemons (UID shell)
 * can never drift. Mirrors {@code OemDashcamApiHandler}'s sticky-disable contract
 * for {@code com.byd.cdr}.
 *
 * <p><b>Process split.</b> {@link #currentState()} and
 * {@link #enforceStickyDisableIfRequested()} shell out to {@code pm} directly and
 * are DAEMON-ONLY (UID 2000). The app process is UID 10xxx, where {@code pm}
 * fails — it must route the payloads from {@link #applyThenProbeCommand(boolean)}
 * through ADB and read the result back with {@link #stateAfterApply(String)}.
 */
public final class TrafficMonitorPolicy {

    private static final String TAG = "TrafficMonitorPolicy";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static final String PACKAGE = "com.byd.trafficmonitor";

    /** UCM home for OS-level package policy the user explicitly opted into. */
    private static final String CONFIG_SECTION = "systemApps";
    public static final String KEY_DISABLE_TRAFFIC_MONITOR = "disableTrafficMonitor";

    public static final String STATE_DISABLED = "disabled";
    public static final String STATE_ENABLED = "enabled";
    public static final String STATE_NOT_INSTALLED = "not_installed";
    /** Probe output was missing or unparseable — never treat as a state confirmation. */
    public static final String STATE_UNKNOWN = "unknown";

    /** Separates the pm mutation output from the verification probe that follows it.
     *  No leading dash and no shell metacharacters — `echo` must emit it verbatim. */
    private static final String PROBE_DELIMITER = "__OD_PROBE__";

    // `pm list packages -d` lists only disabled packages, so an empty result is
    // ambiguous (enabled or uninstalled). The sentinels make the negative case
    // explicit and keep the pipeline's exit code at 0.
    private static final String NOT_DISABLED = "NOT_DISABLED";
    private static final String NOT_INSTALLED = "NOT_INSTALLED";

    private TrafficMonitorPolicy() {}

    // ==================== Shell payloads (single source of truth) ====================

    /** Probe for "is the package currently in the disabled-user list". */
    public static String stateProbeCommand() {
        return "pm list packages " + com.overdrive.app.util.ShellUser.flag() + " -d 2>/dev/null | grep -F " + PACKAGE
                + " || echo " + NOT_DISABLED;
    }

    /** The pm verb for the requested state. */
    public static String applyCommand(boolean disable) {
        return disable
                ? "pm disable-user " + com.overdrive.app.util.ShellUser.flag() + " " + PACKAGE + " 2>&1"
                : "pm enable " + com.overdrive.app.util.ShellUser.flag() + " " + PACKAGE + " 2>&1";
    }

    /**
     * One roundtrip that mutates and then re-probes, so a caller never has to
     * accept "the shell returned" as proof the state changed. pm's own output
     * echoes the package name, which is why the probe is delimited — parse only
     * what follows the delimiter.
     */
    public static String applyThenProbeCommand(boolean disable) {
        return applyCommand(disable) + "; echo " + PROBE_DELIMITER + "; " + stateProbeCommand();
    }

    /** Parse a bare {@link #stateProbeCommand()} output. */
    public static boolean probeSaysDisabled(String probeOutput) {
        return probeOutput != null
                && probeOutput.contains(PACKAGE)
                && !probeOutput.contains(NOT_DISABLED);
    }

    /** Tri-state parse of {@link #stateProbeCommand()} output; unrecognised output is unknown. */
    public static String probeState(String probeOutput) {
        if (probeSaysDisabled(probeOutput)) return STATE_DISABLED;
        return probeOutput != null && probeOutput.contains(NOT_DISABLED) ? STATE_ENABLED : STATE_UNKNOWN;
    }

    /**
     * Resolve the verification tail of {@link #applyThenProbeCommand(boolean)}.
     * Returns {@link #STATE_UNKNOWN} when the delimiter is absent: the mutation
     * half never finished, and the pm echo alone contains {@link #PACKAGE}, so
     * reading it as a state would confirm a transition that never happened.
     */
    public static String stateAfterApply(String output) {
        if (output == null) return STATE_UNKNOWN;
        int cut = output.lastIndexOf(PROBE_DELIMITER);
        if (cut < 0) return STATE_UNKNOWN;
        String probe = output.substring(cut + PROBE_DELIMITER.length());
        return probeState(probe);
    }

    // ==================== Persisted preference ====================

    /** True when the user asked for the package to stay disabled across reboots. */
    public static boolean isDisableRequested() {
        try {
            return UnifiedConfigManager.getSystemApps()
                    .optBoolean(KEY_DISABLE_TRAFFIC_MONITOR, false);
        } catch (Throwable t) {
            logger.warn("systemApps read failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Persist the opt-in that {@link #enforceStickyDisableIfRequested()} reads at
     * boot. Blocking (daemon IPC + full config rewrite from the app UID) — call it
     * off the UI thread. Returns false when the write did not land, in which case
     * the pm change is live but will NOT survive the next OTA.
     */
    public static boolean setDisableRequested(boolean disable) {
        try {
            JSONObject delta = new JSONObject();
            delta.put(KEY_DISABLE_TRAFFIC_MONITOR, disable);
            return UnifiedConfigManager.updateSection(CONFIG_SECTION, delta);
        } catch (Throwable t) {
            logger.warn("systemApps write failed: " + t.getMessage());
            return false;
        }
    }

    // ==================== Daemon-side state + reconcile ====================

    /**
     * DAEMON-ONLY. Resolve live package state. The disabled list is checked FIRST,
     * which is what makes the second probe safe: by then the package is either
     * enabled-and-installed or absent, so a plain {@code pm path} settles it. (Not
     * {@code pm list packages -a} — that flag is newer than some DiLink firmwares,
     * and an unrecognised flag would read as "not_installed" and silently skip the
     * whole re-apply.) An unreadable probe also yields not_installed, so we never
     * fire pm at a package we failed to confirm; the next daemon boot retries.
     */
    public static String currentState() {
        if (probeSaysDisabled(execShell(stateProbeCommand()))) {
            return STATE_DISABLED;
        }
        String path = execShell("pm path " + com.overdrive.app.util.ShellUser.flag() + " " + PACKAGE + " 2>/dev/null || echo " + NOT_INSTALLED);
        if (!path.isEmpty() && !path.contains(NOT_INSTALLED)) {
            return STATE_ENABLED;
        }
        return STATE_NOT_INSTALLED;
    }

    /**
     * DAEMON-ONLY OTA-survives entry point, called from {@code CameraDaemon} boot
     * after UCM init. Re-applies {@code pm disable-user} when the user opted in but
     * a firmware OTA / factory reset / external {@code pm enable} resurrected the
     * package. No-op when the user never opted in or the package isn't on this trim.
     */
    public static void enforceStickyDisableIfRequested() {
        try {
            if (!isDisableRequested()) {
                return;
            }
            String state = currentState();
            if (!STATE_ENABLED.equals(state)) {
                return; // already disabled, or not on this firmware
            }
            String result = execShell(applyCommand(true));
            // Re-probe rather than trusting pm's exit — this log line is how we
            // confirm the OTA re-apply actually worked on a user's unit.
            if (STATE_DISABLED.equals(currentState())) {
                logger.info("Traffic monitor disable re-applied after OTA / factory reset / "
                        + "external `pm enable` — pm output: " + result);
            } else {
                logger.warn("Traffic monitor disable re-apply DID NOT take effect; "
                        + "package is still enabled — pm output: " + result);
            }
        } catch (Throwable t) {
            logger.warn("Traffic monitor sticky-disable check failed: " + t.getMessage());
        }
    }

    // ==================== Internal ====================

    /**
     * Execute a shell command and return trimmed stdout. Returns empty string on
     * any error so callers can {@code .contains()} without null-checking. Only
     * valid in the daemon processes, which run as UID 2000 (shell) and can exec
     * {@code pm} without ADB.
     */
    private static String execShell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd)});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            p.waitFor();
            return out.toString().trim();
        } catch (Exception e) {
            logger.warn("execShell failed for '" + cmd + "': " + e.getMessage());
            return "";
        }
    }
}
