package com.overdrive.app.communication;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

public class RemoteVoicePcmConverterTest {

    @Test
    public void duplicatesMonoSamplesIntoBothStereoChannels() {
        byte[] mono = pcm((short) 0x1234, (short) -0x1234);
        byte[] stereo = new byte[
                RemoteVoicePcmConverter.requiredStereoBytes(
                        mono.length, 16_000, 16_000)];

        int written = RemoteVoicePcmConverter.mono16LeToStereo16Le(
                mono, mono.length, 16_000, 16_000, stereo);

        assertEquals(8, written);
        assertArrayEquals(
                new byte[]{
                        0x34, 0x12, 0x34, 0x12,
                        (byte) 0xCC, (byte) 0xED,
                        (byte) 0xCC, (byte) 0xED
                },
                stereo);
    }

    @Test
    public void convertsSixteenKhzVoiceToFortyEightKhzStereo() {
        byte[] mono = pcm((short) 0, (short) 3_000);
        byte[] stereo = new byte[
                RemoteVoicePcmConverter.requiredStereoBytes(
                        mono.length, 16_000, 48_000)];

        int written = RemoteVoicePcmConverter.mono16LeToStereo16Le(
                mono, mono.length, 16_000, 48_000, stereo);

        assertEquals(24, written);
        assertEquals(0, sample(stereo, 0));
        assertEquals(1_000, sample(stereo, 1));
        assertEquals(2_000, sample(stereo, 2));
        assertEquals(3_000, sample(stereo, 3));
        assertEquals(3_000, sample(stereo, 4));
        assertEquals(3_000, sample(stereo, 5));
    }

    @Test
    public void reportsInputSignalPeak() {
        byte[] mono = pcm((short) -32_768, (short) 100, (short) 20_000);

        assertEquals(32_768,
                RemoteVoicePcmConverter.peakAbsoluteSample(
                        mono, mono.length));
    }

    @Test
    public void optimizedRatesMatchOriginalInterpolation() {
        Random random = new Random(48321L);
        int[][] rates = {{16000, 48000}, {16000, 16000}, {8000, 24000},
                {16000, 44100}, {16000, 8000}, {48000, 48000}};
        for (int[] rate : rates) {
            for (int trial = 0; trial < 300; trial++) {
                int samples = trial == 0 ? 1 : 1 + random.nextInt(2048);
                byte[] mono = new byte[samples * 2 + 6];
                random.nextBytes(mono);
                assertConversion(mono, samples * 2, rate[0], rate[1]);
            }
        }
        assertConversion(pcm(Short.MIN_VALUE, Short.MAX_VALUE, (short) -1,
                (short) 0, (short) 1, Short.MIN_VALUE), 12, 16000, 48000);
    }

    @Test
    public void aliasedBuffersRetainOriginalBehavior() {
        byte[] source = pcm((short) 123, (short) -456, (short) 789);
        for (int rate : new int[]{16000, 48000}) {
            int size = RemoteVoicePcmConverter.requiredStereoBytes(source.length, 16000, rate);
            byte[] expected = Arrays.copyOf(source, size);
            byte[] actual = expected.clone();
            referenceConvert(expected, source.length, 16000, rate, expected);
            RemoteVoicePcmConverter.mono16LeToStereo16Le(actual, source.length, 16000, rate, actual);
            assertArrayEquals(expected, actual);
        }
    }

    private static void assertConversion(byte[] mono, int length, int sourceRate, int outputRate) {
        int size = RemoteVoicePcmConverter.requiredStereoBytes(length, sourceRate, outputRate);
        byte[] expected = new byte[size + 7];
        Arrays.fill(expected, (byte) 0x55);
        byte[] actual = expected.clone();
        byte[] original = mono.clone();
        referenceConvert(mono, length, sourceRate, outputRate, expected);
        assertEquals(size, RemoteVoicePcmConverter.mono16LeToStereo16Le(
                mono, length, sourceRate, outputRate, actual));
        assertArrayEquals(expected, actual);
        assertArrayEquals(original, mono);
    }

    // Original floating-point interpolation, independent of the optimized path.
    private static void referenceConvert(byte[] source, int length, int sourceRate,
                                         int outputRate, byte[] destination) {
        int samples = length / 2;
        int frames = (int) Math.max(1L, Math.round(samples * (double) outputRate / sourceRate));
        double step = sourceRate / (double) outputRate;
        for (int frame = 0; frame < frames; frame++) {
            double pos = frame * step;
            int a = Math.min(samples - 1, (int) pos);
            int b = Math.min(samples - 1, a + 1);
            int first = (short) ((source[2 * a] & 255) | (source[2 * a + 1] << 8));
            int second = (short) ((source[2 * b] & 255) | (source[2 * b + 1] << 8));
            int value = (int) Math.round(first + (second - first) * (pos - a));
            value = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
            int out = frame * 4;
            destination[out] = destination[out + 2] = (byte) value;
            destination[out + 1] = destination[out + 3] = (byte) (value >>> 8);
        }
    }

    private static byte[] pcm(short... samples) {
        byte[] result = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            result[i * 2] = (byte) (samples[i] & 0xFF);
            result[i * 2 + 1] = (byte) ((samples[i] >>> 8) & 0xFF);
        }
        return result;
    }

    private static int sample(byte[] stereo, int frame) {
        int offset = frame * 4;
        int left = (short) ((stereo[offset] & 0xFF)
                | (stereo[offset + 1] << 8));
        int right = (short) ((stereo[offset + 2] & 0xFF)
                | (stereo[offset + 3] << 8));
        assertEquals(left, right);
        return left;
    }
}
