package com.overdrive.app.navmap.glass

import com.overdrive.app.navmap.nav.CongestionLevel
import com.overdrive.app.navmap.nav.CongestionSpan
import com.overdrive.app.navmap.nav.GeoPoint
import com.overdrive.app.navmap.nav.NavRoute
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class PieceBuilderSweepTest {
    private fun track(size: Int): RibbonTrack = RibbonTrack.of(
        NavRoute(List(size) { GeoPoint(47.0 + it * 0.0001, 8.0) }, emptyList(), 0.0, 0.0)
    )

    // Original midpoint classification: retain the full scan as an independent oracle.
    private fun reference(set: SpanSet, x0: Double, x1: Double, before: Double): Pieces {
        val out = Pieces()
        if (!(x1 > x0)) return out
        val marks = mutableListOf(x0, x1)
        for (k in 0 until set.size) {
            if (!(set.a[k] < before) || set.b[k] <= x0 || set.a[k] >= x1) continue
            if (set.a[k] > x0) marks.add(set.a[k])
            if (set.b[k] < x1) marks.add(set.b[k])
        }
        marks.sort()
        for (q in 0 until marks.size - 1) {
            val p = marks[q]
            val r = marks[q + 1]
            if (r - p < 1e-6) continue
            val mid = 0.5 * (p + r)
            var lv = SpanSet.NONE
            for (k in 0 until set.size) {
                if (set.a[k] < before && set.a[k] <= mid && set.b[k] > mid && set.level[k] > lv) {
                    lv = set.level[k]
                }
            }
            if (lv != SpanSet.NONE) out.add(p, r, lv)
        }
        return out
    }

    private fun compare(builder: PieceBuilder, out: Pieces, set: SpanSet, x0: Double, x1: Double, before: Double) {
        val expected = reference(set, x0, x1, before)
        builder.build(set, x0, x1, before, out)
        assertEquals(expected.n, out.n)
        for (i in 0 until out.n) {
            assertEquals(expected.u[i].toBits(), out.u[i].toBits())
            assertEquals(expected.v[i].toBits(), out.v[i].toBits())
            assertEquals(expected.level[i], out.level[i])
        }
    }

    @Test fun nestedOverlapsAndStrictStartCutoff() {
        val t = track(40)
        val set = requireNotNull(SpanSet.of(t, listOf(
            CongestionSpan(0, 35, CongestionLevel.SLOW),
            CongestionSpan(5, 10, CongestionLevel.JAM),
            CongestionSpan(7, 8, CongestionLevel.JAM),
            CongestionSpan(10, 15, CongestionLevel.JAM),
            CongestionSpan(10, 12, CongestionLevel.SLOW),
            CongestionSpan(15, 20, CongestionLevel.SLOW),
            CongestionSpan(25, 22, CongestionLevel.JAM),
            CongestionSpan(5, 10, CongestionLevel.JAM)
        )))
        val builder = PieceBuilder()
        val out = Pieces()
        for (start in listOf(-10.0, t.cum[5], t.cum[9], t.cum[16], t.length)) {
            for (end in listOf(t.cum[10], t.cum[20], t.length, t.length + 10.0)) {
                for (before in listOf(Double.NaN, Double.NEGATIVE_INFINITY, t.cum[5], t.cum[10], t.cum[10] + 1e-9, Double.POSITIVE_INFINITY)) {
                    compare(builder, out, set, start, end, before)
                }
            }
        }
    }

    @Test fun randomizedSpansAndReusableOutput() {
        val random = Random(51)
        val t = track(256)
        val builder = PieceBuilder()
        val out = Pieces()
        repeat(5000) {
            val spans = List(random.nextInt(1, 100)) {
                CongestionSpan(random.nextInt(-10, 266), random.nextInt(-10, 266),
                    if (random.nextBoolean()) CongestionLevel.SLOW else CongestionLevel.JAM)
            }
            val set = SpanSet.of(t, spans) ?: return@repeat
            val x0 = t.cum[random.nextInt(t.size)] + random.nextDouble(-5.0, 5.0)
            val x1 = t.cum[random.nextInt(t.size)] + random.nextDouble(-5.0, 5.0)
            val before = when (random.nextInt(5)) {
                0 -> Double.NaN
                1 -> Double.POSITIVE_INFINITY
                else -> t.cum[random.nextInt(t.size)]
            }
            compare(builder, out, set, minOf(x0, x1), maxOf(x0, x1), before)
        }
    }

    @Test fun denseBoundariesAndDegenerateRanges() {
        val t = track(4097)
        val set = requireNotNull(SpanSet.of(t, List(2048) { i ->
            CongestionSpan(i, i + 2048, if (i % 3 == 0) CongestionLevel.JAM else CongestionLevel.SLOW)
        }))
        val builder = PieceBuilder()
        val out = Pieces()
        compare(builder, out, set, 0.0, t.length, Double.POSITIVE_INFINITY)
        compare(builder, out, set, t.cum[512], t.cum[3072], t.cum[1024])
        compare(builder, out, set, 1.0, 1.0 + 1e-9, Double.POSITIVE_INFINITY)
        compare(builder, out, set, Double.NaN, t.length, Double.POSITIVE_INFINITY)
        compare(builder, out, set, 2.0, 1.0, Double.POSITIVE_INFINITY)
        compare(builder, out, set, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
    }
}
