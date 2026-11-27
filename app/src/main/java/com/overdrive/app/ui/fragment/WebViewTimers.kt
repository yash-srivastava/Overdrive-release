package com.overdrive.app.ui.fragment

import android.os.Handler
import android.os.Looper

/**
 * Pauses the WebView's JS timers while no OverDrive screen is started, which the head units' WebView doesn't
 * do on its own (no reliable Page Visibility event; webView.onPause() leaves timers running). The pause is
 * process-wide, so it waits until every hosting activity has stopped, and a page that has to keep running
 * behind the car's own screens (live audio, talk, remote view) leaves timers alone. Main thread only.
 */
internal object WebViewTimers {
    /** Lets a setting saved just before leaving (pages debounce 200-400 ms) and a stream's pause finish first. */
    private const val PAUSE_DELAY_MS = 5_000L
    private val PAGES_KEEP_RUNNING = setOf("/live-view", "/communicate", "/assistant", "/remote-dev-view")

    private val main = Handler(Looper.getMainLooper())
    private val hosts = LinkedHashSet<WebViewFragment>()
    private var startedActivities = 0
    private var paused = false
    private val pauseLater = Runnable { pauseNow() }

    fun attach(f: WebViewFragment) {
        hosts.add(f)
        // a WebView made after the last one was paused runs once a screen is up
        if (paused && startedActivities > 0) resumeNow()
    }

    fun detach(f: WebViewFragment) {
        hosts.remove(f)
    }

    fun activityStarted() {
        startedActivities++
        main.removeCallbacks(pauseLater)
        if (paused) resumeNow()
    }

    fun activityStopped() {
        if (startedActivities > 0) startedActivities--
        if (startedActivities == 0) {
            main.removeCallbacks(pauseLater)
            main.postDelayed(pauseLater, PAUSE_DELAY_MS)
        }
    }

    /** "/live-view.html" and "/live-view" both name the live view page. */
    fun keepsRunning(path: String?): Boolean {
        val p = path?.removeSuffix(".html") ?: return false
        return p in PAGES_KEEP_RUNNING
    }

    private fun pauseNow() {
        if (paused || startedActivities > 0) return
        if (hosts.any { it.keepsTimersInBackground() }) return
        val wv = hosts.firstNotNullOfOrNull { it.liveWebViewOrNull() } ?: return
        try { wv.pauseTimers(); paused = true } catch (_: Throwable) {}
    }

    private fun resumeNow() {
        val wv = hosts.firstNotNullOfOrNull { it.liveWebViewOrNull() } ?: return
        try { wv.resumeTimers() } catch (_: Throwable) {}
        paused = false
    }
}
