package com.overdrive.app.ui.dashboard

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * FrameLayout that reports its first child's baseline, so a value with an overlaid
 * placeholder can sit in a baseline-aligned row. The first child must be top-aligned.
 */
class DashboardBaselineFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    override fun getBaseline(): Int {
        val child = getChildAt(0) ?: return super.getBaseline()
        val childBaseline = child.baseline
        if (childBaseline < 0) return childBaseline
        val topMargin = (child.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        return paddingTop + topMargin + childBaseline
    }
}
