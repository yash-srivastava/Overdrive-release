package com.overdrive.app.util;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

/** Small preview bitmaps without transferring a full-resolution frame when possible. */
public final class VideoPreviewFrame {
    private static final int MAX_SCALED_EDGE = 4096;

    private VideoPreviewFrame() {}

    /**
     * Extract the requested sync frame. A height of zero preserves its aspect ratio;
     * otherwise the returned bitmap has exactly the requested dimensions. The caller owns it.
     */
    public static Bitmap extract(MediaMetadataRetriever retriever, long timeUs, int option,
                                 int width, int height) {
        Bitmap small = tryScaled(retriever, timeUs, option, width, height);
        if (small != null) return small;

        // Keep the same timestamp and sync option on the compatibility path.
        Bitmap full = retriever.getFrameAtTime(timeUs, option);
        if (full == null) return null;
        Bitmap result = null;
        try {
            if (full.getWidth() <= 0 || full.getHeight() <= 0) return null;
            int outHeight = height == 0 ? aspectHeight(width, full.getWidth(), full.getHeight()) : height;
            result = Bitmap.createScaledBitmap(full, width, outHeight, true);
            return result;
        } finally {
            if (full != result) full.recycle();
        }
    }

    private static Bitmap tryScaled(MediaMetadataRetriever retriever, long timeUs, int option,
                                    int width, int height) {
        Bitmap frame = null;
        Bitmap result = null;
        try {
            // Rotated metadata can describe coded rather than displayed dimensions.
            // Leave those clips on the established full-frame path.
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation != null && !"0".equals(rotation)) return null;
            int sourceWidth = Integer.parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int sourceHeight = Integer.parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            Bounds bounds = bounds(sourceWidth, sourceHeight, width, height);
            if (bounds == null) return null;
            frame = retriever.getScaledFrameAtTime(timeUs, option, bounds.width, bounds.height);
            if (frame == null || !bounds.matches(frame.getWidth(), frame.getHeight())) return null;
            result = Bitmap.createScaledBitmap(frame, width, bounds.outputHeight, true);
            return result;
        } catch (RuntimeException | LinkageError e) {
            // Missing metadata, unsupported vendor retriever or failed scaled decode.
            return null;
        } finally {
            if (frame != null && frame != result) frame.recycle();
        }
    }

    private static int aspectHeight(int width, int sourceWidth, int sourceHeight) {
        // Same rounding as the full-frame scrub renderer.
        return (int) Math.max(1, Math.round((double) width * sourceHeight / sourceWidth));
    }

    static final class Bounds {
        final int width, height, outputHeight;

        Bounds(int width, int height, int outputHeight) {
            this.width = width;
            this.height = height;
            this.outputHeight = outputHeight;
        }

        boolean matches(int actualWidth, int actualHeight) {
            // Retrievers may round the aspect fit down by one pixel. Reject
            // larger discrepancies (e.g. non-square sample aspect or rotation).
            return actualWidth > 0 && actualHeight > 0
                    && Math.abs((long) actualWidth - width) <= 1
                    && Math.abs((long) actualHeight - height) <= 1;
        }
    }

    static Bounds bounds(int sourceWidth, int sourceHeight, int width, int height) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height < 0) return null;
        // getScaledFrameAtTime fits inside the requested box. Ask for enough
        // pixels on both axes before the existing fixed-size stretch (if any).
        long outHeight = height == 0
                ? Math.max(1, Math.round((double) width * sourceHeight / sourceWidth)) : height;
        long requestWidth = height == 0 ? width : Math.max((long) width,
                ((long) height * sourceWidth + sourceHeight - 1) / sourceHeight);
        long requestHeight = Math.max(outHeight,
                ((long) width * sourceHeight + sourceWidth - 1) / sourceWidth);
        if (requestWidth > MAX_SCALED_EDGE || requestHeight > MAX_SCALED_EDGE
                || requestWidth >= sourceWidth || requestHeight >= sourceHeight) return null;
        return new Bounds((int) requestWidth, (int) requestHeight, (int) outHeight);
    }
}
