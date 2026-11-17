package com.overdrive.app.launcher.dilink5.cluster

import kotlin.math.roundToInt

/** Pure hosted-overlay geometry and inverse content-buffer scaling. */
data class HostedProjectionGeometry(
    val panelWidth: Int,
    val panelHeight: Int,
    val densityDpi: Int,
    val overlay: Bounds,
    val bufferWidth: Int,
    val bufferHeight: Int
) {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun width(): Int = right - left
        fun height(): Int = bottom - top
    }

    companion object {
        private const val MAX_DIMENSION = 8192
        private const val MAX_PIXELS = 33_554_432L

        @JvmStatic
        fun calculate(
            panelWidth: Int,
            panelHeight: Int,
            densityDpi: Int,
            config: DiLink5ProjectionConfig
        ): HostedProjectionGeometry? {
            if (panelWidth !in 2..MAX_DIMENSION || panelHeight !in 2..MAX_DIMENSION ||
                densityDpi !in 1..10_000 || panelWidth.toLong() * panelHeight > MAX_PIXELS) {
                return null
            }
            val width = (panelWidth * config.widthPercent / 100.0).roundToInt()
                .coerceIn(1, panelWidth)
            val height = (panelHeight * config.heightPercent / 100.0).roundToInt()
                .coerceIn(1, panelHeight)
            val left = ((panelWidth - width) * config.xOffsetPercent / 100.0)
                .roundToInt().coerceIn(0, panelWidth - width)
            val top = ((panelHeight - height) * config.yOffsetPercent / 100.0)
                .roundToInt().coerceIn(0, panelHeight - height)
            val bufferWidth = (width * 100.0 / config.contentScalePercent).roundToInt()
            val bufferHeight = (height * 100.0 / config.contentScalePercent).roundToInt()
            if (bufferWidth !in 1..MAX_DIMENSION || bufferHeight !in 1..MAX_DIMENSION ||
                bufferWidth.toLong() * bufferHeight > MAX_PIXELS) {
                return null
            }
            return HostedProjectionGeometry(
                panelWidth,
                panelHeight,
                densityDpi,
                Bounds(left, top, left + width, top + height),
                bufferWidth,
                bufferHeight
            )
        }
    }
}
