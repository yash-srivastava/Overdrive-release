package com.overdrive.app.ui.dashboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.format.Formatter
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.WorkerThread
import com.overdrive.app.R
import com.overdrive.app.ui.util.RecordingsApiClient
import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * One row in the rotating dashboard hero subtitle.
 *
 * `text` is a CharSequence so callers can pass either a plain String or a
 * Spanned with bolded numbers / accented colors. Higher `priority` wins in
 * sort order; ties shuffle to keep the rotation feeling alive across visits.
 */
data class DashboardInsight(
    val text: CharSequence,
    val priority: Int = 0
)

/**
 * Builds the live insight catalogue for the hero subtitle carousel.
 *
 * Hard rules:
 *  - Every method returns null when its data source isn't available — never a
 *    fake placeholder. The carousel silently skips nulls.
 *  - All disk / DB I/O is on the caller's worker thread — `build()` is
 *    explicitly @WorkerThread. The fragment posts the result to the UI.
 *  - No activity references are captured; we only hold the application
 *    context so the provider is lifecycle-safe even if recreated mid-rotation.
 */
class DashboardInsightProvider(appContext: Context) {

    private val ctx: Context = appContext.applicationContext
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Bumps the visit counter and returns the previous value. Called once per
     * dashboard view-creation so the welcome insight only fires on the very
     * first launch.
     */
    fun recordDashboardVisit(): Int {
        val prev = prefs.getInt(KEY_DASHBOARD_SEEN_COUNT, 0)
        prefs.edit().putInt(KEY_DASHBOARD_SEEN_COUNT, prev + 1).apply()
        return prev
    }

    /**
     * Build the insight list, ordered by priority (desc) with random tie-break.
     *
     * MUST be called from a background thread — touches H2 + filesystem.
     */
    @WorkerThread
    fun build(visitCountBefore: Int): List<DashboardInsight> {
        val emphasisColor = resolveAttrColor(
            androidx.appcompat.R.attr.colorPrimary,
            fallback = 0
        )
        val recordingStats = fetchRecordingStats()
        val newestSentryTimestamp = fetchNewestSentryTimestamp()

        val insights = mutableListOf<DashboardInsight>()

        welcomeInsight(visitCountBefore)?.let { insights += it }
        parkingDeltaInsight(emphasisColor)?.let { insights += it }
        lastSurveillanceInsight(emphasisColor, newestSentryTimestamp)?.let { insights += it }
        chargingRecapInsight(emphasisColor)?.let { insights += it }
        todaysClipsInsight(emphasisColor, recordingStats?.totalToday)?.let { insights += it }
        storageMilestoneInsight(
            emphasisColor,
            recordingStats?.totalCount,
            recordingStats?.totalSize
        )?.let { insights += it }
        uptimeInsight(emphasisColor)?.let { insights += it }

        // Deterministic order by priority, but tie-break randomly so back-to-back
        // visits don't always show the same card first.
        return insights.shuffled().sortedByDescending { it.priority }
    }

    // ============== Individual insight builders ==============

    /** First-launch welcome. priority highest so it wins on visit #0. */
    private fun welcomeInsight(visitCountBefore: Int): DashboardInsight? {
        if (visitCountBefore != 0) return null
        return DashboardInsight(
            text = ctx.getString(R.string.dashboard_insight_welcome),
            priority = 100
        )
    }

    /**
     * Parking delta — SOC + kWh change across the most recent completed
     * park-and-return cycle, computed from real ACC ON/OFF events recorded
     * in the daemon (NO inference from sample gaps).
     *
     * Fetched over HTTP from the camera daemon — the H2 DB lives in a
     * different process (UID 1000, app_process), so the UI process can't
     * touch SocHistoryDatabase directly: its singleton is never init()'d
     * here, and FILE_LOCK=SOCKET would refuse a second JVM anyway.
     *
     * Skipped silently when |deltaSoc| ≤ 0.5 or the cycle is older than 3 days.
     */
    private fun parkingDeltaInsight(emphasisColor: Int): DashboardInsight? {
        // 72 hours = 3 days, after which the delta is too stale to be
        // interesting on the dashboard.
        val json = fetchDaemonJson("/api/performance/parking-delta?maxAgeHours=72") ?: return null
        if (json.optBoolean("available", true).not()) return null
        val deltaSoc = json.optDouble("deltaSoc", Double.NaN)
        // We only surface drain across an ACC-OFF→ACC-ON cycle — i.e. how
        // much SOC/kWh was consumed while the car sat idle (vampire drain,
        // surveillance recording, parasitic load). Positive deltas mean
        // the car was charging while parked; we deliberately suppress those
        // here so this insight reads consistently as "what you lost since
        // last drive". The charging-finished surface lives elsewhere.
        if (deltaSoc.isNaN() || deltaSoc >= -0.5 || deltaSoc < -100) return null
        // Use onTs (the return event) for staleness — that's when the user
        // came back to the car, which is when the displayed delta becomes
        // relevant.
        val onTs = json.optLong("onTs", 0L)
        if (onTs <= 0L) return null
        val ageMs = System.currentTimeMillis() - onTs
        if (ageMs > 7L * 24 * 60 * 60 * 1000L) return null

        // Drain magnitude (positive number). deltaSoc is negative here.
        val drainSoc = -deltaSoc
        val deltaKwhRaw = if (json.has("deltaKwh")) json.optDouble("deltaKwh", Double.NaN) else Double.NaN
        // kWh must also be a real loss (negative raw → positive drain).
        // A drain in SOC with a positive kWh would be SOH calibration
        // noise, not a real loss — drop the kWh phrasing in that case.
        val drainKwh = if (!deltaKwhRaw.isNaN() && deltaKwhRaw < 0) -deltaKwhRaw else Double.NaN

        val socStr = formatPercent(drainSoc)
        val template = if (!drainKwh.isNaN()) {
            ctx.getString(R.string.dashboard_insight_parked_drained_kwh, socStr, formatKwh(drainKwh))
        } else {
            ctx.getString(R.string.dashboard_insight_parked_drained, socStr)
        }
        return DashboardInsight(
            text = emphasizeNumbers(template, emphasisColor),
            priority = 75
        )
    }

    /** "Last surveillance alert: 2 hours ago" — only when an event ≤ 7 days old exists. */
    private fun lastSurveillanceInsight(
        emphasisColor: Int,
        newest: Long?
    ): DashboardInsight? {
        if (newest == null) return null
        if (newest <= 0L) return null
        val ageMs = System.currentTimeMillis() - newest
        if (ageMs <= 0L || ageMs > 7L * 24 * 60 * 60 * 1000L) return null

        val rel = DateUtils.getRelativeTimeSpanString(
            newest,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
        val template = ctx.getString(R.string.dashboard_insight_last_alert, rel)
        return DashboardInsight(
            text = emphasizeSubstring(template, rel, emphasisColor),
            priority = 70
        )
    }

    /** "Last charge: +X kWh in Y minutes" — only if a session in the last 24h. */
    private fun chargingRecapInsight(emphasisColor: Int): DashboardInsight? {
        val json = fetchDaemonJson("/api/performance/last-charge?hoursBack=24") ?: return null
        if (json.optBoolean("available", true).not()) return null
        val kwh = json.optDouble("energyAddedKwh", Double.NaN)
        val mins = json.optLong("durationMinutes", 0L)
        if (kwh.isNaN() || kwh <= 0.05 || kwh > 500) return null
        if (mins <= 0L || mins > 7 * 24 * 60) return null
        val template = ctx.getString(
            R.string.dashboard_insight_last_charge,
            formatKwh(kwh),
            formatDurationMinutes(mins)
        )
        return DashboardInsight(
            text = emphasizeNumbers(template, emphasisColor),
            priority = 65
        )
    }

    /** "12 clips recorded today" — only when count > 0. */
    private fun todaysClipsInsight(
        emphasisColor: Int,
        todayCount: Long?
    ): DashboardInsight? {
        if (todayCount == null || todayCount > Int.MAX_VALUE) return null
        val n = todayCount.toInt()
        if (n <= 0) return null
        val template = ctx.resources.getQuantityString(
            R.plurals.dashboard_insight_today_clips, n, n
        )
        return DashboardInsight(
            text = emphasizeNumbers(template, emphasisColor),
            priority = 55
        )
    }

    /** "320 clips · 42 GB recorded" — only when total clips > 50. */
    private fun storageMilestoneInsight(
        emphasisColor: Int,
        totalClips: Long?,
        totalBytes: Long?
    ): DashboardInsight? {
        if (totalClips == null || totalBytes == null) return null
        if (totalClips <= 50) return null
        if (totalBytes <= 0L) return null
        val sizeStr = Formatter.formatShortFileSize(ctx, totalBytes)
        val template = ctx.getString(
            R.string.dashboard_insight_storage_milestone, totalClips, sizeStr
        )
        return DashboardInsight(
            text = emphasizeNumbers(template, emphasisColor),
            priority = 50
        )
    }

    /** "Overdrive online for 14 days, 6 hours" — only when uptime > 24h. */
    private fun uptimeInsight(emphasisColor: Int): DashboardInsight? {
        val uptimeMs = readProcessUptimeMs() ?: return null
        if (uptimeMs < 24L * 60 * 60 * 1000L) return null
        val totalMin = uptimeMs / 60_000L
        val days = totalMin / (24 * 60)
        val remHours = (totalMin % (24 * 60)) / 60
        val template = if (days > 0) {
            ctx.resources.getQuantityString(
                R.plurals.dashboard_insight_uptime_days_hours,
                days.toInt(),
                days.toInt(),
                remHours.toInt()
            )
        } else {
            ctx.resources.getQuantityString(
                R.plurals.dashboard_insight_uptime_hours,
                remHours.toInt(),
                remHours.toInt()
            )
        }
        return DashboardInsight(
            text = emphasizeNumbers(template, emphasisColor),
            priority = 45
        )
    }

    // ============== Helpers ==============

    private fun fetchRecordingStats(): RecordingsApiClient.StatsPayload? {
        return try {
            RecordingsApiClient.fetchStats()?.takeUnless {
                it.indexUnavailable || it.warming
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchNewestSentryTimestamp(): Long? {
        return try {
            val page = RecordingsApiClient.fetchRecordings(
                RecordingsApiClient.Filter(type = "sentry"),
                page = 1,
                pageSize = 1
            ) ?: return null
            if (page.indexUnavailable || page.warming) return null
            page.recordings.firstOrNull()?.timestamp
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * GET against the in-process daemon. Returns null on any error (timeout,
     * non-200, malformed JSON) — callers treat null as "no insight available"
     * and the carousel skips it.
     *
     * Always called from a worker thread (build() is @WorkerThread).
     */
    private fun fetchDaemonJson(path: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(path, "GET", connectTimeoutMs = 1500, readTimeoutMs = 2500)
            val code = conn.responseCode
            if (code != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.isEmpty()) return null
            JSONObject(body)
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * Process start time in ms. On API 24+ this is the boot-anchored start
     * elapsed-realtime; we convert to wall-clock by subtracting from
     * elapsedRealtime() and adding wall now. Returns null if the API isn't
     * available or returns a clearly bogus value.
     */
    private fun readProcessUptimeMs(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return try {
            val startElapsed = Process.getStartElapsedRealtime()
            if (startElapsed <= 0L) return null
            val nowElapsed = SystemClock.elapsedRealtime()
            val uptime = nowElapsed - startElapsed
            if (uptime < 0L || uptime > 365L * 24 * 60 * 60 * 1000L) null else uptime
        } catch (_: Throwable) {
            null
        }
    }

    /** "1.2 kWh" — single-decimal kWh formatter; clamps to one decimal. */
    private fun formatKwh(kwh: Double): String {
        val v = if (kwh.isNaN()) 0.0 else kwh
        return ctx.getString(R.string.dashboard_insight_kwh_format, v)
    }

    private fun formatPercent(pct: Double): String {
        val v = if (pct.isNaN()) 0.0 else pct
        return ctx.getString(R.string.dashboard_insight_percent_format, v)
    }

    private fun formatDurationMinutes(mins: Long): String {
        if (mins < 60) {
            return ctx.resources.getQuantityString(
                R.plurals.dashboard_insight_minutes, mins.toInt(), mins.toInt()
            )
        }
        val h = mins / 60
        val m = mins % 60
        return if (m == 0L) {
            ctx.resources.getQuantityString(
                R.plurals.dashboard_insight_hours, h.toInt(), h.toInt()
            )
        } else {
            ctx.getString(
                R.string.dashboard_insight_hours_minutes, h.toInt(), m.toInt()
            )
        }
    }

    /** Bold + tinted highlight on every digit run (and adjoining decimal). */
    private fun emphasizeNumbers(template: String, @androidx.annotation.ColorInt color: Int): CharSequence {
        if (template.isEmpty()) return template
        val sb = SpannableStringBuilder(template)
        // Match runs of digits (with optional . , or unicode digits) plus an
        // optional trailing "%" or "kWh" so the unit moves with the number.
        val regex = Regex("""[\d][\d.,]*""")
        for (m in regex.findAll(template)) {
            sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (color != 0) {
                sb.setSpan(ForegroundColorSpan(color), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun emphasizeSubstring(
        template: String,
        substr: String,
        @androidx.annotation.ColorInt color: Int
    ): CharSequence {
        if (substr.isEmpty()) return template
        val idx = template.indexOf(substr)
        if (idx < 0) return template
        val sb = SpannableStringBuilder(template)
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), idx, idx + substr.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (color != 0) {
            sb.setSpan(ForegroundColorSpan(color), idx, idx + substr.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun resolveAttrColor(@AttrRes attr: Int, fallback: Int): Int {
        return try {
            val tv = TypedValue()
            if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    companion object {
        private const val PREFS_NAME = "overdrive_dashboard_insights"
        private const val KEY_DASHBOARD_SEEN_COUNT = "dashboard_seen_count"
    }
}
