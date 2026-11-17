package com.overdrive.app.server.hls;

import static com.overdrive.app.server.hls.Boxes.fourcc;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * fMP4 init and media segments built from an Mp4Index. Media segments stream sample bytes from
 * the source file; only the moof is held in memory.
 */
public final class Fmp4Writer {
    static final int VIDEO_ID = 1;
    static final int AUDIO_ID = 2;
    private static final int COPY_BUFFER = 64 * 1024;
    private static final int SYNC = 0x02000000;
    private static final int NON_SYNC = 0x01010000;
    private static final int[] IDENTITY = {0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000};
    /** AAC-LC mono raw frame with zero spectra: decodes to 1024 samples of silence. */
    private static final byte[] SILENT_FRAME = {0x00, (byte) 0xC8, 0x00, (byte) 0x80, 0x23, (byte) 0x80};
    static final long SILENT_RATE = 16000;
    private static final long SILENT_FRAME_TICKS = 1024;

    private static final int FTYP = fourcc("ftyp");
    private static final int MOOF = fourcc("moof");
    private static final int MFHD = fourcc("mfhd");
    private static final int TRAF = fourcc("traf");
    private static final int TFHD = fourcc("tfhd");
    private static final int TFDT = fourcc("tfdt");
    private static final int TRUN = fourcc("trun");
    private static final int MDAT = fourcc("mdat");
    private static final int TREX = fourcc("trex");
    private static final Mp4Track SILENT_TRACK = silentTrack();

    private Fmp4Writer() {}

    public static byte[] initSegment(Mp4Index idx, boolean includeAudio) {
        return init(idx, includeAudio ? idx.audio : null);
    }

    /**
     * Init with a silent AAC-LC 16 kHz mono track for a video-only clip, so a window mixing clips
     * with and without audio keeps one track layout. A clip with audio gets its normal init.
     */
    public static byte[] initSegmentSilentAudio(Mp4Index idx) {
        return init(idx, idx.audio != null ? idx.audio : SILENT_TRACK);
    }

    private static byte[] init(Mp4Index idx, Mp4Track a) {
        BoxWriter w = new BoxWriter(1024 + idx.video.stsd.length + (a == null ? 0 : a.stsd.length));
        int ftyp = w.begin(FTYP);
        w.u32(fourcc("iso5"));
        w.u32(512);
        w.u32(fourcc("iso5"));
        w.u32(fourcc("iso6"));
        w.u32(fourcc("mp41"));
        w.end(ftyp);
        int moov = w.begin(Boxes.MOOV);
        int mvhd = w.beginFull(Boxes.MVHD, 0, 0);
        w.zeros(8);
        w.u32(1000);
        w.u32(0);
        w.u32(0x00010000);
        w.u16(0x0100);
        w.zeros(10);
        for (int v : IDENTITY) w.u32(v & 0xffffffffL);
        w.zeros(24);
        w.u32(a == null ? 2 : 3);
        w.end(mvhd);
        trak(w, idx.video, VIDEO_ID);
        if (a != null) trak(w, a, AUDIO_ID);
        int mvex = w.begin(Boxes.MVEX);
        trex(w, VIDEO_ID);
        if (a != null) trex(w, AUDIO_ID);
        w.end(mvex);
        w.end(moov);
        return w.toByteArray();
    }

    public static byte[] initVideoOnly(Mp4Index idx) {
        return initSegment(idx, false);
    }

    /** Exact byte length of s{n}.m4s. */
    public static long mediaSegmentLength(Mp4Index idx, int n) {
        return mediaPlan(idx, n).length();
    }

    public static void writeMediaSegment(Mp4Index idx, FileChannel src, int n, OutputStream out)
            throws IOException {
        Plan p = mediaPlan(idx, n);
        write(idx, src, p, out);
    }

    /** Exact length of s{n}.m4s with silent audio covering the GOP; ignored when the clip has audio. */
    public static long mediaSegmentLength(Mp4Index idx, int n, boolean silentAudio) {
        return mediaPlan(idx, n, silentAudio).length();
    }

    public static void writeMediaSegment(Mp4Index idx, FileChannel src, int n, boolean silentAudio,
                                         OutputStream out) throws IOException {
        write(idx, src, mediaPlan(idx, n, silentAudio), out);
    }

    /** Exact byte length of k{k}s{n}.m4s. */
    public static long skimSegmentLength(Mp4Index idx, int n, int k) {
        return skimPlan(idx, n, k).length();
    }

    public static void writeSkimSegment(Mp4Index idx, FileChannel src, int n, int k, OutputStream out)
            throws IOException {
        write(idx, src, skimPlan(idx, n, k), out);
    }

    static void checkK(int k) {
        if (k < 1 || k > 64) throw new IllegalArgumentException("skim factor " + k);
    }

    private static final class Plan {
        final byte[] head;
        final int vFrom, vTo, aFrom, aTo;
        final int silentFrames;
        final long payload;
        /** Audio entries in mdat order when silence is interleaved; null for a plain sample range. */
        final AudioRun run;

        Plan(byte[] head, int vFrom, int vTo, int aFrom, int aTo, int silentFrames, long payload) {
            this(head, vFrom, vTo, aFrom, aTo, silentFrames, payload, null);
        }

        Plan(byte[] head, int vFrom, int vTo, int aFrom, int aTo, int silentFrames, long payload,
             AudioRun run) {
            this.head = head;
            this.vFrom = vFrom;
            this.vTo = vTo;
            this.aFrom = aFrom;
            this.aTo = aTo;
            this.silentFrames = silentFrames;
            this.payload = payload;
            this.run = run;
        }

        long length() {
            return head.length + payload;
        }
    }

    private static Plan mediaPlan(Mp4Index idx, int n) {
        return mediaPlan(idx, n, false);
    }

    private static Plan mediaPlan(Mp4Index idx, int n, boolean silentAudio) {
        idx.checkGop(n);
        Mp4Track v = idx.video;
        Mp4Track a = idx.audio;
        int vFrom = idx.gopFirst[n];
        int vTo = idx.gopFirst[n + 1];
        int aFrom = a == null ? 0 : idx.audioFirst[n];
        int aTo = a == null ? 0 : idx.audioFirst[n + 1];
        long vBytes = bytes(v, vFrom, vTo);
        // MSE buffers only where every track has media, and Chromium plays AAC frames back to back
        // whatever their durations: audio must cover the video in whole frames.
        AudioRun run = a != null ? filledRun(idx, n) : silentAudio ? silentRun(idx, n) : null;
        long aBytes = run != null ? run.bytes : a == null ? 0 : bytes(a, aFrom, aTo);
        int mdatHdr = mdatHeaderSize(vBytes + aBytes);
        BoxWriter w = new BoxWriter(160 + 16 * (vTo - vFrom + aTo - aFrom + (run == null ? 0 : run.count())));
        int moof = w.begin(MOOF);
        mfhd(w, n + 1);
        int vOff = traf(w, v, VIDEO_ID, vFrom, vTo, v.time[vFrom], -1);
        int aOff = run != null ? (run.count() > 0 ? runTraf(w, run) : -1)
                : aTo > aFrom ? traf(w, a, AUDIO_ID, aFrom, aTo, a.time[aFrom], -1) : -1;
        w.end(moof);
        long base = (long) w.size() + mdatHdr;
        if (base + vBytes > Integer.MAX_VALUE) throw new IllegalStateException("segment too large");
        w.patch32(vOff, base);
        if (aOff >= 0) w.patch32(aOff, base + vBytes);
        mdatHeader(w, vBytes + aBytes, mdatHdr);
        return new Plan(w.toByteArray(), vFrom, vTo, aFrom, aTo, 0, vBytes + aBytes, run);
    }

    /** One audio traf: real samples (index >= 0) and silent frames (-1), back to back from decodeStart. */
    private static final class AudioRun {
        final long decodeStart;
        final int[] sample;
        final long[] duration;
        final int[] size;
        final long bytes;

        AudioRun(long decodeStart, int[] sample, long[] duration, int[] size) {
            this.decodeStart = decodeStart;
            this.sample = sample;
            this.duration = duration;
            this.size = size;
            long sum = 0;
            for (int s : size) sum += s;
            this.bytes = sum;
        }

        int count() {
            return sample.length;
        }
    }

    private static final class RunBuilder {
        int[] sample = new int[32];
        long[] duration = new long[32];
        int[] size = new int[32];
        int n;
        long start = Long.MIN_VALUE;

        void add(int s, long d, int bytes, long at) {
            if (n == sample.length) {
                sample = java.util.Arrays.copyOf(sample, n * 2);
                duration = java.util.Arrays.copyOf(duration, n * 2);
                size = java.util.Arrays.copyOf(size, n * 2);
            }
            if (n == 0) start = at;
            sample[n] = s;
            duration[n] = d;
            size[n] = bytes;
            n++;
        }

        AudioRun build() {
            return new AudioRun(start, java.util.Arrays.copyOf(sample, n),
                    java.util.Arrays.copyOf(duration, n), java.util.Arrays.copyOf(size, n));
        }
    }

    private static final int[] AAC_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350};

    /**
     * AAC frame length in track ticks when this track can take synthesized silence, else -1: the
     * decoder config (not the sample entry) must say AAC-LC, mono, 1024-sample frames, and the
     * samples must really be spaced one frame apart.
     */
    static long silenceFrameTicks(Mp4Track a) {
        if (a == null || a.video || a.cts != null || a.count < 1 || !"mp4a.40.2".equals(a.codec)) return -1;
        byte[] s = a.stsd;
        if (s == null || s.length < 52 || Boxes.u32(s, 20) != Boxes.MP4A) return -1;
        int[] asc = audioSpecificConfig(s);
        if (asc == null || asc[0] != 2 || asc[2] != 1 || asc[3] != 0 || asc[1] >= AAC_RATES.length) return -1;
        long rate = AAC_RATES[asc[1]];
        long ticks = SILENT_FRAME_TICKS * a.timescale;
        if (ticks % rate != 0) return -1;
        long f = ticks / rate;
        return medianDuration(a) == f ? f : -1;
    }

    /** {objectType, rateIndex, channelConfig, frameLengthFlag} from the esds, or null. */
    static int[] audioSpecificConfig(byte[] s) {
        try {
            int p = -1;
            for (int i = 4; i + 4 <= s.length; i++) {
                if (s[i] == 'e' && s[i + 1] == 's' && s[i + 2] == 'd' && s[i + 3] == 's') { p = i + 8; break; }
            }
            if (p < 0) return null;
            int[] pos = {p};
            if (descriptor(s, pos, 0x03) < 0) return null;
            int flags = s[pos[0] + 2] & 0xff;
            pos[0] += 3;
            if ((flags & 0x80) != 0) pos[0] += 2;
            if ((flags & 0x40) != 0) pos[0] += 1 + (s[pos[0]] & 0xff);
            if ((flags & 0x20) != 0) pos[0] += 2;
            if (descriptor(s, pos, 0x04) < 0) return null;
            pos[0] += 13;
            if (descriptor(s, pos, 0x05) < 2) return null;
            int b0 = s[pos[0]] & 0xff;
            int b1 = s[pos[0] + 1] & 0xff;
            int aot = b0 >> 3;
            int rateIndex = ((b0 & 7) << 1) | (b1 >> 7);
            int channels = (b1 >> 3) & 0x0f;
            int frameLengthFlag = (b1 >> 2) & 1;
            return new int[]{aot, rateIndex, channels, frameLengthFlag};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads a descriptor tag and its variable length; leaves pos at the body. Returns the length, -1 on mismatch. */
    private static int descriptor(byte[] s, int[] pos, int tag) {
        if ((s[pos[0]] & 0xff) != tag) return -1;
        int p = pos[0] + 1;
        int len = 0;
        for (int i = 0; i < 4; i++) {
            int b = s[p++] & 0xff;
            len = (len << 7) | (b & 0x7f);
            if ((b & 0x80) == 0) break;
        }
        pos[0] = p;
        return len;
    }

    /** Median sample duration over up to 4096 samples: robust to holes and to a stretched first sample. */
    private static long medianDuration(Mp4Track a) {
        int n = Math.min(a.count, 4096);
        if (n <= 0) return -1;
        long[] d = new long[n];
        for (int i = 0; i < n; i++) d[i] = a.duration(i);
        java.util.Arrays.sort(d);
        return d[n / 2];
    }

    /** The whole clip's audio as Chromium plays it, back to back: real samples and silent frames (-1). */
    private static final class ClipAudio {
        final long f;
        final long[] start;
        final int[] sample;
        final int n;

        ClipAudio(long f, long[] start, int[] sample, int n) {
            this.f = f;
            this.start = start;
            this.sample = sample;
            this.n = n;
        }
    }

    private static final Object NO_FILL = new Object();
    // Clips run minutes; a span past this is a corrupt timestamp, not hours of silence to build.
    private static final long MAX_FILL_SECONDS = 3L * 3600L;
    private static final java.util.Map<Mp4Index, Object> CLIP_AUDIO =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Mp4Index, Object>());

    /** Null when every sample already plays where its timestamp says (the plain traf path is exact). */
    static ClipAudio clipAudio(Mp4Index idx) {
        Object cached = CLIP_AUDIO.get(idx);
        if (cached == null) {
            ClipAudio built = buildClipAudio(idx);
            cached = built != null ? built : NO_FILL;
            CLIP_AUDIO.put(idx, cached);
        }
        return cached instanceof ClipAudio ? (ClipAudio) cached : null;
    }

    /**
     * Lays every frame on the back-to-back playback line and adds silence for a late start, a first
     * sample stretched over a late start, holes and an early end. A hole counts only when the lag
     * persists to the next sample, so timestamp jitter never shifts later audio.
     */
    private static ClipAudio buildClipAudio(Mp4Index idx) {
        Mp4Track a = idx.audio;
        long f = silenceFrameTicks(a);
        if (f <= 0) return null;
        int gops = idx.gopCount();
        long clipStart = idx.silentTicks(0, a.timescale);
        long clipEnd = idx.silentTicks(gops, a.timescale);
        if (clipEnd - clipStart > MAX_FILL_SECONDS * a.timescale) return null;
        long em = a.editMediaTime;
        int count = a.count;
        long half = f / 2;
        long[] start = new long[count + 64];
        int[] sample = new int[count + 64];
        int n = 0;
        boolean filled = false;
        // A stretched first sample decodes as one frame: its content is the frame just before sample 1.
        long s0 = a.time[0] - em;
        if (count > 1 && a.duration(0) > f + half) {
            s0 = a.time[1] - em - f;
            filled = true;
        }
        if (s0 - clipStart >= f && s0 <= clipEnd) {
            for (long k = (s0 - clipStart) / f; k >= 1; k--) {
                if (n == start.length) { start = java.util.Arrays.copyOf(start, n * 2); sample = java.util.Arrays.copyOf(sample, n * 2); }
                start[n] = s0 - k * f;
                sample[n++] = -1;
            }
            filled = true;
        }
        long cursor = s0;
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                long lag = a.time[i] - em - cursor;
                long nextLag = i + 1 < count ? a.time[i + 1] - em - (cursor + f) : lag;
                if (lag >= half && nextLag >= half) {
                    // Silence never runs past the picture: a corrupt jump can't make millions of frames.
                    long room = Math.max(0L, (clipEnd - cursor) / f);
                    for (long k = Math.min((Math.min(lag, nextLag) + half) / f, room); k >= 1; k--) {
                        if (n == start.length) { start = java.util.Arrays.copyOf(start, n * 2); sample = java.util.Arrays.copyOf(sample, n * 2); }
                        start[n] = cursor;
                        sample[n++] = -1;
                        cursor += f;
                    }
                    filled = true;
                }
            }
            if (n == start.length) { start = java.util.Arrays.copyOf(start, n * 2); sample = java.util.Arrays.copyOf(sample, n * 2); }
            start[n] = cursor;
            sample[n++] = i;
            cursor += f;
        }
        if (clipEnd - cursor >= f) {
            for (long k = (clipEnd - cursor) / f; k >= 1; k--) {
                if (n == start.length) { start = java.util.Arrays.copyOf(start, n * 2); sample = java.util.Arrays.copyOf(sample, n * 2); }
                start[n] = cursor;
                sample[n++] = -1;
                cursor += f;
            }
            filled = true;
        }
        if (!filled) return null;
        return new ClipAudio(f, java.util.Arrays.copyOf(start, n), java.util.Arrays.copyOf(sample, n), n);
    }

    /** GOP n's share of the clip's back-to-back audio, by entry start time; null when no fill is needed. */
    private static AudioRun filledRun(Mp4Index idx, int n) {
        ClipAudio ca = clipAudio(idx);
        if (ca == null) return null;
        Mp4Track a = idx.audio;
        int gops = idx.gopCount();
        int from = n == 0 ? 0 : firstAtOrAfter(ca, idx.silentTicks(n, a.timescale));
        int to = n + 1 >= gops ? ca.n : firstAtOrAfter(ca, idx.silentTicks(n + 1, a.timescale));
        RunBuilder b = new RunBuilder();
        for (int e = from; e < to; e++) {
            int smp = ca.sample[e];
            b.add(smp, ca.f, smp < 0 ? SILENT_FRAME.length : a.size[smp], ca.start[e] + a.editMediaTime);
        }
        if (b.n == 0) return new AudioRun(0, new int[0], new long[0], new int[0]);
        return b.build();
    }

    private static int firstAtOrAfter(ClipAudio ca, long t) {
        int lo = 0;
        int hi = ca.n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ca.start[mid] < t) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Silent 16 kHz frames on one 1024-tick grid from the clip's first picture; none overhang its end. */
    private static AudioRun silentRun(Mp4Index idx, int n) {
        int gops = idx.gopCount();
        long b0 = idx.silentTicks(0, SILENT_RATE);
        long f = SILENT_FRAME_TICKS;
        long first = ceilDiv(idx.silentTicks(n, SILENT_RATE) - b0, f);
        long end = n == gops - 1 ? (idx.silentTicks(gops, SILENT_RATE) - b0) / f
                : ceilDiv(idx.silentTicks(n + 1, SILENT_RATE) - b0, f);
        end = Math.min(end, first + MAX_FILL_SECONDS * SILENT_RATE / f);
        RunBuilder b = new RunBuilder();
        for (long i = first; i < end; i++) b.add(-1, f, SILENT_FRAME.length, b0 + i * f);
        if (b.n == 0) return new AudioRun(b0 + first * f, new int[0], new long[0], new int[0]);
        return b.build();
    }

    private static long ceilDiv(long v, long d) {
        return v <= 0 ? 0 : (v + d - 1) / d;
    }

    private static Plan skimPlan(Mp4Index idx, int n, int k) {
        idx.checkGop(n);
        checkK(k);
        Mp4Track v = idx.video;
        int key = idx.gopFirst[n];
        long start = idx.gops.skimTicks(n, k);
        long dur = idx.gops.skimTicks(n + 1, k) - start;
        long vBytes = v.size[key];
        int mdatHdr = mdatHeaderSize(vBytes);
        BoxWriter w = new BoxWriter(160);
        int moof = w.begin(MOOF);
        mfhd(w, n + 1);
        int vOff = traf(w, v, VIDEO_ID, key, key + 1, start, dur);
        w.end(moof);
        w.patch32(vOff, w.size() + mdatHdr);
        mdatHeader(w, vBytes, mdatHdr);
        return new Plan(w.toByteArray(), key, key + 1, 0, 0, 0, vBytes);
    }

    private static void write(Mp4Index idx, FileChannel src, Plan p, OutputStream out) throws IOException {
        // A changed file means the index (and the advertised length) is stale.
        if (src.size() != idx.fileLength()) throw new IOException("source changed since indexing");
        out.write(p.head);
        ByteBuffer buf = ByteBuffer.allocate(COPY_BUFFER);
        copySamples(idx.video, p.vFrom, p.vTo, src, out, buf);
        if (p.run != null) {
            writeRun(idx.audio, p.run, src, out, buf);
            return;
        }
        if (p.aTo > p.aFrom) copySamples(idx.audio, p.aFrom, p.aTo, src, out, buf);
        for (int i = 0; i < p.silentFrames; i++) out.write(SILENT_FRAME);
    }

    private static void writeRun(Mp4Track a, AudioRun run, FileChannel src, OutputStream out,
                                 ByteBuffer buf) throws IOException {
        int i = 0;
        while (i < run.count()) {
            int s = run.sample[i];
            if (s < 0) {
                out.write(SILENT_FRAME);
                i++;
                continue;
            }
            long start = a.offset[s];
            long end = start + a.size[s];
            i++;
            while (i < run.count() && run.sample[i] == s + 1 && a.offset[s + 1] == end) {
                s++;
                end += a.size[s];
                i++;
            }
            copyRange(src, start, end - start, out, buf);
        }
    }

    private static void copySamples(Mp4Track t, int from, int to, FileChannel src, OutputStream out,
                                    ByteBuffer buf) throws IOException {
        int i = from;
        while (i < to) {
            long start = t.offset[i];
            long end = start + t.size[i++];
            while (i < to && t.offset[i] == end) end += t.size[i++];
            copyRange(src, start, end - start, out, buf);
        }
    }

    private static void copyRange(FileChannel src, long pos, long len, OutputStream out, ByteBuffer buf)
            throws IOException {
        byte[] a = buf.array();
        while (len > 0) {
            buf.clear();
            if (len < buf.capacity()) buf.limit((int) len);
            int r = src.read(buf, pos);
            if (r <= 0) throw new EOFException("source truncated at " + pos);
            out.write(a, 0, r);
            pos += r;
            len -= r;
        }
    }

    private static long bytes(Mp4Track t, int from, int to) {
        long sum = 0;
        for (int i = from; i < to; i++) sum += t.size[i];
        return sum;
    }

    private static int mdatHeaderSize(long payload) {
        return payload + 8 > 0xffffffffL ? 16 : 8;
    }

    private static void mdatHeader(BoxWriter w, long payload, int hdr) {
        if (hdr == 8) {
            w.u32(payload + 8);
            w.u32(MDAT);
        } else {
            w.u32(1);
            w.u32(MDAT);
            w.u64(payload + 16);
        }
    }

    private static void mfhd(BoxWriter w, int seq) {
        int at = w.beginFull(MFHD, 0, 0);
        w.u32(seq);
        w.end(at);
    }

    /** Writes one traf and returns the position of its trun data_offset for patching. */
    private static int traf(BoxWriter w, Mp4Track t, int id, int from, int to, long decodeTime,
                            long fixedDuration) {
        int traf = w.begin(TRAF);
        int tfhd = w.beginFull(TFHD, 0, 0x020000);
        w.u32(id);
        w.end(tfhd);
        int tfdt = w.beginFull(TFDT, 1, 0);
        w.u64(decodeTime);
        w.end(tfdt);
        boolean cts = t.cts != null && fixedDuration < 0;
        boolean negative = false;
        if (cts) for (int i = from; i < to && !negative; i++) negative = t.cts[i] < 0;
        int flags = 0x000001 | 0x000100 | 0x000200 | 0x000400 | (cts ? 0x000800 : 0);
        int trun = w.beginFull(TRUN, negative ? 1 : 0, flags);
        w.u32(to - from);
        int dataOffset = w.size();
        w.u32(0);
        for (int i = from; i < to; i++) {
            w.u32(fixedDuration >= 0 ? fixedDuration : t.duration(i));
            w.u32(t.size[i]);
            w.u32(!t.video || i == from ? SYNC : NON_SYNC);
            if (cts) w.u32(t.cts[i]);
        }
        w.end(trun);
        w.end(traf);
        return dataOffset;
    }

    private static int runTraf(BoxWriter w, AudioRun run) {
        int traf = w.begin(TRAF);
        int tfhd = w.beginFull(TFHD, 0, 0x020000);
        w.u32(AUDIO_ID);
        w.end(tfhd);
        int tfdt = w.beginFull(TFDT, 1, 0);
        w.u64(run.decodeStart);
        w.end(tfdt);
        int trun = w.beginFull(TRUN, 0, 0x000001 | 0x000100 | 0x000200 | 0x000400);
        w.u32(run.count());
        int dataOffset = w.size();
        w.u32(0);
        for (int i = 0; i < run.count(); i++) {
            w.u32(run.duration[i]);
            w.u32(run.size[i]);
            w.u32(SYNC);
        }
        w.end(trun);
        w.end(traf);
        return dataOffset;
    }

    /** mp4a sample entry for the silent track: AudioSpecificConfig 0x14 0x08 = AAC-LC, 16 kHz, mono. */
    private static Mp4Track silentTrack() {
        BoxWriter w = new BoxWriter(128);
        int stsd = w.beginFull(Boxes.STSD, 0, 0);
        w.u32(1);
        int mp4a = w.begin(Boxes.MP4A);
        w.zeros(6);
        w.u16(1);
        w.zeros(8);
        w.u16(1);
        w.u16(16);
        w.zeros(4);
        w.u32(SILENT_RATE << 16);
        int esds = w.beginFull(Boxes.ESDS, 0, 0);
        w.u8(0x03);
        w.u8(25);
        w.u16(0);
        w.u8(0);
        w.u8(0x04);
        w.u8(17);
        w.u8(0x40);
        w.u8(0x15);
        w.zeros(11);
        w.u8(0x05);
        w.u8(2);
        w.u8(0x14);
        w.u8(0x08);
        w.u8(0x06);
        w.u8(1);
        w.u8(0x02);
        w.end(esds);
        w.end(mp4a);
        w.end(stsd);
        BoxWriter m = new BoxWriter(36);
        for (int v : IDENTITY) m.u32(v & 0xffffffffL);
        return new Mp4Track(false, SILENT_RATE, w.toByteArray(), "mp4a.40.2", new long[1], new int[0],
                new long[0], null, 0, 0, 3, 0, 0, 0x0100, m.toByteArray(), 0, 0, 0x55C4, 0, 0);
    }

    private static void trak(BoxWriter w, Mp4Track t, int id) {
        int trak = w.begin(Boxes.TRAK);
        int tkhd = w.beginFull(Boxes.TKHD, 0, t.tkhdFlags | 1);
        w.zeros(8);
        w.u32(id);
        w.zeros(8);
        w.zeros(8);
        w.u16(t.layer);
        w.u16(t.altGroup);
        w.u16(t.volume);
        w.u16(0);
        w.bytes(t.matrix);
        long tw = t.tkhdWidth;
        long th = t.tkhdHeight;
        if (t.video && (tw == 0 || th == 0)) {
            tw = (long) t.width << 16;
            th = (long) t.height << 16;
        }
        w.u32(tw);
        w.u32(th);
        w.end(tkhd);
        if (t.editMediaTime > 0) {
            int edts = w.begin(Boxes.EDTS);
            boolean wide = t.editMediaTime > Integer.MAX_VALUE;
            int elst = w.beginFull(Boxes.ELST, wide ? 1 : 0, 0);
            w.u32(1);
            if (wide) {
                w.u64(0);
                w.u64(t.editMediaTime);
            } else {
                w.u32(0);
                w.u32(t.editMediaTime);
            }
            w.u16(1);
            w.u16(0);
            w.end(elst);
            w.end(edts);
        }

        int mdia = w.begin(Boxes.MDIA);
        int mdhd = w.beginFull(Boxes.MDHD, 0, 0);
        w.zeros(8);
        w.u32(t.timescale);
        w.u32(0);
        w.u16(t.language);
        w.u16(0);
        w.end(mdhd);
        int hdlr = w.beginFull(Boxes.HDLR, 0, 0);
        w.u32(0);
        w.u32(t.video ? Boxes.VIDE : Boxes.SOUN);
        w.zeros(12);
        for (char c : (t.video ? "VideoHandler" : "SoundHandler").toCharArray()) w.u8(c);
        w.u8(0);
        w.end(hdlr);

        int minf = w.begin(Boxes.MINF);
        if (t.video) {
            int vmhd = w.beginFull(fourcc("vmhd"), 0, 1);
            w.zeros(8);
            w.end(vmhd);
        } else {
            int smhd = w.beginFull(fourcc("smhd"), 0, 0);
            w.zeros(4);
            w.end(smhd);
        }
        int dinf = w.begin(fourcc("dinf"));
        int dref = w.beginFull(fourcc("dref"), 0, 0);
        w.u32(1);
        int url = w.beginFull(fourcc("url "), 0, 1);
        w.end(url);
        w.end(dref);
        w.end(dinf);
        int stbl = w.begin(Boxes.STBL);
        w.bytes(t.stsd);
        emptyTable(w, Boxes.STTS);
        emptyTable(w, Boxes.STSC);
        int stsz = w.beginFull(Boxes.STSZ, 0, 0);
        w.zeros(8);
        w.end(stsz);
        emptyTable(w, Boxes.STCO);
        w.end(stbl);
        w.end(minf);
        w.end(mdia);
        w.end(trak);
    }

    private static void emptyTable(BoxWriter w, int type) {
        int at = w.beginFull(type, 0, 0);
        w.u32(0);
        w.end(at);
    }

    private static void trex(BoxWriter w, int id) {
        int at = w.beginFull(TREX, 0, 0);
        w.u32(id);
        w.u32(1);
        w.zeros(12);
        w.end(at);
    }
}
