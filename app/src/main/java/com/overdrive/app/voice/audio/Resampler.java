package com.overdrive.app.voice.audio;

/** Streaming polyphase windowed-sinc resampler for mono PCM16 between rational rates. */
public final class Resampler {

    private final int up;
    private final int down;
    private final int taps;
    private final float[] coeffs;
    private final float[] ring;
    private final boolean passthrough;
    private final boolean symmetricDecimator;
    private int write;
    private int rel;

    public Resampler(int srcRate, int dstRate) {
        if (srcRate <= 0 || dstRate <= 0) throw new IllegalArgumentException("Invalid sample rate");
        int g = gcd(srcRate, dstRate);
        up = dstRate / g;
        down = srcRate / g;
        passthrough = up == 1 && down == 1;
        if (passthrough) {
            symmetricDecimator = false;
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
        // Only a single-phase filter is symmetric within that phase. Check the
        // actual rounded coefficients, not just the ideal Blackman formula.
        boolean symmetric = up == 1 && k <= 512;
        for (int j = 0; symmetric && j < k / 2; j++) {
            symmetric = coeffs[j] == coeffs[k - 1 - j];
        }
        symmetricDecimator = symmetric;
        ring = new float[2 * k];
    }

    /** Upper bound on samples {@link #process} emits for {@code inputLength} inputs. */
    public int maxOutput(int inputLength) {
        if (passthrough) return inputLength;
        return (int) (((long) inputLength * up) / down) + 2;
    }

    /** Resamples {@code len} samples; returns the number written to {@code out} at {@code outOff}. */
    public int process(short[] in, int off, int len, short[] out, int outOff) {
        if (passthrough) {
            System.arraycopy(in, off, out, outOff, len);
            return len;
        }
        final int k = taps;
        // A fully silent input block with a fully drained filter history
        // produces only zero samples. Keep phase and ring position advancing
        // exactly as in the regular loop, without evaluating the FIR taps.
        // Checking the first sample rejects most active audio immediately.
        if (len > 0 && in[off] == 0 && isSilent(in, off, len) && isHistorySilent()) {
            // Each input subtracts up; each emitted sample adds down.
            // Emit the minimum number needed to leave rel nonnegative.
            long phase = (long) len * up - rel;
            long count = phase > 0 ? (phase + down - 1) / down : 0;
            if (count <= Integer.MAX_VALUE && outOff >= 0 && count <= out.length - outOff) {
                int n = (int) count;
                java.util.Arrays.fill(out, outOff, outOff + n, (short) 0);
                write = (int) (((long) write + len) % k);
                rel = (int) (count * down - phase);
                return n;
            }
        }
        if (symmetricDecimator) return processSymmetric(in, off, len, out, outOff);
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

    private int processSymmetric(short[] in, int off, int len, short[] out, int outOff) {
        int o = outOff;
        final int k = taps;
        for (int i = 0; i < len; i++) {
            float s = in[off + i];
            ring[write] = s;
            ring[write + k] = s;
            if (++write == k) write = 0;
            // up == 1: at most one output per input, with rel always nonnegative.
            if (rel == 0) {
                int v = symmetricSample(write + k - 1);
                out[o++] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
                rel = down;
            }
            rel--;
        }
        return o - outOff;
    }

    private int symmetricSample(int newest) {
        // Pair equally weighted samples. PCM16 sums fit exactly in float;
        // double accumulation limits rounding error after reassociation.
        int oldest = newest - taps + 1;
        int half = taps / 2;
        double acc = 0;
        for (int j = 0; j < half; j++) {
            acc += (double) coeffs[j] * (ring[newest - j] + ring[oldest + j]);
        }
        if ((taps & 1) != 0) acc += (double) coeffs[half] * ring[newest - half];
        return (int) Math.round(acc);
    }

    private static boolean isSilent(short[] in, int off, int len) {
        for (int i = 1; i < len; i++) {
            if (in[off + i] != 0) return false;
        }
        return true;
    }

    private boolean isHistorySilent() {
        // Both halves of the mirrored ring contain the same samples.
        for (int i = 0; i < taps; i++) {
            if (ring[i] != 0f) return false;
        }
        return true;
    }

    public void reset() {
        java.util.Arrays.fill(ring, 0f);
        write = 0;
        rel = 0;
    }

    /** Little-endian PCM16 bytes to shorts; returns the sample count. */
    public static int bytesToShorts(byte[] src, int byteLen, short[] dst) {
        int n = byteLen >> 1;
        for (int i = 0, b = 0; i < n; i++, b += 2) {
            dst[i] = (short) ((src[b] & 0xff) | (src[b + 1] << 8));
        }
        return n;
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
