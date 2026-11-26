package com.overdrive.app.services;

import android.content.Context;
import android.util.Log;

import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.overlay.SetupGuideDialog;

import org.json.JSONObject;

/** Shares the last BYD dialog observation with the daemon; never a live firmware read. */
public final class AutoStartObservation {
    private static final String TAG = "AutostartObservation";
    private static final String SECTION = "autoStartObservation";

    private AutoStartObservation() {}

    /** Package replacement invalidates every observation from the previous installation. */
    public static long currentInstall(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** Called off the UI looper after the accessibility worker finishes. */
    public static void record(Context context, long install, AutoStartEnabler.Result result) {
        if (install <= 0L || install != currentInstall(context)) return;
        try {
            String state;
            switch (result) {
                case SUCCESS:
                case ALREADY_OFF:
                    state = "allowed";
                    break;
                case STILL_ON:
                    state = "blocked";
                    break;
                default:
                    state = "unknown";
                    break;
            }
            JSONObject observation = new JSONObject()
                    .put("installTime", install)
                    .put("checkedAt", System.currentTimeMillis())
                    .put("state", state);
            if (!UnifiedConfigManager.updateSection(SECTION, observation)) {
                Log.w(TAG, "Could not publish auto-start observation");
            }
        } catch (Throwable failure) {
            Log.w(TAG, "Could not publish auto-start observation", failure);
        }
    }

    /** Reads the shared config; returns null where the BYD dialog cannot be resolved. */
    public static JSONObject read(Context context) {
        if (context == null || DiLink5Platform.isSelected()) return null;
        try {
            if (context.getPackageManager().resolveActivity(
                    SetupGuideDialog.buildAppStartManagementIntent(), 0) == null) return null;
        } catch (Throwable ignored) {
            return null;
        }
        JSONObject unknown = new JSONObject();
        try {
            unknown.put("state", "unknown");
            long install = currentInstall(context);
            if (install <= 0L) return unknown;
            JSONObject saved = UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
            if (saved == null || saved.optLong("installTime", 0L) != install) return unknown;
            long checkedAt = saved.optLong("checkedAt", 0L);
            String state = saved.optString("state", "unknown");
            if (checkedAt <= 0L || checkedAt > System.currentTimeMillis()) return unknown;
            if (!"allowed".equals(state) && !"blocked".equals(state)) state = "unknown";
            return new JSONObject().put("state", state).put("checkedAt", checkedAt);
        } catch (Throwable ignored) {
            return unknown;
        }
    }
}
