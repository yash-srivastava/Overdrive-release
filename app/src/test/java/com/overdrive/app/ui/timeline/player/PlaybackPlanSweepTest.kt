package com.overdrive.app.ui.timeline.player

import com.overdrive.app.ui.timeline.TlSegment
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class PlaybackPlanSweepTest {
    private fun clip(id: String, start: Long, end: Long?, type: String = "normal", available: Boolean = true,
                     layout: String? = null) = TlSegment(
        id, type, start, end, "", available, null, 0, layout, 0, 0, id, "", "", "", null,
        emptyList(), emptyMap(), null, null, null, null, false,
    )

    /** Independent boundary-by-boundary scan; does not use the production sweep. */
    private fun reference(input: List<TlSegment>, prefer: String?): List<PlanItem> {
        val pref = PlaybackPlan.normalizePrefer(prefer)
        val clips = input.filter { PlaybackPlan.playable(it) }.distinctBy { it.id }
        val boundaries = clips.flatMap { listOf(it.start, it.end!!) }.distinct().sorted()
        val priority = compareBy<TlSegment>({ PlaybackPlan.rank(it, pref) }, { it.start }, { it.id })
        val out = ArrayList<PlanItem>()
        for (i in 1 until boundaries.size) {
            val a = boundaries[i - 1]
            val b = boundaries[i]
            val active = clips.filter { it.start <= a && it.end!! > a }
            val best = active.minWithOrNull(priority) ?: continue
            val seam = active.filter {
                it.start < best.start && it.end!! > best.start && it.end - best.start <= PlaybackPlan.SEAM_MS &&
                    PlaybackPlan.oem(it) == PlaybackPlan.oem(best)
            }.minWithOrNull(priority)
            val winner = seam ?: best
            val last = out.lastOrNull()
            if (last != null && last.seg == winner && last.playTo == a) {
                out[out.lastIndex] = last.copy(playTo = b)
            } else {
                out.add(PlanItem(winner, a, b))
            }
        }
        return PlaybackPlan.dropSlivers(out)
    }

    @Test fun sharedBoundariesAndFiltering() {
        val clips = listOf(clip("a", 0, 1000), clip("b", 1000, 2000), clip("b", 2000, 3000),
            clip("zero", 2000, 2000), clip("missing", 0, null), clip("offline", 0, 3000, available = false),
            clip("c", 3000, 4000))
        val plan = PlaybackPlan.build(clips, null)
        assertEquals(listOf(PlanItem(clips[0], 0, 1000), PlanItem(clips[1], 1000, 2000), PlanItem(clips[6], 3000, 4000)), plan.items)
        assertEquals(-1, plan.indexAt(2000))
        assertEquals(2, plan.indexFrom(2000))
        assertEquals(emptyList<PlanItem>(), PlaybackPlan.build(emptyList(), null).items)
    }

    @Test fun growsAndCompactsActiveSetWithNestedClips() {
        val clips = List(100) { clip("nested-$it", it * 500L, 100_000L - it * 500L, if (it % 2 == 0) "sentry" else "normal") } +
            List(100) { clip("later-$it", 100_000L + it * 1000L, 101_000L + it * 1000L, "normal") }
        for (pref in listOf(null, "sentry", "OEM")) {
            assertEquals(reference(clips, pref), PlaybackPlan.build(clips, pref).items)
        }
    }

    @Test fun randomizedSweepMatchesReference() {
        val random = Random(38107)
        val types = listOf("normal", "sentry", "proximity", "oemDashcam", "replay", "other")
        val preferences = listOf(null, "MOSAIC", "OEM", "sentry", "replay")
        repeat(1000) {
            val clips = List(random.nextInt(0, 100)) { i ->
                val start = random.nextInt(-10, 100) * 250L
                clip("clip-${random.nextInt(0, i + 1)}", start,
                    if (random.nextInt(10) == 0) null else start + random.nextInt(-1, 50) * 250L,
                    types.random(random), random.nextInt(10) != 0, if (random.nextInt(8) == 0) "single" else null)
            }.shuffled(random)
            for (pref in preferences) assertEquals(reference(clips, pref), PlaybackPlan.build(clips, pref).items)
        }
    }
}
