package com.overdrive.app.ui.fragment

import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.overdrive.app.R
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.telegram.IBotTokenConfig
import com.overdrive.app.telegram.IPairingManager
import com.overdrive.app.telegram.IOwnerStore
import com.overdrive.app.telegram.impl.BotTokenConfig
import com.overdrive.app.telegram.impl.OwnerStore
import com.overdrive.app.telegram.impl.PairingManager
import com.overdrive.app.telegram.model.NotificationPreferences
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.Executors

/**
 * Fragment for Telegram bot configuration and pairing.
 */
class TelegramSettingsFragment : Fragment() {
    
    // Bot config
    private lateinit var etBotToken: TextInputEditText
    private lateinit var btnConnectTest: MaterialButton
    private lateinit var tvBotStatus: TextView
    private lateinit var tvBotInfo: TextView
    
    // Pairing
    private lateinit var layoutNoOwner: LinearLayout
    private lateinit var layoutOwnerPaired: LinearLayout
    private lateinit var btnGeneratePin: MaterialButton
    private lateinit var tvPin: TextView
    private lateinit var tvPinExpiry: TextView
    private lateinit var tvOwnerInfo: TextView
    private lateinit var btnUnpair: MaterialButton
    
    // Notifications
    private lateinit var switchCriticalAlerts: SwitchMaterial
    private lateinit var switchConnectivity: SwitchMaterial
    private lateinit var switchMotionText: SwitchMaterial
    private lateinit var switchVideoUploads: SwitchMaterial
    
    // Daemon
    private lateinit var switchAutoStartAccOff: SwitchMaterial
    private lateinit var tvDaemonStatus: TextView
    
    // Services
    private lateinit var botTokenConfig: IBotTokenConfig
    private lateinit var ownerStore: IOwnerStore
    private lateinit var pairingManager: IPairingManager
    private var adbLauncher: AdbDaemonLauncher? = null
    
    private val executor = Executors.newSingleThreadExecutor()
    private var pinCountdownTimer: CountDownTimer? = null
    
    companion object {
        private const val PREF_AUTO_START_ACC_OFF = "telegram_auto_start_acc_off"
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_telegram_settings, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initServices()
        initViews(view)
        setupListeners()
        loadState()
    }
    
    private fun initServices() {
        val ctx = requireContext()
        botTokenConfig = BotTokenConfig(ctx)
        ownerStore = OwnerStore(ctx)
        pairingManager = PairingManager(ownerStore)
        adbLauncher = AdbDaemonLauncher(ctx)
    }

    
    private fun initViews(view: View) {
        // Bot config
        etBotToken = view.findViewById(R.id.etBotToken)
        btnConnectTest = view.findViewById(R.id.btnConnectTest)
        tvBotStatus = view.findViewById(R.id.tvBotStatus)
        tvBotInfo = view.findViewById(R.id.tvBotInfo)
        
        // Pairing
        layoutNoOwner = view.findViewById(R.id.layoutNoOwner)
        layoutOwnerPaired = view.findViewById(R.id.layoutOwnerPaired)
        btnGeneratePin = view.findViewById(R.id.btnGeneratePin)
        tvPin = view.findViewById(R.id.tvPin)
        tvPinExpiry = view.findViewById(R.id.tvPinExpiry)
        tvOwnerInfo = view.findViewById(R.id.tvOwnerInfo)
        btnUnpair = view.findViewById(R.id.btnUnpair)
        
        // Notifications
        switchCriticalAlerts = view.findViewById(R.id.switchCriticalAlerts)
        switchConnectivity = view.findViewById(R.id.switchConnectivity)
        switchMotionText = view.findViewById(R.id.switchMotionText)
        switchVideoUploads = view.findViewById(R.id.switchVideoUploads)
        
        // Daemon
        switchAutoStartAccOff = view.findViewById(R.id.switchAutoStartAccOff)
        tvDaemonStatus = view.findViewById(R.id.tvDaemonStatus)
    }
    
    private fun setupListeners() {
        btnConnectTest.setOnClickListener { testBotToken() }
        btnGeneratePin.setOnClickListener { generatePin() }
        btnUnpair.setOnClickListener { unpairOwner() }
        
        // Save preferences on change
        val prefChangeListener = { _: View ->
            savePreferences()
        }
        switchCriticalAlerts.setOnClickListener(prefChangeListener)
        switchConnectivity.setOnClickListener(prefChangeListener)
        switchMotionText.setOnClickListener(prefChangeListener)
        
        // Video uploads - also write to daemon config
        switchVideoUploads.setOnClickListener {
            savePreferences()
            // Write to daemon config so TelegramBotDaemon can check it
            writeDaemonConfigProperty("video_uploads", if (switchVideoUploads.isChecked) "true" else "false", showToast = false)
        }
        
        // Daemon toggle - auto-start on vehicle off
        // Use setOnClickListener (not setOnCheckedChangeListener) to avoid triggering on programmatic changes
        switchAutoStartAccOff.setOnClickListener {
            val isChecked = switchAutoStartAccOff.isChecked
            saveAutoStartAccOff(isChecked)
            // Write to daemon config file so AccSentryDaemon can read it
            writeDaemonConfigProperty("auto_start_acc_off", if (isChecked) "true" else "false", showToast = true)
        }
    }
    
    private fun loadState() {
        // Load token - first try SharedPreferences, then fallback to daemon config file
        var token = botTokenConfig.getToken()
        
        if (token == null) {
            // Try to restore from daemon config file
            restoreTokenFromDaemonConfig()
        } else {
            etBotToken.setText(token)
            val botInfo = botTokenConfig.getCachedBotInfo()
            if (botInfo != null) {
                tvBotStatus.text = "🟢 Connected"
                tvBotInfo.text = "@${botInfo.username}"
                tvBotInfo.visibility = View.VISIBLE
            }
        }
        
        // Load owner state
        updateOwnerUI()
        
        // Load preferences
        val prefs = ownerStore.getPreferences()
        switchCriticalAlerts.isChecked = prefs.isCriticalAlerts
        switchConnectivity.isChecked = prefs.isConnectivityUpdates
        switchMotionText.isChecked = prefs.isMotionText
        switchVideoUploads.isChecked = prefs.isVideoUploads
        
        // Load daemon state
        loadDaemonState()
    }
    
    /**
     * Try to restore bot token from daemon config file (/data/local/tmp/telegram_config.properties).
     * This is useful when the app is reinstalled but the daemon config still exists.
     */
    private fun restoreTokenFromDaemonConfig() {
        val configFile = "/data/local/tmp/telegram_config.properties"
        val cmd = "cat $configFile 2>/dev/null | grep '^bot_token=' | cut -d'=' -f2-"
        
        adbLauncher?.executeShellCommand(cmd, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                val token = message.trim()
                if (token.isNotEmpty() && token.contains(":")) {
                    // Valid token format (contains colon like "123456:ABC...")
                    activity?.runOnUiThread {
                        etBotToken.setText(token)
                        tvBotStatus.text = "⚠️ Restored from config"
                        tvBotInfo.text = "Click 'Connect & Test' to verify"
                        tvBotInfo.visibility = View.VISIBLE
                        
                        // Also restore owner info if present
                        restoreOwnerFromDaemonConfig()
                    }
                }
            }
            
            override fun onLaunched() {}
            override fun onError(error: String) {
                android.util.Log.d("TelegramSettings", "No daemon config to restore from: $error")
            }
        })
    }
    
    /**
     * Try to restore owner info from daemon config file.
     */
    private fun restoreOwnerFromDaemonConfig() {
        val configFile = "/data/local/tmp/telegram_config.properties"
        val cmd = "cat $configFile 2>/dev/null"
        
        adbLauncher?.executeShellCommand(cmd, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                // Parse owner info from config
                val lines = message.lines()
                var ownerId: Long? = null
                var ownerUsername: String? = null
                var ownerFirstName: String? = null
                
                for (line in lines) {
                    when {
                        line.startsWith("owner_id=") -> {
                            ownerId = line.substringAfter("=").trim().toLongOrNull()
                        }
                        line.startsWith("owner_username=") -> {
                            ownerUsername = line.substringAfter("=").trim()
                        }
                        line.startsWith("owner_first_name=") -> {
                            ownerFirstName = line.substringAfter("=").trim()
                        }
                    }
                }
                
                // If we have owner info, restore it
                if (ownerId != null && ownerUsername != null) {
                    activity?.runOnUiThread {
                        // Save to local store
                        val owner = com.overdrive.app.telegram.model.OwnerInfo(
                            ownerId,
                            ownerUsername,
                            ownerFirstName ?: ownerUsername,
                            System.currentTimeMillis()  // pairedAt - use current time as fallback
                        )
                        ownerStore.saveOwner(owner)
                        updateOwnerUI()
                        
                        android.util.Log.d("TelegramSettings", "Restored owner from daemon config: @$ownerUsername")
                    }
                }
            }
            
            override fun onLaunched() {}
            override fun onError(error: String) {}
        })
    }
    
    private fun loadDaemonState() {
        // Load auto-start on ACC off preference
        val prefs = requireContext().getSharedPreferences("telegram_prefs", Context.MODE_PRIVATE)
        val autoStartAccOff = prefs.getBoolean(PREF_AUTO_START_ACC_OFF, false)
        switchAutoStartAccOff.isChecked = autoStartAccOff
        
        // Check current daemon status
        updateDaemonStatus()
    }
    
    private fun updateDaemonStatus() {
        adbLauncher?.isDaemonRunning("telegram_bot_daemon") { isRunning ->
            activity?.runOnUiThread {
                tvDaemonStatus.text = if (isRunning) "🟢 Running" else "🔴 Stopped"
            }
        }
    }
    
    private fun saveAutoStartAccOff(enabled: Boolean) {
        val prefs = requireContext().getSharedPreferences("telegram_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_AUTO_START_ACC_OFF, enabled).apply()
    }
    
    private fun testBotToken() {
        val token = etBotToken.text?.toString()?.trim() ?: ""
        if (token.isEmpty()) {
            Toast.makeText(context, "Enter bot token", Toast.LENGTH_SHORT).show()
            return
        }
        
        btnConnectTest.isEnabled = false
        tvBotStatus.text = "Testing..."
        
        executor.execute {
            val result = botTokenConfig.validateToken(token)
            
            activity?.runOnUiThread {
                btnConnectTest.isEnabled = true
                
                if (result.isValid) {
                    val botInfo = result.botInfo!!
                    botTokenConfig.saveToken(token)
                    botTokenConfig.saveBotInfo(botInfo)
                    
                    // Write config to daemon file via ADB shell (app can't write to /data/local/tmp directly)
                    writeDaemonConfigViaShell(token)
                    
                    tvBotStatus.text = "🟢 Connected"
                    tvBotInfo.text = "@${botInfo.username}"
                    tvBotInfo.visibility = View.VISIBLE
                    
                    Toast.makeText(context, "Bot connected! Starting daemon for pairing...", Toast.LENGTH_SHORT).show()
                    
                    // Start daemon immediately so user can pair via /pair command
                    startTelegramDaemonForPairing()
                } else {
                    tvBotStatus.text = "🔴 Failed"
                    tvBotInfo.visibility = View.GONE
                    Toast.makeText(context, result.errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Start telegram daemon for pairing.
     * Daemon will stay running until vehicle turns on (if auto-start on vehicle off is enabled)
     * or until manually stopped from daemon view.
     */
    private fun startTelegramDaemonForPairing() {
        tvDaemonStatus.text = "⏳ Starting..."
        
        // Small delay to ensure config is written
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            adbLauncher?.launchTelegramDaemon(object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                
                override fun onLaunched() {
                    activity?.runOnUiThread {
                        tvDaemonStatus.text = "🟢 Running"
                        Toast.makeText(context, "Daemon running. Send /pair <PIN> to your bot.", Toast.LENGTH_LONG).show()
                    }
                }
                
                override fun onError(error: String) {
                    activity?.runOnUiThread {
                        tvDaemonStatus.text = "🔴 Failed"
                        Toast.makeText(context, "Failed to start daemon: $error", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }, 500)
    }
    
    /**
     * Write bot token to daemon config file via ADB shell.
     * The app process can't write to /data/local/tmp directly, but ADB shell can.
     */
    private fun writeDaemonConfigViaShell(token: String) {
        // Write token and current video uploads preference
        writeDaemonConfigProperty("bot_token", token, showToast = false)
        writeDaemonConfigProperty("video_uploads", if (switchVideoUploads.isChecked) "true" else "false", showToast = true)
    }
    
    /**
     * Write a single property to the daemon config file via ADB shell.
     * Preserves existing properties in the file.
     */
    private fun writeDaemonConfigProperty(key: String, value: String, showToast: Boolean = true) {
        val configFile = "/data/local/tmp/telegram_config.properties"
        // Escape special characters for shell - use double quotes for variable expansion
        val escapedValue = value.replace("\\", "\\\\").replace("\"", "\\\"")
        
        // Simple approach: create file if not exists, remove old key line, append new key=value
        // This avoids complex sed syntax that may not work on all Android shells
        val cmd = "touch $configFile && " +
                  "grep -v \"^$key=\" $configFile > ${configFile}.tmp 2>/dev/null; " +
                  "mv ${configFile}.tmp $configFile 2>/dev/null; " +
                  "echo \"$key=$escapedValue\" >> $configFile && " +
                  "chmod 666 $configFile"
        
        android.util.Log.d("TelegramSettings", "Writing config: $cmd")
        
        adbLauncher?.executeShellCommand(cmd, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                android.util.Log.d("TelegramSettings", "Config write log: $message")
            }
            
            override fun onLaunched() {
                android.util.Log.d("TelegramSettings", "Config write success for key: $key")
                if (showToast) {
                    activity?.runOnUiThread {
                        Toast.makeText(context, "Config saved for daemon", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            override fun onError(error: String) {
                android.util.Log.e("TelegramSettings", "Config write error: $error")
                if (showToast) {
                    activity?.runOnUiThread {
                        Toast.makeText(context, "Warning: Could not save daemon config: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }
    
    /**
     * Write multiple properties to daemon config file via ADB shell.
     */
    private fun writeDaemonConfigProperties(properties: Map<String, String>) {
        properties.entries.forEachIndexed { index, (key, value) ->
            val isLast = index == properties.size - 1
            writeDaemonConfigProperty(key, value, showToast = isLast)
        }
    }
    
    private fun generatePin() {
        val pinState = pairingManager.generatePin()
        
        tvPin.text = pinState.pin
        tvPin.visibility = View.VISIBLE
        tvPinExpiry.visibility = View.VISIBLE
        
        // Write PIN to daemon config so TelegramBotDaemon can validate it
        writeDaemonConfigProperty("pair_pin", pinState.pin, showToast = false)
        writeDaemonConfigProperty("pair_pin_expiry", pinState.expiresAt.toString(), showToast = false)
        
        // Start countdown
        pinCountdownTimer?.cancel()
        pinCountdownTimer = object : CountDownTimer(pinState.remainingMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                val minutes = seconds / 60
                val secs = seconds % 60
                tvPinExpiry.text = String.format("(%d:%02d)", minutes, secs)
            }
            
            override fun onFinish() {
                tvPin.visibility = View.GONE
                tvPinExpiry.visibility = View.GONE
                Toast.makeText(context, "PIN expired", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
    
    private fun unpairOwner() {
        pairingManager.clearOwner()
        // Also clear owner from daemon config via shell
        clearOwnerFromDaemonConfigViaShell()
        updateOwnerUI()
        Toast.makeText(context, "Owner unpaired", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Clear owner info from daemon config file via ADB shell.
     */
    private fun clearOwnerFromDaemonConfigViaShell() {
        val configFile = "/data/local/tmp/telegram_config.properties"
        // Remove owner-related lines from config using grep -v (more portable than sed -i)
        val cmd = "grep -v \"^owner_\" $configFile > ${configFile}.tmp 2>/dev/null; " +
                  "mv ${configFile}.tmp $configFile 2>/dev/null; " +
                  "echo 'Owner cleared from config'"
        
        adbLauncher?.executeShellCommand(cmd, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                android.util.Log.d("TelegramSettings", "Clear owner log: $message")
            }
            override fun onLaunched() {
                android.util.Log.d("TelegramSettings", "Owner cleared from daemon config")
            }
            override fun onError(error: String) {
                android.util.Log.e("TelegramSettings", "Clear owner error: $error")
            }
        })
    }
    
    private fun updateOwnerUI() {
        val owner = pairingManager.getOwner()
        
        if (owner != null) {
            layoutNoOwner.visibility = View.GONE
            layoutOwnerPaired.visibility = View.VISIBLE
            tvOwnerInfo.text = "✅ Paired with ${owner.firstName} (@${owner.username})"
        } else {
            layoutNoOwner.visibility = View.VISIBLE
            layoutOwnerPaired.visibility = View.GONE
        }
    }
    
    private fun savePreferences() {
        val prefs = NotificationPreferences(
            switchCriticalAlerts.isChecked,
            switchConnectivity.isChecked,
            switchMotionText.isChecked,
            switchVideoUploads.isChecked
        )
        ownerStore.savePreferences(prefs)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        pinCountdownTimer?.cancel()
        adbLauncher?.closePersistentConnection()
    }
}
