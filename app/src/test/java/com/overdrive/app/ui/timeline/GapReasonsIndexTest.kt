package com.overdrive.app.ui.timeline

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

class GapReasonsIndexTest {
    private fun data(
        states: List<TlState> = emptyList(), sessions: List<TlSession> = emptyList(),
        trips: List<TlTrip> = emptyList(), reaped: List<TlReaped> = emptyList(), now: Long = 20_000,
    ) = StripData(emptyList(), emptyList(), sessions, trips, states, reaped, emptyList(),
        emptyList(), now, 0, now, "UTC", emptyList())

    private fun state(s: Long, e: Long?) = TlState(s, e, "storage", "off", "storage_full")
    private fun session(s: Long, e: Long?, kind: String = "surveillance_off") =
        TlSession("session", s, e, null, null, kind)
    private fun trip(s: Long, e: Long?) = TlTrip(s, s, e, null)

    /** Brute force, including the original reaped start-order tie break. */
    private fun reference(d: StripData, gs: Long, ge: Long): Pair<Int, Int> {
        val need = (ge - gs) * GapReasons.MIN_SHARE
        fun <T> winner(entries: List<T>, order: Iterable<Int>, start: (T) -> Long, end: (T) -> Long): Int {
            var sum = 0L
            var max = 0L
            var best = -1
            for (i in order) {
                val e = entries[i]
                val ov = maxOf(0L, minOf(ge, end(e)) - maxOf(gs, start(e)))
                sum += ov
                if (ov > max) { max = ov; best = i }
            }
            return if (sum >= need) best else -1
        }
        val r = winner(d.reaped, d.reaped.indices.sortedBy { d.reaped[it].start }, { it.start }, { it.end })
        if (r >= 0) return GapReasons.REAPED to r
        val s = winner(d.states, d.states.indices, { it.start }, { it.end ?: d.now })
        if (s >= 0) return GapReasons.STATE to s
        val unarmed = setOf("surveillance_off", "vehicle_on_only", "suppressed_safe_zone", "suppressed_schedule", "pipeline_down", "lock_wait")
        val p = winner(d.sessions, d.sessions.indices.filter { d.sessions[it].sentry in unarmed }, { it.start }, { it.end ?: d.now })
        if (p >= 0) return GapReasons.SESSION to p
        val t = winner(d.trips, d.trips.indices, { it.start }, { it.end ?: d.now })
        return if (t >= 0) GapReasons.TRIP to t else GapReasons.NONE to -1
    }

    private fun check(d: StripData, starts: LongArray, ends: LongArray) {
        val n = starts.size
        val sources = IntArray(n + 1) { -99 }
        val indices = IntArray(n + 1) { -99 }
        GapReasons.classify(d, starts, ends, n, sources, indices)
        val expected = starts.indices.map { reference(d, starts[it], ends[it]) }
        assertArrayEquals(IntArray(n + 1) { if (it == n) -99 else expected[it].first }, sources)
        assertArrayEquals(IntArray(n + 1) { if (it == n) -99 else expected[it].second }, indices)
    }

    @Test fun tiesAndCoverageAcrossTiers() {
        val d = data(
            states = listOf(state(50, 100), state(0, 50), state(200, 230), state(200, 230)) +
                List(32) { state(12_000L + it, 12_000L + it) },
            sessions = listOf(session(350, 400), session(300, 350), session(400, 500, "armed")) +
                List(32) { session(13_000L + it, 13_000L + it) },
            trips = listOf(trip(450, 500), trip(400, 450)) + List(32) { trip(14_000L + it, 14_000L + it) },
            reaped = listOf(TlReaped(0, 100, "normal", "user")),
        )
        val starts = longArrayOf(0, 200, 300, 400, 500, 600, 700, 800, 900)
        val ends = LongArray(starts.size) { starts[it] + 100 }
        check(d, starts, ends)
        check(d.copy(reaped = emptyList()), starts, ends)
        // Below the index cutoffs the same ordering and coverage rules still apply.
        check(d.copy(reaped = emptyList()), starts.copyOf(4), ends.copyOf(4))
    }

    @Test fun nestedOpenAndTouchingIntervals() {
        val d = data(states = listOf(state(0, null), state(500, 600), state(900, 800)) +
            List(40) { state(1_000L + it * 100, 1_050L + it * 100) })
        val starts = longArrayOf(0, 600, 999, 1_050, 5_000, 9_999, 19_900, 20_000, 22_000, 300)
        val ends = longArrayOf(100, 700, 1_000, 1_100, 5_100, 10_000, 20_100, 20_100, 22_000, 200)
        check(d, starts, ends)
        check(d.copy(states = d.states.reversed()), starts, ends)
        check(d.copy(states = emptyList()), starts, ends)
        check(d, LongArray(0), LongArray(0))
    }

    @Test fun randomUnsortedIntervalsAndGapsMatchLinearReference() {
        val r = Random(84221)
        repeat(400) { case ->
            fun start() = r.nextLong(0, 20_000)
            fun end(s: Long): Long? = if (r.nextInt(12) == 0) null else s + r.nextLong(-30, 300)
            val states = List(if (case % 4 == 0) 0 else r.nextInt(140)) { val s = start(); state(s, end(s)) }
            val sessions = List(if (case % 4 == 1) 0 else r.nextInt(140)) {
                val s = start(); session(s, end(s), if (r.nextBoolean()) "armed" else "lock_wait")
            }
            val trips = List(r.nextInt(140)) { val s = start(); trip(s, end(s)) }
            val reaped = List(r.nextInt(12)) { val s = start(); TlReaped(s, s + r.nextLong(0, 300), "normal", "retention") }
            val starts = LongArray(r.nextInt(0, 100)) { start() }
            val ends = LongArray(starts.size) { starts[it] + r.nextLong(0, 500) }
            check(data(states, sessions, trips, reaped), starts, ends)
        }
    }

    @Test fun reapedLongHistoryAndStartOrderTies() {
        val r = Random(5731)
        val reaped = listOf(TlReaped(0, 100_000, "normal", "user")) +
            List(4_096) { i ->
                val s = 100_000L + i * 10L
                TlReaped(s, s + 5, "normal", "retention")
            } + listOf(
                TlReaped(142_000, 142_050, "normal", "user"),
                TlReaped(141_950, 142_000, "normal", "retention"),
                TlReaped(142_000, 142_050, "normal", "relief"),
                TlReaped(143_000, 142_990, "normal", "user"),
            )
        val starts = longArrayOf(99_999, 100_000, 100_005, 100_010, 120_000,
            141_950, 142_000, 142_050, 143_000, 143_001, 140_000, 0)
        val ends = LongArray(starts.size) { starts[it] + if (it == 5) 100 else 10 }
        val d = data(reaped = reaped.shuffled(r))
        check(d, starts, ends)
        check(d, starts.copyOf(7), ends.copyOf(7)) // small-window fallback
        check(d.copy(reaped = d.reaped.take(31)), starts, ends)
    }

    @Test fun randomizedReapedPrefixIndexMatchesReference() {
        val r = Random(5732)
        repeat(300) {
            val reaped = List(r.nextInt(32, 180)) {
                val start = r.nextLong(0, 100_000)
                val end = start + if (r.nextInt(12) == 0) r.nextLong(0, 80_000)
                    else r.nextLong(-20, 400)
                TlReaped(start, end, "normal", "retention")
            }.shuffled(r)
            val starts = LongArray(r.nextInt(8, 100)) { r.nextLong(0, 200_000) }
            val ends = LongArray(starts.size) { starts[it] + r.nextLong(-10, 1_000) }
            check(data(reaped = reaped), starts, ends)
        }
    }
}
