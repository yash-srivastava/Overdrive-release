package com.overdrive.app.briefing.daemon

import com.overdrive.app.briefing.engine.BriefPlace
import com.overdrive.app.briefing.engine.BriefTrip
import com.overdrive.app.briefing.engine.BriefingConfig
import com.overdrive.app.briefing.engine.BriefingModel
import com.overdrive.app.briefing.engine.DayType
import com.overdrive.app.briefing.engine.NamedPlace
import com.overdrive.app.briefing.engine.Offer
import com.overdrive.app.briefing.engine.PlaceSource
import com.overdrive.app.briefing.engine.Routine
import com.overdrive.app.briefing.engine.RoutineKey
import com.overdrive.app.briefing.engine.TripBriefingModel
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

internal data class PublishedOffer(
    val id: String,
    val expiresAt: Long,
    /** Monotonic twin of [expiresAt]: a wall-clock step back must not keep an offer alive. */
    val deadlineNs: Long,
    val routineId: String,
    /** Null for an offer with no routine behind it (a test offer to a frequent place). */
    val key: RoutineKey?,
    val dest: BriefPlace,
    val durationS: Int,
    val distanceKm: Double,
    val extra: BriefingExtra?,
    val voice: Boolean,
    val app: String,
    val test: Boolean,
)

internal data class BriefingTiming(
    val settleMs: Long = 5_000L,
    val reverseMaxMs: Long = 30_000L,
    val reversePollMs: Long = 1_000L,
    val modelWaitMs: Long = 10_000L,
    /** After a cold boot the trip store can take a while to open. */
    val coldModelWaitMs: Long = 60_000L,
    val extrasBudgetMs: Long = 4_000L,
    val offerTtlMs: Long = 60_000L,
    val answerRetentionMs: Long = 10 * 60_000L,
    val tripEndDebounceMs: Long = 10_000L,
    val startupRetryMs: Long = 5_000L,
    val startupFastWindowMs: Long = 10 * 60_000L,
    val startupSlowRetryMs: Long = 60_000L,
    val followUpRebuildMs: Long = 3 * 60_000L,
    val syncWaitMs: Long = 3_000L,
    val freshFixMs: Long = 30_000L,
    /** An ACC-on edge inside a trip that started longer ago than this is a daemon restart, not a car start. */
    val midTripMs: Long = 120_000L,
    val movingKmh: Double = 15.0,
)

/** Learns routines from the trip history and offers the likely destination when the car starts. */
class TripBriefingService internal constructor(
    private val env: BriefingEnv,
    private val timing: BriefingTiming = BriefingTiming(),
) {
    private val config = BriefingConfig(weekendDays = env.weekendDays())
    internal val store = TripBriefingStore(env.stateFile(), env)
    private val extras = BriefingExtras(env, timing.extrasBudgetMs)

    private val worker = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "TripBriefing").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

    // Written on the worker only.
    @Volatile private var model: BriefingModel? = null
    @Volatile private var trips: List<BriefTrip>? = null
    @Volatile private var tripsInStore: Int? = null
    private val learning = AtomicBoolean(false)
    private val goWaiting = AtomicBoolean(false)
    @Volatile private var syncWanted = false

    private val started = AtomicBoolean(false)
    private val kickQueued = AtomicBoolean(false)
    private val debounceLock = Any()
    private var tripEndRebuild: ScheduledFuture<*>? = null

    private val accLock = Any()
    private var accOn: Boolean? = null
    private val accGen = AtomicLong(0L)
    @Volatile private var preparingGen = NO_GEN

    private val offerLock = Any()
    @Volatile private var pending: PublishedOffer? = null
    /** The pending offer the app has fetched: it is on screen, and its own timer ends it. */
    @Volatile private var deliveredId: String? = null
    private val offers = LinkedHashMap<String, PublishedOffer>()
    private val answers = HashMap<String, String>()
    private var publishedGen = -1L
    private var lastIdMs = 0L

    /** Daemon start: learns as soon as the trip store is usable. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (!env.settings().enabled) return
        val firstNs = System.nanoTime()
        schedule(0L) { startupAttempt(firstNs) }
        if (store.state.saved.isEmpty()) syncWanted = true
    }

    /** ACC edges; a repeat of the current state is ignored, so one car start publishes at most one offer. */
    fun onAccChanged(accOn: Boolean) {
        val gen: Long
        // the first report since this daemon started: ACC may have been on long before (a reboot mid-drive)
        val initial: Boolean
        synchronized(accLock) {
            if (this.accOn == accOn) return
            initial = this.accOn == null
            this.accOn = accOn
            gen = accGen.incrementAndGet()
        }
        if (!accOn) {
            clearPending()
            // Synchronous: the daemon may be stopped right after ACC off, and the next start must see this edge.
            if (env.settings().enabled) recordAcc(false)
            return
        }
        val edgeMs = env.nowMs()
        val edgeNs = System.nanoTime()
        // A thread per car start, so the ACC chain never waits on this: a superseded flow still finishing its
        // extras must not delay this one's checks either.
        try {
            Thread({
                if (!env.settings().enabled) return@Thread
                if (!recordAcc(true, initial)) {
                    env.info("ACC still on since an earlier start this boot (daemon restart); no briefing")
                    return@Thread
                }
                runAccOn(gen, edgeMs, edgeNs, initial)
            }, "TripBriefingAcc").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            env.warn("ACC-on briefing not started: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Persists the edge; false for the daemon's [initial] ACC-on report when it only repeats one already handled this
     * boot with no ACC-off since, which is what a daemon restart looks like. An ACC-on this daemon saw follow an ACC-off
     * is always a new start.
     */
    private fun recordAcc(on: Boolean, initial: Boolean = false): Boolean {
        val boot = env.bootId()
        // Since boot, so a wall-clock correction can't reorder the two edges.
        val now = env.elapsedMs()
        val s = store.state
        // (a saved edge ahead of the clock is from another run of it, whatever the boot id says)
        if (on && initial && boot.isNotEmpty() && s.accBoot == boot && s.accOnMs > s.accOffMs && now >= s.accOnMs &&
            now - s.accOnMs < SAME_DRIVE_MS
        ) {
            return false
        }
        store.update {
            // Edges from another boot, or ahead of this clock, say nothing beside this one: an ACC-off written next to
            // such an ACC-on made every later start look like the same drive.
            val base = if (it.accBoot == boot && it.accOnMs <= now && it.accOffMs <= now) it
            else it.copy(accOnMs = 0L, accOffMs = 0L)
            if (on) base.copy(accBoot = boot, accOnMs = now) else base.copy(accBoot = boot, accOffMs = now)
        }
        store.writeNow()
        return true
    }

    /** /status `tripBriefingSync`: the app should push its saved places now (just turned on, or none yet). No I/O. */
    fun syncWanted(): Boolean = syncWanted

    /** True from ACC-on until this start's offer is published or abandoned; /status `tripBriefingSoon`. No I/O. */
    fun preparingOffer(): Boolean {
        val g = preparingGen
        return g != NO_GEN && g == accGen.get()
    }

    /** A trip was finalized; the rebuild waits until trips stop ending for a few seconds. */
    fun onTripEnded() {
        if (!env.settings().enabled) return
        synchronized(debounceLock) {
            tripEndRebuild?.cancel(false)
            tripEndRebuild = schedule(timing.tripEndDebounceMs) { rebuildNow(reload = true, selfTest = false) }
        }
    }

    /** The /status `tripBriefing` marker ({id, expiresAt}) while an offer is pending; the offer is behind a session. No I/O. */
    fun pendingOfferMarker(): JSONObject? {
        val p = livePending() ?: return null
        return try {
            JSONObject().put("id", p.id).put("expiresAt", p.expiresAt)
        } catch (t: Throwable) {
            null
        }
    }

    /** GET /api/briefing/offer: the pending offer, else null. No I/O. */
    fun pendingOfferJson(): JSONObject? {
        val p = livePending() ?: return null
        // Fetched again, it would be shown afresh (the app lost the first): not to a driver already moving.
        if (deliveredId == p.id && !p.test && moving()) {
            withdraw(p)
            return null
        }
        deliveredId = p.id
        return try {
            BriefingJson.offer(p)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * The pending offer while it's current. One the car drives off from before the app has it is withdrawn;
     * once fetched it stays, so a reply given while pulling away still counts.
     */
    private fun livePending(): PublishedOffer? {
        val p = pending ?: return null
        if (env.nowMs() >= p.expiresAt || System.nanoTime() - p.deadlineNs >= 0L) return null
        if (p.test || deliveredId == p.id) return p
        if (moving()) {
            withdraw(p)
            return null
        }
        return p
    }

    private fun moving(): Boolean {
        val speed = env.vehicleSpeedKmh()
        return speed.isFinite() && speed >= timing.movingKmh
    }

    /** /status `tripBriefingMoving`: the app shows no card for the first time, nor again after hiding one, while true. */
    fun movingNow(): Boolean = moving()

    /** /status `tripBriefingKmh` (NaN when unknown): the app shows a card only while the car is about stopped. */
    fun speedKmhNow(): Double = env.vehicleSpeedKmh()

    /**
     * /status `tripBriefingGear`: "R" (also for a while after it: the camera view can linger), "other", or null when
     * the live reading is stale (the card then hides).
     */
    fun gearNow(): String? = gearLabel(env.gearShown())

    /** GET /api/briefing/gear: as [gearNow] from a reading taken now, for the card's quick checks while it's up. */
    fun gearFresh(): String? = gearLabel(env.readGear()?.gear ?: env.gearShown())

    private fun gearLabel(gear: BriefingGear): String? = when (gear) {
        BriefingGear.REVERSE -> "R"
        BriefingGear.OTHER -> if (env.msSinceReverse() < REVERSE_VIEW_MS) "R" else "other"
        BriefingGear.UNKNOWN -> null
    }

    /**
     * POST /api/briefing/go: returns once the live gear is known not to be R (up to [maxWaitMs]), so a yes never
     * opens directions over the reverse camera; the app's polled gear can be seconds old. False for no such
     * yes, ACC off, or the wait running out.
     */
    internal fun awaitLaunch(id: String, maxWaitMs: Long = GO_MAX_WAIT_MS): Boolean {
        synchronized(offerLock) { if (answers[id] != BriefingRules.YES) return false }
        // one wait at a time: it holds a server thread for up to 120 s
        if (!goWaiting.compareAndSet(false, true)) {
            env.info("go $id refused: another launch is waiting")
            return false
        }
        try {
            return waitOutReverse(maxWaitMs).also { if (!it) env.info("go $id: not launched (ACC off, or still reversing / gear unknown)") }
        } finally {
            goWaiting.set(false)
        }
    }

    /**
     * The gear reading trails the lever (by about a second on some cars): one that just changed isn't trusted for
     * [GEAR_SETTLE_MS], and after R the camera view can stay up for [REVERSE_VIEW_MS] unless the car is under way.
     * Both count what the daemon saw before this wait; the last word is a reading taken [OBSERVED_AFTER_MS] after the
     * yes (from the car itself where it can be read directly), so a shift into R just before the yes is seen and a
     * yes after a while in P still goes before the driver can shift.
     */
    private fun waitOutReverse(maxWaitMs: Long): Boolean {
        val askedAt = env.elapsedMs()
        val endNs = System.nanoTime() + maxWaitMs * 1_000_000L
        var fastSinceNs = -1L
        while (true) {
            if (synchronized(accLock) { accOn } == false) return false
            val now = System.nanoTime()
            val gear = env.gear()
            val kmh = env.vehicleSpeedKmh()
            // a stale moment (DiLink5 between probes) neither counts nor resets
            if (gear == BriefingGear.REVERSE || !(kmh.isFinite() && kmh >= timing.movingKmh)) fastSinceNs = -1L
            else if (fastSinceNs < 0L) fastSinceNs = now
            if (gear == BriefingGear.OTHER && env.elapsedMs() >= askedAt + OBSERVED_AFTER_MS) {
                val underWay = fastSinceNs >= 0L && now - fastSinceNs >= UNDER_WAY_MS * 1_000_000L
                if (env.msSinceGearChange() >= GEAR_SETTLE_MS && (env.msSinceReverse() >= REVERSE_VIEW_MS || underWay)) {
                    val read = env.readGear()
                    if (read != null && read.gear == BriefingGear.OTHER && read.atMs >= askedAt + OBSERVED_AFTER_MS) return true
                }
            }
            if (now - endNs >= 0L) return false
            try {
                Thread.sleep(GO_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private fun withdraw(p: PublishedOffer) {
        synchronized(offerLock) { if (pending === p) pending = null }
    }

    // ── API operations (TripBriefingApiHandler) ─────────────────────────────

    internal enum class LearnResult { DONE, PENDING, UNAVAILABLE }

    internal fun payload(): JSONObject {
        if (model == null) kickBuild()
        return BriefingJson.payload(env.settings(), model, store.state, config.radiusM, learning.get(),
            tripsInStore ?: trips?.size, env.nowMs(), config)
    }

    /** Reloads the trips and runs the self-test, waiting up to [BriefingTiming.syncWaitMs]. */
    internal fun learn(): LearnResult {
        learning.set(true)
        val w = runAndWait {
            try {
                rebuildNow(reload = true, selfTest = true)
            } finally {
                learning.set(false)
            }
        }
        return when {
            !w.done -> LearnResult.PENDING
            w.value == Build.OK -> LearnResult.DONE
            else -> LearnResult.UNAVAILABLE
        }
    }

    internal fun updateSettings(patch: JSONObject): Boolean {
        if (!env.saveSettings(patch)) return false
        when (patch.opt("enabled")) {
            false -> {
                // An ACC-on flow still preparing must not publish after this.
                accGen.incrementAndGet()
                clearPending()
                syncWanted = false
                store.update { it.copy(saved = emptyList(), recent = emptyList()) }
                store.deleteQuarantined()
                schedule(0L) { rebuildNow(reload = false, selfTest = false) }
            }
            true -> {
                // Edges seen while off weren't recorded: the next car start must not pass for a daemon restart.
                store.update { it.copy(accOnMs = 0L, accOffMs = 0L) }
                syncWanted = true
                start()
                kickBuild()
            }
        }
        return true
    }

    /** Names the place at [at], or clears the driver's name for it when [name] is null. */
    internal fun setPlace(at: LatLng, name: String?) {
        val places = model?.places.orEmpty()
        val target = nearestPlace(places, at.lat, at.lng)
        store.update { s ->
            val keep = s.userPlaces.filterNot { u ->
                BriefingGeo.distanceM(u.lat, u.lng, at.lat, at.lng) <= SAME_SPOT_M ||
                    (target >= 0 && nearestPlace(places, u.lat, u.lng) == target)
            }
            val next = if (name == null) keep else (keep + UserPlace(name, at.lat, at.lng)).takeLast(BriefingState.MAX_USER_PLACES)
            s.copy(userPlaces = next)
        }
        runAndWait { rebuildNow(reload = false, selfTest = false) }
    }

    /** False when [id] is neither a current routine nor a parsable routine id. */
    internal fun setRoutine(id: String, hidden: Boolean?, appGiven: Boolean, app: String?, resume: Boolean = false): Boolean {
        val key = model?.routines?.firstOrNull { it.id == id }?.let { keyOf(it) }
            ?: BriefingGeo.parseRoutineId(id)
            ?: return false
        store.update { s ->
            var n = s
            if (hidden != null) n = BriefingRules.setHidden(n, key, hidden, config)
            if (appGiven) n = BriefingRules.setApp(n, key.dLat, key.dLng, app, config)
            if (resume) n = BriefingRules.resume(n, key, config)
            n
        }
        runAndWait { rebuildNow(reload = false, selfTest = false) }
        return true
    }

    internal fun forget() {
        store.update { BriefingRules.forget(it) }
        store.deleteQuarantined()
        runAndWait { rebuildNow(reload = false, selfTest = false) }
    }

    /** Both lists are replaced on every sync, never merged: the daemon keeps only what the app has now. */
    internal fun placesSync(saved: List<SavedPlace>, recent: List<RecentPlace>) {
        // The app syncs whatever the setting; saved places stay in the app while the feature is off.
        if (!env.settings().enabled) {
            if (store.state.saved.isNotEmpty() || store.state.recent.isNotEmpty()) {
                store.update { it.copy(saved = emptyList(), recent = emptyList()) }
            }
            return
        }
        syncWanted = false
        val before = store.state
        val after = store.update { it.copy(saved = saved, recent = recent) }
        // Turned off while this sync was in flight: it must not leave the places behind.
        if (!env.settings().enabled) {
            store.update { it.copy(saved = emptyList(), recent = emptyList()) }
            return
        }
        if (after != before) schedule(0L) { rebuildNow(reload = false, selfTest = false) }
    }

    /** False for an unknown offer id; an offer stays answerable for answerRetentionMs after it expires. */
    internal fun answer(id: String, answer: String, app: String?, via: String): Boolean {
        val now = env.nowMs()
        val offer: PublishedOffer
        val prev: String?
        // An ignored card is no answer once the car is off or Trip Briefing is turned off (the card just went away).
        val dropNone = answer == BriefingRules.NONE &&
            (synchronized(accLock) { accOn } == false || !env.settings().enabled)
        synchronized(offerLock) {
            offer = offers[id] ?: return false
            // The voice turn never ran; the card still decides.
            if (via == VIA_BUSY || dropNone) return true
            prev = answers[id]
            if (prev == answer || prev == BriefingRules.YES || prev == BriefingRules.NO || prev == BriefingRules.LATER) return true
            answers[id] = answer
            if (pending?.id == id) pending = null
        }
        val key = offer.key
        if (offer.test || key == null) return true
        store.update {
            BriefingRules.applyAnswer(it, key, offer.dest.lat, offer.dest.lng, answer, app, prev, now, config)
        }
        schedule(0L) { rebuildNow(reload = false, selfTest = false) }
        return true
    }

    /** Publishes an offer from the current position now, ignoring every gate but `enabled`. */
    internal fun testOffer(): JSONObject {
        val settings = env.settings()
        if (!settings.enabled) throw BriefingUnavailable("Trip Briefing is turned off")
        // a test tapped on a phone mustn't pop a card up in front of someone driving
        if (moving()) throw BriefingUnavailable("the car is moving")
        val m = model
            ?: runAndWait { if (model == null) rebuildNow(reload = true, selfTest = false); model }.value
            ?: throw BriefingUnavailable("trip history is not loaded yet")
        val origin = origin() ?: throw BriefingUnavailable("no position yet")
        val now = env.nowMs()
        val offer = m.predict(now, origin.lat, origin.lng)
            ?: fallbackOffer(m, origin, now)
            ?: throw BriefingUnavailable("no destinations learned yet")
        val routine = m.routines.firstOrNull { it.id == offer.routineId }
        val extra = if (settings.extras) extras.compute(offer, routine, origin, now, local = true, online = false) else null
        val p = publish(TEST_GEN, offer, routine, extra, settings)
            ?: throw BriefingUnavailable("offer not published")
        return BriefingJson.offer(p)
    }

    // ── ACC-on flow ─────────────────────────────────────────────────────────

    private fun runAccOn(gen: Long, edgeMs: Long, edgeNs: Long, initial: Boolean) {
        try {
            if (!isCurrent(gen) || !env.settings().enabled) return
            synchronized(accLock) { preparingGen = gen }
            // DeferredNavManager prompts for its queued target at this ACC-on; the two must not overlap.
            if (env.deferredNavPending()) {
                env.info("deferred navigation prompt queued; no briefing")
                return
            }
            val tripStart = env.activeTripStartMs()
            if (tripStart > 0L && tripStart < edgeMs - timing.midTripMs) {
                env.info("ACC on inside a running trip; no briefing")
                return
            }
            val speed = env.vehicleSpeedKmh()
            if (speed.isFinite() && speed >= timing.movingKmh) {
                env.info("ACC on while moving; no briefing")
                return
            }
            val settleLeftMs = timing.settleMs - (System.nanoTime() - edgeNs) / 1_000_000L
            if (settleLeftMs > 0 && !pause(gen, settleLeftMs)) return
            var waited = 0L
            while (env.inReverse() && waited < timing.reverseMaxMs) {
                if (!pause(gen, timing.reversePollMs)) return
                waited += timing.reversePollMs
            }
            if (env.inReverse()) {
                env.info("still reversing after ${timing.reverseMaxMs / 1000}s; no briefing")
                return
            }
            // ACC already on when the daemon came up: a head unit rebooted mid-drive (stopped in traffic, say) looks
            // like a cold start, so only a car still in P counts as starting out
            if (initial && !(inParkKnown(gen) ?: return)) {
                env.info("ACC was on before the daemon started and the car isn't in P; no briefing")
                return
            }
            val m = awaitModel(gen, if (initial) timing.coldModelWaitMs else timing.modelWaitMs) ?: return
            // the long wait after a cold boot: the driver may have set off meanwhile
            if (initial && !(inParkKnown(gen) ?: return)) {
                env.info("car left P while the trip model loaded; no briefing")
                return
            }
            val origin = origin() ?: run {
                env.info("no origin (no last trip end, no fresh fix); no briefing")
                return
            }
            val now = env.nowMs()
            val offer = m.predict(now, origin.lat, origin.lng) ?: run {
                env.info("no confident destination; no briefing")
                return
            }
            val settings = env.settings()
            if (!settings.enabled || !isCurrent(gen)) return
            val routine = m.routines.firstOrNull { it.id == offer.routineId }
            val extra = if (settings.extras || settings.onlineExtras) {
                extras.compute(offer, routine, origin, now, local = settings.extras, online = settings.onlineExtras)
            } else {
                null
            }
            if (drivingNow(edgeMs) || (initial && !(inParkKnown(gen) ?: return))) {
                env.info("moving, mid-trip or out of P by publish time; no briefing")
                return
            }
            if (!env.settings().enabled) return
            publish(gen, offer, routine, extra, settings)
        } catch (t: Throwable) {
            env.warn("ACC-on briefing failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            synchronized(accLock) { if (preparingGen == gen) preparingGen = NO_GEN }
        }
    }

    private fun isCurrent(gen: Long): Boolean = accGen.get() == gen

    private fun drivingNow(edgeMs: Long): Boolean {
        val tripStart = env.activeTripStartMs()
        if (tripStart > 0L && tripStart < edgeMs - timing.midTripMs) return true
        val speed = env.vehicleSpeedKmh()
        return (speed.isFinite() && speed >= timing.movingKmh) || env.inReverse()
    }

    // In P, asked once the gear reading is known again (DiLink5's goes stale for a moment between probes); null when
    // the ACC edge was superseded meanwhile.
    private fun inParkKnown(gen: Long): Boolean? {
        val poll = min(timing.reversePollMs, GEAR_KNOWN_POLL_MS)
        var waited = 0L
        while (env.gear() == BriefingGear.UNKNOWN && waited < timing.reverseMaxMs) {
            if (!pause(gen, poll)) return null
            waited += poll
        }
        return env.inPark()
    }

    /** Sleeps unless the ACC edge [gen] is superseded first; true when it is still current. */
    private fun pause(gen: Long, ms: Long): Boolean {
        val endNs = System.nanoTime() + ms * 1_000_000L
        while (isCurrent(gen)) {
            val leftMs = (endNs - System.nanoTime()) / 1_000_000L
            if (leftMs <= 0L) return true
            try {
                Thread.sleep(min(leftMs, PAUSE_SLICE_MS))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun awaitModel(gen: Long, waitMs: Long): BriefingModel? {
        model?.let { return it }
        kickBuild()
        val endNs = System.nanoTime() + waitMs * 1_000_000L
        while (System.nanoTime() < endNs) {
            if (!pause(gen, MODEL_POLL_MS)) return null
            model?.let { return it }
        }
        env.info("no trip model yet; no briefing")
        return null
    }

    private fun origin(): LatLng? = chooseOrigin(env.lastTripEnd(), env.gpsFix(), timing.freshFixMs)

    private fun publish(
        gen: Long,
        offer: Offer,
        routine: Routine?,
        extra: BriefingExtra?,
        settings: TripBriefingSettings,
    ): PublishedOffer? {
        val test = gen == TEST_GEN
        val key = offer.from?.let { RoutineKey(it.lat, it.lng, offer.dest.lat, offer.dest.lng, offer.dayType) }
            ?: routine?.let { keyOf(it) }
            ?: BriefingGeo.parseRoutineId(offer.routineId)
        val app = offer.app?.takeIf { it in TripBriefingSettings.APPS } ?: settings.defaultApp
        synchronized(offerLock) {
            if (!test) {
                if (!isCurrent(gen) || publishedGen == gen) return null
                publishedGen = gen
            }
            val now = env.nowMs()
            val idMs = if (now > lastIdMs) now else lastIdMs + 1
            lastIdMs = idMs
            val p = PublishedOffer(
                id = "b-$idMs",
                expiresAt = now + timing.offerTtlMs,
                deadlineNs = System.nanoTime() + timing.offerTtlMs * 1_000_000L,
                routineId = offer.routineId,
                key = key,
                dest = offer.dest,
                durationS = offer.medianDurationS,
                distanceKm = offer.medianDistanceKm,
                extra = extra,
                voice = settings.voice,
                app = app,
                test = test,
            )
            pruneOffers(now)
            offers[p.id] = p
            pending = p
            env.info("offered ${p.id}${if (test) " (test)" else ""}: ${offer.dest.role?.name ?: "place"}, " +
                "extra=${extra?.type ?: "none"}")
            return p
        }
    }

    /** Caller holds [offerLock]. */
    private fun pruneOffers(now: Long) {
        val it = offers.values.iterator()
        while (it.hasNext()) {
            val o = it.next()
            if (now - o.expiresAt > timing.answerRetentionMs || offers.size > MAX_OFFERS) {
                it.remove()
                answers.remove(o.id)
            }
        }
    }

    private fun clearPending() {
        synchronized(offerLock) { pending = null }
    }

    // ── Model ───────────────────────────────────────────────────────────────

    private fun startupAttempt(firstNs: Long) {
        val built = if (model != null) Build.OK else rebuildNow(reload = true, selfTest = false)
        if (built == Build.NOT_READY) {
            val elapsedMs = (System.nanoTime() - firstNs) / 1_000_000L
            val retryMs = if (elapsedMs < timing.startupFastWindowMs) timing.startupRetryMs else timing.startupSlowRetryMs
            schedule(retryMs) { startupAttempt(firstNs) }
            return
        }
        // A build that threw would throw again on the same history; the next trip end retries.
        if (built == Build.FAILED) return
        // Startup recovery can still add trips after the first load.
        schedule(timing.followUpRebuildMs) { rebuildNow(reload = true, selfTest = false) }
    }

    private enum class Build { OK, NOT_READY, FAILED }

    /** Worker thread only. */
    private fun rebuildNow(reload: Boolean, selfTest: Boolean): Build {
        return try {
            var t = trips
            if (reload || t == null) {
                val load = env.loadTrips() ?: return Build.NOT_READY
                val previous = t
                val shortRead = load.rowsRead == 0 && load.storeCount > 0 && !previous.isNullOrEmpty()
                t = if (shortRead) {
                    env.warn("trip store read ${load.rowsRead} of ${load.storeCount} rows; keeping ${previous!!.size} trips")
                    previous
                } else {
                    load.trips
                }
                trips = t
                tripsInStore = load.storeCount
            }
            val state = store.state
            val now = env.nowMs()
            val m = TripBriefingModel.build(t, namedPlaces(state, env.safeZones()), state.overrides(), now, env.zone(), config)
            model = m
            if (selfTest) {
                val st = m.selfTest()
                store.update { it.copy(selfTest = st, learnedMs = now) }
            }
            env.info("model built: ${t.size} trips, ${m.routines.size} routines")
            Build.OK
        } catch (e: Throwable) {
            env.warn("model build failed: ${e.javaClass.simpleName}: ${e.message}")
            Build.FAILED
        }
    }

    private fun kickBuild() {
        if (model != null || !kickQueued.compareAndSet(false, true)) return
        val queued = schedule(0L) {
            kickQueued.set(false)
            if (model == null) rebuildNow(reload = true, selfTest = false)
        }
        if (queued == null) kickQueued.set(false)
    }

    private fun schedule(delayMs: Long, task: () -> Unit): ScheduledFuture<*>? = try {
        worker.schedule(Runnable { task() }, delayMs, TimeUnit.MILLISECONDS)
    } catch (t: Throwable) {
        env.warn("task not scheduled: ${t.javaClass.simpleName}")
        null
    }

    private class Waited<T>(val done: Boolean, val value: T?)

    /** Runs [block] on the worker and waits up to [BriefingTiming.syncWaitMs]. Never call from the worker. */
    private fun <T> runAndWait(block: () -> T): Waited<T> {
        val f = try {
            worker.submit(Callable { block() })
        } catch (t: Throwable) {
            return Waited(false, null)
        }
        return try {
            Waited(true, f.get(timing.syncWaitMs, TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            Waited(false, null)
        } catch (e: ExecutionException) {
            Waited(true, null)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Waited(false, null)
        }
    }

    private fun nearestPlace(places: List<BriefPlace>, lat: Double, lng: Double): Int {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in places.indices) {
            val d = BriefingGeo.distanceM(lat, lng, places[i].lat, places[i].lng)
            if (d <= config.radiusM && d < bestD) {
                best = i
                bestD = d
            }
        }
        return best
    }

    /** test-offer when nothing is confident: the busiest routine from here, else any, else the busiest place. */
    private fun fallbackOffer(m: BriefingModel, origin: LatLng, nowMs: Long): Offer? {
        val shown = m.routines.filter { !it.hidden }
        val near = shown.filter {
            BriefingGeo.distanceM(it.from.lat, it.from.lng, origin.lat, origin.lng) <= config.radiusM
        }
        val r = near.ifEmpty { shown }.maxByOrNull { it.trips }
        if (r != null) {
            return Offer(
                routineId = r.id,
                dest = r.to,
                dayType = r.dayType,
                share = 1.0,
                matches = r.trips,
                medianDurationS = r.medianDurationS,
                medianDistanceKm = r.medianDistanceKm,
                app = r.app,
                lastTripId = r.lastTripId,
                from = r.from,
            )
        }
        val from = nearestPlace(m.places, origin.lat, origin.lng)
        val dest = m.places.filterIndexed { i, _ -> i != from }.maxByOrNull { it.visits } ?: return null
        val dow = Instant.ofEpochMilli(nowMs).atZone(env.zone()).dayOfWeek
        return Offer(
            routineId = "",
            dest = dest,
            dayType = if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) DayType.WEEKEND else DayType.WEEKDAY,
            share = 1.0,
            matches = 0,
            medianDurationS = 0,
            medianDistanceKm = 0.0,
            app = null,
        )
    }

    companion object {
        private const val TEST_GEN = Long.MIN_VALUE
        private const val NO_GEN = -1L
        private const val VIA_BUSY = "busy"
        private const val MAX_OFFERS = 16
        private const val PAUSE_SLICE_MS = 200L
        private const val GEAR_KNOWN_POLL_MS = 100L
        private const val MODEL_POLL_MS = 250L
        private const val SAME_SPOT_M = 50.0
        private const val ORIGIN_MATCH_M = 500.0
        /** An ACC-on repeated within this long, with no ACC-off in between, is the same drive. */
        private const val SAME_DRIVE_MS = 6 * 3_600_000L
        /** How long a yes waits for the car to leave R before its directions are dropped. */
        const val GO_MAX_WAIT_MS = 120_000L
        /** How long "not R" must hold before a yes's directions open (the reading trails the lever). */
        private const val GEAR_SETTLE_MS = 1_200L
        /** After reversing, the car's own camera view may stay up in D: directions wait this long, or until moving. */
        private const val REVERSE_VIEW_MS = 8_000L
        /** At driving speed this long (a lone GPS spike doesn't count). */
        private const val UNDER_WAY_MS = 1_000L
        /** The deciding gear reading is taken this long after the yes arrived (the car's own signal lags a little). */
        private const val OBSERVED_AFTER_MS = 500L
        private const val GO_POLL_MS = 100L

        @Volatile
        private var instance: TripBriefingService? = null

        @JvmStatic
        fun get(): TripBriefingService = instance ?: synchronized(this) {
            instance ?: TripBriefingService(DaemonBriefingEnv).also { instance = it }
        }

        private fun keyOf(r: Routine) = RoutineKey(r.from.lat, r.from.lng, r.to.lat, r.to.lng, r.dayType)

        /** The last trip's end when a fresh fix agrees within 500 m or there is no fresh fix; else the fix. */
        internal fun chooseOrigin(lastEnd: LatLng?, fix: GpsFix?, freshMs: Long): LatLng? {
            val fresh = fix?.takeIf { it.ageMs in 0..freshMs && BriefingGeo.usable(it.lat, it.lng) }
                ?.let { LatLng(it.lat, it.lng) }
            val end = lastEnd?.takeIf { BriefingGeo.usable(it.lat, it.lng) }
            return when {
                end == null -> fresh
                fresh == null -> end
                BriefingGeo.distanceM(end.lat, end.lng, fresh.lat, fresh.lng) <= ORIGIN_MATCH_M -> end
                else -> fresh
            }
        }

        internal fun namedPlaces(state: BriefingState, zones: List<NamedPlace>): List<NamedPlace> {
            val out = ArrayList<NamedPlace>()
            for (u in state.userPlaces) out.add(NamedPlace(u.name, u.lat, u.lng, PlaceSource.USER))
            for (s in state.saved) {
                when (s.kind) {
                    "home" -> out.add(NamedPlace(s.label.ifBlank { "Home" }, s.lat, s.lng, PlaceSource.SAVED_HOME))
                    "work" -> out.add(NamedPlace(s.label.ifBlank { "Work" }, s.lat, s.lng, PlaceSource.SAVED_WORK))
                    else -> if (s.label.isNotBlank()) out.add(NamedPlace(s.label, s.lat, s.lng, PlaceSource.SAVED))
                }
            }
            out.addAll(zones)
            for (r in state.recent) out.add(NamedPlace(r.label, r.lat, r.lng, PlaceSource.RECENT))
            return out
        }
    }
}
