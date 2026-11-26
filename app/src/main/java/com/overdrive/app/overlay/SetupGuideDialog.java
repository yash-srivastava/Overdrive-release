package com.overdrive.app.overlay;

import androidx.appcompat.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import com.overdrive.app.R;
import com.overdrive.app.camera.DiLink100Platform;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.services.AutoStartReapply;
import com.overdrive.app.services.KeepAliveAccessibilityService;
import com.overdrive.app.util.BydDataCacheWhitelist;

/**
 * First-launch and post-update setup guide.
 *
 * Two guided steps:
 *   1. Disable BYD auto-start restriction (head unit kills background apps)
 *   2. Allow "Display over other apps" for the status overlay
 *
 * Re-show policy: the dialog re-appears every time PackageInfo.lastUpdateTime
 * advances past the stored marker. That covers first install, in-app update,
 * adb sideload, and any other replace path. BYD wipes its autostart whitelist
 * on every install, so the user MUST be reminded to re-enable it.
 */
public class SetupGuideDialog {
    // A BYD settings round trip from this guide keeps an unlocked PIN session.
    private static final long SETUP_RETURN_GRACE_MS = 3 * 60_000L;


    private static final String TAG = "SetupGuideDialog";
    private static final String PREFS_NAME = "overdrive_setup";
    private static final String KEY_LAST_SEEN_INSTALL_TIME = "last_seen_install_time";
    private static final long AUTOSTART_SERVICE_WAIT_MS = 5000L;
    private static final long AUTOSTART_SERVICE_POLL_MS = 200L;

    /**
     * Show the setup guide if the app's last install/update time has advanced
     * past the stored marker. Returns true if the dialog was shown.
     */
    public static boolean showIfNeeded(Context context) {
        long currentInstallTime = getCurrentInstallTime(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastSeen = prefs.getLong(KEY_LAST_SEEN_INSTALL_TIME, 0L);

        if (currentInstallTime > 0 && currentInstallTime <= lastSeen) {
            return false;
        }

        boolean isUpdate = lastSeen > 0L;
        if (isUpdate && !isAutoStartStepNeeded(context)
                && OverlayPermissionChecker.isGranted(context)) {
            Log.i(TAG, "update guide skipped: no auto-start restriction on this firmware");
            markCurrentInstallSeen(context);
            return false;
        }
        // Opted in: AutoStartReapply redoes BYD's switch and resets this marker if it gives up.
        if (isUpdate && AutoStartReapply.willHandleCurrentInstall(context)
                && OverlayPermissionChecker.isGranted(context)) {
            Log.i(TAG, "update guide skipped: auto-start is re-applied automatically");
            markCurrentInstallSeen(context);
            return false;
        }
        show(context, isUpdate);
        return true;
    }

    /** False on firmware that has no auto-start restriction to lift (see {@link AutoStartStepPolicy}). */
    static boolean isAutoStartStepNeeded(Context context) {
        return AutoStartStepPolicy.applies(
                DiLink5Platform.isSelected(),
                DiLink100Platform.isByd100f(),
                hasAutoStartManager(context));
    }

    /** BYD's auto-start manager can be opened by the deep link or its launcher entry. */
    private static boolean hasAutoStartManager(Context context) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            return pm.resolveActivity(buildAppStartManagementIntent(), 0) != null
                    || pm.getLaunchIntentForPackage(BYD_APPSTART_PKG) != null;
        } catch (Throwable t) {
            // Unknown: keep the step rather than hide one the car may need.
            return true;
        }
    }

    /** Force-show the setup guide (e.g., from a settings entry). */
    public static void show(Context context) {
        show(context, false);
    }

    /**
     * @param isUpdate when true, the dialog shows a "Updated to vX" banner so
     *                 the user understands why it reappeared.
     */
    public static void show(Context context, boolean isUpdate) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_setup_guide, null);

        // Version banner — only when re-showing after an update, not on first install.
        TextView tvVersionBanner = view.findViewById(R.id.tvVersionBanner);
        if (tvVersionBanner != null) {
            if (isUpdate) {
                // getInstalledVersion() = in-memory BuildConfig identity, no
                // disk I/O — this runs on the main thread (post-update relaunch
                // banner). getDisplayVersion() would read /data/local/tmp on the
                // looper; the just-relaunched build's BuildConfig identity is
                // the correct value to show here anyway.
                tvVersionBanner.setText(context.getString(R.string.setup_version_banner,
                        com.overdrive.app.updater.AppUpdater.getInstalledVersion()));
                tvVersionBanner.setVisibility(View.VISIBLE);
            } else {
                tvVersionBanner.setVisibility(View.GONE);
            }
        }

        // Step 1: Language. Always shown as "complete" because Auto is a valid
        // selection out of the box; the row exists so users can opt in to a
        // specific language before they hit Done.
        TextView btnLanguage = view.findViewById(R.id.btnOpenLanguage);
        if (btnLanguage != null) {
            btnLanguage.setOnClickListener(v ->
                com.overdrive.app.ui.dialog.LanguagePickerDialog.show(context, picked -> {
                    // After a pick, recreate the host activity so AppCompat
                    // re-applies the locale and the setup dialog re-inflates
                    // in the new language. Cheaper than juggling two dialogs.
                    if (context instanceof android.app.Activity) {
                        ((android.app.Activity) context).recreate();
                    }
                }));
        }

        // Step 2: Auto-start restriction.
        // Wait briefly for the asynchronously bound KeepAlive AccessibilityService,
        // then drive BYD's "Deaktiver Autostart" switch. A fast tap during app
        // startup previously raced the bind and went straight to the manual screen.
        final StepRow autoStartStep = new StepRow(view,
                R.id.tvAutoStartTitle, R.id.tvAutoStartBody,
                R.id.btnOpenAutoStart, R.id.ivAutoStartCheck);
        if (!isAutoStartStepNeeded(context)) {
            autoStartStep.markComplete(context, R.string.setup_autostart_not_needed);
        } else {
            autoStartStep.button.setOnClickListener(v -> {
                com.overdrive.app.auth.PinSession.INSTANCE.expectReturn(SETUP_RETURN_GRACE_MS);
                autoStartStep.button.setEnabled(false);
                autoStartStep.button.setText(context.getString(R.string.setup_autostart_enabling));
                runAutoStartWhenServiceReady(
                        context,
                        autoStartStep,
                        SystemClock.elapsedRealtime() + AUTOSTART_SERVICE_WAIT_MS);
            });
            if (AutoStartReapply.isDoneForCurrentInstall(context)) {
                autoStartStep.markComplete(context, R.string.setup_autostart_enabled);
            }
            bindAutoStartReapply(context, view, autoStartStep);
        }

        // Step 3: Overlay permission
        final StepRow overlayStep = new StepRow(view,
                R.id.tvOverlayTitle, R.id.tvOverlayBody,
                R.id.btnOpenOverlay, R.id.ivOverlayCheck);

        renderOverlayPermission(context, overlayStep);

        overlayStep.button.setOnClickListener(v -> {
            com.overdrive.app.auth.PinSession.INSTANCE.expectReturn(SETUP_RETURN_GRACE_MS);
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.getPackageName())
                );
                context.startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent fallback = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    context.startActivity(fallback);
                } catch (Exception e2) {
                    Intent last = new Intent(Settings.ACTION_SETTINGS);
                    context.startActivity(last);
                }
            }
        });

        // Tip: open the BYD Traffic Monitor enable/disable dialog. The handler
        // lives on MainActivity (it shares the ADB pm-disable plumbing with the
        // Diagnostics tile), so this is a no-op when the dialog is shown from
        // some other host context — the second tip below is instructional-only
        // and remains useful regardless.
        TextView btnTrafficMonitor = view.findViewById(R.id.btnOpenTrafficMonitor);
        if (btnTrafficMonitor != null) {
            btnTrafficMonitor.setOnClickListener(v -> {
                if (context instanceof com.overdrive.app.ui.MainActivity) {
                    ((com.overdrive.app.ui.MainActivity) context).invokeTrafficMonitorAction();
                }
            });
        }

        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
                .setView(view)
                .setCancelable(true)
                .create();

        dialog.setOnShowListener(ignored -> {
            renderOverlayPermission(context, overlayStep);
            dialog.getWindow().getDecorView().getViewTreeObserver()
                    .addOnWindowFocusChangeListener(hasFocus -> {
                        if (hasFocus) {
                            renderOverlayPermission(context, overlayStep);
                        }
                    });
        });

        // "Don't show again" — record current install time so the dialog stays
        // suppressed until PackageInfo.lastUpdateTime advances (next install
        // or update). BYD wipes the autostart whitelist on every install, so
        // the marker naturally invalidates and the dialog reappears post-update.
        view.findViewById(R.id.btnDone).setOnClickListener(v -> {
            markCurrentInstallSeen(context);
            StatusOverlayService.startIfPermitted(context);
            dialog.dismiss();
        });

        View btnDiagnostics = view.findViewById(R.id.btnOpenDiagnostics);
        if (btnDiagnostics != null) {
            btnDiagnostics.setOnClickListener(v -> {
                if (context instanceof com.overdrive.app.ui.MainActivity) {
                    ((com.overdrive.app.ui.MainActivity) context).invokeDiagnosticsScreen();
                    dialog.dismiss();
                }
            });
        }

        // "Remind me later" — soft nag: do NOT update the seen marker, so the
        // dialog reappears on next launch. Autostart is load-bearing; a single
        // accidental dismiss shouldn't permanently silence the reminder.
        view.findViewById(R.id.btnSkip).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /**
     * The views one guided step owns. A step is either pending — instructions
     * plus an action — or complete, where the check and body carry the
     * confirmation and the action is gone.
     */
    private static final class StepRow {
        private final TextView title;
        private final TextView body;
        private final TextView button;
        private final View check;

        StepRow(View root, int titleId, int bodyId, int buttonId, int checkId) {
            this.title = root.findViewById(titleId);
            this.body = root.findViewById(bodyId);
            this.button = root.findViewById(buttonId);
            this.check = root.findViewById(checkId);
        }

        void markComplete(Context context, int bodyRes) {
            if (check != null) check.setVisibility(View.VISIBLE);
            if (button != null) button.setVisibility(View.GONE);
            if (body != null) body.setText(context.getString(bodyRes));
            tintTitle(com.google.android.material.R.attr.colorOnSurfaceVariant);
        }

        void markPending(Context context, int bodyRes, int buttonRes) {
            if (check != null) check.setVisibility(View.INVISIBLE);
            if (button != null) {
                button.setVisibility(View.VISIBLE);
                button.setEnabled(true);
                button.setText(context.getString(buttonRes));
            }
            if (body != null) body.setText(context.getString(bodyRes));
            tintTitle(com.google.android.material.R.attr.colorOnSurface);
        }

        private void tintTitle(int colorAttr) {
            if (title == null) return;
            title.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    title, colorAttr));
        }
    }

    private static void runAutoStartWhenServiceReady(
            Context context, StepRow step, long deadline) {
        // DiLink 5 has no autostart-management app; the daemon applies the cache
        // allowlist instead. Post back on the main looper rather than a step view,
        // which may not be inflated.
        if (DiLink5Platform.isSelected()) {
            new Thread(() -> {
                final boolean applied =
                        BydDataCacheWhitelist.applyViaDaemonWhenReady();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                        () -> finishAutoStartAttempt(
                                context,
                                step,
                                applied,
                                applied
                                        ? com.overdrive.app.services.AutoStartEnabler.Result.SUCCESS
                                        : null));
            }, "BackgroundAccessSetup").start();
            return;
        }
        KeepAliveAccessibilityService service = KeepAliveAccessibilityService.getInstance();
        if (service != null) {
            service.runAutoStartEnabler((success, result) ->
                    finishAutoStartAttempt(context, step, success, result));
            return;
        }
        if (SystemClock.elapsedRealtime() < deadline) {
            step.button.postDelayed(
                    () -> runAutoStartWhenServiceReady(context, step, deadline),
                    AUTOSTART_SERVICE_POLL_MS);
            return;
        }
        Log.w(TAG, "a11y service did not bind within " + AUTOSTART_SERVICE_WAIT_MS
                + "ms — falling back to manual settings");
        finishAutoStartAttempt(context, step, false, null);
    }

    private static void finishAutoStartAttempt(
            Context context,
            StepRow step,
            boolean success,
            com.overdrive.app.services.AutoStartEnabler.Result result) {
        if (success) {
            Log.i(TAG, "autostart auto-enable done (result=" + result + ")");
            AutoStartReapply.markDoneForCurrentInstall(context);
            step.markComplete(context, R.string.setup_autostart_enabled);
            return;
        }
        Log.w(TAG, "autostart auto-enable failed (result=" + result
                + ") — falling back to manual settings");
        step.markPending(context,
                R.string.setup_autostart_body, R.string.setup_autostart_button);
        Toast.makeText(context,
                context.getString(R.string.setup_autostart_failed),
                Toast.LENGTH_LONG).show();
        openAutoStartSettings(context);
    }

    /** The opt-in switch; turning it on also runs the step now if this install still needs it. */
    private static void bindAutoStartReapply(Context context, View root, StepRow step) {
        CompoundButton toggle = root.findViewById(R.id.swAutoStartReapply);
        View hint = root.findViewById(R.id.tvAutoStartReapplyHint);
        if (toggle == null) return;
        int visibility = AutoStartReapply.isAvailable(context) ? View.VISIBLE : View.GONE;
        toggle.setVisibility(visibility);
        if (hint != null) hint.setVisibility(visibility);
        if (visibility != View.VISIBLE) return;
        toggle.setChecked(AutoStartReapply.isEnabled(context));
        toggle.setOnCheckedChangeListener((button, on) -> {
            AutoStartReapply.setEnabled(context, on);
            if (on && !AutoStartReapply.isDoneForCurrentInstall(context)
                    && step.button != null
                    && step.button.getVisibility() == View.VISIBLE
                    && step.button.isEnabled()) {
                step.button.performClick();
            }
        });
    }

    private static void renderOverlayPermission(Context context, StepRow step) {
        if (OverlayPermissionChecker.isGranted(context)) {
            step.markComplete(context, R.string.setup_overlay_already_granted);
        } else {
            step.markPending(context,
                    R.string.setup_overlay_body, R.string.setup_overlay_button);
        }
    }

    /**
     * Open the BYD autostart-management activity directly. Falls back through:
     *   1. com.byd.appstartmanagement/.frame.AppStartManagement (canonical deep link)
     *   2. Default launcher intent for com.byd.appstartmanagement
     *   3. ACTION_APPLICATION_DETAILS_SETTINGS for OverDrive (legacy fallback)
     *   4. ACTION_APPLICATION_SETTINGS / ACTION_SETTINGS
     */
    /** BYD AppStartManagement package — the privileged "Deaktiver Autostart" app. */
    public static final String BYD_APPSTART_PKG = "com.byd.appstartmanagement";

    /**
     * The canonical explicit intent for BYD's autostart-management dialog
     * (the "Deaktiver Autostart" screen with the per-app switches).
     *
     * Shared with {@code AutoStartEnabler}, which drives this same screen via the
     * AccessibilityService to auto-flip OverDrive's switch after each reinstall.
     * Kept in sync with the canonical deep link in {@link #openAutoStartSettings}.
     * Includes FLAG_ACTIVITY_NEW_TASK so it can be launched from a non-Activity
     * context (the a11y service). Throws ActivityNotFoundException at startActivity
     * time on firmware without this component — callers must catch it.
     */
    public static Intent buildAppStartManagementIntent() {
        Intent i = new Intent();
        i.setComponent(new ComponentName(
                BYD_APPSTART_PKG,
                "com.byd.appstartmanagement.frame.AppStartManagement"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static void openAutoStartSettings(Context context) {
        com.overdrive.app.auth.PinSession.INSTANCE.expectReturn(SETUP_RETURN_GRACE_MS);
        // 1) Canonical BYD deep link.
        try {
            Intent direct = buildAppStartManagementIntent();
            context.startActivity(direct);
            return;
        } catch (Exception e) {
            Log.w(TAG, "BYD AppStartManagement deep link failed: " + e.getMessage());
        }

        // 2) Launch the BYD app via its default activity.
        try {
            Intent launch = context.getPackageManager()
                    .getLaunchIntentForPackage("com.byd.appstartmanagement");
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "BYD AppStartManagement launch intent failed: " + e.getMessage());
        }

        // 3) Generic app-info page (BYD ROMs that lack appstartmanagement still
        //    expose an "auto-start" toggle inside the app-info page).
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return;
        } catch (Exception e) {
            Log.w(TAG, "ACTION_APPLICATION_DETAILS_SETTINGS failed: " + e.getMessage());
        }

        // 4) Last resort.
        try {
            context.startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
        } catch (Exception e) {
            try { context.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Exception ignored) {}
        }
    }

    private static long getCurrentInstallTime(Context context) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return pi.lastUpdateTime;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void markCurrentInstallSeen(Context context) {
        long t = getCurrentInstallTime(context);
        if (t <= 0L) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_SEEN_INSTALL_TIME, t)
                .apply();
    }

    /** Reset the seen marker (testing / re-show from settings). */
    public static void reset(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_SEEN_INSTALL_TIME, 0L)
                .apply();
    }
}
