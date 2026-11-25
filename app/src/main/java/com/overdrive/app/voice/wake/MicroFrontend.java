// Derived from TensorFlow Lite Micro's microfrontend (Copyright 2018 The TensorFlow Authors,
// Apache License 2.0) and KISS FFT (Copyright 2003-2010 Mark Borgerding, BSD-3-Clause).
package com.overdrive.app.voice.wake;

/**
 * Bit-exact port of the TensorFlow Lite Micro audio front-end (Hann window, int16 kissfft,
 * mel filterbank, noise reduction, PCAN gain control, log scale) with the settings every
 * microWakeWord model is trained on: 16 kHz, 30 ms window, 40 channels, 125–7500 Hz.
 * Unsigned C arithmetic is kept as int/long bit patterns. Not thread-safe.
 */
public final class MicroFrontend {

    public static final int SAMPLE_RATE = 16000;
    public static final int NUM_CHANNELS = 40;
    public static final int WINDOW_MS = 30;
    public static final int DEFAULT_STEP_MS = 10;

    private static final float LOWER_BAND_LIMIT = 125.0f;
    private static final float UPPER_BAND_LIMIT = 7500.0f;
    private static final int SMOOTHING_BITS = 10;
    private static final float EVEN_SMOOTHING = 0.025f;
    private static final float ODD_SMOOTHING = 0.06f;
    private static final float MIN_SIGNAL_REMAINING = 0.05f;
    private static final float PCAN_STRENGTH = 0.95f;
    private static final float PCAN_OFFSET = 80.0f;
    private static final int PCAN_GAIN_BITS = 21;
    private static final int LOG_SCALE_SHIFT = 6;

    private static final int WINDOW_BITS = 12;
    private static final int FILTERBANK_BITS = 12;
    private static final int NOISE_REDUCTION_BITS = 14;
    private static final int PCAN_SNR_BITS = 12;
    private static final int PCAN_OUTPUT_BITS = 6;
    private static final int WIDE_DYNAMIC_FUNCTION_BITS = 32;
    private static final int WIDE_DYNAMIC_FUNCTION_LUT_SIZE = 4 * WIDE_DYNAMIC_FUNCTION_BITS - 3;
    private static final int FILTERBANK_INDEX_ALIGNMENT = 2;
    private static final int FILTERBANK_CHANNEL_BLOCK_SIZE = 4;
    private static final int LOG_SCALE_LOG2 = 16;
    private static final int LOG_SCALE = 1 << LOG_SCALE_LOG2;
    private static final int LOG_SEGMENTS_LOG2 = 7;
    private static final int LOG_COEFF = 45426;
    private static final int SAMP_MAX = 32767;

    private static final short[] LOG_LUT = {
            0, 224, 442, 654, 861, 1063, 1259, 1450, 1636, 1817, 1992, 2163,
            2329, 2490, 2646, 2797, 2944, 3087, 3224, 3358, 3487, 3611, 3732, 3848,
            3960, 4068, 4172, 4272, 4368, 4460, 4549, 4633, 4714, 4791, 4864, 4934,
            5001, 5063, 5123, 5178, 5231, 5280, 5326, 5368, 5408, 5444, 5477, 5507,
            5533, 5557, 5578, 5595, 5610, 5622, 5631, 5637, 5640, 5641, 5638, 5633,
            5626, 5615, 5602, 5586, 5568, 5547, 5524, 5498, 5470, 5439, 5406, 5370,
            5332, 5291, 5249, 5203, 5156, 5106, 5054, 5000, 4944, 4885, 4825, 4762,
            4697, 4630, 4561, 4490, 4416, 4341, 4264, 4184, 4103, 4020, 3935, 3848,
            3759, 3668, 3575, 3481, 3384, 3286, 3186, 3084, 2981, 2875, 2768, 2659,
            2549, 2437, 2323, 2207, 2090, 1971, 1851, 1729, 1605, 1480, 1353, 1224,
            1094, 963, 830, 695, 559, 421, 282, 142, 0, 0};

    private final int windowSize;
    private final int windowStep;
    private final short[] windowCoefficients;
    private final short[] windowInput;
    private final short[] windowOutput;
    private int inputUsed;
    private int maxAbsOutput;

    private final int fftSize;
    private final int ncfft;
    private final short[] fftInput;
    private final short[] tmpRe;
    private final short[] tmpIm;
    private final short[] outRe;
    private final short[] outIm;
    private final short[] twiddleRe;
    private final short[] twiddleIm;
    private final short[] superRe;
    private final short[] superIm;
    private final int[] factors;

    private final int startIndex;
    private final int endIndex;
    private final short[] channelFrequencyStarts;
    private final short[] channelWeightStarts;
    private final short[] channelWidths;
    private final short[] weights;
    private final short[] unweights;
    private final int[] energy;
    private final long[] work;
    private final int[] signal;

    private final int evenSmoothing;
    private final int oddSmoothing;
    private final int minSignalRemaining;
    private final int[] estimate;

    private final short[] gainLut;
    private final int snrShift;
    private final int correctionBits;

    private final int[] features = new int[NUM_CHANNELS];
    private boolean frameReady;

    public MicroFrontend() {
        this(DEFAULT_STEP_MS);
    }

    public MicroFrontend(int stepMs) {
        if (stepMs < 1 || stepMs > WINDOW_MS) throw new IllegalArgumentException("stepMs " + stepMs);
        windowSize = WINDOW_MS * SAMPLE_RATE / 1000;
        windowStep = stepMs * SAMPLE_RATE / 1000;
        windowCoefficients = new short[windowSize];
        windowInput = new short[windowSize];
        windowOutput = new short[windowSize];
        float arg = (float) Math.PI * 2.0f / (float) windowSize;
        for (int i = 0; i < windowSize; i++) {
            float v = 0.5f - (0.5f * cosf(arg * (i + 0.5f)));
            windowCoefficients[i] = (short) Math.floor(v * (1 << WINDOW_BITS) + 0.5f);
        }

        int n = 1;
        while (n < windowSize) n <<= 1;
        fftSize = n;
        ncfft = fftSize >> 1;
        fftInput = new short[fftSize];
        tmpRe = new short[ncfft];
        tmpIm = new short[ncfft];
        outRe = new short[ncfft + 1];
        outIm = new short[ncfft + 1];
        twiddleRe = new short[ncfft];
        twiddleIm = new short[ncfft];
        for (int i = 0; i < ncfft; i++) {
            double phase = -2 * Math.PI * i / ncfft;
            twiddleRe[i] = (short) Math.floor(.5 + SAMP_MAX * StrictMath.cos(phase));
            twiddleIm[i] = (short) Math.floor(.5 + SAMP_MAX * StrictMath.sin(phase));
        }
        superRe = new short[ncfft / 2];
        superIm = new short[ncfft / 2];
        for (int i = 0; i < ncfft / 2; i++) {
            double phase = -Math.PI * ((double) (i + 1) / ncfft + .5);
            superRe[i] = (short) Math.floor(.5 + SAMP_MAX * StrictMath.cos(phase));
            superIm[i] = (short) Math.floor(.5 + SAMP_MAX * StrictMath.sin(phase));
        }
        factors = factor(ncfft);

        int spectrumSize = fftSize / 2 + 1;
        int channelsPlus1 = NUM_CHANNELS + 1;
        channelFrequencyStarts = new short[channelsPlus1];
        channelWeightStarts = new short[channelsPlus1];
        channelWidths = new short[channelsPlus1];
        work = new long[channelsPlus1];
        float[] centerMel = new float[channelsPlus1];
        int[] actualStarts = new int[channelsPlus1];
        int[] actualWidths = new int[channelsPlus1];
        float melLow = freqToMel(LOWER_BAND_LIMIT);
        float melSpacing = (freqToMel(UPPER_BAND_LIMIT) - melLow) / ((float) channelsPlus1);
        for (int i = 0; i < channelsPlus1; i++) centerMel[i] = melLow + (melSpacing * (i + 1));

        float hzPerSbin = 0.5f * SAMPLE_RATE / ((float) spectrumSize - 1);
        startIndex = (int) (1.5f + LOWER_BAND_LIMIT / hzPerSbin);
        int chanFreqIndexStart = startIndex;
        int weightIndexStart = 0;
        boolean needsZeros = false;
        for (int chan = 0; chan < channelsPlus1; chan++) {
            int freqIndex = chanFreqIndexStart;
            while (freqToMel(freqIndex * hzPerSbin) <= centerMel[chan]) freqIndex++;
            int width = freqIndex - chanFreqIndexStart;
            actualStarts[chan] = chanFreqIndexStart;
            actualWidths[chan] = width;
            if (width == 0) {
                channelFrequencyStarts[chan] = 0;
                channelWeightStarts[chan] = 0;
                channelWidths[chan] = FILTERBANK_CHANNEL_BLOCK_SIZE;
                if (!needsZeros) {
                    needsZeros = true;
                    for (int j = 0; j < chan; j++) channelWeightStarts[j] += FILTERBANK_CHANNEL_BLOCK_SIZE;
                    weightIndexStart += FILTERBANK_CHANNEL_BLOCK_SIZE;
                }
            } else {
                int alignedStart = (chanFreqIndexStart / FILTERBANK_INDEX_ALIGNMENT) * FILTERBANK_INDEX_ALIGNMENT;
                int alignedWidth = chanFreqIndexStart - alignedStart + width;
                int paddedWidth = (((alignedWidth - 1) / FILTERBANK_CHANNEL_BLOCK_SIZE) + 1)
                        * FILTERBANK_CHANNEL_BLOCK_SIZE;
                channelFrequencyStarts[chan] = (short) alignedStart;
                channelWeightStarts[chan] = (short) weightIndexStart;
                channelWidths[chan] = (short) paddedWidth;
                weightIndexStart += paddedWidth;
            }
            chanFreqIndexStart = freqIndex;
        }
        weights = new short[weightIndexStart];
        unweights = new short[weightIndexStart];
        int end = 0;
        for (int chan = 0; chan < channelsPlus1; chan++) {
            int frequency = actualStarts[chan];
            int offset = frequency - channelFrequencyStarts[chan];
            int weightStart = channelWeightStarts[chan];
            float denom = chan == 0 ? melLow : centerMel[chan - 1];
            for (int j = 0; j < actualWidths[chan]; j++, frequency++) {
                float w = (centerMel[chan] - freqToMel(frequency * hzPerSbin)) / (centerMel[chan] - denom);
                int idx = weightStart + offset + j;
                weights[idx] = (short) Math.floor(w * (1 << FILTERBANK_BITS) + 0.5f);
                unweights[idx] = (short) Math.floor((1.0f - w) * (1 << FILTERBANK_BITS) + 0.5f);
            }
            if (frequency > end) end = frequency;
        }
        endIndex = end;
        if (endIndex >= spectrumSize) throw new IllegalStateException("filterbank end above spectrum");
        int maxEnergyIndex = spectrumSize;
        for (int chan = 0; chan < channelsPlus1; chan++) {
            maxEnergyIndex = Math.max(maxEnergyIndex, channelFrequencyStarts[chan] + channelWidths[chan]);
        }
        energy = new int[maxEnergyIndex];
        signal = new int[NUM_CHANNELS];

        oddSmoothing = (int) (ODD_SMOOTHING * (1 << NOISE_REDUCTION_BITS));
        evenSmoothing = (int) (EVEN_SMOOTHING * (1 << NOISE_REDUCTION_BITS));
        minSignalRemaining = (int) (MIN_SIGNAL_REMAINING * (1 << NOISE_REDUCTION_BITS));
        estimate = new int[NUM_CHANNELS];

        correctionBits = msb32(fftSize) - 1 - (FILTERBANK_BITS / 2);
        snrShift = PCAN_GAIN_BITS - correctionBits - PCAN_SNR_BITS;
        int inputBits = SMOOTHING_BITS - correctionBits;
        gainLut = new short[WIDE_DYNAMIC_FUNCTION_LUT_SIZE];
        gainLut[0] = pcanGain(inputBits, 0);
        gainLut[1] = pcanGain(inputBits, 1);
        for (int interval = 2; interval <= WIDE_DYNAMIC_FUNCTION_BITS; interval++) {
            int x0 = 1 << (interval - 1);
            int x1 = x0 + (x0 >>> 1);
            int x2 = interval == WIDE_DYNAMIC_FUNCTION_BITS ? x0 + (x0 - 1) : 2 * x0;
            short y0 = pcanGain(inputBits, x0);
            short y1 = pcanGain(inputBits, x1);
            short y2 = pcanGain(inputBits, x2);
            int diff1 = y1 - y0;
            int diff2 = y2 - y0;
            int a1 = 4 * diff1 - diff2;
            int a2 = diff2 - a1;
            gainLut[4 * interval - 6] = y0;
            gainLut[4 * interval - 5] = (short) a1;
            gainLut[4 * interval - 4] = (short) a2;
        }
    }

    /**
     * Consumes samples until one frame completes or the input runs out and returns how many
     * were consumed. {@link #frameReady()} then tells whether {@link #features()} is new.
     */
    public int process(short[] pcm, int offset, int length) {
        frameReady = false;
        int copy = Math.min(windowSize - inputUsed, length);
        if (copy <= 0) return 0;
        System.arraycopy(pcm, offset, windowInput, inputUsed, copy);
        inputUsed += copy;
        if (inputUsed < windowSize) return copy;

        applyWindow();
        int inputShift = 15 - msb32(maxAbsOutput);
        for (int i = 0; i < windowSize; i++) {
            fftInput[i] = (short) ((windowOutput[i] & 0xFFFF) << inputShift);
        }
        for (int i = windowSize; i < fftSize; i++) fftInput[i] = 0;
        kissFftr();
        accumulateFilterbank(inputShift);
        reduceNoise();
        applyPcan();
        applyLog();
        frameReady = true;
        return copy;
    }

    /** True when the last {@link #process} call completed a frame. */
    public boolean frameReady() {
        return frameReady;
    }

    /** Latest frame: {@link #NUM_CHANNELS} unsigned 16-bit values, overwritten by the next frame. */
    public int[] features() {
        return features;
    }

    /** Samples between frames. */
    public int stepSamples() {
        return windowStep;
    }

    public void reset() {
        java.util.Arrays.fill(windowInput, (short) 0);
        java.util.Arrays.fill(windowOutput, (short) 0);
        inputUsed = 0;
        maxAbsOutput = 0;
        java.util.Arrays.fill(fftInput, (short) 0);
        java.util.Arrays.fill(outRe, (short) 0);
        java.util.Arrays.fill(outIm, (short) 0);
        java.util.Arrays.fill(work, 0L);
        java.util.Arrays.fill(estimate, 0);
        frameReady = false;
    }

    /** Int8 model input for a frontend value: ESPHome's integer rounding of value / 25.6 / scale + zeroPoint. */
    static int quantize(int value, int divisor, int zeroPoint) {
        int q = ((value * 256) + (divisor / 2)) / divisor + zeroPoint;
        return q < -128 ? -128 : (q > 127 ? 127 : q);
    }

    /** Divisor for {@link #quantize}; 666 for the 26/256 input scale ESPHome hardcodes. */
    static int quantizeDivisor(float inputScale) {
        return (int) Math.round(25.6 * 256.0 * inputScale);
    }

    private void applyWindow() {
        int maxAbs = 0;
        for (int i = 0; i < windowSize; i++) {
            short v = (short) ((windowInput[i] * windowCoefficients[i]) >> WINDOW_BITS);
            windowOutput[i] = v;
            short a = v < 0 ? (short) -v : v;
            if (a > maxAbs) maxAbs = a;
        }
        System.arraycopy(windowInput, windowStep, windowInput, 0, windowSize - windowStep);
        inputUsed -= windowStep;
        maxAbsOutput = maxAbs;
    }

    private void kissFftr() {
        kfWork(0, 0, 1, 0);
        short tdcR = sround(tmpRe[0] * (SAMP_MAX / 2));
        short tdcI = sround(tmpIm[0] * (SAMP_MAX / 2));
        outRe[0] = (short) (tdcR + tdcI);
        outRe[ncfft] = (short) (tdcR - tdcI);
        outIm[0] = 0;
        outIm[ncfft] = 0;
        for (int k = 1; k <= ncfft / 2; k++) {
            short fpkR = sround(tmpRe[k] * (SAMP_MAX / 2));
            short fpkI = sround(tmpIm[k] * (SAMP_MAX / 2));
            short fpnkR = sround(tmpRe[ncfft - k] * (SAMP_MAX / 2));
            short fpnkI = sround(((short) -tmpIm[ncfft - k]) * (SAMP_MAX / 2));
            short f1kR = (short) (fpkR + fpnkR);
            short f1kI = (short) (fpkI + fpnkI);
            short f2kR = (short) (fpkR - fpnkR);
            short f2kI = (short) (fpkI - fpnkI);
            short twR = sround(f2kR * superRe[k - 1] - f2kI * superIm[k - 1]);
            short twI = sround(f2kR * superIm[k - 1] + f2kI * superRe[k - 1]);
            outRe[k] = (short) ((f1kR + twR) >> 1);
            outIm[k] = (short) ((f1kI + twI) >> 1);
            outRe[ncfft - k] = (short) ((f1kR - twR) >> 1);
            outIm[ncfft - k] = (short) ((twI - f1kI) >> 1);
        }
    }

    /** kissfft's recursive decimation; {@code f} indexes the real input as complex pairs. */
    private void kfWork(int fout, int f, int fstride, int factorIndex) {
        int p = factors[factorIndex];
        int m = factors[factorIndex + 1];
        int foutEnd = fout + p * m;
        if (m == 1 && p == 4) {
            bfly4Leaf(fout, f, fstride);
            return;
        }
        if (m == 1) {
            for (int o = fout; o != foutEnd; o++) {
                tmpRe[o] = fftInput[2 * f];
                tmpIm[o] = fftInput[2 * f + 1];
                f += fstride;
            }
        } else {
            for (int o = fout; o != foutEnd; o += m) {
                kfWork(o, f, fstride * p, factorIndex + 2);
                f += fstride;
            }
        }
        if (p == 4) {
            bfly4(fout, fstride, m);
        } else {
            bfly2(fout, fstride, m);
        }
    }

    /** Radix-4 leaf: fuse the input gather, fixed-point scaling and butterfly.
     * All three twiddles are (32767, 0). After division by four, the
     * scaled values are in [-8191, 8191], where sround(v * 32767) == v.
     * Keep the original short narrowing at every butterfly boundary. */
    private void bfly4Leaf(int fout, int f, int stride) {
        int i = 2 * f;
        int step = 2 * stride;
        short aR = sround(fftInput[i] * (SAMP_MAX / 4));
        short aI = sround(fftInput[i + 1] * (SAMP_MAX / 4));
        i += step;
        short bR = sround(fftInput[i] * (SAMP_MAX / 4));
        short bI = sround(fftInput[i + 1] * (SAMP_MAX / 4));
        i += step;
        short cR = sround(fftInput[i] * (SAMP_MAX / 4));
        short cI = sround(fftInput[i + 1] * (SAMP_MAX / 4));
        i += step;
        short dR = sround(fftInput[i] * (SAMP_MAX / 4));
        short dI = sround(fftInput[i + 1] * (SAMP_MAX / 4));

        short acSumR = (short) (aR + cR), acSumI = (short) (aI + cI);
        short acDiffR = (short) (aR - cR), acDiffI = (short) (aI - cI);
        short bdSumR = (short) (bR + dR), bdSumI = (short) (bI + dI);
        short bdDiffR = (short) (bR - dR), bdDiffI = (short) (bI - dI);
        tmpRe[fout] = (short) (acSumR + bdSumR);
        tmpIm[fout] = (short) (acSumI + bdSumI);
        tmpRe[fout + 1] = (short) (acDiffR + bdDiffI);
        tmpIm[fout + 1] = (short) (acDiffI - bdDiffR);
        tmpRe[fout + 2] = (short) (acSumR - bdSumR);
        tmpIm[fout + 2] = (short) (acSumI - bdSumI);
        tmpRe[fout + 3] = (short) (acDiffR - bdDiffI);
        tmpIm[fout + 3] = (short) (acDiffI + bdDiffR);
    }

    private void bfly2(int fout, int fstride, int m) {
        int tw = 0;
        for (int k = 0; k < m; k++) {
            int a = fout + k;
            int b = a + m;
            fixdiv(a, SAMP_MAX / 2);
            fixdiv(b, SAMP_MAX / 2);
            short tR = sround(tmpRe[b] * twiddleRe[tw] - tmpIm[b] * twiddleIm[tw]);
            short tI = sround(tmpRe[b] * twiddleIm[tw] + tmpIm[b] * twiddleRe[tw]);
            tw += fstride;
            tmpRe[b] = (short) (tmpRe[a] - tR);
            tmpIm[b] = (short) (tmpIm[a] - tI);
            tmpRe[a] = (short) (tmpRe[a] + tR);
            tmpIm[a] = (short) (tmpIm[a] + tI);
        }
    }

    private void bfly4(int fout, int fstride, int m) {
        int tw1 = 0;
        int tw2 = 0;
        int tw3 = 0;
        for (int k = 0; k < m; k++) {
            int i0 = fout + k;
            int i1 = i0 + m;
            int i2 = i1 + m;
            int i3 = i2 + m;
            fixdiv(i0, SAMP_MAX / 4);
            fixdiv(i1, SAMP_MAX / 4);
            fixdiv(i2, SAMP_MAX / 4);
            fixdiv(i3, SAMP_MAX / 4);
            short s0R = sround(tmpRe[i1] * twiddleRe[tw1] - tmpIm[i1] * twiddleIm[tw1]);
            short s0I = sround(tmpRe[i1] * twiddleIm[tw1] + tmpIm[i1] * twiddleRe[tw1]);
            short s1R = sround(tmpRe[i2] * twiddleRe[tw2] - tmpIm[i2] * twiddleIm[tw2]);
            short s1I = sround(tmpRe[i2] * twiddleIm[tw2] + tmpIm[i2] * twiddleRe[tw2]);
            short s2R = sround(tmpRe[i3] * twiddleRe[tw3] - tmpIm[i3] * twiddleIm[tw3]);
            short s2I = sround(tmpRe[i3] * twiddleIm[tw3] + tmpIm[i3] * twiddleRe[tw3]);
            short s5R = (short) (tmpRe[i0] - s1R);
            short s5I = (short) (tmpIm[i0] - s1I);
            tmpRe[i0] = (short) (tmpRe[i0] + s1R);
            tmpIm[i0] = (short) (tmpIm[i0] + s1I);
            short s3R = (short) (s0R + s2R);
            short s3I = (short) (s0I + s2I);
            short s4R = (short) (s0R - s2R);
            short s4I = (short) (s0I - s2I);
            tmpRe[i2] = (short) (tmpRe[i0] - s3R);
            tmpIm[i2] = (short) (tmpIm[i0] - s3I);
            tw1 += fstride;
            tw2 += fstride * 2;
            tw3 += fstride * 3;
            tmpRe[i0] = (short) (tmpRe[i0] + s3R);
            tmpIm[i0] = (short) (tmpIm[i0] + s3I);
            tmpRe[i1] = (short) (s5R + s4I);
            tmpIm[i1] = (short) (s5I - s4R);
            tmpRe[i3] = (short) (s5R - s4I);
            tmpIm[i3] = (short) (s5I + s4R);
        }
    }

    private void fixdiv(int i, int k) {
        tmpRe[i] = sround(tmpRe[i] * k);
        tmpIm[i] = sround(tmpIm[i] * k);
    }

    private void accumulateFilterbank(int inputShift) {
        for (int i = startIndex; i < endIndex; i++) {
            int re = outRe[i];
            int im = outIm[i];
            energy[i] = re * re + im * im;
        }
        long weightAcc = 0;
        long unweightAcc = 0;
        for (int ch = 0; ch <= NUM_CHANNELS; ch++) {
            int mag = channelFrequencyStarts[ch];
            int w = channelWeightStarts[ch];
            int width = channelWidths[ch];
            for (int j = 0; j < width; j++) {
                long e = energy[mag + j];
                weightAcc += weights[w + j] * e;
                unweightAcc += unweights[w + j] * e;
            }
            work[ch] = weightAcc;
            weightAcc = unweightAcc;
            unweightAcc = 0;
        }
        for (int i = 0; i < NUM_CHANNELS; i++) {
            signal[i] = sqrt64(work[i + 1]) >>> inputShift;
        }
    }

    private void reduceNoise() {
        for (int i = 0; i < NUM_CHANNELS; i++) {
            long smoothing = (i & 1) == 0 ? evenSmoothing : oddSmoothing;
            long oneMinus = (1 << NOISE_REDUCTION_BITS) - smoothing;
            int scaledUp = signal[i] << SMOOTHING_BITS;
            int est = (int) ((((scaledUp & 0xFFFFFFFFL) * smoothing)
                    + ((estimate[i] & 0xFFFFFFFFL) * oneMinus)) >>> NOISE_REDUCTION_BITS);
            estimate[i] = est;
            if (Integer.compareUnsigned(est, scaledUp) > 0) est = scaledUp;
            int floor = (int) (((signal[i] & 0xFFFFFFFFL) * minSignalRemaining) >>> NOISE_REDUCTION_BITS);
            int subtracted = (scaledUp - est) >>> SMOOTHING_BITS;
            signal[i] = Integer.compareUnsigned(subtracted, floor) > 0 ? subtracted : floor;
        }
    }

    private void applyPcan() {
        for (int i = 0; i < NUM_CHANNELS; i++) {
            long gain = wideDynamicFunction(estimate[i]) & 0xFFFFFFFFL;
            int snr = (int) (((signal[i] & 0xFFFFFFFFL) * gain) >>> snrShift);
            signal[i] = Integer.compareUnsigned(snr, 2 << PCAN_SNR_BITS) < 0
                    ? (snr * snr) >>> (2 + 2 * PCAN_SNR_BITS - PCAN_OUTPUT_BITS)
                    : (snr >>> (PCAN_SNR_BITS - PCAN_OUTPUT_BITS)) - (1 << PCAN_OUTPUT_BITS);
        }
    }

    private int wideDynamicFunction(int x) {
        if (Integer.compareUnsigned(x, 2) <= 0) return gainLut[x];
        int interval = msb32(x);
        int base = 4 * interval - 6;
        int frac = ((interval < 11) ? (x << (11 - interval)) : (x >>> (interval - 11))) & 0x3FF;
        int result = (gainLut[base + 2] * frac) >> 5;
        result += gainLut[base + 1] << 5;
        result *= frac;
        result = (result + (1 << 14)) >> 15;
        result += gainLut[base];
        return (short) result;
    }

    private void applyLog() {
        for (int i = 0; i < NUM_CHANNELS; i++) {
            int value = signal[i] << correctionBits;
            value = Integer.compareUnsigned(value, 1) > 0 ? log(value) : 0;
            features[i] = Integer.compareUnsigned(value, 0xFFFF) < 0 ? value : 0xFFFF;
        }
    }

    private static int log(int x) {
        int integer = msb32(x) - 1;
        int frac = (int) ((x & 0xFFFFFFFFL) - (1L << integer));
        if (integer < LOG_SCALE_LOG2) {
            frac <<= LOG_SCALE_LOG2 - integer;
        } else {
            frac >>= integer - LOG_SCALE_LOG2;
        }
        int baseSeg = frac >> (LOG_SCALE_LOG2 - LOG_SEGMENTS_LOG2);
        int c0 = LOG_LUT[baseSeg];
        int c1 = LOG_LUT[baseSeg + 1];
        int segBase = (LOG_SCALE >> LOG_SEGMENTS_LOG2) * baseSeg;
        int relPos = ((c1 - c0) * (frac - segBase)) >> LOG_SCALE_LOG2;
        int fraction = frac + c0 + relPos;
        int log2 = (integer << LOG_SCALE_LOG2) + fraction;
        int round = LOG_SCALE / 2;
        int loge = (int) ((LOG_COEFF * (log2 & 0xFFFFFFFFL) + round) >>> LOG_SCALE_LOG2);
        return ((loge << LOG_SCALE_SHIFT) + round) >>> LOG_SCALE_LOG2;
    }

    static int sqrt64(long num) {
        // Normal filterbank energies fit exactly in a double. Use the
        // hardware/JIT square root, then recover the exact integer result
        // (including values whose floating-point root rounds up to an integer).
        // The original unsigned algorithm covers larger or unsigned inputs.
        if (num >= 0 && num <= (1L << 53)) {
            long root = (long) Math.sqrt(num);
            long square = root * root;
            if (square > num) {
                square -= 2 * root - 1;
                root--;
            }
            // Round to nearest, as in the original remainder > root rule.
            long rounded = num - square > root ? root + 1 : root;
            // The original 32-bit path saturates at 0xffff.
            if ((num >>> 32) == 0 && rounded > 0xffffL) return 0xffff;
            return (int) rounded;
        }

        long res = 0;
        int maxBitNumber = Long.numberOfLeadingZeros(num) | 1;
        long bit = 1L << (63 - maxBitNumber);
        for (int iterations = (63 - maxBitNumber) / 2 + 1; iterations > 0; iterations--) {
            if (Long.compareUnsigned(num, res + bit) >= 0) {
                num -= res + bit;
                res = (res >>> 1) + bit;
            } else {
                res >>>= 1;
            }
            bit >>>= 2;
        }
        if (Long.compareUnsigned(num, res) > 0 && res != 0xFFFFFFFFL) res++;
        return (int) res;
    }

    private static short sround(int x) {
        return (short) ((x + (1 << 14)) >> 15);
    }

    private static int msb32(int n) {
        return 32 - Integer.numberOfLeadingZeros(n);
    }

    /** kissfft's factorisation: powers of 4, then 2, then odd primes. */
    private static int[] factor(int n) {
        int[] buf = new int[64];
        int count = 0;
        int p = 4;
        double floorSqrt = Math.floor(Math.sqrt(n));
        do {
            while (n % p != 0) {
                if (p == 4) {
                    p = 2;
                } else if (p == 2) {
                    p = 3;
                } else {
                    p += 2;
                }
                if (p > floorSqrt) p = n;
            }
            if (p != 2 && p != 4) throw new IllegalStateException("unsupported FFT radix " + p);
            n /= p;
            buf[count++] = p;
            buf[count++] = n;
        } while (n > 1);
        return java.util.Arrays.copyOf(buf, count);
    }

    private static float freqToMel(float freq) {
        return 1127.0f * (float) StrictMath.log1p(freq / 700.0f);
    }

    private static float cosf(float x) {
        return (float) StrictMath.cos(x);
    }

    private static short pcanGain(int inputBits, int x) {
        float xf = (float) (x & 0xFFFFFFFFL) / (float) (1 << inputBits);
        float gain = (float) (1 << PCAN_GAIN_BITS)
                * (float) StrictMath.pow(xf + PCAN_OFFSET, -PCAN_STRENGTH);
        if (gain > 0x7FFF) return 0x7FFF;
        return (short) (gain + 0.5f);
    }
}
