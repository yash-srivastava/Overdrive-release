package com.overdrive.app.ui.dashboard

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * Children in a row on one baseline; a child that no longer fits starts a new line below.
 * A baseline-aligned LinearLayout clips a wrapping caption instead (it ignores baseline offsets).
 */
class DashboardBaselineFlow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    // Filled by onMeasure, read by onLayout.
    private var lineOfChild = IntArray(0)
    private val lineAscent = ArrayList<Int>()
    private val lineHeight = ArrayList<Int>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        }
        if (lineOfChild.size < childCount) lineOfChild = IntArray(childCount)
        lineAscent.clear()
        lineHeight.clear()
        var x = 0
        var widest = 0
        var ascent = 0
        var descent = 0
        var plain = 0
        var started = false
        var childState = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            // Measured as if it starts a line, so a caption moves down whole instead of wrapping inline.
            child.measure(
                getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight + lp.marginEnd, lp.width),
                getChildMeasureSpec(
                    heightMeasureSpec,
                    paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin,
                    lp.height,
                ),
            )
            childState = combineMeasuredStates(childState, child.measuredState)
            val inlineWidth = lp.marginStart + child.measuredWidth + lp.marginEnd
            if (started && x + inlineWidth > avail) {
                lineAscent.add(ascent)
                lineHeight.add(max(ascent + descent, plain))
                x = 0
                ascent = 0
                descent = 0
                plain = 0
                started = false
            }
            x += if (started) inlineWidth else child.measuredWidth + lp.marginEnd
            widest = max(widest, x)
            started = true
            lineOfChild[i] = lineAscent.size
            val baseline = child.baseline
            if (baseline >= 0) {
                ascent = max(ascent, lp.topMargin + baseline)
                descent = max(descent, child.measuredHeight - baseline + lp.bottomMargin)
            } else {
                plain = max(plain, lp.topMargin + child.measuredHeight + lp.bottomMargin)
            }
        }
        if (started) {
            lineAscent.add(ascent)
            lineHeight.add(max(ascent + descent, plain))
        }
        val height = lineHeight.sum() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSizeAndState(max(widest + paddingLeft + paddingRight, suggestedMinimumWidth), widthMeasureSpec, childState),
            resolveSizeAndState(
                max(height, suggestedMinimumHeight),
                heightMeasureSpec,
                childState shl MEASURED_HEIGHT_STATE_SHIFT,
            ),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val width = r - l
        var line = -1
        var x = 0
        var top = paddingTop
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            if (lineOfChild[i] != line) {
                if (line >= 0) top += lineHeight[line]
                line = lineOfChild[i]
                x = 0
            } else {
                x += lp.marginStart
            }
            val w = child.measuredWidth
            val baseline = child.baseline
            val childTop = top + if (baseline >= 0) lineAscent[line] - baseline else lp.topMargin
            val left = if (rtl) width - paddingRight - x - w else paddingLeft + x
            child.layout(left, childTop, left + w, childTop + child.measuredHeight)
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
