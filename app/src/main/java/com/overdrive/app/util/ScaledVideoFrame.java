package com.overdrive.app.util;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

/** Small still extraction for callers that do not need a full-resolution bitmap. */
public final class ScaledVideoFrame {
    private ScaledVideoFrame() {}

    /**
     * Ask the retriever for a frame fitting the requested bounds. Some vendor
     * retrievers cannot produce scaled stills, so keep the original full-frame
     * path for null results or runtime failures. The caller owns the bitmap and
     * is responsible for any final resize and recycling.
     */
    public static Bitmap get(MediaMetadataRetriever retriever, long timeUs, int option,
                             int width, int height) {
        Bitmap scaled = tryScaled(retriever, timeUs, option, width, height);
        return scaled != null ? scaled : retriever.getFrameAtTime(timeUs, option);
    }

    private static Bitmap tryScaled(MediaMetadataRetriever retriever, long timeUs, int option,
                                    int width, int height) {
        if (width <= 0 || height <= 0) return null;
        try {
            return retriever.getScaledFrameAtTime(timeUs, option, width, height);
        } catch (RuntimeException ignored) {
            // Vendor decoder or scaled extraction unsupported: retry the original path.
            return null;
        }
    }

    /**
     * Request a width-limited still with a height estimated from the video
     * metadata, taking display rotation into account. If geometry is missing
     * or unusual, use the full-frame path instead of guessing a shape.
     */
    public static Bitmap getForWidth(MediaMetadataRetriever retriever, long timeUs,
                                     int option, int width) {
        int height = 0;
        try {
            height = targetHeight(width,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        } catch (RuntimeException ignored) {
            // Metadata retrieval is not required for the legacy extraction path.
        }
        Bitmap scaled = tryScaled(retriever, timeUs, option, width, height);
        if (scaled != null) {
            // A metadata/display-aspect mismatch may produce a much narrower
            // frame than requested. Don't upscale it and lose visible detail.
            // Allow a single pixel of decoder rounding.
            if (scaled.getWidth() >= width - 1) return scaled;
            scaled.recycle();
        }
        return retriever.getFrameAtTime(timeUs, option);
    }

    // Zero selects the legacy path. Bound speculative scaled allocations; this
    // does not impose a new size limit on the existing full-frame fallback.
    static int targetHeight(int width, String videoWidth, String videoHeight, String rotation) {
        try {
            int w = Integer.parseInt(videoWidth);
            int h = Integer.parseInt(videoHeight);
            int angle = rotation == null ? 0 : Integer.parseInt(rotation);
            if (width <= 0 || w <= 0 || h <= 0) return 0;
            if (angle == 90 || angle == 270) {
                int swap = w;
                w = h;
                h = swap;
            } else if (angle != 0 && angle != 180) {
                return 0;
            }
            // Don't ask the retriever to upscale; the caller already handles this.
            if (width > w) return 0;
            long scaledHeight = Math.max(1L, Math.round((double) width * h / w));
            return scaledHeight <= 8192 ? (int) scaledHeight : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
