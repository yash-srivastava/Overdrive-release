package com.overdrive.app.overlay;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.overdrive.app.BuildConfig;
import com.overdrive.app.R;

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

    private static final String TAG = "SetupGuideDialog";
    private static final String PREFS_NAME = "overdrive_setup";
    private static final String KEY_LAST_SEEN_INSTALL_TIME = "last_seen_install_time";

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
        show(context, isUpdate);
        return true;
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
                tvVersionBanner.setText(context.getString(R.string.setup_version_banner, BuildConfig.VERSION_NAME));
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

        // Step 2: Auto-start restriction
        TextView btnAutoStart = view.findViewById(R.id.btnOpenAutoStart);
        btnAutoStart.setOnClickListener(v -> openAutoStartSettings(context));

        // Step 3: Overlay permission
        TextView btnOverlay = view.findViewById(R.id.btnOpenOverlay);
        View stepOverlayCheck = view.findViewById(R.id.ivOverlayCheck);

        if (Settings.canDrawOverlays(context)) {
            stepOverlayCheck.setVisibility(View.VISIBLE);
            btnOverlay.setText(context.getString(R.string.setup_overlay_already_granted));
            btnOverlay.setEnabled(false);
        }

        btnOverlay.setOnClickListener(v -> {
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

        AlertDialog dialog = new AlertDialog.Builder(context, R.style.Theme_Overdrive_Dialog)
                .setView(view)
                .setCancelable(true)
                .create();

        // "Don't show again" — record current install time so the dialog stays
        // suppressed until PackageInfo.lastUpdateTime advances (next install
        // or update). BYD wipes the autostart whitelist on every install, so
        // the marker naturally invalidates and the dialog reappears post-update.
        view.findViewById(R.id.btnDone).setOnClickListener(v -> {
            markCurrentInstallSeen(context);
            StatusOverlayService.startIfPermitted(context);
            dialog.dismiss();
        });

        // "Remind me later" — soft nag: do NOT update the seen marker, so the
        // dialog reappears on next launch. Autostart is load-bearing; a single
        // accidental dismiss shouldn't permanently silence the reminder.
        view.findViewById(R.id.btnSkip).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /**
     * Open the BYD autostart-management activity directly. Falls back through:
     *   1. com.byd.appstartmanagement/.frame.AppStartManagement (canonical deep link)
     *   2. Default launcher intent for com.byd.appstartmanagement
     *   3. ACTION_APPLICATION_DETAILS_SETTINGS for OverDrive (legacy fallback)
     *   4. ACTION_APPLICATION_SETTINGS / ACTION_SETTINGS
     */
    private static void openAutoStartSettings(Context context) {
        // 1) Canonical BYD deep link.
        try {
            Intent direct = new Intent();
            direct.setComponent(new ComponentName(
                    "com.byd.appstartmanagement",
                    "com.byd.appstartmanagement.frame.AppStartManagement"));
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
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
