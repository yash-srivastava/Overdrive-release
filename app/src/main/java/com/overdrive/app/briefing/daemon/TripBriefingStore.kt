package com.overdrive.app.briefing.daemon

import com.overdrive.app.briefing.engine.AppChoice
import com.overdrive.app.briefing.engine.BriefingConfig
import com.overdrive.app.briefing.engine.BriefingOverrides
import com.overdrive.app.briefing.engine.DayType
import com.overdrive.app.briefing.engine.Decline
import com.overdrive.app.briefing.engine.RoutineKey
import com.overdrive.app.briefing.engine.RoutineScore
import com.overdrive.app.briefing.engine.SelfTest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

internal data class UserPlace(val name: String, val lat: Double, val lng: Double)

internal data class SavedPlace(val kind: String, val label: String, val lat: Double, val lng: Double)

internal data class RecentPlace(val label: String, val lat: Double, val lng: Double)

/** Everything trip_briefing.json holds; replaced as a whole on every change. */
internal data class BriefingState(
    val userPlaces: List<UserPlace> = emptyList(),
    val hidden: List<RoutineKey> = emptyList(),
    val apps: List<AppChoice> = emptyList(),
    val declines: List<Decline> = emptyList(),
    val saved: List<SavedPlace> = emptyList(),
    val recent: List<RecentPlace> = emptyList(),
    val selfTest: SelfTest? = null,
    val learnedMs: Long = 0L,
    /** The last handled ACC-on (and the ACC-off after it) in boot [accBoot]: a daemon restart isn't a car start. */
    val accBoot: String = "",
    val accOnMs: Long = 0L,
    val accOffMs: Long = 0L,
) {
    fun overrides(): BriefingOverrides = BriefingOverrides(hidden, apps, declines)

    fun toJson(): JSONObject {
        val o = JSONObject().put("version", VERSION)
        o.put("userPlaces", JSONArray().also { a ->
            userPlaces.forEach { a.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng)) }
        })
        o.put("hidden", JSONArray().also { a -> hidden.forEach { a.put(keyJson(it)) } })
        o.put("apps", JSONArray().also { a ->
            apps.forEach { a.put(JSONObject().put("dLat", it.dLat).put("dLng", it.dLng).put("app", it.app)) }
        })
        o.put("declines", JSONArray().also { a ->
            declines.forEach {
                a.put(keyJson(it.key).put("count", it.count).put("lastMs", it.lastMs)
                    .put("untilMs", it.untilMs).put("rounds", it.rounds).put("snoozeUntilMs", it.snoozeUntilMs))
            }
        })
        o.put("saved", JSONArray().also { a ->
            saved.forEach {
                a.put(JSONObject().put("kind", it.kind).put("label", it.label).put("lat", it.lat).put("lng", it.lng))
            }
        })
        o.put("recent", JSONArray().also { a ->
            recent.forEach { a.put(JSONObject().put("label", it.label).put("lat", it.lat).put("lng", it.lng)) }
        })
        o.put("selfTest", selfTest?.let { BriefingJson.selfTest(it) } ?: JSONObject())
        o.put("learnedMs", learnedMs)
        o.put("accBoot", accBoot).put("accOnMs", accOnMs).put("accOffMs", accOffMs)
        return o
    }

    companion object {
        const val VERSION = 1
        const val MAX_PLACES = 50
        const val MAX_USER_PLACES = 200
        const val MAX_KEYS = 500
        const val MAX_ROUNDS = 8
        private const val MAX_SCORES = 2000

        @JvmField
        val SAVED_KINDS: Set<String> = setOf("home", "work", "fav")

        /** Entries that don't parse are dropped; the rest are kept. */
        fun fromJson(o: JSONObject): BriefingState = BriefingState(
            userPlaces = entries(o, "userPlaces", MAX_USER_PLACES) { e ->
                val name = BriefingJson.cleanLabel(e.opt("name")) ?: return@entries null
                point(e, "lat", "lng")?.let { UserPlace(name, it.lat, it.lng) }
            },
            hidden = entries(o, "hidden", MAX_KEYS) { key(it) },
            apps = entries(o, "apps", MAX_KEYS) { e ->
                val app = (e.opt("app") as? String)?.takeIf { it in TripBriefingSettings.APPS } ?: return@entries null
                point(e, "dLat", "dLng")?.let { AppChoice(it.lat, it.lng, app) }
            },
            declines = entries(o, "declines", MAX_KEYS) { e ->
                val k = key(e) ?: return@entries null
                val count = (e.opt("count") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: return@entries null
                Decline(k, count, e.optLong("lastMs", 0L).coerceAtLeast(0L),
                    e.optLong("untilMs", 0L).coerceAtLeast(0L), e.optInt("rounds", 0).coerceIn(0, MAX_ROUNDS),
                    e.optLong("snoozeUntilMs", 0L).coerceAtLeast(0L))
            },
            saved = entries(o, "saved", MAX_PLACES) { e ->
                val kind = (e.opt("kind") as? String)?.takeIf { it in SAVED_KINDS } ?: return@entries null
                point(e, "lat", "lng")?.let { SavedPlace(kind, BriefingJson.cleanLabel(e.opt("label")) ?: "", it.lat, it.lng) }
            },
            recent = entries(o, "recent", MAX_PLACES) { e ->
                val label = BriefingJson.cleanLabel(e.opt("label")) ?: return@entries null
                point(e, "lat", "lng")?.let { RecentPlace(label, it.lat, it.lng) }
            },
            selfTest = selfTestFrom(o.optJSONObject("selfTest")),
            learnedMs = o.optLong("learnedMs", 0L).coerceAtLeast(0L),
            accBoot = (o.opt("accBoot") as? String)?.take(64) ?: "",
            accOnMs = o.optLong("accOnMs", 0L).coerceAtLeast(0L),
            accOffMs = o.optLong("accOffMs", 0L).coerceAtLeast(0L),
        )

        fun selfTestFrom(o: JSONObject?): SelfTest? {
            if (o == null || !o.has("trips")) return null
            val scores = entries(o, "routines", MAX_SCORES) { e ->
                val id = e.opt("id") as? String ?: return@entries null
                RoutineScore(id, e.optInt("offers", 0).coerceAtLeast(0), e.optInt("correct", 0).coerceAtLeast(0))
            }
            val first = if (o.isNull("firstOfferDay")) null else o.optInt("firstOfferDay", -1).takeIf { it >= 0 }
            return SelfTest(
                o.optInt("trips", 0).coerceAtLeast(0),
                o.optInt("offers", 0).coerceAtLeast(0),
                o.optInt("correct", 0).coerceAtLeast(0),
                scores,
                first,
            )
        }

        private fun keyJson(k: RoutineKey): JSONObject = JSONObject()
            .put("oLat", k.oLat).put("oLng", k.oLng).put("dLat", k.dLat).put("dLng", k.dLng)
            .put("dayType", k.dayType.name)

        private fun key(e: JSONObject): RoutineKey? {
            val dayType = DayType.entries.firstOrNull { it.name == e.opt("dayType") } ?: return null
            val o = point(e, "oLat", "oLng") ?: return null
            val d = point(e, "dLat", "dLng") ?: return null
            return RoutineKey(o.lat, o.lng, d.lat, d.lng, dayType)
        }

        private fun point(e: JSONObject, latKey: String, lngKey: String): LatLng? {
            val lat = (e.opt(latKey) as? Number)?.toDouble() ?: return null
            val lng = (e.opt(lngKey) as? Number)?.toDouble() ?: return null
            return if (BriefingGeo.usable(lat, lng)) LatLng(lat, lng) else null
        }

        private inline fun <T> entries(o: JSONObject, key: String, max: Int, parse: (JSONObject) -> T?): List<T> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<T>()
            for (i in 0 until arr.length()) {
                if (out.size >= max) break
                val e = arr.optJSONObject(i) ?: continue
                parse(e)?.let { out.add(it) }
            }
            return out
        }
    }
}

/** How answers and review choices change the state. Pure. */
internal object BriefingRules {
    const val YES = "yes"
    const val NO = "no"
    const val LATER = "later"
    const val NONE = "none"

    private const val NONE_WEIGHT = 0.5
    private const val DAY_MS = 86_400_000L
    private const val MAX_PAUSE_MS = 90 * DAY_MS
    private const val LADDER_RESET_MS = 180 * DAY_MS
    private const val SNOOZE_MS = 3 * 3_600_000L

    /**
     * no: +1 decline, later and none: +0.5 (later snoozes too, and like a no can start a pause once the count is
     * reached), yes: declines reset (and the app remembered for the destination). [prev] is an earlier "none" for
     * the same offer, which a later answer replaces.
     */
    fun applyAnswer(
        s: BriefingState,
        key: RoutineKey,
        destLat: Double,
        destLng: Double,
        answer: String,
        app: String?,
        prev: String?,
        nowMs: Long,
        cfg: BriefingConfig,
    ): BriefingState {
        val others = s.declines.filterNot { BriefingGeo.sameRoutine(it.key, key, cfg.radiusM) }
        if (answer == YES) {
            val next = s.copy(declines = others)
            return if (app != null) setApp(next, destLat, destLng, app, cfg) else next
        }
        // The count lapses once a window passes with no answer to it, and starts again from zero.
        val windowMs = cfg.declineWindowDays * DAY_MS
        var count = s.declines
            .filter { BriefingGeo.sameRoutine(it.key, key, cfg.radiusM) && nowMs - it.lastMs <= windowMs }
            .sumOf { it.count }
        if (prev == NONE) count = max(0.0, count - NONE_WEIGHT)
        count += if (answer == NO) 1.0 else NONE_WEIGHT
        // A "no" that reaches the limit pauses the routine, longer each time (14, 28, 56, then 90 days at most);
        // ignored cards only count toward the short window. A pause long over starts the ladder again.
        val prior = s.declines.firstOrNull { BriefingGeo.sameRoutine(it.key, key, cfg.radiusM) }
        var rounds = prior?.rounds ?: 0
        var until = prior?.untilMs ?: 0L
        var snooze = prior?.snoozeUntilMs ?: 0L
        if (until > 0L && nowMs - until > LADDER_RESET_MS) rounds = 0
        if ((answer == NO || answer == LATER) && count >= cfg.declineLimit && until <= nowMs) {
            until = nowMs + min(windowMs * (1L shl rounds.coerceAtMost(BriefingState.MAX_ROUNDS)), MAX_PAUSE_MS)
            rounds = (rounds + 1).coerceAtMost(BriefingState.MAX_ROUNDS)
            count = 0.0
        }
        // Any "no" is at least "not now": a restart soon after doesn't ask the same again.
        if (answer == NO || answer == LATER) snooze = nowMs + SNOOZE_MS
        val declines = (others + Decline(key, count, nowMs, until, rounds, snooze))
            .sortedBy { it.lastMs }.takeLast(BriefingState.MAX_KEYS)
        return s.copy(declines = declines)
    }

    /** Ends a pause the declines put on [key] (the driver asked for it back). */
    fun resume(s: BriefingState, key: RoutineKey, cfg: BriefingConfig): BriefingState =
        s.copy(declines = s.declines.filterNot { BriefingGeo.sameRoutine(it.key, key, cfg.radiusM) })

    /**
     * When the routine [key] is paused until, or null: a pause the no's started, or the quiet that
     * ignored cards add up to (as TripBriefingModel's declined check counts it). A snooze isn't one.
     */
    fun pausedUntil(s: BriefingState, key: RoutineKey, nowMs: Long, cfg: BriefingConfig): Long? {
        val windowMs = cfg.declineWindowDays * DAY_MS
        var until = 0L
        var count = 0.0
        var lastMs = 0L
        for (d in s.declines) {
            if (!BriefingGeo.sameRoutine(d.key, key, cfg.radiusM)) continue
            if (d.untilMs > nowMs) until = max(until, d.untilMs)
            if (nowMs - d.lastMs > windowMs) continue
            count += d.count
            lastMs = max(lastMs, d.lastMs)
        }
        if (count >= cfg.declineLimit) until = max(until, lastMs + windowMs)
        return until.takeIf { it > nowMs }
    }

    fun setHidden(s: BriefingState, key: RoutineKey, hidden: Boolean, cfg: BriefingConfig): BriefingState {
        val rest = s.hidden.filterNot { BriefingGeo.sameRoutine(it, key, cfg.radiusM) }
        return s.copy(hidden = if (hidden) (rest + key).takeLast(BriefingState.MAX_KEYS) else rest)
    }

    /** One app per destination; null clears it. */
    fun setApp(s: BriefingState, destLat: Double, destLng: Double, app: String?, cfg: BriefingConfig): BriefingState {
        val rest = s.apps.filterNot { BriefingGeo.distanceM(it.dLat, it.dLng, destLat, destLng) <= cfg.radiusM }
        val apps = if (app != null) (rest + AppChoice(destLat, destLng, app)).takeLast(BriefingState.MAX_KEYS) else rest
        return s.copy(apps = apps)
    }

    /** Clears the driver's choices and the self-test; synced places stay (the app owns them). */
    fun forget(s: BriefingState): BriefingState = s.copy(
        userPlaces = emptyList(),
        hidden = emptyList(),
        apps = emptyList(),
        declines = emptyList(),
        selfTest = null,
        learnedMs = 0L,
    )
}

/** trip_briefing.json behind an in-memory copy. Writes are atomic (tmp + rename) and run on their own thread. */
internal class TripBriefingStore(private val file: File, private val log: BriefingLog) {

    private val lock = Any()
    private val writeLock = Any()
    private val writeQueued = AtomicBoolean(false)

    @Volatile
    private var current: BriefingState? = null

    private val io: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "TripBriefingIO").apply { isDaemon = true } }
    }

    val state: BriefingState
        get() = current ?: synchronized(lock) { current ?: load().also { current = it } }

    fun update(change: (BriefingState) -> BriefingState): BriefingState {
        val next: BriefingState
        val changed: Boolean
        synchronized(lock) {
            val prev = state
            next = change(prev)
            changed = next != prev
            current = next
        }
        if (changed) scheduleWrite()
        return next
    }

    /** Writes the current state now; false when it could not be written. */
    fun writeNow(): Boolean = synchronized(writeLock) {
        val s = current ?: return true
        // An unguessable name, created owner-only and exclusively: the scratch dir is shared.
        val tmp = File(file.path + "." + java.util.UUID.randomUUID().toString().take(8) + ".tmp")
        try {
            openPrivate(tmp).use { out ->
                out.write(s.toJson().toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (tmp.renameTo(file)) return true
            tmp.delete()
            log.warn("state rename failed")
            false
        } catch (e: Exception) {
            tmp.delete()
            log.warn("state write failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Owner-only (0600) from the first byte; throws rather than write a readable file. */
    private fun openPrivate(f: File): FileOutputStream {
        val fd = try {
            android.system.Os.open(
                f.path,
                android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or
                    android.system.OsConstants.O_EXCL or android.system.OsConstants.O_NOFOLLOW or
                    android.system.OsConstants.O_CLOEXEC,
                OWNER_RW,
            )
        } catch (e: LinkageError) {
            // Not on Android (the JVM harness).
            return FileOutputStream(f)
        }
        return FileOutputStream(fd)
    }

    /** Tightens a file a build before owner-only writes left readable. */
    private fun ownerOnly(f: File) {
        try {
            android.system.Os.chmod(f.path, OWNER_RW)
        } catch (e: LinkageError) {
            // Not on Android.
        } catch (e: Exception) {
            log.warn("state chmod failed: ${e.javaClass.simpleName}")
        }
    }

    /** Forget / turning off: the quarantined copy of an unreadable state file goes too, and any write a crash left. */
    fun deleteQuarantined() {
        File(file.path + ".bad").delete()
        synchronized(writeLock) { deleteLeftoverWrites() }
    }

    private fun deleteLeftoverWrites() {
        val prefix = file.name + "."
        file.parentFile?.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun scheduleWrite() {
        if (!writeQueued.compareAndSet(false, true)) return
        try {
            io.execute {
                writeQueued.set(false)
                writeNow()
            }
        } catch (t: Throwable) {
            writeQueued.set(false)
            log.warn("state write not queued: ${t.javaClass.simpleName}")
        }
    }

    private fun load(): BriefingState {
        // Nothing is written before the first load.
        deleteLeftoverWrites()
        if (!file.exists()) return BriefingState()
        // Written world-readable by builds before owner-only writes.
        ownerOnly(file)
        return try {
            if (file.length() > MAX_FILE_BYTES) throw IllegalStateException("too large")
            BriefingState.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            quarantine(e)
            BriefingState()
        } catch (e: StackOverflowError) {
            quarantine(e)
            BriefingState()
        }
    }

    private fun quarantine(cause: Throwable) {
        val bad = File(file.path + ".bad")
        bad.delete()
        val kept = file.renameTo(bad)
        if (kept) ownerOnly(bad)
        log.warn("state file unreadable (${cause.javaClass.simpleName}); starting empty" +
            if (kept) ", kept as ${bad.name}" else "")
    }

    private companion object {
        const val MAX_FILE_BYTES = 1L shl 20
        /** 0600 */
        const val OWNER_RW = 0x180
    }
}
