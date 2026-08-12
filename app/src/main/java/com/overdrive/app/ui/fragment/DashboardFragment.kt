package com.overdrive.app.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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
import com.overdrive.app.ui.util.RecordingsApiClient
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.viewmodel.MainViewModel
import com.overdrive.app.ui.viewmodel.RecordingViewModel
import com.overdrive.app.util.DeviceIdGenerator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SOTA Dashboard.
 *
 * Layout (top to bottom):
 *   - Hero card: time-of-day greeting + system summary subtitle.
 *   - Metric grid: Recordings · Storage · Remote · Services.
 *   - Connect card: QR + tunnel chips + access code.
 *   - Quick actions row: Live · Recordings · Settings.
 */
class DashboardFragment : Fragment() {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    private val recordingViewModel: RecordingViewModel by activityViewModels()

    // Hero
    private lateinit var heroCard: MaterialCardView
    private lateinit var heroGreeting: TextView
    private lateinit var heroSubtitle: TextView
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

    // Connect card
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

    // Quick-action tiles (cards now, not buttons — they share dimensions
    // with the metric tiles so the dashboard reads as one rhythm). The
    // Settings tile was removed in the wide-Vehicle layout — Settings is
    // still reachable from the navigation rail.
    private lateinit var quickLive: MaterialCardView

    // Vehicle tile — battery capacity + model summary. Tap opens a dialog.
    private var metricVehicle: MaterialCardView? = null
    private var metricVehicleValue: TextView? = null

    // Background work for storage / recording-count tiles. Single thread is enough
    // — both probes are just a directory walk, and serializing them keeps disk I/O
    // out of the UI thread without contending with itself.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var metricsExecutor: ExecutorService? = null

    // Latest values cached so the recording-state observer (which fires on its
    // own cadence) can re-render without re-walking the disk.
    @Volatile private var todayClipCount: Int = -1

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

        // First paint of the metric tiles. onResume will refresh on every return.
        refreshMetricsTiles()
        refreshVehicleTile()

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
        // Refresh indexed recording statistics on every return so new and
        // deleted clips are reflected without materializing the full library.
        refreshMetricsTiles()
        refreshVehicleTile()
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
        super.onPause()
        cancelInsightCallbacks()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelInsightCallbacks()
        metricsExecutor?.shutdownNow()
        metricsExecutor = null
    }

    private fun bindViews(view: View) {
        heroCard = view.findViewById(R.id.heroCard)
        heroGreeting = view.findViewById(R.id.heroGreeting)
        heroSubtitle = view.findViewById(R.id.heroSubtitle)
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
    }

    private fun wireClicks() {
        // Tile taps deep-link to peer rail destinations. Use the same M3
        // fade-through motion the rail itself uses so the user can't tell
        // whether they tapped the tile or the rail icon.
        val fadeThrough = com.overdrive.app.ui.util.NavOptionsExt.m3FadeThrough()
        metricRecordings.setOnClickListener {
            findNavController().navigate(R.id.recordingsFragment, null, fadeThrough)
        }
        metricTunnel.setOnClickListener {
            findNavController().navigate(R.id.daemonsFragment, null, fadeThrough)
        }
        cardDaemons.setOnClickListener {
            findNavController().navigate(R.id.daemonsFragment, null, fadeThrough)
        }
        quickLive.setOnClickListener {
            findNavController().navigate(R.id.liveViewFragment, null, fadeThrough)
        }
        metricVehicle?.setOnClickListener { showVehicleCapacityDialog() }

        btnToggleToken.setOnClickListener { toggleTokenVisibility() }
        btnCopyToken.setOnClickListener { copyTokenToClipboard() }
        btnRegenerateToken.setOnClickListener { showRegenerateConfirmation() }
    }

    private fun observeViewModels() {
        // Daemon health drives the hero subtitle, the services tile, and chip rebuild.
        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            val running = states.values.count { it.status == DaemonStatus.RUNNING }
            val total = states.size
            tvDaemonsStatus.text = getString(R.string.dashboard_daemons_running, running, total)
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
     * Reads today's clip count from the daemon's indexed stats endpoint on a
     * background thread, then posts it to the tile. An unavailable or warming
     * index leaves the last known value intact instead of rendering a false zero.
     */
    private fun refreshMetricsTiles() {
        if (context == null) return
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }

        executor.execute {
            val clipCountToday = try {
                val stats = RecordingsApiClient.fetchStats()
                DashboardRecordingMetricPolicy.authoritativeTodayCount(
                    indexUnavailable = stats?.indexUnavailable ?: true,
                    warming = stats?.warming ?: false,
                    totalToday = stats?.totalToday ?: 0L
                )
            } catch (_: Throwable) {
                null
            }

            mainHandler.post {
                if (!isAdded || view == null) return@post
                // Tile is a single-line label so it never ellipsizes on
                // longer locales (fr/de). Storage figure used to be appended
                // here ("Today's recordings · 8.4 GB"); we dropped it because
                // localized strings made the line overflow on the BYD tile
                // width. Storage lives in its own surfaces.
                metricStorageValue.text = getString(R.string.dashboard_metric_recordings)

                if (clipCountToday != null) {
                    todayClipCount = clipCountToday
                }
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
        if (todayClipCount < 0) {
            metricRecordingsValue.setText(R.string.battery_health_dashes)
            return
        }
        val isRec = recordingViewModel.isRecording.value == true
        metricRecordingsValue.text = if (isRec) {
            getString(R.string.dashboard_recordings_value_live, todayClipCount)
        } else {
            todayClipCount.toString()
        }
    }

    internal object DashboardRecordingMetricPolicy {
        fun authoritativeTodayCount(
            indexUnavailable: Boolean,
            warming: Boolean,
            totalToday: Long
        ): Int? {
            if (indexUnavailable || warming || totalToday < 0L || totalToday > Int.MAX_VALUE) {
                return null
            }
            return totalToday.toInt()
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
     * Tint the hero card by *core* daemon health. Uses M3 Container tones so
     * the wash is soft rather than the saturated colorPrimary/Error.
     *
     * - OK   → primaryContainer (green wash, On*Container fg).
     * - ALERT → errorContainer (red wash) — only when at least one CORE daemon
     *           is in a hard-failed state. Tunnels (cloudflared/zrok/tailscale)
     *           and the Telegram bot are opt-in and never trigger ALERT.
     * - UNKNOWN → neutral surface — used pre-bind / before the daemon-states
     *           LiveData has fired so a fresh install doesn't flash red.
     *
     * STARTING is treated as OK (not ALERT) so the hero flips green the
     * instant a daemon is being launched, instead of waiting for RUNNING.
     */
    private fun applyGreetingTint(coreHealth: CoreHealth) {
        if (!::heroCard.isInitialized) return
        val ctx = context ?: return
        val (bgAttr, fgAttr, subAttr) = when (coreHealth) {
            CoreHealth.UNKNOWN -> Triple(
                com.google.android.material.R.attr.colorSurfaceContainer,
                com.google.android.material.R.attr.colorOnSurface,
                com.google.android.material.R.attr.colorOnSurfaceVariant
            )
            CoreHealth.OK -> Triple(
                com.google.android.material.R.attr.colorPrimaryContainer,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                com.google.android.material.R.attr.colorOnPrimaryContainer
            )
            CoreHealth.ALERT -> Triple(
                com.google.android.material.R.attr.colorErrorContainer,
                com.google.android.material.R.attr.colorOnErrorContainer,
                com.google.android.material.R.attr.colorOnErrorContainer
            )
        }
        resolveAttrColor(ctx, bgAttr)?.let { heroCard.setCardBackgroundColor(it) }
        resolveAttrColor(ctx, fgAttr)?.let { heroGreeting.setTextColor(it) }
        resolveAttrColor(ctx, subAttr)?.let { heroSubtitle.setTextColor(it) }
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
     * Read /api/performance/soh/nominal + /api/models/selected and render
     * "82.5 kWh · BYD Seal" or "Tap to set" if no nominal yet.
     *
     * Both calls run on a worker thread (HTTP). Defaults survive a daemon
     * boot race — the tile flashes "Tap to set" until the first successful
     * round-trip lands.
     */
    private fun refreshVehicleTile() {
        val tile = metricVehicleValue ?: return
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            var nominalKwh = 0.0
            var modelId: String? = null
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh/nominal", "GET", 2000, 3000)
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(body)
                    if (!json.isNull("nominalKwh")) {
                        nominalKwh = json.optDouble("nominalKwh", 0.0)
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) { /* keep defaults */ }
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
                        // Backward compatibility with an older daemon that does
                        // not expose selection provenance yet.
                        else -> json.optString("modelId", "")
                    }
                    if (m.isNotEmpty()) modelId = m
                }
                conn.disconnect()
            } catch (_: Throwable) {}

            mainHandler.post {
                if (!isAdded || view == null) return@post
                if (nominalKwh > 0) {
                    tile.text = if (modelId != null) {
                        getString(R.string.dashboard_vehicle_summary, nominalKwh, modelDisplayName(modelId))
                    } else {
                        String.format("%.1f kWh", nominalKwh)
                    }
                } else {
                    tile.text = getString(R.string.dashboard_vehicle_tap_to_set)
                }
            }
        }
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
    }
}
