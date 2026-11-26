package com.overdrive.app.surveillance;
import com.overdrive.app.logging.DaemonLogger;


/**
 * ModeTransitionManager - Handles smooth transitions between Normal and Sentry modes.
 * 
 * Ensures the GPU pipeline transitions smoothly without losing frames or
 * tearing down the EGL context. Manages FPS and bitrate changes during transitions.
 * 
 * Transitions:
 * - Normal → Sentry (ACC OFF): Reduce FPS 15→10, activate wake-on-motion
 * - Sentry → Normal (ACC ON): Restore FPS 10→15, disable stealth
 */
public class ModeTransitionManager {
    private static final String TAG = "ModeTransition";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // Modes
    public enum Mode {
        NORMAL,   // ACC ON - full quality recording
        SENTRY    // ACC OFF - reduced quality + wake-on-motion
    }
    
    // State
    private Mode currentMode = Mode.NORMAL;
    private boolean transitioning = false;
    
    // Components
    private HardwareEventRecorderGpu encoder;
    private SurveillanceEngineGpu sentry;
    private AdaptiveBitrateController bitrateController;
    
    // Configuration
    private static final int NORMAL_FPS = 15;
    private static final int NORMAL_BITRATE = 6_000_000;
    private static final int SENTRY_FPS = 10;
    private static final int SENTRY_BITRATE_IDLE = 2_000_000;
    
    /**
     * Creates a mode transition manager.
     * 
     * @param encoder Hardware encoder
     * @param sentry Surveillance engine
     * @param bitrateController Bitrate controller
     */
    public ModeTransitionManager(HardwareEventRecorderGpu encoder,
                                 SurveillanceEngineGpu sentry,
                                 AdaptiveBitrateController bitrateController) {
        this.encoder = encoder;
        this.sentry = sentry;
        this.bitrateController = bitrateController;
    }
    
    /**
     * Transitions to a new mode.
     * 
     * @param newMode Target mode
     */
    public void transitionTo(Mode newMode) {
        if (newMode == currentMode) {
            logger.debug( "Already in " + newMode + " mode");
            return;
        }
        
        if (transitioning) {
            logger.warn( "Transition already in progress");
            return;
        }
        
        transitioning = true;
        long startTime = System.currentTimeMillis();
        
        logger.info( String.format("Transitioning: %s → %s", currentMode, newMode));
        
        try {
            if (newMode == Mode.SENTRY) {
                transitionToSentry();
            } else {
                transitionToNormal();
            }
            
            currentMode = newMode;
            
            long elapsed = System.currentTimeMillis() - startTime;
            logger.info( String.format("Transition complete: %s (took %dms)", newMode, elapsed));
            
        } catch (Exception e) {
            logger.error( "Transition failed", e);
        } finally {
            transitioning = false;
        }
    }
    
    /**
     * Transitions from Normal to Sentry mode (ACC OFF).
     * 
     * Steps:
     * 1. Complete current recording segment
     * 2. Reduce FPS from 15 to 10
     * 3. Reduce bitrate to 2 Mbps (idle)
     * 4. Activate wake-on-motion (2 FPS AI checks)
     * 5. Enable stealth mode (handled by caller)
     */
    private void transitionToSentry() {
        logger.info( "Transitioning to SENTRY mode...");
        
        // 1. Complete current segment if recording
        if (encoder != null && encoder.isRecording()) {
            logger.debug( "Completing current segment...");
            encoder.stopRecording();
        }
        
        // 2. Reduce bitrate to idle level
        if (bitrateController != null) {
            bitrateController.setImmediateBitrate(SENTRY_BITRATE_IDLE);
            logger.debug( "Bitrate reduced to " + (SENTRY_BITRATE_IDLE / 1_000_000) + " Mbps");
        }
        
        // 3. Note: FPS is set at encoder initialization
        // Dynamic FPS change would require encoder restart
        logger.debug( "FPS: " + SENTRY_FPS + " (set at encoder init)");
        
        // 4. Activate wake-on-motion
        if (sentry != null) {
            sentry.enable();
            logger.debug( "Wake-on-motion activated (2 FPS AI checks)");
        }
        
        // 5. Stealth mode is handled by BydSystemLock (caller's responsibility)
        
        logger.info( "SENTRY mode active");
    }
    
    /**
     * Transitions from Sentry to Normal mode (ACC ON).
     * 
     * Steps:
     * 1. Stop any active sentry recording
     * 2. Disable stealth mode (handled by caller)
     * 3. Restore FPS to 15
     * 4. Restore bitrate to 6 Mbps
     * 5. Resume normal recording
     */
    private void transitionToNormal() {
        logger.info( "Transitioning to NORMAL mode...");
        
        // 1. Stop sentry recording if active
        if (sentry != null && sentry.isRecording()) {
            logger.debug( "Stopping sentry recording...");
            sentry.disable();
        }
        
        // 2. Stealth mode is handled by BydSystemLock (caller's responsibility)
        
        // 3. Note: FPS is set at encoder initialization
        logger.debug( "FPS: " + NORMAL_FPS + " (set at encoder init)");
        
        // 4. Restore bitrate
        if (bitrateController != null) {
            bitrateController.setImmediateBitrate(NORMAL_BITRATE);
            logger.debug( "Bitrate restored to " + (NORMAL_BITRATE / 1_000_000) + " Mbps");
        }
        
        // 5. Resume normal recording (caller's responsibility)
        
        logger.info( "NORMAL mode active");
    }
    
    /**
     * Handles ACC state change.
     * 
     * @param accIsOff true if ACC is OFF, false if ON
     */
    public void onAccStateChanged(boolean accIsOff) {
        logger.info( "ACC state changed: " + (accIsOff ? "OFF" : "ON"));
        
        if (accIsOff) {
            // ACC OFF → Sentry mode
            transitionTo(Mode.SENTRY);
        } else {
            // ACC ON → Normal mode
            transitionTo(Mode.NORMAL);
        }
    }
    
    /**
     * Gets the current mode.
     * 
     * @return Current mode
     */
    public Mode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * Checks if transitioning.
     * 
     * @return true if transition in progress
     */
    public boolean isTransitioning() {
        return transitioning;
    }
}
