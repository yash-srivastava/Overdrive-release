package com.overdrive.app.voice.wake;

import org.junit.Test;

import java.security.MessageDigest;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MicroFrontendPerformanceTest {
    @Test
    public void roundedUnsignedSquareRootMatchesIntegerReference() {
        Random random = new Random(30091);
        for (int i = 0; i < 20000; i++) {
            checkRoot(random.nextLong());
            checkRoot(random.nextLong() & ((1L << 53) - 1));
            checkRoot(random.nextInt() & 0xffffffffL);
        }
        long[] roots = {0, 1, 2, 255, 256, 65534, 65535, 65536,
                94906265, 94906266, 3037000499L, 0xfffffffeL, 0xffffffffL};
        for (long root : roots) {
            for (long delta = -2; delta <= 2; delta++) {
                checkRoot(root * root + delta);
                checkRoot(root * root + root + delta);
            }
        }
        for (long delta = -10; delta <= 10; delta++) {
            checkRoot((1L << 32) + delta);
            checkRoot((1L << 53) + delta);
            checkRoot(Long.MAX_VALUE + delta);
        }
    }

    private static void checkRoot(long num) {
        // Independent unsigned binary search for the floor root. A 32-bit
        // unsigned root squared always fits in an unsigned 64-bit value.
        long low = 0, high = 0xffffffffL;
        while (low < high) {
            long mid = (low + high + 1) >>> 1;
            if (Long.compareUnsigned(mid * mid, num) <= 0) low = mid;
            else high = mid - 1;
        }
        long max = (num >>> 32) == 0 ? 0xffffL : 0xffffffffL;
        if (low < max && Long.compareUnsigned(num - low * low, low) > 0) low++;
        assertEquals(Long.toUnsignedString(num), (int) low, MicroFrontend.sqrt64(num));
    }

    @Test
    public void streamingFeaturesMatchBaselineDigests() throws Exception {
        // Digests captured from the unmodified frontend at 283bbbdd. Cover
        // silence, full-scale alternating PCM, noise, impulses, tones and
        // low-level noise with varying input boundaries and a midstream reset.
        int[] steps = {1, 10, 20, 30};
        String[] expected = {
                "8dd37840b39e35f4cb14a0d786f8af14383a48d4aed064cb4b33106e7b31ff10",
                "6c36ea0c0ec2eb590d933fa2a2414f052c13d28aa66452cef3766015413f98c2",
                "32bda58e5a0c093932e29110c8e149c289ec34149c88387e77e9d4d4b9e0ae61",
                "1e664739ffe0964d49e43795d29b3b8dfdf6f71205db9c80380674cbb457142c"
        };
        for (int s = 0; s < steps.length; s++) {
            MicroFrontend frontend = new MicroFrontend(steps[s]);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Random chunks = new Random(44);
            int frames = 0;
            for (int kind = 0; kind < 6; kind++) {
                if (kind == 0 || kind == 3) frontend.reset();
                short[] pcm = input(kind);
                int pos = 0;
                while (pos < pcm.length) {
                    int len = Math.min(pcm.length - pos, 1 + chunks.nextInt(701));
                    int used = frontend.process(pcm, pos, len);
                    assertTrue(used > 0 && used <= len);
                    pos += used;
                    if (frontend.frameReady()) {
                        frames++;
                        for (int value : frontend.features()) {
                            digest.update((byte) (value >>> 8));
                            digest.update((byte) value);
                        }
                    }
                }
            }
            assertTrue(frames > 0);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            assertEquals("step " + steps[s], expected[s], hex.toString());
        }
    }

    private static short[] input(int kind) {
        short[] pcm = new short[16384];
        Random random = new Random(58491L + kind);
        for (int i = 0; i < pcm.length; i++) {
            switch (kind) {
                case 1: pcm[i] = i % 2 == 0 ? Short.MIN_VALUE : Short.MAX_VALUE; break;
                case 2: pcm[i] = (short) random.nextInt(); break;
                case 3: pcm[i] = (short) (i % 173 == 0 ? Short.MIN_VALUE
                        : i % 211 == 0 ? Short.MAX_VALUE : 0); break;
                case 4: pcm[i] = (short) (StrictMath.sin(i * 0.173) * 24000
                        + StrictMath.cos(i * 0.031) * 6000); break;
                case 5: pcm[i] = (short) (random.nextInt(31) - 15); break;
                default: break;
            }
        }
        return pcm;
    }
}
