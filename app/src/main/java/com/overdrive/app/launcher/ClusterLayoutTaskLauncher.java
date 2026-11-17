package com.overdrive.app.launcher;

import android.content.ComponentName;
import android.graphics.Rect;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.launcher.layout.ClusterLayoutDisplayEvidence;
import com.overdrive.app.launcher.layout.ClusterLayoutSurfaceHost;
import com.overdrive.app.launcher.layout.ClusterLayoutTaskLaunch;
import com.overdrive.app.launcher.layout.LayoutPreset;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Layout-only adapter for DashCast's Phase4TaskVerbs launchAndForce, empty-stack cleanup,
 * and fission watchdog (MIT, copyright 2026 Cedric Carre). See THIRD_PARTY_NOTICES-dashcast.txt.
 * Each operation is fenced by the Layout generation and its owned private display.
 * RoadSense uses its isolated component; neither force-stop nor -S may stop OverDrive itself.
 */
final class ClusterLayoutTaskLauncher implements ClusterLayoutTaskLaunch.Operations {
    private final ClusterLayoutSurfaceHost.SlotResource resource;
    private final String pkg, component;
    private final long generation;
    private final BooleanSupplier live;
    private final ClusterLayoutTaskLaunch.Watchdog watchdog = new ClusterLayoutTaskLaunch.Watchdog();
    private long nextPoll;
    private int polls, moves;
    private boolean watching;
    private boolean quiet;

    ClusterLayoutTaskLauncher(String pkg, ClusterLayoutSurfaceHost.SlotResource resource,
            long generation, BooleanSupplier live) {
        this.pkg = pkg;
        this.resource = resource;
        this.generation = generation;
        this.live = live;
        String resolved = LayoutPreset.MAP_PACKAGE.equals(pkg)
                ? "com.overdrive.app/.navmap.RoadSenseLayoutMapActivity"
                : AppLauncher.resolveLauncherComponent(pkg);
        component = resolved != null && resolved.startsWith(pkg + "/")
                && resolved.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+") ? resolved : null;
    }

    boolean launch() {
        if (!current() || (LayoutPreset.MAP_PACKAGE.equals(pkg) && component == null)) return false;
        int task = ClusterLayoutTaskLaunch.launch(this, component != null,
                !LayoutPreset.MAP_PACKAGE.equals(pkg));
        if (task <= 0 || !current()) return false;
        watching = true;
        nextPoll = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                ClusterLayoutTaskLaunch.Watchdog.INTERVAL_MS);
        return true;
    }

    /** Called on the existing Layout worker, including while other slots await foreground. */
    void tick() {
        if (!watching || !current()) { watching = false; return; }
        long now = System.nanoTime();
        if (now < nextPoll) return;
        nextPoll = now + TimeUnit.MILLISECONDS.toNanos(ClusterLayoutTaskLaunch.Watchdog.INTERVAL_MS);
        if (polls >= ClusterLayoutTaskLaunch.Watchdog.MAX_POLLS) { finishWatchdog(); return; }
        ClusterLayoutTaskLaunch.Task task = taskLocation();
        if (!current()) { watching = false; return; }
        ClusterLayoutTaskLaunch.Watchdog.Action action = watchdog.onPoll(++polls,
                task.match(resource.virtualDisplayId));
        if (action == ClusterLayoutTaskLaunch.Watchdog.Action.COMPLETE) {
            finishWatchdog();
        } else if (action == ClusterLayoutTaskLaunch.Watchdog.Action.REANCHOR) {
            moves++;
            quiet = !ClusterLayoutTaskLaunch.Watchdog.logMove(moves);
            try {
                ClusterLayoutTaskLaunch.place(this, task.taskId);
                if (!quiet && current()) record("watchdog_reanchor",
                        "task=" + task.taskId + " from=" + task.displayId + " count=" + moves);
            } finally { quiet = false; }
        }
    }

    private void finishWatchdog() {
        watching = false;
        record("watchdog_complete", "polls=" + polls + " moves=" + moves);
    }

    @Override public boolean current() {
        return !Thread.currentThread().isInterrupted() && live.getAsBoolean()
                && resource.generation == generation && resource.virtualDisplayId > 0
                && resource.virtualDisplayId != resource.physicalDisplayId
                && ClusterLayoutDisplayEvidence.ownedDisplayName(resource.displayName, generation)
                && !resource.isReleased() && resource.lastFailure().isEmpty();
    }

    @Override public String stopPackage() {
        if (!current()) return "cancelled";
        if (LayoutPreset.MAP_PACKAGE.equals(pkg)) return "isolated RoadSense component";
        return ClusterLayouts.shell(3000, this::current, "am", "force-stop", "--user", "0", pkg);
    }

    @Override public String start(boolean freeform, boolean explicitComponent) {
        if (!current()) return null;
        if (explicitComponent && component == null) return "Error: no launcher component";
        if (!explicitComponent && LayoutPreset.MAP_PACKAGE.equals(pkg))
            return "Error: RoadSense requires its isolated Layout component";
        List<String> command = launchCommand(pkg, component, resource.virtualDisplayId, generation,
                freeform, explicitComponent);
        if (!current()) return null;
        return ClusterLayouts.shell(5000, this::current, command.toArray(new String[0]));
    }

    static List<String> launchCommand(String pkg, String component, int displayId, long generation,
            boolean freeform, boolean explicitComponent) {
        if (displayId <= 0 || generation <= 0 || !LayoutPreset.validPackage(pkg))
            throw new IllegalArgumentException("invalid_layout_launch_target");
        if (explicitComponent && (component == null || !component.startsWith(pkg + "/")
                || !component.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")))
            throw new IllegalArgumentException("invalid_layout_launch_component");
        if (LayoutPreset.MAP_PACKAGE.equals(pkg) && (!explicitComponent
                || !"com.overdrive.app/.navmap.RoadSenseLayoutMapActivity".equals(component)))
            throw new IllegalArgumentException("roadsense_requires_layout_component");
        List<String> command = new ArrayList<>();
        Collections.addAll(command, "am", "start-activity");
        if (!LayoutPreset.MAP_PACKAGE.equals(pkg)) command.add("-S");
        Collections.addAll(command, "-W", "--user", "0", "--display",
                Integer.toString(displayId));
        if (freeform) Collections.addAll(command, "--windowingMode", "5");
        command.add("--activity-no-animation");
        if (explicitComponent) Collections.addAll(command, "-n", component);
        else Collections.addAll(command, "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER", "-p", pkg);
        if (LayoutPreset.MAP_PACKAGE.equals(pkg)) {
            Collections.addAll(command, "--ez", "cluster", "true", "--el", "layoutGeneration",
                    Long.toString(generation), "--ei", "layoutDisplayId",
                    Integer.toString(displayId));
        }
        return command;
    }

    @Override public ClusterLayoutTaskLaunch.Task pollTask() {
        // DashCast polls sixteen times at 300 ms, distinguishing no task from an unreadable query.
        ClusterLayoutTaskLaunch.Task task = ClusterLayoutTaskLaunch.Task.unknown();
        for (int i = 0; i < 16 && current(); i++) {
            try { Thread.sleep(300); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ClusterLayoutTaskLaunch.Task.unknown();
            }
            if (!current()) return ClusterLayoutTaskLaunch.Task.unknown();
            task = taskLocation();
            if (task.known && task.taskId > 0) return task;
        }
        return task;
    }

    @Override public String singleTask() {
        try {
            Object atm = service();
            if (!current()) return "cancelled";
            atm.getClass().getMethod("setDisplayToSingleTaskInstance", int.class)
                    .invoke(atm, resource.virtualDisplayId);
            return "OK display=" + resource.virtualDisplayId;
        } catch (Throwable error) { return failure(error); }
    }

    @Override public String resizeable(int taskId) {
        return taskVerb(taskId, new String[]{"setTaskResizeable"},
                new Class<?>[]{int.class, int.class}, taskId, 4);
    }

    @Override public String freeform(int taskId) {
        try {
            // AOSP Q changes the containing stack's mode, not just the named task.
            Object atm = service();
            if (exclusiveRoot(stacks(atm), taskId) == null || !current())
                return "exclusive_root_unverified";
        } catch (Throwable error) { return failure(error); }
        return taskVerb(taskId, new String[]{"setTaskWindowingMode", "setCustomTaskWindowingMode"},
                new Class<?>[]{int.class, int.class, boolean.class}, taskId, 5, true);
    }

    private String taskVerb(int taskId, String[] names, Class<?>[] types, Object... args) {
        try {
            Object atm = service();
            if (!ownedTask(atm, taskId) || !current()) return "task_identity_unverified";
            for (String name : names) {
                Method method;
                try { method = atm.getClass().getMethod(name, types); }
                catch (NoSuchMethodException absent) { continue; }
                if (!current()) return "cancelled";
                method.invoke(atm, args);
                return "OK " + name + "(" + taskId + ")";
            }
            return "method_unavailable";
        } catch (Throwable error) { return failure(error); }
    }

    @Override public String move(int taskId) {
        try {
            Object atm = service();
            if (!ownedTask(atm, taskId) || !current()) return "task_identity_unverified";
            Object root = exclusiveRoot(stacks(atm), taskId);
            if (root == null || !current()) return "exclusive_root_unverified";
            int actual = integer(field(root, "displayId"));
            if (actual == resource.virtualDisplayId) return "OK already on private display";
            if (actual < 0) return "source_display_unverified";
            int rootId = rootId(root);
            for (String name : new String[]{"moveStackToDisplay", "moveRootTaskToDisplay"}) {
                Method method;
                try { method = atm.getClass().getMethod(name, int.class, int.class); }
                catch (NoSuchMethodException absent) { continue; }
                if (!current()) return "cancelled";
                method.invoke(atm, rootId, resource.virtualDisplayId);
                return "OK " + name + "(" + rootId + "," + resource.virtualDisplayId + ")";
            }
            return "move_method_unavailable";
        } catch (Throwable error) { return failure(error); }
    }

    @Override public String resize(int taskId) {
        try {
            Object atm = service();
            if (!ownedTask(atm, taskId) || !current()) return "task_identity_unverified";
            // A refused move must not resize a task still on the head unit.
            if (displayForTask(atm, taskId) != resource.virtualDisplayId || !current())
                return "target_display_unverified";
            Rect bounds = new Rect(0, 0, resource.width, resource.height);
            Method method;
            Object result;
            try {
                method = atm.getClass().getMethod("resizeTask", int.class, Rect.class, int.class);
                if (!current()) return "cancelled";
                result = method.invoke(atm, taskId, bounds, 1);
            } catch (NoSuchMethodException absent) {
                method = atm.getClass().getMethod("resizeTask", int.class, Rect.class);
                if (!current()) return "cancelled";
                result = method.invoke(atm, taskId, bounds);
            }
            return Boolean.FALSE.equals(result) ? "SKIP resize refused by windowing mode"
                    : "OK resizeTask(" + taskId + "," + resource.width + "x" + resource.height + ")";
        } catch (Throwable error) { return failure(error); }
    }

    @Override public String focus(int taskId) {
        try {
            Object atm = service();
            if (!ownedTask(atm, taskId) || !current()) return "task_identity_unverified";
            Object root = exclusiveRoot(stacks(atm), taskId);
            if (root == null || integer(field(root, "displayId")) != resource.virtualDisplayId
                    || !current()) return "target_root_unverified";
            Method method;
            int focusId;
            try {
                method = atm.getClass().getMethod("setFocusedRootTask", int.class);
                focusId = rootId(root);
            } catch (NoSuchMethodException absent) {
                method = atm.getClass().getMethod("setFocusedTask", int.class);
                focusId = taskId;
            }
            if (!current()) return "cancelled";
            method.invoke(atm, focusId);
            return "OK " + method.getName() + "(" + focusId + ")";
        } catch (Throwable error) { return failure(error); }
    }

    private ClusterLayoutTaskLaunch.Task taskLocation() {
        try {
            Object atm = service();
            List<ClusterLayoutTaskLaunch.Task> found = new ArrayList<>();
            for (Object task : runningTasks(atm)) {
                if (!matches(task) || activityType(task) == 2) continue;
                int id = taskId(task);
                if (id <= 0) { found.add(ClusterLayoutTaskLaunch.Task.unknown()); continue; }
                int display = integer(field(task, "displayId"));
                if (display < 0) display = displayForTask(atm, id);
                found.add(new ClusterLayoutTaskLaunch.Task(true, id, display));
            }
            if (!current()) return ClusterLayoutTaskLaunch.Task.unknown();
            return selectTask(found, resource.virtualDisplayId);
        } catch (Throwable error) { return ClusterLayoutTaskLaunch.Task.unknown(); }
    }

    static ClusterLayoutTaskLaunch.Task selectTask(List<ClusterLayoutTaskLaunch.Task> tasks,
            int targetDisplay) {
        if (tasks == null) return ClusterLayoutTaskLaunch.Task.unknown();
        ClusterLayoutTaskLaunch.Task expected = null, unknown = null;
        for (ClusterLayoutTaskLaunch.Task task : tasks) {
            if (task == null) continue;
            ClusterLayoutTaskLaunch.Match match = task.match(targetDisplay);
            if (match == ClusterLayoutTaskLaunch.Match.OTHER) return task;
            if (match == ClusterLayoutTaskLaunch.Match.UNKNOWN && unknown == null) unknown = task;
            if (match == ClusterLayoutTaskLaunch.Match.EXPECTED && expected == null) expected = task;
        }
        if (unknown != null) return unknown;
        return expected == null ? ClusterLayoutTaskLaunch.Task.absent() : expected;
    }

    private boolean ownedTask(Object atm, int id) throws Exception {
        if (id <= 0 || !current()) return false;
        for (Object task : runningTasks(atm)) {
            if (taskId(task) == id && matches(task) && activityType(task) != 2) return current();
        }
        return false;
    }

    private boolean matches(Object task) {
        Object top = field(task, "topActivity"), base = field(task, "baseActivity");
        ComponentName t = top instanceof ComponentName ? (ComponentName) top : null;
        ComponentName b = base instanceof ComponentName ? (ComponentName) base : null;
        return ClusterLayoutTaskLaunch.matchesTask(pkg,
                t == null ? null : t.getPackageName(), t == null ? null : t.getClassName(),
                b == null ? null : b.getPackageName(), b == null ? null : b.getClassName());
    }

    /** DashCast's cleanup, including the guarded Android 10 empty-split-stack escalation. */
    @Override public String cleanDisplay() {
        try {
            Object atm = service();
            List<?> initial = stacks(atm);
            Method remove = null;
            for (String name : new String[]{"removeStack", "removeTask"}) {
                try { remove = atm.getClass().getMethod(name, int.class); break; }
                catch (NoSuchMethodException absent) { /* next Android generation */ }
            }
            if (remove == null) return "empty_stack_cleanup_unavailable";
            boolean escalated = false;
            int removed = 0;
            for (Object root : initial) {
                if (!current()) return "cancelled";
                int id = rootId(root);
                if (id < 0 || !removable(root)) continue;
                // Re-read before the write: a stack gaining a task is no longer removable.
                Object fresh = rootById(stacks(atm), id);
                if (fresh == null || !removable(fresh) || !current()) continue;
                try {
                    remove.invoke(atm, id);
                    List<?> after = stacks(atm);
                    if (!current()) return "cancelled";
                    fresh = rootById(after, id);
                    if (fresh == null) { removed++; continue; }
                    if (!escalated && removable(fresh) && allSplitStacksEmpty(after)) {
                        escalated = true;
                        // AOSP Q removeStack can leave the empty stack object behind.
                        for (String name : new String[]{
                                "removeStacksInWindowingModes", "removeRootTasksInWindowingModes"}) {
                            Method dissolve;
                            try { dissolve = atm.getClass().getMethod(name, int[].class); }
                            catch (NoSuchMethodException absent) { continue; }
                            if (!current()) return "cancelled";
                            dissolve.invoke(atm, (Object) new int[]{3, 4});
                            if (rootById(stacks(atm), id) == null) removed++;
                            break;
                        }
                    }
                } catch (Throwable error) {
                    record("clean_stack_" + id, failure(error));
                }
            }
            return "OK empty stacks removed=" + removed;
        } catch (Throwable error) { return failure(error); }
    }

    private boolean removable(Object root) {
        return ClusterLayoutTaskLaunch.removableStack(resource.virtualDisplayId,
                integer(field(root, "displayId")), windowingMode(root), activityType(root), children(root));
    }

    static boolean allSplitStacksEmpty(List<?> roots) {
        if (roots == null || roots.isEmpty()) return false;
        for (Object root : roots) {
            int mode = windowingMode(root);
            if (mode < 0) return false;
            if (mode == 3 || mode == 4) {
                int[] tasks = children(root);
                if (tasks == null || tasks.length != 0) return false;
            }
        }
        return true;
    }

    static Object exclusiveRoot(List<?> roots, int taskId) {
        Object selected = null;
        for (Object root : roots) {
            int[] tasks = children(root);
            if (tasks == null) continue;
            boolean contains = false;
            int liveChildren = 0;
            for (int id : tasks) { if (id > 0) liveChildren++; if (id == taskId) contains = true; }
            if (!contains) continue;
            if (liveChildren != 1 || rootId(root) < 0 || selected != null) return null;
            selected = root;
        }
        return selected;
    }

    private static Object rootById(List<?> roots, int id) {
        for (Object root : roots) if (rootId(root) == id) return root;
        return null;
    }

    private static int displayForTask(Object atm, int taskId) throws Exception {
        for (Object root : stacks(atm)) {
            int[] children = children(root);
            if (children == null) continue;
            for (int id : children) if (id == taskId) return integer(field(root, "displayId"));
        }
        return -1;
    }

    private static int rootId(Object root) {
        Object value = field(root, "stackId");
        return integer(value == null ? field(root, "taskId") : value);
    }

    private static int taskId(Object task) {
        Object value = field(task, "taskId");
        return integer(value == null ? field(task, "id") : value);
    }

    private static int[] children(Object root) {
        Object value = field(root, "taskIds");
        if (!(value instanceof int[])) value = field(root, "childTaskIds");
        return value instanceof int[] ? (int[]) value : null;
    }

    private static List<?> stacks(Object atm) throws Exception {
        Object result;
        try { result = atm.getClass().getMethod("getAllStackInfos").invoke(atm); }
        catch (NoSuchMethodException absent) {
            result = atm.getClass().getMethod("getAllRootTaskInfos").invoke(atm);
        }
        if (result instanceof List) return (List<?>) result;
        if (result instanceof Object[]) return Arrays.asList((Object[]) result);
        throw new IllegalStateException("unreadable_stack_list");
    }

    private static List<?> runningTasks(Object atm) throws Exception {
        for (Method method : atm.getClass().getMethods()) {
            if (!"getTasks".equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == int.class) args[i] = i == 0 ? 64 : 0;
                else if (types[i] == boolean.class) args[i] = false;
            }
            Object result = method.invoke(atm, args);
            if (result instanceof List) return (List<?>) result;
            throw new IllegalStateException("unreadable_running_tasks");
        }
        throw new NoSuchMethodException("getTasks");
    }

    private static Object service() throws Exception {
        Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        if (atm == null) throw new IllegalStateException("activity_task_service_unavailable");
        return atm;
    }

    static int windowingMode(Object root) {
        return windowConfig(root, "getWindowingMode", "windowingMode", new String[]{
                "undefined", "fullscreen", "pinned", "split-screen-primary",
                "split-screen-secondary", "freeform"});
    }

    static int activityType(Object root) {
        return windowConfig(root, "getActivityType", "activityType", new String[]{
                "undefined", "standard", "home", "recents", "assistant"});
    }

    private static int windowConfig(Object root, String getter, String legacy, String[] names) {
        try { return (Integer) root.getClass().getMethod(getter).invoke(root); }
        catch (Throwable ignored) {}
        Object config = field(root, "configuration");
        Object window = field(config, "windowConfiguration");
        try { return (Integer) window.getClass().getMethod(getter).invoke(window); }
        catch (Throwable ignored) {}
        String text = window != null ? window.toString() : config != null ? config.toString() : "";
        String key = getter.equals("getWindowingMode") ? "mWindowingMode=" : "mActivityType=";
        int start = text.indexOf(key);
        if (start >= 0) {
            String tail = text.substring(start + key.length());
            for (int i = 0; i < names.length; i++) if (tail.startsWith(names[i])) return i;
        }
        return integer(field(root, legacy));
    }

    private static Object field(Object value, String name) {
        if (value == null) return null;
        try { Field f = value.getClass().getField(name); f.setAccessible(true); return f.get(value); }
        catch (Throwable unavailable) { return null; }
    }

    private static int integer(Object value) { return value instanceof Integer ? (Integer) value : -1; }

    private static String failure(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                ? error.getCause() : error;
        return "WARN " + cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
    }

    @Override public void record(String step, String result) {
        if (quiet) return;
        String text = result == null ? "unavailable" : result.replace('\n', ' ').replace('\r', ' ');
        if (text.length() > 700) text = text.substring(0, 700);
        CameraDaemon.log("Cluster layouts: launch package=" + pkg + " generation=" + generation
                + " display=" + resource.virtualDisplayId + " step=" + step + " " + text);
    }
}
