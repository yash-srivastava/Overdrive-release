package com.overdrive.app.server;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RecordingThumbnailFrameAndroidTest {
    @Test
    public void exactSizeIsReturnedWithoutFullDecodeOrSecondBitmap() {
        Bitmap input = bitmap(320, 180);
        Bitmap result = RecordingThumbnailFrame.extract(time -> {
            assertEquals(1_000_000L, time);
            return input;
        }, time -> { throw new AssertionError("Unexpected full decode"); });
        try {
            assertSame(input, result);
            assertFalse(result.isRecycled());
        } finally {
            input.recycle();
        }
    }

    @Test
    public void fourByThreeFrameRetainsLegacyDimensionsAndRecyclesIntermediate() {
        Bitmap input = bitmap(320, 240);
        Bitmap result = RecordingThumbnailFrame.extract(time -> input,
                time -> { throw new AssertionError("Unexpected full decode"); });
        try {
            assertEquals(320, result.getWidth());
            assertEquals(180, result.getHeight());
            assertEquals(Color.MAGENTA, result.getPixel(160, 90));
            assertTrue(input.isRecycled());
        } finally {
            result.recycle();
        }
    }

    @Test
    public void undersizedScaledResultFallsBackAtTheSameTimestamp() {
        Bitmap small = bitmap(240, 180);
        Bitmap full = bitmap(640, 480);
        Bitmap result = RecordingThumbnailFrame.extract(time -> small, time -> {
            assertEquals(1_000_000L, time);
            assertTrue(small.isRecycled());
            return full;
        });
        try {
            assertEquals(320, result.getWidth());
            assertEquals(180, result.getHeight());
            assertTrue(full.isRecycled());
        } finally {
            result.recycle();
        }
    }

    @Test
    public void unsupportedScaledDecodePreservesTimestampFallbackOrder() {
        List<String> calls = new ArrayList<>();
        Bitmap full = bitmap(640, 480);
        Bitmap result = RecordingThumbnailFrame.extract(time -> {
            calls.add("scaled:" + time);
            if (time != 0) throw new UnsupportedOperationException();
            return null;
        }, time -> {
            calls.add("full:" + time);
            return time == 0 ? full : null;
        });
        try {
            assertEquals(Arrays.asList("scaled:1000000", "full:1000000",
                    "scaled:0", "full:0"), calls);
            assertEquals(320, result.getWidth());
            assertTrue(full.isRecycled());
        } finally {
            result.recycle();
        }
    }

    @Test
    public void noFrameReturnsNull() {
        assertNull(RecordingThumbnailFrame.extract(time -> null, time -> null));
    }

    private static Bitmap bitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.MAGENTA);
        return bitmap;
    }
}
