package com.overdrive.app.trips;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Incrementally decodes LF-delimited UTF-8 records from a compressed telemetry snapshot. */
final class GzipJsonLines {
    private GzipJsonLines() {}

    static void read(byte[] data, Consumer<String> onLine) {
        if (data == null || data.length == 0) return;

        ByteArrayInputStream source = new ByteArrayInputStream(data);
        byte[] chunk = new byte[4096];
        // Only lines crossing a read boundary need staging. Keep raw bytes until the
        // entire line is present so a UTF-8 character can span reads or gzip members.
        PendingLine pending = new PendingLine();
        while (source.available() > 0) {
            // Keep the existing concatenated-member and damaged-tail behavior. Closing
            // a gzip reader releases its inflater; closing the byte-array source is a no-op.
            try (GZIPInputStream gzip = new GZIPInputStream(source)) {
                int count;
                while ((count = gzip.read(chunk)) != -1) {
                    int start = 0;
                    for (int i = 0; i < count; i++) {
                        if (chunk[i] != '\n') continue;
                        if (pending.size() == 0) {
                            onLine.accept(new String(chunk, start, i - start, StandardCharsets.UTF_8));
                        } else {
                            pending.write(chunk, start, i - start);
                            onLine.accept(pending.decode());
                            pending.reset();
                        }
                        start = i + 1;
                    }
                    if (start < count) pending.write(chunk, start, count - start);
                }
            } catch (IOException e) {
                // As before, keep the bytes successfully inflated before a broken tail.
                break;
            }
        }
        // The utility writer does not append a final newline; recovery also used
        // to attempt parsing the last (possibly incomplete) line of a damaged file.
        if (pending.size() != 0) onLine.accept(pending.decode());
    }

    private static final class PendingLine extends ByteArrayOutputStream {
        PendingLine() {
            super(256);
        }

        String decode() {
            return new String(buf, 0, count, StandardCharsets.UTF_8);
        }
    }
}
