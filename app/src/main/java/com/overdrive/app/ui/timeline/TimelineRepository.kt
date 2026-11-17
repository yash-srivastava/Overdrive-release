package com.overdrive.app.ui.timeline

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Day-chunked cache over /api/timeline. Main-thread confined: one worker runs
 * the fetches (visible days first), results and listener calls land on main.
 */
class TimelineRepository(private val fetcher: Fetcher = DefaultFetcher) {

    interface Fetcher {
        fun window(from: Long, to: Long): TimelineApiClient.WindowResult
        fun days(from: Long, to: Long): List<TlDay>?
    }

    object DefaultFetcher : Fetcher {
        override fun window(from: Long, to: Long): TimelineApiClient.WindowResult =
            TimelineApiClient.fetchWindow(from, to)

        override fun days(from: Long, to: Long): List<TlDay>? = TimelineApiClient.fetchDays(from, to)
    }

    interface Listener {
        fun onTimelineData(snapshot: Snapshot)
        fun onTimelineState(state: LoadState)
        /** The day list (footage, events, alerts per day) changed. */
        fun onTimelineDays() {}
    }

    enum class LoadState { LOADING, READY, OFFLINE, ERROR }

    class Snapshot(
        val segments: List<TlSegment>,
        val marks: List<TlMark>,
        val sessions: List<TlSession>,
        val trips: List<TlTrip>,
        val states: List<TlState>,
        val reaped: List<TlReaped>,
        val road: List<TlRoad>,
        val live: List<TlLive>,
        val tzId: String,
        val minMs: Long,
        val loadedDays: Set<Long>,
        val truncated: Boolean,
    )

    private class DayEntry(val window: TlWindow, val fetchedAt: Long)

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TimelineRepo").apply { isDaemon = true }
    }
    private val days = LinkedHashMap<Long, DayEntry>(16, 0.75f, true)
    private val pending = ArrayList<Long>()
    private val failedAt = HashMap<Long, Long>()
    private var inflightDay: Long? = null
    private var listener: Listener? = null
    private var released = false
    private var active = false
    private var tz: TimeZone = TimeZone.getDefault()
    private var visibleFrom = 0L
    private var visibleTo = 0L
    private var focusMs = 0L
    // The strip's last range as asked, before clamping to now: the live tick re-applies it.
    private var askedFrom = 0L
    private var askedTo = 0L
    // Days the day list says have footage, and the one loaded past the neighbours so playing on never skips it.
    private var footageDays: List<Long> = emptyList()
    // The day list as read, and keyed by day start in the current zone (re-keyed if the zone changes).
    private var dayList: List<TlDay>? = null
    private var dayStatsCache: Map<Long, TlDay>? = null
    private var dayStatsTz: TimeZone? = null
    private var prefetchDay = 0L
    private var visibleDayList: List<Long> = emptyList()
    private var visibleDayEnd = 0L
    private var computedMin: Long = TimelineDays.dayStart(System.currentTimeMillis() - FALLBACK_HISTORY_MS, tz)
    private var explicitMin: Long = Long.MAX_VALUE
    private var daysState = DAYS_IDLE
    private var lastFailure: TimelineApiClient.WindowResult.Failed? = null
    private var retryAttempt = 0
    private var state = LoadState.LOADING
    private var snapshot: Snapshot? = null

    private val retryRunnable = Runnable { retryInternal(false) }
    private var liveTicks = 0
    private val refetchAfterFlight = HashSet<Long>()
    private val refreshAtLast = HashMap<Long, Long>()
    private val liveRunnable = object : Runnable {
        override fun run() {
            if (released || !active) return
            // A still strip across midnight asks for nothing new: its range now reaches the new day.
            if (askedTo > 0L) reapplyRange()
            // The next day with footage again (a failed or evicted prefetch comes back here).
            prefetchNextFootage(System.currentTimeMillis())
            // Every other tick re-reads all visible days: a card pulled or re-inserted changes past days too.
            if (++liveTicks % 2 == 0) refreshVisible() else refreshToday()
            // The day list (earliest day, footage per day) can change too, e.g. after a re-index.
            if (liveTicks % DAYS_REPOLL_TICKS == 0 && daysState != DAYS_LOADING) loadDays()
            main.postDelayed(this, LIVE_REFRESH_MS)
        }
    }

    val currentSnapshot: Snapshot? get() = snapshot
    val loadState: LoadState get() = state
    val timeZone: TimeZone get() = tz
    val minMs: Long get() = minOf(computedMin, explicitMin)
    /** Day start to that day's footage, events and alerts (the last 61 days); null until the list loads. */
    val dayStats: Map<Long, TlDay>?
        get() {
            val raw = dayList ?: return null
            val cached = dayStatsCache
            if (cached != null && dayStatsTz === tz) return cached
            val m = LinkedHashMap<Long, TlDay>()
            for (d in raw) TimelineDays.parseDay(d.day, tz)?.let { m[it] = d }
            dayStatsCache = m
            dayStatsTz = tz
            return m
        }

    /** Re-reads the day list (e.g. a day picker opening), unless a read is already running. */
    fun refreshDays() {
        if (released || daysState == DAYS_LOADING) return
        loadDays()
    }

    fun setListener(l: Listener?) {
        listener = l
        if (l != null) {
            snapshot?.let(l::onTimelineData)
            l.onTimelineState(state)
        }
    }

    /** Resumed: refresh the live day every 30 s and keep retrying failures. */
    fun setActive(on: Boolean) {
        if (released || active == on) return
        active = on
        main.removeCallbacks(liveRunnable)
        main.removeCallbacks(retryRunnable)
        if (!on) return
        // Back after a sleep that crossed midnight: the new day joins before the re-read.
        if (askedTo > 0L) reapplyRange()
        refreshVisible()
        main.postDelayed(liveRunnable, LIVE_REFRESH_MS)
        if (lastFailure != null) main.post(retryRunnable)
    }

    /** Re-reads every visible day: loaded ones forced, failed or missing ones retried. */
    fun refreshVisible() {
        if (released) return
        for (d in visibleDayList) enqueue(d, force = days.containsKey(d))
        pump()
    }

    /** Re-reads the day holding [wallMs], e.g. after one of its clips vanished; at most every 10 s per day. */
    fun refreshAt(wallMs: Long) {
        if (released || wallMs <= 0L) return
        val day = TimelineDays.dayStart(wallMs, tz)
        val now = SystemClock.elapsedRealtime()
        val last = refreshAtLast[day]
        if (last != null && now - last < REFRESH_AT_MIN_MS) return
        refreshAtLast[day] = now
        enqueue(day, force = true)
        pump()
    }

    /** Called with the strip's visible range; loads its days, then their neighbours. */
    fun ensureLoaded(fromMs: Long, toMs: Long) {
        // Back on today after browsing: its live band may be a refresh old.
        val today = TimelineDays.dayStart(System.currentTimeMillis(), tz)
        val cached = days[today]
        if (cached != null && toMs >= today && inflightDay != today &&
            SystemClock.elapsedRealtime() - cached.fetchedAt > LIVE_REFRESH_MS
        ) {
            enqueue(today, force = true)
        }
        if (released) return
        askedFrom = fromMs
        askedTo = toMs
        val now = System.currentTimeMillis()
        val lo = minOf(fromMs, toMs).coerceAtMost(now)
        val hi = maxOf(fromMs, toMs).coerceIn(lo, now)
        visibleFrom = lo
        visibleTo = hi
        // The strip's centre is the playhead; clamping to now must not move it.
        focusMs = (minOf(fromMs, toMs) / 2 + maxOf(fromMs, toMs) / 2).coerceAtMost(now)
        if (visibleDayList.isEmpty() || lo < visibleDayList.first() || hi >= visibleDayEnd) {
            val clampedLo = maxOf(lo, focusMs - VISIBLE_HALF_RANGE_MS)
            val clampedHi = minOf(hi, focusMs + VISIBLE_HALF_RANGE_MS)
            visibleDayList = TimelineDays.daysCovering(clampedLo, clampedHi, tz, MAX_VISIBLE_DAYS)
            visibleDayEnd = visibleDayList.lastOrNull()?.let { TimelineDays.nextDayStart(it, tz) } ?: 0L
            for (d in visibleDayList) {
                days.get(d) // refreshes LRU order
                enqueue(d)
            }
            visibleDayList.firstOrNull()?.let { enqueue(TimelineDays.prevDayStart(it, tz)) }
            if (visibleDayEnd in 1..now) enqueue(visibleDayEnd)
            prefetchNextFootage(now)
            prunePending()
            pump()
            if (daysState == DAYS_IDLE) loadDays()
        }
        updateState()
    }

    /** Keeps an explicitly opened (possibly older) day navigable. */
    fun includeTime(ms: Long) {
        val d = TimelineDays.dayStart(ms, tz)
        if (d < explicitMin) explicitMin = d
    }

    fun retry() = retryInternal(true)

    fun isDayLoaded(day: Long): Boolean = days.containsKey(day)

    fun dayStartOf(ms: Long): Long = TimelineDays.dayStart(ms, tz)

    fun nextDayStart(day: Long): Long = TimelineDays.nextDayStart(day, tz)

    fun release() {
        released = true
        listener = null
        main.removeCallbacks(retryRunnable)
        main.removeCallbacks(liveRunnable)
        pending.clear()
        worker.shutdownNow()
    }

    private fun isVisible(day: Long): Boolean = day in visibleDayList

    private fun enqueue(day: Long, force: Boolean = false) {
        if (day > System.currentTimeMillis()) return
        if (!force && day < TimelineDays.dayStart(minMs, tz)) return
        if (!force && days.containsKey(day)) return
        if (inflightDay == day) {
            // That fetch may predate the change being asked for: read the day again once it lands.
            if (force) refetchAfterFlight.add(day)
            return
        }
        if (!force && !isVisible(day)) {
            val failed = failedAt[day]
            if (failed != null && SystemClock.elapsedRealtime() - failed < FAIL_COOLDOWN_MS) return
        }
        if (!pending.contains(day)) pending.add(day)
    }

    /** Drops queued neighbours that scrolled out of reach. */
    private fun prunePending() {
        if (visibleDayList.isEmpty()) return
        val lo = TimelineDays.prevDayStart(visibleDayList.first(), tz)
        val hi = visibleDayEnd
        pending.retainAll { it in lo..hi || it == prefetchDay }
    }

    /**
     * The first day with footage after the neighbour: otherwise playing on from the visible days would go
     * straight to a later cached day (e.g. today) and skip it.
     */
    private fun prefetchNextFootage(now: Long) {
        val end = visibleDayEnd
        val next = if (end > 0L) footageDays.firstOrNull { it >= end } else null
        prefetchDay = if (next != null && next != end && next <= now) next else 0L
        if (prefetchDay != 0L) enqueue(prefetchDay)
    }

    private fun pump() {
        if (released || inflightDay != null || pending.isEmpty()) return
        val pick = pending.firstOrNull { isVisible(it) } ?: pending.first()
        pending.remove(pick)
        inflightDay = pick
        val to = TimelineDays.nextDayStart(pick, tz)
        try {
            worker.execute {
                val result = try {
                    fetcher.window(pick, to)
                } catch (t: Throwable) {
                    TimelineApiClient.WindowResult.Failed(false, -1, 0L)
                }
                main.post { onWindow(pick, result) }
            }
        } catch (t: Throwable) {
            inflightDay = null
        }
    }

    private fun onWindow(day: Long, result: TimelineApiClient.WindowResult) {
        if (released) return
        if (inflightDay == day) inflightDay = null
        if (refetchAfterFlight.remove(day)) enqueue(day, force = true)
        when (result) {
            is TimelineApiClient.WindowResult.Ok -> {
                val w = result.window
                val known = TimeZone.getTimeZone(w.tzId)
                // Day keys follow the recorder's zone; re-key once if it differs.
                if (known.id == w.tzId && known.id != tz.id) {
                    tz = known
                    days.clear()
                    pending.clear()
                    failedAt.clear()
                    visibleDayList = emptyList()
                    computedMin = TimelineDays.dayStart(computedMin, tz)
                    reapplyRange()
                    return
                }
                val prev = days[day]
                days[day] = DayEntry(w, SystemClock.elapsedRealtime())
                failedAt.remove(day)
                // Another visible day that never loaded keeps its banner and retry going.
                if (visibleDayList.none { it != day && failedAt.containsKey(it) && !days.containsKey(it) }) {
                    lastFailure = null
                    retryAttempt = 0
                    main.removeCallbacks(retryRunnable)
                }
                trim()
                // A re-read that changed nothing but its clock: no rebuild, no redraw on the main thread.
                if (prev == null || prev.window.copy(now = w.now) != w) rebuild()
            }
            is TimelineApiClient.WindowResult.Failed -> {
                failedAt[day] = SystemClock.elapsedRealtime()
                if (isVisible(day) && !days.containsKey(day)) {
                    lastFailure = result
                    scheduleRetry(result)
                }
            }
        }
        updateState()
        pump()
    }

    private fun trim() {
        if (days.size <= MAX_DAYS) return
        val it = days.entries.iterator()
        while (days.size > MAX_DAYS && it.hasNext()) {
            if (it.next().key !in visibleDayList) it.remove()
        }
    }

    private fun rebuild() {
        val entries = days.values.sortedBy { it.fetchedAt }
        val segs = LinkedHashMap<String, TlSegment>()
        val marks = LinkedHashMap<String, TlMark>()
        val sessions = LinkedHashMap<String, TlSession>()
        val trips = LinkedHashMap<Long, TlTrip>()
        val states = LinkedHashMap<String, TlState>()
        val reaped = LinkedHashMap<String, TlReaped>()
        val road = LinkedHashMap<String, TlRoad>()
        var truncated = false
        // Fresher windows overwrite: a clip crossing midnight is in two days.
        for (e in entries) {
            val w = e.window
            for (s in w.segments) segs[s.id] = s
            for (m in w.marks) marks["${m.rec}|${m.t0}|${m.kind}|${m.cam}"] = m
            for (s in w.sessions) sessions[s.id] = s
            for (t in w.trips) trips[t.id] = t
            for (s in w.states) states["${s.kind}|${s.start}"] = s
            for (r in w.reaped) reaped["${r.type}|${r.start}|${r.end}"] = r
            for (r in w.road) road["${r.t}|${r.kind}"] = r
            truncated = truncated || w.truncated
        }
        val snap = Snapshot(
            segments = segs.values.sortedBy { it.start },
            marks = marks.values.sortedBy { it.t0 },
            sessions = sessions.values.sortedBy { it.start },
            trips = trips.values.sortedBy { it.start },
            states = states.values.sortedBy { it.start },
            reaped = reaped.values.sortedBy { it.start },
            road = road.values.sortedBy { it.t },
            // The newest day's window lists every recording still open (past days list none): not the last one fetched.
            live = days.maxByOrNull { it.key }?.value?.window?.live ?: emptyList(),
            tzId = tz.id,
            minMs = minMs,
            loadedDays = HashSet(days.keys),
            truncated = truncated,
        )
        snapshot = snap
        listener?.onTimelineData(snap)
    }

    // A failure sticks until a success so auto-retries don't flicker the banner.
    private fun updateState() {
        val focusDay = TimelineDays.dayStart(
            if (focusMs > 0L) focusMs else System.currentTimeMillis(), tz
        )
        val failure = lastFailure
        val next = when {
            days.containsKey(focusDay) -> LoadState.READY
            failure != null -> if (failure.offline) LoadState.OFFLINE else LoadState.ERROR
            else -> LoadState.LOADING
        }
        if (next != state) {
            state = next
            listener?.onTimelineState(next)
        }
    }

    private fun scheduleRetry(f: TimelineApiClient.WindowResult.Failed) {
        main.removeCallbacks(retryRunnable)
        if (!active) return
        val backoff = if (f.retryAfterMs > 0L) {
            f.retryAfterMs.coerceIn(500L, MAX_RETRY_MS)
        } else {
            (RETRY_BASE_MS shl retryAttempt.coerceAtMost(4)).coerceAtMost(MAX_RETRY_MS)
        }
        retryAttempt++
        main.postDelayed(retryRunnable, backoff)
    }

    private fun retryInternal(user: Boolean) {
        if (released || visibleTo == 0L) return
        main.removeCallbacks(retryRunnable)
        if (user) {
            retryAttempt = 0
            lastFailure = null
        }
        for (d in visibleDayList) failedAt.remove(d)
        if (daysState == DAYS_FAILED) daysState = DAYS_IDLE
        visibleDayList = emptyList()
        reapplyRange()
    }

    /** The strip's range as it last asked (unclamped), so a still strip past midnight still reaches the new day. */
    private fun reapplyRange() {
        if (askedTo > 0L) ensureLoaded(askedFrom, askedTo) else ensureLoaded(visibleFrom, visibleTo)
    }

    private fun refreshToday() {
        val today = TimelineDays.dayStart(System.currentTimeMillis(), tz)
        if (!isVisible(today)) return
        enqueue(today, force = days.containsKey(today))
        pump()
    }

    private fun loadDays() {
        daysState = DAYS_LOADING
        val now = System.currentTimeMillis()
        val from = TimelineDays.dayStart(now - DAYS_HORIZON_MS, tz)
        try {
            worker.execute {
                val list = try {
                    fetcher.days(from, now)
                } catch (t: Throwable) {
                    null
                }
                main.post { onDays(list) }
            }
        } catch (t: Throwable) {
            daysState = DAYS_FAILED
        }
    }

    private fun onDays(list: List<TlDay>?) {
        if (released) return
        if (list == null) {
            daysState = DAYS_FAILED
            return
        }
        daysState = DAYS_DONE
        val changed = list != dayList
        dayList = list
        dayStatsCache = null
        footageDays = list.asSequence()
            .filter { it.footageMs > 0L }
            .mapNotNull { TimelineDays.parseDay(it.day, tz) }
            .sorted()
            .toList()
        prefetchNextFootage(System.currentTimeMillis())
        pump()
        val today = TimelineDays.dayStart(System.currentTimeMillis(), tz)
        val earliest = list.asSequence()
            .filter { it.footageMs > 0L || it.events > 0 }
            .mapNotNull { TimelineDays.parseDay(it.day, tz) }
            .minOrNull()
        val next = minOf(earliest ?: today, today)
        if (next != computedMin) {
            computedMin = next
            if (snapshot != null) rebuild()
        }
        if (changed) listener?.onTimelineDays()
    }

    companion object {
        private const val MAX_DAYS = 7
        private const val MAX_VISIBLE_DAYS = 4
        private const val VISIBLE_HALF_RANGE_MS = 36L * 60 * 60 * 1000
        private const val LIVE_REFRESH_MS = 30_000L
        private const val REFRESH_AT_MIN_MS = 10_000L
        private const val DAYS_REPOLL_TICKS = 10
        private const val FAIL_COOLDOWN_MS = 15_000L
        private const val RETRY_BASE_MS = 2_000L
        private const val MAX_RETRY_MS = 30_000L
        private const val FALLBACK_HISTORY_MS = 30L * 24 * 60 * 60 * 1000
        private const val DAYS_HORIZON_MS = 61L * 24 * 60 * 60 * 1000
        private const val DAYS_IDLE = 0
        private const val DAYS_LOADING = 1
        private const val DAYS_DONE = 2
        private const val DAYS_FAILED = 3
    }
}

/** Local-day arithmetic in the recorder's zone (DST-safe through Calendar). */
internal object TimelineDays {
    fun dayStart(ms: Long, tz: TimeZone): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun nextDayStart(day: Long, tz: TimeZone): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = dayStart(day, tz)
        c.add(Calendar.DAY_OF_MONTH, 1)
        return dayStart(c.timeInMillis, tz)
    }

    fun prevDayStart(day: Long, tz: TimeZone): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = dayStart(day, tz)
        c.add(Calendar.DAY_OF_MONTH, -1)
        return dayStart(c.timeInMillis, tz)
    }

    fun daysCovering(from: Long, to: Long, tz: TimeZone, max: Int): List<Long> {
        val out = ArrayList<Long>()
        var d = dayStart(from, tz)
        while (d <= to && out.size < max) {
            out.add(d)
            d = nextDayStart(d, tz)
        }
        return out
    }

    /** "YYYY-MM-DD" to that day's start, or null. */
    fun parseDay(day: String, tz: TimeZone): Long? {
        val parts = day.split('-')
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        val c = Calendar.getInstance(tz)
        c.clear()
        c.set(y, m - 1, d, 12, 0, 0)
        return dayStart(c.timeInMillis, tz)
    }
}
