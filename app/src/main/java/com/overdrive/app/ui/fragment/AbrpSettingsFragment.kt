package com.overdrive.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.overdrive.app.R
import com.overdrive.app.abrp.AbrpTokenConfig
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Fragment for ABRP telemetry configuration and status monitoring.
 * Mirrors TelegramSettingsFragment pattern.
 */
class AbrpSettingsFragment : Fragment() {

    // Views
    private lateinit var etAbrpToken: TextInputEditText
    private lateinit var btnSaveTest: MaterialButton
    private lateinit var tvTokenStatus: TextView
    private lateinit var switchAbrpEnabled: SwitchMaterial
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvLastUpload: TextView
    private lateinit var tvTelemetryPreview: TextView
    private lateinit var btnDeleteToken: MaterialButton

    // Services
    private lateinit var tokenConfig: AbrpTokenConfig
    private var adbLauncher: AdbDaemonLauncher? = null

    private val executor = Executors.newSingleThreadExecutor()
    private var statusPoller: ScheduledExecutorService? = null
    private var pollFuture: ScheduledFuture<*>? = null

    companion object {
        private const val IPC_PORT = 19877
        private const val IPC_TIMEOUT_MS = 3000
        private const val POLL_INTERVAL_SECONDS = 5L
        private const val CONFIG_FILE = "/data/local/tmp/abrp_config.properties"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_abrp_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initServices()
        initViews(view)
        setupListeners()
        loadState()
        startStatusPolling()
    }

    private fun initServices() {
        val ctx = requireContext()
        tokenConfig = AbrpTokenConfig(ctx)
        adbLauncher = AdbDaemonLauncher(ctx)
    }

    private fun initViews(view: View) {
        etAbrpToken = view.findViewById(R.id.etAbrpToken)
        btnSaveTest = view.findViewById(R.id.btnSaveTest)
        tvTokenStatus = view.findViewById(R.id.tvTokenStatus)
        switchAbrpEnabled = view.findViewById(R.id.switchAbrpEnabled)
        tvConnectionStatus = view.findViewById(R.id.tvConnectionStatus)
        tvLastUpload = view.findViewById(R.id.tvLastUpload)
        tvTelemetryPreview = view.findViewById(R.id.tvTelemetryPreview)
        btnDeleteToken = view.findViewById(R.id.btnDeleteToken)
    }

    private fun setupListeners() {
        btnSaveTest.setOnClickListener { saveAndTestToken() }
        btnDeleteToken.setOnClickListener { deleteToken() }

        // Use setOnClickListener to avoid triggering on programmatic changes
        switchAbrpEnabled.setOnClickListener {
            val enabled = switchAbrpEnabled.isChecked
            setAbrpEnabled(enabled)
        }
    }

    // ==================== LOAD STATE ====================

    private fun loadState() {
        // Show existing token status
        if (tokenConfig.hasToken()) {
            val token = tokenConfig.getToken() ?: ""
            val masked = maskToken(token)
            tvTokenStatus.text = getString(R.string.abrp_token_label_format, masked)
        } else {
            tvTokenStatus.text = getString(R.string.abrp_not_configured_short)
        }

        // Load current ABRP status from daemon via IPC
        executor.execute {
            val status = sendIpcCommand("GET_ABRP_STATUS")
            activity?.runOnUiThread {
                if (status != null && status.optBoolean("success", false)) {
                    updateStatusUI(status.optJSONObject("status"))
                }
            }

            // Also load config to set the toggle state
            val config = sendIpcCommand("GET_ABRP_CONFIG")
            activity?.runOnUiThread {
                if (config != null && config.optBoolean("success", false)) {
                    val cfg = config.optJSONObject("config")
                    if (cfg != null) {
                        switchAbrpEnabled.isChecked = cfg.optBoolean("enabled", false)
                    }
                }
            }
        }
    }

    // ==================== SAVE & TEST ====================

    private fun saveAndTestToken() {
        val token = etAbrpToken.text?.toString()?.trim() ?: ""
        if (token.isEmpty()) {
            Toast.makeText(context, getString(R.string.toast_enter_abrp_token), Toast.LENGTH_SHORT).show()
            return
        }

        btnSaveTest.isEnabled = false
        tvTokenStatus.text = getString(R.string.abrp_token_status_testing)

        executor.execute {
            // 1. Save token to encrypted local storage
            tokenConfig.saveToken(token)

            // 2. Write daemon config via ADB shell
            writeDaemonConfig(token, true)

            // 3. Send SET_ABRP_CONFIG IPC command to daemon
            val extraFields = mapOf<String, Any>(
                "token" to token,
                "enabled" to true
            )
            val result = sendIpcCommand("SET_ABRP_CONFIG", extraFields)

            activity?.runOnUiThread {
                btnSaveTest.isEnabled = true

                val success = result != null && result.optBoolean("success", false)
                if (success) {
                    val masked = maskToken(token)
                    tvTokenStatus.text = getString(R.string.abrp_token_status_format, masked)
                    switchAbrpEnabled.isChecked = true
                    Toast.makeText(context, getString(R.string.toast_abrp_token_saved), Toast.LENGTH_SHORT).show()
                } else {
                    val msg = result?.optString("message", getString(R.string.abrp_failed_to_configure)) ?: getString(R.string.abrp_failed_to_connect)
                    tvTokenStatus.text = getString(R.string.abrp_save_failed_status)
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ==================== ENABLE/DISABLE ====================

    private fun setAbrpEnabled(enabled: Boolean) {
        executor.execute {
            // Update daemon config via ADB shell
            val token = tokenConfig.getToken() ?: ""
            if (token.isNotEmpty()) {
                writeDaemonConfig(token, enabled)
            }

            // Send IPC command
            val extraFields = mapOf<String, Any>(
                "enabled" to enabled
            )
            // Include token if available so daemon has full config
            val token2 = tokenConfig.getToken()
            val fields = if (token2 != null) {
                extraFields + ("token" to token2)
            } else {
                extraFields
            }
            val result = sendIpcCommand("SET_ABRP_CONFIG", fields)

            activity?.runOnUiThread {
                val success = result != null && result.optBoolean("success", false)
                if (success) {
                    Toast.makeText(context, if (enabled) getString(R.string.toast_abrp_telemetry_enabled) else getString(R.string.toast_abrp_telemetry_disabled), Toast.LENGTH_SHORT).show()
                } else {
                    // Revert toggle on failure
                    switchAbrpEnabled.isChecked = !enabled
                    Toast.makeText(context, getString(R.string.toast_abrp_failed_update), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ==================== DELETE TOKEN ====================

    private fun deleteToken() {
        executor.execute {
            // 1. Clear from encrypted local storage
            tokenConfig.clearToken()

            // 2. Send DELETE_ABRP_TOKEN IPC command
            sendIpcCommand("DELETE_ABRP_TOKEN")

            // 3. Clear daemon config file
            deleteDaemonConfig()

            activity?.runOnUiThread {
                etAbrpToken.setText("")
                tvTokenStatus.text = getString(R.string.abrp_not_configured)
                switchAbrpEnabled.isChecked = false
                tvConnectionStatus.text = getString(R.string.abrp_disconnected_status)
                tvLastUpload.text = getString(R.string.abrp_never)
                tvTelemetryPreview.text = getString(R.string.abrp_no_telemetry_text)
                Toast.makeText(context, getString(R.string.toast_abrp_token_deleted), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== STATUS POLLING ====================

    private fun startStatusPolling() {
        statusPoller = Executors.newSingleThreadScheduledExecutor()
        pollFuture = statusPoller?.scheduleAtFixedRate({
            val status = sendIpcCommand("GET_ABRP_STATUS")
            activity?.runOnUiThread {
                if (status != null && status.optBoolean("success", false)) {
                    updateStatusUI(status.optJSONObject("status"))
                }
            }
        }, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    private fun stopStatusPolling() {
        pollFuture?.cancel(false)
        pollFuture = null
        statusPoller?.shutdownNow()
        statusPoller = null
    }

    private fun updateStatusUI(status: JSONObject?) {
        if (status == null) return

        // Connection status
        val running = status.optBoolean("running", false)
        val consecutiveFailures = status.optInt("consecutiveFailures", 0)
        tvConnectionStatus.text = when {
            !running -> getString(R.string.abrp_disconnected_status)
            consecutiveFailures > 0 -> getString(R.string.abrp_retrying_status, consecutiveFailures)
            else -> getString(R.string.abrp_connected_status)
        }

        // Last upload time
        val lastUploadTime = status.optLong("lastUploadTime", 0)
        tvLastUpload.text = if (lastUploadTime > 0) {
            val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            dateFormat.format(Date(lastUploadTime))
        } else {
            getString(R.string.abrp_never)
        }

        // Telemetry preview
        val lastTelemetry = status.optJSONObject("lastTelemetry")
        if (lastTelemetry != null) {
            tvTelemetryPreview.text = formatTelemetryPreview(lastTelemetry)
        }
    }

    private fun formatTelemetryPreview(telemetry: JSONObject): String {
        val sb = StringBuilder()
        sb.appendLine("SOC: ${telemetry.optDouble("soc", -1.0)}%")
        sb.appendLine("Power: ${telemetry.optDouble("power", 0.0)} kW")
        sb.appendLine("Speed: ${telemetry.optDouble("speed", 0.0)} km/h")
        val lat = telemetry.optDouble("lat", 0.0)
        val lon = telemetry.optDouble("lon", 0.0)
        if (lat != 0.0 || lon != 0.0) {
            sb.appendLine("GPS: ${"%.4f".format(lat)}, ${"%.4f".format(lon)}")
        }
        sb.appendLine("Charging: ${if (telemetry.optInt("is_charging", 0) == 1) "Yes" else "No"}")
        sb.appendLine("Parked: ${if (telemetry.optInt("is_parked", 0) == 1) "Yes" else "No"}")
        val elevation = telemetry.optDouble("elevation", Double.NaN)
        if (!elevation.isNaN()) {
            sb.appendLine("Elevation: ${"%.1f".format(elevation)} m")
        }
        val soh = telemetry.optDouble("soh", -1.0)
        if (soh > 0) {
            sb.appendLine("SOH: ${"%.1f".format(soh)}%")
        }
        return sb.toString().trimEnd()
    }

    // ==================== IPC COMMUNICATION ====================

    private fun sendIpcCommand(command: String, extraFields: Map<String, Any> = emptyMap()): JSONObject? {
        return try {
            val socket = Socket("127.0.0.1", IPC_PORT)
            socket.soTimeout = IPC_TIMEOUT_MS
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            val request = JSONObject()
            request.put("command", command)
            extraFields.forEach { (k, v) -> request.put(k, v) }
            writer.println(request.toString())

            val response = reader.readLine()
            socket.close()
            if (response != null) JSONObject(response) else null
        } catch (e: Exception) {
            null
        }
    }

    // ==================== DAEMON CONFIG VIA ADB SHELL ====================

    private fun writeDaemonConfig(token: String, enabled: Boolean) {
        val escapedToken = token.replace("\\", "\\\\").replace("\"", "\\\"")
        val content = "user_token=$escapedToken\\nenabled=$enabled"
        val cmd = "sh -c \"echo '$content' > $CONFIG_FILE && chmod 666 $CONFIG_FILE\""

        adbLauncher?.executeShellCommand(cmd, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {}
            override fun onLaunched() {}
            override fun onError(error: String) {
                activity?.runOnUiThread {
                    Toast.makeText(context, getString(R.string.toast_warning_daemon_config_write), Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun deleteDaemonConfig() {
        val cmd = "rm -f $CONFIG_FILE"
        adbLauncher?.executeShellCommand(cmd, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {}
            override fun onLaunched() {}
            override fun onError(error: String) {}
        })
    }

    // ==================== UTILITY ====================

    private fun maskToken(token: String): String {
        return if (token.length >= 4) {
            "••••" + token.takeLast(4)
        } else {
            "••••$token"
        }
    }

    // ==================== LIFECYCLE ====================

    override fun onDestroyView() {
        super.onDestroyView()
        stopStatusPolling()
        adbLauncher?.closePersistentConnection()
    }
}
