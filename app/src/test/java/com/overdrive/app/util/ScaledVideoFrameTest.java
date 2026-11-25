package com.overdrive.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ScaledVideoFrameTest {
    @Test
    public void landscapeAndPortraitBoundsFollowDisplayRotation() {
        assertEquals(180, ScaledVideoFrame.targetHeight(320, "1920", "1080", "0"));
        assertEquals(180, ScaledVideoFrame.targetHeight(320, "1920", "1080", "180"));
        assertEquals(569, ScaledVideoFrame.targetHeight(320, "1920", "1080", "90"));
        assertEquals(569, ScaledVideoFrame.targetHeight(320, "1920", "1080", "270"));
        assertEquals(240, ScaledVideoFrame.targetHeight(320, "640", "480", null));
        assertEquals(320, ScaledVideoFrame.targetHeight(320, "480", "480", "0"));
    }

    @Test
    public void invalidOrUnusualGeometrySelectsLegacyPath() {
        assertEquals(0, ScaledVideoFrame.targetHeight(320, null, "1080", "0"));
        assertEquals(0, ScaledVideoFrame.targetHeight(320, "bad", "1080", "0"));
        assertEquals(0, ScaledVideoFrame.targetHeight(320, "1920", "0", "0"));
        assertEquals(0, ScaledVideoFrame.targetHeight(320, "1920", "1080", "45"));
        assertEquals(0, ScaledVideoFrame.targetHeight(320, "1920", "1080", "bad"));
        assertEquals(0, ScaledVideoFrame.targetHeight(320, "160", "90", "0"));
        assertEquals(0, ScaledVideoFrame.targetHeight(320, "320", "100000", "0"));
        assertEquals(0, ScaledVideoFrame.targetHeight(0, "1920", "1080", "0"));
    }
}
