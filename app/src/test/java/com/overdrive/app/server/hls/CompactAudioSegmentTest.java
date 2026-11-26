package com.overdrive.app.server.hls;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.Test;

public class CompactAudioSegmentTest {
    private static final long F = 1024;
    private static final byte[] SILENCE = {0, (byte) 0xc8, 0, (byte) 0x80, 0x23, (byte) 0x80};

    static Mp4Index index(File file, long[] videoTimes, long[] audioTimes) throws Exception {
        int n = videoTimes.length - 1;
        int[] sizes = new int[n], gops = new int[n + 1];
        long[] offsets = new long[n], us = new long[n + 1];
        Arrays.fill(sizes, 1);
        for (int i = 0; i <= n; i++) {
            gops[i] = i;
            us[i] = Mp4Index.ticksToUs(videoTimes[i], 16000);
            if (i < n) offsets[i] = i;
        }
        Mp4Track video = track(true, new byte[0], videoTimes, sizes, offsets);
        Mp4Track audio = null;
        int[] audioFirst = null;
        if (audioTimes != null) {
            Field field = Fmp4Writer.class.getDeclaredField("SILENT_TRACK");
            field.setAccessible(true);
            Mp4Track template = (Mp4Track) field.get(null);
            int count = audioTimes.length - 1;
            int[] audioSizes = new int[count];
            long[] audioOffsets = new long[count];
            for (int i = 0; i < count; i++) {
                audioSizes[i] = 1 + i % 4;
                audioOffsets[i] = 32 + 8L * i;
            }
            audio = track(false, template.stsd, audioTimes, audioSizes, audioOffsets);
            audioFirst = new int[n + 1];
            for (int i = 0; i <= n; i++) {
                int j = 0;
                while (j < count && audioTimes[j] < videoTimes[i]) j++;
                audioFirst[i] = j;
            }
            audioFirst[n] = count;
        }
        Constructor<Mp4Index> ctor = Mp4Index.class.getDeclaredConstructor(File.class, long.class,
                long.class, Mp4Track.class, Mp4Track.class, int[].class, long[].class, int[].class);
        ctor.setAccessible(true);
        return ctor.newInstance(file, file.length(), file.lastModified(), video, audio, gops, us, audioFirst);
    }

    private static Mp4Track track(boolean video, byte[] stsd, long[] time, int[] size, long[] offset) {
        return new Mp4Track(video, 16000, stsd, video ? "avc1" : "mp4a.40.2", time, size,
                offset, null, 0, 0, 0, 0, 0, 0, new byte[36], 0, 0, 0, 0, 0);
    }

    @Test public void longSilentSegmentBatchesPayloadAndPreservesEveryFrame() throws Exception {
        File file = File.createTempFile("hls-silence", ".bin");
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.write(42);
            int frames = 10_003;
            Mp4Index idx = index(file, new long[]{0, frames * F}, null);
            CountingOutput actual = new CountingOutput();
            Fmp4Writer.PreparedSegment plan = Fmp4Writer.prepareMediaSegment(idx, 0, true);
            plan.writeTo(raf.getChannel(), actual);
            byte[] segment = actual.toByteArray();
            assertEquals(plan.length(), segment.length);
            assertEquals(2 + (frames + 255) / 256, actual.writes);
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.write(42);
            for (int i = 0; i < frames; i++) expected.write(SILENCE);
            checkAudio(segment, 0, frames, null, expected.toByteArray());
        } finally {
            file.delete();
        }
    }

    @Test public void repairedTrackPreservesRealAndSilentSamplesAcrossGops() throws Exception {
        File file = File.createTempFile("hls-repair", ".bin");
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            byte[] source = new byte[128];
            for (int i = 0; i < source.length; i++) source[i] = (byte) (i + 1);
            raf.write(source);
            Mp4Index idx = index(file, new long[]{0, 302*F, 310*F, 600*F},
                    new long[]{300*F, 301*F, 302*F, 306*F, 307*F, 308*F});
            assertEquals(F, Fmp4Writer.silenceFrameTicks(idx.audio));
            ClipAudioTimeline timeline = Fmp4Writer.clipAudio(idx);
            assertNotNull(timeline);
            for (int g = 0; g < idx.gopCount(); g++) {
                int from = g == 0 ? 0 : timeline.firstAtOrAfter(idx.silentTicks(g, 16000));
                int to = g + 1 == idx.gopCount() ? timeline.n
                        : timeline.firstAtOrAfter(idx.silentTicks(g + 1, 16000));
                ByteArrayOutputStream expected = new ByteArrayOutputStream();
                expected.write(source[g]);
                int[] sizes = new int[to - from];
                for (int e = from; e < to; e++) {
                    int sample = timeline.sampleAt(e, timeline.runAt(e));
                    if (sample < 0) {
                        expected.write(SILENCE);
                        sizes[e - from] = SILENCE.length;
                    } else {
                        int size = idx.audio.size[sample];
                        expected.write(source, (int) idx.audio.offset[sample], size);
                        sizes[e - from] = size;
                    }
                }
                ByteArrayOutputStream actual = new ByteArrayOutputStream();
                Fmp4Writer.PreparedSegment plan = Fmp4Writer.prepareMediaSegment(idx, g, false);
                plan.writeTo(raf.getChannel(), actual);
                assertEquals(plan.length(), actual.size());
                checkAudio(actual.toByteArray(), timeline.startAt(from), to - from, sizes,
                        expected.toByteArray());
            }
        } finally {
            file.delete();
        }
    }

    private static void checkAudio(byte[] segment, long start, int frames, int[] sizes,
                                   byte[] payload) throws Exception {
        int moofEnd = (int) Boxes.u32(segment, 0);
        assertEquals(Boxes.fourcc("moof"), Boxes.s32(segment, 4));
        Boxes.Iter children = new Boxes.Iter(segment, 8, moofEnd);
        int tracks = 0;
        while (children.next()) {
            if (children.type != Boxes.fourcc("traf") || ++tracks != 2) continue;
            Boxes.Iter boxes = new Boxes.Iter(segment, children.payload, children.boxEnd);
            boolean tfdt = false, trun = false;
            while (boxes.next()) {
                if (boxes.type == Boxes.fourcc("tfdt")) {
                    assertEquals(start, Boxes.u64(segment, boxes.payload + 4));
                    tfdt = true;
                } else if (boxes.type == Boxes.fourcc("trun")) {
                    assertEquals(frames, Boxes.u32(segment, boxes.payload + 4));
                    assertEquals(moofEnd + 9, Boxes.u32(segment, boxes.payload + 8));
                    for (int i = 0; i < frames; i++) {
                        int p = boxes.payload + 12 + i * 12;
                        assertEquals(F, Boxes.u32(segment, p));
                        assertEquals(sizes == null ? SILENCE.length : sizes[i], Boxes.u32(segment, p + 4));
                        assertEquals(0x02000000, Boxes.u32(segment, p + 8));
                    }
                    trun = true;
                }
            }
            assertTrue(tfdt && trun);
        }
        assertEquals(2, tracks);
        assertEquals(Boxes.fourcc("mdat"), Boxes.s32(segment, moofEnd + 4));
        assertEquals(payload.length + 8, Boxes.u32(segment, moofEnd));
        assertArrayEquals(payload, Arrays.copyOfRange(segment, moofEnd + 8, segment.length));
    }

    private static final class CountingOutput extends ByteArrayOutputStream {
        int writes;
        @Override public synchronized void write(byte[] b, int off, int len) {
            writes++;
            super.write(b, off, len);
        }
    }
}
