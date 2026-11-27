package com.overdrive.app.launcher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class DriveModeWindowPolicyTest {

    private static DriveModeWindowPolicy.TaskView task(int mode, boolean visible, boolean covered) {
        return new DriveModeWindowPolicy.TaskView(42, mode, visible, covered);
    }

    private static final int FULL = DriveModeWindowPolicy.WINDOWING_MODE_FULLSCREEN;

    @Test
    public void convertsAVisibleFullscreenTaskOnlyWhenOptedInOnByd100f() {
        DriveModeWindowPolicy.Attempts a = new DriveModeWindowPolicy.Attempts();
        assertTrue(DriveModeWindowPolicy.shouldConvert(true, true, task(FULL, true, false), a, 10_000L));
        assertFalse(DriveModeWindowPolicy.shouldConvert(false, true, task(FULL, true, false), a, 10_000L));
        assertFalse(DriveModeWindowPolicy.shouldConvert(true, false, task(FULL, true, false), a, 10_000L));
        assertFalse(DriveModeWindowPolicy.shouldConvert(true, true, null, a, 10_000L));
    }

    @Test
    public void undefinedModeInheritsFullscreenAndCounts() {
        DriveModeWindowPolicy.Attempts a = new DriveModeWindowPolicy.Attempts();
        assertTrue(DriveModeWindowPolicy.shouldConvert(true, true,
                task(DriveModeWindowPolicy.WINDOWING_MODE_UNDEFINED, true, false), a, 10_000L));
    }

    @Test
    public void leavesFloatingSplitAndHiddenTasksAlone() {
        DriveModeWindowPolicy.Attempts a = new DriveModeWindowPolicy.Attempts();
        assertFalse(DriveModeWindowPolicy.shouldConvert(true, true,
                task(DriveModeWindowPolicy.WINDOWING_MODE_FREEFORM, true, false), a, 10_000L));
        assertFalse(DriveModeWindowPolicy.shouldConvert(true, true, task(3, true, false), a, 10_000L));
        assertFalse(DriveModeWindowPolicy.shouldConvert(true, true, task(FULL, false, false), a, 10_000L));
    }

    @Test
    public void aTaskCoveredByTheCarsRestrictionScreenIsStillMoved() {
        DriveModeWindowPolicy.Attempts a = new DriveModeWindowPolicy.Attempts();
        assertTrue(DriveModeWindowPolicy.shouldConvert(true, true, task(FULL, false, true), a, 10_000L));
    }

    @Test
    public void attemptsAreSpacedAndBoundedPerTask() {
        DriveModeWindowPolicy.Attempts a = new DriveModeWindowPolicy.Attempts();
        long now = 10_000L;
        for (int i = 0; i < DriveModeWindowPolicy.MAX_ATTEMPTS_PER_TASK; i++) {
            assertTrue(DriveModeWindowPolicy.shouldConvert(true, true, task(FULL, true, false), a, now));
            a.note(42, now);
            assertFalse("spacing", DriveModeWindowPolicy.shouldConvert(
                    true, true, task(FULL, true, false), a, now + 10));
            now += DriveModeWindowPolicy.MIN_ATTEMPT_SPACING_MS;
        }
        assertFalse("budget spent", DriveModeWindowPolicy.shouldConvert(
                true, true, task(FULL, true, false), a, now + 60_000L));
        // A new task (the app was reopened) gets a fresh budget.
        DriveModeWindowPolicy.TaskView next = new DriveModeWindowPolicy.TaskView(43, FULL, true, false);
        assertTrue(DriveModeWindowPolicy.shouldConvert(true, true, next, a, now + 60_000L));
        a.reset();
        assertEquals(0, a.countFor(42));
    }

    @Test
    public void floatingBoundsLeaveAMarginOnEverySide() {
        assertArrayEquals(new int[] {36, 36, 1884, 1164},
                DriveModeWindowPolicy.floatingBounds(0, 0, 1920, 1200));
        // Small displays still get the minimum margin.
        assertArrayEquals(new int[] {24, 24, 776, 576},
                DriveModeWindowPolicy.floatingBounds(0, 0, 800, 600));
        assertNull(DriveModeWindowPolicy.floatingBounds(0, 0, 300, 300));
        assertNull(DriveModeWindowPolicy.floatingBounds(0, 0, 0, 0));
    }

    @Test
    public void settingsCommandsScopeSecureToTheUserAndGlobalToNone() {
        assertEquals("settings get global enable_freeform_support",
                DriveModeWindowPolicy.settingsCommand("get", "global", "enable_freeform_support", null, "--user 10"));
        assertEquals("settings --user 10 put secure freeform_window_management 1",
                DriveModeWindowPolicy.settingsCommand("put", "secure", "freeform_window_management", "1", "--user 10"));
        assertEquals("settings put secure freeform_window_management 1",
                DriveModeWindowPolicy.settingsCommand("put", "secure", "freeform_window_management", "1", ""));
    }

    @Test
    public void restoreDeletesAMissingKeyAndPutsBackAValue() {
        assertEquals("settings delete global force_resizable_activities",
                DriveModeWindowPolicy.restoreCommand("global", "force_resizable_activities", "null", "--user 10"));
        assertEquals("settings put global enable_freeform_support 0",
                DriveModeWindowPolicy.restoreCommand("global", "enable_freeform_support", " 0\n", "--user 10"));
        assertEquals("settings --user 10 delete secure freeform_window_management",
                DriveModeWindowPolicy.restoreCommand("secure", "freeform_window_management", "", "--user 10"));
    }

    @Test
    public void aConfirmedFloatDoesNotUseUpTheBudget() {
        DriveModeWindowPolicy.Attempts a = new DriveModeWindowPolicy.Attempts();
        long now = 10_000L;
        // The user maximises the floating window many times; each float is confirmed.
        for (int i = 0; i < 10; i++) {
            assertTrue(DriveModeWindowPolicy.shouldConvert(true, true, task(FULL, true, false), a, now));
            a.note(42, now);
            a.succeeded(42);
            now += DriveModeWindowPolicy.MIN_ATTEMPT_SPACING_MS;
        }
        // Failures in a row are still bounded.
        for (int i = 0; i < DriveModeWindowPolicy.MAX_ATTEMPTS_PER_TASK; i++) {
            assertTrue(DriveModeWindowPolicy.shouldConvert(true, true, task(FULL, true, false), a, now));
            a.note(42, now);
            now += DriveModeWindowPolicy.MIN_ATTEMPT_SPACING_MS;
        }
        assertFalse(DriveModeWindowPolicy.shouldConvert(true, true, task(FULL, true, false), a, now));
        // A success for another task doesn't clear this one's failures.
        a.succeeded(7);
        assertFalse(DriveModeWindowPolicy.shouldConvert(true, true, task(FULL, true, false), a, now));
    }

    @Test
    public void aFailedReadIsNotMistakenForAMissingKey() {
        assertFalse(DriveModeWindowPolicy.isReadable(""));
        assertFalse(DriveModeWindowPolicy.isReadable(null));
        assertTrue(DriveModeWindowPolicy.isReadable("null"));
        assertTrue(DriveModeWindowPolicy.restoreConfirmed("null", "null"));
        assertFalse("empty readback = command failed", DriveModeWindowPolicy.restoreConfirmed("null", ""));
        assertTrue(DriveModeWindowPolicy.restoreConfirmed("0", "0"));
        assertFalse(DriveModeWindowPolicy.restoreConfirmed("0", "1"));
    }

    @Test
    public void onlyPlainValuesAreRestored() {
        assertTrue(DriveModeWindowPolicy.isRestorableValue("0"));
        assertTrue(DriveModeWindowPolicy.isRestorableValue("null"));
        assertTrue(DriveModeWindowPolicy.isRestorableValue(null));
        assertFalse(DriveModeWindowPolicy.isRestorableValue("1; reboot"));
        assertFalse(DriveModeWindowPolicy.isRestorableValue("$(id)"));
    }

    @Test
    public void freeformIsAvailableWithTheFeatureOrTheSwitch() {
        assertTrue(DriveModeWindowPolicy.freeformAvailable(true, "null"));
        assertTrue(DriveModeWindowPolicy.freeformAvailable(false, "1"));
        assertFalse(DriveModeWindowPolicy.freeformAvailable(false, "0"));
        assertFalse(DriveModeWindowPolicy.freeformAvailable(false, ""));
    }

    @Test
    public void callersAreWired() throws Exception {
        String daemon = read("app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        assertTrue(daemon.contains("com.overdrive.app.launcher.DriveModeWindow.onGear(gear)"));
        String api = read("app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");
        assertTrue(api.contains("\"/api/vehicle/drive-mode-window\""));
        assertTrue(api.contains("\"/api/vehicle/drive-mode-window/resumed\""));
        String cluster = read("app/src/main/java/com/overdrive/app/launcher/ClusterFreeformWindow.java");
        int prepare = cluster.indexOf("static boolean prepareHostedFreeformState()");
        int guard = cluster.indexOf("DriveModeWindow.holdsFreeformSupport()", prepare);
        int restore = cluster.indexOf("settings delete global ", prepare);
        assertTrue("cluster cast keeps freeform on for drive mode", guard > prepare && guard < restore);
        String main = read("app/src/main/java/com/overdrive/app/ui/MainActivity.kt");
        assertTrue(main.contains("DriveModeWindowClient.notifyResumed()"));
        // The worker thread starts only where the feature applies.
        String window = read("app/src/main/java/com/overdrive/app/launcher/DriveModeWindow.java");
        // Settings restore lets the cluster cast re-read freeform; the task's display is resolved
        // through root tasks.
        assertTrue(window.contains("ClusterFreeformWindow.forgetFreeformSettingCheck()"));
        assertTrue(window.contains("ClusterFreeformWindow.taskDisplayId(svc, info, taskId) != 0"));
        int onGear = window.indexOf("public static void onGear(int gear)");
        assertTrue(window.indexOf("if (!appliesHere() || !isEnabled()) return;", onGear) > onGear);
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
