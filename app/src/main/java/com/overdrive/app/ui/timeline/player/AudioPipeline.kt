package com.overdrive.app.ui.timeline.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.PlaybackParams
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.overdrive.app.BuildConfig
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Audio decoder + AudioTrack on their own thread. Follows the video epoch log so audio sits on the
 * same media timeline; PCM is written only near the clock, so silent items and gaps stay aligned.
 * Attributes USAGE_MEDIA / CONTENT_TYPE_MOVIE, no audio focus request.
 */
internal class AudioPipeline(private val cache: BlockCache, private val clock: MediaClock) {
    val audioClock = AudioClock()

    private val thread = HandlerThread("TlAudio").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var released = false
    @Volatile private var activeReader: ClipReader? = null

    private var log: EpochLog? = null
    private var epoch: Epoch? = null
    private var epochLog: EpochLog? = null
    private var reader: ClipReader? = null
    private var codec: MediaCodec? = null
    private var codecConfig: CodecConfig? = null
    private var inputDone = false
    private var outputDone = true
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackChannels = 0
    private var trackPaused = true
    private var trackDraining = false
    private var outRate = 0
    private var outChannels = 0
    private var pcm: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024).apply { limit(0) }
    private var pcmMediaUs = 0L
    private var align = true
    private var playing = false
    private var speed = 1f
    private var muted = false
    private var lastWriteNs = 0L
    private val info = MediaCodec.BufferInfo()
    private val pumpTask = Runnable { pump() }

    /** Plays from media time [mediaUs] of [log]; replaces whatever was playing. */
    fun start(log: EpochLog, mediaUs: Long) {
        handler.post {
            if (released) return@post
            resetTrack()
            this.log = log
            playing = true
            align = true
            val ep = log.atMedia(mediaUs)
            if (ep == null) {
                closeEpoch()
            } else {
                openEpoch(log, ep, ep.offsetClipUs + maxOf(0L, mediaUs - ep.mediaStartUs))
            }
            schedule(0L)
        }
    }

    /** Continues into epochs of [log] (a restarted video decoder) without resetting audio. */
    fun follow(log: EpochLog) {
        handler.post { this.log = log }
    }

    fun pause() {
        handler.post {
            playing = false
            handler.removeCallbacks(pumpTask)
            pauseTrack()
        }
    }

    fun resume() {
        handler.post {
            if (released || log == null) return@post
            playing = true
            schedule(0L)
        }
    }

    /** Stops output; the clock falls back to standalone time. */
    fun stop() {
        handler.post {
            playing = false
            handler.removeCallbacks(pumpTask)
            closeEpoch()
            log = null
            resetTrack()
        }
    }

    fun setSpeed(s: Float) {
        handler.post {
            speed = s
            applyParams()
            audioClock.setSpeed(s)
        }
    }

    fun setMuted(m: Boolean) {
        handler.post {
            muted = m
            try {
                track?.setVolume(if (m) 0f else 1f)
            } catch (_: Throwable) {
            }
        }
    }

    fun release(waitMs: Long) {
        if (released) return
        released = true
        activeReader?.abort()
        val done = CountDownLatch(1)
        val posted = handler.postAtFrontOfQueue {
            handler.removeCallbacks(pumpTask)
            closeEpoch()
            releaseCodec()
            val t = track
            track = null
            if (t != null) {
                try {
                    t.pause()
                    t.flush()
                } catch (_: Throwable) {
                }
                try {
                    t.release()
                } catch (_: Throwable) {
                }
            }
            audioClock.reset(0, 0L)
            done.countDown()
        }
        if (posted) {
            try {
                done.await(waitMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        thread.quitSafely()
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(pumpTask)
        if (!released && playing) handler.postDelayed(pumpTask, delayMs)
    }

    private fun pump() {
        if (released || !playing) return
        try {
            var progress = false
            val w = writePcm()
            if (w > 0) progress = true
            if (w < 0) {
                pollClock()
                maybeIdleTrack()
                schedule(WRITE_RETRY_MS)
                return
            }
            val lg = log
            if (epoch == null) {
                val next = nextEpochFromLog()
                if (next != null && lg != null) {
                    openEpoch(lg, next, clipUsOfMedia(next, maxOf(next.mediaStartUs, clock.positionUs())))
                    progress = true
                }
            } else if (outputDone) {
                if (advanceEpoch()) progress = true
            } else {
                if (feedDecoder()) progress = true
                if (drainDecoder()) progress = true
            }
            pollClock()
            maybeIdleTrack()
            schedule(if (progress) 0L else IDLE_RETRY_MS)
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.d(TAG, "audio item skipped: ${t.javaClass.simpleName}")
            closeReaderAndDecoderState()
            releaseCodec()
            pcm.clear()
            pcm.limit(0)
            outputDone = true
            schedule(IDLE_RETRY_MS)
        }
    }

    private fun pollClock() {
        val t = track ?: return
        if (!trackPaused) audioClock.poll(t)
    }

    /** 1 wrote or dropped, 0 nothing pending, -1 must wait (buffer full or too far ahead of the clock). */
    private fun writePcm(): Int {
        val p = pcm
        if (!p.hasRemaining()) return 0
        val frameBytes = 2 * outChannels
        if (outRate <= 0 || frameBytes <= 0) {
            p.position(p.limit())
            return 1
        }
        val tr = ensureTrack()
        if (tr == null) {
            if (trackDraining) return -1
            p.position(p.limit())
            return 1
        }
        if (!clock.running) return -1
        val clockUs = clock.positionUs()
        val frames = p.remaining() / frameBytes
        val endUs = pcmMediaUs + frames * 1_000_000L / outRate
        if (align && pcmMediaUs < clockUs) {
            val skip = minOf(frames.toLong(), (clockUs - pcmMediaUs) * outRate / 1_000_000L).toInt()
            p.position(p.position() + skip * frameBytes)
            pcmMediaUs += skip * 1_000_000L / outRate
            if (!p.hasRemaining()) return 1
        } else if (!align && endUs < clockUs - LATE_DROP_US) {
            p.position(p.limit())
            return 1
        }
        if (pcmMediaUs > clockUs + (AHEAD_US * speed).toLong()) return -1
        if (trackPaused) {
            try {
                tr.play()
            } catch (_: IllegalStateException) {
                return -1
            }
            trackPaused = false
            audioClock.invalidate()
        }
        val n = tr.write(p, p.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        if (n < 0) {
            dropTrack()
            return -1
        }
        if (n > 0) {
            val wrote = n / frameBytes
            audioClock.onWrite(wrote, pcmMediaUs)
            pcmMediaUs += wrote * 1_000_000L / outRate
            align = false
            lastWriteNs = System.nanoTime()
        }
        return if (p.hasRemaining()) -1 else 1
    }

    private fun feedDecoder(): Boolean {
        val c = codec ?: return false
        val r = reader ?: return false
        val ep = epoch ?: return false
        if (inputDone) return false
        var progress = false
        val endClipUs = minOf(ep.endClipUs, clipUsOfMedia(ep, mediaEnd(ep)))
        while (true) {
            val idx = c.dequeueInputBuffer(0L)
            if (idx < 0) break
            activeReader = r
            val t = r.sampleTimeUs()
            val buf = c.getInputBuffer(idx)
            val n = if (t < 0 || t >= endClipUs || buf == null) -1 else r.read(buf)
            activeReader = null
            if (n < 0) {
                c.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
                return true
            }
            c.queueInputBuffer(idx, 0, n, t, 0)
            r.advance()
            progress = true
        }
        return progress
    }

    private fun drainDecoder(): Boolean {
        val c = codec ?: return false
        val ep = epoch ?: return false
        if (pcm.hasRemaining()) return false
        val idx = c.dequeueOutputBuffer(info, 0L)
        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val f = c.outputFormat
            outRate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
            outChannels = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                f.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
            ) {
                outChannels = 0
            }
            return true
        }
        if (idx < 0) return false
        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        if (info.size > 0 && outRate > 0 && outChannels > 0) {
            val ob = c.getOutputBuffer(idx)
            if (ob != null) copyPcm(ob, ep)
        }
        c.releaseOutputBuffer(idx, false)
        if (eos) outputDone = true
        return true
    }

    private fun copyPcm(ob: ByteBuffer, ep: Epoch) {
        val frameBytes = 2 * outChannels
        val src = ob.duplicate()
        src.position(info.offset)
        src.limit(info.offset + info.size)
        var clipUs = info.presentationTimeUs
        var frames = src.remaining() / frameBytes
        if (clipUs < ep.offsetClipUs) {
            val skip = minOf(frames.toLong(), (ep.offsetClipUs - clipUs) * outRate / 1_000_000L).toInt()
            src.position(src.position() + skip * frameBytes)
            frames -= skip
            clipUs += skip * 1_000_000L / outRate
        }
        if (frames <= 0) return
        val mediaUs = ep.mediaUsOfClipUs(clipUs)
        val maxFrames = ((mediaEnd(ep) - mediaUs) * outRate / 1_000_000L).coerceAtLeast(0L)
        if (maxFrames < frames) frames = maxFrames.toInt()
        if (frames <= 0) return
        src.limit(src.position() + frames * frameBytes)
        if (pcm.capacity() < src.remaining()) pcm = ByteBuffer.allocateDirect(src.remaining() * 2)
        pcm.clear()
        pcm.put(src)
        pcm.flip()
        pcmMediaUs = mediaUs
    }

    /** True when the next epoch was opened. */
    private fun advanceEpoch(): Boolean {
        val cur = epoch ?: return false
        val lg = log ?: return false
        val next = nextEpochAfter(cur) ?: return false
        openEpoch(lg, next, next.offsetClipUs)
        return true
    }

    /** Epoch under the clock that has not ended yet, else the one after it. */
    private fun nextEpochFromLog(): Epoch? {
        val lg = log ?: return null
        val now = clock.positionUs()
        val e = lg.atMedia(now) ?: return null
        return if (lg.mediaEndOf(e) > now + 1_000L) e else lg.after(e)
    }

    private fun nextEpochAfter(cur: Epoch): Epoch? {
        val lg = log ?: return null
        val end = mediaEnd(cur)
        val e = lg.atMedia(end) ?: return null
        if (e === cur) return lg.after(cur)
        if (e.mediaStartUs >= end - 2_000L) return e
        return lg.after(e)?.takeIf { it.mediaStartUs >= end - 2_000L }
    }

    private fun mediaEnd(ep: Epoch): Long = (epochLog ?: log)?.mediaEndOf(ep) ?: ep.plannedMediaEndUs

    private fun clipUsOfMedia(ep: Epoch, mediaUs: Long): Long = ep.offsetClipUs + (mediaUs - ep.mediaStartUs)

    private fun openEpoch(lg: EpochLog, ep: Epoch, startClipUs: Long) {
        closeReaderAndDecoderState()
        epoch = ep
        epochLog = lg
        outputDone = false
        val r = try {
            ClipReader.open(ep.seg, ClipReader.Kind.AUDIO, cache)
        } catch (_: IOException) {
            null
        }
        if (r == null || released) {
            r?.close()
            outputDone = true
            return
        }
        reader = r
        r.seekPreviousSync(startClipUs)
        val cfg = r.config
        val c = codec
        if (c != null && cfg.sameAs(codecConfig)) {
            c.flush()
        } else {
            releaseCodec()
            r.format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val nc = MediaCodec.createDecoderByType(cfg.mime)
            try {
                nc.configure(r.format, null, null, 0)
                nc.start()
            } catch (t: Throwable) {
                nc.release()
                throw t
            }
            codec = nc
            codecConfig = cfg
        }
        inputDone = false
    }

    private fun closeReaderAndDecoderState() {
        reader?.close()
        reader = null
        activeReader = null
        inputDone = true
    }

    private fun closeEpoch() {
        closeReaderAndDecoderState()
        epoch = null
        epochLog = null
        outputDone = true
        pcm.clear()
        pcm.limit(0)
    }

    private fun releaseCodec() {
        val c = codec ?: return
        codec = null
        codecConfig = null
        try {
            c.stop()
        } catch (_: Throwable) {
        }
        try {
            c.release()
        } catch (_: Throwable) {
        }
    }

    private fun ensureTrack(): AudioTrack? {
        trackDraining = false
        val t = track
        if (t != null && trackRate == outRate && trackChannels == outChannels) return t
        if (t != null) {
            val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (!trackPaused && head < audioClock.writtenFrames) {
                trackDraining = true
                return null
            }
            dropTrack()
        }
        val mask = when (outChannels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return null
        }
        val minBuf = AudioTrack.getMinBufferSize(outRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val bytes = maxOf(minBuf * 2, outRate * outChannels * 2 / 4)
        val nt = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(outRate)
                        .setChannelMask(mask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bytes)
                .build()
        } catch (_: Throwable) {
            null
        } ?: return null
        if (nt.state != AudioTrack.STATE_INITIALIZED) {
            nt.release()
            return null
        }
        track = nt
        trackRate = outRate
        trackChannels = outChannels
        trackPaused = true
        audioClock.reset(outRate, nt.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
        audioClock.setSpeed(speed)
        try {
            nt.setVolume(if (muted) 0f else 1f)
        } catch (_: Throwable) {
        }
        applyParams()
        return nt
    }

    private fun applyParams() {
        val t = track ?: return
        try {
            t.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
        } catch (_: Throwable) {
            if (speed != 1f) {
                playing = false
                pauseTrack()
            }
        }
    }

    private fun pauseTrack() {
        val t = track ?: return
        if (!trackPaused) {
            try {
                t.pause()
            } catch (_: Throwable) {
            }
            trackPaused = true
        }
        audioClock.invalidate()
    }

    /** Pauses a drained track while nothing is due, so the next write restarts cleanly. */
    private fun maybeIdleTrack() {
        val t = track ?: return
        if (trackPaused || System.nanoTime() - lastWriteNs < IDLE_PAUSE_NS) return
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (head >= audioClock.writtenFrames) pauseTrack()
    }

    private fun resetTrack() {
        pcm.clear()
        pcm.limit(0)
        val t = track ?: run {
            audioClock.reset(0, 0L)
            return
        }
        try {
            t.pause()
            t.flush()
        } catch (_: Throwable) {
        }
        trackPaused = true
        audioClock.reset(trackRate, t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
        audioClock.setSpeed(speed)
    }

    private fun dropTrack() {
        val t = track ?: return
        track = null
        try {
            t.pause()
            t.flush()
        } catch (_: Throwable) {
        }
        try {
            t.release()
        } catch (_: Throwable) {
        }
        trackPaused = true
        audioClock.reset(0, 0L)
    }

    companion object {
        private const val TAG = "TlAudio"
        private const val AHEAD_US = 400_000L
        private const val LATE_DROP_US = 200_000L
        private const val WRITE_RETRY_MS = 10L
        private const val IDLE_RETRY_MS = 40L
        private const val IDLE_PAUSE_NS = 1_000_000_000L
    }
}
