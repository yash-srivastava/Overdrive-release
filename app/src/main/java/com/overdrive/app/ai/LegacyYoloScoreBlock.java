package com.overdrive.app.ai;

import java.util.Arrays;

/** Scratch for a tile of a legacy [4+C,N] YOLO output tensor. */
final class LegacyYoloScoreBlock {
    static final int CAPACITY = 256;
    private final float[] confidence = new float[CAPACITY];
    private final int[] classes = new int[CAPACITY];

    /** Scan the next tile, visiting contiguous scores within each class plane. */
    void scan(float[] output, int boxes, int classCount, int first) {
        int count = Math.min(CAPACITY, boxes - first);
        Arrays.fill(confidence, 0, count, 0f);
        Arrays.fill(classes, 0, count, -1);
        for (int c = 0; c < classCount; c++) {
            int base = (4 + c) * boxes + first;
            for (int i = 0; i < count; i++) {
                float score = output[base + i];
                // Strict comparison keeps the original first-class tie rule,
                // ignores NaN and leaves nonpositive-only rows at (0, -1).
                if (score > confidence[i]) {
                    confidence[i] = score;
                    classes[i] = c;
                }
            }
        }
    }

    float confidenceAt(int local) {
        return confidence[local];
    }

    int classAt(int local) {
        return classes[local];
    }
}
