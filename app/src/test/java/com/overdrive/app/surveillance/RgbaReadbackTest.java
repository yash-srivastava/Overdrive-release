package com.overdrive.app.surveillance;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.Assert.*;

public class RgbaReadbackTest {
    @Test public void packsRowsAndDropsAlphaForRepeatedFrames() {
        Random random = new Random(71);
        for (int width : new int[]{1, 15, 16, 17, 640}) {
            int height = width == 640 ? 480 : 3;
            RgbaReadback packer = new RgbaReadback(width, height);
            byte[] input = new byte[width * height * 4];
            byte[] actual = new byte[width * height * 3];
            byte[] expected = new byte[actual.length];
            ByteBuffer source = ByteBuffer.allocateDirect(input.length + 8);
            for (int frame = 0; frame < 3; frame++) {
                random.nextBytes(input);
                source.clear();
                source.put(input).flip();
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int from = ((height - y - 1) * width + x) * 4;
                        System.arraycopy(input, from, expected, (y * width + x) * 3, 3);
                    }
                }
                assertTrue(packer.pack(source, actual));
                assertArrayEquals(expected, actual);
                assertEquals(0, source.position());
            }
            packer.clear();
            assertTrue(packer.pack(source, actual));
            assertArrayEquals(expected, actual);
        }
    }

    @Test public void rejectsIncompleteOrOffsetBuffersWithoutWritingOutput() {
        RgbaReadback packer = new RgbaReadback(2, 2);
        ByteBuffer source = ByteBuffer.allocateDirect(16);
        byte[] output = new byte[12];
        byte[] expected = new byte[12];
        source.limit(15);
        assertFalse(packer.pack(source, output));
        source.clear();
        source.position(1);
        assertFalse(packer.pack(source, output));
        source.clear();
        assertFalse(packer.pack(source, new byte[11]));
        assertArrayEquals(expected, output);
    }
}
