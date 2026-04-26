package com.overdrive.app.surveillance;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.ai.YoloDetector;
import com.overdrive.app.telegram.TelegramNotifier;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SurveillanceEngineGpu - V2 Per-Quadrant Motion Detection Pipeline
 * 
 * Uses the V2 native pipeline for per-quadrant 6-stage motion detection
 * with staggered YOLO AI inference on active quadrants.
 */
public class SurveillanceEngineGpu {
    private static final String TAG = "SurveillanceEngineGpu";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // Motion detection buffers
    private ByteBuffer currentFrame;
    private long lastMotionTime = 0;
    private long firstMotionTime = 0;  // When sustained motion started (for duration check)
    
    // SUSTAINED MOTION: Base minimum before any trigger (prevents single-frame noise).
    // For THREAT_HIGH (loitering confirmed), this is the only delay needed.
    // For THREAT_MEDIUM (approaching), the loitering time setting adds additional delay.
    private static final long SUSTAINED_MOTION_BASE_MS = 500;
    
    // Loitering time in ms — derived from user setting (1-10 seconds).
    // THREAT_MEDIUM must persist for this duration before triggering recording.
    // THREAT_HIGH triggers after SUSTAINED_MOTION_BASE_MS (loitering already confirmed by native pipeline).
    private long loiteringTimeMs = 3000;  // Default 3 seconds
    
    // MOTION THROTTLING: Process motion at 10 FPS max (saves 66% CPU vs 30 FPS)
    private static final long MOTION_PROCESS_INTERVAL_MS = 100;  // 10 FPS
    private long lastMotionProcessTime = 0;
    
    // ROI mask (null = full frame, otherwise byte array with 0/1 values)
    private byte[] roiMask = null;
    private int roiPixelCount = 0;  // Number of pixels in ROI (for normalization)
    
    // Reference to downscaler for buffer recycling
    private GpuDownscaler downscaler;
    
    // Reference to mosaic recorder for triggering recording
    private GpuMosaicRecorder recorder;
    
    // SOTA: Grid Motion Configuration
    // 640x480 / 32 = 20x15 grid. 32px blocks are ideal for human detection at distance.
    private static final int GRID_BLOCK_SIZE = 32;
    private static final int GRID_COLS = 640 / GRID_BLOCK_SIZE;  // 20
    private static final int GRID_ROWS = 480 / GRID_BLOCK_SIZE;  // 15
    private static final int TOTAL_BLOCKS = GRID_COLS * GRID_ROWS;  // 300
    
    // SIMPLIFIED: Frame-to-frame motion detection
    private int requiredActiveBlocks = 3;    // Need 3+ blocks changed to trigger
    
    // SOTA: Flash Immunity Level (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
    // Uses edge-based detection to ignore light flashes while detecting real motion
    private int flashImmunity = 2;  // Default: MEDIUM
    
    // SOTA: Unified configuration for motion detection, flash filtering, and distance estimation
    private SurveillanceConfig config = createDefaultConfig();
    
    /**
     * Creates default config with proper resolution for mosaic mode.
     * SOTA: Enables chroma filtering by default to ignore lighting changes.
     */
    private static SurveillanceConfig createDefaultConfig() {
        SurveillanceConfig cfg = new SurveillanceConfig(
            SurveillanceConfig.DistancePreset.MEDIUM,
            SurveillanceConfig.FlashMode.ADAPTIVE
        );
        // CRITICAL: Set resolution to match THUMBNAIL dimensions
        cfg.setResolution(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        cfg.setIsMosaic(true);  // We use 2x2 mosaic layout
        cfg.setUseChroma(true); // SOTA: Enable chroma filtering to ignore lighting changes
        return cfg;
    }
    
    // Track active blocks for UI display
    private int lastActiveBlocksCount = 0;
    private int lastTemporalBlocksCount = 0;  // SOTA: Temporally consistent blocks
    private int lastMotionMinY = 0;           // SOTA: Top of motion bounding box
    private int lastMotionMaxY = 0;           // SOTA: Bottom of motion bounding box
    private float lastEstimatedDistance = 0;  // SOTA: Estimated distance in meters
    
    // Pre-record and post-record configuration (configurable via API)
    private long preRecordMs = 5000;    // 5 seconds before motion (default)
    private long postRecordMs = 10000;  // 10 seconds after motion (default)
    private long recordingStopTime = 0;  // When to stop recording (motion time + post-record)
    private long lastRecordingStopTime = 0;  // When last recording stopped (for cooldown)
    
    // Detection mode
    private boolean useObjectDetection = false;
    private YoloDetector yoloDetector = null;
    
    // Object detection filters (SOTA: Quadrant-relative height filter in YoloDetector)
    private float minObjectSize = 0.12f;  // 12% of QUADRANT height (~8m for person in 2x2 grid)
    private float aiConfidence = 0.25f;  // 25% confidence (lowered for debugging)
    private int[] classFilter = null;  // null = all classes, or {0, 2, 3} for person, car, bike
    
    // AI throttling - only run YOLO every 500ms to save CPU
    private long lastAiTimeMs = 0;
    private static final long AI_COOLDOWN_MS = 500;
    
    // --- SOTA FIX: Persistent Resources (Eliminates GC Stutter) ---
    // 1. Reusable Buffer: Prevents ~900KB allocation per frame
    private byte[] aiBuffer = null;
    // 2. Single Thread Executor: Prevents OS thread creation overhead
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    // 3. Atomic Flag for thread safety
    private final AtomicBoolean isAiRunning = new AtomicBoolean(false);
    // --- END SOTA FIX ---
    
    // State
    private boolean active = false;
    private boolean inActiveMode = false;
    private boolean recording = false;
    
    // V2 Pipeline: Per-quadrant 6-stage motion detection
    private MotionPipelineV2 pipelineV2 = null;
    private MotionPipelineV2.Config pipelineV2Config = null;
    // Staggered YOLO: queue of quadrants to run AI on
    private final java.util.Queue<Integer> aiQuadrantQueue = new java.util.LinkedList<>();
    
    // Foveated AI cropping: high-res 640×640 crop from raw camera strip
    private FoveatedCropper foveatedCropper = null;
    private int cameraTextureId = -1;  // OES texture for foveated crop
    
    // Cross-quadrant object tracker
    private final CrossQuadrantTracker crossQuadrantTracker = new CrossQuadrantTracker();
    
    // Heartbeat cooldown: prevent NCC tracker from spamming YOLO on every frame
    // when the template match is failing. Without this, a bad template causes
    // needsYoloHeartbeat=true on every frame, turning YOLO into a continuous
    // 10 FPS detector and destroying the battery savings of decoupled tracking.
    private static final long HEARTBEAT_COOLDOWN_MS = 2000;  // Min 2s between heartbeats per quadrant
    private final long[] lastHeartbeatTimeMs = new long[MotionPipelineV2.NUM_QUADRANTS];
    
    // Auto-exposure state (C++ handles per-quadrant threshold scaling,
    // Java only handles global params like brightness suppression and shadow filter mode)
    
    // Filter debug log: ring buffer of recent filter decisions (max 100 entries)
    private static final int FILTER_LOG_CAPACITY = 100;
    private final String[] filterLog = new String[FILTER_LOG_CAPACITY];
    private int filterLogIndex = 0;
    private int filterLogCount = 0;
    private boolean filterDebugEnabled = false;
    
    // SOTA: Event timeline collector for JSON sidecar files
    private final EventTimelineCollector timelineCollector = new EventTimelineCollector();
    
    // Output directory
    private File eventOutputDir;
    private File currentEventFile;
    
    // Frame dimensions - SOTA: Increased to 640x480 for better AI detection
    // At 320x240 with quad view, each camera is 160x120 - too small for YOLO
    // At 640x480 with quad view, each camera is 320x240 - YOLO can detect people at 5m
    private static final int THUMBNAIL_WIDTH = 640;
    private static final int THUMBNAIL_HEIGHT = 480;
    private static final int BYTES_PER_PIXEL = 3;  // RGB
    private static final int FRAME_SIZE = THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT * BYTES_PER_PIXEL;
    
    // Stats
    private int frameCount = 0;
    private int motionDetections = 0;
    
    /**
     * Initializes the surveillance engine.
     * 
     * @param eventDir Directory for saving event recordings
     * @param downscaler GPU downscaler reference for buffer recycling
     */
    public void init(File eventDir, GpuDownscaler downscaler) {
        init(eventDir, downscaler, null, null);
    }
    
    /**
     * Initializes the surveillance engine with optional AssetManager for YOLO loading.
     * 
     * @param eventDir Directory for saving event recordings
     * @param downscaler GPU downscaler reference for buffer recycling
     * @param assetManager Android AssetManager for loading YOLO model (null = skip YOLO)
     */
    public void init(File eventDir, GpuDownscaler downscaler, android.content.res.AssetManager assetManager) {
        init(eventDir, downscaler, assetManager, null);
    }
    
    /**
     * Initializes the surveillance engine with Context for Java TFLite.
     * 
     * @param eventDir Directory for saving event recordings
     * @param downscaler GPU downscaler reference for buffer recycling
     * @param assetManager Android AssetManager (unused, kept for compatibility)
     * @param context Android Context for TFLite initialization
     */
    public void init(File eventDir, GpuDownscaler downscaler, android.content.res.AssetManager assetManager, android.content.Context context) {
        this.eventOutputDir = eventDir;
        this.downscaler = downscaler;
        
        if (!eventDir.exists()) {
            eventDir.mkdirs();
        }
        
        // Allocate direct buffer for V2 pipeline JNI
        currentFrame = ByteBuffer.allocateDirect(FRAME_SIZE);
        currentFrame.order(ByteOrder.nativeOrder());
        
        // Detect available features
        try {
            // Initialize Java TFLite YOLO detector
            // Note: We don't have a full Context in daemon mode, but we can create one from AssetManager
            if (context != null) {
                try {
                    logger.info("Initializing Java TFLite YOLO detector...");
                    yoloDetector = new YoloDetector(context);
                    boolean yoloLoaded = yoloDetector.init();
                    
                    if (yoloLoaded) {
                        useObjectDetection = true;
                        logger.info("YOLO model loaded successfully - object detection enabled");
                        logger.info("GPU acceleration: " + (yoloDetector.isGpuEnabled() ? "ENABLED" : "disabled (CPU fallback)"));
                    } else {
                        logger.warn("Failed to load YOLO model");
                        useObjectDetection = false;
                        yoloDetector = null;
                    }
                } catch (Exception e) {
                    logger.error("Error initializing YOLO detector: " + e.getMessage(), e);
                    useObjectDetection = false;
                    yoloDetector = null;
                }
            } else if (assetManager != null) {
                // Daemon mode: Create minimal context from AssetManager
                try {
                    logger.info("Creating AssetContext for TFLite (daemon mode)...");
                    android.content.Context assetContext = new com.overdrive.app.ai.AssetContext(assetManager);
                    
                    yoloDetector = new YoloDetector(assetContext);
                    boolean yoloLoaded = yoloDetector.init();
                    
                    if (yoloLoaded) {
                        useObjectDetection = true;
                        logger.info("YOLO model loaded successfully - object detection enabled");
                        logger.info("GPU acceleration: " + (yoloDetector.isGpuEnabled() ? "ENABLED" : "disabled (CPU fallback)"));
                    } else {
                        logger.warn("Failed to load YOLO model");
                        useObjectDetection = false;
                        yoloDetector = null;
                    }
                } catch (Exception e) {
                    logger.error("Error creating AssetContext: " + e.getMessage(), e);
                    useObjectDetection = false;
                    yoloDetector = null;
                }
            } else {
                logger.info("No Context or AssetManager provided - object detection disabled");
                useObjectDetection = false;
            }
        } catch (UnsatisfiedLinkError e) {
            logger.warn("Native features not available: " + e.getMessage());
            useObjectDetection = false;
        }
        
        logger.info("Initialized surveillance engine (buffer=" + FRAME_SIZE + " bytes)");
        
        // Initialize V2 per-quadrant pipeline
        try {
            pipelineV2 = new MotionPipelineV2();
            if (pipelineV2.init()) {
                pipelineV2Config = new MotionPipelineV2.Config();
                pipelineV2Config.applyEnvironmentPreset("outdoor");  // Default preset
                pipelineV2.applyConfig(pipelineV2Config);
                logger.info("V2 per-quadrant pipeline initialized");
            } else {
                logger.error("V2 pipeline init failed");
                pipelineV2 = null;
            }
        } catch (Exception e) {
            logger.error("V2 pipeline not available: " + e.getMessage());
            pipelineV2 = null;
        }
    }
    
    /**
     * Sets the mosaic recorder for event recording.
     * 
     * @param recorder Mosaic recorder instance
     */
    public void setRecorder(GpuMosaicRecorder recorder) {
        this.recorder = recorder;
    }
    
    /**
     * Set the foveated cropper for high-res AI inference.
     * When set, YOLO runs on a 640×640 crop from the raw 5120×960 strip
     * instead of the 320×240 mosaic quadrant. Must be called from GL thread.
     *
     * @param cropper FoveatedCropper instance (initialized on GL thread)
     * @param textureId Camera OES texture ID for direct strip access
     */
    public void setFoveatedCropper(FoveatedCropper cropper, int textureId) {
        this.foveatedCropper = cropper;
        this.cameraTextureId = textureId;
        if (cropper != null && cropper.isInitialized()) {
            logger.info("Foveated AI cropping enabled (640×640 from raw strip)");
        }
    }
    
    /**
     * Get the current foveated cropper (for lazy-init check).
     */
    public FoveatedCropper getFoveatedCropper() {
        return foveatedCropper;
    }
    
    /**
     * SOTA: Updates the event output directory.
     * Called when storage type changes (internal <-> SD card) to ensure
     * events are saved to the correct location.
     * 
     * @param eventDir New directory for saving event recordings
     */
    public void setEventOutputDir(File eventDir) {
        this.eventOutputDir = eventDir;
        if (eventDir != null && !eventDir.exists()) {
            boolean created = eventDir.mkdirs();
            logger.info("Updated event output directory: " + eventDir.getAbsolutePath() + " (created=" + created + ")");
            if (created) {
                eventDir.setReadable(true, false);
                eventDir.setExecutable(true, false);
            }
        } else {
            logger.info("Updated event output directory: " + (eventDir != null ? eventDir.getAbsolutePath() : "null"));
        }
    }
    
    /**
     * Processes a frame from the GPU downscaler.
     * 
     * This is called at 2 FPS during idle mode. When motion is detected,
     * it can be called at 5 FPS for more responsive AI.
     * 
     * CRITICAL: This method receives a BORROWED buffer from the pool.
     * The buffer MUST be recycled in a finally block to prevent pool exhaustion.
     * If async AI is needed, the data must be copied before recycling.
     * 
     * @param smallRgbFrame 320x240 RGB frame from GPU (borrowed from pool)
     */
    public void processFrame(byte[] smallRgbFrame) {
        if (!active) {
            // Still need to recycle even if not active
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }
        
        if (smallRgbFrame == null || smallRgbFrame.length != FRAME_SIZE) {
            logger.warn( "Invalid frame size: " + (smallRgbFrame != null ? smallRgbFrame.length : 0));
            if (downscaler != null && smallRgbFrame != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }
        
        try {
            frameCount++;
            long now = System.currentTimeMillis();
            
            // Log frame count every 100 frames to confirm frames are arriving
            if (frameCount % 100 == 0) {
                logger.info("Surveillance frame #" + frameCount + " received");
            }
            
            // MOTION THROTTLING: Skip frames to achieve 10 FPS (saves 66% CPU)
            if (now - lastMotionProcessTime < MOTION_PROCESS_INTERVAL_MS) {
                return;
            }
            lastMotionProcessTime = now;
            
            if (pipelineV2 == null) {
                logger.warn("V2 pipeline not initialized — skipping frame");
                return;
            }
            
            processFrameV2(smallRgbFrame, now);
            
        } finally {
            // CRITICAL: Always recycle buffer back to pool
            // This MUST happen in finally block to prevent pool exhaustion
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
        }
    }
    
    /**
     * V2 Pipeline: Per-quadrant 6-stage motion detection.
     */
    // Track peak threat level during a motion sequence (reset when sequence ends)
    private int peakThreatDuringSequence = 0;
    
    // Previous frame sample for Java-side motion diff check (independent of native pipeline)
    private int[] prevFrameSamples = null;
    private int[] prevDenseHash = null;
    
    private void processFrameV2(byte[] smallRgbFrame, long now) {
        // Copy frame data into a direct ByteBuffer for JNI
        currentFrame.clear();
        currentFrame.put(smallRgbFrame);
        currentFrame.flip();
        
        // DIAGNOSTIC: Every 100 frames, check frame validity and inter-frame diff.
        // Only in debug builds — this is pure development tooling.
        if (com.overdrive.app.BuildConfig.DEBUG && frameCount % 100 == 0) {
            // Sample 16 pixels spread across the frame
            int[] currentSamples = new int[16];
            int[][] sampleCoords = {
                {60, 80}, {60, 240}, {60, 400}, {60, 560},    // Row 1
                {180, 80}, {180, 240}, {180, 400}, {180, 560}, // Row 2
                {300, 80}, {300, 240}, {300, 400}, {300, 560}, // Row 3
                {420, 80}, {420, 240}, {420, 400}, {420, 560}  // Row 4
            };
            boolean allBlack = true;
            for (int i = 0; i < 16; i++) {
                int off = (sampleCoords[i][0] * THUMBNAIL_WIDTH + sampleCoords[i][1]) * 3;
                if (off + 2 < smallRgbFrame.length) {
                    int r = smallRgbFrame[off] & 0xFF;
                    int g = smallRgbFrame[off + 1] & 0xFF;
                    int b = smallRgbFrame[off + 2] & 0xFF;
                    currentSamples[i] = (r << 16) | (g << 8) | b;
                    if (r > 5 || g > 5 || b > 5) allBlack = false;
                }
            }
            
            // Compare with previous frame samples
            int maxDiff = 0;
            int changedSamples = 0;
            if (prevFrameSamples != null) {
                for (int i = 0; i < 16; i++) {
                    int r1 = (currentSamples[i] >> 16) & 0xFF;
                    int g1 = (currentSamples[i] >> 8) & 0xFF;
                    int b1 = currentSamples[i] & 0xFF;
                    int r2 = (prevFrameSamples[i] >> 16) & 0xFF;
                    int g2 = (prevFrameSamples[i] >> 8) & 0xFF;
                    int b2 = prevFrameSamples[i] & 0xFF;
                    int diff = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
                    if (diff > maxDiff) maxDiff = diff;
                    if (diff > 10) changedSamples++;
                }
            }
            prevFrameSamples = currentSamples;
            
            // Also compute a dense diff: scan every 20th pixel across the full frame
            int denseMaxDiff = 0;
            int denseChanged = 0;
            int denseSamples = 0;
            if (prevDenseHash != null) {
                for (int y = 0; y < THUMBNAIL_HEIGHT; y += 20) {
                    for (int x = 0; x < THUMBNAIL_WIDTH; x += 20) {
                        int off = (y * THUMBNAIL_WIDTH + x) * 3;
                        if (off + 2 < smallRgbFrame.length) {
                            int r = smallRgbFrame[off] & 0xFF;
                            int g = smallRgbFrame[off + 1] & 0xFF;
                            int b = smallRgbFrame[off + 2] & 0xFF;
                            int idx = denseSamples;
                            if (idx < prevDenseHash.length) {
                                int pr = (prevDenseHash[idx] >> 16) & 0xFF;
                                int pg = (prevDenseHash[idx] >> 8) & 0xFF;
                                int pb = prevDenseHash[idx] & 0xFF;
                                int diff = Math.abs(r - pr) + Math.abs(g - pg) + Math.abs(b - pb);
                                if (diff > denseMaxDiff) denseMaxDiff = diff;
                                if (diff > 30) denseChanged++;
                            }
                            denseSamples++;
                        }
                    }
                }
            }
            // Store dense samples for next comparison
            int totalDense = (THUMBNAIL_HEIGHT / 20) * (THUMBNAIL_WIDTH / 20);
            if (prevDenseHash == null || prevDenseHash.length != totalDense) {
                prevDenseHash = new int[totalDense];
            }
            int di = 0;
            for (int y = 0; y < THUMBNAIL_HEIGHT; y += 20) {
                for (int x = 0; x < THUMBNAIL_WIDTH; x += 20) {
                    int off = (y * THUMBNAIL_WIDTH + x) * 3;
                    if (off + 2 < smallRgbFrame.length && di < prevDenseHash.length) {
                        int r = smallRgbFrame[off] & 0xFF;
                        int g = smallRgbFrame[off + 1] & 0xFF;
                        int b = smallRgbFrame[off + 2] & 0xFF;
                        prevDenseHash[di++] = (r << 16) | (g << 8) | b;
                    }
                }
            }
            
            // Log sample pixels from each quadrant center
            int q0 = currentSamples[5];
            int q1 = currentSamples[6];
            int q2 = currentSamples[9];
            int q3 = currentSamples[10];
            
            logger.info(String.format("FRAME_DIAG #%d: %s | sparse: max=%d changed=%d/16 | dense: max=%d changed=%d/%d | Q0=(%d,%d,%d) Q1=(%d,%d,%d) Q2=(%d,%d,%d) Q3=(%d,%d,%d)",
                    frameCount,
                    allBlack ? "ALL_BLACK!" : "ok",
                    maxDiff, changedSamples,
                    denseMaxDiff, denseChanged, denseSamples,
                    (q0>>16)&0xFF, (q0>>8)&0xFF, q0&0xFF,
                    (q1>>16)&0xFF, (q1>>8)&0xFF, q1&0xFF,
                    (q2>>16)&0xFF, (q2>>8)&0xFF, q2&0xFF,
                    (q3>>16)&0xFF, (q3>>8)&0xFF, q3&0xFF));
        }
        
        // Run V2 pipeline (includes C++ Global Illumination Sync)
        MotionPipelineV2.QuadrantResult[] results = pipelineV2.processFrame(
                currentFrame, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        
        // Check if any quadrant detected motion at MEDIUM or higher threat.
        int maxThreat = pipelineV2.getMaxThreatLevel();
        boolean anyMotion = maxThreat >= MotionPipelineV2.THREAT_MEDIUM;
        
        // SOTA: Tracker immunity from brightness suppression (Headlight Sweep Fix).
        // When a car's headlights sweep across the camera, the brightness suppression
        // stage kills ALL motion blocks in that quadrant. If a person is being tracked
        // in that quadrant, the motion sequence timer loses them and the recording
        // stops prematurely. Fix: if any quadrant is brightness-suppressed but the
        // NCC tracker has an active lock on it, keep anyMotion=true so the sequence
        // timer continues. The tracker's pixel-level lock is immune to global
        // brightness changes — it tracks texture, not absolute luminance.
        if (!anyMotion) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (results[q].brightnessSuppressed) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(q)) {
                            anyMotion = true;
                            if (maxThreat < MotionPipelineV2.THREAT_MEDIUM) {
                                maxThreat = MotionPipelineV2.THREAT_MEDIUM;
                            }
                            if (frameCount % 50 == 0) {
                                logger.info("Headlight sweep immunity: Q" + q + 
                                        " [" + MotionPipelineV2.QUADRANT_NAMES[q] + 
                                        "] suppressed but tracker holds lock");
                            }
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        
        // --- Diagnostic: Log per-quadrant pipeline results every time motion is detected ---
        // This shows exactly what the pipeline saw and why it did/didn't trigger.
        if (anyMotion || filterDebugEnabled) {
            String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
            int bestQ = pipelineV2.getHighestThreatQuadrant();
            
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                MotionPipelineV2.QuadrantResult r = results[q];
                if (r.activeBlocks == 0 && !r.brightnessSuppressed) continue;
                
                String qName = MotionPipelineV2.QUADRANT_NAMES[q];
                
                // Convert centroid block coords to estimated distance in meters
                float estDistM = estimateDistanceFromCentroid(q, r.centroidY);
                String distStr = (r.componentSize > 0) ? String.format("~%.1fm", estDistM) : "n/a";
                
                // Zone cutoff in human terms
                int maxRow = pipelineV2Config != null ? pipelineV2Config.maxDistanceRow : 0;
                float zoneCutoffDist = estimateDistanceFromCentroid(q, maxRow);
                String zoneStr = config != null ? config.getDetectionZone() : "?";
                String zoneLimitStr = maxRow > 0 ? String.format("%s(<%s ~%.1fm)", zoneStr, 
                        maxRow == 4 ? "close" : maxRow == 2 ? "normal" : "extended", zoneCutoffDist) 
                        : zoneStr + "(no limit)";
                
                if (r.brightnessSuppressed) {
                    logger.debug(String.format(
                        "  [%s] BRIGHTNESS_SUPPRESSED luma=%.0f (light change detected)",
                        qName, r.meanLuma));
                } else if (r.shadowFiltered && !r.motionDetected) {
                    logger.debug(String.format(
                        "  [%s] SHADOW_FILTERED active=%d (shadow discrimination removed blocks)",
                        qName, r.activeBlocks));
                } else if (r.motionDetected) {
                    logger.info(String.format(
                        "  [%s] %s | dist=%s | blocks: active=%d confirmed=%d component=%d | zone=%s",
                        qName, threatNames[r.threatLevel], distStr,
                        r.activeBlocks, r.confirmedBlocks, r.componentSize, zoneLimitStr));
                } else if (r.activeBlocks > 0) {
                    // Motion was detected at block level but rejected by later stages
                    String reason;
                    if (r.confirmedBlocks == 0) {
                        reason = "not yet confirmed (need more frames)";
                    } else if (r.componentSize < (pipelineV2Config != null ? pipelineV2Config.minComponentSize : 1)) {
                        reason = String.format("component too small (%d blocks, need %d)", r.componentSize,
                                pipelineV2Config != null ? pipelineV2Config.minComponentSize : 1);
                    } else if (maxRow > 0 && r.centroidY < maxRow) {
                        reason = String.format("too far away (%s, zone limit ~%.1fm)", distStr, zoneCutoffDist);
                    } else if (r.confirmedBlocks < (pipelineV2Config != null ? pipelineV2Config.alarmBlockThreshold : 2)) {
                        reason = String.format("below alarm threshold (%d blocks, need %d)", r.confirmedBlocks,
                                pipelineV2Config != null ? pipelineV2Config.alarmBlockThreshold : 2);
                    } else {
                        reason = "passing motion (" + threatNames[r.threatLevel] + ", ignored)";
                    }
                    logger.debug(String.format(
                        "  [%s] REJECTED: %s | dist=%s active=%d confirmed=%d",
                        qName, reason, distStr, r.activeBlocks, r.confirmedBlocks));
                }
            }
        }
        
        // Update legacy tracking variables for compatibility
        if (anyMotion) {
            int bestQ = pipelineV2.getHighestThreatQuadrant();
            if (bestQ >= 0) {
                lastActiveBlocksCount = results[bestQ].activeBlocks;
                lastTemporalBlocksCount = results[bestQ].confirmedBlocks;
            }
        }
        
        if (anyMotion) {
            lastMotionTime = now;
            
            // Track peak threat across the entire motion sequence
            if (maxThreat > peakThreatDuringSequence) {
                peakThreatDuringSequence = maxThreat;
            }
            
            // Log motion to timeline — ALWAYS, even before recording starts.
            // The timeline collector's pre-trigger ring buffer captures events during
            // the approach phase. When recording triggers, these are flushed into the
            // active span array with timestamps aligned to the video's pre-record window.
            timelineCollector.onMotionDetected(lastActiveBlocksCount, pipelineV2.getActiveQuadrantMask());
            
            if (firstMotionTime == 0) {
                firstMotionTime = now;
                peakThreatDuringSequence = maxThreat;
                int bestQ = pipelineV2.getHighestThreatQuadrant();
                MotionPipelineV2.QuadrantResult bestR = bestQ >= 0 ? results[bestQ] : null;
                float estDist = bestQ >= 0 && bestR != null ? estimateDistanceFromCentroid(bestQ, bestR.centroidY) : -1;
                String threatStr = maxThreat >= MotionPipelineV2.THREAT_HIGH ? "HIGH(loiter)" : "MEDIUM(approach)";
                long needed = maxThreat >= MotionPipelineV2.THREAT_HIGH ? SUSTAINED_MOTION_BASE_MS : loiteringTimeMs;
                logger.info(String.format("Motion started: %s camera, threat=%s, dist=~%.1fm, need %.1fs sustained...",
                        bestQ >= 0 ? MotionPipelineV2.QUADRANT_NAMES[bestQ] : "?",
                        threatStr, estDist, needed / 1000.0));
            }
            
            long motionDuration = now - firstMotionTime;
            
            // Use peak threat for duration requirement (not just current frame).
            // This prevents a brief MEDIUM→NONE→MEDIUM flicker from resetting the clock.
            int effectiveThreat = peakThreatDuringSequence;
            
            // Determine required sustained motion based on threat level:
            // - THREAT_HIGH (loitering): Only need base delay (500ms).
            // - THREAT_MEDIUM (approaching): Require loitering time setting.
            long requiredDuration = (effectiveThreat >= MotionPipelineV2.THREAT_HIGH)
                    ? SUSTAINED_MOTION_BASE_MS
                    : loiteringTimeMs;
            
            // --- Diagnostic: Log sustained motion progress ---
            if (motionDuration > 0 && motionDuration < requiredDuration) {
                // Log every second while waiting
                if (motionDuration % 1000 < MOTION_PROCESS_INTERVAL_MS) {
                    String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
                    int bestQ = pipelineV2.getHighestThreatQuadrant();
                    MotionPipelineV2.QuadrantResult bestR = bestQ >= 0 ? results[bestQ] : null;
                    float estDist = bestQ >= 0 && bestR != null ? estimateDistanceFromCentroid(bestQ, bestR.centroidY) : -1;
                    logger.info(String.format("Motion building: %.1fs / %.1fs | threat=%s | dist=~%.1fm | loiterSetting=%ds",
                            motionDuration / 1000.0, requiredDuration / 1000.0,
                            threatNames[maxThreat], estDist, (int)(loiteringTimeMs / 1000)));
                }
            }
            
            // FIX: Early AI initialization — queue YOLO on active quadrants as soon as
            // motion is detected, not after the loitering timer expires. This lets the
            // EventTimelineCollector build contextual history (person vs car vs bike)
            // BEFORE the MP4 write is triggered. Without this, if someone leaves the
            // frame right at the trigger threshold, YOLO runs on an empty frame and the
            // JSON sidecar records a generic "motion" event instead of classifying it.
            if (useObjectDetection && !isAiRunning.get() && aiQuadrantQueue.isEmpty()) {
                int bestQ = pipelineV2.getHighestThreatQuadrant();
                if (bestQ >= 0) aiQuadrantQueue.add(bestQ);
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    if (q != bestQ && results[q].motionDetected) {
                        aiQuadrantQueue.add(q);
                    }
                }
                // Kick off AI immediately if cooldown allows
                if (!aiQuadrantQueue.isEmpty() && (System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                    runAiOnQuadrant(smallRgbFrame, aiQuadrantQueue.poll());
                }
            }
            
            if (motionDuration >= requiredDuration) {
                inActiveMode = true;
                
                // Filter debug log
                if (filterDebugEnabled) {
                    int bestQ = pipelineV2.getHighestThreatQuadrant();
                    String qName = bestQ >= 0 ? MotionPipelineV2.QUADRANT_NAMES[bestQ] : "?";
                    String[] threatNames = {"NONE", "LOW", "MEDIUM", "HIGH"};
                    MotionPipelineV2.QuadrantResult r = bestQ >= 0 ? results[bestQ] : null;
                    float estDist = bestQ >= 0 && r != null ? estimateDistanceFromCentroid(bestQ, r.centroidY) : -1;
                    addFilterLogEntry(String.format("[%s] TRIGGER: %s threat=%s dist=~%.1fm active=%d confirmed=%d component=%d sustained=%.1fs",
                            new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now)),
                            qName, threatNames[maxThreat], estDist,
                            r != null ? r.activeBlocks : 0, r != null ? r.confirmedBlocks : 0,
                            r != null ? r.componentSize : 0, motionDuration / 1000.0));
                }
                
                if (!recording) {
                    // SOTA: Event stitching — if new motion appears shortly after the last
                    // recording stopped, start a new recording immediately. The previous
                    // recentlyStoppedRecording cooldown blocked new recordings for the entire
                    // postRecordMs window after a stop, causing missed events when someone
                    // lingered near the car. The 3-second sustained motion requirement already
                    // prevents rapid-fire false triggers, so the cooldown is unnecessary.
                    {
                        motionDetections++;
                        int bestQ = pipelineV2.getHighestThreatQuadrant();
                        // If no quadrant has motion (e.g., tracker held through flash),
                        // fall back to the quadrant with an active tracker lock
                        if (bestQ < 0) {
                            for (int tq = 0; tq < MotionPipelineV2.NUM_QUADRANTS; tq++) {
                                try {
                                    if (NativeMotion.trackerHasActiveTrack(tq)) {
                                        bestQ = tq;
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        String qName = bestQ >= 0 ? MotionPipelineV2.QUADRANT_NAMES[bestQ] : "?";
                        String triggerSource = (pipelineV2.getMaxThreatLevel() >= MotionPipelineV2.THREAT_MEDIUM) 
                                ? "motion" : "tracker";
                        String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
                        MotionPipelineV2.QuadrantResult bestResult = bestQ >= 0 ? results[bestQ] : null;
                        
                        // Estimate distance from centroid position
                        float estDist = bestQ >= 0 && bestResult != null ? 
                                estimateDistanceFromCentroid(bestQ, bestResult.centroidY) : -1;
                        String distStr = estDist > 0 ? String.format("~%.1fm", estDist) : "unknown";
                        
                        String detectionZone = config != null ? config.getDetectionZone() : "?";
                        int sensitivityLevel = config != null ? config.getSensitivityLevel() : -1;
                        int loiteringSec = config != null ? config.getLoiteringTimeSeconds() : -1;
                        int maxRow = pipelineV2Config != null ? pipelineV2Config.maxDistanceRow : 0;
                        float zoneLimitDist = bestQ >= 0 ? estimateDistanceFromCentroid(bestQ, maxRow) : -1;
                        
                        logger.info(String.format(
                            ">>> RECORDING TRIGGERED <<<\n" +
                            "  Camera: %s | Threat: %s | Distance: %s | Sustained: %.1fs | Source: %s\n" +
                            "  Blocks: active=%d, confirmed=%d, component=%d\n" +
                            "  Settings: sensitivity=%d, zone=%s (limit %s), loiterTime=%ds\n" +
                            "  Why: threat %s >= MEDIUM ✓, duration %.1fs >= %.1fs ✓, distance %s within zone ✓",
                            qName, threatNames[maxThreat], distStr, motionDuration / 1000.0, triggerSource,
                            bestResult != null ? bestResult.activeBlocks : 0,
                            bestResult != null ? bestResult.confirmedBlocks : 0,
                            bestResult != null ? bestResult.componentSize : 0,
                            sensitivityLevel, detectionZone,
                            maxRow > 0 ? String.format("~%.1fm", zoneLimitDist) : "none",
                            loiteringSec,
                            threatNames[maxThreat], motionDuration / 1000.0, requiredDuration / 1000.0,
                            distStr));
                        
                        recordingStopTime = now + postRecordMs;
                        startRecording();
                        
                        try {
                            String videoFilename = currentEventFile != null ? currentEventFile.getName() : null;
                            TelegramNotifier.notifyMotion("motion", 1.0f, videoFilename);
                        } catch (Exception e) {
                            logger.warn("Failed to send motion notification: " + e.getMessage());
                        }
                    }
                } else {
                    // Already recording — extend recording timer on continued motion.
                    // Any quadrant with MEDIUM+ threat extends the recording.
                    long newStopTime = now + postRecordMs;
                    if (newStopTime > recordingStopTime) {
                        recordingStopTime = newStopTime;
                    }
                    
                    // Also run YOLO on new quadrants that have motion (even if different from original)
                    if (useObjectDetection && !isAiRunning.get()) {
                        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                            if (results[q].motionDetected && results[q].threatLevel >= MotionPipelineV2.THREAT_MEDIUM) {
                                if (!aiQuadrantQueue.contains(q)) {
                                    aiQuadrantQueue.add(q);
                                }
                            }
                        }
                        // FIX: Check cooldown before consuming queue item
                        if (!aiQuadrantQueue.isEmpty() && (System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                            runAiOnQuadrant(smallRgbFrame, aiQuadrantQueue.poll());
                        }
                    }
                }
                
                // Staggered YOLO: queue active quadrants for AI detection
                if (useObjectDetection && !isAiRunning.get()) {
                    aiQuadrantQueue.clear();
                    // Add quadrants sorted by threat level (highest first)
                    int bestQ = pipelineV2.getHighestThreatQuadrant();
                    if (bestQ >= 0) aiQuadrantQueue.add(bestQ);
                    for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                        if (q != bestQ && results[q].motionDetected) {
                            aiQuadrantQueue.add(q);
                        }
                    }
                    // FIX: Check cooldown before consuming queue item
                    if (!aiQuadrantQueue.isEmpty() && (System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                        runAiOnQuadrant(smallRgbFrame, aiQuadrantQueue.poll());
                    }
                }
            }
        } else {
            // No motion detected on this frame (all quadrants below MEDIUM threat).
            // Don't immediately end the sequence — allow gaps up to 2 seconds.
            // A person walking past creates motion bursts with brief gaps as they
            // move between quadrants or between block boundaries. A 500ms gap was
            // too tight and caused the sequence to reset before reaching the trigger.
            if (!recording) {
                long timeSinceLastMotion = now - lastMotionTime;
                
                // SOTA: Extend gap tolerance during cross-quadrant transit.
                // When a person walks from the left camera to the rear camera, there's
                // a brief gap where neither camera has MEDIUM+ threat (left is decaying,
                // rear hasn't confirmed yet). Without this fix, the 2-second timeout
                // resets firstMotionTime, and the rear camera starts a fresh sequence
                // from zero — the person's total approach time is never accumulated.
                //
                // If the texture tracker has an active track, we know an object is still
                // physically present. Extend the gap tolerance to 4 seconds to bridge
                // the cross-quadrant handoff. Also check for any quadrant with active
                // blocks (even below MEDIUM threat) as a secondary signal.
                //
                // FIX: Also extend tolerance if YOLO is currently running or queued.
                // The tracker is started inside the async YOLO lambda. If motion drops
                // before the lambda executes, trackerHasActiveTrack returns false even
                // though YOLO is about to classify the person and start a track.
                boolean trackerActive = false;
                boolean anyLowActivity = false;
                boolean aiPending = isAiRunning.get() || !aiQuadrantQueue.isEmpty();
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(q)) trackerActive = true;
                    } catch (Exception ignored) {}
                    if (results[q].activeBlocks > 0) anyLowActivity = true;
                }
                long gapTolerance = (trackerActive || anyLowActivity || aiPending) ? 4000 : 2000;
                
                if (firstMotionTime != 0 && timeSinceLastMotion > gapTolerance) {
                    // Motion sequence ended without triggering
                    long motionDuration = lastMotionTime - firstMotionTime;
                    if (motionDuration > 200) {
                        String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
                        long requiredMs = (peakThreatDuringSequence >= MotionPipelineV2.THREAT_HIGH)
                                ? SUSTAINED_MOTION_BASE_MS : loiteringTimeMs;
                        logger.info(String.format("Motion ended WITHOUT trigger: lasted=%.1fs, peakThreat=%s, required=%.1fs, gapTolerance=%.1fs%s",
                                motionDuration / 1000.0, threatNames[peakThreatDuringSequence],
                                requiredMs / 1000.0, gapTolerance / 1000.0,
                                trackerActive ? " (tracker was active)" : ""));
                    }
                    firstMotionTime = 0;
                    peakThreatDuringSequence = 0;
                }
            }
        }
        
        // Post-record check: stop recording when no motion for postRecordMs.
        // SOTA: Also check ANY quadrant for activity (not just MEDIUM+ threat).
        // A person standing still near the car produces minimal block changes but
        // is still a valid reason to keep recording. Use a lower threshold:
        // any quadrant with confirmedBlocks > 0 counts as "activity" for post-record.
        if (recording && now >= recordingStopTime && recordingStopTime > 0) {
            // Check if any quadrant has residual activity (even below MEDIUM threat)
            boolean anyActivity = false;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (results[q].confirmedBlocks > 0 || results[q].activeBlocks > 0) {
                    anyActivity = true;
                    break;
                }
            }
            
            // SOTA: Also check texture tracker — a person standing still produces
            // zero motion blocks but the NCC tracker holds a lock on their pixel texture.
            // This is the "Static Foreground Victory" — recording stays alive as long as
            // the tracked object is present, even with zero motion pipeline activity.
            boolean trackerHolding = false;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                try {
                    if (NativeMotion.trackerHasActiveTrack(q)) {
                        trackerHolding = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            
            if (anyActivity || trackerHolding) {
                // Still some activity or tracker holding — extend recording
                recordingStopTime = now + postRecordMs;
                if (trackerHolding && !anyActivity && frameCount % 100 == 0) {
                    logger.info("Post-record extended by texture tracker (no motion, object still present)");
                }
            } else {
                long timeSinceLastMotion = now - lastMotionTime;
                if (timeSinceLastMotion >= postRecordMs) {
                    logger.info(String.format("V2 post-record complete — stopping (no motion for %.1fs)",
                            timeSinceLastMotion / 1000.0));
                    stopRecording();
                    recordingStopTime = 0;
                    firstMotionTime = 0;
                    peakThreatDuringSequence = 0;
                }
            }
        }
        
        // SOTA: Update texture tracker on every frame (runs NCC template matching).
        // This is the core of the decoupled tracking — YOLO sleeps, NCC tracks.
        // Also handles YOLO heartbeat: when NCC confidence drops below 0.60 or
        // 3 seconds have elapsed, the tracker requests YOLO re-verification.
        if (recording) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                try {
                    if (NativeMotion.trackerHasActiveTrack(q)) {
                        // Feed the quadrant crop to the tracker
                        int qW = THUMBNAIL_WIDTH / 2;
                        int qH = THUMBNAIL_HEIGHT / 2;
                        byte[] quadCrop = cropFromMosaic(smallRgbFrame, q, qW, qH);
                        if (quadCrop != null) {
                            NativeMotion.trackerUpdate(quadCrop, qW, qH, q, now);
                        }
                        
                        // Check if tracker wants YOLO heartbeat (NCC score dropped or timer expired).
                        // FIX: Enforce a hard 2-second cooldown per quadrant to prevent heartbeat spam.
                        // Without this, a failing NCC tracker fires needsYoloHeartbeat=true on every
                        // single frame, turning YOLO into a 10 FPS continuous detector and destroying
                        // the battery savings of the decoupled architecture.
                        if (NativeMotion.trackerNeedsYoloHeartbeat(q)) {
                            long timeSinceLastHeartbeat = now - lastHeartbeatTimeMs[q];
                            if (timeSinceLastHeartbeat >= HEARTBEAT_COOLDOWN_MS
                                    && useObjectDetection && !isAiRunning.get() && !aiQuadrantQueue.contains(q)) {
                                aiQuadrantQueue.add(q);
                                lastHeartbeatTimeMs[q] = now;
                                logger.info("Tracker heartbeat: waking YOLO for Q" + q + 
                                        " [" + MotionPipelineV2.QUADRANT_NAMES[q] + "]");
                            }
                        }
                    }
                } catch (Exception e) {
                    // Tracker not available — continue without it
                }
            }
        }
        
        // Process staggered YOLO queue (one per frame)
        // FIX: Check cooldown BEFORE polling the queue. Previously, poll() consumed
        // the quadrant, then runAiOnQuadrant's internal cooldown check rejected it —
        // permanently vaporizing that quadrant's AI pass.
        if (useObjectDetection && !isAiRunning.get() && !aiQuadrantQueue.isEmpty()) {
            if ((System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                runAiOnQuadrant(smallRgbFrame, aiQuadrantQueue.poll());
            }
        }
        
        // Periodic stats
        if (frameCount % 500 == 0) {
            logger.info(String.format("V2 stats: frames=%d, motions=%d, recording=%b",
                    frameCount, motionDetections, recording));
            // Log per-quadrant status for debugging
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                MotionPipelineV2.QuadrantResult r = results[q];
                String status = r.brightnessSuppressed ? "SUPPRESSED" : 
                    (r.motionDetected ? "MOTION(t=" + r.threatLevel + ")" : "quiet");
                logger.info(String.format("  Q%d[%s]: %s active=%d confirmed=%d component=%d luma=%.0f",
                        q, MotionPipelineV2.QUADRANT_NAMES[q], status,
                        r.activeBlocks, r.confirmedBlocks, r.componentSize, r.meanLuma));
            }
            
            // SOTA: Auto day/night mode switch based on ambient light.
            // The BYD's camera ISP boosts ISO at night, pushing mean luma to ~75-85.
            // Daytime luma sits at ~115-130. The threshold of 95 cleanly splits the two.
            // Night mode relaxes edge/shadow thresholds to handle ISO noise and headlights.
            float avgLuma = 0;
            int lumaCount = 0;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (results[q].meanLuma > 0) {
                    avgLuma += results[q].meanLuma;
                    lumaCount++;
                }
            }
            if (lumaCount > 0) {
                avgLuma /= lumaCount;
                
                // Java-level auto-exposure: simple day/night switch for global params.
                // Threshold scaling is handled per-quadrant in C++ with relative multipliers.
                boolean shouldBeNight = avgLuma < 90.0f;
                boolean currentlyNight = isNightMode();
                
                if (shouldBeNight != currentlyNight && pipelineV2Config != null) {
                    // Restore base global params from user's preset
                    String preset = config != null ? config.getEnvironmentPreset() : "outdoor";
                    MotionPipelineV2.Config tempCfg = new MotionPipelineV2.Config();
                    tempCfg.applyEnvironmentPreset(preset);
                    
                    pipelineV2Config.brightnessShiftThreshold = tempCfg.brightnessShiftThreshold;
                    pipelineV2Config.brightnessSuppressionFrames = tempCfg.brightnessSuppressionFrames;
                    pipelineV2Config.shadowFilterMode = tempCfg.shadowFilterMode;
                    pipelineV2Config.chromaRatioTolerance = tempCfg.chromaRatioTolerance;
                    pipelineV2Config.shadowPixelFraction = tempCfg.shadowPixelFraction;
                    pipelineV2Config.oscillationThreshold = tempCfg.oscillationThreshold;
                    
                    if (shouldBeNight) {
                        pipelineV2Config.brightnessShiftThreshold = 0.35f;
                        pipelineV2Config.brightnessSuppressionFrames = 8;
                        pipelineV2Config.shadowFilterMode = 1;  // LIGHT
                        pipelineV2Config.chromaRatioTolerance = 0.25f;
                        pipelineV2Config.shadowPixelFraction = 0.7f;
                        pipelineV2Config.oscillationThreshold = 4;
                        setNightMode(true);
                        logger.info(String.format("Auto NIGHT mode (avgLuma=%.0f < 95)", avgLuma));
                    } else {
                        setNightMode(false);
                        logger.info(String.format("Auto NORMAL mode (avgLuma=%.0f >= 95)", avgLuma));
                    }
                    pipelineV2.applyConfig(pipelineV2Config);
                }
            }
            
            // Log suppressed quadrants for debug
            if (filterDebugEnabled) {
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    if (results[q].brightnessSuppressed) {
                        addFilterLogEntry(String.format("[%s] SUPPRESSED: %s (brightness shift, luma=%.0f)",
                                new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now)),
                                MotionPipelineV2.QUADRANT_NAMES[q], results[q].meanLuma));
                    }
                }
            }
        }
    }
    
    /**
     * Run YOLO on a single quadrant (cropped from the mosaic).
     */
    private void runAiOnQuadrant(byte[] mosaicRgb, int quadrant) {
        if (!useObjectDetection || yoloDetector == null) return;
        if (isAiRunning.get()) return;
        
        long now = System.currentTimeMillis();
        if ((now - lastAiTimeMs) < AI_COOLDOWN_MS) return;
        lastAiTimeMs = now;
        
        // Determine crop dimensions and data source.
        // If foveated cropper is available, extract a 640×640 window from the raw
        // 5120×960 strip centered on the motion centroid. This gives YOLO ~4× more
        // pixels per object compared to the 320×240 mosaic quadrant.
        final int qW;
        final int qH;
        final byte[] cropData;
        
        MotionPipelineV2.QuadrantResult motionResult = pipelineV2 != null ? pipelineV2.getResults()[quadrant] : null;
        
        // For heartbeat runs, the person may be stationary (zero motion blocks).
        // Use the tracker's last known position as the centroid for foveated crop
        // instead of requiring active motion blocks from the V2 pipeline.
        boolean heartbeatHasTrackerPos = false;
        float trackerCentroidX = 0, trackerCentroidY = 0;
        try {
            float[] trackBox = NativeMotion.trackerGetTrackBox(quadrant);
            if (trackBox != null && trackBox[6] > 0) {  // trackBox[6] = active flag
                // Convert tracker bbox (pixel coords) to block coords for foveated crop
                trackerCentroidX = (trackBox[0] + trackBox[2] / 2.0f) / 32.0f;
                trackerCentroidY = (trackBox[1] + trackBox[3] / 2.0f) / 32.0f;
                heartbeatHasTrackerPos = true;
            }
        } catch (Exception ignored) {}
        
        if (foveatedCropper != null && foveatedCropper.isInitialized() && cameraTextureId >= 0
                && ((motionResult != null && motionResult.componentSize > 0) || heartbeatHasTrackerPos)) {
            // Foveated path: 640×640 from raw strip (called on GL thread — safe)
            // Use motion centroid if available, otherwise fall back to tracker position
            float centroidX = (motionResult != null && motionResult.componentSize > 0)
                    ? motionResult.centroidX : trackerCentroidX;
            float centroidY = (motionResult != null && motionResult.componentSize > 0)
                    ? motionResult.centroidY : trackerCentroidY;
            byte[] foveatedRgb = foveatedCropper.crop(cameraTextureId, quadrant, centroidX, centroidY);
            if (foveatedRgb != null) {
                qW = FoveatedCropper.CROP_SIZE;
                qH = FoveatedCropper.CROP_SIZE;
                // Must copy — foveatedCropper reuses its internal buffer
                cropData = new byte[foveatedRgb.length];
                System.arraycopy(foveatedRgb, 0, cropData, 0, foveatedRgb.length);
            } else {
                // Foveated crop failed — fall back to mosaic crop
                qW = THUMBNAIL_WIDTH / 2;
                qH = THUMBNAIL_HEIGHT / 2;
                cropData = cropFromMosaic(mosaicRgb, quadrant, qW, qH);
            }
        } else {
            // Legacy path: 320×240 from mosaic
            qW = THUMBNAIL_WIDTH / 2;
            qH = THUMBNAIL_HEIGHT / 2;
            cropData = cropFromMosaic(mosaicRgb, quadrant, qW, qH);
        }
        
        if (cropData == null) return;
        
        isAiRunning.set(true);
        final int qIdx = quadrant;
        
        // FIX: Snapshot block confidences on the main thread BEFORE dispatching to aiExecutor.
        // The live pipelineV2.getResults() array is mutated by the JNI backend on every frame.
        // By the time the aiExecutor thread runs (150-300ms later), the main loop has processed
        // 2-3 new frames. If the person briefly stopped, confirmedBlocks will be 0 on the new
        // frame, and a valid YOLO detection gets thrown away because it doesn't overlap with
        // the "current" empty motion mask. Deep-copy the confidence array now.
        final float[] blockConfSnapshot = new float[MotionPipelineV2.TOTAL_BLOCKS];
        final int snapshotConfirmedBlocks;
        if (pipelineV2 != null) {
            MotionPipelineV2.QuadrantResult snapResult = pipelineV2.getResults()[qIdx];
            System.arraycopy(snapResult.blockConfidence, 0, blockConfSnapshot, 0, MotionPipelineV2.TOTAL_BLOCKS);
            snapshotConfirmedBlocks = snapResult.confirmedBlocks;
        } else {
            snapshotConfirmedBlocks = 0;
        }
        
        final boolean usedFoveated = (qW == FoveatedCropper.CROP_SIZE);
        
        // Capture whether this YOLO run is a heartbeat verification BEFORE the lambda.
        // The C++ tracker's needsYoloVerification flag is mutated by trackerUpdate() on
        // every frame. By the time the aiExecutor lambda runs (100-200ms later), the flag
        // state may have changed, causing the lambda to confirm the wrong quadrant.
        boolean heartbeatCheck = false;
        try {
            heartbeatCheck = NativeMotion.trackerNeedsYoloHeartbeat(quadrant)
                    && NativeMotion.trackerHasActiveTrack(quadrant);
        } catch (Exception e) {
            // Tracker not available
        }
        final boolean isHeartbeatRun = heartbeatCheck;
        
        // Capture mosaic quadrant crop for the texture tracker (always 320×240).
        // The tracker needs the mosaic-scale image regardless of whether YOLO used foveated.
        final byte[] mosaicQuadCrop;
        {
            int mqW = THUMBNAIL_WIDTH / 2;
            int mqH = THUMBNAIL_HEIGHT / 2;
            byte[] tmp = cropFromMosaic(mosaicRgb, quadrant, mqW, mqH);
            if (tmp != null) {
                mosaicQuadCrop = new byte[tmp.length];
                System.arraycopy(tmp, 0, mosaicQuadCrop, 0, tmp.length);
            } else {
                mosaicQuadCrop = null;
            }
        }
        
        aiExecutor.execute(() -> {
            try {
                boolean detectPerson = true, detectCar = true, detectBike = true;
                if (classFilter != null && classFilter.length > 0) {
                    detectPerson = false; detectCar = false; detectBike = false;
                    for (int cls : classFilter) {
                        if (cls == 0) detectPerson = true;
                        if (cls == 2 || cls == 5 || cls == 7) detectCar = true;
                        if (cls == 1 || cls == 3) detectBike = true;
                    }
                }
                
                java.util.List<com.overdrive.app.ai.Detection> detections = yoloDetector.detect(
                        cropData, qW, qH, aiConfidence, detectPerson, detectCar, false, detectBike, minObjectSize);
                
                // Track how many motion-filtered detections we found (accessible outside the block
                // for the teardown gate that kills zombie tracks when YOLO returns empty)
                int motionFilteredCount = 0;
                
                if (detections != null && !detections.isEmpty()) {
                    // Filter detections: only keep objects that overlap with active motion blocks.
                    // A static parked car detected by YOLO should be ignored if no motion blocks
                    // overlap with it. Only the moving person (whose bounding box overlaps with
                    // active motion blocks) should be reported to the timeline.
                    // FIX: Use the snapshot taken on the main thread, NOT the live pipeline results.
                    // The live results have been mutated by 2-3 frames by now.
                    
                    java.util.List<com.overdrive.app.ai.Detection> motionFiltered = new java.util.ArrayList<>();
                    for (com.overdrive.app.ai.Detection det : detections) {
                        int classId = det.getClassId();
                        
                        // Respect user's class filter settings.
                        // Only keep detections for classes the user has enabled.
                        if (classFilter != null && classFilter.length > 0) {
                            boolean classAllowed = false;
                            for (int allowedCls : classFilter) {
                                if (classId == allowedCls) {
                                    classAllowed = true;
                                    break;
                                }
                            }
                            if (!classAllowed) continue;
                        } else {
                            // No filter set — only allow known relevant classes
                            if (classId != 0 && classId != 1 && classId != 2 && 
                                classId != 3 && classId != 5 && classId != 7) continue;
                        }
                        
                        // Check if detection overlaps with any confirmed motion blocks
                        // using the SNAPSHOT taken on the main thread.
                        // When using foveated crop (640×640), detection coords are in a different
                        // coordinate space than the block grid (320×240 with 32px blocks).
                        // Scale detection coords to match the block grid.
                        //
                        // FIX: During a heartbeat run, BYPASS the spatial filter entirely.
                        // The whole point of the heartbeat is to verify a STATIONARY person
                        // who has zero motion blocks. If we require motion-block overlap,
                        // the heartbeat will always fail for stationary objects, the teardown
                        // gate will kill the track, and the recording will stop even though
                        // the person is still standing right there.
                        // Spatial filter: check if detection overlaps with active motion blocks.
                        // Heartbeat bypass: persons (class 0) skip the spatial check because
                        // a stationary person has zero motion blocks but is a real threat.
                        // Vehicles during heartbeat still require motion blocks — a parked car
                        // is never a threat and will hold the recording open forever otherwise.
                        boolean passesFilter = false;
                        
                        if (isHeartbeatRun && classId == 0) {
                            // Heartbeat + person: bypass spatial filter
                            passesFilter = true;
                        } else if (snapshotConfirmedBlocks > 0) {
                            // Normal path: require overlap with active motion blocks
                            float scaleX = usedFoveated ? (320.0f / FoveatedCropper.CROP_SIZE) : 1.0f;
                            float scaleY = usedFoveated ? (240.0f / FoveatedCropper.CROP_SIZE) : 1.0f;
                            int detLeft = (int)(det.getX() * scaleX);
                            int detTop = (int)(det.getY() * scaleY);
                            int detRight = (int)((det.getX() + det.getW()) * scaleX);
                            int detBottom = (int)((det.getY() + det.getH()) * scaleY);
                            
                            for (int bi = 0; bi < MotionPipelineV2.TOTAL_BLOCKS; bi++) {
                                if (blockConfSnapshot[bi] < 0.5f) continue;
                                
                                int bx = (bi % MotionPipelineV2.GRID_COLS) * 32;
                                int by = (bi / MotionPipelineV2.GRID_COLS) * 32;
                                int bRight = bx + 32;
                                int bBottom = by + 32;
                                
                                if (detLeft < bRight && detRight > bx && detTop < bBottom && detBottom > by) {
                                    passesFilter = true;
                                    break;
                                }
                            }
                        } else {
                            // No motion data available — keep all detections (fallback)
                            passesFilter = true;
                        }
                        
                        if (passesFilter) {
                            motionFiltered.add(det);
                        }
                    }
                    
                    int relevantCount = motionFiltered.size();
                    motionFilteredCount = relevantCount;
                    
                    if (relevantCount > 0) {
                        long timeSinceMotion = System.currentTimeMillis() - lastMotionTime;
                        if (timeSinceMotion < 2000) {
                            lastMotionTime = System.currentTimeMillis();
                        }
                        
                        boolean hasActiveMotion = timeSinceMotion < 2000;
                        // Always send to timeline — pre-trigger ring buffer captures
                        // events before recording starts for the JSON sidecar.
                        timelineCollector.onAiDetection(motionFiltered, hasActiveMotion, 1 << qIdx);
                        
                        // Cross-quadrant tracking: assign persistent track IDs.
                        // The tracker expects bounding boxes in 320×240 quadrant pixel space.
                        // When using foveated crop (640×640), YOLO returns coords in 640×640
                        // space. We must translate them back to 320×240 before tracking,
                        // otherwise a 10px real-world movement looks like 80px to the tracker
                        // and breaks the dist < 120 centroid matching threshold.
                        java.util.List<com.overdrive.app.ai.Detection> trackableDetections;
                        if (usedFoveated) {
                            trackableDetections = new java.util.ArrayList<>(motionFiltered.size());
                            float scaleToQuad = 320.0f / FoveatedCropper.CROP_SIZE;  // 0.5
                            for (com.overdrive.app.ai.Detection det : motionFiltered) {
                                trackableDetections.add(new com.overdrive.app.ai.Detection(
                                        det.getClassId(),
                                        det.getConfidence(),
                                        (int)(det.getX() * scaleToQuad),
                                        (int)(det.getY() * scaleToQuad),
                                        (int)(det.getW() * scaleToQuad),
                                        (int)(det.getH() * scaleToQuad)
                                ));
                            }
                        } else {
                            trackableDetections = motionFiltered;
                        }
                        
                        java.util.List<CrossQuadrantTracker.TrackResult> tracked =
                                crossQuadrantTracker.processDetections(trackableDetections, qIdx);
                        
                        // SOTA: Start/refresh texture tracker on the highest-confidence detection.
                        // YOLO's job is done — the NCC tracker takes over frame-by-frame
                        // tracking. YOLO only wakes up again on heartbeat or NCC score drop.
                        if (!trackableDetections.isEmpty() && mosaicQuadCrop != null) {
                            com.overdrive.app.ai.Detection best = trackableDetections.get(0);
                            for (com.overdrive.app.ai.Detection d : trackableDetections) {
                                if (d.getConfidence() > best.getConfidence()) best = d;
                            }
                            try {
                                // FIX: Use the pre-captured isHeartbeatRun flag, NOT a live
                                // call to trackerNeedsYoloHeartbeat(). The live flag is mutated
                                // by trackerUpdate() on the main thread between when we queued
                                // this quadrant and when the lambda executes (100-200ms later).
                                if (isHeartbeatRun) {
                                    // SEMANTIC LOCK: The track was born with a specific classId.
                                    // If YOLO now sees a different class, the tracker has morphed
                                    // onto a background object (e.g., person → parked car).
                                    // Reject the heartbeat and let the track die.
                                    float[] trackBox = NativeMotion.trackerGetTrackBox(qIdx);
                                    int trackClassId = (trackBox != null) ? (int) trackBox[5] : -1;
                                    
                                    if (trackClassId >= 0 && best.getClassId() != trackClassId) {
                                        // Semantic mismatch — tracker morphed onto a different object
                                        logger.info("Semantic mismatch: track Q" + qIdx + 
                                                " born as class " + trackClassId + 
                                                " but YOLO sees class " + best.getClassId() + 
                                                " — killing track");
                                        NativeMotion.trackerDropTrack(qIdx);
                                        NativeMotion.trackerConfirmHeartbeat(qIdx, System.currentTimeMillis());
                                    } else {
                                        // Class matches — refresh the template
                                        NativeMotion.trackerRefreshTemplate(
                                                mosaicQuadCrop, THUMBNAIL_WIDTH / 2, THUMBNAIL_HEIGHT / 2,
                                                qIdx,
                                                best.getX(), best.getY(), best.getW(), best.getH(),
                                                System.currentTimeMillis());
                                        NativeMotion.trackerConfirmHeartbeat(qIdx, System.currentTimeMillis());
                                        logger.info("Tracker heartbeat confirmed: refreshed template for Q" + qIdx +
                                                " [" + MotionPipelineV2.QUADRANT_NAMES[qIdx] + "]");
                                    }
                                } else {
                                    // First detection: start a new track
                                    NativeMotion.trackerStartTrack(
                                            mosaicQuadCrop, THUMBNAIL_WIDTH / 2, THUMBNAIL_HEIGHT / 2,
                                            qIdx, best.getClassId(),
                                            best.getX(), best.getY(), best.getW(), best.getH(),
                                            System.currentTimeMillis());
                                }
                            } catch (Exception e) {
                                logger.warn("Tracker start/refresh failed: " + e.getMessage());
                            }
                        } else if (trackableDetections.isEmpty() && isHeartbeatRun) {
                            // (Moved to teardown gate outside the detections block)
                        }
                        
                        String qName = MotionPipelineV2.QUADRANT_NAMES[qIdx];
                        String cropMode = usedFoveated ? "foveated 640×640" : "mosaic 320×240";
                        logger.info(String.format("V2 AI [%s] (%s): %d objects (motion-filtered from %d), %d tracks",
                                qName, cropMode, relevantCount, detections.size(),
                                crossQuadrantTracker.getActiveTrackCount()));
                    }
                }
                
                // TEARDOWN GATE: When YOLO returns 0 objects during a heartbeat,
                // the object has left the scene. Kill the zombie track immediately.
                // Previously this was nested inside the `if (!detections.isEmpty())` block,
                // so it never executed when YOLO returned empty — the track stayed alive
                // forever, spamming heartbeats on every frame.
                if (isHeartbeatRun && (detections == null || detections.isEmpty()
                        || motionFilteredCount == 0)) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(qIdx)) {
                            NativeMotion.trackerDropTrack(qIdx);
                            NativeMotion.trackerConfirmHeartbeat(qIdx, System.currentTimeMillis());
                            logger.info("Tracker teardown: YOLO heartbeat found nothing, killed track Q" + qIdx +
                                    " [" + MotionPipelineV2.QUADRANT_NAMES[qIdx] + "]");
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                logger.error("V2 AI detection error (Q" + qIdx + ")", e);
            } finally {
                isAiRunning.set(false);
            }
        });
    }
    
    /**
     * Crop a quadrant from the 640×480 mosaic into the reusable aiBuffer.
     * Legacy path used when foveated cropper is not available.
     */
    private byte[] cropFromMosaic(byte[] mosaicRgb, int quadrant, int qW, int qH) {
        int startX = (quadrant % 2) * qW;
        int startY = (quadrant / 2) * qH;
        
        int cropSize = qW * qH * BYTES_PER_PIXEL;
        if (aiBuffer == null || aiBuffer.length != cropSize) {
            aiBuffer = new byte[cropSize];
        }
        
        for (int y = 0; y < qH; y++) {
            int srcOffset = ((startY + y) * THUMBNAIL_WIDTH + startX) * BYTES_PER_PIXEL;
            int dstOffset = y * qW * BYTES_PER_PIXEL;
            System.arraycopy(mosaicRgb, srcOffset, aiBuffer, dstOffset, qW * BYTES_PER_PIXEL);
        }
        return aiBuffer;
    }
    
    /**
     * Sets the Region of Interest (ROI) mask for motion detection.
     * 
     * @param mask Byte array (320×240) where 1 = check motion, 0 = ignore
     *             Pass null to use entire frame (default)
     */
    public void setRoiMask(byte[] mask) {
        if (mask != null && mask.length != THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT) {
            logger.error("Invalid ROI mask size: " + mask.length + 
                       " (expected " + (THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT) + ")");
            return;
        }
        
        this.roiMask = mask;
        
        // Count pixels in ROI for normalization
        if (mask != null) {
            roiPixelCount = 0;
            for (byte b : mask) {
                if (b != 0) roiPixelCount++;
            }
            logger.info("ROI mask set: " + roiPixelCount + " pixels (" + 
                      (roiPixelCount * 100 / (THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT)) + "%)");
        } else {
            roiPixelCount = THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT;
            logger.info("ROI mask cleared (using full frame)");
        }
    }
    
    /**
     * Sets ROI from polygon points (normalized 0.0-1.0 coordinates).
     * 
     * @param points Array of [x, y] pairs defining polygon vertices
     */
    public void setRoiFromPolygon(float[][] points) {
        if (points == null || points.length < 3) {
            setRoiMask(null);  // Clear ROI
            return;
        }
        
        // Create mask by rasterizing polygon
        byte[] mask = new byte[THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT];
        
        for (int y = 0; y < THUMBNAIL_HEIGHT; y++) {
            for (int x = 0; x < THUMBNAIL_WIDTH; x++) {
                float nx = (float) x / THUMBNAIL_WIDTH;
                float ny = (float) y / THUMBNAIL_HEIGHT;
                
                // Point-in-polygon test (ray casting algorithm)
                if (isPointInPolygon(nx, ny, points)) {
                    mask[y * THUMBNAIL_WIDTH + x] = 1;
                }
            }
        }
        
        setRoiMask(mask);
    }
    
    /**
     * Point-in-polygon test using ray casting.
     */
    private boolean isPointInPolygon(float x, float y, float[][] polygon) {
        boolean inside = false;
        int n = polygon.length;
        
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = polygon[i][0], yi = polygon[i][1];
            float xj = polygon[j][0], yj = polygon[j][1];
            
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        
        return inside;
    }
    
    /**
     * Sets the SOTA surveillance configuration.
     * 
     * @param config Configuration object with distance preset, flash mode, and camera calibration
     */
    public void setConfig(SurveillanceConfig config) {
        this.config = config;
        
        // Sync legacy fields for backward compatibility
        this.flashImmunity = config.getFlashImmunity();
        this.requiredActiveBlocks = config.getRequiredBlocks();
        this.minObjectSize = config.getMinObjectSize();
        this.aiConfidence = config.getAiConfidence();
        this.preRecordMs = config.getPreRecordSeconds() * 1000L;
        this.postRecordMs = config.getPostRecordSeconds() * 1000L;
        
        // Sync loitering time for Java-side sustained motion enforcement
        this.loiteringTimeMs = config.getLoiteringTimeSeconds() * 1000L;
        
        // Update frame dimensions in config for distance estimation
        config.setResolution(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        config.setIsMosaic(true);  // We use 2x2 mosaic layout
        
        // Apply object detection filters from saved config.
        // This rebuilds the classFilter array so YOLO respects detectPerson/detectCar/detectBike.
        setObjectFilters(config.getMinObjectSize(), config.getAiConfidence(),
                config.isDetectPerson(), config.isDetectCar(), config.isDetectBike());
        
        // Apply V2 pipeline settings from loaded config.
        // Order matters: environment preset sets all defaults, then sensitivity and
        // detection zone override their specific parameters, then loitering and cameras.
        if (pipelineV2Config != null && pipelineV2 != null) {
            pipelineV2Config.applyEnvironmentPreset(config.getEnvironmentPreset());
            pipelineV2Config.applySensitivity(config.getSensitivityLevel());
            pipelineV2Config.applyDetectionZone(config.getDetectionZone());
            pipelineV2Config.loiteringFrames = config.getLoiteringTimeSeconds() * 10;
            // Apply saved shadow filter mode (after preset, so user override takes precedence)
            pipelineV2Config.shadowFilterMode = config.getShadowFilterMode();
            boolean[] cameras = config.getCameraEnabled();
            for (int i = 0; i < 4; i++) {
                pipelineV2Config.quadrantEnabled[i] = cameras[i];
            }
            pipelineV2.applyConfig(pipelineV2Config);
            logger.info(String.format("V2 pipeline config applied: env=%s, sens=%d, zone=%s, loiter=%ds, cameras=[%b,%b,%b,%b]",
                    config.getEnvironmentPreset(), config.getSensitivityLevel(), config.getDetectionZone(),
                    config.getLoiteringTimeSeconds(), cameras[0], cameras[1], cameras[2], cameras[3]));
        }
        
        // Apply filter debug setting
        this.filterDebugEnabled = config.isFilterDebugLogEnabled();
        
        logger.info("Config applied: " + config.toString());
    }
    
    /**
     * Gets the current SOTA configuration.
     * 
     * @return Current configuration
     */
    public SurveillanceConfig getConfig() {
        return config;
    }
    
    /**
     * Gets the last estimated distance to motion.
     * 
     * @return Distance in meters, or 0 if no motion detected
     */
    public float getLastEstimatedDistance() {
        return lastEstimatedDistance;
    }
    
    /**
     * Estimate real-world distance from a centroid Y position in block coordinates.
     * 
     * The centroid Y is in block coordinates (0 = top row / far, GRID_ROWS-1 = bottom / close).
     * We convert to pixel coordinates in the full mosaic, then use SurveillanceConfig's
     * camera calibration to estimate distance in meters.
     * 
     * @param quadrant Quadrant index (0=front, 1=right, 2=left, 3=rear)
     * @param centroidBlockY Centroid Y in block coordinates (0-6)
     * @return Estimated distance in meters, or -1 if unavailable
     */
    private float estimateDistanceFromCentroid(int quadrant, float centroidBlockY) {
        if (config == null) return -1;
        
        // Convert block Y to pixel Y within the quadrant
        // Block size is 32px, centroid is center of the block cluster
        float pixelY = centroidBlockY * GRID_BLOCK_SIZE + (GRID_BLOCK_SIZE / 2.0f);
        
        // Convert quadrant-local pixel Y to global mosaic Y
        // Mosaic layout: top row = quadrants 0,1; bottom row = quadrants 2,3
        int quadrantOffsetY = (quadrant >= 2) ? (THUMBNAIL_HEIGHT / 2) : 0;
        int globalY = quadrantOffsetY + (int) pixelY;
        
        return config.estimateDistance(globalY);
    }
    
    /**
     * Gets the last temporal blocks count (blocks with temporal consistency).
     * 
     * @return Number of temporally consistent blocks
     */
    public int getLastTemporalBlocksCount() {
        return lastTemporalBlocksCount;
    }
    
    /**
     * Gets the last motion bounding box Y coordinates.
     * 
     * @return int array [minY, maxY] or null if no motion
     */
    public int[] getLastMotionBounds() {
        if (lastMotionMaxY > lastMotionMinY) {
            return new int[] { lastMotionMinY, lastMotionMaxY };
        }
        return null;
    }
    
    /**
     * Gets class name from COCO class ID.
     */
    private String getClassName(int classId) {
        switch (classId) {
            case 0: return "person";
            case 2: return "car";
            case 3: return "motorcycle";
            case 5: return "bus";
            case 7: return "truck";
            default: return "object_" + classId;
        }
    }
    
    /**
     * Sets object detection filters.
     * 
     * Also adjusts motion detection sensitivity based on minSize:
     * - Lower minSize (for distant objects) = lower motion sensitivity
     * - Higher minSize (for close objects) = higher motion sensitivity
     * 
     * @param minSize Minimum object size (0.0-1.0, fraction of frame area)
     * @param confidence Minimum confidence (0.0-1.0)
     * @param detectPerson Enable person detection
     * @param detectCar Enable car detection
     * @param detectBike Enable bike detection
     */
    public void setObjectFilters(float minSize, float confidence, 
                                 boolean detectPerson, boolean detectCar, boolean detectBike) {
        this.minObjectSize = minSize;
        this.aiConfidence = confidence;
        
        // Build class filter for YOLO
        java.util.ArrayList<Integer> classes = new java.util.ArrayList<>();
        if (detectPerson) classes.add(0);  // COCO: person
        if (detectCar) {
            classes.add(2);  // COCO: car
            classes.add(5);  // COCO: bus
            classes.add(7);  // COCO: truck
        }
        if (detectBike) {
            classes.add(1);  // COCO: bicycle
            classes.add(3);  // COCO: motorcycle
        }
        
        if (classes.isEmpty()) {
            classFilter = null;  // Detect all classes
        } else {
            classFilter = new int[classes.size()];
            for (int i = 0; i < classes.size(); i++) {
                classFilter[i] = classes.get(i);
            }
        }
        
        logger.info(String.format("Object filters: minSize=%.1f%%, confidence=%.0f%%, classes=%s",
                minSize * 100, confidence * 100, classes));
    }
    
    /**
     * Starts recording an event with pre-record support.
     * 
     * The encoder is always running and buffering frames. This method
     * triggers the flush of the pre-record buffer and starts writing to file.
     */
    private void startRecording() {
        if (recorder == null) {
            logger.error("Cannot start recording - recorder is null");
            return;
        }
        
        if (recording) {
            logger.debug("Already recording");
            return;
        }
        
        // SOTA: Ensure storage space before recording (auto-cleanup oldest files)
        try {
            com.overdrive.app.storage.StorageManager storageManager =
                com.overdrive.app.storage.StorageManager.getInstance();
            
            // Safety net: verify SD card is still mounted before writing
            // BYD system can unmount it at any time when ACC is off
            if (storageManager.getSurveillanceStorageType() == 
                com.overdrive.app.storage.StorageManager.StorageType.SD_CARD &&
                !storageManager.isSdCardMounted()) {
                logger.warn("SD card unmounted before recording - attempting remount");
                if (!storageManager.ensureSdCardMounted(true)) {
                    logger.error("SD card remount failed - event may write to stale path");
                }
            }
            
            // Reserve ~50MB for new recording (typical event is 10-30MB)
            boolean spaceAvailable = storageManager.ensureSurveillanceSpace(50 * 1024 * 1024);
            if (!spaceAvailable) {
                logger.warn("Storage cleanup could not free enough space, recording anyway");
            }
        } catch (Exception e) {
            logger.warn("Storage check failed: " + e.getMessage());
        }
        
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "event_" + timestamp + ".mp4";
        currentEventFile = new File(eventOutputDir, fileName);
        
        logger.info("Triggering event recording: " + currentEventFile.getAbsolutePath());
        logger.info(String.format("Pre-record: %d sec, Post-record: %d sec", 
                preRecordMs / 1000, postRecordMs / 1000));
        
        // Trigger event recording (flushes pre-record buffer)
        recorder.triggerEventRecording(currentEventFile.getAbsolutePath(), postRecordMs);
        recording = true;
        
        // SOTA: Start timeline event collection for this recording.
        // Use the ACTUAL pre-record duration from the H.264 circular buffer, not the
        // configured preRecordMs. The circular buffer starts from the nearest keyframe,
        // which can be significantly longer than the configured pre-record window.
        // Example: configured preRecordMs=5000, but buffer flushed 14.1 sec of video.
        // If we use 5000ms as the origin, timeline events appear 9 seconds too early.
        long actualPreRecordMs = preRecordMs;
        try {
            HardwareEventRecorderGpu encoder = recorder.getEncoder();
            if (encoder != null) {
                long actual = encoder.getActualPreRecordDurationMs();
                if (actual > 0) {
                    actualPreRecordMs = actual;
                    logger.info("Timeline using actual pre-record duration: " + actual + "ms (configured: " + preRecordMs + "ms)");
                }
            }
        } catch (Exception e) {
            logger.warn("Could not get actual pre-record duration: " + e.getMessage());
        }
        timelineCollector.startCollecting(actualPreRecordMs);
        
        logger.info("Event recording triggered successfully");
    }
    
    /**
     * Stops recording an event with post-record support.
     */
    private void stopRecording() {
        if (recorder == null || !recording) {
            return;
        }
        
        // Stop immediately (post-record already handled by timeout)
        recorder.stopEventRecording(true, 0);
        recording = false;
        lastRecordingStopTime = System.currentTimeMillis();  // Track when we stopped
        
        if (currentEventFile != null && currentEventFile.exists()) {
            logger.info( String.format("Saved: %s (%d KB)",
                    currentEventFile.getName(), currentEventFile.length() / 1024));
            
            // SOTA: Write timeline JSON sidecar alongside the MP4
            timelineCollector.stopAndWrite(currentEventFile);
        }
        
        currentEventFile = null;
        logger.info("Recording stopped, motion detection continues");
    }
    
    /**
     * Enables surveillance (starts monitoring).
     */
    public void enable() {
        // Check if native library is loaded
        if (!NativeMotion.isLibraryLoaded()) {
            logger.error(">>> Cannot enable surveillance: NativeMotion library not loaded! Error: " + 
                NativeMotion.getLoadError());
            return;
        }
        
        logger.info("Enabling surveillance engine (pipelineV2=" + (pipelineV2 != null) + 
            ", pipelineV2init=" + (pipelineV2 != null && pipelineV2.isInitialized()) + ")");
        
        active = true;
        frameCount = 0;
        motionDetections = 0;
        firstMotionTime = 0;  // Reset sustained motion timer
        peakThreatDuringSequence = 0;
        
        // Reset SOTA tracking variables
        lastTemporalBlocksCount = 0;
        lastMotionMinY = 0;
        lastMotionMaxY = 0;
        lastEstimatedDistance = 0;
        
        // SOTA: Notify StorageManager that surveillance is active (for periodic cleanup)
        try {
            com.overdrive.app.storage.StorageManager.getInstance().setSurveillanceActive(true);
        } catch (Exception e) {
            logger.warn("Could not set surveillance active state: " + e.getMessage());
        }
        
        // V2: Re-initialize pipeline for clean start
        if (pipelineV2 != null) {
            try {
                NativeMotion.initPipelineV2();
                logger.info("V2 pipeline reset for new surveillance session");
            } catch (Exception e) {
                logger.warn("V2 pipeline reset failed: " + e.getMessage());
            }
        }
        
        // Reset cross-quadrant tracker for clean session
        crossQuadrantTracker.reset();
        
        // Initialize native texture tracker (YOLO + NCC hybrid VOT)
        try {
            NativeMotion.initTracker();
            logger.info("Texture tracker initialized (YOLO + NCC hybrid)");
        } catch (Exception e) {
            logger.warn("Texture tracker init failed: " + e.getMessage());
        }
        
        logger.info("Surveillance enabled (V2 per-quadrant pipeline)");
    }
    
    /**
     * Disables surveillance (stops monitoring).
     */
    public void disable() {
        if (recording) {
            stopRecording();
        }
        active = false;
        inActiveMode = false;
        
        // SOTA: Notify StorageManager that surveillance is inactive
        try {
            com.overdrive.app.storage.StorageManager.getInstance().setSurveillanceActive(false);
        } catch (Exception e) {
            logger.warn("Could not set surveillance inactive state: " + e.getMessage());
        }
        
        logger.info("Surveillance disabled");
    }
    
    /**
     * Checks if surveillance is active.
     * 
     * @return true if active, false otherwise
     */
    public boolean isActive() {
        return active;
    }
    
    /**
     * Checks if currently recording.
     * 
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return recording;
    }
    
    /**
     * Checks if in active mode (heavy AI).
     * 
     * @return true if in active mode, false if idle
     */
    public boolean isInActiveMode() {
        return inActiveMode;
    }
    
    /**
     * Gets the current SAD threshold.
     * 
     * @return Threshold value (0.0-1.0)
     */
    public float getSadThreshold() {
        return config.getSensitivity();
    }
    
    /**
     * Gets the grid motion block sensitivity.
     * 
     * @return Sensitivity value (0.0-1.0, typically 0.04-0.10)
     */
    public float getBlockSensitivity() {
        return config.getSensitivity();
    }
    
    /**
     * Sets the grid motion block sensitivity.
     * Lower values detect more distant/subtle motion.
     * 
     * @param sensitivity Sensitivity value (0.01-0.20, default 0.04)
     */
    public void setBlockSensitivity(float sensitivity) {
        float clamped = Math.max(0.01f, Math.min(0.20f, sensitivity));
        config.setSensitivity(clamped);
        logger.info("Block sensitivity set to: " + clamped);
    }
    
    /**
     * Sets the unified motion sensitivity (0-100%).
     * 
     * This is the recommended API for controlling motion detection.
     * A single slider that intelligently adjusts:
     * - Density Threshold: How many pixels must change per block
     * - Alarm Threshold: How many blocks must trigger to start recording
     * 
     * Mapping:
     * - 0-30%:   LOW (large/close objects only)
     * - 31-60%:  MEDIUM (balanced, default)
     * - 61-80%:  HIGH (detects distant objects)
     * - 81-100%: VERY HIGH (any motion)
     * 
     * @param sensitivity 0-100 percentage
     */
    public void setUnifiedSensitivity(int sensitivity) {
        config.setUnifiedSensitivity(sensitivity);
        
        // Sync legacy fields for backward compatibility
        this.requiredActiveBlocks = config.getAlarmBlockThreshold();
        
        logger.info(String.format("Unified sensitivity set to: %d%% (alarm=%d blocks, density=%d pixels, shadow=%d)",
                sensitivity, config.getAlarmBlockThreshold(), config.getDensityThreshold(), config.getShadowThreshold()));
    }
    
    /**
     * Gets the unified motion sensitivity (0-100%).
     * 
     * @return Sensitivity percentage
     */
    public int getUnifiedSensitivity() {
        return config.getUnifiedSensitivity();
    }
    
    /**
     * Sets night mode (affects shadow threshold).
     * 
     * Night mode uses a higher shadow threshold (40 vs 25) to filter
     * out headlight reflections and other light artifacts.
     * 
     * @param enabled true for night mode
     */
    public void setNightMode(boolean enabled) {
        config.setNightMode(enabled);
        // Log the V2 pipeline's actual shadow threshold (not the legacy config's)
        int v2Shadow = pipelineV2Config != null ? pipelineV2Config.shadowThreshold : -1;
        int v2Filter = pipelineV2Config != null ? pipelineV2Config.shadowFilterMode : -1;
        logger.info("Night mode set to: " + enabled + 
                " (V2 shadow threshold=" + v2Shadow + ", filter=" + v2Filter + ")");
    }
    
    /**
     * Gets night mode state.
     * 
     * @return true if night mode is enabled
     */
    public boolean isNightMode() {
        return config.isNightMode();
    }
    
    /**
     * Gets the required active blocks threshold.
     * 
     * @return Number of blocks required to trigger motion
     */
    public int getRequiredActiveBlocks() {
        return requiredActiveBlocks;
    }
    
    /**
     * Sets the required active blocks threshold.
     * Lower values are more sensitive to small/distant motion.
     * 
     * @param blocks Number of blocks (1-10, default 2)
     */
    public void setRequiredActiveBlocks(int blocks) {
        this.requiredActiveBlocks = Math.max(1, Math.min(10, blocks));
        // Sync with SOTA config
        config.setRequiredBlocks(this.requiredActiveBlocks);
        logger.info("Required active blocks set to: " + this.requiredActiveBlocks);
    }
    
    /**
     * Gets the flash immunity level.
     * 
     * @return Flash immunity level (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
     */
    public int getFlashImmunity() {
        return flashImmunity;
    }
    
    /**
     * Gets the minimum object size for detection.
     * 
     * @return Minimum object size as fraction of frame (0.02 = 2% = ~15m, 0.20 = 20% = ~3m)
     */
    public float getMinObjectSize() {
        return minObjectSize;
    }
    
    /**
     * Sets the flash immunity level.
     * 
     * Uses edge-based detection to ignore light flashes (headlights, lightning, etc.)
     * while still detecting real object motion.
     * 
     * Levels:
     * - 0 = OFF: Legacy pixel differencing, sensitive to flashes
     * - 1 = LOW: Edge-based, some flash filtering
     * - 2 = MEDIUM: Edge-based + brightness normalization (default)
     * - 3 = HIGH: Edge-based + aggressive flash rejection
     * 
     * @param level Flash immunity level (0-3)
     */
    public void setFlashImmunity(int level) {
        this.flashImmunity = Math.max(0, Math.min(3, level));
        // Sync with SOTA config
        config.setFlashImmunity(this.flashImmunity);
        String[] levelNames = {"OFF", "LOW", "MEDIUM", "HIGH"};
        logger.info("Flash immunity set to: " + levelNames[this.flashImmunity] + " (" + this.flashImmunity + ")");
    }
    
    /**
     * Gets the total number of grid blocks.
     * 
     * @return Total blocks (300 for 640x480 with 32px blocks)
     */
    public int getTotalBlocks() {
        return TOTAL_BLOCKS;
    }
    
    /**
     * Gets the last active blocks count (for UI display).
     * 
     * @return Number of blocks that were active in the last frame
     */
    public int getLastActiveBlocksCount() {
        return lastActiveBlocksCount;
    }
    
    /**
     * Gets the baseline noise blocks count (deprecated - always returns 0).
     * 
     * @return Always 0 (baseline logic removed)
     */
    public int getBaselineNoiseBlocks() {
        return 0;  // Baseline logic removed for simplicity
    }
    
    /**
     * Sets the SAD threshold for motion detection.
     * 
     * @param threshold Threshold value (0.0-1.0, typically 0.05 for 5%)
     */
    public void setSadThreshold(float threshold) {
        config.setSensitivity(threshold);
        logger.info( "SAD threshold set to: " + threshold);
    }
    
    /**
     * Gets the pre-record duration in seconds.
     * 
     * @return Pre-record duration in seconds
     */
    public int getPreRecordSeconds() {
        return (int) (preRecordMs / 1000);
    }
    
    /**
     * Sets the pre-record duration.
     * 
     * @param seconds Duration in seconds (e.g., 10 for 10 seconds before motion)
     */
    public void setPreRecordSeconds(int seconds) {
        this.preRecordMs = seconds * 1000L;
        // Sync with SOTA config
        config.setPreRecordSeconds(seconds);
        logger.info("Pre-record duration set to: " + seconds + " seconds");
        
        // Update the circular buffer size in the recorder's encoder
        if (recorder != null && recorder.getEncoder() != null) {
            recorder.getEncoder().setPreRecordDuration(seconds);
        }
    }
    
    /**
     * Gets the post-record duration in seconds.
     * 
     * @return Post-record duration in seconds
     */
    public int getPostRecordSeconds() {
        return (int) (postRecordMs / 1000);
    }
    
    /**
     * Sets the post-record duration.
     * 
     * @param seconds Duration in seconds (e.g., 5 for 5 seconds after motion stops)
     */
    public void setPostRecordSeconds(int seconds) {
        this.postRecordMs = seconds * 1000L;
        // Sync with SOTA config
        config.setPostRecordSeconds(seconds);
        logger.info("Post-record duration set to: " + seconds + " seconds");
    }
    
    /**
     * Gets the frame count.
     * 
     * @return Total frames processed
     */
    public int getFrameCount() {
        return frameCount;
    }
    
    /**
     * Gets the motion detection count.
     * 
     * @return Total motion events detected
     */
    public int getMotionDetections() {
        return motionDetections;
    }
    
    /**
     * Releases all resources.
     */
    /**
     * Updates the V2 pipeline configuration.
     * Call this when user changes settings via IPC.
     */
    public void updateV2Config(MotionPipelineV2.Config newConfig) {
        if (pipelineV2 != null) {
            if (newConfig != null) {
                pipelineV2Config = newConfig;
            }
            if (pipelineV2Config != null) {
                pipelineV2.applyConfig(pipelineV2Config);
                logger.info("V2 pipeline config updated");
            }
        }
    }
    
    /**
     * Apply a V2 environment preset (outdoor/garage/street).
     */
    public void applyV2EnvironmentPreset(String preset) {
        if (pipelineV2Config != null) {
            pipelineV2Config.applyEnvironmentPreset(preset);
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            logger.info("V2 environment preset applied: " + preset);
        }
    }
    
    /**
     * Apply V2 sensitivity level (1-5).
     */
    public void applyV2Sensitivity(int level) {
        if (pipelineV2Config != null) {
            pipelineV2Config.applySensitivity(level);
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            logger.info("V2 sensitivity set to " + level);
        }
    }
    
    /**
     * Set V2 loitering time in seconds.
     */
    public void setV2LoiteringTime(int seconds) {
        if (pipelineV2Config != null) {
            pipelineV2Config.loiteringFrames = seconds * 10;  // 10 FPS
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
        }
        // Also update Java-side sustained motion threshold.
        // THREAT_MEDIUM must persist for this duration before triggering.
        this.loiteringTimeMs = seconds * 1000L;
        logger.info("V2 loitering time set to " + seconds + "s (native=" + (seconds * 10) + " frames, java=" + loiteringTimeMs + "ms)");
    }
    
    /**
     * Enable/disable a specific camera quadrant for V2 detection.
     * @param quadrant 0=front, 1=right, 2=left, 3=rear
     * @param enabled true to enable, false to disable
     */
    public void setV2QuadrantEnabled(int quadrant, boolean enabled) {
        if (pipelineV2Config != null && quadrant >= 0 && quadrant < 4) {
            pipelineV2Config.quadrantEnabled[quadrant] = enabled;
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            logger.info("V2 quadrant " + MotionPipelineV2.QUADRANT_NAMES[quadrant] + 
                    " " + (enabled ? "enabled" : "disabled"));
        }
    }
    
    /**
     * Get V2 pipeline results (for heatmap overlay / debug).
     */
    public MotionPipelineV2.QuadrantResult[] getV2Results() {
        return pipelineV2 != null ? pipelineV2.getResults() : null;
    }
    
    /**
     * Set shadow filter mode for V2 pipeline.
     * @param mode 0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE
     */
    public void setV2ShadowFilterMode(int mode) {
        if (pipelineV2Config != null && mode >= 0 && mode <= 3) {
            pipelineV2Config.shadowFilterMode = mode;
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            String[] modeNames = {"OFF", "LIGHT", "NORMAL", "AGGRESSIVE"};
            logger.info("V2 shadow filter mode set to " + modeNames[mode]);
        }
    }
    
    /**
     * Get current shadow filter mode.
     * @return 0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE
     */
    public int getV2ShadowFilterMode() {
        return pipelineV2Config != null ? pipelineV2Config.shadowFilterMode : 0;
    }
    
    /**
     * Enable/disable filter debug logging.
     */
    public void setFilterDebugEnabled(boolean enabled) {
        this.filterDebugEnabled = enabled;
        if (!enabled) {
            synchronized (filterLog) {
                filterLogCount = 0;
                filterLogIndex = 0;
            }
        }
        logger.info("Filter debug log " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Add an entry to the filter debug log ring buffer.
     */
    private void addFilterLogEntry(String entry) {
        if (!filterDebugEnabled) return;
        synchronized (filterLog) {
            filterLog[filterLogIndex] = entry;
            filterLogIndex = (filterLogIndex + 1) % FILTER_LOG_CAPACITY;
            if (filterLogCount < FILTER_LOG_CAPACITY) filterLogCount++;
        }
    }
    
    /**
     * Get recent filter log entries (newest first).
     */
    public String[] getFilterLogEntries() {
        synchronized (filterLog) {
            String[] entries = new String[filterLogCount];
            for (int i = 0; i < filterLogCount; i++) {
                int idx = (filterLogIndex - 1 - i + FILTER_LOG_CAPACITY) % FILTER_LOG_CAPACITY;
                entries[i] = filterLog[idx];
            }
            return entries;
        }
    }
    
    public void release() {
        disable();
        
        // SOTA FIX: Shutdown the executor
        aiExecutor.shutdownNow();
        
        // Clean up YOLO detector
        if (yoloDetector != null) {
            yoloDetector.close();
            yoloDetector = null;
        }
        
        currentFrame = null;
        aiBuffer = null;  // Let GC reclaim the large buffer
        
        logger.info("Released");
    }
    
}
