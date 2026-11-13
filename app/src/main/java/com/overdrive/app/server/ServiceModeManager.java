package com.overdrive.app.server;

import android.content.Context;
import android.content.pm.PackageManager;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.launcher.AppLauncher;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchRoot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Daemon-owned Service Mode lifecycle.
 *
 * <p>Selected third-party packages are disabled by the shell-UID daemon only
 * after PackageManager revalidates each package as a user-installed launchable
 * app on every apply. The OverDrive package is never disabled: its dedicated
 * launcher alias is toggled through the authenticated app-process bridge, so
 * the daemon, boot receivers, and WebUI remain alive to restore the state.</p>
 *
 * <p>The serviceMode section is private runtime state. It is excluded from
 * backups and rejected by the generic settings writer; only this manager may
 * update it.</p>
 */
public final class ServiceModeManager {
    private static final String TAG = "ServiceMode";
    private static final String SECTION = "serviceMode";
    private static final String APP_PACKAGE = "com.overdrive.app";
    private static final int LAUNCHER_DISABLED =
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    private static final long MAX_DURATION_MS = TimeUnit.DAYS.toMillis(366);
    private static final long POLL_INTERVAL_SECONDS = 15L;
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final Object LOCK = new Object();
    private static final RemoteDevViewBridgeClient APP_BRIDGE =
            new RemoteDevViewBridgeClient();
    private static volatile ScheduledExecutorService scheduler;
    private static volatile boolean keymapPaused;

    private ServiceModeManager() {}

    /** Start the daemon-owned expiry/reconciliation worker. Safe to call repeatedly. */
    public static void start() {
        synchronized (LOCK) {
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "ServiceModeExpiry");
                    thread.setDaemon(true);
                    return thread;
                });
                scheduler.scheduleWithFixedDelay(
                        ServiceModeManager::tickSafely,
                        0L,
                        POLL_INTERVAL_SECONDS,
                        TimeUnit.SECONDS);
            }
        }
        refreshFeatureGates(readSectionQuietly());
    }

    /** Return the current state and the head unit's local date/time zone. */
    public static JSONObject getStatus() throws JSONException {
        JSONObject state = readSection();
        long endsAt = state.optLong("endsAtEpochMs", 0L);
        boolean enabled = state.optBoolean("enabled", false);
        boolean restoring = enabled && (state.optBoolean("ending", false)
                || endsAt <= System.currentTimeMillis());

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("enabled", enabled);
        result.put("active", enabled && !restoring);
        result.put("restoring", restoring);
        result.put("startedAtEpochMs", state.optLong("startedAtEpochMs", 0L));
        result.put("endsAtEpochMs", endsAt);
        result.put("hideOverdrive", state.optBoolean("hideOverdrive", false));
        result.put("hiddenPackages", copyArray(state.optJSONArray("hiddenPackages")));
        result.put("disabledFeatures", copyArray(state.optJSONArray("disabledFeatures")));
        result.put("lastError", state.optString("lastError", ""));
        result.put("serverLocalDate", formatServerLocalDate());
        result.put("timeZoneId", TimeZone.getDefault().getID());
        return result;
    }

    /** True only while the requested feature is actively paused (not while restoring). */
    public static boolean isFeatureDisabled(String feature) {
        if ("keymap".equals(feature)) return keymapPaused;
        if ("automations".equals(feature)) return Automations.isServiceModeDisabled();
        return false;
    }

    /**
     * Activate Service Mode immediately and schedule restoration at the end of
     * the selected head-unit-local date.
     */
    public static JSONObject startServiceMode(
            String endDate,
            boolean hideOverdrive,
            JSONArray requestedPackages,
            JSONArray requestedFeatures) throws Exception {
        start();
        synchronized (LOCK) {
            JSONObject existing = readSection();
            if (existing.optBoolean("enabled", false)) {
                long currentEnd = existing.optLong("endsAtEpochMs", 0L);
                if (existing.optBoolean("ending", false)
                        || currentEnd <= System.currentTimeMillis()) {
                    JSONObject restored = restoreLocked(existing);
                    if (!restored.optBoolean("success", false)) {
                        restored.put("error",
                                "The previous Service Mode is still restoring. Try again shortly.");
                        return restored;
                    }
                } else {
                    return failure("Service Mode is already active.");
                }
            }

            long now = System.currentTimeMillis();
            long endsAtEpochMs = parseEndDate(endDate);
            if (endsAtEpochMs <= now || endsAtEpochMs > now + MAX_DURATION_MS) {
                return failure("Choose an end date in the future, within 366 days.");
            }

            JSONArray allowedApps = AppLauncher.listUserLaunchableApps();
            Set<String> allowedPackages = new HashSet<>();
            for (int i = 0; i < allowedApps.length(); i++) {
                JSONObject app = allowedApps.optJSONObject(i);
                if (app != null) {
                    String pkg = app.optString("package", "");
                    if (!pkg.isEmpty()) allowedPackages.add(pkg);
                }
            }

            JSONArray hiddenPackages = normalizePackages(requestedPackages, allowedPackages);
            JSONArray disabledFeatures = normalizeFeatures(requestedFeatures);
            if (!hideOverdrive && hiddenPackages.length() == 0
                    && disabledFeatures.length() == 0) {
                return failure("Select OverDrive, at least one app, or at least one feature to pause.");
            }

            Context context = requireContext();
            PackageManager pm = context.getPackageManager();
            JSONArray originalStates = new JSONArray();
            for (int i = 0; i < hiddenPackages.length(); i++) {
                String pkg = hiddenPackages.getString(i);
                // Validate again at activation time; the picker list is not an authority.
                if (!AppLauncher.isUserLaunchableApp(pkg)) {
                    return failure("The selected app is no longer an eligible third-party app: " + pkg);
                }
                int state = pm.getApplicationEnabledSetting(pkg);
                if (isEnabledState(state)) {
                    originalStates.put(new JSONObject()
                            .put("package", pkg)
                            .put("state", state));
                }
            }

            int launcherOriginalState = -1;
            if (hideOverdrive) {
                launcherOriginalState = getLauncherStateFromAppProcess();
                if (launcherOriginalState < 0) {
                    return failure("The OverDrive app-process bridge is unavailable; its launcher was not changed.");
                }
            }

            JSONObject state = new JSONObject();
            state.put("enabled", true);
            state.put("ending", false);
            state.put("startedAtEpochMs", now);
            state.put("endsAtEpochMs", endsAtEpochMs);
            state.put("hideOverdrive", hideOverdrive);
            state.put("hiddenPackages", hiddenPackages);
            state.put("disabledFeatures", disabledFeatures);
            state.put("originalStates", originalStates);
            state.put("launcherOriginalState", launcherOriginalState);
            state.put("lastError", "");

            // Persist original state before the first external package-manager action.
            persist(state);
            String applyError = applyActiveModeLocked(state);
            JSONObject result = getStatus();
            result.put("success", applyError.isEmpty());
            if (!applyError.isEmpty()) result.put("error", applyError);
            return result;
        }
    }

    /** End Service Mode now and restore the captured package/component states. */
    public static JSONObject endServiceMode() throws Exception {
        start();
        synchronized (LOCK) {
            JSONObject state = readSection();
            if (!state.optBoolean("enabled", false)) {
                JSONObject result = getStatus();
                result.put("success", true);
                return result;
            }
            state.put("ending", true);
            persist(state);
            refreshFeatureGates(state);
            return restoreLocked(state);
        }
    }

    private static void tickSafely() {
        try {
            synchronized (LOCK) {
                JSONObject state = readSection();
                if (!state.optBoolean("enabled", false)) {
                    refreshFeatureGates(state);
                    return;
                }

                long endsAt = state.optLong("endsAtEpochMs", 0L);
                if (state.optBoolean("ending", false)
                        || endsAt <= System.currentTimeMillis()) {
                    if (!state.optBoolean("ending", false)) {
                        state.put("ending", true);
                        persist(state);
                    }
                    refreshFeatureGates(state);
                    restoreLocked(state);
                } else {
                    applyActiveModeLocked(state);
                }
            }
        } catch (Throwable error) {
            logger.warn("Service Mode reconciliation failed: " + error.getMessage());
        }
    }

    /**
     * Re-assert active mode. Crucially, re-check the package's current flags
     * immediately before every pm disable-user call; a package that becomes a
     * system/updated-system app is never disabled by a later reconciliation.
     */
    private static String applyActiveModeLocked(JSONObject state) throws Exception {
        if (!state.optBoolean("enabled", false) || state.optBoolean("ending", false)) {
            refreshFeatureGates(state);
            return "";
        }

        requireContext();
        refreshFeatureGates(state);
        List<String> failures = new ArrayList<>();
        JSONArray packages = state.optJSONArray("hiddenPackages");
        if (packages != null) {
            for (int i = 0; i < packages.length(); i++) {
                String pkg = packages.optString(i, "");
                if (pkg.isEmpty()) continue;

                // This check runs on every 15-second apply, not only on selection.
                if (!AppLauncher.isUserLaunchableApp(pkg)) {
                    failures.add(pkg + ": no longer verified as a third-party launchable app; skipped");
                    continue;
                }

                int currentState = getPackageEnabledState(pkg);
                if (currentState < 0) {
                    if (isPackageInstalled(pkg)) {
                        failures.add(pkg + ": could not read its enabled state");
                    }
                    continue;
                }
                if (!isEnabledState(currentState)) continue;

                JSONArray originalStates = state.optJSONArray("originalStates");
                if (findOriginalState(originalStates, pkg) < 0) {
                    originalStates = originalStates == null ? new JSONArray() : originalStates;
                    originalStates.put(new JSONObject()
                            .put("package", pkg)
                            .put("state", currentState));
                    state.put("originalStates", originalStates);
                    persist(state);
                }

                ShellResult result = runShell(
                        "pm disable-user --user 0 " + shellQuote(pkg));
                if (!result.success) {
                    failures.add(pkg + ": " + result.output);
                } else {
                    logger.info("Disabled verified third-party app for Service Mode: " + pkg);
                }
            }
        }

        if (state.optBoolean("hideOverdrive", false)) {
            int currentState = getLauncherStateFromAppProcess();
            if (currentState < 0) {
                failures.add("OverDrive launcher: app-process bridge is unavailable");
            } else if (currentState != LAUNCHER_DISABLED) {
                int originalState = state.optInt("launcherOriginalState", -1);
                if (originalState < 0) {
                    state.put("launcherOriginalState", currentState);
                    persist(state);
                }
                if (!setLauncherStateInAppProcess(LAUNCHER_DISABLED)) {
                    failures.add("OverDrive launcher: app-process component update failed");
                } else {
                    logger.info("Hidden OverDrive launcher alias in the app process");
                }
            }
        }

        String error = joinFailures(failures);
        if (!error.equals(state.optString("lastError", ""))) {
            state.put("lastError", error);
            persist(state);
        }
        return error;
    }

    /** Restore original package states; failures remain persisted for retry. */
    private static JSONObject restoreLocked(JSONObject state) throws Exception {
        requireContext();
        List<String> failures = new ArrayList<>();
        JSONArray originalStates = state.optJSONArray("originalStates");
        if (originalStates != null) {
            for (int i = 0; i < originalStates.length(); i++) {
                JSONObject original = originalStates.optJSONObject(i);
                if (original == null) continue;

                String pkg = original.optString("package", "");
                int previousState = original.optInt("state", -1);
                if (pkg.isEmpty() || previousState < 0) continue;

                int currentState = getPackageEnabledState(pkg);
                if (currentState < 0) {
                    if (isPackageInstalled(pkg)) {
                        failures.add(pkg + ": could not read its enabled state");
                    }
                    continue;
                }
                if (isEnabledState(currentState)) continue;

                // Restoring a previously enabled app is not a disable operation.
                // It is deliberately allowed even if a later firmware update
                // reclassified the package as a system app.
                ShellResult result = runShell(
                        restoreCommand(pkg, previousState));
                if (!result.success) {
                    failures.add(pkg + ": " + result.output);
                } else {
                    logger.info("Restored app after Service Mode: " + pkg);
                }
            }
        }

        int launcherOriginalState = state.optInt("launcherOriginalState", -1);
        if (launcherOriginalState >= 0) {
            int currentState = getLauncherStateFromAppProcess();
            if (currentState < 0) {
                failures.add("OverDrive launcher: app-process bridge is unavailable");
            } else if (currentState != launcherOriginalState
                    && !setLauncherStateInAppProcess(launcherOriginalState)) {
                failures.add("OverDrive launcher: app-process component restore failed");
            }
        }

        if (failures.isEmpty()) {
            JSONObject cleared = new JSONObject();
            cleared.put("enabled", false);
            cleared.put("ending", false);
            cleared.put("startedAtEpochMs", 0L);
            cleared.put("endsAtEpochMs", 0L);
            cleared.put("hideOverdrive", false);
            cleared.put("hiddenPackages", new JSONArray());
            cleared.put("disabledFeatures", new JSONArray());
            cleared.put("originalStates", new JSONArray());
            cleared.put("launcherOriginalState", -1);
            cleared.put("lastError", "");
            persist(cleared);
            refreshFeatureGates(cleared);

            JSONObject result = getStatus();
            result.put("success", true);
            return result;
        }

        String error = joinFailures(failures);
        state.put("enabled", true);
        state.put("ending", true);
        state.put("lastError", error);
        persist(state);
        refreshFeatureGates(state);

        JSONObject result = getStatus();
        result.put("success", false);
        result.put("error", "Service Mode is ending, but some items could not be restored. "
                + "The daemon will retry automatically. " + error);
        return result;
    }

    private static void refreshFeatureGates(JSONObject state) {
        // A transient config read error must not reopen gates while a previously
        // observed Service Mode is active. Keep the last known runtime flags until
        // a complete state snapshot can be read again.
        if (state.optBoolean("__readError", false)) return;

        boolean active = state.optBoolean("enabled", false)
                && !state.optBoolean("ending", false);
        boolean pauseAutomations = active
                && contains(state.optJSONArray("disabledFeatures"), "automations");
        boolean pauseKeymap = active
                && contains(state.optJSONArray("disabledFeatures"), "keymap");
        try {
            Automations.setServiceModeDisabled(pauseAutomations);
            keymapPaused = pauseKeymap;
        } catch (Throwable error) {
            logger.warn("Could not refresh Service Mode feature gates: " + error.getMessage());
        }
    }

    private static int getLauncherStateFromAppProcess() {
        try {
            JSONObject request = new JSONObject().put("command", "serviceModeLauncher");
            RemoteDevViewBridgeClient.Response response =
                    APP_BRIDGE.sendWithServiceStart(request, 1000, 3000, true);
            if (response == null || !response.metadata.optBoolean("success", false)) return -1;
            return response.metadata.optInt("enabledState", -1);
        } catch (Throwable error) {
            logger.warn("Could not read launcher state from app process: " + error.getMessage());
            return -1;
        }
    }

    private static boolean setLauncherStateInAppProcess(int state) {
        try {
            JSONObject request = new JSONObject()
                    .put("command", "serviceModeLauncher")
                    .put("state", state);
            RemoteDevViewBridgeClient.Response response =
                    APP_BRIDGE.sendWithServiceStart(request, 1000, 3000, true);
            return response != null && response.metadata.optBoolean("success", false)
                    && response.metadata.optInt("enabledState", -1) == state;
        } catch (Throwable error) {
            logger.warn("Could not update launcher state in app process: " + error.getMessage());
            return false;
        }
    }

    private static long parseEndDate(String endDate) throws Exception {
        if (endDate == null || !endDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("Choose a valid end date.");
        }
        TimeZone zone = TimeZone.getDefault();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        format.setTimeZone(zone);
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(endDate, position);
        if (parsed == null || position.getIndex() != endDate.length()) {
            throw new IllegalArgumentException("Choose a valid end date.");
        }
        Calendar calendar = Calendar.getInstance(zone, Locale.US);
        calendar.setTime(parsed);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTimeInMillis();
    }

    private static String formatServerLocalDate() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    private static JSONArray normalizePackages(
            JSONArray requested, Set<String> allowedPackages) throws JSONException {
        JSONArray normalized = new JSONArray();
        Set<String> seen = new HashSet<>();
        if (requested == null) return normalized;

        for (int i = 0; i < requested.length(); i++) {
            String pkg = requested.optString(i, "").trim();
            if (pkg.isEmpty() || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
                throw new IllegalArgumentException("Invalid app package name in selection.");
            }
            if (APP_PACKAGE.equals(pkg)) {
                throw new IllegalArgumentException("OverDrive is controlled by its dedicated option.");
            }
            if (!allowedPackages.contains(pkg) || !AppLauncher.isUserLaunchableApp(pkg)) {
                throw new IllegalArgumentException(
                        "Only verified third-party launchable apps can be selected: " + pkg);
            }
            if (seen.add(pkg)) normalized.put(pkg);
        }
        return normalized;
    }

    private static JSONArray normalizeFeatures(JSONArray requested) {
        JSONArray normalized = new JSONArray();
        Set<String> seen = new HashSet<>();
        if (requested == null) return normalized;

        for (int i = 0; i < requested.length(); i++) {
            String feature = requested.optString(i, "").trim().toLowerCase(Locale.ROOT);
            if (!"automations".equals(feature) && !"keymap".equals(feature)) {
                throw new IllegalArgumentException("Unsupported Service Mode feature: " + feature);
            }
            if (seen.add(feature)) normalized.put(feature);
        }
        return normalized;
    }

    private static int findOriginalState(JSONArray states, String pkg) {
        if (states == null) return -1;
        for (int i = 0; i < states.length(); i++) {
            JSONObject item = states.optJSONObject(i);
            if (item != null && pkg.equals(item.optString("package", ""))) {
                return item.optInt("state", -1);
            }
        }
        return -1;
    }

    private static boolean contains(JSONArray values, String wanted) {
        if (values == null) return false;
        for (int i = 0; i < values.length(); i++) {
            if (wanted.equals(values.optString(i, ""))) return true;
        }
        return false;
    }

    private static String restoreCommand(String pkg, int previousState) {
        String operation = previousState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                ? "default-state" : "enable";
        return "pm " + operation + " --user 0 " + shellQuote(pkg);
    }

    private static boolean isEnabledState(int state) {
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private static boolean isPackageInstalled(String pkg) {
        try {
            requireContext().getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        } catch (Throwable error) {
            logger.warn("Could not verify package installation for " + pkg + ": "
                    + error.getMessage());
            return true;
        }
    }

    private static int getPackageEnabledState(String pkg) {
        try {
            return requireContext().getPackageManager().getApplicationEnabledSetting(pkg);
        } catch (IllegalArgumentException error) {
            return -1;
        } catch (Throwable error) {
            logger.warn("Could not read package state for " + pkg + ": " + error.getMessage());
            return -1;
        }
    }

    private static Context requireContext() {
        Context context = CameraDaemon.getAppContext();
        if (context == null) {
            throw new IllegalStateException("The daemon package context is not ready.");
        }
        return context;
    }

    private static JSONObject readSection() throws JSONException {
        JSONObject section = UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
        return section == null ? new JSONObject() : new JSONObject(section.toString());
    }

    private static JSONObject readSectionQuietly() {
        try {
            return readSection();
        } catch (Throwable error) {
            logger.warn("Could not read Service Mode state: " + error.getMessage());
            JSONObject unavailable = new JSONObject();
            try { unavailable.put("__readError", true); } catch (JSONException ignored) { }
            return unavailable;
        }
    }

    private static void persist(JSONObject state) throws Exception {
        if (!UnifiedConfigManager.replaceSection(SECTION, state)) {
            throw new IllegalStateException("Could not persist Service Mode state.");
        }
    }

    private static JSONArray copyArray(JSONArray source) throws JSONException {
        return source == null ? new JSONArray() : new JSONArray(source.toString());
    }

    private static JSONObject failure(String message) throws JSONException {
        return new JSONObject().put("success", false).put("error", message);
    }

    private static String joinFailures(List<String> failures) {
        if (failures == null || failures.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (String failure : failures) {
            if (result.length() > 0) result.append("; ");
            result.append(failure);
        }
        return result.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final class ShellResult {
        final boolean success;
        final String output;

        ShellResult(boolean success, String output) {
            this.success = success;
            this.output = output == null ? "" : output.trim();
        }
    }

    private static ShellResult runShell(String command) {
        Process process = null;
        InputStream input = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = null;
        try {
            process = new ProcessBuilder("sh", "-c", ScratchRoot.remap(command))
                    .redirectErrorStream(true)
                    .start();
            input = process.getInputStream();
            final InputStream stream = input;
            final ByteArrayOutputStream capture = output;
            reader = new Thread(() -> {
                byte[] buffer = new byte[1024];
                int count;
                try {
                    while ((count = stream.read(buffer)) != -1) {
                        synchronized (capture) {
                            capture.write(buffer, 0, count);
                        }
                    }
                } catch (Throwable ignored) { }
            }, "ServiceModeShellOutput");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(8L, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ShellResult(false, "command timed out");
            }
            if (reader != null) reader.join(500L);
            String text = output.toString("UTF-8");
            return new ShellResult(process.exitValue() == 0, text);
        } catch (Throwable error) {
            return new ShellResult(false, error.getMessage());
        } finally {
            try { if (input != null) input.close(); } catch (Throwable ignored) { }
            try { if (process != null) process.destroy(); } catch (Throwable ignored) { }
        }
    }
}
