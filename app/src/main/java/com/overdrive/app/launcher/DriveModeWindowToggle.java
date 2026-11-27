package com.overdrive.app.launcher;

import org.json.JSONObject;

/**
 * Turning the BYD100F drive mode window (#1002) on and off, with the I/O behind {@link Env} so
 * the order of steps is testable.
 *
 * <p>On: the previous values of the freeform settings (and the user the secure one belongs to)
 * are saved before anything is written. Off: the option is persisted off first, so a resume or
 * gear check that lands during turn-off can't float the task again; then the task goes back to
 * full screen; only then are the settings restored, for the user they were saved for. A step that
 * fails stops the sequence and keeps the backup, so turning it off again retries.
 */
final class DriveModeWindowToggle {

    static final String RESULT_ENABLED = "enabled";
    static final String RESULT_SETTINGS_UNCONFIRMED = "settings_unconfirmed";
    static final String RESULT_DISABLED = "disabled";
    static final String RESULT_SETTING_UNREADABLE = "setting_unreadable";
    static final String RESULT_CONFIG_WRITE_FAILED = "config_write_failed";
    static final String RESULT_WINDOW_RESTORE_FAILED = "window_restore_failed";
    static final String RESULT_RESTORE_UNCONFIRMED = "restore_unconfirmed";

    /** Backup key holding the {@code --user N} flag the secure setting was read for. */
    static final String BACKUP_USER_FLAG = "userFlag";

    /** How putting the task back to full screen went. */
    enum WindowRestore { RESTORED, NOT_FLOATING, FAILED, TIMED_OUT }

    interface Env {
        /** The {@code driveModeWindow} config section, or null. */
        JSONObject section();

        /** Merge {@code patch} into the section; false when the write failed. */
        boolean writeSection(JSONObject patch);

        /** The current {@code --user N} flag for per-user settings ("" when unknown). */
        String currentUserFlag();

        /** Run one {@code settings} command; trimmed output, "" on any failure. */
        String run(String command);

        /** Put OverDrive's task back to full screen, waiting a bounded time. */
        WindowRestore restoreWindow();

        /** The option is on: start floating the task. */
        void afterEnable();

        /** The settings were restored: anything that cached them must re-read. */
        void afterSettingsRestored();
    }

    private DriveModeWindowToggle() {
    }

    static String enable(Env env) throws Exception {
        JSONObject backup = backupOf(env.section());
        if (backup == null) {
            String userFlag = safeUserFlag(env.currentUserFlag());
            backup = new JSONObject().put(BACKUP_USER_FLAG, userFlag);
            for (String[] s : DriveModeWindowPolicy.SETTINGS) {
                String prior = read(env, s[0], s[1], userFlag);
                // A failed read comes back empty; only a printed "null" means missing.
                if (!DriveModeWindowPolicy.isReadable(prior)
                        || !DriveModeWindowPolicy.isRestorableValue(prior)) {
                    return RESULT_SETTING_UNREADABLE;
                }
                backup.put(key(s), prior.trim());
            }
        }
        // Record the previous values before changing anything, so they can be restored.
        if (!env.writeSection(new JSONObject().put("enabled", true).put("backup", backup))) {
            return RESULT_CONFIG_WRITE_FAILED;
        }
        String userFlag = userFlagOf(backup);
        boolean allOn = true;
        for (String[] s : DriveModeWindowPolicy.SETTINGS) {
            env.run(DriveModeWindowPolicy.settingsCommand("put", s[0], s[1], "1", userFlag));
            allOn &= DriveModeWindowPolicy.isOn(read(env, s[0], s[1], userFlag));
        }
        env.afterEnable();
        return allOn ? RESULT_ENABLED : RESULT_SETTINGS_UNCONFIRMED;
    }

    static String disable(Env env) throws Exception {
        JSONObject backup = backupOf(env.section());
        // Off first: a resume or gear check during turn-off then finds the option off.
        if (!env.writeSection(new JSONObject().put("enabled", false)
                .put("backup", backup == null ? JSONObject.NULL : backup))) {
            return RESULT_CONFIG_WRITE_FAILED;
        }
        WindowRestore window = env.restoreWindow();
        if (window == WindowRestore.FAILED || window == WindowRestore.TIMED_OUT) {
            // Switching freeform off under a floating task would strand it there; keep the
            // settings and the backup so turning the option off again retries.
            return RESULT_WINDOW_RESTORE_FAILED;
        }
        if (backup == null) return RESULT_DISABLED;
        String userFlag = userFlagOf(backup);
        boolean restored = true;
        for (String[] s : DriveModeWindowPolicy.SETTINGS) {
            String prior = backup.optString(key(s), "null");
            if (!DriveModeWindowPolicy.isRestorableValue(prior)) continue;
            env.run(DriveModeWindowPolicy.restoreCommand(s[0], s[1], prior, userFlag));
            // Read back separately: "settings delete" prints "Deleted N rows", which a combined
            // command would glue onto the value.
            restored &= DriveModeWindowPolicy.restoreConfirmed(prior, read(env, s[0], s[1], userFlag));
        }
        env.afterSettingsRestored();
        if (!restored) return RESULT_RESTORE_UNCONFIRMED;
        if (!env.writeSection(new JSONObject().put("backup", JSONObject.NULL))) {
            return RESULT_CONFIG_WRITE_FAILED;
        }
        return RESULT_DISABLED;
    }

    /** Results that mean the last turn on/off did not complete. */
    static boolean isFailure(String result) {
        return RESULT_SETTING_UNREADABLE.equals(result)
                || RESULT_CONFIG_WRITE_FAILED.equals(result)
                || RESULT_WINDOW_RESTORE_FAILED.equals(result)
                || RESULT_RESTORE_UNCONFIRMED.equals(result)
                || "error".equals(result);
    }

    /** Only a plain {@code --user N} flag (or none) ever reaches a shell command. */
    static String safeUserFlag(String flag) {
        if (flag == null) return "";
        String f = flag.trim();
        return f.matches("--user [0-9]{1,5}") ? f : "";
    }

    private static String userFlagOf(JSONObject backup) {
        return safeUserFlag(backup.optString(BACKUP_USER_FLAG, ""));
    }

    private static JSONObject backupOf(JSONObject section) {
        return section == null ? null : section.optJSONObject("backup");
    }

    private static String key(String[] setting) {
        return setting[0] + "/" + setting[1];
    }

    private static String read(Env env, String namespace, String key, String userFlag) {
        return env.run(DriveModeWindowPolicy.settingsCommand("get", namespace, key, null, userFlag));
    }
}
