package com.overdrive.app.launcher

import android.app.Activity
import android.os.Handler
import android.os.Looper

/**
 * Transparent, zero-content anchor activity for the split-screen re-dock path.
 *
 * <p>When [AppLauncher] docks an ALREADY-RUNNING app into split-screen it launches
 * this activity with {@code am start --windowingMode 3} (SPLIT_SCREEN_PRIMARY) to
 * force a split-screen-primary stack into existence, then moves the target app's
 * task into that stack. The activity has no UI (never calls setContentView, uses a
 * translucent theme) so it is invisible while it briefly holds the primary half.
 *
 * <p>It self-finishes after a short grace window so it never lingers behind the
 * re-docked app. {@code launchMode=singleInstance} means a rapid second re-dock
 * re-uses this instance and re-runs {@link #onResume}; the finish is scheduled once
 * per resume and guarded so overlapping launches don't stack callbacks or finish an
 * instance that a newer launch is still relying on. Mirrors the transparent
 * LocationStarterActivity pattern already used by the daemon.
 */
open class AppLauncherGhostActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { if (!isFinishing) finish() }

    override fun onResume() {
        super.onResume()
        // Re-arm a single self-finish per resume. Removing any pending callback first
        // keeps a singleInstance re-launch from stacking multiple finishes (and from
        // finishing early while the newer re-dock is still moving its task on top).
        handler.removeCallbacks(finishRunnable)
        handler.postDelayed(finishRunnable, SELF_FINISH_MS)
    }

    override fun onPause() {
        super.onPause()

//        It is unnecessary and prematurely cancels the ghostview's termination.
//        It only needs to be called in onDestroy. The activity should pause when the app
//        is injected on top of it in the stack.
//        handler.removeCallbacks(finishRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        super.onDestroy()
    }

    companion object {
        /** Grace window before the invisible anchor removes itself. Must outlast the
         *  caller's full re-dock budget (up to ~1.5s of stack polls + several dumpsys
         *  reads + `am stack move-task`, each dumpsys hundreds of ms on a loaded head
         *  unit) so the split stack still exists when move-task lands the real app on
         *  top; short enough that a failed re-dock doesn't leave a dead half on screen.
         *  4.5s comfortably covers the worst-case poll budget with margin. */
        private const val SELF_FINISH_MS = 4500L
    }
}
