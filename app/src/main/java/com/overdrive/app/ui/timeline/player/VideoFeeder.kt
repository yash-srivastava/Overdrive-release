package com.overdrive.app.ui.timeline.player

import android.media.MediaCodec
import android.util.Log
import com.overdrive.app.BuildConfig
import com.overdrive.app.ui.timeline.ClipSpec
import com.overdrive.app.ui.timeline.TlSegment
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Reads video samples for a [VideoPipeline] on its own thread; network reads block only here.
 * Walks plan items while they share the decoder config, rebasing them onto one media timeline.
 */
internal class VideoFeeder(private val pipe: VideoPipeline, private val host: PipelineHost) {
    private val lock = Object()
    private var request: FeedRequest? = null
    @Volatile private var stopped = false
    @Volatile private var active: ClipReader? = null
    @Volatile private var opening: HttpRangeDataSource? = null
    private val thread = Thread({ loop() }, "TlVideoFeed").apply { isDaemon = true }
    private val readers = LinkedHashMap<String, ClipReader>()
    private val specs = HashMap<String, ClipSpec>()
    private val specAhead = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "TlSpecAhead").apply { isDaemon = true }
    }
    private var buf: ByteBuffer = ByteBuffer.allocateDirect(INITIAL_BUFFER)
    private var nextTagBase = TAG_ORIGIN
    private var lastScrubKey: String? = null

    fun start() {
        thread.start()
    }

    fun submit(req: FeedRequest) {
        synchronized(lock) {
            request = req
            lock.notifyAll()
        }
    }

    fun stop() {
        stopped = true
        synchronized(lock) { lock.notifyAll() }
        active?.abort()
        opening?.abort()
        specAhead.shutdownNow()
    }

    /** Cuts a read that has been blocked on the network, so a new request does not queue behind it. */
    fun abortIfStalled() {
        val now = System.nanoTime()
        val o = opening
        if (o != null && o.busySinceNs != 0L && now - o.busySinceNs > STALL_ABORT_NS) o.abort()
        val r = active ?: return
        val since = r.busySinceNs
        if (since != 0L && now - since > STALL_ABORT_NS) r.abort()
    }

    private fun stale(req: FeedRequest): Boolean = stopped || pipe.released || req.gen != pipe.reqGen

    private fun nextRequest(handled: Int): FeedRequest? {
        synchronized(lock) {
            while (!stopped) {
                val r = request
                if (r != null && r.gen != handled) return r
                lock.wait(500L)
            }
            return null
        }
    }

    private fun loop() {
        var handled = 0
        try {
            while (!stopped) {
                val req = nextRequest(handled) ?: break
                handled = req.gen
                try {
                    if (req.kind == FeedRequest.Kind.SCRUB) scrubOnce(req) else feedRun(req)
                } catch (t: Throwable) {
                    active = null
                    if (!stale(req)) pipe.emit(PipeEvent.CodecError(req.gen, pipe.feedingItem, t))
                }
            }
        } finally {
            active = null
            for (r in readers.values) r.close()
            readers.clear()
        }
    }

    private fun feedRun(req: FeedRequest) {
        val log = EpochLog(req.gen)
        pipe.log = log
        lastScrubKey = null
        var item = req.item
        var join = req.join
        var gapFrom = req.gapFromWall
        var mediaStart = req.mediaStartUs
        var startWall = req.targetWall
        var first = true
        var targetPending = true
        var failures = 0
        var lastEpoch: Epoch? = null
        var shownEndWall = req.gapFromWall
        while (!stale(req)) {
            pipe.feedingItem = item
            val seg = item.seg
            val r: ClipReader
            try {
                r = reader(seg)
            } catch (e: IOException) {
                if (stale(req)) return
                pipe.emit(PipeEvent.ItemFailed(req.gen, item, e))
                failures++
                val nxt = nextAfter(item, item.playTo)
                if (nxt == null || failures >= MAX_FAILURES) {
                    endRun(req, if (nxt == null) EndReason.END else EndReason.FAILED, lastEpoch, nxt, shownEndWall, mediaStart)
                    return
                }
                if (lastEpoch != null) {
                    join = Join.GAP
                    gapFrom = shownEndWall
                }
                item = nxt
                startWall = nxt.playFrom
                continue
            }
            val cfg = pipe.config
            if (cfg == null) {
                val err = pipe.configure(r.format, r.config)
                if (err != null) {
                    if (!stale(req)) pipe.emit(PipeEvent.CodecError(req.gen, item, err))
                    return
                }
            } else if (!cfg.sameAs(r.config)) {
                if (lastEpoch == null) {
                    pipe.emit(
                        PipeEvent.NeedsRestart(
                            req.gen,
                            FeedRequest(req.kind, item, startWall, req.precise, join, mediaStart, gapFrom, req.crossfade),
                        )
                    )
                } else {
                    endRun(req, EndReason.RESTART, lastEpoch, item, shownEndWall, mediaStart)
                }
                return
            }

            active = r
            r.seekPreviousSync(maxOf(0L, (startWall - seg.start) * 1000L))
            var t = r.sampleTimeUs()
            val ep = Epoch(
                gen = req.gen,
                item = item,
                tagBase = nextTagBase,
                firstClipUs = if (t >= 0) t else 0L,
                mediaStartUs = mediaStart,
                join = join,
                gapFromWall = gapFrom,
                spec = spec(seg),
                crossfade = join == Join.GAP || (first && req.crossfade),
            )
            if (targetPending) {
                if (first) {
                    ep.targetPrecise = req.precise
                    ep.targetClipUs = if (req.precise) (req.targetWall - seg.start) * 1000L else ep.firstClipUs - 1L
                } else {
                    ep.targetClipUs = ep.offsetClipUs
                }
            }
            log.add(ep)
            nextAfter(item, item.playTo)?.let { prefetchSpec(it.seg) }

            var skim = false
            var lastT = -1L
            var maxT = -1L
            var fed = 0
            var keys = 0
            var reorder = false
            var frameUs = DEFAULT_FRAME_US
            while (t >= 0) {
                if (stale(req)) {
                    active = null
                    return
                }
                val sync = r.isSync()
                if (t >= ep.endClipUs && (sync || !reorder)) break
                if (sync) {
                    // Every frame by default (fast stretches too); keyframes only after the decoder fell behind.
                    val fast = quietAt(item, seg.start + t / 1000L)
                    skim = pipe.skimMode && (fast || !host.skipQuiet())
                    ep.markFast(ep.tagOfClipUs(t), fast)
                } else if (skim) {
                    r.seekNextSync(t + 1)
                    t = r.sampleTimeUs()
                    continue
                }
                val size = readInto(r)
                if (size < 0) break
                val data = pipe.copySampleData(buf, size)
                val tag = ep.tagOfClipUs(t)
                val flags = if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                if (!pipe.enqueue(Sample(req.gen, data, size, tag, flags, null))) {
                    active = null
                    return
                }
                if (tag > ep.maxTag) ep.maxTag = tag
                if (sync) keys++
                if (lastT >= 0 && t < lastT) reorder = true
                if (lastT >= 0 && t > lastT && t - lastT < 1_000_000L && !skim) frameUs = t - lastT
                lastT = t
                if (t > maxT) maxT = t
                fed++
                failures = 0
                if (skim) r.seekNextSync(t + 1) else r.advance()
                t = r.sampleTimeUs()
            }
            active = null
            if (BuildConfig.DEBUG) Log.d(TAG, "item ${seg.id} fed $fed frames ($keys key) skim=${pipe.skimMode}")

            val err = r.ioError
            var endWall = item.playTo
            var mediaEnd = ep.plannedMediaEndUs
            val short = maxT < 0 || maxT + frameUs < ep.endClipUs - EARLY_END_US
            if (err != null || short) {
                val endClip = if (maxT >= 0) maxOf(maxT + frameUs, ep.offsetClipUs) else ep.offsetClipUs
                if (endClip < ep.endClipUs) {
                    ep.cutMediaUs = ep.mediaUsOfClipUs(endClip)
                    mediaEnd = minOf(mediaEnd, ep.cutMediaUs)
                    endWall = maxOf(item.playFrom, seg.start + endClip / 1000L)
                }
                if (err != null) {
                    closeReader(seg)
                    if (stale(req)) return
                    pipe.emit(PipeEvent.ItemFailed(req.gen, item, err))
                    failures++
                }
            }
            nextTagBase = ep.maxTag + TAG_SPACING
            if (fed > 0 || lastEpoch == null) lastEpoch = ep
            if (fed > 0) {
                shownEndWall = endWall
                targetPending = false
            }
            first = false
            val nxt = nextAfter(item, endWall)
            if (nxt == null) {
                endRun(req, EndReason.END, lastEpoch, null, shownEndWall, mediaEnd)
                return
            }
            if (failures >= MAX_FAILURES) {
                endRun(req, EndReason.FAILED, lastEpoch, nxt, shownEndWall, mediaEnd)
                return
            }
            join = if (PlaybackPlan.contiguous(shownEndWall, nxt.playFrom)) Join.GAPLESS else Join.GAP
            gapFrom = shownEndWall
            mediaStart = mediaEnd
            item = nxt
            startWall = nxt.playFrom
        }
    }

    private fun scrubOnce(req: FeedRequest) {
        val log = EpochLog(req.gen)
        pipe.log = log
        val item = req.item
        val seg = item.seg
        pipe.feedingItem = item
        val r = try {
            reader(seg)
        } catch (e: IOException) {
            if (!stale(req)) pipe.emit(PipeEvent.ItemFailed(req.gen, item, e))
            endScrub(req, null)
            return
        }
        val cfg = pipe.config
        if (cfg == null) {
            val err = pipe.configure(r.format, r.config)
            if (err != null) {
                if (!stale(req)) pipe.emit(PipeEvent.CodecError(req.gen, item, err))
                return
            }
        } else if (!cfg.sameAs(r.config)) {
            pipe.emit(PipeEvent.NeedsRestart(req.gen, req))
            return
        }
        active = r
        r.seekPreviousSync(maxOf(0L, (req.targetWall - seg.start) * 1000L))
        val t = r.sampleTimeUs()
        val key = seg.id + "|" + seg.fp + "|" + t
        if (t < 0 || (key == lastScrubKey && !req.crossfade)) {
            active = null
            val err = r.ioError
            if (err != null) {
                closeReader(seg)
                if (!stale(req)) pipe.emit(PipeEvent.ItemFailed(req.gen, item, err))
            }
            endScrub(req, null)
            return
        }
        val size = readInto(r)
        active = null
        if (size < 0) {
            val err = r.ioError
            if (err != null) {
                closeReader(seg)
                if (!stale(req)) pipe.emit(PipeEvent.ItemFailed(req.gen, item, err))
            }
            endScrub(req, null)
            return
        }
        val ep = Epoch(req.gen, item, nextTagBase, t, 0L, Join.START, req.gapFromWall, spec(seg), req.crossfade, scrub = true)
        log.add(ep)
        val tag = ep.tagOfClipUs(t)
        nextTagBase = tag + TAG_SPACING
        val data = pipe.copySampleData(buf, size)
        if (!pipe.enqueue(Sample(req.gen, data, size, tag, MediaCodec.BUFFER_FLAG_KEY_FRAME, null))) return
        lastScrubKey = key
        endScrub(req, ep)
    }

    private fun endScrub(req: FeedRequest, ep: Epoch?) {
        pipe.enqueue(Sample(req.gen, null, 0, 0L, 0, EosInfo(EndReason.SCRUB, ep, null, req.targetWall, 0L)))
    }

    private fun endRun(req: FeedRequest, reason: EndReason, last: Epoch?, next: PlanItem?, endWall: Long, mediaEndUs: Long) {
        pipe.enqueue(Sample(req.gen, null, 0, 0L, 0, EosInfo(reason, last, next, endWall, mediaEndUs)))
    }

    /** Next plan item after [item]; after a plan swap, the item that continues from [endWall]. */
    private fun nextAfter(item: PlanItem, endWall: Long): PlanItem? {
        val plan = pipe.plan
        val i = plan.indexOf(item)
        if (i >= 0) return if (i + 1 < plan.items.size) plan.items[i + 1] else null
        val j = plan.indexFrom(endWall)
        if (j < 0) return null
        val x = plan.items[j]
        if (x.playFrom >= endWall) return x
        return if (x.playTo - endWall >= MIN_TAIL_MS) PlanItem(x.seg, endWall, x.playTo) else plan.next(x.playFrom)
    }

    /** Skip quiet: this GOP lies in a quiet stretch, which plays at the fast speed. */
    private fun quietAt(item: PlanItem, wall: Long): Boolean =
        host.skipQuiet() && QuietZones.quietType(item.seg.type) && host.quietZones().quietSpan(wall, wall + SKIM_LOOKAHEAD_MS)

    private fun readInto(r: ClipReader): Int {
        val need = r.sampleSize()
        if (need > MAX_SAMPLE_BYTES) return -1
        if (need > buf.capacity()) buf = ByteBuffer.allocateDirect((need + need / 4).toInt())
        return r.read(buf)
    }

    private fun spec(seg: TlSegment): ClipSpec? {
        val key = seg.id + "|" + seg.fp
        synchronized(specs) { specs[key]?.let { return it } }
        val s = try {
            host.specFor(seg)
        } catch (_: Throwable) {
            null
        }
        if (s != null) synchronized(specs) { specs[key] = s }
        return s
    }

    /** Resolves the next item's spec off the feeding path so a slow provider never stalls a seam. */
    private fun prefetchSpec(seg: TlSegment) {
        val key = seg.id + "|" + seg.fp
        synchronized(specs) { if (specs.containsKey(key)) return }
        try {
            specAhead.execute { if (!stopped) spec(seg) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun reader(seg: TlSegment): ClipReader {
        val key = seg.id + "|" + seg.fp
        val cached = readers.remove(key)
        if (cached != null) {
            if (!cached.closed && cached.ioError == null) {
                readers[key] = cached
                return cached
            }
            cached.close()
        }
        val r = try {
            ClipReader.open(seg, ClipReader.Kind.VIDEO, pipe.cache, null) { opening = it }
        } finally {
            opening = null
        } ?: throw IOException("clip has no video track")
        readers[key] = r
        while (readers.size > MAX_READERS) {
            val eldest = readers.keys.first()
            readers.remove(eldest)?.close()
        }
        return r
    }

    private fun closeReader(seg: TlSegment) {
        readers.remove(seg.id + "|" + seg.fp)?.close()
    }

    companion object {
        private const val TAG = "TlVideoFeed"
        private const val TAG_ORIGIN = 1_000_000L
        private const val TAG_SPACING = 200_000L
        private const val DEFAULT_FRAME_US = 66_000L
        private const val EARLY_END_US = 300_000L
        private const val MAX_FAILURES = 3
        private const val MAX_READERS = 3
        private const val INITIAL_BUFFER = 512 * 1024
        private const val MAX_SAMPLE_BYTES = 32L * 1024 * 1024
        private const val STALL_ABORT_NS = 300_000_000L
        private const val SKIM_LOOKAHEAD_MS = 2_500L
        private const val MIN_TAIL_MS = 200L
    }
}
