package com.overdrive.app.navmap.google

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.floor
import kotlin.math.min

enum class GoogleSku(val wire: String, val api: GoogleApi) {
    TILES("tiles", GoogleApi.TILES),
    ROUTES("routes", GoogleApi.ROUTES),
    ROUTES_TRAFFIC("routesTraffic", GoogleApi.ROUTES),
    AUTOCOMPLETE("autocomplete", GoogleApi.PLACES),
    PLACE_DETAILS("placeDetails", GoogleApi.PLACES),
    TEXT_SEARCH("textSearch", GoogleApi.PLACES),
    GEOCODE("geocode", GoogleApi.GEOCODE)
}

enum class TileBudgetLevel { HEALTHY, CONSTRAINED, TRAFFIC_OFF, EXHAUSTED }

data class GoogleLatch(val reason: String, val untilMs: Long, val httpCode: Int)

/** Local per-SKU request budget that keeps Google usage inside the monthly free caps. */
object GoogleMapsBudget {

    private const val TAG = "GoogleMapsBudget"
    private const val PREFS = "navmap_google_budget"
    private const val REASON_QUOTA = "quota"
    private const val SHORT_LATCH_MS = 10 * 60_000L
    // A PT day is at most 25 h; anything longer is a clock jump, not a real latch.
    private const val MAX_LATCH_MS = 26 * 60 * 60_000L
    private const val PERSIST_INTERVAL_MS = 2_000L
    private const val TICK_MAX_MS = 10 * 60_000L
    private const val MAX_SAVED_COUNT = 50_000_000L
    private val DAY_KEY = Regex("""\d{4}-\d{2}-\d{2}""")
    private val MONTH_KEY = Regex("""\d{4}-\d{2}""")

    private val PT: ZoneId = try {
        ZoneId.of("America/Los_Angeles")
    } catch (t: Throwable) {
        ZoneOffset.ofHours(-8)
    }

    private val SKUS = GoogleSku.entries
    private val APIS = GoogleApi.entries

    // Per SKU: high 32 bits = used today, low 32 bits = used this month (one CAS covers both).
    private val counters = AtomicLongArray(SKUS.size)
    private val latches = AtomicReferenceArray<GoogleLatch?>(APIS.size)
    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val lastLevel = AtomicReference<TileBudgetLevel?>(null)
    private val periodLock = Any()
    private val tickLock = Any()
    private val persistPending = AtomicBoolean(false)
    // What restore() loaded from this process's prefs; mergeSavedCopy() adds only usage beyond it.
    private val baseDay = IntArray(SKUS.size)
    private val baseMonth = IntArray(SKUS.size)
    @Volatile private var baseDayKey = ""
    @Volatile private var baseMonthKey = ""
    private val savedCopyMerged = AtomicBoolean(false)

    @Volatile private var context: Context? = null
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var dayKey = ""
    @Volatile private var monthKey = ""
    // Wall-clock span of the PT day last evaluated; outside it the period keys are re-checked.
    @Volatile private var windowStartMs = 0L
    @Volatile private var windowEndMs = 0L
    @Volatile private var lastPersistMs = 0L
    @Volatile private var tickFuture: ScheduledFuture<*>? = null

    private val scheduler: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, "GoogleMapsBudget").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }

    @JvmStatic
    fun init(context: Context) {
        if (this.context != null) return
        synchronized(periodLock) {
            if (this.context != null) return
            val app = context.applicationContext ?: context
            val p = try {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            } catch (t: Throwable) {
                null
            }
            if (p != null) restore(p)
            prefs = p
            this.context = app
        }
        scheduleTick()
    }

    @JvmStatic
    fun appContext(): Context? = context

    @JvmStatic
    fun monthlyFreeCap(pricing: GooglePricing, sku: GoogleSku): Int = when (pricing) {
        GooglePricing.GLOBAL -> when (sku) {
            GoogleSku.TILES -> 100_000
            GoogleSku.ROUTES -> 5_000
            GoogleSku.ROUTES_TRAFFIC -> 1_000
            GoogleSku.AUTOCOMPLETE -> 10_000
            GoogleSku.PLACE_DETAILS -> 10_000
            GoogleSku.TEXT_SEARCH -> 5_000
            GoogleSku.GEOCODE -> 10_000
        }
        GooglePricing.INDIA -> when (sku) {
            GoogleSku.TILES -> 700_000
            GoogleSku.ROUTES -> 35_000
            GoogleSku.ROUTES_TRAFFIC -> 7_000
            GoogleSku.AUTOCOMPLETE -> 70_000
            GoogleSku.PLACE_DETAILS -> 70_000
            GoogleSku.TEXT_SEARCH -> 35_000
            GoogleSku.GEOCODE -> 70_000
        }
    }

    @JvmStatic
    fun recommendedDailyQuota(pricing: GooglePricing, sku: GoogleSku): Int {
        val perDay = floor(monthlyFreeCap(pricing, sku) / 31.0 * 0.9).toInt()
        return if (sku == GoogleSku.TILES) min(perDay, 15_000) else perDay
    }

    /** First paid-tier list price per request, in micro-USD (higher tiers are cheaper, so this over-estimates). */
    @JvmStatic
    fun unitPriceMicros(pricing: GooglePricing, sku: GoogleSku): Long = when (pricing) {
        GooglePricing.GLOBAL -> when (sku) {
            GoogleSku.TILES -> 600L
            GoogleSku.ROUTES -> 10_000L
            GoogleSku.ROUTES_TRAFFIC -> 15_000L
            GoogleSku.AUTOCOMPLETE -> 2_830L
            GoogleSku.PLACE_DETAILS -> 5_000L
            GoogleSku.TEXT_SEARCH -> 32_000L
            GoogleSku.GEOCODE -> 5_000L
        }
        GooglePricing.INDIA -> when (sku) {
            GoogleSku.TILES -> 180L
            GoogleSku.ROUTES -> 3_000L
            GoogleSku.ROUTES_TRAFFIC -> 4_500L
            GoogleSku.AUTOCOMPLETE -> 850L
            GoogleSku.PLACE_DETAILS -> 1_500L
            GoogleSku.TEXT_SEARCH -> 9_600L
            GoogleSku.GEOCODE -> 1_500L
        }
    }

    /** Google-side daily quota that still lets a paid budget be spent on [sku] alone (a safety net, not the limiter). */
    @JvmStatic
    fun recommendedDailyQuotaPaid(pricing: GooglePricing, sku: GoogleSku, budgetCents: Int): Int {
        val paidUnits = budgetMicros(budgetCents) / unitPriceMicros(pricing, sku)
        val perDay = (monthlyFreeCap(pricing, sku) + paidUnits) * 12 / (31 * 10)
        return perDay.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    @JvmStatic
    fun paidMode(): Boolean = try { GoogleMapsConfig.cached().paid } catch (_: Throwable) { false }

    /** True when one more [sku] request is allowed right now (free or paid rules). */
    @JvmStatic
    fun hasRoom(sku: GoogleSku): Boolean {
        ensurePeriod(System.currentTimeMillis())
        if (latch(sku.api) != null) return false
        val settings = GoogleMapsConfig.cached()
        val v = counters.get(sku.ordinal)
        if (settings.paid) {
            if (monthOf(v) < monthlyFreeCap(settings.pricing, sku)) return true
            return spendMicros(settings.pricing) + unitPriceMicros(settings.pricing, sku) <= spendLimitMicros(settings)
        }
        return dayOf(v) < allowanceFor(settings, sku) && monthOf(v) < monthHardStop(settings.pricing, sku)
    }

    @JvmStatic
    fun dailyAllowance(sku: GoogleSku): Int = allowanceFor(GoogleMapsConfig.cached(), sku)

    @JvmStatic
    fun tryAcquire(sku: GoogleSku): Boolean {
        ensurePeriod(System.currentTimeMillis())
        if (latch(sku.api) != null) return false
        val settings = GoogleMapsConfig.cached()
        if (settings.paid) return tryAcquirePaid(settings, sku)
        val dayLimit = allowanceFor(settings, sku)
        val monthLimit = monthHardStop(settings.pricing, sku)
        val i = sku.ordinal
        while (true) {
            val v = counters.get(i)
            val d = dayOf(v)
            val m = monthOf(v)
            if (d >= dayLimit || m >= monthLimit) return false
            if (counters.compareAndSet(i, v, pack(d + 1, m + 1))) break
        }
        persistSoon()
        if (sku == GoogleSku.TILES) notifyIfChanged(false)
        return true
    }

    @JvmStatic
    fun usedToday(sku: GoogleSku): Int {
        ensurePeriod(System.currentTimeMillis())
        return dayOf(counters.get(sku.ordinal))
    }

    @JvmStatic
    fun tileLevel(): TileBudgetLevel {
        if (latch(GoogleApi.TILES) != null) return TileBudgetLevel.EXHAUSTED
        val settings = GoogleMapsConfig.cached()
        if (settings.paid) return paidTileLevel(settings)
        val allowance = allowanceFor(settings, GoogleSku.TILES).toLong()
        if (allowance <= 0L) return TileBudgetLevel.EXHAUSTED
        ensurePeriod(System.currentTimeMillis())
        val v = counters.get(GoogleSku.TILES.ordinal)
        // The month hard stop refuses every tile too, so it must read as exhausted.
        if (monthOf(v) >= monthHardStop(settings.pricing, GoogleSku.TILES)) return TileBudgetLevel.EXHAUSTED
        val used = dayOf(v).toLong()
        return when {
            used >= allowance -> TileBudgetLevel.EXHAUSTED
            used * 10 >= allowance * 8 -> TileBudgetLevel.TRAFFIC_OFF
            used * 10 >= allowance * 5 -> TileBudgetLevel.CONSTRAINED
            else -> TileBudgetLevel.HEALTHY
        }
    }

    @JvmStatic
    fun onHttpResult(api: GoogleApi, code: Int, errorBody: String?) {
        val body = errorBody ?: ""
        val now = System.currentTimeMillis()
        when {
            code == 429 || body.contains("RESOURCE_EXHAUSTED") ->
                setLatch(api, GoogleLatch(REASON_QUOTA, nextResetMs(), code))
            code == 403 -> {
                val reason = when {
                    body.contains("BILLING") -> "billing"
                    body.contains("SERVICE_DISABLED") || body.contains("has not been used") ||
                        body.contains("is disabled") -> "disabled"
                    else -> "denied"
                }
                setLatch(api, GoogleLatch(reason, now + SHORT_LATCH_MS, code))
            }
            code == 400 && (body.contains("API_KEY_INVALID") || body.contains("API key not valid")) ->
                setLatch(api, GoogleLatch("invalid_key", now + SHORT_LATCH_MS, code))
            code == 401 -> setLatch(api, GoogleLatch("denied", now + SHORT_LATCH_MS, code))
            code in 200..299 || code == 304 -> clearSoftLatch(api, now)
        }
    }

    @JvmStatic
    fun latch(api: GoogleApi): GoogleLatch? =
        latches.get(api.ordinal)?.takeIf { isActive(it, System.currentTimeMillis()) }

    @JvmStatic
    fun nextResetMs(): Long {
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(PT).toLocalDate()
        return today.plusDays(1).atStartOfDay(PT).toInstant().toEpochMilli()
    }

    @JvmStatic
    fun snapshotJson(): JSONObject {
        val out = JSONObject()
        try {
            val now = System.currentTimeMillis()
            ensurePeriod(now)
            val settings = GoogleMapsConfig.cached()
            val day = JSONObject()
            val month = JSONObject()
            val allowance = JSONObject()
            for (sku in SKUS) {
                val v = counters.get(sku.ordinal)
                day.put(sku.wire, dayOf(v))
                month.put(sku.wire, monthOf(v))
                allowance.put(sku.wire, allowanceFor(settings, sku))
            }
            val active = JSONObject()
            for (api in APIS) {
                val l = latches.get(api.ordinal)?.takeIf { isActive(it, now) } ?: continue
                active.put(
                    api.wire,
                    JSONObject().put("reason", l.reason).put("untilMs", l.untilMs).put("httpCode", l.httpCode)
                )
            }
            out.put("dayKey", dayKey)
                .put("monthKey", monthKey)
                .put("pricing", settings.pricing.wire)
                .put("day", day)
                .put("month", month)
                .put("allowance", allowance)
                .put("latches", active)
                .put("tileLevel", tileLevel().name)
                .put("mode", if (settings.paid) "paid" else "free")
                .put("spendMicros", spendMicros(settings.pricing))
                .put("budgetMicros", if (settings.paid) budgetMicros(settings.monthlyBudgetCents) else 0L)
                .put("resetAtMs", nextResetMs())
                .put("updatedMs", now)
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot failed: ${t.javaClass.simpleName}")
        }
        return out
    }

    /**
     * Folds in the daemon's copy of the counters, which outlives app data (reinstall, Clear data). The copy
     * overlaps what this process restored from its own prefs, so only usage beyond that is added. Once per process.
     */
    @JvmStatic
    fun mergeSavedCopy(saved: JSONObject) {
        if (context == null || !savedCopyMerged.compareAndSet(false, true)) return
        ensurePeriod(System.currentTimeMillis())
        synchronized(periodLock) {
            val savedDay = saved.optString("dayKey", "").takeIf { DAY_KEY.matches(it) }.orEmpty()
            val savedMonth = saved.optString("monthKey", "").takeIf { MONTH_KEY.matches(it) }.orEmpty()
            val days = saved.optJSONObject("day")
            val months = saved.optJSONObject("month")
            // Same forward-only rule as restore(): the later period wins.
            val day = maxOf(dayKey, savedDay)
            val month = maxOf(monthKey, savedMonth)
            for (sku in SKUS) {
                val i = sku.ordinal
                val addDay = if (savedDay.isNotEmpty() && savedDay == day) {
                    savedCount(days, sku) - (if (baseDayKey == day) baseDay[i] else 0)
                } else 0
                val addMonth = if (savedMonth.isNotEmpty() && savedMonth == month) {
                    savedCount(months, sku) - (if (baseMonthKey == month) baseMonth[i] else 0)
                } else 0
                while (true) {
                    val v = counters.get(i)
                    val d = (if (dayKey == day) dayOf(v) else 0) + addDay.coerceAtLeast(0)
                    val m = (if (monthKey == month) monthOf(v) else 0) + addMonth.coerceAtLeast(0)
                    if (counters.compareAndSet(i, v, pack(d, m))) break
                }
            }
            dayKey = day
            monthKey = month
            windowStartMs = 0L
            windowEndMs = 0L
        }
        persistSoon()
        notifyIfChanged(false)
    }

    @JvmStatic
    fun flush() {
        val main = Looper.getMainLooper()
        // apply() on the main thread (onPause); commit() is fine everywhere else.
        val onMain = main != null && main.thread === Thread.currentThread()
        writeNow(sync = !onMain)
    }

    @JvmStatic
    fun addListener(l: Runnable) {
        listeners.addIfAbsent(l)
        lastLevel.compareAndSet(null, tileLevel())
        scheduleTick()
    }

    @JvmStatic
    fun removeListener(l: Runnable) {
        listeners.remove(l)
    }

    @JvmStatic
    internal fun fireChanged() {
        for (l in listeners) {
            try {
                l.run()
            } catch (t: Throwable) {
                Log.w(TAG, "listener failed", t)
            }
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun dayOf(v: Long): Int = (v ushr 32).toInt()
    private fun monthOf(v: Long): Int = (v and 0xFFFF_FFFFL).toInt()
    private fun pack(day: Int, month: Int): Long =
        (day.toLong() shl 32) or (month.toLong() and 0xFFFF_FFFFL)

    private fun allowanceFor(settings: GoogleMapsSettings, sku: GoogleSku): Int {
        val recommended = recommendedDailyQuota(settings.pricing, sku)
        val user = when (sku) {
            GoogleSku.TILES -> settings.quotaTilesPerDay
            GoogleSku.ROUTES, GoogleSku.ROUTES_TRAFFIC -> settings.quotaRoutesPerDay
            GoogleSku.AUTOCOMPLETE, GoogleSku.PLACE_DETAILS, GoogleSku.TEXT_SEARCH -> settings.quotaPlacesPerDay
            GoogleSku.GEOCODE -> settings.quotaGeocodePerDay
        }
        return if (user > 0) min(user, recommended) else recommended
    }

    private val spendLock = Any()

    private fun budgetMicros(budgetCents: Int): Long = budgetCents.toLong().coerceAtLeast(0L) * 10_000L

    /** Paid mode stops at 95 % of the user's monthly limit. */
    private fun spendLimitMicros(settings: GoogleMapsSettings): Long =
        budgetMicros(settings.monthlyBudgetCents) * 95 / 100

    private fun spendMicros(pricing: GooglePricing): Long {
        var total = 0L
        for (sku in SKUS) {
            val over = monthOf(counters.get(sku.ordinal)) - monthlyFreeCap(pricing, sku)
            if (over > 0) total += over.toLong() * unitPriceMicros(pricing, sku)
        }
        return total
    }

    private fun tryAcquirePaid(settings: GoogleMapsSettings, sku: GoogleSku): Boolean {
        val i = sku.ordinal
        // The spend check spans every SKU, so it and the increment must be one atomic step.
        synchronized(spendLock) {
            val v = counters.get(i)
            if (monthOf(v) >= monthlyFreeCap(settings.pricing, sku) &&
                spendMicros(settings.pricing) + unitPriceMicros(settings.pricing, sku) > spendLimitMicros(settings)
            ) return false
            counters.set(i, pack(dayOf(v) + 1, monthOf(v) + 1))
        }
        persistSoon()
        notifyIfChanged(false)
        return true
    }

    private fun paidTileLevel(settings: GoogleMapsSettings): TileBudgetLevel {
        ensurePeriod(System.currentTimeMillis())
        val limit = spendLimitMicros(settings)
        if (limit <= 0L) return TileBudgetLevel.EXHAUSTED
        val spend = spendMicros(settings.pricing)
        val tilesFree = monthOf(counters.get(GoogleSku.TILES.ordinal)) <
            monthlyFreeCap(settings.pricing, GoogleSku.TILES)
        return when {
            !tilesFree && spend + unitPriceMicros(settings.pricing, GoogleSku.TILES) > limit -> TileBudgetLevel.EXHAUSTED
            spend * 10 >= limit * 9 -> TileBudgetLevel.TRAFFIC_OFF
            spend * 4 >= limit * 3 -> TileBudgetLevel.CONSTRAINED
            else -> TileBudgetLevel.HEALTHY
        }
    }

    private fun monthHardStop(pricing: GooglePricing, sku: GoogleSku): Int =
        (monthlyFreeCap(pricing, sku).toLong() * 95 / 100).toInt()

    private fun isActive(l: GoogleLatch, now: Long): Boolean =
        l.untilMs > now && l.untilMs - now <= MAX_LATCH_MS

    private fun ensurePeriod(now: Long) {
        if (now >= windowStartMs && now < windowEndMs) return
        var reset = false
        synchronized(periodLock) {
            if (now >= windowStartMs && now < windowEndMs) return
            val date = Instant.ofEpochMilli(now).atZone(PT).toLocalDate()
            val day = date.toString()
            val month = day.substring(0, 7)
            // Forward-only: a clock stepping back (unset RTC at boot) must not hand out a fresh budget.
            val newDay = day > dayKey
            val newMonth = month > monthKey
            if (newDay || newMonth) {
                for (i in SKUS.indices) {
                    while (true) {
                        val v = counters.get(i)
                        val next = pack(if (newDay) 0 else dayOf(v), if (newMonth) 0 else monthOf(v))
                        if (counters.compareAndSet(i, v, next)) break
                    }
                }
                if (newDay) dayKey = day
                if (newMonth) monthKey = month
                reset = true
            }
            windowStartMs = date.atStartOfDay(PT).toInstant().toEpochMilli()
            windowEndMs = date.plusDays(1).atStartOfDay(PT).toInstant().toEpochMilli()
        }
        if (reset) {
            persistSoon()
            // Getters can land here; notify off-thread so listeners never re-enter a caller.
            if (listeners.isNotEmpty()) runAsync { notifyIfChanged(false) }
        }
    }

    private fun setLatch(api: GoogleApi, latch: GoogleLatch) {
        val now = System.currentTimeMillis()
        val prev = latches.getAndSet(api.ordinal, latch)?.takeIf { isActive(it, now) }
        val changed = prev == null || prev.reason != latch.reason
        if (changed) Log.w(TAG, "${api.wire} paused: ${latch.reason} (HTTP ${latch.httpCode})")
        persistSoon()
        scheduleTick()
        notifyIfChanged(changed)
    }

    private fun clearSoftLatch(api: GoogleApi, now: Long) {
        val i = api.ordinal
        val l = latches.get(i) ?: return
        val active = isActive(l, now)
        if (active && l.reason == REASON_QUOTA) return
        if (!latches.compareAndSet(i, l, null)) return
        persistSoon()
        notifyIfChanged(active)
    }

    private fun notifyIfChanged(latchChanged: Boolean) {
        val level = tileLevel()
        val prev = lastLevel.getAndSet(level)
        if (latchChanged || (prev != null && prev != level)) fireChanged()
    }

    private fun runAsync(task: () -> Unit) {
        try {
            scheduler.execute {
                try {
                    task()
                } catch (t: Throwable) {
                    Log.w(TAG, "task failed: ${t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            // Executor unavailable; the next counter or latch change notifies instead.
        }
    }

    /** Fires listeners at PT midnight and latch expiry; runs only while someone listens. */
    private fun scheduleTick() {
        if (listeners.isEmpty()) return
        try {
            val now = System.currentTimeMillis()
            var target = nextResetMs()
            for (i in APIS.indices) {
                val l = latches.get(i) ?: continue
                if (isActive(l, now) && l.untilMs < target) target = l.untilMs
            }
            val delay = (target - now + 1_000L).coerceIn(1_000L, TICK_MAX_MS)
            synchronized(tickLock) {
                tickFuture?.cancel(false)
                tickFuture = scheduler.schedule(Runnable { onTick() }, delay, TimeUnit.MILLISECONDS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "tick schedule failed: ${t.javaClass.simpleName}")
        }
    }

    private fun onTick() {
        try {
            val now = System.currentTimeMillis()
            ensurePeriod(now)
            var expired = false
            for (i in APIS.indices) {
                val l = latches.get(i) ?: continue
                if (!isActive(l, now) && latches.compareAndSet(i, l, null)) expired = true
            }
            if (expired) persistSoon()
            notifyIfChanged(expired)
        } catch (t: Throwable) {
            Log.w(TAG, "tick failed: ${t.javaClass.simpleName}")
        }
        scheduleTick()
    }

    private fun persistSoon() {
        if (prefs == null) return
        if (!persistPending.compareAndSet(false, true)) return
        val delay = (lastPersistMs + PERSIST_INTERVAL_MS - SystemClock.elapsedRealtime())
            .coerceIn(0L, PERSIST_INTERVAL_MS)
        try {
            scheduler.schedule(Runnable {
                persistPending.set(false)
                writeNow(sync = true)
            }, delay, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            persistPending.set(false)
        }
    }

    private fun writeNow(sync: Boolean) {
        val p = prefs ?: return
        try {
            lastPersistMs = SystemClock.elapsedRealtime()
            val e = p.edit()
            synchronized(periodLock) {
                e.putString("dayKey", dayKey).putString("monthKey", monthKey)
                for (sku in SKUS) {
                    val v = counters.get(sku.ordinal)
                    e.putInt("d_${sku.wire}", dayOf(v)).putInt("m_${sku.wire}", monthOf(v))
                }
            }
            for (api in APIS) {
                val l = latches.get(api.ordinal)
                if (l != null) {
                    e.putString("latch_${api.wire}", "${l.reason}|${l.untilMs}|${l.httpCode}")
                } else {
                    e.remove("latch_${api.wire}")
                }
            }
            if (sync) e.commit() else e.apply()
        } catch (t: Throwable) {
            Log.w(TAG, "persist failed: ${t.javaClass.simpleName}")
        }
    }

    /** Caller holds [periodLock]. Pre-init in-memory usage is added to the persisted counts. */
    private fun restore(p: SharedPreferences) {
        val storedDay = prefString(p, "dayKey")
        val storedMonth = prefString(p, "monthKey")
        val memDay = dayKey
        val memMonth = monthKey
        val day = maxOf(storedDay, memDay)
        val month = maxOf(storedMonth, memMonth)
        for (sku in SKUS) {
            val pd = if (storedDay == day) prefInt(p, "d_${sku.wire}") else 0
            val pm = if (storedMonth == month) prefInt(p, "m_${sku.wire}") else 0
            val i = sku.ordinal
            baseDay[i] = pd
            baseMonth[i] = pm
            while (true) {
                val v = counters.get(i)
                val d = pd + (if (memDay == day) dayOf(v) else 0)
                val m = pm + (if (memMonth == month) monthOf(v) else 0)
                if (counters.compareAndSet(i, v, pack(d.coerceAtLeast(0), m.coerceAtLeast(0)))) break
            }
        }
        dayKey = day
        monthKey = month
        baseDayKey = day
        baseMonthKey = month
        windowStartMs = 0L
        windowEndMs = 0L
        val now = System.currentTimeMillis()
        for (api in APIS) {
            val l = parseLatch(prefString(p, "latch_${api.wire}")) ?: continue
            if (isActive(l, now)) latches.compareAndSet(api.ordinal, null, l)
        }
    }

    private fun savedCount(counts: JSONObject?, sku: GoogleSku): Int =
        (counts?.optLong(sku.wire, 0L) ?: 0L).coerceIn(0L, MAX_SAVED_COUNT).toInt()

    private fun parseLatch(s: String): GoogleLatch? {
        val parts = s.split('|')
        if (parts.size != 3 || parts[0].isEmpty()) return null
        val until = parts[1].toLongOrNull() ?: return null
        val code = parts[2].toIntOrNull() ?: return null
        return GoogleLatch(parts[0], until, code)
    }

    private fun prefString(p: SharedPreferences, key: String): String =
        try {
            p.getString(key, "") ?: ""
        } catch (t: Throwable) {
            ""
        }

    private fun prefInt(p: SharedPreferences, key: String): Int =
        try {
            p.getInt(key, 0).coerceAtLeast(0)
        } catch (t: Throwable) {
            0
        }
}
