package com.overdrive.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.textview.MaterialTextView
import kotlin.math.ceil

/** One-line badge that collapses to zero width instead of showing a truncated "…" label. */
class FitOrHideTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : MaterialTextView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) return
        val label = text ?: return
        val needed = ceil(paint.measureText(label, 0, label.length)).toInt() +
            totalPaddingLeft + totalPaddingRight
        if (measuredWidth < needed) setMeasuredDimension(0, measuredHeight)
    }
}
