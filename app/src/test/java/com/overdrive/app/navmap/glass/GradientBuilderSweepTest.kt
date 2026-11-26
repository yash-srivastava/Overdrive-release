package com.overdrive.app.navmap.glass

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class GradientBuilderSweepTest {
    // Original implementation, including the in-place half-texel shift and
    // separate first-match scans on either side of each event.
    private fun reference(p0In: Double, p1In: Double, pieces: Pieces, texel: Double): Stops {
        val out = Stops()
        val shift = texel * 0.5
        val inset = min(texel * 0.5, (p1In - p0In) * 0.45)
        val p0 = p0In - shift + inset
        val p1 = p1In - shift - inset
        val trim = !p0.isNaN() && !p1.isNaN() && p1 > p0
        fun alpha(t: Double) = if (!trim) 1.0 else ((t - p0) / (p1 - p0)).coerceIn(0.0, 1.0)
        fun slot(t: Double, before: Boolean): Int {
            for (k in 0 until pieces.n) {
                if (if (before) pieces.u[k] < t && t <= pieces.v[k]
                    else pieces.u[k] <= t && t < pieces.v[k]) return pieces.level[k]
            }
            return SpanSet.NONE
        }
        val events = ArrayList<Double>()
        for (k in 0 until pieces.n) {
            pieces.u[k] -= shift
            pieces.v[k] -= shift
            events.add(pieces.u[k]); events.add(pieces.v[k])
        }
        if (trim) { events.add(p0); events.add(p1) }
        events.sort()
        val clearSlot = if (trim) slot(max(0.0, p0), false) else 0
        val a0 = alpha(0.0)
        out.add(0.0, if (a0 <= 0.0) clearSlot else slot(0.0, false), a0)
        var prev = Double.NaN
        for (e in events) {
            if (!(e > 0.0 && e < 1.0) || e == prev) continue
            prev = e
            if (trim && e <= p0) {
                if (e == p0) out.add(e, clearSlot, 0.0)
                continue
            }
            val before = slot(e, true)
            val after = slot(e, false)
            if (before != after) out.add(e - 1e-6, before, alpha(e - 1e-6))
            out.add(e, after, alpha(e))
        }
        out.add(1.0, slot(1.0, true), alpha(1.0))
        return out
    }

    private fun copy(p: Pieces) = Pieces().also {
        it.n = p.n
        it.u = p.u.copyOf()
        it.v = p.v.copyOf()
        it.level = p.level.copyOf()
    }

    private fun compare(builder: GradientBuilder, out: Stops, input: Pieces,
                        p0: Double, p1: Double, texel: Double) {
        val expectedInput = copy(input)
        val actualInput = copy(input)
        val expected = reference(p0, p1, expectedInput, texel)
        builder.trimStops(p0, p1, actualInput, out, texel)
        assertEquals(expected.n, out.n)
        for (i in 0 until out.n) {
            assertEquals(expected.t[i].toBits(), out.t[i].toBits())
            assertEquals(expected.slot[i], out.slot[i])
            assertEquals(expected.a[i].toBits(), out.a[i].toBits())
        }
        for (i in 0 until input.n) {
            assertEquals(expectedInput.u[i].toBits(), actualInput.u[i].toBits())
            assertEquals(expectedInput.v[i].toBits(), actualInput.v[i].toBits())
        }
    }

    @Test fun sharedBoundariesEmptyPiecesAndTrimKnots() {
        val p = Pieces().apply {
            add(-0.2, 0.0, SpanSet.SLOW)
            add(0.0, 0.25, SpanSet.JAM)
            add(0.25, 0.25, SpanSet.SLOW)
            add(0.25, 0.5, SpanSet.JAM)
            add(0.75, 1.0, SpanSet.SLOW)
            add(1.0, 1.2, SpanSet.JAM)
        }
        val builder = GradientBuilder()
        val out = Stops()
        for (texel in listOf(0.0, GradientBuilder.RAMP_TEXEL)) {
            for (p0 in listOf(Double.NaN, -0.1, 0.0, 0.25, 0.5, 1.0)) {
                for (p1 in listOf(Double.NaN, 0.25, 0.5, 0.75, 1.0, 1.1)) {
                    compare(builder, out, p, p0, p1, texel)
                    compare(builder, out, Pieces(), p0, p1, texel)
                }
            }
        }
    }

    @Test fun randomizedOrderedPiecesWithReusedBuilderAndOutput() {
        val random = Random(116)
        val builder = GradientBuilder()
        val out = Stops()
        repeat(2000) {
            val p = Pieces()
            var edge = -0.1
            repeat(random.nextInt(0, 100)) {
                val u = edge + random.nextInt(0, 4) / 256.0
                val v = u + random.nextInt(0, 5) / 256.0
                p.add(u, v, if (random.nextBoolean()) SpanSet.SLOW else SpanSet.JAM)
                edge = v
            }
            val p0 = if (random.nextBoolean()) Double.NaN else random.nextDouble(-0.1, 1.1)
            val p1 = if (random.nextBoolean()) Double.NaN else random.nextDouble(-0.1, 1.1)
            compare(builder, out, p, p0, p1, if (random.nextBoolean()) 0.0 else GradientBuilder.RAMP_TEXEL)
        }
    }

    @Test fun unorderedOverlappingAndNonfinitePiecesUseOriginalFirstMatch() {
        val p = Pieces().apply {
            add(0.2, 0.8, SpanSet.SLOW)
            add(0.1, 0.5, SpanSet.JAM)
            add(0.7, 0.6, SpanSet.SLOW)
            add(Double.NaN, 0.9, SpanSet.JAM)
            add(0.9, Double.POSITIVE_INFINITY, SpanSet.SLOW)
        }
        val builder = GradientBuilder()
        val out = Stops()
        for (texel in listOf(0.0, GradientBuilder.RAMP_TEXEL)) {
            compare(builder, out, p, Double.NaN, Double.NaN, texel)
            compare(builder, out, p, 0.15, 0.85, texel)
        }
    }

    @Test fun denseDisjointPieces() {
        val p = Pieces().apply {
            repeat(2048) { i ->
                add((2 * i + 1) / 4098.0, (2 * i + 2) / 4098.0,
                    if (i % 2 == 0) SpanSet.SLOW else SpanSet.JAM)
            }
        }
        val builder = GradientBuilder()
        val out = Stops()
        compare(builder, out, p, Double.NaN, Double.NaN, 0.0)
        compare(builder, out, p, 0.37, 0.43, GradientBuilder.RAMP_TEXEL)
    }
}
