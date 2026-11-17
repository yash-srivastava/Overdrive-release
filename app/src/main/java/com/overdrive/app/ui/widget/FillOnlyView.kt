package com.overdrive.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View

/**
 * Decorative view (e.g. a glow) that fills its parent but never sizes it: zero unless measured
 * EXACTLY. A plain match_parent View takes the whole AT_MOST height, which a fillViewport scroll
 * hands out when the content is short, and so pushes every card below it to zero height.
 */
class FillOnlyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(exactOrZero(widthMeasureSpec), exactOrZero(heightMeasureSpec))
    }

    private fun exactOrZero(spec: Int): Int =
        if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
}
