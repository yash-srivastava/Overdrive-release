package com.overdrive.app.launcher;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.ClusterProjectionController;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Casts an arbitrary installed app onto the BYD driver-cluster (fission) display
 * for the "Move app to display → cluster" automation / key-mapping action.
 *
 * <p><b>Why this is not a bare {@code am start --display 1}.</b> On this firmware the
 * cluster display does NOT exist until the OEM projection is opened: the fission
 * {@code VirtualDisplay} is created by the projection open-sequence (size-profile →
 * fullscreen-on → Di4 mode) that {@link ClusterProjectionController} owns, and
 * SurfaceFlinger assigns its logical id per-open (it is NOT reliably 1 — on some trims
 * display 1 is the HEAD UNIT). A raw {@code --display 1} therefore either lands on
 * nothing or clobbers the infotainment screen. This mirrors the proven
 * {@code ClusterMapProjector} flow: acquire the projection as a sustained holder, wait
 * for the live fission display id to materialise, then {@code am start --display <id>
 * --windowingMode 1} the target app onto it. The gauge-restore safety net
 * (forceClose on ACC-off / disable / SIGTERM) is entirely inside the controller and is
 * unaffected by this class.
 *
 * <p><b>Difference from the map cast.</b> The map is OUR cooperative Activity (a
 * dedicated cluster alias that self-finishes off a UCM flag and is kept alive by a
 * daemon watchdog). A third-party app is NOT cooperative: the daemon (uid 2000) cannot
 * {@code finish()} a foreign uid-1000 Activity, and a non-touch 1920×720 panel offers
 * no way for the user to dismiss it. So this class deliberately does NOT run a
 * keep-alive watchdog or a self-finish handshake — it launches once (with a short
 * resume-verify + retry, since {@code am start} exits on ACCEPT not RESUMED) and holds
 * the projection open under the {@code "castapp"} token until {@link #stop()} (moving
 * back to the head unit, an explicit stop action, or ACC-off) releases it and the
 * controller restores the gauges. Holding open is what keeps the cast app visible; a
 * swipe-away simply leaves the projection showing whatever the OEM puts there until
 * stop, which is the safest non-cooperative behaviour (never repaints a ghost).
 *
 * <p>Runs the wait+launch off the caller's thread (the HTTP worker), exactly like the
 * map projector, so a slow projection open never blocks the request.
 */
public final class ClusterCast {

    private static final String TAG = "ClusterCast";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Distinct sustained token so this coexists with the map/blind-spot holders. */
    private static final String TOKEN = "castapp";

    // WINDOWING_MODE_FULLSCREEN — fills the whole fission panel. FREEFORM (5) placed a
    // ~270px floating box (the "small projection" symptom the map hit); fullscreen is
    // what the map + blind-spot use on this display. Verified via ClusterMapProjector.
    private static final String WINDOWING_MODE_FULLSCREEN = "1";
    // NEW_TASK only (0x10000000): the daemon Context is not an Activity, and a foreign
    // app owns its own task affinity, so NEW_TASK is sufficient and correct.
    private static final String LAUNCH_FLAGS = "0x10000000";

    // Poll budget for the fission display to appear after the projection opens
    // (materialises ~1-3s after the open opcodes). Mirrors ClusterMapProjector.
    private static final int READY_POLL_MS = 250;
    private static final int READY_TIMEOUT_MS = 8000;
    // Post-launch resume verify+retry (am start exits on ACCEPT, not RESUMED-on-display).
    private static final int LAUNCH_VERIFY_ATTEMPTS = 3;
    private static final int LAUNCH_VERIFY_POLLS_PER_ATTEMPT = 6;
    private static final int LAUNCH_VERIFY_POLL_MS = 300;
    private static final long RESUMED_STATE_TIMEOUT_MS = 1500L;
    // startAfterSettle polling; the grace covers the instant a close hands admission back.
    private static final long SETTLE_POLL_MS = 200L;
    private static final long SETTLE_GRACE_MS = 1000L;
    private static final Pattern RESUMED_COMPONENT_PATTERN = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)"
                    + "/[A-Za-z0-9_.$]+");

    // ── Freeform-on-cluster resize. When ON, the cast app is CONVERTED to freeform at explicit
    // bounds AFTER it resumes fullscreen (the reliable path — never bare `am start
    // --windowingMode 5`, which drops it at AMS's ~270px default rect), and the Projection box
    // resizes it live via ClusterFreeformWindow.applyBounds. Read live from
    // surveillance.clusterFreeformResize so it can be flipped without a rebuild.
    //
    // DEFAULT ON: there is no UI toggle for this, and it is scoped so it CANNOT affect the
    // map or blind-spot — it only reshapes the CAST APP's own task (task/stack windowing +
    // resizeTask), never the display config, and only while a cast is active. Blind-spot is a
    // SurfaceControl layer (no task, structurally immune). The map is NOT protected merely by
    // "castPkg is null": the map lives in com.overdrive.app, which is itself a launchable package,
    // so a self-cast would make a by-package task lookup ambiguous. The real guard is that every
    // resize is scoped to `castDisplayId` (and the picker hides OverDrive) — see
    // ClusterFreeformWindow's class doc. On stop the task is restored to fullscreen before
    // reparent so the display is left pristine. Flip to false in
    // surveillance.clusterFreeformResize to force the old always-fullscreen cast. ──────
    private static final String FLAG_FREEFORM_RESIZE = "clusterFreeformResize";
    private static final boolean FREEFORM_RESIZE_DEFAULT = true;

    // Requested freeform bounds in CLUSTER px (null = full panel / not set). Written by
    // resize() on a box commit and read by the launch thread when it converts to freeform, so
    // the first freeform apply already lands at the user's last box. Guarded by the class lock.
    private static android.graphics.Rect freeformBounds;

    // The package currently cast (for logging + so a second cast replaces the first).
    private static volatile String castPkg;
    // The fission display the cast app was actually placed on, or -1 before the launch thread
    // resolves it. Every freeform resize is SCOPED to this display so a package that owns tasks on
    // several displays can only ever have its CLUSTER task resized. That matters concretely for
    // com.overdrive.app itself: it is a launchable package (so it can be cast) and it also owns the
    // head-unit UI task and the nav-map cluster task, so an unscoped by-package lookup could resize
    // the wrong one. See ClusterFreeformWindow.findTaskIdViaAtm.
    private static volatile int castDisplayId = -1;
    private static volatile Thread launchThread;
    // Monotonic identity for a cast accepted by start(). Mirror owner-death cleanup captures
    // this value so a delayed death callback cannot stop a newer replacement cast.
    private static long castSessionGeneration;
    // A stop reparents before releasing the projection token. Reject a concurrent start during
    // that bounded transition, otherwise the old stop could release the new cast's same token.
    private static boolean castStopInProgress;
    private static long castStopGeneration;

    // SIGKILL boot-recovery flag. Persisted (UCM surveillance section, beside the projection
    // gauge-restore gate flags in ClusterProjectionController — the analogous backstop for the
    // gauges) so that if the daemon is SIGKILLed mid-cast — no clean stop(), no shutdown hook —
    // the next respawn can pull the stranded foreign app off the (now-closed) cluster display
    // back to the head unit. Holds the committed cast package; empty when no cast needs
    // recovery. Set at the commit point in start(); cleared when we cleanly rehome the app to
    // display 0 (stop's CLEAR_AFFINITY / FOREGROUND) or after the boot sweep reparents it.
    private static final String CAST_STRANDED_PKG = "clusterCastStrandedPkg";

    private ClusterCast() {}

    /** True when the freeform-on-cluster resize feature is enabled (UCM
     *  surveillance.clusterFreeformResize, default {@link #FREEFORM_RESIZE_DEFAULT} = ON). Read
     *  live + fully guarded so a missing/garbled config falls back to the default. */
    private static boolean freeformResizeEnabled() {
        try {
            org.json.JSONObject s = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
            return s != null
                    ? s.optBoolean(FLAG_FREEFORM_RESIZE, FREEFORM_RESIZE_DEFAULT)
                    : FREEFORM_RESIZE_DEFAULT;
        } catch (Throwable t) {
            return FREEFORM_RESIZE_DEFAULT;
        }
    }

    /** Snapshot of the requested freeform bounds (a copy, so callers can't mutate shared
     *  state), or null if none set yet. */
    private static synchronized android.graphics.Rect freeformBoundsCopy() {
        return freeformBounds != null ? new android.graphics.Rect(freeformBounds) : null;
    }

    // ── Persisted per-app box geometry (daemon-readable) ─────────────────────────────
    //
    // WHY THIS EXISTS. freeformBounds is only ever populated by a LIVE Projection screen
    // (POST /api/vehicle/cluster-resize). Every cast that has no UI in the loop — the ACC-on
    // auto-cast, a key-mapping cast, an automation cast — therefore launched with null bounds
    // and maybeApplyFreeform() bailed out, leaving the app FULLSCREEN. The user's saved scale
    // only appeared once they opened Projection and scrolled to the preview, which re-sent the
    // rect. So the box is now ALSO persisted here, in the unified config (uid-2000 readable,
    // unlike the fragment's app-UID SharedPreferences), and re-loaded at cast time.
    //
    // Stored NORMALIZED (0..1 fractions of the panel) under projection.windows.<pkg>, matching
    // what the fragment persists, so the rect stays correct if the panel size profile differs
    // from the one it was captured on.
    private static final String PROJECTION_SECTION = "projection";
    private static final String WINDOWS_KEY = "windows";
    /** Fraction slack within which a saved box counts as the full panel (≈2px on 1920). */
    private static final double FULL_PANEL_EPSILON = 0.001;

    /**
     * Persist a committed box for {@code pkg} as panel FRACTIONS so any later cast of that app
     * (auto-start, key mapping, automation) can reproduce it with no UI in the loop. Called only
     * on a COMMIT — never per drag frame, which would thrash the config file.
     */
    private static void persistWindowFractions(String pkg, int l, int t, int r, int b) {
        if (pkg == null || pkg.isEmpty()) return;
        int[] panel = clusterPanelSizeCached();
        int pw = panel[0], ph = panel[1];
        if (pw <= 1 || ph <= 1) return;   // can't normalize without a real panel; skip silently
        saveWindowFractions(pkg, l / (double) pw, t / (double) ph,
                r / (double) pw, b / (double) ph);
    }

    /**
     * Store a per-app box already expressed as panel FRACTIONS (0..1). Public entry point for
     * {@code POST /api/vehicle/cluster-window}, which the Projection screen uses to save a box
     * adjusted while NO cast is active — the app-UID SharedPreferences it also writes are
     * unreadable by this (uid 2000) daemon, so without this the next headless cast would have
     * no geometry to apply. Returns false on a malformed/degenerate rect.
     */
    public static boolean saveWindowFractions(String pkg, double fl, double ft,
                                              double fr, double fb) {
        if (pkg == null || pkg.trim().isEmpty()) return false;
        if (Double.isNaN(fl) || Double.isNaN(ft) || Double.isNaN(fr) || Double.isNaN(fb)) return false;
        // Fractions only, and a real (non-inverted) rect. Anything else is a client bug — refuse
        // rather than store a rect that would later be handed to AMS.
        if (fl < 0 || ft < 0 || fr > 1 || fb > 1 || fr <= fl || fb <= ft) return false;
        final String key = pkg.trim();
        // FULL PANEL == "no saved box" (the fullscreen default), so it REMOVES the entry rather
        // than storing a rect loadPersistedBounds would reject anyway. Removing matters: resetting
        // a previously-shrunk app back to full must not leave the old small rect behind for the
        // next headless cast to apply. And when there is nothing to remove the whole call becomes a
        // no-op, which keeps browsing the app picker (each never-resized app resets to FULL) from
        // rewriting the config file on every selection.
        final boolean fullPanel = fl <= FULL_PANEL_EPSILON && ft <= FULL_PANEL_EPSILON
                && fr >= 1 - FULL_PANEL_EPSILON && fb >= 1 - FULL_PANEL_EPSILON;
        try {
            org.json.JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.loadConfig();
            org.json.JSONObject proj = cfg != null ? cfg.optJSONObject(PROJECTION_SECTION) : null;
            org.json.JSONObject windows = proj != null ? proj.optJSONObject(WINDOWS_KEY) : null;
            if (fullPanel && (windows == null || !windows.has(key))) return true;   // already absent
            // Copy so we never mutate the shared config cache in place.
            org.json.JSONObject merged = new org.json.JSONObject();
            if (windows != null) {
                java.util.Iterator<String> it = windows.keys();
                while (it.hasNext()) { String k = it.next(); merged.put(k, windows.get(k)); }
            }
            if (fullPanel) {
                merged.remove(key);
            } else {
                org.json.JSONObject rect = new org.json.JSONObject();
                rect.put("l", fl); rect.put("t", ft);
                rect.put("r", fr); rect.put("b", fb);
                merged.put(key, rect);
            }
            // updateSection merges at the SECTION level only, so autoStartOnAcc/autoStartPackage
            // survive — but the nested windows map must be merged by hand (above).
            return com.overdrive.app.config.UnifiedConfigManager.updateSection(
                    PROJECTION_SECTION, new org.json.JSONObject().put(WINDOWS_KEY, merged));
        } catch (Throwable t2) {
            logger.warn("cluster cast: persisting box for " + key + " failed: " + t2.getMessage());
            return false;
        }
    }

    /**
     * Load {@code pkg}'s persisted box and convert it back to CLUSTER px for the live panel.
     * Returns null when nothing is saved, the panel is unresolved, or the stored rect is
     * degenerate/full-panel (full panel = leave the app fullscreen, the documented default).
     */
    private static android.graphics.Rect loadPersistedBounds(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        int[] panel = clusterPanelSizeCached();
        int pw = panel[0], ph = panel[1];
        if (pw <= 1 || ph <= 1) return null;
        try {
            org.json.JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.loadConfig();
            org.json.JSONObject proj = cfg != null ? cfg.optJSONObject(PROJECTION_SECTION) : null;
            org.json.JSONObject windows = proj != null ? proj.optJSONObject(WINDOWS_KEY) : null;
            org.json.JSONObject rect = windows != null ? windows.optJSONObject(pkg) : null;
            if (rect == null) return null;
            double fl = rect.optDouble("l", Double.NaN), ft = rect.optDouble("t", Double.NaN);
            double fr = rect.optDouble("r", Double.NaN), fb = rect.optDouble("b", Double.NaN);
            if (Double.isNaN(fl) || Double.isNaN(ft) || Double.isNaN(fr) || Double.isNaN(fb)) {
                return null;
            }
            int l = (int) Math.round(fl * pw), t = (int) Math.round(ft * ph);
            int r = (int) Math.round(fr * pw), b = (int) Math.round(fb * ph);
            if (r - l < MIN_WINDOW_PX || b - t < MIN_WINDOW_PX) return null;
            // A saved FULL-panel box is the fullscreen default — applying freeform would only add
            // the freeform decoration/inset for no visible gain.
            if (l <= 0 && t <= 0 && r >= pw && b >= ph) return null;
            return clampToClusterPanel(l, t, r, b);
        } catch (Throwable e) {
            logger.warn("cluster cast: loading saved box for " + pkg + " failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Resize the LIVE cast app on the cluster to {@code bounds} (cluster px) — the Projection
     * box path. No-op (returns false) unless the freeform feature is enabled AND a cast is
     * currently active. Applies via {@link ClusterFreeformWindow#applyBounds} on the calling
     * (HTTP worker) thread; the reflection is cheap (no shell, no relaunch on the drag path).
     * The bounds are remembered so a subsequent relaunch/verify re-applies them.
     *
     * @param commit true for a gesture RELEASE / preset / restore — run the full escalation
     *   ladder and VERIFY that AMS honoured the rect. False for a live drag frame — issue a cheap
     *   bounds-only update so dragging tracks the finger without a stack scan or shell fork.
     */
    public static boolean resize(int l, int t, int r, int b, boolean commit) {
        return resizeWithResult(l, t, r, b, commit).applied;
    }

    /** What a resize request did to the cast app's window on the cluster. */
    public static final class ResizeResult {
        public final boolean applied;
        /** A drag frame the current placement only applies on release. */
        public final boolean deferred;
        /** A transient refusal (cast still starting) — a later try may land. */
        public final boolean retryable;
        /** Another app is on the cluster display, so the cast app was not docked. */
        public final boolean blocked;
        /** "fullscreen", "docked", "freeform", or "" when unknown. */
        public final String mode;
        /** The window rect the cluster actually holds (cluster px), or null when unknown. */
        public final android.graphics.Rect bounds;
        public final String reason;

        ResizeResult(boolean applied, boolean deferred, boolean retryable, boolean blocked,
                     String mode, android.graphics.Rect bounds, String reason) {
            this.applied = applied;
            this.deferred = deferred;
            this.retryable = retryable;
            this.blocked = blocked;
            this.mode = mode == null ? "" : mode;
            this.bounds = bounds;
            this.reason = reason == null ? "" : reason;
        }

        static ResizeResult of(boolean applied) {
            return new ResizeResult(applied, false, false, false, "", null, "");
        }

        static ResizeResult retry(String reason) {
            return new ResizeResult(false, false, true, false, "", null, reason);
        }
    }

    // Serializes every legacy window placement and teardown: a docked re-placement is several AMS
    // calls, and a stop's restore must not interleave with them. Never taken while holding the
    // ClusterCast monitor (placeLegacy takes that monitor inside this lock).
    private static final Object PLACEMENT_LOCK = new Object();
    /** Its other tasks (head-unit UI, nav map) must never be re-homed as a replaced cast. */
    private static final String OWN_PACKAGE = "com.overdrive.app";
    private static final int INITIAL_PLACEMENT_ATTEMPTS = 3;
    private static final int INITIAL_PLACEMENT_RETRY_MS = 800;

    /** Like {@link #resize} but reports what the cluster actually holds afterwards. */
    public static ResizeResult resizeWithResult(int l, int t, int r, int b, boolean commit) {
        return resizeWithResult(l, t, r, b, commit, null);
    }

    /**
     * @param forPkg the app the box describes, or null/empty when the caller does not say; a box
     *   meant for another app (the cast changed while the request was in flight) is ignored on the
     *   legacy path, so it can neither move nor be saved for the wrong app.
     */
    public static ResizeResult resizeWithResult(int l, int t, int r, int b, boolean commit,
                                                String forPkg) {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            return ResizeResult.of(DiLink5ClusterCast.resize(l, t, r, b, commit));
        }
        if (!freeformResizeEnabled()) return ResizeResult.of(false);
        if (r - l <= 0 || b - t <= 0) return ResizeResult.of(false);
        // CLAMP SERVER-SIDE to the real cluster panel. The geometry engine that produces this rect
        // (ProjectionBoundsGeometry) already clamps, but it runs in the UI process — this endpoint is
        // reachable by anything that can POST to the daemon, so the panel bound must be enforced here
        // too. Unclamped, a rect like (0,0,999999,999999) or one entirely off-panel would be handed
        // to resizeTask and could place the cast app's window somewhere the user cannot see or
        // recover on a non-touch cluster.
        android.graphics.Rect clamped = clampToClusterPanel(l, t, r, b);
        if (clamped == null) return ResizeResult.of(false);
        l = clamped.left; t = clamped.top; r = clamped.right; b = clamped.bottom;
        final String pkg;
        final long generation;
        synchronized (ClusterCast.class) {
            // castStopInProgress guards the check-then-act window: once a stop has begun (it sets
            // this true in the same locked section where it nulls castPkg + calls restoreFullscreen
            // outside the lock), a late box-commit must NOT re-apply freeform — otherwise applyBounds
            // could land after stop's restoreFullscreen and leave the app freeform as it's reparented
            // to the head unit.
            if (castPkg == null || castStopInProgress) return ResizeResult.of(false);
            if (forPkg != null && !forPkg.isEmpty() && !forPkg.equals(castPkg)) {
                return ResizeResult.of(false);
            }
            pkg = castPkg;
            generation = castSessionGeneration;
            freeformBounds = new android.graphics.Rect(l, t, r, b);
        }
        // PROJECTION-LIVENESS GATE, same as every other path (stillOurs/isActive). A DIRECT
        // forceClose (cluster relayout, blind-spot disable, cluster→head-unit retarget, ACC-off)
        // clears all sustained holders WITHOUT routing through stop(), so castPkg stays stale-true
        // until the next status poll reconciles it — up to a second. A drag landing in that window
        // would re-apply freeform bounds to our old app on a display the map or blind-spot now owns.
        // Checked OUTSIDE the monitor (holdsTokenStatic takes the controller's lock) to keep the
        // ClusterCast monitor free of nested lock acquisition.
        try {
            if (!ClusterProjectionController.holdsTokenStatic(TOKEN)) {
                logger.info("cluster cast: resize ignored — projection no longer holds our token");
                return ResizeResult.of(false);
            }
        } catch (Throwable ignored) { /* fail toward attempting the resize */ }
        // A docked re-placement passes through fullscreen, so drag frames would make the app flash
        // on every frame; docking applies on release only.
        if (!commit && ClusterFreeformWindow.dockingSupported()) {
            return new ResizeResult(false, true, false, false, "docked", null, "");
        }
        android.graphics.Rect requested = new android.graphics.Rect(l, t, r, b);
        ResizeResult result = placeLegacy(pkg, requested, commit, generation, false);
        // Persist the COMMITTED box (never a drag frame) so a later UI-less cast of this app —
        // ACC-on auto-start, key mapping, automation — can reproduce the user's scale itself.
        if (commit) {
            android.graphics.Rect kept = result.applied && result.bounds != null
                    && !result.bounds.isEmpty() ? result.bounds : requested;
            persistWindowFractions(pkg, kept.left, kept.top, kept.right, kept.bottom);
        }
        return result;
    }

    /**
     * Place the live legacy cast at {@code bounds}: docked where this AMS supports it, otherwise
     * the freeform ladder. A full-panel box means plain fullscreen. Re-validates the session under
     * {@link #PLACEMENT_LOCK} so a placement can never land after its cast was stopped or replaced.
     *
     * @param preferStaged use the newest staged box instead of {@code bounds}, read under the lock
     *   so a fresher commit that ran first is never overwritten by an older rect.
     */
    private static ResizeResult placeLegacy(String pkg, android.graphics.Rect bounds,
                                            boolean commit, long generation, boolean preferStaged) {
        synchronized (PLACEMENT_LOCK) {
            final int displayId;
            synchronized (ClusterCast.class) {
                if (!pkg.equals(castPkg) || castStopInProgress
                        || castSessionGeneration != generation) {
                    return ResizeResult.of(false);
                }
                displayId = castDisplayId;
                if (preferStaged && freeformBounds != null) {
                    bounds = new android.graphics.Rect(freeformBounds);
                }
            }
            if (displayId < 0) return ResizeResult.retry("cluster display not resolved yet");
            int[] panel = clusterPanelSizeCached();
            try {
                String dockBlocked = "";
                if (commit && ClusterFreeformWindow.dockingSupported()) {
                    ClusterFreeformWindow.Placement p = ClusterFreeformWindow.applyDocked(
                            pkg, bounds, displayId, panel[0], panel[1]);
                    if (p.status == ClusterFreeformWindow.Placement.APPLIED
                            || p.status == ClusterFreeformWindow.Placement.RETRY) {
                        return new ResizeResult(
                                p.status == ClusterFreeformWindow.Placement.APPLIED, false,
                                p.status == ClusterFreeformWindow.Placement.RETRY, false,
                                p.docked() ? "docked" : "fullscreen", p.actual, p.reason);
                    }
                    // Docking needs the cluster to itself and an AMS that accepts it. Where it
                    // cannot run, the freeform ladder still only touches our own task.
                    if (p.status == ClusterFreeformWindow.Placement.BLOCKED) dockBlocked = p.reason;
                    logger.info("cluster cast: docking did not place " + pkg + " ("
                            + p.reason + ") — using freeform");
                }
                boolean fullPanel = panel[0] > 1 && panel[1] > 1 && bounds.left <= 0
                        && bounds.top <= 0 && bounds.right >= panel[0] && bounds.bottom >= panel[1];
                if (fullPanel) {
                    ClusterFreeformWindow.restoreFullscreen(pkg, displayId);
                    return new ResizeResult(true, false, false, false, "fullscreen",
                            new android.graphics.Rect(0, 0, panel[0], panel[1]), "");
                }
                boolean ok = ClusterFreeformWindow.applyBounds(pkg, bounds, commit, displayId);
                return new ResizeResult(ok, false, false, !ok && !dockBlocked.isEmpty(), "freeform",
                        ok ? new android.graphics.Rect(bounds) : null,
                        ok ? "" : (dockBlocked.isEmpty()
                                ? "the cluster did not accept freeform bounds" : dockBlocked));
            } catch (Throwable e) {
                logger.warn("cluster cast: resize failed: " + e.getMessage());
                return new ResizeResult(false, false, false, false, "", null,
                        String.valueOf(e.getMessage()));
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean dockCleanupPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final long DOCK_CLEANUP_SETTLE_MS = 1500L;
    private static final long DOCK_CLEANUP_MAX_WAIT_MS = 20_000L;

    /**
     * A teardown that may do no AMS work (ACC-off, a forced projection close) leaves a docked cast
     * on the cluster display. Undock it once the projection close has settled, unless a newer cast
     * owns the display by then (its launch clears residue itself).
     */
    private static void scheduleDockResidueCleanup(final int displayId) {
        if (displayId <= 0 || !dockCleanupPending.compareAndSet(false, true)) return;
        try {
            Thread t = new Thread(() -> {
                try {
                    long deadline = android.os.SystemClock.elapsedRealtime()
                            + DOCK_CLEANUP_MAX_WAIT_MS;
                    Thread.sleep(DOCK_CLEANUP_SETTLE_MS);
                    while (ClusterProjectionController.isLegacyRestoreInFlight()
                            && android.os.SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(250L);
                    }
                    if (!ClusterFreeformWindow.dockingSupported()) return;
                    synchronized (PLACEMENT_LOCK) {
                        synchronized (ClusterCast.class) {
                            if (castPkg != null) return;
                        }
                        ClusterFreeformWindow.clearDisplayDock(displayId);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t2) {
                    logger.warn("cluster cast: dock residue cleanup failed: " + t2.getMessage());
                } finally {
                    dockCleanupPending.set(false);
                }
            }, "ClusterDockResidue");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            dockCleanupPending.set(false);
        }
    }

    /**
     * Undock a docked cast left on the legacy cluster display (its ACC-off teardown skips AMS
     * work) so a fullscreen launch there is not squeezed into split-screen-secondary.
     */
    public static void clearStaleClusterDock(int displayId) {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) return;
        synchronized (PLACEMENT_LOCK) {
            try {
                ClusterFreeformWindow.clearDisplayDock(displayId);
            } catch (Throwable t) {
                logger.warn("cluster cast: clearing dock residue failed: " + t.getMessage());
            }
        }
    }

    /**
     * Clamp a requested rect to the live cluster panel, preserving the requested size where it fits.
     * Returns null when the panel size cannot be resolved or the rect is degenerate — in that case we
     * refuse rather than guess, since a wrong rect on a non-touch cluster is not user-recoverable.
     * A minimum edge keeps the window grabbable/visible.
     */
    private static android.graphics.Rect clampToClusterPanel(int l, int t, int r, int b) {
        int[] panel = clusterPanelSizeCached();
        int pw = panel[0], ph = panel[1];
        if (pw <= 1 || ph <= 1) {
            // Unknown panel: accept only a rect that is already sane by construction (non-negative
            // origin and a plausible extent), so a malformed request still cannot reach AMS.
            if (l < 0 || t < 0 || r > MAX_PLAUSIBLE_PANEL_PX || b > MAX_PLAUSIBLE_PANEL_PX) {
                logger.warn("cluster cast: resize refused — panel size unknown and rect implausible "
                        + "[" + l + "," + t + "," + r + "," + b + "]");
                return null;
            }
            return new android.graphics.Rect(l, t, r, b);
        }
        int minW = Math.min(MIN_WINDOW_PX, pw);
        int minH = Math.min(MIN_WINDOW_PX, ph);
        int w = Math.max(minW, Math.min(r - l, pw));
        int h = Math.max(minH, Math.min(b - t, ph));
        int left = Math.max(0, Math.min(l, pw - w));
        int top = Math.max(0, Math.min(t, ph - h));
        android.graphics.Rect out = new android.graphics.Rect(left, top, left + w, top + h);
        if (out.left != l || out.top != t || out.right != r || out.bottom != b) {
            logger.info("cluster cast: resize rect clamped to panel " + pw + "x" + ph + ": ["
                    + l + "," + t + "," + r + "," + b + "] → " + out.toShortString());
        }
        return out;
    }

    /**
     * Cluster panel size, CACHED for the lifetime of a cast.
     *
     * <p>{@code BsNativeLayer.clusterDisplaySize} can fall back to forking {@code dumpsys display},
     * which costs hundreds of ms — unusable on the live-drag path, where this runs on an HTTP worker
     * for every committed frame. The panel cannot change while a cast is up (it is fixed by the
     * projection's size profile), so resolve it once per cast: {@link #start} clears the cache, and a
     * failed resolve is not cached so a later attempt can still succeed.
     *
     * @return {@code {width, height}}, or {@code {0, 0}} when unresolved.
     */
    private static int[] clusterPanelSizeCached() {
        int[] cached = cachedPanel;
        if (cached != null) return cached;
        int pw = 0, ph = 0;
        try {
            android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (ctx != null) {
                android.graphics.Point p =
                        com.overdrive.app.surveillance.BsNativeLayer.clusterDisplaySize(ctx);
                if (p != null) { pw = p.x; ph = p.y; }
            }
        } catch (Throwable ignored) {}
        int[] out = new int[] { pw, ph };
        if (pw > 1 && ph > 1) cachedPanel = out;   // only cache a real answer
        return out;
    }

    /** Resolved cluster panel size for the current cast (null = not yet resolved). */
    private static volatile int[] cachedPanel;

    /** Smallest window we will ask AMS for (cluster px) — mirrors the UI geometry's MIN_SIZE. */
    private static final int MIN_WINDOW_PX = 200;
    /** Upper sanity bound used only when the panel size is unresolvable. */
    private static final int MAX_PLAUSIBLE_PANEL_PX = 8192;

    /** Record the committed cast package for SIGKILL boot recovery. */
    private static void persistStrandedCastPkg(String pkg) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put(CAST_STRANDED_PKG, pkg != null ? pkg : "");
        try { com.overdrive.app.config.UnifiedConfigManager.updateValues("surveillance", m); }
        catch (Throwable ignored) {}
    }

    /** Drop the boot-recovery flag. Read-before-write so an already-clear flag costs no disk
     *  rewrite (mirrors ClusterProjectionController.clearGateFlagsStatic). */
    private static void clearStrandedCastPkg() {
        try {
            org.json.JSONObject s = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
            if (s == null || s.optString(CAST_STRANDED_PKG, "").isEmpty()) return;
        } catch (Throwable ignored) {}
        persistStrandedCastPkg("");
    }

    /**
     * Daemon boot recovery. If a cast was active when the daemon was SIGKILLed (so neither
     * {@link #stop()} nor a clean shutdown ran), the foreign app is stranded on the closed
     * cluster display with cluster affinity. Read the persisted package and reparent its task
     * back to display 0 in the background, then clear the flag. Mirrors
     * {@link ClusterProjectionController#clearStaleGateAtBoot()} (the companion gauge backstop
     * called right before this on respawn): static, best-effort, and runs on a throwaway thread
     * so the several-second {@code dumpsys}+{@code am} reparent never blocks daemon boot.
     * Idempotent — {@link AppLauncher#reparentToDisplay0} is a no-op when the task is gone or
     * already on display 0.
    */
    public static void reparentStrandedCastAtBoot() {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            // DI5 recovery owns the cross-mode fence. Once its compositor
            // ownership is proven clear it must also consume any stranded
            // legacy task/gauge marker before leaving RECOVERING.
            DiLink5ClusterCast.recoverAtBoot(true);
            return;
        }
        // Always probe durable DI5 ownership before admitting the currently
        // selected legacy stack. This survives a second daemon restart after
        // the mode marker has already changed away from DI5.
        DiLink5ClusterCast.recoverAtBoot(true);
    }

    /**
     * Completes the legacy stranded-task half of boot recovery while the
     * DI5 cross-mode admission fence is still held. Synchronous by design:
     * opening a new legacy projection before this task move finishes could
     * let the recovery move that new session back to display 0.
     */
    public static boolean
            reparentLegacyStrandedCastAtBootSynchronously() {
        final String pkg;
        final boolean autoCast;
        final String autoPkg;
        final boolean mapAutoProject;
        try {
            org.json.JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.forceReload();
            org.json.JSONObject s = cfg.optJSONObject("surveillance");
            pkg = s != null ? s.optString(CAST_STRANDED_PKG, "") : "";
            org.json.JSONObject proj = cfg.optJSONObject("projection");
            autoCast = proj != null && proj.optBoolean("autoStartOnAcc", false);
            autoPkg = proj != null ? proj.optString("autoStartPackage", "") : "";
            // Needed for the deferral decision below: AccMonitor's tiebreak makes the map win when
            // both cluster auto-starts are on, which means the auto-cast never runs.
            org.json.JSONObject navCfg = cfg.optJSONObject("navMap");
            mapAutoProject = navCfg != null && navCfg.optBoolean("autoProjectCluster", false);
        } catch (Throwable t) {
            return false;
        }
        if (pkg == null || pkg.isEmpty()) return true;

        // If the stranded package is the configured ACC-on auto-cast, DEFER ENTIRELY to that
        // auto-cast — do not reparent. Two reasons, both decisive:
        //   1. Correctness of target: the user configured this app to live ON THE CLUSTER, so
        //      the ACC-on auto-cast (AccMonitor → ClusterCast.start) re-placing it there IS the
        //      recovery. Reparenting it to display 0 would move it to the WRONG display.
        //   2. Race: the auto-cast's `am start --display <fission>` and a background
        //      `am stack move-task ... 0` would otherwise fight over the same task (the confirmed
        //      boot-sweep race). Not spawning the sweep thread removes that race at the source.
        // The auto-cast owns the recovery flag via its own start()/stop(); leave it set. A
        // NON-auto (or different) package is never re-placed on the cluster automatically, so the
        // sweep below is its only recovery and cannot collide with an auto-cast of a live app.
        // Defer ONLY if that auto-cast will actually run. AccMonitor enforces a tiebreak when BOTH
        // cluster auto-starts are enabled: the MAP wins and the auto-cast is skipped entirely. So
        // deferring in that case would strand this app on the cluster in freeform forever — the flag
        // is never cleared, so every subsequent boot would defer again. Read the map flag here and
        // fall through to the sweep when the map is going to win.
        if (autoCast && !mapAutoProject && pkg.equals(autoPkg)) {
            logger.info("cluster cast: boot reparent deferred to ACC-on auto-cast (" + pkg + ")");
            // The auto-cast re-places it; until then it must not stay docked on the cluster.
            try { ClusterFreeformWindow.undockPackageOffDefaultDisplay(pkg); }
            catch (Throwable ignored) {}
            return true;
        }
        if (autoCast && mapAutoProject && pkg.equals(autoPkg)) {
            logger.warn("cluster cast: stranded auto-cast package " + pkg + " but map auto-project "
                    + "also enabled (AccMonitor tiebreak: map wins, auto-cast will NOT run) — "
                    + "sweeping instead of deferring");
        }

        logger.warn("cluster cast: stranded cast package at boot (" + pkg
                + ") — reparenting to display 0");
        // Never reparent out from under a cast that (re)started on this boot.
        synchronized (ClusterCast.class) {
            if (castPkg != null) {
                logger.info("cluster cast: boot reparent skipped — a live "
                        + "cast owns the cluster");
                return true;
            }
        }
        boolean rehomed = false;
        try {
            rehomed = AppLauncher.reparentToDisplay0(pkg);
        } catch (Throwable t) {
            logger.warn("cluster cast: boot reparent failed: "
                    + t.getMessage());
        }
        if (rehomed) {
            synchronized (ClusterCast.class) {
                if (castPkg == null) {
                    clearStrandedCastPkg();
                }
            }
        }
        return rehomed;
    }

    /**
     * True while an app is (being) cast onto the cluster. Self-reconciling: if the OEM
     * projection was torn down out from under us by a DIRECT forceClose (cluster-size
     * relayout, blind-spot disable, cluster→head-unit retarget, ACC-off) — which clears
     * ALL sustained holders including our {@code "castapp"} token but does not call our
     * {@link #stop()} — the projection no longer holds our token, so the cast is over.
     * We detect that here (no keep-alive loop needed for a non-cooperative app) and
     * reconcile {@code castPkg} to null, releasing our (already-dropped) token defensively.
     * Once a launch thread has been started (castPkg set + acquired) the projection is
     * held for us; the only time it wouldn't be is exactly this torn-down case.
     */
    /** The package currently (being) cast onto the cluster, or null if none. Read by the
     *  package-removed watcher to tear down a live cast whose app was just uninstalled. */
    public static synchronized String getCastPackage() {
        return com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                ? DiLink5ClusterCast.getCastPackage() : castPkg;
    }

    /** Current cast identity for race-safe dependent cleanup, or 0 when no cast is active. */
    public static synchronized long currentSessionGeneration() {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            return DiLink5ClusterCast.currentSessionGeneration();
        }
        return castPkg != null ? castSessionGeneration : 0L;
    }

    public static synchronized boolean isActive() {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            return DiLink5ClusterCast.isActive();
        }
        String pkg = castPkg;
        if (pkg == null) return false;
        try {
            // While the launch thread may still be waiting for the display to materialise,
            // the "castapp" hold was already acquired in start() — so a missing hold means
            // a forceClose cleared it (teardown), not a not-yet-acquired state.
            if (!ClusterProjectionController.holdsTokenStatic(TOKEN)) {
                // Torn down by a DIRECT forceClose (relayout / bs-disable / retarget / ACC-off),
                // which never routes through stop(). Drop the freeform CACHE so a later cast can
                // never resize this now-dead task id. We deliberately do NOT attempt an AMS undo
                // here: the projection is already closing (or closed), this runs on a status-poll
                // thread, and issuing windowing work into that teardown window is the documented
                // SurfaceFlinger hazard the ACC-off path exists to avoid. The residue dies with the
                // projection close, and the boot sweep reparents anything left stranded.
                logger.info("cluster cast: projection dropped our token — reconciling " + pkg);
                castPkg = null;
                launchThread = null;
                try { ClusterFreeformWindow.invalidateTaskCache(); } catch (Throwable ignored) {}
                scheduleDockResidueCleanup(castDisplayId);
                return false;
            }
        } catch (Throwable ignored) { /* fail toward the raw flag */ }
        return true;
    }

    /**
     * Begin casting {@code pkg} onto the cluster. Validates the package, resolves its
     * launcher component (trusted PackageManager / {@code cmd package resolve-activity},
     * reused from {@link AppLauncher}), acquires the OEM projection, waits for the live
     * fission display, then launches + verifies. Returns true if the request was
     * accepted (component resolved + projection acquired) — the actual on-screen resume
     * is confirmed asynchronously on the launch thread. Returns false for an invalid
     * package, an unresolvable component, or a competing active cluster projection,
     * NOT for the async resume race.
     */
    public static synchronized boolean start(String pkg) {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            return DiLink5ClusterCast.start(pkg);
        }
        if (ClusterProjectionController
                .isLegacyProjectionAdmissionBlocked()) {
            logger.warn("cluster cast: legacy projection blocked by "
                    + "unresolved cluster recovery ownership");
            return false;
        }
        if (castStopInProgress) {
            logger.warn("cluster cast: start deferred while previous cast is stopping");
            return false;
        }
        if (pkg == null || pkg.trim().isEmpty() || !AppLauncher.isValidPackageName(pkg.trim())) {
            logger.warn("cluster cast: missing/invalid package");
            return false;
        }
        pkg = pkg.trim();
        final String component = AppLauncher.resolveLauncherComponent(pkg);
        if (component == null) {
            logger.warn("cluster cast: could not resolve launcher component for " + pkg);
            return false;
        }
        // A valid cluster app cast and the Android Auto cluster video are
        // exclusive consumers of the same BYD projection surface.
        if (com.overdrive.app.navmap.AndroidAutoClusterBridge.isActive()) {
            logger.warn("cluster cast: stop the Android Auto cluster bridge first");
            return false;
        }
        // REPLACING A LIVE CAST: undo the outgoing app's freeform state FIRST. Without this the old
        // app stays parked on the cluster in FREEFORM at its old box (stop() only ever acts on the
        // CURRENT castPkg, and the cache we need is about to be invalidated), it is dropped from
        // SIGKILL recovery when the flag below is overwritten, and its box would leak onto the new
        // app via freeformBounds. Best-effort + guarded; the display is still live at this point.
        final String outgoing = castPkg;
        if (outgoing != null && !outgoing.equals(pkg) && freeformResizeEnabled()) {
            try { ClusterFreeformWindow.restoreFullscreen(outgoing, castDisplayId); }
            catch (Throwable t) {
                logger.warn("cluster cast: replacing " + outgoing + " — freeform restore failed: "
                        + t.getMessage());
            }
        }
        // Forget the previous cast's box so the incoming app is never placed at another app's rect.
        // The UI re-sends this app's own geometry once it is cast (restoreWindowFor), and the ACC-on
        // auto-cast has no UI at all — so a leftover rect there would be silently wrong.
        if (outgoing == null || !outgoing.equals(pkg)) {
            synchronized (ClusterCast.class) { freeformBounds = null; }
        }
        castPkg = pkg;
        castSessionGeneration++;
        if (castSessionGeneration <= 0L) castSessionGeneration = 1L;
        // Drop any task-id / freeform state cached for a PREVIOUS cast. Task ids are recycled by
        // AMS, so a stale id could otherwise be resized — reshaping an unrelated app's window.
        // Also clear the previous cast's display so a resize racing this launch cannot scope to a
        // stale (now-closed) fission display; the launch thread republishes it once resolved.
        castDisplayId = -1;
        cachedPanel = null;   // re-resolve the panel for this cast's projection
        try { ClusterFreeformWindow.invalidateTaskCache(); } catch (Throwable ignored) {}
        logger.info("cluster cast: start " + pkg + " (" + component + ")");
        // Acquire our hold FIRST so the projection is pinned before we touch the map.
        try {
            if (!ClusterProjectionController.getInstance()
                    .acquireSustained(TOKEN)) {
                logger.warn("cluster cast: sustained projection was not admitted");
                castPkg = null;
                return false;
            }
        } catch (Throwable t) {
            logger.warn("cluster cast: acquireSustained failed: " + t.getMessage());
            // acquireSustained adds the token to the holder set as its FIRST step, then
            // does more work that could throw — so a failure may leave "castapp" stranded
            // (suppressing auto-close, gauges blanked) with castPkg about to be nulled
            // (stop() then early-returns on castPkg==null and can't release it). Release
            // defensively so a partial acquire can't pin the projection open forever.
            try { ClusterProjectionController.getInstance().releaseSustained(TOKEN); }
            catch (Throwable ignored) {}
            castPkg = null;
            return false;
        }
        // Now stop any active nav-map projection. ORDER MATTERS: our "castapp" hold is
        // already in place, so the map's releaseSustained("map") sees a remaining holder
        // and leaves the projection OPEN (no close→reopen churn, no new fission id, no
        // gauge flash). stop() also sets the map's active=false (its keep-alive watchdog
        // exits on the next tick, so it never relaunches the map over the cast) and
        // clears navMap.clusterMapActive (the map Activity self-finishes ~500ms), freeing
        // the foreground for the cast app.
        try {
            if (com.overdrive.app.navmap.ClusterMapProjector.isActive()) {
                logger.info("cluster cast: stopping active map projection to take the cluster");
                com.overdrive.app.navmap.ClusterMapProjector.stop();
            }
        } catch (Throwable t) {
            logger.warn("cluster cast: map stop failed (continuing): " + t.getMessage());
        }
        // Persist for SIGKILL boot recovery now that the projection is committed: from here on a
        // hard daemon kill would strand this app on the cluster with no clean stop. Written
        // BEFORE the launch thread starts (and before we hand the foreground to the cast) so
        // there is no window where the app is being placed on the cluster without the flag set.
        // A cast stopped without a re-home (ACC-off, failed re-home) is still on the cluster and
        // only its marker names it, so read that before it is overwritten.
        String stranded = "";
        try {
            org.json.JSONObject sv = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
            if (sv != null) stranded = sv.optString(CAST_STRANDED_PKG, "");
        } catch (Throwable ignored) {}
        persistStrandedCastPkg(pkg);
        final String castComponent = component;
        final String replaced = outgoing != null && !outgoing.equals(pkg) ? outgoing
                : (!stranded.isEmpty() && !stranded.equals(pkg) ? stranded : null);
        launchThread = new Thread(() -> waitAndLaunch(castComponent, replaced),
                "ClusterCastLaunch");
        launchThread.setDaemon(true);
        launchThread.start();
        return true;
    }

    /**
     * DI5-only verified start used by request/response APIs. The regular
     * {@link #start(String)} stays asynchronous for ACC auto-start and other
     * fire-and-forget callers. Legacy platforms keep their established behavior.
     */
    public static boolean startAndAwait(String pkg, long timeoutMs) {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            return DiLink5ClusterCast.startAndAwait(pkg, timeoutMs);
        }
        return start(pkg);
    }

    /**
     * Legacy user-initiated start: waits (bounded) for a previous stop's close or a gauge restore
     * that is still running, so Start right after Stop queues behind it instead of failing.
     */
    public static boolean startAfterSettle(String pkg, long timeoutMs) {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            return start(pkg);
        }
        final long startedAt = android.os.SystemClock.elapsedRealtime();
        final long deadline = startedAt + Math.max(0L, timeoutMs);
        long blockedIdleSince = -1L;
        boolean waited = false;
        while (true) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now >= deadline) break;
            if (legacyStartSettling()) {
                blockedIdleSince = -1L;
            } else if (!ClusterProjectionController.isLegacyProjectionAdmissionBlocked()) {
                break;
            } else if (blockedIdleSince < 0L) {
                blockedIdleSince = now;
            } else if (now - blockedIdleSince >= SETTLE_GRACE_MS) {
                break;   // blocked with nothing in flight: a persistent lockout, not a close
            }
            if (!waited) {
                waited = true;
                logger.info("cluster cast: waiting for the previous stop/restore to settle");
            }
            try {
                Thread.sleep(SETTLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;   // the caller cancelled; never start into a close that is still running
            }
        }
        if (waited) {
            logger.info("cluster cast: start after "
                    + (android.os.SystemClock.elapsedRealtime() - startedAt) + "ms settle");
        }
        if (Thread.currentThread().isInterrupted()) return false;
        return start(pkg);
    }

    private static boolean legacyStartSettling() {
        synchronized (ClusterCast.class) {
            if (castStopInProgress) return true;
        }
        return ClusterProjectionController.isLegacyRestoreInFlight();
    }

    public static String getStartPhase() {
        return com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                ? DiLink5ClusterCast.getPhase() : (isActive() ? "active" : "idle");
    }

    public static String getLastStartFailure() {
        return com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                ? DiLink5ClusterCast.getLastFailureReason() : "";
    }

    /**
     * Stop casting from a USER-INITIATED, within-session path (the Projection screen's Stop
     * button, {@code cluster_cast_stop}). Releases the sustained hold so the controller
     * restores the gauges (when no other consumer — map / blind-spot — still wants the
     * projection) AND clears the cast task's stale cluster-display affinity in the background
     * so the app reopens on the infotainment (see {@link AppLauncher#reparentToDisplay0}).
     * Idempotent.
     */
    public static boolean stop() { return stop(CLEAR_AFFINITY, 0L); }

    // stop() rehome modes.
    private static final int NO_REHOME     = 0;  // ACC-off / forceClose: touch nothing extra
    private static final int CLEAR_AFFINITY = 1; // background reparent to display 0 (no focus steal)
    private static final int FOREGROUND    = 2;  // explicit "move back to head unit" (brings to front)

    /**
     * Stop casting the ACC-OFF / teardown way: release the hold only, do NO shell/AMS work.
     * The ACC-off sequence has a load-bearing SurfaceFlinger teardown ordering (mirror VD
     * unbound before the fission source closes — see {@link com.overdrive.app.monitor.AccMonitor});
     * injecting an {@code am stack move-task} into that window risks the native crash cascade.
     * The stale cluster affinity is harmless until the next ACC-on reopen, and by then the VD
     * is typically truly removed (AMS auto-reparents) — and a user-initiated
     * {@link #stop()} on the Projection screen clears it explicitly anyway.
     */
    public static boolean stopForAccOff() { return stop(NO_REHOME, 0L); }

    /**
     * Stop only the cast identified by {@code expectedGeneration}. Used when a Projection UI
     * process dies: the delayed Binder death callback must not terminate a cast started by a
     * replacement process in the meantime.
     */
    public static boolean stopIfSession(long expectedGeneration) {
        return expectedGeneration <= 0L
                || stop(CLEAR_AFFINITY, expectedGeneration);
    }

    /** @param rehomeToHeadUnit true → the explicit "move app back to head unit" foreground
     *   reparent; false → the ACC-off-safe no-rehome path. Retained for the existing
     *   {@code move_display} caller; new call sites should use {@link #stop()} (background
     *   affinity clear) or {@link #stopForAccOff()}. */
    public static boolean stop(boolean rehomeToHeadUnit) {
        return stop(rehomeToHeadUnit ? FOREGROUND : NO_REHOME, 0L);
    }

    private static boolean stop(int rehomeMode, long expectedGeneration) {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            if (expectedGeneration > 0L) {
                return DiLink5ClusterCast.stopIfSession(expectedGeneration);
            } else if (rehomeMode == NO_REHOME) {
                return DiLink5ClusterCast.stopForAccOff();
            } else {
                return DiLink5ClusterCast.stop(rehomeMode == FOREGROUND);
            }
        }
        // Capture + clear state UNDER the lock, but do the slow shell reparent OUTSIDE it:
        // reparentToDisplay0 / launchOnDisplay spawn dumpsys + am (up to several seconds), and
        // holding the ClusterCast monitor that long would block isActive()/getCastPackage()/
        // a concurrent start() — including the CastPackageWatcher teardown path.
        final String pkg;
        final long stoppingGeneration;
        synchronized (ClusterCast.class) {
            if (castPkg == null
                    || (expectedGeneration > 0L
                    && castSessionGeneration != expectedGeneration)) {
                return true;
            }
            pkg = castPkg;
            logger.info("cluster cast: stop " + pkg + " (rehomeMode=" + rehomeMode + ")");
            castPkg = null;
            launchThread = null;   // supersede any in-flight verify loop
            freeformBounds = null; // forget the box; next cast starts full-panel unless re-set
            stoppingGeneration = castSessionGeneration;
            castStopGeneration = stoppingGeneration;
            castStopInProgress = true;
        }
        // Freeform hygiene (feature-gated): if we converted the app to freeform, restore it to
        // FULLSCREEN + clear its freeform bounds BEFORE the reparent, while the task is still on
        // the live cluster display. This leaves the display in a pristine state so the nav-map /
        // blind-spot projection that follows a stop is never corrupted by leftover freeform
        // task state (the exact failure mode that sank the earlier freeform attempt). Skipped on
        // NO_REHOME (ACC-off): that path must do NO AMS work in the SurfaceFlinger teardown
        // window, and the app is intentionally left in place — its residue dies with the
        // projection close + the next boot's stranded-cast reparent. Best-effort; guarded.
        if (rehomeMode != NO_REHOME && freeformResizeEnabled()) {
            // Waits out an in-flight placement; any later one sees castStopInProgress and aborts.
            synchronized (PLACEMENT_LOCK) {
                try { ClusterFreeformWindow.restoreFullscreen(pkg, castDisplayId); }
                catch (Throwable t) { logger.warn("cluster cast: freeform restore failed: " + t.getMessage()); }
            }
        } else {
            // NO_REHOME (ACC-off) skips the restore above — no AMS work is allowed inside that
            // SurfaceFlinger teardown window. Still drop the cached task id: it must never survive
            // into the next cast, where AMS may have recycled it onto a different app's task.
            // Cache-only, no binder/AMS call, so it is safe on the teardown path.
            try { ClusterFreeformWindow.invalidateTaskCache(); } catch (Throwable ignored) {}
            scheduleDockResidueCleanup(castDisplayId);
        }
        // Reparent BEFORE releaseSustained so the fission display is still live — reparenting
        // between two LIVE displays is the well-supported AMS case; doing it after the source
        // is torn down would orphan the task. `rehomed` = the app is confirmed off the cluster:
        // either we moved it, or there was no live task to move (nothing stranded). It is FALSE
        // only when the move was attempted but couldn't complete (e.g. no display-0 stack
        // parsed) — in which case the app may still be stranded, so we keep the recovery flag.
        boolean rehomed = true;
        if (rehomeMode == FOREGROUND) {
            // Explicit user "move back to head unit" — bring it to the front on display 0.
            try { rehomed = AppLauncher.launchOnDisplay(pkg, 0); }
            catch (Throwable t) { rehomed = false; logger.warn("cluster cast: re-home to display 0 failed: " + t.getMessage()); }
        } else if (rehomeMode == CLEAR_AFFINITY) {
            // User stopped the cast (not driving-away ACC-off) — silently pull the task's
            // affinity back to display 0 so it reopens on the infotainment, WITHOUT bringing
            // it to the foreground (toTop=false). A dispatched move means the app isn't stranded;
            // a failure leaves it possibly on the cluster, so we keep the recovery flag.
            try { rehomed = AppLauncher.reparentToDisplay0(pkg); }
            catch (Throwable t) { rehomed = false; logger.warn("cluster cast: clear affinity failed: " + t.getMessage()); }
        }
        // Drop the boot-recovery flag only once the app is confirmed off the cluster. NO_REHOME
        // (ACC-off) intentionally leaves the app on the cluster to avoid the SurfaceFlinger
        // teardown race, so its flag STAYS set as the SIGKILL backstop. If a FOREGROUND/
        // CLEAR_AFFINITY rehome couldn't complete, we ALSO keep the flag so the next boot's
        // reparentStrandedCastAtBoot retries — a no-op if AMS has since moved the task.
        if ((rehomeMode == FOREGROUND || rehomeMode == CLEAR_AFFINITY) && rehomed) {
            clearStrandedCastPkg();
        }
        boolean projectionReleased = true;
        try {
            // After a USER-INITIATED stop (not ACC-off), if the user has the RoadSense map set to
            // auto-project the cluster, restore it so the cluster returns to the map instead of
            // sitting on restored gauges. Runs BEFORE releaseSustained so the map takes over the
            // still-open projection (a close in progress refuses new holders), and BEFORE
            // castStopInProgress is cleared below, so a cluster-cast POST that races in
            // is still deferred by start()'s castStopInProgress guard and cannot fight the
            // map-restart for the cluster foreground. ClusterMapProjector.start is itself
            // non-blocking + idempotent. NO_REHOME (ACC-off) intentionally skips
            // this — the car is powering down and the ACC-off teardown ordering must not be disturbed.
            if (rehomeMode != NO_REHOME) {
                try {
                    org.json.JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.forceReload();
                    org.json.JSONObject nav = cfg != null ? cfg.optJSONObject("navMap") : null;
                    if (nav != null && nav.optBoolean("autoProjectCluster", false)) {
                        logger.info("cluster cast: restoring auto-project map after cast stop");
                        com.overdrive.app.navmap.ClusterMapProjector.start();
                    }
                } catch (Throwable t) {
                    logger.warn("cluster cast: map restore after stop failed: " + t.getMessage());
                }
            }
            // The head-unit preview's own hold must not outlive the cast it mirrors.
            if (rehomeMode != NO_REHOME) {
                try {
                    com.overdrive.app.surveillance.ClusterViewMirrorService
                            .forceDetachIfActive("cast-stop");
                } catch (Throwable ignored) {}
            }
            try {
                ClusterProjectionController.getInstance().releaseSustainedUnlessSignalled(TOKEN);
            } catch (Throwable t) {
                projectionReleased = false;
                logger.warn("cluster cast: releaseSustained failed: " + t.getMessage());
            }
        } finally {
            synchronized (ClusterCast.class) {
                if (castStopInProgress && castStopGeneration == stoppingGeneration) {
                    castStopInProgress = false;
                }
            }
        }
        return rehomed && projectionReleased;
    }

    /**
     * True while this launch thread should keep working: we're still the current cast,
     * not superseded, AND the projection still holds our "castapp" token. The token
     * check is the key gate (mirrors ClusterMapProjector.isSustainedProjectionHeld):
     * a DIRECT forceClose (cluster-size relayout, blind-spot disable, cluster→head-unit
     * retarget) clears ALL sustained holders + restores the gauges WITHOUT routing
     * through stop(), leaving castPkg stale-true. Without this gate the launch/verify
     * loop would re-issue `am start` and repaint the cast app over the just-restored
     * gauges (the fission VirtualDisplay outlives the OEM 18→0 close). When we detect the
     * token is gone, reconcile our own flags so isActive() and a later start() are correct.
     */
    private static boolean stillOurs(Thread self) {
        if (castPkg == null || launchThread != self) return false;
        boolean held;
        try {
            held = ClusterProjectionController.holdsTokenStatic(TOKEN);
        } catch (Throwable t) {
            held = true;   // fail toward continuing (never worse than the pre-gate behaviour)
        }
        if (!held) {
            // Torn down by a direct forceClose. Reconcile under the monitor, identity-guarded
            // so a concurrent start() that already replaced launchThread keeps its state.
            synchronized (ClusterCast.class) {
                if (launchThread == self) {
                    logger.info("cluster cast: projection no longer holds our token "
                            + "(closed via a direct forceClose — relayout / bs-disable / retarget); "
                            + "aborting launch without repaint");
                    castPkg = null;
                    launchThread = null;
                    // Same reasoning as isActive(): cache-only cleanup, no AMS work in a teardown
                    // window. Prevents a recycled task id from being resized by a later cast.
                    try { ClusterFreeformWindow.invalidateTaskCache(); } catch (Throwable ignored) {}
                    scheduleDockResidueCleanup(castDisplayId);
                }
            }
            return false;
        }
        return true;
    }

    private static void waitAndLaunch(String component, String replaced) {
        final Thread self = Thread.currentThread();
        int waited = 0;
        int displayId = -1;
        // Wait for a POSITIVE fission displayId (never 0 = built-in head unit). The
        // VirtualDisplay materialises 1-3s after the open opcodes, so an early resolve
        // transiently returns -1.
        while (stillOurs(self) && waited < READY_TIMEOUT_MS) {
            int id = resolveFissionDisplayId();
            if (id > 0) { displayId = id; break; }
            try { Thread.sleep(READY_POLL_MS); } catch (InterruptedException e) { return; }
            waited += READY_POLL_MS;
        }
        if (!stillOurs(self)) return;   // stopped / superseded / projection torn down
        // Publish the resolved cluster display so resize() can scope its task lookup to it. Set
        // BEFORE the launch so a box-commit racing the launch already scopes correctly.
        synchronized (ClusterCast.class) {
            if (launchThread != self) return;
            if (displayId > 0) castDisplayId = displayId;
        }
        if (displayId <= 0) {
            // No fission display materialised — non-fission trim, or display 1 is the
            // head unit here. Do NOT blind-fall-back to 1 (would clobber infotainment).
            // Abort + release so the controller restores the gauges.
            logger.warn("cluster cast: fission display not resolved (>0) in " + READY_TIMEOUT_MS
                    + "ms — aborting (no clobber of display 0)");
            stop();
            return;
        }
        // The replaced app's task would otherwise stay on the cluster behind the new one, where a
        // docked placement would show it in the space beside the new app.
        // Under PLACEMENT_LOCK so an in-flight placement of the replaced app finishes first.
        synchronized (PLACEMENT_LOCK) {
            if (!stillOurs(self)) return;
            if (replaced != null && !OWN_PACKAGE.equals(replaced)) {
                try {
                    boolean moved = ClusterFreeformWindow.moveDisplayTaskToDefaultDisplay(
                            replaced, displayId) || AppLauncher.reparentToDisplay0(replaced);
                    logger.info("cluster cast: re-homing replaced " + replaced
                            + " off the cluster: " + moved);
                } catch (Throwable t) {
                    logger.warn("cluster cast: re-homing replaced " + replaced + " failed: "
                            + t.getMessage());
                }
            }
            // A docked cast whose teardown skipped AMS work (ACC-off) would resolve this launch
            // into split-screen-secondary.
            ClusterFreeformWindow.clearDisplayDock(displayId);
        }
        launchOnDisplay(component, displayId, self);
    }

    /** {@code am start --display N} the app, then verify it RESUMED on that display,
     *  retrying if it lost the resume race (same rationale as ClusterMapProjector). */
    private static void launchOnDisplay(String component, int displayId, Thread self) {
        for (int attempt = 1; stillOurs(self) && attempt <= LAUNCH_VERIFY_ATTEMPTS; attempt++) {
            if (!issueLaunch(component, displayId, attempt, self)) {
                if (!sleepWhileActive(LAUNCH_VERIFY_POLL_MS, self)) return;
                continue;
            }
            for (int poll = 0; stillOurs(self) && poll < LAUNCH_VERIFY_POLLS_PER_ATTEMPT; poll++) {
                if (!sleepWhileActive(LAUNCH_VERIFY_POLL_MS, self)) return;
                if (isResumedOnDisplay(component, displayId)) {
                    logger.info("cluster cast: RESUMED on displayId " + displayId
                            + " (attempt " + attempt + ")");
                    // Freeform conversion (feature-gated): the app is now RESUMED fullscreen on
                    // the cluster — the reliable moment to convert it to freeform at the user's
                    // box bounds (never bare `am start --windowingMode 5`). No-op when the flag
                    // is off or no bounds were set (full panel = leave it fullscreen). Best-effort;
                    // a failure just leaves it fullscreen (the pre-existing behaviour).
                    maybeApplyFreeform(component, self);
                    return;   // no keep-alive for a foreign app (see class doc)
                }
            }
            logger.warn("cluster cast: NOT resumed on displayId " + displayId
                    + " after attempt " + attempt + " — re-launching");
        }
        if (stillOurs(self)) {
            logger.warn("cluster cast: failed to confirm resume on displayId " + displayId
                    + " after " + LAUNCH_VERIFY_ATTEMPTS + " attempts (projection stays up; "
                    + "gauges restore on the normal stop / ACC-off path)");
        }
    }

    /** After the app resumes fullscreen on the cluster, convert it to freeform at the
     *  remembered box bounds (feature-gated). No bounds set → leave it fullscreen (full-panel
     *  default). {@code component} is "pkg/activity"; we resize by package. Guarded.
     *
     *  <p>This is the app's FIRST placement, so it runs as a COMMIT (full escalation ladder +
     *  verification): the task has just resumed fullscreen and its parent stack is still in
     *  fullscreen mode — exactly the state the stack-level rungs exist to break out of. */
    private static void maybeApplyFreeform(String component, Thread self) {
        if (!freeformResizeEnabled()) return;
        String pkg = component;
        int slash = component.indexOf('/');
        if (slash > 0) pkg = component.substring(0, slash);
        android.graphics.Rect bounds = freeformBoundsCopy();
        if (bounds == null) {
            // No LIVE Projection screen set a box for this cast — the ACC-on auto-cast, a key
            // mapping and an automation all reach here with nothing staged. Fall back to the
            // app's own PERSISTED box so the user's chosen scale applies on the very first
            // launch, instead of only after they open Projection and the preview re-sends it.
            android.graphics.Rect saved = loadPersistedBounds(pkg);
            if (saved != null) {
                // Stage it so a later stop()/replace restores from the same rect the UI would,
                // and a Projection screen opened mid-cast starts from what is actually on screen.
                synchronized (ClusterCast.class) {
                    if (!pkg.equals(castPkg) || castStopInProgress) {
                        return;   // cast ended or was replaced while we read the config
                    }
                    // Re-check under the lock: a Projection screen can have committed a LIVE drag
                    // in the gap since the null test above, and that rect is fresher than this one.
                    // Without this the staging would clobber it and we'd apply the older box.
                    if (freeformBounds != null) {
                        saved = new android.graphics.Rect(freeformBounds);
                    } else {
                        freeformBounds = new android.graphics.Rect(saved);
                        logger.info("cluster cast: applying saved box for " + pkg + " "
                                + saved.toShortString());
                    }
                }
                bounds = saved;
            }
        }
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return;
        final long generation;
        synchronized (ClusterCast.class) {
            if (!pkg.equals(castPkg) || castStopInProgress) return;
            generation = castSessionGeneration;
        }
        // A just-resumed task can still be settling (a stopped map finishing, a nav app opening
        // its second activity), so a refusal worth retrying gets a few more tries.
        for (int attempt = 1; attempt <= INITIAL_PLACEMENT_ATTEMPTS; attempt++) {
            ResizeResult result = placeLegacy(pkg, bounds, true, generation, true);
            if (result.applied) {
                logger.info("cluster cast: placed " + pkg + " " + result.mode + " at "
                        + (result.bounds != null ? result.bounds.toShortString()
                                : bounds.toShortString()) + " (attempt " + attempt + ")");
                return;
            }
            if (!(result.retryable || result.blocked) || attempt == INITIAL_PLACEMENT_ATTEMPTS) {
                logger.warn("cluster cast: initial placement of " + pkg + " at "
                        + bounds.toShortString() + " not applied: " + result.reason);
                return;
            }
            if (!sleepWhileActive(INITIAL_PLACEMENT_RETRY_MS, self)) return;
        }
    }

    /** Issue one {@code am start --display N}. Returns true iff it exited 0. */
    private static boolean issueLaunch(String component, int displayId, int attempt, Thread self) {
        try {
            // Final liveness re-check IMMEDIATELY before exec: shrinks the check-then-act
            // window between the loop guard and the launch to microseconds, so a forceClose
            // (relayout / bs-disable / retarget) that landed in between aborts here instead
            // of firing one stray `am start` over the just-restored gauges.
            if (!stillOurs(self)) return false;
            String[] cmd = {
                "am", "start",
                "--user", "0",
                "--display", String.valueOf(displayId),
                "--windowingMode", WINDOWING_MODE_FULLSCREEN,
                "-f", LAUNCH_FLAGS,
                "-n", component
            };
            logger.info("cluster cast: launching " + component + " onto displayId " + displayId
                    + " (attempt " + attempt + ")");
            Process proc = Runtime.getRuntime().exec(cmd);
            boolean done = proc.waitFor(5, TimeUnit.SECONDS);
            if (!done) { proc.destroy(); logger.warn("cluster cast: am start did not exit in 5s"); return false; }
            if (proc.exitValue() != 0) { logger.warn("cluster cast: am start exited " + proc.exitValue()); return false; }
            return true;
        } catch (Throwable t) {
            logger.warn("cluster cast: am start failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Resolve the current fission cluster displayId from {@code dumpsys display} (the
     * id whose display name contains "fission", or -1). Read live — SurfaceFlinger
     * assigns it per open and the daemon's DisplayManager cache doesn't see the foreign
     * uid-1000 display. Mirrors ClusterMapProjector.resolveFissionDisplayId.
     */
    private static int resolveFissionDisplayId() {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "display").redirectErrorStream(true).start();
            final Process fp = p;
            // Read on a daemon thread so a wedged dumpsys (never reaching EOF) can't block
            // the launch thread forever; we cap the whole read at 3s and destroy on timeout.
            final int[] result = { -1 };
            final Thread reader = new Thread(() -> {
                try {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(fp.getInputStream()));
                    String line;
                    int found = -1;
                    while ((line = r.readLine()) != null) {
                        if (!line.toLowerCase(Locale.US).contains("fission")) continue;
                        int id = extractDisplayIdOnLine(line);
                        if (id >= 0) { found = id; if (id > 0) break; }
                    }
                    result[0] = found;
                } catch (Throwable ignored) { /* result stays -1 */ }
            }, "ClusterCastDisplayScan");
            reader.setDaemon(true);
            reader.start();
            reader.join(3000);
            return result[0];
        } catch (Throwable t) {
            logger.warn("cluster cast: resolveFissionDisplayId failed: " + t.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
        return -1;
    }

    /** Pull the integer after "displayid" (followed by ' ' or '=') on a single line, or -1. */
    private static int extractDisplayIdOnLine(String line) {
        String low = line.toLowerCase(Locale.US);
        int idx = low.indexOf("displayid");
        while (idx >= 0) {
            int i = idx + "displayid".length();
            while (i < low.length() && (low.charAt(i) == '=' || low.charAt(i) == ' ')) i++;
            int start = i;
            while (i < low.length() && Character.isDigit(low.charAt(i))) i++;
            if (i > start) {
                try { return Integer.parseInt(low.substring(start, i)); } catch (Throwable ignored) {}
            }
            idx = low.indexOf("displayid", idx + 1);
        }
        return -1;
    }

    /**
     * Package owning the RESUMED activity on {@code displayId}, an empty string when the
     * display block explicitly reports no resumed activity, or {@code null} when the state
     * could not be read unambiguously.
     *
     * <p>This is stronger than task placement. On DI5 the OEM can leave our task on the correct
     * shared display while re-fronting its own translucent MeterActivity above it; the task still
     * reports visible and a location-only guardian produces a false success. The per-display
     * resumed activity is the signal that proves who actually owns the panel foreground.
     */
    static String resumedPackageOnDisplay(int displayId) {
        if (displayId < 0) return null;
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "activity", "activities")
                    .redirectErrorStream(true).start();
            final Process running = p;
            final java.util.concurrent.atomic.AtomicReference<String> result =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader buffered = new java.io.BufferedReader(
                        new java.io.InputStreamReader(running.getInputStream()))) {
                    result.set(resumedPackageInActivityReader(buffered, displayId));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "ClusterResumedActivityScan");
            reader.setDaemon(true);
            reader.start();
            try {
                reader.join(RESUMED_STATE_TIMEOUT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (reader.isAlive()) {
                try { running.destroyForcibly(); } catch (Throwable ignored) {}
                try { reader.join(250L); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                logger.debug("cluster cast: resumed-package read timed out");
                return null;
            }
            Throwable readFailure = failure.get();
            if (readFailure != null) {
                logger.debug("cluster cast: resumed-package read failed: "
                        + readFailure.getMessage());
                return null;
            }
            return result.get();
        } catch (Throwable t) {
            logger.debug("cluster cast: resumed-package read failed: " + t.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Pure parser kept package-visible for replay tests against real dumpsys formats. */
    static String resumedPackageInActivityDump(String dump, int displayId) {
        if (dump == null || displayId < 0) return null;
        try {
            return resumedPackageInActivityReader(
                    new java.io.BufferedReader(new java.io.StringReader(dump)),
                    displayId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String resumedPackageInActivityReader(
            java.io.BufferedReader reader, int displayId) throws java.io.IOException {
        String line;
        boolean inTargetDisplay = false;
        boolean targetDisplaySeen = false;
        boolean resumedMarkerSeen = false;
        String displayHeader = "display #" + displayId;
        while ((line = reader.readLine()) != null) {
            String low = line.toLowerCase(Locale.US);
            int hdr = low.indexOf("display #");
            if (hdr >= 0) {
                boolean target = low.startsWith(displayHeader, hdr)
                        && !Character.isDigit(charAt(low, hdr + displayHeader.length()));
                if (inTargetDisplay && !target) break;
                inTargetDisplay = target;
                targetDisplaySeen |= target;
                continue;
            }
            if (!inTargetDisplay || !low.contains("resumedactivity")) continue;
            resumedMarkerSeen = true;
            Matcher component = RESUMED_COMPONENT_PATTERN.matcher(line);
            if (component.find()) return component.group(1);
            if (low.contains("null") || low.contains("none")) return "";
        }
        return targetDisplaySeen && resumedMarkerSeen ? "" : null;
    }

    /**
     * True iff {@code component}'s package owns the RESUMED activity on {@code displayId}.
     *
     * <p>Keep this legacy verifier byte-for-behaviour compatible outside DI5. The stricter
     * foreground-owner parser above is called directly only by {@link DiLink5ClusterCast}; changing
     * this shared method would silently alter DiLink 3/4 launch behavior.
     */
    static boolean isResumedOnDisplay(String component, int displayId) {
        if (component == null) return false;
        String pkg = component;
        int slash = component.indexOf('/');
        if (slash > 0) pkg = component.substring(0, slash);
        String needle = pkg.toLowerCase(Locale.US);
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "activity", "activities")
                    .redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            boolean inTargetDisplay = false;
            String displayHeader = "display #" + displayId;
            while ((line = r.readLine()) != null) {
                String low = line.toLowerCase(Locale.US);
                int hdr = low.indexOf("display #");
                if (hdr >= 0) {
                    inTargetDisplay = low.startsWith(displayHeader, hdr)
                            && !Character.isDigit(charAt(low, hdr + displayHeader.length()));
                    continue;
                }
                if (inTargetDisplay && low.contains("resumedactivity")
                        && low.contains(needle)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            logger.debug("cluster cast: isResumedOnDisplay failed: " + t.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
        return false;
    }

    private static char charAt(String s, int i) {
        return (i >= 0 && i < s.length()) ? s.charAt(i) : ' ';
    }

    /** Sleep {@code ms}, returning false if the cast was stopped / superseded / torn down
     *  (via {@link #stillOurs}) meanwhile — so the caller aborts promptly and never
     *  re-issues an {@code am start} after teardown. */
    private static boolean sleepWhileActive(int ms, Thread self) {
        if (!stillOurs(self)) return false;
        try { Thread.sleep(ms); } catch (InterruptedException e) { return false; }
        return stillOurs(self);
    }
}
