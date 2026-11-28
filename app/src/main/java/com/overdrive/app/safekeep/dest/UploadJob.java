package com.overdrive.app.safekeep.dest;

import com.overdrive.app.safekeep.util.StreamDigests;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/** One object's transfer as seen by a driver: bytes, resume state and core callbacks. */
public final class UploadJob {

    public interface Source {
        InputStream open(long offset) throws IOException;
    }

    public interface Hooks {
        /** Persists the driver state and confirmed offset before the call returns. */
        void persist(JSONObject state, long confirmed);

        /** Blocks for the bandwidth budget of {@code bytes}. */
        void pace(int bytes) throws InterruptedException;

        boolean cancelled();

        void onCall(Call call);

        void onBytes(int n);
    }

    public final long itemId;
    public final String remotePath;
    public final String logicalKey;
    public final long length;
    public final String contentType;
    public final StreamDigests digests;
    public JSONObject state;

    private final Source source;
    private final Hooks hooks;
    private StreamDigests.Checkpoint chunkStart;

    public UploadJob(long itemId, String remotePath, String logicalKey, long length, String contentType,
                     JSONObject state, Source source, Hooks hooks, StreamDigests digests) {
        this.itemId = itemId;
        this.remotePath = remotePath;
        this.logicalKey = logicalKey;
        this.length = length;
        this.contentType = contentType == null ? "application/octet-stream" : contentType;
        this.state = state == null ? new JSONObject() : state;
        this.source = source;
        this.hooks = hooks;
        this.digests = digests;
    }

    public void saveState(long confirmed) {
        hooks.persist(state, confirmed);
    }

    public void saveState() {
        hooks.persist(state, state.optLong("confirmed", 0));
    }

    public boolean cancelled() {
        return hooks.cancelled();
    }

    public InputStream open(long offset) throws IOException {
        return source.open(offset);
    }

    /** Core marks the digest state at the start of each chunk. */
    public void markChunkStartForCore() {
        chunkStart = digests.checkpoint();
    }

    /** Drivers call this before re-sending the same chunk (e.g. after an in-put token refresh). */
    public void rewindChunk() {
        if (chunkStart == null) return;
        try {
            digests.restore(chunkStart);
            chunkStart = digests.checkpoint();
        } catch (CloneNotSupportedException ignored) {
        }
    }

    /** Streams [offset, offset+len) with pacing and hashing. One-shot: never replayed by OkHttp. */
    public ChunkBody body(long offset, long len, String mediaType) {
        return new ChunkBody(offset, len, mediaType == null ? contentType : mediaType);
    }

    public Response execute(OkHttpClient client, Request request) throws DestError {
        if (hooks.cancelled()) throw DestError.aborted();
        Call call = client.newCall(request);
        hooks.onCall(call);
        try {
            return call.execute();
        } catch (IOException e) {
            if (hooks.cancelled() || call.isCanceled()) throw DestError.aborted();
            throw DestError.network(e);
        } finally {
            hooks.onCall(null);
        }
    }

    /** Non-HTTP transports (SFTP): copies [offset, offset+len) with the same pacing and hashing. */
    public long copyChunk(long offset, long len, java.io.OutputStream out) throws IOException {
        return copyRange(offset, len, (buf, off, n) -> out.write(buf, off, n), false);
    }

    @FunctionalInterface
    private interface ByteWriter {
        void write(byte[] bytes, int offset, int length) throws IOException;
    }

    /** Shared pacing, cancellation, whole-object hashing and byte accounting. */
    private long copyRange(long offset, long len, ByteWriter writer, boolean detailedEof) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long sent = 0;
        try (InputStream in = source.open(offset)) {
            while (sent < len) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, len - sent));
                if (n < 0) throw new IOException(detailedEof
                        ? "source ended early at " + (offset + sent) : "source ended early");
                try {
                    hooks.pace(n);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
                if (hooks.cancelled()) throw new IOException("cancelled");
                if (digests != null) digests.update(buf, 0, n);
                writer.write(buf, 0, n);
                sent += n;
                hooks.onBytes(n);
            }
        }
        return sent;
    }

    public final class ChunkBody extends RequestBody {
        private final long offset;
        private final long len;
        private final MediaType type;
        private final java.security.MessageDigest md5;
        private boolean written;
        private long sent;

        ChunkBody(long offset, long len, String mediaType) {
            this.offset = offset;
            this.len = len;
            this.type = MediaType.parse(mediaType);
            java.security.MessageDigest d = null;
            try {
                d = java.security.MessageDigest.getInstance("MD5");
            } catch (Exception ignored) {
            }
            this.md5 = d;
        }

        @Override
        public MediaType contentType() {
            return type;
        }

        @Override
        public long contentLength() {
            return len;
        }

        @Override
        public boolean isOneShot() {
            return true;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            if (written) throw new IOException("chunk body replay refused");
            written = true;
            copyRange(offset, len, (buf, off, n) -> {
                if (md5 != null) md5.update(buf, off, n);
                sink.write(buf, off, n);
                sent += n;
            }, true);
        }

        public long sent() {
            return sent;
        }

        public String md5Hex() {
            return md5 == null ? null : StreamDigests.hex(md5.digest());
        }
    }
}
