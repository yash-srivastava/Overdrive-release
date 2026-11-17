package com.overdrive.app.ui.timeline.player

import com.overdrive.app.ui.timeline.ClipSpec

/** How an epoch's first frame follows the frame shown before it. */
enum class Join { START, GAPLESS, GAP, RESTART }

/**
 * One plan item as fed into one decoder. Tags are the decoder PTS values (unique and increasing
 * per decoder); media time is the continuous playback timeline on which joined items abut.
 */
class Epoch(
    val gen: Int,
    val item: PlanItem,
    val tagBase: Long,
    val firstClipUs: Long,
    val mediaStartUs: Long,
    val join: Join,
    val gapFromWall: Long,
    val spec: ClipSpec?,
    val crossfade: Boolean,
    val scrub: Boolean = false,
) {
    val seg get() = item.seg
    val offsetClipUs: Long = (item.playFrom - item.seg.start) * 1000L
    val endClipUs: Long = (item.playTo - item.seg.start) * 1000L
    val plannedMediaEndUs: Long = mediaStartUs + (endClipUs - offsetClipUs)

    @Volatile var maxTag: Long = tagBase
    @Volatile var cutMediaUs: Long = Long.MAX_VALUE
    /** Clip time of a precise-seek target; frames before it are decoded but not shown. */
    @Volatile var targetClipUs: Long = NO_TARGET
    /** False: show the sync frame itself and move the clock to it. */
    @Volatile var targetPrecise: Boolean = true

    private val modeLock = Any()
    private var modeTags = LongArray(4)
    private var modeFast = BooleanArray(4)
    private var modeCount = 0

    fun clipUsOfTag(tag: Long): Long = tag - tagBase + firstClipUs
    fun tagOfClipUs(clipUs: Long): Long = tagBase + (clipUs - firstClipUs)
    fun mediaUsOfClipUs(clipUs: Long): Long = mediaStartUs + (clipUs - offsetClipUs)
    fun mediaUsOfTag(tag: Long): Long = mediaUsOfClipUs(clipUsOfTag(tag))

    fun wallOfMediaUs(us: Long): Long {
        val w = item.playFrom + (us - mediaStartUs) / 1000L
        return w.coerceIn(item.playFrom, item.playTo)
    }

    fun mediaUsOfWall(wall: Long): Long = mediaStartUs + (wall - item.playFrom) * 1000L

    /** Records that frames from [tag] on belong to a quiet stretch that skip quiet plays at the fast speed, or not. */
    fun markFast(tag: Long, fast: Boolean) {
        synchronized(modeLock) {
            if (modeCount > 0 && modeFast[modeCount - 1] == fast) return
            if (modeCount == modeTags.size) {
                modeTags = modeTags.copyOf(modeCount * 2)
                modeFast = modeFast.copyOf(modeCount * 2)
            }
            modeTags[modeCount] = tag
            modeFast[modeCount] = fast
            modeCount++
        }
    }

    companion object {
        const val NO_TARGET = Long.MIN_VALUE
    }

    fun fastAt(tag: Long): Boolean {
        synchronized(modeLock) {
            var ans = false
            for (i in 0 until modeCount) {
                if (modeTags[i] <= tag) ans = modeFast[i] else break
            }
            return ans
        }
    }
}

/** Append-only epoch list of one decoder generation, readable from any thread. */
class EpochLog(val gen: Int) {
    @Volatile private var arr: Array<Epoch> = emptyArray()

    fun add(e: Epoch) {
        synchronized(this) { arr = arr + e }
    }

    fun isEmpty(): Boolean = arr.isEmpty()
    fun first(): Epoch? = arr.firstOrNull()
    fun last(): Epoch? = arr.lastOrNull()

    fun forTag(tag: Long): Epoch? {
        val a = arr
        var ans: Epoch? = null
        for (e in a) {
            if (e.tagBase <= tag) ans = e else break
        }
        return ans
    }

    fun atMedia(us: Long): Epoch? {
        val a = arr
        if (a.isEmpty()) return null
        var ans = a[0]
        for (e in a) {
            if (e.mediaStartUs <= us) ans = e else break
        }
        return ans
    }

    fun after(e: Epoch): Epoch? {
        val a = arr
        for (i in a.indices) if (a[i] === e) return if (i + 1 < a.size) a[i + 1] else null
        return null
    }

    fun mediaEndOf(e: Epoch): Long {
        var end = minOf(e.plannedMediaEndUs, e.cutMediaUs)
        val n = after(e)
        if (n != null) end = minOf(end, n.mediaStartUs)
        return end
    }

    fun wallAtMedia(us: Long): Long? {
        val e = atMedia(us) ?: return null
        return e.wallOfMediaUs(minOf(us, mediaEndOf(e)))
    }
}

/** Share of frames that missed their render slot over a sliding window. */
class LateFrameMeter(private val windowNs: Long = 2_000_000_000L) {
    private val times = LongArray(512)
    private val late = BooleanArray(512)
    private var head = 0
    private var size = 0

    @Synchronized fun add(nowNs: Long, wasLate: Boolean) {
        val i = (head + size) % times.size
        times[i] = nowNs
        late[i] = wasLate
        if (size < times.size) size++ else head = (head + 1) % times.size
    }

    @Synchronized fun reset() {
        head = 0
        size = 0
    }

    @Synchronized fun count(nowNs: Long): Int {
        trim(nowNs)
        return size
    }

    @Synchronized fun ratio(nowNs: Long): Float {
        trim(nowNs)
        if (size == 0) return 0f
        var n = 0
        for (k in 0 until size) if (late[(head + k) % times.size]) n++
        return n.toFloat() / size
    }

    /** Ratio over a window that is at least [minSpanNs] long, else -1. */
    @Synchronized fun ratioIfSpanned(nowNs: Long, minSpanNs: Long): Float {
        trim(nowNs)
        if (size == 0 || nowNs - times[head] < minSpanNs) return -1f
        var n = 0
        for (k in 0 until size) if (late[(head + k) % times.size]) n++
        return n.toFloat() / size
    }

    private fun trim(nowNs: Long) {
        while (size > 0 && nowNs - times[head] > windowNs) {
            head = (head + 1) % times.size
            size--
        }
    }
}
