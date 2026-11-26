package com.overdrive.app.parking.signage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

public class CtcDecoderFlatTest {
    // Reference: original row-based CTC decode, including confidence on every row.
    private static CtcDecoder.Decoded reference(float[][] rows, String[] charset,
                                                int blank, boolean probability) {
        StringBuilder text = new StringBuilder();
        int previous = -1;
        double confidence = 0;
        int emitted = 0;
        for (float[] row : rows) {
            if (row == null || row.length == 0) { previous = -1; continue; }
            int best = 0;
            for (int i = 1; i < row.length; i++) if (row[i] > row[best]) best = i;
            float p = row[best];
            if (!probability) {
                float max = Float.NEGATIVE_INFINITY;
                for (float value : row) if (value > max) max = value;
                double sum = 0;
                for (float value : row) sum += Math.exp(value - max);
                p = sum <= 0 ? 0f : (float) (Math.exp(row[best] - max) / sum);
            }
            if (best != blank && best != previous && best < charset.length && charset[best] != null) {
                text.append(charset[best]);
                confidence += p;
                emitted++;
            }
            previous = best;
        }
        return new CtcDecoder.Decoded(text.toString(), emitted == 0 ? 0f : (float) (confidence / emitted));
    }

    private static void same(CtcDecoder.Decoded expected, CtcDecoder.Decoded actual) {
        assertEquals(expected.text, actual.text);
        assertEquals(Float.floatToIntBits(expected.confidence), Float.floatToIntBits(actual.confidence));
    }

    @Test
    public void randomizedFlatAndRowsMatchOriginal() {
        Random random = new Random(18);
        for (int trial = 0; trial < 2000; trial++) {
            int steps = 1 + random.nextInt(32);
            int classes = 1 + random.nextInt(40);
            int blank = random.nextInt(classes);
            boolean probability = trial % 2 == 0;
            String[] charset = new String[random.nextInt(classes + 2)];
            for (int c = 0; c < charset.length; c++) {
                if (random.nextInt(5) != 0) charset[c] = c % 4 == 0 ? "" : "<" + c + ">";
            }
            float[][] rows = new float[steps][classes];
            float[] flat = new float[steps * classes + 3];
            for (int t = 0; t < steps; t++) {
                for (int c = 0; c < classes; c++) {
                    float value = random.nextInt(17) - 8;
                    if (probability) value = (value + 8f) / 16f;
                    rows[t][c] = value;
                }
                // Include ties, repeated winners and blank rows.
                if (t > 0 && t % 5 == 0) rows[t] = rows[t - 1].clone();
                if (t % 7 == 0) rows[t][blank] = probability ? 1f : 20f;
                System.arraycopy(rows[t], 0, flat, t * classes, classes);
            }
            Arrays.fill(flat, steps * classes, flat.length, Float.POSITIVE_INFINITY);
            CtcDecoder.Decoded expected = reference(rows, charset, blank, probability);
            same(expected, CtcDecoder.decode(rows, charset, blank, probability));
            same(expected, CtcDecoder.decodeFlat(flat, steps, classes, charset, blank, probability));
        }
    }

    @Test
    public void nonFiniteValuesAndRaggedRowsKeepExistingSemantics() {
        String[] charset = {"", "X", "Y"};
        float[][] rows = {{Float.NaN, 4, 5}, {0, Float.POSITIVE_INFINITY, 1},
                {0, 3, Float.NaN}, {0, 0, 0}, {-0f, 0f, -0f},
                {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY}};
        float[] flat = new float[rows.length * 3];
        for (int t = 0; t < rows.length; t++) System.arraycopy(rows[t], 0, flat, t * 3, 3);
        for (boolean probability : new boolean[]{true, false}) {
            for (int blank = 0; blank < 3; blank++) {
                // Compare individual rows too, so NaN confidence in one row cannot
                // hide a regression in another row's confidence.
                for (float[] row : rows) {
                    same(reference(new float[][]{row}, charset, blank, probability),
                            CtcDecoder.decodeFlat(row, 1, 3, charset, blank, probability));
                }
                same(reference(rows, charset, blank, probability),
                        CtcDecoder.decodeFlat(flat, rows.length, 3, charset, blank, probability));
                float[][] ragged = {{0, 3}, null, {0, 3}, {}, {0, 0, 4}, {0, 5, 0, 6}};
                same(reference(ragged, charset, blank, probability),
                        CtcDecoder.decode(ragged, charset, blank, probability));
            }
        }
    }

    @Test
    public void invalidDimensionsDoNotOverflowOrAllocate() {
        String[] charset = {"", "X"};
        float[] flat = {0, 1};
        CtcDecoder.Decoded empty = new CtcDecoder.Decoded("", 0f);
        same(empty, CtcDecoder.decodeFlat(flat, Integer.MAX_VALUE, 2, charset, 0, true));
        same(empty, CtcDecoder.decodeFlat(flat, 65536, 65536, charset, 0, true));
        same(empty, CtcDecoder.decodeFlat(flat, 0, 2, charset, 0, true));
        same(empty, CtcDecoder.decodeFlat(flat, 1, -1, charset, 0, true));
        same(empty, CtcDecoder.decodeFlat(flat, 1, 3, charset, 0, true));
        same(empty, CtcDecoder.decodeFlat(flat, 1, 2, null, 0, true));
        same(empty, CtcDecoder.decodeFlat(null, 1, 2, charset, 0, true));
    }
}
