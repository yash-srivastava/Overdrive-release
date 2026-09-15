package com.overdrive.app.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.overdrive.app.R
import kotlin.math.sqrt

/**
 * Custom view for drawing ROI (Region of Interest) polygons on camera preview.
 * 
 * Supports:
 * - Touch to add points (3-8 points)
 * - Drag existing points to adjust
 * - Visual feedback with polygon fill and outline
 * - Normalized coordinates (0.0-1.0) for resolution independence
 */
class RoiDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Points in normalized coordinates (0.0 to 1.0)
    private val normalizedPoints = mutableListOf<PointF>()
    
    // Points in screen coordinates (for drawing)
    private val screenPoints = mutableListOf<PointF>()
    
    // Drawing paints
    private val fillPaint = Paint().apply {
        color = Color.argb(60, 0, 212, 170) // Semi-transparent accent
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val strokePaint = Paint().apply {
        color = Color.rgb(0, 212, 170) // Accent color
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    private val pointPaint = Paint().apply {
        color = Color.rgb(0, 212, 170)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val pointStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    
    // Interaction state
    private var draggedPointIndex = -1
    private val pointRadius = 24f
    private val touchSlop = 48f // Touch detection radius
    
    // Constraints
    val minPoints = 3
    val maxPoints = 8
    
    // Listener
    var onRoiChangedListener: ((List<PointF>) -> Unit)? = null
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (screenPoints.isEmpty()) {
            // Draw hint text
            canvas.drawText(
                context.getString(R.string.roi_tap_to_add_points, minPoints, maxPoints),
                width / 2f,
                height / 2f,
                textPaint
            )
            return
        }
        
        // Draw polygon fill if we have at least 3 points
        if (screenPoints.size >= minPoints) {
            val path = Path()
            path.moveTo(screenPoints[0].x, screenPoints[0].y)
            for (i in 1 until screenPoints.size) {
                path.lineTo(screenPoints[i].x, screenPoints[i].y)
            }
            path.close()
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        } else {
            // Draw lines between points
            for (i in 0 until screenPoints.size - 1) {
                canvas.drawLine(
                    screenPoints[i].x, screenPoints[i].y,
                    screenPoints[i + 1].x, screenPoints[i + 1].y,
                    strokePaint
                )
            }
        }
        
        // Draw points
        screenPoints.forEachIndexed { index, point ->
            // Outer circle (white stroke)
            canvas.drawCircle(point.x, point.y, pointRadius, pointStrokePaint)
            // Inner circle (accent fill)
            canvas.drawCircle(point.x, point.y, pointRadius - 2, pointPaint)
            // Point number
            canvas.drawText(
                "${index + 1}",
                point.x,
                point.y + textPaint.textSize / 3,
                textPaint.apply { textSize = 24f }
            )
        }
        
        // Draw point count indicator
        val countText = "${screenPoints.size}/${maxPoints} points"
        textPaint.textSize = 28f
        canvas.drawText(countText, width / 2f, height - 40f, textPaint)
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Check if touching an existing point
                draggedPointIndex = findPointAt(event.x, event.y)
                
                if (draggedPointIndex == -1 && screenPoints.size < maxPoints) {
                    // Add new point
                    addPoint(event.x, event.y)
                    invalidate()
                    return true
                }
                return draggedPointIndex != -1
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (draggedPointIndex != -1) {
                    // Move the dragged point
                    movePoint(draggedPointIndex, event.x, event.y)
                    invalidate()
                    return true
                }
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggedPointIndex != -1) {
                    draggedPointIndex = -1
                    notifyRoiChanged()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
    
    private fun findPointAt(x: Float, y: Float): Int {
        screenPoints.forEachIndexed { index, point ->
            val distance = sqrt(
                (x - point.x) * (x - point.x) + (y - point.y) * (y - point.y)
            )
            if (distance <= touchSlop) {
                return index
            }
        }
        return -1
    }
    
    private fun addPoint(screenX: Float, screenY: Float) {
        // Convert to normalized coordinates
        val normalizedX = (screenX / width).coerceIn(0f, 1f)
        val normalizedY = (screenY / height).coerceIn(0f, 1f)
        
        normalizedPoints.add(PointF(normalizedX, normalizedY))
        screenPoints.add(PointF(screenX, screenY))
        
        notifyRoiChanged()
    }
    
    private fun movePoint(index: Int, screenX: Float, screenY: Float) {
        val clampedX = screenX.coerceIn(0f, width.toFloat())
        val clampedY = screenY.coerceIn(0f, height.toFloat())
        
        // Update screen coordinates
        screenPoints[index].set(clampedX, clampedY)
        
        // Update normalized coordinates
        normalizedPoints[index].set(
            clampedX / width,
            clampedY / height
        )
    }
    
    private fun notifyRoiChanged() {
        onRoiChangedListener?.invoke(normalizedPoints.toList())
    }
    
    /**
     * Set ROI from normalized coordinates.
     */
    fun setRoi(points: List<PointF>) {
        normalizedPoints.clear()
        screenPoints.clear()
        
        points.take(maxPoints).forEach { point ->
            normalizedPoints.add(PointF(point.x, point.y))
            // Screen points will be calculated in onSizeChanged
            screenPoints.add(PointF(point.x * width, point.y * height))
        }
        
        invalidate()
    }
    
    /**
     * Get ROI as normalized coordinates.
     */
    fun getRoi(): List<PointF> = normalizedPoints.toList()
    
    /**
     * Get ROI as flat float array [x1, y1, x2, y2, ...].
     */
    fun getRoiAsFloatArray(): FloatArray {
        val arr = FloatArray(normalizedPoints.size * 2)
        normalizedPoints.forEachIndexed { index, point ->
            arr[index * 2] = point.x
            arr[index * 2 + 1] = point.y
        }
        return arr
    }
    
    /**
     * Clear all points.
     */
    fun clearRoi() {
        normalizedPoints.clear()
        screenPoints.clear()
        invalidate()
        notifyRoiChanged()
    }
    
    /**
     * Remove the last added point.
     */
    fun undoLastPoint() {
        if (normalizedPoints.isNotEmpty()) {
            normalizedPoints.removeAt(normalizedPoints.lastIndex)
            screenPoints.removeAt(screenPoints.lastIndex)
            invalidate()
            notifyRoiChanged()
        }
    }
    
    /**
     * Check if ROI is valid (has minimum required points).
     */
    fun isValidRoi(): Boolean = normalizedPoints.size >= minPoints
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        
        // Recalculate screen points from normalized coordinates
        screenPoints.clear()
        normalizedPoints.forEach { normalized ->
            screenPoints.add(PointF(normalized.x * w, normalized.y * h))
        }
    }
}
