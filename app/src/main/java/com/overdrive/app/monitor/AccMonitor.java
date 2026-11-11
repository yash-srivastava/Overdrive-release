package com.overdrive.app.monitor;

import com.overdrive.app.daemon.CameraDaemon;

/**
 * ACC Monitor - State holder for ACC status with direct hardware query.
 * 
 * ACC state detection is handled by AccSentryDaemon which:
 * 1. Uses BYDAutoBodyworkDevice listener for real ACC events
 * 2. Falls back to sys.accanim.status polling
 * 3. Sends IPC commands to SurveillanceEngine on port 19877
 * 
 * On CameraDaemon restart (e.g., after EGL crash), the ACC state is read
 * directly from BYDAutoBodyworkDevice.getPowerLevel() so the daemon can
 * re-enter sentry mode without depending on AccSentryDaemon IPC.
 */
public class AccMonitor {

    // Power levels from BYDAutoBodyworkDevice (same as AccSentryDaemon)
    private static final int POWER_LEVEL_OFF = 0;
    private static final int POWER_LEVEL_ACC = 1;
    private static final int POWER_LEVEL_ON = 2;

    private static volatile boolean inSentryMode = false;
    // Default to false (ACC off) - safer assumption until AccSentryDaemon confirms state
    // This prevents false "acc: true" in status when daemon restarts
    private static volatile boolean accOn = false;

    // Distinguishes "we received an authoritative IPC from AccSentryDaemon"
    // from "we're at the default ACC=false". RecordingModeManager's hardware
    // fallback uses this to decide whether AccMonitor's state is trustworthy
    // — without it, a CameraDaemon restart leaves accOn=false (default) and
    // the recording pipeline can't tell that apart from a real ACC OFF, so
    // it stays unrecorded for the rest of the drive.
    private static volatile boolean accOnAuthoritative = false;
    private static volatile long accStateUpdatedAtElapsedMs;
    private static volatile long lastAccOnElapsedMs;
    private static volatile long lastAccOffEdgeElapsedMs;
    private static final long DILINK5_SAFETY_STATE_MAX_AGE_MS = 15_000L;

    // DiLink 5 car_service power-mode dump source: ACC-OFF admission dampener.
    // A single OFF-looking dump row must never flip a drive into sentry (the
    // dc327fe bug class) — require DILINK5_DUMP_OFF_CONFIRMATIONS consistent
    // OFF readings, spaced at least DILINK5_DUMP_OFF_MIN_SPACING_MS apart
    // (so one glitch re-read by CameraDaemon's 200ms retry loop counts once),
    // all inside DILINK5_DUMP_OFF_WINDOW_MS (so observations from different
    // parking events never combine). ON-looking rows immediately reset this
    // OFF streak; their final transition admission remains source-aware below.
    // Guarded by DILINK5_DUMP_ADMISSION_LOCK; this is
    // internal probe bookkeeping, NOT published ACC state — probeDiLink5AccOn
    // stays side-effect-free with respect to accOn/inSentryMode/authoritative.
    static final int DILINK5_DUMP_OFF_CONFIRMATIONS = 2;
    static final long DILINK5_DUMP_OFF_WINDOW_MS = 30_000L;
    static final long DILINK5_DUMP_OFF_MIN_SPACING_MS = 3_000L;
    private static final Object DILINK5_DUMP_ADMISSION_LOCK = new Object();
    private static int diLink5DumpOffStreak = 0;
    private static long diLink5DumpOffFirstAtElapsedMs;
    private static long diLink5DumpOffLastCountedAtElapsedMs;
    static final int DILINK5_POWER_MODE_UNKNOWN = -1;
    static final int DILINK5_POWER_MODE_OFF = 0;
    static final int DILINK5_POWER_MODE_STARTUP = 1;
    static final int DILINK5_POWER_MODE_IVI_AWAKE = 2;
    private static volatile String lastDiLink5ObservationSummary = "unavailable";
    private static volatile String lastLoggedDiLink5ObservationSignature = "";

    // Trustworthiness of the MOST RECENT probeAccState() call. True only when the
    // last probe landed on a CLEAN bodywork power level (0-3); false when it
    // returned via a sentinel bluff (FAKE_OK=4 / INVALID=255), a reflection/device
    // failure, or the "assume ACC-ON safe default" fallback. The ACC-ON disarm
    // watchdog reads this so it disarms ONLY on a real ignition-on (clean level≥2)
    // and never on a sentinel that merely DEFAULTED to ACC-ON — the latter is what
    // a parked car with "Keep USB powered" OFF produces once AccSentryDaemon's IPC
    // heartbeats stop. A genuine ACC-ON still reads cleanly, so real disarm is
    // unaffected. Volatile: written on the probe thread, read on the watchdog thread.
    private static volatile boolean lastProbeTrustworthy = false;

    // Last accOn value an EDGE was dispatched for, so notifyAccEdge fires the
    // auto-project hook only on a genuine OFF→ON transition (both setAccState
    // IPC and probeAccState refresh the value repeatedly without a real change).
    // -1 = no edge dispatched yet (first authoritative read is treated as an edge).
    private static volatile int lastEdgeState = -1;

    // Set only in the camera daemon, which runs legacy cluster boot recovery and holds its
    // in-memory admission state; other processes that observe an ACC edge do not.
    private static volatile boolean clusterRecoveryOwner;

    public static void markClusterRecoveryOwner() {
        clusterRecoveryOwner = true;
    }

    // Track the last sentinel state we logged (FAKE_OK=4, INVALID=255, or
    // out-of-range value), so we log only on transitions. Without this,
    // a persistently broken HAL would emit ~2880 "powerLevel=INVALID" lines
    // per day. -1 = no sentinel currently observed (last reading was a
    // real 0/1/2/3 or no probe has run yet).
    private static volatile int lastLoggedSentinel = -1;

    // Cached reflection for probeAccState. Without caching, every probe
    // (called every 5s by CameraDaemon.startAccOnDisarmWatchdog while
    // sentry is active = ~17,000 probes/day overnight) re-runs
    // Class.forName + 2× getMethod. The HAL surface is fixed at boot, so
    // resolve once and reuse. Volatile for safe publication; idempotent
    // double-resolve race is acceptable.
    //
    // Mirrors the pattern already used in RecordingModeManager
    // .resolveBodyworkReflection — same target Class+Methods, same
    // resolved/failed semantics.
    private static volatile Class<?> bodyworkDeviceClassCache;
    private static volatile java.lang.reflect.Method bodyworkGetInstanceCache;
    private static volatile java.lang.reflect.Method bodyworkGetPowerLevelCache;
    private static volatile boolean bodyworkReflectionResolved = false;
    private static volatile boolean bodyworkReflectionFailed = false;

    private static void resolveBodyworkReflection() {
        if (bodyworkReflectionResolved || bodyworkReflectionFailed) return;
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            java.lang.reflect.Method getInstance =
                cls.getMethod("getInstance", android.content.Context.class);
            java.lang.reflect.Method getPowerLevel = cls.getMethod("getPowerLevel");
            bodyworkDeviceClassCache = cls;
            bodyworkGetInstanceCache = getInstance;
            bodyworkGetPowerLevelCache = getPowerLevel;
            // MUST be the last write — readers that observe resolved=true rely
            // on volatile happens-before to see the three Class/Method fields
            // already populated. Reordering this above the cache assignments
            // would let a racing reader see resolved=true with null Methods.
            bodyworkReflectionResolved = true;
        } catch (Exception e) {
            // Permanent — class/method genuinely missing on this firmware.
            // Per-call invoke failures (transient binder errors) do NOT
            // come through here; they hit the outer catch in probeAccState.
            bodyworkReflectionFailed = true;
            CameraDaemon.log("AccMonitor: BYDAutoBodyworkDevice reflection unavailable: "
                + e.getMessage());
        }
    }

    public static boolean isAccOn() {
        return accOn;
    }

    /** elapsedRealtime of the latest ACC-on report (0 = none this process); marks a new drive. */
    public static long lastAccOnElapsedMs() {
        return lastAccOnElapsedMs;
    }

    /** elapsedRealtime of the latest genuine ON→OFF edge (0 = none this process). */
    public static long lastAccOffEdgeElapsedMs() {
        return lastAccOffEdgeElapsedMs;
    }

    /** Fail-safe guard used immediately before any full-screen deterrent render. */
    public static boolean isVehicleActive() {
        return accOn
                || (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                        && hasDrivingTelemetry());
    }

    private static boolean hasDrivingTelemetry() {
        try {
            com.overdrive.app.byd.BydDataCollector collector =
                    com.overdrive.app.byd.BydDataCollector.getInstance();
            return hasDrivingTelemetry(
                    collector.readCurrentSpeedKmh(), collector.readGearNow());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean hasDrivingTelemetry(double speedKmh, int gear) {
        return (!Double.isNaN(speedKmh) && speedKmh > 0)
                || (gear > GearMonitor.GEAR_P && gear <= GearMonitor.GEAR_S);
    }

    public static boolean isInSentryMode() {
        return inSentryMode;
    }

    /**
     * True iff setAccState() has been called at least once since process
     * start — i.e. we have an authoritative reading from AccSentryDaemon
     * via IPC. False means accOn is still at its (false) default and
     * callers should NOT treat it as "ACC is OFF" — it could be either.
     *
     * Used by RecordingModeManager.queryAccStateFromHardware to gate its
     * fallback path: when AccMonitor isn't authoritative, the RMM probes
     * the HAL directly instead of trusting the default.
     */
    public static boolean isAccStateAuthoritative() {
        return accOnAuthoritative;
    }

    /** Freshness is enforced only by the DiLink 5 movement gate. */
    public static boolean isAccStateFreshForSafety() {
        return isStateFresh(
                android.os.SystemClock.elapsedRealtime(),
                accStateUpdatedAtElapsedMs,
                DILINK5_SAFETY_STATE_MAX_AGE_MS);
    }

    public static long accStateFreshUntilForSafety() {
        return stateFreshUntil(
                accStateUpdatedAtElapsedMs,
                DILINK5_SAFETY_STATE_MAX_AGE_MS);
    }

    /**
     * DiLink 5 movement gate: re-stamps safety freshness when a fresh read agrees with the
     * published state. Never changes the published state or dispatches an edge.
     */
    public static void confirmDiLink5StateForSafety() {
        if (!accOnAuthoritative || isAccStateFreshForSafety()) return;
        try {
            boolean published = accOn;
            long observedAtElapsedMs = android.os.SystemClock.elapsedRealtime();
            Boolean observed = probeDiLink5AccOnForTransition(null, published);
            if (observed == null || observed.booleanValue() != published) return;
            synchronized (AccMonitor.class) {
                if (accOnAuthoritative && accOn == published
                        && observedAtElapsedMs > accStateUpdatedAtElapsedMs) {
                    accStateUpdatedAtElapsedMs = observedAtElapsedMs;
                }
            }
        } catch (Throwable ignored) {
            // Stale state stays stale; the gate fails closed.
        }
    }

    static long stateFreshUntil(long updatedAtElapsedMs, long maxAgeMs) {
        if (updatedAtElapsedMs <= 0L || maxAgeMs <= 0L
                || updatedAtElapsedMs > Long.MAX_VALUE - maxAgeMs) {
            return 0L;
        }
        return updatedAtElapsedMs + maxAgeMs;
    }

    static boolean isStateFresh(long nowElapsedMs, long updatedAtElapsedMs, long maxAgeMs) {
        long age = nowElapsedMs - updatedAtElapsedMs;
        return updatedAtElapsedMs > 0L && maxAgeMs > 0L
                && age >= 0L && age <= maxAgeMs;
    }

    /**
     * Called by SurveillanceEngine IPC when AccSentryDaemon sends ACC state.
     */
    public static void setAccState(boolean isAccOn) {
        synchronized (AccMonitor.class) {
            accOn = isAccOn;
            inSentryMode = !isAccOn;
            // First IPC marks the state authoritative; stays authoritative for
            // the rest of the process lifetime (subsequent IPCs just refresh
            // the value).
            accOnAuthoritative = true;
            accStateUpdatedAtElapsedMs = android.os.SystemClock.elapsedRealtime();
            if (isAccOn) lastAccOnElapsedMs = accStateUpdatedAtElapsedMs;
        }
        CameraDaemon.log("ACC state updated via IPC: accOn=" + isAccOn + ", sentryMode=" + inSentryMode);
        notifyAccEdge(isAccOn);
    }

    /**
     * Dispatch side-effects on a genuine ACC state EDGE. Both the IPC path
     * (setAccState) and the hardware probe (probeAccState) refresh accOn on every
     * call, so this de-dupes to the actual OFF→ON / ON→OFF transition.
     *
     * <p>OFF→ON: if the user enabled "auto-project map to cluster"
     * (navMap.autoProjectCluster), start the cluster map projection.
     *
     * <p>ON→OFF: two distinct teardowns are needed, because the SUSTAINED map
     * holder and a TRANSIENT blind-spot projection close via different paths:
     * <ul>
     *   <li>SUSTAINED map: {@code ClusterMapProjector.stop()} releases the holder
     *       and signals the launched cluster Activity to self-finish (it polls
     *       navMap.clusterMapActive and finishes within ~500ms — the OEM 18→0
     *       close never destroys the fission display, so its onDisplayRemoved
     *       self-finish does not fire on a normal stop).</li>
     *   <li>TRANSIENT blind-spot: there is NO ACC-off path inside the BS turn loop
     *       — {@code bsTurnTick} has no ACC guard and the pipeline stays alive in
     *       sentry mode, so {@code disableBlindSpot()} (the only BS forceClose) is
     *       never reached on ACC-off. Without an explicit close here, a turn signal
     *       held ON at the instant of ACC-off would leave the gauges blanked until
     *       the 8s linger / 90s max-cap. So we force-close the projection directly
     *       via {@link com.overdrive.app.surveillance.ClusterProjectionController#forceCloseIfActive}.</li>
     * </ul>
     * Both are idempotent + no-op if nothing is active, and never construct the
     * controller singleton on a head-unit-only daemon. Never throws.
     */
    private static void notifyAccEdge(boolean isAccOn) {
        int previousEdgeState = lastEdgeState;
        if (previousEdgeState == (isAccOn ? 1 : 0)) return;  // no real transition
        lastEdgeState = isAccOn ? 1 : 0;
        if (!isAccOn && previousEdgeState == 1) {
            lastAccOffEdgeElapsedMs = android.os.SystemClock.elapsedRealtime();
        }
        try {
            ParkedPosition.get().onAccEdge(isAccOn);
        } catch (Throwable ignored) {}
        if (!isAccOn) {
            // Load-bearing ordering for BOTH legacy and DI5: every map/app stop
            // below may physically retire the OEM projection source. Detach
            // both consumers first so SurfaceFlinger never sees a live virtual
            // display reading a source that is being destroyed.
            try {
                com.overdrive.app.surveillance.ClusterViewMirrorService
                        .forceDetachIfActive("acc-off");
                if (!com.overdrive.app.surveillance.ClusterMirrorController
                        .forceCloseIfActive("acc-off")) {
                    CameraDaemon.log("ACC-off mirror detach not yet confirmed; "
                            + "projection close paths will retain recovery ownership");
                }
            } catch (Throwable t) {
                CameraDaemon.log("notifyAccEdge ACC-off cluster mirror stop failed: "
                        + t.getMessage());
            }
            // ACC-OFF: stop the cluster map projector so its holder releases + the
            // launched cluster Activity is torn down. Safe + idempotent if not active.
            try {
                if (com.overdrive.app.navmap.ClusterMapProjector.isActive()) {
                    CameraDaemon.log("ACC-off edge: stopping cluster map projection");
                    // Releases the sustained hold AND clears navMap.clusterMapActive
                    // so the launched cluster Activity self-finishes (~500ms poll).
                    com.overdrive.app.navmap.ClusterMapProjector.stop();
                }
            } catch (Throwable t) {
                CameraDaemon.log("notifyAccEdge ACC-off stop failed: " + t.getMessage());
            }
            // ACC-OFF: reconcile any driver-cluster APP CAST (move-app-to-cluster). Its
            // "castapp" sustained hold is dropped by the forceClose below (which clears
            // all holders + restores the gauges), but ClusterCast keeps its own active
            // flag — stop() reconciles it so a later isActive()/start() isn't confused.
            // Idempotent + no-op if nothing is cast.
            try {
                if (com.overdrive.app.launcher.ClusterCast.isActive()) {
                    CameraDaemon.log("ACC-off edge: stopping cluster app cast");
                    // ACC-off-safe stop: release the hold only, NO am/shell reparent work in
                    // the load-bearing SF teardown window below (mirror-VD-before-source).
                    com.overdrive.app.launcher.ClusterCast.stopForAccOff();
                }
            } catch (Throwable t) {
                CameraDaemon.log("notifyAccEdge ACC-off cluster cast stop failed: " + t.getMessage());
            }
            // ACC-OFF: THEN force-close any TRANSIENT blind-spot cluster projection so the
            // gauges are restored IMMEDIATELY (not after the 8s linger). No-op if the
            // projection was never opened (controller singleton null) or is already closed.
            // The BS turn loop is gated against re-opening after an authoritative ACC-off
            // (see GpuSurveillancePipeline.bsTurnTick), so this close is not re-asserted by
            // the next 250ms tick mid-blink.
            try {
                com.overdrive.app.surveillance.ClusterProjectionController.forceCloseIfActive("acc-off");
            } catch (Throwable t) {
                CameraDaemon.log("notifyAccEdge ACC-off cluster force-close failed: " + t.getMessage());
            }
            return;
        }
        // ACC-ON: wake the panel from THIS process too. AccSentryDaemon already
        // wakes it on its own ACC-ON edge, but that daemon is a separate process
        // and can be dead, wedged, or killed mid-park — in which case nothing
        // there runs and the driver is handed a dark screen with no way to
        // recover. byd_cam_daemon is independently watchdogged and also UID 2000,
        // which makes this the natural second wake path. This is the narrowest
        // de-duped OFF→ON edge in the process and is reached from BOTH the IPC
        // path and the independent hardware probe, so it also covers a
        // lost/never-sent ACC-ON IPC.
        //
        // turnOn() self-skips when getPowerScreenStatus() already reads on, so
        // this is effectively free whenever the panel is already awake —
        // including on the whole DiLink 3 fleet. Dispatched off this thread: it
        // does binder reflection round-trips and this edge runs on the IPC/probe
        // thread, where the side effects below expect to stay quick.
        // Stamp the real ACC-ON edge so StealthPanel honours "vehicle in use" and
        // suppresses any darken attempt during the transition (notably the screen
        // deterrent's teardown, which runs in this process). Cheap volatile write;
        // done inline, before the dispatch, so the suppression is in effect
        // immediately rather than after a thread starts.
        try {
            com.overdrive.app.power.StealthPanel.noteAccOnObserved();
        } catch (Throwable ignored) {}
        try {
            new Thread(() -> {
                try {
                    com.overdrive.app.power.StealthPanel.turnOn(CameraDaemon.getAppContext());
                } catch (Throwable t) {
                    CameraDaemon.log("notifyAccEdge panel wake failed: " + t.getMessage());
                }
            }, "StealthPanelWake-AccOn").start();
        } catch (Throwable t) {
            CameraDaemon.log("notifyAccEdge panel wake dispatch failed: " + t.getMessage());
        }
        maybeAutoStartClusterProjection("ACC-on edge");
    }

    /**
     * Replays a consumed ACC-on auto-projection request after cross-mode boot
     * recovery drops its admission fence. If ACC has not yet been established,
     * the normal future ACC edge remains the owner and this is a no-op.
     */
    public static void retryClusterAutoProjectionAfterRecovery() {
        if (!isAccStateAuthoritative() || !isAccOn()) return;
        try {
            if (com.overdrive.app.navmap.ClusterMapProjector.isActive()
                    || com.overdrive.app.launcher.ClusterCast.isActive()) {
                return;
            }
        } catch (Throwable ignored) {
        }
        maybeAutoStartClusterProjection("projection-recovery");
    }

    private static void maybeAutoStartClusterProjection(String reason) {
        try {
            // At most ONE cluster takeover can auto-start. Read both settings
            // from one snapshot and keep the established map-wins tiebreak.
            org.json.JSONObject cfg =
                    com.overdrive.app.config.UnifiedConfigManager
                            .forceReload();
            org.json.JSONObject nav = cfg.optJSONObject("navMap");
            boolean mapAuto = nav != null
                    && nav.optBoolean("autoProjectCluster", false);
            org.json.JSONObject proj = cfg.optJSONObject("projection");
            boolean projAuto = proj != null
                    && proj.optBoolean("autoStartOnAcc", false);
            String projPkg = proj != null
                    ? proj.optString("autoStartPackage", "")
                    : "";
            if (mapAuto && projAuto) {
                CameraDaemon.log(reason + ": BOTH cluster auto-starts "
                        + "enabled — map wins");
                projAuto = false;
            }
            if ((mapAuto || (projAuto && projPkg != null && !projPkg.isEmpty()))
                    && !clusterRecoveryOwner
                    && !com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5()
                    && com.overdrive.app.surveillance.ClusterProjectionController
                            .isLegacyCommandUnresolvedThisBoot(
                                    cfg.optJSONObject("surveillance"))) {
                CameraDaemon.log(reason + ": cluster auto-start skipped — a legacy "
                        + "cluster command from this boot is still unresolved");
                return;
            }
            if (mapAuto) {
                CameraDaemon.log(reason
                        + ": auto-projecting map to cluster");
                com.overdrive.app.navmap.ClusterMapProjector.start();
            } else if (projAuto
                    && projPkg != null
                    && !projPkg.isEmpty()) {
                CameraDaemon.log(reason
                        + ": auto-casting projection app " + projPkg);
                com.overdrive.app.launcher.ClusterCast.start(projPkg);
            }
        } catch (Throwable t) {
            CameraDaemon.log(reason
                    + " auto-start check failed: " + t.getMessage());
        }
    }

    /**
     * Reads ACC state directly from BYDAutoBodyworkDevice hardware.
     * No dependency on AccSentryDaemon or file persistence.
     * 
     * @param context Android context for BYD device API
     * @return true if ACC is OFF (sentry mode should be active), false if ACC is ON or unknown
     */
    public static boolean probeAccState(android.content.Context context) {
        // 1. DI-LINK 5.0 (Android 11 Automotive / SA8155P) ACC PROBE
        // On DiLink 5.0, BYDAutoBodyworkDevice is virtualized/missing or returns sentinel 4/255.
        // We probe the local Automotive power mode and shutdown-animation state.
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            Boolean isOn = probeDiLink5AccOnForTransition(
                    context,
                    accOnAuthoritative && accOn);
            if (isOn != null) {
                return publishDiLink5State(isOn, "local power probe");
            }
            lastProbeTrustworthy = false;
            // UNKNOWN/weak IVI-awake is not an ACC-ON edge. Preserve a state
            // already established by AccSentryDaemon instead of returning the
            // generic "unknown => ON" safety default. The old behavior made
            // RecordingModeManager report hwAcc=true while CameraDaemon was
            // still authoritatively parked, leaving two DiLink 5 consumers in
            // opposite states. A cold process with no authoritative IPC still
            // defaults to ON, so recording is never suppressed on uncertainty.
            return diLink5UndecidedAccOffFallback(
                    accOnAuthoritative, accOn);
        }

        // 2. LEGACY DILINK 3.0 / 4.0 BYDAutoBodyworkDevice PROBE (Preserved 100% untouched)
        resolveBodyworkReflection();
        if (!bodyworkReflectionResolved) {
            // Class genuinely missing on this firmware — safe default.
            // Don't enter sentry on a permanent reflection failure.
            lastProbeTrustworthy = false;
            return false;
        }
        try {
            Object device = bodyworkGetInstanceCache.invoke(null, context);

            if (device == null) {
                CameraDaemon.log("AccMonitor: BYDAutoBodyworkDevice.getInstance returned null");
                lastProbeTrustworthy = false;
                return false;
            }

            int level = (Integer) bodyworkGetPowerLevelCache.invoke(device);

            // Only trust the four legitimate power levels (0/1/2/3). The HAL
            // can also return FAKE_OK=4 or INVALID=255, both of which mean
            // "this reading is untrustworthy." Treating either as ACC=ON
            // (because both are >= POWER_LEVEL_ON=2) would incorrectly drop
            // sentry mode. On sentinel/unknown, KEEP the prior state — the
            // last IPC from AccSentryDaemon is more reliable than a HAL
            // bluff. Return true (sentry) only if we're confident ACC=OFF.
            if (level < 0 || level > 3) {
                // Short retry loop with backoff before treating sentinel as
                // authoritative. Prior-audit found that boot-time probes
                // (CameraDaemon post-init drain at ~line 678 and boot
                // recovery at ~line 970) hit a sentinel reading + cold
                // AccMonitor cache (accOn defaults to false), then fell
                // through to "return !accOn" = true = ACC OFF. That
                // dispatched a false ACC-OFF mid-drive, dropping pano
                // CONTINUOUS / DRIVE_MODE recording. Retry up to 2
                // additional times × 200 ms — transient HAL bluffs settle
                // within ~400 ms in practice (matches the 200-500 ms
                // ignition transient window already documented in
                // AccSentryDaemon's heartbeat).
                int retryLevel = level;
                for (int attempt = 0; attempt < 2 && (retryLevel < 0 || retryLevel > 3); attempt++) {
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    try {
                        retryLevel = (Integer) bodyworkGetPowerLevelCache.invoke(device);
                    } catch (Exception probeEx) {
                        // Keep retryLevel at its prior sentinel; outer
                        // catch handles any reflection failure on the
                        // first invoke. A transient invoke failure here
                        // just means we exit the retry loop with a
                        // sentinel and apply the conservative branch.
                        break;
                    }
                }
                if (retryLevel >= 0 && retryLevel <= 3) {
                    CameraDaemon.log("AccMonitor: hardware probe sentinel="
                        + (level == 4 ? "FAKE_OK" : (level == 255 ? "INVALID" : "UNKNOWN(" + level + ")"))
                        + " settled to level=" + retryLevel + " after retry");
                    level = retryLevel;
                    // Fall through to the real-reading branch below.
                } else {
                    // Log only when entering a new sentinel state; otherwise a
                    // persistently broken HAL would flood the log at the probe
                    // interval. Reset the sentinel tracker once we observe a
                    // real value again (handled in the success branch below).
                    if (lastLoggedSentinel != level) {
                        CameraDaemon.log("AccMonitor: hardware probe powerLevel="
                            + (level == 4 ? "FAKE_OK" : (level == 255 ? "INVALID" : "UNKNOWN(" + level + ")"))
                            + " — keeping prior accOn=" + accOn
                            + " authoritative=" + accOnAuthoritative);
                        lastLoggedSentinel = level;
                    }
                    // When we have NO authoritative state yet (cold cache,
                    // accOn=false default), the "!accOn" return would
                    // falsely claim ACC=OFF on a HAL bluff. Refuse to
                    // claim sentry in that case — return false (ACC ON,
                    // safe default that keeps recording alive). Only
                    // trust the prior state when an authoritative IPC
                    // has already established it.
                    // Sentinel reading — NOT a clean power level. Mark the probe
                    // untrustworthy so the ACC-ON disarm watchdog won't act on it
                    // (a sentinel that defaults to ACC-ON must never disarm a parked
                    // session; a real ignition-on reads cleanly below).
                    lastProbeTrustworthy = false;
                    if (!accOnAuthoritative) {
                        CameraDaemon.log("AccMonitor: sentinel + cold cache — returning ACC ON (safe default, not sentry)");
                        return false;
                    }
                    return !accOn;
                }
            }
            // Real reading — clear the sentinel tracker so the next sentinel
            // (if any) gets logged. Also log the recovery once.
            if (lastLoggedSentinel != -1) {
                CameraDaemon.log("AccMonitor: hardware probe recovered (level=" + level + ")");
                lastLoggedSentinel = -1;
            }

            boolean isAccOn = level >= POWER_LEVEL_ON;
            accOn = isAccOn;
            inSentryMode = !isAccOn;
            // Clean power level (0-3, possibly settled from a retry) — this reading
            // is trustworthy. The disarm watchdog may act on it.
            lastProbeTrustworthy = true;
            notifyAccEdge(isAccOn);

            String levelStr;
            switch (level) {
                case 0: levelStr = "OFF"; break;
                case 1: levelStr = "ACC"; break;
                case 2: levelStr = "ON"; break;
                case 3: levelStr = "OK"; break;
                default: levelStr = "UNKNOWN(" + level + ")"; break;
            }
            CameraDaemon.log("AccMonitor: hardware probe powerLevel=" + levelStr +
                " → accOn=" + isAccOn + ", sentryMode=" + inSentryMode);

            return !isAccOn;  // true if ACC is OFF
        } catch (Exception e) {
            CameraDaemon.log("AccMonitor: hardware probe failed: " + e.getMessage());
            lastProbeTrustworthy = false;  // error path → untrustworthy reading
            return false;  // assume ACC ON (safe default — don't enter sentry on error)
        }
    }

    /**
     * Side-effect-free DiLink 5 power read. CameraDaemon uses this before its
     * generation check, so this method must not update global ACC state.
     * (The dump-source OFF dampener keeps private streak bookkeeping, but
     * accOn/inSentryMode/authoritative are never written here.)
     */
    public static Boolean probeDiLink5AccOn(android.content.Context context) {
        return probeDiLink5AccObservation(context).accOn;
    }

    /**
     * Transition-safe DiLink 5 ACC read.
     *
     * <p>Automotive {@code Display on}/{@code Degraded} modes prove that the
     * IVI/AP is awake, not that the driver switched the vehicle on. They may
     * appear during a CameraDaemon restart or a parked cloud wake. Such a weak
     * observation may maintain an already-ON state, but cannot by itself
     * disarm a confirmed parked session. A real transition is still immediate
     * when corroborated by driving telemetry, {@code sys.accanim.status=0}, or
     * the vehicle {@code StartUp} power mode.
     */
    public static Boolean probeDiLink5AccOnForTransition(
            android.content.Context context, boolean currentlyAccOn) {
        return probeDiLink5AccOnForTransition(context, currentlyAccOn, false);
    }

    /**
     * Transition-safe DiLink 5 ACC read with an optional independent corroborator.
     *
     * @param independentIgnitionEvidence true when a source OTHER than the IVI
     *        power mode has just reported the vehicle switching on — the
     *        app-process {@code com.byd.action.ACC_ON} / {@code IGN_ON} broadcast,
     *        carried across processes by
     *        {@link com.overdrive.app.power.IgnitionBroadcastHint}. This is the
     *        "second, independent source" a weak IVI-awake reading needs to be
     *        admitted as ACC ON from a confirmed parked state. It never overrides a
     *        strong OFF and never resolves an UNKNOWN observation.
     */
    public static Boolean probeDiLink5AccOnForTransition(
            android.content.Context context, boolean currentlyAccOn,
            boolean independentIgnitionEvidence) {
        return admitDiLink5AccObservation(
                probeDiLink5AccObservation(context),
                currentlyAccOn || independentIgnitionEvidence);
    }

    /**
     * Property-only DiLink 5 ignition read for processes that do not run the full probe: no
     * car_service dump, no telemetry, no state or logging. A weak IVI-awake reading counts only
     * with a fresh ACC_ON/IGN_ON broadcast hint.
     */
    public static boolean isDiLink5IgnitionOnFromProperties() {
        try {
            int power = classifyDiLink5PowerModeKind(
                    getSystemProperty("sys.byd.power_mode", ""));
            DiLink5AccObservation observation = resolveDiLink5AccObservation(
                    classifyAccAnim(getSystemProperty("sys.accanim.status", "")),
                    power == DILINK5_POWER_MODE_STARTUP,
                    power == DILINK5_POWER_MODE_IVI_AWAKE,
                    power == DILINK5_POWER_MODE_OFF,
                    "");
            return Boolean.TRUE.equals(admitDiLink5AccObservation(
                    observation,
                    com.overdrive.app.power.IgnitionBroadcastHint.isFresh(0L)));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Source-aware DiLink 5 power observation. This method is side-effect-free
     * with respect to the published ACC state; only the existing dump-source
     * OFF confirmation bookkeeping and diagnostic snapshot are updated.
     */
    public static DiLink5AccObservation probeDiLink5AccObservation(
            android.content.Context context) {
        if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            return rememberDiLink5Observation(
                    DiLink5AccObservation.unknown(
                            "platform", "DiLink 5 not selected"));
        }
        try {
            // Moving/in-gear is authoritative and must win over stale parked signals.
            if (hasDrivingTelemetry()) {
                return rememberDiLink5Observation(
                        DiLink5AccObservation.strong(
                                true, "driving telemetry", "moving/in-gear"));
            }

            String systemPowerRaw =
                    getSystemProperty("sys.byd.power_mode", "");
            int systemPowerKind =
                    classifyDiLink5PowerModeKind(systemPowerRaw);

            String dumpPowerRaw =
                    com.overdrive.app.byd.CarSvcTelemetry.powerModeLine();
            int dumpPowerKind =
                    classifyDiLink5PowerModeKind(dumpPowerRaw);
            Boolean dumpOffAdmitted = null;
            if (dumpPowerKind == DILINK5_POWER_MODE_OFF) {
                dumpOffAdmitted = admitDiLink5DumpPowerReading(
                        false,
                        android.os.SystemClock.elapsedRealtime());
            } else if (dumpPowerKind
                    != DILINK5_POWER_MODE_UNKNOWN) {
                // Any live ON-looking row retires an incomplete OFF streak.
                admitDiLink5DumpPowerReading(
                        true,
                        android.os.SystemClock.elapsedRealtime());
            }

            String accAnimRaw =
                    getSystemProperty("sys.accanim.status", "");
            int accAnim = classifyAccAnim(accAnimRaw);
            String diagnostics = "accanim=" + compactDiagnostic(accAnimRaw)
                    + ", sysPower=" + compactDiagnostic(systemPowerRaw)
                    + ", dumpPower=" + compactDiagnostic(dumpPowerRaw);

            boolean startupOn =
                    systemPowerKind == DILINK5_POWER_MODE_STARTUP
                    || dumpPowerKind == DILINK5_POWER_MODE_STARTUP;
            boolean iviAwake =
                    systemPowerKind == DILINK5_POWER_MODE_IVI_AWAKE
                    || dumpPowerKind == DILINK5_POWER_MODE_IVI_AWAKE;
            boolean powerOff =
                    systemPowerKind == DILINK5_POWER_MODE_OFF
                    || Boolean.FALSE.equals(dumpOffAdmitted);

            return rememberDiLink5Observation(
                    resolveDiLink5AccObservation(
                            accAnim,
                            startupOn,
                            iviAwake,
                            powerOff,
                            diagnostics));
        } catch (Throwable t) {
            return rememberDiLink5Observation(
                    DiLink5AccObservation.unknown(
                            "probe error",
                            t.getClass().getSimpleName() + ": "
                                    + t.getMessage()));
        }
    }

    static DiLink5AccObservation resolveDiLink5AccObservation(
            int accAnim,
            boolean startupOn,
            boolean iviAwake,
            boolean powerOff,
            String diagnostics) {
        String detail = diagnostics == null ? "" : diagnostics;

        // An ON accanim signal is drive-safe and may immediately disarm.
        // An OFF accanim signal must not override a simultaneous StartUp
        // mode: that conflict is common while ignition sources settle and
        // falsely entering sentry is more dangerous than preserving the
        // prior state for one poll.
        if (accAnim == 1) {
            return DiLink5AccObservation.strong(
                    true,
                    "sys.accanim.status",
                    detail);
        }
        if (accAnim == 0) {
            if (startupOn) {
                return DiLink5AccObservation.unknown(
                        "ignition-source conflict",
                        detail);
            }
            return DiLink5AccObservation.strong(
                    false,
                    "sys.accanim.status",
                    detail);
        }

        // Conflicting lifecycle rows are not an ignition edge. Preserve the
        // caller's admitted state until a coherent source arrives.
        if ((startupOn || iviAwake) && powerOff) {
            return DiLink5AccObservation.unknown(
                    "power-mode conflict", detail);
        }
        if (startupOn) {
            return DiLink5AccObservation.strong(
                    true, "vehicle PowerMode StartUp", detail);
        }
        if (powerOff) {
            return DiLink5AccObservation.strong(
                    false, "vehicle PowerMode stopped", detail);
        }
        if (iviAwake) {
            return DiLink5AccObservation.weakOn(
                    "IVI PowerMode awake", detail);
        }
        return DiLink5AccObservation.unknown(
                "no source",
                detail.isEmpty()
                        ? "all local power sources unavailable"
                        : "all local power sources unavailable; " + detail);
    }

    static Boolean admitDiLink5AccObservation(
            DiLink5AccObservation observation, boolean currentlyAccOn) {
        if (observation == null || observation.accOn == null) {
            return null;
        }
        if (!observation.accOn.booleanValue()) {
            return Boolean.FALSE;
        }
        if (observation.strong || currentlyAccOn) {
            return Boolean.TRUE;
        }
        // Display-on/degraded is an IVI lifecycle observation only. It cannot
        // turn a confirmed parked state into vehicle ACC ON without a second,
        // independent source; doing so is the restart/deep-sleep failure mode.
        return null;
    }

    /**
     * Boolean compatibility result for callers of {@link #probeAccState} when
     * the DiLink 5 transition probe is undecided.
     *
     * @return true only when an earlier authoritative state already established
     *         ACC OFF; a cold/unknown process stays on the fail-drive-safe ON
     *         default.
     */
    static boolean diLink5UndecidedAccOffFallback(
            boolean authoritative, boolean currentAccOn) {
        return authoritative && !currentAccOn;
    }

    public static String describeLastDiLink5AccObservation() {
        return lastDiLink5ObservationSummary;
    }

    private static DiLink5AccObservation rememberDiLink5Observation(
            DiLink5AccObservation observation) {
        String summary = observation.summary();
        lastDiLink5ObservationSummary = summary;
        String signature = observation.source + "|"
                + observation.accOn + "|" + observation.strong + "|"
                + observation.detail;
        if (!signature.equals(lastLoggedDiLink5ObservationSignature)) {
            lastLoggedDiLink5ObservationSignature = signature;
            CameraDaemon.log("AccMonitor [DiLink5]: observation " + summary);
        }
        return observation;
    }

    private static String compactDiagnostic(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "<empty>";
        String compact = raw.trim().replaceAll("\\s+", " ");
        return compact.length() <= 160
                ? compact : compact.substring(0, 157) + "...";
    }

    public static final class DiLink5AccObservation {
        public final Boolean accOn;
        public final boolean strong;
        public final String source;
        public final String detail;

        private DiLink5AccObservation(
                Boolean accOn, boolean strong,
                String source, String detail) {
            this.accOn = accOn;
            this.strong = strong;
            this.source = source == null ? "unknown" : source;
            this.detail = detail == null ? "" : detail;
        }

        static DiLink5AccObservation strong(
                boolean accOn, String source, String detail) {
            return new DiLink5AccObservation(
                    Boolean.valueOf(accOn), true, source, detail);
        }

        static DiLink5AccObservation weakOn(
                String source, String detail) {
            return new DiLink5AccObservation(
                    Boolean.TRUE, false, source, detail);
        }

        static DiLink5AccObservation unknown(
                String source, String detail) {
            return new DiLink5AccObservation(
                    null, false, source, detail);
        }

        String summary() {
            return "state="
                    + (accOn == null ? "UNKNOWN"
                            : accOn.booleanValue() ? "ON" : "OFF")
                    + ", confidence=" + (strong ? "strong" : "weak")
                    + ", source=" + source
                    + (detail.isEmpty() ? "" : ", raw={" + detail + "}");
        }
    }

    /**
     * ACC-OFF admission dampener for the DiLink 5 car_service power-mode dump
     * source. Returns {@code TRUE} immediately for an ON reading (and resets
     * the OFF streak), {@code FALSE} once an OFF reading has been confirmed
     * {@link #DILINK5_DUMP_OFF_CONFIRMATIONS} times inside
     * {@link #DILINK5_DUMP_OFF_WINDOW_MS} with observations spaced at least
     * {@link #DILINK5_DUMP_OFF_MIN_SPACING_MS} apart, and {@code null} while
     * an OFF streak is still pending confirmation (callers treat null as
     * indeterminate and preserve prior state).
     */
    static Boolean admitDiLink5DumpPowerReading(boolean isOn, long nowElapsedMs) {
        synchronized (DILINK5_DUMP_ADMISSION_LOCK) {
            if (isOn) {
                diLink5DumpOffStreak = 0;
                return Boolean.TRUE;
            }
            if (diLink5DumpOffStreak >= DILINK5_DUMP_OFF_CONFIRMATIONS) {
                // Already confirmed — a steadily parked car keeps reading OFF
                // until a genuine ON observation resets the streak. The
                // confirmation window only constrains the UNCONFIRMED phase.
                return Boolean.FALSE;
            }
            if (diLink5DumpOffStreak > 0
                    && nowElapsedMs - diLink5DumpOffFirstAtElapsedMs
                            > DILINK5_DUMP_OFF_WINDOW_MS) {
                // Partial streak went stale — this OFF starts a new streak.
                diLink5DumpOffStreak = 0;
            }
            if (diLink5DumpOffStreak == 0) {
                diLink5DumpOffStreak = 1;
                diLink5DumpOffFirstAtElapsedMs = nowElapsedMs;
                diLink5DumpOffLastCountedAtElapsedMs = nowElapsedMs;
                return null;
            }
            if (nowElapsedMs - diLink5DumpOffLastCountedAtElapsedMs
                    < DILINK5_DUMP_OFF_MIN_SPACING_MS) {
                // Same observation glitch re-read by a retry loop — do not
                // double-count, keep waiting for an independent confirmation.
                return null;
            }
            diLink5DumpOffStreak++;
            diLink5DumpOffLastCountedAtElapsedMs = nowElapsedMs;
            return diLink5DumpOffStreak >= DILINK5_DUMP_OFF_CONFIRMATIONS
                    ? Boolean.FALSE : null;
        }
    }

    /** Test hook: clears the dump-source ACC-OFF admission streak. */
    static void resetDiLink5DumpAdmissionForTest() {
        synchronized (DILINK5_DUMP_ADMISSION_LOCK) {
            diLink5DumpOffStreak = 0;
            diLink5DumpOffFirstAtElapsedMs = 0L;
            diLink5DumpOffLastCountedAtElapsedMs = 0L;
        }
    }

    private static boolean publishDiLink5State(boolean isOn, String source) {
        synchronized (AccMonitor.class) {
            accOn = isOn;
            inSentryMode = !isOn;
            lastProbeTrustworthy = true;
            accOnAuthoritative = true;
            accStateUpdatedAtElapsedMs = android.os.SystemClock.elapsedRealtime();
            if (isOn) lastAccOnElapsedMs = accStateUpdatedAtElapsedMs;
        }
        notifyAccEdge(isOn);
        CameraDaemon.log("AccMonitor [DiLink5]: " + source + " -> accOn=" + isOn);
        return !isOn;
    }

    static int classifyDiLink5PowerMode(String raw) {
        int kind = classifyDiLink5PowerModeKind(raw);
        if (kind == DILINK5_POWER_MODE_UNKNOWN) return -1;
        return kind == DILINK5_POWER_MODE_OFF ? 0 : 1;
    }

    static int classifyDiLink5PowerModeKind(String raw) {
        if (raw == null || raw.trim().isEmpty()) return -1;
        String value = raw.toLowerCase(java.util.Locale.US)
                .replace('_', ' ')
                .replace('-', ' ');
        if (value.contains("standby") || value.contains("sleep")
                || value.contains("pre startup") || value.contains("tod")
                || value.contains("powermode off")
                || java.util.regex.Pattern.compile("\\bstr\\b").matcher(value).find()
                || hasPowerModeCode(value, 0) || hasPowerModeCode(value, 1)
                || hasPowerModeCode(value, 4)
                || hasPowerModeCode(value, 5) || hasPowerModeCode(value, 8)
                || hasPowerModeCode(value, 9) || hasPowerModeCode(value, 12)) {
            return DILINK5_POWER_MODE_OFF;
        }
        if (value.contains("startup") || hasPowerModeCode(value, 2)) {
            return DILINK5_POWER_MODE_STARTUP;
        }
        if (value.contains("display on") || value.contains("degraded")
                || hasPowerModeCode(value, 3)
                || hasPowerModeCode(value, 10)) {
            return DILINK5_POWER_MODE_IVI_AWAKE;
        }
        return DILINK5_POWER_MODE_UNKNOWN;
    }

    private static boolean hasPowerModeCode(String value, int code) {
        return java.util.regex.Pattern
                .compile("(^|\\D)" + code + "(?:\\s*=|\\s*$)")
                .matcher(value)
                .find();
    }

    static int classifyAccAnim(String raw) {
        if (raw == null) return -1;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value == 0) return 1;
            return value > 0 ? 0 : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * @return true iff the MOST RECENT {@link #probeAccState} call landed on a
     * clean bodywork power level (0-3). False after a sentinel/error/default
     * reading. The ACC-ON disarm watchdog gates on this so it never disarms a
     * parked session on a HAL bluff that merely defaulted to ACC-ON.
     */
    public static boolean wasLastProbeTrustworthy() {
        return lastProbeTrustworthy;
    }

    /**
     * No-op start method for backward compatibility with CameraDaemon.
     */
    public void start() {
        CameraDaemon.log("AccMonitor: passive mode (ACC detection by AccSentryDaemon)");
    }

    /**
     * No-op stop method for backward compatibility.
     */
    public void stop() {
        // Nothing to stop
    }

    /**
     * Helper to read Android system properties safely via reflection.
     */
    private static String getSystemProperty(String key, String def) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = systemProperties.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static String execShell(String command) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", com.overdrive.app.util.ScratchRoot.remap(command)});
            if (!process.waitFor(1500L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "";
            }
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            reader.close();
        } catch (Throwable ignored) {
            if (process != null) process.destroy();
        }
        return output.toString();
    }
}
