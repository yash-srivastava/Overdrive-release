package com.overdrive.app.ui.timeline

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Timeline settings that outlive the screen. */
object TimelinePrefs {
    private const val PREFS = "timeline_prefs"
    private const val KEY_VIEW = "view"
    private const val KEY_SKIP_QUIET = "skip_quiet"
    private const val KEY_CONTINUE = "continue_next"
    private const val KEY_PREFER = "prefer_source"
    private const val KEY_LAST_WALL = "last_wall_ms"
    private const val KEY_LAST_SAVED = "last_saved_ms"
    private const val VIEW_TIMELINE = "timeline"
    private const val VIEW_LIST = "list"
    private const val LAST_POSITION_TTL_MS = 6L * 60 * 60 * 1000

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isTimelineRemembered(ctx: Context): Boolean =
        runCatching { prefs(ctx).getString(KEY_VIEW, VIEW_LIST) == VIEW_TIMELINE }.getOrDefault(false)

    fun setTimelineRemembered(ctx: Context, on: Boolean) {
        runCatching { prefs(ctx).edit().putString(KEY_VIEW, if (on) VIEW_TIMELINE else VIEW_LIST).apply() }
    }

    fun skipQuiet(ctx: Context): Boolean =
        runCatching { prefs(ctx).getBoolean(KEY_SKIP_QUIET, false) }.getOrDefault(false)

    fun setSkipQuiet(ctx: Context, on: Boolean) {
        runCatching { prefs(ctx).edit().putBoolean(KEY_SKIP_QUIET, on).apply() }
    }

    fun continueNext(ctx: Context): Boolean =
        runCatching { prefs(ctx).getBoolean(KEY_CONTINUE, true) }.getOrDefault(true)

    fun setContinueNext(ctx: Context, on: Boolean) {
        runCatching { prefs(ctx).edit().putBoolean(KEY_CONTINUE, on).apply() }
    }

    fun preferredSource(ctx: Context): String? =
        runCatching { prefs(ctx).getString(KEY_PREFER, null) }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun setPreferredSource(ctx: Context, type: String?) {
        runCatching { prefs(ctx).edit().putString(KEY_PREFER, type ?: "").apply() }
    }

    fun lastPosition(ctx: Context, nowMs: Long): Long? = runCatching {
        val p = prefs(ctx)
        val saved = p.getLong(KEY_LAST_SAVED, 0L)
        val wall = p.getLong(KEY_LAST_WALL, 0L)
        if (wall > 0L && nowMs - saved in 0..LAST_POSITION_TTL_MS) wall else null
    }.getOrNull()

    fun saveLastPosition(ctx: Context, wallMs: Long, nowMs: Long) {
        if (wallMs <= 0L) return
        runCatching { prefs(ctx).edit().putLong(KEY_LAST_WALL, wallMs).putLong(KEY_LAST_SAVED, nowMs).apply() }
    }

    /**
     * Mode on (re)creation: saved state, then an explicit `view` arg, then a
     * parking-session list request, then the remembered choice.
     */
    fun resolveInitialMode(saved: Boolean?, argView: String?, argParking: Boolean, remembered: Boolean): Boolean {
        if (saved != null) return saved
        when (argView?.trim()?.lowercase(Locale.ROOT)) {
            VIEW_TIMELINE -> return true
            VIEW_LIST -> return false
        }
        if (argParking) return false
        return remembered
    }
}

/** Deep-link parsing shared by the WebView interception and Recordings. */
object TimelineLinks {
    const val ARG_VIEW = "view"
    const val ARG_TIME_MS = "time_ms"
    const val ARG_END_MS = "end_ms"
    const val VIEW_TIMELINE = "timeline"

    private const val MIN_EPOCH_MS = 1_420_070_400_000L
    private const val DAY_MS = 24L * 60 * 60 * 1000
    // A replay's stamp marks its export; one second earlier is still inside it.
    private const val REPLAY_BACKOFF_MS = 1_000L
    private val NAME = Regex("""^(cam\d*|event|proximity|replay|dvr(?:_p)?)_(\d{8})_(\d{6})(?:_\d+)?\.mp4$""")

    data class Route(val timeMs: Long?, val endMs: Long?)

    /** Wall time named by a clip file's yyyyMMdd_HHmmss stamp, or null. */
    fun timeFromFilename(raw: String, tz: TimeZone = TimeZone.getDefault()): Long? {
        val name = raw.trim().substringAfterLast('/')
        val m = NAME.matchEntire(name) ?: return null
        val date = m.groupValues[2]
        val time = m.groupValues[3]
        val month = date.substring(4, 6).toInt()
        val day = date.substring(6, 8).toInt()
        val hh = time.substring(0, 2).toInt()
        val mm = time.substring(2, 4).toInt()
        val ss = time.substring(4, 6).toInt()
        if (month !in 1..12 || day !in 1..31 || hh > 23 || mm > 59 || ss > 59) return null
        // Validate the calendar date in UTC, independently of local DST gaps.
        val dateCheck = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        // Keep the local timestamp lenient so a DST-gap stamp still resolves.
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = tz }
        val ts = try {
            if (dateCheck.parse(date) == null) return null
            fmt.parse(date + "_" + time)?.time
        } catch (_: Exception) {
            null
        } ?: return null
        return if (m.groupValues[1] == "replay") ts - REPLAY_BACKOFF_MS else ts
    }

    /** Epoch ms from a link value; seconds are accepted, absurd values rejected. */
    fun parseEpochMs(raw: String?, nowMs: Long): Long? {
        val v = raw?.trim()?.toLongOrNull() ?: return null
        val ms = if (v in 1_000_000_000L..9_999_999_999L) v * 1000L else v
        if (ms < MIN_EPOCH_MS || ms > nowMs + DAY_MS) return null
        return ms
    }

    /** Timeline target for an events link, or null to keep the existing path. */
    fun route(
        view: String?,
        t: String?,
        end: String?,
        file: String?,
        remembered: Boolean,
        nowMs: Long,
        tz: TimeZone = TimeZone.getDefault(),
    ): Route? {
        val v = view?.trim()?.lowercase(Locale.ROOT)
        val fileTime = file?.takeIf { it.isNotBlank() }?.let { timeFromFilename(it, tz) }
        if (v == VIEW_TIMELINE) {
            val time = parseEpochMs(t, nowMs) ?: fileTime
            val endMs = if (time == null) null else parseEpochMs(end, nowMs)?.takeIf { it > time }
            return Route(time, endMs)
        }
        if (v.isNullOrEmpty() && remembered && fileTime != null) return Route(fileTime, null)
        return null
    }

    /** Nav args for recordingsFragment when the link targets the timeline. */
    fun recordingsArgs(
        ctx: Context?,
        view: String?,
        t: String?,
        end: String?,
        file: String?,
        filter: String?,
    ): Bundle? {
        if (view.isNullOrBlank() && file.isNullOrBlank()) return null
        val remembered = ctx != null && TimelinePrefs.isTimelineRemembered(ctx)
        val r = route(view, t, end, file, remembered, System.currentTimeMillis()) ?: return null
        return Bundle().apply {
            putString(ARG_VIEW, VIEW_TIMELINE)
            r.timeMs?.let { putLong(ARG_TIME_MS, it) }
            r.endMs?.let { putLong(ARG_END_MS, it) }
            if (!filter.isNullOrBlank()) putString("filter", filter)
        }
    }
}

/** Playhead navigation rules shared by the controls and D-pad paths. */
object TimelineNav {
    private const val NEXT_SLACK_MS = 500L
    private const val PREV_RESTART_MS = 2_000L

    fun nextMoment(sortedAsc: List<Long>, playheadMs: Long): Long? =
        sortedAsc.firstOrNull { it > playheadMs + NEXT_SLACK_MS }

    /** Within 2 s of a moment's start "previous" means the one before it. */
    fun prevMoment(sortedAsc: List<Long>, playheadMs: Long): Long? =
        sortedAsc.lastOrNull { it < playheadMs - PREV_RESTART_MS }

    /** First explicit position wins; null means "pick once the day loads". */
    fun initialPosition(explicit: Long?, saved: Long?, retained: Long?, remembered: Long?): Long? =
        explicit ?: saved ?: retained ?: remembered

    /** Newest moment, else the tail of the newest footage, else just before now. */
    fun autoPosition(momentStartsDesc: List<Long>, latestStart: Long?, latestEnd: Long?, nowMs: Long): Long {
        momentStartsDesc.firstOrNull()?.let { return it }
        if (latestStart != null && latestEnd != null && latestEnd >= latestStart) {
            return maxOf(latestStart, latestEnd - 30_000L).coerceAtMost(nowMs)
        }
        return nowMs - 60_000L
    }

    // A picked day, as the web's bestTargetInDay: its newest moment, else the last 30 s of its latest footage,
    // else a minute ago (today) or midday (a past day).
    fun bestInDay(
        momentStartsDesc: List<Long>, latestStart: Long?, latestEnd: Long?, dayStart: Long, dayEnd: Long, nowMs: Long,
    ): Long {
        momentStartsDesc.firstOrNull()?.let { return it.coerceAtMost(nowMs) }
        if (latestStart != null && latestEnd != null && latestEnd >= latestStart) {
            return maxOf(latestStart, minOf(latestEnd, dayEnd) - 30_000L).coerceAtMost(nowMs)
        }
        return if (nowMs < dayEnd) nowMs - 60_000L else minOf(dayStart + 12L * 3_600_000L, nowMs)
    }
}
