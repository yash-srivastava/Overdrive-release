package com.overdrive.app.ai;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Reusable NHWC float input for opaque RGB888 images. Owned by the inference lane. */
final class NormalizedRgbInput {
    private static final int CHUNK = 4096;
    private static final float[] NORMALIZED = new float[256];
    static {
        // Division, not multiplication by a rounded reciprocal: match NormalizeOp(0, 255).
        for (int i = 0; i < NORMALIZED.length; i++) NORMALIZED[i] = i / 255f;
    }

    private final int elements;
    private final ByteBuffer input;
    private final FloatBuffer floats;
    private final float[] scratch;
    private final byte[] bytes;

    NormalizedRgbInput(int elements) {
        if (elements <= 0 || elements > Integer.MAX_VALUE / Float.BYTES) {
            throw new IllegalArgumentException("Invalid RGB tensor size: " + elements);
        }
        this.elements = elements;
        input = ByteBuffer.allocateDirect(elements * Float.BYTES).order(ByteOrder.nativeOrder());
        floats = input.asFloatBuffer();
        scratch = new float[Math.min(CHUNK, elements)];
        bytes = new byte[scratch.length];
    }

    /** Return a borrowed buffer valid until the next prepare call. */
    ByteBuffer prepare(byte[] rgb) {
        if (rgb.length != elements) throw new IllegalArgumentException("Incorrect RGB tensor size");
        floats.clear();
        for (int offset = 0; offset < elements;) {
            int count = Math.min(scratch.length, elements - offset);
            append(rgb, offset, count);
            offset += count;
        }
        input.clear();
        return input;
    }

    /** Normalize a resized RGB tensor without allocating a frame-sized byte array. */
    ByteBuffer prepare(ByteBuffer rgb) {
        ByteBuffer source = rgb.duplicate();
        source.rewind();
        if (source.remaining() != elements) throw new IllegalArgumentException("Incorrect RGB tensor size");
        floats.clear();
        while (source.hasRemaining()) {
            int count = Math.min(bytes.length, source.remaining());
            source.get(bytes, 0, count);
            append(bytes, 0, count);
        }
        input.clear();
        return input;
    }

    private void append(byte[] rgb, int offset, int count) {
        for (int i = 0; i < count; i++) scratch[i] = NORMALIZED[rgb[offset + i] & 0xff];
        floats.put(scratch, 0, count);
    }
}
