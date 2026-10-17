package com.overdrive.app.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class AppUpdaterRestartSafetyContractTest {

    @Test
    public void prepareStatusClassifierRecognizesOnlySuccessfulResponses() {
        assertFalse(AppUpdater.isSuccessfulCameraPrepareStatus(0));
        assertFalse(AppUpdater.isSuccessfulCameraPrepareStatus(199));
        assertTrue(AppUpdater.isSuccessfulCameraPrepareStatus(200));
        assertTrue(AppUpdater.isSuccessfulCameraPrepareStatus(204));
        assertTrue(AppUpdater.isSuccessfulCameraPrepareStatus(299));
        assertFalse(AppUpdater.isSuccessfulCameraPrepareStatus(300));
        assertFalse(AppUpdater.isSuccessfulCameraPrepareStatus(503));
    }

    @Test
    public void coreInstallNeverGatesOnCameraPrepareAndUsesBothKillPaths()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        String install = methodBody(
                source,
                "public void downloadAndInstall(InstallCallback callback)",
                "// ==================== COMPANION APK INSTALL");

        int metadata = install.indexOf(
                "android.content.SharedPreferences.Editor ie");
        int detached = install.indexOf(
                "runDetachedInstall(", metadata);
        int synchronous = install.indexOf(
                "boolean cameraStopped = stopAllDaemons();", detached);

        assertFalse(install.contains("prepareCameraDaemonForUpdate();"));
        assertFalse(install.contains("Update stopped safely"));
        assertTrue(metadata >= 0);
        assertTrue(detached > metadata);
        assertTrue(synchronous > detached);
        assertTrue(install.contains(
                "OTA is intentionally force-forward: do not gate installation"));
    }

    @Test
    public void detachedFailureRollsBackButUnconfirmedStopStillInstalls()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        String install = methodBody(
                source,
                "public void downloadAndInstall(InstallCallback callback)",
                "// ==================== COMPANION APK INSTALL");

        int detachedCall = install.indexOf(
                "boolean detachedStarted = runDetachedInstall(");
        int detachedFailure = install.indexOf(
                "if (!detachedStarted)", detachedCall);
        int detachedRollback = install.indexOf(
                "rollbackPreparedUpdateMetadata(", detachedFailure);
        int detachedReturn = install.indexOf("return;", detachedRollback);

        assertTrue(detachedCall >= 0);
        assertTrue(detachedFailure > detachedCall);
        assertTrue(detachedRollback > detachedFailure);
        assertTrue(detachedReturn > detachedRollback);
        assertFalse(install.contains("abortPreparedCameraRestart();"));
        assertTrue(install.contains(
                "if (rollbackMetadataOnException && preparedChannel != null)"));

        int synchronousCall = install.indexOf(
                "boolean cameraStopped = stopAllDaemons();");
        int synchronousFailure = install.indexOf(
                "if (!cameraStopped)", synchronousCall);
        int installSleep = install.indexOf("Thread.sleep(3000);", synchronousFailure);
        assertTrue(synchronousCall >= 0);
        assertTrue(synchronousFailure > synchronousCall);
        assertTrue(installSleep > synchronousFailure);
        String unconfirmedStop = install.substring(synchronousFailure, installSleep);
        assertTrue(unconfirmedStop.contains(
                "continuing OTA install by force"));
        assertTrue(unconfirmedStop.contains(
                "Installing update despite unconfirmed daemon stop"));
        assertFalse(unconfirmedStop.contains("rollbackPreparedUpdateMetadata("));
        assertFalse(unconfirmedStop.contains("postInstallError("));
        assertFalse(unconfirmedStop.contains("return;"));

        String detached = methodBody(
                source,
                "private boolean runDetachedInstall(",
                "private void cleanup(String path)");
        assertTrue(detached.contains("pb.start();"));
        assertTrue(detached.contains("return true;"));
        assertTrue(detached.contains("return false;"));
    }

    @Test
    public void allUpdaterCameraKillsRemainInTheTwoInstallHandoffs()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        int detachedStart = source.indexOf(
                "private boolean runDetachedInstall(");
        int detachedEnd = source.indexOf(
                "private void cleanup(String path)", detachedStart);
        int stopStart = source.indexOf(
                "private boolean stopAllDaemons()");
        int stopEnd = source.indexOf(
                "private static final java.util.regex.Pattern VALID_ALPHA_TAG",
                stopStart);

        assertTrue(detachedStart >= 0);
        assertTrue(detachedEnd > detachedStart);
        assertTrue(stopStart > detachedEnd);
        assertTrue(stopEnd > stopStart);

        String cameraKill = "psAwkKillLine(\"cam_daemon\")";
        int count = 0;
        int from = 0;
        int position;
        while ((position = source.indexOf(cameraKill, from)) >= 0) {
            assertTrue(inRange(position, detachedStart, detachedEnd)
                    || inRange(position, stopStart, stopEnd));
            count++;
            from = position + cameraKill.length();
        }
        assertEquals(3, count);

        int killall = source.indexOf(
                "killall -9 byd_cam_daemon 2>/dev/null");
        assertTrue(inRange(killall, detachedStart, detachedEnd));
        assertEquals(-1, source.indexOf(
                "killall -9 byd_cam_daemon 2>/dev/null",
                killall + 1));

        String install = methodBody(
                source,
                "public void downloadAndInstall(InstallCallback callback)",
                "// ==================== COMPANION APK INSTALL");
        assertFalse(install.contains("prepareCameraDaemonForUpdate();"));
        assertTrue(install.indexOf("runDetachedInstall(") >= 0);
        assertTrue(install.indexOf("stopAllDaemons();") >= 0);

        String stop = source.substring(stopStart, stopEnd);
        int finalKill = stop.lastIndexOf(cameraKill);
        int verification = stop.indexOf(
                "CAMERA_PIDS=$(ps -A -o PID,ARGS", finalKill);
        int confirmedReturn = stop.indexOf(
                "return cameraStopConfirmed[0];", verification);
        assertTrue(verification > finalKill);
        assertTrue(confirmedReturn > verification);
    }

    @Test
    public void otaHandoffsClearWatchdogOwnershipLocksAfterKillingWrappers()
            throws IOException {
        String updater = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        String lifecycle = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/UpdateLifecycle.java");
        String cameraLock = "/data/local/tmp/cam_watchdog.lock";
        String accLock = "/data/local/tmp/acc_sentry_watchdog.lock";

        String detached = methodBody(
                updater,
                "private boolean runDetachedInstall(",
                "private void cleanup(String path)");
        int detachedCameraKill = detached.indexOf(
                "psAwkKillLine(\"cam_daemon\")");
        int detachedAccKill = detached.indexOf(
                "psAwkKillLine(\"acc_sentry\")");
        assertTrue(detachedCameraKill >= 0);
        assertTrue(detachedAccKill >= 0);
        assertTrue(detached.indexOf(cameraLock, detachedCameraKill)
                > detachedCameraKill);
        assertTrue(detached.indexOf(accLock, detachedAccKill)
                > detachedAccKill);
        assertTrue(detached.contains("rm -rf " + cameraLock));
        assertTrue(detached.contains("rm -rf " + accLock));

        String stop = methodBody(
                updater,
                "private boolean stopAllDaemons()",
                "private static final java.util.regex.Pattern VALID_ALPHA_TAG");
        int firstCameraKill = stop.indexOf(
                "psAwkKillLine(\"cam_daemon\")");
        int firstAccKill = stop.indexOf(
                "psAwkKillLine(\"acc_sentry\")");
        assertTrue(stop.indexOf(cameraLock, firstCameraKill)
                > firstCameraKill);
        assertTrue(stop.indexOf(accLock, firstAccKill)
                > firstAccKill);
        assertTrue(stop.contains("rm -rf " + cameraLock));
        assertTrue(stop.contains("rm -rf " + accLock));

        int finalCameraKill = stop.lastIndexOf(
                "psAwkKillLine(\"cam_daemon\")");
        int finalAccKill = stop.lastIndexOf(
                "psAwkKillLine(\"acc_sentry\")");
        int finalSettle = stop.indexOf("\"sleep 1\\n\"", finalCameraKill);
        assertTrue(finalSettle > finalCameraKill);
        assertTrue(finalSettle > finalAccKill);
        assertTrue(stop.indexOf(cameraLock, finalSettle) > finalSettle);
        assertTrue(stop.indexOf(accLock, finalSettle) > finalSettle);

        int lifecycleReset = lifecycle.indexOf(
                "public static void hardResetDaemons");
        int lifecycleCameraKill = lifecycle.indexOf(
                "psAwkKillLine(\"start_cam_daemon\")", lifecycleReset);
        int lifecycleAccKill = lifecycle.indexOf(
                "psAwkKillLine(\"start_acc_sentry\")", lifecycleReset);
        int lifecycleSettle = lifecycle.indexOf(
                "\"sleep 1\\n\"", lifecycleCameraKill);
        assertTrue(lifecycleSettle > lifecycleCameraKill);
        assertTrue(lifecycleSettle > lifecycleAccKill);
        assertTrue(lifecycle.indexOf(cameraLock, lifecycleSettle)
                > lifecycleSettle);
        assertTrue(lifecycle.indexOf(accLock, lifecycleSettle)
                > lifecycleSettle);
        int lifecycleRecursiveRemove = lifecycle.indexOf(
                "\"rm -rf ", lifecycleSettle);
        assertTrue(lifecycleRecursiveRemove > lifecycleSettle);
        assertTrue(lifecycle.indexOf(cameraLock, lifecycleRecursiveRemove)
                > lifecycleRecursiveRemove);
        assertTrue(lifecycle.indexOf(accLock, lifecycleRecursiveRemove)
                > lifecycleRecursiveRemove);
    }

    @Test
    public void otaRefreshesAndRestartsTheFastCameraHelper()
            throws IOException {
        String backend = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/"
                        + "DiLink5QCarCamBackend.java");
        String updater = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        String lifecycle = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/UpdateLifecycle.java");
        String controller = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/daemon/"
                        + "CameraDaemonController.kt");

        int deploy = backend.indexOf("deployVerifiedAsset(");
        int reuse = backend.indexOf(
                "Process existingProcess = sHardwareProcess;",
                deploy);
        int verifyRunningBinary = backend.indexOf(
                "resolveOwnedProcessPid(existingProcess, binary)", reuse);
        int staleSweep = backend.indexOf(
                "stopStaleCaptureProcesses(binary)", verifyRunningBinary);
        int launch = backend.indexOf(
                "ProcessBuilder processBuilder = new ProcessBuilder(",
                staleSweep);
        assertTrue(deploy >= 0);
        assertTrue(reuse > deploy);
        assertTrue(verifyRunningBinary > reuse);
        assertTrue(staleSweep > verifyRunningBinary);
        assertTrue(launch > staleSweep);
        assertTrue(backend.contains("MessageDigest.getInstance(\"SHA-256\")"));
        assertTrue(backend.contains("android.system.Os.rename("));
        assertTrue(backend.contains("executable.endsWith(\" (deleted)\")"));
        assertTrue(backend.contains(
                "LEGACY_QCARCAM_PATH =\n"
                        + "            \"/data/local/tmp/qcarcam_test\""));
        assertTrue(backend.contains(
                "cameraIds.equals(sHardwareCameraIds)"));
        assertTrue(backend.contains(
                "\"--cams\", cameraIds"));
    }

    @Test
    public void captureKillCascadesUseCleanStopHelper() throws IOException {
        String updater = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        String lifecycle = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/UpdateLifecycle.java");
        String controller = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/daemon/"
                        + "CameraDaemonController.kt");

        String updaterKill = methodBody(
                updater,
                "private static String diLink5CaptureKillScript()",
                "private static String diLink5CaptureVerificationScript()");
        assertTrue(updaterKill.contains(
                "if (!isDiLink5ModeSelected()) return \"\";"));
        assertTrue(updaterKill.contains(
                "DiLink5CaptureStopScript.lines()"));
        assertFalse(updaterKill.contains("killall -9"));
        String updaterVerify = methodBody(
                updater,
                "private static String diLink5CaptureVerificationScript()",
                "private static final String POST_UPDATE_FILE");
        assertTrue(updaterVerify.contains(
                "if (!isDiLink5ModeSelected()) return \"\";"));
        assertTrue(updaterVerify.contains(
                "CAPTURE_PIDS=$(ps -A -o PID,ARGS"));

        String lifecycleKill = methodBody(
                lifecycle,
                "private static String diLink5CaptureKillScript()",
                "public static final String UPDATE_IN_PROGRESS_FILE");
        assertTrue(lifecycleKill.contains(
                "DiLink5Platform\n                    .isSelected()"));
        assertTrue(lifecycleKill.contains(
                "DiLink5CaptureStopScript.lines()"));
        assertFalse(lifecycleKill.contains("killall -9"));

        int commonProcesses = controller.indexOf(
                "private val RELATED_PROCESSES");
        int commonEnd = controller.indexOf(")", commonProcesses);
        assertTrue(commonProcesses >= 0);
        String commonList = controller.substring(commonProcesses, commonEnd);
        assertFalse(commonList.contains("fast_cam_capture"));
        assertFalse(commonList.contains("qcarcam_test"));
        assertFalse(controller.contains("DILINK5_PROCESSES"));
        // stop() and cleanup() both retire the capture through the shared
        // SIGTERM-first helper, after the daemon and only in DiLink 5 mode.
        String diLink5Retire = "if (diLink5Selected) {\n";
        int first = controller.indexOf(diLink5Retire);
        int second = controller.indexOf(diLink5Retire, first + 1);
        assertTrue(first >= 0);
        assertTrue(second > first);
        assertTrue(controller.indexOf("psAwkKillLine(\"cam_daemon\")") < first);
        assertTrue(controller.indexOf(
                "DiLink5CaptureStopScript.lines()", first) > first);
        assertTrue(controller.indexOf(
                "DiLink5CaptureStopScript.lines()", second) > second);
    }

    @Test
    public void diLink5CaptureStopScriptSignalsTermBeforeKill() {
        String script = com.overdrive.app.camera.dilink5
                .DiLink5CaptureStopScript.lines();
        int term = script.indexOf("kill -TERM");
        int wait = script.indexOf("while [ $W -lt 20 ]");
        int kill = script.indexOf("kill -KILL");
        int killall = script.indexOf("killall -9 fast_cam_capture");
        assertTrue(term >= 0);
        assertTrue(wait > term);
        assertTrue(kill > wait);
        assertTrue(killall > wait);
        assertTrue(script.contains("grep -F 'fast_cam_capture'"));
        assertTrue(script.contains("grep -F 'qcarcam_test'"));
        assertTrue(script.contains("grep -E 'fast_cam_capture|qcarcam_test'"));
        assertTrue(script.contains("if [ \"$pid\" != \"$MY_PID\" ]"));
    }

    @Test
    public void postUpdateRelaunchStaysHeadlessAndSchedulesDaemons()
            throws IOException {
        String updater = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        String main = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");
        String startup = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/daemon/"
                        + "DaemonStartupManager.kt");

        String synchronousInstall = methodBody(
                updater,
                "public void downloadAndInstall(InstallCallback callback)",
                "// ==================== COMPANION APK INSTALL");
        String detachedInstall = methodBody(
                updater,
                "private boolean runDetachedInstall(",
                "private void cleanup(String path)");

        assertTrue(synchronousInstall.contains(
                "\" --ez minimize_on_start true\""));
        assertTrue(detachedInstall.contains(
                ".append(\" true --ez minimize_on_start true\\n\")"));

        assertTrue(main.contains(
                ".isPostUpdateLaunch(this, intent)"));
        int postUpdateCheck = main.indexOf(
                ".isPostUpdateLaunch(this, intent)");
        int postUpdateConsume = main.indexOf(
                "intent.removeExtra(com.overdrive.app.updater.UpdateLifecycle.EXTRA_POST_UPDATE)",
                postUpdateCheck);
        assertTrue(postUpdateConsume > postUpdateCheck);
        assertTrue(main.contains(
                "UpdateLifecycle.hardResetDaemons(this)"));
        assertTrue(main.contains(
                "daemonStartupManager.initializeOnAppLaunch()"));
        assertTrue(startup.contains(
                "handler.postDelayed({ startCoreDaemons() }, 45000)"));
        assertTrue(startup.contains(
                "handler.postDelayed({ startOptionalDaemonsFromPreferences() }, 60000)"));
    }

    private static boolean inRange(int value, int start, int end) {
        return value >= start && value < end;
    }

    private static String methodBody(
            String source, String methodStart, String followingMarker) {
        int start = source.indexOf(methodStart);
        int end = source.indexOf(followingMarker, start);
        assertTrue("Missing method start: " + methodStart, start >= 0);
        assertTrue("Missing following marker: " + followingMarker, end > start);
        return source.substring(start, end);
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
