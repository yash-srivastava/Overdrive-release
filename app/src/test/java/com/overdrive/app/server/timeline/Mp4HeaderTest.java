package com.overdrive.app.server.timeline;

import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Mp4HeaderTest {
    private static byte[] join(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        ByteBuffer out = ByteBuffer.allocate(length);
        for (byte[] part : parts) out.put(part);
        return out.array();
    }

    private static byte[] box(String type, byte[]... parts) {
        byte[] body = join(parts);
        return ByteBuffer.allocate(8 + body.length).putInt(8 + body.length)
                .put(type.getBytes(StandardCharsets.US_ASCII)).put(body).array();
    }

    private static byte[] extendedBox(String type, byte[] body) {
        return ByteBuffer.allocate(16 + body.length).putInt(1)
                .put(type.getBytes(StandardCharsets.US_ASCII)).putLong(16L + body.length)
                .put(body).array();
    }

    private static byte[] duration(int version, int timescale, long ticks) {
        ByteBuffer b = ByteBuffer.allocate(version == 1 ? 32 : 20);
        b.putInt(version << 24);
        b.position(version == 1 ? 20 : 12);
        b.putInt(timescale);
        if (version == 1) b.putLong(ticks); else b.putInt((int) ticks);
        return b.array();
    }

    private static byte[] track(byte[] header, byte[]... extra) {
        return box("trak", box("mdia", box("mdhd", header), join(extra)));
    }

    private static final class Source implements Mp4Header.Reader {
        final byte[] file;
        final int maxRead;
        long bytesRead;
        int largestRequest;

        Source(byte[] file, int maxRead) {
            this.file = file;
            this.maxRead = maxRead;
        }

        @Override public int read(ByteBuffer dst, long position) {
            largestRequest = Math.max(largestRequest, dst.remaining());
            if (position >= file.length) return -1;
            int n = Math.min(Math.min(maxRead, dst.remaining()), file.length - (int) position);
            dst.put(file, (int) position, n);
            bytesRead += n;
            return n;
        }
    }

    private static long read(byte[] moov, int maxRead) throws IOException {
        byte[] file = join(box("ftyp", new byte[8]), box("moov", moov));
        return Mp4Header.durationMs(new Source(file, maxRead), file.length);
    }

    @Test public void skipsLargeSampleTablesWithoutLoadingTheirBodies() throws IOException {
        byte[] tables = box("minf", box("stbl", box("stsz", new byte[2 * 1024 * 1024]),
                box("stco", new byte[2 * 1024 * 1024])));
        byte[] moov = join(box("mvhd", duration(0, 1000, 123456)),
                track(duration(0, 1000, 120000), tables));
        byte[] file = join(box("ftyp", new byte[8]), box("moov", moov));
        Source source = new Source(file, Integer.MAX_VALUE);
        assertEquals(123456, Mp4Header.durationMs(source, file.length));
        assertTrue(source.largestRequest <= Mp4Header.WINDOW_BYTES);
        // A few bounded header windows, not the 4 MiB sample-table bodies.
        assertTrue(source.bytesRead < 32 * 1024);
    }

    @Test public void readsMetadataWindowsOnlyOnceDuringForwardWalk() throws IOException {
        byte[] tables = box("minf", box("stbl", box("stsz", new byte[1024 * 1024])));
        byte[] moov = join(box("mvhd", duration(0, 1000, 5000)),
                track(duration(0, 1000, 3000), tables),
                track(duration(0, 1000, 4000), tables), box("free", new byte[8192]));
        byte[] file = box("moov", moov);
        Source source = new Source(file, Integer.MAX_VALUE);
        assertEquals(5000, Mp4Header.durationMs(source, file.length));
        // Initial header plus three forward windows; no rewind to collect durations.
        assertEquals(16 + 3 * Mp4Header.WINDOW_BYTES, source.bytesRead);
    }

    @Test public void onlyCollectsDurationFromDirectFirstTrackChildren() throws IOException {
        byte[] nested = box("minf", box("mdia", box("mdhd", duration(0, 1000, 9000))));
        byte[] first = box("mdia", box("minf", box("mdhd", duration(0, 1000, 8000))),
                box("mdhd", duration(0, 0, 1)), box("mdhd", duration(0, 1000, 7000)));
        byte[] second = box("mdia", box("mdhd", duration(0, 1000, 6000)));
        byte[] moov = join(box("trak", nested, first, second),
                box("trak", box("mdia"), second), track(duration(0, 1000, 2000)));
        assertEquals(2000, read(moov, 19));
        assertEquals(2000, Mp4Header.moovDurationMs(moov));
    }

    @Test public void validatesIgnoredDuplicateMediaContainer() throws IOException {
        byte[] good = box("mdia", box("mdhd", duration(0, 1000, 2000)));
        byte[] invalid = ByteBuffer.allocate(8).putInt(100).putInt(0x6d646864).array();
        byte[] moov = join(box("mvhd", duration(0, 1000, 5000)),
                box("trak", good, box("mdia", invalid)));
        assertEquals(-1, read(moov, 13));
    }

    @Test public void handlesCrossWindowExtendedHeaderAndPartialReads() throws IOException {
        byte[] moov = join(box("free", new byte[Mp4Header.WINDOW_BYTES - 12]),
                extendedBox("mvhd", duration(1, 90000, 90000L * 123)));
        assertEquals(123000, read(moov, 37));
        assertEquals(123000, Mp4Header.moovDurationMs(moov));
    }

    @Test public void usesLongestTrackWhenMovieDurationUnknown() throws IOException {
        byte[] moov = join(box("mvhd", duration(0, 1000, 0xffffffffL)),
                track(duration(0, 1000, 700)), track(duration(1, 48000, 96000)));
        assertEquals(2000, read(moov, Integer.MAX_VALUE));
    }

    @Test public void keepsLastMovieHeaderAndFirstTrackChildren() throws IOException {
        byte[] first = box("mdia", box("mdhd", duration(0, 1000, 1000)),
                box("mdhd", duration(0, 1000, 9000)));
        byte[] second = box("mdia", box("mdhd", duration(0, 1000, 8000)));
        byte[] moov = join(box("mvhd", duration(0, 1000, 5000)),
                box("trak", first, second), box("mvhd", duration(0, 0, 1)));
        assertEquals(1000, read(moov, Integer.MAX_VALUE));
    }

    @Test public void rejectsMalformedNestedBoxesAfterValidMovieHeader() throws IOException {
        byte[] overrun = ByteBuffer.allocate(8).putInt(100).putInt(0x7374737a).array();
        byte[] moov = join(box("mvhd", duration(0, 1000, 5000)),
                box("trak", box("mdia", box("minf", box("stbl", overrun)))));
        assertEquals(-1, read(moov, Integer.MAX_VALUE));
    }

    @Test public void rejectsTooManyBoxesAndTooDeepContainers() throws IOException {
        ByteBuffer boxes = ByteBuffer.allocate(20_001 * 8);
        for (int i = 0; i < 20_001; i++) boxes.putInt(8).putInt(0x66726565);
        assertEquals(-1, read(boxes.array(), Integer.MAX_VALUE));
        byte[] nested = box("free");
        for (int i = 0; i < 10; i++) nested = box("trak", nested);
        assertEquals(-1, read(nested, Integer.MAX_VALUE));
    }

    @Test public void rejectsTruncatedHeaderRead() throws IOException {
        byte[] file = box("moov", box("mvhd", duration(0, 1000, 2000)));
        byte[] truncated = java.util.Arrays.copyOf(file, file.length - 1);
        try {
            Mp4Header.durationMs(new Source(truncated, 5), file.length);
            fail("short read must fail");
        } catch (EOFException expected) {
            // The public File entry point maps this to -1.
        }
    }

    @Test public void acceptsSizeZeroLeafAndRejectsInvalidFourcc() throws IOException {
        byte[] tail = ByteBuffer.allocate(12).putInt(0).putInt(0x66726565).putInt(42).array();
        byte[] header = box("mvhd", duration(0, 1000, 5000));
        assertEquals(5000, read(join(header, tail), Integer.MAX_VALUE));
        tail[4] = 0;
        assertEquals(-1, read(join(header, tail), Integer.MAX_VALUE));
    }
}
