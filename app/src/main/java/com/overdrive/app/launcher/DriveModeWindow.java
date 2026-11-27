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
    /** Outcome of the last turn on/off, so a failed one keeps showing after a refresh. */
    private static volatile String lastToggleResult = "idle";

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
        String result;
        try {
            result = on ? DriveModeWindowToggle.enable(ENV) : DriveModeWindowToggle.disable(ENV);
        } catch (Throwable t) {
            logger.warn("setEnabled(" + on + ") failed: " + t.getMessage());
            result = "error";
        }
        lastToggleResult = result;
        lastResult = result;
        logger.info((on ? "enable" : "disable") + ": " + result);
        return status(result);
    }

    /** The daemon side of {@link DriveModeWindowToggle.Env}. */
    private static final DriveModeWindowToggle.Env ENV = new DriveModeWindowToggle.Env() {
        @Override public JSONObject section() {
            return UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
        }

        @Override public boolean writeSection(JSONObject patch) {
            return UnifiedConfigManager.updateSection(SECTION, patch);
        }

        @Override public String currentUserFlag() {
            return com.overdrive.app.util.ShellUser.flag();
        }

        @Override public String run(String command) {
            return ClusterFreeformWindow.runSettingsCmd(command);
        }

        @Override public DriveModeWindowToggle.WindowRestore restoreWindow() {
            final DriveModeWindowToggle.WindowRestore[] out = {DriveModeWindowToggle.WindowRestore.FAILED};
            Future f = runOnWorker(() -> out[0] = restoreFullscreen());
            if (!f.await()) return DriveModeWindowToggle.WindowRestore.TIMED_OUT;
            return out[0];
        }

        @Override public void afterEnable() {
            try {
                Worker.EXEC.execute(attempts::reset);
            } catch (Throwable ignored) {
            }
            schedule("enable", 0L);
        }

        @Override public void afterSettingsRestored() {
            ClusterFreeformWindow.forgetFreeformSettingCheck();
        }
    };

    /** Status for the settings screen. */
    public static JSONObject status(String result) {
        JSONObject out = new JSONObject();
        try {
            boolean platform = appliesHere();
            // A refresh (no result) reports whether the last turn on/off failed.
            boolean failed = DriveModeWindowToggle.isFailure(
                    result != null ? result : lastToggleResult);
            out.put("success", platform && !failed);
            out.put("failed", failed);
            out.put("platform", platform);
            out.put("enabled", isEnabled());
            out.put("result", result != null ? result : lastResult);
            out.put("lastResult", lastResult);
            out.put("lastToggleResult", lastToggleResult);
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
            // Only failures use up the budget; a confirmed float leaves it whole for next time.
            if (floating) attempts.succeeded(found.view.taskId);
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

    /** Put our task back to full screen if it is floating. Worker thread. */
    private static DriveModeWindowToggle.WindowRestore restoreFullscreen() {
        try {
            Object svc = atm();
            if (svc == null) return DriveModeWindowToggle.WindowRestore.FAILED;
            Found found = findOurTask(svc);
            if (found == null
                    || found.view.windowingMode != DriveModeWindowPolicy.WINDOWING_MODE_FREEFORM) {
                return DriveModeWindowToggle.WindowRestore.NOT_FLOATING;
            }
            boolean applied = applyWindowing(svc, found.info,
                    DriveModeWindowPolicy.WINDOWING_MODE_FULLSCREEN, new Rect());
            sleep(VERIFY_DELAY_MS);
            Found after = findOurTask(svc);
            boolean stillFloating = after != null && after.view.taskId == found.view.taskId
                    && after.view.windowingMode == DriveModeWindowPolicy.WINDOWING_MODE_FREEFORM;
            logger.info("task " + found.view.taskId + " back to full screen: applied=" + applied
                    + " stillFloating=" + stillFloating);
            return applied && !stillFloating
                    ? DriveModeWindowToggle.WindowRestore.RESTORED
                    : DriveModeWindowToggle.WindowRestore.FAILED;
        } catch (Throwable t) {
            logger.warn("restoreFullscreen failed: " + t.getMessage());
            return DriveModeWindowToggle.WindowRestore.FAILED;
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
            int taskId = intField(info, "taskId", -1);
            // Resolve through the root tasks (the raw field has reported 0 for a task on another
            // display); an unknown display is not ours.
            if (taskId < 0 || ClusterFreeformWindow.taskDisplayId(svc, info, taskId) != 0) continue;
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
                    taskId, windowingMode(info), visible, coveredByCar));
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

        /** True when the work finished within the bound. */
        boolean await() {
            try {
                return done.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
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
