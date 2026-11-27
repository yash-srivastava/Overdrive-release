package com.overdrive.app.voice.audio;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ResamplerSymmetryTest {
    @Test
    public void integerDownsamplingAgreesWithSequentialFloatFir() {
        for (int src : new int[]{32000, 48000, 64000, 96000, 128000, 144000}) {
            check(src, 16000, 1);
        }
        check(48000, 8000, 1);
    }

    @Test
    public void otherRateRatiosKeepOriginalArithmetic() {
        for (int[] rate : new int[][]{{44100, 16000}, {16000, 24000}, {8000, 16000},
                {16000, 16000}, {160000, 16000}}) {
            check(rate[0], rate[1], 0);
        }
    }

    private static void check(int src, int dst, int tolerance) {
        Resampler resampler = new Resampler(src, dst);
        Random random = new Random(2016);
        for (int signal = 0; signal < 7; signal++) {
            short[] input = new short[18000];
            for (int i = 0; i < 15000; i++) {
                switch (signal) {
                    case 0: input[i] = (short) random.nextInt(); break;
                    case 1: input[i] = Short.MAX_VALUE; break;
                    case 2: input[i] = Short.MIN_VALUE; break;
                    case 3: input[i] = (i & 1) == 0 ? Short.MAX_VALUE : Short.MIN_VALUE; break;
                    case 4: input[i] = (short) (32767 * Math.sin(2 * Math.PI * 1000 * i / src)); break;
                    case 5: input[i] = (short) (32767 * Math.sin(2 * Math.PI * 0.47 * i)); break;
                    case 6: input[i] = i % 997 == 0 ? Short.MIN_VALUE : 0; break;
                    default: throw new AssertionError();
                }
            }
            // Trailing zeros exercise the filter tail and drained-silence path.
            short[] expected = reference(input, src, dst);
            short[] whole = new short[resampler.maxOutput(input.length)];
            int n = resampler.process(input, 0, input.length, whole, 0);
            assertEquals(expected.length, n);
            for (int i = 0; i < n; i++) {
                if (Math.abs((int) expected[i] - whole[i]) > tolerance) {
                    throw new AssertionError("rate " + src + " -> " + dst + ", signal " + signal
                            + ", sample " + i + ": " + expected[i] + " != " + whole[i]);
                }
            }
            resampler.reset();
            short[] chunked = new short[n + 8];
            Arrays.fill(chunked, (short) 1234);
            int used = 0, emitted = 0;
            while (used < input.length) {
                int len = Math.min(input.length - used, 1 + random.nextInt(257));
                emitted += resampler.process(input, used, len, chunked, 4 + emitted);
                used += len;
            }
            assertEquals(n, emitted);
            assertArrayEquals(Arrays.copyOf(whole, n), Arrays.copyOfRange(chunked, 4, n + 4));
            for (int i = 0; i < 4; i++) {
                assertEquals(1234, chunked[i]);
                assertEquals(1234, chunked[n + 4 + i]);
            }
            resampler.reset();
        }
    }

    // The original coefficient construction and sequential float dot product,
    // expressed as an offline convolution (no mirrored ring or streaming phase).
    private static short[] reference(short[] input, int src, int dst) {
        if (src == dst) return input.clone();
        int g = src, b = dst;
        while (b != 0) {
            int rem = g % b;
            g = b;
            b = rem;
        }
        int up = dst / g, down = src / g, minRate = Math.min(src, dst);
        double fsUp = (double) src * up;
        int total = (int) Math.ceil(5.5 * fsUp / (0.1 * minRate));
        int k = Math.max(8, (total + up - 1) / up), n = k * up;
        double fc = 0.45 * minRate / fsUp, mid = (n - 1) / 2.0;
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
        float[] c = new float[n];
        for (int i = 0; i < n; i++) c[i] = (float) (h[i] * scale);
        short[] out = new short[(int) (((long) input.length * up + down - 1) / down)];
        for (int o = 0; o < out.length; o++) {
            long t = (long) o * down;
            int newest = (int) (t / up), phase = (int) (t % up);
            float acc = 0;
            for (int j = 0; j < k; j++) {
                acc += c[phase + j * up] * (newest >= j ? input[newest - j] : 0);
            }
            int v = Math.round(acc);
            out[o] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
        }
        return out;
    }
}
