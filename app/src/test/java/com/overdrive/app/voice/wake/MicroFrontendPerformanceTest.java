package com.overdrive.app.voice.wake;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
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

    @Test
    public void silentWindowsPreserveStreamingNoiseHistory() throws Exception {
        // Captured from the unmodified frontend at 08a4cce5. Alternate noise,
        // full-scale PCM, sustained zeros, impulses and low-level samples.
        int[] steps = {1, 3, 10, 20, 30};
        String[] expected = {
                "9969b495c2524a784fc105fc26584e9e6acc5c931caf8727063a1ebff0218af5",
                "abd9a2a985b910356d273a1647fc3f1e7f329ad2ecd601e7a77fdbd60dc1fb95",
                "5395ea9a318bea056c495960211a8923e2d01122bcb46795eaab12f12dd85479",
                "9b0d681b2c93913247a91659ba7febe948c9a12c010141b33a1cd0a50b12cd46",
                "27cfdeb0e56b3c77cb82bc3f86164352a2d5eea816e860173aaa72834e2df9b2"
        };
        for (int s = 0; s < steps.length; s++) {
            MicroFrontend frontend = new MicroFrontend(steps[s]);
            Random random = new Random(8821 + steps[s]);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int block = 0; block < 60; block++) {
                if (block == 30) frontend.reset();
                short[] pcm = new short[2048 + block * 7];
                for (int i = 0; i < pcm.length; i++) {
                    switch (block % 6) {
                        case 0: pcm[i] = (short) random.nextInt(); break;
                        case 1: pcm[i] = i % 2 == 0 ? Short.MIN_VALUE : Short.MAX_VALUE; break;
                        case 4: pcm[i] = i % 181 == 0 ? Short.MIN_VALUE : 0; break;
                        case 5: pcm[i] = (short) (random.nextInt(5) - 2); break;
                        default: break;
                    }
                }
                for (int pos = 0; pos < pcm.length;) {
                    int len = Math.min(pcm.length - pos, 1 + random.nextInt(503));
                    int used = frontend.process(pcm, pos, len);
                    assertTrue(used > 0 && used <= len);
                    pos += used;
                    if (frontend.frameReady()) {
                        for (int value : frontend.features()) {
                            digest.update((byte) (value >>> 8));
                            digest.update((byte) value);
                        }
                    }
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            assertEquals("step " + steps[s], expected[s], hex.toString());
        }
    }

    @Test
    public void compactFilterbankMatchesIndependentSpectrumReference() throws Exception {
        MicroFrontend frontend = new MicroFrontend();
        // Test the filterbank before adaptive noise reduction can mask a mismatch.
        // Keep the FFT and filterbank internals private in production.
        short[] re = (short[]) readField(frontend, "outRe");
        short[] im = (short[]) readField(frontend, "outIm");
        int[] signal = (int[]) readField(frontend, "signal");
        Method accumulate = MicroFrontend.class.getDeclaredMethod("accumulateFilterbank", int.class);
        accumulate.setAccessible(true);
        Random random = new Random(92731);
        for (int frame = 0; frame < 128; frame++) {
            for (int bin = 0; bin < re.length; bin++) {
                // Include zero, maximum negative, single-bin impulses and
                // arbitrary signed spectra, not just typical speech magnitudes.
                re[bin] = frame == 0 ? 0 : frame == 1 ? Short.MIN_VALUE
                        : frame < 18 ? (bin == (frame - 2) * 16 ? Short.MAX_VALUE : 0)
                        : (short) random.nextInt();
                im[bin] = frame == 0 || (frame >= 2 && frame < 18) ? 0
                        : frame == 1 ? Short.MIN_VALUE : (short) random.nextInt();
            }
            int shift = frame % 16;
            accumulate.invoke(frontend, shift);
            assertArrayEquals("spectrum " + frame, referenceFilterbank(re, im, shift), signal);
        }
    }

    private static Object readField(MicroFrontend frontend, String name) throws Exception {
        Field field = MicroFrontend.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(frontend);
    }

    // Independent bin-to-output accumulation: scatter to neighboring channels
    // instead of carrying the preceding interval into the next interval.
    private static int[] referenceFilterbank(short[] re, short[] im, int shift) {
        int channels = MicroFrontend.NUM_CHANNELS;
        long[] sums = new long[channels];
        float hz = 0.5f * MicroFrontend.SAMPLE_RATE / (re.length - 1);
        float low = referenceMel(125f);
        float spacing = (referenceMel(7500f) - low) / (channels + 1);
        int first = (int) (1.5f + 125f / hz);
        for (int bin = first; bin < re.length; bin++) {
            float mel = referenceMel(bin * hz);
            int interval = 0;
            while (interval <= channels && mel > low + spacing * (interval + 1)) interval++;
            if (interval > channels) break;
            float upper = low + spacing * (interval + 1);
            float lower = interval == 0 ? low : low + spacing * interval;
            float w = (upper - mel) / (upper - lower);
            long energy = re[bin] * re[bin] + im[bin] * im[bin];
            if (interval > 0) sums[interval - 1] += (short) Math.floor(w * 4096 + 0.5f) * energy;
            if (interval < channels) sums[interval] += (short) Math.floor((1f - w) * 4096 + 0.5f) * energy;
        }
        int[] out = new int[channels];
        for (int ch = 0; ch < channels; ch++) out[ch] = MicroFrontend.sqrt64(sums[ch]) >>> shift;
        return out;
    }

    private static float referenceMel(float hz) {
        return 1127f * (float) StrictMath.log1p(hz / 700f);
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
