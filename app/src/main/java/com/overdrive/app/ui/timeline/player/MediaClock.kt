package com.overdrive.app.ui.timeline.player

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock

/** Media time of what is audible now, or [NONE] while audio is not driving playback. */
internal interface AudioTimeSource {
    fun audioMediaUs(): Long

    companion object {
        const val NONE = Long.MIN_VALUE
    }
}

/** anchor + elapsedRealtime * speed. Not thread-safe; [MediaClock] guards it. */
internal class StandaloneClock(private val nowNs: () -> Long) {
    var speed = 1f
        private set
    var running = false
        private set
    private var anchorUs = 0L
    private var anchorNs = 0L

    fun positionUs(): Long =
        if (!running) anchorUs else anchorUs + ((nowNs() - anchorNs) / 1000.0 * speed).toLong()

    fun set(us: Long) {
        anchorUs = us
        anchorNs = nowNs()
    }

    fun start() {
        if (running) return
        anchorNs = nowNs()
        running = true
    }

    fun pause() {
        if (!running) return
        anchorUs = positionUs()
        running = false
    }

    fun setSpeed(s: Float) {
        if (running) set(positionUs())
        speed = s
    }
}

/**
 * Audio position from AudioTrack.getTimestamp, mapped from written frames to media time.
 * Fed on the audio thread; [audioMediaUs] is safe from any thread.
 */
internal class AudioClock(private val nanoNow: () -> Long = { System.nanoTime() }) : AudioTimeSource {
    private class Snap(
        val tsFrames: Long,
        val tsNanos: Long,
        val speed: Float,
        val rate: Int,
        val mapFrames: LongArray,
        val mapMedia: LongArray,
        val writtenFrames: Long,
    )

    @Volatile private var snap: Snap? = null
    private val ts = AudioTimestamp()
    private var rate = 0
    private var speed = 1f
    private var written = 0L
    private var mapFrames = LongArray(MAP_CAP)
    private var mapMedia = LongArray(MAP_CAP)
    private var mapCount = 0
    private var tsFrames = -1L
    private var tsNanos = 0L
    private var lastPollNs = 0L
    private var goodPolls = 0

    /** New track or flushed track: frame 0 is the next written frame. */
    fun reset(sampleRate: Int, startFrames: Long) {
        rate = sampleRate
        written = startFrames
        mapCount = 0
        invalidate()
    }

    fun setSpeed(s: Float) {
        speed = s
        invalidate()
    }

    /** Timestamps restart after play/pause/flush/params changes. */
    fun invalidate() {
        tsFrames = -1L
        goodPolls = 0
        lastPollNs = 0L
        publish()
    }

    /** Records [frames] PCM frames written whose first frame plays media time [mediaUs]. */
    fun onWrite(frames: Int, mediaUs: Long) {
        if (frames <= 0 || rate <= 0) return
        val expected = if (mapCount == 0) Long.MIN_VALUE
        else mapMedia[mapCount - 1] + (written - mapFrames[mapCount - 1]) * 1_000_000L / rate
        if (mapCount == 0 || kotlin.math.abs(expected - mediaUs) > 2_000L) {
            if (mapCount == MAP_CAP) {
                System.arraycopy(mapFrames, 1, mapFrames, 0, MAP_CAP - 1)
                System.arraycopy(mapMedia, 1, mapMedia, 0, MAP_CAP - 1)
                mapCount--
            }
            mapFrames[mapCount] = written
            mapMedia[mapCount] = mediaUs
            mapCount++
        }
        written += frames
        publish()
    }

    val writtenFrames: Long get() = written

    /** Polls the track timestamp at a bounded rate; call from the audio thread while playing. */
    fun poll(track: AudioTrack) {
        val now = nanoNow()
        val interval = if (goodPolls >= 3) STABLE_POLL_NS else WARMUP_POLL_NS
        if (lastPollNs != 0L && now - lastPollNs < interval) return
        lastPollNs = now
        val ok = try {
            track.getTimestamp(ts)
        } catch (_: Throwable) {
            false
        }
        if (!ok || rate <= 0) return
        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val frame = ts.framePosition
        if (frame < 0 || kotlin.math.abs(frame - head) > rate || ts.nanoTime > now + 50_000_000L) return
        if (tsFrames >= 0 && frame <= tsFrames && goodPolls < 3) {
            tsFrames = frame
            tsNanos = ts.nanoTime
            publish()
            return
        }
        tsFrames = frame
        tsNanos = ts.nanoTime
        goodPolls++
        publish()
    }

    override fun audioMediaUs(): Long {
        val s = snap ?: return AudioTimeSource.NONE
        if (s.tsFrames < 0 || s.rate <= 0 || s.mapFrames.isEmpty()) return AudioTimeSource.NONE
        val elapsed = nanoNow() - s.tsNanos
        val frames = s.tsFrames + (elapsed / 1e9 * s.rate * s.speed).toLong()
        if (frames >= s.writtenFrames) return AudioTimeSource.NONE
        var i = s.mapFrames.size - 1
        while (i > 0 && s.mapFrames[i] > frames) i--
        if (s.mapFrames[i] > frames) return AudioTimeSource.NONE
        return s.mapMedia[i] + (frames - s.mapFrames[i]) * 1_000_000L / s.rate
    }

    private fun publish() {
        snap = if (tsFrames < 0 || goodPolls < 2 || mapCount == 0) {
            null
        } else {
            Snap(tsFrames, tsNanos, speed, rate, mapFrames.copyOf(mapCount), mapMedia.copyOf(mapCount), written)
        }
    }

    companion object {
        private const val MAP_CAP = 64
        private const val WARMUP_POLL_NS = 50_000_000L
        private const val STABLE_POLL_NS = 500_000_000L
    }
}

/**
 * Playback clock on the continuous media timeline. Runs standalone; while [audio] reports a
 * position it is slewed toward it (output latency included) so hand-over never jumps backwards.
 * Thread-safe.
 */
internal class MediaClock(private val realtimeNs: () -> Long = { SystemClock.elapsedRealtimeNanos() }) {
    private val base = StandaloneClock(realtimeNs)
    @Volatile var audio: AudioTimeSource? = null
    @Volatile var speed = 1f
        private set
    @Volatile var running = false
        private set
    private var limitUs = Long.MAX_VALUE
    private var lastUs = 0L
    private var lastCorrNs = 0L
    private var stalledSinceNs = 0L

    @Synchronized fun positionUs(): Long {
        if (!base.running) {
            lastUs = base.positionUs()
            return lastUs
        }
        val now = realtimeNs()
        val raw = base.positionUs()
        var p = raw
        val a = audio?.audioMediaUs() ?: AudioTimeSource.NONE
        if (a != AudioTimeSource.NONE) {
            val err = a - p
            if (err > HARD_SYNC_US) {
                p = a
            } else if (err >= -MAX_BEHIND_US && lastCorrNs != 0L) {
                val dtNs = (now - lastCorrNs).toDouble()
                var corr = err * minOf(1.0, dtNs / SLEW_NS)
                val maxBack = MAX_SLOWDOWN * base.speed * dtNs / 1000.0
                if (corr < -maxBack) corr = -maxBack
                p += corr.toLong()
            }
            lastCorrNs = now
        } else {
            lastCorrNs = 0L
        }
        if (p > limitUs) {
            p = limitUs
            if (stalledSinceNs == 0L) stalledSinceNs = now
        } else {
            stalledSinceNs = 0L
        }
        if (p < lastUs) p = lastUs
        if (p != raw) base.set(p)
        lastUs = p
        return p
    }

    /** How long the clock has been held at the video limit, 0 when flowing. */
    @Synchronized fun stalledForNs(): Long =
        if (!base.running || stalledSinceNs == 0L) 0L else realtimeNs() - stalledSinceNs

    @Synchronized fun seekTo(us: Long) {
        base.set(us)
        lastUs = us
        limitUs = Long.MAX_VALUE
        lastCorrNs = 0L
        stalledSinceNs = 0L
    }

    /** Moves forward only; used when a frame is shown later than the clock. */
    @Synchronized fun advanceTo(us: Long) {
        if (us <= lastUs) return
        base.set(us)
        lastUs = us
    }

    @Synchronized fun start() {
        if (base.running) return
        base.start()
        lastCorrNs = 0L
        stalledSinceNs = 0L
        running = true
    }

    @Synchronized fun pause() {
        if (!base.running) return
        val p = positionUs()
        base.set(p)
        base.pause()
        running = false
        stalledSinceNs = 0L
    }

    @Synchronized fun setSpeed(s: Float) {
        if (s == speed) return
        if (base.running) base.set(positionUs())
        base.setSpeed(s)
        speed = s
        lastCorrNs = 0L
    }

    /** Upper bound from decoded video: the clock never runs past the newest available frame. */
    @Synchronized fun setLimit(us: Long) {
        limitUs = us
    }

    companion object {
        const val HARD_SYNC_US = 250_000L
        /** Audio further behind than this is treated as bogus. */
        private const val MAX_BEHIND_US = 800_000L
        /** Slewing toward late audio never slows playback below 0.25x. */
        private const val MAX_SLOWDOWN = 0.75
        private const val SLEW_NS = 500_000_000.0
    }
}
