package com.overdrive.app.server.timeline;

import com.overdrive.app.storage.removable.SeekableFileReader;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** MP4 duration from the moov header. Returns -1 on anything inconsistent; never throws. */
public final class Mp4Header {

    // Bound the top-level search (including one logical moov access), not
    // the number of physical reads inside the bounded moov box walk.
    static final int MAX_READS = 16;
    static final int MAX_MOOV_BYTES = 16 * 1024 * 1024;
    static final int WINDOW_BYTES = 4096;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_BOXES = 20_000;

    private Mp4Header() {}

    interface Reader {
        int read(ByteBuffer dst, long position) throws IOException;
    }

    public static long durationMs(File file) {
        if (file == null) return -1L;
        try (SeekableFileReader raf = new SeekableFileReader(file)) {
            long length = raf.length();
            long duration = durationMs(raf.getChannel()::read, length);
            // Skipping leaf bodies must not turn a concurrent truncation into
            // a successful duration for a file whose original extent is gone.
            return duration > 0 && raf.length() != length ? -1L : duration;
        } catch (Throwable t) {
            return -1L;
        }
    }

    static long durationMs(Reader reader, long length) throws IOException {
        if (length < 16) return -1L;
        byte[] header = new byte[16];
        ByteBuffer dst = ByteBuffer.wrap(header);
        long pos = 0;
        int reads = 0;
        while (pos <= length - 8) {
            if (++reads > MAX_READS) return -1L;
            int want = (int) Math.min(16L, length - pos);
            dst.clear().limit(want);
            readFully(reader, dst, pos);
            if (!isFourcc(header, 4)) return -1L;
            long size = u32(header, 0);
            int headerLength = 8;
            if (size == 1) {
                if (want < 16) return -1L;
                size = s64(header, 8);
                headerLength = 16;
            } else if (size == 0) {
                size = length - pos;
            }
            if (size < headerLength || size > length - pos) return -1L;
            if (is(header, 4, "moov")) {
                long payload = size - headerLength;
                if (payload < 8 || payload > MAX_MOOV_BYTES) return -1L;
                if (++reads > MAX_READS) return -1L;
                return moovDurationMs(new Window(reader, pos + headerLength, (int) payload));
            }
            pos += size;
        }
        return -1L;
    }

    private static void readFully(Reader reader, ByteBuffer dst, long position) throws IOException {
        while (dst.hasRemaining()) {
            int n = reader.read(dst, position);
            if (n <= 0) throw new EOFException("short MP4 header read at " + position);
            position += n;
        }
    }

    /** A single reusable window, bounded to the moov payload. Large leaf boxes are skipped. */
    private static final class Window {
        final Reader reader;
        final long base;
        final int length;
        final byte[] bytes;
        final ByteBuffer dst;
        int start = -1;
        int end = -1;

        Window(Reader reader, long base, int length) {
            this.reader = reader;
            this.base = base;
            this.length = length;
            bytes = new byte[Math.min(WINDOW_BYTES, length)];
            dst = ByteBuffer.wrap(bytes);
        }

        // Only used by the in-memory parser entry point; no additional copy.
        Window(byte[] bytes) {
            this.reader = null;
            this.base = 0;
            this.length = bytes.length;
            this.bytes = bytes;
            this.dst = null;
            this.start = 0;
            this.end = length;
        }

        int at(int offset, int count) throws IOException {
            if (offset < 0 || count < 0 || offset > length - count) throw new EOFException();
            if (offset < start || offset > end - count) {
                dst.clear().limit(Math.min(bytes.length, length - offset));
                readFully(reader, dst, base + offset);
                start = offset;
                end = offset + dst.limit();
            }
            return offset - start;
        }
    }

    /** mvhd duration, else the longest mdhd; -1 when the box tree does not nest cleanly. */
    static long moovDurationMs(byte[] moov) {
        try {
            return moovDurationMs(new Window(moov));
        } catch (IOException e) {
            return -1L;
        }
    }

    private static long moovDurationMs(Window w) throws IOException {
        int[] budget = {MAX_BOXES};
        if (!validTree(w, 0, w.length, 0, budget)) return -1L;
        long movie = -1L;
        long longestTrack = -1L;
        Box box = new Box();
        int off = 0;
        while (off < w.length) {
            if (!box.read(w, off, w.length)) return -1L;
            if (box.type == MVHD) {
                movie = fullBoxDurationMs(w, off + box.header, off + box.size);
            } else if (box.type == TRAK) {
                long track = trackDurationMs(w, off + box.header, off + box.size);
                if (track > longestTrack) longestTrack = track;
            }
            off += box.size;
        }
        long result = movie > 0 ? movie : longestTrack;
        return result > 0 ? result : -1L;
    }

    private static long trackDurationMs(Window w, int from, int to) throws IOException {
        Box box = new Box();
        int mdia = child(w, from, to, MDIA, box);
        if (mdia < 0) return -1L;
        int mdiaEnd = mdia + box.size;
        int mdhd = child(w, mdia + box.header, mdiaEnd, MDHD, box);
        if (mdhd < 0) return -1L;
        return fullBoxDurationMs(w, mdhd + box.header, mdhd + box.size);
    }

    /** mvhd/mdhd share the version 0/1 layout for timescale and duration. */
    private static long fullBoxDurationMs(Window w, int payload, int end) throws IOException {
        if (end - payload < 4) return -1L;
        int p = w.at(payload, 4);
        int version = w.bytes[p] & 0xFF;
        long timescale;
        long duration;
        if (version == 1) {
            if (end - payload < 32) return -1L;
            p = w.at(payload, 32);
            timescale = u32(w.bytes, p + 20);
            duration = s64(w.bytes, p + 24);
            if (duration == -1L) return 0L;
        } else if (version == 0) {
            if (end - payload < 20) return -1L;
            p = w.at(payload, 20);
            timescale = u32(w.bytes, p + 12);
            duration = u32(w.bytes, p + 16);
            if (duration == 0xFFFFFFFFL) return 0L;
        } else {
            return -1L;
        }
        if (timescale <= 0 || duration < 0) return -1L;
        if (duration > Long.MAX_VALUE / 1000L) return -1L;
        return duration * 1000L / timescale;
    }

    // On success, box describes the first matching child, not a later duplicate.
    private static int child(Window w, int from, int to, int type, Box box) throws IOException {
        int off = from;
        while (off < to) {
            if (!box.read(w, off, to)) return -1;
            if (box.type == type) return off;
            off += box.size;
        }
        return -1;
    }

    private static boolean validTree(Window w, int from, int to, int depth, int[] budget)
            throws IOException {
        if (depth > MAX_DEPTH) return false;
        int off = from;
        Box box = new Box();
        while (off < to) {
            if (--budget[0] < 0 || !box.read(w, off, to)) return false;
            if (isContainer(box.type)
                    && !validTree(w, off + box.header, off + box.size, depth + 1, budget)) {
                return false;
            }
            off += box.size;
        }
        return off == to;
    }

    private static final int MVHD = 0x6d766864, TRAK = 0x7472616b;
    private static final int MDIA = 0x6d646961, MDHD = 0x6d646864;

    private static boolean isContainer(int type) {
        return type == TRAK || type == MDIA || type == 0x6d696e66 // minf
                || type == 0x7374626c || type == 0x65647473 // stbl, edts
                || type == 0x64696e66 || type == 0x6d766578; // dinf, mvex
    }

    /** Reused per traversal level; no allocation per leaf/sample table. */
    private static final class Box {
        int type, size, header;

        boolean read(Window w, int off, int to) throws IOException {
            if (to - off < 8) return false;
            int p = w.at(off, 8);
            if (!isFourcc(w.bytes, p + 4)) return false;
            type = (int) u32(w.bytes, p + 4);
            long n = u32(w.bytes, p);
            header = 8;
            if (n == 1) {
                if (to - off < 16) return false;
                p = w.at(off, 16);
                n = s64(w.bytes, p + 8);
                header = 16;
            } else if (n == 0) {
                n = to - off;
            }
            if (n < header || n > to - off) return false;
            size = (int) n;
            return true;
        }
    }

    private static boolean isFourcc(byte[] b, int off) {
        for (int i = off; i < off + 4; i++) {
            int c = b[i] & 0xFF;
            if (c < 0x20 || c > 0x7E) {
                if (c != 0xA9) return false;
            }
        }
        return true;
    }

    private static boolean is(byte[] b, int off, String type) {
        return off + 4 <= b.length && b[off] == type.charAt(0) && b[off + 1] == type.charAt(1)
                && b[off + 2] == type.charAt(2) && b[off + 3] == type.charAt(3);
    }

    private static long u32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    private static long s64(byte[] b, int off) {
        return (u32(b, off) << 32) | u32(b, off + 4);
    }
}
