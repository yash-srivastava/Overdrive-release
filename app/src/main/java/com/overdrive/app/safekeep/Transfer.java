package com.overdrive.app.safekeep;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.byd.cloud.crypto.CredentialCipher;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.safekeep.crypto.AgeCodec;
import com.overdrive.app.safekeep.dest.DestError;
import com.overdrive.app.safekeep.dest.Destination;
import com.overdrive.app.safekeep.dest.UploadJob;
import com.overdrive.app.safekeep.util.StreamDigests;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;

/** One object → one destination. Checkpoints every chunk; any kill resumes from the last one. */
final class Transfer implements Runnable {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SafeKeepXfer");
    private static final long MIN_GONE_GAP_MS = 10 * 60_000L;
    private static final long SOURCE_SETTLE_MS = 30_000L;
    private static final long SMALL_FILE_BYTES = 16L * 1024 * 1024;

    final Engine engine;
    final Ledger.Item item;
    final Destination dest;
    final SafeKeepConfig.Destination dcfg;
    final SafeKeepConfig.Route route;

    private volatile boolean cancel;
    private volatile boolean yield;
    private volatile boolean pause;
    private volatile boolean routeChanged;
    private volatile Call call;
    private volatile long length;
    private volatile long confirmed;
    private volatile long sentInChunk;
    private volatile long startedAt;
    private volatile double measuredBps;
    private volatile boolean metered;
    private volatile String pauseReason;
    private volatile File sourceFile;
    private final com.overdrive.app.safekeep.util.TokenBucket routeBucket =
            new com.overdrive.app.safekeep.util.TokenBucket(0);

    Transfer(Engine engine, Ledger.Item item, Destination dest, SafeKeepConfig.Destination dcfg,
             SafeKeepConfig.Route route) {
        this.engine = engine;
        this.item = item;
        this.dest = dest;
        this.dcfg = dcfg;
        this.route = route;
    }

    void cancel() {
        cancel = true;
        abortCall();
    }

    void cancelForRouteChange() {
        routeChanged = true;
        abortCall();
    }

    void requestYield() {
        yield = true;
    }

    void requestPause() {
        pause = true;
        abortCall();
    }

    private void abortCall() {
        Call c = call;
        if (c != null) c.cancel();
    }

    long length() {
        return length;
    }

    long confirmedBytes() {
        return confirmed + sentInChunk;
    }

    JSONObject progressJson() {
        JSONObject o = new JSONObject();
        try {
            String name = item.localPath != null ? new File(item.localPath).getName()
                    : item.remotePath.substring(item.remotePath.lastIndexOf('/') + 1);
            o.put("id", item.id).put("name", name).put("dest", item.destId).put("lane", item.lane)
                    .put("sent", confirmedBytes()).put("size", length)
                    .put("kbps", Math.round(measuredBps * 8 / 1000));
        } catch (Exception ignored) {
        }
        return o;
    }

    @Override
    public void run() {
        startedAt = System.currentTimeMillis();
        try {
            execute();
        } catch (DestError e) {
            onError(e);
        } catch (Throwable t) {
            onError(new DestError(DestError.Kind.NETWORK, String.valueOf(t.getMessage())));
        } finally {
            engine.onTransferDone(this);
        }
    }

    com.overdrive.app.safekeep.util.TokenBucket routeBucket() {
        return routeBucket;
    }

    private void execute() throws Exception {
        final Ledger ledger = engine.ledger;
        Naming.Kind kind = Naming.Kind.parse(item.kind);
        File src;
        if (kind == Naming.Kind.TRIP_ROW || kind == Naming.Kind.TRIP_GPX || kind == Naming.Kind.SETTINGS) {
            src = Staging.materialize(item);
            if (src == null) {
                ledger.markState(item.id, Ledger.SKIPPED, "LOCAL_IO", "source no longer exists");
                return;
            }
            // Staging records what this generation really carries (change detection compares against it).
            if (kind == Naming.Kind.TRIP_ROW || kind == Naming.Kind.SETTINGS) ledger.saveMeta(item.id, item.metaJson);
        } else {
            src = item.localPath == null ? null : new File(item.localPath);
            if (src == null || !RemovableStorageFs.isFile(src)) {
                handleMissing();
                return;
            }
            long len = RemovableStorageFs.length(src);
            long mtime = RemovableStorageFs.lastModified(src);
            if (len == 0) {
                handleEmpty();
                return;
            }
            // Small sidecars are rewritten in place (place names, geo backfill): compare mtime too.
            boolean rewritten = len < SMALL_FILE_BYTES && item.mtime > 0 && mtime != item.mtime;
            if (len != item.sizeBytes || rewritten) {
                // Changed after it was queued: record the new size, drop any partial session, let it settle.
                engine.abandonSession(item);
                ledger.updateSource(item.id, len, mtime);
                ledger.markRetry(item.id, "LOCAL_IO", "source changed; restarting",
                        System.currentTimeMillis() + SOURCE_SETTLE_MS, false);
                return;
            }
        }
        final File file = src;
        sourceFile = src;
        final long plainLen = RemovableStorageFs.length(file);

        AgeCodec.Material material = null;
        if (item.encrypted) {
            material = loadOrCreateMaterial();
            if (material == null) throw new DestError(DestError.Kind.PERMANENT, "no valid age recipient");
        }
        final AgeCodec.Material m = material;
        length = m != null ? m.ciphertextLength(plainLen) : plainLen;

        String verify = dest.caps().verify;
        StreamDigests digests = new StreamDigests("md5".equals(verify), true, "dropbox".equals(verify));
        JSONObject state;
        try {
            state = item.sessionJson == null || item.sessionJson.isEmpty() ? new JSONObject()
                    : new JSONObject(item.sessionJson);
        } catch (Exception e) {
            state = new JSONObject();
        }
        String contentType = Naming.contentType(item.remotePath);
        UploadJob.Source source = offset -> m != null
                ? AgeCodec.openCiphertext(m, file, plainLen, offset)
                : openPlain(file, offset);
        UploadJob job = new UploadJob(item.id, item.remotePath, item.logicalKey, length, contentType, state,
                source, hooks(), digests);

        try {
            runJob(job, source, digests);
        } finally {
            dest.release(job);
        }
    }

    private void runJob(UploadJob job, UploadJob.Source source, StreamDigests digests) throws Exception {
        final Ledger ledger = engine.ledger;
        // Same gate as every chunk, before any network call or rehash.
        String first = engine.gateChunk(this);
        if (first != null) {
            pauseReason = first;
            throw DestError.aborted();
        }
        dest.begin(job);
        long off = dest.confirmed(job);
        if (off < 0 || off > length) throw new DestError(DestError.Kind.SESSION_GONE, "remote offset out of range");
        if (off > 0) rehash(source, digests, off);
        confirmed = off;

        while (off < length) {
            String hold = engine.gateChunk(this);
            if (hold != null) {
                pauseReason = hold;
                throw DestError.aborted();
            }
            int suggested = Governor.suggestChunk(effectiveRate());
            int n = (int) Math.min(dest.chunkSize(job, off, suggested), length - off);
            if (n <= 0) throw new DestError(DestError.Kind.PERMANENT, "driver returned an empty chunk");
            job.markChunkStartForCore();
            StreamDigests.Checkpoint ck = digests.checkpoint();
            long t0 = System.nanoTime();
            sentInChunk = 0;
            long next;
            try {
                next = dest.put(job, off, n);
            } catch (DestError e) {
                if (ck != null) digests.restore(ck);
                throw e;
            } finally {
                sentInChunk = 0;
            }
            double secs = Math.max(0.001, (System.nanoTime() - t0) / 1e9);
            measuredBps = measuredBps <= 0 ? n / secs : (measuredBps * 0.5 + (n / secs) * 0.5);
            if (next != off + n) {
                // Server kept a different amount than was sent: re-derive digests for what it holds.
                if (ck == null || next < off) {
                    digests.reset();
                    feed(source, digests, 0, next);
                } else {
                    digests.restore(ck);
                    feed(source, digests, off, next);
                }
            }
            if (next <= off && next < length) throw new DestError(DestError.Kind.NETWORK, "no progress");
            off = next;
            confirmed = off;
            ledger.saveSession(item.id, job.state.toString(), off);
        }

        Destination.RemoteRef ref = dest.finish(job);
        verifyRef(ref, digests);
        finishDone(job, ref, digests);
    }

    private void finishDone(UploadJob job, Destination.RemoteRef ref, StreamDigests digests) throws Exception {
        final Ledger ledger = engine.ledger;
        File file = sourceFile;
        ledger.markDone(item.id, ref.ref, digests.md5Hex(), digests.sha256Hex(), System.currentTimeMillis());
        if (ref.webUrl != null && !ref.webUrl.isEmpty()) {
            JSONObject meta = item.meta();
            meta.put("webUrl", ref.webUrl);
            item.metaJson = meta.toString();
            ledger.saveMeta(item.id, item.metaJson);
        }
        engine.noteDestOk(dcfg.id);
        engine.onItemDone(item, ref, digests.sha256Hex(), file);
    }

    private UploadJob.Hooks hooks() {
        return new UploadJob.Hooks() {
            @Override
            public void persist(JSONObject state, long conf) {
                engine.ledger.saveSession(item.id, state.toString(), conf);
            }

            @Override
            public void pace(int bytes) throws InterruptedException {
                java.util.function.BooleanSupplier stop = () -> cancel || routeChanged || pause;
                engine.diskPace(bytes, stop);
                routeBucket.acquire(bytes, stop);
                engine.governor.bucket().acquire(bytes, stop);
            }

            @Override
            public boolean cancelled() {
                return cancel || routeChanged || pause;
            }

            @Override
            public void onCall(Call c) {
                call = c;
            }

            @Override
            public void onBytes(int n) {
                sentInChunk += n;
                if (metered) engine.addMobileBytes(n);
            }
        };
    }

    void setMetered(boolean m) {
        metered = m;
    }

    boolean isYieldRequested() {
        return yield;
    }

    boolean isPauseRequested() {
        return pause;
    }

    double effectiveRate() {
        double cap = engine.governor.bucket().getRate();
        double own = routeBucket.getRate();
        if (own > 0 && (cap <= 0 || own < cap)) cap = own;
        if (cap > 0) return measuredBps > 0 ? Math.min(cap, measuredBps) : cap;
        return measuredBps;
    }

    private AgeCodec.Material loadOrCreateMaterial() throws Exception {
        if (item.cryptJson != null && !item.cryptJson.isEmpty()) {
            String plain = CredentialCipher.decrypt(item.cryptJson);
            if (plain != null && !plain.isEmpty()) return AgeCodec.Material.fromJson(new JSONObject(plain));
        }
        List<byte[]> recips = new ArrayList<>();
        for (String r : dcfg.recipients) {
            try {
                recips.add(AgeCodec.parseRecipient(r));
            } catch (Exception ignored) {
            }
        }
        if (recips.isEmpty()) return null;
        AgeCodec.Material m = AgeCodec.newMaterial(recips);
        String enc = CredentialCipher.encrypt(m.toJson().toString());
        if (!CredentialCipher.isEncrypted(enc)) throw new DestError(DestError.Kind.LOCAL_IO, "cannot protect file key");
        engine.ledger.saveCrypt(item.id, enc);
        engine.ledger.clearSessionKeepCrypt(item.id);
        item.cryptJson = enc;
        item.sessionJson = null;
        return m;
    }

    private static InputStream openPlain(File f, long offset) throws IOException {
        FileInputStream in = RemovableStorageFs.openInput(f);
        try {
            if (offset > 0) in.getChannel().position(offset);
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return in;
    }

    /** Rebuilds digests for bytes the server already holds (after a restart), at the disk budget. */
    private void rehash(UploadJob.Source source, StreamDigests d, long upTo) throws Exception {
        d.reset();
        feed(source, d, 0, upTo);
    }

    private void feed(UploadJob.Source source, StreamDigests d, long from, long to) throws Exception {
        if (to <= from) return;
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = source.open(from)) {
            long remaining = to - from;
            while (remaining > 0) {
                if (cancel || pause || yield) throw DestError.aborted();
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new DestError(DestError.Kind.LOCAL_IO, "source ended during rehash");
                d.update(buf, 0, n);
                remaining -= n;
                engine.diskPace(n, () -> cancel || pause || yield);
            }
        }
    }

    private void verifyRef(Destination.RemoteRef ref, StreamDigests d) throws DestError {
        if (ref == null || ref.ref == null) throw new DestError(DestError.Kind.PERMANENT, "no remote reference");
        if (ref.size >= 0 && ref.size != length) {
            throw new DestError(DestError.Kind.SESSION_GONE,
                    "remote size " + ref.size + " != " + length + "; re-uploading");
        }
        String v = dest.caps().verify;
        if ("md5".equals(v) && ref.md5 != null && !ref.md5.isEmpty()
                && !ref.md5.equalsIgnoreCase(d.md5Hex())) {
            throw new DestError(DestError.Kind.SESSION_GONE, "checksum mismatch; re-uploading");
        }
        if ("dropbox".equals(v) && ref.contentHash != null && !ref.contentHash.isEmpty()
                && !ref.contentHash.equalsIgnoreCase(d.dropboxHex())) {
            throw new DestError(DestError.Kind.SESSION_GONE, "checksum mismatch; re-uploading");
        }
    }

    /**
     * Zero bytes: a sidecar still being (re)written. Wait 10 min from the first empty reading, then
     * drop it (revived if it fills).
     */
    private void handleEmpty() {
        long now = System.currentTimeMillis();
        JSONObject meta = item.meta();
        long since = meta.optLong("emptySince", 0);
        if (since <= 0) {
            try {
                item.metaJson = meta.put("emptySince", now).toString();
                engine.ledger.saveMeta(item.id, item.metaJson);
            } catch (Exception ignored) {
            }
            since = now;
        }
        if (now - since < MIN_GONE_GAP_MS) {
            engine.ledger.markRetry(item.id, "LOCAL_IO", "file is still empty", now + 60_000L, false);
        } else {
            engine.ledger.markState(item.id, Ledger.SKIPPED, Ledger.SKIP_EMPTY, "empty file");
            engine.retention.unpinIfIdle(item.localPath, engine.ledger);
        }
    }

    private void handleMissing() {
        long now = System.currentTimeMillis();
        File f = item.localPath == null ? null : new File(item.localPath);
        File dir = f == null ? null : f.getParentFile();
        String[] listing = dir == null ? null : RemovableStorageFs.list(dir);
        boolean enumerable = listing != null && listing.length > 0;
        if (!enumerable) {
            // Unmounted or unreadable volume reads as "unknown", never as "deleted" (CS-8).
            engine.ledger.markRetry(item.id, "LOCAL_IO", "storage not available", now + MIN_GONE_GAP_MS, false);
            return;
        }
        if (item.missingSince <= 0) {
            engine.ledger.setMissingSince(item.id, now);
            engine.ledger.markRetry(item.id, "LOCAL_IO", "file missing; re-checking", now + MIN_GONE_GAP_MS, false);
            return;
        }
        if (now - item.missingSince >= MIN_GONE_GAP_MS) {
            engine.abandonSession(item);
            engine.ledger.markState(item.id, Ledger.GONE, "LOCAL_IO", "deleted before upload");
            engine.retention.unpin(item.localPath);
        } else {
            engine.ledger.markRetry(item.id, "LOCAL_IO", "file missing; re-checking", item.missingSince + MIN_GONE_GAP_MS, false);
        }
    }

    private void onError(DestError e) {
        final Ledger ledger = engine.ledger;
        long now = System.currentTimeMillis();
        switch (e.kind) {
            case ABORTED:
                if (cancel) {
                    ledger.markRetry(item.id, null, "stopped", now, false);
                } else if (routeChanged) {
                    ledger.markRetry(item.id, "paused", "network changed", now + 3_000, false);
                } else if (yield) {
                    ledger.markRetry(item.id, null, "yielded to a higher-priority item", now, false);
                } else {
                    String r = pauseReason != null ? pauseReason : (pause ? "paused_by_user" : "paused");
                    ledger.markRetry(item.id, r, r, now + 15_000, false);
                }
                return;
            case AUTH_EXPIRED:
                engine.setDestState(dcfg.id, "needs_auth", e.getMessage(), 0);
                ledger.markRetry(item.id, "needs_auth", e.getMessage(), now + 3600_000L, false);
                break;
            case QUOTA_FULL:
                engine.setDestState(dcfg.id, "quota_full", e.getMessage(), now + 30 * 60_000L);
                ledger.markRetry(item.id, "quota_full", e.getMessage(), now + 30 * 60_000L, false);
                engine.maintenance.quotaPressure(dcfg.id);
                break;
            case RATE_LIMITED: {
                long wait = e.retryAfterMs > 0 ? Math.min(e.retryAfterMs, 3600_000L) : 60_000L;
                Ledger.DestState ds = engine.destState(dcfg.id);
                ds.backoffUntil = now + wait;
                engine.ledger.saveDestState(dcfg.id, ds);
                ledger.markRetry(item.id, "rate_limited", e.getMessage(), now + wait, false);
                break;
            }
            case SESSION_GONE:
                ledger.clearSessionKeepCrypt(item.id);
                retryCounted(e, now + 2_000, 6);
                break;
            case CLOCK_SKEW:
                retryCounted(e, now + 5_000, 10);
                break;
            case LOCAL_IO:
                ledger.markRetry(item.id, "LOCAL_IO", e.getMessage(), now + MIN_GONE_GAP_MS, false);
                break;
            case PERMANENT:
                retryCounted(e, now + 5 * 60_000L, 3);
                break;
            case CONFLICT:
                retryCounted(e, now + 60_000L, 10);
                break;
            case NETWORK:
            default: {
                int n = Math.min(priorAttempts(e), 6);
                retryCounted(e, now + Math.min(10 * 60_000L, 10_000L << n), 50);
                break;
            }
        }
        engine.lastError = e.kind.name().toLowerCase(java.util.Locale.ROOT) + ": " + e.getMessage();
        SafeKeepSignals.publishError(e.kind);
        logger.warn("Upload " + item.logicalKey + " → " + dcfg.id + " failed (" + e.kind + "): " + e.getMessage());
    }

    /** Attempts already spent on this error kind (the counter restarts when the kind changes). */
    private int priorAttempts(DestError e) {
        return e.kind.name().equals(item.attemptClass) ? item.attempts : 0;
    }

    /** Overall ceiling: alternating error kinds must not buy endless full re-uploads. */
    private static final int MAX_TOTAL_ATTEMPTS = 100;

    private void retryCounted(DestError e, long when, int maxAttempts) {
        if (priorAttempts(e) + 1 >= maxAttempts || item.totalAttempts + 1 >= MAX_TOTAL_ATTEMPTS) {
            Ledger.Item fresh = engine.ledger.get(item.id);
            engine.abandonSession(fresh != null ? fresh : item);
            engine.ledger.markState(item.id, Ledger.FAILED, e.kind.name(), e.getMessage());
            engine.retention.unpinIfIdle(item.localPath, engine.ledger);
        } else {
            engine.ledger.markRetry(item.id, e.kind.name(), e.getMessage(), when, true);
        }
    }
}
