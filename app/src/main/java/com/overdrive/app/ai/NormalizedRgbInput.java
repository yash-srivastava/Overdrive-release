package com.overdrive.app.ai;

import com.overdrive.app.surveillance.NativeMotion;

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
    private final boolean allowNative;
    private boolean nativeChecked;
    private boolean nativeEnabled;

    NormalizedRgbInput(int elements) {
        this(elements, !javaOnly());
    }

    NormalizedRgbInput(int elements, boolean allowNative) {
        if (elements <= 0 || elements > Integer.MAX_VALUE / Float.BYTES) {
            throw new IllegalArgumentException("Invalid RGB tensor size: " + elements);
        }
        this.elements = elements;
        this.allowNative = allowNative;
        input = ByteBuffer.allocateDirect(elements * Float.BYTES).order(ByteOrder.nativeOrder());
        floats = input.asFloatBuffer();
        scratch = new float[Math.min(CHUNK, elements)];
        bytes = new byte[scratch.length];
    }

    /** Return a borrowed buffer valid until the next prepare call. */
    ByteBuffer prepare(byte[] rgb) {
        if (rgb.length != elements) throw new IllegalArgumentException("Incorrect RGB tensor size");
        if (tryNative(rgb, 0, null)) {
            input.clear();
            return input;
        }
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
        if ((source.isDirect() && tryNative(null, 0, source))
                || (source.hasArray() && tryNative(source.array(), source.arrayOffset(), null))) {
            input.clear();
            return input;
        }
        floats.clear();
        while (source.hasRemaining()) {
            int count = Math.min(bytes.length, source.remaining());
            source.get(bytes, 0, count);
            append(bytes, 0, count);
        }
        input.clear();
        return input;
    }

    // Read once per inference lane; the original Java path remains available for A/B profiling.
    private static boolean javaOnly() {
        try {
            return "java".equals(Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "debug.overdrive.rgb_normalize", "native"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryNative(byte[] array, int offset, ByteBuffer direct) {
        if (!allowNative || !NativeMotion.isLibraryLoaded()) return false;
        try {
            if (!nativeChecked) {
                nativeChecked = true;
                nativeEnabled = validateNative();
            }
            if (!nativeEnabled) return false;
            if (array != null ? nativeNormalizeArray(array, offset, input, elements)
                    : nativeNormalizeDirect(direct, input, elements)) return true;
        } catch (UnsatisfiedLinkError e) {
            // Older libsurveillance builds are supported without the new JNI exports.
            nativeChecked = true;
        }
        nativeEnabled = false;
        return false;
    }

    /** Exercise every possible channel value, a nonzero array offset and a SIMD tail. */
    private static boolean validateNative() {
        final int count = 271;
        byte[] probe = new byte[count + 1];
        ByteBuffer direct = ByteBuffer.allocateDirect(count);
        ByteBuffer result = ByteBuffer.allocateDirect(count * Float.BYTES).order(ByteOrder.nativeOrder());
        for (int i = 0; i < count; i++) {
            probe[i + 1] = (byte) i;
            direct.put(i, (byte) (255 - i));
        }
        if (!nativeNormalizeArray(probe, 1, result, count)) return false;
        for (int i = 0; i < count; i++) {
            if (Float.floatToIntBits(result.getFloat(i * Float.BYTES))
                    != Float.floatToIntBits(NORMALIZED[probe[i + 1] & 0xff])) return false;
        }
        if (!nativeNormalizeDirect(direct, result, count)) return false;
        for (int i = 0; i < count; i++) {
            if (Float.floatToIntBits(result.getFloat(i * Float.BYTES))
                    != Float.floatToIntBits(NORMALIZED[direct.get(i) & 0xff])) return false;
        }
        return true;
    }

    boolean isNativeEnabled() {
        return nativeEnabled;
    }

    private static native boolean nativeNormalizeArray(byte[] source, int offset,
            ByteBuffer destination, int count);
    private static native boolean nativeNormalizeDirect(ByteBuffer source,
            ByteBuffer destination, int count);

    private void append(byte[] rgb, int offset, int count) {
        for (int i = 0; i < count; i++) scratch[i] = NORMALIZED[rgb[offset + i] & 0xff];
        floats.put(scratch, 0, count);
    }
}
