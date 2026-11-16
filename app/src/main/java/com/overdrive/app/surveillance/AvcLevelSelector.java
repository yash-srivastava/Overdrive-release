package com.overdrive.app.surveillance;

import android.media.MediaCodecInfo;

/**
 * Selects the lowest advertised AVC level that can represent the requested
 * picture size, frame rate and bitrate. AVC level values are bit flags, not
 * ordinal numbers, so they must never be compared numerically.
 */
final class AvcLevelSelector {
    private AvcLevelSelector() {}

    private static final class Limit {
        final int value;
        final long maxMacroblocksPerSecond;
        final long maxMacroblocksPerFrame;
        final long maxBitrate;

        Limit(int value, long mbps, long mbpf, long bitrate) {
            this.value = value;
            this.maxMacroblocksPerSecond = mbps;
            this.maxMacroblocksPerFrame = mbpf;
            this.maxBitrate = bitrate;
        }
    }

    // MaxBR values below are the AVC Baseline/Main-profile limits (kbps × 1000).
    // Values follow ITU-T H.264 Annex A. Keep this ordered by capability, not
    // by the integer value of Android's level bit flags.
    private static final Limit[] LIMITS = {
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel1, 1_485, 99, 64_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel1b, 1_485, 99, 128_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel11, 3_000, 396, 192_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel12, 6_000, 396, 384_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel13, 11_880, 396, 768_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel2, 11_880, 396, 2_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel21, 19_800, 792, 4_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel22, 20_250, 1_620, 4_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel3, 40_500, 1_620, 10_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel31, 108_000, 3_600, 14_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel32, 216_000, 5_120, 20_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel4, 245_760, 8_192, 20_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel41, 245_760, 8_192, 50_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel42, 522_240, 8_704, 50_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel5, 589_824, 22_080, 135_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel51, 983_040, 36_864, 240_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel52, 2_073_600, 36_864, 240_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel6, 4_177_920, 139_264, 240_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel61, 8_355_840, 139_264, 480_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.AVCLevel62, 16_711_680, 139_264, 800_000_000)
    };

    /**
     * @return Android AVC level flag, or 0 when the dimensions/rate/bitrate
     *         cannot be represented by a known level or that level is not
     *         advertised by the selected encoder.
     */
    static int selectAdvertisedBaselineLevel(int width, int height, int fps,
                                             int bitrate, int[] advertisedLevels) {
        if (width <= 0 || height <= 0 || fps <= 0 || bitrate <= 0
                || advertisedLevels == null || advertisedLevels.length == 0) {
            return 0;
        }
        long mbWidth = (width + 15L) / 16L;
        long mbHeight = (height + 15L) / 16L;
        long macroblocksPerFrame = mbWidth * mbHeight;
        long macroblocksPerSecond = macroblocksPerFrame * fps;

        // Vendor CodecProfileLevel entries can describe the maximum level
        // supported by a profile. A stream needing a lower level is covered
        // by that maximum; requiring an exact equality rejects valid encoders.
        int maximumAdvertisedRank = -1;
        for (int advertised : advertisedLevels) {
            for (int i = 0; i < LIMITS.length; i++) {
                if (LIMITS[i].value == advertised) {
                    maximumAdvertisedRank = Math.max(maximumAdvertisedRank, i);
                    break;
                }
            }
        }
        if (maximumAdvertisedRank < 0) return 0;

        for (int i = 0; i <= maximumAdvertisedRank; i++) {
            Limit limit = LIMITS[i];
            if (macroblocksPerFrame <= limit.maxMacroblocksPerFrame
                    && macroblocksPerSecond <= limit.maxMacroblocksPerSecond
                    && bitrate <= limit.maxBitrate) {
                return limit.value;
            }
        }
        return 0;
    }

    static String describe(int level) {
        for (Limit limit : LIMITS) {
            if (limit.value == level) {
                // Android uses level flags; the name is the stable diagnostic.
                switch (level) {
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel1: return "1";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel1b: return "1b";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel11: return "1.1";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel12: return "1.2";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel13: return "1.3";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel2: return "2";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel21: return "2.1";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel22: return "2.2";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel3: return "3";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel31: return "3.1";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel32: return "3.2";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel4: return "4";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel41: return "4.1";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel42: return "4.2";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel5: return "5";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel51: return "5.1";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel52: return "5.2";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel6: return "6";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel61: return "6.1";
                    case MediaCodecInfo.CodecProfileLevel.AVCLevel62: return "6.2";
                    default: break;
                }
            }
        }
        return "unknown(" + level + ")";
    }
}
