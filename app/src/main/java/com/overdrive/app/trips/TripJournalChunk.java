package com.overdrive.app.trips;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

/** Prepares one complete gzip member for append to a trip journal. */
final class TripJournalChunk {
    private static final int TEXT_BATCH_CHARS = 4096;

    private TripJournalChunk() {}

    static <T> ByteArrayOutputStream encode(Iterable<T> rows, Function<T, String> serialize)
            throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        StringBuilder text = new StringBuilder();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            for (T row : rows) {
                text.append(serialize.apply(row)).append('\n');
                // Encode complete lines so surrogate pairs are never split.
                // Do not flush gzip itself: retain its compression history.
                if (text.length() >= TEXT_BATCH_CHARS) {
                    gzip.write(text.toString().getBytes(StandardCharsets.UTF_8));
                    text.setLength(0);
                }
            }
            if (text.length() != 0) {
                gzip.write(text.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        // A failed encode never hands the caller an incomplete member to append.
        return compressed;
    }
}
