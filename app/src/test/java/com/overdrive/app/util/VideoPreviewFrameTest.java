package com.overdrive.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VideoPreviewFrameTest {
    @Test
    public void widescreenThumbnailNeedsNoIntermediateResize() {
        check(1920, 1080, 320, 180, 320, 180, 180);
        check(3840, 2160, 320, 180, 320, 180, 180);
    }

    @Test
    public void fixedThumbnailCoversBothAxesBeforeStretching() {
        check(1280, 960, 320, 180, 320, 240, 180);
        check(2560, 1080, 320, 180, 427, 180, 180);
        check(1080, 1920, 320, 180, 320, 569, 180);
    }

    @Test
    public void scrubKeepsOriginalRoundedAspectHeight() {
        check(1920, 1080, 640, 0, 640, 360, 360);
        check(1280, 960, 319, 0, 319, 240, 239);
        check(1080, 1920, 320, 0, 320, 569, 569);
    }

    @Test
    public void tinyOrUnreasonableGeometryUsesFullFrameFallback() {
        assertNull(VideoPreviewFrame.bounds(0, 1080, 320, 180));
        assertNull(VideoPreviewFrame.bounds(1920, -1, 320, 180));
        assertNull(VideoPreviewFrame.bounds(1920, 1080, 0, 180));
        assertNull(VideoPreviewFrame.bounds(1920, 1080, 320, -1));
        assertNull(VideoPreviewFrame.bounds(320, 180, 320, 180));
        assertNull(VideoPreviewFrame.bounds(160, 90, 320, 180));
        assertNull(VideoPreviewFrame.bounds(1, Integer.MAX_VALUE, 320, 0));
        assertNull(VideoPreviewFrame.bounds(Integer.MAX_VALUE, 1, 320, 180));
        assertNull(VideoPreviewFrame.bounds(8192, 8192, 4097, 0));
    }

    @Test
    public void unexpectedDecoderDimensionsAreRejected() {
        VideoPreviewFrame.Bounds b = VideoPreviewFrame.bounds(1920, 1080, 320, 180);
        assertNotNull(b);
        assertTrue(b.matches(320, 180));
        assertTrue(b.matches(319, 180));
        assertTrue(b.matches(320, 179));
        assertFalse(b.matches(320, 240));
        assertFalse(b.matches(180, 320));
        assertFalse(b.matches(1920, 1080));
        assertFalse(b.matches(0, 180));
    }

    private static void check(int sourceWidth, int sourceHeight, int width, int height,
                              int reqWidth, int reqHeight, int outputHeight) {
        VideoPreviewFrame.Bounds b = VideoPreviewFrame.bounds(sourceWidth, sourceHeight, width, height);
        assertNotNull(b);
        assertEquals(reqWidth, b.width);
        assertEquals(reqHeight, b.height);
        assertEquals(outputHeight, b.outputHeight);
    }
}
