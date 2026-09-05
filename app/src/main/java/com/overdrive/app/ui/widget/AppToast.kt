package com.overdrive.app.ui.widget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.overdrive.app.R

/** In-page toast stack matching the WebView `#toastContainer` / `.toast` chips. */
class AppToast(private val host: View) {

    enum class Kind { INFO, SUCCESS, WARNING, ERROR }

    private var resolved: ViewGroup? = null
    private val hideRunnables = ArrayList<Runnable>()

    /**
     * Resolved per call, not once in the constructor: a fragment's view is not attached to its
     * container yet in onViewCreated, so an embedded fragment (the recordings library inside
     * Recordings) cannot see the host page's stack until later.
     */
    private fun stack(): ViewGroup? {
        resolved?.let { if (it.isAttachedToWindow) return it }
        val found = host.findViewById<ViewGroup>(R.id.appToastStack)
            ?: host.rootView?.findViewById(R.id.appToastStack)
        resolved = found
        return found
    }

    fun show(message: String, kind: Kind = Kind.INFO, durationMs: Long = DURATION_MS) {
        val stack = stack() ?: return
        val chip = LayoutInflater.from(stack.context)
            .inflate(R.layout.item_app_toast, stack, false)
        chip.findViewById<TextView>(R.id.appToastText).text = message
        chip.findViewById<View>(R.id.appToastDot).setBackgroundResource(dotFor(kind))
        if (stack.childCount > 0) {
            val gap = (8f * stack.resources.displayMetrics.density).toInt()
            (chip.layoutParams as LinearLayout.LayoutParams).topMargin = gap
        }
        stack.addView(chip)
        stack.bringToFront()
        chip.alpha = 0f
        chip.translationY = 10f * chip.resources.displayMetrics.density
        chip.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300L)
            .start()
        val hide = Runnable { dismiss(chip) }
        hideRunnables.add(hide)
        chip.setTag(R.id.appToastStack, hide)
        stack.postDelayed(hide, durationMs)
    }

    fun cancel() {
        val stack = resolved ?: return
        hideRunnables.forEach { stack.removeCallbacks(it) }
        hideRunnables.clear()
        for (i in 0 until stack.childCount) {
            stack.getChildAt(i).animate().cancel()
        }
        stack.removeAllViews()
        resolved = null
    }

    private fun dismiss(chip: View) {
        val hide = chip.getTag(R.id.appToastStack) as? Runnable
        if (hide != null) hideRunnables.remove(hide)
        chip.animate()
            .alpha(0f)
            .translationY(10f * chip.resources.displayMetrics.density)
            .setDuration(400L)
            .withEndAction {
                val parent = chip.parent as? ViewGroup
                parent?.removeView(chip)
            }
            .start()
    }

    private fun dotFor(kind: Kind): Int = when (kind) {
        Kind.SUCCESS -> R.drawable.status_dot_online
        Kind.WARNING -> R.drawable.status_dot_starting
        Kind.ERROR -> R.drawable.status_dot_offline
        Kind.INFO -> R.drawable.status_dot_neutral
    }

    companion object {
        private const val DURATION_MS = 3000L
    }
}
