package com.overdrive.app.server.hls;

/** Bounds-checked big-endian reads and child-box iteration over an in-memory moov. */
final class Boxes {
    private Boxes() {}

    static int fourcc(String s) {
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    static String name(int type) {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) {
            int ch = (type >>> (24 - 8 * i)) & 0xff;
            c[i] = ch >= 0x20 && ch < 0x7f ? (char) ch : '?';
        }
        return new String(c);
    }

    static final int MOOV = fourcc("moov");
    static final int MVHD = fourcc("mvhd");
    static final int MVEX = fourcc("mvex");
    static final int TRAK = fourcc("trak");
    static final int TKHD = fourcc("tkhd");
    static final int EDTS = fourcc("edts");
    static final int ELST = fourcc("elst");
    static final int MDIA = fourcc("mdia");
    static final int MDHD = fourcc("mdhd");
    static final int HDLR = fourcc("hdlr");
    static final int MINF = fourcc("minf");
    static final int STBL = fourcc("stbl");
    static final int STSD = fourcc("stsd");
    static final int STTS = fourcc("stts");
    static final int CTTS = fourcc("ctts");
    static final int STSS = fourcc("stss");
    static final int STSZ = fourcc("stsz");
    static final int STZ2 = fourcc("stz2");
    static final int STSC = fourcc("stsc");
    static final int STCO = fourcc("stco");
    static final int CO64 = fourcc("co64");
    static final int VIDE = fourcc("vide");
    static final int SOUN = fourcc("soun");
    static final int AVC1 = fourcc("avc1");
    static final int AVC3 = fourcc("avc3");
    static final int HVC1 = fourcc("hvc1");
    static final int HEV1 = fourcc("hev1");
    static final int MP4A = fourcc("mp4a");
    static final int AVCC = fourcc("avcC");
    static final int HVCC = fourcc("hvcC");
    static final int ESDS = fourcc("esds");
    static final int WAVE = fourcc("wave");

    static void need(int pos, int len, int end, String what) throws Mp4Exception {
        if (pos < 0 || len < 0 || pos > end - len) throw new Mp4Exception("truncated " + what);
    }

    static int u8(byte[] b, int p) {
        return b[p] & 0xff;
    }

    static int u16(byte[] b, int p) {
        return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }

    static int s32(byte[] b, int p) {
        return ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
    }

    static long u32(byte[] b, int p) {
        return s32(b, p) & 0xffffffffL;
    }

    static long u64(byte[] b, int p) {
        return (u32(b, p) << 32) | u32(b, p + 4);
    }

    /** Walks the children of [start, end); a child that overruns its parent is an error. */
    static final class Iter {
        private final byte[] b;
        private final int end;
        private int pos;
        int type;
        int start;
        int payload;
        int boxEnd;

        Iter(byte[] b, int start, int end) {
            this.b = b;
            this.pos = start;
            this.end = end;
        }

        boolean next() throws Mp4Exception {
            // Fewer than 8 trailing bytes are terminator padding, not a box.
            if (end - pos < 8) return false;
            long size = u32(b, pos);
            int hdr = 8;
            if (size == 1) {
                need(pos, 16, end, "box header");
                size = u64(b, pos + 8);
                hdr = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < hdr || size > end - pos) {
                throw new Mp4Exception("box " + name(s32(b, pos + 4)) + " overruns parent");
            }
            type = s32(b, pos + 4);
            start = pos;
            payload = pos + hdr;
            boxEnd = (int) (pos + size);
            pos = boxEnd;
            return true;
        }
    }
}
