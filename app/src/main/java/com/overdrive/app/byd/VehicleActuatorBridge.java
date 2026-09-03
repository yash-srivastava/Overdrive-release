package com.overdrive.app.byd;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Daemon-side bridge that dispatches mirror-fold / HUD to {@code VehicleActuatorService} and
 * powertrain mode to its isolated {@code EnergyModeActuatorService}, so each write uses a real app
 * Context (UID 10xxx) — the environment where the OEM {@code setMirrorFoldState} /
 * {@code setHUDBrightness} calls (and the HUD-switch feature-id write) actually actuate.
 * The daemon's own mirror/HUD attempts (see {@link BydDataCollector#setMirrorsFolded}
 * / {@link BydDataCollector#setHudBrightness}) run independently; whichever environment the
 * HAL honours wins. Powertrain launches are synchronous and serialized so launch acceptance is
 * known before the daemon-side fallback runs. Uses the same proven
 * {@code am start-foreground-service} bridge as {@link AudioPlaybackController} to
 * {@code MediaPlaybackService}.
 */
public final class VehicleActuatorBridge {

    private static final String TAG = "VehicleActuatorBridge";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String SERVICE =
            "com.overdrive.app/.services.VehicleActuatorService";
    private static final String ENERGY_SERVICE =
            "com.overdrive.app/.services.EnergyModeActuatorService";
    private static final EnergyGenerationGate ENERGY_GENERATIONS = new EnergyGenerationGate();
    private static final long ENERGY_LAUNCH_TIMEOUT_MS = 1500L;
    private static final long ENERGY_STANDALONE_TIMEOUT_MS = 6000L;
    private static final long ENERGY_PROCESS_TERMINATION_GRACE_MS = 500L;
    private static final long ENERGY_DIRECT_START_TIMEOUT_MS = 750L;
    private static final long ENERGY_STATE_IO_TIMEOUT_MS = 500L;
    private static final String ENERGY_STATE_SETTING = "overdrive_energy_request_v3";
    private static final String ENERGY_STATE_CONFIRM_SETTING =
            "overdrive_energy_request_v3_confirm";
    private static final String ENERGY_AUTHORITY_FENCE_SETTING =
            "overdrive_energy_request_v4_authority";
    private static final String ENERGY_AUTHORITY_EPOCH_SETTING =
            "overdrive_energy_request_v5_epoch";
    private static final String ENERGY_STATE_LOCK_FILE =
            ScratchPaths.path("overdrive_energy_request.lock");
    private static final String ENERGY_STATE_COORDINATE_FILE =
            ScratchPaths.path("overdrive_energy_request.state");
    private static final long ENERGY_STATE_LOCK_TIMEOUT_MS = 400L;
    private static final long ENERGY_MARKER_MAX_FUTURE_NANOS =
            TimeUnit.SECONDS.toNanos(30L);
    private static final long ENERGY_MARKER_MAX_AGE_NANOS =
            TimeUnit.SECONDS.toNanos(60L);
    private static final long ENERGY_CONTROL_TIMEOUT_MS = 1500L;
    private static final long ENERGY_SETTINGS_COMMAND_TIMEOUT_MS = 750L;
    private static final int ENERGY_STATE_PENDING = 0;
    private static final int ENERGY_STATE_DESIRED = 1;
    private static final int ENERGY_STATE_CANCELLED = 2;
    private static final String SETTINGS_AUTHORITY = "settings";
    private static final String SETTINGS_CALL_GET_GLOBAL = "GET_global";
    private static final String SETTINGS_CALL_PUT_GLOBAL = "PUT_global";
    private static final String SETTINGS_CALL_VALUE = "value";
    private static final String SETTINGS_CALL_USER = "_user";
    private static final int ANDROID_SHELL_UID = 2000;
    /** Android's UserHandle.PER_USER_RANGE; kept local because the framework field is hidden. */
    private static final int ANDROID_UIDS_PER_USER = 100000;
    public static final int ENERGY_ACTUATOR_NONE = 0;
    public static final int ENERGY_ACTUATOR_DAEMON = 1;
    public static final int ENERGY_ACTUATOR_APP = 2;
    public static final int MAX_ENERGY_COMPENSATION_ATTEMPTS = 3;
    private static volatile String energyBootToken;
    private static volatile android.content.Context energyStateContext;
    private static volatile boolean shellSettingsResolverRejected;
    private static volatile boolean shellSettingsFallbackLogged;
    private static final EnergyLaunchLane ENERGY_LAUNCH_LANE = new EnergyLaunchLane();
    private static final EnergyDirectStartLane ENERGY_DIRECT_START_LANE =
            new EnergyDirectStartLane();
    private static final EnergyStateIoLane ENERGY_STATE_WRITE_LANE =
            new EnergyStateIoLane("EnergyStateWrite");
    private static final EnergyStateIoLane ENERGY_STATE_READ_LANE =
            new EnergyStateIoLane("EnergyStateRead");
    private static final EnergyControlLane ENERGY_CONTROL_SHELL_LANE =
            new EnergyControlLane();
    private static final LatestRunnableLane ENERGY_CONTROL_DIRECT_LANE =
            new LatestRunnableLane("EnergyControlStart");

    private VehicleActuatorBridge() {}

    /** Also fold/unfold the mirrors from the app process (the OEM's environment). */
    public static void dispatchMirror(boolean fold) {
        exec("am start-foreground-service -n " + SERVICE
                + " --es action mirror"
                + " --ez fold " + fold);
        logger.info("mirror fold=" + fold + " also dispatched to app-process VehicleActuatorService");
    }

    /** Retry the persistent OEM auto mirror follow-up setting from the app process. */
    public static void dispatchAutoExternalRearMirrorFollowUp(boolean enabled) {
        exec("am start-foreground-service -n " + SERVICE
                + " --es action mirror_auto_follow_up"
                + " --ez enabled " + enabled);
        logger.info("mirror_auto_follow_up enabled=" + enabled
                + " dispatched to app-process VehicleActuatorService");
    }

    /** Also set HUD brightness level (0..100) from the app process. */
    public static void dispatchHud(int level) {
        if (level < 0 || level > 100) return;
        exec("am start-foreground-service -n " + SERVICE
                + " --es action hud"
                + " --ei level " + level);
        logger.info("hud level=" + level + " also dispatched to app-process VehicleActuatorService");
    }

    /** Set the dedicated HUD power switch (on/off) from the app process — distinct from
     *  brightness. The service writes SET_HUD_SWITCH_SET (1=on/2=off) where it actuates. */
    public static void dispatchHudPower(boolean on) {
        exec("am start-foreground-service -n " + SERVICE
                + " --es action hud_power"
                + " --ez on " + on);
        logger.info("hud_power on=" + on + " also dispatched to app-process VehicleActuatorService");
    }

    /**
     * Retry the five-position AC inlet current setting from the normal app process.
     *
     * <p>The daemon remains responsible for reading the authoritative state after this launch. The
     * service only performs the same idempotent setting write from a real application Context, which
     * covers firmware that accepts Setting-device calls from UID 2000 without actuating them.
     */
    public static void dispatchAcChargeCurrentLimit(int state) {
        if (state < BydDataCollector.AC_CHARGE_CURRENT_6A
                || state > BydDataCollector.AC_CHARGE_CURRENT_MAX) {
            return;
        }
        exec("am start-foreground-service -n " + SERVICE
                + " --es action ac_charge_current_limit"
                + " --es state " + state);
        logger.info("ac_charge_current_limit state=" + state
                + " also dispatched to app-process VehicleActuatorService");
    }

    /**
     * Deliver the app-process powertrain write and wait for ActivityManager to accept the service
     * launch. Energy launches use one process-wide worker with one latest-pending slot, so an
     * uninterruptible process spawn cannot leak threads and a newer request is retained as the
     * corrective launch after an older one unwinds.
     */
    public static boolean dispatchEnergyMode(int mode, long generation) {
        return dispatchEnergyMode(
                mode, generation, ENERGY_GENERATIONS,
                (command, tag, timeoutMs) ->
                        execLoggedBlocking(command, tag, timeoutMs));
    }

    /**
     * Dispatch through {@code am}, then use Android's direct service API if the fixed subprocess
     * lane is unavailable. The direct path has its own one-worker/one-pending bound, so a wedged
     * process spawn cannot starve the newest app-process command.
     */
    public static boolean dispatchEnergyMode(
            android.content.Context context, int mode, long generation) {
        android.content.Context appContext =
                context != null ? context.getApplicationContext() : null;
        if (appContext == null) appContext = context;
        final android.content.Context cancellationContext = appContext;
        boolean launched = dispatchEnergyMode(
                mode,
                generation,
                ENERGY_GENERATIONS,
                (command, tag, timeoutMs) ->
                        execLoggedBlocking(
                                command,
                                tag,
                                timeoutMs,
                                () -> cancelPublishedEnergyRequest(
                                        cancellationContext, generation)));
        if (launched || Thread.currentThread().isInterrupted()
                || !ENERGY_GENERATIONS.isCurrent(generation)) {
            return launched;
        }
        boolean direct = dispatchEnergyModeDirect(context, mode, generation);
        logger.info("energy_mode=" + mode + " generation=" + generation
                + " direct app-process start " + (direct ? "accepted" : "failed"));
        return direct;
    }

    static boolean dispatchStandaloneEnergyMode(
            android.content.Context context, int mode, long generation) {
        return dispatchStandaloneMode(
                context,
                buildStandaloneEnergyCommand(mode, generation),
                "energy_mode=" + mode + " generation=" + generation,
                true);
    }

    static boolean dispatchStandaloneDriveMode(android.content.Context context, int mode) {
        return dispatchStandaloneMode(
                context, buildStandaloneDriveCommand(mode), "drive_mode=" + mode, false);
    }

    private static boolean dispatchStandaloneMode(
            android.content.Context context, String command, String tag, boolean fenced) {
        if (command == null || context == null) return false;
        String classpath;
        try {
            classpath = context.getApplicationInfo().sourceDir;
        } catch (Throwable unavailable) {
            classpath = System.getenv("CLASSPATH");
        }
        if (classpath == null || classpath.trim().isEmpty()) return false;
        LaunchOutcome outcome = execLoggedBlocking(
                command,
                tag + " standalone",
                ENERGY_STANDALONE_TIMEOUT_MS,
                null,
                fenced,
                classpath);
        return outcome == LaunchOutcome.SUCCESS;
    }

    static String buildStandaloneEnergyCommand(int mode, long generation) {
        if (!isUserWritableEnergyMode(mode) || generation <= 0L) return null;
        return buildStandaloneModeCommand("energy", mode) + " " + generation;
    }

    static String buildStandaloneDriveCommand(int mode) {
        if (mode < 1 || mode > 4) return null;
        return buildStandaloneModeCommand("drive", mode);
    }

    private static String buildStandaloneModeCommand(String type, int mode) {
        return "/system/bin/app_process /system/bin --nice-name=overdrive_byd_mode "
                + BydModeCommand.class.getName() + " " + type + " " + mode;
    }

    interface EnergyCommandLauncher {
        LaunchOutcome launch(String command, String tag, long timeoutMs);
    }

    static boolean dispatchEnergyMode(
            int mode,
            long generation,
            EnergyGenerationGate generations,
            EnergyCommandLauncher launcher) {
        if (!isUserWritableEnergyMode(mode)) {
            logger.warn("energy_mode " + mode
                    + " is not a supported user command (valid: 1=EV, 3=HEV)");
            return false;
        }
        if (generation <= 0L) {
            logger.warn("energy_mode generation " + generation + " invalid — not dispatched");
            return false;
        }

        synchronized (generations) {
            if (!generations.claim(generation)) {
                logger.info("energy_mode=" + mode + " generation=" + generation
                        + " ignored as stale before bridge launch");
                return false;
            }
            EnergyDispatch request = new EnergyDispatch(mode, generation);
            String tag = "energy_mode=" + mode + " generation=" + generation;
            LaunchOutcome outcome =
                    launcher.launch(buildEnergyCommand(request), tag, ENERGY_LAUNCH_TIMEOUT_MS);
            if (outcome == LaunchOutcome.FAILURE && !Thread.currentThread().isInterrupted()) {
                // A definite nonzero `am` exit means ActivityManager did not accept the request.
                // Retry once while this generation still owns the serialized bridge.
                logger.warn(tag + ": retrying failed app-process launch");
                outcome =
                        launcher.launch(
                                buildEnergyCommand(request), tag + " retry",
                                ENERGY_LAUNCH_TIMEOUT_MS);
            }
            boolean launched = outcome == LaunchOutcome.SUCCESS;
            logger.info(tag + " app-process launch " + (launched ? "accepted" : "failed"));
            return launched;
        }
    }

    enum LaunchOutcome {
        SUCCESS,
        FAILURE,
        TIMEOUT,
        INTERRUPTED
    }

    static final class EnergyGenerationGate {
        private long latestGeneration;

        synchronized boolean claim(long generation) {
            if (generation <= latestGeneration) return false;
            latestGeneration = generation;
            return true;
        }

        synchronized boolean isCurrent(long generation) {
            return generation > 0L && generation == latestGeneration;
        }
    }

    static String buildEnergyCommand(EnergyDispatch request) {
        // This firmware's `am` rejects --ei/--el and aborts the command. Keep numeric values as
        // strings; the service parses both extras explicitly.
        return "am start-foreground-service -n " + ENERGY_SERVICE
                + " --es action energy_mode"
                + " --es mode " + request.mode
                + " --es request_generation " + request.generation;
    }

    /**
     * Reserve and persist one globally ordered generation. The generation is allocated under the
     * cross-process sidecar lock, so overlapping daemon processes cannot issue the same token.
     */
    public static PublishedEnergyRequest reserveEnergyRequest(
            android.content.Context context, int mode, long proposedGeneration) {
        if (context == null || !isUserWritableEnergyMode(mode)
                || proposedGeneration <= 0L) {
            return null;
        }
        String boot = currentBootToken(context);
        if (boot == null) return null;
        android.content.ContentResolver resolver = context.getContentResolver();
        PublishedEnergyRequest reserved;
        try (RandomAccessFile lockFile = openEnergyStateLockFile();
             FileChannel lockChannel = lockFile.getChannel()) {
            FileLock stateLock = acquireEnergyStateLock(lockChannel, null);
            if (stateLock == null) return null;
              try {
                  long now = android.os.SystemClock.elapsedRealtimeNanos();
                  CoordinateRead coordinate = readCoordinateEnergyMarkerUnlocked(boot);
                  if (coordinate.status == CoordinateStatus.INACCESSIBLE) return null;
                  EnergyAuthorityFence existingFence =
                          readEnergyAuthorityFence(resolver, boot);
                  EnergyAuthorityEpoch existingEpoch =
                          readEnergyAuthorityEpoch(resolver, boot);
                  PublishedEnergyRequest existing = coordinate.marker;
                  if (existingFence != null && existingFence.exact) {
                      if (existing != null
                              && existing.generation > existingFence.generation) {
                          return null;
                      }
                      existing = existingFence.marker;
                      if (!sameEnergyMarker(coordinate.marker, existing)) {
                          try {
                              writeCoordinateEnergyMarkerAtomic(boot, existing);
                          } catch (Throwable mirrorFailed) {
                              logger.warn("energy reservation sidecar repair failed: "
                                      + mirrorFailed.getMessage());
                          }
                      }
                  } else if (coordinate.status == CoordinateStatus.UNREADABLE) {
                      return null;
                  }
                  if (existing != null
                          && !isPlausibleEnergyGenerationForMutation(
                          existing.generation, now)) return null;
                  if (existingFence != null
                          && !isPlausibleEnergyGenerationForMutation(
                                  existingFence.generation, now)) {
                      return null;
                  }
                  if (existingEpoch != null
                          && !isPlausibleEnergyGenerationForMutation(
                                  existingEpoch.generation, now)) {
                      return null;
                  }
                long generation = Math.max(now, proposedGeneration);
                if (existing != null && existing.generation >= generation) {
                    if (existing.generation == Long.MAX_VALUE) return null;
                    generation = existing.generation + 1L;
                }
                  if (existingFence != null && existingFence.generation >= generation) {
                      if (existingFence.generation == Long.MAX_VALUE) return null;
                      generation = existingFence.generation + 1L;
                  }
                  if (existingEpoch != null && existingEpoch.generation >= generation) {
                      if (existingEpoch.generation == Long.MAX_VALUE) return null;
                      generation = existingEpoch.generation + 1L;
                  }
                if (!isPlausibleEnergyGeneration(generation, now)) return null;
                reserved = PublishedEnergyRequest.desired(generation, mode);
                if (!installEnergyAuthorityFence(
                        resolver, boot, reserved)) {
                    return null;
                }
                try {
                    writeCoordinateEnergyMarkerAtomic(boot, reserved);
                } catch (Throwable mirrorFailed) {
                    logger.warn("energy reservation sidecar mirror failed: "
                            + mirrorFailed.getMessage());
                }
            } finally {
                stateLock.release();
            }
        } catch (Throwable failed) {
            logger.warn("energy generation reservation failed: " + failed.getMessage());
            return null;
        }
        if (!publishEnergyRequest(context, mode, reserved.generation)) {
            cancelPublishedEnergyRequest(context, reserved.generation);
            return null;
        }
        return reserved;
    }

    /**
     * Confirm an already-reserved desired marker through the app-visible Settings mirror. A
     * sidecar-only match is not sufficient because an enforcing app domain may not be permitted to
     * read {@code /data/local/tmp}.
     */
    public static boolean publishEnergyRequest(
            android.content.Context context, int mode, long generation) {
        if (context == null || !isUserWritableEnergyMode(mode)
                || generation <= 0L) {
            return false;
        }
        StateIoTask task = ENERGY_STATE_WRITE_LANE.submitMutation(
                generation,
                false,
                stateTask -> Boolean.valueOf(
                        writePublishedEnergyRequest(context, mode, generation, stateTask)));
        Object result = task.await(ENERGY_STATE_IO_TIMEOUT_MS);
        if (result == null && task.isCompleted()) result = task.resultNow();
        boolean published = Boolean.TRUE.equals(result);
        if (!published) {
            logger.warn("energy_mode=" + mode + " generation=" + generation
                    + " latest-state marker was not confirmed");
        }
        return published;
    }

    /**
     * Publish a same-generation tombstone so a launch already accepted by ActivityManager cannot
     * actuate after its daemon caller was interrupted or superseded.
     */
    public static boolean cancelPublishedEnergyRequest(
            android.content.Context context, long generation) {
        if (context == null || generation <= 0L) return false;
        String boot = currentBootToken(context);
        MutationResult commitResult = boot != null
                ? commitCancellationMarkerResult(context, boot, generation)
                : MutationResult.REJECTED;
        boolean committed = commitResult == MutationResult.UPDATED;
        StateIoTask mirror = ENERGY_STATE_WRITE_LANE.submitMutation(
                generation,
                true,
                stateTask -> Boolean.valueOf(
                        mirrorCurrentEnergyMarker(context, boot, generation, true)));
        Object result = mirror.await(ENERGY_STATE_IO_TIMEOUT_MS);
        if (result == null && mirror.isCompleted()) result = mirror.resultNow();
        boolean appReadable = Boolean.TRUE.equals(result);
        dispatchEnergyControl(context, "energy_mode_cancel", generation);
        if (!committed || !appReadable) {
            logger.warn("energy generation=" + generation
                    + " cancellation was not synchronously confirmed"
                    + " (sidecar=" + committed + ", appReadable=" + appReadable + ")");
        }
        return committed && appReadable;
    }

    /**
     * Persist a cancellation received by the app service without dispatching another service
     * control intent. When the authoritative sidecar is inaccessible to the app domain, update the
     * already-confirmed Settings record and let the daemon reconcile that same-generation
     * tombstone back into the sidecar.
     */
    public static boolean persistEnergyCancellation(
            android.content.Context context, long generation) {
        if (context == null || generation <= 0L) return false;
        String boot = currentBootToken(context);
        if (boot == null) return false;
        MutationResult commitResult =
                commitCancellationMarkerResult(context, boot, generation);
        StateIoTask task = ENERGY_STATE_WRITE_LANE.submitMutation(
                generation,
                true,
                stateTask -> Boolean.valueOf(
                        commitResult == MutationResult.UPDATED
                        ? mirrorCurrentEnergyMarker(context, boot, generation, true)
                        : commitResult == MutationResult.INACCESSIBLE
                        && authorityFenceMatches(
                                readEnergyAuthorityFence(
                                        context.getContentResolver(), boot),
                                generation,
                                -1,
                                true)
                        && mutateConfirmedSettingsMarker(
                                context, boot, generation, MarkerMutation.CANCEL)));
        Object result = task.await(ENERGY_STATE_IO_TIMEOUT_MS);
        if (result == null && task.isCompleted()) result = task.resultNow();
        return Boolean.TRUE.equals(result);
    }

    /** Persist rollback metadata before a HAL setter is allowed to start. */
    public static boolean beginEnergyActuation(
            android.content.Context context,
            long generation,
            int mode,
            int previousMode) {
        return beginEnergyActuation(
                context, generation, mode, previousMode, ENERGY_ACTUATOR_DAEMON);
    }

    /** Persist rollback metadata and the process class that owns any later compensation. */
    public static boolean beginEnergyActuation(
            android.content.Context context,
            long generation,
            int mode,
            int previousMode,
            int actuatorOwner) {
        if (context == null || generation <= 0L || mode < 1 || mode > 5
                || previousMode < 1 || previousMode > 5
                || !isValidActuatorOwner(actuatorOwner)) {
            return false;
        }
        return mutateEnergyActuation(
                context,
                generation,
                mode,
                previousMode,
                actuatorOwner,
                MarkerMutation.BEGIN);
    }

    /** Mark physical confirmation so service recreation does not replay completed work. */
    public static boolean completeEnergyActuation(
            android.content.Context context, long generation, int mode) {
        if (context == null || generation <= 0L || mode < 1 || mode > 5) return false;
        return mutateEnergyActuation(
                context,
                generation,
                mode,
                -1,
                ENERGY_ACTUATOR_NONE,
                MarkerMutation.COMPLETE);
    }

    /** Mark a cancellation rollback physically complete. */
    public static boolean completeEnergyRollback(
            android.content.Context context, long generation, int rollbackMode) {
        if (context == null || generation <= 0L
                || rollbackMode < 1 || rollbackMode > 5) {
            return false;
        }
        return mutateEnergyActuation(
                context,
                generation,
                rollbackMode,
                rollbackMode,
                ENERGY_ACTUATOR_NONE,
                MarkerMutation.ROLLBACK);
    }

    /**
     * Durably consume one shared rollback attempt before a compensation setter is invoked.
     *
     * @return the one-based attempt number, {@code 0} when the budget is exhausted or owned by
     * another process class, and {@code -1} when durable state is temporarily unavailable.
     */
    public static int claimEnergyRollbackAttempt(
            android.content.Context context,
            long generation,
            int rollbackMode,
            int actuatorOwner) {
        if (context == null || generation <= 0L
                || rollbackMode < 1 || rollbackMode > 5
                || !isValidActuatorOwner(actuatorOwner)) {
            return -1;
        }
        PublishedEnergyRead beforeRead = readPublishedEnergyState(context);
        PublishedEnergyRequest before = beforeRead.request;
        if (beforeRead.status != EnergyReadStatus.VALID || before == null) return -1;
        if (!before.cancelled || !before.rollbackPending
                || before.generation != generation
                || before.rollbackMode != rollbackMode
                || before.rollbackOwner != actuatorOwner) {
            return 0;
        }
        if (before.compensationAttempts >= MAX_ENERGY_COMPENSATION_ATTEMPTS) return 0;
        int expectedAttempt = before.compensationAttempts + 1;
        if (!mutateEnergyActuation(
                context,
                generation,
                rollbackMode,
                -1,
                actuatorOwner,
                MarkerMutation.CLAIM_ROLLBACK)) {
            return -1;
        }
        PublishedEnergyRead afterRead = readPublishedEnergyState(context);
        PublishedEnergyRequest after = afterRead.request;
        if (afterRead.status != EnergyReadStatus.VALID || after == null
                || after.generation != generation
                || !after.cancelled
                || after.rollbackMode != rollbackMode
                || after.rollbackOwner != actuatorOwner) {
            return -1;
        }
        return after.compensationAttempts >= expectedAttempt
                ? after.compensationAttempts : -1;
    }

    /**
     * Read the latest boot-scoped request while preserving absence, policy-denied sidecar access,
     * and malformed/transiently unreadable state as distinct outcomes.
     */
    public static PublishedEnergyRead readPublishedEnergyState(
            android.content.Context context) {
        if (context == null) return PublishedEnergyRead.unreadable();
        StateIoTask task = ENERGY_STATE_READ_LANE.submit(
                stateTask -> readPublishedEnergyRequestDirect(context));
        Object result = task.await(ENERGY_STATE_IO_TIMEOUT_MS);
        if (result == null && !task.isCompleted()) {
            ENERGY_STATE_READ_LANE.cancel(task);
            result = task.resultNow();
        }
        return result instanceof PublishedEnergyRead
                ? (PublishedEnergyRead) result : PublishedEnergyRead.unreadable();
    }

    static boolean isPublishedEnergyRequestCurrent(
            android.content.Context context, int mode, long generation) {
        PublishedEnergyRead read = readPublishedEnergyState(context);
        return read.status == EnergyReadStatus.VALID
                && matchesPublishedEnergyRequest(read.request, mode, generation);
    }

    static boolean matchesPublishedEnergyRequest(
            PublishedEnergyRequest marker, int mode, long generation) {
        return marker != null
                && !marker.cancelled
                && !marker.pending
                && marker.mode == mode
                && marker.generation == generation;
    }

    /** Compatibility view for callers that only consume a valid marker. */
    public static PublishedEnergyRequest readPublishedEnergyRequest(
            android.content.Context context) {
        PublishedEnergyRead read = readPublishedEnergyState(context);
        return read.status == EnergyReadStatus.VALID ? read.request : null;
    }

    /** Fail-closed cross-process authority check used at every external invocation gate. */
    public static boolean isEnergyRequestCurrent(
            android.content.Context context, long generation, int mode) {
        PublishedEnergyRead read = readPublishedEnergyState(context);
        PublishedEnergyRequest marker = read.request;
        return read.status == EnergyReadStatus.VALID
                && marker != null
                && !marker.cancelled
                && !marker.pending
                && marker.generation == generation
                && marker.mode == mode;
    }

    private static boolean writePublishedEnergyRequest(
            android.content.Context context,
            int mode,
            long generation,
            StateIoTask task) {
        try {
            String boot = currentBootToken(context);
            if (boot == null) return false;
            CoordinateRead coordinated = readCoordinatedEnergyMarker(boot);
            PublishedEnergyRequest authoritative = coordinated.marker;
            if (!sameEnergyMarker(
                    authoritative, PublishedEnergyRequest.desired(generation, mode))) {
                return false;
            }
            if (!isPlausibleEnergyGeneration(
                    generation, android.os.SystemClock.elapsedRealtimeNanos())) {
                return false;
            }
            android.content.ContentResolver resolver = context.getContentResolver();
            PublishedEnergyRequest confirmed =
                    mirrorCoordinatedEnergyMarker(resolver, boot);
            return confirmed != null
                    && confirmed.generation == generation
                    && confirmed.mode == mode
                    && !confirmed.cancelled;
        } catch (Throwable t) {
            logger.warn("energy latest-state publish failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean mirrorCurrentEnergyMarker(
            android.content.Context context,
            String boot,
            long generation,
            boolean cancellation) {
        if (boot == null) return false;
        try {
            android.content.ContentResolver resolver = context.getContentResolver();
            if (cancellation) {
                reconcileCancellationMetadataFromSettings(
                        resolver, boot, generation);
            }
            PublishedEnergyRequest confirmed =
                    mirrorCoordinatedEnergyMarker(resolver, boot);
            return confirmed != null
                    && (confirmed.generation > generation
                    || (confirmed.generation == generation
                    && confirmed.cancelled == cancellation));
        } catch (Throwable t) {
            logger.warn("energy latest-state mirror failed: " + t.getMessage());
            return false;
        }
    }

    private static void reconcileCancellationMetadataFromSettings(
            android.content.ContentResolver resolver,
            String boot,
            long generation) {
        PublishedEnergyRequest mirror =
                readConfirmedSettingsMarkerPayload(resolver, boot);
        if (mirror == null || mirror.generation != generation) return;
        try (RandomAccessFile lockFile = openEnergyStateLockFile();
             FileChannel lockChannel = lockFile.getChannel()) {
            FileLock stateLock = acquireEnergyStateLock(lockChannel, null);
            if (stateLock == null) return;
            try {
                CoordinateRead read = readCoordinateEnergyMarkerUnlocked(boot);
                PublishedEnergyRequest coordinate = read.marker;
                if (read.status != CoordinateStatus.VALID
                        || coordinate == null
                        || !coordinate.cancelled
                        || coordinate.generation != generation
                        || coordinate.requestedMode != mirror.requestedMode) {
                    return;
                }
                PublishedEnergyRequest merged =
                        coordinate.withCancellationMetadataFrom(mirror);
                if (!sameEnergyMarker(coordinate, merged)) {
                    writeCoordinateEnergyMarkerAtomic(boot, merged);
                }
            } finally {
                stateLock.release();
            }
        } catch (Throwable failed) {
            logger.warn("energy cancellation metadata reconciliation failed: "
                    + failed.getMessage());
        }
    }

    private static MutationResult commitCancellationMarkerResult(
            android.content.Context context, String boot, long generation) {
        try (RandomAccessFile lockFile = openEnergyStateLockFile();
             FileChannel lockChannel = lockFile.getChannel()) {
            FileLock stateLock = acquireEnergyStateLock(lockChannel, null);
            if (stateLock == null) return MutationResult.REJECTED;
            try {
                CoordinateRead coordinate = readCoordinateEnergyMarkerUnlocked(boot);
                if (coordinate.status == CoordinateStatus.INACCESSIBLE) {
                    return MutationResult.INACCESSIBLE;
                }
                  PublishedEnergyRequest existing = coordinate.marker;
                  EnergyAuthorityFence authority = readEnergyAuthorityFence(
                          context.getContentResolver(), boot);
                  EnergyAuthorityEpoch authorityEpoch = readEnergyAuthorityEpoch(
                          context.getContentResolver(), boot);
                  if (authorityEpoch != null
                          && authorityEpoch.generation > generation) {
                      return MutationResult.UPDATED;
                  }
                  if (coordinate.status == CoordinateStatus.UNREADABLE
                        && (authority == null || !authority.exact)) {
                    return MutationResult.REJECTED;
                }
                if (authority != null && authority.exact) {
                    if (authority.generation > generation) {
                        return MutationResult.UPDATED;
                    }
                    if (authority.generation == generation) {
                        if (existing != null
                                && existing.generation > authority.generation) {
                            return MutationResult.REJECTED;
                        }
                        existing = authority.marker;
                        if (!sameEnergyMarker(coordinate.marker, existing)) {
                            try {
                                writeCoordinateEnergyMarkerAtomic(boot, existing);
                            } catch (Throwable mirrorFailed) {
                                logger.warn("energy cancellation sidecar repair failed: "
                                        + mirrorFailed.getMessage());
                            }
                        }
                    } else if (existing != null
                            && existing.generation > authority.generation) {
                        return MutationResult.REJECTED;
                    }
                }
                if (existing != null && !isPlausibleEnergyGenerationForMutation(
                        existing.generation, android.os.SystemClock.elapsedRealtimeNanos())) {
                    return MutationResult.REJECTED;
                }
                if (existing != null && existing.generation > generation) {
                    return MutationResult.UPDATED;
                }
                if (existing == null || existing.generation != generation) {
                    return MutationResult.REJECTED;
                }
                PublishedEnergyRequest cancelled = existing.asCancelled();
                if (!installEnergyAuthorityFence(
                        context.getContentResolver(),
                        boot,
                        cancelled)) {
                    return MutationResult.REJECTED;
                }
                if (existing.cancelled) return MutationResult.UPDATED;
                try {
                    writeCoordinateEnergyMarkerAtomic(boot, cancelled);
                } catch (Throwable mirrorFailed) {
                    logger.warn("energy cancellation sidecar mirror failed: "
                            + mirrorFailed.getMessage());
                }
                return isExactEnergyAuthorityFence(
                        context.getContentResolver(), boot, cancelled)
                        ? MutationResult.UPDATED : MutationResult.REJECTED;
            } finally {
                stateLock.release();
            }
        } catch (Throwable failed) {
            logger.warn("energy cancellation sidecar failed: " + failed.getMessage());
            return isAccessDenied(failed)
                    ? MutationResult.INACCESSIBLE : MutationResult.REJECTED;
        }
    }

    private enum MarkerMutation {
        BEGIN,
        COMPLETE,
        CANCEL,
        ROLLBACK,
        CLAIM_ROLLBACK
    }

    private static boolean mutateEnergyActuation(
            android.content.Context context,
            long generation,
            int mode,
            int previousMode,
            int actuatorOwner,
            MarkerMutation mutation) {
        String boot = currentBootToken(context);
        if (boot == null) return false;
        MutationResult coordinateResult =
                mutateCoordinateEnergyMarker(
                        context,
                        boot,
                        generation,
                        mode,
                        previousMode,
                        actuatorOwner,
                        mutation);
        if (coordinateResult == MutationResult.UPDATED) return true;
        if (coordinateResult != MutationResult.INACCESSIBLE) return false;
        StateIoTask task = ENERGY_STATE_WRITE_LANE.submitMutation(
                generation,
                mutation == MarkerMutation.CANCEL,
                stateTask -> Boolean.valueOf(
                        mutateConfirmedSettingsMarker(
                                context, boot, generation, mutation,
                                mode, previousMode, actuatorOwner)));
        Object result = task.await(ENERGY_STATE_IO_TIMEOUT_MS);
        if (result == null && task.isCompleted()) result = task.resultNow();
        return Boolean.TRUE.equals(result);
    }

    private static MutationResult mutateCoordinateEnergyMarker(
            android.content.Context context,
            String boot,
            long generation,
            int mode,
            int previousMode,
            int actuatorOwner,
            MarkerMutation mutation) {
        try (RandomAccessFile lockFile = openEnergyStateLockFile();
             FileChannel lockChannel = lockFile.getChannel()) {
            FileLock stateLock = acquireEnergyStateLock(lockChannel, null);
            if (stateLock == null) return MutationResult.REJECTED;
            try {
                CoordinateRead read = readCoordinateEnergyMarkerUnlocked(boot);
                  if (read.status == CoordinateStatus.INACCESSIBLE) {
                      return MutationResult.INACCESSIBLE;
                  }
                  android.content.ContentResolver resolver = context.getContentResolver();
                  EnergyAuthorityFence fence = readEnergyAuthorityFence(
                          resolver, boot);
                  PublishedEnergyRequest current;
                  if (fence != null && fence.exact) {
                      if (read.marker != null
                              && read.marker.generation > fence.generation) {
                          return MutationResult.REJECTED;
                      }
                      current = fence.marker;
                      if (!sameEnergyMarker(read.marker, current)) {
                          try {
                              writeCoordinateEnergyMarkerAtomic(boot, current);
                          } catch (Throwable mirrorFailed) {
                              logger.warn("energy sidecar repair failed: "
                                      + mirrorFailed.getMessage());
                          }
                      }
                  } else {
                      if (read.status != CoordinateStatus.VALID
                              || read.marker == null
                              || !authorityFenceMatches(fence, read.marker)) {
                          return MutationResult.REJECTED;
                      }
                      current = read.marker;
                      PublishedEnergyRequest mirror =
                              readConfirmedSettingsMarker(resolver, boot);
                      if (mirror == null || !sameEnergyAuthority(current, mirror)) {
                          return MutationResult.REJECTED;
                      }
                      PublishedEnergyRequest merged =
                              mergeEnergyMarkers(current, mirror);
                      if (mirror.revision > current.revision
                              && sameEnergyMarker(merged, current)
                              && !sameEnergyMarker(mirror, current)) {
                          return MutationResult.REJECTED;
                      }
                      if (!sameEnergyMarker(current, merged)) {
                          writeCoordinateEnergyMarkerAtomic(boot, merged);
                          current = merged;
                      }
                  }
                PublishedEnergyRequest updated =
                        applyMarkerMutation(
                                current,
                                generation,
                                mode,
                                previousMode,
                                actuatorOwner,
                                mutation);
                if (updated == null) return MutationResult.REJECTED;
                if (sameEnergyMarker(current, updated)) return MutationResult.UPDATED;

                // Settings is the app-visible commit point for metadata. Commit and confirm it
                // before advancing the sidecar so a process denied /data/local/tmp either sees the
                // new complete record or fails closed on the state/confirmation mismatch.
                if (!installEnergyAuthorityFence(resolver, boot, updated)) {
                    return MutationResult.REJECTED;
                }
                mirrorConfirmedSettingsMarkerBestEffort(resolver, boot, updated);
                try {
                    writeCoordinateEnergyMarkerAtomic(boot, updated);
                } catch (Throwable mirrorFailed) {
                    logger.warn("energy sidecar mirror update failed: "
                            + mirrorFailed.getMessage());
                }
                if (!isExactEnergyAuthorityFence(resolver, boot, updated)) {
                    return MutationResult.REJECTED;
                }
                return MutationResult.UPDATED;
            } finally {
                stateLock.release();
            }
        } catch (Throwable failed) {
            return isAccessDenied(failed)
                    ? MutationResult.INACCESSIBLE : MutationResult.REJECTED;
        }
    }

    private static boolean mutateConfirmedSettingsMarker(
            android.content.Context context,
            String boot,
            long generation,
            MarkerMutation mutation) {
        return mutateConfirmedSettingsMarker(
                context,
                boot,
                generation,
                -1,
                -1,
                ENERGY_ACTUATOR_NONE,
                mutation);
    }

    private static boolean mutateConfirmedSettingsMarker(
            android.content.Context context,
            String boot,
            long generation,
            MarkerMutation mutation,
            int mode,
            int previousMode,
            int actuatorOwner) {
        return mutateConfirmedSettingsMarker(
                context,
                boot,
                generation,
                mode,
                previousMode,
                actuatorOwner,
                mutation);
    }

    private static boolean mutateConfirmedSettingsMarker(
            android.content.Context context,
            String boot,
            long generation,
            int mode,
            int previousMode,
            int actuatorOwner,
            MarkerMutation mutation) {
        try {
            android.content.ContentResolver resolver = context.getContentResolver();
            PublishedEnergyRequest current =
                    readAuthoritativeSettingsMarker(resolver, boot);
            if (current == null) return false;
            if (current.generation > generation) {
                return mutation == MarkerMutation.CANCEL;
            }
            PublishedEnergyRequest updated =
                    applyMarkerMutation(
                            current,
                            generation,
                            mode,
                            previousMode,
                            actuatorOwner,
                            mutation);
            if (updated == null) return false;
            if (sameEnergyMarker(current, updated)) return true;
            if (!installEnergyAuthorityFence(resolver, boot, updated)) return false;
            mirrorConfirmedSettingsMarkerBestEffort(resolver, boot, updated);
            return isExactEnergyAuthorityFence(resolver, boot, updated);
        } catch (Throwable failed) {
            logger.warn("energy Settings mutation failed: " + failed.getMessage());
            return false;
        }
    }

    private static PublishedEnergyRequest applyMarkerMutation(
            PublishedEnergyRequest marker,
            long generation,
            int mode,
            int previousMode,
            int actuatorOwner,
            MarkerMutation mutation) {
        if (marker == null || marker.generation != generation) return null;
        if (marker.revision == Long.MAX_VALUE) {
            return mutation == MarkerMutation.CANCEL && marker.cancelled
                    ? marker : null;
        }
        switch (mutation) {
            case CANCEL:
                return marker.asCancelled();
            case BEGIN:
                if (marker.cancelled || marker.pending || marker.mode != mode) return null;
                if (!isEnergyActuatorOwnershipAvailable(marker, actuatorOwner)) {
                    // One process class must own every setter that can outlive this generation.
                    // Otherwise the recorded owner can finish rollback while the other process
                    // still has a blocked setter that later restores the cancelled mode.
                    return null;
                }
                if (marker.rollbackMode >= 1 && marker.rollbackMode <= 5
                        && marker.rollbackMode != previousMode) {
                    // A previously-started or applied request may need to be reasserted after a
                    // stale, uninterruptible HAL call lands late. Preserve its original rollback
                    // point instead of rejecting the corrective write or replacing compensation
                    // metadata with the stale physical mode.
                    return marker.actuationStarted || marker.applied ? marker : null;
                }
                int owner = marker.actuationStarted
                        ? marker.rollbackOwner : actuatorOwner;
                if (!isValidActuatorOwner(owner)) return null;
                return marker.withActuation(
                        previousMode,
                        true,
                        marker.applied,
                        false,
                        owner,
                        marker.compensationAttempts);
            case COMPLETE:
                if (marker.cancelled || marker.pending || marker.mode != mode) return null;
                // Reaching an already-selected mode is a valid no-op confirmation. Do not invent
                // BEGIN metadata or an invalid rollback=-1/started=true combination.
                return marker.withActuation(
                        marker.rollbackMode,
                        marker.actuationStarted,
                        true,
                        false,
                        marker.rollbackOwner,
                        marker.compensationAttempts);
            case ROLLBACK:
                if (!marker.cancelled || !marker.rollbackPending
                        || marker.rollbackMode != mode) {
                    return null;
                }
                return marker.withActuation(
                        marker.rollbackMode,
                        marker.actuationStarted,
                        marker.applied,
                        false,
                        marker.rollbackOwner,
                        marker.compensationAttempts);
            case CLAIM_ROLLBACK:
                if (!marker.cancelled || !marker.rollbackPending
                        || marker.rollbackMode != mode
                        || marker.rollbackOwner != actuatorOwner
                        || marker.compensationAttempts
                        >= MAX_ENERGY_COMPENSATION_ATTEMPTS) {
                    return null;
                }
                return marker.withActuation(
                        marker.rollbackMode,
                        marker.actuationStarted,
                        marker.applied,
                        true,
                        marker.rollbackOwner,
                        marker.compensationAttempts + 1);
            default:
                return null;
        }
    }

    private enum MutationResult {
        UPDATED,
        INACCESSIBLE,
        REJECTED
    }

    private static RandomAccessFile openEnergyStateLockFile() throws Exception {
        java.io.File file = new java.io.File(ENERGY_STATE_LOCK_FILE);
        RandomAccessFile lockFile = new RandomAccessFile(file, "rw");
        file.setReadable(true, false);
        file.setWritable(true, false);
        return lockFile;
    }

    private static FileLock acquireEnergyStateLock(
            FileChannel channel, StateIoTask task) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(ENERGY_STATE_LOCK_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (task != null && task.isCancelled()) return null;
            try {
                FileLock acquired = channel.tryLock();
                if (acquired != null) return acquired;
            } catch (java.nio.channels.OverlappingFileLockException busy) {
                // Another state operation in this process owns it.
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static CoordinateRead readCoordinateEnergyMarkerUnlocked(String boot) {
        java.io.File stateFile = new java.io.File(ENERGY_STATE_COORDINATE_FILE);
        try {
            java.nio.file.Files.readAttributes(
                    stateFile.toPath(),
                    java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException missing) {
            return CoordinateRead.missing();
        } catch (Throwable unreadable) {
            return isAccessDenied(unreadable)
                    ? CoordinateRead.inaccessible() : CoordinateRead.unreadable();
        }
        try (RandomAccessFile state = new RandomAccessFile(stateFile, "r")) {
            long length = state.length();
            if (length <= 0L || length > 512L) return CoordinateRead.unreadable();
            byte[] raw = new byte[(int) length];
            state.readFully(raw);
            String encoded =
                    new String(raw, java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (isOtherBootEnergyMarker(encoded, boot)) return CoordinateRead.otherBoot();
            PublishedEnergyRequest marker = parsePublishedEnergyRequest(encoded, boot);
            return marker != null ? CoordinateRead.valid(marker) : CoordinateRead.unreadable();
        } catch (Throwable unavailable) {
            return isAccessDenied(unavailable)
                    ? CoordinateRead.inaccessible() : CoordinateRead.unreadable();
        }
    }

    private static void writeCoordinateEnergyMarkerAtomic(
            String boot, PublishedEnergyRequest marker) throws Exception {
        String encoded = encodeEnergyMarker(boot, marker);
        java.io.File stateFile = new java.io.File(ENERGY_STATE_COORDINATE_FILE);
        java.io.File temporary = new java.io.File(
                stateFile.getParentFile(),
                stateFile.getName() + "." + android.os.Process.myPid() + "."
                        + Thread.currentThread().getId() + ".tmp");
        try {
            try (RandomAccessFile state = new RandomAccessFile(temporary, "rw")) {
                state.setLength(0L);
                state.write(encoded.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                state.getFD().sync();
            }
            temporary.setReadable(true, false);
            temporary.setWritable(true, false);
            java.nio.file.Files.move(
                    temporary.toPath(),
                    stateFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                logger.debug("could not remove stale energy marker temp " + temporary);
            }
        }
        stateFile.setReadable(true, false);
        stateFile.setWritable(true, false);
        forceEnergyStateDirectory(stateFile.getParentFile());
    }

    private static final class EnergyAuthorityFence {
        final long generation;
        final int mode;
        final boolean cancelled;
        final PublishedEnergyRequest marker;
        final boolean exact;

        EnergyAuthorityFence(long generation, int mode, boolean cancelled) {
            this.generation = generation;
            this.mode = mode;
            this.cancelled = cancelled;
            this.marker = null;
            this.exact = false;
        }

        EnergyAuthorityFence(PublishedEnergyRequest marker) {
            this.generation = marker.generation;
            this.mode = marker.requestedMode;
            this.cancelled = marker.cancelled;
            this.marker = marker;
            this.exact = true;
        }
    }

    private static String encodeEnergyAuthorityFence(
            String boot, PublishedEnergyRequest marker) {
        return "v2|" + encodeEnergyMarker(boot, marker);
    }

    private static final class EnergyAuthorityEpoch {
        final long generation;
        final int mode;
        final boolean cancelled;

        EnergyAuthorityEpoch(long generation, int mode, boolean cancelled) {
            this.generation = generation;
            this.mode = mode;
            this.cancelled = cancelled;
        }
    }

    private static String encodeEnergyAuthorityEpoch(
            String boot, PublishedEnergyRequest marker) {
        return "v1:" + boot + ":" + marker.generation + ":"
                + marker.requestedMode + ":" + (marker.cancelled ? 1 : 0);
    }

    private static EnergyAuthorityEpoch readEnergyAuthorityEpoch(
            android.content.ContentResolver resolver, String boot) {
        if (resolver == null || boot == null) return null;
        try {
            String encoded = readGlobalSetting(
                    resolver, ENERGY_AUTHORITY_EPOCH_SETTING);
            if (encoded == null) return null;
            String[] parts = encoded.split(":", -1);
            if (parts.length != 5 || !"v1".equals(parts[0])
                    || !bootTokensMatch(boot, parts[1])) {
                return null;
            }
            long generation = Long.parseLong(parts[2]);
            int mode = Integer.parseInt(parts[3]);
            boolean cancelled = parseFlag(parts[4]);
            if (generation <= 0L || mode < 1 || mode > 5) return null;
            return new EnergyAuthorityEpoch(generation, mode, cancelled);
        } catch (Throwable malformed) {
            return null;
        }
    }

    private static boolean hasInvalidCurrentBootEnergyAuthorityEpoch(
            android.content.ContentResolver resolver, String boot) {
        if (resolver == null || boot == null) return true;
        try {
            String encoded = readGlobalSetting(
                    resolver, ENERGY_AUTHORITY_EPOCH_SETTING);
            if (encoded == null) return false;
            String[] parts = encoded.split(":", -1);
            if (parts.length == 5 && "v1".equals(parts[0])
                    && !bootTokensMatch(boot, parts[1])) {
                return false;
            }
            return readEnergyAuthorityEpoch(resolver, boot) == null;
        } catch (Throwable unavailable) {
            return true;
        }
    }

    private static boolean authorityEpochMatches(
            EnergyAuthorityEpoch epoch, EnergyAuthorityFence fence) {
        return epoch != null && fence != null
                && epoch.generation == fence.generation
                && epoch.mode == fence.mode
                && epoch.cancelled == fence.cancelled;
    }

    private static boolean authorityEpochMatches(
            EnergyAuthorityEpoch epoch, PublishedEnergyRequest marker) {
        return epoch != null && marker != null
                && epoch.generation == marker.generation
                && epoch.mode == marker.requestedMode
                && epoch.cancelled == marker.cancelled;
    }

    private static boolean ensureEnergyAuthorityEpoch(
            android.content.ContentResolver resolver,
            String boot,
            PublishedEnergyRequest marker) {
        try {
            EnergyAuthorityEpoch current =
                    readEnergyAuthorityEpoch(resolver, boot);
            if (current == null
                    && hasInvalidCurrentBootEnergyAuthorityEpoch(resolver, boot)) {
                return false;
            }
            if (current != null) {
                if (current.generation > marker.generation
                        || current.generation == marker.generation
                        && (current.mode != marker.requestedMode
                        || current.cancelled && !marker.cancelled)) {
                    return false;
                }
                if (authorityEpochMatches(current, marker)) return true;
            }
            if (!writeGlobalSetting(
                    resolver,
                    ENERGY_AUTHORITY_EPOCH_SETTING,
                    encodeEnergyAuthorityEpoch(boot, marker))) {
                return false;
            }
            return authorityEpochMatches(
                    readEnergyAuthorityEpoch(resolver, boot), marker);
        } catch (Throwable failed) {
            logger.warn("energy authority epoch update failed: " + failed.getMessage());
            return false;
        }
    }

    private static EnergyAuthorityFence readEnergyAuthorityFencePayload(
            android.content.ContentResolver resolver, String boot) {
        if (resolver == null || boot == null) return null;
        try {
            String encoded = readGlobalSetting(
                    resolver, ENERGY_AUTHORITY_FENCE_SETTING);
            if (encoded == null) return null;
            EnergyAuthorityFence fence;
            if (encoded.startsWith("v2|")) {
                PublishedEnergyRequest marker =
                        parsePublishedEnergyRequest(encoded.substring(3), boot);
                if (marker == null) return null;
                fence = new EnergyAuthorityFence(marker);
            } else {
                String[] parts = encoded.split(":", -1);
                if (parts.length != 5 || !"v1".equals(parts[0])
                        || !bootTokensMatch(boot, parts[1])) {
                    return null;
                }
                long generation = Long.parseLong(parts[2]);
                int mode = Integer.parseInt(parts[3]);
                boolean cancelled = parseFlag(parts[4]);
                if (generation <= 0L || mode < 1 || mode > 5) return null;
                fence = new EnergyAuthorityFence(generation, mode, cancelled);
            }
            return fence;
        } catch (Throwable malformed) {
            return null;
        }
    }

    private static EnergyAuthorityFence readEnergyAuthorityFence(
            android.content.ContentResolver resolver, String boot) {
        EnergyAuthorityFence fence =
                readEnergyAuthorityFencePayload(resolver, boot);
        if (fence == null) return null;
        EnergyAuthorityEpoch epoch =
                readEnergyAuthorityEpoch(resolver, boot);
        if (epoch != null) {
            return authorityEpochMatches(epoch, fence) ? fence : null;
        }
        return hasInvalidCurrentBootEnergyAuthorityEpoch(resolver, boot)
                ? null : fence;
    }

    private static PublishedEnergyRequest readBestEnergyAuthorityPayload(
            android.content.ContentResolver resolver, String boot) {
        EnergyAuthorityFence rawFence =
                readEnergyAuthorityFencePayload(resolver, boot);
        if (rawFence != null && rawFence.exact) return rawFence.marker;
        return readConfirmedSettingsMarkerPayload(resolver, boot);
    }

    private static boolean hasInvalidCurrentBootEnergyAuthorityFence(
            android.content.ContentResolver resolver, String boot) {
        if (resolver == null || boot == null) return true;
        try {
            String encoded = readGlobalSetting(
                    resolver, ENERGY_AUTHORITY_FENCE_SETTING);
            if (encoded == null) return false;
            if (encoded.startsWith("v2|")) {
                String marker = encoded.substring(3);
                if (parsePublishedEnergyRequest(marker, boot) != null) return false;
                return !isOtherBootEnergyMarker(marker, boot);
            }
            String[] parts = encoded.split(":", -1);
            if (parts.length == 5 && "v1".equals(parts[0])
                    && !bootTokensMatch(boot, parts[1])) {
                return false;
            }
            return readEnergyAuthorityFencePayload(resolver, boot) == null;
        } catch (Throwable unavailable) {
            return true;
        }
    }

    private static boolean installEnergyAuthorityFence(
            android.content.ContentResolver resolver,
            String boot,
            PublishedEnergyRequest marker) {
        if (resolver == null || boot == null || marker == null
                || marker.generation <= 0L
                || marker.requestedMode < 1 || marker.requestedMode > 5) {
            return false;
        }
        try {
            if (!ensureEnergyAuthorityEpoch(resolver, boot, marker)) {
                return false;
            }
            EnergyAuthorityFence current = readEnergyAuthorityFence(resolver, boot);
            if (current == null
                    && hasInvalidCurrentBootEnergyAuthorityFence(resolver, boot)) {
                return false;
            }
            if (current != null) {
                if (current.generation > marker.generation
                        || current.generation == marker.generation
                        && (current.mode != marker.requestedMode
                        || current.cancelled && !marker.cancelled)) {
                    return false;
                }
                if (current.exact
                        && current.generation == marker.generation) {
                    if (current.marker.revision > marker.revision) return false;
                    if (current.marker.revision == marker.revision) {
                        return sameEnergyMarker(current.marker, marker);
                    }
                    if (!sameEnergyMarker(
                            marker,
                            mergeEnergyMarkers(current.marker, marker))) {
                        return false;
                    }
                }
            }
            String encoded = encodeEnergyAuthorityFence(boot, marker);
            if (!writeGlobalSetting(
                    resolver, ENERGY_AUTHORITY_FENCE_SETTING, encoded)) {
                return false;
            }
            EnergyAuthorityFence confirmed = readEnergyAuthorityFence(resolver, boot);
            return confirmed != null
                    && confirmed.exact
                    && sameEnergyMarker(confirmed.marker, marker);
        } catch (Throwable failed) {
            logger.warn("energy authority fence update failed: " + failed.getMessage());
            return false;
        }
    }

    private static boolean authorityFenceMatches(
            EnergyAuthorityFence fence, PublishedEnergyRequest marker) {
        return marker != null && authorityFenceMatches(
                fence,
                marker.generation,
                marker.requestedMode,
                marker.cancelled)
                && (!fence.exact || sameEnergyMarker(fence.marker, marker));
    }

    private static boolean isExactEnergyAuthorityFence(
            android.content.ContentResolver resolver,
            String boot,
            PublishedEnergyRequest marker) {
        EnergyAuthorityFence fence = readEnergyAuthorityFence(resolver, boot);
        return fence != null && fence.exact
                && sameEnergyMarker(fence.marker, marker);
    }

      private static boolean authorityFenceMatches(
            EnergyAuthorityFence fence,
            long generation,
            int mode,
            boolean cancelled) {
        return fence != null
                && fence.generation == generation
                && (mode < 1 || fence.mode == mode)
                && fence.cancelled == cancelled;
    }

    private static boolean sameEnergyAuthority(
            PublishedEnergyRequest first, PublishedEnergyRequest second) {
        return first != null && second != null
                && first.generation == second.generation
                && first.requestedMode == second.requestedMode
                && first.cancelled == second.cancelled;
    }

    private static PublishedEnergyRequest mirrorCoordinatedEnergyMarker(
            android.content.ContentResolver resolver, String boot) {
        for (int attempt = 0; attempt < 3; attempt++) {
            CoordinateRead read = readCoordinatedEnergyMarker(boot);
            PublishedEnergyRequest coordinated = read.marker;
            if (read.status != CoordinateStatus.VALID || coordinated == null) return null;
            EnergyAuthorityFence authority =
                    readEnergyAuthorityFence(resolver, boot);
            if (!authorityFenceMatches(authority, coordinated)) return null;
            String encoded = encodeEnergyMarker(boot, coordinated);
            if (!writeGlobalSetting(
                    resolver, ENERGY_STATE_SETTING, encoded)) {
                return null;
            }
            CoordinateRead afterState = readCoordinatedEnergyMarker(boot);
            if (afterState.status != CoordinateStatus.VALID
                    || !sameEnergyMarker(coordinated, afterState.marker)) {
                continue;
            }
            if (!writeGlobalSetting(
                    resolver,
                    ENERGY_STATE_CONFIRM_SETTING,
                    confirmationToken(encoded))) {
                return null;
            }
            CoordinateRead afterConfirm = readCoordinatedEnergyMarker(boot);
            PublishedEnergyRequest confirmed =
                    readConfirmedSettingsMarkerPayload(resolver, boot);
            EnergyAuthorityFence finalAuthority =
                    readEnergyAuthorityFence(resolver, boot);
            if (afterConfirm.status == CoordinateStatus.VALID
                    && sameEnergyMarker(coordinated, afterConfirm.marker)
                    && sameEnergyMarker(coordinated, confirmed)
                    && authorityFenceMatches(finalAuthority, coordinated)) {
                return coordinated;
            }
        }
        return null;
    }

    private static CoordinateRead readCoordinatedEnergyMarker(String boot) {
        try (RandomAccessFile lockFile = openEnergyStateLockFile();
             FileChannel lockChannel = lockFile.getChannel()) {
            FileLock stateLock = acquireEnergyStateLock(lockChannel, null);
            if (stateLock == null) return CoordinateRead.unreadable();
            try {
                CoordinateRead coordinate = readCoordinateEnergyMarkerUnlocked(boot);
                if (coordinate.status != CoordinateStatus.VALID) return coordinate;
                PublishedEnergyRequest marker = coordinate.marker;
                return isPlausibleEnergyMarker(
                        marker, android.os.SystemClock.elapsedRealtimeNanos())
                        ? coordinate : CoordinateRead.unreadable();
            } finally {
                stateLock.release();
            }
        } catch (Throwable unavailable) {
            return isAccessDenied(unavailable)
                    ? CoordinateRead.inaccessible() : CoordinateRead.unreadable();
        }
    }

    private static String encodeEnergyMarker(String boot, PublishedEnergyRequest marker) {
        return "v4:" + boot
                + ":" + marker.generation
                + ":" + marker.requestedMode
                + ":" + marker.state
                + ":" + marker.revision
                + ":" + marker.rollbackMode
                + ":" + (marker.actuationStarted ? 1 : 0)
                + ":" + (marker.applied ? 1 : 0)
                + ":" + (marker.rollbackPending ? 1 : 0)
                + ":" + marker.rollbackOwner
                + ":" + marker.compensationAttempts;
    }

    private static boolean sameEnergyMarker(
            PublishedEnergyRequest first, PublishedEnergyRequest second) {
        return first != null && second != null
                && first.generation == second.generation
                && first.requestedMode == second.requestedMode
                && first.state == second.state
                && first.revision == second.revision
                && first.rollbackMode == second.rollbackMode
                && first.actuationStarted == second.actuationStarted
                && first.applied == second.applied
                && first.rollbackPending == second.rollbackPending
                && first.rollbackOwner == second.rollbackOwner
                && first.compensationAttempts == second.compensationAttempts;
    }

    private enum CoordinateStatus {
        MISSING,
        OTHER_BOOT,
        VALID,
        INACCESSIBLE,
        UNREADABLE
    }

    private static final class CoordinateRead {
        final CoordinateStatus status;
        final PublishedEnergyRequest marker;

        private CoordinateRead(CoordinateStatus status, PublishedEnergyRequest marker) {
            this.status = status;
            this.marker = marker;
        }

        static CoordinateRead missing() {
            return new CoordinateRead(CoordinateStatus.MISSING, null);
        }

        static CoordinateRead valid(PublishedEnergyRequest marker) {
            return new CoordinateRead(CoordinateStatus.VALID, marker);
        }

        static CoordinateRead otherBoot() {
            return new CoordinateRead(CoordinateStatus.OTHER_BOOT, null);
        }

        static CoordinateRead inaccessible() {
            return new CoordinateRead(CoordinateStatus.INACCESSIBLE, null);
        }

        static CoordinateRead unreadable() {
            return new CoordinateRead(CoordinateStatus.UNREADABLE, null);
        }
    }

    /**
     * Resolve a fence-first crash before exposing state. The fence is the authority commit point:
     * a sidecar that lags it is advanced, while a sidecar ahead of or conflicting with it is never
     * trusted by a sidecar-inaccessible process.
     */
    private static CoordinateRead reconcileCoordinateAuthority(
            android.content.Context context,
            String boot,
            CoordinateRead observed) {
        if (context == null || boot == null || observed == null
                || observed.status == CoordinateStatus.INACCESSIBLE) {
            return observed;
        }
        android.content.ContentResolver resolver = context.getContentResolver();
        EnergyAuthorityFence fence = readEnergyAuthorityFence(resolver, boot);
        if (fence == null) {
            EnergyAuthorityEpoch epoch =
                    readEnergyAuthorityEpoch(resolver, boot);
            if (epoch != null) {
                fence = new EnergyAuthorityFence(
                        epoch.generation, epoch.mode, epoch.cancelled);
            } else {
              PublishedEnergyRequest payload =
                      readConfirmedSettingsMarkerPayload(resolver, boot);
              if (observed.status != CoordinateStatus.VALID
                      || payload == null
                      || !sameEnergyAuthority(observed.marker, payload)) {
                  return observed;
              }
              PublishedEnergyRequest migration =
                      mergeEnergyMarkers(observed.marker, payload);
              if (payload.revision > observed.marker.revision
                      && sameEnergyMarker(migration, observed.marker)
                      && !sameEnergyMarker(payload, observed.marker)) {
                  return CoordinateRead.unreadable();
              }
              if (!installEnergyAuthorityFence(resolver, boot, migration)) {
                  return CoordinateRead.unreadable();
              }
              fence = readEnergyAuthorityFence(resolver, boot);
            }
          }
        if (observed.status == CoordinateStatus.VALID
                && fence != null
                && fence.exact
                && authorityFenceMatches(fence, observed.marker)) {
            return observed;
        }
        try (RandomAccessFile lockFile = openEnergyStateLockFile();
             FileChannel lockChannel = lockFile.getChannel()) {
              FileLock stateLock = acquireEnergyStateLock(lockChannel, null);
              if (stateLock == null) return CoordinateRead.unreadable();
                try {
                    CoordinateRead current = readCoordinateEnergyMarkerUnlocked(boot);
                    if (current.status == CoordinateStatus.INACCESSIBLE) return current;
                    if (fence.exact) {
                        PublishedEnergyRequest authoritative = fence.marker;
                        if (current.status == CoordinateStatus.VALID
                                && current.marker != null
                                && current.marker.generation > authoritative.generation) {
                            return CoordinateRead.unreadable();
                        }
                        if (current.status != CoordinateStatus.VALID
                                || !sameEnergyMarker(current.marker, authoritative)) {
                            writeCoordinateEnergyMarkerAtomic(boot, authoritative);
                        }
                        return CoordinateRead.valid(authoritative);
                    }
                    PublishedEnergyRequest payload = markerForEnergyAuthority(
                            fence,
                            readBestEnergyAuthorityPayload(resolver, boot));
                  PublishedEnergyRequest replacement;
                  if (current.status == CoordinateStatus.VALID
                          && current.marker != null) {
                      PublishedEnergyRequest marker = current.marker;
                    if (marker.generation > fence.generation
                            || marker.generation == fence.generation
                            && marker.requestedMode != fence.mode
                            || marker.generation == fence.generation
                            && marker.cancelled && !fence.cancelled) {
                          return CoordinateRead.unreadable();
                      }
                      if (marker.generation == fence.generation) {
                          replacement = markerForEnergyAuthority(fence, marker);
                          if (replacement == null) return CoordinateRead.unreadable();
                          if (payload != null) {
                              if (fence.cancelled) {
                                  replacement =
                                          replacement.withCancellationMetadataFrom(payload);
                              } else {
                                  PublishedEnergyRequest merged =
                                          mergeEnergyMarkers(replacement, payload);
                                  if (payload.revision > replacement.revision
                                          && sameEnergyMarker(merged, replacement)
                                          && !sameEnergyMarker(payload, replacement)) {
                                      return CoordinateRead.unreadable();
                                  }
                                  replacement = merged;
                              }
                          }
                      } else {
                          if (payload == null && fence.cancelled) {
                              return CoordinateRead.unreadable();
                          }
                          replacement = payload != null
                                  ? payload
                                  : PublishedEnergyRequest.desired(
                                          fence.generation, fence.mode);
                      }
                  } else {
                      if (payload == null && fence.cancelled) {
                          // Never turn an unreadable cancellation record into a tombstone that
                          // falsely says no rollback is owed.
                          return CoordinateRead.unreadable();
                      }
                      replacement = payload != null
                              ? payload
                              : PublishedEnergyRequest.desired(
                                      fence.generation, fence.mode);
                  }
                  if (!authorityFenceMatches(fence, replacement)) {
                      return CoordinateRead.unreadable();
                  }
                  if (!installEnergyAuthorityFence(
                          resolver, boot, replacement)) {
                      return CoordinateRead.unreadable();
                  }
                  if (current.status != CoordinateStatus.VALID
                          || !sameEnergyMarker(current.marker, replacement)) {
                    writeCoordinateEnergyMarkerAtomic(boot, replacement);
                }
                return CoordinateRead.valid(replacement);
            } finally {
                stateLock.release();
            }
        } catch (Throwable failed) {
            return isAccessDenied(failed)
                    ? CoordinateRead.inaccessible() : CoordinateRead.unreadable();
          }
      }

      private static PublishedEnergyRequest markerForEnergyAuthority(
              EnergyAuthorityFence fence, PublishedEnergyRequest marker) {
          if (fence == null || marker == null
                  || marker.generation != fence.generation
                  || marker.requestedMode != fence.mode
                  || marker.cancelled && !fence.cancelled) {
              return null;
          }
          PublishedEnergyRequest candidate =
                  fence.cancelled && !marker.cancelled ? marker.asCancelled() : marker;
          return authorityFenceMatches(fence, candidate) ? candidate : null;
      }

      private static PublishedEnergyRead readPublishedEnergyRequestDirect(
              android.content.Context context) {
        try {
            String boot = currentBootToken(context);
            if (boot == null) return PublishedEnergyRead.unreadable();
            CoordinateRead coordinated = reconcileCoordinateAuthority(
                    context, boot, readCoordinatedEnergyMarker(boot));
            android.content.ContentResolver resolver = context.getContentResolver();
            if (coordinated.status == CoordinateStatus.VALID) {
                if (!isPlausibleEnergyMarker(
                        coordinated.marker, android.os.SystemClock.elapsedRealtimeNanos())) {
                    return PublishedEnergyRead.unreadable();
                }
                EnergyAuthorityFence fence =
                        readEnergyAuthorityFence(resolver, boot);
                if (!authorityFenceMatches(fence, coordinated.marker)) {
                    return PublishedEnergyRead.unreadable();
                }
                PublishedEnergyRequest mirrored =
                        readConfirmedSettingsMarker(resolver, boot);
                PublishedEnergyRequest marker =
                        reconcileConfirmedMirror(
                                resolver, boot, coordinated.marker, mirrored);
                if (!sameEnergyMarker(marker, mirrored)) {
                    PublishedEnergyRequest repaired =
                            mirrorCoordinatedEnergyMarker(resolver, boot);
                    if (repaired != null) marker = repaired;
                }
                return PublishedEnergyRead.valid(marker);
              }
              if (coordinated.status == CoordinateStatus.INACCESSIBLE) {
                  PublishedEnergyRequest authoritative =
                          readAuthoritativeSettingsMarker(resolver, boot);
                  if (!isPlausibleEnergyMarker(
                          authoritative, android.os.SystemClock.elapsedRealtimeNanos())) {
                      return PublishedEnergyRead.inaccessible();
                  }
                  return PublishedEnergyRead.valid(authoritative);
            }
            if (coordinated.status == CoordinateStatus.MISSING
                    || coordinated.status == CoordinateStatus.OTHER_BOOT) {
                return PublishedEnergyRead.missing();
            }
            return PublishedEnergyRead.unreadable();
        } catch (Throwable unavailable) {
            return PublishedEnergyRead.unreadable();
        }
    }

    private static PublishedEnergyRequest parsePublishedEnergyRequest(
            String raw, String boot) {
        if (raw == null || boot == null) return null;
        try {
            String[] parts = raw.split(":", -1);
            if (parts.length == 12 && "v4".equals(parts[0])
                    && bootTokensMatch(boot, parts[1])) {
                long generation = Long.parseLong(parts[2]);
                int requestedMode = Integer.parseInt(parts[3]);
                int state = Integer.parseInt(parts[4]);
                long revision = Long.parseLong(parts[5]);
                int rollbackMode = Integer.parseInt(parts[6]);
                boolean started = parseFlag(parts[7]);
                boolean applied = parseFlag(parts[8]);
                boolean rollbackPending = parseFlag(parts[9]);
                int rollbackOwner = Integer.parseInt(parts[10]);
                int compensationAttempts = Integer.parseInt(parts[11]);
                if (generation <= 0L || requestedMode < 1 || requestedMode > 5
                        || state < ENERGY_STATE_PENDING
                        || state > ENERGY_STATE_CANCELLED
                        || revision < 0L
                        || (rollbackMode != -1
                        && (rollbackMode < 1 || rollbackMode > 5))
                        || rollbackOwner < ENERGY_ACTUATOR_NONE
                        || rollbackOwner > ENERGY_ACTUATOR_APP
                        || compensationAttempts < 0
                        || compensationAttempts > MAX_ENERGY_COMPENSATION_ATTEMPTS
                        || (started && (rollbackMode < 1
                        || !isValidActuatorOwner(rollbackOwner)))
                        || (!started && (rollbackMode != -1
                        || rollbackOwner != ENERGY_ACTUATOR_NONE
                        || compensationAttempts != 0))
                        || (state == ENERGY_STATE_PENDING
                        && (started || applied || rollbackMode != -1))
                        || (rollbackPending && (state != ENERGY_STATE_CANCELLED
                        || !started || rollbackMode < 1
                        || rollbackMode == requestedMode))
                        || (compensationAttempts > 0
                        && (state != ENERGY_STATE_CANCELLED || !started))) {
                    return null;
                }
                return new PublishedEnergyRequest(
                        generation,
                        requestedMode,
                        state,
                        revision,
                        rollbackMode,
                        started,
                        applied,
                        rollbackPending,
                        rollbackOwner,
                        compensationAttempts);
            }
            if (parts.length == 10 && "v3".equals(parts[0])
                    && bootTokensMatch(boot, parts[1])) {
                long generation = Long.parseLong(parts[2]);
                int requestedMode = Integer.parseInt(parts[3]);
                int state = Integer.parseInt(parts[4]);
                long revision = Long.parseLong(parts[5]);
                int rollbackMode = Integer.parseInt(parts[6]);
                boolean started = parseFlag(parts[7]);
                boolean applied = parseFlag(parts[8]);
                boolean rollbackPending = parseFlag(parts[9]);
                // Recover the old COMPLETE-without-BEGIN encoding as a valid non-actuating
                // confirmation instead of wedging every later request for this boot.
                if (started && rollbackMode < 1 && applied && !rollbackPending) {
                    started = false;
                }
                if (generation <= 0L || requestedMode < 1 || requestedMode > 5
                        || state < ENERGY_STATE_PENDING
                        || state > ENERGY_STATE_CANCELLED
                        || revision < 0L
                        || (rollbackMode != -1
                        && (rollbackMode < 1 || rollbackMode > 5))
                        || (started && rollbackMode < 1)
                        || (!started && rollbackMode != -1)
                        || (state == ENERGY_STATE_PENDING
                        && (started || applied || rollbackMode != -1))
                        || (rollbackPending && (state != ENERGY_STATE_CANCELLED
                        || !started || rollbackMode < 1
                        || rollbackMode == requestedMode))) {
                    return null;
                }
                return new PublishedEnergyRequest(
                        generation,
                        requestedMode,
                        state,
                        revision,
                        rollbackMode,
                        started,
                        applied,
                        rollbackPending,
                        started ? ENERGY_ACTUATOR_APP : ENERGY_ACTUATOR_NONE,
                        0);
            }
            if (parts.length == 3 && bootTokensMatch(boot, parts[0])) {
                long generation = Long.parseLong(parts[1]);
                int mode = Integer.parseInt(parts[2]);
                if (generation <= 0L || mode < 0 || mode > 5) return null;
                if (mode == 0) {
                    return new PublishedEnergyRequest(
                            generation,
                            1,
                            ENERGY_STATE_CANCELLED,
                            0L,
                            -1,
                            false,
                            false,
                            false,
                            ENERGY_ACTUATOR_NONE,
                            0);
                }
                return PublishedEnergyRequest.desired(generation, mode);
            }
            return null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    static boolean isUserWritableEnergyMode(int mode) {
        return mode == 1 || mode == 3;
    }

    /** Map the public EV/HEV command to the OEM selector used by the vehicle's own UI. */
    public static int mandatoryElectricStateForEnergyMode(int mode) {
        if (mode == 1) return 2; // EV -> mandatory electric
        if (mode == 3) return 1; // HEV -> intelligent
        return -1;
    }

    /** Convert the OEM selector readback to the public EV/HEV command domain. */
    public static int energyModeForMandatoryElectricState(int state) {
        if (state == 2) return 1;
        if (state == 1) return 3;
        return -1;
    }

    /**
     * Read the OEM EV/HEV selector. Feature IDs are device-scoped: on the Energy device,
     * 2665/2667 are the mandatory-electric read/write pair.
     */
    public static int readMandatoryElectricState(Object energyDevice) {
        if (energyDevice == null) return -1;
        try {
            Object value = energyDevice.getClass()
                    .getMethod("getMandatoryElectricState")
                    .invoke(energyDevice);
            if (value instanceof Number) {
                int state = ((Number) value).intValue();
                if (state == 1 || state == 2) return state;
            }
        } catch (Throwable unavailable) {
            // Older runtime wrappers expose this selector only through generic feature IDs.
        }
        Object value = BydDeviceHelper.callGet(energyDevice, 2665, Integer.TYPE);
        int state = BydDeviceHelper.getIntValue(value);
        return state == 1 || state == 2 ? state : -1;
    }

    /** Write the OEM selector through both supported SDK surfaces; readback remains authoritative. */
    public static int writeMandatoryElectricState(Object energyDevice, int state) {
        if (energyDevice == null || state < 1 || state > 2) return Integer.MIN_VALUE;
        int namedResult = Integer.MIN_VALUE;
        try {
            Object result = energyDevice.getClass()
                    .getMethod("setMandatoryElectricState", int.class)
                    .invoke(energyDevice, state);
            namedResult = result instanceof Number
                    ? ((Number) result).intValue()
                    : result instanceof Boolean && !((Boolean) result) ? -1 : 0;
        } catch (Throwable unavailable) {
            // The generic Energy feature is the actual path on older runtime wrappers.
        }
        int genericResult = BydDeviceHelper.sendSetCommandRaw(energyDevice, 2667, state);
        return genericResult != Integer.MIN_VALUE ? genericResult : namedResult;
    }

    static boolean isPlausibleEnergyGeneration(long generation, long nowNanos) {
        if (generation <= 0L || nowNanos <= 0L) return false;
        long oldest = nowNanos > ENERGY_MARKER_MAX_AGE_NANOS
                ? nowNanos - ENERGY_MARKER_MAX_AGE_NANOS : 1L;
        long newest = nowNanos > Long.MAX_VALUE - ENERGY_MARKER_MAX_FUTURE_NANOS
                ? Long.MAX_VALUE : nowNanos + ENERGY_MARKER_MAX_FUTURE_NANOS;
        return generation >= oldest && generation <= newest;
    }

    static boolean isPlausibleEnergyMarker(
            PublishedEnergyRequest marker, long nowNanos) {
        if (marker == null) return false;
        return marker.cancelled
                ? isPlausibleEnergyGenerationForMutation(marker.generation, nowNanos)
                : isPlausibleEnergyGeneration(marker.generation, nowNanos);
    }

    /**
     * Mutations may finish cancellation or rollback after admission freshness expires. They still
     * reject future-poisoned generations, but deliberately have no lower age bound.
     */
    private static boolean isPlausibleEnergyGenerationForMutation(
            long generation, long nowNanos) {
        if (generation <= 0L || nowNanos <= 0L) return false;
        long newest = nowNanos > Long.MAX_VALUE - ENERGY_MARKER_MAX_FUTURE_NANOS
                ? Long.MAX_VALUE : nowNanos + ENERGY_MARKER_MAX_FUTURE_NANOS;
        return generation <= newest;
    }

      private static PublishedEnergyRequest readConfirmedSettingsMarker(
              android.content.ContentResolver resolver, String boot) {
          PublishedEnergyRequest marker =
                  readConfirmedSettingsMarkerPayload(resolver, boot);
          EnergyAuthorityFence fence = readEnergyAuthorityFence(resolver, boot);
          return authorityFenceMatches(fence, marker) ? marker : null;
      }

    private static PublishedEnergyRequest readAuthoritativeSettingsMarker(
            android.content.ContentResolver resolver, String boot) {
        EnergyAuthorityFence fence = readEnergyAuthorityFence(resolver, boot);
        if (fence != null && fence.exact) return fence.marker;
        EnergyAuthorityEpoch epoch = readEnergyAuthorityEpoch(resolver, boot);
        if (epoch != null) {
            EnergyAuthorityFence epochFence = new EnergyAuthorityFence(
                    epoch.generation, epoch.mode, epoch.cancelled);
            PublishedEnergyRequest candidate = markerForEnergyAuthority(
                    epochFence,
                    readBestEnergyAuthorityPayload(resolver, boot));
            if (candidate == null && !epoch.cancelled) {
                candidate = PublishedEnergyRequest.desired(
                        epoch.generation, epoch.mode);
            }
            if (candidate != null
                    && installEnergyAuthorityFence(resolver, boot, candidate)) {
                EnergyAuthorityFence repaired =
                        readEnergyAuthorityFence(resolver, boot);
                if (repaired != null && repaired.exact) return repaired.marker;
            }
            return null;
        }
        PublishedEnergyRequest marker =
                readConfirmedSettingsMarkerPayload(resolver, boot);
        return authorityFenceMatches(fence, marker) ? marker : null;
      }

      private static PublishedEnergyRequest readConfirmedSettingsMarkerPayload(
            android.content.ContentResolver resolver, String boot) {
        String encoded = readGlobalSetting(
                resolver, ENERGY_STATE_SETTING);
        String confirmation = readGlobalSetting(
                resolver, ENERGY_STATE_CONFIRM_SETTING);
        if (encoded == null || !confirmationToken(encoded).equals(confirmation)) return null;
        return parsePublishedEnergyRequest(encoded, boot);
    }

    private static boolean writeConfirmedSettingsMarker(
            android.content.ContentResolver resolver,
            String boot,
            PublishedEnergyRequest marker) {
        if (!authorityFenceMatches(
                readEnergyAuthorityFence(resolver, boot), marker)) {
            return false;
        }
        String encoded = encodeEnergyMarker(boot, marker);
        if (!writeGlobalSetting(
                resolver, ENERGY_STATE_SETTING, encoded)) {
            return false;
        }
        if (!writeGlobalSetting(
                resolver, ENERGY_STATE_CONFIRM_SETTING, confirmationToken(encoded))) {
            return false;
        }
        return sameEnergyMarker(
                marker, readConfirmedSettingsMarkerPayload(resolver, boot))
                && authorityFenceMatches(
                        readEnergyAuthorityFence(resolver, boot), marker);
    }

    private static void mirrorConfirmedSettingsMarkerBestEffort(
            android.content.ContentResolver resolver,
            String boot,
            PublishedEnergyRequest marker) {
        try {
            writeConfirmedSettingsMarker(resolver, boot, marker);
        } catch (Throwable failed) {
            logger.warn("energy Settings mirror update failed: " + failed.getMessage());
        }
    }

    private static String confirmationToken(String encoded) {
        return "confirmed:" + encoded;
    }

    private static PublishedEnergyRequest reconcileConfirmedMirror(
            android.content.ContentResolver resolver,
            String boot,
            PublishedEnergyRequest coordinate,
            PublishedEnergyRequest mirror) {
        PublishedEnergyRequest merged = mergeEnergyMarkers(coordinate, mirror);
        if (merged == null || sameEnergyMarker(coordinate, merged)) return coordinate;
        if (!authorityFenceMatches(
                readEnergyAuthorityFence(resolver, boot), merged)) {
            return coordinate;
        }
        try (RandomAccessFile lockFile = openEnergyStateLockFile();
             FileChannel lockChannel = lockFile.getChannel()) {
            FileLock stateLock = acquireEnergyStateLock(lockChannel, null);
            if (stateLock == null) return coordinate;
            try {
                CoordinateRead current = readCoordinateEnergyMarkerUnlocked(boot);
                if (current.status != CoordinateStatus.VALID
                        || !sameEnergyMarker(current.marker, coordinate)
                        || !authorityFenceMatches(
                                readEnergyAuthorityFence(resolver, boot), merged)) {
                    return current.status == CoordinateStatus.VALID
                            ? current.marker : coordinate;
                }
                writeCoordinateEnergyMarkerAtomic(boot, merged);
                return merged;
            } finally {
                stateLock.release();
            }
        } catch (Throwable ignored) {
            return coordinate;
        }
    }

    private static PublishedEnergyRequest mergeEnergyMarkers(
            PublishedEnergyRequest coordinate,
            PublishedEnergyRequest mirror) {
        if (coordinate == null || mirror == null
                || coordinate.generation != mirror.generation
                || coordinate.requestedMode != mirror.requestedMode) {
            return coordinate;
        }
        if (mirror.revision <= coordinate.revision) return coordinate;
        if (coordinate.cancelled && !mirror.cancelled) return coordinate;
        if (coordinate.rollbackMode >= 1 && mirror.rollbackMode >= 1
                && coordinate.rollbackMode != mirror.rollbackMode) {
            return coordinate;
        }
        if (isValidActuatorOwner(coordinate.rollbackOwner)
                && isValidActuatorOwner(mirror.rollbackOwner)
                && coordinate.rollbackOwner != mirror.rollbackOwner) {
            return coordinate;
        }
        if (coordinate.actuationStarted && !mirror.actuationStarted
                || coordinate.applied && !mirror.applied
                || coordinate.compensationAttempts > mirror.compensationAttempts
                || coordinate.cancelled && mirror.state != ENERGY_STATE_CANCELLED) {
            return coordinate;
        }
        return mirror;
    }

    private static boolean isOtherBootEnergyMarker(String raw, String boot) {
        if (raw == null || boot == null) return false;
        String[] parts = raw.split(":", -1);
        if ((parts.length == 12 && "v4".equals(parts[0]))
                || (parts.length == 10 && "v3".equals(parts[0]))) {
            return !bootTokensMatch(boot, parts[1]);
        }
        return parts.length == 3 && !bootTokensMatch(boot, parts[0]);
    }

    private static boolean bootTokensMatch(String expected, String actual) {
        return expected.equals(actual);
    }

    static boolean isEnergyActuatorOwnershipAvailable(
            PublishedEnergyRequest marker, int actuatorOwner) {
        return marker != null
                && isValidActuatorOwner(actuatorOwner)
                && (!marker.actuationStarted || marker.rollbackOwner == actuatorOwner);
    }

    private static boolean parseFlag(String value) {
        if ("0".equals(value)) return false;
        if ("1".equals(value)) return true;
        throw new IllegalArgumentException("invalid flag");
    }

    private static boolean isValidActuatorOwner(int owner) {
        return owner == ENERGY_ACTUATOR_DAEMON || owner == ENERGY_ACTUATOR_APP;
    }

    private static boolean isAccessDenied(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SecurityException
                    || current instanceof java.nio.file.AccessDeniedException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("permission denied")
                        || lower.contains("eacces")
                        || lower.contains("operation not permitted")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void forceEnergyStateDirectory(java.io.File directory) {
        if (directory == null) return;
        try (FileChannel channel = FileChannel.open(
                directory.toPath(), java.nio.file.StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Throwable ignored) {
            // The state file itself was fsynced; some Android filesystems reject directory fsync.
        }
    }

    /**
     * Access a Global setting from either a normal app context or the UID-2000 daemon.
     *
     * <p>The daemon's synthetic context is attributed to package {@code android}, which
     * SettingsProvider correctly rejects for shell UID. Use the provider's external-client path
     * with {@code com.android.shell} attribution in that process, retaining a read-only
     * command-line client only as a final compatibility fallback.
     */
    private static String readGlobalSetting(
            android.content.ContentResolver resolver, String key) {
        Throwable resolverFailure = null;
        boolean shellUid = android.os.Process.myUid() == ANDROID_SHELL_UID;
        if (!shellUid || !shellSettingsResolverRejected) {
            try {
                return android.provider.Settings.Global.getString(resolver, key);
            } catch (Throwable failed) {
                resolverFailure = failed;
                if (!shellUid) {
                    throw new IllegalStateException(
                            "Global setting read failed for " + key, failed);
                }
                shellSettingsResolverRejected = true;
            }
        }

        GlobalSettingCall external = callExternalGlobalSetting(
                SETTINGS_CALL_GET_GLOBAL, key, null);
        if (external.success) {
            logShellSettingsFallbackOnce("external SettingsProvider");
            return external.value;
        }
        GlobalSettingCall command = readSettingCommand(key);
        if (command.success) {
            logShellSettingsFallbackOnce("settings command");
            return command.value;
        }
        Throwable cause = command.failure != null
                ? command.failure
                : external.failure != null ? external.failure : resolverFailure;
        throw new IllegalStateException(
                "Global setting read unavailable for " + key, cause);
    }

    private static boolean writeGlobalSetting(
            android.content.ContentResolver resolver, String key, String value) {
        boolean shellUid = android.os.Process.myUid() == ANDROID_SHELL_UID;
        if (!shellUid || !shellSettingsResolverRejected) {
            try {
                if (android.provider.Settings.Global.putString(resolver, key, value)) {
                    return true;
                }
                if (!shellUid) return false;
                shellSettingsResolverRejected = true;
            } catch (Throwable failed) {
                if (!shellUid) return false;
                shellSettingsResolverRejected = true;
            }
        }

        GlobalSettingCall external = callExternalGlobalSetting(
                SETTINGS_CALL_PUT_GLOBAL, key, value);
        if (external.success) {
            logShellSettingsFallbackOnce("external SettingsProvider");
            return true;
        }
        // Never fall back to a subprocess for writes. A timed-out `settings put` could outlive its
        // caller and overwrite a newer generation after the request had already failed closed.
        logger.warn("Global setting write unavailable for " + key);
        return false;
    }

    /**
     * Mirror the Android 10 {@code settings} command's provider access without spawning an
     * app_process for every marker read. UID 2000 owns {@code com.android.shell}, so this caller
     * identity satisfies SettingsProvider's package/UID check.
     */
    private static GlobalSettingCall callExternalGlobalSetting(
            String method, String key, String value) {
        return callExternalGlobalSetting(
                android.os.Process.myUid(),
                method,
                key,
                value,
                new ReflectiveExternalSettingsAccess());
    }

    static GlobalSettingCall callExternalGlobalSetting(
            int uid,
            String method,
            String key,
            String value,
            ExternalSettingsAccess access) {
        if (uid != ANDROID_SHELL_UID || access == null) {
            return GlobalSettingCall.unavailable();
        }
        int userId = userIdForUid(uid);
        Object holder = null;
        try {
            holder = access.acquire(
                    SETTINGS_AUTHORITY,
                    userId,
                    "OverDriveEnergyState");
            if (holder == null) return GlobalSettingCall.unavailable();
            return access.call(
                    holder,
                    "com.android.shell",
                    SETTINGS_AUTHORITY,
                    method,
                    key,
                    userId,
                    value);
        } catch (java.lang.reflect.InvocationTargetException invocation) {
            Throwable cause = invocation.getCause() != null
                    ? invocation.getCause() : invocation;
            return GlobalSettingCall.failure(cause);
        } catch (Throwable failed) {
            return GlobalSettingCall.failure(failed);
        } finally {
            if (holder != null) {
                try {
                    access.release(SETTINGS_AUTHORITY, holder);
                } catch (Throwable ignored) {
                    // Best effort: SettingsProvider is process-global and remains available.
                }
            }
        }
    }

    interface ExternalSettingsAccess {
        Object acquire(String authority, int userId, String tag) throws Throwable;

        GlobalSettingCall call(
                Object holder,
                String callingPackage,
                String authority,
                String method,
                String key,
                int userId,
                String value) throws Throwable;

        void release(String authority, Object holder) throws Throwable;
    }

    private static final class ReflectiveExternalSettingsAccess
            implements ExternalSettingsAccess {
        private Object activityManager;
        private final android.os.IBinder token = new android.os.Binder();

        @Override
        public Object acquire(String authority, int userId, String tag) throws Throwable {
            try {
                com.overdrive.app.shell.HiddenApiBypass.INSTANCE.bypass();
            } catch (Throwable ignored) {
                // The daemon normally enables this during bootstrap; the CLI read fallback remains.
            }

            Class<?> activityManagerClass = Class.forName("android.app.ActivityManager");
            activityManager = activityManagerClass.getMethod("getService").invoke(null);
            if (activityManager == null) return null;

            java.lang.reflect.Method acquire = null;
            for (java.lang.reflect.Method candidate
                    : activityManager.getClass().getMethods()) {
                if (!"getContentProviderExternal".equals(candidate.getName())) continue;
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length == 4
                        && parameters[0] == String.class
                        && parameters[1] == int.class
                        && android.os.IBinder.class.isAssignableFrom(parameters[2])
                        && parameters[3] == String.class) {
                    acquire = candidate;
                    break;
                }
            }
            return acquire != null
                    ? acquire.invoke(activityManager, authority, userId, token, tag) : null;
        }

        @Override
        public GlobalSettingCall call(
                Object holder,
                String callingPackage,
                String authority,
                String method,
                String key,
                int userId,
                String value) throws Throwable {
            java.lang.reflect.Field providerField = holder.getClass().getField("provider");
            Object provider = providerField.get(holder);
            if (provider == null) return GlobalSettingCall.unavailable();

            java.lang.reflect.Method call = null;
            for (java.lang.reflect.Method candidate : provider.getClass().getMethods()) {
                if (!"call".equals(candidate.getName())) continue;
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length == 5
                        && parameters[0] == String.class
                        && parameters[1] == String.class
                        && parameters[2] == String.class
                        && parameters[3] == String.class
                        && android.os.Bundle.class.isAssignableFrom(parameters[4])) {
                    call = candidate;
                    break;
                }
            }
            if (call == null) return GlobalSettingCall.unavailable();

            android.os.Bundle extras = new android.os.Bundle();
            extras.putInt(SETTINGS_CALL_USER, userId);
            if (SETTINGS_CALL_PUT_GLOBAL.equals(method)) {
                extras.putString(SETTINGS_CALL_VALUE, value);
            }
            Object response = call.invoke(
                    provider,
                    callingPackage,
                    authority,
                    method,
                    key,
                    extras);
            if (!SETTINGS_CALL_GET_GLOBAL.equals(method)) {
                return GlobalSettingCall.success(null);
            }
            if (!(response instanceof android.os.Bundle)) {
                return response == null
                        ? GlobalSettingCall.success(null)
                        : GlobalSettingCall.unavailable();
            }
            android.os.Bundle result = (android.os.Bundle) response;
            Object raw = result.get(SETTINGS_CALL_VALUE);
            if (raw == null) {
                try {
                    java.lang.reflect.Method pairValue =
                            android.os.Bundle.class.getDeclaredMethod("getPairValue");
                    pairValue.setAccessible(true);
                    raw = pairValue.invoke(result);
                } catch (Throwable ignored) {
                    // Android 10 normally returns the value under "value".
                }
            }
            if (raw == null && result.size() == 1) {
                for (String resultKey : result.keySet()) {
                    raw = result.get(resultKey);
                    break;
                }
            }
            return GlobalSettingCall.success(raw != null ? String.valueOf(raw) : null);
        }

        @Override
        public void release(String authority, Object holder) throws Throwable {
            if (activityManager == null) return;
            for (java.lang.reflect.Method candidate
                    : activityManager.getClass().getMethods()) {
                if (!"removeContentProviderExternal".equals(candidate.getName())) continue;
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length == 2
                        && parameters[0] == String.class
                        && android.os.IBinder.class.isAssignableFrom(parameters[1])) {
                    candidate.invoke(activityManager, authority, token);
                    return;
                }
            }
        }
    }

    private static GlobalSettingCall readSettingCommand(String key) {
        Process process = null;
        java.io.InputStream output = null;
        try {
            java.util.ArrayList<String> command = new java.util.ArrayList<>();
            command.add("settings");
            command.add("get");
            command.add("global");
            command.add(key);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();
            if (!process.waitFor(
                    ENERGY_SETTINGS_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return GlobalSettingCall.failure(
                        new IllegalStateException("settings command timed out"));
            }
            output = process.getInputStream();
            String text = readSmallProcessOutput(output).trim();
            if (process.exitValue() != 0) {
                return GlobalSettingCall.failure(
                        new IllegalStateException(
                                "settings command exited " + process.exitValue() + ": " + text));
            }
            return GlobalSettingCall.success("null".equals(text) ? null : text);
        } catch (Throwable failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return GlobalSettingCall.failure(failed);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (output != null) {
                try {
                    output.close();
                } catch (Throwable ignored) {
                    // Best effort cleanup.
                }
            }
        }
    }

    private static String readSmallProcessOutput(java.io.InputStream input) throws Exception {
        if (input == null) return "";
        byte[] buffer = new byte[1024];
        StringBuilder text = new StringBuilder();
        int count;
        while ((count = input.read(buffer)) >= 0 && text.length() < 4096) {
            int retained = Math.min(count, 4096 - text.length());
            text.append(new String(
                    buffer,
                    0,
                    retained,
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        return text.toString();
    }

    /** SettingsProvider takes the Android user/profile ID, not the process's full UID. */
    static int userIdForUid(int uid) {
        return Math.max(0, uid / ANDROID_UIDS_PER_USER);
    }

    private static void logShellSettingsFallbackOnce(String route) {
        if (shellSettingsFallbackLogged) return;
        synchronized (VehicleActuatorBridge.class) {
            if (shellSettingsFallbackLogged) return;
            shellSettingsFallbackLogged = true;
            logger.info("Energy coordination using " + route
                    + " for UID-2000 Global settings access");
        }
    }

    static final class GlobalSettingCall {
        final boolean success;
        final String value;
        final Throwable failure;

        private GlobalSettingCall(boolean success, String value, Throwable failure) {
            this.success = success;
            this.value = value;
            this.failure = failure;
        }

        static GlobalSettingCall success(String value) {
            return new GlobalSettingCall(true, value, null);
        }

        static GlobalSettingCall unavailable() {
            return new GlobalSettingCall(false, null, null);
        }

        static GlobalSettingCall failure(Throwable failure) {
            return new GlobalSettingCall(false, null, failure);
        }
    }

    private static String currentBootToken(android.content.Context context) {
        if (context == null) return null;
        android.content.Context appContext = context.getApplicationContext();
        energyStateContext = appContext != null ? appContext : context;
        String cached = energyBootToken;
        if (cached != null) return cached;
        synchronized (VehicleActuatorBridge.class) {
            if (energyBootToken != null) return energyBootToken;
            String kernelBootId = null;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
                String token = reader.readLine();
                if (token != null && token.matches("[0-9a-fA-F-]{16,64}")) {
                    kernelBootId = token.toLowerCase(java.util.Locale.ROOT);
                }
            } catch (Throwable unavailable) {
                logger.warn("kernel boot token unavailable: " + unavailable.getMessage());
            }
            if (kernelBootId == null) return null;
            // The kernel UUID is generated once per boot. Using it alone keeps the daemon and app
            // process on the same token even when only one process can read SettingsProvider.
            energyBootToken = buildEnergyBootToken(kernelBootId);
            return energyBootToken;
        }
    }

    static String buildEnergyBootToken(String kernelBootId) {
        if (kernelBootId == null || kernelBootId.isEmpty()) return null;
        return "boot-" + kernelBootId;
    }

    private static void dispatchEnergyControl(
            android.content.Context context, String action, long generation) {
        final String shellCommand = "am start-foreground-service -n " + ENERGY_SERVICE
                + " --es action " + action
                + " --es request_generation " + generation;
        ENERGY_CONTROL_SHELL_LANE.submit(action, generation, shellCommand);
        android.content.Context appContext =
                context != null ? context.getApplicationContext() : null;
        if (appContext == null) appContext = context;
        final android.content.Context startContext = appContext;
        if (startContext == null) return;
        ENERGY_CONTROL_DIRECT_LANE.submit(() -> {
            try {
                android.content.Intent intent = new android.content.Intent();
                intent.setComponent(new android.content.ComponentName(
                        "com.overdrive.app",
                        "com.overdrive.app.services.EnergyModeActuatorService"));
                intent.putExtra("action", action);
                intent.putExtra("request_generation", Long.toString(generation));
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startContext.startForegroundService(intent);
                } else {
                    startContext.startService(intent);
                }
            } catch (Throwable failed) {
                logger.warn("energy control direct start failed: "
                        + failed.getMessage());
            }
        });
    }

    /** Fire-and-forget {@code am} exec — identical to {@link AudioPlaybackController}'s. */
    private static void exec(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        } catch (Throwable t) {
            logger.warn("exec failed [" + cmd + "]: " + t.getMessage());
        }
    }

    /**
     * Submit one direct {@code am} process (no {@code sh -c} child) to the process-wide launch
     * lane and wait only for the caller's budget. A timeout leaves the latest request retained on
     * that fixed lane; interruption cancels it and is never converted into a retry.
     */
    private static LaunchOutcome execLoggedBlocking(String cmd, String tag, long timeoutMs) {
        return execLoggedBlocking(cmd, tag, timeoutMs, null);
    }

    private static LaunchOutcome execLoggedBlocking(
            String cmd, String tag, long timeoutMs, Runnable onInterrupted) {
        return execLoggedBlocking(
                cmd, tag, timeoutMs, onInterrupted, true, null);
    }

    private static LaunchOutcome execLoggedBlocking(
            String cmd,
            String tag,
            long timeoutMs,
            Runnable onInterrupted,
            boolean fenced,
            String classpath) {
        LaunchTask task = ENERGY_LAUNCH_LANE.submit(
                cmd, tag, timeoutMs,
                fenced ? () -> isEnergyLaunchCommandCurrent(cmd) : null,
                classpath);
        try {
            if (!task.done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                logger.warn(tag + ": am launch still running at caller deadline");
                return LaunchOutcome.TIMEOUT;
            }
            return task.outcome;
        } catch (InterruptedException interrupted) {
            ENERGY_LAUNCH_LANE.cancel(task);
            runInterruptionCancellation(onInterrupted, tag);
            Thread.currentThread().interrupt();
            logger.warn(tag + ": am launch interrupted");
            return LaunchOutcome.INTERRUPTED;
        }
    }

    private static void runInterruptionCancellation(Runnable cancellation, String tag) {
        if (cancellation == null) return;
        try {
            cancellation.run();
        } catch (Throwable failed) {
            logger.warn(tag + ": interruption tombstone failed: " + failed.getMessage());
        }
    }

    /** Execute one launch on the lane worker, with bounded runtime and termination waits. */
    private static LaunchOutcome runLaunchDirect(LaunchTask task) {
        Process process = null;
        java.io.InputStream stream = null;
        final long deadlineNanos =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(task.timeoutMs);
        try {
            // Every word is generated locally and values are numeric; invoking `am` directly avoids
            // leaving a child behind when only an intermediate `sh -c` process is killed.
            String[] argv = task.command.trim().split("\\s+");
            ProcessBuilder builder = new ProcessBuilder(argv);
            if (task.classpath != null) {
                builder.environment().put("CLASSPATH", task.classpath);
            }
            builder.redirectErrorStream(true);
            process = task.start(builder);
            if (process == null) return LaunchOutcome.INTERRUPTED;
            final StringBuilder output = new StringBuilder();
            stream = process.getInputStream();

            while (process.isAlive()) {
                if (task.cancelled) return LaunchOutcome.INTERRUPTED;
                drainAvailableProcessOutput(stream, output);
                long processBudgetNanos = deadlineNanos - System.nanoTime();
                if (processBudgetNanos <= 0L) {
                    logger.warn(task.tag + ": am timed out");
                    return LaunchOutcome.TIMEOUT;
                }
                process.waitFor(
                        Math.max(1L, Math.min(
                                50L, TimeUnit.NANOSECONDS.toMillis(processBudgetNanos))),
                        TimeUnit.MILLISECONDS);
            }
            drainAvailableProcessOutput(stream, output);
            String text = output.toString().trim();
            int exitCode = process.exitValue();
            if (exitCode != 0 || task.classpath == null && text.contains("Error")) {
                logger.warn(task.tag + ": am FAILED (exit=" + exitCode + ") " + text);
                return LaunchOutcome.FAILURE;
            } else if (!text.isEmpty()) {
                logger.debug(task.tag + ": am ok — " + text);
            }
            return LaunchOutcome.SUCCESS;
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return LaunchOutcome.INTERRUPTED;
            }
            logger.warn(task.tag + ": exec failed [" + task.command + "]: " + t.getMessage());
            return LaunchOutcome.FAILURE;
        } finally {
            // Give forced termination a bounded grace period. A broken Process implementation must
            // not hold the sole launch worker forever and starve the latest pending generation.
            // The boot-scoped marker still rejects this older launch if its process survives and
            // reaches ActivityManager later.
            if (process != null && process.isAlive()) {
                boolean restoreInterrupt = false;
                try {
                    process.destroyForcibly();
                } catch (Throwable ignored) {
                    // Fall through to the termination wait.
                }
                long terminationDeadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(
                                ENERGY_PROCESS_TERMINATION_GRACE_MS);
                while (process.isAlive() && System.nanoTime() < terminationDeadline) {
                    try {
                        long remaining = terminationDeadline - System.nanoTime();
                        process.waitFor(
                                Math.max(1L, Math.min(
                                        50L, TimeUnit.NANOSECONDS.toMillis(remaining))),
                                TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        restoreInterrupt = true;
                    } catch (Throwable cannotWait) {
                        break;
                    }
                }
                if (process.isAlive()) {
                    task.unreapedProcess = true;
                    logger.warn(task.tag + ": am process survived forced termination grace; "
                            + "poisoning this subprocess lane");
                }
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                    // Best effort cleanup.
                }
            }
        }
    }

    private static void drainAvailableProcessOutput(
            java.io.InputStream stream, StringBuilder output) {
        if (stream == null) return;
        byte[] buffer = new byte[1024];
        try {
            while (stream.available() > 0) {
                int count = stream.read(
                        buffer, 0, Math.min(buffer.length, stream.available()));
                if (count <= 0) return;
                if (output.length() < 1024) {
                    int retained = Math.min(count, 1024 - output.length());
                    output.append(new String(
                            buffer,
                            0,
                            retained,
                            java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
            // The stream can close concurrently with process termination.
        }
    }

    /** One worker plus one conflating pending launch; no timed-out call creates another worker. */
    private static final class EnergyLaunchLane {
        private LaunchTask pending;
        private boolean workerRunning;
        private boolean poisoned;

        synchronized LaunchTask submit(String command, String tag, long timeoutMs) {
            return submit(
                    command,
                    tag,
                    timeoutMs,
                    () -> isEnergyLaunchCommandCurrent(command),
                    null);
        }

        synchronized LaunchTask submit(
                String command,
                String tag,
                long timeoutMs,
                LaunchAuthority authority,
                String classpath) {
            LaunchTask task = new LaunchTask(
                    command,
                    tag,
                    timeoutMs,
                    authority,
                    classpath);
            if (poisoned) {
                task.cancel();
                task.complete(LaunchOutcome.FAILURE);
                return task;
            }
            if (pending != null) {
                pending.cancel();
                pending.complete(LaunchOutcome.TIMEOUT);
            }
            pending = task;
            if (!workerRunning) {
                workerRunning = true;
                try {
                    Thread worker = new Thread(this::drain, "EnergyAmLaunch");
                    worker.setDaemon(true);
                    worker.start();
                } catch (Throwable unavailable) {
                    pending = null;
                    workerRunning = false;
                    task.cancel();
                    task.complete(LaunchOutcome.FAILURE);
                    logger.warn("energy launch worker could not start: "
                            + unavailable.getMessage());
                }
            }
            return task;
        }

        synchronized void cancel(LaunchTask task) {
            task.cancel();
            if (pending == task) pending = null;
            task.complete(LaunchOutcome.INTERRUPTED);
        }

        private void drain() {
            while (true) {
                final LaunchTask task;
                synchronized (this) {
                    task = pending;
                    pending = null;
                    if (task == null) {
                        workerRunning = false;
                        return;
                    }
                }
                LaunchOutcome outcome = task.cancelled
                        ? LaunchOutcome.INTERRUPTED : runLaunchDirect(task);
                task.complete(outcome);
                if (task.unreapedProcess) {
                    synchronized (this) {
                        poisoned = true;
                        workerRunning = false;
                        if (pending != null) {
                            pending.cancel();
                            pending.complete(LaunchOutcome.FAILURE);
                            pending = null;
                        }
                    }
                    return;
                }
            }
        }
    }

    private static final class LaunchTask {
        final String command;
        final String tag;
        final long timeoutMs;
        final String classpath;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean cancelled;
        volatile boolean unreapedProcess;
        volatile LaunchOutcome outcome = LaunchOutcome.TIMEOUT;
        private final ReentrantLock invocationGate = new ReentrantLock();
        private final LaunchAuthority authority;
        private boolean completed;
        private boolean invocationStarted;

        LaunchTask(String command, String tag, long timeoutMs) {
            this(command, tag, timeoutMs, null, null);
        }

        LaunchTask(
                String command,
                String tag,
                long timeoutMs,
                LaunchAuthority authority) {
            this(command, tag, timeoutMs, authority, null);
        }

        LaunchTask(
                String command,
                String tag,
                long timeoutMs,
                LaunchAuthority authority,
                String classpath) {
            this.command = command;
            this.tag = tag;
            this.timeoutMs = timeoutMs;
            this.authority = authority;
            this.classpath = classpath;
        }

        Process start(ProcessBuilder builder) throws java.io.IOException {
            invocationGate.lock();
            try {
                if (cancelled || authority != null && !authority.isCurrent()) return null;
                invocationStarted = true;
                Process process = builder.start();
                if (cancelled || authority != null && !authority.isCurrent()) {
                    // Return the handle so runLaunchDirect's single bounded reap path can verify
                    // termination and permanently poison the lane if this child survives.
                    cancelled = true;
                }
                return process;
            } finally {
                invocationGate.unlock();
            }
        }

        void cancel() {
            if (invocationGate.tryLock()) {
                try {
                    cancelled = true;
                } finally {
                    invocationGate.unlock();
                }
                return;
            }
            // start() owns the same gate across ProcessBuilder.start(). Mark the task and rely on
            // the already-published service tombstone to compensate that committed launch.
            cancelled = true;
        }

        synchronized void complete(LaunchOutcome value) {
            if (completed) return;
            completed = true;
            outcome = value;
            done.countDown();
        }
    }

    private interface LaunchAuthority {
        boolean isCurrent();
    }

    private static boolean isEnergyLaunchCommandCurrent(String command) {
        long generation = parseNumericCommandArgument(
                command, "request_generation");
        long modeValue = parseNumericCommandArgument(command, "mode");
        if (generation <= 0L || modeValue < 1L || modeValue > 5L) {
            modeValue = parseStandaloneEnergyCommandArgument(command, 2);
            generation = parseStandaloneEnergyCommandArgument(command, 3);
        }
        if (generation <= 0L || modeValue < 1L || modeValue > 5L) return false;
        String boot = energyBootToken;
        if (boot == null) return false;
        android.content.Context context = energyStateContext;
        if (context == null) return false;
        CoordinateRead read = readCoordinatedEnergyMarker(boot);
        PublishedEnergyRequest marker = read.marker;
        EnergyAuthorityFence fence =
                readEnergyAuthorityFence(context.getContentResolver(), boot);
        return read.status == CoordinateStatus.VALID
                && marker != null
                && authorityFenceMatches(fence, marker)
                && !marker.cancelled
                && !marker.pending
                && marker.generation == generation
                && marker.mode == (int) modeValue;
    }

    private static long parseStandaloneEnergyCommandArgument(
            String command, int argumentOffset) {
        if (command == null) return -1L;
        String[] words = command.trim().split("\\s+");
        for (int index = 0; index + 3 < words.length; index++) {
            if (BydModeCommand.class.getName().equals(words[index])
                    && "energy".equals(words[index + 1])) {
                try {
                    return Long.parseLong(words[index + argumentOffset]);
                } catch (NumberFormatException ignored) {
                    return -1L;
                }
            }
        }
        return -1L;
    }

    private static long parseNumericCommandArgument(String command, String name) {
        if (command == null) return -1L;
        String[] words = command.trim().split("\\s+");
        for (int index = 0; index + 2 < words.length; index++) {
            if ("--es".equals(words[index]) && name.equals(words[index + 1])) {
                try {
                    return Long.parseLong(words[index + 2]);
                } catch (NumberFormatException ignored) {
                    return -1L;
                }
            }
        }
        return -1L;
    }

    private static boolean dispatchEnergyModeDirect(
            android.content.Context context, int mode, long generation) {
        if (context == null) return false;
        DirectStartTask task = ENERGY_DIRECT_START_LANE.submit(context, mode, generation);
        try {
            if (!task.done.await(ENERGY_DIRECT_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                logger.warn("energy_mode=" + mode + " generation=" + generation
                        + ": direct service start still running at caller deadline");
                return false;
            }
            return task.accepted;
        } catch (InterruptedException interrupted) {
            ENERGY_DIRECT_START_LANE.cancel(task);
            runInterruptionCancellation(
                    () -> cancelPublishedEnergyRequest(task.context, generation),
                    "energy_mode=" + mode + " generation=" + generation + " direct start");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean runDirectStart(DirectStartTask task) {
        if (!ENERGY_GENERATIONS.isCurrent(task.generation)) return false;
        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setComponent(new android.content.ComponentName(
                    "com.overdrive.app",
                    "com.overdrive.app.services.EnergyModeActuatorService"));
            intent.putExtra("action", "energy_mode");
            intent.putExtra("mode", Integer.toString(task.mode));
            intent.putExtra("request_generation", Long.toString(task.generation));
            if (!ENERGY_GENERATIONS.isCurrent(task.generation)) {
                return false;
            }
            android.content.ComponentName started = task.start(intent);
            return started != null;
        } catch (Throwable failed) {
            logger.warn("energy_mode=" + task.mode + " generation=" + task.generation
                    + ": direct service start failed: " + failed.getMessage());
            return false;
        }
    }

    /** Independent subprocess-free service-start lane with one latest-pending request. */
    private static final class EnergyDirectStartLane {
        private DirectStartTask pending;
        private boolean workerRunning;

        synchronized DirectStartTask submit(
                android.content.Context context, int mode, long generation) {
            android.content.Context appContext = context.getApplicationContext();
            if (appContext == null) appContext = context;
            DirectStartTask task = new DirectStartTask(appContext, mode, generation);
            if (pending != null) {
                pending.cancel();
                pending.complete(false);
            }
            pending = task;
            if (!workerRunning) {
                workerRunning = true;
                try {
                    Thread worker = new Thread(this::drain, "EnergyDirectStart");
                    worker.setDaemon(true);
                    worker.start();
                } catch (Throwable unavailable) {
                    pending = null;
                    workerRunning = false;
                    task.cancel();
                    task.complete(false);
                    logger.warn("energy direct-start worker could not start: "
                            + unavailable.getMessage());
                }
            }
            return task;
        }

        synchronized void cancel(DirectStartTask task) {
            task.cancel();
            if (pending == task) pending = null;
            task.complete(false);
        }

        private void drain() {
            while (true) {
                final DirectStartTask task;
                synchronized (this) {
                    task = pending;
                    pending = null;
                    if (task == null) {
                        workerRunning = false;
                        return;
                    }
                }
                task.complete(runDirectStart(task));
            }
        }
    }

    private static final class DirectStartTask {
        final android.content.Context context;
        final int mode;
        final long generation;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean cancelled;
        volatile boolean accepted;
        private final ReentrantLock invocationGate = new ReentrantLock();
        private boolean completed;
        private boolean invocationStarted;

        DirectStartTask(android.content.Context context, int mode, long generation) {
            this.context = context;
            this.mode = mode;
            this.generation = generation;
        }

        android.content.ComponentName start(android.content.Intent intent) {
            invocationGate.lock();
            try {
                if (cancelled
                        || !isEnergyRequestCurrent(context, generation, mode)) {
                    return null;
                }
                invocationStarted = true;
                android.content.ComponentName started;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    started = context.startForegroundService(intent);
                } else {
                    started = context.startService(intent);
                }
                return !cancelled
                        && isEnergyRequestCurrent(context, generation, mode)
                        ? started : null;
            } finally {
                invocationGate.unlock();
            }
        }

        void cancel() {
            if (invocationGate.tryLock()) {
                try {
                    cancelled = true;
                } finally {
                    invocationGate.unlock();
                }
                return;
            }
            cancelled = true;
        }

        synchronized void complete(boolean value) {
            if (completed) return;
            completed = true;
            accepted = value;
            done.countDown();
        }
    }

    /**
     * One ordered energy-control subprocess with one latest pending command. Generation ordering,
     * and cancellation over fence at an equal generation, are retained even when an older caller
     * arrives late.
     */
    private static final class EnergyControlLane {
        private EnergyControlTask active;
        private EnergyControlTask pending;
        private boolean workerRunning;
        private boolean poisoned;

        synchronized void submit(String action, long generation, String command) {
            if (poisoned) {
                logger.warn("energy control subprocess lane is poisoned; "
                        + action + " generation=" + generation
                        + " will use only the direct service-start lane");
                return;
            }
            EnergyControlTask task =
                    new EnergyControlTask(action, generation, command);
            if (active != null && !task.outranks(active)) return;
            if (pending != null && !task.outranks(pending)) return;
            if (pending != null) pending.launch.cancel();
            pending = task;
            if (active != null && task.outranks(active)) active.launch.cancel();
            if (workerRunning) return;
            workerRunning = true;
            try {
                Thread worker = new Thread(this::drain, "EnergyControlAm");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable unavailable) {
                pending = null;
                workerRunning = false;
                logger.warn("energy control worker could not start: "
                        + unavailable.getMessage());
            }
        }

        private void drain() {
            while (true) {
                final EnergyControlTask task;
                synchronized (this) {
                    task = pending;
                    pending = null;
                    if (task == null) {
                        active = null;
                        workerRunning = false;
                        return;
                    }
                    active = task;
                }
                LaunchOutcome outcome = runLaunchDirect(task.launch);
                task.launch.complete(outcome);
                synchronized (this) {
                    if (active == task) active = null;
                    if (task.launch.unreapedProcess) {
                        poisoned = true;
                        workerRunning = false;
                        if (pending != null) {
                            pending.launch.cancel();
                            pending.launch.complete(LaunchOutcome.FAILURE);
                            pending = null;
                        }
                        return;
                    }
                }
            }
        }
    }

    private static final class EnergyControlTask {
        final String action;
        final long generation;
        final LaunchTask launch;

        EnergyControlTask(String action, long generation, String command) {
            this.action = action;
            this.generation = generation;
            this.launch = new LaunchTask(
                    command,
                    "energy control " + action + " generation=" + generation,
                    ENERGY_CONTROL_TIMEOUT_MS);
        }

        boolean outranks(EnergyControlTask other) {
            return other == null
                    || generation > other.generation
                    || generation == other.generation
                    && "energy_mode_cancel".equals(action)
                    && !"energy_mode_cancel".equals(other.action);
        }
    }

    /** One active control launch plus one latest pending launch. */
    private static final class LatestRunnableLane {
        private final String workerName;
        private Runnable pending;
        private boolean workerRunning;

        LatestRunnableLane(String workerName) {
            this.workerName = workerName;
        }

        synchronized void submit(Runnable operation) {
            pending = operation;
            if (workerRunning) return;
            workerRunning = true;
            try {
                Thread worker = new Thread(this::drain, workerName);
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable unavailable) {
                pending = null;
                workerRunning = false;
                logger.warn(workerName + " worker could not start: "
                        + unavailable.getMessage());
            }
        }

        private void drain() {
            while (true) {
                Runnable operation;
                synchronized (this) {
                    operation = pending;
                    pending = null;
                    if (operation == null) {
                        workerRunning = false;
                        return;
                    }
                }
                try {
                    operation.run();
                } catch (Throwable failed) {
                    logger.warn(workerName + " operation failed: " + failed.getMessage());
                }
            }
        }
    }

    private interface StateIoOperation {
        Object run(StateIoTask task);
    }

    /**
     * One process-local SettingsProvider worker with one latest-pending operation. Reads and writes
     * use separate instances so a verification read can never evict the newest pending publication.
     */
    private static final class EnergyStateIoLane {
        private final String workerName;
        private StateIoTask pending;
        private boolean workerRunning;

        EnergyStateIoLane(String workerName) {
            this.workerName = workerName;
        }

        synchronized StateIoTask submit(StateIoOperation operation) {
            return submit(new StateIoTask(operation, 0L, false, false));
        }

        synchronized StateIoTask submitMutation(
                long generation, boolean cancellation, StateIoOperation operation) {
            return submit(
                    new StateIoTask(operation, generation, cancellation, true));
        }

        private StateIoTask submit(StateIoTask task) {
            if (pending != null) {
                if (task.mutation && pending.mutation) {
                    boolean pendingWins =
                            pending.generation > task.generation
                            || (pending.generation == task.generation
                            && pending.cancellation && !task.cancellation);
                    if (pendingWins) {
                        task.cancelBeforeInvocation();
                        task.complete(null);
                        return task;
                    }
                }
                pending.cancelBeforeInvocation();
                pending.complete(null);
            }
            pending = task;
            if (!workerRunning) {
                workerRunning = true;
                try {
                    Thread worker = new Thread(this::drain, workerName);
                    worker.setDaemon(true);
                    worker.start();
                } catch (Throwable unavailable) {
                    pending = null;
                    workerRunning = false;
                    task.complete(null);
                    logger.warn(workerName + " worker could not start: "
                            + unavailable.getMessage());
                }
            }
            return task;
        }

        synchronized void cancel(StateIoTask task) {
            if (!task.cancelBeforeInvocation()) return;
            if (pending == task) pending = null;
            task.complete(null);
        }

        private void drain() {
            while (true) {
                final StateIoTask task;
                synchronized (this) {
                    task = pending;
                    pending = null;
                    if (task == null) {
                        workerRunning = false;
                        return;
                    }
                }
                Object result = null;
                if (task.beginInvocation()) {
                    try {
                        result = task.operation.run(task);
                    } catch (Throwable ignored) {
                        // The public caller reports an unavailable marker.
                    }
                }
                task.complete(result);
            }
        }
    }

    private static final class StateIoTask {
        final StateIoOperation operation;
        final long generation;
        final boolean cancellation;
        final boolean mutation;
        final CountDownLatch done = new CountDownLatch(1);
        private Object result;
        private boolean completed;
        private boolean cancelled;
        private boolean invocationStarted;

        StateIoTask(
                StateIoOperation operation,
                long generation,
                boolean cancellation,
                boolean mutation) {
            this.operation = operation;
            this.generation = generation;
            this.cancellation = cancellation;
            this.mutation = mutation;
        }

        Object await(long timeoutMs) {
            try {
                if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
                synchronized (this) {
                    return result;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        synchronized boolean beginInvocation() {
            if (cancelled) return false;
            invocationStarted = true;
            return true;
        }

        synchronized boolean cancelBeforeInvocation() {
            if (completed || invocationStarted) return false;
            cancelled = true;
            return true;
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }

        synchronized boolean completeUnlessCancelled(Object value) {
            if (cancelled || completed) return false;
            completed = true;
            result = value;
            done.countDown();
            return true;
        }

        synchronized boolean isCompleted() {
            return completed;
        }

        synchronized Object resultNow() {
            return result;
        }

        synchronized void complete(Object value) {
            if (completed) return;
            completed = true;
            result = value;
            done.countDown();
        }
    }

    static final class EnergyDispatch {
        final int mode;
        final long generation;

        EnergyDispatch(int mode, long generation) {
            this.mode = mode;
            this.generation = generation;
        }
    }

    public enum EnergyReadStatus {
        VALID,
        MISSING,
        INACCESSIBLE,
        UNREADABLE
    }

    public static final class PublishedEnergyRead {
        public final EnergyReadStatus status;
        public final PublishedEnergyRequest request;

        private PublishedEnergyRead(
                EnergyReadStatus status, PublishedEnergyRequest request) {
            this.status = status;
            this.request = request;
        }

        static PublishedEnergyRead valid(PublishedEnergyRequest request) {
            return new PublishedEnergyRead(EnergyReadStatus.VALID, request);
        }

        static PublishedEnergyRead missing() {
            return new PublishedEnergyRead(EnergyReadStatus.MISSING, null);
        }

        static PublishedEnergyRead inaccessible() {
            return new PublishedEnergyRead(EnergyReadStatus.INACCESSIBLE, null);
        }

        static PublishedEnergyRead unreadable() {
            return new PublishedEnergyRead(EnergyReadStatus.UNREADABLE, null);
        }
    }

    public static final class PublishedEnergyRequest {
        public final long generation;
        /** Desired mode for a live marker; zero for a cancellation tombstone. */
        public final int mode;
        public final int requestedMode;
        public final boolean cancelled;
        public final boolean pending;
        public final int rollbackMode;
        public final boolean actuationStarted;
        public final boolean applied;
        public final boolean rollbackPending;
        public final int rollbackOwner;
        public final int compensationAttempts;
        private final int state;
        private final long revision;

        private PublishedEnergyRequest(
                long generation,
                int requestedMode,
                int state,
                long revision,
                int rollbackMode,
                boolean actuationStarted,
                boolean applied,
                boolean rollbackPending,
                int rollbackOwner,
                int compensationAttempts) {
            this.generation = generation;
            this.requestedMode = requestedMode;
            this.state = state;
            this.revision = revision;
            this.cancelled = state == ENERGY_STATE_CANCELLED;
            this.pending = state == ENERGY_STATE_PENDING;
            this.mode = cancelled ? 0 : requestedMode;
            this.rollbackMode = rollbackMode;
            this.actuationStarted = actuationStarted;
            this.applied = applied;
            this.rollbackPending = rollbackPending;
            this.rollbackOwner = rollbackOwner;
            this.compensationAttempts = compensationAttempts;
        }

        static PublishedEnergyRequest desired(long generation, int mode) {
            return new PublishedEnergyRequest(
                    generation,
                    mode,
                    ENERGY_STATE_DESIRED,
                    0L,
                    -1,
                    false,
                    false,
                    false,
                    ENERGY_ACTUATOR_NONE,
                    0);
        }

        PublishedEnergyRequest asCancelled() {
            if (cancelled) return this;
            boolean needsRollback = actuationStarted
                    && rollbackMode >= 1
                    && rollbackMode <= 5
                    && rollbackMode != requestedMode;
            return new PublishedEnergyRequest(
                    generation,
                    requestedMode,
                    ENERGY_STATE_CANCELLED,
                    nextRevision(),
                    rollbackMode,
                    actuationStarted,
                    applied,
                    needsRollback,
                    rollbackOwner,
                    compensationAttempts);
        }

        PublishedEnergyRequest withActuation(
                int rollback,
                boolean started,
                boolean physicallyApplied,
                boolean needsRollback,
                int owner,
                int attempts) {
            return new PublishedEnergyRequest(
                    generation,
                    requestedMode,
                    state,
                    nextRevision(),
                    rollback,
                    started,
                    physicallyApplied,
                    needsRollback,
                    owner,
                    attempts);
        }

        PublishedEnergyRequest withCancellationMetadataFrom(
                PublishedEnergyRequest other) {
            if (!cancelled || other == null
                    || generation != other.generation
                    || requestedMode != other.requestedMode) {
                return this;
            }
            int rollback = rollbackMode;
            if (rollback < 1 && other.rollbackMode >= 1) {
                rollback = other.rollbackMode;
            }
            if (rollback >= 1 && other.rollbackMode >= 1
                    && rollback != other.rollbackMode) {
                return this;
            }
            int owner = rollbackOwner;
            if (!isValidActuatorOwner(owner)
                    && isValidActuatorOwner(other.rollbackOwner)) {
                owner = other.rollbackOwner;
            }
            if (isValidActuatorOwner(rollbackOwner)
                    && isValidActuatorOwner(other.rollbackOwner)
                    && rollbackOwner != other.rollbackOwner) {
                return this;
            }
            boolean started = actuationStarted || other.actuationStarted;
            boolean physicallyApplied = applied || other.applied;
            int attempts = Math.max(compensationAttempts, other.compensationAttempts);
            boolean needsRollback;
            if (other.cancelled && other.revision > revision) {
                needsRollback = other.rollbackPending;
            } else {
                needsRollback = started
                        && rollback >= 1
                        && rollback != requestedMode;
            }
            if (rollback == rollbackMode
                    && owner == rollbackOwner
                    && attempts == compensationAttempts
                    && started == actuationStarted
                    && physicallyApplied == applied
                    && needsRollback == rollbackPending) {
                return this;
            }
            long newestRevision = Math.max(revision, other.revision);
            if (newestRevision == Long.MAX_VALUE) return this;
            return new PublishedEnergyRequest(
                    generation,
                    requestedMode,
                    ENERGY_STATE_CANCELLED,
                    newestRevision + 1L,
                    rollback,
                    started,
                    physicallyApplied,
                    needsRollback,
                    started ? owner : ENERGY_ACTUATOR_NONE,
                    started ? attempts : 0);
        }

        private long nextRevision() {
            return revision == Long.MAX_VALUE ? Long.MAX_VALUE : revision + 1L;
        }
    }

}
