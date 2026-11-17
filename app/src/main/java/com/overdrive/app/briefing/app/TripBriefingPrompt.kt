package com.overdrive.app.briefing.app

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.overdrive.app.R
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.navmap.NavSession
import com.overdrive.app.util.AppLocaleContext
import com.overdrive.app.voice.TripBriefingAsk
import com.overdrive.app.voice.VoiceAssistantRuntime
import org.json.JSONObject
import kotlin.math.min

/**
 * The car-start Trip Briefing prompt (dev/trip-briefing/00-DESIGN.md §6), fed by StatusOverlayService's
 * /status poll. Each offer is shown once as a glass card and, when the offer allows it, asked by voice.
 * The first answer wins and closes the other; it is POSTed once and a yes starts directions.
 * Main thread only.
 */
object TripBriefingPrompt {

    private const val TAG = "TripBriefing"
    private const val MAX_SHOW_MS = 20_000L
    private const val MIN_LEFT_MS = 4_000L
    /** The voice turn ends this much before the card, and a confirmation still playing gets as long again. */
    private const val VOICE_MARGIN_MS = 3_000L
    private const val VOICE_GRACE_MS = 5_000L
    /** A card on screen for less than this before it lapsed wasn't really offered. */
    private const val MIN_SEEN_MS = 3_000L
    private const val GEAR_PROBE_MS = 250L
    /** OverDrive's guidance counts as running only if it stepped this recently. */
    private const val GUIDANCE_LIVE_MS = 120_000L
    /** A session kept only for its voice turn ends by this time at the latest. */
    private const val KEPT_VOICE_MAX_MS = 30_000L
    /** A quick check that hasn't answered for this long no longer holds the card back (the status poll still does). */
    private const val PROBE_STALE_MS = 2_000L
    /** The daemon holds a yes up to 2 min while the car is in R; the reply needs a little longer. */
    private const val GO_READ_TIMEOUT_MS = 125_000
    /** No poll answered for this long: the session is over (the daemon is gone or the service stopped). */
    private const val NO_POLL_GIVE_UP_MS = 30_000L
    /** The gear was read this long ago (from the poll's start): it may have changed, so the card steps aside. */
    private const val POLL_STALE_MS = 1_500L
    /** A card shows afresh, or comes back, only below this (a car rolling out of a space is driving). */
    private const val SHOW_MAX_KMH = 5.0
    /** While the daemon wants places, asked at most this often (each ask restarts the sync's debounce). */
    private const val SYNC_ASK_MS = 10_000L
    private const val VIA_CARD = "card"

    private val main = Handler(Looper.getMainLooper())
    private var lastId: String? = null
    private var generation = 0
    private var active: Session? = null
    /** Ready while reversing: shown once the gear leaves R. */
    private var held: Prepared? = null
    private var reversing = false
    private var synced = false
    private var syncAskedAt = 0L
    /** The offer id of the last poll's marker. */
    private var markerId: String? = null
    /** The last good poll read a gear, and it wasn't R. */
    private var gearOk = false
    /** When that poll started (elapsedRealtime): what it read was true then. */
    private var lastGoodPollAt = 0L
    /** The last poll said the car is moving: no card is shown afresh or brought back. */
    private var movingSeen = false
    /** The card's quick gear check: its last verdict (false: R or unknown) and when it last answered. */
    private var probeOk = true
    private var probeAt = 0L
    private var probing = false
    // While a session is up: a gear read every 250 ms, so a shift into R hides the card at once rather than at the
    // next status poll (up to a second later). The card shows only while both agree it may.
    private val gearProbe = object : Runnable {
        override fun run() {
            val s = active
            if (s == null || s.done) return
            if (!probing) {
                probing = true
                TripBriefingIO.execute {
                    val r = TripBriefingIO.getJson("/api/briefing/gear")
                    main.post {
                        probing = false
                        // "unknown" is the status poll's to judge (a reading stale for a moment shouldn't blink the card)
                        val gear = if (r == null || r.isNull("gear")) null else r.optString("gear")
                        if (gear != null && active === s && !s.done) {
                            probeAt = android.os.SystemClock.elapsedRealtime()
                            probeOk = gear != "R"
                            if (!probeOk) reversing = true
                            applyVisibility()
                        }
                    }
                }
            }
            main.postDelayed(this, GEAR_PROBE_MS)
        }
    }
    private val staleCheck = object : Runnable {
        override fun run() {
            val s = active ?: return
            if (android.os.SystemClock.elapsedRealtime() - lastGoodPollAt > NO_POLL_GIVE_UP_MS) {
                close(s)
                return
            }
            applyVisibility()
            main.postDelayed(this, 500L)
        }
    }
    /** A card up: polls run fast (read off the main thread). */
    @Volatile private var wantsFastPoll = false
    /** Asks the status poll to run now (set by StatusOverlayService). */
    @Volatile private var pollNow: Runnable? = null
    /** The marker being fetched; a failed fetch clears it so the next poll tries again. */
    private var fetching: String? = null

    private class Prepared(
        val app: Context,
        val ctx: Context,
        val offer: TripBriefingOffer,
        val texts: TripBriefingTexts,
        val apps: List<String>,
        val spec: CardSpec,
    )

    private class Session(val p: Prepared) {
        var card: TripBriefingCard? = null
        var voice: TripBriefingAsk.Handle? = null
        var voiceEnded = false
        /** How a voice turn ended without an answer: "voice" or "busy". */
        var voiceVia: String? = null
        var done = false
        var graced = false
        var hidden = false
        /** Card time left when it was hidden for reverse; the timer resumes with it (-1: its clock runs). */
        var hiddenLeftMs = -1L
        var shownUntil = 0L
        /** Time the card has been on screen: [visibleMs] so far, plus since [visibleSince] (0 while hidden). */
        var visibleMs = 0L
        var visibleSince = 0L
        val expire: Runnable = Runnable {
            // A voice yes may still be confirming out loud: give it a moment before the card gives up.
            if (voice != null && !graced) {
                graced = true
                main.postDelayed(this.expire, VOICE_GRACE_MS)
            } else {
                endUnanswered(this)
            }
        }

        fun seenMs(): Long =
            visibleMs + if (visibleSince > 0L) android.os.SystemClock.elapsedRealtime() - visibleSince else 0L
    }

    /**
     * Every /status poll; [json] is its "tripBriefing" marker ({id, expiresAt}), null when no offer is
     * pending; the offer itself is read from /api/briefing/offer. The card stays off the reverse camera:
     * it waits, or hides with its clock stopped, while [reversing]; it also hides while the gear is
     * unknown ([gearKnown] false, or no poll begun in the last 1.5 s). The voice turn carries on, its
     * pill hidden too. While [moving] or above 5 km/h ([speedKmh]), no card is shown afresh or brought back.
     * [polledAt] is the elapsedRealtime the poll started.
     */
    @JvmStatic
    @JvmOverloads
    fun onStatus(
        context: Context,
        json: JSONObject?,
        reversing: Boolean = false,
        syncWanted: Boolean = false,
        accOn: Boolean = true,
        moving: Boolean = false,
        polledAt: Long = android.os.SystemClock.elapsedRealtime(),
        gearKnown: Boolean = true,
        speedKmh: Double = Double.NaN,
    ) {
        val app = context.applicationContext ?: context
        // a poll still in flight when the service stopped: nothing to show it with any more
        if (pollNow == null) return
        // once per process at the first poll the daemon answered, and whenever the daemon asks (just turned on)
        val nowEl = android.os.SystemClock.elapsedRealtime()
        if (!synced || (syncWanted && nowEl - syncAskedAt >= SYNC_ASK_MS)) {
            synced = true
            syncAskedAt = nowEl
            TripBriefingSync.request(app)
        }
        // switching off from R isn't driving off: nothing launches or shows (dismiss() follows)
        if (!accOn) return
        lastGoodPollAt = polledAt
        gearOk = gearKnown && !reversing
        this.reversing = reversing && gearKnown
        movingSeen = moving || speedKmh >= SHOW_MAX_KMH
        markerId = json?.optString("id", "")?.takeIf { it.isNotEmpty() }
        val id = json?.optString("id", "").orEmpty()
        // The daemon withdrew the offer (expired, turned off, car moving): the card goes; seen or asked, it was ignored.
        // A voice answer still on its way counts (the daemon takes answers a while after an offer ends).
        active?.let { s ->
            if (!s.done && s.p.offer.id != id) {
                if (s.voice != null) {
                    if (s.card != null) keepForVoice(s)
                } else {
                    endUnanswered(s)
                }
            }
        }
        applyVisibility()
        if (gearOk) held?.let { held = null; present(it) }
        if (json == null) return
        val now = System.currentTimeMillis()
        if (id.isEmpty() || id == lastId || id == fetching || json.optLong("expiresAt", 0L) - now < MIN_LEFT_MS) return
        if (navigating()) return
        fetching = id
        val gen = ++generation
        TripBriefingIO.execute {
            val full = TripBriefingIO.getJson("/api/briefing/offer")?.optJSONObject("tripBriefing")
            val offer = full?.let { TripBriefingOffer.parse(it, System.currentTimeMillis()) }
            main.post {
                if (fetching == id) fetching = null
                if (offer == null || offer.id != id || gen != generation) return@post
                lastId = id
                TripBriefingIO.execute {
                    val p = prepare(app, offer)
                    main.post { if (gen == generation) present(p) }
                }
            }
        }
    }

    // A gear read recently enough to trust, and not R (nor R by the quick check, while it answers).
    private fun gearSafe(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        return gearOk && now - lastGoodPollAt <= POLL_STALE_MS && (probeOk || now - probeAt > PROBE_STALE_MS)
    }

    // The card and the voice pill show only while the gear is known not to be R.
    private fun applyVisibility() {
        val safe = gearSafe()
        val s = active
        if (s != null && !s.done) {
            if (safe) resumeCard(s) else pauseCard(s, stopClock = reversing)
        }
        // only a briefing turn's pill: the assistant's other turns keep theirs (R as seen by a slow poll is stale)
        com.overdrive.app.voice.ui.VoiceOverlay.setSuppressed(s != null && !s.done && (!safe || movingSeen))
    }

    // Hidden while reversing (its clock stops: the driver gets that time back) or while the gear is unknown (it runs).
    private fun pauseCard(s: Session, stopClock: Boolean) {
        // no card left (only its voice turn is finishing): nothing to hide or time
        if (s.card == null) return
        s.card?.setHidden(true)
        if (!s.hidden && s.visibleSince > 0L) {
            s.visibleMs += android.os.SystemClock.elapsedRealtime() - s.visibleSince
            s.visibleSince = 0L
        }
        s.hidden = true
        if (stopClock && s.hiddenLeftMs < 0) {
            main.removeCallbacks(s.expire)
            s.hiddenLeftMs = (s.shownUntil - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }
    }

    private fun resumeCard(s: Session) {
        if (!s.hidden || s.card == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        val untilExpiry = s.p.offer.expiresAt - System.currentTimeMillis()
        val stopped = s.hiddenLeftMs >= 0
        // lapsed while hidden
        if (untilExpiry < MIN_LEFT_MS || (!stopped && s.shownUntil - now <= 0L)) {
            endUnanswered(s)
            return
        }
        // the car is away by now: the card stays gone, a voice answer still counts
        if (movingSeen) {
            if (s.voice == null) endUnanswered(s) else keepForVoice(s)
            return
        }
        s.hidden = false
        if (stopped) {
            val ms = minOf(s.hiddenLeftMs.coerceAtLeast(MIN_LEFT_MS), untilExpiry)
            s.hiddenLeftMs = -1L
            s.shownUntil = now + ms
            main.postDelayed(s.expire, ms)
        }
        s.card?.unhide((s.shownUntil - now).coerceAtLeast(1L))
        s.visibleSince = now
    }

    // a card up, or a yes waiting: a poll that's overdue counts as a lost one
    private fun watchPolls() {
        main.removeCallbacks(staleCheck)
        main.postDelayed(staleCheck, 500L)
    }

    /** A /status poll failed: the gear is unknown, so the card steps aside and a yes waits. */
    @JvmStatic
    fun onStatusLost() {
        gearOk = false
        applyVisibility()
    }

    /** True while a card is up: the status poll stays at 1 s. Any thread. */
    @JvmStatic
    fun wantsFastPoll(): Boolean = wantsFastPoll

    /** StatusOverlayService's "poll now", or null when it stops. */
    @JvmStatic
    fun setPollNow(r: Runnable?) {
        pollNow = r
    }

    private fun updateFastPoll() {
        wantsFastPoll = active != null && active?.done == false
    }

    /** ACC off: closes the card and ends the voice turn, recording no answer (switching off isn't a "no"). */
    @JvmStatic
    fun dismiss() {
        generation++
        held = null
        // an offer the daemon still has (a misread ACC-off) may be shown again
        lastId = null
        active?.let { close(it) }
        updateFastPoll()
        com.overdrive.app.voice.ui.VoiceOverlay.setSuppressed(false)
    }

    // OverDrive's own guidance (another app on screen says nothing about whether it is guiding)
    // (a trip left "running" behind a map that closed before it saw the arrival doesn't count)
    private fun navigating(): Boolean = NavSession.state.navigating && NavSession.guidanceTickAt > 0L &&
        android.os.SystemClock.elapsedRealtime() - NavSession.guidanceTickAt < GUIDANCE_LIVE_MS

    // The card's own expiry counts as ignored even when the voice turn couldn't run.
    private fun noneVia(s: Session) =
        if (s.voice != null || (s.voiceEnded && s.voiceVia == TripBriefingAsk.VIA_VOICE)) TripBriefingAsk.VIA_VOICE else VIA_CARD

    // The card goes; the session stays only for its voice turn, with a fallback end should that never report back.
    private fun keepForVoice(s: Session) {
        s.card?.close()
        s.card = null
        s.hidden = false
        main.removeCallbacks(s.expire)
        main.postDelayed(s.expire, KEPT_VOICE_MAX_MS)
    }

    // Ended with no answer: an ignored offer if it was asked aloud or the card was up long enough to read, else nothing.
    private fun endUnanswered(s: Session) {
        val asked = s.voice != null || (s.voiceEnded && s.voiceVia != TripBriefingAsk.VIA_BUSY)
        if (asked || s.seenMs() >= MIN_SEEN_MS) finish(s, TripBriefingAsk.ANSWER_NONE, null, noneVia(s)) else close(s)
    }


    // off the looper: the config read can wait on the shared file lock, and the package queries are IPC
    private fun prepare(app: Context, offer: TripBriefingOffer): Prepared {
        val ctx = AppLocaleContext.of(app)
        val miles = try {
            UnifiedConfigManager.loadConfig().optJSONObject("tripAnalytics")?.optString("distanceUnit") == "mi"
        } catch (_: Throwable) {
            false
        }
        val apps = TripBriefingLauncher.installedApps(app, offer.app)
        val texts = TripBriefingTexts.build(ctx, offer, miles, System.currentTimeMillis())
        val keys = ArrayList<CardKey>(apps.size + 2)
        for ((i, a) in apps.withIndex()) keys.add(CardKey(a, appName(ctx, a), primary = i == 0))
        if (!texts.named) keys.add(CardKey(TripBriefingCard.KEY_NAME, ctx.getString(R.string.trip_briefing_name_place), false))
        keys.add(CardKey(TripBriefingCard.KEY_NO, ctx.getString(R.string.trip_briefing_no), false))
        // a sentence's full stop is clutter on the card
        val spec = CardSpec(texts.question, texts.title, texts.subtitle?.removeSuffix("."), texts.subtitleIsExtra, keys, night(app))
        return Prepared(app, ctx, offer, texts, apps, spec)
    }

    private fun present(p: Prepared) {
        active?.let { endUnanswered(it) }
        val left = p.offer.expiresAt - System.currentTimeMillis()
        // withdrawn while it was being prepared (or held through R); never shown for the first time on the move
        if (left < MIN_LEFT_MS || navigating() || p.offer.id != markerId || movingSeen) return
        // the gear isn't known to be other than R right now: shown by the next poll that says so
        if (!gearSafe()) {
            held = p
            return
        }
        val showMs = min(left, MAX_SHOW_MS)
        val s = Session(p)
        active = s
        s.card = TripBriefingCard.show(p.app, p.ctx, p.spec, showMs) { key -> onCardKey(s, key) }
        s.shownUntil = android.os.SystemClock.elapsedRealtime() + showMs
        if (s.card != null) s.visibleSince = android.os.SystemClock.elapsedRealtime()
        main.postDelayed(s.expire, showMs)
        val line = p.texts.spokenLine
        if (p.offer.voice && line != null) {
            val askMs = (showMs - VOICE_MARGIN_MS).coerceAtLeast(MIN_LEFT_MS)
            val ask = TripBriefingAsk(p.offer.id, line, p.texts.destSaid, p.texts.durationMin, p.texts.extraLine, p.apps, askMs)
            // the callback may run before this returns
            val handle = try {
                VoiceAssistantRuntime.askTripBriefing(ask) { answer, app, via ->
                    if (Looper.myLooper() == Looper.getMainLooper()) onVoice(s, answer, app, via)
                    else main.post { onVoice(s, answer, app, via) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "voice ask failed: ${t.message}")
                onVoice(s, TripBriefingAsk.ANSWER_NONE, null, TripBriefingAsk.VIA_BUSY)
                null
            }
            if (!s.done && !s.voiceEnded) s.voice = handle
        }
        // neither a card nor a voice turn: nothing to answer
        if (!s.done && s.card == null && s.voice == null) close(s)
        if (!s.done) {
            probeOk = true
            probeAt = 0L
            main.removeCallbacks(gearProbe)
            main.postDelayed(gearProbe, GEAR_PROBE_MS)
            watchPolls()
            Log.i(TAG, "offer ${p.offer.id} shown (card=${s.card != null}, voice=${s.voice != null})")
        }
        updateFastPoll()
    }

    private fun onVoice(s: Session, answer: String?, app: String?, via: String?) {
        if (s.done) return
        s.voiceEnded = true
        s.voice = null
        when (answer) {
            TripBriefingAsk.ANSWER_YES -> {
                val chosen = app ?: s.p.apps.first()
                if (chosen in s.p.apps) {
                    finish(s, TripBriefingAsk.ANSWER_YES, chosen, TripBriefingAsk.VIA_VOICE, explicit = app != null)
                    return
                }
            }
            TripBriefingAsk.ANSWER_NO, TripBriefingAsk.ANSWER_LATER -> {
                finish(s, answer, null, TripBriefingAsk.VIA_VOICE)
                return
            }
        }
        // no answer, or an app that isn't installed: the card stays up
        s.voiceVia = if (via == TripBriefingAsk.VIA_BUSY) TripBriefingAsk.VIA_BUSY else TripBriefingAsk.VIA_VOICE
        if (s.card == null) {
            if (s.voiceVia == TripBriefingAsk.VIA_BUSY) close(s)
            else finish(s, TripBriefingAsk.ANSWER_NONE, null, TripBriefingAsk.VIA_VOICE)
        }
    }

    private fun onCardKey(s: Session, key: String) {
        when (key) {
            TripBriefingCard.KEY_NO -> finish(s, TripBriefingAsk.ANSWER_NO, null, VIA_CARD)
            // naming a place is not a decline: close without an answer
            TripBriefingCard.KEY_NAME -> if (close(s)) TripBriefingLauncher.openSettings(s.p.app)
            // the first key is the default: only another app is a choice worth remembering
            else -> finish(s, TripBriefingAsk.ANSWER_YES, key, VIA_CARD, explicit = key != s.p.apps.first())
        }
    }

    private fun finish(s: Session, answer: String, app: String?, via: String, explicit: Boolean = false) {
        if (!close(s)) return
        Log.i(TAG, "offer ${s.p.offer.id}: $answer${if (app != null) " ($app)" else ""} via $via")
        val body = JSONObject().put("id", s.p.offer.id).put("answer", answer).put("via", via)
        if (app != null && explicit) body.put("app", app)
        if (answer != TripBriefingAsk.ANSWER_YES || app == null) {
            TripBriefingIO.execute { TripBriefingIO.postJson("/api/briefing/answer", body) }
            return
        }
        val p = s.p
        val id = p.offer.id
        val gen = generation
        // Never over the reverse camera: the daemon reads the gear live and answers /go once the car is known not to be
        // in R (at once, normally); a poll's gear here can be seconds old. Its own thread: /go can take up to 120 s.
        Thread({
            // the daemon must hold the yes before /go can find it: one retry for a busy moment
            if (!TripBriefingIO.postJson("/api/briefing/answer", body)) {
                try { Thread.sleep(1_000L) } catch (_: InterruptedException) {}
                TripBriefingIO.postJson("/api/briefing/answer", body)
            }
            val go = TripBriefingIO.postForJson("/api/briefing/go", JSONObject().put("id", id), GO_READ_TIMEOUT_MS)
                ?.optBoolean("go", false) == true
            main.post {
                when {
                    // switched off, or a newer offer: the yes is moot
                    gen != generation -> Log.i(TAG, "offer $id: directions not started (superseded)")
                    go -> if (!TripBriefingLauncher.start(p.app, app, p.texts.title, p.offer.lat, p.offer.lng)) {
                        runCatching {
                            android.widget.Toast.makeText(p.ctx, R.string.trip_briefing_go_failed, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    else -> {
                        Log.i(TAG, "offer $id: directions not started (still reversing, or no reply)")
                        // never silent: the driver said yes and is waiting for something to happen
                        runCatching {
                            android.widget.Toast.makeText(p.ctx, R.string.trip_briefing_go_failed, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }, "TripBriefingGo").apply { isDaemon = true }.start()
    }

    /** Ends [s] once: stops the timer and the voice turn, closes the card. */
    private fun close(s: Session): Boolean {
        if (s.done) return false
        s.done = true
        if (active === s) active = null
        updateFastPoll()
        // a pill hidden for this turn comes back for the assistant's others
        com.overdrive.app.voice.ui.VoiceOverlay.setSuppressed(false)
        main.removeCallbacks(s.expire)
        main.removeCallbacks(gearProbe)
        probeOk = true
        probeAt = 0L
        s.voice?.let { h ->
            s.voice = null
            runCatching { h.cancel() }
        }
        s.card?.close()
        s.card = null
        return true
    }

    private fun appName(ctx: Context, app: String): String = ctx.getString(when (app) {
        TripBriefingAsk.APP_WAZE -> R.string.trip_briefing_app_waze
        TripBriefingAsk.APP_GMAPS -> R.string.trip_briefing_app_gmaps
        TripBriefingAsk.APP_CAR -> R.string.trip_briefing_app_car
        else -> R.string.trip_briefing_app_overdrive
    })

    // the app's own day/night choice; a plain Service context doesn't carry it
    private fun night(app: Context): Boolean = when (AppCompatDelegate.getDefaultNightMode()) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
