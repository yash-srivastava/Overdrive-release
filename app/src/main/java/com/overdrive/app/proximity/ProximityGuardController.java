package com.overdrive.app.proximity;

import android.content.Context;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Proximity Guard Controller
 * 
 * Core state machine for Proximity Guard recording mode.
 * 
 * State transitions:
 * - IDLE: Mode disabled or ACC OFF
 * - MONITORING: Radar listeners active, waiting for trigger
 * - RECORDING: Active recording in progress
 * - POST_RECORD: Countdown timer after radar goes safe
 * 
 * Features:
 * - Smart continuation: If radar triggers during POST_RECORD, continues same recording
 * - Configurable pre/post buffers
 * - Automatic cleanup and resource management
 */
public class ProximityGuardController implements ProximityRadarMonitor.TriggerCallback,
        ProximityBlindSpotMonitor.Callback {
    private static final DaemonLogger logger = DaemonLogger.getInstance("ProximityGuardController");
    
    /**
     * Controller states.
     */
    public enum State {
        IDLE,          // Not active
        MONITORING,    // Listening for radar triggers
        RECORDING,     // Active recording
        POST_RECORD    // Countdown after radar safe
    }
    
    private final Context context;
    private final GpuSurveillancePipeline pipeline;
    private final ProximityRadarMonitor radarMonitor;
    private final ProximityRecordingHandler recordingHandler;
    private final ProximityBlindSpotMonitor blindSpotMonitor;
    private ProximityGuardConfig config;
    
    private volatile State currentState = State.IDLE;
    private ScheduledFuture<?> postRecordTimer;
    // Keyframe-pulse timer, active ONLY while MONITORING under the low-power
    // profile. The low monitor frame-rate stretches the encoder's natural GOP
    // (KEY_I_FRAME_INTERVAL=2s is converted to a FRAME COUNT = 2s×cameraFps, so
    // at ~4 fps an IDR would otherwise land only every ~8s wall-clock). The
    // pre-record ring's flush needs a keyframe inside the window, so without
    // this pulse a trigger after a quiet stretch would flush an empty/ragged
    // pre-roll — defeating the feature. We pulse requestSyncFrame on a
    // wall-clock cadence (~preRecordSeconds/2) so a keyframe is always within
    // the pre-record window. Cancelled the moment we leave MONITORING.
    private ScheduledFuture<?> keyframePulseTimer;
    // Second blind-spot poll refresh after arming; guarded by this.
    private ScheduledFuture<?> blindSpotPollRetry;
    // Set by shutdown(); a late start() must not re-arm listeners on a dying scheduler.
    private boolean closed = false;
    private final ScheduledExecutorService scheduler;
    // Tracks whether a RED tier was hit during the CURRENT recording session.
    // Sticky for the duration of the recording so the post-record window
    // honours the highest tier seen, not just the latest. Reset when we
    // return to MONITORING.
    private volatile boolean redEscalatedThisSession = false;
    
    public ProximityGuardController(Context context, GpuSurveillancePipeline pipeline) {
        this.context = context;
        this.pipeline = pipeline;
        
        // Load config
        this.config = loadConfig();
        
        // Create components
        this.radarMonitor = new ProximityRadarMonitor(context, config.getTriggerLevel());
        this.radarMonitor.setCallback(this);
        
        this.recordingHandler = new ProximityRecordingHandler(pipeline);
        
        // Create scheduler for post-record timer
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ProximityPostRecordTimer");
            t.setDaemon(true);
            return t;
        });
        this.blindSpotMonitor = new ProximityBlindSpotMonitor(scheduler, this);
        
        logger.info("ProximityGuardController initialized: " + config.toString());
    }
    
    /**
     * Start Proximity Guard mode.
     * Transitions from IDLE to MONITORING.
     * 
     * Note: The "enabled" state is controlled by RecordingModeManager's mode selection.
     * When mode is PROXIMITY_GUARD, this controller should start regardless of config.enabled.
     */
    public synchronized void start() {
        if (closed) {
            logger.warn("Cannot start - controller is shut down");
            return;
        }
        if (currentState != State.IDLE) {
            logger.warn("Cannot start - already in state: " + currentState);
            return;
        }

        // Reload config in case it changed (for trigger level, pre/post record settings)
        config = loadConfig();

        // SOTA: Don't check config.isEnabled() here - the mode selection in RecordingModeManager
        // is the source of truth for whether proximity guard should be active.
        // The config.enabled flag is deprecated/redundant.

        // Push the proximity tab's pre-record duration into the encoder.
        // Without this, the encoder's pre-record window stays at whatever
        // SurveillanceConfig set it to (default 5s), and the proximity
        // tab's preRecordSeconds slider is silently ignored — the user
        // sets 10s but gets 5s of pre-roll. The encoder owns ONE
        // shared pre-record buffer across recording paths, so this
        // setting wins for as long as proximity is the active mode.
        try {
            com.overdrive.app.surveillance.HardwareEventRecorderGpu enc = pipeline.getEncoder();
            if (enc != null) {
                enc.setPreRecordDuration(config.getPreRecordSeconds());
                logger.info("Pre-record duration set from proximity config: "
                    + config.getPreRecordSeconds() + "s");
            }
        } catch (Exception e) {
            logger.warn("Failed to apply proximity pre-record duration: " + e.getMessage());
        }

        logger.info("Starting Proximity Guard mode...");
        transitionTo(State.MONITORING);
        radarMonitor.setTriggerLevel(config.getTriggerLevel());
        radarMonitor.startListening();
        applyBlindSpotTrigger();
    }
    
    /**
     * Stop Proximity Guard mode.
     * Transitions to IDLE.
     */
    public synchronized void stop() {
        logger.info("Stopping Proximity Guard mode (current state: " + currentState + ")");
        
        // Stop radar listener
        radarMonitor.stopListening();
        if (blindSpotMonitor.isArmed()) {
            blindSpotMonitor.disarm();
            refreshBlindSpotPoll(false);
        }
        
        // Stop recording if active
        if (currentState == State.RECORDING || currentState == State.POST_RECORD) {
            cancelPostRecordTimer();
            recordingHandler.stopRecording();
            com.overdrive.app.recording.OemDashcamMirror.onPanoRecordingStopped();
        }

        transitionTo(State.IDLE);
    }
    
    /**
     * Get current state.
     */
    public State getCurrentState() {
        return currentState;
    }
    
    /**
     * Check if active (not IDLE).
     */
    public boolean isActive() {
        return currentState != State.IDLE;
    }

    /**
     * The GLOBAL camera HAL fps this controller wants RIGHT NOW, or 0 meaning
     * "no opinion — use the full recording rate". Consumed by the single
     * camera-profile authority (RecordingModeManager.desiredCameraState) so the
     * camera HAL — not just the recorder draw-stride — is dropped to the
     * low-power rate while we sit MONITORING for radar triggers.
     *
     * <p>Why this matters (measured on-device): proximity detection is RADAR-
     * driven, not camera-driven, so while MONITORING the camera only needs to
     * keep the pre-record ring lightly warm. Previously we lowered only the
     * recorder draw-stride and left the camera HAL at 15 fps — which kept the
     * OEM camera stack (mm-qcamera-daemon) pinned at ~50% of a core capturing
     * frames almost all of which were stride-skipped. Dropping the HAL capture
     * rate to monitorFps cuts that capture cost roughly proportionally.
     *
     * <ul>
     *   <li>MONITORING (low-power enabled) → monitorFps (~4) — the lean idle rate.</li>
     *   <li>RECORDING / POST_RECORD → 0 (full rate; an event clip is live, the
     *       camera must snap back to the configured recording fps).</li>
     *   <li>IDLE, or low-power monitor disabled → 0 (no opinion).</li>
     * </ul>
     */
    public int desiredCameraFps() {
        if (currentState == State.MONITORING && config.isLowPowerMonitor()) {
            return Math.max(1, config.getMonitorFps());
        }
        return 0;
    }

    /**
     * Re-derive the MONITORING draw stride against the CURRENT camera fps, but
     * ONLY while we are in MONITORING. Called by RecordingModeManager after it
     * changes the shared camera HAL fps for a non-proximity reason (e.g. a
     * live-view stream opening/closing raises/lowers the shared camera rate).
     *
     * <p>Why this is needed: applyMonitorProfile() computes stride =
     * round(cameraFps / monitorFps) and is otherwise only invoked on the
     * MONITORING state-entry. If the camera fps changes WHILE we are already
     * monitoring, the stride captured at entry no longer maps to the configured
     * monitor fps — the pre-record ring would over- or (worse) under-feed,
     * silently degrading pre-roll. Re-running applyMonitorProfile() here
     * recomputes the stride against the new camera fps so the effective monitor
     * rate stays at the configured value regardless of camera-fps changes.
     *
     * <p>No-op outside MONITORING (RECORDING/POST_RECORD own the full-rate event
     * profile; IDLE has no pipeline). Cheap: a couple of volatile pipeline writes.
     *
     * <p>MUST be {@code synchronized(this)}: this is the ONLY caller of
     * applyMonitorProfile() that runs OFF the controller's own state-machine
     * thread (it is invoked from RecordingModeManager's reconcile, on the
     * live-view stream HTTP/WS thread). Every other profile mutator
     * (applyMonitorProfile / applyEventProfile / restoreFullProfile /
     * start/cancelKeyframePulse) is reached only via transitionTo() under
     * synchronized(this). Without the monitor here, a stream open/close racing a
     * radar trigger could (a) interleave applyMonitorProfile() AFTER an
     * applyEventProfile() — stranding a live triggered clip at low monitor
     * fps+bitrate until the recording ends — and (b) double-enter
     * startKeyframePulse()'s null-guard and LEAK a ScheduledFuture (two pulses,
     * one un-cancellable). Holding the monitor across the state-check + apply
     * makes the "MONITORING ⇒ apply" decision atomic w.r.t. transitionTo, so the
     * re-derive can never land on top of a state that has since left MONITORING.
     * Lock order is safe: applyMonitorProfile only calls pipeline fps/stride/
     * bitrate setters, none of which fire RMM's stream/BS listeners (no reverse
     * controller-monitor → reconcileLock edge).
     */
    public synchronized void reapplyMonitorProfileIfMonitoring() {
        if (currentState == State.MONITORING) {
            applyMonitorProfile();
        }
    }
    
    // ==================== TRIGGER CALLBACKS ====================
    
    @Override
    public synchronized void onProximityTrigger(int area, int state, String level) {
        // A callback that waited on this lock across stop()/start() belongs to the old session.
        if (!radarMonitor.isTriggered()) {
            logger.debug("Ignoring stale proximity trigger: area=" + area);
            return;
        }
        logger.info("onProximityTrigger: state=" + currentState + " area=" + area + " level=" + level);

        // Sticky escalation flag — once RED is seen during a recording, it
        // stays RED for the remainder of that session so the post-record
        // window uses the elevated value even if the radar drops back to
        // YELLOW before going safe.
        if ("RED".equals(level)) {
            redEscalatedThisSession = true;
        }

        switch (currentState) {
            case MONITORING:
                // Start new recording. (RED-tracking handled at method top.)
                transitionTo(State.RECORDING);
                recordingHandler.startRecording(level);
                com.overdrive.app.recording.OemDashcamMirror.onPanoRecordingStarted();
                break;

            case POST_RECORD:
                // Smart continuation - cancel timer and extend recording
                logger.info("Extending recording: radar triggered during post-record countdown");
                cancelPostRecordTimer();
                transitionTo(State.RECORDING);
                // Recording continues - same file, just reset the post-record timer when safe
                recordingHandler.extendRecording(level);
                break;

            case RECORDING:
                // Already recording - extend by resetting any pending timers
                logger.debug("Already recording, extending duration");
                recordingHandler.extendRecording(level);
                break;

            case IDLE:
                logger.warn("Received trigger while IDLE - should not happen");
                break;
        }
    }
    
    @Override
    public synchronized void onProximitySafe() {
        if (radarMonitor.isTriggered()) {
            logger.debug("Ignoring stale proximity safe");
            return;
        }
        logger.info("onProximitySafe: state=" + currentState);
        if (blindSpotMonitor.isTriggered()) {
            logger.info("Radar clear but the blind spot is still occupied - recording continues");
            return;
        }
        
        if (currentState == State.RECORDING) {
            // Start post-record countdown
            transitionTo(State.POST_RECORD);
            startPostRecordTimer();
        }
    }
    
    // ==================== BLIND-SPOT TRIGGER ====================
    // Optional second source. The session ends only when the radar and the blind spot are both clear.

    @Override
    public synchronized void onBlindSpotTrigger(int sides) {
        if (!blindSpotMonitor.isTriggered()) {
            logger.debug("Ignoring stale blind-spot trigger");
            return;
        }
        logger.info("onBlindSpotTrigger: state=" + currentState
                + " side=" + ProximityBlindSpotMonitor.describe(sides));

        switch (currentState) {
            case MONITORING:
                transitionTo(State.RECORDING);
                recordingHandler.startBlindSpotRecording();
                com.overdrive.app.recording.OemDashcamMirror.onPanoRecordingStarted();
                break;

            case POST_RECORD:
                logger.info("Extending recording: blind-spot alert during post-record countdown");
                cancelPostRecordTimer();
                transitionTo(State.RECORDING);
                break;

            case RECORDING:
                break;

            case IDLE:
                logger.warn("Received blind-spot trigger while IDLE - should not happen");
                break;
        }
    }

    @Override
    public synchronized void onBlindSpotSafe() {
        if (blindSpotMonitor.isTriggered()) {
            logger.debug("Ignoring stale blind-spot safe");
            return;
        }
        logger.info("onBlindSpotSafe: state=" + currentState);
        onBlindSpotCleared();
    }

    private void onBlindSpotCleared() {
        if (radarMonitor.isTriggered()) {
            logger.info("Blind spot clear but the radar is still triggered - recording continues");
            return;
        }
        if (currentState == State.RECORDING) {
            transitionTo(State.POST_RECORD);
            startPostRecordTimer();
        }
    }

    /** Arms or disarms the blind-spot source to match the config; a live trigger is released. */
    private void applyBlindSpotTrigger() {
        if (config.isBlindSpotTrigger()) {
            boolean pollUseful = !com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
            if (blindSpotMonitor.arm(pollUseful)) {
                logger.info("Blind-spot trigger armed (" + (pollUseful ? "poll + events" : "relayed events") + ")");
                refreshBlindSpotPoll(true);
            }
        } else if (blindSpotMonitor.isArmed()) {
            boolean wasTriggered = blindSpotMonitor.disarm();
            logger.info("Blind-spot trigger disarmed");
            refreshBlindSpotPoll(false);
            if (wasTriggered) onBlindSpotCleared();
        }
    }

    // Off the caller's thread: the poll's reference check reads the config file. Repeated once after
    // arming, because a concurrent refresh that read the old state can cancel the poll just after this.
    private void refreshBlindSpotPoll(boolean armed) {
        if (blindSpotPollRetry != null) {
            blindSpotPollRetry.cancel(false);
            blindSpotPollRetry = null;
        }
        Runnable refresh = () -> {
            try {
                com.overdrive.app.automation.condition.BlindSpotEvent.refresh();
            } catch (Throwable t) {
                logger.warn("Blind-spot poll refresh failed: " + t.getMessage());
            }
        };
        try {
            scheduler.execute(refresh);
            if (armed) blindSpotPollRetry = scheduler.schedule(refresh, 5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.debug("Blind-spot poll refresh skipped: scheduler shut down");
        }
    }

    // ==================== STATE MACHINE ====================
    
    private void transitionTo(State newState) {
        if (currentState == newState) {
            return;
        }
        
        logger.info("State transition: " + currentState + " -> " + newState);
        
        // Exit actions for old state
        switch (currentState) {
            case MONITORING:
                // No cleanup needed
                break;
            case RECORDING:
                // Recording will be stopped by caller
                break;
            case POST_RECORD:
                cancelPostRecordTimer();
                break;
            case IDLE:
                // No cleanup needed
                break;
        }
        
        currentState = newState;
        
        // Entry actions for new state
        switch (newState) {
            case IDLE:
                logger.info("Entered IDLE state");
                // Proximity is shutting down (e.g. gear->P). Restore the shared
                // recording encoder to full rate + event bitrate so a following
                // mode (surveillance/sentry/continuous reusing the same pipeline)
                // doesn't inherit our throttled idle profile.
                restoreFullProfile();
                break;
            case MONITORING:
                logger.info("Entered MONITORING state - waiting for radar triggers");
                // Reset session-scoped escalation tracking so the next event
                // starts from YELLOW unless the radar reports RED again.
                redEscalatedThisSession = false;
                // Drop the recording lane to the low-rate, low-bitrate idle
                // profile while we wait for a trigger. The pre-record ring keeps
                // filling (so pre-roll is preserved) but at a fraction of the
                // GPU-encode / drain / disk cost.
                applyMonitorProfile();
                break;
            case RECORDING:
                logger.info("Entered RECORDING state");
                // Snap to full rate + event bitrate. Applied BEFORE the caller's
                // startRecording() flushes the pre-record ring, so the live
                // portion of the clip is full-quality from its first frame. The
                // already-buffered pre-roll frames keep their (lower) idle
                // quality — acceptable, they are lead-in context.
                applyEventProfile();
                break;
            case POST_RECORD:
                logger.info("Entered POST_RECORD state - countdown started");
                // Stay on the event profile — the post-record window is still
                // capturing event aftermath and should remain full quality.
                break;
        }
        // Tell the OEM DVR (opt-in throttle) whether the low-power monitor is waiting.
        try {
            com.overdrive.app.recording.OemDashcamMirror.onProximityMonitorFps(desiredCameraFps());
        } catch (Throwable ignored) {
        }
    }

    // ==================== ADAPTIVE RECORDING PROFILE ====================
    // Proximity Guard records on radar triggers but the encoder runs continuously
    // to keep a pre-record ring warm. While MONITORING (the common idle state) we
    // feed that ring at a low frame-rate + low bitrate to minimise resource use,
    // then snap to the configured event quality the instant a trigger fires, and
    // revert when the event (incl. post-record) completes. fps is driven by the
    // recorder draw stride (render loop feeds the encoder every Nth frame);
    // bitrate is a runtime MediaCodec param. Streaming / blind-spot lanes use
    // separate encoders and are unaffected. All gated on config.lowPowerMonitor.

    private void applyMonitorProfile() {
        if (!config.isLowPowerMonitor()) {
            return;
        }
        try {
            int monitorFps = Math.max(1, config.getMonitorFps());
            // The camera HAL fps is owned by the SINGLE authority
            // (RecordingModeManager.desiredCameraState), NOT here — it sets the HAL
            // to the monitor rate while we sit MONITORING (the resource win), and
            // raises it when ANOTHER consumer needs more (a SHOWN blind-spot view,
            // or a live-view stream). We must NOT set the camera fps here or we'd
            // fight that authority (e.g. flap 15->4 when BS is shown during
            // proximity). Instead, derive the recorder DRAW STRIDE against the
            // CURRENT camera fps so the pre-record RING always fills at ~monitorFps
            // regardless of what the camera HAL is doing:
            //   - camera at monitorFps (BS hidden, no stream) -> stride 1, ring = monitorFps.
            //   - camera raised to 15 (BS shown) -> stride round(15/monitorFps),
            //     so the ring still fills at ~monitorFps (low-power ENCODE preserved
            //     even though the HAL must run faster for the BS view).
            int cameraFps = pipeline.getCameraTargetFps();
            if (cameraFps <= 0) cameraFps = monitorFps;
            int stride = Math.max(1, Math.round(cameraFps / (float) monitorFps));
            pipeline.setRecorderFrameStride(stride);
            pipeline.setRecordingBitrate(config.getMonitorBitrate());
            // At the low effective ring rate the encoder's frame-count GOP still
            // stretches past the pre-record window, so drive keyframes on a
            // wall-clock cadence: one immediately + a periodic pulse.
            pipeline.requestRecordingSyncFrame();
            startKeyframePulse();
            logger.info("Monitor profile applied: stride=" + stride + " (camera HAL fps="
                    + cameraFps + " -> ring ~" + (cameraFps / stride) + " fps), bitrate="
                    + (config.getMonitorBitrate() / 1_000_000.0) + " Mbps, keyframe pulse on");
        } catch (Exception e) {
            logger.warn("Failed to apply monitor profile: " + e.getMessage());
        }
    }

    private void applyEventProfile() {
        if (!config.isLowPowerMonitor()) {
            return;
        }
        try {
            // Leaving MONITORING — stop the keyframe pulse; the full-rate GOP
            // resumes natural 2s IDR spacing.
            cancelKeyframePulse();
            // Snap the GLOBAL camera HAL back to full recording fps IMMEDIATELY.
            // While MONITORING we drop the camera HAL to the monitor rate (~4fps)
            // to save the OEM capture cost; a radar event must record the LIVE
            // portion at full fps, so we cannot wait for the 30s reconcile tick —
            // set it here, synchronously, the instant the event fires. This is
            // the camera CAPTURE rate; the stride below (=1) then draws every
            // captured frame. reconcileCameraProfile's PROXIMITY branch defers to
            // the controller (desiredCameraFps()==0 during RECORDING => "full"),
            // so it agrees on the next tick — no fight.
            try {
                // getCameraTargetFps reflects the CURRENT (lowered) rate, so use
                // the pipeline's CONFIGURED recording fps as the snap-up target.
                // getConfiguredRecordingFps() never returns <=0 (loadTargetFps
                // falls back to 15 + clamps internally), so no guard needed here.
                pipeline.setCameraTargetFps(pipeline.getConfiguredRecordingFps());
            } catch (Throwable t) {
                logger.warn("Event profile: camera fps snap-up failed: " + t.getMessage());
            }
            // Full rate so the live recording is full-fps, then event bitrate.
            pipeline.setRecorderFrameStride(1);
            pipeline.setRecordingBitrate(config.getEventBitrate());
            // Force an IDR NOW so the live portion opens on a clean keyframe
            // regardless of how stale the last monitor-rate IDR was. Without
            // this the live segment could open mid-GOP (P-frames referencing an
            // IDR that may be many seconds old) and render garbage until the
            // next scheduled keyframe.
            pipeline.requestRecordingSyncFrame();
            logger.info("Event profile applied: stride=1 (full rate), bitrate="
                    + (config.getEventBitrate() / 1_000_000.0) + " Mbps, sync frame requested");
        } catch (Exception e) {
            logger.warn("Failed to apply event profile: " + e.getMessage());
        }
    }

    /**
     * Restore the recording encoder to full rate + the pipeline's user-configured
     * recording bitrate. Used on IDLE (proximity teardown) so the shared encoder
     * is never left in the throttled idle profile for a subsequent recording
     * mode. Runs UNCONDITIONALLY (not gated on lowPowerMonitor): if the flag was
     * toggled off mid-session while we'd already throttled, gating here would
     * strand the encoder at the low bitrate. setRecordingBitrate is idempotent
     * and only re-asserts a known-good value, so this is safe even when we never
     * throttled. Restores to the pipeline's configured bitrate (not proximity's
     * eventBitrate) so a follow-on mode inherits the user's true quality.
     */
    private void restoreFullProfile() {
        try {
            cancelKeyframePulse();
            // Restore the camera HAL to the configured recording fps too — while
            // MONITORING we lowered it to the monitor rate, and a follow-on mode
            // (or pipeline reuse) must not inherit our throttled capture rate.
            try {
                // getConfiguredRecordingFps() never returns <=0 (internal fallback).
                pipeline.setCameraTargetFps(pipeline.getConfiguredRecordingFps());
            } catch (Throwable t) {
                logger.warn("restoreFullProfile: camera fps restore failed: " + t.getMessage());
            }
            pipeline.setRecorderFrameStride(1);
            pipeline.setRecordingBitrate(pipeline.getConfiguredRecordingBitrate());
        } catch (Exception e) {
            logger.warn("Failed to restore full recording profile: " + e.getMessage());
        }
    }

    /**
     * Start the MONITORING keyframe pulse (idempotent). Requests an IDR every
     * ~preRecordSeconds/2 (min 1s) so the pre-record window always contains a
     * keyframe despite the stretched low-rate GOP.
     */
    private void startKeyframePulse() {
        if (keyframePulseTimer != null && !keyframePulseTimer.isDone()) {
            return;
        }
        // Pulse period = 3/4 of the pre-record window (was 1/2).
        //
        // The ONLY correctness requirement is that at least one keyframe always
        // lies inside the pre-record window, so a trigger-time flush produces a
        // decodable clip. 1/2 satisfied that with 2x margin; 3/4 still keeps a
        // full quarter-window of margin while cutting forced IDRs by ~33%.
        //
        // Why that matters: each pulse is a full-resolution IDR (2560x1920 by
        // default) on the shared hardware video block, and a keyframe costs
        // several times a P-frame. At the low monitor ring rate the IDRs were a
        // large fraction of all encoded frames, so this is a direct reduction in
        // sustained load on the block the UI also composites through.
        //
        // Kept DERIVED from preRecordSeconds rather than hardcoded: the window is
        // user-configurable 2-15s (ProximityGuardConfig MIN/MAX_PRE_RECORD_SECONDS),
        // and a fixed period would break the short end (a flat 4s pulse with a 2s
        // window could leave the window keyframe-less). Math.max(1L, ...) floors
        // the 2s window at a 1s pulse.
        //
        // Note this is independent of camera fps: applyMonitorProfile derives a
        // draw stride so the ring always fills at ~monitorFps (8 by default)
        // regardless of whether the HAL runs at 8, 15, 25 or 30 — so the
        // keyframe-per-window guarantee holds across the whole fps range.
        long periodSec = Math.max(1L, (config.getPreRecordSeconds() * 3L) / 4L);
        keyframePulseTimer = scheduler.scheduleWithFixedDelay(() -> {
            try {
                // Only pulse while still MONITORING — a late tick after a state
                // change is a harmless no-op but skip it cleanly.
                if (currentState == State.MONITORING) {
                    pipeline.requestRecordingSyncFrame();
                }
            } catch (Throwable t) {
                logger.debug("Keyframe pulse tick failed: " + t.getMessage());
            }
        }, periodSec, periodSec, TimeUnit.SECONDS);
        logger.debug("Keyframe pulse started (every " + periodSec + "s)");
    }

    private void cancelKeyframePulse() {
        if (keyframePulseTimer != null && !keyframePulseTimer.isDone()) {
            keyframePulseTimer.cancel(false);
        }
        keyframePulseTimer = null;
    }
    
    // ==================== POST-RECORD TIMER ====================
    
    private void startPostRecordTimer() {
        cancelPostRecordTimer();  // Cancel any existing timer

        // RED tier escalation extends the post-record window to the
        // configured maximum (capped at MAX_POST_RECORD_SECONDS=30) so a
        // RED-then-YELLOW-then-safe sequence captures more aftermath than
        // a YELLOW-only event. Without this, the controller honours the
        // dialled-in YELLOW value regardless of how serious the trigger
        // got — the user explicitly asked us not to silently equalise
        // RED and YELLOW outcomes.
        int postRecordSeconds = config.getPostRecordSeconds();
        if (redEscalatedThisSession) {
            int redSec = Math.max(postRecordSeconds,
                    com.overdrive.app.proximity.ProximityGuardConfig.getMaxPostRecordSeconds());
            logger.info("RED escalation seen this session — extending post-record window: "
                    + postRecordSeconds + "s → " + redSec + "s");
            postRecordSeconds = redSec;
        }
        logger.info("Starting post-record timer: " + postRecordSeconds + " seconds");

        final int countdownSeconds = postRecordSeconds;
        postRecordTimer = scheduler.schedule(() -> {
            synchronized (this) {
                if (currentState == State.POST_RECORD) {
                    logger.info("Post-record timer expired (after " + countdownSeconds
                            + "s) - stopping recording");
                    recordingHandler.stopRecording();
                    com.overdrive.app.recording.OemDashcamMirror.onPanoRecordingStopped();
                    transitionTo(State.MONITORING);
                }
            }
        }, postRecordSeconds, TimeUnit.SECONDS);
    }
    
    private void cancelPostRecordTimer() {
        if (postRecordTimer != null && !postRecordTimer.isDone()) {
            postRecordTimer.cancel(false);
            logger.debug("Post-record timer cancelled");
        }
        postRecordTimer = null;
    }
    
    // ==================== CONFIG ====================
    
    private ProximityGuardConfig loadConfig() {
        try {
            JSONObject proximityConfig = UnifiedConfigManager.getProximityGuard();
            return ProximityGuardConfig.fromConfig(proximityConfig);
        } catch (Exception e) {
            logger.error("Failed to load proximity config: " + e.getMessage());
            return ProximityGuardConfig.createDefault();
        }
    }
    
    /**
     * Reload configuration (call when config changes).
     */
    public synchronized void reloadConfig() {
        config = loadConfig();
        // Apply pre-record duration to the encoder so live UI changes take
        // effect without an ACC cycle. Symmetric with start().
        if (currentState != State.IDLE) {
            try {
                com.overdrive.app.surveillance.HardwareEventRecorderGpu enc = pipeline.getEncoder();
                if (enc != null) {
                    enc.setPreRecordDuration(config.getPreRecordSeconds());
                }
            } catch (Exception e) {
                logger.warn("Failed to re-apply pre-record duration on reload: " + e.getMessage());
            }
            radarMonitor.setTriggerLevel(config.getTriggerLevel());
            applyBlindSpotTrigger();
        }
        logger.info("Config reloaded: " + config.toString());
    }
    
    /**
     * Shutdown and cleanup resources.
     */
    public void shutdown() {
        synchronized (this) {
            stop();
            closed = true;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        logger.info("ProximityGuardController shutdown complete");
    }
}
