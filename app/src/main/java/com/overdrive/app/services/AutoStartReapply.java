package com.overdrive.app.services;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.overdrive.app.auth.PinSession;
import com.overdrive.app.byd.routing.DrivingSafetyGuard;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.overlay.SetupGuideDialog;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in: re-applies BYD's "Disable Auto-Start" switch by itself after an install or update.
 *
 * <p>BYD may reset the switch on install, and only its own dialog can change it
 * ({@code ACCESS_APPOPSDATA} is signature-only), so this drives {@link AutoStartEnabler} after install and at two
 * delayed checkpoints: screen on, parked, bounded attempts per phase, then the setup guide
 * takes over again.
 */
public final class AutoStartReapply {

    private static final String TAG = "AutostartReapply";
    private static final String PREFS = "overdrive_setup";
    private static final String KEY_ENABLED = "autostart_reapply_enabled";
    private static final String KEY_DONE_INSTALL = "autostart_reapply_done_install";
    private static final String KEY_FIRST_CONFIRM_INSTALL = "autostart_first_confirm_install";
    private static final String KEY_FIRST_CONFIRM_AT = "autostart_first_confirm_at";
    private static final String KEY_RECHECK_DONE_INSTALL = "autostart_recheck_done_install";
    private static final String KEY_FINAL_CHECK_INSTALL = "autostart_final_check_install";
    private static final String KEY_ATTEMPT_INSTALL = "autostart_reapply_attempt_install";
    private static final String KEY_ATTEMPTS = "autostart_reapply_attempts";

    private static final long FIRST_CHECK_DELAY_MS = 20_000L;
    private static final long RECHECK_MS = 60_000L;
    private static final long GATE_RECHECK_MS = 5 * 60_000L;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MIN_ATTEMPT_SPACING_MS = 120_000L;
    private static final long PIN_RETURN_GRACE_MS = 60_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static final Runnable CHECK = AutoStartReapply::check;

    // Main thread only.
    private static long lastAttemptElapsed = -MIN_ATTEMPT_SPACING_MS;
    private static volatile Boolean available;

    private AutoStartReapply() {
    }

    /** BYD's dialog exists here and the DiLink 5 daemon path is not the one in use. */
    public static boolean isAvailable(Context context) {
        if (DiLink5Platform.isSelected()) return false;
        Boolean cached = available;
        if (cached != null) return cached;
        boolean found;
        try {
            found = context.getPackageManager().resolveActivity(
                    SetupGuideDialog.buildAppStartManagementIntent(), 0) != null;
        } catch (Throwable t) {
            return false;
        }
        available = found;
        return found;
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        Log.i(TAG, "re-apply after updates " + (enabled ? "enabled" : "disabled"));
        MAIN.removeCallbacks(CHECK);
        if (enabled) schedule(FIRST_CHECK_DELAY_MS);
    }

    /** Opted in and able to act on this install: the service is enabled and it hasn't given up. */
    public static boolean willHandleCurrentInstall(Context context) {
        if (!isEnabled(context) || !isAvailable(context)) return false;
        long install = currentInstall(context);
        if (install <= 0L || attemptsFor(context, install) >= MAX_ATTEMPTS) return false;
        return KeepAliveAccessibilityService.isRunning() || isServiceEnabled(context);
    }

    /** The switch was confirmed off for this install (by the guide's button or by this class). */
    public static boolean isDoneForCurrentInstall(Context context) {
        long install = currentInstall(context);
        return install > 0L && prefs(context).getLong(KEY_DONE_INSTALL, 0L) == install;
    }

    public static void markDoneForCurrentInstall(Context context) {
        long install = currentInstall(context);
        if (install <= 0L) return;
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor edit = p.edit().putLong(KEY_DONE_INSTALL, install);
        if (p.getLong(KEY_FIRST_CONFIRM_INSTALL, 0L) != install) {
            // A successful first observation starts a separate retry budget for the follow-up.
            edit.putLong(KEY_FIRST_CONFIRM_INSTALL, install)
                    .putLong(KEY_FIRST_CONFIRM_AT, System.currentTimeMillis())
                    .putLong(KEY_ATTEMPT_INSTALL, install)
                    .putInt(KEY_ATTEMPTS, 0);
        } else if (followUpDelay(p, install) == 0L) {
            edit.putLong(KEY_RECHECK_DONE_INSTALL, install)
                    .putLong(KEY_ATTEMPT_INSTALL, install)
                    .putInt(KEY_ATTEMPTS, 0);
            if (AutoStartCheckpointPolicy.completesFinalCheckpoint(
                    p.getLong(KEY_FIRST_CONFIRM_AT, 0L), System.currentTimeMillis(),
                    p.getLong(KEY_RECHECK_DONE_INSTALL, 0L) == install)) {
                edit.putLong(KEY_FINAL_CHECK_INSTALL, install);
            }
        }
        edit.apply();
        schedule(FIRST_CHECK_DELAY_MS);
    }

    /** Called on the main thread once the accessibility service is wired. */
    static void onServiceConnected(KeepAliveAccessibilityService service) {
        schedule(FIRST_CHECK_DELAY_MS);
    }

    static void onServiceDisconnected() {
        MAIN.removeCallbacks(CHECK);
    }

    private static boolean isPending(Context context) {
        if (!isEnabled(context) || !isAvailable(context)) return false;
        long install = currentInstall(context);
        if (install <= 0L || attemptsFor(context, install) >= MAX_ATTEMPTS) return false;
        return prefs(context).getLong(KEY_FINAL_CHECK_INSTALL, 0L) != install;
    }

    private static void check() {
        final KeepAliveAccessibilityService service = KeepAliveAccessibilityService.getInstance();
        if (service == null || !isPending(service)) return;
        final long install = currentInstall(service);
        long delay = followUpDelay(prefs(service), install);
        if (delay > 0L) {
            schedule(Math.min(delay, GATE_RECHECK_MS));
            return;
        }
        if (SystemClock.elapsedRealtime() - lastAttemptElapsed < MIN_ATTEMPT_SPACING_MS
                || !IN_FLIGHT.compareAndSet(false, true)) {
            schedule(RECHECK_MS);
            return;
        }
        new Thread(() -> {
            String blocked = gateReason(service);
            MAIN.post(() -> {
                if (KeepAliveAccessibilityService.getInstance() != service || !isPending(service)
                        || currentInstall(service) != install
                        || blocked != null || !isScreenOn(service)
                        || followUpDelay(prefs(service), install) > 0L) {
                    IN_FLIGHT.set(false);
                    if (blocked != null) Log.i(TAG, "deferred: " + blocked);
                    schedule(GATE_RECHECK_MS);
                    return;
                }
                launch(service);
            });
        }, "autostart-reapply-gate").start();
    }

    /** Null when BYD's dialog may open now; reads the daemon, so off the main thread. */
    private static String gateReason(Context context) {
        if (!isScreenOn(context)) return "screen off or unknown";
        return DrivingSafetyGuard.isMovementBlockedViaDaemon()
                ? "not parked (or vehicle state unavailable)" : null;
    }

    private static boolean isScreenOn(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void launch(KeepAliveAccessibilityService service) {
        final Context context = service.getApplicationContext();
        final long install = currentInstall(context);
        final int attempt = attemptsFor(context, install) + 1;
        prefs(context).edit()
                .putLong(KEY_ATTEMPT_INSTALL, install)
                .putInt(KEY_ATTEMPTS, attempt)
                .apply();
        lastAttemptElapsed = SystemClock.elapsedRealtime();
        Log.i(TAG, "attempt " + attempt + "/" + MAX_ATTEMPTS + " for install " + install);
        PinSession.INSTANCE.expectReturn(PIN_RETURN_GRACE_MS);
        service.runAutoStartEnabler((success, result) -> {
            IN_FLIGHT.set(false);
            if (currentInstall(context) != install) return;
            if (success) {
                markDoneForCurrentInstall(context);
                Log.i(TAG, "done (result=" + result + ")");
                return;
            }
            // A failed follow-up no longer supports the guide's previous green check.
            // Keep the first-confirmation timestamp so retries stay in this phase.
            prefs(context).edit().remove(KEY_DONE_INSTALL).apply();
            boolean terminal = result == AutoStartEnabler.Result.NOT_THIS_FIRMWARE
                    || attempt >= MAX_ATTEMPTS;
            if (terminal) {
                // The guide was skipped for this update; bring its manual button back.
                prefs(context).edit().putInt(KEY_ATTEMPTS, MAX_ATTEMPTS).apply();
                SetupGuideDialog.reset(context);
                Log.w(TAG, "gave up (result=" + result + "); setup guide will show next launch");
                return;
            }
            Log.w(TAG, "attempt " + attempt + " failed (result=" + result + "); will retry");
            schedule(RECHECK_MS);
        });
    }

    private static long followUpDelay(SharedPreferences p, long install) {
        if (p.getLong(KEY_FIRST_CONFIRM_INSTALL, 0L) != install) return 0L;
        return AutoStartCheckpointPolicy.delayUntilNextCheckpoint(
                p.getLong(KEY_FIRST_CONFIRM_AT, 0L), System.currentTimeMillis(),
                p.getLong(KEY_RECHECK_DONE_INSTALL, 0L) == install);
    }

    private static void schedule(long delay) {
        MAIN.removeCallbacks(CHECK);
        KeepAliveAccessibilityService service = KeepAliveAccessibilityService.getInstance();
        if (service != null && isPending(service)) MAIN.postDelayed(CHECK, delay);
    }

    private static boolean isServiceEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            ComponentName self = new ComponentName(context, KeepAliveAccessibilityService.class);
            for (String entry : enabled.split(":")) {
                if (self.equals(ComponentName.unflattenFromString(entry.trim()))) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int attemptsFor(Context context, long install) {
        SharedPreferences p = prefs(context);
        return p.getLong(KEY_ATTEMPT_INSTALL, 0L) == install ? p.getInt(KEY_ATTEMPTS, 0) : 0;
    }

    private static long currentInstall(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.lastUpdateTime;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
