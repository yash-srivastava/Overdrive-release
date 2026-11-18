package com.overdrive.app.server.hls;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Bound searches must retain the playlist's strict end and start comparisons. */
public class HlsPlaylistsGopSearchTest {
    @Test
    public void skipsOnlyGopsEndingAtOrBeforeTheCutoff() {
        long[] us = {0, 2_000_000, 2_000_000, 4_000_000, 6_000_000};
        long clipUs = 100_000_000;
        assertEquals(0, HlsPlaylists.firstGopEndingAfter(us, clipUs, clipUs - 1));
        assertEquals(0, HlsPlaylists.firstGopEndingAfter(us, clipUs, clipUs));
        assertEquals(2, HlsPlaylists.firstGopEndingAfter(us, clipUs, clipUs + 2_000_000));
        assertEquals(3, HlsPlaylists.firstGopEndingAfter(us, clipUs, clipUs + 4_000_000));
        assertEquals(4, HlsPlaylists.firstGopEndingAfter(us, clipUs, clipUs + 6_000_000));
    }

    @Test
    public void stopsBeforeGopsStartingAtTheWindowEnd() {
        long[] us = {0, 2_000_000, 2_000_000, 4_000_000, 6_000_000};
        long clipUs = 100_000_000;
        assertEquals(0, HlsPlaylists.firstGopStartingAtOrAfter(us, clipUs, clipUs));
        assertEquals(1, HlsPlaylists.firstGopStartingAtOrAfter(us, clipUs, clipUs + 2_000_000));
        assertEquals(3, HlsPlaylists.firstGopStartingAtOrAfter(us, clipUs, clipUs + 4_000_000));
        assertEquals(4, HlsPlaylists.firstGopStartingAtOrAfter(us, clipUs, clipUs + 8_000_000));
    }
}
