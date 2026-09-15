package com.overdrive.app.ui.fragment

import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.ui.adapter.DaemonAdapter
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.model.localizedName
import com.overdrive.app.R
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.util.QrCodeGenerator
import java.util.concurrent.Executors

/**
 * Fragment for managing background daemons.
 */
class DaemonsFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private val wifiSettingsWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WifiAutoEnableSettings").apply { isDaemon = true }
    }

    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    private lateinit var recyclerDaemons: RecyclerView
    private lateinit var tvDaemonsCount: TextView
    private lateinit var swWifiAutoEnable: SwitchMaterial
    private lateinit var daemonAdapter: DaemonAdapter
    private var applyingWifiAutoEnable = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_daemons, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupRecyclerView()
        observeViewModel()
        
        // Check Zrok token status on view creation
        checkZrokTokenStatus()
    }
    
    private fun initViews(view: View) {
        recyclerDaemons = view.findViewById(R.id.recyclerDaemons)
        tvDaemonsCount = view.findViewById(R.id.tvDaemonsCount)
        swWifiAutoEnable = view.findViewById(R.id.swWifiAutoEnable)
        swWifiAutoEnable.isEnabled = false

        view.findViewById<View>(R.id.rowWifiAutoEnable).setOnClickListener {
            if (swWifiAutoEnable.isEnabled) {
                swWifiAutoEnable.isChecked = !swWifiAutoEnable.isChecked
            }
        }
        swWifiAutoEnable.setOnCheckedChangeListener { _, enabled ->
            if (applyingWifiAutoEnable || !swWifiAutoEnable.isEnabled) {
                return@setOnCheckedChangeListener
            }
            persistWifiAutoEnable(enabled)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::swWifiAutoEnable.isInitialized) refreshWifiAutoEnable()
    }

    override fun onDestroy() {
        wifiSettingsWorker.shutdown()
        super.onDestroy()
    }

    private fun refreshWifiAutoEnable() {
        swWifiAutoEnable.isEnabled = false
        wifiSettingsWorker.execute {
            val enabled = runCatching {
                UnifiedConfigManager.forceReload()
                UnifiedConfigManager.isWifiAutoEnableEnabled()
            }.getOrDefault(true)
            handler.post {
                if (view == null || !::swWifiAutoEnable.isInitialized) return@post
                applyingWifiAutoEnable = true
                swWifiAutoEnable.isChecked = enabled
                swWifiAutoEnable.isEnabled = true
                applyingWifiAutoEnable = false
            }
        }
    }

    private fun persistWifiAutoEnable(enabled: Boolean) {
        swWifiAutoEnable.isEnabled = false
        wifiSettingsWorker.execute {
            val saved = runCatching {
                UnifiedConfigManager.setWifiAutoEnableEnabled(enabled)
            }.getOrDefault(false)
            handler.post {
                if (view == null || !::swWifiAutoEnable.isInitialized) return@post
                applyingWifiAutoEnable = true
                if (!saved) swWifiAutoEnable.isChecked = !enabled
                swWifiAutoEnable.isEnabled = true
                applyingWifiAutoEnable = false
                if (!saved) {
                    context?.let {
                        Toast.makeText(
                            it,
                            R.string.daemons_wifi_auto_enable_save_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
    
    private fun setupRecyclerView() {
        daemonAdapter = DaemonAdapter(
            onToggle = { type, enabled -> onDaemonToggled(type, enabled) },
            onConfigureClick = { type -> onDaemonConfigureClicked(type) },
            // Per-daemon log action is available in debug AND in the braveheart
            // build (LOG_CAPTURE) — braveheart keeps DaemonLogger file output so
            // customers can send logs. Plain release/alpha builds strip the
            // calls, so the action stays hidden there.
            onDownloadLog = if (com.overdrive.app.BuildConfig.DEBUG
                    || com.overdrive.app.BuildConfig.LOG_CAPTURE) {
                { type -> onDownloadLogClicked(type) }
            } else null
        )
        
        recyclerDaemons.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = daemonAdapter
        }
    }
    
    private fun observeViewModel() {
        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            // Convert map to list sorted by daemon type ordinal
            val sortedList = states.values.sortedBy { it.type.ordinal }
            daemonAdapter.submitList(sortedList)

            // Update hero header live count: "X of Y running"
            val total = sortedList.size
            val running = sortedList.count { it.status == DaemonStatus.RUNNING }
            tvDaemonsCount.text = getString(R.string.daemons_count_fmt, running, total)
        }
    }
    
    /**
     * Check if Zrok token is configured and update state accordingly.
     */
    private fun checkZrokTokenStatus() {
        daemonsViewModel.zrokController.hasEnableToken { hasToken ->
            activity?.runOnUiThread {
                if (!hasToken) {
                    // Update Zrok state to show configuration needed
                    daemonsViewModel.updateZrokNeedsConfig(getString(R.string.daemon_config_no_token))
                }
            }
        }
    }
    
    private fun onDaemonToggled(type: DaemonType, enabled: Boolean) {
        // Save preference for optional daemons (so they auto-start on next app launch if enabled)
        daemonsViewModel.daemonStartupManager?.onDaemonToggled(type, enabled)
        
        if (enabled) {
            daemonsViewModel.startDaemon(type)
        } else {
            daemonsViewModel.stopDaemon(type)
        }
    }
    
    private fun onDaemonConfigureClicked(type: DaemonType) {
        when (type) {
            DaemonType.ZROK_TUNNEL -> showZrokTokenDialog()
            DaemonType.TAILSCALE_TUNNEL -> showTailscaleSettingsDialog()
            DaemonType.CLOUDFLARED_TUNNEL -> {
                com.overdrive.app.config.CloudflaredPaidConfig.showSettingsDialog(requireContext(), daemonsViewModel)
            }
            else -> {
                // Other daemons don't need configuration yet
                val ctx = context ?: return
                Toast.makeText(ctx, getString(R.string.toast_no_config_needed, type.localizedName(ctx)), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Show dialog to configure Zrok enable token.
     */
    private fun showZrokTokenDialog() {
        val context = context ?: return
        
        // First get current token to show in dialog
        daemonsViewModel.zrokController.getEnableToken { currentToken ->
            activity?.runOnUiThread {
                val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_zrok_token, null)
                val editToken = dialogView.findViewById<EditText>(R.id.editZrokToken)
                
                // Pre-fill with current token if exists
                currentToken?.let { editToken.setText(it) }
                
                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
                    .setIcon(R.drawable.ic_link)
                    .setTitle(getString(R.string.dialog_zrok_token_title))
                    .setView(dialogView)
                    .setPositiveButton(getString(R.string.dialog_save)) { _, _ ->
                        val token = editToken.text.toString().trim()
                        if (token.isNotEmpty()) {
                            saveZrokToken(token)
                        } else {
                            Toast.makeText(context, getString(R.string.toast_token_cannot_be_empty), Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .setNeutralButton(getString(R.string.dialog_delete)) { _, _ ->
                        deleteZrokToken()
                    }
                    .create()
                
                // Wire up the Reset Environment button
                dialogView.findViewById<View>(R.id.btnResetZrokEnvironment)?.setOnClickListener {
                    dialog.dismiss()
                    confirmResetZrokEnvironment()
                }
                
                dialog.show()
            }
        }
    }

    /**
     * Show dialog to configure and login to Tailscale.
     */
    private fun showTailscaleSettingsDialog() {
        val context = context ?: return
        var loginGenerated = false

        activity?.runOnUiThread {
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_tailscale_settings, null)
            val loginGenerateButton = dialogView.findViewById<TextView>(R.id.generateLoginUrlBtn)
            val qrCodeContainer = dialogView.findViewById<LinearLayout>(R.id.qrCodeContainer)
            val qrCodeText = dialogView.findViewById<TextView>(R.id.qrCodeURL)
            val qrCodeImage = dialogView.findViewById<ImageView>(R.id.qrCodeImage)
            val proxySwitch = dialogView.findViewById<SwitchMaterial>(R.id.switchTailscaleProxy)
            val adbSwitch = dialogView.findViewById<SwitchMaterial>(R.id.switchTailscaleAdb)
            val httpsSwitch = dialogView.findViewById<SwitchMaterial>(R.id.switchTailscaleHttps)
            val adbEndpoint = dialogView.findViewById<TextView>(R.id.tailscaleAdbEndpoint)

            daemonsViewModel.tailscaleController.isProxyEnabled { isEnabled ->
                activity?.runOnUiThread {
                    proxySwitch.isChecked = isEnabled
                }
            }

            daemonsViewModel.tailscaleController.isAdbEnabled { isEnabled ->
                activity?.runOnUiThread {
                    adbSwitch.isChecked = isEnabled
                }
            }

            daemonsViewModel.tailscaleController.isHttpsEnabled { isEnabled ->
                activity?.runOnUiThread {
                    httpsSwitch.isChecked = isEnabled
                }
            }

            // Endpoint resolves only when ADB is on, the daemon is up and login is
            // done — stays hidden otherwise rather than showing a dead address.
            daemonsViewModel.tailscaleController.getAdbEndpoint { endpoint ->
                activity?.runOnUiThread {
                    // The probe is a multi-hop shell chain, so the fragment can
                    // detach before it lands — re-check attachment, because
                    // getString() routes through requireContext() and would throw.
                    val ctx = context ?: return@runOnUiThread
                    if (endpoint.isNullOrEmpty()) {
                        adbEndpoint.visibility = View.GONE
                    } else {
                        val command = ctx.getString(R.string.tailscale_adb_endpoint, endpoint)
                        adbEndpoint.text = command
                        adbEndpoint.visibility = View.VISIBLE
                        adbEndpoint.setOnClickListener {
                            val tapCtx = context ?: return@setOnClickListener
                            val clip = tapCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            // Copy what's displayed — the whole command, not just the address.
                            clip?.setPrimaryClip(android.content.ClipData.newPlainText("adb", command))
                            Toast.makeText(tapCtx, tapCtx.getString(R.string.tailscale_adb_endpoint_copied), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            loginGenerateButton.setOnClickListener {
                if (!loginGenerated) {
                    loginGenerated = true
                    qrCodeContainer.visibility = View.VISIBLE
                    daemonsViewModel.tailscaleController.generateLoginUrl { url ->
                        activity?.runOnUiThread {
                            if (url != null) {
                                val qrBitmap = QrCodeGenerator.generate(url, 400)
                                qrCodeImage.setImageBitmap(qrBitmap)
                                qrCodeText.text = url
                                qrCodeText.setTextColor(ContextCompat.getColor(context, R.color.brand_primary))
                            } else {
                                qrCodeText.text = getString(R.string.tailscale_failed_login_url)
                                qrCodeText.setTextColor(ContextCompat.getColor(context, R.color.status_danger))
                                loginGenerated = false
                            }
                        }
                    }
                }
            }

            daemonsViewModel.tailscaleController.tunnelUrl.observe(viewLifecycleOwner) { url ->
                if (loginGenerated && !url.isNullOrEmpty()) {
                    activity?.runOnUiThread {
                        qrCodeContainer.visibility = View.GONE
                        loginGenerated = false
                        loginGenerateButton.text = getString(R.string.tailscale_logged_in_relogin)
                    }
                }
            }

            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
                .setIcon(R.drawable.ic_mqtt)
                .setTitle(getString(R.string.dialog_tailscale_settings_title))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.dialog_save)) { _, _ ->
                    val enableProxy = proxySwitch.isChecked
                    val enableAdb = adbSwitch.isChecked
                    val enableHttps = httpsSwitch.isChecked

                    // Settled on its own, outside the ADB/proxy chain: it only
                    // ever raises a toast, so it cannot stack a dialog on top of
                    // theirs. Publishing the web UI over TLS exposes nothing the
                    // tailnet address does not already expose over plain HTTP,
                    // so unlike remote ADB it needs no confirmation.
                    daemonsViewModel.tailscaleController.isHttpsEnabled { httpsWasEnabled ->
                        activity?.runOnUiThread {
                            if (enableHttps != httpsWasEnabled) saveTailscaleHttpsSettings(enableHttps)
                        }
                    }
                    // Settle the ADB toggle first, then the proxy. Both are
                    // independent and each only acts on a real change; the proxy
                    // step is deferred until any ADB confirm is dismissed so the
                    // two warning dialogs can't stack on top of each other.
                    val thenProxy = {
                        daemonsViewModel.tailscaleController.isProxyEnabled { wasEnabled ->
                            activity?.runOnUiThread {
                                // Only confirm when *turning on* the proxy (going off→on). Disabling is always safe.
                                if (enableProxy && !wasEnabled) {
                                    confirmEnableTailscaleProxy()
                                } else if (enableProxy != wasEnabled) {
                                    saveTailscaleProxySettings(enableProxy)
                                }
                            }
                        }
                    }
                    daemonsViewModel.tailscaleController.isAdbEnabled { adbWasEnabled ->
                        activity?.runOnUiThread {
                            if (enableAdb != adbWasEnabled) {
                                // Confirm only off→on; withdrawing access is always safe.
                                if (enableAdb) confirmEnableTailscaleAdb(thenProxy)
                                else {
                                    saveTailscaleAdbSettings(false)
                                    thenProxy()
                                }
                            } else {
                                thenProxy()
                            }
                        }
                    }
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setNeutralButton(getString(R.string.dialog_delete)) { _, _ ->
                    confirmResetTailscaleEnvironment()
                }
                .create()

            dialog.show()
        }
    }

    /**
     * Confirm before exposing ADB to the tailnet — this grants full shell access
     * to any tailnet peer, so it must never be a silent one-tap change.
     */
    private fun confirmEnableTailscaleAdb(onDismissed: (() -> Unit)? = null) {
        // Detached before the dialog could be built — still run the follow-up so a
        // pending proxy change isn't silently dropped.
        val context = context ?: run {
            onDismissed?.invoke()
            return
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_warning)
            .setTitle(getString(R.string.dialog_tailscale_adb_enable_title))
            .setMessage(getString(R.string.dialog_tailscale_adb_enable_message))
            .setPositiveButton(getString(R.string.dialog_enable)) { _, _ ->
                saveTailscaleAdbSettings(true)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            // Runs on accept AND cancel, so a queued follow-up step never strands.
            .setOnDismissListener { onDismissed?.invoke() }
            .show()
    }

    private fun saveTailscaleAdbSettings(enabled: Boolean) {
        daemonsViewModel.tailscaleController.saveAdbSettings(enabled) { saved ->
            activity?.runOnUiThread {
                val ctx = context ?: return@runOnUiThread
                if (!saved) {
                    Toast.makeText(ctx, getString(R.string.toast_tailscale_adb_save_failed), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val msg = if (enabled) R.string.toast_tailscale_adb_enabled
                          else R.string.toast_tailscale_adb_disabled
                Toast.makeText(ctx, getString(msg), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveTailscaleHttpsSettings(enabled: Boolean) {
        daemonsViewModel.tailscaleController.saveHttpsSettings(enabled) { saved ->
            activity?.runOnUiThread {
                val ctx = context ?: return@runOnUiThread
                if (!saved) {
                    // Almost always the tailnet lacking HTTPS Certificates, which
                    // only the admin console can fix — so the toast says so rather
                    // than reporting a bare failure.
                    Toast.makeText(ctx, getString(R.string.toast_tailscale_https_save_failed), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val msg = if (enabled) R.string.toast_tailscale_https_enabled
                          else R.string.toast_tailscale_https_disabled
                Toast.makeText(ctx, getString(msg), Toast.LENGTH_SHORT).show()

                // Push the URL through now that the share is live/withdrawn.
                // getTunnelUrl prefers the served https:// URL and falls back to the
                // plain tailnet address, so this flips the connect URL and the
                // dashboard remote-access QR (which observes tunnelUrl) to match the
                // new state at once. Without it the QR keeps showing the old scheme
                // until the next ~30s daemon-status poll — and on enable that stale
                // URL is plain HTTP, which lacks the secure context (camera, QR
                // pairing, service worker) that HTTPS was turned on to provide.
                daemonsViewModel.tailscaleController.refreshTunnelUrl()
            }
        }
    }

    /**
     * Confirm before enabling the tailscale proxy — has implications for MQTT to public brokers.
     */
    private fun confirmEnableTailscaleProxy() {
        val context = context ?: return

        com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_warning)
            .setTitle(getString(R.string.dialog_tailscale_proxy_enable_title))
            .setMessage(getString(R.string.dialog_tailscale_proxy_enable_message))
            .setPositiveButton(getString(R.string.dialog_enable)) { _, _ ->
                saveTailscaleProxySettings(true)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }
    
    /**
     * Show confirmation dialog before resetting zrok environment.
     */
    private fun confirmResetZrokEnvironment() {
        val context = context ?: return
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_warning)
            .setTitle(getString(R.string.dialog_zrok_reset_title))
            .setMessage(getString(R.string.dialog_zrok_reset_message))
            .setPositiveButton(getString(R.string.dialog_reset)) { _, _ ->
                resetZrokEnvironment()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }
    
    /**
     * Reset zrok environment: stop tunnel, disable environment, delete token.
     */
    private fun resetZrokEnvironment() {
        val context = context ?: return
        Toast.makeText(context, getString(R.string.toast_resetting_zrok), Toast.LENGTH_SHORT).show()

        // First stop the tunnel if running
        daemonsViewModel.stopDaemon(DaemonType.ZROK_TUNNEL)

        // Then disable the environment (removes environment.json and reserved tokens)
        daemonsViewModel.zrokController.disableEnvironment(object : com.overdrive.app.ui.daemon.DaemonCallback {
            override fun onStatusChanged(status: com.overdrive.app.ui.model.DaemonStatus, message: String) {
                // Environment disabled, now delete the enable token
                daemonsViewModel.zrokController.deleteEnableToken { success ->
                    activity?.runOnUiThread {
                        if (success) {
                            Toast.makeText(context, getString(R.string.toast_zrok_reset_success), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, getString(R.string.toast_zrok_reset_partial), Toast.LENGTH_LONG).show()
                        }
                        daemonsViewModel.updateZrokNeedsConfig(getString(R.string.zrok_no_token_configured))
                    }
                }
            }

            override fun onError(error: String) {
                // Even if disable fails, still try to delete the token
                daemonsViewModel.zrokController.deleteEnableToken { _ ->
                    activity?.runOnUiThread {
                        Toast.makeText(context, getString(R.string.toast_zrok_reset_warnings, error), Toast.LENGTH_LONG).show()
                        daemonsViewModel.updateZrokNeedsConfig(getString(R.string.zrok_no_token_configured))
                    }
                }
            }
        })
    }

    /**
     * Show confirmation dialog before resetting tailscale environment.
     */
    private fun confirmResetTailscaleEnvironment() {
        val context = context ?: return

        com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_warning)
            .setTitle(getString(R.string.dialog_tailscale_reset_title))
            .setMessage(getString(R.string.dialog_tailscale_reset_message))
            .setPositiveButton(getString(R.string.dialog_reset)) { _, _ ->
                resetTailscaleEnvironment()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    /**
     * Reset tailscale environment: stop tunnel, disable environment.
     */
    private fun resetTailscaleEnvironment() {
        val context = context ?: return
        Toast.makeText(context, getString(R.string.toast_resetting_tailscale), Toast.LENGTH_SHORT).show()

        // First stop the tunnel if running
        daemonsViewModel.stopDaemon(DaemonType.TAILSCALE_TUNNEL)

        // Then disable the environment (removes environment.json and reserved tokens)
        daemonsViewModel.tailscaleController.disableEnvironment(object : com.overdrive.app.ui.daemon.DaemonCallback {
            override fun onStatusChanged(status: com.overdrive.app.ui.model.DaemonStatus, message: String) {
                Toast.makeText(context, getString(R.string.toast_tailscale_reset_success), Toast.LENGTH_LONG).show()
            }

            override fun onError(error: String) {
                Toast.makeText(context, getString(R.string.toast_tailscale_reset_warnings, error), Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun saveTailscaleProxySettings(enabled: Boolean) {
        daemonsViewModel.tailscaleController.saveProxySettings(enabled) { saved ->
            activity?.runOnUiThread {
                if (saved != null) {
                    if (saved) {
                        // Force MQTT proxy probe to re-run on next reconnect
                        com.overdrive.app.mqtt.ProxyHelper.invalidateCache()

                        val status = daemonsViewModel.daemonStates.value?.get(DaemonType.TAILSCALE_TUNNEL)?.status
                        if (status != DaemonStatus.STOPPED) {
                            daemonsViewModel.stopDaemon(DaemonType.TAILSCALE_TUNNEL)
                            handler.postDelayed(
                                { daemonsViewModel.startDaemon(DaemonType.TAILSCALE_TUNNEL) },
                                2000
                            )
                        }
                        if (enabled) {
                            Toast.makeText(context, getString(R.string.toast_tailscale_proxy_enabled), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, getString(R.string.toast_tailscale_proxy_disabled), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, getString(R.string.toast_tailscale_proxy_save_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    private fun saveZrokToken(token: String) {
        daemonsViewModel.zrokController.saveEnableToken(token) { success ->
            activity?.runOnUiThread {
                if (success) {
                    Toast.makeText(context, getString(R.string.toast_zrok_token_saved), Toast.LENGTH_SHORT).show()
                    // Refresh Zrok status
                    daemonsViewModel.refreshDaemonStatus(DaemonType.ZROK_TUNNEL)
                } else {
                    Toast.makeText(context, getString(R.string.toast_zrok_token_save_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteZrokToken() {
        daemonsViewModel.zrokController.deleteEnableToken { success ->
            activity?.runOnUiThread {
                if (success) {
                    Toast.makeText(context, getString(R.string.toast_zrok_token_deleted), Toast.LENGTH_SHORT).show()
                    // Update state to show configuration needed
                    daemonsViewModel.updateZrokNeedsConfig(getString(R.string.zrok_no_token_configured))
                } else {
                    Toast.makeText(context, getString(R.string.toast_zrok_token_delete_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // ==================== Log Download (Debug Only) ====================
    
    /**
     * Download a daemon's log file from /data/local/tmp/ and share it.
     * Uses tail to limit output size and avoid OOM on large log files.
     */
    private fun onDownloadLogClicked(type: DaemonType) {
        val logPath = DaemonAdapter.getLogFilePath(type) ?: return
        val ctx = context ?: return
        val daemonName = type.displayName.replace(" ", "_").lowercase()
        val localizedName = type.localizedName(ctx)

        // In braveheart (LOG_CAPTURE) with a Worker URL configured, offer two
        // ways to send: upload-and-get-a-code (zero-friction for support) or
        // the existing Android share-sheet. Debug builds keep share-only.
        if (com.overdrive.app.logging.LogUploader.isUploadConfigured()) {
            val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_send_log, null)
            dialogView.findViewById<TextView>(R.id.sendLogSubtitle)?.text =
                getString(R.string.logs_send_subtitle, localizedName)

            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
                ctx, R.style.Theme_Overdrive_M3_Dialog)
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            dialogView.findViewById<View>(R.id.optionUpload)?.setOnClickListener {
                dialog.dismiss()
                uploadDaemonLog(type, localizedName)
            }
            dialogView.findViewById<View>(R.id.optionShare)?.setOnClickListener {
                dialog.dismiss()
                shareDaemonLog(type)
            }
            dialog.show()
            return
        }
        shareDaemonLog(type)
    }

    /**
     * Upload a daemon log via the daemon-side IPC (UPLOAD_LOG → LogUploader →
     * Cloudflare Worker) so the proxy-aware, redaction path runs in the daemon
     * process that owns /data/local/tmp. Shows the returned short code.
     */
    private fun uploadDaemonLog(type: DaemonType, localizedName: String) {
        val ctx = context ?: return
        val daemonKey = DaemonAdapter.daemonLogKey(type) ?: run {
            Toast.makeText(ctx, getString(R.string.toast_log_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        // Indeterminate progress for the duration of the IPC (proxy + retry, up
        // to ~35s). LogUploader reports no byte progress, so this is a spinner,
        // not a percentage. Non-cancelable: the daemon-side upload runs to
        // completion regardless, and a half-dismissed dialog would just drop the
        // returned code on the floor.
        val progressView = LayoutInflater.from(ctx).inflate(R.layout.dialog_log_uploading, null)
        val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            ctx, R.style.Theme_Overdrive_M3_Dialog)
            .setView(progressView)
            .setCancelable(false)
            .show()

        Thread {
            val req = org.json.JSONObject().apply {
                put("command", "UPLOAD_LOG")
                put("daemon", daemonKey)
            }
            // 35s > LogUploader worst case (proxy 12s + direct-retry 12s = 24s)
            // so the IPC read never races a still-running upload.
            val resp = com.overdrive.app.server.DaemonIpcClient.send(req, 35_000)
            activity?.runOnUiThread {
                progressDialog.dismiss()
                if (!isAdded) return@runOnUiThread
                val ctx2 = context ?: return@runOnUiThread
                if (resp == null || !resp.optBoolean("success", false)) {
                    val err = resp?.optString("error") ?: getString(R.string.errors_network)
                    Toast.makeText(ctx2, getString(R.string.toast_log_save_failed, err), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val code = resp.optString("code", "")
                showLogUploadedDialog(ctx2, code)
            }
        }.start()
    }

    /**
     * Result dialog for a successful log upload. The retrieval code is the
     * payload, so it gets a prominent monospace card (tap the card OR the
     * "Copy code" button to copy) instead of being buried in a prose message.
     */
    private fun showLogUploadedDialog(ctx: android.content.Context, code: String) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_log_uploaded, null)
        view.findViewById<TextView>(R.id.uploadedCode)?.text = code

        fun copyCode() {
            val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            clip?.setPrimaryClip(android.content.ClipData.newPlainText("log code", code))
            Toast.makeText(ctx, R.string.toast_url_copied_short, Toast.LENGTH_SHORT).show()
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            ctx, R.style.Theme_Overdrive_M3_Dialog)
            .setView(view)
            .setPositiveButton(R.string.logs_copy_code) { _, _ -> copyCode() }
            .setNegativeButton(R.string.logs_done, null)
            .show()

        // Tapping the code card copies too (and keeps the dialog open so the
        // user can still read the instructions / re-copy).
        view.findViewById<View>(R.id.codeCard)?.setOnClickListener { copyCode() }
    }

    private fun shareDaemonLog(type: DaemonType) {
        val logPath = DaemonAdapter.getLogFilePath(type) ?: return
        val ctx = context ?: return
        val daemonName = type.displayName.replace(" ", "_").lowercase()
        val localizedName = type.localizedName(ctx)

        // Reuse the shared adbLauncher; allocating a fresh AdbDaemonLauncher
        // here would leak its non-daemon executor + tunnel-poll scheduler
        // thread every time the user views a log. If the manager isn't
        // attached yet (Activity recreate window: Fragment.onViewCreated
        // can fire before MainActivity.onCreate's setStartupManager call),
        // bail out with a clear toast rather than allocate a leaked fallback.
        val adb = daemonsViewModel.daemonStartupManager?.adbLauncher
        if (adb == null) {
            Toast.makeText(ctx, getString(R.string.toast_daemon_manager_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(ctx, getString(R.string.toast_fetching_log, localizedName), Toast.LENGTH_SHORT).show()

        // Use tail to limit output — 10000 lines is ~1-2MB which is safe for ADB + String.
        adb.executeShellCommand(
            "wc -l < $logPath 2>/dev/null; echo '---SEPARATOR---'; tail -10000 $logPath 2>/dev/null",
            object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    activity?.runOnUiThread {
                        if (message.isBlank()) {
                            Toast.makeText(ctx, getString(R.string.toast_log_empty_or_missing), Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }

                        try {
                            // Parse: first part is line count, after separator is the log content
                            val parts = message.split("---SEPARATOR---", limit = 2)
                            val totalLines = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                            val logContent = parts.getOrNull(1)?.trimStart('\n') ?: message

                            if (logContent.isBlank()) {
                                Toast.makeText(ctx, getString(R.string.toast_log_empty), Toast.LENGTH_SHORT).show()
                                return@runOnUiThread
                            }

                            // Write to a shareable file in cache dir
                            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                            val fileName = "${daemonName}_${timestamp}.log"
                            val cacheDir = java.io.File(ctx.cacheDir, "logs")
                            cacheDir.mkdirs()
                            val logFile = java.io.File(cacheDir, fileName)

                            // Add header with metadata
                            val header = buildString {
                                appendLine(getString(R.string.log_header_title, localizedName))
                                appendLine(getString(R.string.log_header_source, logPath))
                                appendLine(getString(R.string.log_header_exported, java.util.Date().toString()))
                                if (totalLines > 10000) {
                                    appendLine(getString(R.string.log_header_truncated, totalLines))
                                }
                                appendLine("===")
                                appendLine()
                            }
                            logFile.writeText(header + logContent)

                            // Share via intent
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                ctx,
                                "${ctx.packageName}.fileprovider",
                                logFile
                            )

                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                this.type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.log_share_title, localizedName, timestamp))
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                // Explicitly set ClipData to ensure the Chooser and target app can access the URI
                                clipData = android.content.ClipData.newRawUri(null, uri)
                            }
                            startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.log_share_chooser, localizedName)))
                        } catch (e: Exception) {
                            Toast.makeText(ctx, getString(R.string.toast_log_save_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                }

                override fun onLaunched() {}

                override fun onError(error: String) {
                    activity?.runOnUiThread {
                        Toast.makeText(ctx, getString(R.string.toast_log_not_found), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}
