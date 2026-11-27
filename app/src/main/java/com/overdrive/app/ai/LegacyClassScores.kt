package com.overdrive.app.ai

/** Reusable, bounded working set for a channel-major [4+C,N] detection tensor. */
internal class LegacyClassScores {
    val scores = FloatArray(BLOCK_SIZE)
    val classes = IntArray(BLOCK_SIZE)

    /**
     * Reduce a block of consecutive anchors across every class, in class order.
     * Strict > preserves the first winning class, ignores NaN and keeps -1 for
     * anchors without a positive score, just like the original anchor-wise scan.
     * Only the first [count] entries of the scratch arrays are valid afterwards.
     */
    fun scan(output: FloatArray, numBoxes: Int, numClasses: Int, start: Int, count: Int) {
        java.util.Arrays.fill(scores, 0, count, 0f)
        java.util.Arrays.fill(classes, 0, count, -1)
        for (c in 0 until numClasses) {
            val base = (4 + c) * numBoxes + start
            for (j in 0 until count) {
                val score = output[base + j]
                if (score > scores[j]) {
                    scores[j] = score
                    classes[j] = c
                }
            }
        }
    }

    companion object {
        const val BLOCK_SIZE = 256
    }
}
