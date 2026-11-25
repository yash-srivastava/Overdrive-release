package com.overdrive.app.server;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.ServiceModeAutomationGate;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.config.ServiceModeFeatureGate;
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
 * app on every apply. OverDrive's launcher entry and package state are never
 * changed by Service Mode.</p>
 *
 * <p>The serviceMode section is private runtime state. It is excluded from
 * backups and rejected by the generic settings writer; only this manager may
 * update it.</p>
 */
public final class ServiceModeManager {
    private static final String TAG = "ServiceMode";
    private static final String SECTION = "serviceMode";
    private static final String APP_PACKAGE = "com.overdrive.app";
    // Rescue instructions live outside OverDrive's APK/data, so an uninstall
    // does not erase the only record of the original package states.
    private static final String RESCUE_SCRIPT = "/sdcard/Download/OverDrive-ServiceMode-restore.sh";
    // Scratch-root files, resolved per call so a relocated root (ScratchRoot.ALT_DIR) is honoured.
    private static String privateRescueScript() { return ScratchRoot.path("overdrive-service-mode-rescue.sh"); }
    private static String guardScript() { return ScratchRoot.path("overdrive-service-mode-guard.sh"); }
    private static String guardMarker() { return ScratchRoot.path("overdrive-service-mode-active"); }
    private static String guardLock() { return ScratchRoot.path("overdrive-service-mode-guard.lock"); }
    // A live pid is only the guardian if its command line names the guard script.
    private static final String GUARD_ALIVE_TEST = "[ -n \"$1\" ] && kill -0 \"$1\" 2>/dev/null"
            + " && tr '\\000' ' ' < \"/proc/$1/cmdline\" 2>/dev/null"
            + " | grep -q 'overdrive-service-mode-guard[.]sh'";
    private static volatile boolean guardInitialized;
    private static final long MAX_DURATION_MS = TimeUnit.DAYS.toMillis(366);
    private static final long POLL_INTERVAL_SECONDS = 15L;
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final Object LOCK = new Object();
    private static volatile ScheduledExecutorService scheduler;

    private ServiceModeManager() {}

    /** Start the 15-second reconciliation worker only while Service Mode is active. */
    public static void start() {
        synchronized (LOCK) {
            JSONObject state = readSectionQuietly();
            boolean readError = state.optBoolean("__readError", false);
            boolean active = state.optBoolean("enabled", false);
            if (readError) {
                // Preserve the last-known worker and feature gates during a transient
                // config read failure; stopping here could strand an active schedule.
            } else if (active) {
                if (scheduler == null || scheduler.isShutdown()) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "ServiceModeExpiry");
                        thread.setDaemon(true);
                        return thread;
                    });
                    scheduler.scheduleWithFixedDelay(
                            ServiceModeManager::tickSafely,
                            0L, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
                }
            } else if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
            // App context is not necessarily ready on the HTTP-server thread.
            // Install persisted gates before it can accept its first request;
            // the background worker alone needs package-manager context.
            refreshFeatureGates(state);
        }
    }

    private static void ensureWorkerRunning() {
        synchronized (LOCK) {
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "ServiceModeExpiry");
                    thread.setDaemon(true);
                    return thread;
                });
                scheduler.scheduleWithFixedDelay(
                        ServiceModeManager::tickSafely,
                        0L, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    /** Return the current state and the head unit's local date/time zone. */
    public static JSONObject getStatus() throws JSONException {
        JSONObject state = readSection();
        long endsAt = state.optLong("endsAtEpochMs", 0L);
        boolean enabled = state.optBoolean("enabled", false);
        boolean restoring = enabled && (state.optBoolean("ending", false)
                || isExpired(state));

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("enabled", enabled);
        result.put("active", enabled && !restoring);
        result.put("restoring", restoring);
        result.put("startedAtEpochMs", state.optLong("startedAtEpochMs", 0L));
        result.put("endsAtEpochMs", endsAt);
        result.put("hiddenPackages", copyArray(state.optJSONArray("hiddenPackages")));
        result.put("disabledFeatures", copyArray(state.optJSONArray("disabledFeatures")));
        result.put("lastError", state.optString("lastError", ""));
        result.put("serverLocalDate", formatServerLocalDate());
        result.put("timeZoneId", TimeZone.getDefault().getID());
        result.put("rescueScriptPath", privateRescueScript());
        return result;
    }

    /**
     * Activate Service Mode immediately and schedule restoration at the end of
     * the selected head-unit-local date.
     */
    public static JSONObject startServiceMode(
            String endDate,
            JSONArray requestedPackages,
            JSONArray requestedFeatures) throws Exception {
        start();
        synchronized (LOCK) {
            JSONObject existing = readSection();
            if (existing.optBoolean("enabled", false)) {
                long currentEnd = existing.optLong("endsAtEpochMs", 0L);
                if (existing.optBoolean("ending", false)
                        || isExpired(existing)) {
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
            if (hiddenPackages.length() == 0 && disabledFeatures.length() == 0) {
                return failure("Select at least one app or feature to pause.");
            }

            int targetUser = com.overdrive.app.util.ShellUser.targetUserId();
            Context context = requireContext(targetUser);
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

            JSONObject state = new JSONObject();
            state.put("enabled", true);
            state.put("ending", false);
            state.put("startedAtEpochMs", now);
            state.put("endsAtEpochMs", endsAtEpochMs);
            // elapsedRealtime is monotonic within a boot and therefore still
            // expires the schedule if the wall clock is moved backwards.
            state.put("startedAtElapsedMs", SystemClock.elapsedRealtime());
            state.put("endsAtElapsedMs", SystemClock.elapsedRealtime()
                    + Math.max(0L, endsAtEpochMs - now));
            state.put("bootCount", currentBootCount());
            state.put("hiddenPackages", hiddenPackages);
            state.put("disabledFeatures", disabledFeatures);
            state.put("originalStates", originalStates);
            // Ending must undo the same user even after a user switch or an early-boot restart.
            state.put("targetUser", targetUser);
            state.put("lastError", "");

            // Establish an out-of-APK recovery path BEFORE changing any package.
            // The independent shell guard restores on uninstall; the saved
            // rescue script also survives a reboot with OverDrive uninstalled.
            if (originalStates.length() != 0) {
                String rescueError = ensureRecoveryGuard(state);
                if (!rescueError.isEmpty()) {
                    return failure("Cannot safely hide apps without a recovery copy: " + rescueError);
                }
            }
            // Persist original state before the first external package-manager action.
            persist(state);
            ensureWorkerRunning();
            String applyError = applyActiveModeLocked(state);
            JSONObject result = getStatus();
            result.put("success", applyError.isEmpty());
            if (!applyError.isEmpty()) result.put("error", applyError);
            return result;
        }
    }

    /** End Service Mode now and restore the captured package states. */
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
                        || isExpired(state)) {
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
        JSONArray savedStates = state.optJSONArray("originalStates");
        // The guardian deletes its marker once it has restored the apps; never hide them again.
        if (savedStates != null && savedStates.length() != 0 && !guardMarkerPresent()) {
            logger.warn("Service Mode recovery marker is gone; ending Service Mode");
            state.put("ending", true);
            persist(state);
            refreshFeatureGates(state);
            JSONObject restored = restoreLocked(state);
            return restored.optBoolean("success", false) ? "" : restored.optString("error", "");
        }
        if (savedStates != null && savedStates.length() != 0) {
            String guardError = ensureRecoveryGuard(state);
            if (!guardError.isEmpty()) return "Recovery guard unavailable: " + guardError;
        }
        List<String> failures = new ArrayList<>();
        JSONArray packages = state.optJSONArray("hiddenPackages");
        if (packages != null) {
            for (int i = 0; i < packages.length(); i++) {
                String pkg = packages.optString(i, "");
                if (pkg.isEmpty()) continue;

                int currentState = getPackageEnabledState(pkg, targetUser(state));
                if (currentState < 0) {
                    if (isPackageInstalled(pkg, targetUser(state))) {
                        failures.add(pkg + ": could not read its enabled state");
                    }
                    continue;
                }
                // A disabled app may no longer appear in the launchable-app picker.
                // Revalidate only when re-disabling an enabled app, immediately
                // before invoking pm disable-user, including on later ticks.
                if (!isEnabledState(currentState)) continue;
                if (!AppLauncher.isUserLaunchableApp(pkg)) {
                    failures.add(pkg + ": no longer verified as a third-party launchable app; skipped");
                    continue;
                }

                // Only ever change packages captured before the first disable.
                // The independent rescue script contains exactly that immutable
                // snapshot; adding a new package later would leave it unprotected
                // if OverDrive were uninstalled before End.
                if (findOriginalState(savedStates, pkg) < 0) continue;

                ShellResult result = runShell(
                        "pm disable-user " + userFlag(targetUser(state)) + " " + shellQuote(pkg));
                if (!result.success) {
                    failures.add(pkg + ": " + result.output);
                } else {
                    logger.info("Disabled verified third-party app for Service Mode: " + pkg);
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

                int currentState = getPackageEnabledState(pkg, targetUser(state));
                if (currentState < 0) {
                    if (isPackageInstalled(pkg, targetUser(state))) {
                        failures.add(pkg + ": could not read its enabled state");
                    }
                    continue;
                }
                if (isEnabledState(currentState)) continue;

                // Restoring a previously enabled app is not a disable operation.
                // It is deliberately allowed even if a later firmware update
                // reclassified the package as a system app.
                ShellResult result = runShell(
                        restoreCommand(pkg, previousState, targetUser(state)));
                if (!result.success) {
                    failures.add(pkg + ": " + result.output);
                } else if (!isEnabledState(getPackageEnabledState(pkg, targetUser(state)))) {
                    failures.add(pkg + ": still disabled after pm");
                } else {
                    logger.info("Restored app after Service Mode: " + pkg);
                }
            }
        }

        if (failures.isEmpty()) {
            JSONObject cleared = new JSONObject();
            cleared.put("enabled", false);
            cleared.put("ending", false);
            cleared.put("startedAtEpochMs", 0L);
            cleared.put("endsAtEpochMs", 0L);
            cleared.put("hiddenPackages", new JSONArray());
            cleared.put("disabledFeatures", new JSONArray());
            cleared.put("originalStates", new JSONArray());
            cleared.put("lastError", "");
            persist(cleared);
            refreshFeatureGates(cleared);
            guardInitialized = false;
            // Best-effort cleanup: an obsolete rescue file is safe (it can only
            // re-enable previously enabled apps) but need not stay in Downloads.
            runShell("rm -f " + shellQuote(guardMarker()) + " "
                    + shellQuote(RESCUE_SCRIPT) + " " + shellQuote(guardScript())
                    + " " + shellQuote(privateRescueScript()));
            if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }

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
                && !state.optBoolean("ending", false)
                && !isExpired(state);
        boolean wasProximityPaused = ServiceModeFeatureGate.isPaused("proximity");
        boolean wasRoadSensePaused = ServiceModeFeatureGate.isPaused("roadsense");
        boolean wasWidgetsPaused = ServiceModeFeatureGate.isPaused("widgets");
        ServiceModeFeatureGate.apply(active ? state.optJSONArray("disabledFeatures") : null);
        // Reconcile live services on a gate transition, off the state lock.
        if (wasProximityPaused != ServiceModeFeatureGate.isPaused("proximity")
                && CameraDaemon.getAppContext() != null) {
            RemoteAccessApiHandler.onServiceModeChanged();
        }
        if (wasRoadSensePaused != ServiceModeFeatureGate.isPaused("roadsense")) {
            com.overdrive.app.roadsense.RoadSenseController controller = CameraDaemon.getRoadSense();
            if (controller != null) controller.onServiceModeChanged();
        }
        if (wasWidgetsPaused != ServiceModeFeatureGate.isPaused("widgets")
                && CameraDaemon.getAppContext() != null) {
            Thread sync = new Thread(GlassWidgetsApiHandler::sync, "service-mode-widgets");
            sync.setDaemon(true);
            sync.start();
        }
        boolean pauseAutomations = active
                && contains(state.optJSONArray("disabledFeatures"), "automations");
        // Do not touch Automations on an ordinary daemon start. Its <clinit>
        // loads rules, starts pollers and dispatches the boot event. On a paused
        // start, stage the flag *before* that class can initialize.
        boolean wasPaused = ServiceModeAutomationGate.isPaused();
        ServiceModeAutomationGate.setPaused(pauseAutomations);
        if (pauseAutomations || wasPaused) {
            try {
                Automations.setServiceModeDisabled(pauseAutomations);
            } catch (Throwable error) {
                logger.warn("Could not refresh Service Mode feature gate: " + error.getMessage());
            }
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

    /**
     * Prefer the monotonic deadline during the boot in which the mode started.
     * The platform boot counter distinguishes a reboot even after the new boot's
     * elapsedRealtime has already passed the old start value. If unavailable or
     * changed, fall back to the wall deadline for cross-reboot continuity.
     */
    private static boolean isExpired(JSONObject state) {
        long wallDeadline = state.optLong("endsAtEpochMs", 0L);
        long elapsedStart = state.optLong("startedAtElapsedMs", -1L);
        long elapsedDeadline = state.optLong("endsAtElapsedMs", -1L);
        long elapsedNow = SystemClock.elapsedRealtime();
        long savedBootCount = state.optLong("bootCount", -1L);
        long currentBootCount = currentBootCount();
        if (savedBootCount >= 0L && currentBootCount == savedBootCount
                && elapsedStart >= 0L && elapsedDeadline >= elapsedStart) {
            return elapsedNow >= elapsedDeadline;
        }
        return wallDeadline <= System.currentTimeMillis();
    }

    private static long currentBootCount() {
        try {
            Context context = requireContext();
            return android.provider.Settings.Global.getInt(
                    context.getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT,
                    -1);
        } catch (Throwable ignored) {
            return -1L;
        }
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
            if (!"automations".equals(feature) && !"keymap".equals(feature)
                    && !"proximity".equals(feature) && !"roadsense".equals(feature)
                    && !"widgets".equals(feature) && !"seat_positions".equals(feature)) {
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

    private static String restoreCommand(String pkg, int previousState, int user) {
        String operation = previousState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                ? "default-state" : "enable";
        return "pm " + operation + " " + userFlag(user) + " " + shellQuote(pkg);
    }

    /** The user a session was started for; sessions saved before it was recorded use the current one. */
    static int targetUser(JSONObject state) {
        return state != null && state.has("targetUser") ? state.optInt("targetUser", -1)
                : com.overdrive.app.util.ShellUser.targetUserId();
    }

    static String userFlag(int user) {
        return user >= 0 ? "--user " + user : "";
    }

    private static boolean isEnabledState(int state) {
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private static boolean isPackageInstalled(String pkg, int user) {
        try {
            requireContext(user).getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        } catch (Throwable error) {
            logger.warn("Could not verify package installation for " + pkg + ": "
                    + error.getMessage());
            return true;
        }
    }

    private static int getPackageEnabledState(String pkg, int user) {
        try {
            return requireContext(user).getPackageManager().getApplicationEnabledSetting(pkg);
        } catch (IllegalArgumentException error) {
            return -1;
        } catch (Throwable error) {
            logger.warn("Could not read package state for " + pkg + ": " + error.getMessage());
            return -1;
        }
    }

    private static Context requireContext() {
        return requireContext(com.overdrive.app.util.ShellUser.targetUserId());
    }

    private static Context requireContext(int user) {
        Context context = com.overdrive.app.util.ShellUser.context(CameraDaemon.getAppContext(), user);
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

    /**
     * Recovery beyond the daemon: a detached shell process watches for this app
     * being uninstalled and re-enables the captured packages. The on-device
     * rescue reference copy is left in public Downloads until successful End.
     * Manual ADB recovery and the guardian must execute ONLY the private
     * shell-owned /data/local/tmp copy (never the editable Downloads file).
     * Never proceed with a NEW disable when this escrow cannot be written.
     *
     * This is best-effort: Android provides no guaranteed uninstall callback to
     * the app being removed and may kill shell processes on reboot/ADB reset.
     */
    private static String ensureRecoveryGuard(JSONObject state) {
        if (guardInitialized) return "";
        JSONArray originals = state.optJSONArray("originalStates");
        if (originals == null || originals.length() == 0) return "";
        StringBuilder recovery = new StringBuilder("#!/system/bin/sh\ncode=0\n");
        for (int i = 0; i < originals.length(); i++) {
            JSONObject saved = originals.optJSONObject(i);
            if (saved == null) continue;
            String pkg = saved.optString("package", "");
            int enabledState = saved.optInt("state", -1);
            if (!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
                    || APP_PACKAGE.equals(pkg) || !isEnabledState(enabledState)) continue;
            recovery.append(restoreCommand(pkg, enabledState, targetUser(state)))
                    .append(" || code=1\n")
                    .append("pm list packages " + userFlag(targetUser(state)) + " -d 2>/dev/null | grep -qx ")
                    .append(shellQuote("package:" + pkg)).append(" && code=1\n");
        }
        recovery.append("exit $code\n");
        // Atomic mkdir is available even on older Android builds without flock.
        // A live guardian owns this directory across daemon restarts. Retry a
        // newly created directory briefly before treating it as stale, so a
        // concurrent launcher cannot remove a lock before the owner writes pid.
        String guardian = "#!/system/bin/sh\n"
                + "umask 077\n"
                + "lock=" + shellQuote(guardLock()) + "\n"
                + "guard_alive() { " + GUARD_ALIVE_TEST + "; }\n"
                + "if ! mkdir \"$lock\" 2>/dev/null; then\n"
                + "  n=0\n"
                + "  while [ \"$n\" -lt 3 ]; do\n"
                + "    pid=$(cat \"$lock/pid\" 2>/dev/null)\n"
                + "    guard_alive \"$pid\" && exit 0\n"
                + "    sleep 1\n"
                + "    n=$((n + 1))\n"
                + "  done\n"
                + "  rm -f \"$lock/pid\"\n"
                + "  rmdir \"$lock\" 2>/dev/null || exit 0\n"
                + "  mkdir \"$lock\" 2>/dev/null || exit 0\n"
                + "fi\n"
                + "echo $$ > \"$lock/pid\" || exit 1\n"
                + "trap 'rm -f \"$lock/pid\"; rmdir \"$lock\" 2>/dev/null' EXIT\n"
                + "while [ -e " + shellQuote(guardMarker()) + " ]; do\n"
                + "  if ! pm path " + userFlag(targetUser(state)) + " " + APP_PACKAGE + " | grep -q '^package:'; then\n"
                + "    if sh " + shellQuote(privateRescueScript()) + "; then\n"
                + "      rm -f " + shellQuote(guardMarker()) + "\n"
                + "      exit 0\n"
                + "    fi\n"
                + "  fi\n"
                + "  sleep 30\n"
                + "done\n";
        String command = "umask 077; mkdir -p /sdcard/Download && "
                + "printf %s " + shellQuote(recovery.toString()) + " > "
                + shellQuote(RESCUE_SCRIPT + ".new") + " && "
                + "mv -f " + shellQuote(RESCUE_SCRIPT + ".new") + " "
                + shellQuote(RESCUE_SCRIPT) + " && "
                + "printf %s " + shellQuote(recovery.toString()) + " > "
                + shellQuote(privateRescueScript() + ".new") + " && "
                + "mv -f " + shellQuote(privateRescueScript() + ".new") + " "
                + shellQuote(privateRescueScript()) + " && "
                + "printf %s " + shellQuote(guardian) + " > "
                + shellQuote(guardScript() + ".new") + " && "
                + "mv -f " + shellQuote(guardScript() + ".new") + " "
                + shellQuote(guardScript()) + " && "
                + "touch " + shellQuote(guardMarker()) + " && "
                // A restarted daemon must not spawn a second live guardian.
                // The guardian's atomic mkdir also protects simultaneous starts
                // that both reach this check before either writes its pid.
                + "if guard_alive() { " + GUARD_ALIVE_TEST + "; }; "
                + "guard_alive \"$(cat " + shellQuote(guardLock() + "/pid") + " 2>/dev/null)\"; then :; else "
                + "(nohup sh " + shellQuote(guardScript())
                + " </dev/null >/dev/null 2>&1 & ); fi";
        ShellResult result = runShell(command);
        if (!result.success) return result.output.isEmpty() ? "shell recovery setup failed" : result.output;
        guardInitialized = true;
        return "";
    }

    private static boolean guardMarkerPresent() {
        return new java.io.File(guardMarker()).exists();
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
