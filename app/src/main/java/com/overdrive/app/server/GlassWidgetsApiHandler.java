package com.overdrive.app.server;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.config.ServiceModeFeatureGate;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Glass widgets overlay: lifecycle sync and the foreground probe behind "home screen only".
 * The overlay runs in the app process; the daemon (shell uid) launches it and can see which
 * app is on top, which the app process cannot.
 */
public final class GlassWidgetsApiHandler {

    private static final String TAG = "GlassWidgetsApi";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String SECTION = "glassWidgets";
    private static final String COMPONENT =
            "com.overdrive.app/com.overdrive.app.overlay.GlassWidgetsOverlayService";
    private static final String[] KNOWN_HOMES = { "com.byd.launcher", "com.overdrive.launcher" };
    private static final long HOME_CACHE_MS = 60_000L;

    // One thread keeps a stop and a following start in order at ActivityManager.
    private static final ExecutorService AM = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "glass-widgets-am");
        t.setDaemon(true);
        return t;
    });

    private static volatile String defaultHome;
    private static volatile long defaultHomeAt;

    private GlassWidgetsApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (p.equals("/api/widgets/sync") && method.equals("POST")) {
            boolean enabled = sync();
            HttpResponse.sendJson(out, new JSONObject().put("success", true).put("enabled", enabled).toString());
            return true;
        }
        if (p.equals("/api/widgets/foreground") && method.equals("GET")) {
            HttpResponse.sendJson(out, foreground().toString());
            return true;
        }
        if (p.equals("/api/widgets/live") && method.equals("GET")) {
            HttpResponse.sendJson(out, live(path.contains("dyn=1"), path.contains("drive=1")).toString());
            return true;
        }
        if (p.equals("/api/widgets/automations") && method.equals("GET")) {
            HttpResponse.sendJson(out, new JSONObject().put("success", true)
                    .put("automations", com.overdrive.app.automation.Automations.listForShortcuts()).toString());
            return true;
        }
        if (p.equals("/api/widgets/arrange") && method.equals("POST")) {
            Boolean enabled = arrange();
            HttpResponse.sendJson(out, new JSONObject().put("success", Boolean.TRUE.equals(enabled))
                    .put("enabled", enabled == null ? JSONObject.NULL : (Object) enabled).toString());
            return true;
        }
        return false;
    }

    /** One 10 Hz cluster-feed read interval plus jitter: while such a reader runs, its readings serve these. */
    private static final long SHARED_READ_MAX_AGE_MS = 150L;

    /**
     * One reading for the live widgets while they show, once a second (live drive) to four times
     * ([dynamics]: also the pedals and motor speeds). The blind-spot zones ride along, so they light
     * without waiting for the summary. [drive]: a custom widget reads power, so a DiLink 3/4 plug-in
     * hybrid's drive power is read too.
     */
    private static JSONObject live(boolean dynamics, boolean drive) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ts", System.currentTimeMillis());
        Object kmh = JSONObject.NULL;
        Object kw = JSONObject.NULL;
        Object driveKw = JSONObject.NULL;
        try {
            com.overdrive.app.byd.BydDataCollector c = com.overdrive.app.byd.BydDataCollector.getInstance();
            com.overdrive.app.byd.BydVehicleData vd = c.getData();
            // RoadSense's 250 ms poll, while it runs, is the freshest speed and pedals
            com.overdrive.app.byd.BydDataCollector.FastDynamics fd = dynamics ? c.getFastDynamics() : null;
            boolean fresh = fd != null && Math.abs(System.currentTimeMillis() - fd.timestamp) < 1000L;
            double speed = fresh && fd.speedKmh >= 0 ? fd.speedKmh
                    : dynamics ? c.readSpeedNowKmh(SHARED_READ_MAX_AGE_MS) : vd != null ? vd.speedKmh : Double.NaN;
            if (Double.isNaN(speed) && vd != null) speed = vd.speedKmh;
            if (!Double.isNaN(speed) && speed >= 0) kmh = Math.round(speed * 10.0) / 10.0;
            double power = c.readDrivePowerNowKw(SHARED_READ_MAX_AGE_MS);
            if (!Double.isNaN(power)) kw = Math.round(power * 10.0) / 10.0;
            double hybrid = drive ? c.readHybridDrivePowerNowKw(SHARED_READ_MAX_AGE_MS) : Double.NaN;
            if (!Double.isNaN(hybrid)) driveKw = Math.round(hybrid * 10.0) / 10.0;
            if (dynamics) {
                o.put("accelPct", pedal(fresh ? fd.accelPercent : c.readAccelNow()));
                o.put("brakePct", pedal(fresh ? fd.brakePercent : c.readBrakeNow(SHARED_READ_MAX_AGE_MS)));
                o.put("frontRpm", vd == null || vd.frontMotorSpeed == com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                        ? JSONObject.NULL : (Object) vd.frontMotorSpeed);
                o.put("rearRpm", vd == null || vd.rearMotorSpeed == com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                        ? JSONObject.NULL : (Object) vd.rearMotorSpeed);
            }
        } catch (Throwable ignored) {
            // unknown readings stay null; the widget draws a gap
        }
        o.put("speedKmh", kmh);
        o.put("packKw", kw);
        o.put("driveKw", driveKw);
        try {
            JSONObject bs = new JSONObject();
            bs.put("left", com.overdrive.app.automation.condition.BlindSpotEvent.widgetLevel(true));
            bs.put("right", com.overdrive.app.automation.condition.BlindSpotEvent.widgetLevel(false));
            bs.put("any", com.overdrive.app.automation.condition.BlindSpotEvent.widgetLevelAny());
            o.put("blindSpot", bs);
        } catch (Throwable ignored) { }
        return o;
    }

    private static Object pedal(int pct) {
        return pct >= 0 && pct <= 100 ? (Object) pct : JSONObject.NULL;
    }

    /** Opens the widgets' arrange mode on the head unit; false when the widgets are off, null when the config can't be read. */
    private static Boolean arrange() {
        Boolean enabled = null;
        try {
            JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
            enabled = cfg != null && cfg.optBoolean("enabled", false)
                    && !ServiceModeFeatureGate.isPaused("widgets");
        } catch (Throwable t) {
            logger.warn("config unavailable: " + t.getMessage());
        }
        if (Boolean.TRUE.equals(enabled)) exec("am", "start-foreground-service", "-n", COMPONENT, "--es", "action", "arrange");
        return enabled;
    }

    /** Start or stop the app-side overlay to match the saved master toggle. */
    public static boolean sync() {
        boolean enabled = false;
        try {
            JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
            enabled = cfg != null && cfg.optBoolean("enabled", false)
                    && !ServiceModeFeatureGate.isPaused("widgets");
        } catch (Throwable t) {
            logger.warn("config unavailable: " + t.getMessage());
        }
        if (enabled) exec("am", "start-foreground-service", "-n", COMPONENT);
        else exec("am", "stopservice", "-n", COMPONENT);
        // the power widget's blind-spot zones keep the radar poll running; re-check whether they still want it
        try { com.overdrive.app.automation.condition.BlindSpotEvent.refresh(); } catch (Throwable ignored) { }
        return enabled;
    }

    private static JSONObject foreground() throws Exception {
        String pkg = null;
        String cls = null;
        String base = null;
        Boolean homeTask = null;
        try {
            Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            Object svc = ctx == null ? null : ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (svc instanceof ActivityManager) {
                List<ActivityManager.RunningTaskInfo> tasks = ((ActivityManager) svc).getRunningTasks(8);
                // Android 9's tasks don't say their display, and it orders every display's tasks together
                // (a PiP tile or the cluster's app can come first): the main display's top stack says instead
                MainTop p = TASK_DISPLAY == null ? mainDisplayTop() : null;
                if (tasks != null) {
                    // the most recent task can be on another screen (the cluster); the widgets live on the main one
                    for (ActivityManager.RunningTaskInfo t : tasks) {
                        if (t == null || t.topActivity == null) continue;
                        if (p != null ? !p.top.equals(t.topActivity) : displayOf(t) != 0) continue;
                        pkg = t.topActivity.getPackageName();
                        cls = t.topActivity.getClassName();
                        base = t.baseActivity != null ? t.baseActivity.getClassName() : null;
                        // a task the HOME intent started is the launcher's; TaskInfo.baseIntent is API 29+
                        if (android.os.Build.VERSION.SDK_INT >= 29 && t.baseIntent != null) {
                            homeTask = t.baseIntent.hasCategory(Intent.CATEGORY_HOME);
                        }
                        break;
                    }
                }
                if (p != null) {
                    if (pkg == null) { pkg = p.top.getPackageName(); cls = p.top.getClassName(); }
                    if (homeTask == null) homeTask = p.home;
                }
            }
        } catch (Throwable ignored) {
            // A firmware that denies the task query reports "unknown", never "home".
        }
        JSONObject o = new JSONObject();
        o.put("pkg", pkg == null ? JSONObject.NULL : pkg);
        // The class matters inside a launcher: its app list is another activity of the same package,
        // and this query is live, where the overlay's accessibility signal only moves on an event.
        o.put("cls", cls == null ? JSONObject.NULL : cls);
        // the task's first activity: a home screen reached through a trampoline is its task's only one,
        // while the app list opens on top of the home screen
        o.put("base", base == null ? JSONObject.NULL : base);
        o.put("homeTask", homeTask == null ? JSONObject.NULL : (Object) homeTask);
        // an unknown top app is reported as unknown, so the overlay keeps showing rather than hiding for good
        o.put("home", pkg == null ? JSONObject.NULL : (Object) isHome(pkg));
        return o;
    }

    private static final class MainTop {
        final android.content.ComponentName top;
        final Boolean home;
        MainTop(android.content.ComponentName top, Boolean home) { this.top = top; this.home = home; }
    }

    /**
     * The top activity of display 0's topmost visible stack, and whether that stack is the home one, from
     * the platform's stack list (hidden; Android 9). Null when it can't be read: the task list stands alone.
     */
    private static MainTop mainDisplayTop() {
        try {
            Object am = ActivityManager.class.getMethod("getService").invoke(null);
            List<?> stacks = (List<?>) am.getClass().getMethod("getAllStackInfos").invoke(am);
            if (stacks == null) return null;
            // listed display by display, each display's stacks from the top down
            for (Object si : stacks) {
                Class<?> c = si.getClass();
                if (c.getField("displayId").getInt(si) != 0 || !c.getField("visible").getBoolean(si)) continue;
                android.content.ComponentName top = (android.content.ComponentName) c.getField("topActivity").get(si);
                if (top == null) continue;
                Boolean home = null;
                try {
                    Object conf = c.getField("configuration").get(si);
                    Object wc = conf.getClass().getField("windowConfiguration").get(conf);
                    // a picture-in-picture window always sits on top; the screen under it is what's showing
                    if (((Integer) wc.getClass().getMethod("getWindowingMode").invoke(wc)) == 2) continue;
                    // WindowConfiguration.ACTIVITY_TYPE_HOME
                    home = ((Integer) wc.getClass().getMethod("getActivityType").invoke(wc)) == 2;
                } catch (Throwable ignored) {
                    // no stack type: the overlay goes by the activity class
                }
                return new MainTop(top, home);
            }
        } catch (Throwable ignored) {
            // no stack list on this firmware
        }
        return null;
    }

    // TaskInfo.displayId is hidden before API 34; without it every task counts as the main screen's, as before
    private static final java.lang.reflect.Field TASK_DISPLAY = taskDisplayField();

    private static java.lang.reflect.Field taskDisplayField() {
        try {
            return ActivityManager.RunningTaskInfo.class.getField("displayId");
        } catch (Throwable t) {
            return null;
        }
    }

    private static int displayOf(ActivityManager.RunningTaskInfo t) {
        if (TASK_DISPLAY == null) return 0;
        try {
            return TASK_DISPLAY.getInt(t);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static boolean isHome(String pkg) {
        for (String h : KNOWN_HOMES) if (h.equals(pkg)) return true;
        String def = resolveDefaultHome();
        return def != null && def.equals(pkg);
    }

    private static String resolveDefaultHome() {
        long now = System.currentTimeMillis();
        if (defaultHome != null && now - defaultHomeAt < HOME_CACHE_MS) return defaultHome;
        try {
            Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (ctx == null) return defaultHome;
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            // With no default chosen this resolves to the system chooser ("android"), which is not a home.
            if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
                defaultHome = ri.activityInfo.packageName;
                defaultHomeAt = now;
            }
        } catch (Throwable ignored) {
            // keep the last known home
        }
        return defaultHome;
    }

    private static void exec(String... argv) {
        AM.execute(() -> {
            Process proc = null;
            try {
                proc = Runtime.getRuntime().exec(argv);
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    logger.warn("exec timed out: " + String.join(" ", argv));
                    proc.destroy();
                }
            } catch (Throwable t) {
                logger.warn("exec failed: " + String.join(" ", argv) + ": " + t.getMessage());
            } finally {
                if (proc != null) {
                    try { proc.getInputStream().close(); } catch (Throwable ignored) { }
                    try { proc.getErrorStream().close(); } catch (Throwable ignored) { }
                    try { proc.getOutputStream().close(); } catch (Throwable ignored) { }
                }
            }
        });
    }
}
