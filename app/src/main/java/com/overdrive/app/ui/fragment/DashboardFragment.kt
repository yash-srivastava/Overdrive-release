package com.overdrive.app.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.overdrive.app.auth.AuthManager
import com.overdrive.app.client.CameraDaemonClient
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.util.QrCodeGenerator
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.viewmodel.MainViewModel
import com.overdrive.app.ui.viewmodel.RecordingViewModel
import com.overdrive.app.util.DeviceIdGenerator
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.overdrive.app.R

/**
 * Dashboard fragment showing QR code, URL, and quick status overview.
 */
class DashboardFragment : Fragment() {
    
    private val mainViewModel: MainViewModel by activityViewModels()
    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    private val recordingViewModel: RecordingViewModel by activityViewModels()
    
    private lateinit var ivQrCode: ImageView
    private lateinit var tvQrPlaceholder: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvDaemonsStatus: TextView
    private lateinit var tvRecordingStatus: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvAccessMode: TextView
    private lateinit var cardDaemons: MaterialCardView
    private lateinit var cardRecording: MaterialCardView
    private lateinit var chipGroupTunnels: ChipGroup

    private var selectedTunnel: DaemonType? = null
    
    // Auth UI elements
    private lateinit var tvDeviceToken: TextView
    private lateinit var btnToggleToken: ImageView
    private lateinit var btnCopyToken: ImageView
    private lateinit var btnRegenerateToken: MaterialButton
    
    private var isTokenVisible = false
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupClickListeners()
        observeViewModels()
        
        // Set device ID
        tvDeviceId.text = DeviceIdGenerator.generateDeviceId(requireContext())
        
        // Load auth state
        loadAuthState()
    }
    
    private fun initViews(view: View) {
        ivQrCode = view.findViewById(R.id.ivQrCode)
        tvQrPlaceholder = view.findViewById(R.id.tvQrPlaceholder)
        tvUrl = view.findViewById(R.id.tvUrl)
        tvDaemonsStatus = view.findViewById(R.id.tvDaemonsStatus)
        tvRecordingStatus = view.findViewById(R.id.tvRecordingStatus)
        tvDeviceId = view.findViewById(R.id.tvDeviceId)
        tvAccessMode = view.findViewById(R.id.tvAccessMode)
        cardDaemons = view.findViewById(R.id.cardDaemons)
        cardRecording = view.findViewById(R.id.cardRecording)
        chipGroupTunnels = view.findViewById(R.id.chipGroupTunnels)
        
        // Auth UI
        tvDeviceToken = view.findViewById(R.id.tvDeviceToken)
        btnToggleToken = view.findViewById(R.id.btnToggleToken)
        btnCopyToken = view.findViewById(R.id.btnCopyToken)
        btnRegenerateToken = view.findViewById(R.id.btnRegenerateToken)
    }
    
    private fun setupClickListeners() {
        // Navigate to daemons screen on card click
        cardDaemons.setOnClickListener {
            findNavController().navigate(R.id.daemonsFragment)
        }
        
        // Navigate to recording screen on card click
        cardRecording.setOnClickListener {
            findNavController().navigate(R.id.recordingFragment)
        }
        
        // Auth UI click listeners
        btnToggleToken.setOnClickListener {
            toggleTokenVisibility()
        }
        
        btnCopyToken.setOnClickListener {
            copyTokenToClipboard()
        }
        
        btnRegenerateToken.setOnClickListener {
            showRegenerateConfirmation()
        }
    }
    
    private fun observeViewModels() {
        // Observe access mode (display only — QR/URL are driven by per-tunnel observers below)
        mainViewModel.accessMode.observe(viewLifecycleOwner) { mode ->
            tvAccessMode.text = mode.name
            rebuildTunnelChips()
        }

        // Observe daemon states for quick status + chip refresh (covers stop/disable cases)
        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            val running = states.values.count { it.status == DaemonStatus.RUNNING }
            val total = states.size
            tvDaemonsStatus.text = getString(R.string.dashboard_daemons_running, running, total)
            rebuildTunnelChips()
        }

        // Observe each tunnel controller so chips rebuild when URLs appear/disappear
        val rebuild = Observer<String?> { _ -> rebuildTunnelChips() }
        daemonsViewModel.cloudflaredController.tunnelUrl.observe(viewLifecycleOwner, rebuild)
        daemonsViewModel.zrokController.tunnelUrl.observe(viewLifecycleOwner, rebuild)
        daemonsViewModel.tailscaleController.tunnelUrl.observe(viewLifecycleOwner, rebuild)

        // Observe recording state
        recordingViewModel.isRecording.observe(viewLifecycleOwner) { isRecording ->
            tvRecordingStatus.text = if (isRecording) getString(R.string.dashboard_recording_active) else getString(R.string.dashboard_recording_idle_label)
            tvRecordingStatus.setTextColor(
                resources.getColor(
                    if (isRecording) R.color.status_error else R.color.status_stopped,
                    null
                )
            )
        }
    }

    /**
     * Rebuild the tunnel chip group based on current per-controller URLs.
     * Preserves selection across rebuilds when possible; falls back to the first
     * available tunnel when the previously-selected one disappears.
     */
    private fun rebuildTunnelChips() {
        val available = collectAvailableTunnels()

        if (available.isEmpty()) {
            chipGroupTunnels.removeAllViews()
            chipGroupTunnels.visibility = View.GONE
            selectedTunnel = null
            renderQr(null)
            return
        }

        // Decide selection: keep previous if still present, else first.
        val newSelection = selectedTunnel?.takeIf { prev -> available.any { it.first == prev } }
            ?: available.first().first
        selectedTunnel = newSelection

        // Rebuild chip set if mismatched (count or order changed).
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

        // Mark the right chip checked (silently — listener guards against re-firing).
        for (i in 0 until chipGroupTunnels.childCount) {
            val chip = chipGroupTunnels.getChildAt(i) as Chip
            chip.isChecked = (chip.tag as DaemonType) == newSelection
        }

        chipGroupTunnels.visibility = if (available.size > 1) View.VISIBLE else View.GONE
        renderQr(urlFor(newSelection))
    }

    private fun collectAvailableTunnels(): List<Pair<DaemonType, String>> {
        val list = mutableListOf<Pair<DaemonType, String>>()
        daemonsViewModel.cloudflaredController.tunnelUrl.value
            ?.takeIf { it.isNotEmpty() }
            ?.let { list.add(DaemonType.CLOUDFLARED_TUNNEL to it) }
        daemonsViewModel.zrokController.tunnelUrl.value
            ?.takeIf { it.isNotEmpty() }
            ?.let { list.add(DaemonType.ZROK_TUNNEL to it) }
        daemonsViewModel.tailscaleController.tunnelUrl.value
            ?.takeIf { it.isNotEmpty() }
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
        else -> type.displayName
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
    
    /**
     * Get appropriate placeholder text based on tunnel daemon state.
     */
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
    
    // ==================== AUTH UI METHODS ====================
    
    private fun loadAuthState() {
        try {
            val state = AuthManager.getState()
            if (state != null) {
                // Show only the secret (8 chars) - device ID is shown on login page
                updateTokenDisplay(state.secret)
            } else {
                // Initialize auth if not done
                AuthManager.initialize()
                loadAuthState()
            }
        } catch (e: Exception) {
            tvDeviceToken.text = "••••••••"
        }
    }
    
    private fun updateTokenDisplay(secret: String) {
        if (isTokenVisible) {
            tvDeviceToken.text = secret
        } else {
            tvDeviceToken.text = "••••••••"
        }
    }
    
    private fun toggleTokenVisibility() {
        isTokenVisible = !isTokenVisible
        val state = AuthManager.getState()
        if (state != null) {
            updateTokenDisplay(state.secret)
        }
        
        // Update icon
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
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_regenerate_token_title))
            .setMessage(getString(R.string.dialog_regenerate_token_message))
            .setPositiveButton(getString(R.string.dialog_regenerate)) { _, _ ->
                regenerateToken()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }
    
    private fun regenerateToken() {
        // Regenerate the token
        AuthManager.regenerateToken()
        
        // Notify daemon to invalidate its cached auth state via IPC
        // This ensures old JWTs are rejected immediately
        Thread {
            try {
                val client = CameraDaemonClient()
                if (client.connect()) {
                    val success = client.invalidateAuthCacheSync()
                    client.disconnect()
                    
                    activity?.runOnUiThread {
                        if (success) {
                            Toast.makeText(requireContext(), getString(R.string.toast_token_regenerated_logged_out), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.toast_token_regenerated_restart), Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), getString(R.string.toast_token_regenerated_no_notify), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), getString(R.string.toast_token_regenerated), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
        
        // Update UI
        loadAuthState()
    }
}
