package com.overdrive.app.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class DiLink5VehicleBoundaryContractTest {
    @Test
    public void vehicleRequestsStayInsideTheSelectedMode() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/VehicleActuatorService.java");
        int bridge = source.indexOf("private Bundle runDiLink5Bridge(Intent request)");
        int locked = source.indexOf(
                "private Bundle runDiLink5BridgeLocked(Intent request)", bridge);
        String boundary = source.substring(bridge, locked);
        assertTrue(boundary.contains("synchronized (DILINK5_BRIDGE_LOCK)"));
        assertFalse(boundary.contains("synchronized (DiLink5Platform.class)"));
        assertTrue(boundary.contains("return runDiLink5BridgeLocked(request);"));
        assertTrue(source.contains("if (!diLink5ModeCurrent(request))"));
        assertTrue(source.contains("requireDiLink5ModeCurrent(request);"));
        assertTrue(source.contains("DILINK5_MODE_GENERATION"));

        String bridgeSource = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/VehicleActuatorBridge.java");
        assertTrue(bridgeSource.contains("if (modeGeneration == null)"));
        assertTrue(bridgeSource.contains(
                "active mode marker unavailable"));
    }

    @Test
    public void carSvcTelemetryCannotRunOutsideSelectedDiLink5()
            throws Exception {
        String telemetry = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/CarSvcTelemetry.kt");
        assertTrue(telemetry.contains(
                "fun isActive(): Boolean = DiLink5Platform.isSelected()"));
        int dump = telemetry.indexOf("private fun dumpSnapshot(): DumpSnapshot?");
        int parser = telemetry.indexOf("fun buildSearchKey", dump);
        assertTrue(dump >= 0);
        assertTrue(parser > dump);
        assertTrue(telemetry.substring(dump, parser).contains(
                "if (!isActive()) return null"));
        assertTrue(telemetry.contains(
                "\"dumpsys -t 3 car_service > $path 2>&1\""));
        assertTrue(telemetry.contains(
                "val observedAtNanos = System.nanoTime()"));
        assertTrue(telemetry.contains(
                "elapsedMs(System.nanoTime(), snapshot.observedAtNanos)"));

        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        assertTrue(collector.contains(
                "if (!isDiLink5Vehicle()) collectSpeed(b);"));
        assertTrue(collector.contains(
                "if (!isDiLink5Vehicle()) collectGearbox(b);"));
        assertTrue(collector.contains(
                "CarSvcTelemetry.readChargingObservation()"));
        assertTrue(collector.contains(
                "boolean carSvcCharging = observation.charging && !vtol;"));
        assertOrdered(
                collector,
                "public double readCurrentSpeedKmh()",
                "if (isDiLink5BridgeConsumer())",
                "CarSvcTelemetry.INSTANCE.currentResolvedSpeedKmh()");
        assertOrdered(
                collector,
                "public int readTurnNow()",
                "CarSvcTelemetry.INSTANCE.turnLightState()",
                "if (lightDevice != null)");

        String gearMonitor = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/GearMonitor.java");
        assertTrue(gearMonitor.contains("CarSvcTelemetry.INSTANCE"));
        assertTrue(gearMonitor.contains(".currentDynamics()"));
        assertTrue(gearMonitor.contains(".observedAtFromAge("));
        assertFalse(gearMonitor.contains("\"dumpsys car_service"));
        int diLink5Sample = gearMonitor.indexOf(
                "private GearSample readDiLink5GearSample()");
        int diLink5Probe = gearMonitor.indexOf(
                "private GearSample readDiLink5Probe", diLink5Sample);
        String diLink5SampleBody =
                gearMonitor.substring(diLink5Sample, diLink5Probe);
        assertTrue(diLink5SampleBody.contains(
                "readDiLink5GearObservation()"));
        assertFalse(diLink5SampleBody.contains("telemetrySource"));
        assertTrue(gearMonitor.contains(
                "DILINK5_GEAR_PROBE_THROTTLE_MS =\n"
                        + "            CACHED_GEAR_MAX_AGE_MS;"));
        assertTrue(gearMonitor.contains(
                "DILINK5_CARSVC_GEAR_MAX_AGE_MS =\n"
                        + "            com.overdrive.app.byd.CarSvcTelemetry.DUMP_TTL_MS + 1_000L;"));
        assertTrue(gearMonitor.contains(
                "cachedDiLink5ProbeMaxAgeMs =\n"
                        + "                                DILINK5_CARSVC_GEAR_MAX_AGE_MS;"));
        assertTrue(gearMonitor.contains(
                "result.statusCode\n"
                        + "                                == com.byd.datasource.feature.Status.STATUS_SUCCESS"));

        String accMonitor = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/AccMonitor.java");
        assertTrue(accMonitor.contains(
                "getSystemProperty(\"sys.byd.power_mode\", \"\")"));
        assertTrue(accMonitor.contains(
                "getSystemProperty(\"sys.accanim.status\", \"\")"));
        assertFalse(accMonitor.contains("\"dumpsys car_service"));

        String chargingApi = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/ChargingApiHandler.java");
        assertFalse(chargingApi.contains("applyChargingOverrides"));
    }

    @Test
    public void clusterProjectionUsesOneStrictlyGatedDiLink5Backend()
            throws Exception {
        String cast = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/"
                        + "DiLink5ClusterCast.kt");
        // The DI5 backend is gated on the CLUSTER TRANSPORT (dilink5 camera
        // mode, config override, or DiLink 5 firmware identity) — not on the
        // camera mode alone, which left DiLink 5.0 units without the QCarCam
        // library on the legacy AutoContainer protocol their firmware rejects.
        assertTrue(cast.contains(
                "if (!ClusterTransport.isDiLink5()) return false"));
        assertFalse(cast.contains("DiLink5Platform.isSelected()"));
        assertTrue(cast.contains(
                "shared_fission_bg_XDJAScreenProjection_0"));
        assertTrue(cast.contains(
                "shared_fission_bg_XDJAScreenProjection_1"));
        assertTrue(cast.contains("SessionKind { APP, MAP }"));
        assertTrue(cast.contains("fun startMap(): Boolean"));
        assertTrue(cast.contains("fun stopMap(): Boolean"));
        assertTrue(cast.contains(
                "contains(\"xdjascreenprojection\", ignoreCase = true)"));
        assertFalse(cast.contains("DI5_REMOTE_DASHBOARD"));
        assertTrue(cast.contains(
                "isClosedAaosProjectionTopology("));
        assertTrue(cast.contains(
                "BYD AAOS HDMI cluster exposes OEM navigation only"));
        assertTrue(cast.contains(
                "ClusterProjectionController.sendDiLink5ContainerInfoResult(16)"));
        assertTrue(cast.contains(
                "di5ClusterProjectionTeardownIndeterminate"));
        assertTrue(cast.contains(
                "di5ClusterProjectionTeardownBootId"));
        String markerRead = cast.substring(
                cast.indexOf("private fun readRecoveryMarker()"),
                cast.indexOf("private fun startSession("));
        assertTrue(markerRead.contains(
                "UnifiedConfigManager.readDurableConfigStrict()"));
        assertFalse(markerRead.contains("forceReload()"));
        String markedTeardown = cast.substring(
                cast.indexOf("private fun sendMarkedTeardownCommand("),
                cast.indexOf("private fun writeMarker("));
        assertOrdered(
                markedTeardown,
                "markInFlight = {",
                "writeTeardownMarker(",
                "send = {",
                ".sendDiLink5ContainerCleanupResult(opcode)",
                "clearInFlight = {");
        assertTrue(cast.contains(
                "refusing same-boot compositor restore"));
        assertTrue(markerRead.contains("strictMarkerBoolean("));
        assertTrue(markerRead.contains("strictMarkerBootId("));
        assertTrue(cast.contains("canonicalDiLink5BootIdOrNull("));
        assertTrue(cast.contains("enableAndAwaitProjectionDisplay("));
        assertTrue(cast.contains(
                "AppLauncher.launchOnDisplay(target.pkg, displayId)"));
        assertTrue(cast.contains("TASK_GUARD_INITIAL_MS = 30_000L"));
        assertTrue(cast.contains("VERIFY_ATTEMPTS = 2"));
        assertTrue(cast.contains("START_FOREGROUND_STABLE_MS = 3_000L"));
        assertTrue(cast.contains("START_FOREGROUND_OBSERVE_MS = 6_500L"));
        assertTrue(cast.contains(
                "TASK_GUARD_MAX_FOREGROUND_TAKEOVERS = 5"));
        assertTrue(cast.contains(
                "ClusterFreeformWindow.findTaskLocation("));
        assertTrue(cast.contains(
                "ClusterFreeformWindow.moveTaskToDisplay("));
        assertTrue(cast.contains(
                "ClusterFreeformWindow.focusTask("));
        assertTrue(cast.contains(
                "ClusterCast.resumedPackageOnDisplay("));
        assertTrue(cast.contains(
                "OEM projection service repeatedly reclaimed display"));
        assertTrue(cast.contains("SessionPhase.RECOVERING"));
        assertTrue(cast.contains("scheduleDeferredTaskRecovery("));
        assertTrue(cast.contains("scheduleBootRecoveryRetry("));
        assertTrue(cast.contains("recoveryRetryDelayMs("));
        assertTrue(cast.contains("AppLauncher.forceStopPackage("));
        // OEM takeover handling (2026-09-23 DiLink 5.0 Atto 3: com.byd.sr re-fronts
        // on shared _0): the reclaimed surface is remembered, the sibling shared
        // surface is tried, the survivor is learned, and both the failed-start and
        // stop paths fall back to a force-stop instead of stranding the state
        // machine in "cleanup incomplete; restart required".
        assertTrue(cast.contains("markDisplayReclaimed("));
        assertTrue(cast.contains("resolveAlternativeSharedDisplay("));
        assertTrue(cast.contains("rememberStableSharedDisplay("));
        assertTrue(cast.contains("forceStopAndConfirmAbsent("));
        assertTrue(cast.contains("recovering unresolved cleanup state before start"));
        assertTrue(cast.contains("normalized == \"com.byd.sr\""));
        // Flicker bound: a repeat OEM re-front after one correction ends the
        // startup fight immediately (no second VERIFY attempt, no force-restart)
        // and routes straight to the shared-display fallback.
        assertTrue(cast.contains("oemReclaimedAfterCorrection"));
        assertTrue(cast.contains("surface belongs to the OEM"));
        String rehome = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/AppLauncher.java");
        assertTrue(rehome.contains(
                "ClusterFreeformWindow.moveTaskToDefaultDisplayBackground(task)"));
        // Live-task truth beats the dump parser: a root task's stale
        // mLastPausedActivity reference must neither block absence confirmation
        // nor be offered as a move target.
        assertTrue(rehome.contains(
                "ClusterFreeformWindow.liveTaskIdForPackage(pkg)"));
        assertTrue(rehome.contains("isTaskStateReferenceLine(line)"));
        assertFalse(cast.contains("getRealDisplay"));
        assertTrue(cast.contains(
                "DI5 projection target was not resolved before container enable"));
        assertTrue(cast.contains(
                "using preflight-selected OEM shared projection"));
        int shellOnlyBranch = cast.indexOf("if (!appVisible) {");
        int compositorDispatch = cast.indexOf(
                "val commandResult = synchronized(stateLock) {", shellOnlyBranch);
        assertTrue(shellOnlyBranch >= 0);
        assertTrue(compositorDispatch > shellOnlyBranch);
        String shellOnlyPlan = cast.substring(shellOnlyBranch, compositorDispatch);
        assertTrue(shellOnlyPlan.contains("return configureProjectionTarget("));
        assertTrue(shellOnlyPlan.contains("appDisplayVisible = false"));
        assertTrue(shellOnlyPlan.contains(".copy(deferCompositorEnable = true)"));
        assertFalse(shellOnlyPlan.contains("sendDiLink5ContainerInfoResult(16)"));
        assertFalse(cast.contains("createVirtualDisplay("));
        assertFalse(cast.contains("TYPE_APPLICATION_OVERLAY"));
        assertFalse(cast.contains("di5ClusterSizeProfile"));
        String startupDaemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        assertOrdered(
                startupDaemon,
                ".activateConfiguredMode();",
                "final boolean leavingDiLink5 =",
                "ClusterCast.reparentStrandedCastAtBoot();",
                "ClusterProjectionController\n"
                        + "                    "
                        + ".clearStaleGateAtBootSynchronously();");

        String map = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/navmap/"
                        + "ClusterMapProjector.java");
        assertTrue(map.contains("DiLink5ClusterCast.startMap()"));
        assertTrue(map.contains("DiLink5ClusterCast.stopMap()"));
        assertTrue(map.contains("if (!acquired)"));

        String controller = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "ClusterProjectionController.java");
        int method = controller.indexOf(
                "public static DiLink5CommandResult "
                        + "sendDiLink5ContainerInfoResult(int op)");
        int next = controller.indexOf(
                "private static DiLink5EndpointResult "
                        + "sendDiLink5InfoShell", method);
        String gate = controller.substring(method, next);
        assertTrue(gate.contains("ClusterTransport.isDiLink5()"));
        assertTrue(gate.contains("isAllowedDiLink5ContainerOpcode(op)"));
        assertTrue(gate.contains("DiLink5CommandResult.REJECTED"));
        assertTrue(gate.contains("DiLink5CommandResult.INDETERMINATE"));
        assertTrue(gate.contains("DiLink5CommandResult.UNAVAILABLE"));
        assertTrue(controller.contains(
                "sendDiLink5ContainerCleanupResult(int op)"));
        assertTrue(controller.contains(
                "isAllowedDiLink5CleanupOpcode(op)"));
        assertTrue(controller.contains(
                "allowInactiveCleanup && cleanupOpcode"));
        assertTrue(controller.contains(
                "return op == OP_REFRESH"));
        assertFalse(controller.contains(
                "|| op == 29"));
        assertTrue(controller.contains(
                "isAcceptedDiLink5ContainerResult(result)"));
        assertFalse(controller.contains("DI5_BINDER_CALL_IN_FLIGHT"));
        assertFalse(controller.contains("sendDiLink5InfoBinderBlocking("));
        assertTrue(controller.contains("p.destroyForcibly();"));
        assertTrue(controller.contains(
                "shouldBootstrapDiLink5ContainerService(op, result)"));
        assertTrue(controller.contains(
                "legacy controller owns no physical projection"));
        assertTrue(controller.contains(
                "deferring stale legacy gauge restore until "));
        assertFalse(controller.contains(
                "cleared stale legacy projection gate "));
        assertTrue(cast.contains(
                "DiLink5ProjectionConfig.from("));
        assertTrue(cast.contains(
                "Target.FULL"));
        assertTrue(cast.contains(
                "Fresh and unreadable configuration uses the standard target."));

        String clusterCast = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/ClusterCast.java");
        assertTrue(clusterCast.contains(
                "rehomeMode == NO_REHOME"));
        assertTrue(clusterCast.contains(
                "DiLink5ClusterCast.stopForAccOff()"));
        assertTrue(clusterCast.contains(
                "public static void reparentStrandedCastAtBoot()"));
        assertTrue(clusterCast.contains(
                "DiLink5ClusterCast.recoverAtBoot(true);"));
        assertTrue(clusterCast.contains(
                "reparentLegacyStrandedCastAtBootSynchronously()"));
        assertTrue(clusterCast.contains(
                "public static boolean stop()"));
        int legacyResume = clusterCast.indexOf(
                "static boolean isResumedOnDisplay(");
        int legacyResumeEnd = clusterCast.indexOf(
                "private static char charAt", legacyResume);
        assertTrue(legacyResume >= 0 && legacyResumeEnd > legacyResume);
        assertFalse(clusterCast.substring(
                legacyResume, legacyResumeEnd).contains(
                "resumedPackageOnDisplay("));

        String viewMirror = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "ClusterViewMirrorService.java");
        assertTrue(viewMirror.contains(
                "resolveDiLink5MirrorDisplay(diLink5TargetId)"));
        assertTrue(viewMirror.contains(
                "isProjectionSourceActive()"));
        assertTrue(viewMirror.contains(
                "inputSnapshot()"));

        String legacyProjection = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "ClusterProjectionController.java");
        assertTrue(legacyProjection.contains(
                "isLegacyProjectionBlockedByDiLink5Recovery()"));
        assertOrdered(
                legacyProjection,
                "public boolean requestOpen()",
                "isLegacyProjectionAdmissionBlocked()");
        assertOrdered(
                legacyProjection,
                "public boolean acquireSustained(String token)",
                "isLegacyProjectionAdmissionBlocked()");
        assertTrue(legacyProjection.contains(
                "deferring legacy projection gate restore "));
        assertTrue(legacyProjection.contains(
                "clearStaleGateAtBootSynchronously()"));
        assertTrue(legacyProjection.contains(
                "BOOT_RESTORE_LOCK"));
        assertTrue(legacyProjection.contains(
                ".isDiLink5()\n"
                        + "                        && "
                        + "!allowDiLink5RecoveryFence"));
        assertTrue(legacyProjection.contains(
                "deferring stale legacy gauge restore until "));
        assertFalse(legacyProjection.contains(
                "cleared stale legacy projection gate in DI5 mode"));
        assertTrue(legacyProjection.contains(
                "if (closeSent\n"
                        + "                        && refreshSent\n"
                        + "                        && profileSent\n"
                        + "                        && clearGateFlagsStatic())"));
        String legacyOpen = legacyProjection.substring(
                legacyProjection.indexOf(
                        "private void doOpenSequence(int epoch)"),
                legacyProjection.indexOf(
                        "private void pollReady(long elapsed, int epoch)"));
        assertOrdered(
                legacyOpen,
                "writeGateFlagsAsync(",
                "fissionDisplayExists()",
                "sendOpenStep(OP_FULLSCREEN_ON, epoch, true)",
                "sendOpenStep(sizeOp, epoch, false)",
                "sendOpenStep(OP_FULLSCREEN_ON, epoch, false)",
                "sendOpenStep(OP_DI4_MODE, epoch, false)");
        String legacyForceClose = legacyProjection.substring(
                legacyProjection.indexOf(
                        "public void forceClose(String reason)"),
                legacyProjection.indexOf(
                        "private void doOpenSequence(int epoch)"));
        assertTrue(legacyForceClose.contains(
                "ClusterMapProjector\n"
                        + "                .onLegacyProjectionForceClosed()"));
        assertTrue(legacyForceClose.contains(
                "ClusterViewMirrorService\n"
                        + "                            "
                        + ".detachBeforeProjectionClose(reason)"));
        assertTrue(legacyForceClose.contains(
                "ClusterMirrorController\n"
                        + "                            "
                        + ".detachBeforeProjectionClose(reason)"));
        String legacyClose = legacyProjection.substring(
                legacyProjection.indexOf(
                        "private void doCloseSequence(int epoch)"),
                legacyProjection.indexOf(
                        "private boolean isCurrentCloseEpoch(int epoch)"));
        assertOrdered(
                legacyClose,
                "sendInfoResult(OP_CLOSE)",
                "sendInfoResult(OP_REFRESH)",
                "sendInfoResult(restoreProfile)");
        assertFalse(legacyClose.contains("clearGateFlags"));
        String legacyCloseCommit = legacyProjection.substring(
                legacyProjection.indexOf(
                        "private void completeCloseSequence("),
                legacyProjection.indexOf(
                        "// ── SIGKILL / SIGTERM recovery"));
        assertOrdered(
                legacyCloseCommit,
                "synchronized (this)",
                "if (epoch != seqEpoch || projState != ST_CLOSING) return;",
                "restoreCommandsSucceeded && clearGateFlagsStatic()",
                "projState = ST_CLOSED");
        assertTrue(viewMirror.contains(
                "if (!projection.acquireSustained(\"viewmirror\"))"));

        String clusterMirror = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "ClusterMirrorController.java");
        assertTrue(clusterMirror.contains(
                "isLegacyProjectionAdmissionBlocked()"));
        assertTrue(clusterMirror.contains(
                "if (!projection.isOpen())"));
        assertOrdered(
                clusterMirror,
                "if (!projection.isOpen())",
                "projection.acquireSustained(\"mirror\")",
                "BsNativeLayer.resolveFissionDisplay()");
        assertTrue(clusterMirror.contains(
                "public static boolean "
                        + "detachBeforeProjectionClose(String reason)"));
        assertTrue(clusterMirror.contains(
                "if (suppressProjectionRelease) "
                        + "holdsProjection = false;\n"
                        + "                    stopOnExec();"));

        String mapProjector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/navmap/"
                        + "ClusterMapProjector.java");
        assertTrue(mapProjector.contains(
                "public static void onLegacyProjectionForceClosed()"));
        assertTrue(mapProjector.contains(
                "if (!active || launchThread != self"));
        assertTrue(mapProjector.contains(
                "launchMapOnDisplay(displayId, self)"));

        String accMonitor = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/"
                        + "AccMonitor.java");
        assertTrue(accMonitor.contains(
                "retryClusterAutoProjectionAfterRecovery()"));
        assertTrue(cast.contains(
                "phase = SessionPhase.RECOVERING"));
        assertTrue(cast.contains(
                "reservedRetryTicket = ticket"));

        String inputRelay = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "ClusterInputRelay.java");
        assertTrue(inputRelay.contains(
                "currentTargetDisplayId()"));
        assertTrue(inputRelay.contains(
                "inputSnapshot()"));
        assertFalse(inputRelay.contains(
                "resolveDiLink5MirrorDisplay(targetId)"));
        assertTrue(inputRelay.contains(
                "input and panel composition"));

        String freeform = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/"
                        + "ClusterFreeformWindow.java");
        assertTrue(freeform.contains(
                "static TaskLocation findTaskLocation("));
        assertTrue(freeform.contains(
                "static boolean moveTaskToDisplay("));
        assertTrue(freeform.contains(
                "static boolean focusTask("));
        // Android 12 head-unit re-home: single task, background, standard
        // fullscreen root only (never a whole root task with siblings).
        assertTrue(freeform.contains(
                "static boolean moveTaskToDefaultDisplayBackground("));
        assertTrue(freeform.contains("\"moveTaskToRootTask\", \"moveTaskToStack\""));
        assertTrue(freeform.contains("method.invoke(svc, taskId, hostRoot, false);"));
        // Live running-task lookup (no stale mLastPausedActivity references) and
        // root-task-first display resolution (RunningTaskInfo.displayId reported 0
        // for a task resumed on the shared display, causing spurious relaunches).
        assertTrue(freeform.contains(
                "static int liveTaskIdForPackage(String pkg)"));
        assertTrue(freeform.contains(
                "int viaRoots = displayIdViaRootTasks(svc, taskId);"));

        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        assertTrue(daemon.contains(
                "20260925-di5-acc-authority-heartbeat-1"));
        assertOrdered(
                daemon,
                "DiLink5ClusterCast",
                ".shutdownIfActive()");
    }

    @Test
    public void diLink5CameraSuppressionDoesNotDriveRecordingRetryLoops()
            throws Exception {
        String recording = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/recording/"
                        + "RecordingModeManager.java");
        assertTrue(recording.contains(
                "DiLink5Platform.isSelected()"));
        assertTrue(recording.contains(
                "DiLink5QCarCamBackend"));
        assertTrue(recording.contains(
                "&& !diLink5CaptureSuppressed"));

        String avm = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/"
                        + "TsAvmCoordinator.java");
        assertTrue(avm.contains(
                "AVM_PREFLIGHT_DISPATCH_ATTEMPTS = 3"));
        assertTrue(avm.contains(
                "formatDispatchOutput(dispatchOutput)"));
    }

    @Test
    public void initializationSerializationAndRestartReasonsAreExplicit()
            throws Exception {
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "GpuSurveillancePipeline.java");
        assertTrue(pipeline.contains(
                "private volatile boolean initialized = false;"));
        assertTrue(pipeline.contains(
                "private final Object pipelineInitLock = new Object();"));
        assertTrue(pipeline.contains(
                "synchronized (pipelineInitLock)"));
        assertTrue(pipeline.contains(
                "initLocked(assetManager, context);"));

        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "SurveillanceApiHandler.java");
        assertTrue(api.contains("handlePrepareRestart(out, body)"));
        assertTrue(api.contains(
                "prepare-restart: requested reason="));

        String vehicleApi = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "VehicleControlApiHandler.java");
        assertTrue(vehicleApi.contains(
                ".startAndAwait(pkg, 60000L)"));

        String projectionUi = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/"
                        + "ProjectionFragment.kt");
        assertTrue(projectionUi.contains(
                "DaemonHttpClient.open(\"/api/vehicle/cluster-cast\", "
                        + "\"POST\", 2000, 70000)"));
    }

    @Test
    public void accWhitelistKeepsLegacyFallbackOutsideDiLink5() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        int fallback = source.indexOf(
                "private static boolean whitelistViaDirectTransact");
        int next = source.indexOf(
                "private static boolean tryTransactCode", fallback);
        String transact = source.substring(fallback, next);
        int strictGate = transact.indexOf("if (isDilink5CameraMode())");
        int legacyScan = transact.indexOf("for (int code = 1; code <= 5; code++)");
        assertTrue(strictGate >= 0);
        assertTrue(legacyScan > strictGate);
        assertTrue(transact.contains("binder.getInterfaceDescriptor()"));
        assertTrue(transact.contains("tryTransactCode(binder, packageName, 2)"));
        assertTrue(transact.contains("code != 2"));
    }

    @Test
    public void partialSeatClimateSnapshotKeepsAnsweredChannelsFresh()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int collect = source.indexOf(
                "private void collectSettings(BydVehicleData.Builder b)");
        int next = source.indexOf(
                "private void collectSocTarget", collect);
        String settings = source.substring(collect, next);
        assertTrue(settings.contains("int seatClimateReadCount = 0;"));
        assertTrue(settings.contains("seatClimateReadCount++;"));
        assertTrue(settings.contains("seatClimateReadCount > 0"));
        assertTrue(settings.contains("seatClimateReadCount == 4"));
        assertTrue(settings.contains(
                "b.seatClimateAtMs(seatClimateFresh ? System.currentTimeMillis() : 0L);"));
    }

    @Test
    public void diLink5PrimitiveValidityResetsBeforeEachPoll()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        assertTrue(source.contains("b.ambientColourKnown(false);"));
        assertTrue(source.contains(
                "b.ambientEnabled(BydVehicleData.UNAVAILABLE);"));
        assertTrue(source.contains("b.speedLimitWarningKnown(false);"));
        assertTrue(source.contains("b.driftModeKnown(false);"));
    }

    @Test
    public void selectedDiLink5SafetyUsesLiveGearAndFailsClosed()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/communication/"
                        + "VehicleCommunicationSafety.java");
        int readGear = source.indexOf("private static Integer readGear()");
        String liveRead = source.substring(readGear);
        assertTrue(liveRead.contains("if (DiLink5Platform.isSelected())"));
        assertTrue(liveRead.contains(
                "BydDataCollector.getInstance().readGearNow()"));
        assertTrue(source.contains(
                "if (DiLink5Platform.isSelected() && gear == null) return false;"));
    }

    @Test
    public void selectedDiLink5CallbacksRefreshSeatClimateAndUseExactCpdApi()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int callback = source.indexOf(
                "private void onSettingsCallback(String method, Object[] args)");
        int next = source.indexOf(
                "private static String diagnosticValue", callback);
        String settingsCallback = source.substring(callback, next);
        assertTrue(settingsCallback.contains(
                "\"onCpdImsSwitchStateChanged\".equals(method)"));
        assertTrue(settingsCallback.contains(
                "b.seatClimateAtMs(System.currentTimeMillis());"));
        assertTrue(source.contains(
                "\"getCpdImsSwitchState\""));
        assertTrue(source.contains(
                "\"setCpdImsSwitchState\", value"));
        assertTrue(source.contains(
                "DiLink5Confirmation.seatMemory(position, 1)"));
        assertTrue(source.contains(
                "DiLink5Confirmation.seatMemory(position, 2)"));
    }

    @Test
    public void nestedActuatorWorkCannotEscapeTheVehicleModeRequest()
            throws Exception {
        String bridge = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/VehicleActuatorBridge.java");
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        String router = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/routing/"
                        + "VehicleCommandRouter.java");

        assertTrue(bridge.contains(
                "if (hasDiLink5RequestScope()) return;"));
        assertTrue(bridge.contains(
                "if (hasDiLink5RequestScope()) return false;"));
        assertTrue(bridge.contains(
                "if (hasDiLink5RequestScope() || command == null"));
        assertTrue(collector.contains(
                "if (VehicleActuatorBridge.hasDiLink5RequestScope()) {\n"
                        + "            return setEnergyModeDirectVerified(mode);"));
        assertTrue(collector.contains(
                "VehicleActuatorBridge.writeEnergyModeRaw(device, mode)"));
        assertTrue(collector.contains(
                "scopedDiLink5 && VehicleActuatorBridge.isDiLink5RequestExpired()"));
        assertFalse(router.contains(
                "&& !(cmd instanceof EnergyModeCommand)"));
    }

    @Test
    public void diLink5PositionWritesRequirePoweredMotors() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BodyworkSeatProbe.java");
        assertTrue(source.contains(
                "if (positionPowerUnavailable(r)) return r;"));
        assertTrue(source.contains(
                "Boolean accOn = VehicleActuatorBridge.currentDiLink5RequestAccOn();"));
        assertTrue(source.contains("result.put(\"accepted\", false);"));
        assertTrue(source.contains("result.put(\"inert\", true);"));
    }

    @Test
    public void mediaAutomationsUseTheModeAwareVehicleRouter() throws Exception {
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");
        String router = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/routing/"
                        + "VehicleCommandRouter.java");

        assertTrue(handler.contains(
                "new VehicleCommandRouter.AmbientBrightnessCommand(value, zone)"));
        assertTrue(handler.contains(
                "new VehicleCommandRouter.AmbientPowerCommand(value > 0, zone)"));
        assertTrue(handler.contains(
                "new VehicleCommandRouter.HudBrightnessCommand(value)"));
        assertTrue(handler.contains(
                "new VehicleCommandRouter.HudPowerCommand(value > 0)"));
        assertTrue(router.contains(
                "return c.setHudBrightness(percent);"));
        assertTrue(router.contains(
                "return c.setHudPower(on);"));
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 6 && current != null;
                depth++, current = current.getParent()) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Could not locate " + relativePath);
    }

    private static void assertOrdered(String text, String... values) {
        int position = -1;
        for (String value : values) {
            int next = text.indexOf(value, position + 1);
            assertTrue("Missing or out-of-order text: " + value, next > position);
            position = next;
        }
    }
}
