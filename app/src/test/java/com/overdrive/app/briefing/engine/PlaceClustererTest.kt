package com.overdrive.app.briefing.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

class PlaceClustererTest {
    // Independent quadratic reference for small, valid coordinate fixtures.
    private fun check(lat: DoubleArray, lng: DoubleArray, radius: Double) {
        val n = lat.size
        val near = Array(n) { a -> BooleanArray(n) { b ->
            haversineM(lat[a], lng[a], lat[b], lng[b]) < radius
        } }
        val density = IntArray(n) { a -> near[a].count { it } }
        val rank = (0 until n).sortedWith(compareByDescending<Int> { density[it] }.thenBy { it })
        val assign = IntArray(n) { -1 }
        val leaders = ArrayList<Int>()
        for (a in rank) {
            if (assign[a] >= 0) continue
            val place = leaders.size
            leaders.add(a)
            assign[a] = place
            for (b in 0 until n) if (assign[b] < 0 && near[a][b]) assign[b] = place
        }
        fun wrap(x: Double): Double = when {
            x >= 180.0 -> x - 360.0
            x < -180.0 -> x + 360.0
            else -> x
        }
        val sumLat = DoubleArray(leaders.size)
        val sumLng = DoubleArray(leaders.size)
        val visits = IntArray(leaders.size)
        for (i in 0 until n) {
            val p = assign[i]
            val leader = leaders[p]
            sumLat[p] += lat[i] - lat[leader]
            sumLng[p] += wrap(lng[i] - lng[leader])
            visits[p]++
        }
        val actual = PlaceClusterer.cluster(lat, lng, radius)
        assertArrayEquals(assign, actual.assign)
        assertArrayEquals(visits, actual.visits)
        assertArrayEquals(DoubleArray(leaders.size) { lat[leaders[it]] + sumLat[it] / visits[it] }, actual.lat, 0.0)
        assertArrayEquals(DoubleArray(leaders.size) { wrap(lng[leaders[it]] + sumLng[it] / visits[it]) }, actual.lng, 0.0)
    }

    @Test fun denseAndSeparatedSubtrees() {
        check(DoubleArray(0), DoubleArray(0), 100.0)
        check(DoubleArray(257) { 47.0 }, DoubleArray(257) { 8.0 }, 100.0)
        check(DoubleArray(256) { 47.0 + (it % 4) * 0.01 }, DoubleArray(256) { 8.0 }, 100.0)
        check(DoubleArray(129) { 0.1 }, DoubleArray(129) { if (it % 2 == 0) 179.9999 else -179.9999 }, 100.0)
        check(DoubleArray(33) { 47.0 }, DoubleArray(33) { 8.0 }, 0.0)
    }

    @Test fun overlappingPlacesAndPartialClaims() {
        val random = Random(42)
        repeat(400) {
            val n = random.nextInt(1, 150)
            val lat = DoubleArray(n) { 47.0 + random.nextInt(6) * 0.0005 + random.nextDouble() * 0.0003 }
            val lng = DoubleArray(n) { 8.0 + random.nextInt(6) * 0.0005 + random.nextDouble() * 0.0003 }
            check(lat, lng, listOf(10.0, 40.0, 100.0, 250.0, 1000.0)[it % 5])
        }
    }
}
