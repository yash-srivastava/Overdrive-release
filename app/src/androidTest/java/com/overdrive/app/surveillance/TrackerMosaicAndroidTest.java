package com.overdrive.app.surveillance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Compare both native tracker entry points with the same synthetic camera frames. */
public class TrackerMosaicAndroidTest {
    private static final int W = 640, H = 480, QW = W / 2, QH = H / 2;
    private static final int FRAME_BYTES = W * H * 3;

    private static byte[] crop(byte[] mosaic, int q) {
        byte[] tile = new byte[QW * QH * 3];
        int x = (q % 2) * QW;
        int y = (q / 2) * QH;
        for (int row = 0; row < QH; row++) {
            System.arraycopy(mosaic, ((y + row) * W + x) * 3,
                    tile, row * QW * 3, QW * 3);
        }
        return tile;
    }

    private static List<float[]> run(boolean direct) {
        NativeMotion.initTracker();
        byte[] base = new byte[FRAME_BYTES];
        new Random(197).nextBytes(base);
        byte[] mosaic = new byte[FRAME_BYTES];
        ByteBuffer staged = ByteBuffer.allocateDirect(FRAME_BYTES);
        List<float[]> states = new ArrayList<>();
        for (int frame = 0; frame < 45; frame++) {
            // Move each quadrant's texture independently, wrapping within the
            // quadrant so a wrong tile offset cannot produce the same input.
            for (int q = 0; q < 4; q++) {
                int x = (q % 2) * QW;
                int y = (q / 2) * QH;
                int shift = (frame * (q + 1)) % QW;
                for (int row = 0; row < QH; row++) {
                    int start = ((y + row) * W + x) * 3;
                    System.arraycopy(base, start, mosaic, start + shift * 3, (QW - shift) * 3);
                    System.arraycopy(base, start + (QW - shift) * 3, mosaic, start, shift * 3);
                }
            }
            staged.clear();
            staged.put(mosaic).flip();
            long now = 1000L + frame * 100L;
            for (int q = 0; q < 4; q++) {
                if (frame == 0 || frame == 23) {
                    assertTrue(NativeMotion.trackerStartTrack(crop(mosaic, q), QW, QH,
                            q, q, 90, 65, 74, 90, now) >= 0);
                    assertNotNull(NativeMotion.trackerGetTrackBox(q));
                }
                if (direct) {
                    NativeMotion.trackerUpdateMosaic(staged, q, now);
                } else {
                    NativeMotion.trackerUpdate(crop(mosaic, q), QW, QH, q, now);
                }
                float[] box = NativeMotion.trackerGetTrackBox(q);
                states.add(box == null ? new float[0] : box);
                states.add(new float[]{NativeMotion.trackerNeedsYoloHeartbeat(q) ? 1f : 0f});
            }
            byte[] after = new byte[FRAME_BYTES];
            staged.rewind();
            staged.get(after);
            assertArrayEquals(mosaic, after);
        }
        return states;
    }

    @Test
    public void mosaicTrackerMatchesPackedQuadrants() {
        assertTrue(NativeMotion.isLibraryLoaded());
        assumeTrue(NativeMotion.isMog2Available()); // NCC is only built with OpenCV.
        try {
            List<float[]> packed = run(false);
            List<float[]> direct = run(true);
            assertTrue(packed.size() == direct.size());
            for (int i = 0; i < packed.size(); i++) {
                assertArrayEquals("Tracker state " + i, packed.get(i), direct.get(i), 0f);
            }
        } finally {
            NativeMotion.initTracker();
        }
    }

    private static void expectInvalid(ByteBuffer buffer, int quadrant) {
        try {
            NativeMotion.trackerUpdateMosaic(buffer, quadrant, 1L);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Rejected before any frame memory access.
        }
    }

    @Test
    public void rejectsInvalidMosaic() {
        assertTrue(NativeMotion.isLibraryLoaded());
        expectInvalid(null, 0);
        expectInvalid(ByteBuffer.allocate(FRAME_BYTES), 0);
        expectInvalid(ByteBuffer.allocateDirect(FRAME_BYTES - 1), 0);
        ByteBuffer valid = ByteBuffer.allocateDirect(FRAME_BYTES);
        expectInvalid(valid, -1);
        expectInvalid(valid, 4);
    }
}
