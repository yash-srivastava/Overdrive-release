package com.overdrive.app.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.overdrive.app.auth.AuthManager
import com.overdrive.app.client.CameraDaemonClient
import com.overdrive.app.ui.model.AccessMode
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.util.QrCodeGenerator
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.viewmodel.MainViewModel
import com.overdrive.app.ui.viewmodel.RecordingViewModel
import com.overdrive.app.util.DeviceIdGenerator
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
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
    private lateinit var cardDaemons: MaterialCardView
    private lateinit var cardRecording: MaterialCardView

    // Remote Access card UI
    private lateinit var urlStatusDot: View
    private lateinit var tvCurrentUrl: TextView
    private lateinit var btnCopyUrl: ImageButton
    private lateinit var switchAccessMode: SwitchMaterial
    private lateinit var tvAccessMode: TextView
    private var isUpdatingSwitch = false

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
        setupAccessModeToggle()
        observeViewModels()

        tvDeviceId.text = DeviceIdGenerator.generateDeviceId(requireContext())
        loadAuthState()
    }
    
    private fun initViews(view: View) {
        ivQrCode = view.findViewById(R.id.ivQrCode)
        tvQrPlaceholder = view.findViewById(R.id.tvQrPlaceholder)
        tvUrl = view.findViewById(R.id.tvUrl)
        tvDaemonsStatus = view.findViewById(R.id.tvDaemonsStatus)
        tvRecordingStatus = view.findViewById(R.id.tvRecordingStatus)
        tvDeviceId = view.findViewById(R.id.tvDeviceId)
        cardDaemons = view.findViewById(R.id.cardDaemons)
        cardRecording = view.findViewById(R.id.cardRecording)

        // Remote Access card
        urlStatusDot = view.findViewById(R.id.urlStatusDot)
        tvCurrentUrl = view.findViewById(R.id.tvCurrentUrl)
        btnCopyUrl = view.findViewById(R.id.btnCopyUrl)
        switchAccessMode = view.findViewById(R.id.switchAccessMode)
        tvAccessMode = view.findViewById(R.id.tvAccessMode)

        // Auth UI
        tvDeviceToken = view.findViewById(R.id.tvDeviceToken)
        btnToggleToken = view.findViewById(R.id.btnToggleToken)
        btnCopyToken = view.findViewById(R.id.btnCopyToken)
        btnRegenerateToken = view.findViewById(R.id.btnRegenerateToken)
    }
    
    private fun setupClickListeners() {
        val drawerNavOptions = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setPopUpTo(R.id.nav_graph, false)
            .build()

        cardDaemons.setOnClickListener {
            findNavController().navigate(R.id.daemonsFragment, null, drawerNavOptions)
        }

        cardRecording.setOnClickListener {
            findNavController().navigate(R.id.recordingFragment, null, drawerNavOptions)
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

        btnCopyUrl.setOnClickListener {
            val url = mainViewModel.currentUrl.value
            if (!url.isNullOrEmpty()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Tunnel URL", url))
                Toast.makeText(requireContext(), "URL copied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAccessModeToggle() {
        switchAccessMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener
            val mode = if (isChecked) AccessMode.PUBLIC else AccessMode.PRIVATE
            mainViewModel.setAccessMode(mode)
            daemonsViewModel.daemonStartupManager?.onAccessModeChanged(mode)
        }
    }
    
    private fun observeViewModels() {
        mainViewModel.currentUrl.observe(viewLifecycleOwner) { url ->
            updateQrCode(url)
            updateUrlDisplay(url)
        }

        mainViewModel.accessMode.observe(viewLifecycleOwner) { mode ->
            isUpdatingSwitch = true
            switchAccessMode.isChecked = mode == AccessMode.PUBLIC
            isUpdatingSwitch = false
            tvAccessMode.text = mode.name
            if (mainViewModel.currentUrl.value.isNullOrEmpty()) {
                tvQrPlaceholder.text = when (mode) {
                    AccessMode.PRIVATE -> getTunnelPlaceholderText()
                    AccessMode.PUBLIC -> "Loading VPS URL..."
                }
            }
        }

        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            val running = states.values.count { it.status == DaemonStatus.RUNNING }
            val total = states.size
            tvDaemonsStatus.text = "$running/$total Running"
            if (mainViewModel.currentUrl.value.isNullOrEmpty()) {
                tvQrPlaceholder.text = getTunnelPlaceholderText()
            }
        }
        
        // Observe recording state
        recordingViewModel.isRecording.observe(viewLifecycleOwner) { isRecording ->
            tvRecordingStatus.text = if (isRecording) "🔴 Recording" else "Idle"
            tvRecordingStatus.setTextColor(
                resources.getColor(
                    if (isRecording) R.color.status_error else R.color.status_stopped,
                    null
                )
            )
        }
    }
    
    private fun updateQrCode(url: String?) {
        if (url.isNullOrEmpty()) {
            showPlaceholder()
        } else {
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
    }
    
    private fun showPlaceholder() {
        ivQrCode.setImageDrawable(null)
        ivQrCode.visibility = View.VISIBLE
        tvQrPlaceholder.visibility = View.VISIBLE
        tvQrPlaceholder.text = getTunnelPlaceholderText()
        tvUrl.visibility = View.GONE
    }
    
    private fun updateUrlDisplay(url: String?) {
        if (url.isNullOrEmpty()) {
            tvCurrentUrl.text = getTunnelPlaceholderText()
            urlStatusDot.setBackgroundResource(R.drawable.status_dot_offline)
        } else {
            tvCurrentUrl.text = url
            urlStatusDot.setBackgroundResource(R.drawable.status_dot_online)
        }
    }

    /**
     * Get appropriate placeholder text based on tunnel daemon state.
     */
    private fun getTunnelPlaceholderText(): String {
        val states = daemonsViewModel.daemonStates.value ?: return "No tunnel running"
        val cfState = states[DaemonType.CLOUDFLARED_TUNNEL]
        val zrokState = states[DaemonType.ZROK_TUNNEL]
        
        return when {
            zrokState?.status == DaemonStatus.STARTING -> "Starting Zrok tunnel..."
            cfState?.status == DaemonStatus.STARTING -> "Starting Cloudflared tunnel..."
            zrokState?.status == DaemonStatus.RUNNING -> "Waiting for tunnel URL..."
            cfState?.status == DaemonStatus.RUNNING -> "Waiting for tunnel URL..."
            else -> "No tunnel running"
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
        val clip = ClipData.newPlainText("Access Code", state.secret)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(requireContext(), "Access code copied", Toast.LENGTH_SHORT).show()
    }
    
    private fun showRegenerateConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Regenerate Token")
            .setMessage("This will invalidate the current token. All active sessions will be logged out. Continue?")
            .setPositiveButton("Regenerate") { _, _ ->
                regenerateToken()
            }
            .setNegativeButton("Cancel", null)
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
                            Toast.makeText(requireContext(), "New token generated. All sessions logged out.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), "Token regenerated. Daemon may need restart to apply.", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Token regenerated. Could not notify daemon.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Token regenerated", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
        
        // Update UI
        loadAuthState()
    }
}
