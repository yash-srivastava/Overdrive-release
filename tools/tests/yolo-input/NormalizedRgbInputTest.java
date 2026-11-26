package com.overdrive.app.ai;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

/** Standalone host regression test; no Android or TFLite runtime required. */
public final class NormalizedRgbInputTest {
    public static void main(String[] args) {
        Random random = new Random(2026);
        int cases = 0;
        for (int size : new int[]{1, 256, 4095, 4096, 4097, 1228800}) {
            NormalizedRgbInput normalizer = new NormalizedRgbInput(size);
            byte[] rgb = new byte[size];
            ByteBuffer previous = null;
            for (int frame = 0; frame < 6; frame++) {
                if (frame == 0) {
                    for (int i = 0; i < size; i++) rgb[i] = (byte) i;
                } else if (frame == 1) {
                    java.util.Arrays.fill(rgb, (byte) 114);
                } else {
                    random.nextBytes(rgb);
                }
                ByteBuffer heap = ByteBuffer.wrap(rgb);
                ByteBuffer direct = ByteBuffer.allocateDirect(size);
                direct.put(rgb);
                heap.position(size / 2);
                for (int path = 0; path < 3; path++) {
                    ByteBuffer result = path == 0 ? normalizer.prepare(rgb)
                            : normalizer.prepare(path == 1 ? heap : direct);
                    if (previous != null && result != previous) throw new AssertionError("Not reused");
                    previous = result;
                    if (!result.isDirect() || result.position() != 0 || result.remaining() != size * 4
                            || result.order() != ByteOrder.nativeOrder()) throw new AssertionError("Buffer contract");
                    FloatBuffer values = result.asFloatBuffer();
                    for (int i = 0; i < size; i++) {
                        float expected = ((rgb[i] & 0xff) - 0f) / 255f;
                        if (Float.floatToIntBits(expected) != Float.floatToIntBits(values.get())) {
                            throw new AssertionError("Mismatch at " + i);
                        }
                    }
                    // Simulate interpreter advancing the borrowed input's position.
                    result.position(result.limit());
                    cases++;
                }
                if (heap.position() != size / 2 || direct.position() != size) {
                    throw new AssertionError("Source cursor changed");
                }
            }
        }
        NormalizedRgbInput normalizer = new NormalizedRgbInput(3);
        for (int size : new int[]{2, 4}) {
            try {
                normalizer.prepare(new byte[size]);
                throw new AssertionError("Accepted bad array size");
            } catch (IllegalArgumentException expected) { cases++; }
            try {
                normalizer.prepare(ByteBuffer.allocate(size));
                throw new AssertionError("Accepted bad buffer size");
            } catch (IllegalArgumentException expected) { cases++; }
        }
        System.out.println("Passed " + cases + " normalization cases");
    }
}
