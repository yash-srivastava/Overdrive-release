package com.overdrive.app.server.hls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

import org.junit.Test;

public class HlsPlaylistSerializationTest {
    private static final String HEADER = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:";
    private static final String REST = "\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n"
            + "#EXT-X-INDEPENDENT-SEGMENTS\n";

    @Test
    public void emptyPlaylistRetainsHeaderAndTrailer() {
        assertEquals(HEADER + "1" + REST + "#EXT-X-ENDLIST\n",
                HlsPlaylists.timelinePlaylist(Collections.emptyList(), id -> null, null));
    }

    @Test
    public void timelineKeepsExactHeaderDurationsAndAudioPadding() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            GopTable.put("serialization-video/fp", new GopTable(
                    new long[]{0, 1_005_000}, new long[]{0, 1005}, 1000, false));
            GopTable.put("serialization-audio/fp", new GopTable(
                    new long[]{0, 12_010_000}, new long[]{0, 12010}, 1000, true));
            HlsPlaylists.Entry video = new HlsPlaylists.Entry("serialization-video", "fp", 0, 0, 1005);
            HlsPlaylists.Entry audio = new HlsPlaylists.Entry("serialization-audio", "fp", 1005, 1005, 13015);
            assertEquals(HEADER + "13" + REST
                            + "#EXT-X-MAP:URI=\"/hls/id/serialization-video/fp/init.mp4?sa=1\"\n"
                            + "#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:00:00.000Z\n"
                            + "#EXTINF:1.005,\n/hls/id/serialization-video/fp/s0.m4s?sa=1\n"
                            + "#EXT-X-DISCONTINUITY\n"
                            + "#EXT-X-MAP:URI=\"/hls/id/serialization-audio/fp/init.mp4\"\n"
                            + "#EXT-X-PROGRAM-DATE-TIME:1970-01-01T00:00:01.005Z\n"
                            + "#EXTINF:12.010,\n/hls/id/serialization-audio/fp/s0.m4s\n"
                            + "#EXT-X-ENDLIST\n",
                    HlsPlaylists.timelinePlaylist(Arrays.asList(video, audio), id -> null, null));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    public void timestampPaddingHandlesOffsetsAndNegativeEpochs() {
        assertIso("1969-12-31T23:59:59.999Z", -1, "UTC");
        assertIso("1970-01-01T05:45:00.007+05:45", 7, "GMT+05:45");
        assertIso("1969-12-31T20:30:00.042-03:30", 42, "GMT-03:30");
        assertIso("2000-02-29T00:00:00.000Z", 951782400000L, "UTC");
        assertIso("0001-01-01T00:00:00.000Z", -62135596800000L, "UTC");
        assertIso("10000-01-01T00:00:00.000Z", 253402300800000L, "UTC");
    }

    @Test
    public void paddingPreservesMinimumWidthAndExistingContent() throws Exception {
        Method pad = HlsPlaylists.class.getDeclaredMethod("pad", StringBuilder.class,
                long.class, int.class);
        pad.setAccessible(true);
        long[] values = {Long.MIN_VALUE, -123, -1, 0, 1, 9, 10, 99, 100, 999,
                1000, 9999, 10000, Long.MAX_VALUE};
        for (long value : values) {
            String digits = Long.toString(value);
            for (int width = -1; width <= 8; width++) {
                StringBuilder expected = new StringBuilder("prefix:");
                for (int i = digits.length(); i < width; i++) expected.append('0');
                expected.append(digits);
                StringBuilder actual = new StringBuilder("prefix:");
                assertSame(actual, pad.invoke(null, actual, value, width));
                assertEquals("value=" + value + ", width=" + width,
                        expected.toString(), actual.toString());
            }
        }
    }

    private static void assertIso(String expected, long epochMs, String zone) {
        StringBuilder result = new StringBuilder();
        HlsPlaylists.appendIso(result, epochMs, TimeZone.getTimeZone(zone));
        assertEquals(expected, result.toString());
    }
}
