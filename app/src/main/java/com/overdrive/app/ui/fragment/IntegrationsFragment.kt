package com.overdrive.app.ui.fragment

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.card.MaterialCardView
import com.overdrive.app.R
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.telegram.impl.BotTokenConfig
import com.overdrive.app.ui.util.navigateDrillDown
import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integrations roll-up: Telegram / ABRP / MQTT / BYD Cloud cards. Each card
 * navigates to the existing per-integration destination so we don't duplicate
 * any of the native or web settings surfaces.
 *
 * Per-card status row reflects live state:
 *   - Telegram  → BotTokenConfig#hasToken (encrypted SharedPreferences in this
 *                 process — fast and correct).
 *   - ABRP      → daemon HTTP /api/abrp/status (upload service running) — the
 *                 token and config live in the daemon, not the app.
 *   - MQTT      → daemon HTTP /api/mqtt/status (any connection currently
 *                 connected) — the connection store is owned by the daemon
 *                 under /data/local/tmp/ which is not readable by the app UID.
 *   - BYD Cloud → daemon HTTP /api/bydcloud/status (credentials configured) —
 *                 raw password is never stored, only derived key hashes live
 *                 in the unified config.
 *
 * Daemon calls run on a background executor and poll every 3 s while the
 * fragment is resumed so the dot/label tracks reconnects without manual
 * refresh. Card click handlers are unchanged.
 */
class IntegrationsFragment : Fragment() {

    // Create the worker only while this Fragment owns a view. The previous
    // executor was never shut down, so navigating away could leave its thread
    // alive for the remainder of the process.
    private var executor: ExecutorService? = null
    private var pollInFlight = AtomicBoolean(false)
    private val pollGeneration = AtomicInteger()
    private var pollActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val daemonPoll = object : Runnable {
        override fun run() {
            if (!pollActive) return
            refreshDaemonStatuses()
            // onPause can race with a running tick. Recheck before reposting so
            // a callback cannot resurrect the poll loop after it was removed.
            if (pollActive) mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private var telegramConfigured: Boolean = false
    private var abrpConnected: Boolean = false
    private var mqttConnected: Boolean = false
    private var bydCloudConfigured: Boolean = false
    private var safeKeepOn: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_integrations, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Null-safe: a layout variant without one of the cards must not crash the screen.
        view.findViewById<View>(R.id.cardTelegram)?.setOnClickListener {
            findNavController().navigateDrillDown(R.id.telegramSettingsFragment)
        }
        view.findViewById<View>(R.id.cardAbrp)?.setOnClickListener {
            findNavController().navigateDrillDown(R.id.abrpSettingsFragment)
        }
        view.findViewById<View>(R.id.cardMqtt)?.setOnClickListener {
            findNavController().navigateDrillDown(R.id.mqttFragment)
        }
        view.findViewById<View>(R.id.cardBydCloud)?.setOnClickListener {
            findNavController().navigateDrillDown(R.id.bydCloudFragment)
        }
        view.findViewById<View>(R.id.cardSafeKeep)?.setOnClickListener {
            findNavController().navigateDrillDown(R.id.safeKeepFragment)
        }
    }

    override fun onResume() {
        super.onResume()
        pollActive = true
        // Telegram is a same-process encrypted-prefs read; safe on main thread.
        refreshTelegramStatus()
        // ABRP and MQTT live in the daemon — kick off an immediate poll and
        // schedule periodic refreshes so reconnects/disconnects show up.
        mainHandler.removeCallbacks(daemonPoll)
        mainHandler.post(daemonPoll)
    }

    override fun onPause() {
        pollActive = false
        pollGeneration.incrementAndGet()
        mainHandler.removeCallbacks(daemonPoll)
        super.onPause()
    }

    override fun onDestroyView() {
        pollActive = false
        pollGeneration.incrementAndGet()
        mainHandler.removeCallbacks(daemonPoll)
        // Detach the in-flight flag from this view generation. shutdownNow()
        // can remove a queued Runnable before its finally block executes; a new
        // view must not inherit that old flag in the permanently-true state.
        pollInFlight = AtomicBoolean(false)
        executor?.shutdownNow()
        executor = null
        super.onDestroyView()
    }

    // ============== Refresh ==============

    private fun refreshTelegramStatus() {
        val view = view ?: return
        val ctx = context ?: return
        telegramConfigured = isTelegramConfigured(ctx)
        bindStatus(
            view,
            dotId = R.id.dotTelegram,
            labelId = R.id.tvTelegramStatus,
            configured = telegramConfigured
        )
        bindHero(view)
    }

    private fun refreshDaemonStatuses() {
        // Each sweep makes several sequential daemon requests. A fixed-rate UI
        // tick must not enqueue another sweep while the previous one is still
        // waiting on a slow daemon; otherwise the single-thread queue can grow
        // without bound and display increasingly stale status.
        val inFlight = pollInFlight
        if (!inFlight.compareAndSet(false, true)) return
        val generation = pollGeneration.get()
        val appContext = context?.applicationContext
        val worker = executor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "IntegrationsStatus").apply { isDaemon = true }
        }.also { executor = it }

        try {
            worker.execute {
                try {
                    // Telegram lives in the unified config (cross-process); a
                    // forceReload before reading tracks daemon-side writes while
                    // this Fragment is visible. All probes remain off the looper.
                    val telegram = appContext?.let { isTelegramConfigured(it) } ?: telegramConfigured
                    val abrp = fetchAbrpRunning()
                    val mqtt = fetchMqttAnyConnected()
                    val bydCloud = fetchBydCloudConfigured()
                    val safeKeep = fetchSafeKeepOn()
                    mainHandler.post {
                        if (!isAdded || view == null || generation != pollGeneration.get()) {
                            return@post
                        }
                        val v = view ?: return@post
                        telegramConfigured = telegram
                        abrpConnected = abrp
                        mqttConnected = mqtt
                        bydCloudConfigured = bydCloud
                        safeKeepOn = safeKeep
                        bindStatus(v, R.id.dotTelegram, R.id.tvTelegramStatus, telegramConfigured)
                        bindStatus(v, R.id.dotAbrp, R.id.tvAbrpStatus, abrpConnected)
                        bindStatus(v, R.id.dotMqtt, R.id.tvMqttStatus, mqttConnected)
                        bindStatus(v, R.id.dotBydCloud, R.id.tvBydCloudStatus, bydCloudConfigured)
                        bindStatus(v, R.id.dotSafeKeep, R.id.tvSafeKeepStatus, safeKeepOn)
                        bindHero(v)
                    }
                } finally {
                    inFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            inFlight.set(false)
        }
    }

    // ============== Probes ==============

    private fun isTelegramConfigured(ctx: Context): Boolean = try {
        // Force a re-read from disk before checking. The token is written by
        // the daemon process (web UI POST → /api/telegram/token → daemon
        // writes /data/local/tmp/overdrive_config.json), and this Fragment
        // runs in the app process with its own UnifiedConfigManager cache.
        // Without forceReload() the in-process cache can return the value
        // observed at app launch — pre-token-save — making the Integrations
        // card show "Not set up" indefinitely after a successful save.
        // Same pattern TelegramApiHandler.handleStatus uses for the same
        // reason. Cost: one re-parse of a small JSON file on each onResume.
        UnifiedConfigManager.forceReload()
        BotTokenConfig(ctx.applicationContext).hasToken()
    } catch (_: Throwable) {
        false
    }

    /**
     * ABRP is "connected" when the daemon's upload service reports running.
     * Running implies a token is configured, the user enabled it, and the
     * scheduler is alive.
     */
    private fun fetchAbrpRunning(): Boolean {
        val json = fetchDaemonJson("/api/abrp/status") ?: return false
        if (!json.optBoolean("success", false)) return false
        val status = json.optJSONObject("status") ?: return false
        return status.optBoolean("running", false)
    }

    /**
     * MQTT is "connected" when at least one configured connection in the
     * daemon's manager is currently connected to its broker.
     */
    private fun fetchMqttAnyConnected(): Boolean {
        val json = fetchDaemonJson("/api/mqtt/status") ?: return false
        if (!json.optBoolean("success", false)) return false
        val arr: JSONArray = json.optJSONArray("connections") ?: return false
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val status = entry.optJSONObject("status") ?: continue
            if (status.optBoolean("connected", false)) return true
        }
        return false
    }

    /**
     * BYD Cloud is "configured" when the daemon reports stored credentials in
     * the unified config. The raw password is never persisted — only derived
     * key hashes — so a configured state means the user has completed setup.
     */
    private fun fetchBydCloudConfigured(): Boolean {
        val json = fetchDaemonJson("/api/bydcloud/status") ?: return false
        if (!json.optBoolean("success", false)) return false
        val status = json.optJSONObject("status") ?: return false
        return status.optBoolean("configured", false)
    }

    /** SafeKeep is "configured" when its master toggle is on and at least one storage is linked. */
    private fun fetchSafeKeepOn(): Boolean {
        val json = fetchDaemonJson("/api/safekeep/status") ?: return false
        if (!json.optBoolean("enabled", false)) return false
        val dests = json.optJSONArray("destinations") ?: return false
        return dests.length() > 0
    }

    private fun fetchDaemonJson(path: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(path, "GET", 1500, 2500)
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.isEmpty()) null else JSONObject(body)
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    // ============== Binding helpers ==============

    private fun bindStatus(
        root: View,
        dotId: Int,
        labelId: Int,
        configured: Boolean
    ) {
        val dot = root.findViewById<View>(dotId) ?: return
        val label = root.findViewById<TextView>(labelId) ?: return

        @StringRes val labelRes: Int
        @DrawableRes val dotRes: Int
        @AttrRes val labelAttr: Int

        if (configured) {
            labelRes = R.string.integrations_status_configured
            dotRes = R.drawable.status_dot_online
            labelAttr = androidx.appcompat.R.attr.colorPrimary
        } else {
            labelRes = R.string.integrations_status_not_set_up
            dotRes = R.drawable.status_dot_offline
            labelAttr = com.google.android.material.R.attr.colorOnSurfaceVariant
        }

        label.setText(labelRes)
        label.setTextColor(resolveAttrColor(label.context, labelAttr))
        dot.setBackgroundResource(dotRes)
    }

    private fun bindHero(root: View) {
        val pill = root.findViewById<MaterialCardView>(R.id.heroStatusPill) ?: return
        val label = root.findViewById<TextView>(R.id.tvHeroStatus) ?: return

        val allReady = telegramConfigured && abrpConnected && mqttConnected && bydCloudConfigured
        @AttrRes val bgAttr: Int
        @AttrRes val fgAttr: Int
        @StringRes val textRes: Int
        if (allReady) {
            bgAttr = com.google.android.material.R.attr.colorPrimaryContainer
            fgAttr = com.google.android.material.R.attr.colorOnPrimaryContainer
            textRes = R.string.integrations_status_configured
        } else {
            bgAttr = com.google.android.material.R.attr.colorSecondaryContainer
            fgAttr = com.google.android.material.R.attr.colorOnSecondaryContainer
            textRes = R.string.integrations_status_unknown
        }
        pill.setCardBackgroundColor(resolveAttrColor(label.context, bgAttr))
        label.setText(textRes)
        label.setTextColor(resolveAttrColor(label.context, fgAttr))
    }

    private fun resolveAttrColor(ctx: Context, @AttrRes attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3000L
    }
}
