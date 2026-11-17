package com.overdrive.app.surveillance;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.overdrive.app.logging.DaemonLogger;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds an interrupted video-only {@code .mp4.tmp} from its {@link SegmentJournal}.
 * The platform muxer writes a single-track file's samples straight into one mdat
 * run as 4-byte length-prefixed NAL units; each journaled sample is checked against
 * that layout and the validated prefix is re-muxed into a normal MP4.
 */
public final class SegmentRecovery {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SegmentRecovery");

    /** A journal written this recently may still belong to a live writer (it flushes every second). */
    private static final long MIN_JOURNAL_AGE_MS = 5_000L;
    /** Outside the startup passes, an unattempted orphan this young still waits for a restart. */
    private static final long PENDING_WINDOW_MS = 60L * 60 * 1000;
    /** Recovery reads and rewrites on the recording card; yield after each chunk. */
    private static final long THROTTLE_BYTES = 1024L * 1024;
    private static final long THROTTLE_SLEEP_MS = 150L;
    private static final long MAX_TMP_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAX_SAMPLE_BYTES = 8 * 1024 * 1024;

    private static final Set<String> attempted =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> inProgress =
            Collections.synchronizedSet(new HashSet<String>());
    private static volatile boolean startupPassesRunning = false;

    private SegmentRecovery() {
    }

    /** Held from before the first startup pass until the last one ends; sweeps keep every journaled orphan meanwhile. */
    public static void beginStartupPasses() {
        startupPassesRunning = true;
    }

    public static void endStartupPasses() {
        startupPassesRunning = false;
    }

    /** True while an orphaned {@code .mp4.tmp} still awaits (or is undergoing) its recovery attempt. */
    public static boolean isPending(File tmp) {
        if (tmp == null || !tmp.getName().endsWith(".mp4.tmp")) return false;
        String path = tmp.getAbsolutePath();
        if (inProgress.contains(path)) return true;
        if (attempted.contains(path)) return false;
        File jnl = SegmentJournal.fileFor(tmp);
        if (!jnl.isFile()) return false;
        if (startupPassesRunning) return true;
        long age = System.currentTimeMillis() - jnl.lastModified();
        return age >= 0 && age < PENDING_WINDOW_MS;
    }

    /** Recovers every orphaned journaled segment in {@code dir}; returns the count recovered. */
    public static int recoverOrphans(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] tmps = dir.listFiles((d, name) -> name.endsWith(".mp4.tmp"));
        if (tmps == null) {
            try {
                tmps = com.overdrive.app.storage.StorageManager.getInstance()
                        .listFilesWithFallback(dir, ".mp4.tmp");
            } catch (Throwable t) {
                return 0;
            }
        }
        if (tmps == null) return 0;
        int recovered = 0;
        for (File tmp : tmps) {
            File jnl = SegmentJournal.fileFor(tmp);
            String path = tmp.getAbsolutePath();
            if (!jnl.isFile() || attempted.contains(path) || inProgress.contains(path)) continue;
            if (SegmentJournal.isActive(jnl)) continue;
            long age = System.currentTimeMillis() - jnl.lastModified();
            if (age >= 0 && age < MIN_JOURNAL_AGE_MS) continue;  // negative = clock reset: old
            inProgress.add(path);
            try {
                File out = recover(tmp, jnl);
                if (out != null) {
                    recovered++;
                    try {
                        com.overdrive.app.storage.StorageManager.getInstance().onFileSaved(out);
                    } catch (Throwable ignored) {
                    }
                    try {
                        com.overdrive.app.server.RecordingsIndex.getInstance().upsert(out);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                logger.warn("Recovery failed for " + tmp.getName() + ": " + t.getMessage());
            } finally {
                attempted.add(path);
                inProgress.remove(path);
            }
        }
        return recovered;
    }

    private static final class Journal {
        String mime;
        int width;
        int height;
        byte[] csd0;
        byte[] csd1;
        final List<long[]> samples = new ArrayList<>();  // {flags, size, ptsUs}
    }

    private static Journal readJournal(File jnl) throws IOException {
        Journal j = new Journal();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(jnl), 64 * 1024))) {
            if (in.readInt() != SegmentJournal.MAGIC) return null;
            j.mime = in.readUTF();
            j.width = in.readInt();
            j.height = in.readInt();
            j.csd0 = readBuffer(in);
            j.csd1 = readBuffer(in);
            if (j.csd0 == null) return null;
            try {
                while (true) {
                    int flags = in.readUnsignedByte();
                    int size = in.readInt();
                    long pts = in.readLong();
                    if (size <= 0 || size > MAX_SAMPLE_BYTES) break;
                    j.samples.add(new long[]{flags, size, pts});
                }
            } catch (EOFException truncatedTail) {
                // A record cut by the crash is simply not recovered.
            }
        }
        return j;
    }

    private static byte[] readBuffer(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) return null;
        if (len > 64 * 1024) throw new IOException("csd too large: " + len);
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    /** @return the recovered clip, or null when nothing usable could be rebuilt. */
    static File recover(File tmp, File jnl) throws IOException {
        Journal j = readJournal(jnl);
        if (j == null) {
            logger.warn("Recovery skipped (unreadable journal): " + tmp.getName());
            return null;
        }
        long len = tmp.length();
        if (len <= 0 || len > MAX_TMP_BYTES) return null;
        List<long[]> valid = new ArrayList<>();  // {offset, flags, size, ptsUs}
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "r")) {
            long pos = findMdatPayload(raf, len);
            if (pos < 0) return null;
            long lastPts = Long.MIN_VALUE;
            for (long[] s : j.samples) {
                int size = (int) s[1];
                if (pos + size > len || s[2] <= lastPts || !isLengthPrefixedSample(raf, pos, size)) {
                    break;
                }
                valid.add(new long[]{pos, s[0], size, s[2]});
                lastPts = s[2];
                pos += size;
            }
        }
        if (valid.size() < 2 || (valid.get(0)[1] & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) {
            logger.warn("Recovery skipped (" + valid.size() + " usable samples): " + tmp.getName());
            return null;
        }
        String tmpPath = tmp.getAbsolutePath();
        File target = new File(tmpPath.substring(0, tmpPath.length() - ".tmp".length()));
        if (target.exists()) {
            String p = target.getAbsolutePath();
            target = new File(p.substring(0, p.length() - ".mp4".length()) + "_recovered.mp4");
            if (target.exists()) return null;
        }
        File staging = new File(target.getAbsolutePath() + ".recovering");
        boolean ok = false;
        try {
            remux(tmp, j, valid, staging);
            ok = staging.renameTo(target);
        } finally {
            if (!ok) staging.delete();
        }
        if (!ok) return null;
        tmp.delete();
        jnl.delete();
        long durMs = (valid.get(valid.size() - 1)[3] - valid.get(0)[3]) / 1000L;
        logger.info("Recovered interrupted clip " + target.getName() + ": " + valid.size()
                + " of " + j.samples.size() + " journaled frames, " + durMs + " ms");
        return target;
    }

    private static long findMdatPayload(RandomAccessFile raf, long len) throws IOException {
        long pos = 0;
        while (pos + 8 <= len) {
            raf.seek(pos);
            long size = raf.readInt() & 0xFFFFFFFFL;
            int type = raf.readInt();
            long header = 8;
            if (size == 1) {
                size = raf.readLong();
                header = 16;
            }
            if (type == 0x6D646174) return pos + header;  // "mdat": size field is unset mid-write
            if (size < 8) return -1;
            pos += size;
        }
        return -1;
    }

    private static boolean isLengthPrefixedSample(RandomAccessFile raf, long start, int size)
            throws IOException {
        long end = start + size;
        long p = start;
        while (p < end) {
            if (p + 5 > end) return false;
            raf.seek(p);
            long nal = raf.readInt() & 0xFFFFFFFFL;
            int header = raf.readUnsignedByte();
            if (nal == 0 || (header & 0x80) != 0 || p + 4 + nal > end) return false;
            p += 4 + nal;
        }
        return p == end;
    }

    private static void remux(File tmp, Journal j, List<long[]> valid, File staging)
            throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(j.mime, j.width, j.height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(j.csd0));
        if (j.csd1 != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(j.csd1));
        int max = 0;
        for (long[] s : valid) max = Math.max(max, (int) s[2]);
        byte[] bytes = new byte[max];
        ByteBuffer buf = ByteBuffer.allocateDirect(max);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaMuxer mux = new MediaMuxer(staging.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "r")) {
            int track = mux.addTrack(format);
            mux.start();
            long origin = valid.get(0)[3];
            long sinceYield = 0;
            for (long[] s : valid) {
                int size = (int) s[2];
                raf.seek(s[0]);
                raf.readFully(bytes, 0, size);
                toAnnexB(bytes, size);
                buf.clear();
                buf.put(bytes, 0, size);
                buf.flip();
                int flags = (s[1] & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, size, s[3] - origin, flags);
                mux.writeSampleData(track, buf, info);
                sinceYield += size;
                if (sinceYield >= THROTTLE_BYTES) {
                    sinceYield = 0;
                    try {
                        Thread.sleep(THROTTLE_SLEEP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("recovery interrupted");
                    }
                }
            }
            mux.stop();
        } finally {
            try { mux.release(); } catch (Throwable ignored) {}
        }
    }

    /** Rewrites 4-byte NAL lengths as start codes in place (same byte count). */
    private static void toAnnexB(byte[] b, int size) {
        int p = 0;
        while (p + 4 <= size) {
            int nal = ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                    | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            b[p] = 0;
            b[p + 1] = 0;
            b[p + 2] = 0;
            b[p + 3] = 1;
            p += 4 + nal;
        }
    }
}
