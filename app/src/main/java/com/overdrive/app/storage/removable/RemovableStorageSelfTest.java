package com.overdrive.app.storage.removable;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Self-test of {@link RemovableStorageFs}, run as the ADB shell like the daemons:
 * <pre>
 *   CLASSPATH=&lt;overdrive apk&gt; app_process /system/bin \
 *       com.overdrive.app.storage.removable.RemovableStorageSelfTest [--keep]
 * </pre>
 * Runs every adapter operation on each removable volume (MediaStore-routed on DiLink 100)
 * and on internal storage (plain java.io.File), records a short H.264 clip through
 * {@link RemovableStorageFs#newMuxer}, reads it back, and measures write speed.
 */
public final class RemovableStorageSelfTest {
    private static final int W = 1280, H = 720, FPS = 30, FRAMES = 150;
    private static int failures;

    public static void main(String[] args) {
        boolean keep = args.length > 0 && args[0].equals("--keep");
        try {
            log("platform DiLink 100: " + RemovableStorageFs.isActive());
            String[] roots = RemovableStorageFs.volumeRoots();
            log("removable volumes: " + java.util.Arrays.toString(roots));
            check("at least one removable volume", roots.length > 0);
            for (String root : roots) runSuite(new File(root), true, keep);
            runSuite(new File("/storage/emulated/0"), false, false);
        } catch (Throwable e) {
            failures++;
            log("FAIL unexpected " + e);
            e.printStackTrace(System.out);
        }
        log(failures == 0 ? "RESULT: PASS" : "RESULT: FAIL (" + failures + ")");
        System.exit(0);
    }

    private static void runSuite(File volume, boolean routed, boolean keep) throws Exception {
        log("=== " + volume + (routed ? " (MediaStore)" : " (plain file I/O)"));
        File base = new File(volume, "Overdrive/fstest");
        check("isRouted == " + routed, RemovableStorageFs.isRouted(base) == routed);
        if (routed) check("shell alone cannot see the volume", !volume.exists());

        check("mkdirs creates the folder", RemovableStorageFs.mkdirs(base) && RemovableStorageFs.isDirectory(base));
        check("mkdirs on existing dir -> false", !RemovableStorageFs.mkdirs(base));
        String[] afterMkdir = RemovableStorageFs.list(base);
        check("new folder is empty (no placeholder left)", afterMkdir != null && afterMkdir.length == 0);
        File nested = new File(base, "a/b");
        check("mkdirs nested a/b", RemovableStorageFs.mkdirs(nested) && RemovableStorageFs.isDirectory(nested));
        check("canWrite", RemovableStorageFs.canWrite(base));
        long total = RemovableStorageFs.getTotalSpace(volume);
        long usable = RemovableStorageFs.statFsAvailableBytes(volume.getPath());
        log("space total=" + total / (1 << 20) + " MB usable=" + usable / (1 << 20) + " MB");
        check("space > 0", total > 0 && usable > 0);

        // Small file: stream write, append, read back, stat.
        File txt = new File(base, "hello.txt");
        try (FileOutputStream o = RemovableStorageFs.openOutput(txt)) {
            o.write("hello ".getBytes(StandardCharsets.UTF_8));
        }
        try (FileOutputStream o = RemovableStorageFs.openOutput(txt, true)) {
            o.write("card".getBytes(StandardCharsets.UTF_8));
        }
        byte[] buf = new byte[64];
        int n;
        try (FileInputStream in = RemovableStorageFs.openInput(txt)) {
            n = in.read(buf);
        }
        check("write + append + read", "hello card".equals(new String(buf, 0, n, StandardCharsets.UTF_8)));
        check("length == 10", RemovableStorageFs.length(txt) == 10);
        check("isFile", RemovableStorageFs.isFile(txt) && !RemovableStorageFs.isDirectory(txt));
        check("lastModified is recent",
                Math.abs(RemovableStorageFs.lastModified(txt) - System.currentTimeMillis()) < 120_000);

        // Listing: snapshot metadata and routed methods on the returned entries.
        File[] kids = RemovableStorageFs.listFiles(base, (d, name) -> name.endsWith(".txt"));
        check("listFiles(filter) finds hello.txt", kids != null && kids.length == 1
                && kids[0].getName().equals("hello.txt"));
        File[] all = RemovableStorageFs.listFiles(base);
        boolean sawDir = false;
        if (all != null) for (File f : all) if (f.getName().equals("a") && f.isDirectory()) sawDir = true;
        check("listing shows subfolder a/ as a directory", sawDir);
        if (kids != null && kids.length == 1) {
            check("listed entry length() (direct File call)", kids[0].length() == 10);
            File moved = new File(base, "moved.txt");
            check("listed entry renameTo()", kids[0].renameTo(moved));
            check("listed entry exists() after rename -> false", !kids[0].exists());
            check("renamed file exists", RemovableStorageFs.exists(moved));
            File intoSub = new File(nested, "moved.txt");
            check("move into another folder", RemovableStorageFs.renameTo(moved, intoSub)
                    && RemovableStorageFs.exists(intoSub) && !RemovableStorageFs.exists(moved));
            check("delete of a non-empty folder is refused", !RemovableStorageFs.delete(nested));
            check("delete file", RemovableStorageFs.delete(intoSub) && !RemovableStorageFs.exists(intoSub));
            check("delete empty folders", RemovableStorageFs.delete(nested)
                    && RemovableStorageFs.delete(new File(base, "a")));
        }

        // Rename onto an existing name replaces it (rename(2)), never "name (1)".
        File one = new File(base, "seg.mp4.tmp"), two = new File(base, "seg.mp4");
        writeText(two, "old");
        writeText(one, "new-content");
        check("rename onto existing name replaces it", RemovableStorageFs.renameTo(one, two)
                && "new-content".equals(readText(two)) && !RemovableStorageFs.exists(one));
        String[] names = RemovableStorageFs.list(base);
        boolean dup = false;
        if (names != null) for (String s : names) if (s.contains("(1)")) dup = true;
        check("no ' (1)' duplicates", !dup);
        check(".tmp name kept (generic data)", writeText(new File(base, "x.mp4.tmp"), "t")
                && RemovableStorageFs.exists(new File(base, "x.mp4.tmp"))
                && !RemovableStorageFs.exists(new File(base, "x.mp4.tmp.mp4")));
        RemovableStorageFs.delete(new File(base, "x.mp4.tmp"));
        RemovableStorageFs.delete(two);

        if (routed) {
            boolean refused;
            try (FileOutputStream o = RemovableStorageFs.openOutput(new File(base, ".hidden"))) {
                refused = false;
            } catch (java.io.FileNotFoundException expected) {
                refused = true;
            }
            check("dot name refused (not silently renamed)", refused
                    && !RemovableStorageFs.exists(new File(base, "_.hidden")));
        }

        // Recorder path: temp file -> MediaMuxer -> rename -> readback.
        long stamp = System.currentTimeMillis();
        File tmp = new File(base, "clip_" + stamp + ".mp4.tmp");
        File mp4 = new File(base, "clip_" + stamp + ".mp4");
        long t0 = System.nanoTime();
        int samples = recordClip(tmp);
        log("recorded " + samples + " samples in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        check("clip has 150 samples", samples == FRAMES);
        long realSize;
        try (FileInputStream in = RemovableStorageFs.openInput(tmp)) {
            realSize = in.getChannel().size();
        }
        check("tmp length() is the real size (" + realSize + " bytes), not a stale header size",
                realSize > 100_000 && RemovableStorageFs.length(tmp) == realSize);
        File[] tmps = RemovableStorageFs.listFiles(base, (d, name) -> name.equals(tmp.getName()));
        check("listed tmp length() is the real size too",
                tmps != null && tmps.length == 1 && tmps[0].length() == realSize);
        check("tmp lastModified() is recent (not the creation time)",
                Math.abs(RemovableStorageFs.lastModified(tmp) - System.currentTimeMillis()) < 30_000);
        check("rename .tmp -> .mp4", RemovableStorageFs.renameTo(tmp, mp4));

        // Readers used by playback (range requests), the timeline and segment recovery.
        long mp4Size = RemovableStorageFs.length(mp4);
        try (SeekableFileReader r = new SeekableFileReader(mp4)) {
            r.seek(4);
            boolean ftyp = r.readInt() == 0x66747970;
            byte[] tail = new byte[16];
            r.seek(mp4Size - 16);
            int got = r.read(tail, 0, 16);
            check("SeekableFileReader: seek/readInt ('ftyp'), read at end, EOF -> -1",
                    ftyp && got == 16 && r.read(tail, 0, 16) == -1 && r.length() == mp4Size
                            && r.getChannel().size() == mp4Size);
        }
        byte[] whole = RemovableStorageFs.readAllBytes(mp4);
        check("readAllBytes returns the whole file", whole.length == mp4Size);

        // Files.move replacement (SafeKeep staging, parking snapshots).
        File mA = new File(base, "move_a.json"), mB = new File(base, "move_b.json");
        writeText(mA, "new");
        writeText(mB, "old");
        boolean refused;
        try {
            RemovableStorageFs.move(mA, mB);
            refused = false;
        } catch (java.nio.file.FileAlreadyExistsException expected) {
            refused = true;
        }
        check("move without REPLACE_EXISTING refuses an existing target", refused && "old".equals(readText(mB)));
        RemovableStorageFs.move(mA, mB, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        check("move with REPLACE_EXISTING replaces the target",
                "new".equals(readText(mB)) && !RemovableStorageFs.exists(mA));
        RemovableStorageFs.delete(mB);

        // Path checks in the web handlers canonicalize requests and roots the same way.
        File dotted = new File(base, "../fstest/" + mp4.getName());
        String canon;
        try {
            canon = dotted.getCanonicalPath();
        } catch (Exception e) {
            canon = "threw " + e;
        }
        check("getCanonicalPath collapses '..' (" + canon + ")", mp4.getPath().equals(canon));
        MediaExtractor ex = new MediaExtractor();
        try {
            RemovableStorageFs.setDataSource(ex, mp4);
            MediaFormat f = ex.getTrackFormat(0);
            check("extractor: avc 1280x720 5000 ms",
                    "video/avc".equals(f.getString(MediaFormat.KEY_MIME))
                            && f.getInteger(MediaFormat.KEY_WIDTH) == W
                            && f.getLong(MediaFormat.KEY_DURATION) / 1000 == 5000);
        } finally {
            ex.release();
        }
        if (routed) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                RemovableStorageFs.setDataSource(mmr, mp4);
                check("retriever: duration 5000 and a frame",
                        "5000".equals(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
                                && mmr.getFrameAtTime(1_000_000) != null);
            } finally {
                mmr.release();
            }
        } else {
            log("retriever check skipped for plain files (needs the daemon's system context)");
        }

        // Throughput: 64 MB in 1 MB chunks, fsync included.
        File speed = new File(base, "speed.bin");
        byte[] chunk = new byte[1 << 20];
        long s0 = System.nanoTime();
        try (FileOutputStream o = RemovableStorageFs.openOutput(speed)) {
            for (int i = 0; i < 64; i++) o.write(chunk);
            o.getFD().sync();
        }
        log(String.format("write speed: %.1f MB/s", 64.0 / ((System.nanoTime() - s0) / 1e9)));
        RemovableStorageFs.delete(speed);

        if (!keep) {
            RemovableStorageFs.delete(mp4);
            check("cleanup: delete fstest folder", RemovableStorageFs.delete(base)
                    && !RemovableStorageFs.exists(base));
        } else {
            log("kept " + mp4);
        }
    }

    private static boolean writeText(File f, String s) throws Exception {
        try (FileOutputStream o = RemovableStorageFs.openOutput(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return true;
    }

    private static String readText(File f) throws Exception {
        byte[] b = new byte[256];
        try (FileInputStream in = RemovableStorageFs.openInput(f)) {
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, StandardCharsets.UTF_8);
        }
    }

    private static int recordClip(File out) throws Exception {
        MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        MediaMuxer muxer = RemovableStorageFs.newMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = -1, fed = 0, written = 0;
        boolean inputDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                if (!inputDone) {
                    int in = enc.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        long pts = fed * 1_000_000L / FPS;
                        if (fed == FRAMES) {
                            enc.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            fillFrame(enc.getInputImage(in), fed);
                            enc.queueInputBuffer(in, 0, W * H * 3 / 2, pts, 0);
                            fed++;
                        }
                    }
                }
                int o = enc.dequeueOutputBuffer(info, 10_000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(enc.getOutputFormat());
                    muxer.start();
                } else if (o >= 0) {
                    ByteBuffer b = enc.getOutputBuffer(o);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config && info.size > 0 && track >= 0) {
                        muxer.writeSampleData(track, b, info);
                        written++;
                    }
                    enc.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            muxer.stop();
        } finally {
            muxer.release();
            enc.stop();
            enc.release();
        }
        return written;
    }

    /** Moving gradient so the clip is visibly a test pattern. */
    private static void fillFrame(Image img, int n) {
        Image.Plane[] p = img.getPlanes();
        ByteBuffer y = p[0].getBuffer();
        int rs = p[0].getRowStride();
        for (int r = 0; r < H; r++) {
            for (int x = 0; x < W; x++) y.put(r * rs + x, (byte) ((x + r + n * 8) & 0xFF));
        }
        for (int i = 1; i < 3; i++) {
            ByteBuffer b = p[i].getBuffer();
            int crs = p[i].getRowStride(), ps = p[i].getPixelStride();
            for (int r = 0; r < H / 2; r++) {
                for (int x = 0; x < W / 2; x++) b.put(r * crs + x * ps, (byte) 128);
            }
        }
    }

    private static void check(String what, boolean ok) {
        if (!ok) failures++;
        log((ok ? "ok   " : "FAIL ") + what);
    }

    private static void log(String s) {
        System.out.println("[fs] " + s);
    }
}
