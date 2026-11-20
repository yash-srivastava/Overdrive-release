package com.overdrive.app.server.hls;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import static com.overdrive.app.server.hls.Boxes.*;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import com.overdrive.app.storage.removable.SeekableFileReader;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable sample index of a progressive MP4 with one H.264/HEVC video track and an optional
 * mp4a track, split into GOPs (sync sample to next sync sample) for fMP4/HLS repackaging.
 */
public final class Mp4Index {
    static final int MAX_SAMPLES = 2_000_000;
    static final int MAX_SAMPLE_BYTES = 64 << 20;
    // Real clips carry a few MB at most; a bigger moov is corrupt and would cost the recorder's heap.
    private static final int MAX_MOOV_BYTES = 16 << 20;
    private static final int MAX_TOP_LEVEL_BOXES = 4096;
    private static final int MAX_DEPTH = 8;
    private static final long MAX_TRACK_SECONDS = 48L * 3600L;

    private final File file;
    private final long fileLength;
    private final long lastModified;
    final Mp4Track video;
    final Mp4Track audio;
    /** Video sample index of each GOP's sync sample; gopFirst[gopCount] = video sample count. */
    final int[] gopFirst;
    /** GOP presentation start in microseconds; gopUs[gopCount] = video end. */
    final long[] gopUs;
    /** Audio samples muxed with GOP n are [audioFirst[n], audioFirst[n + 1]). Null without audio. */
    final int[] audioFirst;
    final GopTable gops;
    private final String codecs;

    private Mp4Index(File file, long fileLength, long lastModified, Mp4Track video, Mp4Track audio,
                     int[] gopFirst, long[] gopUs, int[] audioFirst) {
        this.file = file;
        this.fileLength = fileLength;
        this.lastModified = lastModified;
        this.video = video;
        this.audio = audio;
        this.gopFirst = gopFirst;
        this.gopUs = gopUs;
        this.audioFirst = audioFirst;
        long[] ticks = new long[gopFirst.length];
        for (int g = 0; g < ticks.length; g++) ticks[g] = video.time[gopFirst[g]];
        this.gops = new GopTable(gopUs, ticks, video.timescale, audio != null);
        this.codecs = audio == null ? video.codec : video.codec + "," + audio.codec;
    }

    public static Mp4Index parse(File f) throws Mp4Exception {
        if (f == null) throw new Mp4Exception("no file");
        long mtime = RemovableStorageFs.lastModified(f);
        try (SeekableFileReader raf = new SeekableFileReader(f)) {
            FileChannel ch = raf.getChannel();
            long len = ch.size();
            return build(f, len, mtime, readMoov(ch, len));
        } catch (FileNotFoundException e) {
            throw new Mp4Exception("file not readable");
        } catch (IOException e) {
            throw new Mp4Exception("read failed: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new Mp4Exception("malformed: " + e);
        }
    }

    public File file() {
        return file;
    }

    public long fileLength() {
        return fileLength;
    }

    public long lastModified() {
        return lastModified;
    }

    public boolean hasVideo() {
        return video != null;
    }

    public boolean hasAudio() {
        return audio != null;
    }

    public int gopCount() {
        return gopFirst.length - 1;
    }

    public double gopDurationSec(int n) {
        checkGop(n);
        return (gopUs[n + 1] - gopUs[n]) / 1e6;
    }

    /** Presentation time of GOP n's keyframe, microseconds from media time 0. */
    public long gopStartUs(int n) {
        checkGop(n);
        return gopUs[n];
    }

    /** Presentation end of the video track in microseconds. */
    public long durationUs() {
        return gopUs[gopUs.length - 1];
    }

    /** RFC 6381 codecs: video, then audio when present (e.g. "avc1.42C01F,mp4a.40.2"). */
    public String codecs() {
        return codecs;
    }

    public int width() {
        return video.width;
    }

    public int height() {
        return video.height;
    }

    public byte[] videoStsd() {
        return video.stsd.clone();
    }

    void checkGop(int n) {
        if (n < 0 || n >= gopCount()) throw new IndexOutOfBoundsException("gop " + n + " of " + gopCount());
    }

    /** Silent-audio boundary of GOP g (g == gopCount: video end) in rate ticks, on the video's output timeline. */
    long silentTicks(int g, long rate) {
        int key = gopFirst[Math.min(g, gopCount() - 1)];
        return rescale(Math.max(0, video.time[gopFirst[g]] + video.ctsAt(key)), rate, video.timescale);
    }

    static long ticksToUs(long ticks, long timescale) {
        long q = Math.floorDiv(ticks, timescale);
        long r = Math.floorMod(ticks, timescale);
        return q * 1_000_000L + r * 1_000_000L / timescale;
    }

    private static byte[] readMoov(FileChannel ch, long len) throws IOException, Mp4Exception {
        ByteBuffer h = ByteBuffer.allocate(16);
        long pos = 0;
        long moovPos = -1;
        long moovSize = 0;
        int moovHdr = 0;
        for (int i = 0; pos <= len - 8; i++) {
            if (i >= MAX_TOP_LEVEL_BOXES) {
                if (moovPos >= 0) break;
                throw new Mp4Exception("too many top-level boxes");
            }
            h.clear();
            h.limit((int) Math.min(16, len - pos));
            readFully(ch, h, pos);
            byte[] b = h.array();
            long size = u32(b, 0);
            int type = s32(b, 4);
            int hdr = 8;
            if (size == 1) {
                size = h.limit() < 16 ? -1 : u64(b, 8);
                hdr = 16;
            } else if (size == 0) {
                size = len - pos;
            }
            if (size < hdr || size > len - pos) {
                // Junk after a complete moov is ignored; a broken box before it is fatal.
                if (moovPos >= 0) break;
                throw new Mp4Exception("top-level box " + name(type) + " overruns file");
            }
            if (type == MOOV) {
                if (moovPos >= 0) throw new Mp4Exception("duplicate moov");
                moovPos = pos;
                moovSize = size;
                moovHdr = hdr;
            }
            pos += size;
        }
        if (moovPos < 0) throw new Mp4Exception("no moov");
        long payload = moovSize - moovHdr;
        if (payload > MAX_MOOV_BYTES) throw new Mp4Exception("moov too large");
        byte[] m = new byte[(int) payload];
        readFully(ch, ByteBuffer.wrap(m), moovPos + moovHdr);
        return m;
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long pos) throws IOException {
        while (buf.hasRemaining()) {
            int r = ch.read(buf, pos);
            if (r <= 0) throw new EOFException("short read at " + pos);
            pos += r;
        }
    }

    /** Positions (payload offset / end) of the boxes of one trak; -1 when absent. */
    private static final class TrakInfo {
        int tkhd = -1, tkhdEnd;
        int elst = -1, elstEnd;
        int mdhd = -1, mdhdEnd;
        int hdlr = -1, hdlrEnd;
        int stsdStart = -1, stsd = -1, stsdEnd;
        int stts = -1, sttsEnd;
        int ctts = -1, cttsEnd;
        int stss = -1, stssEnd;
        int stsz = -1, stszEnd;
        int stz2 = -1;
        int stsc = -1, stscEnd;
        int stco = -1, stcoEnd;
        int co64 = -1, co64End;
        int handler;
        int entryType;
    }

    private static Mp4Index build(File f, long len, long mtime, byte[] m) throws Mp4Exception {
        long movieTs = -1;
        List<TrakInfo> traks = new ArrayList<>();
        Iter it = new Iter(m, 0, m.length);
        while (it.next()) {
            if (it.type == MVHD) {
                if (movieTs >= 0) throw new Mp4Exception("duplicate mvhd");
                movieTs = mvhdTimescale(m, it.payload, it.boxEnd);
            } else if (it.type == TRAK) {
                TrakInfo t = new TrakInfo();
                scan(m, it.payload, it.boxEnd, t, TRAK, 0);
                classify(m, t);
                traks.add(t);
            } else if (it.type == MVEX) {
                throw new Mp4Exception("fragmented input");
            }
        }
        if (movieTs <= 0) throw new Mp4Exception("mvhd missing or zero timescale");

        TrakInfo vInfo = null;
        TrakInfo aInfo = null;
        int otherVideo = 0;
        for (TrakInfo t : traks) {
            if (t.handler == VIDE) {
                boolean ok = t.entryType == AVC1 || t.entryType == AVC3 || t.entryType == HVC1 || t.entryType == HEV1;
                if (ok && vInfo == null) vInfo = t;
                else if (!ok) otherVideo = t.entryType;
            } else if (t.handler == SOUN && t.entryType == MP4A && aInfo == null) {
                aInfo = t;
            }
        }
        if (vInfo == null) {
            throw new Mp4Exception(otherVideo != 0 ? "unsupported video codec " + name(otherVideo) : "no video track");
        }

        Track v = track(m, vInfo, true, movieTs, len);
        Track a = aInfo == null ? null : track(m, aInfo, false, movieTs, len);

        int n = v.size.length;
        int[] sync = v.sync;
        if (sync != null && sync.length == 0) throw new Mp4Exception("video has no sync samples");
        int gops = sync == null ? n : sync.length;
        int[] gopFirst = new int[gops + 1];
        for (int g = 0; g < gops; g++) gopFirst[g] = sync == null ? g : sync[g];
        gopFirst[gops] = n;

        // fMP4 decode times are unsigned: lift every track by the most negative start.
        long minUs = v.toUs(v.time[gopFirst[0]]);
        if (a != null) minUs = Math.min(minUs, a.toUs(a.time[0]));
        Mp4Track video = v.finish(minUs < 0 ? -minUs : 0);
        Mp4Track audio = a == null ? null : a.finish(minUs < 0 ? -minUs : 0);

        long[] gopUs = new long[gops + 1];
        for (int g = 0; g < gops; g++) {
            int s = gopFirst[g];
            gopUs[g] = video.toUs(video.time[s] + video.ctsAt(s) - video.baseTicks);
        }
        gopUs[gops] = video.toUs(video.time[n] + video.ctsAt(gopFirst[gops - 1]) - video.baseTicks);
        for (int g = 0; g < gops; g++) {
            if (gopUs[g + 1] < gopUs[g]) throw new Mp4Exception("video GOP times not monotonic");
        }

        int[] audioFirst = null;
        if (audio != null) {
            audioFirst = new int[gops + 1];
            int i = 0;
            for (int g = 1; g < gops; g++) {
                int s = gopFirst[g];
                long vUs = video.toUs(video.time[s] + video.ctsAt(s));
                while (i < audio.count && audio.toUs(audio.time[i] - audio.editMediaTime) < vUs) i++;
                audioFirst[g] = i;
            }
            audioFirst[gops] = audio.count;
        }
        return new Mp4Index(f, len, mtime, video, audio, gopFirst, gopUs, audioFirst);
    }

    private static long mvhdTimescale(byte[] m, int p, int end) throws Mp4Exception {
        need(p, 4, end, "mvhd");
        int v = u8(m, p);
        if (v > 1) throw new Mp4Exception("mvhd version " + v);
        int at = p + (v == 1 ? 20 : 12);
        need(at, 4, end, "mvhd");
        return u32(m, at);
    }

    private static void scan(byte[] m, int start, int end, TrakInfo t, int parent, int depth)
            throws Mp4Exception {
        if (depth > MAX_DEPTH) throw new Mp4Exception("boxes nested too deep");
        Iter it = new Iter(m, start, end);
        while (it.next()) {
            int ty = it.type;
            if (ty == EDTS || ty == MDIA || ty == MINF || ty == STBL) {
                scan(m, it.payload, it.boxEnd, t, ty, depth + 1);
            } else if (ty == TKHD) {
                if (t.tkhd >= 0) throw dup(ty);
                t.tkhd = it.payload;
                t.tkhdEnd = it.boxEnd;
            } else if (ty == ELST) {
                if (t.elst >= 0) throw dup(ty);
                t.elst = it.payload;
                t.elstEnd = it.boxEnd;
            } else if (ty == MDHD) {
                if (t.mdhd >= 0) throw dup(ty);
                t.mdhd = it.payload;
                t.mdhdEnd = it.boxEnd;
            } else if (ty == HDLR && parent == MDIA) {
                if (t.hdlr >= 0) throw dup(ty);
                t.hdlr = it.payload;
                t.hdlrEnd = it.boxEnd;
            } else if (ty == STSD) {
                if (t.stsd >= 0) throw dup(ty);
                t.stsdStart = it.start;
                t.stsd = it.payload;
                t.stsdEnd = it.boxEnd;
            } else if (ty == STTS) {
                if (t.stts >= 0) throw dup(ty);
                t.stts = it.payload;
                t.sttsEnd = it.boxEnd;
            } else if (ty == CTTS) {
                if (t.ctts >= 0) throw dup(ty);
                t.ctts = it.payload;
                t.cttsEnd = it.boxEnd;
            } else if (ty == STSS) {
                if (t.stss >= 0) throw dup(ty);
                t.stss = it.payload;
                t.stssEnd = it.boxEnd;
            } else if (ty == STSZ) {
                if (t.stsz >= 0) throw dup(ty);
                t.stsz = it.payload;
                t.stszEnd = it.boxEnd;
            } else if (ty == STZ2) {
                t.stz2 = it.payload;
            } else if (ty == STSC) {
                if (t.stsc >= 0) throw dup(ty);
                t.stsc = it.payload;
                t.stscEnd = it.boxEnd;
            } else if (ty == STCO) {
                if (t.stco >= 0) throw dup(ty);
                t.stco = it.payload;
                t.stcoEnd = it.boxEnd;
            } else if (ty == CO64) {
                if (t.co64 >= 0) throw dup(ty);
                t.co64 = it.payload;
                t.co64End = it.boxEnd;
            }
        }
    }

    private static Mp4Exception dup(int type) {
        return new Mp4Exception("duplicate " + name(type));
    }

    /** Lenient: a track we will not use must not reject the file. */
    private static void classify(byte[] m, TrakInfo t) {
        if (t.hdlr >= 0 && t.hdlrEnd - t.hdlr >= 12) t.handler = s32(m, t.hdlr + 8);
        if (t.stsd >= 0 && t.stsdEnd - t.stsd >= 16) t.entryType = s32(m, t.stsd + 12);
    }

    /** Mutable per-track tables while parsing. */
    private static final class Track {
        boolean isVideo;
        long ts;
        byte[] stsd;
        String codec;
        long[] time;
        int[] size;
        long[] offset;
        int[] cts;
        int[] sync;
        long editMediaTime;
        int tkhdFlags, layer, altGroup, volume, language, width, height;
        byte[] matrix;
        long tkhdWidth, tkhdHeight;

        long toUs(long ticks) {
            return ticksToUs(ticks, ts);
        }

        Mp4Track finish(long baseUs) {
            long base = 0;
            if (baseUs > 0) {
                base = rescaleUp(baseUs, ts, 1_000_000L);
                for (int i = 0; i < time.length; i++) time[i] += base;
            }
            return new Mp4Track(isVideo, ts, stsd, codec, time, size, offset, cts, base, editMediaTime, tkhdFlags,
                    layer, altGroup, volume, matrix, tkhdWidth, tkhdHeight, language, width, height);
        }
    }

    private static Track track(byte[] m, TrakInfo t, boolean video, long movieTs, long fileLen)
            throws Mp4Exception {
        String w = video ? "video " : "audio ";
        Track tr = new Track();
        tr.isVideo = video;

        if (t.tkhd < 0) throw new Mp4Exception(w + "tkhd missing");
        need(t.tkhd, 4, t.tkhdEnd, "tkhd");
        int tv = u8(m, t.tkhd);
        if (tv > 1) throw new Mp4Exception(w + "tkhd version " + tv);
        int tb = t.tkhd + (tv == 1 ? 36 : 24);
        need(tb, 60, t.tkhdEnd, "tkhd");
        tr.tkhdFlags = s32(m, t.tkhd) & 0xffffff;
        tr.layer = u16(m, tb + 8);
        tr.altGroup = u16(m, tb + 10);
        tr.volume = u16(m, tb + 12);
        tr.matrix = Arrays.copyOfRange(m, tb + 16, tb + 52);
        tr.tkhdWidth = u32(m, tb + 52);
        tr.tkhdHeight = u32(m, tb + 56);

        if (t.mdhd < 0) throw new Mp4Exception(w + "mdhd missing");
        need(t.mdhd, 4, t.mdhdEnd, "mdhd");
        int mv = u8(m, t.mdhd);
        if (mv == 1) {
            need(t.mdhd, 34, t.mdhdEnd, "mdhd");
            tr.ts = u32(m, t.mdhd + 20);
            tr.language = u16(m, t.mdhd + 32);
        } else if (mv == 0) {
            need(t.mdhd, 22, t.mdhdEnd, "mdhd");
            tr.ts = u32(m, t.mdhd + 12);
            tr.language = u16(m, t.mdhd + 20);
        } else {
            throw new Mp4Exception(w + "mdhd version " + mv);
        }
        if (tr.ts == 0) throw new Mp4Exception(w + "timescale 0");

        sampleEntry(m, t, tr);

        if (t.stz2 >= 0) throw new Mp4Exception(w + "stz2 unsupported");
        if (t.stsz < 0) throw new Mp4Exception(w + "stsz missing");
        need(t.stsz, 12, t.stszEnd, "stsz");
        long fixed = u32(m, t.stsz + 4);
        long count = u32(m, t.stsz + 8);
        if (count > MAX_SAMPLES) throw new Mp4Exception(w + "too many samples");
        int n = (int) count;
        if (n == 0) {
            if (video) throw new Mp4Exception("video has no samples");
            return null;
        }
        int[] size = new int[n];
        if (fixed == 0) {
            need(t.stsz + 12, 4 * n, t.stszEnd, "stsz");
            for (int i = 0, p = t.stsz + 12; i < n; i++, p += 4) {
                long s = u32(m, p);
                if (s > MAX_SAMPLE_BYTES) throw new Mp4Exception(w + "sample too large");
                size[i] = (int) s;
            }
        } else {
            if (fixed > MAX_SAMPLE_BYTES) throw new Mp4Exception(w + "sample too large");
            Arrays.fill(size, (int) fixed);
        }

        long[] time = decodeTimes(m, t, n, w);
        if (time[n] / tr.ts > MAX_TRACK_SECONDS) throw new Mp4Exception(w + "duration too long");
        int[] cts = compositionOffsets(m, t, n, w);

        if (t.stss >= 0) {
            need(t.stss, 8, t.stssEnd, "stss");
            long ec = u32(m, t.stss + 4);
            if (ec > (t.stssEnd - t.stss - 8) / 4 || ec > n) throw new Mp4Exception(w + "stss truncated");
            int[] sync = new int[(int) ec];
            long prev = 0;
            for (int i = 0, p = t.stss + 8; i < sync.length; i++, p += 4) {
                long s = u32(m, p);
                if (s <= prev || s > n) throw new Mp4Exception(w + "stss out of order");
                sync[i] = (int) (s - 1);
                prev = s;
            }
            tr.sync = sync;
        }

        long[] offset = sampleOffsets(m, t, size, fileLen, w);

        // Edit list: honour a leading empty edit and the first media_time (rate 1).
        long emptyMv = 0;
        long mediaTime = 0;
        if (t.elst >= 0) {
            need(t.elst, 8, t.elstEnd, "elst");
            int ev = u8(m, t.elst);
            if (ev > 1) throw new Mp4Exception(w + "elst version " + ev);
            int es = ev == 1 ? 20 : 12;
            long ec = u32(m, t.elst + 4);
            if (ec > (t.elstEnd - t.elst - 8) / es) throw new Mp4Exception(w + "elst truncated");
            int first = 0;
            for (int i = 0, p = t.elst + 8; i < ec && i <= first; i++, p += es) {
                long dur = ev == 1 ? u64(m, p) : u32(m, p);
                long mt = ev == 1 ? u64(m, p + 8) : s32(m, p + 4);
                int rate = u16(m, p + (ev == 1 ? 16 : 8));
                if (dur < 0 || mt < -1) throw new Mp4Exception(w + "elst entry invalid");
                if (i == 0 && mt == -1) {
                    if (dur / movieTs > MAX_TRACK_SECONDS) throw new Mp4Exception(w + "elst too long");
                    emptyMv = dur;
                    first = 1;
                } else if (mt >= 0 && rate == 1) {
                    if (mt > time[n]) throw new Mp4Exception(w + "elst starts after media end");
                    mediaTime = mt;
                }
            }
        }
        long empty = rescale(emptyMv, tr.ts, movieTs);
        if (!video && cts == null && mediaTime > 0) {
            // Priming before media_time stays in the stream for decoder warm-up; the init's elst trims it.
            if (mediaTime >= time[n]) return null;
            for (int i = 0; i <= n; i++) time[i] += empty;
            tr.editMediaTime = mediaTime;
        } else if (cts == null) {
            long shift = empty - mediaTime;
            for (int i = 0; i <= n; i++) time[i] += shift;
        } else {
            for (int i = 0; i <= n; i++) time[i] += empty;
            for (int i = 0; i < n; i++) {
                long c = cts[i] - mediaTime;
                if (c < Integer.MIN_VALUE) throw new Mp4Exception(w + "composition offset out of range");
                cts[i] = (int) c;
            }
        }
        tr.time = time;
        tr.size = size;
        tr.offset = offset;
        tr.cts = cts;
        return tr;
    }

    private static long[] decodeTimes(byte[] m, TrakInfo t, int n, String w) throws Mp4Exception {
        if (t.stts < 0) throw new Mp4Exception(w + "stts missing");
        need(t.stts, 8, t.sttsEnd, "stts");
        long ec = u32(m, t.stts + 4);
        if (ec > (t.sttsEnd - t.stts - 8) / 8) throw new Mp4Exception(w + "stts truncated");
        long[] time = new long[n + 1];
        int k = 0;
        long acc = 0;
        for (long e = 0, p = t.stts + 8; e < ec; e++, p += 8) {
            long c = u32(m, (int) p);
            long d = u32(m, (int) p + 4);
            if (c > n - k) throw new Mp4Exception(w + "stts overruns sample count");
            for (int j = 0; j < c; j++) {
                time[k++] = acc;
                acc += d;
            }
        }
        if (k != n) throw new Mp4Exception(w + "stts covers " + k + " of " + n + " samples");
        time[n] = acc;
        return time;
    }

    private static int[] compositionOffsets(byte[] m, TrakInfo t, int n, String w) throws Mp4Exception {
        if (t.ctts < 0) return null;
        need(t.ctts, 8, t.cttsEnd, "ctts");
        int ver = u8(m, t.ctts);
        if (ver > 1) throw new Mp4Exception(w + "ctts version " + ver);
        long ec = u32(m, t.ctts + 4);
        if (ec > (t.cttsEnd - t.ctts - 8) / 8) throw new Mp4Exception(w + "ctts truncated");
        int[] cts = new int[n];
        int k = 0;
        boolean nonZero = false;
        for (long e = 0, p = t.ctts + 8; e < ec; e++, p += 8) {
            long c = u32(m, (int) p);
            // v0 is nominally unsigned; writers emit signed values there too.
            int off = s32(m, (int) p + 4);
            if (c > n - k) throw new Mp4Exception(w + "ctts overruns sample count");
            Arrays.fill(cts, k, k + (int) c, off);
            k += (int) c;
            if (off != 0 && c > 0) nonZero = true;
        }
        if (k != n) throw new Mp4Exception(w + "ctts covers " + k + " of " + n + " samples");
        return nonZero ? cts : null;
    }

    private static long[] sampleOffsets(byte[] m, TrakInfo t, int[] size, long fileLen, String w)
            throws Mp4Exception {
        int n = size.length;
        if (t.stco >= 0 && t.co64 >= 0) throw new Mp4Exception(w + "stco and co64");
        long[] chunk;
        if (t.stco >= 0 || t.co64 >= 0) {
            boolean wide = t.co64 >= 0;
            int p = wide ? t.co64 : t.stco;
            int end = wide ? t.co64End : t.stcoEnd;
            int es = wide ? 8 : 4;
            need(p, 8, end, wide ? "co64" : "stco");
            long ec = u32(m, p + 4);
            if (ec > (end - p - 8) / es) throw new Mp4Exception(w + "chunk table truncated");
            if (ec > n) throw new Mp4Exception(w + "more chunks than samples");
            chunk = new long[(int) ec];
            for (int i = 0, q = p + 8; i < chunk.length; i++, q += es) {
                chunk[i] = wide ? u64(m, q) : u32(m, q);
                if (chunk[i] < 0) throw new Mp4Exception(w + "chunk offset invalid");
            }
        } else {
            throw new Mp4Exception(w + "chunk offsets missing");
        }

        if (t.stsc < 0) throw new Mp4Exception(w + "stsc missing");
        need(t.stsc, 8, t.stscEnd, "stsc");
        long ec = u32(m, t.stsc + 4);
        if (ec == 0 || ec > (t.stscEnd - t.stsc - 8) / 12) throw new Mp4Exception(w + "stsc truncated");
        long chunks = chunk.length;
        long[] offset = new long[n];
        int s = 0;
        for (int e = 0, q = t.stsc + 8; e < ec; e++, q += 12) {
            long fc = u32(m, q);
            long spc = u32(m, q + 4);
            long sdi = u32(m, q + 8);
            long next = e + 1 < ec ? u32(m, q + 12) : chunks + 1;
            if (e == 0 && fc != 1) throw new Mp4Exception(w + "stsc does not start at chunk 1");
            if (next <= fc || next > chunks + 1) throw new Mp4Exception(w + "stsc out of order");
            if (spc == 0 || spc > n) throw new Mp4Exception(w + "stsc samples per chunk invalid");
            if (sdi != 1) throw new Mp4Exception(w + "multiple sample descriptions");
            for (long c = fc - 1; c < next - 1; c++) {
                long o = chunk[(int) c];
                for (long j = 0; j < spc; j++) {
                    if (s >= n) throw new Mp4Exception(w + "chunks hold more samples than stsz");
                    long e2 = o + size[s];
                    if (e2 > fileLen) throw new Mp4Exception(w + "sample beyond end of file");
                    offset[s++] = o;
                    o = e2;
                }
            }
        }
        if (s != n) throw new Mp4Exception(w + "chunks hold " + s + " of " + n + " samples");
        return offset;
    }

    private static void sampleEntry(byte[] m, TrakInfo t, Track tr) throws Mp4Exception {
        if (t.stsd < 0) throw new Mp4Exception("stsd missing");
        need(t.stsd, 8, t.stsdEnd, "stsd");
        if (u32(m, t.stsd + 4) < 1) throw new Mp4Exception("stsd empty");
        int e = t.stsd + 8;
        need(e, 8, t.stsdEnd, "stsd entry");
        long es = u32(m, e);
        if (es < 8 || es > t.stsdEnd - e) throw new Mp4Exception("stsd entry overruns stsd");
        int ee = e + (int) es;
        int type = s32(m, e + 4);
        tr.stsd = Arrays.copyOfRange(m, t.stsdStart, t.stsdEnd);
        if (tr.isVideo) {
            need(e, 86, ee, "visual sample entry");
            tr.width = u16(m, e + 32);
            tr.height = u16(m, e + 34);
            if (tr.width == 0 || tr.height == 0) {
                tr.width = (int) (tr.tkhdWidth >>> 16);
                tr.height = (int) (tr.tkhdHeight >>> 16);
            }
            Iter it = new Iter(m, e + 86, ee);
            while (it.next()) {
                if (it.type == AVCC && (type == AVC1 || type == AVC3)) {
                    need(it.payload, 4, it.boxEnd, "avcC");
                    tr.codec = name(type) + "." + hex2(m[it.payload + 1]) + hex2(m[it.payload + 2])
                            + hex2(m[it.payload + 3]);
                } else if (it.type == HVCC && (type == HVC1 || type == HEV1)) {
                    need(it.payload, 13, it.boxEnd, "hvcC");
                    tr.codec = hevcCodec(name(type), m, it.payload);
                }
            }
            if (tr.codec == null) throw new Mp4Exception("video decoder config missing");
        } else {
            need(e, 36, ee, "audio sample entry");
            int ver = u16(m, e + 16);
            int kids = e + 36 + (ver == 1 ? 16 : ver == 2 ? 36 : 0);
            String codec = kids <= ee ? esdsCodec(m, kids, ee, 0) : null;
            tr.codec = codec != null ? codec : "mp4a.40.2";
        }
    }

    private static String esdsCodec(byte[] m, int start, int end, int depth) throws Mp4Exception {
        Iter it = new Iter(m, start, end);
        while (it.next()) {
            if (it.type == ESDS) return aacCodec(m, it.payload + 4, it.boxEnd);
            if (it.type == WAVE && depth == 0) {
                String c = esdsCodec(m, it.payload, it.boxEnd, 1);
                if (c != null) return c;
            }
        }
        return null;
    }

    /** Walks ES_Descriptor -> DecoderConfigDescriptor -> AudioSpecificConfig; null when unreadable. */
    private static String aacCodec(byte[] m, int p, int end) {
        try {
            int[] len = new int[1];
            if (p >= end || m[p] != 0x03) return null;
            p = descriptorBody(m, p + 1, end, len);
            need(p, 3, end, "esds");
            int flags = u8(m, p + 2);
            p += 3;
            if ((flags & 0x80) != 0) p += 2;
            if ((flags & 0x40) != 0) {
                need(p, 1, end, "esds");
                p += 1 + u8(m, p);
            }
            if ((flags & 0x20) != 0) p += 2;
            if (p >= end || m[p] != 0x04) return null;
            p = descriptorBody(m, p + 1, end, len);
            need(p, 13, end, "esds");
            int oti = u8(m, p);
            p += 13;
            if (oti != 0x40) return "mp4a." + hex2((byte) oti);
            if (p >= end || m[p] != 0x05) return "mp4a.40.2";
            p = descriptorBody(m, p + 1, end, len);
            need(p, 2, end, "esds");
            int aot = u8(m, p) >> 3;
            if (aot == 31) aot = 32 + (((u8(m, p) & 7) << 3) | (u8(m, p + 1) >> 5));
            return aot == 0 ? "mp4a.40.2" : "mp4a.40." + aot;
        } catch (Mp4Exception | RuntimeException e) {
            return null;
        }
    }

    private static int descriptorBody(byte[] m, int p, int end, int[] len) throws Mp4Exception {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            need(p, 1, end, "esds");
            int b = u8(m, p++);
            v = (v << 7) | (b & 0x7f);
            if ((b & 0x80) == 0) break;
        }
        len[0] = v;
        return p;
    }

    /** ISO/IEC 14496-15 Annex E codec string, e.g. hvc1.1.6.L93.B0. */
    private static String hevcCodec(String fourcc, byte[] m, int p) {
        int x = u8(m, p + 1);
        int space = x >> 6;
        int tier = (x >> 5) & 1;
        int idc = x & 0x1f;
        int compat = Integer.reverse(s32(m, p + 2));
        StringBuilder sb = new StringBuilder(fourcc).append('.');
        if (space > 0) sb.append((char) ('A' + space - 1));
        sb.append(idc).append('.').append(Integer.toHexString(compat).toUpperCase(java.util.Locale.ROOT));
        sb.append('.').append(tier == 0 ? 'L' : 'H').append(u8(m, p + 12));
        int last = -1;
        for (int i = 0; i < 6; i++) if (m[p + 6 + i] != 0) last = i;
        for (int i = 0; i <= last; i++) sb.append('.').append(hex2(m[p + 6 + i]));
        return sb.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static String hex2(byte b) {
        return new String(new char[] {HEX[(b >> 4) & 0xf], HEX[b & 0xf]});
    }

    /** round(v * num / den) for non-negative inputs. */
    static long rescale(long v, long num, long den) {
        try {
            return Math.addExact(Math.multiplyExact(v, num), den / 2) / den;
        } catch (ArithmeticException e) {
            return BigInteger.valueOf(v).multiply(BigInteger.valueOf(num))
                    .add(BigInteger.valueOf(den / 2)).divide(BigInteger.valueOf(den)).longValue();
        }
    }

    /** ceil(v * num / den) for non-negative inputs. */
    private static long rescaleUp(long v, long num, long den) {
        try {
            return Math.addExact(Math.multiplyExact(v, num), den - 1) / den;
        } catch (ArithmeticException e) {
            return BigInteger.valueOf(v).multiply(BigInteger.valueOf(num))
                    .add(BigInteger.valueOf(den - 1)).divide(BigInteger.valueOf(den)).longValue();
        }
    }
}
