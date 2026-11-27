package com.overdrive.app.launcher;

import android.graphics.Rect;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * BYD100F "drive mode window" (#1002), daemon side.
 *
 * <p>Out of P the car service covers OverDrive's full-screen activity with its driving
 * restriction screen. It only governs the display's full-screen task, so while this opt-in is on
 * the daemon moves OverDrive's own head-unit task on display 0 into a large floating window with
 * a {@code WindowContainerTransaction} (the Android 12+ way to change a live task's windowing
 * mode; the daemon's shell uid holds MANAGE_ACTIVITY_TASKS). The task is moved whenever it is on
 * screen full-screen, so it is normally already floating before the car leaves P instead of
 * racing the car service at the shift; a gear change re-checks in case it was not.
 *
 * <p>Turning the option on writes the freeform settings (they affect every app) and remembers
 * their previous values; turning it off moves the task back to full screen and restores them.
 * Every step is bounded and non-fatal: on any failure OverDrive just stays full screen.
 */
public final class DriveModeWindow {

    private static final String TAG = "DriveModeWindow";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    static final String SECTION = "driveModeWindow";
    private static final String PKG = "com.overdrive.app";
    private static final String MAIN_ACTIVITY = "com.overdrive.app.ui.MainActivity";
    private static final String RESTRICTION_PKG = "com.android.systemui";
    private static final String RESTRICTION_ACTIVITY_SUFFIX = ".car.activity.ActivityBlockingActivity";
    private static final String FEATURE_FREEFORM = "android.software.freeform_window_management";

    /** Re-checks after a gear change: the car's restriction screen lands a few hundred ms later. */
    private static final long[] GEAR_CHECK_DELAYS_MS = {300L, 1_500L};
    private static final long VERIFY_DELAY_MS = 400L;

    /** Created on first use, so cars where this never applies never start the thread. */
    private static final class Worker {
        static final ScheduledExecutorService EXEC =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "DriveModeWindow");
                    t.setDaemon(true);
                    return t;
                });
    }

    // Worker-thread state.
    private static final DriveModeWindowPolicy.Attempts attempts =
            new DriveModeWindowPolicy.Attempts();
    private static volatile String lastResult = "idle";

    private DriveModeWindow() {
    }

    /** True on BYD100F, the only platform this applies to. */
    public static boolean appliesHere() {
        try {
            return com.overdrive.app.camera.DiLink100Platform.isByd100f();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The opt-in, from the shared config. */
    public static boolean isEnabled() {
        try {
            JSONObject section = UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
            return section != null && section.optBoolean("enabled", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether this feature currently owns the freeform settings (the cluster cast must not undo them). */
    static boolean holdsFreeformSupport() {
        return appliesHere() && isEnabled();
    }

    /** Gear edge from the daemon's gear monitor. */
    public static void onGear(int gear) {
        if (!appliesHere() || !isEnabled()) return;
        for (long delay : GEAR_CHECK_DELAYS_MS) schedule("gear", delay);
    }

    /** OverDrive's main screen came to the front. */
    public static void onAppResumed() {
        if (!appliesHere() || !isEnabled()) return;
        schedule("resume", 0L);
    }

    private static void schedule(String why, long delayMs) {
        try {
            Worker.EXEC.schedule(() -> evaluate(why), delayMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            logger.warn("could not schedule a check (" + why + "): " + t.getMessage());
        }
    }

    // ── Enable / disable ────────────────────────────────────────────────────────

    /** Turn the option on or off. Runs the settings work on the caller's (HTTP worker) thread. */
    public static synchronized JSONObject setEnabled(boolean on) {
        if (!appliesHere()) return status("not_this_platform");
        try {
            if (on) {
                JSONObject backup = currentBackup();
                if (backup == null) {
                    backup = new JSONObject();
                    for (String[] s : DriveModeWindowPolicy.SETTINGS) {
                        String prior = readSetting(s[0], s[1]);
                        // A failed read comes back empty; only a printed "null" means missing.
                        if (!DriveModeWindowPolicy.isReadable(prior)
                                || !DriveModeWindowPolicy.isRestorableValue(prior)) {
                            logger.warn("refusing to enable: " + s[0] + " " + s[1]
                                    + " has an unexpected value");
                            return status("setting_unreadable");
                        }
                        backup.put(s[0] + "/" + s[1], prior == null ? "null" : prior.trim());
                    }
                }
                // Record the previous values before changing anything, so they can be restored.
                if (!UnifiedConfigManager.updateSection(SECTION, new JSONObject()
                        .put("enabled", true).put("backup", backup))) {
                    return status("config_write_failed");
                }
                boolean allOn = true;
                for (String[] s : DriveModeWindowPolicy.SETTINGS) {
                    allOn &= DriveModeWindowPolicy.isOn(writeSetting(s[0], s[1], "1"));
                }
                lastResult = allOn ? "enabled" : "settings_unconfirmed";
                logger.info("enabled (settings confirmed=" + allOn + ")");
                Worker.EXEC.execute(attempts::reset);
                schedule("enable", 0L);
                return status(lastResult);
            }
            JSONObject backup = currentBackup();
            Future fullscreen = runOnWorker(DriveModeWindow::restoreFullscreen);
            fullscreen.await();
            boolean restored = true;
            if (backup != null) {
                for (String[] s : DriveModeWindowPolicy.SETTINGS) {
                    String prior = backup.optString(s[0] + "/" + s[1], "null");
                    if (!DriveModeWindowPolicy.isRestorableValue(prior)) continue;
                    String cmd = DriveModeWindowPolicy.restoreCommand(
                            s[0], s[1], prior, com.overdrive.app.util.ShellUser.flag());
                    String readback = ClusterFreeformWindow.runSettingsCmd(
                            cmd + " && " + getCommand(s[0], s[1]));
                    boolean ok = DriveModeWindowPolicy.restoreConfirmed(prior, readback);
                    restored &= ok;
                }
            }
            UnifiedConfigManager.updateSection(SECTION, new JSONObject()
                    .put("enabled", false).put("backup", restored ? JSONObject.NULL : backup));
            lastResult = restored ? "disabled" : "restore_unconfirmed";
            logger.info("disabled (settings restored=" + restored + ")");
            return status(lastResult);
        } catch (Throwable t) {
            logger.warn("setEnabled(" + on + ") failed: " + t.getMessage());
            return status("error");
        }
    }

    private static JSONObject currentBackup() {
        JSONObject section = UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
        return section == null ? null : section.optJSONObject("backup");
    }

    /** Status for the settings screen. */
    public static JSONObject status(String result) {
        JSONObject out = new JSONObject();
        try {
            boolean platform = appliesHere();
            out.put("success", platform);
            out.put("platform", platform);
            out.put("enabled", isEnabled());
            out.put("result", result != null ? result : lastResult);
            out.put("lastResult", lastResult);
            if (platform) {
                String freeform = readSetting("global", "enable_freeform_support");
                out.put("freeformAvailable",
                        DriveModeWindowPolicy.freeformAvailable(hasFreeformFeature(), freeform));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ── Worker: find our task and float it ─────────────────────────────────────

    private static void evaluate(String why) {
        try {
            if (!appliesHere() || !isEnabled()) return;
            Object svc = atm();
            if (svc == null) {
                lastResult = "unsupported";
                return;
            }
            Found found = findOurTask(svc);
            long now = android.os.SystemClock.elapsedRealtime();
            if (!DriveModeWindowPolicy.shouldConvert(true, true,
                    found == null ? null : found.view, attempts, now)) {
                return;
            }
            Rect full = taskBounds(found.info);
            int[] b = full == null ? null
                    : DriveModeWindowPolicy.floatingBounds(full.left, full.top, full.right, full.bottom);
            if (b == null) {
                lastResult = "no_bounds";
                return;
            }
            attempts.note(found.view.taskId, now);
            Rect bounds = new Rect(b[0], b[1], b[2], b[3]);
            boolean applied = applyWindowing(svc, found.info,
                    DriveModeWindowPolicy.WINDOWING_MODE_FREEFORM, bounds);
            sleep(VERIFY_DELAY_MS);
            Found after = findOurTask(svc);
            boolean floating = after != null && after.view.taskId == found.view.taskId
                    && after.view.windowingMode == DriveModeWindowPolicy.WINDOWING_MODE_FREEFORM;
            lastResult = floating ? "floating" : applied ? "not_honoured" : "apply_failed";
            logger.info("task " + found.view.taskId + " (" + why + ", covered="
                    + found.view.coveredByCar + ") → floating window " + bounds.toShortString()
                    + ": " + lastResult + " (attempt "
                    + attempts.countFor(found.view.taskId) + "/"
                    + DriveModeWindowPolicy.MAX_ATTEMPTS_PER_TASK + ")");
        } catch (Throwable t) {
            lastResult = "error";
            logger.warn("evaluate(" + why + ") failed: " + t.getMessage());
        }
    }

    /** Put our task back to full screen if we floated it. Worker thread. */
    private static void restoreFullscreen() {
        try {
            Object svc = atm();
            if (svc == null) return;
            Found found = findOurTask(svc);
            if (found == null
                    || found.view.windowingMode != DriveModeWindowPolicy.WINDOWING_MODE_FREEFORM) {
                return;
            }
            boolean ok = applyWindowing(svc, found.info,
                    DriveModeWindowPolicy.WINDOWING_MODE_FULLSCREEN, new Rect());
            logger.info("task " + found.view.taskId + " back to full screen: " + ok);
        } catch (Throwable t) {
            logger.warn("restoreFullscreen failed: " + t.getMessage());
        } finally {
            attempts.reset();
        }
    }

    private static final class Found {
        final Object info;
        final DriveModeWindowPolicy.TaskView view;

        Found(Object info, DriveModeWindowPolicy.TaskView view) {
            this.info = info;
            this.view = view;
        }
    }

    /** OverDrive's head-unit task on display 0 (the one whose root is MainActivity). */
    private static Found findOurTask(Object svc) throws Exception {
        ClusterFreeformWindow.RunningTaskInventory inventory =
                ClusterFreeformWindow.queryRunningTasks(svc);
        if (inventory == null || inventory.tasks == null) return null;
        boolean coveredByCar = false;
        boolean seenTopVisible = false;
        for (Object info : inventory.tasks) {
            if (intField(info, "displayId", 0) != 0) continue;
            boolean visible = boolField(info, "isVisible", true);
            String top = componentClass(info, "topActivity");
            String topPkg = componentPackage(info, "topActivity");
            if (visible && !seenTopVisible) {
                seenTopVisible = true;
                coveredByCar = RESTRICTION_PKG.equals(topPkg)
                        && top != null && top.endsWith(RESTRICTION_ACTIVITY_SUFFIX);
            }
            if (!PKG.equals(componentPackage(info, "baseActivity"))
                    || !MAIN_ACTIVITY.equals(componentClass(info, "baseActivity"))) {
                continue;
            }
            return new Found(info, new DriveModeWindowPolicy.TaskView(
                    intField(info, "taskId", -1), windowingMode(info), visible, coveredByCar));
        }
        return null;
    }

    /** Apply a windowing mode and bounds to the task through the window organizer. */
    private static boolean applyWindowing(Object svc, Object info, int mode, Rect bounds) {
        try {
            Field tokenField = info.getClass().getField("token");
            Object token = tokenField.get(info);
            if (token == null) return false;
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Object wct = wctClass.getConstructor().newInstance();
            wctClass.getMethod("setWindowingMode", tokenClass, int.class).invoke(wct, token, mode);
            wctClass.getMethod("setBounds", tokenClass, Rect.class).invoke(wct, token, bounds);
            Object controller = svc.getClass().getMethod("getWindowOrganizerController").invoke(svc);
            if (controller == null) return false;
            for (Method m : controller.getClass().getMethods()) {
                if ("applyTransaction".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    m.invoke(controller, wct);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            logger.warn("window organizer refused mode " + mode + ": " + cause);
            return false;
        }
    }

    // ── Small reflection and shell helpers ─────────────────────────────────────

    private static Object atm() {
        try {
            return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int windowingMode(Object info) {
        try {
            Method m = info.getClass().getMethod("getWindowingMode");
            return (Integer) m.invoke(info);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Rect taskBounds(Object info) {
        try {
            Object config = info.getClass().getField("configuration").get(info);
            Object window = config.getClass().getField("windowConfiguration").get(config);
            Object bounds = window.getClass().getMethod("getBounds").invoke(window);
            return bounds instanceof Rect ? new Rect((Rect) bounds) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intField(Object o, String name, int fallback) {
        try {
            return o.getClass().getField(name).getInt(o);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean boolField(Object o, String name, boolean fallback) {
        try {
            return o.getClass().getField(name).getBoolean(o);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static android.content.ComponentName component(Object o, String name) {
        try {
            Object v = o.getClass().getField(name).get(o);
            return v instanceof android.content.ComponentName ? (android.content.ComponentName) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String componentClass(Object o, String name) {
        android.content.ComponentName c = component(o, name);
        return c == null ? null : c.getClassName();
    }

    private static String componentPackage(Object o, String name) {
        android.content.ComponentName c = component(o, name);
        return c == null ? null : c.getPackageName();
    }

    private static String getCommand(String namespace, String key) {
        return DriveModeWindowPolicy.settingsCommand(
                "get", namespace, key, null, com.overdrive.app.util.ShellUser.flag());
    }

    private static String readSetting(String namespace, String key) {
        return ClusterFreeformWindow.runSettingsCmd(getCommand(namespace, key));
    }

    private static String writeSetting(String namespace, String key, String value) {
        return ClusterFreeformWindow.runSettingsCmd(DriveModeWindowPolicy.settingsCommand(
                "put", namespace, key, value, com.overdrive.app.util.ShellUser.flag())
                + " && " + getCommand(namespace, key));
    }

    private static boolean hasFreeformFeature() {
        try {
            android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            return ctx != null && ctx.getPackageManager().hasSystemFeature(FEATURE_FREEFORM);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Minimal wait handle for work handed to the worker thread. */
    private static final class Future {
        private final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

        void await() {
            try {
                done.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Future runOnWorker(Runnable task) {
        Future f = new Future();
        try {
            Worker.EXEC.execute(() -> {
                try {
                    task.run();
                } finally {
                    f.done.countDown();
                }
            });
        } catch (Throwable t) {
            f.done.countDown();
        }
        return f;
    }
}
