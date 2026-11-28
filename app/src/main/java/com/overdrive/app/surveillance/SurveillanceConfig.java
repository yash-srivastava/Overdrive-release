package com.overdrive.app.surveillance;

/**
 * SOTA Surveillance Configuration
 * 
 * Unified configuration for motion detection, flash filtering, and distance estimation.
 * Provides presets for common use cases and custom configuration for advanced users.
 */
public class SurveillanceConfig {
    
    // ========================================================================
    // Distance Presets
    // ========================================================================
    
    public enum DistancePreset {
        /** Strict: Large objects only (car/group). Ignores single walkers. */
        CLOSE(32, 4, 0.06f, 0.5f, 3.0f),
        
        /** Conservative: Solid objects. Good for windy trees. */
        NEAR(32, 3, 0.05f, 1.0f, 5.0f),
        
        /** Default: Balanced. Catches walking people reliably. */
        MEDIUM(32, 2, 0.04f, 2.0f, 8.0f),
        
        /** Sensitive: High gain. Catches motion immediately on block entry. */
        FAR(32, 2, 0.03f, 3.0f, 10.0f),
        
        /** Aggressive: Max sensitivity. Use only indoors/garages. */
        VERY_FAR(32, 1, 0.02f, 5.0f, 15.0f),
        
        /** Custom configuration - use setters to configure. */
        CUSTOM(32, 2, 0.04f, 0.5f, 15.0f);
        
        public final int blockSize;
        public final int requiredBlocks;
        public final float sensitivity;
        public final float minDistanceM;
        public final float maxDistanceM;
        
        // Density thresholds for each preset (pixels per block that must change)
        // With 32x32 blocks and stride-2 sampling = ~256 pixels checked per block
        public int getDensityThreshold() {
            switch (this) {
                case CLOSE:    return 48;  // ~19% - strict
                case NEAR:     return 40;  // ~16% - conservative  
                case MEDIUM:   return 32;  // ~12% - balanced (default)
                case FAR:      return 16;  // ~6%  - sensitive
                case VERY_FAR: return 12;  // ~4%  - aggressive
                default:       return 32;  // default to balanced
            }
        }
        
        DistancePreset(int blockSize, int requiredBlocks, float sensitivity, 
                       float minDistanceM, float maxDistanceM) {
            this.blockSize = blockSize;
            this.requiredBlocks = requiredBlocks;
            this.sensitivity = sensitivity;
            this.minDistanceM = minDistanceM;
            this.maxDistanceM = maxDistanceM;
        }
    }
    
    // ========================================================================
    // Flash Filtering Mode (Now: Shadow Rejection for Grayscale Grid)
    // ========================================================================
    
    public enum FlashMode {
        /** No shadow filtering (very sensitive, may catch shadows). */
        OFF(0, 0, false),
        
        /** Normal shadow filtering - shadowThreshold=40. */
        LOW(1, 0, false),
        
        /** Strict shadow filtering - shadowThreshold=50. */
        MEDIUM(2, 0, false),
        
        /** Maximum shadow filtering - shadowThreshold=60. */
        HIGH(3, 0, false),
        
        /** Adaptive filtering with temporal consistency. */
        ADAPTIVE(2, 3, false),
        
        /** Maximum filtering - temporal + strict shadow. */
        MAXIMUM(3, 3, true);
        
        public final int flashImmunity;
        public final int temporalFrames;
        public final boolean useChroma;
        
        FlashMode(int flashImmunity, int temporalFrames, boolean useChroma) {
            this.flashImmunity = flashImmunity;
            this.temporalFrames = temporalFrames;
            this.useChroma = useChroma;
        }
    }
    
    // ========================================================================
    // Configuration Fields
    // ========================================================================
    
    // Distance filtering
    private DistancePreset distancePreset = DistancePreset.MEDIUM;
    private int blockSize = 32;
    private int requiredBlocks = 3;
    private float sensitivity = 0.04f;
    private float minDistanceM = 0.5f;   // Allow close objects (was 2.0f)
    private float maxDistanceM = 10.0f;
    
    // Flash filtering
    private FlashMode flashMode = FlashMode.ADAPTIVE;
    private int flashImmunity = 2;
    private int temporalFrames = 3;
    private boolean useChroma = false;
    
    // Camera calibration values retained for configuration serialization.
    private float cameraHeightM = 0.8f;
    private float cameraTiltDeg = 0.0f;      // Zero tilt for 360 fish-eye cameras
    private float verticalFovDeg = 110.0f;   // Wide angle for 360 fish-eye cameras
    private int frameWidth = 640;            // Frame width in pixels
    private int frameHeight = 480;           // Frame height in pixels
    private boolean isMosaic = true;         // True for 2x2 camera grid
    
    // Object detection
    private float aiConfidence = 0.25f;
    private float minObjectSize = 0.12f;     // Fraction of quadrant height
    private boolean detectPerson = true;
    private boolean detectCar = true;
    private boolean detectBike = false;
    // Animals (COCO 14-23: bird/cat/dog/horse/sheep/cow/elephant/bear/zebra/
    // giraffe). Default OFF — opt-in only, like detectBike. The downstream
    // pipeline (Actor.ClassGroup.ANIMAL, animalCount stats, recordings
    // "Animal" filter chip) already exists; this flag is the input gate.
    private boolean detectAnimal = false;
    
    // Recording
    private int preRecordSeconds = 5;
    private int postRecordSeconds = 10;

    // ========================================================================
    // Notification severity gating (item 8)
    //
    // Per-tier mute is enforced device-side via PushSubscription.mutedCategories
    // against the new "surveillance.motion.{notice,alert,critical}" subcategories
    // (see notifications-categories.json). These config fields exist purely so
    // the legacy NotificationGate static helpers compile and so existing callers
    // that read the values keep working. They are NOT persisted any more.
    // ========================================================================
    private boolean pushNotices  = false;
    private boolean pushAlerts   = true;
    private boolean pushCritical = true;

    /**
     * Telegram-specific: when true, send a "Recording in progress…" text
     * message at the moment recording starts, in addition to the rich photo +
     * caption sent when it closes. When false, only the close-stage photo is
     * sent — recommended default because the start-stage text is redundant
     * for users who also have the PWA push (the PWA banner replaces itself
     * via the same notification tag, but Telegram has no equivalent and the
     * user sees two messages back-to-back). Telegram-only users may want
     * this on for low-latency awareness.
     */
    private boolean telegramSendStartPing = false;

    // Per-tier Telegram filter (tierNotices/tierAlerts/tierCritical) moved
    // to the telegram section of unified config — see
    // UnifiedTelegramConfig.K_TIER_*. Lives there so the daemon process and
    // the app process see the same value without a camera-daemon restart.
    
    // ========================================================================
    // UNIFIED SENSITIVITY (0-100%)
    // ========================================================================
    // Single slider that controls both density and alarm thresholds
    // Maps to technical parameters internally
    private int unifiedSensitivity = 50;  // Default: 50% (Medium)
    
    // Night mode toggle (affects shadow threshold)
    private boolean nightMode = false;
    
    // ========================================================================
    // V2 Pipeline Settings
    // ========================================================================
    private String environmentPreset = "outdoor";  // outdoor, garage, street
    private String detectionZone = "normal";        // close, normal, extended
    private int loiteringTimeSeconds = 3;           // 1-10 seconds
    // Sustained-motion seconds required to record an APPROACH (THREAT_MEDIUM)
    // once YOLO has confirmed a real in-zone object (person/car/...). This is the
    // short, responsive path for someone walking up to / past the car — distinct
    // from loiteringTimeSeconds (the long dwell required for a loiter / for
    // motion-only events with no AI confirmation). 0 = disabled (fall back to
    // loiteringTimeSeconds, i.e. the legacy behavior).
    private int approachTriggerSeconds = 2;         // 0 (off) or 1-10 seconds
    private int sensitivityLevel = 3;               // 1-5
    private boolean[] cameraEnabled = {true, true, true, true};  // front, right, rear, left (matches quadrant order: Q0=front, Q1=right, Q2=rear, Q3=left)
    private boolean motionHeatmapEnabled = false;
    private boolean filterDebugLogEnabled = false;
    // Aggressively discard a recording that ended with NO real (non-static) actor
    // when the scene was bright everywhere and nothing approached — the
    // shadow/tree-branch false-positive. Default OFF. See the engine's
    // shouldDiscardEvent() for the full safety predicate.
    private boolean discardEmptyBrightMotionEvents = false;
    // Extend the discard above to DARK scenes too. Only meaningful when the primary
    // flag is on. Off = the bright-only behaviour (dark clips always kept). On = at
    // night the discard uses luma-free flow-coherence / illumination-artifact
    // evidence instead of the brightness clauses. Higher low-light risk → separate
    // explicit opt-in. See SurveillanceEngineGpu.shouldDiscardEvent() night path.
    private boolean discardEmptyMotionAtNight = false;
    // MOTION SALIENCE (third evidence channel). Record when the motion itself is
    // object-grade evidence — large, compact, sustained, photometrically stable and
    // rigidly translating — even though YOLO returned no class and the subject
    // never held still long enough to read as a loiter. Default OFF; with it off
    // both the native flash-filter probe and the Java trigger channel are inert.
    // Pairs with discardEmptyBrightMotionEvents: salience supplies recall, the
    // discard supplies precision. See SurveillanceEngineGpu's salience* fields.
    private boolean motionSalienceEnabled = false;

    // Post-park vigilance: after a vehicle is WATCHED parking (promoted to the
    // detection baseline by a live event), motion at that spot for the next few
    // minutes gets a lowered trigger bar + far-unconfirmed-gate exemption, so a
    // person exiting the just-parked car records even when YOLO never resolves
    // a person class (occluded by their own door / fisheye warp). Heavily
    // braked (min-gap + per-window budget + TTL'd anchor) — see the vigilance*
    // fields in SurveillanceEngineGpu. Default ON: the channel is inert unless
    // a vehicle was actually watched arriving.
    private boolean postParkVigilanceEnabled = true;
    private int shadowFilterMode = 2;               // 0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE

    // ========================================================================
    // Per-Quadrant Sensitivity / Zone Overrides
    // ========================================================================
    // null = inherit the global value. Indexed Q0=front, Q1=right, Q2=rear, Q3=left.
    // Side cams (Q1, Q3) often want stricter recall than front/rear because
    // a parking-lot neighbor pulls into the side stalls — these overrides let
    // the user crank just the sides without flooding front/rear with false
    // positives.
    private Integer[] quadrantSensitivityOverride = new Integer[4];   // 1-5, null = inherit
    private String[]  quadrantDetectionZoneOverride = new String[4];   // close|normal|extended, null = inherit

    /**
     * Resolved sensitivity level for a quadrant: per-quadrant override if set,
     * otherwise the global {@code sensitivityLevel}.
     */
    public int getEffectiveSensitivityLevel(int quadrant) {
        if (quadrant >= 0 && quadrant < 4 && quadrantSensitivityOverride[quadrant] != null) {
            return quadrantSensitivityOverride[quadrant];
        }
        return sensitivityLevel;
    }

    /**
     * Resolved detection zone for a quadrant: per-quadrant override if set,
     * otherwise the global {@code detectionZone}.
     */
    public String getEffectiveDetectionZone(int quadrant) {
        if (quadrant >= 0 && quadrant < 4 && quadrantDetectionZoneOverride[quadrant] != null) {
            return quadrantDetectionZoneOverride[quadrant];
        }
        return detectionZone;
    }

    public Integer getQuadrantSensitivityOverride(int quadrant) {
        if (quadrant < 0 || quadrant >= 4) return null;
        return quadrantSensitivityOverride[quadrant];
    }

    public void setQuadrantSensitivityOverride(int quadrant, Integer level) {
        if (quadrant < 0 || quadrant >= 4) return;
        if (level == null) {
            quadrantSensitivityOverride[quadrant] = null;
        } else {
            quadrantSensitivityOverride[quadrant] = Math.max(1, Math.min(5, level));
        }
    }

    public String getQuadrantDetectionZoneOverride(int quadrant) {
        if (quadrant < 0 || quadrant >= 4) return null;
        return quadrantDetectionZoneOverride[quadrant];
    }

    public void setQuadrantDetectionZoneOverride(int quadrant, String zone) {
        if (quadrant < 0 || quadrant >= 4) return;
        if (zone == null) { quadrantDetectionZoneOverride[quadrant] = null; return; }
        if (zone.equals("close") || zone.equals("normal") || zone.equals("extended")) {
            quadrantDetectionZoneOverride[quadrant] = zone;
        }
    }

    // V2 getters
    public String getEnvironmentPreset() { return environmentPreset; }
    public String getDetectionZone() { return detectionZone; }
    public int getLoiteringTimeSeconds() { return loiteringTimeSeconds; }
    public int getApproachTriggerSeconds() { return approachTriggerSeconds; }
    public int getSensitivityLevel() { return sensitivityLevel; }
    public boolean[] getCameraEnabled() { return cameraEnabled; }
    public boolean isMotionHeatmapEnabled() { return motionHeatmapEnabled; }
    public boolean isFilterDebugLogEnabled() { return filterDebugLogEnabled; }
    public boolean isDiscardEmptyBrightMotionEvents() { return discardEmptyBrightMotionEvents; }
    public boolean isDiscardEmptyMotionAtNight() { return discardEmptyMotionAtNight; }
    public boolean isMotionSalienceEnabled() { return motionSalienceEnabled; }
    public boolean isPostParkVigilanceEnabled() { return postParkVigilanceEnabled; }
    public int getShadowFilterMode() { return shadowFilterMode; }
    
    // V2 setters
    public void setEnvironmentPreset(String preset) { this.environmentPreset = preset; }
    public void setDetectionZone(String zone) { this.detectionZone = zone; }
    public void setLoiteringTimeSeconds(int seconds) {
        this.loiteringTimeSeconds = Math.max(1, Math.min(10, seconds));
    }
    /** 0 disables the fast approach path; otherwise clamped to 1-10s. */
    public void setApproachTriggerSeconds(int seconds) {
        this.approachTriggerSeconds = (seconds <= 0) ? 0 : Math.max(1, Math.min(10, seconds));
    }
    public void setSensitivityLevel(int level) { 
        this.sensitivityLevel = Math.max(1, Math.min(5, level)); 
    }
    public void setCameraEnabled(int quadrant, boolean enabled) {
        if (quadrant >= 0 && quadrant < 4) cameraEnabled[quadrant] = enabled;
    }
    public void setMotionHeatmapEnabled(boolean enabled) { this.motionHeatmapEnabled = enabled; }
    public void setDiscardEmptyBrightMotionEvents(boolean enabled) { this.discardEmptyBrightMotionEvents = enabled; }
    public void setDiscardEmptyMotionAtNight(boolean enabled) { this.discardEmptyMotionAtNight = enabled; }
    public void setMotionSalienceEnabled(boolean enabled) { this.motionSalienceEnabled = enabled; }
    public void setPostParkVigilanceEnabled(boolean enabled) { this.postParkVigilanceEnabled = enabled; }
    public void setFilterDebugLogEnabled(boolean enabled) { this.filterDebugLogEnabled = enabled; }
    public void setShadowFilterMode(int mode) { this.shadowFilterMode = Math.max(0, Math.min(3, mode)); }
    
    // ========================================================================
    // Per-Camera ROI (Region of Interest)
    // ========================================================================
    // Each quadrant can have an independent polygon ROI. Vertices are stored as
    // normalized coordinates (0.0-1.0) relative to quadrant dimensions.
    // null = no ROI (all blocks enabled, default behavior).
    @SuppressWarnings("unchecked")
    private float[][][] roiPolygons = new float[4][][];  // [quadrant][vertex][x,y]
    private boolean[] roiEnabled = {false, false, false, false};
    
    public float[][] getRoiPolygon(int quadrant) {
        if (quadrant < 0 || quadrant >= 4) return null;
        return roiPolygons[quadrant];
    }
    public void setRoiPolygon(int quadrant, float[][] polygon) {
        if (quadrant >= 0 && quadrant < 4) {
            roiPolygons[quadrant] = polygon;
            roiEnabled[quadrant] = (polygon != null && polygon.length >= 3);
        }
    }
    public boolean isRoiEnabled(int quadrant) {
        return quadrant >= 0 && quadrant < 4 && roiEnabled[quadrant];
    }
    public void clearRoi(int quadrant) {
        if (quadrant >= 0 && quadrant < 4) {
            roiPolygons[quadrant] = null;
            roiEnabled[quadrant] = false;
        }
    }
    
    /**
     * Sets only the ROI enabled flag without changing the polygon.
     * When disabled, the polygon is preserved but not applied to the C++ pipeline.
     */
    public void setRoiEnabled(int quadrant, boolean enabled) {
        if (quadrant >= 0 && quadrant < 4) {
            roiEnabled[quadrant] = enabled;
        }
    }
    
    // ========================================================================
    // Surveillance Schedule
    // ========================================================================
    private final SurveillanceSchedule schedule = new SurveillanceSchedule();
    
    public SurveillanceSchedule getSchedule() { return schedule; }
    
    // ========================================================================
    // Constructors
    // ========================================================================
    
    public SurveillanceConfig() {
        // Use defaults
    }
    
    public SurveillanceConfig(DistancePreset distancePreset, FlashMode flashMode) {
        setDistancePreset(distancePreset);
        setFlashMode(flashMode);
    }
    
    // ========================================================================
    // Distance Preset Methods
    // ========================================================================
    
    public void setDistancePreset(DistancePreset preset) {
        this.distancePreset = preset;
        if (preset != DistancePreset.CUSTOM) {
            this.blockSize = preset.blockSize;
            this.requiredBlocks = preset.requiredBlocks;
            this.sensitivity = preset.sensitivity;
            this.minDistanceM = preset.minDistanceM;
            this.maxDistanceM = preset.maxDistanceM;
        }
    }
    
    public DistancePreset getDistancePreset() {
        return distancePreset;
    }
    
    // ========================================================================
    // Flash Mode Methods
    // ========================================================================
    
    public void setFlashMode(FlashMode mode) {
        this.flashMode = mode;
        this.flashImmunity = mode.flashImmunity;
        this.temporalFrames = mode.temporalFrames;
        this.useChroma = mode.useChroma;
    }
    
    // ========================================================================
    // Custom Configuration Setters
    // ========================================================================
    
    public void setBlockSize(int blockSize) {
        this.blockSize = Math.max(8, Math.min(64, blockSize));
        this.distancePreset = DistancePreset.CUSTOM;
    }
    
    public void setRequiredBlocks(int requiredBlocks) {
        this.requiredBlocks = Math.max(1, Math.min(10, requiredBlocks));
        this.distancePreset = DistancePreset.CUSTOM;
    }
    
    public void setSensitivity(float sensitivity) {
        this.sensitivity = Math.max(0.01f, Math.min(0.20f, sensitivity));
        this.distancePreset = DistancePreset.CUSTOM;
    }
    
    public void setDistanceRange(float minM, float maxM) {
        this.minDistanceM = Math.max(0.5f, minM);
        this.maxDistanceM = Math.max(minM + 1.0f, maxM);
        this.distancePreset = DistancePreset.CUSTOM;
    }
    
    public void setFlashImmunity(int level) {
        this.flashImmunity = Math.max(0, Math.min(3, level));
        this.flashMode = FlashMode.OFF; // Custom
    }
    
    public void setTemporalFrames(int frames) {
        this.temporalFrames = Math.max(0, Math.min(5, frames));
    }
    
    public void setUseChroma(boolean useChroma) {
        this.useChroma = useChroma;
    }
    
    // ========================================================================
    // Camera Calibration
    // ========================================================================
    
    public void setCameraCalibration(float heightM, float tiltDeg, float fovDeg) {
        this.cameraHeightM = Math.max(0.1f, heightM);
        this.cameraTiltDeg = Math.max(-45.0f, Math.min(45.0f, tiltDeg));
        this.verticalFovDeg = Math.max(20.0f, Math.min(120.0f, fovDeg));
    }
    
    public void setResolution(int width, int height) {
        this.frameWidth = Math.max(160, Math.min(1920, width));
        this.frameHeight = Math.max(120, Math.min(1080, height));
    }
    
    public void setIsMosaic(boolean isMosaic) {
        this.isMosaic = isMosaic;
    }
    
    public boolean isMosaic() {
        return isMosaic;
    }
    
    // ========================================================================
    // Getters
    // ========================================================================
    
    public int getBlockSize() { return blockSize; }
    public int getRequiredBlocks() { return requiredBlocks; }
    public float getSensitivity() { return sensitivity; }
    public float getMinDistanceM() { return minDistanceM; }
    public float getMaxDistanceM() { return maxDistanceM; }
    public int getFlashImmunity() { return flashImmunity; }
    public int getTemporalFrames() { return temporalFrames; }
    public boolean isUseChroma() { return useChroma; }
    public float getCameraHeightM() { return cameraHeightM; }
    public float getCameraTiltDeg() { return cameraTiltDeg; }
    public float getVerticalFovDeg() { return verticalFovDeg; }
    public float getAiConfidence() { return aiConfidence; }
    public float getMinObjectSize() { return minObjectSize; }
    public boolean isDetectPerson() { return detectPerson; }
    public boolean isDetectCar() { return detectCar; }
    public boolean isDetectBike() { return detectBike; }
    public boolean isDetectAnimal() { return detectAnimal; }
    public int getPreRecordSeconds() { return preRecordSeconds; }
    public int getPostRecordSeconds() { return postRecordSeconds; }

    // Notification gating (item 8)
    public boolean isPushNotices() { return pushNotices; }
    public boolean isPushAlerts() { return pushAlerts; }
    public boolean isPushCritical() { return pushCritical; }

    public boolean isTelegramSendStartPing() { return telegramSendStartPing; }
    public void setTelegramSendStartPing(boolean v) { this.telegramSendStartPing = v; }

    // Per-tier Telegram getters/setters moved to UnifiedTelegramConfig.
    
    // Object detection setters
    public void setAiConfidence(float confidence) {
        this.aiConfidence = Math.max(0.1f, Math.min(0.9f, confidence));
    }
    
    public void setMinObjectSize(float size) {
        this.minObjectSize = Math.max(0.02f, Math.min(0.5f, size));
    }
    
    public void setDetectPerson(boolean detect) { this.detectPerson = detect; }
    public void setDetectCar(boolean detect) { this.detectCar = detect; }
    public void setDetectBike(boolean detect) { this.detectBike = detect; }
    public void setDetectAnimal(boolean detect) { this.detectAnimal = detect; }
    
    public void setPreRecordSeconds(int seconds) {
        this.preRecordSeconds = Math.max(1, Math.min(30, seconds));
    }
    
    public void setPostRecordSeconds(int seconds) {
        this.postRecordSeconds = Math.max(1, Math.min(60, seconds));
    }
    
    // ========================================================================
    // UNIFIED SENSITIVITY API
    // ========================================================================
    
    /**
     * Sets the unified motion sensitivity (0-100%).
     * 
     * This single slider controls both:
     * - Density Threshold: How many pixels must change per block
     * - Alarm Threshold: How many blocks must trigger to start recording
     * 
     * Mapping:
     * - 0-30%:   LOW sensitivity (large/close objects only)
     * - 31-60%:  MEDIUM sensitivity (balanced, default)
     * - 61-80%:  HIGH sensitivity (detects distant objects)
     * - 81-100%: VERY HIGH sensitivity (any motion)
     * 
     * @param sensitivity 0-100 percentage
     */
    public void setUnifiedSensitivity(int sensitivity) {
        this.unifiedSensitivity = Math.max(0, Math.min(100, sensitivity));
    }
    
    /**
     * Gets the unified sensitivity (0-100%).
     */
    public int getUnifiedSensitivity() {
        return unifiedSensitivity;
    }
    
    /**
     * Sets night mode (affects shadow threshold).
     * 
     * @param enabled true for night mode (more aggressive shadow filtering)
     */
    public void setNightMode(boolean enabled) {
        this.nightMode = enabled;
    }
    
    /**
     * Gets night mode state.
     */
    public boolean isNightMode() {
        return nightMode;
    }
    
    /**
     * Gets the shadow threshold based on flash immunity level and night mode.
     * 
     * Shadow threshold determines the minimum luma difference to count as motion.
     * Higher values = more aggressive shadow filtering (less sensitive).
     * 
     * Flash immunity slider (0-3) maps to shadow threshold:
     * - 0 (OFF): 20 (very sensitive, catches faint changes)
     * - 1 (LOW): 30 (normal filtering)
     * - 2 (MEDIUM): 40 (moderate filtering)
     * - 3 (HIGH/MAX): 50 (aggressive filtering, ignores most shadows)
     * 
     * Night mode adds +10 to the threshold for extra shadow rejection.
     * 
     * @return Shadow threshold (20-60)
     */
    public int getShadowThreshold() {
        // Base threshold from flash immunity level (shadow rejection slider)
        int baseThreshold;
        switch (flashImmunity) {
            case 0: baseThreshold = 20; break;  // OFF - very sensitive
            case 1: baseThreshold = 30; break;  // LOW - normal
            case 2: baseThreshold = 40; break;  // MEDIUM - moderate
            case 3: baseThreshold = 50; break;  // HIGH - aggressive
            default: baseThreshold = 40; break; // Default to medium
        }
        
        // Night mode adds extra filtering
        return nightMode ? baseThreshold + 10 : baseThreshold;
    }
    
    /**
     * Gets the density threshold based on distance preset OR unified sensitivity.
     * 
     * Density threshold = pixels per block that must change.
     * With 32x32 blocks and stride-2 sampling, we check ~256 pixels per block.
     * 
     * If a distance preset is active, use its density.
     * Otherwise, map from unified sensitivity slider.
     * 
     * @return Density threshold (8-48)
     */
    public int getDensityThreshold() {
        // SOTA: Density is controlled by sensitivity slider, NOT distance
        // Distance only controls minObjectSize (AI detection range)
        // Map from unified sensitivity (0-100%)
        // Lower density = more sensitive (fewer pixels need to change per block)
        if (unifiedSensitivity >= 81) {
            return 8;   // Very High: ~3% - very aggressive
        } else if (unifiedSensitivity >= 61) {
            return 12;  // High: ~5% - sensitive
        } else if (unifiedSensitivity >= 41) {
            return 16;  // Medium-High: ~6% - balanced-sensitive
        } else if (unifiedSensitivity >= 21) {
            return 24;  // Medium: ~9% - balanced
        } else {
            return 48;  // Low: ~19% - strict
        }
    }
    
    /**
     * Gets the alarm block threshold based on distance preset OR unified sensitivity.
     * 
     * Alarm threshold = number of blocks that must be active to trigger.
     * 
     * If a distance preset is active, use its required blocks.
     * Otherwise, map from unified sensitivity slider.
     * 
     * @return Alarm block threshold (1-4)
     */
    public int getAlarmBlockThreshold() {
        // SOTA: Alarm threshold is controlled by requiredBlocks (set by sensitivity slider)
        // Distance only controls minObjectSize (AI detection range)
        // Use the requiredBlocks field directly (set by sensitivity slider 1-5)
        return requiredBlocks;
    }
    
    // ========================================================================
    // Utility Methods
    // ========================================================================
    
    /**
     * Calculates total grid blocks for current configuration.
     */
    public int getTotalBlocks(int frameWidth, int frameHeight) {
        int cols = frameWidth / blockSize;
        int rows = frameHeight / blockSize;
        return cols * rows;
    }
    
    @Override
    public String toString() {
        return String.format(
            "SurveillanceConfig{distance=%s, flash=%s, block=%d, required=%d, " +
            "sensitivity=%.2f, temporal=%d, chroma=%b}",
            distancePreset, flashMode, blockSize, requiredBlocks, 
            sensitivity, temporalFrames, useChroma
        );
    }
}
