package com.overdrive.app.surveillance;

import android.media.MediaCodec;

import com.overdrive.app.logging.DaemonLogger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H264ByteRingBuffer — SOTA pre-record ring with constant memory budget.
 *
 * <p>Replaces a legacy slot-pool design (every packet got a 1 MB slot
 * whether it was a 30 KB P-frame or a 1 MB I-frame, wasting ~80% of the
 * budget on padding) with a single contiguous direct {@link ByteBuffer}
 * arena + a parallel primitive-array index of packet headers. Bytes pack
 * tightly; only headers (~96 KB total) live on the Java heap.
 *
 * <h3>Why this is a win for our consumer</h3>
 * <ul>
 *   <li><b>Density:</b> 64 MB budget at MAX H.265/30fps holds ~50 s of
 *       footage. The slot-pool buffer holds ~5 s at the same budget
 *       and OOMs the daemon if you ask for more.</li>
 *   <li><b>Bitrate-agnostic:</b> the ring doesn't care about
 *       per-packet ceilings. Quality changes never recreate the buffer.
 *       Eliminates the 4-axis (duration/fps/bitrate/codec) reuse logic
 *       in the consumer.</li>
 *   <li><b>Boot:</b> one {@code allocateDirect(BUDGET)} (~30-80 ms) instead
 *       of N×1MB direct allocations (each is a separate mmap round-trip).</li>
 *   <li><b>Flush:</b> consumer borrows {@link Cursor}s and reads bytes
 *       directly out of the ring, never deep-copying. Snapshot stability
 *       is provided by the seqlock + pin — see {@link Cursor}.</li>
 * </ul>
 *
 * <h3>Concurrency model</h3>
 * Single producer (encoder drainer thread, calls {@link #add}) and a single
 * consumer (event handler thread, calls {@link #beginFlush} / {@link Cursor}
 * methods). Producer never locks; header publication uses a seqlock
 * ({@link AtomicLong} even/odd). Consumer pins the read offset before
 * iterating. Event cursors use a breakable pin; manual replay uses a strong
 * pin that may drop new packets from this history ring while the independent
 * live muxer keeps receiving them.
 *
 * <h3>Failure modes</h3>
 * <ul>
 *   <li><b>Packet larger than budget/2:</b> dropped (defensive; never observed
 *       in practice — at MAX H.264 worst-case I-frame is ~3 MB, budget is 64 MB).</li>
 *   <li><b>Header table exhausted:</b> evict from tail until a slot is free.
 *       Header table is sized for 4096 packets — at 30fps × 62s = 1860
 *       packets, we retain more than 2× headroom.</li>
 *   <li><b>Breakable event pin:</b> P-frames may drop from history; an IDR may
 *       break the pin and leave the event with a shorter pre-roll.</li>
 *   <li><b>Strong replay pin:</b> any new packet that would overwrite unread
 *       replay bytes drops only from history. The ring rebuilds from the next
 *       IDR after export, while live muxers continue uninterrupted.</li>
 * </ul>
 */
public class H264ByteRingBuffer {
    private static final DaemonLogger logger = DaemonLogger.getInstance("H264ByteRing");

    /** Maximum number of packet headers we can index simultaneously. Power
     * of two so we can use bitwise mask instead of modulo. At 30 fps × 62 s
     * = 1860 packets, 4096 gives more than 2× headroom. */
    private static final int HEADER_CAPACITY = 4096;
    private static final int HEADER_MASK = HEADER_CAPACITY - 1;
    /** A larger/backward source-PTS step is a camera clock-domain change, not
     * real elapsed footage. Keeping packets across that boundary makes range
     * selection ambiguous and can produce hour-long MP4 timelines. */
    private static final long MAX_PLAUSIBLE_INTERFRAME_GAP_US = 10_000_000L;

    static {
        if ((HEADER_CAPACITY & HEADER_MASK) != 0) {
            throw new AssertionError("HEADER_CAPACITY must be power of two");
        }
    }

    // ── Bitstream payload ────────────────────────────────────────────────
    /** Single contiguous direct buffer. Allocated once at construction. Never
     * resized. Native size charged to Java heap on Android. */
    private final ByteBuffer payload;
    /** Capacity of {@link #payload}, cached to avoid bounds-check overhead. */
    private final int budget;
    /** Producer-thread-only view into {@link #payload} for slicing source
     * data. Reused across {@link #add} calls to avoid a per-frame
     * {@code data.duplicate()} allocation (~40 bytes/frame at 30 fps). */
    private final ByteBuffer producerPayloadView;
    /** Consumer-thread-only view into {@link #payload}. Used by
     * {@link Cursor#next} for reading bytes out without ever calling
     * {@code payload.duplicate()} concurrently with the producer's
     * {@code payload.position(...)} writes — those non-atomic field
     * mutations would otherwise tear the duplicate's mark/pos/lim
     * tuple and rarely throw {@link IllegalArgumentException} from
     * the {@code DirectByteBuffer} ctor's invariant check. Acquired
     * once at construction (quiescence) and never re-duplicated. The
     * consumer thread is single-threaded and only mutates THIS view's
     * position/limit, never {@link #payload}'s. */
    private final ByteBuffer consumerPayloadView;

    // ── Header table (parallel primitive arrays for cache locality) ──────
    /** Byte offset into {@link #payload} where each packet starts. */
    private final int[] hOffset = new int[HEADER_CAPACITY];
    /** Length in bytes of each packet's payload. */
    private final int[] hLength = new int[HEADER_CAPACITY];
    /** Presentation timestamp (microseconds) of each packet. */
    private final long[] hPts = new long[HEADER_CAPACITY];
    /** {@link MediaCodec.BufferInfo#flags} bits — most importantly
     * {@link MediaCodec#BUFFER_FLAG_KEY_FRAME}. */
    private final int[] hFlags = new int[HEADER_CAPACITY];

    // ── Cursors (monotonic; logical positions, modulo HEADER_CAPACITY for
    //    table indexing and modulo budget for byte indexing) ─────────────
    /** Index of the oldest valid header. Bumped by eviction. Read-modify
     * by producer only; consumer reads via seqlock. */
    private long headerHead;
    /** Index just past the newest valid header. Bumped by {@link #add}. */
    private long headerTail;
    /** Byte offset of the oldest stored byte. */
    private long bytesHead;
    /** Byte offset just past the newest stored byte. */
    private long bytesTail;

    /** Number of keyframes currently in the ring. Producer-only writes. */
    private int keyframeCount;
    /** Minimum keyframes the ring must retain even when over duration budget.
     * Computed from {@code (durationSec / 2) + 2} (encoder uses 2-second GOP).
     * <p>volatile because it is mutated by the control plane (setMaxDurationUs
     * called from any thread) and read by the producer thread inside add()'s
     * prune loop. The producer's read happens-before the producer's eviction
     * decision; without volatile the JMM doesn't guarantee the new value is
     * visible to a producer running on a different CPU. */
    private volatile int minKeyframes;

    /** User-configured maximum window in microseconds. Producer prunes
     * headers whose PTS spans exceed this after each {@link #add}. */
    private volatile long maxDurationUs;

    // ── Seqlock (producer-side publication) ──────────────────────────────
    /** Even = stable, odd = mid-write. Producer increments before+after
     * mutating cursors; consumer reads even-on-entry, validates same on
     * exit. Lock-free read path. */
    private final AtomicLong seq = new AtomicLong(0);

    // ── Pin (consumer-side flush guard) ──────────────────────────────────
    private static final long NO_PIN = Long.MIN_VALUE;
    /** Producer claim used to linearize eviction against cursor acquisition. */
    private static final long EVICTING = Long.MIN_VALUE + 1L;

    private enum EvictionResult {
        EVICTED,
        BLOCKED_WEAK,
        BLOCKED_STRONG
    }

    /** Encoded byte offset the consumer is currently reading from. Producer's
     * eviction respects this — won't advance {@link #bytesHead} past
     * the decoded offset. Non-negative values are breakable event pins;
     * complemented (negative) values are strong manual-replay pins.
     * {@link #NO_PIN} means no pin is active.
     * <p>Volatile so producer sees the pin as soon as the consumer
     * sets it. */
    private final AtomicLong pinOffset = new AtomicLong(NO_PIN);

    /** A strong cursor had to reject at least one new history packet. Once the
     * cursor closes, drop dependent P-frames and rebuild from the next IDR. */
    private volatile boolean awaitRecoveryKeyframe;

    // ── Stats (debug) ────────────────────────────────────────────────────
    private long totalAdds;
    private long totalKeyDrops;
    private long totalPDrops;
    private long totalEvictions;

    /**
     * Create a ring buffer with the given native budget and initial duration
     * window. Both can be tuned at runtime via {@link #setMaxDurationUs}.
     *
     * @param budgetBytes      Total native-heap budget (e.g. 64 MB). Allocated
     *                         once. Must be ≥ 1 MB.
     * @param initialDurationS Internal retention window in seconds. 1-62; the
     *                         extra two seconds are GOP lead-in for a 60-second
     *                         user-visible replay.
     */
    public H264ByteRingBuffer(int budgetBytes, int initialDurationS) {
        if (budgetBytes < 1024 * 1024) {
            throw new IllegalArgumentException("budgetBytes too small: " + budgetBytes);
        }
        this.budget = budgetBytes;
        this.payload = ByteBuffer.allocateDirect(budgetBytes).order(ByteOrder.nativeOrder());
        // Duplicate ONCE at construction (quiescence — no concurrent producer).
        // Each thread thereafter mutates only its own view's position/limit;
        // the underlying native pointer + capacity are immutable.
        this.producerPayloadView = this.payload.duplicate().order(ByteOrder.nativeOrder());
        this.consumerPayloadView = this.payload.duplicate().order(ByteOrder.nativeOrder());
        setInitialDuration(initialDurationS);
        logger.info("H264ByteRingBuffer ready: budget=" + (budgetBytes / 1024 / 1024)
            + "MB, duration=" + initialDurationS + "s, minKeyframes="
            + minKeyframes + ", headerCap=" + HEADER_CAPACITY);
    }

    private void setInitialDuration(int durationSeconds) {
        int clamped = Math.max(1, Math.min(62, durationSeconds));
        this.maxDurationUs = clamped * 1_000_000L;
        this.minKeyframes = (clamped / 2) + 2;
    }

    /**
     * Add an encoded packet to the ring. Called from the encoder drainer
     * thread. Must not block, must not allocate.
     *
     * <p>Behavior on contention with an in-flight flush ({@link #pinOffset}
     * set):
     * <ul>
     *   <li>Breakable pin: drop a blocked P-frame; an IDR may break the pin.</li>
     *   <li>Strong pin: drop either frame type only from history and preserve
     *       the replay cursor.</li>
     * </ul>
     *
     * @param data Source ByteBuffer (typically the encoder's output buffer).
     *             Position/limit will be modified.
     * @param info Source metadata. Only {@code offset}, {@code size},
     *             {@code presentationTimeUs}, and {@code flags} are read.
     */
    public void add(ByteBuffer data, MediaCodec.BufferInfo info) {
        final int sz = info.size;
        if (sz <= 0) return;
        final boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (sz > budget / 2) {
            logger.warn("Dropping pathological packet (size=" + sz + " > budget/2)");
            if (isStrongPin(pinOffset.get())) awaitRecoveryKeyframe = true;
            return;
        }

        // A strong replay cursor may temporarily reject new packets from this
        // history ring. Those packets still reach the live muxer, but their
        // reference chain is incomplete here. Re-arm history only at an IDR.
        if (awaitRecoveryKeyframe && pinOffset.get() == NO_PIN) {
            if (!isKey) {
                totalPDrops++;
                return;
            }
            clear();
            logger.info("Strong replay pin released; rebuilding history from IDR");
        }

        // The BYD camera can switch between HAL and CLOCK_MONOTONIC PTS
        // domains while the encoder remains alive. Range selection and
        // duration pruning both require a monotonic source timeline, so drop
        // history from the old domain instead of mixing incomparable PTSs.
        if (headerCount() > 0) {
            final int last = (int) ((headerTail - 1) & HEADER_MASK);
            final long sourceGapUs = info.presentationTimeUs - hPts[last];
            if (sourceGapUs < 0 || sourceGapUs > MAX_PLAUSIBLE_INTERFRAME_GAP_US) {
                if (isStrongPin(pinOffset.get())) {
                    awaitRecoveryKeyframe = true;
                    if (isKey) totalKeyDrops++; else totalPDrops++;
                    return;
                }
                logger.warn("Pre-record PTS discontinuity (gap=" + sourceGapUs
                    + "us) - clearing history before accepting the new clock domain");
                clear();
            }
        }

        // 1. Make room. Evict from tail until we have `sz` free bytes AND
        //    a free header slot. Respect the pin: drop new P-frames, break
        //    pin for new I-frames.
        while (freeBytes() < sz || headerCount() >= HEADER_CAPACITY) {
            EvictionResult result = evictTail(isKey);
            if (result != EvictionResult.EVICTED) {
                // A weak pin drops P-frames; a strong replay pin drops either
                // frame type from history rather than aborting the cursor.
                if (result == EvictionResult.BLOCKED_STRONG) {
                    awaitRecoveryKeyframe = true;
                }
                if (isKey) totalKeyDrops++;
                else totalPDrops++;
                return;
            }
        }

        // 2. Write payload bytes through the producer's private view (never
        //    via `payload` directly — the consumer thread holds its own view
        //    and a concurrent ByteBuffer.duplicate() would race the
        //    payload.position(...) writes here, occasionally throwing
        //    IllegalArgumentException from the duplicate ctor's
        //    mark/pos/limit invariant check). The producer's view shares
        //    `payload`'s native bytes but has its own position/limit fields.
        final int writePos = (int) (bytesTail % budget);
        if (writePos + sz <= budget) {
            // Note: Buffer.position(int)/limit(int) return `Buffer` (covariant
            // override added in Java 9), so the chained form fails on Android's
            // older bytecode target. Split into discrete statements.
            final ByteBuffer src = data.duplicate();
            src.position(info.offset);
            src.limit(info.offset + sz);
            producerPayloadView.position(writePos);
            producerPayloadView.put(src);
        } else {
            // Wrap: split into [writePos..budget) + [0..remaining).
            final int firstChunk = budget - writePos;
            final ByteBuffer src = data.duplicate();
            src.position(info.offset);
            src.limit(info.offset + firstChunk);
            producerPayloadView.position(writePos);
            producerPayloadView.put(src);

            // Defensive: explicitly set src.position. The Java spec guarantees
            // ByteBuffer.put(ByteBuffer) advances src.position by the number
            // of bytes copied, but resetting it explicitly removes a fragile
            // dependency on that side effect for future readers of this code.
            src.position(info.offset + firstChunk);
            src.limit(info.offset + sz);
            producerPayloadView.position(0);
            producerPayloadView.put(src);
        }

        // 3. Publish header. Seqlock: pre-increment to odd, mutate, post-increment to even.
        //
        // Memory-model note (load-bearing): `lazySet` on AtomicLong is
        // `putOrderedLong` underneath — it has *release* semantics. That
        // means all plain stores BEFORE the second lazySet (the array
        // writes below) are guaranteed visible-before the seq=even value
        // becomes visible to any consumer that performs a volatile (acquire)
        // read of seq. This is what makes the seqlock work without an
        // explicit StoreStore fence between the array writes and the
        // publish. JSR-133 §17.4.5 covers the guarantee. Don't downgrade
        // either lazySet to a plain store; don't reorder array writes
        // after the second lazySet.
        seq.lazySet(seq.get() + 1);  // odd
        final int h = (int) (headerTail & HEADER_MASK);
        hOffset[h] = writePos;
        hLength[h] = sz;
        hPts[h]    = info.presentationTimeUs;
        hFlags[h]  = info.flags;
        headerTail++;
        bytesTail += sz;
        if (isKey) keyframeCount++;
        seq.lazySet(seq.get() + 1);  // even, published (release fence)

        totalAdds++;

        // 4. Duration-based pruning. Memory-budget eviction in step 1
        //    keeps RAM bounded; this enforces the user's chosen window.
        //
        // CRITICAL: this loop respects the pin via evictTail(false). At MAX
        // settings under saturated steady-state (every add at duration cap),
        // bypassing the pin here would let a flush-in-flight cursor see its
        // bytes evicted, partially aborting the flush and silently shrinking
        // the user's pre-record window from the configured maximum to as little
        // as 1-2s. By respecting the pin during duration-prune, the producer
        // pauses pruning while a flush holds the read frontier; the buffer
        // briefly exceeds maxDurationUs by the flush's duration (~150 ms),
        // then prunes back to the configured window after the cursor closes.
        // The keyframe floor (minKeyframes) still applies as a hard policy.
        while (currentDurationUs() > maxDurationUs && headerCount() > 1) {
            final int t = (int) (headerHead & HEADER_MASK);
            // Don't drop a keyframe when we're at the minimum.
            if ((hFlags[t] & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    && keyframeCount <= minKeyframes) {
                break;
            }
            // evictTail(false): pin-blocking eviction. If the pin holds, we
            // exit the loop without further pruning — the next add will
            // re-enter and try again. The maxDurationUs overshoot during
            // a flush is bounded by flush duration (~150 ms typical).
            if (evictTail(false) != EvictionResult.EVICTED) {
                break;
            }
        }
    }

    /**
     * Try to evict one packet from the tail. Returns false if blocked by
     * the pin (and `isKey` is false — keys break the pin).
     */
    private EvictionResult evictTail(boolean isKey) {
        if (headerCount() == 0) return EvictionResult.BLOCKED_WEAK;
        while (true) {
            final long pinToken = pinOffset.get();
            if (pinToken == EVICTING) {
                Thread.yield();
                continue;
            }
            final int t = (int) (headerHead & HEADER_MASK);
            final long tailEndsAt = bytesHead + hLength[t];
            if (pinToken != NO_PIN && tailEndsAt > decodePinOffset(pinToken)) {
                // Eviction would cross the pin's read frontier.
                if (isStrongPin(pinToken)) return EvictionResult.BLOCKED_STRONG;
                if (!isKey) return EvictionResult.BLOCKED_WEAK;
            }

            // Claim eviction in the same atomic state used by cursor pins. A
            // cursor cannot publish a pin after we inspected NO_PIN but before
            // evictTailOnce mutates the arena; active cursors briefly spin.
            if (!pinOffset.compareAndSet(pinToken, EVICTING)) continue;
            evictTailOnce();
            long restore = pinToken != NO_PIN
                    && tailEndsAt <= decodePinOffset(pinToken) ? pinToken : NO_PIN;
            pinOffset.compareAndSet(EVICTING, restore);
            return EvictionResult.EVICTED;
        }
    }

    private static boolean isStrongPin(long token) {
        return token < 0L && token != NO_PIN && token != EVICTING;
    }

    private static long encodePinOffset(long offset, boolean strong) {
        return strong ? ~offset : offset;
    }

    private static long decodePinOffset(long token) {
        return isStrongPin(token) ? ~token : token;
    }

    private void evictTailOnce() {
        seq.lazySet(seq.get() + 1);  // odd
        final int t = (int) (headerHead & HEADER_MASK);
        if ((hFlags[t] & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
            keyframeCount--;
        }
        bytesHead += hLength[t];
        headerHead++;
        seq.lazySet(seq.get() + 1);  // even
        totalEvictions++;
    }

    private int headerCount() {
        return (int) (headerTail - headerHead);
    }

    private int freeBytes() {
        return budget - (int) (bytesTail - bytesHead);
    }

    private long currentDurationUs() {
        if (headerCount() < 2) return 0;
        final int first = (int) (headerHead & HEADER_MASK);
        final int last = (int) ((headerTail - 1) & HEADER_MASK);
        return hPts[last] - hPts[first];
    }

    /**
     * Empty the buffer without releasing the underlying byte arena. Cheap.
     * Called between encoder reinitializations and from {@link #release}
     * when the encoder is being torn down for restart.
     *
     * <p>Resets {@link #pinOffset} too. Without this, an orphaned cursor
     * left over from an interrupted flush (e.g., daemon shutdown mid-event,
     * encoder reinit while a flush is in flight) keeps the pin set after
     * its bytes were cleared. The next encoder boot would see a stale pin
     * pointing at a now-evicted byte offset, blocking eviction of all
     * P-frames until the next keyframe arrives ~2s later — silently
     * truncating the new pre-record window.
     */
    public synchronized void clear() {
        seq.lazySet(seq.get() + 1);  // odd
        headerHead = headerTail = 0;
        bytesHead = bytesTail = 0;
        keyframeCount = 0;
        pinOffset.set(NO_PIN);
        awaitRecoveryKeyframe = false;
        seq.lazySet(seq.get() + 1);  // even
        logger.info("Buffer cleared (byte arena retained, pin released)");
    }

    /** Update the duration window. Cheap — no reallocation. */
    public void setMaxDurationUs(long newMaxUs) {
        long clamped = Math.max(1_000_000L, Math.min(62_000_000L, newMaxUs));
        this.maxDurationUs = clamped;
        // Recompute minKeyframes based on new duration so the prune-keep
        // policy adjusts.
        this.minKeyframes = (int) (clamped / 2_000_000L) + 2;
    }

    public long getMaxDurationUs() { return maxDurationUs; }

    /**
     * @return newest packet PTS, or {@link Long#MIN_VALUE} when the ring is
     *         empty. The result is a seqlock-consistent snapshot.
     */
    public long getLatestPtsUs() {
        return getBoundaryPtsUs(false);
    }

    /**
     * @return oldest packet PTS, or {@link Long#MIN_VALUE} when the ring is
     *         empty. The result is a seqlock-consistent snapshot.
     */
    public long getOldestPtsUs() {
        return getBoundaryPtsUs(true);
    }

    private long getBoundaryPtsUs(boolean oldest) {
        for (;;) {
            long s1 = seq.get();
            if ((s1 & 1L) != 0) {
                Thread.yield();
                continue;
            }

            long localHead = headerHead;
            long localTail = headerTail;
            long ptsUs = Long.MIN_VALUE;
            if (localHead < localTail) {
                long header = oldest ? localHead : localTail - 1;
                ptsUs = hPts[(int) (header & HEADER_MASK)];
            }

            long s2 = seq.get();
            if (s1 == s2) {
                return ptsUs;
            }
        }
    }

    /** @return current packet count (approximate when concurrent with add). */
    public int size() { return headerCount(); }

    /** @return current duration in seconds (approximate). */
    public double getDurationSeconds() { return currentDurationUs() / 1_000_000.0; }

    /** @return total bytes pinned by header table (approximate). */
    public int storedBytes() { return (int) (bytesTail - bytesHead); }

    /** @return fixed native payload arena capacity in bytes. */
    public int getBudgetBytes() { return budget; }

    public String getStats() {
        return String.format(
            "ByteRing: %d packets, %.1f sec, %d keyframes, %.1f MB stored, "
            + "adds=%d evicts=%d keyDrops=%d pDrops=%d",
            headerCount(), getDurationSeconds(), keyframeCount,
            storedBytes() / 1024.0 / 1024.0,
            totalAdds, totalEvictions, totalKeyDrops, totalPDrops);
    }

    // Structured stat accessors — exposed via /api/status so the UI can show
    // health (key drops should be zero in steady state; non-zero means the
    // pin held during a keyframe arrival, which is a SOTA-grade rare event
    // worth logging). All counters are diagnostic-only and read without
    // synchronization; values are eventually-consistent across threads.
    public long getTotalAdds()       { return totalAdds; }
    public long getTotalEvictions()  { return totalEvictions; }
    public long getTotalKeyDrops()   { return totalKeyDrops; }
    public long getTotalPDrops()     { return totalPDrops; }

    // ── Compatibility shims (called by HardwareEventRecorderGpu.init for
    //    triplet-mismatch detection in the legacy buffer path). The byte
    //    ring is bitrate- and fps-agnostic, so these return sentinels that
    //    cause the consumer's reuse logic to always reuse. ────────────────
    public int getSizedForFps() { return -1; }
    public int getSizedForBitrate() { return -1; }
    public int getPoolFreeCount() { return budget - storedBytes(); }

    // ────────────────────────────────────────────────────────────────────
    // CONSUMER API — flush
    // ────────────────────────────────────────────────────────────────────

    /**
     * Begin a flush. Pins the current read frontier so the producer won't
     * overwrite bytes the consumer is about to read. Returns a {@link Cursor}
     * positioned at the oldest keyframe in the buffer (or {@code null} if
     * the buffer has no keyframes — flush would be undecodable, skip).
     *
     * <p>The cursor is single-use. Call {@link Cursor#next} until it returns
     * {@code false}, then {@link Cursor#close} (which releases the pin).
     *
     * <p>If the producer breaks the pin while the cursor is in-flight,
     * {@link Cursor#next} returns {@code false} and {@link Cursor#aborted}
     * returns {@code true}. The partial flush captured up to that point is
     * still valid for muxing — the seqlock validation guarantees we only
     * yielded packets whose bytes were not overwritten.
     */
    public Cursor beginFlush() {
        if (awaitRecoveryKeyframe) return null;
        // Whole-walk seqlock retry: snapshot cursors, walk hFlags/hLength to
        // find the first keyframe and compute the pin offset, then revalidate
        // seq. If the producer evicted any header during the walk, retry from
        // scratch — without this, a stale firstKey/pinAt could point at bytes
        // the producer already overwrote, making the consumer read garbage
        // bitstream from the new packet that landed in the same slot.
        //
        // The walk is read-only, so retry is cheap. In practice the producer
        // only mutates seq during add() (~1 µs every 33 ms at 30 fps), so the
        // retry should happen approximately never under non-pathological load.
        for (int attempt = 0; attempt < 8; attempt++) {
            // Pre-init locals: the `continue` on odd-seq inside the do/while
            // skips the assignments, so the compiler's definite-assignment
            // check fails on the post-loop usage. The values are unused on
            // the continue path (loop reruns), but Java needs them assigned.
            long s1, s2 = 0L;
            long localHead = 0L, localTail = 0L, localBytesHead = 0L;
            // 1. Snapshot cursors under seqlock.
            do {
                s1 = seq.get();
                if ((s1 & 1L) != 0) { Thread.yield(); continue; }
                localHead = headerHead;
                localTail = headerTail;
                localBytesHead = bytesHead;
                s2 = seq.get();
            } while (s1 != s2);

            // 2. Walk for first keyframe.
            long firstKey = -1;
            for (long i = localHead; i < localTail; i++) {
                int idx = (int) (i & HEADER_MASK);
                if ((hFlags[idx] & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                    firstKey = i;
                    break;
                }
            }

            // 3. Validate the walk against the seqlock. If a producer write
            //    landed during steps 1-2, the read may be torn — retry.
            long sAfter = seq.get();
            if (sAfter != s2) {
                continue;
            }

            if (firstKey < 0) {
                logger.warn("beginFlush: no keyframe in buffer (count=" + (localTail - localHead) + ") — skipping flush");
                return null;
            }

            // 4. Sum lengths up to firstKey under seqlock revalidation.
            long pinAt = localBytesHead;
            for (long i = localHead; i < firstKey; i++) {
                pinAt += hLength[(int) (i & HEADER_MASK)];
            }
            long sFinal = seq.get();
            if (sFinal != s2) {
                continue;
            }

            // 5. Commit pin and return cursor. Never replace another consumer's
            // pin: manual replay and the event path may race during a mode
            // transition even though their callers normally serialize starts.
            if (!pinOffset.compareAndSet(NO_PIN, pinAt)) {
                if (pinOffset.get() == EVICTING) {
                    Thread.yield();
                    continue;
                }
                return null;
            }
            if (seq.get() != sFinal) {
                pinOffset.compareAndSet(pinAt, NO_PIN);
                continue;
            }
            return new Cursor(firstKey, localTail, pinAt,
                    hPts[(int) (firstKey & HEADER_MASK)],
                    hPts[(int) ((localTail - 1) & HEADER_MASK)], false);
        }
        logger.warn("beginFlush: producer churn defeated 8 retries — skipping flush");
        return null;
    }

    /**
     * Begin a flush for an inclusive PTS range. The cursor starts at the last
     * keyframe at or before {@code startPtsUs}. If the ring does not contain
     * such a keyframe, it starts at the first available keyframe after the
     * requested start. Consequently, the cursor may include a GOP lead-in
     * before {@code startPtsUs}, but never starts with an undecodable P-frame.
     * The cursor ends immediately after the last packet whose PTS is at most
     * {@code endPtsUs}.
     *
     * <p>Returns {@code null} when the range is invalid or has no stored packet,
     * no suitable keyframe exists inside the available portion of the range,
     * another cursor already owns the single-consumer pin, or producer churn
     * prevents a stable snapshot. The caller must close a non-null cursor.
     *
     * @param startPtsUs requested inclusive start PTS
     * @param endPtsUs requested inclusive end PTS
     */
    public Cursor beginFlushRange(long startPtsUs, long endPtsUs) {
        return beginFlushRange(startPtsUs, endPtsUs, false, Long.MIN_VALUE);
    }

    /** Event cursor like {@link #beginFlushRange} whose first keyframe is newer than {@code floorPtsUs}. */
    public Cursor beginFlushRangeAfter(long floorPtsUs, long startPtsUs, long endPtsUs) {
        return beginFlushRange(startPtsUs, endPtsUs, false, floorPtsUs);
    }

    /**
     * Manual-replay range cursor. Unlike the event cursor, its pin cannot be
     * broken by an incoming IDR. If the arena fills, new packets are omitted
     * only from history until this cursor closes; live muxers are unaffected.
     */
    public Cursor beginStrongFlushRange(long startPtsUs, long endPtsUs) {
        return beginFlushRange(startPtsUs, endPtsUs, true, Long.MIN_VALUE);
    }

    private Cursor beginFlushRange(long startPtsUs, long endPtsUs, boolean strong,
                                   long keyFloorPtsUs) {
        if (startPtsUs > endPtsUs) {
            return null;
        }
        if (awaitRecoveryKeyframe) return null;

        for (int attempt = 0; attempt < 8; attempt++) {
            long s1;
            long snapshotSeq = 0L;
            long localHead = 0L;
            long localTail = 0L;
            long localBytesHead = 0L;

            do {
                s1 = seq.get();
                if ((s1 & 1L) != 0) {
                    Thread.yield();
                    continue;
                }
                localHead = headerHead;
                localTail = headerTail;
                localBytesHead = bytesHead;
                snapshotSeq = seq.get();
            } while (s1 != snapshotSeq);

            if (localHead >= localTail) {
                return null;
            }

            long keyAtOrBeforeStart = -1L;
            long firstKeyAfterStart = -1L;
            boolean hasPacketInRange = false;
            for (long i = localHead; i < localTail; i++) {
                int idx = (int) (i & HEADER_MASK);
                long ptsUs = hPts[idx];
                if (ptsUs >= startPtsUs && ptsUs <= endPtsUs) {
                    hasPacketInRange = true;
                }
                if ((hFlags[idx] & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) {
                    continue;
                }
                if (ptsUs <= keyFloorPtsUs) {
                    continue;
                }
                if (ptsUs <= startPtsUs) {
                    keyAtOrBeforeStart = i;
                } else if (firstKeyAfterStart < 0L) {
                    firstKeyAfterStart = i;
                }
            }

            if (!hasPacketInRange) {
                if (seq.get() != snapshotSeq) {
                    continue;
                }
                return null;
            }

            long startHeader = keyAtOrBeforeStart >= 0L
                ? keyAtOrBeforeStart : firstKeyAfterStart;
            if (startHeader < 0L) {
                if (seq.get() != snapshotSeq) {
                    continue;
                }
                return null;
            }

            long endHeader = startHeader;
            for (long i = startHeader; i < localTail; i++) {
                int idx = (int) (i & HEADER_MASK);
                if (hPts[idx] > endPtsUs) {
                    break;
                }
                endHeader = i + 1L;
            }

            if (seq.get() != snapshotSeq) {
                continue;
            }
            if (endHeader <= startHeader) {
                return null;
            }

            long pinAt = localBytesHead;
            for (long i = localHead; i < startHeader; i++) {
                pinAt += hLength[(int) (i & HEADER_MASK)];
            }
            if (seq.get() != snapshotSeq) {
                continue;
            }

            // A range cursor must not replace the pin of beginFlush() or a
            // previous range cursor. The API remains single-consumer.
            long pinToken = encodePinOffset(pinAt, strong);
            if (!pinOffset.compareAndSet(NO_PIN, pinToken)) {
                if (pinOffset.get() == EVICTING) {
                    Thread.yield();
                    continue;
                }
                return null;
            }

            // Close the small race between the last seqlock validation and
            // publishing the pin. A producer mutation in that gap may have
            // evicted or reused one of the selected headers; release and retry.
            if (seq.get() != snapshotSeq) {
                pinOffset.compareAndSet(pinToken, NO_PIN);
                continue;
            }

            return new Cursor(startHeader, endHeader, pinAt,
                    hPts[(int) (startHeader & HEADER_MASK)],
                    hPts[(int) ((endHeader - 1) & HEADER_MASK)], strong);
        }

        logger.warn("beginFlushRange: producer churn defeated 8 retries — skipping flush");
        return null;
    }

    /**
     * Iterator over a snapshotted flush range. Single-threaded (consumer-side).
     * Validates each packet against the seqlock before returning it.
     */
    public final class Cursor {
        private final long endHeader;
        private long curHeader;
        private long currentPinOffset;
        private final long startPtsUs;
        private final long endPtsUs;
        private final boolean strong;
        private boolean aborted;
        private boolean closed;

        Cursor(long startHeader, long endHeader, long pinReadFloor,
               long startPtsUs, long endPtsUs, boolean strong) {
            this.curHeader = startHeader;
            this.endHeader = endHeader;
            this.currentPinOffset = pinReadFloor;
            this.startPtsUs = startPtsUs;
            this.endPtsUs = endPtsUs;
            this.strong = strong;
        }

        /**
         * Advance to the next packet. Returns false when no more packets
         * are available (either end-of-snapshot or the pin was broken by
         * a concurrent keyframe).
         *
         * <p>On success, fills the supplied {@code outInfo} with metadata
         * and copies the packet's bytes into {@code outDst} starting at
         * {@code outDst.position()}. {@code outDst} must have at least
         * {@link #peekSize} bytes remaining.
         *
         * @return true if a packet was emitted; false if cursor is exhausted
         *         or aborted.
         */
        public boolean next(ByteBuffer outDst, MediaCodec.BufferInfo outInfo) {
            if (closed || aborted) return false;
            if (curHeader >= endHeader) return false;

            // Validate against current pin — if producer broke our pin
            // (keyframe arrived, evicted bytes we needed), abort.
            if (stablePinToken() != currentPinToken()) {
                aborted = true;
                return false;
            }

            // Seqlock-validated read of the header at curHeader.
            // Pre-init: continue-on-odd skips the assignments, so the
            // compiler's definite-assignment check fails without defaults.
            int idx = (int) (curHeader & HEADER_MASK);
            long s1, s2 = 0L;
            int off = 0, len = 0, flags = 0;
            long pts = 0L;
            do {
                s1 = seq.get();
                if ((s1 & 1L) != 0) {
                    Thread.yield();
                    continue;
                }
                off = hOffset[idx];
                len = hLength[idx];
                pts = hPts[idx];
                flags = hFlags[idx];
                s2 = seq.get();
            } while (s1 != s2);

            // Cross-check: if the producer evicted past curHeader during
            // our seqlock retry, the slot may now belong to a newer packet
            // with the same header index. Detect via headerHead bound.
            // Producer never evicts past the pin when consumer is alive,
            // BUT a key-driven pin break may have raced us. Treat as abort.
            if (curHeader < headerHead) {
                aborted = true;
                return false;
            }

            // Copy bytes out, handling wraparound. outDst.position() advances
            // by `len` after the puts; the consumer rewinds before muxer write.
            //
            // Use the cached consumer view rather than payload.duplicate() —
            // this avoids the producer/consumer race on payload.position()
            // that could throw IllegalArgumentException from the duplicate
            // ctor's mark/pos/limit invariant check. The consumer thread
            // is the only writer of consumerPayloadView's position/limit,
            // so we can mutate them freely here without locking.
            //
            // CRITICAL: reset limit to capacity (budget) BEFORE setting
            // position. The view persists across flushes — last call left
            // it at limit=N (some byte offset from a prior packet). If the
            // new `off` is greater than that stale limit, position(off)
            // throws IllegalArgumentException ("Bad position off/limit"),
            // which kills the drainer's flush loop mid-burst and silently
            // drops every remaining pre-record packet. Always extend limit
            // to capacity first, then narrow it down. Equivalent to a
            // Buffer.clear() but spelled explicitly so future readers
            // understand the load-bearing ordering.
            int dstStart = outDst.position();
            if (off + len <= budget) {
                consumerPayloadView.limit(budget);
                consumerPayloadView.position(off);
                consumerPayloadView.limit(off + len);
                outDst.put(consumerPayloadView);
            } else {
                int firstChunk = budget - off;
                consumerPayloadView.limit(budget);
                consumerPayloadView.position(off);
                outDst.put(consumerPayloadView);

                consumerPayloadView.position(0);
                consumerPayloadView.limit(len - firstChunk);
                outDst.put(consumerPayloadView);
            }

            // Post-copy revalidation. The seqlock above only proved the *header*
            // fields (off, len, pts, flags) were stable at read time. Between
            // the header read and the byte copy that just finished, a producer
            // could have evicted past `off` (key-driven pin break) and
            // overwritten those exact bytes with a newer packet's payload.
            // Result: a flushed packet with a valid PTS and corrupt bitstream
            // — muxer accepts it (it's just bytes), player chokes mid-pre-record
            // with no error log. The fix: re-check the pin AND seq after the
            // copy. If either changed, the bytes we just wrote are untrusted —
            // rewind outDst and abort the cursor. The partial flush up to here
            // is still valid (those bytes were validated when their packets
            // were emitted; only THIS packet's bytes are suspect).
            if (stablePinToken() != currentPinToken()) {
                outDst.position(dstStart);
                aborted = true;
                return false;
            }
            // Tighter check: verify the slot's header didn't change while we
            // copied. If hOffset/hLength at idx still match what we read, the
            // producer didn't reuse this slot — the bytes are good. (We can't
            // re-read seq because the producer's add() advances seq for every
            // packet whether or not it touched our slot.)
            long sFinal = seq.get();
            if ((sFinal & 1L) == 0 && (hOffset[idx] != off || hLength[idx] != len)) {
                outDst.position(dstStart);
                aborted = true;
                return false;
            }

            // Move the pin past the packet we just copied. This lets the
            // producer reclaim already-consumed bytes during a long manual
            // export instead of filling the arena and breaking the cursor at
            // the next keyframe. CAS also proves the pin was not preempted
            // between the byte-copy validation and publication.
            final long nextPinOffset = currentPinOffset + len;
            final long expectedToken = currentPinToken();
            final long nextToken = encodePinOffset(nextPinOffset, strong);
            while (!pinOffset.compareAndSet(expectedToken, nextToken)) {
                if (stablePinToken() != expectedToken) {
                    outDst.position(dstStart);
                    aborted = true;
                    return false;
                }
            }
            currentPinOffset = nextPinOffset;

            // Populate metadata. outInfo.offset is the pre-put dst position;
            // most consumers rewind to 0 anyway (MuxerPacket.rewindForWrite).
            outInfo.set(dstStart, len, pts, flags);
            curHeader++;
            return true;
        }

        /** Bytes the next call to {@link #next} will write. -1 if exhausted. */
        public int peekSize() {
            if (closed || aborted || curHeader >= endHeader) return -1;
            return hLength[(int) (curHeader & HEADER_MASK)];
        }

        /** Total packets remaining in the cursor's snapshot range. */
        public int remaining() {
            return (int) Math.max(0, endHeader - curHeader);
        }

        /** True if the cursor was aborted by a concurrent pin break. */
        public boolean aborted() { return aborted; }

        /** Source PTS of the first packet selected for this cursor. */
        public long getStartPtsUs() { return startPtsUs; }

        /** Source PTS of the final packet selected for this cursor. */
        public long getEndPtsUs() { return endPtsUs; }

        private long currentPinToken() {
            return encodePinOffset(currentPinOffset, strong);
        }

        private long stablePinToken() {
            long token;
            while ((token = pinOffset.get()) == EVICTING) {
                Thread.yield();
            }
            return token;
        }

        /** Release the pin. Safe to call multiple times. */
        public void close() {
            if (closed) return;
            closed = true;
            // Only clear the pin if it's still ours (a producer key-break
            // would have already set it to MIN_VALUE — don't stomp).
            long expectedToken = currentPinToken();
            while (!pinOffset.compareAndSet(expectedToken, NO_PIN)) {
                if (stablePinToken() != expectedToken) return;
            }
        }
    }

    /**
     * Compute the total byte count the cursor would emit (sum of remaining
     * packet sizes). Useful for the consumer to log expected flush size.
     * Approximate when concurrent with add.
     */
    public int peekFlushBytes() {
        // Pre-init: see beginFlush rationale.
        long s1, s2 = 0L;
        long head = 0L, tail = 0L;
        do {
            s1 = seq.get();
            if ((s1 & 1L) != 0) { Thread.yield(); continue; }
            head = headerHead;
            tail = headerTail;
            s2 = seq.get();
        } while (s1 != s2);

        int total = 0;
        boolean foundKey = false;
        for (long i = head; i < tail; i++) {
            int idx = (int) (i & HEADER_MASK);
            if (!foundKey) {
                if ((hFlags[idx] & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                    foundKey = true;
                } else {
                    continue;
                }
            }
            total += hLength[idx];
        }
        return total;
    }
}
