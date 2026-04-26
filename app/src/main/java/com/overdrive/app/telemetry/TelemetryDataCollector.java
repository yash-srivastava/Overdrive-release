package com.overdrive.app.telemetry;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls BYD device APIs at 5 Hz via reflection to collect vehicle telemetry.
 * Produces immutable {@link TelemetrySnapshot} objects consumed by the overlay renderer.
 * Uses last-known-good fallback per field on device API failure.
 */
public class TelemetryDataCollector {

    private static final String TAG = "TelemetryDataCollector";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final long POLL_INTERVAL_MS = 200; // 5 Hz — only used when overlay recording is active
    private static final long SLOW_POLL_INTERVAL_MS = 1000; // 1 Hz fallback when not recording

    // Slow-path sub-polling: turn signals and seatbelts don't change at 5Hz.
    // Poll them at 1Hz (every 5th fast poll) to save 4 reflection calls per cycle.
    private static final int SLOW_FIELD_DIVISOR = 5; // every 5th poll = 1Hz at 200ms base

    // BYDAutoSpeedDevice
    private Object speedDevice;
    private Method getCurrentSpeedMethod;
    private Method getAccelerateDeepnessMethod;
    private Method getBrakeDeepnessMethod;

    // BYDAutoGearboxDevice
    private Object gearboxDevice;
    private Method getGearboxAutoModeTypeMethod;
    private Method getBrakePedalStateMethod;

    // Turn signal detection via getTurnLightFlashState()
    // Returns: 0=off, 1=left, 2=right, 3=hazard (model-dependent)
    private Object lightDevice;
    private java.lang.reflect.Method getTurnLightFlashStateMethod;

    // Seatbelt via BYDAutoInstrumentDevice.getSafetyBeltStatus(int)
    // Fallback: BYDAutoSafetyBeltDevice.getPassengerStatus(int)
    private Object safetyBeltDevice;
    private java.lang.reflect.Method getPassengerStatusMethod;
    private Object instrumentDeviceForBelt;
    private java.lang.reflect.Method getSafetyBeltStatusMethod;

    // Polling
    private ScheduledExecutorService executor;
    private volatile TelemetrySnapshot latestSnapshot;
    
    // Recording mode: when true, polls at 200ms (5Hz) for overlay.
    // When false, polls at 1000ms (1Hz) for trip telemetry / ABRP only.
    private volatile boolean overlayRecordingActive = false;
    
    // Reference counting: polling stays alive as long as any consumer needs it
    // (pipeline overlay, trip recorder, etc.)
    private final java.util.concurrent.atomic.AtomicInteger pollingRefCount = 
        new java.util.concurrent.atomic.AtomicInteger(0);

    // Last-known-good values (used as fallback when a device call fails)
    private int lastSpeedKmh = 0;
    private int lastAccelPercent = 0;
    private int lastBrakePercent = 0;
    // Staleness detection: if speed stays identical for too long, force reconnect
    private int staleSpeedCount = 0;
    private int prevSpeedForStaleCheck = -1;
    private static final int STALE_THRESHOLD = 50; // 10 seconds at 5Hz
    private boolean lastBrakePedalPressed = false;
    private int lastGearMode = 1; // P
    private boolean lastLeftTurn = false;
    private boolean lastRightTurn = false;
    private boolean[] lastSeatbelts = new boolean[]{true, true}; // buckled by default
    private long pollCount = 0;
    private int leftTurnStickyCount = 0;
    private int rightTurnStickyCount = 0;

    /**
     * Initialize BYD device handles via reflection using PermissionBypassContext.
     * Each device is initialized independently — if one fails, others still work.
     */
    public void init(Context context) {
        logger.info("Initializing telemetry device access...");

        Context permissiveContext = new PermissionBypassContext(context);
        this.savedContext = permissiveContext;

        // BYDAutoSpeedDevice — getCurrentSpeed(), getAccelerateDeepness(), getBrakeDeepness()
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            speedDevice = getInstance.invoke(null, permissiveContext);
            getCurrentSpeedMethod = cls.getMethod("getCurrentSpeed");
            getAccelerateDeepnessMethod = cls.getMethod("getAccelerateDeepness");
            getBrakeDeepnessMethod = cls.getMethod("getBrakeDeepness");
            logger.info("BYDAutoSpeedDevice initialized");
        } catch (Exception e) {
            logger.warn("BYDAutoSpeedDevice unavailable: " + e.getMessage());
        }

        // BYDAutoGearboxDevice — getGearboxAutoModeType(), getBrakePedalState()
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            gearboxDevice = getInstance.invoke(null, permissiveContext);
            getGearboxAutoModeTypeMethod = cls.getMethod("getGearboxAutoModeType");
            getBrakePedalStateMethod = cls.getMethod("getBrakePedalState");
            logger.info("BYDAutoGearboxDevice initialized");
        } catch (Exception e) {
            logger.warn("BYDAutoGearboxDevice unavailable: " + e.getMessage());
        }

        // BYDAutoLightDevice — getTurnLightFlashState() (more reliable than getLightStatus)
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.light.BYDAutoLightDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            lightDevice = getInstance.invoke(null, permissiveContext);
            getTurnLightFlashStateMethod = cls.getMethod("getTurnLightFlashState");
            logger.info("BYDAutoLightDevice initialized (using getTurnLightFlashState)");
        } catch (Exception e) {
            logger.warn("BYDAutoLightDevice unavailable: " + e.getMessage());
        }

        // Seatbelt: Try InstrumentDevice.getSafetyBeltStatus(int) first, fallback to SafetyBeltDevice
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            instrumentDeviceForBelt = getInstance.invoke(null, permissiveContext);
            getSafetyBeltStatusMethod = cls.getMethod("getSafetyBeltStatus", int.class);
            logger.info("Using InstrumentDevice for seatbelt status");
        } catch (Exception e) {

            try {
                Class<?> cls2 = Class.forName("android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice");
                Method getInstance2 = cls2.getMethod("getInstance", Context.class);
                safetyBeltDevice = getInstance2.invoke(null, permissiveContext);
                getPassengerStatusMethod = cls2.getMethod("getPassengerStatus", int.class);
                logger.info("BYDAutoSafetyBeltDevice initialized (fallback)");
            } catch (Exception e2) {
                logger.warn("No seatbelt device available: " + e2.getMessage());
            }
        }

        // Initialize with safe defaults
        latestSnapshot = TelemetrySnapshot.createDefault();

        logger.info("Telemetry device initialization complete");
    }

    /**
     * Start polling BYD device APIs on a background thread.
     * Rate depends on overlayRecordingActive:
     *   true  → 200ms (5Hz) for video overlay — only polls speed/accel/brake/gear fast
     *   false → 1000ms (1Hz) for trip telemetry / ABRP
     * Uses reference counting — multiple callers can request polling,
     * and it only stops when ALL callers have called stopPolling().
     */
    public void startPolling() {
        int refs = pollingRefCount.incrementAndGet();
        if (executor != null && !executor.isShutdown()) {
            logger.info("Polling already running (refCount=" + refs + ")");
            return;
        }
        long interval = overlayRecordingActive ? POLL_INTERVAL_MS : SLOW_POLL_INTERVAL_MS;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TelemetryPoller");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::poll, 0, interval, TimeUnit.MILLISECONDS);
        logger.info("Telemetry polling started at " + (1000 / interval) + " Hz (overlay=" + overlayRecordingActive + ", refCount=" + refs + ")");
    }

    /**
     * Set overlay recording mode. When active, polling runs at 5Hz.
     * When inactive, drops to 1Hz. Restarts the scheduler if the rate changes.
     */
    public void setOverlayRecordingActive(boolean active) {
        if (this.overlayRecordingActive == active) return;
        this.overlayRecordingActive = active;
        logger.info("Overlay recording " + (active ? "ACTIVE (5Hz)" : "INACTIVE (1Hz)"));
        // Restart scheduler at new rate if currently running
        restartAtCurrentRate();
    }

    /**
     * Restarts the polling scheduler at the rate matching the current overlayRecordingActive state.
     * No-op if the scheduler is not running.
     */
    private void restartAtCurrentRate() {
        if (executor == null || executor.isShutdown()) return;
        executor.shutdown();
        executor = null;
        long interval = overlayRecordingActive ? POLL_INTERVAL_MS : SLOW_POLL_INTERVAL_MS;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TelemetryPoller");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::poll, 0, interval, TimeUnit.MILLISECONDS);
        logger.info("Telemetry polling restarted at " + (1000 / interval) + " Hz");
    }

    /**
     * Request to stop polling. Only actually stops when all consumers have released.
     * If overlay recording was deactivated but other consumers remain, downgrades to 1Hz.
     */
    public void stopPolling() {
        int refs = pollingRefCount.decrementAndGet();
        if (refs < 0) {
            pollingRefCount.set(0);
            refs = 0;
        }
        if (refs > 0) {
            logger.info("Polling stop requested but still needed (refCount=" + refs + ")");
            // If overlay just stopped but trip recorder still needs polling, downgrade rate
            if (!overlayRecordingActive) {
                restartAtCurrentRate();
            }
            return;
        }
        if (executor != null) {
            executor.shutdown();
            executor = null;
            logger.info("Telemetry polling stopped (refCount=0)");
        }
    }
    
    /**
     * Force stop polling regardless of reference count.
     * Used during daemon shutdown.
     */
    public void forceStopPolling() {
        pollingRefCount.set(0);
        if (executor != null) {
            executor.shutdown();
            executor = null;
            logger.info("Telemetry polling force-stopped");
        }
    }

    /**
     * Returns the latest telemetry snapshot (thread-safe via volatile reference).
     */
    public TelemetrySnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    /**
     * Poll all BYD devices and produce a new TelemetrySnapshot.
     * On failure for any individual field, uses the last-known-good value.
     */
    private void poll() {
        try {
            pollInner();
        } catch (Throwable t) {
            // CRITICAL: ScheduledExecutorService silently stops if task throws.
            // Catch everything to keep polling alive.
            logger.error("Poll error (keeping alive): " + t.getMessage());
        }
    }

    private void pollInner() {
        int speedKmh = lastSpeedKmh;
        int accelPercent = lastAccelPercent;
        int brakePercent = lastBrakePercent;
        boolean brakePedalPressed = lastBrakePedalPressed;
        int gearMode = lastGearMode;
        boolean leftTurn = lastLeftTurn;
        boolean rightTurn = lastRightTurn;
        boolean[] seatbelts = lastSeatbelts;

        // ── FAST PATH: speed, accel, brake, gear (every poll) ──
        // These are the only fields that change rapidly during driving
        // and are needed for the video overlay at 5Hz.

        // Speed device: getCurrentSpeed(), getAccelerateDeepness(), getBrakeDeepness()
        if (speedDevice != null) {
            boolean deviceFailed = false;
            try {
                double rawSpeed = (double) getCurrentSpeedMethod.invoke(speedDevice);
                speedKmh = (int) rawSpeed;
                lastSpeedKmh = speedKmh;
            } catch (Exception e) {
                logger.warn("Failed to read speed: " + e.getMessage());
                deviceFailed = true;
            }
            try {
                accelPercent = (int) getAccelerateDeepnessMethod.invoke(speedDevice);
                lastAccelPercent = accelPercent;
            } catch (Exception e) {
                logger.warn("Failed to read accel pedal: " + e.getMessage());
                deviceFailed = true;
            }
            try {
                brakePercent = (int) getBrakeDeepnessMethod.invoke(speedDevice);
                lastBrakePercent = brakePercent;
            } catch (Exception e) {
                logger.warn("Failed to read brake depth: " + e.getMessage());
                deviceFailed = true;
            }
            // If any read failed, try to re-obtain the device reference
            if (deviceFailed) {
                boolean reconnected = tryReconnectSpeedDevice();
                if (reconnected) {
                    speedKmh = lastSpeedKmh;
                    accelPercent = lastAccelPercent;
                    brakePercent = lastBrakePercent;
                }
            }

            // Staleness detection: if speed value is identical for 10+ seconds, force reconnect
            if (speedKmh == prevSpeedForStaleCheck && !(speedKmh == 0 && lastGearMode == 1)) {
                staleSpeedCount++;
                if (staleSpeedCount >= STALE_THRESHOLD) {
                    logger.warn("Speed device appears stale (same value " + speedKmh + " for " + (staleSpeedCount / 5) + "s), reconnecting");
                    boolean reconnected = tryReconnectSpeedDevice();
                    if (reconnected) {
                        speedKmh = lastSpeedKmh;
                        accelPercent = lastAccelPercent;
                        brakePercent = lastBrakePercent;
                    }
                    staleSpeedCount = 0;
                    prevSpeedForStaleCheck = -1;
                }
            } else {
                staleSpeedCount = 0;
                prevSpeedForStaleCheck = speedKmh;
            }
        }

        // Gearbox: gear mode (every poll — changes on shift)
        if (gearboxDevice != null) {
            try {
                gearMode = (int) getGearboxAutoModeTypeMethod.invoke(gearboxDevice);
                lastGearMode = gearMode;
            } catch (Exception e) {
                logger.warn("Failed to read gear mode: " + e.getMessage());
            }
        }

        // ── SLOW PATH: turn signals, brake pedal state, seatbelts (every 5th poll = 1Hz) ──
        // These change infrequently and don't need 5Hz resolution.
        boolean doSlowFields = (pollCount % SLOW_FIELD_DIVISOR == 0);

        if (doSlowFields) {
            // Brake pedal pressed state (binary, not the depth %)
            if (gearboxDevice != null) {
                try {
                    int brakeState = (int) getBrakePedalStateMethod.invoke(gearboxDevice);
                    brakePedalPressed = brakeState == 1;
                    lastBrakePedalPressed = brakePedalPressed;
                } catch (Exception e) {
                    logger.warn("Failed to read brake pedal state: " + e.getMessage());
                }
            }

            // Turn signals
            if (lightDevice != null && getTurnLightFlashStateMethod != null) {
                try {
                    int flashState = (int) getTurnLightFlashStateMethod.invoke(lightDevice);
                    
                    boolean leftNow = (flashState == 2 || flashState == 3);
                    boolean rightNow = (flashState == 4 || flashState == 5);
                    boolean hazardNow = (flashState == 6 || flashState == 7);
                    
                    if (hazardNow) { leftNow = true; rightNow = true; }
                    
                    if (leftNow) leftTurnStickyCount = 10;
                    if (rightNow) rightTurnStickyCount = 10;
                    
                    leftTurn = leftTurnStickyCount > 0;
                    rightTurn = rightTurnStickyCount > 0;
                    
                    if (leftTurnStickyCount > 0) leftTurnStickyCount--;
                    if (rightTurnStickyCount > 0) rightTurnStickyCount--;
                    
                    lastLeftTurn = leftTurn;
                    lastRightTurn = rightTurn;
                } catch (Exception e) {
                    logger.warn("Failed to read turn signal: " + e.getMessage());
                }
            }

            // Seatbelt: comprehensive probe on first poll to find working API
            if (pollCount == 0) {
                probeSeatbeltApis(savedContext);
            }
            if (instrumentDeviceForBelt != null && getSafetyBeltStatusMethod != null) {
                try {
                    boolean[] belts = new boolean[2];
                    int driverRaw = (int) getSafetyBeltStatusMethod.invoke(instrumentDeviceForBelt, 1);
                    int passengerRaw = (int) getSafetyBeltStatusMethod.invoke(instrumentDeviceForBelt, 2);
                    belts[0] = (driverRaw != 0);
                    belts[1] = (passengerRaw != 0);
                    seatbelts = belts;
                    lastSeatbelts = seatbelts;
                } catch (Exception e) {
                    // Use defaults
                }
            }
        }

        latestSnapshot = new TelemetrySnapshot(
                speedKmh, accelPercent, brakePercent,
                brakePedalPressed, gearMode,
                leftTurn, rightTurn,
                seatbelts, System.currentTimeMillis()
        );
        
        pollCount++;
    }

    /**
     * Try to re-obtain the BYDAutoSpeedDevice reference and verify it returns fresh data.
     * This can happen if the BYD service restarts between trips.
     * Returns true if reconnect succeeded and fresh data was obtained.
     */
    private boolean tryReconnectSpeedDevice() {
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object newDevice = getInstance.invoke(null, savedContext);
            if (newDevice == null) return false;
            
            // Verify the new device actually returns data by doing a test read
            double testSpeed = (double) getCurrentSpeedMethod.invoke(newDevice);
            int testAccel = (int) getAccelerateDeepnessMethod.invoke(newDevice);
            int testBrake = (int) getBrakeDeepnessMethod.invoke(newDevice);
            
            // If we get here without exception, the device is alive
            speedDevice = newDevice;
            lastSpeedKmh = (int) testSpeed;
            lastAccelPercent = testAccel;
            lastBrakePercent = testBrake;
            logger.info("Re-obtained BYDAutoSpeedDevice — verified working (speed=" + lastSpeedKmh + ")");
            return true;
        } catch (Exception e) {
            logger.warn("Speed device reconnect failed: " + e.getMessage());
            return false;
        }
    }

    // Seatbelt alarm detection (discovered at runtime)
    private Object seatbeltAlarmDevice;
    private Method seatbeltAlarmMethod;
    private Context savedContext;
    
    /**
     * Probe multiple BYD devices for any seatbelt-related method.
     * Tries: BodyworkDevice alarm, InstrumentDevice malfunction indicators,
     * SafetyBeltDevice with various seat IDs.
     */
    private void probeSeatbeltApis(Context ctx) {
        logger.info("Probing BYD devices for seatbelt API...");
        
        // 1. Try BYDAutoBodyworkDevice — getAlarmState() or similar
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, ctx);
            
            // Probe all methods that might relate to seatbelt
            String[] methodNames = {
                "getAlarmState", "getSafetyBeltAlarm", "getSeatBeltWarning",
                "getSafetyBeltState", "getBeltAlarmState", "getAutoSystemState"
            };
            for (String name : methodNames) {
                try {
                    Method m = cls.getMethod(name);
                    int val = (int) m.invoke(device);
                    logger.info("Bodywork." + name + "() = " + val);
                    // If we find a working method, save it
                    if (val >= 0 && val < 100) {
                        seatbeltAlarmDevice = device;
                        seatbeltAlarmMethod = m;
                        logger.info("Using Bodywork." + name + "() for seatbelt alarm");
                    }
                } catch (NoSuchMethodException e) {
                    // Method doesn't exist, try next
                } catch (Exception e) {

                }
            }
        } catch (Exception e) {

        }
        
        // 2. Try BYDAutoInstrumentDevice — getMalfunctionState() or seatbelt-specific
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, ctx);
            
            String[] methodNames = {
                "getSafetyBeltStatus", "getSeatBeltAlarm", "getSafetyBeltAlarmState",
                "getMalfunctionState"
            };
            for (String name : methodNames) {
                // Try no-arg version
                try {
                    Method m = cls.getMethod(name);
                    int val = (int) m.invoke(device);
                    logger.info("Instrument." + name + "() = " + val);
                    if (seatbeltAlarmDevice == null && val >= 0 && val < 100) {
                        seatbeltAlarmDevice = device;
                        seatbeltAlarmMethod = m;
                        logger.info("Using Instrument." + name + "() for seatbelt alarm");
                    }
                } catch (NoSuchMethodException e) {
                    // Try int-arg version
                    try {
                        Method m = cls.getMethod(name, int.class);
                        StringBuilder sb = new StringBuilder("Instrument." + name + "(int):");
                        for (int i = 0; i <= 5; i++) {
                            try {
                                int val = (int) m.invoke(device, i);
                                sb.append(" [").append(i).append("]=").append(val);
                            } catch (Exception ex) {
                                sb.append(" [").append(i).append("]=ERR");
                            }
                        }
                        logger.info(sb.toString());
                    } catch (NoSuchMethodException e2) {
                        // Neither version exists
                    }
                } catch (Exception e) {

                }
            }
            
            // Also try MALFUNCTION_ELECTRIC_PARKING_BRAKE constant area
            // getMalfunctionState(int) with various malfunction IDs
            try {
                Method m = cls.getMethod("getMalfunctionState", int.class);
                StringBuilder sb = new StringBuilder("Instrument.getMalfunctionState(int):");
                // Try common malfunction IDs (0-20)
                for (int i = 0; i <= 20; i++) {
                    try {
                        int val = (int) m.invoke(device, i);
                        if (val != 0 && val != -2147482645) {
                            sb.append(" [").append(i).append("]=").append(val);
                        }
                    } catch (Exception ex) { /* skip */ }
                }
                logger.info(sb.toString());
            } catch (Exception e) { /* no getMalfunctionState(int) */ }
            
        } catch (Exception e) {

        }
        
        if (seatbeltAlarmDevice == null) {
            logger.warn("No working seatbelt API found — seatbelt status will show as buckled");
        }
    }

    /**
     * Context wrapper that bypasses BYD permission checks.
     * Required for accessing BYD hardware services without signature permissions from UID 2000.
     */
    private static class PermissionBypassContext extends android.content.ContextWrapper {
        public PermissionBypassContext(Context base) {
            super(base);
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {}

        @Override
        public void enforcePermission(String permission, int pid, int uid, String message) {}

        @Override
        public void enforceCallingPermission(String permission, String message) {}

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
    }
}
