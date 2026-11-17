package com.overdrive.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.core.widget.NestedScrollView
import com.overdrive.app.ui.model.NavigationRailSwipePolicy

/**
 * Vertical rail scroller that hands deliberate horizontal drags to [railDragListener].
 *
 * Vertical gestures scroll as usual. Once a drag arms, child rows receive CANCEL, so a drag never
 * clicks a row. Deltas and velocities are in screen space (the rail can move under the finger)
 * and signed towards "expanded", which is mirrored in RTL.
 */
class SwipeExpandableRailScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    interface RailDragListener {
        fun onRailDragStart()

        /** [delta] px since the drag armed, positive towards expanded. */
        fun onRailDrag(delta: Float)

        /** [velocity] px/s at release, positive towards expanded; 0 when the gesture was cancelled. */
        fun onRailDragEnd(velocity: Float)
    }

    var railDragListener: RailDragListener? = null

    private val armDistance = ARM_DISTANCE_DP * resources.displayMetrics.density
    private val maxVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private var velocityTracker: VelocityTracker? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragOriginRawX = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginTracking(event)
            MotionEvent.ACTION_MOVE -> {
                track(event)
                if (!dragging && tryArm(event)) return true
            }
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) resetTracking()
        }
        return dragging || super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) {
            // Touches that no row consumed (headers, gaps) arrive here directly; they can arm too.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> beginTracking(event)
                MotionEvent.ACTION_MOVE -> {
                    track(event)
                    if (tryArm(event)) return true
                }
                MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            }
            val handled = super.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                resetTracking()
            }
            return handled
        }

        track(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0) {
                    railDragListener?.onRailDrag(directed(rawX(event, index) - dragOriginRawX))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP -> finishDrag(releaseVelocity())
            MotionEvent.ACTION_CANCEL -> finishDrag(0f)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        dragging = false
        resetTracking()
        super.onDetachedFromWindow()
    }

    private fun tryArm(event: MotionEvent): Boolean {
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return false
        val deltaX = directed(rawX(event, index) - downRawX)
        val deltaY = rawY(event, index) - downRawY
        if (NavigationRailSwipePolicy.resolve(deltaX, deltaY, armDistance) ==
            NavigationRailSwipePolicy.Action.NONE
        ) {
            return false
        }
        dragging = true
        // Tracking starts here, so crossing the arm distance never jumps the rail.
        dragOriginRawX = rawX(event, index)
        parent?.requestDisallowInterceptTouchEvent(true)
        // Unwind the nested scroll NestedScrollView began on DOWN; it sees no more events.
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        try {
            super.onTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
        railDragListener?.onRailDragStart()
        return true
    }

    private fun finishDrag(velocity: Float) {
        dragging = false
        resetTracking()
        parent?.requestDisallowInterceptTouchEvent(false)
        railDragListener?.onRailDragEnd(velocity)
    }

    private fun beginTracking(event: MotionEvent) {
        resetTracking()
        dragging = false
        activePointerId = event.getPointerId(0)
        downRawX = event.rawX
        downRawY = event.rawY
        track(event)
    }

    /** Keeps the drag continuous when the tracked finger lifts and another stays down. */
    private fun onPointerUp(event: MotionEvent) {
        val index = event.actionIndex
        if (event.getPointerId(index) != activePointerId) return
        val next = if (index == 0) 1 else 0
        if (next >= event.pointerCount) return
        val shift = rawX(event, next) - rawX(event, index)
        downRawX += shift
        dragOriginRawX += shift
        downRawY += rawY(event, next) - rawY(event, index)
        activePointerId = event.getPointerId(next)
    }

    private fun track(event: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        val screen = MotionEvent.obtain(event)
        screen.offsetLocation(event.rawX - event.x, event.rawY - event.y)
        tracker.addMovement(screen)
        screen.recycle()
    }

    private fun releaseVelocity(): Float {
        val tracker = velocityTracker ?: return 0f
        tracker.computeCurrentVelocity(1000, maxVelocity)
        return directed(tracker.getXVelocity(activePointerId))
    }

    private fun resetTracking() {
        velocityTracker?.recycle()
        velocityTracker = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun rawX(event: MotionEvent, index: Int): Float =
        event.getX(index) + (event.rawX - event.x)

    private fun rawY(event: MotionEvent, index: Int): Float =
        event.getY(index) + (event.rawY - event.y)

    private fun directed(deltaX: Float): Float =
        if (layoutDirection == LAYOUT_DIRECTION_RTL) -deltaX else deltaX

    private companion object {
        const val ARM_DISTANCE_DP = 10f
    }
}

/**
 * Rail row background drawn within the rail's visible width, so plates keep their rounded end
 * while the rail slides under a clip. A [visibleWidth] at least as wide as the bounds draws as-is.
 */
class RailPlateDrawable(
    drawable: Drawable,
    private val visibleWidth: () -> Int,
    private val anchorRight: () -> Boolean,
) : DrawableWrapper(null) {

    private val target = Rect()

    init {
        setDrawable(drawable)
    }

    // The row pads itself; the plate's insets must not become view padding.
    override fun getPadding(padding: Rect): Boolean {
        padding.setEmpty()
        return false
    }

    override fun draw(canvas: Canvas) {
        val inner = getDrawable() ?: return
        val outer = bounds
        val limit = visibleWidth()
        when {
            limit >= outer.width() -> target.set(outer)
            anchorRight() -> target.set(outer.right - limit, outer.top, outer.right, outer.bottom)
            else -> target.set(outer.left, outer.top, outer.left + limit, outer.bottom)
        }
        if (inner.bounds != target) inner.bounds = target
        inner.draw(canvas)
    }
}
