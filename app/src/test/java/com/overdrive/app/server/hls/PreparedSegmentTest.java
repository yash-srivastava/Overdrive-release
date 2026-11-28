package com.overdrive.app.server.hls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class PreparedSegmentTest {
    // Synthetic index: test the writer independently of the progressive MP4 parser.
    private static Mp4Index index(File file, boolean audio) throws Exception {
        return index(file, audio, false);
    }

    private static Mp4Index index(File file, boolean audio, boolean repair) throws Exception {
        Mp4Track video = new Mp4Track(true, 1000, new byte[0], "avc1",
                new long[]{0, 500, 1000, 1500, 2000}, new int[]{4, 5, 6, 7},
                new long[]{0, 8, 16, 24}, new int[]{0, 20, 0, -20},
                0, 0, 0, 0, 0, 0, new byte[36], 0, 0, 0, 0, 0);
        Mp4Track sound = audio ? new Mp4Track(false, repair ? 16_000 : 1000,
                repair ? monoAacStsd() : new byte[0], "mp4a.40.2",
                repair ? new long[]{1024, 2048, 3072, 4096, 5120}
                        : new long[]{0, 500, 1000, 1500, 2000}, new int[]{2, 2, 2, 2},
                new long[]{4, 13, 22, 31}, null,
                0, 0, 0, 0, 0, 0, new byte[36], 0, 0, 0, 0, 0) : null;
        Constructor<Mp4Index> constructor = Mp4Index.class.getDeclaredConstructor(
                long.class, long.class, Mp4Track.class, Mp4Track.class,
                int[].class, long[].class, int[].class);
        constructor.setAccessible(true);
        return constructor.newInstance(file.length(), file.lastModified(), video, sound,
                new int[]{0, 2, 4}, new long[]{0, 1_000_000, 2_000_000},
                audio ? (repair ? new int[]{0, 4, 4} : new int[]{0, 2, 4}) : null);
    }

    // Minimal stsd/esds fields for the writer's AAC-LC mono configuration probe.
    private static byte[] monoAacStsd() {
        byte[] stsd = new byte[64];
        ByteBuffer b = ByteBuffer.wrap(stsd);
        b.putInt(20, 0x6d703461); // mp4a
        b.putInt(24, 0x65736473); // esds
        stsd[32] = 0x03;
        stsd[33] = 25;
        stsd[37] = 0x04;
        stsd[38] = 17;
        stsd[52] = 0x05;
        stsd[53] = 2;
        stsd[54] = 0x14;
        stsd[55] = 0x08; // AAC-LC, 16 kHz, mono, 1024 samples
        return stsd;
    }

    @Test public void preparedOutputHasExpectedFragmentHeadersAndSampleBytes() throws Exception {
        File file = File.createTempFile("prepared-segment", ".bin");
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            for (int i = 0; i < 64; i++) raf.write(i);
            FileChannel channel = raf.getChannel();
            for (boolean audio : new boolean[]{false, true}) {
                Mp4Index idx = index(file, audio);
                assertEquals(2_000_000L, idx.durationUs());
                for (int gop = 0; gop < idx.gopCount(); gop++) {
                    int[] videoSizes = gop == 0 ? new int[]{4, 5} : new int[]{6, 7};
                    int[] cts = {0, gop == 0 ? 20 : -20};
                    byte[] video = gop == 0 ? new byte[]{0, 1, 2, 3, 8, 9, 10, 11, 12}
                            : new byte[]{16, 17, 18, 19, 20, 21, 24, 25, 26, 27, 28, 29, 30};
                    for (boolean silent : new boolean[]{false, true}) {
                        int[] audioSizes = new int[0];
                        byte[] sound = new byte[0];
                        long audioStart = 0;
                        long audioDuration = 500;
                        if (audio) {
                            audioSizes = new int[]{2, 2};
                            sound = gop == 0 ? new byte[]{4, 5, 13, 14} : new byte[]{22, 23, 31, 32};
                            audioStart = gop * 1000L;
                        } else if (silent) {
                            audioSizes = new int[gop == 0 ? 16 : 15];
                            Arrays.fill(audioSizes, 6);
                            sound = silence(audioSizes.length);
                            audioStart = gop == 0 ? 0 : 16_384;
                            audioDuration = 1024;
                        }
                        assertFragment(Fmp4Writer.prepareMediaSegment(idx, gop, silent), channel,
                                gop, gop * 1000L, 500, videoSizes, cts, video,
                                audioStart, audioDuration, audioSizes, sound);
                    }
                    for (int k : new int[]{1, 4, 8, 64}) {
                        long start = gop * 1000L / k;
                        long duration = (gop + 1) * 1000L / k - start;
                        assertFragment(Fmp4Writer.prepareSkimSegment(idx, gop, k), channel,
                                gop, start, duration, new int[]{videoSizes[0]}, null,
                                Arrays.copyOf(video, videoSizes[0]), 0, 0, new int[0], new byte[0]);
                    }
                }
            }
        } finally {
            file.delete();
        }
    }

    @Test public void repairedAudioKeepsRealSamplesBetweenSilentFrames() throws Exception {
        File file = File.createTempFile("prepared-repair", ".bin");
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            for (int i = 0; i < 64; i++) raf.write(i);
            Mp4Index idx = index(file, true, true);
            int[] audioSizes = new int[16];
            Arrays.fill(audioSizes, 6);
            Arrays.fill(audioSizes, 1, 5, 2);
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            audio.write(silence(1));
            audio.write(new byte[]{4, 5, 13, 14, 22, 23, 31, 32});
            audio.write(silence(11));
            assertFragment(Fmp4Writer.prepareMediaSegment(idx, 0, false), raf.getChannel(),
                    0, 0, 500, new int[]{4, 5}, new int[]{0, 20},
                    new byte[]{0, 1, 2, 3, 8, 9, 10, 11, 12},
                    0, 1024, audioSizes, audio.toByteArray());
            int[] tailSizes = new int[15];
            Arrays.fill(tailSizes, 6);
            assertFragment(Fmp4Writer.prepareMediaSegment(idx, 1, false), raf.getChannel(),
                    1, 1000, 500, new int[]{6, 7}, new int[]{0, -20},
                    new byte[]{16, 17, 18, 19, 20, 21, 24, 25, 26, 27, 28, 29, 30},
                    16_384, 1024, tailSizes, silence(15));
        } finally {
            file.delete();
        }
    }

    private static byte[] silence(int count) {
        byte[] frame = {0, (byte) 0xC8, 0, (byte) 0x80, 0x23, (byte) 0x80};
        byte[] data = new byte[count * frame.length];
        for (int i = 0; i < count; i++) System.arraycopy(frame, 0, data, i * frame.length, frame.length);
        return data;
    }

    private static void assertFragment(Fmp4Writer.PreparedSegment segment, FileChannel channel,
                                       int gop, long videoStart, long videoDuration, int[] videoSizes,
                                       int[] cts, byte[] video, long audioStart, long audioDuration,
                                       int[] audioSizes, byte[] audio) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        segment.writeTo(channel, out);
        int moofSize = 24 + 64 + videoSizes.length * (cts == null ? 12 : 16)
                + (audioSizes.length == 0 ? 0 : 64 + audioSizes.length * 12);
        int mdatSize = 8 + video.length + audio.length;
        assertEquals(moofSize + mdatSize, segment.length());
        assertEquals(segment.length(), out.size());
        ByteBuffer b = ByteBuffer.wrap(out.toByteArray());
        assertBox(b, moofSize, "moof");
        assertBox(b, 16, "mfhd");
        assertEquals(0, b.getInt());
        assertEquals(gop + 1, b.getInt());
        assertTrack(b, 1, videoStart, videoDuration, videoSizes, cts, moofSize + 8);
        if (audioSizes.length > 0) {
            assertTrack(b, 2, audioStart, audioDuration, audioSizes, null, moofSize + 8 + video.length);
        }
        assertEquals(moofSize, b.position());
        assertBox(b, mdatSize, "mdat");
        byte[] payload = new byte[video.length];
        b.get(payload);
        assertArrayEquals(video, payload);
        payload = new byte[audio.length];
        b.get(payload);
        assertArrayEquals(audio, payload);
        assertEquals(0, b.remaining());
    }

    private static void assertTrack(ByteBuffer b, int id, long start, long duration,
                                    int[] sizes, int[] cts, int offset) {
        int entries = sizes.length * (cts == null ? 12 : 16);
        assertBox(b, 64 + entries, "traf");
        assertBox(b, 16, "tfhd");
        assertEquals(0x020000, b.getInt());
        assertEquals(id, b.getInt());
        assertBox(b, 20, "tfdt");
        assertEquals(0x01000000, b.getInt());
        assertEquals(start, b.getLong());
        assertBox(b, 20 + entries, "trun");
        int flags = cts == null ? 0x701 : 0xf01;
        if (cts != null && cts[1] < 0) flags |= 0x01000000;
        assertEquals(flags, b.getInt());
        assertEquals(sizes.length, b.getInt());
        assertEquals(offset, b.getInt());
        for (int i = 0; i < sizes.length; i++) {
            assertEquals(duration, b.getInt());
            assertEquals(sizes[i], b.getInt());
            assertEquals(id == 1 && i > 0 ? 0x01010000 : 0x02000000, b.getInt());
            if (cts != null) assertEquals(cts[i], b.getInt());
        }
    }

    private static void assertBox(ByteBuffer b, int size, String type) {
        assertEquals(size, b.getInt());
        for (int i = 0; i < 4; i++) assertEquals(type.charAt(i), b.get());
    }

    @Test public void staleSourceFailsBeforeWritingAnyBytes() throws Exception {
        File file = File.createTempFile("prepared-stale", ".bin");
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(64);
            Fmp4Writer.PreparedSegment segment = Fmp4Writer.prepareMediaSegment(index(file, false), 0, true);
            raf.setLength(63);
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            try {
                segment.writeTo(raf.getChannel(), actual);
                fail("Expected stale source rejection");
            } catch (IOException expected) {
                assertEquals(0, actual.size());
            }
        } finally {
            file.delete();
        }
    }
}
