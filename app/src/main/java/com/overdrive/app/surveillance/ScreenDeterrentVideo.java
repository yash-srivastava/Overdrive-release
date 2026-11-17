package com.overdrive.app.surveillance;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;

import com.overdrive.app.logging.DaemonLogger;

import java.io.FileInputStream;
import java.nio.ByteBuffer;

/** Hardware-decoded, muted MP4 playback on the daemon-owned deterrent layer. */
public final class ScreenDeterrentVideo {

    private static final DaemonLogger logger =
            DaemonLogger.getInstance("ScreenDeterrentVideo");
    private static final long DEQUEUE_TIMEOUT_US = 5_000;
    private static final long DEFAULT_FRAME_GAP_US = 33_333;
    private static final long STOP_POLL_INTERVAL_MS = 200;

    private ScreenDeterrentVideo() {}

    public interface StopSignal {
        boolean shouldStop();
    }

    /** ISO-BMFF {@code ftyp} magic. Upload names and MIME types are untrusted. */
    public static boolean isMp4(byte[] data) {
        return data != null
                && data.length >= 12
                && data[4] == 'f'
                && data[5] == 't'
                && data[6] == 'y'
                && data[7] == 'p';
    }

    public static boolean isMp4File(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] header = new byte[12];
            int offset = 0;
            while (offset < header.length) {
                int read = in.read(header, offset, header.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return offset == header.length && isMp4(header);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static final class Probe {
        public final String mime;
        public final int width;
        public final int height;
        public final int visibleWidth;
        public final int visibleHeight;
        public final long durationUs;
        public final MediaFormat format;

        Probe(String mime, int width, int height,
              int visibleWidth, int visibleHeight,
              long durationUs, MediaFormat format) {
            this.mime = mime;
            this.width = width;
            this.height = height;
            this.visibleWidth = visibleWidth;
            this.visibleHeight = visibleHeight;
            this.durationUs = durationUs;
            this.format = format;
        }
    }

    /** Reads the same video-track metadata used by playback. */
    public static Probe probe(String path) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int track = selectVideoTrack(extractor);
            if (track < 0) return null;
            MediaFormat format = extractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) return null;
            return new Probe(
                    mime,
                    codedWidth(format),
                    codedHeight(format),
                    displayWidth(format),
                    displayHeight(format),
                    trackDurationUs(format),
                    format);
        } catch (Throwable t) {
            logger.debug("Deterrent video probe failed: " + t.getMessage());
            return null;
        } finally {
            if (extractor != null) {
                try { extractor.release(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Loops the video until the supplied stop predicate fires. Audio tracks are
     * never selected, so playback is always silent. {@code onShown} runs once,
     * after the first frame is released to the layer.
     */
    public static boolean play(String path, int displayWidth, int displayHeight,
                               StopSignal stop, Runnable onFrame, Runnable onShown) {
        if (path == null || path.isEmpty() || displayWidth <= 0 || displayHeight <= 0) {
            return false;
        }
        if (stop == null || stop.shouldStop()) return false;

        MediaExtractor extractor = null;
        MediaCodec codec = null;
        BsNativeLayer layer = null;
        boolean codecStarted = false;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int track = selectVideoTrack(extractor);
            if (track < 0) return false;
            extractor.selectTrack(track);

            MediaFormat format = extractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int videoWidth = displayWidth(format);
            int videoHeight = displayHeight(format);
            int rotation = rotationDegrees(format);
            if (mime == null || videoWidth <= 0 || videoHeight <= 0) return false;
            String decoderName = decoderForFormat(format);
            if (decoderName == null) return false;
            if (stop.shouldStop()) return false;

            layer = new BsNativeLayer(
                    videoWidth, videoHeight, "ScreenDeterrentVideo", Integer.MAX_VALUE);
            if (!layer.create()) return false;
            Surface surface = layer.getSurface();
            if (surface == null) return false;

            codec = MediaCodec.createByCodecName(decoderName);
            codec.configure(format, surface, null, 0);
            codec.start();
            codecStarted = true;

            if (stop.shouldStop()) return false;
            layer.setBufferRotation(rotation);
            layer.setGeometry(
                    coverCrop(videoWidth, videoHeight, displayWidth, displayHeight,
                            rotation),
                    0, 0, displayWidth, displayHeight);
            logger.info("Deterrent video: " + videoWidth + "x" + videoHeight
                    + " " + mime + " rotation=" + rotation
                    + " on " + displayWidth + "x" + displayHeight);

            return decodeLoop(codec, extractor, frameGapUs(format),
                    trackDurationUs(format), stop, onFrame, onShown);
        } catch (Throwable t) {
            logger.warn("Deterrent video playback failed: " + t.getMessage());
            return false;
        } finally {
            // Visual teardown must not wait on vendor codec shutdown. Some
            // MediaCodec implementations can block in stop()/release(); hide
            // the z=MAX layer first so input capture can later be released
            // without leaving a visible tap-through surface behind.
            if (layer != null) {
                try { layer.hide(); } catch (Throwable ignored) {}
            }
            if (codec != null) {
                if (codecStarted) {
                    try { codec.stop(); } catch (Throwable ignored) {}
                }
                try { codec.release(); } catch (Throwable ignored) {}
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Throwable ignored) {}
            }
            if (layer != null) layer.release();
        }
    }

    private static boolean decodeLoop(MediaCodec codec, MediaExtractor extractor,
                                      long frameGapUs, long trackDurationUs,
                                      StopSignal stop, Runnable onFrame, Runnable onShown)
            throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ThrottledStop shouldStop = new ThrottledStop(stop);
        long loopOffsetUs = 0;
        long maxSampleUs = 0;
        long anchorNs = -1;
        boolean rendered = false;
        boolean inputDone = false;

        while (!shouldStop.check()) {
            if (!inputDone) {
                int inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                if (inputIndex >= 0) {
                    ByteBuffer input = codec.getInputBuffer(inputIndex);
                    if (input != null) input.clear();
                    int size = input == null ? -1 : extractor.readSampleData(input, 0);
                    if (size < 0) {
                        loopOffsetUs = nextLoopOffsetUs(
                                loopOffsetUs, trackDurationUs,
                                maxSampleUs, frameGapUs);
                        maxSampleUs = 0;
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        if (input != null) input.clear();
                        size = input == null ? -1 : extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                    if (!inputDone && size >= 0) {
                        long sampleUs = Math.max(0, extractor.getSampleTime());
                        maxSampleUs = Math.max(maxSampleUs, sampleUs);
                        codec.queueInputBuffer(inputIndex, 0, size,
                                sampleUs + loopOffsetUs, 0);
                        extractor.advance();
                    }
                }
            }

            int outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            if (outputIndex >= 0) {
                boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (info.size > 0) {
                    if (anchorNs < 0) {
                        anchorNs = System.nanoTime() - info.presentationTimeUs * 1_000L;
                    }
                    if (!waitForFrame(anchorNs + info.presentationTimeUs * 1_000L,
                            shouldStop, onFrame)) {
                        codec.releaseOutputBuffer(outputIndex, false);
                        return rendered;
                    }
                    codec.releaseOutputBuffer(outputIndex, true);
                    if (!rendered && onShown != null) {
                        try { onShown.run(); } catch (Throwable ignored) {}
                    }
                    rendered = true;
                    if (onFrame != null) {
                        try { onFrame.run(); } catch (Throwable ignored) {}
                    }
                } else {
                    codec.releaseOutputBuffer(outputIndex, false);
                }
                if (end) return rendered && holdLastFrame(shouldStop, onFrame);
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                logger.debug("Deterrent video output: " + codec.getOutputFormat());
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER
                    && inputDone && rendered) {
                return holdLastFrame(shouldStop, onFrame);
            }
        }
        return rendered;
    }

    private static boolean holdLastFrame(ThrottledStop shouldStop, Runnable onFrame) {
        while (!shouldStop.check()) {
            if (onFrame != null) {
                try { onFrame.run(); } catch (Throwable ignored) {}
            }
            try {
                Thread.sleep(STOP_POLL_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return true;
    }

    private static boolean waitForFrame(long targetNs, ThrottledStop shouldStop,
                                        Runnable onFrame) {
        while (true) {
            long remainingNs = targetNs - System.nanoTime();
            if (remainingNs <= 0) return true;
            if (shouldStop.check()) return false;
            if (onFrame != null) {
                try { onFrame.run(); } catch (Throwable ignored) {}
            }
            long sleepMs = Math.min(
                    STOP_POLL_INTERVAL_MS, Math.max(1, remainingNs / 1_000_000L));
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static final class ThrottledStop {
        private final StopSignal delegate;
        private long lastCheckMs;
        private boolean stopped;

        ThrottledStop(StopSignal delegate) {
            this.delegate = delegate;
        }

        boolean check() {
            if (stopped || Thread.currentThread().isInterrupted()) return true;
            long now = SystemClock.elapsedRealtime();
            if (lastCheckMs != 0 && now - lastCheckMs < STOP_POLL_INTERVAL_MS) return false;
            lastCheckMs = now;
            try {
                stopped = delegate == null || delegate.shouldStop();
            } catch (Throwable ignored) {
                stopped = true;
            }
            return stopped;
        }
    }

    private static int selectVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }

    private static int displayWidth(MediaFormat format) {
        int coded = codedWidth(format);
        if (coded <= 0) return 0;
        if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
            int left = format.getInteger("crop-left");
            int right = format.getInteger("crop-right");
            if (left < 0 || right < left || right >= coded) return 0;
            return right - left + 1;
        }
        return coded;
    }

    private static int codedWidth(MediaFormat format) {
        return format.containsKey(MediaFormat.KEY_WIDTH)
                ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
    }

    private static int displayHeight(MediaFormat format) {
        int coded = codedHeight(format);
        if (coded <= 0) return 0;
        if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
            int top = format.getInteger("crop-top");
            int bottom = format.getInteger("crop-bottom");
            if (top < 0 || bottom < top || bottom >= coded) return 0;
            return bottom - top + 1;
        }
        return coded;
    }

    private static int codedHeight(MediaFormat format) {
        return format.containsKey(MediaFormat.KEY_HEIGHT)
                ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
    }

    private static long frameGapUs(MediaFormat format) {
        try {
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                int fps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                if (fps > 0) return 1_000_000L / fps;
            }
        } catch (Throwable ignored) {}
        return DEFAULT_FRAME_GAP_US;
    }

    private static long trackDurationUs(MediaFormat format) {
        try {
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                long duration = format.getLong(MediaFormat.KEY_DURATION);
                if (duration > 0) return duration;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    static long nextLoopOffsetUs(long currentOffsetUs, long trackDurationUs,
                                 long maxSampleUs, long frameGapUs) {
        long minimumGapUs = Math.max(1L, frameGapUs);
        long loopSpanUs = Math.max(
                trackDurationUs, maxSampleUs + minimumGapUs);
        return currentOffsetUs + Math.max(minimumGapUs, loopSpanUs);
    }

    private static int rotationDegrees(MediaFormat format) {
        try {
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                int degrees = format.getInteger(MediaFormat.KEY_ROTATION);
                int normalized = ((degrees % 360) + 360) % 360;
                return (Math.round(normalized / 90f) * 90) % 360;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    static int[] coverCropBounds(int videoWidth, int videoHeight,
                                 int displayWidth, int displayHeight) {
        float videoAspect = (float) videoWidth / videoHeight;
        float displayAspect = (float) displayWidth / displayHeight;
        int cropWidth;
        int cropHeight;
        if (videoAspect > displayAspect) {
            cropHeight = videoHeight;
            cropWidth = Math.max(1, Math.round(videoHeight * displayAspect));
        } else {
            cropWidth = videoWidth;
            cropHeight = Math.max(1, Math.round(videoWidth / displayAspect));
        }
        cropWidth = Math.min(cropWidth, videoWidth);
        cropHeight = Math.min(cropHeight, videoHeight);
        int left = (videoWidth - cropWidth) / 2;
        int top = (videoHeight - cropHeight) / 2;
        return new int[] { left, top, left + cropWidth, top + cropHeight };
    }

    static int[] coverCropBounds(int videoWidth, int videoHeight,
                                 int displayWidth, int displayHeight,
                                 int rotation) {
        boolean quarterTurn = rotation == 90 || rotation == 270;
        int orientedWidth = quarterTurn ? videoHeight : videoWidth;
        int orientedHeight = quarterTurn ? videoWidth : videoHeight;
        int[] oriented = coverCropBounds(
                orientedWidth, orientedHeight, displayWidth, displayHeight);
        int cropWidth = oriented[2] - oriented[0];
        int cropHeight = oriented[3] - oriented[1];
        if (quarterTurn) {
            int swap = cropWidth;
            cropWidth = cropHeight;
            cropHeight = swap;
        }
        int left = (videoWidth - cropWidth) / 2;
        int top = (videoHeight - cropHeight) / 2;
        return new int[] { left, top, left + cropWidth, top + cropHeight };
    }

    private static Rect coverCrop(int videoWidth, int videoHeight,
                                  int displayWidth, int displayHeight,
                                  int rotation) {
        int[] bounds = coverCropBounds(
                videoWidth, videoHeight, displayWidth, displayHeight, rotation);
        return new Rect(bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    public static String decoderForFormat(MediaFormat format) {
        if (format == null) return null;
        try {
            return new MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .findDecoderForFormat(format);
        } catch (Throwable t) {
            logger.debug("Decoder probe failed: " + t.getMessage());
            return null;
        }
    }
}
