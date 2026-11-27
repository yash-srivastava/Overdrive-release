package com.overdrive.app.server;

import org.junit.Test;

import static org.junit.Assert.*;

public class VideoStillFrameTest {
    @Test
    public void matchingAspectUsesThumbnailSizedDecode() {
        VideoStillFrame.DecodeSize size = VideoStillFrame.decodeSize(1920, 1080, 320, 180);
        assertNotNull(size);
        assertEquals(320, size.width);
        assertEquals(180, size.height);
        assertEquals(180, size.outputHeight);
    }

    @Test
    public void fixedThumbnailPreservesBothAxesBeforeFinalResize() {
        VideoStillFrame.DecodeSize size = VideoStillFrame.decodeSize(1280, 960, 320, 180);
        assertNotNull(size);
        assertEquals(320, size.width);
        assertEquals(240, size.height);
        assertEquals(180, size.outputHeight);
        size = VideoStillFrame.decodeSize(1920, 800, 320, 180);
        assertNotNull(size);
        assertEquals(432, size.width);
        assertEquals(180, size.height);
        assertEquals(180, size.outputHeight);
    }

    @Test
    public void timelinePreservesRoundedSourceAspect() {
        VideoStillFrame.DecodeSize size = VideoStillFrame.decodeSize(1920, 1080, 321, 0);
        assertNotNull(size);
        assertEquals(321, size.width);
        assertEquals(181, size.height);
        assertEquals(181, size.outputHeight);
        assertTrue(size.matches(321, 180));
        assertFalse(size.matches(1920, 1080));
        assertFalse(size.matches(180, 321));
    }

    @Test
    public void noScaledDecodeForInvalidMetadataOrUpsampling() {
        assertNull(VideoStillFrame.decodeSize(0, 1080, 320, 180));
        assertNull(VideoStillFrame.decodeSize(1920, -1, 320, 180));
        assertNull(VideoStillFrame.decodeSize(320, 180, 640, 0));
        assertNull(VideoStillFrame.decodeSize(640, 360, 320, 360));
    }
}
