package com.overdrive.app.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Regression contracts for the DiLink 3 parked-wake fixes. These assertions
 * intentionally protect the field-critical recovery paths while preventing
 * uncertain probes or panel darkening from turning into visible full wakes.
 */
public class Di3BootAnimationSafetyContractTest {

    @Test
    public void batteryCallbacksAreSerializedAndLowVoltageWakeIsEpisodeBounded()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/power/BatteryVoltageMonitorV2.java");

        String notify = method(
                source,
                "public static void notifyBatteryPowerVoltage(double voltage)",
                "\n    // ── Voltage / MCU state machine");
        assertTrue(notify.contains("enqueueVoltageSample(voltage);"));
        assertFalse(notify.contains("onBatteryPowerVoltageChanged(voltage);"));

        assertTrue(source.contains("MSG_VOLTAGE_SAMPLE"));
        assertTrue(source.contains("resetSessionState();"));
        assertTrue(source.contains("lowVoltageEpisodeWakeIssued"));
        assertTrue(source.contains("pendingNonHealthyVoltage"));
        assertTrue(source.contains("nonHealthyVoltageEpoch"));
        assertTrue(source.contains("monitorLifecycleEpoch"));
        assertTrue(source.contains("AccOffReaders.getMcuStatus(appContext)"));
        assertTrue(source.contains("status == 1 || status == 10"));

        String voltageStateMachine = method(
                source,
                "private static void onBatteryPowerVoltageChanged(",
                "\n    private static boolean allowSleep");
        assertOrdered(
                voltageStateMachine,
                "if (observedNonHealthy || voltage <= SLEEP_ALLOW_VOLTAGE)",
                "handler.removeMessages(MSG_DEFERRED_MCU_SLEEP)",
                "highPowerVoltage = voltage",
                "if (allowSleep(voltage))",
                "if (shouldEvaluateWake(voltage))");

        String sleep = method(
                source,
                "private static void doMcuSleep()",
                "\n    private static boolean forceWake()");
        assertOrdered(
                sleep,
                "lastPowerVoltage <= SLEEP_ALLOW_VOLTAGE",
                "doMcuSleep: SKIPPED",
                "isDeferredSleepStillCurrent(",
                "McuPowerHal.requestMcuSleep()",
                "compensateStaleSleepWrite(");
        assertTrue(sleep.contains("expectedLifecycleEpoch"));
        assertOrdered(
                sleep,
                "boolean committed = ok || sentryOk",
                "if (!committed)",
                "isWakeupMcu = true",
                "scheduleDeferredMcuSleep()",
                "isWakeupMcu = false");
        assertTrue(source.contains(
                "monitorLifecycleEpoch.incrementAndGet();"));
        assertTrue(source.contains("ensureMonitorTickScheduled();"));
        assertTrue(source.contains(
                "public static synchronized void stopMonitorForShutdown()"));

        String compensation = method(
                source,
                "private static void compensateStaleSleepWrite(",
                "\n    private static boolean forceWake()");
        assertOrdered(
                compensation,
                "expectedLifecycleEpoch == uncompensatedTerminalSleepEpoch",
                "compensating wake suppressed",
                "McuPowerHal.requestMcuWake()",
                "McuPowerHal.requestSentryWake()",
                "running",
                "monitorLifecycleEpoch.get() == expectedLifecycleEpoch");

        String forceWake = method(
                source,
                "private static boolean forceWake()",
                "\n    // ── Handler loop");
        assertOrdered(
                forceWake,
                "handler.removeMessages(MSG_DEFERRED_MCU_SLEEP)",
                "lastWakeAttemptElapsedMs =",
                "McuPowerHal.requestMcuWake()",
                "McuPowerHal.requestSentryWake()",
                "boolean committed = ok || sentryOk",
                "isWakeupMcu = committed");
    }

    @Test
    public void avcWatchdogFailsClosedButColdOpenRecoveryRemains()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/AvcHalWarmup.java");

        assertTrue(source.contains("enum AvcProbeState"));
        assertTrue(source.contains("CONFIRMED_ABSENT"));
        assertTrue(source.contains("UNKNOWN"));
        assertTrue(source.contains("exitCode == 1"));
        assertTrue(source.contains("confirmAvcAbsent(probe)"));
        assertTrue(source.contains("probe inconclusive"));
        assertTrue(source.contains("AVC_POST_LAUNCH_VERIFY_DELAY_MS"));
        assertTrue(source.contains("AVC_RELAUNCH_BACKOFF_MAX_MS"));
        assertTrue(source.contains("keepAliveLaunchAwaitingStableTick"));

        String warmup = method(
                source,
                "public boolean warmupAndWait()",
                "\n    // ==================== Keep-Alive Watchdog");
        assertOrdered(
                warmup,
                "AvcProbeResult beforeLaunch = probeAvcProcess();",
                "beforeLaunch.state != AvcProbeState.RUNNING",
                "launched = launchAvc();",
                "Thread.sleep(HAL_WARMUP_DELAY_MS)",
                "AvcProbeResult postWarmup = probeAvcProcess()");

        String watchdog = method(
                source,
                "private void startKeepAliveWorkerLocked",
                "\n    private void scheduleKeepAliveHandoffLocked");
        assertOrdered(
                watchdog,
                "probe.state == AvcProbeState.UNKNOWN",
                "not launching",
                "confirmAvcAbsent(probe)",
                "nextKeepAliveLaunchAllowedElapsedMs",
                "boolean launched = launchAvc();",
                "probeAvcAfterLaunchDelay()",
                "deferNextKeepAliveLaunch()");

        String forceRestart = method(
                source,
                "private boolean forceRestartAvc(\n"
                        + "            java.util.function.BooleanSupplier stillRequired)",
                "\n    /**\n     * Static counterpart");
        assertTrue(forceRestart.contains(
                "android.os.SystemClock.elapsedRealtime()"));
        assertFalse(forceRestart.contains("System.currentTimeMillis()"));

        String forcedRestart = method(
                source,
                "private boolean forceRestartAvc(\n"
                        + "            java.util.function.BooleanSupplier stillRequired)",
                "\n    /**\n     * Static counterpart");
        assertOrdered(
                forcedRestart,
                "if (isDiLink5Selected()) return false;",
                "if (isDilink4)",
                "isWarmupStillRequired(stillRequired)",
                "\"am force-stop com.byd.avc\"",
                "suppressing recovery relaunch",
                "launchAvc();  // restart",
                "return true;");
    }

    @Test
    public void keepUsbPanelPathNeverUsesDeviceSleepAndAwaitsRedarken()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");

        String generationOverload = method(
                source,
                "private static boolean setBacklightState(\n"
                        + "            long generation, boolean on)",
                "\n    private static boolean setBacklightState(\n"
                        + "            boolean on, ShellOwnership ownership)");
        assertTrue(generationOverload.contains(
                "&& !isKeepUsbPowerOnAccOff()"));

        String fallback = method(
                source,
                "private static boolean setBacklightState(\n"
                        + "            boolean on,\n"
                        + "            ShellOwnership ownership,\n"
                        + "            boolean allowDeviceSleep)",
                "\n    /**\n     * Enforces strict power management state.");
        assertOrdered(
                fallback,
                "if (!allowDeviceSleep)",
                "KEYCODE_SLEEP suppressed",
                "\"input keyevent 223\"");

        String await = method(
                source,
                "private static void requestAndAwaitPanelDarkAfterWake",
                "\n    private static boolean isSentryTransitionGenerationCurrent");
        assertTrue(await.contains("panelReconciler.requestReapply"));
        assertTrue(await.contains("panelReconciler.awaitApplied"));
        assertTrue(source.contains("Periodic keepalive wake"));

        String userActivity = method(
                source,
                "private static boolean injectFakeUserActivity",
                "\n    private static void immediateWakeUpMcu()");
        assertOrdered(
                userActivity,
                "panelReconciler.isDesired(",
                "userActivity(long, noChangeLights=true) called ",
                "resolvePmUserActivity1Arg()");
        assertTrue(userActivity.contains(
                "skipping one-arg call"));

        String lowBatteryWake = method(
                source,
                "private static boolean applyLowBatteryWake",
                "\n    private static void requestLatestSurveillanceIntentReconciliation");
        assertOrdered(
                lowBatteryWake,
                "long episodeId = currentLowBatteryWakeEpisode(generation)",
                "runBoundedHardwareBoolean(",
                "!isLowBatterySystemWakeCommitted(",
                "markLowBatterySystemWakeCommitted(",
                "systemWakeCommittedThisAttempt.set(true)",
                "if (systemWakeCommittedThisAttempt.get())",
                "requestAndAwaitPanelDarkAfterWake(");
        assertTrue(source.contains(
                "lowBatteryWakeReconciler.request("));
        assertFalse(source.contains(
                "lowBatteryWakeReconciler.requestReapply("));
        assertTrue(source.contains(
                "LOW_BATTERY_WAKE_RETRY_INTERVAL_MS = 60_000L"));
        String lowBatteryReconciler = method(
                source,
                "private static final LatestBooleanReconciler lowBatteryWakeReconciler",
                "\n    private static final LatestBooleanReconciler\n"
                        + "            surveillanceIntentReconciler");
        assertOrdered(
                lowBatteryReconciler,
                "boolean applied = applyLowBatteryWake(generation)",
                "currentLowBatteryWakeEpisode(generation)",
                "sleepForLowBatteryWake(",
                "LOW_BATTERY_WAKE_RETRY_INTERVAL_MS",
                "return !isLowBatteryWakeCurrent(generation)");

        String transition = method(
                source,
                "private static long beginSentryTransition(",
                "\n    private static void requestTransitionReconciliation");
        assertOrdered(
                transition,
                "generation = sentryTransitionGeneration.incrementAndGet()",
                "resetLowBatteryWakeEpisode()",
                "lowBatteryWakeReconciler.request(generation, false)",
                "latestSentryTransition = new SentryTransitionState(");
        assertTrue(source.contains(
                "snapshotSentryTransitionForBatteryCallback();"));
        String batterySnapshot = method(
                source,
                "private static SentryTransitionState\n"
                        + "            snapshotSentryTransitionForBatteryCallback()",
                "\n    private static final Object lowBatteryWakeEpisodeLock");
        assertOrdered(
                batterySnapshot,
                "synchronized (sentryTransitionLock)",
                "return latestSentryTransition");

        // The periodic AP wake remains load-bearing for Di3 USB VBUS.
        assertTrue(source.contains("MCU_REWAKE_EVERY_TICKS = 48"));
        assertTrue(source.contains("performSystemWakeUp(\n"
                + "                                            transitionGeneration)"));
    }

    @Test
    public void staleLegacyWarmupsCannotOverlapOrLaunchAfterCancellation()
            throws IOException {
        String manager = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/recording/"
                        + "RecordingModeManager.java");
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");

        assertTrue(manager.contains(
                "WARMUP_STUCK_THRESHOLD_MS = 90_000L"));
        String stuckGuard = method(
                manager,
                "public void resyncFromHardware(String reason)",
                "\n        boolean hwAcc = queryAccStateFromHardware();");
        assertOrdered(
                stuckGuard,
                "android.os.SystemClock.elapsedRealtime()",
                "CameraDaemon.requestProcessRestartPreservingTrip(",
                "keeping the single-flight",
                "gate latched");
        assertFalse(stuckGuard.contains("warmupInFlight.set(false)"));

        String activation = method(
                manager,
                "private void activateModeWithWarmup(final Mode mode, "
                        + "final String reason, final boolean force)",
                "\n    // 8s gives");
        assertOrdered(
                activation,
                "final long cameraStartEpoch",
                "CameraDaemon.isCameraStartEpochCurrent(",
                "Skipping stale camera warmup",
                "avcWarmup.warmupAndWait(",
                "() -> !shuttingDown",
                "CameraDaemon.isCameraStartEpochCurrent(",
                "&& accIsOn",
                "&& currentMode == mode");

        String cameraCommand = method(
                daemon,
                "public static void startCamera(int viewId, boolean enableStreaming, "
                        + "boolean viewOnly)",
                "\n    /**\n     * Internal: starts the GPU pipeline");
        assertOrdered(
                cameraCommand,
                "isCameraStartEpochCurrent(startGeneration)",
                "avcHalWarmup.warmupAndWait(",
                "() -> isCameraStartEpochCurrent(",
                "startPipelineInternal(");
        assertTrue(cameraCommand.contains("warmupWorker.setDaemon(true)"));
    }

    @Test
    public void sdFastRecoveryRequiresMountedEvidenceAndDebouncedAbsence()
            throws IOException {
        String storage = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/storage/StorageManager.java");
        String sentry = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");

        assertTrue(storage.contains("overdrive_sd_mounted_lease"));
        assertTrue(storage.contains("SD_MOUNTED_LEASE_MS = 60_000L"));
        assertTrue(storage.contains("publishSdMountedLease();"));
        assertTrue(storage.contains(
                "boolean sdMountedThisTick =\n"
                        + "                        watchSd && isSdCardLikelyMounted();"));

        assertTrue(sentry.contains("SdMountedLeaseEvidence.FRESH_MOUNT"));
        assertTrue(sentry.contains(
                "SD_RECOVERY_FALSE_CONFIRM_SAMPLES = 3"));
        assertTrue(sentry.contains("mountedHeartbeatAdvanced"));
        assertTrue(sentry.contains(
                ">= SD_RECOVERY_FALSE_CONFIRM_SAMPLES"));
        assertTrue(sentry.contains("SdMountedLeaseEvidence.UNAVAILABLE"));
        String fallback = method(
                sentry,
                "} else if (sdMountedLease.evidence\n"
                        + "                            == SdMountedLeaseEvidence.UNAVAILABLE)",
                "\n                    } else {\n"
                        + "                        // A current publisher explicitly");
        assertTrue(fallback.contains(
                ">= SD_RECOVERY_FALSE_CONFIRM_SAMPLES"));
        assertTrue(sentry.contains(
                "sdRecoveryBackoffTicks =\n"
                        + "                                        SD_RECOVERY_BASE_BACKOFF_TICKS;"));
        assertTrue(sentry.contains("tick % MCU_REWAKE_EVERY_TICKS == 0"));
    }

    @Test
    public void legacyCameraOpenTimeoutCannotSpawnOverlappingReopenLoop()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String restart = method(
                source,
                "private void restartCameraAfterError()",
                "\n    /**\n     * FORTIFY FIX");

        assertOrdered(
                restart,
                "boolean releaseRestartGate = true",
                "boolean legacyOpenAttempt =",
                "!USE_DILINK4_AVM_PATH && !USE_DILINK5_QCARCAM_PATH",
                "GL_THREAD_WARMUP_TIMEOUT_MS",
                "if (legacyOpenAttempt)",
                "releaseRestartGate = false",
                "CameraDaemon.requestUrgentCameraReleaseRestart(",
                "if (releaseRestartGate)",
                "restartInProgress.set(false)");
        assertTrue(restart.contains(
                "Preserve the existing DiLink 4 behavior exactly"));
        assertTrue(restart.contains(
                "legacyOpenAttempt\n"
                        + "                    ? android.os.SystemClock.elapsedRealtime()"));

        String accOnReopen = method(
                source,
                "public void reopenCamera(long maxWaitMs)",
                "\n    /**\n     * Legacy-only bounded AVMCamera open");
        assertOrdered(
                accOnReopen,
                "final boolean legacyOpenAttempt =",
                "!USE_DILINK4_AVM_PATH && !USE_DILINK5_QCARCAM_PATH",
                "boolean releaseRestartGate = true",
                "openLegacyCameraWithHardTimeout(\n"
                        + "                        \"ACC ON camera reopen\", handoffSettle)",
                "releaseRestartGate = false",
                "openLegacyCameraWithHardTimeout(",
                "\"ACC ON camera reopen retry\"",
                "if (releaseRestartGate)",
                "restartInProgress.set(false)");

        String boundedLegacyOpen = method(
                source,
                "private boolean openLegacyCameraWithHardTimeout(String phase,",
                "\n    /**\n     * Sleeps for");
        assertOrdered(
                boundedLegacyOpen,
                "AvcHalWarmup.warmupBeforeColdOpen(",
                "() -> running",
                "&& restartInProgress.get()",
                "&& !CameraDaemon.isProcessRestartPending()",
                "Thread cameraOpenThread = new Thread",
                "startCamera()",
                "Object partial = cameraObj",
                "cameraObj = null",
                "closeCameraForPath(partial)",
                "android.os.SystemClock.elapsedRealtime()",
                "CameraDaemon.requestUrgentCameraReleaseRestart(",
                "return false");
        assertTrue(boundedLegacyOpen.contains(
                "final long hardTimeoutMs = GL_THREAD_WARMUP_TIMEOUT_MS"));
        assertTrue(boundedLegacyOpen.contains(
                "cameraOpenThread.setDaemon(true)"));

        String oem = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/OemDashcamPipeline.java");
        String oemOpen = method(
                oem,
                "private void openCameraAndAttach(",
                "\n    private static boolean isLegacyAvmMode()");
        assertOrdered(
                oemOpen,
                "AvcHalWarmup.warmupBeforeColdOpen(",
                "() -> running.get()",
                "isStartAdmissionCurrent(",
                "requireStartCurrent(",
                "\"post-warmup camera acquisition\"",
                "isLegacyAvmMode()",
                "openLegacyCameraAndAttachWithHardTimeout()",
                "openCameraAndAttachAfterWarmup()");

        String boundedOemOpen = method(
                oem,
                "private void openLegacyCameraAndAttachWithHardTimeout()",
                "\n    private void armTerminalLegacyOpenRestart");
        assertOrdered(
                boundedOemOpen,
                "legacyCameraOpenInFlight.set(true)",
                "Thread openThread = new Thread",
                "openCameraAndAttachAfterWarmup()",
                "openThread.setDaemon(true)",
                "SystemClock.elapsedRealtime()",
                "LEGACY_CAMERA_OPEN_HARD_TIMEOUT_MS",
                "armTerminalLegacyOpenRestart(");
        assertTrue(oem.contains(
                "legacyCameraOpenTerminalRestart.get()"));
        assertTrue(oem.contains(
                "requestUrgentCameraReleaseRestart(reason)"));

        String oemStop = method(
                oem,
                "private void stopInternal(boolean fromStartFailure)",
                "\n    /**\n     * Reattach pano's stream sink");
        assertTrue(oemStop.contains(
                "legacyCameraOpenTerminalRestart.get()"));

        String windshieldOpen = method(
                source,
                "private void startLegacyWindshieldCameraWithHardTimeout()",
                "\n    private boolean isLegacyWindshieldRequestCurrent(");
        assertOrdered(
                windshieldOpen,
                "legacyWindshieldCameraTerminalRestart.get()",
                "createWindshieldImageReader()",
                "legacyWindshieldCameraLifecycleInFlight",
                ".compareAndSet(false, true)",
                "Thread worker = new Thread",
                "getDeclaredMethod(\"open\")",
                "getDeclaredMethod(\n"
                        + "                        \"addPreviewSurface\"",
                "getDeclaredMethod(\"startPreview\")",
                "isLegacyWindshieldRequestCurrent(",
                "worker.setDaemon(true)",
                "awaitLegacyWindshieldWorker(",
                "windshieldCameraObj = completed");

        String windshieldWait = method(
                source,
                "private boolean awaitLegacyWindshieldWorker(",
                "\n    private boolean closeLegacyWindshieldCameraWithHardTimeout(");
        assertOrdered(
                windshieldWait,
                "android.os.SystemClock.elapsedRealtime()",
                "GL_THREAD_WARMUP_TIMEOUT_MS",
                "lastGlThreadHeartbeat",
                "worker.join(",
                "armLegacyWindshieldTerminalRestart(");

        String windshieldStop = method(
                source,
                "private boolean stopWindshieldCameraOnGlThread()",
                "\n    /**\n     * DiLink 5 never yields");
        assertOrdered(
                windshieldStop,
                "legacyWindshieldCameraTerminalRestart.get()",
                "closeLegacyWindshieldCameraWithHardTimeout(",
                "windshieldCameraObj = null",
                "windshieldSurface.release()",
                "windshieldImageReader.close()");

        String oemStart = method(
                oem,
                "public void start(\n"
                        + "            long cameraStartEpoch,",
                "\n    private static void requireStartAdmissionCurrent");
        assertOrdered(
                oemStart,
                "requireStartAdmissionCurrent(",
                "\"start admission\"",
                "initEglAndEncoder()",
                "\"post-EGL setup\"",
                "openCameraAndAttach(",
                "\"post-camera open\"",
                "installFrameCallback()",
                "\"pre-render loop\"",
                "startRenderLoop()");

        String concurrentProbe = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/ConcurrentAvmProbe.java");
        String runProbe = method(
                concurrentProbe,
                "public static int runIfNeeded()",
                "\n    /**\n     * Reset the sticky result");
        assertOrdered(
                runProbe,
                "concurrentAvmProbeEnabled",
                "isAccConfirmedOffForCameraProbe()",
                "concurrentAvmProbeEnabled\", false",
                "int result = probe(panoId, oemId)");

        String boundedProbeOpen = method(
                concurrentProbe,
                "private static Object openAvmCamera(Class<?> avm, int id)",
                "\n    private static void armTerminalOpenRestart");
        assertOrdered(
                boundedProbeOpen,
                "Thread worker = new Thread",
                "worker.setDaemon(true)",
                "android.os.SystemClock.elapsedRealtime()",
                "CAMERA_OPEN_HARD_TIMEOUT_MS",
                "armTerminalOpenRestart(");
        assertTrue(concurrentProbe.contains(
                "requestUrgentCameraReleaseRestart(reason)"));
        assertOrdered(
                concurrentProbe,
                "tryStopClose(oemCam);",
                "tryStopClose(panoCam);");

        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String imageReaderProbe = method(
                daemon,
                "private static boolean runImageReaderProbeIfRequested()",
                "\n    // ==================== LOGGING");
        assertOrdered(
                imageReaderProbe,
                "sentinel.exists()",
                "sentinel.delete()",
                "isProcessRestartPending()",
                "Thread worker = new Thread",
                "worker.setDaemon(true)",
                "android.os.SystemClock.elapsedRealtime()",
                "IMAGE_READER_PROBE_HARD_TIMEOUT_MS",
                "requestUrgentCameraReleaseRestart(reason)",
                "return false");
        assertTrue(daemon.contains(
                "if (!runImageReaderProbeIfRequested()) {\n"
                        + "            return;\n"
                        + "        }\n\n"
                        + "        // Initialize surveillance module"));

        String parkedGate = method(
                daemon,
                "public static boolean isAccConfirmedOffForCameraProbe()",
                "\n    /**\n     * Deferred lock/schedule effects");
        assertOrdered(
                parkedGate,
                "captureAccObservationGeneration()",
                "probeAccStateWithBackoff(\"destructive-camera-probe\")",
                "probe.trustworthy",
                "probe.accIsOff",
                "isAccObservationCurrent(observationGeneration)");

        String coldOpen = method(
                source,
                "private void startCamera() throws Exception",
                "\n    /**\n     * Opens camera via AVMCamera reflection");
        assertOrdered(
                coldOpen,
                "final boolean legacyOpen =",
                "!USE_DILINK4_AVM_PATH && !USE_DILINK5_QCARCAM_PATH",
                "legacyCameraOpenInFlight.set(true)",
                "startCameraViaAvmReflection(cameraId)",
                "finally",
                "legacyCameraOpenInFlight.set(false)");

        String stopFence = method(
                source,
                "private boolean awaitLegacyCameraLifecycleIdleForStop()",
                "\n    /**\n     * True while this pipeline holds");
        assertOrdered(
                stopFence,
                "legacyWindshieldCameraTerminalRestart.get()",
                "legacyCameraInitializationInFlight.get()",
                "legacyCameraOpenInFlight.get()",
                "legacyWindshieldCameraLifecycleInFlight.get()",
                "LEGACY_OPEN_STOP_GRACE_MS",
                "boolean cameraOwnershipIndeterminate",
                "CameraDaemon.requestUrgentCameraReleaseRestart(",
                "CameraDaemon.requestProcessRestartPreservingTrip(",
                "return false");
        String stop = method(
                source,
                "public boolean stop()",
                "\n    // Sticky stop verdict");
        assertOrdered(
                stop,
                "stopYieldPoller()",
                "awaitLegacyCameraLifecycleIdleForStop()",
                "stopVerdictWedged = true",
                "closeCameraForPath(cameraObj)");
    }

    @Test
    public void legacyLowSocShutdownDoesNotRelightPanelBeforeSleep()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/power/SocCutoffMonitor.java");
        String shutdown = method(
                source,
                "private static void performShutdown(double finalSoc)",
                "\n    // ── PowerManager.goToSleep reflection");

        assertOrdered(
                shutdown,
                "BatteryVoltageMonitorV2.stopMonitorForShutdown()",
                "boolean stealthPanelPlatform = false",
                ".isDiLink4Selected()",
                ".isSelected()",
                "if (stealthPanelPlatform)",
                "StealthPanel.turnOn(appContext)",
                "powerManagerGoToSleep()");
        assertTrue(shutdown.contains("Legacy Di3 darkening"));
        assertTrue(shutdown.contains("uses the ordinary backlight path"));
    }

    private static String method(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue("method start not found: " + start, from >= 0);
        assertTrue("method end not found: " + end, to > from);
        return source.substring(from, to);
    }

    private static void assertOrdered(String source, String... needles) {
        int cursor = -1;
        for (String needle : needles) {
            int next = source.indexOf(needle, cursor + 1);
            assertTrue("missing or out of order: " + needle, next > cursor);
            cursor = next;
        }
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
