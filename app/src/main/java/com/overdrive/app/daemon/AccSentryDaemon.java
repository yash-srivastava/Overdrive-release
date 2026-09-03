package com.overdrive.app.daemon;

import android.content.Context;
import android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener;
import android.hardware.bydauto.power.AbsBYDAutoPowerListener;
import android.hardware.bydauto.power.BYDAutoPowerDevice;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.overdrive.app.daemon.proxy.Safe;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatteryPowerData;
import com.overdrive.app.monitor.BatteryVoltageData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.VehicleDataListener;
import com.overdrive.app.monitor.VehicleDataMonitor;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.Socket;

/**
 * ACC Sentry Daemon - runs as shell user (UID 2000) via ADB shell.
 *
 * RESPONSIBILITIES:
 * 1. ACC state monitoring via BYD bodywork service
 * 2. Screen control (input keyevent) - MUST run as UID 2000
 * 3. Surveillance enable/disable via IPC to CameraDaemon
 * 4. MCU wake-up to keep hardware powered during sentry mode
 * 5. Backlight control and blocker activity management
 *
 * NOTE: Whitelisting and ACC Lock acquisition is handled by SentryDaemon (UID 1000).
 * This daemon focuses on ACC state detection and sentry mode management.
 */
public class AccSentryDaemon {

    private static final String TAG = "AccSentryDaemon";
    private static DaemonLogger logger;

    // ==================== ENCRYPTED CONSTANTS (SOTA Java obfuscation) ====================
    // Decrypted at runtime via Safe.s() - AES-256-CBC with stack-based key reconstruction
    /** com.overdrive.app */
    private static String APP_PACKAGE_NAME() { return Safe.s("3Is1Ze/xWL6dkFvd9bF+deUGK/HqnInkSi6jinpc6s8="); }
    /** accmodemanager */
    private static String SERVICE_ACCMODEMANAGER() { return Safe.s("tr877WU3+MV4zFtCjanWUw=="); }
    /** byd_datacached */
    private static String SERVICE_BYD_DATACACHE() { return Safe.s("JQiIxMJxYlF8spk2fIi8Sg=="); }
    /** bg_datacache */
    private static String SERVICE_BG_DATACACHE() { return Safe.s("m84QJmAGTQpH+XP36MaDpA=="); }
    /** svc wifi enable */
    private static String CMD_WIFI_ENABLE() { return Safe.s("GzzLDvODRsKARkPOXEZeIA=="); }
    /** svc data enable */
    private static String CMD_DATA_ENABLE() { return Safe.s("IyVVEc3FpTCbWAn/AlxUnA=="); }
    /** settings put global mobile_data_always_on 1 */
    private static String CMD_DATA_ALWAYS_ON() { return Safe.s("kSl507BgPZXbv0JUusGzZofsus1EHyUHZji5UFGB7WLLwoz58e3wRdD6/xbXC307"); }
    /** settings get global mobile_data */
    private static String CMD_DATA_GET() { return Safe.s("4/qqmGNE2vhiGGggG70n0sRfHtz6gZempQZl+6FiiZk="); }
    /** /data/local/tmp */
    private static String PATH_DATA_LOCAL_TMP() { return Safe.s("vuaMjrmBGBFh07qqnUuL8w=="); }
    /** /data/local/tmp/telegram_config.properties */
    private static String PATH_TELEGRAM_CONFIG() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxzwQSn0P1N0jxHygc8N+4Ft+9mlR8XQ+WvEw0ktanrtNx"); }

    // Power levels from BYDAutoBodyworkDevice
    private static final int POWER_LEVEL_OFF = 0;
    private static final int POWER_LEVEL_ACC = 1;
    private static final int POWER_LEVEL_ON = 2;
    private static final int POWER_LEVEL_OK = 3;

    // MCU Status codes
    private static final int MCU_STATUS_SLEEPING = 0;
    private static final int MCU_STATUS_ACTIVE = 1;
    private static final int MCU_STATUS_ACC_OFF = 2;
    private static final int MCU_STATUS_DEEP_SLEEP = 3;

    private static volatile boolean running = true;
    private static volatile boolean inSentryMode = false;
    private static final Object shutdownLock = new Object();
    private static boolean shutdownComplete;
    private static final Object sentryTransitionLock = new Object();
    private static final java.util.concurrent.atomic.AtomicLong sentryTransitionGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile SentryTransitionState latestSentryTransition =
            new SentryTransitionState(0L, false, false);
    private static long panelDeterrentResetGeneration = -1L;
    private static final Object accObservationLock = new Object();
    private static final java.util.concurrent.atomic.AtomicLong accCallbackSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private static final Object powerListenerRegistrationLock = new Object();
    private static long powerListenerLifecycleGeneration;
    private static long powerListenerAttemptGeneration;
    private static long activePowerListenerAttempt = -1L;
    private static boolean powerListenerRegistered;
    private static Thread powerListenerRegistrationThread;
    private static final Object bodyworkRegistrationLock = new Object();
    private static long bodyworkLifecycleGeneration;
    private static long bodyworkAttemptGeneration;
    private static long activeBodyworkAttempt = -1L;
    private static Thread bodyworkRegistrationThread;
    private static final java.util.concurrent.atomic.AtomicBoolean
            controlledRecoveryRequested =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static int lastPowerLevel = -1;
    private static int lastMcuStatus = -1;
    // Set to true once a bodywork listener has been successfully registered
    // with the BYD HAL. The slow-retry thread spins until this flips true,
    // and the periodic ACC heartbeat is gated on it as well — there's no
    // point publishing state if the daemon never received an event source.
    private static volatile boolean bodyworkRegistered = false;
    // Heartbeat thread for periodic ACC state republish (covers the wedge
    // where CameraDaemon restarts mid-drive and misses our edge-only IPC).
    private static Thread accHeartbeatThread = null;
    // Last accOff value the heartbeat actually published. -1 = nothing
    // published yet; 0 = ACC ON; 1 = ACC OFF. Heartbeat short-circuits
    // when its tick would re-publish the same state, because each IPC
    // re-runs CameraDaemon.onAccStateChanged side-effects (cleanupDoorLockGate,
    // surveillanceEnabled reset, DB write, OEM recalc) that aren't fully
    // idempotent. Edge handlers in onPowerLevelChanged still notify
    // unconditionally — they're the authoritative state delta. See
    // prior-audit "Heartbeat triggers full CameraDaemon.onAccStateChanged
    // side-effects every 30s".
    private static volatile int lastHeartbeatPublishedAccOff = -1;
    private static final Object heartbeatPublishLock = new Object();
    // Counter of consecutive heartbeat ticks that hit the dedup
    // short-circuit (state unchanged since last publish). When this
    // reaches HEARTBEAT_FORCE_REPUBLISH_TICKS we publish anyway, so a
    // CameraDaemon process restart mid-drive (which resets its in-process
    // lastDispatchedAccIsOff cache) resyncs within ~5 min instead of
    // waiting indefinitely for the next bodywork edge. CameraDaemon's
    // own onAccStateChanged dedup (CameraDaemon.java:2802-2809) drops
    // the no-op IPC when the consumer is already in sync, so the
    // periodic republish is cheap when not needed. See prior-audit
    // "Heartbeat refuses to republish ACC ON after CameraDaemon
    // process restart".
    private static volatile int heartbeatDedupRunLength = 0;
    // Dropped from 10 (~5min) to 2 (~1min) per prior-audit "Heartbeat
    // dedup creates 5-minute pano wedge on CameraDaemon mid-drive
    // restart". CameraDaemon's onAccStateChanged dedup drops the no-op
    // IPC when state already matches, so a 1min republish is cheap in
    // steady state but caps the post-restart resync window at 60s
    // instead of 5min — pano stays armed for at most one extra minute
    // of staleness before the heartbeat force-republishes.
    private static final int HEARTBEAT_FORCE_REPUBLISH_TICKS = 2;
    // Thread for the 10-second loop
    private static volatile Thread systemKeepAliveThread = null;
    private static volatile long systemKeepAliveGeneration = -1L;
    private static final Object systemKeepAliveLock = new Object();
    // Interval from  (C0004a0)
    private static final long SYSTEM_KEEPALIVE_INTERVAL_MS = 10000;

    // Surveillance IPC
    private static final int SURVEILLANCE_IPC_PORT = 19877;
    private static volatile boolean surveillanceEnabled = false;

    // MCU wake timestamp (for voltage-triggered wake cooldown)
    private static volatile long lastMcuWakeTime = 0;

    // ==================== ACTIVE VOLTAGE RECOVERY (REPLACED) ====================
    //
    // Replaced by com.overdrive.app.power.BatteryVoltageMonitorV2 +
    // com.overdrive.app.power.McuPowerHal. The 45 s "wake the MCU on every
    // pulse" model was net-negative on a parked car — no alternator load,
    // and each pulse drew the 12 V it was meant to preserve. The new model
    // gates MCU wake/sleep on a 12.0 V / 12.5 V hysteresis with a 60 s
    // re-arm and a 15 min sleep-defer window.
    //
    // Kept as commented references only; no live code paths use these.
    // private static Thread mcuChargingThread = null;
    // private static final long MCU_CHARGE_PULSE_INTERVAL_MS = 45000;

    // Context for BYD device access
    private static Context appContext;
    private static final String BOOT_IDENTITY = readBootIdentity();
    private static final String PROCESS_START_IDENTITY =
            readProcessStartIdentity(android.os.Process.myPid());
    private static final String PROCESS_INSTANCE_NONCE =
            createProcessInstanceNonce();
    private static final String PARK_REAPER_PATH =
            "/data/local/tmp/overdrive_park_reaper.sh";
    private static final String PARK_REAPER_CONTROL_PATH =
            "/data/local/tmp/overdrive_park_reaper.control";
    private static final String PARK_REAPER_STATE_PATH =
            "/data/local/tmp/overdrive_park_reaper.state";
    private static final String PARK_REAPER_LEASE_PATH =
            "/data/local/tmp/overdrive_park_reaper.lease";
    private static final String PARK_REAPER_LEASE_OWNER_PATH =
            PARK_REAPER_LEASE_PATH + "/owner";
    private static final String PARK_REAPER_RUN_PATH =
            "/data/local/tmp/overdrive_park_reaper.running";
    private static final String PARK_REAPER_RUN_OWNER_PATH =
            PARK_REAPER_RUN_PATH + "/owner";
    private static final String PARK_REAPER_ACK_PREFIX =
            "/data/local/tmp/overdrive_park_reaper.ack.";
    private static final String PARK_REAPER_DONE_PREFIX =
            "/data/local/tmp/overdrive_park_reaper.done.";

    /** Process-local app context. Returns null before main() initialises it. */
    public static Context getAppContext() { return appContext; }

    // WakeLock for guaranteed CPU cycles. volatile + synchronized accessors
    // (acquireWakeLock/releaseWakeLock) because on dilink4 TWO HAL listeners
    // (BYDAutoBodyworkDevice + BYDAutoPowerDevice) fire enter/exitSentryMode
    // concurrently on independent binder threads — an unsynchronized check-then-act
    // here would let both create + acquire a non-ref-counted lock and orphan one.
    private static volatile PowerManager.WakeLock wakeLock;
    private static volatile android.net.wifi.WifiManager.WifiLock wifiLock;

    // Original screen timeout (saved before sentry mode)
    private static String originalScreenTimeout = "60000";

    // Daemon start time for uptime tracking
    private static long startTime = 0;
    
    // Handler for periodic status checks
    private static android.os.Handler statusHandler = null;

    // ==================== CENTRALIZED MCU POWER HELPER ====================
    // Cached BYDAutoPowerDevice instance to avoid repeated reflection
    private static BYDAutoPowerDevice cachedPowerDevice = null;
    
    // ==================== SPECIAL HARDWARE CONFIG (USB/POWER) ====================
    // Cached BYDAutoSpecialDevice for peripheral power control
    private static Object cachedSpecialDevice = null;
    
    // Magic config IDs from BYD malware analysis (C1310c class)
    // These control the BCM's peripheral power rail behavior
    // Per the DiCarServer feature catalog (dev/byd-property-bus/dicarserver_feature_catalog.txt)
    // these two are the vehicle's own SENTRY-MODE flags, not power-rail holds:
    //   782237711 = 0x2EA0000F SPECIAL_LOCAL_CTL_ENTER_SENTRY_MODE_SET
    //   782237728 = 0x2EA00020 SPECIAL_SENTRY_MODE_SET
    // The names below are kept (widely referenced) but the trailing comments are corrected:
    // holding sentry mode is what stops the MCU cutting the 5V/modem rails, which is why the
    // effect reads as a rail hold. Values unchanged.
    private static final int SPECIAL_CONFIG_REMOTE_POWER_MODE = 782237711;  // 0x2EA0000F enter sentry mode
    private static final int SPECIAL_CONFIG_DATA_MODULE_POWER = 782237728;  // 0x2EA00020 sentry-mode state
    
    /**
     * Get or create the cached BYDAutoPowerDevice instance.
     * Uses PermissionBypassContext for BYD hardware access.
     */
    private static BYDAutoPowerDevice getPowerDevice() {
        if (cachedPowerDevice != null) return cachedPowerDevice;
        if (appContext == null) return null;

        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            cachedPowerDevice = BYDAutoPowerDevice.getInstance(permissiveContext);
        } catch (Exception e) {
            log("Failed to get BYDAutoPowerDevice: " + e.getMessage());
        }
        return cachedPowerDevice;
    }
    
    /**
     * Candidate FQNs for BYDAutoSpecialDevice, probed in order.
     *
     * <p>TWO PACKAGES EXIST IN THE WILD. The reference OEM dashcam app
     * (flavor bydSofaPro) imports the BARE
     * {@code android.hardware.special.BYDAutoSpecialDevice} — confirmed in its
     * raw dex string table, not just a decompiler artifact:
     * <pre>
     *   classes2.dex:  /Landroid/hardware/special/BYDAutoSpecialDevice;
     * </pre>
     * Corroborating: the BYD SDK javadoc bundled in this repo under {@code doc/}
     * lists 20 {@code android.hardware.bydauto.*} packages and NO {@code special}
     * one — consistent with the class living outside the {@code bydauto} subtree.
     *
     * <p>We previously hardcoded ONLY the {@code .bydauto.special.} variant. When
     * that FQN is absent, {@code Class.forName} throws, this resolver returns null,
     * and EVERY {@link #setSpecialConfig} write becomes a silent no-op — which
     * silently disabled the oem sentry keep-alive pair (1901/1902) AND the OEM 409
     * camera/ISP power vote on any trim using the bare package. That is exactly the
     * "pano cameras unreachable with ACC off" symptom: the AVM/ISP rail is never
     * actually held, so post-ACC-OFF AVMCamera frames come back all-zero.
     *
     * <p>The previously-working FQN is tried FIRST, so any unit that resolved before
     * resolves identically now (same class, same instance, zero behaviour change).
     *
     * <p><b>The bare fallback is gated to DiLink 4.</b> It would be wrong to call the
     * extra candidate "strictly additive because the old path already returned null":
     * {@link #setSpecialConfig} is also reached from the FLEET-WIDE writes in
     * {@link #applyPeripheralPowerBatch} (782237711 / 782237728) and
     * {@link #applySentryIspPowerVote} (the 409 pair), which run on every variant. On
     * a legacy trim that ships only the bare class, all of those are currently inert
     * no-ops; making them start landing would newly drive BCM peripheral-power and
     * camera/ISP flags on hardware they have never been exercised against. Gating
     * keeps the legacy write set byte-identical while DiLink 4 gets the real device.
     */
    private static final String[] SPECIAL_DEVICE_CLASS_CANDIDATES = {
        // Tried first: preserves bit-exact behaviour on every trim that already
        // resolved this class (the 90% legacy fleet).
        "android.hardware.bydauto.special.BYDAutoSpecialDevice",
        // DiLink4 reality — verified in raw dex (see above). DiLink 4 ONLY.
        "android.hardware.special.BYDAutoSpecialDevice",
    };

    /** Number of leading {@link #SPECIAL_DEVICE_CLASS_CANDIDATES} probed on non-dilink4. */
    private static final int SPECIAL_DEVICE_LEGACY_CANDIDATE_COUNT = 1;

    /**
     * Get the BYDAutoSpecialDevice instance via reflection.
     * This device controls hidden BCM configuration for peripheral power.
     *
     * <p>Probes {@link #SPECIAL_DEVICE_CLASS_CANDIDATES} in order and caches the
     * first FQN whose {@code getInstance(Context)} returns non-null. Logs which
     * FQN won so a field log tells us unambiguously whether the rail writes are
     * landing at all — previously the failure was a single easy-to-miss line and
     * every downstream write no-oped in silence.
     */
    private static Object getSpecialDevice() {
        if (cachedSpecialDevice != null) return cachedSpecialDevice;
        if (appContext == null) return null;

        Context permissiveContext;
        try {
            permissiveContext = new PermissionBypassContext(appContext);
        } catch (Throwable t) {
            log("Failed to wrap context for BYDAutoSpecialDevice: " + t.getMessage());
            return null;
        }

        // Probe all candidate packages on DiLink 4 and DiLink 5
        int limit = (isDilink4CameraMode() || com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported())
                ? SPECIAL_DEVICE_CLASS_CANDIDATES.length
                : SPECIAL_DEVICE_LEGACY_CANDIDATE_COUNT;
        for (int i = 0; i < limit; i++) {
            String fqn = SPECIAL_DEVICE_CLASS_CANDIDATES[i];
            try {
                Class<?> clazz = Class.forName(fqn);
                Method getInstance = clazz.getMethod("getInstance", Context.class);
                Object device = getInstance.invoke(null, permissiveContext);
                if (device == null) {
                    log("BYDAutoSpecialDevice [" + fqn + "] getInstance returned null — trying next");
                    continue;
                }
                cachedSpecialDevice = device;
                log("BYDAutoSpecialDevice acquired via " + fqn);
                return cachedSpecialDevice;
            } catch (ClassNotFoundException e) {
                log("BYDAutoSpecialDevice [" + fqn + "] not present — trying next");
            } catch (Exception e) {
                log("BYDAutoSpecialDevice [" + fqn + "] resolve failed: " + e.getMessage());
            }
        }
        // Every probed candidate failed: all setSpecialConfig writes will no-op.
        // Log loudly — this is the difference between "sentry keep-alive armed"
        // and "silently doing nothing all night".
        log("ERROR: BYDAutoSpecialDevice unavailable (" + limit + " candidate(s) probed) — "
            + "sentry keep-alive (1901/1902) and 409 ISP vote will NOT be applied");
        return null;
    }
    
    /**
     * Sets a hidden BYD configuration value via BYDAutoSpecialDevice.
     * Used to keep USB/Peripherals powered during Sleep.
     *
     * @param configId The magic config ID (e.g., 782237711)
     * @param value The value to set (typically 0=OFF, 1=ON)
     */
    private static boolean setSpecialConfig(int configId, int value) {
        Object device = getSpecialDevice();
        if (device == null) {
            log("Cannot set Special Config - device unavailable");
            return false;
        }
        
        try {
            // 1. Create the Value Object (BYDAutoEventValue)
            Class<?> valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object valueObj = valueClass.newInstance();

            // 2. Set the integer value
            java.lang.reflect.Field intValueField = valueClass.getField("intValue");
            intValueField.setInt(valueObj, value);

            // 3. Set the value type (1 = Integer) - may be needed on some models
            try {
                java.lang.reflect.Field typeField = valueClass.getField("valueType");
                typeField.setInt(valueObj, 1);
            } catch (Exception ignored) {
                // Field might not exist on older SDKs
            }
            
            // 4. Call set(int[] ids, BYDAutoEventValue value)
            Class<?> deviceClass = device.getClass();
            Method setMethod = deviceClass.getMethod("set", int[].class, valueClass);
            int[] ids = { configId };
            Object rc = setMethod.invoke(device, ids, valueObj);

            boolean success;
            if (setMethod.getReturnType() == Void.TYPE) {
                success = true;
            } else {
                success = rc instanceof Number
                        && ((Number) rc).intValue() == 0;
            }
            if (!success) {
                log("Special Config [" + configId + "] <- " + value
                    + " REJECTED by HAL (rc=" + rc + ")");
            } else {
                log("Special Config [" + configId + "] set to: " + value
                    + " (rc=" + rc + ")");
            }
            return success;
        } catch (Exception e) {
            log("Failed to set Special Config [" + configId + "]: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Sets a hidden BYD configuration value via BYDAutoPowerDevice.
     * Used for power hold/release signals (e.g., -1442840502).
     * 
     * @param configId The power config ID
     * @param value The value to set
     */
    private static boolean setPowerConfig(int configId, int value) {
        BYDAutoPowerDevice device = getPowerDevice();
        if (device == null) {
            log("Cannot set Power Config - device unavailable");
            return false;
        }
        
        try {
            Class<?> valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object valueObj = valueClass.newInstance();
            
            java.lang.reflect.Field intValueField = valueClass.getField("intValue");
            intValueField.setInt(valueObj, value);
            
            try {
                java.lang.reflect.Field typeField = valueClass.getField("valueType");
                typeField.setInt(valueObj, 1);
            } catch (Exception ignored) {}
            
            Method setMethod = device.getClass().getMethod("set", int[].class, valueClass);
            int[] ids = { configId };
            Object rc = setMethod.invoke(device, ids, valueObj);
            boolean success;
            if (setMethod.getReturnType() == Void.TYPE) {
                success = true;
            } else {
                success = rc instanceof Number
                        && ((Number) rc).intValue() == 0;
            }
            if (success) {
                log("Power Config [" + configId + "] set to: " + value
                        + " (rc=" + rc + ")");
            } else {
                log("Power Config [" + configId + "] <- " + value
                        + " REJECTED by HAL (rc=" + rc + ")");
            }
            return success;
        } catch (Exception e) {
            log("Failed to set Power Config [" + configId + "]: " + e.getMessage());
            return false;
        }
    }

    /**
     * Toggles the "Remote Surveillance" power flags in the Gateway/BCM.
     * Matches the secondary reference app C1310c implementation exactly:
     *
     * DISABLE path:
     *   - SpecialDevice 782237711 = 0 (sentry keep-alive OFF)
     *   - SpecialDevice 782237728 = 2 (allow sleep — value is 2, NOT 0)
     *   - PowerDevice  -1442840502 = 0 (release power hold)
     *
     * ENABLE path (MCU status 1 or 10):
     *   - SpecialDevice 782237711 = 1 (sentry keep-alive ON)
     *   - SpecialDevice 782237728 = 1 (Modem/USB rail ON)
     *   - PowerDevice  -1442840502 = 1 ON dilink4 ONLY — oem kh/C6861d.java:344
     *     writes this on its sentry wake path. Without it the byd_apa MCU
     *     drops the AVM/ISP rail seconds after ACC OFF and any subsequent
     *     AVMCamera frames are all-zero. Legacy secondary-reference path skips this write
     *     (untouched, bit-exact 90% fleet behaviour).
     *
     * ENABLE path (MCU needs wake):
     *   - wakeUpMcu() loop, then signals + dilink4 power hold are set when MCU is ready.
     *
     * <p><b>ALL writes here are UNCONDITIONAL — identical regardless of the "Keep USB
     * powered" toggle.</b> The SpecialDevice rail writes do NOT gate USB-port power on
     * DiLink 3.0 (proven on-device). The toggle's ONLY effect is gating {@link
     * #performSystemWakeUp()} in {@link #enterSentryMode()}: the AP wake state is the
     * real USB lever (USB VBUS follows wakefulness). This method behaves the same in
     * both toggle states.
     *
     * @param enable true to keep peripherals powered, false to restore stock behavior
     */
    private static boolean isPeripheralPowerRequestCurrent(
            long generation, boolean enabled) {
        SentryTransitionState latest = latestSentryTransition;
        return latest.generation == generation
                && latest.keepAwakeEnabled() == enabled
                && peripheralPowerReconciler.isDesired(
                        generation, enabled);
    }

    private static void requestPeripheralPowerCompensation() {
        SentryTransitionState latest = latestSentryTransition;
        peripheralPowerReconciler.requestReapply(
                latest.generation, latest.keepAwakeEnabled());
    }

    private interface BoundedCall<T> {
        T run() throws Exception;
    }

    private static final class BoundedCallResult<T> {
        final boolean completed;
        final T value;
        final Throwable failure;

        BoundedCallResult(boolean completed, T value, Throwable failure) {
            this.completed = completed;
            this.value = value;
            this.failure = failure;
        }
    }

    /**
     * Serial primary lane plus one compensation slot. The compensation slot is
     * opened only after the primary exceeded its deadline, so a non-returning
     * Binder call cannot cause an unbounded replacement-thread cascade.
     */
    private static final class BoundedLatestCallLane {
        private final Object lock = new Object();
        private final String name;
        private Thread primary;
        private Thread compensation;
        private boolean primaryAbandoned;
        private long callSequence;

        BoundedLatestCallLane(String name) {
            this.name = name;
        }

        <T> BoundedCallResult<T> invoke(
                String label,
                long timeoutMs,
                ShellOwnership ownership,
                BoundedCall<T> call,
                Runnable staleCompensation) {
            final boolean useCompensation;
            final CallHolder<T> holder = new CallHolder<>();
            final Thread worker;
            synchronized (lock) {
                clearFinishedLocked();
                if (primary == null && compensation == null) {
                    useCompensation = false;
                } else if (primary != null
                        && primaryAbandoned
                        && compensation == null) {
                    useCompensation = true;
                } else {
                    return new BoundedCallResult<>(false, null, null);
                }

                long sequence = ++callSequence;
                worker = new Thread(() -> {
                    try {
                        if (isShellOwnershipCurrent(ownership)) {
                            holder.value = call.run();
                        }
                    } catch (Throwable failure) {
                        holder.failure = failure;
                    } finally {
                        holder.completed = true;
                        synchronized (lock) {
                            if (useCompensation) {
                                if (compensation == Thread.currentThread()) {
                                    compensation = null;
                                }
                            } else if (primary == Thread.currentThread()) {
                                primary = null;
                                primaryAbandoned = false;
                            }
                            lock.notifyAll();
                        }
                        if (!isShellOwnershipCurrent(ownership)
                                && staleCompensation != null) {
                            try {
                                staleCompensation.run();
                            } catch (Throwable ignored) {}
                        }
                    }
                }, name + (useCompensation ? "-Comp-" : "-Primary-")
                        + sequence);
                worker.setDaemon(true);
                if (useCompensation) {
                    compensation = worker;
                } else {
                    primary = worker;
                }
                try {
                    worker.start();
                } catch (Throwable startFailure) {
                    if (useCompensation) {
                        compensation = null;
                    } else {
                        primary = null;
                        primaryAbandoned = false;
                    }
                    return new BoundedCallResult<>(
                            false, null, startFailure);
                }
            }

            long deadline = android.os.SystemClock.elapsedRealtime()
                    + Math.max(1L, timeoutMs);
            boolean interrupted = false;
            while (worker.isAlive()) {
                long remaining = deadline
                        - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    worker.join(Math.min(remaining, 100L));
                } catch (InterruptedException interruption) {
                    interrupted = true;
                    break;
                }
            }
            if (worker.isAlive()) {
                worker.interrupt();
                synchronized (lock) {
                    if (!useCompensation && primary == worker) {
                        primaryAbandoned = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                log(name + " call timed out or was interrupted: " + label);
                return new BoundedCallResult<>(false, null, null);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return new BoundedCallResult<>(
                    holder.completed, holder.value, holder.failure);
        }

        private void clearFinishedLocked() {
            if (primary != null && !primary.isAlive()) {
                primary = null;
                primaryAbandoned = false;
            }
            if (compensation != null && !compensation.isAlive()) {
                compensation = null;
            }
        }

        boolean hasTwoStuckCalls() {
            synchronized (lock) {
                clearFinishedLocked();
                return primary != null
                        && primaryAbandoned
                        && compensation != null;
            }
        }

        private static final class CallHolder<T> {
            volatile boolean completed;
            volatile T value;
            volatile Throwable failure;
        }
    }

    private static final long HARDWARE_CALL_TIMEOUT_MS = 5000L;
    private static final BoundedLatestCallLane boundedHardwareLane =
            new BoundedLatestCallLane("SentryHardware");
    private static final BoundedLatestCallLane boundedDiagnosticHardwareLane =
            new BoundedLatestCallLane("SentryHardwareStatus");
    private static final BoundedLatestCallLane boundedAccPowerQueryLane =
            new BoundedLatestCallLane("AccPowerQuery");
    private static final BoundedLatestCallLane
            boundedPowerListenerRegistrationLane =
            new BoundedLatestCallLane("PowerListenerRegistration");
    private static final BoundedLatestCallLane
            boundedBodyworkRegistrationLane =
            new BoundedLatestCallLane("BodyworkRegistration");
    private static final Object accPowerQueryLock = new Object();
    private static long accPowerQueryRevision;
    private static long accPowerQuerySequence;
    private static Integer accPowerQueryDeliveredLevel;
    private static String accPowerQuerySource;
    private static boolean accPowerQueryHeartbeat;
    private static Thread accPowerQueryWorker;

    private static final class PowerLevelQueryRequest {
        final long revision;
        final long observationSequence;
        final Integer deliveredLevel;
        final String source;
        final boolean heartbeat;

        PowerLevelQueryRequest(
                long revision,
                long observationSequence,
                Integer deliveredLevel,
                String source,
                boolean heartbeat) {
            this.revision = revision;
            this.observationSequence = observationSequence;
            this.deliveredLevel = deliveredLevel;
            this.source = source;
            this.heartbeat = heartbeat;
        }
    }

    private static void requestPowerLevelSnapshot(
            long observationSequence,
            Integer deliveredLevel,
            String source,
            boolean heartbeat) {
        if (!running) {
            return;
        }
        Throwable startFailure = null;
        synchronized (accPowerQueryLock) {
            accPowerQueryRevision++;
            accPowerQuerySequence = observationSequence;
            accPowerQueryDeliveredLevel = deliveredLevel;
            accPowerQuerySource = source;
            accPowerQueryHeartbeat = heartbeat;
            if (accPowerQueryWorker == null) {
                startFailure = startAccPowerQueryWorkerLocked();
            }
            accPowerQueryLock.notifyAll();
        }
        if (startFailure != null) {
            log("ACC power-query worker could not start: "
                    + startFailure.getMessage());
            scheduleAccPowerQueryWorkerRestart();
        }
    }

    private static Throwable startAccPowerQueryWorkerLocked() {
        Thread worker = new Thread(
                AccSentryDaemon::runAccPowerQueryLoop,
                "AccPowerQuerySupervisor");
        worker.setDaemon(true);
        accPowerQueryWorker = worker;
        try {
            worker.start();
            return null;
        } catch (Throwable failure) {
            accPowerQueryWorker = null;
            return failure;
        }
    }

    private static void scheduleAccPowerQueryWorkerRestart() {
        android.os.Handler handler = statusHandler;
        if (handler == null) {
            return;
        }
        try {
            handler.postDelayed(() -> {
                Throwable startFailure = null;
                synchronized (accPowerQueryLock) {
                    if (running && accPowerQueryWorker == null) {
                        startFailure =
                                startAccPowerQueryWorkerLocked();
                    }
                }
                if (startFailure != null) {
                    log("ACC power-query worker restart failed: "
                            + startFailure.getMessage());
                    scheduleAccPowerQueryWorkerRestart();
                }
            }, 500L);
        } catch (Throwable failure) {
            log("ACC power-query restart could not be scheduled: "
                    + failure.getMessage());
        }
    }

    private static void runAccPowerQueryLoop() {
        long retryDelayMs = 100L;
        try {
            while (running
                    && !Thread.currentThread().isInterrupted()) {
                PowerLevelQueryRequest request;
                synchronized (accPowerQueryLock) {
                    request = new PowerLevelQueryRequest(
                            accPowerQueryRevision,
                            accPowerQuerySequence,
                            accPowerQueryDeliveredLevel,
                            accPowerQuerySource,
                            accPowerQueryHeartbeat);
                }

                ShellOwnership ownership = () ->
                        isPowerLevelQueryCurrent(request);
                BoundedCallResult<Integer> result =
                        boundedAccPowerQueryLane.invoke(
                                request.source,
                                5000L,
                                ownership,
                                AccSentryDaemon::readPowerLevel,
                                null);

                if (!isPowerLevelQueryCurrent(request)) {
                    retryDelayMs = 100L;
                    continue;
                }

                Integer level = result.completed
                        && result.failure == null
                        ? result.value : null;
                if (level != null
                        && level >= POWER_LEVEL_OFF
                        && level <= POWER_LEVEL_OK) {
                    applyPowerLevelQueryResult(request, level);
                    synchronized (accPowerQueryLock) {
                        if (request.revision
                                == accPowerQueryRevision) {
                            accPowerQueryWorker = null;
                            return;
                        }
                    }
                    retryDelayMs = 100L;
                    continue;
                }

                if (request.deliveredLevel != null
                        && request.deliveredLevel >= POWER_LEVEL_OFF
                        && request.deliveredLevel <= POWER_LEVEL_OK
                        && isPowerLevelQueryCurrent(request)) {
                    log("ACC HAL validation unavailable for "
                            + request.source + "; applying current callback "
                            + powerLevelToString(
                                    request.deliveredLevel));
                    applyObservedPowerLevelIfCurrent(
                            request.deliveredLevel,
                            request.source + " validation fallback",
                            request.observationSequence);
                    synchronized (accPowerQueryLock) {
                        if (request.revision
                                == accPowerQueryRevision) {
                            accPowerQueryWorker = null;
                            return;
                        }
                    }
                    retryDelayMs = 100L;
                    continue;
                }

                if (result.failure != null) {
                    log("ACC HAL snapshot failed for "
                            + request.source + ": "
                            + result.failure.getMessage());
                } else {
                    log("ACC HAL snapshot unavailable for "
                            + request.source + "; retrying");
                }

                synchronized (accPowerQueryLock) {
                    if (!running) {
                        return;
                    }
                    if (request.revision
                            != accPowerQueryRevision) {
                        retryDelayMs = 100L;
                        continue;
                    }
                    try {
                        accPowerQueryLock.wait(retryDelayMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (request.revision
                            == accPowerQueryRevision) {
                        retryDelayMs = Math.min(
                                retryDelayMs * 2L, 5000L);
                    } else {
                        retryDelayMs = 100L;
                    }
                }
            }
        } finally {
            boolean restart = false;
            synchronized (accPowerQueryLock) {
                if (accPowerQueryWorker
                        == Thread.currentThread()) {
                    accPowerQueryWorker = null;
                    restart = running;
                }
                accPowerQueryLock.notifyAll();
            }
            if (restart) {
                scheduleAccPowerQueryWorkerRestart();
            }
        }
    }

    private static boolean isPowerLevelQueryCurrent(
            PowerLevelQueryRequest request) {
        synchronized (accPowerQueryLock) {
            return running
                    && request.revision == accPowerQueryRevision
                    && request.observationSequence
                        == accPowerQuerySequence;
        }
    }

    private static void applyPowerLevelQueryResult(
            PowerLevelQueryRequest request, int currentLevel) {
        if (request.heartbeat) {
            applyHeartbeatPowerLevel(
                    currentLevel, request.observationSequence);
            return;
        }
        if (request.deliveredLevel != null
                && currentLevel
                    != request.deliveredLevel) {
            log("Replacing stale " + request.source + " "
                    + powerLevelToString(
                            request.deliveredLevel)
                    + " with current HAL state "
                    + powerLevelToString(currentLevel));
        }
        applyObservedPowerLevelIfCurrent(
                currentLevel,
                request.source,
                request.observationSequence);
    }

    private static void stopAccPowerQuerySupervisor() {
        Thread worker;
        synchronized (accPowerQueryLock) {
            accPowerQueryRevision++;
            accPowerQuerySource = null;
            worker = accPowerQueryWorker;
            accPowerQueryWorker = null;
            accPowerQueryLock.notifyAll();
        }
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
        }
    }

    private static boolean runBoundedHardwareBoolean(
            String label,
            ShellOwnership ownership,
            BoundedCall<Boolean> call,
            Runnable staleCompensation) {
        BoundedCallResult<Boolean> result = boundedHardwareLane.invoke(
                label,
                HARDWARE_CALL_TIMEOUT_MS,
                ownership,
                call,
                staleCompensation);
        if (result.failure != null) {
            log(label + " failed: " + result.failure.getMessage());
        }
        return result.completed
                && result.failure == null
                && Boolean.TRUE.equals(result.value);
    }

    private static final class PeripheralBatchContext {
        final long generation;
        final boolean enabled;
        boolean stale;

        PeripheralBatchContext(long generation, boolean enabled) {
            this.generation = generation;
            this.enabled = enabled;
        }

        boolean ensureCurrent(String stage) {
            if (isPeripheralPowerRequestCurrent(generation, enabled)) {
                return true;
            }
            if (!stale) {
                stale = true;
                log("Peripheral batch became stale around " + stage
                        + " (generation=" + generation
                        + ", enabled=" + enabled + "); compensating latest state");
                requestPeripheralPowerCompensation();
            }
            return false;
        }

        boolean writeSpecial(int configId, int value) {
            if (!ensureCurrent("SpecialDevice[" + configId + "] pre-write")) {
                return false;
            }
            // Best-effort: a HAL rejection (unsupported key on this trim) is logged
            // and discarded, never fed back as batch failure. Continue the batch so
            // one absent key can't skip the writes after it.
            runBoundedHardwareBoolean(
                    "SpecialDevice[" + configId + "]=" + value,
                    () -> isPeripheralPowerRequestCurrent(
                            generation, enabled),
                    () -> setSpecialConfig(configId, value),
                    AccSentryDaemon::requestPeripheralPowerCompensation);
            return ensureCurrent(
                    "SpecialDevice[" + configId + "] post-write");
        }

        boolean writePower(int configId, int value) {
            if (!ensureCurrent("PowerDevice[" + configId + "] pre-write")) {
                return false;
            }
            // Best-effort, same as writeSpecial above.
            runBoundedHardwareBoolean(
                    "PowerDevice[" + configId + "]=" + value,
                    () -> isPeripheralPowerRequestCurrent(
                            generation, enabled),
                    () -> setPowerConfig(configId, value),
                    AccSentryDaemon::requestPeripheralPowerCompensation);
            return ensureCurrent(
                    "PowerDevice[" + configId + "] post-write");
        }

        Integer readMcuStatus() {
            if (!ensureCurrent("MCU status pre-read")) {
                return null;
            }
            BoundedCallResult<Integer> result = boundedHardwareLane.invoke(
                    "MCU status",
                    HARDWARE_CALL_TIMEOUT_MS,
                    () -> isPeripheralPowerRequestCurrent(
                            generation, enabled),
                    AccSentryDaemon::getMcuStatus,
                    AccSentryDaemon::requestPeripheralPowerCompensation);
            if (!result.completed || result.failure != null) {
                return null;
            }
            return ensureCurrent("MCU status post-read")
                    ? result.value : null;
        }

        boolean wakeMcu() {
            if (!ensureCurrent("MCU wake pre-call")) {
                return false;
            }
            // The wake result still drives control flow (the status re-read below),
            // but a failed wake does not fail the batch — the writes are attempted
            // regardless, matching the reference behaviour.
            runBoundedHardwareBoolean(
                    "MCU wake",
                    () -> isPeripheralPowerRequestCurrent(
                            generation, enabled),
                    AccSentryDaemon::wakeUpMcu,
                    AccSentryDaemon::requestPeripheralPowerCompensation);
            return ensureCurrent("MCU wake post-call");
        }

        Boolean readDilink4Mode() {
            if (!ensureCurrent("camera-mode pre-read")) {
                return null;
            }
            boolean dilink4 = isDilink4CameraMode();
            return ensureCurrent("camera-mode post-read")
                    ? dilink4 : null;
        }

        boolean sleep(long delayMs) {
            long deadline =
                    android.os.SystemClock.elapsedRealtime() + delayMs;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!ensureCurrent("retry delay")) {
                    return false;
                }
                long remaining = deadline
                        - android.os.SystemClock.elapsedRealtime();
                try {
                    Thread.sleep(Math.min(remaining, 50L));
                } catch (InterruptedException interrupted) {
                    Thread.interrupted();
                    if (!ensureCurrent("interrupted retry delay")) {
                        return false;
                    }
                }
            }
            return ensureCurrent("retry delay completion");
        }

        // The batch is fire-and-forget: reaching the end counts as applied even if
        // individual keys were rejected, so the reconciler never re-runs it. A trim
        // that permanently lacks a key would otherwise be retried forever (250ms-5s
        // backoff, re-armed by every periodic re-assert) and never idle while parked.
        boolean finish() {
            ensureCurrent("batch completion");
            return true;
        }

        // Never ask the reconciler to re-run: a stale batch is already superseded by
        // the compensation request, and an aborted one (e.g. the MCU status read
        // timing out) is picked up by the next periodic re-assert instead of a
        // tight retry.
        boolean abortResult() {
            return true;
        }
    }

    private static boolean applyPeripheralPowerBatch(
            long generation, boolean enable) {
        PeripheralBatchContext batch =
                new PeripheralBatchContext(generation, enable);
        if (!batch.ensureCurrent("batch start")) {
            return batch.abortResult();
        }
        log("Configuring Peripheral Power (USB/Data): "
                + (enable ? "ON" : "OFF")
                + " generation=" + generation);

        Boolean dilink4Value = batch.readDilink4Mode();
        if (dilink4Value == null) {
            return batch.abortResult();
        }
        boolean dilink4 = dilink4Value;

        if (!enable) {
            if (!batch.writeSpecial(
                    SPECIAL_CONFIG_REMOTE_POWER_MODE, 0)) {
                return batch.abortResult();
            }
            if (!batch.writeSpecial(
                    SPECIAL_CONFIG_DATA_MODULE_POWER, 2)) {
                return batch.abortResult();
            }
            if (!batch.writePower(OEM_MCU_POWER_HOLD_ID, 0)) {
                return batch.abortResult();
            }
            if (dilink4) {
                if (!batch.writeSpecial(OEM_SENTRY_KEY_1, 0)
                        || !batch.writeSpecial(OEM_SENTRY_KEY_2, 2)) {
                    return batch.abortResult();
                }
            }
            if (!batch.writeSpecial(SENTRY_ISP_NEED_409_SET, 0)
                    || !batch.writeSpecial(SENTRY_ISP_WORK_STATE_SET, 0)) {
                return batch.abortResult();
            }
            return batch.finish();
        }

        if (dilink4
                && !batch.writePower(OEM_MCU_POWER_HOLD_ID, 1)) {
            return batch.abortResult();
        }

        Integer mcuStatusValue = batch.readMcuStatus();
        if (mcuStatusValue == null) {
            return batch.abortResult();
        }
        int mcuStatus = mcuStatusValue;
        log("MCU status for peripheral power: " + mcuStatus);
        if (mcuStatus != 1 && mcuStatus != 10) {
            log("MCU not ready (status=" + mcuStatus
                    + "), waking up and retrying...");
            if (!batch.wakeMcu() || !batch.sleep(1000L)) {
                return batch.abortResult();
            }
            mcuStatusValue = batch.readMcuStatus();
            if (mcuStatusValue == null) {
                return batch.abortResult();
            }
            mcuStatus = mcuStatusValue;
            log("MCU status after wake: " + mcuStatus);
            if (mcuStatus != 1 && mcuStatus != 10) {
                if (!batch.wakeMcu() || !batch.sleep(1000L)) {
                    return batch.abortResult();
                }
                log("Forcing peripheral power enable after second wake attempt");
            }
        }

        if (!batch.writeSpecial(
                SPECIAL_CONFIG_REMOTE_POWER_MODE, 1)
                || !batch.writeSpecial(
                        SPECIAL_CONFIG_DATA_MODULE_POWER, 1)) {
            return batch.abortResult();
        }
        if (dilink4) {
            if (!batch.writeSpecial(OEM_SENTRY_KEY_1, 1)
                    || !batch.writeSpecial(OEM_SENTRY_KEY_2, 1)
                    || !batch.writePower(OEM_MCU_POWER_HOLD_ID, 1)) {
                return batch.abortResult();
            }
        }
        if (!batch.writeSpecial(SENTRY_ISP_NEED_409_SET, 1)
                || !batch.writeSpecial(SENTRY_ISP_WORK_STATE_SET, 1)) {
            return batch.abortResult();
        }
        return batch.finish();
    }

    // ==================== OEM-PARITY MCU POWER HOLD (DILINK 4) ====================

    // PowerDevice eventId 0xAA00004A = -1442840502. On byd_apa firmware the
    // MCU governs the AVM/ISP power rail; without this set=1 write on the
    // sentry-wake path the rail collapses post ACC OFF and the AVMCamera
    // handle delivers all-zero buffers (size <= 1.9 KB encoded H.264).
    //
    // Oem kh/C6861d.java m30178I (line 344) writes intValue=1 on wake and
    // m30176G writes intValue=0 on sleep. We already write 0 on disable
    // (applyPeripheralPowerBatch DISABLE branch). The matching set=1 was
    // missing on the enable branches, by mistake.
    //
    // Gated to dilink4 — legacy secondary-reference path stays bit-exact unchanged.
    private static final int OEM_MCU_POWER_HOLD_ID = -1442840502;

    private static boolean applyOemMcuPowerHold(boolean enable) {
        if (!isDilink4CameraMode()) return true;
        if (enable) {
            log("[oem-parity] McuStatus = ON (PowerDevice -1442840502 = 1)");
            return setPowerConfig(OEM_MCU_POWER_HOLD_ID, 1);
        }
        // Disable path is already covered in applyPeripheralPowerBatch's
        // DISABLE branch via setPowerConfig(-1442840502, 0); no separate
        // call needed here. Kept symmetric for future callers.
        return true;
    }

    // ==================== V1-PARITY UNCONDITIONAL POWER HOLD (DILINK 4) ====================

    /**
     * V1-parity MCU power hold: write {@code -1442840502 = 1} IMMEDIATELY and
     * UNCONDITIONALLY on the ACC-OFF path, with no MCU-status precondition.
     *
     * <p><b>Why this exists.</b> We ported the reference app's
     * BatteryVoltageMonitor<b>V2</b> (its {@code kh/d}) and wired it up as if it
     * were the shipping behaviour. It is not. The reference app selects its
     * monitor at runtime from the {@code key.flameout.wakeup.mode} preference
     * ({@code kh/a.c()}), and that preference <b>defaults to 0 → V1</b>
     * ({@code vj/a.m()} returns {@code d("key.flameout.wakeup.mode", 0)}).
     *
     * <p>V1 ({@code kh/b}) is drastically simpler than what we implemented — its
     * whole ACC-off behaviour is a single unconditional write:
     * <pre>
     *   BYDAutoEventValue v; v.intValue = 1;
     *   powerDevice.set(new int[]{-1442840502}, v);   // "wakeUp"
     * </pre>
     * and it <b>never sleeps the MCU at all</b> — no voltage hysteresis, no
     * 15-minute deferred sleep, no {@code getMcuStatus} gate.
     *
     * <p>Our equivalent write ({@link #applyOemMcuPowerHold}) only fires from
     * inside {@link #applyPeripheralPowerBatch}'s branches, all of which are
     * predicated on {@code getMcuStatus()} reading 1 or 10 (or on a wake-retry
     * succeeding). When the MCU reports any other status the hold was never
     * written, so on DiLink 4 the AVM/ISP rail could collapse right after ACC OFF
     * and every subsequent AVMCamera frame came back all-zero.
     *
     * <p>Idempotent (it is the same value the status-gated path writes when it
     * does run), cheap (one binder write), and gated to
     * {@code cameraMode=dilink4} so the legacy fleet's sequence is byte-identical.
     */
    private static boolean applyV1ParityMcuPowerHold() {
        if (!isDilink4CameraMode()) return true;
        log("[v1-parity] unconditional MCU power hold (PowerDevice -1442840502 = 1) "
            + "— no MCU-status precondition, mirrors reference-app default mode 0 (V1)");
        return setPowerConfig(OEM_MCU_POWER_HOLD_ID, 1);
    }

    // NOTE: the matching "never sleep the MCU on dilink4" gate lives in
    // BatteryVoltageMonitorV2.isDilink4CameraMode() — that class deliberately
    // reads the config itself rather than calling across into this daemon
    // (it must work in whichever process boots it), exactly as it already does
    // for isKeepUsbPowerOnAccOff().

    // ==================== OEM-PARITY SENTRY KEYS (DILINK 4) ====================

    // Oem's BatteryVoltageMonitorV2 sentry keep-alive IDs. Different magic
    // numbers from our 782237711 / 782237728 (which are secondary-reference-derived, kept
    // additive and unchanged for legacy fleet). On byd_apa firmware the
    // AVMCamera HAL gates frame production on these specific BYD-internal
    // peripheral-power flags being held active; without them the producer
    // surface delivers all-zero pixels post ACC OFF.
    //
    // ENABLE  (oem kh/C6861d.java m30171B "sentry wakeUp"): [1901]=1, [1902]=1
    // DISABLE (oem kh/C6861d.java m30170A "sentry sleep"):  [1901]=0, [1902]=2
    //
    // Gated to cameraMode=dilink4. Legacy cars don't read or write these.
    private static final int OEM_SENTRY_KEY_1 = 1901;
    private static final int OEM_SENTRY_KEY_2 = 1902;

    private static boolean applyOemSentrySpecialConfig(boolean enable) {
        if (!isDilink4CameraMode()) return true;
        boolean success = true;
        if (enable) {
            log("[oem-parity] sentry wakeUp: SpecialDevice [1901]=1, [1902]=1");
            success &= setSpecialConfig(OEM_SENTRY_KEY_1, 1);
            success &= setSpecialConfig(OEM_SENTRY_KEY_2, 1);
        } else {
            log("[oem-parity] sentry sleep: SpecialDevice [1901]=0, [1902]=2");
            success &= setSpecialConfig(OEM_SENTRY_KEY_1, 0);
            success &= setSpecialConfig(OEM_SENTRY_KEY_2, 2);
        }
        return success;
    }

    // ==================== SENTRY ISP / CAMERA POWER VOTE (409) ====================

    // The OEM BYD Sentry Mode app (com.byd.sentrymode) casts a dedicated
    // camera/ISP power vote on its sentry-arm path that NONE of our existing
    // keep-alive writes cover. The secondary-reference keys (782237711/782237728) hold the
    // 5V/modem/USB rails; the oem keys (1901/1902) and MCU hold (-1442840502)
    // hold the byd_apa AVM rail on dilink4. But the OEM's "409" pair is the
    // sentry-mode CAMERA/ISP power request specifically. Without it, on certain
    // trims the AVM ISP rail power-gates after ~30-35 min of inactivity and
    // AVMCamera frames go all-black — recovering only when the OEM Sentry app
    // (or anything) re-casts this vote. That recovery-on-OEM-event is exactly
    // the symptom we set out to fix; replicating the vote ourselves makes our
    // feed self-sustaining without depending on the OEM app being armed.
    //
    // OEM source (decompiled com.byd.sentrymode):
    //   AutoApiManager.set409Value(v):       SpecialDevice[0x4090103E] = v
    //   AutoApiManager.set409SentryState(v): SpecialDevice[0x4090103C] = v
    //   SentryModeFuncRequest:451   set409Value(1); set409SentryState(1);  (arm)
    //   AutoApiManager:976-977      set409Value(0); set409SentryState(0);  (exit)
    //   The OEM gates set409Value on isMcuWake()/wakeUpMcu(); our callers
    //   (applyPeripheralPowerBatch / keep-alive) only fire this after the MCU is
    //   already confirmed awake, so no extra wake is needed here.
    //
    // Applied FLEET-WIDE (NOT gated to dilink4): the OEM writes these from the
    // generic AutoApiManager with no car-type gate, and the black-frame symptom
    // spans multiple models — gating to dilink4 would miss affected legacy
    // trims. setSpecialConfig is best-effort, so an unsupported key on a given
    // trim is a logged no-op; this is safe additive coverage for legacy too.
    // 0x4090103E = 1083183166, 0x4090103C = 1083183164.
    private static final int SENTRY_ISP_NEED_409_SET   = 0x4090103E;
    private static final int SENTRY_ISP_WORK_STATE_SET = 0x4090103C;

    private static boolean applySentryIspPowerVote(boolean enable) {
        int v = enable ? 1 : 0;
        log("[sentry-isp] 409 camera/ISP power vote " + (enable ? "ON" : "OFF")
            + ": SpecialDevice[0x4090103E]=" + v + " [0x4090103C]=" + v);
        boolean success = setSpecialConfig(SENTRY_ISP_NEED_409_SET, v);
        success &= setSpecialConfig(SENTRY_ISP_WORK_STATE_SET, v);
        return success;
    }
    
    /**
     * Get current MCU status.
     * @return MCU status code, or -1 if unavailable
     */
    private static int getMcuStatus() {
        BYDAutoPowerDevice device = getPowerDevice();
        if (device == null) return -1;
        
        try {
            return device.getMcuStatus();
        } catch (Exception e) {
            log("getMcuStatus error: " + e.getMessage());
            return -1;
        }
    }
    
    /**
     * Wake up the MCU. Returns true on success.
     */
    private static boolean wakeUpMcu() {
        BYDAutoPowerDevice device = getPowerDevice();
        if (device == null) {
            log("wakeUpMcu: No power device available");
            return false;
        }
        
        try {
            int result = device.wakeUpMcu();
            return result == 0;
        } catch (Exception e) {
            log("wakeUpMcu error: " + e.getMessage());
            return false;
        }
    }
    
    // Lock file for singleton enforcement
    private static final String LOCK_FILE = "/data/local/tmp/acc_sentry_daemon.lock";
    private static java.io.RandomAccessFile lockFileHandle;
    private static java.nio.channels.FileLock fileLock;

    public static void main(String[] args) {
        int myUid = android.os.Process.myUid();

        // Configure DaemonLogger for daemon context (enable stdout for app_process)
        DaemonLogger.configure(DaemonLogger.Config.defaults()
            .withStdoutLog(true)
            .withFileLog(true)
            .withConsoleLog(true));

        logger = DaemonLogger.getInstance(TAG, PATH_DATA_LOCAL_TMP());
        
        // CRITICAL: Acquire singleton lock FIRST - exit if another instance is running
        if (!acquireSingletonLock()) {
            log("ERROR: Another AccSentryDaemon instance is already running. Exiting.");
            System.exit(1);
            return;
        }
        if (!publishParkReaperBootCancellation()) {
            log("WARNING: could not invalidate detached reapers from an older process");
        }

        log("=== ACC Sentry Daemon Starting ===");
        log("UID: " + myUid + " (expected: 2000 shell)");
        log("PID: " + android.os.Process.myPid());

        // Initialize unified config so calls into isSurveillanceEnabled() and
        // getSurveillanceSchedule() see the on-disk config (and trigger legacy
        // migration if needed) when AccSentryDaemon starts before CameraDaemon.
        // Idempotent — CameraDaemon also calls this.
        try {
            com.overdrive.app.config.UnifiedConfigManager.init();
        } catch (Exception e) {
            log("UnifiedConfigManager.init() failed: " + e.getMessage());
        }

        // SIGKILL recovery: clear cross-process screen-deterrent flags. Both
        // are normally cleared on the next ACC OFF (enterSentryMode lines
        // ~900-907) so this is mostly defensive — but if the daemon was
        // killed mid-exitSentryMode between the screenDeterrentForceStop=true
        // write (line ~998) and the worker thread's clear (line ~1047), and
        // the next event happens to be a manual deterrent fire while the car
        // is parked-but-already-in-sentry, the stale flag would block it.
        // Mirroring CameraDaemon.main()'s top-of-process clear keeps the
        // pair symmetric. See feedback memory: daemon-shutdown-clears-state.
        try {
            java.util.Map<String, Object> reset = new java.util.HashMap<>();
            reset.put("screenDeterrentForceStop", false);
            reset.put("screenDeterrentActiveUntilMs", 0L);
            com.overdrive.app.config.UnifiedConfigManager.updateValues(
                    "surveillance", reset);
        } catch (Throwable ignored) {}

        // Record start time for uptime tracking
        startTime = System.currentTimeMillis();

        if (myUid != 2000) {
            log("WARNING: Not running as shell (UID 2000)! Screen control may not work.");
        }

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        // Create handler for periodic status checks
        statusHandler = new android.os.Handler(Looper.myLooper());
        pumpFallbackReconcilerRetries();

        try {
            Context context = createAppContext();
            if (context == null) {
                log("createAppContext failed, trying getSystemContext...");
                context = getSystemContext();
            }

            if (context != null) {
                log("Got context: " + context);
                appContext = context;

                // Debug: Dump sleep reason constants to identify correct values for this firmware
                //logAllSleepReasonFields();

                // Dump all power-related methods for discovery
                //dumpPowerManagerMethods();
                //dumpBydPowerDeviceMethods();
                //dumpBydSettingDeviceMethods();

                // Dump all BYD device methods for discovery
                //dumpAllBydDeviceMethods();
                
                // Test instrument device (charging power)
                //testInstrumentDevice();

                // Acquire WakeLock for guaranteed CPU cycles.
                // GATE (G4a): in "Vehicle ON only" mode, do NOT hold the process-wide
                // AccSentry:Core wakelock while the car is already OFF at daemon start.
                // This matters on a watchdog/revival RESPAWN while parked: no ACC power
                // edge fires on a respawn, so enterSentryMode()'s G1 gate never runs and
                // the freshly-spawned daemon would otherwise pin the CPU awake forever.
                // probeAccState() returns true only when ACC is CONFIRMED OFF; on ACC-ON
                // or any reflection/HAL failure it returns false → we still acquire
                // (fail-open, current behaviour preserved). The wakelock is re-acquired
                // on the ACC-ON edge in exitSentryMode() (G4b).
                if (com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()
                        && com.overdrive.app.monitor.AccMonitor.probeAccState(appContext)) {
                    log("onOnly + ACC currently OFF: skipping core wakelock so AP can sleep");
                } else {
                    acquireWakeLock();
                }
                //forceSmartSleepReflection();
                
                // SIGKILL recovery: if the previous instance darkened the panel
                // while parked and was then killed, the backlight is still off
                // and nothing is left to wake it. Restore here when ACC reads
                // ON, so a driver can never be handed a dark panel by a daemon
                // that died. probeAccState() returns true only when ACC is
                // confirmed OFF, so `!probeAccState` is our ON signal; on any
                // HAL/reflection failure it returns false, which reads as "ON"
                // here — deliberately fail-VISIBLE: the worst case is waking a
                // parked car's panel, which the keep-alive re-darkens within one
                // 10 s tick, whereas the opposite failure (leaving it dark) is
                // not recoverable by the user. turnOn() self-skips when the
                // screen already reads on, so this is a no-op on a normal boot.
                try {
                    if (!com.overdrive.app.monitor.AccMonitor.probeAccState(appContext)) {
                        requestPanelForLatestTransition();
                    }
                } catch (Throwable t) {
                    log("Stealth panel boot recovery failed: " + t.getMessage());
                }

                // CRITICAL: Whitelist our app from ACC power management killing
                whitelistAppPackageOld();

                // CRITICAL: Whitelist app UID with BYD background data-cache services.
                // BgDataCacheService accepts shell UID (2000), so this only succeeds
                // when called from the daemon — not from MainActivity (UID 10xxx).
                applyDataCacheWhitelist();

                // Install shutdown hook for debugging process termination
                installShutdownHook();
                
                // Log initial memory status
                logMemoryStatus();
                
                // Start periodic status monitoring
                startStatusMonitoring();

                // Prevent Wi-Fi sleep policy from disconnecting when display is darkened
                execShell("settings put global wifi_sleep_policy 2");
                execShell("settings put global wifi_suspend_optimizations_enabled 0");
                
                // BYD traffic monitor: user-opt-in only. TrafficMonitorPolicy owns the
                // toggle, and CameraDaemon re-applies it on boot when the user opted in.

                // Note: VehicleDataMonitor is initialized in CameraDaemon (separate process)
                // which handles the HTTP API for vehicle data
            } else {
                log("WARNING: Running without context");
            }

            // Registration can enter a vendor Binder call that never returns.
            // Keep startup moving while a bounded supervisor retries it.
            startBodyworkListenerRegistrationSupervisor(context);

            // Oem-parity: ALSO register BYDAutoPowerDevice
            // onPowerCtlStatusChanged listener for event id 0x99000037
            // (= -1728053193). Oem's sentry/camera pipeline gates on this
            // signal (oem bk/C1478c.java:71-75 and p111dh/C4995i.java
            // FlameoutService). The bodywork onPowerLevelChanged signal is
            // different in timing/state from the power-ctl signal on
            // byd_apa firmware. Both listeners fan into the same
            // idempotent enterSentryMode/exitSentryMode — whichever
            // fires first wins, the other is a no-op.
            //
            // Gated to dilink4 — legacy fleet keeps the bodywork-only
            // path bit-exact unchanged.
            if (isDilink4CameraMode()) {
                startPowerListenerRegistrationSupervisor(context);
            }

            log("Daemon running, entering persistence loop...");
            
            // UNKILLABLE LOOP WRAPPER - Crash-proof main loop
            // Automatically restarts logic if a random crash occurs
            while (true) {
                try {
                    // Start the message pump. This blocks until an exception occurs.
                    Looper.loop();
                } catch (Throwable e) {
                    // Catch ANY crash (Exception or Error)
                    log("CRASH DETECTED in Main Loop: " + e.getMessage());
                    e.printStackTrace();
                    
                    // Safety pause to prevent CPU spiking if crash is repetitive
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored) {}
                    
                    log("Restarting message queue...");
                    if (Looper.myLooper() == null) {
                        Looper.prepare();
                    }
                }
            }

        } catch (Exception e) {
            log("FATAL: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void log(String msg) {
        if (logger != null) {
            logger.info(msg);
        }
        // Note: System.out.println is now handled by DaemonLogger when enableStdoutLog is true
    }
    
    // ==================== SINGLETON LOCK ====================
    
    /**
     * Acquire a file lock to ensure only one daemon instance runs at a time.
     */
    private static boolean acquireSingletonLock() {
        try {
            java.io.File lockFileObj = new java.io.File(LOCK_FILE);
            lockFileHandle = new java.io.RandomAccessFile(lockFileObj, "rw");
            java.nio.channels.FileChannel channel = lockFileHandle.getChannel();
            
            // Try to acquire exclusive lock (non-blocking)
            fileLock = channel.tryLock();
            
            if (fileLock == null) {
                lockFileHandle.close();
                return false;
            }
            
            // Write our PID to the lock file
            lockFileHandle.setLength(0);
            lockFileHandle.writeBytes(String.valueOf(android.os.Process.myPid()));
            
            log("Acquired singleton lock (PID: " + android.os.Process.myPid() + ")");
            
            // Register shutdown hook to release lock on process termination
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                shutdownDaemon();
            }, "DaemonCleanup"));
            
            return true;
            
        } catch (java.nio.channels.OverlappingFileLockException e) {
            log("Lock already held by this process");
            return false;
        } catch (Exception e) {
            log("Failed to acquire singleton lock: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Release the singleton lock on shutdown.
     */
    private static void releaseSingletonLock() {
        try {
            if (fileLock != null) {
                fileLock.release();
                fileLock = null;
            }
            if (lockFileHandle != null) {
                lockFileHandle.close();
                lockFileHandle = null;
            }
            new java.io.File(LOCK_FILE).delete();
        } catch (Exception e) {
            log("Error releasing singleton lock: " + e.getMessage());
        }
    }

    // ==================== WAKELOCK MANAGEMENT ====================

    private static synchronized void acquireWakeLock() {
        if (appContext == null) return;

        if (wakeLock == null || !wakeLock.isHeld()) {
            try {
                Context permissiveContext = new PermissionBypassContext(appContext);
                PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AccSentry:Core");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
                log("WakeLock Acquired");
            } catch (Throwable e) {
                log("WakeLock Error: " + e.getMessage());
            }
        }
        acquireWifiLock();
    }

    private static synchronized void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                log("WakeLock Released");
            } catch (Throwable e) {
                // Ignore
            }
        }
        releaseWifiLock();
    }

    private static synchronized void acquireWifiLock() {
        if (wifiLock != null && wifiLock.isHeld()) return;
        if (appContext == null) return;

        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    permissiveContext.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "AccSentry:Wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                log("WifiLock Acquired");
            }
        } catch (Throwable e) {
            log("WifiLock Error: " + e.getMessage());
        }
    }

    private static synchronized void releaseWifiLock() {
        if (wifiLock != null && wifiLock.isHeld()) {
            try {
                wifiLock.release();
                log("WifiLock Released");
            } catch (Throwable ignored) {}
        }
    }

    // ==================== ACC WHITELIST ====================
    /**
     * Whitelist app package from ACC power management killing.
     * 
     * Loads the real system IAccModeManager$Stub via Class.forName from the boot
     * classloader, guaranteeing the correct transaction code is used.
     * 
     * Fallback: direct binder transact with TX code 2 (confirmed working).
     */
    private static void whitelistAppPackageOld() {
        whitelistAccPackage(APP_PACKAGE_NAME());
        whitelistAccPackage("com.byd.warning");
    }

    private static void whitelistAccPackage(String pkg) {
        log("Whitelisting package " + pkg + " via accmodemanager...");

        boolean success = false;

        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, SERVICE_ACCMODEMANAGER());

            if (binder != null) {
                log("Got accmodemanager binder: " + binder);

                // === STRATEGY 1: Load real system stub via Class.forName ===
                try {
                    Class<?> stubClass = Class.forName("android.os.IAccModeManager$Stub");
                    Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                    Object manager = asInterface.invoke(null, binder);

                    if (manager != null) {
                        Method setPkg = manager.getClass().getMethod("setPkg2AccWhiteList", String.class);
                        setPkg.invoke(manager, pkg);
                        log("Whitelisted successfully via system stub!");
                        success = true;
                    } else {
                        log("System stub asInterface returned null");
                    }
                } catch (Exception e) {
                    String msg = (e.getCause() != null) ? e.getCause().getMessage() : e.getMessage();
                    log("System stub method failed: " + msg);
                }

                // === STRATEGY 2: Direct binder transact with known TX code 2 ===
                if (!success) {
                    log("System stub failed, trying direct transact (TX code 2)...");
                    success = whitelistViaDirectTransact(binder, pkg);
                }

            } else {
                log("accmodemanager service not found");
            }
        } catch (Exception e) {
            log("Binder Access Error: " + e.getMessage());
            e.printStackTrace();
        }

        if (!success) {
            log("WARNING: All whitelist strategies failed - app may be killed during ACC OFF");
        }
    }

    /**
     * Direct binder transact fallback using TX code 2 (confirmed working).
     * If TX code 2 fails, scans codes 1-5 for firmware variations.
     */
    private static boolean whitelistViaDirectTransact(IBinder binder, String packageName) {
        if (tryTransactCode(binder, packageName, 2)) {
            return true;
        }

        log("TX code 2 failed, scanning codes 1-5...");
        for (int code = 1; code <= 5; code++) {
            if (code == 2) continue;
            if (tryTransactCode(binder, packageName, code)) {
                return true;
            }
        }

        log("Direct transact: no working transaction code found");
        return false;
    }

    private static boolean tryTransactCode(IBinder binder, String packageName, int code) {
        try {
            android.os.Parcel data = android.os.Parcel.obtain();
            android.os.Parcel reply = android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken("android.os.IAccModeManager");
                data.writeString(packageName);

                boolean transactSuccess = binder.transact(code, data, reply, 0);
                if (transactSuccess) {
                    reply.readException();
                    log("Whitelist SUCCESS with transaction code " + code);
                    return true;
                }
            } catch (Exception e) {
                log("TX code " + code + ": " + e.getMessage());
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            // Parcel obtain failed
        }
        return false;
    }

    // ==================== DATA-CACHE WHITELIST ====================
    /**
     * Whitelist app UID with BYD background data-cache services.
     *
     * BYD's BgDataCacheService accepts the shell UID (2000), so calls from this
     * daemon succeed where the same call from MainActivity (UID 10xxx) hits the
     * AppOps gate. Mirrors the secondary reference app's vanss daemon, which arrives at shell UID via
     * an ADB-localhost tunnel and then makes this exact call.
     *
     * SDK ≥ 31 → byd_datacached.setAppStartupData(uid, 0)
     * SDK < 31 → bg_datacache.setAppOpsData(uid, 0)
     *
     * Threshold matches oem's C0241c.m941c() — earlier we used >= 32, but
     * BYD DiLink 4 ROMs that ship Android 12 (API 31) base have the new
     * byd_datacached service available, and the old bg_datacache.setAppOpsData
     * gates on ACCESS_APPOPSDATA (denied to shell UID 2000 → frames all-black
     * post ACC OFF on byd_apa).
     */
    private static void applyDataCacheWhitelist() {
        if (appContext == null) {
            log("applyDataCacheWhitelist: no context");
            return;
        }

        String pkg = APP_PACKAGE_NAME();
        int appUid;
        try {
            appUid = appContext.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Exception e) {
            log("applyDataCacheWhitelist: failed to resolve UID: " + e.getMessage());
            return;
        }
        String uidStr = String.valueOf(appUid);
        log("Applying data-cache whitelist for " + pkg + " (uid=" + appUid + ")");

        Context permissiveContext = new PermissionBypassContext(appContext);
        // Probe both services — oem gates on SDK_INT >= 31, but some BYD
        // ROMs (DiLink 4 on Android 11 base, build markers report SDK 30
        // even though the BYD branding says "DiLink 4") expose
        // byd_datacached without bumping SDK_INT. Try the new service
        // unconditionally and only fall through to the legacy service when
        // it returns null. Removes the false negative we hit when SDK is
        // exactly 30 but byd_datacached IS available.
        log("Data-cache whitelist: SDK_INT=" + android.os.Build.VERSION.SDK_INT
            + " — probing byd_datacached first regardless");

        try {
            Object service = permissiveContext.getSystemService(SERVICE_BYD_DATACACHE());
            if (service != null) {
                Method m = service.getClass().getMethod("setAppStartupData", String.class, Integer.TYPE);
                m.invoke(service, uidStr, 0);
                log("setAppStartupData OK (uid=" + appUid + ")");
                return;
            }
            log("byd_datacached service unavailable — falling through to bg_datacache");
        } catch (java.lang.reflect.InvocationTargetException ite) {
            log("setAppStartupData rejected: " + ite.getCause());
        } catch (NoSuchMethodException nsme) {
            log("setAppStartupData method not on this ROM: " + nsme.getMessage());
        } catch (Exception e) {
            log("setAppStartupData failed: " + e.getMessage());
        }

        try {
            Object service = permissiveContext.getSystemService(SERVICE_BG_DATACACHE());
            if (service != null) {
                Method m = service.getClass().getMethod("setAppOpsData", String.class, Integer.TYPE);
                m.invoke(service, uidStr, 0);
                log("setAppOpsData OK (uid=" + appUid + ")");
                return;
            }
            log("bg_datacache service unavailable");
        } catch (java.lang.reflect.InvocationTargetException ite) {
            log("setAppOpsData rejected: " + ite.getCause());
        } catch (Exception e) {
            log("setAppOpsData failed: " + e.getMessage());
        }
    }

    // ==================== ACC STATE DETECTION ====================

    private static boolean isBodyworkSupported() {
        try {
            Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Class.forName("android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isPowerListenerSupported() {
        try {
            Class.forName("android.hardware.bydauto.power.BYDAutoPowerDevice");
            Class.forName("android.hardware.bydauto.power.AbsBYDAutoPowerListener");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean registerBodyworkListener(
            Context context, AccListener listener) {
        if (context == null) return false;

        try {
            log("Registering bodywork listener...");

            Class<?> deviceClass = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = deviceClass.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, context);

            if (device == null) {
                log("BYDAutoBodyworkDevice.getInstance returned null");
                return false;
            }

            log("Got bodywork device: " + device);

            Class<?> listenerClass = Class.forName("android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener");
            Method registerListener = deviceClass.getMethod("registerListener", listenerClass);

            registerListener.invoke(device, listener);

            log("Bodywork listener registered!");

            requestPowerLevelSnapshot(
                    currentAccObservationSequence(),
                    null,
                    "initial bodywork snapshot",
                    false);

            return true;

        } catch (Throwable e) {
            log("Bodywork registration failed: " + e.getMessage());
            return false;
        }
    }

    private static void startBodyworkListenerRegistrationSupervisor(
            Context context) {
        if (context == null) {
            return;
        }
        if (!isBodyworkSupported()) {
            log("BYD bodywork SDK classes (AbsBYDAutoBodyworkListener) not available on this ROM — skipping bodywork listener and enabling ACC fallback heartbeat");
            synchronized (bodyworkRegistrationLock) {
                bodyworkRegistered = true;
            }
            startAccStateHeartbeat();
            return;
        }
        synchronized (bodyworkRegistrationLock) {
            if (!running || bodyworkRegistered
                    || bodyworkRegistrationThread != null) {
                return;
            }
            long lifecycleGeneration =
                    ++bodyworkLifecycleGeneration;
            Thread worker = new Thread(
                    () -> runBodyworkRegistrationSupervisor(
                            context, lifecycleGeneration),
                    "BodyworkRegistration-" + lifecycleGeneration);
            worker.setDaemon(true);
            bodyworkRegistrationThread = worker;
            try {
                worker.start();
            } catch (Throwable failure) {
                bodyworkRegistrationThread = null;
                log("Bodywork registration supervisor could not start: "
                        + failure.getMessage());
                requestControlledDaemonRecovery(
                        "bodywork registration supervisor start failure");
            }
        }
    }

    private static void runBodyworkRegistrationSupervisor(
            Context context, long lifecycleGeneration) {
        long retryDelayMs = 1000L;
        try {
            while (isBodyworkLifecycleCurrent(
                    lifecycleGeneration)) {
                if (!isBodyworkSupported()) {
                    log("Bodywork SDK no longer available — stopping bodywork supervisor");
                    synchronized (bodyworkRegistrationLock) {
                        bodyworkRegistered = true;
                    }
                    startAccStateHeartbeat();
                    return;
                }
                final long attemptGeneration;
                synchronized (bodyworkRegistrationLock) {
                    attemptGeneration =
                            ++bodyworkAttemptGeneration;
                }
                ShellOwnership ownership = () ->
                        isBodyworkAttemptCurrent(
                                lifecycleGeneration,
                                attemptGeneration);
                BoundedCallResult<Boolean> result =
                        boundedBodyworkRegistrationLane.invoke(
                                "register attempt " + attemptGeneration,
                                5000L,
                                ownership,
                                () -> BodyworkListenerRegistrar.register(
                                        context,
                                        lifecycleGeneration,
                                        attemptGeneration),
                                null);
                if (!result.completed
                        && result.failure == null
                        && boundedBodyworkRegistrationLane
                            .hasTwoStuckCalls()) {
                    requestControlledDaemonRecovery(
                            "both bodywork registration lanes wedged");
                    return;
                }
                if (result.failure != null) {
                    log("Bodywork registration attempt "
                            + attemptGeneration + " failed: "
                            + result.failure.getMessage());
                }
                if (result.completed
                        && result.failure == null
                        && Boolean.TRUE.equals(result.value)) {
                    synchronized (bodyworkRegistrationLock) {
                        if (running
                                && lifecycleGeneration
                                    == bodyworkLifecycleGeneration
                                && attemptGeneration
                                    == bodyworkAttemptGeneration) {
                            activeBodyworkAttempt =
                                    attemptGeneration;
                            bodyworkRegistered = true;
                            startAccStateHeartbeat();
                            return;
                        }
                    }
                }
                if (!sleepForBodyworkRetry(
                        lifecycleGeneration, retryDelayMs)) {
                    return;
                }
                retryDelayMs = Math.min(
                        retryDelayMs * 2L, 60_000L);
            }
        } catch (Throwable t) {
            log("Bodywork registration supervisor encountered fatal error: " + t.getMessage());
        } finally {
            synchronized (bodyworkRegistrationLock) {
                if (bodyworkRegistrationThread
                        == Thread.currentThread()) {
                    bodyworkRegistrationThread = null;
                }
            }
        }
    }

    private static boolean sleepForBodyworkRetry(
            long lifecycleGeneration, long delayMs) {
        long deadline = android.os.SystemClock.elapsedRealtime()
                + Math.max(1L, delayMs);
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!isBodyworkLifecycleCurrent(
                    lifecycleGeneration)) {
                return false;
            }
            try {
                long remaining = deadline
                        - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0L) {
                    break;
                }
                Thread.sleep(Math.min(100L, remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isBodyworkLifecycleCurrent(
                lifecycleGeneration);
    }

    private static boolean isBodyworkLifecycleCurrent(
            long lifecycleGeneration) {
        synchronized (bodyworkRegistrationLock) {
            return running
                    && !bodyworkRegistered
                    && lifecycleGeneration
                        == bodyworkLifecycleGeneration;
        }
    }

    private static boolean isBodyworkAttemptCurrent(
            long lifecycleGeneration, long attemptGeneration) {
        synchronized (bodyworkRegistrationLock) {
            return running
                    && !bodyworkRegistered
                    && lifecycleGeneration
                        == bodyworkLifecycleGeneration
                    && attemptGeneration
                        == bodyworkAttemptGeneration;
        }
    }

    private static boolean isBodyworkCallbackCurrent(
            long lifecycleGeneration, long attemptGeneration) {
        synchronized (bodyworkRegistrationLock) {
            return running
                    && bodyworkRegistered
                    && lifecycleGeneration
                        == bodyworkLifecycleGeneration
                    && attemptGeneration
                        == activeBodyworkAttempt;
        }
    }

    private static void stopBodyworkRegistrationSupervisor() {
        Thread worker;
        synchronized (bodyworkRegistrationLock) {
            bodyworkLifecycleGeneration++;
            bodyworkAttemptGeneration++;
            activeBodyworkAttempt = -1L;
            bodyworkRegistered = false;
            worker = bodyworkRegistrationThread;
            bodyworkRegistrationThread = null;
        }
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
        }
    }

    private static void requestControlledDaemonRecovery(
            String reason) {
        if (!controlledRecoveryRequested.compareAndSet(
                false, true)) {
            return;
        }
        log("FATAL: requesting controlled daemon recovery: "
                + reason);
        try {
            Thread recovery = new Thread(() -> {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                android.os.Process.killProcess(
                        android.os.Process.myPid());
            }, "AccSentryControlledRecovery");
            recovery.setDaemon(false);
            recovery.start();
        } catch (Throwable failure) {
            log("Controlled recovery worker failed: "
                    + failure.getMessage());
            android.os.Process.killProcess(
                    android.os.Process.myPid());
        }
    }

    // ==================== OEM-PARITY POWER LISTENER ====================

    /** BYD power-ctl event id 0x99000037 = -1728053193, value 0=ACC OFF,
     *  value 1=ACC ON. Source: oem bk/C1478c.java:71-75. */
    private static final int POWER_CTL_EVENT_ACC = -1728053193;

    /** Register oem-style BYDAutoPowerDevice.onPowerCtlStatusChanged
     *  listener via reflection. Runs in parallel with the bodywork
     *  listener; whichever fires first wins. */
    private static boolean registerPowerListener(
            Context context, OemStylePowerListener listener) {
        if (context == null) return false;
        try {
            log("Registering BYDAutoPowerDevice listener (oem-parity)...");

            Class<?> deviceClass = Class.forName(
                "android.hardware.bydauto.power.BYDAutoPowerDevice");
            Method getInstance = deviceClass.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, context);
            if (device == null) {
                log("BYDAutoPowerDevice.getInstance returned null");
                return false;
            }

            Class<?> listenerClass = Class.forName(
                "android.hardware.bydauto.power.AbsBYDAutoPowerListener");
            Method registerListener = deviceClass.getMethod(
                "registerListener", listenerClass);

            // SDK stub for AbsBYDAutoPowerListener is in our tree at
            // android/hardware/bydauto/power/AbsBYDAutoPowerListener.java
            // — at runtime BYD's bmmcamera.jar provides the real class.
            // Subclass directly; if the runtime class signature differs
            // we'll catch via the outer try/catch.
            registerListener.invoke(device, listener);
            log("BYDAutoPowerDevice listener registered (oem-parity)");
            return true;
        } catch (ClassNotFoundException cnf) {
            log("BYDAutoPowerDevice classes not on this ROM: " + cnf.getMessage());
            return false;
        } catch (Throwable e) {
            log("registerPowerListener failed: " + e.getMessage());
            return false;
        }
    }

    private static void startPowerListenerRegistrationSupervisor(
            Context context) {
        if (context == null) {
            return;
        }
        if (!isPowerListenerSupported()) {
            log("BYD power SDK classes (AbsBYDAutoPowerListener) not available on this ROM — skipping power listener");
            return;
        }
        synchronized (powerListenerRegistrationLock) {
            if (!running || powerListenerRegistered
                    || (powerListenerRegistrationThread != null
                        && powerListenerRegistrationThread.isAlive())) {
                return;
            }
            long lifecycleGeneration =
                    ++powerListenerLifecycleGeneration;
            startPowerListenerRegistrationWorkerLocked(
                    context, lifecycleGeneration);
        }
    }

    private static void startPowerListenerRegistrationWorkerLocked(
            Context context, long lifecycleGeneration) {
        Thread worker = new Thread(
                () -> runPowerListenerRegistrationSupervisor(
                        context, lifecycleGeneration),
                "PowerListenerRegistration-" + lifecycleGeneration);
        worker.setDaemon(true);
        powerListenerRegistrationThread = worker;
        try {
            worker.start();
        } catch (Throwable startFailure) {
            powerListenerRegistrationThread = null;
            log("Power-listener registration supervisor could not start: "
                    + startFailure.getMessage());
            schedulePowerListenerRegistrationRestart(
                    context, lifecycleGeneration);
        }
    }

    private static void schedulePowerListenerRegistrationRestart(
            Context context, long lifecycleGeneration) {
        android.os.Handler handler = statusHandler;
        if (handler == null) {
            return;
        }
        try {
            handler.postDelayed(() -> {
                synchronized (powerListenerRegistrationLock) {
                    if (!running
                            || powerListenerRegistered
                            || lifecycleGeneration
                                != powerListenerLifecycleGeneration
                            || powerListenerRegistrationThread != null) {
                        return;
                    }
                    startPowerListenerRegistrationWorkerLocked(
                            context, lifecycleGeneration);
                }
            }, 1000L);
        } catch (Throwable failure) {
            log("Power-listener supervisor restart could not be scheduled: "
                    + failure.getMessage());
        }
    }

    private static void runPowerListenerRegistrationSupervisor(
            Context context, long lifecycleGeneration) {
        long retryDelayMs = 1000L;
        try {
            while (isPowerListenerLifecycleCurrent(
                    lifecycleGeneration)) {
                if (!isPowerListenerSupported()) {
                    log("Power SDK no longer available — stopping power supervisor");
                    return;
                }
                final long attemptGeneration;
                synchronized (powerListenerRegistrationLock) {
                    if (!running
                            || lifecycleGeneration
                                != powerListenerLifecycleGeneration
                            || powerListenerRegistered) {
                        return;
                    }
                    attemptGeneration =
                            ++powerListenerAttemptGeneration;
                }

                ShellOwnership ownership = () ->
                        isPowerListenerAttemptCurrent(
                                lifecycleGeneration,
                                attemptGeneration);
                BoundedCallResult<Boolean> result =
                        boundedPowerListenerRegistrationLane.invoke(
                                "register attempt "
                                        + attemptGeneration,
                                5000L,
                                ownership,
                                () -> PowerListenerRegistrar.register(
                                        context,
                                        lifecycleGeneration,
                                        attemptGeneration),
                                null);
                if (!result.completed
                        && result.failure == null
                        && boundedPowerListenerRegistrationLane
                            .hasTwoStuckCalls()) {
                    requestControlledDaemonRecovery(
                            "both power-listener registration lanes wedged");
                    return;
                }
                if (result.failure != null) {
                    log("Power-listener registration attempt "
                            + attemptGeneration + " failed: "
                            + result.failure.getMessage());
                }
                if (result.completed
                        && result.failure == null
                        && Boolean.TRUE.equals(result.value)) {
                    synchronized (powerListenerRegistrationLock) {
                        if (running
                                && lifecycleGeneration
                                    == powerListenerLifecycleGeneration
                                && attemptGeneration
                                    == powerListenerAttemptGeneration) {
                            activePowerListenerAttempt =
                                    attemptGeneration;
                            powerListenerRegistered = true;
                            log("Power-listener registration supervisor "
                                    + "succeeded on attempt "
                                    + attemptGeneration);
                            return;
                        }
                    }
                }

                if (!sleepForPowerListenerRetry(
                        lifecycleGeneration, retryDelayMs)) {
                    return;
                }
                retryDelayMs = Math.min(
                        retryDelayMs * 2L, 60_000L);
            }
        } catch (Throwable t) {
            log("Power-listener registration supervisor encountered fatal error: " + t.getMessage());
        } finally {
            boolean restart = false;
            synchronized (powerListenerRegistrationLock) {
                if (powerListenerRegistrationThread
                        == Thread.currentThread()) {
                    powerListenerRegistrationThread = null;
                    restart = running
                            && !powerListenerRegistered
                            && lifecycleGeneration
                                == powerListenerLifecycleGeneration;
                }
            }
            if (restart) {
                schedulePowerListenerRegistrationRestart(
                        context, lifecycleGeneration);
            }
        }
    }

    private static boolean sleepForPowerListenerRetry(
            long lifecycleGeneration, long delayMs) {
        long deadline = android.os.SystemClock.elapsedRealtime()
                + Math.max(1L, delayMs);
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!isPowerListenerLifecycleCurrent(
                    lifecycleGeneration)) {
                return false;
            }
            long remaining = deadline
                    - android.os.SystemClock.elapsedRealtime();
            try {
                Thread.sleep(Math.min(remaining, 100L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isPowerListenerLifecycleCurrent(
                lifecycleGeneration);
    }

    private static boolean isPowerListenerLifecycleCurrent(
            long lifecycleGeneration) {
        synchronized (powerListenerRegistrationLock) {
            return running
                    && lifecycleGeneration
                        == powerListenerLifecycleGeneration
                    && !powerListenerRegistered;
        }
    }

    private static boolean isPowerListenerAttemptCurrent(
            long lifecycleGeneration, long attemptGeneration) {
        synchronized (powerListenerRegistrationLock) {
            return running
                    && !powerListenerRegistered
                    && lifecycleGeneration
                        == powerListenerLifecycleGeneration
                    && attemptGeneration
                        == powerListenerAttemptGeneration;
        }
    }

    private static boolean isPowerListenerCallbackCurrent(
            long lifecycleGeneration, long attemptGeneration) {
        synchronized (powerListenerRegistrationLock) {
            return running
                    && powerListenerRegistered
                    && lifecycleGeneration
                        == powerListenerLifecycleGeneration
                    && attemptGeneration
                        == activePowerListenerAttempt;
        }
    }

    private static void stopPowerListenerRegistrationSupervisor() {
        Thread worker;
        synchronized (powerListenerRegistrationLock) {
            powerListenerLifecycleGeneration++;
            powerListenerAttemptGeneration++;
            activePowerListenerAttempt = -1L;
            powerListenerRegistered = false;
            worker = powerListenerRegistrationThread;
            powerListenerRegistrationThread = null;
        }
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
        }
    }

    private static final class PowerListenerRegistrar {
        static boolean register(Context context, long lifecycleGeneration, long attemptGeneration) {
            OemStylePowerListener listener = new OemStylePowerListener(lifecycleGeneration, attemptGeneration);
            return registerPowerListener(context, listener);
        }
    }

    private static final class BodyworkListenerRegistrar {
        static boolean register(Context context, long lifecycleGeneration, long attemptGeneration) {
            AccListener listener = new AccListener(lifecycleGeneration, attemptGeneration);
            return registerBodyworkListener(context, listener);
        }
    }

    /** Concrete subclass of AbsBYDAutoPowerListener that drives the
     *  same enterSentryMode/exitSentryMode as the bodywork listener.
     *  Mirrors oem bk/C1478c.java:65-77. The bytecode is loaded by the
     *  daemon process against BYD's runtime class via the SDK stub. */
    private static class OemStylePowerListener
        extends AbsBYDAutoPowerListener {
        private final long lifecycleGeneration;
        private final long attemptGeneration;

        OemStylePowerListener(
                long lifecycleGeneration, long attemptGeneration) {
            this.lifecycleGeneration = lifecycleGeneration;
            this.attemptGeneration = attemptGeneration;
        }

        @Override
        public void onPowerCtlStatusChanged(int eventId, int value) {
            if (!isPowerListenerCallbackCurrent(
                    lifecycleGeneration, attemptGeneration)) {
                return;
            }
            if (eventId == POWER_CTL_EVENT_ACC) {
                log(">>> POWER CTL: " + (value == 0 ? "ACC OFF" : "ACC ON")
                    + " (event=0x" + Integer.toHexString(eventId)
                    + ", value=" + value + ")");
                if (value == 0 || value == 1) {
                    int level = value == 0 ? POWER_LEVEL_OFF : POWER_LEVEL_ON;
                    applyValidatedPowerEvent(level, "power-ctl listener");
                } else {
                    log("Ignoring invalid power-ctl ACC value " + value);
                }
            }
        }
    }

    private static long nextAccObservationSequence() {
        synchronized (accObservationLock) {
            return accCallbackSequence.incrementAndGet();
        }
    }

    private static long currentAccObservationSequence() {
        synchronized (accObservationLock) {
            return accCallbackSequence.get();
        }
    }

    private static boolean applyObservedPowerLevelIfCurrent(
            int level, String source, long observationSequence) {
        synchronized (accObservationLock) {
            long currentSequence = accCallbackSequence.get();
            if (observationSequence != currentSequence) {
                log("Discarding stale " + source + " observation "
                        + powerLevelToString(level) + " (sequence="
                        + observationSequence + ", current="
                        + currentSequence + ")");
                return false;
            }
            return applyObservedPowerLevelLocked(
                    level, source, observationSequence);
        }
    }

    private static void applyValidatedPowerEvent(
            int deliveredLevel, String source) {
        long observationSequence = nextAccObservationSequence();
        requestPowerLevelSnapshot(
                observationSequence, deliveredLevel, source, false);
    }

    private static boolean applyObservedPowerLevelLocked(
            int level, String source, long observationSequence) {
        log("ACC observation from " + source + ": "
                + powerLevelToString(level) + " (sequence="
                + observationSequence + ")");
        handlePowerLevelChanged(level);
        return true;
    }

    private static void handlePowerLevelChanged(int level) {
        log(">>> POWER LEVEL: " + powerLevelToString(level) + " (was: "
                + powerLevelToString(lastPowerLevel) + ")");

        // Reject sentinel readings without changing the prior definitive state.
        if (level < 0 || level > 3) {
            log("Sentinel power level " + powerLevelToString(level)
                    + " — ignoring (lastPowerLevel stays "
                    + powerLevelToString(lastPowerLevel) + ")");
            return;
        }

        if (level == POWER_LEVEL_OFF && lastPowerLevel != POWER_LEVEL_OFF) {
            log("ACC OFF detected");
            enterSentryMode();
        } else if (level >= POWER_LEVEL_ON && lastPowerLevel < POWER_LEVEL_ON) {
            log("ACC ON detected");
            exitSentryMode();
        } else if (level == POWER_LEVEL_ACC && lastPowerLevel >= POWER_LEVEL_ON) {
            log("ACC level dropped from ON to ACC — treating as ACC OFF "
                    + "(BYD app shutdown)");
            enterSentryMode();
        }

        lastPowerLevel = level;
    }

    private static class AccListener extends AbsBYDAutoBodyworkListener {
        private final long lifecycleGeneration;
        private final long attemptGeneration;

        AccListener(
                long lifecycleGeneration, long attemptGeneration) {
            this.lifecycleGeneration = lifecycleGeneration;
            this.attemptGeneration = attemptGeneration;
        }

        @Override
        public void onPowerLevelChanged(int level) {
            if (!isBodyworkCallbackCurrent(
                    lifecycleGeneration, attemptGeneration)) {
                return;
            }
            applyValidatedPowerEvent(level, "bodywork listener");
        }

        @Override
        public void onAutoSystemStateChanged(int state) {
            if (!isBodyworkCallbackCurrent(
                    lifecycleGeneration, attemptGeneration)) {
                return;
            }
            log("System state: " + state);
        }

        @Override
        public void onBatteryVoltageLevelChanged(int level) {
            if (!isBodyworkCallbackCurrent(
                    lifecycleGeneration, attemptGeneration)) {
                return;
            }
            // Discrete level callback (0=LOW, 1=NORMAL)
            // Actual voltage monitoring is done via polling in manageMcuPowerState()
            String levelName = (level == 0) ? "LOW" : (level == 1) ? "NORMAL" : "INVALID";
            log("Car battery level: " + levelName);
            
            // Emergency action on LOW level.
            // GATE (onOnly): this HAL-delivered low-battery callback is event-driven and
            // independent of the SentrySetup worker that G1 gates — AccListener is
            // registered unconditionally at daemon init and inSentryMode stays true for
            // the whole park, so without this guard forceMcuWakeUp() would fire in onOnly
            // and (with the default Keep-USB toggle ON) force the AP awake via
            // performSystemWakeUp(), defeating "let the head unit sleep". In onOnly the
            // daemon deliberately runs no post-OFF voltage management (G1 skipped
            // initVehicleDataMonitor / BatteryVoltageMonitorV2), so this emergency wake is
            // suppressed too — the system is meant to sleep and rely on the vehicle's own
            // BMS for low-SoC protection, consistent with the mode's contract.
            if (level == 0) {
                SentryTransitionState transition = latestSentryTransition;
                if (transition.sentryMode) {
                    log("LOW BATTERY - scheduling authoritative surveillance stop");
                    lowBatteryStopReconciler.requestReapply(
                            transition.generation, true);

                    if (!transition.vehicleOnOnly) {
                        log("CRITICAL: Battery level LOW - scheduling emergency wake");
                        lowBatteryWakeReconciler.requestReapply(
                                transition.generation, true);
                    }
                }
            }
        }
    }

    private static boolean isLowBatteryStopCurrent(long generation) {
        SentryTransitionState latest = latestSentryTransition;
        return latest.generation == generation
                && latest.sentryMode
                && lowBatteryStopReconciler.isDesired(generation, true);
    }

    private static boolean isLowBatteryWakeCurrent(long generation) {
        SentryTransitionState latest = latestSentryTransition;
        return latest.generation == generation
                && latest.sentryMode
                && !latest.vehicleOnOnly
                && lowBatteryWakeReconciler.isDesired(generation, true);
    }

    private static boolean sleepForLowBatteryWake(
            long generation, long delayMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + delayMs;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!isLowBatteryWakeCurrent(generation)) {
                return false;
            }
            long remaining =
                    deadline - android.os.SystemClock.elapsedRealtime();
            try {
                Thread.sleep(Math.min(remaining, 50L));
            } catch (InterruptedException interrupted) {
                Thread.interrupted();
                if (!isLowBatteryWakeCurrent(generation)) {
                    return false;
                }
            }
        }
        return isLowBatteryWakeCurrent(generation);
    }

    private static boolean applyLowBatteryWake(long generation) {
        if (!isLowBatteryWakeCurrent(generation)) {
            return true;
        }
        return runBoundedHardwareBoolean(
                "Low-battery emergency wake",
                () -> isLowBatteryWakeCurrent(generation),
                () -> {
                    if (!isLowBatteryWakeCurrent(generation)) {
                        return true;
                    }
                    lastMcuWakeTime = System.currentTimeMillis();
                    boolean success = true;
                    if (isKeepUsbPowerOnAccOff()) {
                        success = performSystemWakeUp();
                    }
                    if (!isLowBatteryWakeCurrent(generation)) {
                        return true;
                    }
                    boolean mcuWoke = wakeUpMcu();
                    if (!sleepForLowBatteryWake(generation, 500L)) {
                        return true;
                    }
                    mcuWoke |= wakeUpMcu();
                    return success && mcuWoke;
                },
                AccSentryDaemon::requestPanelForLatestTransition);
    }

    private static void requestLatestSurveillanceIntentReconciliation() {
        SentryTransitionState latest = latestSentryTransition;
        notifyAccState(latest.generation, latest.sentryMode, true);
        surveillanceIntentReconciler.requestReapply(
                latest.generation, latest.sentryMode);
    }

    private static boolean isSurveillanceIntentRequestCurrent(
            long generation, boolean expectedParked) {
        SentryTransitionState latest = latestSentryTransition;
        return latest.generation == generation
                && latest.sentryMode == expectedParked
                && surveillanceIntentReconciler.isDesired(
                        generation, expectedParked);
    }

    private static Boolean desiredSurveillanceState(boolean parked) {
        if (!parked) {
            return false;
        }
        try {
            if (!com.overdrive.app.config.UnifiedConfigManager
                    .isSurveillanceEnabled()) {
                return false;
            }
        } catch (Throwable failure) {
            log("Surveillance intent config unavailable: "
                    + failure.getMessage());
            return null;
        }
        try {
            com.overdrive.app.surveillance.SafeLocationManager safeLocation =
                    com.overdrive.app.surveillance.SafeLocationManager
                            .getInstance();
            if (safeLocation.isFeatureEnabled()
                    && safeLocation.isInSafeZone()) {
                return false;
            }
        } catch (Throwable failure) {
            log("Surveillance intent safe-zone check failed; preserving "
                    + "normal fail-open behavior: " + failure.getMessage());
        }
        try {
            com.overdrive.app.surveillance.SurveillanceSchedule schedule =
                    com.overdrive.app.config.UnifiedConfigManager
                            .getSurveillanceSchedule();
            if (schedule != null
                    && schedule.isEnabled()
                    && !schedule.isActiveNow()) {
                return false;
            }
        } catch (Throwable failure) {
            log("Surveillance intent schedule check failed; preserving "
                    + "normal fail-open behavior: " + failure.getMessage());
        }
        return true;
    }

    private static boolean applySurveillanceIntentReconciliation(
            long generation, boolean expectedParked) {
        if (!isSurveillanceIntentRequestCurrent(
                generation, expectedParked)) {
            requestLatestSurveillanceIntentReconciliation();
            return true;
        }
        Boolean desired = desiredSurveillanceState(expectedParked);
        if (desired == null) {
            return false;
        }

        try {
            JSONObject statusCommand = new JSONObject();
            statusCommand.put("command", "STATUS");
            JSONObject status = sendSurveillanceCommandRaw(statusCommand);
            if (!isSurveillanceIntentRequestCurrent(
                    generation, expectedParked)) {
                requestLatestSurveillanceIntentReconciliation();
                return true;
            }
            if (status == null
                    || !status.optBoolean("success", false)) {
                return false;
            }
            boolean active = status.optBoolean("active", false);
            if (active == desired) {
                surveillanceEnabled = active;
                return true;
            }

            if (!isSurveillanceIntentRequestCurrent(
                    generation, expectedParked)) {
                requestLatestSurveillanceIntentReconciliation();
                return true;
            }
            JSONObject command = new JSONObject();
            command.put("command", "SET_CONFIG");
            JSONObject config = new JSONObject();
            if (desired) {
                config.put("enabled", true);
            } else {
                config.put("stopSurveillance", true);
            }
            command.put("config", config);
            JSONObject response = sendSurveillanceCommandRaw(command);
            if (!isSurveillanceIntentRequestCurrent(
                    generation, expectedParked)) {
                requestLatestSurveillanceIntentReconciliation();
                return true;
            }
            if (response == null
                    || !response.optBoolean("success", false)) {
                return false;
            }

            JSONObject confirmation =
                    sendSurveillanceCommandRaw(statusCommand);
            if (!isSurveillanceIntentRequestCurrent(
                    generation, expectedParked)) {
                requestLatestSurveillanceIntentReconciliation();
                return true;
            }
            if (confirmation == null
                    || !confirmation.optBoolean("success", false)
                    || confirmation.optBoolean("active", !desired)
                            != desired) {
                return false;
            }
            surveillanceEnabled = desired;
            return true;
        } catch (Throwable failure) {
            log("Surveillance intent reconciliation failed: "
                    + failure.getMessage());
            return false;
        }
    }

    private static boolean applyLowBatteryStop(long generation) {
        if (!isLowBatteryStopCurrent(generation)) {
            return true;
        }
        try {
            JSONObject statusCommand = new JSONObject();
            statusCommand.put("command", "STATUS");
            JSONObject status = sendSurveillanceCommandRaw(statusCommand);
            if (!isLowBatteryStopCurrent(generation)) {
                return true;
            }
            if (status == null
                    || !status.optBoolean("success", false)) {
                log("LOW BATTERY: STATUS failed; retrying until confirmed");
                return false;
            }
            if (!status.optBoolean("active", false)) {
                surveillanceEnabled = false;
                log("LOW BATTERY: surveillance confirmed stopped");
                return true;
            }

            log("LOW BATTERY: active surveillance confirmed; disabling");
            JSONObject stopCommand = new JSONObject();
            stopCommand.put("command", "SET_CONFIG");
            JSONObject config = new JSONObject();
            config.put("stopSurveillance", true);
            stopCommand.put("config", config);
            JSONObject stopResponse =
                    sendSurveillanceCommandRaw(stopCommand);
            if (!isLowBatteryStopCurrent(generation)) {
                requestLatestSurveillanceIntentReconciliation();
                return true;
            }
            if (stopResponse == null
                    || !stopResponse.optBoolean("success", false)) {
                log("LOW BATTERY: STOP was not acknowledged; retrying");
                return false;
            }

            JSONObject confirmation =
                    sendSurveillanceCommandRaw(statusCommand);
            if (!isLowBatteryStopCurrent(generation)) {
                requestLatestSurveillanceIntentReconciliation();
                return true;
            }
            if (confirmation == null
                    || !confirmation.optBoolean("success", false)
                    || confirmation.optBoolean("active", true)) {
                log("LOW BATTERY: STOP not yet confirmed; retrying");
                return false;
            }
            surveillanceEnabled = false;
            log("LOW BATTERY: surveillance stop confirmed");
            return true;
        } catch (Throwable t) {
            if (!isLowBatteryStopCurrent(generation)) {
                requestLatestSurveillanceIntentReconciliation();
                return true;
            }
            log("LOW BATTERY: stop reconciliation failed: "
                    + t.getMessage() + "; retrying");
            return false;
        }
    }

    // ==================== VOLTAGE HYSTERESIS STATE (REPLACED) ====================
    //
    // Replaced by BatteryVoltageMonitorV2 (12.0/12.5 V thresholds, 15 min
    // sleep-defer). The local copy is kept commented as a reference only.
    //
    // private static volatile boolean isVoltageChargingCycle = false;
    // private static final double LOW_VOLTAGE_THRESHOLD = 12.1;      // Wake Trigger (Volts)
    // private static final double HEALTHY_VOLTAGE_THRESHOLD = 12.8;  // Sleep Trigger (Volts)

    // VehicleDataMonitor listener for voltage-based MCU control
    private static VehicleDataListener vehicleDataListener = null;
    private static final Object monitorIoLock = new Object();
    private static final Object batteryVoltageScheduleLock = new Object();
    private static final java.util.concurrent.ScheduledExecutorService
            batteryVoltageScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "BatteryV2-Schedule");
                thread.setDaemon(true);
                return thread;
            });
    private static java.util.concurrent.ScheduledFuture<?> batteryVoltageFuture;
    private static long batteryVoltageFutureGeneration = -1L;
    private static long batteryVoltageRetryDelayMs = 1000L;
    private static final long BATTERY_VOLTAGE_START_DELAY_MS = 35_000L;
    private static final long BATTERY_VOLTAGE_RETRY_MAX_MS = 30_000L;
    private static long batteryVoltageMonitorOwnerGeneration = -1L;
    private static long vehicleDataMonitorOwnerGeneration = -1L;
    private static long socMonitorOwnerGeneration = -1L;
    private static final java.util.concurrent.ScheduledExecutorService
            sentryReconcilerRetryScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "SentryReconcileRetry");
                thread.setDaemon(true);
                return thread;
            });
    private static final java.util.Set<LatestBooleanReconciler>
            fallbackReconcilerRetries =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<
                            LatestBooleanReconciler, Boolean>());

    private static String powerLevelToString(int level) {
        switch (level) {
            case POWER_LEVEL_OFF: return "OFF";
            case POWER_LEVEL_ACC: return "ACC";
            case POWER_LEVEL_ON: return "ON";
            case POWER_LEVEL_OK: return "OK";
            default: return "UNKNOWN(" + level + ")";
        }
    }

    // ==================== SENTRY MODE ====================

    private static final class SentryTransitionState {
        final long generation;
        final boolean sentryMode;
        final boolean vehicleOnOnly;

        SentryTransitionState(long generation, boolean sentryMode, boolean vehicleOnOnly) {
            this.generation = generation;
            this.sentryMode = sentryMode;
            this.vehicleOnOnly = vehicleOnOnly;
        }

        boolean keepAwakeEnabled() {
            return sentryMode && !vehicleOnOnly;
        }
    }

    private static final class ReconcileRequest {
        final long generation;
        final boolean state;
        final long revision;

        ReconcileRequest(long generation, boolean state, long revision) {
            this.generation = generation;
            this.state = state;
            this.revision = revision;
        }
    }

    /**
     * One running worker and one coalesced latest request per side-effect family.
     * I/O never runs under {@link #lock}; if desired state changes during an
     * uninterruptible operation, the loop applies the newest full state next.
     */
    private abstract static class LatestBooleanReconciler {
        private static final long INITIAL_RETRY_DELAY_MS = 250L;
        private static final long MAX_RETRY_DELAY_MS = 5000L;

        private final Object lock = new Object();
        private final String workerName;
        private long desiredGeneration = -1L;
        private boolean desiredState;
        private long desiredRevision;
        private long appliedGeneration = -1L;
        private boolean appliedState;
        private boolean appliedValid;
        private long appliedRevision = -1L;
        private boolean workerRunning;
        private Thread activeWorker;
        private boolean startRetryScheduled;
        private long startRetryDelayMs = INITIAL_RETRY_DELAY_MS;

        LatestBooleanReconciler(String workerName) {
            this.workerName = workerName;
        }

        final void request(long generation, boolean state) {
            requestInternal(generation, state, false);
        }

        final void requestReapply(long generation, boolean state) {
            requestInternal(generation, state, true);
        }

        private void requestInternal(long generation, boolean state, boolean force) {
            Throwable startFailure = null;
            synchronized (lock) {
                if (generation < desiredGeneration) {
                    return;
                }
                if (!force
                        && generation == desiredGeneration
                        && state == desiredState
                        && (workerRunning
                            || isDesiredAppliedLocked())) {
                    return;
                }
                desiredGeneration = generation;
                desiredState = state;
                desiredRevision++;
                if (!workerRunning) {
                    startFailure = startWorkerLocked();
                }
                lock.notifyAll();
            }
            if (startFailure != null) {
                log(workerName + " could not start: "
                        + startFailure.getMessage());
            }
        }

        private boolean isDesiredAppliedLocked() {
            return appliedValid
                    && appliedGeneration == desiredGeneration
                    && appliedState == desiredState
                    && appliedRevision == desiredRevision;
        }

        private Throwable startWorkerLocked() {
            workerRunning = true;
            try {
                Thread worker = new Thread(this::runLoop, workerName);
                worker.setDaemon(true);
                activeWorker = worker;
                worker.start();
                startRetryDelayMs = INITIAL_RETRY_DELAY_MS;
                fallbackReconcilerRetries.remove(this);
                return null;
            } catch (Throwable failure) {
                activeWorker = null;
                workerRunning = false;
                scheduleStartRetryLocked();
                return failure;
            }
        }

        private void scheduleStartRetryLocked() {
            if (startRetryScheduled
                    || workerRunning
                    || isDesiredAppliedLocked()) {
                return;
            }

            long delayMs = startRetryDelayMs;
            startRetryDelayMs = Math.min(
                    startRetryDelayMs * 2L, MAX_RETRY_DELAY_MS);
            startRetryScheduled = true;
            try {
                sentryReconcilerRetryScheduler.schedule(
                        this::retryWorkerStart,
                        delayMs,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable scheduleFailure) {
                startRetryScheduled = false;
                registerFallbackReconcilerRetry(this, delayMs);
                log(workerName + " could not schedule worker retry: "
                        + scheduleFailure.getMessage());
            }
        }

        private void retryWorkerStart() {
            Throwable startFailure = null;
            synchronized (lock) {
                startRetryScheduled = false;
                if (!workerRunning && !isDesiredAppliedLocked()) {
                    startFailure = startWorkerLocked();
                }
                lock.notifyAll();
            }
            if (startFailure != null) {
                log(workerName + " retry worker could not start: "
                        + startFailure.getMessage());
            }
        }

        private void retryWorkerStartFromFallback() {
            fallbackReconcilerRetries.remove(this);
            retryWorkerStart();
        }

        final ReconcileRequest desiredSnapshot() {
            synchronized (lock) {
                return new ReconcileRequest(
                        desiredGeneration, desiredState, desiredRevision);
            }
        }

        final boolean isDesired(long generation, boolean state) {
            synchronized (lock) {
                return desiredGeneration == generation && desiredState == state;
            }
        }

        final boolean awaitApplied(
                long generation, boolean state, long timeoutMs) {
            long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
            synchronized (lock) {
                while (desiredGeneration == generation && desiredState == state) {
                    if (appliedValid
                            && appliedGeneration == generation
                            && appliedState == state
                            && appliedRevision == desiredRevision) {
                        return true;
                    }
                    long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                    if (remaining <= 0L) {
                        return false;
                    }
                    try {
                        lock.wait(remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return false;
            }
        }

        private void runLoop() {
            long applyRetryDelayMs = INITIAL_RETRY_DELAY_MS;
            try {
                while (true) {
                    ReconcileRequest request;
                    synchronized (lock) {
                        request = new ReconcileRequest(
                                desiredGeneration, desiredState, desiredRevision);
                    }

                    boolean success = false;
                    try {
                        success = apply(request.generation, request.state);
                    } catch (Throwable failure) {
                        log(workerName + " reconciliation failed: "
                                + failure.getMessage());
                    }

                    synchronized (lock) {
                        if (success) {
                            appliedGeneration = request.generation;
                            appliedState = request.state;
                            appliedRevision = request.revision;
                            appliedValid = true;
                        } else if (appliedValid
                                && appliedGeneration == request.generation
                                && appliedState == request.state) {
                            appliedValid = false;
                        }
                        lock.notifyAll();

                        if (desiredRevision != request.revision) {
                            applyRetryDelayMs = INITIAL_RETRY_DELAY_MS;
                            continue;
                        }
                        if (success) {
                            activeWorker = null;
                            workerRunning = false;
                            return;
                        }

                        long delayMs = applyRetryDelayMs;
                        applyRetryDelayMs = Math.min(
                                applyRetryDelayMs * 2L, MAX_RETRY_DELAY_MS);
                        try {
                            lock.wait(delayMs);
                        } catch (InterruptedException interrupted) {
                            // Interruption is only a wake-up signal here. The latest
                            // desired revision remains authoritative.
                            Thread.interrupted();
                        }
                        if (desiredRevision != request.revision) {
                            applyRetryDelayMs = INITIAL_RETRY_DELAY_MS;
                        }
                    }
                }
            } finally {
                synchronized (lock) {
                    if (activeWorker == Thread.currentThread()) {
                        activeWorker = null;
                        workerRunning = false;
                        scheduleStartRetryLocked();
                        lock.notifyAll();
                    }
                }
            }
        }

        protected abstract boolean apply(long generation, boolean state) throws Exception;
    }

    private static void registerFallbackReconcilerRetry(
            LatestBooleanReconciler reconciler, long delayMs) {
        fallbackReconcilerRetries.add(reconciler);
        android.os.Handler handler = statusHandler;
        if (handler == null) {
            return;
        }
        try {
            handler.postDelayed(
                    AccSentryDaemon::pumpFallbackReconcilerRetries,
                    Math.max(1L, delayMs));
        } catch (Throwable ignored) {}
    }

    private static void pumpFallbackReconcilerRetries() {
        for (LatestBooleanReconciler reconciler :
                fallbackReconcilerRetries.toArray(
                        new LatestBooleanReconciler[0])) {
            if (reconciler != null) {
                reconciler.retryWorkerStartFromFallback();
            }
        }
    }

    private static final LatestBooleanReconciler peripheralPowerReconciler =
            new LatestBooleanReconciler("SentryRails") {
                @Override
                protected boolean apply(long generation, boolean enabled) {
                    return applyPeripheralPowerBatch(generation, enabled);
                }
            };

    private static final LatestBooleanReconciler sentrySetupReconciler =
            new LatestBooleanReconciler("SentrySetup") {
                @Override
                protected boolean apply(long generation, boolean enabled) {
                    return applySentrySetupState(generation, enabled);
                }
            };

    private static final LatestBooleanReconciler lowBatteryStopReconciler =
            new LatestBooleanReconciler("LowBatteryStop") {
                @Override
                protected boolean apply(long generation, boolean active) {
                    return !active || applyLowBatteryStop(generation);
                }
            };

    private static final LatestBooleanReconciler lowBatteryWakeReconciler =
            new LatestBooleanReconciler("LowBatteryWake") {
                @Override
                protected boolean apply(long generation, boolean active) {
                    return !active || applyLowBatteryWake(generation);
                }
            };

    private static final LatestBooleanReconciler
            surveillanceIntentReconciler =
            new LatestBooleanReconciler("SurveillanceIntent") {
                @Override
                protected boolean apply(
                        long generation, boolean expectedParked) {
                    return applySurveillanceIntentReconciliation(
                            generation, expectedParked);
                }
            };

    private static final LatestBooleanReconciler monitorReconciler =
            new LatestBooleanReconciler("SentryMonitors") {
                @Override
                protected boolean apply(long generation, boolean enabled) {
                    return applyMonitorState(generation, enabled);
                }
            };

    private static final LatestBooleanReconciler
            parkReaperTokenReconciler =
            new LatestBooleanReconciler("ParkReaperToken") {
                @Override
                protected boolean apply(long generation, boolean active) {
                    return publishParkReaperTransition(
                            generation, active);
                }
            };

    private static final LatestBooleanReconciler parkReaperReconciler =
            new LatestBooleanReconciler("ParkReaperState") {
                @Override
                protected boolean apply(long generation, boolean active) {
                    return active
                            ? launchParkReaper(generation)
                            : cancelParkReaperForAccOn(generation);
                }
            };

    private static final LatestBooleanReconciler keepAliveReconciler =
            new LatestBooleanReconciler("KeepAliveState") {
                @Override
                protected boolean apply(long generation, boolean enabled) {
                    return enabled
                            ? startSystemKeepAlive(generation)
                            : stopSystemKeepAlive(generation);
                }
            };

    private static final LatestBooleanReconciler telegramReconciler =
            new LatestBooleanReconciler("TelegramState") {
                @Override
                protected boolean apply(long generation, boolean enabled) {
                    return enabled
                            ? startTelegramDaemonIfEnabled(generation)
                            : stopTelegramDaemonIfAutoStarted(generation);
                }
            };

    private static final LatestBooleanReconciler panelReconciler =
            new LatestBooleanReconciler("PanelState") {
                @Override
                protected boolean apply(long generation, boolean panelOn) {
                    return applyPanelStateBounded(generation, panelOn);
                }
            };

    private static final LatestBooleanReconciler accNotifyReconciler =
            new LatestBooleanReconciler("AccStateIPC") {
                @Override
                protected boolean apply(long generation, boolean accOff) {
                    return applyAccStateNotification(generation, accOff);
                }
            };

    /**
     * Atomically accepts a real ACC transition and invalidates work owned by
     * every older transition. Duplicate reports from the two HAL listeners do
     * not advance the generation, so they cannot cancel the active transition.
     */
    private static long beginSentryTransition(boolean entering, boolean vehicleOnOnly) {
        final long generation;
        synchronized (sentryTransitionLock) {
            if (inSentryMode == entering) {
                SentryTransitionState latest = latestSentryTransition;
                parkReaperTokenReconciler.requestReapply(
                        latest.generation,
                        latest.sentryMode && latest.vehicleOnOnly);
                return -1L;
            }

            inSentryMode = entering;
            if (!entering) {
                surveillanceEnabled = false;
            }
            generation = sentryTransitionGeneration.incrementAndGet();
            latestSentryTransition = new SentryTransitionState(
                    generation, entering, entering && vehicleOnOnly);
            parkReaperTokenReconciler.request(
                    generation, entering && vehicleOnOnly);
            Thread keepAlive = systemKeepAliveThread;
            if (keepAlive != null && keepAlive != Thread.currentThread()) {
                keepAlive.interrupt();
            }
        }
        lowBatteryStopReconciler.request(generation, false);
        lowBatteryWakeReconciler.request(generation, false);
        return generation;
    }

    private static void requestTransitionReconciliation(SentryTransitionState state) {
        boolean keepAwake = state.keepAwakeEnabled();
        parkReaperTokenReconciler.request(
                state.generation,
                state.sentryMode && state.vehicleOnOnly);
        peripheralPowerReconciler.request(state.generation, keepAwake);
        sentrySetupReconciler.request(state.generation, keepAwake);
        parkReaperReconciler.request(
                state.generation, state.sentryMode && state.vehicleOnOnly);
        notifyAccState(state.generation, state.sentryMode, false);
        monitorReconciler.request(state.generation, keepAwake);
        keepAliveReconciler.request(state.generation, keepAwake);
        telegramReconciler.request(state.generation, keepAwake);
        panelReconciler.request(state.generation, !state.sentryMode);
    }

    private static void requestPanelForLatestTransition() {
        SentryTransitionState latest = latestSentryTransition;
        panelReconciler.requestReapply(latest.generation, !latest.sentryMode);
    }

    private static boolean isSentryTransitionGenerationCurrent(long generation) {
        return latestSentryTransition.generation == generation;
    }

    private static boolean isSentryTransitionCurrent(
            long generation, boolean expectedSentryMode) {
        SentryTransitionState latest = latestSentryTransition;
        return generation == latest.generation
                && latest.sentryMode == expectedSentryMode;
    }

    private static boolean isSentrySetupRequestCurrent(
            long generation, boolean enabled) {
        SentryTransitionState latest = latestSentryTransition;
        return latest.generation == generation
                && latest.keepAwakeEnabled() == enabled
                && sentrySetupReconciler.isDesired(generation, enabled);
    }

    private static boolean sleepForSentrySetup(
            long generation, long delayMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + delayMs;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!isSentrySetupRequestCurrent(generation, true)) {
                return false;
            }
            long remaining =
                    deadline - android.os.SystemClock.elapsedRealtime();
            try {
                Thread.sleep(Math.min(remaining, 50L));
            } catch (InterruptedException interrupted) {
                Thread.interrupted();
                if (!isSentrySetupRequestCurrent(generation, true)) {
                    return false;
                }
            }
        }
        return isSentrySetupRequestCurrent(generation, true);
    }

    private static boolean applySentrySetupState(
            long generation, boolean enabled) {
        if (!enabled || !isSentrySetupRequestCurrent(generation, true)) {
            return true;
        }
        if (!sleepForSentrySetup(generation, 300L)) {
            return true;
        }

        boolean keepUsbPowered = isKeepUsbPowerOnAccOff();
        if (!isSentrySetupRequestCurrent(generation, true)) {
            return true;
        }
        if (!keepUsbPowered) {
            log("Keep-USB-power OFF: skipping forced system wake so AP can sleep "
                    + "(USB drops). HAL events still wake on demand.");
            return true;
        }

        boolean wakeCommitted = runBoundedHardwareBoolean(
                "Sentry setup system wake",
                () -> isSentrySetupRequestCurrent(generation, true),
                AccSentryDaemon::performSystemWakeUp,
                AccSentryDaemon::requestPanelForLatestTransition);
        if (!isSentrySetupRequestCurrent(generation, true)) {
            requestPanelForLatestTransition();
            return true;
        }
        if (!wakeCommitted) {
            log("Sentry mode setup wake was not confirmed; retrying");
            return false;
        }
        log("Sentry mode setup complete");
        return true;
    }

    /**
     * Enter Sentry Mode - The "car is off but watching" state.
     *
     * CRITICAL SEQUENCE (order matters for power stability):
     * 1. Initialize voltage monitoring FIRST
     * 2. Wake MCU immediately (triggers DC-DC converter)
     * 3. THEN wake the system (screen/CPU)
     * 4. Start the keep-alive loop (maintains the wake state)
     * 5. Enable surveillance AFTER power is stable
     */
    private static void enterSentryMode() {
        final boolean vehicleOnOnly =
                com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode();
        final long transitionGeneration =
                beginSentryTransition(true, vehicleOnOnly);
        if (transitionGeneration < 0L) {
            log("Already in sentry mode");
            requestTransitionReconciliation(latestSentryTransition);
            return;
        }

        log("=== ENTERING SENTRY MODE ===");
        SentryTransitionState transition = latestSentryTransition;
        requestTransitionReconciliation(transition);

        // GATE (G4c): in onOnly, release the core wakelock HERE — inline on the BYD HAL
        // listener thread — not on the SentrySetup worker below. exitSentryMode()'s
        // re-acquire (G4b) also runs on this listener thread, and the BYD bodywork
        // callbacks are single-threaded, so releasing here is strictly serialized against
        // the next ACC-ON acquire: OFF releases, next ON re-acquires, no interleave. Doing
        // the release on the async worker (as the first cut did) raced a rapid ACC-ON that
        // could re-acquire via G4b and flip inSentryMode=false, after which the lagging
        // worker would clobber the lock for the whole ON session. Guarded on inSentryMode
        // (still true here — we set it above and the listener is single-threaded) purely
        // for symmetry with the onAndOff path. onAndOff never enters this branch.
        if (vehicleOnOnly) {
            releaseWakeLock();
            log("onOnly mode: released core wakelock on ACC-OFF (inline) — letting head unit sleep");
            return;
        }

        log("Sentry mode ACTIVE");
    }

    /**
     * Exit Sentry Mode - Restore normal operation.
     *
     * Listener-thread contract: BYD bodywork callbacks are single-threaded.
     * Anything that does shell exec, Process.waitFor, Thread.join, binder
     * reflection, or UnifiedConfig disk writes MUST be dispatched off this
     * thread or the next ACC edge will queue behind us. The state flips
     * (inSentryMode/surveillanceEnabled) and the dispatch decision happen
     * inline; everything heavy runs on SentryTeardown.
     */
    private static void exitSentryMode() {
        final long transitionGeneration = beginSentryTransition(false, false);

        // Stamp the ACC-ON edge FIRST (cheap volatile write, no I/O). From here
        // until the trust window lapses, StealthPanel.turnOff() refuses to darken
        // the panel — which closes the race where the keep-alive's final in-flight
        // tick, or a deterrent teardown, darkens the screen just as the driver
        // starts the car. Deliberately before the !inSentryMode early-return, for
        // the same respawn-while-parked reason as G4b/G4c.
        try {
            com.overdrive.app.power.StealthPanel.noteAccOnObserved();
        } catch (Throwable ignored) {}

        // Duplicate ON reports and real transitions publish into the same bounded
        // reconcilers, so wake/cancel/IPC work cannot overlap another copy.
        requestTransitionReconciliation(latestSentryTransition);

        // GATE (G4b): re-acquire the core wakelock on every ACC-ON report. This
        // remains after state publication so a slow Binder call cannot delay the
        // generation-owned teardown and ACC notification.
        acquireWakeLock();

        if (transitionGeneration < 0L) {
            log("Not in sentry mode");
            return;
        }

        log("=== EXITING SENTRY MODE ===");

        // CRITICAL: beginSentryTransition set inSentryMode=false FIRST, before
        // any teardown work or keep-alive interruption.
        // The keep-alive loop checks `while (running && inSentryMode)` and its interrupt
        // handler also checks `if (!running || !inSentryMode)`. If we stop the thread
        // while inSentryMode is still true, the interrupt handler sees inSentryMode=true
        // and CONTINUES the loop instead of exiting — racing with the screen-wake thread
        // below and calling setBacklightState(false) after we've already turned the screen on.
        // This race caused intermittent 20-30 second screen blackouts after vehicle ON.
        // Clear safe zone suppression flag (clean slate for next sentry session) —
        // simple in-memory volatile flip in CameraDaemon, safe inline.
        try { CameraDaemon.setSafeZoneSuppressed(false); } catch (Exception ignored) {}

        log("Sentry mode DEACTIVATED");
    }

    /**
     * Cleanup and shutdown the daemon gracefully.
     * Called on process termination or manual shutdown.
     */
    private static void shutdownDaemon() {
        synchronized (shutdownLock) {
            if (shutdownComplete) {
                return;
            }
            log("=== DAEMON SHUTDOWN INITIATED ===");

            running = false;
            stopPowerListenerRegistrationSupervisor();
            stopBodyworkRegistrationSupervisor();
            stopAccPowerQuerySupervisor();
            Thread heartbeat = accHeartbeatThread;
            accHeartbeatThread = null;
            if (heartbeat != null
                    && heartbeat != Thread.currentThread()) {
                heartbeat.interrupt();
            }

            SentryTransitionState shutdownTransition =
                    beginShutdownAccOnTransition();
            boolean safeToReleaseSingleton =
                    awaitEssentialShutdownReconciliation(
                    shutdownTransition);

            stopStatusMonitoring();
            releaseWakeLock();
            if (safeToReleaseSingleton) {
                releaseSingletonLock();
            } else {
                log("WARNING: retaining singleton lock because park-reaper "
                        + "cancellation could not be made authoritative");
            }
            shutdownComplete = true;

            log("=== DAEMON SHUTDOWN COMPLETE ===");
        }
    }

    private static SentryTransitionState
            beginShutdownAccOnTransition() {
        if (inSentryMode) {
            beginSentryTransition(false, false);
            try {
                com.overdrive.app.power.StealthPanel
                        .noteAccOnObserved();
            } catch (Throwable ignored) {}
            try {
                CameraDaemon.setSafeZoneSuppressed(false);
            } catch (Throwable ignored) {}
        }

        SentryTransitionState transition =
                latestSentryTransition;
        requestTransitionReconciliation(transition);
        parkReaperTokenReconciler.requestReapply(
                transition.generation, false);
        parkReaperReconciler.requestReapply(
                transition.generation, false);
        accNotifyReconciler.requestReapply(
                transition.generation, false);
        return transition;
    }

    private static boolean awaitEssentialShutdownReconciliation(
            SentryTransitionState transition) {
        final long deadline =
                android.os.SystemClock.elapsedRealtime()
                        + 20_000L;
        boolean tokenCanceled = awaitReconcilerUntil(
                parkReaperTokenReconciler,
                transition.generation,
                false,
                deadline);
        if (!tokenCanceled) {
            log("WARNING: shutdown timed out publishing the "
                    + "park-reaper cancellation token");
        }

        boolean reaperCanceled = awaitReconcilerUntil(
                parkReaperReconciler,
                transition.generation,
                false,
                deadline);
        if (!reaperCanceled) {
            log("WARNING: shutdown timed out clearing the "
                    + "park-reaper state");
        }

        boolean reaperMadeSafe = tokenCanceled
                || terminateIdentityOwnedParkReaper();
        if (!reaperMadeSafe) {
            log("WARNING: shutdown could not persist cancellation or "
                    + "terminate the identity-owned park reaper");
        }

        boolean accOnPublished = awaitReconcilerUntil(
                accNotifyReconciler,
                transition.generation,
                false,
                deadline);
        if (!accOnPublished) {
            log("WARNING: shutdown timed out publishing "
                    + "the final ACC-ON IPC");
        }
        return reaperMadeSafe;
    }

    private static boolean awaitReconcilerUntil(
            LatestBooleanReconciler reconciler,
            long generation,
            boolean state,
            long deadline) {
        long remaining = deadline
                - android.os.SystemClock.elapsedRealtime();
        return remaining > 0L
                && reconciler.awaitApplied(
                        generation, state, remaining);
    }

    // ==================== DEBUG TOOLS ====================

    /**
     * DEBUG TOOL: Dumps the values of all known Sleep Reason constants.
     * Use this to verify which magic number (9, 13, etc.) your specific car firmware uses.
     */
    private static void logAllSleepReasonFields() {
        log("=== DUMPING SLEEP REASON CONSTANTS ===");

        String[] possibleFieldNames = {
            "GO_TO_SLEEP_REASON_ACCOFF",       // Primary BYD constant
            "GO_TO_SLEEP_REASON_ACC_OFF",      // Alternative naming
            "GO_TO_SLEEP_REASON_POWER_OFF",    // Generic power off
            "GO_TO_SLEEP_REASON_DEVICE_ADMIN", // Android 10+ constant (value 13)
            "GO_TO_SLEEP_REASON_TIMEOUT",      // Standard Android (usually 2)
            "GO_TO_SLEEP_REASON_POWER_BUTTON"  // Standard Android (usually 4)
        };

        for (String fieldName : possibleFieldNames) {
            try {
                java.lang.reflect.Field field = PowerManager.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                int value = field.getInt(null); // Static field, so object is null
                log("  [FOUND] " + fieldName + " = " + value);
            } catch (NoSuchFieldException e) {
                log("  [MISSING] " + fieldName + " (Not present on this firmware)");
            } catch (Exception e) {
                log("  [ERROR] " + fieldName + ": " + e.getMessage());
            }
        }

        // Also dump the standard SDK version for context
        log("  [INFO] Android SDK Version: " + android.os.Build.VERSION.SDK_INT);
        log("=== END DUMP ===");
    }

    // ==================== SENTRY HELPERS ====================

    /*// ==================== POWER METHOD DISCOVERY ====================

    *//**
     * Dump ALL PowerManager methods (no filtering).
     *//*
    private static void dumpPowerManagerMethods() {
        log("=== DUMPING ALL POWERMANAGER METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);

            for (Method m : pm.getClass().getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  PM: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }

            // Also try to get current screen power status if method exists
            try {
                Method getStatus = PowerManager.class.getMethod("getPowerScreenStatus");
                int status = (int) getStatus.invoke(pm);
                log("  >> Current getPowerScreenStatus(): " + status);
            } catch (NoSuchMethodException e) {
                log("  >> getPowerScreenStatus() not found");
            }

        } catch (Exception e) {
            log("PowerManager dump error: " + e.getMessage());
        }
        log("=== END POWERMANAGER METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoPowerDevice methods (no filtering).
     *//*
    private static void dumpBydPowerDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOPOWERDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            BYDAutoPowerDevice powerDevice = BYDAutoPowerDevice.getInstance(permissiveContext);

            if (powerDevice == null) {
                log("  BYDAutoPowerDevice.getInstance() returned null");
                return;
            }

            for (Method m : powerDevice.getClass().getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  BYD: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }

        } catch (Exception e) {
            log("BYDAutoPowerDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOPOWERDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoSettingDevice methods (no filtering).
     *//*
    private static void dumpBydSettingDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOSETTINGDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object settingDevice = getInstance.invoke(null, permissiveContext);

            if (settingDevice == null) {
                log("  BYDAutoSettingDevice.getInstance() returned null");
                return;
            }

            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  SETTING: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }

        } catch (Exception e) {
            log("BYDAutoSettingDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOSETTINGDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoLocationDevice methods.
     *//*
    private static void dumpBydLocationDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOLOCATIONDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.location.BYDAutoLocationDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, permissiveContext);

            if (device == null) {
                log("  BYDAutoLocationDevice.getInstance() returned null");
                return;
            }

            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  LOCATION: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }

        } catch (Exception e) {
            log("BYDAutoLocationDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOLOCATIONDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoADASDevice methods.
     *//*
    private static void dumpBydAdasDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOADASDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.adas.BYDAutoADASDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, permissiveContext);

            if (device == null) {
                log("  BYDAutoADASDevice.getInstance() returned null");
                return;
            }

            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  ADAS: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }

        } catch (Exception e) {
            log("BYDAutoADASDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOADASDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoBodyworkDevice methods.
     *//*
    private static void dumpBydBodyworkDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOBODYWORKDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, permissiveContext);

            if (device == null) {
                log("  BYDAutoBodyworkDevice.getInstance() returned null");
                return;
            }

            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  BODYWORK: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }

        } catch (Exception e) {
            log("BYDAutoBodyworkDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOBODYWORKDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoChargingDevice methods.
     *//*
    private static void dumpBydChargingDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOCHARGINGDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.charging.BYDAutoChargingDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, permissiveContext);

            if (device == null) {
                log("  BYDAutoChargingDevice.getInstance() returned null");
                return;
            }

            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  CHARGING: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }

        } catch (Exception e) {
            log("BYDAutoChargingDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOCHARGINGDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoStatisticDevice methods.
     *//*
    private static void dumpBydStatisticDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOSTATISTICDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, permissiveContext);

            if (device == null) {
                log("  BYDAutoStatisticDevice.getInstance() returned null");
                return;
            }

            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  STATISTIC: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }

        } catch (Exception e) {
            log("BYDAutoStatisticDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOSTATISTICDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoTyreDevice methods.
     *//*
    private static void dumpBydTyreDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOTYREDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.tyre.BYDAutoTyreDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, permissiveContext);

            if (device == null) {
                log("  BYDAutoTyreDevice.getInstance() returned null");
                return;
            }

            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  TYRE: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }

        } catch (Exception e) {
            log("BYDAutoTyreDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOTYREDEVICE METHODS ===");
    }

    *//**
     * Dump all BYD device methods at startup for discovery.
     *//*
    private static void dumpAllBydDeviceMethods() {
        log("=== STARTING BYD DEVICE METHOD DUMP ===");
        dumpBydLocationDeviceMethods();
        dumpBydAdasDeviceMethods();
        dumpBydBodyworkDeviceMethods();
        dumpBydChargingDeviceMethods();
        dumpBydStatisticDeviceMethods();
        dumpBydTyreDeviceMethods();
        log("=== COMPLETED BYD DEVICE METHOD DUMP ===");
    }*/

    // ==================== POWER CONTROL (Reflection) ====================

    /**
     * Dynamically retrieves the correct sleep reason code from the PowerManager.
     * This ensures compatibility across different Android versions (SDK 28 vs 29+)
     * and different BYD car models (Atto 3, Seal, etc.).
     *
     * Tries multiple field names that BYD might use across firmware versions.
     *
     * @return The correct GO_TO_SLEEP_REASON code (9 for older, 13 for SDK 32+).
     */
    private static int getSystemSleepReasonCode() {
        // Probe the BYD-added GO_TO_SLEEP_REASON_ACCOFF constant; if absent,
        // fall back to the SDK-version literal (13 on SDK 32+, else 9).
        try {
            java.lang.reflect.Field field =
                PowerManager.class.getDeclaredField("GO_TO_SLEEP_REASON_ACCOFF");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (NoSuchFieldException e) {
            // Constant not present on this firmware — use the version literal.
        } catch (Exception e) {
            // Access error — use the version literal.
        }
        return android.os.Build.VERSION.SDK_INT >= 32 ? 13 : 9;
    }

    /**
     * Performs a validated wake-up call using the correct context ID and details string.
     * This mimics a legitimate ignition event to bypass the ACC lock.
     * Uses "Double-Key" logic (Correct ID + "ACC_ON") to pass security check.
     *
     * CRITICAL: This is the initial wake call when entering sentry mode.
     * The keep-alive thread maintains this state via userActivity().
     */
    // Cached 3-arg PowerManager.wakeUp(long, int, String), resolved once.
    private static volatile Method pmWakeUp3ArgMethod;

    private static boolean performSystemWakeUp() {
        return performSystemWakeUp(-1L);
    }

    private static boolean performSystemWakeUp(long keepAliveGeneration) {
        if (appContext == null) {
            log("performSystemWakeUp: No context available");
            return false;
        }

        boolean committed = false;
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);

            int reasonID = getSystemSleepReasonCode();

            if (pmWakeUp3ArgMethod == null) {
                pmWakeUp3ArgMethod = PowerManager.class.getMethod(
                        "wakeUp", Long.TYPE, Integer.TYPE, String.class);
            }
            if (keepAliveGeneration >= 0L
                    && !isKeepAliveGenerationCurrent(keepAliveGeneration)) {
                return false;
            }
            pmWakeUp3ArgMethod.invoke(pm, android.os.SystemClock.uptimeMillis(), reasonID, "ACC_ON");
            log("System wake-up sent (reason: " + reasonID + ")");
            committed = true;
        } catch (Throwable t) {
            log("Wake-up failed: " + t.getMessage());
        }
        requestPanelForLatestTransition();
        return committed;
    }

    private static boolean isPanelRequestCurrent(
            long generation, boolean panelOn) {
        SentryTransitionState latest = latestSentryTransition;
        return latest.generation == generation
                && latest.sentryMode != panelOn
                && panelReconciler.isDesired(generation, panelOn);
    }

    private static boolean sleepForPanelRequest(
            long generation, boolean panelOn, long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return isPanelRequestCurrent(generation, panelOn);
    }

    /**
     * Owns every transition-driven panel write. If a Binder call completes
     * after ownership changes, LatestBooleanReconciler immediately applies the
     * newer complete state.
     */
    private static boolean applyPanelStateBounded(
            long generation, boolean panelOn) {
        ShellOwnership ownership =
                () -> isPanelRequestCurrent(generation, panelOn);
        BoundedCallResult<Boolean> result = boundedHardwareLane.invoke(
                "Panel state " + (panelOn ? "ON" : "OFF"),
                15_000L,
                ownership,
                () -> applyPanelState(generation, panelOn),
                AccSentryDaemon::requestPanelForLatestTransition);
        if (result.failure != null) {
            log("Panel state reconciliation failed: "
                    + result.failure.getMessage());
        }
        return result.completed
                && result.failure == null
                && Boolean.TRUE.equals(result.value);
    }

    private static boolean applyPanelState(long generation, boolean panelOn) {
        if (!isPanelRequestCurrent(generation, panelOn)) {
            return true;
        }

        if (panelOn) {
            boolean configCommitted = true;
            try {
                if (!isPanelRequestCurrent(generation, true)) {
                    return true;
                }
                com.overdrive.app.config.UnifiedConfigManager.updateValues(
                        "surveillance",
                        java.util.Collections.singletonMap(
                                "screenDeterrentForceStop", true));
            } catch (Throwable t) {
                log("Failed to signal screen deterrent stop: " + t.getMessage());
                configCommitted = false;
            }
            if (!sleepForPanelRequest(generation, true, 300L)) {
                return !isPanelRequestCurrent(generation, true);
            }

            boolean panelWriteCommitted = false;
            try {
                if (!isPanelRequestCurrent(generation, true)) {
                    return true;
                }
                panelWriteCommitted =
                        com.overdrive.app.power.StealthPanel
                                .turnOn(appContext);
                if (!panelWriteCommitted) {
                    log("Stealth panel wake was not confirmed");
                }
            } catch (Throwable t) {
                log("Stealth panel wake failed: " + t.getMessage());
            }

            for (int attempt = 1;
                    attempt <= 3 && isPanelRequestCurrent(generation, true);
                    attempt++) {
                if (!isPanelRequestCurrent(generation, true)) {
                    return true;
                }
                panelWriteCommitted |= setBacklightState(
                        generation, true);
                if (attempt < 3
                        && !sleepForPanelRequest(generation, true, 1000L)) {
                    return !isPanelRequestCurrent(generation, true);
                }
            }

            if (isPanelRequestCurrent(generation, true)) {
                try {
                    if (!isPanelRequestCurrent(generation, true)) {
                        return true;
                    }
                    java.util.Map<String, Object> deterrentClear =
                            new java.util.HashMap<>();
                    deterrentClear.put("screenDeterrentForceStop", false);
                    deterrentClear.put("screenDeterrentActiveUntilMs", 0L);
                    com.overdrive.app.config.UnifiedConfigManager.updateValues(
                            "surveillance", deterrentClear);
                } catch (Throwable t) {
                    log("Failed to clear screen deterrent stop: " + t.getMessage());
                    configCommitted = false;
                }
            }
            return configCommitted && panelWriteCommitted;
        }

        boolean configCommitted = true;
        if (panelDeterrentResetGeneration != generation) {
            try {
                if (!isPanelRequestCurrent(generation, false)) {
                    return true;
                }
                java.util.Map<String, Object> deterrentClear =
                        new java.util.HashMap<>();
                deterrentClear.put("screenDeterrentForceStop", false);
                deterrentClear.put("screenDeterrentActiveUntilMs", 0L);
                com.overdrive.app.config.UnifiedConfigManager.updateValues(
                        "surveillance", deterrentClear);
                panelDeterrentResetGeneration = generation;
            } catch (Throwable t) {
                log("Failed to clear stale screen deterrent flags: "
                        + t.getMessage());
                configCommitted = false;
            }
        }
        if (!isPanelRequestCurrent(generation, false)) {
            return true;
        }

        boolean dilink4 = isDilink4CameraMode();
        if (!dilink4) {
            if (!isScreenDeterrentActive() && !isCameraPipelineActive()) {
                if (!isPanelRequestCurrent(generation, false)) {
                    return true;
                }
                return setBacklightState(
                        generation, false) && configCommitted;
            }
            if (!isPanelRequestCurrent(generation, false)) {
                return true;
            }
            log("Legacy panel darkening temporarily gated; reconciliation remains pending");
            return false;
        }

        PanelDarkenDecision decision = panelDarkenDecision();
        if (!isPanelRequestCurrent(generation, false)) {
            return true;
        }
        if (decision == PanelDarkenDecision.ALREADY_DARK) {
            return configCommitted;
        }
        if (decision == PanelDarkenDecision.RETRY_LATER) {
            return false;
        }
        if (decision == PanelDarkenDecision.DARKEN) {
            try {
                if (!isPanelRequestCurrent(generation, false)) {
                    return true;
                }
                boolean darkened =
                        com.overdrive.app.power.StealthPanel
                                .turnOff(appContext);
                if (!darkened) {
                    log("WARN: parked panel did not darken after both tiers");
                }
                return darkened && configCommitted;
            } catch (Throwable t) {
                log("Stealth panel turnOff failed: " + t.getMessage());
                return false;
            }
        }
        return false;
    }

    private static boolean setBacklightState(boolean on) {
        return setBacklightState(on, null);
    }

    private static boolean setBacklightState(
            long generation, boolean on) {
        ShellOwnership ownership =
                () -> isPanelRequestCurrent(generation, on);
        return setBacklightState(on, ownership);
    }

    private static boolean setBacklightState(
            boolean on, ShellOwnership ownership) {
        log("Setting backlight: " + (on ? "ON" : "OFF"));

        // Try PowerManager reflection
        if (appContext != null) {
            try {
                PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                Class<?> pmClass = pm.getClass();

                // First-call probe of lowercase variant; cached thereafter.
                // Original semantics: lowercase invoke-time exceptions
                // bubble to the outer catch (then to BYD path), so we let
                // them propagate naturally.
                Method lower = getPmBacklightLowerMethod(on, pmClass);
                if (lower != null) {
                    if (!isShellOwnershipCurrent(ownership)) {
                        return false;
                    }
                    lower.invoke(pm, android.os.SystemClock.uptimeMillis());
                    log("Backlight: PowerManager." + (on ? "turnBacklightOn" : "turnBacklightOff") + " SUCCESS");
                    return true;
                }

                // Lowercase missing — try PascalCase variant. Original
                // probe order is preserved on first run; subsequent calls
                // skip directly to whichever resolved. PascalCase invoke-
                // time exceptions are swallowed (original code wrapped
                // [C]+[D] in catch (Exception e2)) so we mirror that with
                // an inner try.
                Method pascal = getPmBacklightPascalMethod(on, pmClass);
                if (pascal != null) {
                    try {
                        if (!isShellOwnershipCurrent(ownership)) {
                            return false;
                        }
                        pascal.invoke(pm, android.os.SystemClock.uptimeMillis());
                        log("Backlight: PowerManager." + (on ? "TurnBacklightOn" : "TurnBacklightOff") + " SUCCESS");
                        return true;
                    } catch (Exception e2) {
                        // Fall through to BYD path
                    }
                }
            } catch (Exception e) {
                // Fall through to BYD path (matches original outer catch).
            }

            // Try BYD Hardware Service
            try {
                resolveBydSettingDevice();
                if (bydSettingDeviceResolved) {
                    Method bydMethod = getBydSettingBacklightMethod(on);
                    if (bydMethod != null) {
                        Object device = bydSettingGetInstanceMethod.invoke(null, appContext);
                        if (!isShellOwnershipCurrent(ownership)) {
                            return false;
                        }
                        bydMethod.invoke(device);
                        log("Backlight: BYDAutoSettingDevice." + (on ? "turnBacklightOn" : "turnBacklightOff") + " SUCCESS");
                        return true;
                    }
                }
            } catch (Exception e) {
                // Fall through
            }
        }

        // Fallback: Settings brightness & StealthPanel
        int brightness = on ? 128 : 0;
        ShellResult brightnessResult = execShellResult(
                "settings put system screen_brightness " + brightness,
                DEFAULT_SHELL_TIMEOUT_MS, ownership);
        if (!brightnessResult.success) {
            return false;
        }
        if (on) {
            ShellResult keyResult = execShellResult(
                    "input keyevent 224",
                    DEFAULT_SHELL_TIMEOUT_MS, ownership);
            if (!keyResult.success) {
                log("Backlight shell fallback failed: keyevent=" + keyResult.describeFailure());
            }
            return keyResult.success;
        } else {
            // CRITICAL: Do NOT send "input keyevent 223" (KEYCODE_SLEEP).
            // KEYCODE_SLEEP forces mWakefulness to Asleep, which triggers mHalAutoSuspendModeEnabled
            // and kernel suspend-to-RAM, freezing CPU, Wi-Fi, and LTE.
            // Brightness 0 + turnBacklightOff keeps mWakefulness Awake while display is fully dark.
            try {
                com.overdrive.app.power.StealthPanel.turnOff(appContext);
            } catch (Throwable ignored) {}
            return true;
        }
    }

    /**
     * Enforces strict power management state.
     * Transitions the display to the OFF state while strictly prohibiting
     * the operating system from entering deep sleep (Doze) modes.
     * This maintains network and CPU availability while minimizing power draw.
     */
    private static void enforceSmartSleep() {
        if (appContext == null) return;

        // Yield to an active screen deterrent — the deterrent in
        // byd_cam_daemon's process is currently driving the panel ON and
        // would be clobbered by goToSleep / setBacklightState(false).
        if (isScreenDeterrentActive()) {
            return;
        }

        // DiLink 4: use the verified two-tier backlight path rather than
        // goToSleep. goToSleep is a full AP sleep request, which the reference
        // app reserves for its own shutdown path — for "dark panel, CPU alive"
        // it uses TurnBacklightOff(+WithLock) and verifies with
        // getPowerScreenStatus(). The setBacklightState(false) fallback below is
        // tier-1-only and can be silently ignored on this firmware.
        //
        // This method currently has NO callers. The gate is here because it
        // reads as the obvious helper to reach for ("enforce stealth power
        // state"), so the next person to wire it up gets the verified path
        // instead of an unverified one. Legacy pano_h/pano_l units are
        // unaffected and keep the original goToSleep behaviour.
        try {
            com.overdrive.app.power.StealthPanel.turnOff(appContext);
            setBacklightState(false);
            log("enforceSmartSleep: display darkened without triggering Asleep state");
        } catch (Throwable t) {
            log("enforceSmartSleep failed: " + t.getMessage());
            setBacklightState(false);
        }
    }

    // ==================== SYSTEM PERSISTENCE SERVICE ====================

    /**
     * Starts the System Persistence Service (10-second maintenance loop).
     * Implements the "Refresh & Enforce" pattern:
     * 1. Maintains network interface stability (WiFi always; cellular when armed —
     *    i.e. mobile data was ON at sentry entry, issue #209)
     * 2. Refreshes CPU wake timer (fake user activity)
     * 3. Enforces stealth power state (screen off, CPU active)
     *
     * CRITICAL: Uses Throwable catch to survive OutOfMemoryError and other Errors.
     * Thread is NOT a daemon so it survives if main thread has issues.
     */
    private static boolean startSystemKeepAlive(final long transitionGeneration) {
        if (!isSentryTransitionGenerationCurrent(transitionGeneration)
                || !keepAliveReconciler.isDesired(
                        transitionGeneration, true)) {
            return true;
        }

        final Thread keepAliveThread = new Thread(() -> {
            log("System Persistence Service started");

            // Re-assert cadence for the OEM 409 camera/ISP power vote. The
            // loop ticks every SYSTEM_KEEPALIVE_INTERVAL_MS (10s); the ISP
            // gate that black-frames the AVM feed fires at ~30-35 min of
            // inactivity, so re-asserting every ~5 min (30 ticks) is a 6-7x
            // safety margin while keeping binder/log churn negligible. The
            // initial vote is already cast by applyPeripheralPowerBatch(true)
            // in enterSentryMode; this only defends against the MCU/BCM
            // silently dropping the flag mid-session.
            final long ISP_VOTE_REASSERT_EVERY_TICKS = 30;  // 30 * 10s = 5 min
            // Periodic MCU re-wake + peripheral-rail re-assert cadence. On some
            // models the BCM drifts the MCU back to sleep mid-park after the
            // one-shot enterSentryMode wake, which collapses the USB-bridged SD
            // reader rail and the modem/data rail — the unit then loses USB/SD
            // power AND network connectivity while parked. Re-asserting on a slow
            // cadence holds them up. 48 ticks * 10s = 8 min.
            final long MCU_REWAKE_EVERY_TICKS = 48;  // 48 * 10s = 8 min
            // Reactive SD-rail recovery. On-car (log 2026-07-19): the USB-bridged
            // SD rail collapses DURING the ACC-OFF transition itself
            // (sys.byd.isSDExist flips false seconds after sentry entry), so the
            // one-shot enterSentryMode wake was too early to help and the FIRST
            // periodic re-assert at 8 min was too late — a 4.5-min park session
            // ran its whole life with the SD dead (all events fell back to
            // internal), and an 8-min session only recovered the card exactly at
            // the first re-wake (sm mount succeeded within one watchdog tick of
            // it). Rather than blind early ticks, PROBE the vendor card-detect
            // prop each tick (reflection SystemProperties read — microseconds)
            // and fire the same MCU+rail re-assert only when the rail is actually
            // dead AND the user has SD configured as a storage target. Backoff
            // doubles per attempt (30s → 1m → 2m → 4m → capped at the 8-min
            // cadence) so a genuinely absent card (user pulled it) degenerates
            // to the existing periodic behaviour instead of waking the MCU every
            // 30s all night. Backoff resets whenever the rail reads alive, so a
            // LATER mid-park drop is again recovered within ~30s. First probe at
            // tick 3 (~30s) — the MCU needs a beat to stabilize post-transition;
            // probing earlier just burns a wake on a rail that's still settling.
            final long SD_RECOVERY_MIN_TICK = 3;            // ~30s after entry
            final long SD_RECOVERY_BASE_BACKOFF_TICKS = 3;  // 30s between attempts
            long sdRecoveryBackoffTicks = SD_RECOVERY_BASE_BACKOFF_TICKS;
            // NOT Long.MIN_VALUE: `tick - lastSdRecoveryTick` would overflow to
            // a negative value and the backoff gate would never open. Seeding
            // one backoff below zero makes the first eligible tick pass exactly.
            long lastSdRecoveryTick = -SD_RECOVERY_BASE_BACKOFF_TICKS;
            // Cellular keep-alive arming snapshot (issue #209). The sentry rail hold
            // keeps the modem POWERED across ACC-OFF, but on some firmware (reported
            // on Seal 2025 / system 2506) the system still flips the mobile-data
            // master switch off at park, so WiFi survives and cellular doesn't.
            // OPT-IN (default OFF): holding the bearer up costs battery and data on
            // firmware that doesn't need it, so the probe only runs when the user
            // enabled it. Then snapshot the switch ONCE at session start: only when
            // the user had data ON do we re-assert it each tick, mirroring
            // ensureWifiEnabled. A user who deliberately keeps data off (no plan /
            // roaming cost) is never force-enabled — unlike WiFi, turning data on can
            // cost money, so this keep-alive preserves state rather than imposing it.
            final boolean cellularKeepAliveEnabled =
                    com.overdrive.app.config.UnifiedConfigManager
                            .isMobileDataKeepAliveEnabled();
            final boolean cellularKeepAliveArmed = cellularKeepAliveEnabled
                    && isMobileDataOnAtSentryEntry(transitionGeneration);
            log("Cellular keep-alive " + (!cellularKeepAliveEnabled
                    ? "disabled (setting off — default)"
                    : cellularKeepAliveArmed
                    ? "armed (mobile data was ON at sentry entry)"
                    : "not armed (mobile data OFF/unknown at sentry entry)"));
            long tick = 0;

            while (running && isKeepAliveCommitCurrent(transitionGeneration)) {
                try {
                    // 1. Maintain Network Interface Stability
                    if (!isKeepAliveCommitCurrent(transitionGeneration)) break;
                    ensureWifiEnabled(transitionGeneration);
                    if (cellularKeepAliveArmed) {
                        if (!isKeepAliveCommitCurrent(transitionGeneration)) break;
                        ensureMobileDataEnabled(transitionGeneration);
                    }
                    // Fake-activity injection resets the AP sleep timer using the
                    // 2-arg stealth userActivity(uptime, noChangeLights=true), which
                    // holds the AP awake (USB VBUS follows wakefulness) WITHOUT
                    // relighting the panel. We can therefore keep the backlight OFF
                    // and USB powered at the same time. Only inject when "Keep USB
                    // powered" is ON (default); toggle OFF lets the AP drift to sleep
                    // and USB drop to save the 12 V battery. The ISP/AVM camera vote
                    // and AVC keep-alive below stay unconditional — they're the camera
                    // rail, not the AP wake, and surveillance needs them either way.
                    if (isKeepAliveCommitCurrent(transitionGeneration)
                            && isKeepUsbPowerOnAccOff()) {
                        if (!isKeepAliveCommitCurrent(transitionGeneration)) break;
                        runBoundedHardwareBoolean(
                                "KeepAlive user activity",
                                () -> isKeepAliveGenerationCurrent(
                                        transitionGeneration),
                                () -> injectFakeUserActivity(
                                        transitionGeneration),
                                AccSentryDaemon::requestPanelForLatestTransition);
                        requestPanelForLatestTransition();
                    }

                    // ScreenDeterrent gate: if a screen deterrent is currently
                    // displaying (set by ScreenDeterrent.fire()), skip the
                    // backlight-off tick. Otherwise this loop would clobber
                    // the wake within 10s and the user would never see the
                    // deterrent through to its full duration.
                    //
                    // DiLink 4 gate: byd_apa AVMCamera HAL ties its preview
                    // surface to display power state. setBacklightState(false)
                    // makes the HAL emit event=8 ("camera died") and tear
                    // down the preview, killing 24/7 sentry recording on a
                    // 10s cadence. Skip the backlight-off entirely on
                    // dilink4. Legacy pano_h/pano_l HALs are
                    // display-state-agnostic and keep their existing
                    // power-save behaviour.
                    //
                    // BUT skipping backlight-off did NOT mean the panel dimmed
                    // by itself. This comment used to claim the "display
                    // naturally dims via the head-unit's own timeout" — it
                    // cannot: injectFakeUserActivity() above pumps
                    // PowerManager.userActivity() every 10s to hold the AP
                    // awake, which is precisely what resets the display-off
                    // timer. So dilink4 parked with a fully-lit screen.
                    //
                    // The fix is NOT to suppress display power. Verified against
                    // the byd_apa reference app (
                    // BacklightController + DeviceWakeupMonitor): it turns the
                    // backlight genuinely OFF at park entry WHILE its
                    // AVMCameraRecordAgent is recording, so backlight-off is not
                    // what kills the AVM preview. It also never writes
                    // screen_brightness — brightness 0 does not sleep this
                    // panel. What it has that we lacked is a second tier
                    // (TurnBacklightOffWithLock on PowerManager.mService) plus
                    // getPowerScreenStatus() verification, which is what makes
                    // the off actually stick. StealthPanel.turnOff() reproduces
                    // that. Strictly dilink4-only; legacy units keep
                    // setBacklightState(false) byte-for-byte.
                    //
                    // The cameraActiveUntilMs check is the finer-grained
                    // gate: only the slice of time when the GPU pipeline is
                    // actively consuming frames. Useful even on legacy if
                    // a dashcam mode is running across ACC OFF (rare).
                    //
                    // "Keep USB powered" is NO LONGER a backlight gate. Keeping
                    // USB powered needs the AP awake (wakefulness AWAKE → VBUS),
                    // NOT the panel illuminated — so we let the backlight go off
                    // here even with the toggle ON, rather than pinning the panel
                    // lit for the whole park (the old behaviour: a parked car with
                    // a fully lit screen all night).
                    //
                    // AP wakefulness is held by two mechanisms: (a) the per-tick
                    // injectFakeUserActivity() above WHILE the panel is on (it
                    // self-skips once getPowerScreenStatus()==0, i.e. after this
                    // backlight-off tick), and (b) the periodic performSystemWakeUp()
                    // in the 8-min re-assert block below, which re-arms wakefulness
                    // with the panel dark (then immediately re-darks it). (b) is the
                    // load-bearing re-assert once the screen is off.
                    //
                    // We still suppress the backlight-off in three cases that
                    // genuinely need the panel powered:
                    //   - a screen deterrent is on-screen (it owns the backlight);
                    //   - DiLink 4 AVMCamera HAL ties its preview surface to display
                    //     power — backlight off makes it emit event=8 ("camera
                    //     died") and tear down 24/7 sentry recording;
                    //   - the GPU camera pipeline is actively consuming frames
                    //     (cameraActiveUntilMs lease) on a display-coupled HAL.
                    // Legacy pano_h/pano_l HALs are display-state-agnostic, so on
                    // those the backlight goes off here even mid-recording.
                    // Evaluate each predicate ONCE per tick. Both read config, and
                    // the branches below used to ask for the same answer twice.
                    // isScreenDeterrentActive(true) escalates to a fresh read only
                    // when the cheap read says "inactive" — see its javadoc: a
                    // same-second stale read here would darken the panel out from
                    // under a deterrent that is actively rendering.
                    // NOTE ON EVALUATION ORDER — this is deliberate, not stylistic.
                    // The legacy branch keeps the ORIGINAL cheap mtime-gated read
                    // (isScreenDeterrentActive()), so the pano_h/pano_l fleet pays
                    // exactly what it always did. The confirm-with-forceReload
                    // variant is evaluated ONLY inside the dilink4 branch, and only
                    // after the cheap deterrent check has already said "inactive"
                    // and the panel is about to be darkened. Hoisting it into a
                    // local (the obvious-looking cleanup) would force a ~10 KB JSON
                    // re-parse every 10 s on EVERY unit — the ≈3.6 MB/hour churn
                    // that isScreenDeterrentActive's own javadoc exists to avoid.
                    if (isKeepAliveCommitCurrent(transitionGeneration)) {
                        panelReconciler.requestReapply(
                                transitionGeneration, false);
                    }

                    // DiLink 4 AVC keep-alive (June 2026 reversal). The
                    // AVM HAL only delivers mosaic content into our
                    // panoramic producer surface while another consumer is
                    // attached to the same vendor.byd.avm daemon — AVC is
                    // exactly that consumer. If BYD's reaper kills AVC
                    // post-ACC-OFF our frames go all-zero. ensureAvcAlive
                    // is a cheap pidof + (only if absent) am start. No-op
                    // when AVC is already running. Red overlay is masked
                    // cosmetically by the GL red-mask shader.
                    if (isKeepAliveCommitCurrent(transitionGeneration)
                            && isDilink4CameraMode()) {
                        try {
                            if (isKeepAliveCommitCurrent(transitionGeneration)) {
                                com.overdrive.app.camera.AvcHalWarmup.ensureAvcAlive();
                            }
                        } catch (Throwable t) {
                            log("AVC keep-alive tick failed: " + t.getMessage());
                        }
                    }

                    // 3b. Re-assert the OEM 409 camera/ISP power vote on a
                    // ~5 min cadence so the AVM ISP rail can't power-gate
                    // mid-session (the ~30-35 min black-frame symptom). Cheap
                    // best-effort SpecialDevice writes; no-op on trims that
                    // don't expose the key. Fires on the first tick too
                    // (tick==0) as a belt-and-suspenders re-cast after the
                    // enterSentryMode vote. ALWAYS on — this is the CAMERA/ISP
                    // rail, not the USB rail; the USB toggle never gates it.
                    if (tick % ISP_VOTE_REASSERT_EVERY_TICKS == 0
                            && isKeepAliveCommitCurrent(transitionGeneration)) {
                        peripheralPowerReconciler.requestReapply(
                                transitionGeneration, true);
                    }

                    // Periodic MCU re-wake + peripheral-rail re-assert (every
                    // MCU_REWAKE_EVERY_TICKS, ~8 min). Gated on the "Keep USB
                    // powered" toggle so the battery-save (toggle-OFF) path is
                    // byte-unchanged — when the user opted to let the AP/USB
                    // sleep we must NOT re-wake the MCU here. Skipped on tick 0
                    // because enterSentryMode just ran the same sequence seconds
                    // ago. applyPeripheralPowerBatch(true) is idempotent and
                    // already self-checks MCU status (re-waking it if asleep)
                    // before re-writing the rails, so this is the exact same
                    // proven entry-path call, just re-run on a slow cadence to
                    // counter the BCM drifting the MCU back to sleep mid-park.
                    // Reactive SD-rail probe (see constants above). Only when the
                    // rail is confirmed dead, SD is a configured storage target,
                    // the backoff window has elapsed, and keep-USB is ON.
                    boolean sdRecoveryTick = false;
                    if (tick >= SD_RECOVERY_MIN_TICK
                            && (tick - lastSdRecoveryTick) >= sdRecoveryBackoffTicks
                            && isKeepUsbPowerOnAccOff()
                            && isSdConfiguredAsStorageTarget()) {
                        if (isSdRailDead()) {
                            sdRecoveryTick = true;
                            lastSdRecoveryTick = tick;
                            // Exponential backoff, capped at the periodic cadence.
                            sdRecoveryBackoffTicks = Math.min(
                                sdRecoveryBackoffTicks * 2, MCU_REWAKE_EVERY_TICKS);
                        } else {
                            // Rail alive — reset so a later mid-park drop gets the
                            // fast 30s response again.
                            sdRecoveryBackoffTicks = SD_RECOVERY_BASE_BACKOFF_TICKS;
                        }
                    }
                    if ((sdRecoveryTick || (tick > 0 && tick % MCU_REWAKE_EVERY_TICKS == 0))
                            && isKeepUsbPowerOnAccOff()
                            && isKeepAliveCommitCurrent(transitionGeneration)) {
                        try {
                            log("Periodic MCU re-wake + rail re-assert ("
                                + (sdRecoveryTick
                                    ? "SD-rail-dead recovery, tick " + tick
                                        + ", next backoff " + sdRecoveryBackoffTicks + " ticks"
                                    : "8-min cadence") + ")");
                            // Re-assert the MCU + peripheral rails (idempotent;
                            // self-wakes the MCU if the BCM slept it).
                            peripheralPowerReconciler.requestReapply(
                                    transitionGeneration, true);
                            if (!isKeepAliveCommitCurrent(transitionGeneration)) {
                                break;
                            }
                            // ALSO re-establish AP wakefulness. applyPeripheralPowerBatch
                            // only drives the MCU/DC-DC rail, NOT the AP wake state —
                            // and on DiLink 3.0 USB VBUS follows AP wakefulness. The
                            // per-tick injectFakeUserActivity() pump self-skips once the
                            // backlight is off (it returns early on getPowerScreenStatus
                            // ==0), so without this the AP can drift to sleep mid-park and
                            // USB/SD drops even with the toggle ON. performSystemWakeUp()
                            // is the same AP-wake enterSentryMode casts at ACC-OFF.
                            // NOTE: wakeUp() lights the panel. Left alone, the screen
                            // would stay on until the next backlight-off tick (up to one
                            // SYSTEM_KEEPALIVE_INTERVAL_MS away) — an ~8-min screen flash
                            // on a parked car. So immediately re-dark the panel here under
                            // the SAME conditions the per-tick backlight-off gate uses
                            // (skip when a deterrent owns the panel, on dilink4 where
                            // backlight-off kills the AVM HAL, or while the GPU pipeline is
                            // actively consuming frames). The AP wakefulness set by
                            // wakeUp() persists with the panel dark — exactly the desired
                            // state. Gated on the same toggle so the OFF path is untouched.
                            if (!isKeepAliveCommitCurrent(transitionGeneration)) {
                                break;
                            }
                            boolean wakeCommitted = runBoundedHardwareBoolean(
                                    "KeepAlive system wake",
                                    () -> isKeepAliveGenerationCurrent(
                                            transitionGeneration),
                                    () -> performSystemWakeUp(
                                            transitionGeneration),
                                    AccSentryDaemon::requestPanelForLatestTransition);
                            // wakeUp() just relit the panel. On firmware with no
                            // getPowerScreenStatus() that relight is invisible to
                            // StealthPanel, so declare it — otherwise its
                            // unverifiable-firmware latch would treat the
                            // re-darken below as redundant and skip it, leaving
                            // the panel lit for the rest of the park.
                            //
                            // Gated on dilink4: this is a CONFIG WRITE, and the key
                            // it clears is one only the dilink4 path ever reads. Run
                            // unconditionally it would bump the config mtime every
                            // ~8 min on the legacy fleet too, invalidating the
                            // mtime-gated loadConfig() cache in every daemon process
                            // and forcing a ~10 KB re-parse on their next read — the
                            // churn the gates here are carefully written to avoid.
                            if (wakeCommitted
                                    && isKeepAliveCommitCurrent(transitionGeneration)
                                    && isDilink4CameraMode()) {
                                try {
                                    if (isKeepAliveCommitCurrent(
                                            transitionGeneration)) {
                                        com.overdrive.app.power.StealthPanel
                                            .notePanelStateChangedExternally();
                                    }
                                } catch (Throwable ignored) {}
                            }
                            requestPanelForLatestTransition();
                        } catch (Throwable t) {
                            log("Periodic MCU re-wake / rail re-assert failed: " + t.getMessage());
                        }
                    }
                    tick++;

                    // 4. Maintenance Cycle Interval (10 seconds)
                    Thread.sleep(SYSTEM_KEEPALIVE_INTERVAL_MS);

                } catch (InterruptedException e) {
                    log("KeepAlive interrupted - checking if should continue...");
                    if (!running
                            || !isKeepAliveCommitCurrent(transitionGeneration)) {
                        break;  // Exit cleanly
                    }
                    // Otherwise continue the loop
                } catch (Throwable t) {
                    // CRITICAL: Catch EVERYTHING including Errors (OutOfMemoryError, etc.)
                    // DON'T break - keep trying!
                    log("KeepAlive error: " + t.getMessage());
                    try {
                        Thread.sleep(1000);  // Brief pause before retry
                    } catch (InterruptedException ignored) {
                        if (!running
                                || !isKeepAliveCommitCurrent(transitionGeneration)) {
                            break;
                        }
                    }
                }
            }

            log("System Persistence Service stopped");
            boolean requestReplacement = false;
            synchronized (systemKeepAliveLock) {
                if (systemKeepAliveThread == Thread.currentThread()) {
                    systemKeepAliveThread = null;
                    systemKeepAliveGeneration = -1L;
                    SentryTransitionState latest = latestSentryTransition;
                    requestReplacement = running
                            && latest.generation == transitionGeneration
                            && latest.keepAwakeEnabled()
                            && keepAliveReconciler.isDesired(
                                    transitionGeneration, true);
                }
            }
            if (requestReplacement) {
                keepAliveReconciler.requestReapply(
                        transitionGeneration, true);
            }
        }, "SystemKeepAlive");

        final Thread previous;
        synchronized (systemKeepAliveLock) {
            if (!isSentryTransitionGenerationCurrent(transitionGeneration)
                    || !keepAliveReconciler.isDesired(
                            transitionGeneration, true)) {
                return true;
            }
            if (systemKeepAliveThread != null
                    && systemKeepAliveThread.isAlive()
                    && systemKeepAliveGeneration == transitionGeneration) {
                return true;
            }
            if (systemKeepAliveThread != null
                    && systemKeepAliveGeneration > transitionGeneration) {
                return true;
            }
            previous = systemKeepAliveThread;
        }

        if (previous != null && previous.isAlive()) {
            previous.interrupt();
            if (!joinThreadBounded(previous, 2000L)) {
                log("WARN: prior KeepAlive generation "
                        + systemKeepAliveGeneration
                        + " is still terminating; deferring generation "
                        + transitionGeneration);
                return false;
            }
        }

        try {
            // CRITICAL: Not a daemon thread! Survives if main thread has issues.
            keepAliveThread.setDaemon(false);
        } catch (Throwable configurationFailure) {
            log("System Persistence Service thread setup failed: "
                    + configurationFailure.getMessage());
            return false;
        }

        synchronized (systemKeepAliveLock) {
            if (systemKeepAliveThread == previous
                    && (previous == null || !previous.isAlive())) {
                systemKeepAliveThread = null;
                systemKeepAliveGeneration = -1L;
            }
            if (!isSentryTransitionGenerationCurrent(transitionGeneration)
                    || !keepAliveReconciler.isDesired(
                            transitionGeneration, true)) {
                return true;
            }
            if (systemKeepAliveThread != null
                    && systemKeepAliveThread.isAlive()) {
                return systemKeepAliveGeneration >= transitionGeneration;
            }
            systemKeepAliveThread = keepAliveThread;
            systemKeepAliveGeneration = transitionGeneration;
        }

        try {
            keepAliveThread.start();
            return true;
        } catch (Throwable startFailure) {
            synchronized (systemKeepAliveLock) {
                if (systemKeepAliveThread == keepAliveThread) {
                    systemKeepAliveThread = null;
                    systemKeepAliveGeneration = -1L;
                }
            }
            log("System Persistence Service failed to start: "
                    + startFailure.getMessage());
            return false;
        }
    }

    private static boolean isKeepAliveGenerationCurrent(long generation) {
        synchronized (systemKeepAliveLock) {
            SentryTransitionState latest = latestSentryTransition;
            return latest.generation == generation
                    && latest.keepAwakeEnabled()
                    && keepAliveReconciler.isDesired(generation, true)
                    && systemKeepAliveGeneration == generation
                    && systemKeepAliveThread != null
                    && systemKeepAliveThread.isAlive();
        }
    }

    private static boolean isKeepAliveCommitCurrent(long generation) {
        synchronized (systemKeepAliveLock) {
            SentryTransitionState latest = latestSentryTransition;
            return latest.generation == generation
                    && latest.keepAwakeEnabled()
                    && keepAliveReconciler.isDesired(generation, true)
                    && systemKeepAliveGeneration == generation
                    && systemKeepAliveThread == Thread.currentThread();
        }
    }

    /**
     * True if a screen deterrent is currently displaying (set by
     * ScreenDeterrent.fire() in byd_cam_daemon's process). loadConfig()
     * invalidates its cache against configFile.lastModified() which is
     * filesystem-wide and therefore visible across UIDs — forceReload()
     * here would re-parse ~10 KB JSON every 10 s for the same answer
     * (≈3.6 MB/hour GC churn). Returns false on any failure so a stuck
     * flag can never disable the stealth keep-alive permanently.
     */
    private static boolean isScreenDeterrentActive() {
        return isScreenDeterrentActive(false);
    }

    /**
     * The dilink4 "should the keep-alive darken the panel this tick?" decision.
     *
     * <p>Factored out of the keep-alive's {@code else if} condition deliberately.
     * Those guards previously sat in the condition itself, OUTSIDE the narrow
     * try/catch around the darken call — so a throw from any of them would escape
     * to the loop-body handler and abandon the REST of that tick (AVC keep-alive,
     * SD-rail recovery, USB-power re-assert, the 8-min MCU re-wake), every tick,
     * for the whole park. Each guard catches Throwable internally today, but the
     * structure made a future edit to any of them a fleet-wide surveillance
     * outage. Here a failure is contained and fails CLOSED (don't darken), which
     * is the safe direction: a lit panel is visible and self-corrects next tick,
     * whereas losing the tick's rail work is not visible at all.
     *
     * <p>Order is a cost decision, not style. {@code isAlreadyDark} is checked
     * FIRST because it is the cheap common case (~99.9% of ticks) and short-
     * circuits the two expensive guards, both of which do a full config re-parse.
     */
    private enum PanelDarkenDecision {
        DARKEN,
        ALREADY_DARK,
        RETRY_LATER
    }

    private static PanelDarkenDecision panelDarkenDecision() {
        String reason;
        try {
            if (com.overdrive.app.power.StealthPanel.isAlreadyDark(appContext)) {
                reason = "already-dark";
            } else if (isScreenDeterrentActive(true)) {
                reason = "deterrent-active";
            } else if (com.overdrive.app.power.StealthPanel.isUserOverrideActive()) {
                reason = "user-override";
            } else {
                logPanelGate(null);
                return PanelDarkenDecision.DARKEN;
            }
        } catch (Throwable t) {
            log("Panel-darken gate failed, skipping this tick: " + t.getMessage());
            return PanelDarkenDecision.RETRY_LATER;
        }
        // Log WHY we declined, on state change only. Without this a park that
        // stayed lit produced no log line at all from either file: all three
        // branches above skip turnOff() entirely, so neither StealthPanel's
        // per-transition log nor the ~5-min failure WARN can fire. That made the
        // four possible causes of "my screen stayed on all night" indistinguishable
        // in a customer log pull.
        logPanelGate(reason);
        return "already-dark".equals(reason)
                ? PanelDarkenDecision.ALREADY_DARK
                : PanelDarkenDecision.RETRY_LATER;
    }

    private static volatile String lastPanelGateReason = "";

    private static void logPanelGate(String reason) {
        String key = (reason == null) ? "" : reason;
        if (key.equals(lastPanelGateReason)) return;
        lastPanelGateReason = key;
        if (reason == null) log("Panel-darken gate: proceeding (darkening panel)");
        else log("Panel-darken gate: declining — " + reason);
    }

    /**
     * @param confirmInactive when true, a negative answer from the mtime-cached
     *        read is re-checked with {@link
     *        com.overdrive.app.config.UnifiedConfigManager#forceReload()} before
     *        being believed.
     *
     * <p>Why that escalation exists: ext4 mtime resolution is 1 s, and
     * ScreenDeterrent (a DIFFERENT process) publishes its gate immediately before
     * waking the panel. If that write lands in the same wall-clock second as our
     * cached read, loadConfig()'s {@code fileModified <= lastModified} check
     * returns the STALE config without the new deadline, we conclude no deterrent
     * is active, and turnOff() darkens the panel while the deterrent's
     * z=Integer.MAX_VALUE layer is being composited — an intruder warning nobody
     * can see. ScreenDeterrent guards the mirror-image hazard the same way
     * (forceReload in shouldStop / isForceStop).
     *
     * <p>Cost is bounded to the case that matters: the extra parse happens only
     * when the cheap read says "inactive" AND the caller is about to darken the
     * panel. Once the panel is dark, turnOff() self-skips before consulting this,
     * so the steady state does not pay it — nowhere near the ≈3.6 MB/hour that an
     * unconditional forceReload here would cost.
     */
    private static boolean isScreenDeterrentActive(boolean confirmInactive) {
        try {
            org.json.JSONObject s = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("surveillance");
            long deadline = (s == null) ? 0L : s.optLong("screenDeterrentActiveUntilMs", 0L);
            if (isDeterrentDeadlineLive(deadline)) return true;
            if (!confirmInactive) return false;
            // Cheap read says inactive — re-read fresh before acting on it.
            org.json.JSONObject fresh = com.overdrive.app.config.UnifiedConfigManager.forceReload()
                    .optJSONObject("surveillance");
            if (fresh == null) return false;
            return isDeterrentDeadlineLive(
                    fresh.optLong("screenDeterrentActiveUntilMs", 0L));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Upper-bound the deterrent gate the same way StealthPanel bounds its
     * user-override deadline. ScreenDeterrent only ever publishes {@code now +
     * duration} with duration clamped to 3-30 s, so any legitimate deadline is
     * seconds away. A far-future value means a stale gate survived an unclean
     * teardown (the deterrent was SIGKILLed before cleanup() zeroed it) AND the
     * wall clock stepped backward — a documented condition on these head units.
     * Unbounded, that reads as "deterrent active" forever and silently suppresses
     * parked darkening for the whole park. The 5-minute ceiling is ~10x the
     * longest real deterrent, so it cannot reject a genuine one.
     */
    private static final long DETERRENT_GATE_MAX_HORIZON_MS = 5 * 60_000L;

    private static boolean isDeterrentDeadlineLive(long deadlineMs) {
        long now = System.currentTimeMillis();
        return deadlineMs > now && deadlineMs <= now + DETERRENT_GATE_MAX_HORIZON_MS;
    }

    /**
     * True when the user has selected DiLink 4 mode for the camera. On
     * byd_apa firmware the AVMCamera HAL tears down the preview surface
     * whenever the display backlight goes off, so the keepalive's
     * setBacklightState(false) tick must be suppressed entirely. Reads
     * the same UnifiedConfigManager cross-UID cache as the screen-deterrent
     * gate; cheap (~0 GC churn between writes since loadConfig() is mtime-
     * gated). Returns false on any failure so a stuck flag can never
     * keep the legacy fleet's screen on permanently.
     */
    private static boolean isDilink4CameraMode() {
        try {
            org.json.JSONObject c = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("camera");
            if (c == null) return false;
            return "dilink4".equalsIgnoreCase(c.optString("cameraMode", "default"));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * User toggle (Surveillance → General → "Keep USB powered while parked"):
     * whether the head-unit AP is forced fully awake after ACC OFF. DEFAULT TRUE so the
     * out-of-box behaviour is byte-for-byte identical to the pre-toggle build (and any
     * install whose config predates this key).
     *
     * <p><b>This gates {@link #performSystemWakeUp()} in {@link #enterSentryMode()}.</b>
     * On DiLink 3.0 the USB-port VBUS follows the AP wake state, not the SpecialDevice
     * rail registers (rail writes were proven ineffective on-device). When the toggle is
     * ON we force wakefulness=Awake (USB stays powered, as today). When OFF we skip the
     * wake so the AP can sleep on ACC-OFF and USB drops, saving the 12 V battery.
     *
     * <p><b>Trade-off:</b> skipping the forced wake means features that need the AP
     * continuously awake while parked may not run when the toggle is OFF. The MCU/AVM
     * power writes in {@link #applyPeripheralPowerBatch} still fire, and HAL events still
     * wake the AP on demand, but a continuously-awake assumption (e.g. some keep-alive
     * cadence) won't hold. The toggle defaults ON so this only applies when the user
     * explicitly opts out.
     *
     * <p>Read fresh on the ACC-OFF setup path so a change applies on the NEXT ACC-OFF
     * cycle. Reads the same cross-UID mtime-gated loadConfig() cache the other gates here
     * use (no forceReload churn); returns the safe DEFAULT (true) on any failure so a
     * transient read error can never suppress the wake unexpectedly.
     */
    private static boolean isKeepUsbPowerOnAccOff() {
        try {
            org.json.JSONObject s = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("surveillance");
            if (s == null) return true;
            return s.optBoolean("keepUsbPowerOnAccOff", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * True when the vendor SD card-detect prop reports the card ABSENT. On the
     * affected fleet the USB-bridged SD reader loses power during the ACC-OFF
     * transition; the MCU's card-detect pin then drives sys.byd.isSDExist to
     * 'false' even though a card is physically inserted. The keepalive loop
     * uses this as the "rail is dead" trigger for a reactive MCU+rail
     * re-assert. Deliberately conservative: only an EXPLICIT 'false' counts —
     * an empty/unreadable prop (trim without the vendor prop, reflection
     * blocked) returns false here so those units never wake the MCU on a
     * signal they don't actually have.
     */
    private static boolean isSdRailDead() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String v = (String) get.invoke(null, "sys.byd.isSDExist", "");
            return "false".equalsIgnoreCase(v);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when the user has the SD card configured as the storage target for
     * ANY stream (surveillance / recordings / trips). Gates the reactive
     * SD-rail recovery so an internal-storage-only install never pays the
     * extra MCU wakes for a rail it doesn't use. Reads the same mtime-gated
     * loadConfig() cache as the other keepalive gates; defaults false (skip
     * recovery) on read failure — the periodic 8-min re-assert still covers
     * the rail as a backstop.
     */
    private static boolean isSdConfiguredAsStorageTarget() {
        try {
            org.json.JSONObject st = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("storage");
            if (st == null) return false;
            return "SD_CARD".equals(st.optString("surveillanceStorageType", ""))
                || "SD_CARD".equals(st.optString("recordingsStorageType", ""))
                || "SD_CARD".equals(st.optString("tripsStorageType", ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when CameraDaemon's GPU pipeline is actively consuming camera
     * frames. CameraDaemon refreshes a lease deadline ~8s ahead of now while
     * gpuPipeline.isRunning(); we read it here to skip the keepalive's
     * backlight-off tick during that window.
     *
     * <p>The lease lives in a dedicated sidecar file (a single timestamp), NOT
     * in the shared unified config — reading/writing it must not take the
     * cross-process config lock or bump the config mtime every 4s (that defeated
     * the mtime-gated config cache other subsystems rely on; see
     * CameraDaemon.writeCameraActiveLease). We read the sidecar directly and fall
     * back to the legacy {@code surveillance.cameraActiveUntilMs} key for
     * compatibility with an older CameraDaemon build that still writes it there.
     * A missing/unparseable lease → false, so legacy fleets (that write neither)
     * keep the existing power-save behaviour bit-exact.
     */
    private static final String CAMERA_ACTIVE_LEASE_PATH =
        "/data/local/tmp/camera_active_lease";

    // Upper bound on how far ahead of "now" a lease deadline may legitimately be.
    // CameraDaemon only ever writes now + 8s, so any live lease is <=8s out; we
    // allow a generous 5-min horizon to absorb any future bump to the lease
    // duration. A deadline BEYOND this is not a real lease — it means a stale
    // file persisted across an unclean teardown (crash / kill / power-loss never
    // runs clearCameraActiveLease) and the wall clock then stepped BACKWARD, a
    // documented BYD head-unit condition (see UnifiedConfigManager.isCacheFresh:
    // "BYD head units boot with a wrong clock" / "the RTC stepped backward").
    // Without this bound such a file reads as active FOREVER — with no camera
    // running and nothing to refresh or clear it — pinning the backlight on for a
    // parked car all night. Clamp fails safe to "not active", matching the
    // isDilink4CameraMode() invariant that a stuck flag can never keep the
    // screen on permanently.
    private static final long CAMERA_ACTIVE_LEASE_MAX_HORIZON_MS = 5 * 60_000L;

    private static boolean isLeaseDeadlineLive(long deadlineMs, long nowMs) {
        return deadlineMs > nowMs
                && deadlineMs <= nowMs + CAMERA_ACTIVE_LEASE_MAX_HORIZON_MS;
    }

    private static boolean isCameraPipelineActive() {
        long now = System.currentTimeMillis();
        // Primary: the dedicated sidecar file (cheap, lock-free, no config parse).
        try {
            java.io.File f = new java.io.File(CAMERA_ACTIVE_LEASE_PATH);
            if (f.exists()) {
                byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
                long deadline = Long.parseLong(new String(raw, java.nio.charset.StandardCharsets.US_ASCII).trim());
                return isLeaseDeadlineLive(deadline, now);
            }
        } catch (Throwable ignored) {
            // Torn/absent/unparseable read fails safe to the fallback below.
        }
        // Fallback: legacy config key (older CameraDaemon build). Only reached
        // when the sidecar is absent, so it does not reintroduce the 4s config
        // churn on a current build.
        try {
            org.json.JSONObject s = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("surveillance");
            if (s == null) return false;
            long deadline = s.optLong("cameraActiveUntilMs", 0L);
            return isLeaseDeadlineLive(deadline, now);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean stopSystemKeepAlive(long transitionGeneration) {
        final Thread target;
        synchronized (systemKeepAliveLock) {
            target = systemKeepAliveThread;
            if (target != null
                    && systemKeepAliveGeneration > transitionGeneration) {
                return true;
            }
        }

        if (target != null) {
            log("Stopping System Persistence Service...");
            target.interrupt();
            if (!joinThreadBounded(target, 2000L)) {
                log("WARN: KeepAlive thread is still terminating; ownership retained");
                return false;
            }

            synchronized (systemKeepAliveLock) {
                if (systemKeepAliveThread == target && !target.isAlive()) {
                    systemKeepAliveThread = null;
                    systemKeepAliveGeneration = -1L;
                }
            }
        }
        return true;
    }

    private static boolean joinThreadBounded(Thread target, long timeoutMs) {
        if (target == null || !target.isAlive()) {
            return true;
        }
        try {
            target.join(timeoutMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !target.isAlive();
    }

    /**
     * Checks if Wi-Fi is enabled and forces it ON if not.
     * Equivalent to: Runtime.getRuntime().exec("svc wifi enable");
     */
    private static void ensureWifiEnabled(long transitionGeneration) {
        // Respect a user's explicit "WiFi off" automation / key-mapping: when the radio
        // action set the suppression flag, the keep-alive stands down so the deliberate
        // off state isn't clobbered every 10s tick. With no such rule the flag is false
        // (default) and keep-alive behaves exactly as before. Fail-open (read returns
        // false on error → we re-enable), so a config glitch can never strand WiFi off.
        if (com.overdrive.app.config.UnifiedConfigManager.isWifiKeepAliveSuppressed()) {
            // A HOTSPOT-owned suppression is only valid while the access point is
            // actually up. If the app process that owns the hotspot died with the
            // AP down, nothing else would ever clear the flag and WiFi would stay
            // stranded off — so reconcile it here and fall through to re-enable.
            if (!reconcileStrandedHotspotSuppression()) {
                return;
            }
        }
        if (!isKeepAliveCommitCurrent(transitionGeneration)) {
            return;
        }
        // We use a lightweight check to avoid spamming the shell log
        // running it blindly is safer for persistence.
        ShellResult result = execShellResult(
                CMD_WIFI_ENABLE(),
                DEFAULT_SHELL_TIMEOUT_MS,
                () -> isKeepAliveCommitCurrent(transitionGeneration));
        if (!result.success && !result.canceled) {
            log("Wi-Fi keepalive command failed: " + result.describeFailure());
        }
    }

    /**
     * Cellular counterpart of {@link #ensureWifiEnabled} (issue #209). On some firmware
     * (reported on Seal 2025 / system 2506) the system flips the mobile-data master
     * switch off at ACC-OFF even though the sentry rail hold keeps the modem powered —
     * so parked recording works but cellular connectivity (Tailscale etc.) drops while
     * WiFi survives. Re-asserts each tick:
     *   svc data enable                                — repair the master switch
     *   settings put global mobile_data_always_on 1   — keep the bearer up even when
     *                                                    the framework would drop it
     * Both are idempotent and cheap; they run as one shell invocation to keep the
     * per-tick process spawn count identical to the WiFi path (one each).
     *
     * <p>Only called when the session was ARMED — the user opted in via the
     * "Keep mobile data awake while parked" setting AND mobile data was ON at sentry
     * entry (see the snapshot in the persistence loop). Within an armed session, a user
     * radio rule that turns data off mid-park is honoured via the same suppression-flag
     * pattern as WiFi.
     */
    private static void ensureMobileDataEnabled(long transitionGeneration) {
        // Respect a user's explicit "data off" automation / key-mapping, exactly like
        // the WiFi flag above. Fail-open (read error → false → we re-enable).
        if (com.overdrive.app.config.UnifiedConfigManager.isDataKeepAliveSuppressed()) {
            return;
        }
        if (!isKeepAliveCommitCurrent(transitionGeneration)) {
            return;
        }
        ShellResult result = execShellResult(
                CMD_DATA_ENABLE() + "; " + CMD_DATA_ALWAYS_ON(),
                DEFAULT_SHELL_TIMEOUT_MS,
                () -> isKeepAliveCommitCurrent(transitionGeneration));
        if (!result.success && !result.canceled) {
            log("Mobile-data keepalive command failed: " + result.describeFailure());
        }
    }

    /**
     * One-shot arming probe for the cellular keep-alive: was the mobile-data master
     * switch ON when the parked session began? Reads {@code settings get global
     * mobile_data}. Conservative on ambiguity — a missing/null key reads as OFF, so we
     * never force-enable data on firmware whose state we can't read (unlike WiFi,
     * enabling data can have real cost for the user).
     */
    private static boolean isMobileDataOnAtSentryEntry(long transitionGeneration) {
        ShellResult result = execShellResult(
                CMD_DATA_GET(),
                DEFAULT_SHELL_TIMEOUT_MS,
                () -> isKeepAliveCommitCurrent(transitionGeneration));
        if (!result.success) {
            return false;
        }
        return "1".equals(result.output.trim());
    }

    // ==================== REFLECTION CACHES ====================
    // injectFakeUserActivity is invoked every SYSTEM_KEEPALIVE_INTERVAL_MS
    // (10s) while sentry is active — ~360 calls/hr, ~3000/night. Without
    // caching, each call performed up to three Class.getMethod() lookups
    // (linear method-table scans) on PowerManager for getPowerScreenStatus,
    // userActivity(long), and userActivity(long, boolean). The Method
    // objects are immutable; resolve once and reuse. Volatile for safe
    // publication; idempotent double-resolve race accepted (matches
    // RecordingModeManager.resolveBodyworkReflection pattern).
    //
    // Per-method resolved/failed flags so that a missing optional method
    // (e.g., getPowerScreenStatus on older firmware) doesn't poison the
    // userActivity lookups, and a missing 1-arg userActivity still allows
    // the 2-arg fallback to be cached.
    private static volatile Method pmGetPowerScreenStatusMethod;
    private static volatile boolean pmGetPowerScreenStatusResolved = false;
    private static volatile boolean pmGetPowerScreenStatusFailed = false;

    private static volatile Method pmUserActivity1ArgMethod;
    private static volatile boolean pmUserActivity1ArgResolved = false;
    private static volatile boolean pmUserActivity1ArgFailed = false;

    private static volatile Method pmUserActivity2ArgMethod;
    private static volatile boolean pmUserActivity2ArgResolved = false;
    private static volatile boolean pmUserActivity2ArgFailed = false;

    private static volatile Method pmUserActivity3ArgMethod;
    private static volatile boolean pmUserActivity3ArgResolved = false;
    private static volatile boolean pmUserActivity3ArgFailed = false;

    private static void resolvePmGetPowerScreenStatus() {
        if (pmGetPowerScreenStatusResolved || pmGetPowerScreenStatusFailed) return;
        try {
            pmGetPowerScreenStatusMethod = PowerManager.class.getMethod("getPowerScreenStatus");
            pmGetPowerScreenStatusResolved = true;
        } catch (NoSuchMethodException e) {
            pmGetPowerScreenStatusFailed = true;
        } catch (Exception e) {
            pmGetPowerScreenStatusFailed = true;
        }
    }

    private static void resolvePmUserActivity1Arg() {
        if (pmUserActivity1ArgResolved || pmUserActivity1ArgFailed) return;
        try {
            pmUserActivity1ArgMethod = PowerManager.class.getMethod("userActivity", long.class);
            pmUserActivity1ArgResolved = true;
        } catch (NoSuchMethodException e) {
            pmUserActivity1ArgFailed = true;
        } catch (Exception e) {
            pmUserActivity1ArgFailed = true;
        }
    }

    private static void resolvePmUserActivity2Arg() {
        if (pmUserActivity2ArgResolved || pmUserActivity2ArgFailed) return;
        try {
            pmUserActivity2ArgMethod = PowerManager.class.getMethod("userActivity", long.class, boolean.class);
            pmUserActivity2ArgResolved = true;
        } catch (NoSuchMethodException e) {
            pmUserActivity2ArgFailed = true;
        } catch (Exception e) {
            pmUserActivity2ArgFailed = true;
        }
    }

    private static void resolvePmUserActivity3Arg() {
        if (pmUserActivity3ArgResolved || pmUserActivity3ArgFailed) return;
        try {
            pmUserActivity3ArgMethod = PowerManager.class.getMethod("userActivity", long.class, int.class, int.class);
            pmUserActivity3ArgResolved = true;
        } catch (NoSuchMethodException e) {
            pmUserActivity3ArgFailed = true;
        } catch (Throwable e) {
            pmUserActivity3ArgFailed = true;
        }
    }

    // setBacklightState reflection cache. The probe order is:
    //   1. PowerManager lowercase (turnBacklightOn / turnBacklightOff)
    //   2. PowerManager PascalCase (TurnBacklightOn / TurnBacklightOff)
    //   3. BYDAutoSettingDevice lowercase (turnBacklightOn / turnBacklightOff)
    // The on-variant and off-variant are independent methods on the same
    // class, so each (variant, on/off) tuple has its own scalar volatile
    // fields. The "which class won" is implicit per variant: subsequent
    // calls hit whichever resolved first.
    //
    // First-call probing is preserved: each variant has its own
    // resolved/failed pair, so if lowercase is missing, the PascalCase
    // resolve still runs the first time. Once any one succeeds it short-
    // circuits subsequent lookups for that (variant, on/off) tuple.
    //
    // Scalar volatiles (rather than arrays) used to match the
    // RecordingModeManager.resolveBodyworkReflection memory-publication
    // pattern — boolean[] elements are not volatile in Java's MM.
    private static volatile Method pmBacklightLowerOnMethod;
    private static volatile Method pmBacklightLowerOffMethod;
    private static volatile boolean pmBacklightLowerOnResolved = false;
    private static volatile boolean pmBacklightLowerOnFailed = false;
    private static volatile boolean pmBacklightLowerOffResolved = false;
    private static volatile boolean pmBacklightLowerOffFailed = false;

    private static volatile Method pmBacklightPascalOnMethod;
    private static volatile Method pmBacklightPascalOffMethod;
    private static volatile boolean pmBacklightPascalOnResolved = false;
    private static volatile boolean pmBacklightPascalOnFailed = false;
    private static volatile boolean pmBacklightPascalOffResolved = false;
    private static volatile boolean pmBacklightPascalOffFailed = false;

    private static volatile Class<?> bydSettingDeviceClass;
    private static volatile Method bydSettingGetInstanceMethod;
    private static volatile boolean bydSettingDeviceResolved = false;
    private static volatile boolean bydSettingDeviceFailed = false;

    private static volatile Method bydSettingBacklightOnMethod;
    private static volatile Method bydSettingBacklightOffMethod;
    private static volatile boolean bydSettingBacklightOnResolved = false;
    private static volatile boolean bydSettingBacklightOnFailed = false;
    private static volatile boolean bydSettingBacklightOffResolved = false;
    private static volatile boolean bydSettingBacklightOffFailed = false;

    private static Method getPmBacklightLowerMethod(boolean on, Class<?> pmClass) {
        if (on) {
            if (pmBacklightLowerOnResolved) return pmBacklightLowerOnMethod;
            if (pmBacklightLowerOnFailed) return null;
            try {
                pmBacklightLowerOnMethod = pmClass.getMethod("turnBacklightOn", long.class);
                pmBacklightLowerOnResolved = true;
                return pmBacklightLowerOnMethod;
            } catch (Exception e) {
                pmBacklightLowerOnFailed = true;
                return null;
            }
        } else {
            if (pmBacklightLowerOffResolved) return pmBacklightLowerOffMethod;
            if (pmBacklightLowerOffFailed) return null;
            try {
                pmBacklightLowerOffMethod = pmClass.getMethod("turnBacklightOff", long.class);
                pmBacklightLowerOffResolved = true;
                return pmBacklightLowerOffMethod;
            } catch (Exception e) {
                pmBacklightLowerOffFailed = true;
                return null;
            }
        }
    }

    private static Method getPmBacklightPascalMethod(boolean on, Class<?> pmClass) {
        if (on) {
            if (pmBacklightPascalOnResolved) return pmBacklightPascalOnMethod;
            if (pmBacklightPascalOnFailed) return null;
            try {
                pmBacklightPascalOnMethod = pmClass.getMethod("TurnBacklightOn", long.class);
                pmBacklightPascalOnResolved = true;
                return pmBacklightPascalOnMethod;
            } catch (Exception e) {
                pmBacklightPascalOnFailed = true;
                return null;
            }
        } else {
            if (pmBacklightPascalOffResolved) return pmBacklightPascalOffMethod;
            if (pmBacklightPascalOffFailed) return null;
            try {
                pmBacklightPascalOffMethod = pmClass.getMethod("TurnBacklightOff", long.class);
                pmBacklightPascalOffResolved = true;
                return pmBacklightPascalOffMethod;
            } catch (Exception e) {
                pmBacklightPascalOffFailed = true;
                return null;
            }
        }
    }

    private static void resolveBydSettingDevice() {
        if (bydSettingDeviceResolved || bydSettingDeviceFailed) return;
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            bydSettingDeviceClass = cls;
            bydSettingGetInstanceMethod = getInstance;
            bydSettingDeviceResolved = true;
        } catch (Exception e) {
            bydSettingDeviceFailed = true;
        }
    }

    private static Method getBydSettingBacklightMethod(boolean on) {
        if (!bydSettingDeviceResolved) return null;
        if (on) {
            if (bydSettingBacklightOnResolved) return bydSettingBacklightOnMethod;
            if (bydSettingBacklightOnFailed) return null;
            try {
                bydSettingBacklightOnMethod = bydSettingDeviceClass.getMethod("turnBacklightOn");
                bydSettingBacklightOnResolved = true;
                return bydSettingBacklightOnMethod;
            } catch (Exception e) {
                bydSettingBacklightOnFailed = true;
                return null;
            }
        } else {
            if (bydSettingBacklightOffResolved) return bydSettingBacklightOffMethod;
            if (bydSettingBacklightOffFailed) return null;
            try {
                bydSettingBacklightOffMethod = bydSettingDeviceClass.getMethod("turnBacklightOff");
                bydSettingBacklightOffResolved = true;
                return bydSettingBacklightOffMethod;
            } catch (Exception e) {
                bydSettingBacklightOffFailed = true;
                return null;
            }
        }
    }

    /**
     * Uses Reflection to call PowerManager.userActivity()
     * This mimics the "Fake Touch" to keep CPU awake.
     *
     * CRITICAL: Checks screen status FIRST to avoid exceptions on some BYD firmware
     * where calling userActivity() when screen is OFF causes issues.
     */
    private static boolean injectFakeUserActivity(long transitionGeneration) {
        if (appContext == null) return false;

        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);

            // Android 11+ / DiLink 5 (Snapdragon SA8155P) stealth userActivity
            // Signature: userActivity(long when, int event, int flags)
            // event=0 (USER_ACTIVITY_EVENT_OTHER), flags=1 (USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS)
            resolvePmUserActivity3Arg();
            if (pmUserActivity3ArgResolved) {
                if (!isKeepAliveGenerationCurrent(transitionGeneration)) {
                    return true;
                }
                pmUserActivity3ArgMethod.invoke(
                    pm, android.os.SystemClock.uptimeMillis(), 0, 1);
                log("userActivity(long, 0, NO_CHANGE_LIGHTS) called [Android 11 stealth]");
                return true;
            }

            // CRITICAL: Check screen status for legacy 1-arg method
            // On legacy 1-arg PowerManager, userActivity() turns on the screen,
            // so we skip 1-arg if screen is OFF.
            resolvePmGetPowerScreenStatus();
            if (pmGetPowerScreenStatusResolved) {
                try {
                    int screenStatus = (Integer) pmGetPowerScreenStatusMethod.invoke(pm);
                    if (screenStatus == 0 && !isDilink4CameraMode()) {
                        // For legacy 1-arg fallback, try 2-arg stealth before giving up
                        resolvePmUserActivity2Arg();
                        if (pmUserActivity2ArgResolved) {
                            if (!isKeepAliveGenerationCurrent(transitionGeneration)) {
                                return true;
                            }
                            pmUserActivity2ArgMethod.invoke(pm, android.os.SystemClock.uptimeMillis(), true);
                            log("userActivity(long, boolean) called [stealth fallback]");
                            return true;
                        }
                        log("Screen OFF - skipping legacy 1-arg userActivity");
                        return true;
                    }
                } catch (Exception e) {
                    // Per-call invocation failure (transient binder/access issue);
                    // do NOT mark resolution failed — proceed anyway.
                }
            }

            // DILINK 4 ONLY: prefer the 2-arg variant
            // userActivity(uptime, noChangeLights=true) — "reset the sleep
            // timer, but do NOT touch the lights". This is what the keep-alive
            // loop's own comment has always claimed it used, but the 1-arg
            // branch below returns first, so on any firmware that exposes
            // 1-arg (i.e. all of them) the noChangeLights call was unreachable.
            if (isDilink4CameraMode()) {
                resolvePmUserActivity2Arg();
                if (pmUserActivity2ArgResolved) {
                    if (!isKeepAliveGenerationCurrent(transitionGeneration)) {
                        return true;
                    }
                    pmUserActivity2ArgMethod.invoke(
                        pm, android.os.SystemClock.uptimeMillis(), true);
                    log("userActivity(long, noChangeLights=true) called [dilink4 stealth]");
                    return true;
                }
            }

            // 1-arg version ( style). Original semantics: only
            // NoSuchMethodException falls through to the 2-arg fallback;
            // invocation exceptions bubble to the outer catch. We preserve
            // that by gating only on the resolved flag (NoSuchMethodException
            // is now captured at resolve-time as failed=true) and letting
            // any invoke-time exception propagate.
            resolvePmUserActivity1Arg();
            if (pmUserActivity1ArgResolved) {
                if (!isKeepAliveGenerationCurrent(transitionGeneration)) {
                    return true;
                }
                pmUserActivity1ArgMethod.invoke(pm, android.os.SystemClock.uptimeMillis());
                log("userActivity(long) called");
                return true;
            } else {
                log("userActivity 1-arg not found, trying 2-arg fallback");
            }

            // Fallback: Try 2-arg version (stealth mode - doesn't turn on screen)
            // noChangeLights = true means "Reset the sleep timer, but don't turn on the screen"
            resolvePmUserActivity2Arg();
            if (pmUserActivity2ArgResolved) {
                if (!isKeepAliveGenerationCurrent(transitionGeneration)) {
                    return true;
                }
                pmUserActivity2ArgMethod.invoke(pm, android.os.SystemClock.uptimeMillis(), true);
                log("userActivity(long, boolean) called");
                return true;
            }
            return false;

        } catch (Exception e) {
            log("userActivity error: " + e.getMessage());
            return false;
        }
    }

    private static void immediateWakeUpMcu() {
        log("IMMEDIATE MCU WAKE-UP...");

        if (wakeUpMcu()) {
            log("  MCU wake: OK");
        } else {
            log("  MCU wake: FAILED");
        }
    }

    /**
     * Force MCU wake-up for voltage-triggered charging cycles.
     * Called by VehicleDataListener when battery drops below threshold.
     * Also triggers system wake to ensure full power rail activation.
     */
    private static void forceMcuWakeUp() {
        log("VOLTAGE-TRIGGERED MCU WAKE-UP...");

        // Update wake timestamp
        lastMcuWakeTime = System.currentTimeMillis();

        // Wake the system first (ensures power rails are active) — GATED ON THE
        // "Keep USB powered" TOGGLE, matching enterSentryMode(). The forced AP wake
        // is what keeps USB powered while parked; when the user turned it OFF we must
        // NOT force the AP awake, or the emergency wake silently contradicts their
        // power-save preference. The MCU wake below (DC-DC converter) stays
        // unconditional — it is the actual low-battery emergency action.
        if (isKeepUsbPowerOnAccOff()) {
            performSystemWakeUp();
        } else {
            log("  Keep-USB-power OFF: skipping forced system wake (AP may stay asleep)");
        }

        // Then wake MCU to trigger DC-DC converter
        if (wakeUpMcu()) {
            log("  MCU wake: OK");
        }

        // Double-tap for reliability
        try {
            Thread.sleep(500);
            wakeUpMcu();
        } catch (InterruptedException ignored) {}
    }

    // ==================== ACTIVE VOLTAGE RECOVERY ====================

    /**
     * Schedule the V2 voltage monitor to start 35 s after ACC=OFF.
     * 35 s mirrors the sibling-app entry timer — gives the head unit time
     * to finish its own ACC-OFF housekeeping before we start writing
     * MCU sleep/wake events.
     *
     * <p>Uses {@link java.util.concurrent.ScheduledExecutorService} rather
     * than {@code Handler.postDelayed} because this daemon runs in
     * {@code app_process} with no main Looper —
     * {@code Looper.getMainLooper()} returns null and the Handler
     * constructor NPE'd. The executor is a single-shot, daemon thread.
     */
    private static boolean applyMonitorState(long generation, boolean enabled) {
        synchronized (monitorIoLock) {
            SentryTransitionState latest = latestSentryTransition;
            if (latest.generation != generation
                    || latest.keepAwakeEnabled() != enabled
                    || !monitorReconciler.isDesired(generation, enabled)) {
                return true;
            }

            boolean success = true;
            if (enabled) {
                if (vehicleDataListener == null) {
                    success &= initVehicleDataMonitor();
                }
                if (vehicleDataListener != null) {
                    vehicleDataMonitorOwnerGeneration = generation;
                } else {
                    success = false;
                }
                if (!isMonitorRequestCurrent(generation, true)) {
                    return true;
                }

                try {
                    if (socMonitorOwnerGeneration < 0L) {
                        Context socCtx = new PermissionBypassContext(appContext);
                        com.overdrive.app.power.SocCutoffMonitor.startMonitor(socCtx);
                    }
                    socMonitorOwnerGeneration = generation;
                } catch (Throwable t) {
                    log("SocCutoffMonitor start failed: " + t.getMessage());
                    success = false;
                }
                if (!isMonitorRequestCurrent(generation, true)) {
                    return true;
                }

                if (batteryVoltageMonitorOwnerGeneration >= 0L) {
                    if (batteryVoltageMonitorOwnerGeneration == generation) {
                        cancelBatteryVoltageFutureUpTo(generation);
                    } else {
                        try {
                            com.overdrive.app.power.BatteryVoltageMonitorV2.stopMonitor();
                            batteryVoltageMonitorOwnerGeneration = -1L;
                        } catch (Throwable t) {
                            log("BatteryVoltageMonitorV2 prior-owner stop failed: "
                                    + t.getMessage());
                            success = false;
                        }
                    }
                }
                if (batteryVoltageMonitorOwnerGeneration < 0L) {
                    success &= scheduleBatteryVoltageMonitorV2(generation);
                }
                return success;
            }

            cancelBatteryVoltageFutureUpTo(generation);
            if (batteryVoltageMonitorOwnerGeneration >= 0L
                    && batteryVoltageMonitorOwnerGeneration <= generation) {
                try {
                    com.overdrive.app.power.BatteryVoltageMonitorV2.stopMonitor();
                    batteryVoltageMonitorOwnerGeneration = -1L;
                } catch (Throwable t) {
                    log("BatteryVoltageMonitorV2 stop failed: " + t.getMessage());
                    success = false;
                }
            }
            if (!isMonitorRequestCurrent(generation, false)) {
                return true;
            }

            if (socMonitorOwnerGeneration >= 0L
                    && socMonitorOwnerGeneration <= generation) {
                try {
                    com.overdrive.app.power.SocCutoffMonitor.stopMonitor();
                    socMonitorOwnerGeneration = -1L;
                } catch (Throwable t) {
                    log("SocCutoffMonitor stop failed: " + t.getMessage());
                    success = false;
                }
            }
            if (!isMonitorRequestCurrent(generation, false)) {
                return true;
            }

            if (vehicleDataMonitorOwnerGeneration >= 0L
                    && vehicleDataMonitorOwnerGeneration <= generation) {
                if (stopVehicleDataMonitor()) {
                    vehicleDataMonitorOwnerGeneration = -1L;
                } else {
                    success = false;
                }
            }
            return success;
        }
    }

    private static boolean isMonitorRequestCurrent(
            long generation, boolean enabled) {
        SentryTransitionState latest = latestSentryTransition;
        return latest.generation == generation
                && latest.keepAwakeEnabled() == enabled
                && monitorReconciler.isDesired(generation, enabled);
    }

    private static boolean scheduleBatteryVoltageMonitorV2(
            final long transitionGeneration) {
        boolean scheduled = scheduleBatteryVoltageMonitorStart(
                transitionGeneration,
                BATTERY_VOLTAGE_START_DELAY_MS,
                true);
        if (scheduled) {
            log("scheduleBatteryVoltageMonitorV2: in 35 s for generation "
                    + transitionGeneration);
        }
        return scheduled;
    }

    private static boolean scheduleBatteryVoltageMonitorStart(
            final long transitionGeneration,
            long delayMs,
            boolean resetRetryDelay) {
        synchronized (batteryVoltageScheduleLock) {
            if (!isMonitorRequestCurrent(transitionGeneration, true)) {
                return true;
            }
            if (batteryVoltageFuture != null) {
                if (batteryVoltageFutureGeneration == transitionGeneration) {
                    return true;
                }
                batteryVoltageFuture.cancel(false);
            }
            if (resetRetryDelay) {
                batteryVoltageRetryDelayMs = 1000L;
            }
            try {
                batteryVoltageFutureGeneration = transitionGeneration;
                batteryVoltageFuture = batteryVoltageScheduler.schedule(
                        () -> startDelayedBatteryVoltageMonitor(
                                transitionGeneration),
                        delayMs,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                return true;
            } catch (Throwable scheduleFailure) {
                batteryVoltageFuture = null;
                batteryVoltageFutureGeneration = -1L;
                log("BatteryVoltageMonitorV2 scheduling failed: "
                        + scheduleFailure.getMessage());
                return false;
            }
        }
    }

    private static void cancelBatteryVoltageFutureUpTo(long generation) {
        synchronized (batteryVoltageScheduleLock) {
            if (batteryVoltageFuture != null
                    && batteryVoltageFutureGeneration <= generation) {
                batteryVoltageFuture.cancel(false);
                batteryVoltageFuture = null;
                batteryVoltageFutureGeneration = -1L;
                batteryVoltageRetryDelayMs = 1000L;
            }
        }
    }

    private static void startDelayedBatteryVoltageMonitor(long scheduledGeneration) {
        boolean requestReapply = false;
        synchronized (monitorIoLock) {
            if (!isDelayedBatteryVoltageStartCurrent(scheduledGeneration)) {
                clearBatteryVoltageFutureIfOwned(scheduledGeneration);
                return;
            }

            try {
                com.overdrive.app.power.BatteryVoltageMonitorV2.startMonitor(appContext);
            } catch (Throwable t) {
                log("BatteryVoltageMonitorV2 start failed: " + t.getMessage());
                clearBatteryVoltageFutureIfOwned(scheduledGeneration);
                if (isMonitorRequestCurrent(scheduledGeneration, true)) {
                    long retryDelay;
                    synchronized (batteryVoltageScheduleLock) {
                        retryDelay = batteryVoltageRetryDelayMs;
                        batteryVoltageRetryDelayMs = Math.min(
                                batteryVoltageRetryDelayMs * 2L,
                                BATTERY_VOLTAGE_RETRY_MAX_MS);
                    }
                    if (!scheduleBatteryVoltageMonitorStart(
                            scheduledGeneration, retryDelay, false)) {
                        requestReapply = true;
                    } else {
                        log("BatteryVoltageMonitorV2 retry scheduled in "
                                + retryDelay + " ms for generation "
                                + scheduledGeneration);
                    }
                }
                if (requestReapply) {
                    monitorReconciler.requestReapply(
                            scheduledGeneration, true);
                }
                return;
            }

            // startMonitor can be uninterruptible. It may only commit for the
            // exact generation whose full 35-second delay elapsed.
            if (!isDelayedBatteryVoltageStartCurrent(scheduledGeneration)) {
                try {
                    com.overdrive.app.power.BatteryVoltageMonitorV2.stopMonitor();
                    batteryVoltageMonitorOwnerGeneration = -1L;
                } catch (Throwable t) {
                    log("BatteryVoltageMonitorV2 stale-start rollback failed: "
                            + t.getMessage());
                    batteryVoltageMonitorOwnerGeneration = scheduledGeneration;
                    requestReapply = true;
                }
                clearBatteryVoltageFutureIfOwned(scheduledGeneration);
            } else {
                batteryVoltageMonitorOwnerGeneration = scheduledGeneration;
                clearBatteryVoltageFutureIfOwned(scheduledGeneration);
                synchronized (batteryVoltageScheduleLock) {
                    batteryVoltageRetryDelayMs = 1000L;
                }
            }
        }
        if (requestReapply) {
            ReconcileRequest desired = monitorReconciler.desiredSnapshot();
            monitorReconciler.requestReapply(
                    desired.generation, desired.state);
        }
    }

    private static boolean isDelayedBatteryVoltageStartCurrent(
            long scheduledGeneration) {
        if (!isMonitorRequestCurrent(scheduledGeneration, true)) {
            return false;
        }
        synchronized (batteryVoltageScheduleLock) {
            return batteryVoltageFuture != null
                    && batteryVoltageFutureGeneration == scheduledGeneration;
        }
    }

    private static void clearBatteryVoltageFutureIfOwned(long generation) {
        synchronized (batteryVoltageScheduleLock) {
            if (batteryVoltageFutureGeneration == generation) {
                batteryVoltageFuture = null;
                batteryVoltageFutureGeneration = -1L;
            }
        }
    }

    /**
     * REPLACED — superseded by {@link com.overdrive.app.power.BatteryVoltageMonitorV2}.
     * The old 45 s MCU-pulse loop drained the 12 V faster than it preserved
     * it on a parked car (no alternator load). Calls are now no-ops; the
     * V2 monitor is what does MCU sleep/wake hysteresis.
     */
    private static void startChargingMaintenance() {
        log("startChargingMaintenance: NO-OP (replaced by BatteryVoltageMonitorV2)");
        // Original body retained for reference:
        //   if (isVoltageChargingCycle && mcuChargingThread != null && mcuChargingThread.isAlive()) return;
        //   log("Starting Active Voltage Recovery (Target: " + HEALTHY_VOLTAGE_THRESHOLD + "V)...");
        //   isVoltageChargingCycle = true;
        //   mcuChargingThread = new Thread(() -> { while (isVoltageChargingCycle && running && inSentryMode) { try { forceMcuWakeUp(); Thread.sleep(MCU_CHARGE_PULSE_INTERVAL_MS); } catch (...) {} } }, "McuChargeLoop");
        //   mcuChargingThread.start();
    }

    /** REPLACED — see {@link #startChargingMaintenance}. */
    private static void stopChargingMaintenance() {
        log("stopChargingMaintenance: NO-OP (replaced by BatteryVoltageMonitorV2)");
        // Original body retained for reference:
        //   if (!isVoltageChargingCycle) return;
        //   log("Target voltage reached. Stopping Active Recovery.");
        //   isVoltageChargingCycle = false;
        //   if (mcuChargingThread != null) { mcuChargingThread.interrupt(); mcuChargingThread = null; }
    }

    // ==================== VEHICLE DATA MONITOR INTEGRATION ====================

    /**
     * Initialize VehicleDataMonitor and register listener for voltage-based MCU control.
     * Only initializes the 12V battery power monitor (not all monitors) for sentry mode.
     */
    private static boolean initVehicleDataMonitor() {
        if (appContext == null) {
            log("Cannot init VehicleDataMonitor: no context");
            return false;
        }

        try {
            log("Initializing VehicleDataMonitor for voltage monitoring (battery power only)...");

            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();

            // Initialize with our permissive context - ONLY battery power monitor
            Context permissiveContext = new PermissionBypassContext(appContext);
            monitor.initBatteryPowerOnly(permissiveContext);

            // Create and register our listener for voltage-based MCU control
            vehicleDataListener = new VehicleDataListener() {
                @Override
                public void onBatteryVoltageChanged(BatteryVoltageData data) {
                    // Discrete level changes (0=LOW, 1=NORMAL) - handled by AccListener
                }

                @Override
                public void onBatteryPowerChanged(BatteryPowerData data) {
                    // REPLACED — voltage hysteresis is now owned by
                    // BatteryVoltageMonitorV2 (12.0V wake / 12.5V sleep,
                    // 15-min defer). The 12V isCritical < 10.5V kill switch
                    // is also dropped — the new SoC-driven cutoff
                    // (SocCutoffMonitor, HV battery, default 10%) is the
                    // primary safety net. The replaced body remains for
                    // reference only:
                    //
                    //   if (!inSentryMode || data == null) return;
                    //   double voltage = data.voltageVolts;
                    //   if (!data.isValidRange()) { forceMcuWakeUp(); }
                    //   if (isVoltageChargingCycle) {
                    //       if (voltage >= HEALTHY_VOLTAGE_THRESHOLD) stopChargingMaintenance();
                    //   } else {
                    //       if (voltage <= LOW_VOLTAGE_THRESHOLD) startChargingMaintenance();
                    //   }
                    //   if (data.isCritical && surveillanceEnabled) disableSurveillance();
                    if (data != null) {
                        log("onBatteryPowerChanged " + String.format("%.2f", data.voltageVolts)
                                + "V (handled by BatteryVoltageMonitorV2)");
                    }
                }

                @Override
                public void onChargingStateChanged(ChargingStateData data) {
                    // Not used in sentry mode (battery power only)
                }

                @Override
                public void onChargingPowerChanged(double powerKW) {
                    // Not used in sentry mode (battery power only)
                }

                @Override
                public void onDataUnavailable(String monitorName, String reason) {
                    log("VehicleData unavailable: " + monitorName + " - " + reason);
                }
            };

            monitor.addListener(vehicleDataListener);
            monitor.startBatteryPowerOnly();

            log("VehicleDataMonitor initialized (battery power only)");
            return true;

        } catch (Exception e) {
            log("VehicleDataMonitor init failed: " + e.getMessage());
            vehicleDataListener = null;
            return false;
        }
    }

    /**
     * Stop listening to VehicleDataMonitor (battery power only).
     */
    private static boolean stopVehicleDataMonitor() {
        try {
            log("Removing VehicleDataMonitor listener...");

            if (vehicleDataListener != null) {
                VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
                monitor.removeListener(vehicleDataListener);
                monitor.stopBatteryPowerOnly();
                vehicleDataListener = null;
            }

            // isVoltageChargingCycle = false;  // (state replaced — see V2 monitor)

            log("VehicleDataMonitor listener removed");
            return true;

        } catch (Exception e) {
            log("VehicleDataMonitor cleanup failed: " + e.getMessage());
            return false;
        }
    }

    // ==================== SURVEILLANCE ====================

    private static void enableSurveillance() {
        if (surveillanceEnabled) {
            // Silent return historically — added log so future diagnosis of
            // "why didn't surveillance arm this cycle?" doesn't cost a build
            // cycle. AccSentry's surveillanceEnabled flag is reset to false
            // by exitSentryMode and disableSurveillance, so reaching this
            // line means AccSentry believes a prior IPC succeeded within
            // the current sentry session — re-arming would just churn.
            // See feedback memory: diagnostic-log-paths.
            log("enableSurveillance: already enabled this sentry session — skipping");
            return;
        }

        // RACE CONDITION FIX: Check inSentryMode before attempting to enable.
        // If exitSentryMode() was called (ACC ON) while we were sleeping/retrying,
        // we must NOT enable surveillance.
        if (!inSentryMode) {
            log("enableSurveillance() aborted — no longer in sentry mode (ACC is ON)");
            return;
        }

        // Check if user has enabled surveillance in config
        // If not enabled, skip — don't auto-start on ACC OFF
        try {
            boolean userEnabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
            if (!userEnabled) {
                log("Surveillance NOT enabled in config — skipping auto-start on ACC OFF");
                return;
            }
        } catch (Exception e) {
            log("WARN: Could not read surveillance config: " + e.getMessage() + " — skipping auto-start");
            return;
        }

        log("Enabling surveillance...");

        // Check safe zone — don't start surveillance if parked in a safe zone.
        // Mark as suppressed so onLeftSafeZone() can re-arm if the car is towed out.
        try {
            com.overdrive.app.surveillance.SafeLocationManager safeLocMgr =
                com.overdrive.app.surveillance.SafeLocationManager.getInstance();
            if (safeLocMgr.isFeatureEnabled() && safeLocMgr.isInSafeZone()) {
                log("In safe zone '" + safeLocMgr.getCurrentZoneName() + "' — skipping surveillance");
                CameraDaemon.setSafeZoneSuppressed(true);
                return;
            }
        } catch (Exception e) {
            log("Safe zone check failed: " + e.getMessage() + " — proceeding with surveillance");
        }

        // Check schedule — don't start surveillance outside configured time windows
        try {
            com.overdrive.app.surveillance.SurveillanceSchedule schedule =
                com.overdrive.app.config.UnifiedConfigManager.getSurveillanceSchedule();
            if (schedule != null && schedule.isEnabled() && !schedule.isActiveNow()) {
                log("SCHEDULE: Outside time window (" + schedule.getSummary() + ") — skipping surveillance");
                return;
            }
        } catch (Exception e) {
            log("Schedule check failed: " + e.getMessage() + " — proceeding with surveillance");
        }

        // Retry with backoff — CameraDaemon may not be up yet after boot
        int maxRetries = 10;
        long retryDelayMs = 3000; // Start with 3 seconds

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // RACE CONDITION FIX: Re-check inSentryMode on EVERY retry iteration.
            // exitSentryMode() sets inSentryMode=false, so if ACC turned ON during
            // our sleep between retries, we bail out immediately.
            if (!inSentryMode) {
                log("enableSurveillance() aborted at attempt " + attempt + " — no longer in sentry mode (ACC is ON)");
                return;
            }

            try {
                JSONObject cmd = new JSONObject();
                cmd.put("command", "SET_CONFIG");
                JSONObject config = new JSONObject();
                // NOTE: Do NOT send accOff=true here — it was already sent by
                // notifyAccState(true) in enterSentryMode(). Sending it again
                // causes CameraDaemon.onAccStateChanged to run twice, which
                // double-enables surveillance and resets the V2 pipeline.
                config.put("enabled", true);
                cmd.put("config", config);

                JSONObject response = sendSurveillanceCommandRaw(cmd);
                if (response != null && response.optBoolean("success", false)) {
                    // Final guard: verify we're still in sentry mode AFTER the IPC succeeded.
                    // There's a tiny window where exitSentryMode() could fire between the IPC
                    // send and this check — if so, immediately send a disable to undo it.
                    if (!inSentryMode) {
                        log("Surveillance enabled but ACC turned ON during IPC — immediately disabling");
                        disableSurveillance();
                        return;
                    }
                    surveillanceEnabled = true;
                    log("Surveillance ENABLED (attempt " + attempt + ")");
                    return;
                } else {
                    log("WARN: Surveillance enable failed (attempt " + attempt + "/" + maxRetries + "): " +
                        (response != null ? response.toString() : "null"));
                }
            } catch (Exception e) {
                log("WARN: Surveillance enable failed (attempt " + attempt + "/" + maxRetries + "): " + e.getMessage());
            }

            if (attempt < maxRetries) {
                try {
                    log("Retrying surveillance enable in " + (retryDelayMs / 1000) + "s...");
                    Thread.sleep(retryDelayMs);
                    retryDelayMs = Math.min(retryDelayMs + 2000, 10000); // Increase delay, cap at 10s
                } catch (InterruptedException e) {
                    log("Surveillance retry interrupted");
                    return;
                }
            }
        }

        log("ERROR: Failed to enable surveillance after " + maxRetries + " attempts — CameraDaemon may not be running");
    }

    private static void disableSurveillance() {
        // SOTA: Always attempt to disable when called — CameraDaemon may have enabled
        // surveillance independently (e.g., via the periodic schedule checker or the
        // 45-second fallback timer) without AccSentryDaemon knowing. Skipping based on
        // the local surveillanceEnabled flag would leave surveillance running when the
        // owner returns and unlocks the door.
        // Note: exitSentryMode() already sends notifyAccState(false) which triggers
        // CameraDaemon's full ACC ON path (pipeline.stop()), so this is a belt-and-suspenders
        // call. It's safe to send even if surveillance is already stopped.

        log("Disabling surveillance via IPC (battery protection / session stop)...");

        try {
            // Send stopSurveillance=true to stop motion detection without persisting
            // the preference change. This preserves the user's "surveillance enabled"
            // setting so it auto-starts on the next ACC OFF cycle.
            JSONObject cmd = new JSONObject();
            cmd.put("command", "SET_CONFIG");
            JSONObject config = new JSONObject();
            config.put("stopSurveillance", true);
            cmd.put("config", config);

            JSONObject response = sendSurveillanceCommandRaw(cmd);
            if (response == null
                    || !response.optBoolean("success", false)) {
                log("WARN: Surveillance stop was not acknowledged: "
                        + (response == null ? "null" : response.toString()));
                return;
            }
            surveillanceEnabled = false;
            log("Surveillance STOPPED via IPC (user preference preserved)");
        } catch (Exception e) {
            log("WARN: Failed to disable surveillance via IPC: " + e.getMessage());
        }
    }

    /**
     * Notify CameraDaemon of ACC state change.
     * This updates AccMonitor so HTTP API returns correct acc status.
     *
     * @param accOff true if ACC is OFF, false if ACC is ON
     */

    // ==================== DOOR LOCK GATED SURVEILLANCE — DELETED ====================
    // Door-lock gating is owned by CameraDaemon (it has the cloud MQTT subscriber
    // in-process and BydDataCollector's typed HAL listener). AccSentryDaemon
    // delegates by calling notifyAccState() — see enterSentryMode() / exitSentryMode().

    /**
     * Read the current bodywork power level via reflection.
     *
     * Returns the raw int the HAL gave us (0..3 = real states, 4 = FAKE_OK,
     * 255 = INVALID, anything else = unknown). Returns -1 if reflection
     * itself failed (no HAL, no context). Callers MUST gate sentinel values
     * (4, 255, anything outside 0..3) — see startAccStateHeartbeat().
     *
     * Mirrors the inline reflection used in registerBodyworkListener; kept
     * standalone so the heartbeat doesn't have to re-register a listener
     * just to peek at the current level.
     */
    private static int readPowerLevel() {
        if (appContext == null) return -1;
        try {
            if (!com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                return POWER_LEVEL_OFF;
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> deviceClass = Class.forName(
                "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = deviceClass.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, appContext);
            if (device == null) return -1;
            Method getPowerLevel = deviceClass.getMethod("getPowerLevel");
            return (Integer) getPowerLevel.invoke(device);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void applyHeartbeatPowerLevel(
            int level, long callbackSequenceBeforeRead) {
        if (level == POWER_LEVEL_ACC) {
            log("ACC heartbeat: level=ACC (1) is a transient; "
                    + "skipping publish");
            return;
        }
        if (level != POWER_LEVEL_OFF
                && level != POWER_LEVEL_ON
                && level != POWER_LEVEL_OK) {
            return;
        }

        boolean isAccOff = level < POWER_LEVEL_ON;
        int desiredFlag = isAccOff ? 1 : 0;
        synchronized (accObservationLock) {
            if (accCallbackSequence.get()
                    != callbackSequenceBeforeRead) {
                log("ACC heartbeat: callback arrived during HAL "
                        + "snapshot; discarding stale observation");
                return;
            }
            long heartbeatObservationSequence =
                    accCallbackSequence.incrementAndGet();
            log("ACC observation from heartbeat snapshot: "
                    + powerLevelToString(level)
                    + " (sequence="
                    + heartbeatObservationSequence + ")");

            SentryTransitionState latest =
                    latestSentryTransition;
            if (latest.sentryMode != isAccOff) {
                log("ACC heartbeat: HAL state disagrees with "
                        + "generation " + latest.generation
                        + "; reconciling full transition");
                lastPowerLevel = level;
                if (isAccOff) {
                    enterSentryMode();
                } else {
                    exitSentryMode();
                }
                return;
            }

            lastPowerLevel = level;
            boolean publish = false;
            boolean forced = false;
            synchronized (heartbeatPublishLock) {
                if (desiredFlag
                        == lastHeartbeatPublishedAccOff) {
                    heartbeatDedupRunLength++;
                    if (heartbeatDedupRunLength
                            >= HEARTBEAT_FORCE_REPUBLISH_TICKS) {
                        heartbeatDedupRunLength = 0;
                        publish = true;
                        forced = true;
                    }
                } else {
                    heartbeatDedupRunLength = 0;
                    publish = true;
                }
            }

            if (publish) {
                notifyAccState(
                        latest.generation, isAccOff, true);
                if (forced) {
                    log("ACC heartbeat: forced republish after "
                            + HEARTBEAT_FORCE_REPUBLISH_TICKS
                            + " dedup ticks (~1min) accOff="
                            + isAccOff
                            + "; covers CameraDaemon process restart");
                } else {
                    log("ACC heartbeat: level="
                            + powerLevelToString(level)
                            + " accOff=" + isAccOff
                            + " (state changed since last "
                            + "successful publish)");
                }
            }
        }
    }

    /**
     * Periodic ACC state heartbeat — runs every 30s while the daemon is up
     * and a bodywork listener has been registered. Republishes the current
     * ACC state to CameraDaemon so a CameraDaemon restart mid-drive
     * resyncs within ≤30s instead of waiting for the next ACC edge (which
     * may never come if the user just keeps driving).
     *
     * Skips sentinel HAL readings (FAKE_OK=4, INVALID=255, anything outside
     * 0..3) — only publish definitive states. Same parsing as the
     * onPowerLevelChanged edge handler.
     */
    private static synchronized void startAccStateHeartbeat() {
        if (accHeartbeatThread != null && accHeartbeatThread.isAlive()) {
            return;
        }
        accHeartbeatThread = new Thread(() -> {
            log("ACC state heartbeat started (30s interval)");
            while (running
                    && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException ie) {
                    return;
                }
                try {
                    pumpFallbackReconcilerRetries();
                    long callbackSequenceBeforeRead =
                            currentAccObservationSequence();
                    requestPowerLevelSnapshot(
                            callbackSequenceBeforeRead,
                            null,
                            "heartbeat snapshot",
                            true);
                } catch (Throwable th) {
                    log("ACC heartbeat error: " + th.getMessage());
                }
            }
        }, "AccSentryHeartbeat");
        accHeartbeatThread.setDaemon(true);
        accHeartbeatThread.start();
    }

    /**
     * Notify CameraDaemon of ACC state change.
     * This updates AccMonitor so HTTP API returns correct acc status.
     *
     * @param accOff true if ACC is OFF, false if ACC is ON
     */
    private static void notifyAccState(
            long transitionGeneration, boolean accOff, boolean force) {
        if (force) {
            accNotifyReconciler.requestReapply(transitionGeneration, accOff);
        } else {
            accNotifyReconciler.request(transitionGeneration, accOff);
        }
    }

    private static boolean applyAccStateNotification(
            long transitionGeneration, boolean accOff) {
        SentryTransitionState transition = latestSentryTransition;
        if (transition.generation != transitionGeneration
                || transition.sentryMode != accOff
                || !accNotifyReconciler.isDesired(transitionGeneration, accOff)) {
            log("Discarding stale queued ACC IPC for generation "
                    + transitionGeneration + " accOff=" + accOff);
            return true;
        }

        // Preserve the established camera-arm ordering: for normal parked
        // operation, let the complete rail-enable batch finish first. The wait
        // is bounded so an unavailable HAL cannot suppress the ACC edge.
        if (accOff && !transition.vehicleOnOnly
                && !peripheralPowerReconciler.awaitApplied(
                        transitionGeneration, true, 5000L)) {
            log("ACC OFF IPC proceeding after bounded rail reconciliation wait");
        }

        transition = latestSentryTransition;
        if (transition.generation != transitionGeneration
                || transition.sentryMode != accOff
                || !accNotifyReconciler.isDesired(transitionGeneration, accOff)) {
            log("Discarding stale ACC IPC before socket send for generation "
                    + transitionGeneration);
            return true;
        }

        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", "SET_CONFIG");
            JSONObject config = new JSONObject();
            config.put("accOff", accOff);
            cmd.put("config", config);

            JSONObject response = sendSurveillanceCommandRaw(cmd);
            SentryTransitionState afterSend = latestSentryTransition;
            boolean stillCurrent =
                    afterSend.generation == transitionGeneration
                    && afterSend.sentryMode == accOff
                    && accNotifyReconciler.isDesired(
                            transitionGeneration, accOff);
            if (!stillCurrent) {
                log("ACC IPC completed for stale generation "
                        + transitionGeneration
                        + "; newest state will be sent by reconciler");
                return true;
            }

            if (response == null) {
                resetHeartbeatPublication();
                log("WARN: ACC state IPC returned null — heartbeat will retry");
                return false;
            }
            if (!response.optBoolean("success", false)) {
                resetHeartbeatPublication();
                log("WARN: ACC state IPC reply success=false (error="
                        + response.optString("error", "<no-error-field>")
                        + ") — heartbeat will retry");
                return false;
            }

            synchronized (heartbeatPublishLock) {
                lastHeartbeatPublishedAccOff = accOff ? 1 : 0;
                heartbeatDedupRunLength = 0;
            }
            log("ACC state notified to CameraDaemon: generation="
                    + transitionGeneration + " accOff=" + accOff);
            return true;
        } catch (Exception e) {
            if (latestSentryTransition.generation == transitionGeneration) {
                resetHeartbeatPublication();
            }
            log("WARN: Failed to notify ACC state for generation "
                    + transitionGeneration + ": " + e.getMessage());
            return false;
        }
    }

    private static void resetHeartbeatPublication() {
        synchronized (heartbeatPublishLock) {
            lastHeartbeatPublishedAccOff = -1;
            heartbeatDedupRunLength = 0;
        }
    }

    private static JSONObject sendSurveillanceCommandRaw(JSONObject command) {
        // Bounded retry: 2 attempts, 1s backoff between them. Targets the
        // narrow case where CameraDaemon's IPC server is mid-bind (port not
        // yet listening) — without this, an edge ACC event could land in the
        // ~hundreds of ms gap and silently drop, leaving AccMonitor wedged
        // on stale state. Connection-refused only; other errors fail fast
        // (treat as the existing terminal path).
        for (int attempt = 0; attempt < 2; attempt++) {
            Socket socket = null;
            try {
                // Bound the connect itself to 2s. `new Socket(host, port)`
                // uses the OS default connect timeout (~21s on Android),
                // so a half-stuck CameraDaemon (port bound but accept
                // stalled by HAL/init) would block this single-thread
                // executor for ~42s per IPC across 2 retries — backing
                // up subsequent ACC edges. See prior-audit
                // "sendSurveillanceCommandRaw lacks a connect timeout".
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", SURVEILLANCE_IPC_PORT), 2000);
                socket.setSoTimeout(5000);

                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                writer.println(command.toString());
                String responseLine = reader.readLine();

                return responseLine != null ? new JSONObject(responseLine) : null;
            } catch (java.net.ConnectException ce) {
                if (attempt == 0) {
                    log("IPC connect refused, retry in 1s");
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                log("IPC connect refused after retry: " + ce.getMessage());
                return null;
            } catch (java.net.SocketTimeoutException ste) {
                // Connect or read timed out. The 2s connect cap above
                // turns a half-stuck CameraDaemon listen queue into a
                // SocketTimeoutException; retry once with the same 1s
                // backoff as ConnectException so transient HAL stalls
                // self-heal. Read-side timeouts (5s SoTimeout) also land
                // here — same retry policy is fine since we're
                // idempotent on SET_CONFIG accOff.
                if (attempt == 0) {
                    log("WARN: IPC socket timeout (connect or read), retry in 1s: "
                        + ste.getMessage());
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                log("WARN: IPC socket timeout after retry: " + ste.getMessage());
                return null;
            } catch (Exception e) {
                log("Surveillance IPC error: " + e.getMessage());
                return null;
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    // ==================== TELEGRAM DAEMON AUTO-START ====================

    private static final String TELEGRAM_CONFIG_FILE = null; // Lazy init
    private static String getTelegramConfigFile() { return PATH_TELEGRAM_CONFIG(); }
    private static final String TELEGRAM_DAEMON_PROCESS = "telegram_bot_daemon";

    /**
     * Check if Telegram daemon auto-start on ACC off is enabled.
     *
     * <p>True for EITHER signal: the parked-only {@code autoStartAccOff}
     * toggle, or {@code daemonEnabled} — the cross-UID mirror of the
     * Daemons-screen switch. The latter is what makes "I enabled the Telegram
     * daemon" survive a park: the app-side SharedPreferences that used to be
     * the only record of it is unreadable from this shell-UID process.
     */
    private static Boolean isTelegramAutoStartEnabled() {
        try {
            // Force-reload so a toggle the user just flipped from the app UI
            // (different UID, different mtime tick) is visible immediately
            // rather than after the cache expires.
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            boolean enabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.shouldStartOnAccOff();
            log("Telegram start-on-ACC-off = " + enabled
                + " (autoStartAccOff=" + com.overdrive.app.telegram.config.UnifiedTelegramConfig.isAutoStartAccOff()
                + ", daemonEnabled=" + com.overdrive.app.telegram.config.UnifiedTelegramConfig.isDaemonEnabled() + ")");
            return enabled;
        } catch (Exception e) {
            log("Error reading telegram config: " + e.getMessage());
            return null;
        }
    }

    /**
     * ACC-ON stop gate. Deliberately NOT the same predicate as the start gate:
     * only the parked-only {@code autoStartAccOff} mode means "stop again when
     * the vehicle starts". A daemon the user switched on from the Daemons
     * screen must keep running across ACC-on.
     */
    private static Boolean isTelegramParkedOnlyMode() {
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            boolean parkedOnly = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isAutoStartAccOff()
                    && !com.overdrive.app.telegram.config.UnifiedTelegramConfig.isDaemonEnabled();
            log("Telegram parked-only mode = " + parkedOnly);
            return parkedOnly;
        } catch (Exception e) {
            log("Error reading telegram config: " + e.getMessage());
            return null;
        }
    }

    private static final class TelegramProcessState {
        final boolean daemonRunning;
        final boolean watchdogRunning;

        TelegramProcessState(boolean daemonRunning, boolean watchdogRunning) {
            this.daemonRunning = daemonRunning;
            this.watchdogRunning = watchdogRunning;
        }

        boolean fullySupervised() {
            return daemonRunning && watchdogRunning;
        }

        boolean fullyStopped() {
            return !daemonRunning && !watchdogRunning;
        }
    }

    /** Query daemon and watchdog from one successful ps snapshot. */
    private static TelegramProcessState queryTelegramProcessState(
            long generation, boolean enabled) {
        ShellResult result = runTelegramShell(
                generation,
                enabled,
                "SELF=$$; T=/data/local/tmp/telegram_probe.$$; "
                + "trap 'rm -f \"$T\" 2>/dev/null' 0 HUP INT TERM; "
                + "ps -A -o PID,ARGS > \"$T\" 2>/dev/null || exit 41; "
                + "D=$(awk -v self=\"$SELF\" "
                + "'$1 != self && ($2 == \"telegram_bot_daemon\" "
                + "|| index($0,\"--nice-name=telegram_bot_daemon\") > 0) "
                + "{print 1; exit}' \"$T\") || exit 42; "
                + "W=$(awk -v self=\"$SELF\" "
                + "'$1 != self && index($0,\"/data/local/tmp/start_telegram.sh\") > 0 "
                + "{print 1; exit}' \"$T\") || exit 43; "
                + "printf 'daemon=%s watchdog=%s\\n' \"${D:-0}\" \"${W:-0}\" "
                + "|| exit 44");
        if (!result.success) {
            if (!result.canceled) {
                log("Telegram process query failed: "
                        + result.describeFailure());
            }
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^daemon=([01]) watchdog=([01])$")
                .matcher(result.output.trim());
        if (!matcher.matches()) {
            log("Telegram process query returned malformed output: "
                    + result.output);
            return null;
        }
        return new TelegramProcessState(
                "1".equals(matcher.group(1)),
                "1".equals(matcher.group(2)));
    }

    private static ShellResult runTelegramShell(
            long generation, boolean enabled, String command) {
        return execShellResult(
                command,
                DEFAULT_SHELL_TIMEOUT_MS,
                () -> isSentryTransitionGenerationCurrent(generation)
                        && telegramReconciler.isDesired(
                                generation, enabled));
    }

    private static boolean shouldAbortTelegramReconciliation(
            long generation, boolean enabled, String nextStage) {
        if (!Thread.currentThread().isInterrupted()
                && isSentryTransitionGenerationCurrent(generation)
                && telegramReconciler.isDesired(generation, enabled)) {
            return false;
        }
        log("Telegram reconciliation canceled before " + nextStage
                + " (generation=" + generation + ", enabled=" + enabled + ")");
        return true;
    }

    private static boolean sleepForTelegramReconciliation(
            long generation, boolean enabled, long delayMs, String nextStage) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return !shouldAbortTelegramReconciliation(
                generation, enabled, nextStage);
    }

    /**
     * Start Telegram daemon if auto-start is enabled.
     * Retries once if first attempt fails (APK path detection can be flaky when ACC is off).
     */
    private static boolean startTelegramDaemonIfEnabled(
            long transitionGeneration) {
        if (shouldAbortTelegramReconciliation(
                transitionGeneration, true, "auto-start check")) {
            return true;
        }
        log("Checking if Telegram daemon should auto-start...");

        Boolean autoStartEnabled = isTelegramAutoStartEnabled();
        if (autoStartEnabled == null) {
            return false;
        }
        if (!autoStartEnabled) {
            log("Telegram auto-start not enabled, skipping");
            return true;
        }
        if (shouldAbortTelegramReconciliation(
                transitionGeneration, true, "user-stop check")) {
            return true;
        }

        // Check the durable disable sentinel written by BOTH the UI stop and
        // the Telegram stop — the single cross-UID source of truth. Without
        // it, a daemon the user stopped from the Daemons UI would get silently
        // resurrected on the next ACC-on.
        //
        // BUT the sentinel file is overloaded — three kinds of writer use it:
        //   "disabled by ui"/"disabled by telegram"  → a REAL user stop, honor it
        //   "disabled by ACC-on"                     → our own ACC arbitration pause
        //   "disabled by stopAllDaemons sweep"       → the app-update sweep
        //                                              (AppUpdater.stopAllDaemons)
        //
        // "ACC-on" — we wrote it ourselves on the last ACC-on edge, and
        // launchTelegramDaemon() rm's it before redeploying.
        // "stopAllDaemons sweep" — the app-update sweep, which is write-if-absent
        // (AppUpdater.stopAllDaemons), so this text can ONLY appear when no user
        // stop was already recorded. Both are therefore unambiguous machine
        // stops. Honouring the sweep text as a user stop is what previously left
        // a parked-only bot permanently unable to auto-start after any update,
        // since nothing clears the optional-daemon sentinels post-update.
        //
        // A missing file falls through to auto-start; an unreadable one retries.
        java.io.File telegramSentinel =
            new java.io.File("/data/local/tmp/telegram_bot_daemon.disabled");
        if (telegramSentinel.exists()) {
            String reason = readSentinelReason(telegramSentinel);
            if (reason == null) {
                log("Telegram disable sentinel could not be read; retrying reconciliation");
                return false;
            }
            boolean machineStop = reason != null
                    && (reason.contains("ACC-on") || reason.contains("stopAllDaemons"));
            if (machineStop) {
                log("Telegram disable sentinel is a machine stop (" + reason
                    + "), not a user stop; proceeding to auto-start");
            } else {
                log("Telegram daemon disable sentinel present (user-stopped), not auto-starting");
                return true;
            }
        }
        if (shouldAbortTelegramReconciliation(
                transitionGeneration, true, "process check")) {
            return true;
        }

        TelegramProcessState runningNow = queryTelegramProcessState(
                transitionGeneration, true);
        if (runningNow == null) {
            return shouldAbortTelegramReconciliation(
                    transitionGeneration, true, "process query retry");
        }
        if (runningNow.fullySupervised()) {
            log("Telegram daemon and watchdog already running");
            return true;
        }
        if (runningNow.daemonRunning || runningNow.watchdogRunning) {
            log("Telegram supervision is partial; rebuilding daemon and watchdog");
        }

        // Try up to 2 times (APK path detection can fail when system is still waking up)
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (shouldAbortTelegramReconciliation(
                    transitionGeneration, true, "launch attempt " + attempt)) {
                return true;
            }
            log("Starting Telegram daemon (attempt " + attempt + "/2)...");

            if (attempt > 1) {
                if (!sleepForTelegramReconciliation(
                        transitionGeneration, true, 3000L,
                        "launch retry " + attempt)) {
                    return true;
                }
            }

            try {
                if (!launchTelegramDaemon(transitionGeneration)) {
                    if (shouldAbortTelegramReconciliation(
                            transitionGeneration, true,
                            "launch failure reconciliation")) {
                        return true;
                    }
                    continue;
                }

                // Verify it started
                if (!sleepForTelegramReconciliation(
                        transitionGeneration, true, 2000L,
                        "launch verification")) {
                    return true;
                }

                TelegramProcessState started = queryTelegramProcessState(
                        transitionGeneration, true);
                if (started == null) {
                    if (shouldAbortTelegramReconciliation(
                            transitionGeneration, true,
                            "launch verification retry")) {
                        return true;
                    }
                    continue;
                }
                if (started.fullySupervised()) {
                    log("Telegram daemon and watchdog started successfully (attempt "
                            + attempt + ")");
                    return true;
                } else {
                    log("Telegram launch incomplete after attempt " + attempt
                            + " (daemon=" + started.daemonRunning
                            + ", watchdog=" + started.watchdogRunning + ")");
                    ShellResult logResult = runTelegramShell(
                            transitionGeneration,
                            true,
                            "tail -20 /data/local/tmp/telegrambotdaemon.log "
                                    + "2>/dev/null || true");
                    if (logResult.success
                            && !logResult.output.isEmpty()) {
                        log("Telegram daemon log: " + logResult.output);
                    }
                }
            } catch (Exception e) {
                log("Telegram daemon launch error (attempt " + attempt + "): " + e.getMessage());
            }
        }

        log("WARN: Telegram daemon failed to start after 2 attempts");
        return false;
    }

    /**
     * Read the first line of a disable sentinel so callers can tell a real
     * user stop ("disabled by ui"/"disabled by telegram") from an ACC-on
     * arbitration pause ("disabled by ACC-on"). Returns null if unreadable.
     */
    private static String readSentinelReason(java.io.File sentinel) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(sentinel)))) {
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Launch the Telegram daemon process.
     */
    private static boolean launchTelegramDaemon(long transitionGeneration) {
        if (shouldAbortTelegramReconciliation(
                transitionGeneration, true, "APK discovery")) {
            return true;
        }

        // SOTA: Use pm path to get current APK path (most reliable method)
        // This ensures we always use the correct path even after app updates
        ShellResult pathResult = runTelegramShell(
                transitionGeneration,
                true,
                "pm path com.overdrive.app 2>/dev/null "
                        + "| head -1 | cut -d: -f2");
        if (pathResult.canceled) {
            return true;
        }
        String apkPath = pathResult.success ? pathResult.output : "";

        // Fallback to ls if pm path fails
        if (apkPath.trim().isEmpty()) {
            log("pm path failed, using ls fallback");
            pathResult = runTelegramShell(
                    transitionGeneration,
                    true,
                    "ls /data/app/*/com.overdrive.app*/base.apk "
                            + "2>/dev/null | head -1");
            if (pathResult.canceled) {
                return true;
            }
            apkPath = pathResult.success ? pathResult.output : "";
            if (apkPath.trim().isEmpty()) {
                pathResult = runTelegramShell(
                        transitionGeneration,
                        true,
                        "ls /data/app/com.overdrive.app*/base.apk "
                                + "2>/dev/null | head -1");
                if (pathResult.canceled) {
                    return true;
                }
                apkPath = pathResult.success ? pathResult.output : "";
            }
        }

        if (apkPath.trim().isEmpty()) {
            log("ERROR: Could not find APK path for com.overdrive.app");
            return false;
        }

        apkPath = apkPath.trim();
        log("Using APK path: " + apkPath);
        if (shouldAbortTelegramReconciliation(
                transitionGeneration, true, "sentinel clear")) {
            return true;
        }

        // Clear the disable sentinel — ACC OFF path is explicitly starting
        // the daemon. Without this, the watchdog we're about to deploy
        // would gate-1 → exit 0 immediately because a previous ACC-on
        // stop left the sentinel on disk.
        ShellResult shellResult = runTelegramShell(
                transitionGeneration,
                true,
                "rm -f /data/local/tmp/telegram_bot_daemon.disabled "
                        + "2>/dev/null");
        if (!shellResult.success) {
            return shellResult.canceled;
        }
        if (shouldAbortTelegramReconciliation(
                transitionGeneration, true, "old process cleanup")) {
            return true;
        }

        // Kill any prior watchdog shells before deploying a fresh one. On
        // boot, this path can race the UI's DaemonStartupManager launch
        // — both write start_telegram.sh and `nohup sh` it, leaving two
        // watchdog shells alive. Each spawns the daemon; daemon's
        // killOldInstances kills the other watchdog's daemon; that
        // watchdog respawns; restart loop. The sentinel-gate alone can't
        // catch this because we just cleared the sentinel above.
        // ALSO kill the daemon itself for the same reason — without it,
        // an alive daemon from a stale watchdog would refuse our new
        // daemon's singleton lock.
        //
        // We can't use `pkill -f <pattern>` here: pkill -f matches against
        // /proc/<pid>/cmdline, and execShell wraps each command in
        // `sh -c "<cmd>"`. The wrapper's cmdline contains the literal
        // pattern (or the variable assignment text — pkill matches the
        // bytes regardless), so `pkill -f start_telegram.sh` would
        // SIGKILL its own parent shell. The "P=…; pkill -f \"$P\""
        // variable-hop trick was cargo-culted defense; the assignment
        // text "P=start_telegram.sh" still appears in argv and pkill
        // catches it.
        //
        // Use the ps+awk+kill pattern instead — it filters by PID list
        // and explicitly excludes the calling shell's own PID. This is
        // the same pattern TelegramBotDaemon.killOldInstances uses.
        shellResult = runTelegramShell(
            transitionGeneration,
            true,
            "MY_PID=$$; "
            + "ps -A -o PID,ARGS | grep -F start_telegram.sh | grep -v grep | awk '{print $1}' "
            + "| while read pid; do if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done; "
            + "ps -A -o PID,ARGS | grep -F " + TELEGRAM_DAEMON_PROCESS + " | grep -v grep | awk '{print $1}' "
            + "| while read pid; do if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done"
        );
        if (!shellResult.success) {
            return shellResult.canceled;
        }
        if (!sleepForTelegramReconciliation(
                transitionGeneration, true, 500L, "lock cleanup")) {
            return true;
        }
        shellResult = runTelegramShell(
                transitionGeneration,
                true,
                "rm -f /data/local/tmp/telegram_bot_daemon.lock 2>/dev/null");
        if (!shellResult.success) {
            return shellResult.canceled;
        }
        if (shouldAbortTelegramReconciliation(
                transitionGeneration, true, "watchdog deployment")) {
            return true;
        }

        // Deploy the SAME shell watchdog script the UI uses
        // (DaemonLauncher.Companion.buildTelegramWatchdogScript). Without
        // a watchdog, a transient daemon crash leaves it dead until the
        // next ACC cycle or the next 30s in-process health-check tick
        // (only fires when MainActivity is alive). The watchdog respawns
        // on any non-zero exit, sentinel-gated for legitimate stops.
        String scriptPath = "/data/local/tmp/start_telegram.sh";
        try {
            // proxyArgs="" because AccSentry-launched daemon doesn't have
            // visibility into Android global HTTP proxy from this context.
            // Direct connection — Telegram bot's OkHttp proxies are
            // configured via UnifiedTelegramConfig at runtime.
            java.util.List<String> lines =
                com.overdrive.app.launcher.DaemonLauncher.Companion
                    .buildTelegramWatchdogScript(apkPath, "");
            // Write line-by-line — heredoc through execShell isn't reliable
            // across all toybox builds. Same pattern as
            // DaemonCommandHandler.startCameraDaemonWithWatchdog.
            shellResult = runTelegramShell(
                    transitionGeneration,
                    true,
                    "rm -f " + scriptPath + " 2>/dev/null");
            if (!shellResult.success) {
                return shellResult.canceled;
            }
            boolean first = true;
            for (String line : lines) {
                if (shouldAbortTelegramReconciliation(
                        transitionGeneration, true, "watchdog write")) {
                    return true;
                }
                String escaped = line
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("$", "\\$")
                    .replace("`", "\\`");
                String redirect = first ? " > " : " >> ";
                shellResult = runTelegramShell(
                        transitionGeneration,
                        true,
                        "echo \"" + escaped + "\""
                                + redirect + scriptPath);
                if (!shellResult.success) {
                    return shellResult.canceled;
                }
                first = false;
            }
            if (shouldAbortTelegramReconciliation(
                    transitionGeneration, true, "watchdog chmod")) {
                return true;
            }
            shellResult = runTelegramShell(
                    transitionGeneration,
                    true,
                    "chmod 755 " + scriptPath);
            if (!shellResult.success) {
                return shellResult.canceled;
            }
        } catch (Throwable t) {
            if (shouldAbortTelegramReconciliation(
                    transitionGeneration, true, "watchdog deploy failure")) {
                return true;
            }
            log("Failed to deploy Telegram watchdog: " + t.getMessage()
                + " — supervised launch remains pending");
            return false;
        }
        if (shouldAbortTelegramReconciliation(
                transitionGeneration, true, "watchdog launch")) {
            return true;
        }

        // Run the watchdog. nohup so it survives the AccSentry shell's
        // exit; it execs the daemon binary in a loop.
        String launchCmd = "nohup sh " + scriptPath + " > /dev/null 2>&1 &";
        log("Telegram launch command (watchdog-supervised): " + launchCmd);
        shellResult = runTelegramShell(
                transitionGeneration, true, launchCmd);
        return shellResult.success || shellResult.canceled;
    }

    /**
     * Stop Telegram daemon if it was auto-started.
     */
    private static boolean stopTelegramDaemonIfAutoStarted(
            long transitionGeneration) {
        if (shouldAbortTelegramReconciliation(
                transitionGeneration, false, "parked-only check")) {
            return true;
        }
        Boolean parkedOnlyMode = isTelegramParkedOnlyMode();
        if (parkedOnlyMode == null) {
            return false;
        }
        if (!parkedOnlyMode) {
            log("Telegram not in parked-only mode, not stopping");
            return true;
        }
        if (shouldAbortTelegramReconciliation(
                transitionGeneration, false, "process check")) {
            return true;
        }

        TelegramProcessState runningNow = queryTelegramProcessState(
                transitionGeneration, false);
        if (runningNow == null) {
            return shouldAbortTelegramReconciliation(
                    transitionGeneration, false, "process query retry");
        }
        if (!runningNow.daemonRunning) {
            log("Telegram daemon not running; stopping watchdog state anyway");
        }

        // ACC-driven stop must plant the disable sentinel BEFORE pkill,
        // OTHERWISE the start_telegram.sh watchdog (deployed by the UI
        // launchTelegramDaemon path) will respawn the daemon within 60s
        // — exactly the loop ACC-on is meant to break. The sentinel
        // signals the watchdog to gate-1 → exit 0 cleanly. ACC-off path
        // (launchTelegramDaemon below) clears the sentinel before
        // re-deploying.
        //
        // Also rm the watchdog script so any orphan watchdog dies; lock
        // rm comes AFTER pkill+settle to prevent the lockfile resurrection
        // race. Mirrors DaemonLauncher.stopTelegramDaemon pattern.
        if (shouldAbortTelegramReconciliation(
                transitionGeneration, false, "stop sentinel")) {
            return true;
        }
        log("Stopping Telegram daemon (vehicle on)...");
        ShellResult shellResult = runTelegramShell(
            transitionGeneration,
            false,
            "S=/data/local/tmp/telegram_bot_daemon.disabled; T=\"$S.tmp.$$\"; "
            + "R=$(head -1 \"$S\" 2>/dev/null); "
            + "case \"$R\" in "
            + "'disabled by ui'*|'disabled by telegram'*|'disabled by user'*) :;; "
            + "*) printf 'disabled by ACC-on at %s\\n' \"$(date)\" > \"$T\" "
            + "&& chmod 666 \"$T\" && mv -f \"$T\" \"$S\";; esac; "
            + "rm -f /data/local/tmp/start_telegram.sh"
        );
        if (!shellResult.success) {
            return shellResult.canceled;
        }
        if (shouldAbortTelegramReconciliation(
                transitionGeneration, false, "process stop")) {
            return true;
        }
        // Kill the watchdog shell first so it can't respawn the daemon
        // between our pkill and the lock-rm. The sentinel-gate on its
        // next iteration would also stop it, but the watchdog's outer
        // 10-60 s sleep would let it spawn a daemon before noticing.
        //
        // pkill -f matches the FULL argv. The "P=…; pkill -f \"$P\""
        // variable-hop trick was cargo-cult: the assignment text is
        // also in argv and toybox pkill matches it. ps+awk+kill
        // filters by PID list and excludes the calling shell's own
        // PID via $$ — the same pattern TelegramBotDaemon's
        // killOldInstances uses. Mirror of the launchTelegramDaemon
        // path (line 2620) that we already fixed.
        shellResult = runTelegramShell(
            transitionGeneration,
            false,
            "MY_PID=$$; "
            + "ps -A -o PID,ARGS | grep -F start_telegram.sh | grep -v grep | awk '{print $1}' "
            + "| while read pid; do if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done; "
            + "ps -A -o PID,ARGS | grep -F " + TELEGRAM_DAEMON_PROCESS + " | grep -v grep | awk '{print $1}' "
            + "| while read pid; do if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done"
        );
        if (!shellResult.success) {
            return shellResult.canceled;
        }
        // Settle so SIGKILL'd daemon releases its lockfile before we rm
        // it (otherwise the daemon's still-flushing JVM rewrites the
        // lock between our rm and its actual death).
        if (!sleepForTelegramReconciliation(
                transitionGeneration, false, 1000L, "lock cleanup")) {
            return true;
        }
        shellResult = runTelegramShell(
                transitionGeneration,
                false,
                "rm -f /data/local/tmp/telegram_bot_daemon.lock 2>/dev/null");
        if (!shellResult.success) {
            return shellResult.canceled;
        }
        TelegramProcessState stillRunning = queryTelegramProcessState(
                transitionGeneration, false);
        if (stillRunning == null) {
            return shouldAbortTelegramReconciliation(
                    transitionGeneration, false, "stop verification retry");
        }
        if (!stillRunning.fullyStopped()) {
            log("WARN: Telegram processes remain after stop (daemon="
                    + stillRunning.daemonRunning + ", watchdog="
                    + stillRunning.watchdogRunning + ")");
            return false;
        }
        log("Telegram daemon stopped (sentinel-disabled)");
        return true;
    }

    // ==================== CONTEXT HELPERS ====================

    private static Context getSystemContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = resolveActivityThread(activityThreadClass);
            if (activityThread == null) return null;
            Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            return (Context) getSystemContext.invoke(activityThread);
        } catch (Exception e) {
            log("getSystemContext failed: " + e.getMessage());
            return null;
        }
    }

    private static Context createAppContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = resolveActivityThread(activityThreadClass);

            if (activityThread == null) {
                log("createAppContext: all strategies failed, using null-safe fallback");
                return new PermissionBypassContext(null);
            }

            Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            Context systemContext = (Context) getSystemContext.invoke(activityThread);
            if (systemContext == null) return new PermissionBypassContext(null);

            String packageName = APP_PACKAGE_NAME();
            Context appContext = systemContext.createPackageContext(packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);

            return new PermissionBypassContext(appContext);

        } catch (Exception e) {
            log("createAppContext failed: " + e.getMessage());
            return new PermissionBypassContext(null);
        }
    }

    private static Object resolveActivityThread(Class<?> activityThreadClass) {
        try {
            Method cur = activityThreadClass.getMethod("currentActivityThread");
            Object at = cur.invoke(null);
            if (at != null) return at;
        } catch (Exception ignored) {}

        final Object[] result = new Object[1];
        try {
            Thread t = new Thread(() -> {
                try {
                    Method systemMain = activityThreadClass.getMethod("systemMain");
                    result[0] = systemMain.invoke(null);
                } catch (Exception ignored) {}
            }, "SystemMainInit");
            t.setDaemon(true);
            t.start();
            t.join(10_000);
            if (t.isAlive()) {
                log("resolveActivityThread: systemMain timed out");
                t.interrupt();
                try {
                    Method cur = activityThreadClass.getMethod("currentActivityThread");
                    Object at = cur.invoke(null);
                    if (at != null) return at;
                } catch (Exception ignored) {}
            } else if (result[0] != null) {
                return result[0];
            }
        } catch (Exception ignored) {}

        try {
            try { android.os.Looper.prepareMainLooper(); } catch (Exception ignored) {}
            java.lang.reflect.Constructor<?> ctor = activityThreadClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object at = ctor.newInstance();
            try {
                java.lang.reflect.Field f = activityThreadClass.getDeclaredField("sCurrentActivityThread");
                f.setAccessible(true);
                f.set(null, at);
            } catch (Exception ignored) {}
            log("resolveActivityThread: manual creation succeeded");
            return at;
        } catch (Exception e) {
            log("resolveActivityThread: manual creation failed: " + e.getMessage());
        }

        return null;
    }

    private static class PermissionBypassContext extends android.content.ContextWrapper {
        public PermissionBypassContext(Context base) { super(base); }

        @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
        @Override public void enforcePermission(String permission, int pid, int uid, String message) {}
        @Override public void enforceCallingPermission(String permission, String message) {}
        @Override public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public Context getApplicationContext() {
            try { return super.getApplicationContext(); } catch (NullPointerException e) { return this; }
        }
        @Override public String getPackageName() {
            try { return super.getPackageName(); } catch (NullPointerException e) { return APP_PACKAGE_NAME(); }
        }
        @Override public Object getSystemService(String name) {
            try { return super.getSystemService(name); } catch (NullPointerException e) { return null; }
        }
        @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
            try { return super.getApplicationInfo(); } catch (NullPointerException e) { return new android.content.pm.ApplicationInfo(); }
        }
        @Override public android.content.ContentResolver getContentResolver() {
            try { return super.getContentResolver(); } catch (NullPointerException e) { return null; }
        }
        @Override public android.content.res.Resources getResources() {
            try { return super.getResources(); } catch (NullPointerException e) { return null; }
        }
        @Override public Context createPackageContext(String packageName, int flags) {
            try { return super.createPackageContext(packageName, flags); } catch (Exception e) { return this; }
        }
    }

    // ==================== INSTRUMENT DEVICE TEST ====================

    /**
     * Tests BYDAutoInstrumentDevice and BYDAutoStatisticDevice for charging data.
     */
    private static void testInstrumentDevice() {
        log("=== TESTING CHARGING DATA SOURCES ===");

        if (appContext == null) {
            log("ERROR: No context available");
            return;
        }

        try {
            Context permissiveContext = new PermissionBypassContext(appContext);

            // Test InstrumentDevice
            log("--- BYDAutoInstrumentDevice ---");
            Class<?> instrClazz = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Method getInstrInstance = instrClazz.getMethod("getInstance", Context.class);
            Object instrDevice = getInstrInstance.invoke(null, permissiveContext);

            if (instrDevice != null) {
                String[] instrGetters = {
                    "getExternalChargingPower",
                    "getChargePower",
                    "getChargePercent",
                    "getChargeRestTime",
                    "getOutCarTemperature"
                };

                for (String methodName : instrGetters) {
                    testGetter(instrClazz, instrDevice, methodName);
                }
            }

            // Test StatisticDevice ( uses this for SOC)
            log("--- BYDAutoStatisticDevice ---");
            Class<?> statClazz = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
            Method getStatInstance = statClazz.getMethod("getInstance", Context.class);
            Object statDevice = getStatInstance.invoke(null, permissiveContext);

            if (statDevice != null) {
                String[] statGetters = {
                    "getElecPercentageValue",      // SOC % ( uses this!)
                    "getFuelPercentageValue",      // Fuel %
                    "getTotalElecConValue",        // Total kWh consumed
                    "getTotalFuelConValue",        // Total fuel consumed
                    "getEVMileageValue",           // EV range
                    "getWaterTemperature"          // Coolant temp
                };

                for (String methodName : statGetters) {
                    testGetter(statClazz, statDevice, methodName);
                }

                // Test getMileageNumber(int type)
                try {
                    Method m = statClazz.getMethod("getMileageNumber", int.class);
                    for (int type = 0; type <= 3; type++) {
                        Object result = m.invoke(statDevice, type);
                        log("  getMileageNumber(" + type + ") = " + result);
                    }
                } catch (Exception e) {
                    log("  getMileageNumber(int) = [ERROR]");
                }
            }

            // Test EnergyDevice
            log("--- BYDAutoEnergyDevice ---");
            Class<?> energyClazz = Class.forName("android.hardware.bydauto.energy.BYDAutoEnergyDevice");
            Method getEnergyInstance = energyClazz.getMethod("getInstance", Context.class);
            Object energyDevice = getEnergyInstance.invoke(null, permissiveContext);

            if (energyDevice != null) {
                String[] energyGetters = {
                    "getElecPercentageValue",
                    "getEnergyMode",
                    "getOperationMode",
                    "getEVMileageValue"
                };

                for (String methodName : energyGetters) {
                    testGetter(energyClazz, energyDevice, methodName);
                }
            }

            log("=== END CHARGING DATA TEST ===");

        } catch (Exception e) {
            log("ERROR testing devices: " + e.getMessage());
        }
    }

    private static void testGetter(Class<?> clazz, Object device, String methodName) {
        try {
            Method method = clazz.getMethod(methodName);
            Object result = method.invoke(device);

            String resultStr;
            if (result == null) {
                resultStr = "null";
            } else if (result instanceof int[]) {
                resultStr = java.util.Arrays.toString((int[]) result);
            } else if (result instanceof double[]) {
                resultStr = java.util.Arrays.toString((double[]) result);
            } else {
                resultStr = result.toString();
            }

            log("  " + methodName + "() = " + resultStr);
        } catch (NoSuchMethodException e) {
            log("  " + methodName + "() = [NOT FOUND]");
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            log("  " + methodName + "() = [ERROR: " + msg + "]");
        }
    }

    // ==================== SHELL EXECUTION ====================

    /**
     * "Vehicle ON only" parked reaper. Writes a detached shell script and launches it with
     * {@code nohup ... &} so it reparents to init and OUTLIVES this acc_sentry_daemon (which
     * it kills). Sequence:
     *   1. (Re-)plant the parked-shutdown marker (chmod 666) — the single arbiter that makes
     *      every watchdog gate-exit and every app-side rebuild path stand down.
     *   2. rm the watchdog start-scripts + cam_watchdog.pid so nothing can re-exec.
     *   3. sleep GRACE so (a) CameraDaemon's own parkTerminate (graceful H2 close, up to
     *      ~15s SD work) finishes and (b) any live watchdog loop re-checks the marker and
     *      exit 0's (≤2s). The marker is already set, so nothing rebuilds during the grace.
     *   4. Backstop psAwkKill any survivor (watchdog shells + daemon processes) — excludes
     *      the reaper's own PID; no pattern matches the reaper script name.
     *   5. rm stale *_daemon.lock, then self-delete. Does NOT clear the marker (that is the
     *      whole point — it stays until the ACC-on edge clears it).
     * This mirrors UpdateLifecycle.hardResetDaemons's proven kill cascade, swapped to the
     * parked marker and with no end-of-run marker clear.
     */
    private static String readBootIdentity() {
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("/proc/sys/kernel/random/boot_id"));
            String value = new String(
                    raw, java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (!value.isEmpty()) {
                return value.replaceAll("[^A-Za-z0-9_-]", "");
            }
        } catch (Throwable ignored) {}
        try {
            java.util.List<String> statLines = java.nio.file.Files.readAllLines(
                    java.nio.file.Paths.get("/proc/stat"),
                    java.nio.charset.StandardCharsets.US_ASCII);
            for (String line : statLines) {
                if (line != null && line.startsWith("btime ")) {
                    return ("btime-" + line.substring(6).trim())
                            .replaceAll("[^A-Za-z0-9_-]", "");
                }
            }
        } catch (Throwable ignored) {}
        return "unknown-boot";
    }

    private static String createProcessInstanceNonce() {
        return BOOT_IDENTITY + "-" + android.os.Process.myPid() + "-"
                + Long.toUnsignedString(System.nanoTime(), 36) + "-"
                + java.util.UUID.randomUUID().toString();
    }

    private static String readProcessStartIdentity(int pid) {
        if (pid <= 0) {
            return null;
        }
        try {
            String stat = new String(
                    java.nio.file.Files.readAllBytes(
                            java.nio.file.Paths.get(
                                    "/proc/" + pid + "/stat")),
                    java.nio.charset.StandardCharsets.US_ASCII);
            int commandEnd = stat.lastIndexOf(')');
            if (commandEnd < 0 || commandEnd + 2 >= stat.length()) {
                return null;
            }
            String[] fields = stat.substring(commandEnd + 2)
                    .trim().split("\\s+");
            // The suffix starts at field 3; starttime is field 22.
            return fields.length > 19 ? fields[19] : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String parkReaperStateToken(SentryTransitionState state) {
        String kind = state.sentryMode
                ? (state.vehicleOnOnly ? "OFF" : "DISARMED")
                : "ON";
        return kind + ":" + PROCESS_INSTANCE_NONCE + ":" + state.generation;
    }

    private static String parkReaperArmToken(String stateToken) {
        return "ARM:" + stateToken;
    }

    private static String parkReaperCancelToken(String stateToken) {
        return "CANCEL:" + stateToken;
    }

    private static final BoundedLatestCallLane boundedParkReaperIoLane =
            new BoundedLatestCallLane("ParkReaperIo");

    private static boolean isParkReaperTokenRequestCurrent(
            long generation, boolean active) {
        SentryTransitionState latest = latestSentryTransition;
        return latest.generation == generation
                && (latest.sentryMode && latest.vehicleOnOnly) == active
                && parkReaperTokenReconciler.isDesired(
                        generation, active);
    }

    private static void requestLatestParkReaperTokenWrite() {
        SentryTransitionState latest = latestSentryTransition;
        parkReaperTokenReconciler.requestReapply(
                latest.generation,
                latest.sentryMode && latest.vehicleOnOnly);
    }

    /**
     * Token publication runs outside ACC observation locks. The same lease is
     * used by the detached reaper for every destructive action, making a token
     * change and an action linearisable: whichever acquires the lease first
     * completes before the other can validate ownership.
     */
    private static boolean publishParkReaperTransition(
            long generation, boolean active) {
        if (!isParkReaperTokenRequestCurrent(generation, active)) {
            return true;
        }
        ShellOwnership ownership =
                () -> isParkReaperTokenRequestCurrent(
                        generation, active);
        return runBoundedParkReaperIo(
                "publish generation " + generation,
                ownership,
                () -> publishParkReaperTransitionUnderLease(
                        generation, active),
                AccSentryDaemon::requestLatestParkReaperTokenWrite);
    }

    private static boolean runBoundedParkReaperIo(
            String label,
            ShellOwnership ownership,
            BoundedCall<Boolean> call,
            Runnable staleCompensation) {
        BoundedCallResult<Boolean> result =
                boundedParkReaperIoLane.invoke(
                        label, 5000L, ownership, call,
                        staleCompensation);
        if (result.failure != null) {
            log("Park-reaper I/O failed for " + label + ": "
                    + result.failure.getMessage());
        }
        return result.completed
                && result.failure == null
                && Boolean.TRUE.equals(result.value);
    }

    private static boolean publishParkReaperTransitionUnderLease(
            long generation, boolean active) {
        ParkReaperLease lease = acquireParkReaperLease();
        if (lease == null) {
            return false;
        }
        try {
            if (!isParkReaperTokenRequestCurrent(
                    generation, active)) {
                return true;
            }
            SentryTransitionState latest = latestSentryTransition;
            String stateToken = parkReaperStateToken(latest);
            if (active) {
                boolean stateWritten = writeParkReaperToken(
                        PARK_REAPER_STATE_PATH, stateToken);
                return stateWritten && writeParkReaperToken(
                        PARK_REAPER_CONTROL_PATH,
                        parkReaperArmToken(stateToken));
            }
            boolean canceled = writeParkReaperToken(
                    PARK_REAPER_CONTROL_PATH,
                    parkReaperCancelToken(stateToken));
            boolean stateWritten = writeParkReaperToken(
                    PARK_REAPER_STATE_PATH, stateToken);
            return canceled && stateWritten;
        } finally {
            lease.close();
        }
    }

    private static boolean publishParkReaperBootCancellation() {
        SentryTransitionState latest = latestSentryTransition;
        parkReaperTokenReconciler.requestReapply(
                latest.generation, false);
        return parkReaperTokenReconciler.awaitApplied(
                latest.generation, false, 5000L);
    }

    private static boolean ensureParkReaperTransitionPublished(long generation) {
        SentryTransitionState latest = latestSentryTransition;
        if (latest.generation != generation) {
            return true;
        }
        boolean active = latest.sentryMode && latest.vehicleOnOnly;
        parkReaperTokenReconciler.request(
                generation, active);
        return parkReaperTokenReconciler.awaitApplied(
                generation, active, 5000L);
    }

    private static boolean isParkReaperRequestCurrent(
            long generation, boolean active) {
        SentryTransitionState latest = latestSentryTransition;
        return latest.generation == generation
                && (latest.sentryMode && latest.vehicleOnOnly) == active
                && parkReaperReconciler.isDesired(generation, active);
    }

    private static boolean writeParkReaperToken(String path, String token) {
        java.io.File target = new java.io.File(path);
        java.io.File temporary = new java.io.File(
                path + ".tmp." + PROCESS_INSTANCE_NONCE + "."
                        + Thread.currentThread().getId() + "."
                        + System.nanoTime());
        try {
            byte[] bytes = (token + "\n").getBytes(
                    java.nio.charset.StandardCharsets.US_ASCII);
            try (java.io.FileOutputStream output =
                         new java.io.FileOutputStream(temporary, false)) {
                output.write(bytes);
                output.flush();
            }
            temporary.setReadable(true, false);
            temporary.setWritable(true, false);
            try {
                java.nio.file.Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                java.nio.file.Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            target.setReadable(true, false);
            target.setWritable(true, false);
            return token.equals(readSmallAsciiFile(target));
        } catch (Throwable t) {
            try { java.nio.file.Files.deleteIfExists(temporary.toPath()); }
            catch (Throwable ignored) {}
            log("WARNING: failed to write park-reaper token " + path
                    + ": " + t.getMessage());
            return false;
        }
    }

    private static final class ParkReaperLease implements AutoCloseable {
        private final String ownerToken;
        private boolean closed;

        ParkReaperLease(String ownerToken) {
            this.ownerToken = ownerToken;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                java.io.File owner =
                        new java.io.File(PARK_REAPER_LEASE_OWNER_PATH);
                String current = readSmallAsciiFile(owner);
                if (!ownerToken.equals(current)) {
                    return;
                }
                owner.delete();
                new java.io.File(PARK_REAPER_LEASE_PATH).delete();
            } catch (Throwable ignored) {}
        }
    }

    private static ParkReaperLease acquireParkReaperLease() {
        long deadline = android.os.SystemClock.elapsedRealtime() + 2000L;
        String ownerToken = "v3|java|" + android.os.Process.myPid()
                + "|" + (PROCESS_START_IDENTITY == null
                        ? "unknown" : PROCESS_START_IDENTITY)
                + "|" + BOOT_IDENTITY
                + "|" + PROCESS_INSTANCE_NONCE
                + "|" + System.nanoTime();
        java.io.File leaseDir = new java.io.File(PARK_REAPER_LEASE_PATH);
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                java.nio.file.Files.createDirectory(leaseDir.toPath());
                if (!writeParkReaperToken(
                        PARK_REAPER_LEASE_OWNER_PATH, ownerToken)) {
                    leaseDir.delete();
                    return null;
                }
                return new ParkReaperLease(ownerToken);
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                reclaimDeadParkReaperLease();
            } catch (Throwable failure) {
                log("WARNING: park-reaper lease acquisition failed: "
                        + failure.getMessage());
                return null;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        log("WARNING: timed out acquiring park-reaper lease");
        return null;
    }

    private static void reclaimDeadParkReaperLease() {
        try {
            java.io.File leaseDir =
                    new java.io.File(PARK_REAPER_LEASE_PATH);
            java.io.File ownerFile =
                    new java.io.File(PARK_REAPER_LEASE_OWNER_PATH);
            String owner = readSmallAsciiFile(ownerFile);
            long leaseAgeMs = Math.max(
                    0L, System.currentTimeMillis()
                            - leaseDir.lastModified());
            if (owner == null || owner.isEmpty()) {
                if (leaseAgeMs < 30_000L) {
                    return;
                }
            } else {
                String[] parts = owner.split("\\|", -1);
                boolean recognized = false;
                boolean sameBoot = false;
                int ownerPid = -1;
                String expectedStart = "";
                if (parts.length >= 6 && "v3".equals(parts[0])) {
                    recognized = true;
                    ownerPid = parsePositiveInt(parts[2]);
                    expectedStart = parts[3];
                    sameBoot = BOOT_IDENTITY.equals(parts[4]);
                } else if (parts.length >= 5 && "v2".equals(parts[0])) {
                    recognized = true;
                    ownerPid = parsePositiveInt(parts[2]);
                    expectedStart = parts[3];
                    // v2 embedded the boot id in its process nonce/state.
                    sameBoot = !"unknown-boot".equals(BOOT_IDENTITY)
                            && parts[4].contains(BOOT_IDENTITY);
                }

                if (recognized && sameBoot && ownerPid > 0) {
                    String actualStart =
                            readProcessStartIdentity(ownerPid);
                    boolean expectedKnown = expectedStart != null
                            && !expectedStart.isEmpty()
                            && !"unknown".equals(expectedStart);
                    if (expectedKnown
                            && expectedStart.equals(actualStart)) {
                        return;
                    }
                    if ((!expectedKnown || actualStart == null)
                            && new java.io.File(
                                    "/proc/" + ownerPid).exists()
                            && leaseAgeMs < 30_000L) {
                        return;
                    }
                } else if (!recognized && leaseAgeMs < 30_000L) {
                    // Unknown legacy owners get one bounded action window.
                    return;
                }
                if (!owner.equals(readSmallAsciiFile(ownerFile))) {
                    return;
                }
            }
            ownerFile.delete();
            leaseDir.delete();
        } catch (Throwable ignored) {}
    }

    private static int parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String readSmallAsciiFile(java.io.File file) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(
                    bytes,
                    java.nio.charset.StandardCharsets.US_ASCII).trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean deleteFileIfPresent(String path) {
        try {
            java.io.File file = new java.io.File(path);
            return !file.exists() || file.delete();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasLiveParkReaperExecution() {
        java.io.File runDir = new java.io.File(PARK_REAPER_RUN_PATH);
        java.io.File ownerFile =
                new java.io.File(PARK_REAPER_RUN_OWNER_PATH);
        String owner = readSmallAsciiFile(ownerFile);
        if (owner != null && !owner.isEmpty()) {
            String[] parts = owner.split("\\|", -1);
            if (parts.length >= 6 && "v1".equals(parts[0])) {
                int ownerPid = parsePositiveInt(parts[1]);
                String expectedStart = parts[2];
                String ownerBoot = parts[3];
                if (BOOT_IDENTITY.equals(ownerBoot) && ownerPid > 0) {
                    String actualStart =
                            readProcessStartIdentity(ownerPid);
                    long now = System.currentTimeMillis();
                    long modifiedAt = runDir.lastModified();
                    long ageMs = modifiedAt > now
                            ? Long.MAX_VALUE : Math.max(
                                    0L, now - modifiedAt);
                    if (expectedStart != null
                            && !expectedStart.isEmpty()
                            && !"unknown".equals(expectedStart)
                            && expectedStart.equals(actualStart)) {
                        return true;
                    }
                    if ((expectedStart == null
                                    || expectedStart.isEmpty()
                                    || "unknown".equals(expectedStart)
                                    || actualStart == null)
                            && new java.io.File(
                                    "/proc/" + ownerPid).exists()
                            && ageMs < 95_000L) {
                        return true;
                    }
                }
            } else {
                // An unknown owner is treated as active for one hard-deadline
                // window rather than risking concurrent destructive workers.
                long now = System.currentTimeMillis();
                long modifiedAt = runDir.lastModified();
                long ageMs = modifiedAt > now
                        ? Long.MAX_VALUE : Math.max(
                                0L, now - modifiedAt);
                if (ageMs < 95_000L) {
                    return true;
                }
            }
            if (!owner.equals(readSmallAsciiFile(ownerFile))) {
                return true;
            }
            ownerFile.delete();
            runDir.delete();
        } else if (runDir.exists()) {
            // mkdir precedes owner publication by one bounded operation.
            long now = System.currentTimeMillis();
            long modifiedAt = runDir.lastModified();
            long ageMs = modifiedAt > now
                    ? Long.MAX_VALUE : Math.max(
                            0L, now - modifiedAt);
            if (ageMs < 30_000L) {
                return true;
            }
            runDir.delete();
        }
        return runDir.exists();
    }

    private static boolean terminateIdentityOwnedParkReaper() {
        java.io.File ownerFile =
                new java.io.File(PARK_REAPER_RUN_OWNER_PATH);
        String owner = readSmallAsciiFile(ownerFile);
        if (owner == null || owner.isEmpty()) {
            ProcessIdentity pending =
                    findIdentityOwnedParkReaperProcess();
            if (pending != null) {
                return terminatePendingParkReaper(pending);
            }
            return !new java.io.File(
                    PARK_REAPER_RUN_PATH).exists();
        }
        String[] parts = owner.split("\\|", -1);
        if (parts.length < 6 || !"v1".equals(parts[0])) {
            return false;
        }
        int pid = parsePositiveInt(parts[1]);
        String expectedStart = parts[2];
        String expectedBoot = parts[3];
        String expectedState = parts[4];
        if (pid <= 0
                || pid == android.os.Process.myPid()
                || !BOOT_IDENTITY.equals(expectedBoot)
                || expectedStart == null
                || expectedStart.isEmpty()
                || "unknown".equals(expectedStart)
                || expectedState == null
                || !expectedState.contains(
                        ":" + PROCESS_INSTANCE_NONCE + ":")) {
            return false;
        }

        String observedStart = readProcessStartIdentity(pid);
        if (observedStart == null) {
            return !new java.io.File(
                            "/proc/" + pid).exists()
                    && owner.equals(
                            readSmallAsciiFile(ownerFile));
        }
        if (!expectedStart.equals(observedStart)) {
            return owner.equals(readSmallAsciiFile(ownerFile));
        }

        // Revalidate both the owner token and kernel process identity at the
        // destructive boundary so PID reuse cannot target another process.
        if (!owner.equals(readSmallAsciiFile(ownerFile))
                || !expectedStart.equals(
                        readProcessStartIdentity(pid))) {
            return false;
        }
        try {
            android.os.Process.killProcess(pid);
        } catch (Throwable failure) {
            log("WARNING: failed to terminate owned park reaper: "
                    + failure.getMessage());
            return false;
        }

        long deadline = android.os.SystemClock.elapsedRealtime()
                + 2000L;
        while (android.os.SystemClock.elapsedRealtime()
                < deadline) {
            String currentStart =
                    readProcessStartIdentity(pid);
            if (!expectedStart.equals(currentStart)) {
                if (owner.equals(readSmallAsciiFile(ownerFile))) {
                    ownerFile.delete();
                    new java.io.File(
                            PARK_REAPER_RUN_PATH).delete();
                }
                return true;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !expectedStart.equals(
                readProcessStartIdentity(pid));
    }

    private static final class ProcessIdentity {
        final int pid;
        final String start;

        ProcessIdentity(int pid, String start) {
            this.pid = pid;
            this.start = start;
        }
    }

    private static ProcessIdentity
            findIdentityOwnedParkReaperProcess() {
        String commandMarker = PARK_REAPER_PATH + "."
                + PROCESS_INSTANCE_NONCE + ".";
        java.io.File[] processes =
                new java.io.File("/proc").listFiles();
        if (processes == null) {
            return null;
        }
        for (java.io.File process : processes) {
            int pid = parsePositiveInt(process.getName());
            if (pid <= 0
                    || pid == android.os.Process.myPid()) {
                continue;
            }
            String cmdline = readProcessCmdline(pid);
            if (cmdline == null
                    || !cmdline.contains(commandMarker)) {
                continue;
            }
            String start = readProcessStartIdentity(pid);
            if (start != null && !start.isEmpty()) {
                return new ProcessIdentity(pid, start);
            }
        }
        return null;
    }

    private static String readProcessCmdline(int pid) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(
                            "/proc/" + pid + "/cmdline"));
            return new String(
                    bytes,
                    java.nio.charset.StandardCharsets.US_ASCII)
                    .replace('\0', ' ');
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean terminatePendingParkReaper(
            ProcessIdentity identity) {
        String commandMarker = PARK_REAPER_PATH + "."
                + PROCESS_INSTANCE_NONCE + ".";
        if (!identity.start.equals(
                    readProcessStartIdentity(identity.pid))) {
            return false;
        }
        String currentCmdline =
                readProcessCmdline(identity.pid);
        if (currentCmdline == null
                || !currentCmdline.contains(commandMarker)
                || !identity.start.equals(
                        readProcessStartIdentity(identity.pid))) {
            return false;
        }
        try {
            android.os.Process.killProcess(identity.pid);
        } catch (Throwable failure) {
            return false;
        }
        long deadline = android.os.SystemClock.elapsedRealtime()
                + 2000L;
        while (android.os.SystemClock.elapsedRealtime()
                < deadline) {
            if (!identity.start.equals(
                    readProcessStartIdentity(identity.pid))) {
                return true;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !identity.start.equals(
                readProcessStartIdentity(identity.pid));
    }

    private static boolean launchParkReaper(long transitionGeneration) {
        if (!isParkReaperRequestCurrent(transitionGeneration, true)) {
            return true;
        }
        if (!ensureParkReaperTransitionPublished(transitionGeneration)) {
            return false;
        }

        final String marker = com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH;
        final String expectedState =
                parkReaperStateToken(latestSentryTransition);
        final String expectedControl = parkReaperArmToken(expectedState);
        if (!expectedState.equals(readSmallAsciiFile(
                        new java.io.File(PARK_REAPER_STATE_PATH)))
                || !expectedControl.equals(readSmallAsciiFile(
                        new java.io.File(PARK_REAPER_CONTROL_PATH)))) {
            return false;
        }
        final String ackPath = PARK_REAPER_ACK_PREFIX
                + PROCESS_INSTANCE_NONCE + "." + transitionGeneration;
        final String donePath = PARK_REAPER_DONE_PREFIX
                + PROCESS_INSTANCE_NONCE + "." + transitionGeneration;
        if (expectedState.equals(readSmallAsciiFile(
                        new java.io.File(donePath)))
                && new java.io.File(marker).isFile()) {
            return true;
        }
        final boolean executionAlreadyActive =
                hasLiveParkReaperExecution();
        if (!executionAlreadyActive) {
            deleteFileIfPresent(ackPath);
            deleteFileIfPresent(donePath);
        }
        final String reaperPath = PARK_REAPER_PATH + "."
                + PROCESS_INSTANCE_NONCE + "." + transitionGeneration + "."
                + Long.toUnsignedString(System.nanoTime(), 36);

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("EXPECTED_STATE='").append(expectedState).append("'\n");
        sb.append("EXPECTED_CONTROL='").append(expectedControl).append("'\n");
        sb.append("BOOT_ID='").append(BOOT_IDENTITY).append("'\n");
        sb.append("STATE_PATH='").append(PARK_REAPER_STATE_PATH).append("'\n");
        sb.append("CONTROL_PATH='").append(PARK_REAPER_CONTROL_PATH).append("'\n");
        sb.append("ACK_PATH='").append(ackPath).append("'\n");
        sb.append("DONE_PATH='").append(donePath).append("'\n");
        sb.append("MARKER_PATH='").append(marker).append("'\n");
        sb.append("MARKER_VALUE='").append(System.currentTimeMillis()).append("'\n");
        sb.append("LEASE_PATH='").append(PARK_REAPER_LEASE_PATH).append("'\n");
        sb.append("LEASE_OWNER=\"$LEASE_PATH/owner\"\n");
        sb.append("RUN_PATH='").append(PARK_REAPER_RUN_PATH).append("'\n");
        sb.append("RUN_OWNER=\"$RUN_PATH/owner\"\n");
        sb.append("SELF='").append(reaperPath).append("'\n");
        sb.append("WORK_PREFIX=\"$SELF.work\"\n");
        sb.append("RUN_CANDIDATE=\"$SELF.run-owner\"\n");
        sb.append("proc_start() {\n");
        sb.append("  IFS= read -r ps_stat < \"/proc/$1/stat\" 2>/dev/null || return 1\n");
        sb.append("  ps_rest=\"${ps_stat##*) }\"\n");
        sb.append("  [ \"$ps_rest\" != \"$ps_stat\" ] || return 1\n");
        sb.append("  ps_old_ifs=\"$IFS\"; IFS=' '; set -- $ps_rest; IFS=\"$ps_old_ifs\"\n");
        sb.append("  [ \"$#\" -ge 20 ] || return 1\n");
        sb.append("  printf '%s' \"${20}\"\n");
        sb.append("}\n");
        sb.append("OWNER_START=\"$(proc_start $$)\"\n");
        sb.append("OWNER_TOKEN=\"v3|shell|$$|$OWNER_START|$BOOT_ID|$EXPECTED_STATE\"\n");
        sb.append("RUN_TOKEN=\"v1|$$|$OWNER_START|$BOOT_ID|$EXPECTED_STATE|shell\"\n");
        sb.append("LEASE_HELD=0\n");
        sb.append("RUN_HELD=0\n");
        sb.append("file_value() { IFS= read -r value < \"$1\" 2>/dev/null || return 1; printf '%s' \"$value\"; }\n");
        sb.append("owns_state() { ")
          .append("[ \"$(file_value \"$STATE_PATH\")\" = \"$EXPECTED_STATE\" ] && ")
          .append("[ \"$(file_value \"$CONTROL_PATH\")\" = \"$EXPECTED_CONTROL\" ]; }\n");
        sb.append("run_bounded() {\n");
        sb.append("  ticks=\"$1\"; shift; \"$@\" & child=$!; i=0\n");
        sb.append("  while kill -0 \"$child\" 2>/dev/null; do\n");
        sb.append("    [ \"$i\" -lt \"$ticks\" ] || { kill -9 \"$child\" 2>/dev/null; wait \"$child\" 2>/dev/null; return 124; }\n");
        sb.append("    sleep 0.1; i=$((i+1))\n");
        sb.append("  done\n");
        sb.append("  wait \"$child\"\n");
        sb.append("}\n");
        sb.append("run_bounded_capture() {\n");
        sb.append("  output=\"$1\"; ticks=\"$2\"; shift 2\n");
        sb.append("  \"$@\" > \"$output\" 2>/dev/null & child=$!; i=0\n");
        sb.append("  while kill -0 \"$child\" 2>/dev/null; do\n");
        sb.append("    [ \"$i\" -lt \"$ticks\" ] || { kill -9 \"$child\" 2>/dev/null; wait \"$child\" 2>/dev/null; return 124; }\n");
        sb.append("    sleep 0.1; i=$((i+1))\n");
        sb.append("  done\n");
        sb.append("  wait \"$child\"\n");
        sb.append("}\n");
        sb.append("release_run() {\n");
        sb.append("  rm -f \"$RUN_CANDIDATE\" 2>/dev/null\n");
        sb.append("  [ \"$RUN_HELD\" = 1 ] || return\n");
        sb.append("  if [ \"$(file_value \"$RUN_OWNER\")\" = \"$RUN_TOKEN\" ]; then\n");
        sb.append("    rm -f \"$RUN_OWNER\" 2>/dev/null\n");
        sb.append("    rmdir \"$RUN_PATH\" 2>/dev/null\n");
        sb.append("  elif [ ! -e \"$RUN_OWNER\" ]; then\n");
        sb.append("    rmdir \"$RUN_PATH\" 2>/dev/null\n");
        sb.append("  fi\n");
        sb.append("  RUN_HELD=0\n");
        sb.append("}\n");
        sb.append("acquire_run() {\n");
        sb.append("  printf '%s\\n' \"$RUN_TOKEN\" > \"$RUN_CANDIDATE\" 2>/dev/null || return 1\n");
        sb.append("  mkdir \"$RUN_PATH\" 2>/dev/null || { rm -f \"$RUN_CANDIDATE\"; return 2; }\n");
        sb.append("  RUN_HELD=1\n");
        sb.append("  run_bounded 20 mv -f \"$RUN_CANDIDATE\" \"$RUN_OWNER\" || { release_run; return 1; }\n");
        sb.append("  [ \"$(file_value \"$RUN_OWNER\")\" = \"$RUN_TOKEN\" ] || { release_run; return 1; }\n");
        sb.append("}\n");
        sb.append("atomic_write() {\n");
        sb.append("  target=\"$1\"; value=\"$2\"; tmp=\"$target.tmp.$$\"\n");
        sb.append("  printf '%s\\n' \"$value\" > \"$tmp\" 2>/dev/null || return 1\n");
        sb.append("  run_bounded 20 chmod 666 \"$tmp\" || { rm -f \"$tmp\"; return 1; }\n");
        sb.append("  run_bounded 20 mv -f \"$tmp\" \"$target\"\n");
        sb.append("}\n");
        sb.append("release_lease() {\n");
        sb.append("  [ \"$LEASE_HELD\" = 1 ] || return\n");
        sb.append("  if [ \"$(file_value \"$LEASE_OWNER\")\" = \"$OWNER_TOKEN\" ]; then\n");
        sb.append("    rm -f \"$LEASE_OWNER\" 2>/dev/null\n");
        sb.append("    rmdir \"$LEASE_PATH\" 2>/dev/null\n");
        sb.append("  fi\n");
        sb.append("  LEASE_HELD=0\n");
        sb.append("}\n");
        sb.append("reclaim_dead_lease() {\n");
        sb.append("  owner=\"$(file_value \"$LEASE_OWNER\")\"\n");
        sb.append("  [ -n \"$owner\" ] || return\n");
        sb.append("  old_ifs=\"$IFS\"; IFS='|'; set -- $owner; IFS=\"$old_ifs\"\n");
        sb.append("  [ \"$1\" = v3 ] || return\n");
        sb.append("  owner_pid=\"$3\"; owner_start=\"$4\"; owner_boot=\"$5\"\n");
        sb.append("  if [ \"$owner_boot\" = \"$BOOT_ID\" ]; then\n");
        sb.append("    actual_start=\"$(proc_start \"$owner_pid\")\"\n");
        sb.append("    [ -n \"$owner_start\" ] && [ \"$owner_start\" != unknown ] ")
          .append("&& [ \"$actual_start\" = \"$owner_start\" ] && return\n");
        sb.append("    if [ -d \"/proc/$owner_pid\" ]; then\n");
        sb.append("      [ -z \"$owner_start\" ] && return\n");
        sb.append("      [ \"$owner_start\" = unknown ] && return\n");
        sb.append("      [ -z \"$actual_start\" ] && return\n");
        sb.append("    fi\n");
        sb.append("  fi\n");
        sb.append("  [ \"$(file_value \"$LEASE_OWNER\")\" = \"$owner\" ] || return\n");
        sb.append("  rm -f \"$LEASE_OWNER\" 2>/dev/null\n");
        sb.append("  rmdir \"$LEASE_PATH\" 2>/dev/null\n");
        sb.append("}\n");
        sb.append("acquire_lease() {\n");
        sb.append("  i=0\n");
        sb.append("  while ! mkdir \"$LEASE_PATH\" 2>/dev/null; do\n");
        sb.append("    owns_state || return 1\n");
        sb.append("    reclaim_dead_lease\n");
        sb.append("    i=$((i+1))\n");
        sb.append("    [ $i -lt 20 ] || return 1\n");
        sb.append("    sleep 0.1\n");
        sb.append("  done\n");
        sb.append("  echo \"$OWNER_TOKEN\" > \"$LEASE_OWNER\" 2>/dev/null || { ")
          .append("rmdir \"$LEASE_PATH\" 2>/dev/null; return 1; }\n");
        sb.append("  LEASE_HELD=1\n");
        sb.append("}\n");
        sb.append("owned_begin() {\n");
        sb.append("  acquire_lease || return 1\n");
        sb.append("  owns_state || { release_lease; return 1; }\n");
        sb.append("}\n");
        sb.append("cleanup_work() { [ -n \"$DEADLINE_PID\" ] && kill \"$DEADLINE_PID\" 2>/dev/null; rm -f \"$WORK_PREFIX\".* 2>/dev/null; }\n");
        sb.append("stale_exit() { release_lease; release_run; cleanup_work; rm -f \"$SELF\" 2>/dev/null; exit 0; }\n");
        sb.append("failed_exit() { release_lease; release_run; cleanup_work; rm -f \"$SELF\" 2>/dev/null; exit 1; }\n");
        sb.append("trap 'release_lease; release_run; cleanup_work' EXIT\n");
        sb.append("trap 'release_lease; release_run; cleanup_work; exit 1' HUP INT TERM\n");
        sb.append("MAIN_PID=$$; (sleep 90; kill -TERM \"$MAIN_PID\" 2>/dev/null) & DEADLINE_PID=$!\n");
        sb.append("acquire_run\n");
        sb.append("run_rc=$?\n");
        sb.append("[ \"$run_rc\" -eq 0 ] || { cleanup_work; rm -f \"$SELF\" 2>/dev/null; [ \"$run_rc\" -eq 2 ] && exit 0; exit 1; }\n");
        sb.append("sleep 1\n");
        sb.append("owned_begin || stale_exit\n");
        sb.append("atomic_write \"$MARKER_PATH\" \"$MARKER_VALUE\" || stale_exit\n");
        sb.append("owns_state || stale_exit\n");
        sb.append("atomic_write \"$ACK_PATH\" \"$EXPECTED_STATE\" || stale_exit\n");
        sb.append("owns_state || stale_exit\n");
        sb.append("release_lease\n");
        sb.append("i=0\n");
        sb.append("while [ $i -lt 20 ]; do\n");
        sb.append("  owns_state || stale_exit\n");
        sb.append("  sleep 1\n");
        sb.append("  i=$((i+1))\n");
        sb.append("done\n");
        sb.append("owns_state || stale_exit\n");

        String[] patterns = {
            "start_cam_daemon", "start_telegram",
            "byd_cam_daemon", "cam_daemon",
            "telegram_bot_daemon",
            "start_zrok", "start_singbox", "start_cloudflared",
            "start_tailscale", "sentry_proxy", "sing-box",
            "cloudflared", "zrok", "tailscaled"
        };
        for (int patternIndex = 0;
                patternIndex < patterns.length;
                patternIndex++) {
            String pattern = patterns[patternIndex];
            String targetSuffix = Integer.toString(patternIndex);
            sb.append("owns_state || stale_exit\n");
            sb.append("TARGETS=\"$WORK_PREFIX.targets.")
              .append(targetSuffix).append("\"\n");
            sb.append("run_bounded_capture \"$TARGETS\" 30 sh -c '")
              .append("P=\"$(ps -A -o PID,ARGS 2>/dev/null)\" || exit 41; ")
              .append("printf \"%s\\n\" \"$P\" | awk -v self=\"$$\" ")
              .append("-v pat=\"").append(pattern).append("\" ")
              .append("'\"'\"'$1 != self && index($0,pat) > 0 {print $1; if (++n >= 32) exit}'\"'\"''")
              .append(" || failed_exit\n");
            sb.append("while IFS= read -r pid; do\n");
            sb.append("  case \"$pid\" in ''|*[!0-9]*) continue;; esac\n");
            sb.append("  [ \"$pid\" != \"$$\" ] || continue\n");
            sb.append("  owned_begin || stale_exit\n");
            sb.append("  pid_start=\"$(proc_start \"$pid\")\"\n");
            sb.append("  [ -n \"$pid_start\" ] || { release_lease; failed_exit; }\n");
            sb.append("  CMDLINE=\"$WORK_PREFIX.cmd.$pid\"\n");
            sb.append("  run_bounded_capture \"$CMDLINE\" 20 sh -c '")
              .append("tr \"\\\\000\" \" \" < \"/proc/$1/cmdline\" 2>/dev/null; printf \"\\n\"")
              .append("' sh \"$pid\" || { release_lease; failed_exit; }\n");
            sb.append("  cmdline=\"$(file_value \"$CMDLINE\")\"\n");
            sb.append("  case \"$cmdline\" in *'")
              .append(pattern)
              .append("'*) current_start=\"$(proc_start \"$pid\")\"; ")
              .append("[ \"$current_start\" = \"$pid_start\" ] ")
              .append("|| { release_lease; continue; }; ")
              .append("kill_rc=0; run_bounded 10 kill -9 \"$pid\" ")
              .append(">/dev/null 2>&1 || kill_rc=$?; ")
              .append("[ \"$kill_rc\" -ne 124 ] || { release_lease; failed_exit; }; ")
              .append("if [ \"$kill_rc\" -eq 0 ]; then ")
              .append("wait_i=0; ")
              .append("while [ \"$(proc_start \"$pid\")\" = \"$pid_start\" ]; do ")
              .append("[ \"$wait_i\" -lt 20 ] ")
              .append("|| { release_lease; failed_exit; }; ")
              .append("sleep 0.1; wait_i=$((wait_i+1)); done; ")
              .append("elif [ \"$(proc_start \"$pid\")\" = \"$pid_start\" ]; then ")
              .append("release_lease; failed_exit; fi;; esac\n");
            sb.append("  release_lease\n");
            sb.append("done < \"$TARGETS\"\n");
            sb.append("owned_begin || stale_exit\n");
            sb.append("atomic_write \"$ACK_PATH\" \"$EXPECTED_STATE\" || { release_lease; failed_exit; }\n");
            sb.append("release_lease\n");
        }
        sb.append("owned_begin || stale_exit\n");
        sb.append("run_bounded 20 sh -c 'echo GlobalProxyDaemon > /sys/power/wake_unlock 2>/dev/null' || true\n");
        sb.append("release_lease\n");
        sb.append("owned_begin || stale_exit\n");
        sb.append("run_bounded 30 sh -c 'svc power stayon false 2>/dev/null' || true\n");
        sb.append("release_lease\n");
        sb.append("sleep 1\n");
        sb.append("owned_begin || stale_exit\n");
        sb.append("run_bounded 20 rm -f /data/local/tmp/camera_daemon.lock ")
          .append("/data/local/tmp/telegram_bot_daemon.lock || true\n");
        sb.append("owns_state || { release_lease; stale_exit; }\n");
        sb.append("atomic_write \"$DONE_PATH\" \"$EXPECTED_STATE\" || { release_lease; failed_exit; }\n");
        sb.append("release_lease\n");
        sb.append("cleanup_work\n");
        sb.append("rm -f \"$SELF\" 2>/dev/null\n");

        try {
            if (!executionAlreadyActive) {
                java.io.FileWriter writer =
                        new java.io.FileWriter(reaperPath);
                try {
                    writer.write(sb.toString());
                } finally {
                    writer.close();
                }
                try {
                    new java.io.File(reaperPath).setReadable(true, false);
                    new java.io.File(reaperPath).setExecutable(true, false);
                } catch (Exception ignored) {}
                if (!isParkReaperRequestCurrent(
                        transitionGeneration, true)) {
                    new java.io.File(reaperPath).delete();
                    return true;
                }
                ShellResult launchResult = execShellResult(
                        "nohup sh " + reaperPath + " >/dev/null 2>&1 &",
                        DEFAULT_SHELL_TIMEOUT_MS,
                        () -> isParkReaperRequestCurrent(
                                transitionGeneration, true));
                if (!launchResult.success) {
                    if (launchResult.canceled) {
                        new java.io.File(reaperPath).delete();
                        return true;
                    }
                    log("WARNING: failed to launch park reaper: "
                            + launchResult.describeFailure());
                    writeParkedShutdownMarkerIfOwned(
                            transitionGeneration, marker,
                            expectedState, expectedControl);
                    return false;
                }
                log("Park reaper launched for generation "
                        + transitionGeneration + " (detached): "
                        + reaperPath);
            } else {
                log("Park reaper execution already active; waiting for generation "
                        + transitionGeneration + " acknowledgement");
            }
            if (!awaitParkReaperAcknowledgement(
                    transitionGeneration, expectedState,
                    ackPath, marker, 5000L)) {
                log("WARNING: detached park reaper did not acknowledge marker commit");
                writeParkedShutdownMarkerIfOwned(
                        transitionGeneration, marker,
                        expectedState, expectedControl);
                return false;
            }
            if (!awaitParkReaperCompletion(
                    transitionGeneration, expectedState,
                    donePath, 95_000L)) {
                log("WARNING: detached park reaper did not acknowledge completion");
                return false;
            }
        } catch (Exception e) {
            log("WARNING: failed to launch park reaper: " + e.getMessage());
            writeParkedShutdownMarkerIfOwned(
                    transitionGeneration, marker,
                    expectedState, expectedControl);
            return false;
        }
        return true;
    }

    private static boolean awaitParkReaperCompletion(
            long generation,
            String expectedState,
            String donePath,
            long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!isParkReaperRequestCurrent(generation, true)) {
                return true;
            }
            if (expectedState.equals(readSmallAsciiFile(
                    new java.io.File(donePath)))) {
                return true;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean awaitParkReaperAcknowledgement(
            long generation,
            String expectedState,
            String ackPath,
            String markerPath,
            long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!isParkReaperRequestCurrent(generation, true)) {
                return true;
            }
            if (expectedState.equals(readSmallAsciiFile(
                            new java.io.File(ackPath)))
                    && new java.io.File(markerPath).isFile()) {
                return true;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean writeParkedShutdownMarkerIfOwned(
            long transitionGeneration,
            String markerPath,
            String expectedState,
            String expectedControl) {
        ParkReaperLease lease = acquireParkReaperLease();
        if (lease == null) {
            return false;
        }
        try {
            if (!isParkReaperRequestCurrent(
                    transitionGeneration, true)) {
                return true;
            }
            if (!expectedState.equals(readSmallAsciiFile(
                            new java.io.File(PARK_REAPER_STATE_PATH)))
                    || !expectedControl.equals(readSmallAsciiFile(
                            new java.io.File(PARK_REAPER_CONTROL_PATH)))) {
                return false;
            }
            return writeParkReaperToken(
                    markerPath, Long.toString(System.currentTimeMillis()));
        } catch (Throwable failure) {
            log("WARNING: failed to write parked shutdown marker: "
                    + failure.getMessage());
            return false;
        } finally {
            lease.close();
        }
    }

    /** Cancel a detached parked reaper without creating a second worker. */
    private static boolean cancelParkReaperForAccOn(long transitionGeneration) {
        if (!isParkReaperRequestCurrent(transitionGeneration, false)) {
            return true;
        }
        if (!ensureParkReaperTransitionPublished(transitionGeneration)) {
            return false;
        }
        String stateToken = parkReaperStateToken(latestSentryTransition);
        String cancelToken = parkReaperCancelToken(stateToken);
        ParkReaperLease lease = acquireParkReaperLease();
        if (lease == null) {
            return false;
        }
        try {
            if (!isParkReaperRequestCurrent(
                    transitionGeneration, false)) {
                return true;
            }
            if (!stateToken.equals(readSmallAsciiFile(
                            new java.io.File(PARK_REAPER_STATE_PATH)))
                    || !cancelToken.equals(readSmallAsciiFile(
                            new java.io.File(PARK_REAPER_CONTROL_PATH)))) {
                return false;
            }
            boolean markerDeleted = deleteFileIfPresent(
                    com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH);
            log("Canceled detached parked reaper for generation "
                    + transitionGeneration);
            return markerDeleted;
        } catch (Throwable t) {
            log("WARNING: could not clear parked reaper marker: "
                    + t.getMessage());
            return false;
        } finally {
            lease.close();
        }
    }

    private static final long DEFAULT_SHELL_TIMEOUT_MS = 10_000L;
    private static final int MAX_SHELL_OUTPUT_BYTES = 64 * 1024;

    private interface ShellOwnership {
        boolean isCurrent();
    }

    private static final class ShellResult {
        final boolean success;
        final boolean canceled;
        final boolean timedOut;
        final int exitCode;
        final String output;
        final String error;

        ShellResult(
                boolean success,
                boolean canceled,
                boolean timedOut,
                int exitCode,
                String output,
                String error) {
            this.success = success;
            this.canceled = canceled;
            this.timedOut = timedOut;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.error = error;
        }

        String describeFailure() {
            if (success) {
                return "none";
            }
            if (canceled) {
                return "canceled";
            }
            if (timedOut) {
                return "timed out";
            }
            if (error != null && !error.isEmpty()) {
                return error;
            }
            return "exit=" + exitCode
                    + (output.isEmpty() ? "" : " output=" + output);
        }
    }

    private static ShellResult execShellResult(
            String cmd, long timeoutMs, ShellOwnership ownership) {
        if (Thread.currentThread().isInterrupted()
                || !isShellOwnershipCurrent(ownership)) {
            return new ShellResult(
                    false, true, false, -1, "", "ownership changed");
        }

        Process process = null;
        java.io.InputStream processOutput = null;
        java.io.ByteArrayOutputStream output =
                new java.io.ByteArrayOutputStream();
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(new String[]{"sh", "-c", cmd});
            builder.redirectErrorStream(true);
            process = builder.start();
            processOutput = process.getInputStream();
            long deadline = android.os.SystemClock.elapsedRealtime()
                    + Math.max(1L, timeoutMs);

            while (true) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    destroyShellProcessTree(process);
                    return new ShellResult(
                            false, false, true, -1,
                            shellOutputString(output),
                            "timeout after " + timeoutMs + " ms");
                }
                drainShellOutput(processOutput, output);
                if (Thread.currentThread().isInterrupted()
                        || !isShellOwnershipCurrent(ownership)) {
                    destroyShellProcessTree(process);
                    return new ShellResult(
                            false, true, false, -1,
                            shellOutputString(output),
                            "ownership changed");
                }

                long remaining = deadline
                        - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0L) {
                    destroyShellProcessTree(process);
                    return new ShellResult(
                            false, false, true, -1,
                            shellOutputString(output),
                            "timeout after " + timeoutMs + " ms");
                }
                if (process.waitFor(
                        Math.min(100L, remaining),
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    break;
                }
            }

            // Never block waiting for EOF here. A detached descendant can
            // inherit stdout after the shell exits and keep the pipe open.
            drainShellOutput(processOutput, output);
            int exitCode = process.exitValue();
            String outputText = shellOutputString(output);
            return new ShellResult(
                    exitCode == 0,
                    false,
                    false,
                    exitCode,
                    outputText,
                    exitCode == 0 ? null : "exit=" + exitCode);
        } catch (InterruptedException interrupted) {
            if (process != null) {
                destroyShellProcessTree(process);
            }
            Thread.currentThread().interrupt();
            return new ShellResult(
                    false, true, false, -1,
                    shellOutputString(output), "interrupted");
        } catch (Throwable failure) {
            if (process != null) {
                destroyShellProcessTree(process);
            }
            return new ShellResult(
                    false, false, false, -1,
                    shellOutputString(output), failure.getMessage());
        } finally {
            if (processOutput != null) {
                try {
                    processOutput.close();
                } catch (Exception ignored) {}
            }
            if (process != null && process.isAlive()) {
                destroyShellProcessTree(process);
            }
        }
    }

    private static boolean isShellOwnershipCurrent(
            ShellOwnership ownership) {
        if (ownership == null) {
            return true;
        }
        try {
            return ownership.isCurrent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void drainShellOutput(
            java.io.InputStream input,
            java.io.ByteArrayOutputStream output) throws java.io.IOException {
        byte[] buffer = new byte[4096];
        // A noisy child can keep available() positive forever. Bound each
        // drain pass so the caller always regains control to enforce deadline
        // and ownership checks.
        for (int reads = 0; reads < 8; reads++) {
            int available = input.available();
            if (available <= 0) {
                return;
            }
            int read = input.read(
                    buffer,
                    0,
                    Math.min(buffer.length, available));
            if (read < 0) {
                return;
            }
            int remaining = MAX_SHELL_OUTPUT_BYTES - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(read, remaining));
            }
            if (input.available() <= 0) {
                return;
            }
        }
    }

    private static String shellOutputString(
            java.io.ByteArrayOutputStream output) {
        try {
            return output.toString("UTF-8").trim();
        } catch (Exception ignored) {
            return output.toString().trim();
        }
    }

    private static void destroyShellProcessTree(Process process) {
        if (process == null) {
            return;
        }
        int rootPid = getProcessPid(process);
        java.util.List<Integer> descendants =
                new java.util.ArrayList<>();
        if (rootPid > 0) {
            collectProcessDescendants(
                    rootPid,
                    descendants,
                    new java.util.HashSet<Integer>());
        }
        for (Integer pid : descendants) {
            if (pid != null
                    && pid > 0
                    && pid != android.os.Process.myPid()) {
                try {
                    android.os.Process.killProcess(pid);
                } catch (Throwable ignored) {}
            }
        }
        try {
            process.destroy();
            if (!process.waitFor(
                    250L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(
                        250L, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {}
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            try {
                process.destroyForcibly();
            } catch (Throwable ignoredAgain) {}
        }
    }

    private static int getProcessPid(Process process) {
        try {
            Method pidMethod = Process.class.getMethod("pid");
            Object value = pidMethod.invoke(process);
            if (value instanceof Number) {
                long pid = ((Number) value).longValue();
                if (pid > 0L && pid <= Integer.MAX_VALUE) {
                    return (int) pid;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static void collectProcessDescendants(
            int parentPid,
            java.util.List<Integer> output,
            java.util.Set<Integer> visited) {
        if (!visited.add(parentPid)) {
            return;
        }
        try {
            java.nio.file.Path childrenPath = java.nio.file.Paths.get(
                    "/proc/" + parentPid + "/task/" + parentPid + "/children");
            String children = new String(
                    java.nio.file.Files.readAllBytes(childrenPath),
                    java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (children.isEmpty()) {
                return;
            }
            for (String token : children.split("\\s+")) {
                int childPid = Integer.parseInt(token);
                collectProcessDescendants(
                        childPid, output, visited);
                output.add(childPid);
            }
        } catch (Throwable ignored) {}
    }

    private static String execShell(String cmd) {
        ShellResult result = execShellResult(
                cmd, DEFAULT_SHELL_TIMEOUT_MS, null);
        return result.success
                ? result.output
                : "ERROR: " + result.describeFailure();
    }

    /**
     * Clear a hotspot-owned WiFi suppression that outlived its access point.
     * The hotspot owner lives in the app process; if that process dies while the
     * flag is set and the AP is already down, no one is left to clear it and WiFi
     * would never come back. Returns true when the flag was stale and cleared
     * (caller should proceed to re-enable WiFi).
     *
     * <p>Only touches the HOTSPOT-owned marker. A deliberate user "WiFi off"
     * preference is stored independently and must survive.
     */
    /**
     * Consecutive keep-alive ticks spent honouring a published "AP coming up".
     * Counted in ticks rather than milliseconds so no clock can distort it; the
     * keep-alive runs every ~10s, so 6 ticks comfortably covers the worst-case
     * ~27s enable while still releasing WiFi if the owner dies mid-startup.
     */
    private static int enablingHoldOffTicks = 0;
    private static final int MAX_ENABLING_HOLD_OFF_TICKS = 6;

    private static boolean reconcileStrandedHotspotSuppression() {
        try {
            if (!com.overdrive.app.config.UnifiedConfigManager
                    .didHotspotSuppressWifiKeepAlive()) {
                return false;
            }
            // The AP takes up to ~27s to come up (shell + tether latch) and does not
            // report SoftApState for part of that, so an owner that has published
            // ENABLING gets the benefit of the doubt. This is the marker the app
            // writes before it blocks; without this hold-off the probe below would
            // read "not up" mid-startup and clear the suppression, letting the
            // keep-alive re-enable the station link and kill the AP being started.
            // Bounded by a tick count, not a clock: if the owner dies mid-enable the
            // ENABLING marker would otherwise persist and strand WiFi off forever.
            int declared = com.overdrive.app.config.UnifiedConfigManager
                    .getHotspotState().optInt("apState", 11);
            if (declared == 12) {
                if (enablingHoldOffTicks < MAX_ENABLING_HOLD_OFF_TICKS) {
                    enablingHoldOffTicks++;
                    return false;
                }
                log("Hotspot has claimed ENABLING for " + enablingHoldOffTicks
                        + " ticks — no longer holding off the keep-alive");
            }
            enablingHoldOffTicks = 0;
            // Otherwise ask the RADIO, not a stored snapshot. Earlier revisions
            // inferred liveness from published timestamps, but every variant broke on
            // some clock-step or short-reboot combination: an owner that looked dead
            // got its AP torn down, or a dead one looked alive and stranded WiFi off.
            // The framework's own AP state is ground truth and needs no clock.
            // `; echo` keeps the exit status at 0: grep exits 1 when it counts zero
            // matches, and execShell reports any non-zero exit as "ERROR", which the
            // unusable-probe branch below would read as "can't tell" — stranding WiFi
            // off on every ordinary AP-down case, the exact failure this method exists
            // to prevent. The marker text is what we test, not the exit code.
            String apDump = execShell(
                    "dumpsys wifi 2>/dev/null | grep -c curState=SoftApState"
                            + "; echo PROBE_OK");
            boolean apUp = false;
            boolean probeWorked = false;
            if (apDump != null && apDump.contains("PROBE_OK")) {
                for (String line : apDump.split("\\r?\\n")) {
                    String t = line.trim();
                    if (t.isEmpty() || t.equals("PROBE_OK")) continue;
                    try {
                        apUp = Integer.parseInt(t) > 0;
                        probeWorked = true;
                    } catch (NumberFormatException ignored) {
                        // Not the count line; keep looking.
                    }
                }
            }
            if (!probeWorked) {
                // Can't see the radio, so we can't prove the owner is gone. Leaving
                // the suppression costs connectivity until the next tick; clearing it
                // could kill a live AP. Prefer the recoverable option.
                log("Hotspot suppression reconcile skipped — AP state probe failed");
                return false;
            }
            if (apUp) {
                return false;
            }
            log("Hotspot WiFi suppression is stale (AP is not up)"
                    + " — clearing so WiFi keep-alive can resume");
            java.util.Map<String, Object> clear = new java.util.HashMap<>();
            clear.put("suppressedByHotspot", false);
            boolean cleared =
                    com.overdrive.app.config.UnifiedConfigManager.updateHotspot(clear);
            // Fall through to re-enable only when no independent user/automation
            // suppression remains after clearing the stale hotspot marker.
            return cleared && !com.overdrive.app.config.UnifiedConfigManager
                    .isWifiKeepAliveSuppressed();
        } catch (Throwable t) {
            log("Hotspot suppression reconcile failed: " + t.getMessage());
            return false;
        }
    }
    
    // ==================== MONITORING & DIAGNOSTICS ====================
    
    /**
     * Install shutdown hook to detect process termination.
     * This helps debug why the daemon might be dying.
     */
    private static void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("=== SHUTDOWN HOOK TRIGGERED ===");
            log("Reason: Process is being terminated");
            log("Uptime: " + (System.currentTimeMillis() - startTime) / 1000 + "s");
            log("InSentryMode: " + inSentryMode);
            log("Running flag: " + running);
            
            // Try to determine why we're dying
            try {
                String ps = execShell("ps -p " + android.os.Process.myPid());
                log("Process status before death: " + ps);
            } catch (Exception e) {
                log("Could not get process status: " + e.getMessage());
            }
            
            // Check wake lock status
            if (wakeLock != null) {
                try {
                    log("WakeLock held: " + wakeLock.isHeld());
                } catch (Exception e) {
                    log("Could not check WakeLock: " + e.getMessage());
                }
            }
            
            // Log memory status at death
            try {
                logMemoryStatus();
            } catch (Exception e) {
                log("Could not log memory status: " + e.getMessage());
            }
            
            log("=== SHUTDOWN COMPLETE ===");
        }, "ShutdownHook"));
        
        log("Shutdown hook installed");
    }
    
    /**
     * Log current memory status.
     * Helps detect if we're being killed due to low memory.
     */
    private static void logMemoryStatus() {
        if (appContext == null) {
            log("Cannot log memory status: no context");
            return;
        }
        
        try {
            android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
            android.app.ActivityManager am = (android.app.ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            
            if (am != null) {
                am.getMemoryInfo(memInfo);
                long availMB = memInfo.availMem / 1024 / 1024;
                long totalMB = memInfo.totalMem / 1024 / 1024;
                long usedMB = totalMB - availMB;
                
                log("=== MEMORY STATUS ===");
                log("  Available: " + availMB + " MB");
                log("  Total: " + totalMB + " MB");
                log("  Used: " + usedMB + " MB");
                log("  Low memory: " + memInfo.lowMemory);
                log("  Threshold: " + (memInfo.threshold / 1024 / 1024) + " MB");
            } else {
                log("ActivityManager is null");
            }
        } catch (Exception e) {
            log("Error logging memory status: " + e.getMessage());
        }
    }
    
    /**
     * Start periodic status monitoring.
     * Logs daemon health every 60 seconds for debugging.
     */
    private static void startStatusMonitoring() {
        if (statusHandler == null) {
            log("Cannot start status monitoring: no handler");
            return;
        }
        
        final Runnable statusCheck = new Runnable() {
            @Override
            public void run() {
                try {
                    pumpFallbackReconcilerRetries();
                    long uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000;
                    long uptimeMinutes = uptimeSeconds / 60;
                    
                    log("=== STATUS CHECK ===");
                    log("  Uptime: " + uptimeMinutes + "m " + (uptimeSeconds % 60) + "s");
                    log("  WakeLock: " + (wakeLock != null && wakeLock.isHeld()));
                    log("  InSentryMode: " + inSentryMode);
                    log("  Running: " + running);
                    log("  KeepAlive thread: " + (systemKeepAliveThread != null && systemKeepAliveThread.isAlive()));
                    // Charging thread replaced by BatteryVoltageMonitorV2 — handler-thread, no liveness probe needed.
                    // log("  Charging thread: " + (mcuChargingThread != null && mcuChargingThread.isAlive()));
                    log("  Surveillance: " + surveillanceEnabled);
                    log("  Last power level: " + powerLevelToString(lastPowerLevel));
                    log("  Last MCU status: " + lastMcuStatus);
                    
                    // Check MCU status
                    BoundedCallResult<Integer> currentMcuStatus =
                            boundedDiagnosticHardwareLane.invoke(
                                    "Status MCU read",
                                    HARDWARE_CALL_TIMEOUT_MS,
                                    null,
                                    AccSentryDaemon::getMcuStatus,
                                    null);
                    if (currentMcuStatus.completed
                            && currentMcuStatus.failure == null
                            && currentMcuStatus.value != null
                            && currentMcuStatus.value != -1) {
                        log("  Current MCU status: "
                                + currentMcuStatus.value);
                    }
                    
                    // Log memory every 5 minutes
                    if (uptimeMinutes % 5 == 0) {
                        logMemoryStatus();
                    }
                    
                    // Enforce persistent ADB over Wi-Fi and self-heal companion daemons
                    enforceAdbAndDaemonHealth();

                    log("===================");
                    
                } catch (Exception e) {
                    log("Status check error: " + e.getMessage());
                }
                
                // Schedule next check
                if (running && statusHandler != null) {
                    statusHandler.postDelayed(this, 60000);  // 60 seconds
                }
            }
        };
        
        // Start first check after 60 seconds
        statusHandler.postDelayed(statusCheck, 60000);
        log("Status monitoring started (60s interval)");
    }

    /**
     * Periodically enforce persistent ADB over Wi-Fi and self-heal companion daemons.
     * Runs every 60s as shell UID 2000.
     */
    private static void enforceAdbAndDaemonHealth() {
        try {
            // 1. Enforce global ADB settings via SettingsProvider (authorized for shell UID 2000)
            Runtime.getRuntime().exec(new String[]{"settings", "put", "global", "adb_enabled", "1"});
            Runtime.getRuntime().exec(new String[]{"settings", "put", "global", "adb_wifi_enabled", "1"});
            Runtime.getRuntime().exec(new String[]{"settings", "put", "global", "adb_allowed_connection_time", "0"});
            Runtime.getRuntime().exec(new String[]{"settings", "put", "global", "development_settings_enabled", "1"});
            Runtime.getRuntime().exec(new String[]{"settings", "put", "global", "stay_on_while_plugged_in", "7"});

            // 2. Self-heal CameraDaemon if unexpectedly dead and not explicitly disabled
            java.io.File camDisabled = new java.io.File("/data/local/tmp/camera_daemon.disabled");
            java.io.File camScript = new java.io.File("/data/local/tmp/start_cam_daemon.sh");
            if (!camDisabled.exists() && camScript.exists()) {
                if (!isProcessRunning("byd_cam_daemon") && !isProcessRunning("CameraDaemon")) {
                    log("Self-healing: CameraDaemon is dead, respawning watchdog via start_cam_daemon.sh...");
                    Runtime.getRuntime().exec(new String[]{"sh", "-c", "nohup sh /data/local/tmp/start_cam_daemon.sh > /dev/null 2>&1 &"});
                }
            }

            // 3. Self-heal TelegramBotDaemon if unexpectedly dead and not explicitly disabled
            java.io.File tgDisabled = new java.io.File("/data/local/tmp/telegram_bot_daemon.disabled");
            java.io.File tgScript = new java.io.File("/data/local/tmp/start_telegram.sh");
            if (!tgDisabled.exists() && tgScript.exists()) {
                if (!isProcessRunning("telegram_bot_daemon") && !isProcessRunning("start_telegram.sh")) {
                    log("Self-healing: TelegramBotDaemon is dead, respawning watchdog via start_telegram.sh...");
                    Runtime.getRuntime().exec(new String[]{"sh", "-c", "nohup sh /data/local/tmp/start_telegram.sh > /dev/null 2>&1 &"});
                }
            }
        } catch (Throwable t) {
            log("enforceAdbAndDaemonHealth error: " + t.getMessage());
        }
    }

    private static boolean isProcessRunning(String processName) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"pgrep", "-f", processName});
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                return line != null && !line.trim().isEmpty();
            } finally {
                p.waitFor();
            }
        } catch (Throwable ignored) {
            return false;
        }
    }
    
    /**
     * Stop periodic status monitoring.
     */
    private static void stopStatusMonitoring() {
        if (statusHandler != null) {
            statusHandler.removeCallbacksAndMessages(null);
            log("Status monitoring stopped");
        }
    }
}
