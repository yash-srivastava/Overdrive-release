package com.overdrive.app.recording;

import android.content.Context;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.proximity.ProximityGuardController;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;

import org.json.JSONObject;

/**
 * Recording Mode Manager
 * 
 * Coordinates all recording modes with mutual exclusivity.
 * 
 * Modes:
 * - NONE: No recording, pipeline stopped (DEFAULT)
 * - CONTINUOUS: Always recording when ACC ON
 * - DRIVE_MODE: Recording when in driving gears (D/R/S/M), stops in P/N
 * - PROXIMITY_GUARD: Radar-triggered recording in non-P gears (D/R/S/M/N), disabled in P
 * 
 * Features:
 * - Mutual exclusivity enforcement
 * - Proper cleanup when switching modes
 * - Resource management (stops pipeline when NONE)
 * - Gear state awareness for DRIVE_MODE and PROXIMITY_GUARD
 * - ACC state awareness for CONTINUOUS mode
 */
public class RecordingModeManager {
    private static final DaemonLogger logger = DaemonLogger.getInstance("RecordingModeManager");
    
    // Gear constants (from BYDAutoGearboxDevice)
    public static final int GEAR_P = 1;
    public static final int GEAR_R = 2;
    public static final int GEAR_N = 3;
    public static final int GEAR_D = 4;
    public static final int GEAR_M = 5;
    public static final int GEAR_S = 6;
    
    /**
     * Recording modes for ACC ON state.
     */
    public enum Mode {
        NONE,            // No recording - saves resources (DEFAULT)
        CONTINUOUS,      // Always recording when ACC ON
        DRIVE_MODE,      // Recording when driving (not in P gear)
        PROXIMITY_GUARD  // Recording on radar triggers when gear != P
    }
    
    private final Context context;
    private final GpuSurveillancePipeline pipeline;
    private final ProximityGuardController proximityController;
    
    private volatile Mode currentMode = Mode.NONE;  // Default: no recording
    private volatile boolean accIsOn = false;  // Default: ACC OFF — wait for AccSentryDaemon to confirm
    private volatile int currentGear = GEAR_P;  // Default: Park
    
    public RecordingModeManager(Context context, GpuSurveillancePipeline pipeline) {
        this.context = context;
        this.pipeline = pipeline;
        this.proximityController = new ProximityGuardController(context, pipeline);
        
        // Load persisted mode from config
        loadPersistedMode();
        
        logger.info("RecordingModeManager initialized: mode=" + currentMode);
        
        // Sync ACC state from AccMonitor if it's already been set by AccSentryDaemon
        boolean monitorAccState = queryAccStateFromHardware();
        if (monitorAccState) {
            accIsOn = true;
            logger.info("ACC state from hardware: ON");
        }
        
        // Activate the loaded mode if conditions are met
        // CONTINUOUS: activate immediately (accIsOn defaults to true)
        // DRIVE_MODE: activate if in driving gear
        // PROXIMITY_GUARD: activate if gear != P
        // NONE: no action needed
        if (currentMode == Mode.CONTINUOUS && accIsOn) {
            logger.info("Auto-activating CONTINUOUS mode on startup");
            activateMode(currentMode);
        } else if (currentMode == Mode.DRIVE_MODE && isDrivingGear(currentGear)) {
            logger.info("Auto-activating DRIVE_MODE on startup (gear=" + gearToString(currentGear) + ")");
            activateMode(currentMode);
        } else if (currentMode == Mode.PROXIMITY_GUARD && currentGear != GEAR_P) {
            logger.info("Auto-activating PROXIMITY_GUARD on startup (gear=" + gearToString(currentGear) + ")");
            activateMode(currentMode);
        }
    }
    
    /**
     * Set recording mode.
     * Enforces mutual exclusivity by deactivating current mode before activating new.
     */
    public synchronized void setMode(Mode mode) {
        if (mode == currentMode) {
            logger.debug("Mode already set to: " + mode);
            return;
        }
        
        logger.info("Changing recording mode: " + currentMode + " -> " + mode);
        
        // Sync ACC state — query hardware directly for authoritative state
        boolean actualAccState = queryAccStateFromHardware();
        if (actualAccState != accIsOn) {
            logger.info("Syncing ACC state: " + accIsOn + " -> " + actualAccState);
            accIsOn = actualAccState;
        }
        
        // Sync gear state from GearMonitor (authoritative source)
        try {
            com.overdrive.app.monitor.GearMonitor gearMonitor = com.overdrive.app.monitor.GearMonitor.getInstance();
            if (gearMonitor.isRunning()) {
                int actualGear = gearMonitor.getCurrentGear();
                if (actualGear != currentGear) {
                    logger.info("Syncing gear from GearMonitor: " + gearToString(currentGear) + " -> " + gearToString(actualGear));
                    currentGear = actualGear;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not sync gear: " + e.getMessage());
        }
        
        // Deactivate current mode
        deactivateMode(currentMode);
        
        // Update current mode
        Mode oldMode = currentMode;
        currentMode = mode;
        
        // Persist mode to config EARLY — before activation which might fail
        persistMode(mode);
        
        // Activate new mode based on appropriate trigger
        if (mode == Mode.DRIVE_MODE) {
            // DRIVE_MODE activates when in driving gears (D/R/S/M)
            if (isDrivingGear(currentGear)) {
                activateMode(mode);
            } else {
                logger.info("Gear is " + gearToString(currentGear) + " - DRIVE_MODE will activate when in D/R/S/M");
            }
        } else if (mode == Mode.PROXIMITY_GUARD) {
            // PROXIMITY_GUARD activates in all gears except P
            if (currentGear != GEAR_P) {
                activateMode(mode);
            } else {
                logger.info("Gear is P - PROXIMITY_GUARD will activate when gear changes");
            }
        } else if (accIsOn) {
            // CONTINUOUS and NONE activate when ACC is ON
            activateMode(mode);
        } else {
            logger.info("ACC is OFF - mode will activate when ACC turns ON");
        }
        
        logger.info("Recording mode changed: " + oldMode + " -> " + mode);
    }
    
    /**
     * Get current recording mode.
     */
    public Mode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * Notify of ACC state change.
     * Activates/deactivates modes that depend on ACC state.
     */
    public synchronized void onAccStateChanged(boolean isOn) {
        logger.info("ACC state changed: " + (isOn ? "ON" : "OFF") + " (mode=" + currentMode + ", wasOn=" + accIsOn + ")");
        
        boolean wasOn = accIsOn;
        accIsOn = isOn;
        
        if (isOn) {
            // ACC is ON — stop pipeline completely. The delayed thread will restart it.
            // Don't call onAccOn() — it tries to reopen the camera which is pointless
            // since stop() will tear everything down immediately after.
            if (pipeline.isRunning()) {
                pipeline.stop();
            }
            
            // Delay mode activation to let BYD native app finish camera init
            final Mode modeToActivate = currentMode;
            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {}
                
                synchronized (RecordingModeManager.this) {
                    if (!accIsOn) {
                        logger.info("ACC turned OFF during reacquire delay — skipping mode activation");
                        return;
                    }
                    
                    // Use CURRENT gear, not the gear at ACC ON time — gear may have changed
                    // during the 5-second delay (e.g., P→D or D→P)
                    int gearNow = currentGear;
                    
                    if (modeToActivate == Mode.DRIVE_MODE && !isDrivingGear(gearNow)) {
                        logger.info("DRIVE_MODE waiting for driving gear (current=" + gearToString(gearNow) + ")");
                    } else if (modeToActivate == Mode.PROXIMITY_GUARD && gearNow == GEAR_P) {
                        logger.info("PROXIMITY_GUARD waiting for gear != P");
                    } else if (modeToActivate != Mode.NONE) {
                        // Check if gear change already activated the mode during the delay
                        if (pipeline.isRunning() && pipeline.isRecording()) {
                            logger.info("Mode already activated by gear change during delay — skipping");
                        } else {
                            activateMode(modeToActivate);
                        }
                    }
                }
            }, "AccOnReacquire").start();
            
        } else if (!isOn && wasOn) {
            // ACC turned OFF - deactivate current mode
            deactivateMode(currentMode);
        }
    }
    
    /**
     * Notify of gear state change.
     * - DRIVE_MODE: activates on D/R/S/M, deactivates on P/N
     * - PROXIMITY_GUARD: activates on D/R/S/M/N, deactivates on P
     * 
     * @param gear The new gear position (GEAR_P, GEAR_R, GEAR_N, GEAR_D, etc.)
     */
    public synchronized void onGearChanged(int gear) {
        String gearName = gearToString(gear);
        logger.info("Gear changed: " + gearToString(currentGear) + " -> " + gearName + " (mode=" + currentMode + ")");
        
        int previousGear = currentGear;
        currentGear = gear;
        
        // Only DRIVE_MODE and PROXIMITY_GUARD respond to gear changes
        if (currentMode != Mode.DRIVE_MODE && currentMode != Mode.PROXIMITY_GUARD) {
            logger.debug("Mode " + currentMode + " does not respond to gear changes");
            return;
        }
        
        if (currentMode == Mode.DRIVE_MODE) {
            // DRIVE_MODE: record when driving (D/R/S/M) AND ACC is ON
            boolean wasDriving = isDrivingGear(previousGear);
            boolean nowDriving = isDrivingGear(gear);
            
            if (nowDriving && !wasDriving && accIsOn) {
                logger.info("Shifted to driving gear - activating DRIVE_MODE recording");
                activateMode(Mode.DRIVE_MODE);
            } else if (!nowDriving && wasDriving) {
                logger.info("Shifted to parked gear - deactivating DRIVE_MODE recording");
                deactivateMode(Mode.DRIVE_MODE);
            } else if (nowDriving && !accIsOn) {
                logger.info("Driving gear but ACC OFF - DRIVE_MODE will activate when ACC turns ON");
            }
        } else if (currentMode == Mode.PROXIMITY_GUARD) {
            // PROXIMITY_GUARD: active in all gears except P, only when ACC is ON
            boolean wasInP = (previousGear == GEAR_P);
            boolean nowInP = (gear == GEAR_P);
            
            if (!nowInP && wasInP && accIsOn) {
                logger.info("Shifted out of P - activating PROXIMITY_GUARD");
                activateMode(Mode.PROXIMITY_GUARD);
            } else if (nowInP && !wasInP) {
                logger.info("Shifted to P - deactivating PROXIMITY_GUARD");
                deactivateMode(Mode.PROXIMITY_GUARD);
            } else if (!nowInP && !accIsOn) {
                logger.info("Not in P but ACC OFF - PROXIMITY_GUARD will activate when ACC turns ON");
            }
        }
    }
    
    /**
     * Check if ACC is ON.
     */
    public boolean isAccOn() {
        return accIsOn;
    }
    
    /**
     * Get current gear position.
     */
    public int getCurrentGear() {
        return currentGear;
    }
    
    /**
     * Check if gear is a driving gear (D/R/S/M).
     */
    public static boolean isDrivingGear(int gear) {
        return gear == GEAR_D || gear == GEAR_R || gear == GEAR_S || gear == GEAR_M;
    }
    
    /**
     * Check if gear is a parked/stationary gear (P/N).
     */
    public static boolean isParkedGear(int gear) {
        return gear == GEAR_P || gear == GEAR_N;
    }
    
    /**
     * Convert gear constant to string.
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
    
    // ==================== MODE ACTIVATION ====================
    
    private void activateMode(Mode mode) {
        logger.info("Activating mode: " + mode);
        
        // SOTA: Stop any manual recording before activating a mode
        // This ensures mode-managed recording takes precedence over manual recording
        if (pipeline.isNormalRecordingMode()) {
            logger.info("Stopping manual recording before activating mode: " + mode);
            pipeline.stopRecording();
        }
        
        switch (mode) {
            case NONE:
                // Stop pipeline to save resources
                if (pipeline.isRunning()) {
                    logger.info("Stopping pipeline for NONE mode (resource saving)");
                    pipeline.stop();
                }
                break;
                
            case CONTINUOUS:
                // Start pipeline and recording
                try {
                    if (!pipeline.isRunning()) {
                        logger.info("Starting pipeline for CONTINUOUS mode");
                        pipeline.start(false);
                    }
                    // Pipeline.start() blocks ~2s for GL init. Recorder should be ready.
                    if (pipeline.isRunning() && !pipeline.isRecording()) {
                        pipeline.startRecording();
                    }
                } catch (Exception e) {
                    logger.error("Failed to start CONTINUOUS mode: " + e.getMessage());
                }
                break;
                
            case DRIVE_MODE:
                // Start recording when driving (gear is D/R/S/M)
                try {
                    if (!pipeline.isRunning()) {
                        logger.info("Starting pipeline for DRIVE_MODE");
                        pipeline.start(false);
                    }
                    // Pipeline.start() blocks ~2s for GL init. Recorder should be ready.
                    if (pipeline.isRunning() && !pipeline.isRecording()) {
                        logger.info("Starting DRIVE_MODE recording");
                        pipeline.startRecording();
                    }
                } catch (Exception e) {
                    logger.error("Failed to start DRIVE_MODE: " + e.getMessage());
                }
                break;
                
            case PROXIMITY_GUARD:
                // Start pipeline (without recording) and proximity controller
                try {
                    if (!pipeline.isRunning()) {
                        logger.info("Starting pipeline for PROXIMITY_GUARD mode");
                        pipeline.start(false);  // Don't auto-start recording
                    }
                    proximityController.start();
                } catch (Exception e) {
                    logger.error("Failed to start PROXIMITY_GUARD mode: " + e.getMessage());
                }
                break;
        }
    }
    
    private void deactivateMode(Mode mode) {
        logger.info("Deactivating mode: " + mode);
        
        // Check if surveillance should be preserved — don't stop pipeline during ACC OFF
        // (surveillance/sentry mode needs the pipeline running)
        boolean keepPipelineRunning = !accIsOn;
        
        if (keepPipelineRunning) {
            logger.info("ACC is OFF — keeping pipeline running for surveillance");
        }
        
        switch (mode) {
            case NONE:
                // Already stopped
                break;
                
            case CONTINUOUS:
                // Stop recording but keep pipeline if ACC is OFF (surveillance running)
                pipeline.stopRecording();
                if (pipeline.isRunning() && !keepPipelineRunning) {
                    pipeline.stop();
                }
                break;
                
            case DRIVE_MODE:
                // Stop recording only — keep pipeline alive for quick resume on next gear change.
                // Full pipeline teardown (camera/EGL/encoder release) makes restart unreliable
                // and slow. Only stop the pipeline on full ACC OFF (handled by onAccStateChanged).
                pipeline.stopRecording();
                break;
                
            case PROXIMITY_GUARD:
                // Stop proximity controller but keep pipeline if ACC is OFF (surveillance running)
                proximityController.stop();
                if (pipeline.isRunning() && !keepPipelineRunning) {
                    pipeline.stop();
                }
                break;
        }
    }
    
    // ==================== CONFIG PERSISTENCE ====================
    
    /**
     * Query ACC state directly from BYD hardware.
     * Falls back to AccMonitor if hardware query fails.
     */
    private boolean queryAccStateFromHardware() {
        // Try direct hardware query via BYDAutoBodyworkDevice
        try {
            Class<?> deviceClass = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            java.lang.reflect.Method getInstance = deviceClass.getMethod("getInstance", android.content.Context.class);
            Object device = getInstance.invoke(null, context);
            if (device != null) {
                java.lang.reflect.Method getPowerLevel = deviceClass.getMethod("getPowerLevel");
                int level = (Integer) getPowerLevel.invoke(device);
                // Power levels: 0=OFF, 1=ACC, 2=ON, 3=START
                boolean isOn = level >= 2;
                logger.debug("Hardware power level: " + level + " (ACC " + (isOn ? "ON" : "OFF") + ")");
                return isOn;
            }
        } catch (Exception e) {
            logger.debug("Hardware ACC query failed: " + e.getMessage());
        }
        
        // Fallback to AccMonitor
        return com.overdrive.app.monitor.AccMonitor.isAccOn();
    }

    private void loadPersistedMode() {
        try {
            JSONObject recording = UnifiedConfigManager.getRecording();
            String modeStr = recording.optString("mode", "NONE");
            
            try {
                currentMode = Mode.valueOf(modeStr.toUpperCase());
                logger.info("Loaded persisted mode: " + currentMode);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid persisted mode: " + modeStr + ", using NONE");
                currentMode = Mode.NONE;
            }
        } catch (Exception e) {
            logger.error("Failed to load persisted mode: " + e.getMessage());
            currentMode = Mode.NONE;
        }
    }
    
    private void persistMode(Mode mode) {
        try {
            JSONObject recording = UnifiedConfigManager.getRecording();
            recording.put("mode", mode.name());
            UnifiedConfigManager.setRecording(recording);
            logger.debug("Persisted mode: " + mode);
        } catch (Exception e) {
            logger.error("Failed to persist mode: " + e.getMessage());
        }
    }
    
    /**
     * Reload configuration (call when config changes).
     */
    public synchronized void reloadConfig() {
        loadPersistedMode();
        if (proximityController != null) {
            proximityController.reloadConfig();
        }
        logger.info("Config reloaded: mode=" + currentMode);
    }
    
    /**
     * Shutdown and cleanup resources.
     */
    public void shutdown() {
        logger.info("Shutting down RecordingModeManager...");
        deactivateMode(currentMode);
        if (proximityController != null) {
            proximityController.shutdown();
        }
        logger.info("RecordingModeManager shutdown complete");
    }
}
