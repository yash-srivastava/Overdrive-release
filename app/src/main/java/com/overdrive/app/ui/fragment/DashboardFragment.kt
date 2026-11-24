package com.overdrive.app.ui.fragment

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.overdrive.app.R
import com.overdrive.app.auth.AuthManager
import com.overdrive.app.client.CameraDaemonClient
import com.overdrive.app.ui.dashboard.DashboardAiInsight
import com.overdrive.app.ui.dashboard.DashboardInsight
import com.overdrive.app.ui.dashboard.DashboardInsightProvider
import com.overdrive.app.ui.dashboard.DashboardStateReducer
import com.overdrive.app.ui.dashboard.DashboardStatusParser
import com.overdrive.app.ui.dashboard.DashboardStatusResult
import com.overdrive.app.ui.dashboard.DashboardUiState
import com.overdrive.app.ui.model.DaemonState
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.model.TunnelDisplayPolicy
import com.overdrive.app.ui.model.localizedName
import com.overdrive.app.ui.util.QrCodeGenerator
import com.overdrive.app.ui.util.RecordingScanner
import com.overdrive.app.ui.util.RecordingsApiClient
import com.overdrive.app.ui.vehicle.VehicleArt
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.viewmodel.MainViewModel
import com.overdrive.app.ui.viewmodel.RecordingViewModel
import com.overdrive.app.ui.widget.AppToast
import com.overdrive.app.ui.widget.Skeleton
import com.overdrive.app.util.DeviceIdGenerator
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** State-driven dashboard backed by the daemon's existing /status contract. */
class DashboardFragment : Fragment() {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    private val recordingViewModel: RecordingViewModel by activityViewModels()

    // Hero
    private lateinit var heroGreeting: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var heroChipTunnel: Chip
    private lateinit var heroChipServices: Chip
    private lateinit var heroChipRecording: Chip
    private lateinit var vehicleSocValue: TextView
    private lateinit var vehicleRangeValue: TextView

    // Optional, stored-only GenAI dashboard card.
    private lateinit var aiInsightCard: MaterialCardView
    private lateinit var aiInsightTitle: TextView
    private lateinit var aiInsightText: TextView
    private lateinit var aiInsightMeta: TextView
    private lateinit var aiInsightExpand: ImageView
    // od_expand_remote: scroll again once the first vehicle state settles the hero height.
    private var pendingRemoteRealign = false
    private var remoteRealignDeadline = 0L
    private var aiInsightExpanded = false

    // Conditional charging block.
    private lateinit var chargingCard: MaterialCardView
    private lateinit var chargingStateValue: TextView
    private lateinit var chargingPowerGroup: View
    private lateinit var chargingPowerValue: TextView
    private lateinit var chargingEtaGroup: View
    private lateinit var chargingEtaValue: TextView
    private lateinit var chargingSessionGroup: View
    private lateinit var chargingSessionValue: TextView

    // Recordings and storage.
    private lateinit var metricRecordings: MaterialCardView
    private lateinit var metricRecordingsValue: TextView
    private lateinit var metricStorageValue: TextView
    private lateinit var recordingStorageProgress:
        com.google.android.material.progressindicator.LinearProgressIndicator

    private class ActivityRowViews(
        val container: View,
        val icon: ImageView,
        val text: TextView,
    )

    private var activityRows: List<ActivityRowViews> = emptyList()

    // Existing operational actions.
    private lateinit var metricTunnel: MaterialCardView
    private lateinit var metricTunnelValue: TextView
    private lateinit var tunnelStateDot: View
    private lateinit var cardDaemons: MaterialCardView
    private lateinit var tvDaemonsStatus: TextView

    // Connect card
    private lateinit var ivQrCode: ImageView
    private lateinit var qrContainer: FrameLayout
    private lateinit var tvQrPlaceholder: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var chipGroupTunnels: ChipGroup
    private lateinit var remoteDetails: View
    private lateinit var btnExpandRemote: ImageView
    private var selectedTunnel: DaemonType? = null
    private var lastRenderedQrUrl: String? = null
    private var hasRenderedQrForView = false
    private var pendingQrUrl: String? = null
    private var qrGeneration = 0
    // QR encoding is CPU work; never run it on the dashboard UI thread.
    private var qrExecutor: ExecutorService? = null

    // Auth
    private lateinit var tvDeviceToken: TextView
    private lateinit var btnToggleToken: ImageView
    private lateinit var btnCopyToken: ImageView
    private lateinit var btnRegenerateToken: MaterialButton
    private var isTokenVisible = false

    private lateinit var quickLive: MaterialCardView

    // Vehicle summary remains the onboarding anchor and opens the existing dialog.
    private var metricVehicle: View? = null
    private var metricVehicleValue: TextView? = null

    // Nullable additions (present in both orientations, but bound null-safely
    // so a layout variant can drop them without crashing).
    private var heroSocProgress:
        com.google.android.material.progressindicator.LinearProgressIndicator? = null
    private var vehicleRangeLabel: TextView? = null
    private var heroRangeBreakdown: TextView? = null
    // Side-by-side HAL range column — visible only while a personalized
    // figure occupies the main range column.
    private var vehicleHalRangeColumn: View? = null
    private var vehicleHalRangeValue: TextView? = null
    private var personalizedRangeFirst = true
    private var vehicleArt: ImageView? = null
    private var quickTrips: View? = null
    private var quickVehicleControl: View? = null

    // Revamp views, bound null-safely like the additions above.
    private var dashScroll: NestedScrollView? = null
    private var heroRibbon: ImageView? = null
    private var heroServicesAction: View? = null
    private var qrRow: View? = null
    private var copyUrlButton: View? = null
    private var chargeIcon: ImageView? = null
    private var chargeSeparators: List<Pair<View, View?>> = emptyList()

    // Background work for storage / recording-count tiles. Single thread is enough
    // — both probes are just a directory walk, and serializing them keeps disk I/O
    // out of the UI thread without contending with itself.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var metricsExecutor: ExecutorService? = null
    private var appToast: AppToast? = null
    private var skeleton: Skeleton? = null

    private var dashboardState = DashboardUiState()
    private var todayClipCount: Int? = null
    private var storageSummary: DashboardUiState.StorageSummary? = null
    // Clips and storage answer on separate hops, so each retires its own placeholder. A null
    // count with storage already in hand means "still counting", not "no clips".
    private var clipCountResolved: Boolean = false
    private var storageResolved: Boolean = false
    private var viewGeneration: Int = 0
    private var dashboardResumed: Boolean = false
    private var recordingStatsRetryCount: Int = 0
    private var vehicleTileRetryCount: Int = 0

    private var insightsProvider: DashboardInsightProvider? = null
    private var firstVisitCount: Int = -1
    private val statusRefreshRunnable = Runnable { refreshVehicleStatus(showLoading = false) }
    private val recordingStatsRefreshRunnable = Runnable { refreshMetricsTiles() }
    private val vehicleTileRefreshRunnable = Runnable { refreshVehicleTile() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewGeneration += 1

        bindViews(view)
        appToast = AppToast(view)
        bindSkeletons(view)
        wireClicks()
        observeViewModels()

        // Carry the resolved sections across a view recreation (rotation): starting from a
        // fresh DashboardUiState would repaint every value as Loading and re-flash the
        // placeholders for data this fragment instance already has.
        dashboardState = DashboardStateReducer.remoteExpanded(
            dashboardState,
            savedInstanceState?.getBoolean(STATE_REMOTE_EXPANDED, false) == true,
        )
        // One-shot: removed from the arguments so recreation does not scroll again.
        val expandRemoteRequested = arguments?.getBoolean(ARG_EXPAND_REMOTE, false) == true
        if (expandRemoteRequested) {
            arguments?.remove(ARG_EXPAND_REMOTE)
            dashboardState = DashboardStateReducer.remoteExpanded(dashboardState, true)
        }
        selectedTunnel = savedInstanceState
            ?.getString(STATE_SELECTED_TUNNEL)
            ?.let { runCatching { DaemonType.valueOf(it) }.getOrNull() }
        aiInsightExpanded = savedInstanceState
            ?.getBoolean(STATE_AI_INSIGHT_EXPANDED, false) == true
        renderDashboardState()
        styleHeroRibbon()
        if (expandRemoteRequested) {
            scrollRemoteIntoView()
            pendingRemoteRealign = true
            remoteRealignDeadline = android.os.SystemClock.uptimeMillis() + REMOTE_REALIGN_WINDOW_MS
        }
        tvDeviceId.text = DeviceIdGenerator.generateDeviceId(requireContext())
        loadAuthState()

        if (insightsProvider == null) {
            val provider = DashboardInsightProvider(requireContext().applicationContext)
            insightsProvider = provider
            firstVisitCount = provider.recordDashboardVisit()
        }
    }

    override fun onResume() {
        super.onResume()
        dashboardResumed = true
        recordingStatsRetryCount = 0
        RecordingScanner.invalidateCache()
        // Queue the live cockpit state before filesystem scans and insight
        // generation on the shared worker so SOC/charging paint first.
        refreshVehicleStatus(showLoading = true)
        refreshMetricsTiles()
        recordingViewModel.updateStorageInfo()
        vehicleTileRetryCount = 0
        refreshVehicleTile()
        rebuildInsightsAsync()
        loadAuthState()
    }

    override fun onPause() {
        dashboardResumed = false
        mainHandler.removeCallbacks(statusRefreshRunnable)
        mainHandler.removeCallbacks(recordingStatsRefreshRunnable)
        mainHandler.removeCallbacks(vehicleTileRefreshRunnable)
        super.onPause()
    }

    override fun onDestroyView() {
        pendingRemoteRealign = false
        dashboardResumed = false
        viewGeneration += 1
        mainHandler.removeCallbacks(statusRefreshRunnable)
        mainHandler.removeCallbacks(recordingStatsRefreshRunnable)
        mainHandler.removeCallbacks(vehicleTileRefreshRunnable)
        appToast?.cancel()
        appToast = null
        skeleton?.cancel()
        skeleton = null
        if (::ivQrCode.isInitialized) ivQrCode.setImageDrawable(null)
        qrGeneration++
        pendingQrUrl = null
        lastRenderedQrUrl = null
        hasRenderedQrForView = false
        qrExecutor?.shutdownNow()
        qrExecutor = null
        super.onDestroyView()
        metricsExecutor?.shutdownNow()
        metricsExecutor = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_REMOTE_EXPANDED, dashboardState.remoteExpanded)
        outState.putBoolean(STATE_AI_INSIGHT_EXPANDED, aiInsightExpanded)
        selectedTunnel?.let { outState.putString(STATE_SELECTED_TUNNEL, it.name) }
        super.onSaveInstanceState(outState)
    }

    /**
     * Places the first-load placeholders. Each retires for good once its value resolves, so the
     * 2s/15s status poll and the per-resume tile refresh never blank a slot again.
     */
    private fun bindSkeletons(view: View) {
        val skeleton = Skeleton(view)
        this.skeleton = skeleton
        skeleton.bind(R.id.vehicleSocSkeleton, vehicleSocValue)
        skeleton.bind(R.id.vehicleRangeSkeleton, vehicleRangeValue)
        skeleton.bind(R.id.metricRecordingsSkeleton, metricRecordingsValue)
        skeleton.bind(R.id.metricStorageSkeleton, metricStorageValue)
        activityRows.firstOrNull()?.let {
            skeleton.bind(R.id.activityRow1Skeleton, it.icon, it.text)
        }
    }

    private fun bindViews(view: View) {
        heroGreeting = view.findViewById(R.id.heroGreeting)
        heroSubtitle = view.findViewById(R.id.heroSubtitle)
        heroChipTunnel = view.findViewById(R.id.heroChipTunnel)
        heroChipServices = view.findViewById(R.id.heroChipServices)
        heroChipRecording = view.findViewById(R.id.heroChipRecording)
        vehicleSocValue = view.findViewById(R.id.vehicleSocValue)
        vehicleRangeValue = view.findViewById(R.id.vehicleRangeValue)
        aiInsightCard = view.findViewById(R.id.aiInsightCard)
        aiInsightTitle = view.findViewById(R.id.aiInsightTitle)
        aiInsightText = view.findViewById(R.id.aiInsightText)
        aiInsightMeta = view.findViewById(R.id.aiInsightMeta)
        aiInsightExpand = view.findViewById(R.id.aiInsightExpand)

        chargingCard = view.findViewById(R.id.chargingCard)
        chargingStateValue = view.findViewById(R.id.chargingStateValue)
        chargingPowerGroup = view.findViewById(R.id.chargingPowerGroup)
        chargingPowerValue = view.findViewById(R.id.chargingPowerValue)
        chargingEtaGroup = view.findViewById(R.id.chargingEtaGroup)
        chargingEtaValue = view.findViewById(R.id.chargingEtaValue)
        chargingSessionGroup = view.findViewById(R.id.chargingSessionGroup)
        chargingSessionValue = view.findViewById(R.id.chargingSessionValue)

        metricRecordings = view.findViewById(R.id.metricRecordings)
        metricRecordingsValue = view.findViewById(R.id.metricRecordingsValue)
        metricStorageValue = view.findViewById(R.id.metricStorageValue)
        recordingStorageProgress = view.findViewById(R.id.recordingStorageProgress)
        activityRows = listOf(
            Triple(R.id.activityItem1, R.id.activityIcon1, R.id.activityRow1),
            Triple(R.id.activityItem2, R.id.activityIcon2, R.id.activityRow2),
            Triple(R.id.activityItem3, R.id.activityIcon3, R.id.activityRow3),
        ).map { (containerId, iconId, textId) ->
            ActivityRowViews(
                view.findViewById(containerId),
                view.findViewById(iconId),
                view.findViewById(textId),
            )
        }
        metricTunnel = view.findViewById(R.id.metricTunnel)
        metricTunnelValue = view.findViewById(R.id.metricTunnelValue)
        tunnelStateDot = view.findViewById(R.id.tunnelStateDot)
        cardDaemons = view.findViewById(R.id.cardDaemons)
        tvDaemonsStatus = view.findViewById(R.id.tvDaemonsStatus)

        ivQrCode = view.findViewById(R.id.ivQrCode)
        qrContainer = view.findViewById(R.id.qrContainer)
        tvQrPlaceholder = view.findViewById(R.id.tvQrPlaceholder)
        tvUrl = view.findViewById(R.id.tvUrl)
        tvDeviceId = view.findViewById(R.id.tvDeviceId)
        chipGroupTunnels = view.findViewById(R.id.chipGroupTunnels)
        remoteDetails = view.findViewById(R.id.remoteDetails)
        btnExpandRemote = view.findViewById(R.id.btnExpandRemote)

        tvDeviceToken = view.findViewById(R.id.tvDeviceToken)
        btnToggleToken = view.findViewById(R.id.btnToggleToken)
        btnCopyToken = view.findViewById(R.id.btnCopyToken)
        btnRegenerateToken = view.findViewById(R.id.btnRegenerateToken)

        quickLive = view.findViewById(R.id.quickLive)
        quickTrips = view.findViewById(R.id.quickTrips)
        quickVehicleControl = view.findViewById(R.id.quickVehicleControl)
        heroSocProgress = view.findViewById(R.id.heroSocProgress)
        vehicleRangeLabel = view.findViewById(R.id.vehicleRangeLabel)
        heroRangeBreakdown = view.findViewById(R.id.heroRangeBreakdown)
        vehicleHalRangeColumn = view.findViewById(R.id.vehicleHalRangeColumn)
        vehicleHalRangeValue = view.findViewById(R.id.vehicleHalRangeValue)
        vehicleArt = view.findViewById(R.id.vehicleArt)

        // Vehicle tile present in both portrait and landscape layouts.
        metricVehicle = view.findViewById(R.id.metricVehicle)
        metricVehicleValue = view.findViewById(R.id.metricVehicleValue)

        dashScroll = view.findViewById(R.id.dashScroll)
        heroRibbon = view.findViewById(R.id.dashHeroRibbon)
        heroServicesAction = view.findViewById(R.id.dashServicesAction)
        qrRow = view.findViewById(R.id.dashQrRow)
        copyUrlButton = view.findViewById(R.id.dashCopyUrl)
        chargeIcon = view.findViewById(R.id.dashChargeIcon)
        chargeSeparators = listOf(
            chargingPowerGroup to view.findViewById<View>(R.id.dashChargePowerSep),
            chargingEtaGroup to view.findViewById<View>(R.id.dashChargeEtaSep),
            chargingSessionGroup to view.findViewById<View>(R.id.dashChargeSessionSep),
        )
    }

    private fun wireClicks() {
        // Tile taps deep-link to peer rail destinations. Use the same M3
        // fade-through motion the rail itself uses so the user can't tell
        // whether they tapped the tile or the rail icon.
        val fadeThrough = com.overdrive.app.ui.util.NavOptionsExt.m3FadeThrough()
        metricRecordings.setOnClickListener {
            findNavController().navigate(R.id.recordingsFragment, null, fadeThrough)
        }
        cardDaemons.setOnClickListener {
            findNavController().navigate(R.id.daemonsFragment, null, fadeThrough)
        }
        heroServicesAction?.setOnClickListener {
            findNavController().navigate(R.id.daemonsFragment, null, fadeThrough)
        }
        (heroServicesAction as? TextView)?.let {
            it.contentDescription = "${it.text}, ${getString(R.string.dashboard_metric_services)}"
        }
        quickLive.setOnClickListener {
            findNavController().navigate(R.id.liveViewFragment, null, fadeThrough)
        }
        quickTrips?.setOnClickListener {
            findNavController().navigate(R.id.tripsFragment, null, fadeThrough)
        }
        quickVehicleControl?.setOnClickListener {
            findNavController().navigate(R.id.vehicleControlFragment, null, fadeThrough)
        }
        aiInsightCard.setOnClickListener {
            aiInsightExpanded = !aiInsightExpanded
            renderAiInsightExpansion(animate = true)
        }
        metricTunnel.setOnClickListener {
            dashboardState = DashboardStateReducer.remoteExpanded(
                dashboardState,
                !dashboardState.remoteExpanded,
            )
            renderRemoteExpansion(animate = true)
        }
        metricVehicle?.setOnClickListener { showVehicleCapacityDialog() }

        btnToggleToken.setOnClickListener { toggleTokenVisibility() }
        btnCopyToken.setOnClickListener { copyTokenToClipboard() }
        btnRegenerateToken.setOnClickListener { showRegenerateConfirmation() }
        copyUrlButton?.setOnClickListener { copyUrlToClipboard() }
    }

    private fun observeViewModels() {
        // Daemon health drives the hero subtitle, the services tile, and chip rebuild.
        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            renderServicesTile(states)
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
        recordingViewModel.storageInfo.observe(viewLifecycleOwner) { info ->
            storageSummary = info
                ?.takeIf { it.totalBytes > 0L }
                ?.let {
                    DashboardUiState.StorageSummary(
                        usedBytes = it.usedBytes.coerceAtLeast(0L),
                        availableBytes = it.availableBytes.coerceAtLeast(0L),
                        totalBytes = it.totalBytes,
                    )
                }
            storageResolved = true
            updateRecordingState()
        }
    }

    /** Services tile count; amber only while a core service is down. */
    private fun renderServicesTile(states: Map<DaemonType, DaemonState>?) {
        val running = states?.values?.count { it.status == DaemonStatus.RUNNING } ?: 0
        tvDaemonsStatus.text = getString(R.string.dash_services_running, running, states?.size ?: 0)
        tvDaemonsStatus.setTextColor(
            if (computeCoreHealth(states) == CoreHealth.ALERT) {
                attentionTextColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
            } else {
                themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
        )
    }

    /**
     * Status strip under the hero: remote access, services, recording. Text and
     * tone come from the same state, never parsed back out of localized text.
     */
    private fun refreshHeroChips() {
        val tunnel = tunnelDisplay()
        heroChipTunnel.text = when (tunnel.kind) {
            TunnelDisplayPolicy.Kind.ONLINE -> getString(R.string.dash_status_remote_on)
            TunnelDisplayPolicy.Kind.HIDDEN -> getString(R.string.dash_status_remote_off)
            TunnelDisplayPolicy.Kind.FAILED -> getString(
                R.string.dash_status_remote_state,
                getString(R.string.dashboard_tunnel_tile_failed),
            )
            TunnelDisplayPolicy.Kind.STOPPING -> getString(
                R.string.dash_status_remote_state,
                getString(R.string.dashboard_tunnel_tile_stopping),
            )
            else -> getString(
                R.string.dash_status_remote_state,
                getString(R.string.dashboard_tunnel_connecting),
            )
        }
        tintStatusChip(
            heroChipTunnel,
            when (tunnel.kind) {
                TunnelDisplayPolicy.Kind.ONLINE -> StatusTone.LIVE
                else -> StatusTone.IDLE
            }
        )

        val states = daemonsViewModel.daemonStates.value
        val running = states?.values?.count { it.status == DaemonStatus.RUNNING } ?: 0
        heroChipServices.text = if (states == null) {
            getString(R.string.dashboard_metric_value_pending)
        } else {
            getString(R.string.dash_status_services, running, states.size)
        }
        val health = computeCoreHealth(states)
        val coreTransitioning = states?.any { (type, state) ->
            type in CORE_DAEMONS &&
                (state.status == DaemonStatus.STARTING || state.status == DaemonStatus.STOPPING)
        } == true
        tintStatusChip(
            heroChipServices,
            when {
                health == CoreHealth.ALERT -> StatusTone.ATTENTION
                health == CoreHealth.OK && !coreTransitioning -> StatusTone.LIVE
                else -> StatusTone.IDLE
            }
        )
        heroServicesAction?.visibility =
            if (health == CoreHealth.ALERT) View.VISIBLE else View.GONE

        val recording = recordingViewModel.isRecording.value == true
        heroChipRecording.setText(
            if (recording) R.string.dashboard_chip_recording_active
            else R.string.dash_status_recording_idle
        )
        tintStatusChip(heroChipRecording, if (recording) StatusTone.LIVE else StatusTone.IDLE)
    }

    private enum class StatusTone { LIVE, IDLE, ATTENTION }

    /** Dot carries the tone; the label turns amber only for the item that needs attention. */
    private fun tintStatusChip(chip: Chip, tone: StatusTone) {
        val ctx = context ?: return
        val dot = ContextCompat.getColor(
            ctx,
            when (tone) {
                StatusTone.LIVE -> R.color.status_success
                StatusTone.IDLE -> R.color.status_stopped
                StatusTone.ATTENTION -> R.color.status_warning
            }
        )
        chip.chipIconTint = ColorStateList.valueOf(dot)
        chip.setTextColor(
            if (tone == StatusTone.ATTENTION) {
                attentionTextColor(com.google.android.material.R.attr.colorSurfaceContainer)
            } else {
                themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
        )
    }

    private fun themeColor(attr: Int): Int =
        MaterialColors.getColor(requireContext(), attr, Color.GRAY)

    /** status_warning, pulled toward the text colour until it reads at 4.5:1 on [backgroundAttr]. */
    private fun attentionTextColor(backgroundAttr: Int): Int {
        val warning = ContextCompat.getColor(requireContext(), R.color.status_warning)
        val background = themeColor(backgroundAttr)
        if (Color.alpha(background) != 255) return warning
        val ink = themeColor(com.google.android.material.R.attr.colorOnSurface)
        var color = warning
        var step = 1
        while (ColorUtils.calculateContrast(color, background) < MIN_TEXT_CONTRAST && step <= 6) {
            color = ColorUtils.blendARGB(warning, ink, step * 0.1f)
            step += 1
        }
        return color
    }

    /**
     * Keeps [number] at the view's own size and mutes the rest at [scale], so
     * "78%", "412 km" and "3 clips" lead with the figure.
     */
    private fun leadNumber(text: String, number: String, scale: Float, regularUnit: Boolean): CharSequence {
        val start = if (number.isEmpty()) -1 else text.indexOf(number)
        if (start < 0) return text
        val end = start + number.length
        val muted = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val out = SpannableString(text)
        fun mute(from: Int, to: Int) {
            if (from >= to) return
            out.setSpan(RelativeSizeSpan(scale), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(muted), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (regularUnit) {
                out.setSpan(TypefaceSpan("sans-serif"), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        mute(0, start)
        mute(end, text.length)
        return out
    }

    /**
     * No reading: the app's em dash in the number's place, unit kept ("—%"), so the hero reads as a
     * value that is unknown rather than a lone bar. Built from the localized format, so "%—" locales hold.
     */
    private fun pendingPercent(): CharSequence {
        val dash = getString(R.string.dashboard_metric_value_pending)
        val zero = formatLocalized("%.0f", 0.0)
        val text = getString(R.string.dashboard_modern_percent, 0.0).replaceFirst(zero, dash)
        return leadNumber(text, dash, SOC_UNIT_SCALE, regularUnit = false)
    }

    // Same locale getString formats with, so the digits match.
    private fun formatLocalized(pattern: String, vararg args: Any): String =
        String.format(resources.configuration.locales[0] ?: Locale.getDefault(), pattern, *args)

    // ============== Metric tiles (storage + today's recordings) ==============

    /**
     * Reads today's indexed total, falling back to the shared filesystem
     * scanner only when the daemon API is unreachable. Warming and
     * index-unavailable responses remain unknown rather than becoming a
     * misleading zero.
     */
    private fun refreshMetricsTiles() {
        val ctx = context?.applicationContext ?: return
        val generation = viewGeneration
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }

        executor.execute {
            val stats = try {
                RecordingsApiClient.fetchStats()
            } catch (_: Throwable) {
                null
            }
            val shouldRetry = stats?.warming == true
            val clipCountToday = when {
                stats?.warming == true || stats?.indexUnavailable == true -> null
                stats != null -> stats.totalToday
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                else -> try {
                    val startOfDayMs = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    RecordingScanner.scanRecordings(ctx)
                        .count { it.timestamp >= startOfDayMs }
                } catch (_: Throwable) {
                    null
                }
            }

            mainHandler.post {
                if (!isAdded || view == null || generation != viewGeneration) return@post
                todayClipCount = clipCountToday
                mainHandler.removeCallbacks(recordingStatsRefreshRunnable)
                val willRetry = shouldRetry &&
                    dashboardResumed &&
                    recordingStatsRetryCount < MAX_RECORDING_STATS_RETRIES
                // A warming index has not answered yet, so the placeholder stays for the retry.
                if (!willRetry) clipCountResolved = true
                updateRecordingState()
                if (willRetry) {
                    recordingStatsRetryCount += 1
                    mainHandler.postDelayed(
                        recordingStatsRefreshRunnable,
                        RECORDING_STATS_RETRY_MS * recordingStatsRetryCount,
                    )
                } else if (!shouldRetry) {
                    recordingStatsRetryCount = 0
                }
            }
        }
    }

    private fun updateRecordingState() {
        dashboardState = DashboardStateReducer.recordings(
            dashboardState,
            todayClipCount,
            storageSummary,
        )
        renderRecordingsValue()
    }

    private fun renderRecordingsValue() {
        if (!::metricRecordingsValue.isInitialized) return
        val unavailable = dashboardState.recordings == DashboardUiState.RecordingState.Unavailable
        if (clipCountResolved || unavailable) {
            skeleton?.markLoaded(R.id.metricRecordingsSkeleton)
        }
        if (storageResolved || unavailable) {
            skeleton?.markLoaded(R.id.metricStorageSkeleton)
        }
        when (val recording = dashboardState.recordings) {
            DashboardUiState.RecordingState.Loading -> {
                metricRecordingsValue.setText(R.string.dashboard_metric_value_pending)
                metricStorageValue.setText(R.string.dashboard_metric_value_pending)
                recordingStorageProgress.visibility = View.INVISIBLE
            }
            DashboardUiState.RecordingState.Unavailable -> {
                metricRecordingsValue.setText(R.string.dashboard_metric_value_pending)
                metricStorageValue.setText(R.string.dashboard_modern_recordings_unavailable)
                recordingStorageProgress.visibility = View.INVISIBLE
            }
            is DashboardUiState.RecordingState.Ready -> {
                val count = recording.todayClipCount
                metricRecordingsValue.text = when {
                    count == null -> getString(R.string.dashboard_metric_value_pending)
                    recordingViewModel.isRecording.value == true -> leadNumber(
                        getString(R.string.dashboard_recordings_value_live, count),
                        formatLocalized("%d", count),
                        CLIPS_UNIT_SCALE,
                        regularUnit = true,
                    )
                    else -> leadNumber(
                        resources.getQuantityString(R.plurals.dash_clip_count, count, count),
                        formatLocalized("%d", count),
                        CLIPS_UNIT_SCALE,
                        regularUnit = true,
                    )
                }

                val storage = recording.storage
                if (storage == null) {
                    metricStorageValue.setText(R.string.dashboard_modern_storage_unavailable)
                    recordingStorageProgress.visibility = View.INVISIBLE
                } else {
                    val ctx = context ?: return
                    metricStorageValue.text = getString(
                        R.string.dashboard_modern_storage_summary,
                        Formatter.formatShortFileSize(ctx, storage.usedBytes),
                        Formatter.formatShortFileSize(ctx, storage.availableBytes),
                    )
                    recordingStorageProgress.progress = storage.usagePercent
                    recordingStorageProgress.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun refreshVehicleStatus(showLoading: Boolean) {
        if (showLoading && dashboardState.vehicle !is DashboardUiState.VehicleState.Ready) {
            dashboardState = DashboardStateReducer.statusLoading(dashboardState)
            renderVehicleState()
        }
        mainHandler.removeCallbacks(statusRefreshRunnable)
        val generation = viewGeneration
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            val result = fetchVehicleStatus()
            mainHandler.post {
                if (!isAdded || view == null || generation != viewGeneration) return@post
                val previousVehicleState = dashboardState.vehicle
                val previousRangePreference = personalizedRangeFirst
                if (result is DashboardStatusResult.Available) {
                    personalizedRangeFirst = result.snapshot.personalizedRangeFirst
                }
                dashboardState = DashboardStateReducer.status(dashboardState, result)
                skeleton?.markLoaded(R.id.vehicleSocSkeleton)
                skeleton?.markLoaded(R.id.vehicleRangeSkeleton)
                // Most polls return the same values; skip the repaint and SOC gauge animation then.
                if (dashboardState.vehicle != previousVehicleState
                    || personalizedRangeFirst != previousRangePreference) {
                    renderVehicleState()
                }
                if (dashboardResumed) {
                    mainHandler.postDelayed(statusRefreshRunnable, STATUS_REFRESH_MS)
                }
            }
        }
    }

    private fun fetchVehicleStatus(): DashboardStatusResult {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = com.overdrive.app.util.DaemonHttpClient.open(
                "/status",
                "GET",
                STATUS_CONNECT_TIMEOUT_MS,
                STATUS_READ_TIMEOUT_MS,
            )
            if (conn.responseCode != 200) {
                DashboardStatusResult.Unavailable(
                    DashboardStatusResult.Reason.SERVICE_UNAVAILABLE
                )
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                DashboardStatusParser.parse(body, fetchPersonalizedRange())
            }
        } catch (_: Throwable) {
            DashboardStatusResult.Unavailable(
                DashboardStatusResult.Reason.SERVICE_UNAVAILABLE
            )
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
                // Connection is already unusable.
            }
        }
    }

    /**
     * Best-effort learned range enrichment. A failure here must never hide the
     * otherwise healthy vehicle status; the parser retains the HAL range.
     */
    private fun fetchPersonalizedRange(): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = com.overdrive.app.util.DaemonHttpClient.open(
                "/api/trips/range",
                "GET",
                RANGE_CONNECT_TIMEOUT_MS,
                RANGE_READ_TIMEOUT_MS,
            )
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
                // Connection is already unusable.
            }
        }
    }

    private fun renderDashboardState() {
        renderVehicleState()
        renderRecordingsValue()
        renderActivityState()
        renderRemoteExpansion()
    }

    private fun renderVehicleState() {
        if (!::vehicleSocValue.isInitialized) return
        if (dashboardState.vehicle != DashboardUiState.VehicleState.Loading) {
            skeleton?.markLoaded(R.id.vehicleSocSkeleton)
            skeleton?.markLoaded(R.id.vehicleRangeSkeleton)
        }
        when (val vehicle = dashboardState.vehicle) {
            DashboardUiState.VehicleState.Loading -> {
                heroGreeting.setText(R.string.dashboard_modern_vehicle_status)
                heroSubtitle.setText(R.string.dashboard_modern_updating)
                vehicleSocValue.text = pendingPercent()
                vehicleSocValue.contentDescription = null
                vehicleRangeValue.setText(R.string.dashboard_metric_value_pending)
                vehicleRangeLabel?.setText(R.string.dash_range_caption)
                setHalRangeColumnVisible(false)
                chargingCard.visibility = View.GONE
                heroSubtitle.visibility = View.VISIBLE
                renderSocGauge(null)
                renderRangeBreakdown(null)
            }
            is DashboardUiState.VehicleState.Unavailable -> {
                heroGreeting.setText(R.string.dashboard_modern_vehicle_status)
                heroSubtitle.setText(R.string.dashboard_modern_vehicle_unavailable)
                vehicleSocValue.text = pendingPercent()
                vehicleSocValue.contentDescription = null
                vehicleRangeValue.setText(R.string.dashboard_metric_value_pending)
                vehicleRangeLabel?.setText(R.string.dash_range_caption)
                setHalRangeColumnVisible(false)
                chargingCard.visibility = View.GONE
                heroSubtitle.visibility = View.VISIBLE
                renderSocGauge(null)
                renderRangeBreakdown(null)
            }
            is DashboardUiState.VehicleState.Ready -> {
                val snapshot = vehicle.snapshot
                heroGreeting.setText(R.string.dashboard_modern_vehicle_status)
                val isPowerOn = snapshot.isAccOn == true
                val gear = snapshot.gear
                val speed = snapshot.speedKmh
                val isRecording = snapshot.isRecording == true
                val isSentry = snapshot.isGpuSurveillance == true

                heroSubtitle.text = when {
                    snapshot.charging?.fault == true ->
                        getString(R.string.dashboard_modern_charge_fault)
                    snapshot.charging?.full == true ->
                        getString(R.string.dashboard_modern_charge_complete)
                    snapshot.charging?.charging == true ->
                        getString(R.string.dashboard_modern_charging)
                    else -> getString(R.string.dashboard_modern_vehicle_connected)
                }
                vehicleSocValue.text = snapshot.socPercent?.let {
                    leadNumber(
                        getString(R.string.dashboard_modern_percent, it),
                        formatLocalized("%.0f", it),
                        SOC_UNIT_SCALE,
                        regularUnit = false,
                    )
                } ?: pendingPercent()
                vehicleSocValue.contentDescription = snapshot.socPercent?.let {
                    getString(R.string.dash_a11y_label_value, getString(R.string.dashboard_modern_battery), vehicleSocValue.text)
                }
                if (pendingRemoteRealign) {
                    pendingRemoteRealign = false
                    if (dashboardState.remoteExpanded &&
                        android.os.SystemClock.uptimeMillis() < remoteRealignDeadline
                    ) {
                        scrollRemoteIntoView()
                    }
                }
                // Headline preference affects only presentation, never the
                // learned estimate or the vehicle's original HAL reading.
                val personalized = snapshot.rangeDetails?.personalized
                val halRange = snapshot.range
                val vehicleFirst = !personalizedRangeFirst && halRange != null
                val primaryRange = if (vehicleFirst) halRange else (personalized ?: halRange)
                vehicleRangeValue.text = primaryRange?.let {
                    leadNumber(
                        getString(R.string.dashboard_modern_distance, it.value, it.unit.label),
                        formatLocalized("%d", it.value),
                        RANGE_UNIT_SCALE,
                        regularUnit = true,
                    )
                } ?: getString(R.string.dashboard_metric_value_pending)
                vehicleRangeLabel?.setText(
                    if (vehicleFirst) R.string.dashboard_modern_vehicle_range
                    else if (personalized != null) R.string.dashboard_modern_personalized_range
                    else R.string.dash_range_caption
                )
                val showHalColumn = personalized != null && halRange != null
                if (showHalColumn) {
                    val secondary = if (vehicleFirst) personalized else halRange
                    val label = if (vehicleFirst) R.string.dashboard_modern_personalized_range
                        else R.string.dashboard_modern_vehicle_range
                    if (secondary != null) {
                        vehicleHalRangeValue?.text = rangeComparisonText(
                            getString(label),
                            getString(R.string.dashboard_modern_distance, secondary.value, secondary.unit.label)
                        )
                    }
                }
                setHalRangeColumnVisible(showHalColumn)
                renderSocGauge(snapshot.socPercent)
                renderRangeBreakdown(snapshot.rangeDetails)
                renderCharging(snapshot.charging)
                // The charging row takes the meta line's place.
                heroSubtitle.visibility =
                    if (chargingCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
    }

    /** Secondary range label + figure, retaining the existing emphasized number. */
    private fun rangeComparisonText(label: String, distance: String): CharSequence {
        val out = SpannableString("$label $distance")
        val start = label.length + 1
        out.setSpan(
            ForegroundColorSpan(themeColor(com.google.android.material.R.attr.colorOnSurface)),
            start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        out.setSpan(TypefaceSpan("sans-serif-medium"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    private fun setHalRangeColumnVisible(visible: Boolean) {
        vehicleHalRangeColumn?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * PHEV EV/petrol split under the SOC gauge — "EV 234 km · Fuel 320 km
     * (62%)". Each leg is already resolved by the parser (personalized when
     * learned, vehicle HAL otherwise). GONE on BEVs and while loading so the
     * hero card keeps its compact single-drivetrain height.
     */
    private fun renderRangeBreakdown(
        details: com.overdrive.app.ui.dashboard.DashboardRangeDetails?,
    ) {
        val view = heroRangeBreakdown ?: return
        if (details == null || !details.isPhev ||
            (details.evLeg == null && details.fuelLeg == null)
        ) {
            view.visibility = View.GONE
            return
        }
        val parts = mutableListOf<String>()
        details.evLeg?.let {
            parts += getString(
                R.string.dashboard_modern_breakdown_ev, it.value, it.unit.label
            )
        }
        details.fuelLeg?.let { leg ->
            parts += details.fuelPercent?.let { pct ->
                getString(
                    R.string.dashboard_modern_breakdown_fuel_pct,
                    leg.value, leg.unit.label, pct,
                )
            } ?: getString(
                R.string.dashboard_modern_breakdown_fuel, leg.value, leg.unit.label
            )
        }
        view.text = parts.joinToString(separator = " · ")
        view.visibility = View.VISIBLE
    }

    /**
     * Visual battery gauge under the numeric SOC. An unknown SOC keeps the
     * empty track on screen instead of hiding the widget; the indicator turns
     * the theme's error colour at or below [LOW_SOC_THRESHOLD_PERCENT].
     */
    private fun renderSocGauge(socPercent: Double?) {
        val gauge = heroSocProgress ?: return
        gauge.visibility = View.VISIBLE
        if (socPercent == null) {
            // Zero draws the track only, standing in for "no reading".
            gauge.setProgressCompat(0, /* animated = */ false)
            return
        }
        val clamped = socPercent.coerceIn(0.0, 100.0).toInt()
        gauge.setProgressCompat(clamped, /* animated = */ true)
        // colorError/colorPrimary live under appcompat's attr namespace in this
        // project (nonTransitiveRClass — see OnboardingOverlayView / LogsAdapter
        // convention), not material's.
        val colorAttr = if (clamped <= LOW_SOC_THRESHOLD_PERCENT) {
            androidx.appcompat.R.attr.colorError
        } else {
            androidx.appcompat.R.attr.colorPrimary
        }
        gauge.setIndicatorColor(
            com.google.android.material.color.MaterialColors.getColor(gauge, colorAttr)
        )
    }

    private fun renderCharging(charging: com.overdrive.app.ui.dashboard.DashboardChargingSnapshot?) {
        if (charging == null) {
            chargingCard.visibility = View.GONE
            return
        }
        chargingCard.visibility = View.VISIBLE
        val state = when {
            charging.fault -> getString(R.string.dashboard_modern_charge_fault)
            charging.full -> getString(R.string.dashboard_modern_charge_complete)
            charging.charging -> getString(R.string.dashboard_modern_charging)
            else -> charging.stateName ?: getString(R.string.dashboard_modern_plugged_in)
        }
        chargingStateValue.text = state
        val power = charging.powerKw?.let {
            getString(
                if (charging.powerEstimated) {
                    R.string.dashboard_modern_charge_power_estimated
                } else {
                    R.string.dashboard_modern_charge_power
                },
                it,
            )
        }
        val eta = charging.timeToFullMinutes?.let {
            getString(R.string.dash_charge_eta_left, formatChargeDuration(it))
        }
        val session = charging.sessionKwh?.let {
            val rendered = getString(R.string.dashboard_modern_charge_session, it)
            val approximate = charging.sessionEnergyEstimated || charging.sessionEnergyIncomplete
            getString(
                R.string.dash_charge_session_inline,
                if (approximate) "~$rendered" else "+$rendered",
            )
        }
        renderOptionalMetric(chargingPowerGroup, chargingPowerValue, power)
        renderOptionalMetric(chargingEtaGroup, chargingEtaValue, eta)
        renderOptionalMetric(chargingSessionGroup, chargingSessionValue, session)

        // While a charge is running the icon and values say so; the state word stays for
        // complete, fault and plugged-in, and as the icon's description otherwise.
        val stateImplied = charging.charging && !charging.fault && !charging.full &&
            (power != null || eta != null || session != null)
        chargingStateValue.visibility = if (stateImplied) View.GONE else View.VISIBLE
        chargeIcon?.contentDescription = if (stateImplied) state else null
        chargingStateValue.setTextColor(
            if (charging.fault) {
                attentionTextColor(com.google.android.material.R.attr.colorSurfaceContainer)
            } else {
                themeColor(com.google.android.material.R.attr.colorOnSurface)
            }
        )
        chargeIcon?.let {
            ImageViewCompat.setImageTintList(
                it,
                ColorStateList.valueOf(
                    if (charging.fault) {
                        ContextCompat.getColor(requireContext(), R.color.status_warning)
                    } else {
                        themeColor(androidx.appcompat.R.attr.colorPrimary)
                    }
                ),
            )
        }
        normalizeChargingSeparators()
    }

    private fun renderOptionalMetric(group: View, valueView: TextView, value: String?) {
        group.visibility = if (value == null) View.GONE else View.VISIBLE
        if (value != null) valueView.text = value
    }

    /** A value's leading dot shows only when visible text precedes it in the row. */
    private fun normalizeChargingSeparators() {
        var textBefore = chargingStateValue.visibility == View.VISIBLE
        chargeSeparators.forEach { (group, separator) ->
            if (group.visibility != View.VISIBLE) return@forEach
            separator?.visibility = if (textBefore) View.VISIBLE else View.GONE
            textBefore = true
        }
    }

    private fun formatChargeDuration(minutes: Int): String {
        val hours = minutes / 60
        val remaining = minutes % 60
        return if (hours == 0) {
            getString(R.string.dashboard_modern_minutes, minutes)
        } else {
            getString(R.string.dashboard_modern_hours_minutes, hours, remaining)
        }
    }

    private enum class CoreHealth { UNKNOWN, OK, ALERT }

    /**
     * ALERT when a core daemon (CORE_DAEMONS; opt-in daemons never count) is STOPPED or ERROR.
     * OK includes STARTING/STOPPING; the services chip shows those as idle, not live.
     */
    private fun computeCoreHealth(states: Map<DaemonType, DaemonState>?): CoreHealth {
        if (states.isNullOrEmpty()) return CoreHealth.UNKNOWN
        var sawCore = false
        for ((type, state) in states) {
            if (type !in CORE_DAEMONS) continue
            sawCore = true
            if (state.status == DaemonStatus.STOPPED || state.status == DaemonStatus.ERROR) {
                return CoreHealth.ALERT
            }
        }
        return if (sawCore) CoreHealth.OK else CoreHealth.UNKNOWN
    }

    // ============== Stable recent activity ==============

    private fun rebuildInsightsAsync() {
        val provider = insightsProvider ?: return
        val generation = viewGeneration
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        val visitCount = if (firstVisitCount >= 0) firstVisitCount else 0
        executor.execute {
            val built = try {
                provider.build(visitCount)
            } catch (_: Throwable) {
                null
            }
            val aiInsight = try {
                provider.latestAiInsight()
            } catch (_: Throwable) {
                null
            }
            mainHandler.post {
                if (!isAdded || view == null || generation != viewGeneration) return@post
                applyInsightList(built)
                renderAiInsight(aiInsight)
            }
        }
    }

    private fun applyInsightList(built: List<DashboardInsight>?) {
        val rows = built
            ?.asSequence()
            ?.filter { it.priority < WELCOME_INSIGHT_PRIORITY }
            ?.map { DashboardUiState.ActivityRow(it.text, it.icon) }
            ?.toList()
        dashboardState = DashboardStateReducer.activity(dashboardState, rows)
        renderActivityState()
    }

    private fun renderAiInsight(insight: DashboardAiInsight?) {
        if (!::aiInsightCard.isInitialized) return
        if (insight == null) {
            aiInsightExpanded = false
            aiInsightCard.visibility = View.GONE
            return
        }
        aiInsightTitle.text = insight.title
        aiInsightText.text = insight.text
        val relative = DateUtils.getRelativeTimeSpanString(
            insight.createdAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
        aiInsightMeta.text = listOf(relative, insight.model)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        renderAiInsightExpansion()
        aiInsightCard.visibility = View.VISIBLE
    }

    private fun renderAiInsightExpansion(animate: Boolean = false) {
        if (!::aiInsightCard.isInitialized) return
        if (animate) beginExpandTransition(aiInsightCard)
        aiInsightText.maxLines =
            if (aiInsightExpanded) Int.MAX_VALUE else AI_INSIGHT_PREVIEW_LINES
        aiInsightText.ellipsize =
            if (aiInsightExpanded) null else TextUtils.TruncateAt.END
        aiInsightMeta.visibility =
            if (aiInsightExpanded && aiInsightMeta.text.isNotEmpty()) View.VISIBLE else View.GONE
        rotateChevron(aiInsightExpand, aiInsightExpanded, animate)
        aiInsightCard.contentDescription = getString(
            if (aiInsightExpanded) {
                R.string.dashboard_ai_insight_collapse
            } else {
                R.string.dashboard_ai_insight_open
            }
        )
    }

    private fun renderActivityState() {
        if (activityRows.isEmpty()) return
        // The placeholder is the loading state, so there is nothing to paint until it retires.
        if (dashboardState.activity == DashboardUiState.ActivityState.Loading &&
            skeleton?.isLoaded(R.id.activityRow1Skeleton) != true
        ) {
            return
        }
        skeleton?.markLoaded(R.id.activityRow1Skeleton)
        val rows = when (val activity = dashboardState.activity) {
            DashboardUiState.ActivityState.Loading -> listOf(
                DashboardUiState.ActivityRow(
                    getString(R.string.dashboard_modern_activity_loading))
            )
            DashboardUiState.ActivityState.Unavailable -> listOf(
                DashboardUiState.ActivityRow(
                    getString(R.string.dashboard_modern_activity_unavailable))
            )
            is DashboardUiState.ActivityState.Ready -> activity.rows.ifEmpty {
                listOf(
                    DashboardUiState.ActivityRow(
                        getString(R.string.dashboard_modern_no_activity))
                )
            }
        }
        activityRows.forEachIndexed { index, views ->
            val row = rows.getOrNull(index)
            views.container.visibility = if (row == null) View.GONE else View.VISIBLE
            if (row == null) return@forEachIndexed
            views.text.text = row.text
            val placeholder = row.icon == 0
            val textLp = views.text.layoutParams as ViewGroup.MarginLayoutParams
            if (placeholder) {
                views.icon.visibility = View.GONE
                textLp.marginStart = 0
            } else {
                views.icon.setImageResource(row.icon)
                views.icon.visibility = View.VISIBLE
                textLp.marginStart = views.text.resources.getDimensionPixelSize(
                    R.dimen.dashboard_modern_gap
                )
            }
            views.text.layoutParams = textLp
            val textAttr = if (placeholder) {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            } else {
                com.google.android.material.R.attr.colorOnSurface
            }
            views.text.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    views.text,
                    textAttr
                )
            )
        }
    }

    private fun tunnelDisplay(): TunnelDisplayPolicy.Result {
        val states = daemonsViewModel.daemonStates.value
        return TunnelDisplayPolicy.resolve(
            daemonsViewModel.zrokController.tunnelUrl.value,
            daemonsViewModel.cloudflaredController.tunnelUrl.value,
            daemonsViewModel.tailscaleController.tunnelUrl.value,
            states?.get(DaemonType.ZROK_TUNNEL)?.status,
            states?.get(DaemonType.CLOUDFLARED_TUNNEL)?.status,
            states?.get(DaemonType.TAILSCALE_TUNNEL)?.status,
        )
    }

    /** "Online · Cloudflared · host" for the remote card subtitle. */
    private fun onlineSummary(url: String?): String {
        val provider = listOf(
            DaemonType.ZROK_TUNNEL,
            DaemonType.CLOUDFLARED_TUNNEL,
            DaemonType.TAILSCALE_TUNNEL,
        ).firstOrNull { url != null && urlFor(it) == url }
        val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        return listOfNotNull(
            getString(R.string.dashboard_tunnel_online),
            provider?.let(::labelFor),
            host?.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
    }

    private fun updateTunnelTile() {
        val display = tunnelDisplay()

        when (display.kind) {
            com.overdrive.app.ui.model.TunnelDisplayPolicy.Kind.ONLINE -> {
                metricTunnelValue.text = onlineSummary(display.url)
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
        // Ignore duplicate LiveData emissions while a QR is being encoded.
        if (!dashboardState.remoteExpanded) return
        if (url.isNullOrEmpty()) {
            qrGeneration++
            pendingQrUrl = null
            lastRenderedQrUrl = null
            hasRenderedQrForView = false
            showPlaceholder()
            return
        }
        if ((hasRenderedQrForView && url == lastRenderedQrUrl) || url == pendingQrUrl) return

        // Clear a previous tunnel's QR immediately: an old URL must never be
        // presented as the newly selected connection while encoding is pending.
        qrGeneration++
        val generation = qrGeneration
        pendingQrUrl = url
        lastRenderedQrUrl = null
        hasRenderedQrForView = false
        showPlaceholder()

        val executor = qrExecutor ?: Executors.newSingleThreadExecutor()
            .also { qrExecutor = it }
        executor.execute {
            val bitmap = try {
                QrCodeGenerator.generate(url, 400)
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                if (!isAdded || view == null || generation != qrGeneration ||
                    !dashboardState.remoteExpanded || pendingQrUrl != url
                ) {
                    bitmap?.recycle() // Result is never installed into an ImageView.
                    return@post
                }
                pendingQrUrl = null
                if (bitmap == null) {
                    showPlaceholder()
                } else {
                    ivQrCode.setImageBitmap(bitmap)
                    qrRow?.visibility = View.VISIBLE
                    qrContainer.visibility = View.VISIBLE
                    ivQrCode.visibility = View.VISIBLE
                    tvQrPlaceholder.visibility = View.GONE
                    tvUrl.text = url
                    tvUrl.visibility = View.VISIBLE
                    lastRenderedQrUrl = url
                    hasRenderedQrForView = true
                }
            }
        }
    }

    private fun showPlaceholder() {
        ivQrCode.setImageDrawable(null)
        ivQrCode.visibility = View.GONE
        qrContainer.visibility = View.GONE
        qrRow?.visibility = View.GONE
        tvQrPlaceholder.visibility = View.VISIBLE
        tvQrPlaceholder.text = getTunnelPlaceholderText()
        tvUrl.visibility = View.GONE
    }

    private fun renderRemoteExpansion(animate: Boolean = false) {
        if (!::remoteDetails.isInitialized) return
        val expanded = dashboardState.remoteExpanded
        if (animate) beginExpandTransition(metricTunnel)
        remoteDetails.visibility = if (expanded) View.VISIBLE else View.GONE
        rotateChevron(btnExpandRemote, expanded, animate)
        metricTunnel.contentDescription = getString(
            if (expanded) {
                R.string.dashboard_modern_collapse_remote
            } else {
                R.string.dashboard_modern_expand_remote
            }
        )
        if (expanded) {
            renderQr(selectedTunnel?.let(::urlFor))
        } else {
            // Prevent an in-flight encode from repainting a collapsed panel.
            qrGeneration++
            pendingQrUrl = null
        }
    }

    /**
     * Height reveal for an expanding card. Bounds animate within the card's column
     * without per-frame layout; contents fade. Reduced motion jumps to the end state.
     */
    private fun beginExpandTransition(card: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val column = card.parent as? ViewGroup ?: return
        val transition = TransitionSet()
            .addTransition(ChangeBounds())
            .addTransition(Fade())
            .setOrdering(TransitionSet.ORDERING_TOGETHER)
            .setDuration(expandDurationMs())
            .setInterpolator(EXPAND_INTERPOLATOR)
        TransitionManager.beginDelayedTransition(column, transition)
    }

    private fun rotateChevron(chevron: View, expanded: Boolean, animate: Boolean) {
        val target = if (expanded) 180f else 0f
        chevron.animate().cancel()
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            chevron.animate()
                .rotation(target)
                .setDuration(expandDurationMs())
                .setInterpolator(EXPAND_INTERPOLATOR)
                .start()
        } else {
            chevron.rotation = target
        }
    }

    private fun expandDurationMs(): Long = resources.getInteger(R.integer.od_motion_expand).toLong()

    /** Brings the remote card's top into view once its expanded height is laid out. */
    private fun scrollRemoteIntoView() {
        val scroll = dashScroll ?: return
        metricTunnel.doOnLayout { card ->
            scroll.post {
                if (view == null || card.parent == null) return@post
                val bounds = Rect(0, 0, card.width, card.height)
                scroll.offsetDescendantRectToMyCoords(card, bounds)
                val margin = resources.getDimensionPixelSize(R.dimen.od_space_16)
                val target = (bounds.top - margin).coerceAtLeast(0)
                if (ValueAnimator.areAnimatorsEnabled()) {
                    scroll.smoothScrollTo(0, target)
                } else {
                    scroll.scrollTo(0, target)
                }
            }
        }
    }

    /** Fades the ribbon out under the text column, in the theme's own surface colour. */
    private fun styleHeroRibbon() {
        val ribbon = heroRibbon ?: return
        val surface = themeColor(com.google.android.material.R.attr.colorSurfaceContainer)
        val clear = ColorUtils.setAlphaComponent(surface, 0)
        // ImageView applies view alpha per draw op, which would half-show the fade;
        // keep the strength on the bitmap so the fade paints opaque.
        ribbon.imageAlpha = (ribbon.alpha * 255f).toInt().coerceIn(0, 255)
        ribbon.alpha = 1f
        ribbon.foreground = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(surface, clear, clear),
        )
        // Mirroring the whole view moves the fade to the start side as well.
        if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            ribbon.scaleX = -1f
        }
    }

    private fun copyUrlToClipboard() {
        val url = lastRenderedQrUrl ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clip_label_url), url))
        appToast?.show(getString(R.string.toast_url_copied_short), AppToast.Kind.SUCCESS)
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
                scheduleAuthRetry(attempt = 1, generation = viewGeneration)
            }
        } catch (e: Exception) {
            tvDeviceToken.text = getString(R.string.dashboard_token_masked)
        }
    }

    private fun scheduleAuthRetry(attempt: Int, generation: Int) {
        // Cap the retry storm at ~10 seconds total (10 attempts × 1s).
        // Anything beyond that is a real config problem, not a daemon
        // boot race; falling back to user-driven onResume()/regenerate
        // is fine.
        if (attempt > 10) return
        mainHandler.postDelayed({
            if (!isAdded || view == null || generation != viewGeneration) return@postDelayed
            val state = AuthManager.getState()
            if (state != null) {
                updateTokenDisplay(state.secret)
            } else {
                scheduleAuthRetry(attempt + 1, generation)
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
        appToast?.show(getString(R.string.toast_access_code_copied), AppToast.Kind.SUCCESS)
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
        // Use the lifecycle-managed metricsExecutor (shut down in onDestroyView)
        // instead of a bare Thread that would outlive the fragment and leak
        // its Activity reference.
        val ctx = context?.applicationContext ?: return
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            // (the daemon makes it: a request to it, off the main thread)
            val newToken = AuthManager.regenerateToken()
            if (newToken == null) {
                // Nothing changed: the old code and every session still work.
                mainHandler.post {
                    if (!isAdded) return@post
                    appToast?.show(ctx.getString(R.string.toast_token_regenerate_failed), AppToast.Kind.WARNING)
                    loadAuthState()
                }
                return@execute
            }
            // The daemon rotated its own secret, so every session already ended.
            val outcome = if (AuthManager.lastRegenerateEndedSessions()) {
                R.string.toast_token_regenerated_logged_out to AppToast.Kind.SUCCESS
            } else try {
                val client = CameraDaemonClient()
                if (client.connect()) {
                    val ok = client.invalidateAuthCacheSync()
                    client.disconnect()
                    if (ok) R.string.toast_token_regenerated_logged_out to AppToast.Kind.SUCCESS
                    else R.string.toast_token_regenerated_restart to AppToast.Kind.WARNING
                } else {
                    R.string.toast_token_regenerated_no_notify to AppToast.Kind.WARNING
                }
            } catch (_: Exception) {
                R.string.toast_token_regenerated to AppToast.Kind.SUCCESS
            }
            mainHandler.post {
                if (!isAdded) return@post
                appToast?.show(ctx.getString(outcome.first), outcome.second)
                loadAuthState()
            }
        }
    }

    // ============== Vehicle tile ==============

    /**
     * Read /api/performance/soh/nominal + /api/models/selected and render
     * "82.5 kWh · BYD Seal" or "Tap to set" if no nominal yet.
     *
     * Both calls run on a worker thread (HTTP). While the nominal is unknown
     * (call failed, or 503 during daemon start) the tile keeps its text and retries.
     */
    private fun refreshVehicleTile() {
        if (metricVehicleValue == null) return
        val generation = viewGeneration
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            var nominalKwh = 0.0
            var nominalKnown = false
            var modelId: String? = null
            try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/performance/soh/nominal", "GET", 2000, 3000)
                if (conn.responseCode == 200) {
                    nominalKnown = true
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
                if (!isAdded || view == null || generation != viewGeneration) return@post
                val tile = metricVehicleValue ?: return@post
                mainHandler.removeCallbacks(vehicleTileRefreshRunnable)
                if (!nominalKnown && dashboardResumed &&
                    vehicleTileRetryCount < MAX_VEHICLE_TILE_RETRIES
                ) {
                    vehicleTileRetryCount += 1
                    mainHandler.postDelayed(
                        vehicleTileRefreshRunnable,
                        VEHICLE_TILE_RETRY_MS * vehicleTileRetryCount,
                    )
                    return@post
                }
                vehicleTileRetryCount = 0
                if (nominalKwh > 0) {
                    tile.text = if (modelId != null) {
                        getString(R.string.dash_vehicle_line, modelDisplayName(modelId), nominalKwh)
                    } else {
                        String.format("%.1f kWh", nominalKwh)
                    }
                } else {
                    tile.text = getString(R.string.dashboard_vehicle_tap_to_set)
                }
                // Follows the model, not nominalKwh — a model can be selected
                // before a capacity is entered.
                vehicleArt?.setImageResource(VehicleArt.drawableFor(modelId))
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

        val summaryDetection = dialogView.findViewById<TextView>(R.id.vehicleSummaryDetection)
        val summaryCapacity = dialogView.findViewById<TextView>(R.id.vehicleSummaryCapacity)
        val summarySoh = dialogView.findViewById<TextView>(R.id.vehicleSummarySoh)
        val summaryEffective = dialogView.findViewById<TextView>(R.id.vehicleSummaryEffective)
        val summaryModel = dialogView.findViewById<TextView>(R.id.vehicleSummaryModel)
        val summaryCalibration = dialogView.findViewById<TextView>(R.id.vehicleSummaryCalibration)
        val capInput = dialogView.findViewById<
            com.google.android.material.textfield.TextInputEditText>(R.id.vehicleCapacityInput)
        val capLayout = dialogView.findViewById<
            com.google.android.material.textfield.TextInputLayout>(R.id.vehicleCapacityLayout)
        val modelDropdown = dialogView.findViewById<
            com.google.android.material.textfield.MaterialAutoCompleteTextView>(
            R.id.vehicleModelDropdown)
        val resetButton = dialogView.findViewById<
            com.google.android.material.button.MaterialButton>(R.id.vehicleResetAuto)

        // Nothing is known to reset until the fetch lands, and only a manually
        // set capacity is resettable. Held so re-enabling after a failed save
        // cannot offer a reset that was never available.
        var resetEligible = false
        // The summary fetch answers on a background thread and posts back. If it
        // lands after the user has already hit Save, it must not re-enable the
        // controls mid-write.
        var saveInFlight = false
        resetButton.isEnabled = false

        capInput.doAfterTextChanged { capLayout.error = null }

        // Placeholders from the start: the fetch below is off the main thread,
        // and filling empty rows on its return would resize the live dialog.
        val pendingValue = getString(R.string.vehicle_dialog_value_pending)
        summaryDetection.text = getString(R.string.vehicle_dialog_detection, pendingValue)
        summaryCapacity.text = pendingValue
        summarySoh.text = pendingValue
        summaryModel.text = pendingValue

        // Track the selected model's id locally (the dropdown's text holds
        // the user-facing title; the id is what we POST). Each entry also
        // carries the manifest's canonical nominalKwh so picking a model
        // can auto-fill the capacity input. The list is refreshed from
        // the manifest below.
        data class ModelEntry(val id: String, val title: String, val nominalKwh: Double)
        val modelEntries = mutableListOf<ModelEntry>()
        var selectedModelId: String? = null
        var modelSelectionChanged = false
        modelDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position in modelEntries.indices) {
                val entry = modelEntries[position]
                selectedModelId = entry.id
                modelSelectionChanged = true
                // Auto-fill the capacity field with the manifest's
                // canonical nominalKwh for this model. The user can still
                // edit it before saving — this is just a sensible starting
                // value rather than leaving the field showing the previous
                // model's number.
                if (entry.nominalKwh > 0) {
                    // Locale.ROOT: the field accepts only ASCII digits and '.', so a localized
                    // prefill ("82,5", Arabic digits) could not be saved again.
                    capInput.setText(String.format(java.util.Locale.ROOT, "%.1f", entry.nominalKwh))
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
                if (initialKwh > 0) capInput.setText(String.format(java.util.Locale.ROOT, "%.1f", initialKwh))

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

                // A typed capacity or a picked model takes the pack out of
                // auto-detection. Model state has to come from
                // /api/models/selected: the status modelId also reports
                // auto-detected models.
                val manual = finalNominalSource == "user" || initialModelId != null
                summaryDetection.text = getString(
                    R.string.vehicle_dialog_detection,
                    getString(
                        if (manual) {
                            R.string.vehicle_dialog_detection_manual
                        } else {
                            R.string.vehicle_dialog_detection_auto
                        }
                    )
                )
                resetEligible = manual
                resetButton.isEnabled = manual && !saveInFlight

                summaryCapacity.text = if (finalNominalKwh > 0) {
                    String.format("%.1f kWh", finalNominalKwh)
                } else {
                    getString(R.string.soh_dialog_capacity_not_detected)
                }

                summarySoh.text = when {
                    finalDisplaySoh > 0 && finalDisplaySource == "oem" ->
                        String.format("%.1f%% (vehicle)", finalDisplaySoh)
                    finalDisplaySoh > 0 && finalDisplaySource == "live" ->
                        String.format("%.1f%% (live)", finalDisplaySoh)
                    finalDisplaySoh > 0 && finalDisplaySource == "calibration" ->
                        String.format("%.1f%% (from last charge)", finalDisplaySoh)
                    finalDisplaySoh > 0 -> String.format("%.1f%%", finalDisplaySoh)
                    else -> getString(R.string.vehicle_dialog_soh_unavailable)
                        .replaceFirstChar { it.uppercase() }
                }

                if (finalEstimatedKwh > 0) {
                    summaryEffective.text = getString(
                        R.string.vehicle_dialog_summary_effective, finalEstimatedKwh)
                    summaryEffective.visibility = View.VISIBLE
                }

                summaryModel.text = if (finalStatusModelId != null) {
                    modelDisplayName(finalStatusModelId)
                } else {
                    getString(R.string.soh_dialog_model_not_selected)
                }

                if (finalCalSoh > 0 && finalCalTs > 0) {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(java.util.Date(finalCalTs))
                    summaryCalibration.text = getString(
                        R.string.vehicle_dialog_summary_calibration, finalCalSoh, date)
                    summaryCalibration.visibility = View.VISIBLE
                }
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
            .setNegativeButton(getString(R.string.action_cancel), null)
            .create()
        resetButton.setOnClickListener {
            completionDeferred = true
            postNominal(
                kwh = null,
                clearModelSelection = true,
                onComplete = { finishOnce() },
            )
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            val saveButton =
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            val cancelButton =
                dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
            fun setSaving(saving: Boolean) {
                saveInFlight = saving
                saveButton.isEnabled = !saving
                resetButton.isEnabled = !saving && resetEligible
                cancelButton.isEnabled = !saving
                dialog.setCancelable(!saving)
                dialog.setCanceledOnTouchOutside(!saving)
            }
            saveButton.setOnClickListener {
                val raw = capInput.text?.toString()?.trim().orEmpty()
                val lastSeparator = raw.lastIndexOfAny(charArrayOf('.', ','))
                val normalized = if (lastSeparator >= 0) {
                    raw.substring(0, lastSeparator).replace(",", "").replace(".", "") +
                        "." + raw.substring(lastSeparator + 1)
                } else {
                    raw
                }
                val kwh = normalized.toDoubleOrNull()
                if (kwh == null || !kwh.isFinite() || kwh < 5.0 || kwh > 120.0) {
                    capLayout.error = getString(R.string.vehicle_dialog_invalid_capacity)
                    return@setOnClickListener
                }
                completionDeferred = true
                setSaving(true)
                postNominalAndModel(
                    kwh,
                    selectedModelId.takeIf { modelSelectionChanged },
                ) { error ->
                    if (error == null) {
                        finishOnce()
                        dialog.dismiss()
                    } else {
                        completionDeferred = false
                        setSaving(false)
                        appToast?.show(
                            getString(R.string.toast_failed_with_message, error),
                            AppToast.Kind.ERROR,
                        )
                    }
                }
            }
            cancelButton.setOnClickListener {
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
        onComplete: ((String?) -> Unit)? = null,
    ) {
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            val error = if (!modelId.isNullOrEmpty()) {
                postJsonResult(
                    "/api/models/selected",
                    org.json.JSONObject()
                        .put("modelId", modelId)
                        .put("nominalKwh", kwh),
                    "ok",
                )
            } else {
                postJsonResult(
                    "/api/performance/soh/nominal",
                    org.json.JSONObject().put("nominalKwh", kwh),
                    "success",
                )
            }

            mainHandler.post {
                if (error == null) refreshVehicleTile()
                onComplete?.invoke(error)
            }
        }
    }

    private fun postJsonResult(
        path: String,
        body: org.json.JSONObject,
        successKey: String,
    ): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = com.overdrive.app.util.DaemonHttpClient.open(
                path, "POST", 3000, 5000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val response = responseBody.takeIf { it.isNotBlank() }
                ?.let { org.json.JSONObject(it) }
            if (status in 200..299 && response?.optBoolean(successKey, false) == true) {
                null
            } else {
                response?.optString("error", "")?.takeIf { it.isNotBlank() }
                    ?: "HTTP $status"
            }
        } catch (t: Throwable) {
            t.message?.takeIf { it.isNotBlank() } ?: "Network error"
        } finally {
            conn?.disconnect()
        }
    }

    private fun modelDisplayName(modelId: String?): String {
        return when (modelId?.lowercase()) {
            null -> "—"
            "seal" -> "BYD Seal"
            "atto3", "atto-3" -> "BYD Atto 3"
            "atto3evo", "atto3-evo", "atto-3-evo" -> "BYD Atto 3 Evo"
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
            "shark" -> "BYD Shark"
            "sealu", "seal-u" -> "BYD Seal U"
            else -> modelId.replaceFirstChar { it.uppercase() }
        }
    }

    companion object {
        /** Boolean argument: open with the remote access card expanded and scrolled into view. */
        const val ARG_EXPAND_REMOTE = "od_expand_remote"
        private const val REMOTE_REALIGN_WINDOW_MS = 6_000L
        private val CORE_DAEMONS = setOf(
            DaemonType.CAMERA_DAEMON, DaemonType.SENTRY_DAEMON, DaemonType.ACC_SENTRY_DAEMON,
        )
        private const val STATE_REMOTE_EXPANDED = "dashboard.remote_expanded"
        private const val STATE_AI_INSIGHT_EXPANDED =
            "dashboard.ai_insight_expanded"
        private const val STATE_SELECTED_TUNNEL = "dashboard.selected_tunnel"
        private const val STATUS_REFRESH_MS = 15_000L
        private const val RECORDING_STATS_RETRY_MS = 1_500L
        private const val MAX_RECORDING_STATS_RETRIES = 3
        // Linear backoff, ~110 s in total: covers a cold daemon start after an update.
        private const val VEHICLE_TILE_RETRY_MS = 2_000L
        private const val MAX_VEHICLE_TILE_RETRIES = 10
        private const val STATUS_CONNECT_TIMEOUT_MS = 2_000
        private const val STATUS_READ_TIMEOUT_MS = 4_000
        private const val RANGE_CONNECT_TIMEOUT_MS = 1_000
        private const val RANGE_READ_TIMEOUT_MS = 1_500
        /** SOC at or below this flips the hero gauge to the error colour. */
        private const val LOW_SOC_THRESHOLD_PERCENT = 20
        private const val WELCOME_INSIGHT_PRIORITY = 100
        private const val AI_INSIGHT_PREVIEW_LINES = 2
        private const val MIN_TEXT_CONTRAST = 4.5
        // Unit sizes relative to the figure: 30/68sp, 16/28sp, 15/34sp.
        private const val SOC_UNIT_SCALE = 30f / 68f
        private const val RANGE_UNIT_SCALE = 16f / 28f
        private const val CLIPS_UNIT_SCALE = 15f / 34f
        private val EXPAND_INTERPOLATOR = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)
    }
}
