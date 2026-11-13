package com.overdrive.app.navmap;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.ClusterProjectionController;

/**
 * Projects the RoadSense map Activity onto the BYD driver-cluster (fission)
 * display, as a SUSTAINED holder of {@link ClusterProjectionController}.
 *
 * <p>Flow (daemon process, UID 2000):
 * <ol>
 *   <li>{@link #start()} acquires the projection as a sustained holder (opens
 *       the OEM cluster projection if not already up, and suppresses the linger
 *       / max-cap auto-close so the map stays up for the drive).</li>
 *   <li>Once the fission display has materialised, launch {@code RoadSenseMapActivity}
 *       onto it via {@code am start --display N} (the same uid-2000 launch path
 *       AvcHalWarmup already uses on-car — no self-ADB needed). The Activity is
 *       told via an intent extra that it's on the cluster, so it renders the
 *       non-touch cluster view.</li>
 *   <li>{@link #stop()} releases the sustained hold; the controller restores the
 *       gauges (18→0) when no transient consumer still wants the projection.</li>
 * </ol>
 *
 * <p>Blind-spot coexistence: BS composites its own SurfaceControl layer at z=MAX
 * onto the SAME projection (it doesn't own the lifecycle while the map holds it),
 * so a turn signal always paints on top of the map. See the BS pipeline.
 *
 * <p>Safety: this class never bypasses the controller's gauge-restore net. ACC-off
 * / disable / SIGTERM / SIGKILL recovery all still fire through the controller
 * regardless of this holder. Default OFF — only runs when the user opts in.
 */
public final class ClusterMapProjector {

    private static final String TAG = "ClusterMapProjector";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String APP_PKG = "com.overdrive.app";
    // Launch the CLUSTER ALIAS (own taskAffinity + singleInstance) so the cluster
    // map lives in a separate task from the interactive infotainment instance and
    // the two coexist on two displays. (Was the bare Activity, which relied on the
    // OEM-inconsistent MULTIPLE_TASK flag to avoid stealing the display-0 instance.)
    private static final String MAP_ACTIVITY = APP_PKG + "/.navmap.RoadSenseClusterMapActivity";

    // am start flag: NEW_TASK only (0x10000000). The alias's singleInstance +
    // distinct taskAffinity already isolate the task, so MULTIPLE_TASK (0x08000000)
    // is redundant and contradicts singleInstance.
    private static final String LAUNCH_FLAGS = "0x10000000";

    // windowingMode for the cluster launch. 1 = WINDOWING_MODE_FULLSCREEN — the
    // Activity fills the whole fission panel (1920x720). The earlier value 5
    // (WINDOWING_MODE_FREEFORM) was WRONG: it placed the map in a tiny floating
    // window (on-device: mLastNonFullscreenBounds ~= Rect(825,0-1095,720), a
    // ~270px-wide box centred on the panel) — the "small projection" symptom.
    // Verified on-car via `am start --display 1 --windowingMode 1`: the window
    // resolves to mBounds=[0,0][1920,720] / mWindowingMode=fullscreen, matching
    // the panel exactly. Fullscreen is also what blind-spot uses on this display.
    private static final String WINDOWING_MODE_FULLSCREEN = "1";

    // Poll budget for the fission display to appear after the projection opens.
    private static final int READY_POLL_MS = 250;
    private static final int READY_TIMEOUT_MS = 8000;

    // Post-launch verify+retry budget. `am start` exits 0 on ACCEPT, not on RESUMED-
    // on-display, so we confirm the Activity actually reached the foreground of the
    // cluster display and re-launch if it lost the resume race (see launchMapOnDisplay).
    // Total worst case ≈ ATTEMPTS × POLLS_PER_ATTEMPT × POLL_MS = 3 × 6 × 300 ≈ 5.4s,
    // comfortably inside the drive and never blocking the BS loop / GL thread (this all
    // runs on the ClusterMapLaunch thread). Each tick re-checks `active` so a stop()/
    // ACC-off aborts immediately.
    private static final int LAUNCH_VERIFY_ATTEMPTS = 3;
    private static final int LAUNCH_VERIFY_POLLS_PER_ATTEMPT = 6;
    private static final int LAUNCH_VERIFY_POLL_MS = 300;

    // ── Keep-alive watchdog (auto-recover from app swipe-away / process death) ───
    // After the initial launch+verify, the daemon — which SURVIVES the app being
    // swiped away (it's a separate uid-2000 process) — keeps watching that OUR cluster
    // map Activity is still the resumed window on the fission display. If the user
    // swipes Overdrive away from Recents (or the app process is recycled), the Activity
    // is destroyed but the daemon still holds the OEM projection OPEN → the cluster goes
    // BLACK and never recovers on its own: start() is a one-shot (no-op while `active`),
    // launchMapOnDisplay() returns after a single verify, and nothing else re-launches
    // the Activity. This watchdog re-issues the launch (which cold-starts the app
    // process if it died), so the map comes back automatically within a few seconds,
    // then keeps watching for the rest of the session. It runs ONLY while a map
    // projection is active (never for a pure blind-spot projection, which paints a
    // SurfaceControl layer with no Activity) and exits the instant the projection is
    // stopped (ACC-off / disable / web stop), so it can never fight a teardown.
    private static final int KEEPALIVE_POLL_MS = 2500;
    private static final int KEEPALIVE_MISS_THRESHOLD = 2;  // consecutive misses before relaunch

    // UCM coordination flag (navMap.clusterMapActive) the launched cluster Activity
    // polls to self-finish. The daemon (uid 2000) can't call finish() on a uid-1000
    // Activity, and the OEM projection close (18→0) deliberately never destroys the
    // fission VirtualDisplay (opcode 1 is forbidden — it poisons teardown), so the
    // Activity's onDisplayRemoved self-finish NEVER fires on a normal stop. Result:
    // a stopped map Activity stays parked on the still-alive fission display and
    // RE-SURFACES under the partial blind-spot card the next time a turn signal
    // re-opens the SAME projection — the "map shows on the cluster even though map
    // projection is disabled" bug. Fix mirrors the proven DeterrentActivity pattern:
    // start() sets the flag true, stop()/abort sets it false, and RoadSense(Cluster)
    // MapActivity polls UCM and finishAndRemoveTask() when it reads false. Lives in
    // the navMap section alongside autoProjectCluster.
    private static final String NAVMAP_SECTION = "navMap";
    private static final String K_CLUSTER_MAP_ACTIVE = "clusterMapActive";

    private static volatile boolean active = false;
    // Volatile + identity-guarded: the keep-alive watchdog runs for the whole session, so
    // a quick stop()→start() (e.g. an ACC off→on bounce) could flip `active` back to true
    // while an OLD watchdog is mid-sleep and leave TWO loops watching the same display.
    // start() replaces this reference with the new thread; each loop bails the moment it
    // is no longer the current launchThread, so only the latest session's watchdog lives.
    private static volatile Thread launchThread;

    private ClusterMapProjector() {}

    /** Publish the cluster-map-active coordination flag the launched Activity polls
     *  to self-finish (see {@link #K_CLUSTER_MAP_ACTIVE}). Best-effort; a missed
     *  write just leaves the Activity parked-but-invisible (same as the old
     *  behaviour) and never blanks gauges. Off the synchronized critical section. */
    private static void publishActiveFlag(boolean activeFlag) {
        try {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put(K_CLUSTER_MAP_ACTIVE, activeFlag);
            com.overdrive.app.config.UnifiedConfigManager.updateValues(NAVMAP_SECTION, m);
        } catch (Throwable t) {
            logger.warn("publishActiveFlag(" + activeFlag + ") failed: " + t.getMessage());
        }
    }

    /** True while the map is (being) projected onto the cluster. */
    public static boolean isActive() {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            return active && com.overdrive.app.launcher.DiLink5ClusterCast.isMapActive();
        }
        return active;
    }

    /**
     * Begin projecting the map onto the cluster. Idempotent. Acquires the
     * sustained projection hold, waits for the fission display, then launches the
     * map Activity onto it. Runs the wait+launch off the caller's thread.
     */
    public static synchronized void start() {
        // The Android Auto second-screen video and the RoadSense map share the
        // same physical cluster surface. Require an explicit stop before handoff.
        if (AndroidAutoClusterBridge.isActive()) {
            logger.warn("cluster map projection: stop the Android Auto cluster bridge first");
            return;
        }
        if (active) {
            if (!com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                    || com.overdrive.app.launcher.DiLink5ClusterCast.isMapActive()) {
                return;
            }
            active = false;
            publishActiveFlag(false);
        }
        active = true;
        logger.info("cluster map projection: start");
        // Mark active BEFORE launch so the Activity's first poll sees true and
        // doesn't immediately self-finish on a fast startup.
        publishActiveFlag(true);
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            if (!com.overdrive.app.launcher.DiLink5ClusterCast.startMap()) {
                active = false;
                publishActiveFlag(false);
                logger.warn("DI5 cluster map projection was not accepted");
            }
            return;
        }
        boolean acquired = false;
        try {
            acquired = ClusterProjectionController.getInstance()
                    .acquireSustained();
        } catch (Throwable t) {
            logger.warn("acquireSustained failed: " + t.getMessage());
            // acquireSustained adds the token before work that can throw; never leave it held.
            try { ClusterProjectionController.getInstance().releaseSustainedUnlessSignalled("map"); }
            catch (Throwable ignored) {}
        }
        if (!acquired) {
            active = false;
            publishActiveFlag(false);
            logger.warn("legacy cluster map projection was not admitted");
            return;
        }
        try {
            launchThread = new Thread(ClusterMapProjector::waitAndLaunch, "ClusterMapLaunch");
            launchThread.setDaemon(true);
            launchThread.start();
        } catch (Throwable t) {
            // The hold is already taken; drop it so the projection is not pinned without a map.
            launchThread = null;
            active = false;
            publishActiveFlag(false);
            logger.warn("cluster map launch thread failed: " + t.getMessage());
            try { ClusterProjectionController.getInstance().releaseSustainedUnlessSignalled("map"); }
            catch (Throwable ignored) {}
        }
    }

    /** Stop projecting: release the sustained hold (the controller restores gauges)
     *  AND signal the launched cluster Activity to finish itself. Without the
     *  finish signal the Activity stays parked on the still-alive fission display
     *  and re-surfaces under the blind-spot card on the next turn-signal projection
     *  open — the "map shows on the cluster even when disabled" bug. */
    public static synchronized boolean stop() {
        if (!active) return true;
        active = false;
        logger.info("cluster map projection: stop");
        // Tell the cluster Activity to finish (it polls navMap.clusterMapActive).
        // Done first so the Activity is dismissed even if releaseSustained throws.
        publishActiveFlag(false);
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) {
            return com.overdrive.app.launcher.DiLink5ClusterCast.stopMap();
        }
        try {
            ClusterProjectionController.getInstance().releaseSustained();
        } catch (Throwable t) {
            logger.warn("releaseSustained failed: " + t.getMessage());
            return false;
        }
        return true;
    }

    public static void onDiLink5ProjectionFailed() {
        if (!com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) return;
        synchronized (ClusterMapProjector.class) {
            if (!active
                    || com.overdrive.app.launcher.DiLink5ClusterCast.isMapActive()) {
                return;
            }
            active = false;
            launchThread = null;
        }
        publishActiveFlag(false);
    }

    /**
     * Authoritative legacy projection-close hook. Several safety paths close
     * the controller directly (relayout, retarget, blind-spot disable) instead
     * of routing through {@link #stop()}; invalidate the pending launch and its
     * keep-alive identity before the fission display is retired.
     */
    public static void onLegacyProjectionForceClosed() {
        if (com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()) return;
        boolean wasActive;
        synchronized (ClusterMapProjector.class) {
            wasActive = active || launchThread != null;
            active = false;
            launchThread = null;
        }
        if (wasActive) {
            publishActiveFlag(false);
        }
    }

    private static void waitAndLaunch() {
        final Thread self = Thread.currentThread();
        // Wait for the OEM projection to actually establish the fission display.
        int waited = 0;
        int displayId = -1;
        // Keep polling until a POSITIVE fission displayId appears. The fission
        // VirtualDisplay materialises ~1-3s AFTER the open opcodes (31→16→35), so
        // an early resolve transiently returns -1; and it's NEVER display 0 (that's
        // the built-in head unit). Requiring >0 means we wait for the real cluster
        // display instead of aborting on a transient/misparse 0.
        while (active && launchThread == self
                && waited < READY_TIMEOUT_MS) {
            int id = resolveFissionDisplayId();
            if (id > 0) { displayId = id; break; }
            try { Thread.sleep(READY_POLL_MS); } catch (InterruptedException e) { return; }
            waited += READY_POLL_MS;
        }
        if (!active || launchThread != self) return;
        if (displayId <= 0) {
            // No fission display ever materialised within the budget (non-fission /
            // Atto-class cluster, or the OEM projection never established). Do NOT
            // blind-fall back to displayId 1 — on such a model display 1 may be the
            // HEAD UNIT, so launching there would clobber the infotainment screen.
            // Abort + RELEASE the sustained hold so the controller restores gauges.
            logger.warn("fission display not resolved (>0) in " + READY_TIMEOUT_MS
                    + "ms — aborting cluster map projection (no clobber of display 0)");
            synchronized (ClusterMapProjector.class) {
                if (launchThread != self) return;
                active = false;
                launchThread = null;
            }
            publishActiveFlag(false);   // dismiss any Activity that did come up
            try { com.overdrive.app.surveillance.ClusterProjectionController.getInstance().releaseSustained(); }
            catch (Throwable ignored) {}
            return;
        }
        // Final race guard: a stop() may have fired during the resolve above. Don't
        // launch the Activity if the projection was torn down in the meantime.
        if (!active || launchThread != self
                || !isSustainedProjectionHeld()) {
            logger.info("stop() raced the display resolve — skipping cluster map launch");
            // stop() already cleared the flag; re-assert to cover a launch that
            // slipped onto the display just before this guard.
            publishActiveFlag(false);
            return;
        }
        launchMapOnDisplay(displayId, self);
    }

    /**
     * Resolve the current fission cluster displayId from {@code dumpsys display}.
     * SurfaceFlinger assigns the id per projection-open, so it must be read live
     * (the daemon's DisplayManager cache doesn't see the foreign uid-1000 display).
     * Returns the id of the display whose name contains "fission", or -1.
     */
    private static int resolveFissionDisplayId() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "display"});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            int found = -1;
            while ((line = r.readLine()) != null) {
                if (!line.toLowerCase(java.util.Locale.US).contains("fission")) continue;
                // The authoritative line is the LOGICAL display info, e.g.
                //   DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 1", ...}
                // The id appears ON THE SAME LINE as "fission" as either
                // `displayId 1` (logical info string) or `displayId=1`. The old
                // code paired "fission" with the LAST displayId from a PRIOR line
                // (the built-in viewport's displayId=0) → wrongly returned 0.
                // Extract the id from THIS fission line, supporting both forms.
                int id = extractDisplayIdOnLine(line);
                if (id >= 0) { found = id; if (id > 0) break; } // prefer a non-zero logical id
            }
            r.close();
            return found;
        } catch (Throwable t) {
            logger.warn("resolveFissionDisplayId failed: " + t.getMessage());
        }
        return -1;
    }

    /** Pull the integer after "displayid" (followed by ' ' or '=') on a single line, or -1. */
    private static int extractDisplayIdOnLine(String line) {
        String low = line.toLowerCase(java.util.Locale.US);
        int idx = low.indexOf("displayid");
        while (idx >= 0) {
            int i = idx + "displayid".length();
            // Skip a single '=' or whitespace separator.
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
     * {@code am start --display N} the map Activity onto the cluster, then VERIFY it
     * actually reached the foreground on that display — RETRYING if it didn't.
     *
     * <p>WHY (root cause of "map didn't project until a later restart"): {@code am
     * start} returns exit 0 as soon as AMS ACCEPTS the launch — NOT when the Activity
     * is RESUMED on the target display. On a contended ACC-on edge (observed on-car:
     * the daemon was simultaneously finalizing recordings + auto-disabling
     * surveillance at the exact ACC-on instant the map launched), the cluster map
     * Activity could lose the resume race to the head-unit launcher/home that owns
     * the fission display, ending up PAUSED/STOPPED behind it — the projection is
     * "OPEN + ready (sustained)" and {@code am start} exited 0, yet nothing renders.
     * A clean restart later won the race, which is why "it worked after 2-3 hrs".
     *
     * <p>Fix: after each launch, poll {@code dumpsys activity activities} to confirm
     * OUR component is the RESUMED activity on {@code displayId}; if not, re-issue the
     * launch (NEW_TASK + the cluster extra is idempotent — singleInstance just
     * re-resumes the existing task) up to {@link #LAUNCH_VERIFY_ATTEMPTS} times. Every
     * iteration re-checks {@link #active} so a stop()/ACC-off that raced in aborts the
     * retry immediately (never fights the gauge-restore). All on the ClusterMapLaunch
     * thread (off the 250ms BS loop / GL thread). On a genuine non-fission trim we
     * never get here (waitAndLaunch already aborted on displayId<=0).
     */
    private static void launchMapOnDisplay(
            int displayId, Thread ownerThread) {
        for (int attempt = 1;
                active
                        && launchThread == ownerThread
                        && attempt <= LAUNCH_VERIFY_ATTEMPTS;
                attempt++) {
            if (!issueLaunch(displayId, attempt)) {
                // am start itself failed (non-zero exit / exception). Brief backoff
                // then retry — a transient AMS hiccup during the ACC-on storm.
                if (!sleepWhileActive(LAUNCH_VERIFY_POLL_MS)
                        || launchThread != ownerThread) return;
                continue;
            }
            // Give AMS a moment to resume the Activity, then verify foreground-on-display.
            for (int poll = 0;
                    active
                            && launchThread == ownerThread
                            && poll < LAUNCH_VERIFY_POLLS_PER_ATTEMPT;
                    poll++) {
                if (!sleepWhileActive(LAUNCH_VERIFY_POLL_MS)
                        || launchThread != ownerThread) return;
                if (isMapResumedOnDisplay(displayId)) {
                    logger.info("cluster map RESUMED on displayId " + displayId
                            + " (attempt " + attempt + ")");
                    // Initial launch confirmed — hand off to the keep-alive watchdog so a
                    // later swipe-away / process death is auto-recovered for the rest of
                    // the session (the daemon outlives the app, so it must self-heal the map).
                    keepAliveLoop(displayId);
                    return;
                }
            }
            logger.warn("cluster map NOT resumed on displayId " + displayId
                    + " after attempt " + attempt + " (likely lost the resume race to the "
                    + "head-unit home) — re-launching");
        }
        if (active && launchThread == ownerThread
                && isSustainedProjectionHeld()) {
            logger.warn("cluster map failed to reach foreground on displayId " + displayId
                    + " after " + LAUNCH_VERIFY_ATTEMPTS + " attempts — entering keep-alive "
                    + "watchdog anyway (projection stays up; gauges restore on the normal "
                    + "stop/ACC-off path)");
            // Even if the initial verify never confirmed, keep watching: the resume race
            // may resolve in our favour shortly, and we still want swipe-away recovery.
            keepAliveLoop(displayId);
        }
    }

    /**
     * Keep-alive watchdog: while the map projection is active, periodically confirm OUR
     * cluster map Activity is still the resumed window on {@code displayId}, and re-launch
     * it if it has disappeared (the user swiped Overdrive away from Recents, or the app
     * process was recycled). The daemon (uid 2000) survives the app's task removal and
     * still holds the OEM fission projection OPEN, so without this the cluster goes BLACK
     * and never recovers until ACC-off. Re-issuing the launch cold-starts the app process
     * if needed; {@code singleInstance} makes it idempotent when the Activity is alive.
     *
     * <p>Runs on the same ClusterMapLaunch thread (off the 250ms BS loop / GL thread).
     * Every iteration re-checks {@link #active} so a {@code stop()} / ACC-off aborts the
     * loop immediately — it never re-launches into a teardown. A small consecutive-miss
     * threshold avoids relaunch-thrash on a single transient dumpsys parse miss (e.g. the
     * instant AMS is swapping the resumed activity). The relaunch reuses the verify+retry
     * path ({@link #launchMapOnDisplay}-style {@link #issueLaunch} + poll) inline.
     */
    private static void keepAliveLoop(int displayId) {
        logger.info("cluster map keep-alive watchdog: armed on displayId " + displayId);
        final Thread self = Thread.currentThread();
        int consecutiveMisses = 0;
        // Identity guard: a stop()→start() bounce starts a NEW launchThread; the old loop
        // must exit so two watchdogs never race on the same display.
        while (active && launchThread == self) {
            if (!sleepWhileActive(KEEPALIVE_POLL_MS)) return;
            if (launchThread != self) return;   // superseded by a newer start()
            // Re-resolve the fission displayId each tick: a projection close+reopen
            // (e.g. a transient blind-spot open interleaving) can reassign it, and a
            // stale id would make us watch the wrong display forever.
            int liveId = resolveFissionDisplayId();
            if (liveId > 0) displayId = liveId;
            if (isMapResumedOnDisplay(displayId)) {
                consecutiveMisses = 0;
                continue;
            }
            // Our Activity is NOT the resumed window. Could be a transient blink (AMS
            // mid-swap) or a real swipe-away. Require a couple of consecutive misses
            // before relaunching to avoid thrash.
            if (++consecutiveMisses < KEEPALIVE_MISS_THRESHOLD) continue;
            if (!active) return;
            // DECISIVE GATE: only relaunch if the daemon is STILL holding the OEM cluster
            // projection open FOR THE MAP. `active` is this class's own flag, but several
            // teardown paths close the projection by calling ClusterProjectionController
            // .forceClose(...) DIRECTLY without routing through stop() — blind-spot disable,
            // cluster-layout relayout, and the cluster→head-unit retarget. Those restore the
            // gauges and drop the sustained hold but leave `active` stale-true. ACC-off does
            // call stop() (clearing active) AND forceClose (clearing the hold), so it is
            // covered twice. Gating on isSustainedHeld() means: if the projection is no
            // longer held for the map, the gauges are already (being) restored — relaunching
            // the Activity would paint a ghost map back over them. Reconcile our own flag and
            // exit so the watchdog stops cleanly (mirrors stop()'s effect minus the redundant
            // releaseSustained, which already ran inside the forceClose that cleared the hold).
            if (!isSustainedProjectionHeld()) {
                logger.info("cluster map keep-alive: projection no longer held for the map "
                        + "(closed via a direct forceClose — bs-disable / relayout / retarget / "
                        + "ACC-off); stopping watchdog without relaunch");
                // Reconcile our own flag to reality so a later start() isn't blocked by a
                // stale active=true (start() no-ops when active). Identity-guarded + on the
                // SAME monitor start()/stop() synchronize on, so a concurrent start() that
                // already replaced launchThread keeps its active=true (we don't clobber it).
                synchronized (ClusterMapProjector.class) {
                    if (launchThread == self) {
                        active = false;
                        publishActiveFlag(false);
                    }
                }
                return;
            }
            // Atomic relaunch-commit: re-check ALL THREE liveness conditions INSIDE the class
            // monitor that start()/stop() hold, so the decision is serialised against a
            // concurrent stop()/ACC-off (which flips active + drops the hold under the same
            // lock). A stop() that wins the lock first makes commit=false → we skip the launch
            // entirely; once commit is captured true under the lock, stop() can still publish
            // clusterMapActive=false afterwards and the reconciliation gate below will honor it.
            // The publishActiveFlag(true) write is deliberately done AFTER releasing the
            // monitor (still gated by the captured `commit`): only the CHECK needs to be
            // serialised — holding the monitor across UCM's disk write + cross-process file
            // lock would needlessly stall a concurrent stop()/start() (and the sequential
            // ACC-off gauge restore). The am start likewise runs OUTSIDE the lock (it can take
            // seconds); the irreducible window where stop() lands after the commit is caught
            // by the reconciliation gate, which re-publishes false + dismisses the ghost.
            final boolean commit;
            synchronized (ClusterMapProjector.class) {
                commit = active && launchThread == self && isSustainedProjectionHeld();
            }
            if (!commit) {
                if (launchThread != self) return;   // superseded — new session owns the flag
                continue;                           // torn down — loop head reconciles active
            }
            // Re-assert the coordination flag so the freshly-launched Activity's first
            // self-finish poll sees true and stays up. (Off the monitor — see above.)
            publishActiveFlag(true);
            logger.warn("cluster map gone from displayId " + displayId
                    + " (likely app swiped away / process recycled) — relaunching to restore "
                    + "the projection (was black)");
            if (issueLaunch(displayId, /*attempt=*/ 0)) {
                // Give the cold-start a moment, then re-verify before counting it healed.
                // NOTE: every inner exit is a `break` (never a bare `return`) so the
                // post-launch reconciliation gate below ALWAYS runs — an early return here
                // was the ghost-map bug: an ACC-off/disable racing the relaunch would skip
                // the reconciliation and leave the just-launched Activity painting a stale
                // map over the restored gauges for the whole parked period.
                for (int poll = 0; poll < LAUNCH_VERIFY_POLLS_PER_ATTEMPT; poll++) {
                    if (!sleepWhileActive(LAUNCH_VERIFY_POLL_MS)) break;  // torn down mid-verify → reconcile
                    if (launchThread != self) break;                     // superseded → reconcile
                    if (isMapResumedOnDisplay(displayId)) {
                        logger.info("cluster map RELAUNCHED + resumed on displayId " + displayId);
                        break;
                    }
                }
            }
            // ── SINGLE RECONCILIATION GATE — every relaunch exit funnels here ─────────
            // Re-validate ALL THREE liveness conditions AFTER the launch+verify (which can
            // take seconds), because a stop() (disable), ACC-off, a direct forceClose
            // (bs-disable / relayout / retarget), or a stop()→start() bounce can all land
            // during that window. Two distinct outcomes:
            if (launchThread != self) {
                // SUPERSEDED by a newer start(): a fresh watchdog now owns the projection
                // and the clusterMapActive flag. DON'T touch the flag (publishing false
                // would clobber the new session and dismiss ITS map) — just exit. This is
                // the fix for the stop()→start() bounce double-launch.
                return;
            }
            if (!active || !isSustainedProjectionHeld()) {
                // GENUINELY TORN DOWN while we were relaunching (disable cleared active;
                // ACC-off cleared both; a direct forceClose dropped the hold). The
                // publishActiveFlag(true) above + the am start may have surfaced a ghost
                // Activity over the now-restored gauges — dismiss it by re-asserting false
                // (its ~500ms self-finish poll then removes it) and reconcile our own flag.
                // Synchronized + identity-guarded (mirrors the line-377 gate) so a bounce
                // that slips in here still defers to the new session.
                synchronized (ClusterMapProjector.class) {
                    if (launchThread == self) {
                        active = false;
                        publishActiveFlag(false);
                    }
                }
                return;
            }
            // Still live + ours: relaunch round complete. Reset the miss counter so the
            // next full KEEPALIVE_MISS_THRESHOLD window must elapse before another relaunch
            // (a process cold-start can take longer than one verify window).
            consecutiveMisses = 0;
        }
    }

    /** Issue one {@code am start --display N}. Returns true iff the command exited 0. */
    private static boolean issueLaunch(int displayId, int attempt) {
        try {
            // A docked app cast left on this display would resolve the map launch into
            // split-screen-secondary; no-op when nothing is docked.
            com.overdrive.app.launcher.ClusterCast.clearStaleClusterDock(displayId);
            String[] cmd = {
                "am", "start",
                "--display", String.valueOf(displayId),
                "--windowingMode", WINDOWING_MODE_FULLSCREEN,  // full panel, not freeform
                "-f", LAUNCH_FLAGS,
                "--ez", "cluster", "true",   // RoadSenseMapActivity reads this → cluster view
                "-n", MAP_ACTIVITY
            };
            logger.info("launching map onto displayId " + displayId + " (attempt " + attempt + ")");
            Process proc = Runtime.getRuntime().exec(cmd);
            // Bounded wait (mirrors ClusterProjectionController.sendInfoShell's 2s cap): an
            // unbounded waitFor() let a wedged `am start` hang the watchdog thread, so a
            // disable/ACC-off couldn't be honored until it returned. 5s comfortably covers a
            // cold app cold-start while still letting the loop re-check liveness promptly.
            boolean done = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                proc.destroy();
                logger.warn("am start --display " + displayId + " did not exit within 5s");
                return false;
            }
            if (proc.exitValue() != 0) {
                logger.warn("am start --display " + displayId + " exited " + proc.exitValue());
                return false;
            }
            return true;
        } catch (Throwable t) {
            logger.warn("launchMapOnDisplay failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * True iff our cluster map component is the RESUMED activity on {@code displayId}
     * per {@code dumpsys activity activities}. We scan for the "Display #N" block and,
     * within it, a ResumedActivity line naming our component. Robust to per-build
     * dumpsys layout: we accept a match when, after the target display header and
     * before the next "Display #" header, a line contains both "ResumedActivity" and
     * our class. Conservative — any parse failure returns false (→ retry), never a
     * false positive that would mask a real no-show.
     */
    private static boolean isMapResumedOnDisplay(int displayId) {
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
                String low = line.toLowerCase(java.util.Locale.US);
                int hdr = low.indexOf("display #");
                if (hdr >= 0) {
                    // Entering a display block — is it ours? (match "display #1" exactly,
                    // not "display #10"+).
                    inTargetDisplay = low.startsWith(displayHeader, hdr)
                            && !Character.isDigit(charAt(low, hdr + displayHeader.length()));
                    continue;
                }
                if (inTargetDisplay
                        && low.contains("resumedactivity")
                        && low.contains("roadsenseclustermapactivity")) {
                    return true;
                }
            }
        } catch (Throwable t) {
            logger.debug("isMapResumedOnDisplay failed: " + t.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
        return false;
    }

    private static char charAt(String s, int i) {
        return (i >= 0 && i < s.length()) ? s.charAt(i) : ' ';
    }

    /** True iff the daemon still holds the OEM cluster projection open FOR THE MAP
     *  (a sustained holder). Read null-safely (never constructs the controller singleton)
     *  so a head-unit-only daemon that never opened a projection reports false. The
     *  keep-alive watchdog gates relaunches on this so a projection closed via a DIRECT
     *  forceClose (blind-spot disable / relayout / retarget / ACC-off) — which restores
     *  the gauges but does not flip this class's {@link #active} flag — is never repainted
     *  over the restored gauges. */
    private static boolean isSustainedProjectionHeld() {
        try {
            return com.overdrive.app.surveillance.ClusterProjectionController.isSustainedHeldStatic();
        } catch (Throwable t) {
            // On any reflection/link error, FAIL SAFE: assume NOT held so we never relaunch
            // a ghost map over the gauges. Worst case the map doesn't auto-recover on swipe
            // (the pre-change behaviour), which is strictly safer than blanking the gauges.
            logger.warn("isSustainedProjectionHeld check failed: " + t.getMessage());
            return false;
        }
    }

    /** Sleep {@code ms}, returning false if the projection was stopped meanwhile (so
     *  the caller aborts the retry loop immediately rather than fighting a teardown). */
    private static boolean sleepWhileActive(int ms) {
        if (!active) return false;
        try { Thread.sleep(ms); } catch (InterruptedException e) { return false; }
        return active;
    }
}
