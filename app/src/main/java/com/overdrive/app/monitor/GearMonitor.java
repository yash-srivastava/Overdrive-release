package com.overdrive.app.monitor;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * Gear Monitor — polling-based gear position monitoring.
 * 
 * Uses polling instead of AbsBYDAutoGearboxListener because the BYD framework's
 * internal learningEPB() method crashes with a UID mismatch when running as shell
 * (UID 2000). The crash kills the BYD device manager's HandlerThread and cascades
 * into daemon restart loops.
 * 
 * Polls getGearboxAutoModeType() every 200ms — fast enough for gear change detection
 * while avoiding the listener crash path entirely.
 */
public class GearMonitor {
    private static final DaemonLogger logger = DaemonLogger.getInstance("GearMonitor");
    
    // Gear constants
    public static final int GEAR_P = 1;
    public static final int GEAR_R = 2;
    public static final int GEAR_N = 3;
    public static final int GEAR_D = 4;
    public static final int GEAR_M = 5;
    public static final int GEAR_S = 6;
    
    private static final long POLL_INTERVAL_MS = 200;  // 5 Hz polling
    private static final long CACHED_GEAR_MAX_AGE_MS = 1000L;
    
    private static GearMonitor instance;
    
    private Context context;
    // Volatile because the poll thread reads these without holding the
    // singleton's monitor; concurrent stop() (synchronized) nullifies them.
    // Volatile gives the poll iteration a consistent snapshot per loop turn.
    private volatile Object gearboxDevice;
    private volatile Method getGearMethod;
    private Thread pollThread;
    private volatile boolean isRunning = false;
    private volatile int currentGear = GEAR_P;
    /** Elapsed-realtime timestamp of the last valid gear observation. */
    private volatile long lastUpdateTime = 0;

    /** Whether the 200ms poll thread is active — i.e. getCurrentGear() is fresh
     *  to within ~POLL_INTERVAL_MS rather than a cold initial value. */
    public boolean isActive() { return isRunning; }
    
    public interface OnGearChangeListener {
        void onGearChanged(int oldGear, int newGear);
    }

    private final java.util.List<OnGearChangeListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addListener(OnGearChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(OnGearChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(int oldGear, int newGear) {
        for (OnGearChangeListener listener : listeners) {
            try {
                listener.onGearChanged(oldGear, newGear);
            } catch (Throwable t) {
                logger.error("Error in OnGearChangeListener: " + t.getMessage(), t);
            }
        }
    }

    // TelemetryDataCollector reference — when set, read gear from its cached snapshot
    // instead of polling the BYD device directly (avoids duplicate CAN bus reads)
    private volatile com.overdrive.app.telemetry.TelemetryDataCollector telemetrySource = null;
    
    private GearMonitor() {}
    
    public static synchronized GearMonitor getInstance() {
        if (instance == null) {
            instance = new GearMonitor();
        }
        return instance;
    }
    
    /**
     * Initialize with context.
     */
    public void init(Context context) {
        this.context = context;
        logger.info("GearMonitor initialized");
    }
    
    /**
     * Set the TelemetryDataCollector as the gear data source.
     * When set and its poller is running, GearMonitor reads gear from the cached
     * snapshot instead of polling the BYD device directly — eliminating duplicate
     * CAN bus reads.
     */
    public void setTelemetrySource(com.overdrive.app.telemetry.TelemetryDataCollector source) {
        this.telemetrySource = source;
    }
    
    /**
     * Start monitoring gear changes via polling.
     *
     * <p>Synchronized: the round-3 RecordingModeManager change made
     * {@code resyncFromHardware} call this every 30s when the monitor isn't
     * running. Without this lock, two concurrent callers (resync ticker +
     * cold-start retry) can both pass the {@code !isRunning} guard, both
     * complete the reflection, and both spawn their own {@code GearPoll}
     * thread — leaking a permanent second thread that double-reports every
     * gear change. The duplicate {@code onGearChanged} deliveries then
     * cancel each other in RMM (gear==currentGear short-circuit) but still
     * waste CPU on every 200ms tick.
     */
    public synchronized void start() {
        if (isRunning) {
            logger.warn("Already running");
            return;
        }

        try {
            logger.info("Starting gear monitor...");
            
            // 1. Try BYDAutoGearboxDevice via BydDeviceHelper
            try {
                gearboxDevice = com.overdrive.app.byd.BydDeviceHelper.getDevice("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice", context);
                if (gearboxDevice == null) {
                    Class<?> gearboxClass = Class.forName("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
                    Method getInstance = gearboxClass.getMethod("getInstance", Context.class);
                    gearboxDevice = getInstance.invoke(null, context);
                }
                if (gearboxDevice != null) {
                    getGearMethod = gearboxDevice.getClass().getMethod("getGearboxAutoModeType");
                }
            } catch (Throwable t) {
                logger.info("BYDAutoGearboxDevice reflection skipped: " + t.getMessage());
            }

            int initialGearRead = -1;
            if (getGearMethod != null && gearboxDevice != null) {
                try {
                    Object initialReadObj = getGearMethod.invoke(gearboxDevice);
                    if (initialReadObj instanceof Number) {
                        initialGearRead = ((Number) initialReadObj).intValue();
                    }
                } catch (Throwable ignored) {}
            }

            if (!isValidGearMode(initialGearRead)) {
                initialGearRead = readFromCarAdapter();
            }
            if (!isValidGearMode(initialGearRead)) {
                initialGearRead = readFromBydDataCollector();
            }
            if (!isValidGearMode(initialGearRead) && com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
                initialGearRead = readGearFromDumpsys();
            }
            // Hardware interlock: if the vehicle is charging, gear is unconditionally P
            try {
                if (com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging()) {
                    initialGearRead = GEAR_P;
                }
            } catch (Throwable ignored) {}

            if (!isValidGearMode(initialGearRead)) {
                initialGearRead = GEAR_P; // Safe fallback
            }

            currentGear = initialGearRead;
            lastUpdateTime = SystemClock.elapsedRealtime();
            logger.info("Initial gear: " + gearToString(currentGear) + (gearboxDevice != null ? " (HAL)" : " (Multi-source fallback)"));
            
            isRunning = true;
            
            final int initialGear = currentGear;
            pollThread = new Thread(() -> {
                while (isRunning) {
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                        if (!isRunning) break;

                        int gear = -1;
                        long gearObservedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();

                        // Hardware interlock: if the vehicle is charging, gear is unconditionally P
                        try {
                            if (com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging()) {
                                gear = GEAR_P;
                            }
                        } catch (Throwable ignored) {}

                        // 1. Try CarAdapterManager / CarBodyManager (DiLink 5.0 / TS framework)
                        if (!isValidGearMode(gear)) {
                            int carAdapterGear = readFromCarAdapter();
                            if (isValidGearMode(carAdapterGear)) {
                                gear = carAdapterGear;
                                gearObservedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();
                            }
                        }

                        // 2. Prefer TelemetryDataCollector's cached snapshot
                        if (!isValidGearMode(gear)) {
                            com.overdrive.app.telemetry.TelemetryDataCollector src = telemetrySource;
                            com.overdrive.app.telemetry.TelemetrySnapshot snap =
                                (src != null) ? src.getLatestSnapshot() : null;
                            long gearAgeMs = snap != null
                                    && snap.gearReadElapsedRealtimeMs >= 0L
                                    ? SystemClock.elapsedRealtime()
                                            - snap.gearReadElapsedRealtimeMs
                                    : Long.MAX_VALUE;
                            if (snap != null
                                    && snap.gearValid
                                    && isValidGearMode(snap.gearMode)
                                    && gearAgeMs >= 0L
                                    && gearAgeMs < CACHED_GEAR_MAX_AGE_MS) {
                                gear = snap.gearMode;
                                gearObservedAtElapsedRealtimeMs =
                                        snap.gearReadElapsedRealtimeMs;
                            }
                        }

                        // 3. Try BydDataCollector fast dynamics / CAN poll
                        if (!isValidGearMode(gear)) {
                            int g = readFromBydDataCollector();
                            if (isValidGearMode(g)) gear = g;
                        }

                        // 4. Try BYDAutoGearboxDevice getter if available
                        if (!isValidGearMode(gear)) {
                            Method getter = getGearMethod;
                            Object device = gearboxDevice;
                            if (getter != null && device != null) {
                                try {
                                    Object res = getter.invoke(device);
                                    if (res instanceof Number) {
                                        int g = ((Number) res).intValue();
                                        if (isValidGearMode(g)) gear = g;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }

                        // 5. DiLink 5.0 HAL property / dumpsys fallback
                        if (!isValidGearMode(gear) && com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
                            int g = readGearFromDumpsys();
                            if (isValidGearMode(g)) {
                                gear = g;
                                gearObservedAtElapsedRealtimeMs = SystemClock.elapsedRealtime();
                            }
                        }

                        if (!isValidGearMode(gear)) {
                            continue;
                        }
                        int previousGear = currentGear;
                        currentGear = gear;
                        lastUpdateTime =
                                gearObservedAtElapsedRealtimeMs;
                        if (gear != previousGear) {
                            logger.info("Gear changed: " + gearToString(previousGear) + " -> " + gearToString(gear));
                            CameraDaemon.onGearChanged(gear);
                            notifyListeners(previousGear, gear);
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        logger.debug("Gear poll error: " + e.getMessage());
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                    }
                }
                logger.info("Gear poll thread stopped");
            }, "GearPoll");
            pollThread.setDaemon(true);
            CameraDaemon.onGearChanged(initialGear);
            notifyListeners(-1, initialGear);
            pollThread.start();
            
            logger.info("Gear monitor started successfully");
            
        } catch (Exception e) {
            logger.error("Failed to start gear monitor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int readFromCarAdapter() {
        try {
            Class<?> camCls = Class.forName("com.ts.lib.caradapter.CarAdapterManager");
            Method getInst = camCls.getMethod("getInstance", Context.class);
            Object cam = getInst.invoke(null, context != null ? context : CameraDaemon.getAppContext());
            if (cam != null) {
                // Check if car service is bound
                try {
                    Method isBound = camCls.getMethod("isCarServiceBound");
                    Object boundObj = isBound.invoke(cam);
                    if (boundObj instanceof Boolean && !((Boolean) boundObj)) {
                        // Reconnect / reset singleton if unbound
                        try {
                            Method connect = camCls.getMethod("connect");
                            connect.invoke(cam);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}

                Method getMgr = camCls.getMethod("getCarAdapterManager", String.class);
                
                // 1. DiLink 5.0 / SL7: CarBodyManager ("body") -> getShiftMode()
                try {
                    Object bodyMgr = getMgr.invoke(cam, "body");
                    if (bodyMgr != null) {
                        try {
                            Method m = bodyMgr.getClass().getMethod("getShiftMode");
                            Object res = m.invoke(bodyMgr);
                            if (res instanceof Number) {
                                int shift = ((Number) res).intValue();
                                // Shift values: 0=parked/charging, 1=P, 2=R, 3=N, 4=D
                                switch (shift) {
                                    case 0:
                                    case 1: return GEAR_P;
                                    case 2: return GEAR_R;
                                    case 3: return GEAR_N;
                                    case 4: return GEAR_D;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}

                // 2. Legacy DiLink / Cabin adapter fallback
                try {
                    Object cabinMgr = getMgr.invoke(cam, "cabin");
                    if (cabinMgr != null) {
                        try {
                            Method m = cabinMgr.getClass().getMethod("getGearboxAutoModeType");
                            Object res = m.invoke(cabinMgr);
                            if (res instanceof Number) {
                                int g = ((Number) res).intValue();
                                if (isValidGearMode(g)) return g;
                            }
                        } catch (Throwable ignored) {}
                        try {
                            Method m = cabinMgr.getClass().getMethod("getGear");
                            Object res = m.invoke(cabinMgr);
                            if (res instanceof Number) {
                                int g = ((Number) res).intValue();
                                if (isValidGearMode(g)) return g;
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private int readFromBydDataCollector() {
        try {
            com.overdrive.app.byd.BydDataCollector collector = com.overdrive.app.byd.BydDataCollector.getInstance();
            if (collector != null) {
                int g = collector.readGearNow();
                if (isValidGearMode(g)) return g;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private volatile long lastDumpsysReadTime = 0;
    private volatile int lastDumpsysGear = -1;

    private static final long DUMPSYS_GEAR_THROTTLE_MS = 3000L;

    private int readGearFromDumpsys() {
        try {
            if (com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging()) {
                return GEAR_P;
            }
        } catch (Throwable ignored) {}

        long now = SystemClock.elapsedRealtime();
        if (now - lastDumpsysReadTime < DUMPSYS_GEAR_THROTTLE_MS) {
            return isValidGearMode(lastDumpsysGear) ? lastDumpsysGear : -1;
        }
        lastDumpsysReadTime = now;

        try {
            // 1. First try CarPropertyBridge if available (zero-fork Binder call)
            try {
                com.overdrive.app.byd.CarPropertyBridge bridge = com.overdrive.app.byd.CarPropertyBridge.getInstance();
                if (bridge != null) {
                    com.overdrive.app.byd.CarPropertyBridge.ReadResult rr = bridge.readProperty("SHIFT_MODE");
                    if (rr != null && rr.success && rr.intValue != null) {
                        int shift = rr.intValue;
                        int decoded = decodeShiftMode(shift);
                        if (isValidGearMode(decoded)) {
                            lastDumpsysGear = decoded;
                            return decoded;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 2. Try CarSvcTelemetry on DiLink 5.0 (extracts PROP_GEAR_R 0x21403a0a)
            try {
                if (com.overdrive.app.byd.DiLink5Platform.isActive()) {
                    int csg = com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.gearValue();
                    if (isValidGearMode(csg)) {
                        lastDumpsysGear = csg;
                        return csg;
                    }
                }
            } catch (Throwable ignored) {}

            // 3. Fallback: dumpsys car_service (throttled to 3 seconds to avoid CPU/IPC saturation)
            String propDump = com.overdrive.app.monitor.AccMonitor.execShell(
                "dumpsys car_service 2>/dev/null | grep -E '0x21406407|0x21403a06|0x21403a0a' | grep 'lastEvent'");
            if (propDump != null && !propDump.isEmpty()) {
                if (propDump.contains("0x21406407") || propDump.contains("0x21403a06") || propDump.contains("0x21403a0a")) {
                    int decoded = -1;
                    if (propDump.contains("int32Values: [4]")) decoded = GEAR_D;
                    else if (propDump.contains("int32Values: [2]")) decoded = GEAR_R;
                    else if (propDump.contains("int32Values: [3]")) decoded = GEAR_N;
                    else if (propDump.contains("int32Values: [1]") || propDump.contains("int32Values: [0]")) decoded = GEAR_P;
                    
                    if (isValidGearMode(decoded)) {
                        lastDumpsysGear = decoded;
                        return decoded;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return isValidGearMode(lastDumpsysGear) ? lastDumpsysGear : -1;
    }

    private static int decodeShiftMode(int shift) {
        switch (shift) {
            case 0:
            case 1: return GEAR_P;
            case 2: return GEAR_R;
            case 3: return GEAR_N;
            case 4: return GEAR_D;
            case 5: return GEAR_M;
            case 6: return GEAR_S;
            default: return -1;
        }
    }
    
    /**
     * Stop monitoring.
     */
    public synchronized void stop() {
        if (!isRunning) {
            return;
        }

        isRunning = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        gearboxDevice = null;
        getGearMethod = null;
        logger.info("Gear monitor stopped");
    }
    
    /**
     * Get current gear.
     */
    public int getCurrentGear() {
        return currentGear;
    }
    
    /**
     * Get last update time.
     */
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
    
    /**
     * Check if running.
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * Convert gear to string.
     */
    public static String gearToString(int gear) {
        switch (gear) {
            case GEAR_P: return "P";
            case GEAR_R: return "R";
            case GEAR_N: return "N";
            case GEAR_D: return "D";
            case GEAR_M: return "M";
            case GEAR_S: return "S";
            default: return "UNKNOWN(" + gear + ")";
        }
    }

    private static boolean isValidGearMode(int gear) {
        return gear >= GEAR_P && gear <= GEAR_S;
    }
}
