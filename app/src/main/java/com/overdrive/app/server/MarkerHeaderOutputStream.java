package com.overdrive.app.server;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Inserts {@code X-OverDrive: 1} right after the status line of the HTTP/1.x response written
 * through it, so a browser can tell the car's replies from a tunnel edge's. Anything else
 * passes through unchanged.
 */
final class MarkerHeaderOutputStream extends FilterOutputStream {

    private static final byte[] PREFIX = "HTTP/1.".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MARKER = "X-OverDrive: 1\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_STATUS_LINE = 1024;

    private byte[] pending = new byte[MAX_STATUS_LINE];
    private int pendingLen;
    private boolean passThrough;
    private int status = -1;

    MarkerHeaderOutputStream(OutputStream out) {
        super(out);
    }

    @Override
    public synchronized void write(int b) throws IOException {
        if (passThrough) {
            out.write(b);
        } else {
            feed((byte) b);
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        if (passThrough) {
            out.write(b, off, len);
            return;
        }
        if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
        int i = off;
        int end = off + len;
        while (i < end && !passThrough) feed(b[i++]);
        if (i < end) out.write(b, i, end - i);
    }

    /** The status code of the response's status line once written; -1 before that or for a non-HTTP/1.x write. */
    synchronized int status() {
        return status;
    }

    /** Bytes held for an unfinished status line stay held. */
    @Override
    public synchronized void flush() throws IOException {
        out.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            if (!passThrough) release(false);
        } finally {
            out.close();
        }
    }

    // The first LF ends the status line; a bare LF or an over-long line is left unmarked.
    private void feed(byte c) throws IOException {
        pending[pendingLen++] = c;
        if (pendingLen <= PREFIX.length) {
            if (c != PREFIX[pendingLen - 1]) release(false);
        } else if (c == '\n') {
            release(pending[pendingLen - 2] == '\r');
        } else if (pendingLen == MAX_STATUS_LINE) {
            release(false);
        }
    }

    // "HTTP/1.x 200 ..." -> 200
    private static int parseStatus(byte[] line, int n) {
        int i = PREFIX.length + 1;
        if (n < i + 4 || line[i] != ' ') return -1;
        int code = 0;
        for (int k = i + 1; k <= i + 3; k++) {
            if (line[k] < '0' || line[k] > '9') return -1;
            code = code * 10 + (line[k] - '0');
        }
        return code;
    }

    private void release(boolean marker) throws IOException {
        byte[] head = pending;
        int n = pendingLen;
        pending = null;
        pendingLen = 0;
        passThrough = true;
        if (marker) status = parseStatus(head, n);
        if (n > 0) out.write(head, 0, n);
        if (marker) out.write(MARKER, 0, MARKER.length);
    }
}
