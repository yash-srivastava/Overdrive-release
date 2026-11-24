package com.overdrive.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.overdrive.app.R

/**
 * Custom view that renders event detection markers on a video timeline.
 * Each event span is drawn as a colored rectangle proportional to the video duration.
 * A playhead indicator shows current position.
 *
 * Colors: motion=gray, person=red, car=blue, bike=green, animal=purple
 */
class EventTimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TimelineEvent(
        val startMs: Long,
        val endMs: Long,
        val type: String,
        val confidence: Float = 0f
    )

    private var events: List<TimelineEvent> = emptyList()
    private var durationMs: Long = 0
    private var playheadMs: Long = 0
    private var pendingTapMs: Long? = null
    private var onSeekRequested: ((Long) -> Unit)? = null
    private var onEventSelected: ((TimelineEvent) -> Unit)? = null

    private val paintMotion = Paint().apply { color = 0x99888888.toInt(); isAntiAlias = true }
    private val paintPerson = Paint().apply { color = 0xCCFF4444.toInt(); isAntiAlias = true }
    private val paintCar = Paint().apply { color = 0xCC4488FF.toInt(); isAntiAlias = true }
    private val paintBike = Paint().apply { color = 0xCC44CC44.toInt(); isAntiAlias = true }
    private val paintAnimal = Paint().apply { color = 0xCCA855F7.toInt(); isAntiAlias = true }
    private val animalMarker = android.graphics.Path()
    private val paintPlayhead = Paint().apply { color = 0xFFFFFFFF.toInt(); isAntiAlias = true }

    fun setEvents(events: List<TimelineEvent>, durationMs: Long) {
        this.events = events
        this.durationMs = durationMs
        contentDescription = if (events.isEmpty()) {
            context.getString(R.string.video_timeline_empty)
        } else {
            context.getString(R.string.video_timeline_events, events.size)
        }
        invalidate()
    }

    fun setPlayhead(positionMs: Long) {
        this.playheadMs = positionMs
        invalidate()
    }

    fun setOnSeekRequestedListener(listener: ((Long) -> Unit)?) {
        onSeekRequested = listener
    }

    fun setOnEventSelectedListener(listener: ((TimelineEvent) -> Unit)?) {
        onEventSelected = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0 || width <= 0) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                pendingTapMs = ((event.x / width).coerceIn(0f, 1f) * durationMs).toLong()
                return performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        val requested = pendingTapMs ?: playheadMs
        pendingTapMs = null
        selectNearest(requested)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.SeekBar::class.java.name
        info.isClickable = true
        if (durationMs > 0) {
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                0f,
                durationMs.toFloat(),
                playheadMs.coerceIn(0L, durationMs).toFloat()
            )
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (events.isNotEmpty() && action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            val next = events.firstOrNull { it.startMs > playheadMs } ?: events.last()
            selectEvent(next)
            return true
        }
        if (events.isNotEmpty() && action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            val previous = events.lastOrNull { it.startMs < playheadMs } ?: events.first()
            selectEvent(previous)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    /** True when a tap at [x] (view coordinates) would snap to an event rather than seek. */
    fun hasEventNear(x: Float): Boolean {
        if (width <= 0 || durationMs <= 0 || events.isEmpty()) return false
        val requestedMs = ((x / width).coerceIn(0f, 1f) * durationMs).toLong()
        val thresholdMs = (durationMs * 32L / width).coerceAtLeast(500L)
        return events.any { kotlin.math.abs(it.startMs - requestedMs) <= thresholdMs }
    }

    private fun selectNearest(requestedMs: Long) {
        val nearest = events.minByOrNull { kotlin.math.abs(it.startMs - requestedMs) }
        val thresholdMs = if (width > 0) (durationMs * 32L / width).coerceAtLeast(500L) else 500L
        if (nearest != null && kotlin.math.abs(nearest.startMs - requestedMs) <= thresholdMs) {
            selectEvent(nearest)
        } else {
            onSeekRequested?.invoke(requestedMs.coerceIn(0L, durationMs))
        }
    }

    private fun selectEvent(event: TimelineEvent) {
        playheadMs = event.startMs.coerceIn(0L, durationMs)
        onSeekRequested?.invoke(playheadMs)
        onEventSelected?.invoke(event)
        announceForAccessibility("${event.type}, ${formatTime(playheadMs)}")
        invalidate()
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (durationMs <= 0 || width <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val barTop = h * 0.2f
        val barBottom = h * 0.8f

        // Draw event spans
        for (ev in events) {
            val left = (ev.startMs.toFloat() / durationMs) * w
            val right = ((ev.endMs.toFloat() / durationMs) * w).coerceAtLeast(left + 2f)
            val paint = when (ev.type) {
                "person" -> paintPerson
                "car" -> paintCar
                "bike" -> paintBike
                "animal" -> paintAnimal
                else -> paintMotion
            }
            canvas.drawRect(left, barTop, right, barBottom, paint)
            // Shape cues keep event classes distinguishable without color.
            val centerY = (barTop + barBottom) / 2f
            when (ev.type) {
                "person" -> canvas.drawCircle(left + 4f, centerY, 4f, paintPlayhead)
                "car" -> canvas.drawRect(left, centerY - 2f, left + 8f, centerY + 2f, paintPlayhead)
                "bike" -> {
                    canvas.drawCircle(left + 2f, centerY, 2f, paintPlayhead)
                    canvas.drawCircle(left + 7f, centerY, 2f, paintPlayhead)
                }
                "animal" -> {
                    animalMarker.reset()
                    animalMarker.moveTo(left + 4f, centerY - 4f)
                    animalMarker.lineTo(left + 8f, centerY)
                    animalMarker.lineTo(left + 4f, centerY + 4f)
                    animalMarker.lineTo(left, centerY)
                    animalMarker.close()
                    canvas.drawPath(animalMarker, paintPlayhead)
                }
                else -> canvas.drawRect(left, barTop, left + 2f, barBottom, paintPlayhead)
            }
        }

        // Draw playhead
        if (playheadMs in 0..durationMs) {
            val x = (playheadMs.toFloat() / durationMs) * w
            canvas.drawRect(x - 1.5f, 0f, x + 1.5f, h, paintPlayhead)
        }
    }
}
