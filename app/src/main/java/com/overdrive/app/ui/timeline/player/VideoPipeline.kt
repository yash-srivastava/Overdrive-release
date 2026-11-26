package com.overdrive.app.ui.timeline.player

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.overdrive.app.BuildConfig
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One video decoder (async MediaCodec, callbacks on its own HandlerThread) plus its feeder thread.
 * Items with an identical codec config run through it gaplessly; another config needs a new pipeline.
 * All MediaCodec calls happen on the codec thread.
 */
internal class VideoPipeline(
    val id: Int,
    val cache: BlockCache,
    val clock: MediaClock,
    private val host: PipelineHost,
    surface: Surface,
    plan: PlaybackPlan,
    private val previousRelease: CountDownLatch?,
) {
    @Volatile var surface: Surface = surface
        private set
    @Volatile var plan: PlaybackPlan = plan
    @Volatile var playing = false
        private set
    @Volatile var skimMode = false
    @Volatile var released = false
        private set
    @Volatile var reqGen = 0
        private set
    @Volatile var log: EpochLog? = null
    @Volatile var config: CodecConfig? = null
        private set
    @Volatile var feedingItem: PlanItem? = null

    private val gens = AtomicInteger(0)
    private val thread = HandlerThread("TlVideoCodec").apply { start() }
    private val handler = Handler(thread.looper)
    private val feeder = VideoFeeder(this, host)
    private val codecReleased = CountDownLatch(1)

    private val sampleBuffers = SampleBufferPool()
    private val qLock = Object()
    private val queue = ArrayDeque<Sample>()
    private var queueBytes = 0L
    private val feedPosted = AtomicBoolean(false)

    private var codec: MediaCodec? = null
    private var codecGen = 0
    // Callback thread only: the decoder between flush() and its posted start().
    private var flushing: MediaCodec? = null
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<OutFrame>()
    private val queuedEos = ArrayDeque<EosInfo>()
    private var candidate: OutFrame? = null
    private var shownEpoch: Epoch? = null
    private var lastSpecItem: PlanItem? = null
    private var lastRenderNs = 0L
    private var lastShowRealNs = 0L
    private var maxAvailUs = Long.MIN_VALUE
    private var frameDeltaUs = DEFAULT_FRAME_US
    private var videoW = 0
    private var videoH = 0
    private val meter = LateFrameMeter()
    private var tooSlowSent = false
    // Codec thread: start of the window the achieved playback rate is measured over (0 = none yet).
    private var rateFromNs = 0L
    private var rateFromUs = 0L
    private var rateSpeed = 0f
    private var slowWindows = 0
    private var statFromNs = 0L
    private var statShown = 0
    private var statLate = 0

    @Volatile private var gapHeld = false
    // Callback thread: the epoch held at its gap, and the one the user released past it.
    private var heldEpoch: Epoch? = null
    private var passedEpoch: Epoch? = null
    @Volatile private var inputsSinceOutput = 0
    @Volatile private var lastProgressNs = System.nanoTime()
    @Volatile private var lastInputNs = System.nanoTime()
    @Volatile private var pendingSize = 0

    private val drainTask = Runnable { drain() }
    private val candidateTask = Runnable { showCandidateIfStill() }
    private val feedTask = Runnable {
        feedPosted.set(false)
        feedInputs()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (c !== codec || released || c === flushing) return
            lastInputNs = System.nanoTime()
            freeInputs.addLast(index)
            feedInputs()
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec || released || c === flushing) return
            onOutput(c, index, info)
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (c !== codec || released) return
            if (!surface.isValid) emit(PipeEvent.SurfaceLost(codecGen))
            else emit(PipeEvent.CodecError(codecGen, shownEpoch?.item ?: feedingItem, e))
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            if (c !== codec || released) return
            onFormat(format)
        }
    }

    fun start() {
        feeder.start()
    }

    /** Retires the current request now; its late events and clock writes are ignored. */
    fun fence() {
        if (released) return
        synchronized(clock) { reqGen = gens.incrementAndGet() }
    }

    /** Retires the current request and idles the decoder (nothing new is fed). */
    fun invalidate() {
        if (released) return
        val gen = synchronized(clock) { gens.incrementAndGet().also { reqGen = it } }
        feeder.abortIfStalled()
        handler.post { flushTo(gen) }
    }

    /** Starts a new generation: flushes the decoder and repositions the feeder. */
    fun request(req: FeedRequest) {
        if (released) return
        val gen = gens.incrementAndGet()
        req.gen = gen
        reqGen = gen
        feeder.abortIfStalled()
        handler.post { flushTo(gen) }
        feeder.submit(req)
    }

    fun setPlaying(p: Boolean) {
        playing = p
        handler.post {
            // A pause is not slowness: the rate window restarts with playback.
            rateFromNs = 0L
            if (p) drain() else handler.removeCallbacks(drainTask)
        }
    }

    /** Re-evaluates frame timing after a clock change. */
    fun kick() {
        handler.post { drain() }
    }

    fun resumeFromGap() {
        handler.post {
            if (gapHeld) {
                gapHeld = false
                // Play past this gap: the same epoch must not hold again.
                passedEpoch = heldEpoch
                rateFromNs = 0L
                drain()
            }
        }
    }

    fun swapSurface(s: Surface) {
        surface = s
        handler.post {
            val c = codec ?: return@post
            try {
                c.setOutputSurface(s)
            } catch (_: Exception) {
                emit(PipeEvent.SurfaceLost(codecGen))
            }
        }
    }

    /** No output for a while although input was queued (or is waiting) and nothing is held. */
    fun stuckForMs(): Long {
        if (released || gapHeld || pendingSize > 0) return 0L
        val now = System.nanoTime()
        val sinceOutput = (now - lastProgressNs) / 1_000_000L
        if (inputsSinceOutput >= STUCK_INPUTS) return sinceOutput
        val waiting = synchronized(qLock) { queue.isNotEmpty() }
        if (config != null && waiting) return minOf(sinceOutput, (now - lastInputNs) / 1_000_000L)
        return 0L
    }

    /** Frees the decoder on its own thread; the latch opens once the output surface is free again. */
    fun release(waitMs: Long): CountDownLatch {
        if (released) return codecReleased
        // Under the clock lock with a fresh generation: a drain in flight can't write the clock afterwards.
        synchronized(clock) {
            released = true
            reqGen = gens.incrementAndGet()
        }
        feeder.stop()
        // A codec callback may still hold a dequeued array. Closing prevents late returns
        // from repopulating the idle pool; queued arrays can simply be dropped.
        sampleBuffers.close()
        synchronized(qLock) {
            queue.clear()
            queueBytes = 0
            qLock.notifyAll()
        }
        handler.removeCallbacksAndMessages(null)
        val posted = handler.post {
            val c = codec
            codec = null
            if (c != null) {
                try {
                    c.stop()
                } catch (_: Throwable) {
                }
                try {
                    c.release()
                } catch (_: Throwable) {
                }
            }
            codecReleased.countDown()
        }
        if (!posted) codecReleased.countDown()
        thread.quitSafely()
        if (waitMs > 0) {
            try {
                codecReleased.await(waitMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        return codecReleased
    }

    /** Feeder thread: creates and starts the decoder. Returns the failure, or null. */
    internal fun configure(format: MediaFormat, cfg: CodecConfig): Throwable? {
        if (released) return IllegalStateException("released")
        if (previousRelease != null) {
            try {
                previousRelease.await(PREV_RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        val done = CountDownLatch(1)
        var err: Throwable? = null
        val posted = handler.post {
            try {
                createCodec(format, cfg)
            } catch (t: Throwable) {
                err = t
            }
            done.countDown()
        }
        if (!posted) return IllegalStateException("released")
        try {
            if (!done.await(CONFIGURE_WAIT_MS, TimeUnit.MILLISECONDS)) return IllegalStateException("decoder start timeout")
        } catch (e: InterruptedException) {
            return e
        }
        return err
    }

    internal fun copySampleData(source: ByteBuffer, size: Int): ByteArray =
        sampleBuffers.copyFrom(source, size)

    /** Feeder thread: takes ownership even on rejection; blocks while the queue is full. */
    internal fun enqueue(s: Sample): Boolean {
        var accepted = false
        try {
            synchronized(qLock) {
                while (!released && s.gen == reqGen && queue.isNotEmpty() &&
                    (queue.size >= MAX_QUEUE || queueBytes + s.storageBytes > MAX_QUEUE_BYTES)
                ) {
                    qLock.wait(50L)
                }
                if (released || s.gen != reqGen) return false
                queue.addLast(s)
                queueBytes += s.storageBytes
                accepted = true
            }
            if (feedPosted.compareAndSet(false, true)) handler.post(feedTask)
            return true
        } finally {
            if (!accepted) sampleBuffers.recycle(s.data)
        }
    }

    internal fun emit(e: PipeEvent) {
        if (!released) host.onPipelineEvent(this, e)
    }

    private fun createCodec(format: MediaFormat, cfg: CodecConfig) {
        if (released) throw IllegalStateException("released")
        // A retry after a timed-out configure: the first decoder came up meanwhile. Fine only for the same format.
        if (codec != null) {
            if (config?.sameAs(cfg) == false) throw IllegalStateException("decoder already configured for another format")
            return
        }
        val declared = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(declared, cfg.width * cfg.height * 3 / 4, 256 * 1024))
        val name = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        } catch (_: Throwable) {
            null
        }
        val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(cfg.mime)
        try {
            c.setCallback(callback, handler)
            c.configure(format, surface, null, 0)
            c.start()
        } catch (t: Throwable) {
            try {
                c.release()
            } catch (_: Throwable) {
            }
            throw t
        }
        codec = c
        config = cfg
        lastProgressNs = System.nanoTime()
        lastInputNs = lastProgressNs
    }

    private fun flushTo(gen: Int) {
        if (released) return
        codecGen = gen
        handler.removeCallbacks(drainTask)
        handler.removeCallbacks(candidateTask)
        pending.clear()
        pendingSize = 0
        freeInputs.clear()
        queuedEos.clear()
        candidate = null
        shownEpoch = null
        gapHeld = false
        heldEpoch = null
        passedEpoch = null
        maxAvailUs = Long.MIN_VALUE
        frameDeltaUs = DEFAULT_FRAME_US
        meter.reset()
        tooSlowSent = false
        rateFromNs = 0L
        slowWindows = 0
        synchronized(qLock) {
            while (queue.isNotEmpty() && queue.first().gen < gen) discardFirstLocked()
            qLock.notifyAll()
        }
        inputsSinceOutput = 0
        lastProgressNs = System.nanoTime()
        lastInputNs = lastProgressNs
        val c = codec ?: return
        // Still flushed: the start posted by the earlier flush resumes it for this generation.
        if (c === flushing) return
        try {
            c.flush()
        } catch (e: Exception) {
            emit(PipeEvent.CodecError(gen, feedingItem, e))
            return
        }
        // Callbacks queued before flush() carry dead buffer indices: they run first and are dropped.
        flushing = c
        handler.post {
            if (flushing === c) flushing = null
            if (released || codec !== c) return@post
            try {
                c.start()
            } catch (e: Exception) {
                emit(PipeEvent.CodecError(codecGen, feedingItem, e))
            }
        }
    }

    /** Clock writes only for the request that owns the clock; a newer one has fenced it. */
    private inline fun ownClock(block: () -> Unit) {
        synchronized(clock) { if (codecGen == reqGen) block() }
    }

    private fun peekSample(): Sample? = synchronized(qLock) {
        while (queue.isNotEmpty() && queue.first().gen < codecGen) discardFirstLocked()
        qLock.notifyAll()
        val h = queue.firstOrNull()
        if (h == null || h.gen > codecGen) null else h
    }

    // Only the codec thread discards stale generations, under qLock.
    private fun discardFirstLocked() {
        val s = queue.removeFirst()
        queueBytes -= s.storageBytes
        sampleBuffers.recycle(s.data)
    }

    /** Transfer queue ownership to the codec thread, unless release already cleared it. */
    private fun takeSample(s: Sample): Boolean = synchronized(qLock) {
        if (queue.firstOrNull() !== s) return@synchronized false
        queue.removeFirst()
        queueBytes -= s.storageBytes
        qLock.notifyAll()
        true
    }

    private fun feedInputs() {
        val c = codec ?: return
        if (released) return
        while (freeInputs.isNotEmpty()) {
            val s = peekSample() ?: return
            val idx = freeInputs.removeFirst()
            if (s.eos != null) {
                if (queueInput(c, idx, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)) {
                    takeSample(s)
                    queuedEos.addLast(s.eos)
                }
                continue
            }
            val buf = try {
                c.getInputBuffer(idx)
            } catch (e: MediaCodec.CodecException) {
                emit(PipeEvent.CodecError(codecGen, feedingItem, e))
                return
            } catch (_: IllegalStateException) {
                null
            } ?: continue
            if (!takeSample(s)) return
            val data = s.data
            try {
                if (data == null || s.size > buf.capacity()) {
                    freeInputs.addFirst(idx)
                    continue
                }
                buf.clear()
                buf.put(data, 0, s.size)
            } finally {
                // MediaCodec owns its own input buffer. Once put() returns, no decoder
                // operation reads the feeder array, even if queueInputBuffer fails.
                sampleBuffers.recycle(data)
            }
            if (queueInput(c, idx, s.size, s.tag, s.flags)) inputsSinceOutput++
        }
    }

    private fun queueInput(c: MediaCodec, idx: Int, size: Int, tag: Long, flags: Int): Boolean = try {
        c.queueInputBuffer(idx, 0, size, tag, flags)
        true
    } catch (e: MediaCodec.CodecException) {
        emit(PipeEvent.CodecError(codecGen, feedingItem, e))
        false
    } catch (_: IllegalStateException) {
        false
    }

    private fun onOutput(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        lastProgressNs = System.nanoTime()
        inputsSinceOutput = 0
        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        if (eos) {
            val ei = queuedEos.removeFirstOrNull()
            if (ei == null) {
                drop(c, index)
                return
            }
            if (ei.reason != EndReason.SCRUB) clock.setLimit(ei.mediaEndUs)
            if (info.size > 0) {
                pending.addLast(OutFrame(index, info.presentationTimeUs, null))
                pending.addLast(OutFrame(-1, 0L, ei))
            } else {
                pending.addLast(OutFrame(index, 0L, ei))
            }
        } else {
            pending.addLast(OutFrame(index, info.presentationTimeUs, null))
            if (codecGen == reqGen) noteAvailable(info.presentationTimeUs)
        }
        pendingSize = pending.size
        drain()
    }

    private fun noteAvailable(tag: Long) {
        val ep = log?.takeIf { it.gen == codecGen }?.forTag(tag) ?: return
        if (ep.scrub) return
        val clipUs = ep.clipUsOfTag(tag)
        if (clipUs < ep.offsetClipUs - SLICE_TOLERANCE_US || clipUs >= ep.endClipUs) return
        val m = ep.mediaUsOfTag(tag)
        if (maxAvailUs != Long.MIN_VALUE && m > maxAvailUs && m - maxAvailUs < 3_000_000L) frameDeltaUs = m - maxAvailUs
        if (m > maxAvailUs) maxAvailUs = m
        clock.setLimit(maxAvailUs + maxOf(frameDeltaUs, 34_000L) + LIMIT_GRACE_US)
    }

    private fun drain() {
        if (released) return
        handler.removeCallbacks(drainTask)
        val c = codec ?: return
        if (codecGen != reqGen) return
        val lg = log?.takeIf { it.gen == codecGen }
        while (true) {
            val f = pending.firstOrNull() ?: break
            val eos = f.eos
            if (eos != null) {
                pending.removeFirst()
                if (f.index >= 0) drop(c, f.index)
                flushCandidate(c)
                if (eos.reason == EndReason.SCRUB) emit(PipeEvent.ScrubShown(codecGen, eos.last))
                else emit(PipeEvent.RunEnded(codecGen, eos))
                continue
            }
            val ep = lg?.forTag(f.tag)
            if (ep == null) {
                pending.removeFirst()
                drop(c, f.index)
                continue
            }
            if (ep.scrub) {
                pending.removeFirst()
                show(c, f, ep, System.nanoTime())
                continue
            }
            val clipUs = ep.clipUsOfTag(f.tag)
            if (clipUs < ep.offsetClipUs - SLICE_TOLERANCE_US || clipUs >= ep.endClipUs) {
                pending.removeFirst()
                drop(c, f.index)
                continue
            }
            val held = candidate
            if (held != null && lg?.forTag(held.tag) !== ep) flushCandidate(c)
            if (ep.targetClipUs != Epoch.NO_TARGET) {
                if (clipUs <= ep.targetClipUs) {
                    pending.removeFirst()
                    candidate?.let { drop(c, it.index) }
                    candidate = f
                    handler.removeCallbacks(candidateTask)
                    handler.postDelayed(candidateTask, CANDIDATE_WAIT_MS)
                    continue
                }
                val cand = candidate
                if (cand != null) {
                    candidate = null
                    showTarget(c, cand, ep)
                } else {
                    pending.removeFirst()
                    showTarget(c, f, ep)
                    continue
                }
            }
            if (ep !== shownEpoch && ep !== passedEpoch && ep.join == Join.GAP && !host.continueAcrossGaps()) {
                if (!gapHeld) {
                    gapHeld = true
                    heldEpoch = ep
                    clock.pause()
                    emit(PipeEvent.GapHold(codecGen, ep))
                }
                break
            }
            if (gapHeld || !playing) break
            // Re-checked under the clock lock: a pause between the check above and here wins.
            if (!clock.running) ownClock { if (playing && !gapHeld) clock.start() }
            val mediaUs = ep.mediaUsOfTag(f.tag)
            val first = ep !== shownEpoch
            val earlyUs = ((mediaUs - clock.positionUs()) / clock.speed).toLong()
            if (earlyUs > EARLY_US) {
                handler.postDelayed(drainTask, maxOf(1L, (earlyUs - EARLY_US) / 1000L))
                break
            }
            val nowNs = System.nanoTime()
            pending.removeFirst()
            if (!first && earlyUs < -LATE_US && nowNs - lastShowRealNs < MAX_FROZEN_NS) {
                drop(c, f.index)
                meter.add(nowNs, true)
                stat(nowNs, true)
                checkTooSlow(nowNs)
                continue
            }
            meter.add(nowNs, false)
            stat(nowNs, false)
            checkTooSlow(nowNs)
            checkRate(nowNs, mediaUs)
            show(c, f, ep, nowNs + maxOf(0L, earlyUs) * 1000L)
        }
        pendingSize = pending.size
    }

    private fun showTarget(c: MediaCodec, f: OutFrame, ep: Epoch) {
        val precise = ep.targetPrecise
        ep.targetClipUs = Epoch.NO_TARGET
        handler.removeCallbacks(candidateTask)
        show(c, f, ep, System.nanoTime())
        ownClock {
            if (!precise) clock.seekTo(ep.mediaUsOfTag(f.tag))
            if (playing) clock.start()
        }
        emit(PipeEvent.TargetShown(codecGen, ep))
    }

    private fun flushCandidate(c: MediaCodec) {
        val f = candidate ?: return
        candidate = null
        val ep = log?.takeIf { it.gen == codecGen }?.forTag(f.tag)
        if (ep == null) drop(c, f.index) else showTarget(c, f, ep)
    }

    private fun showCandidateIfStill() {
        val c = codec ?: return
        if (released || candidate == null) return
        flushCandidate(c)
        drain()
    }

    private fun show(c: MediaCodec, f: OutFrame, ep: Epoch, desiredNs: Long) {
        val t = maxOf(desiredNs, lastRenderNs + 1_000L)
        val first = ep !== shownEpoch
        if (first && !(ep.scrub && ep.item.sameSlice(lastSpecItem))) {
            val sink = host.frameSink()
            sink?.scheduleClipSpec(t, ep.spec)
            if (ep.crossfade) sink?.scheduleCrossfade(t, CROSSFADE_MS)
            lastSpecItem = ep.item
            if (BuildConfig.DEBUG) Log.d(TAG, "item ${ep.seg.id} spec@$t release@$t")
        }
        try {
            c.releaseOutputBuffer(f.index, t)
        } catch (e: MediaCodec.CodecException) {
            emit(PipeEvent.CodecError(codecGen, ep.item, e))
            return
        } catch (_: IllegalStateException) {
            return
        }
        lastRenderNs = t
        lastShowRealNs = System.nanoTime()
        if (first) {
            shownEpoch = ep
            emit(PipeEvent.FirstFrame(codecGen, ep))
        }
        if (!ep.scrub && host.skipQuiet()) {
            val s = if (ep.fastAt(f.tag)) SKIM_SPEED else 1f
            if (clock.speed != s) {
                clock.setSpeed(s)
                emit(PipeEvent.RenderSpeed(codecGen, s))
            }
        }
    }

    private fun drop(c: MediaCodec, index: Int) {
        try {
            c.releaseOutputBuffer(index, false)
        } catch (_: IllegalStateException) {
        }
    }

    /** Debug builds: frames shown and dropped late, every few seconds of playback. */
    private fun stat(nowNs: Long, late: Boolean) {
        if (!BuildConfig.DEBUG) return
        if (late) statLate++ else statShown++
        if (statFromNs == 0L) statFromNs = nowNs
        val dt = nowNs - statFromNs
        if (dt < STAT_SPAN_NS) return
        Log.d(TAG, "rendered $statShown late $statLate in ${dt / 1_000_000L} ms at ${clock.speed}x skim=$skimMode")
        statFromNs = nowNs
        statShown = 0
        statLate = 0
    }

    /** Frames rendering at 4x or more (skip quiet: its fast stretches) that the decoder can't keep up with. */
    private fun fastNow(): Boolean = if (host.skipQuiet()) clock.speed > 2f else host.userSpeed() >= 4

    private fun checkTooSlow(nowNs: Long) {
        if (tooSlowSent || skimMode || !fastNow()) return
        val r = meter.ratioIfSpanned(nowNs, TOO_SLOW_SPAN_NS)
        if (r >= TOO_SLOW_RATIO) sendTooSlow("late ${(r * 100).toInt()}%")
    }

    /**
     * The clock waits at the newest decoded frame, so a decoder that is merely slow shows every frame late-free
     * but below the speed asked: media advanced per wall time over a few seconds catches that.
     */
    private fun checkRate(nowNs: Long, mediaUs: Long) {
        val sp = clock.speed
        if (tooSlowSent || skimMode || !fastNow()) {
            rateFromNs = 0L
            return
        }
        if (rateFromNs == 0L || sp != rateSpeed) {
            if (sp != rateSpeed) slowWindows = 0
            rateFromNs = nowNs
            rateFromUs = mediaUs
            rateSpeed = sp
            return
        }
        val dtNs = nowNs - rateFromNs
        if (dtNs < RATE_SPAN_NS) return
        val achieved = (mediaUs - rateFromUs) * 1000.0 / dtNs
        rateFromNs = nowNs
        rateFromUs = mediaUs
        // Two windows in a row: one network hiccup is not a decoder that can't keep up.
        slowWindows = if (achieved < sp * RATE_MIN_SHARE) slowWindows + 1 else 0
        if (slowWindows >= 2) sendTooSlow("rate %.1fx of %.0fx".format(achieved, sp))
    }

    private fun sendTooSlow(why: String) {
        tooSlowSent = true
        if (BuildConfig.DEBUG) Log.d(TAG, "too slow: $why")
        emit(PipeEvent.TooSlow(codecGen))
    }

    private fun onFormat(format: MediaFormat) {
        var w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
        var h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
        if (format.containsKey(KEY_CROP_LEFT) && format.containsKey(KEY_CROP_RIGHT)) {
            w = format.getInteger(KEY_CROP_RIGHT) - format.getInteger(KEY_CROP_LEFT) + 1
        }
        if (format.containsKey(KEY_CROP_TOP) && format.containsKey(KEY_CROP_BOTTOM)) {
            h = format.getInteger(KEY_CROP_BOTTOM) - format.getInteger(KEY_CROP_TOP) + 1
        }
        if (w > 0 && h > 0 && (w != videoW || h != videoH)) {
            videoW = w
            videoH = h
            emit(PipeEvent.VideoSize(codecGen, w, h))
        }
    }

    companion object {
        private const val TAG = "TlVideo"
        const val CROSSFADE_MS = 180L
        const val SKIM_SPEED = 8f
        private const val EARLY_US = 12_000L
        private const val LATE_US = 40_000L
        private const val MAX_FROZEN_NS = 120_000_000L
        private const val CANDIDATE_WAIT_MS = 250L
        private const val DEFAULT_FRAME_US = 66_000L
        private const val LIMIT_GRACE_US = 30_000L
        private const val SLICE_TOLERANCE_US = 1_000L
        private const val MAX_QUEUE = 12
        private const val MAX_QUEUE_BYTES = 8L * 1024 * 1024
        private const val STUCK_INPUTS = 8
        private const val CONFIGURE_WAIT_MS = 4_000L
        private const val PREV_RELEASE_WAIT_MS = 1_500L
        private const val TOO_SLOW_SPAN_NS = 1_800_000_000L
        private const val TOO_SLOW_RATIO = 0.10f
        private const val RATE_SPAN_NS = 2_500_000_000L
        /** Below this share of the speed asked, keyframes at that speed beat every frame at a crawl. */
        private const val RATE_MIN_SHARE = 0.6
        private const val STAT_SPAN_NS = 3_000_000_000L
        private const val KEY_CROP_LEFT = "crop-left"
        private const val KEY_CROP_RIGHT = "crop-right"
        private const val KEY_CROP_TOP = "crop-top"
        private const val KEY_CROP_BOTTOM = "crop-bottom"
    }
}
