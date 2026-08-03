package com.overdrive.app.ui.fragment

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.overdrive.app.R
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.util.RecordingScanner
import com.overdrive.app.ui.util.navigateDrillDown
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Diagnostics: power-user surface for logs, ADB console, traffic monitor,
 * camera probe, and battery health.
 *
 * Wires real telemetry into the Network and Storage health tiles:
 *   - Network top line: Wi-Fi SSID (or "Mobile" / "Ethernet" / "Offline").
 *   - Network bottom line: tunnel state ("Tunnel · Online/Connecting/Offline").
 *   - Storage top line: clip count + bytes used (active recordings + sentry dirs).
 *   - Storage bottom line: free bytes on the recordings filesystem.
 *
 * The Camera and Battery tiles still show placeholders (out of scope).
 */
class DiagnosticsFragment : Fragment() {

    private val daemonsViewModel: DaemonsViewModel by activityViewModels()

    // Network tile
    private var tvNetworkValue: TextView? = null
    private var viewNetworkTunnelDot: View? = null
    private var tvNetworkTunnelState: TextView? = null

    // Storage tile
    private var tvStorageUsed: TextView? = null
    private var tvStorageFree: TextView? = null

    // Camera tile
    private var tvCameraValue: TextView? = null
    private var viewCameraDot: View? = null

    // Battery tile
    private var tvBatteryValue: TextView? = null
    private var viewBatteryDot: View? = null
    private var tvBatteryReviewBadge: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ssidRefreshRunnable: Runnable? = null
    private var storageExecutor: ExecutorService? = null
    private var batteryExecutor: ExecutorService? = null

    // Watches the SOH properties file for changes so the Battery tile
    // reflects user-driven reset/recalibration without requiring the user
    // to leave and re-enter the diagnostics tab. SohEstimator deletes and
    // rewrites this file on reset → seedInitialEstimate, which the
    // observer surfaces as CLOSE_WRITE / DELETE / MOVED_TO events.
    private var sohFileObserver: android.os.FileObserver? = null
    private val sohFilePath = "/data/local/tmp/abrp_soh_estimate.properties"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_diagnostics, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The inline live-logs panel was removed from this page. Logs
        // remain reachable from the ADB Console tile.

        view.findViewById<View>(R.id.cardAdb).setOnClickListener {
            findNavController().navigateDrillDown(R.id.adbConsoleFragment)
        }
        view.findViewById<View>(R.id.cardTraffic).setOnClickListener {
            (activity as? MainActivity)?.invokeTrafficMonitorAction()
        }
        view.findViewById<View>(R.id.cardCameraProbe).setOnClickListener {
            (activity as? MainActivity)?.invokeReconfigureCameraAction()
        }
        view.findViewById<View>(R.id.cardBattery).setOnClickListener {
            (activity as? MainActivity)?.invokeBatteryHealthAction()
        }
        // Hidden affordance: long-press Battery tile to wipe onboarding state and replay
        // the first-run guide (for re-demos / support). No visible label — discoverable
        // only deliberately. The normal click (battery health) stays intact.
        view.findViewById<View>(R.id.cardBattery).setOnLongClickListener {
            (activity as? MainActivity)?.resetAndReplayOnboarding()
            android.widget.Toast.makeText(
                requireContext(), R.string.onboarding_reset_toast, android.widget.Toast.LENGTH_SHORT
            ).show()
            true
        }
        view.findViewById<View>(R.id.cardSettingsShortcut)?.setOnClickListener {
            // Settings is a peer rail destination — match rail fade-through
            // motion so the cardSettings shortcut feels like rail navigation.
            findNavController().navigate(
                R.id.settingsFragment, null,
                com.overdrive.app.ui.util.NavOptionsExt.m3FadeThrough()
            )
        }
        // HEALTH tiles for Camera + Battery now act as shortcuts to the
        // same actions the corresponding TOOLS tiles fire — saves the
        // user a tap when the tile already shows the health they want
        // to act on.
        view.findViewById<View>(R.id.cardCameraHealth)?.setOnClickListener {
            (activity as? MainActivity)?.invokeReconfigureCameraAction()
        }
        view.findViewById<View>(R.id.cardBatteryHealth)?.setOnClickListener {
            (activity as? MainActivity)?.invokeBatteryHealthAction()
        }

        // ===== Network tile bindings =====
        tvNetworkValue = view.findViewById(R.id.tvNetworkValue)
        viewNetworkTunnelDot = view.findViewById(R.id.viewNetworkTunnelDot)
        tvNetworkTunnelState = view.findViewById(R.id.tvNetworkTunnelState)

        // ===== Storage tile bindings =====
        tvStorageUsed = view.findViewById(R.id.tvStorageUsed)
        tvStorageFree = view.findViewById(R.id.tvStorageFree)

        // ===== Camera + Battery tile bindings =====
        tvCameraValue = view.findViewById(R.id.tvCameraValue)
        viewCameraDot = view.findViewById(R.id.viewCameraDot)
        tvBatteryValue = view.findViewById(R.id.tvBatteryValue)
        viewBatteryDot = view.findViewById(R.id.viewBatteryDot)
        tvBatteryReviewBadge = view.findViewById(R.id.tvBatteryReviewBadge)

        // Tunnel-state-driven refresh (changes immediately when daemons toggle).
        val tunnelObserver = Observer<String?> { _ -> updateNetworkTile() }
        daemonsViewModel.cloudflaredController.tunnelUrl.observe(viewLifecycleOwner, tunnelObserver)
        daemonsViewModel.zrokController.tunnelUrl.observe(viewLifecycleOwner, tunnelObserver)
        daemonsViewModel.tailscaleController.tunnelUrl.observe(viewLifecycleOwner, tunnelObserver)
        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { _ ->
            updateNetworkTile()
            // Camera tile depends on whether the camera daemon is RUNNING — refresh
            // every time daemonStates flips so the dot/value update without delay.
            updateCameraTile()
        }

        // First paint.
        updateNetworkTile()
        updateCameraTile()

        // SSID polling: Wi-Fi network can change without a system broadcast we're listening
        // to here, so a 5-second timer is a good-enough refresh cadence for a diagnostics panel.
        scheduleSsidRefresh()
    }

    override fun onResume() {
        super.onResume()
        // Storage probe is relatively expensive (file walk) — refresh on each resume.
        refreshStorageTile()
        // Camera config + battery SOH are also re-probed (config can mutate from
        // SurveillanceApiHandler; SOH file can be regenerated by SohEstimator).
        updateCameraTile()
        refreshBatteryTile()
        startSohFileObserver()
    }

    override fun onPause() {
        super.onPause()
        stopSohFileObserver()
    }

    @Suppress("DEPRECATION")
    private fun startSohFileObserver() {
        if (sohFileObserver != null) return
        // The deprecated path-based ctor is the only one available on the
        // app's minSdk; the post-API-29 File ctor would require minSdk 29.
        sohFileObserver = object : android.os.FileObserver(
            sohFilePath,
            android.os.FileObserver.CLOSE_WRITE
                or android.os.FileObserver.DELETE
                or android.os.FileObserver.DELETE_SELF
                or android.os.FileObserver.MOVED_TO
                or android.os.FileObserver.CREATE
        ) {
            override fun onEvent(event: Int, path: String?) {
                // Coalesce multiple events (delete then create on reset) by
                // posting a single refresh to the main thread; FileObserver
                // fires on its own thread.
                mainHandler.post {
                    if (isAdded) refreshBatteryTile()
                }
            }
        }.also { it.startWatching() }
    }

    private fun stopSohFileObserver() {
        sohFileObserver?.stopWatching()
        sohFileObserver = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ssidRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        ssidRefreshRunnable = null
        storageExecutor?.shutdownNow()
        storageExecutor = null
        batteryExecutor?.shutdownNow()
        batteryExecutor = null
        stopSohFileObserver()
        tvNetworkValue = null
        viewNetworkTunnelDot = null
        tvNetworkTunnelState = null
        tvStorageUsed = null
        tvStorageFree = null
        tvCameraValue = null
        viewCameraDot = null
        tvBatteryValue = null
        tvBatteryReviewBadge = null
        viewBatteryDot = null
    }

    // ============== Network tile ==============

    private fun scheduleSsidRefresh() {
        val r = object : Runnable {
            override fun run() {
                if (!isAdded) return
                updateNetworkTile()
                mainHandler.postDelayed(this, SSID_REFRESH_INTERVAL_MS)
            }
        }
        ssidRefreshRunnable = r
        mainHandler.postDelayed(r, SSID_REFRESH_INTERVAL_MS)
    }

    private fun updateNetworkTile() {
        val ctx = context ?: return
        val topLine = computeNetworkTopLine(ctx)
        tvNetworkValue?.text = topLine

        val (stateLabelRes, dotRes) = computeTunnelState()
        tvNetworkTunnelState?.text = getString(
            R.string.diagnostics_network_tunnel_label,
            getString(stateLabelRes)
        )
        viewNetworkTunnelDot?.setBackgroundResource(dotRes)
    }

    /**
     * Resolves the top-line label: connected SSID (quotes stripped), or
     * "Mobile" / "Ethernet" / "Offline" if not on Wi-Fi.
     */
    private fun computeNetworkTopLine(ctx: Context): String {
        // Try Wi-Fi first — strip the framework's surrounding quotes.
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifi?.connectionInfo
            // SSID is "<unknown ssid>" when not connected; networkId == -1 also means no association.
            val rawSsid = info?.ssid
            val networkId = info?.networkId ?: -1
            if (!rawSsid.isNullOrBlank() &&
                rawSsid != WifiManager.UNKNOWN_SSID &&
                rawSsid != "0x" &&
                networkId != -1
            ) {
                val stripped = if (rawSsid.length >= 2 &&
                    rawSsid.startsWith("\"") &&
                    rawSsid.endsWith("\"")
                ) {
                    rawSsid.substring(1, rawSsid.length - 1)
                } else {
                    rawSsid
                }
                if (stripped.isNotBlank()) return stripped
            }
        } catch (_: SecurityException) {
            // Permission denied — fall through to ConnectivityManager.
        } catch (_: Throwable) {
            // Defensive — never let a probe crash the tile.
        }

        // Fall back to active-network type (deprecated APIs, but still functional on minSdk 25).
        return try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
            @Suppress("DEPRECATION")
            val ni = cm?.activeNetworkInfo
            @Suppress("DEPRECATION")
            when {
                ni == null || !ni.isConnected -> getString(R.string.diagnostics_network_offline)
                ni.type == ConnectivityManager.TYPE_MOBILE -> getString(R.string.diagnostics_network_mobile)
                ni.type == ConnectivityManager.TYPE_ETHERNET -> getString(R.string.diagnostics_network_ethernet)
                ni.type == ConnectivityManager.TYPE_WIFI -> getString(R.string.diagnostics_network_offline)
                else -> getString(R.string.diagnostics_network_offline)
            }
        } catch (_: Throwable) {
            getString(R.string.diagnostics_network_offline)
        }
    }

    /**
     * Tunnel state for the bottom row: any non-empty tunnel URL → online (green),
     * any tunnel daemon STARTING → connecting (warning), else offline (neutral).
     */
    private fun computeTunnelState(): Pair<Int, Int> {
        val anyUrl = listOf(
            daemonsViewModel.cloudflaredController.tunnelUrl.value,
            daemonsViewModel.zrokController.tunnelUrl.value,
            daemonsViewModel.tailscaleController.tunnelUrl.value
        ).any { !it.isNullOrEmpty() }

        if (anyUrl) {
            return R.string.diagnostics_tunnel_state_online to R.drawable.status_dot_online
        }

        val states = daemonsViewModel.daemonStates.value
        val anyStarting = states?.values?.any {
            (it.type == DaemonType.CLOUDFLARED_TUNNEL ||
                it.type == DaemonType.ZROK_TUNNEL ||
                it.type == DaemonType.TAILSCALE_TUNNEL) &&
                it.status == DaemonStatus.STARTING
        } == true

        return if (anyStarting) {
            // status_dot_starting is the existing warning-coloured dot.
            R.string.diagnostics_tunnel_state_connecting to R.drawable.status_dot_starting
        } else {
            R.string.diagnostics_tunnel_state_offline to R.drawable.status_dot_offline
        }
    }

    // ============== Storage tile ==============

    private fun refreshStorageTile() {
        val ctx = context?.applicationContext ?: return
        val executor = storageExecutor ?: Executors.newSingleThreadExecutor()
            .also { storageExecutor = it }

        executor.execute {
            // Walk the active recordings + sentry dirs directly so we don't depend on
            // the (cached) RecordingScanner.scanRecordings result, which excludes
            // 0-byte ghost files etc. For a Storage tile we want raw on-disk numbers.
            var clipCount = 0
            var usedBytes = 0L
            var freeBytes = 0L
            try {
                val recordingsDir = RecordingScanner.getRecordingsDir(ctx)
                val sentryDir = RecordingScanner.getSentryEventsDir(ctx)
                clipCount += countMp4(recordingsDir)
                clipCount += countMp4(sentryDir)
                usedBytes += sumMp4Sizes(recordingsDir)
                usedBytes += sumMp4Sizes(sentryDir)

                // Free space on the filesystem holding the recordings dir.
                val statFsTarget = when {
                    recordingsDir.exists() -> recordingsDir
                    sentryDir.exists() -> sentryDir
                    else -> null
                }
                if (statFsTarget != null) {
                    try {
                        val stat = StatFs(statFsTarget.absolutePath)
                        freeBytes = stat.availableBytes
                    } catch (_: Throwable) {
                        freeBytes = 0L
                    }
                }
            } catch (_: Throwable) {
                // Leave defaults — we still post the (zero) result so the tile updates.
            }

            val usedHuman = Formatter.formatShortFileSize(ctx, usedBytes)
            val freeHuman = Formatter.formatShortFileSize(ctx, freeBytes)

            mainHandler.post {
                if (!isAdded) return@post
                tvStorageUsed?.text = getString(
                    R.string.diagnostics_storage_used_line, clipCount, usedHuman
                )
                tvStorageFree?.text = getString(
                    R.string.diagnostics_storage_free_line, freeHuman
                )
            }
        }
    }

    private fun countMp4(dir: File): Int {
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) return 0
        val files = dir.listFiles() ?: return 0
        var n = 0
        for (f in files) {
            if (f.isFile && f.name.endsWith(".mp4") && f.length() > 0L) n++
        }
        return n
    }

    private fun sumMp4Sizes(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) return 0L
        val files = dir.listFiles() ?: return 0L
        var sum = 0L
        for (f in files) {
            if (f.isFile && f.name.endsWith(".mp4")) sum += f.length()
        }
        return sum
    }

    // ============== Camera tile ==============

    /**
     * Resolves camera state from two signals:
     *  1) UnifiedConfigManager's "camera" section (probedCameraId / manualOverride)
     *     — the config file is on /data/local/tmp and cheap to read.
     *  2) The CAMERA_DAEMON daemon status from DaemonsViewModel — if not RUNNING,
     *     we override to "Offline" + red dot regardless of the saved probe.
     */
    private fun updateCameraTile() {
        val tv = tvCameraValue ?: return
        val dot = viewCameraDot ?: return

        // Daemon state takes priority: a non-running camera daemon means the
        // tile must show Offline regardless of any saved probedCameraId.
        val daemonState = daemonsViewModel.daemonStates.value
            ?.get(DaemonType.CAMERA_DAEMON)
        if (daemonState == null || daemonState.status != DaemonStatus.RUNNING) {
            tv.text = getString(R.string.diagnostics_camera_value_offline)
            dot.setBackgroundResource(R.drawable.status_dot_offline)
            return
        }

        // Daemon is RUNNING. Read the saved probed camera id from config.
        var probedId = -1
        var manualOverride = false
        try {
            val cfg = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
            val cam = cfg.optJSONObject("camera")
            if (cam != null) {
                probedId = cam.optInt("probedCameraId", -1)
                manualOverride = cam.optBoolean("manualOverride", false)
            }
        } catch (_: Throwable) {
            // Defensive — fall through to "Probing…" if config read fails.
        }

        when {
            probedId < 0 -> {
                tv.text = getString(R.string.diagnostics_camera_value_probing)
                dot.setBackgroundResource(R.drawable.status_dot_starting)
            }
            manualOverride -> {
                tv.text = getString(R.string.diagnostics_camera_value_camera_n_manual, probedId)
                dot.setBackgroundResource(R.drawable.status_dot_online)
            }
            else -> {
                tv.text = getString(R.string.diagnostics_camera_value_camera_n, probedId)
                dot.setBackgroundResource(R.drawable.status_dot_online)
            }
        }
    }

    // ============== Battery tile ==============

    /** Reads only the daemon's canonical OEM-first SOH value. */
    private fun refreshBatteryTile() {
        val executor = batteryExecutor ?: Executors.newSingleThreadExecutor()
            .also { batteryExecutor = it }

        executor.execute {
            var sohPercent: Double? = null
            var frameMismatch = false
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh", "GET", 1500, 2000)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    val source = json.optString("displaySource", "unavailable")
                    val value = json.optDouble("displaySoh", -1.0)
                    if (source != "unavailable" && value > 0.0 && value <= 100.0) {
                        sohPercent = value
                    }
                    val frame = json.optJSONObject("frameAnchor")
                    if (frame != null) frameMismatch = frame.optBoolean("mismatch", false)
                }
                conn.disconnect()
            } catch (_: Throwable) { /* pending data; never fall back to raw estimator state */ }

            val finalSoh = sohPercent
            val finalMismatch = frameMismatch
            mainHandler.post {
                if (!isAdded) return@post
                val tv = tvBatteryValue ?: return@post
                val dot = viewBatteryDot ?: return@post
                val badge = tvBatteryReviewBadge
                if (finalSoh == null) {
                    tv.text = getString(R.string.diagnostics_battery_value_pending)
                    dot.setBackgroundResource(R.drawable.status_dot_neutral)
                } else {
                    tv.text = getString(R.string.diagnostics_battery_value_soh, finalSoh)
                    // When the frame anchor flags a mismatch, force the dot
                    // amber regardless of the SOH bucket — the headline number
                    // itself isn't trustworthy until the user confirms or
                    // corrects nominal. Otherwise fall through to the normal
                    // health bucket coloring.
                    val dotRes = when {
                        finalMismatch -> R.drawable.status_dot_starting
                        finalSoh >= 80.0 -> R.drawable.status_dot_online
                        finalSoh >= 50.0 -> R.drawable.status_dot_starting
                        else -> R.drawable.status_dot_offline
                    }
                    dot.setBackgroundResource(dotRes)
                }
                badge?.visibility = if (finalMismatch) View.VISIBLE else View.GONE
            }
        }
    }

    companion object {
        private const val SSID_REFRESH_INTERVAL_MS = 5_000L
    }
}
