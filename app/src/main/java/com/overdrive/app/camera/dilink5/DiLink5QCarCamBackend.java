package com.overdrive.app.camera.dilink5;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DiLink 5 camera backend using the owned fast_cam_capture sidecar.
 */
public class DiLink5QCarCamBackend {

    private static final String TAG = "DiLink5QCarCam";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String FAST_CAM_ASSET =
            "dilink5/fast_cam_capture";
    private static final String FAST_CAM_PATH =
            "/data/local/tmp/fast_cam_capture";
    private static final String LEGACY_QCARCAM_PATH =
            "/data/local/tmp/qcarcam_test";
    private static final String CAPTURE_FPS_PROPERTY =
            "persist.overdrive.fast_cam_fps";
    public static final String CONFIG_CAMERA_MAPPING_KEY =
            "dilink5CameraMapping";
    private static final int DEFAULT_CAPTURE_FPS = 30;
    /**
     * Deliberate producer self-exit after an AIS pipeline stall: newer
     * fast_cam_capture builds (public-repo lineage, commit ab1c3b8) exit
     * with this code when {@code qcarcam_get_frame} keeps returning ERR 12
     * instead of spinning against a wedged vendor camera server. The binary
     * currently shipped in assets predates that behavior and never emits it;
     * classifying it now keeps logs truthful the moment a stall-aware binary
     * lands (bench swap or a future asset refresh) and keeps producer health
     * exits out of crash forensics. Recovery handling is IDENTICAL to any
     * other producer death — client-side retire plus source-only reacquire —
     * only the diagnosis wording changes.
     */
    private static final int FAST_CAM_EXIT_AIS_STALL = 42;

    /** True when {@code exitCode} is the producer's deliberate AIS-stall
     *  self-exit ({@link #FAST_CAM_EXIT_AIS_STALL}), not a crash. */
    static boolean isAisStallSelfExit(int exitCode) {
        return exitCode == FAST_CAM_EXIT_AIS_STALL;
    }
    private static final long PROCESS_STOP_TIMEOUT_MS = 1_000L;
    private static final long NATIVE_RECOVERY_WAIT_TIMEOUT_MS = 12_000L;
    private static final long NATIVE_RETIREMENT_EXECUTION_BUDGET_MS =
            9_500L;
    private static final long NATIVE_OPERATION_BUDGET_MS = 3_000L;
    private static final long PROCESS_RETIREMENT_BUDGET_MS = 3_000L;
    private static final long OWNER_RETIREMENT_RETRY_BUDGET_MS = 22_000L;
    private static final long EXPECTED_STOP_RETRY_WINDOW_MS = 24_000L;
    private static final long PROCESS_START_CHECK_MS = 300L;
    private static final long HARDWARE_RELEASE_SETTLE_MS = 1_000L;
    private static final SecureRandom CHANNEL_RANDOM = new SecureRandom();
    private static volatile Boolean sSupported;
    private static volatile Process sHardwareProcess;
    private static volatile int sHardwarePid = -1;
    private static volatile String sSocketPath;
    private static volatile String sHardwareCameraIds;
    private static volatile int sHardwareCaptureFps;
    private static volatile long sHardwareAcquisitionGeneration =
            Long.MIN_VALUE;
    private static volatile long sHardwareOwnerToken = Long.MIN_VALUE;
    private static volatile boolean sHardwareSourcePublished;
    private static volatile boolean sHardwareSourceClaimed;
    private static final HardwareStartupBarrier sHardwareStartup =
            new HardwareStartupBarrier();
    private static volatile long sSafetyLeaseOwnerToken =
            Long.MIN_VALUE;
    private static volatile long sSafetyLeaseGeneration =
            Long.MIN_VALUE;
    private static volatile boolean sNativeLibrariesLoaded;
    private static final java.util.concurrent.atomic.AtomicLong
            sAcquisitionGeneration =
                    new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong
            sNextSessionToken =
                    new java.util.concurrent.atomic.AtomicLong();
    private static final AtomicBoolean sConfiguredInputReservation =
            new AtomicBoolean(false);
    private static final AtomicBoolean sNativeCleanupPending =
            new AtomicBoolean(false);
    private static final AtomicBoolean
            sNativeBridgeReleasedPendingProcess =
                    new AtomicBoolean(false);
    private static final AtomicBoolean sNativeRecoveryInProgress =
            new AtomicBoolean(false);
    private static volatile long sNativeRecoveryDeadlineElapsedMs =
            Long.MIN_VALUE;
    private static final AtomicBoolean sNativeSessionOwned =
            new AtomicBoolean(false);
    private static volatile long sNativeOwnerToken = Long.MIN_VALUE;
    private static volatile DiLink5QCarCamBackend sNativeOwnerBackend;
    private static final AtomicBoolean sGlResourcesReleased =
            new AtomicBoolean(true);
    private static volatile long sGlResourcesOwnerToken = Long.MIN_VALUE;
    // DI5 no longer drives the vendor com.ts.avm service (no start/stop/probe
    // Binder calls from the daemon). The AIS server streams the QCarCam inputs
    // to fast_cam_capture regardless of the OEM AVM state — field logs show
    // full-rate capture with the AVM "start" never confirmed — and every
    // startAvm()/stopAvm() poke was a race against the OEM's own AVM usage.
    private static final Object EXPECTED_STOP_LOCK = new Object();
    private static final java.util.IdentityHashMap<Process, ExpectedStopMarker>
            sExpectedStopProcesses = new java.util.IdentityHashMap<>();
    private static volatile FrameListener sFrameListener;
    private static volatile long sFrameListenerOwnerToken = Long.MIN_VALUE;

    /**
     * Tracks the otherwise invisible interval between ProcessBuilder.start()
     * entering the kernel and the returned Process being published as the
     * generation-owned sidecar. Reverse cancellation invalidates acquisition
     * under the class monitor, then waits on this separate barrier without
     * holding that monitor. It can therefore never report a clean handoff
     * while a child is being spawned but is not yet present in
     * {@link #sHardwareProcess}.
     */
    static final class HardwareStartupBarrier {
        private long ownerToken = Long.MIN_VALUE;
        private long acquisitionGeneration = Long.MIN_VALUE;

        synchronized boolean begin(
                long requestedOwnerToken,
                long requestedGeneration) {
            if (ownerToken != Long.MIN_VALUE) return false;
            ownerToken = requestedOwnerToken;
            acquisitionGeneration = requestedGeneration;
            return true;
        }

        synchronized boolean matches(
                long expectedOwnerToken,
                long expectedGeneration) {
            return ownerToken == expectedOwnerToken
                    && acquisitionGeneration == expectedGeneration;
        }

        synchronized boolean isActive() {
            return ownerToken != Long.MIN_VALUE;
        }

        synchronized boolean isOwnedByAnother(long expectedOwnerToken) {
            return ownerToken != Long.MIN_VALUE
                    && ownerToken != expectedOwnerToken;
        }

        synchronized void clearIfOwned(
                long expectedOwnerToken,
                long expectedGeneration) {
            if (!matches(expectedOwnerToken, expectedGeneration)) return;
            ownerToken = Long.MIN_VALUE;
            acquisitionGeneration = Long.MIN_VALUE;
            notifyAll();
        }

        synchronized boolean awaitClear(long timeoutMs) {
            if (ownerToken == Long.MIN_VALUE) return true;
            if (timeoutMs <= 0L) return false;
            long deadlineNs = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (ownerToken != Long.MIN_VALUE) {
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0L) return false;
                long waitMs = TimeUnit.NANOSECONDS.toMillis(remainingNs);
                int waitNs = (int) (remainingNs
                        - TimeUnit.MILLISECONDS.toNanos(waitMs));
                try {
                    wait(waitMs, waitNs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Reentrant lifecycle serialization with a timed admission path for
     * recovery. Unlike a synchronized method, recovery can decline when the
     * remaining shared retirement budget is too small instead of blocking
     * indefinitely behind another open/close.
     */
    static final class LifecycleGate {
        private final ReentrantLock lock = new ReentrantLock();

        void lock() {
            lock.lock();
        }

        boolean tryLock(long timeoutMs) {
            if (timeoutMs < 0L) return false;
            try {
                return lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void unlock() {
            lock.unlock();
        }
    }

    public interface FrameListener {
        void onFrameAvailable(long timestampNs);
    }

    public boolean installFrameListener(FrameListener listener) {
        if (listener == null) return false;
        synchronized (DiLink5QCarCamBackend.class) {
            if (!isStartAllowed(startEpoch, acquisitionGeneration)
                    || sNativeOwnerToken != sessionToken
                    || sNativeOwnerBackend != this
                    || (sFrameListener != null
                            && sFrameListenerOwnerToken != sessionToken)) {
                return false;
            }
            sFrameListenerOwnerToken = sessionToken;
            sFrameListener = listener;
            return true;
        }
    }

    public void clearFrameListenerIfOwner() {
        synchronized (DiLink5QCarCamBackend.class) {
            if (sFrameListenerOwnerToken == sessionToken) {
                sFrameListener = null;
                sFrameListenerOwnerToken = Long.MIN_VALUE;
            }
        }
    }

    public static void onNativeFrameAvailable(long timestampNs) {
        long leaseGeneration;
        synchronized (DiLink5QCarCamBackend.class) {
            leaseGeneration = sSafetyLeaseGeneration;
        }
        DiLink5CameraSafety.markFirstFrame(leaseGeneration);
        FrameListener listener = sFrameListener;
        if (listener != null) listener.onFrameAvailable(timestampNs);
    }

    public static boolean isCaptureSuppressed() {
        return DiLink5CameraSafety.isCaptureSuppressed();
    }

    public int bindLatestFrameForOwner(int textureId) {
        if (textureId <= 0
                || !DiLink5Platform.isEnabled()
                || !ensureNativeLibrariesLoaded(null)) {
            return 0;
        }
        synchronized (DiLink5QCarCamBackend.class) {
            if (sNativeOwnerToken != sessionToken
                    || (sGlResourcesOwnerToken != Long.MIN_VALUE
                            && sGlResourcesOwnerToken != sessionToken)) {
                return 0;
            }
            sGlResourcesOwnerToken = sessionToken;
            sGlResourcesReleased.set(false);
        }
        try {
            return nativeBindLatestFrame(textureId);
        } catch (Throwable t) {
            logger.warn("Failed to bind latest fast camera frame: "
                    + t.getMessage());
            return 0;
        }
    }

    /** Must run on the owning GL thread while its EGL context is current. */
    public boolean releaseOwnedGlResources() {
        synchronized (DiLink5QCarCamBackend.class) {
            if (sGlResourcesOwnerToken != sessionToken) {
                return true;
            }
        }
        if (!sNativeLibrariesLoaded) {
            clearGlResourceOwnerIfOwned(sessionToken);
            return true;
        }
        try {
            boolean released = nativeReleaseGlResources();
            if (released) clearGlResourceOwnerIfOwned(sessionToken);
            return released;
        } catch (Throwable t) {
            logger.warn("Failed to release fast camera GL resources: "
                    + t.getMessage());
            return false;
        }
    }

    /**
     * Publish retirement intent before the owning GL thread starts tearing
     * down imported frames. The release guard can fail closed with status 125
     * while GL cleanup is still in progress; waiting until nativeRelease()
     * would let the output-drainer misclassify that intentional shutdown as a
     * crash and persist a boot-wide camera block.
     */
    public void markExpectedProcessRetirement() {
        Process process;
        synchronized (DiLink5QCarCamBackend.class) {
            process = sHardwareOwnerToken == sessionToken
                    ? sHardwareProcess
                    : null;
        }
        if (process != null && process.isAlive()) {
            markProcessExpectedStop(process, sessionToken);
        }
    }

    private static synchronized void clearGlResourceOwnerIfOwned(
            long ownerToken) {
        if (sGlResourcesOwnerToken == ownerToken) {
            sGlResourcesOwnerToken = Long.MIN_VALUE;
            sGlResourcesReleased.set(true);
        }
    }

    private boolean areOwnedGlResourcesReleased() {
        synchronized (DiLink5QCarCamBackend.class) {
            return sGlResourcesOwnerToken != sessionToken
                    || sGlResourcesReleased.get();
        }
    }

    public static synchronized boolean ensureNativeLibrariesLoaded(
            String nativeLibDir) {
        if (!DiLink5Platform.isSelected()) return true;
        if (sNativeLibrariesLoaded) return true;
        try {
            loadNativeLibrary(nativeLibDir, "c++_shared");
            loadNativeLibrary(nativeLibDir, "dilink5_camera");
            sNativeLibrariesLoaded = true;
            logger.info("DiLink 5 native camera libraries loaded.");
            return true;
        } catch (Throwable t) {
            logger.error("Failed to load DiLink 5 native camera libraries: "
                    + t.getMessage());
            return false;
        }
    }

    // CameraDaemon runs through app_process, whose native namespace cannot
    // resolve APK libraries by name. This private loader only accepts the
    // app's verified ApplicationInfo.nativeLibraryDir (or falls back to the
    // normal name-based loader), so the explicit path is intentional.
    @android.annotation.SuppressLint("UnsafeDynamicallyLoadedCode")
    private static void loadNativeLibrary(
            String nativeLibDir, String libraryName) {
        if (nativeLibDir == null || nativeLibDir.trim().isEmpty()) {
            System.loadLibrary(libraryName);
            return;
        }
        java.io.File library = new java.io.File(
                nativeLibDir, System.mapLibraryName(libraryName));
        if (!library.isFile()) {
            throw new UnsatisfiedLinkError(
                    "Missing native library: " + library.getPath());
        }
        System.load(library.getAbsolutePath());
    }

    private final int cameraId;
    private final long startEpoch;
    private final long acquisitionGeneration;
    private final long sessionToken;
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final LifecycleGate lifecycleGate = new LifecycleGate();
    private long nativeHandle;

    private enum NativeRecoveryResult {
        COMPLETE,
        BUSY,
        FAILED
    }

    private enum OwnerRetirementResult {
        COMPLETE,
        BUSY,
        FAILED
    }

    private static final class ExpectedStopMarker {
        final long ownerToken;
        final long expiresAtElapsedMs;

        ExpectedStopMarker(long ownerToken, long expiresAtElapsedMs) {
            this.ownerToken = ownerToken;
            this.expiresAtElapsedMs = expiresAtElapsedMs;
        }
    }

    enum NativeRecoveryAdmission {
        NOT_NEEDED,
        ACQUIRED,
        BUSY
    }

    public DiLink5QCarCamBackend(int cameraId) {
        this(cameraId,
                com.overdrive.app.daemon.CameraDaemon.captureCameraStartEpoch());
    }

    public DiLink5QCarCamBackend(int cameraId, long startEpoch) {
        this.cameraId = cameraId;
        this.startEpoch = startEpoch;
        this.acquisitionGeneration = sAcquisitionGeneration.get();
        this.sessionToken = nextSessionToken();
    }

    private static long nextSessionToken() {
        long token = sNextSessionToken.incrementAndGet();
        if (token > 0L) return token;
        synchronized (DiLink5QCarCamBackend.class) {
            sNextSessionToken.set(1L);
            return 1L;
        }
    }

    public long getSessionToken() {
        return sessionToken;
    }

    public static boolean isSupported() {
        if (!DiLink5Platform.isSelected()
                || !ensureNativeLibrariesLoaded(null)) {
            return false;
        }
        Boolean cached = sSupported;
        if (cached != null) return cached;
        try {
            cached = nativeIsSupported();
        } catch (Throwable t) {
            logger.warn("nativeIsSupported check failed: " + t.getMessage());
            cached = false;
        }
        sSupported = cached;
        return cached;
    }

    private static boolean isStartAllowed(
            long startEpoch, long acquisitionGeneration) {
        return com.overdrive.app.daemon.CameraDaemon
                .isCameraStartEpochCurrent(startEpoch)
                && isAcquisitionGenerationCurrent(acquisitionGeneration)
                && !sNativeRecoveryInProgress.get();
    }

    static boolean isAcquisitionGenerationCurrent(long generation) {
        return generation == sAcquisitionGeneration.get();
    }

    // The OEM-reverse source handoff (the gear-driven acquisition
    // cancellation, the post-reverse resume and its admission fence) was
    // removed. The AIS server multiplexes the QCarCam inputs, so FastCam keeps
    // its session in every gear and never trades ownership with the vendor
    // AVM.

    private static boolean awaitExactOwnerRetirement(
            long expectedOwnerToken,
            long deadlineElapsedMs) {
        synchronized (DiLink5QCarCamBackend.class) {
            while (sNativeOwnerToken == expectedOwnerToken
                    || sHardwareOwnerToken == expectedOwnerToken) {
                long remaining = deadlineElapsedMs
                        - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0L) return false;
                try {
                    DiLink5QCarCamBackend.class.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private boolean publishSourceIfCurrent() {
        synchronized (DiLink5QCarCamBackend.class) {
            if (!isStartAllowed(startEpoch, acquisitionGeneration)
                || sHardwareProcess == null
                || !sHardwareProcess.isAlive()
                || sHardwareOwnerToken != sessionToken
                || sNativeOwnerToken != sessionToken
                || !sNativeSessionOwned.get()) {
                return false;
            }
            sHardwareSourcePublished = true;
            sHardwareSourceClaimed = false;
            return true;
        }
    }

    /**
     * Claims a successfully started source for the caller's GL-owned
     * cameraObj field. The caller must hold {@code DiLink5QCarCamBackend.class}
     * until that field assignment is complete, making publication, claim, and
     * owner visibility atomic against reverse cancellation.
     */
    public boolean claimPublishedSourceForGlOwner() {
        synchronized (DiLink5QCarCamBackend.class) {
            if (!isStartAllowed(startEpoch, acquisitionGeneration)
                    || !sHardwareSourcePublished
                    || sHardwareSourceClaimed
                    || sHardwareAcquisitionGeneration
                            != acquisitionGeneration
                    || sHardwareOwnerToken != sessionToken
                    || sNativeOwnerToken != sessionToken
                    || nativeHandle == 0
                    || !isStreaming.get()) {
                return false;
            }
            sHardwareSourceClaimed = true;
            return true;
        }
    }

    private boolean claimNativeSessionOwner() {
        synchronized (DiLink5QCarCamBackend.class) {
            if (!canClaimNativeSessionOwner(
                    isStartAllowed(startEpoch, acquisitionGeneration),
                    sNativeRecoveryInProgress.get(),
                    sNativeOwnerToken,
                    sessionToken)) {
                return false;
            }
            sNativeOwnerToken = sessionToken;
            sNativeOwnerBackend = this;
            sNativeBridgeReleasedPendingProcess.set(false);
            return true;
        }
    }

    static boolean canClaimNativeSessionOwner(
            boolean startAllowed,
            boolean recoveryInProgress,
            long currentOwnerToken,
            long requestingToken) {
        return startAllowed
                && !recoveryInProgress
                && (currentOwnerToken == Long.MIN_VALUE
                        || currentOwnerToken == requestingToken);
    }

    private void markNativeSessionOwned() {
        synchronized (DiLink5QCarCamBackend.class) {
            if (sNativeOwnerToken == sessionToken
                    && sNativeOwnerBackend == this) {
                sNativeSessionOwned.set(true);
            }
        }
    }

    private void clearNativeSessionOwnerIfOwned() {
        clearNativeSessionOwnerIfOwned(true);
    }

    private void clearNativeSessionOwnerIfOwned(
            boolean clearCleanupPending) {
        synchronized (DiLink5QCarCamBackend.class) {
            if (sNativeOwnerToken == sessionToken
                    && sNativeOwnerBackend == this) {
                sNativeCleanupPending.set(!clearCleanupPending);
                sNativeBridgeReleasedPendingProcess.set(
                        !clearCleanupPending);
                sNativeSessionOwned.set(false);
                sNativeOwnerToken = Long.MIN_VALUE;
                sNativeOwnerBackend = null;
                DiLink5QCarCamBackend.class.notifyAll();
            }
        }
    }

    private static boolean isOwnedByAnotherSession(long ownerToken) {
        synchronized (DiLink5QCarCamBackend.class) {
            return (sHardwareOwnerToken != Long.MIN_VALUE
                    && sHardwareOwnerToken != ownerToken)
                    || sHardwareStartup.isOwnedByAnother(ownerToken)
                    || (sNativeOwnerToken != Long.MIN_VALUE
                            && sNativeOwnerToken != ownerToken)
                    || (sFrameListenerOwnerToken != Long.MIN_VALUE
                            && sFrameListenerOwnerToken != ownerToken);
        }
    }

    private static synchronized boolean beginHardwareStartupIfCurrent(
            long startEpoch,
            long acquisitionGeneration,
            long ownerToken) {
        if (!isStartAllowed(startEpoch, acquisitionGeneration)
                || sHardwareStartup.isActive()
                || (sHardwareProcess != null
                        && sHardwareProcess.isAlive())) {
            return false;
        }
        return sHardwareStartup.begin(
                ownerToken, acquisitionGeneration);
    }

    private static synchronized boolean installHardwareSessionAfterSpawn(
            Process process,
            String socketPath,
            String cameraIds,
            int captureFps,
            long startEpoch,
            long acquisitionGeneration,
            long ownerToken) {
        if (process == null
                || !process.isAlive()
                || !sHardwareStartup.matches(
                        ownerToken, acquisitionGeneration)
                || (sHardwareProcess != null
                        && sHardwareProcess != process
                        && sHardwareProcess.isAlive())) {
            return false;
        }
        // Publish the exact child even if reverse invalidated this generation
        // after ProcessBuilder.start() began. The return value tells the
        // starter to abort, while cancellation can now see and retire the
        // process instead of mistaking the spawn→publication gap for a clean
        // ownership release.
        sHardwareProcess = process;
        sHardwarePid = -1;
        sSocketPath = socketPath;
        sHardwareCameraIds = cameraIds;
        sHardwareCaptureFps = captureFps;
        sHardwareAcquisitionGeneration = acquisitionGeneration;
        sHardwareOwnerToken = ownerToken;
        sHardwareSourcePublished = false;
        sHardwareSourceClaimed = false;
        sHardwareStartup.clearIfOwned(
                ownerToken, acquisitionGeneration);
        return isStartAllowed(startEpoch, acquisitionGeneration);
    }

    private static synchronized void clearHardwareStartupIfOwned(
            long ownerToken,
            long acquisitionGeneration) {
        sHardwareStartup.clearIfOwned(
                ownerToken, acquisitionGeneration);
    }

    private static synchronized boolean publishHardwarePidIfCurrent(
            Process process,
            int processPid,
            long startEpoch,
            long acquisitionGeneration,
            long ownerToken) {
        if (processPid <= 0
                || sHardwareProcess != process
                || !process.isAlive()
                || sHardwareAcquisitionGeneration != acquisitionGeneration
                || sHardwareOwnerToken != ownerToken
                || !isStartAllowed(startEpoch, acquisitionGeneration)) {
            return false;
        }
        sHardwarePid = processPid;
        return true;
    }

    private static synchronized boolean isHardwareSessionCurrent(
            Process process,
            long acquisitionGeneration,
            long ownerToken) {
        return sHardwareProcess == process
                && process != null
                && process.isAlive()
                && sHardwareAcquisitionGeneration == acquisitionGeneration
                && sHardwareOwnerToken == ownerToken;
    }

    private static synchronized void clearHardwareSessionIfOwned(
            Process process, long ownerToken) {
        if (sHardwareProcess == process
                && sHardwareOwnerToken == ownerToken) {
            clearHardwareSession();
        }
    }

    private static boolean ensureHardwareProcess(
            long startEpoch,
            long acquisitionGeneration,
            long ownerToken) {
        Process startedProcess = null;
        boolean outputDrainerStarted = false;
        boolean startupRegistered = false;
        try {
            if (!isStartAllowed(startEpoch, acquisitionGeneration)) return false;
            synchronized (DiLink5QCarCamBackend.class) {
                if (sNativeOwnerToken != Long.MIN_VALUE
                        && sNativeOwnerToken != ownerToken) {
                    logger.info("FastCam native bridge is still owned by "
                            + "another backend session.");
                    return false;
                }
            }

            android.content.Context context =
                    com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (context == null || context.getApplicationInfo() == null) {
                logger.error("Cannot deploy fast_cam_capture without app context.");
                return false;
            }

            String nativeLibDir =
                    context.getApplicationInfo().nativeLibraryDir;
            java.io.File binary;
            if (ScratchPaths.usesLegacyDir()) {
                binary = new java.io.File(FAST_CAM_PATH);
                if (!deployVerifiedAsset(
                        context, FAST_CAM_ASSET, binary, 0700)) {
                    logger.error("fast_cam_capture is unavailable at "
                            + FAST_CAM_PATH);
                    return false;
                }
            } else {
                binary = new java.io.File(
                        nativeLibDir,
                        System.mapLibraryName("fast_cam_capture"));
                if (!binary.isFile() || binary.length() <= 0L) {
                    logger.error("Packaged fast_cam_capture is unavailable at "
                            + binary.getPath());
                    return false;
                }
                logger.info("Using packaged fast_cam_capture at "
                        + binary.getPath());
            }
            int[] cameraMapping = resolveCameraMapping();
            String cameraIds = DiLink5CameraMapping.toCsv(cameraMapping);
            int captureFps = resolveCaptureFps();
            if (!applyNativeCameraMapping(cameraMapping)) return false;
            Process existingProcess = sHardwareProcess;
            if (existingProcess != null && existingProcess.isAlive()) {
                if (sHardwareOwnerToken != ownerToken) {
                    logger.info("FastCam hardware process is owned by another "
                            + "backend session; acquisition will retry.");
                    return false;
                }
                int verifiedPid =
                        resolveOwnedProcessPid(existingProcess, binary);
                if (verifiedPid == sHardwarePid
                        && sSocketPath != null
                        && cameraIds.equals(sHardwareCameraIds)
                        && captureFps == sHardwareCaptureFps
                        && sHardwareAcquisitionGeneration
                                == acquisitionGeneration
                        && sHardwareOwnerToken == ownerToken) {
                    return true;
                }
                if (!stopOwnedHardwareProcess(true, true, ownerToken)) {
                    logger.error("Unable to stop a stale fast camera child.");
                    return false;
                }
            } else if (existingProcess != null) {
                classifyObservedHardwareExitAndClearIfCurrent(existingProcess);
            }
            if (!stopStaleCaptureProcesses(binary)) {
                logger.error("A stale camera capture child is still alive.");
                return false;
            }
            if (!isStartAllowed(startEpoch, acquisitionGeneration)) return false;

            java.io.File releaseGuard = new java.io.File(
                    nativeLibDir,
                    System.mapLibraryName("fast_cam_release_guard"));
            if (!releaseGuard.isFile()) {
                logger.error("Fast camera release guard is unavailable; "
                        + "refusing unsafe producer buffer ownership.");
                return false;
            }

            if (!DiLink5CameraSafety.runPreflight(
                    startEpoch, acquisitionGeneration)) {
                logger.error("DiLink 5 camera safety preflight refused QCarCam.");
                return false;
            }
            if (!isStartAllowed(startEpoch, acquisitionGeneration)) {
                return false;
            }
            if (!beginHardwareStartupIfCurrent(
                    startEpoch,
                    acquisitionGeneration,
                    ownerToken)) {
                return false;
            }
            startupRegistered = true;
            long safetyLeaseGeneration =
                    DiLink5CameraSafety.beginAcquisition();
            if (safetyLeaseGeneration == Long.MIN_VALUE) {
                logger.error("Unable to arm the QCarCam acquisition lease.");
                return false;
            }
            boolean safetyLeaseAssociated;
            synchronized (DiLink5QCarCamBackend.class) {
                safetyLeaseAssociated =
                        isStartAllowed(startEpoch, acquisitionGeneration)
                                && sHardwareStartup.matches(
                                        ownerToken,
                                        acquisitionGeneration);
                if (safetyLeaseAssociated) {
                    sSafetyLeaseOwnerToken = ownerToken;
                    sSafetyLeaseGeneration =
                            safetyLeaseGeneration;
                }
            }
            if (!safetyLeaseAssociated) {
                DiLink5CameraSafety.markCleanRelease(
                        safetyLeaseGeneration);
                return false;
            }

            String socketPath = "@dilink5_fast_" + randomHex(16);
            ProcessBuilder processBuilder = new ProcessBuilder(
                    binary.getPath(),
                    "--cams", cameraIds,
                    "--fps", Integer.toString(captureFps),
                    "--time", "0",
                    "--socket", socketPath);
            processBuilder.environment().put(
                    "LD_LIBRARY_PATH",
                    "/vendor/lib64:/system/lib64:"
                            + nativeLibDir + ":" + ScratchPaths.getDir());
            processBuilder.environment().put(
                    ScratchPaths.ENV_SCRATCH, ScratchPaths.getDir());
            processBuilder.environment().put("TMPDIR", ScratchPaths.getDir());
            processBuilder.environment().put(
                    "LD_PRELOAD", releaseGuard.getAbsolutePath());
            processBuilder.redirectErrorStream(true);

            startedProcess = processBuilder.start();
            Process process = startedProcess;
            if (!installHardwareSessionAfterSpawn(
                    process,
                    socketPath,
                    cameraIds,
                    captureFps,
                    startEpoch,
                    acquisitionGeneration,
                    ownerToken)) {
                if (stopProcess(process)) {
                    clearExpectedStop(process);
                    clearHardwareSessionIfOwned(process, ownerToken);
                    markCleanReleaseIfNoOwnership();
                }
                return false;
            }
            startupRegistered = false;
            int processPid = resolveOwnedProcessPid(process, binary);
            if (processPid <= 0) {
                if (stopProcess(process)) {
                    clearExpectedStop(process);
                    clearHardwareSessionIfOwned(process, ownerToken);
                    markCleanReleaseIfNoOwnership();
                }
                logger.error("Unable to verify the fast camera child process.");
                return false;
            }
            if (!publishHardwarePidIfCurrent(
                    process,
                    processPid,
                    startEpoch,
                    acquisitionGeneration,
                    ownerToken)) {
                if (stopProcess(process)) {
                    clearExpectedStop(process);
                    clearHardwareSessionIfOwned(process, ownerToken);
                    markCleanReleaseIfNoOwnership();
                }
                return false;
            }
            drainHardwareOutput(process);
            outputDrainerStarted = true;

            if (process.waitFor(
                    PROCESS_START_CHECK_MS, TimeUnit.MILLISECONDS)) {
                int exitCode = process.exitValue();
                clearHardwareSessionIfOwned(process, ownerToken);
                markCleanReleaseIfNoOwnership();
                if (!isStartAllowed(startEpoch, acquisitionGeneration)) {
                    return false;
                }
                logger.error("fast_cam_capture exited during startup with code "
                        + exitCode
                        + (isAisStallSelfExit(exitCode)
                                ? " (AIS pipeline stall self-exit — vendor "
                                        + "camera server unresponsive)"
                                : ""));
                return false;
            }
            if (!isStartAllowed(startEpoch, acquisitionGeneration)) {
                if (stopProcess(process)) {
                    clearHardwareSessionIfOwned(process, ownerToken);
                    markCleanReleaseIfNoOwnership();
                }
                return false;
            }
            if (!isHardwareSessionCurrent(
                    process, acquisitionGeneration, ownerToken)) {
                return false;
            }
            logger.info("Qualcomm fast_cam_capture started via owned supervisor"
                    + " (pid=" + processPid + ", cams=" + cameraIds
                    + ", captureFps=" + captureFps + ").");
            return true;
        } catch (InterruptedException e) {
            Thread.interrupted();
            Process process = startedProcess;
            if (process != null) {
                try {
                    if (stopProcess(process)) {
                        if (!outputDrainerStarted) {
                            clearExpectedStop(process);
                        }
                        clearHardwareSessionIfOwned(process, ownerToken);
                        markCleanReleaseIfNoOwnership();
                    }
                } catch (InterruptedException ignored) {
                    // Restore the original interrupt after best-effort cleanup.
                }
            } else {
                markCleanReleaseIfNoOwnership();
            }
            Thread.currentThread().interrupt();
            logger.error("Interrupted while starting fast_cam_capture.");
            return false;
        } catch (Throwable t) {
            Process process = startedProcess;
            if (process != null) {
                try {
                    if (stopProcess(process)) {
                        if (!outputDrainerStarted) {
                            clearExpectedStop(process);
                        }
                        clearHardwareSessionIfOwned(process, ownerToken);
                        markCleanReleaseIfNoOwnership();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            } else {
                markCleanReleaseIfNoOwnership();
            }
            logger.error("Failed to start fast_cam_capture: "
                    + t.getMessage(), t);
            return false;
        } finally {
            if (startupRegistered) {
                clearHardwareStartupIfOwned(
                        ownerToken, acquisitionGeneration);
                markCleanReleaseIfNoOwnership();
            }
        }
    }

    public static boolean stopHardwareProcessForExit() {
        long retirementDeadline =
                android.os.SystemClock.elapsedRealtime()
                        + NATIVE_RECOVERY_WAIT_TIMEOUT_MS;
        boolean hadOwnedProcess;
        boolean hadNativeOwnership;
        boolean startupInProgress;
        synchronized (DiLink5QCarCamBackend.class) {
            hadOwnedProcess = sHardwareProcess != null
                    && sHardwareProcess.isAlive();
            startupInProgress = sHardwareStartup.isActive();
            hadNativeOwnership =
                    sNativeOwnerToken != Long.MIN_VALUE
                            || sNativeSessionOwned.get();
        }
        boolean stopped;
        if (startupInProgress
                && !sHardwareStartup.awaitClear(
                        boundedDeadlineWaitMs(
                                retirementDeadline,
                                NATIVE_RECOVERY_WAIT_TIMEOUT_MS))) {
            stopped = false;
            logger.warn("Timed out waiting for FastCam process startup "
                    + "during daemon exit.");
        } else {
            DiLink5QCarCamBackend exactNativeOwner;
            long exactNativeOwnerToken;
            boolean unattributedNativeOwnership;
            synchronized (DiLink5QCarCamBackend.class) {
                exactNativeOwner = sNativeOwnerBackend;
                exactNativeOwnerToken = sNativeOwnerToken;
                unattributedNativeOwnership =
                        (exactNativeOwnerToken != Long.MIN_VALUE
                                && exactNativeOwner == null)
                                || (exactNativeOwnerToken == Long.MIN_VALUE
                                        && sNativeSessionOwned.get());
            }

            boolean nativeRetired = true;
            if (exactNativeOwner != null
                    && exactNativeOwnerToken != Long.MIN_VALUE) {
                OwnerRetirementResult result =
                        exactNativeOwner.closeForRecoveryBefore(
                                retirementDeadline);
                if (result == OwnerRetirementResult.BUSY) {
                    nativeRetired = awaitExactOwnerRetirement(
                            exactNativeOwnerToken,
                            retirementDeadline);
                } else {
                    nativeRetired =
                            result == OwnerRetirementResult.COMPLETE;
                }
                synchronized (DiLink5QCarCamBackend.class) {
                    if (sNativeOwnerToken == exactNativeOwnerToken
                            || sHardwareOwnerToken
                                    == exactNativeOwnerToken) {
                        nativeRetired = false;
                    }
                }
            } else if (unattributedNativeOwnership) {
                nativeRetired = false;
                logger.error("Refusing process-first FastCam shutdown while "
                        + "native ownership cannot be attributed to an exact "
                        + "backend.");
            }

            if (!nativeRetired) {
                stopped = false;
            } else {
                Process process = null;
                long processOwnerToken = Long.MIN_VALUE;
                boolean processStopAllowed;
                synchronized (DiLink5QCarCamBackend.class) {
                    processStopAllowed =
                            sNativeOwnerToken == Long.MIN_VALUE
                                    && !sNativeSessionOwned.get();
                    if (processStopAllowed) {
                        process = sHardwareProcess;
                        processOwnerToken = sHardwareOwnerToken;
                    }
                }
                if (!processStopAllowed) {
                    logger.error("Refusing process-first FastCam shutdown "
                            + "because native ownership appeared during "
                            + "terminal cleanup.");
                    stopped = false;
                } else {
                    try {
                        stopped = stopHardwareProcessForOwner(
                                process,
                                processOwnerToken,
                                false,
                                false,
                                retirementDeadline);
                    } catch (Throwable t) {
                        logger.warn("Failed to stop fast_cam_capture: "
                                + t.getMessage());
                        stopped = false;
                    }
                }
            }
        }
        if (stopped && canMarkCleanRelease()) {
            markCleanReleaseIfNoOwnership();
        }
        return stopped;
    }

    private static boolean stopOwnedHardwareProcess(
            boolean alwaysSettle) throws InterruptedException {
        return stopOwnedHardwareProcess(alwaysSettle, true);
    }

    private static boolean stopOwnedHardwareProcess(
            boolean alwaysSettle,
            boolean markCleanRelease) throws InterruptedException {
        Process process;
        long ownerToken;
        synchronized (DiLink5QCarCamBackend.class) {
            process = sHardwareProcess;
            ownerToken = sHardwareOwnerToken;
        }
        return stopHardwareProcessForOwner(
                process, ownerToken, alwaysSettle, markCleanRelease);
    }

    private static boolean stopOwnedHardwareProcess(
            boolean alwaysSettle,
            boolean markCleanRelease,
            long expectedOwnerToken) throws InterruptedException {
        Process process;
        synchronized (DiLink5QCarCamBackend.class) {
            process = sHardwareOwnerToken == expectedOwnerToken
                    ? sHardwareProcess
                    : null;
        }
        return stopHardwareProcessForOwner(
                process,
                expectedOwnerToken,
                alwaysSettle,
                markCleanRelease);
    }

    private static boolean stopHardwareProcessForOwner(
            Process process,
            long expectedOwnerToken,
            boolean alwaysSettle,
            boolean markCleanRelease) throws InterruptedException {
        return stopHardwareProcessForOwner(
                process,
                expectedOwnerToken,
                alwaysSettle,
                markCleanRelease,
                Long.MAX_VALUE);
    }

    private static boolean stopHardwareProcessForOwner(
            Process process,
            long expectedOwnerToken,
            boolean alwaysSettle,
            boolean markCleanRelease,
            long deadlineElapsedMs) throws InterruptedException {
        synchronized (DiLink5QCarCamBackend.class) {
            if (sGlResourcesOwnerToken == expectedOwnerToken
                    && !sGlResourcesReleased.get()) {
                logger.error("Refusing to stop fast_cam_capture before its "
                        + "EGLImages are released on the owning GL thread.");
                return false;
            }
            if (process != null && process.isAlive()) {
                markProcessExpectedStop(process, expectedOwnerToken);
            }
        }
        // Process termination can consume both graceful and forced-stop
        // deadlines. Never retain the class monitor here: reverse
        // cancellation must still be able to invalidate the generation and
        // independently kill the exact same child.
        boolean stopped = stopHardwareProcesses(
                process, alwaysSettle, deadlineElapsedMs);
        // Keep the exact process/generation marker through the bounded retry
        // window. nativeStop/nativeRelease can complete asynchronously after
        // their caller times out; clearing the marker here would classify that
        // initiated retirement as an unexpected crash and boot-disable DI5.
        boolean ownershipRetired;
        boolean safeToMarkClean;
        synchronized (DiLink5QCarCamBackend.class) {
            Process current = sHardwareProcess;
            long currentOwnerToken = sHardwareOwnerToken;
            if (stopped
                    && current == process
                    && currentOwnerToken == expectedOwnerToken) {
                clearHardwareSession();
                current = sHardwareProcess;
                currentOwnerToken = sHardwareOwnerToken;
            }
            boolean sameOwnerStillPresent =
                    current != null
                            && currentOwnerToken == expectedOwnerToken;
            ownershipRetired = stopped && !sameOwnerStillPresent;
            safeToMarkClean = ownershipRetired
                    && current == null
                    && sNativeOwnerToken == Long.MIN_VALUE
                    && !sHardwareStartup.isActive();
        }
        if (safeToMarkClean && markCleanRelease) {
            markCleanReleaseIfNoOwnership();
        }
        if (ownershipRetired) {
            return true;
        }
        logger.error("The generation-owned FastCam process survived forced "
                + "stop or was replaced by another process for the same owner.");
        return false;
    }

    public static boolean hasOwnedHardwareProcess() {
        Process process = sHardwareProcess;
        return process != null && process.isAlive();
    }

    /**
     * True when any FastCam/JNI/startup owner still exists. Diagnostic view of
     * every static ownership bit, used to decide whether a hard camera release
     * (process restart) is still required after a failed close.
     */
    public static synchronized boolean hasActiveCameraOwnership() {
        return (sHardwareProcess != null && sHardwareProcess.isAlive())
                || sHardwareOwnerToken != Long.MIN_VALUE
                || sNativeOwnerToken != Long.MIN_VALUE
                || sNativeOwnerBackend != null
                || sNativeSessionOwned.get()
                || sNativeCleanupPending.get()
                || sNativeBridgeReleasedPendingProcess.get()
                || sFrameListenerOwnerToken != Long.MIN_VALUE
                || sGlResourcesOwnerToken != Long.MIN_VALUE
                || sHardwareStartup.isActive();
    }

    public static boolean ownsConfiguredInput(int inputId) {
        return inputId >= 0 && inputId <= 3;
    }

    public static boolean tryAcquireConfiguredInputReservation() {
        return sConfiguredInputReservation.compareAndSet(false, true);
    }

    public static void releaseConfiguredInputReservation() {
        sConfiguredInputReservation.set(false);
    }

    private static boolean stopStaleCaptureProcesses(
            java.io.File fastCamBinary) throws Exception {
        List<Integer> stalePids = new ArrayList<>();
        collectExactExecutablePids(fastCamBinary.getCanonicalPath(), stalePids);
        collectExactExecutablePids(LEGACY_QCARCAM_PATH, stalePids);
        int lastStoppedPid = -1;
        for (int pid : stalePids) {
            if (!stopPid(pid)) return false;
            lastStoppedPid = pid;
            logger.info("Stopped stale camera capture process pid=" + pid);
        }
        if (lastStoppedPid > 0) {
            Thread.sleep(HARDWARE_RELEASE_SETTLE_MS);
            DiLink5CameraSafety.recordForeignRelease(lastStoppedPid);
        }
        return true;
    }

    private static void collectExactExecutablePids(
            String expectedPath, List<Integer> output) {
        java.io.File[] entries = new java.io.File("/proc").listFiles();
        if (entries == null) return;
        for (java.io.File entry : entries) {
            String name = entry.getName();
            if (!name.matches("\\d+")) continue;
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (pid <= 0 || pid == android.os.Process.myPid()) continue;
            try {
                String executable = android.system.Os.readlink(
                        "/proc/" + pid + "/exe");
                if (executable.endsWith(" (deleted)")) {
                    executable = executable.substring(
                            0, executable.length() - " (deleted)".length());
                }
                if (expectedPath.equals(executable)
                        && !output.contains(pid)) {
                    output.add(pid);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean stopPid(int pid) throws InterruptedException {
        if (!new java.io.File("/proc/" + pid).exists()) return true;
        try {
            android.system.Os.kill(
                    pid, android.system.OsConstants.SIGTERM);
        } catch (android.system.ErrnoException e) {
            if (e.errno == android.system.OsConstants.ESRCH) return true;
        }
        if (waitForPidExit(pid, PROCESS_STOP_TIMEOUT_MS)) return true;
        try {
            android.system.Os.kill(
                    pid, android.system.OsConstants.SIGKILL);
        } catch (android.system.ErrnoException e) {
            if (e.errno == android.system.OsConstants.ESRCH) return true;
        }
        return waitForPidExit(pid, PROCESS_STOP_TIMEOUT_MS);
    }

    private static boolean waitForPidExit(int pid, long timeoutMs)
            throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        java.io.File process = new java.io.File("/proc/" + pid);
        while (process.exists()
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(25L);
        }
        return !process.exists();
    }

    private static byte[] digestFile(java.io.File file)
            throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            return digest(in);
        }
    }

    private static byte[] digest(java.io.InputStream in)
            throws java.io.IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) {
                digest.update(buffer, 0, length);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        CHANNEL_RANDOM.nextBytes(bytes);
        char[] hex = new char[byteCount * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            hex[i * 2] = alphabet[value >>> 4];
            hex[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(hex);
    }

    private static int[] resolveCameraMapping() {
        String override = readCameraMappingProperty();
        int[] mapping = DiLink5CameraMapping.parse(override);
        if (mapping != null) {
            logger.info("Using persist.overdrive.cams mapping: "
                    + DiLink5CameraMapping.toCsv(mapping));
            return mapping;
        }
        if (!override.isEmpty()) {
            logger.warn("Ignoring invalid persist.overdrive.cams mapping: "
                    + override);
        }

        String configuredOverride = "";
        try {
            org.json.JSONObject camera =
                    com.overdrive.app.config.UnifiedConfigManager
                            .loadConfig().optJSONObject("camera");
            if (camera != null) {
                configuredOverride = camera.optString(
                        CONFIG_CAMERA_MAPPING_KEY, "");
            }
        } catch (Throwable ignored) {
        }
        mapping = DiLink5CameraMapping.parse(configuredOverride);
        if (mapping != null) {
            logger.info("Using configured DiLink 5 camera mapping: "
                    + DiLink5CameraMapping.toCsv(mapping));
            return mapping;
        }
        if (!configuredOverride.trim().isEmpty()) {
            logger.warn("Ignoring invalid configured DiLink 5 camera mapping: "
                    + configuredOverride);
        }

        String configuredModel = "";
        try {
            String selected = com.overdrive.app.config.UnifiedConfigManager
                    .getSelectedVehicleModelId();
            configuredModel = selected != null ? selected : "";
        } catch (Throwable ignored) {
        }
        mapping = DiLink5CameraMapping.forPlatform(
                configuredModel,
                android.os.Build.MODEL,
                android.os.Build.PRODUCT);
        if (DiLink5PlatformHelper.isSharkHardware()
                || DiLink5PlatformHelper.isSharkProfile(configuredModel)) {
            mapping = new int[]{8, 9, 5, 4};
        }
        String cameraIds = DiLink5CameraMapping.toCsv(mapping);
        logger.info(("8,9,5,4".equals(cameraIds)
                ? "Detected BYD Shark/DMO"
                : "Using standard DiLink 5")
                + " camera mapping: " + cameraIds);
        return mapping;
    }

    /**
     * Canonicalizes the app-facing mapping override. Empty means Auto.
     *
     * @return canonical CSV, an empty string for Auto, or null when invalid
     */
    public static String normalizeCameraMapping(String csv) {
        if (csv == null || csv.trim().isEmpty()) return "";
        int[] mapping = DiLink5CameraMapping.parse(csv);
        return mapping != null ? DiLink5CameraMapping.toCsv(mapping) : null;
    }

    private static String readCameraMappingProperty() {
        return readSystemProperty("persist.overdrive.cams");
    }

    private static String readSystemProperty(String property) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "getprop", property)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "";
            }
            try (java.io.BufferedReader reader =
                         new java.io.BufferedReader(
                                 new java.io.InputStreamReader(
                                         process.getInputStream()))) {
                String value = reader.readLine();
                return value != null ? value.trim() : "";
            }
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    static int parseCaptureFps(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_CAPTURE_FPS;
        }
        try {
            int fps = Integer.parseInt(value.trim());
            return fps >= 1 && fps <= 30 ? fps : DEFAULT_CAPTURE_FPS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_CAPTURE_FPS;
        }
    }

    private static int resolveCaptureFps() {
        String override = readSystemProperty(CAPTURE_FPS_PROPERTY);
        int fps = parseCaptureFps(override);
        if (!override.isEmpty()) {
            if (Integer.toString(fps).equals(override.trim())) {
                logger.info("Using " + CAPTURE_FPS_PROPERTY + "=" + fps);
            } else {
                logger.warn("Ignoring invalid " + CAPTURE_FPS_PROPERTY
                        + "=" + override + "; using " + DEFAULT_CAPTURE_FPS);
            }
        }
        return fps;
    }

    private static boolean applyNativeCameraMapping(int[] mapping) {
        try {
            nativeSetCameraMapping(
                    mapping[0], mapping[1], mapping[2], mapping[3],
                    mapping.length == 5 ? mapping[4] : -1);
            return true;
        } catch (Throwable t) {
            logger.error("Failed to apply fast camera mapping: "
                    + t.getMessage(), t);
            return false;
        }
    }

    private static synchronized void clearHardwareSession() {
        sHardwareProcess = null;
        sHardwarePid = -1;
        sSocketPath = null;
        sHardwareCameraIds = null;
        sHardwareCaptureFps = 0;
        sHardwareAcquisitionGeneration = Long.MIN_VALUE;
        sHardwareOwnerToken = Long.MIN_VALUE;
        sHardwareSourcePublished = false;
        sHardwareSourceClaimed = false;
        DiLink5QCarCamBackend.class.notifyAll();
    }

    private static int resolveOwnedProcessPid(
            Process process, java.io.File executable) {
        if (process == null || !process.isAlive() || executable == null) {
            return -1;
        }
        try {
            List<Integer> candidates = new ArrayList<>();
            collectExactExecutablePids(
                    executable.getCanonicalPath(), candidates);
            int ownedPid = -1;
            for (int pid : candidates) {
                if (readParentPid(pid) != android.os.Process.myPid()) {
                    continue;
                }
                if (ownedPid > 0) return -1;
                ownedPid = pid;
            }
            return ownedPid;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int readParentPid(int pid) throws java.io.IOException {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("PPid:")) {
                    return Integer.parseInt(line.substring(5).trim());
                }
            }
        }
        return -1;
    }

    private static boolean stopHardwareProcesses(
            Process ownedProcess, boolean alwaysSettle)
            throws InterruptedException {
        return stopHardwareProcesses(
                ownedProcess, alwaysSettle, Long.MAX_VALUE);
    }

    private static boolean stopHardwareProcesses(
            Process ownedProcess,
            boolean alwaysSettle,
            long deadlineElapsedMs)
            throws InterruptedException {
        boolean hadProcess =
                ownedProcess != null && ownedProcess.isAlive();
        if (!stopProcess(ownedProcess, deadlineElapsedMs)) {
            return false;
        }
        if (hadProcess || alwaysSettle) {
            if (!hasDeadlineBudget(
                    deadlineElapsedMs,
                    HARDWARE_RELEASE_SETTLE_MS)) {
                return false;
            }
            Thread.sleep(HARDWARE_RELEASE_SETTLE_MS);
        }
        return ownedProcess == null || !ownedProcess.isAlive();
    }

    private static void drainHardwareOutput(Process process) {
        Thread drainer = new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[FastCamProc] " + line);
                }
                int exitCode = process.waitFor();
                boolean expected =
                        classifyDrainedHardwareExitAndClear(process);
                if (expected) {
                    logger.info("fast_cam_capture stopped with code " + exitCode);
                } else if (isAisStallSelfExit(exitCode)) {
                    logger.warn("fast_cam_capture self-exited after an AIS "
                            + "pipeline stall (code " + exitCode + "); known "
                            + "producer health exit — source-only recovery "
                            + "will reacquire the source");
                } else {
                    logger.error("fast_cam_capture exited unexpectedly with code "
                            + exitCode + releaseGuardReasonSuffix());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                logger.warn("fast_cam_capture output drainer stopped: "
                        + t.getMessage());
            } finally {
                synchronized (DiLink5QCarCamBackend.class) {
                    if (sHardwareProcess == process && !process.isAlive()) {
                        clearHardwareSession();
                    }
                }
            }
        }, "fast-cam-capture-drainer");
        drainer.setDaemon(true);
        drainer.start();
    }

    private static boolean stopProcess(Process process)
            throws InterruptedException {
        return stopProcess(process, Long.MAX_VALUE);
    }

    private static boolean stopProcess(
            Process process,
            long deadlineElapsedMs)
            throws InterruptedException {
        if (process == null || !process.isAlive()) return true;
        if (!hasDeadlineBudget(deadlineElapsedMs, 1L)) return false;
        markProcessExpectedStop(
                process, ownerTokenForProcess(process));
        try {
            process.destroy();
            long gracefulWaitMs = boundedDeadlineWaitMs(
                    deadlineElapsedMs, PROCESS_STOP_TIMEOUT_MS);
            if (gracefulWaitMs <= 0L) {
                return false;
            }
            if (!process.waitFor(
                    gracefulWaitMs, TimeUnit.MILLISECONDS)) {
                if (!hasDeadlineBudget(deadlineElapsedMs, 1L)) {
                    return false;
                }
                process.destroyForcibly();
                long forcedWaitMs = boundedDeadlineWaitMs(
                        deadlineElapsedMs, PROCESS_STOP_TIMEOUT_MS);
                if (forcedWaitMs <= 0L) {
                    return false;
                }
                if (!process.waitFor(
                        forcedWaitMs, TimeUnit.MILLISECONDS)) {
                    return false;
                }
            }
            return !process.isAlive();
        } catch (InterruptedException e) {
            throw e;
        }
    }

    private static boolean hasDeadlineBudget(
            long deadlineElapsedMs,
            long requiredMs) {
        if (deadlineElapsedMs == Long.MAX_VALUE) return true;
        return deadlineElapsedMs
                - android.os.SystemClock.elapsedRealtime()
                >= Math.max(0L, requiredMs);
    }

    private static long boundedDeadlineWaitMs(
            long deadlineElapsedMs,
            long maximumMs) {
        if (deadlineElapsedMs == Long.MAX_VALUE) {
            return maximumMs;
        }
        long remaining = deadlineElapsedMs
                - android.os.SystemClock.elapsedRealtime();
        return Math.max(0L, Math.min(maximumMs, remaining));
    }

    private static boolean deployVerifiedAsset(
            android.content.Context context,
            String asset,
            java.io.File destination,
            int mode) {
        java.io.File temporary = null;
        java.io.FileDescriptor descriptor = null;
        try {
            java.io.File parent =
                    destination.getParentFile().getCanonicalFile();
            if (!FAST_CAM_ASSET.equals(asset)
                    || !"/data/local/tmp".equals(parent.getPath())
                    || !FAST_CAM_PATH.equals(destination.getPath())) {
                return false;
            }
            byte[] expected;
            try (java.io.InputStream in = context.getAssets().open(asset)) {
                expected = digest(in);
            }
            if (destination.isFile()
                    && MessageDigest.isEqual(
                            expected, digestFile(destination))) {
                android.system.Os.chmod(destination.getPath(), mode);
                return true;
            }

            temporary = new java.io.File(
                    parent,
                    ".fast_cam_capture."
                            + android.os.Process.myPid()
                            + "." + randomHex(8));
            descriptor = android.system.Os.open(
                    temporary.getPath(),
                    android.system.OsConstants.O_WRONLY
                            | android.system.OsConstants.O_CREAT
                            | android.system.OsConstants.O_EXCL
                            | android.system.OsConstants.O_NOFOLLOW
                            | android.system.OsConstants.O_CLOEXEC,
                    mode);
            try (java.io.InputStream in = context.getAssets().open(asset);
                 java.io.FileOutputStream out =
                         new java.io.FileOutputStream(descriptor)) {
                copy(in, out);
                out.getFD().sync();
            }
            if (!MessageDigest.isEqual(
                    expected, digestFile(temporary))) {
                return false;
            }
            android.system.Os.chmod(temporary.getPath(), mode);
            android.system.Os.rename(
                    temporary.getPath(), destination.getPath());
            temporary = null;
            android.system.Os.chmod(destination.getPath(), mode);
            boolean verified = destination.isFile()
                    && MessageDigest.isEqual(
                            expected, digestFile(destination));
            if (verified) {
                logger.info("Deployed current fast_cam_capture asset ("
                        + destination.length() + " bytes).");
            }
            return verified;
        } catch (Throwable t) {
            logger.warn("Failed to deploy " + asset + ": " + t.getMessage());
            return false;
        } finally {
            if (descriptor != null) {
                try {
                    android.system.Os.close(descriptor);
                } catch (Throwable ignored) {
                }
            }
            if (temporary != null) temporary.delete();
        }
    }

    private static void copy(
            java.io.InputStream in, java.io.OutputStream out)
            throws java.io.IOException {
        byte[] buffer = new byte[8192];
        int length;
        while ((length = in.read(buffer)) > 0) {
            out.write(buffer, 0, length);
        }
        out.flush();
    }

    private NativeRecoveryResult recoverNativeBridgeIfNeeded() {
        NativeRecoveryAdmission admission =
                acquireNativeRecoveryAdmission(
                        startEpoch, acquisitionGeneration);
        if (admission == NativeRecoveryAdmission.NOT_NEEDED) {
            return NativeRecoveryResult.COMPLETE;
        }
        if (admission == NativeRecoveryAdmission.BUSY) {
            logger.warn("Fast camera native recovery is already in progress.");
            return NativeRecoveryResult.BUSY;
        }
        long recoveryDeadline;
        synchronized (DiLink5QCarCamBackend.class) {
            recoveryDeadline =
                    sNativeRecoveryDeadlineElapsedMs;
        }

        Process expectedProcess = null;
        long expectedHardwareOwnerToken = Long.MIN_VALUE;
        long expectedNativeOwnerToken = Long.MIN_VALUE;
        DiLink5QCarCamBackend expectedNativeOwnerBackend = null;
        try {
            synchronized (DiLink5QCarCamBackend.class) {
                if (!sNativeCleanupPending.get()) {
                    return NativeRecoveryResult.COMPLETE;
                }
                if (!sGlResourcesReleased.get()) {
                    logger.error("Previous fast camera GL resources are still held.");
                    return NativeRecoveryResult.FAILED;
                }
                expectedHardwareOwnerToken = sHardwareOwnerToken;
                expectedNativeOwnerToken = sNativeOwnerToken;
                expectedNativeOwnerBackend = sNativeOwnerBackend;
            }

            if (expectedNativeOwnerBackend != null) {
                // Use the same timed lifecycle gate as every normal owner
                // close. Recovery cannot wait indefinitely behind another
                // open/close and then start a second full retirement after
                // reverse's shared deadline has already expired.
                OwnerRetirementResult retirementResult =
                        OwnerRetirementResult.FAILED;
                try {
                    retirementResult =
                            expectedNativeOwnerBackend
                                    .closeForRecoveryBefore(
                                            recoveryDeadline);
                } catch (Throwable t) {
                    logger.error("Exact FastCam owner recovery failed: "
                            + t.getMessage(), t);
                }
                boolean exactOwnershipRetired;
                synchronized (DiLink5QCarCamBackend.class) {
                    exactOwnershipRetired =
                            sNativeOwnerToken == Long.MIN_VALUE
                                    && sNativeOwnerBackend == null
                                    && sHardwareProcess == null
                                    && sHardwareOwnerToken == Long.MIN_VALUE
                                    && !sHardwareStartup.isActive();
                    if (exactOwnershipRetired) {
                        sNativeCleanupPending.set(false);
                        sNativeBridgeReleasedPendingProcess.set(false);
                        sNativeSessionOwned.set(false);
                        isStreaming.set(false);
                        nativeHandle = 0;
                    }
                }
                if (exactOwnershipRetired) {
                    markCleanReleaseIfNoOwnership();
                    return NativeRecoveryResult.COMPLETE;
                }
                if (retirementResult == OwnerRetirementResult.BUSY) {
                    if (awaitExactOwnerRetirement(
                            expectedNativeOwnerToken,
                            recoveryDeadline)) {
                        synchronized (DiLink5QCarCamBackend.class) {
                            if (sNativeOwnerToken == Long.MIN_VALUE
                                    && sHardwareOwnerToken
                                            == Long.MIN_VALUE
                                    && sHardwareProcess == null
                                    && !sHardwareStartup.isActive()) {
                                sNativeCleanupPending.set(false);
                                sNativeBridgeReleasedPendingProcess
                                        .set(false);
                                sNativeSessionOwned.set(false);
                            }
                        }
                        markCleanReleaseIfNoOwnership();
                        return NativeRecoveryResult.COMPLETE;
                    }
                    logger.info("Exact FastCam owner close is still in "
                            + "progress; recovery will retry.");
                    return NativeRecoveryResult.BUSY;
                }
                logger.error("Exact FastCam owner recovery remained "
                        + (retirementResult
                                == OwnerRetirementResult.COMPLETE
                                ? "owned" : "incomplete") + ".");
                return NativeRecoveryResult.FAILED;
            }

            // There is no exact Java owner capable of performing token-scoped
            // close. Mark the generation-owned sidecar expected only now,
            // immediately before wildcard JNI retirement can make it exit.
            expectedProcess = markOwnedHardwareProcessExpectedStop(
                    expectedHardwareOwnerToken);
            boolean nativeRecovered;
            synchronized (DiLink5QCarCamBackend.class) {
                nativeRecovered =
                        (sNativeBridgeReleasedPendingProcess.get()
                                || !sNativeCleanupPending.get())
                                && sNativeOwnerToken == Long.MIN_VALUE
                                && sNativeOwnerBackend == null;
            }
            if (!nativeRecovered) {
                try {
                    if (!nativeHasActiveSession()) {
                        nativeRecovered = true;
                    } else if (!nativeStop(0)) {
                        logger.error("Previous fast camera stream cleanup is blocked.");
                    } else if (!nativeRelease(0)) {
                        logger.error("Previous fast camera native session is still held.");
                    } else {
                        nativeRecovered = true;
                    }
                } catch (Throwable t) {
                    logger.error("Unable to recover the fast camera stream: "
                            + t.getMessage(), t);
                }
                if (!nativeRecovered) {
                    // The exact owner may have completed while recovery was
                    // being admitted. Once both its ownership token and
                    // cleanup debt are gone, JNI's "no active session" false
                    // result is successful retirement, not a reason to
                    // suppress the replacement process.
                    synchronized (DiLink5QCarCamBackend.class) {
                        nativeRecovered =
                                (sNativeBridgeReleasedPendingProcess.get()
                                        || !sNativeCleanupPending.get())
                                        && sNativeOwnerToken == Long.MIN_VALUE
                                        && sNativeOwnerBackend == null;
                    }
                }
            }
            if (!nativeRecovered) {
                // nativeStop/nativeRelease are bounded waits. A false result
                // while JNI still owns the session means retirement may still
                // be completing asynchronously; it is retryable BUSY, never a
                // permanent process-suppression verdict.
                return NativeRecoveryResult.BUSY;
            }
            synchronized (DiLink5QCarCamBackend.class) {
                sNativeSessionOwned.set(false);
                sNativeCleanupPending.set(true);
                sNativeBridgeReleasedPendingProcess.set(true);
            }
            OwnerRetirementResult processRetirement =
                    stopExpectedHardwareProcess(
                            expectedProcess,
                            expectedHardwareOwnerToken,
                            recoveryDeadline);
            if (processRetirement != OwnerRetirementResult.COMPLETE) {
                return processRetirement == OwnerRetirementResult.BUSY
                        ? NativeRecoveryResult.BUSY
                        : NativeRecoveryResult.FAILED;
            }

            synchronized (DiLink5QCarCamBackend.class) {
                Process current = sHardwareProcess;
                if (current != null
                        && (current != expectedProcess
                                || sHardwareOwnerToken
                                != expectedHardwareOwnerToken)) {
                    logger.error("Refusing to commit stale native recovery "
                            + "over a replacement FastCam process.");
                    return NativeRecoveryResult.FAILED;
                }
                if (current == expectedProcess
                        && sHardwareOwnerToken
                        == expectedHardwareOwnerToken) {
                    clearHardwareSession();
                }
                if (sNativeOwnerToken != Long.MIN_VALUE
                        && sNativeOwnerToken
                        != expectedNativeOwnerToken) {
                    logger.error("Refusing to commit stale native recovery "
                            + "over a replacement native owner.");
                    return NativeRecoveryResult.FAILED;
                }
                sNativeCleanupPending.set(false);
                sNativeBridgeReleasedPendingProcess.set(false);
                sNativeSessionOwned.set(false);
                if (sNativeOwnerToken == expectedNativeOwnerToken) {
                    sNativeOwnerToken = Long.MIN_VALUE;
                    sNativeOwnerBackend = null;
                }
                isStreaming.set(false);
                nativeHandle = 0;
            }
            markCleanReleaseIfNoOwnership();
            return NativeRecoveryResult.COMPLETE;
        } finally {
            synchronized (DiLink5QCarCamBackend.class) {
                sNativeRecoveryInProgress.set(false);
                sNativeRecoveryDeadlineElapsedMs = Long.MIN_VALUE;
                DiLink5QCarCamBackend.class.notifyAll();
            }
        }
    }

    private static NativeRecoveryAdmission acquireNativeRecoveryAdmission(
            long startEpoch,
            long acquisitionGeneration) {
        synchronized (DiLink5QCarCamBackend.class) {
            boolean callerCurrent =
                    com.overdrive.app.daemon.CameraDaemon
                            .isCameraStartEpochCurrent(startEpoch)
                            && isAcquisitionGenerationCurrent(
                                    acquisitionGeneration);
            NativeRecoveryAdmission admission =
                    decideNativeRecoveryAdmission(
                            callerCurrent,
                            sNativeCleanupPending.get(),
                            sNativeRecoveryInProgress.get());
            if (admission == NativeRecoveryAdmission.ACQUIRED) {
                sNativeRecoveryInProgress.set(true);
                sNativeRecoveryDeadlineElapsedMs =
                        android.os.SystemClock.elapsedRealtime()
                                + NATIVE_RECOVERY_WAIT_TIMEOUT_MS;
            }
            return admission;
        }
    }

    static NativeRecoveryAdmission decideNativeRecoveryAdmission(
            boolean callerCurrent,
            boolean cleanupPending,
            boolean recoveryInProgress) {
        if (!callerCurrent || recoveryInProgress) {
            return NativeRecoveryAdmission.BUSY;
        }
        return cleanupPending
                ? NativeRecoveryAdmission.ACQUIRED
                : NativeRecoveryAdmission.NOT_NEEDED;
    }

    private static OwnerRetirementResult stopExpectedHardwareProcess(
            Process expectedProcess,
            long expectedOwnerToken,
            long deadlineElapsedMs) {
        boolean stopped;
        boolean interrupted = false;
        try {
            stopped = stopHardwareProcessForOwner(
                    expectedProcess,
                    expectedOwnerToken,
                    false,
                    false,
                    deadlineElapsedMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped = false;
            interrupted = true;
        } catch (Throwable t) {
            logger.warn("Failed to retire recovered fast_cam_capture: "
                    + t.getMessage());
            stopped = false;
        }
        // Preserve the expected-retirement marker after a timeout. The exact
        // child may exit when the already-initiated native disconnect finally
        // completes, and that delayed exit must not boot-disable capture.
        if (stopped) return OwnerRetirementResult.COMPLETE;
        if (interrupted
                || !hasDeadlineBudget(deadlineElapsedMs, 1L)) {
            return OwnerRetirementResult.BUSY;
        }
        return OwnerRetirementResult.FAILED;
    }

    private void rollbackFailedOpen() {
        boolean ownsExactNativeSession;
        synchronized (DiLink5QCarCamBackend.class) {
            ownsExactNativeSession =
                    sNativeOwnerToken == sessionToken
                            && sNativeOwnerBackend == this;
        }
        if (ownsExactNativeSession) {
            OwnerRetirementResult result =
                    closeWithLifecycleGateResult(
                            true, Long.MAX_VALUE);
            if (result != OwnerRetirementResult.COMPLETE) {
                logger.warn("Fast camera startup rollback could not retire "
                        + "the exact native owner; preserving its sidecar.");
            }
            return;
        }
        try {
            if (!stopOwnedHardwareProcess(
                    false, true, sessionToken)) {
                logger.warn("Fast camera startup rollback is incomplete.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean open() {
        lifecycleGate.lock();
        try {
        if (nativeHandle != 0) {
            if (!isStartAllowed(startEpoch, acquisitionGeneration)) {
                closeAfterCancelledAcquisition();
                return false;
            }
            NativeRecoveryResult recovery =
                    recoverNativeBridgeIfNeeded();
            if (recovery != NativeRecoveryResult.COMPLETE) {
                return false;
            }
            if (nativeHandle != 0) return true;
        }
        if (!isStartAllowed(startEpoch, acquisitionGeneration)) return false;
        if (!DiLink5Platform.isEnabled()) {
            logger.warn("DiLink 5 camera backend is not explicitly enabled.");
            return false;
        }
        boolean supported = isSupported();
        if (!isStartAllowed(startEpoch, acquisitionGeneration)) return false;
        if (!supported) {
            DiLink5CameraSafety.suppressForProcess(
                    "DiLink 5 AIS compatibility check failed");
            logger.error("DiLink 5 AIS compatibility check failed.");
            return false;
        }
        NativeRecoveryResult bridgeRecovery =
                recoverNativeBridgeIfNeeded();
        if (!isStartAllowed(startEpoch, acquisitionGeneration)) {
            closeAfterCancelledAcquisition();
            return false;
        }
        if (bridgeRecovery == NativeRecoveryResult.BUSY) {
            logger.info("Native FastCam recovery is busy; acquisition will retry.");
            return false;
        }
        if (bridgeRecovery == NativeRecoveryResult.FAILED) {
            DiLink5CameraSafety.suppressForProcess(
                    "Previous native camera bridge could not be recovered");
            return false;
        }
        if (!tryAcquireConfiguredInputReservation()) {
            logger.warn("Camera inputs are busy during fast camera startup.");
            return false;
        }
        try {
            if (!ensureHardwareProcess(
                    startEpoch,
                    acquisitionGeneration,
                    sessionToken)) {
                if (isStartAllowed(startEpoch, acquisitionGeneration)
                        && !isOwnedByAnotherSession(sessionToken)
                        && !DiLink5CameraSafety.isCaptureSuppressed()) {
                    DiLink5CameraSafety.suppressForProcess(
                            "QCarCam supervisor did not pass safe startup");
                }
                rollbackFailedOpen();
                return false;
            }
            if (!isStartAllowed(startEpoch, acquisitionGeneration)) {
                rollbackFailedOpen();
                return false;
            }
            Process process;
            int processPid;
            String socketPath;
            int hardwareCaptureFps;
            synchronized (DiLink5QCarCamBackend.class) {
                if (sHardwareOwnerToken != sessionToken) {
                    logger.info("FastCam process ownership changed before "
                            + "native bridge initialization.");
                    return false;
                }
                process = sHardwareProcess;
                processPid = sHardwarePid;
                socketPath = sSocketPath;
                hardwareCaptureFps = sHardwareCaptureFps;
            }
            if (process == null
                    || !process.isAlive()
                    || processPid <= 0
                    || socketPath == null) {
                logger.error("fast_cam_capture exited before bridge initialization.");
                rollbackFailedOpen();
                return false;
            }
            if (!claimNativeSessionOwner()) {
                logger.info("Native FastCam bridge is still owned by another "
                        + "backend session; acquisition will retry.");
                rollbackFailedOpen();
                return false;
            }
            nativeHandle = nativeInit(
                    cameraId,
                    socketPath,
                    processPid,
                    hardwareCaptureFps);
            if (nativeHandle == 0) {
                boolean nativeSessionStillActive = false;
                try {
                    nativeSessionStillActive = nativeHasActiveSession();
                } catch (Throwable probeFailure) {
                    // A failed probe cannot prove the global JNI session is
                    // clear. Retain exact-owner cleanup debt and retire it
                    // through the wildcard native path before touching the
                    // generation-owned sidecar.
                    nativeSessionStillActive = true;
                    logger.warn("Unable to verify native session state after "
                            + "nativeInit returned zero: "
                            + probeFailure.getMessage());
                }
                if (nativeSessionStillActive) {
                    markNativeSessionOwned();
                    sNativeCleanupPending.set(true);
                    sNativeBridgeReleasedPendingProcess.set(false);
                    logger.warn("nativeInit returned zero while JNI still "
                            + "reports an active session; retaining exact "
                            + "ownership for wildcard retirement.");
                    rollbackFailedOpen();
                    return false;
                }
                if (!isStartAllowed(startEpoch, acquisitionGeneration)) {
                    logger.info("Fast camera bridge initialization was "
                            + "cancelled for system reverse handoff.");
                    closeAfterCancelledAcquisition();
                } else {
                    clearNativeSessionOwnerIfOwned();
                    DiLink5CameraSafety.suppressForProcess(
                            "Native bridge initialization failed after QCarCam acquisition");
                    logger.error("nativeInit(" + cameraId + ") failed.");
                    rollbackFailedOpen();
                }
                return false;
            }
            markNativeSessionOwned();
            if (!isStartAllowed(startEpoch, acquisitionGeneration)) {
                closeAfterCancelledAcquisition();
                return false;
            }
            logger.info("Fast camera bridge initialized: 0x"
                    + Long.toHexString(nativeHandle)
                    + " (awaiting DMA socket and first frame).");
            return true;
        } catch (Throwable t) {
            if (!isStartAllowed(startEpoch, acquisitionGeneration)) {
                logger.info("Fast camera bridge open was cancelled for "
                        + "system reverse handoff: " + t.getMessage());
                closeAfterCancelledAcquisition();
                return false;
            }
            DiLink5CameraSafety.suppressForProcess(
                    "Unexpected error while opening the QCarCam bridge");
            logger.error("Error opening DiLink 5 fast camera backend", t);
            // If nativeInit already returned a handle, retain it and arm the
            // explicit recovery path. Zeroing it here would lose the only
            // token capable of releasing the native DMA session while the
            // static ownership bit remained set.
            if (nativeHandle != 0
                    || sNativeOwnerToken == sessionToken) {
                sNativeCleanupPending.set(true);
                sNativeBridgeReleasedPendingProcess.set(false);
            } else {
                nativeHandle = 0;
                clearNativeSessionOwnerIfOwned();
            }
            rollbackFailedOpen();
            return false;
        } finally {
            releaseConfiguredInputReservation();
        }
        } finally {
            lifecycleGate.unlock();
        }
    }

    public boolean start(FrameListener listener) {
        lifecycleGate.lock();
        try {
        if (listener == null) return false;
        if (!isStartAllowed(startEpoch, acquisitionGeneration)) return false;
        if (nativeHandle == 0 && !open()) return false;
        // Reserve the callback only after this backend owns the native
        // session, but before nativeStart can publish its first frame.
        if (!installFrameListener(listener)) {
            logger.info("Fast camera listener installation was cancelled or "
                    + "is still owned by another native session.");
            closeAfterCancelledAcquisition();
            return false;
        }
        if (isStreaming.get()) return true;
        try {
            boolean started = nativeStart(nativeHandle);
            if (started) isStreaming.set(true);
            if (!started) {
                clearFrameListenerIfOwner();
                if (!isStartAllowed(startEpoch, acquisitionGeneration)) {
                    logger.info("Fast camera stream start was cancelled for "
                            + "system reverse handoff.");
                    closeAfterCancelledAcquisition();
                } else {
                    DiLink5CameraSafety.suppressForProcess(
                            "Native QCarCam stream did not start");
                }
            }
            if (started && !isStartAllowed(
                    startEpoch, acquisitionGeneration)) {
                clearFrameListenerIfOwner();
                closeAfterCancelledAcquisition();
                return false;
            }
            if (started && !publishSourceIfCurrent()) {
                logger.info("Fast camera source publication was cancelled for "
                        + "system reverse handoff.");
                clearFrameListenerIfOwner();
                closeAfterCancelledAcquisition();
                return false;
            }
            return started;
        } catch (Throwable t) {
            clearFrameListenerIfOwner();
            if (!isStartAllowed(startEpoch, acquisitionGeneration)) {
                logger.info("Fast camera stream start was cancelled for "
                        + "system reverse handoff: " + t.getMessage());
                closeAfterCancelledAcquisition();
                return false;
            }
            DiLink5CameraSafety.suppressForProcess(
                    "Native QCarCam stream start threw");
            logger.error("Error starting DiLink 5 fast camera stream", t);
            return false;
        }
        } finally {
            lifecycleGate.unlock();
        }
    }

    public void stop() {
        lifecycleGate.lock();
        try {
        if (nativeHandle == 0 || !isStreaming.get()) return;
        synchronized (DiLink5QCarCamBackend.class) {
            if (sNativeOwnerToken != sessionToken
                    || sNativeOwnerBackend != this) {
                nativeHandle = 0;
                isStreaming.set(false);
                return;
            }
        }
        if (!areOwnedGlResourcesReleased()) {
            sNativeCleanupPending.set(true);
            sNativeBridgeReleasedPendingProcess.set(false);
            logger.error("Refusing to stop the fast camera stream before "
                    + "EGLImage cleanup.");
            return;
        }
        try {
            if (nativeStop(nativeHandle)) {
                isStreaming.set(false);
                sNativeCleanupPending.set(false);
            } else {
                sNativeCleanupPending.set(true);
                sNativeBridgeReleasedPendingProcess.set(false);
                logger.error("Fast camera native stream did not stop cleanly.");
            }
        } catch (Throwable t) {
            sNativeCleanupPending.set(true);
            sNativeBridgeReleasedPendingProcess.set(false);
            logger.warn("Error stopping fast camera stream: "
                    + t.getMessage());
        }
        } finally {
            lifecycleGate.unlock();
        }
    }

    public boolean close() {
        return closeWithLifecycleGate(
                true, Long.MAX_VALUE);
    }

    /**
     * Performs one complete token-scoped retirement retry before a caller
     * considers terminating the process. Native stop/release each have their
     * own bounded waits, so a retry needs a budget larger than the old urgent
     * five-second halt window. Only BUSY is retried; invariant failures remain
     * terminal.
     */
    public boolean closeWithRetirementRetry(boolean stopAvm) {
        long deadline = android.os.SystemClock.elapsedRealtime()
                + OWNER_RETIREMENT_RETRY_BUDGET_MS;
        while (true) {
            OwnerRetirementResult result =
                    closeWithLifecycleGateResult(stopAvm, deadline);
            if (result == OwnerRetirementResult.COMPLETE) return true;
            if (result != OwnerRetirementResult.BUSY
                    || !hasDeadlineBudget(
                            deadline,
                            NATIVE_RETIREMENT_EXECUTION_BUDGET_MS)) {
                return false;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Retires a source that finished native startup but lost the atomic
     * GL-owner claim. Reverse-generation cancellation preserves system AVM;
     * daemon/start-epoch cancellation performs the normal complete close.
     */
    public boolean closeAfterFailedGlOwnerClaim() {
        return closeAfterCancelledAcquisition();
    }

    private OwnerRetirementResult closeAfterFailedGlOwnerClaimBefore(
            long deadlineElapsedMs) {
        return closeWithLifecycleGateResult(
                false, deadlineElapsedMs);
    }

    private boolean closeAfterCancelledAcquisition() {
        boolean systemAvmCancellation =
                com.overdrive.app.daemon.CameraDaemon
                        .isCameraStartEpochCurrent(startEpoch)
                        && !isAcquisitionGenerationCurrent(
                                acquisitionGeneration);
        if (systemAvmCancellation) {
            return closeWithLifecycleGate(
                    false, Long.MAX_VALUE);
        }
        return closeWithLifecycleGate(
                true, Long.MAX_VALUE);
    }

    private OwnerRetirementResult closeForRecoveryBefore(
            long deadlineElapsedMs) {
        return closeWithLifecycleGateResult(
                true, deadlineElapsedMs);
    }

    private boolean closeWithLifecycleGate(
            boolean stopAvm,
            long deadlineElapsedMs) {
        return closeWithLifecycleGateResult(
                stopAvm, deadlineElapsedMs)
                == OwnerRetirementResult.COMPLETE;
    }

    private OwnerRetirementResult closeWithLifecycleGateResult(
            boolean stopAvm,
            long deadlineElapsedMs) {
        boolean acquired;
        if (deadlineElapsedMs == Long.MAX_VALUE) {
            lifecycleGate.lock();
            acquired = true;
        } else {
            long remaining = deadlineElapsedMs
                    - android.os.SystemClock.elapsedRealtime();
            if (remaining < NATIVE_RETIREMENT_EXECUTION_BUDGET_MS) {
                return OwnerRetirementResult.BUSY;
            }
            long lockBudget = remaining
                    - NATIVE_RETIREMENT_EXECUTION_BUDGET_MS;
            acquired = lifecycleGate.tryLock(lockBudget);
        }
        if (!acquired) {
            logger.info("FastCam owner retirement is waiting for an "
                    + "existing lifecycle operation.");
            return OwnerRetirementResult.BUSY;
        }
        try {
            return closeInternal(stopAvm, deadlineElapsedMs);
        } finally {
            lifecycleGate.unlock();
        }
    }

    private OwnerRetirementResult closeInternal(
            boolean stopAvm,
            long deadlineElapsedMs) {
        if (!areOwnedGlResourcesReleased()) {
            clearFrameListenerIfOwner();
            sNativeCleanupPending.set(true);
            sNativeBridgeReleasedPendingProcess.set(false);
            logger.error("Refusing to close fast camera ownership before "
                    + "EGLImage cleanup.");
            return OwnerRetirementResult.FAILED;
        }
        boolean ownsNativeSession;
        synchronized (DiLink5QCarCamBackend.class) {
            ownsNativeSession =
                    sNativeOwnerToken == sessionToken
                            && sNativeOwnerBackend == this;
        }
        boolean hadNativeHandle =
                ownsNativeSession && nativeHandle != 0;
        boolean wasStreaming =
                ownsNativeSession && isStreaming.get();
        boolean hasUnknownNativeHandle =
                ownsNativeSession && nativeHandle == 0;
        boolean hadHardwareProcess = hasOwnedHardwareProcess(sessionToken);
        long requiredRetirementBudget =
                (wasStreaming ? NATIVE_OPERATION_BUDGET_MS : 0L)
                        + (hadNativeHandle
                                ? NATIVE_OPERATION_BUDGET_MS : 0L)
                        + (hasUnknownNativeHandle
                                ? NATIVE_OPERATION_BUDGET_MS * 2L
                                : 0L)
                        + (hadHardwareProcess
                                ? PROCESS_RETIREMENT_BUDGET_MS : 0L);
        if (!hasDeadlineBudget(
                deadlineElapsedMs, requiredRetirementBudget)) {
            logger.info("FastCam owner retirement has insufficient shared "
                    + "deadline budget; waiting for a retry.");
            return OwnerRetirementResult.BUSY;
        }
        clearFrameListenerIfOwner();
        synchronized (DiLink5QCarCamBackend.class) {
            if (sHardwareOwnerToken == sessionToken) {
                // cameraObj is being detached and no EGLImage remains. Do not
                // make a concurrent reverse cancellation wait for this
                // backend's bounded native/process shutdown.
                sHardwareSourceClaimed = false;
            }
        }
        // nativeStop/nativeRelease close the DMA socket before the later
        // Process.destroy() phase. The release guard can safely fail-close
        // with status 125 in that interval, so publish the expected-stop
        // intent before the native bridge can make the child exit.
        Process expectedProcess =
                markOwnedHardwareProcessExpectedStop(sessionToken);
        boolean released = true;
        boolean nativeRetirementBusy = false;
        if (ownsNativeSession) {
            if (nativeHandle != 0) {
                stop();
                try {
                    released = nativeRelease(nativeHandle);
                    if (!released) {
                        logger.error("Fast camera native handle release is blocked.");
                    }
                } catch (Throwable t) {
                    released = false;
                    logger.warn("Error releasing fast camera handle: "
                            + t.getMessage());
                }
            } else {
                released = retireUnknownNativeSession();
            }
            if (!released) {
                try {
                    if (!nativeHasActiveSession()) {
                        // A delayed native disconnect completed after the
                        // bounded JNI call returned false. Treat the global
                        // session as retired and continue with the exact
                        // generation-owned process.
                        released = true;
                    } else {
                        nativeRetirementBusy = true;
                    }
                } catch (Throwable probeFailure) {
                    nativeRetirementBusy = true;
                    logger.warn("Unable to verify FastCam native retirement: "
                            + probeFailure.getMessage());
                }
            }
            if (released) {
                nativeHandle = 0;
                isStreaming.set(false);
                // Native state is retired, but the generation-owned sidecar is
                // still live until the process phase below confirms exit.
                clearNativeSessionOwnerIfOwned(false);
            } else {
                sNativeCleanupPending.set(true);
                sNativeBridgeReleasedPendingProcess.set(false);
            }
        } else if (!ownsNativeSession) {
            // A recovery/newer owner already retired this backend's global
            // bridge. Never let a stale object release the replacement.
            nativeHandle = 0;
            isStreaming.set(false);
        }
        boolean processStopped =
                expectedProcess == null || !expectedProcess.isAlive();
        if (released || !ownsNativeSession) {
            try {
                processStopped = stopHardwareProcessForOwner(
                        expectedProcess,
                        sessionToken,
                        false,
                        false,
                        deadlineElapsedMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                processStopped = false;
            }
        } else {
            // The JNI bridge may still own producer buffers or a live socket.
            // Killing its sidecar now would convert a retryable native cleanup
            // failure into an uncoordinated producer death. Preserve the exact
            // generation-owned child so recovery can retry native retirement
            // first; the expected-stop marker already covers an exit caused by
            // the attempted nativeStop/nativeRelease above.
            logger.warn("Preserving fast_cam_capture because native ownership "
                    + "was not retired.");
        }
        // A timed-out process stop remains an initiated, generation-scoped
        // retirement. Keep its expected-stop marker until the retry window
        // expires so a delayed guard/disconnect exit is not misclassified.
        boolean cleanRelease =
                isCompleteOwnershipRelease(released, processStopped);
        if (ownsNativeSession) {
            synchronized (DiLink5QCarCamBackend.class) {
                if (cleanRelease) {
                    sNativeCleanupPending.set(false);
                    sNativeBridgeReleasedPendingProcess.set(false);
                } else {
                    sNativeCleanupPending.set(true);
                    sNativeBridgeReleasedPendingProcess.set(released);
                }
            }
        }
        if (cleanRelease && canMarkCleanRelease()) {
            markCleanReleaseIfNoOwnership();
        }
        if (cleanRelease) {
            return OwnerRetirementResult.COMPLETE;
        }
        if (nativeRetirementBusy
                || (expectedProcess != null
                        && expectedProcess.isAlive())) {
            return OwnerRetirementResult.BUSY;
        }
        return deadlineElapsedMs != Long.MAX_VALUE
                        && !hasDeadlineBudget(deadlineElapsedMs, 1L)
                ? OwnerRetirementResult.BUSY
                : OwnerRetirementResult.FAILED;
    }

    private boolean retireUnknownNativeSession() {
        try {
            if (!nativeHasActiveSession()) {
                return true;
            }
            if (!nativeStop(0)) {
                logger.error("Unknown FastCam native stream did not stop.");
                return false;
            }
            if (!nativeRelease(0)) {
                logger.error("Unknown FastCam native session did not release.");
                return false;
            }
            return true;
        } catch (Throwable failure) {
            logger.warn("Unable to retire unknown FastCam native ownership: "
                    + failure.getMessage());
            return false;
        }
    }

    static boolean isCompleteOwnershipRelease(
            boolean nativeReleased, boolean processStopped) {
        return nativeReleased && processStopped;
    }

    private static synchronized Process markOwnedHardwareProcessExpectedStop(
            long expectedOwnerToken) {
        Process process = sHardwareOwnerToken == expectedOwnerToken
                ? sHardwareProcess
                : null;
        if (process != null && process.isAlive()) {
            markProcessExpectedStop(process, expectedOwnerToken);
            return process;
        }
        return null;
    }

    private static void markProcessExpectedStop(
            Process process, long ownerToken) {
        if (process == null) return;
        long expiresAt = android.os.SystemClock.elapsedRealtime()
                + EXPECTED_STOP_RETRY_WINDOW_MS;
        synchronized (EXPECTED_STOP_LOCK) {
            sExpectedStopProcesses.put(
                    process,
                    new ExpectedStopMarker(ownerToken, expiresAt));
        }
    }

    private static void clearExpectedStop(Process process) {
        if (process == null) return;
        synchronized (EXPECTED_STOP_LOCK) {
            sExpectedStopProcesses.remove(process);
        }
    }

    private static boolean consumeExpectedStop(Process process) {
        if (process == null) return false;
        ExpectedStopMarker marker;
        synchronized (EXPECTED_STOP_LOCK) {
            marker = sExpectedStopProcesses.remove(process);
        }
        if (marker == null) return false;
        boolean current = android.os.SystemClock.elapsedRealtime()
                <= marker.expiresAtElapsedMs;
        if (!current) {
            logger.warn("Expired expected FastCam stop marker for owner "
                    + marker.ownerToken + "; treating exit as unexpected.");
        }
        return current;
    }

    /**
     * A replacement acquisition can observe an intentionally retired child
     * after Process.waitFor() has completed but before its output-drainer has
     * cleared the published hardware session. Keep the expected-stop marker
     * available for the drainer while atomically clearing that stale
     * publication. Otherwise the replacement can write a false boot-wide
     * camera block during a normal daemon/reverse shutdown.
     */
    private static synchronized void
            classifyObservedHardwareExitAndClearIfCurrent(Process process) {
        if (process == null
                || process.isAlive()
                || sHardwareProcess != process) {
            return;
        }
        if (isExpectedStopCurrent(process)) {
            logger.info("Clearing an intentionally stopped FastCam child "
                    + "before its output-drainer completed.");
        } else {
            // Process death is positive proof that the producer no longer
            // owns QCarCam. Keep the JNI/client owner published so the frame
            // watchdog can retire it on the GL thread and reopen only the
            // source. A boot-wide block here made every transient child crash
            // (including the release guard's fail-closed status 125) defeat
            // that recovery path even though no producer ownership survived.
            int exitCode;
            try {
                exitCode = process.exitValue();
            } catch (Throwable ignored) {
                exitCode = Integer.MIN_VALUE;
            }
            if (isAisStallSelfExit(exitCode)) {
                logger.warn("Clearing a FastCam child that self-exited on an "
                        + "AIS pipeline stall (code " + exitCode + "); "
                        + "source-only recovery will retire the remaining "
                        + "client");
            } else {
                logger.warn("Clearing an unexpectedly exited FastCam child; "
                        + "source-only recovery will retire the remaining client"
                        + releaseGuardReasonSuffix());
            }
        }
        clearHardwareSession();
    }

    /**
     * Must match REASON_FILE_PATH in fast_cam_release_guard.c: the guard
     * writes ONE line there describing why it failed closed (which step,
     * errno, frame ids) right before {@code _exit(125)}.
     */
    private static final String RELEASE_GUARD_REASON_PATH =
            "/data/local/tmp/overdrive_dilink5_release_guard_reason";

    /**
     * Read-and-consume the release guard's fail-closed reason as a log
     * suffix, or "" when none exists. The daemon log is truncated in place
     * under pressure, which previously erased the initiating cause of an
     * exit-125 (log_2MEH8B86); the reason file survives truncation, and
     * consuming (deleting) it here keeps a stale reason from being
     * attributed to a later, unrelated exit.
     */
    private static String releaseGuardReasonSuffix() {
        java.io.File file = new java.io.File(RELEASE_GUARD_REASON_PATH);
        try {
            if (!file.isFile() || file.length() <= 0L
                    || file.length() > 4_096L) {
                return "";
            }
            byte[] raw = new byte[(int) file.length()];
            int count;
            try (java.io.FileInputStream input =
                    new java.io.FileInputStream(file)) {
                count = input.read(raw);
            }
            if (count <= 0) return "";
            String line = new String(raw, 0, count,
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            int newline = line.indexOf('\n');
            if (newline > 0) line = line.substring(0, newline).trim();
            if (line.isEmpty()) return "";
            if (line.length() > 256) line = line.substring(0, 256);
            return " (release guard: " + line + ")";
        } catch (Throwable failure) {
            return "";
        } finally {
            try {
                if (file.exists() && !file.delete()) {
                    logger.warn("Unable to consume the release guard "
                            + "reason file.");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Consume the stop marker and retire the published process under the same
     * ownership monitor. This closes the marker-consumed/session-still-dead
     * race with a concurrent replacement acquisition.
     */
    private static synchronized boolean
            classifyDrainedHardwareExitAndClear(Process process) {
        boolean expected = consumeExpectedStop(process);
        if (!expected) {
            int exitCode;
            try {
                exitCode = process.exitValue();
            } catch (Throwable ignored) {
                exitCode = Integer.MIN_VALUE;
            }
            if (isAisStallSelfExit(exitCode)) {
                logger.warn("fast_cam_capture self-exited after an AIS "
                        + "pipeline stall (code " + exitCode + "); "
                        + "source-only recovery remains enabled");
            } else {
                logger.warn((exitCode == Integer.MIN_VALUE
                        ? "fast_cam_capture exited unexpectedly; source-only "
                                + "recovery remains enabled"
                        : "fast_cam_capture exited unexpectedly with code "
                                + exitCode
                                + "; source-only recovery remains enabled")
                        + releaseGuardReasonSuffix());
            }
        }
        if (sHardwareProcess == process && !process.isAlive()) {
            clearHardwareSession();
        }
        return expected;
    }

    private static boolean isExpectedStopCurrent(Process process) {
        if (process == null) return false;
        ExpectedStopMarker marker;
        synchronized (EXPECTED_STOP_LOCK) {
            marker = sExpectedStopProcesses.get(process);
        }
        return marker != null
                && android.os.SystemClock.elapsedRealtime()
                        <= marker.expiresAtElapsedMs;
    }

    private static long ownerTokenForProcess(Process process) {
        synchronized (DiLink5QCarCamBackend.class) {
            return process != null && sHardwareProcess == process
                    ? sHardwareOwnerToken
                    : Long.MIN_VALUE;
        }
    }

    private static boolean hasOwnedHardwareProcess(long ownerToken) {
        synchronized (DiLink5QCarCamBackend.class) {
            return sHardwareOwnerToken == ownerToken
                    && sHardwareProcess != null
                    && sHardwareProcess.isAlive();
        }
    }

    private static boolean canMarkCleanRelease() {
        synchronized (DiLink5QCarCamBackend.class) {
            return sHardwareOwnerToken == Long.MIN_VALUE
                    && sNativeOwnerToken == Long.MIN_VALUE
                    && !sNativeSessionOwned.get()
                    && !sNativeCleanupPending.get()
                    && !sNativeBridgeReleasedPendingProcess.get()
                    && sFrameListenerOwnerToken == Long.MIN_VALUE
                    && sGlResourcesOwnerToken == Long.MIN_VALUE
                    && !sHardwareStartup.isActive();
        }
    }

    private static void markCleanReleaseIfNoOwnership() {
        long expectedLeaseGeneration;
        synchronized (DiLink5QCarCamBackend.class) {
            if (!canMarkCleanRelease()) return;
            expectedLeaseGeneration = sSafetyLeaseGeneration;
        }
        if (expectedLeaseGeneration == Long.MIN_VALUE) return;
        boolean cleared = DiLink5CameraSafety.markCleanRelease(
                expectedLeaseGeneration);
        if (!cleared) return;
        synchronized (DiLink5QCarCamBackend.class) {
            if (sSafetyLeaseGeneration == expectedLeaseGeneration
                    && canMarkCleanRelease()) {
                sSafetyLeaseGeneration = Long.MIN_VALUE;
                sSafetyLeaseOwnerToken = Long.MIN_VALUE;
            }
        }
    }

    public boolean isStreaming() {
        return isStreaming.get();
    }

    public static void setActiveCamera(int cameraIndex) {
        if (cameraIndex < 0
                || cameraIndex > 6
                || cameraIndex == 5
                || !DiLink5Platform.isEnabled()) {
            return;
        }
        try {
            nativeSetActiveCamera(cameraIndex);
        } catch (Throwable t) {
            logger.warn("Failed to select fast camera view: "
                    + t.getMessage());
        }
    }

    public static void setOutputFps(int fps) {
        if (!DiLink5Platform.isEnabled()
                || !ensureNativeLibrariesLoaded(null)) {
            return;
        }
        int clamped = Math.max(1, Math.min(30, fps));
        int captureFps = sHardwareCaptureFps;
        if (captureFps > 0 && clamped > captureFps) {
            logger.warn("Requested output FPS " + clamped
                    + " exceeds the explicit source cap " + captureFps
                    + "; update " + CAPTURE_FPS_PROPERTY
                    + " and reopen the camera to raise it.");
        }
        try {
            nativeSetOutputFps(clamped);
        } catch (Throwable t) {
            logger.warn("Failed to set fast camera output FPS: "
                    + t.getMessage());
        }
    }

    private static native boolean nativeIsSupported();
    private static native boolean nativeHasActiveSession();
    private static native void nativeSetActiveCamera(int cameraIndex);
    private static native void nativeSetOutputFps(int fps);
    private static native int nativeBindLatestFrame(int textureId);
    private static native boolean nativeReleaseGlResources();
    private static native void nativeSetCameraMapping(
            int front, int right, int rear, int left, int dashcam);
    private native long nativeInit(
            int inputId, String socketPath, long serverPid, int captureFps);
    private native boolean nativeStart(long handle);
    private native boolean nativeStop(long handle);
    private native boolean nativeRelease(long handle);
}

final class DiLink5CameraMapping {
    private static final int MAX_HARDWARE_CAMERA_ID = 255;

    private DiLink5CameraMapping() {}

    static int[] parse(String csv) {
        if (csv == null || csv.trim().isEmpty()) return null;
        String[] parts = csv.split(",", -1);
        if (parts.length != 4 && parts.length != 5) return null;

        int[] result = new int[parts.length];
        boolean[] used = new boolean[MAX_HARDWARE_CAMERA_ID + 1];
        for (int i = 0; i < parts.length; i++) {
            String value = parts[i].trim();
            if (!value.matches("[0-9]{1,3}")) return null;
            int cameraId;
            try {
                cameraId = Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
            // ponytail: vendor camera IDs fit in one byte; widen if AIS exposes larger IDs.
            if (cameraId > MAX_HARDWARE_CAMERA_ID || used[cameraId]) {
                return null;
            }
            used[cameraId] = true;
            result[i] = cameraId;
        }
        return result;
    }

    static int[] forPlatform(
            String configuredModel, String buildModel, String buildProduct) {
        String identity = ((configuredModel != null ? configuredModel : "")
                + " " + (buildModel != null ? buildModel : "")
                + " " + (buildProduct != null ? buildProduct : ""))
                .toLowerCase(java.util.Locale.ROOT);
        return identity.contains("shark") || identity.contains("dmo")
                ? new int[]{8, 9, 5, 4}
                : new int[]{0, 1, 2, 3};
    }

    static String toCsv(int[] mapping) {
        StringBuilder value = new StringBuilder();
        for (int cameraId : mapping) {
            if (value.length() > 0) value.append(',');
            value.append(cameraId);
        }
        return value.toString();
    }
}
