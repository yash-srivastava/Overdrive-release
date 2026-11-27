package com.overdrive.app.ui.timeline.player

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.overdrive.app.BuildConfig
import com.overdrive.app.ui.timeline.ClipSpec
import com.overdrive.app.ui.timeline.TlMark
import com.overdrive.app.ui.timeline.TlRoad
import com.overdrive.app.ui.timeline.TlSegment
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Native timeline playback engine. Every public method is thread-safe and returns at once; work is
 * serialised on the "TlControl" thread. Listener callbacks arrive on the main thread.
 */
class TimelinePlayer(context: Context, private val listener: Listener) {

    interface Listener {
        fun onPosition(wallMs: Long, playing: Boolean)
        fun onState(state: State)
        fun onItemChanged(seg: TlSegment?, spec: ClipSpec?)
        fun onSources(families: List<String>, active: String?)
        fun onGap(fromWall: Long, toWall: Long)
        /** A user seek landed where nothing was recorded; [nextWall] is the next footage or -1. */
        fun onSeekGap(atWall: Long, nextWall: Long) {}
        /** A seek while playing landed where nothing was recorded: playback goes on from [nextWall]. */
        fun onSeekGapSkipped(atWall: Long, nextWall: Long) {}
        fun onError(seg: TlSegment?, message: String)
        fun onVideoSize(w: Int, h: Int)
        /** A pending follow expired without starting: nothing will start by itself. */
        fun onFollowDropped() {}
        /** The video decoder failed (it is being recreated or the clip skipped). */
        fun onDecoderError() {}
    }

    enum class State { IDLE, BUFFERING, PLAYING, PAUSED, SCRUBBING, ENDED, ERROR }

    private val main = Handler(Looper.getMainLooper())
    private val controlThread = HandlerThread("TlControl").apply { start() }
    private val ctl = Handler(controlThread.looper)
    private val cache = BlockCache()
    private val clock = MediaClock()
    private val audio = AudioPipeline(cache, clock)

    @Volatile private var released = false
    @Volatile private var positionWall = 0L
    @Volatile private var continueGaps = true
    @Volatile private var skipQuietOn = false
    @Volatile private var quiet = QuietZones.NONE
    @Volatile private var userSpeed = 1
    @Volatile private var sink: FrameSink? = null
    @Volatile private var specProvider: ((TlSegment) -> ClipSpec?)? = null

    /** Wall time (epoch ms) of the frame on screen, or of the scrub target while scrubbing. */
    val positionMs: Long get() = positionWall

    // Last clock-derived position and when it was taken: lets a follower read the wall between ticks.
    @Volatile private var clockWall = Long.MIN_VALUE
    @Volatile private var clockWallAtNs = 0L

    /** Wall of the playing frame now (any thread): the last clock reading moved on by the running clock. */
    fun wallNowMs(): Long {
        val w = positionWall
        if (!clock.running || w != clockWall) return w
        val dtNs = (SystemClock.elapsedRealtimeNanos() - clockWallAtNs).coerceIn(0L, MAX_EXTRAPOLATE_NS)
        return w + (dtNs * clock.speed / 1_000_000f).toLong()
    }

    /** Config of the running video decoder, null when none. */
    fun decoderConfig(): CodecConfig? = pipe?.config

    /** The playback clock runs (false while paused, seeking or waiting for a frame). */
    val clockRunning: Boolean get() = clock.running

    // Control thread only.
    private var segments: List<TlSegment> = emptyList()
    private var marks: List<TlMark> = emptyList()
    private var road: List<TlRoad> = emptyList()
    private var prefer: String? = null
    private var plan = PlaybackPlan.EMPTY
    private var surface: Surface? = null
    private var graceSurface: Surface? = null
    @Volatile private var pipe: VideoPipeline? = null
    private var pipeIds = 0
    private var lastRelease: CountDownLatch? = null
    private var state = State.IDLE
    private var playIntent = false
    private var positionSet = false
    private var targetPending = false
    private var muted = false
    private var audioOn = false
    // The decoder fell behind at a fast speed: keyframes only (at 4x/8x, or skip quiet's fast stretches) until the speed changes.
    private var adaptiveSkim = false
    private var currentItem: PlanItem? = null
    private var gapHold: Epoch? = null
    private var gapRestart: EosInfo? = null
    private var gapPreview: PlanItem? = null
    private var lastGapKey: String? = null
    private var scrubbing = false
    private var scrubWasPlaying = false
    private var userSeek = false
    // Wall where playback ran out at the newest clip; Long.MIN_VALUE when not following the tail.
    private var tailWall = Long.MIN_VALUE
    // Set by followFrom: the awaited saved clip covers tailWall itself.
    private var tailInside = false
    // Clips a follow ran out on or tried and failed: never followed again (reset once playback runs).
    private val tailSkipIds = HashSet<String>()
    private var lastRequested: PlanItem? = null
    // A seek while playing to where the clips aren't loaded yet: plays there once they are (buffering meanwhile).
    private var awaitWall = Long.MIN_VALUE
    private var awaitGaveUp = false
    // Sorted [from, to) wall pairs whose clips are all loaded; null until the screen reports them.
    private var loadedSpans: LongArray? = null
    // A recording is open (the newest day lists one): its saved clip will land, so an end waits for it.
    @Volatile private var liveOpen = false
    @Volatile private var liveSeenAt = 0L
    // When the follow was armed: a wait that outlives any clip length is dropped (no surprise start later).
    private var tailArmedAt = 0L
    private val watchedRetried: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    private var scrubTarget = 0L
    private var scrubIssued = Long.MIN_VALUE
    private var scrubIssuedAtMs = 0L
    private var scrubInFlight = false
    private var failures = 0
    private val codecRetried = HashSet<String>()
    private val watchedPosted = HashSet<String>()
    private var watchKey: String? = null
    private var watchMs = 0L
    private var lastTickMs = 0L
    private var tickCount = 0
    private var tickerOn = false
    private var lastPosPostMs = 0L
    private var lastPosPlaying = false
    private var posTrailing = false
    private var lastFamilies: List<String>? = null
    private var lastActive: String? = null

    private val host = object : PipelineHost {
        override fun onPipelineEvent(p: VideoPipeline, e: PipeEvent) {
            if (!released) ctl.post { handleEvent(p, e) }
        }

        override fun frameSink(): FrameSink? = sink

        override fun specFor(seg: TlSegment): ClipSpec? = try {
            specProvider?.invoke(seg)
        } catch (_: Throwable) {
            null
        }

        override fun continueAcrossGaps(): Boolean = continueGaps
        override fun skipQuiet(): Boolean = skipQuietOn
        override fun quietZones(): QuietZones = quiet
        override fun userSpeed(): Int = userSpeed
    }

    private val tickTask = Runnable { tick() }
    private val bufferingCheck = Runnable {
        if (!released && targetPending && !scrubbing) setState(State.BUFFERING)
    }
    private val trailingPosition = Runnable {
        posTrailing = false
        postPosition()
    }
    private val surfaceGrace = Runnable {
        if (!released && surface == null) {
            graceSurface = null
            detachPipeline()
        }
    }

    init {
        clock.audio = audio.audioClock
    }

    /** Wall spans (sorted [from, to) pairs) whose clips are all loaded; call before [setSegments]. */
    fun setLoadedSpans(spans: LongArray) {
        val copy = spans.copyOf()
        exec { loadedSpans = copy }
    }

    /** Replaces the plan input; the playhead keeps its wall position. */
    fun setSegments(segments: List<TlSegment>) {
        val copy = ArrayList(segments)
        exec { onSegments(copy) }
    }

    /** Extra inputs for "Skip quiet driving": marks and road events near which playback stays at 1x. */
    fun setQuietHints(marks: List<TlMark>, road: List<TlRoad>) {
        val m = ArrayList(marks)
        val r = ArrayList(road)
        exec {
            this.marks = m
            this.road = r
            quiet = QuietZones.build(this.segments, m, r)
        }
    }

    /** Decoder output surface; null detaches (decoder released, position kept). */
    fun setOutput(surface: Surface?) {
        exec { onSurface(surface) }
    }

    fun setFrameSink(sink: FrameSink?) {
        this.sink = sink
    }

    fun play() {
        exec { onPlay() }
    }

    fun pause() {
        exec { onPause() }
    }

    fun seekTo(wallMs: Long, precise: Boolean = true) {
        exec { onSeek(wallMs, precise) }
    }

    /** [resume]: the screen showed a wait as playing, so the release resumes playback. */
    fun beginScrub(resume: Boolean = false) {
        exec { onBeginScrub(resume) }
    }

    fun scrubTo(wallMs: Long) {
        exec { onScrubTo(wallMs) }
    }

    fun endScrub(wallMs: Long) {
        exec { onEndScrub(wallMs) }
    }

    /** 1, 2, 4 or 8. */
    fun setSpeed(speed: Int) {
        val s = when {
            speed >= 8 -> 8
            speed >= 4 -> 4
            speed >= 2 -> 2
            else -> 1
        }
        exec { onSpeed(s) }
    }

    fun setContinueAcrossGaps(on: Boolean) {
        continueGaps = on
    }

    fun setLive(open: Boolean) {
        liveOpen = open
        if (open) liveSeenAt = SystemClock.elapsedRealtime()
    }

    fun setSkipQuietDriving(on: Boolean) {
        exec { onSkipQuiet(on) }
    }

    /** "oemDashcam"/"OEM" prefers the dashcam family, null/"MOSAIC" the default order; sticky. */
    fun setPreferredSource(type: String?) {
        exec { onPrefer(type) }
    }

    fun setMuted(m: Boolean) {
        exec {
            muted = m
            audio.setMuted(m)
        }
    }

    /** Asked on the feeder thread before an item starts; may block briefly. */
    fun setSpecProvider(p: (TlSegment) -> ClipSpec?) {
        specProvider = p
    }

    /** Frees decoders, AudioTrack and threads; returns within 500 ms. Idempotent. */
    fun release() {
        if (released) return
        released = true
        cache.close()
        val done = CountDownLatch(1)
        val posted = ctl.postAtFrontOfQueue {
            try {
                ctl.removeCallbacksAndMessages(null)
                val p = pipe
                pipe = null
                p?.release(PIPE_RELEASE_WAIT_MS)
                audio.release(AUDIO_RELEASE_WAIT_MS)
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }
        if (posted) {
            try {
                done.await(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        controlThread.quitSafely()
        main.removeCallbacksAndMessages(null)
    }

    private fun exec(block: () -> Unit) {
        if (released) return
        ctl.post {
            if (released) return@post
            try {
                block()
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) Log.w(TAG, "control step failed", t)
                fail(currentItem?.seg, "Playback error")
            }
        }
    }

    private fun toMain(block: () -> Unit) {
        main.post {
            if (!released) {
                try {
                    block()
                } catch (t: Throwable) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "listener failed", t)
                }
            }
        }
    }

    // ------------------------------------------------------------ control thread: inputs

    private fun onSegments(list: List<TlSegment>) {
        segments = list
        quiet = QuietZones.build(list, marks, road)
        rebuildPlan(sourceSwitch = false)
    }

    private fun onPrefer(type: String?) {
        val np = PlaybackPlan.normalizePrefer(type)
        if (np == prefer) return
        prefer = np
        rebuildPlan(sourceSwitch = true)
    }

    /** Swaps the plan silently when the shown and queued items are unchanged; otherwise re-seeks in place. */
    private fun rebuildPlan(sourceSwitch: Boolean) {
        val old = plan
        val np = PlaybackPlan.build(segments, prefer)
        plan = np
        val p = pipe
        if (!positionSet) {
            p?.plan = np
            updateSources()
            return
        }
        if (awaitWall != Long.MIN_VALUE && !scrubbing) {
            p?.plan = np
            if (loaded(awaitWall)) startAt(awaitWall, true, false) else updateSources()
            return
        }
        if (tryFollow(np)) return
        if (scrubbing || state == State.ENDED) {
            p?.plan = np
            updateSources()
            return
        }
        if (state == State.ERROR) {
            // Only when the clip under the playhead changed: an unchanged plan would fail the same way.
            if (outputSurface() != null && !sameItemAt(old, np, positionWall)) startAt(positionWall, true, false)
            else {
                p?.plan = np
                updateSources()
            }
            return
        }
        if (p == null) {
            if (outputSurface() != null && state == State.IDLE) startAt(positionWall, true, false)
            else updateSources()
            return
        }
        val preview = gapPreview
        val restart = gapRestart
        val keep = when {
            preview != null -> np.indexFrom(positionWall).let { it >= 0 && np.items[it].sameSlice(preview) }
            restart != null -> restart.next?.let { np.indexOf(it) >= 0 } == true
            else -> sameRun(old, np, currentItem, p.feedingItem)
        }
        if (keep) {
            p.plan = np
            updateSources()
            return
        }
        if (sourceSwitch) sink?.holdFrame()
        startAt(positionWall, precise = true, crossfade = sourceSwitch)
    }

    /** Plays on from [tailWall] once [np] holds the clip it waits for; true when playback started. */
    private fun tryFollow(np: PlaybackPlan): Boolean {
        val tail = tailWall
        if (tail == Long.MIN_VALUE || scrubbing) return false
        if (state != State.ENDED && state != State.PAUSED && state != State.IDLE && state != State.ERROR) return false
        if (SystemClock.elapsedRealtime() - tailArmedAt > TAIL_WAIT_MAX_MS) {
            tailWall = Long.MIN_VALUE
            toMain { listener.onFollowDropped() }
            return false
        }
        // Play pressed inside the recording: the clip saved over that moment. After a natural end only one
        // that starts where playback ran out, never the one that ended (it may have stopped short or failed).
        val skip: Set<String> = if (tailInside) emptySet() else tailSkipIds
        val next = (if (tailInside) np.itemAt(tail) else null)
            ?: np.items.firstOrNull { it.playFrom >= tail - PlaybackPlan.SEAM_MS && it.playTo > tail && it.seg.id !in skip }
            ?: return false
        // Clips tried and failed right at the tail bridge to the one after them (recording didn't stop there).
        var from = tail
        for (it in np.items) {
            if (it.playFrom > from + PlaybackPlan.SEAM_MS || it.playFrom >= next.playFrom) break
            if (it.seg.id in skip && it.playTo > from) from = it.playTo
        }
        val gap = next.playFrom - from
        // Any gap with continue across gaps on (the wait itself is capped); straight on only with it off.
        if (gap > PlaybackPlan.SEAM_MS && !continueGaps) return false
        if (state == State.ERROR) {
            failures = 0
            codecRetried.clear()
        }
        // tailWall stays armed until playback really runs (setState PLAYING): a start that lands nowhere keeps it,
        // but a clip tried once is never tried again (one that fails can't loop on every refresh).
        tailSkipIds.add(next.seg.id)
        tailInside = false
        armTail(tailWall)
        playIntent = true
        startAt(maxOf(tail, next.playFrom), true, false)
        return true
    }

    private fun recordingOpen(): Boolean =
        liveOpen || (liveSeenAt > 0L && SystemClock.elapsedRealtime() - liveSeenAt < LIVE_GRACE_MS)

    /** The clips around [wall] are loaded (always true until the screen reports spans). */
    private fun loaded(wall: Long): Boolean {
        val s = loadedSpans ?: return true
        var i = 0
        while (i + 1 < s.size) {
            if (wall >= s[i] && wall < s[i + 1]) return true
            i += 2
        }
        return false
    }

    /** Holds at [wall], still playing in intent, until its clips load or the wait times out. */
    private fun awaitData(wall: Long) {
        awaitWall = wall
        ctl.removeCallbacks(awaitExpiry)
        ctl.postDelayed(awaitExpiry, AWAIT_DATA_MS)
        targetPending = false
        pipe?.setPlaying(false)
        pipe?.invalidate()
        setCurrentItem(null, null)
        positionWall = wall
        setState(State.BUFFERING)
        postPosition()
    }

    private fun clearAwait() {
        if (awaitWall == Long.MIN_VALUE && !awaitGaveUp) return
        awaitWall = Long.MIN_VALUE
        awaitGaveUp = false
        ctl.removeCallbacks(awaitExpiry)
    }

    // The clips never came (offline, failed load): stop waiting.
    private val awaitExpiry = Runnable {
        val at = awaitWall
        if (released || at == Long.MIN_VALUE || scrubbing) return@Runnable
        awaitGaveUp = true
        startAt(at, true, false)
    }

    /** Arms (or re-arms) the follow at [wall]; it expires on its own, telling the screen, after the wait cap. */
    private fun armTail(wall: Long) {
        tailWall = wall
        tailArmedAt = SystemClock.elapsedRealtime()
        ctl.removeCallbacks(tailExpiry)
        ctl.postDelayed(tailExpiry, TAIL_WAIT_MAX_MS + 1_000L)
    }

    private val tailExpiry = Runnable {
        if (tailWall != Long.MIN_VALUE && SystemClock.elapsedRealtime() - tailArmedAt > TAIL_WAIT_MAX_MS) {
            tailWall = Long.MIN_VALUE
            toMain { listener.onFollowDropped() }
        }
    }

    /** Plays from [wallMs]; nothing to do when a follow already started playing there. */
    fun playFrom(wallMs: Long) = exec {
        val heading = playIntent && kotlin.math.abs(positionWall - wallMs) < PlaybackPlan.SEAM_MS &&
            (targetPending || state == State.PLAYING || state == State.BUFFERING)
        if (heading) return@exec
        tailWall = Long.MIN_VALUE
        playIntent = true
        startAt(wallMs, true, false)
    }

    private fun sameItemAt(old: PlaybackPlan, np: PlaybackPlan, wall: Long): Boolean {
        val i = old.indexFrom(wall)
        val j = np.indexFrom(wall)
        val a = if (i >= 0) old.items[i] else null
        val b = if (j >= 0) np.items[j] else null
        return if (a == null || b == null) a == null && b == null else a.sameSlice(b)
    }

    private fun sameRun(old: PlaybackPlan, np: PlaybackPlan, cur: PlanItem?, feeding: PlanItem?): Boolean {
        if (cur == null) return false
        val i = np.indexOf(cur)
        if (i < 0) return false
        if (feeding == null || feeding.sameSlice(cur)) return true
        val j = np.indexOf(feeding)
        val oi = old.indexOf(cur)
        val oj = old.indexOf(feeding)
        if (j < i || oi < 0 || oj < oi || oj - oi != j - i) return false
        for (k in 0..(j - i)) if (!old.items[oi + k].sameSlice(np.items[i + k])) return false
        return true
    }

    /** Null keeps decoding into the old surface for a short grace (the view keeps it valid), so a swap needs no restart. */
    private fun onSurface(s: Surface?) {
        if (s === surface) return
        val old = surface
        surface = s
        ctl.removeCallbacks(surfaceGrace)
        val p = pipe
        if (s == null) {
            if (p != null && old != null && old.isValid) {
                graceSurface = old
                ctl.postDelayed(surfaceGrace, SURFACE_GRACE_MS)
            } else {
                graceSurface = null
                detachPipeline()
            }
            return
        }
        graceSurface = null
        if (p != null) {
            if (p.surface !== s) p.swapSurface(s)
            return
        }
        if (positionSet && !plan.isEmpty && !scrubbing) startAt(positionWall, true, false)
        else if (scrubbing) issueScrub()
    }

    private fun outputSurface(): Surface? = surface ?: graceSurface

    private fun detachPipeline() {
        if (pipe == null) return
        releasePipeline(hold = false)
        clock.pause()
        audio.pause()
        targetPending = false
        if (state == State.PLAYING) setState(State.BUFFERING)
    }

    private fun onPlay() {
        if (scrubbing) {
            scrubWasPlaying = true
            return
        }
        if (awaitWall != Long.MIN_VALUE) {
            playIntent = true
            return
        }
        if (state == State.ERROR) {
            failures = 0
            codecRetried.clear()
        }
        val hold = gapHold
        if (hold != null) {
            gapHold = null
            playIntent = true
            pipe?.setPlaying(true)
            pipe?.resumeFromGap()
            resumeAudio()
            setState(State.PLAYING)
            return
        }
        val restart = gapRestart
        if (restart != null) {
            gapRestart = null
            playIntent = true
            val next = restart.next
            if (next != null) restartAt(next, restart.endWall, restart.mediaEndUs) else startAt(positionWall, true, false)
            return
        }
        val preview = gapPreview
        if (preview != null) {
            gapPreview = null
            playIntent = true
            positionWall = preview.playFrom
            if (pipe == null) {
                // Nothing is decoding (no surface yet): onSurface starts from here.
                if (outputSurface() != null) startAt(positionWall, true, false)
                else {
                    setState(State.BUFFERING)
                    postPosition()
                }
                return
            }
            if (!targetPending) {
                pipe?.setPlaying(true)
                setState(State.PLAYING)
                startAudioIfWanted()
            } else {
                pipe?.setPlaying(true)
            }
            ensureTicker()
            return
        }
        if (state == State.ENDED) {
            val i = plan.indexFrom(positionWall)
            if (i < 0) return
            playIntent = true
            startAt(maxOf(positionWall, plan.items[i].playFrom), true, false)
            return
        }
        playIntent = true
        val p = pipe
        if (p == null || state == State.IDLE || state == State.ERROR) {
            if (positionSet) startAt(positionWall, true, false)
            else plan.items.firstOrNull()?.let { startAt(it.playFrom, true, false) }
            return
        }
        p.setPlaying(true)
        if (!targetPending) {
            setState(State.PLAYING)
            startAudioIfWanted()
        }
        ensureTicker()
    }

    /**
     * Play pressed where the clip is still being recorded ([inside]: its saved version covers [wallMs]) or
     * where playback ran out at the newest clip: start it once it lands, or now if it already has.
     */
    fun followFrom(wallMs: Long, inside: Boolean = true) = exec {
        // A follow that already fired: nothing to arm.
        if (state == State.PLAYING || state == State.BUFFERING) return@exec
        clearAwait()
        tailInside = inside
        armTail(wallMs)
        tryFollow(plan)
    }

    private fun onPause() {
        tailWall = Long.MIN_VALUE
        clearAwait()
        if (scrubbing) {
            scrubWasPlaying = false
            return
        }
        playIntent = false
        pipe?.setPlaying(false)
        clock.pause()
        audio.pause()
        if (state == State.PLAYING || state == State.BUFFERING) setState(State.PAUSED)
        if (gapPreview == null && gapHold == null && gapRestart == null && !targetPending) updatePositionFromClock()
        postPosition()
    }

    private fun onSeek(wall: Long, precise: Boolean) {
        tailWall = Long.MIN_VALUE
        if (scrubbing) {
            onEndScrub(wall)
            return
        }
        userSeek = true
        startAt(wall, precise, false)
    }

    private fun onSpeed(s: Int) {
        if (s == userSpeed) return
        val wasSkim = skimMode()
        userSpeed = s
        if (!skipQuietOn) adaptiveSkim = false
        if (skipQuietOn) return
        clock.setSpeed(s.toFloat())
        val p = pipe
        p?.skimMode = skimMode()
        p?.kick()
        if (scrubbing) return
        val idle = gapPreview != null || gapHold != null || gapRestart != null || state == State.ENDED
        if (wasSkim && !skimMode() && p != null && positionSet && !idle) {
            startAt(positionWall, true, false)
            return
        }
        if (s <= 2) {
            audio.setSpeed(s.toFloat())
            if (state == State.PLAYING && !audioOn && !targetPending) startAudioIfWanted()
        } else {
            stopAudio()
        }
    }

    private fun onSkipQuiet(on: Boolean) {
        if (on == skipQuietOn) return
        skipQuietOn = on
        adaptiveSkim = false
        clock.setSpeed(if (on) 1f else userSpeed.toFloat())
        val idle = gapPreview != null || gapHold != null || gapRestart != null || state == State.ENDED
        if (positionSet && pipe != null && !scrubbing && !idle) startAt(positionWall, true, false)
    }

    private fun onBeginScrub(resume: Boolean) {
        tailWall = Long.MIN_VALUE
        if (scrubbing) return
        val awaiting = awaitWall != Long.MIN_VALUE
        clearAwait()
        scrubWasPlaying = playIntent || resume || awaiting
        scrubbing = true
        playIntent = false
        targetPending = false
        ctl.removeCallbacks(bufferingCheck)
        pipe?.setPlaying(false)
        // Late events of the request being dragged away from must not move the playhead.
        pipe?.invalidate()
        clock.pause()
        stopAudio()
        gapHold = null
        gapRestart = null
        gapPreview = null
        scrubTarget = positionWall
        scrubIssued = Long.MIN_VALUE
        scrubInFlight = false
        setState(State.SCRUBBING)
    }

    private fun onScrubTo(wall: Long) {
        if (!scrubbing) onBeginScrub(false)
        scrubTarget = wall
        positionWall = wall
        postPosition()
        if (scrubInFlight && SystemClock.uptimeMillis() - scrubIssuedAtMs < SCRUB_STALL_MS) return
        issueScrub()
    }

    /** Decodes the sync frame at or before the latest scrub target; later targets wait for it. */
    private fun issueScrub() {
        val wall = scrubTarget
        val item = plan.itemAt(wall)
        if (item == null) {
            scrubIssued = wall
            scrubInFlight = false
            return
        }
        val s = outputSurface() ?: return
        val p = pipe ?: newPipeline(s)
        p.plan = plan
        p.setPlaying(false)
        scrubInFlight = true
        scrubIssued = wall
        scrubIssuedAtMs = SystemClock.uptimeMillis()
        p.request(FeedRequest(FeedRequest.Kind.SCRUB, item, wall, false, Join.START, 0L, wall, false))
    }

    private fun onEndScrub(wall: Long) {
        if (!scrubbing) {
            userSeek = true
            startAt(wall, true, false)
            return
        }
        scrubbing = false
        scrubInFlight = false
        playIntent = scrubWasPlaying
        userSeek = true
        startAt(wall, precise = true, crossfade = false)
    }

    // ------------------------------------------------------------ control thread: playback

    /** Repositions to [wall]: flushes or restarts the decoder, stops audio until the target frame is up. */
    private fun startAt(wall: Long, precise: Boolean, crossfade: Boolean) {
        // Re-trying a seek that waited for its clips counts as that seek.
        val explicit = userSeek || (awaitWall != Long.MIN_VALUE && wall == awaitWall)
        userSeek = false
        val gaveUp = awaitGaveUp
        clearAwait()
        lastGapKey = null
        gapHold = null
        gapRestart = null
        gapPreview = null
        positionSet = true
        positionWall = wall
        ctl.removeCallbacks(bufferingCheck)
        // Fence first: a frame of the old request in flight can no longer restart the clock.
        pipe?.fence()
        clock.pause()
        stopAudio()
        clock.setSpeed(if (skipQuietOn) 1f else userSpeed.toFloat())
        val idx = plan.indexFrom(wall)
        // Playing on from a seek into clips not loaded yet: wait for them instead of stopping or skipping ahead.
        if (explicit && playIntent && !gaveUp && !loaded(wall)) {
            awaitData(wall)
            return
        }
        // They never came: hold there as a paused seek would, rather than play some other day's footage.
        if (gaveUp && !loaded(wall)) playIntent = false
        if (idx < 0) {
            targetPending = false
            pipe?.setPlaying(false)
            pipe?.invalidate()
            setCurrentItem(null, null)
            if (!plan.isEmpty) playIntent = false
            // Also with nothing playable loaded (e.g. all still indexing): the card says why.
            if (explicit) toMain { listener.onSeekGap(wall, -1L) }
            setState(if (plan.isEmpty) State.IDLE else State.ENDED)
            postPosition()
            return
        }
        val item = plan.items[idx]
        lastRequested = item
        var target = wall
        if (wall < item.playFrom) {
            target = item.playFrom
            if (item.playFrom - wall < PlaybackPlan.SEAM_MS) {
                // A hole under a seam is drawn as solid footage: just land on the next frame.
            } else if (explicit && playIntent) {
                // Moved while playing: nothing here, so it plays on from the next footage.
                positionWall = target
                val next = item.playFrom
                toMain { listener.onSeekGapSkipped(wall, next) }
            } else if (explicit) {
                // The playhead stays where the user put it; Play continues from the next footage.
                playIntent = false
                gapPreview = item
                val next = item.playFrom
                toMain { listener.onSeekGap(wall, next) }
            } else {
                reportGap(wall, item.playFrom)
                if (playIntent && continueGaps) positionWall = target
                else {
                    playIntent = false
                    gapPreview = item
                }
            }
        }
        clock.seekTo((target - item.playFrom) * 1000L)
        val s = outputSurface()
        if (s == null) {
            targetPending = false
            setState(if (playIntent) State.BUFFERING else State.PAUSED)
            postPosition()
            return
        }
        val p = pipe ?: newPipeline(s)
        p.plan = plan
        p.skimMode = skimMode()
        targetPending = true
        p.request(FeedRequest(FeedRequest.Kind.PLAY, item, target, precise, Join.START, 0L, wall, crossfade))
        p.setPlaying(playIntent)
        ctl.postDelayed(bufferingCheck, BUFFERING_DELAY_MS)
        ensureTicker()
        postPosition()
    }

    /** Next item needs another decoder config: new decoder behind the held frame, same media timeline. */
    private fun restartAt(next: PlanItem, gapFrom: Long, mediaStartUs: Long) {
        val s = outputSurface()
        if (s == null) {
            releasePipeline(hold = false)
            startAt(next.playFrom, true, false)
            return
        }
        releasePipeline(hold = true)
        val p = newPipeline(s)
        p.skimMode = skimMode()
        targetPending = true
        lastRequested = next
        p.request(FeedRequest(FeedRequest.Kind.PLAY, next, next.playFrom, true, Join.RESTART, mediaStartUs, gapFrom, true))
        p.setPlaying(playIntent)
        ctl.removeCallbacks(bufferingCheck)
        ctl.postDelayed(bufferingCheck, BUFFERING_DELAY_MS)
        ensureTicker()
    }

    private fun newPipeline(s: Surface): VideoPipeline {
        val p = VideoPipeline(++pipeIds, cache, clock, host, s, plan, lastRelease)
        pipe = p
        p.start()
        return p
    }

    private fun releasePipeline(hold: Boolean) {
        val p = pipe ?: return
        if (hold) sink?.holdFrame()
        pipe = null
        lastRelease = p.release(0L)
    }

    private fun handleEvent(p: VideoPipeline, e: PipeEvent) {
        if (released || p !== pipe) return
        // Surface and size events belong to the decoder, not to one request.
        if (e !is PipeEvent.SurfaceLost && e !is PipeEvent.VideoSize && e.gen != p.reqGen) return
        when (e) {
            is PipeEvent.FirstFrame -> onFirstFrame(p, e.epoch)
            is PipeEvent.TargetShown -> onTargetShown(p, e.epoch)
            is PipeEvent.ScrubShown -> onScrubShown(e.epoch)
            is PipeEvent.GapHold -> onGapHold(e.epoch)
            is PipeEvent.RunEnded -> onRunEnded(e.info)
            is PipeEvent.NeedsRestart -> onNeedsRestart(e.request)
            is PipeEvent.ItemFailed -> onItemFailed(e.item, e.error)
            is PipeEvent.CodecError -> onCodecError(e.item, e.error)
            is PipeEvent.VideoSize -> toMain { listener.onVideoSize(e.width, e.height) }
            is PipeEvent.RenderSpeed -> onRenderSpeed(e.speed)
            is PipeEvent.TooSlow -> onTooSlow()
            is PipeEvent.SurfaceLost -> onSurfaceLost()
        }
    }

    private fun onFirstFrame(p: VideoPipeline, ep: Epoch) {
        if (ep.scrub) return
        failures = 0
        setCurrentItem(ep.item, ep.spec)
        val gapped = ep.join == Join.GAP ||
            (ep.join == Join.RESTART && !PlaybackPlan.contiguous(ep.gapFromWall, ep.item.playFrom))
        if (gapped) reportGap(ep.gapFromWall, ep.item.playFrom)
        if (ep.join == Join.RESTART) p.log?.let { audio.follow(it) }
    }

    private fun onTargetShown(p: VideoPipeline, ep: Epoch) {
        targetPending = false
        ctl.removeCallbacks(bufferingCheck)
        setCurrentItem(ep.item, ep.spec)
        if (gapPreview != null) {
            setState(State.PAUSED)
            postPosition()
            return
        }
        updatePositionFromClock()
        if (playIntent) {
            setState(State.PLAYING)
            if (ep.join == Join.RESTART && audioOn) {
                p.log?.let { audio.follow(it) }
                audio.resume()
            } else {
                startAudioIfWanted()
            }
            ensureTicker()
        } else {
            setState(State.PAUSED)
        }
        postPosition()
    }

    private fun onScrubShown(ep: Epoch?) {
        scrubInFlight = false
        if (ep != null) setCurrentItem(ep.item, ep.spec)
        if (scrubbing && scrubTarget != scrubIssued) issueScrub()
    }

    private fun onGapHold(ep: Epoch) {
        gapHold = ep
        playIntent = false
        audio.pause()
        reportGap(ep.gapFromWall, ep.item.playFrom)
        positionWall = ep.gapFromWall
        setState(State.PAUSED)
        postPosition()
    }

    private fun onRunEnded(info: EosInfo) {
        when (info.reason) {
            EndReason.END -> {
                // Played up to the newest saved clip: the next one (still recording) resumes it when it lands.
                // Only while a recording is open (seen moments ago counts: its stop and last clip land in one
                // refresh): later events of a parked car never start by themselves.
                tailInside = false
                if (playIntent && recordingOpen()) armTail(info.endWall) else tailWall = Long.MIN_VALUE
                // The clip the run was on when it ended (also one that failed before showing a frame).
                (pipe?.feedingItem ?: info.last?.item ?: lastRequested)?.seg?.id?.let { tailSkipIds.add(it) }
                playIntent = false
                clock.pause()
                stopAudio()
                positionWall = info.endWall
                setState(if (info.last == null && failures > 0) State.ERROR else State.ENDED)
                postPosition()
                // The feeder decides END a queue ahead of the screen: the next clip may have landed meanwhile.
                tryFollow(plan)
            }
            EndReason.RESTART -> {
                val next = info.next ?: return
                if (!PlaybackPlan.contiguous(info.endWall, next.playFrom) && !continueGaps) {
                    gapRestart = info
                    playIntent = false
                    clock.pause()
                    audio.pause()
                    positionWall = info.endWall
                    reportGap(info.endWall, next.playFrom)
                    setState(State.PAUSED)
                    postPosition()
                } else {
                    restartAt(next, info.endWall, info.mediaEndUs)
                }
            }
            EndReason.FAILED -> {
                playIntent = false
                clock.pause()
                stopAudio()
                setState(State.ERROR)
            }
            EndReason.SCRUB -> Unit
        }
    }

    private fun onNeedsRestart(req: FeedRequest) {
        val s = outputSurface() ?: return
        releasePipeline(hold = true)
        val p = newPipeline(s)
        p.skimMode = skimMode()
        if (req.kind == FeedRequest.Kind.SCRUB) {
            scrubInFlight = true
            scrubIssuedAtMs = SystemClock.uptimeMillis()
        }
        p.request(
            FeedRequest(req.kind, req.item, req.targetWall, req.precise, req.join, req.mediaStartUs, req.gapFromWall, true)
        )
        p.setPlaying(playIntent)
    }

    private fun onItemFailed(item: PlanItem, error: IOException) {
        failures++
        val msg = when (error) {
            is ClipGoneException -> MSG_GONE
            is ClipChangedException -> MSG_CHANGED
            else -> MSG_LOAD
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "item ${item.seg.id} failed: ${error.message}")
        val seg = item.seg
        toMain { listener.onError(seg, msg) }
    }

    /** Recreate the decoder once per item; a second failure skips the item. */
    private fun onCodecError(item: PlanItem?, error: Throwable) {
        if (BuildConfig.DEBUG) Log.d(TAG, "decoder error: ${error.javaClass.simpleName}")
        toMain { listener.onDecoderError() }
        val key = item?.let { itemKey(it) } ?: "-"
        if (scrubbing) {
            releasePipeline(hold = true)
            scrubInFlight = false
            if (codecRetried.add(key)) issueScrub()
            return
        }
        if (codecRetried.add(key)) {
            releasePipeline(hold = true)
            startAt(positionWall, precise = true, crossfade = true)
            return
        }
        failures++
        val seg = item?.seg
        toMain { listener.onError(seg, MSG_DECODE) }
        releasePipeline(hold = true)
        val from = item?.playTo ?: positionWall
        val next = if (item != null) plan.nextOf(item) ?: plan.next(item.playFrom) else plan.next(positionWall)
        if (next == null || failures >= MAX_FAILURES) {
            // The newest clip can't play while one is recording: the next saved clip still follows (never this one).
            if (next == null && playIntent && item != null && recordingOpen()) {
                tailInside = false
                tailSkipIds.add(item.seg.id)
                armTail(item.playTo)
            }
            playIntent = false
            clock.pause()
            stopAudio()
            setState(if (next == null && failures < MAX_FAILURES) State.ENDED else State.ERROR)
            return
        }
        reportGap(from, next.playFrom)
        startAt(next.playFrom, precise = true, crossfade = true)
    }

    private fun onRenderSpeed(speed: Float) {
        if (speed > 2f) {
            stopAudio()
        } else if (playIntent && state == State.PLAYING) {
            audio.setSpeed(speed)
            if (!audioOn) startAudioIfWanted()
        }
    }

    private fun onTooSlow() {
        if (adaptiveSkim || (!skipQuietOn && userSpeed < 4)) return
        adaptiveSkim = true
        pipe?.skimMode = true
    }

    /** A valid current surface means only the swap failed: restart on it; else wait for setOutput. */
    private fun onSurfaceLost() {
        releasePipeline(hold = false)
        ctl.removeCallbacks(surfaceGrace)
        graceSurface = null
        val s = surface
        if (s != null && s.isValid && positionSet && !plan.isEmpty) {
            if (scrubbing) issueScrub() else startAt(positionWall, true, false)
            return
        }
        surface = null
        clock.pause()
        audio.pause()
        targetPending = false
        if (state == State.PLAYING) setState(State.BUFFERING)
    }

    private fun startAudioIfWanted() {
        if (!playIntent || scrubbing) return
        val lg = pipe?.log ?: return
        val sp = clock.speed
        if (sp > 2f) {
            stopAudio()
            return
        }
        audio.setSpeed(sp)
        audio.start(lg, clock.positionUs())
        audioOn = true
    }

    private fun resumeAudio() {
        if (audioOn) audio.resume() else startAudioIfWanted()
    }

    private fun stopAudio() {
        audio.stop()
        audioOn = false
    }

    private fun skimMode(): Boolean = adaptiveSkim && (skipQuietOn || userSpeed >= 4)

    private fun fail(seg: TlSegment?, msg: String) {
        playIntent = false
        pipe?.setPlaying(false)
        clock.pause()
        stopAudio()
        setState(State.ERROR)
        toMain { listener.onError(seg, msg) }
    }

    // ------------------------------------------------------------ control thread: reporting

    private fun ensureTicker() {
        if (tickerOn || released) return
        tickerOn = true
        lastTickMs = SystemClock.uptimeMillis()
        ctl.postDelayed(tickTask, TICK_MS)
    }

    private fun tick() {
        tickerOn = false
        if (released) return
        val now = SystemClock.uptimeMillis()
        val dt = now - lastTickMs
        lastTickMs = now
        tickCount++
        val following = !scrubbing && gapPreview == null && gapHold == null && gapRestart == null && !targetPending
        if (following && pipe != null) {
            updatePositionFromClock()
            postPosition()
        }
        val stalled = clock.stalledForNs()
        if (state == State.PLAYING && playIntent && stalled > STALL_BUFFERING_NS) {
            setState(State.BUFFERING)
            if (audioOn) audio.pause()
        } else if (state == State.BUFFERING && playIntent && following && pipe != null && clock.running && stalled == 0L) {
            setState(State.PLAYING)
            if (audioOn) audio.resume()
        }
        trackWatched(dt)
        if (tickCount % SOURCES_EVERY_TICKS == 0) updateSources()
        if (tickCount % STUCK_EVERY_TICKS == 0) {
            val p = pipe
            if (p != null && p.stuckForMs() > STUCK_MS) onCodecError(p.feedingItem, IllegalStateException("decoder produced no frames"))
        }
        if (state == State.PLAYING || state == State.BUFFERING || targetPending) ensureTicker()
    }

    private fun updatePositionFromClock() {
        val lg = pipe?.log ?: return
        val w = lg.wallAtMedia(clock.positionUs()) ?: return
        clockWallAtNs = SystemClock.elapsedRealtimeNanos()
        clockWall = w
        positionWall = w
    }

    private fun trackWatched(dt: Long) {
        val item = currentItem
        if (item == null || item.seg.watched || state != State.PLAYING || !playIntent || scrubbing || clock.speed > 2f) {
            watchKey = null
            watchMs = 0L
            return
        }
        val key = itemKey(item)
        if (key != watchKey) {
            watchKey = key
            watchMs = 0L
            return
        }
        watchMs += dt
        val seg = item.seg
        if (watchMs >= WATCHED_AFTER_MS && watchedPosted.add(seg.id + "|" + seg.fp)) postWatched(seg)
    }

    private fun postWatched(seg: TlSegment) {
        val body = "{\"sizeBytes\":${seg.sizeBytes},\"mp4Mtime\":${seg.mp4Mtime}}"
        val key = seg.id + "|" + seg.fp
        val url = BlockCache.BASE_URL + "/api/recordings/id/" + URLEncoder.encode(seg.id, "UTF-8") + "/watched"
        Thread({
            val code = try {
                val rb = Request.Builder().url(url).post(body.toRequestBody(JSON))
                BlockCache.defaultCookie()?.let { rb.header("Cookie", it) }
                val call = BlockCache.sharedClient().newCall(rb.build())
                call.timeout().timeout(WATCHED_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                call.execute().use { it.code }
            } catch (_: Exception) {
                -1
            }
            // A 4xx is retried once later (the server answers 404 for a brief database error too); then left.
            // Anything else retries after a pause, never every tick.
            val retry = code !in 200..299 && (code !in 400..499 || watchedRetried.add(key))
            if (retry) ctl.postDelayed({ watchedPosted.remove(key) }, WATCHED_RETRY_MS)
        }, "TlWatched").apply { isDaemon = true }.start()
    }

    private fun setCurrentItem(item: PlanItem?, spec: ClipSpec?) {
        val cur = currentItem
        if (item == null && cur == null) return
        if (item != null && item.sameSlice(cur)) return
        currentItem = item
        val seg = item?.seg
        toMain { listener.onItemChanged(seg, spec) }
        updateSources()
    }

    private fun updateSources() {
        val fams = plan.familiesAt(positionWall)
        val active = currentItem?.family
        if (fams == lastFamilies && active == lastActive) return
        lastFamilies = fams
        lastActive = active
        toMain { listener.onSources(fams, active) }
    }

    private fun reportGap(from: Long, to: Long) {
        val key = "$from|$to"
        if (key == lastGapKey) return
        lastGapKey = key
        toMain { listener.onGap(from, to) }
    }

    private fun setState(s: State) {
        if (s == state) return
        state = s
        // Playback runs: any armed follow is spent (a later end arms a new one).
        if (s == State.PLAYING) {
            tailWall = Long.MIN_VALUE
            tailSkipIds.clear()
        }
        toMain { listener.onState(s) }
        postPosition()
        if (s == State.PLAYING || s == State.BUFFERING) ensureTicker()
    }

    // At most one onPosition per 100 ms of wall time (34 ms floor at 4x and 8x); the strip extrapolates between
    // them. A play/pause edge goes out at once, and the latest value always goes out.
    private fun postPosition() {
        val now = SystemClock.uptimeMillis()
        val playing = state == State.PLAYING
        val interval = (POSITION_INTERVAL_MS / clock.speed.coerceAtLeast(1f)).toLong().coerceAtLeast(MIN_POSITION_INTERVAL_MS)
        val wait = if (playing != lastPosPlaying) 0L else lastPosPostMs + interval - now
        if (wait > 0) {
            if (!posTrailing) {
                posTrailing = true
                ctl.postDelayed(trailingPosition, wait)
            }
            return
        }
        if (posTrailing) {
            posTrailing = false
            ctl.removeCallbacks(trailingPosition)
        }
        lastPosPostMs = now
        lastPosPlaying = playing
        val w = positionWall
        toMain { listener.onPosition(w, playing) }
    }

    private fun itemKey(item: PlanItem): String = item.seg.id + "|" + item.seg.fp + "|" + item.playFrom

    companion object {
        private const val TAG = "TlPlayer"
        const val MSG_GONE = "This clip is no longer available"
        const val MSG_CHANGED = "This clip changed while playing"
        const val MSG_LOAD = "This clip could not be loaded"
        const val MSG_DECODE = "This clip can't be decoded"
        private const val TICK_MS = 33L
        private const val POSITION_INTERVAL_MS = 100L
        private const val MIN_POSITION_INTERVAL_MS = 34L
        private const val BUFFERING_DELAY_MS = 250L
        private const val STALL_BUFFERING_NS = 500_000_000L
        private const val STUCK_MS = 3_000L
        private const val STUCK_EVERY_TICKS = 15
        private const val SOURCES_EVERY_TICKS = 8
        private const val SCRUB_STALL_MS = 1_000L
        private const val WATCHED_AFTER_MS = 3_000L
        private const val WATCHED_TIMEOUT_MS = 5_000L
        private const val WATCHED_RETRY_MS = 30_000L
        // Longest clip setting (10 min) plus saving, analysis and refreshes, with room to spare.
        private const val TAIL_WAIT_MAX_MS = 20L * 60_000L
        private const val AWAIT_DATA_MS = 20_000L
        private const val LIVE_GRACE_MS = 60_000L
        private const val MAX_FAILURES = 3
        private const val PIPE_RELEASE_WAIT_MS = 250L
        private const val AUDIO_RELEASE_WAIT_MS = 150L
        private const val RELEASE_WAIT_MS = 450L
        private const val SURFACE_GRACE_MS = 2_000L
        private const val MAX_EXTRAPOLATE_NS = 200_000_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
