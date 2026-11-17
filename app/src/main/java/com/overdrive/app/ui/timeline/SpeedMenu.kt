package com.overdrive.app.ui.timeline

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.PopupWindow
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.overdrive.app.R

/**
 * Playback speed + "Skip quiet driving" + "Continue to next event": a popover
 * anchored to the speed button (landscape) or a bottom sheet (portrait).
 */
class SpeedMenu(
    private val context: Context,
    private val reducedMotion: () -> Boolean,
    private val onDismiss: () -> Unit,
    private val onChange: (State) -> Unit,
) {
    data class State(val speed: Int, val skipQuiet: Boolean, val continueNext: Boolean)

    private var popup: PopupWindow? = null
    private var sheet: BottomSheetDialog? = null
    private var state = State(1, false, true)

    val isShowing: Boolean get() = popup?.isShowing == true || sheet?.isShowing == true

    fun showPopover(anchor: View, current: State) {
        dismiss()
        state = current
        val content = LayoutInflater.from(context).inflate(R.layout.popup_timeline_speed, null, false)
        bind(content)
        val width = dp(POPOVER_WIDTH_DP).coerceAtMost(anchor.rootView.width - dp(24)).coerceAtLeast(dp(240))
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = content.measuredWidth
        val h = content.measuredHeight
        val root = anchor.rootView
        val anchorLoc = IntArray(2)
        val rootLoc = IntArray(2)
        anchor.getLocationInWindow(anchorLoc)
        root.getLocationInWindow(rootLoc)
        val margin = dp(12)
        val gap = dp(8)
        val maxX = (root.width - w - margin).coerceAtLeast(margin)
        val x = (anchorLoc[0] - rootLoc[0] + anchor.width / 2 - w / 2).coerceIn(margin, maxX)
        var y = anchorLoc[1] - rootLoc[1] - h - gap
        if (y < margin) y = anchorLoc[1] - rootLoc[1] + anchor.height + gap
        val pw = PopupWindow(content, w, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            animationStyle = 0
            isOutsideTouchable = true
            setOnDismissListener {
                if (popup === this) popup = null
                onDismiss()
            }
        }
        popup = pw
        try {
            pw.showAtLocation(root, Gravity.TOP or Gravity.LEFT, x, y)
        } catch (t: Throwable) {
            popup = null
            return
        }
        enter(content, h)
    }

    fun showSheet(current: State) {
        dismiss()
        state = current
        val dialog = BottomSheetDialog(context, R.style.Theme_Overdrive_M3_BottomSheet)
        val content = LayoutInflater.from(context).inflate(R.layout.sheet_timeline_speed, null, false)
        com.overdrive.app.ui.util.SheetInsets.padForNavigationBar(content)
        bind(content)
        dialog.setContentView(content)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener {
            if (sheet === dialog) sheet = null
            onDismiss()
        }
        sheet = dialog
        dialog.show()
    }

    fun dismiss() {
        popup?.let { runCatching { it.dismiss() } }
        popup = null
        sheet?.let { runCatching { it.dismiss() } }
        sheet = null
    }

    private fun bind(root: View) {
        val ids = intArrayOf(R.id.tlSpeed1, R.id.tlSpeed2, R.id.tlSpeed4, R.id.tlSpeed8)
        val speeds = intArrayOf(1, 2, 4, 8)
        val group = root.findViewById<MaterialButtonToggleGroup>(R.id.tlSpeedGroup)
        for (i in ids.indices) {
            val b = root.findViewById<MaterialButton>(ids[i])
            b.text = speedLabel(speeds[i])
            b.contentDescription = context.getString(R.string.timeline_cd_speed, speedLabel(speeds[i]))
        }
        group.check(ids[speeds.indexOf(state.speed).coerceAtLeast(0)])
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val speed = speeds[ids.indexOf(checkedId).coerceAtLeast(0)]
            if (speed != state.speed) update(state.copy(speed = speed))
        }
        val skip = root.findViewById<MaterialSwitch>(R.id.tlSkipQuietSwitch)
        skip.isChecked = state.skipQuiet
        skip.setOnCheckedChangeListener { _, on -> if (on != state.skipQuiet) update(state.copy(skipQuiet = on)) }
        root.findViewById<View>(R.id.tlSkipQuietRow).setOnClickListener { skip.toggle() }
        val cont = root.findViewById<MaterialSwitch>(R.id.tlContinueSwitch)
        cont.isChecked = state.continueNext
        cont.setOnCheckedChangeListener { _, on -> if (on != state.continueNext) update(state.copy(continueNext = on)) }
        root.findViewById<View>(R.id.tlContinueRow).setOnClickListener { cont.toggle() }
    }

    private fun update(next: State) {
        state = next
        onChange(next)
    }

    // Ease-out scale from the anchor edge; reduced motion fades only.
    private fun enter(content: View, height: Int) {
        content.alpha = 0f
        if (reducedMotion()) {
            content.animate().alpha(1f).setDuration(REDUCED_MS).start()
            return
        }
        content.pivotX = content.measuredWidth / 2f
        content.pivotY = height.toFloat()
        content.scaleX = ENTER_SCALE
        content.scaleY = ENTER_SCALE
        content.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(ENTER_MS)
            .setInterpolator(PathInterpolator(0.23f, 1f, 0.32f, 1f))
            .start()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val ENTER_MS = 200L
        private const val REDUCED_MS = 150L
        private const val ENTER_SCALE = 0.96f
        private const val POPOVER_WIDTH_DP = 344

        /** "2×" kept left-to-right inside RTL text. */
        fun speedLabel(speed: Int): String =
            BidiFormatter.getInstance().unicodeWrap("$speed×", TextDirectionHeuristics.LTR)
    }
}
