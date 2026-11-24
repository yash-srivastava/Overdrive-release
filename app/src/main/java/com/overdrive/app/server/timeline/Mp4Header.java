package com.overdrive.app.server.timeline;

import com.overdrive.app.storage.removable.SeekableFileReader;

import java.io.File;

/** MP4 duration from the moov header. Returns -1 on anything inconsistent; never throws. */
public final class Mp4Header {

    static final int MAX_READS = 16;
    static final int MAX_MOOV_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_BOXES = 20_000;

    private Mp4Header() {}

    public static long durationMs(File file) {
        if (file == null) return -1L;
        try (SeekableFileReader raf = new SeekableFileReader(file)) {
            long length = raf.length();
            if (length < 16) return -1L;
            byte[] header = new byte[16];
            long pos = 0;
            int reads = 0;
            while (pos + 8 <= length) {
                if (++reads > MAX_READS) return -1L;
                int want = (int) Math.min(16L, length - pos);
                raf.seek(pos);
                raf.readFully(header, 0, want);
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
                if (header[4] == 'm' && header[5] == 'o' && header[6] == 'o' && header[7] == 'v') {
                    long payload = size - headerLength;
                    if (payload < 8 || payload > MAX_MOOV_BYTES) return -1L;
                    if (++reads > MAX_READS) return -1L;
                    byte[] moov = new byte[(int) payload];
                    raf.seek(pos + headerLength);
                    raf.readFully(moov, 0, moov.length);
                    return moovDurationMs(moov);
                }
                pos += size;
            }
            return -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** mvhd duration, else the longest mdhd; -1 when the box tree does not nest cleanly. */
    static long moovDurationMs(byte[] moov) {
        int[] budget = {MAX_BOXES};
        if (!validTree(moov, 0, moov.length, 0, budget)) return -1L;
        long movie = -1L;
        long longestTrack = -1L;
        int off = 0;
        while (off + 8 <= moov.length) {
            int size = boxSize(moov, off, moov.length);
            int header = boxHeader(moov, off);
            if (is(moov, off + 4, "mvhd")) {
                movie = fullBoxDurationMs(moov, off + header, off + size);
            } else if (is(moov, off + 4, "trak")) {
                long track = trackDurationMs(moov, off + header, off + size);
                if (track > longestTrack) longestTrack = track;
            }
            off += size;
        }
        long result = movie > 0 ? movie : longestTrack;
        return result > 0 ? result : -1L;
    }

    private static long trackDurationMs(byte[] b, int from, int to) {
        int mdia = child(b, from, to, "mdia");
        if (mdia < 0) return -1L;
        int mdiaEnd = mdia + boxSize(b, mdia, to);
        int mdhd = child(b, mdia + boxHeader(b, mdia), mdiaEnd, "mdhd");
        if (mdhd < 0) return -1L;
        return fullBoxDurationMs(b, mdhd + boxHeader(b, mdhd), mdhd + boxSize(b, mdhd, mdiaEnd));
    }

    /** mvhd/mdhd share the version 0/1 layout for timescale and duration. */
    private static long fullBoxDurationMs(byte[] b, int payload, int end) {
        if (payload + 4 > end) return -1L;
        int version = b[payload] & 0xFF;
        long timescale;
        long duration;
        if (version == 1) {
            if (payload + 32 > end) return -1L;
            timescale = u32(b, payload + 20);
            duration = s64(b, payload + 24);
            if (duration == -1L) return 0L;
        } else if (version == 0) {
            if (payload + 20 > end) return -1L;
            timescale = u32(b, payload + 12);
            duration = u32(b, payload + 16);
            if (duration == 0xFFFFFFFFL) return 0L;
        } else {
            return -1L;
        }
        if (timescale <= 0 || duration < 0) return -1L;
        if (duration > Long.MAX_VALUE / 1000L) return -1L;
        return duration * 1000L / timescale;
    }

    private static int child(byte[] b, int from, int to, String type) {
        int off = from;
        while (off + 8 <= to) {
            int size = boxSize(b, off, to);
            if (size < 8) return -1;
            if (is(b, off + 4, type)) return off;
            off += size;
        }
        return -1;
    }

    private static boolean validTree(byte[] b, int from, int to, int depth, int[] budget) {
        if (depth > MAX_DEPTH) return false;
        int off = from;
        while (off < to) {
            if (--budget[0] < 0) return false;
            if (to - off < 8) return false;
            if (!isFourcc(b, off + 4)) return false;
            int size = boxSize(b, off, to);
            int header = boxHeader(b, off);
            if (size < header || size > to - off) return false;
            if (isContainer(b, off + 4)
                    && !validTree(b, off + header, off + size, depth + 1, budget)) {
                return false;
            }
            off += size;
        }
        return off == to;
    }

    private static boolean isContainer(byte[] b, int typeOff) {
        return is(b, typeOff, "trak") || is(b, typeOff, "mdia") || is(b, typeOff, "minf")
                || is(b, typeOff, "stbl") || is(b, typeOff, "edts") || is(b, typeOff, "dinf")
                || is(b, typeOff, "mvex");
    }

    /** Box size within [off, to): largesize and size-0 resolved; -1 when unrepresentable. */
    private static int boxSize(byte[] b, int off, int to) {
        long size = u32(b, off);
        if (size == 1) {
            if (off + 16 > to) return -1;
            size = s64(b, off + 8);
        } else if (size == 0) {
            size = to - off;
        }
        return size < 0 || size > Integer.MAX_VALUE ? -1 : (int) size;
    }

    private static int boxHeader(byte[] b, int off) {
        return u32(b, off) == 1 ? 16 : 8;
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
