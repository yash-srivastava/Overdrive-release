package com.overdrive.app.overlay

/**
 * Stored [chipOpacity] is whole-pill visibility percent: 10 = faint, 100 = fully prominent.
 * Legacy installs may still have 20–100 from the inverted slider era; values clamp safely.
 */
object StatusOverlayChipOpacity {
    const val MIN = 10
    const val MAX = 100
    const val DEFAULT = 100
    const val STEP = 5

    @JvmStatic
    fun clampStored(value: Int): Int = value.coerceIn(MIN, MAX)

    @JvmStatic
    fun snapSliderValue(opacity: Int): Float {
        val clamped = clampStored(opacity)
        return (MIN + (clamped - MIN) / STEP * STEP).toFloat()
    }
}
