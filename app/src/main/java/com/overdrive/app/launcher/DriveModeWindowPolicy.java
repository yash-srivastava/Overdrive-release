package com.overdrive.app.launcher;

/**
 * Pure decisions for the BYD100F "drive mode window" (#1002).
 *
 * <p>On BYD100F (Android 14 Automotive) the car service covers any full-screen activity that is
 * not on its driving allowlist as soon as the car leaves P. It only governs the display's
 * full-screen task, so OverDrive's own task is moved into a large floating (freeform) window,
 * where it stays usable. This class holds the rules; {@link DriveModeWindow} does the I/O.
 */
final class DriveModeWindowPolicy {

    static final int WINDOWING_MODE_UNDEFINED = 0;
    static final int WINDOWING_MODE_FULLSCREEN = 1;
    static final int WINDOWING_MODE_FREEFORM = 5;

    /** Conversion attempts for one task before we stop trying until a new task appears. */
    static final int MAX_ATTEMPTS_PER_TASK = 3;
    /** Minimum spacing between two attempts, so gear and resume triggers never stack up. */
    static final long MIN_ATTEMPT_SPACING_MS = 1_000L;
    /** Window inset from each display edge, as a fraction of the shorter side. */
    static final double MARGIN_FRACTION = 0.03;
    static final int MIN_MARGIN_PX = 24;
    static final int MIN_WINDOW_PX = 400;

    /**
     * The settings that turn freeform windows on. The first two are the platform's own; the third
     * is not read by the platform but was part of the recipe verified on the car, so it is set and
     * restored with the others.
     */
    static final String[][] SETTINGS = {
            {"global", "enable_freeform_support"},
            {"global", "force_resizable_activities"},
            {"secure", "freeform_window_management"},
    };

    private DriveModeWindowPolicy() {
    }

    /** OverDrive's head-unit task on display 0, as last read from the task list. */
    static final class TaskView {
        final int taskId;
        final int windowingMode;
        final boolean visible;
        /** The car's driving-restriction screen is the top task and covers ours. */
        final boolean coveredByCar;

        TaskView(int taskId, int windowingMode, boolean visible, boolean coveredByCar) {
            this.taskId = taskId;
            this.windowingMode = windowingMode;
            this.visible = visible;
            this.coveredByCar = coveredByCar;
        }
    }

    /** Per-task attempt bookkeeping, owned by the single worker thread. */
    static final class Attempts {
        int taskId = -1;
        int count;
        long lastMs;

        /** Count a new attempt for {@code taskId}; a different task starts a fresh budget. */
        void note(int taskId, long nowMs) {
            if (taskId != this.taskId) {
                this.taskId = taskId;
                count = 0;
            }
            count++;
            lastMs = nowMs;
        }

        int countFor(int taskId) {
            return taskId == this.taskId ? count : 0;
        }

        void reset() {
            taskId = -1;
            count = 0;
            lastMs = 0L;
        }
    }

    /**
     * Whether to move OverDrive's task into a floating window now. Only a full-screen task that
     * is on screen (or covered by the car's restriction screen) is moved; split-screen and other
     * modes are the user's choice and are left alone.
     */
    static boolean shouldConvert(boolean enabled, boolean platform, TaskView task,
            Attempts attempts, long nowMs) {
        if (!enabled || !platform || task == null || task.taskId < 0) return false;
        if (task.windowingMode != WINDOWING_MODE_FULLSCREEN
                && task.windowingMode != WINDOWING_MODE_UNDEFINED) {
            return false;
        }
        if (!task.visible && !task.coveredByCar) return false;
        if (attempts.countFor(task.taskId) >= MAX_ATTEMPTS_PER_TASK) return false;
        return attempts.lastMs <= 0L || nowMs - attempts.lastMs >= MIN_ATTEMPT_SPACING_MS;
    }

    /**
     * Floating-window bounds inside the full-screen bounds {@code l,t,r,b}: the display minus a
     * small margin on every side. Null when the display is too small to leave a usable window.
     */
    static int[] floatingBounds(int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        if (w <= 0 || h <= 0) return null;
        int margin = Math.max(MIN_MARGIN_PX, (int) Math.round(Math.min(w, h) * MARGIN_FRACTION));
        int[] out = {l + margin, t + margin, r - margin, b - margin};
        if (out[2] - out[0] < MIN_WINDOW_PX || out[3] - out[1] < MIN_WINDOW_PX) return null;
        return out;
    }

    /** {@code settings get} output for a value that is switched on. */
    static boolean isOn(String value) {
        return value != null && "1".equals(value.trim());
    }

    /** A {@code settings get} that ran: it prints a value or {@code null}, never nothing. */
    static boolean isReadable(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** The readback after a restore matches what was there before. */
    static boolean restoreConfirmed(String prior, String readback) {
        if (!isReadable(readback)) return false;
        return isMissing(prior) ? "null".equals(readback.trim()) : prior.trim().equals(readback.trim());
    }

    /** {@code settings get} prints {@code null} for a missing key. */
    static boolean isMissing(String value) {
        return value == null || value.trim().isEmpty() || "null".equals(value.trim());
    }

    /**
     * A {@code settings} command. Global settings are shared by all users; secure ones are
     * per user, so they carry the target user's {@code --user} flag.
     */
    static String settingsCommand(String verb, String namespace, String key, String value,
            String userFlag) {
        String user = userFlag == null || userFlag.isEmpty() || "global".equals(namespace)
                ? "" : userFlag + " ";
        return "settings " + user + verb + " " + namespace + " " + key
                + (value == null ? "" : " " + value);
    }

    /** The shell command that puts {@code key} back to {@code prior} (deleting a missing key). */
    static String restoreCommand(String namespace, String key, String prior, String userFlag) {
        if (isMissing(prior)) return settingsCommand("delete", namespace, key, null, userFlag);
        return settingsCommand("put", namespace, key, prior.trim(), userFlag);
    }

    /** A setting value is safe to restore only if it is a plain token (no shell metacharacters). */
    static boolean isRestorableValue(String prior) {
        return isMissing(prior) || prior.trim().matches("[A-Za-z0-9._-]{1,32}");
    }

    /** Freeform is usable this boot when the device has the feature or the switch is on. */
    static boolean freeformAvailable(boolean hasFeature, String enableFreeformValue) {
        return hasFeature || isOn(enableFreeformValue);
    }
}
