package com.overdrive.app.updater;

import android.content.Context;
import android.util.Log;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in, daemon-owned automatic update checks. Never auto-select an Alpha
 * archive version; Braveheart is the only channel with a rolling head.
 * The daemon may be stopped in onOnly parked mode, so checks are best-effort
 * and never keep a parked vehicle awake just to install an update.
 */
public final class AutoUpdateScheduler {
    private static final String TAG = "AutoUpdate";
    private static ScheduledExecutorService executor;
    private static final AtomicBoolean checking = new AtomicBoolean(false);
    private AutoUpdateScheduler() {}

    public static synchronized void start(Context context) {
        if (context == null || executor != null) return;
        Context application = context.getApplicationContext();
        final Context app = application != null ? application : context;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoUpdateCheck");
            t.setDaemon(true);
            return t;
        });
        // No check during boot stabilization. Only one network request every 6h.
        executor.scheduleWithFixedDelay(() -> {
            try { checkOnce(app); }
            catch (Throwable t) { Log.w(TAG, "Check failed", t); }
        }, 5, 360, TimeUnit.MINUTES);
    }

    public static synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    private static boolean allowed() {
        try {
            UnifiedConfigManager.forceReload();
            return UnifiedConfigManager.isAutoUpdateEnabled()
                    && AppUpdater.CHANNEL_BRAVEHEART.equals(UnifiedConfigManager.getUpdateChannel())
                    && !CameraDaemon.isPublicMode()
                    && CameraDaemon.isSafeForAutomaticUpdate();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to verify automatic update admission", t);
            return false;
        }
    }

    private static void checkOnce(Context context) {
        if (!allowed() || !checking.compareAndSet(false, true)) return;
        final AppUpdater updater;
        try { updater = new AppUpdater(context); }
        catch (Throwable t) {
            checking.set(false);
            Log.w(TAG, "Updater initialization failed", t);
            return;
        }
        final AtomicBoolean resolved = new AtomicBoolean(false);
        // Never leak a check instance when a callback is lost on network failure.
        ScheduledExecutorService owner;
        synchronized (AutoUpdateScheduler.class) { owner = executor; }
        if (owner != null && !owner.isShutdown()) {
            owner.schedule(() -> {
                if (resolved.compareAndSet(false, true)) {
                    Log.w(TAG, "Release check timed out");
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
                    if (!allowed() || !AppUpdater.tryBeginInstall()) {
                        finish(false);
                        return;
                    }
                    if (updater.getUpdateStorageError() != null) {
                        Log.w(TAG, "Not enough storage for automatic update");
                        finish(true);
                        return;
                    }
                    Log.i(TAG, "Installing available Braveheart update: " + remote);
                    try {
                        updater.downloadAndInstall(new AppUpdater.InstallCallback() {
                            @Override public void onProgress(String message) {
                                Log.i(TAG, message);
                            }
                            @Override public void onDownloadProgress(int percent) { }
                            @Override public void onSuccess() { finish(true); }
                            @Override public void onError(String error) {
                                Log.w(TAG, "Automatic update deferred/failed: " + error);
                                finish(true);
                            }
                        }, AutoUpdateScheduler::allowed);
                    } catch (Throwable t) {
                        Log.w(TAG, "Could not start automatic update", t);
                        finish(true);
                    }
                }
                @Override public void onNoUpdate(String current) {
                    if (resolved.compareAndSet(false, true)) finish(false);
                }
                @Override public void onError(String error) {
                    if (resolved.compareAndSet(false, true)) {
                        Log.w(TAG, "Release check failed: " + error);
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
            Log.w(TAG, "Failed to start release check", t);
        }
    }
}
