package com.overdrive.app.surveillance;

import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * AacCircularBuffer — small in-memory ring of recent AAC access units, used to
 * give event recordings audio at frame 0 instead of the previous "5 seconds of
 * silent video at every event start".
 *
 * <p>One lazily allocated byte arena packs variable-length frames, with a
 * circular primitive header table for lengths and PTSs. The producer copies
 * into the arena without allocating a packet, payload array or deque node
 * once storage is warm. Exported packets own their bytes.
 *
 * <p>All ring operations share a monitor. Snapshot/drain hold it while copying
 * the selected bytes; no muxer or storage I/O takes place under this lock.
 * FIFO eviction uses the same byte budget as before (744,000 bytes at 62 s
 * and 64 kbps), with no extra packet-count limit. Header storage grows only
 * as needed and is bounded by the byte budget (every packet is nonempty).
 *
 * <h3>What this ring does NOT do</h3>
 * <ul>
 *   <li>It does not synchronize with the muxer's audio track index. Drained
 *       packets that arrive before the muxer's audio track is wired up are
 *       discarded by the caller.</li>
 *   <li>It does not rebase PTSs. The trigger flow forwards each packet's
 *       absolute PTS into {@link HardwareEventRecorderGpu}'s muxer queue and
 *       the existing {@code writeRebasedAudio} machinery anchors them to the
 *       segment's {@code ptsOriginUs}.</li>
 *   <li>It is not bounded by header count. The byte budget alone is enough —
 *       the maximum window's budget at 256 B/packet caps the ring at roughly
 *       3000 entries.</li>
 * </ul>
 */
public class AacCircularBuffer {
    private static final DaemonLogger logger = DaemonLogger.getInstance("AacRing");

    /** Multiplier applied on top of the bitrate-time product to absorb AAC
     *  frame-size variability. Empirically AAC-LC frames at 64 kbps cluster
     *  around 256 B but spike to 350 B+ on transients; 1.5× covers worst-case
     *  bursts without padding the steady-state footprint. */
    private static final double BITRATE_OVERHEAD = 1.5;

    /** One exported AAC access unit + its original presentation timestamp.
     * Its payload is independent of the ring and other exports. */
    public static final class Packet {
        public final byte[] data;
        public final long ptsUs;
        Packet(byte[] data, long ptsUs) {
            this.data = data;
            this.ptsUs = ptsUs;
        }
    }

    private byte[] payload;
    private int[] lengths;
    private long[] timestamps;
    private int byteHead, byteCount;
    private int headerHead, packetCount;
    /** Total bytes the ring is allowed to hold before FIFO eviction kicks in.
     *  Final after construction — no resize API. */
    private final int byteBudget;

    /**
     * @param maxDurationSeconds Pre-record window the ring is sized for, in
     *                           seconds. Clamped to [1, 62] to mirror
     *                           {@link H264ByteRingBuffer}'s bounds.
     * @param maxBitrateBps      AAC bitrate in bits per second (e.g. 64000
     *                           for the daemon's default 64 kbps capture).
     */
    public AacCircularBuffer(int maxDurationSeconds, int maxBitrateBps) {
        int clampedSec = Math.max(1, Math.min(62, maxDurationSeconds));
        int safeBitrate = Math.max(8000, maxBitrateBps);
        // Floor at 16 KB so a misconfigured bitrate (e.g. 0 from a stale
        // config) never produces a budget so small that every add() instantly
        // evicts itself. 16 KB still holds ~60 packets at 256 B average.
        long budget = (long) ((clampedSec * (long) safeBitrate / 8L) * BITRATE_OVERHEAD);
        this.byteBudget = (int) Math.max(16 * 1024L, budget);
        logger.info("AacCircularBuffer ready: budget=" + byteBudget
            + " B (duration=" + clampedSec + "s, bitrate="
            + (safeBitrate / 1000) + " kbps)");
    }

    /**
     * Append one AAC access unit to the ring. Bytes are copied — the caller's
     * source array may be reused immediately.
     *
     * <p>Evicts oldest packets in FIFO order until the byte budget is
     * satisfied. A pathologically large single packet (greater than the
     * entire budget) is dropped silently; that scenario is impossible with
     * AAC-LC at 64 kbps but the guard keeps a corrupt ingest stream from
     * wiping the ring.
     *
     * @param data    Source byte array
     * @param offset  Start offset within {@code data}
     * @param length  Valid byte count
     * @param ptsUs   Presentation timestamp in microseconds, on the same
     *                wall-clock origin as the video encoder's PTSs
     */
    public synchronized void add(byte[] data, int offset, int length, long ptsUs) {
        if (data == null || length <= 0 || offset < 0 || offset > data.length - length
                || length > byteBudget) return;
        if (payload == null) payload = new byte[byteBudget];

        while (length > byteBudget - byteCount) {
            int oldLength = lengths[headerHead];
            byteHead = advance(byteHead, oldLength, byteBudget);
            byteCount -= oldLength;
            headerHead = advance(headerHead, 1, lengths.length);
            packetCount--;
        }
        ensureHeaderSpace();
        int tail = advance(byteHead, byteCount, byteBudget);
        int first = Math.min(length, byteBudget - tail);
        System.arraycopy(data, offset, payload, tail, first);
        if (first < length) System.arraycopy(data, offset + first, payload, 0, length - first);
        int header = advance(headerHead, packetCount, lengths.length);
        lengths[header] = length;
        timestamps[header] = ptsUs;
        packetCount++;
        byteCount += length;
    }

    // Both offset and distance are bounded by capacity; avoid overflowing their sum.
    private static int advance(int offset, int distance, int capacity) {
        int remaining = capacity - offset;
        return distance < remaining ? offset + distance : distance - remaining;
    }

    private void ensureHeaderSpace() {
        if (lengths != null && packetCount < lengths.length) return;
        int capacity = lengths == null ? Math.min(64, byteBudget)
                : (int) Math.min((long) lengths.length * 2, byteBudget);
        int[] newLengths = new int[capacity];
        long[] newTimestamps = new long[capacity];
        if (packetCount != 0) {
            int first = Math.min(packetCount, lengths.length - headerHead);
            System.arraycopy(lengths, headerHead, newLengths, 0, first);
            System.arraycopy(timestamps, headerHead, newTimestamps, 0, first);
            System.arraycopy(lengths, 0, newLengths, first, packetCount - first);
            System.arraycopy(timestamps, 0, newTimestamps, first, packetCount - first);
        }
        lengths = newLengths;
        timestamps = newTimestamps;
        headerHead = 0;
    }

    /** Return independent packets in insertion order and atomically empty the ring. */
    public synchronized List<Packet> drainAll() {
        List<Packet> out = copyPackets(Long.MIN_VALUE, Long.MAX_VALUE, packetCount);
        resetPositions();
        return out;
    }

    /**
     * Copy packets in the inclusive PTS range without removing them. The
     * returned bytes remain valid after any subsequent add, drain or clear.
     * PTSs need not be monotonic; preserve insertion order even across resets.
     */
    public synchronized List<Packet> snapshotRange(long startPtsUs, long endPtsUs) {
        return copyPackets(startPtsUs, endPtsUs, 0);
    }

    private List<Packet> copyPackets(long startPtsUs, long endPtsUs, int capacity) {
        List<Packet> out = new ArrayList<>(capacity);
        if (startPtsUs > endPtsUs) return out;
        int offset = byteHead;
        int header = headerHead;
        for (int i = 0; i < packetCount; i++) {
            int length = lengths[header];
            long ptsUs = timestamps[header];
            if (ptsUs >= startPtsUs && ptsUs <= endPtsUs) {
                byte[] data = new byte[length];
                int first = Math.min(length, byteBudget - offset);
                System.arraycopy(payload, offset, data, 0, first);
                if (first < length) System.arraycopy(payload, 0, data, first, length - first);
                out.add(new Packet(data, ptsUs));
            }
            offset = advance(offset, length, byteBudget);
            header = advance(header, 1, lengths.length);
        }
        return out;
    }

    private void resetPositions() {
        byteHead = byteCount = headerHead = packetCount = 0;
    }

    /** Empty the ring and release storage when audio is disabled or restarted. */
    public synchronized void clear() {
        resetPositions();
        payload = null;
        lengths = null;
        timestamps = null;
    }

    /** @return exact bytes currently held by the ring */
    public synchronized int getByteCount() {
        return byteCount;
    }

    /** @return current packet count, in constant time */
    public synchronized int getPacketCount() {
        return packetCount;
    }

    /** @return configured byte budget (final after construction). */
    public int getByteBudget() {
        return byteBudget;
    }
}
