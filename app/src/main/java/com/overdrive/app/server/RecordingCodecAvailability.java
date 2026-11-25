package com.overdrive.app.server;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.surveillance.GpuPipelineConfig;
import com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality;
import com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Advertised GPU recording capabilities, not proof that an encoder can start. */
final class RecordingCodecAvailability {
    private static final Cache CACHE = new Cache(Executors.newSingleThreadExecutor(r -> {
        Thread worker = new Thread(r, "RecordingCodecCapabilities");
        worker.setDaemon(true);
        return worker;
    }));

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

    // At most one outstanding probe, even if a vendor codec service hangs.
    // The request thread only touches this small state; it never waits for a probe.
    static final class Cache {
        private final Executor executor;
        private String key;
        private Result result;
        private boolean running;

        Cache(Executor executor) {
            this.executor = executor;
        }

        synchronized Result get(String requestedKey, Supplier<Result> probe) {
            if (!requestedKey.equals(key)) {
                key = requestedKey;
                result = null;
            }
            if (result != null) return result;
            if (!running) {
                running = true;
                try {
                    executor.execute(() -> {
                        Result value = Result.UNKNOWN;
                        try {
                            value = probe.get();
                        } finally {
                            synchronized (Cache.this) {
                                if (requestedKey.equals(key)) result = value;
                                running = false;
                            }
                        }
                    });
                } catch (RuntimeException e) {
                    running = false;
                    result = Result.UNKNOWN;
                }
            }
            return Result.UNKNOWN;
        }
    }

    static int bitrate(VideoCodec codec, int fps, RecordingQuality tier, int override) {
        return GpuPipelineConfig.resolveRecordingBitrate(tier, codec, fps, override);
    }

    static Result get(int width, int height, int driveFps, int parkedFps,
            RecordingQuality driveTier, RecordingQuality parkedTier, int override,
            String liveMime, String liveName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || width <= 0 || height <= 0
                || driveFps <= 0 || parkedFps <= 0) return Result.UNKNOWN;
        String key = width + "x" + height + ":" + driveFps + ":" + parkedFps
                + ":" + driveTier.name() + ":" + parkedTier.name() + ":" + override
                + ":" + liveMime + ":" + liveName;
        return CACHE.get(key, () -> probe(key, width, height, driveFps, parkedFps,
                driveTier, parkedTier, override, liveMime, liveName));
    }

    private static Result probe(String key, int width, int height, int driveFps, int parkedFps,
            RecordingQuality driveTier, RecordingQuality parkedTier, int override,
            String liveMime, String liveName) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            Result result = new Result(
                    supports(list, VideoCodec.H264, width, height, driveFps, parkedFps, driveTier, parkedTier, override,
                            VideoCodec.H264.mimeType.equals(liveMime) ? liveName : null),
                    supports(list, VideoCodec.H265, width, height, driveFps, parkedFps, driveTier, parkedTier, override,
                            VideoCodec.H265.mimeType.equals(liveMime) ? liveName : null));
            CameraDaemon.log("Recording codec capabilities " + key + ": H264=" + result.h264
                    + ", H265=" + result.h265);
            return result;
        } catch (RuntimeException | LinkageError e) {
            CameraDaemon.log("Recording codec capability enumeration failed: " + e.getMessage());
            return Result.UNKNOWN;
        }
    }

    private static Boolean supports(MediaCodecList list, VideoCodec codec, int width, int height,
            int driveFps, int parkedFps, RecordingQuality driveTier, RecordingQuality parkedTier,
            int override, String liveName) {
        try {
            // createEncoderByType selects by MIME, not by the eventual recording format.
            // Query the preferred regular encoder with MIME alone; never skip an unsupported
            // first choice in favor of a later hardware codec. When the recorder has
            // confirmed output, use its actual implementation instead of the predicted one.
            // Do not open a competing encoder just to query capabilities.
            MediaFormat mimeOnly = new MediaFormat();
            mimeOnly.setString(MediaFormat.KEY_MIME, codec.mimeType);
            String name = liveName != null ? liveName : list.findEncoderForFormat(mimeOnly);
            if (name == null) return false;
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!name.equals(info.getName())) continue;
                if (!info.isEncoder() || info.isSoftwareOnly() || !info.isHardwareAccelerated()) return false;
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(codec.mimeType);
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video == null) return false;
                boolean surface = false;
                for (int color : caps.colorFormats) {
                    if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                        surface = true;
                        break;
                    }
                }
                int driveBps = bitrate(codec, driveFps, driveTier, override);
                int parkedBps = bitrate(codec, parkedFps, parkedTier, override);
                Boolean supported = surface && video.areSizeAndRateSupported(width, height, driveFps)
                        && video.areSizeAndRateSupported(width, height, parkedFps)
                        && video.getBitrateRange().contains(driveBps)
                        && video.getBitrateRange().contains(parkedBps)
                        && caps.isFormatSupported(format(codec, width, height, driveFps, driveBps))
                        && caps.isFormatSupported(format(codec, width, height, parkedFps, parkedBps));
                CameraDaemon.log("Preferred recording encoder " + name + " " + codec.name()
                        + ": drive=" + driveBps + "bps, parked=" + parkedBps + "bps, advertised=" + supported);
                return supported;
            }
            return null; // preferred encoder disappeared from the list
        } catch (RuntimeException | LinkageError e) {
            CameraDaemon.log("Recording codec capability query failed for " + codec.name()
                    + ": " + e.getMessage());
            return null;
        }
    }

    private static MediaFormat format(VideoCodec codec, int width, int height, int fps, int bitrate) {
        MediaFormat format = MediaFormat.createVideoFormat(codec.mimeType, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_PROFILE, codec == VideoCodec.H265
                ? MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                : MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        format.setInteger(MediaFormat.KEY_LEVEL, codec == VideoCodec.H265
                ? MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4
                : MediaCodecInfo.CodecProfileLevel.AVCLevel31);
        return format;
    }
}
