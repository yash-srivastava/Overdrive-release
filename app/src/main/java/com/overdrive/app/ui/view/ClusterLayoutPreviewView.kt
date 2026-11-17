package com.overdrive.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import com.overdrive.app.R
import kotlin.math.roundToInt

/** Physical pane positions stay fixed in RTL; their app labels follow the locale. */
internal class ClusterLayoutPreviewView(context: Context) : View(context) {
    data class Pane(val x: Int, val y: Int, val width: Int, val height: Int, val label: String)

    private val density = resources.displayMetrics.density
    private val background = MaterialColors.getColor(this,
        com.google.android.material.R.attr.colorSurfaceContainerHighest)
    private val paneColor = MaterialColors.getColor(this,
        com.google.android.material.R.attr.colorSecondaryContainer)
    private val foreground = MaterialColors.getColor(this,
        com.google.android.material.R.attr.colorOnSecondaryContainer)
    private val outline = MaterialColors.getColor(this,
        com.google.android.material.R.attr.colorOutlineVariant)
    private val selectedOutline = MaterialColors.getColor(this,
        androidx.appcompat.R.attr.colorPrimary)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foreground
        textSize = 14 * resources.displayMetrics.scaledDensity
    }
    private var panes = emptyList<Pane>()
    private var layouts = emptyList<StaticLayout?>()
    private var selectedPane = -1
    private var pressedPane = -1
    var onPaneSelected: ((Int) -> Unit)? = null
        set(value) {
            field = value
            isClickable = value != null
            isFocusable = value != null
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        minimumHeight = maxOf((96 * density).roundToInt(),
            (4 * labelPaint.fontSpacing + 48 * density).roundToInt())
    }

    fun setPanes(value: List<Pane>) {
        panes = value.toList()
        contentDescription = resources.getString(R.string.cluster_layouts_preview_description,
            panes.joinToString(" · ") { it.label })
        rebuildLabels(width)
        invalidate()
    }

    fun setSelectedPane(index: Int) {
        selectedPane = index.takeIf { it in panes.indices } ?: -1
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onPaneSelected == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedPane = paneAt(event.x, event.y)
                return pressedPane >= 0
            }
            MotionEvent.ACTION_UP -> {
                if (pressedPane >= 0 && paneAt(event.x, event.y) == pressedPane) {
                    setSelectedPane(pressedPane)
                    performClick()
                }
                pressedPane = -1
            }
            MotionEvent.ACTION_CANCEL -> pressedPane = -1
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        val index = selectedPane.takeIf { it in panes.indices } ?: panes.indices.firstOrNull()
        if (index != null) onPaneSelected?.invoke(index)
        return true
    }

    private fun paneAt(x: Float, y: Float): Int {
        val edge = 8 * density
        val w = width - 2 * edge
        val h = height - 2 * edge
        if (w <= 0 || h <= 0 || x < edge || x > width - edge ||
            y < edge || y > height - edge) return -1
        val panelX = (x - edge) * 10000 / w
        val panelY = (y - edge) * 10000 / h
        return panes.indexOfFirst { panelX >= it.x && panelX < it.x + it.width &&
            panelY >= it.y && panelY < it.y + it.height }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize((440 * density).roundToInt(), widthMeasureSpec)
        val height = maxOf(minimumHeight, (width / (8f / 3f)).roundToInt())
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildLabels(w)
    }

    private fun rebuildLabels(width: Int) {
        val panelWidth = (width - 16 * density).coerceAtLeast(0f)
        layouts = panes.map { pane ->
            val labelWidth = (panelWidth * pane.width.coerceAtLeast(0) / 10000 -
                24 * density).roundToInt()
            if (labelWidth <= 0) null else StaticLayout.Builder
                .obtain(pane.label, 0, pane.label.length, labelPaint, labelWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val edge = 8 * density
        val panel = RectF(edge, edge, width - edge, height - edge)
        if (panel.width() <= 0 || panel.height() <= 0) return
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRoundRect(panel, 8 * density, 8 * density, paint)
        panes.forEachIndexed { index, pane ->
            val bounds = RectF(
                panel.left + panel.width() * pane.x / 10000,
                panel.top + panel.height() * pane.y / 10000,
                panel.left + panel.width() * (pane.x + pane.width) / 10000,
                panel.top + panel.height() * (pane.y + pane.height) / 10000
            ).apply { inset(3 * density, 3 * density) }
            if (bounds.width() <= 0 || bounds.height() <= 0) return@forEachIndexed
            paint.color = paneColor
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(bounds, 6 * density, 6 * density, paint)
            paint.color = if (index == selectedPane) selectedOutline else outline
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (index == selectedPane) 3 * density else density
            canvas.drawRoundRect(bounds, 6 * density, 6 * density, paint)
            val label = layouts.getOrNull(index) ?: return@forEachIndexed
            canvas.save()
            canvas.clipRect(bounds)
            canvas.translate(bounds.centerX() - label.width / 2f,
                bounds.centerY() - label.height / 2f)
            label.draw(canvas)
            canvas.restore()
        }
    }
}
