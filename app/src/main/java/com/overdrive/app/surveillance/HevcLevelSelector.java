package com.overdrive.app.surveillance;

import android.media.MediaCodecInfo;

/** Selects a supported HEVC Main-tier level using picture, sample-rate and bitrate limits. */
final class HevcLevelSelector {
    private HevcLevelSelector() {}

    private static final class Limit {
        final int value;
        final long maxLumaSamplesPerPicture;
        final long maxLumaSamplesPerSecond;
        final long maxBitrate;

        Limit(int value, long picture, long perSecond, long bitrate) {
            this.value = value;
            this.maxLumaSamplesPerPicture = picture;
            this.maxLumaSamplesPerSecond = perSecond;
            this.maxBitrate = bitrate;
        }
    }

    // HEVC Main-tier limits from ITU-T H.265. Android's level constants are
    // bit flags and are deliberately kept in capability order here.
    private static final Limit[] LIMITS = {
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1, 36_864, 552_960, 128_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2, 122_880, 3_686_400, 1_500_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21, 245_760, 7_372_800, 3_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3, 552_960, 16_588_800, 6_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31, 983_040, 33_177_600, 10_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4, 2_228_224, 66_846_720, 12_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41, 2_228_224, 133_693_440, 20_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5, 8_912_896, 267_386_880, 25_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51, 8_912_896, 534_773_760, 40_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52, 8_912_896, 1_069_547_520, 60_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6, 35_651_584, 1_069_547_520, 60_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61, 35_651_584, 2_139_095_040, 120_000_000),
        new Limit(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62, 35_651_584, 4_278_190_080L, 240_000_000)
    };

    /** @return lowest advertised Main-tier level that covers the requested stream, or 0. */
    static int selectAdvertisedMainTierLevel(int width, int height, int fps,
                                             int bitrate, int[] advertisedLevels) {
        if (width <= 0 || height <= 0 || fps <= 0 || bitrate <= 0
                || advertisedLevels == null || advertisedLevels.length == 0) {
            return 0;
        }
        long lumaSamplesPerPicture = (long) width * height;
        long lumaSamplesPerSecond = lumaSamplesPerPicture * fps;
        for (Limit limit : LIMITS) {
            if (lumaSamplesPerPicture > limit.maxLumaSamplesPerPicture
                    || lumaSamplesPerSecond > limit.maxLumaSamplesPerSecond
                    || bitrate > limit.maxBitrate) {
                continue;
            }
            for (int advertised : advertisedLevels) {
                if (advertised == limit.value) return limit.value;
            }
        }
        return 0;
    }

    static String describe(int level) {
        for (Limit limit : LIMITS) {
            if (limit.value == level) {
                switch (level) {
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1: return "1";
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2: return "2";
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21: return "2.1";
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3: return "3";
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31: return "3.1";
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4: return "4";
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41: return "4.1";
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5: return "5";
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51: return "5.1";
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52: return "5.2";
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6: return "6";
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61: return "6.1";
                    case MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62: return "6.2";
                    default: break;
                }
            }
        }
        return "unknown(" + level + ")";
    }
}
