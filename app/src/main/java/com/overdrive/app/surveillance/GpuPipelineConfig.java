package com.overdrive.app.surveillance;

import android.media.MediaFormat;

/**
 * GpuPipelineConfig - Configuration for GPU surveillance pipeline.
 * 
 * Supports:
 * - Recording quality (Normal/Sentry modes with different FPS/bitrate)
 * - Streaming quality (HQ/LQ with different resolutions/FPS)
 * - Configurable bitrate (2, 3, 6 Mbps)
 * - Codec selection (H.264/H.265)
 * - Dynamic reconfiguration
 */
public class GpuPipelineConfig {
    
    // Video codec selection
    public enum VideoCodec {
        H264(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264"),
        H265(MediaFormat.MIMETYPE_VIDEO_HEVC, "H.265/HEVC");
        
        public final String mimeType;
        public final String displayName;
        
        VideoCodec(String mimeType, String displayName) {
            this.mimeType = mimeType;
            this.displayName = displayName;
        }
    }
    
    // Recording quality — single user-facing knob that bundles bitrate +
    // user-readable expectations. FPS is configured separately via
    // camera.targetFps so the user can pick e.g. PREMIUM @ 15 fps for
    // "archival without smoothness" or HIGH @ 30 fps for "smooth daily".
    //
    // Bitrate sizing rationale at 2560×1920 (4.9 megapixels per frame):
    //   - H.265 needs ~0.04 bpp for "good" quality, ~0.10 bpp for "evidence"
    //   - At 15 fps: 0.04 bpp × 5 MP × 15 = 3 Mbps minimum for "good"
    //   - At 30 fps: encoder spreads bits over 2× frames so per-frame detail
    //     drops at fixed bitrate — bump tier when going to higher fps
    //
    // H.265 / H.264 split: H.265 gets ~50% better compression at same
    // perceived quality, so H.264 columns are ~1.5× the H.265 column.
    public enum RecordingQuality {
        // ~7.5 MB/min @ H.265, archival multi-day SD lifespan
        ECONOMY (1_000_000,  1_500_000, "Economy"),
        // ~15 MB/min @ H.265, daily driver default
        STANDARD(2_000_000,  3_000_000, "Standard"),
        // ~30 MB/min @ H.265, fine textures readable
        HIGH    (4_000_000,  6_000_000, "High"),
        // ~45 MB/min @ H.265, evidence-grade
        PREMIUM (6_000_000,  9_000_000, "Premium"),
        // ~75 MB/min @ H.265, hardware ceiling
        MAX     (10_000_000, 15_000_000, "Max"),
        // ~90 MB/min @ H.265, 4K Ultra-HD native sensor resolution (100% native pixels)
        ULTRA_4K(12_000_000, 18_000_000, "Ultra 4K");

        public final int bitrateH265;
        public final int bitrateH264;
        public final String displayName;

        RecordingQuality(int bitrateH265, int bitrateH264, String displayName) {
            this.bitrateH265 = bitrateH265;
            this.bitrateH264 = bitrateH264;
            this.displayName = displayName;
        }

        /** Resolved bitrate (bps) for the given codec. */
        public int getBitrateForCodec(VideoCodec codec) {
            return codec == VideoCodec.H265 ? bitrateH265 : bitrateH264;
        }

        /** Effective-bitrate calibration. Measured on-device with MAX H.265
         *  configured @ 10 Mbps ceiling:
         *    sample 1: 38.4 MB / 30 s = 10.24 Mbps
         *    sample 2: 21.9 MB / 17 s = 10.31 Mbps
         *  Average ≈ 10.27 Mbps = 103% of ceiling — the encoder treats
         *  KEY_BIT_RATE as a target, not a cap, and briefly overshoots on
         *  keyframes. Use 1.0 so the storage forecast matches what users
         *  actually see on disk; rounding the displayed values down (Math.round
         *  in QualitySettingsApiHandler) keeps the headline conservative.
         *  H.264 tracks its cap similarly closely. */
        private static final double H265_EFFECTIVE_FACTOR = 1.0;
        private static final double H264_EFFECTIVE_FACTOR = 1.0;

        /** Effective bitrate (bps) — what the encoder typically produces,
         *  not the KEY_BIT_RATE ceiling. Used for storage estimates. */
        public double effectiveBitrateForCodec(VideoCodec codec) {
            int ceiling = getBitrateForCodec(codec);
            double factor = (codec == VideoCodec.H265) ? H265_EFFECTIVE_FACTOR : H264_EFFECTIVE_FACTOR;
            return ceiling * factor;
        }

        /** Estimated size per minute in MB for the given codec. Independent
         *  of FPS — bitrate is bandwidth-per-second. Uses effective rate so
         *  the user's storage forecast matches what they'll actually see. */
        public double estimateMbPerMinute(VideoCodec codec) {
            return (effectiveBitrateForCodec(codec) * 60.0) / 8.0 / 1024.0 / 1024.0;
        }

        /** Estimated size per hour in MB for the given codec. */
        public double estimateMbPerHour(VideoCodec codec) {
            return estimateMbPerMinute(codec) * 60.0;
        }

        /** Display string showing tier name + bitrate for the given codec. */
        public String getDisplayString(VideoCodec codec) {
            int br = getBitrateForCodec(codec);
            return displayName + " (" + formatMbps(br) + " Mbps)";
        }

        private static String formatMbps(int bps) {
            double m = bps / 1_000_000.0;
            return m == Math.floor(m) ? String.valueOf((int) m) : String.format("%.1f", m);
        }

        public static RecordingQuality fromString(String name) {
            if (name == null) return STANDARD;
            switch (name.toUpperCase()) {
                case "ECONOMY": return ECONOMY;
                case "STANDARD": return STANDARD;
                case "HIGH": return HIGH;
                case "PREMIUM": return PREMIUM;
                case "MAX": return MAX;
                case "ULTRA_4K":
                case "ULTRA":
                case "4K":
                case "ULTRA_4K_HEVC":
                    return ULTRA_4K;
                default: return STANDARD;
            }
        }

        /**
         * Approximate perceptual equivalence to a familiar resolution at the
         * given codec + fps. Returns a human-readable label like "~1080p".
         *
         * Computed via bits-per-pixel-frame (bpp = bitrate / (frameW × frameH × fps))
         * and benchmarked against industry-standard "good quality" bitrates:
         *   480p ≈ 0.014 bpp at H.265
         *   720p ≈ 0.027 bpp
         *   1080p ≈ 0.054 bpp
         *   1440p ≈ 0.081 bpp
         *
         * Native frame is fixed at 2560×1920 (4.92 MP, 4:3). The label is
         * capped at "~1440p" — the mosaic is physically incapable of 4K
         * (3840×2160) detail because it's stitched from four 1280×960
         * camera feeds, so any "4K-equivalent" label would be misleading
         * marketing. Higher fps reduces bpp at fixed bitrate, so the
         * equivalent shifts down one tier when going from 15 → 30 fps.
         */
        public String getQualityEquivalent(VideoCodec codec, int fps) {
            if (this == ULTRA_4K) return "4K UHD";
            int br = getBitrateForCodec(codec);
            // Frame size: mosaic is 2560×1920 = 4.92 MP per frame.
            double bpp = (double) br / (2560.0 * 1920.0 * Math.max(1, fps));
            if (bpp < 0.012)  return "~SD";
            if (bpp < 0.022)  return "~480p";
            if (bpp < 0.040)  return "~720p";
            if (bpp < 0.067)  return "~1080p";
            return "~1440p";
        }
    }

    /** @deprecated use RecordingQuality. Kept as a thin alias so older call
     *  sites compile until they're migrated. */
    @Deprecated
    public enum BitratePreset {
        LOW(RecordingQuality.ECONOMY),
        MEDIUM(RecordingQuality.STANDARD),
        HIGH(RecordingQuality.HIGH);

        public final RecordingQuality quality;
        public final int bitrate;

        BitratePreset(RecordingQuality q) {
            this.quality = q;
            this.bitrate = q.bitrateH264;
        }

        public int getBitrateForCodec(VideoCodec codec) {
            return quality.getBitrateForCodec(codec);
        }

        public String getDisplayString(VideoCodec codec) {
            return quality.getDisplayString(codec);
        }

        public static BitratePreset fromBitrate(int bitrate) {
            if (bitrate <= 2_500_000) return LOW;
            if (bitrate <= 4_500_000) return MEDIUM;
            return HIGH;
        }
    }
    
    // Recording modes (legacy - now uses BitratePreset).
    // FPS values are *defaults per mode*; user-configurable override is
    // honored via UnifiedConfig camera.targetFps and applyFpsChange().
    public enum RecordingMode {
        NORMAL(15, 6_000_000),       // 15 FPS, 6 Mbps
        SENTRY(10, 2_000_000),       // 10 FPS, 2 Mbps (idle)
        SENTRY_EVENT(10, 5_000_000), // 10 FPS, 5 Mbps (event)
        HIGH_FPS(25, 6_000_000),     // 25 FPS for high-motion capture
        MAX_FPS(30, 8_000_000);      // 30 FPS, HAL ceiling on this device

        public final int fps;
        public final int bitrate;

        RecordingMode(int fps, int bitrate) {
            this.fps = fps;
            this.bitrate = bitrate;
        }
    }
    
    // Streaming quality presets (optimized for various network conditions)
    public enum StreamingQuality {
        // Ultra Low: 400 kbps - prioritize resolution over FPS for surveillance
        // Higher resolution at lower FPS looks better than low-res at higher FPS
        ULTRA_LOW(480, 360, 5, 400_000, "Ultra Low (400k)"),
        
        // Low: 600 kbps - for slow connections
        LOW(640, 480, 8, 600_000, "Low (600k)"),
        
        // Medium: 1 Mbps - balanced quality/bandwidth (default)
        MEDIUM(800, 600, 10, 1_000_000, "Medium (1M)"),
        
        // High: 1.5 Mbps - good quality
        HIGH(960, 720, 12, 1_500_000, "High (1.5M)"),
        
        // Ultra High: 2.5 Mbps - best quality
        ULTRA_HIGH(1280, 960, 15, 2_500_000, "Ultra (2.5M)"),

        // Smooth: 1280×960 @ 25 fps, 3.5 Mbps - high motion clarity
        SMOOTH(1280, 960, 25, 3_500_000, "Smooth (3.5M)"),

        // Max: 1280×960 @ 30 fps, 5 Mbps - HAL ceiling on this device, LAN only
        MAX(1280, 960, 30, 5_000_000, "Max (5M)");
        
        // Legacy aliases
        public static final StreamingQuality LQ = LOW;
        public static final StreamingQuality HQ = HIGH;
        
        public final int width;
        public final int height;
        public final int fps;
        public final int bitrate;
        public final String displayName;
        
        StreamingQuality(int width, int height, int fps, int bitrate, String displayName) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrate = bitrate;
            this.displayName = displayName;
        }
        
        public static StreamingQuality fromString(String name) {
            if (name == null) return MEDIUM;
            switch (name.toUpperCase()) {
                case "ULTRA_LOW": return ULTRA_LOW;
                case "LOW":
                case "LQ": return LOW;
                case "MEDIUM": return MEDIUM;
                case "HIGH":
                case "HQ": return HIGH;
                case "ULTRA_HIGH": return ULTRA_HIGH;
                case "SMOOTH": return SMOOTH;
                case "MAX": return MAX;
                default: return MEDIUM;
            }
        }
    }
    
    // Current configuration
    private RecordingMode recordingMode = RecordingMode.NORMAL;
    private StreamingQuality streamingQuality = StreamingQuality.HQ;
    
    // New configurable settings
    private VideoCodec videoCodec = VideoCodec.H264;  // Default H.264 for compatibility
    // Single user-facing quality knob (replaces legacy bitratePreset string).
    // Resolved bitrate = recordingQuality.getBitrateForCodec(videoCodec).
    // Default STANDARD per the migration policy: existing settings reset to
    // STANDARD on first load after upgrade.
    private RecordingQuality recordingQuality = RecordingQuality.STANDARD;
    /** @deprecated mirrors recordingQuality, kept until call sites migrate. */
    @Deprecated
    private BitratePreset bitratePreset = BitratePreset.MEDIUM;
    private int customBitrate = 0;  // 0 = derive from recordingQuality + codec
    
    // AI configuration
    private boolean aiEnabled = true;
    private float sadThreshold = 0.05f;
    private boolean grayscaleAi = false;
    
    /**
     * Gets the current recording mode.
     */
    public RecordingMode getRecordingMode() {
        return recordingMode;
    }
    
    /**
     * Sets the recording mode.
     */
    public void setRecordingMode(RecordingMode mode) {
        this.recordingMode = mode;
    }
    
    /**
     * Gets the current streaming quality.
     */
    public StreamingQuality getStreamingQuality() {
        return streamingQuality;
    }
    
    /**
     * Sets the streaming quality.
     */
    public void setStreamingQuality(StreamingQuality quality) {
        this.streamingQuality = quality;
    }
    
    /**
     * Checks if AI is enabled.
     */
    public boolean isAiEnabled() {
        return aiEnabled;
    }
    
    /**
     * Sets AI enabled state.
     */
    public void setAiEnabled(boolean enabled) {
        this.aiEnabled = enabled;
    }
    
    /**
     * Gets the SAD threshold.
     */
    public float getSadThreshold() {
        return sadThreshold;
    }
    
    /**
     * Sets the SAD threshold.
     */
    public void setSadThreshold(float threshold) {
        this.sadThreshold = threshold;
    }
    
    /**
     * Checks if grayscale AI mode is enabled.
     */
    public boolean isGrayscaleAi() {
        return grayscaleAi;
    }
    
    /**
     * Sets grayscale AI mode.
     */
    public void setGrayscaleAi(boolean enabled) {
        this.grayscaleAi = enabled;
    }
    
    // ==================== NEW CODEC/BITRATE SETTINGS ====================
    
    /**
     * Gets the current video codec.
     */
    public VideoCodec getVideoCodec() {
        return videoCodec;
    }
    
    /**
     * Sets the video codec (H.264 or H.265).
     * H.265 provides ~50% better compression but requires hardware support.
     * Bitrate is automatically adjusted to maintain equivalent quality.
     */
    public void setVideoCodec(VideoCodec codec) {
        this.videoCodec = codec;
        // Codec change re-derives bitrate from the active quality tier.
        this.customBitrate = 0;  // re-derive via getEffectiveBitrate
    }

    /** Gets the user-selected recording quality tier. */
    public RecordingQuality getRecordingQuality() {
        return recordingQuality;
    }

    /** Sets the recording quality tier and clears any custom-bitrate override
     *  so the new tier's bitrate (resolved against the current codec) takes
     *  effect on the next encoder reinit. */
    public void setRecordingQuality(RecordingQuality quality) {
        this.recordingQuality = quality;
        this.customBitrate = 0;
        // Keep legacy bitratePreset alias roughly in sync for any code that
        // still reads it. Map ECONOMY/STANDARD → LOW/MEDIUM, the rest → HIGH.
        switch (quality) {
            case ECONOMY:  this.bitratePreset = BitratePreset.LOW; break;
            case STANDARD: this.bitratePreset = BitratePreset.MEDIUM; break;
            default:       this.bitratePreset = BitratePreset.HIGH; break;
        }
    }

    /** Checks if 4K Ultra-HD native sensor recording quality is selected. */
    public boolean is4K() {
        return recordingQuality == RecordingQuality.ULTRA_4K;
    }
    
    /**
     * Gets the current bitrate preset.
     */
    public BitratePreset getBitratePreset() {
        return bitratePreset;
    }
    
    /**
     * Sets the bitrate preset.
     * The actual bitrate will be adjusted based on the selected codec.
     * Clears any custom bitrate so the preset takes effect.
     */
    public void setBitratePreset(BitratePreset preset) {
        this.bitratePreset = preset;
        // Set custom bitrate to the codec-aware value from the preset
        this.customBitrate = preset.getBitrateForCodec(videoCodec);
    }
    
    /**
     * Gets the effective bitrate in bps. Order of precedence:
     *   1. customBitrate if explicitly set (>0) — used by AdaptiveBitrate
     *      controller when scaling for thermals or network conditions.
     *   2. The current RecordingQuality tier resolved against the active codec.
     */
    public int getEffectiveBitrate() {
        return getEffectiveBitrateForQuality(recordingQuality);
    }

    /**
     * Gets the effective bitrate (bps) for an EXPLICIT quality tier, applying
     * the same precedence as {@link #getEffectiveBitrate()}:
     *   1. customBitrate if explicitly set (>0) — AdaptiveBitrate controller's
     *      thermal/network scaling. This ALWAYS wins, for both the ACC-on and
     *      ACC-off (surveillance) flows, so a thermal throttle is never
     *      overridden by a per-mode tier.
     *   2. The supplied tier resolved against the active (shared) codec.
     *
     * <p>Used by the surveillance flow to resolve the ACC-off tier
     * (recording.surveillanceQuality) without disturbing the ACC-on
     * recordingQuality field. {@code quality} must be non-null; a null (e.g. a
     * failed enum parse upstream) falls back to the configured recordingQuality
     * so the caller can never accidentally zero the bitrate.
     */
    public int getEffectiveBitrateForQuality(RecordingQuality quality) {
        if (customBitrate > 0) {
            return customBitrate;
        }
        RecordingQuality q = (quality != null) ? quality : recordingQuality;
        return q.getBitrateForCodec(videoCodec);
    }
    
    /**
     * Sets a custom bitrate in bps directly (bypasses preset).
     * Used when applying bitrate changes at runtime.
     */
    public void setCustomBitrate(int bitrate) {
        this.customBitrate = bitrate;
        // Don't change the preset - just store the custom value
    }
    
    /**
     * Gets the MIME type for the current codec.
     */
    public String getCodecMimeType() {
        return videoCodec.mimeType;
    }
}
