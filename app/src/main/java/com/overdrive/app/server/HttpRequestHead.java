package com.overdrive.app.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Bounded HTTP/1 request-head reader and framing validator.
 *
 * <p>Read the head directly from the same buffered byte stream that will be
 * used to read the body. A BufferedReader may read ahead into the body, and
 * its readLine() has no size limit. Neither is appropriate for unauthenticated
 * network input on the always-on camera daemon.
 *
 * <p>The daemon accepts one request per connection and supports only
 * Content-Length framing (not chunked transfer encoding). Be strict about
 * contradictory framing before dispatching any API handler.
 */
final class HttpRequestHead {
    static final int MAX_REQUEST_LINE_BYTES = 8192;
    static final int MAX_HEADER_LINE_BYTES = 16384;
    static final int MAX_HEAD_BYTES = 32768;
    static final int MAX_HEADER_FIELDS = 100;
    private static final long MAX_HEAD_NANOS = 15_000_000_000L;

    private final InputStream input;
    private final long startedNanos;
    private int bytesRead;
    private int requestLineCount;
    private int headerCount;
    private boolean headersComplete;

    HttpRequestHead(InputStream input) {
        if (input == null) throw new IllegalArgumentException("input");
        this.input = input;
        this.startedNanos = System.nanoTime();
    }

    /** Allow one HTTP request line, plus one trusted PROXY v1 preamble. */
    String readRequestLine() throws IOException {
        if (headerCount != 0 || headersComplete || requestLineCount >= 2) {
            throw new IllegalStateException("Request line read out of order");
        }
        requestLineCount++;
        return readLine(MAX_REQUEST_LINE_BYTES, 414);
    }

    /** Read one header line, returning the empty string at the terminator. */
    String readHeaderLine() throws IOException {
        if (requestLineCount == 0 || headersComplete) {
            throw new IllegalStateException("Headers read out of order");
        }
        String line = readLine(MAX_HEADER_LINE_BYTES, 431);
        if (line == null) throw new HeadException(400, "Incomplete request headers");
        if (line.isEmpty()) {
            headersComplete = true;
            return line;
        }
        if (++headerCount > MAX_HEADER_FIELDS) {
            throw new HeadException(431, "Too many request headers");
        }
        return line;
    }

    private String readLine(int lineLimit, int oversizedStatus) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (true) {
            // Socket SO_TIMEOUT bounds a single blocked read. This independent
            // elapsed budget also stops a peer that trickles one byte per read.
            if (System.nanoTime() - startedNanos >= MAX_HEAD_NANOS) {
                throw new HeadException(408, "Request headers timed out");
            }
            int b;
            try {
                b = input.read();
            } catch (SocketTimeoutException timedOut) {
                throw new HeadException(408, "Request headers timed out");
            }
            if (b < 0) {
                if (line.size() == 0) return null;
                throw new HeadException(400, "Incomplete request line");
            }
            if (++bytesRead > MAX_HEAD_BYTES) {
                throw new HeadException(431, "Request headers too large");
            }
            if (b == '\n') {
                byte[] data = line.toByteArray();
                int length = data.length;
                // Support CRLF and the existing LF-only request compatibility.
                if (length > 0 && data[length - 1] == '\r') length--;
                for (int i = 0; i < length; i++) {
                    // Reject embedded CR, NUL, DEL and other control bytes.
                    // A tab is legal within a header field value.
                    int c = data[i] & 0xff;
                    if ((c < 0x20 && c != '\t') || c == 0x7f) {
                        throw new HeadException(400, "Invalid request header characters");
                    }
                }
                return new String(data, 0, length, StandardCharsets.UTF_8);
            }
            if (line.size() >= lineLimit) {
                throw new HeadException(oversizedStatus, "Request line or header too long");
            }
            line.write(b);
        }
    }

    /**
     * Decode exactly the Content-Length bytes, without guessing byte counts
     * from character chunks (which can split multi-byte UTF-8 sequences).
     * The bounded stream returns EOF at the declared length, so a decoding
     * buffer cannot consume bytes belonging to another HTTP message.
     */
    static String readUtf8Body(InputStream input, int contentLength) throws IOException {
        if (contentLength < 0) throw new IllegalArgumentException("contentLength");
        if (contentLength == 0) return null;
        LimitedBodyStream limited = new LimitedBodyStream(input, contentLength);
        InputStreamReader decoder = new InputStreamReader(limited, StandardCharsets.UTF_8);
        char[] chars = new char[4096];
        StringBuilder body = new StringBuilder(Math.min(contentLength, 4096));
        int count;
        while ((count = decoder.read(chars, 0, chars.length)) >= 0) {
            if (count > 0) body.append(chars, 0, count);
        }
        if (limited.remaining != 0) {
            throw new HeadException(400, "Incomplete request body");
        }
        return body.toString();
    }

    private static final class LimitedBodyStream extends InputStream {
        private final InputStream input;
        private int remaining;

        private LimitedBodyStream(InputStream input, int contentLength) {
            this.input = input;
            this.remaining = contentLength;
        }

        @Override public int read() throws IOException {
            if (remaining == 0) return -1;
            int value = input.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > buffer.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int count = input.read(buffer, offset, Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }

    /** Reject duplicate/ambiguous HTTP framing instead of silently taking the last value. */
    static final class Framing {
        private boolean hasLength;
        private int contentLength;

        void accept(String line) throws HeadException {
            int colon = line.indexOf(':');
            if (colon <= 0) throw new HeadException(400, "Malformed request header");
            for (int i = 0; i < colon; i++) {
                char c = line.charAt(i);
                // RFC 9110 tchar: reject whitespace before the colon and
                // non-ASCII field names that might be parsed differently.
                boolean token = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9')
                        || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
                if (!token) throw new HeadException(400, "Malformed request header name");
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if ("transfer-encoding".equals(name)) {
                // No chunked decoder exists here. Never reinterpret a chunked
                // body as an empty or Content-Length-delimited body.
                throw new HeadException(400, "Transfer-Encoding is unsupported");
            }
            if (!"content-length".equals(name)) return;
            if (hasLength) throw new HeadException(400, "Duplicate Content-Length");
            hasLength = true;
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) throw new HeadException(400, "Invalid Content-Length");
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                    throw new HeadException(400, "Invalid Content-Length");
                }
            }
            try {
                long parsed = Long.parseLong(value);
                if (parsed > Integer.MAX_VALUE) {
                    throw new HeadException(413, "Payload too large");
                }
                contentLength = (int) parsed;
            } catch (NumberFormatException e) {
                throw new HeadException(413, "Payload too large");
            }
        }

        int contentLength() {
            return contentLength;
        }
    }

    static final class HeadException extends IOException {
        final int status;

        HeadException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
