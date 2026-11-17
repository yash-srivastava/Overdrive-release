package com.overdrive.app.ui.view

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** Equal-width action buttons that wrap rather than squeeze their labels. */
internal class ProjectionActionRow(
    context: Context,
    private val preferredCellWidth: Int = 0
) : ViewGroup(context) {
    private val gap = (8 * resources.displayMetrics.density).roundToInt()
    private var cells = emptyList<Cell>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val actions = (0 until childCount).map { getChildAt(it) }
            .filter { it.visibility != View.GONE }
        if (actions.isEmpty()) {
            cells = emptyList()
            setMeasuredDimension(
                resolveSize(paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(paddingTop + paddingBottom, heightMeasureSpec)
            )
            return
        }

        // Read the themed button's preferred width, including its current font
        // scale and translated label, before assigning equally sized columns.
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        actions.forEach { it.measure(unspecified, unspecified) }
        val preferred = (if (preferredCellWidth > 0) preferredCellWidth
            else actions.maxOf { it.measuredWidth }).coerceAtLeast(1)
        val desiredWidth = paddingLeft + paddingRight +
            actions.size * preferred + (actions.size - 1) * gap
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val columns = ((available + gap) / (preferred + gap)).coerceIn(1, actions.size)
        val cellSpace = (available - (columns - 1) * gap).coerceAtLeast(0)
        val measuredCells = ArrayList<Cell>(actions.size)
        var top = paddingTop
        actions.chunked(columns).forEach { row ->
            var height = 0
            row.forEachIndexed { column, action ->
                val left = column * cellSpace / columns + column * gap
                val right = (column + 1) * cellSpace / columns + column * gap
                action.measure(
                    MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY),
                    unspecified
                )
                height = maxOf(height, action.measuredHeight)
                measuredCells += Cell(action, left, top, right, 0)
            }
            for (index in measuredCells.size - row.size until measuredCells.size) {
                val cell = measuredCells[index]
                cell.view.measure(
                    MeasureSpec.makeMeasureSpec(cell.right - cell.left, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                )
                measuredCells[index] = cell.copy(bottom = top + height)
            }
            top += height + gap
        }
        cells = measuredCells
        setMeasuredDimension(
            width,
            resolveSize(top - gap + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val available = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        cells.forEach { cell ->
            val childLeft = paddingLeft + if (rtl) available - cell.right else cell.left
            cell.view.layout(
                childLeft, cell.top,
                childLeft + cell.right - cell.left, cell.bottom
            )
        }
    }

    override fun generateDefaultLayoutParams() =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private data class Cell(
        val view: View,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )
}
