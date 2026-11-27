package com.overdrive.app.server;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

/** Size-aware extraction for daemon recording thumbnails and timeline stills. */
public final class VideoStillFrame {
    private VideoStillFrame() {}

    /**
     * Returns an owned bitmap at the requested width and height (zero height preserves aspect).
     * The retriever must already have a data source. The caller owns the retriever and result.
     */
    public static Bitmap extract(MediaMetadataRetriever retriever, long timeUs, int option,
                                 int width, int height) {
        DecodeSize size = null;
        Bitmap frame = null;
        try {
            // Rotated clips and uncertain metadata retain the legacy full-frame path.
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if ("0".equals(rotation)) {
                int sourceWidth = Integer.parseInt(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int sourceHeight = Integer.parseInt(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                size = decodeSize(sourceWidth, sourceHeight, width, height);
                if (size != null) {
                    frame = retriever.getScaledFrameAtTime(timeUs, option, size.width, size.height);
                }
            }
        } catch (RuntimeException ignored) {
            // Older vendor codecs may reject scaled extraction; retry the original API.
        }
        if (frame != null) {
            if (size != null && size.matches(frame.getWidth(), frame.getHeight())) {
                return resizeOwned(frame, width, size.outputHeight);
            }
            frame.recycle();
        }

        frame = retriever.getFrameAtTime(timeUs, option);
        if (frame == null) return null;
        return resizeOwned(frame, width, height == 0
                ? proportionalHeight(frame.getWidth(), frame.getHeight(), width) : height);
    }

    private static Bitmap resizeOwned(Bitmap source, int width, int height) {
        Bitmap result = null;
        try {
            result = Bitmap.createScaledBitmap(source, width, height, true);
            return result;
        } finally {
            if (source != result) source.recycle();
        }
    }

    private static int proportionalHeight(int sourceWidth, int sourceHeight, int width) {
        return (int) Math.max(1L, Math.round((double) width * sourceHeight / sourceWidth));
    }

    /** Fit the source aspect into a box large enough for the final resize, without upsampling. */
    static DecodeSize decodeSize(int sourceWidth, int sourceHeight, int width, int height) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height < 0
                || width >= sourceWidth || height >= sourceHeight) return null;
        long decodeWidth = Math.max((long) width,
                ceilDivide((long) height * sourceWidth, sourceHeight));
        long decodeHeight = Math.max((long) height,
                ceilDivide((long) width * sourceHeight, sourceWidth));
        // No worthwhile downscale, or would request more pixels than the source.
        if (decodeWidth >= sourceWidth || decodeHeight >= sourceHeight) return null;
        return new DecodeSize((int) decodeWidth, (int) decodeHeight,
                height == 0 ? proportionalHeight(sourceWidth, sourceHeight, width) : height);
    }

    private static long ceilDivide(long numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    static final class DecodeSize {
        final int width;
        final int height;
        final int outputHeight;

        DecodeSize(int width, int height, int outputHeight) {
            this.width = width;
            this.height = height;
            this.outputHeight = outputHeight;
        }

        boolean matches(int actualWidth, int actualHeight) {
            // The platform preserves source aspect and may round by one pixel.
            return actualWidth > 0 && actualHeight > 0
                    && Math.abs((long) actualWidth - width) <= 1
                    && Math.abs((long) actualHeight - height) <= 1;
        }
    }
}
