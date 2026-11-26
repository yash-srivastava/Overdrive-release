package com.overdrive.app.voice.audio;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ResamplerSilenceTest {
    private static final int[][] RATES = {
            {48000, 16000}, {16000, 24000}, {44100, 16000}, {16000, 48000},
            {16000, 16000}, {8000, 16000}, {48000, 8000}, {16000, 11025}, {11025, 48000}
    };

    @Test
    public void chunkedSilenceAndFilterTailsMatchOrdinaryProcessing() {
        // A nonzero first sample forces the one-shot reference through the
        // ordinary FIR path. The chunked stream can skip drained silent blocks.
        for (int[] rate : RATES) {
            Resampler reference = new Resampler(rate[0], rate[1]);
            Resampler chunked = new Resampler(rate[0], rate[1]);
            Random random = new Random(9021);
            for (int round = 0; round < 6; round++) {
                short[] input = new short[12000];
                input[0] = Short.MAX_VALUE;
                for (int i = 2000; i < 3500; i++) input[i] = (short) random.nextInt();
                input[7000] = Short.MIN_VALUE;
                for (int i = 10000; i < 12000; i++) input[i] = (short) random.nextInt();
                short[] expected = new short[reference.maxOutput(input.length)];
                int expectedCount = reference.process(input, 0, input.length, expected, 0);
                short[] actual = new short[expected.length + 6];
                Arrays.fill(actual, (short) 12345);
                int pos = 0, written = 0;
                while (pos < input.length) {
                    int len = Math.min(input.length - pos, 1 + random.nextInt(257));
                    written += chunked.process(input, pos, len, actual, 3 + written);
                    pos += len;
                }
                assertEquals(expectedCount, written);
                assertArrayEquals(Arrays.copyOf(expected, expectedCount),
                        Arrays.copyOfRange(actual, 3, 3 + written));
                for (int i = 0; i < 3; i++) assertEquals(12345, actual[i]);
                for (int i = 3 + written; i < actual.length; i++) assertEquals(12345, actual[i]);
                reference.reset();
                chunked.reset();
            }
        }
    }

    @Test
    public void silentBlocksAdvanceFractionalPhaseEvenWhenNoSamplesAreEmitted() {
        for (int[] rate : RATES) {
            Resampler resampler = new Resampler(rate[0], rate[1]);
            short[] input = new short[1400];
            long totalIn = 0, totalOut = 0;
            Random random = new Random(17);
            for (int i = 0; i < 200; i++) {
                int len = i % 3 == 0 ? i % 2 : random.nextInt(input.length);
                short[] output = new short[resampler.maxOutput(len) + 4];
                Arrays.fill(output, (short) 17);
                int n = resampler.process(input, 0, len, output, 2);
                totalIn += len;
                totalOut += n;
                assertEquals((totalIn * rate[1] + rate[0] - 1) / rate[0], totalOut);
                for (int j = 0; j < output.length; j++) {
                    assertEquals(j >= 2 && j < n + 2 ? 0 : 17, output[j]);
                }
            }
        }
    }

    @Test
    public void zeroBlocksWithLiveHistoryStillProduceFilterTail() {
        Resampler reference = new Resampler(48000, 16000);
        Resampler chunked = new Resampler(48000, 16000);
        short[] input = new short[1024];
        input[0] = Short.MAX_VALUE;
        short[] expected = new short[reference.maxOutput(input.length)];
        int count = reference.process(input, 0, input.length, expected, 0);
        short[] actual = new short[expected.length];
        int first = chunked.process(input, 0, 1, actual, 0);
        int tail = chunked.process(input, 1, 64, actual, first);
        int rest = chunked.process(input, 65, input.length - 65, actual, first + tail);
        assertEquals(count, first + tail + rest);
        assertArrayEquals(expected, actual);
    }
}
