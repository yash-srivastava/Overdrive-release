package com.overdrive.app.daemon;

import android.content.Context;
import android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener;
import android.hardware.bydauto.power.BYDAutoPowerDevice;
import android.os.IAccModeManager;
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
    /** svc wifi enable */
    private static String CMD_WIFI_ENABLE() { return Safe.s("GzzLDvODRsKARkPOXEZeIA=="); }
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
    private static int lastPowerLevel = -1;
    private static int lastMcuStatus = -1;
    // Thread for the 10-second loop
    private static Thread systemKeepAliveThread = null;
    // Interval from  (C0004a0)
    private static final long SYSTEM_KEEPALIVE_INTERVAL_MS = 10000;

    // Surveillance IPC
    private static final int SURVEILLANCE_IPC_PORT = 19877;
    private static volatile boolean surveillanceEnabled = false;

    // MCU wake timestamp (for voltage-triggered wake cooldown)
    private static volatile long lastMcuWakeTime = 0;
    
    // ==================== ACTIVE VOLTAGE RECOVERY ====================
    // Thread handle for the active charging loop
    private static Thread mcuChargingThread = null;
    // Pulse interval during active charging (45s keeps MCU awake without flooding CAN bus)
    private static final long MCU_CHARGE_PULSE_INTERVAL_MS = 45000;

    // Context for BYD device access
    private static Context appContext;

    // WakeLock for guaranteed CPU cycles
    private static PowerManager.WakeLock wakeLock;

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
    private static final int SPECIAL_CONFIG_REMOTE_POWER_MODE = 782237711;  // Keeps 5V rails active
    private static final int SPECIAL_CONFIG_DATA_MODULE_POWER = 782237728;  // Keeps Modem/USB active
    
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
     * Get the BYDAutoSpecialDevice instance via reflection.
     * This device controls hidden BCM configuration for peripheral power.
     */
    private static Object getSpecialDevice() {
        if (cachedSpecialDevice != null) return cachedSpecialDevice;
        if (appContext == null) return null;
        
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.special.BYDAutoSpecialDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            cachedSpecialDevice = getInstance.invoke(null, permissiveContext);
            log("BYDAutoSpecialDevice acquired");
        } catch (Exception e) {
            log("Failed to get BYDAutoSpecialDevice: " + e.getMessage());
        }
        return cachedSpecialDevice;
    }
    
    /**
     * Sets a hidden BYD configuration value via BYDAutoSpecialDevice.
     * Used to keep USB/Peripherals powered during Sleep.
     * 
     * @param configId The magic config ID (e.g., 782237711)
     * @param value The value to set (typically 0=OFF, 1=ON)
     */
    private static void setSpecialConfig(int configId, int value) {
        Object device = getSpecialDevice();
        if (device == null) {
            log("Cannot set Special Config - device unavailable");
            return;
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
            setMethod.invoke(device, ids, valueObj);
            
            log("Special Config [" + configId + "] set to: " + value);
        } catch (Exception e) {
            log("Failed to set Special Config [" + configId + "]: " + e.getMessage());
        }
    }
    
    /**
     * Toggles the "Remote Surveillance" power flags in the Gateway/BCM.
     * This tells the BCM: "I am in Remote Surveillance Mode. Do NOT cut the 5V Peripheral Rail."
     * 
     * - ID 782237711: "Remote Power Mode" (Keeps 5V rails active)
     * - ID 782237728: "Data Module Power" (Keeps Modem/USB active)
     * 
     * @param enable true to keep peripherals powered, false to restore stock behavior
     */
    private static void configurePeripheralPower(boolean enable) {
        log("Configuring Peripheral Power (USB/Data): " + (enable ? "ON" : "OFF"));
        
        if (enable) {
            // ENABLE SENTRY POWER MODE
            // Forces BCM to keep peripheral rails hot during ACC OFF
            setSpecialConfig(SPECIAL_CONFIG_REMOTE_POWER_MODE, 1);  // 1 = ON
            setSpecialConfig(SPECIAL_CONFIG_DATA_MODULE_POWER, 1);  // 1 = ON
            setSpecialConfig(-1442840502, 1);
        } else {
            // DISABLE SENTRY POWER MODE (Restore stock behavior)
            // Allows BCM to cut peripheral power normally
            setSpecialConfig(SPECIAL_CONFIG_REMOTE_POWER_MODE, 0);  // 0 = OFF
            setSpecialConfig(SPECIAL_CONFIG_DATA_MODULE_POWER, 0);  // 0 = OFF
            setSpecialConfig(-1442840502, 0);
        }
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

        log("=== ACC Sentry Daemon Starting ===");
        log("UID: " + myUid + " (expected: 2000 shell)");
        log("PID: " + android.os.Process.myPid());
        
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

                // Acquire WakeLock for guaranteed CPU cycles
                acquireWakeLock();
                //forceSmartSleepReflection();
                
                // CRITICAL: Whitelist our app from ACC power management killing
                whitelistAppPackageOld();
                
                // Install shutdown hook for debugging process termination
                installShutdownHook();
                
                // Log initial memory status
                logMemoryStatus();
                
                // Start periodic status monitoring
                startStatusMonitoring();
                
                // Disable BYD traffic monitor app (consumes data/battery in background)
                // NOTE: Removed automatic disable — user can now toggle this from the app drawer menu
                // disableBydTrafficMonitor();
                
                // Note: VehicleDataMonitor is initialized in CameraDaemon (separate process)
                // which handles the HTTP API for vehicle data
            } else {
                log("WARNING: Running without context");
            }

            // Register bodywork listener for ACC state changes
            boolean registered = registerBodyworkListener(context);

            if (!registered) {
                log("Bodywork listener failed - ACC monitoring unavailable");
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

    private static void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        if (appContext == null) return;

        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AccSentry:Core");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            log("WakeLock Acquired");
        } catch (Exception e) {
            log("WakeLock Error: " + e.getMessage());
        }
    }

    private static void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                log("WakeLock Released");
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    // ==================== ACC WHITELIST ====================
    /**
     * OLD METHOD - Kept for reference but not used.
     * The new whitelistFromShellUid() is more reliable.
     */
    private static void whitelistAppPackageOld() {
        String pkg = APP_PACKAGE_NAME();
        log("Whitelisting package " + pkg + " via accmodemanager...");

        boolean success = false;

        try {
            // Get the Binder directly from ServiceManager
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, SERVICE_ACCMODEMANAGER());

            if (binder != null) {
                log("Got accmodemanager binder: " + binder);

                // === STRATEGY 1: Stub Injection ===
                try {
                    IAccModeManager service = IAccModeManager.Stub.asInterface(binder);
                    if (service != null) {
                        service.setPkg2AccWhiteList(pkg);
                        log("Whitelisted successfully via Stub Injection!");
                        success = true;
                    }
                } catch (Exception e) {
                    log("Stub injection failed (Wrong TX ID?): " + e.getMessage());
                }

                // === STRATEGY 2: Brute Force (Fallback) ===
                if (!success) {
                    log("Stub failed, attempting Brute Force...");
                    success = whitelistViaBruteForce(binder, pkg);
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

    private static boolean whitelistViaBruteForce(IBinder binder, String packageName) {
        log("Trying brute force transaction codes for setPkg2AccWhiteList...");
        
        for (int code = 1; code <= 10; code++) {
            try {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken("android.os.IAccModeManager");
                    data.writeString(packageName);
                    
                    boolean transactSuccess = binder.transact(code, data, reply, 0);
                    if (transactSuccess) {
                        try {
                            reply.readException();
                            log("Whitelist SUCCESS with transaction code " + code);
                            return true;
                        } catch (Exception e) {
                            log("TX code " + code + " exception: " + e.getMessage());
                        }
                    }
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            } catch (Exception e) {
            }
        }
        
        log("Brute force whitelist: no working transaction code found");
        return false;
    }

    // ==================== ACC STATE DETECTION ====================

    private static boolean registerBodyworkListener(Context context) {
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

            AccListener listener = new AccListener();
            registerListener.invoke(device, listener);

            log("Bodywork listener registered!");

            // Get initial power level
            try {
                Method getPowerLevel = deviceClass.getMethod("getPowerLevel");
                int level = (Integer) getPowerLevel.invoke(device);
                log("Initial power level: " + powerLevelToString(level));
                lastPowerLevel = level;

                if (level == POWER_LEVEL_OFF) {
                    log("Started with ACC OFF - entering sentry mode");
                    enterSentryMode();
                } else {
                    // ACC is ON - notify CameraDaemon so AccMonitor has correct state
                    log("Started with ACC ON - notifying CameraDaemon");
                    notifyAccState(false);  // accOff=false means ACC is ON
                }
            } catch (Exception e) {
                log("Could not get initial power level: " + e.getMessage());
            }

            return true;

        } catch (Exception e) {
            log("Bodywork registration failed: " + e.getMessage());
            return false;
        }
    }

    private static class AccListener extends AbsBYDAutoBodyworkListener {
        @Override
        public void onPowerLevelChanged(int level) {
            log(">>> POWER LEVEL: " + powerLevelToString(level) + " (was: " + powerLevelToString(lastPowerLevel) + ")");

            if (level == POWER_LEVEL_OFF && lastPowerLevel != POWER_LEVEL_OFF) {
                log("ACC OFF detected");
                enterSentryMode();
            } else if (level >= POWER_LEVEL_ON && lastPowerLevel < POWER_LEVEL_ON) {
                log("ACC ON detected");
                exitSentryMode();
            } else if (level == POWER_LEVEL_ACC && lastPowerLevel >= POWER_LEVEL_ON) {
                // BYD app scenario: car was ON (level 2+) and dropped to ACC (level 1)
                // This is a "turning off" transition — treat as ACC OFF for sentry purposes.
                // Without this, a brief BYD app wake (OFF→ON→ACC→OFF) leaves AccMonitor
                // stuck showing ACC ON because exitSentryMode fired but enterSentryMode
                // only triggers on level 0.
                log("ACC level dropped from ON to ACC — treating as ACC OFF (BYD app shutdown)");
                enterSentryMode();
            }

            lastPowerLevel = level;
        }

        @Override
        public void onAutoSystemStateChanged(int state) {
            log("System state: " + state);
        }

        @Override
        public void onBatteryVoltageLevelChanged(int level) {
            // Discrete level callback (0=LOW, 1=NORMAL)
            // Actual voltage monitoring is done via polling in manageMcuPowerState()
            String levelName = (level == 0) ? "LOW" : (level == 1) ? "NORMAL" : "INVALID";
            log("Car battery level: " + levelName);
            
            // Emergency action on LOW level
            if (level == 0 && inSentryMode) {
                log("CRITICAL: Battery level LOW - triggering emergency wake");
                forceMcuWakeUp();
                
                if (surveillanceEnabled) {
                    log("LOW BATTERY - Disabling surveillance to conserve power");
                    disableSurveillance();
                }
            }
        }
    }

    // ==================== VOLTAGE HYSTERESIS STATE ====================
    // Tracks whether we're in a charging cycle (voltage-based MCU wake)
    private static volatile boolean isVoltageChargingCycle = false;
    
    // Hysteresis thresholds for MCU wake/sleep decisions
    private static final double LOW_VOLTAGE_THRESHOLD = 12.1;      // Wake Trigger (Volts)
    private static final double HEALTHY_VOLTAGE_THRESHOLD = 12.8;  // Sleep Trigger (Volts)
    
    // VehicleDataMonitor listener for voltage-based MCU control
    private static VehicleDataListener vehicleDataListener = null;

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
        if (inSentryMode) {
            log("Already in sentry mode");
            return;
        }

        inSentryMode = true;
        log("=== ENTERING SENTRY MODE ===");

        // CRITICAL: Always notify CameraDaemon that ACC is OFF immediately.
        // enableSurveillance() may skip the IPC if surveillanceEnabled is already true
        // or if the user has surveillance disabled in config, which would leave
        // AccMonitor stuck showing ACC ON (e.g. when parked in a safe zone).
        // This mirrors exitSentryMode() which also calls notifyAccState() first.
        notifyAccState(true);  // accOff=true → ACC is OFF

        // Background thread for setup
        new Thread(() -> {
            try {
                // 1. Initialize voltage monitoring FIRST (for battery protection)
                initVehicleDataMonitor();
                
                // 2. Wake MCU immediately (triggers DC-DC converter for stable power)
                immediateWakeUpMcu();
                
                // 3. Small delay to let MCU stabilize power rails
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                
                // 4. THEN wake the system (screen/CPU)
                performSystemWakeUp();
                
                // 5. Start the keep-alive loop (maintains the wake state)
                startSystemKeepAlive();
                
                // 6. Another small delay to let power stabilize before surveillance
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                
                // 7. Register door lock listener and wait for lock before arming surveillance.
                // When ACC goes OFF and you exit the car, motion detection would pick you up
                // as a false event. By waiting for the doors to lock, we skip your own exit.
                // The pipeline/camera are started by the notifyAccState(true) IPC above
                // (CameraDaemon starts the pipeline on ACC OFF), so they're warming up
                // while we wait for the lock — no extra delay once you lock the car.
                registerDoorLockListenerAndArmOnLock();
                
                // 8. Optional: Telegram daemon (in separate try-catch so surveillance failure doesn't block it)
                try {
                    startTelegramDaemonIfEnabled();
                } catch (Throwable t) {
                    log("Telegram daemon start failed: " + t.getMessage());
                }
                
                log("Sentry mode setup complete");
                
            } catch (Throwable t) {
                log("CRITICAL: Sentry setup failed: " + t.getMessage());
                t.printStackTrace();
                // Don't exit sentry mode - keep-alive may still work
            }
        }, "SentrySetup").start();
        
        log("Sentry mode ACTIVE");
    }

    /**
     * Exit Sentry Mode - Restore normal operation.
     */
    private static void exitSentryMode() {
        if (!inSentryMode) {
            log("Not in sentry mode");
            return;
        }

        log("=== EXITING SENTRY MODE ===");

        // CRITICAL: Set inSentryMode=false FIRST, before stopping the keep-alive thread.
        // The keep-alive loop checks `while (running && inSentryMode)` and its interrupt
        // handler also checks `if (!running || !inSentryMode)`. If we stop the thread
        // while inSentryMode is still true, the interrupt handler sees inSentryMode=true
        // and CONTINUES the loop instead of exiting — racing with the screen-wake thread
        // below and calling setBacklightState(false) after we've already turned the screen on.
        // This race caused intermittent 20-30 second screen blackouts after vehicle ON.
        inSentryMode = false;

        // CRITICAL: Always notify CameraDaemon that ACC is ON, regardless of surveillance state.
        // disableSurveillance() may skip IPC if surveillanceEnabled is already false
        // (e.g. user had surveillance disabled, or safe zone suppressed it),
        // which would leave AccMonitor stuck showing ACC OFF.
        notifyAccState(false);  // accOff=false → ACC is ON

        // Disable surveillance
        disableSurveillance();
        
        // Stop Telegram daemon if it was auto-started
        stopTelegramDaemonIfAutoStarted();
        
        // Stop active charging maintenance if running
        stopChargingMaintenance();
        
        // Stop VehicleDataMonitor listener
        stopVehicleDataMonitor();
        
        // Stop system keep-alive (thread will exit cleanly since inSentryMode is already false)
        stopSystemKeepAlive();

        // Restore backlight — retry a few times with delay.
        // The keep-alive thread should be fully stopped by now (inSentryMode=false
        // ensures it exits on interrupt), but retry in case the BYD system overrides
        // our first attempt during its own ACC ON boot sequence.
        new Thread(() -> {
            for (int attempt = 1; attempt <= 3; attempt++) {
                setBacklightState(true);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
            }
        }, "ScreenWake").start();

        log("Sentry mode DEACTIVATED");
    }
    
    /**
     * Cleanup and shutdown the daemon gracefully.
     * Called on process termination or manual shutdown.
     */
    private static void shutdownDaemon() {
        log("=== DAEMON SHUTDOWN INITIATED ===");
        
        running = false;
        
        // Exit sentry mode if active
        if (inSentryMode) {
            exitSentryMode();
        }
        
        // Stop status monitoring
        stopStatusMonitoring();
        
        // Release wake lock
        releaseWakeLock();
        
        // Release singleton lock
        releaseSingletonLock();
        
        log("=== DAEMON SHUTDOWN COMPLETE ===");
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
     * @return The correct GO_TO_SLEEP_REASON code (9 for older, 13 for newer)
     */
    private static int getSystemSleepReasonCode() {
        // List of possible field names BYD might use across different firmware versions
        String[] possibleFieldNames = {
            "GO_TO_SLEEP_REASON_ACCOFF",      // Primary BYD constant
            "GO_TO_SLEEP_REASON_ACC_OFF",     // Alternative naming
            "GO_TO_SLEEP_REASON_POWER_OFF",   // Generic power off
            "GO_TO_SLEEP_REASON_DEVICE_ADMIN" // Android 10+ constant (value 13)
        };
        
        for (String fieldName : possibleFieldNames) {
            try {
                java.lang.reflect.Field field = PowerManager.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                int value = field.getInt(null);
                // Only log on first successful discovery (cache this ideally)
                // log("Found sleep reason code: " + fieldName + " = " + value);
                return value;
            } catch (NoSuchFieldException e) {
                // Field doesn't exist, try next
            } catch (Exception e) {
                // Access error, try next
            }
        }
        
        // Fallback strategy: Android 10+ (SDK 29) uses 13, older uses 9
        // This matches AOSP GO_TO_SLEEP_REASON_DEVICE_ADMIN (13) vs legacy (9)
        return android.os.Build.VERSION.SDK_INT >= 29 ? 13 : 9;
    }

    /**
     * Performs a validated wake-up call using the correct context ID and details string.
     * This mimics a legitimate ignition event to bypass the ACC lock.
     * Uses "Double-Key" logic (Correct ID + "ACC_ON") to pass security check.
     * 
     * CRITICAL: This is the initial wake call when entering sentry mode.
     * The keep-alive thread maintains this state via userActivity().
     */
    private static void performSystemWakeUp() {
        if (appContext == null) {
            log("performSystemWakeUp: No context available");
            return;
        }
        
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);
            
            // 1. Get the correct lock key (9 or 13) dynamically
            int reasonID = getSystemSleepReasonCode();
            
            // 2. Try the 3-arg wakeUp method (most reliable on BYD)
            try {
                Method method = PowerManager.class.getMethod("wakeUp", Long.TYPE, Integer.TYPE, String.class);
                method.invoke(pm, android.os.SystemClock.uptimeMillis(), reasonID, "ACC_ON");
                log("System wake-up sent (reason: " + reasonID + ")");
                return;
            } catch (NoSuchMethodException e) {
                // Fall through to 1-arg version
            }
            
            // 3. Fallback: 1-arg wakeUp (older Android)
            try {
                Method method = PowerManager.class.getMethod("wakeUp", long.class);
                method.invoke(pm, android.os.SystemClock.uptimeMillis());
                log("System wake-up sent (1-arg fallback)");
                return;
            } catch (NoSuchMethodException e) {
                // Fall through to keyevent
            }
            
            // 4. Last resort: keyevent
            log("wakeUp methods unavailable, using keyevent fallback");
            execShell("input keyevent 224");
            
        } catch (Exception e) {
            log("Wake-up failed: " + e.getMessage());
            // Fallback for extreme cases
            execShell("input keyevent 224");
        }
    }

    private static void setBacklightState(boolean on) {
        log("Setting backlight: " + (on ? "ON" : "OFF"));

        // Try PowerManager reflection
        if (appContext != null) {
            try {
                PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                String methodName = on ? "turnBacklightOn" : "turnBacklightOff";
                try {
                    Method m = pm.getClass().getMethod(methodName, long.class);
                    m.invoke(pm, android.os.SystemClock.uptimeMillis());
                    log("Backlight: PowerManager." + methodName + " SUCCESS");
                    return;
                } catch (NoSuchMethodException e) {
                    // Try PascalCase variant
                    methodName = on ? "TurnBacklightOn" : "TurnBacklightOff";
                    try {
                        Method m = pm.getClass().getMethod(methodName, long.class);
                        m.invoke(pm, android.os.SystemClock.uptimeMillis());
                        log("Backlight: PowerManager." + methodName + " SUCCESS");
                        return;
                    } catch (Exception e2) {
                        // Fall through
                    }
                }
            } catch (Exception e) {
                // Fall through
            }

            // Try BYD Hardware Service
            try {
                Class<?> clazz = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice");
                Method getInstance = clazz.getMethod("getInstance", Context.class);
                Object device = getInstance.invoke(null, appContext);
                String methodName = on ? "turnBacklightOn" : "turnBacklightOff";
                clazz.getMethod(methodName).invoke(device);
                log("Backlight: BYDAutoSettingDevice." + methodName + " SUCCESS");
                return;
            } catch (Exception e) {
                // Fall through
            }
        }

        // Fallback: Settings brightness
        int brightness = on ? 128 : 0;
        execShell("settings put system screen_brightness " + brightness);
        if (on) {
            execShell("input keyevent 224");  // KEYCODE_WAKEUP
        } else {
            execShell("input keyevent 223");  // KEYCODE_SLEEP
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
        
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);
            
            // Method signature: goToSleep(long time, int reason, int flags)
            Method method = PowerManager.class.getMethod("goToSleep", Long.TYPE, Integer.TYPE, Integer.TYPE);
            
            // Dynamically retrieve the system-specific reason code (Compatibility Mode)
            // This ensures the command is accepted by the Body Control Module
            int reasonID = getSystemSleepReasonCode();
            
            // Execute with Flag 1 (GO_TO_SLEEP_FLAG_NO_DOZE)
            // Flag 1 is the critical component: Screen OFF, but CPU/Radio remain ACTIVE.
            method.invoke(pm, android.os.SystemClock.uptimeMillis(), reasonID, 1);
            
        } catch (Exception e) {
            log("Smart sleep state enforcement failed: " + e.getMessage());
            // Graceful fallback to basic backlight control if reflection fails
            setBacklightState(false);
        }
    }

    // ==================== SYSTEM PERSISTENCE SERVICE ====================
    
    /**
     * Starts the System Persistence Service (10-second maintenance loop).
     * Implements the "Refresh & Enforce" pattern:
     * 1. Maintains network interface stability (WiFi)
     * 2. Refreshes CPU wake timer (fake user activity)
     * 3. Enforces stealth power state (screen off, CPU active)
     * 
     * CRITICAL: Uses Throwable catch to survive OutOfMemoryError and other Errors.
     * Thread is NOT a daemon so it survives if main thread has issues.
     */
    private static void startSystemKeepAlive() {
        if (systemKeepAliveThread != null && systemKeepAliveThread.isAlive()) {
            return;
        }

        systemKeepAliveThread = new Thread(() -> {
            log("System Persistence Service started");

            while (running && inSentryMode) {  // Check BOTH flags
                try {
                    // 1. Maintain Network Interface Stability
                    ensureWifiEnabled();
                    injectFakeUserActivity();
                    setBacklightState(false);

                    // 4. Maintenance Cycle Interval (10 seconds)
                    Thread.sleep(SYSTEM_KEEPALIVE_INTERVAL_MS);
                    
                } catch (InterruptedException e) {
                    log("KeepAlive interrupted - checking if should continue...");
                    if (!running || !inSentryMode) {
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
                        if (!running || !inSentryMode) break;
                    }
                }
            }

            log("System Persistence Service stopped");
        }, "SystemKeepAlive");

        // CRITICAL: Not a daemon thread! Survives if main thread has issues.
        systemKeepAliveThread.setDaemon(false);
        systemKeepAliveThread.start();
    }

    private static void stopSystemKeepAlive() {
        if (systemKeepAliveThread != null) {
            log("Stopping System Persistence Service...");
            systemKeepAliveThread.interrupt();
            
            // Wait briefly for clean shutdown
            try {
                systemKeepAliveThread.join(2000);
            } catch (InterruptedException ignored) {}
            
            if (systemKeepAliveThread.isAlive()) {
                log("WARN: KeepAlive thread did not stop cleanly");
            }
            
            systemKeepAliveThread = null;
        }
    }

    /**
     * Checks if Wi-Fi is enabled and forces it ON if not.
     * Equivalent to: Runtime.getRuntime().exec("svc wifi enable");
     */
    private static void ensureWifiEnabled() {
        // We use a lightweight check to avoid spamming the shell log
        // In the decompiled code, they just blindly ran "svc wifi enable"
        // running it blindly is safer for persistence.
        execShell(CMD_WIFI_ENABLE());
    }

    /**
     * Uses Reflection to call PowerManager.userActivity()
     * This mimics the "Fake Touch" to keep CPU awake.
     * 
     * CRITICAL: Checks screen status FIRST to avoid exceptions on some BYD firmware
     * where calling userActivity() when screen is OFF causes issues.
     */
    private static void injectFakeUserActivity() {
        if (appContext == null) return;

        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);

            // CRITICAL: Check screen status FIRST ( pattern)
            // On some BYD firmware, calling userActivity() when screen is OFF fails
            try {
                Method getScreenStatus = PowerManager.class.getMethod("getPowerScreenStatus");
                int screenStatus = (Integer) getScreenStatus.invoke(pm);
                if (screenStatus == 0) {
                    // Screen is OFF - userActivity may fail or be ignored
                    // Skip it - the wakeUp call in performSystemWakeUp() handles keeping CPU alive
                    log("Screen OFF - skipping userActivity");
                    return;
                }
            } catch (NoSuchMethodException e) {
                // Method doesn't exist on this firmware - proceed anyway
            } catch (Exception e) {
                // Access error - proceed anyway
            }

            // 1-arg version ( style)
            try {
                Method method = PowerManager.class.getMethod("userActivity", long.class);
                method.invoke(pm, android.os.SystemClock.uptimeMillis());
                log("userActivity(long) called");
                return;
            } catch (NoSuchMethodException e) {
                log("userActivity: no compatible method found");
            }

            // Fallback: Try 2-arg version first (stealth mode - doesn't turn on screen)
            // noChangeLights = true means "Reset the sleep timer, but don't turn on the screen"
            try {
                Method method = PowerManager.class.getMethod("userActivity", long.class, boolean.class);
                method.invoke(pm, android.os.SystemClock.uptimeMillis(), true);
                log("userActivity(long, boolean) called");
            } catch (NoSuchMethodException e) {
                // Fall through to 1-arg version
            }

        } catch (Exception e) {
            log("userActivity error: " + e.getMessage());
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
        
        // Wake the system first (ensures power rails are active)
        performSystemWakeUp();
        
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
     * Starts the Active Charging Maintenance routine.
     * Launches a background thread that repeatedly pulses the MCU to keep the
     * DC-DC converter active until the target voltage is reached.
     * 
     * This prevents the "Limbo State" where MCU times out and sleeps before
     * the battery has fully recovered.
     */
    private static void startChargingMaintenance() {
        if (isVoltageChargingCycle && mcuChargingThread != null && mcuChargingThread.isAlive()) {
            return; // Already actively charging
        }
        
        log("Starting Active Voltage Recovery (Target: " + HEALTHY_VOLTAGE_THRESHOLD + "V)...");
        isVoltageChargingCycle = true;
        
        mcuChargingThread = new Thread(() -> {
            while (isVoltageChargingCycle && running && inSentryMode) {
                try {
                    // Trigger the DC-DC Converter
                    forceMcuWakeUp();
                    
                    // Wait before the next pulse.
                    // 45s is aggressive enough to prevent MCU sleep (usually 1-5 min timeout)
                    // but relaxed enough to avoid flooding the CAN bus.
                    Thread.sleep(MCU_CHARGE_PULSE_INTERVAL_MS);
                    
                } catch (InterruptedException e) {
                    log("Charging maintenance interrupted");
                    break;
                } catch (Exception e) {
                    log("Charging loop error: " + e.getMessage());
                }
            }
            log("Active Voltage Recovery stopped.");
        }, "McuChargeLoop");
        
        mcuChargingThread.start();
    }
    
    /**
     * Stops the Active Charging Maintenance routine.
     * Called when voltage has recovered to healthy levels.
     */
    private static void stopChargingMaintenance() {
        if (!isVoltageChargingCycle) return;
        
        log("Target voltage reached. Stopping Active Recovery.");
        isVoltageChargingCycle = false;
        
        if (mcuChargingThread != null) {
            mcuChargingThread.interrupt();
            mcuChargingThread = null;
        }
    }

    // ==================== VEHICLE DATA MONITOR INTEGRATION ====================
    
    /**
     * Initialize VehicleDataMonitor and register listener for voltage-based MCU control.
     * Only initializes the 12V battery power monitor (not all monitors) for sentry mode.
     */
    private static void initVehicleDataMonitor() {
        if (appContext == null) {
            log("Cannot init VehicleDataMonitor: no context");
            return;
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
                    // This is the actual voltage from BYDAutoOtaDevice
                    if (!inSentryMode || data == null) return;
                    
                    double voltage = data.voltageVolts;
                    
                    // OUT-OF-RANGE CHECK: Wake MCU if voltage is outside valid bounds (9.0-16.0V)
                    // This catches both critically low AND abnormally high readings
                    if (!data.isValidRange()) {
                        log("VOLTAGE OUT OF RANGE (" + String.format("%.2f", voltage) + "V) - Triggering MCU wake");
                        forceMcuWakeUp();
                    }
                    
                    // HYSTERESIS LOGIC WITH ACTIVE MAINTENANCE
                    if (isVoltageChargingCycle) {
                        // We are currently forcing the MCU to stay awake to charge.
                        // CHECK: Have we reached the healthy threshold?
                        if (voltage >= HEALTHY_VOLTAGE_THRESHOLD) {
                            log("Voltage recovered (" + String.format("%.2f", voltage) + "V).");
                            stopChargingMaintenance();
                            // Result: MCU is finally allowed to sleep.
                        }
                    } else {
                        // We are passively monitoring. The MCU is likely sleeping.
                        // CHECK: Has voltage dropped below critical?
                        if (voltage <= LOW_VOLTAGE_THRESHOLD) {
                            log("LOW VOLTAGE (" + String.format("%.2f", voltage) + "V) DETECTED!");
                            startChargingMaintenance();
                            // Result: Starts the loop that wakes MCU every 45s.
                        }
                    }
                    
                    // Critical safety check - disable surveillance to conserve power
                    if (data.isCritical && surveillanceEnabled) {
                        log("CRITICAL VOLTAGE (" + String.format("%.2f", voltage) + "V) - Disabling surveillance");
                        disableSurveillance();
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
            
        } catch (Exception e) {
            log("VehicleDataMonitor init failed: " + e.getMessage());
        }
    }
    
    /**
     * Stop listening to VehicleDataMonitor (battery power only).
     */
    private static void stopVehicleDataMonitor() {
        try {
            log("Removing VehicleDataMonitor listener...");
            
            if (vehicleDataListener != null) {
                VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
                monitor.removeListener(vehicleDataListener);
                monitor.stopBatteryPowerOnly();
                vehicleDataListener = null;
            }
            
            isVoltageChargingCycle = false;
            
            log("VehicleDataMonitor listener removed");
            
        } catch (Exception e) {
            log("VehicleDataMonitor cleanup failed: " + e.getMessage());
        }
    }

    // ==================== SURVEILLANCE ====================

    private static void enableSurveillance() {
        if (surveillanceEnabled) return;

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

        // Retry with backoff — CameraDaemon may not be up yet after boot
        int maxRetries = 10;
        long retryDelayMs = 3000; // Start with 3 seconds

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
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
        if (!surveillanceEnabled) return;

        log("Disabling surveillance (session only — preserving user preference)...");

        try {
            // Only send accOff=false to signal ACC ON.
            // Do NOT send enabled=false — that would overwrite the user's
            // surveillance preference in UnifiedConfigManager, preventing
            // auto-start on the next ACC OFF cycle.
            JSONObject cmd = new JSONObject();
            cmd.put("command", "SET_CONFIG");
            JSONObject config = new JSONObject();
            config.put("accOff", false);
            cmd.put("config", config);
            
            sendSurveillanceCommandRaw(cmd);
            surveillanceEnabled = false;
            log("Surveillance session STOPPED (user preference preserved)");
        } catch (Exception e) {
            log("WARN: Failed to disable surveillance: " + e.getMessage());
        }
    }

    /**
     * Notify CameraDaemon of ACC state change.
     * This updates AccMonitor so HTTP API returns correct acc status.
     * 
     * @param accOff true if ACC is OFF, false if ACC is ON
     */
    
    // ==================== DOOR LOCK GATED SURVEILLANCE ====================
    // When ACC goes OFF, we start the pipeline/camera immediately but suppress
    // motion detection until the car is locked. This prevents your own exit
    // from the vehicle being detected as a sentry event.
    
    private static Object doorLockDevice = null;
    private static volatile boolean doorLockListenerArmed = false;
    // Timeout: if doors aren't locked within this window, arm surveillance anyway
    // (user may have walked away without locking, or lock event wasn't detected)
    private static final long DOOR_LOCK_ARM_TIMEOUT_MS = 30_000;  // 30 seconds
    
    // Door lock state constants (hardcoded from BYD SDK docs)
    // DOOR_LOCK_STATE_INVALID = 0, DOOR_LOCK_STATE_UNLOCK = 1, DOOR_LOCK_STATE_LOCK = 2
    private static final int DOOR_STATE_INVALID = 0;
    private static final int DOOR_STATE_UNLOCK = 1;
    private static final int DOOR_STATE_LOCK = 2;
    
    /**
     * Initialize door lock device using BydDeviceHelper (proven pattern from BydDataCollector).
     * Also registers an IBYDAutoListener for real-time lock state change events.
     * Falls back to polling if listener registration fails.
     */
    private static void registerDoorLockListenerAndArmOnLock() {
        if (appContext == null) {
            log("No context — arming surveillance immediately (no door lock gate)");
            enableSurveillance();
            return;
        }
        
        // Use BydDeviceHelper.getDevice — same proven pattern as BydDataCollector
        doorLockDevice = com.overdrive.app.byd.BydDeviceHelper.getDevice(
            "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice",
            new PermissionBypassContext(appContext));
        
        if (doorLockDevice == null) {
            log("BYDAutoDoorLockDevice not available — arming surveillance immediately");
            enableSurveillance();
            return;
        }
        
        log("DoorLock device initialized: " + doorLockDevice.getClass().getSimpleName());
        
        // Dump all door lock states for debugging
        logAllDoorStates();
        
        // Check if already locked (user may have locked before ACC OFF, e.g., remote lock)
        if (isDriverDoorLocked()) {
            // If the module is asleep (all INVALID), the car just shut down.
            // Add a short grace period so we don't record the owner walking away.
            // If the module reports actual LOCKED state, arm immediately.
            Object result = com.overdrive.app.byd.BydDeviceHelper.callGetter(
                doorLockDevice, "getDoorLockStatus", 1);
            int state = (result instanceof Number) ? ((Number) result).intValue() : -1;
            
            if (state == DOOR_STATE_INVALID) {
                log("Door lock module asleep — adding 10s grace period before arming");
                new Thread(() -> {
                    try {
                        Thread.sleep(10_000);
                        if (inSentryMode && !surveillanceEnabled) {
                            log("Grace period complete — arming surveillance");
                            enableSurveillance();
                        }
                    } catch (InterruptedException ignored) {}
                }, "DoorLockGrace").start();
            } else {
                log("Doors already locked — arming surveillance immediately");
                enableSurveillance();
            }
            return;
        }
        
        log("Doors unlocked — registering listener + polling for lock event...");
        doorLockListenerArmed = false;
        
        // Register IBYDAutoListener for real-time door lock events
        // BydDeviceHelper uses a Proxy on IBYDAutoListener interface which works
        // even though AbsBYDAutoDoorLockListener is an abstract class.
        try {
            boolean registered = com.overdrive.app.byd.BydDeviceHelper.registerListener(
                doorLockDevice,
                (methodName, args) -> {
                    // onDoorLockStatusChanged(int area, int state)
                    if ("onDoorLockStatusChanged".equals(methodName) && args != null && args.length >= 2) {
                        int area = ((Number) args[0]).intValue();
                        int state = ((Number) args[1]).intValue();
                        String stateName = doorStateToString(state);
                        log("Door lock EVENT: area=" + area + " state=" + stateName + " (" + state + ")");
                        
                        // Any door locking means the user has locked the car
                        if (state == DOOR_STATE_LOCK && !doorLockListenerArmed && inSentryMode) {
                            doorLockListenerArmed = true;
                            log("Door LOCKED via listener event — arming surveillance");
                            enableSurveillance();
                        }
                    }
                });
            log("Door lock listener registered: " + registered);
        } catch (Exception e) {
            log("Door lock listener registration failed: " + e.getMessage() + " — relying on polling");
        }
        
        // Poll for door lock state with timeout (backup for listener)
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + DOOR_LOCK_ARM_TIMEOUT_MS;
            log("Door lock poll started (timeout=" + (DOOR_LOCK_ARM_TIMEOUT_MS / 1000) + "s)");
            
            while (inSentryMode && System.currentTimeMillis() < deadline) {
                try {
                    // If listener already armed, we're done
                    if (doorLockListenerArmed || surveillanceEnabled) {
                        log("Door lock poll: surveillance already armed (listener=" + doorLockListenerArmed + ")");
                        return;
                    }
                    
                    if (isDriverDoorLocked()) {
                        log("All doors LOCKED (via poll) — arming surveillance");
                        enableSurveillance();
                        return;
                    }
                    Thread.sleep(2000);  // Check every 2 seconds
                } catch (InterruptedException e) {
                    if (!inSentryMode) {
                        log("Door lock poll cancelled — exited sentry mode");
                        return;
                    }
                } catch (Exception e) {
                    log("Door lock poll error: " + e.getMessage());
                    break;
                }
            }
            
            if (inSentryMode && !surveillanceEnabled && !doorLockListenerArmed) {
                log("Door lock timeout (" + (DOOR_LOCK_ARM_TIMEOUT_MS / 1000) + "s) — arming surveillance anyway");
                enableSurveillance();
            }
        }, "DoorLockPoll").start();
    }
    
    /**
     * Check if the driver's door (left front) is locked.
     * Uses BydDeviceHelper.callGetter — same proven pattern as BydDataCollector.collectDoorLock.
     * BydDataCollector queries areas 1-7 by integer index. Area 1 = left front (driver's door).
     * 
     * IMPORTANT: When ACC is OFF, the BYD door lock module goes to sleep and returns
     * INVALID (0) for all electronic locks. We treat "all doors INVALID" as locked,
     * because the module only sleeps after the car is fully shut down and secured.
     * If any door were physically unlocked/open, the car stays semi-awake.
     */
    private static boolean isDriverDoorLocked() {
        if (doorLockDevice == null) {
            log("Door lock check: device is null");
            return false;
        }
        
        // Use BydDeviceHelper.callGetter with area index (same as BydDataCollector)
        // Area 1 = DOOR_LOCK_AREA_LEFT_FRONT (driver's door)
        Object result = com.overdrive.app.byd.BydDeviceHelper.callGetter(
            doorLockDevice, "getDoorLockStatus", 1);
        
        int state = (result instanceof Number) ? ((Number) result).intValue() : -1;
        String stateName = doorStateToString(state);
        log("Door lock poll: driver door=" + stateName + " (raw=" + state + ")");
        
        if (state == DOOR_STATE_LOCK) {
            return true;
        }
        
        // When ACC is OFF, the door lock module sleeps and returns INVALID (0) for all doors.
        // Check if ALL electronic doors are INVALID — if so, the module is asleep which means
        // the car is fully shut down and secured. Treat this as "locked".
        if (state == DOOR_STATE_INVALID) {
            boolean allInvalid = true;
            // Check areas 1-5 (the 4 doors + back, skip child locks 6-7)
            for (int area = 1; area <= 5; area++) {
                Object r = com.overdrive.app.byd.BydDeviceHelper.callGetter(
                    doorLockDevice, "getDoorLockStatus", area);
                int s = (r instanceof Number) ? ((Number) r).intValue() : -1;
                if (s != DOOR_STATE_INVALID) {
                    allInvalid = false;
                    break;
                }
            }
            if (allInvalid) {
                log("Door lock module asleep (all doors INVALID) — treating as LOCKED");
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Log all door lock states for debugging.
     * Queries areas 1-7 (same as BydDataCollector.collectDoorLock).
     */
    private static void logAllDoorStates() {
        if (doorLockDevice == null) return;
        
        String[] areaNames = {"?", "LeftFront", "LeftRear", "RightFront", "RightRear", 
                              "Back", "ChildLockLeft", "ChildLockRight"};
        StringBuilder sb = new StringBuilder("Door lock states: ");
        for (int i = 1; i <= 7; i++) {
            Object result = com.overdrive.app.byd.BydDeviceHelper.callGetter(
                doorLockDevice, "getDoorLockStatus", i);
            int state = (result instanceof Number) ? ((Number) result).intValue() : -1;
            if (i > 1) sb.append(", ");
            sb.append(areaNames[i]).append("=").append(doorStateToString(state));
        }
        log(sb.toString());
    }
    
    private static String doorStateToString(int state) {
        switch (state) {
            case DOOR_STATE_INVALID: return "INVALID";
            case DOOR_STATE_UNLOCK: return "UNLOCKED";
            case DOOR_STATE_LOCK: return "LOCKED";
            default: return "UNKNOWN(" + state + ")";
        }
    }
    
    /**
     * Notify CameraDaemon of ACC state change.
     * This updates AccMonitor so HTTP API returns correct acc status.
     * 
     * @param accOff true if ACC is OFF, false if ACC is ON
     */
    private static void notifyAccState(boolean accOff) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", "SET_CONFIG");
            JSONObject config = new JSONObject();
            config.put("accOff", accOff);
            cmd.put("config", config);
            
            sendSurveillanceCommandRaw(cmd);
            log("ACC state notified to CameraDaemon: accOff=" + accOff);
        } catch (Exception e) {
            log("WARN: Failed to notify ACC state: " + e.getMessage());
        }
    }

    private static JSONObject sendSurveillanceCommandRaw(JSONObject command) {
        Socket socket = null;
        try {
            socket = new Socket("127.0.0.1", SURVEILLANCE_IPC_PORT);
            socket.setSoTimeout(5000);

            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            writer.println(command.toString());
            String responseLine = reader.readLine();

            return responseLine != null ? new JSONObject(responseLine) : null;
        } catch (Exception e) {
            log("Surveillance IPC error: " + e.getMessage());
            return null;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ==================== TELEGRAM DAEMON AUTO-START ====================
    
    private static final String TELEGRAM_CONFIG_FILE = null; // Lazy init
    private static String getTelegramConfigFile() { return PATH_TELEGRAM_CONFIG(); }
    private static final String TELEGRAM_DAEMON_PROCESS = "telegram_bot_daemon";
    
    /**
     * Check if Telegram daemon auto-start on ACC off is enabled.
     */
    private static boolean isTelegramAutoStartEnabled() {
        try {
            java.io.File configFile = new java.io.File(getTelegramConfigFile());
            log("Checking telegram config: " + getTelegramConfigFile());
            
            if (!configFile.exists()) {
                log("Telegram config file does not exist");
                return false;
            }
            
            java.util.Properties props = new java.util.Properties();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(configFile)) {
                props.load(fis);
            }
            
            String autoStart = props.getProperty("auto_start_acc_off", "false");
            log("Telegram auto_start_acc_off = " + autoStart);
            return "true".equalsIgnoreCase(autoStart);
        } catch (Exception e) {
            log("Error reading telegram config: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if Telegram daemon is running.
     */
    private static boolean isTelegramDaemonRunning() {
        String output = execShell("ps -A | grep " + TELEGRAM_DAEMON_PROCESS + " | grep -v grep");
        return output != null && !output.trim().isEmpty();
    }
    
    /**
     * Start Telegram daemon if auto-start is enabled.
     * Retries once if first attempt fails (APK path detection can be flaky when ACC is off).
     */
    private static void startTelegramDaemonIfEnabled() {
        log("Checking if Telegram daemon should auto-start...");
        
        if (!isTelegramAutoStartEnabled()) {
            log("Telegram auto-start not enabled, skipping");
            return;
        }
        
        // Check if user explicitly stopped it via Telegram command
        try {
            if (com.overdrive.app.daemon.telegram.DaemonCommandHandler.isDaemonStoppedViaTelegram("telegram")) {
                log("Telegram daemon was stopped via Telegram command, not auto-starting");
                return;
            }
        } catch (Exception e) {
            // State file not available, proceed with auto-start
        }
        
        if (isTelegramDaemonRunning()) {
            log("Telegram daemon already running");
            return;
        }
        
        // Try up to 2 times (APK path detection can fail when system is still waking up)
        for (int attempt = 1; attempt <= 2; attempt++) {
            log("Starting Telegram daemon (attempt " + attempt + "/2)...");
            
            if (attempt > 1) {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
            
            try {
                launchTelegramDaemon();
                
                // Verify it started
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                
                if (isTelegramDaemonRunning()) {
                    log("Telegram daemon started successfully (attempt " + attempt + ")");
                    return;
                } else {
                    log("Telegram daemon not running after attempt " + attempt);
                    String logContent = execShell("tail -20 /data/local/tmp/telegrambotdaemon.log 2>/dev/null");
                    if (logContent != null && !logContent.isEmpty()) {
                        log("Telegram daemon log: " + logContent);
                    }
                }
            } catch (Exception e) {
                log("Telegram daemon launch error (attempt " + attempt + "): " + e.getMessage());
            }
        }
        
        log("WARN: Telegram daemon failed to start after 2 attempts");
    }
    
    /**
     * Launch the Telegram daemon process.
     */
    private static void launchTelegramDaemon() {
        
        // SOTA: Use pm path to get current APK path (most reliable method)
        // This ensures we always use the correct path even after app updates
        String apkPath = execShell("pm path com.overdrive.app 2>/dev/null | head -1 | cut -d: -f2");
        
        // Fallback to ls if pm path fails
        if (apkPath == null || apkPath.trim().isEmpty()) {
            log("pm path failed, using ls fallback");
            apkPath = execShell("ls /data/app/*/com.overdrive.app*/base.apk 2>/dev/null | head -1");
            if (apkPath == null || apkPath.trim().isEmpty()) {
                apkPath = execShell("ls /data/app/com.overdrive.app*/base.apk 2>/dev/null | head -1");
            }
        }
        
        if (apkPath == null || apkPath.trim().isEmpty()) {
            log("ERROR: Could not find APK path for com.overdrive.app");
            return;
        }
        
        apkPath = apkPath.trim();
        log("Using APK path: " + apkPath);
        
        // Launch via app_process with nice-name (matching DaemonLauncher.kt format)
        String innerCmd = "CLASSPATH=" + apkPath + " " +
                         "app_process /system/bin " +
                         "--nice-name=" + TELEGRAM_DAEMON_PROCESS + " " +
                         "com.overdrive.app.daemon.TelegramBotDaemon";
        
        String cmd = "nohup sh -c '" + innerCmd + "' > /data/local/tmp/telegrambotdaemon.log 2>&1 &";
        
        log("Telegram launch command: " + cmd);
        execShell(cmd);
    }
    
    /**
     * Stop Telegram daemon if it was auto-started.
     */
    private static void stopTelegramDaemonIfAutoStarted() {
        if (!isTelegramAutoStartEnabled()) {
            log("Telegram auto-start not enabled, not stopping");
            return;
        }
        
        if (!isTelegramDaemonRunning()) {
            log("Telegram daemon not running");
            return;
        }
        
        log("Stopping Telegram daemon (vehicle on)...");
        execShell("pkill -9 -f " + TELEGRAM_DAEMON_PROCESS + " 2>/dev/null");
        execShell("rm -f /data/local/tmp/telegram_bot_daemon.lock 2>/dev/null");
        log("Telegram daemon stopped");
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
     * Disable BYD's built-in traffic monitor app.
     * It runs in the background consuming mobile data and battery.
     */
    private static void disableBydTrafficMonitor() {
        try {
            String result = execShell("pm disable-user --user 0 com.byd.trafficmonitor 2>&1");
            log("Disable BYD traffic monitor: " + result);
        } catch (Exception e) {
            log("Failed to disable BYD traffic monitor: " + e.getMessage());
        }
    }

    private static String execShell(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            process.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            return output.toString().trim();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
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
                    long uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000;
                    long uptimeMinutes = uptimeSeconds / 60;
                    
                    log("=== STATUS CHECK ===");
                    log("  Uptime: " + uptimeMinutes + "m " + (uptimeSeconds % 60) + "s");
                    log("  WakeLock: " + (wakeLock != null && wakeLock.isHeld()));
                    log("  InSentryMode: " + inSentryMode);
                    log("  Running: " + running);
                    log("  KeepAlive thread: " + (systemKeepAliveThread != null && systemKeepAliveThread.isAlive()));
                    log("  Charging thread: " + (mcuChargingThread != null && mcuChargingThread.isAlive()));
                    log("  Surveillance: " + surveillanceEnabled);
                    log("  Last power level: " + powerLevelToString(lastPowerLevel));
                    log("  Last MCU status: " + lastMcuStatus);
                    
                    // Check MCU status
                    int currentMcuStatus = getMcuStatus();
                    if (currentMcuStatus != -1) {
                        log("  Current MCU status: " + currentMcuStatus);
                    }
                    
                    // Log memory every 5 minutes
                    if (uptimeMinutes % 5 == 0) {
                        logMemoryStatus();
                    }
                    
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
     * Stop periodic status monitoring.
     */
    private static void stopStatusMonitoring() {
        if (statusHandler != null) {
            statusHandler.removeCallbacksAndMessages(null);
            log("Status monitoring stopped");
        }
    }
}
