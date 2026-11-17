package com.overdrive.app.server.hls;

import java.util.Arrays;

/** Growable big-endian buffer; begin()/end() patch the 32-bit box size. */
final class BoxWriter {
    private byte[] buf;
    private int len;

    BoxWriter(int capacity) {
        buf = new byte[Math.max(64, capacity)];
    }

    int size() {
        return len;
    }

    int begin(int type) {
        int at = len;
        u32(0);
        u32(type);
        return at;
    }

    int beginFull(int type, int version, int flags) {
        int at = begin(type);
        u32(((long) version << 24) | (flags & 0xffffff));
        return at;
    }

    void end(int at) {
        patch32(at, len - at);
    }

    void patch32(int at, long v) {
        buf[at] = (byte) (v >>> 24);
        buf[at + 1] = (byte) (v >>> 16);
        buf[at + 2] = (byte) (v >>> 8);
        buf[at + 3] = (byte) v;
    }

    void u8(int v) {
        ensure(1);
        buf[len++] = (byte) v;
    }

    void u16(int v) {
        ensure(2);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
    }

    void u32(long v) {
        ensure(4);
        buf[len++] = (byte) (v >>> 24);
        buf[len++] = (byte) (v >>> 16);
        buf[len++] = (byte) (v >>> 8);
        buf[len++] = (byte) v;
    }

    void u64(long v) {
        u32(v >>> 32);
        u32(v);
    }

    void zeros(int n) {
        ensure(n);
        len += n;
    }

    void bytes(byte[] b) {
        ensure(b.length);
        System.arraycopy(b, 0, buf, len, b.length);
        len += b.length;
    }

    byte[] toByteArray() {
        return Arrays.copyOf(buf, len);
    }

    private void ensure(int n) {
        if (len + n > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
    }
}
