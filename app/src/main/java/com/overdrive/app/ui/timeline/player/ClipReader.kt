package com.overdrive.app.ui.timeline.player

import android.media.MediaExtractor
import android.media.MediaFormat
import com.overdrive.app.ui.timeline.TlSegment
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * One track of one clip read through MediaExtractor, from a readable local file or from the daemon
 * over HTTP. Not thread-safe: one owner thread; [abort] is the only cross-thread call.
 */
internal class ClipReader private constructor(
    val seg: TlSegment,
    val kind: Kind,
    private val extractor: MediaExtractor,
    private val source: HttpRangeDataSource?,
    private val file: FileInputStream?,
    val format: MediaFormat,
    val config: CodecConfig,
) {
    enum class Kind { VIDEO, AUDIO }

    @Volatile var closed = false
        private set

    val mime: String get() = config.mime

    /** Failure behind a premature end of samples; null for a real end of stream. */
    val ioError: IOException? get() = source?.error

    /** System.nanoTime() of the blocking network read in progress, 0 when none. */
    val busySinceNs: Long get() = source?.busySinceNs ?: 0L

    fun seekPreviousSync(clipUs: Long) {
        extractor.seekTo(maxOf(0L, clipUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    fun seekNextSync(clipUs: Long) {
        extractor.seekTo(maxOf(0L, clipUs), MediaExtractor.SEEK_TO_NEXT_SYNC)
    }

    /** Presentation time of the current sample in clip time, -1 at the end. */
    fun sampleTimeUs(): Long = extractor.sampleTime

    fun isSync(): Boolean = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0

    fun sampleSize(): Long = extractor.sampleSize

    /** Copies the current sample into [buf] from position 0; -1 at the end. */
    fun read(buf: ByteBuffer): Int {
        buf.clear()
        return extractor.readSampleData(buf, 0)
    }

    fun advance(): Boolean = extractor.advance()

    /** Unblocks a reader stuck on the network; the reader must be closed afterwards. */
    fun abort() {
        source?.abort()
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            extractor.release()
        } catch (_: Throwable) {
        }
        try {
            source?.close()
        } catch (_: Throwable) {
        }
        try {
            file?.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        /** Opens the [kind] track of [seg]; null when the clip has no such track. */
        @Throws(IOException::class)
        fun open(
            seg: TlSegment,
            kind: Kind,
            cache: BlockCache,
            localPath: String? = null,
            onSource: ((HttpRangeDataSource) -> Unit)? = null,
        ): ClipReader? {
            val ex = MediaExtractor()
            var src: HttpRangeDataSource? = null
            var fis: FileInputStream? = null
            try {
                val f = localPath?.let { File(it) }
                if (f != null && f.isFile && f.canRead()) {
                    val stream = FileInputStream(f)
                    fis = stream
                    ex.setDataSource(stream.fd)
                } else {
                    val s = HttpRangeDataSource(cache, seg.videoUrl, seg.fp)
                    src = s
                    onSource?.invoke(s)
                    s.open()
                    ex.setDataSource(s)
                }
                val idx = findTrack(ex, kind)
                if (idx < 0) {
                    release(ex, src, fis)
                    return null
                }
                ex.selectTrack(idx)
                val fmt = ex.getTrackFormat(idx)
                return ClipReader(seg, kind, ex, src, fis, fmt, configOf(fmt))
            } catch (e: IOException) {
                release(ex, src, fis)
                throw src?.error ?: e
            } catch (e: RuntimeException) {
                release(ex, src, fis)
                throw src?.error ?: IOException("unreadable clip: ${e.javaClass.simpleName}", e)
            }
        }

        fun configOf(fmt: MediaFormat): CodecConfig {
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            val w = if (fmt.containsKey(MediaFormat.KEY_WIDTH)) fmt.getInteger(MediaFormat.KEY_WIDTH) else 0
            val h = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else 0
            val csd = ArrayList<ByteArray>(3)
            for (k in arrayOf("csd-0", "csd-1", "csd-2")) {
                val b = if (fmt.containsKey(k)) fmt.getByteBuffer(k) else null
                if (b != null) {
                    val d = b.duplicate()
                    d.rewind()
                    val arr = ByteArray(d.remaining())
                    d.get(arr)
                    csd.add(arr)
                }
            }
            return CodecConfig(mime, w, h, csd)
        }

        private fun findTrack(ex: MediaExtractor, kind: Kind): Int {
            val prefix = if (kind == Kind.VIDEO) "video/" else "audio/"
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith(prefix)) return i
            }
            return -1
        }

        private fun release(ex: MediaExtractor, src: HttpRangeDataSource?, fis: FileInputStream?) {
            try {
                ex.release()
            } catch (_: Throwable) {
            }
            try {
                src?.close()
            } catch (_: Throwable) {
            }
            try {
                fis?.close()
            } catch (_: Throwable) {
            }
        }
    }
}
