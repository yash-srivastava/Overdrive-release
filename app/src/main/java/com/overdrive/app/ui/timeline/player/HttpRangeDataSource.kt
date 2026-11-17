package com.overdrive.app.ui.timeline.player

import android.media.MediaDataSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The clip is gone from the daemon (404/410), e.g. reaped by retention while playing. */
class ClipGoneException(message: String) : IOException(message)

/** The file behind a clip changed while it was being read. */
class ClipChangedException(message: String) : IOException(message)

internal interface RangeOwner {
    val isClosed: Boolean
}

/**
 * Per-player cache of 512 KiB blocks fetched with HTTP Range GETs from the local daemon.
 * Keys include the clip fingerprint, so a replaced file never mixes with cached bytes.
 */
internal class BlockCache(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS,
    private val baseUrl: String = BASE_URL,
    private val client: OkHttpClient = sharedClient(),
    private val cookie: () -> String? = { defaultCookie() },
) {
    private class Meta {
        var size = -1L
        var etag: String? = null
    }

    private class Fetch {
        val done = CountDownLatch(1)
        @Volatile var data: ByteArray? = null
    }

    private class Prefetch(val url: String, val fp: String, val index: Int, val owner: RangeOwner?, val key: String)

    private val lock = Object()
    private val lru = LinkedHashMap<String, ByteArray>(64, 0.75f, true)
    private var lruBytes = 0L
    private val inflight = HashMap<String, Fetch>()
    private val meta = HashMap<String, Meta>()
    private val calls = HashMap<Call, RangeOwner?>()
    private val prefetchQueue = ArrayDeque<Prefetch>()
    private val prefetchKeys = HashSet<String>()
    private var prefetchThread: Thread? = null
    @Volatile private var closed = false

    /** Clip length in bytes; fetches block 0 when it is not known yet. */
    @Throws(IOException::class)
    fun open(url: String, fp: String, owner: RangeOwner?): Long {
        val ck = clipKey(url, fp)
        synchronized(lock) { meta[ck]?.let { if (it.size >= 0) return it.size } }
        block(url, fp, 0, owner)
        synchronized(lock) {
            val size = meta[ck]?.size ?: -1L
            if (size <= 0) throw IOException("empty clip")
            return size
        }
    }

    @Throws(IOException::class)
    fun block(url: String, fp: String, index: Int, owner: RangeOwner?): ByteArray = block(url, fp, index, owner, true)

    private fun block(url: String, fp: String, index: Int, owner: RangeOwner?, mayWait: Boolean): ByteArray {
        val bk = clipKey(url, fp) + "#" + index
        var waitFor: Fetch? = null
        val mine: Fetch
        synchronized(lock) {
            if (closed) throw IOException("player released")
            lru[bk]?.let { return it }
            val f = inflight[bk]
            if (f != null && mayWait) {
                waitFor = f
                mine = f
            } else {
                mine = Fetch()
                inflight[bk] = mine
            }
        }
        val other = waitFor
        if (other != null) {
            if (!other.done.await(requestTimeoutMs + 1_000L, TimeUnit.MILLISECONDS)) throw IOException("read timeout")
            other.data?.let { return it }
            return block(url, fp, index, owner, false)
        }
        try {
            val data = fetchWithRetry(url, fp, index, owner)
            mine.data = data
            synchronized(lock) { putLocked(bk, data) }
            return data
        } finally {
            synchronized(lock) { if (inflight[bk] === mine) inflight.remove(bk) }
            mine.done.countDown()
        }
    }

    /** Queues read-ahead of the [READ_AHEAD] blocks after [index]. */
    fun prefetchAfter(url: String, fp: String, index: Int, owner: RangeOwner?) {
        for (j in 1..READ_AHEAD) prefetch(url, fp, index + j, owner)
    }

    fun prefetch(url: String, fp: String, index: Int, owner: RangeOwner?) {
        val ck = clipKey(url, fp)
        val bk = "$ck#$index"
        synchronized(lock) {
            if (closed || owner?.isClosed == true) return
            val size = meta[ck]?.size ?: return
            if (index < 0 || index.toLong() * BLOCK_SIZE >= size) return
            if (lru.containsKey(bk) || inflight.containsKey(bk) || !prefetchKeys.add(bk)) return
            prefetchQueue.addLast(Prefetch(url, fp, index, owner, bk))
            while (prefetchQueue.size > MAX_PREFETCH_QUEUE) prefetchKeys.remove(prefetchQueue.removeFirst().key)
            if (prefetchThread == null) {
                prefetchThread = Thread({ prefetchLoop() }, "TlPrefetch").apply {
                    isDaemon = true
                    start()
                }
            }
            lock.notifyAll()
        }
    }

    /** Cancels in-flight requests of [owner] so a blocked reader returns promptly. */
    fun cancel(owner: RangeOwner) {
        val victims = synchronized(lock) { calls.filterValues { it === owner }.keys.toList() }
        for (c in victims) c.cancel()
    }

    /** Releases everything; blocked reads fail fast. */
    fun close() {
        val victims: List<Call>
        synchronized(lock) {
            closed = true
            victims = calls.keys.toList()
            lru.clear()
            lruBytes = 0
            prefetchQueue.clear()
            prefetchKeys.clear()
            lock.notifyAll()
        }
        for (c in victims) c.cancel()
    }

    private fun prefetchLoop() {
        while (true) {
            val task: Prefetch
            val skip: Boolean
            synchronized(lock) {
                while (!closed && prefetchQueue.isEmpty()) lock.wait(1_000L)
                if (closed) {
                    prefetchThread = null
                    return
                }
                task = prefetchQueue.removeLast()
                prefetchKeys.remove(task.key)
                skip = lru.containsKey(task.key) || inflight.containsKey(task.key)
            }
            if (skip || task.owner?.isClosed == true) continue
            try {
                block(task.url, task.fp, task.index, task.owner, false)
            } catch (_: IOException) {
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun putLocked(key: String, data: ByteArray) {
        if (closed) return
        val old = lru.put(key, data)
        if (old != null) lruBytes -= old.size
        lruBytes += data.size
        val it = lru.entries.iterator()
        while (lruBytes > maxBytes && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            lruBytes -= e.value.size
            it.remove()
        }
    }

    private fun invalidateLocked(ck: String) {
        val prefix = "$ck#"
        val it = lru.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.startsWith(prefix)) {
                lruBytes -= e.value.size
                it.remove()
            }
        }
    }

    private fun fetchWithRetry(url: String, fp: String, index: Int, owner: RangeOwner?): ByteArray {
        val deadline = System.nanoTime() + requestTimeoutMs * 1_000_000L
        var attempt = 0
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) throw IOException("read timeout")
            try {
                return fetchOnce(url, fp, index, owner, left)
            } catch (e: IOException) {
                val retryable = e !is ClipGoneException && e !is ClipChangedException &&
                    !closed && owner?.isClosed != true && attempt == 0
                if (!retryable || (deadline - System.nanoTime()) / 1_000_000L < RETRY_DELAY_MS + 200L) throw e
                attempt++
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    throw e
                }
            }
        }
    }

    private fun fetchOnce(url: String, fp: String, index: Int, owner: RangeOwner?, timeoutMs: Long): ByteArray {
        val ck = clipKey(url, fp)
        val knownSize = synchronized(lock) { meta[ck]?.size ?: -1L }
        val from = index.toLong() * BLOCK_SIZE
        if (knownSize >= 0 && from >= knownSize) return ByteArray(0)
        val to = if (knownSize >= 0) minOf(from + BLOCK_SIZE, knownSize) - 1 else from + BLOCK_SIZE - 1
        val rb = Request.Builder().url(absolute(url)).get().header("Range", "bytes=$from-$to")
        cookie()?.let { rb.header("Cookie", it) }
        val call = client.newCall(rb.build())
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(lock) {
            if (closed || owner?.isClosed == true) throw IOException("cancelled")
            calls[call] = owner
        }
        try {
            call.execute().use { resp ->
                when (resp.code) {
                    206 -> Unit
                    200 -> if (from != 0L) throw IOException("range ignored")
                    404, 410 -> throw ClipGoneException("clip no longer available (HTTP ${resp.code})")
                    416 -> throw ClipChangedException("clip shorter than expected")
                    else -> throw IOException("HTTP ${resp.code}")
                }
                val total: Long
                if (resp.code == 206) {
                    val cr = parseContentRange(resp.header("Content-Range")) ?: throw IOException("bad Content-Range")
                    if (cr[0] != from) throw IOException("unexpected range start")
                    total = cr[2]
                } else {
                    total = resp.body?.contentLength() ?: -1L
                }
                if (total <= 0) throw IOException("unknown clip length")
                val etag = resp.header("ETag")
                synchronized(lock) {
                    val m = meta.getOrPut(ck) { Meta() }
                    if ((m.size >= 0 && m.size != total) || (m.etag != null && etag != null && m.etag != etag)) {
                        invalidateLocked(ck)
                        throw ClipChangedException("clip changed while playing")
                    }
                    m.size = total
                    if (m.etag == null) m.etag = etag
                }
                val want = (minOf(from + BLOCK_SIZE, total) - from).toInt()
                if (want <= 0) return ByteArray(0)
                val source = (resp.body ?: throw IOException("empty body")).source()
                val out = ByteArray(want)
                var n = 0
                while (n < want) {
                    val r = source.read(out, n, want - n)
                    if (r < 0) break
                    n += r
                }
                if (n < want) throw IOException("short read $n/$want")
                return out
            }
        } finally {
            synchronized(lock) { calls.remove(call) }
        }
    }

    private fun absolute(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> baseUrl + url
        else -> "$baseUrl/$url"
    }

    companion object {
        const val BLOCK_SIZE = 512 * 1024
        const val READ_AHEAD = 2
        const val DEFAULT_MAX_BYTES = 24L * 1024 * 1024
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val BASE_URL = "http://127.0.0.1:8080"
        private const val RETRY_DELAY_MS = 150L
        private const val MAX_PREFETCH_QUEUE = 6

        @Volatile private var shared: OkHttpClient? = null

        fun sharedClient(): OkHttpClient {
            shared?.let { return it }
            synchronized(this) {
                shared?.let { return it }
                val c = OkHttpClient.Builder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .proxy(Proxy.NO_PROXY)
                    .build()
                shared = c
                return c
            }
        }

        fun defaultCookie(): String? = try {
            com.overdrive.app.util.DaemonHttpClient.sessionCookie()
        } catch (_: Throwable) {
            null
        }

        fun clipKey(url: String, fp: String): String = "$fp|$url"

        /** "bytes a-b/total" -> [a, b, total]. */
        fun parseContentRange(v: String?): LongArray? {
            if (v == null) return null
            val s = v.trim()
            if (!s.startsWith("bytes ", ignoreCase = true)) return null
            val dash = s.indexOf('-', 6)
            val slash = s.indexOf('/', 6)
            if (dash < 0 || slash < dash) return null
            return try {
                longArrayOf(
                    s.substring(6, dash).trim().toLong(),
                    s.substring(dash + 1, slash).trim().toLong(),
                    s.substring(slash + 1).trim().toLong(),
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}

/**
 * MediaDataSource over [BlockCache]. A failure is recorded in [error] before it is thrown, because
 * MediaExtractor reports a failed read the same way as end of stream.
 */
internal class HttpRangeDataSource(
    private val cache: BlockCache,
    private val url: String,
    private val fp: String,
) : MediaDataSource(), RangeOwner {
    @Volatile var error: IOException? = null
        private set
    @Volatile override var isClosed: Boolean = false
        private set
    /** System.nanoTime() when the current blocking read started, 0 when idle. */
    @Volatile var busySinceNs: Long = 0L
        private set
    @Volatile private var length: Long = -1L

    @Throws(IOException::class)
    fun open() {
        busySinceNs = System.nanoTime()
        try {
            length = cache.open(url, fp, this)
        } catch (e: IOException) {
            throw fail(e)
        } finally {
            busySinceNs = 0L
        }
    }

    override fun getSize(): Long = length

    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        val total = length
        if (position < 0 || total < 0 || position >= total) return -1
        if (isClosed) throw fail(IOException("closed"))
        var done = 0
        var lastBlock = -1
        busySinceNs = System.nanoTime()
        try {
            while (done < size) {
                val p = position + done
                if (p >= total) break
                val bi = (p / BlockCache.BLOCK_SIZE).toInt()
                val blk = cache.block(url, fp, bi, this)
                val inBlk = (p - bi.toLong() * BlockCache.BLOCK_SIZE).toInt()
                if (inBlk >= blk.size) break
                val n = minOf(size - done, blk.size - inBlk)
                System.arraycopy(blk, inBlk, buffer, offset + done, n)
                done += n
                lastBlock = bi
            }
        } catch (e: IOException) {
            throw fail(e)
        } finally {
            busySinceNs = 0L
        }
        if (lastBlock >= 0) cache.prefetchAfter(url, fp, lastBlock, this)
        return if (done == 0) -1 else done
    }

    /** Unblocks a pending read; the source stays unusable afterwards. */
    fun abort() {
        if (error == null) error = IOException("cancelled")
        isClosed = true
        cache.cancel(this)
    }

    override fun close() {
        isClosed = true
        cache.cancel(this)
    }

    private fun fail(e: IOException): IOException {
        if (error == null) error = e
        return e
    }
}
