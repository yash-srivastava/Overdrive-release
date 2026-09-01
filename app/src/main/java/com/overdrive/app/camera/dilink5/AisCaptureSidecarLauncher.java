package com.overdrive.app.camera.dilink5;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Launches {@code libais_capture.so} via {@code linker64} for Shark 6 / restricted SELinux units.
 */
public final class AisCaptureSidecarLauncher {

    private static final String TAG = "AisCaptureSidecar";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static volatile Process sSidecarProcess;

    private AisCaptureSidecarLauncher() {}

    public static boolean canLaunch() {
        if (!vendorAisPresent()) {
            return false;
        }
        return resolveSidecarBinary() != null;
    }

    public static synchronized void ensureRunning() {
        killStaleCaptureProcesses();

        if (sSidecarProcess != null && sSidecarProcess.isAlive()) {
            return;
        }

        String bin = resolveSidecarBinary();
        if (bin == null) {
            logger.error("libais_capture.so not found under APK lib/arm64");
            return;
        }

        File sidecarFile = new File(bin);
        if (!sidecarFile.canExecute() && !sidecarFile.setExecutable(true, false)) {
            logger.warn("Could not chmod libais_capture.so executable: " + bin);
        }

        String linker = new File("/system/bin/linker64").exists()
                ? "/system/bin/linker64"
                : "/system/bin/linker";
        String logPath = ScratchPaths.path("ais_capture.log");
        int defaultCam = DiLink5PlatformHelper.defaultAisCameraId();
        String mosaicArg = DiLink5PlatformHelper.mosaicArg();

        List<String> cmd = new ArrayList<>();
        cmd.add(linker);
        cmd.add(bin);
        cmd.add(logPath);
        cmd.add(String.valueOf(defaultCam));
        cmd.add(mosaicArg);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            sSidecarProcess = pb.start();
            logger.info("AIS sidecar started: " + linker + " " + bin
                    + " defaultCam=" + defaultCam + " log=" + logPath);

            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(sSidecarProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.info("[ais_capture] " + line);
                    }
                } catch (Throwable ignored) {}
            }, "ais-capture-log");
            drain.setDaemon(true);
            drain.start();
        } catch (Throwable t) {
            logger.error("Failed to start AIS sidecar: " + t.getMessage(), t);
            sSidecarProcess = null;
        }
    }

    public static synchronized void stop() {
        if (sSidecarProcess != null) {
            sSidecarProcess.destroy();
            sSidecarProcess = null;
        }
        killStaleCaptureProcesses();
    }

    /** Kill stale capture helpers (not the running camera daemon). */
    public static void killStaleCaptureProcesses() {
        String cmd = ScratchPaths.prepareExecShell(
                "pkill -9 -f libais_capture 2>/dev/null; "
                        + "pkill -9 -f qcarcam_test 2>/dev/null");
        execQuiet(cmd);
    }

    /** Full cleanup after APK install (external/watchdog use). */
    public static void killStaleSidecars() {
        String cmd = ScratchPaths.prepareExecShell(
                "pkill -9 -f libais_capture 2>/dev/null; "
                        + "pkill -9 -f byd_cam_daemon 2>/dev/null; "
                        + "pkill -9 -f qcarcam_test 2>/dev/null");
        execQuiet(cmd);
    }

    /**
     * Resolve {@code /data/app/~~…/com.overdrive.app-…/lib/arm64/libais_capture.so}
     * from {@code pm path com.overdrive.app}.
     */
    public static String resolveSidecarBinary() {
        String pmPath = execQuiet(ScratchPaths.prepareExecShell(
                "pm path com.overdrive.app 2>/dev/null | head -1"));
        if (pmPath == null || pmPath.isEmpty()) {
            return null;
        }
        pmPath = pmPath.trim();
        if (pmPath.startsWith("package:")) {
            pmPath = pmPath.substring("package:".length());
        }
        if (!pmPath.endsWith("/base.apk")) {
            return null;
        }
        String libDir = pmPath.substring(0, pmPath.length() - "/base.apk".length()) + "/lib/arm64";
        File candidate = new File(libDir, "libais_capture.so");
        if (candidate.isFile()) {
            return candidate.getAbsolutePath();
        }
        return null;
    }

    private static boolean vendorAisPresent() {
        return new File("/vendor/lib64/libais_client.so").exists();
    }

    private static String execQuiet(String shellCmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", shellCmd});
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
            return out.toString().trim();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
