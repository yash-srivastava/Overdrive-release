package com.overdrive.app.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared helper for launching an installed Android app and enumerating the
 * launchable apps for a picker UI. Used by BOTH the automation "open app"
 * action and the key-mapping "openApp" action so there is a single launch
 * path and a single app-list source of truth.
 *
 * <p>Launch strategy: prefer {@link PackageManager#getLaunchIntentForPackage}
 * + {@code startActivity} from the daemon's shared app Context (works on this
 * SDK-25 target with no package-visibility {@code <queries>} needed), and fall
 * back to a shell {@code am start} — the same mechanism the daemon already uses
 * to (re)launch our own app and the sidecar services. Runs in the daemon
 * (UID 2000 = shell), so both paths are permitted.
 */
public final class AppLauncher {
    private static final DaemonLogger logger = DaemonLogger.getInstance("AppLauncher");
    // ponytail: one global lock; split by display only if launch contention becomes measurable.
    private static final Object APP_LAUNCH_LOCK = new Object();

    private AppLauncher() {}

    /**
     * Launch the given package (full-screen) by resolving its default launcher activity.
     *
     * @param pkg the target application package name
     * @return true if a launch was dispatched (Intent or shell), false otherwise
     */
    public static boolean launch(String pkg) {
        return launch(pkg, false);
    }

    /**
     * Open two selected apps together in split-screen without changing the legacy
     * one-app split action. The first app is explicitly placed in split-primary,
     * then the second is explicitly placed in split-secondary. This avoids relying
     * on Android to promote the current foreground task to the opposite pane, which
     * is not reliable when either selected app already has a task.
     *
     * <p>Unlike {@link #launch(String, boolean)}, this deliberately has no fullscreen
     * fallback: a two-app automation must report failure if the requested pair was not
     * dispatched as a split pair.
     */
    public static boolean launchSplitPair(String primaryPkg, String secondaryPkg) {
        if (primaryPkg == null || secondaryPkg == null) {
            logger.warn("openAppPair: missing package");
            return false;
        }
        String primary = primaryPkg.trim();
        String secondary = secondaryPkg.trim();
        if (!isValidSplitPair(primary, secondary)) {
            logger.warn("openAppPair: invalid or duplicate package");
            return false;
        }

        // Resolve both before changing the foreground app, so an uninstalled selection
        // fails without disturbing the user's current screen.
        String primaryComponent = resolveLauncherComponent(primary);
        String secondaryComponent = resolveLauncherComponent(secondary);
        if (primaryComponent == null || secondaryComponent == null) {
            logger.warn("openAppPair: could not resolve both launcher components");
            return false;
        }

        synchronized (APP_LAUNCH_LOCK) {
            if (!dockSplitScreen(primary, primaryComponent)
                    || !waitForPackageInWindowingMode(
                            primary, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)) {
                logger.warn("openAppPair: could not dock primary app " + primary);
                return false;
            }
            if (!dockSplitScreenSecondary(secondary, secondaryComponent)
                    || !waitForPackageInWindowingMode(
                            primary, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)) {
                logger.warn("openAppPair: split pair did not settle into separate panes");
                return false;
            }
        }
        logger.info("openAppPair: opened " + primary + " + " + secondary + " in split-screen");
        return true;
    }

    /**
     * Launch {@code pkg} onto a specific display ({@code displayId}: head-unit 0 /
     * cluster 1). Resolves the launcher component (trusted PackageManager/cmd resolver)
     * and dispatches {@code am start-activity --display}. Package charset is validated
     * and the component shell-quoted, exactly like the split-screen path. Returns false
     * on a bad package or if the component can't be resolved / the launch fails.
     */
    public static boolean launchOnDisplay(String pkg, int displayId) {
        synchronized (APP_LAUNCH_LOCK) {
            return launchOnDisplayLocked(pkg, displayId);
        }
    }

    private static boolean launchOnDisplayLocked(String pkg, int displayId) {
        if (pkg == null || pkg.trim().isEmpty() || !isValidPackageName(pkg.trim())) {
            logger.warn("launchOnDisplay: missing/invalid package");
            return false;
        }
        pkg = pkg.trim();
        String component = resolveLauncherComponent(pkg);
        if (component == null) {
            logger.warn("launchOnDisplay: could not resolve component for " + pkg);
            return false;
        }
        boolean ok = runShell("am start-activity --user 0 --display " + displayId
                + " -n " + shellQuote(component));
        logger.info("launchOnDisplay: " + pkg + " → display " + displayId + " (" + component + ") ok=" + ok);
        return ok;
    }

    /**
     * Last-resort DI5 launch recovery: remove an existing task whose old display affinity prevents
     * {@code --display N} from creating it on the OEM shared projection display. Package names are
     * validated before reaching the shell. This is intentionally package-private and is used only
     * after the normal launch + task-move paths have both failed.
     */
    static boolean forceStopPackage(String pkg) {
        synchronized (APP_LAUNCH_LOCK) {
            if (pkg == null || !isValidPackageName(pkg.trim())) return false;
            String normalized = pkg.trim();
            boolean stopped = runShell(
                    "am force-stop --user 0 " + shellQuote(normalized));
            logger.info("forceStopPackage: " + normalized + " ok=" + stopped);
            return stopped;
        }
    }

    /**
     * Launch the given package normally or through the legacy one-app split action.
     * Split failures retain the existing fullscreen fallback.
     */
    public static boolean launch(String pkg, boolean split) {
        synchronized (APP_LAUNCH_LOCK) {
            return launchLocked(pkg, split);
        }
    }

    private static boolean launchLocked(String pkg, boolean split) {
        if (pkg == null || pkg.trim().isEmpty()) {
            logger.warn("openApp: missing package");
            return false;
        }
        pkg = pkg.trim();

        // Validate the package-name charset before it ever reaches getLaunchIntentForPackage
        // or the `monkey -p <pkg>` shell. This is the last line of defense for the keymap
        // path (which does not go through AppType validation) — a package name is
        // [A-Za-z0-9._], so anything else is rejected rather than passed to the shell.
        if (!isValidPackageName(pkg)) {
            logger.warn("openApp: rejected invalid package name");
            return false;
        }

        if (split) {
            String component = resolveLauncherComponent(pkg);
            if (component != null) {
                if (dockSplitScreen(pkg, component)) {
                    logger.info("openApp: docked " + pkg + " in split-screen (" + component + ")");
                    return true;
                }
                logger.warn("openApp: split-screen dock failed for " + component + " — falling back to full-screen");
            } else {
                logger.warn("openApp: could not resolve launcher component for " + pkg + " — falling back to full-screen");
            }
            return launchLocked(pkg, false);
        }

        // 1) Intent path via the daemon's app Context (cleanest; no shell).
        Context ctx = CameraDaemon.getAppContext();
        if (ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    // NEW_TASK required because the daemon Context is not an Activity.
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    // Force the head unit (display 0). A plain startActivity RESUMES the
                    // app's EXISTING task on its last-bound display — after a cluster cast
                    // (am start --display <fissionId>) that affinity sticks to the task, so
                    // reopening from the launcher / a keymap button would resume it on the
                    // now-invisible cluster display and nothing shows on infotainment.
                    // setLaunchDisplayId(0) pulls the task back to display 0. Targeting the
                    // DEFAULT display needs no permission; for a never-cast app already on 0
                    // it's a no-op resume. Best-effort — fall through to shell if it throws.
                    try {
                        android.os.Bundle opts = android.app.ActivityOptions.makeBasic()
                                .setLaunchDisplayId(0).toBundle();
                        ctx.startActivity(launch, opts);
                        logger.info("openApp: launched " + pkg + " via Intent (display 0)");
                        return true;
                    } catch (Throwable optErr) {
                        logger.warn("openApp: setLaunchDisplayId(0) start failed (" + optErr.getMessage()
                                + ") — retrying plain start");
                        ctx.startActivity(launch);
                        logger.info("openApp: launched " + pkg + " via Intent (no display opt)");
                        return true;
                    }
                }
                logger.info("openApp: no launch intent for " + pkg + " — trying shell");
            } catch (Throwable t) {
                logger.warn("openApp: Intent launch failed for " + pkg + " (" + t.getMessage() + ") — trying shell");
            }
        }

        // 2) Shell fallback. Prefer the explicit display-0 component launch — the SAME
        //    `am start-activity --user 0 --display 0 -n <component>` the move-to-head-unit
        //    path already uses — so a task carrying a stale cluster affinity (from a prior
        //    cast) is pulled back to the head unit on reopen. Bare `monkey` is the last
        //    resort only when the component can't be resolved; such apps were never castable
        //    (ClusterCast.start bails on a null component) so they can't hold a stale
        //    cluster affinity anyway.
        if (launchOnDisplayLocked(pkg, 0)) {
            logger.info("openApp: launched " + pkg + " via am start-activity (display 0)");
            return true;
        }
        if (runShell("monkey -p " + shellQuote(pkg) + " -c android.intent.category.LAUNCHER 1")) {
            logger.info("openApp: launched " + pkg + " via monkey");
            return true;
        }
        logger.warn("openApp: all launch paths failed for " + pkg);
        return false;
    }

    /** Ghost activity component used to seed a split-screen-primary stack for the
     *  re-dock path (must match the AndroidManifest entry). */
    private static final String GHOST_COMPONENT =
            "com.overdrive.app/com.overdrive.app.launcher.AppLauncherGhostActivity";
    private static final String SECONDARY_GHOST_COMPONENT =
            "com.overdrive.app/com.overdrive.app.launcher.AppLauncherSecondaryGhostActivity";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3;
    private static final int WINDOWING_MODE_SPLIT_SCREEN_SECONDARY = 4;
    /** Bounded polls waiting for the ghost stack / re-docked task to materialise. */
    private static final int SPLIT_POLL_ATTEMPTS = 6;
    private static final int SPLIT_PAIR_POLL_ATTEMPTS = 12;
    private static final long SPLIT_POLL_MS = 250L;
    private static final int STACK_LOOKUP_FAILED = -2;

    /**
     * {@link #findTaskId}/{@link #taskIdInDump} tri-state: the dumpsys capture failed or did
     * not look like a task dump (no {@code Task{}/TaskRecord{}} header at all), so "the task
     * is absent" could NOT be confirmed. Distinct from -1, which means the dump parsed and
     * the package has no live task. Boot-time recovery treats -1 as "nothing stranded —
     * recovered" and this value as "cannot verify — retry", so an early-boot AMS hiccup can
     * never clear a stranded-task marker while the task might still exist.
     */
    static final int TASK_LOOKUP_FAILED = -2;

    /**
     * Dock {@code pkg} into split-screen (SPLIT_SCREEN_PRIMARY / windowingMode 3).
     *
     * <p>Two cases, because {@code am start --windowingMode 3} only docks an app on
     * its INITIAL launch — if the app is already running/backgrounded/full-screen,
     * that same command just re-focuses it full-screen and the screen never splits
     * (the bug this method fixes):
     * <ol>
     *   <li><b>Not running:</b> the plain {@code am start --windowingMode 3 -n
     *   <component>} docks it directly — the original field-tested path. We keep it
     *   verbatim (shell-quoted component) and treat its exit-0 as success.
     *   <li><b>Already running:</b> we launch the transparent {@link
     *   AppLauncherGhostActivity} into windowingMode 3 to FORCE a split-screen-primary
     *   stack into existence, find that stack's id, then {@code am stack move-task}
     *   the app's existing task into it so it fills the other half.
     * </ol>
     *
     * <p>The stack id is read from {@code dumpsys activity activities} (which emits
     * {@code mWindowingMode=<mode>} on this firmware — verified on-car by
     * ClusterMapProjector), NOT from {@code am stack list} (whose API-29 StackInfo
     * output carries no windowing mode, which is why the previous attempt always
     * parsed an empty id). We require both OUR ghost component and a standard primary
     * stack, accepting either named or numeric windowing-mode output.
     *
     * <p>Best-effort: ANY failure (no task found, ghost stack didn't appear,
     * move-task non-zero) returns false; the legacy one-app action falls back to
     * full-screen while the deterministic pair action reports failure. Runs on the
     * caller's daemon/HTTP worker thread (never the UI thread); all shells are
     * bounded by {@code runShell}'s 5s cap.
     */
    private static boolean dockSplitScreen(String pkg, String component) {
        // Case 1: app not already running → the original single-shot dock, which is
        // the ONLY thing that works for a cold app. Component is trusted + shell-quoted.
        int existingTask = findTaskId(pkg);
        if (existingTask < 0) {
            return runShell("am start --user 0 --display 0 --windowingMode "
                    + WINDOWING_MODE_SPLIT_SCREEN_PRIMARY + " -n " + shellQuote(component));
        }

        // Case 2: app already running → seed a split-primary stack with the ghost,
        // then move the app's existing task onto it.
        logger.info("openApp: " + pkg + " already running (task " + existingTask
                + ") — re-docking via ghost split stack");
        if (!runShell("am start --user 0 --display 0 --windowingMode "
                + WINDOWING_MODE_SPLIT_SCREEN_PRIMARY + " -n "
                + shellQuote(GHOST_COMPONENT))) {
            logger.warn("openApp: ghost split-stack launch failed");
            return false;
        }

        // The ghost's stack is created asynchronously; poll for it rather than a fixed
        // sleep (mirrors ClusterMapProjector's launch→verify pattern).
        int ghostStack = -1;
        for (int i = 0; i < SPLIT_POLL_ATTEMPTS && ghostStack < 0; i++) {
            sleepQuietly(SPLIT_POLL_MS);
            ghostStack = findGhostStackId();
            if (ghostStack == STACK_LOOKUP_FAILED) return false;
        }
        if (ghostStack < 0) {
            logger.warn("openApp: ghost split stack did not appear — re-dock aborted");
            return false;
        }

        // move-task <taskId> <stackId> true  → the trailing bool is toTop. Re-read the
        // task id: it is stable for a running app, but a re-resolve costs nothing and
        // guards against the app dying between the two dumpsys reads.
        int task = findTaskId(pkg);
        if (task < 0) task = existingTask;
        boolean moved = runShell("am stack move-task " + task + " " + ghostStack + " true");
        if (!moved) {
            logger.warn("openApp: move-task " + task + " → stack " + ghostStack + " failed");
            return false;
        }
        logger.info("openApp: re-docked task " + task + " into split stack " + ghostStack);
        return true;
    }

    /**
     * Put the second selected app in the opposite split pane. A mode-4 launch handles
     * cold tasks. Android deliberately keeps an already-running task in its existing
     * stack, so verify the secondary stack and reparent that task when necessary.
     */
    private static boolean dockSplitScreenSecondary(String pkg, String component) {
        if (!runShell("am start --user 0 --display 0 --windowingMode "
                + WINDOWING_MODE_SPLIT_SCREEN_SECONDARY + " -n " + shellQuote(component))) {
            return false;
        }

        if (waitForPackageInWindowingMode(
                pkg, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY)) {
            return true;
        }

        int stack = findSplitStackOnDisplay(
                pkg, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, false);
        if (stack == STACK_LOOKUP_FAILED) return false;
        if (stack < 0) stack = seedSecondarySplitStack();
        int task = findTaskId(pkg);
        if (stack < 0 || task < 0) {
            logger.warn("openAppPair: secondary task/stack did not materialise for " + pkg);
            return false;
        }
        if (!runShell("am stack move-task " + task + " " + stack + " true")) {
            logger.warn("openAppPair: could not move task " + task
                    + " into secondary stack " + stack);
            return false;
        }
        return waitForPackageInWindowingMode(
                pkg, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
    }

    private static int seedSecondarySplitStack() {
        if (!runShell("am start --user 0 --display 0 --windowingMode "
                + WINDOWING_MODE_SPLIT_SCREEN_SECONDARY + " -n "
                + shellQuote(SECONDARY_GHOST_COMPONENT))) {
            return -1;
        }
        int stack = -1;
        for (int i = 0; i < SPLIT_POLL_ATTEMPTS && stack < 0; i++) {
            sleepQuietly(SPLIT_POLL_MS);
            stack = findSecondaryGhostStackId();
            if (stack == STACK_LOOKUP_FAILED) return -1;
        }
        return stack;
    }

    private static boolean waitForPackageInWindowingMode(String pkg, int windowingMode) {
        for (int i = 0; i < SPLIT_PAIR_POLL_ATTEMPTS; i++) {
            int stack = findSplitStackOnDisplay(pkg, windowingMode, true);
            if (stack >= 0) {
                return true;
            }
            if (stack == STACK_LOOKUP_FAILED) return false;
            sleepQuietly(SPLIT_POLL_MS);
        }
        return false;
    }

    private static int findSplitStackOnDisplay(
            String pkg, int windowingMode, boolean requirePackage) {
        String out = runShellCapture(
                "dumpsys activity activities | grep -E 'Display #|Stack #|ActivityStack|"
                        + "mWindowingMode|windowingMode|mode=|mActivityType|activityType|type=|"
                        + pkg + "/'");
        if (out == null) return STACK_LOOKUP_FAILED;
        return splitStackIdInDump(
                out, 0, windowingMode, requirePackage ? pkg : null);
    }

    /** Package-private parser seam for the JVM regression test. */
    static int splitStackIdInDump(
            String out, int displayId, int windowingMode, String requiredPkg) {
        if (windowingMode != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                && windowingMode != WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
            return -1;
        }
        if (requiredPkg != null && !isValidPackageName(requiredPkg)) return -1;
        return standardStackIdInDump(
                out, displayId, windowingMode,
                requiredPkg == null ? null : requiredPkg + "/");
    }

    private static int standardStackIdInDump(
            String out, int displayId, int windowingMode, String requiredComponentToken) {
        if (out == null
                || displayId < 0
                || (windowingMode != WINDOWING_MODE_FULLSCREEN
                        && windowingMode != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                        && windowingMode != WINDOWING_MODE_SPLIT_SCREEN_SECONDARY)) {
            return -1;
        }
        String displayHeader = "display #" + displayId;
        boolean inDisplay = false;
        int stack = -1;
        boolean modeMatches = false;
        boolean standardMatches = false;
        boolean componentMatches = requiredComponentToken == null;
        for (String rawLine : out.split("\\r?\\n")) {
            String line = rawLine.trim();
            String low = line.toLowerCase(java.util.Locale.US);
            int display = low.indexOf("display #");
            if (display >= 0) {
                if (inDisplay && stack >= 0 && modeMatches
                        && standardMatches && componentMatches) return stack;
                inDisplay = low.startsWith(displayHeader, display)
                        && !Character.isDigit(charAt(
                                low, display + displayHeader.length()));
                stack = -1;
                modeMatches = false;
                standardMatches = false;
                componentMatches = requiredComponentToken == null;
                continue;
            }
            if (!inDisplay) continue;

            int nextStack = extractStackId(line);
            if (nextStack >= 0 && nextStack != stack) {
                if (stack >= 0 && modeMatches && standardMatches && componentMatches) return stack;
                stack = nextStack;
                modeMatches = false;
                standardMatches = false;
                componentMatches = requiredComponentToken == null;
            }
            if (stack < 0) continue;
            modeMatches |= lineHasWindowingMode(low, windowingMode);
            standardMatches |= lineHasStandardActivityType(low);
            componentMatches |= requiredComponentToken != null
                    && lineContainsComponentToken(line, requiredComponentToken);
        }
        return inDisplay && stack >= 0 && modeMatches && standardMatches && componentMatches
                ? stack : -1;
    }

    private static boolean lineHasWindowingMode(String low, int windowingMode) {
        String name = windowingMode == WINDOWING_MODE_FULLSCREEN
                ? "fullscreen"
                : windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                        ? "split-screen-primary" : "split-screen-secondary";
        if (low.contains(name)) return true;
        String numeric = Integer.toString(windowingMode);
        return hasAssignment(low, "mwindowingmode=", numeric)
                || hasAssignment(low, "windowingmode=", numeric);
    }

    private static boolean lineHasStandardActivityType(String low) {
        return low.contains("type=standard")
                || hasAssignment(low, "mactivitytype=", "1")
                || hasAssignment(low, "activitytype=", "1");
    }

    private static boolean hasAssignment(String line, String key, String value) {
        int at = line.indexOf(key);
        if (at < 0) return false;
        int start = at + key.length();
        return line.startsWith(value, start)
                && !Character.isDigit(charAt(line, start + value.length()));
    }

    /**
     * Task id of the (top) task hosting {@code pkg}, or -1 if the package has no task
     * (i.e. not currently running). Parsed from {@code dumpsys activity activities},
     * whose task headers look like {@code * Task{... #123 ... A=<affinity> U=0 ...}}
     * followed by the task's activities. We match the task whose recorded package is
     * {@code pkg} by scanning each task block's first component reference.
     */
    static int findTaskId(String pkg) {
        // Grep to the task-header + component lines so the parse stays cheap and the
        // output small. On API 29 the task-header class is `TaskRecord{...#<id>...}`
        // (newer platforms print `Task{...}`); match BOTH so this works across the
        // firmware range. A following "<pkg>/" component line ties a task to its
        // package. (`.` in pkg is a regex any-char here but charset-limited so the
        // over-match is benign; attribution below re-checks with a literal "<pkg>/".)
        String out = runShellCapture(
                "dumpsys activity activities | grep -E 'TaskRecord\\{|Task\\{|" + pkg + "/'");
        return taskIdInDump(out, pkg);
    }

    /**
     * True only when a PARSED dump proves {@code pkg} has no live task. False both when a
     * task exists and when the lookup itself failed ({@link #TASK_LOOKUP_FAILED}) — callers
     * use this as the post-{@code force-stop} "the task is really gone" proof, and a failed
     * dumpsys must never pass for proof.
     */
    static boolean taskConfirmedAbsent(String pkg) {
        // Prefer the ActivityTaskManager running-task list: it only knows LIVE tasks,
        // whereas the dumpsys parser can be fooled by a root task's stale
        // mLastPausedActivity reference to an already-destroyed activity (Android 12
        // shared cluster displays) and then never confirms absence.
        int live = ClusterFreeformWindow.liveTaskIdForPackage(pkg);
        if (live != TASK_LOOKUP_FAILED) return live == -1;
        return findTaskId(pkg) == -1;
    }

    /** Package-private parser seam for the JVM regression test. */
    static int taskIdInDump(String out, String pkg) {
        if (!isValidPackageName(pkg)) return -1;
        if (out == null) return TASK_LOOKUP_FAILED;   // shell/dumpsys failure — unverifiable
        int pendingTaskId = -1;
        boolean sawTaskHeader = false;
        for (String line : out.split("\\r?\\n")) {
            line = line.trim();
            int hashTask = extractTaskId(line);
            if (hashTask >= 0) {
                sawTaskHeader = true;
                pendingTaskId = hashTask;          // remember the task we're inside
                continue;
            }
            // A component line "<pkg>/<activity>" inside the current task block.
            // Literal "<pkg>/" (not a bare contains(pkg)) so a sibling package like
            // "com.foobar" can't be mistaken for "com.foo".
            // Skip a task's bookkeeping references (mLastPausedActivity, mResumedActivity,
            // mPausingActivity, ...): a root task keeps pointing at an activity record
            // after that activity's child task was destroyed, which made a force-stopped
            // app look permanently present (Android 12 shared cluster displays).
            if (pendingTaskId >= 0
                    && !isTaskStateReferenceLine(line)
                    && lineContainsComponentToken(line, pkg + "/")) {
                return pendingTaskId;
            }
        }
        // A live system always has at least one task (home/SystemUI), so a capture with
        // ZERO task headers is dumpsys erroring out (AMS not up yet, permission refusal),
        // not proof of absence. Only a parsed dump that contains other tasks but none for
        // this package confirms the task is gone.
        return sawTaskHeader ? -1 : TASK_LOOKUP_FAILED;
    }

    /** {@code mLastPausedActivity: ...}, {@code mResumedActivity: ...}, {@code topResumedActivity=...}
     *  and similar fields describe a task's remembered state, not a live member activity. */
    static boolean isTaskStateReferenceLine(String trimmedLine) {
        if (trimmedLine == null) return false;
        String low = trimmedLine.toLowerCase(java.util.Locale.US);
        return low.startsWith("mlastpausedactivity")
                || low.startsWith("mresumedactivity")
                || low.startsWith("mpausingactivity")
                || low.startsWith("mlastresumedactivity")
                || low.startsWith("topresumedactivity")
                || low.startsWith("mfocusedapp")
                || low.startsWith("mlastnonfullscreenbounds");
    }

    private static boolean lineContainsComponentToken(String line, String token) {
        if (!token.endsWith("/")) return line.contains(token);
        for (int at = line.indexOf(token); at >= 0; at = line.indexOf(token, at + 1)) {
            if (at == 0 || !isPackageNameChar(line.charAt(at - 1))) return true;
        }
        return false;
    }

    private static boolean isPackageNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '.' || c == '_';
    }

    /**
     * Stack id of the display-0 standard split-primary stack hosting OUR ghost activity.
     * Named and numeric windowing-mode output are both accepted. -1 if not found.
     */
    private static int findGhostStackId() {
        String out = runShellCapture(
                "dumpsys activity activities | grep -E 'Display #|Stack #|ActivityStack|"
                        + "mWindowingMode|windowingMode|mode=|mActivityType|activityType|type=|"
                        + "AppLauncherGhostActivity'");
        if (out == null) return STACK_LOOKUP_FAILED;
        return ghostStackIdInDump(out);
    }

    /** Package-private parser seam for the JVM regression test. */
    static int ghostStackIdInDump(String out) {
        return standardStackIdInDump(
                out, 0, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY,
                "AppLauncherGhostActivity");
    }

    private static int findSecondaryGhostStackId() {
        String out = runShellCapture(
                "dumpsys activity activities | grep -E 'Display #|Stack #|ActivityStack|"
                        + "mWindowingMode|windowingMode|mode=|mActivityType|activityType|type=|"
                        + "AppLauncherSecondaryGhostActivity'");
        if (out == null) return STACK_LOOKUP_FAILED;
        return secondaryGhostStackIdInDump(out);
    }

    /** Package-private parser seam for the JVM regression test. */
    static int secondaryGhostStackIdInDump(String out) {
        return standardStackIdInDump(
                out, 0, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
                "AppLauncherSecondaryGhostActivity");
    }

    /**
     * Reparent {@code pkg}'s task onto a fullscreen stack of display 0 (the head unit)
     * WITHOUT bringing it to the foreground, so a task carrying a stale cluster-display
     * affinity (from a prior {@link ClusterCast}) resumes on the infotainment the next time
     * it is opened — instead of on the now-invisible fission cluster display.
     *
     * <p><b>Why this exists.</b> On Android 10, {@code setLaunchDisplayId(0)} /
     * {@code am start --display 0} reliably place a NEW task on display 0 but do NOT
     * reparent an ALREADY-RUNNING task off a still-registered secondary display — a matched
     * standard task is merely brought forward on its CURRENT display. The OEM fission
     * VirtualDisplay outlives its projection close (invisible, not removed), so AMS never
     * auto-reparents the cast task, and every reopen (launcher tap, keymap, notification)
     * resumes it on a dead display. {@code am stack move-task <task> <display-0 stack> false}
     * is the operation that actually relocates a live task; {@code toTop=false} keeps it in
     * the background so nothing pops onto the head unit while driving.
     *
     * <p>Must be called WHILE the fission display is still live (a supported live-to-live
     * reparent) — {@link ClusterCast#stop(boolean)} invokes it before releasing the
     * projection hold. Best-effort: any parse/stack-resolution failure is a logged no-op
     * (never a foregrounding fallback that could surface the app while driving). Runs on the
     * caller's daemon/HTTP worker thread; all shells are bounded by {@link #runShell}'s 5s cap.
     *
     * @return true when the package is confirmed OFF the cluster: either a move-task was
     *   dispatched (exit 0), or the parsed dump proves the package has NO live task — an
     *   absent task carries no display affinity, so there is nothing stranded to recover
     *   and callers may clear their recovery markers. False when the move could not be
     *   dispatched OR the task lookup itself failed ({@link #TASK_LOOKUP_FAILED}), in which
     *   case the task may still be stranded and recovery must be retried. The previous
     *   contract returned false for a confirmed-absent task too, which made boot recovery
     *   retry forever on every boot where the stranded app was simply not running
     *   (log_2MEH8B86: "no live task ... nothing to reparent" → permanent
     *   "legacy projection boot recovery incomplete").
     */
    static boolean reparentToDisplay0(String pkg) {
        synchronized (APP_LAUNCH_LOCK) {
            return reparentToDisplay0Locked(pkg);
        }
    }

    private static boolean reparentToDisplay0Locked(String pkg) {
        if (pkg == null || pkg.trim().isEmpty() || !isValidPackageName(pkg.trim())) {
            logger.warn("reparentToDisplay0: missing/invalid package");
            return false;
        }
        pkg = pkg.trim();
        // Live-task list first (see taskConfirmedAbsent): the dump parser can hand back a
        // root task that merely REMEMBERS the package's destroyed activity, and moving a
        // root task would drag the OEM's cluster container along with it.
        int task = ClusterFreeformWindow.liveTaskIdForPackage(pkg);
        if (task == TASK_LOOKUP_FAILED) task = findTaskId(pkg);
        if (task == TASK_LOOKUP_FAILED) {
            logger.warn("reparentToDisplay0: task lookup failed for " + pkg
                    + " — cannot verify the task is gone, keeping recovery pending");
            return false;
        }
        if (task < 0) {
            logger.info("reparentToDisplay0: no live task for " + pkg
                    + " — nothing stranded, treating as recovered");
            return true;
        }
        int stack = findFullscreenStackIdOnDisplay(0);
        if (stack < 0) {
            // Android 12+ prints root Tasks instead of `Stack #` headers, so the dump
            // parser has nothing to find on DiLink 5.0. Use the ActivityTaskManager
            // root-task reparent (same single-task, background semantics) before
            // giving up; the caller keeps its recovery marker on a false return.
            boolean movedViaAtm =
                    ClusterFreeformWindow.moveTaskToDefaultDisplayBackground(task);
            if (movedViaAtm) {
                logger.info("reparentToDisplay0: task " + task + " (" + pkg
                        + ") re-homed to display 0 via ActivityTaskManager");
                return true;
            }
            logger.warn("reparentToDisplay0: no display-0 fullscreen stack found — skipping (no clobber)");
            return false;
        }
        // toTop=false: reparent in the BACKGROUND so we never pop the foreign app onto the
        // head unit while driving; it simply resumes there on the user's next open.
        boolean moved = runShell("am stack move-task " + task + " " + stack + " false");
        logger.info("reparentToDisplay0: move-task " + task + " → display-0 stack " + stack
                + " (" + pkg + ") ok=" + moved);
        return moved;
    }

    /**
     * Standard fullscreen stack id on {@code displayId}. Split-screen and home stacks are
     * deliberately rejected so a cluster-affinity cleanup cannot replace one of an active
     * split pair. Returns -1 when no safe target exists.
     */
    private static int findFullscreenStackIdOnDisplay(int displayId) {
        String out = runShellCapture(
                "dumpsys activity activities | grep -E 'Display #|Stack #|ActivityStack|"
                        + "mWindowingMode|windowingMode|mode=|mActivityType|activityType|type='");
        return fullscreenStackIdInDump(out, displayId);
    }

    /** Package-private parser seam for the JVM regression test. */
    static int fullscreenStackIdInDump(String out, int displayId) {
        return standardStackIdInDump(out, displayId, WINDOWING_MODE_FULLSCREEN, null);
    }

    /** Char at index {@code i}, or a space if out of range (safe bounds for the
     *  Display-header digit-boundary guard in {@link #findFullscreenStackIdOnDisplay}). */
    private static char charAt(String s, int i) {
        return (i >= 0 && i < s.length()) ? s.charAt(i) : ' ';
    }

    /** Parse the "#<n>" id from a {@code Task{...#123...}} header line, else -1. */
    private static int extractTaskId(String line) {
        if (!line.contains("Task{") && !line.contains("TaskRecord{")) return -1;
        return extractHashNumber(line);
    }

    /** Parse the id from a {@code Stack #123} / {@code ActivityStack{...#123}} header, else -1. */
    private static int extractStackId(String line) {
        if (!line.contains("Stack #") && !line.contains("ActivityStack")) return -1;
        return extractHashNumber(line);
    }

    /** First {@code #<digits>} run in {@code line} as an int, or -1 if none. */
    private static int extractHashNumber(String line) {
        int hash = line.indexOf('#');
        while (hash >= 0 && hash + 1 < line.length()) {
            int j = hash + 1;
            while (j < line.length() && Character.isDigit(line.charAt(j))) j++;
            if (j > hash + 1) {
                try {
                    return Integer.parseInt(line.substring(hash + 1, j));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
            hash = line.indexOf('#', hash + 1);
        }
        return -1;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * List launchable installed apps as a JSON array of {package, label},
     * sorted by label. Used to populate the picker in the automation and
     * key-mapping UIs.
     */
    public static JSONArray listLaunchableApps() {
        List<JSONObject> apps = new ArrayList<>();
        Context ctx = CameraDaemon.getAppContext();
        if (ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
                launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> resolved = pm.queryIntentActivities(launcherIntent, 0);
                for (ResolveInfo ri : resolved) {
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null) continue;
                    String label = null;
                    try {
                        CharSequence l = ri.loadLabel(pm);
                        if (l != null) label = l.toString();
                    } catch (Throwable ignored) { }
                    if (label == null || label.isEmpty()) label = pkg;
                    apps.add(makeApp(pkg, label));
                }
            } catch (Throwable t) {
                logger.warn("listLaunchableApps: PackageManager query failed (" + t.getMessage() + ")");
            }
        }
        // Shell fallback if the PackageManager path produced nothing (e.g. broken
        // daemon Context) — package names only, no friendly labels.
        if (apps.isEmpty()) {
            for (String pkg : listPackagesViaShell()) {
                apps.add(makeApp(pkg, pkg));
            }
        }

        // De-dup by package FIRST (a package can expose several launcher
        // activities, sometimes with different labels — those would not be
        // adjacent after a label sort, so an adjacent-only pass would miss them),
        // then sort by label. We launch by package, so one entry per package.
        java.util.LinkedHashMap<String, JSONObject> byPkg = new java.util.LinkedHashMap<>();
        for (JSONObject app : apps) {
            String pkg = app.optString("package", "");
            if (pkg.isEmpty() || byPkg.containsKey(pkg)) continue;
            byPkg.put(pkg, app);
        }
        List<JSONObject> unique = new ArrayList<>(byPkg.values());
        Collections.sort(unique, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return a.optString("label", "").compareToIgnoreCase(b.optString("label", ""));
            }
        });
        JSONArray out = new JSONArray();
        for (JSONObject app : unique) out.put(app);
        return out;
    }

    /**
     * List only user-installed launchable apps for Service Mode. System packages are
     * intentionally excluded so the service-mode picker cannot accidentally disable
     * BYD/OEM components or Android system applications.
     */
    public static JSONArray listUserLaunchableApps() {
        List<JSONObject> apps = new ArrayList<>();
        Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) return new JSONArray();

        try {
            PackageManager pm = ctx.getPackageManager();
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(launcherIntent, 0);
            java.util.LinkedHashMap<String, JSONObject> byPackage =
                    new java.util.LinkedHashMap<>();
            for (ResolveInfo ri : resolved) {
                if (ri == null || ri.activityInfo == null
                        || ri.activityInfo.applicationInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.isEmpty()
                        || "com.overdrive.app".equals(pkg)) continue;
                ApplicationInfo appInfo = ri.activityInfo.applicationInfo;
                if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;

                String label = pkg;
                try {
                    CharSequence value = ri.loadLabel(pm);
                    if (value != null && value.length() > 0) label = value.toString();
                } catch (Throwable ignored) { }
                if (!byPackage.containsKey(pkg)) {
                    byPackage.put(pkg, makeApp(pkg, label));
                }
            }
            apps.addAll(byPackage.values());
        } catch (Throwable t) {
            logger.warn("listUserLaunchableApps failed: " + t.getMessage());
            return new JSONArray();
        }

        Collections.sort(apps, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return a.optString("label", "")
                        .compareToIgnoreCase(b.optString("label", ""));
            }
        });
        JSONArray out = new JSONArray();
        for (JSONObject app : apps) out.put(app);
        return out;
    }

    private static JSONObject makeApp(String pkg, String label) {
        JSONObject o = new JSONObject();
        try {
            o.put("package", pkg);
            o.put("label", label);
        } catch (JSONException ignored) { }
        return o;
    }

    /**
     * Shell fallback for enumerating packages when PackageManager is unavailable.
     * {@code pm list packages} lists all installed packages (we can't cheaply
     * filter to launchable-only over shell, so the picker labels are package
     * names in this degraded path).
     */
    private static List<String> listPackagesViaShell() {
        List<String> pkgs = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap("pm list packages -3"))
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n));
            p.waitFor(5, TimeUnit.SECONDS);
            try { is.close(); } catch (Throwable ignored) { }
            for (String line : sb.toString().split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("package:")) {
                    String pkg = line.substring("package:".length()).trim();
                    if (!pkg.isEmpty()) pkgs.add(pkg);
                }
            }
        } catch (Throwable t) {
            logger.warn("listPackagesViaShell failed: " + t.getMessage());
        }
        return pkgs;
    }

    /** Run a short shell command bounded at 5s; true if it exited 0. */
    private static boolean runShell(String cmd) {
        try {
            final Process p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd))
                    .redirectErrorStream(true).start();
            final InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] b = new byte[4096];
                try { while (is.read(b) != -1) { /* discard */ } } catch (Throwable ignored) { }
            }, "applauncher-drain");
            drain.setDaemon(true);
            drain.start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); }
            drain.join(500);
            try { is.close(); } catch (Throwable ignored) { }
            return done && p.exitValue() == 0;
        } catch (Throwable t) {
            logger.warn("runShell failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Run a short shell command bounded at 5s and return its (stdout+stderr) output,
     * or null on failure/timeout. Output is capped at {@link #SHELL_CAPTURE_CAP_BYTES}
     * so a large {@code dumpsys} dump can't balloon memory — callers grep upstream to
     * keep the stream small, and this is a hard backstop. Used by the split-screen
     * re-dock parse; mirrors the capture pattern already in {@link #resolveLauncherComponent}.
     */
    private static String runShellCapture(String cmd) {
        Process p = null;
        InputStream is = null;
        try {
            p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd)).redirectErrorStream(true).start();
            is = p.getInputStream();
            final InputStream captureStream = is;
            final StringBuffer sb = new StringBuffer();
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while ((n = captureStream.read(buf)) != -1) {
                        int remaining = SHELL_CAPTURE_CAP_BYTES - sb.length();
                        if (remaining > 0) {
                            sb.append(new String(buf, 0, Math.min(n, remaining)));
                        }
                        // Keep draining past the cap so the child never blocks on a full pipe.
                    }
                } catch (Throwable ignored) {
                }
            }, "applauncher-capture");
            reader.setDaemon(true);
            reader.start();

            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            reader.join(500);
            if (reader.isAlive()) {
                try { is.close(); } catch (Throwable ignored) { }
                reader.join(500);
            }
            if (!done) return null;
            return sb.toString();
        } catch (Throwable t) {
            logger.warn("runShellCapture failed: " + t.getMessage());
            return null;
        } finally {
            if (is != null) { try { is.close(); } catch (Throwable ignored) { } }
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
        }
    }

    /** Hard cap on captured shell output (see {@link #runShellCapture}). */
    private static final int SHELL_CAPTURE_CAP_BYTES = 256 * 1024;

    /**
     * Resolve the {@code package/activity} launcher component for a package.
     * Split-screen's {@code am start -n} needs the explicit component. Tries the
     * PackageManager (daemon Context) first, then a shell
     * {@code cmd package resolve-activity} fallback. Returns null if neither
     * yields a component.
     *
     * <p>Package-private so the same-package {@link ClusterCast} can reuse this
     * trusted resolver for the projection-aware driver-cluster launch.
     */
    static String resolveLauncherComponent(String pkg) {
        Context ctx = CameraDaemon.getAppContext();
        if (ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null && launch.getComponent() != null) {
                    return launch.getComponent().flattenToShortString();
                }
            } catch (Throwable t) {
                logger.warn("resolveLauncherComponent: PackageManager failed for " + pkg + " (" + t.getMessage() + ")");
            }
        }
        // Shell fallback: parse `cmd package resolve-activity --brief` for the component.
        try {
            String out = runShellCapture(
                    "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "
                            + shellQuote(pkg));
            if (out == null) return null;
            for (String line : out.split("\\r?\\n")) {
                line = line.trim();
                // A resolved component line looks like "pkg/.Activity" or "pkg/pkg.Activity".
                if (line.startsWith(pkg + "/")) return line;
            }
        } catch (Throwable t) {
            logger.warn("resolveLauncherComponent: shell resolve failed for " + pkg + " (" + t.getMessage() + ")");
        }
        return null;
    }

    /**
     * True if {@code pkg} is installed and has a resolvable launcher activity (i.e. it can
     * actually be cast/launched). Used by callers to distinguish an "app no longer installed"
     * failure from a generic one. Charset-validated first so a bad name never hits the resolver.
     */
    public static boolean isLaunchable(String pkg) {
        if (pkg == null || pkg.trim().isEmpty() || !isValidPackageName(pkg.trim())) return false;
        return resolveLauncherComponent(pkg.trim()) != null;
    }

    /** Minimal shell quoting for a package name (defensive; pkg names are [A-Za-z0-9._]). */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** True if the string is a plausible Android package name ([A-Za-z0-9._], 1..255).
     *  Package-private so {@link ClusterCast} validates before its projection open. */
    static boolean isValidPackageName(String s) {
        if (s == null || s.isEmpty() || s.length() > 255) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!isPackageNameChar(s.charAt(i))) return false;
        }
        return true;
    }

    static boolean isValidSplitPair(String primaryPkg, String secondaryPkg) {
        return isValidPackageName(primaryPkg)
                && isValidPackageName(secondaryPkg)
                && !primaryPkg.equals(secondaryPkg);
    }
}
