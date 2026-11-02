package com.overdrive.app.byd.routing;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.BydCloudDataProvider;
import com.overdrive.app.byd.cloud.CloudCapabilities;
import com.overdrive.app.byd.cloud.VehicleCloudSnapshot;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Routes vehicle control commands between BYD cloud REST and the local SDK
 * (BydDataCollector). Each {@link VehicleCommand} declares a capability
 * matrix — which paths it has, which is preferred, what handshake the cloud
 * leg needs — and the router resolves the effective policy from the
 * declaration plus a per-command override under the {@code bydCloud.routePolicy}
 * config section. The result of every dispatch is a structured
 * {@link CommandResult} so callers can render a transparent
 * "sent via cloud" / "sent via direct connection" badge to the UI.
 *
 * <p>Cloud calls run on a single-thread executor with a 30 s budget so a
 * stalled BYD round-trip never blocks the HTTP request beyond {@link
 * #CLOUD_TIMEOUT_MS}. A per-router lock guarantees only one cloud command is
 * in flight at a time — concurrent /control/remoteControl posts trip BYD's
 * rate-limit (response code 6024).
 */
public final class VehicleCommandRouter {

    private static final String TAG = "VehicleCommandRouter";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final long CLOUD_TIMEOUT_MS = 30_000L;
    /** A cloud seat command is composite; local seed data must be a recent full read. */
    private static final long SEAT_CLOUD_SNAPSHOT_MAX_AGE_MS = 15_000L;
    /** The cloud MQTT/realtime payload is safe only while its connection-health window holds. */
    private static final long CLOUD_SEAT_SNAPSHOT_MAX_AGE_MS =
            VehicleCloudSnapshot.CONNECTION_HEALTH_MAX_AGE_MS;
    /** Bodywork getPowerLevel() values (SDK BODYWORK_POWER_LEVEL_OFF / _ON). */
    private static final int POWER_LEVEL_OFF = 0;
    private static final int POWER_LEVEL_ON = 2;
    /** Climate wake-up: power-on wait after a local power-on, and the HVAC settle after it. */
    private static final long CLIMATE_WAKE_TIMEOUT_MS = 15_000L;
    private static final long CLIMATE_WAKE_POLL_MS = 250L;
    private static final long CLIMATE_WAKE_SETTLE_MS = 2_000L;
    /** Read-back window per write after the wake, and the gap before the one retry. */
    private static final long CLIMATE_WAKE_CONFIRM_MS = 3_000L;
    private static final long CLIMATE_WAKE_RETRY_GAP_MS = 1_000L;
    private static final int CLIMATE_WAKE_WRITE_ATTEMPTS = 2;
    /** No climate wake this soon after the car was switched off. */
    private static final long CLIMATE_WAKE_AFTER_OFF_MS = 120_000L;
    /** BYD response code meaning "previous command in progress". */
    private static final String CLOUD_CODE_RATE_LIMITED = "6024";

    private static volatile VehicleCommandRouter instance;

    private final ExecutorService cloudExec;
    private final Object cloudLock = new Object();
    /** Incremented by STOP so a previously-started tailgate open never actuates later. */
    private final AtomicLong tailgateStopGeneration = new AtomicLong();
    /**
     * Coordinates STOP with the final tailgate-open actuator boundary. It is intentionally
     * separate from cloudLock so STOP never waits for a network request to finish.
     */
    private final Object tailgateAbortLock = new Object();
    private final AtomicReference<Future<?>> activeTailgateOpenFuture = new AtomicReference<>();
    private final AtomicReference<Thread> activeTailgateOpenWorker = new AtomicReference<>();
    private final AtomicReference<BydCloudClient> activeTailgateOpenClient = new AtomicReference<>();
    // Serializes both legs of a composite seat command. The cloud endpoint
    // overwrites all front-seat zones plus steering-wheel heat, so a local
    // write must not slip between snapshot capture and a cloud fallback from
    // another seat command.
    private final Object seatCommandLock = new Object();
    // Serializes CabinClimateCommand: an AC-off sent during an AC-on wake must not run against
    // the still-off car and then be overridden by the wake's write.
    private final Object climateCommandLock = new Object();
    private long remoteClimateActiveUntilMs;
    // Guarded by cloudLock. Updated only after a terminally-confirmed cloud or SDK seat result.
    private int[] seatCompositeState;
    private long seatCompositeStateAtMs;

    private VehicleCommandRouter() {
        cloudExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VehicleCommandRouter-Cloud");
            t.setDaemon(true);
            return t;
        });
    }

    public static VehicleCommandRouter getInstance() {
        if (instance == null) {
            synchronized (VehicleCommandRouter.class) {
                if (instance == null) instance = new VehicleCommandRouter();
            }
        }
        return instance;
    }

    /**
     * Capture tailgate-open cancellation state at an asynchronous caller's ingress boundary.
     * MQTT uses this before queueing its command, so a later STOP cannot be overtaken by that
     * queued open when the worker eventually reaches this router.
     */
    public long captureTailgateOpenStopGeneration() {
        return tailgateStopGeneration.get();
    }

    /**
     * Bind an already-created tailgate open to its ingress cancellation generation.
     * Other command types are returned unchanged so generic callers need no type branch.
     */
    public VehicleCommand bindTailgateOpenStopGeneration(VehicleCommand command,
                                                         long stopGeneration) {
        return command instanceof TrunkOpenCommand
                ? new TrunkOpenCommand(stopGeneration) : command;
    }

    /**
     * Marks a STOP whose cancellation was already applied at an asynchronous ingress boundary.
     * This avoids cancelling a newer OPEN that arrived after that STOP message.
     */
    public VehicleCommand bindTailgateStopCancellation(VehicleCommand command) {
        return command instanceof TrunkStopCommand
                ? new TrunkStopCommand(true) : command;
    }

    /** Interrupt a queued or active tailgate cloud-open without waiting for cloudLock. */
    public void abortPendingTailgateOpen() {
        cancelPendingTailgateOpen();
    }

    /**
     * Whether a router-confirmed OPENAIR preconditioning session is still within
     * BYD's timeSpan=3 duration. This remains true while the car is asleep,
     * where the local ignition/HVAC snapshot cannot represent remote climate.
     */
    public synchronized boolean isRemoteClimateActive() {
        if (remoteClimateActiveUntilMs <= System.currentTimeMillis()) {
            remoteClimateActiveUntilMs = 0L;
            return false;
        }
        return true;
    }

    /** Clear remote-HVAC state when the cloud identity or credentials change. */
    public synchronized void clearRemoteClimateSession() {
        remoteClimateActiveUntilMs = 0L;
    }

    /**
     * The cloud seat endpoint overwrites all driver/passenger heat and ventilation zones.
     * A caller may offer a snapshot as a seed only when every zone was collected recently.
     */
    public static boolean hasFreshCompleteSeatState(BydVehicleData snapshot) {
        if (snapshot == null || snapshot.seatHeat == null || snapshot.seatCool == null
                || snapshot.seatHeat.length < 2 || snapshot.seatCool.length < 2) {
            return false;
        }
        long ageMs = System.currentTimeMillis() - snapshot.seatClimateAtMs;
        if (ageMs < 0L || ageMs > SEAT_CLOUD_SNAPSHOT_MAX_AGE_MS) return false;
        for (int i = 0; i < 2; i++) {
            if (!isSeatLevel(snapshot.seatHeat[i]) || !isSeatLevel(snapshot.seatCool[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSeatLevel(int level) {
        return level >= 0 && level <= 2;
    }

    /** Legacy catalog level 3 meant "high"; never let it reach the cloud as wire-off. */
    private static int normalizeLegacySeatLevel(int level) {
        return level == 3 ? 2 : level;
    }

    // ── Public types ────────────────────────────────────────────────────

    /**
     * What a command's declared default preference is when both paths are
     * available. The router may degrade CLOUD_FIRST → SDK if cloud is
     * unavailable, and may upgrade SDK_FIRST → cloud-only when no SDK path
     * exists. CLOUD_ONLY / SDK_ONLY commands skip the alternate path entirely.
     */
    public enum RoutePreference { CLOUD_FIRST, SDK_FIRST, CLOUD_ONLY, SDK_ONLY }

    /**
     * Per-leg capability — does the command have this path, and is it
     * required (no fallback) or optional (router may substitute).
     */
    public enum Capability { NONE, AVAILABLE, REQUIRED }

    /**
     * What the cloud leg needs from the cloud session before it'll dispatch.
     * Kept conservative by default — see {@link VehicleCommand#cloudHandshake()}.
     */
    public enum CloudHandshake {
        /** Stateless config write — only valid creds + VIN. */
        SESSION,
        /** /control/remoteControl — also wants an active MQTT or REST poller. */
        LIVE_CHANNEL
    }

    public enum Outcome {
        SUCCESS, FAILED, NOT_SUPPORTED, RATE_LIMITED, VEHICLE_UNREACHABLE,
        AUTH_REQUIRED, BLOCKED_DRIVING
    }

    /**
     * Path actually executed. CLOUD_THEN_SDK = cloud tried, fell back to SDK.
     * SDK_THEN_CLOUD = SDK tried, fell back to cloud (rare; happens for
     * SDK_FIRST commands when the local primitive returns false).
     */
    public enum Path { CLOUD, SDK, CLOUD_THEN_SDK, SDK_THEN_CLOUD, NONE }

    public static final class CommandResult {
        public final Outcome outcome;
        public final Path path;
        public final String displayMessage;
        public final long latencyMs;
        public final Throwable error;

        private CommandResult(Outcome outcome, Path path, String displayMessage,
                              long latencyMs, Throwable error) {
            this.outcome = outcome;
            this.path = path;
            this.displayMessage = displayMessage != null ? displayMessage : "";
            this.latencyMs = latencyMs;
            this.error = error;
        }

        public static CommandResult success(Path path, String msg, long latencyMs) {
            return new CommandResult(Outcome.SUCCESS, path, msg, latencyMs, null);
        }
        public static CommandResult failed(Path path, String msg, long latencyMs, Throwable t) {
            return new CommandResult(Outcome.FAILED, path, msg, latencyMs, t);
        }
        public static CommandResult notSupported(String msg) {
            return new CommandResult(Outcome.NOT_SUPPORTED, Path.NONE, msg, 0, null);
        }
        public static CommandResult authRequired(String msg) {
            return new CommandResult(Outcome.AUTH_REQUIRED, Path.NONE, msg, 0, null);
        }
        public static CommandResult rateLimited(String msg, long latencyMs) {
            return new CommandResult(Outcome.RATE_LIMITED, Path.CLOUD, msg, latencyMs, null);
        }
        public static CommandResult vehicleUnreachable(String msg, long latencyMs, Throwable t) {
            return new CommandResult(Outcome.VEHICLE_UNREACHABLE, Path.CLOUD, msg, latencyMs, t);
        }
        public static CommandResult blocked(String msg) {
            return new CommandResult(Outcome.BLOCKED_DRIVING, Path.NONE, msg, 0, null);
        }

        public String pathString() {
            switch (path) {
                case CLOUD: return "cloud";
                case SDK: return "local";
                case CLOUD_THEN_SDK: return "cloud-then-local";
                case SDK_THEN_CLOUD: return "local-then-cloud";
                default: return "none";
            }
        }
    }

    // ── Command base ────────────────────────────────────────────────────

    /**
     * Base class for vehicle commands. Subclasses declare the capability
     * matrix (cloud + SDK availability, default preference, cloud handshake)
     * and provide the per-path execution. The router never inspects subclass
     * types — everything flows through these declarations, so adding a new
     * command is "extend, declare capabilities, override the leg(s) you have."
     */
    public static abstract class VehicleCommand implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private transient volatile com.overdrive.app.byd.LocalActuationAttempt localAttempt;

        private void beginLocalAttempt() {
            localAttempt = new com.overdrive.app.byd.LocalActuationAttempt();
        }

        private com.overdrive.app.byd.LocalActuationAttempt localAttempt() {
            com.overdrive.app.byd.LocalActuationAttempt attempt = localAttempt;
            if (attempt == null) {
                synchronized (this) {
                    if (localAttempt == null) beginLocalAttempt();
                    attempt = localAttempt;
                }
            }
            return attempt;
        }

        public abstract String name();

        /**
         * Whether this command can use the cloud leg.
         * - NONE: no cloud path; SDK is the only option.
         * - AVAILABLE: cloud works; router may pick it or skip it.
         * - REQUIRED: cloud is the only path; missing cloud → AUTH_REQUIRED.
         */
        public Capability cloudCapability() { return Capability.NONE; }

        /**
         * Whether this command can use the SDK leg.
         * - NONE: no SDK path; cloud is the only option.
         * - AVAILABLE: SDK works; router may pick it or skip it.
         * - REQUIRED: SDK is the only path; missing SDK → NOT_SUPPORTED.
         */
        public Capability sdkCapability() { return Capability.NONE; }

        /**
         * What to try first when both legs are available. Ignored when one
         * leg is REQUIRED and the other is NONE.
         */
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }

        /**
         * Cloud handshake requirement.
         * /control/remoteControl posts (LOCKDOOR, OPENAIR, …) push their result
         * back through MQTT in the BYD app, but in this app we poll the result
         * over HTTP — so a credentials-only check is sufficient. Override to
         * LIVE_CHANNEL only if the command genuinely depends on an active push
         * channel (none of our current commands do).
         */
        public CloudHandshake cloudHandshake() { return CloudHandshake.SESSION; }

        /** /control/remoteControl needs the PIN; /control/smartCharge/* does not. */
        public boolean requiresControlPin() { return true; }

        /** Coarse vehicle capability required by the cloud leg, if known. */
        public CloudCapabilities.Feature cloudFeature() { return CloudCapabilities.Feature.NONE; }

        /**
         * Retained for compatibility with command declarations written before
         * capability discovery covered every cloud feature. The router now
         * refreshes every declared cloud feature before dispatching it.
         */
        public boolean requiresKnownCloudFeature() { return false; }

        /** Some cloud commands legitimately need longer than the normal cloud budget. */
        public long cloudTimeoutMs() { return CLOUD_TIMEOUT_MS; }

        /**
         * Latency-sensitive commands skip the cloud leg when the vehicle is
         * already awake (SDK is instant; cloud is 5–30 s). Default is normal latency.
         */
        public boolean isLatencySensitive() { return false; }

        /**
         * Prefer the SDK leg while the connected vehicle is awake or ACC-on,
         * while retaining the cloud leg as a fallback. This is useful when a
         * command has equivalent local and remote semantics but should use the
         * remote path first for an asleep vehicle.
         */
        public boolean preferLocalWhenOccupiedOrAwake() { return false; }

        /**
         * When the vehicle is AWAKE, run the SDK leg ONLY — never fall through to cloud.
         *
         * <p>Distinct from {@link #isLatencySensitive()}, which is an optimisation (try the
         * fast path first, cloud still allowed after). This is a CORRECTNESS constraint: the
         * cloud leg of these commands is a <b>remote</b> command that manipulates the
         * vehicle's remote-conditioning session, not the cabin control the occupant asked
         * for. Sending {@code CLOSEAIR} to an occupied, running car ends that remote session
         * and the vehicle powers itself down — the reported "AC-off automation switched my
         * car off while in P". {@code OPENAIR} is the mirror problem: it starts a timed
         * remote session (time_span) on a car that is already running.
         *
         * <p>So while awake, cloud is not a valid fallback for these — a failed local write
         * is reported as failed rather than escalated to a remote command. When the car is
         * asleep the cloud leg is the only path that can work and behaviour is unchanged
         * (that is the actual pre-conditioning use case).
         */
        public boolean localOnlyWhenAwake() { return false; }

        /**
         * The HVAC accepts writes while the car is off and ignores them. When true and the car is
         * confirmed off, {@link VehicleCommandRouter#execute} powers it on through the local rail
         * first. Only for absolute writes (the post-wake write may be repeated) the local leg
         * can represent.
         */
        public boolean wakeVehicleIfOff() { return false; }

        /** The local write made right after that power-on. */
        public boolean executeViaSdkAfterWake(BydDataCollector c) { return executeViaSdk(c); }

        /**
         * Read-back for the post-wake write: TRUE/FALSE when the live state shows it applied or
         * not, null when it cannot be read. The default trusts the write's own result code.
         */
        public Boolean landedAfterWake(BydDataCollector c) { return Boolean.TRUE; }

        /**
         * Whether Home Assistant / MQTT may use this command's normal router path.
         *
         * <p>MQTT remains local by default. Commands must opt in only when their cloud leg is
         * semantically equivalent to the requested operation and has the normal capability,
         * confirmation, timeout, and motion-safety protections.
         */
        public boolean allowCloudFallbackFromMqtt() { return false; }

        /** Whether this command actuates something driving-safety-relevant. */
        public enum MotionSafety { UNRESTRICTED, BLOCK_WHILE_MOVING }

        /**
         * Whether the router should refuse this command while the vehicle is
         * moving (see {@link DrivingSafetyGuard}). Default is UNRESTRICTED; only
         * commands with an explicit BLOCK_WHILE_MOVING override are gated.
         */
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }

        /**
         * User-configurable guard key. Null keeps BLOCK_WHILE_MOVING enabled with no
         * UI override, which is the safe default for future guarded commands.
         */
        public String motionSafetyGuardKey() { return null; }

        // ── Execution legs (override the ones you support) ──────────────

        /**
         * Run via cloud. Implementations either return {@link CloudOutcome}
         * with success/rateLimited, or throw if the path is unsupported.
         */
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return CloudOutcome.unsupported();
        }

        /**
         * Run via SDK. Returns true on success, false on failure.
         */
        public boolean executeViaSdk(BydDataCollector collector) {
            return false;
        }

        // ── Convenience accessors derived from capability declarations ──

        public final boolean hasCloudPath() { return cloudCapability() != Capability.NONE; }
        public final boolean hasSdkPath() { return sdkCapability() != Capability.NONE; }
        public final boolean cloudRequired() { return cloudCapability() == Capability.REQUIRED; }
        public final boolean sdkRequired() { return sdkCapability() == Capability.REQUIRED; }
    }

    /** Cloud execution outcome — success, rate-limited, blocked, or unsupported. */
    public static final class CloudOutcome {
        public final boolean success;
        public final boolean rateLimited;
        public final boolean unsupported;
        public final boolean blockedDriving;
        private CloudOutcome(boolean s, boolean r, boolean u, boolean b) {
            success = s; rateLimited = r; unsupported = u; blockedDriving = b;
        }
        public static CloudOutcome success() { return new CloudOutcome(true, false, false, false); }
        public static CloudOutcome failed() { return new CloudOutcome(false, false, false, false); }
        public static CloudOutcome rateLimited() { return new CloudOutcome(false, true, false, false); }
        public static CloudOutcome unsupported() { return new CloudOutcome(false, false, true, false); }
        public static CloudOutcome blockedDriving() {
            return new CloudOutcome(false, false, false, true);
        }
    }

    // ── Concrete commands ───────────────────────────────────────────────
    // Each command's capability declarations form the routing contract. The
    // router never special-cases a command (except TrunkOpenCommand, which is
    // a composite). To add a new control: extend, declare, override legs.

    // Lock / unlock / find-car / flash run the local rail FIRST — it is instant and needs no
    // account — and fall through to the terminally-confirmed cloud leg when the local write is
    // refused (a sleeping body controller, or a frame this firmware does not honour).
    public static final class LockCommand extends VehicleCommand {
        public String name() { return "lock"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "LOCKDOOR", null);
        }
        public boolean executeViaSdk(BydDataCollector c) {
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
                return c.setDiLink5AllDoorsLocked(true);
            }
            return com.overdrive.app.byd.RemoteKeyControl.lock(c.getRuntimeContext());
        }
        public CloudCapabilities.Feature cloudFeature() { return CloudCapabilities.Feature.LOCK; }
        public boolean requiresKnownCloudFeature() { return true; }
        public MotionSafety motionSafety() { return MotionSafety.BLOCK_WHILE_MOVING; }
        public String motionSafetyGuardKey() { return DrivingSafetyGuard.GUARD_DOOR_LOCKS; }
    }

    public static final class UnlockCommand extends VehicleCommand {
        private final boolean confirmLanded;
        public UnlockCommand() { this(true); }
        /** {@code confirmLanded=false} for callers that confirm and re-send on their own. */
        public UnlockCommand(boolean confirmLanded) { this.confirmLanded = confirmLanded; }
        public String name() { return "unlock"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "OPENDOOR", null);
        }
        public boolean executeViaSdk(BydDataCollector c) {
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
                return c.setDiLink5AllDoorsLocked(false);
            }
            boolean typed = c.isLocalTypedDoorLockAvailable();
            // unlockConfirmed registers one door intent on the remote-key rail; the typed rail none.
            return com.overdrive.app.byd.FourDoorUnlock.unlock(c, confirmLanded, typed ? 0 : 1,
                    () -> unlockLocally(c, typed));
        }
        private boolean unlockLocally(BydDataCollector c, boolean typed) {
            if (typed) {
                return c.setLocalTypedDoorsLocked(false);
            }
            return com.overdrive.app.byd.RemoteKeyControl.unlockConfirmed(
                    c.getRuntimeContext(), confirmLanded ? c : null);
        }
        public CloudCapabilities.Feature cloudFeature() { return CloudCapabilities.Feature.UNLOCK; }
        public boolean requiresKnownCloudFeature() { return true; }
        public MotionSafety motionSafety() { return MotionSafety.BLOCK_WHILE_MOVING; }
        public String motionSafetyGuardKey() { return DrivingSafetyGuard.GUARD_DOOR_LOCKS; }
    }

    // cloudOnly: the cloud leg alone, ungated as before the local rail existed (BYD refuses it in
    // motion). For BYD Cloud "Test Connection", whose result must come from the account.

    /** Horn + lights. */
    public static final class FindCarCommand extends VehicleCommand {
        private final boolean cloudOnly;
        public FindCarCommand() { this(false); }
        public FindCarCommand(boolean cloudOnly) { this.cloudOnly = cloudOnly; }
        public String name() { return "find-car"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return cloudOnly ? Capability.NONE : Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "FINDCAR", null);
        }
        public boolean executeViaSdk(BydDataCollector c) {
            return com.overdrive.app.byd.RemoteKeyControl.findCar(c.getRuntimeContext());
        }
        public CloudCapabilities.Feature cloudFeature() { return CloudCapabilities.Feature.FIND_CAR; }
        public boolean requiresKnownCloudFeature() { return true; }
        public MotionSafety motionSafety() {
            return cloudOnly ? MotionSafety.UNRESTRICTED : MotionSafety.BLOCK_WHILE_MOVING;
        }
        public String motionSafetyGuardKey() { return DrivingSafetyGuard.GUARD_HORN_LIGHTS; }
    }

    /** Lights-only flash. */
    public static final class FlashLightsCommand extends VehicleCommand {
        private final boolean cloudOnly;
        public FlashLightsCommand() { this(false); }
        public FlashLightsCommand(boolean cloudOnly) { this.cloudOnly = cloudOnly; }
        public String name() { return "flash"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return cloudOnly ? Capability.NONE : Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "FLASHLIGHTNOWHISTLE", null);
        }
        public boolean executeViaSdk(BydDataCollector c) {
            return com.overdrive.app.byd.RemoteKeyControl.flashLights(c.getRuntimeContext());
        }
        public CloudCapabilities.Feature cloudFeature() { return CloudCapabilities.Feature.FLASH_LIGHTS; }
        public boolean requiresKnownCloudFeature() { return true; }
        public MotionSafety motionSafety() {
            return cloudOnly ? MotionSafety.UNRESTRICTED : MotionSafety.BLOCK_WHILE_MOVING;
        }
        public String motionSafetyGuardKey() { return DrivingSafetyGuard.GUARD_HORN_LIGHTS; }
    }

    /** Vehicle power on (local remote-key frame). Never while moving; the guard cannot be disabled. */
    public static final class VehiclePowerOnCommand extends VehicleCommand {
        public String name() { return "vehicle-power-on"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return DrivingSafetyGuard.isAccConfirmedOff()
                    ? com.overdrive.app.byd.RemoteKeyControl.powerOnFromOff(c.getRuntimeContext())
                    : com.overdrive.app.byd.RemoteKeyControl.powerOn(c.getRuntimeContext());
        }
        public MotionSafety motionSafety() { return MotionSafety.BLOCK_WHILE_MOVING; }
        public String motionSafetyGuardKey() { return DrivingSafetyGuard.GUARD_VEHICLE_POWER; }
    }

    /** Vehicle power off (local remote-key frame). Never while moving; the guard cannot be disabled. */
    public static final class VehiclePowerOffCommand extends VehicleCommand {
        public String name() { return "vehicle-power-off"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return com.overdrive.app.byd.RemoteKeyControl.powerOff(c.getRuntimeContext());
        }
        public MotionSafety motionSafety() { return MotionSafety.BLOCK_WHILE_MOVING; }
        public String motionSafetyGuardKey() { return DrivingSafetyGuard.GUARD_VEHICLE_POWER; }
    }

    public static final class ClimateOnCommand extends VehicleCommand
            implements CabinClimateCommand {
        public final double tempCelsius;
        /** Requested cloud preconditioning duration; local HVAC ignores this setting. */
        public final int remoteDurationMinutes;
        public ClimateOnCommand(double t) { this(t, 20); }
        public ClimateOnCommand(double t, int remoteDurationMinutes) {
            this.tempCelsius = t;
            this.remoteDurationMinutes = remoteDurationMinutes;
        }
        public String name() { return "climate-on"; }
        public Capability cloudCapability() {
            // OPENAIR accepts whole 15..31 C values. Do not use it as a
            // fallback when it would clamp a valid local 32/33 C request.
            return isCloudTemperatureRepresentable() && isCloudDurationRepresentable()
                    ? Capability.AVAILABLE : Capability.NONE;
        }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            if (!isCloudTemperatureRepresentable() || !isCloudDurationRepresentable()) {
                return CloudOutcome.unsupported();
            }
            return remoteCommand(client, vin, "OPENAIR",
                    BydCloudClient.climateStartParams(tempCelsius, remoteDurationMinutes));
        }
        public boolean executeViaSdk(BydDataCollector c) {
            // 15/16 C are valid OPENAIR targets but the local dial cannot represent them.
            // Refuse before the setter (rather than clamping) so an asleep car can use the
            // cloud fallback, while localOnlyWhenAwake() keeps an occupied car off the remote
            // path entirely.
            if (!isSdkTemperatureRepresentable()) return false;
            // Zone 0 is the SDK's main+copilot target. Set it before powering
            // AC so a failed setpoint write never reports a successful
            // power-on while silently leaving the old temperature in place.
            return c.setAcTemperature(0, tempCelsius) && c.setAcPower(true);
        }
        public CloudCapabilities.Feature cloudFeature() { return CloudCapabilities.Feature.CLIMATE; }
        // OPENAIR starts a TIMED REMOTE session; on an already-running car the occupant wants
        // the cabin AC, not a remote window. See localOnlyWhenAwake().
        public boolean localOnlyWhenAwake() { return true; }
        public boolean wakeVehicleIfOff() { return isSdkTemperatureRepresentable(); }
        /** The car may restore the AC at power-on, and start() refuses an AC that is already on. */
        public boolean executeViaSdkAfterWake(BydDataCollector c) {
            return isSdkTemperatureRepresentable() && c.setAcTemperature(0, tempCelsius)
                    && (c.setAcPower(true) || c.readAcPowerNow() == 1);
        }
        public Boolean landedAfterWake(BydDataCollector c) {
            int state = c.readAcPowerNow();
            return state == 1 ? Boolean.TRUE : state == 0 ? Boolean.FALSE : null;
        }
        public boolean allowCloudFallbackFromMqtt() { return true; }

        private boolean isCloudTemperatureRepresentable() {
            return !Double.isNaN(tempCelsius) && !Double.isInfinite(tempCelsius)
                    && tempCelsius == Math.rint(tempCelsius)
                    && tempCelsius >= 15D && tempCelsius <= 31D;
        }

        private boolean isSdkTemperatureRepresentable() {
            return !Double.isNaN(tempCelsius) && !Double.isInfinite(tempCelsius)
                    && tempCelsius >= BydDataCollector.AC_SETPOINT_MIN_C
                    && tempCelsius <= BydDataCollector.AC_SETPOINT_MAX_C;
        }

        private boolean isCloudDurationRepresentable() {
            return remoteDurationMinutes == 10 || remoteDurationMinutes == 15
                    || remoteDurationMinutes == 20 || remoteDurationMinutes == 25
                    || remoteDurationMinutes == 30;
        }
    }

    public static final class ClimateOffCommand extends VehicleCommand
            implements CabinClimateCommand {
        public String name() { return "climate-off"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "CLOSEAIR", null);
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcPower(false); }
        public CloudCapabilities.Feature cloudFeature() { return CloudCapabilities.Feature.CLIMATE; }
        // CLOSEAIR ends the REMOTE conditioning session — on an occupied, running car the
        // vehicle responds by powering itself down. See localOnlyWhenAwake().
        public boolean localOnlyWhenAwake() { return true; }
        public boolean allowCloudFallbackFromMqtt() { return true; }
    }

    /**
     * Schedule, modify, or remove a cloud remote-climate booking (BOOKINGAIR).
     * There is no equivalent local SDK feature, so this is intentionally cloud-only and capability
     * gated through the same remote-HVAC feature as OPENAIR/CLOSEAIR.
     */
    public static final class ClimateScheduleCommand extends VehicleCommand {
        public static final int CREATE = 1;
        public static final int MODIFY = 2;
        public static final int REMOVE = 3;

        public final int mode;
        public final Long bookingId;
        public final Long bookingTimeSeconds;
        public final Double tempCelsius;
        public final Integer durationMinutes;

        public ClimateScheduleCommand(int mode, Long bookingId, Long bookingTimeSeconds,
                                      Double tempCelsius, Integer durationMinutes) {
            this.mode = mode;
            this.bookingId = bookingId;
            this.bookingTimeSeconds = bookingTimeSeconds;
            this.tempCelsius = tempCelsius;
            this.durationMinutes = durationMinutes;
        }

        public String name() { return "climate-schedule"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public CloudCapabilities.Feature cloudFeature() { return CloudCapabilities.Feature.CLIMATE; }
        public boolean requiresKnownCloudFeature() { return true; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "BOOKINGAIR",
                    BydCloudClient.climateScheduleParams(mode, bookingId, bookingTimeSeconds,
                            tempCelsius, durationMinutes));
        }
        public boolean allowCloudFallbackFromMqtt() { return true; }
    }

    public static final class CloseAllWindowsCommand extends VehicleCommand {
        public String name() { return "windows-close-all"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "CLOSEWINDOW", null);
        }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setAllWindowsCommand(2); // 2 = close
        }
        public CloudCapabilities.Feature cloudFeature() {
            return CloudCapabilities.Feature.WINDOWS_CLOSE;
        }
        public boolean allowCloudFallbackFromMqtt() { return true; }
    }

    /** Full all-window opening is a local SDK operation only. */
    public static final class OpenAllWindowsCommand extends VehicleCommand {
        public String name() { return "windows-open-all"; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setAllWindowsCommand(1); // 1 = open
        }
    }

    /**
     * Vent all four side windows. BYD's cloud OPENWINDOW command opens a small
     * ventilation crack; while the connected vehicle is awake the SDK leg
     * reproduces that intent with closed-loop 15% positioning.
     */
    public static final class VentAllWindowsCommand extends VehicleCommand {
        public static final int LOCAL_VENT_PERCENT = 15;

        public String name() { return "windows-vent-all"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
        public boolean preferLocalWhenOccupiedOrAwake() { return true; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "OPENWINDOW", null);
        }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.moveAllSideWindowsToPercent(LOCAL_VENT_PERCENT);
        }
        public CloudCapabilities.Feature cloudFeature() {
            return CloudCapabilities.Feature.WINDOWS_OPEN_VENT;
        }
        public boolean requiresKnownCloudFeature() { return true; }
        public boolean allowCloudFallbackFromMqtt() { return true; }
    }

    public static final class BatteryHeatCommand extends VehicleCommand {
        public final boolean enabled;
        public BatteryHeatCommand(boolean on) { this.enabled = on; }
        public String name() { return "battery-heat"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            JSONObject extra = new JSONObject();
            extra.put("batteryHeatSwitch", enabled ? 1 : 0);
            return remoteCommand(client, vin, "BATTERYHEAT", extra);
        }
        public CloudCapabilities.Feature cloudFeature() { return CloudCapabilities.Feature.BATTERY_HEAT; }
        public boolean requiresKnownCloudFeature() { return true; }
    }

    // ── Trunk: composite (cloud unlock + SDK tailgate) ──────────────────

    public static final class TrunkOpenCommand extends VehicleCommand {
        // Treated specially in execute() — see executeTrunkOpen().
        private final long stopGeneration;
        public TrunkOpenCommand() { this(-1L); }
        private TrunkOpenCommand(long stopGeneration) {
            this.stopGeneration = stopGeneration;
        }
        public String name() { return "trunk-open"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "OPENTRUNK", null);
        }
        /**
         * MQTT is intentionally SDK-only. Do not fire the tailgate motor until
         * the local lock rail explicitly says the vehicle is unlocked; UNKNOWN
         * is treated as locked because opening while locked can trigger the alarm.
         */
        public boolean executeViaSdk(BydDataCollector c) {
            return c.readDoorLockState() == BydDataCollector.DOOR_STATE_UNLOCK
                    && c.openTailgate();
        }
        public CloudCapabilities.Feature cloudFeature() { return CloudCapabilities.Feature.TRUNK_OPEN; }
        public MotionSafety motionSafety() { return MotionSafety.BLOCK_WHILE_MOVING; }
        public String motionSafetyGuardKey() { return DrivingSafetyGuard.GUARD_TRUNK; }
        public boolean allowCloudFallbackFromMqtt() { return true; }
    }

    public static final class TrunkCloseCommand extends VehicleCommand {
        public String name() { return "trunk-close"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "CLOSETRUNK", null);
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.closeTailgate(); }
        public CloudCapabilities.Feature cloudFeature() { return CloudCapabilities.Feature.TRUNK_CLOSE; }
        public MotionSafety motionSafety() { return MotionSafety.BLOCK_WHILE_MOVING; }
        public String motionSafetyGuardKey() { return DrivingSafetyGuard.GUARD_TRUNK; }
        public boolean allowCloudFallbackFromMqtt() { return true; }
    }

    /**
     * Legacy local-only tailgate open. New callers use {@link TrunkOpenCommand};
     * retain this class for binary compatibility, with the same locked-state
     * safety gate as the newer command.
     */
    public static final class TrunkOpenSdkCommand extends VehicleCommand {
        public String name() { return "trunk-open-sdk"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.readDoorLockState() == BydDataCollector.DOOR_STATE_UNLOCK
                    && c.openTailgate();
        }
        public MotionSafety motionSafety() { return MotionSafety.BLOCK_WHILE_MOVING; }
        public String motionSafetyGuardKey() { return DrivingSafetyGuard.GUARD_TRUNK; }
    }

    // ── Tier 2: local body comfort (reuse verified SDK setters) ─────────

    /** Sunroof open/close/stop — BYDAutoBodyworkDevice.voiceCtlMoonRoof (area 5). */
    public static final class SunroofCommand extends VehicleCommand {
        public final int command; // 1=open, 2=close, 3=stop
        public SunroofCommand(int c) { this.command = c; }
        public String name() { return "sunroof"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSunWindowCommand(5, command); }
    }

    /** Sunshade open/close/stop — BYDAutoBodyworkDevice.voiceCtlSunshadePanel (area 6). */
    public static final class SunshadeCommand extends VehicleCommand {
        public final int command;
        public SunshadeCommand(int c) { this.command = c; }
        public String name() { return "sunshade"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSunWindowCommand(6, command); }
    }

    /** Rear-door child lock (both sides) — BYDAutoDoorLockDevice feature write. */
    public static final class ChildLockCommand extends VehicleCommand {
        public final boolean enabled;
        public ChildLockCommand(boolean e) { this.enabled = e; }
        public String name() { return "child-lock"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            // Both sides must move together; there's no telemetry readback for child
            // lock, so a half-applied state (one door locked, one not) would never
            // reconcile. Attempt both (non-short-circuit), then retry only the side
            // that missed once, so a transient HAL miss self-corrects instead of
            // leaving the doors inconsistent. Returns true only if both ended set.
            boolean left = c.setChildLock(true, enabled);
            boolean right = c.setChildLock(false, enabled);
            if (!left) left = c.setChildLock(true, enabled);
            if (!right) right = c.setChildLock(false, enabled);
            return left && right;
        }
    }

    /** Fold / unfold the exterior rear-view mirrors (BODYWORK_REARVIEW_MIRROR_SET).
     *  fold=true → mirrors fold in, false → unfold. No telemetry readback exists for
     *  mirror-fold state (confirmed against the OEM firmware — set-only), so this is
     *  a fire-and-set command; the keymap/automation "toggle" is a blind flip via the
     *  last-commanded cache, not a live read. */
    public static final class MirrorFoldCommand extends VehicleCommand {
        public final boolean fold;
        public MirrorFoldCommand(boolean fold) { this.fold = fold; }
        public String name() { return "mirror-fold"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        /**
         * FOLDING is gated in motion — it removes the driver's rear-quarter vision mid-manoeuvre,
         * and there is no readback, so the UI cannot even show that it happened. UNFOLDING is
         * deliberately NOT gated: it is the recovery action, and blocking it would strand a driver
         * whose mirrors folded (from any source) with no way to restore them until stopped.
         */
        public MotionSafety motionSafety() {
            return fold ? MotionSafety.BLOCK_WHILE_MOVING : MotionSafety.UNRESTRICTED;
        }
        public String motionSafetyGuardKey() { return DrivingSafetyGuard.GUARD_MIRROR_FOLD; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setMirrorsFolded(fold);
        }
    }

    /**
     * Persist the OEM automatic exterior-mirror follow-up preference. Unlike
     * {@link MirrorFoldCommand}, this changes no physical mirror immediately: once enabled, the
     * vehicle performs the fold/unfold as part of its own power lifecycle.
     */
    public static final class MirrorAutoFollowUpCommand extends VehicleCommand {
        public final boolean enabled;
        public MirrorAutoFollowUpCommand(boolean enabled) { this.enabled = enabled; }
        public String name() { return "mirror-auto-follow-up"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setAutoExternalRearMirrorFollowUp(enabled);
        }
    }

    /** Phone wireless-charger pad on/off — BYDAutoChargingDevice feature write. */
    public static final class WirelessChargingCommand extends VehicleCommand {
        public final boolean enabled;
        public WirelessChargingCommand(boolean e) { this.enabled = e; }
        public String name() { return "wireless-charging"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setWirelessCharging(enabled); }
    }

    /** One wireless-charging pad (left/right) on trims with dual pads. SDK-only. */
    public static final class WirelessChargingPadCommand extends VehicleCommand {
        public final int pad;          // BydDataCollector.WIRELESS_PAD_LEFT / _RIGHT
        public final boolean enabled;
        public WirelessChargingPadCommand(int pad, boolean e) { this.pad = pad; this.enabled = e; }
        public String name() { return "wireless-charging-pad"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setWirelessChargingPad(pad, enabled); }
    }

    public static final class TrunkStopCommand extends VehicleCommand {
        /**
         * MQTT cancels tailgate opens before it queues STOP on its urgent worker. Do not perform a
         * second cancellation later because an OPEN received after that message is a newer intent.
         */
        private final boolean cancellationAlreadyApplied;
        public TrunkStopCommand() { this(false); }
        private TrunkStopCommand(boolean cancellationAlreadyApplied) {
            this.cancellationAlreadyApplied = cancellationAlreadyApplied;
        }
        public String name() { return "trunk-stop"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.stopTailgate(); }
    }

    // ── SDK-only commands ───────────────────────────────────────────────

    public static final class WindowMoveCommand extends VehicleCommand {
        public final int area; public final int action; public final Integer targetPercent;
        public WindowMoveCommand(int area, int action, Integer targetPercent) {
            this.area = area; this.action = action; this.targetPercent = targetPercent;
        }
        public String name() { return "window-move"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            if (targetPercent != null) return c.moveWindowToPercent(area, targetPercent);
            if (area == 0) return c.setAllWindowsCommand(action);
            return c.setWindowCommand(area, action);
        }
    }

    public static final class ClimateSetTempCommand extends VehicleCommand
            implements CabinClimateCommand {
        public final double tempCelsius; public final int zone;
        public ClimateSetTempCommand(int zone, double t) { this.zone = zone; this.tempCelsius = t; }
        public String name() { return "climate-temp"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean wakeVehicleIfOff() {
            return !Double.isNaN(tempCelsius) && !Double.isInfinite(tempCelsius);
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcTemperature(zone, tempCelsius); }
    }

    /**
     * RELATIVE temperature step (±N dial notches) — reads the dial, adds the delta, clamps,
     * writes. SDK_ONLY like the absolute setter: there is no cloud command that can express
     * "one degree warmer than whatever it is now", and a cloud leg would race the read.
     *
     * <p>The new setpoint is published to [resultSetpoint] so the caller can report it without
     * a second read (which could observe someone else's change and report a value this command
     * never wrote).
     */
    public static final class ClimateStepTempCommand extends VehicleCommand
            implements CabinClimateCommand {
        public final int zone, area, delta;
        /** The setpoint actually written, or UNAVAILABLE when the step didn't happen. */
        public volatile int resultSetpoint = com.overdrive.app.byd.BydVehicleData.UNAVAILABLE;
        public ClimateStepTempCommand(int zone, int area, int delta) {
            this.zone = zone; this.area = area; this.delta = delta;
        }
        public String name() { return "climate-temp-step"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            int result = c.stepAcTemperature(zone, area, delta);
            resultSetpoint = result;
            return result != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE;
        }
    }

    public static final class ClimateSetFanCommand extends VehicleCommand
            implements CabinClimateCommand {
        public final int level;
        public ClimateSetFanCommand(int l) { this.level = l; }
        public String name() { return "climate-fan"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcFanLevel(level); }
    }

    /**
     * RELATIVE fan step (±N levels) — reads the level, adds the delta, clamps to 1..7, writes.
     * SDK_ONLY like the absolute setter, for the same reason as {@link ClimateStepTempCommand}:
     * no cloud command can express "one level faster than now". The written level is published
     * to [resultLevel] so the caller can report it without a second read.
     */
    public static final class ClimateStepFanCommand extends VehicleCommand {
        public final int delta;
        /** The level actually written, or UNAVAILABLE when the step didn't happen. */
        public volatile int resultLevel = com.overdrive.app.byd.BydVehicleData.UNAVAILABLE;
        public ClimateStepFanCommand(int delta) { this.delta = delta; }
        public String name() { return "climate-fan-step"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            int result = c.stepAcFanLevel(delta);
            resultLevel = result;
            return result != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE;
        }
    }

    /**
     * Seat heat / ventilation — SDK first, with a composite BYD
     * VENTILATIONHEATING fallback when the local actuator is unavailable.
     *
     * <p>The cloud command is stateful: it requires the FULL snapshot of all
     * seat states. We track driver+passenger heat+vent locally; the constructor
     * captures the rest of the state at the moment the command is built so
     * unchanged seats retain their level.
     */
    /** Cabin HVAC writes. They run one at a time so none can land inside a power-on wake. */
    private interface CabinClimateCommand {}

    private interface SeatClimateCommand {
        int targetLevel();
        int targetStateIndex();
        int[] freshSeatState();
        boolean hasFreshSeatSnapshot();
        long freshSeatSnapshotAtMs();
        String cloudChairType();
        /** Explicit 1=on/3=off for chairType=5, or -1 to preserve cloud state. */
        int explicitSteeringWheelHeatWireState();
    }

    public static final class SeatHeatCommand extends VehicleCommand implements SeatClimateCommand {
        public final int position; public final int level;
        public final int driverHeat, driverVent, passengerHeat, passengerVent;
        private final boolean cloudFallbackSafe;
        private final long seatSnapshotAtMs;
        public SeatHeatCommand(int p, int l, int dh, int dv, int ph, int pv) {
            this(p, l, dh, dv, ph, pv, false, 0L);
        }
        public SeatHeatCommand(int p, int l, int dh, int dv, int ph, int pv,
                               boolean allowCloudFallback) {
            this(p, l, dh, dv, ph, pv, allowCloudFallback, System.currentTimeMillis());
        }
        public SeatHeatCommand(int p, int l, int dh, int dv, int ph, int pv,
                               boolean allowCloudFallback, long snapshotAtMs) {
            this.position = p;
            this.level = normalizeLegacySeatLevel(l);
            this.driverHeat = normalizeLegacySeatLevel(dh);
            this.driverVent = normalizeLegacySeatLevel(dv);
            this.passengerHeat = normalizeLegacySeatLevel(ph);
            this.passengerVent = normalizeLegacySeatLevel(pv);
            this.seatSnapshotAtMs = snapshotAtMs;
            // The router independently validates a complete local OR cloud
            // composite snapshot immediately before dispatch. Retaining the
            // request intent here lets an asleep car use a fresh cloud
            // vehicleInfo snapshot without trusting these stale constructor
            // values as a fallback payload.
            this.cloudFallbackSafe = allowCloudFallback;
        }
        public String name() { return "seat-heat"; }
        public Capability cloudCapability() {
            return cloudFallbackSafe ? Capability.AVAILABLE : Capability.NONE;
        }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        // SDK_FIRST (not CLOUD_FIRST): the local setSeatHeatingState(position,level) is
        // genuinely PER-SEAT, whereas the cloud VENTILATIONHEATING command is COMPOSITE —
        // it writes mainHeat AND copilotHeat together from the (possibly stale) snapshot,
        // so a "driver heat on" cloud call would also drive the passenger seat. Preferring
        // the SDK path fixes the reported "driver seat heating turns on both seats" bug;
        // cloud stays as the fallback for when the local write is refused (e.g. parked).
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) {
            // runCloudCall constructs a state-preserving composite, including
            // the current steering-wheel field, immediately before dispatch.
            return CloudOutcome.unsupported();
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSeatHeating(position, level); }
        public CloudCapabilities.Feature cloudFeature() {
            return position == 1
                    ? CloudCapabilities.Feature.SEAT_DRIVER
                    : CloudCapabilities.Feature.SEAT_PASSENGER;
        }
        public boolean requiresKnownCloudFeature() { return true; }
        public boolean allowCloudFallbackFromMqtt() { return cloudFallbackSafe; }
        public int targetLevel() { return level; }
        public int targetStateIndex() { return position == 1 ? 0 : 2; }
        public int[] freshSeatState() {
            return new int[] { driverHeat, driverVent, passengerHeat, passengerVent };
        }
        public boolean hasFreshSeatSnapshot() { return cloudFallbackSafe; }
        public long freshSeatSnapshotAtMs() { return seatSnapshotAtMs; }
        public String cloudChairType() { return position == 1 ? "1" : "2"; }
        public int explicitSteeringWheelHeatWireState() { return -1; }
    }

    public static final class SeatVentCommand extends VehicleCommand implements SeatClimateCommand {
        public final int position; public final int level;
        public final int driverHeat, driverVent, passengerHeat, passengerVent;
        private final boolean cloudFallbackSafe;
        private final long seatSnapshotAtMs;
        public SeatVentCommand(int p, int l, int dh, int dv, int ph, int pv) {
            this(p, l, dh, dv, ph, pv, false, 0L);
        }
        public SeatVentCommand(int p, int l, int dh, int dv, int ph, int pv,
                               boolean allowCloudFallback) {
            this(p, l, dh, dv, ph, pv, allowCloudFallback, System.currentTimeMillis());
        }
        public SeatVentCommand(int p, int l, int dh, int dv, int ph, int pv,
                               boolean allowCloudFallback, long snapshotAtMs) {
            this.position = p;
            this.level = normalizeLegacySeatLevel(l);
            this.driverHeat = normalizeLegacySeatLevel(dh);
            this.driverVent = normalizeLegacySeatLevel(dv);
            this.passengerHeat = normalizeLegacySeatLevel(ph);
            this.passengerVent = normalizeLegacySeatLevel(pv);
            this.seatSnapshotAtMs = snapshotAtMs;
            this.cloudFallbackSafe = allowCloudFallback;
        }
        public String name() { return "seat-vent"; }
        public Capability cloudCapability() {
            return cloudFallbackSafe ? Capability.AVAILABLE : Capability.NONE;
        }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        // SDK_FIRST — same rationale as SeatHeatCommand: the local setSeatVentilation
        // (position,level) is per-seat; the cloud path is a composite that would drive
        // both seats. Prefer the per-seat SDK write; cloud is the fallback.
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) {
            // See SeatHeatCommand: bypassing runCloudCall would lose the
            // fresh full composite and could overwrite sibling controls.
            return CloudOutcome.unsupported();
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSeatVentilation(position, level); }
        public CloudCapabilities.Feature cloudFeature() {
            return position == 1
                    ? CloudCapabilities.Feature.SEAT_DRIVER
                    : CloudCapabilities.Feature.SEAT_PASSENGER;
        }
        public boolean requiresKnownCloudFeature() { return true; }
        public boolean allowCloudFallbackFromMqtt() { return cloudFallbackSafe; }
        public int targetLevel() { return level; }
        public int targetStateIndex() { return position == 1 ? 1 : 3; }
        public int[] freshSeatState() {
            return new int[] { driverHeat, driverVent, passengerHeat, passengerVent };
        }
        public boolean hasFreshSeatSnapshot() { return cloudFallbackSafe; }
        public long freshSeatSnapshotAtMs() { return seatSnapshotAtMs; }
        public String cloudChairType() { return position == 1 ? "1" : "2"; }
        public int explicitSteeringWheelHeatWireState() { return -1; }
    }

    /**
     * Driver-seat memory: recall (move to a stored slot) or save (store the current
     * position into a slot). Both slots are 1-2. SDK-only — the driver-seat memory
     * ids live on the setting HAL and have no BYD cloud remote-control equivalent.
     * {@code save=false} recalls (setSeatMemoryPosition / WAKE id); {@code save=true}
     * stores (setSeatMemorySave / SET id).
     */
    public static final class SeatMemoryCommand extends VehicleCommand {
        public final int position;
        public final boolean save;
        /** Recall a stored slot (backwards-compatible constructor). */
        public SeatMemoryCommand(int p) { this(p, false); }
        public SeatMemoryCommand(int p, boolean save) { this.position = p; this.save = save; }
        public String name() { return save ? "seat-memory-save" : "seat-memory-recall"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        /**
         * RECALL is gated in motion: it drives the DRIVER's seat rails and backrest to a stored
         * position, moving the person operating the vehicle away from the pedals and wheel — a
         * larger hazard than the trunk, which is already gated. It is bindable to a physical key,
         * so an accidental press while driving is the likely case, not an exotic one. SAVE only
         * records the current position and moves nothing, so it stays unrestricted.
         */
        public MotionSafety motionSafety() {
            return save ? MotionSafety.UNRESTRICTED : MotionSafety.BLOCK_WHILE_MOVING;
        }
        public String motionSafetyGuardKey() { return DrivingSafetyGuard.GUARD_POSITIONING; }
        public boolean executeViaSdk(BydDataCollector c) {
            return save ? c.setSeatMemorySave(position) : c.setSeatMemoryPosition(position);
        }
    }

    public static final class LightsCommand extends VehicleCommand {
        public final boolean drlOn;
        public LightsCommand(boolean on) { this.drlOn = on; }
        public String name() { return "lights"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setDayTimeLight(drlOn); }
    }

    /**
     * OEM four-position exterior-light selector. Local SDK only: the persistent headlight
     * selector has no BYD Cloud equivalent (cloud flash/find-car commands are momentary).
     *
     * <p>Only OFF is Park-gated. AUTO, parking lights and low beam restore or increase
     * visibility, so blocking those modes while moving would prevent recovery from an
     * accidental or stale selector state.
     */
    public static final class HeadlightModeCommand extends VehicleCommand {
        public final int mode;
        public HeadlightModeCommand(int mode) { this.mode = mode; }
        public String name() { return "headlight-mode"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public MotionSafety motionSafety() {
            return mode == BydDataCollector.HEADLIGHT_MODE_OFF
                    ? MotionSafety.BLOCK_WHILE_MOVING
                    : MotionSafety.UNRESTRICTED;
        }
        public String motionSafetyGuardKey() { return DrivingSafetyGuard.GUARD_HEADLIGHT_OFF; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setHeadlightMode(mode);
        }
    }

    /**
     * Hazard (double-flash) lights on/off. Local SDK only — no cloud hazard command
     * exists (the cloud "flash" is the momentary locate-flash, not a sustained toggle).
     * Actuation is UNCONFIRMED on this platform (inferred feature id, no OEM precedent)
     * — {@link BydDataCollector#setHazardLights} returns false if the HAL rejects it, so
     * a failed write surfaces honestly rather than silently no-op'ing.
     */
    public static final class HazardCommand extends VehicleCommand {
        public final boolean on;
        public HazardCommand(boolean on) { this.on = on; }
        public String name() { return "hazard"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setHazardLights(on); }
    }

    /** One turn indicator on/off. Same class of control as {@link HazardCommand}. */
    public static final class TurnSignalCommand extends VehicleCommand {
        public final int side;
        public final boolean on;
        public TurnSignalCommand(int side, boolean on) { this.side = side; this.on = on; }
        public String name() { return "turn-signal"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return com.overdrive.app.byd.TurnSignalControl.set(c, side, on);
        }
    }

    public static final class AmbientColourCommand extends VehicleCommand {
        public final int colour;
        public final String zone; // front/rear/both — null defaults to both (whole cabin)
        /** 0xRRGGBB to match against the car's palette, or -1 for a palette index request. */
        public final int rgb;
        /** The 1-based colour actually written, published so the caller can report it. */
        public volatile int resultColour = com.overdrive.app.byd.BydVehicleData.UNAVAILABLE;
        public AmbientColourCommand(int colour) { this(colour, "both"); }
        public AmbientColourCommand(int colour, String zone) { this(colour, zone, -1); }
        private AmbientColourCommand(int colour, String zone, int rgb) {
            this.colour = colour;
            this.zone = (zone == null || zone.isEmpty()) ? "both" : zone;
            this.rgb = rgb;
        }
        public static AmbientColourCommand rgb(int rgb, String zone) {
            return new AmbientColourCommand(0, zone, rgb & 0xFFFFFF);
        }
        public String name() { return "ambient"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            if (rgb >= 0) {
                int written = c.setAmbientLightRgb(zone, rgb);
                if (written > 0) resultColour = written;
                return written > 0;
            }
            boolean ok = c.setAmbientLightZoned(zone, colour);
            if (ok) resultColour = colour;
            return ok;
        }
    }

    /** AC auto mode on/off (feature-id Ac.AUTO_MODE_SET). SDK-only. */
    public static final class AcAutoModeCommand extends VehicleCommand
            implements CabinClimateCommand {
        public final boolean on;
        public AcAutoModeCommand(boolean on) { this.on = on; }
        public String name() { return "ac-auto"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcAutoMode(on); }
    }

    /** Link or separate the driver/passenger AC temperature zones. SDK-only. */
    public static final class AcTemperatureSyncCommand extends VehicleCommand
            implements CabinClimateCommand {
        public final boolean synced;
        public AcTemperatureSyncCommand(boolean synced) { this.synced = synced; }
        public String name() { return "ac-temperature-sync"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcTemperatureSync(synced); }
    }

    /** Air intake: recirculate cabin air vs draw fresh outside air (feature-id
     *  Ac.CYCLE_MODE_SET, FRESH_AIR=0 / RECIRCULATION=1). SDK-only. */
    public static final class AcRecirculationCommand extends VehicleCommand
            implements CabinClimateCommand {
        public final boolean recirculate;
        public AcRecirculationCommand(boolean recirculate) { this.recirculate = recirculate; }
        public String name() { return "ac-recirculation"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcRecirculation(recirculate); }
    }

    /** Front windscreen defrost/demist on/off (feature-id Ac.DEFROST_FRONT_SET). SDK-only —
     *  no BYD cloud remote-control equivalent; same AC-device routing as recirculation. */
    public static final class FrontDefrostCommand extends VehicleCommand
            implements CabinClimateCommand {
        public final boolean on;
        public FrontDefrostCommand(boolean on) { this.on = on; }
        public String name() { return "defrost-front"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setFrontDefrost(on); }
    }

    /** Rear windscreen defrost/demist on/off (feature-id Ac.DEFROST_REAR_SET). SDK-only. */
    public static final class RearDefrostCommand extends VehicleCommand
            implements CabinClimateCommand {
        public final boolean on;
        public RearDefrostCommand(boolean on) { this.on = on; }
        public String name() { return "defrost-rear"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setRearDefrost(on); }
    }

    /** Fan-only (ventilation, no compressor) on/off. SDK-only, named-method HAL call. */
    public static final class FanOnlyModeCommand extends VehicleCommand
            implements CabinClimateCommand {
        public final boolean on;
        public FanOnlyModeCommand(boolean on) { this.on = on; }
        public String name() { return "fan-only"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setFanOnlyMode(on); }
    }

    public static final class AcMaxCoolingCommand extends VehicleCommand
            implements CabinClimateCommand {
        public final boolean on;
        public AcMaxCoolingCommand(boolean on) { this.on = on; }
        public String name() { return "ac-max-cooling"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcMaxCooling(on); }
    }

    public static final class AirPurifyCommand extends VehicleCommand
            implements CabinClimateCommand {
        public final boolean on;
        public AirPurifyCommand(boolean on) { this.on = on; }
        public String name() { return "air-purify"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAirPurify(on); }
    }

    /** Persistent setting: the vehicle closes its windows itself when rain is sensed. */
    public static final class RainCloseWindowCommand extends VehicleCommand {
        public final boolean on;
        public RainCloseWindowCommand(boolean on) { this.on = on; }
        public String name() { return "rain-close-windows"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setRainCloseWindow(on); }
    }

    /**
     * Steering-wheel heating, SDK-first with a composite-cloud fallback.
     * The cloud leg is available only when it can preserve all four front-seat
     * channels from a fresh local or cloud snapshot.
     */
    public static final class SteeringWheelHeatCommand extends VehicleCommand
            implements SeatClimateCommand {
        public final boolean on;
        public SteeringWheelHeatCommand(boolean on) { this.on = on; }
        public String name() { return "steering-heat"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        /**
         * runCloudCall handles all SeatClimateCommand instances together so
         * the full seat snapshot and explicit wheel target are sent atomically.
         */
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) {
            return CloudOutcome.unsupported();
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSteeringWheelHeating(on); }
        public CloudCapabilities.Feature cloudFeature() {
            return CloudCapabilities.Feature.SEAT_STEERING_WHEEL;
        }
        public boolean requiresKnownCloudFeature() { return true; }
        public boolean allowCloudFallbackFromMqtt() { return true; }
        public int targetLevel() { return on ? 2 : 0; }
        public int targetStateIndex() { return -1; }
        public int[] freshSeatState() { return null; }
        public boolean hasFreshSeatSnapshot() { return false; }
        public long freshSeatSnapshotAtMs() { return 0L; }
        public String cloudChairType() { return "5"; }
        public int explicitSteeringWheelHeatWireState() { return on ? 1 : 3; }
    }

    /** Smart welcome-light on/off (setting HAL, on=1/off=2). SDK-only. */
    public static final class WelcomeLightCommand extends VehicleCommand {
        public final boolean on;
        public WelcomeLightCommand(boolean on) { this.on = on; }
        public String name() { return "welcome-light"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setWelcomeLight(on); }
    }

    /** Interior reading light on/off (body feature-id, on=1/off=2). SDK-only. */
    public static final class ReadingLightCommand extends VehicleCommand {
        public final boolean on;
        public ReadingLightCommand(boolean on) { this.on = on; }
        public String name() { return "reading-light"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setReadingLight(on); }
    }

    /** DI5 on-car calibration: one raw cabin-light write ({@code /api/debug/light/cabin}). */
    public static final class CabinLightProbeCommand extends VehicleCommand {
        public final String api;
        public final int value;
        public CabinLightProbeCommand(String api, int value) { this.api = api; this.value = value; }
        public String name() { return "cabin-light-probe"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.probeDiLink5CabinLight(api, value); }
    }

    /**
     * Interior-ambient brightness as a 0-100 percent (converted to the SDK's 0..5 level by the
     * collector), per zone. SDK-only.
     */
    public static final class AmbientBrightnessCommand extends VehicleCommand {
        public final int percent;
        public final String zone; // front/rear/both — null/empty defaults to both (whole cabin)
        public AmbientBrightnessCommand(int percent) { this(percent, "both"); }
        public AmbientBrightnessCommand(int percent, String zone) {
            this.percent = percent;
            this.zone = (zone == null || zone.isEmpty()) ? "both" : zone;
        }
        public String name() { return "ambient-brightness"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setAmbientBrightnessZoned(zone, percent);
        }
    }

    /**
     * Interior-ambient main switch on/off. SDK-only, and zone-aware: "both" drives the real
     * GLOBAL main switch (three-tier chain), while a single zone has no dedicated switch on
     * this platform so off/on dims that zone out and restores it.
     */
    public static final class AmbientPowerCommand extends VehicleCommand {
        public final boolean on;
        public final String zone; // front/rear/both — null/empty defaults to both (whole cabin)
        public AmbientPowerCommand(boolean on) { this(on, "both"); }
        public AmbientPowerCommand(boolean on, String zone) {
            this.on = on;
            this.zone = (zone == null || zone.isEmpty()) ? "both" : zone;
        }
        public String name() { return "ambient-power"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setAmbientLightEnabledZoned(zone, on);
        }
    }

    /** Head-up display brightness, executed through the selected vehicle backend. */
    public static final class HudBrightnessCommand extends VehicleCommand {
        public final int percent;
        public HudBrightnessCommand(int percent) { this.percent = percent; }
        public String name() { return "hud-brightness"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public MotionSafety motionSafety() { return MotionSafety.BLOCK_WHILE_MOVING; }
        public String motionSafetyGuardKey() {
            return DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS;
        }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setHudBrightness(percent);
        }
    }

    /** Driver-cluster brightness; routed only on DiLink 5, whose daemon has no car adapter. */
    public static final class DriverDisplayBrightnessCommand extends VehicleCommand {
        public final int percent;
        public DriverDisplayBrightnessCommand(int percent) { this.percent = percent; }
        public String name() { return "driver-display-brightness"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public MotionSafety motionSafety() { return MotionSafety.BLOCK_WHILE_MOVING; }
        public String motionSafetyGuardKey() {
            return DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS;
        }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setDriverDisplayBrightness(percent);
        }
    }

    /** Dedicated head-up display power switch, distinct from brightness. */
    public static final class HudPowerCommand extends VehicleCommand {
        public final boolean on;
        public HudPowerCommand(boolean on) { this.on = on; }
        public String name() { return "hud-power"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public MotionSafety motionSafety() {
            return on ? MotionSafety.UNRESTRICTED : MotionSafety.BLOCK_WHILE_MOVING;
        }
        public String motionSafetyGuardKey() {
            return DrivingSafetyGuard.GUARD_DISPLAY_POWER;
        }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setHudPower(on);
        }
    }

    /** Ambient-light music mode on/off (body feature-id, on=1/off=2). SDK-only. */
    public static final class AmbientMusicModeCommand extends VehicleCommand {
        public final boolean on;
        public AmbientMusicModeCommand(boolean on) { this.on = on; }
        public String name() { return "ambient-music"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAmbientMusicMode(on); }
    }

    /** Headlight (headlamp) level / height (setting HAL, clamped 1..11). SDK-only. */
    public static final class HeadlightLevelCommand extends VehicleCommand {
        public final int level;
        public HeadlightLevelCommand(int level) { this.level = level; }
        public String name() { return "headlight-level"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setHeadlightLevel(level); }
    }

    public static final class AdasSpeedLimitWarningCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasSpeedLimitWarningCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-slw"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSpeedLimitWarning(enabled); }
    }

    /**
     * Electronic Stability Program (ESP / ESC) on/off. SDK-only — the ESP feature
     * id lives on the setting HAL (family-consistent with DOW/RCW) and has no BYD
     * cloud remote-control equivalent. SAFETY control: enabled=true restores
     * stability control, false disables it. On many vehicles the HAL/ECU re-enables
     * ESP at the next ignition cycle regardless of this write.
     */
    public static final class AdasEspCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasEspCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-esp"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setEspState(enabled); }
    }

    /**
     * iTAC (Intelligent Torque Adaption Control) on/off. SDK-only — the iTAC feature
     * id lives on the setting HAL and has no BYD cloud remote-control equivalent.
     * Performance/traction feature, distinct from the ESP stability interlock.
     */
    public static final class AdasItacCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasItacCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-itac"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setItacState(enabled); }
    }

    /**
     * Lane-assist mode (Lane Departure Warning / Prevention) via
     * BYDAutoADASDevice.setLKSMode. SDK-only — a dedicated ADAS-device method, no BYD
     * cloud equivalent. This is a MULTI-mode control (not on/off): app-level mode
     * 0=Off, 1=LDW, 2=LDP, 3=LDW+LDP (BydDataCollector maps to the MCU values).
     */
    public static final class AdasLaneAssistCommand extends VehicleCommand {
        public final int mode;
        public AdasLaneAssistCommand(int mode) { this.mode = mode; }
        public String name() { return "adas-lane-assist"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setLaneAssistMode(mode); }
    }

    public static final class SettingChildPresenceDetectionCommand extends VehicleCommand {
        public final int value;
        public SettingChildPresenceDetectionCommand(int value) { this.value = value; }
        public String name() { return "setting-cpd"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setChildPresenceDetection(value); }
    }

    /** DiLink 5 only: app-process half of the setting-cpd DiPilot fallback; never routed directly. */
    public static final class DiLink5DiPilotCpdCommand extends VehicleCommand {
        public final int value;
        public DiLink5DiPilotCpdCommand(int value) { this.value = value; }
        public String name() { return "setting-cpd"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.writeDiLink5DiPilotCpd(value); }
    }

    // ── Expanded ADAS matrix (all SDK-only, on adasDevice via BydDataCollector) ──
    // Warning/info toggles are low-risk; the auto-brake / lane-keep ones are SAFETY
    // controls (disabling reduces protection) and are labelled as such at the action
    // layer. AEB is exposed ENABLE-ONLY (its action never sends a disable).

    public static final class AdasBlindSpotCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasBlindSpotCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-bsd"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setBlindSpotDetection(enabled); }
    }

    public static final class AdasTrafficSignCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasTrafficSignCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-tsr"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setTrafficSignRecognition(enabled); }
    }

    public static final class AdasRearCrossTrafficCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasRearCrossTrafficCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-rcta"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setRearCrossTrafficAlert(enabled); }
    }

    public static final class AdasFrontCrossTrafficCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasFrontCrossTrafficCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-fcta"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setFrontCrossTrafficAlert(enabled); }
    }

    public static final class AdasTrafficLightAttentionCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasTrafficLightAttentionCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-tla"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setTrafficLightAttention(enabled); }
    }

    public static final class AdasOpenDoorWarningCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasOpenDoorWarningCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-dow"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setOpenDoorWarning(enabled); }
    }

    public static final class AdasRearCollisionWarningCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasRearCollisionWarningCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-rcw"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setRearCollisionWarning(enabled); }
    }

    public static final class AdasSpeedLimitControlCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasSpeedLimitControlCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-islc"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSpeedLimitControl(enabled); }
    }

    /** Emergency/urgent lane keeping — SAFETY (autonomous steering intervention). */
    public static final class AdasEmergencyLaneKeepCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasEmergencyLaneKeepCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-elka"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setEmergencyLaneKeeping(enabled); }
    }

    /** Rear cross-traffic BRAKE — SAFETY (autonomous braking). */
    public static final class AdasRearCrossBrakeCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasRearCrossBrakeCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-rctb"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setRearCrossTrafficBraking(enabled); }
    }

    /** Front cross-traffic BRAKE — SAFETY (autonomous braking). */
    public static final class AdasFrontCrossBrakeCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasFrontCrossBrakeCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-fctb"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setFrontCrossTrafficBraking(enabled); }
    }

    /**
     * Forward Collision Warning sensitivity LEVEL (multi-mode, not on/off): app-level
     * 0=Off/1=Low/2=Med/3=High. SAFETY — lowering delays collision warnings.
     */
    public static final class AdasFcwLevelCommand extends VehicleCommand {
        public final int level;
        public AdasFcwLevelCommand(int level) { this.level = level; }
        public String name() { return "adas-fcw"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setFcwLevel(level); }
    }

    /** Automatic Emergency Braking — SAFETY-CRITICAL, ENABLE-ONLY at the action layer. */
    public static final class AdasEmergencyBrakingCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasEmergencyBrakingCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-aeb"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setEmergencyBraking(enabled); }
    }

    /**
     * Smart-charging schedule — BYD cloud /control/smartCharge/saveOrUpdate.
     * Wire-compatible with pyBYD's trigger_save_charging_schedule.
     */
    public static final class ChargeScheduleCommand extends VehicleCommand {
        public final String startChargeTime;
        public final String endChargeTime;
        public final String chargeWay;
        public final boolean enabled;
        public ChargeScheduleCommand(String start, String end, String chargeWay, boolean enabled) {
            this.startChargeTime = start;
            this.endChargeTime = end;
            this.chargeWay = chargeWay;
            this.enabled = enabled;
        }
        public String name() { return "charge-schedule"; }
        // Cloud stays primary; the SDK leg is a local fallback for an offline /
        // account-less head unit. CLOUD_FIRST routing tries cloud, then local.
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
        public boolean requiresControlPin() { return false; }
        public boolean requiresKnownCloudFeature() { return true; }
        public long cloudTimeoutMs() { return 30_000L; }
        public CloudCapabilities.Feature cloudFeature() {
            return CloudCapabilities.Feature.SMART_CHARGING;
        }
        public boolean allowCloudFallbackFromMqtt() { return true; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            boolean ok = client.saveChargingSchedule(vin, startChargeTime, endChargeTime, chargeWay, enabled);
            return ok ? CloudOutcome.success() : CloudOutcome.failed();
        }
        public boolean executeViaSdk(BydDataCollector collector) {
            return collector.saveChargingScheduleLocal(startChargeTime, endChargeTime, chargeWay, enabled);
        }
    }

    /** Generic charge limit via the verified charge-stop backend (50..100%). */
    public static final class ChargeCapPercentCommand extends VehicleCommand {
        public final int percent;
        public ChargeCapPercentCommand(int p) { this.percent = p; }
        public String name() { return "charge-cap-percent"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setChargeCapPercent(percent); }
    }

    /** Generic charge-limit master switch with verified charge-stop readback. */
    public static final class ChargeCapToggleCommand extends VehicleCommand {
        public final boolean enabled;
        public ChargeCapToggleCommand(boolean on) { this.enabled = on; }
        public String name() { return "charge-cap-toggle"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setChargeCapEnabled(enabled); }
    }

    /** OEM five-position AC inlet current limit: 1=6A, 2=8A, 3=10A, 4=16A, 5=max. */
    public static final class AcChargeCurrentLimitCommand extends VehicleCommand {
        public final int state;
        public AcChargeCurrentLimitCommand(int state) { this.state = state; }
        public String name() { return "ac-charge-current-limit"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setAcChargingCurrentLimitState(state);
        }
    }

    /**
     * PHEV battery-hold preset: one command that writes BOTH legs of the SOC-hold pair in the
     * OEM's order (target, then switch), replicating the OEM's two DM-i presets.
     * This is the genuine "hold my charge" lever — unlike {@code hold_battery}, which is only
     * an alias for energy-mode HEV and starts the engine to RECHARGE the pack.
     *
     * <p>{@code atCurrent=true} → hold at min(current SOC, 50) with switch mode 2 (the Highway
     * "save what I have" behaviour). {@code false} → target this trim's floor with switch mode 1
     * (the City "let it deplete" behaviour).
     */
    public static final class SocHoldPresetCommand extends VehicleCommand {
        public final boolean atCurrent;
        public SocHoldPresetCommand(boolean atCurrent) { this.atCurrent = atCurrent; }
        public String name() { return atCurrent ? "soc-hold-at-current" : "soc-hold-at-floor"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return atCurrent ? c.applySocHoldAtCurrent() : c.applySocHoldAtFloor();
        }
    }

    /**
     * Turn SOC hold on/off without changing the configured target. Enabling first verifies that
     * the vehicle reports a valid saved target; disabling leaves that target untouched.
     */
    public static final class SocHoldToggleCommand extends VehicleCommand {
        public final boolean enabled;
        public SocHoldToggleCommand(boolean on) { this.enabled = on; }
        public String name() { return "soc-hold-toggle"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return enabled ? c.enableSocHoldAtSavedTarget() : c.clearSocHold();
        }
    }

    /** PHEV driving SOC target via BYDAutoSettingDevice.setSOCTarget (15..70%). */
    public static final class SocTargetPercentCommand extends VehicleCommand {
        public final int percent;
        public SocTargetPercentCommand(int percent) { this.percent = percent; }
        public String name() { return "soc-target-percent"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setSocTargetPercent(percent);
        }
    }

    /** Smart-charge master switch — BYD cloud /control/smartCharge/changeChargeStatue. */
    public static final class SmartChargingToggleCommand extends VehicleCommand {
        public final boolean enabled;
        public SmartChargingToggleCommand(boolean on) { this.enabled = on; }
        public String name() { return "smart-charging-toggle"; }
        // Cloud primary, local SDK fallback (see ChargeScheduleCommand).
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
        public boolean requiresControlPin() { return false; }
        public boolean requiresKnownCloudFeature() { return true; }
        public long cloudTimeoutMs() { return 30_000L; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            boolean ok = client.toggleSmartCharging(vin, enabled);
            return ok ? CloudOutcome.success() : CloudOutcome.failed();
        }
        public boolean executeViaSdk(BydDataCollector collector) {
            return collector.setSmartChargingEnabledLocal(enabled);
        }
        public CloudCapabilities.Feature cloudFeature() {
            return CloudCapabilities.Feature.SMART_CHARGING;
        }
        public boolean allowCloudFallbackFromMqtt() { return true; }
    }

    /** Cloud immediate-start charge. There is deliberately no stop counterpart. */
    public static final class StartChargingNowCommand extends VehicleCommand {
        public String name() { return "start-charging-now"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public boolean requiresControlPin() { return false; }
        public boolean requiresKnownCloudFeature() { return true; }
        public long cloudTimeoutMs() { return 30_000L; }
        public CloudCapabilities.Feature cloudFeature() {
            return CloudCapabilities.Feature.SMART_CHARGING;
        }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return client.startChargingNow(vin) ? CloudOutcome.success() : CloudOutcome.failed();
        }
        public boolean allowCloudFallbackFromMqtt() { return true; }
    }

    /**
     * Local CAN-backed in-car setting write via the BYD carsettings provider
     * ({@link com.overdrive.app.byd.BydCarSettings}). SDK/local-only — never cloud.
     * Only allowlisted keys with in-domain values are accepted (validated downstream).
     */
    public static final class CarSettingCommand extends VehicleCommand {

        public final String key; public final int value;
        public CarSettingCommand(String key, int value) { this.key = key; this.value = value; }
        public String name() { return "car-setting:" + key; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return com.overdrive.app.byd.BydCarSettings.getInstance().writeInt(key, value);
        }
    }

    /** Drive mode on OverDrive's config axis: 1=NORMAL, 2=ECO, 3=SPORT, 4=SNOW.
     *  Routed via {@link BydDataCollector#setDriveConfigMode(int)}, which selects the OEM
     *  Energy, road-surface, or setting-device axis for that mode. */
    public static final class OperationModeCommand extends VehicleCommand {
        public final int mode;
        public OperationModeCommand(int mode) { this.mode = mode; }
        public String name() { return "drive-config-mode"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setDriveConfigMode(mode); }
    }

    /** Powertrain mode: EV vs HEV on fuel-capable hybrids. SDK-only. */
    public static final class EnergyModeCommand extends VehicleCommand {
        public final int mode;
        public EnergyModeCommand(int mode) { this.mode = mode; }
        public String name() { return "energy-mode"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setEnergyMode(mode); }
    }

    /** Centre infotainment panel orientation: horizontal or vertical. SDK-only. */
    public static final class InfotainmentRotationCommand extends VehicleCommand {
        public final int rotation;
        public InfotainmentRotationCommand(int rotation) { this.rotation = rotation; }
        public String name() { return "infotainment-rotation"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setPadRotation(rotation); }
    }

    /**
     * Select a view in the OEM native panorama camera app using its AUTO_VIDEO_BUTTON broadcast.
     * This is distinct from OverDrive's camera overlay/viewpoint controls.
     */
    public static final class NativeCameraViewCommand extends VehicleCommand {
        public final int viewCode;
        public NativeCameraViewCommand(int viewCode) { this.viewCode = viewCode; }
        public String name() { return "native-camera-view"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setNativeCameraView(viewCode);
        }
    }

    /** Energy recuperation / regen strength. SDK-only. */
    public static final class EnergyFeedbackCommand extends VehicleCommand {
        public final int level;
        public EnergyFeedbackCommand(int level) { this.level = level; }
        public String name() { return "energy-feedback"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setEnergyFeedback(level); }
    }

    /** Steering-assist weighting: comfort vs sport. SDK-only. */
    public static final class SteerAssistCommand extends VehicleCommand {
        public final int mode;
        public SteerAssistCommand(int mode) { this.mode = mode; }
        public String name() { return "steer-assist"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSteerAssist(mode); }
    }

    /**
     * Brake-pedal feel: comfort vs sport/strong. SDK-only (BYDAutoADASDevice
     * setBrakeFootSenseState). {@code level} is the app-level convention
     * 0=comfort/1=sport; the collector maps it to the HAL value.
     */
    public static final class BrakeFeelCommand extends VehicleCommand {
        public final int level;
        public BrakeFeelCommand(int level) { this.level = level; }
        public String name() { return "brake-feel"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setBrakeFootSense(level); }
    }

    // ── Routing ─────────────────────────────────────────────────────────

    public CommandResult execute(VehicleCommand cmd) {
        // Snapshot at ingress before any safety/preflight work can yield. MQTT may provide
        // a still-earlier message-ingress snapshot, so an already-queued open cannot overtake
        // a STOP while its per-connection worker is busy.
        long tailgateOpenStopGeneration = tailgateOpenStopGeneration(cmd);
        cmd.beginLocalAttempt();
        if (cmd instanceof SeatClimateCommand) {
            synchronized (seatCommandLock) {
                return executeUnserialized(cmd, tailgateOpenStopGeneration);
            }
        }
        if (cmd instanceof CabinClimateCommand) {
            synchronized (climateCommandLock) {
                return executeUnserialized(cmd, tailgateOpenStopGeneration);
            }
        }
        if (cmd instanceof TrunkCloseCommand) {
            // Close shares the open transaction lock. Otherwise a successful
            // close can be followed by an older unlock-and-open sequence.
            synchronized (cloudLock) {
                return executeUnserialized(cmd, tailgateOpenStopGeneration);
            }
        }
        if (cmd instanceof TrunkStopCommand) {
            // STOP must remain immediate, but it invalidates any already-pending
            // unlock/settle/cloud-fallback open before its motor leg can run.
            if (!((TrunkStopCommand) cmd).cancellationAlreadyApplied) {
                cancelPendingTailgateOpen();
            }
            return executeUnserialized(cmd, tailgateOpenStopGeneration);
        }
        return executeUnserialized(cmd, tailgateOpenStopGeneration);
    }

    private CommandResult executeUnserialized(VehicleCommand cmd, long tailgateOpenStopGeneration) {
        CommandResult blocked = checkDrivingSafety(cmd);
        if (blocked != null) return blocked;

        if (cmd instanceof TrunkOpenCommand) {
            // The composite flow safely unlocks and opens when the default
            // SDK-first policy needs a remote fallback. An explicit sdk_only
            // policy is an offline-only promise: run the guarded local motor
            // leg and never wake/unlock the vehicle through the cloud.
            RoutePreference trunkPreference = resolveEffectivePreference(cmd);
            if (trunkPreference == RoutePreference.SDK_ONLY) {
                return finishCommand(cmd, runSdkOnlyTrunkOpen(cmd, tailgateOpenStopGeneration));
            }
            return finishCommand(cmd, executeTrunkOpen(tailgateOpenStopGeneration));
        }
        if (cmd instanceof TrunkOpenSdkCommand) {
            return finishCommand(cmd, runSdkOnlyTrunkOpen(cmd, tailgateOpenStopGeneration));
        }

        // No legs at all — nothing to do.
        if (!cmd.hasCloudPath() && !cmd.hasSdkPath()) {
            return CommandResult.notSupported(msg("not_supported"));
        }

        CommandResult result = runWithVehicleWake(cmd);

        // Applied BEFORE route resolution so no preference — including an explicit
        // routePolicy override to cloud_only — can reach the remote leg while occupied.
        if (result == null) result = runLocalOnlyWhileAwake(cmd);
        if (result == null) {
            RoutePreference pref = resolveEffectivePreference(cmd);
            switch (pref) {
                case SDK_ONLY:
                    result = runSdkOnly(cmd);
                    break;
                case CLOUD_ONLY:
                    result = runCloudOnly(cmd);
                    break;
                case SDK_FIRST:
                    result = runSdkFirst(cmd);
                    break;
                case CLOUD_FIRST:
                default:
                    result = runCloudFirst(cmd);
                    break;
            }
        }
        return finishCommand(cmd, result);
    }

    private enum Wake { AWAKE, NOT_SENT, NOT_ON, STATE_CHANGED, ABORTED }

    /**
     * {@link VehicleCommand#wakeVehicleIfOff()}: power on, then write. Requires the live power
     * level AND ACC to read off, since the power-on primer is a power-OFF frame. Cloud only when
     * no power-on was sent: OPENAIR must never reach a car that may be coming on. Null = normal
     * routing.
     */
    private CommandResult runWithVehicleWake(VehicleCommand cmd) {
        // DiLink 5 virtualizes the bodywork power level and its local rail is unconfirmed.
        if (!cmd.wakeVehicleIfOff() || com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                || !DrivingSafetyGuard.isAccConfirmedOff() || isRemoteClimateActive()
                || isRecentPowerOff()) {
            return null;
        }
        BydDataCollector collector = BydDataCollector.getInstance();
        if (collector.readPowerLevelNow() != POWER_LEVEL_OFF) return null;
        long start = System.currentTimeMillis();
        Wake wake = powerOnAndAwaitOn(cmd, collector);
        if (wake == Wake.ABORTED) {
            return stoppedLocalAttempt(cmd, System.currentTimeMillis() - start);
        }
        if (wake == Wake.AWAKE) return runSdkAfterWake(cmd, collector, start);
        if (wake == Wake.STATE_CHANGED) return null;
        if (wake == Wake.NOT_SENT) {
            return resolveEffectivePreference(cmd) != RoutePreference.SDK_ONLY
                    && cloudHandshakeSatisfied(cmd) ? runCloudOnly(cmd) : null;
        }
        return CommandResult.failed(Path.SDK, msg("local_failed"),
                System.currentTimeMillis() - start, null);
    }

    /** A climate write just after a switch-off is a power-off rule or an occupant, not a wake. */
    private static boolean isRecentPowerOff() {
        long offAt = com.overdrive.app.monitor.AccMonitor.lastAccOffEdgeElapsedMs();
        return offAt > 0L
                && android.os.SystemClock.elapsedRealtime() - offAt < CLIMATE_WAKE_AFTER_OFF_MS;
    }

    private Wake powerOnAndAwaitOn(VehicleCommand cmd, BydDataCollector collector) {
        if (!com.overdrive.app.byd.RemoteKeyControl.isEnabled()) return Wake.NOT_SENT;
        logger.info("'" + cmd.name() + "' with the vehicle off — powering on first");
        SdkLeg power = invokeSdk(new VehiclePowerOnCommand());
        if (power.error instanceof com.overdrive.app.byd.LocalActuationAbort) {
            cmd.localAttempt().abort((com.overdrive.app.byd.LocalActuationAbort) power.error);
            return Wake.ABORTED;
        }
        // Blocked means ACC changed after the gate read it off: the car may be coming on.
        if (power.blocked) return Wake.STATE_CHANGED;
        if (!power.success) {
            logger.warn("'" + cmd.name() + "': power-on refused");
            return Wake.NOT_SENT;
        }
        long deadline = android.os.SystemClock.elapsedRealtime() + CLIMATE_WAKE_TIMEOUT_MS;
        try {
            while (collector.readPowerLevelNow() < POWER_LEVEL_ON) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    logger.warn("'" + cmd.name() + "': vehicle not on within "
                            + CLIMATE_WAKE_TIMEOUT_MS + " ms of power-on");
                    return Wake.NOT_ON;
                }
                Thread.sleep(CLIMATE_WAKE_POLL_MS);
            }
            Thread.sleep(CLIMATE_WAKE_SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Wake.NOT_ON;
        }
        return Wake.AWAKE;
    }

    /** Just after power-on the HVAC can ignore an accepted write: read back, retry once. */
    private CommandResult runSdkAfterWake(VehicleCommand cmd, BydDataCollector collector,
                                          long start) {
        SdkLeg leg = null;
        for (int attempt = 1; attempt <= CLIMATE_WAKE_WRITE_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                try {
                    Thread.sleep(CLIMATE_WAKE_RETRY_GAP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            leg = invokeSdkAfterWake(cmd, collector);
            if (leg.blocked) return CommandResult.blocked(msg("blocked_driving"));
            if (leg.success && awaitLandedAfterWake(cmd, collector)) {
                logger.info("'" + cmd.name() + "' landed after power-on (attempt " + attempt + ")");
                return CommandResult.success(Path.SDK, msg("local_sent"),
                        System.currentTimeMillis() - start);
            }
            logger.warn("'" + cmd.name() + "' after power-on: attempt " + attempt
                    + (leg.success ? " accepted but did not take effect" : " refused"));
        }
        return CommandResult.failed(Path.SDK, msg("local_failed"),
                System.currentTimeMillis() - start, leg != null ? leg.error : null);
    }

    /** The wake path is never DiLink 5, so the write runs here rather than over the bridge. */
    private SdkLeg invokeSdkAfterWake(VehicleCommand cmd, BydDataCollector collector) {
        if (checkDrivingSafety(cmd) != null) return new SdkLeg(false, null, true);
        try {
            return new SdkLeg(cmd.executeViaSdkAfterWake(collector), null);
        } catch (Exception e) {
            logger.warn("SDK exec for " + cmd.name() + " after power-on threw: " + e.getMessage());
            return new SdkLeg(false, e);
        }
    }

    /** Waits for the read-back to confirm; a state that stays unreadable trusts the write. */
    private static boolean awaitLandedAfterWake(VehicleCommand cmd, BydDataCollector collector) {
        long deadline = android.os.SystemClock.elapsedRealtime() + CLIMATE_WAKE_CONFIRM_MS;
        while (true) {
            Boolean landed = cmd.landedAfterWake(collector);
            if (Boolean.TRUE.equals(landed)) return true;
            if (android.os.SystemClock.elapsedRealtime() >= deadline) return landed == null;
            try {
                Thread.sleep(CLIMATE_WAKE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Enforces {@link VehicleCommand#localOnlyWhenAwake()}: while the car is awake/occupied,
     * these commands run their LOCAL leg only and never escalate to the cloud remote leg.
     *
     * <p>Returns {@code null} when the constraint doesn't apply, meaning "carry on with normal
     * routing". A non-null result is final — notably a local FAILURE is reported as failed
     * rather than escalated, because the escalation is precisely what powers the car down.
     */
    private CommandResult runLocalOnlyWhileAwake(VehicleCommand cmd) {
        if (!cmd.localOnlyWhenAwake() || !cmd.hasSdkPath()) return null;
        if (!isVehicleOccupiedOrAwake()) return null;
        long start = System.currentTimeMillis();
        SdkLeg leg = invokeSdk(cmd);
        long elapsed = System.currentTimeMillis() - start;
        if (leg.blocked) return CommandResult.blocked(msg("blocked_driving"));
        if (leg.success) return CommandResult.success(Path.SDK, msg("local_sent"), elapsed);
        logger.warn("'" + cmd.name() + "' local write failed while awake/occupied — NOT falling "
                + "back to the cloud remote command (it acts on the remote session, which ends "
                + "with the vehicle powering down)");
        return CommandResult.failed(Path.SDK, msg("local_failed"), elapsed, leg.error);
    }

    /**
     * A successful AC-off — from ANY surface — retires a pending auto-off window.
     *
     * <p>Hooked here, at the one funnel every caller goes through (HTTP
     * {@code /api/vehicle/climate}, the Home Assistant / MQTT catalog, key mapping, automations),
     * rather than at each call site: the HA and key-mapping paths build {@link ClimateOffCommand}
     * directly and so used to leave the timer armed. Now that the window is PERSISTED, such a stale
     * timer survives reboots and could switch off an AC the user had since turned back on by hand —
     * exactly what {@code AcAutoOffTimer} documents it will not do.
     *
     * <p>Only SUCCESS retires the window: a blocked/failed off command means the AC is still
     * running, so the timer must stay armed. The timer's own shutdown also flows through here and
     * is harmlessly idempotent (it has already cleared its state by this point).
     */
    private static void retireAcAutoOffWindow(VehicleCommand cmd, CommandResult result) {
        if (!(cmd instanceof ClimateOffCommand)) return;
        if (result == null || result.outcome != Outcome.SUCCESS) return;
        try {
            com.overdrive.app.byd.AcAutoOffTimer.cancel();
        } catch (Throwable t) {
            // Never let timer bookkeeping affect the command's own reported outcome.
        }
    }

    /**
     * Resolve the command's effective preference, applying any per-command
     * config override under {@code bydCloud.routePolicy.<name>}, and clamping
     * the result to the command's declared capabilities (e.g., a CLOUD_FIRST
     * command with SDK NONE collapses to CLOUD_ONLY automatically).
     *
     * <p>Valid override values: {@code cloud_first}, {@code sdk_first},
     * {@code cloud_only}, {@code sdk_only}. Anything else is ignored.
     */
    private RoutePreference resolveEffectivePreference(VehicleCommand cmd) {
        RoutePreference pref = cmd.defaultPreference();
        RoutePreference override = readPolicyOverride(cmd.name());
        // Commands which have an SDK leg are intentionally SDK-first so
        // automations behave locally while the car is awake. Do not allow a
        // persisted cloud-first override to silently defeat that contract;
        // sdk_only remains useful for an explicit offline-only policy.
        if (override != null && cmd.defaultPreference() == RoutePreference.SDK_FIRST
                && override != RoutePreference.SDK_FIRST
                && override != RoutePreference.SDK_ONLY) {
            logger.warn("Ignoring cloud routePolicy override for SDK-first command '" + cmd.name() + "'");
            override = null;
        }
        if (override != null) pref = override;

        // Clamp to the command's actual capabilities so a misconfigured override
        // (e.g., cloud_first on a SDK-only command) doesn't break dispatch.
        RoutePreference clamped;
        if (!cmd.hasCloudPath()) {
            clamped = cmd.hasSdkPath() ? RoutePreference.SDK_ONLY : pref;
        } else if (!cmd.hasSdkPath()) {
            clamped = RoutePreference.CLOUD_ONLY;
        } else if (cmd.cloudRequired() && !cmd.sdkRequired()) {
            clamped = RoutePreference.CLOUD_ONLY;
        } else if (cmd.sdkRequired() && !cmd.cloudRequired()) {
            clamped = RoutePreference.SDK_ONLY;
        } else {
            clamped = pref;
        }

        // Log when an override was actually rejected by the clamp — silent
        // rewrites would leave admins wondering why their override "didn't take".
        if (override != null && clamped != override) {
            logger.info("routePolicy['" + cmd.name() + "']=" + override
                    + " clamped to " + clamped + " (capabilities: cloud="
                    + cmd.cloudCapability() + " sdk=" + cmd.sdkCapability() + ")");
        }
        return clamped;
    }

    private RoutePreference readPolicyOverride(String commandName) {
        try {
            JSONObject root = UnifiedConfigManager.loadConfig();
            if (root == null) return null;
            JSONObject byd = root.optJSONObject("bydCloud");
            if (byd == null) return null;
            JSONObject policy = byd.optJSONObject("routePolicy");
            if (policy == null) return null;
            String raw = policy.optString(commandName, "");
            if (raw.isEmpty()) return null;
            switch (raw.toLowerCase()) {
                case "cloud_first": return RoutePreference.CLOUD_FIRST;
                case "sdk_first": return RoutePreference.SDK_FIRST;
                case "cloud_only": return RoutePreference.CLOUD_ONLY;
                case "sdk_only": return RoutePreference.SDK_ONLY;
                default:
                    logger.warn("Ignoring unknown routePolicy['" + commandName + "']='" + raw + "'");
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ── Per-policy executors ────────────────────────────────────────────

    private CommandResult runSdkOnly(VehicleCommand cmd) {
        if (!cmd.hasSdkPath()) return CommandResult.notSupported(msg("not_supported"));
        long start = System.currentTimeMillis();
        SdkLeg leg = invokeSdk(cmd);
        long elapsed = System.currentTimeMillis() - start;
        if (leg.blocked) return CommandResult.blocked(msg("blocked_driving"));
        if (leg.success) return CommandResult.success(Path.SDK, msg("local_sent"), elapsed);
        return CommandResult.failed(Path.SDK, msg("local_failed"), elapsed, leg.error);
    }

    /**
     * Local-only dispatch for the MQTT / Home Assistant control path.
     *
     * <p>Runs <b>only</b> the SDK leg — it never touches the cloud leg, the cloud
     * handshake, the control PIN, or VIN lookup, and never constructs a cloud
     * client. This is the structural guarantee behind "MQTT control is fully
     * local with zero BYD-cloud dependency": even a command that declares a cloud
     * capability will only have its {@link VehicleCommand#executeViaSdk} leg run
     * here. Commands with no SDK path return {@code NOT_SUPPORTED} (the control
     * catalog should not have offered them in the first place).
     */
    public CommandResult executeSdkOnly(VehicleCommand cmd) {
        // See execute(): this must happen before checkDrivingSafety() in the
        // unserialized helper so STOP invalidates an already-entered request.
        long tailgateOpenStopGeneration = tailgateOpenStopGeneration(cmd);
        cmd.beginLocalAttempt();
        if (cmd instanceof SeatClimateCommand) {
            synchronized (seatCommandLock) {
                return executeSdkOnlyUnserialized(cmd, tailgateOpenStopGeneration);
            }
        }
        if (cmd instanceof CabinClimateCommand) {
            synchronized (climateCommandLock) {
                return executeSdkOnlyUnserialized(cmd, tailgateOpenStopGeneration);
            }
        }
        if (cmd instanceof TrunkCloseCommand) {
            synchronized (cloudLock) {
                return executeSdkOnlyUnserialized(cmd, tailgateOpenStopGeneration);
            }
        }
        if (cmd instanceof TrunkStopCommand) {
            if (!((TrunkStopCommand) cmd).cancellationAlreadyApplied) {
                cancelPendingTailgateOpen();
            }
            return executeSdkOnlyUnserialized(cmd, tailgateOpenStopGeneration);
        }
        return executeSdkOnlyUnserialized(cmd, tailgateOpenStopGeneration);
    }

    private CommandResult executeSdkOnlyUnserialized(VehicleCommand cmd,
                                                     long tailgateOpenStopGeneration) {
        CommandResult blocked = checkDrivingSafety(cmd);
        if (blocked != null) return blocked;
        CommandResult result = isSdkOnlyTrunkOpen(cmd)
                ? runSdkOnlyTrunkOpen(cmd, tailgateOpenStopGeneration) : runSdkOnly(cmd);
        // Same AC-auto-off retirement as execute(). This is a SECOND public entry point —
        // MqttCommandRouter uses it unconditionally and KeymapApiHandler for any non-cloud
        // command — so hooking only execute() would have left the Home Assistant AC-off path
        // (the very one this is meant to cover) with a stale, now-persisted timer.
        return finishCommand(cmd, result);
    }

    /**
     * Updates router-owned state only after a command result is known. A local
     * climate start never creates a remote session; a cloud-confirmed OPENAIR
     * does. Any successful ClimateOff, including a local SDK write, ends it.
     */
    private CommandResult finishCommand(VehicleCommand cmd, CommandResult result) {
        updateRemoteClimateSession(cmd, result);
        updateSeatCloudCacheAfterLocalSuccess(cmd, result);
        retireAcAutoOffWindow(cmd, result);
        confirmLockStateAfterCommand(cmd, result);
        return result;
    }

    /** Rules that check the lock after this command must see the car's new state, not the poll's. */
    private static void confirmLockStateAfterCommand(VehicleCommand cmd, CommandResult result) {
        if (result == null || result.outcome != Outcome.SUCCESS) return;
        if (!(cmd instanceof LockCommand) && !(cmd instanceof UnlockCommand)) return;
        try {
            BydDataCollector.getInstance().confirmLockStateAfterCommand(cmd instanceof LockCommand);
        } catch (Throwable t) {
            // Never let lock-state bookkeeping affect the command's own reported outcome.
        }
    }

    private synchronized void updateRemoteClimateSession(VehicleCommand cmd, CommandResult result) {
        if (result == null || result.outcome != Outcome.SUCCESS) return;
        if (cmd instanceof ClimateOnCommand
                && (result.path == Path.CLOUD || result.path == Path.SDK_THEN_CLOUD)) {
            ClimateOnCommand climateOn = (ClimateOnCommand) cmd;
            remoteClimateActiveUntilMs = System.currentTimeMillis()
                    + TimeUnit.MINUTES.toMillis(climateOn.remoteDurationMinutes);
        } else if (cmd instanceof ClimateOffCommand) {
            remoteClimateActiveUntilMs = 0L;
        }
    }

    /**
     * A successful local seat write changes one field outside the cloud composite protocol.
     * Replace prior cloud evidence with the fresh source snapshot plus that confirmed target when
     * available; otherwise discard it. Either path prevents a later fallback from restoring the
     * pre-local target from an older cloud composite.
     */
    private void updateSeatCloudCacheAfterLocalSuccess(VehicleCommand cmd, CommandResult result) {
        if (!isFrontSeatClimateCommand(cmd) || result == null
                || result.outcome != Outcome.SUCCESS
                || (result.path != Path.SDK && result.path != Path.CLOUD_THEN_SDK)) {
            return;
        }
        SeatClimateCommand command = (SeatClimateCommand) cmd;
        synchronized (cloudLock) {
            boolean seedFresh = command.hasFreshSeatSnapshot()
                    && isFreshSeatSnapshot(command.freshSeatSnapshotAtMs());
            // A local write can complete after another command has already received a
            // terminal cloud confirmation. In that ordering the old collector seed
            // must not roll back the confirmed sibling zones; layer the local target
            // over the newer router-owned composite instead.
            int[] state = hasFreshSeatCompositeState() && (!seedFresh
                    || command.freshSeatSnapshotAtMs() <= seatCompositeStateAtMs)
                    ? seatCompositeState.clone()
                    : (seedFresh ? command.freshSeatState() : null);
            int index = command.targetStateIndex();
            if (isCompleteSeatState(state) && index >= 0 && index < state.length
                    && isSeatLevel(command.targetLevel())) {
                state[index] = command.targetLevel();
                commitSeatCompositeState(state);
            } else {
                seatCompositeState = null;
                seatCompositeStateAtMs = 0L;
            }
        }
    }

    private CommandResult runCloudOnly(VehicleCommand cmd) {
        if (!cmd.hasCloudPath()) return CommandResult.notSupported(msg("not_supported"));
        if (!cloudHandshakeSatisfied(cmd)) {
            return CommandResult.authRequired(msg("cloud_required"));
        }
        long start = System.currentTimeMillis();
        CloudCallResult cr = runCloudCall(cmd);
        long elapsed = System.currentTimeMillis() - start;
        return mapCloudOnlyResult(cr, elapsed);
    }

    /**
     * Cloud-only dispatch for the cloud fallback ACTUATOR of the proximity path
     * ({@code RemoteAccessApiHandler.performDecision}). Runs ONLY the cloud leg of {@code cmd}: no
     * SDK leg, no vehicle wake, no SDK_FIRST/CLOUD_FIRST cascade. Same driving-safety gate, cloud
     * handshake, capability discovery, PIN handshake and {@link VehicleCommand#cloudTimeoutMs()}
     * budget as the manual path's cloud leg; exactly one attempt, serialized on cloudLock. The caller
     * bounds its own wait and interrupts this thread at its deadline: the interrupted leg is
     * cancelled exactly like a timed-out manual leg (no dispatch after the interrupt). An explicit
     * {@code bydCloud.routePolicy.<name> = sdk_only} disables this leg (NOT_SUPPORTED). AUTH_REQUIRED
     * means "cloud not configured" and is returned before any network call.
     */
    public CommandResult executeCloudOnly(VehicleCommand cmd) {
        CommandResult blocked = checkDrivingSafety(cmd);
        if (blocked != null) return blocked;
        if (resolveEffectivePreference(cmd) == RoutePreference.SDK_ONLY) {
            return CommandResult.notSupported(msg("not_supported"));
        }
        CommandResult stopped = stoppedLocalAttempt(cmd, 0L);
        if (stopped != null) return stopped;
        return finishCommand(cmd, runCloudOnly(cmd));
    }

    private CommandResult runCloudFirst(VehicleCommand cmd) {
        long start = System.currentTimeMillis();

        // Some dual-path commands are instant or semantically preferable on
        // the local rail while the connected vehicle is awake/occupied.
        boolean preferLocal = cmd.isLatencySensitive() && isVehicleAwake();
        if (!preferLocal && cmd.preferLocalWhenOccupiedOrAwake()) {
            preferLocal = isVehicleOccupiedOrAwake();
        }
        if (preferLocal && cmd.hasSdkPath()) {
            SdkLeg leg = invokeSdk(cmd);
            if (leg.success) {
                return CommandResult.success(Path.SDK, msg("local_sent"),
                        System.currentTimeMillis() - start);
            }
            if (leg.blocked) return CommandResult.blocked(msg("blocked_driving"));
            CommandResult stopped = stoppedLocalAttempt(cmd, System.currentTimeMillis() - start);
            if (stopped != null) return stopped;
            // SDK failed despite being awake — fall through to cloud.
        }

        if (cloudHandshakeSatisfied(cmd)) {
            CloudCallResult cr = runCloudCall(cmd);
            long elapsed = System.currentTimeMillis() - start;
            if (cr.outcome == CloudOutcomeKind.SUCCESS) {
                return CommandResult.success(Path.CLOUD, msg("cloud_sent"), elapsed);
            }
            if (cr.outcome == CloudOutcomeKind.BLOCKED_DRIVING) {
                return CommandResult.blocked(msg("blocked_driving"));
            }
            // Rate-limit: don't fall back; the previous command is still
            // executing and the SDK would race it.
            if (cr.outcome == CloudOutcomeKind.RATE_LIMITED) {
                return CommandResult.rateLimited(msg("rate_limited"), elapsed);
            }
            // Cloud failed (timeout, HTTP, controlState=2). Try SDK.
            if (cmd.hasSdkPath()) {
                SdkLeg leg = invokeSdk(cmd);
                long elapsed2 = System.currentTimeMillis() - start;
                if (leg.success) return CommandResult.success(Path.CLOUD_THEN_SDK,
                        msg("cloud_unavailable_used_local"), elapsed2);
                if (leg.blocked) return CommandResult.blocked(msg("blocked_driving"));
                return CommandResult.failed(Path.CLOUD_THEN_SDK,
                        msg("both_legs_failed"), elapsed2, cr.error != null ? cr.error : leg.error);
            }
            return CommandResult.failed(Path.CLOUD, msg("cloud_failed"), elapsed, cr.error);
        }

        // Cloud unavailable; SDK fallback if possible.
        if (cmd.hasSdkPath()) {
            SdkLeg leg = invokeSdk(cmd);
            long elapsed = System.currentTimeMillis() - start;
            if (leg.success) return CommandResult.success(Path.SDK,
                    msg("cloud_offline_used_local"), elapsed);
            if (leg.blocked) return CommandResult.blocked(msg("blocked_driving"));
            // BOTH legs had their turn and both failed — say that, rather than blaming only the
            // cloud when the local HAL is the leg that actually rejected the command last.
            return CommandResult.failed(Path.SDK, msg("both_legs_failed"), elapsed, leg.error);
        }
        return CommandResult.authRequired(msg("cloud_required"));
    }

    /**
     * SDK_FIRST routing: try the local primitive first; if it fails (or the
     * car is asleep and the call would no-op), fall through to cloud.
     */
    private CommandResult runSdkFirst(VehicleCommand cmd) {
        long start = System.currentTimeMillis();
        SdkLeg sdkLeg = null;
        if (cmd.hasSdkPath()) {
            sdkLeg = invokeSdk(cmd);
            if (sdkLeg.success) {
                return CommandResult.success(Path.SDK, msg("local_sent"),
                        System.currentTimeMillis() - start);
            }
            if (sdkLeg.blocked) return CommandResult.blocked(msg("blocked_driving"));
            CommandResult stopped = stoppedLocalAttempt(cmd, System.currentTimeMillis() - start);
            if (stopped != null) return stopped;
        }
        // SDK failed or absent — try cloud.
        if (!cmd.hasCloudPath()) {
            // No cloud path. Whether SDK was attempted matters for the message:
            // if it ran and failed, surface that; otherwise it's truly unsupported.
            // Report local_failed, NOT cloud_failed: the cloud was never attempted here, and
            // labelling a local HAL rejection "couldn't reach the car / wake the vehicle" sent a
            // real debugging session down the wrong path for 353 failed seat-vent writes.
            if (sdkLeg != null) {
                return CommandResult.failed(Path.SDK, msg("local_failed"),
                        System.currentTimeMillis() - start, sdkLeg.error);
            }
            return CommandResult.notSupported(msg("not_supported"));
        }
        if (!cloudHandshakeSatisfied(cmd)) {
            // SDK actually ran and returned false — don't blame missing cloud
            // creds (or an asleep car) for a local primitive that had its turn.
            if (sdkLeg != null) {
                return CommandResult.failed(Path.SDK, msg("local_failed"),
                        System.currentTimeMillis() - start, sdkLeg.error);
            }
            return CommandResult.authRequired(msg("cloud_required"));
        }
        CloudCallResult cr = runCloudCall(cmd);
        long elapsed = System.currentTimeMillis() - start;
        if (cr.outcome == CloudOutcomeKind.SUCCESS) {
            return CommandResult.success(Path.SDK_THEN_CLOUD,
                    msg("local_unavailable_used_cloud"), elapsed);
        }
        if (cr.outcome == CloudOutcomeKind.RATE_LIMITED) {
            return CommandResult.rateLimited(msg("rate_limited"), elapsed);
        }
        if (cr.outcome == CloudOutcomeKind.BLOCKED_DRIVING) {
            return CommandResult.blocked(msg("blocked_driving"));
        }
        // BYD endpoint rejected the command shape — distinct from a transient
        // failure. Mirror runCloudOnly's UNSUPPORTED handling.
        if (cr.outcome == CloudOutcomeKind.UNSUPPORTED) {
            return CommandResult.notSupported(msg("not_supported"));
        }
        return CommandResult.failed(Path.SDK_THEN_CLOUD, msg("cloud_failed"), elapsed, cr.error);
    }

    private CommandResult mapCloudOnlyResult(CloudCallResult cr, long elapsed) {
        switch (cr.outcome) {
            case SUCCESS:      return CommandResult.success(Path.CLOUD, msg("cloud_sent"), elapsed);
            case RATE_LIMITED: return CommandResult.rateLimited(msg("rate_limited"), elapsed);
            case VEHICLE_UNREACHABLE:
                return CommandResult.vehicleUnreachable(msg("cloud_failed"), elapsed, cr.error);
            case BLOCKED_DRIVING: return CommandResult.blocked(msg("blocked_driving"));
            case UNSUPPORTED:  return CommandResult.notSupported(msg("not_supported"));
            default:           return CommandResult.failed(Path.CLOUD, msg("cloud_failed"), elapsed, cr.error);
        }
    }

    /**
     * Trunk open: use the local SDK motor only when the local lock rail explicitly says the
     * vehicle is already unlocked. The body controller can trip the alarm when a locked
     * tailgate is driven through the SDK, so LOCKED and UNKNOWN values skip that motor entirely.
     *
     * <p>When the local leg is unavailable, use BYD's capability-gated {@code OPENTRUNK}
     * command directly. It is the remote tailgate operation and must not be preceded by the
     * general {@code OPENDOOR} unlock command, which would unnecessarily unlock the vehicle.
     */
    private CommandResult executeTrunkOpen(long stopGeneration) {
        // Keep the local sampling/motor decision and cloud fallback serialized with close.
        synchronized (cloudLock) {
            return executeTrunkOpenSerialized(stopGeneration);
        }
    }

    /**
     * Offline tailgate open transaction. The local lock-state sample, final
     * motion check, and motor call share cloudLock so a concurrent cloud lock
     * command cannot land between them. This path deliberately contains no
     * cloud fallback, unlock, or handshake work.
     */
    private CommandResult runSdkOnlyTrunkOpen(VehicleCommand command, long stopGeneration) {
        synchronized (cloudLock) {
            long start = System.currentTimeMillis();
            BydDataCollector collector = BydDataCollector.getInstance();
            if (collector.readDoorLockState() != BydDataCollector.DOOR_STATE_UNLOCK) {
                return CommandResult.failed(Path.SDK, msg("not_supported"),
                        System.currentTimeMillis() - start, null);
            }
            // The outer public entry-point check can become stale while a
            // cloud command is draining. Check again immediately before the
            // physical motor write.
            CommandResult safety = checkDrivingSafety(command);
            if (safety != null) return safety;
            synchronized (tailgateAbortLock) {
                if (isTailgateOpenCancelled(stopGeneration)) {
                    return tailgateOpenCancelled(Path.SDK, start);
                }
                boolean opened;
                try {
                    opened = shouldDispatchViaDiLink5Bridge(command)
                            ? com.overdrive.app.byd.VehicleActuatorBridge.dispatchDiLink5Command(
                                    collector.getRuntimeContext(), command)
                            : collector.openTailgate();
                } catch (Exception e) {
                    logger.warn("SDK exec for " + command.name() + " threw: " + e.getMessage());
                    return CommandResult.failed(Path.SDK, msg("not_supported"),
                            System.currentTimeMillis() - start, e);
                }
                long elapsed = System.currentTimeMillis() - start;
                return opened ? CommandResult.success(Path.SDK, msg("local_sent"), elapsed)
                        : CommandResult.failed(Path.SDK, msg("not_supported"), elapsed, null);
            }
        }
    }

    private static boolean isSdkOnlyTrunkOpen(VehicleCommand command) {
        return command instanceof TrunkOpenCommand || command instanceof TrunkOpenSdkCommand;
    }

    /**
     * A bound generation comes from the caller's ingress boundary. Otherwise capture at router
     * ingress so direct callers retain the normal "a new OPEN after STOP is allowed" behavior.
     */
    private long tailgateOpenStopGeneration(VehicleCommand cmd) {
        if (cmd instanceof TrunkOpenCommand
                && ((TrunkOpenCommand) cmd).stopGeneration >= 0L) {
            return ((TrunkOpenCommand) cmd).stopGeneration;
        }
        return isSdkOnlyTrunkOpen(cmd) ? tailgateStopGeneration.get() : -1L;
    }

    private CommandResult executeTrunkOpenSerialized(long stopGeneration) {
        long start = System.currentTimeMillis();
        TrunkOpenCommand command = new TrunkOpenCommand(stopGeneration);

        // An MQTT OPEN can have waited behind an earlier cloud command. If STOP arrived while it
        // waited, reject it before touching the local motor or cloud endpoint.
        if (isTailgateOpenCancelled(stopGeneration)) {
            return tailgateOpenCancelled(Path.SDK, start);
        }
        int lockState = BydDataCollector.getInstance().readDoorLockState();
        SdkLeg local = new SdkLeg(false, null);
        if (lockState == BydDataCollector.DOOR_STATE_UNLOCK) {
            CommandResult localSafety = checkDrivingSafety(command);
            if (localSafety != null) return localSafety;
            local = invokeTailgateOpen(command, stopGeneration);
        }
        long elapsed = System.currentTimeMillis() - start;
        if (local.blocked) return CommandResult.blocked(msg("blocked_driving"));
        if (local.success) return CommandResult.success(Path.SDK, msg("local_sent"), elapsed);
        Path localPath = Path.SDK;
        Path cloudPath = lockState == BydDataCollector.DOOR_STATE_UNLOCK
                ? Path.SDK_THEN_CLOUD : Path.CLOUD;
        if (isTailgateOpenCancelled(stopGeneration)) {
            return tailgateOpenCancelled(localPath, start);
        }

        // Direct OPENTRUNK handles the remote tailgate operation. Never issue OPENDOOR here:
        // it exposes the cabin without being necessary for the cloud trunk command.
        if (!cloudHandshakeSatisfied(command)) {
            return CommandResult.failed(localPath, msg("local_failed"), elapsed, local.error);
        }
        if (isTailgateOpenCancelled(stopGeneration)) {
            return tailgateOpenCancelled(localPath, start);
        }
        // Recheck at the cloud dispatch boundary so a movement edge cannot open the tailgate.
        CommandResult cloudSafety = checkDrivingSafety(command);
        if (cloudSafety != null) return cloudSafety;
        CloudCallResult cloud = runCloudCall(command);
        elapsed = System.currentTimeMillis() - start;
        if (cloud.outcome == CloudOutcomeKind.SUCCESS) {
            return CommandResult.success(cloudPath,
                    cloudPath == Path.CLOUD ? msg("cloud_sent")
                            : msg("local_unavailable_used_cloud"), elapsed);
        }
        if (cloud.outcome == CloudOutcomeKind.RATE_LIMITED) {
            return CommandResult.rateLimited(msg("rate_limited"), elapsed);
        }
        if (cloud.outcome == CloudOutcomeKind.BLOCKED_DRIVING) {
            return CommandResult.blocked(msg("blocked_driving"));
        }
        if (cloud.outcome == CloudOutcomeKind.UNSUPPORTED) {
            return CommandResult.notSupported(msg("not_supported"));
        }
        return CommandResult.failed(cloudPath, msg("both_legs_failed"), elapsed,
                cloud.error != null ? cloud.error : local.error);
    }

    private boolean isTailgateOpenCancelled(long stopGeneration) {
        return tailgateStopGeneration.get() != stopGeneration;
    }

    private boolean isTailgateOpenCancelled(VehicleCommand command) {
        return command instanceof TrunkOpenCommand
                && ((TrunkOpenCommand) command).stopGeneration >= 0L
                && isTailgateOpenCancelled(((TrunkOpenCommand) command).stopGeneration);
    }

    /**
     * STOP is independent of cloudLock so it can interrupt a queued or active
     * remote tailgate-open request rather than waiting for its network budget.
     */
    private void cancelPendingTailgateOpen() {
        synchronized (tailgateAbortLock) {
            tailgateStopGeneration.incrementAndGet();
            Future<?> future = activeTailgateOpenFuture.getAndSet(null);
            Thread worker = activeTailgateOpenWorker.getAndSet(null);
            BydCloudClient client = activeTailgateOpenClient.getAndSet(null);
            if (future != null) future.cancel(true);
            if (client != null) client.cancelRequestForThread(worker);
        }
    }

    private void cancelCloudRequest(Future<?> future, BydCloudClient client, Thread worker,
                                    boolean tailgateOpen) {
        if (tailgateOpen) {
            synchronized (tailgateAbortLock) {
                cancelCloudRequestUnserialized(future, client, worker);
            }
        } else {
            cancelCloudRequestUnserialized(future, client, worker);
        }
    }

    private static void cancelCloudRequestUnserialized(Future<?> future, BydCloudClient client,
                                                       Thread worker) {
        if (future != null) future.cancel(true);
        if (client != null) client.cancelRequestForThread(worker);
    }

    /** Clear only this open's registration; a later transaction must remain visible to STOP. */
    private void clearActiveTailgateOpen(Future<?> future, Thread worker, BydCloudClient client) {
        if (future == null) return;
        synchronized (tailgateAbortLock) {
            activeTailgateOpenFuture.compareAndSet(future, null);
            if (worker != null) activeTailgateOpenWorker.compareAndSet(worker, null);
            if (client != null) activeTailgateOpenClient.compareAndSet(client, null);
        }
    }

    /**
     * The composite flow already owns cloudLock. Keep STOP out of that lock while
     * still making its generation change and the final local motor write atomic.
     */
    private SdkLeg invokeTailgateOpen(TrunkOpenCommand command, long stopGeneration) {
        if (checkDrivingSafety(command) != null) return new SdkLeg(false, null, true);
        synchronized (tailgateAbortLock) {
            if (isTailgateOpenCancelled(stopGeneration)) {
                return new SdkLeg(false,
                        new java.util.concurrent.CancellationException(
                                "tailgate open cancelled by stop"));
            }
            if (checkDrivingSafety(command) != null) return new SdkLeg(false, null, true);
            try {
                return new SdkLeg(executeSdkCommand(command), null);
            } catch (Exception e) {
                logger.warn("SDK exec for " + command.name() + " threw: " + e.getMessage());
                return new SdkLeg(false, e);
            }
        }
    }

    private CommandResult tailgateOpenCancelled(Path path, long startedAt) {
        return CommandResult.failed(path, msg("local_failed"),
                System.currentTimeMillis() - startedAt,
                new java.util.concurrent.CancellationException("tailgate open cancelled by stop"));
    }

    /**
     * Do not open the tailgate if shutdown/cancellation interrupts the required
     * post-unlock settle. Proceeding early can race the body-controller unlock.
     */
    // ── Cloud helpers ───────────────────────────────────────────────────

    private enum CloudOutcomeKind {
        SUCCESS, FAILED, RATE_LIMITED, VEHICLE_UNREACHABLE, BLOCKED_DRIVING, UNSUPPORTED
    }
    private static final class CloudCallResult {
        final CloudOutcomeKind outcome;
        final Throwable error;
        CloudCallResult(CloudOutcomeKind o, Throwable e) { outcome = o; error = e; }
    }

    private static final class SdkLeg {
        final boolean success;
        final Throwable error;
        final boolean blocked;
        SdkLeg(boolean s, Throwable e) { this(s, e, false); }
        SdkLeg(boolean s, Throwable e, boolean b) {
            success = s;
            error = e;
            blocked = b;
        }
    }

    /**
     * Resolve the composite cloud payload immediately before dispatch while cloudLock is held.
     * A later command therefore layers its one requested field over the prior confirmed seat
     * result rather than over a snapshot captured before that prior command was sent.
     */
    private int[] prepareSeatCloudState(SeatClimateCommand command) {
        boolean seedFresh = command.hasFreshSeatSnapshot()
                && isFreshSeatSnapshot(command.freshSeatSnapshotAtMs());
        boolean cacheFresh = hasFreshSeatCompositeState();
        // A collector read taken after the prior terminal cloud confirmation is newer evidence
        // than the cache. This also prevents a local state refresh from being overwritten.
        int[] state = cacheFresh && (!seedFresh
                || command.freshSeatSnapshotAtMs() <= seatCompositeStateAtMs)
                ? seatCompositeState.clone() : null;
        if (state == null) {
            if (seedFresh) {
                state = command.freshSeatState();
            } else {
                VehicleCloudSnapshot cloud = freshCloudSeatSnapshot();
                state = cloud != null ? cloud.frontSeatClimateUiState() : null;
            }
            if (!isCompleteSeatState(state)) return null;
        }
        int index = command.targetStateIndex();
        if (index == -1) {
            // Steering-wheel commands deliberately preserve every front-seat
            // channel and only set the wheel field in the same cloud payload.
            return state;
        }
        if (index < 0 || index >= state.length || !isSeatLevel(command.targetLevel())) {
            return null;
        }
        state[index] = command.targetLevel();
        return state;
    }

    /**
     * A VENTILATIONHEATING payload always includes steering-wheel heat. Preserve
     * a reported value when available. If capability discovery positively says
     * this trim has no wheel heater, use pyBYD's wire-off default (3). When the
     * cloud omits a supported wheel, use an exact live HAL readback; only block
     * when neither source can preserve its current state.
     */
    private int prepareSeatCloudSteeringWheelWireState(
            SeatClimateCommand command, CloudCapabilities capabilities) {
        int explicit = command.explicitSteeringWheelHeatWireState();
        VehicleCloudSnapshot cloud = freshCloudSeatSnapshot();
        int reported = cloud != null ? cloud.steeringWheelHeatWireState() : -1;
        int resolved = resolveSeatCloudSteeringWheelWireState(
                explicit, reported, BydVehicleData.UNAVAILABLE, capabilities);
        if (resolved >= 0) return resolved;
        int local = BydDataCollector.getInstance().getSteeringWheelHeatingState();
        return resolveSeatCloudSteeringWheelWireState(explicit, reported, local, capabilities);
    }

    static int resolveSeatCloudSteeringWheelWireState(
            int explicit, int reported, int local, CloudCapabilities capabilities) {
        if (explicit == 1 || explicit == 3) return explicit;
        if (reported == 1 || reported == 3) return reported;
        if (capabilities != null
                && !capabilities.supports(CloudCapabilities.Feature.SEAT_STEERING_WHEEL)) {
            return 3;
        }
        if (local == 2) return 1;
        if (local == 1) return 3;
        return -1;
    }

    private static VehicleCloudSnapshot freshCloudSeatSnapshot() {
        VehicleCloudSnapshot cloud = BydCloudDataProvider.getInstance().getSnapshot();
        if (cloud == null || !cloud.hasCompleteFrontSeatClimateState()
                || System.currentTimeMillis() - cloud.receivedAt > CLOUD_SEAT_SNAPSHOT_MAX_AGE_MS) {
            return null;
        }
        return cloud;
    }

    /** Commit a terminally confirmed cloud or SDK result as the next composite base. */
    private void commitSeatCompositeState(int[] state) {
        if (!isCompleteSeatState(state)) return;
        seatCompositeState = state.clone();
        seatCompositeStateAtMs = System.currentTimeMillis();
    }

    private boolean hasFreshSeatCompositeState() {
        return isFreshSeatSnapshot(seatCompositeStateAtMs)
                && isCompleteSeatState(seatCompositeState);
    }

    private static boolean isFrontSeatClimateCommand(VehicleCommand command) {
        return command instanceof SeatHeatCommand || command instanceof SeatVentCommand;
    }

    private static boolean isFreshSeatSnapshot(long timestampMs) {
        long ageMs = System.currentTimeMillis() - timestampMs;
        return ageMs >= 0L && ageMs <= SEAT_CLOUD_SNAPSHOT_MAX_AGE_MS;
    }

    private static boolean isCompleteSeatState(int[] state) {
        if (state == null || state.length != 4) return false;
        for (int level : state) {
            if (!isSeatLevel(level)) return false;
        }
        return true;
    }

    private SdkLeg invokeSdk(VehicleCommand cmd) {
        com.overdrive.app.byd.LocalActuationAttempt attempt = cmd.localAttempt();
        com.overdrive.app.byd.RemoteKeyControl.takeLastAbort();
        if (Thread.currentThread().isInterrupted()) {
            com.overdrive.app.byd.LocalActuationAbort stopped =
                    new com.overdrive.app.byd.LocalActuationAbort(
                            com.overdrive.app.byd.LocalActuationAbort.Kind.CANCELLED,
                            "vehicle command cancelled");
            attempt.abort(stopped);
            return new SdkLeg(false, stopped);
        }
        // A command can spend time behind a cloud request or local transaction
        // after the routing-entry gate. Check again immediately before every
        // local actuator write and keep this a terminal block.
        if (checkDrivingSafety(cmd) != null) {
            return new SdkLeg(false, null, true);
        }
        try {
            boolean accepted = executeSdkCommand(cmd);
            com.overdrive.app.byd.LocalActuationAbort stopped =
                    com.overdrive.app.byd.RemoteKeyControl.takeLastAbort();
            if (stopped != null) {
                attempt.abort(stopped);
                return new SdkLeg(false, stopped,
                        stopped.kind == com.overdrive.app.byd.LocalActuationAbort.Kind.BLOCKED_DRIVING);
            }
            return new SdkLeg(accepted, null);
        } catch (Exception e) {
            com.overdrive.app.byd.LocalActuationAbort stopped =
                    com.overdrive.app.byd.RemoteKeyControl.takeLastAbort();
            if (stopped != null) {
                attempt.abort(stopped);
                return new SdkLeg(false, stopped,
                        stopped.kind == com.overdrive.app.byd.LocalActuationAbort.Kind.BLOCKED_DRIVING);
            }
            logger.warn("SDK exec for " + cmd.name() + " threw: " + e.getMessage());
            return new SdkLeg(false, e);
        }
    }

    private static boolean executeSdkCommand(VehicleCommand cmd) {
        BydDataCollector collector = BydDataCollector.getInstance();
        if (shouldDispatchViaDiLink5Bridge(cmd)) {
            return com.overdrive.app.byd.VehicleActuatorBridge.dispatchDiLink5Command(
                    collector.getRuntimeContext(), cmd);
        }
        return cmd.executeViaSdk(collector);
    }

    private static boolean shouldDispatchViaDiLink5Bridge(VehicleCommand cmd) {
        return com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                && !"com.overdrive.app".equals(android.app.Application.getProcessName());
    }

    private CloudCallResult runCloudCall(final VehicleCommand cmd) {
        // Serialize cloud commands so we never race two simultaneous BYD
        // remote-control posts from different HTTP threads.
        synchronized (cloudLock) {
            final com.overdrive.app.byd.LocalActuationAttempt localAttempt = cmd.localAttempt();
            com.overdrive.app.byd.LocalActuationAbort localAbort = localAttempt.cloudAbort();
            if (localAbort != null) {
                return new CloudCallResult(
                        localAbort.kind == com.overdrive.app.byd.LocalActuationAbort.Kind.BLOCKED_DRIVING
                                ? CloudOutcomeKind.BLOCKED_DRIVING : CloudOutcomeKind.FAILED,
                        localAbort);
            }
            Future<CloudOutcome> future = null;
            final AtomicReference<Thread> workerThread = new AtomicReference<>();
            final AtomicBoolean cancelled = new AtomicBoolean(false);
            final boolean trackTailgateOpen = cmd instanceof TrunkOpenCommand
                    && ((TrunkOpenCommand) cmd).stopGeneration >= 0L;
            final AtomicReference<Future<?>> tailgateFuture = new AtomicReference<>();
            final SeatClimateCommand seatCommand = cmd instanceof SeatClimateCommand
                    ? (SeatClimateCommand) cmd : null;
            final int[] seatState = seatCommand != null ? prepareSeatCloudState(seatCommand) : null;
            if (seatCommand != null && seatState == null) {
                return new CloudCallResult(CloudOutcomeKind.UNSUPPORTED,
                        new IllegalStateException("fresh complete seat state unavailable"));
            }
            BydCloudClient client = null;
            try {
                client = BydCloudDataProvider.getInstance().getSharedClient();
                if (client == null) {
                    return new CloudCallResult(CloudOutcomeKind.FAILED,
                            new IllegalStateException("cloud client unavailable"));
                }
                final BydCloudClient cloudClient = client;
                final String vin = BydCloudConfig.fromUnifiedConfig().vin;
                if (vin == null || vin.isEmpty()) {
                    return new CloudCallResult(CloudOutcomeKind.FAILED,
                            new IllegalStateException("VIN missing"));
                }
                Callable<CloudOutcome> cloudWork = new Callable<CloudOutcome>() {
                    public CloudOutcome call() throws Exception {
                        Thread worker = Thread.currentThread();
                        workerThread.set(worker);
                        if (trackTailgateOpen) {
                            // submit() can start the worker before its Future is published.
                            // Do not run unless the open is still the registered active one.
                            synchronized (tailgateAbortLock) {
                                Future<?> registered = tailgateFuture.get();
                                if (registered == null
                                        || activeTailgateOpenFuture.get() != registered
                                        || isCloudCallCancelled(cancelled)
                                        || isTailgateOpenCancelled(cmd)) {
                                    return CloudOutcome.failed();
                                }
                                activeTailgateOpenWorker.set(worker);
                            }
                        }
                        if (isCloudCallCancelled(cancelled)) return CloudOutcome.failed();
                        CloudCapabilities.Feature feature = cmd.cloudFeature();
                        CloudCapabilities capabilities = null;
                        if (feature != CloudCapabilities.Feature.NONE) {
                            capabilities = cloudClient.getCachedCloudCapabilities(vin);
                            if (capabilities == null) {
                                try {
                                    capabilities = cloudClient.fetchCloudCapabilities(vin);
                                } catch (Exception e) {
                                    if (isCloudCallCancelled(cancelled)) {
                                        return CloudOutcome.failed();
                                    }
                                    // Never send a physical remote command when
                                    // support is unknown. SDK-first commands
                                    // have already attempted their local leg;
                                    // cloud-only commands fail closed.
                                    logger.info("Cloud capability discovery failed for " + cmd.name()
                                            + "; skipping cloud dispatch: " + e.getMessage());
                                    return CloudOutcome.unsupported();
                                }
                            }
                            if (!capabilities.supports(feature)) {
                                return CloudOutcome.unsupported();
                            }
                        }
                        if (isCloudCallCancelled(cancelled)) return CloudOutcome.failed();
                        if (localAttempt.cloudAbort() != null) return CloudOutcome.failed();
                        // /control/remoteControl commands require the PIN handshake;
                        // /control/smartCharge/* and similar config writes do not.
                        if (cmd.requiresControlPin()) {
                            cloudClient.verifyControlPassword(vin);
                        }
                        if (isCloudCallCancelled(cancelled)) return CloudOutcome.failed();
                        if (localAttempt.cloudAbort() != null) return CloudOutcome.failed();
                        // The command may have waited behind another cloud
                        // request, capability discovery, or PIN verification.
                        // Recheck here, directly before physical dispatch.
                        if (isCloudDispatchBlocked(cmd)) {
                            return CloudOutcome.blockedDriving();
                        }
                        if (isTailgateOpenCancelled(cmd)) {
                            return CloudOutcome.failed();
                        }
                        if (trackTailgateOpen) {
                            // STOP synchronizes with this final guard and then interrupts both
                            // the worker and its registered OkHttp Call if it arrives later.
                            synchronized (tailgateAbortLock) {
                                Future<?> registered = tailgateFuture.get();
                                if (registered == null
                                        || activeTailgateOpenFuture.get() != registered
                                        || isCloudCallCancelled(cancelled)
                                        || isTailgateOpenCancelled(cmd)) {
                                    return CloudOutcome.failed();
                                }
                            }
                        }
                        if (seatCommand != null) {
                            int seatSteeringWheelWireState =
                                    prepareSeatCloudSteeringWheelWireState(
                                            seatCommand, capabilities);
                            if (seatSteeringWheelWireState < 0) {
                                return CloudOutcome.unsupported();
                            }
                            boolean ok = cloudClient.setSeatClimate(vin, seatCommand.cloudChairType(),
                                    seatState[0], seatState[1], seatState[2], seatState[3],
                                    seatSteeringWheelWireState);
                            return ok ? CloudOutcome.success() : CloudOutcome.failed();
                        }
                        return cmd.executeViaCloud(cloudClient, vin);
                    }
                };
                if (trackTailgateOpen) {
                    synchronized (tailgateAbortLock) {
                        if (isTailgateOpenCancelled(cmd)) {
                            return new CloudCallResult(CloudOutcomeKind.FAILED,
                                    new java.util.concurrent.CancellationException(
                                            "tailgate open cancelled by stop"));
                        }
                        activeTailgateOpenClient.set(cloudClient);
                        Future<CloudOutcome> submitted = cloudExec.submit(cloudWork);
                        tailgateFuture.set(submitted);
                        activeTailgateOpenFuture.set(submitted);
                        future = submitted;
                    }
                } else {
                    future = cloudExec.submit(cloudWork);
                }
                CloudOutcome out = future.get(cmd.cloudTimeoutMs(), TimeUnit.MILLISECONDS);
                if (out.success) {
                    if (seatState != null) commitSeatCompositeState(seatState);
                    return new CloudCallResult(CloudOutcomeKind.SUCCESS, null);
                }
                if (out.blockedDriving) {
                    return new CloudCallResult(CloudOutcomeKind.BLOCKED_DRIVING, null);
                }
                if (out.rateLimited) return new CloudCallResult(CloudOutcomeKind.RATE_LIMITED, null);
                if (out.unsupported) return new CloudCallResult(CloudOutcomeKind.UNSUPPORTED, null);
                return new CloudCallResult(CloudOutcomeKind.FAILED, null);
            } catch (TimeoutException te) {
                // Mark cancellation before interrupting the worker. The transport
                // checks the interrupt both before and after registering each
                // OkHttp Call, closing the race where a new request otherwise
                // starts immediately after this timeout returns to the caller.
                cancelled.set(true);
                cancelCloudRequest(future, client, workerThread.get(), trackTailgateOpen);
                logger.warn("Cloud leg for '" + cmd.name() + "' timed out after "
                        + cmd.cloudTimeoutMs() + "ms");
                return new CloudCallResult(CloudOutcomeKind.FAILED, te);
            } catch (InterruptedException ie) {
                // Treat caller cancellation exactly like timeout. Returning
                // while the worker continues could dispatch a physical remote
                // command after its caller has already abandoned it.
                cancelled.set(true);
                cancelCloudRequest(future, client, workerThread.get(), trackTailgateOpen);
                Thread.currentThread().interrupt();
                return new CloudCallResult(CloudOutcomeKind.FAILED, ie);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof BydCloudClient.SmartChargeVehicleUnreachableException) {
                    return new CloudCallResult(CloudOutcomeKind.VEHICLE_UNREACHABLE, cause);
                }
                logger.warn("Cloud leg for '" + cmd.name() + "' failed: " + cause);
                return new CloudCallResult(CloudOutcomeKind.FAILED, cause);
            } catch (Exception e) {
                logger.warn("Cloud leg for '" + cmd.name() + "' failed: " + e);
                return new CloudCallResult(CloudOutcomeKind.FAILED, e);
            } finally {
                if (trackTailgateOpen) {
                    clearActiveTailgateOpen(future, workerThread.get(), client);
                }
            }
        }
    }

    /**
     * Shared helper for /control/remoteControl commands — issues the POST and
     * maps the BYD response to a {@link CloudOutcome}, recognizing the 6024
     * rate-limit code so the caller can stop the cascade and not race a
     * still-executing command.
     */
    private static CloudOutcome remoteCommand(BydCloudClient client, String vin,
                                              String commandType, JSONObject extra) throws Exception {
        BydCloudClient.CloudCommandResult r =
                client.executeRemoteCommandWithCode(vin, commandType, extra, true);
        if (r.success) return CloudOutcome.success();
        if (BydCloudClient.DRIVING_SAFETY_BLOCKED_CODE.equals(r.code)) {
            return CloudOutcome.blockedDriving();
        }
        if (CLOUD_CODE_RATE_LIMITED.equals(r.code)) return CloudOutcome.rateLimited();
        return CloudOutcome.failed();
    }

    private static boolean isCloudCallCancelled(AtomicBoolean cancelled) {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    /** Recheck motion in the cloud worker immediately before dispatch. */
    private boolean isCloudDispatchBlocked(VehicleCommand cmd) {
        if (cmd.motionSafety() != VehicleCommand.MotionSafety.BLOCK_WHILE_MOVING) {
            return false;
        }
        String reason = DrivingSafetyGuard.getActionBlockReason(cmd.motionSafetyGuardKey());
        if (reason == null) return false;
        logger.warn("Blocked cloud dispatch for '" + cmd.name()
                + "' — not confirmed parked (" + reason + ")");
        return true;
    }

    // ── Cloud handshake ─────────────────────────────────────────────────

    /**
     * Returns true iff the cloud leg has what it needs to dispatch. Honors
     * the per-command {@link CloudHandshake} declaration:
     * <ul>
     *   <li>{@code SESSION} — credentials + VIN verified (default).</li>
     *   <li>{@code LIVE_CHANNEL} — also need an active MQTT subscriber or
     *       REST poller (the {@code connected} bit on the data provider).</li>
     * </ul>
     * Most BYD remote-control commands work fine with just SESSION because
     * we poll {@code /control/remoteControlResult} over HTTP — there's no
     * live-channel dependency. Override LIVE_CHANNEL only if a command
     * genuinely needs a push subscription.
     */
    private boolean cloudHandshakeSatisfied(VehicleCommand cmd) {
        if (!cmd.hasCloudPath()) return false;
        try {
            BydCloudConfig cfg = BydCloudConfig.fromUnifiedConfig();
            if (!cfg.isVerified()) return false;
            if (cmd.cloudHandshake() == CloudHandshake.LIVE_CHANNEL) {
                JSONObject status = BydCloudDataProvider.getInstance().getStatusJson();
                return status != null && status.optBoolean("connected", false);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isVehicleAwake() {
        BydVehicleData d = BydDataCollector.getInstance().getData();
        return d != null && d.powerLevel != BydVehicleData.UNAVAILABLE && d.powerLevel >= 2;
    }

    /**
     * "Is someone in the car with it running?" — the gate for {@link
     * VehicleCommand#localOnlyWhenAwake()}.
     *
     * <p>Deliberately WIDER than {@link #isVehicleAwake()} and biased toward the local leg:
     * the cost of a wrong answer is asymmetric. Suppressing the cloud leg on a genuinely
     * asleep car only loses a remote pre-condition (recoverable, and the local write is still
     * attempted); allowing it on a running car powers the vehicle down with the driver in it.
     * So ACC-on (the daemon's own authoritative power-rail state, which is what a parked-in-P
     * occupant reports) counts as awake even if the {@code powerLevel} snapshot is stale or
     * missing — the collector only refreshes on its poll cadence.
     */
    private boolean isVehicleOccupiedOrAwake() {
        if (isVehicleAwake()) return true;
        try {
            return com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative()
                    && com.overdrive.app.monitor.AccMonitor.isAccOn();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Refuses a motion-sensitive command while the vehicle is moving. Returns
     * {@code null} when the command may proceed (either it's not motion-sensitive,
     * or {@link DrivingSafetyGuard} confirms the vehicle is safely parked).
     *
     * <p>Called from both {@link #execute} and {@link #executeSdkOnly} — they are
     * independent public entry points (the MQTT/Home-Assistant path calls
     * executeSdkOnly directly, never execute), so each needs its own call to this
     * shared helper rather than relying on a single insertion point.
     */
    private CommandResult checkDrivingSafety(VehicleCommand cmd) {
        if (cmd.motionSafety() != VehicleCommand.MotionSafety.BLOCK_WHILE_MOVING) return null;
        String reason = DrivingSafetyGuard.getActionBlockReason(cmd.motionSafetyGuardKey());
        if (reason == null) return null;
        logger.warn("Blocked '" + cmd.name() + "' — not confirmed parked (" + reason + ")");
        return CommandResult.blocked(msg("blocked_driving"));
    }

    /** An expired/cancelled generic frame cannot escape through an explicit second cloud leg. */
    private static CommandResult stoppedLocalAttempt(VehicleCommand cmd, long elapsed) {
        com.overdrive.app.byd.LocalActuationAbort stopped = cmd.localAttempt().cloudAbort();
        if (stopped == null) return null;
        return stopped.kind == com.overdrive.app.byd.LocalActuationAbort.Kind.BLOCKED_DRIVING
                ? CommandResult.blocked(msg("blocked_driving"))
                : CommandResult.failed(Path.SDK, msg("local_failed"), elapsed, stopped);
    }

    // ── i18n key resolution ─────────────────────────────────────────────

    private static String msg(String key) {
        return com.overdrive.app.server.Messages.get("vehicle_control." + key);
    }
}
