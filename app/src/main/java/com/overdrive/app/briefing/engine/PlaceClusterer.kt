package com.overdrive.app.briefing.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Places found by clustering points; [assign] maps each input point to its place index. */
internal class PlaceClusters(
    val assign: IntArray,
    val lat: DoubleArray,
    val lng: DoubleArray,
    val visits: IntArray,
) {
    val size: Int get() = lat.size
}

/**
 * Leader clustering, densest point first: in order of how many points lie within the radius of it
 * (ties by input index), each unassigned point claims every unassigned point within the radius.
 * Place centroid = mean of its members.
 */
internal object PlaceClusterer {

    fun cluster(lat: DoubleArray, lng: DoubleArray, radiusM: Double): PlaceClusters {
        val m = lat.size
        if (m == 0) return PlaceClusters(IntArray(0), DoubleArray(0), DoubleArray(0), IntArray(0))
        val tree = PointTree(lat, lng, radiusM)

        val density = IntArray(m)
        var maxDensity = 0
        for (k in 0 until m) {
            density[k] = tree.countNear(k)
            if (density[k] > maxDensity) maxDensity = density[k]
        }
        val rank = LongArray(m) { ((maxDensity - density[it]).toLong() shl 32) or it.toLong() }
        rank.sort()

        val assign = IntArray(m) { -1 }
        val leaders = IntArray(m)
        var places = 0
        for (r in rank) {
            val k = (r and 0xFFFF_FFFFL).toInt()
            if (assign[k] >= 0) continue
            val p = places++
            leaders[p] = k
            tree.claim(k, p, assign)
        }

        // Offsets from the leader keep the mean right across the antimeridian.
        val sLat = DoubleArray(places)
        val sLng = DoubleArray(places)
        val visits = IntArray(places)
        for (k in 0 until m) {
            val p = assign[k]
            val l = leaders[p]
            sLat[p] += lat[k] - lat[l]
            sLng[p] += wrap180(lng[k] - lng[l])
            visits[p]++
        }
        val cLat = DoubleArray(places) { lat[leaders[it]] + sLat[it] / visits[it] }
        val cLng = DoubleArray(places) { wrap180(lng[leaders[it]] + sLng[it] / visits[it]) }
        return PlaceClusters(assign, cLat, cLng, visits)
    }

    private fun wrap180(x: Double): Double = when {
        x >= 180.0 -> x - 360.0
        x < -180.0 -> x + 360.0
        else -> x
    }
}

/**
 * Points as unit vectors in a k-d tree with a bounding box per node. A radius query takes a whole
 * subtree when its box is inside (or outside) the radius, so dense places cost about as much as
 * sparse ones. Every pair gets the same answer as `haversineM(a, b) < radiusM`.
 */
private class PointTree(private val lat: DoubleArray, private val lng: DoubleArray, private val radiusM: Double) {
    private val m = lat.size
    private val x = DoubleArray(m)
    private val y = DoubleArray(m)
    private val z = DoubleArray(m)

    /** Squared chord bounds: below [lo2] is surely inside the radius, above [hi2] surely outside. */
    private val lo2: Double
    private val hi2: Double

    private val idx = IntArray(m) { it }
    private val cap = m / 2 + 2
    private val from = IntArray(cap)
    private val to = IntArray(cap)
    private val left = IntArray(cap)
    private val right = IntArray(cap)
    private val parent = IntArray(cap)
    private val box = DoubleArray(cap * 6)
    private val unclaimed = IntArray(cap)
    private val leafOf = IntArray(m)
    private val stack = IntArray(64)
    private var nodes = 0

    init {
        for (k in 0 until m) {
            val p = Math.toRadians(lat[k])
            val l = Math.toRadians(lng[k])
            val cp = cos(p)
            x[k] = cp * cos(l)
            y[k] = cp * sin(l)
            z[k] = sin(p)
        }
        if (radiusM > 0) {
            // Chord for the radius; over half the globe means everything is in.
            val chord = if (radiusM >= PI * EARTH_R) 3.0 else 2 * sin(radiusM / (2 * EARTH_R))
            lo2 = sq(chord * (1 - 1e-9))
            hi2 = sq(chord * (1 + 1e-9))
        } else {
            lo2 = -1.0
            hi2 = -1.0
        }
        build(0, m, -1)
        for (nd in 0 until nodes) unclaimed[nd] = to[nd] - from[nd]
    }

    /** Points within the radius of point [k], itself included. */
    fun countNear(k: Int): Int {
        var n = 0
        var sp = 0
        stack[sp++] = 0
        while (sp > 0) {
            val nd = stack[--sp]
            val b = nd * 6
            val mn2 = minDist2(b, k)
            if (mn2 > hi2) continue
            if (maxDist2(b, k) < lo2) {
                n += to[nd] - from[nd]
            } else if (left[nd] < 0) {
                for (t in from[nd] until to[nd]) if (near(k, idx[t])) n++
            } else {
                stack[sp++] = left[nd]
                stack[sp++] = right[nd]
            }
        }
        return n
    }

    /** Gives [place] to point [k] and every unassigned point within the radius of it. */
    fun claim(k: Int, place: Int, assign: IntArray) {
        assign[k] = place
        consume(k)
        var sp = 0
        stack[sp++] = 0
        while (sp > 0) {
            val nd = stack[--sp]
            if (unclaimed[nd] == 0) continue
            val b = nd * 6
            if (minDist2(b, k) > hi2) continue
            val all = maxDist2(b, k) < lo2
            if (all || left[nd] < 0) {
                for (t in from[nd] until to[nd]) {
                    val q = idx[t]
                    if (assign[q] < 0 && (all || near(k, q))) {
                        assign[q] = place
                        consume(q)
                    }
                }
            } else {
                stack[sp++] = left[nd]
                stack[sp++] = right[nd]
            }
        }
    }

    private fun near(a: Int, b: Int): Boolean {
        val dx = x[a] - x[b]
        val dy = y[a] - y[b]
        val dz = z[a] - z[b]
        val c2 = dx * dx + dy * dy + dz * dz
        if (c2 < lo2) return true
        if (c2 > hi2) return false
        return haversineM(lat[a], lng[a], lat[b], lng[b]) < radiusM
    }

    private fun consume(q: Int) {
        var nd = leafOf[q]
        while (nd >= 0) {
            unclaimed[nd]--
            nd = parent[nd]
        }
    }

    private fun minDist2(b: Int, k: Int): Double =
        sq(gap(x[k], box[b], box[b + 1])) + sq(gap(y[k], box[b + 2], box[b + 3])) + sq(gap(z[k], box[b + 4], box[b + 5]))

    private fun maxDist2(b: Int, k: Int): Double =
        sq(max(x[k] - box[b], box[b + 1] - x[k])) + sq(max(y[k] - box[b + 2], box[b + 3] - y[k])) +
            sq(max(z[k] - box[b + 4], box[b + 5] - z[k]))

    private fun gap(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo - v else if (v > hi) v - hi else 0.0

    private fun build(start: Int, end: Int, up: Int): Int {
        val nd = nodes++
        from[nd] = start
        to[nd] = end
        parent[nd] = up
        val b = nd * 6
        box[b] = Double.MAX_VALUE; box[b + 1] = -Double.MAX_VALUE
        box[b + 2] = Double.MAX_VALUE; box[b + 3] = -Double.MAX_VALUE
        box[b + 4] = Double.MAX_VALUE; box[b + 5] = -Double.MAX_VALUE
        for (t in start until end) {
            val p = idx[t]
            if (x[p] < box[b]) box[b] = x[p]
            if (x[p] > box[b + 1]) box[b + 1] = x[p]
            if (y[p] < box[b + 2]) box[b + 2] = y[p]
            if (y[p] > box[b + 3]) box[b + 3] = y[p]
            if (z[p] < box[b + 4]) box[b + 4] = z[p]
            if (z[p] > box[b + 5]) box[b + 5] = z[p]
        }
        if (end - start <= LEAF) {
            left[nd] = -1
            right[nd] = -1
            for (t in start until end) leafOf[idx[t]] = nd
            return nd
        }
        val ex = box[b + 1] - box[b]
        val ey = box[b + 3] - box[b + 2]
        val ez = box[b + 5] - box[b + 4]
        val c = if (ex >= ey && ex >= ez) x else if (ey >= ez) y else z
        val mid = (start + end) ushr 1
        select(start, end - 1, mid, c)
        left[nd] = build(start, mid, nd)
        right[nd] = build(mid, end, nd)
        return nd
    }

    /** Partial sort of idx[l..r] so that position [k] holds its rank-k element by [c]. */
    private fun select(l0: Int, r0: Int, k: Int, c: DoubleArray) {
        var l = l0
        var r = r0
        while (r > l) {
            val pivot = c[idx[(l + r) ushr 1]]
            var i = l
            var j = r
            while (i <= j) {
                while (c[idx[i]] < pivot) i++
                while (c[idx[j]] > pivot) j--
                if (i <= j) {
                    val s = idx[i]
                    idx[i] = idx[j]
                    idx[j] = s
                    i++
                    j--
                }
            }
            if (k <= j) r = j else if (k >= i) l = i else return
        }
    }

    private fun sq(v: Double) = v * v

    private companion object {
        const val LEAF = 8
    }
}
