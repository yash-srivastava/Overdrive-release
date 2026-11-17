package com.overdrive.app.ui.timeline.view

/**
 * Frame-exact switches keyed by the decoder's render timestamp (SurfaceTexture.getTimestamp), plus the
 * held-picture / crossfade state the renderer presents. Not thread-safe.
 */
class FrameSchedule<S> {
    private class SpecAt<S>(val at: Long, val spec: S?)
    private class FadeAt(val at: Long, val durationMs: Long)

    private val specs = ArrayDeque<SpecAt<S>>()
    private val fades = ArrayDeque<FadeAt>()

    /** The held picture is shown instead of live frames. */
    var holdShown = false
        private set

    /** Blending from the held picture to live frames. */
    var fading = false
        private set

    var snapshotFresh = false
        private set

    /** Spec popped by the last [takeDueSpec] that returned true. */
    var dueSpec: S? = null
        private set

    private var fadeStartNs = 0L
    private var fadeDurationNs = 0L

    val pendingSpecs: Int get() = specs.size
    val pendingFades: Int get() = fades.size

    /** An entry at or before pending ones replaces them: the player re-timed its output. */
    fun scheduleSpec(atNs: Long, spec: S?) {
        while (specs.isNotEmpty() && specs.last().at >= atNs) specs.removeLast()
        specs.addLast(SpecAt(atNs, spec))
        while (specs.size > MAX_PENDING) specs.removeFirst()
    }

    fun scheduleFade(atNs: Long, durationMs: Long) {
        while (fades.isNotEmpty() && fades.last().at >= atNs) fades.removeLast()
        fades.addLast(FadeAt(atNs, durationMs.coerceIn(0L, MAX_FADE_MS)))
        while (fades.size > MAX_PENDING) fades.removeFirst()
    }

    /** Pops every spec due at [frameTs] (a dropped frame's entry applies on the next one); the newest wins. */
    fun takeDueSpec(frameTs: Long): Boolean {
        var any = false
        while (specs.isNotEmpty() && specs.first().at <= frameTs + TOLERANCE_NS) {
            dueSpec = specs.removeFirst().spec
            any = true
        }
        return any
    }

    /** Pops every fade due at [frameTs]; the newest one's duration, or -1. */
    fun takeDueFade(frameTs: Long): Long {
        var ms = -1L
        while (fades.isNotEmpty() && fades.first().at <= frameTs + TOLERANCE_NS) ms = fades.removeFirst().durationMs
        return ms
    }

    /** The current picture must be copied before latching: a pending fade may start on the next frame. */
    fun wantsSnapshot(hasFrame: Boolean, lastFrameTs: Long): Boolean =
        hasFrame && !holdShown && fades.isNotEmpty() && fades.first().at - lastFrameTs <= SNAPSHOT_LEAD_NS

    fun onSnapshot() {
        snapshotFresh = true
    }

    /** The just-captured picture replaces live frames until a fade starts or a frame arrives with none pending. */
    fun hold() {
        holdShown = true
        fading = false
    }

    /** Fade from the just-captured picture, starting now. */
    fun fadeNow(nowNs: Long, durationMs: Long) {
        holdShown = false
        if (durationMs > 0L) startFade(nowNs, durationMs) else fading = false
    }

    /** Resolves a latch given [dueFadeMs] from [takeDueFade]; true when the fresh snapshot becomes the fade source. */
    fun endLatch(dueFadeMs: Long, nowNs: Long): Boolean {
        var useSnapshot = false
        if (dueFadeMs >= 0L) {
            if ((holdShown || snapshotFresh) && dueFadeMs > 0L) {
                useSnapshot = !holdShown
                startFade(nowNs, dueFadeMs)
            } else {
                fading = false
            }
            holdShown = false
        } else if (holdShown && fades.isEmpty()) {
            holdShown = false
        }
        snapshotFresh = false
        return useSnapshot
    }

    /** Linear progress 0..1 at [nowNs]; the fade ends at 1. */
    fun fadeProgress(nowNs: Long): Float {
        if (!fading) return 1f
        val p = ((nowNs - fadeStartNs).toDouble() / fadeDurationNs).toFloat().coerceIn(0f, 1f)
        if (p >= 1f) fading = false
        return p
    }

    fun clearPresentation() {
        holdShown = false
        fading = false
        snapshotFresh = false
    }

    private fun startFade(nowNs: Long, durationMs: Long) {
        fading = true
        fadeStartNs = nowNs
        fadeDurationNs = durationMs * 1_000_000L
    }

    companion object {
        const val TOLERANCE_NS = 2_000_000L
        const val MAX_PENDING = 8
        const val SNAPSHOT_LEAD_NS = 1_500_000_000L
        const val MAX_FADE_MS = 2_000L
    }
}
