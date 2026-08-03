package com.overdrive.app.ui.fragment

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.overdrive.app.R
import com.overdrive.app.auth.AuthManager
import com.overdrive.app.client.CameraDaemonClient
import com.overdrive.app.ui.dashboard.DashboardInsight
import com.overdrive.app.ui.dashboard.DashboardInsightProvider
import com.overdrive.app.ui.model.DaemonState
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.model.localizedName
import com.overdrive.app.ui.util.QrCodeGenerator
import com.overdrive.app.ui.util.RecordingScanner
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.viewmodel.MainViewModel
import com.overdrive.app.ui.viewmodel.RecordingViewModel
import com.overdrive.app.util.DeviceIdGenerator
import java.util.Calendar
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URLConnection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Vehicle-first dashboard.
 *
 * Layout (top to bottom):
 *   - Hero cockpit: surveillance health plus live SOC, range, SOH and vehicle art.
 *   - Command deck: vehicle profile, background services and operational status.
 *   - Pairing details remain collapsed until Remote access is selected.
 */
class DashboardFragment : Fragment() {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    private val recordingViewModel: RecordingViewModel by activityViewModels()

    // Hero
    private lateinit var heroCard: MaterialCardView
    private lateinit var heroGreeting: TextView
    private lateinit var heroSubtitle: TextView
    private var heroStateDot: View? = null
    private var heroSocProgress: CircularProgressIndicator? = null
    private var heroSocValue: TextView? = null
    private var heroRangeValue: TextView? = null
    private var heroSohValue: TextView? = null
    private var heroChargeCompletion: TextView? = null
    private var heroVehicleWebView: WebView? = null
    // Hero status chips (3): tunnel state, services running, recording state.
    // Bound lazily (chipGroup may be absent on landscape variant).
    private var heroChipTunnel: com.google.android.material.chip.Chip? = null
    private var heroChipServices: com.google.android.material.chip.Chip? = null
    private var heroChipRecording: com.google.android.material.chip.Chip? = null

    // Metric tiles
    private lateinit var metricRecordings: MaterialCardView
    private lateinit var metricRecordingsValue: TextView
    // Storage line lives inside the combined Recordings tile now; no
    // separate clickable card. The text view is still bound so the
    // background metrics walker can populate "8.4 GB used · 12 GB free".
    private lateinit var metricStorageValue: TextView
    private lateinit var metricTunnel: MaterialCardView
    private lateinit var metricTunnelValue: TextView
    private lateinit var tunnelStateDot: View
    private lateinit var cardDaemons: MaterialCardView
    private lateinit var tvDaemonsStatus: TextView
    private var serviceSegments: List<View> = emptyList()

    // Remote connection details. Landscape keeps this card collapsed until
    // the compact Remote Access row is tapped; portrait still renders its
    // existing always-visible card and therefore has no wrapper/close button.
    private var remoteAccessDetails: MaterialCardView? = null
    private var btnCloseRemoteAccess: ImageView? = null
    private lateinit var ivQrCode: ImageView
    private lateinit var tvQrPlaceholder: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var chipGroupTunnels: ChipGroup
    private var selectedTunnel: DaemonType? = null

    // Auth
    private lateinit var tvDeviceToken: TextView
    private lateinit var btnToggleToken: ImageView
    private lateinit var btnCopyToken: ImageView
    private lateinit var btnRegenerateToken: MaterialButton
    private var isTokenVisible = false

    // The compact live card is present in both orientations.
    private lateinit var quickLive: MaterialCardView

    // Vehicle tile — battery capacity + model summary. Tap opens a dialog.
    private var metricVehicle: MaterialCardView? = null
    private var metricVehicleValue: TextView? = null
    private var metricVehicleDetail: TextView? = null
    private var metricVehicleStatus: TextView? = null
    private var vehicleRefreshGeneration = 0
    private val chargeCompletionRefreshRunnable = object : Runnable {
        override fun run() {
            refreshHeroChargeCompletionOnly()
            mainHandler.postDelayed(this, CHARGE_COMPLETION_REFRESH_MS)
        }
    }

    // Background work for storage / recording-count tiles. Single thread is enough
    // — both probes are just a directory walk, and serializing them keeps disk I/O
    // out of the UI thread without contending with itself.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var metricsExecutor: ExecutorService? = null

    // Latest values cached so the recording-state observer (which fires on its
    // own cadence) can re-render without re-walking the disk.
    @Volatile private var todayClipCount: Int = 0

    // ============== Hero subtitle insights carousel ==============
    //
    // Tesla/Polestar-tier rotating data line. Each insight is computed on a
    // worker thread (DB + filesystem), then posted back to fade through the
    // existing heroSubtitle TextView. We deliberately reuse the existing view
    // — no layout changes — and just animate its text content.
    private var insightsProvider: DashboardInsightProvider? = null
    private var insights: List<DashboardInsight> = emptyList()
    private var insightIndex: Int = 0
    private var rotationPaused: Boolean = false
    private var firstVisitCount: Int = -1
    // Track the running daemon-summary so we can show it as the bottom-of-the-
    // ladder fallback only when no real insights are available.
    private var lastDaemonRunning: Int = 0
    private var lastDaemonTotal: Int = 0

    private val insightRotateRunnable = Runnable { rotateInsight() }
    private val insightResumeRunnable = Runnable {
        rotationPaused = false
        scheduleNextInsight()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        setupVehicleHero()
        wireClicks()
        observeViewModels()

        // Hero headline is a static brand statement, not a time-of-day greeting.
        // The "Good morning / Good night" line read as concierge UI; an
        // infotainment cockpit benefits more from a stable identity headline
        // paired with the live status row of chips below it. See
        // dashboard_hero_headline (default: "Cockpit").
        heroGreeting.setText(R.string.dashboard_hero_headline)
        tvDeviceId.text = DeviceIdGenerator.generateDeviceId(requireContext())
        loadAuthState()

        // First paint of the local metric tiles. Vehicle telemetry starts in
        // onResume so it is requested once rather than duplicated during setup.
        refreshMetricsTiles()

        // Insights carousel — initialize provider once; bump visit counter so
        // the welcome-on-first-install insight is exclusive to visit #0.
        if (insightsProvider == null) {
            val provider = DashboardInsightProvider(requireContext().applicationContext)
            insightsProvider = provider
            firstVisitCount = provider.recordDashboardVisit()
        }
        wireSubtitleTouchPause()
    }

    override fun onResume() {
        super.onResume()
        heroVehicleWebView?.onResume()
        // Cheap-but-not-free disk walk: refresh on every resume so the storage
        // and today's-clip-count numbers update after the user records, deletes,
        // or sentry events fire while the dashboard wasn't on screen.
        //
        // Drop the scanner cache first — deletions in other fragments don't
        // notify the dashboard, so without this the tile and the carousel
        // could read up to 5s of stale data after the user navigates back
        // from the recordings page.
        RecordingScanner.invalidateCache()
        refreshMetricsTiles()
        refreshVehicleTile()
        mainHandler.removeCallbacks(chargeCompletionRefreshRunnable)
        mainHandler.postDelayed(chargeCompletionRefreshRunnable, CHARGE_COMPLETION_REFRESH_MS)
        // Always rebuild the insight list on resume — data may have changed
        // while we were backgrounded (new clips, finished charging session,
        // SOC delta from a parking session that ended off-screen, etc.).
        rotationPaused = false
        rebuildInsightsAsync()
        // Re-read the access code on every resume. On a fresh install the
        // app process initializes AuthManager with an in-memory secret
        // before the daemon writes the canonical one to /data/local/tmp/;
        // a resume-time refresh means navigating away and back is enough
        // to pick up the daemon's secret if the bootstrap reconcile
        // (1s cadence in AuthManager.getState()) hasn't fired yet.
        loadAuthState()
    }

    override fun onPause() {
        heroVehicleWebView?.onPause()
        super.onPause()
        vehicleRefreshGeneration++
        mainHandler.removeCallbacks(chargeCompletionRefreshRunnable)
        cancelInsightCallbacks()
    }

    override fun onDestroyView() {
        vehicleRefreshGeneration++
        remoteAccessDetails?.animate()?.cancel()
        mainHandler.removeCallbacks(chargeCompletionRefreshRunnable)
        heroVehicleWebView?.let { webView ->
            webView.removeJavascriptInterface("DashboardHeroBridge")
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        heroVehicleWebView = null
        super.onDestroyView()
        cancelInsightCallbacks()
        metricsExecutor?.shutdownNow()
        metricsExecutor = null
    }

    private fun bindViews(view: View) {
        heroCard = view.findViewById(R.id.heroCard)
        heroGreeting = view.findViewById(R.id.heroGreeting)
        heroSubtitle = view.findViewById(R.id.heroSubtitle)
        heroStateDot = view.findViewById(R.id.heroStateDot)
        heroSocProgress = view.findViewById(R.id.heroSocProgress)
        heroSocValue = view.findViewById(R.id.heroSocValue)
        heroRangeValue = view.findViewById(R.id.heroRangeValue)
        heroSohValue = view.findViewById(R.id.heroSohValue)
        heroChargeCompletion = view.findViewById(R.id.heroChargeCompletion)
        heroVehicleWebView = view.findViewById(R.id.heroVehicleWebView)
        // Hero status chips — present in portrait, may be absent in landscape.
        heroChipTunnel = view.findViewById(R.id.heroChipTunnel)
        heroChipServices = view.findViewById(R.id.heroChipServices)
        heroChipRecording = view.findViewById(R.id.heroChipRecording)

        metricRecordings = view.findViewById(R.id.metricRecordings)
        metricRecordingsValue = view.findViewById(R.id.metricRecordingsValue)
        metricStorageValue = view.findViewById(R.id.metricStorageValue)
        metricTunnel = view.findViewById(R.id.metricTunnel)
        metricTunnelValue = view.findViewById(R.id.metricTunnelValue)
        tunnelStateDot = view.findViewById(R.id.tunnelStateDot)
        cardDaemons = view.findViewById(R.id.cardDaemons)
        tvDaemonsStatus = view.findViewById(R.id.tvDaemonsStatus)
        serviceSegments = listOfNotNull(
            view.findViewById<View>(R.id.serviceSegment1),
            view.findViewById<View>(R.id.serviceSegment2),
            view.findViewById<View>(R.id.serviceSegment3),
            view.findViewById<View>(R.id.serviceSegment4),
            view.findViewById<View>(R.id.serviceSegment5),
            view.findViewById<View>(R.id.serviceSegment6),
            view.findViewById<View>(R.id.serviceSegment7),
            view.findViewById<View>(R.id.serviceSegment8)
        )

        remoteAccessDetails = view.findViewById(R.id.remoteAccessDetails)
        btnCloseRemoteAccess = view.findViewById(R.id.btnCloseRemoteAccess)
        ivQrCode = view.findViewById(R.id.ivQrCode)
        tvQrPlaceholder = view.findViewById(R.id.tvQrPlaceholder)
        tvUrl = view.findViewById(R.id.tvUrl)
        tvDeviceId = view.findViewById(R.id.tvDeviceId)
        chipGroupTunnels = view.findViewById(R.id.chipGroupTunnels)

        tvDeviceToken = view.findViewById(R.id.tvDeviceToken)
        btnToggleToken = view.findViewById(R.id.btnToggleToken)
        btnCopyToken = view.findViewById(R.id.btnCopyToken)
        btnRegenerateToken = view.findViewById(R.id.btnRegenerateToken)

        quickLive = view.findViewById(R.id.quickLive)

        // Vehicle tile present in both portrait and landscape layouts.
        metricVehicle = view.findViewById(R.id.metricVehicle)
        metricVehicleValue = view.findViewById(R.id.metricVehicleValue)
        metricVehicleDetail = view.findViewById(R.id.metricVehicleDetail)
        metricVehicleStatus = view.findViewById(R.id.metricVehicleStatus)
    }

    /**
     * Render the selected vehicle in the native landscape hero using the exact
     * web GLB/model/paint pipeline. The renderer is mutation-driven rather than
     * continuously animated. The neutral radar stage remains visible until the
     * selected model has completed its first paint; no model-specific fallback
     * image is shipped or shown.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupVehicleHero() {
        val webView = heroVehicleWebView ?: return // portrait has no vehicle stage
        webView.visibility = View.INVISIBLE
        webView.alpha = 0f
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isClickable = false
        webView.isFocusable = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // existing GLB sprite cache is IndexedDB-backed
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        webView.addJavascriptInterface(DashboardHeroBridge(), "DashboardHeroBridge")
        webView.webViewClient = DashboardVehicleWebViewClient()
        webView.loadUrl(DASHBOARD_VEHICLE_HERO_URL)
    }

    private inner class DashboardHeroBridge {
        @JavascriptInterface
        fun onVehicleHeroReady(source: String) {
            mainHandler.post {
                val webView = heroVehicleWebView ?: return@post
                if (!isAdded || view == null) return@post
                webView.visibility = View.VISIBLE
                webView.animate().cancel()
                webView.animate().alpha(1f).setDuration(180L).start()
                android.util.Log.d("DashboardVehicle", "Selected vehicle hero ready via $source")
            }
        }

        @JavascriptInterface
        fun onVehicleHeroFailed(reason: String) {
            android.util.Log.w("DashboardVehicle", "Selected vehicle hero unavailable: $reason")
        }
    }

    /**
     * WebView honors the system sing-box proxy even for loopback traffic.
     * Route only the daemon origin directly and leave remote model downloads to
     * the normal WebView network stack. Top-level navigation is locked to the
     * local renderer page so the JavaScript bridge is never exposed elsewhere.
     */
    private inner class DashboardVehicleWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return true
            return !(uri.host == "127.0.0.1" && uri.port == com.overdrive.app.daemon.CameraDaemon.HTTP_PORT)
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val req = request ?: return null
            val uri = req.url
            if (uri.host != "127.0.0.1"
                || uri.port != com.overdrive.app.daemon.CameraDaemon.HTTP_PORT) return null

            var conn: HttpURLConnection? = null
            return try {
                val path = buildString {
                    append(uri.encodedPath ?: "/")
                    if (!uri.encodedQuery.isNullOrEmpty()) append('?').append(uri.encodedQuery)
                }
                conn = com.overdrive.app.util.DaemonHttpClient.open(
                    path,
                    req.method ?: "GET",
                    HERO_HTTP_CONNECT_TIMEOUT_MS,
                    HERO_HTTP_READ_TIMEOUT_MS
                )
                req.requestHeaders.forEach { (name, value) ->
                    if (!name.equals("Cookie", ignoreCase = true)
                        && !name.equals("Host", ignoreCase = true)
                        && !name.equals("Accept-Encoding", ignoreCase = true)) {
                        conn?.setRequestProperty(name, value)
                    }
                }
                val code = conn!!.responseCode
                val stream = if (code in 200..399) conn!!.inputStream else conn!!.errorStream
                if (stream == null) {
                    conn!!.disconnect()
                    conn = null
                    return unavailableHeroResponse("HTTP $code")
                }
                val rawType = conn!!.contentType.orEmpty()
                val mime = rawType.substringBefore(';').takeIf { it.isNotBlank() }
                    ?: URLConnection.guessContentTypeFromName(uri.lastPathSegment)
                    ?: "application/octet-stream"
                val encoding = when {
                    rawType.contains("charset=", ignoreCase = true) -> rawType.substringAfter("charset=").trim()
                    mime.startsWith("text/") || mime == "application/javascript" || mime == "application/json" -> "utf-8"
                    else -> null
                }
                val activeConnection = conn!!
                val response = WebResourceResponse(
                    mime,
                    encoding,
                    object : FilterInputStream(stream) {
                        override fun close() {
                            try { super.close() } finally { activeConnection.disconnect() }
                        }
                    }
                )
                response.setStatusCodeAndReasonPhrase(code, activeConnection.responseMessage ?: "OK")
                response.responseHeaders = activeConnection.headerFields
                    .filterKeys { it != null }
                    .filterKeys { name ->
                        !name.equals("Content-Encoding", ignoreCase = true)
                            && !name.equals("Transfer-Encoding", ignoreCase = true)
                            && !name.equals("Connection", ignoreCase = true)
                    }
                    .mapValues { it.value.lastOrNull().orEmpty() }
                conn = null // stream owns disconnect from this point
                response
            } catch (t: Throwable) {
                conn?.disconnect()
                unavailableHeroResponse(t.message ?: "daemon unavailable")
            }
        }
    }

    private fun unavailableHeroResponse(reason: String): WebResourceResponse {
        val body = reason.toByteArray(Charsets.UTF_8)
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(body)).apply {
            setStatusCodeAndReasonPhrase(503, "Service Unavailable")
            responseHeaders = mapOf("Cache-Control" to "no-store")
        }
    }

    private fun wireClicks() {
        // Tile taps deep-link to peer rail destinations. Use the same M3
        // fade-through motion the rail itself uses so the user can't tell
        // whether they tapped the tile or the rail icon.
        val fadeThrough = com.overdrive.app.ui.util.NavOptionsExt.m3FadeThrough()
        val openTodayRecordings = View.OnClickListener {
            val args = Bundle().apply {
                putBoolean(RecordingsFragment.ARG_TODAY_ONLY, true)
            }
            findNavController().navigate(R.id.recordingsFragment, args, fadeThrough)
        }
        metricRecordings.setOnClickListener(openTodayRecordings)
        metricTunnel.setOnClickListener {
            if (remoteAccessDetails != null) {
                toggleRemoteAccessDetails()
            } else {
                findNavController().navigate(R.id.daemonsFragment, null, fadeThrough)
            }
        }
        cardDaemons.setOnClickListener {
            findNavController().navigate(R.id.daemonsFragment, null, fadeThrough)
        }
        val openLive = View.OnClickListener {
            findNavController().navigate(R.id.liveViewFragment, null, fadeThrough)
        }
        quickLive.setOnClickListener(openLive)
        metricVehicle?.setOnClickListener { showVehicleCapacityDialog() }

        btnCloseRemoteAccess?.setOnClickListener { setRemoteAccessDetailsVisible(false) }
        btnToggleToken.setOnClickListener { toggleTokenVisibility() }
        btnCopyToken.setOnClickListener { copyTokenToClipboard() }
        btnRegenerateToken.setOnClickListener { showRegenerateConfirmation() }
    }

    private fun toggleRemoteAccessDetails() {
        val details = remoteAccessDetails ?: return
        setRemoteAccessDetailsVisible(details.visibility != View.VISIBLE)
    }

    private fun setRemoteAccessDetailsVisible(visible: Boolean) {
        val details = remoteAccessDetails ?: return
        details.animate().cancel()

        if (visible) {
            details.alpha = 0f
            details.visibility = View.VISIBLE
            details.animate()
                .alpha(1f)
                .setDuration(180L)
                .start()

            (view as? androidx.core.widget.NestedScrollView)?.post {
                (view as? androidx.core.widget.NestedScrollView)
                    ?.smoothScrollTo(0, details.top)
            }
        } else {
            details.animate()
                .alpha(0f)
                .setDuration(140L)
                .withEndAction {
                    details.visibility = View.GONE
                    details.alpha = 1f
                }
                .start()
        }
    }

    private fun observeViewModels() {
        // Daemon health drives the hero subtitle, the services tile, and chip rebuild.
        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            val running = states.values.count { it.status == DaemonStatus.RUNNING }
            val total = states.size
            tvDaemonsStatus.text = getString(R.string.dashboard_daemons_running, running, total)
            renderServiceSegments(running)
            // Hero tile alert vs. ok is driven only by *core* daemons — tunnels
            // and bots are opt-in services and missing them shouldn't paint the
            // dashboard red. STARTING counts as ok so the hero flips green the
            // moment a daemon is being launched, without waiting for RUNNING.
            updateHeroSubtitle(running, total, computeCoreHealth(states))
            rebuildTunnelChips()
            updateTunnelTile()
            refreshHeroChips()
        }

        // Tunnel URL → tile state + chip refresh.
        val rebuild = Observer<String?> { _ ->
            rebuildTunnelChips()
            updateTunnelTile()
            refreshHeroChips()
        }
        daemonsViewModel.cloudflaredController.tunnelUrl.observe(viewLifecycleOwner, rebuild)
        daemonsViewModel.zrokController.tunnelUrl.observe(viewLifecycleOwner, rebuild)
        daemonsViewModel.tailscaleController.tunnelUrl.observe(viewLifecycleOwner, rebuild)

        // Recording state → live "● <count>" prefix on the recordings tile.
        // The numeric count itself comes from refreshMetricsTiles() below; this
        // observer just toggles the red-dot prefix without re-walking the disk.
        recordingViewModel.isRecording.observe(viewLifecycleOwner) { _ ->
            renderRecordingsValue()
            refreshHeroChips()
        }
    }

    private fun renderServiceSegments(running: Int) {
        val activeSegments = running.coerceIn(0, SERVICE_SEGMENT_COUNT)
        serviceSegments.forEachIndexed { index, segment ->
            segment.setBackgroundResource(
                if (index < activeSegments) {
                    R.drawable.dashboard_service_segment_active
                } else {
                    R.drawable.dashboard_service_segment_inactive
                }
            )
        }
    }

    /**
     * Push the latest tunnel / services / recording status into the three
     * hero chips. Each chip just mirrors the corresponding metric tile
     * value, but at the top of the hero they're discoverable at-a-glance
     * without needing to scan the 5-tile grid.
     *
     * Lazy-tolerates absent chips (landscape variant has no hero chips).
     */
    private fun refreshHeroChips() {
        heroChipTunnel?.text = metricTunnelValue.text
        heroChipServices?.text = tvDaemonsStatus.text
        val recording = recordingViewModel.isRecording.value == true
        heroChipRecording?.text = if (recording) {
            getString(R.string.dashboard_chip_recording_active)
        } else {
            getString(R.string.dashboard_chip_recording_idle)
        }
    }

    // ============== Metric tiles (storage + today's recordings) ==============

    /**
     * Walks the recordings directories on a background thread via the
     * shared [RecordingScanner], then posts today's clip count back.
     *
     * Uses the same source-of-truth as [RecordingsFragment] so the dashboard
     * tile and the recordings page can never disagree. The scanner already
     * walks active + alternate + legacy paths for cam_* / surveillance /
     * proximity, dedupes by filename, and exposes parsed-from-filename
     * timestamps — counting via mtime here would have missed every clip
     * whose file was copied after capture (different mtime than filename).
     */
    private fun refreshMetricsTiles() {
        val ctx = context?.applicationContext ?: return
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }

        executor.execute {
            var clipCountToday = 0
            try {
                val startOfDayMs = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                clipCountToday = RecordingScanner.scanRecordings(ctx)
                    .count { it.timestamp >= startOfDayMs }
            } catch (_: Throwable) {
                // Leave defaults — still post so the tile updates off "—".
            }

            mainHandler.post {
                if (!isAdded || view == null) return@post
                // Tile is a single-line label so it never ellipsizes on
                // longer locales (fr/de). Storage figure used to be appended
                // here ("Today's recordings · 8.4 GB"); we dropped it because
                // localized strings made the line overflow on the BYD tile
                // width. Storage lives in its own surfaces.
                metricStorageValue.text = getString(R.string.dashboard_metric_recordings)

                todayClipCount = clipCountToday
                renderRecordingsValue()
            }
        }
    }

    /**
     * Renders the recordings tile value from the cached clip count + the
     * current recording state. Pulled into a helper so both the metric
     * refresh and the isRecording observer can call it without duplicating
     * the format logic.
     */
    private fun renderRecordingsValue() {
        if (!::metricRecordingsValue.isInitialized) return
        val isRec = recordingViewModel.isRecording.value == true
        metricRecordingsValue.text = if (isRec) {
            getString(R.string.dashboard_recordings_value_live, todayClipCount)
        } else {
            todayClipCount.toString()
        }
    }

    private fun updateHeroSubtitle(running: Int, total: Int, coreHealth: CoreHealth) {
        // Cache the daemon summary so the carousel can fall through to it when
        // no real insights have data. This keeps the legacy "X of Y services
        // online" line as the safety net the user has always seen.
        lastDaemonRunning = running
        lastDaemonTotal = total
        // If insights are already populated, the carousel owns the subtitle.
        // Otherwise show the daemon-summary fallback immediately so the line
        // doesn't read stale text while we wait for the first build to finish.
        if (insights.isEmpty()) {
            heroSubtitle.text = daemonFallbackText(running, total)
        }
        applyGreetingTint(coreHealth)
    }

    /**
     * Express core health as a precise cockpit signal instead of flooding the
     * largest card with a green/red container fill. The neutral surface keeps
     * hierarchy stable; the edge and status dot carry the live state.
     */
    private fun applyGreetingTint(coreHealth: CoreHealth) {
        if (!::heroCard.isInitialized) return
        val ctx = context ?: return
        val accent = when (coreHealth) {
            CoreHealth.UNKNOWN -> resolveAttrColor(
                ctx,
                com.google.android.material.R.attr.colorOutline
            ) ?: androidx.core.content.ContextCompat.getColor(ctx, R.color.status_stopped)
            CoreHealth.OK ->
                androidx.core.content.ContextCompat.getColor(ctx, R.color.status_success)
            CoreHealth.ALERT ->
                androidx.core.content.ContextCompat.getColor(ctx, R.color.status_error)
        }
        resolveAttrColor(
            ctx,
            com.google.android.material.R.attr.colorSurfaceContainerLow
        )?.let { heroCard.setCardBackgroundColor(it) }
        heroCard.strokeColor = accent
        heroStateDot?.backgroundTintList =
            android.content.res.ColorStateList.valueOf(accent)
        resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurface)
            ?.let { heroGreeting.setTextColor(it) }
        resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant)
            ?.let { heroSubtitle.setTextColor(it) }
    }

    private enum class CoreHealth { UNKNOWN, OK, ALERT }

    /**
     * Reduce the daemon-state map to a tri-state for the hero tint.
     *
     * Rule: green when every core daemon is started (RUNNING / STARTING /
     * STOPPING — anything that means a process exists or is being managed),
     * red when at least one core daemon is STOPPED. ERROR is folded in
     * with STOPPED for tinting purposes since either way the daemon isn't
     * doing its job.
     *
     * "Core" = Camera + Sentry + ACC Sentry. Sing-box, tunnels, and the
     * Telegram bot are all opt-in — they don't gate the hero tint.
     */
    private fun computeCoreHealth(states: Map<DaemonType, DaemonState>?): CoreHealth {
        if (states.isNullOrEmpty()) return CoreHealth.UNKNOWN
        val core = setOf(
            DaemonType.CAMERA_DAEMON,
            DaemonType.SENTRY_DAEMON,
            DaemonType.ACC_SENTRY_DAEMON
        )
        var sawCore = false
        for ((type, state) in states) {
            if (type !in core) continue
            sawCore = true
            if (state.status == DaemonStatus.STOPPED || state.status == DaemonStatus.ERROR) {
                return CoreHealth.ALERT
            }
        }
        return if (sawCore) CoreHealth.OK else CoreHealth.UNKNOWN
    }

    private fun resolveAttrColor(ctx: Context, attr: Int): Int? {
        val tv = android.util.TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data else null
    }

    private fun daemonFallbackText(running: Int, total: Int): CharSequence = when {
        total == 0 -> getString(R.string.dashboard_subtitle_no_tunnel)
        running == total -> getString(R.string.dashboard_subtitle_all_systems)
        else -> getString(R.string.dashboard_subtitle_some_offline, running, total)
    }

    // ============== Insights carousel ==============

    /**
     * Re-build the insight list off the main thread. Posts the result back to
     * the UI which restarts the rotation from index 0.
     */
    private fun rebuildInsightsAsync() {
        val provider = insightsProvider ?: return
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        val visitCount = if (firstVisitCount >= 0) firstVisitCount else 0
        executor.execute {
            val built = try {
                provider.build(visitCount)
            } catch (_: Throwable) {
                emptyList()
            }
            mainHandler.post {
                if (!isAdded || view == null) return@post
                applyInsightList(built)
            }
        }
    }

    private fun applyInsightList(built: List<DashboardInsight>) {
        cancelInsightCallbacks()
        insights = built
        insightIndex = 0
        if (built.isEmpty()) {
            // Static fallback — no rotation. The daemon-summary observer keeps
            // this line accurate as services flip online/offline.
            heroSubtitle.text = daemonFallbackText(lastDaemonRunning, lastDaemonTotal)
            return
        }
        // Show the first insight immediately (no fade — we want it instant on
        // dashboard open / resume). Subsequent transitions cross-fade.
        heroSubtitle.alpha = 0.78f
        heroSubtitle.text = built[0].text
        if (built.size > 1) {
            scheduleNextInsight()
        }
    }

    private fun scheduleNextInsight() {
        if (rotationPaused) return
        if (insights.size <= 1) return
        mainHandler.removeCallbacks(insightRotateRunnable)
        mainHandler.postDelayed(insightRotateRunnable, INSIGHT_HOLD_MS)
    }

    private fun rotateInsight() {
        if (!isAdded || view == null) return
        if (rotationPaused) return
        if (insights.size <= 1) return
        val nextIdx = (insightIndex + 1) % insights.size
        val next = insights[nextIdx]
        // 250ms cross-fade: fade current to alpha=0, swap text, fade back to
        // the resting 0.78 the layout uses for the subtitle. M3 standard
        // easing is the AccelerateDecelerateInterpolator default on animate().
        heroSubtitle.animate()
            .alpha(0f)
            .setDuration(INSIGHT_FADE_MS)
            .withEndAction {
                if (!isAdded || view == null) return@withEndAction
                heroSubtitle.text = next.text
                heroSubtitle.animate()
                    .alpha(0.78f)
                    .setDuration(INSIGHT_FADE_MS)
                    .start()
                insightIndex = nextIdx
                scheduleNextInsight()
            }
            .start()
    }

    private fun cancelInsightCallbacks() {
        mainHandler.removeCallbacks(insightRotateRunnable)
        mainHandler.removeCallbacks(insightResumeRunnable)
        // Cancel any in-flight fade so onPause / onDestroyView don't leave a
        // dangling animation that targets a TextView whose host is gone.
        if (::heroSubtitle.isInitialized) {
            heroSubtitle.animate().cancel()
            heroSubtitle.alpha = 0.78f
        }
    }

    /**
     * Long-press on the subtitle pauses the rotation; release resumes it after
     * a 5 s grace period so the user has time to actually read the line they
     * paused on. We use ACTION_DOWN / ACTION_UP (not OnLongClick) so the
     * pause kicks in immediately, not after the system long-press timeout.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun wireSubtitleTouchPause() {
        if (!::heroSubtitle.isInitialized) return
        heroSubtitle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    rotationPaused = true
                    mainHandler.removeCallbacks(insightRotateRunnable)
                    mainHandler.removeCallbacks(insightResumeRunnable)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(insightResumeRunnable)
                    mainHandler.postDelayed(insightResumeRunnable, INSIGHT_RESUME_AFTER_MS)
                }
            }
            // Don't consume — let click-through still work for accessibility.
            false
        }
    }

    private fun updateTunnelTile() {
        val states = daemonsViewModel.daemonStates.value
        val display = com.overdrive.app.ui.model.TunnelDisplayPolicy.resolve(
            daemonsViewModel.zrokController.tunnelUrl.value,
            daemonsViewModel.cloudflaredController.tunnelUrl.value,
            daemonsViewModel.tailscaleController.tunnelUrl.value,
            states?.get(DaemonType.ZROK_TUNNEL)?.status,
            states?.get(DaemonType.CLOUDFLARED_TUNNEL)?.status,
            states?.get(DaemonType.TAILSCALE_TUNNEL)?.status,
        )

        when (display.kind) {
            com.overdrive.app.ui.model.TunnelDisplayPolicy.Kind.ONLINE -> {
                metricTunnelValue.text = getString(R.string.dashboard_tunnel_online)
                tunnelStateDot.setBackgroundResource(R.drawable.status_dot_online)
            }
            com.overdrive.app.ui.model.TunnelDisplayPolicy.Kind.STARTING_ZROK,
            com.overdrive.app.ui.model.TunnelDisplayPolicy.Kind.STARTING_CLOUDFLARED,
            com.overdrive.app.ui.model.TunnelDisplayPolicy.Kind.STARTING_TAILSCALE,
            com.overdrive.app.ui.model.TunnelDisplayPolicy.Kind.WAITING_FOR_URL -> {
                metricTunnelValue.text = getString(R.string.dashboard_tunnel_connecting)
                // Amber, matching the toolbar pill for these states.
                tunnelStateDot.setBackgroundResource(R.drawable.status_dot_starting)
            }
            com.overdrive.app.ui.model.TunnelDisplayPolicy.Kind.STOPPING -> {
                metricTunnelValue.text = getString(R.string.dashboard_tunnel_tile_stopping)
                tunnelStateDot.setBackgroundResource(R.drawable.status_dot_starting)
            }
            com.overdrive.app.ui.model.TunnelDisplayPolicy.Kind.FAILED -> {
                metricTunnelValue.text = getString(R.string.dashboard_tunnel_tile_failed)
                tunnelStateDot.setBackgroundResource(R.drawable.status_dot_offline)
            }
            com.overdrive.app.ui.model.TunnelDisplayPolicy.Kind.HIDDEN -> {
                metricTunnelValue.text = getString(R.string.dashboard_tunnel_offline)
                tunnelStateDot.setBackgroundResource(R.drawable.status_dot_offline)
            }
        }
    }

    // ============== Tunnel chips + QR ==============

    private fun rebuildTunnelChips() {
        val available = collectAvailableTunnels()

        if (available.isEmpty()) {
            chipGroupTunnels.removeAllViews()
            chipGroupTunnels.visibility = View.GONE
            selectedTunnel = null
            renderQr(null)
            return
        }

        val newSelection = selectedTunnel?.takeIf { prev -> available.any { it.first == prev } }
            ?: available.first().first
        selectedTunnel = newSelection

        val currentTags = (0 until chipGroupTunnels.childCount)
            .map { (chipGroupTunnels.getChildAt(it) as Chip).tag as DaemonType }
        val newTags = available.map { it.first }
        if (currentTags != newTags) {
            chipGroupTunnels.setOnCheckedStateChangeListener(null)
            chipGroupTunnels.removeAllViews()
            available.forEach { (type, _) ->
                val chip = Chip(requireContext()).apply {
                    id = View.generateViewId()
                    tag = type
                    text = labelFor(type)
                    isCheckable = true
                    isCheckedIconVisible = false
                }
                chipGroupTunnels.addView(chip)
            }
            chipGroupTunnels.setOnCheckedStateChangeListener { group, ids ->
                val checkedId = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
                val chip = group.findViewById<Chip>(checkedId) ?: return@setOnCheckedStateChangeListener
                val type = chip.tag as? DaemonType ?: return@setOnCheckedStateChangeListener
                if (type != selectedTunnel) {
                    selectedTunnel = type
                    renderQr(urlFor(type))
                }
            }
        }

        for (i in 0 until chipGroupTunnels.childCount) {
            val chip = chipGroupTunnels.getChildAt(i) as Chip
            chip.isChecked = (chip.tag as DaemonType) == newSelection
        }

        chipGroupTunnels.visibility = if (available.size > 1) View.VISIBLE else View.GONE
        renderQr(urlFor(newSelection))
    }

    private fun collectAvailableTunnels(): List<Pair<DaemonType, String>> {
        val list = mutableListOf<Pair<DaemonType, String>>()
        val states = daemonsViewModel.daemonStates.value
        daemonsViewModel.cloudflaredController.tunnelUrl.value
            ?.takeIf {
                com.overdrive.app.ui.model.TunnelDisplayPolicy.isActiveUrl(
                    it, states?.get(DaemonType.CLOUDFLARED_TUNNEL)?.status)
            }
            ?.let { list.add(DaemonType.CLOUDFLARED_TUNNEL to it) }
        daemonsViewModel.zrokController.tunnelUrl.value
            ?.takeIf {
                com.overdrive.app.ui.model.TunnelDisplayPolicy.isActiveUrl(
                    it, states?.get(DaemonType.ZROK_TUNNEL)?.status)
            }
            ?.let { list.add(DaemonType.ZROK_TUNNEL to it) }
        daemonsViewModel.tailscaleController.tunnelUrl.value
            ?.takeIf {
                com.overdrive.app.ui.model.TunnelDisplayPolicy.isActiveUrl(
                    it, states?.get(DaemonType.TAILSCALE_TUNNEL)?.status)
            }
            ?.let { list.add(DaemonType.TAILSCALE_TUNNEL to it) }
        return list
    }

    private fun urlFor(type: DaemonType): String? = when (type) {
        DaemonType.CLOUDFLARED_TUNNEL -> daemonsViewModel.cloudflaredController.tunnelUrl.value
        DaemonType.ZROK_TUNNEL -> daemonsViewModel.zrokController.tunnelUrl.value
        DaemonType.TAILSCALE_TUNNEL -> daemonsViewModel.tailscaleController.tunnelUrl.value
        else -> null
    }

    private fun labelFor(type: DaemonType): String = when (type) {
        DaemonType.CLOUDFLARED_TUNNEL -> getString(R.string.tunnel_label_cloudflared)
        DaemonType.ZROK_TUNNEL -> getString(R.string.tunnel_label_zrok)
        DaemonType.TAILSCALE_TUNNEL -> getString(R.string.tunnel_label_tailscale)
        else -> type.localizedName(requireContext())
    }

    private fun renderQr(url: String?) {
        if (url.isNullOrEmpty()) {
            showPlaceholder()
            return
        }
        try {
            val qrBitmap = QrCodeGenerator.generate(url, 400)
            if (qrBitmap != null) {
                ivQrCode.setImageBitmap(qrBitmap)
                ivQrCode.visibility = View.VISIBLE
                tvQrPlaceholder.visibility = View.GONE
                tvUrl.text = url
                tvUrl.visibility = View.VISIBLE
            } else {
                showPlaceholder()
            }
        } catch (e: Exception) {
            showPlaceholder()
        }
    }

    private fun showPlaceholder() {
        ivQrCode.setImageDrawable(null)
        ivQrCode.visibility = View.VISIBLE
        tvQrPlaceholder.visibility = View.VISIBLE
        tvQrPlaceholder.text = getTunnelPlaceholderText()
        tvUrl.visibility = View.GONE
    }

    private fun getTunnelPlaceholderText(): String {
        val states = daemonsViewModel.daemonStates.value ?: return getString(R.string.dashboard_no_tunnel)
        val cfState = states[DaemonType.CLOUDFLARED_TUNNEL]
        val zrokState = states[DaemonType.ZROK_TUNNEL]
        val tailscaleState = states[DaemonType.TAILSCALE_TUNNEL]
        return when {
            zrokState?.status == DaemonStatus.STARTING -> getString(R.string.dashboard_starting_zrok)
            cfState?.status == DaemonStatus.STARTING -> getString(R.string.dashboard_starting_cloudflared)
            tailscaleState?.status == DaemonStatus.STARTING -> getString(R.string.dashboard_starting_tailscale)
            zrokState?.status == DaemonStatus.RUNNING -> getString(R.string.dashboard_waiting_url)
            cfState?.status == DaemonStatus.RUNNING -> getString(R.string.dashboard_waiting_url)
            tailscaleState?.status == DaemonStatus.RUNNING -> getString(R.string.dashboard_waiting_url)
            else -> getString(R.string.dashboard_no_tunnel)
        }
    }

    // ============== Auth (access code) ==============

    private fun loadAuthState() {
        try {
            // getState()/initialize() can return null on a fresh install
            // before the daemon has populated the unified config — in that
            // window the access code genuinely doesn't exist yet. Show
            // the masked placeholder and schedule a short poll: the
            // daemon writes the canonical secret within ~1-2s of boot,
            // and we want the dashboard tile to fill in without the user
            // having to navigate away.
            val state = AuthManager.getState() ?: AuthManager.initialize()
            if (state != null) {
                updateTokenDisplay(state.secret)
            } else {
                tvDeviceToken.text = getString(R.string.dashboard_token_masked)
                scheduleAuthRetry(attempt = 1)
            }
        } catch (e: Exception) {
            tvDeviceToken.text = getString(R.string.dashboard_token_masked)
        }
    }

    private fun scheduleAuthRetry(attempt: Int) {
        // Cap the retry storm at ~10 seconds total (10 attempts × 1s).
        // Anything beyond that is a real config problem, not a daemon
        // boot race; falling back to user-driven onResume()/regenerate
        // is fine.
        if (attempt > 10) return
        mainHandler.postDelayed({
            if (!isAdded) return@postDelayed
            val state = AuthManager.getState()
            if (state != null) {
                updateTokenDisplay(state.secret)
            } else {
                scheduleAuthRetry(attempt + 1)
            }
        }, 1000)
    }

    private fun updateTokenDisplay(secret: String) {
        tvDeviceToken.text = if (isTokenVisible) secret else getString(R.string.dashboard_token_masked)
    }

    private fun toggleTokenVisibility() {
        isTokenVisible = !isTokenVisible
        AuthManager.getState()?.let { updateTokenDisplay(it.secret) }
        btnToggleToken.setImageResource(
            if (isTokenVisible) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_view
        )
    }

    private fun copyTokenToClipboard() {
        val state = AuthManager.getState() ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.clip_label_access_code), state.secret)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), getString(R.string.toast_access_code_copied), Toast.LENGTH_SHORT).show()
    }

    private fun showRegenerateConfirmation() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_warning)
            .setTitle(getString(R.string.dialog_regenerate_token_title))
            .setMessage(getString(R.string.dialog_regenerate_token_message))
            .setPositiveButton(getString(R.string.dialog_regenerate)) { _, _ -> regenerateToken() }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun regenerateToken() {
        val newToken = AuthManager.regenerateToken()
        // Use the lifecycle-managed metricsExecutor (shut down in onDestroyView)
        // instead of a bare Thread that would outlive the fragment and leak
        // its Activity reference. The applicationContext for the Toast also
        // bypasses requireContext()'s detach-aware throw.
        val ctx = context?.applicationContext ?: return
        if (newToken == null) {
            // Persistence failed — usually means the daemon hasn't booted
            // yet so the unified config file isn't writable from app UID.
            // Better to surface this than to claim success and leave the
            // user wondering why login still rejects the new code.
            Toast.makeText(ctx, ctx.getString(R.string.toast_token_regenerated_restart), Toast.LENGTH_LONG).show()
            loadAuthState()
            return
        }
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            val msgRes = try {
                val client = CameraDaemonClient()
                if (client.connect()) {
                    val ok = client.invalidateAuthCacheSync()
                    client.disconnect()
                    if (ok) R.string.toast_token_regenerated_logged_out
                    else R.string.toast_token_regenerated_restart
                } else {
                    R.string.toast_token_regenerated_no_notify
                }
            } catch (_: Exception) {
                R.string.toast_token_regenerated
            }
            // Use the application context for Toast — survives fragment detach
            // and is the recommended pattern for "fire-and-forget" notifications
            // from a background thread.
            mainHandler.post {
                if (isAdded) {
                    Toast.makeText(ctx, ctx.getString(msgRes), Toast.LENGTH_SHORT).show()
                }
            }
        }
        loadAuthState()
    }

    // ============== Vehicle tile ==============

    /**
     * Read the selected model plus live SOC / estimated SOH / nominal capacity
     * and render them as a compact Vehicle-card summary.
     *
     * Calls run on a worker thread. A short bounded retry covers the common
     * post-install race where the fragment resumes before the daemon is ready;
     * a generation token prevents an old attempt updating a replacement view.
     */
    private fun refreshVehicleTile() {
        val generation = ++vehicleRefreshGeneration
        refreshVehicleTile(generation, 0)
    }

    private fun refreshVehicleTile(generation: Int, attempt: Int) {
        val tile = metricVehicleValue ?: return
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            var nominalKwh = 0.0
            var modelId: String? = null
            var socPercent: Double? = null
            var sohPercent: Double? = null
            var sohSource: String? = null
            var rangeKm: Double? = null
            var distanceUnit = "km"
            var charging = false
            var plugged = false
            var chargeFull = false
            var chargeFault = false
            var timeToFullMin: Int? = null
            var timeToFullSource: String? = null
            var completionEpochMs: Long? = null
            var completionTargetPercent: Int? = null

            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh", "GET", 1500, 2500)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    val nominal = json.optDouble("nominalCapacityKwh", Double.NaN)
                    if (nominal.isFinite() && nominal > 0) nominalKwh = nominal
                    val source = json.optString("displaySource", "unavailable")
                    val soh = json.optDouble("displaySoh", Double.NaN)
                    if (source != "unavailable" && soh.isFinite() && soh in 0.0..100.0) {
                        sohPercent = soh
                        sohSource = source
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) { /* retry below if the daemon is still starting */ }

            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/status", "GET", 1500, 2500)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val status = org.json.JSONObject(body)
                    val soc = status.optJSONObject("soc")
                        ?.optDouble("percent", Double.NaN)
                    if (soc != null && soc.isFinite() && soc in 0.0..100.0) {
                        socPercent = soc
                    }
                    val range = status.optJSONObject("range")
                        ?.optDouble("elecRangeKm", Double.NaN)
                    if (range != null && range.isFinite() && range >= 0.0) {
                        rangeKm = range
                    }
                    distanceUnit = status.optString("distanceUnit", "km")
                    status.optJSONObject("charging")?.let { charge ->
                        charging = charge.optBoolean("charging", false)
                        plugged = charge.optBoolean("plugged", false)
                        chargeFull = charge.optBoolean("full", false)
                        chargeFault = charge.optBoolean("fault", false)
                        if (!charge.isNull("timeToFullMin")) {
                            charge.optInt("timeToFullMin", -1).takeIf { it > 0 }?.let {
                                timeToFullMin = it
                            }
                        }
                        if (!charge.isNull("timeToFullSource")) {
                            timeToFullSource = charge.optString("timeToFullSource", "")
                                .takeIf { it.isNotEmpty() }
                        }
                        if (!charge.isNull("completionEpochMs")) {
                            charge.optLong("completionEpochMs", -1L).takeIf { it > 0 }?.let {
                                completionEpochMs = it
                            }
                        }
                        if (!charge.isNull("completionTargetPercent")) {
                            charge.optInt("completionTargetPercent", -1).takeIf { it in 1..100 }?.let {
                                completionTargetPercent = it
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) { /* retry below if the daemon is still starting */ }

            // The detailed SOH endpoint normally supplies nominal capacity too.
            // Keep the small nominal endpoint as a fallback while SOH is seeding.
            if (nominalKwh <= 0) {
                try {
                    val conn = com.overdrive.app.util.DaemonHttpClient.open(
                        "/api/performance/soh/nominal", "GET", 1500, 2500)
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = org.json.JSONObject(body)
                        if (!json.isNull("nominalKwh")) {
                            nominalKwh = json.optDouble("nominalKwh", 0.0)
                        }
                    }
                    conn.disconnect()
                } catch (_: Throwable) { /* keep defaults */ }
            }

            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/models/selected", "GET", 1500, 2500)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    val m = when {
                        json.has("selectedModelId") && !json.isNull("selectedModelId") ->
                            json.optString("selectedModelId", "")
                        json.has("modelSource")
                                && json.optString("modelSource", "unset") == "unset" -> ""
                        // Backward compatibility with an older daemon that does
                        // not expose selection provenance yet.
                        else -> json.optString("modelId", "")
                    }
                    if (m.isNotEmpty()) modelId = m
                }
                conn.disconnect()
            } catch (_: Throwable) {}

            mainHandler.post {
                if (!isAdded || view == null || generation != vehicleRefreshGeneration) {
                    return@post
                }

                heroSocValue?.text = socPercent?.let {
                    getString(R.string.dashboard_vehicle_soc_value, it)
                } ?: getString(R.string.dashboard_soc_pending)
                heroSocProgress?.setProgressCompat(
                    socPercent?.roundToInt()?.coerceIn(0, 100) ?: 0,
                    socPercent != null
                )
                heroSohValue?.text = sohPercent?.let {
                    getString(R.string.dashboard_vehicle_soh_value, it)
                } ?: getString(R.string.dashboard_soh_pending)
                heroRangeValue?.text = rangeKm?.let { kilometres ->
                    if (distanceUnit.equals("mi", ignoreCase = true)) {
                        getString(
                            R.string.dashboard_vehicle_range_miles,
                            kilometres * KM_TO_MILES
                        )
                    } else {
                        getString(R.string.dashboard_vehicle_range_km, kilometres)
                    }
                } ?: getString(R.string.dashboard_range_pending)
                renderHeroChargeCompletion(
                    charging,
                    plugged,
                    chargeFull,
                    chargeFault,
                    timeToFullMin,
                    timeToFullSource,
                    completionEpochMs,
                    completionTargetPercent
                )

                val detail = metricVehicleDetail
                if (detail != null) {
                    if (modelId != null || nominalKwh > 0) {
                        tile.text = modelId?.let { modelDisplayName(it) }
                            ?: String.format("%.1f kWh", nominalKwh)
                    } else {
                        tile.text = getString(R.string.dashboard_vehicle_tap_to_set)
                    }

                    val metrics = formatVehicleMetrics(
                        socPercent, sohPercent, sohSource, nominalKwh
                    )
                    detail.text = metrics
                    detail.visibility = if (metrics.isNotEmpty()) View.VISIBLE else View.GONE
                    metricVehicleStatus?.text = if (
                        modelId != null || nominalKwh > 0 || socPercent != null || rangeKm != null
                    ) {
                        getString(R.string.dashboard_vehicle_telemetry_linked)
                    } else {
                        getString(R.string.dashboard_vehicle_telemetry_pending)
                    }
                } else {
                    if (nominalKwh > 0) {
                        tile.text = if (modelId != null) {
                            getString(
                                R.string.dashboard_vehicle_summary,
                                nominalKwh,
                                modelDisplayName(modelId)
                            )
                        } else {
                            String.format("%.1f kWh", nominalKwh)
                        }
                    } else {
                        tile.text = getString(R.string.dashboard_vehicle_tap_to_set)
                    }
                }

                val needsRetry = nominalKwh <= 0 || socPercent == null || sohPercent == null
                if (needsRetry && attempt < VEHICLE_REFRESH_MAX_RETRIES) {
                    mainHandler.postDelayed({
                        if (isAdded && view != null && generation == vehicleRefreshGeneration) {
                            refreshVehicleTile(generation, attempt + 1)
                        }
                    }, VEHICLE_REFRESH_RETRY_MS)
                }
            }
        }
    }

    private fun renderHeroChargeCompletion(
        charging: Boolean,
        plugged: Boolean,
        full: Boolean,
        fault: Boolean,
        minutes: Int?,
        source: String?,
        completionMs: Long?,
        targetPercent: Int?,
    ) {
        val label = heroChargeCompletion ?: return
        val text = when {
            fault -> null
            full -> getString(R.string.dashboard_charge_complete)
            plugged && !charging -> getString(R.string.dashboard_charge_waiting)
            charging && minutes != null -> {
                val duration = if (minutes >= 60) {
                    getString(
                        R.string.dashboard_charge_duration_hours,
                        minutes / 60,
                        minutes % 60
                    )
                } else {
                    getString(R.string.dashboard_charge_duration_minutes, minutes)
                }
                val completedAt = completionMs ?: (System.currentTimeMillis() + minutes * 60_000L)
                val clock = android.text.format.DateFormat.getTimeFormat(requireContext())
                    .format(java.util.Date(completedAt))
                val calculated = source ==
                    com.overdrive.app.charging.ChargingCompletionEstimate.SOURCE_CALCULATED
                if (targetPercent != null && targetPercent < 100) {
                    getString(
                        if (calculated) R.string.dashboard_charge_target_completion_calculated
                        else R.string.dashboard_charge_target_completion,
                        duration,
                        targetPercent,
                        clock
                    )
                } else {
                    getString(
                        if (calculated) R.string.dashboard_charge_completion_calculated
                        else R.string.dashboard_charge_completion,
                        duration,
                        clock
                    )
                }
            }
            else -> null
        }
        label.text = text.orEmpty()
        label.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** Lightweight status-only refresh so the cockpit countdown does not freeze. */
    private fun refreshHeroChargeCompletionOnly() {
        if (heroChargeCompletion == null) return
        val generation = vehicleRefreshGeneration
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            var charging = false
            var plugged = false
            var full = false
            var fault = false
            var minutes: Int? = null
            var source: String? = null
            var completionMs: Long? = null
            var targetPercent: Int? = null
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/status", "GET", 1500, 2500)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    org.json.JSONObject(body).optJSONObject("charging")?.let { charge ->
                        charging = charge.optBoolean("charging", false)
                        plugged = charge.optBoolean("plugged", false)
                        full = charge.optBoolean("full", false)
                        fault = charge.optBoolean("fault", false)
                        if (!charge.isNull("timeToFullMin")) {
                            minutes = charge.optInt("timeToFullMin", -1).takeIf { it > 0 }
                        }
                        if (!charge.isNull("timeToFullSource")) {
                            source = charge.optString("timeToFullSource", "").takeIf { it.isNotEmpty() }
                        }
                        if (!charge.isNull("completionEpochMs")) {
                            completionMs = charge.optLong("completionEpochMs", -1L).takeIf { it > 0 }
                        }
                        if (!charge.isNull("completionTargetPercent")) {
                            targetPercent = charge.optInt("completionTargetPercent", -1)
                                .takeIf { it in 1..100 }
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) {
                return@execute
            }
            mainHandler.post {
                if (isAdded && view != null && generation == vehicleRefreshGeneration) {
                    renderHeroChargeCompletion(
                        charging, plugged, full, fault, minutes, source, completionMs, targetPercent
                    )
                }
            }
        }
    }

    private fun formatVehicleMetrics(
        socPercent: Double?,
        sohPercent: Double?,
        sohSource: String?,
        nominalKwh: Double
    ): String {
        val metrics = mutableListOf<String>()
        socPercent?.let {
            metrics.add(getString(R.string.dashboard_vehicle_soc_metric, it))
        }
        sohPercent?.let {
            metrics.add(getString(
                if (sohSource == "oem") R.string.dashboard_vehicle_soh_metric
                else R.string.dashboard_vehicle_soh_metric_estimated,
                it
            ))
        }
        if (nominalKwh > 0) {
            metrics.add(getString(R.string.dashboard_vehicle_capacity_metric, nominalKwh))
        }
        return metrics.joinToString(" · ")
    }

    /**
     * Dialog with capacity input + model dropdown. POSTs to
     * /api/performance/soh/nominal and /api/models/selected.
     *
     * `internal` so the onboarding vehicle chapter can launch the real dialog via
     * MainActivity.openVehicleProfileForOnboarding() rather than reimplementing it.
     */
    internal fun showVehicleCapacityDialog(onFinished: (() -> Unit)? = null): Boolean {
        val ctx = context ?: return false

        // Inflate the M3 layout (outlined inputs + ExposedDropdownMenu).
        val dialogView = layoutInflater.inflate(
            R.layout.dialog_vehicle_capacity, null, false)

        val summaryCapacity = dialogView.findViewById<TextView>(R.id.vehicleSummaryCapacity)
        val summarySoh = dialogView.findViewById<TextView>(R.id.vehicleSummarySoh)
        val summaryEffective = dialogView.findViewById<TextView>(R.id.vehicleSummaryEffective)
        val summaryModel = dialogView.findViewById<TextView>(R.id.vehicleSummaryModel)
        val summaryCalibration = dialogView.findViewById<TextView>(R.id.vehicleSummaryCalibration)
        val summaryDivider = dialogView.findViewById<View>(R.id.vehicleSummaryDivider)
        val capInput = dialogView.findViewById<
            com.google.android.material.textfield.TextInputEditText>(R.id.vehicleCapacityInput)
        val modelDropdown = dialogView.findViewById<
            com.google.android.material.textfield.MaterialAutoCompleteTextView>(
            R.id.vehicleModelDropdown)

        // Track the selected model's id locally (the dropdown's text holds
        // the user-facing title; the id is what we POST). Each entry also
        // carries the manifest's canonical nominalKwh so picking a model
        // can auto-fill the capacity input. The list is refreshed from
        // the manifest below.
        data class ModelEntry(val id: String, val title: String, val nominalKwh: Double)
        val modelEntries = mutableListOf<ModelEntry>()
        var selectedModelId: String? = null
        modelDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position in modelEntries.indices) {
                val entry = modelEntries[position]
                selectedModelId = entry.id
                // Auto-fill the capacity field with the manifest's
                // canonical nominalKwh for this model. The user can still
                // edit it before saving — this is just a sensible starting
                // value rather than leaving the field showing the previous
                // model's number.
                if (entry.nominalKwh > 0) {
                    capInput.setText(String.format("%.1f", entry.nominalKwh))
                }
            }
        }

        // Pre-populate from the current state via background fetch.
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            var initialKwh = 0.0
            val modelIds = mutableListOf<ModelEntry>()
            var initialModelId: String? = null

            // Full status fields for the summary section.
            var nominalKwh = 0.0
            var nominalSource = "unset"
            var displaySoh = -1.0
            var displaySource = "unavailable"
            var estimatedKwh = 0.0
            var statusModelId: String? = null
            var calSoh = 0.0
            var calTs = 0L

            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh/nominal", "GET", 2000, 3000)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    if (!json.isNull("nominalKwh")) initialKwh = json.optDouble("nominalKwh", 0.0)
                }
                conn.disconnect()
            } catch (_: Throwable) {}
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh", "GET", 2000, 3000)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    nominalKwh = json.optDouble("nominalCapacityKwh", 0.0)
                    nominalSource = json.optString("nominalSource", "unset")
                    displaySoh = json.optDouble("displaySoh", -1.0)
                    displaySource = json.optString("displaySource", "unavailable")
                    val est = json.optDouble("estimatedCapacityKwh", -1.0)
                    if (est > 0) estimatedKwh = est
                    if (!json.isNull("modelId")) {
                        statusModelId = json.optString("modelId", "").ifEmpty { null }
                    }
                    val calObj = json.optJSONObject("calibration")
                    if (calObj != null) {
                        calSoh = calObj.optDouble("soh", -1.0)
                        calTs = calObj.optLong("timestampMs", 0L)
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) {}
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/models/manifest", "GET", 2000, 3000)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    val arr = json.optJSONArray("models")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val m = arr.getJSONObject(i)
                            val id = m.optString("id", "")
                            // Manifest uses "name" for the canonical user-facing
                            // string ("BYD Seal", etc.) and falls back to the id.
                            // The previous version read "title" first which never
                            // existed in our manifest, so models showed as the
                            // id text. Ordering: name → title → id.
                            val canonicalTitle = when {
                                m.optString("name", "").isNotEmpty() -> m.optString("name")
                                m.optString("title", "").isNotEmpty() -> m.optString("title")
                                else -> id
                            }
                            val title = if (id.equals("seagull", ignoreCase = true)) {
                                ctx.getString(R.string.vehicle_model_seagull)
                            } else {
                                canonicalTitle
                            }
                            // nominalKwh is the manifest's canonical pack
                            // capacity for this model. 0 means the manifest
                            // doesn't carry a value for it; the dropdown
                            // listener treats 0 as "don't touch the input".
                            val kwh = m.optDouble("nominalKwh", 0.0)
                            if (id.isNotEmpty()) modelIds.add(ModelEntry(id, title, kwh))
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) {}
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/models/selected", "GET", 2000, 3000)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    val m = when {
                        json.has("selectedModelId") && !json.isNull("selectedModelId") ->
                            json.optString("selectedModelId", "")
                        json.has("modelSource")
                                && json.optString("modelSource", "unset") == "unset" -> ""
                        else -> json.optString("modelId", "")
                    }
                    if (m.isNotEmpty()) initialModelId = m
                }
                conn.disconnect()
            } catch (_: Throwable) {}

            val finalNominalKwh = nominalKwh
            val finalNominalSource = nominalSource
            val finalDisplaySoh = displaySoh
            val finalDisplaySource = displaySource
            val finalEstimatedKwh = estimatedKwh
            val finalStatusModelId = statusModelId ?: initialModelId
            val finalCalSoh = calSoh
            val finalCalTs = calTs

            mainHandler.post {
                if (!isAdded || view == null) return@post

                // Capacity input — current user value if any.
                if (initialKwh > 0) capInput.setText(String.format("%.1f", initialKwh))

                // Model dropdown — populate using a Material adapter so the
                // popup uses M3 list-item styling. setText(filter=false) sets
                // the displayed value without filtering the list.
                modelEntries.clear()
                modelEntries.addAll(modelIds)
                val titles = modelIds.map { it.title }
                val adapter = android.widget.ArrayAdapter(
                    ctx,
                    com.google.android.material.R.layout.m3_auto_complete_simple_item,
                    titles)
                modelDropdown.setAdapter(adapter)
                if (initialModelId != null) {
                    val idx = modelIds.indexOfFirst { it.id == initialModelId }
                    if (idx >= 0) {
                        modelDropdown.setText(titles[idx], false)
                        selectedModelId = initialModelId
                    }
                }

                // Populate summary section. Each line shows only when its data
                // is meaningful — keeps the dialog tight when the daemon is
                // still seeding.
                val capacityText = if (finalNominalKwh > 0) {
                    val suffix = when (finalNominalSource) {
                        "user" -> " (" + getString(R.string.soh_dialog_source_user) + ")"
                        "auto" -> " (" + getString(R.string.soh_dialog_source_auto) + ")"
                        else -> ""
                    }
                    String.format("%.1f kWh", finalNominalKwh) + suffix
                } else {
                    getString(R.string.soh_dialog_capacity_not_detected)
                }
                summaryCapacity.text = getString(R.string.vehicle_dialog_summary_capacity, capacityText)
                summaryCapacity.visibility = View.VISIBLE

                val sohText = when {
                    finalDisplaySoh > 0 && finalDisplaySource == "oem" ->
                        String.format("%.1f%% (vehicle)", finalDisplaySoh)
                    finalDisplaySoh > 0 && finalDisplaySource == "live" ->
                        String.format("%.1f%% (live)", finalDisplaySoh)
                    finalDisplaySoh > 0 && finalDisplaySource == "calibration" ->
                        String.format("%.1f%% (from last charge)", finalDisplaySoh)
                    else -> getString(R.string.vehicle_dialog_soh_unavailable)
                }
                summarySoh.text = getString(R.string.vehicle_dialog_summary_soh, sohText)
                summarySoh.visibility = View.VISIBLE

                if (finalEstimatedKwh > 0) {
                    summaryEffective.text = getString(
                        R.string.vehicle_dialog_summary_effective, finalEstimatedKwh)
                    summaryEffective.visibility = View.VISIBLE
                }

                val modelText = if (finalStatusModelId != null) modelDisplayName(finalStatusModelId)
                else getString(R.string.soh_dialog_model_not_selected)
                summaryModel.text = getString(R.string.vehicle_dialog_summary_model, modelText)
                summaryModel.visibility = View.VISIBLE

                if (finalCalSoh > 0 && finalCalTs > 0) {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(java.util.Date(finalCalTs))
                    summaryCalibration.text = getString(
                        R.string.vehicle_dialog_summary_calibration, finalCalSoh, date)
                    summaryCalibration.visibility = View.VISIBLE
                }

                summaryDivider.visibility = View.VISIBLE
            }
        }

        var completionDeferred = false
        var completionSent = false
        fun finishOnce() {
            if (!completionSent) {
                completionSent = true
                onFinished?.invoke()
            }
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            ctx, R.style.Theme_Overdrive_M3_Dialog)
            .setTitle(getString(R.string.vehicle_dialog_title))
            .setView(dialogView)
            // Install button listeners after show so invalid capacity does not
            // trigger AlertDialog's default auto-dismiss behavior.
            .setPositiveButton(getString(R.string.vehicle_dialog_save), null)
            .setNeutralButton(getString(R.string.vehicle_dialog_reset), null)
            .setNegativeButton(getString(R.string.action_cancel), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val raw = capInput.text?.toString()?.trim().orEmpty()
                val kwh = raw.toDoubleOrNull()
                if (kwh == null || kwh < 15.0 || kwh > 120.0) {
                    Toast.makeText(ctx, getString(R.string.vehicle_dialog_invalid_capacity), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                completionDeferred = true
                postNominalAndModel(kwh, selectedModelId) { finishOnce() }
                dialog.dismiss()
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                completionDeferred = true
                postNominal(
                    kwh = null,
                    clearModelSelection = true,
                    onComplete = { finishOnce() },
                )
                dialog.dismiss()
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            // Save/reset wait for both daemon writes. Cancel, back, and
            // outside-tap complete the optional onboarding chapter immediately.
            if (!completionDeferred) finishOnce()
        }
        dialog.show()
        return true
    }

    private fun postNominal(
        kwh: Double?,
        clearModelSelection: Boolean = false,
        onComplete: (() -> Unit)? = null,
    ) {
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh/nominal", "POST", 3000, 5000)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = if (kwh == null) "{\"nominalKwh\":null}" else "{\"nominalKwh\":$kwh}"
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Throwable) {}

            if (clearModelSelection) {
                try {
                    val conn = com.overdrive.app.util.DaemonHttpClient.open(
                        "/api/models/selected", "POST", 3000, 5000)
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use {
                        it.write("{\"clearModelSelection\":true}".toByteArray())
                    }
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Throwable) {}
            }

            mainHandler.post {
                refreshVehicleTile()
                onComplete?.invoke()
            }
        }
    }

    private fun postNominalAndModel(
        kwh: Double,
        modelId: String?,
        onComplete: (() -> Unit)? = null,
    ) {
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh/nominal", "POST", 3000, 5000)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write("{\"nominalKwh\":$kwh}".toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Throwable) {}

            if (!modelId.isNullOrEmpty()) {
                try {
                    val conn = com.overdrive.app.util.DaemonHttpClient.open(
                        "/api/models/selected", "POST", 3000, 5000)
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write("{\"modelId\":\"$modelId\"}".toByteArray()) }
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Throwable) {}
            }

            mainHandler.post {
                refreshVehicleTile()
                onComplete?.invoke()
            }
        }
    }

    private fun modelDisplayName(modelId: String?): String {
        return when (modelId?.lowercase()) {
            null -> "—"
            "seal" -> "BYD Seal"
            "atto3", "atto-3" -> "BYD Atto 3"
            "atto2", "atto-2" -> "BYD Atto 2"
            "atto1", "atto-1" -> "BYD Atto 1"
            "han" -> "BYD Han"
            "tang" -> "BYD Tang"
            "song" -> "BYD Song"
            "qin" -> "BYD Qin"
            "dolphin" -> "BYD Dolphin"
            "seagull" -> getString(R.string.vehicle_model_seagull)
            "sealion6" -> "BYD Sealion 6"
            "sealion7" -> "BYD Sealion 7"
            "sealu", "seal-u" -> "BYD Seal U"
            else -> modelId.replaceFirstChar { it.uppercase() }
        }
    }

    companion object {
        // Hero-subtitle insights carousel timing.
        // 5 s hold matches Tesla / Polestar style; 250 ms cross-fade is M3
        // "duration-medium2" (close enough for a one-line text swap).
        private const val INSIGHT_HOLD_MS = 5_000L
        private const val INSIGHT_FADE_MS = 250L
        private const val INSIGHT_RESUME_AFTER_MS = 5_000L
        private const val SERVICE_SEGMENT_COUNT = 8
        private const val KM_TO_MILES = 0.621371
        private const val VEHICLE_REFRESH_MAX_RETRIES = 2
        private const val VEHICLE_REFRESH_RETRY_MS = 1_500L
        private const val CHARGE_COMPLETION_REFRESH_MS = 15_000L
        private const val HERO_HTTP_CONNECT_TIMEOUT_MS = 3_000
        private const val HERO_HTTP_READ_TIMEOUT_MS = 65_000
        private const val DASHBOARD_VEHICLE_HERO_URL =
            "http://127.0.0.1:8080/local/dashboard-vehicle-hero.html"
    }
}
