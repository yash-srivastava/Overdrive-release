package com.overdrive.app.overlay;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.overdrive.app.R;

/**
 * First-launch setup guide dialog.
 * 
 * Shows two guided steps:
 * 1. Disable auto-start restriction (BYD kills background apps aggressively)
 * 2. Enable "Display over other apps" for the status overlay
 * 
 * Each step has a button that deep-links to the relevant system settings.
 * A "Don't show again" checkbox persists the preference.
 */
public class SetupGuideDialog {

    private static final String PREFS_NAME = "overdrive_setup";
    private static final String KEY_GUIDE_DISMISSED = "setup_guide_dismissed";

    /**
     * Show the setup guide if it hasn't been dismissed.
     * Returns true if the dialog was shown, false if already dismissed.
     */
    public static boolean showIfNeeded(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_GUIDE_DISMISSED, false)) {
            return false;
        }

        show(context);
        return true;
    }

    /**
     * Force show the setup guide (e.g., from settings menu).
     */
    public static void show(Context context) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_setup_guide, null);

        // Step 1: Auto-start restriction
        TextView btnAutoStart = view.findViewById(R.id.btnOpenAutoStart);
        View stepAutoStartCheck = view.findViewById(R.id.ivAutoStartCheck);
        btnAutoStart.setOnClickListener(v -> {
            try {
                // BYD Android 10: auto-start settings are in the standard App Info page
                // under "Battery" or "Auto-start" section. The standard app details
                // intent is the correct entry point — user scrolls to find the toggle.
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                context.startActivity(intent);
            } catch (Exception e) {
                // Fallback: try generic app list
                try {
                    Intent fallback = new Intent(Settings.ACTION_APPLICATION_SETTINGS);
                    context.startActivity(fallback);
                } catch (Exception e2) {
                    Intent last = new Intent(Settings.ACTION_SETTINGS);
                    context.startActivity(last);
                }
            }
        });

        // Step 2: Overlay permission
        TextView btnOverlay = view.findViewById(R.id.btnOpenOverlay);
        View stepOverlayCheck = view.findViewById(R.id.ivOverlayCheck);
        
        // Show check mark if already granted
        if (Settings.canDrawOverlays(context)) {
            stepOverlayCheck.setVisibility(View.VISIBLE);
            btnOverlay.setText("✓ Already Granted");
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
                // Fallback
                try {
                    Intent fallback = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    context.startActivity(fallback);
                } catch (Exception e2) {
                    Intent last = new Intent(Settings.ACTION_SETTINGS);
                    context.startActivity(last);
                }
            }
        });

        // Don't show again checkbox
        CheckBox cbDontShow = view.findViewById(R.id.cbDontShowAgain);

        AlertDialog dialog = new AlertDialog.Builder(context, R.style.Theme_Overdrive_Dialog)
                .setView(view)
                .setCancelable(true)
                .create();

        // Wire up inline buttons (AlertDialog's button bar gets pushed off-screen
        // on BYD head units in landscape, so we use buttons inside the layout).
        view.findViewById(R.id.btnDone).setOnClickListener(v -> {
            if (cbDontShow.isChecked()) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_GUIDE_DISMISSED, true)
                        .apply();
            }
            StatusOverlayService.startIfPermitted(context);
            dialog.dismiss();
        });

        view.findViewById(R.id.btnSkip).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /**
     * Reset the dismissed state (for testing or re-showing).
     */
    public static void reset(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_GUIDE_DISMISSED, false)
                .apply();
    }
}
