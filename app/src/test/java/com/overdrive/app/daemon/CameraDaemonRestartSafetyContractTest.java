package com.overdrive.app.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class CameraDaemonRestartSafetyContractTest {

    @Test
    public void standaloneDaemonRegistersTheProcessMainLooper()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");

        assertOrdered(
                source,
                "if (Looper.myLooper() == null) {",
                "if (Looper.getMainLooper() == null) {",
                "Looper.prepareMainLooper();",
                "} else {",
                "Looper.prepare();",
                "mainHandler = new Handler(Looper.myLooper());");
    }

    @Test
    public void watchdogRestartCheckpointsBeforeSettingRestartIntent()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int coordinator = source.indexOf(
                "public static void requestProcessRestartPreservingTrip");
        int checkpoint = source.indexOf(
                "prepareTripsForProcessRestart(reason)", coordinator);
        int intent = source.indexOf("processRestartIntent = true;", checkpoint);
        int exit = source.indexOf("System.exit(0);", intent);

        assertTrue(coordinator >= 0);
        assertTrue(checkpoint > coordinator);
        assertTrue(intent > checkpoint);
        assertTrue(exit > intent);
        assertTrue(source.contains("shouldFinalizeTripsOnShutdown()"));
        assertTrue(source.contains("shouldFinalizeTripsOnShutdown() && manager != null"));
    }

    @Test
    public void cameraFailurePathsUseOnlyTripSafeRestartCoordinator()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");

        assertFalse(source.contains("System.exit(0);"));
        assertFalse(source.contains("Runtime.getRuntime().halt(0);"));
        assertFalse(source.contains(
                "android.os.Process.killProcess(android.os.Process.myPid())"));
        // GL stall watchdog, EGLCore exhaustion breaker, reopen-wedge path,
        // plus two audit follow-ups: the stop() GL-thread teardown escalation
        // (a GL thread that won't exit keeps its EGL context CURRENT — pinned
        // in the driver's context table — and only a trip-safe process exit
        // frees it) and the wedged-encoder-drainer escalation in
        // stopEncoderDrainersBeforeCameraClose (closing the camera over a
        // mid-dequeue drainer aborts the process — FORTIFY destroyed mutex —
        // before the async restart coordinator can checkpoint the trip).
        // The eighth site is the decoupled encoder lane's stop() quiesce
        // failure with the camera NOT held. Two additional DI5 sites replace
        // a dead GL generation after a rejected delayed source retry or a
        // null retry handler. DI4 terminal producer recovery adds two guarded
        // fallbacks: no pipeline listener, and a listener dispatch throw.
        // Legacy stop adds one conservative fallback when initialization is
        // still in flight but no camera handle/open ownership is observable.
        // (The former rejected post-reverse resume site went with the removed
        // DI5 reverse yield.) DI5 adds one once-per-boot site: a reopen after an
        // idle source release that the safety guard refused for the process.
        assertTrue(count(source,
                "requestProcessRestartPreservingTrip(") == 13);
    }

    @Test
    public void everyScopedExternalKillRequiresPrepareRestart()
            throws IOException {
        String activity = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");
        int reconfigure = activity.indexOf("private fun performCameraReconfigure()");
        int reconfigureEnd = activity.indexOf(
                "// ==================== Battery Health", reconfigure);
        String reconfigureBody = activity.substring(reconfigure, reconfigureEnd);
        assertTrue(reconfigureBody.contains(
                "restartCameraDaemonForCameraSettings()"));
        assertFalse(reconfigureBody.contains("killDaemon("));

        String appManager = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/management/DaemonManager.kt");
        int appStop = appManager.indexOf("private fun stopCameraDaemon");
        int appPrepare = appManager.indexOf("prepareCameraRestart()", appStop);
        int appKill = appManager.indexOf("adbLauncher.killDaemon(", appPrepare);
        assertTrue(appPrepare > appStop);
        assertTrue(appKill > appPrepare);
        assertTrue(appManager.contains(
                "\"/api/surveillance/prepare-restart\", \"POST\""));

        String telegram = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/telegram/DaemonCommandHandler.java");
        int telegramStop = telegram.indexOf("private boolean stopDaemon(");
        int cameraBranch = telegram.indexOf(
                "if (\"byd_cam_daemon\".equals(processName))", telegramStop);
        int telegramPrepare = telegram.indexOf(
                "prepareCameraDaemonForKill(ctx);", cameraBranch);
        int firstKill = telegram.indexOf("ctx.execShell(", cameraBranch);
        assertTrue(telegramStop >= 0);
        assertTrue(cameraBranch > telegramStop);
        assertTrue(telegramPrepare > cameraBranch);
        assertTrue(firstKill > telegramPrepare);
        int prepareMethod = telegram.indexOf("private void prepareCameraDaemonForKill(");
        int prepareRequest = telegram.indexOf(
                "\"/api/surveillance/prepare-restart\", \"POST\"", prepareMethod);
        assertTrue(prepareMethod >= 0 && prepareRequest > prepareMethod);
    }

    @Test
    public void restartArmsTerminalGuardBeforeExitAndDisarmsOnFailedExit()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int coordinator = source.indexOf(
                "public static void requestProcessRestartPreservingTrip");
        int intent = source.indexOf("processRestartIntent = true;", coordinator);
        // The guard must be armed AFTER the checkpoint is durable (a halt
        // before it would lose the trip) but BEFORE System.exit — the shutdown
        // hook can wedge on the failed camera/GL state and nothing else would
        // ever terminate the process.
        int arm = source.indexOf("armTerminalShutdownDeadline()", intent);
        int exit = source.indexOf("System.exit(0);", arm);
        // If exit throws and the process is deliberately left running, the
        // armed deadline must be disarmed so it can't halt us 20 s later.
        int exitFailure = source.indexOf("catch (Throwable t)", exit);
        int disarm = source.indexOf("disarmTerminalShutdownDeadline();", exitFailure);
        int coordinatorEnd = source.indexOf(
                "private static boolean prepareTripsForProcessRestart", coordinator);

        assertTrue(coordinator >= 0);
        assertTrue(intent > coordinator);
        assertTrue(arm > intent);
        assertTrue(exit > arm);
        assertTrue(exitFailure > exit);
        assertTrue(disarm > exitFailure);
        assertTrue(coordinatorEnd > disarm);
    }

    @Test
    public void shutdownHookRestoresSafetyStateBeforeRoadSenseAndNeverFinalizesRestartTrips()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int hook = source.indexOf("Runtime.getRuntime().addShutdownHook");
        assertTrue(hook >= 0);
        int hookEnd = source.indexOf("releaseSingletonLock();", hook);
        assertTrue(hookEnd > hook);
        String hookBody = source.substring(hook, hookEnd);

        // Ordering: trip quiesce (restart-aware), THEN the safety-critical
        // screen-deterrent flag reset and cluster gauge restoration, THEN
        // RoadSense/RecordingModeManager (whose teardown can block), THEN GPU.
        // If RoadSense wedges and the terminal guard halts the process, the
        // deterrent flags and cluster gauges must already be restored.
        assertOrdered(
                hookBody,
                "shutdownTripAnalyticsBeforeBlockingCleanup();",
                "ScreenDeterrent.getInstance().cancel();",
                "ClusterProjectionController.shutdownIfActive();",
                "detachRoadSenseAndRecordingMode();",
                "gpuPipeline.stop();");

        // The hook must never finalize the active trip unconditionally: a
        // trip-safe restart already checkpointed it, and finalize can route a
        // short leg to discardTrip() which deletes the telemetry file. The one
        // direct manager.shutdown() left in the hook must be gated.
        assertTrue(count(hookBody, "tripAnalyticsManager.shutdown();") == 1);
        String normalized = hookBody.replaceAll("\\s+", " ");
        assertTrue(normalized.contains(
                "if (shouldFinalizeTripsOnShutdown() && tripAnalyticsManager != null) "
                        + "{ tripAnalyticsManager.shutdown();"));

        // The RoadSense/RMM helper is shared: normal shutdown and the hook use
        // the same code path (definition + exactly two call sites).
        assertTrue(count(source, "detachRoadSenseAndRecordingMode();") == 2);
        int normalShutdown = source.indexOf(
                "private static void shutdownInternal(");
        int normalHelperCall = source.indexOf(
                "detachRoadSenseAndRecordingMode();", normalShutdown);
        assertTrue(normalShutdown >= 0);
        assertTrue(normalHelperCall > normalShutdown && normalHelperCall < hook);
    }

    @Test
    public void operatingModeAutomationReconcilesBeforeParkedShutdownCommit()
            throws IOException {
        String actions = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/automation/action/Actions.java");
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String messages = readRepositoryFile(
                "app/src/main/assets/server-i18n/en.json");

        assertTrue(actions.contains("applyCurrentAccState"));
        assertTrue(messages.contains("all OverDrive daemons after ACC turns off"));

        int operatingModePost = handler.indexOf("// Operating mode:");
        int persist = handler.indexOf("UnifiedConfigManager.updateValues(", operatingModePost);
        int reconcile = handler.indexOf(
                "CameraDaemon.reconcileOperatingModeForCurrentAccState();", persist);
        int response = handler.indexOf("HttpResponse.sendJsonSuccess(out);", reconcile);
        assertTrue(operatingModePost >= 0);
        assertTrue(persist > operatingModePost);
        assertTrue(reconcile > persist);
        assertTrue(response > reconcile);

        int parkTerminate = daemon.indexOf(
                "private static boolean parkTerminate(long expectedGeneration)");
        int drain = daemon.indexOf("drainDueNowResult(12_000L)", parkTerminate);
        int modeGuard = daemon.indexOf("isVehicleOnOnlyMode()", drain);
        int commit = daemon.indexOf("commitShutdownIfQuiescent", modeGuard);
        assertTrue(parkTerminate >= 0);
        assertTrue(drain > parkTerminate);
        assertTrue(modeGuard > drain);
        assertTrue(commit > modeGuard);

        int currentCycle = daemon.indexOf(
                "public static void reconcileOperatingModeForCurrentAccState()");
        int unknownStateRecovery = daemon.indexOf(
                "requestTrustedAccHardwareRecovery(", currentCycle);
        int parkedReconcile = daemon.indexOf(
                "forceLatestAccStateReconciliation(", unknownStateRecovery);
        assertTrue(currentCycle >= 0);
        assertTrue(unknownStateRecovery > currentCycle);
        assertTrue(parkedReconcile > unknownStateRecovery);
    }

    @Test
    public void urgentCameraReleaseArmsMonotonicDeadlineBeforeLoggingAndDelegation()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int method = source.indexOf(
                "public static void requestUrgentCameraReleaseRestart");
        int methodEnd = source.indexOf(
                "private static boolean prepareTripsForProcessRestart", method);
        assertTrue(method >= 0);
        assertTrue(methodEnd > method);
        String body = source.substring(method, methodEnd);

        // Arm-once latch first, then a MONOTONIC deadline stamped BEFORE the
        // guard thread starts (wall-clock changes must not stretch/shrink the
        // camera-hold bound; scheduler delay in starting the guard must not
        // extend it), then the guard start with its failure log DEFERRED (a
        // catch-block log between the two arms would re-open the hole), then
        // the handler fallback scheduled with the REMAINING monotonic budget
        // (a fresh 5s window would extend the bound past the stamped
        // deadline), then — only with a deadline running — the first logger
        // touch, the deferred failure report, and the conservative delegate.
        // The logger can block on the wedged storage this path fires under, so
        // a log call ahead of the arms would strand the held camera while the
        // latch makes every later urgent request a no-op.
        assertOrdered(
                body,
                "URGENT_CAMERA_RELEASE_ARMED.compareAndSet(false, true)",
                "android.os.SystemClock.elapsedRealtime() + URGENT_CAMERA_RELEASE_BUDGET_MS",
                "guard.start();",
                "guardStartFailure = t;",
                "long remainingBudgetMs = haltDeadlineElapsedMs",
                "if (remainingBudgetMs <= 0)",
                "deadline already expired during arming",
                "handler.postDelayed(",
                "forceTerminateProcess(\"urgent camera-release guard unavailable: \" + reason);",
                "URGENT camera-release restart armed",
                "guard thread could not start",
                "requestProcessRestartPreservingTrip(reason);");

        // An already-expired budget must HALT, never post: postDelayed(0)
        // only queues work behind a possibly-blocked main looper, and when
        // the thread arm failed that queue entry would be the only
        // "deadline". The expired branch must therefore precede (and guard)
        // the postDelayed call.
        int expiredBranch = body.indexOf("if (remainingBudgetMs <= 0)");
        int expiredHalt = body.indexOf(
                "deadline already expired during arming", expiredBranch);
        int post = body.indexOf("handler.postDelayed(", expiredBranch);
        assertTrue(expiredBranch >= 0);
        assertTrue(expiredHalt > expiredBranch);
        assertTrue(post > expiredHalt);
        assertFalse(body.contains("Math.max(0L, haltDeadlineElapsedMs"));

        // THE invariant this test exists for: no logger touch of any kind
        // until the not-armed halt gate has passed — i.e. until a deadline is
        // guaranteed to be running (or the process is already halting).
        int firstLoggerTouch = body.indexOf("log(");
        int armGate = body.indexOf(
                "forceTerminateProcess(\"urgent camera-release guard unavailable: \" + reason);");
        assertTrue(armGate >= 0);
        assertTrue(firstLoggerTouch > armGate);

        // No wall-clock anywhere in the urgent path; the stamp, the guard
        // loop's re-check and the handler fallback's remaining-budget
        // computation all read the monotonic clock.
        assertFalse(body.contains("System.currentTimeMillis()"));
        assertTrue(count(body, "android.os.SystemClock.elapsedRealtime()") == 3);

        // The halt must be the hook-free kill path, never System.exit — exit's
        // shutdown hooks can pile onto the wedged startStopLock/GL/storage
        // state and hold the process open past the deadline.
        assertFalse(body.contains("System.exit"));
    }

    @Test
    public void urgentLatchGatesBringUpAndNeverSelfClears() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");

        // isProcessRestartPending must also read the urgent latch: the
        // conservative coordinator's failure paths clear its own flag, but an
        // armed urgent halt is guaranteed process death and new camera/GL
        // bring-up must stay refused for the remaining seconds.
        int pending = source.indexOf("public static boolean isProcessRestartPending()");
        int pendingEnd = source.indexOf("}", pending);
        String pendingBody = source.substring(pending, pendingEnd);
        assertTrue(pendingBody.contains("PROCESS_RESTART_REQUESTED.get()"));
        assertTrue(pendingBody.contains("URGENT_CAMERA_RELEASE_ARMED.get()"));

        // Arm-once and non-cancellable: exactly one CAS raise, no set()/reset
        // anywhere — unlike PROCESS_RESTART_REQUESTED there are deliberately
        // no self-clearing failure paths.
        assertTrue(count(source,
                "URGENT_CAMERA_RELEASE_ARMED.compareAndSet(false, true)") == 1);
        assertTrue(count(source, "URGENT_CAMERA_RELEASE_ARMED.set(") == 0);
    }

    @Test
    public void wedgedCameraClosePathsEscalateUrgentlyOnlyWhileCameraHeld()
            throws IOException {
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");

        // The camera-held probe is a volatile read, safe from the watchdog and
        // pipeline threads.
        assertTrue(camera.contains("private volatile Object cameraObj;"));
        assertTrue(camera.contains("public boolean isCameraHandleHeld()"));

        // Drainer-close helper: gate on the held handle, REQUEST the restart
        // before the wedge log (the logger can block on the same wedged mount
        // that wedged the drainer), urgent when held / conservative when not.
        int helper = camera.indexOf(
                "private boolean stopEncoderDrainersBeforeCameraClose");
        int helperEnd = camera.indexOf("public boolean isCameraHandleHeld()", helper);
        String helperBody = camera.substring(helper, helperEnd);
        assertOrdered(
                helperBody,
                "final boolean cameraHeld = cameraObj != null;",
                "CameraDaemon.requestUrgentCameraReleaseRestart(",
                "CameraDaemon.requestProcessRestartPreservingTrip(",
                "encoder drainer wedged — closing the camera now");

        // GL watchdog: the LAST escape when the GL thread wedges on
        // startStopLock before ever reaching the helper (timed-out pre-yield
        // worker holds it inside closeEventRecording). Urgent when the camera
        // is held; the CRITICAL log only after the request is placed.
        int watchdogUrgent = camera.indexOf(
                "GL watchdog heartbeat timeout (camera held)");
        int watchdogLog = camera.indexOf("CRITICAL: GL thread blocked for ");
        assertTrue(watchdogUrgent >= 0);
        assertTrue(camera.contains("\"GL watchdog heartbeat timeout\");"));
        assertTrue(watchdogLog > watchdogUrgent);

        // Thirteen urgent sites in the camera layer: helper, GL watchdog, the
        // stall-recovery legacy HAL open hard bound, the DiLink 4 HAL open still
        // blocked past its late-open deadline, the ACC-ON legacy reopen's
        // timeout/interruption bounds, the legacy stop/open ownership fence, a
        // bounded legacy windshield-camera lifecycle helper,
        // DiLink 5 safe-off teardown that cannot reach its owning GL thread, a
        // bounded ownership-transition wait that expired while camera ownership
        // was indeterminate, a source that completed native startup but could not
        // be atomically claimed by the GL owner, and a wedged EncoderLane quiesce
        // during stop() (decoupled encoder lane — a lane stuck mid-eglSwapBuffers
        // pins the child EGL context and may still be drawing into the codec the
        // caller is about to release). Every other failure stays conservative;
        // the lane site is held-gated with the conservative
        // requestProcessRestartPreservingTrip fallback like the helper. DI4
        // pano has no ownership-handoff escape sites because it keeps its
        // single AVMCamera producer open continuously. The additional opt-in
        // SurfaceTexture site runs only when its owned vendor handle refuses close.
        assertTrue(count(camera,
                "requestUrgentCameraReleaseRestart(") == 13);
        int surfaceTextureCloseStart = camera.indexOf(
                "if (USE_AVM_SURFACE_TEXTURE_PATH) {",
                camera.indexOf("private boolean closeCameraForPath("));
        int surfaceTextureCloseEnd = camera.indexOf(
                "\n        if (USE_DILINK4_AVM_PATH) {", surfaceTextureCloseStart);
        assertTrue(surfaceTextureCloseStart >= 0);
        assertTrue(surfaceTextureCloseEnd > surfaceTextureCloseStart);
        String surfaceTextureClose = camera.substring(
                surfaceTextureCloseStart, surfaceTextureCloseEnd);
        assertOrdered(surfaceTextureClose,
                "surfaceTextureBackends.get(cam)",
                "if (backend == null) return true;",
                "boolean clean = backend.close();",
                "} else {",
                "requestUrgentCameraReleaseRestart(");

        // Retiring-stream-encoder wedge in the surveillance pipeline: same
        // gate, same request-before-log ordering, urgent only while held.
        assertOrdered(
                pipeline,
                "final boolean cameraHeld = camera != null && camera.isCameraHandleHeld();",
                "com.overdrive.app.daemon.CameraDaemon.requestUrgentCameraReleaseRestart(",
                "com.overdrive.app.daemon.CameraDaemon.requestProcessRestartPreservingTrip(",
                "retiring stream-encoder release incomplete/wedged");
        assertTrue(count(pipeline,
                "CameraDaemon.requestUrgentCameraReleaseRestart(") == 1);
    }

    @Test
    public void drainerCameraCloseJoinUsesTeardownStandardDeadline()
            throws IOException {
        String recorder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java");
        int method = recorder.indexOf("public boolean stopDrainerForCameraClose()");
        int methodEnd = recorder.indexOf(
                "public void restartDrainerAfterCameraClose()", method);
        assertTrue(method >= 0);
        assertTrue(methodEnd > method);
        String body = recorder.substring(method, methodEnd);

        // 2s join, aligned with stopDrainerThread and the disk-writer join.
        // A false "wedged" verdict now costs a bounded URGENT process restart,
        // so the 1s outlier this replaced is the false-positive risk.
        assertTrue(body.contains(".joinFullDeadline(deadDrainer, 2000, interrupted)"));
        assertFalse(body.contains(", 1000, interrupted"));
    }

    private static void assertOrdered(String source, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int position = source.indexOf(needle, previous + 1);
            assertTrue("Missing or out of order: " + needle, position > previous);
            previous = position;
        }
    }

    private static int count(String source, String needle) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
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
