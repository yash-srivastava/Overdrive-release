package com.overdrive.app.launcher;

import android.graphics.Rect;

import com.overdrive.app.camera.dilink5.ClusterTransport;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts a cast app into FREEFORM windowing at explicit bounds on the driver-cluster
 * ("fission") display and resizes it in place — the mechanism that lets the Projection
 * screen's box actually shrink/move the app ON the cluster. All work is
 * {@code IActivityTaskManager} reflection from the daemon (uid 2000 / shell, targetSdk 25 so
 * hidden-API exempt), the same privileged surface the reference cluster-cast app uses.
 *
 * <h3>Why the old three-call sequence silently did nothing</h3>
 * The previous implementation issued {@code setTaskResizeable} →
 * {@code setTaskWindowingMode(FREEFORM)} → {@code resizeTask(bounds)} and reported success as
 * soon as the reflected methods DISPATCHED — it returned {@code freeform && sized} where both
 * flags meant only "no exception escaped", never "AMS honoured it".
 *
 * <p>The decisive detail, from the platform source
 * ({@code ActivityTaskManagerService.resizeTask}, android-29 line 3217):
 * <pre>
 *   if (!task.getWindowConfiguration().canResizeTask())
 *       throw new IllegalArgumentException("resizeTask not allowed on task=" + task);
 * </pre>
 * and {@code WindowConfiguration.canResizeTask()} is literally
 * {@code mWindowingMode == WINDOWING_MODE_FREEFORM}. So {@code resizeTask} <b>throws</b> unless
 * the task is ALREADY in freeform. If the preceding mode change did not actually take — and on a
 * cluster VirtualDisplay that does not advertise freeform support, {@code getOrCreateStack(
 * WINDOWING_MODE_FREEFORM, …)} can fail to move it — then the resize threw
 * {@code IllegalArgumentException}, the old code swallowed it at {@code debug} level, and
 * {@code applyBounds} STILL returned true. Nothing moved, nothing was reported: the exact
 * no-error/no-movement symptom.
 *
 * <p><b>And one layer deeper.</b> Getting the task INTO freeform is itself gated:
 * {@code ActivityDisplay.isWindowingModeSupported} has
 * {@code if (!supportsFreeform && windowingMode == WINDOWING_MODE_FREEFORM) return false;}, where
 * {@code supportsFreeform} is {@code hasSystemFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
 * Settings.Global.enable_freeform_support != 0}. A car head unit ships neither by default, so the
 * mode change is silently resolved away and the resize then throws. See
 * {@link #ensureFreeformSupportEnabled()} — note AMS caches that setting at boot, so enabling it
 * only takes effect after a restart. This is why an honest {@code applied=false} matters: on such a
 * build the feature genuinely cannot work yet, and the log says so instead of pretending.
 *
 * <p>{@code setTaskWindowingModeSplitScreenPrimary(taskId, createMode, toTop, animate, bounds,
 * showRecents)} never produces FREEFORM (its 2nd parameter is a {@code SPLIT_SCREEN_CREATE_MODE_*});
 * it docks the task at {@code bounds}, which is the legacy cluster's placement path — see
 * {@link #applyDocked}. {@code setStackWindowingMode}/{@code setActivityStackWindowingMode} do not
 * exist on API 29 at all
 * (the only stack-level entry points are {@code moveTaskToStack}/{@code moveStackToDisplay}), so
 * that rung is a no-op there and is kept only for builds that do expose it.
 *
 * <h3>Escalation ladder (each rung is best-effort; verification decides the verdict)</h3>
 * <pre>
 *   resolve taskId (display-scoped)  →  ensureFreeformSupportEnabled()
 *   setTaskResizeable(taskId, FORCE_RESIZEABLE)
 *   [stack rungs, only where they exist AND the stack holds no other task — see Safety]
 *       setStackWindowingMode(stackId, FREEFORM)   // absent on API 29
 *       resizeStack(stackId, bounds, …)
 *   setTaskWindowingMode(taskId, FREEFORM, true)   // THE call that matters — must precede resize
 *   resizeTask(taskId, bounds, RESIZE_MODE_USER)   // throws unless already FREEFORM
 *   setFocusedRootTask/setFocusedTask(taskId)   // the latter on API 29
 *   verify getTaskBounds(taskId) ≈ bounds  →  else shell `cmd activity task resize`
 * </pre>
 *
 * <h3>Why NOT {@code am start --windowingMode 5}</h3>
 * Bare freeform-at-launch drops the app into AMS's DEFAULT freeform rect
 * ({@code Rect(825,0-1095,720)} — the "small projection" symptom) and can leave the task in a
 * dirty state. The reliable path is: launch NORMALLY (fullscreen, the proven {@link ClusterCast}
 * path), let it RESUME, then convert the live task with bounds attached. Resizing later is the
 * same call on the already-running task (no relaunch, no flicker).
 *
 * <h3>Safety — this cannot disturb the nav-map or blind-spot</h3>
 * Everything here is scoped to the CAST APP's OWN task, identified by package <b>AND</b> display:
 * <ul>
 *   <li><b>Display scoping is the primary guard</b> ({@link #findTaskIdViaAtm}). Matching on
 *       package alone is NOT sufficient, because a package can own tasks on several displays at
 *       once — and {@code com.overdrive.app} is itself a launchable package that owns the head-unit
 *       UI task AND the nav-map cluster task ({@code .navmap.RoadSenseClusterMapActivity}). An
 *       unscoped lookup could therefore return the MAP's task and hand its stack to the resize
 *       ladder. Every resolve is pinned to the cast's own fission display
 *       ({@code ClusterCast.castDisplayId}); a package match on any OTHER display is REFUSED, and
 *       a resize is REFUSED outright while that display is still unresolved (rather than falling
 *       back to an unscoped lookup). The reference scopes by display for the same reason.</li>
 *   <li>The resize path deliberately does NOT use display-level verbs such as
 *       {@code setDisplayToSingleTaskInstance} or stack cleanup. The DI5 launch guardian has one
 *       narrowly-scoped move helper, but it refuses unless the task's root contains exactly one
 *       live task, so it cannot drag a co-resident map/head-unit task to another display.</li>
 *   <li>The stack rungs are additionally gated on the stack being <b>exclusively ours</b>
 *       ({@link #exclusiveStackIdForTask}): if the resolved task's stack hosts any other task we
 *       SKIP them and rely on the task-level calls. Stricter than the reference, which
 *       drives the stack unconditionally.</li>
 *   <li>Blind-spot is a SurfaceControl LAYER (no task at all — structurally immune).</li>
 *   <li>Belt-and-braces: the cast picker itself filters OverDrive out of the app list
 *       ({@code VehicleControlApiHandler.handleClusterApps}), so a self-cast cannot normally be
 *       requested in the first place.</li>
 * </ul>
 * Every call is individually guarded: a missing method or denied reflection returns false and
 * the caller simply keeps the app fullscreen (the pre-existing behaviour).
 */
final class ClusterFreeformWindow {

    private static final String TAG = "ClusterFreeform";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // WindowConfiguration windowing-mode ints (stable across the API 28→30 range this firmware
    // spans). Passed as raw literals rather than reflected constants, as the reference does.
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM   = 5;
    // ActivityManager.RESIZE_MODE_* : 4 = FORCE_RESIZEABLE (make a non-resizeable task
    // resizeable so a freeform mode change sticks), 1 = USER (preserve-window resize).
    private static final int RESIZE_MODE_FORCE_RESIZEABLE = 4;
    private static final int RESIZE_MODE_USER             = 1;

    /** {@code Settings.Global.DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT}. AMS reads this ONCE at
     *  {@code retrieveSettings()} into {@code mSupportsFreeformWindowManagement}, and
     *  {@code ActivityDisplay.isWindowingModeSupported} refuses FREEFORM outright when it is false
     *  — so on a vehicle without {@code FEATURE_FREEFORM_WINDOW_MANAGEMENT} EVERY freeform verb is
     *  a guaranteed no-op until this is set and AMS re-reads it. We set it (best-effort) so the
     *  capability at least exists on a later boot; it does NOT take effect for the current AMS
     *  instance, which is why the verdict can legitimately be MISMATCH on the first cast. */
    private static final String SETTING_ENABLE_FREEFORM = "enable_freeform_support";

    /** Per-edge slack when verifying {@code getTaskBounds} against what we asked for. AMS may
     *  clamp for decor insets / minimum sizes, so an exact match is too strict — but this is
     *  tight enough to still catch the failure that matters (the task snapping back to the full
     *  panel, which is hundreds of px off). */
    private static final int VERIFY_TOLERANCE_PX = 48;

    // Cached IActivityTaskManager handle (resolved once; null = unavailable on this build).
    private static volatile Object atm;
    private static volatile boolean atmResolved;

    // Resolved-once method handles for the hot path. `resizeTask` is called on every live drag
    // frame, so re-scanning getMethods() each time would be pure overhead.
    private static volatile Method cachedResizeTask;
    private static volatile boolean freeformSettingChecked;
    private static volatile boolean freeformRecoveryChecked;
    private static volatile String freeformSettingState = "unchecked";
    private static volatile boolean freeformCurrentBootSupported;
    private static final ConcurrentHashMap<String, Integer> freeformLosses =
            new ConcurrentHashMap<>();
    private static final java.util.Set<String> freeformSuppressed =
            ConcurrentHashMap.newKeySet();
    private static volatile boolean freeformSuppressionLoaded;
    private static final String FREEFORM_RECOVERY_KEY = "clusterFreeformRecovery";
    private static final String FREEFORM_SUPPRESSION_KEY = "clusterFreeformSuppression";

    /**
     * Immutable task-cache snapshot for the LIVE-DRAG fast path. Resolving a task id costs an ATM
     * round-trip (or, on the shell fallback, a whole {@code dumpsys}+grep) — far too heavy at the
     * box view's ~16fps emit rate.
     *
     * <p>The package, task id and freeform-converted flag live in ONE object published by a single
     * volatile write, because they must be read CONSISTENTLY. Held as three separate volatile
     * fields they could be read torn: a reader that sampled {@code taskId} for package A, then had
     * the cache invalidated and repopulated for A with a NEW task id, would go on to see a matching
     * package and return the STALE id — and AMS recycles task ids, so that resizes whatever app now
     * owns that number. One snapshot per write makes that interleaving unrepresentable.
     */
    private static final class TaskCache {
        final String pkg;
        final int taskId;
        /** The display this entry was resolved AGAINST (-1 = resolved with no display scoping).
         *  Part of the cache KEY, not just payload: an entry resolved unscoped must never be
         *  reused once a real cluster display is known, or it would silently bypass the display
         *  filter that keeps us off the nav map's task. */
        final int displayId;
        /** True once this exact task was successfully converted to freeform AND verified, so the
         *  drag path can skip the mode/stack rungs and issue bounds-only updates. */
        final boolean converted;
        /** True once we have ISSUED any windowing write against this task (mode change and/or
         *  stack resize), whether or not AMS honoured the requested rect.
         *
         *  <p>This is what {@link #restoreFullscreen} must gate on — NOT {@link #converted}. The
         *  mode/stack rungs can dispatch successfully while the subsequent {@code resizeTask} still
         *  throws, so a commit can leave the task (and its stack) in FREEFORM at cluster bounds and
         *  yet report {@code applied=false}. Gating the undo on the stricter flag would skip the
         *  teardown in exactly that case and leave a freeform stack on the shared cluster display
         *  for the nav map to inherit. */
        final boolean touched;
        /** The {@link #cacheGeneration} this snapshot was resolved under. A snapshot whose
         *  generation is stale is discarded rather than published, so a resolve that was in flight
         *  across an {@link #invalidateTaskCache()} cannot resurrect a dead task id. */
        final long generation;

        TaskCache(String pkg, int taskId, int displayId, boolean converted, boolean touched,
                  long generation) {
            this.pkg = pkg;
            this.taskId = taskId;
            this.displayId = displayId;
            this.converted = converted;
            this.touched = touched;
            this.generation = generation;
        }
    }

    /**
     * One ATM task-location snapshot used by the DI5 post-launch guardian.
     *
     * <p>{@code known=false} means the task service/query itself was unavailable, while
     * {@code known=true, taskId=-1} means the query completed and the package genuinely had no
     * task. Keeping those outcomes separate prevents a transient Binder/read failure from being
     * mistaken for an app rebound and triggering an unnecessary relaunch.
     */
    static final class TaskLocation {
        final boolean known;
        final int taskId;
        final int displayId;
        /** Windowing mode from this same task row; null is not evidence of a living freeform task. */
        final Integer windowingMode;

        TaskLocation(boolean known, int taskId, int displayId) {
            this(known, taskId, displayId, null);
        }

        TaskLocation(boolean known, int taskId, int displayId, Integer windowingMode) {
            this.known = known;
            this.taskId = taskId;
            this.displayId = displayId;
            this.windowingMode = windowingMode;
        }

        static TaskLocation unknown() { return new TaskLocation(false, -1, -1); }
        static TaskLocation absent() { return new TaskLocation(true, -1, -1); }
    }

    private static volatile TaskCache taskCache;
    /** Bumped by every {@link #invalidateTaskCache()}. Guarded by the class monitor for writes;
     *  read volatile-ly so an in-flight resolve can detect that it raced an invalidation. */
    private static volatile long cacheGeneration;

    private ClusterFreeformWindow() {}

    /** Drop the task-id + conversion cache. Called by {@link ClusterCast} whenever a cast starts
     *  or stops, so a stale (possibly AMS-recycled) task id can never be resized. Bumping the
     *  generation also invalidates any resolve currently in flight on another thread. */
    static void invalidateTaskCache() {
        synchronized (ClusterFreeformWindow.class) {
            cacheGeneration++;
            taskCache = null;
        }
    }

    static void beginDirectPlacementAttempt() {
        synchronized (ClusterFreeformWindow.class) {
            // Re-read ownership and the live setting for each direct launch. A previous
            // attempt must not suppress reassertion after external drift or a failed write.
            freeformRecoveryChecked = false;
            freeformSettingChecked = false;
            freeformSettingState = "unchecked";
        }
        ensureFreeformSupportEnabled();
    }

    /**
     * The live cache snapshot for {@code pkg} resolved against {@code displayId}, or null when
     * absent / for a different package / resolved against a DIFFERENT display.
     *
     * <p>The display is part of the key. Without that, an entry populated early — while
     * {@code ClusterCast.castDisplayId} was still -1, so the resolve fell back to the unscoped
     * {@code dumpsys} lookup — would keep being served after the launch thread published the real
     * cluster id, letting an off-display task (for our own package: the head-unit UI or the nav
     * map) be resized despite the filter. A display change forces a fresh, scoped resolve.
     */
    private static TaskCache cacheFor(String pkg, int displayId) {
        TaskCache c = taskCache;   // single volatile read — never torn
        return (c != null && c.taskId >= 0 && pkg.equals(c.pkg) && c.displayId == displayId)
                ? c : null;
    }

    /** Publish {@code snapshot}, unless an invalidation landed while it was being resolved. */
    private static void publishCache(TaskCache snapshot) {
        synchronized (ClusterFreeformWindow.class) {
            if (snapshot.generation != cacheGeneration) return;   // raced an invalidate — drop it
            taskCache = snapshot;
        }
    }

    /** Record that we issued a windowing write against this task, so the teardown knows it must
     *  undo something even if the resize itself was refused. */
    private static void markTouched(String pkg, int taskId) {
        synchronized (ClusterFreeformWindow.class) {
            TaskCache c = taskCache;
            if (c != null && c.taskId == taskId && pkg.equals(c.pkg) && !c.touched) {
                taskCache = new TaskCache(c.pkg, c.taskId, c.displayId, c.converted, true,
                        c.generation);
            }
        }
    }

    /** Mark the cached task for {@code pkg} as converted-to-freeform, preserving its identity. */
    private static void markConverted(String pkg, int taskId) {
        synchronized (ClusterFreeformWindow.class) {
            TaskCache c = taskCache;
            if (c != null && c.taskId == taskId && pkg.equals(c.pkg) && !c.converted) {
                taskCache = new TaskCache(c.pkg, c.taskId, c.displayId, true, true, c.generation);
            }
        }
    }

    /**
     * Convert {@code pkg}'s live task to freeform and pin it to {@code bounds} (cluster px).
     *
     * @param commit {@code true} for a gesture RELEASE / preset tap / initial placement: run the
     *   FULL escalation ladder and VERIFY the result (the authoritative apply). {@code false} for
     *   a live drag frame: issue the cheap bounds-only update on the already-converted task with
     *   no stack scan and no verification, so dragging stays smooth. A drag frame that arrives
     *   before the first successful conversion is promoted to a full apply automatically.
     * @return true iff the app is believed to be at {@code bounds} — for {@code commit=true} that
     *   means {@code getTaskBounds} CONFIRMED it (or every rung dispatched and the firmware
     *   exposes no way to read bounds back); for a drag frame it means the update dispatched.
     */
    static boolean applyBounds(String pkg, Rect bounds, boolean commit, int displayId) {
        if (pkg == null || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        Object svc = service();
        if (svc == null) return false;

        // ── Live-drag fast path: this exact task is already freeform, so only its bounds need to
        // move. One reflected call per frame — no stack scan, no verify, no shell, no log line.
        // Reading the snapshot ONCE guarantees the id and the converted flag describe the SAME
        // task, so a drag frame can never be applied against a different (recycled) task id.
        if (!commit) {
            TaskCache cached = cacheFor(pkg, displayId);
            if (cached != null && cached.converted) {
                if (invokeResizeTask(svc, cached.taskId, bounds)) return true;
                // The cheap path failed. Most likely the task left FREEFORM behind our back (an
                // app relaunch, or AMS resetting the mode), which makes resizeTask throw for as
                // long as we keep believing it is converted — every later drag frame would fail
                // identically. Clear the belief and fall through to a FULL apply, which re-does
                // the mode/stack rungs and heals the session in place. (invokeResizeTask already
                // dropped the task-id cache, so the resolve below re-reads it from AMS.)
                logger.info("applyBounds: drag fast-path failed for " + pkg
                        + " — falling back to a full re-convert");
            }
            // Not converted yet (first frame of a fresh cast) or just invalidated: fall through to
            // the full apply so the frame still lands rather than being silently dropped.
        }

        int taskId = resolveTaskId(pkg, displayId);
        if (taskId < 0) {
            logger.info("applyBounds: no live task for " + pkg
                    + (displayId >= 0 ? " on display " + displayId : ""));
            return false;
        }

        // ── Full apply. Each rung is independent + guarded; the verify at the end decides.
        // First make sure freeform is even permitted on this build (one-shot; see the method doc —
        // AMS caches the setting at boot, so this may only help after a restart).
        boolean learnDiLink5Support = ClusterTransport.isDiLink5();
        if (learnDiLink5Support) loadFreeformSuppression();
        if (shouldSuppressFreeform(learnDiLink5Support, freeformSuppressed.contains(pkg))) {
            logger.warn("applyBounds " + pkg + " skipped after repeated current-boot freeform loss");
            return false;
        }
        ensureFreeformSupportEnabled();
        boolean resizeable = invokeSetTaskResizeable(svc, taskId, RESIZE_MODE_FORCE_RESIZEABLE);

        // Stack rungs — the fix for "resizeTask dispatched but nothing moved". A task cannot hold
        // bounds its parent stack contradicts, so the stack must leave FULLSCREEN first. Gated on
        // the stack being exclusively this task's, so a stack shared with anything else (never
        // expected for the cast app, but the cluster display is shared infrastructure) is left
        // untouched rather than reconfigured under a sibling.
        int stackId = exclusiveStackIdForTask(svc, taskId);
        if (stackId < 0) {
            // SHARED (or unresolvable) STACK — REFUSE the whole apply.
            //
            // This is a hard safety stop, not an optimisation. On this AMS `setTaskWindowingMode` is
            // STACK-scoped: it resolves the task and then calls `stack.setWindowingMode(...)` on the
            // task's PARENT (ActivityTaskManagerService:2265-2267), and `ActivityStack.resize` writes
            // the new bounds onto EVERY task in that stack. So converting "the cast app" would drag
            // every sibling in its stack into freeform at our rect. If the nav map ever shares a
            // standard stack with the cast app on the fission display, dragging the box would move
            // the MAP too — exactly the corruption this class promises never to cause. Since no
            // task-scoped alternative exists on this API, the only safe action is to do nothing.
            logger.warn("applyBounds " + pkg + " task=" + taskId + " REFUSED — stack not exclusively "
                    + "ours; task-level windowing is stack-scoped on this AMS and would reshape "
                    + "co-resident tasks (e.g. the nav map)");
            return false;
        }
        // Stack rungs. Absent on API 29 (no setStackWindowingMode), so this is a no-op there and
        // the task-level call below does the work; kept for builds that do expose it.
        boolean stackMode = invokeSetStackWindowingMode(svc, stackId, WINDOWING_MODE_FREEFORM);

        // Put the task into FREEFORM. This is THE call that matters, and it must come BEFORE the
        // resize: resizeTask() starts with `if (!canResizeTask()) throw`, and canResizeTask() is
        // literally `mWindowingMode == WINDOWING_MODE_FREEFORM`. Note we do NOT use
        // setTaskWindowingModeSplitScreenPrimary here — see invokeSetTaskWindowingMode's doc for
        // why that call cannot produce freeform on this AMS.
        boolean mode = invokeSetTaskWindowingMode(svc, taskId, WINDOWING_MODE_FREEFORM);
        // Stack bounds AFTER the mode change, never before: `setWindowingMode` recomputes the stack
        // rect from the raw bounds (ActivityStack:825-835) and would discard anything we set first.
        boolean stackSized = invokeResizeStack(svc, stackId, bounds);
        // Now pin the exact rect (freeform bounds live on the task).
        boolean sized = invokeResizeTask(svc, taskId, bounds);

        // From here on the task may be in FREEFORM at our bounds even if the resize was refused, so
        // the teardown MUST undo it. Record that independently of the verdict below.
        if (mode || stackMode || stackSized || sized) markTouched(pkg, taskId);

        // Focus the task so the resized window is the one the user sees + touches on the cluster.
        invokeSetFocusedRootTask(svc, taskId);

        // Verify against what AMS actually recorded. This is what turns "the reflection didn't
        // throw" into "the app really is that size" — and it is why a failure can now escalate.
        //
        // CONFIRMED alone is sufficient: if AMS reports the task at the requested rect then the
        // app IS that size, which is the whole definition of success — regardless of which rung
        // (or an earlier apply of the same rect) put it there. Only when bounds are UNREADABLE do
        // we fall back to "did any rung dispatch", since that is then the only signal available.
        Verdict verdict = verifyBounds(svc, taskId, bounds);
        // `sized` is the only rung that actually pins task bounds, and it can ONLY have succeeded
        // if the task really is in freeform (resizeTask throws otherwise) — so it is the one signal
        // worth trusting when bounds are unreadable. stackSized alone is NOT enough: a stack resize
        // with the task still fullscreen changes nothing the user can see.
        boolean ok = (verdict == Verdict.CONFIRMED)
                || (verdict == Verdict.UNKNOWN && sized);

        if (verdict == Verdict.MISMATCH) {
            // AMS kept different bounds. Escalate to the shell resize the reference falls back on
            // (`cmd activity task resize`, with the legacy `am task resize` spelling as backup),
            // then re-verify. Some builds honour the shell path when the binder path is clamped.
            logger.warn("applyBounds " + pkg + " task=" + taskId + " → " + bounds.toShortString()
                    + " NOT honoured by AMS (got " + describeTaskBounds(svc, taskId)
                    + ") — escalating to shell resize");
            // Require CONFIRMED on the re-verify, not merely "not MISMATCH": we already know this
            // firmware CAN report task bounds (that is how we detected the mismatch), so an
            // UNKNOWN here means the read stopped working — most likely the task died — which must
            // not be laundered into a success.
            if (shellResizeTask(taskId, bounds)) {
                ok = verifyBounds(svc, taskId, bounds) == Verdict.CONFIRMED;
            }
        }

        if (ok) {
            markConverted(pkg, taskId);
            freeformCurrentBootSupported = true;
            if (learnDiLink5Support) {
                boolean changedSuppression = freeformLosses.remove(pkg) != null;
                changedSuppression = freeformSuppressed.remove(pkg) || changedSuppression;
                if (changedSuppression) persistFreeformSuppression();
            }
        } else if (learnDiLink5Support) {
            int losses = freeformLosses.merge(pkg, 1, (a, b) -> Math.min(2, a + b));
            if (losses >= 2) freeformSuppressed.add(pkg);
            persistFreeformSuppression();
        }
        logger.info("applyBounds " + pkg + " task=" + taskId + " → " + bounds.toShortString()
                + " resizeable=" + resizeable + " stack=" + stackId + "(mode=" + stackMode
                + ",size=" + stackSized + ") freeformMode=" + mode
                + " resize=" + sized + " verify=" + verdict + " → applied=" + ok);
        return ok;
    }

    /**
     * Restore {@code pkg}'s task (and its stack, if we reconfigured one) to FULLSCREEN and drop
     * the freeform bounds, so the cluster display is left pristine for the nav-map / blind-spot
     * projection that follows a cast stop. Best-effort; a failure is non-fatal (the app is about
     * to be reparented off the cluster anyway). No-op if the task is already gone.
     *
     * <p><b>Only ever undoes a task we ourselves converted.</b> This method WRITES windowing state
     * (FULLSCREEN + {@code resizeStack(null)}, which clears a stack's remembered bounds), so acting
     * on a misidentified task is more damaging than a failed resize. It therefore runs strictly
     * from the cache entry {@link #applyBounds} published for this package+display: no cache entry
     * (or one never converted) means we changed nothing, so there is nothing to restore and we exit
     * without touching AMS. In particular it never falls back to an unscoped by-package lookup,
     * which on our own package could otherwise resolve the head-unit UI or the nav-map task and
     * wipe ITS stack bounds — right before {@code ClusterCast.stop()} restarts the map into it.
     */
    static void restoreFullscreen(String pkg, int displayId) {
        if (pkg == null) return;
        Object svc = service();
        if (svc == null) { invalidateTaskCache(); return; }
        TaskCache converted = cacheFor(pkg, displayId);
        if (converted == null || !converted.touched) {
            // Nothing of ours to undo (never converted, or the entry was invalidated because the
            // task died / the display changed). Leave AMS alone.
            invalidateTaskCache();
            return;
        }
        int taskId = converted.taskId;
        if (taskId < 0) { invalidateTaskCache(); return; }
        // Restore the STACK too when we were the one who took it out of fullscreen — otherwise a
        // freeform stack would outlive the cast and the next projection would inherit it.
        int stackId = exclusiveStackIdForTask(svc, taskId);
        if (stackId < 0) {
            // The stack acquired a sibling since we converted (or is unreadable). Undoing via the
            // stack-scoped verbs would force that sibling to FULLSCREEN and wipe its bounds, so
            // leave everything alone — the app is about to be reparented off the cluster anyway,
            // and applyBounds refuses shared stacks so our own residue is bounded.
            logger.warn("restoreFullscreen " + pkg + " task=" + taskId + " SKIPPED — stack no longer "
                    + "exclusively ours; refusing stack-scoped undo that would affect siblings");
            invalidateTaskCache();
            return;
        }
        boolean stackMode = invokeSetStackWindowingMode(svc, stackId, WINDOWING_MODE_FULLSCREEN);
        // ORDER IS LOAD-BEARING HERE TOO, and it is the MIRROR of applyBounds. resizeTask(null)
        // must run while the task is STILL freeform: `canResizeTask()` is checked before the
        // null-bounds branch, so clearing bounds after switching to FULLSCREEN just throws and the
        // stale rect survives (the app would then reopen at the old cluster rect on a later launch,
        // restored from TaskRecord.mLastNonFullscreenBounds). Per the platform's own comment, "a
        // null bounds on a freeform task moves that task to fullscreen" — so this single call both
        // clears the bounds AND returns the task to fullscreen.
        invokeResizeTaskNull(svc, taskId);
        // Then assert FULLSCREEN explicitly, in case resizeTask(null) was unavailable or refused.
        boolean mode = invokeSetTaskWindowingMode(svc, taskId, WINDOWING_MODE_FULLSCREEN);
        logger.info("restoreFullscreen " + pkg + " task=" + taskId + " → fullscreen=" + mode
                + " stack=" + stackId + "(mode=" + stackMode + ")");
        invalidateTaskCache();
    }

    // ── Docked placement (legacy Android 10 cluster) ─────────────────────────────────

    private static final int WINDOWING_MODE_PINNED = 2;
    private static final int WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3;
    private static final int SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT = 0;
    private static final int DOCK_TOLERANCE_PX = 2;
    /** framework default_minimal_size_resizable_task when the resource cannot be read. */
    private static final float DEFAULT_MIN_TASK_DP = 220f;

    private static volatile Method cachedDock;
    private static volatile boolean dockResolved;

    /** Outcome of {@link #applyDocked}. {@code actual} is the task rect AMS resolved, or null. */
    static final class Placement {
        static final int APPLIED = 0;
        /** The task is not on the cluster yet; a later attempt may land. */
        static final int RETRY = 1;
        /** This AMS or task cannot be docked; the caller may fall back to freeform. */
        static final int UNSUPPORTED = 2;
        static final int FAILED = 3;
        /** Another task is on the display; docking would resize it into the free space. */
        static final int BLOCKED = 4;

        final int status;
        final int mode;
        final Rect actual;
        final String reason;

        Placement(int status, Integer mode, Rect actual, String reason) {
            this.status = status;
            this.mode = mode != null ? mode : -1;
            this.actual = actual;
            this.reason = reason == null ? "" : reason;
        }

        boolean docked() { return mode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY; }
    }

    /** True when this AMS exposes the bounds-taking split-screen-primary verb (Android 10). */
    static boolean dockingSupported() {
        Object svc = service();
        return svc != null && dockMethod(svc) != null;
    }

    private static Method dockMethod(Object svc) {
        if (dockResolved) return cachedDock;
        Class<?>[] sig = { int.class, int.class, boolean.class, boolean.class, Rect.class,
                boolean.class };
        Method found = null;
        // The vendor spelling exists on this ROM family (as it does for setCustomTaskWindowingMode).
        for (String name : new String[] { "setTaskWindowingModeSplitScreenPrimary",
                                          "setCustomTaskWindowingModeSplitScreenPrimary" }) {
            found = findMethod(svc, name, sig, sig);
            if (found != null) break;
        }
        cachedDock = found;
        dockResolved = true;
        return found;
    }

    /**
     * Give {@code pkg}'s cluster task exactly {@code bounds} by docking it as the display's
     * split-screen-primary stack with those bounds — no freeform support or caption needed. The
     * stack must enter split mode from FULLSCREEN with no bounds of its own, otherwise AMS snaps
     * it to a divider target instead of the requested rect. A full-panel request returns the task
     * to fullscreen. Refuses while any other task is on the display, since entering split mode
     * would push it into split-screen-secondary beside the cast app.
     */
    static Placement applyDocked(String pkg, Rect bounds, int displayId, int panelW, int panelH) {
        if (pkg == null || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            return new Placement(Placement.FAILED, null, null, "invalid bounds");
        }
        Object svc = service();
        Method dock = svc != null ? dockMethod(svc) : null;
        if (dock == null) {
            return new Placement(Placement.UNSUPPORTED, null, null, "docking unavailable");
        }
        int taskId = resolveTaskId(pkg, displayId);
        if (taskId < 0) {
            return new Placement(Placement.RETRY, null, null, "no task on the cluster yet");
        }
        if (exclusiveStackIdForTask(svc, taskId) < 0) {
            return new Placement(Placement.FAILED, null, null, "task shares its stack");
        }
        Integer mode = taskWindowingMode(svc, taskId);
        if (mode == null) {
            return new Placement(Placement.UNSUPPORTED, null, null, "windowing mode unreadable");
        }
        boolean fullPanel = panelW > 1 && panelH > 1 && bounds.left <= 0 && bounds.top <= 0
                && bounds.right >= panelW && bounds.bottom >= panelH;
        if (fullPanel) {
            if (mode != WINDOWING_MODE_FULLSCREEN) {
                markTouched(pkg, taskId);
                leaveMultiWindow(svc, taskId, mode);
            }
            Integer now = taskWindowingMode(svc, taskId);
            boolean full = now != null && now == WINDOWING_MODE_FULLSCREEN;
            logger.info("applyDocked " + pkg + " task=" + taskId + " → fullscreen (was mode="
                    + mode + ") ok=" + full);
            return new Placement(full ? Placement.APPLIED : Placement.FAILED, now,
                    getTaskBounds(svc, taskId), full ? "" : "task did not return to fullscreen");
        }
        // AMS grows a side shorter than the task's minimal size from the top/left edge without
        // keeping it on the display, so ask for a rect that already satisfies it.
        int[] min = minTaskSizePx(svc, taskId);
        Rect target = fitMinimumSize(bounds, min[0], min[1], panelW, panelH);
        if (mode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            Rect current = getTaskBounds(svc, taskId);
            DockRecord last = lastDock;
            if (sameRect(current, target) || (last != null && last.taskId == taskId
                    && sameRect(last.target, target) && sameRect(last.actual, current))) {
                return new Placement(Placement.APPLIED, mode, current, "");
            }
        }
        String blocker = otherTaskOnDisplay(svc, displayId, taskId);
        if (blocker != null) {
            logger.info("applyDocked " + pkg + " deferred — cluster display " + displayId
                    + " also holds " + blocker);
            return new Placement(Placement.BLOCKED, mode, null, "cluster shared with " + blocker);
        }
        markTouched(pkg, taskId);
        if (mode != WINDOWING_MODE_FULLSCREEN) {
            // Re-docking at a new rect has to go through fullscreen: an already-docked stack
            // ignores new bounds, and a freeform stack would be snapped.
            leaveMultiWindow(svc, taskId, mode);
            if (exclusiveStackIdForTask(svc, taskId) < 0) {
                return new Placement(Placement.FAILED, null, null, "task shares its stack");
            }
            Integer now = taskWindowingMode(svc, taskId);
            if (now == null || now != WINDOWING_MODE_FULLSCREEN) {
                return new Placement(Placement.FAILED, now, null,
                        "task did not return to fullscreen");
            }
        }
        // An activity starting in the task resets its resize mode to the app's own, which makes
        // a non-resizeable app refuse split mode, so the dock gets one immediate second try.
        Integer after = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            invokeSetTaskResizeable(svc, taskId, RESIZE_MODE_FORCE_RESIZEABLE);
            try {
                // toTop=false: the task is already the resumed one on the cluster, and raising its
                // stack would also raise that display and take key focus off the head unit.
                dock.invoke(svc, taskId, SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT, false, false,
                        new Rect(target), false);
            } catch (Throwable t) {
                return new Placement(Placement.UNSUPPORTED, WINDOWING_MODE_FULLSCREEN, null,
                        "dock refused: " + unwrap(t));
            }
            after = taskWindowingMode(svc, taskId);
            if (after != null && after == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) break;
        }
        if (after == null || after != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            logger.warn("applyDocked " + pkg + " task=" + taskId + " → " + target.toShortString()
                    + " did not dock (mode=" + after + ")");
            return new Placement(Placement.UNSUPPORTED, after, getTaskBounds(svc, taskId),
                    "dock did not take");
        }
        // WMS keeps the rect as the global docked-create bounds; clear it through the window
        // manager so a later head-unit split does not start at a cluster rect. Not through the
        // task verb: that re-validates split support and would undock a non-resizeable app.
        clearDockedCreateBounds();
        after = taskWindowingMode(svc, taskId);
        Rect actual = getTaskBounds(svc, taskId);
        boolean docked = after != null && after == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
        // From a no-bounds fullscreen stack the only change AMS makes is growing a side to a
        // minimum it knows better (an app's declared size), anchored at the top/left edge. A rect
        // that grew to the whole panel is indistinguishable from "not honoured", so it is refused.
        boolean landed = docked && (sameRect(actual, target)
                || (grownFrom(actual, target) && !coversPanel(actual, target, panelW, panelH)));
        if (landed) lastDock = new DockRecord(taskId, target, actual);
        logger.info("applyDocked " + pkg + " task=" + taskId + " → " + target.toShortString()
                + " got " + (actual == null ? "unreadable" : actual.toShortString())
                + " (was mode=" + mode + ", now=" + after + ") landed=" + landed);
        return new Placement(landed ? Placement.APPLIED : Placement.FAILED, after, actual,
                landed ? "" : "cluster kept "
                        + (actual == null ? "unreadable bounds" : actual.toShortString()));
    }

    /** The last docked placement, so a rect AMS grew is not re-docked on every identical request. */
    private static final class DockRecord {
        final int taskId;
        final Rect target;
        final Rect actual;

        DockRecord(int taskId, Rect target, Rect actual) {
            this.taskId = taskId;
            this.target = new Rect(target);
            this.actual = actual != null ? new Rect(actual) : null;
        }
    }

    private static volatile DockRecord lastDock;

    /** {@code IWindowManager.setDockedStackCreateState(TOP_OR_LEFT, null)}. Best-effort. */
    private static void clearDockedCreateBounds() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "window");
            if (!(binder instanceof android.os.IBinder)) return;
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            Object wm = stub.getMethod("asInterface", android.os.IBinder.class)
                    .invoke(null, binder);
            if (wm == null) return;
            wm.getClass().getMethod("setDockedStackCreateState", int.class, Rect.class)
                    .invoke(wm, SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT, null);
        } catch (Throwable t) {
            logger.debug("clearDockedCreateBounds failed: " + unwrap(t));
        }
    }

    /**
     * Undock every split-screen-primary task on {@code displayId} — residue of a docked cast whose
     * teardown was skipped (ACC-off) — so a fullscreen launch there is not resolved into
     * split-screen-secondary. A no-op when nothing is docked on that display.
     */
    static int clearDisplayDock(int displayId) {
        if (displayId <= 0) return 0;
        Object svc = service();
        if (svc == null) return 0;
        int undocked = 0;
        try {
            for (Object info : atmTaskList(svc)) {
                if (info == null) continue;
                Object did = readFieldNoThrow(info, "displayId");
                if (!(did instanceof Integer) || (Integer) did != displayId) continue;
                Integer mode = windowingModeOf(info);
                if (mode == null || mode != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) continue;
                int[] children = childTaskIds(info);
                if (children == null || children.length == 0) continue;
                int task = children[children.length - 1];
                if (setTaskWindowingModeInPlace(svc, task, WINDOWING_MODE_FULLSCREEN)) {
                    undocked++;
                    logger.info("clearDisplayDock: undocked task " + task + " on display "
                            + displayId);
                }
            }
        } catch (Throwable t) {
            logger.debug("clearDisplayDock failed: " + unwrap(t));
        }
        if (undocked > 0) invalidateTaskCache();
        return undocked;
    }

    /**
     * Undock {@code pkg}'s split-screen-primary task on any secondary display. Used at daemon boot,
     * when the cluster display id of a stranded cast is no longer known.
     */
    static int undockPackageOffDefaultDisplay(String pkg) {
        if (pkg == null || pkg.isEmpty()) return 0;
        Object svc = service();
        if (svc == null) return 0;
        int undocked = 0;
        try {
            for (Object info : atmTaskList(svc)) {
                if (info == null) continue;
                Object did = readFieldNoThrow(info, "displayId");
                if (!(did instanceof Integer) || (Integer) did <= 0) continue;
                Integer mode = windowingModeOf(info);
                if (mode == null || mode != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) continue;
                Object top = readFieldNoThrow(info, "topActivity");
                if (!(top instanceof android.content.ComponentName)
                        || !pkg.equals(((android.content.ComponentName) top).getPackageName())) {
                    continue;
                }
                int[] children = childTaskIds(info);
                if (children == null || children.length == 0) continue;
                if (setTaskWindowingModeInPlace(svc, children[children.length - 1],
                        WINDOWING_MODE_FULLSCREEN)) {
                    undocked++;
                }
            }
        } catch (Throwable t) {
            logger.debug("undockPackageOffDefaultDisplay failed: " + unwrap(t));
        }
        if (undocked > 0) {
            invalidateTaskCache();
            logger.info("undocked " + undocked + " stranded task(s) of " + pkg);
        }
        return undocked;
    }

    /**
     * Move {@code pkg}'s task on {@code displayId} (and only that one) to the head unit in the
     * background. True when it moved or the package has no task there.
     */
    static boolean moveDisplayTaskToDefaultDisplay(String pkg, int displayId) {
        if (pkg == null || displayId <= 0) return false;
        int taskId = findTaskIdViaAtm(pkg, displayId);
        if (taskId < 0) return true;
        return moveTaskToDefaultDisplayBackground(taskId);
    }

    /** Freeform + null bounds is the only call that both clears a freeform task's bounds and
     *  returns it to fullscreen, so it must run while the task is still freeform. */
    private static void leaveMultiWindow(Object svc, int taskId, int mode) {
        if (mode == WINDOWING_MODE_FREEFORM) invokeResizeTaskNull(svc, taskId);
        invokeSetTaskWindowingMode(svc, taskId, WINDOWING_MODE_FULLSCREEN);
    }

    /** {@code setTaskWindowingMode} with {@code toTop=false}: changes the mode without moving
     *  the task's stack (or its display) to the front. */
    private static boolean setTaskWindowingModeInPlace(Object svc, int taskId, int mode) {
        for (String name : new String[] { "setTaskWindowingMode", "setCustomTaskWindowingMode" }) {
            try {
                Method m = svc.getClass().getMethod(name, int.class, int.class, boolean.class);
                m.invoke(svc, taskId, mode, false);
                return true;
            } catch (NoSuchMethodException ignored) {
                /* try the alternate spelling */
            } catch (Throwable t) {
                logger.debug(name + "(" + taskId + "," + mode + ") failed: " + unwrap(t));
                return false;
            }
        }
        return false;
    }

    /** A task on {@code displayId} outside our own stack that entering split mode would resize,
     *  or null when the display holds only ours. Freeform/pinned stacks are not resized. */
    private static String otherTaskOnDisplay(Object svc, int displayId, int ourTaskId) {
        try {
            for (Object info : atmTaskList(svc)) {
                if (info == null) continue;
                Object did = readFieldNoThrow(info, "displayId");
                if (!(did instanceof Integer) || (Integer) did != displayId) continue;
                int[] children = childTaskIds(info);
                if (children == null || children.length == 0) continue;
                boolean ours = false;
                for (int t : children) if (t == ourTaskId) { ours = true; break; }
                if (ours) continue;
                Integer mode = windowingModeOf(info);
                if (mode != null
                        && (mode == WINDOWING_MODE_FREEFORM || mode == WINDOWING_MODE_PINNED)) {
                    continue;
                }
                Object top = readFieldNoThrow(info, "topActivity");
                return top instanceof android.content.ComponentName
                        ? ((android.content.ComponentName) top).flattenToShortString()
                        : "task " + children[children.length - 1];
            }
        } catch (Throwable t) {
            return "an unreadable stack list";
        }
        return null;
    }

    /** {@code r} grown to the minimum on each short side and slid back onto the panel. */
    private static Rect fitMinimumSize(Rect r, int minW, int minH, int panelW, int panelH) {
        int w = Math.max(r.width(), minW);
        int h = Math.max(r.height(), minH);
        int left = r.left;
        int top = r.top;
        if (panelW > 1) {
            w = Math.min(w, panelW);
            left = Math.max(0, Math.min(left, panelW - w));
        }
        if (panelH > 1) {
            h = Math.min(h, panelH);
            top = Math.max(0, Math.min(top, panelH - h));
        }
        return new Rect(left, top, left + w, top + h);
    }

    private static boolean sameRect(Rect a, Rect b) {
        return a != null && b != null
                && Math.abs(a.left - b.left) <= DOCK_TOLERANCE_PX
                && Math.abs(a.top - b.top) <= DOCK_TOLERANCE_PX
                && Math.abs(a.right - b.right) <= DOCK_TOLERANCE_PX
                && Math.abs(a.bottom - b.bottom) <= DOCK_TOLERANCE_PX;
    }

    /** True when {@code actual} spans a whole panel axis that {@code want} deliberately did not. */
    private static boolean coversPanel(Rect actual, Rect want, int panelW, int panelH) {
        boolean wideNow = panelW > 1 && actual.width() >= panelW - DOCK_TOLERANCE_PX;
        boolean tallNow = panelH > 1 && actual.height() >= panelH - DOCK_TOLERANCE_PX;
        boolean wideAsked = panelW <= 1 || want.width() >= panelW - DOCK_TOLERANCE_PX;
        boolean tallAsked = panelH <= 1 || want.height() >= panelH - DOCK_TOLERANCE_PX;
        return (wideNow && !wideAsked) || (tallNow && !tallAsked);
    }

    /** Same top/left edge, no side shorter than requested. */
    private static boolean grownFrom(Rect actual, Rect want) {
        return actual != null && !actual.isEmpty()
                && Math.abs(actual.left - want.left) <= DOCK_TOLERANCE_PX
                && Math.abs(actual.top - want.top) <= DOCK_TOLERANCE_PX
                && actual.right >= want.right - DOCK_TOLERANCE_PX
                && actual.bottom >= want.bottom - DOCK_TOLERANCE_PX;
    }

    /**
     * The task's minimal width and height in px as AMS applies them: the root activity's declared
     * {@code <layout minWidth/minHeight>} when set, else the framework default (220dp) at the
     * task's display density. 0 = unknown.
     */
    private static int[] minTaskSizePx(Object svc, int taskId) {
        int dpi = 0;
        android.content.ComponentName root = null;
        try {
            for (Object info : atmTaskList(svc)) {
                if (info == null) continue;
                int[] children = childTaskIds(info);
                if (children == null) continue;
                boolean holds = false;
                for (int t : children) if (t == taskId) { holds = true; break; }
                if (!holds) continue;
                Object cfg = readFieldNoThrow(info, "configuration");
                Object d = cfg != null ? readFieldNoThrow(cfg, "densityDpi") : null;
                if (d instanceof Integer) dpi = (Integer) d;
                break;
            }
            Object raw = svc.getClass().getMethod("getTasks", int.class).invoke(svc, 64);
            if (raw instanceof List) {
                for (Object task : (List<?>) raw) {
                    Object id = task != null ? readFieldNoThrow(task, "taskId") : null;
                    if (!(id instanceof Integer) || (Integer) id != taskId) continue;
                    Object base = readFieldNoThrow(task, "baseActivity");
                    if (base instanceof android.content.ComponentName) {
                        root = (android.content.ComponentName) base;
                    }
                    break;
                }
            }
        } catch (Throwable ignored) { /* fall back to the defaults below */ }
        int defaultMin = 0;
        if (dpi > 0) {
            float minDp = DEFAULT_MIN_TASK_DP;
            try {
                android.content.res.Resources res = android.content.res.Resources.getSystem();
                int id = res.getIdentifier("default_minimal_size_resizable_task", "dimen",
                        "android");
                float density = res.getDisplayMetrics().density;
                if (id != 0 && density > 0f) minDp = (int) (res.getDimension(id) / density);
            } catch (Throwable ignored) { /* keep the framework default */ }
            defaultMin = (int) (minDp * dpi / 160f);
        }
        int minW = defaultMin;
        int minH = defaultMin;
        try {
            android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (root != null && ctx != null) {
                android.content.pm.ActivityInfo ai =
                        ctx.getPackageManager().getActivityInfo(root, 0);
                if (ai != null && ai.windowLayout != null) {
                    if (ai.windowLayout.minWidth >= 0) minW = ai.windowLayout.minWidth;
                    if (ai.windowLayout.minHeight >= 0) minH = ai.windowLayout.minHeight;
                }
            }
        } catch (Throwable ignored) { /* the default applies */ }
        return new int[] { minW, minH };
    }

    // ── Verification ─────────────────────────────────────────────────────────────────

    private enum Verdict {
        /** The task is in FREEFORM and its bounds are plausibly ours — the resize took. */
        CONFIRMED,
        /** The task is demonstrably NOT freeform (or is at the full panel we never asked for) —
         *  the resize did not take. */
        MISMATCH,
        /** Neither the mode nor the bounds can be read — we cannot tell either way. */
        UNKNOWN
    }

    /**
     * Decide whether the resize actually took.
     *
     * <h4>Why this checks the MODE, not a pixel match</h4>
     * {@code getTaskBounds} returns AMS's <b>resolved</b> bounds, not the rect we asked for
     * ({@code ActivityTaskManagerService:2205} → {@code TaskRecord.getBounds}). For a freeform task
     * {@code TaskRecord.resolveOverrideConfiguration} first runs
     * {@code adjustForMinimalTaskDimensions} (a ~220dp floor when the app declares no minimum),
     * then {@code fitWithinBounds} against the display's STABLE rect, then an unconditional
     * {@code offsetTop} to keep the caption below the status bar. A legitimate 200×150-at-(0,0)
     * request therefore reads back as something like (0, statusBarH, 290, statusBarH+290) — every
     * edge well past any sane pixel tolerance. Pixel-matching would report MISMATCH on a resize
     * that AMS honoured as fully as policy allows, fork a pointless shell escalation on every
     * commit, and never latch {@code converted} (so the cheap drag path would never engage).
     *
     * <p>Conversely a task that never left FULLSCREEN has EMPTY override bounds and therefore
     * inherits the display rect — so a full-panel request would pixel-match exactly and read as a
     * false CONFIRMED. Mode is the signal that distinguishes these; bounds alone cannot.
     *
     * <p>So: FREEFORM mode is necessary and sufficient for "the resize took" (freeform bounds are
     * ours by construction — {@code resizeTask} is the only thing that sets them, and it throws
     * unless already freeform). Bounds are used only as a corroborating sanity check when the mode
     * is unreadable.
     */
    private static Verdict verifyBounds(Object svc, int taskId, Rect want) {
        Integer mode = taskWindowingMode(svc, taskId);
        if (mode != null) {
            return mode == WINDOWING_MODE_FREEFORM ? Verdict.CONFIRMED : Verdict.MISMATCH;
        }
        // Mode unreadable — fall back to bounds, but only to catch the unambiguous failure: the task
        // sitting at the FULL PANEL when we asked for a strict sub-rect. Anything else is UNKNOWN,
        // since AMS's clamping makes a pixel comparison unreliable in both directions.
        Rect got = getTaskBounds(svc, taskId);
        if (got == null || got.isEmpty()) return Verdict.UNKNOWN;
        boolean wantedSubRect = want.width() < panelSpan(got, true)
                || want.height() < panelSpan(got, false);
        boolean atFullPanel = got.left == 0 && got.top == 0;
        if (wantedSubRect && atFullPanel && got.width() > want.width() + VERIFY_TOLERANCE_PX) {
            return Verdict.MISMATCH;
        }
        return Verdict.UNKNOWN;
    }

    /** Width/height of {@code r} — a tiny helper so the sub-rect test above reads clearly. */
    private static int panelSpan(Rect r, boolean horizontal) {
        return horizontal ? r.width() : r.height();
    }

    /**
     * The task's current windowing mode, or null when it cannot be read. Sourced from the task's
     * {@code RunningTaskInfo.configuration.windowConfiguration} (a public {@code TaskInfo} field on
     * this API range), falling back to the stack list. This is the authoritative success signal —
     * see {@link #verifyBounds}.
     */
    private static Integer taskWindowingMode(Object svc, int taskId) {
        try {
            for (Object info : atmTaskList(svc)) {
                if (info == null) continue;
                int[] children = childTaskIds(info);
                if (children == null) continue;
                boolean holds = false;
                for (int t : children) if (t == taskId) { holds = true; break; }
                if (!holds) continue;
                Integer m = windowingModeOf(info);
                if (m != null) return m;
            }
        } catch (Throwable t) {
            logger.debug("taskWindowingMode failed: " + t.getMessage());
        }
        return null;
    }

    /** Pull a windowing mode out of a StackInfo/TaskInfo-shaped object: prefer the public
     *  {@code getWindowingMode()} accessor, else dig through {@code configuration
     *  .windowConfiguration}, else the legacy flat {@code windowingMode} field. */
    private static Integer windowingModeOf(Object info) {
        try {
            Object v = info.getClass().getMethod("getWindowingMode").invoke(info);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) { /* try the field paths */ }
        Object cfg = readFieldNoThrow(info, "configuration");
        Object wc = cfg == null ? null : readFieldNoThrow(cfg, "windowConfiguration");
        if (wc != null) {
            try {
                Object v = wc.getClass().getMethod("getWindowingMode").invoke(wc);
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) { /* fall through */ }
        }
        Object flat = readFieldNoThrow(info, "windowingMode");
        return (flat instanceof Integer) ? (Integer) flat : null;
    }

    /** {@code getTaskBounds(int)} → Rect, or null when unavailable. */
    private static Rect getTaskBounds(Object svc, int taskId) {
        try {
            Method m = svc.getClass().getMethod("getTaskBounds", int.class);
            Object res = m.invoke(svc, taskId);
            return (res instanceof Rect) ? (Rect) res : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describeTaskBounds(Object svc, int taskId) {
        Rect r = getTaskBounds(svc, taskId);
        return r == null ? "unreadable" : r.toShortString();
    }

    // ── Task / stack resolution ──────────────────────────────────────────────────────

    /**
     * Task id for {@code pkg}, cached for the live-drag path. Resolved via ATM
     * {@code getTasks} reflection FIRST (an in-process binder call) and only then via
     * {@link AppLauncher#findTaskId}, whose {@code dumpsys | grep} costs hundreds of ms —
     * unusable at drag rates. A cached id is re-validated cheaply by the resize call itself: a
     * dead task id makes AMS throw, the rung reports false, and the next commit re-resolves.
     */
    private static int resolveTaskId(String pkg, int displayId) {
        // REFUSE outright when the cluster display is not yet known. The cast's fission display is
        // published asynchronously by ClusterCast's launch thread (it polls up to 8s for the
        // VirtualDisplay to materialise), but the UI marks itself "casting" as soon as the HTTP POST
        // returns — so commits (a preset tap, or restoreWindowFor on resume) can arrive during that
        // window. With no display to scope to, a by-package lookup would find the app's task on the
        // HEAD UNIT (where it is typically already running) and pin an infotainment window to
        // cluster-px bounds — a visible, un-undoable corruption, since the cache entry would be
        // keyed to displayId=-1 and restoreFullscreen(pkg, realId) would never match it.
        // Reporting "no task yet" is strictly better: the box is persisted, and the initial
        // freeform apply after the launch verifies (maybeApplyFreeform) re-applies it correctly.
        if (displayId < 0) {
            logger.info("resolveTaskId: cluster display not resolved yet for " + pkg
                    + " — deferring resize (no unscoped lookup)");
            return -1;
        }
        TaskCache cached = cacheFor(pkg, displayId);
        if (cached != null) return cached.taskId;
        // Snapshot the generation BEFORE resolving, so if an invalidate lands while we are querying
        // AMS, publishCache drops this (now-stale) result instead of resurrecting a dead task id.
        long generation = cacheGeneration;
        int id = findTaskIdViaAtm(pkg, displayId);
        if (id >= 0) publishCache(new TaskCache(pkg, id, displayId, false, false, generation));
        return id;
    }

    /**
     * Task id hosting {@code pkg} per ATM {@code getTasks(maxNum, …)}, matching on the task's
     * {@code topActivity} / {@code baseActivity} package AND — decisively — on the task living on
     * {@code displayId}. Returns -1 when unavailable. The argument list is built by TYPE so this
     * works across the {@code getTasks} overloads this firmware range ships.
     *
     * <h4>Why the display filter is load-bearing, not defensive</h4>
     * A package can own SEVERAL tasks on DIFFERENT displays at once, and matching on package alone
     * returns whichever {@code getTasks} happens to list first. That is an actual hazard here, not
     * a hypothetical: OverDrive declares a LAUNCHER activity, so {@code com.overdrive.app} itself
     * appears in the cast picker — and {@code ClusterMapProjector}'s nav-map
     * ({@code com.overdrive.app/.navmap.RoadSenseClusterMapActivity}) is the SAME package. Casting
     * OverDrive while the map is projecting would otherwise let an unfiltered resolve return the
     * MAP's task and hand its stack to the resize ladder — dragging the nav map instead of the cast
     * app. Filtering to the cluster display makes the cast app's own task the only candidate. The
     * reference scopes by display for exactly this reason ({@code queryTaskLocationsForPackage}
     * records each task's {@code displayId} and {@code FissionWatchdogPolicy.selectTask} picks by
     * it); this port originally dropped that filter.
     *
     * @param displayId the display the task must be on, or -1 to accept any display (used only
     *   when the caller could not resolve the cluster display id).
     */
    private static final int TASK_QUERY_LIMIT = 65;

    static final class RunningTaskInventory {
        final List<?> tasks;
        final boolean complete;

        RunningTaskInventory(List<?> tasks) {
            this.tasks = tasks;
            this.complete = tasks != null && taskInventoryComplete(tasks.size());
        }
    }

    /** Positive matches remain useful even when the capped list cannot prove absence. */
    static RunningTaskInventory queryRunningTasks(Object svc) throws Exception {
        Method getTasks = null;
        for (Method method : svc.getClass().getMethods()) {
            if (!"getTasks".equals(method.getName())) continue;
            if (getTasks == null || method.getParameterTypes().length
                    > getTasks.getParameterTypes().length) {
                getTasks = method;
            }
        }
        if (getTasks == null) return null;
        Class<?>[] parameterTypes = getTasks.getParameterTypes();
        Object[] args = runningTaskQueryArgs(parameterTypes);
        Object raw = getTasks.invoke(svc, args);
        if (!(raw instanceof List)) return null;
        return new RunningTaskInventory((List<?>) raw);
    }

    static Object[] runningTaskQueryArgs(Class<?>[] parameterTypes) {
        Object[] args = new Object[parameterTypes.length];
        boolean suppliedLimit = false;
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == int.class) {
                args[i] = suppliedLimit ? -1 : TASK_QUERY_LIMIT;
                suppliedLimit = true;
            } else if (parameterTypes[i] == boolean.class) {
                args[i] = Boolean.FALSE;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    static boolean taskInventoryComplete(int size) {
        return size > 0 && size < TASK_QUERY_LIMIT;
    }

    private static int findTaskIdViaAtm(String pkg, int displayId) {
        try {
            Object svc = service();
            if (svc == null) return -1;
            RunningTaskInventory inventory = queryRunningTasks(svc);
            if (inventory == null) return -1;
            int anyDisplayMatch = -1;
            for (Object task : inventory.tasks) {
                if (task == null) continue;
                if (!taskMatchesPackage(task, pkg)) continue;
                Object id = readFieldNoThrow(task, "taskId");
                if (!(id instanceof Integer)) id = readFieldNoThrow(task, "id");
                if (!(id instanceof Integer)) continue;
                int taskId = (Integer) id;
                int taskDisplay = taskDisplayId(svc, task, taskId);
                if (displayId >= 0 && taskDisplay == displayId) return taskId;   // exact match
                if (anyDisplayMatch < 0) anyDisplayMatch = taskId;
            }
            // displayId is always >= 0 here (resolveTaskId refuses otherwise), so an off-display
            // match is never acceptable — fall through to the refusal below.
            // A package match exists but NOT on the cluster display. Refuse it: resizing a task on
            // another display would move a window the user never asked about (and, for our own
            // package, could be the head-unit UI or the nav map).
            if (anyDisplayMatch >= 0) {
                logger.info("findTaskIdViaAtm: " + pkg + " has a task, but not on display "
                        + displayId + " — refusing to resize an off-display task");
            }
            return -1;
        } catch (Throwable t) {
            logger.debug("findTaskIdViaAtm failed: " + t.getMessage());
        }
        return -1;
    }

    /**
     * Locate a package task for the DI5 launch guardian. A task on a display other than
     * {@code targetDisplayId} is deliberately preferred over one already on the target: navigation
     * apps can create a second task on display 0 several seconds after their first cluster launch,
     * and that wrong-display rebound is the task that must be moved back.
     */
    static TaskLocation findTaskLocation(String pkg, int targetDisplayId) {
        if (pkg == null || !AppLauncher.isValidPackageName(pkg) || targetDisplayId <= 0) {
            return TaskLocation.unknown();
        }
        try {
            Object svc = service();
            if (svc == null) return TaskLocation.unknown();
            RunningTaskInventory inventory = queryRunningTasks(svc);
            if (inventory == null) return TaskLocation.unknown();

            List<TaskLocation> locations = new ArrayList<>();
            for (Object task : inventory.tasks) {
                Boolean match = taskPackageMatch(task, pkg);
                if (match == null) {
                    locations.add(TaskLocation.unknown());
                    continue;
                }
                if (!match) continue;
                Object id = readFieldNoThrow(task, "taskId");
                if (!(id instanceof Integer)) id = readFieldNoThrow(task, "id");
                if (!(id instanceof Integer)) {
                    locations.add(TaskLocation.unknown());
                    continue;
                }
                int taskId = (Integer) id;
                int displayId = taskDisplayId(svc, task, taskId);
                locations.add(taskLocationFromSnapshot(task, taskId, displayId));
            }
            return selectTaskLocation(locations, targetDisplayId, inventory.complete);
        } catch (Throwable t) {
            logger.debug("findTaskLocation failed: " + unwrap(t));
            return TaskLocation.unknown();
        }
    }

    static TaskLocation taskLocationFromSnapshot(Object task, int taskId, int displayId) {
        return new TaskLocation(true, taskId, displayId, windowingModeOf(task));
    }

    /** True only when every matching task is known to be off {@code displayId}. */
    static Boolean packageTasksReclaimedFromDisplay(String pkg, int displayId) {
        if (pkg == null || !AppLauncher.isValidPackageName(pkg) || displayId <= 0) return null;
        try {
            Object svc = service();
            if (svc == null) return null;
            RunningTaskInventory inventory = queryRunningTasks(svc);
            if (inventory == null) return null;
            List<TaskLocation> matches = new ArrayList<>();
            for (Object task : inventory.tasks) {
                Boolean match = taskPackageMatch(task, pkg);
                if (match == null) {
                    matches.add(TaskLocation.unknown());
                    continue;
                }
                if (!match) continue;
                Object id = readFieldNoThrow(task, "taskId");
                if (!(id instanceof Integer)) id = readFieldNoThrow(task, "id");
                if (!(id instanceof Integer)) {
                    matches.add(TaskLocation.unknown());
                    continue;
                }
                int taskId = (Integer) id;
                matches.add(taskLocationFromSnapshot(task, taskId, taskDisplayId(svc, task, taskId)));
            }
            return allTasksReclaimedFromDisplay(matches, displayId, inventory.complete);
        } catch (Throwable t) {
            logger.debug("packageTasksReclaimedFromDisplay failed: " + unwrap(t));
            return null;
        }
    }

    /** Whether any task of {@code pkg} is on {@code displayId}; null when evidence is incomplete. */
    static Boolean packageTaskOnDisplay(String pkg, int displayId) {
        Boolean reclaimed = packageTasksReclaimedFromDisplay(pkg, displayId);
        return reclaimed == null ? null : !reclaimed;
    }

    static Boolean allTasksReclaimedFromDisplay(
            List<TaskLocation> locations, int targetDisplayId) {
        return allTasksReclaimedFromDisplay(locations, targetDisplayId, true);
    }

    static Boolean allTasksReclaimedFromDisplay(
            List<TaskLocation> locations, int targetDisplayId, boolean inventoryComplete) {
        if (locations == null || targetDisplayId <= 0) return null;
        var incomplete = false;
        for (TaskLocation location : locations) {
            if (location == null || !location.known || location.taskId <= 0
                    || location.displayId < 0) {
                incomplete = true;
                continue;
            }
            if (location.displayId == targetDisplayId) return Boolean.FALSE;
        }
        return incomplete || !inventoryComplete ? null : Boolean.TRUE;
    }

    /** Predecessor density leases have no package; do not invent one for their recovery. */
    static Boolean displayTasksReclaimed(int displayId) {
        if (displayId <= 0) return null;
        try {
            Object svc = service();
            if (svc == null) return null;
            RunningTaskInventory inventory = queryRunningTasks(svc);
            if (inventory == null) return null;
            List<TaskLocation> locations = new ArrayList<>();
            for (Object task : inventory.tasks) {
                if (task == null) {
                    locations.add(TaskLocation.unknown());
                    continue;
                }
                Object id = readFieldNoThrow(task, "taskId");
                if (!(id instanceof Integer)) id = readFieldNoThrow(task, "id");
                if (!(id instanceof Integer)) {
                    locations.add(TaskLocation.unknown());
                } else {
                    int taskId = (Integer) id;
                    locations.add(taskLocationFromSnapshot(task, taskId,
                            taskDisplayId(svc, task, taskId)));
                }
            }
            return allTasksReclaimedFromDisplay(locations, displayId, inventory.complete);
        } catch (Throwable t) {
            logger.debug("displayTasksReclaimed failed: " + unwrap(t));
            return null;
        }
    }

    static TaskLocation selectTaskLocation(
            List<TaskLocation> locations, int targetDisplayId) {
        return selectTaskLocation(locations, targetDisplayId, true);
    }

    static TaskLocation selectTaskLocation(
            List<TaskLocation> locations, int targetDisplayId, boolean inventoryComplete) {
        if (locations == null) return TaskLocation.unknown();
        TaskLocation onTarget = null;
        TaskLocation unknownDisplay = null;
        boolean incomplete = !inventoryComplete;
        for (TaskLocation location : locations) {
            if (location == null || !location.known || location.taskId <= 0) {
                incomplete = true;
                continue;
            }
            if (location.displayId >= 0 &&
                    location.displayId != targetDisplayId) {
                return location;
            }
            if (location.displayId == targetDisplayId && onTarget == null) {
                onTarget = location;
            } else if (location.displayId < 0 && unknownDisplay == null) {
                unknownDisplay = location;
            }
        }
        if (unknownDisplay != null) return unknownDisplay;
        if (onTarget != null) return onTarget;
        return incomplete ? TaskLocation.unknown() : TaskLocation.absent();
    }

    /**
     * Move one exclusively-owned task/root-task onto {@code targetDisplayId}. Android 10 exposes
     * {@code moveStackToDisplay}; Android 12+ renamed the same operation to
     * {@code moveRootTaskToDisplay}. We try both and verify the task's actual display afterwards.
     *
     * <p>The exclusive-root gate is intentional: both APIs move the whole root task, so invoking
     * them on a root containing siblings would drag unrelated head-unit apps onto the cluster.
     */
    enum MoveTaskResult {
        NOT_DISPATCHED,
        DISPATCHED_UNCONFIRMED,
        CONFIRMED
    }

    interface MoveDispatchAuthority {
        boolean runIfCurrent(Runnable dispatch);
    }

    static boolean moveTaskToDisplay(int taskId, int targetDisplayId) {
        return moveTaskToDisplayResult(taskId, targetDisplayId, dispatch -> {
            dispatch.run();
            return true;
        }) == MoveTaskResult.CONFIRMED;
    }

    static MoveTaskResult moveTaskToDisplayResult(
            int taskId,
            int targetDisplayId,
            MoveDispatchAuthority authority) {
        if (taskId <= 0 || targetDisplayId <= 0 || authority == null) {
            return MoveTaskResult.NOT_DISPATCHED;
        }
        boolean dispatched = false;
        try {
            Object svc = service();
            if (svc == null) return MoveTaskResult.NOT_DISPATCHED;
            int rootTaskId = -1;
            int currentDisplayId = -1;
            for (Object info : atmTaskList(svc)) {
                if (info == null) continue;
                int[] children = childTaskIds(info);
                if (children == null) continue;
                boolean containsTask = false;
                int liveChildren = 0;
                for (int child : children) {
                    if (child > 0) liveChildren++;
                    if (child == taskId) containsTask = true;
                }
                if (!containsTask) continue;
                if (liveChildren != 1) {
                    logger.warn("moveTaskToDisplay refused task=" + taskId
                            + " because its root contains " + liveChildren + " tasks");
                    return MoveTaskResult.NOT_DISPATCHED;
                }
                rootTaskId = stackOrRootTaskId(info);
                Object display = readFieldNoThrow(info, "displayId");
                if (display instanceof Integer) currentDisplayId = (Integer) display;
                break;
            }
            if (currentDisplayId == targetDisplayId) {
                focusMovedTask(svc, taskId, rootTaskId);
                return MoveTaskResult.CONFIRMED;
            }
            if (rootTaskId < 0) {
                logger.warn("moveTaskToDisplay could not resolve root for task=" + taskId);
                return MoveTaskResult.NOT_DISPATCHED;
            }

            final int moveRootTaskId = rootTaskId;
            for (String name : new String[] {
                    "moveRootTaskToDisplay", "moveStackToDisplay" }) {
                try {
                    Method method = svc.getClass().getMethod(name, int.class, int.class);
                    final boolean[] invocationStarted = { false };
                    final boolean[] invoked = { false };
                    final Throwable[] failure = { null };
                    boolean admitted = authority.runIfCurrent(() -> {
                        invocationStarted[0] = true;
                        try {
                            method.invoke(svc, moveRootTaskId, targetDisplayId);
                            invoked[0] = true;
                        } catch (Throwable t) {
                            failure[0] = t;
                        }
                    });
                    if (!admitted) return MoveTaskResult.NOT_DISPATCHED;
                    if (failure[0] != null) {
                        logger.debug(name + " failed after dispatch began: " + unwrap(failure[0]));
                        if (invocationStarted[0]) {
                            dispatched = true;
                            break;
                        }
                        continue;
                    }
                    if (!invoked[0]) continue;
                    dispatched = true;
                    logger.info(name + "(" + rootTaskId + "," + targetDisplayId
                            + ") dispatched for task=" + taskId);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // Try the spelling used by the other Android generation.
                } catch (Throwable t) {
                    logger.debug(name + " failed: " + unwrap(t));
                }
            }
            if (!dispatched) return MoveTaskResult.NOT_DISPATCHED;

            for (int attempt = 0; attempt < 5; attempt++) {
                int actualDisplay = taskDisplayIdById(svc, taskId);
                if (actualDisplay == targetDisplayId) {
                    focusMovedTask(svc, taskId, rootTaskId);
                    invalidateTaskCache();
                    return MoveTaskResult.CONFIRMED;
                }
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return MoveTaskResult.DISPATCHED_UNCONFIRMED;
                }
            }
            logger.warn("moveTaskToDisplay verification failed task=" + taskId
                    + " target=" + targetDisplayId);
        } catch (Throwable t) {
            logger.warn("moveTaskToDisplay failed: " + unwrap(t));
        }
        return dispatched
                ? MoveTaskResult.DISPATCHED_UNCONFIRMED
                : MoveTaskResult.NOT_DISPATCHED;
    }

    /**
     * Live-task lookup through {@code IActivityTaskManager.getTasks}: the id of a RUNNING task
     * whose top/base activity belongs to {@code pkg}; {@code -1} when the running-task list was
     * obtained and holds none; {@link AppLauncher#TASK_LOOKUP_FAILED} when the service is
     * unavailable or returned nothing usable (never a proof of absence).
     *
     * <p>Why this exists: the {@code dumpsys activity} parser attributes any {@code <pkg>/}
     * component line inside a task block to that task. On Android 12 the shared cluster
     * display's ROOT task keeps a {@code mLastPausedActivity: ActivityRecord{... <pkg>/...}}
     * line after the app's child task has been destroyed, so a force-stopped app was never
     * "confirmed absent" (field log 2026-09-24 03:02: {@code task=11} reported for com.waze
     * forever), the DI5 cleanup stayed incomplete and every following cast was refused.
     * Running tasks reported by ATM carry no such stale references.
     */
    static int liveTaskIdForPackage(String pkg) {
        if (pkg == null || !AppLauncher.isValidPackageName(pkg)) return -1;
        try {
            Object svc = service();
            if (svc == null) return AppLauncher.TASK_LOOKUP_FAILED;
            RunningTaskInventory inventory = queryRunningTasks(svc);
            if (inventory == null) return AppLauncher.TASK_LOOKUP_FAILED;
            boolean complete = inventory.complete;
            for (Object task : inventory.tasks) {
                Boolean match = taskPackageMatch(task, pkg);
                if (match == null) {
                    complete = false;
                    continue;
                }
                if (!match) continue;
                Object id = readFieldNoThrow(task, "taskId");
                if (!(id instanceof Integer)) id = readFieldNoThrow(task, "id");
                if (id instanceof Integer && (Integer) id > 0) return (Integer) id;
                complete = false;
            }
            return complete ? -1 : AppLauncher.TASK_LOOKUP_FAILED;
        } catch (Throwable t) {
            logger.debug("liveTaskIdForPackage failed: " + unwrap(t));
            return AppLauncher.TASK_LOOKUP_FAILED;
        }
    }

    /**
     * Reparent {@code taskId} into an existing standard fullscreen root task on the DEFAULT
     * display (head unit) WITHOUT bringing it to the front — the Android 12+ equivalent of
     * {@code am stack move-task <task> <display-0 stack> false}.
     *
     * <p>Why this exists: {@link AppLauncher#reparentToDisplay0} finds its display-0 target by
     * parsing {@code Stack #} / {@code ActivityStack} headers out of {@code dumpsys activity},
     * which Android 12 no longer prints (stacks became root {@code Task}s). On DiLink 5.0
     * firmware that lookup therefore always failed ("no display-0 fullscreen stack found"), the
     * projected task kept its cluster-display affinity, the DI5 cleanup stayed "incomplete" and
     * every following cast was refused until the camera service was restarted (field log
     * 2026-09-23 20:18–20:32). This path asks {@code IActivityTaskManager} for the root tasks
     * directly and uses {@code moveTaskToRootTask} (S+) / {@code moveTaskToStack} (Q).
     *
     * <p>Safety: only the ONE task named by the caller is moved (never a whole root task, so no
     * sibling is dragged along), {@code toTop=false} keeps it in the background so nothing pops
     * onto the head unit while driving, and the destination is restricted to standard fullscreen
     * roots (never home/recents/split). A background (non-visible) host root is preferred so the
     * foreground app's back-navigation is left untouched. The result is verified through the
     * task's actual display id; any doubt returns false so the caller keeps its recovery marker.
     */
    static boolean moveTaskToDefaultDisplayBackground(int taskId) {
        if (taskId <= 0) return false;
        try {
            Object svc = service();
            if (svc == null) return false;
            int currentDisplay = taskDisplayIdById(svc, taskId);
            if (currentDisplay == 0) {
                invalidateTaskCache();
                return true;
            }
            int hostRoot = -1;
            boolean hostVisible = true;
            for (Object info : atmTaskList(svc)) {
                if (info == null) continue;
                Object display = readFieldNoThrow(info, "displayId");
                if (!(display instanceof Integer) || (Integer) display != 0) continue;
                int root = stackOrRootTaskId(info);
                if (root <= 0) continue;
                int[] children = childTaskIds(info);
                boolean containsTask = false;
                if (children != null) {
                    for (int child : children) {
                        if (child == taskId) containsTask = true;
                    }
                }
                if (containsTask || !isStandardFullscreenRoot(info)) continue;
                boolean visible = Boolean.TRUE.equals(readFieldNoThrow(info, "visible"));
                if (hostRoot < 0 || (hostVisible && !visible)) {
                    hostRoot = root;
                    hostVisible = visible;
                }
            }
            if (hostRoot < 0) {
                logger.warn("moveTaskToDefaultDisplayBackground: no standard fullscreen "
                        + "root task on display 0 to host task=" + taskId);
                return false;
            }
            boolean dispatched = false;
            for (String name : new String[] { "moveTaskToRootTask", "moveTaskToStack" }) {
                try {
                    Method method = svc.getClass().getMethod(
                            name, int.class, int.class, boolean.class);
                    method.invoke(svc, taskId, hostRoot, false);
                    dispatched = true;
                    logger.info(name + "(" + taskId + "," + hostRoot
                            + ",toTop=false) dispatched (from display "
                            + currentDisplay + ")");
                    break;
                } catch (NoSuchMethodException ignored) {
                    // Try the spelling used by the other Android generation.
                } catch (Throwable t) {
                    logger.debug(name + " failed: " + unwrap(t));
                }
            }
            if (!dispatched) return false;
            for (int attempt = 0; attempt < 5; attempt++) {
                if (taskDisplayIdById(svc, taskId) == 0) {
                    invalidateTaskCache();
                    return true;
                }
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            logger.warn("moveTaskToDefaultDisplayBackground verification failed task="
                    + taskId);
        } catch (Throwable t) {
            logger.warn("moveTaskToDefaultDisplayBackground failed: " + unwrap(t));
        }
        return false;
    }

    /** True when a root-task/stack info describes a standard (type 1) fullscreen (mode 1)
     *  container — the only kind a background head-unit reparent may target. */
    private static boolean isStandardFullscreenRoot(Object info) {
        Object configuration = readFieldNoThrow(info, "configuration");
        if (configuration == null) return false;
        Object windowConfiguration = readFieldNoThrow(configuration, "windowConfiguration");
        if (windowConfiguration == null) return false;
        return invokeIntNoThrow(windowConfiguration, "getWindowingMode") == 1
                && invokeIntNoThrow(windowConfiguration, "getActivityType") == 1;
    }

    private static int invokeIntNoThrow(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Bring one exact task to the front without moving its display.
     *
     * <p>This is the DI5 same-display recovery path. The OEM projection service can put its own
     * MeterActivity above a projected app while leaving our task on the correct shared display;
     * relaunching or reparenting based only on display location cannot distinguish that condition.
     * Prefer {@code setFocusedTask(taskId)} because it names the exact task. The root-task fallback
     * is allowed only when that root contains no sibling tasks, so focusing our cast cannot surface
     * an unrelated head-unit task.
     */
    static boolean focusTask(int taskId, int expectedDisplayId) {
        if (taskId <= 0 || expectedDisplayId <= 0) return false;
        try {
            Object svc = service();
            if (svc == null) return false;
            int actualDisplayId = taskDisplayIdById(svc, taskId);
            if (actualDisplayId != expectedDisplayId) {
                logger.warn("focusTask refused task=" + taskId
                        + " because display changed from expected=" + expectedDisplayId
                        + " to actual=" + actualDisplayId);
                return false;
            }

            try {
                Method taskFocus = svc.getClass().getMethod(
                        "setFocusedTask", int.class);
                taskFocus.invoke(svc, taskId);
                logger.info("setFocusedTask(" + taskId + ") dispatched");
                return true;
            } catch (NoSuchMethodException ignored) {
                // API generation exposing only setFocusedRootTask.
            } catch (Throwable t) {
                logger.debug("setFocusedTask failed: " + unwrap(t));
            }

            int rootTaskId = -1;
            int liveChildren = 0;
            for (Object info : atmTaskList(svc)) {
                if (info == null) continue;
                int[] children = childTaskIds(info);
                if (children == null) continue;
                boolean containsTask = false;
                int count = 0;
                for (int child : children) {
                    if (child > 0) count++;
                    if (child == taskId) containsTask = true;
                }
                if (!containsTask) continue;
                rootTaskId = stackOrRootTaskId(info);
                liveChildren = count;
                break;
            }
            if (rootTaskId < 0 || liveChildren != 1) {
                logger.warn("focusTask refused root fallback for task=" + taskId
                        + " root=" + rootTaskId + " children=" + liveChildren);
                return false;
            }
            Method rootFocus = svc.getClass().getMethod(
                    "setFocusedRootTask", int.class);
            rootFocus.invoke(svc, rootTaskId);
            logger.info("setFocusedRootTask(" + rootTaskId
                    + ") dispatched for task=" + taskId);
            return true;
        } catch (Throwable t) {
            logger.warn("focusTask failed: " + unwrap(t));
            return false;
        }
    }

    private static int taskDisplayIdById(Object svc, int taskId) {
        try {
            for (Object info : atmTaskList(svc)) {
                if (info == null) continue;
                int[] children = childTaskIds(info);
                if (children == null) continue;
                for (int child : children) {
                    if (child != taskId) continue;
                    Object display = readFieldNoThrow(info, "displayId");
                    return display instanceof Integer ? (Integer) display : -1;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void focusMovedTask(Object svc, int taskId, int rootTaskId) {
        try {
            Method taskFocus = svc.getClass().getMethod("setFocusedTask", int.class);
            taskFocus.invoke(svc, taskId);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Method rootFocus = svc.getClass().getMethod(
                    "setFocusedRootTask", int.class);
            rootFocus.invoke(svc, rootTaskId);
        } catch (Throwable ignored) {
        }
    }

    /** Display id for a RunningTaskInfo — the {@code displayId} field when present, else located
     *  via the stack list. -1 when it cannot be determined. */
    private static int taskDisplayId(Object svc, Object task, int taskId) {
        // Root-task list FIRST: on Android 12 shared cluster displays RunningTaskInfo.displayId
        // reported 0 for a task that was resumed on display 4 (field log 2026-09-24 03:02:04,
        // "task=42 from=0 to=4" with foreground=com.waze), which made the guardian relaunch a
        // task the user was already looking at. Root tasks are direct display children, so
        // their displayId is authoritative; the field is only a fallback.
        int viaRoots = displayIdViaRootTasks(svc, taskId);
        if (viaRoots >= 0) return viaRoots;
        Object raw = readFieldNoThrow(task, "displayId");
        if (raw instanceof Integer) return (Integer) raw;
        return -1;
    }

    private static int displayIdViaRootTasks(Object svc, int taskId) {
        try {
            for (Object info : atmTaskList(svc)) {
                if (info == null) continue;
                // Match ONLY on the stack's child task ids. Comparing stackOrRootTaskId(info)
                // against taskId would be an id-space confusion: stack ids come from a global
                // sNextFreeStackId++ starting at 0, while user-0 task ids also start small, so the
                // two collide freely — an unrelated stack whose id equals our taskId would donate
                // its displayId and make us refuse (or mis-accept) the real task.
                int[] children = childTaskIds(info);
                if (children == null) continue;
                boolean holds = false;
                for (int t : children) if (t == taskId) { holds = true; break; }
                if (!holds) continue;
                Object did = readFieldNoThrow(info, "displayId");
                if (did instanceof Integer) return (Integer) did;
            }
        } catch (Throwable ignored) { /* fall through */ }
        return -1;
    }

    /** True when a RunningTaskInfo's top or base activity belongs to {@code pkg}. */
    private static boolean taskMatchesPackage(Object task, String pkg) {
        return Boolean.TRUE.equals(taskPackageMatch(task, pkg));
    }

    /** Missing identity is unknown, not proof that the task belongs to another package. */
    private static Boolean taskPackageMatch(Object task, String pkg) {
        if (task == null) return null;
        boolean complete = true;
        for (String field : new String[] { "topActivity", "baseActivity" }) {
            Object cn = readFieldNoThrow(task, field);
            if (cn instanceof android.content.ComponentName
                    && pkg.equals(((android.content.ComponentName) cn).getPackageName())) {
                return true;
            }
            if (!(cn instanceof android.content.ComponentName)) complete = false;
        }
        return complete ? Boolean.FALSE : null;
    }

    /**
     * Stack (root-task) id owning {@code taskId} — but ONLY when that stack contains no OTHER
     * task, so reconfiguring it cannot disturb an unrelated app sharing the cluster display.
     * Returns -1 when the stack can't be resolved or is shared (caller then skips the stack
     * rungs entirely). This gate is the safety margin over the reference implementation.
     */
    private static int exclusiveStackIdForTask(Object svc, int taskId) {
        try {
            for (Object info : atmTaskList(svc)) {
                if (info == null) continue;
                int[] children = childTaskIds(info);
                if (children == null) continue;
                boolean holdsOurs = false;
                for (int t : children) if (t == taskId) { holdsOurs = true; break; }
                if (!holdsOurs) continue;
                if (children.length != 1) {
                    logger.info("stack for task " + taskId + " hosts " + children.length
                            + " tasks — skipping stack-level resize (shared stack)");
                    return -1;
                }
                return stackOrRootTaskId(info);
            }
        } catch (Throwable t) {
            logger.debug("exclusiveStackIdForTask failed: " + t.getMessage());
        }
        return -1;
    }

    /** {@code getAllStackInfos()} with the {@code getAllRootTaskInfos()} rename as fallback. */
    private static List<?> atmTaskList(Object svc) throws Throwable {
        Object res;
        try {
            res = svc.getClass().getMethod("getAllStackInfos").invoke(svc);
        } catch (NoSuchMethodException e) {
            res = svc.getClass().getMethod("getAllRootTaskInfos").invoke(svc);
        }
        if (res instanceof List) return (List<?>) res;
        if (res != null && res.getClass().isArray()) return Arrays.asList((Object[]) res);
        return Collections.emptyList();
    }

    private static int stackOrRootTaskId(Object info) {
        Object v = readFieldNoThrow(info, "stackId");
        if (v instanceof Integer) return (Integer) v;
        Object v2 = readFieldNoThrow(info, "taskId");
        if (v2 instanceof Integer) return (Integer) v2;
        return -1;
    }

    private static int[] childTaskIds(Object info) {
        Object v = readFieldNoThrow(info, "taskIds");
        if (v instanceof int[]) return (int[]) v;
        Object v2 = readFieldNoThrow(info, "childTaskIds");
        if (v2 instanceof int[]) return (int[]) v2;
        return null;
    }

    private static Object readFieldNoThrow(Object target, String fieldName) {
        try {
            Field f = target.getClass().getField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── IActivityTaskManager reflection ──────────────────────────────────────────────

    /** Resolve {@code ActivityTaskManager.getService()} once, falling back to the pre-split
     *  {@code ActivityManager.getService()} on older builds where windowing lives on IAM.
     *  Returns null on any denied build. */
    private static Object service() {
        Object cached = atm;
        if (cached != null) return cached;
        if (atmResolved) return null;
        synchronized (ClusterFreeformWindow.class) {
            if (atmResolved) return atm;
            Object svc = null;
            for (String cls : new String[] {
                    "android.app.ActivityTaskManager", "android.app.ActivityManager" }) {
                try {
                    svc = Class.forName(cls).getMethod("getService").invoke(null);
                    if (svc != null) {
                        logger.info("windowing service resolved via " + cls);
                        break;
                    }
                } catch (Throwable ignored) { /* try the next class */ }
            }
            if (svc == null) logger.info("IActivityTaskManager unavailable on this build");
            atm = svc;
            atmResolved = true;
            return atm;
        }
    }

    /** {@code setTaskResizeable(int taskId, int resizeableMode)} — make the task resizeable so
     *  a freeform mode change is honoured. Non-fatal if the method is absent. */
    private static boolean invokeSetTaskResizeable(Object svc, int taskId, int mode) {
        try {
            Method m = svc.getClass().getMethod("setTaskResizeable", int.class, int.class);
            m.invoke(svc, taskId, mode);
            return true;
        } catch (Throwable t) {
            logger.debug("setTaskResizeable failed: " + unwrap(t));
            return false;
        }
    }

    /** {@code setTaskWindowingMode(int taskId, int mode, boolean toTop)} with a
     *  {@code setCustomTaskWindowingMode} fallback (builds differ on the name). */
    /**
     * <p>Deliberately does NOT route through {@code setTaskWindowingModeSplitScreenPrimary}: on this
     * AMS that method's 2nd arg is a {@code SPLIT_SCREEN_CREATE_MODE_*}, not a windowing mode, and
     * its target mode is hard-coded to SPLIT_SCREEN_PRIMARY — it cannot produce FREEFORM. Note AMS's
     * own {@code setTaskWindowingMode} forwards to it when asked for mode 3, which is another reason
     * to only ever pass FREEFORM (5) or FULLSCREEN (1) here.
     */
    private static boolean invokeSetTaskWindowingMode(Object svc, int taskId, int mode) {
        for (String name : new String[] { "setTaskWindowingMode", "setCustomTaskWindowingMode" }) {
            try {
                Method m = svc.getClass().getMethod(name, int.class, int.class, boolean.class);
                m.invoke(svc, taskId, mode, true);
                return true;
            } catch (NoSuchMethodException ignored) {
                /* try the alternate spelling */
            } catch (Throwable t) {
                logger.debug(name + "(" + mode + ") failed: " + unwrap(t));
                return false;
            }
        }
        return false;
    }

    private static synchronized void loadFreeformSuppression() {
        if (freeformSuppressionLoaded) return;
        freeformSuppressionLoaded = true;
        JSONObject projection = UnifiedConfigManager.loadConfig().optJSONObject("projection");
        JSONObject saved = projection == null
                ? null : projection.optJSONObject(FREEFORM_SUPPRESSION_KEY);
        if (saved == null) return;
        String currentBoot = freeformBootId();
        if (currentBoot.isEmpty() || !currentBoot.equals(saved.optString("bootId", ""))) {
            try {
                UnifiedConfigManager.updateSection("projection",
                        new JSONObject().put(FREEFORM_SUPPRESSION_KEY, JSONObject.NULL));
            } catch (Throwable ignored) { }
            return;
        }
        org.json.JSONArray packages = saved.optJSONArray("packages");
        if (packages != null) {
            for (int i = 0; i < packages.length(); i++) {
                String pkg = packages.optString(i, "");
                if (AppLauncher.isValidPackageName(pkg)) freeformSuppressed.add(pkg);
            }
        }
        JSONObject losses = saved.optJSONObject("losses");
        if (losses != null) {
            java.util.Iterator<String> keys = losses.keys();
            while (keys.hasNext()) {
                String pkg = keys.next();
                int count = losses.optInt(pkg, 0);
                if (AppLauncher.isValidPackageName(pkg) && count > 0) {
                    freeformLosses.put(pkg, Math.min(2, count));
                }
            }
        }
    }

    static boolean shouldSuppressFreeform(boolean diLink5Transport, boolean packageSuppressed) {
        return diLink5Transport && packageSuppressed;
    }

    private static synchronized void persistFreeformSuppression() {
        try {
            org.json.JSONArray packages = new org.json.JSONArray();
            for (String pkg : freeformSuppressed) packages.put(pkg);
            JSONObject losses = new JSONObject();
            for (java.util.Map.Entry<String, Integer> entry : freeformLosses.entrySet()) {
                losses.put(entry.getKey(), entry.getValue());
            }
            JSONObject value = new JSONObject()
                    .put("bootId", freeformBootId())
                    .put("packages", packages)
                    .put("losses", losses);
            UnifiedConfigManager.updateSection("projection",
                    new JSONObject().put(FREEFORM_SUPPRESSION_KEY, value));
        } catch (Throwable ignored) { }
    }

    /**
     * Reconcile the durable freeform-setting owner after process/daemon restart. No write is
     * performed here: a matching expected value remains owned, a confirmed prior value clears the
     * record, and external drift relinquishes ownership without overwriting it.
     */
    static synchronized void recoverFreeformSupportOwnership() {
        if (freeformRecoveryChecked) return;
        freeformRecoveryChecked = true;
        JSONObject projection = UnifiedConfigManager.loadConfig().optJSONObject("projection");
        JSONObject ownership = projection == null
                ? null : projection.optJSONObject(FREEFORM_RECOVERY_KEY);
        if (ownership == null) return;
        String prior = ownership.optString("prior", null);
        String expected = ownership.optString("expected", null);
        String originBoot = ownership.optString("bootId", "");
        String current = (prior == null || expected == null) ? "" :
                runSettingsCmd("settings get global " + SETTING_ENABLE_FREEFORM);
        String decision = freeformRecoveryDecision(prior, expected, current);
        if ("unreadable".equals(decision)) {
            freeformSettingState = "ownership_unreadable";
            freeformSettingChecked = true;
            return;
        }
        if ("restored".equals(decision)) {
            if (clearFreeformOwnership()) {
                freeformSettingState = "restored";
                freeformSettingChecked = false;
            }
            return;
        }
        if ("expected".equals(decision)) {
            String boot = freeformBootId();
            if (!boot.isEmpty() && !boot.equals(originBoot)) {
                try {
                    ownership.put("bootId", boot);
                    ownership.put("uncertain", false);
                    UnifiedConfigManager.updateSection("projection",
                            new JSONObject().put(FREEFORM_RECOVERY_KEY, ownership));
                } catch (Throwable ignored) { }
            }
            freeformSettingState = "owned_expected";
            freeformSettingChecked = true;
            return;
        }
        // Another actor owns the new value. Relinquish our record without changing their state.
        if (clearFreeformOwnership()) freeformSettingState = "external_change";
        freeformSettingChecked = false;
    }

    static String freeformRecoveryDecision(String prior, String expected, String current) {
        if (prior == null || expected == null || current == null || current.isEmpty()) {
            return "unreadable";
        }
        if (prior.equals(current)) return "restored";
        if (expected.equals(current)) return "expected";
        return "external";
    }

    private static boolean clearFreeformOwnership() {
        try {
            return UnifiedConfigManager.updateSection("projection",
                    new JSONObject().put(FREEFORM_RECOVERY_KEY, JSONObject.NULL));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Best-effort: turn ON {@code Settings.Global.enable_freeform_support} so freeform windowing is
     * available to AMS at all.
     *
     * <h4>Why this is necessary, and why it is not sufficient</h4>
     * {@code ActivityDisplay.isWindowingModeSupported} contains
     * {@code if (!supportsFreeform && windowingMode == WINDOWING_MODE_FREEFORM) return false;}, and
     * {@code supportsFreeform} comes from {@code ActivityTaskManagerService
     * .mSupportsFreeformWindowManagement}, which {@code retrieveSettings()} computes as
     * {@code hasSystemFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT) || Settings.Global.getInt(
     * DEVELOPMENT_ENABLE_FREEFORM_WINDOWS_SUPPORT, 0) != 0}. A car head unit does not ship the
     * system feature, so unless this setting is on, {@code stack.setWindowingMode(FREEFORM)} is
     * silently resolved away and EVERY freeform verb is a guaranteed no-op — no exception, nothing
     * moves. That is the deepest layer of the original bug.
     *
     * <p><b>AMS caches it.</b> {@code retrieveSettings()} runs at boot, so flipping the setting does
     * NOT affect the running AMS instance. We write it anyway (idempotent, cheap) so the capability
     * exists after the next reboot, and we surface the situation honestly: {@link #applyBounds}
     * reports {@code applied=false} via real bounds verification rather than pretending. Requires
     * {@code WRITE_SECURE_SETTINGS}, which the daemon has as uid 2000 (shell).
     */
    private static void ensureFreeformSupportEnabled() {
        recoverFreeformSupportOwnership();
        if (freeformSettingChecked) return;
        synchronized (ClusterFreeformWindow.class) {
            if (freeformSettingChecked) return;
            String cur = runSettingsCmd("settings get global " + SETTING_ENABLE_FREEFORM);
            if ("1".equals(cur)) {
                freeformSettingState = "already_enabled";
                freeformSettingChecked = true;
                logger.info("freeform support already enabled");
                return;
            }
            if (!("0".equals(cur) || "null".equals(cur))) {
                freeformSettingState = "unreadable";
                freeformSettingChecked = true;
                logger.warn("freeform support setting is unreadable; refusing an unowned write");
                return;
            }
            String split = runSettingsCmd(
                    "settings get global enable_non_resizable_multi_window");
            JSONObject ownership = new JSONObject();
            try {
                ownership.put("prior", cur);
                ownership.put("expected", "1");
                ownership.put("splitPrior", split);
                ownership.put("bootId", freeformBootId());
                ownership.put("uncertain", true);
                if (!UnifiedConfigManager.updateSection("projection",
                        new JSONObject().put(FREEFORM_RECOVERY_KEY, ownership))) {
                    freeformSettingState = "ownership_persist_failed";
                    freeformSettingChecked = true;
                    return;
                }
            } catch (Throwable t) {
                freeformSettingState = "ownership_persist_failed";
                freeformSettingChecked = true;
                return;
            }
            String readback = runSettingsCmd("settings put global " + SETTING_ENABLE_FREEFORM
                    + " 1 && settings get global " + SETTING_ENABLE_FREEFORM);
            freeformSettingState = "1".equals(readback) ? "configured_next_boot" : "write_unconfirmed";
            try {
                ownership.put("uncertain", !"1".equals(readback));
                UnifiedConfigManager.updateSection("projection",
                        new JSONObject().put(FREEFORM_RECOVERY_KEY, ownership));
            } catch (Throwable ignored) { }
            freeformSettingChecked = true;
            logger.warn("freeform setting state=" + freeformSettingState
                    + "; current-boot placement support=" + freeformCurrentBootSupported
                    + "; split-window setting was preserved as '" + split + "'");
        }
    }

    static boolean prepareHostedFreeformState() {
        recoverFreeformSupportOwnership();
        JSONObject projection = UnifiedConfigManager.loadConfig().optJSONObject("projection");
        JSONObject ownership = projection == null
                ? null : projection.optJSONObject(FREEFORM_RECOVERY_KEY);
        if (ownership == null) return true;
        String split = runSettingsCmd("settings get global enable_non_resizable_multi_window");
        if (split.isEmpty()) {
            freeformSettingState = "split_state_unreadable";
            return false;
        }
        if (!("0".equals(split) || "null".equals(split))) {
            freeformSettingState = "preserved_for_split";
            return true;
        }
        String prior = ownership.optString("prior", null);
        String expected = ownership.optString("expected", null);
        if (!("0".equals(prior) || "null".equals(prior)) || !"1".equals(expected)) {
            freeformSettingState = "ownership_unreadable";
            return false;
        }
        String current = runSettingsCmd("settings get global " + SETTING_ENABLE_FREEFORM);
        String decision = freeformRecoveryDecision(prior, expected, current);
        if ("restored".equals(decision)) return clearFreeformOwnership();
        if (!"expected".equals(decision)) return false;
        String command = "null".equals(prior)
                ? "settings delete global " + SETTING_ENABLE_FREEFORM
                    + " && settings get global " + SETTING_ENABLE_FREEFORM
                : "settings put global " + SETTING_ENABLE_FREEFORM + " 0"
                    + " && settings get global " + SETTING_ENABLE_FREEFORM;
        String readback = runSettingsCmd(command);
        if (!prior.equals(readback)) {
            freeformSettingState = "restore_unconfirmed";
            return false;
        }
        if (!clearFreeformOwnership()) return false;
        freeformSettingChecked = false;
        freeformRecoveryChecked = true;
        freeformSettingState = "restored_for_hosted";
        return true;
    }

    static boolean hasFreeformSupportOwnership() {
        JSONObject projection = UnifiedConfigManager.loadConfig().optJSONObject("projection");
        return projection != null && projection.optJSONObject(FREEFORM_RECOVERY_KEY) != null;
    }

    static JSONObject freeformSupportStatus() {
        loadFreeformSuppression();
        recoverFreeformSupportOwnership();
        JSONObject result = new JSONObject();
        try {
            result.put("settingState", freeformSettingState);
            result.put("currentBootSupported", freeformCurrentBootSupported);
            result.put("suppressedPackages", freeformSuppressed.size());
            JSONObject ownership = UnifiedConfigManager.loadConfig().optJSONObject("projection");
            result.put("ownershipPending", ownership != null
                    && ownership.optJSONObject(FREEFORM_RECOVERY_KEY) != null);
        } catch (Throwable ignored) { }
        return result;
    }

    private static String freeformBootId() {
        try {
            return new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("/proc/sys/kernel/random/boot_id"))).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Run a short {@code settings} command and return its trimmed output ("" on any failure).
     * Bounded at 3s with the read on a DAEMON thread and the process destroyed in a finally: a
     * blocking {@code readLine()} on a child that never reaches EOF would otherwise hang this
     * (HTTP worker) thread past the timeout, and an un-destroyed process would leak an fd per call.
     */
    private static String runSettingsCmd(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd + " 2>&1")).redirectErrorStream(true).start();
            final Process fp = p;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(fp.getInputStream()));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (sb.length() < 256) sb.append(line.trim());
                    }
                } catch (Throwable ignored) { /* partial/empty output is fine */ }
            }, "ClusterFreeformSettings");
            reader.setDaemon(true);
            reader.start();
            boolean completed = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) p.destroyForcibly();
            reader.join(500);
            if (!completed || reader.isAlive() || p.exitValue() != 0) return "";
            return sb.toString();
        } catch (Throwable t) {
            logger.debug("settings cmd failed (" + cmd + "): " + t.getMessage());
            return "";
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    /** {@code resizeTask(int taskId, Rect bounds, int resizeMode)}, falling back to the 2-arg
     *  {@code resizeTask(int, Rect)}. Method handle is cached — this is the live-drag hot path. */
    private static boolean invokeResizeTask(Object svc, int taskId, Rect bounds) {
        Method m = cachedResizeTask;
        if (m == null) {
            m = findMethod(svc, "resizeTask",
                    new Class<?>[] { int.class, Rect.class, int.class },
                    new Class<?>[] { int.class, Rect.class });
            if (m == null) return false;
            cachedResizeTask = m;
        }
        try {
            if (m.getParameterTypes().length == 3) m.invoke(svc, taskId, bounds, RESIZE_MODE_USER);
            else m.invoke(svc, taskId, bounds);
            return true;
        } catch (Throwable t) {
            logger.debug("resizeTask failed: " + unwrap(t));
            // A recycled/dead task id is the likely cause — force a re-resolve next time.
            invalidateTaskCache();
            return false;
        }
    }

    /** {@code resizeTask(taskId, null, …)} — clear remembered freeform bounds on restore. */
    private static void invokeResizeTaskNull(Object svc, int taskId) {
        Method m = findMethod(svc, "resizeTask",
                new Class<?>[] { int.class, Rect.class, int.class },
                new Class<?>[] { int.class, Rect.class });
        if (m == null) return;
        try {
            if (m.getParameterTypes().length == 3) m.invoke(svc, taskId, null, RESIZE_MODE_USER);
            else m.invoke(svc, taskId, (Rect) null);
        } catch (Throwable t) {
            logger.debug("resizeTask(null) failed: " + unwrap(t));
        }
    }

    /**
     * {@code setStackWindowingMode(stackId, mode)} — take the cast app's parent stack out of
     * FULLSCREEN so it stops forcing fullscreen bounds onto its child task. Without this rung a
     * task-level resize is accepted and then silently reverted. Three method spellings and both
     * the 2-arg and 3-arg (trailing {@code toTop}) shapes are tried.
     */
    private static boolean invokeSetStackWindowingMode(Object svc, int stackId, int mode) {
        for (String name : new String[] { "setActivityStackWindowingMode",
                                          "setStackWindowingMode",
                                          "setActivityStackWindowingModeForced" }) {
            for (Method cand : svc.getClass().getMethods()) {
                if (!cand.getName().equals(name)) continue;
                Class<?>[] pt = cand.getParameterTypes();
                try {
                    if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                        cand.invoke(svc, stackId, mode);
                        return true;
                    }
                    if (pt.length == 3 && pt[0] == int.class && pt[1] == int.class
                            && pt[2] == boolean.class) {
                        cand.invoke(svc, stackId, mode, true);
                        return true;
                    }
                } catch (Throwable t) {
                    logger.debug(name + "(" + stackId + "," + mode + ") failed: " + unwrap(t));
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * {@code resizeStack(stackId, bounds, allowResizeInDockedMode, preserveWindows, animate,
     * animationDuration)} — give the (now non-fullscreen) stack the target bounds so the child
     * task has a parent rect to inherit. Falls back to the 2-arg {@code resizeStack(int, Rect)}.
     */
    private static boolean invokeResizeStack(Object svc, int stackId, Rect bounds) {
        for (Method cand : svc.getClass().getMethods()) {
            if (!cand.getName().equals("resizeStack")) continue;
            Class<?>[] pt = cand.getParameterTypes();
            try {
                if (pt.length == 6 && pt[0] == int.class && pt[1] == Rect.class
                        && pt[2] == boolean.class && pt[3] == boolean.class
                        && pt[4] == boolean.class && pt[5] == int.class) {
                    cand.invoke(svc, stackId, bounds, true, true, false, -1);
                    return true;
                }
                if (pt.length == 2 && pt[0] == int.class && pt[1] == Rect.class) {
                    cand.invoke(svc, stackId, bounds);
                    return true;
                }
            } catch (Throwable t) {
                logger.debug("resizeStack failed: " + unwrap(t));
                return false;
            }
        }
        return false;
    }

    /** {@code resizeStack(stackId, null, …)} — clear the stack's remembered bounds on restore.
     *  Validates the FULL 6-arg signature (not just the first two params): a build exposing a
     *  differently-shaped {@code resizeStack(int, Rect, …)} would otherwise be handed
     *  {@code (true, true, false, -1)} against mismatched types, throw IllegalArgumentException,
     *  and leave a freeform stack behind — the exact residue this method exists to clear. */
    private static void invokeResizeStackNull(Object svc, int stackId) {
        for (Method cand : svc.getClass().getMethods()) {
            if (!cand.getName().equals("resizeStack")) continue;
            Class<?>[] pt = cand.getParameterTypes();
            try {
                if (pt.length == 6 && pt[0] == int.class && pt[1] == Rect.class
                        && pt[2] == boolean.class && pt[3] == boolean.class
                        && pt[4] == boolean.class && pt[5] == int.class) {
                    cand.invoke(svc, stackId, null, true, true, false, -1);
                    return;
                }
                if (pt.length == 2 && pt[0] == int.class && pt[1] == Rect.class) {
                    cand.invoke(svc, stackId, (Rect) null);
                    return;
                }
            } catch (Throwable t) {
                logger.debug("resizeStack(null) failed: " + unwrap(t));
                return;
            }
        }
    }

    /** {@code setFocusedRootTask(taskId)} / {@code setFocusedTask(taskId)} — make the resized
     *  window the focused one on the cluster so it receives the relayed touches. */
    private static void invokeSetFocusedRootTask(Object svc, int taskId) {
        for (String name : new String[] { "setFocusedRootTask", "setFocusedTask" }) {
            try {
                Method m = svc.getClass().getMethod(name, int.class);
                m.invoke(svc, taskId);
                return;
            } catch (NoSuchMethodException ignored) {
                /* try the alternate spelling */
            } catch (Throwable t) {
                logger.debug(name + " failed: " + unwrap(t));
                return;
            }
        }
    }

    /**
     * Last-resort shell resize, used only when the binder path verified as NOT applied:
     * {@code cmd activity task resize <id> <l> <t> <r> <b>} with the legacy {@code am task
     * resize} spelling as backup. Kept off the normal path — it forks a process, so it must
     * never run on a drag frame.
     *
     * <p><b>The exit code cannot be trusted here.</b> Verified against
     * {@code ActivityManagerShellCommand.runTaskResize} (android-29): it returns -1 ONLY for
     * unparseable bounds, and otherwise returns 0 even when the underlying
     * {@code resizeTask} throws — the failure is merely PRINTED (as {@code Error: …}) by
     * {@code ShellCommand.exec}'s handler. An unknown subcommand likewise prints
     * {@code Error: unknown command} to stderr. So we merge stderr into stdout and treat any
     * {@code Error}/{@code Exception} text as failure, mirroring the reference's output-sniffing
     * ({@code ShellTaskResizer}) rather than trusting the status. The caller re-verifies with
     * {@code getTaskBounds} regardless, so this is a fast reject, not the final word.
     */
    private static boolean shellResizeTask(int taskId, Rect b) {
        String coords = taskId + " " + b.left + " " + b.top + " " + b.right + " " + b.bottom;
        for (String cmd : new String[] { "cmd activity task resize " + coords,
                                         "am task resize " + coords }) {
            try {
                Process p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd + " 2>&1"))
                        .redirectErrorStream(true).start();
                StringBuilder sb = new StringBuilder();
                Thread reader = new Thread(() -> {
                    try {
                        java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(p.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (sb.length() < 4096) sb.append(line).append('\n');
                        }
                    } catch (Throwable ignored) { /* output stays partial */ }
                }, "ClusterFreeformShellResize");
                reader.setDaemon(true);
                reader.start();
                boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                if (!done) { p.destroyForcibly(); reader.join(200); continue; }
                reader.join(500);
                String out = sb.toString();
                String low = out.toLowerCase(java.util.Locale.US);
                boolean looksOk = p.exitValue() == 0
                        && !low.contains("error")
                        && !low.contains("exception")
                        && !low.contains("unknown command");
                if (looksOk) {
                    logger.info("shell resize OK: " + cmd);
                    return true;
                }
                logger.debug("shell resize rejected (" + cmd + "): exit=" + p.exitValue()
                        + " out=" + out.trim());
            } catch (Throwable t) {
                logger.debug("shell resize failed (" + cmd + "): " + t.getMessage());
            }
        }
        return false;
    }

    /** First method named {@code name} matching {@code preferred}, else {@code fallback}. */
    private static Method findMethod(Object svc, String name,
                                     Class<?>[] preferred, Class<?>[] fallback) {
        Method alt = null;
        for (Method cand : svc.getClass().getMethods()) {
            if (!cand.getName().equals(name)) continue;
            Class<?>[] pt = cand.getParameterTypes();
            if (Arrays.equals(pt, preferred)) return cand;
            if (Arrays.equals(pt, fallback)) alt = cand;
        }
        return alt;
    }

    /** Unwrap an InvocationTargetException to the real cause for readable logs. */
    private static String unwrap(Throwable t) {
        Throwable c = (t instanceof InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
