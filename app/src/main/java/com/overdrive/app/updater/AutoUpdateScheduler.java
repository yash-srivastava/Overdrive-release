package com.overdrive.app.updater;

import android.content.Context;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.NetworkMonitor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Daemon-owned, opt-in Braveheart release checks with durable request intervals. */
public final class AutoUpdateScheduler {
    private static final DaemonLogger logger = DaemonLogger.getInstance("CameraDaemon");
    private static ScheduledExecutorService executor;
    private static final AtomicBoolean checking = new AtomicBoolean(false);
    private static volatile Context appContext;
    private static volatile String lastAdmissionRefusal;

    private AutoUpdateScheduler() {}

    public static synchronized void start(Context context) {
        if (context == null) {
            logger.warn("Auto-update scheduler not started: daemon context is null");
            return;
        }
        if (executor != null) return;
        Context application = context.getApplicationContext();
        final Context app = application != null ? application : context;
        appContext = app;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoUpdateCheck");
            t.setDaemon(true);
            return t;
        });
        // Probe on startup; persistent timing bounds requests to 6h/30m/backoff.
        // The five-minute local poll never forces a config reload when disabled.
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

    private static void refuse(String reason) {
        if (!reason.equals(lastAdmissionRefusal)) {
            lastAdmissionRefusal = reason;
            logger.info("Auto-update admission refused: " + reason);
        }
    }

    /** Shell UID 2000 cannot use per-UID default-network metering on Android 10. */
    static boolean unmetered() {
        if (appContext == null) {
            refuse("no daemon context/network");
            return false;
        }
        try {
            // NetworkMonitor's shell-side Wi-Fi state works even when the shell
            // has no observable Android default network. Check the outbound
            // route too: wlan0 may retain an IP after traffic moves to cellular.
            if (!NetworkMonitor.isAutoUpdateWifiRoute()) {
                refuse("Wi-Fi route unavailable or cellular network selected (UID 2000)");
                return false;
            }
            if (NetworkMonitor.isWifiMeteredHintKnown()) {
                if (NetworkMonitor.isWifiMeteredHint()) {
                    refuse("metered Wi-Fi hotspot");
                    return false;
                }
                return true;
            }
            // Some ROMs cannot expose the AP's metered hint to the shell.
            // A missing hint is not proof that a hotspot is unmetered. Accept
            // only when the API positively reports unmetered; if UID 2000 has
            // no per-UID active network, Android 10 returns metered here.
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && !cm.isActiveNetworkMetered()) return true;
            refuse("Wi-Fi metering unknown (shell UID 2000)");
            return false;
        } catch (Throwable t) {
            refuse("shell Wi-Fi network state unavailable (UID 2000)");
            logger.warn("Auto-update Wi-Fi admission unavailable: " + t);
            return false;
        }
    }

    /** Metadata queries may run while driving, but not with automatic updates off. */
    private static boolean mayQueryRelease() {
        try {
            // loadConfig() has an mtime-gated cache; do not force disk reads every poll.
            if (!UnifiedConfigManager.isAutoUpdateEnabled()) {
                refuse("automatic updates switched off");
                return false;
            }
            if (!AppUpdater.CHANNEL_BRAVEHEART.equals(UnifiedConfigManager.getUpdateChannel())) {
                refuse("not on Braveheart channel");
                return false;
            }
            if (CameraDaemon.isPublicMode()) {
                refuse("public mode");
                return false;
            }
            if (!unmetered()) return false;
            return true;
        } catch (Throwable t) {
            refuse("release admission unavailable");
            logger.warn("Auto-update release check admission unavailable: " + t);
            return false;
        }
    }

    /** Recheck before streaming and before any destructive handoff. */
    private static boolean mayInstall() {
        if (!mayQueryRelease()) return false;
        if (UnifiedConfigManager.isVehicleOnOnlyMode()) {
            refuse("Vehicle-ON-only mode shuts down when ACC turns off");
            return false;
        }
        if (!CameraDaemon.isSafeForAutomaticUpdate()) {
            refuse("ACC off for 30 minutes, recording idle and Sentry off required");
            return false;
        }
        lastAdmissionRefusal = null;
        return true;
    }

    private static void checkOnce(Context context) {
        if (!mayQueryRelease() || !AutoUpdateAttemptState.mayCheck()
                || !checking.compareAndSet(false, true)) return;
        logger.info("Auto-update: checking Braveheart release on unmetered network (UID "
                + android.os.Process.myUid() + ")");
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
                        String digest = updater.automaticAssetDigest();
                        if (digest.matches("sha256:[0-9a-f]{64}")) {
                            AutoUpdateAttemptState.reject(digest, "release label or digest not eligible");
                            AutoUpdateAttemptState.checkSucceeded(false);
                        } else {
                            AutoUpdateAttemptState.checkFailed();
                        }
                        logger.info("Auto-update: version or digest not eligible: " + remote);
                        finish(false);
                        return;
                    }
                    if (updater.wasAutomaticAssetAttempted()
                            || AutoUpdateAttemptState.isRejected(updater.automaticAssetDigest())) {
                        AutoUpdateAttemptState.checkSucceeded(false);
                        logger.info("Auto-update: asset already attempted or rejected; skipping " + remote);
                        finish(false);
                        return;
                    }
                    // Recheck in 30 minutes if parking or network blocks installation.
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
                        AutoUpdateAttemptState.checkFailed();
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
                                // Callback is on the main looper: ACC probing runs on the worker.
                                ScheduledExecutorService worker;
                                synchronized (AutoUpdateScheduler.class) { worker = executor; }
                                if (worker == null || worker.isShutdown()) { finish(true); return; }
                                try {
                                    worker.execute(() -> {
                                        boolean deferred = (error != null
                                                && error.startsWith("Automatic update deferred:"))
                                                || !mayInstall();
                                        logger.warn("Auto-update "
                                                + (deferred ? "deferred: " : "failed: ") + error);
                                        // Reserve only in AppUpdater at the irreversible handoff.
                                        if (!deferred) AutoUpdateAttemptState.checkFailed();
                                        finish(true);
                                    });
                                } catch (java.util.concurrent.RejectedExecutionException rejected) {
                                    finish(true);
                                }
                            }
                        }, AutoUpdateScheduler::mayInstall);
                    } catch (Throwable t) {
                        logger.error("Auto-update download could not start", t);
                        if (mayInstall()) AutoUpdateAttemptState.checkFailed();
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
