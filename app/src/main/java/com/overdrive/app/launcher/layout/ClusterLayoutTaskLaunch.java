package com.overdrive.app.launcher.layout;

/**
 * DashCast's launchAndForce and FissionWatchdogPolicy, adapted to a Layout-owned display.
 * Upstream: Kiroha/byd-dashcast, commit 174fcf89 (MIT, copyright 2026 Cedric Carre).
 * See THIRD_PARTY_NOTICES-dashcast.txt.
 *
 * The caller supplies privileged operations and checks the resulting foreground before reveal.
 * Cancellation is checked after reads as well as before every subsequent write.
 */
public final class ClusterLayoutTaskLaunch {
    private ClusterLayoutTaskLaunch() {}

    public interface Operations {
        boolean current();
        String cleanDisplay();
        String stopPackage();
        String start(boolean freeform, boolean explicitComponent);
        Task pollTask();
        String singleTask();
        String resizeable(int taskId);
        String freeform(int taskId);
        String move(int taskId);
        String resize(int taskId);
        String focus(int taskId);
        void record(String step, String result);
    }

    public enum Match { EXPECTED, OTHER, ABSENT, UNKNOWN }

    public static final class Task {
        public final boolean known;
        public final int taskId, displayId;

        public Task(boolean known, int taskId, int displayId) {
            this.known = known;
            this.taskId = taskId;
            this.displayId = displayId;
        }

        public static Task unknown() { return new Task(false, -1, -1); }
        public static Task absent() { return new Task(true, -1, -1); }

        public Match match(int expectedDisplayId) {
            if (!known) return Match.UNKNOWN;
            if (taskId <= 0) return Match.ABSENT;
            if (displayId < 0 || expectedDisplayId <= 0) return Match.UNKNOWN;
            return displayId == expectedDisplayId ? Match.EXPECTED : Match.OTHER;
        }
    }

    /** Freeform component → MAIN fallback → clean/plain retry → force placement. */
    public static int launch(Operations ops, boolean componentAvailable, boolean allowImplicit) {
        try {
            requireCurrent(ops);
            record(ops, "clean", ops.cleanDisplay());
            requireCurrent(ops);
            record(ops, "stop", ops.stopPackage());
            boolean started = false, poisoned = false;
            if (componentAvailable) {
                requireCurrent(ops);
                String out = ops.start(true, true);
                record(ops, "start_freeform_component", out);
                started = !startFailed(out);
                poisoned = freeformStackFailed(out);
            }
            if (!started && !poisoned && allowImplicit) {
                requireCurrent(ops);
                String out = ops.start(true, false);
                record(ops, "start_freeform_main", out);
                started = !startFailed(out);
            }

            Task task;
            if (started) {
                requireCurrent(ops);
                task = ops.pollTask();
                requireCurrent(ops);
            } else {
                task = retryPlain(ops, componentAvailable);
            }
            // Some BYD builds print a successful start but create no task at all.
            if (started && task.taskId <= 0) task = retryPlain(ops, componentAvailable);
            if (!task.known || task.taskId <= 0) {
                record(ops, "failed", "no task discovered");
                return -1;
            }

            requireCurrent(ops);
            record(ops, "single_task", ops.singleTask());
            requireCurrent(ops);
            record(ops, "force_resizeable", ops.resizeable(task.taskId));
            if (!place(ops, task.taskId)) return -1;
            record(ops, "complete", "task=" + task.taskId);
            return task.taskId;
        } catch (Cancelled stopped) {
            return -1;
        }
    }

    private static Task retryPlain(Operations ops, boolean explicitComponent) {
        requireCurrent(ops);
        record(ops, "reclean", ops.cleanDisplay());
        requireCurrent(ops);
        record(ops, "start_plain", ops.start(false, explicitComponent));
        requireCurrent(ops);
        Task task = ops.pollTask();
        requireCurrent(ops);
        return task;
    }

    /** A move can reset FREEFORM to FULLSCREEN, so the second mode call is required. */
    public static boolean place(Operations ops, int taskId) {
        try {
            requireCurrent(ops);
            record(ops, "freeform_before_move", ops.freeform(taskId));
            requireCurrent(ops);
            record(ops, "move", ops.move(taskId));
            requireCurrent(ops);
            record(ops, "freeform_after_move", ops.freeform(taskId));
            requireCurrent(ops);
            record(ops, "resize", ops.resize(taskId));
            requireCurrent(ops);
            record(ops, "focus", ops.focus(taskId));
            return true;
        } catch (Cancelled stopped) {
            return false;
        }
    }

    public static boolean startFailed(String out) {
        // Our bounded shell returns null for a timeout/transport error.
        return out == null || out.contains("Error:")
                || out.contains("Exception occurred while executing");
    }

    public static boolean freeformStackFailed(String out) {
        return out != null && (out.contains("ActivityStack.getBounds()")
                || (out.contains("ActivityStack.onConfigurationChanged")
                && out.contains("NullPointerException")));
    }

    /** Same 500 ms, 30 s initial / 15 s after a move / 90 s maximum as DashCast. */
    public static final class Watchdog {
        public static final long INTERVAL_MS = 500;
        public static final int INITIAL_POLLS = 60, AFTER_MOVE_POLLS = 30, MAX_POLLS = 180;
        private int completionPoll = INITIAL_POLLS;

        public enum Action { WAIT, REANCHOR, COMPLETE }

        public Action onPoll(int poll, Match match) {
            if (poll <= 0 || match == null) throw new IllegalArgumentException("invalid_watchdog_poll");
            if (match == Match.OTHER) {
                completionPoll = Math.min(MAX_POLLS, Math.max(completionPoll, poll + AFTER_MOVE_POLLS));
                return Action.REANCHOR;
            }
            if (poll >= MAX_POLLS || (match == Match.EXPECTED && poll >= completionPoll))
                return Action.COMPLETE;
            return Action.WAIT;
        }

        public static boolean logMove(int count) { return count <= 5 || count % 20 == 0; }
    }

    /** Only empty non-fullscreen/non-home stacks can be cleaned, as in DashCast. */
    public static boolean removableStack(int targetDisplay, int actualDisplay, int mode,
            int activityType, int[] children) {
        return targetDisplay > 0 && actualDisplay == targetDisplay && children != null
                && children.length == 0 && mode != 1 && activityType != 2
                && (mode >= 0 || activityType >= 0);
    }

    /** A RoadSense pane must never select OverDrive's head-unit or full-cluster task. */
    public static boolean matchesTask(String pkg, String topPkg, String topClass,
            String basePkg, String baseClass) {
        if (LayoutPreset.MAP_PACKAGE.equals(pkg)) {
            String activity = "com.overdrive.app.navmap.RoadSenseLayoutMapActivity";
            return (pkg.equals(topPkg) && activity.equals(topClass))
                    || (pkg.equals(basePkg) && activity.equals(baseClass));
        }
        return pkg != null && (pkg.equals(topPkg) || pkg.equals(basePkg));
    }

    private static void record(Operations ops, String step, String result) {
        ops.record(step, result == null ? "unavailable" : result);
        requireCurrent(ops);
    }

    private static void requireCurrent(Operations ops) {
        if (!ops.current()) throw new Cancelled();
    }

    private static final class Cancelled extends RuntimeException {}
}
