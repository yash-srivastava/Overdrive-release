package com.overdrive.app.ui.timeline.player

import com.overdrive.app.ui.timeline.ClipSpec
import com.overdrive.app.ui.timeline.TlSegment
import java.io.IOException

/** What the feeder should do for one decoder generation. */
internal class FeedRequest(
    val kind: Kind,
    val item: PlanItem,
    val targetWall: Long,
    val precise: Boolean,
    val join: Join,
    val mediaStartUs: Long,
    val gapFromWall: Long,
    val crossfade: Boolean,
) {
    enum class Kind { PLAY, SCRUB }

    @Volatile var gen: Int = 0
}

internal enum class EndReason { END, RESTART, FAILED, SCRUB }

/** Carried by the end-of-stream input so the output side knows why the run stopped. */
internal class EosInfo(
    val reason: EndReason,
    val last: Epoch?,
    val next: PlanItem?,
    val endWall: Long,
    val mediaEndUs: Long,
)

internal class Sample(
    val gen: Int,
    val data: ByteArray?,
    val size: Int,
    val tag: Long,
    val flags: Int,
    val eos: EosInfo?,
) {
    // Pooled capacity may exceed the valid payload; account for real queue memory.
    val storageBytes: Int get() = data?.size ?: 0
}

internal class OutFrame(val index: Int, val tag: Long, val eos: EosInfo?)

internal sealed class PipeEvent(val gen: Int) {
    class FirstFrame(gen: Int, val epoch: Epoch) : PipeEvent(gen)
    class TargetShown(gen: Int, val epoch: Epoch) : PipeEvent(gen)
    class ScrubShown(gen: Int, val epoch: Epoch?) : PipeEvent(gen)
    class GapHold(gen: Int, val epoch: Epoch) : PipeEvent(gen)
    class RunEnded(gen: Int, val info: EosInfo) : PipeEvent(gen)
    class NeedsRestart(gen: Int, val request: FeedRequest) : PipeEvent(gen)
    class ItemFailed(gen: Int, val item: PlanItem, val error: IOException) : PipeEvent(gen)
    class CodecError(gen: Int, val item: PlanItem?, val error: Throwable) : PipeEvent(gen)
    class VideoSize(gen: Int, val width: Int, val height: Int) : PipeEvent(gen)
    class RenderSpeed(gen: Int, val speed: Float) : PipeEvent(gen)
    class TooSlow(gen: Int) : PipeEvent(gen)
    class SurfaceLost(gen: Int) : PipeEvent(gen)
}

/** Player side of a pipeline. Calls arrive on pipeline threads and must not block. */
internal interface PipelineHost {
    fun onPipelineEvent(p: VideoPipeline, e: PipeEvent)
    fun frameSink(): FrameSink?
    /** Feeder thread; may block briefly. */
    fun specFor(seg: TlSegment): ClipSpec?
    fun continueAcrossGaps(): Boolean
    fun skipQuiet(): Boolean
    fun quietZones(): QuietZones
    fun userSpeed(): Int
}
