package com.overdrive.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * One line of children, vertically centred. The first child never shrinks; when the line is too
 * narrow the middle children collapse whole (last first), then the last child gets what is left.
 */
class BadgeLine @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private var collapsed = BooleanArray(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val avail = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        if (collapsed.size < childCount) collapsed = BooleanArray(childCount)
        val shown = ArrayList<Int>(childCount)
        for (i in 0 until childCount) {
            collapsed[i] = false
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            shown.add(i)
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
        }
        var used = shown.sumOf { span(getChildAt(it)) }
        if (used > avail && shown.size > 2) {
            for (k in shown.size - 2 downTo 1) {
                if (used <= avail) break
                collapsed[shown[k]] = true
                used -= span(getChildAt(shown[k]))
            }
        }
        if (used > avail && shown.size > 1) {
            val last = getChildAt(shown.last())
            val lp = last.layoutParams as MarginLayoutParams
            val room = (avail - (used - span(last)) - lp.marginStart - lp.marginEnd).coerceAtLeast(0)
            last.measure(
                MeasureSpec.makeMeasureSpec(room, MeasureSpec.EXACTLY),
                getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin, lp.height),
            )
            if (last.measuredWidth == 0) collapsed[shown.last()] = true
            used = shown.filter { !collapsed[it] }.sumOf { span(getChildAt(it)) }
        }
        var height = 0
        var state = 0
        for (i in shown) {
            val child = getChildAt(i)
            state = combineMeasuredStates(state, child.measuredState)
            if (collapsed[i]) continue
            val lp = child.layoutParams as MarginLayoutParams
            height = max(height, child.measuredHeight + lp.topMargin + lp.bottomMargin)
        }
        setMeasuredDimension(
            resolveSizeAndState(max(used + paddingLeft + paddingRight, suggestedMinimumWidth), widthMeasureSpec, state),
            resolveSizeAndState(
                max(height + paddingTop + paddingBottom, suggestedMinimumHeight),
                heightMeasureSpec,
                state shl MEASURED_HEIGHT_STATE_SHIFT,
            ),
        )
    }

    private fun span(child: View): Int {
        val lp = child.layoutParams as MarginLayoutParams
        return lp.marginStart + child.measuredWidth + lp.marginEnd
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val width = r - l
        val innerTop = paddingTop
        val innerHeight = b - t - paddingTop - paddingBottom
        var x = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (collapsed[i]) {
                child.layout(0, 0, 0, 0)
                continue
            }
            val lp = child.layoutParams as MarginLayoutParams
            x += lp.marginStart
            val w = child.measuredWidth
            val h = child.measuredHeight
            val top = innerTop + lp.topMargin + (innerHeight - lp.topMargin - lp.bottomMargin - h) / 2
            val left = if (rtl) width - paddingRight - x - w else paddingLeft + x
            child.layout(left, top, left + w, top + h)
            x += w + lp.marginEnd
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        if (p is MarginLayoutParams) MarginLayoutParams(p) else MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    override fun shouldDelayChildPressedState(): Boolean = false
}
