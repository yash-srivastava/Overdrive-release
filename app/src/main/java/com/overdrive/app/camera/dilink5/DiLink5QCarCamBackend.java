package com.overdrive.app.camera.dilink5;

import androidx.annotation.Keep;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backend driver for BYD DiLink 5.0 (Qualcomm Snapdragon SA8155P / QCarCam / AIS).
 * Communicates directly with /vendor/lib64/libais_client.so to stream raw camera frames
 * (1920x1300 @ 30 FPS) for OverDrive's Dashcam, Sentry, and EGL video encoding pipelines.
 */
@Keep
public class DiLink5QCarCamBackend {

    private static final String TAG = "DiLink5QCarCam";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static volatile Boolean sSupported = null;

    static {
        try {
            try {
                System.loadLibrary("fast_cam_client");
            } catch (Throwable ignored) {}
            System.loadLibrary("surveillance");
        } catch (Throwable t) {
            try {
                try {
                    System.load("/data/local/tmp/libfast_cam_client.so");
                } catch (Throwable ignored) {}
                System.load("/data/local/tmp/libsurveillance.so");
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
            sSupported = nativeIsSupported();
        } catch (Throwable t) {
            logger.warn("nativeIsSupported check failed: " + t.getMessage());
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

    private static class ExtractResult {
        final boolean found;
        final boolean updated;
        ExtractResult(boolean found, boolean updated) {
            this.found = found;
            this.updated = updated;
        }
    }

    private static final java.util.Set<DiLink5QCarCamBackend> sActiveInstances =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public static boolean hasActiveStreamingBackend() {
        for (DiLink5QCarCamBackend backend : sActiveInstances) {
            if (backend.isStreaming.get() || backend.nativeHandle != 0) {
                return true;
            }
        }
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                    com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            if (pipeline != null && pipeline.isRunning()) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            com.overdrive.app.camera.OemDashcamPipeline oem =
                    com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
            if (oem != null && oem.isRunning()) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static volatile boolean sShutdownHookRegistered = false;

    private static void gracefulStopProcess(String processPattern) {
        try {
            // Stage 1: Graceful SIGTERM so Qualcomm AIS / QCarCam can release DMA buffers and close camera session
            Runtime.getRuntime().exec(new String[]{"pkill", "-15", "-f", processPattern}).waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS);

            // Check if process has exited
            Process checkProc = Runtime.getRuntime().exec(new String[]{"pgrep", "-f", processPattern});
            boolean exited = false;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(checkProc.getInputStream()))) {
                if (reader.readLine() == null) {
                    exited = true;
                }
            } catch (Throwable ignored) {}

            // Stage 2: Fallback to SIGKILL only if process is still lingering
            if (!exited) {
                logger.warn("Process " + processPattern + " did not terminate on SIGTERM, forcing SIGKILL");
                Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-f", processPattern}).waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Throwable t) {
            logger.warn("Error in gracefulStopProcess(" + processPattern + "): " + t.getMessage());
        }
    }

    private static volatile boolean sYieldedForReverse = false;
    private static final Object sGearLock = new Object();
    private static java.util.concurrent.ScheduledExecutorService sGearResumeExecutor = null;
    private static volatile boolean sGearListenerRegistered = false;

    private static volatile boolean sYieldedForAccOn = false;
    private static final Object sAccLock = new Object();
    private static java.util.concurrent.ScheduledExecutorService sAccResumeExecutor = null;
    private static volatile boolean sAccListenerRegistered = false;
    private static final Object sSpawnLock = new Object();

    private static synchronized void ensureAccListenerRegistered() {
        if (!sAccListenerRegistered) {
            sAccListenerRegistered = true;
            try {
                com.overdrive.app.monitor.AccMonitor.addListener(isAccOn -> {
                    onAccStateChanged(isAccOn);
                });
            } catch (Throwable t) {
                logger.warn("Failed to register AccMonitor listener: " + t.getMessage());
            }
        }
    }

    public static void onAccStateChanged(boolean isAccOn) {
        if (isAccOn) {
            logger.info("ACC switched to ON: gracefully yielding Qualcomm AIS capture to system services (AVM, radar, SystemUI)...");
            sYieldedForAccOn = true;
            synchronized (sAccLock) {
                if (sAccResumeExecutor != null) {
                    sAccResumeExecutor.shutdownNow();
                    sAccResumeExecutor = null;
                }
            }
            terminateHardwareProcess();
            scheduleResumeAfterAccOn();
        } else {
            logger.info("ACC switched to OFF: entering Sentry mode capture");
            sYieldedForAccOn = false;
            synchronized (sAccLock) {
                if (sAccResumeExecutor != null) {
                    sAccResumeExecutor.shutdownNow();
                    sAccResumeExecutor = null;
                }
            }
            if (hasActiveStreamingBackend()) {
                ensureHardwareProcess();
            }
        }
    }

    private static void scheduleResumeAfterAccOn() {
        synchronized (sAccLock) {
            if (sAccResumeExecutor != null) {
                sAccResumeExecutor.shutdownNow();
            }
            sAccResumeExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fast-cam-acc-resume");
                t.setDaemon(true);
                return t;
            });
            // 2000 ms cooperative yield allows native BYD AVM / camera HAL and SystemUI to initialize cleanly
            sAccResumeExecutor.schedule(() -> {
                sYieldedForAccOn = false;
                if (hasActiveStreamingBackend()) {
                    int curGear = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear();
                    if (curGear == com.overdrive.app.monitor.GearMonitor.GEAR_R || sYieldedForReverse) {
                        logger.info("Capture resumption after ACC-ON deferred: vehicle is in REVERSE");
                        return;
                    }
                    logger.info("Resuming Qualcomm fast_cam_capture hardware pipeline after ACC-ON yield...");
                    ensureHardwareProcess();
                }
            }, 2000, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private static synchronized void ensureGearListenerRegistered() {
        if (!sGearListenerRegistered) {
            sGearListenerRegistered = true;
            try {
                com.overdrive.app.monitor.GearMonitor.getInstance().addListener((oldGear, newGear) -> {
                    onGearChanged(oldGear, newGear);
                });
            } catch (Throwable t) {
                logger.warn("Failed to register GearMonitor listener: " + t.getMessage());
            }
        }
    }

    public static void onGearChanged(int oldGear, int newGear) {
        if (newGear == com.overdrive.app.monitor.GearMonitor.GEAR_R) {
            logger.info("Gear shifted to REVERSE: gracefully yielding Qualcomm AIS capture to native BYD 360 app...");
            sYieldedForReverse = true;
            synchronized (sGearLock) {
                if (sGearResumeExecutor != null) {
                    sGearResumeExecutor.shutdownNow();
                    sGearResumeExecutor = null;
                }
            }
            terminateHardwareProcess();
        } else if (oldGear == com.overdrive.app.monitor.GearMonitor.GEAR_R) {
            logger.info("Gear shifted from REVERSE to " + com.overdrive.app.monitor.GearMonitor.gearToString(newGear) + ": scheduling capture resumption in 400ms...");
            sYieldedForReverse = false;
            scheduleResumeAfterReverse();
        }
    }

    private static void scheduleResumeAfterReverse() {
        synchronized (sGearLock) {
            if (sGearResumeExecutor != null) {
                sGearResumeExecutor.shutdownNow();
            }
            sGearResumeExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fast-cam-reverse-resume");
                t.setDaemon(true);
                return t;
            });
            sGearResumeExecutor.schedule(() -> {
                int curGear = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear();
                if (curGear == com.overdrive.app.monitor.GearMonitor.GEAR_R) {
                    logger.info("Capture resumption cancelled: vehicle is still in REVERSE");
                    return;
                }
                if (hasActiveStreamingBackend()) {
                    logger.info("Resuming Qualcomm fast_cam_capture hardware pipeline after reverse yield...");
                    ensureHardwareProcess();
                }
            }, 400, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    public static synchronized void terminateHardwareProcess() {
        if (sHardwareProcess != null) {
            try {
                sHardwareProcess.destroy(); // sends SIGTERM
                sHardwareProcess.waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {}
            if (sHardwareProcess != null) {
                try {
                    sHardwareProcess.exitValue();
                } catch (IllegalThreadStateException lingering) {
                    try {
                        sHardwareProcess.destroyForcibly();
                    } catch (Throwable ignored) {}
                }
            }
            sHardwareProcess = null;
        }
        gracefulStopProcess("fast_cam_capture");
    }

    private static void ensureHardwareProcess() {
        synchronized (sSpawnLock) {
            try {
                ensureGearListenerRegistered();
                ensureAccListenerRegistered();

                if (sYieldedForAccOn) {
                    logger.info("Skipping ensureHardwareProcess: vehicle currently yielding for ACC-ON transition");
                    return;
                }

                int curGear = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear();
                if (sYieldedForReverse || curGear == com.overdrive.app.monitor.GearMonitor.GEAR_R) {
                    logger.info("Skipping ensureHardwareProcess: vehicle currently in REVERSE (yielding to native AVM)");
                    return;
                }

                // Ensure fast_cam_capture binary exists in /data/local/tmp and is up to date with APK assets
                String binPath = "/data/local/tmp/fast_cam_capture";
                java.io.File binFile = new java.io.File(binPath);
                boolean wasUpdated = ensureDaemonBinaryExtracted(binFile);

                // If we already hold an alive supervised process and the binary wasn't updated, keep it
                if (sHardwareProcess != null) {
                    boolean isAlive = false;
                    try {
                        sHardwareProcess.exitValue();
                    } catch (IllegalThreadStateException e) {
                        isAlive = true; // Process is still running
                    }
                    if (isAlive && !wasUpdated) {
                        return;
                    }
                }

                logger.info("Preparing fresh Qualcomm fast_cam_capture hardware capture supervisor...");

                // Terminate any existing or orphan instances to prevent duplicate services
                terminateHardwareProcess();
                Thread.sleep(300);

                if (binFile.exists()) {
                    binFile.setReadable(true, false);
                    binFile.setExecutable(true, false);
                }

                String camArgs = getCameraMappingArgs();
                ProcessBuilder pb = new ProcessBuilder(
                        "/system/bin/sh", "-c",
                        "export LD_LIBRARY_PATH=/vendor/lib64:/system/lib64:/data/local/tmp && exec " + binPath + " " + camArgs + " --socket @fast_cam.sock --time 0"
                );
                pb.redirectErrorStream(true);
                sHardwareProcess = pb.start();

                // Register shutdown hook once so termination on daemon shutdown or reinstall is guaranteed
                if (!sShutdownHookRegistered) {
                    sShutdownHookRegistered = true;
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        terminateHardwareProcess();
                    }, "fast-cam-shutdown-hook"));
                }

                // Asynchronously drain stdout/stderr to prevent pipe buffer saturation (64KB deadlock)
                final Process proc = sHardwareProcess;
                Thread drainer = new Thread(() -> {
                    try (java.io.BufferedReader streamReader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                        String drainLine;
                        while ((drainLine = streamReader.readLine()) != null) {
                            logger.info("[FastCamProc] " + drainLine);
                        }
                    } catch (Throwable ignored) {}

                    int exitCode = -1;
                    try {
                        exitCode = proc.exitValue();
                    } catch (Throwable ignored) {}

                    if (sHardwareProcess == proc) {
                        sHardwareProcess = null;
                    } else if (sHardwareProcess != null) {
                        // Another instance is already running, suppress duplicate recovery
                        return;
                    }

                    if (exitCode == 42) {
                        logger.info("Qualcomm fast_cam_capture exited cleanly due to hardware preemption (exit code 42)");
                    }

                    if (hasActiveStreamingBackend()) {
                        if (sYieldedForAccOn) {
                            logger.info("Suppressing auto-recovery: vehicle is yielding for ACC-ON transition");
                            return;
                        }

                        int gear = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear();
                        if (gear == com.overdrive.app.monitor.GearMonitor.GEAR_R || sYieldedForReverse) {
                            logger.info("Suppressing auto-recovery: vehicle is in REVERSE, waiting for gear change to resume");
                            return;
                        }

                        logger.warn("Qualcomm fast_cam_capture process exited unexpectedly (code " + exitCode + "). Triggering auto-recovery supervisor in 500ms...");
                        try {
                            Thread.sleep(500);
                            if (hasActiveStreamingBackend()) {
                                ensureHardwareProcess();
                            }
                        } catch (Throwable t) {
                            logger.error("Auto-recovery supervisor failed: " + t.getMessage(), t);
                        }
                    }
                }, "fast-cam-capture-drainer");
                drainer.setDaemon(true);
                drainer.start();

                logger.info("Qualcomm fast_cam_capture hardware pipeline started successfully via supervisor.");
            } catch (Throwable t) {
                logger.error("Failed to start Qualcomm fast_cam_capture hardware supervisor: " + t.getMessage(), t);
            }
        }
    }

    private static boolean ensureDaemonBinaryExtracted(java.io.File dst) {
        try {
            // 0. Try standard Android Context assets if available
            try {
                android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                if (ctx != null) {
                    long assetLen = -1;
                    try (android.content.res.AssetFileDescriptor afd = ctx.getAssets().openFd("dilink5/fast_cam_capture")) {
                        assetLen = afd.getLength();
                    } catch (Throwable ignored) {}

                    if (dst.exists() && dst.length() > 0 && assetLen > 0 && dst.length() == assetLen) {
                        dst.setReadable(true, false);
                        dst.setExecutable(true, false);
                        return false;
                    }

                    java.io.File tmpDst = new java.io.File(dst.getParentFile(), dst.getName() + ".tmp." + System.currentTimeMillis());
                    try (java.io.InputStream in = ctx.getAssets().open("dilink5/fast_cam_capture");
                         java.io.OutputStream out = new java.io.FileOutputStream(tmpDst)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                        out.flush();
                    }
                    if (tmpDst.exists() && tmpDst.length() > 0) {
                        if (dst.exists() && dst.length() == tmpDst.length()) {
                            tmpDst.delete();
                            dst.setReadable(true, false);
                            dst.setExecutable(true, false);
                            return false;
                        }
                        if (dst.exists()) dst.delete();
                        tmpDst.renameTo(dst);
                        dst.setReadable(true, false);
                        dst.setExecutable(true, false);
                        logger.info("Extracted and deployed fast_cam_capture from Context assets (" + dst.length() + " bytes)");
                        return true;
                    }
                }
            } catch (Throwable t) {
                logger.warn("Context assets extraction skipped: " + t.getMessage());
            }

            // 1. Search via environment CLASSPATH (app_process daemon primary mechanism)
            String envClasspath = System.getenv("CLASSPATH");
            if (envClasspath != null && !envClasspath.isEmpty()) {
                for (String cpEntry : envClasspath.split(":")) {
                    if (cpEntry.endsWith(".apk") && (cpEntry.contains("com.overdrive.app") || new java.io.File(cpEntry).exists())) {
                        java.io.File apkFile = new java.io.File(cpEntry);
                        if (apkFile.exists() && apkFile.canRead()) {
                            ExtractResult res = extractFromZip(apkFile, "assets/dilink5/fast_cam_capture", dst);
                            if (res.found) {
                                if (res.updated) logger.info("Updated fast_cam_capture from CLASSPATH APK: " + cpEntry);
                                return res.updated;
                            }
                        }
                    }
                }
            }

            // 2. Check Context package code path
            try {
                android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                if (ctx != null) {
                    String pkgCodePath = ctx.getPackageCodePath();
                    if (pkgCodePath != null) {
                        java.io.File apkFile = new java.io.File(pkgCodePath);
                        if (apkFile.exists() && apkFile.canRead()) {
                            ExtractResult res = extractFromZip(apkFile, "assets/dilink5/fast_cam_capture", dst);
                            if (res.found) {
                                if (res.updated) logger.info("Updated fast_cam_capture from Context package code path: " + pkgCodePath);
                                return res.updated;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                logger.warn("Context package code path check skipped: " + t.getMessage());
            }

            // 3. Fallback: Query package manager path via shell
            try {
                Process pmProc = Runtime.getRuntime().exec(new String[]{"pm", "path", "com.overdrive.app"});
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(pmProc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("package:")) {
                            String apkPath = line.substring("package:".length()).trim();
                            java.io.File apkFile = new java.io.File(apkPath);
                            if (apkFile.exists() && apkFile.canRead()) {
                                ExtractResult res = extractFromZip(apkFile, "assets/dilink5/fast_cam_capture", dst);
                                if (res.found) {
                                    if (res.updated) logger.info("Updated fast_cam_capture from pm path: " + apkPath);
                                    return res.updated;
                                }
                            }
                        }
                    }
                }
                pmProc.waitFor();
            } catch (Throwable t) {
                logger.warn("pm path fallback skipped: " + t.getMessage());
            }

            // 4. Recursive scan in /data/app (handling Android 11+ ~~hash/ subdirectories)
            java.io.File dataApp = new java.io.File("/data/app");
            if (dataApp.exists() && dataApp.isDirectory()) {
                ExtractResult res = findAndExtractInDir(dataApp, dst, 0, 3);
                if (res.found) {
                    if (res.updated) logger.info("Updated fast_cam_capture via /data/app recursive scan");
                    return res.updated;
                }
            }

            // 5. Fallback: check java.class.path JVM property
            String propClasspath = System.getProperty("java.class.path");
            if (propClasspath != null && !propClasspath.isEmpty()) {
                for (String cpEntry : propClasspath.split(":")) {
                    if (cpEntry.endsWith(".apk") && new java.io.File(cpEntry).exists()) {
                        ExtractResult res = extractFromZip(new java.io.File(cpEntry), "assets/dilink5/fast_cam_capture", dst);
                        if (res.found) {
                            if (res.updated) logger.info("Updated fast_cam_capture from java.class.path: " + cpEntry);
                            return res.updated;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.warn("ensureDaemonBinaryExtracted failed: " + t.getMessage());
        }
        return false;
    }

    private static ExtractResult findAndExtractInDir(java.io.File dir, java.io.File dst, int depth, int maxDepth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || depth > maxDepth) return new ExtractResult(false, false);
        java.io.File[] files = dir.listFiles();
        if (files == null) return new ExtractResult(false, false);
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                ExtractResult res = findAndExtractInDir(f, dst, depth + 1, maxDepth);
                if (res.found) return res;
            } else if (f.getName().endsWith(".apk") && f.getAbsolutePath().contains("com.overdrive.app")) {
                ExtractResult res = extractFromZip(f, "assets/dilink5/fast_cam_capture", dst);
                if (res.found) return res;
            }
        }
        return new ExtractResult(false, false);
    }

    private static ExtractResult extractFromZip(java.io.File zipFile, String entryPath, java.io.File dst) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile)) {
            java.util.zip.ZipEntry entry = zf.getEntry(entryPath);
            if (entry != null) {
                // If destination already exists with the exact same non-zero size, no need to re-write
                if (dst.exists() && dst.length() == entry.getSize() && entry.getSize() > 0) {
                    dst.setReadable(true, false);
                    dst.setExecutable(true, false);
                    return new ExtractResult(true, false);
                }
                // Overwrite stale/mismatched file
                java.io.File tmpDst = new java.io.File(dst.getParentFile(), dst.getName() + ".tmp." + System.currentTimeMillis());
                try (java.io.InputStream in = zf.getInputStream(entry);
                     java.io.OutputStream out = new java.io.FileOutputStream(tmpDst)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    out.flush();
                }
                if (tmpDst.exists() && tmpDst.length() > 0) {
                    if (dst.exists()) dst.delete();
                    tmpDst.renameTo(dst);
                    dst.setReadable(true, false);
                    dst.setExecutable(true, false);
                    logger.info("Extracted and deployed " + dst.getName() + " (" + dst.length() + " bytes, mode 755)");
                    return new ExtractResult(true, true);
                }
            }
        } catch (Throwable t) {
            logger.warn("extractFromZip failed for " + entryPath + ": " + t.getMessage());
        }
        return new ExtractResult(false, false);
    }

    private static void copyFile(java.io.File src, java.io.File dst) {
        try {
            if (dst.exists() && dst.length() == src.length() && src.length() > 0) {
                dst.setReadable(true, false);
                dst.setExecutable(true, false);
                return;
            }
            try (java.io.InputStream in = new java.io.FileInputStream(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.flush();
            }
            dst.setReadable(true, false);
            dst.setExecutable(true, false);
            logger.info("Copied and deployed " + dst.getName() + " (" + dst.length() + " bytes, mode 755)");
        } catch (Throwable ignored) {}
    }

    public static synchronized void stopHardwareProcess() {
        try {
            terminateHardwareProcess();
            gracefulStopProcess("qcarcam_test");
            logger.info("Qualcomm fast_cam_capture hardware supervisor stopped.");
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
            nativeHandle = nativeInit(cameraId);
            if (nativeHandle == 0) {
                logger.error("nativeInit(" + cameraId + ") failed to open camera handle.");
                return false;
            }
            sActiveInstances.add(this);
            logger.info("DiLink 5 QCarCam handle opened successfully: 0x" + Long.toHexString(nativeHandle));
            return true;
        } catch (Throwable t) {
            logger.error("Error opening DiLink 5 QCarCam backend", t);
            return false;
        }
    }

    public synchronized boolean start() {
        ensureHardwareProcess();
        if (nativeHandle == 0 && !open()) return false;
        if (isStreaming.get()) return true;

        try {
            boolean ok = nativeStart(nativeHandle);
            if (ok) {
                isStreaming.set(true);
                sActiveInstances.add(this);
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
        // Zero-copy GL texture binding (bindLatestFrame) replaces Surface/ANativeWindow
        // to completely eliminate Qualcomm SA8155P hwcomposer crashes.
        return start();
    }

    public interface FrameListener {
        void onFrameAvailable(long timestampNs);
    }

    private static volatile FrameListener sFrameListener = null;

    public static void setFrameListener(FrameListener listener) {
        sFrameListener = listener;
    }

    /**
     * Called from native C++ streamClientLoop when a new zero-copy hardware frame is fully ready.
     */
    public static void onNativeFrameAvailable(long timestampNs) {
        FrameListener listener = sFrameListener;
        if (listener != null) {
            listener.onFrameAvailable(timestampNs);
        }
    }

    public static long getLatestFrameTimestamp() {
        try {
            return nativeGetLatestFrameTimestamp();
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static boolean isYielded() {
        return sYieldedForReverse || sYieldedForAccOn;
    }

    /**
     * Binds the latest available camera frame directly to the calling thread's
     * GL_TEXTURE_EXTERNAL_OES texture via zero-copy EGLImage/AHardwareBuffer.
     * Bypasses Android Surface, SurfaceFlinger, and HWC completely.
     * Returns the bound texture ID (>0), or 0 on failure.
     */
    public static int bindLatestFrame(int textureId) {
        try {
            return nativeBindLatestFrame(textureId);
        } catch (Throwable t) {
            return 0;
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
        sActiveInstances.remove(this);
        if (sActiveInstances.isEmpty()) {
            terminateHardwareProcess();
        }
    }

    public boolean isStreaming() {
        return isStreaming.get();
    }

    public static void setActiveCamera(int camIdx) {
        try {
            nativeSetActiveCamera(camIdx);
            logger.info("Switched active QCarCam camera to: " + camIdx);
        } catch (Throwable t) {
            logger.warn("Failed to set active camera: " + t.getMessage());
        }
    }

    public static void set4KUltraEnabled(boolean enabled) {
        setActiveCamera(enabled ? 5 : 4);
    }

    public static void setCameraMapping(int front, int right, int rear, int left, int dashcam) {
        try {
            nativeSetCameraMapping(front, right, rear, left, dashcam);
            logger.info("Configured camera hardware mapping: Front=" + front + ", Right=" + right + ", Rear=" + rear + ", Left=" + left + ", Dashcam=" + dashcam);
        } catch (Throwable t) {
            logger.warn("Failed to set camera mapping: " + t.getMessage());
        }
    }

    public static String getCameraMappingProperty() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", "persist.overdrive.cams"});
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public static boolean setCameraMappingProperty(String camsCsv) {
        String val = camsCsv != null ? camsCsv.trim() : "";
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"setprop", "persist.overdrive.cams", val});
            p.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            logger.info("Executed setprop persist.overdrive.cams " + val + " (exitCode=" + p.exitValue() + ")");
            if (!val.isEmpty()) {
                parseAndApplyMapping(val);
            }
            return true;
        } catch (Throwable t) {
            logger.warn("Failed to execute setprop persist.overdrive.cams: " + t.getMessage());
            return false;
        }
    }

    public static String getCameraMappingArgs() {
        // Check for manual system property override first: persist.overdrive.cams
        String propCams = getCameraMappingProperty();
        if (!propCams.isEmpty()) {
            logger.info("Using camera mapping override from persist.overdrive.cams: " + propCams);
            parseAndApplyMapping(propCams);
            return "--cams " + propCams;
        }

        // Automatic platform detection: BYD Shark (DMO / SA8155P) via Build or selected vehicle model
        String model = android.os.Build.MODEL != null ? android.os.Build.MODEL.toLowerCase() : "";
        String product = android.os.Build.PRODUCT != null ? android.os.Build.PRODUCT.toLowerCase() : "";
        String configuredModel = "";
        try {
            org.json.JSONObject vehicle = com.overdrive.app.config.UnifiedConfigManager.getVehicle();
            if (vehicle != null) {
                configuredModel = vehicle.optString("modelId", "").toLowerCase();
            }
        } catch (Throwable ignored) {}

        if (configuredModel.contains("shark") || model.contains("shark") || product.contains("shark") || model.contains("dmo") || product.contains("dmo")) {
            logger.info("Detected BYD Shark platform (model=" + model + ", product=" + product + ", config=" + configuredModel + "): configuring camera mapping 8,9,5,4");
            try {
                nativeSetCameraMapping(8, 9, 5, 4, -1);
            } catch (Throwable t) {
                logger.warn("Failed to set native camera mapping: " + t.getMessage());
            }
            return "--cams 8,9,5,4";
        }

        // Standard DiLink 5.0 default (Sealion 7, Song, Han, etc.)
        try {
            nativeSetCameraMapping(0, 1, 2, 3, -1);
        } catch (Throwable t) {
            logger.warn("Failed to set native camera mapping: " + t.getMessage());
        }
        return "--cams 0,1,2,3";
    }

    private static void parseAndApplyMapping(String csv) {
        try {
            String[] parts = csv.split(",");
            int front = parts.length > 0 ? Integer.parseInt(parts[0].trim()) : 0;
            int right = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
            int rear  = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 2;
            int left  = parts.length > 3 ? Integer.parseInt(parts[3].trim()) : 3;
            int dashcam = parts.length > 4 ? Integer.parseInt(parts[4].trim()) : -1;
            nativeSetCameraMapping(front, right, rear, left, dashcam);
        } catch (Throwable t) {
            logger.warn("Error parsing camera mapping CSV '" + csv + "': " + t.getMessage());
        }
    }

    // --- Native JNI Interface ---
    private static native boolean nativeIsSupported();
    private static native void nativeSetActiveCamera(int camIdx);
    private static native void nativeSetCameraMapping(int front, int right, int rear, int left, int dashcam);
    private static native int nativeBindLatestFrame(int textureId);
    private static native long nativeGetLatestFrameTimestamp();
    private native long nativeInit(int inputId);
    private native boolean nativeStart(long handle);
    private native boolean nativeStartSurface(android.view.Surface surface);
    private native boolean nativeStop(long handle);
    private native void nativeRelease(long handle);
}
