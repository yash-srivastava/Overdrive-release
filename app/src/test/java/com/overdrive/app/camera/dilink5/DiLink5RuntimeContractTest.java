package com.overdrive.app.camera.dilink5;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.junit.Test;

public class DiLink5RuntimeContractTest {

    @Test
    public void reverseCannotMissAJustPublishedFastCamSource()
            throws IOException {
        String backend = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/"
                        + "DiLink5QCarCamBackend.java");
        String gpu = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/"
                        + "PanoramicCameraGpu.java");

        assertTrue(backend.contains(
                "private static volatile boolean sHardwareSourceClaimed;"));
        assertTrue(backend.contains(
                "public boolean claimPublishedSourceForGlOwner()"));
        assertTrue(backend.contains(
                "public boolean closeAfterFailedGlOwnerClaim()"));
        // DI5 no longer yields the FastCam source to OEM reverse and never
        // drives the vendor com.ts.avm service from the daemon. The AIS server
        // multiplexes the QCarCam inputs, so the reverse handoff/admission
        // fence and the AVM start/stop reconciliation must stay removed.
        assertFalse(backend.contains("sSystemAvmSourceHandoffActive"));
        assertFalse(backend.contains("cancelPendingAcquisitionForSystemAvm"));
        assertFalse(backend.contains("completeSystemAvmSourceHandoff"));
        assertFalse(backend.contains("releaseAvmAfterSystemHandoff"));
        assertFalse(backend.contains("TsAvmCoordinator."));
        assertFalse(gpu.contains("onDiLink5GearChanged"));
        assertFalse(gpu.contains("pauseDiLink5CameraSourceForReverse"));
        assertTrue(gpu.contains(
                "private boolean shouldHoldForDiLink5Reverse() {\n"
                        + "        return false;"));
        assertTrue(backend.contains(
                "NATIVE_RECOVERY_WAIT_TIMEOUT_MS = 12_000L"));
        assertTrue(backend.contains(
                "private static final HardwareStartupBarrier "
                        + "sHardwareStartup"));
        assertTrue(backend.contains(
                "private final LifecycleGate lifecycleGate"));

        int claim = gpu.indexOf(
                "claimPublishedSourceForGlOwner()");
        assertTrue(claim >= 0);
        String publication = gpu.substring(
                gpu.lastIndexOf("synchronized (", claim),
                gpu.indexOf(
                        "logger.info(\"DiLink 5 native QCarCam stream initialized",
                        claim));
        assertOrdered(
                publication,
                "synchronized (",
                "claimPublishedSourceForGlOwner()",
                "cameraObj = dilink5Backend;",
                "if (!sourceClaimed)",
                "closeWithRetirementRetry(false)");
    }

    @Test
    public void safeCpuTransportIsDefaultAndDirectDmaRequiresCodeFlag()
            throws IOException {
        String bridge = readRepositoryFile(
                "app/src/main/cpp/camera/qcarcam_bridge.cpp");
        String cmake = readRepositoryFile(
                "app/src/main/cpp/CMakeLists.txt");

        assertTrue(cmake.contains(
                "set(OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA 0)"));
        assertTrue(cmake.contains(
                "OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA="
                        + "${OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA}"));
        assertTrue(bridge.contains(
                "#define OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA 0"));
        assertTrue(bridge.contains(
                "constexpr bool kDirectDmaEnabled ="));
        assertTrue(bridge.contains(
                "constexpr IngestMode configuredInitialIngestMode()"));
        assertTrue(bridge.contains(
                "constexpr bool shouldUseCpuCopy(IngestMode mode)"));
        assertTrue(bridge.contains(
                "return !kDirectDmaEnabled "
                        + "|| mode == IngestMode::kCpuFallback;"));
        assertTrue(bridge.contains(
                "g_ingest_mode{configuredInitialIngestMode()}"));
        assertOrdered(
                bridge.substring(bridge.indexOf(
                        "Java_com_overdrive_app_camera_dilink5_"
                                + "DiLink5QCarCamBackend_nativeInit")),
                "IngestMode configured_mode = configuredInitialIngestMode();",
                "g_ingest_mode.store(configured_mode);",
                "Fast camera ingest policy:");
        assertTrue(bridge.contains(
                "IngestMode mode = g_ingest_mode.load();\n"
                        + "        if (shouldUseCpuCopy(mode)) {"));

        // The experimental implementation remains compiled and available
        // when the code flag is deliberately changed to 1.
        assertTrue(bridge.contains("bool importDmaBufferLocked("));
        assertTrue(bridge.contains("bool drawDirectFrameLocked("));
        assertTrue(bridge.contains(
                "g_ingest_mode.store(IngestMode::kDirectDma);"));
        assertTrue(bridge.contains(
                "g_ingest_mode.store(IngestMode::kCpuFallback);"));
    }

    @Test
    public void cpuTransportStagesConsumedBytesAndReleasesBeforeConvert()
            throws IOException {
        String bridge = readRepositoryFile(
                "app/src/main/cpp/camera/qcarcam_bridge.cpp");

        // Copy phase stages only the consumed pixel subset (decimated mosaic
        // quadrants / center-cropped single frames), forecast-skips copies no
        // publish will read, and the producer buffer is released BEFORE the
        // convert phase runs — holding it across a mosaic render starved the
        // producer pool on publish ticks.
        assertTrue(bridge.contains(
                "bool copyCpuCameraLocked(int slot, const FastCamFrame& frame,"
                        + " bool decimate)"));
        assertTrue(bridge.contains(
                "copyCpuCameraLocked(slot, frame, true)"));
        assertTrue(bridge.contains(
                "copyCpuCameraLocked(slot, frame, false)"));
        assertTrue(bridge.contains(
                "constexpr bool peekNextPacingPublish("));
        assertOrdered(
                bridge.substring(bridge.indexOf(
                        "convert_pending = prepareCpuCopyLocked(")),
                "peekNextPacingPublish(",
                "releaseClientFrame(&client, frame, -1)",
                "finishCpuOutputLocked(");

        // GPU unpack stage converts app-owned staging bytes only — it must
        // never import a producer DMA-BUF (that is the direct-DMA path, which
        // stays disabled) — and any GL failure latches the session back to
        // the NEON convert path. The persist prop is the field kill switch.
        assertTrue(bridge.contains(
                "persist.overdrive.dilink5_gpu_unpack"));
        assertTrue(bridge.contains(
                "bool runGpuUnpackLocked(GLuint texture_id)"));
        assertTrue(bridge.contains(
                "FastCam GPU unpack failed; reverting to NEON convert path"));
        String unpackTexture = bridge.substring(
                bridge.indexOf("bool ensureUnpackTextureLocked()"),
                bridge.indexOf("bool uploadUnpackStagingLocked()"));
        assertTrue(unpackTexture.contains("GL_NEAREST"));
        String unpackStage = bridge.substring(
                bridge.indexOf("bool ensureUnpackRendererLocked()"),
                bridge.indexOf("bool importDmaBufferLocked("));
        assertFalse(unpackStage.contains("eglCreateImageKHR"));
        assertFalse(unpackStage.contains("EGL_LINUX_DMA_BUF_EXT"));
        assertFalse(unpackStage.contains("GL_TEXTURE_EXTERNAL_OES"));
    }

    @Test
    public void aisStallSelfExitIsClassifiedAsProducerHealthNotCrash()
            throws IOException {
        String backend = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/"
                        + "DiLink5QCarCamBackend.java");

        // Exit 42 is the stall-aware fast_cam_capture lineage's deliberate
        // AIS-stall self-exit. It must be diagnosed as a producer health
        // exit at every death-classification site — never as a crash — while
        // recovery handling stays identical to any other producer death.
        assertTrue(backend.contains(
                "private static final int FAST_CAM_EXIT_AIS_STALL = 42;"));
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private static void drainHardwareOutput(Process process)")),
                "classifyDrainedHardwareExitAndClear(process)",
                "fast_cam_capture stopped with code ",
                "isAisStallSelfExit(exitCode)",
                "fast_cam_capture exited unexpectedly with code ");
        assertOrdered(
                backend.substring(backend.indexOf(
                        "classifyObservedHardwareExitAndClearIfCurrent(Process process)")),
                "isExpectedStopCurrent(process)",
                "isAisStallSelfExit(exitCode)",
                "Clearing an unexpectedly exited FastCam child;",
                "clearHardwareSession();");
    }

    @Test
    public void qcarcamOwnershipFailsClosedAcrossDaemonGenerations()
            throws IOException {
        String safety = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/"
                        + "DiLink5CameraSafety.java");
        String backend = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/"
                        + "DiLink5QCarCamBackend.java");
        String coordinator = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/"
                        + "TsAvmCoordinator.java");
        String platform = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/"
                        + "DiLink5Platform.java");
        String keepalive = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/"
                        + "DaemonKeepaliveService.kt");
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/"
                        + "PanoramicCameraGpu.java");

        assertTrue(safety.contains(
                "ScratchPaths.path(\"overdrive_dilink5_camera_session\")"));
        assertTrue(safety.contains(
                "ScratchPaths.path(\"overdrive_dilink5_camera_blocked\")"));
        assertTrue(safety.contains(
                "ScratchPaths.path(\"overdrive_dilink5_camera_release\")"));
        assertFalse(safety.contains(
                "\"/data/local/tmp/overdrive_dilink5_camera_"));
        assertTrue(safety.contains(
                "/proc/sys/kernel/random/boot_id"));
        assertTrue(safety.contains(
                "CROSS_PROCESS_REACQUIRE_COOLDOWN_MS = 15_000L"));
        assertTrue(safety.contains(
                "SAME_PROCESS_REACQUIRE_COOLDOWN_MS = 3_000L"));
        assertOrdered(
                safety.substring(safety.indexOf(
                        "static boolean runPreflight(")),
                "admitCurrentProcess()",
                "awaitStableSystemServices(\n"
                        + "                startEpoch, acquisitionGeneration)",
                "awaitReacquireCooldown(\n"
                        + "                startEpoch, acquisitionGeneration)",
                "probeSystemServices()");
        // The daemon never binds/probes the vendor AVM service any more, and
        // no marker disables the camera for a whole vehicle boot: a lease a
        // dead daemon left behind is recovered as an unclean release (foreign
        // release fence + cross-process cooldown), and a block marker written
        // by an earlier build is cleared rather than honored.
        assertFalse(safety.contains("TsAvmCoordinator"));
        assertFalse(safety.contains("disableForCurrentBoot("));
        assertFalse(safety.contains("persistentBootBlock"));
        assertOrdered(
                safety.substring(safety.indexOf(
                        "private static synchronized boolean admitCurrentProcess()")),
                "if (blockMarker.exists())",
                "safeDelete(blockMarker);",
                "Previous daemon ended without releasing QCarCam",
                "safeDelete(sessionMarker);",
                "recordForeignRelease(0);");
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private static boolean ensureHardwareProcess")),
                "stopStaleCaptureProcesses(binary)",
                "DiLink5CameraSafety.runPreflight(\n"
                        + "                    startEpoch, acquisitionGeneration)",
                "beginHardwareStartupIfCurrent(",
                "DiLink5CameraSafety.beginAcquisition()",
                "startedProcess = processBuilder.start();");
        assertTrue(safety.contains(
                "activeAcquisitionLeaseGeneration"));
        assertTrue(safety.contains(
                "markCleanRelease(\n"
                        + "            long leaseGeneration)"));
        assertFalse(backend.contains(
                "private static synchronized boolean ensureHardwareProcess("));
        assertTrue(backend.contains(
                "private static synchronized boolean "
                        + "installHardwareSessionAfterSpawn("));
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private static boolean stopOwnedHardwareProcess")),
                "synchronized (DiLink5QCarCamBackend.class)",
                "process = sHardwareProcess;",
                "stopHardwareProcesses(\n"
                        + "                process, alwaysSettle, "
                        + "deadlineElapsedMs)",
                "synchronized (DiLink5QCarCamBackend.class)",
                "clearHardwareSession();",
                "markCleanReleaseIfNoOwnership();");
        assertFalse(backend.contains(
                "private static synchronized boolean stopOwnedHardwareProcess"));
        assertTrue(coordinator.contains("ACTION_PROBE_AVM"));
        assertTrue(coordinator.contains("requestProbeInAppProcess()"));
        assertTrue(coordinator.contains(
                "requestProbeResultInAppProcess()"));
        assertTrue(coordinator.contains("probeAvmService("));
        assertTrue(keepalive.contains("probeDiLink5Avm"));
        assertTrue(keepalive.contains(".probeAvmService("));
        assertTrue(keepalive.contains(
                ".refreshActiveModeFromCommittedMarker()"));
        assertTrue(keepalive.contains(
                ".isVehicleOnOnlyModeSnapshot()"));
        String markerRead = platform.substring(
                platform.indexOf("private static String readMode(String path)"),
                platform.indexOf(
                        "static String readModeGeneration(String path)"));
        assertTrue(markerRead.contains(
                "file.length() > MODE_MARKER_MAX_BYTES"));
        assertFalse(camera.contains(
                "disableDiLink5CameraForCurrentBoot("));
        assertTrue(camera.contains(
                "requestDiLink5SourceOnlyRecovery("));
        assertTrue(camera.contains(
                "scheduleDiLink5DeferredReacquire("));
        assertTrue(camera.contains(
                "FastCam bridge frame stall"));
        int stall = camera.indexOf(
                "FastCam bridge frame stall");
        int nextBranch = camera.indexOf(
                "} else if (cameraCoordinator != null)", stall);
        assertTrue(stall >= 0 && nextBranch > stall);
        assertFalse(camera.substring(stall, nextBranch)
                .contains("requestUrgentCameraReleaseRestart"));
        assertFalse(camera.substring(stall, nextBranch)
                .contains("disableForCurrentBoot"));
        assertFalse(camera.substring(stall, nextBranch)
                .contains("yieldCameraInternal"));
        String yieldCallback = camera.substring(
                camera.indexOf("public boolean onYieldCamera()"),
                camera.indexOf("public void onReacquireCamera()"));
        assertOrdered(
                yieldCallback,
                "if (USE_DILINK5_QCARCAM_PATH)",
                "Ignoring legacy camera-yield callback on ",
                "return true;",
                "cameraYielded = true;",
                "yieldCameraInternal()");
        String diLink5YieldBranch = yieldCallback.substring(
                yieldCallback.indexOf("if (USE_DILINK5_QCARCAM_PATH)"),
                yieldCallback.indexOf("return true;")
                        + "return true;".length());
        assertFalse(diLink5YieldBranch.contains("cameraYielded ="));
        assertTrue(camera.contains(
                "private volatile boolean diLink5SystemAvmFrameGate = false;"));
        assertTrue(camera.contains(
                "private boolean isCameraFrameConsumptionPaused()"));
        assertTrue(camera.contains(
                "private boolean "
                        + "isCameraReacquireBlockedByOwnershipHandoff()"));
        assertTrue(camera.contains(
                "private final AtomicBoolean diLink5SourceRecoveryActive"));
        assertTrue(camera.contains(
                "private final AtomicInteger diLink5SourceRecoveryEpoch"));
        assertTrue(camera.contains(
                "diLink5SourceRecoveryActive.compareAndSet(false, true)"));
        assertTrue(camera.contains(
                "recoveryEpoch != diLink5SourceRecoveryEpoch.get()"));
        assertTrue(camera.contains(
                "DILINK5_OWNERSHIP_TRANSITION_TIMEOUT_MS = 30_000L"));
        String startCamera = camera.substring(
                camera.indexOf("private void startCamera() throws Exception"),
                camera.indexOf(
                        "private void startCameraViaAvmReflection",
                        camera.indexOf(
                                "private void startCamera() throws Exception")));
        assertTrue(startCamera.contains(
                "if (!USE_DILINK5_QCARCAM_PATH\n"
                        + "                && cameraCoordinator != null"));
        String genericYield = camera.substring(
                camera.indexOf(
                        "private boolean yieldCameraInternal("
                                + "boolean preserveDiLink5Avm)"),
                camera.indexOf(
                        "private void attemptReacquireOnGlThread()"));
        assertOrdered(
                genericYield,
                "if (USE_DILINK5_QCARCAM_PATH)",
                "requestDiLink5SourceOnlyRecovery(",
                "return false;",
                "yieldListener.onPreYield()");
        String genericRestart = camera.substring(
                camera.indexOf(
                        "private void restartCameraAfterError()"),
                camera.indexOf(
                        "private boolean "
                                + "stopEncoderDrainersBeforeCameraClose("));
        assertOrdered(
                genericRestart,
                "if (USE_DILINK5_QCARCAM_PATH)",
                "requestDiLink5SourceOnlyRecovery(",
                "return;",
                "restartInProgress.compareAndSet(false, true)");
        assertTrue(camera.contains(
                "DiLink 5 camera startup degraded to camera-off mode"));
        assertTrue(camera.contains(
                "if (USE_DILINK5_QCARCAM_PATH) {\n"
                        + "            initTimeoutMs = 35_000L;"));
        assertTrue(camera.contains(
                "} else if (USE_DILINK4_AVM_PATH) {\n"
                        + "            initTimeoutMs = 20_000L;"));
    }

    @Test
    public void avmAidlMatchesTheOemTransactionPrefix() throws IOException {
        String service = readRepositoryFile(
                "app/src/main/aidl/com/ts/avm/IAvmServiceInterface.aidl");
        assertOrdered(
                service,
                "int getAvmStatus();",
                "int registerAvmStatusListener(IAvmServiceListener listener);",
                "int unregisterAvmStatusListener(IAvmServiceListener listener);",
                "void startAvm();",
                "void stopAvm();");
        assertTrue(readRepositoryFile(
                "app/src/main/aidl/com/ts/avm/IAvmServiceListener.aidl")
                .contains("oneway interface"));
    }

    @Test
    public void fastCamRuntimeMatchesTheLatestDmaContract()
            throws IOException {
        String bridge = readRepositoryFile(
                "app/src/main/cpp/camera/qcarcam_bridge.cpp");
        String clientHeader = readRepositoryFile(
                "app/src/main/cpp/include/fast_cam_bridge.h");
        String client = readRepositoryFile(
                "app/src/main/cpp/camera/fast_cam_bridge.cpp");
        String releaseGuard = readRepositoryFile(
                "app/src/main/cpp/camera/fast_cam_release_guard.c");
        String ipc = readRepositoryFile(
                "app/src/main/cpp/include/fast_cam_ipc.h");
        String cmake = readRepositoryFile(
                "app/src/main/cpp/CMakeLists.txt");
        String backend = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/DiLink5QCarCamBackend.java");
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String gpu = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String glUtil = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/GlUtil.java");
        String recorder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuMosaicRecorder.java");
        String downscaler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuDownscaler.java");
        String cropper = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/FoveatedCropper.java");
        String streamScaler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/streaming/GpuStreamScaler.java");
        String previewSampler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/HighResPreviewSampler.java");
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");
        String proguard = readRepositoryFile("app/proguard-rules.pro");
        String manifest = readRepositoryFile("app/src/main/AndroidManifest.xml");

        assertTrue(bridge.contains("kSensorHeight = 1300"));
        assertTrue(bridge.contains("kOutputHeight = 1080"));
        assertTrue(bridge.contains(
                "kCropTop = (kSensorHeight - kOutputHeight) / 2"));
        assertTrue(bridge.contains("kDefaultCaptureFps = 30"));
        assertTrue(bridge.contains("g_active_camera{4}"));
        assertTrue(bridge.contains("g_output_fps{15}"));
        assertTrue(bridge.contains(
                "g_capture_fps{kDefaultCaptureFps}"));
        assertTrue(bridge.contains("#include <arm_neon.h>"));
        assertTrue(bridge.contains("#include <GLES2/gl2.h>"));
        assertTrue(bridge.contains("x <= width - 16"));
        assertTrue(bridge.contains("source += 32"));
        assertTrue(bridge.contains("int32x4_t"));
        assertFalse(bridge.contains("vmulq_n_s16"));
        assertTrue(bridge.contains("composeMosaicRow("));
        assertTrue(bridge.contains("renderMosaicUyvyToRgba("));
        assertTrue(bridge.contains(
                "top ? front.pixels : rear.pixels"));
        assertTrue(bridge.contains(
                "top ? right.pixels : left.pixels"));
        assertTrue(bridge.contains(
                "semantic_slot == 1 || semantic_slot == 3"));
        assertTrue(bridge.contains(
                "semantic_slot == 0 || semantic_slot == 1"));
        assertTrue(bridge.contains(
                "alignas(64) uint8_t mosaic_row[kOutputWidth * 2]"));
        assertTrue(bridge.contains("vld2q_u32("));
        assertTrue(bridge.contains("vst1q_u32("));
        assertFalse(bridge.contains(
                "static uint8_t mosaic[\n"
                        + "            kOutputWidth * kOutputHeight * 2]"));
        assertTrue(bridge.contains(
                "static_assert(mosaicSourceYForOutputRow(0) == kCropTop)"));
        assertTrue(bridge.contains(
                "static_assert(mosaicSourcePairForOutputPair(0) == 0)"));
        assertTrue(bridge.contains("struct CameraMapping"));
        assertTrue(bridge.contains("semanticSlotFor(frame.cam_id)"));
        assertTrue(bridge.contains("g_cpu_cameras[FAST_CAM_MAX_CAMS]"));
        assertTrue(bridge.contains("copyCpuCameraLocked("));
        assertTrue(bridge.contains("importDmaBufferLocked("));
        assertTrue(bridge.contains(
                "source_stride >= width * 2 ? source_stride : width * 2"));
        assertTrue(bridge.contains(
                "frame.stride <= kSensorWidth * 4"));
        assertTrue(bridge.contains("bool ensureStreamThread()"));
        assertTrue(bridge.contains(
                "Connected to owned fast_cam_capture DMA socket"));
        assertTrue(bridge.contains("First fast camera output frame ready"));
        assertOrdered(
                bridge,
                "int capture_fps = g_capture_fps.load();",
                "int output_fps = g_output_fps.load();",
                "publish = advancePacingPhase(",
                "IngestMode mode = g_ingest_mode.load();");
        assertTrue(bridge.contains(
                "pacing_phase = capture_fps - output_fps"));
        assertTrue(bridge.contains(
                "static_assert(pacedFrameCount(30, 1, 30) == 1)"));
        assertTrue(bridge.contains(
                "static_assert(pacedFrameCount(30, 20, 30) == 20)"));
        assertTrue(bridge.contains(
                "static_assert(pacedFrameCount(30, 30, 30) == 30)"));
        assertTrue(bridge.contains(
                "static_assert(pacedFrameCount(15, 15, 15) == 15)"));
        assertTrue(bridge.contains(
                "nativeSetOutputFps"));
        assertTrue(bridge.contains("EGL_LINUX_DMA_BUF_EXT"));
        assertTrue(bridge.contains("EGL_LINUX_DRM_FOURCC_EXT"));
        assertTrue(bridge.contains("EGL_DMA_BUF_PLANE0_FD_EXT"));
        assertTrue(bridge.contains("eglCreateImageKHR"));
        assertTrue(bridge.contains("glEGLImageTargetTexture2DOES"));
        assertTrue(bridge.contains("GL_TEXTURE_EXTERNAL_OES"));
        assertTrue(bridge.contains(
                "EGL_SYNC_NATIVE_FENCE_ANDROID"));
        assertTrue(bridge.contains(
                "eglDupNativeFenceFDANDROID"));
        assertTrue(bridge.contains(
                "Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeReleaseGlResources"));
        assertTrue(bridge.contains(
                "bool finishGpuLocked(const char* operation)"));
        assertTrue(bridge.contains(
                "bool createReleaseFenceLocked(int* output_fence_fd)"));
        assertTrue(bridge.contains(
                "FastCam release fence failed; abandoning producer ownership"));
        assertTrue(bridge.contains(
                "FastCam GL cleanup failed; abandoning producer ownership"));
        assertFalse(bridge.contains("forgetGlResourcesLocked"));
        assertTrue(bridge.contains(
                "Refusing FastCam rendering after EGL context ownership changed"));
        assertTrue(bridge.contains(
                "FastCam GL ownership changed; stopping for safe process restart"));
        assertFalse(bridge.contains("AHardwareBuffer_"));
        assertTrue(bridge.contains("glTexSubImage2D("));
        assertTrue(bridge.contains("DMA_BUF_IOCTL_SYNC"));
        assertTrue(bridge.contains(
                "DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ"));
        assertTrue(bridge.contains(
                "DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ"));
        assertTrue(bridge.contains(
                "CPU fallback unavailable: DMA-BUF slot"));
        assertFalse(bridge.contains("ANativeWindow"));
        assertFalse(bridge.contains("nativeStartSurface"));
        assertTrue(clientHeader.contains("sequence_no"));
        assertTrue(clientHeader.contains("dma_buf_fd"));
        assertTrue(clientHeader.contains("fast_cam_client_release_frame"));
        assertTrue(clientHeader.contains("fast_cam_client_is_connected"));
        assertTrue(client.contains("SCM_RIGHTS"));
        assertTrue(client.contains("FAST_CAM_MSG_FRAME_RELEASE"));
        assertFalse(client.contains("MSG_WAITALL"));
        assertOrdered(
                client.substring(client.indexOf(
                        "bool receiveHandshake(FastCamClientCtx* context)")),
                "void* mapped = mmap(",
                "if (mapped == MAP_FAILED)",
                "return false;",
                "context->mapped_ptrs[slot] = mapped;");
        assertTrue(ipc.contains("FAST_CAM_CAP_FRAME_RELEASE_FENCE"));
        assertFalse(releaseGuard.contains("LD_PRELOAD"));
        assertTrue(releaseGuard.contains("receive_release("));
        assertTrue(releaseGuard.contains("wait_for_fence("));
        assertTrue(releaseGuard.contains("shutdown(socket_fd, SHUT_RDWR)"));
        assertTrue(releaseGuard.contains("_exit(125)"));
        assertTrue(releaseGuard.contains(
                "sent != (ssize_t)sizeof(frame)"));
        assertTrue(releaseGuard.contains(
                "sent != (ssize_t)sizeof(guarded)"));
        assertFalse(releaseGuard.contains("MSG_WAITALL"));
        assertTrue(clientHeader.contains(
                "int expected_server_pid"));
        assertTrue(client.contains("SO_PEERCRED"));
        assertTrue(client.contains(
                "credentials.pid != expected_server_pid"));
        assertTrue(cmake.contains(
                "add_library(fast_cam_release_guard SHARED"));
        assertTrue(cmake.contains(
                "add_library(dilink5_camera SHARED"));
        assertTrue(cmake.contains(
                "target_link_libraries(dilink5_camera"));
        String diLink5LinkLibraries = cmake.substring(
                cmake.indexOf("target_link_libraries(dilink5_camera"),
                cmake.indexOf("target_compile_options(dilink5_camera"));
        assertTrue(diLink5LinkLibraries.contains("EGL"));
        assertTrue(diLink5LinkLibraries.contains("GLESv2"));
        assertTrue(cmake.contains("camera/fast_cam_bridge.cpp"));
        assertTrue(cmake.contains("camera/fast_cam_release_guard.c"));
        assertTrue(cmake.contains("camera/qcarcam_bridge.cpp"));
        assertFalse(cmake.contains("camera/hook_qcarcam.cpp"));
        int surveillanceSources = cmake.indexOf("set(SURVEILLANCE_SOURCES");
        int surveillanceTarget = cmake.indexOf(
                "add_library(surveillance", surveillanceSources);
        assertTrue(surveillanceSources >= 0);
        assertTrue(surveillanceTarget > surveillanceSources);
        assertFalse(cmake.substring(surveillanceSources, surveillanceTarget)
                .contains("qcarcam_bridge.cpp"));
        assertTrue(Files.isRegularFile(repositoryFile(
                "app/src/main/jniLibs/arm64-v8a/libc++_shared.so")));

        assertTrue(backend.contains(
                "FAST_CAM_ASSET =\n            \"dilink5/fast_cam_capture\""));
        assertTrue(backend.contains(
                "FAST_CAM_PATH =\n            \"/data/local/tmp/fast_cam_capture\""));
        assertTrue(backend.contains(
                "\"--cams\", cameraIds"));
        assertTrue(backend.contains(
                "\"--fps\", Integer.toString(captureFps)"));
        assertTrue(backend.contains(
                "\"persist.overdrive.fast_cam_fps\""));
        assertTrue(backend.contains(
                "readSystemProperty(\"persist.overdrive.cams\")"));
        assertTrue(backend.contains(
                "getSelectedVehicleModelId()"));
        assertTrue(backend.contains(
                "? new int[]{8, 9, 5, 4}"));
        assertTrue(backend.contains(
                "nativeSetCameraMapping("));
        assertTrue(backend.contains(
                "public static void setOutputFps(int fps)"));
        assertTrue(backend.contains(
                "nativeSetOutputFps(clamped)"));
        assertTrue(backend.contains(
                "public static void onNativeFrameAvailable(long timestampNs)"));
        assertTrue(backend.contains(
                "nativeBindLatestFrame(textureId)"));
        assertTrue(backend.contains(
                "processPid,\n                    hardwareCaptureFps);"));
        assertFalse(backend.contains("nativeStartSurface"));
        assertFalse(backend.contains("startSurface("));
        assertTrue(backend.contains("\"--socket\", socketPath"));
        assertTrue(backend.contains(
                "\"LD_PRELOAD\", releaseGuard.getAbsolutePath()"));
        assertTrue(backend.contains(
                "refusing unsafe producer buffer ownership"));
        assertTrue(backend.contains(
                "System.mapLibraryName(\"fast_cam_release_guard\")"));
        assertTrue(backend.contains(
                "\"@dilink5_fast_\" + randomHex(16)"));
        assertTrue(backend.contains("new SecureRandom()"));
        assertTrue(backend.contains(
                "resolveOwnedProcessPid(process, binary)"));
        assertOrdered(
                backend,
                "deployVerifiedAsset(",
                "Process existingProcess = sHardwareProcess;",
                "if (existingProcess != null && existingProcess.isAlive())",
                "resolveOwnedProcessPid(existingProcess, binary)",
                "stopStaleCaptureProcesses(binary)",
                "ProcessBuilder processBuilder = new ProcessBuilder(");
        assertOrdered(
                backend,
                "beginHardwareStartupIfCurrent(",
                "startedProcess = processBuilder.start();",
                "installHardwareSessionAfterSpawn(",
                "resolveOwnedProcessPid(process, binary)",
                "publishHardwarePidIfCurrent(");
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private static synchronized boolean "
                                + "installHardwareSessionAfterSpawn(")),
                "!sHardwareStartup.matches(",
                "sHardwareProcess = process;",
                "sSocketPath = socketPath;",
                "sHardwareStartup.clearIfOwned(",
                "return isStartAllowed(startEpoch, acquisitionGeneration);");
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private static synchronized boolean publishHardwarePidIfCurrent(")),
                "sHardwareProcess != process",
                "sHardwarePid = processPid;");
        assertTrue(backend.contains("MessageDigest.isEqual"));
        assertTrue(backend.contains("MessageDigest.getInstance(\"SHA-256\")"));
        assertTrue(backend.contains("OsConstants.O_NOFOLLOW"));
        assertTrue(backend.contains("android.system.Os.rename("));
        assertTrue(backend.contains("executable.endsWith(\" (deleted)\")"));
        assertTrue(backend.contains("collectExactExecutablePids("));
        assertTrue(backend.contains("drainHardwareOutput(process)"));
        assertTrue(backend.contains("\"[FastCamProc] \" + line"));
        assertOrdered(
                backend,
                "if (stopProcess(process)) {",
                "clearHardwareSessionIfOwned(process, ownerToken);",
                "markCleanReleaseIfNoOwnership();");
        assertTrue(backend.contains(
                "private static boolean stopProcess(Process process)"));
        assertTrue(backend.contains(
                "readParentPid(pid) != android.os.Process.myPid()"));
        assertFalse(backend.contains("process.pid()"));
        assertTrue(backend.contains("\"/proc/\" + pid + \"/status\""));
        assertTrue(backend.contains("android.system.Os.readlink("));
        assertTrue(backend.contains("\"/proc/\" + pid + \"/exe\""));
        assertFalse(backend.contains("\"/system/bin/sh\""));
        assertFalse(backend.contains("\"pkill\""));
        assertFalse(backend.contains("\"pgrep\""));
        assertOrdered(
                backend,
                "if (!DiLink5Platform.isSelected()) return true;",
                "loadNativeLibrary(nativeLibDir, \"c++_shared\");",
                "loadNativeLibrary(nativeLibDir, \"dilink5_camera\");");
        assertTrue(backend.contains(
                "@android.annotation.SuppressLint(\"UnsafeDynamicallyLoadedCode\")"));
        int nativeLoading = daemon.indexOf(
                "private static void loadNativeLibraries()");
        int surveillanceLoading = daemon.indexOf(
                "// Load surveillance library", nativeLoading);
        assertTrue(nativeLoading >= 0);
        assertTrue(surveillanceLoading > nativeLoading);
        String earlyNativeLoading = daemon.substring(
                nativeLoading, surveillanceLoading);
        assertTrue(earlyNativeLoading.contains(
                "DiLink5Platform.isSelected()"));
        assertTrue(earlyNativeLoading.contains(
                ".ensureNativeLibrariesLoaded(nativeLibDir)"));
        int nativeSupport = bridge.indexOf(
                "Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeIsSupported");
        int nativeInit = bridge.indexOf(
                "Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeInit",
                nativeSupport);
        assertTrue(nativeSupport >= 0);
        assertTrue(nativeInit > nativeSupport);
        String supportPredicate = bridge.substring(nativeSupport, nativeInit);
        assertTrue(supportPredicate.contains(
                "access(kAisClientPath, F_OK) == 0"));
        assertTrue(bridge.contains("/vendor/lib64/libais_client.so"));
        assertFalse(bridge.contains("libais_test_util.so"));
        assertOrdered(
                backend,
                "boolean supported = isSupported();",
                "if (!isStartAllowed(startEpoch, acquisitionGeneration)) return false;",
                "if (!supported) {");
        assertTrue(backend.contains(
                "private static final AtomicBoolean sNativeCleanupPending"));
        assertTrue(backend.contains("private native boolean nativeRelease"));
        assertOrdered(
                backend,
                "released = nativeRelease(nativeHandle);",
                "if (released) {",
                "nativeHandle = 0;");
        int surfaceSetupStart = gpu.indexOf(
                "private void createCameraSurfaceTexture()");
        int surfaceSetupEnd = gpu.indexOf(
                "/** Bind the active SurfaceTexture", surfaceSetupStart);
        assertTrue(surfaceSetupStart >= 0);
        assertTrue(surfaceSetupEnd > surfaceSetupStart);
        String surfaceSetup = gpu.substring(
                surfaceSetupStart, surfaceSetupEnd);
        assertOrdered(
                surfaceSetup,
                "if (USE_DILINK5_QCARCAM_PATH) {",
                "cameraSurfaceTexture = null;",
                "cameraSurface = null;",
                "return;",
                "Handler callbackHandler = ensureDiLink4FrameCallbackHandler();",
                "SurfaceTexture created = new SurfaceTexture(cameraTextureId);",
                "onDiLink4SurfaceTextureFrameAvailable(st, consumerEpoch)",
                "callbackHandler");
        assertFalse(surfaceSetup.contains("}, glHandler);"));
        assertFalse(surfaceSetup.contains("DiLink5FrameCb"));
        assertFalse(surfaceSetup.contains("startSurface("));
        assertTrue(gpu.contains(
                "cameraTextureId = isTexture2D()\n"
                        + "            ? GlUtil.create2DTexture()\n"
                        + "            : GlUtil.createExternalTexture();"));
        // isTexture2D must remain true whenever the DiLink 5 path is selected.
        // The decoupled encoder lane (camera.decoupledEncoderLane) extends the
        // 2D-source set with its app-owned ring copies — as a DISJUNCT, so the
        // DiLink 5 guarantee is untouched (USE_DECOUPLED_ENCODER_LANE is also
        // structurally false on DiLink 5: it requires !USE_DILINK5_QCARCAM_PATH).
        assertTrue(gpu.contains(
                "public boolean isTexture2D() {\n"
                        + "        // DiLink 5 owns a native DMA compositor output texture; the decoupled\n"
                        + "        // encoder lane publishes app-owned ring copies. Both are plain 2D.\n"
                        + "        return USE_DILINK5_QCARCAM_PATH || USE_DECOUPLED_ENCODER_LANE;"));
        assertTrue(gpu.contains(
                "backend.bindLatestFrameForOwner(cameraTextureId)"));
        assertTrue(gpu.contains(
                "backend.releaseOwnedGlResources()"));
        assertOrdered(
                streamScaler.substring(streamScaler.indexOf(
                        "public void initWithSurface(")),
                "encoderSurface = eglCore.createWindowSurface(encoderInputSurface);",
                "DiLink5Platform.isSelected()",
                "eglCore.makeCurrent(encoderSurface);",
                "clearPendingGlErrorsForDiLink5Init();",
                "programId = GlUtil.createProgram(VERTEX_SHADER, fragmentShader);");
        assertTrue(streamScaler.contains(
                "DI5 scaler init cleared stale GL error"));
        assertTrue(bridge.contains(
                "struct SavedCpuUploadState"));
        assertOrdered(
                bridge.substring(bridge.indexOf(
                        "bool uploadCpuFrameLocked(GLuint texture_id)")),
                "SavedCpuUploadState saved;",
                "saveCpuUploadState(&saved)",
                "ensureOutputTextureLocked(texture_id)",
                "restoreCpuUploadState(saved)");
        String cpuUpload = bridge.substring(
                bridge.indexOf(
                        "bool uploadCpuFrameLocked(GLuint texture_id)"),
                bridge.indexOf(
                        "bool releaseClientFrame("));
        assertFalse(cpuUpload.contains("SavedGlState"));
        assertFalse(cpuUpload.contains("restoreGlState("));
        assertOrdered(
                gpu.substring(gpu.indexOf(
                        "private boolean closeCameraForPath(Object cam)")),
                "backend.markExpectedProcessRetirement();",
                "backend.clearFrameListenerIfOwner();",
                "releaseDiLink5GlResourcesBeforeClose(backend)",
                "backend.closeWithRetirementRetry(",
                "!preserveDiLink5Avm");
        assertOrdered(
                backend.substring(backend.indexOf(
                        "public void "
                                + "markExpectedProcessRetirement()")),
                "sHardwareOwnerToken == sessionToken",
                "process.isAlive()",
                "markProcessExpectedStop(process, sessionToken)");
        assertTrue(gpu.contains(
                "eglCore.makeCurrent(dummySurface);"));
        assertTrue(gpu.contains(
                "done.await(\n"
                        + "                    GL_THREAD_TIMEOUT_MS,"));
        assertTrue(backend.contains(
                "sGlResourcesReleased.set(false);"));
        assertTrue(backend.contains(
                "if (released) clearGlResourceOwnerIfOwned(sessionToken);"));
        assertTrue(backend.contains(
                "Refusing to stop fast_cam_capture before its "));
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private OwnerRetirementResult closeInternal(\n"
                                + "            boolean stopAvm,")),
                "markOwnedHardwareProcessExpectedStop(sessionToken)",
                "nativeRelease(nativeHandle)",
                "if (released || !ownsNativeSession)",
                "stopHardwareProcessForOwner(");
        assertTrue(backend.contains(
                "Preserving fast_cam_capture because native ownership "));
        assertTrue(backend.contains(
                "if (nativeRetirementBusy\n"
                        + "                || (expectedProcess != null\n"
                        + "                        && expectedProcess.isAlive()))"));
        assertTrue(backend.contains(
                "sExpectedStopProcesses.remove(process);"));
        assertTrue(backend.contains(
                "if (process != null && process.isAlive())"));
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private NativeRecoveryResult recoverNativeBridgeIfNeeded()")),
                "if (expectedNativeOwnerBackend != null)",
                "closeForRecoveryBefore(",
                "awaitExactOwnerRetirement(",
                "expectedProcess = markOwnedHardwareProcessExpectedStop(",
                "nativeStop(0)",
                "nativeRelease(0)",
                "stopExpectedHardwareProcess(",
                "sNativeCleanupPending.set(false)");
        String recoveryAdmission = backend.substring(
                backend.indexOf(
                        "private static NativeRecoveryAdmission "
                                + "acquireNativeRecoveryAdmission("),
                backend.indexOf(
                        "static NativeRecoveryAdmission "
                                + "decideNativeRecoveryAdmission("));
        assertOrdered(
                recoveryAdmission,
                "isCameraStartEpochCurrent(startEpoch)",
                "isAcquisitionGenerationCurrent(",
                "sNativeRecoveryInProgress.set(true)");
        assertTrue(backend.contains(
                "private static OwnerRetirementResult "
                        + "stopExpectedHardwareProcess("));
        assertTrue(backend.contains(
                "long expectedOwnerToken,\n"
                        + "            long deadlineElapsedMs)"));
        assertTrue(backend.contains(
                "false,\n"
                        + "                    deadlineElapsedMs);"));
        assertTrue(backend.contains(
                "remaining < NATIVE_RETIREMENT_EXECUTION_BUDGET_MS"));
        assertTrue(backend.contains(
                "private static native boolean nativeHasActiveSession();"));
        assertTrue(bridge.contains(
                "DiLink5QCarCamBackend_nativeHasActiveSession("));
        assertTrue(backend.contains(
                "&& !sNativeSessionOwned.get()\n"
                        + "                    && !sNativeCleanupPending.get()\n"
                        + "                    && "
                        + "!sNativeBridgeReleasedPendingProcess.get()"));
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private boolean retireUnknownNativeSession()")),
                "if (!nativeHasActiveSession())",
                "nativeStop(0)",
                "nativeRelease(0)");
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private OwnerRetirementResult closeInternal(")),
                "if (!hasDeadlineBudget(",
                "return OwnerRetirementResult.BUSY;",
                "markOwnedHardwareProcessExpectedStop(sessionToken)");
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private void rollbackFailedOpen()")),
                "ownsExactNativeSession",
                "closeWithLifecycleGateResult(",
                "preserving its sidecar",
                "stopOwnedHardwareProcess(");
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private static boolean stopProcess(Process process)")),
                "markProcessExpectedStop(",
                "process.destroy();",
                "return false;");
        assertOrdered(
                backend.substring(backend.indexOf(
                        "private static boolean consumeExpectedStop(")),
                "sExpectedStopProcesses.remove(process)",
                "return current;");
        assertTrue(backend.contains(
                "classifyObservedHardwareExitAndClearIfCurrent("
                        + "existingProcess)"));
        String observedExit = backend.substring(
                backend.indexOf(
                        "private static synchronized void\n"
                                + "            "
                                + "classifyObservedHardwareExitAndClearIfCurrent("),
                backend.indexOf(
                        "private static synchronized boolean\n"
                                + "            "
                                + "classifyDrainedHardwareExitAndClear("));
        assertOrdered(
                observedExit,
                "isExpectedStopCurrent(process)",
                "source-only recovery will retire the remaining client",
                "clearHardwareSession();");
        assertFalse(observedExit.contains(
                "DiLink5CameraSafety.disableForCurrentBoot("));
        String drainedExit = backend.substring(
                backend.indexOf(
                        "private static synchronized boolean\n"
                                + "            "
                                + "classifyDrainedHardwareExitAndClear("),
                backend.indexOf(
                        "private static boolean isExpectedStopCurrent("));
        assertOrdered(
                drainedExit,
                "consumeExpectedStop(process)",
                "source-only recovery remains enabled",
                "clearHardwareSession();",
                "return expected;");
        assertFalse(drainedExit.contains(
                "DiLink5CameraSafety.disableForCurrentBoot("));
        assertTrue(backend.contains(
                "private static boolean isExpectedStopCurrent("
                        + "Process process)"));
        String drainer = backend.substring(
                backend.indexOf(
                        "private static void drainHardwareOutput("),
                backend.indexOf(
                        "private static boolean stopProcess(Process process)"));
        assertTrue(drainer.contains(
                "classifyDrainedHardwareExitAndClear(process)"));
        assertFalse(drainer.contains(
                "DiLink5CameraSafety.disableForCurrentBoot("));
        assertTrue(gpu.contains(
                "|| diLink5CloseInProgress)"));
        // DI5 never yields the FastCam source to OEM reverse: the gear-driven
        // pause/resume state machine, its 3 s post-reverse reacquire and the
        // vendor-AVM handoff are gone, and the gear dispatcher no longer
        // routes gear events into the camera at all.
        assertFalse(gpu.contains("onDiLink5GearChanged"));
        assertFalse(gpu.contains("pauseDiLink5CameraSourceForReverse"));
        assertFalse(gpu.contains("DILINK5_REVERSE_RESUME_DELAY_MS"));
        assertFalse(gpu.contains("cancelPendingAcquisitionForSystemAvm"));
        assertFalse(gpu.contains("completeSystemAvmSourceHandoff"));
        assertFalse(gpu.contains("releaseAvmAfterSystemHandoff"));
        assertTrue(gpu.contains(
                "private boolean shouldHoldForDiLink5Reverse() {\n"
                        + "        return false;"));
        assertTrue(gpu.contains(
                "private boolean isFreshDiLink5Reverse() {\n"
                        + "        return false;"));
        String cameraStop = gpu.substring(
                gpu.indexOf("public boolean stop()"),
                gpu.indexOf("private volatile boolean stopVerdictWedged"));
        assertOrdered(
                cameraStop,
                "markExpectedProcessRetirement();",
                "running = false;",
                "stopEncoderDrainersBeforeCameraClose(",
                "closeCameraForPath(cameraObj)");
        assertFalse(cameraStop.contains("releaseAvmAfterSystemHandoff"));
        assertTrue(gpu.contains(
                "diLink5OwnershipTransition.tryLock("));
        assertTrue(gpu.contains(
                "shouldRunPostReacquireLifecycle("));
        assertTrue(gpu.contains(
                "reopened; recorder and streaming sessions \"\n"
                        + "                        + \"remained attached"));
        assertFalse(backend.contains("closeForSystemAvmHandoff"));
        assertFalse(backend.contains("releaseAvmAfterSystemHandoff"));
        assertFalse(backend.contains("Executors.newSingleThreadScheduledExecutor"));
        assertFalse(backend.contains("TsAvmCoordinator."));
        String gearDispatch = daemon.substring(daemon.indexOf(
                "public static void onGearChanged(int gear)"));
        assertFalse(gearDispatch.substring(0, gearDispatch.indexOf(
                "recordingModeManager.onGearChanged(gear)"))
                .contains("onDiLink5GearChanged"));
        assertOrdered(
                gearDispatch,
                "tripManager.onGearChanged(gear)",
                "recordingModeManager.onGearChanged(gear)");
        int statusOverlay = manifest.indexOf(
                "android:name=\"com.overdrive.app.overlay.StatusOverlayService\"");
        assertTrue(statusOverlay >= 0);
        assertTrue(manifest.substring(
                statusOverlay,
                manifest.indexOf("/>", statusOverlay))
                .contains("android:foregroundServiceType=\"specialUse|dataSync\""));
        assertTrue(gpu.contains(
                "dilink5Backend.start(ignoredTimestampNs -> {"));
        String nativeStart = backend.substring(
                backend.indexOf(
                        "public boolean start(FrameListener listener)"),
                backend.indexOf("public void stop()"));
        assertOrdered(
                nativeStart,
                "if (nativeHandle == 0 && !open()) return false;",
                "installFrameListener(listener)",
                "nativeStart(nativeHandle)",
                "publishSourceIfCurrent()");
        assertTrue(gpu.contains(
                "if (USE_DILINK5_QCARCAM_PATH) {\n"
                        + "                if (!consumeDiLink5Frame())"));
        String consumeDiLink5 = gpu.substring(
                gpu.indexOf("private boolean consumeDiLink5Frame()"),
                gpu.indexOf(
                        "private boolean consumeSurfaceTextureFrame()"));
        assertTrue(consumeDiLink5.contains(
                "long candidate = System.nanoTime();"));
        assertFalse(consumeDiLink5.contains("getLatestFrameTimestamp"));
        assertFalse(backend.contains("nativeGetLatestFrameTimestamp"));
        assertTrue(glUtil.contains("public static int create2DTexture()"));
        for (String consumer :
                new String[]{recorder, downscaler, cropper,
                        streamScaler, previewSampler}) {
            assertTrue(consumer.contains("uniform sampler2D uCameraTex"));
            assertTrue(consumer.contains(
                    "uniform samplerExternalOES uCameraTex"));
            assertTrue(consumer.contains("GLES20.GL_TEXTURE_2D"));
            assertTrue(consumer.contains(
                    "GLES11Ext.GL_TEXTURE_EXTERNAL_OES"));
            assertTrue(consumer.contains("boolean isTexture2D"));
        }
        assertTrue(recorder.contains(
                "DEFAULT_VIEWPORT_HEIGHT, false);"));
        assertTrue(downscaler.contains(
                "this((float[]) null, false);"));
        assertTrue(cropper.contains(
                "quadrantCornerOffsetsXY, false);"));
        assertTrue(streamScaler.contains(
                "this(outputWidth, outputHeight, quadrantStripOffsetX, false);"));
        assertTrue(previewSampler.contains(
                "this(sharedContext, false);"));
        assertTrue(pipeline.contains(
                "boolean isTexture2D = nextCamera.isTexture2D();"));
        assertTrue(pipeline.contains(
                "new GpuDownscaler(quadrantStripOffsetX, isTexture2D)"));
        assertTrue(proguard.contains(
                "-keep class com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend"));
        assertTrue(proguard.contains(
                "public static void onNativeFrameAvailable(long);"));
        assertOrdered(
                gpu.substring(gpu.indexOf("public void setTargetFps(int fps)")),
                "if (USE_DILINK5_QCARCAM_PATH) {",
                ".setOutputFps(fps);",
                "} else if (cam != null) {",
                "AvmCameraHelper.setCameraFps(cam, fps);");
    }

    @Test
    public void packagedFastCamProducerUsesReferenceCoreWithoutSelfYield()
            throws IOException {
        Path producer = repositoryFile(
                "app/src/main/assets/dilink5/fast_cam_capture");
        byte[] executable = Files.readAllBytes(producer);
        String image = new String(
                executable, StandardCharsets.ISO_8859_1);

        // Byte-for-byte producer immediately before the reference branch's
        // cooperative-yield commit (09cf4371). DI5 arbitration here is
        // explicit and owned by the gear/source state machine.
        assertTrue("8ac709ce9b889e7f93f39519a695c66f0f65bb4022f9b2abf9c8f5c403239e5d"
                .equals(sha256(producer)));
        assertTrue(image.contains("--cams"));
        assertTrue(image.contains("--fps"));
        assertTrue(image.contains("--time"));
        assertTrue(image.contains("--socket"));
        assertFalse(image.contains("AIS camera preempted"));
        assertFalse(image.contains("Yielding cleanly"));
    }

    @Test
    public void fastCamSocketUsesOwnedRandomChannelWithReferenceClient()
            throws IOException {
        String bridge = readRepositoryFile(
                "app/src/main/cpp/camera/qcarcam_bridge.cpp");
        String clientHeader = readRepositoryFile(
                "app/src/main/cpp/include/fast_cam_bridge.h");
        String client = readRepositoryFile(
                "app/src/main/cpp/camera/fast_cam_bridge.cpp");
        String backend = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/DiLink5QCarCamBackend.java");

        assertTrue(backend.contains(
                "\"@dilink5_fast_\" + randomHex(16)"));
        assertTrue(backend.contains("sHardwarePid"));
        assertTrue(backend.contains(
                "nativeInit(\n"
                        + "                    cameraId,\n"
                        + "                    socketPath,\n"
                        + "                    processPid,\n"
                        + "                    hardwareCaptureFps)"));
        assertFalse(clientHeader.contains("\"fast_cam.sock\""));
        assertTrue(bridge.contains("isValidSocketPath("));
        assertTrue(bridge.contains("g_expected_server_pid"));
        assertTrue(clientHeader.contains("int expected_server_pid"));
        assertTrue(client.contains("SO_PEERCRED"));
        assertTrue(client.contains(
                "credentials.pid != expected_server_pid"));
        assertTrue(backend.contains(
                "readParentPid(pid) != android.os.Process.myPid()"));
        assertTrue(backend.contains(
                "executable.getCanonicalPath(), candidates"));
        assertOrdered(
                bridge,
                "FastCamClient client;",
                "const int expected_server_pid = g_expected_server_pid.load();",
                "!client.connect(",
                "Connected to owned fast_cam_capture DMA socket");
        assertOrdered(
                bridge.substring(bridge.indexOf(
                        "Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeInit")),
                "isValidSocketPath(resolved_path)",
                "g_socket_path = resolved_path;",
                "g_expected_server_pid.store",
                "server pid %lld");
    }

    @Test
    public void configuredCameraInputsHaveOneStartupAndPreviewReservation()
            throws IOException {
        String backend = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/DiLink5QCarCamBackend.java");
        String preview = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/CameraPreviewHelper.java");

        assertTrue(backend.contains(
                "private static final AtomicBoolean sConfiguredInputReservation"));
        assertTrue(backend.contains("inputId >= 0 && inputId <= 3"));
        assertTrue(backend.contains(
                "sConfiguredInputReservation.compareAndSet(false, true)"));
        assertTrue(backend.contains("sConfiguredInputReservation.set(false)"));
        assertOrdered(
                backend,
                "if (!tryAcquireConfiguredInputReservation())",
                "if (!ensureHardwareProcess(\n"
                        + "                    startEpoch,\n"
                        + "                    acquisitionGeneration,\n"
                        + "                    sessionToken))",
                "nativeHandle = nativeInit(",
                "Fast camera bridge initialized: 0x",
                "} finally {",
                "releaseConfiguredInputReservation();");
        assertOrdered(
                backend,
                "private static boolean ensureHardwareProcess(",
                "deployVerifiedAsset(",
                "stopStaleCaptureProcesses(binary)",
                "beginHardwareStartupIfCurrent(",
                "startedProcess = processBuilder.start();",
                "installHardwareSessionAfterSpawn(");
        assertTrue(preview.contains(
                "if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected())"));
        assertFalse(preview.contains(
                "boolean needsDiLink5Reservation"));
    }

    @Test
    public void fastCamClientCarriesTheExactProducerOwnershipToken()
            throws IOException {
        String bridge = readRepositoryFile(
                "app/src/main/cpp/camera/qcarcam_bridge.cpp");
        String clientHeader = readRepositoryFile(
                "app/src/main/cpp/include/fast_cam_bridge.h");
        String ipc = readRepositoryFile(
                "app/src/main/cpp/include/fast_cam_ipc.h");

        assertTrue(clientHeader.contains(
                "uint32_t buffer_index;"));
        assertTrue(clientHeader.contains(
                "uint32_t buffer_slot;"));
        assertTrue(clientHeader.contains(
                "uint32_t sequence_no;"));
        assertTrue(clientHeader.contains(
                "int dma_buf_fd;"));
        assertTrue(ipc.contains(
                "fast_cam_frame_release_msg_t"));
        assertTrue(bridge.contains(
                "client.waitForFrame(&frame, 100)"));
        assertOrdered(
                bridge,
                "g_streaming.store(false);",
                "g_stream_stopped.wait_for(",
                "pthread_join(thread, nullptr)");
        assertTrue(bridge.contains("kStreamJoinTimeoutMs = 3000"));
        assertFalse(bridge.contains("last_sequence"));
        assertFalse(bridge.contains(
                "frame.sequence_no <= last_sequence[slot]"));
        assertTrue(bridge.contains(
                "frame.sequence_no"));
        assertTrue(bridge.contains(
                "releaseClientFrame("));
        assertFalse(bridge.contains("frame.cam_id >= 4"));
        assertTrue(bridge.contains(
                "int slot = valid ? semanticSlotFor(frame.cam_id) : -1;"));
        assertTrue(bridge.contains(
                "slot < 0 || slot >= FAST_CAM_MAX_CAMS"));
        assertTrue(bridge.contains("frame.width == kSensorWidth"));
        assertTrue(bridge.contains("frame.height == kSensorHeight"));
    }

    @Test
    public void dilink5AccUsesLocalPowerStateInsteadOfLegacyBodywork()
            throws IOException {
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        String injector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/dilink5/Dilink5SdkInjector.java");
        String backend = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/DiLink5QCarCamBackend.java");
        String platform = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/DiLink5Platform.java");
        String monitor = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/AccMonitor.java");
        String positions = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/PositionStore.java");

        assertOrdered(
                daemon,
                "if (isDilink5CameraMode()) {",
                "startDiLink5AccStateHeartbeat();",
                "} else {",
                "startBodyworkListenerRegistrationSupervisor(context);");
        assertTrue(daemon.contains(
                ".probeDiLink5AccOnForTransition("));
        assertTrue(daemon.contains("Dilink5SdkInjector"));
        assertTrue(injector.contains("DiLink5Platform.isSelected()"));
        assertTrue(injector.contains("findRuntimeDexPaths(context)"));
        assertOrdered(
                injector,
                "if (!lostInjection && loadable(loader))",
                "List<String> dexPaths = findRuntimeDexPaths(context);");
        assertTrue(injector.contains("\"/system/framework\""));
        assertTrue(injector.contains("definesCoreProbe(candidate)"));
        assertFalse(injector.contains(
                String.join(".", "com", "byd", "data", "collect")));
        assertFalse(injector.contains("Byd" + "DataCollect"));
        int probesStart = injector.indexOf("private static final String[] PROBE_CLASSES");
        int probesEnd = injector.indexOf("};", probesStart);
        assertTrue(probesStart >= 0);
        assertTrue(probesEnd > probesStart);
        String probes = injector.substring(probesStart, probesEnd);
        assertTrue(probes.contains(
                "collectdata.AbsBYDAutoCollectDataListener"));
        assertTrue(probes.contains(
                "charging.AbsBYDAutoChargingListener"));
        assertTrue(probes.contains(
                "tyre.AbsBYDAutoTyreListener"));
        assertTrue(probes.contains(
                "instrument.AbsBYDAutoInstrumentListener"));
        assertTrue(probes.contains(
                "statistic.AbsBYDAutoStatisticListener"));
        assertTrue(probes.contains(
                "power.AbsBYDAutoPowerListener"));
        assertTrue(probes.contains(
                "radar.AbsBYDAutoRadarListener"));
        assertFalse(injector.contains("permanentlyUnavailable"));
        assertTrue(injector.contains(
                "boolean ok = coreLoadable(loader);"));
        assertTrue(injector.contains(
                "injectedElementsPresent(loader, expectedElements)"));
        assertFalse(injector.contains(
                "Thread.currentThread().getContextClassLoader()"));
        assertFalse(injector.contains(
                "ClassLoader.getSystemClassLoader()"));
        assertTrue(backend.contains("DiLink5Platform.isEnabled()"));
        assertTrue(platform.contains(
                "CAMERA_UTILITY_PRESENT ="));
        assertTrue(platform.contains(
                "new java.io.File(CAMERA_UTILITY_PATH).isFile()"));
        assertTrue(platform.contains(
                "return isSelected() && CAMERA_UTILITY_PRESENT;"));
        assertFalse(platform.contains("DiLink5QCarCamBackend.isSupported()"));
        assertTrue(monitor.contains(
                "getSystemProperty(\"sys.byd.power_mode\", \"\")"));
        assertTrue(monitor.contains(
                "getSystemProperty(\"sys.accanim.status\", \"\")"));
        assertFalse(monitor.contains("\"dumpsys car_service"));
        assertFalse(monitor.contains("CpmsState="));
        assertFalse(monitor.contains("!pm.isInteractive()"));
        // The car_service power-mode source must stay routed through
        // CarSvcTelemetry's cached dump (no raw shell-out from AccMonitor),
        // ordered driving-telemetry → sysprop → dump → accanim, and its OFF
        // readings must pass the consecutive-confirmation dampener so one
        // stale/misparsed row can never flip a drive into sentry.
        assertOrdered(
                monitor,
                "if (hasDrivingTelemetry()) {",
                "getSystemProperty(\"sys.byd.power_mode\", \"\")",
                "CarSvcTelemetry.powerModeLine()",
                "admitDiLink5DumpPowerReading(",
                "getSystemProperty(\"sys.accanim.status\", \"\")");
        assertTrue(monitor.contains(
                "DILINK5_DUMP_OFF_CONFIRMATIONS = 2"));
        assertTrue(positions.contains("private static final String[] CONFIRMED_MODELS = { \"seal\" }"));
        assertTrue(positions.contains("return diLink5 && \"sealion7\".equals(m);"));
    }

    @Test
    public void dilink5PropertyBridgeServiceManagerFallbackIsPlatformGated()
            throws IOException {
        String bridge = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/CarPropertyBridge.java");
        // Non-DiLink 5 trims take the provider-only branch and return before
        // any DI5 logic; the DI5 resolver (kill switch → breaker → provider →
        // descriptor-validated ServiceManager fallback) is reached only after
        // the platform check.
        assertOrdered(
                bridge,
                "private ICarPropertyService ensureService()",
                "if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {",
                "svc = resolveServiceViaProvider();",
                "return svc;",
                "svc = resolveDiLink5ServiceLocked();");
        assertOrdered(
                bridge,
                "private ICarPropertyService resolveDiLink5ServiceLocked()",
                "if (!diLink5BridgeEnabled()) return null;",
                "if (diLink5Breaker.isOpen(now))",
                "resolveServiceViaProvider(providerAttempt);",
                "if (!providerAttempt.nullHolder)",
                "diLink5Breaker.recordTransientFailure();",
                "svc = resolveServiceViaServiceManager();",
                "diLink5Breaker.recordHardFailure(now)");
        // Exactly one ServiceManager call site, inside the DI5 resolver.
        int firstCall = bridge.indexOf("svc = resolveServiceViaServiceManager();");
        assertTrue(firstCall >= 0);
        assertTrue(bridge.indexOf(
                "svc = resolveServiceViaServiceManager();", firstCall + 1) < 0);
        // The fallback must validate the remote descriptor BEFORE wrapping —
        // Stub.asInterface wraps any live binder (AOSP car_service = ICar).
        assertOrdered(
                bridge,
                "private ICarPropertyService resolveServiceViaServiceManager()",
                "binder.getInterfaceDescriptor()",
                "if (!ICarPropertyService.Stub.DESCRIPTOR.equals(descriptor))",
                "continue;",
                "return ICarPropertyService.Stub.asInterface(binder);");
        assertTrue(bridge.contains(
                "DILINK5_BRIDGE_PROPERTY =\n"
                        + "            \"persist.overdrive.dilink5_carprop_bridge\""));
        assertTrue(bridge.contains("DILINK5_BREAKER_TRIP_FAILURES = 30"));
        assertTrue(bridge.contains("DILINK5_BREAKER_INITIAL_OPEN_MS = 60_000L"));
        assertTrue(bridge.contains("DILINK5_BREAKER_MAX_OPEN_MS = 30L * 60_000L"));
        // Only the AMS "refused" signature counts toward the breaker.
        assertOrdered(
                bridge,
                "if (holder == null) {",
                "attempt.nullHolder = true;",
                "returned null holder for authority=");
    }

    @Test
    public void avmStartSurvivesAsynchronousServiceBinding()
            throws IOException {
        String coordinator = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/TsAvmCoordinator.java");
        String backend = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/DiLink5QCarCamBackend.java");
        String keepalive = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/DaemonKeepaliveService.kt");
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");

        assertTrue(coordinator.contains("desiredStart = start;"));
        assertTrue(coordinator.contains(
                "AvmRequest superseded = pendingRequest;"));
        assertTrue(coordinator.contains("dispatchPendingRequest();"));
        assertTrue(coordinator.contains(
                "new HandlerThread(\"DiLink5AvmSerial\")"));
        assertTrue(coordinator.contains(
                "new HandlerThread(\"DiLink5AvmRecovery\")"));
        assertTrue(coordinator.contains("binderCalls.post(call, () ->"));
        assertTrue(coordinator.contains("private AvmRequest activeRequest;"));
        assertTrue(coordinator.contains("private VendorCall activeCall;"));
        assertTrue(coordinator.contains(
                "long generation = ++desiredGeneration;"));
        assertTrue(coordinator.contains(
                "if (activeRequest != null && activeRequest.start != start)"));
        assertTrue(coordinator.contains("finishStoppedBarrier();"));
        assertTrue(coordinator.contains(
                "activeRequest != null || pendingRequest != null"));
        assertFalse(coordinator.contains("new Thread("));
        assertFalse(coordinator.contains("replayRequired"));
        assertFalse(coordinator.contains("ThreadPoolExecutor"));
        assertFalse(coordinator.contains("callScheduled"));
        assertTrue(coordinator.contains("service.getAvmStatus();"));
        assertTrue(coordinator.contains("service.startAvm();"));
        assertTrue(coordinator.contains("service.stopAvm();"));
        assertTrue(coordinator.contains("AVM_STATUS_FOREGROUND = 2"));
        assertTrue(coordinator.contains("AVM_STATUS_BACKGROUND = 3"));
        assertTrue(coordinator.contains(
                "isDesiredAvmStatus(request.start, status)"));
        assertTrue(coordinator.contains("binderCalls.abandon("));
        assertTrue(coordinator.contains("AVM_REQUEST_TIMEOUT_MS = 5_000L"));
        assertTrue(coordinator.contains(
                "AVM_PENDING_START_GUARD_MS = 5_000L"));
        assertTrue(coordinator.contains(
                "recoveryQuietUntil =\n"
                        + "                        statusObservedAt "
                        + "+ AVM_PENDING_START_GUARD_MS;"));
        assertTrue(coordinator.contains(
                "request.deadlineElapsedMs = deadlineAfterDispatch("));
        assertFalse(coordinator.contains(
                "extendRequestDeadline("));
        assertTrue(coordinator.contains(
                "avmOwnershipState == AVM_OWNERSHIP_OWNED"));
        assertTrue(coordinator.contains(
                "request.suppressInternalReconcile ="));
        assertFalse(coordinator.contains("lateRecoveredStart"));
        assertOrdered(
                coordinator,
                "request.suppressInternalReconcile =",
                "pendingRequest = request;",
                "scheduleExpiry(request);");
        assertOrdered(
                coordinator,
                "else if (request.suppressInternalReconcile) {",
                "unbind();",
                "finishTerminalStopCompletionIfCurrent(request);");
        assertTrue(coordinator.contains("AVM_DISPATCH_ATTEMPTS = 2"));
        assertOrdered(
                coordinator,
                "for (int attempt = 1; attempt <= AVM_DISPATCH_ATTEMPTS; attempt++)",
                "long deadline =",
                "dispatchAndAwaitResult(");
        assertFalse(coordinator.contains("Settings.Global"));
        assertFalse(coordinator.contains("EXTRA_REQUEST_ID"));
        assertFalse(coordinator.contains("CameraDaemon.getAppContext()"));
        assertTrue(coordinator.contains("ACTION_START_AVM"));
        assertTrue(coordinator.contains("ACTION_STOP_AVM"));
        assertTrue(coordinator.contains("requestStopInAppProcess()"));
        assertTrue(coordinator.contains("\"/system/bin/am\""));
        assertTrue(coordinator.contains("\"start-foreground-service\""));
        assertTrue(coordinator.contains(
                "new java.net.ServerSocket()"));
        assertTrue(coordinator.contains(
                "java.net.InetAddress.getByName(\"127.0.0.1\")"));
        assertTrue(coordinator.contains("EXTRA_ACK_PORT"));
        assertTrue(coordinator.contains("EXTRA_ACK_TOKEN"));
        assertTrue(coordinator.contains(
                "token.equals(input.readUTF())"));
        assertTrue(coordinator.contains(
                "public static boolean sendAppProcessResult("));
        String appResult = coordinator.substring(
                coordinator.indexOf(
                        "public static boolean sendAppProcessResult("),
                coordinator.indexOf(
                        "private static void sendAppProcessResultNow("));
        assertTrue(appResult.contains("avmAckHandler().post("));
        assertFalse(appResult.contains("avmProbeHandler().post("));
        assertTrue(coordinator.contains(
                "new HandlerThread(\"DiLink5AvmAck\")"));
        assertTrue(coordinator.contains(
                "new HandlerThread(\"DiLink5AvmProbe\")"));
        assertFalse(appResult.contains("new java.net.Socket()"));
        String appResultWorker = coordinator.substring(
                coordinator.indexOf(
                        "private static void sendAppProcessResultNow("),
                coordinator.indexOf(
                        "private ServiceConnection createConnection("));
        assertTrue(appResultWorker.contains("new java.net.Socket()"));
        assertTrue(coordinator.contains(
                "\"/proc/sys/kernel/random/boot_id\""));
        assertTrue(coordinator.contains(
                "getSharedPreferences("));
        assertTrue(coordinator.contains("AVM_OWNERSHIP_NONE = 0"));
        assertTrue(coordinator.contains("AVM_OWNERSHIP_STARTING = 1"));
        assertTrue(coordinator.contains("AVM_OWNERSHIP_OWNED = 2"));
        assertTrue(coordinator.contains(
                "private synchronized boolean persistOwnershipState("));
        assertTrue(coordinator.contains(
                "private static int readOwnershipState("));
        assertFalse(coordinator.contains("setAvmOwned("));
        assertFalse(coordinator.contains("avmOwned"));
        String transition = coordinator.substring(
                coordinator.indexOf("private void invokeAndConfirm("),
                coordinator.indexOf("static boolean shouldIssuePhysicalStop("));
        String startCommand = transition.substring(
                transition.indexOf(
                        "if (!invoked && isStartCommandStatus(status))"),
                transition.indexOf("invoked = true;"));
        assertOrdered(
                startCommand,
                "AVM_OWNERSHIP_STARTING",
                "call.commandStarted = true;",
                "service.startAvm();",
                "desiredStart",
                "AVM_OWNERSHIP_OWNED");
        String desiredStatus = transition.substring(
                transition.indexOf("if (isDesiredAvmStatus("),
                transition.indexOf("if (isFailureAvmStatus("));
        assertOrdered(
                desiredStatus,
                "!request.start",
                "hasAvmResponsibility()",
                "persistOwnershipState(AVM_OWNERSHIP_NONE)",
                "return;");
        String persistence = coordinator.substring(
                coordinator.indexOf(
                        "private synchronized boolean persistOwnershipState("),
                coordinator.indexOf(
                        "private static int readOwnershipState("));
        assertOrdered(
                persistence,
                ".putInt(AVM_STATE_VALUE, state)",
                ".remove(AVM_STATE_LEGACY_OWNED)",
                ".commit()",
                "avmOwnershipState = ownershipStateAfterCommit(",
                "return persisted;");
        assertTrue(coordinator.contains(
                "shouldIssuePhysicalStop("));
        assertTrue(keepalive.contains(
                "TsAvmCoordinator.ACTION_START_AVM"));
        assertTrue(keepalive.contains(
                "TsAvmCoordinator.ACTION_STOP_AVM"));
        assertTrue(keepalive.contains(
                ".getInstance(applicationContext)"));
        assertTrue(keepalive.contains(
                "TsAvmCoordinator.RequestCompletion"));
        assertTrue(keepalive.contains(
                ".startAvm(diLink5AvmCompletion)"));
        assertTrue(keepalive.contains(
                ".stopAvm(diLink5AvmCompletion)"));
        assertTrue(keepalive.contains(
                ".sendAppProcessResult("));
        assertFalse(keepalive.contains("diLink5AvmRequestId"));
        assertFalse(keepalive.contains("DILINK5_AVM_STOP_GRACE_MS"));
        String startupReconciliation = daemon.substring(
                daemon.indexOf("boolean leavingDiLink5 ="),
                daemon.indexOf(
                        "// Init app context. This will break the app if run in a thread"));
        assertTrue(startupReconciliation.contains(
                "vehicleModeActivation.activeMode"));
        assertTrue(startupReconciliation.contains(
                "vehicleModeActivation.previousMode"));
        assertTrue(startupReconciliation.contains(
                "TsAvmCoordinator"));
        assertTrue(startupReconciliation.contains(
                ".requestStopInAppProcess()"));
        assertTrue(startupReconciliation.contains(
                "avmCleanup.setDaemon(true)"));
        assertTrue(startupReconciliation.contains(
                "avmCleanup.start()"));
        assertFalse(startupReconciliation.contains(
                "DiLink5Platform.isSelected()"));
        assertFalse(startupReconciliation.contains(
                "for (int attempt ="));
        assertOrdered(
                keepalive,
                "if (stopDiLink5Avm) {",
                "releaseWakeLock()",
                ".stopAvm(",
                "diLink5AvmCompletion",
                "stopSelfResult(startId)");
        // The camera daemon no longer requests AVM start/stop at all: the
        // TsAvmCoordinator app-process lane above is retained only for the
        // one-shot DI5 mode-exit cleanup, and the backend must stay free of
        // the AVM reconciliation state machine it used to drive.
        assertFalse(backend.contains("reconcileAvmState"));
        assertFalse(backend.contains("sAvmOutcomeUncertain"));
        assertFalse(backend.contains("sAvmDesiredOwnerToken"));
        assertFalse(backend.contains("requestAvmStopIfOwned"));
        assertFalse(backend.contains("requestAvmStartIfNeeded"));
        assertFalse(backend.contains("requestAvmState("));
        assertTrue(backend.contains("ownsConfiguredInput(int inputId)"));
        assertTrue(backend.contains("inputId >= 0 && inputId <= 3"));
        String preview = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/CameraPreviewHelper.java");
        String resolver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/CameraConfigResolver.java");
        String surveillance = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        assertTrue(preview.contains(".ownsConfiguredInput(cameraId)"));
        assertTrue(preview.contains(
                "Direct camera previews are unavailable in DiLink 5 mode"));
        assertTrue(resolver.contains(
                "if (!dilink5) {\n"
                        + "            for (int cameraId = 0; cameraId <= 5; cameraId++)"));
        assertTrue(resolver.contains(
                "DiLink5QCarCamBackend.isSupported()"));
        assertTrue(resolver.contains(
                "role == null || sourceRef == null || isDiLink5RuntimeSelected()"));
        assertTrue(surveillance.contains(
                "Manual camera IDs are unavailable in DiLink 5 mode"));
        assertOrdered(
                backend,
                "if (!ensureHardwareProcess(\n"
                        + "                    startEpoch,\n"
                        + "                    acquisitionGeneration,\n"
                        + "                    sessionToken)) {",
                "nativeHandle = nativeInit(",
                "processPid,\n                    hardwareCaptureFps);",
                "Fast camera bridge initialized: 0x");
        assertFalse(backend.contains("TsAvmCoordinator.getInstance("));
        assertFalse(backend.contains("stopHardwareProcess();"));
    }

    @Test
    public void daemonRestartStopsAndVerifiesOnlyTheOverdriveCaptureProcess()
            throws IOException {
        String backend = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/DiLink5QCarCamBackend.java");
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");

        assertTrue(backend.contains("stopHardwareProcessForExit()"));
        assertTrue(backend.contains(
                "exactNativeOwner.closeForRecoveryBefore("));
        assertTrue(backend.contains(
                "process.destroy();"));
        assertTrue(backend.contains(
                "process.destroyForcibly();"));
        assertTrue(backend.contains(
                "Thread.sleep(HARDWARE_RELEASE_SETTLE_MS);"));
        // Daemon exit retires only OUR producer; it no longer sends a terminal
        // stopAvm() to the vendor service (the daemon never touches com.ts.avm).
        assertFalse(backend.contains("TsAvmCoordinator."));
        assertFalse(backend.contains("requestTerminalAvmStopIfStillOwned"));
        assertOrdered(
                backend.substring(backend.indexOf(
                        "public static boolean stopHardwareProcessForExit()")),
                "exactNativeOwner.closeForRecoveryBefore(",
                "if (!processStopAllowed)",
                "stopped = stopHardwareProcessForOwner(",
                "markCleanReleaseIfNoOwnership();",
                "return stopped;");
        assertTrue(backend.contains(
                "sNativeOwnerToken != Long.MIN_VALUE\n"
                        + "                            || "
                        + "sNativeSessionOwned.get()"));
        assertTrue(backend.contains(
                "&& sNativeOwnerToken == Long.MIN_VALUE\n"
                        + "                    && "
                        + "!sHardwareStartup.isActive();"));
        assertFalse(backend.contains("sAvmStartDispatched"));
        assertTrue(backend.contains(
                "if (sHardwareProcess == process && !process.isAlive())"));
        assertTrue(backend.contains("collectExactExecutablePids("));
        assertTrue(backend.contains("expectedPath.equals(executable)"));
        assertTrue(backend.contains("executable.endsWith(\" (deleted)\")"));
        assertFalse(backend.contains("\"pkill\""));
        assertFalse(backend.contains("\"pgrep\""));
        assertFalse(backend.contains("PROCESS_PATTERN"));
        assertFalse(backend.contains("signalHardwareProcesses("));
        assertTrue(handler.contains(
                "CameraDaemon.stopAllCamerasForProcessRestart()"));
        assertTrue(handler.contains(
                "CameraDaemon.abortCameraRestartPreparation()"));
        assertTrue(daemon.contains(
                "CAMERA_RESTART_PREPARED.set(true);"));
        assertTrue(daemon.contains(
                ".stopHardwareProcessForExit();"));
        assertFalse(handler.contains(
                "if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected())"));
        assertTrue(daemon.contains(
                "stopDiLink5HardwareForProcessExit(\"shutdown\");"));
        assertTrue(daemon.contains(
                "stopDiLink5HardwareForProcessExit(\"shutdown hook\");"));
    }

    @Test
    public void diagnosticsCannotMutateDormantLegacyCameraState()
            throws IOException {
        String resolver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/CameraConfigResolver.java");
        String activity = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        String platform = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/DiLink5Platform.java");
        String panel = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/power/StealthPanel.java");
        String battery = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/power/BatteryVoltageMonitorV2.java");
        String mcu = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/power/McuPowerHal.java");
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String accSentry = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");

        assertTrue(resolver.contains(
                "public static boolean saveLegacyOemCameraSettings("));
        assertTrue(resolver.contains(
                "DiLink5Platform.refreshActiveMode();"));
        assertTrue(resolver.contains(
                "if (isDiLink5RuntimeSelected()) return false;"));
        assertTrue(activity.contains(
                ".saveLegacyOemCameraSettings("));
        assertTrue(activity.contains(
                "saveOemDashcamButton.isEnabled = legacyMappingEditable"));
        assertTrue(activity.contains(
                "concurrentProbeSwitch?.isEnabled = legacyMappingEditable"));
        assertTrue(activity.contains(
                "swapButton?.isEnabled = legacyMappingEditable"));
        assertTrue(handler.contains(
                "DiLink 4 camera options are unavailable in DiLink 5 mode"));
        assertTrue(platform.contains(
                "public static boolean isPanelControlModeSelected()"));
        assertTrue(panel.contains(
                ".isPanelControlModeSelected();"));
        assertFalse(panel.contains("optString(\"cameraMode\""));
        assertTrue(battery.contains(".isDiLink4Selected();"));
        assertFalse(battery.contains("optString(\"cameraMode\""));
        assertTrue(mcu.contains(".isDiLink4Selected();"));
        assertFalse(mcu.contains("optString(\"cameraMode\""));
        assertTrue(daemon.contains(".isDiLink4Selected();"));
        assertTrue(accSentry.contains(".isDiLink4Selected();"));
    }

    @Test
    public void dilink5UsesNativeTextureGeometryAndDisablesLegacyRedMask()
            throws IOException {
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        String recorder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuMosaicRecorder.java");
        String scaler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/streaming/GpuStreamScaler.java");

        assertOrdered(
                camera,
                "if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {",
                "return 1;");
        assertTrue(camera.contains(
                "CAMERA_LAYOUT_MODE == 1 && !USE_DILINK5_QCARCAM_PATH"));
        assertTrue(camera.contains(
                "? GlUtil.create2DTexture()\n"
                        + "            : GlUtil.createExternalTexture();"));
        assertFalse(camera.contains("new HandlerThread(\"DiLink5FrameCb\")"));
        assertFalse(recorder.contains("float tileCropY = 0.0423077"));
        assertFalse(scaler.contains("float tileCropY = 0.0423077"));
        assertTrue(recorder.contains(
                "String fullFrameSampling = \"        samplePos = vTexCoord;\\n\";"));
        assertTrue(scaler.contains(
                "samplePos = corner + vTexCoord * 0.5;"));
        assertOrdered(
                recorder,
                "DiLink5Platform.isEnabled()) {",
                "enabled = false;");
        assertOrdered(
                scaler,
                "DiLink5Platform.isEnabled()) {",
                "enabled = false;");
    }

    @Test
    public void dilink5NeverRunsTheLegacyCameraAutoProbe()
            throws IOException {
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");

        assertTrue(camera.contains(
                "&& !skipFrameValidation && !USE_DILINK5_QCARCAM_PATH"));
        assertTrue(camera.contains(
                "&& !USE_DILINK5_QCARCAM_PATH\n"
                        + "                    && downscaler != null"));
        int runtimeGuard = camera.indexOf(
                "if (USE_DILINK5_QCARCAM_PATH) {\n"
                        + "                probeComplete = true;");
        int frame15 = camera.indexOf("if (frameCounter == 15", runtimeGuard);
        assertTrue(runtimeGuard >= 0);
        assertTrue(frame15 > runtimeGuard);

        int advance = camera.indexOf(
                "private void advanceProbeToNext(int skipId)");
        int close = camera.indexOf("// Close current camera cleanly", advance);
        assertTrue(advance >= 0);
        assertTrue(camera.indexOf(
                "if (USE_DILINK5_QCARCAM_PATH)", advance) < close);

        int setter = camera.indexOf(
                "public void setAutoProbeCameras(boolean enabled)");
        int assignment = camera.indexOf(
                "this.autoProbeCameras = enabled;", setter);
        assertTrue(setter >= 0);
        assertTrue(camera.indexOf(
                "if (USE_DILINK5_QCARCAM_PATH)", setter) < assignment);
    }

    @Test
    public void sharedViewsKeepTheDilink5MosaicAndCropLocally()
            throws IOException {
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");
        String scaler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/streaming/GpuStreamScaler.java");
        String bridge = readRepositoryFile(
                "app/src/main/cpp/camera/qcarcam_bridge.cpp");

        assertTrue(pipeline.contains("bsLayout = 3;"));
        assertFalse(pipeline.contains("setActiveCamera("));
        assertTrue(pipeline.contains(
                "scaler.setCameraLayout(mode == 7 || mode == 8 ? 3 : 1);"));
        assertTrue(bridge.contains("g_active_camera{4}"));
        assertTrue(scaler.contains(
                "if (uViewMode == 2) corner = vec2(0.5, 0.0)"));
        assertTrue(scaler.contains(
                "else if (uViewMode == 3) corner = vec2(0.0, 0.5)"));
        assertTrue(scaler.contains(
                "else if (uViewMode == 4) corner = vec2(0.5, 0.5)"));
    }

    @Test
    public void daemonUsesTheProfileEncoderGeometry() throws IOException {
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String factory = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuPipelineFactory.java");

        assertTrue(daemon.contains(".createDefault(eventDir)"));
        assertTrue(factory.contains("resolved.getProfile().getEncoderWidth()"));
        assertTrue(factory.contains("resolved.getProfile().getEncoderHeight()"));
    }

    @Test
    public void telemetryBridgeUsesTheSelectedModeAsItsOnlyGate()
            throws IOException {
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        String helper = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDeviceHelper.java");
        String bridge = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/dilink5/Dilink5TelemetryBridge.java");
        String server = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java");
        String keepalive = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/DaemonKeepaliveService.kt");
        String carSvc = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/CarSvcTelemetry.kt");

        assertTrue(collector.contains(
                "DILINK5_AUTOMATION_MAX_AGE_MS"));
        assertTrue(collector.contains(
                "diLink5BridgeReceivedAtElapsedMs = sampleElapsed;"));
        assertTrue(collector.contains(
                "diLink5DynamicsObservedAtElapsedMs = acceptedDynamicsObservedAt;"));
        assertTrue(bridge.contains(
                "\"dynamicsObservedElapsedMs\""));
        assertTrue(collector.contains(
                "System.currentTimeMillis() + remainingMs"));
        assertTrue(collector.contains(
                "diLink5TyreTemperatureAt"));
        assertTrue(collector.contains(
                "acceptDiLink5Telemetry("));
        assertTrue(collector.contains(
                "isDiLink5DaemonProcess()"));
        assertTrue(collector.contains(
                "if (isDiLink5DaemonProcess()) return;"));
        assertTrue(collector.contains(
                "if (isDiLink5ProducerProcess()) return true;"));
        assertTrue(collector.contains(
                "pollIntervalMs(accIsOn, isDiLink5ProducerActive())"));
        assertTrue(collector.contains(
                "for (int attempt = 0; attempt < 2; attempt++)"));
        assertTrue(collector.contains(
                "publishDiLink5FallbackEvents();"));
        assertOrdered(
                collector,
                "private synchronized void refreshDiLink5AutomationSnapshot()",
                "collectAc(next);",
                "collectSettings(next);",
                "collectLight(next);",
                "collectSafetyBelt(next);",
                "if (accIsOn) collectEnergy(next);",
                "int autoWiper = readAutoWiperNow();",
                "int wiperActive = readWiperActiveNow();");
        assertTrue(collector.contains(
                "\"occupantDriver\", 0, 1"));
        assertTrue(collector.contains(
                "int warnings = readAdasWarningsNow();"));
        assertTrue(collector.contains(
                "for (int bit = BS_LEFT_BIT; bit <= DOW_RIGHT_BIT; bit <<= 1)"));
        assertTrue(collector.contains(
                "case \"occupantDriver\":"));
        assertOrdered(
                collector,
                "if (isDiLink5ProducerActive()) {",
                "collectAdas(b);",
                "collectSettings(b);",
                "collectPower(b);",
                "collectDoorLock(b);",
                "collectBodyworkExtended(b);",
                "if (!accIsOn) b.steeringAngleDegrees(Double.NaN);",
                "collectEngineExtended(b);");
        assertFalse(collector.contains(
                "\"/data/local/tmp/byd_telemetry_snap.json\""));
        assertFalse(collector.contains(
                "DILINK5_DRIVING_SNAPSHOT_MAX_AGE_MS"));
        assertTrue(helper.contains("DiLink5Platform.isSelected()"));
        assertFalse(helper.contains("DiLink5Platform.isEnabled()"));
        assertFalse(collector.contains(
                "DiLink5QCarCamBackend.isSupported()"));
        assertOrdered(
                collector,
                "if (isDiLink5Vehicle()) {",
                "Dilink5SdkInjector.ensure(context);");
        assertTrue(bridge.contains(
                "new InetSocketAddress(\"127.0.0.1\", PORT)"));
        assertTrue(bridge.contains(
                "TELEMETRY_COMMAND = \"DILINK5_TELEMETRY\""));
        assertTrue(bridge.contains(
                "RemoteDevViewBridgeAuth.sign(request)"));
        assertTrue(bridge.contains(
                "HELLO_COMMAND = \"DILINK5_HELLO\""));
        assertTrue(bridge.contains("request.put(\"session\", ingressSession)"));
        assertTrue(bridge.contains("request.put(\"energyFeedback\""));
        assertTrue(bridge.contains("request.put(\"doorLockState\""));
        assertTrue(bridge.contains("request.put(\"doorStates\""));
        assertTrue(server.contains(
                "case \"DILINK5_TELEMETRY\":"));
        assertTrue(server.contains(
                "RemoteDevViewBridgeAuth.verify(line)"));
        assertTrue(server.contains(
                "rememberNonce("));
        assertTrue(server.contains(
                "DILINK5_INGRESS_SESSION"));
        assertTrue(server.contains(
                "case \"DILINK5_HELLO\":"));
        assertTrue(server.contains(
                "if (!diLink5Authenticated) return null;"));
        assertTrue(server.contains(
                "ps -A -o PID,NAME"));
        assertTrue(server.contains(
                "$2==\\\"acc_sentry_daemon\\\""));
        assertTrue(server.contains(
                "/data/local/tmp/start_acc_sentry.sh"));
        assertTrue(server.contains(
                "/data/local/tmp/acc_sentry_daemon.disabled"));
        assertTrue(server.contains(
                "/data/local/tmp/overdrive_parked_shutdown"));
        assertTrue(server.contains(
                "request.optJSONArray(\"doorStates\")"));
        assertTrue(collector.contains(
                "DoorEvent.acceptSample("));
        assertTrue(collector.contains(
                "settingDevice,\n                energyDevice)) {"));
        assertTrue(collector.contains(
                "settingDevice,\n                energyDevice);"));
        assertTrue(collector.contains(
                "current.toBuilder().energyMode(mode).build()"));
        assertTrue(server.contains(
                "case \"DILINK5_VEHICLE_EVENT\":"));
        assertTrue(keepalive.contains(
                ".syncDiLink5Producer(applicationContext)"));
        assertFalse(keepalive.contains(
                "BydDataCollector.getInstance().init"));
        assertFalse(helper.contains("twoArgRegistered"));
        assertTrue(carSvc.contains("0x21403407"));
        assertFalse(carSvc.contains("0x21403c00"));
        assertTrue(carSvc.contains("0x2140461c"));
    }

    @Test
    public void telemetryPermissionsMatchTheDevicesOpenedAtRuntime()
            throws IOException {
        String manifest = readRepositoryFile("app/src/main/AndroidManifest.xml");
        String granter = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/PermissionGranter.java");
        String[] permissions = {
                "android.permission.BYDAUTO_SENSOR_COMMON",
                "android.permission.BYDAUTO_OTA_COMMON",
                "android.permission.BYDAUTO_POWER_COMMON",
                "android.permission.BYDAUTO_REAR_VIEW_MIRROR_COMMON",
                "android.permission.BYDAUTO_MOTOR_COMMON",
                "android.permission.BYDAUTO_VEHICLEHEALTH_COMMON",
                "android.permission.BYDAUTO_VEHICLEHEALTH_GET"
        };
        for (String permission : permissions) {
            assertTrue(permission, manifest.contains(permission));
            assertTrue(permission, granter.contains(permission));
        }
    }

    @Test
    public void pressureUnitFilterIsRegisteredOnInstrumentOnly()
            throws IOException {
        String helper = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDeviceHelper.java");
        int charging = helper.indexOf("registerChargingListener(");
        int instrument = helper.indexOf("registerInstrumentListener(", charging);
        int safetyBelt = helper.indexOf("registerSafetyBeltListener(", instrument);

        assertTrue(charging >= 0);
        assertTrue(instrument > charging);
        assertTrue(safetyBelt > instrument);
        assertFalse(helper.substring(charging, instrument)
                .contains("new int[]{4208}"));
        assertTrue(helper.substring(instrument, safetyBelt)
                .contains("new int[]{4208}"));
    }

    @Test
    public void typedTelemetryCallbacksMatchTheDilink5FrameworkSignatures()
            throws IOException {
        String helper = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDeviceHelper.java");
        String speed = readRepositoryFile(
                "stubs-bydauto/android/hardware/bydauto/speed/AbsBYDAutoSpeedListener.java");
        String instrument = readRepositoryFile(
                "stubs-bydauto/android/hardware/bydauto/instrument/AbsBYDAutoInstrumentListener.java");
        String tyre = readRepositoryFile(
                "stubs-bydauto/android/hardware/bydauto/tyre/AbsBYDAutoTyreListener.java");
        String charging = readRepositoryFile(
                "stubs-bydauto/android/hardware/bydauto/charging/AbsBYDAutoChargingListener.java");
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");

        assertTrue(speed.contains("onSpeedValueChanged(double speed)"));
        assertTrue(instrument.contains(
                "onExternalChargingPowerChanged(float power)"));
        assertTrue(tyre.contains(
                "onTyreBatteryValueChanged(int wheel, float value)"));
        assertTrue(charging.contains(
                "onChargingCapacityChanged(float capacity)"));
        assertTrue(helper.contains("onSpeedValueChanged(double speed)"));
        assertTrue(helper.contains(
                "onExternalChargingPowerChanged(float power)"));
        assertTrue(helper.contains(
                "onTyreBatteryValueChanged(int wheel, float value)"));
        assertTrue(helper.contains(
                "onChargingCapacityChanged(float capacity)"));
        assertTrue(collector.contains(
                "if (!isDiLink5Vehicle() && instrumentDevice != null)"));
        assertOrdered(
                collector,
                "if (\"onTyreBatteryValueChanged\".equals(method)",
                "if (isDiLink5Vehicle()) return;",
                "if (\"onTyrePressureValueChanged\".equals(method)");
        assertOrdered(
                collector,
                "if (isDiLink5Vehicle()\n"
                        + "                && noteRegisterOk(collectDataDevice,",
                "BydDeviceHelper.registerCollectDataListener(");
    }

    @Test
    public void selectingAnotherModeClearsAStaleDilink5Profile()
            throws IOException {
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");

        assertOrdered(
                handler,
                "camCfg.put(\"cameraMode\", mode);",
                "if (!\"dilink5\".equals(mode)) {",
                "PROFILE_DILINK5_SEALION7",
                "camCfg.put(\"cameraProfile\",",
                "CameraProfiles.PROFILE_AUTO");
    }

    @Test
    public void vehicleModeChangesCommitOnlyOnReplacementDaemonStartup()
            throws IOException {
        String platform = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/dilink5/DiLink5Platform.java");
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        String configManager = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt");
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String keepalive = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/DaemonKeepaliveService.kt");

        assertTrue(platform.contains("stageConfiguredMode(String requestedMode)"));
        assertFalse(platform.contains(
                "public static synchronized boolean stageConfiguredMode(String requestedMode)"));
        assertTrue(platform.contains("requested.equals(active)"));
        assertTrue(platform.contains(
                "requested.equals(pending) && requested.equals(configured)"));
        assertFalse(platform.contains(
                "isSelected(processMode, \"\") == isSelected(requested, \"\")"));
        assertTrue(platform.contains("activateConfiguredMode()"));
        assertTrue(platform.contains("currentActiveModeGeneration()"));
        assertTrue(platform.contains("matchesActiveModeGeneration(String expected)"));
        assertTrue(platform.contains("java.util.UUID.randomUUID()"));
        assertTrue(platform.contains("java.util.UUID.fromString(generation)"));
        assertFalse(platform.contains("\"mode:\" + effectiveMode("));
        assertOrdered(
                platform,
                "public static boolean stageConfiguredMode(String requestedMode)",
                "UnifiedConfigManager.runUnderConfigLock",
                "readDurableConfiguredMode()");
        assertOrdered(
                platform,
                "String active = effectiveMode(readActiveMode(), pending, configured);",
                "processMode = active;",
                "canStageMode(\n"
                        + "                active, pending, configured, requested)",
                "ModeMarkerSnapshot before = snapshotModeMarkersLocked();",
                "writeMode(pendingModePath(), requested)",
                "writeMode(activeModePath(), active)",
                "restoreModeMarkersLocked(before)");
        assertOrdered(
                platform,
                "public static ModeActivation activateConfiguredMode()",
                "UnifiedConfigManager.runUnderConfigLock",
                "String configured = readDurableConfiguredMode();",
                "synchronized (DiLink5Platform.class)",
                "ModeMarkerSnapshot before = snapshotModeMarkersLocked();",
                "deletePendingMode()",
                "writeMode(activeModePath(), configured)",
                "restoreModeMarkersLocked(before)");
        assertTrue(platform.contains(
                "ScratchPaths.path(\"overdrive_active_vehicle_mode\")"));
        assertTrue(platform.contains(
                "ScratchPaths.path(\"overdrive_pending_vehicle_mode\")"));
        assertFalse(platform.contains(
                "\"/data/local/tmp/overdrive_active_vehicle_mode\""));
        assertFalse(platform.contains(
                "\"/data/local/tmp/overdrive_pending_vehicle_mode\""));
        assertOrdered(
                platform,
                "public static void refreshActiveMode()",
                "UnifiedConfigManager.runUnderConfigLock",
                "configured = readDurableConfiguredMode();",
                "synchronized (DiLink5Platform.class)");
        assertFalse(handler.contains(".stageConfiguredMode(mode)"));
        assertOrdered(
                configManager,
                "private fun saveConfigLocked(",
                "if (!stageCameraModeChange(config))",
                "val writeResult = saveConfigInternal(config)",
                "private fun stageCameraModeChange(",
                "DiLink5Platform.stageConfiguredMode(requestedMode, currentMode)");
        assertFalse(configManager.contains(
                "DiLink5Platform.isActiveMode(requestedMode) ||"));
        assertOrdered(
                daemon,
                "UnifiedConfigManager.init();",
                "ModeActivation\n"
                        + "                vehicleModeActivation",
                ".activateConfiguredMode();",
                "requestAppVehicleModeSync(\n"
                        + "                    vehicleModeActivation.previousMode,\n"
                        + "                    vehicleModeActivation.activeMode)");
        assertTrue(keepalive.contains(".refreshActiveMode()"));
        String ipc = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java");
        assertFalse(ipc.contains(".stageConfiguredMode(requestedMode)"));
        assertTrue(ipc.contains(".isSameMode(previousMode, activeMode)"));
        assertFalse(ipc.contains(
                "isDiLink5Mode(previousMode) == isDiLink5Mode(currentMode)"));
        int syncStart = ipc.indexOf(
                "public static void requestAppVehicleModeSync(");
        int syncEnd = ipc.indexOf(
                "private static boolean isDiLink5Mode", syncStart);
        assertTrue(syncStart >= 0);
        assertTrue(syncEnd > syncStart);
        assertFalse(ipc.substring(syncStart, syncEnd).contains("forceReload()"));
        assertOrdered(
                ipc,
                "if (!isDiLink5Mode(previousMode) && isDiLink5Mode(activeMode))",
                "BydDataCacheWhitelist",
                ".applyViaDaemonWhenReady()");
    }

    @Test
    public void dilink5StartupRemainsAsynchronousLikeTheWorkingBranch()
            throws IOException {
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");
        String bridge = readRepositoryFile(
                "app/src/main/cpp/camera/qcarcam_bridge.cpp");

        assertFalse(pipeline.contains("DILINK5_CAMERA_START_WAIT_MS"));
        assertFalse(bridge.contains("waitForFirstFrame()"));
        assertFalse(bridge.contains("sidecarSocketPublished()"));
        assertTrue(bridge.contains(
                "return ensureStreamThread() ? JNI_TRUE : JNI_FALSE;"));
    }

    @Test
    public void dilink5PowerAndConnectivityStayBehindTheExplicitModeGate()
            throws IOException {
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        String telegram = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/TelegramBotDaemon.java");
        String config = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt");
        String guard = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/routing/DrivingSafetyGuard.java");

        assertTrue(daemon.contains("private static boolean isBodyworkSupported()"));
        assertTrue(daemon.contains("BodyworkListenerRegistrar.register("));
        assertTrue(daemon.contains("PowerListenerRegistrar.register("));
        assertTrue(daemon.contains("if (!isDilink5CameraMode()\n"
                + "                || (wifiLock != null && wifiLock.isHeld())"));
        assertFalse(daemon.contains(
                "settings put global wifi_suspend_optimizations_enabled 0"));
        assertFalse(daemon.contains(
                "settings put global wifi_sleep_policy 2"));
        assertTrue(daemon.contains(
                "\"userActivity\", long.class, int.class, int.class"));
        assertTrue(daemon.contains(
                "pm, android.os.SystemClock.uptimeMillis(), 0, 1"));

        int sleepComment = daemon.indexOf("// KEYCODE_SLEEP moves Android to Asleep");
        int dilink5Guard = daemon.lastIndexOf(
                "if (isDilink5CameraMode()) {", sleepComment);
        int legacySleep = daemon.indexOf("\"input keyevent 223\"", sleepComment);
        assertTrue(dilink5Guard >= 0);
        assertTrue(sleepComment > dilink5Guard);
        assertTrue(legacySleep > sleepComment);

        assertTrue(telegram.contains(
                "com.overdrive.app.mqtt.ProxyHelper.isProxyAvailable()"));
        assertTrue(telegram.contains(".retryOnConnectionFailure(true)"));
        assertTrue(telegram.contains("connectionPool().evictAll()"));
        assertTrue(telegram.contains("if (elapsed > 5_000)"));

        assertTrue(config.contains(
                "val defaultModel = if (isDilink5Selected(config)) \"sealion7\" else \"seal\""));
        assertTrue(config.contains(
                "DiLink5Platform.isSelected("));
        assertFalse(config.contains(
                "DiLink5QCarCamBackend.isSupported()"));
        assertTrue(guard.contains(
                "if (gm == null) return GearReading.UNKNOWN;"));
    }

    @Test
    public void dilink5BackgroundAccessIsCompleteAndModeGated()
            throws IOException {
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        int method = daemon.indexOf(
                "public static JSONObject applyBackgroundAccess()");
        int nextSection = daemon.indexOf(
                "// ==================== ACC STATE DETECTION", method);
        assertTrue(method >= 0);
        assertTrue(nextSection > method);
        String body = daemon.substring(method, nextSection);

        assertTrue(body.contains("if (!isDilink5CameraMode())"));
        assertTrue(body.contains("RUN_IN_BACKGROUND allow"));
        assertTrue(body.contains("RUN_ANY_IN_BACKGROUND allow"));
        assertTrue(body.contains("WAKE_LOCK allow"));
        assertTrue(body.contains("deviceidle whitelist +"));
        assertTrue(body.contains("com.byd.appstartup/whitelist"));
        assertTrue(body.contains("AUTO_START allow"));
        assertTrue(body.contains("BOOT_COMPLETED allow"));
        assertTrue(body.contains("cmd appops get "));
        assertTrue(body.contains("boolean startupProvider"));
        int startupManager = body.indexOf("boolean startupManager");
        int startupWhitelist = body.indexOf("boolean sscWhitelist", startupManager);
        assertTrue(startupManager >= 0);
        assertTrue(startupWhitelist > startupManager);
        assertFalse(body.substring(startupManager, startupWhitelist)
                .contains("startupProvider"));
        assertTrue(body.contains("settings get global ssc_whitelist"));
        assertTrue(body.contains("settings get secure ssc_whitelist"));
        assertTrue(body.contains("|| exit 1"));
    }

    @Test
    public void dilink5VehicleStateStaysOnTheAppOwnedBridge()
            throws IOException {
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        String bridge = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/dilink5/Dilink5TelemetryBridge.java");

        assertOrdered(
                collector,
                "private void publishDiLink5StateSamples(",
                "notePassengerDoorStateForSeatbelt(i + 1, doorStates[i]);",
                "DoorEvent.acceptSample(");
        assertOrdered(
                collector,
                "public void pollPassengerDoorStateForSeatbeltNow() {",
                "if (isDiLink5BridgeConsumer()) return;",
                "if (bodyworkDevice == null) return;");
        assertOrdered(
                collector,
                "public synchronized void collectAllFull() {",
                "BydVehicleData built = b.build();",
                "if (isDiLink5ProducerActive() && !accIsOn) {",
                "built = clearStaleDiLink5DrivingFields(built);",
                "built = publishCollectedSnapshot(");
        assertOrdered(
                collector,
                "public synchronized void stop() {",
                "if (stopDiLink5Bridge) {",
                "snapshot.set(null);",
                "Dilink5TelemetryBridge.stop();");
        assertTrue(bridge.contains("private static volatile Socket socket;"));
        assertOrdered(
                bridge,
                "public static void stop() {",
                "active = false;",
                "lifecycleGeneration.incrementAndGet();",
                "closeSocket();",
                "pendingTelemetry.set(null);",
                "pendingEvents.clear();");
        assertFalse(bridge.contains(
                "io.execute(Dilink5TelemetryBridge::closeSocket)"));
        assertTrue(bridge.contains(
                "if (!isLifecycleActive(generation)) return false;"));
        assertOrdered(
                bridge,
                "Socket connected = new Socket();",
                "socket = connected;",
                "if (!isLifecycleActive(generation)) {",
                "throw new IllegalStateException(\"bridge stopped\");");
        assertTrue(bridge.contains(
                "while (isLifecycleActive(generation)) {"));
        assertTrue(bridge.contains(
                "private static boolean send(JSONObject request, long generation)"));
        assertTrue(bridge.contains(
                "if (isLifecycleActive(generation)) {\n"
                        + "                            pendingEvents.offerFirst(event);"));
        assertTrue(bridge.contains(
                "if (isLifecycleActive(generation)) {\n"
                        + "                        pendingTelemetry.accumulateAndGet(telemetry,"));
        assertOrdered(
                collector,
                "public boolean setHeadlightMode(int mode) {",
                "\"setHeadlightControlMode\", mode",
                "BydFeatureIds.INSTRUMENT_HEADLIGHT_CONTROL_SET");
        assertOrdered(
                collector,
                "public int readHeadlightModeNow() {",
                "\"getHeadlightControlMode\"",
                "BydFeatureIds.INSTRUMENT_HEADLIGHT_CONTROL_FEEDBACK");
    }

    private static void assertOrdered(String source, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int position = source.indexOf(needle, previous + 1);
            assertTrue("Missing or out of order: " + needle, position > previous);
            previous = position;
        }
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        return new String(
                Files.readAllBytes(repositoryFile(relativePath)),
                StandardCharsets.UTF_8);
    }

    private static Path repositoryFile(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return fromModule;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }

    private static String sha256(Path path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
