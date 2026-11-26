package com.overdrive.app.ui.timeline

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

class StripIntervalsTest {
    private fun check(expected: List<Pair<Long, Long>>, actual: Pair<LongArray, LongArray>) {
        assertArrayEquals(expected.map { it.first }.toLongArray(), actual.first)
        assertArrayEquals(expected.map { it.second }.toLongArray(), actual.second)
    }

    @Test fun mergeSortedAndUnsortedWithDuplicateStartsAndUnusedTail() {
        val random = Random(4021)
        repeat(1000) { case ->
            val n = random.nextInt(120)
            val starts = LongArray(n + 3) { random.nextLong(-30, 100) }
            if (case % 2 == 0) starts.sort(0, n)
            val ends = LongArray(n + 3) { starts[it] + random.nextLong(-5, 20) }
            val tolerance = random.nextLong(0, 5)
            val expected = mutableListOf<Pair<Long, Long>>()
            for (i in (0 until n).sortedBy { starts[it] }) {
                val s = starts[i]
                val e = maxOf(s, ends[i])
                val last = expected.lastOrNull()
                if (last != null && s <= last.second + tolerance) {
                    expected[expected.lastIndex] = last.first to maxOf(last.second, e)
                } else expected.add(s to e)
            }
            check(expected, Intervals.merge(starts, ends, n, tolerance))
        }
    }

    @Test fun intervalOperationsMatchDiscreteCoverage() {
        val random = Random(4022)
        fun runs(bits: BooleanArray): List<Pair<Long, Long>> {
            val result = mutableListOf<Pair<Long, Long>>()
            var i = 0
            while (i < bits.size) {
                if (!bits[i]) { i++; continue }
                val start = i++
                while (i < bits.size && bits[i]) i++
                result.add(start.toLong() to i.toLong())
            }
            return result
        }
        repeat(1000) {
            val a = BooleanArray(256) { random.nextBoolean() }
            val b = BooleanArray(256) { random.nextBoolean() }
            val ar = runs(a)
            val br = runs(b)
            val aS = ar.map { it.first }.toLongArray()
            val aE = ar.map { it.second }.toLongArray()
            val bS = br.map { it.first }.toLongArray()
            val bE = br.map { it.second }.toLongArray()
            val lo = random.nextInt(128)
            val hi = random.nextInt(lo, 257)
            val minLen = random.nextLong(1, 8)
            check(runs(BooleanArray(256) { a[it] && !b[it] }), Intervals.subtract(aS, aE, bS, bE))
            check(runs(BooleanArray(256) { a[it] && it in lo until hi }), Intervals.clip(aS, aE, lo.toLong(), hi.toLong()))
            check(runs(BooleanArray(256) { !a[it] && it in lo until hi }).filter { it.second - it.first >= minLen },
                Intervals.complement(aS, aE, lo.toLong(), hi.toLong(), minLen))
        }
    }

    @Test fun largeOutputsAndEmptyResults() {
        val starts = LongArray(4096) { 1_700_000_000_000L + it * 4L }
        val ends = LongArray(starts.size) { starts[it] + 2L }
        val expected = starts.indices.map { starts[it] to ends[it] }
        check(expected, Intervals.merge(starts, ends, starts.size, 0))
        check(expected, Intervals.subtract(starts, ends, LongArray(0), LongArray(0)))
        check(expected, Intervals.clip(starts, ends, starts.first(), ends.last()))
        check(emptyList(), Intervals.clip(starts, ends, 0, 1))
        check(emptyList(), Intervals.subtract(starts, ends, starts, ends))
        check(emptyList(), Intervals.merge(starts, ends, 0, 0))
        check(emptyList(), Intervals.complement(starts, ends, starts.first(), starts.first(), 1))
    }
}
