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
import java.nio.channels.FileChannel;

public class PreparedSegmentTest {
    // Synthetic index: test the writer independently of the progressive MP4 parser.
    private static Mp4Index index(File file, boolean audio) throws Exception {
        Mp4Track video = new Mp4Track(true, 1000, new byte[0], "avc1",
                new long[]{0, 500, 1000, 1500, 2000}, new int[]{4, 5, 6, 7},
                new long[]{0, 8, 16, 24}, new int[]{0, 20, 0, -20},
                0, 0, 0, 0, 0, 0, new byte[36], 0, 0, 0, 0, 0);
        Mp4Track sound = audio ? new Mp4Track(false, 1000, new byte[0], "mp4a.40.2",
                new long[]{0, 500, 1000, 1500, 2000}, new int[]{2, 2, 2, 2},
                new long[]{4, 13, 22, 31}, null,
                0, 0, 0, 0, 0, 0, new byte[36], 0, 0, 0, 0, 0) : null;
        Constructor<Mp4Index> constructor = Mp4Index.class.getDeclaredConstructor(
                File.class, long.class, long.class, Mp4Track.class, Mp4Track.class,
                int[].class, long[].class, int[].class);
        constructor.setAccessible(true);
        return constructor.newInstance(file, file.length(), file.lastModified(), video, sound,
                new int[]{0, 2, 4}, new long[]{0, 1_000_000, 2_000_000},
                audio ? new int[]{0, 2, 4} : null);
    }

    @Test public void preparedOutputMatchesSeparateLengthAndWriteCalls() throws Exception {
        File file = File.createTempFile("prepared-segment", ".bin");
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            for (int i = 0; i < 64; i++) raf.write(i);
            FileChannel channel = raf.getChannel();
            for (boolean audio : new boolean[]{false, true}) {
                Mp4Index idx = index(file, audio);
                for (int gop = 0; gop < idx.gopCount(); gop++) {
                    for (boolean silent : new boolean[]{false, true}) {
                        ByteArrayOutputStream expected = new ByteArrayOutputStream();
                        Fmp4Writer.writeMediaSegment(idx, channel, gop, silent, expected);
                        compare(Fmp4Writer.prepareMediaSegment(idx, gop, silent), channel,
                                Fmp4Writer.mediaSegmentLength(idx, gop, silent), expected);
                    }
                    for (int k : new int[]{1, 4, 8, 64}) {
                        ByteArrayOutputStream expected = new ByteArrayOutputStream();
                        Fmp4Writer.writeSkimSegment(idx, channel, gop, k, expected);
                        compare(Fmp4Writer.prepareSkimSegment(idx, gop, k), channel,
                                Fmp4Writer.skimSegmentLength(idx, gop, k), expected);
                    }
                }
            }
        } finally {
            file.delete();
        }
    }

    private static void compare(Fmp4Writer.PreparedSegment segment, FileChannel channel,
                                long length, ByteArrayOutputStream expected) throws IOException {
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        assertEquals(length, segment.length());
        segment.writeTo(channel, actual);
        assertEquals(length, actual.size());
        assertArrayEquals(expected.toByteArray(), actual.toByteArray());
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
