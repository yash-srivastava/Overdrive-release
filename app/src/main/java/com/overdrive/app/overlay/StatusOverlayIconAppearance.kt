package com.overdrive.app.overlay

/** Stored [iconOpacity] / [iconSizeDp] keys in the statusOverlay config section. */
object StatusOverlayIconAppearance {
    const val OPACITY_MIN = 20
    const val OPACITY_MAX = 100
    const val SIZE_MIN_DP = 12
    const val SIZE_MAX_DP = 32

    @JvmStatic
    fun clampStoredOpacity(value: Int): Int = value.coerceIn(OPACITY_MIN, OPACITY_MAX)

    @JvmStatic
    fun clampStoredSizeDp(value: Int): Int = value.coerceIn(SIZE_MIN_DP, SIZE_MAX_DP)

    /** Slider shows transparency (100 = invisible icon); stored value is icon opacity percent. */
    @JvmStatic
    fun storedOpacityFromSliderTransparency(transparency: Int): Int =
        clampStoredOpacity(100 - transparency)

    @JvmStatic
    fun snapOpacitySliderValue(storedOpacity: Int): Float {
        val clamped = clampStoredOpacity(storedOpacity)
        return ((100 - clamped) / 5 * 5).toFloat()
    }

    @JvmStatic
    fun snapSizeSliderValue(sizeDp: Int): Float {
        val clamped = clampStoredSizeDp(sizeDp)
        return (SIZE_MIN_DP + (clamped - SIZE_MIN_DP) / 2 * 2).toFloat()
    }
}
