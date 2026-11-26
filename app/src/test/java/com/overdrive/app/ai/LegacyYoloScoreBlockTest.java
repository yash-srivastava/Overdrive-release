package com.overdrive.app.ai;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class LegacyYoloScoreBlockTest {
    @Test
    public void matchesBoxMajorReferenceAcrossShapesAndReuse() {
        Random random = new Random(72817);
        LegacyYoloScoreBlock scratch = new LegacyYoloScoreBlock();
        for (int boxes : new int[]{8400, 1, 255, 256, 257, 511, 512, 513, 17}) {
            for (int classes : new int[]{1, 2, 24, 64, 80, 81}) {
                float[] output = new float[(4 + classes) * boxes];
                for (int frame = 0; frame < 4; frame++) {
                    for (int i = 0; i < output.length; i++) {
                        switch (frame) {
                            case 0: output[i] = random.nextFloat(); break;
                            case 1: output[i] = (random.nextInt(9) - 4) / 4f; break;
                            case 2: output[i] = Float.intBitsToFloat(random.nextInt()); break;
                            default: output[i] = 0f; break;
                        }
                    }
                    check(scratch, output, boxes, classes);
                }
            }
        }
    }

    @Test
    public void preservesFirstWinnerAndSpecialValuesAcrossTileBoundary() {
        int boxes = 257;
        float[] output = new float[8 * boxes];
        Arrays.fill(output, Float.NaN);
        for (int i : new int[]{0, 255, 256}) {
            output[4 * boxes + i] = -0f;
            output[5 * boxes + i] = Float.POSITIVE_INFINITY;
            output[6 * boxes + i] = Float.POSITIVE_INFINITY;
            output[7 * boxes + i] = Float.NEGATIVE_INFINITY;
        }
        LegacyYoloScoreBlock scratch = new LegacyYoloScoreBlock();
        check(scratch, output, boxes, 4);
        Arrays.fill(output, -0f);
        check(scratch, output, boxes, 4);
        Arrays.fill(output, Float.NEGATIVE_INFINITY);
        check(scratch, output, boxes, 4);
    }

    private static void check(LegacyYoloScoreBlock scratch, float[] output, int boxes, int classes) {
        for (int first = 0; first < boxes; first += LegacyYoloScoreBlock.CAPACITY) {
            scratch.scan(output, boxes, classes, first);
            for (int i = first; i < Math.min(boxes, first + LegacyYoloScoreBlock.CAPACITY); i++) {
                float expected = 0f;
                int winner = -1;
                for (int c = 0; c < classes; c++) {
                    float score = output[(4 + c) * boxes + i];
                    if (score > expected) {
                        expected = score;
                        winner = c;
                    }
                }
                int local = i - first;
                assertEquals(winner, scratch.classAt(local));
                assertEquals(Float.floatToRawIntBits(expected),
                        Float.floatToRawIntBits(scratch.confidenceAt(local)));
            }
        }
    }
}
