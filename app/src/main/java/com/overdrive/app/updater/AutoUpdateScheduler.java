package com.overdrive.app.updater;

import android.content.Context;
import android.net.ConnectivityManager;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in daemon-owned Braveheart checks. Metadata checks also run in Vehicle-ON-only
 * mode; installation still requires 30 minutes of confirmed ACC OFF and idle cameras.
 * Durable check timing prevents a restart from bypassing the request interval.
 */
public final class AutoUpdateScheduler {
    private static final DaemonLogger logger = DaemonLogger.getInstance("CameraDaemon");
    private static ScheduledExecutorService executor;
    private static final AtomicBoolean checking = new AtomicBoolean(false);
    private static volatile Context appContext;

    private AutoUpdateScheduler() {}

    public static synchronized void start(Context context) {
        if (context == null || executor != null) return;
        Context application = context.getApplicationContext();
        final Context app = application != null ? application : context;
        appContext = app;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoUpdateCheck");
            t.setDaemon(true);
            return t;
        });
        // Probe immediately, including in Vehicle-ON-only mode. A 5-minute
        // local poll allows the 30-minute ACC gate to become eligible promptly;
        // the persistent state bounds *network* requests to 6 hours after no
        // update, 30 minutes after an available but deferred update, and the
        // exponential backoff after failures.
        executor.scheduleWithFixedDelay(() -> {
            try { checkOnce(app); }
            catch (Throwable t) { logger.error("Auto-update check crashed", t); }
        }, 0, 5, TimeUnit.MINUTES);
    }

    public static synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
        appContext = null;
    }

    /**
     * UID 2000 (app_process) cannot call getNetworkCapabilities() on Android 11+:
     * the PackageManager rejects the attribution package "android". The
     * connectivity service's metered query needs no package attribution and
     * checks the active default network, including Wi-Fi marked as metered.
     * Unknown, missing, or inaccessible connectivity always fails closed.
     */
    static boolean unmetered() {
        Context ctx = appContext;
        if (ctx == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.getActiveNetwork() != null && !cm.isActiveNetworkMetered();
        } catch (Throwable t) {
            logger.warn("Auto-update network metering unavailable: " + t);
            return false;
        }
    }

    /** Release metadata is safe to request even with ACC ON and in onOnly mode. */
    private static boolean mayQueryRelease() {
        try {
            UnifiedConfigManager.forceReload();
            return UnifiedConfigManager.isAutoUpdateEnabled()
                    && AppUpdater.CHANNEL_BRAVEHEART.equals(UnifiedConfigManager.getUpdateChannel())
                    && !CameraDaemon.isPublicMode()
                    && unmetered();
        } catch (Throwable t) {
            logger.warn("Auto-update release check admission unavailable: " + t);
            return false;
        }
    }

    /** Called again while downloading and before the irreversible handoff. */
    private static boolean mayInstall() {
        return mayQueryRelease() && CameraDaemon.isSafeForAutomaticUpdate();
    }

    private static void checkOnce(Context context) {
        if (!AutoUpdateAttemptState.mayCheck() || !mayQueryRelease()
                || !checking.compareAndSet(false, true)) return;
        logger.info("Auto-update: checking Braveheart release (metadata only)");
        final AppUpdater updater;
        try { updater = new AppUpdater(context); }
        catch (Throwable t) {
            checking.set(false);
            logger.error("Auto-update initialization failed", t);
            return;
        }
        final AtomicBoolean resolved = new AtomicBoolean(false);
        ScheduledExecutorService owner;
        synchronized (AutoUpdateScheduler.class) { owner = executor; }
        if (owner != null && !owner.isShutdown()) {
            owner.schedule(() -> {
                if (resolved.compareAndSet(false, true)) {
                    logger.warn("Auto-update release check timed out");
                    AutoUpdateAttemptState.checkFailed();
                    updater.cancel();
                    updater.close();
                    checking.set(false);
                }
            }, 90, TimeUnit.SECONDS);
        }
        try {
            updater.checkForUpdate(new AppUpdater.UpdateCallback() {
                @Override public void onUpdateAvailable(String current, String remote, String notes) {
                    if (!resolved.compareAndSet(false, true)) return;
                    // Callback runs on the main looper. ACC/DiLink 5 probing
                    // is blocking and must occur on the daemon worker.
                    ScheduledExecutorService worker;
                    synchronized (AutoUpdateScheduler.class) { worker = executor; }
                    if (worker == null || worker.isShutdown()) { finish(false); return; }
                    try {
                        worker.execute(() -> installAvailable(remote));
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        finish(false);
                    }
                }
                private void installAvailable(String remote) {
                    if (!updater.isEligibleForAutomaticInstall()) {
                        AutoUpdateAttemptState.checkSucceeded(false);
                        logger.info("Auto-update: version or digest not eligible: " + remote);
                        finish(false);
                        return;
                    }
                    if (updater.wasAutomaticAssetAttempted()) {
                        AutoUpdateAttemptState.checkSucceeded(false);
                        logger.info("Auto-update: asset already attempted; skipping " + remote);
                        finish(false);
                        return;
                    }
                    // Eligible release: recheck after 30 minutes while parked
                    // gating is deferred (otherwise the normal interval is 6h).
                    AutoUpdateAttemptState.checkSucceeded(true);
                    if (!mayInstall()) {
                        logger.info("Auto-update: available " + remote
                                + " deferred (ACC, recording, Sentry or network admission)");
                        finish(false);
                        return;
                    }
                    if (!AppUpdater.tryBeginInstall()) {
                        logger.info("Auto-update: another install is in flight");
                        finish(false);
                        return;
                    }
                    if (updater.getUpdateStorageError() != null) {
                        logger.warn("Auto-update: insufficient storage");
                        finish(true);
                        return;
                    }
                    logger.info("Auto-update: downloading candidate " + remote);
                    try {
                        updater.downloadAndInstall(new AppUpdater.InstallCallback() {
                            @Override public void onProgress(String message) {
                                logger.info("Auto-update: " + message);
                            }
                            @Override public void onDownloadProgress(int percent) { }
                            @Override public void onSuccess() { finish(true); }
                            @Override public void onError(String error) {
                                boolean deferred = error != null
                                        && error.startsWith("Automatic update deferred:");
                                logger.warn("Auto-update " + (deferred ? "deferred: " : "failed: ") + error);
                                // Only a terminal failure for a known asset is
                                // durable; ACC/network changes must be retryable.
                                if (!deferred) {
                                    AutoUpdateAttemptState.reserve(updater.automaticAssetDigest());
                                }
                                finish(true);
                            }
                        }, AutoUpdateScheduler::mayInstall);
                    } catch (Throwable t) {
                        logger.error("Auto-update download could not start", t);
                        finish(true);
                    }
                }
                @Override public void onNoUpdate(String current) {
                    if (resolved.compareAndSet(false, true)) {
                        logger.info("Auto-update: no newer Braveheart asset");
                        AutoUpdateAttemptState.checkSucceeded(false);
                        finish(false);
                    }
                }
                @Override public void onError(String error) {
                    if (resolved.compareAndSet(false, true)) {
                        logger.warn("Auto-update release check failed: " + error);
                        AutoUpdateAttemptState.checkFailed();
                        finish(false);
                    }
                }
                private void finish(boolean ownsInstall) {
                    if (ownsInstall) AppUpdater.endInstall();
                    updater.close();
                    checking.set(false);
                }
            });
        } catch (Throwable t) {
            if (resolved.compareAndSet(false, true)) {
                updater.close();
                checking.set(false);
            }
            AutoUpdateAttemptState.checkFailed();
            logger.error("Auto-update release check could not start", t);
        }
    }
}
