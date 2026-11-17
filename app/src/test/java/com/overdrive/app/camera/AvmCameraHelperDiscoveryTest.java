package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AvmCameraHelperDiscoveryTest {
    @Test public void brokenFirstTagDoesNotHideNextValidHalMapping() {
        int result = AvmCameraHelper.findPanoIdForTags(tag -> {
            if ("pano_h".equals(tag)) throw new IllegalStateException("vendor tag unavailable");
            if ("pano_l".equals(tag)) return 0;
            return -1;
        });
        assertEquals(0, result);
    }

    @Test public void invalidHalIdsAreNotOpened() {
        assertEquals(-1, AvmCameraHelper.findPanoIdForTags(tag -> 99));
    }

    @Test public void absentHalTagFallsBackWithoutInventingId() {
        assertEquals(-1, AvmCameraHelper.findPanoIdForTags(tag -> null));
    }

    @Test public void dilink4PreviewIndexTracksActualTagGroup() {
        AvmCameraHelper.PanoCameraSelection selection = AvmCameraHelper.findDi4Selection(tag -> {
            if ("pano_h".equals(tag)) throw new IllegalStateException("not supported");
            if ("apa".equals(tag)) return 3;
            return -1;
        });
        assertEquals(3, selection.getCameraId());
        assertEquals(1, selection.getPreviewIndex());
        assertEquals("apa", selection.getTag());
    }

    @Test public void invalidDi4TagDoesNotSuppressFallback() {
        AvmCameraHelper.PanoCameraSelection selection = AvmCameraHelper.findDi4Selection(tag -> {
            if ("pano_h".equals(tag)) return 27;
            if ("pano_l".equals(tag)) return 1;
            return -1;
        });
        assertEquals(1, selection.getCameraId());
        assertEquals(0, selection.getPreviewIndex());
    }

    @Test public void noDi4TagIsUnknown() {
        assertNull(AvmCameraHelper.findDi4Selection(tag -> -1));
    }
}
