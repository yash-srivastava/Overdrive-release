package com.overdrive.app.voice.audio;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ResamplerMixedSilenceTest {
    private static final int[][] RATES = {
            {48000, 16000}, {16000, 24000}, {44100, 16000}, {16000, 48000},
            {16000, 16000}, {8000, 16000}, {48000, 8000}, {16000, 11025}, {11025, 48000}
    };

    @Test
    public void mixedSpansMatchOriginalFirAcrossRatesAndChunks() {
        for (int[] rate : RATES) {
            Resampler actual = new Resampler(rate[0], rate[1]);
            LegacyFir expected = new LegacyFir(rate[0], rate[1]);
            Random random = new Random(421);
            for (int round = 0; round < 4; round++) {
                short[] input = new short[8192];
                input[17] = Short.MIN_VALUE;
                input[1024] = Short.MAX_VALUE;
                for (int i = 2048; i < 2500; i++) input[i] = (short) random.nextInt();
                for (int i = 3000; i < 3500; i += 2) input[i] = (short) random.nextInt();
                input[7000] = -1;
                input[input.length - 1] = 1234;
                int pos = 0;
                while (pos < input.length) {
                    int len = Math.min(input.length - pos,
                            round % 2 == 0 ? input.length : 1 + random.nextInt(601));
                    compare(actual, expected, input, pos, len);
                    pos += len;
                }
                // Exercise a live tail at a block boundary, then a drained history.
                compare(actual, expected, new short[2048], 0, 2048);
                compare(actual, expected, new short[1], 0, 0);
                if (round % 2 == 1) {
                    actual.reset();
                    expected.reset();
                }
            }
        }
    }

    @Test
    public void aliasedArraysKeepSequentialFirBehavior() {
        Resampler actual = new Resampler(48000, 16000);
        LegacyFir expected = new LegacyFir(48000, 16000);
        short[] a = new short[4096];
        a[7] = 12345;
        a[2100] = -12345;
        short[] b = a.clone();
        int expectedCount = expected.process(b, 0, 3000, b, 500);
        int actualCount = actual.process(a, 0, 3000, a, 500);
        assertEquals(expectedCount, actualCount);
        assertArrayEquals(b, a);
    }

    private static void compare(Resampler actual, LegacyFir expected, short[] input, int off, int len) {
        short[] a = new short[actual.maxOutput(len) + 6];
        short[] b = new short[a.length];
        Arrays.fill(a, (short) 12345);
        Arrays.fill(b, (short) 12345);
        assertEquals(expected.process(input, off, len, b, 3), actual.process(input, off, len, a, 3));
        assertArrayEquals(b, a);
    }

    // Baseline scalar FIR, with no silence shortcut. Keep the original float
    // accumulation order and filter design so both phase and PCM can be compared.
    private static final class LegacyFir {
        private final int up;
        private final int down;
        private final int taps;
        private final float[] coeffs;
        private final float[] ring;
        private final boolean passthrough;
        private int write;
        private int rel;

        LegacyFir(int srcRate, int dstRate) {
            if (srcRate <= 0 || dstRate <= 0) throw new IllegalArgumentException("Invalid sample rate");
            int g = gcd(srcRate, dstRate);
            up = dstRate / g;
            down = srcRate / g;
            passthrough = up == 1 && down == 1;
            if (passthrough) {
                taps = 0;
                coeffs = new float[0];
                ring = new float[0];
                return;
            }
            int minRate = Math.min(srcRate, dstRate);
            double fsUp = (double) srcRate * up;
            // Blackman: transition ≈ 5.5·fs/N; 10% of the lower rate keeps aliasing above its Nyquist.
            int total = (int) Math.ceil(5.5 * fsUp / (0.1 * minRate));
            int k = Math.max(8, (total + up - 1) / up);
            taps = k;
            int n = up * k;
            double fc = (0.45 * minRate) / fsUp;
            double mid = (n - 1) / 2.0;
            double[] h = new double[n];
            double sum = 0;
            for (int i = 0; i < n; i++) {
                double x = i - mid;
                double sinc = x == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
                double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1))
                        + 0.08 * Math.cos(4 * Math.PI * i / (n - 1));
                h[i] = sinc * w;
                sum += h[i];
            }
            double scale = up / sum;
            coeffs = new float[n];
            for (int p = 0; p < up; p++) {
                for (int j = 0; j < k; j++) {
                    coeffs[p * k + j] = (float) (h[p + j * up] * scale);
                }
            }
            ring = new float[2 * k];
        }

        int process(short[] in, int off, int len, short[] out, int outOff) {
            if (passthrough) {
                System.arraycopy(in, off, out, outOff, len);
                return len;
            }
            final int k = taps;
            int o = outOff;
            for (int i = 0; i < len; i++) {
                float s = in[off + i];
                ring[write] = s;
                ring[write + k] = s;
                if (++write == k) write = 0;
                int newest = write + k - 1;
                while (rel < up) {
                    int base = rel * k;
                    float acc = 0f;
                    for (int j = 0; j < k; j++) {
                        acc += coeffs[base + j] * ring[newest - j];
                    }
                    int v = Math.round(acc);
                    out[o++] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
                    rel += down;
                }
                rel -= up;
            }
            return o - outOff;
        }

        void reset() {
            Arrays.fill(ring, 0f);
            write = 0;
            rel = 0;
        }

        private static int gcd(int a, int b) {
            while (b != 0) {
                int t = a % b;
                a = b;
                b = t;
            }
            return a;
        }
    }
}
