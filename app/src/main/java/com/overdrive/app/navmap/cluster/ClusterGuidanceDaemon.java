package com.overdrive.app.navmap.cluster;

import android.os.Build;
import android.os.SystemClock;

import com.overdrive.app.camera.dilink5.ClusterTransport;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.monitor.AccMonitor;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the guidance session independently of cast displays. At most one pending frame is
 * retained, and source-owned clears cannot cancel another navigator's active session.
 */
public final class ClusterGuidanceDaemon {
    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ClusterGuidance"); thread.setDaemon(true); return thread;
    });
    private static final ClusterGuidanceVendorWriter WRITER = new ClusterGuidanceVendorWriter();
    private static final ClusterGuidanceSession SESSION = new ClusterGuidanceSession();
    private static ClusterGuidanceFrame pending;
    private static long revision, accGeneration;
    private static boolean scheduled, needsClear, written, writtenHud, accOff;
    private static boolean stripClearDeferred, needsStripClear;
    private static String state = "idle";
    private static JSONObject delivery = new JSONObject();
    private static volatile boolean watchdogStarted;
    private static final ClusterGuidanceDi5Writer DI5_WRITER = new ClusterGuidanceDi5Writer();
    private static boolean writtenDi5, di5RestoreQueued;
    private static int di5AdoptAttempts;

    private ClusterGuidanceDaemon() {}

    /**
     * Called only from the owning daemon's entry points: other daemons load this class for its
     * static helpers and must never restore. Queued ahead of any drain.
     */
    private static void queueDi5RestoreLocked() {
        if (di5RestoreQueued) return;
        di5RestoreQueued = true;
        WORKER.execute(ClusterGuidanceDaemon::adoptDi5Restore);
    }

    private static void adoptDi5Restore() {
        // A stray marker on another platform (e.g. an imported backup) must not take DI3 clears.
        if (!ClusterTransport.isDiLink5()) return;
        int adopted = DI5_WRITER.adoptPendingRestore();
        if (adopted < 0) {
            long delay = Math.min(600L, 30L << Math.min(5, di5AdoptAttempts++));
            WORKER.schedule(ClusterGuidanceDaemon::adoptDi5Restore, delay, TimeUnit.SECONDS);
        }
        if (adopted <= 0) return;
        synchronized (LOCK) {
            writtenDi5 = true;
            stripClearDeferred = true;
            if (!watchdogStarted) {
                watchdogStarted = true;
                WORKER.scheduleWithFixedDelay(ClusterGuidanceDaemon::expire, 5, 5, TimeUnit.SECONDS);
            }
        }
    }

    public static boolean isDi3Identity(String identity) {
        String value = identity == null ? "" : identity.toLowerCase(Locale.ROOT);
        return !value.contains("dilink5") && !value.contains("dilink50")
                && !value.contains("dilink4") && !value.contains("dilink 4")
                && !value.contains("dilink6") && !value.contains("dilink 6")
                && (value.contains("dilink3") || value.contains("dilink 3") || value.contains("6125"));
    }

    static boolean di3() {
        if (ClusterTransport.isDiLink5()) return false;
        String firmware = "";
        try {
            firmware = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "apps.setting.product.inswver", "");
        } catch (Throwable ignored) {}
        return isDi3Identity(Build.PRODUCT + " " + Build.DEVICE + " " + Build.FINGERPRINT + " " + firmware);
    }

    private static JSONObject settings() {
        return UnifiedConfigManager.loadConfig().optJSONObject("projection");
    }

    public static boolean enabledForPlatform() {
        JSONObject settings = settings();
        if (settings == null) return false;
        return ClusterTransport.isDiLink5()
                ? settings.optBoolean("clusterGuidanceDi5StripEnabled", false)
                : di3() && settings.optBoolean("clusterGuidanceEnabled", false);
    }

    public static boolean submit(ClusterGuidanceFrame frame, long sequence) {
        if (!enabledForPlatform()) return false;
        synchronized (LOCK) {
            queueDi5RestoreLocked();
            if (accOff || !AccMonitor.isAccStateAuthoritative() || !AccMonitor.isAccOn()) return false;
            if (!SESSION.submit(frame.source, sequence, SystemClock.elapsedRealtime())) return false;
            pending = frame;
            revision++;
            state = "queued";
            scheduleLocked();
            if (!watchdogStarted) {
                watchdogStarted = true;
                WORKER.scheduleWithFixedDelay(ClusterGuidanceDaemon::expire, 5, 5, TimeUnit.SECONDS);
            }
            return true;
        }
    }

    public static void clear(String source, long sequence) {
        synchronized (LOCK) {
            queueDi5RestoreLocked();
            if (!SESSION.clear(source, sequence)) return;
            clearLocked();
        }
    }

    public static void settingsChanged() {
        synchronized (LOCK) {
            queueDi5RestoreLocked();
            clearLocked();
        }
    }

    /** Called on an admitted ACC transition; no CAN/IPC call blocks the transition thread. */
    public static void onAccStateChanged(boolean off, long generation) {
        synchronized (LOCK) {
            queueDi5RestoreLocked();
            if (generation < accGeneration) return;
            accGeneration = generation;
            accOff = off;
            if (off) {
                clearLocked();
                WORKER.execute(DI5_WRITER::newDrive);
            }
        }
    }

    /** A periodic task that throws is cancelled for good, so nothing may escape. */
    private static void expire() {
        try {
            expireOnce();
        } catch (Throwable ignored) {}
    }

    private static void expireOnce() {
        boolean projectionActive = ClusterGuidanceVendorWriter.projectionOwnsCluster();
        synchronized (LOCK) {
            if (!SESSION.source().isEmpty() && (!enabledForPlatform()
                    || SESSION.expired(SystemClock.elapsedRealtime()))) clearLocked();
            if (stripClearDeferred && SESSION.source().isEmpty() && !projectionActive) {
                needsStripClear = true;
                scheduleLocked();
            }
        }
    }

    private static void clearLocked() {
        SESSION.reset();
        pending = null;
        revision++;
        needsClear |= written;
        state = needsClear ? "clearing" : "idle";
        scheduleLocked();
    }

    private static void scheduleLocked() {
        if (pending == null && !needsClear && !needsStripClear) return;
        if (scheduled) return;
        scheduled = true;
        WORKER.execute(ClusterGuidanceDaemon::drain);
    }

    private static void drain() {
        while (true) {
            ClusterGuidanceFrame frame;
            long expected;
            boolean clear, hud, di5;
            synchronized (LOCK) {
                clear = needsClear || needsStripClear;
                frame = clear ? null : pending;
                if (!clear) pending = null;
                expected = revision;
                hud = clear ? needsClear && writtenHud && di3() : di3();
                // A clear follows what was written, even if the platform changed since.
                di5 = clear ? writtenDi5 : ClusterTransport.isDiLink5();
                needsClear = false;
                needsStripClear = false;
                if (!clear && frame == null) { scheduled = false; return; }
                if (!clear) { written = true; writtenHud = hud; }
                if (!clear && di5) writtenDi5 = true;
            }
            try {
                ClusterGuidanceVendorWriter.Result result = di5
                        ? (clear ? DI5_WRITER.clear() : DI5_WRITER.write(frame,
                                () -> isCurrent(expected) && ClusterTransport.isDiLink5()))
                        : clear ? WRITER.clear(hud)
                        : WRITER.write(frame, hud, () -> isCurrent(expected) && (!hud || di3()));
                JSONObject next = new JSONObject();
                next.put("cluster", result.cluster);
                next.put(di5 ? "steps" : "can", new JSONObject(result.registers));
                next.put("physicalRenderingVerified", false);
                synchronized (LOCK) {
                    delivery = next;
                    stripClearDeferred = clear && result.stripClearDeferred;
                    if (clear) written = false;
                    if (clear && di5 && !result.stripClearDeferred) writtenDi5 = false;
                    if (revision == expected) state = clear ? "idle" : "submitted";
                }
            } catch (Throwable error) {
                synchronized (LOCK) {
                    state = "failed";
                    try { delivery = new JSONObject().put("error", error.getClass().getSimpleName()); }
                    catch (Exception ignored) {}
                }
            }
        }
    }

    private static boolean isCurrent(long expected) {
        if (!enabledForPlatform()) return false;
        synchronized (LOCK) {
            return revision == expected && !accOff
                    && AccMonitor.isAccStateAuthoritative() && AccMonitor.isAccOn();
        }
    }

    public static JSONObject status() throws Exception {
        JSONObject settings = settings();
        boolean di5 = ClusterTransport.isDiLink5(), di3 = di3();
        synchronized (LOCK) {
            return new JSONObject()
                    .put("success", true)
                    .put("enabled", settings != null && settings.optBoolean("clusterGuidanceEnabled", false))
                    .put("di5StripEnabled", settings != null && settings.optBoolean("clusterGuidanceDi5StripEnabled", false))
                    .put("platformSupported", di3 || di5)
                    .put("windshieldHudSupported", di3)
                    .put("di5StripExperimental", di5)
                    .put("capability", di5 ? "experimental_cluster_service"
                            : di3 ? "di3_cluster_and_hud_unverified" : "unsupported_or_unknown_platform")
                    .put("di5", DI5_WRITER.status())
                    .put("state", state).put("source", SESSION.source())
                    .put("ageMs", SESSION.source().isEmpty() ? -1 : SystemClock.elapsedRealtime() - SESSION.lastUpdateMs())
                    .put("delivery", new JSONObject(delivery.toString()));
        }
    }
}
