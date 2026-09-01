package com.overdrive.app.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
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
import com.overdrive.app.ui.model.localizedName
import com.overdrive.app.ui.util.QrCodeGenerator
import com.overdrive.app.ui.util.RecordingScanner
import com.overdrive.app.ui.util.RecordingsApiClient
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.viewmodel.MainViewModel
import com.overdrive.app.ui.viewmodel.RecordingViewModel
import com.overdrive.app.util.DeviceIdGenerator
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** State-driven dashboard backed by the daemon's existing /status contract. */
class DashboardFragment : Fragment() {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    private val recordingViewModel: RecordingViewModel by activityViewModels()

    // Hero
    private lateinit var heroCard: MaterialCardView
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

    // Stable activity rows.
    private lateinit var activityRow1: TextView
    private lateinit var activityRow2: TextView
    private lateinit var activityRow3: TextView

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
    private lateinit var btnExpandRemote: ImageButton
    private var selectedTunnel: DaemonType? = null
    private var lastRenderedQrUrl: String? = null
    private var hasRenderedQrForView = false

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
    private var vehicleHalRangeDivider: View? = null
    private var vehicleHalRangeValue: TextView? = null
    private var quickTrips: View? = null
    private var quickVehicleControl: View? = null

    // Background work for storage / recording-count tiles. Single thread is enough
    // — both probes are just a directory walk, and serializing them keeps disk I/O
    // out of the UI thread without contending with itself.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var metricsExecutor: ExecutorService? = null

    private var dashboardState = DashboardUiState()
    private var todayClipCount: Int? = null
    private var storageSummary: DashboardUiState.StorageSummary? = null
    private var viewGeneration: Int = 0
    private var dashboardResumed: Boolean = false
    private var recordingStatsRetryCount: Int = 0

    private var insightsProvider: DashboardInsightProvider? = null
    private var firstVisitCount: Int = -1
    private val statusRefreshRunnable = Runnable { refreshVehicleStatus(showLoading = false) }
    private val recordingStatsRefreshRunnable = Runnable { refreshMetricsTiles() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewGeneration += 1

        bindViews(view)
        wireClicks()
        observeViewModels()

        dashboardState = DashboardStateReducer.remoteExpanded(
            DashboardUiState(),
            savedInstanceState?.getBoolean(STATE_REMOTE_EXPANDED, false) == true,
        )
        selectedTunnel = savedInstanceState
            ?.getString(STATE_SELECTED_TUNNEL)
            ?.let { runCatching { DaemonType.valueOf(it) }.getOrNull() }
        aiInsightExpanded = savedInstanceState
            ?.getBoolean(STATE_AI_INSIGHT_EXPANDED, false) == true
        renderDashboardState()
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
        refreshVehicleTile()
        rebuildInsightsAsync()
        loadAuthState()
    }

    override fun onPause() {
        dashboardResumed = false
        mainHandler.removeCallbacks(statusRefreshRunnable)
        mainHandler.removeCallbacks(recordingStatsRefreshRunnable)
        super.onPause()
    }

    override fun onDestroyView() {
        dashboardResumed = false
        viewGeneration += 1
        mainHandler.removeCallbacks(statusRefreshRunnable)
        mainHandler.removeCallbacks(recordingStatsRefreshRunnable)
        if (::ivQrCode.isInitialized) ivQrCode.setImageDrawable(null)
        lastRenderedQrUrl = null
        hasRenderedQrForView = false
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

    private fun bindViews(view: View) {
        heroCard = view.findViewById(R.id.heroCard)
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
        activityRow1 = view.findViewById(R.id.activityRow1)
        activityRow2 = view.findViewById(R.id.activityRow2)
        activityRow3 = view.findViewById(R.id.activityRow3)
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
        vehicleHalRangeDivider = view.findViewById(R.id.vehicleHalRangeDivider)
        vehicleHalRangeValue = view.findViewById(R.id.vehicleHalRangeValue)

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
        quickTrips?.setOnClickListener {
            findNavController().navigate(R.id.tripsFragment, null, fadeThrough)
        }
        quickVehicleControl?.setOnClickListener {
            findNavController().navigate(R.id.vehicleControlFragment, null, fadeThrough)
        }
        aiInsightCard.setOnClickListener {
            aiInsightExpanded = !aiInsightExpanded
            renderAiInsightExpansion()
        }
        metricVehicle?.setOnClickListener { showVehicleCapacityDialog() }

        btnToggleToken.setOnClickListener { toggleTokenVisibility() }
        btnCopyToken.setOnClickListener { copyTokenToClipboard() }
        btnRegenerateToken.setOnClickListener { showRegenerateConfirmation() }
        btnExpandRemote.setOnClickListener {
            dashboardState = DashboardStateReducer.remoteExpanded(
                dashboardState,
                !dashboardState.remoteExpanded,
            )
            renderRemoteExpansion()
        }
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
            updateHeroSubtitle(computeCoreHealth(states))
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
            updateRecordingState()
        }
    }

    /**
     * Push the latest tunnel / services / recording status into the three
     * hero chips. Each chip just mirrors the corresponding metric tile
     * value, but at the top of the hero they're discoverable at-a-glance
     * without needing to scan the 5-tile grid.
     *
     */
    private fun refreshHeroChips() {
        heroChipTunnel.text = metricTunnelValue.text
        heroChipServices.text = tvDaemonsStatus.text
        val recording = recordingViewModel.isRecording.value == true
        heroChipRecording.text = if (recording) {
            getString(R.string.dashboard_chip_recording_active)
        } else {
            getString(R.string.dashboard_chip_recording_idle)
        }
    }

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
                updateRecordingState()
                mainHandler.removeCallbacks(recordingStatsRefreshRunnable)
                if (shouldRetry &&
                    dashboardResumed &&
                    recordingStatsRetryCount < MAX_RECORDING_STATS_RETRIES
                ) {
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
        when (val recording = dashboardState.recordings) {
            DashboardUiState.RecordingState.Loading -> {
                metricRecordingsValue.setText(R.string.dashboard_metric_value_pending)
                metricStorageValue.setText(R.string.dashboard_modern_updating)
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
                    recordingViewModel.isRecording.value == true ->
                        getString(R.string.dashboard_recordings_value_live, count)
                    else -> getString(R.string.dashboard_modern_clips_today, count)
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
                dashboardState = DashboardStateReducer.status(dashboardState, result)
                renderVehicleState()
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
        when (val vehicle = dashboardState.vehicle) {
            DashboardUiState.VehicleState.Loading -> {
                heroGreeting.setText(R.string.dashboard_modern_vehicle_now)
                heroSubtitle.setText(R.string.dashboard_modern_updating)
                vehicleSocValue.setText(R.string.dashboard_metric_value_pending)
                vehicleRangeValue.setText(R.string.dashboard_metric_value_pending)
                vehicleRangeLabel?.setText(R.string.dashboard_modern_range)
                setHalRangeColumnVisible(false)
                chargingCard.visibility = View.GONE
                renderSocGauge(null)
                renderRangeBreakdown(null)
            }
            is DashboardUiState.VehicleState.Unavailable -> {
                heroGreeting.setText(R.string.dashboard_modern_vehicle_now)
                heroSubtitle.setText(R.string.dashboard_modern_vehicle_unavailable)
                vehicleSocValue.setText(R.string.dashboard_metric_value_pending)
                vehicleRangeValue.setText(R.string.dashboard_metric_value_pending)
                vehicleRangeLabel?.setText(R.string.dashboard_modern_range)
                setHalRangeColumnVisible(false)
                chargingCard.visibility = View.GONE
                renderSocGauge(null)
                renderRangeBreakdown(null)
            }
            is DashboardUiState.VehicleState.Ready -> {
                val snapshot = vehicle.snapshot
                heroGreeting.setText(R.string.dashboard_modern_vehicle_now)
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
                    getString(R.string.dashboard_modern_percent, it)
                } ?: getString(R.string.dashboard_metric_value_pending)
                // Personalized range (learned from the driver's own trips via
                // /api/trips/range) headlines the metric when available; the
                // plain HAL estimate stays as the fallback so the card never
                // regresses to a blank value.
                // When BOTH figures exist, the vehicle's own estimate gets its
                // side-by-side column so the two can be compared at a glance.
                val personalized = snapshot.rangeDetails?.personalized
                vehicleRangeValue.text = (personalized ?: snapshot.range)?.let {
                    getString(R.string.dashboard_modern_distance, it.value, it.unit.label)
                } ?: getString(R.string.dashboard_metric_value_pending)
                vehicleRangeLabel?.setText(
                    if (personalized != null) {
                        R.string.dashboard_modern_personalized_range
                    } else {
                        R.string.dashboard_modern_range
                    }
                )
                val halRange = snapshot.range
                val showHalColumn = personalized != null && halRange != null
                if (showHalColumn && halRange != null) {
                    vehicleHalRangeValue?.text = getString(
                        R.string.dashboard_modern_distance, halRange.value, halRange.unit.label
                    )
                }
                setHalRangeColumnVisible(showHalColumn)
                renderSocGauge(snapshot.socPercent)
                renderRangeBreakdown(snapshot.rangeDetails)
                renderCharging(snapshot.charging)
            }
        }
    }

    /** Show/hide the side-by-side HAL range column and its divider together. */
    private fun setHalRangeColumnVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        vehicleHalRangeColumn?.visibility = visibility
        vehicleHalRangeDivider?.visibility = visibility
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
     * Visual battery gauge under the numeric SOC. INVISIBLE (not GONE) when
     * SOC is unknown so the hero card's height never jumps between the
     * loading and ready states. Indicator colour flips to the theme's error
     * colour at ≤20% so a low battery reads at a glance; both attrs resolve
     * per-theme, so light/dark are handled automatically.
     */
    private fun renderSocGauge(socPercent: Double?) {
        val gauge = heroSocProgress ?: return
        if (socPercent == null) {
            gauge.visibility = View.INVISIBLE
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
        gauge.visibility = View.VISIBLE
    }

    private fun renderCharging(charging: com.overdrive.app.ui.dashboard.DashboardChargingSnapshot?) {
        if (charging == null) {
            chargingCard.visibility = View.GONE
            return
        }
        chargingCard.visibility = View.VISIBLE
        chargingStateValue.text = when {
            charging.fault -> getString(R.string.dashboard_modern_charge_fault)
            charging.full -> getString(R.string.dashboard_modern_charge_complete)
            charging.charging -> getString(R.string.dashboard_modern_charging)
            else -> charging.stateName ?: getString(R.string.dashboard_modern_plugged_in)
        }
        renderOptionalMetric(
            chargingPowerGroup,
            chargingPowerValue,
            charging.powerKw?.let {
                getString(
                    if (charging.powerEstimated) {
                        R.string.dashboard_modern_charge_power_estimated
                    } else {
                        R.string.dashboard_modern_charge_power
                    },
                    it,
                )
            },
        )
        renderOptionalMetric(
            chargingEtaGroup,
            chargingEtaValue,
            charging.timeToFullMinutes?.let(::formatChargeDuration),
        )
        renderOptionalMetric(
            chargingSessionGroup,
            chargingSessionValue,
            charging.sessionKwh?.let {
                val rendered = getString(R.string.dashboard_modern_charge_session, it)
                if (charging.sessionEnergyEstimated
                    || charging.sessionEnergyIncomplete
                ) {
                    "~$rendered"
                } else {
                    rendered
                }
            },
        )
        normalizeChargingMetricMargins()
    }

    private fun renderOptionalMetric(group: View, valueView: TextView, value: String?) {
        group.visibility = if (value == null) View.GONE else View.VISIBLE
        if (value != null) valueView.text = value
    }

    private fun normalizeChargingMetricMargins() {
        val gap = resources.getDimensionPixelSize(R.dimen.dashboard_modern_metric_gap)
        var visibleIndex = 0
        listOf(chargingPowerGroup, chargingEtaGroup, chargingSessionGroup).forEach { group ->
            if (group.visibility != View.VISIBLE) return@forEach
            val params = group.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
            val margin = if (visibleIndex == 0) 0 else gap
            if (params.marginStart != margin) {
                params.marginStart = margin
                group.layoutParams = params
            }
            visibleIndex += 1
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

    private fun updateHeroSubtitle(coreHealth: CoreHealth) {
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
            ?.map { it.text.toString() }
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

    private fun renderAiInsightExpansion() {
        if (!::aiInsightCard.isInitialized) return
        aiInsightText.maxLines =
            if (aiInsightExpanded) Int.MAX_VALUE else AI_INSIGHT_PREVIEW_LINES
        aiInsightText.ellipsize =
            if (aiInsightExpanded) null else TextUtils.TruncateAt.END
        aiInsightExpand.rotation = if (aiInsightExpanded) 180f else 0f
        aiInsightCard.contentDescription = getString(
            if (aiInsightExpanded) {
                R.string.dashboard_ai_insight_collapse
            } else {
                R.string.dashboard_ai_insight_open
            }
        )
    }

    private fun renderActivityState() {
        if (!::activityRow1.isInitialized) return
        val rows = when (val activity = dashboardState.activity) {
            DashboardUiState.ActivityState.Loading ->
                listOf(getString(R.string.dashboard_modern_activity_loading))
            DashboardUiState.ActivityState.Unavailable ->
                listOf(getString(R.string.dashboard_modern_activity_unavailable))
            is DashboardUiState.ActivityState.Ready ->
                activity.rows.ifEmpty { listOf(getString(R.string.dashboard_modern_no_activity)) }
        }
        val views = listOf(activityRow1, activityRow2, activityRow3)
        views.forEachIndexed { index, textView ->
            val text = rows.getOrNull(index)
            textView.visibility = if (text == null) View.GONE else View.VISIBLE
            if (text != null) textView.text = text
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
        // Tunnel status and URL LiveData can both emit unchanged values during the
        // 30-second refresh. Do no bitmap work while this section is hidden, and
        // don't regenerate the same QR for each duplicate observer notification.
        if (!dashboardState.remoteExpanded) return

        if (url.isNullOrEmpty()) {
            // Placeholder copy depends on daemon state, not just the null URL, so
            // keep this cheap text path live while caching only real QR bitmaps.
            showPlaceholder()
            lastRenderedQrUrl = null
            hasRenderedQrForView = false
            return
        }
        if (hasRenderedQrForView && url == lastRenderedQrUrl) return
        try {
            val qrBitmap = QrCodeGenerator.generate(url, 400)
            if (qrBitmap != null) {
                ivQrCode.setImageBitmap(qrBitmap)
                qrContainer.visibility = View.VISIBLE
                ivQrCode.visibility = View.VISIBLE
                tvQrPlaceholder.visibility = View.GONE
                tvUrl.text = url
                tvUrl.visibility = View.VISIBLE
                lastRenderedQrUrl = url
                hasRenderedQrForView = true
            } else {
                showPlaceholder()
            }
        } catch (e: Exception) {
            showPlaceholder()
        }
    }

    private fun showPlaceholder() {
        ivQrCode.setImageDrawable(null)
        ivQrCode.visibility = View.GONE
        qrContainer.visibility = View.GONE
        tvQrPlaceholder.visibility = View.VISIBLE
        tvQrPlaceholder.text = getTunnelPlaceholderText()
        tvUrl.visibility = View.GONE
    }

    private fun renderRemoteExpansion() {
        if (!::remoteDetails.isInitialized) return
        val expanded = dashboardState.remoteExpanded
        remoteDetails.visibility = if (expanded) View.VISIBLE else View.GONE
        btnExpandRemote.rotation = if (expanded) 180f else 0f
        btnExpandRemote.contentDescription = getString(
            if (expanded) {
                R.string.dashboard_modern_collapse_remote
            } else {
                R.string.dashboard_modern_expand_remote
            }
        )
        if (expanded) {
            renderQr(selectedTunnel?.let(::urlFor))
        }
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
        if (metricVehicleValue == null) return
        val generation = viewGeneration
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
                if (!isAdded || view == null || generation != viewGeneration) return@post
                val tile = metricVehicleValue ?: return@post
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
            "shark6" -> "BYD Shark 6"
            "sealu", "seal-u" -> "BYD Seal U"
            else -> modelId.replaceFirstChar { it.uppercase() }
        }
    }

    companion object {
        private const val STATE_REMOTE_EXPANDED = "dashboard.remote_expanded"
        private const val STATE_AI_INSIGHT_EXPANDED =
            "dashboard.ai_insight_expanded"
        private const val STATE_SELECTED_TUNNEL = "dashboard.selected_tunnel"
        private const val STATUS_REFRESH_MS = 15_000L
        private const val RECORDING_STATS_RETRY_MS = 1_500L
        private const val MAX_RECORDING_STATS_RETRIES = 3
        private const val STATUS_CONNECT_TIMEOUT_MS = 2_000
        private const val STATUS_READ_TIMEOUT_MS = 4_000
        private const val RANGE_CONNECT_TIMEOUT_MS = 1_000
        private const val RANGE_READ_TIMEOUT_MS = 1_500
        /** SOC at or below this flips the hero gauge to the error colour. */
        private const val LOW_SOC_THRESHOLD_PERCENT = 20
        private const val WELCOME_INSIGHT_PRIORITY = 100
        private const val AI_INSIGHT_PREVIEW_LINES = 5
    }
}
