package com.overdrive.app.camera.dilink5;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle guard for DiLink 5 QCarCam ownership.
 *
 * <p>The markers live outside the daemon process so a replacement daemon can
 * see what its predecessor left behind. A lease that a dead daemon never
 * released is treated as an unclean release: the stale lease is cleared, a
 * foreign release fence is recorded, and the cross-process reacquire cooldown
 * gives the AIS server time to drop the dead client before capture resumes.
 * Nothing here disables the camera for a whole vehicle boot any more; the
 * remaining fail-closed outcomes are process-scoped (a daemon restart clears
 * them) and cover unreadable markers, an unavailable boot ID, and unstable
 * Android system services. The daemon does not touch the vendor AVM service.
 */
final class DiLink5CameraSafety {

    private static final DaemonLogger logger =
            DaemonLogger.getInstance("DiLink5CameraSafety");

    static final long SAME_PROCESS_REACQUIRE_COOLDOWN_MS = 3_000L;
    static final long CROSS_PROCESS_REACQUIRE_COOLDOWN_MS = 15_000L;
    static final long SYSTEM_STABILITY_TIMEOUT_MS = 6_000L;
    static final long SYSTEM_STABILITY_SAMPLE_INTERVAL_MS = 500L;
    static final int SYSTEM_STABILITY_REQUIRED_SAMPLES = 3;
    private static final long COMMAND_TIMEOUT_MS = 750L;

    private static File sessionMarker() {
        return new File(ScratchPaths.path("overdrive_dilink5_camera_session"));
    }

    private static File blockMarker() {
        return new File(ScratchPaths.path("overdrive_dilink5_camera_blocked"));
    }

    private static File releaseMarker() {
        return new File(ScratchPaths.path("overdrive_dilink5_camera_release"));
    }
    private static final String PROCESS_TOKEN =
            UUID.randomUUID().toString().replace("-", "");

    private static final AtomicBoolean FIRST_FRAME_MARKED =
            new AtomicBoolean(false);
    private static boolean processSuppressed;
    private static boolean acquisitionLeaseActive;
    private static long nextAcquisitionLeaseGeneration;
    private static long activeAcquisitionLeaseGeneration =
            Long.MIN_VALUE;
    private static String cachedBootId;
    private static long acquisitionStartedElapsedMs;

    private DiLink5CameraSafety() {}

    static boolean runPreflight(
            long startEpoch, long acquisitionGeneration) {
        if (!admitCurrentProcess()) return false;
        if (!awaitStableSystemServices(
                startEpoch, acquisitionGeneration)) {
            if (isStartAllowed(startEpoch, acquisitionGeneration)) {
                suppressForProcess(
                        "Android system services did not remain stable");
            }
            return false;
        }
        if (!isStartAllowed(startEpoch, acquisitionGeneration)) return false;
        // No vendor AVM Binder preflight: DI5 no longer touches com.ts.avm.
        // The AIS server serves fast_cam_capture independently of the OEM AVM
        // state, and the probe's fail-closed branch could disable capture for
        // the whole process on an app-side transport hiccup.
        if (!awaitReacquireCooldown(
                startEpoch, acquisitionGeneration)) return false;
        if (!isStartAllowed(startEpoch, acquisitionGeneration)) return false;
        boolean servicesStable = probeSystemServices();
        if (!isStartAllowed(startEpoch, acquisitionGeneration)) return false;
        if (!servicesStable) {
            suppressForProcess(
                    "Android system services changed during camera preflight");
            return false;
        }
        if (!isStartAllowed(startEpoch, acquisitionGeneration)) return false;
        boolean admitted = admitCurrentProcess();
        return isStartAllowed(startEpoch, acquisitionGeneration) && admitted;
    }

    static synchronized long beginAcquisition() {
        if (!admitCurrentProcess()
                || acquisitionLeaseActive) {
            return Long.MIN_VALUE;
        }
        String bootId = currentBootId();
        if (bootId == null) {
            suppressForProcess("Kernel boot ID is unavailable");
            return Long.MIN_VALUE;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long leaseGeneration = ++nextAcquisitionLeaseGeneration;
        if (leaseGeneration <= 0L) {
            nextAcquisitionLeaseGeneration = 1L;
            leaseGeneration = 1L;
        }
        if (!writeMarker(
                sessionMarker(),
                new Marker(
                        bootId,
                        PROCESS_TOKEN,
                        "acquiring",
                        now,
                        ""))) {
            suppressForProcess(
                    "Unable to persist the QCarCam acquisition lease");
            return Long.MIN_VALUE;
        }
        acquisitionLeaseActive = true;
        activeAcquisitionLeaseGeneration = leaseGeneration;
        acquisitionStartedElapsedMs = now;
        FIRST_FRAME_MARKED.set(false);
        logger.info("Armed boot-scoped QCarCam acquisition lease generation "
                + leaseGeneration + ".");
        return leaseGeneration;
    }

    static synchronized void markFirstFrame(long leaseGeneration) {
        if (!matchesAcquisitionLease(
                activeAcquisitionLeaseGeneration,
                leaseGeneration)
                || !acquisitionLeaseActive
                || !FIRST_FRAME_MARKED.compareAndSet(false, true)) {
            return;
        }
        String bootId = currentBootId();
        if (bootId == null) return;
        if (!writeMarker(
                sessionMarker(),
                new Marker(
                        bootId,
                        PROCESS_TOKEN,
                        "streaming",
                        acquisitionStartedElapsedMs,
                        ""))) {
            logger.warn(
                    "Unable to update the QCarCam lease to streaming.");
        }
    }

    static synchronized boolean markCleanRelease(
            long leaseGeneration) {
        if (!acquisitionLeaseActive) {
            return activeAcquisitionLeaseGeneration == Long.MIN_VALUE;
        }
        if (!matchesAcquisitionLease(
                activeAcquisitionLeaseGeneration,
                leaseGeneration)) {
            return false;
        }
        String bootId = currentBootId();
        if (bootId == null) {
            suppressForProcess(
                    "Cannot clear the QCarCam lease without a boot ID");
            return false;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        boolean releasePersisted = writeMarker(
                releaseMarker(),
                new Marker(
                        bootId,
                        PROCESS_TOKEN,
                        "released",
                        now,
                        ""));
        if (!releasePersisted) {
            suppressForProcess(
                    "Unable to persist the QCarCam release fence");
            return false;
        }
        File sessionMarker = sessionMarker();
        Marker session = readMarker(sessionMarker);
        if (session != null
                && bootId.equals(session.bootId)
                && PROCESS_TOKEN.equals(session.owner)) {
            safeDelete(sessionMarker);
        }
        acquisitionLeaseActive = false;
        activeAcquisitionLeaseGeneration = Long.MIN_VALUE;
        acquisitionStartedElapsedMs = 0L;
        FIRST_FRAME_MARKED.set(false);
        logger.info("Cleared QCarCam acquisition lease generation "
                + leaseGeneration + " after confirmed release.");
        return true;
    }

    static boolean matchesAcquisitionLease(
            long activeGeneration,
            long expectedGeneration) {
        return expectedGeneration != Long.MIN_VALUE
                && activeGeneration == expectedGeneration;
    }

    static synchronized void recordForeignRelease(int pid) {
        String bootId = currentBootId();
        if (bootId == null) {
            suppressForProcess(
                    "Cannot fence a stale QCarCam release without a boot ID");
            return;
        }
        String owner = "foreign" + Math.max(0, pid)
                + UUID.randomUUID().toString()
                        .replace("-", "").substring(0, 8);
        if (!writeMarker(
                releaseMarker(),
                new Marker(
                        bootId,
                        owner,
                        "released",
                        android.os.SystemClock.elapsedRealtime(),
                        "stale-process-cleanup"))) {
            suppressForProcess(
                    "Unable to persist the stale QCarCam release fence");
        }
    }

    static synchronized void suppressForProcess(String reason) {
        processSuppressed = true;
        logger.error("DiLink 5 camera suppressed for this daemon process: "
                + sanitize(reason));
    }

    static synchronized boolean isCaptureSuppressed() {
        return processSuppressed;
    }

    private static synchronized boolean admitCurrentProcess() {
        if (processSuppressed) return false;
        String bootId = currentBootId();
        if (bootId == null) {
            suppressForProcess("Kernel boot ID is unavailable");
            return false;
        }

        // No code path writes a boot-scoped block any more. Every reason an
        // earlier build had for one (release-guard exit 125, frame stall, an
        // orphaned session) either proved a producer was already dead or was
        // a misclassified intentional stop, and each wedged the camera until
        // the next vehicle boot (log_2MEH8B86, log_B26FJKKN, log_R6SPYGJ5).
        // A marker left behind by such a build is cleared, not honored.
        File blockMarker = blockMarker();
        if (blockMarker.exists()) {
            Marker blocked = readMarker(blockMarker);
            safeDelete(blockMarker);
            logger.warn("Cleared a legacy boot-scoped camera block marker"
                    + (blocked != null
                            ? " (" + sanitize(blocked.reason) + ")"
                            : " (unreadable)")
                    + "; camera admission continues.");
        }

        File sessionMarker = sessionMarker();
        Marker session = readMarker(sessionMarker);
        if (sessionMarker.exists() && session == null) {
            suppressForProcess(
                    "The QCarCam acquisition marker is unreadable");
            return false;
        }
        if (session != null) {
            if (!bootId.equals(session.bootId)) {
                safeDelete(sessionMarker);
            } else if (isForeignLiveSession(
                    bootId,
                    session.bootId,
                    session.owner,
                    PROCESS_TOKEN)) {
                // A previous daemon in this boot died while holding a QCarCam
                // lease (SIGKILL, native crash, HU-side kill). Its owner is
                // provably gone: CameraDaemon holds the singleton file lock,
                // so no other daemon instance is alive, and the kernel closed
                // that process's DMA-BUF/socket fds. Any orphaned
                // fast_cam_capture child is retired by the exact-path /proc
                // sweep that runs before this preflight (and exits 125 on its
                // own within 3 s once its consumer socket died). Treat the
                // stale lease as an unclean release: record a foreign release
                // fence so the cross-process reacquire cooldown gives the AIS
                // server time to drop the dead client, then continue.
                // Blocking the camera for the whole boot here is what kept
                // log_R6SPYGJ5 dark across three daemon sessions.
                logger.warn("Previous daemon ended without releasing QCarCam"
                        + " (state=" + sanitize(session.state)
                        + "); treating the stale lease as an unclean release"
                        + " and continuing after the reacquire cooldown.");
                safeDelete(sessionMarker);
                recordForeignRelease(0);
                if (processSuppressed) return false;
            }
        }

        File releaseMarker = releaseMarker();
        Marker release = readMarker(releaseMarker);
        if (releaseMarker.exists() && release == null) {
            suppressForProcess(
                    "The QCarCam release marker is unreadable");
            return false;
        }
        if (release != null && !bootId.equals(release.bootId)) {
            safeDelete(releaseMarker);
        }
        return true;
    }

    private static boolean awaitStableSystemServices(
            long startEpoch, long acquisitionGeneration) {
        int consecutive = 0;
        long deadline = android.os.SystemClock.elapsedRealtime()
                + SYSTEM_STABILITY_TIMEOUT_MS;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!isStartAllowed(startEpoch, acquisitionGeneration)) return false;
            if (probeSystemServices()) {
                consecutive++;
                if (consecutive >= SYSTEM_STABILITY_REQUIRED_SAMPLES) {
                    return true;
                }
            } else {
                consecutive = 0;
            }
            try {
                Thread.sleep(SYSTEM_STABILITY_SAMPLE_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean awaitReacquireCooldown(
            long startEpoch, long acquisitionGeneration) {
        while (true) {
            if (!isStartAllowed(startEpoch, acquisitionGeneration)) return false;
            long remaining;
            synchronized (DiLink5CameraSafety.class) {
                remaining = remainingReacquireCooldownMs(
                        android.os.SystemClock.elapsedRealtime());
            }
            if (remaining < 0L) return false;
            if (remaining == 0L) return true;
            logger.info("Waiting " + remaining
                    + "ms for the previous QCarCam generation to settle.");
            try {
                Thread.sleep(Math.min(remaining, 500L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static synchronized long remainingReacquireCooldownMs(long now) {
        Marker release = readMarker(releaseMarker());
        String bootId = currentBootId();
        if (release == null || bootId == null
                || !bootId.equals(release.bootId)) {
            return 0L;
        }
        if (release.elapsedRealtimeMs
                > now + SYSTEM_STABILITY_SAMPLE_INTERVAL_MS) {
            suppressForProcess(
                    "QCarCam release fence is ahead of the monotonic clock");
            return -1L;
        }
        long cooldown = cooldownForOwners(
                release.owner, PROCESS_TOKEN);
        return remainingCooldownMs(
                now, release.elapsedRealtimeMs, cooldown);
    }

    private static boolean probeSystemServices() {
        CommandResult boot = runCommand(
                "/system/bin/getprop", "sys.boot_completed");
        if (boot.exitCode != 0
                || !"1".equals(boot.output.trim())) {
            return false;
        }
        CommandResult activity = runCommand(
                "/system/bin/service", "check", "activity");
        if (!serviceCheckSucceeded(
                activity.exitCode, activity.output, "activity")) {
            return false;
        }
        CommandResult packageService = runCommand(
                "/system/bin/service", "check", "package");
        return serviceCheckSucceeded(
                packageService.exitCode,
                packageService.output,
                "package");
    }

    static boolean serviceCheckSucceeded(
            int exitCode, String output, String service) {
        if (exitCode != 0 || output == null || service == null) {
            return false;
        }
        String normalized = output.toLowerCase(Locale.ROOT);
        return normalized.contains(
                "service " + service.toLowerCase(Locale.ROOT) + ": found");
    }

    static boolean isForeignLiveSession(
            String currentBoot,
            String sessionBoot,
            String sessionOwner,
            String currentOwner) {
        return currentBoot != null
                && currentBoot.equals(sessionBoot)
                && sessionOwner != null
                && !sessionOwner.equals(currentOwner);
    }

    static long cooldownForOwners(
            String releaseOwner, String currentOwner) {
        return releaseOwner != null && releaseOwner.equals(currentOwner)
                ? SAME_PROCESS_REACQUIRE_COOLDOWN_MS
                : CROSS_PROCESS_REACQUIRE_COOLDOWN_MS;
    }

    static long remainingCooldownMs(
            long nowElapsedMs,
            long releaseElapsedMs,
            long cooldownMs) {
        if (releaseElapsedMs <= 0L || cooldownMs <= 0L) return 0L;
        long elapsed = nowElapsedMs - releaseElapsedMs;
        if (elapsed < 0L) return cooldownMs;
        return Math.max(0L, cooldownMs - elapsed);
    }

    private static boolean isStartAllowed(
            long startEpoch, long acquisitionGeneration) {
        return com.overdrive.app.daemon.CameraDaemon
                .isCameraStartEpochCurrent(startEpoch)
                && DiLink5QCarCamBackend
                        .isAcquisitionGenerationCurrent(
                                acquisitionGeneration);
    }

    private static synchronized String currentBootId() {
        if (cachedBootId != null) return cachedBootId;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(
                                "/proc/sys/kernel/random/boot_id"),
                        StandardCharsets.UTF_8))) {
            String value = reader.readLine();
            if (value != null
                    && value.matches("[0-9a-fA-F-]{16,64}")) {
                cachedBootId =
                        value.toLowerCase(Locale.ROOT);
            }
        } catch (Throwable failure) {
            logger.warn("Unable to read kernel boot ID: "
                    + failure.getMessage());
        }
        return cachedBootId;
    }

    private static CommandResult runCommand(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(
                    COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new CommandResult(-1, "");
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            process.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null
                        && output.length() < 2_048) {
                    if (output.length() > 0) output.append('\n');
                    output.append(line);
                }
            }
            return new CommandResult(
                    process.exitValue(), output.toString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "");
        } catch (Throwable failure) {
            return new CommandResult(-1, "");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Marker readMarker(File file) {
        if (file == null || !file.isFile()
                || file.length() <= 0L || file.length() > 4_096L) {
            return null;
        }
        String boot = null;
        String owner = null;
        String state = "";
        String reason = "";
        long elapsed = 0L;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(file),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.indexOf('=');
                if (separator <= 0) continue;
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                switch (key) {
                    case "boot":
                        boot = value;
                        break;
                    case "owner":
                        owner = value;
                        break;
                    case "state":
                        state = value;
                        break;
                    case "elapsed":
                        elapsed = Long.parseLong(value);
                        break;
                    case "reason":
                        reason = value;
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable failure) {
            return null;
        }
        if (boot == null
                || !boot.matches("[0-9a-fA-F-]{16,64}")
                || owner == null
                || !owner.matches("[0-9A-Za-z]{8,96}")
                || elapsed < 0L) {
            return null;
        }
        return new Marker(
                boot.toLowerCase(Locale.ROOT),
                owner,
                sanitize(state),
                elapsed,
                sanitize(reason));
    }

    private static boolean writeMarker(File destination, Marker marker) {
        File temporary = new File(
                destination.getPath()
                        + "." + android.os.Process.myPid()
                        + "." + UUID.randomUUID().toString()
                                .replace("-", "").substring(0, 12));
        FileDescriptor descriptor = null;
        FileOutputStream output = null;
        try {
            descriptor = android.system.Os.open(
                    temporary.getPath(),
                    android.system.OsConstants.O_WRONLY
                            | android.system.OsConstants.O_CREAT
                            | android.system.OsConstants.O_EXCL
                            | android.system.OsConstants.O_NOFOLLOW
                            | android.system.OsConstants.O_CLOEXEC,
                    0600);
            output = new FileOutputStream(descriptor);
            String body = "version=1\n"
                    + "boot=" + marker.bootId + "\n"
                    + "owner=" + marker.owner + "\n"
                    + "state=" + sanitize(marker.state) + "\n"
                    + "elapsed=" + marker.elapsedRealtimeMs + "\n"
                    + "reason=" + sanitize(marker.reason) + "\n";
            output.write(body.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            output.close();
            output = null;
            descriptor = null;
            android.system.Os.chmod(temporary.getPath(), 0600);
            android.system.Os.rename(
                    temporary.getPath(), destination.getPath());
            return destination.isFile();
        } catch (Throwable failure) {
            logger.warn("Unable to write camera safety marker "
                    + destination.getName() + ": "
                    + failure.getMessage());
            return false;
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (Throwable ignored) {
                }
            } else if (descriptor != null) {
                try {
                    android.system.Os.close(descriptor);
                } catch (Throwable ignored) {
                }
            }
            safeDelete(temporary);
        }
    }

    private static void safeDelete(File file) {
        if (file == null || !file.exists()) return;
        try {
            if (!file.delete()) {
                logger.warn("Unable to delete camera safety marker "
                        + file.getName());
            }
        } catch (Throwable failure) {
            logger.warn("Unable to delete camera safety marker "
                    + file.getName() + ": " + failure.getMessage());
        }
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ')
                .replace('\r', ' ')
                .replace('=', ':')
                .trim();
        return clean.length() <= 192
                ? clean : clean.substring(0, 192);
    }

    private static final class Marker {
        final String bootId;
        final String owner;
        final String state;
        final long elapsedRealtimeMs;
        final String reason;

        Marker(
                String bootId,
                String owner,
                String state,
                long elapsedRealtimeMs,
                String reason) {
            this.bootId = bootId;
            this.owner = owner;
            this.state = state;
            this.elapsedRealtimeMs = elapsedRealtimeMs;
            this.reason = reason;
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
