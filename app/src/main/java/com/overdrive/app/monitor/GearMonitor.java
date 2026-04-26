package com.overdrive.app.monitor;

import android.content.Context;

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
    
    private static GearMonitor instance;
    
    private Context context;
    private Object gearboxDevice;
    private Method getGearMethod;
    private Thread pollThread;
    private volatile boolean isRunning = false;
    private volatile int currentGear = GEAR_P;
    private long lastUpdateTime = 0;
    
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
     */
    public void start() {
        if (isRunning) {
            logger.warn("Already running");
            return;
        }
        
        try {
            logger.info("Starting gear monitor...");
            
            // Get gearbox device instance via reflection
            Class<?> gearboxClass = Class.forName("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
            Method getInstance = gearboxClass.getMethod("getInstance", Context.class);
            gearboxDevice = getInstance.invoke(null, context);
            
            if (gearboxDevice == null) {
                logger.error("BYDAutoGearboxDevice.getInstance() returned null");
                return;
            }
            
            // Cache the getter method
            getGearMethod = gearboxClass.getMethod("getGearboxAutoModeType");
            
            // Get initial gear state
            currentGear = (int) getGearMethod.invoke(gearboxDevice);
            lastUpdateTime = System.currentTimeMillis();
            logger.info("Initial gear: " + gearToString(currentGear));
            
            isRunning = true;
            
            // Start polling thread
            pollThread = new Thread(() -> {
                while (isRunning) {
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                        if (!isRunning) break;
                        
                        int gear;
                        // Prefer TelemetryDataCollector's cached snapshot to avoid
                        // duplicate CAN bus reads when the overlay poller is running
                        com.overdrive.app.telemetry.TelemetryDataCollector src = telemetrySource;
                        com.overdrive.app.telemetry.TelemetrySnapshot snap = 
                            (src != null) ? src.getLatestSnapshot() : null;
                        if (snap != null && (System.currentTimeMillis() - snap.timestampMs) < 1000) {
                            // Snapshot is fresh (< 1 second old) — use its gear value
                            gear = snap.gearMode;
                        } else {
                            // No fresh snapshot — poll device directly
                            gear = (int) getGearMethod.invoke(gearboxDevice);
                        }
                        
                        if (gear != currentGear) {
                            logger.info("Gear changed: " + gearToString(currentGear) + " -> " + gearToString(gear));
                            currentGear = gear;
                            lastUpdateTime = System.currentTimeMillis();
                            CameraDaemon.onGearChanged(gear);
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        // Don't crash the poll thread — just log and retry
                        logger.debug("Gear poll error: " + e.getMessage());
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                    }
                }
            }, "GearPoll");
            pollThread.setDaemon(true);
            pollThread.start();
            
            logger.info("Gear monitor started successfully");
            
            // Notify initial state
            CameraDaemon.onGearChanged(currentGear);
            
        } catch (Exception e) {
            logger.error("Failed to start gear monitor: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Stop monitoring.
     */
    public void stop() {
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
}
