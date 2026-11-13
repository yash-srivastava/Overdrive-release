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
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.overdrive.app.R
import com.overdrive.app.network.BydCloudWifiManager
import com.overdrive.app.network.CellularRelay
import com.overdrive.app.network.HotspotManager
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Network & Hotspot pane.
 *
 * The hotspot UI is a consumer of [HotspotManager]: it renders the manager's
 * snapshot and forwards user intent. BYD cloud compatibility is handled by
 * [BydCloudWifiManager], which owns the reversible Android setting and read-only
 * APN diagnostics. The fragment keeps blocking shell work off the main thread.
 *
 * Two independent tick rates: a 1 Hz UI tick that only re-renders the uptime
 * counter (no I/O), and a slower snapshot refresh for everything else.
 */
class NetworkFragment : Fragment() {

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Suppresses onCheckedChanged while restoring persisted values, so
     *  restoration is never mistaken for a user action. */
    private var binding = false

    /** Text inputs are seeded from config once, then left to the user. */
    private var textFieldsSeeded = false

    // The AP name/key are owned by the vehicle: read-only, shown so the user can
    // join, and cached here so the copy handlers don't re-read config on every tap.
    private var apSsid: String = ""
    private var apPassword: String = ""
    private var passwordRevealed = false

    private var lastSnapshot: JSONObject? = null
    private var bydCloudWifiManager: BydCloudWifiManager? = null

    private val uiTick = object : Runnable {
        override fun run() {
            renderUptime()
            mainHandler.postDelayed(this, UI_TICK_MS)
        }
    }

    private val snapshotTick = object : Runnable {
        override fun run() {
            refreshSnapshot()
            mainHandler.postDelayed(this, SNAPSHOT_TICK_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_network, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Fresh views need seeding again (re-entry, rotation).
        textFieldsSeeded = false
        HotspotManager.init(requireContext().applicationContext)
        bydCloudWifiManager = BydCloudWifiManager(requireContext().applicationContext)

        // Restore persisted values BEFORE attaching listeners.
        io.execute {
            val snap = HotspotManager.snapshot()
            mainHandler.post {
                if (isAdded) {
                    bindSnapshot(snap)
                    attachListeners(view)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(uiTick)
        mainHandler.post(snapshotTick)
        refreshBydCloudSnapshot()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(uiTick)
        mainHandler.removeCallbacks(snapshotTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ============== Listeners ==============

    private fun attachListeners(root: View) {
        root.findViewById<SwitchMaterial>(R.id.swHotspot)?.setOnCheckedChangeListener { btn, checked ->
            if (binding) return@setOnCheckedChangeListener
            if (checked) confirmThenEnable(btn) else setHotspot(false)
        }
        root.findViewById<TextView>(R.id.tvSsidValue)?.setOnClickListener {
            copyToClipboard(apSsid, R.string.network_ssid_label)
        }
        root.findViewById<TextView>(R.id.tvPasswordValue)?.setOnClickListener {
            copyToClipboard(apPassword, R.string.network_password_label)
        }
        root.findViewById<MaterialButton>(R.id.btnRevealPassword)?.setOnClickListener { btn ->
            passwordRevealed = !passwordRevealed
            (btn as? MaterialButton)?.setText(
                if (passwordRevealed) R.string.network_hide else R.string.network_reveal
            )
            renderPassword(root)
        }
        root.findViewById<MaterialButton>(R.id.btnSaveLimit)?.setOnClickListener {
            saveLimit(root)
        }
        root.findViewById<MaterialButton>(R.id.btnResetUsage)?.setOnClickListener {
            io.execute { HotspotManager.resetUsage(null) }
            toast(getString(R.string.network_usage_reset))
        }
        // Each switch binds ONLY its own key so the two persisted switches
        // cannot overwrite one another.
        bindSwitch(root, R.id.swKeepAlive, "keepAlive")
        bindSwitch(root, R.id.swAutoStart, "autoStartBoot")
        bindSwitch(root, R.id.swProxySystem, "proxySystemWide")
        bindSwitch(root, R.id.swProxyClients, "proxyForClients")
        optionalId("swClientTunnel")?.let { bindSwitch(root, it, "clientTunnel") }
        root.findViewById<SwitchMaterial>(R.id.swBydCloudWifi)?.let { cloudSwitch ->
            cloudSwitch.setOnCheckedChangeListener { _, checked ->
                if (binding) return@setOnCheckedChangeListener
                setBydCloudWifiEnabled(cloudSwitch, checked)
            }
        }
        root.findViewById<MaterialButton>(R.id.btnBydCloudRefresh)?.setOnClickListener {
            refreshBydCloudSnapshot()
        }
    }

    /**
     * Resolve an id by name, or null when the layout does not define it. Lets the
     * client-tunnel row be optional rather than a hard compile-time dependency.
     */
    private fun optionalId(name: String): Int? {
        val ctx = context ?: return null
        val id = ctx.resources.getIdentifier(name, "id", ctx.packageName)
        return if (id != 0) id else null
    }

    private fun bindSwitch(root: View, id: Int, key: String) {
        root.findViewById<SwitchMaterial>(id)?.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            io.execute { HotspotManager.applySettings(mapOf(key to checked), null) }
        }
    }

    /**
     * First enable needs an explicit acknowledgement: SoftAP and WiFi-STA share
     * one radio here, so hosting means the car leaves its WiFi network and uses
     * SIM data for as long as the hotspot is on.
     */
    private fun confirmThenEnable(button: CompoundButton) {
        val acked = lastSnapshot?.optBoolean("warnAck", false) ?: false
        if (acked) {
            setHotspot(true)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.network_warn_title)
            .setMessage(R.string.network_warn_body)
            .setPositiveButton(R.string.network_warn_continue) { _, _ ->
                io.execute { HotspotManager.applySettings(mapOf("warnAck" to true), null) }
                setHotspot(true)
            }
            .setNegativeButton(R.string.network_warn_cancel) { _, _ ->
                binding = true
                button.isChecked = false
                binding = false
            }
            .setOnCancelListener {
                binding = true
                button.isChecked = false
                binding = false
            }
            .show()
    }

    private fun setHotspot(on: Boolean) {
        io.execute {
            val cb: (Boolean, String) -> Unit = { ok, msg ->
                mainHandler.post {
                    if (!isAdded) return@post
                    if (!ok) {
                        toast(msg)
                        // Snap the switch back to the real state rather than
                        // leaving it showing an intent that failed.
                        refreshSnapshot()
                    }
                }
            }
            if (on) HotspotManager.enable(cb) else HotspotManager.disable(cb)
        }
        // Reflect the pending transition immediately, then let the snapshot
        // refresh replace it with the observed state.
        view?.findViewById<TextView>(R.id.tvHotspotState)?.setText(
            if (on) R.string.network_state_starting else R.string.network_state_stopping
        )
    }

    /** Mask everything but the last two characters, so a glance confirms the value. */
    private fun renderPassword(root: View) {
        val field = root.findViewById<TextView>(R.id.tvPasswordValue) ?: return
        val pw = apPassword
        field.text = when {
            pw.isEmpty() -> "—"
            passwordRevealed -> pw
            else -> "•".repeat(maxOf(0, pw.length - 2)) + pw.takeLast(2)
        }
    }

    private fun copyToClipboard(value: String, labelRes: Int) {
        if (value.isEmpty()) return
        val ctx = context ?: return
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clip.setPrimaryClip(ClipData.newPlainText(getString(labelRes), value))
        toast(getString(R.string.network_copied))
    }

    private fun saveLimit(root: View) {
        val raw = root.findViewById<TextInputEditText>(R.id.etDataCap)?.text?.toString()?.trim() ?: ""
        val mb = raw.toLongOrNull() ?: 0L
        if (mb < 0L) {
            toast(getString(R.string.network_limit_invalid))
            return
        }
        io.execute { HotspotManager.applySettings(mapOf("dataCapMb" to mb), null) }
        toast(getString(R.string.network_saved))
    }

    // ============== Rendering ==============

    private fun refreshSnapshot() {
        io.execute {
            val snap = try { HotspotManager.snapshot() } catch (t: Throwable) { null } ?: return@execute
            mainHandler.post { if (isAdded) bindSnapshot(snap) }
        }
    }

    private fun bindSnapshot(snap: JSONObject) {
        val root = view ?: return
        lastSnapshot = snap
        snapshotAtMs = System.currentTimeMillis()
        binding = true
        try {
            val enabled = snap.optBoolean("enabled", false)
            val transitioning = snap.optBoolean("transitioning", false)
            root.findViewById<SwitchMaterial>(R.id.swHotspot)?.isChecked = enabled || transitioning

            val stateRes = when {
                transitioning -> R.string.network_state_starting
                enabled -> R.string.network_state_on
                else -> R.string.network_state_off
            }
            val stateView = root.findViewById<TextView>(R.id.tvHotspotState)
            val err = snap.optString("lastError", "")
            if (err.isNotEmpty() && !enabled) {
                stateView?.text = err
            } else {
                stateView?.setText(stateRes)
            }

            // The vehicle's own credentials: always safe to repaint, since nothing here
            // is user-editable.
            apSsid = snap.optString("activeSsid", "").ifEmpty { snap.optString("ssid", "") }
            apPassword = snap.optString("activePassword", "")
            root.findViewById<TextView>(R.id.tvSsidValue)?.text =
                apSsid.ifEmpty { "—" }
            renderPassword(root)

            // Seed the remaining text field ONCE. A focus check is not enough: the 5s
            // refresh would still wipe typed-but-unsaved text as soon as the field lost
            // focus (keyboard dismissed, tapping Save). After the first paint the input
            // belongs to the user, and saving re-reads it anyway.
            if (!textFieldsSeeded) {
                root.findViewById<TextInputEditText>(R.id.etDataCap)?.let { field ->
                    val cap = snap.optLong("dataCapMb", 0L)
                    field.setText(if (cap > 0L) cap.toString() else "")
                }
                textFieldsSeeded = true
            }

            root.findViewById<SwitchMaterial>(R.id.swKeepAlive)?.isChecked =
                snap.optBoolean("keepAlive", false)
            root.findViewById<SwitchMaterial>(R.id.swAutoStart)?.isChecked =
                snap.optBoolean("autoStartBoot", false)
            root.findViewById<SwitchMaterial>(R.id.swProxySystem)?.isChecked =
                snap.optBoolean("proxySystemWide", false)
            root.findViewById<SwitchMaterial>(R.id.swProxyClients)?.isChecked =
                snap.optBoolean("proxyForClients", false)
            optionalId("swClientTunnel")?.let { id ->
                root.findViewById<SwitchMaterial>(id)?.isChecked =
                    snap.optBoolean("clientTunnel", false)
            }
            optionalId("tvClientTunnelDesc")?.let { id ->
                root.findViewById<TextView>(id)?.text = getString(
                    R.string.network_client_tunnel_desc_fmt,
                    snap.optInt("clientTunnelPort",
                        com.overdrive.app.daemon.proxy.ProxyConfiguration.CLIENT_TUNNEL_PORT),
                    snap.optInt("relayPort", CellularRelay.PORT)
                )
            }

            // Advertise the cellular-bound relay, not the outbound tunnel proxy:
            // the relay is the only endpoint that gives clients internet here.
            root.findViewById<TextView>(R.id.tvProxyClientsDesc)?.text = getString(
                R.string.network_proxy_clients_desc_fmt,
                snap.optString("gateway", HotspotManager.AP_GATEWAY),
                snap.optInt("relayPort", CellularRelay.PORT)
            )

            root.findViewById<TextView>(R.id.tvRx)?.text = formatBytes(snap.optLong("rxBytes", 0L))
            root.findViewById<TextView>(R.id.tvTx)?.text = formatBytes(snap.optLong("txBytes", 0L))
            renderUptime()
            renderUsage(root, snap)
            renderClients(root, snap)
        } finally {
            binding = false
        }
    }

    /** 1 Hz, UI only: extrapolate from the last snapshot instead of doing I/O. */
    private fun renderUptime() {
        val root = view ?: return
        val snap = lastSnapshot ?: return
        val base = snap.optLong("uptimeSeconds", 0L)
        val drift = if (snapshotAtMs > 0L) (System.currentTimeMillis() - snapshotAtMs) / 1000L else 0L
        val secs = if (snap.optBoolean("enabled", false)) base + drift.coerceAtLeast(0L) else 0L
        root.findViewById<TextView>(R.id.tvUptime)?.text = formatDuration(secs)
    }

    private fun renderUsage(root: View, snap: JSONObject) {
        // The limit is measured against cumulative usage, not just this session.
        val used = snap.optLong("dataUsedBytes", 0L) +
            snap.optLong("rxBytes", 0L) + snap.optLong("txBytes", 0L)
        val cap = snap.optLong("dataCapMb", 0L)
        val label = if (cap > 0L) {
            getString(R.string.network_usage_fmt, formatBytes(used), "$cap MB")
        } else {
            getString(R.string.network_usage_nolimit_fmt, formatBytes(used))
        }
        root.findViewById<TextView>(R.id.tvUsage)?.text = label
    }

    private fun setBydCloudWifiEnabled(button: SwitchMaterial, enabled: Boolean) {
        button.isEnabled = false
        io.execute {
            val manager = bydCloudWifiManager
            val result = if (manager == null) {
                BydCloudWifiManager.OperationResult(false, "BYD cloud network manager is unavailable.")
            } else if (enabled) {
                manager.enable()
            } else {
                manager.disable()
            }
            val snapshot = manager?.snapshot()
            mainHandler.post {
                if (!isAdded) return@post
                binding = true
                button.isChecked = manager?.isEnabled() == true
                binding = false
                button.isEnabled = true
                if (snapshot != null) renderBydCloudSnapshot(snapshot, result.message)
                if (!result.success) toast(result.message)
            }
        }
    }

    private fun refreshBydCloudSnapshot() {
        val manager = bydCloudWifiManager ?: return
        val button = view?.findViewById<MaterialButton>(R.id.btnBydCloudRefresh)
        button?.isEnabled = false
        io.execute {
            val snapshot = manager.snapshot()
            mainHandler.post {
                if (!isAdded) return@post
                val switch = view?.findViewById<SwitchMaterial>(R.id.swBydCloudWifi)
                binding = true
                switch?.isChecked = manager.isEnabled()
                binding = false
                switch?.isEnabled = true
                button?.isEnabled = true
                renderBydCloudSnapshot(snapshot)
            }
        }
    }

    private fun renderBydCloudSnapshot(
        snapshot: BydCloudWifiManager.Snapshot,
        operationMessage: String? = null
    ) {
        val root = view ?: return
        val status = root.findViewById<TextView>(R.id.tvBydCloudStatus)
        val apn3 = root.findViewById<TextView>(R.id.tvBydCloudApn3)

        status?.text = when {
            snapshot.error != null -> getString(R.string.network_byd_cloud_status_error, snapshot.error)
            snapshot.mobileData == "0" -> getString(R.string.network_byd_cloud_status_data_off)
            snapshot.managedByOverdrive -> getString(R.string.network_byd_cloud_status_managed)
            snapshot.mobileDataAlwaysOn == "1" -> getString(R.string.network_byd_cloud_status_already_on)
            else -> getString(R.string.network_byd_cloud_status_off)
        }
        if (operationMessage != null && operationMessage.isNotBlank() && snapshot.error == null) {
            status?.append("\n\n$operationMessage")
        }
        apn3?.text = when (snapshot.apn3State) {
            "connected" -> getString(R.string.network_byd_cloud_apn3_connected)
            "disconnected" -> getString(R.string.network_byd_cloud_apn3_disconnected)
            else -> getString(R.string.network_byd_cloud_apn3_unknown)
        }
    }

    private fun renderClients(root: View, snap: JSONObject) {
        val list = root.findViewById<LinearLayout>(R.id.clientList) ?: return
        val empty = root.findViewById<TextView>(R.id.tvClientsEmpty)
        val arr = snap.optJSONArray("clients")
        list.removeAllViews()
        if (arr == null || arr.length() == 0) {
            empty?.visibility = View.VISIBLE
            return
        }
        empty?.visibility = View.GONE
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val row = LinearLayout(list.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, if (i == 0) 0 else 12, 0, 0)
            }
            val name = TextView(list.context).apply {
                text = c.optString("name", "")
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            }
            val meta = TextView(list.context).apply {
                val ip = c.optString("ip", "")
                text = if (ip.isEmpty()) c.optString("mac", "")
                else getString(R.string.network_client_meta_fmt, ip, c.optString("mac", ""))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            }
            row.addView(name)
            row.addView(meta)
            list.addView(row)
        }
    }

    private fun toast(message: String) {
        val ctx = context ?: return
        android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ============== Formatting ==============

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0L) return "--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private var snapshotAtMs: Long = 0L

    companion object {
        private const val UI_TICK_MS = 1_000L
        private const val SNAPSHOT_TICK_MS = 5_000L
    }
}
