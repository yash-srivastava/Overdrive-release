package com.overdrive.app.server;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality;
import com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec;

import java.util.function.Supplier;

/** Advertised GPU recording capabilities, not proof that an encoder can start. */
final class RecordingCodecAvailability {
    private static final Cache CACHE = new Cache();

    private RecordingCodecAvailability() {}

    static final class Result {
        static final Result UNKNOWN = new Result(null, null);
        final Boolean h264;
        final Boolean h265;

        Result(Boolean h264, Boolean h265) {
            this.h264 = h264;
            this.h265 = h265;
        }
    }

    // One immutable snapshot, shared by concurrent settings requests. Settings
    // changes invalidate it; restarting the daemon refreshes platform capabilities.
    static final class Cache {
        private String key;
        private Result result;

        synchronized Result get(String requestedKey, Supplier<Result> probe) {
            if (!requestedKey.equals(key)) {
                result = probe.get();
                key = requestedKey;
            }
            return result;
        }
    }

    static Result get(int width, int height, int driveFps, int parkedFps,
            RecordingQuality driveTier, RecordingQuality parkedTier) {
        String key = width + "x" + height + ":" + driveFps + ":" + parkedFps
                + ":" + driveTier.name() + ":" + parkedTier.name();
        return CACHE.get(key, () -> probe(key, width, height, driveFps, parkedFps,
                driveTier, parkedTier));
    }

    private static Result probe(String key, int width, int height, int driveFps, int parkedFps,
            RecordingQuality driveTier, RecordingQuality parkedTier) {
        // API 28 cannot reliably distinguish vendor software from hardware codecs.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || width <= 0 || height <= 0
                || driveFps <= 0 || parkedFps <= 0) {
            return Result.UNKNOWN;
        }
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            Result result = new Result(
                    supports(infos, VideoCodec.H264, width, height, driveFps, parkedFps, driveTier, parkedTier),
                    supports(infos, VideoCodec.H265, width, height, driveFps, parkedFps, driveTier, parkedTier));
            CameraDaemon.log("Recording codec capabilities " + key + ": H264=" + result.h264
                    + ", H265=" + result.h265);
            return result;
        } catch (RuntimeException | LinkageError e) {
            CameraDaemon.log("Recording codec capability enumeration failed: " + e.getMessage());
            return Result.UNKNOWN;
        }
    }

    private static Boolean supports(MediaCodecInfo[] infos, VideoCodec codec, int width, int height,
            int driveFps, int parkedFps, RecordingQuality driveTier, RecordingQuality parkedTier) {
        boolean uncertain = false;
        MediaFormat drive = format(codec, width, height, driveFps, driveTier);
        MediaFormat parked = format(codec, width, height, parkedFps, parkedTier);
        for (MediaCodecInfo info : infos) {
            try {
                if (!info.isEncoder() || info.isSoftwareOnly() || !info.isHardwareAccelerated()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!codec.mimeType.equalsIgnoreCase(type)) continue;
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                    if (video == null) continue;
                    // Require one hardware encoder to advertise Surface input and both saved
                    // operating points. Do not combine capabilities from different encoders.
                    boolean surface = false;
                    for (int color : caps.colorFormats) {
                        if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                            surface = true;
                            break;
                        }
                    }
                    if (surface && video.areSizeAndRateSupported(width, height, driveFps)
                            && video.areSizeAndRateSupported(width, height, parkedFps)
                            && video.getBitrateRange().contains(driveTier.getBitrateForCodec(codec, driveFps))
                            && video.getBitrateRange().contains(parkedTier.getBitrateForCodec(codec, parkedFps))
                            && caps.isFormatSupported(drive) && caps.isFormatSupported(parked)) {
                        return true;
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                uncertain = true;
                CameraDaemon.log("Recording codec capability query failed for " + codec.name()
                        + ": " + e.getMessage());
            }
        }
        return uncertain ? null : Boolean.FALSE;
    }

    private static MediaFormat format(VideoCodec codec, int width, int height, int fps,
            RecordingQuality tier) {
        MediaFormat format = MediaFormat.createVideoFormat(codec.mimeType, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_BIT_RATE, tier.getBitrateForCodec(codec, fps));
        return format;
    }
}
