package com.overdrive.app.ui.timeline.player

import com.overdrive.app.ui.timeline.ClipSpec

/**
 * Renderer side of the decoder output. Times are System.nanoTime() values that match the
 * SurfaceTexture timestamp of the frame they refer to. Called from player threads.
 */
interface FrameSink {
    /** Apply [spec] from the first frame whose timestamp is >= [renderTimeNs]. */
    fun scheduleClipSpec(renderTimeNs: Long, spec: ClipSpec?)

    /** Crossfade from the held frame into the frame whose timestamp is >= [renderTimeNs]. */
    fun scheduleCrossfade(renderTimeNs: Long, durationMs: Long)

    /** Keep showing the current frame until the next scheduled crossfade. */
    fun holdFrame()
}
