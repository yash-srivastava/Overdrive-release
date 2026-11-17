package com.overdrive.app.ui.widget

import android.content.Context
import android.text.Layout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import com.google.android.material.textview.MaterialTextView
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Shrinks its text (down to 12sp) only when one unbreakable segment, such as "Background" at a
 * large font scale, is wider than the view. A word still too wide is hyphenated, not cut anywhere.
 */
class WordFitTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : MaterialTextView(context, attrs, defStyleAttr) {

    private var fullSizePx = textSize
    private var fitting = false
    private val measurePaint = TextPaint()
    private var overflowAtFloor = false
    private val defaultHyphenation = hyphenationFrequency

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        if (!fitting) fullSizePx = textSize
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val avail = MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight
            overflowAtFloor = false
            val size = if (avail > 0) fittedSize(avail.toFloat()) else fullSizePx
            if (abs(size - textSize) >= 0.5f) {
                fitting = true
                super.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                fitting = false
            }
            // Only a word that still cannot fit at the floor (e.g. "Hintergrunddienste") is hyphenated.
            val hyphens = if (overflowAtFloor) Layout.HYPHENATION_FREQUENCY_FULL else defaultHyphenation
            if (hyphenationFrequency != hyphens) hyphenationFrequency = hyphens
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        // A same-height text change only invalidates; refit if the new words need another size.
        if (!isLaidOut) return
        val avail = width - totalPaddingLeft - totalPaddingRight
        if (avail <= 0) return
        val size = fittedSize(avail.toFloat())
        val hyphens = if (overflowAtFloor) Layout.HYPHENATION_FREQUENCY_FULL else defaultHyphenation
        if (abs(size - textSize) >= 0.5f || hyphens != hyphenationFrequency) requestLayout()
    }

    private fun fittedSize(avail: Float): Float {
        overflowAtFloor = false
        val text = text?.toString().orEmpty()
        if (text.isEmpty()) return fullSizePx
        measurePaint.set(paint)
        val segments = ArrayList<IntArray>()
        val breaks = BreakIterator.getLineInstance(textLocale)
        breaks.setText(text)
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            // A segment carries its trailing space; that space may hang past the edge.
            var stop = end
            while (stop > start && text[stop - 1].isWhitespace()) stop--
            if (stop > start) segments.add(intArrayOf(start, stop))
            start = end
            end = breaks.next()
        }
        fun widestAt(size: Float): Float {
            measurePaint.textSize = size
            return segments.maxOfOrNull { measurePaint.measureText(text, it[0], it[1]) } ?: 0f
        }
        val widest = widestAt(fullSizePx)
        if (widest <= avail) return fullSizePx
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        var size = max(minPx, floor(fullSizePx * avail / widest))
        // Text is drawn at whole pixels, so check the real paint and step down until it fits.
        while (size > minPx && widestAt(size) > avail) size = max(minPx, size - 1f)
        overflowAtFloor = widestAt(size) > avail
        return size
    }
}
