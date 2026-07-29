package com.overdrive.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.widget.NestedScrollView
import com.overdrive.app.ui.model.NavigationRailSwipePolicy
import kotlin.math.abs

/**
 * Vertical rail scroller that intercepts only deliberate horizontal swipes.
 *
 * Vertical gestures continue through NestedScrollView unchanged. Once a gesture is
 * clearly horizontal, child destination rows receive CANCEL rather than a click.
 */
class SwipeExpandableRailScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minimumSwipeDistance = 48f * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var interceptingHorizontalSwipe = false

    var onRailSwipe: ((NavigationRailSwipePolicy.Action) -> Unit)? = null

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                interceptingHorizontalSwipe = false
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - downX
                val deltaY = event.y - downY
                if (abs(deltaX) >= touchSlop &&
                    NavigationRailSwipePolicy.resolve(
                        deltaX,
                        deltaY,
                        touchSlop
                    ) != NavigationRailSwipePolicy.Action.NONE
                ) {
                    interceptingHorizontalSwipe = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> interceptingHorizontalSwipe = false
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interceptingHorizontalSwipe) {
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val action = NavigationRailSwipePolicy.resolve(
                    event.x - downX,
                    event.y - downY,
                    minimumSwipeDistance
                )
                interceptingHorizontalSwipe = false
                if (action != NavigationRailSwipePolicy.Action.NONE) {
                    onRailSwipe?.invoke(action)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                interceptingHorizontalSwipe = false
                return true
            }
        }
        return true
    }
}
