package com.overdrive.app.parking.signage;

/**
 * Greedy CTC decoding for a CRNN-style recogniser output.
 *
 * <p>Input is the per-timestep class distribution {@code [T][C]} (softmax
 * probabilities or logits — only the argmax and its value are used, so
 * logits work as long as {@link #decode} is told the values are not
 * probabilities). The blank symbol is collapsed and repeated consecutive
 * symbols are merged, which is the standard "best path" decode and what
 * PaddleOCR's own postprocess does.
 */
public final class CtcDecoder {

    /** Decoded text plus a confidence (mean emitted-symbol probability). */
    public static final class Decoded {
        public final String text;
        public final float confidence;
        Decoded(String text, float confidence) { this.text = text; this.confidence = confidence; }
    }

    private CtcDecoder() {}

    /**
     * @param probs        {@code [T][C]} per-timestep distribution
     * @param charset      symbol table indexed by class id; the blank slot's
     *                     entry is ignored
     * @param blankIndex   class id of the CTC blank (PaddleOCR: 0)
     * @param isProbability when false the values are treated as raw logits and
     *                     confidence is computed from a per-step softmax
     */
    public static Decoded decode(float[][] probs, String[] charset, int blankIndex, boolean isProbability) {
        if (probs == null || probs.length == 0 || charset == null) return new Decoded("", 0f);
        return decodeRows(probs, null, probs.length, 0, charset, blankIndex, isProbability);
    }

    /** Convenience for a flat row-major {@code [T*C]} buffer. */
    public static Decoded decodeFlat(float[] flat, int steps, int classes, String[] charset,
                                     int blankIndex, boolean isProbability) {
        // Division avoids overflow when validating an invalid tensor shape.
        if (flat == null || steps <= 0 || classes <= 0 || charset == null
                || steps > flat.length / classes) {
            return new Decoded("", 0f);
        }
        return decodeRows(null, flat, steps, classes, charset, blankIndex, isProbability);
    }

    /** Read row slices in place; the recogniser already owns the flat output buffer. */
    private static Decoded decodeRows(float[][] rows, float[] flat, int steps, int classes,
                                      String[] charset, int blankIndex, boolean isProbability) {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        double confSum = 0.0;
        int emitted = 0;
        for (int t = 0; t < steps; t++) {
            float[] step = rows == null ? flat : rows[t];
            if (step == null || step.length == 0) { prev = -1; continue; }
            int start = rows == null ? t * classes : 0;
            int end = rows == null ? start + classes : step.length;
            int best = start;
            float bestV = step[start];
            for (int c = start + 1; c < end; c++) {
                if (step[c] > bestV) { bestV = step[c]; best = c; }
            }
            int symbol = best - start;
            if (symbol != blankIndex && symbol != prev
                    && symbol < charset.length && charset[symbol] != null) {
                // Confidence is only used for emitted symbols. Blank, repeated and
                // unmapped winners do not need an expensive softmax pass.
                float p = isProbability ? bestV : softmaxAt(step, start, end, best);
                sb.append(charset[symbol]);
                confSum += p;
                emitted++;
            }
            prev = symbol;
        }
        float conf = emitted == 0 ? 0f : (float) (confSum / emitted);
        return new Decoded(sb.toString(), conf);
    }

    static float softmaxAt(float[] logits, int idx) {
        return softmaxAt(logits, 0, logits.length, idx);
    }

    private static float softmaxAt(float[] logits, int start, int end, int idx) {
        // Preserve the original max search, float subtraction and accumulation order
        // (including non-finite input behaviour), but restrict them to this row.
        float max = Float.NEGATIVE_INFINITY;
        for (int i = start; i < end; i++) if (logits[i] > max) max = logits[i];
        double sum = 0.0;
        for (int i = start; i < end; i++) sum += Math.exp(logits[i] - max);
        return sum <= 0 ? 0f : (float) (Math.exp(logits[idx] - max) / sum);
    }

    /**
     * Build the PaddleOCR-style symbol table: blank at index 0, then one
     * symbol per line of the dictionary, then a trailing space when the model
     * has exactly one more class than {@code lines + 1}.
     */
    public static String[] charsetFromLines(java.util.List<String> lines, int modelClasses) {
        int n = lines == null ? 0 : lines.size();
        int size = Math.max(modelClasses, n + 1);
        String[] cs = new String[size];
        cs[0] = "";  // blank
        for (int i = 0; i < n; i++) cs[i + 1] = lines.get(i);
        if (modelClasses == n + 2) cs[n + 1] = " ";
        for (int i = 0; i < size; i++) if (cs[i] == null) cs[i] = "";
        return cs;
    }
}
