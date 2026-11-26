package com.overdrive.app.surveillance;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import android.media.MediaFormat;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Append-only sample journal written next to a video-only {@code .mp4.tmp}
 * ({@code <tmp>.jnl}). It records the codec config and each written sample's
 * flags, size and rebased PTS so {@link SegmentRecovery} can rebuild a clip
 * whose muxer never wrote its moov. Never throws: an I/O error drops the journal.
 */
final class SegmentJournal {

    static final String SUFFIX = ".jnl";
    static final int MAGIC = 0x4F444A31;  // "ODJ1"
    private static final long FLUSH_INTERVAL_NS = 1_000_000_000L;
    /** Journals of this process's unfinalized segments; any other journal belongs to a dead writer. */
    private static final java.util.Set<String> ACTIVE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final File file;
    private DataOutputStream out;
    private long lastFlushNs;

    private SegmentJournal(File file, DataOutputStream out) {
        this.file = file;
        this.out = out;
        this.lastFlushNs = System.nanoTime();
        ACTIVE.add(file.getAbsolutePath());
    }

    static File fileFor(File tmp) {
        return new File(tmp.getPath() + SUFFIX);
    }

    static boolean isActive(File journal) {
        return ACTIVE.contains(journal.getAbsolutePath());
    }

    /** @return the open journal, or null when the format can't be journaled. */
    static SegmentJournal open(File tmp, MediaFormat format) {
        if (tmp == null || format == null) return null;
        File f = fileFor(tmp);
        DataOutputStream out = null;
        try {
            String mime = format.getString(MediaFormat.KEY_MIME);
            ByteBuffer csd0 = format.getByteBuffer("csd-0");
            if (mime == null || csd0 == null) return null;
            ByteBuffer csd1 = format.containsKey("csd-1") ? format.getByteBuffer("csd-1") : null;
            out = new DataOutputStream(new BufferedOutputStream(RemovableStorageFs.openOutput(f), 16 * 1024));
            out.writeInt(MAGIC);
            out.writeUTF(mime);
            out.writeInt(format.getInteger(MediaFormat.KEY_WIDTH));
            out.writeInt(format.getInteger(MediaFormat.KEY_HEIGHT));
            writeBuffer(out, csd0);
            writeBuffer(out, csd1);
            out.flush();
            return new SegmentJournal(f, out);
        } catch (Throwable t) {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) {}
            }
            RemovableStorageFs.delete(f);
            return null;
        }
    }

    private static void writeBuffer(DataOutputStream out, ByteBuffer buf) throws IOException {
        if (buf == null) {
            out.writeInt(-1);
            return;
        }
        ByteBuffer b = buf.duplicate();
        b.position(0);
        byte[] bytes = new byte[b.remaining()];
        b.get(bytes);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** One video sample as written to the muxer (rebased PTS). */
    void append(int flags, int size, long ptsUs) {
        DataOutputStream o = out;
        if (o == null) return;
        try {
            o.writeByte(flags);
            o.writeInt(size);
            o.writeLong(ptsUs);
            long now = System.nanoTime();
            if (now - lastFlushNs >= FLUSH_INTERVAL_NS) {
                o.flush();
                lastFlushNs = now;
            }
        } catch (Throwable t) {
            abandon();
        }
    }

    /** Flushes and closes; the file stays until the segment is finalized. */
    void close() {
        DataOutputStream o = out;
        out = null;
        if (o == null) return;
        try {
            o.close();
        } catch (Throwable ignored) {
        }
    }

    /** Closes and deletes the journal (segment discarded or journal unusable). */
    void abandon() {
        close();
        RemovableStorageFs.delete(file);
        ACTIVE.remove(file.getAbsolutePath());
    }

    /** Deletes the journal that belongs to {@code tmp}, if any. */
    static void deleteFor(File tmp) {
        if (tmp == null) return;
        File f = fileFor(tmp);
        try {
            RemovableStorageFs.delete(f);
        } catch (Throwable ignored) {
        }
        ACTIVE.remove(f.getAbsolutePath());
    }
}
