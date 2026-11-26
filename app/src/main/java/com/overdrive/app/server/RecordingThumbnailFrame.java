package com.overdrive.app.server;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.util.function.LongFunction;

/** Produces the legacy 320x180 thumbnail without requesting a full-size frame when possible. */
final class RecordingThumbnailFrame {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    // Scaled retrieval preserves source aspect ratio. A square bound keeps a
    // 4:3 camera frame at 320x240 rather than undersampling it to 240x180
    // before the existing stretch to 320x180. 16:9 needs no second resize.
    private static final int SCALED_BOUND = WIDTH;
    private static final int SYNC = MediaMetadataRetriever.OPTION_CLOSEST_SYNC;

    private RecordingThumbnailFrame() {}

    static Bitmap extract(MediaMetadataRetriever retriever) {
        return extract(timeUs -> retriever.getScaledFrameAtTime(
                        timeUs, SYNC, SCALED_BOUND, SCALED_BOUND),
                timeUs -> retriever.getFrameAtTime(timeUs, SYNC));
    }

    // Decoder callbacks make the fallback ordering and bitmap ownership testable
    // without requiring an encoded video or a particular vendor codec.
    static Bitmap extract(LongFunction<Bitmap> scaledReader, LongFunction<Bitmap> fullReader) {
        Bitmap frame = readAt(1_000_000L, scaledReader, fullReader);
        if (frame == null) frame = readAt(0L, scaledReader, fullReader);
        if (frame == null) return null;

        Bitmap thumbnail = null;
        try {
            // Preserve the exact output dimensions and filtering used previously.
            thumbnail = Bitmap.createScaledBitmap(frame, WIDTH, HEIGHT, true);
            return thumbnail;
        } finally {
            // createScaledBitmap may return frame itself. Transfer ownership in
            // that case; otherwise release the intermediate, even on failure.
            if (thumbnail != frame) frame.recycle();
        }
    }

    private static Bitmap readAt(long timeUs, LongFunction<Bitmap> scaledReader,
                                 LongFunction<Bitmap> fullReader) {
        Bitmap frame = null;
        try {
            frame = scaledReader.apply(timeUs);
        } catch (RuntimeException ignored) {
            // Some vendor media stacks do not implement scaled extraction for
            // every codec/data source. Preserve the established full-frame path.
        }
        if (frame != null) {
            if (frame.getWidth() >= WIDTH && frame.getHeight() >= HEIGHT) {
                return frame;
            }
            // Unusual aspect ratio or small source: avoid adding an extra
            // downscale/upscale cycle compared with the full-frame path.
            frame.recycle();
        }
        // Try the same timestamp first, before falling back to time zero.
        return fullReader.apply(timeUs);
    }
}
