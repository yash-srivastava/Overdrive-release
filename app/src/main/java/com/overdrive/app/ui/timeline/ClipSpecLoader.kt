package com.overdrive.app.ui.timeline

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import com.overdrive.app.ui.view.PlaybackDewarp
import com.overdrive.app.ui.view.ZoomableVideoView
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Per-clip render spec (camera layout + lens) from the clip's sidecar, fetched
 * off the UI thread and cached by id+fp. Every method is safe from any thread.
 */
class ClipSpecLoader {
    /** [until]: a fallback built while the sidecar couldn't be read expires (the card may be back). */
    private class Holder(val spec: ClipSpec, val epoch: Int, val until: Long = Long.MAX_VALUE)

    private val cache = LruCache<String, Holder>(CACHE_ENTRIES)
    private val inflight = ConcurrentHashMap<String, FutureTask<Holder>>()
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "TimelineSpecs").apply { isDaemon = true }
    }
    @Volatile private var epoch = 0
    @Volatile private var released = false

    fun cached(seg: TlSegment): ClipSpec? =
        cache.get(key(seg))?.takeIf { it.epoch == epoch && SystemClock.elapsedRealtime() < it.until }?.spec

    /** Player hook: answers at once on the main thread, else waits briefly for the sidecar. */
    fun specFor(seg: TlSegment): ClipSpec {
        cached(seg)?.let { return it }
        val task = request(seg)
        if (task == null || Looper.myLooper() == Looper.getMainLooper()) return fallback(seg)
        return try {
            task.get(PROVIDER_WAIT_MS, TimeUnit.MILLISECONDS).spec
        } catch (t: Throwable) {
            fallback(seg)
        }
    }

    fun prefetch(segs: List<TlSegment>) {
        for (s in segs) if (cached(s) == null) request(s)
    }

    /** Async lookup delivered on the main thread (used after a strength change). */
    fun load(seg: TlSegment, onLoaded: (ClipSpec) -> Unit) {
        cached(seg)?.let { onLoaded(it); return }
        if (released) return
        try {
            executor.execute {
                val spec = cached(seg) ?: compute(seg, epoch).spec
                main.post { if (!released) onLoaded(spec) }
            }
        } catch (t: Throwable) {
            // Shut down: nothing to deliver.
        }
    }

    /** Lens specs bake in the live strength; drop them when it changes. */
    fun invalidate() {
        epoch++
    }

    fun release() {
        released = true
        executor.shutdownNow()
        inflight.clear()
        cache.evictAll()
    }

    private fun request(seg: TlSegment): FutureTask<Holder>? {
        if (released) return null
        val ep = epoch
        val k = key(seg) + "@" + ep
        inflight[k]?.let { return it }
        val task = FutureTask {
            try {
                compute(seg, ep)
            } finally {
                inflight.remove(k)
            }
        }
        val prior = inflight.putIfAbsent(k, task)
        if (prior != null) return prior
        return try {
            executor.execute(task)
            task
        } catch (t: Throwable) {
            inflight.remove(k)
            null
        }
    }

    private fun compute(seg: TlSegment, ep: Int): Holder {
        val json = if (seg.type == TYPE_OEM) null else TimelineApiClient.fetchEventJson(seg.eventUrl)
        // A missing or empty sidecar (the card may be gone for a moment) is looked at again later.
        val until = if ((json == null || json.length() == 0) && seg.type != TYPE_OEM) SystemClock.elapsedRealtime() + FALLBACK_TTL_MS else Long.MAX_VALUE
        val holder = Holder(build(seg, json), ep, until)
        if (ep == epoch) cache.put(key(seg), holder)
        return holder
    }

    private fun key(seg: TlSegment) = seg.id + "|" + seg.fp

    companion object {
        private const val CACHE_ENTRIES = 256
        private const val FALLBACK_TTL_MS = 60_000L
        private const val PROVIDER_WAIT_MS = 1_200L
        private const val TYPE_OEM = "oemDashcam"

        /** Spec before the sidecar arrives: the index's layout tag, no lens. */
        fun fallback(seg: TlSegment): ClipSpec {
            val single = seg.type == TYPE_OEM || seg.layout == "single"
            val layout = when {
                single -> null
                seg.layout == "dashcam" -> ZoomableVideoView.Layout.DASHCAM
                else -> ZoomableVideoView.Layout.STANDARD
            }
            return ClipSpec(layout, null, single)
        }

        /** A lens-tagged clip is always the 2x2 mosaic; dvr_ clips are one camera. */
        fun build(seg: TlSegment, json: JSONObject?): ClipSpec {
            if (json == null) return fallback(seg)
            val single = seg.type == TYPE_OEM
            val tag = json.optJSONObject("lens")
            val lensTagged = tag != null && tag.optBoolean("raw", false) && tag.optString("model") == "divide"
            val layoutName = if (json.isNull("layout")) null else json.optString("layout", "")
            val layout = when {
                single -> null
                lensTagged -> ZoomableVideoView.Layout.STANDARD
                layoutName == "dashcam" -> ZoomableVideoView.Layout.DASHCAM
                // An empty or unreadable sidecar: the index's own layout tag knows better than a guess.
                layoutName.isNullOrEmpty() && seg.layout == "dashcam" -> ZoomableVideoView.Layout.DASHCAM
                else -> ZoomableVideoView.Layout.STANDARD
            }
            val lens = if (single) null else PlaybackDewarp.specFor(json)
            val grid = if (single || !json.has("grid")) null else GridSpec.parse(json, PlaybackDewarp.liveStrength())
            return ClipSpec(layout, lens, single, grid)
        }
    }
}
