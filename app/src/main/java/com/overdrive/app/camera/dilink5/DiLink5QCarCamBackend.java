package com.overdrive.app.camera.dilink5;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backend driver for BYD DiLink 5.0 (Qualcomm Snapdragon SA8155P / QCarCam / AIS).
 *
 * <p>Sealion 7: {@code qcarcam_test} + {@code LD_PRELOAD} hook.
 * Shark 6: {@code linker64} + {@code libais_capture.so} AIS sidecar over {@code @dilink5_cam}.
 */
public class DiLink5QCarCamBackend {

    private static final String TAG = "DiLink5QCarCam";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static volatile Boolean sSupported = null;

    static {
        try {
            System.loadLibrary("surveillance");
        } catch (Throwable t) {
            try {
                System.load(ScratchPaths.path("libsurveillance.so"));
            } catch (Throwable t2) {
                logger.warn("Failed to load libsurveillance.so: " + t2.getMessage());
            }
        }
    }

    private long nativeHandle = 0;
    private final int cameraId;
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private static volatile Process sHardwareProcess = null;

    public static boolean isSupported() {
        if (sSupported != null) return sSupported;
        try {
            if (DiLink5PlatformHelper.usesAisSidecar()) {
                sSupported = AisCaptureSidecarLauncher.canLaunch();
            } else {
                sSupported = nativeIsSupported();
            }
        } catch (Throwable t) {
            logger.warn("isSupported check failed: " + t.getMessage());
            sSupported = false;
        }
        return sSupported;
    }

    public static void main(String[] args) {
        System.out.println("[+] DiLink5QCarCamBackend.isSupported() = " + isSupported());
    }

    public DiLink5QCarCamBackend(int cameraId) {
        this.cameraId = cameraId;
    }

    private static synchronized void ensureHardwareProcess() {
        if (DiLink5PlatformHelper.usesAisSidecar()) {
            AisCaptureSidecarLauncher.ensureRunning();
            return;
        }
        ensureQcarcamHookProcess();
    }

    /** Sealion 7 path: vendor qcarcam_test + LD_PRELOAD hook. */
    private static synchronized void ensureQcarcamHookProcess() {
        try {
            Process checkPgrep = Runtime.getRuntime().exec(new String[]{"pgrep", "-f", "qcarcam_test"});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(checkPgrep.getInputStream()));
            String line = reader.readLine();
            checkPgrep.waitFor();
            if (line != null && !line.trim().isEmpty()) {
                logger.info("Qualcomm QCarCam hardware pipeline already running (PID: " + line.trim() + ")");
                return;
            }

            logger.info("Starting Qualcomm QCarCam hardware capture supervisor (SL7 hook)...");

            java.io.File cfgFile = new java.io.File(ScratchPaths.path("4cam.xml"));
            if (!cfgFile.exists()) {
                String defaultXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<qcarcam_inputs>\n"
                        + "    <input_device>\n"
                        + "        <properties input_id=\"0\"/>\n"
                        + "        <display_setting display_id=\"0\"/>\n"
                        + "        <output_setting nbufs=\"5\"/>\n"
                        + "    </input_device>\n"
                        + "    <input_device>\n"
                        + "        <properties input_id=\"1\"/>\n"
                        + "        <display_setting display_id=\"0\"/>\n"
                        + "        <output_setting nbufs=\"5\"/>\n"
                        + "    </input_device>\n"
                        + "    <input_device>\n"
                        + "        <properties input_id=\"2\"/>\n"
                        + "        <display_setting display_id=\"0\"/>\n"
                        + "        <output_setting nbufs=\"5\"/>\n"
                        + "    </input_device>\n"
                        + "    <input_device>\n"
                        + "        <properties input_id=\"3\"/>\n"
                        + "        <display_setting display_id=\"0\"/>\n"
                        + "        <output_setting nbufs=\"5\"/>\n"
                        + "    </input_device>\n"
                        + "</qcarcam_inputs>\n";
                java.io.FileWriter writer = new java.io.FileWriter(cfgFile);
                writer.write(defaultXml);
                writer.close();
            }

            String hookPath = ScratchPaths.path("libhook_qcarcam.so");
            java.io.File hookFile = new java.io.File(hookPath);
            if (!hookFile.exists()) {
                java.io.File appHook = new java.io.File("/data/app", "libhook_qcarcam.so");
                if (appHook.exists()) {
                    copyFile(appHook, hookFile);
                }
            }

            String qcarcamBin = ScratchPaths.path("qcarcam_test");
            if (!new java.io.File(qcarcamBin).exists()) {
                qcarcamBin = "/vendor/bin/qcarcam_test";
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "LD_PRELOAD=" + hookPath + " " + qcarcamBin
                            + " -config=" + ScratchPaths.getDir() + "/4cam.xml"
            );
            pb.redirectErrorStream(true);
            sHardwareProcess = pb.start();
            logger.info("Qualcomm QCarCam hardware pipeline started via SL7 hook.");
        } catch (Throwable t) {
            logger.error("Failed to start QCarCam hook supervisor: " + t.getMessage(), t);
        }
    }

    private static void copyFile(java.io.File src, java.io.File dst) {
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } catch (Throwable ignored) {}
    }

    public static synchronized void stopHardwareProcess() {
        try {
            if (sHardwareProcess != null) {
                sHardwareProcess.destroy();
                sHardwareProcess = null;
            }
            if (DiLink5PlatformHelper.usesAisSidecar()) {
                AisCaptureSidecarLauncher.stop();
            } else {
                Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-f", "qcarcam_test"});
            }
            logger.info("DiLink 5 hardware capture supervisor stopped.");
        } catch (Throwable t) {
            logger.warn("Error stopping hardware supervisor: " + t.getMessage());
        }
    }

    public synchronized boolean open() {
        if (nativeHandle != 0) return true;
        if (!isSupported()) {
            logger.warn("DiLink 5 QCarCam backend is not supported on this platform.");
            return false;
        }

        ensureHardwareProcess();

        try {
            android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (ctx != null) {
                TsAvmCoordinator.getInstance(ctx).bind();
                TsAvmCoordinator.getInstance(ctx).startAvm();
            }
        } catch (Throwable t) {
            logger.warn("TsAvmCoordinator start error: " + t.getMessage());
        }

        try {
            nativeHandle = nativeInit(cameraId);
            if (nativeHandle == 0) {
                logger.error("nativeInit(" + cameraId + ") failed to open camera handle.");
                return false;
            }
            logger.info("DiLink 5 QCarCam handle opened successfully: 0x" + Long.toHexString(nativeHandle));
            return true;
        } catch (Throwable t) {
            logger.error("Error opening DiLink 5 QCarCam backend", t);
            return false;
        }
    }

    public synchronized boolean start() {
        if (nativeHandle == 0 && !open()) return false;
        if (isStreaming.get()) return true;

        try {
            boolean ok = nativeStart(nativeHandle);
            if (ok) {
                isStreaming.set(true);
                logger.info("DiLink 5 QCarCam stream started on camera " + cameraId);
            } else {
                logger.warn("nativeStart failed on camera " + cameraId);
            }
            return ok;
        } catch (Throwable t) {
            logger.error("Error starting DiLink 5 QCarCam stream", t);
            return false;
        }
    }

    public synchronized boolean startSurface(android.view.Surface surface) {
        if (nativeHandle == 0 && !open()) return false;
        if (isStreaming.get()) return true;

        try {
            boolean ok = nativeStartSurface(surface);
            if (ok) {
                isStreaming.set(true);
                logger.info("DiLink 5 QCarCam stream started on Surface for camera " + cameraId);
            } else {
                logger.warn("nativeStartSurface failed on camera " + cameraId);
            }
            return ok;
        } catch (Throwable t) {
            logger.error("Error starting DiLink 5 QCarCam stream on Surface", t);
            return false;
        }
    }

    public synchronized void stop() {
        if (nativeHandle != 0 && isStreaming.getAndSet(false)) {
            try {
                nativeStop(nativeHandle);
                logger.info("DiLink 5 QCarCam stream stopped on camera " + cameraId);
            } catch (Throwable t) {
                logger.warn("Error stopping DiLink 5 QCarCam stream: " + t.getMessage());
            }
        }
    }

    public synchronized void close() {
        stop();
        if (nativeHandle != 0) {
            try {
                nativeRelease(nativeHandle);
                logger.info("DiLink 5 QCarCam handle closed.");
            } catch (Throwable t) {
                logger.warn("Error closing DiLink 5 QCarCam: " + t.getMessage());
            }
            nativeHandle = 0;
        }
    }

    public boolean isStreaming() {
        return isStreaming.get();
    }

    public static void setActiveCamera(int camIdx) {
        try {
            nativeSetActiveCamera(camIdx);
            logger.info("Switched active QCarCam camera to AIS byte: " + camIdx);
        } catch (Throwable t) {
            logger.warn("Failed to set active camera: " + t.getMessage());
        }
    }

    // --- Native JNI Interface ---
    private static native boolean nativeIsSupported();
    private static native void nativeSetActiveCamera(int camIdx);
    private native long nativeInit(int inputId);
    private native boolean nativeStart(long handle);
    private native boolean nativeStartSurface(android.view.Surface surface);
    private native boolean nativeStop(long handle);
    private native void nativeRelease(long handle);
}
