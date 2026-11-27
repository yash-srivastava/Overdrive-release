package com.overdrive.app.ui.timeline.player

import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.overdrive.app.BuildConfig
import com.overdrive.app.ui.timeline.TlSegment
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Dashcam view follower (issue #1414): plays the windshield clip under the leader's wall instant, muted,
 * into the renderer's second input and keeps it in step with the leader (the timeline player, which it never
 * drives). Rate nudge for small drift, bounded hard seeks for large drift. State lives on its own thread;
 * public methods return at once.
 */
class DashcamFollower(
    private val out: Output,
    private val leader: Leader,
    private val listener: Listener,
) {
    /** Leader clock; any thread. */
    interface Leader {
        fun wallNowMs(): Long
        fun clockRunning(): Boolean
    }

    /** Renderer side; any thread. */
    interface Output {
        /** False when there is no renderer; [onSurface] may run on any thread, null on failure. */
        fun open(onSurface: (Surface?) -> Unit): Boolean
        fun close()
        fun setShown(on: Boolean)
        fun setVideoSize(w: Int, h: Int)
        fun frames(): Int
        fun frameNs(): Long
    }

    interface Listener {
        /** Main thread: a second decoder can't run here; the mode should no longer be offered. */
        fun onUnavailable()
    }

    enum class LeaderState { PLAYING, PAUSED, BUFFERING, SCRUBBING, ENDED, OTHER }

    private val thread = HandlerThread("TlDashcam").apply { start() }
    private val h = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val cache = BlockCache(maxBytes = CACHE_BYTES)
    @Volatile private var released = false

    // Follower thread only.
    private var enabled = false
    private var visible = false
    private var unavailable = false
    private var oem: List<TlSegment> = emptyList()
    private var leaderSeg: TlSegment? = null
    private var leaderGrid = false
    private var leaderState = LeaderState.OTHER
    private var leaderRate = 1f
    private var skipQuiet = false

    private var mp: MediaPlayer? = null
    private var source: HttpRangeDataSource? = null
    private var surface: Surface? = null
    private var surfaceGen = 0
    private var surfaceRequested = false
    private var surfaceRetryAt = 0L
    private var seg: TlSegment? = null
    private var prepared = false
    private var seeking = false
    private var seekTargetMs = -1L
    private var seekAt = 0L
    private var openedAt = 0L
    private var pendingSeekMs = -1L
    private var pendingSeekMode = MediaPlayer.SEEK_CLOSEST
    private var framesAtSeek = 0
    private var awaitFrames = 0
    private var mpPlaying = false
    private var mpRate = 0f
    private var nudging = false
    private var nudgeUnsupported = false
    private var hardSeeks = 0
    private var lastHardSeekAt = 0L
    private var unsynced = false
    private var shown = false
    private var lastWall = Long.MIN_VALUE
    private var lastWallAt = 0L
    private var idleSince = 0L
    private var prepareFailures = 0
    private var everPlayed = false
    private var prefetched: String? = null
    private val skip = HashSet<String>()
    private val params = PlaybackParams()
    private var ticking = false
    private val tick = Runnable {
        ticking = false
        evaluate()
    }

    fun setSegments(list: List<TlSegment>) {
        val copy = list.filter { PlaybackPlan.oem(it) && PlaybackPlan.playable(it) }.sortedBy { it.start }
        exec { oem = copy }
    }

    /** The clip the leader shows and whether its sidecar carries a valid grid block. */
    fun setLeader(seg: TlSegment?, gridOk: Boolean) = exec {
        val changed = seg?.id != leaderSeg?.id
        leaderSeg = seg
        leaderGrid = gridOk
        if (changed) resetSync()
    }

    fun setLeaderState(state: LeaderState, rate: Float, skipQuietOn: Boolean) = exec {
        leaderState = state
        leaderRate = rate
        skipQuiet = skipQuietOn
    }

    /** The user chose the Dashcam view (opt-in; never set by itself). */
    fun setEnabled(on: Boolean) = exec {
        enabled = on
        if (on) resetSync()
    }

    /** Screen shown and resumed; hidden releases the decoder. */
    fun setVisible(on: Boolean) = exec { visible = on }

    /** An explicit seek or jump: a follower given up on gets another chance. */
    fun onLeaderSeek() = exec { resetSync() }

    /** Re-evaluates now (leader position or state moved). */
    fun poke() = exec {}

    /** GL dropped the follower Surface: the decoder stops rendering into it. */
    fun onSurfaceLost() = exec {
        teardownPlayer()
        surface = null
        surfaceRequested = false
        surfaceGen++
        setShown(false)
    }

    /** Frees the decoder and its Surface; bounded wait. Idempotent. */
    fun release() {
        if (released) return
        released = true
        cache.close()
        val done = CountDownLatch(1)
        val posted = h.postAtFrontOfQueue {
            try {
                h.removeCallbacksAndMessages(null)
                teardown()
            } finally {
                done.countDown()
            }
        }
        thread.quitSafely()
        if (posted) {
            try {
                done.await(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        main.removeCallbacksAndMessages(null)
    }

    private fun exec(block: () -> Unit) {
        if (released) return
        h.post {
            if (released) return@post
            try {
                block()
                evaluate()
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) Log.w(TAG, "follower step failed", t)
                failCurrent()
            }
        }
    }

    // ------------------------------------------------------------ follower thread

    private fun resetSync() {
        hardSeeks = 0
        unsynced = false
        skip.clear()
    }

    private fun evaluate() {
        if (released) return
        if (!enabled || !visible || unavailable) {
            teardown()
            return
        }
        val now = SystemClock.uptimeMillis()
        val w = leader.wallNowMs()
        noteLeaderJump(w, now)
        val dvr = if (leaderOk()) oemAt(w) else null
        if (dvr == null || unsynced) {
            setShown(false)
            pausePlayer()
            if (idleSince == 0L) idleSince = now
            if (now - idleSince > IDLE_RELEASE_MS) teardown()
            schedule(IDLE_TICK_MS)
            return
        }
        idleSince = 0L
        schedule(if (leaderState == LeaderState.PLAYING) TICK_MS else IDLE_TICK_MS)
        val s = surface
        if (s == null) {
            requestSurface(now)
            return
        }
        if (seg?.id != dvr.id || seg?.fp != dvr.fp) {
            openClip(dvr, s, w)
            return
        }
        if (!prepared) {
            if (now - openedAt > PREPARE_TIMEOUT_MS) failCurrent()
            return
        }
        val target = w - dvr.start
        prefetchNext(dvr, target)
        if (seeking) {
            if (now - seekAt > SEEK_TIMEOUT_MS) {
                failCurrent()
                return
            }
            // One seek in flight; the newest target follows when it lands.
            if (leaderState == LeaderState.SCRUBBING || leaderState == LeaderState.PAUSED) {
                pendingSeekMs = target
                pendingSeekMode = seekModeForState()
            }
            return
        }
        val frames = out.frames() - framesAtSeek
        val playing = leaderState == LeaderState.PLAYING && leader.clockRunning()
        if (playing && awaitFrames > 0 && frames < awaitFrames) {
            // No measurement until the seeked position has really been presented.
            if (!mpPlaying) startPlayer()
            return
        }
        if (playing) awaitFrames = 0
        sync(target, now, playing, frames > 0)
    }

    private fun leaderOk(): Boolean {
        val l = leaderSeg ?: return false
        if (PlaybackPlan.oem(l) || !leaderGrid || l.spanSrc != SPAN_WRITER) return false
        if (leaderRate > MAX_RATE || skipQuiet) return false
        return leaderState != LeaderState.OTHER
    }

    /** OEM clip covering [w] with a trusted anchor, newest start first. */
    private fun oemAt(w: Long): TlSegment? {
        val list = oem
        var lo = 0
        var hi = list.size - 1
        var i = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].start <= w) {
                i = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        while (i >= 0) {
            val c = list[i]
            if (c.start < w - MAX_CLIP_MS) break
            val end = c.end ?: c.start
            if (w < end && c.spanSrc == SPAN_WRITER && key(c) !in skip) return c
            i--
        }
        return null
    }

    // A wall jump the leader's rate doesn't explain is a seek: give a bailed-out follower another try.
    private fun noteLeaderJump(w: Long, now: Long) {
        val prev = lastWall
        val dt = now - lastWallAt
        lastWall = w
        lastWallAt = now
        if (prev == Long.MIN_VALUE) return
        val expected = if (leaderState == LeaderState.PLAYING) prev + (dt * leaderRate).toLong() else prev
        if (abs(w - expected) > JUMP_MS) {
            hardSeeks = 0
            unsynced = false
            lastHardSeekAt = 0L
        }
    }

    private fun sync(targetMs: Long, now: Long, playing: Boolean, framed: Boolean) {
        val p = mp ?: return
        val pos = positionMs(p)
        val d = (pos - targetMs) / 1000.0
        val frameNs = out.frameNs()
        val frame = if (frameNs > 0L) frameNs * leaderRate / 1e9 else DEFAULT_FRAME_S
        val tol = frame.coerceIn(TOL_MIN_S, TOL_MAX_S)
        when {
            playing -> syncPlaying(p, d, tol, targetMs, now)
            leaderState == LeaderState.SCRUBBING || leaderState == LeaderState.PAUSED -> {
                pausePlayer()
                // Only when the leader moved: frame quantisation alone must not re-seek forever.
                if (abs(targetMs - seekTargetMs) > 1L && abs(d) > tol / 2) seek(targetMs, seekModeForState())
                else if (framed || shown) setShown(true)
            }
            else -> {
                // Buffering, ended, or the leader's clock waits for its frame: hold the last frame.
                pausePlayer()
                if (framed || shown) setShown(true)
            }
        }
    }

    private fun syncPlaying(p: MediaPlayer, d: Double, tol: Double, targetMs: Long, now: Long) {
        val ad = abs(d)
        if (!mpPlaying) {
            if (ad > tol) {
                seek(targetMs, MediaPlayer.SEEK_CLOSEST)
                return
            }
            startPlayer()
        }
        val hardAt = if (nudgeUnsupported) SEEK_ONLY_S else HARD_SEEK_S
        if (ad > hardAt) {
            if (now - lastHardSeekAt < HARD_SEEK_GAP_MS) return
            if (hardSeeks >= MAX_HARD_SEEKS) {
                // Never converged: single source until the next user seek or clip boundary.
                unsynced = true
                setShown(false)
                pausePlayer()
                return
            }
            hardSeeks++
            lastHardSeekAt = now
            seek(targetMs, MediaPlayer.SEEK_CLOSEST)
            return
        }
        if (ad <= HARD_SEEK_S) hardSeeks = 0
        if (nudging) {
            if (ad < tol / 2) nudging = false
        } else if (ad > tol) {
            nudging = !nudgeUnsupported
        }
        val rate = if (nudging) leaderRate * (1.0 - (d / 2).coerceIn(-MAX_NUDGE, MAX_NUDGE)).toFloat() else leaderRate
        setRate(p, rate)
        setShown(true)
    }

    private fun seekModeForState(): Int =
        if (leaderState == LeaderState.SCRUBBING) MediaPlayer.SEEK_CLOSEST_SYNC else MediaPlayer.SEEK_CLOSEST

    private fun seek(targetMs: Long, mode: Int) {
        val p = mp ?: return
        val dur = durationMs(seg)
        val t = if (dur > 0L) targetMs.coerceIn(0L, dur - 1L) else targetMs.coerceAtLeast(0L)
        try {
            p.seekTo(t, mode)
            seeking = true
            seekAt = SystemClock.uptimeMillis()
            seekTargetMs = targetMs
            pendingSeekMs = -1L
        } catch (e: IllegalStateException) {
            failCurrent()
        }
    }

    private fun onSeekDone() {
        seeking = false
        framesAtSeek = out.frames()
        awaitFrames = if (leaderState == LeaderState.PLAYING) 2 else 1
        val next = pendingSeekMs
        if (next >= 0L && next != seekTargetMs) {
            seek(next, pendingSeekMode)
            return
        }
        pendingSeekMs = -1L
        if (leaderState == LeaderState.PLAYING && leader.clockRunning() && !mpPlaying) startPlayer()
        evaluate()
    }

    private fun positionMs(p: MediaPlayer): Long {
        // Paused (or just seeked): the reported position is the frame on screen.
        val ts = if (!mpPlaying) null else try {
            p.timestamp
        } catch (_: Throwable) {
            null
        }
        if (ts != null) {
            @Suppress("DEPRECATION")
            val anchorNs = ts.anchorSytemNanoTime
            val rate = ts.mediaClockRate
            val us = ts.anchorMediaTimeUs + ((System.nanoTime() - anchorNs) / 1000L * rate).toLong()
            if (us >= 0L) return us / 1000L
        }
        return try {
            p.currentPosition.toLong()
        } catch (_: Throwable) {
            0L
        }
    }

    private fun setRate(p: MediaPlayer, rate: Float) {
        if (abs(rate - mpRate) < RATE_EPS) return
        try {
            p.playbackParams = params.setSpeed(rate)
            mpRate = rate
        } catch (t: Throwable) {
            if (rate == leaderRate) {
                // The leader's own rate can't be followed: single source.
                unsynced = true
                setShown(false)
                pausePlayer()
            } else {
                // Seek-only correction from now on (bounded by the hard seek rules).
                nudgeUnsupported = true
                nudging = false
            }
        }
    }

    private fun startPlayer() {
        val p = mp ?: return
        if (mpPlaying || !prepared) return
        try {
            p.start()
            mpPlaying = true
            mpRate = 0f
            nudging = false
            setRate(p, leaderRate)
            everPlayed = true
        } catch (e: IllegalStateException) {
            failCurrent()
        }
    }

    private fun pausePlayer() {
        val p = mp ?: return
        if (!mpPlaying) return
        mpPlaying = false
        try {
            p.pause()
        } catch (_: IllegalStateException) {
        }
    }

    private fun setShown(on: Boolean) {
        if (shown == on) return
        shown = on
        out.setShown(on)
    }

    private fun schedule(ms: Long) {
        if (ticking || released) return
        ticking = true
        h.postDelayed(tick, ms)
    }

    private fun requestSurface(now: Long) {
        if (surfaceRequested || now < surfaceRetryAt) return
        surfaceRequested = true
        val gen = ++surfaceGen
        val ok = out.open { s ->
            if (released) return@open
            h.post {
                if (released) return@post
                // Superseded: the close that superseded it already reached the renderer after this open.
                if (gen != surfaceGen || !surfaceRequested) return@post
                surfaceRequested = false
                if (s == null) {
                    surfaceRetryAt = SystemClock.uptimeMillis() + SURFACE_RETRY_MS
                } else {
                    surface = s
                }
                evaluate()
            }
        }
        if (!ok) {
            surfaceRequested = false
            surfaceRetryAt = now + SURFACE_RETRY_MS
        }
    }

    private fun openClip(dvr: TlSegment, s: Surface, w: Long) {
        val prevEnd = seg?.end
        teardownPlayer()
        seg = dvr
        // Straight on into the next clip: the top band holds the last frame meanwhile; else single source.
        if (prevEnd == null || !PlaybackPlan.contiguous(prevEnd, dvr.start)) setShown(false)
        val src = HttpRangeDataSource(cache, dvr.videoUrl, dvr.fp)
        try {
            // Local daemon read of the first block (moov is at the front); bounded by the cache timeout.
            src.open()
        } catch (e: IOException) {
            src.close()
            skip.add(key(dvr))
            seg = null
            return
        }
        val p = try {
            MediaPlayer()
        } catch (t: Throwable) {
            src.close()
            onDecoderUnavailable()
            return
        }
        source = src
        mp = p
        openedAt = SystemClock.uptimeMillis()
        p.setOnPreparedListener { if (it === mp) onPrepared(it) }
        p.setOnSeekCompleteListener { if (it === mp) onSeekDone() }
        p.setOnVideoSizeChangedListener { m, vw, vh -> if (m === mp) out.setVideoSize(vw, vh) }
        p.setOnCompletionListener { if (it === mp) onEndedFirst() }
        p.setOnErrorListener { m, what, extra ->
            if (m === mp) onPlayerError(what, extra)
            true
        }
        try {
            p.setDataSource(src)
            p.setSurface(s)
            p.setVolume(0f, 0f)
            p.prepareAsync()
        } catch (t: Throwable) {
            onPlayerError(0, 0)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "open ${dvr.id} at ${w - dvr.start} ms")
    }

    private fun onPrepared(p: MediaPlayer) {
        prepared = true
        prepareFailures = 0
        try {
            p.setVolume(0f, 0f)
        } catch (_: Throwable) {
        }
        val dvr = seg ?: return
        seek(leader.wallNowMs() - dvr.start, MediaPlayer.SEEK_CLOSEST)
    }

    /** The windshield clip ran out before the index said: a gap until the next one. */
    private fun onEndedFirst() {
        seg?.let { skip.add(key(it)) }
        mpPlaying = false
        setShown(false)
        evaluate()
    }

    private fun onPlayerError(what: Int, extra: Int) {
        if (BuildConfig.DEBUG) Log.d(TAG, "player error $what/$extra")
        val wasPrepared = prepared
        failCurrent()
        // Never got a decoder up twice in a row: a second decoder isn't available on this unit.
        if (!wasPrepared && !everPlayed && ++prepareFailures >= MAX_PREPARE_FAILURES) onDecoderUnavailable()
    }

    private fun failCurrent() {
        seg?.let { skip.add(key(it)) }
        teardownPlayer()
        setShown(false)
    }

    private fun onDecoderUnavailable() {
        unavailable = true
        sessionUnavailable = true
        teardown()
        main.post { if (!released) listener.onUnavailable() }
    }

    private fun prefetchNext(dvr: TlSegment, targetMs: Long) {
        val dur = durationMs(dvr)
        if (dur <= 0L || targetMs < dur - PREFETCH_BEFORE_END_MS) return
        val end = dvr.end ?: return
        val next = oem.firstOrNull { it.start in (end - PlaybackPlan.SEAM_MS)..(end + PlaybackPlan.SEAM_MS) && it.id != dvr.id }
            ?: return
        val k = key(next)
        if (prefetched == k) return
        prefetched = k
        val url = next.videoUrl
        val fp = next.fp
        Thread({
            try {
                cache.open(url, fp, null)
            } catch (_: IOException) {
            } catch (_: RuntimeException) {
            }
        }, "TlDashcamPrefetch").apply { isDaemon = true }.start()
    }

    private fun teardownPlayer() {
        val p = mp
        mp = null
        prepared = false
        seeking = false
        seekTargetMs = -1L
        pendingSeekMs = -1L
        awaitFrames = 0
        mpPlaying = false
        mpRate = 0f
        nudging = false
        seg = null
        if (p != null) {
            try {
                p.setSurface(null)
            } catch (_: Throwable) {
            }
            try {
                p.release()
            } catch (_: Throwable) {
            }
        }
        source?.let {
            it.abort()
            it.close()
        }
        source = null
    }

    /** Leaves the mode: decoder first, then its Surface. */
    private fun teardown() {
        h.removeCallbacks(tick)
        ticking = false
        idleSince = 0L
        setShown(false)
        teardownPlayer()
        if (surface != null || surfaceRequested) {
            surface = null
            surfaceRequested = false
            surfaceGen++
            out.close()
        }
        lastWall = Long.MIN_VALUE
    }

    private fun durationMs(s: TlSegment?): Long {
        val c = s ?: return -1L
        val end = c.end ?: return -1L
        return end - c.start
    }

    private fun key(s: TlSegment): String = s.id + "|" + s.fp

    companion object {
        private const val TAG = "TlDashcam"
        private const val SPAN_WRITER = "writer"
        private const val CACHE_BYTES = 8L * 1024 * 1024
        private const val TICK_MS = 100L
        private const val IDLE_TICK_MS = 250L
        private const val IDLE_RELEASE_MS = 15_000L
        private const val RELEASE_WAIT_MS = 300L
        private const val SURFACE_RETRY_MS = 2_000L
        private const val MAX_CLIP_MS = 2L * 3_600_000L
        private const val JUMP_MS = 1_000L
        private const val MAX_RATE = 2f
        private const val DEFAULT_FRAME_S = 1.0 / 15.0
        private const val TOL_MIN_S = 0.034
        private const val TOL_MAX_S = 0.100
        private const val HARD_SEEK_S = 0.5
        private const val SEEK_ONLY_S = 0.2
        private const val HARD_SEEK_GAP_MS = 2_000L
        private const val MAX_HARD_SEEKS = 3
        private const val MAX_NUDGE = 0.10
        private const val RATE_EPS = 0.004f
        private const val MAX_PREPARE_FAILURES = 2
        private const val PREFETCH_BEFORE_END_MS = 2_000L
        private const val PREPARE_TIMEOUT_MS = 8_000L
        private const val SEEK_TIMEOUT_MS = 4_000L

        @Volatile private var sessionUnavailable = false
        @Volatile private var probed: Boolean? = null

        /**
         * Whether this unit can run the follower next to the leader's decoder: a hardware-capable AVC decoder
         * for 1080p reporting at least two concurrent instances. Blocking (codec list); call off the UI thread.
         */
        fun supported(): Boolean {
            if (sessionUnavailable) return false
            probed?.let { return it }
            val ok = try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                val name = list.findDecoderForFormat(fmt)
                val info = list.codecInfos.firstOrNull { it.name == name }
                val caps = info?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                caps != null && caps.maxSupportedInstances >= 2 &&
                    caps.videoCapabilities?.isSizeSupported(1920, 1080) == true
            } catch (_: Throwable) {
                false
            }
            probed = ok
            return ok
        }

        /** Cached result of [supported]; null until probed. */
        fun supportedNow(): Boolean? = if (sessionUnavailable) false else probed
    }
}
