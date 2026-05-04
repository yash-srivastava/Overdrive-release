package com.overdrive.app.ui.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.overdrive.app.ui.adapter.DaemonAdapter
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.R

/**
 * Fragment for managing background daemons.
 */
class DaemonsFragment : Fragment() {
    
    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    
    private lateinit var recyclerDaemons: RecyclerView
    private lateinit var daemonAdapter: DaemonAdapter
    
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
    }
    
    private fun setupRecyclerView() {
        daemonAdapter = DaemonAdapter(
            onToggle = { type, enabled -> onDaemonToggled(type, enabled) },
            onConfigureClick = { type -> onDaemonConfigureClicked(type) },
            onDownloadLog = if (com.overdrive.app.BuildConfig.DEBUG) {
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
                    daemonsViewModel.updateZrokNeedsConfig("No token configured. Tap to set up.")
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
            else -> {
                // Other daemons don't need configuration yet
                Toast.makeText(context, "No configuration needed for ${type.displayName}", Toast.LENGTH_SHORT).show()
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
            daemonsViewModel.zrokController.getZrokEndpoint { currentEndpoint ->
                activity?.runOnUiThread {
                    val dialogView =
                        LayoutInflater.from(context).inflate(R.layout.dialog_zrok_token, null)
                    val editToken = dialogView.findViewById<EditText>(R.id.editZrokToken)

                    // Pre-fill with current token if exists
                    currentToken?.let { editToken.setText(it) }

                    val endpointSwitch = dialogView.findViewById<SwitchMaterial>(R.id.switchEndpoint)
                    val editEndpoint = dialogView.findViewById<EditText>(R.id.editZrokEndpoint)
                    val editEndpointParent = dialogView.findViewById<TextInputLayout>(R.id.editZrokEndpointParent)
                    val editEndpointHint = dialogView.findViewById<TextView>(R.id.editZrokEndpointHint)
                    currentEndpoint?.let {
                        editEndpoint.setText(it)

                        // Show the hidden endpoint box if endpoint exists
                        endpointSwitch.isChecked = true
                        editEndpointParent.visibility = View.VISIBLE
                        editEndpointHint.visibility = View.VISIBLE
                    }

                    endpointSwitch.setOnCheckedChangeListener { _, isChecked ->
                        val visibility = if (isChecked) View.VISIBLE else View.GONE
                        editEndpointParent.visibility = visibility
                        editEndpointHint.visibility = visibility
                    }

                    val dialog = AlertDialog.Builder(context)
                        .setTitle("🌐 Zrok Tunnel Token")
                        .setMessage("Enter your Zrok enable token.\nGet one at: zrok.io")
                        .setView(dialogView)
                        .setPositiveButton("Save") { _, _ ->
                            val token = editToken.text.toString().trim()
                            val endpoint = editEndpoint.text.toString().trim()
                            if (endpointSwitch.isChecked && endpoint.isEmpty()) {
                                Toast.makeText(context, "Endpoint missing. Either disable self hosted option or provide endpoint.", Toast.LENGTH_SHORT)
                                    .show()
                            } else if (token.isEmpty()) {
                                Toast.makeText(context, "Token cannot be empty", Toast.LENGTH_SHORT).show()
                            } else {
                                saveZrokSettings(token, if (endpointSwitch.isChecked) endpoint else null)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .setNeutralButton("Delete") { _, _ ->
                            deleteZrokSettings()
                        }
                        .create()

                    // Wire up the Reset Environment button
                    dialogView.findViewById<View>(R.id.btnResetZrokEnvironment)
                        ?.setOnClickListener {
                            dialog.dismiss()
                            confirmResetZrokEnvironment()
                        }

                    dialog.show()
                }
            }
        }
    }
    
    /**
     * Show confirmation dialog before resetting zrok environment.
     */
    private fun confirmResetZrokEnvironment() {
        val context = context ?: return
        
        AlertDialog.Builder(context)
            .setTitle("⚠️ Reset Zrok Environment")
            .setMessage(
                "This will:\n" +
                "• Stop the zrok tunnel if running\n" +
                "• Remove the zrok environment from this device\n" +
                "• Delete the saved token\n\n" +
                "You will need to re-enter your token and re-enable. This uses one of your 5 device slots on zrok.io.\n\n" +
                "Are you sure?"
            )
            .setPositiveButton("Reset") { _, _ ->
                resetZrokEnvironment()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Reset zrok environment: stop tunnel, disable environment, delete token.
     */
    private fun resetZrokEnvironment() {
        val context = context ?: return
        Toast.makeText(context, "Resetting zrok environment...", Toast.LENGTH_SHORT).show()
        
        // First stop the tunnel if running
        daemonsViewModel.stopDaemon(DaemonType.ZROK_TUNNEL)
        
        // Then disable the environment (removes environment.json and reserved tokens)
        daemonsViewModel.zrokController.disableEnvironment(object : com.overdrive.app.ui.daemon.DaemonCallback {
            override fun onStatusChanged(status: com.overdrive.app.ui.model.DaemonStatus, message: String) {
                // Environment disabled, now delete the enable token
                daemonsViewModel.zrokController.deleteZrokSettings { success ->
                    activity?.runOnUiThread {
                        if (success) {
                            Toast.makeText(context, "✅ Zrok environment reset. Enter a new token to set up again.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "✅ Environment reset (token file may need manual cleanup)", Toast.LENGTH_LONG).show()
                        }
                        daemonsViewModel.updateZrokNeedsConfig("No token configured. Tap to set up.")
                    }
                }
            }
            
            override fun onError(error: String) {
                // Even if disable fails, still try to delete the token
                daemonsViewModel.zrokController.deleteZrokSettings { _ ->
                    activity?.runOnUiThread {
                        Toast.makeText(context, "Environment reset (with warnings: $error)", Toast.LENGTH_LONG).show()
                        daemonsViewModel.updateZrokNeedsConfig("No token configured. Tap to set up.")
                    }
                }
            }
        })
    }
    
    private fun saveZrokSettings(token: String, endpoint: String?) {
        daemonsViewModel.zrokController.saveEnableToken(token) { success ->
            daemonsViewModel.zrokController.saveZrokEndpoint(endpoint) { endpointSuccess ->
                activity?.runOnUiThread {
                    if (success && (endpoint == null || endpointSuccess)) {
                        Toast.makeText(context, "✅ Settings saved", Toast.LENGTH_SHORT).show()
                        // Refresh Zrok status
                        daemonsViewModel.refreshDaemonStatus(DaemonType.ZROK_TUNNEL)
                    } else if (!success) {
                        Toast.makeText(context, "❌ Failed to save token", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "❌ Failed to save Zrok endpoint", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    private fun deleteZrokSettings() {
        daemonsViewModel.zrokController.deleteZrokSettings { success ->
            activity?.runOnUiThread {
                if (success) {
                    Toast.makeText(context, "Token deleted", Toast.LENGTH_SHORT).show()
                    // Update state to show configuration needed
                    daemonsViewModel.updateZrokNeedsConfig("No token configured. Tap to set up.")
                } else {
                    Toast.makeText(context, "❌ Failed to delete token", Toast.LENGTH_SHORT).show()
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
        
        Toast.makeText(ctx, "Fetching ${type.displayName} log...", Toast.LENGTH_SHORT).show()
        
        // Use tail to limit output — 10000 lines is ~1-2MB which is safe for ADB + String
        val adb = com.overdrive.app.launcher.AdbDaemonLauncher(ctx)
        adb.executeShellCommand(
            "wc -l < $logPath 2>/dev/null; echo '---SEPARATOR---'; tail -10000 $logPath 2>/dev/null",
            object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    activity?.runOnUiThread {
                        if (message.isBlank()) {
                            Toast.makeText(ctx, "Log file is empty or not found", Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                        
                        try {
                            // Parse: first part is line count, after separator is the log content
                            val parts = message.split("---SEPARATOR---", limit = 2)
                            val totalLines = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                            val logContent = parts.getOrNull(1)?.trimStart('\n') ?: message
                            
                            if (logContent.isBlank()) {
                                Toast.makeText(ctx, "Log file is empty", Toast.LENGTH_SHORT).show()
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
                                appendLine("=== ${type.displayName} Log ===")
                                appendLine("Source: $logPath")
                                appendLine("Exported: ${java.util.Date()}")
                                if (totalLines > 10000) {
                                    appendLine("NOTE: Log truncated to last 10000 lines (total: $totalLines lines)")
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
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "${type.displayName} Log - $timestamp")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(android.content.Intent.createChooser(shareIntent, "Share ${type.displayName} Log"))
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "❌ Failed to save log: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                
                override fun onLaunched() {}
                
                override fun onError(error: String) {
                    activity?.runOnUiThread {
                        Toast.makeText(ctx, "❌ Log file not found or unreadable", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}
