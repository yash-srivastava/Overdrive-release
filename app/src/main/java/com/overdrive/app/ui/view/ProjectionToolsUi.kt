package com.overdrive.app.ui.view

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.overdrive.app.R
import kotlin.math.roundToInt

/** Shared spacing, readable controls and window bounds for Projection tool dialogs. */
internal object ProjectionToolsUi {
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    fun body(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val inset = resources.getDimensionPixelSize(R.dimen.page_padding_horizontal)
        setPadding(inset, dp(context, 4), inset, dp(context, 12))
    }

    fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val inset = resources.getDimensionPixelSize(R.dimen.card_padding_standard)
        setPadding(inset, inset, inset, inset)
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        background = GradientDrawable().apply {
            cornerRadius = resources.getDimension(R.dimen.card_radius_standard)
            setColor(surface)
            setStroke(dp(context, 1), outline)
        }
    }

    fun text(context: Context, text: CharSequence, title: Boolean = false) = TextView(context).apply {
        this.text = text
        setTextAppearance(if (title) R.style.TextAppearance_Overdrive_TitleMedium
            else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(MaterialColors.getColor(this, if (title)
            com.google.android.material.R.attr.colorOnSurface
            else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    fun button(context: Context, label: Int, primary: Boolean = false, icon: Int = 0) =
        MaterialButton(context, null, if (primary)
            com.google.android.material.R.attr.materialButtonStyle
            else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(label)
            isAllCaps = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            tooltipText = context.getString(label)
            minimumHeight = resources.getDimensionPixelSize(R.dimen.dashboard_modern_touch_target)
            insetTop = 0
            insetBottom = 0
            cornerRadius = resources.getDimensionPixelSize(R.dimen.card_radius_accent)
            if (icon != 0) {
                setIconResource(icon)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            }
        }

    fun addSection(body: LinearLayout, view: View, gap: Int = 12) {
        body.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (body.childCount > 0) topMargin = dp(body.context, gap)
        })
    }

    fun scroll(body: LinearLayout): ScrollView = BoundedScrollView(body.context).apply {
        isFillViewport = false
        clipToPadding = false
        addView(body, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Call after show: title/actions stay outside the scroll area and above the IME. */
    fun fit(dialog: Dialog) {
        val window = dialog.window ?: return
        val bounds = visibleBounds(dialog.context)
        val width = minOf(dp(dialog.context, 720),
            (bounds.width() - dp(dialog.context, 32)).coerceAtLeast(1))
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun visibleBounds(context: Context): Rect {
        var current: Context? = context
        while (current is ContextWrapper && current !is Activity) {
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        val bounds = Rect()
        (current as? Activity)?.window?.decorView?.getWindowVisibleDisplayFrame(bounds)
        if (bounds.isEmpty) {
            val metrics = context.resources.displayMetrics
            bounds.set(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        return bounds
    }

    private class BoundedScrollView(context: Context) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = visibleBounds(context).height()
            val cap = minOf(dp(context, 420), (available * 0.60f).roundToInt()).coerceAtLeast(1)
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val height = if (mode == MeasureSpec.UNSPECIFIED) cap else minOf(size, cap)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        }
    }
}
