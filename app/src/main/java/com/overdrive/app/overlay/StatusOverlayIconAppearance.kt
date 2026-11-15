package com.overdrive.app.overlay

/** Stored [iconOpacity] / [iconSizeDp] keys in the statusOverlay config section. */
object StatusOverlayIconAppearance {
    const val OPACITY_MIN = 20
    const val OPACITY_MAX = 100
    const val OPACITY_DEFAULT = 100
    const val OPACITY_STEP = 5
    const val SIZE_MIN_DP = 12
    const val SIZE_MAX_DP = 32

    @JvmStatic
    fun clampStoredOpacity(value: Int): Int = value.coerceIn(OPACITY_MIN, OPACITY_MAX)

    @JvmStatic
    fun clampStoredSizeDp(value: Int): Int = value.coerceIn(SIZE_MIN_DP, SIZE_MAX_DP)

    /** Slider position matches stored icon visibility percent (higher = more visible). */
    @JvmStatic
    fun snapOpacitySliderValue(storedOpacity: Int): Float {
        val clamped = clampStoredOpacity(storedOpacity)
        return (OPACITY_MIN + (clamped - OPACITY_MIN) / OPACITY_STEP * OPACITY_STEP).toFloat()
    }

    @JvmStatic
    fun snapSizeSliderValue(sizeDp: Int): Float {
        val clamped = clampStoredSizeDp(sizeDp)
        return (SIZE_MIN_DP + (clamped - SIZE_MIN_DP) / 2 * 2).toFloat()
    }
}
