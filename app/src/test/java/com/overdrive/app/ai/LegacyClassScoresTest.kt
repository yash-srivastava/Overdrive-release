package com.overdrive.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class LegacyClassScoresTest {
    @Test
    fun matchesAnchorWiseScanAcrossShapesAndRepeatedBlocks() {
        val random = Random(7129)
        val scratch = LegacyClassScores()
        for (boxes in intArrayOf(1, 17, 255, 256, 257, 511, 512, 513, 8400)) {
            for (classes in intArrayOf(1, 3, 64, 80)) {
                repeat(4) {
                    val output = FloatArray((4 + classes) * boxes) {
                        when (random.nextInt(12)) {
                            0 -> Float.NaN
                            1 -> Float.NEGATIVE_INFINITY
                            2 -> Float.POSITIVE_INFINITY
                            3 -> -0f
                            4 -> 0f
                            5 -> -1f
                            else -> random.nextInt(5) / 4f
                        }
                    }
                    for (start in 0 until boxes step LegacyClassScores.BLOCK_SIZE) {
                        val count = minOf(LegacyClassScores.BLOCK_SIZE, boxes - start)
                        scratch.scan(output, boxes, classes, start, count)
                        for (j in 0 until count) {
                            var bestScore = 0f
                            var bestClass = -1
                            for (c in 0 until classes) {
                                val score = output[(4 + c) * boxes + start + j]
                                if (score > bestScore) {
                                    bestScore = score
                                    bestClass = c
                                }
                            }
                            assertEquals(bestClass, scratch.classes[j])
                            assertEquals(bestScore.toRawBits(), scratch.scores[j].toRawBits())
                        }
                    }
                }
            }
        }
    }
}
