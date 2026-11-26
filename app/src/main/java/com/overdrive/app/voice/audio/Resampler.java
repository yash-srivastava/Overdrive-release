package com.overdrive.app.voice.audio;

/** Streaming polyphase windowed-sinc resampler for mono PCM16 between rational rates. */
public final class Resampler {

    private final int up;
    private final int down;
    private final int taps;
    private final float[] coeffs;
    private final float[] ring;
    private final boolean passthrough;
    private int write;
    private int rel;
    // Saturates at taps: at that point every sample in the FIR history is zero.
    private int zeroRun;

    public Resampler(int srcRate, int dstRate) {
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
        zeroRun = k;
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
        int o = outOff;
        boolean canSkip = in != out;
        for (int i = 0; i < len; i++) {
            float s = in[off + i];
            if (s == 0f && zeroRun == k && canSkip) {
                // The complete history is zero. Skip the maximal zero span,
                // even inside a mixed block, without dropping a live FIR tail.
                // Keep aliased input/output on the ordinary sequential path.
                int end = i + 1;
                while (end < len && in[off + end] == 0) end++;
                int span = end - i;
                long phase = (long) span * up - rel;
                long count = phase > 0 ? (phase + down - 1) / down : 0;
                if (o >= 0 && count <= out.length - o) {
                    int n = (int) count;
                    java.util.Arrays.fill(out, o, o + n, (short) 0);
                    o += n;
                    write = (int) (((long) write + span) % k);
                    rel = (int) (count * down - phase);
                    i = end - 1;
                    continue;
                }
                // Preserve the ordinary path's output bounds behavior,
                // without rescanning this span if the destination is too small.
                canSkip = false;
            }
            if (s != 0f) zeroRun = 0;
            else if (zeroRun < k) zeroRun++;
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

    public void reset() {
        java.util.Arrays.fill(ring, 0f);
        write = 0;
        rel = 0;
        zeroRun = taps;
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
