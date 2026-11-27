package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.Test;

public class DriveModeWindowToggleTest {

    /** Fake shell + config that answers like the real {@code settings} tool. */
    private static final class FakeEnv implements DriveModeWindowToggle.Env {
        final List<String> calls = new ArrayList<>();
        /** "namespace/key@user" -> value; absent = missing key. */
        final Map<String, String> settings = new HashMap<>();
        JSONObject section = new JSONObject();
        String userFlag = "--user 10";
        boolean failWrites;
        DriveModeWindowToggle.WindowRestore window = DriveModeWindowToggle.WindowRestore.RESTORED;
        String unreadable;

        @Override public JSONObject section() {
            return section;
        }

        @Override public boolean writeSection(JSONObject patch) {
            calls.add("config:" + patch.toString());
            if (failWrites) return false;
            try {
                for (Iterator<String> it = patch.keys(); it.hasNext(); ) {
                    String k = it.next();
                    Object v = patch.get(k);
                    if (v == JSONObject.NULL) section.remove(k); else section.put(k, v);
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return true;
        }

        @Override public String currentUserFlag() {
            return userFlag;
        }

        @Override public String run(String command) {
            calls.add("run:" + command);
            String[] w = command.split(" ");
            int i = 1;
            String user = "";
            if (w[i].equals("--user")) {
                user = w[i + 1];
                i += 2;
            }
            String verb = w[i];
            String ns = w[i + 1];
            String key = w[i + 2];
            String id = ns + "/" + key + ("global".equals(ns) ? "" : "@" + user);
            switch (verb) {
                case "get":
                    if (id.equals(unreadable)) return "";
                    return settings.containsKey(id) ? settings.get(id) : "null";
                case "put":
                    settings.put(id, w[i + 3]);
                    return "";
                case "delete":
                    boolean had = settings.remove(id) != null;
                    return "Deleted " + (had ? 1 : 0) + " rows";
                default:
                    throw new AssertionError(command);
            }
        }

        @Override public DriveModeWindowToggle.WindowRestore restoreWindow() {
            calls.add("window");
            return window;
        }

        @Override public void afterEnable() {
            calls.add("afterEnable");
        }

        @Override public void afterSettingsRestored() {
            calls.add("afterSettingsRestored");
        }

        int indexOf(String prefix) {
            for (int i = 0; i < calls.size(); i++) if (calls.get(i).startsWith(prefix)) return i;
            return -1;
        }
    }

    @Test
    public void enableSavesPriorValuesAndTheUserBeforeWriting() throws Exception {
        FakeEnv env = new FakeEnv();
        env.settings.put("global/force_resizable_activities", "0");
        assertEquals(DriveModeWindowToggle.RESULT_ENABLED, DriveModeWindowToggle.enable(env));
        JSONObject backup = env.section.getJSONObject("backup");
        assertEquals("null", backup.getString("global/enable_freeform_support"));
        assertEquals("0", backup.getString("global/force_resizable_activities"));
        assertEquals("null", backup.getString("secure/freeform_window_management"));
        assertEquals("--user 10", backup.getString(DriveModeWindowToggle.BACKUP_USER_FLAG));
        assertTrue(env.indexOf("config:") < env.indexOf("run:settings put"));
        assertEquals("1", env.settings.get("secure/freeform_window_management@10"));
        assertTrue(env.section.getBoolean("enabled"));
    }

    @Test
    public void enableRefusesWhenASettingCannotBeRead() throws Exception {
        FakeEnv env = new FakeEnv();
        env.unreadable = "global/force_resizable_activities";
        assertEquals(DriveModeWindowToggle.RESULT_SETTING_UNREADABLE, DriveModeWindowToggle.enable(env));
        assertEquals(-1, env.indexOf("config:"));
        assertEquals(-1, env.indexOf("run:settings put"));
    }

    @Test
    public void aSecondEnableKeepsTheOriginalBackup() throws Exception {
        FakeEnv env = new FakeEnv();
        DriveModeWindowToggle.enable(env);
        DriveModeWindowToggle.enable(env);
        assertEquals("null", env.section.getJSONObject("backup").getString("global/enable_freeform_support"));
    }

    @Test
    public void disablePersistsOffBeforeTouchingTheWindowOrTheSettings() throws Exception {
        FakeEnv env = new FakeEnv();
        DriveModeWindowToggle.enable(env);
        env.calls.clear();
        assertEquals(DriveModeWindowToggle.RESULT_DISABLED, DriveModeWindowToggle.disable(env));
        int off = env.indexOf("config:");
        assertTrue(env.calls.get(off).contains("\"enabled\":false"));
        int window = env.indexOf("window");
        int firstRestore = env.indexOf("run:settings");
        assertTrue("off is persisted first", off == 0);
        assertTrue("window back to full screen before the settings", window > off && firstRestore > window);
        assertFalse(env.section.getBoolean("enabled"));
        assertNull("backup cleared once confirmed", env.section.optJSONObject("backup"));
        assertTrue(env.calls.contains("afterSettingsRestored"));
    }

    @Test
    public void missingKeysAreDeletedAndConfirmedDespiteTheDeleteOutput() throws Exception {
        FakeEnv env = new FakeEnv();
        DriveModeWindowToggle.enable(env);
        assertEquals(DriveModeWindowToggle.RESULT_DISABLED, DriveModeWindowToggle.disable(env));
        assertFalse(env.settings.containsKey("global/enable_freeform_support"));
        assertFalse(env.settings.containsKey("global/force_resizable_activities"));
        assertFalse(env.settings.containsKey("secure/freeform_window_management@10"));
    }

    @Test
    public void thePerUserSettingIsRestoredForTheUserItWasSavedFor() throws Exception {
        FakeEnv env = new FakeEnv();
        env.settings.put("secure/freeform_window_management@10", "0");
        DriveModeWindowToggle.enable(env);
        env.userFlag = "--user 11";
        assertEquals(DriveModeWindowToggle.RESULT_DISABLED, DriveModeWindowToggle.disable(env));
        assertEquals("0", env.settings.get("secure/freeform_window_management@10"));
        assertFalse(env.settings.containsKey("secure/freeform_window_management@11"));
    }

    @Test
    public void aFailedOffWriteStopsBeforeAnythingChanges() throws Exception {
        FakeEnv env = new FakeEnv();
        DriveModeWindowToggle.enable(env);
        env.calls.clear();
        env.failWrites = true;
        assertEquals(DriveModeWindowToggle.RESULT_CONFIG_WRITE_FAILED, DriveModeWindowToggle.disable(env));
        assertEquals(-1, env.indexOf("window"));
        assertEquals(-1, env.indexOf("run:"));
        assertTrue(env.section.getBoolean("enabled"));
    }

    @Test
    public void aTaskThatStaysFloatingKeepsTheSettingsAndTheBackupForARetry() throws Exception {
        FakeEnv env = new FakeEnv();
        DriveModeWindowToggle.enable(env);
        env.window = DriveModeWindowToggle.WindowRestore.TIMED_OUT;
        assertEquals(DriveModeWindowToggle.RESULT_WINDOW_RESTORE_FAILED, DriveModeWindowToggle.disable(env));
        assertEquals("1", env.settings.get("global/enable_freeform_support"));
        assertNotNull(env.section.optJSONObject("backup"));
        assertFalse(env.section.getBoolean("enabled"));
        // Retry once the window comes back.
        env.window = DriveModeWindowToggle.WindowRestore.NOT_FLOATING;
        assertEquals(DriveModeWindowToggle.RESULT_DISABLED, DriveModeWindowToggle.disable(env));
        assertFalse(env.settings.containsKey("global/enable_freeform_support"));
    }

    @Test
    public void failuresAreReportedAsFailures() {
        assertTrue(DriveModeWindowToggle.isFailure("setting_unreadable"));
        assertTrue(DriveModeWindowToggle.isFailure("config_write_failed"));
        assertTrue(DriveModeWindowToggle.isFailure("window_restore_failed"));
        assertTrue(DriveModeWindowToggle.isFailure("restore_unconfirmed"));
        assertTrue(DriveModeWindowToggle.isFailure("error"));
        assertFalse(DriveModeWindowToggle.isFailure("enabled"));
        assertFalse(DriveModeWindowToggle.isFailure("disabled"));
        assertFalse(DriveModeWindowToggle.isFailure("idle"));
    }

    @Test
    public void onlyAPlainUserFlagReachesTheShell() {
        assertEquals("--user 10", DriveModeWindowToggle.safeUserFlag("--user 10"));
        assertEquals("", DriveModeWindowToggle.safeUserFlag("--user 10; reboot"));
        assertEquals("", DriveModeWindowToggle.safeUserFlag(null));
    }
}
