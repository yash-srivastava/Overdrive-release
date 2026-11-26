package com.overdrive.app.services;

import android.content.Context;
import android.util.Log;

import com.overdrive.app.config.UnifiedConfigManager;

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

    /** Reads the shared config, not the app process's private SharedPreferences. */
    public static JSONObject read(Context context) {
        JSONObject unknown = new JSONObject();
        try {
            unknown.put("state", "unknown");
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) return null;
            if (com.overdrive.app.camera.DiLink100Platform.isByd100f()
                    && context != null && context.getPackageManager().resolveActivity(
                            com.overdrive.app.overlay.SetupGuideDialog.buildAppStartManagementIntent(),
                            0) == null) return null;
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
