package com.overdrive.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.overdrive.app.R;
import com.overdrive.app.byd.BydConstants;
import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.byd.BydFeatureIds;
import com.overdrive.app.byd.routing.DrivingSafetyGuard;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * App-process actuator for exterior-mirror fold, persistent auto-fold preference, HUD and
 * powertrain mode, which do NOT necessarily actuate from the headless daemon even though its
 * reflection call is byte-identical to the OEM.
 *
 * <p>The daemon performs the synchronous attempt first, then starts this service when a command
 * needs an app-process retry. BYD device wrappers perform local signature-permission checks on
 * their supplied Context, so this service uses the shared, narrowly scoped BYD permission
 * context. On the connected model, mirror actuation targets the write-only Setting command
 * {@code 1023 / 0x4C10A028 / 1|2}; the older bodywork 1/0 API remains a compatibility fallback.
 * Raw return codes and available state readback are logged so physical validation does not rely
 * on accept-on-no-throw behavior.
 *
 * <p>Intent extras: {@code action=mirror} + {@code fold}=true|false;
 * {@code action=mirror_auto_follow_up} + {@code enabled}=true|false;
 * {@code action=hud} + {@code level}=0..100 (brightness);
 * {@code action=hud_power} + {@code on}=true|false (the dedicated HUD switch);
 * {@code action=ac_charge_current_limit} + {@code state}=1..5;
 * {@code action=energy_mode} + {@code mode}=1..5 (powertrain EV/HEV);
 * {@code action=carpower_backlight} + {@code mode}=read|on|off (DiLink 5
 * {@code CarPowerManager} bind probe).
 * Mirror/HUD/current-limit
 * writes are serialized off the main thread so their daemon-backed safety state can be rechecked
 * immediately before actuation. Energy writes use their own serialized lane and bounded readback.
 */
public class VehicleActuatorService extends Service {

    private static final String TAG = "VehicleActuator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String CHANNEL_ID = "overdrive_vehicle_actuator";
    private static final int NOTIFICATION_ID = 9973;

    private static final String SETTING_DEVICE = "android.hardware.bydauto.setting.BYDAutoSettingDevice";
    private static final String BODYWORK_DEVICE = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";
    private static final int BODYWORK_DEVICE_TYPE = 1001;
    private static final String INSTRUMENT_DEVICE = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice";
    private static final String ENERGY_DEVICE = "android.hardware.bydauto.energy.BYDAutoEnergyDevice";
    private static final long ENERGY_VERIFY_TIMEOUT_MS = 1500L;
    private static final long ENERGY_VERIFY_POLL_MS = 100L;
    private static final long ENERGY_READ_TIMEOUT_MS = 250L;
    private static final long ENERGY_HAL_WRITE_TIMEOUT_MS = 1000L;
    private static final int ENERGY_RECONCILE_MAX_ATTEMPTS = 6;
    private static final long ENERGY_RECONCILE_BASE_DELAY_MS = 250L;
    private static final long ENERGY_RECONCILE_MAX_DELAY_MS = 4000L;
    private static final SourceGenerationGate ENERGY_SOURCE_GENERATIONS =
            new SourceGenerationGate();
    /**
     * Process-lifetime lanes cap Binder damage across service recreation. A HAL call can ignore
     * interruption; if one wedges, its lane rejects later work instead of leaking a new thread for
     * every foreground-service start.
     */
    private static final EnergyHalLane ENERGY_HAL_LANE = new EnergyHalLane();
    private static final ThreadPoolExecutor ENERGY_READ_EXECUTOR =
            newProcessHalExecutor("ActuatorEnergyRead");
    private static final AtomicBoolean ENERGY_READ_STALLED = new AtomicBoolean();

    private final Object lifecycleLock = new Object();
    private final EnergyModeArbiter energyModeArbiter = new EnergyModeArbiter();
    private final EnergyModeQueue energyModeQueue = new EnergyModeQueue();
    private final AtomicInteger activeEnergyHalChains = new AtomicInteger();
    private ExecutorService guardedActuatorExecutor;
    private ExecutorService energyExecutor;
    private ScheduledThreadPoolExecutor energyReconcileExecutor;
    private final Object energyReconcileLock = new Object();
    private ScheduledFuture<?> energyReconcileFuture;
    private long energyReconcileTicket;
    private int energyReconcileFailureCount;
    private volatile boolean energyReconciliationScheduled;
    private int activeStartCommands;
    private int latestStartId;
    private volatile boolean destroyed;
    private volatile boolean foregroundStarted;

    protected boolean supportsEnergyMode() {
        return false;
    }

    @Override public void onCreate() {
        super.onCreate();
        guardedActuatorExecutor = Executors.newSingleThreadExecutor(
                r -> daemonThread(r, "ActuatorSafety"));
        if (supportsEnergyMode()) {
            energyExecutor = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                    r -> daemonThread(r, "ActuatorEnergy"), new ThreadPoolExecutor.AbortPolicy());
            energyReconcileExecutor = new ScheduledThreadPoolExecutor(
                    1, r -> daemonThread(r, "ActuatorEnergyReconcile"),
                    new ThreadPoolExecutor.AbortPolicy());
            energyReconcileExecutor.setRemoveOnCancelPolicy(true);
        }
        createChannel();
        foregroundStarted = startForegroundCompat();
        if (!foregroundStarted) {
            synchronized (lifecycleLock) {
                destroyed = true;
            }
            shutdownInstanceExecutors();
            stopSelf();
        } else if (supportsEnergyMode()) {
            // Process-wide HAL work can outlive a service object. Adopt any active/pending chain so
            // recreation keeps the live foreground instance leased until that work settles.
            ENERGY_HAL_LANE.attachOwner(this);
            reconcilePersistedEnergyState();
        }
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread worker = new Thread(runnable, name);
        worker.setDaemon(true);
        return worker;
    }

    private static ThreadPoolExecutor newProcessHalExecutor(String name) {
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                runnable -> daemonThread(runnable, name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static boolean isProcessHalLaneStalled(
            ThreadPoolExecutor executor, AtomicBoolean stalled) {
        if (!stalled.get()) return false;
        if (executor.getActiveCount() != 0) return true;
        stalled.set(false);
        return false;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        beginStartCommand(startId);
        // Read the extras INSIDE the try: getStringExtra unparcels the whole bundle. Even though
        // starts are DUMP-permission-gated, a malformed shell/system bundle must not escape
        // onStartCommand and kill the process.
        String action = null;
        boolean finishesAsync = false;
        try {
            if (!foregroundStarted) {
                Log.e(TAG, "refusing actuation because foreground promotion failed");
                return START_NOT_STICKY;
            }
            if (intent == null) {
                Log.w(TAG, "null start intent");
                if (supportsEnergyMode()) reconcilePersistedEnergyState();
                return START_STICKY;
            }
            action = intent.getStringExtra("action");
            try {
                java.io.File dir = getExternalFilesDir(null);
                if (dir != null) {
                    java.io.File f = new java.io.File(dir, "carpower_probe.txt");
                    java.io.FileWriter w = new java.io.FileWriter(f, false);
                    w.write("action=" + action
                            + " extras=" + intent.getExtras()
                            + " fg=" + foregroundStarted
                            + "\n");
                    w.close();
                }
            } catch (Throwable ignored) {}
            if ("mirror".equals(action)) {
                boolean fold = intent.getBooleanExtra("fold", false);
                finishesAsync = submitGuardedActuation(
                        fold ? DrivingSafetyGuard.GUARD_MIRROR_FOLD : null,
                        "mirror fold=" + fold,
                        () -> logger.info(
                                "mirror fold=" + fold + " -> ok=" + setMirrorsFolded(fold)));
            } else if ("mirror_auto_follow_up".equals(action)) {
                boolean enabled = intent.getBooleanExtra("enabled", false);
                logger.info("mirror auto follow-up enabled=" + enabled + " -> ok="
                        + setAutoExternalRearMirrorFollowUp(enabled));
            } else if ("hud".equals(action)) {
                int level = intent.getIntExtra("level", -1);
                finishesAsync = submitGuardedActuation(
                        DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS,
                        "hud level=" + level,
                        () -> Log.i(TAG, "hud level=" + level + " -> ok=" + setHud(level)));
            } else if ("hud_power".equals(action)) {
                boolean on = intent.getBooleanExtra("on", false);
                finishesAsync = submitGuardedActuation(
                        on ? null : DrivingSafetyGuard.GUARD_DISPLAY_POWER,
                        "hud_power on=" + on,
                        () -> Log.i(TAG,
                                "hud_power on=" + on + " -> ok=" + setHudPower(on)));
            } else if ("ac_charge_current_limit".equals(action)) {
                int state = parseBoundedIntExtra(
                        intent.getStringExtra("state"),
                        intent.getIntExtra("state", -1),
                        com.overdrive.app.byd.BydDataCollector.AC_CHARGE_CURRENT_6A,
                        com.overdrive.app.byd.BydDataCollector.AC_CHARGE_CURRENT_MAX);
                Log.i(TAG, "ac_charge_current_limit state=" + state
                        + " -> ok=" + setAcChargeCurrentLimit(state));
            } else if (supportsEnergyMode() && "energy_mode".equals(action)) {
                int mode = parseEnergyModeExtra(
                        intent.getStringExtra("mode"),
                        intent.getIntExtra("mode", -1));
                long sourceGeneration = parseEnergyGenerationExtra(
                        intent.getStringExtra("request_generation"), -1L);
                // "started" not "ok": the HAL write runs off-thread, so its verdict is logged there.
                boolean started = setEnergyMode(mode, sourceGeneration);
                Log.i(TAG, "energy_mode mode=" + mode + " sourceGeneration=" + sourceGeneration
                        + " -> started=" + started);
            } else if (supportsEnergyMode() && "energy_mode_cancel".equals(action)) {
                long sourceGeneration = parseEnergyGenerationExtra(
                        intent.getStringExtra("request_generation"), -1L);
                Log.i(TAG, "energy_mode_cancel sourceGeneration=" + sourceGeneration
                        + " -> accepted=" + cancelEnergyMode(sourceGeneration));
            } else if (supportsEnergyMode() && "energy_mode_fence".equals(action)) {
                long sourceGeneration = parseEnergyGenerationExtra(
                        intent.getStringExtra("request_generation"), -1L);
                Log.i(TAG, "energy_mode_fence sourceGeneration=" + sourceGeneration
                        + " -> accepted=" + fenceEnergyMode(sourceGeneration));
            } else if ("carpower_backlight".equals(action)) {
                String mode = intent.getStringExtra("mode");
                finishesAsync = submitGuardedActuation(
                        null,
                        "carpower_backlight mode=" + mode,
                        () -> logger.info("carpower_backlight -> "
                                + com.overdrive.app.power.TsCarPowerClient.probe(
                                        getApplicationContext(), mode)));
            } else {
                Log.w(TAG, "unknown action: " + action);
            }
        } catch (Throwable t) {
            Log.w(TAG, "actuation failed (" + action + "): " + t.getMessage());
        } finally {
            if (!finishesAsync) finishStartCommand();
        }
        return supportsEnergyMode() && action != null && action.startsWith("energy_mode")
                ? START_REDELIVER_INTENT : START_NOT_STICKY;
    }

    private boolean submitGuardedActuation(
            String guardKey, String description, Runnable actuation) {
        ExecutorService executor = guardedActuatorExecutor;
        if (executor == null || executor.isShutdown()) {
            logger.warn("refusing " + description + ": actuator worker unavailable");
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    if (guardKey != null
                            && isAppProcessActionBlocked(guardKey)) {
                        logger.warn("blocked delayed app-process actuation: " + description);
                        return;
                    }
                    actuation.run();
                } catch (Throwable t) {
                    logger.warn("app-process actuation failed (" + description + "): "
                            + t.getMessage());
                } finally {
                    finishStartCommand();
                }
            });
            return true;
        } catch (Throwable unavailable) {
            logger.warn("refusing " + description + ": "
                    + unavailable.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isAppProcessActionBlocked(String guardKey) {
        return DrivingSafetyGuard.isActionBlockedViaDaemon(guardKey);
    }

    /**
     * Consume an uncompleted boot-scoped request after service or process recreation. A confirmed
     * cancellation with durable rollback metadata is also resumed here.
     */
    private void reconcilePersistedEnergyState() {
        final long ticket;
        synchronized (energyReconcileLock) {
            ticket = ++energyReconcileTicket;
            energyReconcileFailureCount = 0;
            if (energyReconcileFuture != null) {
                energyReconcileFuture.cancel(false);
                energyReconcileFuture = null;
            }
            energyReconciliationScheduled = false;
        }
        reconcilePersistedEnergyState(ticket, 0);
    }

    private void rearmPersistedEnergyReconciliation() {
        final long ticket;
        final int attempt;
        synchronized (energyReconcileLock) {
            ticket = ++energyReconcileTicket;
            attempt = ++energyReconcileFailureCount;
            if (energyReconcileFuture != null) {
                energyReconcileFuture.cancel(false);
                energyReconcileFuture = null;
            }
            energyReconciliationScheduled = false;
        }
        schedulePersistedEnergyReconciliation(ticket, attempt);
    }

    private void notePersistedEnergyReconciliationProgress() {
        synchronized (energyReconcileLock) {
            energyReconcileFailureCount = 0;
        }
    }

    private void reconcilePersistedEnergyState(long ticket, int attempt) {
        if (destroyed) return;
        synchronized (energyReconcileLock) {
            if (ticket != energyReconcileTicket || destroyed) return;
            energyReconcileFailureCount =
                    Math.max(energyReconcileFailureCount, attempt);
        }
        com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRead read =
                readPublishedEnergyStateWithRetry(getApplicationContext());
        if (read.status
                != com.overdrive.app.byd.VehicleActuatorBridge.EnergyReadStatus.VALID
                || read.request == null) {
            if (read.status
                    != com.overdrive.app.byd.VehicleActuatorBridge.EnergyReadStatus.MISSING) {
                schedulePersistedEnergyReconciliation(ticket, attempt + 1);
            }
            return;
        }
        com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRequest marker =
                read.request;
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        boolean plausible = marker.cancelled
                ? SourceGenerationGate.isPlausibleForMutation(marker.generation, nowNanos)
                : SourceGenerationGate.isPlausible(marker.generation, nowNanos);
        if (!plausible) {
            schedulePersistedEnergyReconciliation(ticket, attempt + 1);
            return;
        }
        ENERGY_SOURCE_GENERATIONS.adoptPublished(
                marker.generation, marker.cancelled);
        if (marker.cancelled) {
            if (marker.rollbackPending
                    && marker.rollbackOwner
                    == com.overdrive.app.byd.VehicleActuatorBridge.ENERGY_ACTUATOR_APP
                    && marker.compensationAttempts
                    < com.overdrive.app.byd.VehicleActuatorBridge
                    .MAX_ENERGY_COMPENSATION_ATTEMPTS) {
                if (!ENERGY_HAL_LANE.reconcileCancellation(
                        this, getApplicationContext(), marker)) {
                    schedulePersistedEnergyReconciliation(ticket, attempt + 1);
                }
            }
            return;
        }
        if (!marker.pending && marker.applied) {
            if (!ENERGY_HAL_LANE.reconcileDesired(
                    this, getApplicationContext(), marker)) {
                schedulePersistedEnergyReconciliation(ticket, attempt + 1);
            }
        } else if (!marker.pending) {
            if (!setEnergyMode(marker.mode, marker.generation)) {
                schedulePersistedEnergyReconciliation(ticket, attempt + 1);
            }
        }
    }

    private void schedulePersistedEnergyReconciliation(long ticket, int attempt) {
        if (attempt > ENERGY_RECONCILE_MAX_ATTEMPTS || destroyed) {
            Log.w(TAG, "persisted energy reconciliation exhausted after "
                    + Math.max(0, attempt - 1) + " retries");
            energyReconciliationScheduled = false;
            stopWhenIdle();
            return;
        }
        long shift = Math.min(4, Math.max(0, attempt - 1));
        long delay = Math.min(
                ENERGY_RECONCILE_MAX_DELAY_MS,
                ENERGY_RECONCILE_BASE_DELAY_MS << shift);
        boolean schedulingFailed = false;
        synchronized (energyReconcileLock) {
            if (destroyed || ticket != energyReconcileTicket) return;
            ScheduledThreadPoolExecutor executor = energyReconcileExecutor;
            if (executor == null || executor.isShutdown()) {
                energyReconcileFuture = null;
                energyReconciliationScheduled = false;
                schedulingFailed = true;
            } else {
                if (energyReconcileFuture != null) energyReconcileFuture.cancel(false);
                energyReconciliationScheduled = true;
                try {
                    energyReconcileFuture = executor.schedule(() -> {
                        synchronized (energyReconcileLock) {
                            if (ticket != energyReconcileTicket || destroyed) return;
                            energyReconcileFuture = null;
                            energyReconciliationScheduled = false;
                        }
                        reconcilePersistedEnergyState(ticket, attempt);
                        stopWhenIdle();
                    }, delay, TimeUnit.MILLISECONDS);
                } catch (Throwable unavailable) {
                    energyReconcileFuture = null;
                    energyReconciliationScheduled = false;
                    schedulingFailed = true;
                    Log.w(TAG, "could not schedule persisted energy reconciliation: "
                            + unavailable.getMessage());
                }
            }
        }
        if (schedulingFailed) stopWhenIdle();
    }

    private void beginStartCommand(int startId) {
        synchronized (lifecycleLock) {
            activeStartCommands++;
            latestStartId = startId;
        }
    }

    private void finishStartCommand() {
        synchronized (lifecycleLock) {
            activeStartCommands--;
        }
        stopWhenIdle();
    }

    /**
     * Stop only after every accepted asynchronous request has finished. {@code stopSelfResult}
     * prevents an older completion from stopping a newer start delivered between the idle check
     * and this call.
     */
    private void stopWhenIdle() {
        final int stopId;
        synchronized (lifecycleLock) {
            if (destroyed || activeStartCommands != 0 || energyModeArbiter.pendingCount() != 0
                    || activeEnergyHalChains.get() != 0
                    || energyReconciliationScheduled) {
                return;
            }
            stopId = latestStartId;
        }
        if (stopId > 0) {
            stopSelfResult(stopId);
        } else {
            stopSelf();
        }
    }

    /**
     * `am` on this firmware accepts only string/boolean extras for this service.
     * Retain the integer read for callers built before the bridge switched to --es.
     */
    static int parseEnergyModeExtra(String stringMode, int legacyMode) {
        if (stringMode == null) return legacyMode;
        try {
            return Integer.parseInt(stringMode.trim());
        } catch (NumberFormatException ignored) {
            return legacyMode;
        }
    }

    static long parseEnergyGenerationExtra(String stringGeneration, long legacyGeneration) {
        if (stringGeneration == null) return legacyGeneration;
        try {
            return Long.parseLong(stringGeneration.trim());
        } catch (NumberFormatException ignored) {
            return legacyGeneration;
        }
    }

    private static int parseBoundedIntExtra(
            String stringValue, int legacyValue, int minimum, int maximum) {
        int value = legacyValue;
        if (stringValue != null) {
            try {
                value = Integer.parseInt(stringValue.trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return value >= minimum && value <= maximum ? value : -1;
    }

    /**
     * Fold ({@code true}) / unfold ({@code false}) the exterior mirrors from the app process.
     *
     * <p>The connected model's DiCar write profile assigns the manual fold command to the Setting
     * device (type 1023), feature {@code 0x4C10A028}, with 1=fold / 2=unfold. Older firmware may
     * instead expose the bodywork named method with 1/0, retained as a compatibility fallback.
     */
    private boolean setMirrorsFolded(boolean fold) {
        Context bydContext = BydDeviceHelper.withBydPermissionBypass(getApplicationContext());
        int value = BydConstants.mirrorFoldCommand(fold);
        Object settingDevice = BydDeviceHelper.getDevice(
                BydConstants.MIRROR_FOLD_SETTING_DEVICE_CLASS, bydContext);
        int code = Integer.MIN_VALUE;
        if (settingDevice != null) {
            // This is the same public set(int[], EventValue) shape used by DiCar's HalSetter.
            if (fold && isAppProcessActionBlocked(
                    DrivingSafetyGuard.GUARD_MIRROR_FOLD)) return false;
            code = BydDeviceHelper.sendSetCommandRaw(
                    settingDevice,
                    BydFeatureIds.SETTING_OUTSIDE_REARVIEW_MIRROR_FOLD_SET,
                    value);
            logger.info("mirror setting event(device="
                    + BydConstants.MIRROR_FOLD_SETTING_DEVICE_TYPE
                    + ", SET_OUTSIDE_REARVIEW_MIRROR_FOLD_SET, " + value + ") -> code=" + code
                    + (code == 0 ? " ACCEPTED" : " REFUSED"));
            if (code == 0) {
                logManualMirrorReadback(bydContext, value);
                return true;
            }

            // Bypass only the SDK wrapper's local feature-list validation; the manager/HAL still
            // receives the identical Setting device, feature id and value.
            if (fold && isAppProcessActionBlocked(
                    DrivingSafetyGuard.GUARD_MIRROR_FOLD)) return false;
            code = BydDeviceHelper.callSetSingle(
                    settingDevice,
                    BydFeatureIds.SETTING_OUTSIDE_REARVIEW_MIRROR_FOLD_SET,
                    value);
            logger.info("direct mirror setting set(device="
                    + BydConstants.MIRROR_FOLD_SETTING_DEVICE_TYPE
                    + ", SET_OUTSIDE_REARVIEW_MIRROR_FOLD_SET, " + value + ") -> code=" + code
                    + (code == 0 ? " ACCEPTED" : " REFUSED"));
            if (code == 0) {
                logManualMirrorReadback(bydContext, value);
                return true;
            }
        }

        // callSetSingle already invokes manager.setInt. Use the manager directly only when the
        // singleton or its inherited protected setter was unavailable.
        if (settingDevice == null || code == -1 || code == Integer.MIN_VALUE) {
            if (fold && isAppProcessActionBlocked(
                    DrivingSafetyGuard.GUARD_MIRROR_FOLD)) return false;
            int managerCode = BydDeviceHelper.callManagerSetInt(
                    bydContext,
                    BydConstants.MIRROR_FOLD_SETTING_DEVICE_TYPE,
                    BydFeatureIds.SETTING_OUTSIDE_REARVIEW_MIRROR_FOLD_SET,
                    value);
            logger.info("mirror setting manager set(device="
                    + BydConstants.MIRROR_FOLD_SETTING_DEVICE_TYPE
                    + ", SET_OUTSIDE_REARVIEW_MIRROR_FOLD_SET, " + value
                    + ") -> code=" + managerCode
                    + (managerCode == 0 ? " ACCEPTED" : " REFUSED"));
            if (managerCode == 0) {
                logManualMirrorReadback(bydContext, value);
                return true;
            }
        }

        logger.warn("mirror Setting route refused; trying legacy bodywork API");
        return setMirrorsFoldedViaLegacyBodywork(bydContext, fold);
    }

    private void logManualMirrorReadback(Context bydContext, int expected) {
        Object stateDevice = BydDeviceHelper.getDevice(
                BydConstants.REAR_VIEW_MIRROR_DEVICE_CLASS, bydContext);
        Object state = BydDeviceHelper.callGetter(
                stateDevice, "getAutoExternalRearMirrorState");
        if (state instanceof Number) {
            logger.info("mirror state=" + ((Number) state).intValue()
                    + " expectedCommand=" + expected);
        } else {
            logger.info("mirror state unavailable after accepted write");
        }
    }

    private boolean setMirrorsFoldedViaLegacyBodywork(Context bydContext, boolean fold) {
        Object device = BydDeviceHelper.getDevice(BODYWORK_DEVICE, bydContext);
        if (device == null) {
            if (fold && isAppProcessActionBlocked(
                    DrivingSafetyGuard.GUARD_MIRROR_FOLD)) return false;
            int code = BydDeviceHelper.callManagerSetInt(
                    bydContext,
                    BODYWORK_DEVICE_TYPE,
                    BydFeatureIds.MIRROR_REARVIEW_SET,
                    fold ? 1 : 0);
            logger.warn("bodywork device unavailable after BYD permission-context acquisition;"
                    + " direct manager mirror write -> code=" + code
                    + (code == 0 ? " ACCEPTED" : " REFUSED"));
            return code == 0;
        }
        logger.info("bodywork device acquired via BYD permission context");
        int val = fold ? 1 : 0;
        try {
            Method m = device.getClass().getMethod("setMirrorFoldState", int.class);
            if (fold && isAppProcessActionBlocked(
                    DrivingSafetyGuard.GUARD_MIRROR_FOLD)) return false;
            Object r = m.invoke(device, val);
            // CHECK the result. This used to `return true` on any non-throwing invoke, so a
            // HAL that refused the write (BODYWORK_COMMAND_FAILED = -2147482648, returned
            // WITHOUT throwing) still logged "ok=true" — making this log useless as evidence
            // of actuation. The OEM's own predicate is equality with
            // BODYWORK_COMMAND_SUCCESS == 0; a void/null return means "returned without
            // throwing", which is the best signal available on trims that declare it void.
            boolean ok = !(r instanceof Integer) || ((Integer) r) == 0;
            if (r instanceof Boolean) ok = (Boolean) r;
            logger.info("setMirrorFoldState(" + val + ") returned " + r + " -> "
                    + (ok ? "ACCEPTED" : "REFUSED"));
            if (ok) return true;
        } catch (NoSuchMethodException nsme) {
            logger.warn("setMirrorFoldState absent on this trim");
        } catch (Throwable t) {
            logger.warn("setMirrorFoldState failed: " + t.getMessage());
        }
        // Named method missing or refused — invoke the bodywork base setter directly. The
        // connected DiLink 3 framework has no setMirrorFoldState method and its public
        // event-value setter validates against a feature list that omits the mirror command.
        // The protected three-int setter is the compatible route used by newer-device wrappers.
        try {
            if (fold && isAppProcessActionBlocked(
                    DrivingSafetyGuard.GUARD_MIRROR_FOLD)) return false;
            int code = BydDeviceHelper.callSetSingle(
                    device, BydFeatureIds.MIRROR_REARVIEW_SET, val);
            logger.info("direct BODYWORK_REARVIEW_MIRROR_SET(" + val + ") -> code=" + code
                    + (code == 0 ? " ACCEPTED" : " REFUSED"));
            if (code == 0) return true;
        } catch (Throwable t) {
            logger.warn("direct mirror feature-id write failed: " + t.getMessage());
        }

        // Retain the standard public event-value API for firmware that advertises this command.
        try {
            if (fold && isAppProcessActionBlocked(
                    DrivingSafetyGuard.GUARD_MIRROR_FOLD)) return false;
            int code = BydDeviceHelper.sendSetCommandRaw(
                    device, BydFeatureIds.MIRROR_REARVIEW_SET, val);
            logger.info("public BODYWORK_REARVIEW_MIRROR_SET(" + val + ") -> code=" + code
                    + (code == 0 ? " ACCEPTED" : " REFUSED"));
            return code == 0;
        } catch (Throwable t) {
            logger.warn("public mirror feature-id write failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Write and verify the OEM persistent mirror follow-up preference from the real app process.
     * The SET_ON / SET_OFF contract is 1 / 0 and, unlike the immediate mirror command, the
     * setting device exposes a companion getter for confirmation.
     */
    private boolean setAutoExternalRearMirrorFollowUp(boolean enabled) {
        Object device = com.overdrive.app.byd.BydDeviceHelper.getDevice(
                SETTING_DEVICE, getApplicationContext());
        if (device == null) {
            logger.warn("setting device unavailable for mirror auto follow-up");
            return false;
        }
        int value = enabled ? 1 : 0;
        try {
            Method setter = device.getClass().getMethod(
                    "setAutoExternalRearMirrorFollowUpSwitch", int.class);
            Object result = setter.invoke(device, value);
            boolean accepted = isWriteAccepted(device, result);
            logger.info("setAutoExternalRearMirrorFollowUpSwitch(" + value + ") returned "
                    + result + " -> " + (accepted ? "ACCEPTED" : "REFUSED"));
            if (!accepted) return false;

            Object readback = device.getClass()
                    .getMethod("getAutoExternalRearMirrorFollowUpSwitch")
                    .invoke(device);
            if (readback instanceof Number && ((Number) readback).intValue() != value) {
                logger.warn("mirror auto follow-up read back " + readback
                        + " after requesting " + value);
                return false;
            }
            logger.info("mirror auto follow-up confirmed=" + readback);
            return true;
        } catch (NoSuchMethodException e) {
            logger.warn("mirror auto follow-up is unavailable on this firmware");
            return false;
        } catch (Throwable t) {
            logger.warn("mirror auto follow-up write failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * HUD on/off + brightness (0..100). The OEM reference calls
     * {@code BYDAutoSettingDevice.setHUDBrightness(int)} from the app process; run it here in
     * the same environment. Accept-on-no-throw.
     */
    private boolean setHud(int level) {
        if (level < 0 || level > 100) return false;
        Object device = com.overdrive.app.byd.BydDeviceHelper.getDevice(SETTING_DEVICE, getApplicationContext());
        if (device == null) { Log.w(TAG, "setting device unavailable"); return false; }
        try {
            Method m = device.getClass().getMethod("setHUDBrightness", int.class);
            if (isAppProcessActionBlocked(
                    DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) return false;
            m.invoke(device, level);
            return true;
        } catch (NoSuchMethodException nsme) {
            Log.w(TAG, "setHUDBrightness absent on this trim");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "setHUDBrightness failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * HUD power on/off — the DEDICATED switch, distinct from brightness. Writes the
     * {@code SET_HUD_SWITCH_SET} setting feature-id as a {@code BYDAutoEventValue} via the
     * standard {@code BYDAutoSettingDevice.set(int[], EventValue)} path (the same
     * sendSetCommand mechanism every other setting write uses), from the REAL app process
     * where setting-HAL writes actually land (the UID-2000 daemon's silently no-op). The
     * OEM contract is value 1 = on, 2 = off (NOT 0). Best-effort: acquires the setting
     * device via {@code getApplicationContext()} and returns whether the write was accepted.
     */
    private boolean setHudPower(boolean on) {
        Object device = com.overdrive.app.byd.BydDeviceHelper.getDevice(SETTING_DEVICE, getApplicationContext());
        if (device == null) { Log.w(TAG, "setting device unavailable"); return false; }
        int val = on ? 1 : 2; // OEM: 1=on, 2=off
        if (!on && isAppProcessActionBlocked(
                DrivingSafetyGuard.GUARD_DISPLAY_POWER)) return false;
        boolean ok = com.overdrive.app.byd.BydDeviceHelper.sendSetCommand(
                device, com.overdrive.app.byd.BydFeatureIds.SETTING_HUD_SWITCH_SET, val);
        Log.i(TAG, "setHudPower SET_HUD_SWITCH_SET(" + val + ") accepted=" + ok);
        return ok;
    }

    /**
     * Set the AC inlet current limit from the normal application Context.
     *
     * <p>The daemon performs the authoritative API readback. This local check is diagnostic and
     * prevents the service log from claiming success solely because the SDK accepted the command.
     */
    private boolean setAcChargeCurrentLimit(int state) {
        if (state < com.overdrive.app.byd.BydDataCollector.AC_CHARGE_CURRENT_6A
                || state > com.overdrive.app.byd.BydDataCollector.AC_CHARGE_CURRENT_MAX
                || !BydFeatureIds.isResolved(
                        BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS_SET)
                || !BydFeatureIds.isResolved(
                        BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS)) {
            return false;
        }
        Context bydContext = BydDeviceHelper.withBydPermissionBypass(getApplicationContext());
        Object device = BydDeviceHelper.getDevice(SETTING_DEVICE, bydContext);
        if (device == null) {
            Log.w(TAG, "setting device unavailable for AC charge current limit");
            return false;
        }
        int configState = readSettingInt(
                device, BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_CONFIG_STATUS);
        int currentState = readSettingInt(
                device, BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS);
        if (!Boolean.TRUE.equals(
                com.overdrive.app.byd.BydDataCollector.resolveAcChargingCurrentLimitSupport(
                        configState, currentState, null))) {
            Log.w(TAG, "AC charge current limit read side did not prove capability"
                    + " config=" + configState + " state=" + currentState);
            return false;
        }
        boolean accepted = BydDeviceHelper.sendSetCommand(
                device,
                BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS_SET,
                state);
        if (!accepted) return false;

        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(150L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            int readBack = readSettingInt(
                    device, BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS);
            if (readBack == state) return true;
        }
        return false;
    }

    private static int readSettingInt(Object device, int featureId) {
        if (device == null || !BydFeatureIds.isResolved(featureId)) {
            return com.overdrive.app.byd.BydVehicleData.UNAVAILABLE;
        }
        try {
            Object value = BydDeviceHelper.callGet(device, featureId, Integer.TYPE);
            return value != null
                    ? BydDeviceHelper.getIntValue(value)
                    : com.overdrive.app.byd.BydVehicleData.UNAVAILABLE;
        } catch (Throwable unavailable) {
            return com.overdrive.app.byd.BydVehicleData.UNAVAILABLE;
        }
    }

    /**
     * Powertrain mode (EV/HEV) via {@code BYDAutoEnergyDevice.setEnergyMode(int)}, run here in the
     * real app process with a handle resolved from {@code getApplicationContext()}.
     *
     * <p>Only in-domain values are written; {@code 0} (STOP) is refused because it is not a user
     * powertrain preference. It is still a valid current readback while the car is stationary, so
     * it must not block a requested EV/HEV transition. STOP is never armed as a rollback command.
     * The result IS inspected, using the same convention as the daemon-side judge: compare against
     * the device's own {@code ENERGY_COMMAND_SUCCESS} constant when the firmware exposes it, else
     * accept any non-negative (documented failures are large negatives returned WITHOUT throwing).
     * Hardcoding {@code == 0} here would report REFUSED for a success on any firmware whose SUCCESS
     * constant is non-zero, disagreeing with the daemon's verdict on the identical write. A
     * post-write read of the axis is the only evidence that distinguishes an
     * accepted-and-applied write from an accepted-and-ignored one.
     *
     * <p>The write runs on one serialized executor, not {@code onStartCommand}'s main thread. A
     * generation ticket plus a one-slot conflating queue gives latest-request-wins behavior without
     * retaining one runnable per superseded command. Potentially blocking HAL writes and readback
     * diagnostics use separate process-lifetime bounded lanes. A wedged setter retains one latest
     * corrective request and one foreground-service lifecycle lease, so it cannot accumulate
     * threads or leave its stale mode final if it eventually returns.
     */
    private boolean setEnergyMode(int mode, long sourceGeneration) {
        if (!isUserWritableEnergyMode(mode)) {
            Log.w(TAG, "energy mode is not user-writable: " + mode
                    + " (valid: 1=EV, 3=HEV)");
            return false;
        }
        final ExecutorService executor = energyExecutor;
        if (executor == null || executor.isShutdown() || destroyed) {
            Log.w(TAG, "energy executor unavailable");
            return false;
        }

        // Keep source-token admission, pending replacement and first-worker submission atomic.
        // Otherwise a second caller can observe "worker scheduled" and return success while the
        // first caller is still discovering that execute() was rejected.
        synchronized (energyModeQueue) {
            if (executor.isShutdown() || destroyed) {
                Log.w(TAG, "energy executor unavailable");
                return false;
            }
            com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRead publishedRead =
                    readPublishedEnergyStateWithRetry(getApplicationContext());
            com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRequest published =
                    publishedRead.request;
            if (publishedRead.status
                    != com.overdrive.app.byd.VehicleActuatorBridge.EnergyReadStatus.VALID
                    || published == null) {
                Log.w(TAG, "energy mode " + mode + " sourceGeneration=" + sourceGeneration
                        + " rejected: latest-state marker status="
                        + publishedRead.status);
                return false;
            }
            final long sourceNow = SystemClock.elapsedRealtimeNanos();
            if (!SourceGenerationGate.isPlausible(published.generation, sourceNow)) {
                Log.w(TAG, "energy mode " + mode + " sourceGeneration=" + sourceGeneration
                        + " rejected: implausible published generation="
                        + published.generation);
                return false;
            }
            // Advance the process watermark even when this intent is rejected. Otherwise one
            // timed-out marker read could let another delayed intent claim the old watermark.
            ENERGY_SOURCE_GENERATIONS.adoptPublished(
                    published.generation, published.cancelled);
            if (published.cancelled
                    || published.pending
                    || published.applied
                    || published.generation != sourceGeneration
                    || published.mode != mode) {
                Log.i(TAG, "energy mode " + mode + " sourceGeneration=" + sourceGeneration
                        + " rejected behind published generation=" + published.generation
                        + " mode=" + published.mode
                        + " cancelled=" + published.cancelled
                        + " applied=" + published.applied);
                return false;
            }
            SourceGenerationGate.Claim sourceClaim = ENERGY_SOURCE_GENERATIONS.claim(
                    sourceGeneration, sourceNow);
            if (sourceClaim == null) {
                Log.i(TAG, "energy mode " + mode
                        + " ignored: invalid, implausible, duplicate or stale source generation "
                        + sourceGeneration);
                return false;
            }

            final EnergyModeRequest request =
                    energyModeArbiter.submit(mode, sourceGeneration);
            final EnergyModeQueue.Offer offer = energyModeQueue.offer(request);
            if (offer.replaced != null) {
                energyModeArbiter.complete(offer.replaced);
                Log.i(TAG, "energy_mode mode=" + offer.replaced.mode + " generation="
                        + offer.replaced.generation + " conflated by generation="
                        + request.generation);
            }
            if (!offer.startWorker) return true;

            try {
                executor.execute(this::drainEnergyModes);
                return true;
            } catch (Throwable t) {
                EnergyModeRequest stranded = energyModeQueue.abortWorker();
                if (stranded != null) energyModeArbiter.complete(stranded);
                ENERGY_SOURCE_GENERATIONS.rollback(sourceClaim);
                Log.w(TAG, "energy request enqueue failed: " + t.getMessage());
                if (t instanceof Error) throw (Error) t;
                return false;
            }
        }
    }

    private boolean cancelEnergyMode(long sourceGeneration) {
        if (!SourceGenerationGate.isPlausible(
                sourceGeneration, SystemClock.elapsedRealtimeNanos())) {
            return false;
        }
        ENERGY_SOURCE_GENERATIONS.adoptPublished(sourceGeneration, true);
        EnergyModeRequest pending = energyModeQueue.cancelThrough(sourceGeneration);
        if (pending != null) energyModeArbiter.complete(pending);
        ENERGY_HAL_LANE.cancelThrough(sourceGeneration);
        boolean persisted =
                com.overdrive.app.byd.VehicleActuatorBridge.persistEnergyCancellation(
                        getApplicationContext(), sourceGeneration);
        com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRead read =
                readPublishedEnergyStateWithRetry(getApplicationContext());
        if (read.status
                == com.overdrive.app.byd.VehicleActuatorBridge.EnergyReadStatus.VALID
                && read.request != null
                && read.request.generation == sourceGeneration
                && read.request.cancelled) {
            ENERGY_HAL_LANE.reconcileCancellation(
                    this, getApplicationContext(), read.request);
        }
        return persisted;
    }

    private boolean fenceEnergyMode(long sourceGeneration) {
        if (!SourceGenerationGate.isPlausible(
                sourceGeneration, SystemClock.elapsedRealtimeNanos())) {
            return false;
        }
        ENERGY_SOURCE_GENERATIONS.adoptPublished(sourceGeneration, false);
        long previousGeneration = sourceGeneration - 1L;
        EnergyModeRequest pending = energyModeQueue.cancelThrough(previousGeneration);
        if (pending != null) energyModeArbiter.complete(pending);
        ENERGY_HAL_LANE.cancelThrough(previousGeneration);
        return true;
    }

    /** Drain the single pending slot. Requests replaced before this point never get a runnable. */
    private void drainEnergyModes() {
        boolean completedNormally = false;
        try {
            EnergyModeRequest request;
            while ((request = energyModeQueue.takeNext()) != null) {
                try {
                    writeEnergyMode(getApplicationContext(), request);
                } finally {
                    energyModeArbiter.complete(request);
                }
            }
            completedNormally = true;
        } finally {
            if (!completedNormally) {
                EnergyModeRequest stranded = energyModeQueue.abortWorker();
                if (stranded != null) {
                    ENERGY_SOURCE_GENERATIONS.releaseClaim(stranded.sourceGeneration);
                    energyModeArbiter.complete(stranded);
                }
            }
            stopWhenIdle();
        }
    }

    /**
     * Resolve and write once through the process-wide HAL lane. Waiting here is bounded; a Binder
     * call that ignores interruption can strand only that one lane, never this service's worker or
     * an additional thread after every recreation.
     */
    private void writeEnergyMode(Context appCtx, EnergyModeRequest request) {
        if (!isEnergyRequestCurrent(request)) {
            Log.i(TAG, "energy_mode mode=" + request.mode + " generation=" + request.generation
                    + " skipped: superseded before HAL submission");
            ENERGY_SOURCE_GENERATIONS.releaseClaim(request.sourceGeneration);
            return;
        }
        final EnergyHalTask task;
        try {
            task =
                    ENERGY_HAL_LANE.submit(
                            this, appCtx, request.mode, request.sourceGeneration);
        } catch (Throwable unavailable) {
            ENERGY_SOURCE_GENERATIONS.releaseClaim(request.sourceGeneration);
            Log.w(TAG, "energy HAL submission failed: " + unavailable.getMessage());
            return;
        }
        EnergyWriteResult result = task.await(ENERGY_HAL_WRITE_TIMEOUT_MS);
        if (result == null) {
            Log.w(TAG, "setEnergyMode(" + request.mode + ") generation=" + request.generation
                    + " was not completed within " + ENERGY_HAL_WRITE_TIMEOUT_MS
                    + "ms or returned no result; requesting bounded HAL-lane recovery");
            ENERGY_HAL_LANE.recoverStalled(
                    task, this, appCtx);
            ENERGY_HAL_LANE.releaseClaimIfIdle(request.sourceGeneration);
        }
    }

    private static EnergyWriteResult writeEnergyModeDirect(EnergyHalTask task) {
        if (!isEnergyHalTaskCurrent(task)) return null;
        Context bydContext =
                BydDeviceHelper.withBydPermissionBypass(task.appContext);
        Object device = com.overdrive.app.byd.BydDeviceHelper.getDevice(
                ENERGY_DEVICE, bydContext);
        if (device == null) { Log.w(TAG, "energy device unavailable"); return null; }
        task.device = device;
        if (!isEnergyHalTaskCurrent(task)) return null;
        try {
            boolean activated = com.overdrive.app.byd.BydManagerChannel.enableDevice(
                    bydContext, device, "Energy");
            Log.i(TAG, "energy device activation=" + activated);
        } catch (Throwable t) {
            // The same activation step is advisory in collector init; the named setter may still
            // work on firmware whose manager API is absent.
            Log.w(TAG, "energy device activation failed: " + t.getMessage());
        }
        if (!isEnergyHalTaskCurrent(task)) return null;

        final boolean accepted;
        String setterName = "setEnergyMode";
        int setterValue = task.mode;
        boolean preferenceAxis = false;
        if (!isEnergyHalTaskCurrent(task)) {
            Log.i(TAG, "energy_mode mode=" + task.mode + " sourceGeneration="
                    + task.sourceGeneration + " skipped: superseded before setter");
            return null;
        }
        try {
            Method m = null;
            int preference =
                    com.overdrive.app.byd.VehicleActuatorBridge
                            .mandatoryElectricStateForEnergyMode(task.mode);
            if (preference > 0) {
                int selectedMode = readEnergyMode(
                        device, ENERGY_READ_TIMEOUT_MS, true);
                if (isUserWritableEnergyMode(selectedMode)) {
                    setterName = "setMandatoryElectricPreference";
                    setterValue = preference;
                    preferenceAxis = true;
                } else {
                    m = device.getClass().getMethod("setEnergyMode", int.class);
                }
            } else {
                m = device.getClass().getMethod("setEnergyMode", int.class);
            }
            task.preferenceAxis = preferenceAxis;
            if (!task.compensation) {
                if (!isEnergyHalTaskCurrent(task)) return null;
                int previousMode = readEnergyMode(
                        device, ENERGY_READ_TIMEOUT_MS, preferenceAxis);
                if (!isEnergyHalTaskCurrent(task)) return null;
                if (!isReadableEnergySourceMode(previousMode)) {
                    Log.w(TAG, "energy_mode mode=" + task.mode + " sourceGeneration="
                            + task.sourceGeneration
                            + " skipped: pre-write axis unavailable");
                    return null;
                }
                task.setPreviousMode(previousMode);
                if (previousMode == task.mode) {
                    return new EnergyWriteResult(device, true);
                }
                if (isSafeEnergyRollbackMode(previousMode)
                        && !com.overdrive.app.byd.VehicleActuatorBridge.beginEnergyActuation(
                        task.appContext,
                        task.sourceGeneration,
                        task.mode,
                        previousMode,
                        com.overdrive.app.byd.VehicleActuatorBridge.ENERGY_ACTUATOR_APP)) {
                    Log.w(TAG, "energy_mode mode=" + task.mode + " sourceGeneration="
                            + task.sourceGeneration
                            + " skipped: rollback metadata was not durably confirmed");
                    return null;
                }
                if (!isSafeEnergyRollbackMode(previousMode)) {
                    Log.i(TAG, "energy_mode mode=" + task.mode + " sourceGeneration="
                            + task.sourceGeneration
                            + " starting from STOP; rollback to STOP is intentionally disabled");
                }
            } else {
                int currentMode = readEnergyMode(
                        device, ENERGY_READ_TIMEOUT_MS, preferenceAxis);
                if (currentMode == task.mode && isEnergyHalTaskCurrent(task)) {
                    task.markCompensationPreconfirmed();
                    return new EnergyWriteResult(device, true);
                }
                int claim = task.compensationClaim();
                if (claim <= 0) {
                    claim = com.overdrive.app.byd.VehicleActuatorBridge
                            .claimEnergyRollbackAttempt(
                                    task.appContext,
                                    task.sourceGeneration,
                                    task.mode,
                                    com.overdrive.app.byd.VehicleActuatorBridge
                                    .ENERGY_ACTUATOR_APP);
                }
                task.setCompensationClaim(claim);
                if (claim <= 0) {
                    Log.w(TAG, "energy rollback sourceGeneration="
                            + task.sourceGeneration
                            + " was not invoked; shared compensation claim=" + claim);
                    return null;
                }
            }
            Object result = task.invokeSetter(m, device, setterValue);
            if (result == EnergyHalTask.INVOCATION_SKIPPED) {
                Log.i(TAG, "energy_mode mode=" + task.mode + " sourceGeneration="
                        + task.sourceGeneration
                        + " skipped: cancelled or superseded at setter invocation gate");
                return null;
            }
            accepted = isWriteAccepted(device, result);
            Log.i(TAG, setterName + "(" + setterValue + ") desiredMode=" + task.mode
                    + " sourceGeneration=" + task.sourceGeneration
                    + " returned " + result + " -> "
                    + (accepted ? "ACCEPTED" : "REFUSED"));
        } catch (NoSuchMethodException nsme) {
            Log.w(TAG, "compatible energy preference setter absent on this trim");
            return null;
        } catch (Throwable t) {
            Log.w(TAG, setterName + " failed: " + t.getMessage());
            return null;
        }
        return new EnergyWriteResult(device, accepted);
    }

    private static boolean isEnergyHalTaskCurrent(EnergyHalTask task) {
        if (task.isCancelled()) return false;
        com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRead publishedRead =
                readPublishedEnergyStateWithRetry(task.appContext);
        com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRequest published =
                publishedRead.request;
        if (publishedRead.status
                != com.overdrive.app.byd.VehicleActuatorBridge.EnergyReadStatus.VALID
                || published == null) {
            return false;
        }
        if (!SourceGenerationGate.isPlausible(
                published.generation, SystemClock.elapsedRealtimeNanos())) {
            return false;
        }
        if (task.compensation) {
            ENERGY_SOURCE_GENERATIONS.adoptPublished(
                    published.generation, published.cancelled);
            return published.cancelled
                    && published.generation == task.sourceGeneration
                    && published.rollbackPending
                    && published.rollbackMode == task.mode
                    && published.rollbackOwner
                    == com.overdrive.app.byd.VehicleActuatorBridge.ENERGY_ACTUATOR_APP
                    && ENERGY_SOURCE_GENERATIONS.isCancelled(task.sourceGeneration);
        }
        if (published.generation < task.sourceGeneration
                || (published.generation == task.sourceGeneration
                && !published.cancelled
                && published.mode != task.mode)) {
            return false;
        }
        ENERGY_SOURCE_GENERATIONS.adoptPublished(
                published.generation, published.cancelled);
        return !published.cancelled
                && !published.pending
                && published.generation == task.sourceGeneration
                && published.mode == task.mode
                && ENERGY_SOURCE_GENERATIONS.isCurrent(task.sourceGeneration);
    }

    /**
     * Marker availability is safety-critical for a HAL write. Retry one transient state-lane miss,
     * then fail closed rather than treating the process-local generation watermark as authority.
     */
    private static com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRead
    readPublishedEnergyStateWithRetry(Context context) {
        com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRead published =
                com.overdrive.app.byd.VehicleActuatorBridge.readPublishedEnergyState(context);
        if (published.status
                == com.overdrive.app.byd.VehicleActuatorBridge.EnergyReadStatus.UNREADABLE
                && !Thread.currentThread().isInterrupted()) {
            published =
                    com.overdrive.app.byd.VehicleActuatorBridge.readPublishedEnergyState(context);
        }
        return published;
    }

    private boolean isEnergyRequestCurrent(EnergyModeRequest request) {
        return energyModeArbiter.isCurrent(request)
                && ENERGY_SOURCE_GENERATIONS.isCurrent(request.sourceGeneration);
    }

    /**
     * Physically confirm a process-wide HAL task before releasing its foreground lease. This runs
     * for both real service deliveries and synthetic latest-state corrections.
     */
    private static boolean verifyEnergyHalTask(
            EnergyHalTask task, EnergyWriteResult result) {
        Object device = task.device;
        if (device == null) return false;
        final long deadline = SystemClock.elapsedRealtime() + ENERGY_VERIFY_TIMEOUT_MS;
        int seen = Integer.MIN_VALUE;
        while (true) {
            if (!isEnergyHalTaskCurrent(task)) return false;
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            seen = readEnergyMode(
                    device,
                    Math.min(ENERGY_READ_TIMEOUT_MS, remaining),
                    task.preferenceAxis);
            if (!isEnergyHalTaskCurrent(task)) return false;
            if (seen == task.mode) {
                Log.i(TAG, "setEnergyMode(" + task.mode + ") sourceGeneration="
                        + task.sourceGeneration + " attempt=" + (task.attempt + 1)
                        + " verify: axis reached target; accepted="
                        + (result != null && result.accepted));
                return true;
            }

            remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            try {
                Thread.sleep(Math.min(ENERGY_VERIFY_POLL_MS, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (!isEnergyHalTaskCurrent(task)) return false;
        Log.w(TAG, "setEnergyMode(" + task.mode + ") sourceGeneration="
                + task.sourceGeneration + " attempt=" + (task.attempt + 1)
                + " accepted=" + (result != null && result.accepted)
                + " but axis did not reach target within " + ENERGY_VERIFY_TIMEOUT_MS
                + "ms (after=" + describeEnergyMode(seen) + ")");
        return false;
    }

    private static int readEnergyMode(
            Object device, long timeoutMs, boolean preferenceAxis) {
        ThreadPoolExecutor executor = ENERGY_READ_EXECUTOR;
        if (executor == null || executor.isShutdown() || timeoutMs <= 0L) {
            return Integer.MIN_VALUE;
        }
        if (isProcessHalLaneStalled(executor, ENERGY_READ_STALLED)) {
            return Integer.MIN_VALUE;
        }
        final Future<Integer> read;
        try {
            read = executor.submit(
                    () -> readEnergyModeDirect(device, preferenceAxis));
        } catch (Throwable unavailable) {
            return Integer.MIN_VALUE;
        }
        try {
            int result = read.get(timeoutMs, TimeUnit.MILLISECONDS);
            ENERGY_READ_STALLED.set(false);
            return result;
        } catch (TimeoutException timeout) {
            read.cancel(true);
            executor.remove((Runnable) read);
            ENERGY_READ_STALLED.set(true);
            return Integer.MIN_VALUE;
        } catch (InterruptedException interrupted) {
            read.cancel(true);
            executor.remove((Runnable) read);
            Thread.currentThread().interrupt();
            return Integer.MIN_VALUE;
        } catch (Throwable failed) {
            read.cancel(true);
            executor.remove((Runnable) read);
            ENERGY_READ_STALLED.set(false);
            return Integer.MIN_VALUE;
        }
    }

    private static int readEnergyModeDirect(Object device, boolean preferenceAxis) {
        if (preferenceAxis) {
            return com.overdrive.app.byd.VehicleActuatorBridge
                    .energyModeForMandatoryElectricState(
                            com.overdrive.app.byd.VehicleActuatorBridge
                                    .readMandatoryElectricState(device));
        }
        try {
            Object result = device.getClass().getMethod("getEnergyMode").invoke(device);
            if (result instanceof Number) return ((Number) result).intValue();
        } catch (Throwable ignored) {
            // The final verification log distinguishes no answer from a real axis value.
        }
        return Integer.MIN_VALUE;
    }

    private static String describeEnergyMode(int mode) {
        return mode == Integer.MIN_VALUE ? "<no answer>" : Integer.toString(mode);
    }

    static final class EnergyModeRequest {
        final long generation;
        final int mode;
        final long sourceGeneration;

        EnergyModeRequest(long generation, int mode, long sourceGeneration) {
            this.generation = generation;
            this.mode = mode;
            this.sourceGeneration = sourceGeneration;
        }
    }

    private static final class EnergyWriteResult {
        final Object device;
        final boolean accepted;

        EnergyWriteResult(Object device, boolean accepted) {
            this.device = device;
            this.accepted = accepted;
        }
    }

    /**
     * Keeps the submitting service in the foreground until an actual HAL task and every synthetic
     * latest-state correction derived from it have settled. One lease is handed along the correction
     * chain and released exactly once.
     */
    private static final class EnergyHalLease {
        private VehicleActuatorService owner;
        final AtomicBoolean released = new AtomicBoolean();

        EnergyHalLease(VehicleActuatorService owner) {
            this.owner = owner;
            owner.activeEnergyHalChains.incrementAndGet();
        }

        synchronized void transferTo(VehicleActuatorService nextOwner) {
            if (nextOwner == null || nextOwner == owner || released.get()) return;
            nextOwner.activeEnergyHalChains.incrementAndGet();
            VehicleActuatorService previousOwner = owner;
            owner = nextOwner;
            releaseFrom(previousOwner);
        }

        synchronized void release() {
            if (!released.compareAndSet(false, true)) return;
            releaseFrom(owner);
        }

        synchronized void reconcilePersistedState() {
            if (!released.get()) owner.rearmPersistedEnergyReconciliation();
        }

        synchronized void notePersistedStateProgress() {
            if (!released.get()) owner.notePersistedEnergyReconciliationProgress();
        }

        private static void releaseFrom(VehicleActuatorService service) {
            int remaining = service.activeEnergyHalChains.decrementAndGet();
            if (remaining < 0) {
                service.activeEnergyHalChains.incrementAndGet();
                Log.e(TAG, "energy HAL lifecycle lease released more than once");
                return;
            }
            service.stopWhenIdle();
        }
    }

    private static final class EnergyHalTask {
        static final Object INVOCATION_SKIPPED = new Object();
        final Context appContext;
        final int mode;
        final long sourceGeneration;
        final EnergyHalLease lease;
        final int attempt;
        final boolean compensation;
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.locks.ReentrantLock invocationGate =
                new java.util.concurrent.locks.ReentrantLock();
        volatile Object device;
        volatile boolean preferenceAxis;
        private EnergyWriteResult result;
        private boolean completed;
        private volatile boolean cancelled;
        private volatile boolean actuationStarted;
        private volatile boolean compensationRequired;
        private volatile boolean abandoned;
        private volatile boolean persistedReconciliationRequired;
        private volatile int previousMode = Integer.MIN_VALUE;
        private volatile int compensationClaim = Integer.MIN_VALUE;
        private volatile boolean compensationPreconfirmed;

        EnergyHalTask(
                Context appContext,
                int mode,
                long sourceGeneration,
                EnergyHalLease lease,
                int attempt) {
            this(appContext, mode, sourceGeneration, lease, attempt, false);
        }

        EnergyHalTask(
                Context appContext,
                int mode,
                long sourceGeneration,
                EnergyHalLease lease,
                int attempt,
                boolean compensation) {
            this.appContext = appContext;
            this.mode = mode;
            this.sourceGeneration = sourceGeneration;
            this.lease = lease;
            this.attempt = attempt;
            this.compensation = compensation;
        }

        EnergyWriteResult await(long timeoutMs) {
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

        synchronized void complete(EnergyWriteResult value) {
            if (completed) return;
            completed = true;
            result = value;
            done.countDown();
        }

        void setPreviousMode(int mode) {
            previousMode = mode;
        }

        Object invokeSetter(Method method, Object target, int value) throws Exception {
            invocationGate.lock();
            try {
                if (cancelled || !isEnergyHalTaskCurrent(this)) {
                    return INVOCATION_SKIPPED;
                }
                actuationStarted = true;
                Object result = method != null
                        ? method.invoke(target, value)
                        : Integer.valueOf(
                                com.overdrive.app.byd.VehicleActuatorBridge
                                        .writeMandatoryElectricState(target, value));
                if (cancelled || !isEnergyHalTaskCurrent(this)) {
                    if (!compensation) compensationRequired = true;
                }
                return result;
            } finally {
                invocationGate.unlock();
            }
        }

        void cancel() {
            if (invocationGate.tryLock()) {
                try {
                    cancelled = true;
                    if (actuationStarted) compensationRequired = true;
                } finally {
                    invocationGate.unlock();
                }
                return;
            }
            cancelled = true;
            // Failure to acquire proves invokeSetter owns the gate. Conservatively compensate even
            // if cancellation landed in the few instructions before actuationStarted is published.
            compensationRequired = true;
        }

        boolean isCancelled() {
            return cancelled;
        }

        boolean needsCompensation() {
            return compensationRequired;
        }

        void setCompensationClaim(int claim) {
            compensationClaim = claim;
        }

        void inheritCompensationClaim(int claim) {
            if (claim > 0) compensationClaim = claim;
        }

        int compensationClaim() {
            return compensationClaim;
        }

        void markCompensationPreconfirmed() {
            compensationClaim = 0;
            compensationPreconfirmed = true;
        }

        boolean wasCompensationPreconfirmed() {
            return compensationPreconfirmed;
        }

        void requireCompensation() {
            if (!compensation && actuationStarted) compensationRequired = true;
        }

        boolean wasActuationStarted() {
            return actuationStarted;
        }

        void markAbandoned() {
            abandoned = true;
        }

        boolean abandoned() {
            return abandoned;
        }

        void requirePersistedReconciliation() {
            persistedReconciliationRequired = true;
        }

        boolean needsPersistedReconciliation() {
            return persistedReconciliationRequired;
        }

        int previousMode() {
            return previousMode;
        }

        void settle() {
            lease.release();
        }
    }

    /**
     * One process-lifetime HAL worker plus one latest-pending request. If an old Binder setter
     * ignores interruption and returns after a newer desired mode was published, the worker runs
     * that newest mode immediately afterward, restoring latest-request-wins ordering.
     */
    private static final class EnergyHalLane {
        private static final int MAX_WORKERS = 2;
        private EnergyHalTask activeOne;
        private EnergyHalTask activeTwo;
        private EnergyHalTask pending;
        private EnergyHalTask deferredCompensation;
        private int workerCount;

        synchronized void attachOwner(VehicleActuatorService owner) {
            if (owner == null) return;
            if (activeOne != null) activeOne.lease.transferTo(owner);
            if (activeTwo != null) activeTwo.lease.transferTo(owner);
            if (pending != null) pending.lease.transferTo(owner);
            if (deferredCompensation != null) {
                deferredCompensation.lease.transferTo(owner);
            }
        }

        synchronized EnergyHalTask submit(
                VehicleActuatorService owner,
                Context context,
                int mode,
                long sourceGeneration) {
            attachOwner(owner);
            Context appContext = context != null ? context.getApplicationContext() : null;
            if (appContext == null) appContext = context;
            EnergyHalTask task = new EnergyHalTask(
                    appContext, mode, sourceGeneration, new EnergyHalLease(owner), 0);
            replacePendingLocked(task);
            ensureWorkerLocked();
            return task;
        }

        synchronized void recoverStalled(
                EnergyHalTask timedOut,
                VehicleActuatorService owner,
                Context context) {
            if (timedOut == null || workerCount >= MAX_WORKERS
                    || !isActiveLocked(timedOut) && pending != timedOut) {
                return;
            }
            timedOut.markAbandoned();
            if (pending == null) {
                Context appContext =
                        context != null ? context.getApplicationContext() : null;
                if (appContext == null) appContext = context;
                pending = new EnergyHalTask(
                        appContext,
                        timedOut.mode,
                        timedOut.sourceGeneration,
                        new EnergyHalLease(owner),
                        timedOut.attempt + 1,
                        timedOut.compensation);
            }
            startWorkerLocked("ActuatorEnergyHalRecovery");
        }

        synchronized void releaseClaimIfIdle(long sourceGeneration) {
            if (!hasSourceLocked(sourceGeneration)) {
                ENERGY_SOURCE_GENERATIONS.releaseClaim(sourceGeneration);
            }
        }

        synchronized void cancelThrough(long sourceGeneration) {
            cancelActiveThroughLocked(activeOne, sourceGeneration);
            cancelActiveThroughLocked(activeTwo, sourceGeneration);
            if (pending != null && pending.sourceGeneration <= sourceGeneration
                    && !(pending.compensation
                    && pending.sourceGeneration == sourceGeneration)) {
                EnergyHalTask cancelled = pending;
                pending = null;
                boolean leaseOwnedByActive =
                        activeOwnsLeaseLocked(cancelled.lease);
                cancelled.cancel();
                cancelled.complete(null);
                if (!leaseOwnedByActive) cancelled.settle();
            }
            if (deferredCompensation != null
                    && deferredCompensation.sourceGeneration <= sourceGeneration
                    && !(deferredCompensation.compensation
                    && deferredCompensation.sourceGeneration == sourceGeneration)) {
                EnergyHalTask cancelled = deferredCompensation;
                deferredCompensation = null;
                cancelled.cancel();
                cancelled.complete(null);
                if (!activeOwnsLeaseLocked(cancelled.lease)
                        && (pending == null || pending.lease != cancelled.lease)) {
                    cancelled.settle();
                }
            }
        }

        synchronized boolean reconcileCancellation(
                VehicleActuatorService owner,
                Context context,
                com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRequest marker) {
            if (marker == null || !marker.cancelled || !marker.rollbackPending
                    || marker.rollbackMode < 1 || marker.rollbackMode > 5) {
                return false;
            }
            if (marker.rollbackOwner
                    != com.overdrive.app.byd.VehicleActuatorBridge.ENERGY_ACTUATOR_APP) {
                return true;
            }
            if (marker.compensationAttempts
                    >= com.overdrive.app.byd.VehicleActuatorBridge
                    .MAX_ENERGY_COMPENSATION_ATTEMPTS) {
                Log.w(TAG, "energy rollback generation=" + marker.generation
                        + " exhausted its shared compensation budget");
                return true;
            }
            cancelThrough(marker.generation);
            if (hasNewerLocked(marker.generation)
                    || hasCompensationLocked(
                            marker.generation, marker.rollbackMode)) {
                return true;
            }
            Context appContext = context != null ? context.getApplicationContext() : null;
            if (appContext == null) appContext = context;
            replacePendingLocked(new EnergyHalTask(
                    appContext,
                    marker.rollbackMode,
                    marker.generation,
                    new EnergyHalLease(owner),
                    0,
                    true));
            if (workerCount == 0) {
                ensureWorkerLocked();
            } else if (workerCount < MAX_WORKERS
                    && (activeOne != null || activeTwo != null)) {
                startWorkerLocked("ActuatorEnergyHalCompensation");
            }
            return hasCompensationLocked(marker.generation, marker.rollbackMode);
        }

        synchronized boolean reconcileDesired(
                VehicleActuatorService owner,
                Context context,
                com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRequest marker) {
            if (marker == null || marker.cancelled || marker.pending
                    || !isUserWritableEnergyMode(marker.mode)) {
                return false;
            }
            cancelThrough(marker.generation - 1L);
            if (hasDesiredLocked(marker.generation, marker.mode)) return true;
            Context appContext = context != null ? context.getApplicationContext() : null;
            if (appContext == null) appContext = context;
            replacePendingLocked(new EnergyHalTask(
                    appContext,
                    marker.mode,
                    marker.generation,
                    new EnergyHalLease(owner),
                    0));
            if (workerCount == 0) {
                ensureWorkerLocked();
            } else if (workerCount < MAX_WORKERS
                    && (activeOne != null || activeTwo != null)) {
                startWorkerLocked("ActuatorEnergyHalReconcile");
            }
            return hasDesiredLocked(marker.generation, marker.mode);
        }

        private void cancelActiveThroughLocked(
                EnergyHalTask task, long sourceGeneration) {
            if (task != null && task.sourceGeneration <= sourceGeneration
                    && !(task.compensation
                    && task.sourceGeneration == sourceGeneration)) {
                task.cancel();
            }
        }

        private void drain() {
            while (true) {
                final EnergyHalTask task;
                synchronized (this) {
                    task = pending;
                    pending = null;
                    if (task == null) {
                        workerCount--;
                        if (workerCount < 0) {
                            workerCount = 0;
                            Log.e(TAG, "energy HAL worker count underflow");
                        }
                        return;
                    }
                    if (activeOne == null) {
                        activeOne = task;
                    } else if (activeTwo == null) {
                        activeTwo = task;
                    } else {
                        replacePendingLocked(task);
                        workerCount--;
                        return;
                    }
                }

                EnergyWriteResult result = null;
                try {
                    result = writeEnergyModeDirect(task);
                } catch (Throwable failed) {
                    Log.e(TAG, "energy HAL task failed outside setter guard: "
                            + failed.getMessage());
                }
                task.complete(result);

                boolean applied = false;
                boolean durableCompletion = false;
                boolean deferredForOutlivingWrite = false;
                boolean retained = false;
                try {
                    if (!task.needsCompensation()) {
                        applied = verifyEnergyHalTask(task, result);
                        if (applied) {
                            if (task.compensation
                                    && deferForOutlivingWrite(task)) {
                                deferredForOutlivingWrite = true;
                                retained = true;
                            } else {
                                // The first readback may have preceded a late setter's return.
                                // Once the lane proves no such writer remains, sample the axis again
                                // before clearing rollbackPending.
                                if (task.compensation) {
                                    applied = verifyEnergyHalTask(task, result);
                                }
                            }
                            if (applied && !deferredForOutlivingWrite) {
                                durableCompletion = task.compensation
                                        ? com.overdrive.app.byd.VehicleActuatorBridge
                                        .completeEnergyRollback(
                                                task.appContext,
                                                task.sourceGeneration,
                                                task.mode)
                                        : com.overdrive.app.byd.VehicleActuatorBridge
                                        .completeEnergyActuation(
                                                task.appContext,
                                                task.sourceGeneration,
                                                task.mode);
                            }
                            if (!durableCompletion && !isEnergyHalTaskCurrent(task)) {
                                task.requireCompensation();
                            }
                        }
                    }
                    if (task.compensation) {
                        if (!deferredForOutlivingWrite) {
                            if (task.compensationClaim() < 0
                                    || task.wasCompensationPreconfirmed()
                                    && (!applied || !durableCompletion)) {
                                task.requirePersistedReconciliation();
                            }
                            if ((!applied || !durableCompletion)
                                    && task.compensationClaim() > 0) {
                                retained = retainPublishedCorrection(task);
                            }
                        }
                    } else if (task.needsCompensation()) {
                        retained = retainPublishedCorrection(task);
                    } else if (!applied || !durableCompletion) {
                        retained = retainSameGenerationRetry(task);
                        if (!retained) retained = retainPublishedCorrection(task);
                    }
                    if (!retained && task.abandoned()) {
                        retained = retainPublishedCorrection(task);
                    }
                    if (durableCompletion) {
                        task.lease.notePersistedStateProgress();
                    }
                } catch (Throwable failed) {
                    Log.e(TAG, "energy HAL correction retention failed: "
                            + failed.getMessage());
                }

                synchronized (this) {
                    if (retained && !retainsLeaseLocked(task.lease)) {
                        retained = false;
                    }
                    // Atomic final handoff with cancelThrough/reconcileCancellation. If
                    // cancellation acquired this monitor first, task.cancel() published the flag
                    // and the durable tombstone supplies the rollback. If this block wins first,
                    // the durable actuation metadata lets the later cancellation enqueue it.
                    if (!retained && !task.compensation
                            && task.needsCompensation()
                            && ENERGY_SOURCE_GENERATIONS.isCancelled(
                                    task.sourceGeneration)) {
                        int rollbackMode = task.previousMode();
                          if (rollbackMode >= 1 && rollbackMode <= 5
                                  && rollbackMode != task.mode
                                  && !hasNewerLocked(task.sourceGeneration)
                                  && !hasCompensationLocked(
                                          task.sourceGeneration, rollbackMode)) {
                              replacePendingLocked(new EnergyHalTask(
                                      task.appContext,
                                      rollbackMode,
                                    task.sourceGeneration,
                                    task.lease,
                                    0,
                                    true));
                            retained = true;
                        }
                    }
                    clearActiveLocked(task);
                    activateDeferredCompensationIfReadyLocked();
                    if (!retained && !task.compensation
                            && (!applied || !durableCompletion)
                            && !hasSourceLocked(task.sourceGeneration)) {
                        ENERGY_SOURCE_GENERATIONS.releaseClaim(
                                task.sourceGeneration);
                    }
                }
                if (!retained) {
                    if (task.needsPersistedReconciliation()) {
                        task.lease.reconcilePersistedState();
                    }
                    task.settle();
                }
            }
        }

        private boolean retainSameGenerationRetry(EnergyHalTask completed) {
            if (completed.attempt >= 1 || !isEnergyHalTaskCurrent(completed)) return false;
            synchronized (this) {
                if (!isGenerationCurrentInMemory(completed)
                        || pending != null
                        && pending.sourceGeneration >= completed.sourceGeneration) {
                    return false;
                }
                replacePendingLocked(new EnergyHalTask(
                        completed.appContext,
                        completed.mode,
                        completed.sourceGeneration,
                        completed.lease,
                        completed.attempt + 1,
                        completed.compensation));
                Log.i(TAG, "setEnergyMode(" + completed.mode + ") sourceGeneration="
                        + completed.sourceGeneration
                        + " retaining one physical-confirmation retry");
                return true;
            }
        }

        private boolean retainPublishedCorrection(EnergyHalTask completed) {
            com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRead read =
                    readPublishedEnergyStateWithRetry(completed.appContext);
            com.overdrive.app.byd.VehicleActuatorBridge.PublishedEnergyRequest marker =
                    read.request;
            if (read.status
                    != com.overdrive.app.byd.VehicleActuatorBridge.EnergyReadStatus.VALID
                    || marker == null
                    || !SourceGenerationGate.isPlausible(
                            marker.generation,
                            SystemClock.elapsedRealtimeNanos())) {
                completed.requirePersistedReconciliation();
                return false;
            }
            ENERGY_SOURCE_GENERATIONS.adoptPublished(
                    marker.generation, marker.cancelled);
            synchronized (this) {
                if (marker.cancelled) {
                    if (!marker.rollbackPending
                            || marker.rollbackMode < 1
                            || marker.rollbackMode > 5
                            || marker.rollbackOwner
                            != com.overdrive.app.byd.VehicleActuatorBridge
                            .ENERGY_ACTUATOR_APP
                              || marker.compensationAttempts
                              >= com.overdrive.app.byd.VehicleActuatorBridge
                              .MAX_ENERGY_COMPENSATION_ATTEMPTS
                              || marker.generation < completed.sourceGeneration
                              || hasOtherCompensationLocked(
                                      completed,
                                      marker.generation,
                                      marker.rollbackMode)) {
                          return false;
                      }
                    replacePendingLocked(new EnergyHalTask(
                            completed.appContext,
                            marker.rollbackMode,
                            marker.generation,
                            completed.lease,
                            0,
                            true));
                    return true;
                }
                if (marker.pending
                        || marker.generation < completed.sourceGeneration
                        || marker.generation == completed.sourceGeneration
                        && marker.mode == completed.mode
                        && !completed.needsCompensation()
                        || pending != null
                        && pending.sourceGeneration >= marker.generation) {
                    return false;
                }
                replacePendingLocked(new EnergyHalTask(
                        completed.appContext,
                        marker.mode,
                        marker.generation,
                        completed.lease,
                        0));
                Log.i(TAG, "energy HAL completion sourceGeneration="
                        + completed.sourceGeneration
                        + " retaining authoritative correction generation="
                        + marker.generation + " mode=" + marker.mode);
                return true;
            }
        }

        /**
         * Keep the current rollback claim alive until every canceled setter that may still return
         * has left the HAL. Completing the rollback marker before that point would let the late
         * write become final while also making a subsequent compensation claim invalid.
         */
        private synchronized boolean deferForOutlivingWrite(EnergyHalTask completed) {
            if (!hasOutlivingWriteLocked(completed)) {
                return false;
            }
            if (deferredCompensation != null) {
                return isCompensation(
                        deferredCompensation,
                        completed.sourceGeneration,
                        completed.mode);
            }
            EnergyHalTask deferred = new EnergyHalTask(
                    completed.appContext,
                    completed.mode,
                    completed.sourceGeneration,
                    completed.lease,
                    completed.attempt + 1,
                    true);
            deferred.inheritCompensationClaim(completed.compensationClaim());
            deferredCompensation = deferred;
            Log.i(TAG, "energy rollback generation=" + completed.sourceGeneration
                    + " deferring durable completion until outliving HAL write exits");
            return true;
        }

        private void activateDeferredCompensationIfReadyLocked() {
            EnergyHalTask deferred = deferredCompensation;
            if (deferred == null || hasOutlivingWriteLocked(deferred)) return;
            deferredCompensation = null;
            if (pending != null
                    && pending.sourceGeneration >= deferred.sourceGeneration) {
                deferred.cancel();
                deferred.complete(null);
                if (!activeOwnsLeaseLocked(deferred.lease)
                        && pending.lease != deferred.lease) {
                    deferred.settle();
                }
                return;
            }
            replacePendingLocked(deferred);
            Log.i(TAG, "energy rollback generation=" + deferred.sourceGeneration
                    + " resuming deferred convergence after outliving HAL write");
        }

        private boolean hasOutlivingWriteLocked(EnergyHalTask compensation) {
            return mayOutliveCompensation(activeOne, compensation)
                    || mayOutliveCompensation(activeTwo, compensation);
        }

        private static boolean mayOutliveCompensation(
                EnergyHalTask candidate, EnergyHalTask compensation) {
            return candidate != null
                    && candidate != compensation
                    && !candidate.compensation
                    && candidate.sourceGeneration <= compensation.sourceGeneration
                    && (candidate.wasActuationStarted()
                    || candidate.needsCompensation());
        }

        private boolean isGenerationCurrentInMemory(EnergyHalTask task) {
            return task.compensation
                    ? ENERGY_SOURCE_GENERATIONS.isCancelled(task.sourceGeneration)
                    : ENERGY_SOURCE_GENERATIONS.isCurrent(task.sourceGeneration);
        }

        private void replacePendingLocked(EnergyHalTask replacement) {
            if (pending != null && pending != replacement) {
                EnergyHalTask replaced = pending;
                pending = null;
                replaced.cancel();
                replaced.complete(null);
                if (!activeOwnsLeaseLocked(replaced.lease)
                        && replaced.lease != replacement.lease) {
                    replaced.settle();
                }
            }
            pending = replacement;
        }

        private boolean hasNewerLocked(long generation) {
            return activeOne != null && activeOne.sourceGeneration > generation
                    || activeTwo != null && activeTwo.sourceGeneration > generation
                    || pending != null && pending.sourceGeneration > generation;
        }

        private boolean hasCompensationLocked(long generation, int mode) {
            return isCompensation(activeOne, generation, mode)
                    || isCompensation(activeTwo, generation, mode)
                    || isCompensation(pending, generation, mode)
                    || isCompensation(deferredCompensation, generation, mode);
        }

        private boolean hasDesiredLocked(long generation, int mode) {
            return isDesired(activeOne, generation, mode)
                    || isDesired(activeTwo, generation, mode)
                    || isDesired(pending, generation, mode);
        }

          private boolean hasOtherCompensationLocked(
                  EnergyHalTask excluded, long generation, int mode) {
              return activeOne != excluded
                      && isCompensation(activeOne, generation, mode)
                      || activeTwo != excluded
                      && isCompensation(activeTwo, generation, mode)
                      || pending != excluded
                      && isCompensation(pending, generation, mode)
                      || deferredCompensation != excluded
                      && isCompensation(deferredCompensation, generation, mode);
          }

        private static boolean isCompensation(
                EnergyHalTask task, long generation, int mode) {
            return task != null && task.compensation
                    && task.sourceGeneration == generation
                    && task.mode == mode;
        }

        private static boolean isDesired(
                EnergyHalTask task, long generation, int mode) {
            return task != null && !task.compensation
                    && task.sourceGeneration == generation
                    && task.mode == mode;
        }

        private boolean hasSourceLocked(long generation) {
            return activeOne != null && activeOne.sourceGeneration == generation
                    || activeTwo != null && activeTwo.sourceGeneration == generation
                    || pending != null && pending.sourceGeneration == generation
                    || deferredCompensation != null
                    && deferredCompensation.sourceGeneration == generation;
        }

        private boolean activeOwnsLeaseLocked(EnergyHalLease lease) {
            return activeOne != null && activeOne.lease == lease
                    || activeTwo != null && activeTwo.lease == lease;
        }

        private boolean retainsLeaseLocked(EnergyHalLease lease) {
            return pending != null && pending.lease == lease
                    || deferredCompensation != null
                    && deferredCompensation.lease == lease;
        }

        private boolean isActiveLocked(EnergyHalTask task) {
            return activeOne == task || activeTwo == task;
        }

        private void clearActiveLocked(EnergyHalTask task) {
            if (activeOne == task) activeOne = null;
            if (activeTwo == task) activeTwo = null;
        }

        private void ensureWorkerLocked() {
            if (workerCount == 0) startWorkerLocked("ActuatorEnergyHal");
        }

        private void startWorkerLocked(String name) {
            if (workerCount >= MAX_WORKERS) return;
            workerCount++;
            try {
                Thread worker = daemonThread(this::drain, name);
                worker.start();
            } catch (Throwable unavailable) {
                workerCount--;
                EnergyHalTask stranded = pending;
                pending = null;
                if (stranded != null) {
                    stranded.complete(null);
                    stranded.settle();
                }
                Log.e(TAG, "energy HAL worker could not start: "
                        + unavailable.getMessage());
            }
        }
    }

    /** Pure request arbiter kept independent of Android so ordering can be unit-tested. */
    static final class EnergyModeArbiter {
        private final AtomicLong latestGeneration = new AtomicLong();
        private final AtomicInteger pending = new AtomicInteger();
        private final Object changeSignal = new Object();

        EnergyModeRequest submit(int mode) {
            return submit(mode, -1L);
        }

        EnergyModeRequest submit(int mode, long sourceGeneration) {
            EnergyModeRequest request =
                    new EnergyModeRequest(
                            latestGeneration.incrementAndGet(), mode, sourceGeneration);
            pending.incrementAndGet();
            synchronized (changeSignal) {
                changeSignal.notifyAll();
            }
            return request;
        }

        boolean isCurrent(EnergyModeRequest request) {
            return request != null && latestGeneration.get() == request.generation;
        }

        int complete(EnergyModeRequest request) {
            int remaining = pending.decrementAndGet();
            if (remaining < 0) {
                pending.incrementAndGet();
                throw new IllegalStateException(
                        "energy request completed more than once: " + request.generation);
            }
            return remaining;
        }

        int pendingCount() {
            return pending.get();
        }

        void cancelAll() {
            latestGeneration.incrementAndGet();
            synchronized (changeSignal) {
                changeSignal.notifyAll();
            }
        }

        boolean awaitWhileCurrent(EnergyModeRequest request, long timeoutMs)
                throws InterruptedException {
            synchronized (changeSignal) {
                if (!isCurrent(request)) return false;
                changeSignal.wait(timeoutMs);
                return isCurrent(request);
            }
        }
    }

    /** Process-wide defense against delayed bridge launches, including service recreation. */
    static final class SourceGenerationGate {
        private static final long MAX_FUTURE_NANOS = TimeUnit.SECONDS.toNanos(30L);
        private static final long MAX_AGE_NANOS = TimeUnit.SECONDS.toNanos(60L);

        static final class Claim {
            final long generation;
            final long previous;
            final boolean previousClaimed;
            final boolean previousCancelled;

            Claim(
                    long generation,
                    long previous,
                    boolean previousClaimed,
                    boolean previousCancelled) {
                this.generation = generation;
                this.previous = previous;
                this.previousClaimed = previousClaimed;
                this.previousCancelled = previousCancelled;
            }
        }

        private long latest;
        /**
         * False when {@link #adoptPublished(long)} reserved a generation for a synthetic correction.
         * The real ActivityManager delivery may claim that same generation once, providing one
         * independent retry if the synthetic lookup/write failed.
         */
        private boolean latestClaimed;
        /** True when the latest boot-scoped generation is a cancellation tombstone. */
        private boolean latestCancelled;

        synchronized Claim claim(long generation, long nowNanos) {
            if (!isPlausible(generation, nowNanos)
                    || generation < latest
                    || (generation == latest && latestCancelled)
                    || (generation == latest && latestClaimed)) {
                return null;
            }
            Claim claim =
                    new Claim(
                            generation, latest, latestClaimed, latestCancelled);
            latest = generation;
            latestClaimed = true;
            latestCancelled = false;
            return claim;
        }

        synchronized boolean isCurrent(long generation) {
            return generation > 0L && latest == generation && !latestCancelled;
        }

        synchronized boolean isCancelled(long generation) {
            return generation > 0L && latest == generation && latestCancelled;
        }

        synchronized boolean rollback(Claim claim) {
            if (claim == null || latest != claim.generation
                    || !latestClaimed || latestCancelled) {
                return false;
            }
            latest = claim.previous;
            latestClaimed = claim.previousClaimed;
            latestCancelled = claim.previousCancelled;
            return true;
        }

        synchronized boolean releaseClaim(long generation) {
            if (generation <= 0L || latest != generation
                    || latestCancelled || !latestClaimed) {
                return false;
            }
            latestClaimed = false;
            return true;
        }

        synchronized boolean adoptPublished(long generation) {
            return adoptPublished(generation, false);
        }

        synchronized boolean adoptPublished(long generation, boolean cancelled) {
            if (generation <= 0L || generation < latest) return false;
            if (generation == latest) {
                if (!cancelled || latestCancelled) return false;
                latestCancelled = true;
                latestClaimed = true;
                return true;
            }
            latest = generation;
            latestClaimed = cancelled;
            latestCancelled = cancelled;
            return true;
        }

        static boolean isPlausible(long generation, long nowNanos) {
            if (generation <= 0L || nowNanos <= 0L) return false;
            long oldest = nowNanos > MAX_AGE_NANOS
                    ? nowNanos - MAX_AGE_NANOS : 1L;
            long newest = nowNanos > Long.MAX_VALUE - MAX_FUTURE_NANOS
                    ? Long.MAX_VALUE : nowNanos + MAX_FUTURE_NANOS;
            return generation >= oldest && generation <= newest;
        }

        static boolean isPlausibleForMutation(long generation, long nowNanos) {
            if (generation <= 0L || nowNanos <= 0L) return false;
            long newest = nowNanos > Long.MAX_VALUE - MAX_FUTURE_NANOS
                    ? Long.MAX_VALUE : nowNanos + MAX_FUTURE_NANOS;
            return generation <= newest;
        }
    }

    static boolean isUserWritableEnergyMode(int mode) {
        return mode == 1 || mode == 3;
    }

    static boolean isReadableEnergySourceMode(int mode) {
        return mode >= 0 && mode <= 5;
    }

    static boolean isSafeEnergyRollbackMode(int mode) {
        return mode >= 1 && mode <= 5;
    }

    /** One pending request plus one drain owner; replacements never allocate executor work. */
    static final class EnergyModeQueue {
        static final class Offer {
            final EnergyModeRequest replaced;
            final boolean startWorker;

            Offer(EnergyModeRequest replaced, boolean startWorker) {
                this.replaced = replaced;
                this.startWorker = startWorker;
            }
        }

        private EnergyModeRequest pending;
        private boolean workerScheduled;

        synchronized Offer offer(EnergyModeRequest request) {
            EnergyModeRequest replaced = pending;
            pending = request;
            boolean startWorker = !workerScheduled;
            workerScheduled = true;
            return new Offer(replaced, startWorker);
        }

        synchronized EnergyModeRequest takeNext() {
            EnergyModeRequest next = pending;
            pending = null;
            if (next == null) workerScheduled = false;
            return next;
        }

        synchronized EnergyModeRequest abortWorker() {
            EnergyModeRequest stranded = pending;
            pending = null;
            workerScheduled = false;
            return stranded;
        }

        synchronized EnergyModeRequest cancelThrough(long sourceGeneration) {
            if (pending == null || pending.sourceGeneration > sourceGeneration) return null;
            EnergyModeRequest cancelled = pending;
            pending = null;
            return cancelled;
        }
    }

    /**
     * Whether a named-setter result means the HAL accepted the write. Mirrors the daemon-side
     * judge: the device's {@code <FAMILY>_COMMAND_SUCCESS} constant when resolvable, else any
     * non-negative int; {@code Boolean} maps directly; void/null means "returned without throwing".
     */
    private static boolean isWriteAccepted(Object device, Object result) {
        if (result instanceof Boolean) return (Boolean) result;
        if (!(result instanceof Integer)) return true;
        int code = (Integer) result;
        String simple = device.getClass().getSimpleName();
        // Locale.ROOT: a Turkish default locale uppercases "Setting" to "SETTİNG" (dotted İ), which
        // misses the field lookup and silently degrades to the fallback convention.
        String family = simple.replaceFirst("^BYDAuto", "").replaceFirst("Device$", "")
                .toUpperCase(java.util.Locale.ROOT);
        try {
            Object v = device.getClass().getField(family + "_COMMAND_SUCCESS").get(null);
            if (v instanceof Integer) return code == (Integer) v;
        } catch (Throwable ignored) {
            // Constant not exposed on this build — fall through to the non-inverting convention.
        }
        return code >= 0;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        synchronized (lifecycleLock) {
            destroyed = true;
        }
        energyModeArbiter.cancelAll();
        EnergyModeRequest canceled = energyModeQueue.abortWorker();
        if (canceled != null) {
            ENERGY_SOURCE_GENERATIONS.releaseClaim(canceled.sourceGeneration);
            energyModeArbiter.complete(canceled);
        }
        shutdownInstanceExecutors();
        super.onDestroy();
    }

    private void shutdownInstanceExecutors() {
        ExecutorService actuatorExecutor = guardedActuatorExecutor;
        if (actuatorExecutor != null) actuatorExecutor.shutdownNow();
        ExecutorService executor = energyExecutor;
        if (executor != null) executor.shutdownNow();
        synchronized (energyReconcileLock) {
            energyReconcileTicket++;
            if (energyReconcileFuture != null) {
                energyReconcileFuture.cancel(false);
                energyReconcileFuture = null;
            }
            energyReconciliationScheduled = false;
        }
        ScheduledThreadPoolExecutor reconcileExecutor = energyReconcileExecutor;
        if (reconcileExecutor != null) reconcileExecutor.shutdownNow();
    }

    private boolean startForegroundCompat() {
        Notification n = buildNotification();
        // Tier exactly as MediaPlaybackService/MessageOverlayService: SPECIAL_USE is API-34;
        // pass DATA_SYNC on Q..33 (this API-29 head unit), bare below. try/catch → bare
        // fallback so a rejected type can never leave us non-foreground.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed: " + t.getMessage());
            try {
                startForeground(NOTIFICATION_ID, n);
                return true;
            } catch (Throwable fallback) {
                Log.e(TAG, "startForeground fallback failed; stopping service: "
                        + fallback.getMessage());
                return false;
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Vehicle Control", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Vehicle control")
                .setContentText("OverDrive")
                .setSmallIcon(R.drawable.ic_play_circle)
                .setOngoing(false)
                .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
                .build();
    }
}
