package com.overdrive.app.camera;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class PreviewChromaTest {
    @Test
    public void matchesScalarForIndependentPlanes() {
        Random random = new Random(421);
        for (int width : new int[] {0, 1, 7, 63, 64, 65, 160, 320, 640}) {
            for (int height : new int[] {0, 1, 2, 7, 31}) {
                for (int us : new int[] {1, 2, 3}) {
                    for (int vs : new int[] {1, 2, 4}) {
                        int ur = width * us + 7;
                        int vr = width * vs + 13;
                        for (int kind = 0; kind < 4; kind++) {
                            ByteBuffer u = plane(random, width, height, us, ur, (kind & 1) != 0);
                            ByteBuffer v = plane(random, width, height, vs, vr, (kind & 2) != 0);
                            compare(u, ur, us, v, vr, vs, width, height);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void handlesSharedInterleavedViewsWithoutTrailingPadding() {
        Random random = new Random(422);
        for (int width : new int[] {1, 63, 64, 320, 640}) {
            for (int height : new int[] {1, 2, 13}) {
                for (int padding : new int[] {0, 1, 32}) {
                    int row = width * 2 + padding;
                    int bytes = (height - 1) * row + width * 2;
                    byte[] data = new byte[bytes];
                    random.nextBytes(data);
                    ByteBuffer parent = ByteBuffer.allocateDirect(bytes);
                    parent.put(data).flip();
                    ByteBuffer first = parent.duplicate();
                    first.limit(bytes - 1);
                    ByteBuffer second = parent.duplicate();
                    second.position(1);
                    second = second.slice().asReadOnlyBuffer();
                    compare(second, row, 2, first, row, 2, width, height);
                    compare(first, row, 2, second, row, 2, width, height);
                }
            }
        }
    }

    private static ByteBuffer plane(Random random, int w, int h, int step, int stride,
            boolean direct) {
        int length = w == 0 || h == 0 ? 0 : (h - 1) * stride + (w - 1) * step + 1;
        byte[] data = new byte[length];
        random.nextBytes(data);
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(length + 11)
                : ByteBuffer.allocate(length + 11);
        storage.position(5);
        storage.put(data);
        storage.limit(5 + length);
        storage.position(5);
        ByteBuffer view = storage.slice().asReadOnlyBuffer();
        view.position(length / 2); // converter uses absolute plane offsets, not position
        return view;
    }

    private static void compare(ByteBuffer u, int ur, int us, ByteBuffer v, int vr,
            int vs, int w, int h) {
        int up = u.position(), vp = v.position(), ul = u.limit(), vl = v.limit();
        byte[] expected = new byte[13 + w * h * 2 + 9];
        Arrays.fill(expected, (byte) 0x5a);
        byte[] actual = expected.clone();
        int dst = 13;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                expected[dst++] = v.get(y * vr + x * vs);
                expected[dst++] = u.get(y * ur + x * us);
            }
        }
        PreviewChroma.copyToNv21(u, ur, us, v, vr, vs, w, h, actual, 13);
        assertArrayEquals(expected, actual);
        assertEquals(up, u.position());
        assertEquals(vp, v.position());
        assertEquals(ul, u.limit());
        assertEquals(vl, v.limit());
    }
}
