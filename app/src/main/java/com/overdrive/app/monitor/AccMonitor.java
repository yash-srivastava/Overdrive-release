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

    public interface AccStateListener {
        void onAccStateChanged(boolean isAccOn);
    }
    private static final java.util.List<AccStateListener> sListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addListener(AccStateListener listener) {
        if (listener != null && !sListeners.contains(listener)) {
            sListeners.add(listener);
        }
    }

    public static void removeListener(AccStateListener listener) {
        if (listener != null) {
            sListeners.remove(listener);
        }
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

    /**
     * Called by SurveillanceEngine IPC when AccSentryDaemon sends ACC state.
     */
    public static void setAccState(boolean isAccOn) {
        accOn = isAccOn;
        inSentryMode = !isAccOn;
        // First IPC marks the state authoritative; stays authoritative for
        // the rest of the process lifetime (subsequent IPCs just refresh
        // the value).
        accOnAuthoritative = true;
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
        if (lastEdgeState == (isAccOn ? 1 : 0)) return;  // no real transition
        lastEdgeState = isAccOn ? 1 : 0;

        for (AccStateListener l : sListeners) {
            try {
                l.onAccStateChanged(isAccOn);
            } catch (Throwable t) {
                CameraDaemon.log("notifyAccEdge listener failed: " + t.getMessage());
            }
        }

        if (!isAccOn) {
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
            // ACC-OFF: tear down the head-unit cluster MIRROR FIRST — BEFORE the OEM
            // projection close below. ORDER IS LOAD-BEARING: the mirror owns its OWN
            // SurfaceFlinger virtual display that reads the fission cluster layerStack (the
            // SOURCE) and outputs into a head-unit SurfaceControl layer. If the OEM
            // projection close destroys the fission source display while our virtual display
            // is still bound + compositing (and the head-unit panel is power-gating at
            // ACC-off), SurfaceFlinger faults natively and the crash cascades to kill BOTH
            // the daemon and the app until the next ACC-on. forceCloseIfActive is
            // SYNCHRONOUS (awaits the unbind+destroy) so the mirror's VD is fully gone
            // before we touch the source. No-op if the mirror was never started.
            try {
                // Detach the view-into-Surface mirror FIRST (unbind its SF display before the
                // fission source closes — same load-bearing ordering as the legacy mirror).
                com.overdrive.app.surveillance.ClusterViewMirrorService.forceDetachIfActive("acc-off");
                com.overdrive.app.surveillance.ClusterMirrorController.forceCloseIfActive("acc-off");
            } catch (Throwable t) {
                CameraDaemon.log("notifyAccEdge ACC-off cluster mirror stop failed: " + t.getMessage());
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
        try {
            // ACC-ON: at most ONE cluster takeover can auto-start (the cluster is a single
            // surface). Read BOTH auto-start settings from a SINGLE config snapshot:
            //   navMap.autoProjectCluster      → auto-project the RoadSense map, and
            //   projection.autoStartOnAcc      → auto-cast projection.autoStartPackage.
            // The two toggles are mutually exclusive at the WRITE layer (each UI clears the
            // sibling), but a client race / OTA merge could leave both true — so we also
            // enforce a deterministic tiebreak HERE: the map wins (established feature, and
            // navigation is the safer thing to surface on the gauges).
            org.json.JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.forceReload();
            org.json.JSONObject nav = cfg.optJSONObject("navMap");
            boolean mapAuto = nav != null && nav.optBoolean("autoProjectCluster", false);
            org.json.JSONObject proj = cfg.optJSONObject("projection");
            boolean projAuto = proj != null && proj.optBoolean("autoStartOnAcc", false);
            String projPkg = proj != null ? proj.optString("autoStartPackage", "") : "";
            if (mapAuto && projAuto) {
                CameraDaemon.log("ACC-on edge: BOTH cluster auto-starts enabled "
                        + "(mutual-exclusion violated) — map wins, skipping projection auto-cast");
                projAuto = false;
            }
            if (mapAuto) {
                CameraDaemon.log("ACC-on edge: auto-projecting map to cluster");
                com.overdrive.app.navmap.ClusterMapProjector.start();
            } else if (projAuto && projPkg != null && !projPkg.isEmpty()) {
                CameraDaemon.log("ACC-on edge: auto-casting projection app " + projPkg + " to cluster");
                // Cast ONLY — the head-unit mirror is app-foreground-only (needs a resumed
                // ProjectionFragment + live box geometry), so it is NOT started here.
                com.overdrive.app.launcher.ClusterCast.start(projPkg);
            }
        } catch (Throwable t) {
            CameraDaemon.log("notifyAccEdge auto-start check failed: " + t.getMessage());
        }
    }

    /**
     * Reads ACC state directly from BYDAutoBodyworkDevice hardware.
     * No dependency on AccSentryDaemon or file persistence.
     * 
     * @param context Android context for BYD device API
     * @return true if ACC is OFF (sentry mode should be active), false if ACC is ON or unknown
     */
    private static volatile long lastDiLink5PowerDumpMs = 0;
    private static final long DILINK5_POWER_DUMP_THROTTLE_MS = 3000L;

    public static boolean probeAccState(android.content.Context context) {
        // 1. DI-LINK 5.0 (Android 11 Automotive / SA8155P) ACC PROBE
        // On DiLink 5.0, BYDAutoBodyworkDevice is virtualized/missing or returns sentinel 4/255.
        // We probe Android PowerManager/Display power state and dumpsys car_service PowerMode (throttled).
        if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
            try {
                // 1. Check Display Interactive State first (zero-fork Binder call). Display OFF = Definitely Sleep/Parked
                if (context != null) {
                    android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(android.content.Context.POWER_SERVICE);
                    if (pm != null && !pm.isInteractive()) {
                        accOn = false;
                        inSentryMode = true;
                        lastProbeTrustworthy = true;
                        accOnAuthoritative = true;
                        notifyAccEdge(false);
                        CameraDaemon.log("AccMonitor [DiLink5]: Display is OFF (isInteractive=false) -> accOn=false, sentryMode=true");
                        return true;
                    }
                }

                // 2. Throttled check for Automotive BYD PowerMode enum (Standby=4, Sleep=8, Str=5, Off=0, Pre StartUp=1 vs StartUp=2, DisPlay on=10)
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastDiLink5PowerDumpMs < DILINK5_POWER_DUMP_THROTTLE_MS && lastProbeTrustworthy) {
                    return !accOn;
                }
                lastDiLink5PowerDumpMs = now;

                String carServicePower = execShell("dumpsys car_service 2>/dev/null | grep -i 'Power Mute State' -A 3 | grep 'current' | head -1");
                if (!carServicePower.isEmpty()) {
                    if (carServicePower.contains("4=PowerMode Standby") || carServicePower.contains("8=PowerMode Sleep") ||
                        carServicePower.contains("5=PowerMode Str") || carServicePower.contains("0=PowerMode Off") ||
                        carServicePower.contains("1=PowerMode Pre StartUp") || carServicePower.contains("12=PowerMode Tod") ||
                        carServicePower.contains("9=PowerMode Str Suspending")) {
                        accOn = false;
                        inSentryMode = true;
                        lastProbeTrustworthy = true;
                        accOnAuthoritative = true;
                        notifyAccEdge(false);
                        CameraDaemon.log("AccMonitor [DiLink5]: Vehicle PowerMode is STANDBY/OFF (" + carServicePower.trim() + ") -> accOn=false, sentryMode=true");
                        return true;
                    } else if (carServicePower.contains("2=PowerMode StartUp") ||
                               carServicePower.contains("10=PowerMode DisPlay on") || carServicePower.contains("3=PowerMode Degraded")) {
                        accOn = true;
                        inSentryMode = false;
                        lastProbeTrustworthy = true;
                        accOnAuthoritative = true;
                        notifyAccEdge(true);
                        CameraDaemon.log("AccMonitor [DiLink5]: Vehicle PowerMode is ACTIVE/READY (" + carServicePower.trim() + ") -> accOn=true, sentryMode=false");
                        return false;
                    }
                }

                // 3. Check Lock State (Vehicle Locked = ACC OFF)
                com.overdrive.app.byd.BydDataCollector collector = com.overdrive.app.byd.BydDataCollector.getInstance();
                if (collector != null) {
                    com.overdrive.app.byd.BydVehicleData vd = collector.getData();
                    if (vd != null && vd.doorLockStatus != null && vd.doorLockStatus.length > 0) {
                        // In BYD doorLockStatus: 1 = LOCKED
                        if (vd.doorLockStatus[0] == 1) {
                            accOn = false;
                            inSentryMode = true;
                            lastProbeTrustworthy = true;
                            accOnAuthoritative = true;
                            notifyAccEdge(false);
                            CameraDaemon.log("AccMonitor [DiLink5]: Vehicle is LOCKED -> accOn=false, sentryMode=true");
                            return true;
                        }
                    }

                    // 4. Check Vehicle Active Telemetry ONLY if not parked (Gear != P and Speed >= 3 km/h)
                    if (vd != null) {
                        if (vd.gearMode > com.overdrive.app.monitor.GearMonitor.GEAR_P && vd.gearMode <= com.overdrive.app.monitor.GearMonitor.GEAR_S) {
                            accOn = true;
                            inSentryMode = false;
                            lastProbeTrustworthy = true;
                            accOnAuthoritative = true;
                            notifyAccEdge(true);
                            CameraDaemon.log("AccMonitor [DiLink5]: Vehicle is IN GEAR (" + vd.gearMode + ") -> accOn=true, sentryMode=false");
                            return false;
                        }
                        if (vd.gearMode != com.overdrive.app.monitor.GearMonitor.GEAR_P
                                && vd.speedKmh >= 3.0f && vd.speedKmh != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE) {
                            accOn = true;
                            inSentryMode = false;
                            lastProbeTrustworthy = true;
                            accOnAuthoritative = true;
                            notifyAccEdge(true);
                            CameraDaemon.log("AccMonitor [DiLink5]: Vehicle is MOVING in non-P gear (speed=" + vd.speedKmh + " km/h) -> accOn=true, sentryMode=false");
                            return false;
                        }
                    }
                }

                // 5. Fallback: check sys.accanim.status if explicitly set to "1" (OFF)
                String accAnim = getSystemProperty("sys.accanim.status", "");
                if ("1".equals(accAnim)) {
                    accOn = false;
                    inSentryMode = true;
                    lastProbeTrustworthy = true;
                    accOnAuthoritative = true;
                    notifyAccEdge(false);
                    CameraDaemon.log("AccMonitor [DiLink5]: sys.accanim.status=1 -> accOn=false, sentryMode=true");
                    return true;
                } else if ("0".equals(accAnim)) {
                    accOn = true;
                    inSentryMode = false;
                    lastProbeTrustworthy = true;
                    accOnAuthoritative = true;
                    notifyAccEdge(true);
                    CameraDaemon.log("AccMonitor [DiLink5]: sys.accanim.status=0 -> accOn=true, sentryMode=false");
                    return false;
                }
            } catch (Throwable t) {
                CameraDaemon.log("AccMonitor [DiLink5]: power probe error: " + t.getMessage());
            }
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
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            reader.close();
            process.waitFor();
        } catch (Throwable ignored) {}
        return output.toString();
    }
}
