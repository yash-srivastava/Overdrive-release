package com.overdrive.app.trips;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Incrementally decodes LF-delimited UTF-8 records from a compressed telemetry snapshot. */
final class GzipJsonLines {
    private GzipJsonLines() {}

    /** Read at most the captured file length; the caller owns the input stream. */
    static void read(InputStream input, long length, Consumer<String> onLine) throws IOException {
        if (length <= 0) return;

        SnapshotSource source = new SnapshotSource(input, length);
        byte[] chunk = new byte[4096];
        // Only lines crossing a read boundary need staging. Keep raw bytes until the
        // entire line is present so a UTF-8 character can span reads or gzip members.
        PendingLine pending = new PendingLine();
        while (source.available() > 0) {
            // Keep the existing concatenated-member and damaged-tail behavior. Closing
            // a gzip reader releases its inflater; the snapshot source stays open.
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
        // A damaged gzip member is recoverable, but a storage read failure is not.
        // GZIPInputStream may swallow an IOException while probing a next member.
        if (source.failure != null) throw source.failure;
        // The utility writer does not append a final newline; recovery also used
        // to attempt parsing the last (possibly incomplete) line of a damaged file.
        if (pending.size() != 0) onLine.accept(pending.decode());
    }

    /**
     * A non-owning, length-limited source with reliable remaining-byte reporting
     * for gzip concatenation. Never expose bytes appended after the read began.
     */
    private static final class SnapshotSource extends InputStream {
        private final InputStream input;
        private final byte[] buffer = new byte[8192];
        private int position;
        private int limit;
        private long remaining;
        private IOException failure;

        SnapshotSource(InputStream input, long length) {
            this.input = input;
            remaining = length;
        }

        @Override
        public int available() {
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }

        private boolean refill() throws IOException {
            if (position < limit) return true;
            if (remaining == 0) return false;
            try {
                int count = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (count == -1) {
                    remaining = 0;
                    return false;
                }
                if (count == 0) throw new IOException("No progress reading telemetry snapshot");
                position = 0;
                limit = count;
                return true;
            } catch (IOException e) {
                failure = e;
                remaining = 0;
                throw e;
            }
        }

        @Override
        public int read() throws IOException {
            if (!refill()) return -1;
            remaining--;
            return buffer[position++] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            int copied = 0;
            while (copied < length && refill()) {
                int count = Math.min(length - copied, limit - position);
                System.arraycopy(buffer, position, bytes, offset + copied, count);
                position += count;
                copied += count;
                remaining -= count;
            }
            return copied == 0 ? -1 : copied;
        }

        // GZIPInputStream owns its inflater, not the file descriptor.
        @Override
        public void close() {}
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
