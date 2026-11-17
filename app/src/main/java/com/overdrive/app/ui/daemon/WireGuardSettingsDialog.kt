package com.overdrive.app.ui.daemon

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.overdrive.app.R
import com.overdrive.app.daemon.CameraDaemon
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.model.TunnelDisplayPolicy
import com.overdrive.app.ui.util.QrCodeGenerator
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Setup dialog for the WireGuard tunnel. The config is only ever sent to the
 * daemon (POST /api/wireguard/config); the saved private key is never read back
 * or shown here.
 *
 * Config sources: paste, a picked file (.conf or a picture of a WireGuard QR
 * code), or the phone, through the car's own dashboard page.
 */
class WireGuardSettingsDialog private constructor(
    private val context: Context,
    private val daemonsViewModel: DaemonsViewModel,
    private val pickFile: ActivityResultLauncher<Array<String>>,
    private val onClosed: () -> Unit
) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var dialog: AlertDialog
    private lateinit var tvStatus: TextView
    private lateinit var tvHandshake: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tilConfig: TextInputLayout
    private lateinit var etConfig: TextInputEditText
    private lateinit var phoneContainer: View
    private lateinit var switchExpose: SwitchMaterial
    private lateinit var tvExposeUrl: TextView
    private var closed = false

    private val poll = object : Runnable {
        override fun run() {
            refreshStatus()
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_wireguard_settings, null)
        tvStatus = view.findViewById(R.id.tvWgStatus)
        tvHandshake = view.findViewById(R.id.tvWgHandshake)
        tvDetail = view.findViewById(R.id.tvWgDetail)
        tilConfig = view.findViewById(R.id.tilWgConfig)
        etConfig = view.findViewById(R.id.etWgConfig)
        phoneContainer = view.findViewById(R.id.wgPhoneContainer)
        switchExpose = view.findViewById(R.id.switchWgExpose)
        tvExposeUrl = view.findViewById(R.id.tvWgExposeUrl)
        switchExpose.setOnCheckedChangeListener(exposeListener)

        view.findViewById<MaterialButton>(R.id.btnWgPaste).setOnClickListener { pasteFromClipboard() }
        view.findViewById<MaterialButton>(R.id.btnWgImport).setOnClickListener { startImport() }
        view.findViewById<MaterialButton>(R.id.btnWgPhone).setOnClickListener { showPhoneSetup(view) }

        dialog = MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_link)
            .setTitle(context.getString(R.string.wgsetup_title))
            .setView(view)
            .setPositiveButton(context.getString(R.string.dialog_save), null)
            .setNeutralButton(context.getString(R.string.wgsetup_delete), null)
            .setNegativeButton(context.getString(R.string.action_cancel), null)
            .create()
        dialog.setOnDismissListener {
            closed = true
            main.removeCallbacks(poll)
            io.shutdown()
            onClosed()
        }
        dialog.show()

        // Override the click handlers so a validation error keeps the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            visibility = View.GONE
            setOnClickListener { confirmDelete() }
        }

        etConfig.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                tilConfig.error = null
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        main.post(poll)
    }

    // ---- dashboard opt-in ----

    private val exposeListener = android.widget.CompoundButton.OnCheckedChangeListener { button, checked ->
        button.isEnabled = false
        io.execute {
            val body = JSONObject().put("enabled", checked).toString()
            val ok = request("POST", "/api/wireguard/expose", body)
                ?.second?.optBoolean("success", false) == true
            val running = ok && request("GET", "/api/wireguard", null)
                ?.second?.optBoolean("running", false) == true
            main.post {
                button.isEnabled = true
                if (!ok) {
                    setExposeChecked(!checked)
                    toast(R.string.wgsetup_expose_failed)
                    return@post
                }
                // wgproxy takes its launch options at start, so a running tunnel restarts.
                if (running) daemonsViewModel.restartWireGuard()
                if (!closed) refreshStatus()
            }
        }
    }

    private fun setExposeChecked(checked: Boolean) {
        if (switchExpose.isChecked == checked) return
        switchExpose.setOnCheckedChangeListener(null)
        switchExpose.isChecked = checked
        switchExpose.setOnCheckedChangeListener(exposeListener)
    }

    /** Tunnel address and port the dashboard answers on, taken from the live status. */
    private fun renderExposeUrl(json: JSONObject, status: JSONObject?) {
        val exposed = json.optBoolean("expose_dashboard", false)
        setExposeChecked(exposed)
        val ports = status?.optJSONArray("expose")
        val addrs = status?.optJSONArray("addresses")
        val live = ports != null && ports.length() > 0 && addrs != null && addrs.length() > 0
        if (!exposed || !live) {
            tvExposeUrl.visibility = View.GONE
            return
        }
        val urls = (0 until addrs!!.length()).mapNotNull { i ->
            val ip = addrs.optString(i).substringBefore('/')
            if (ip.isEmpty()) return@mapNotNull null
            val host = if (ip.contains(':')) "[$ip]" else ip
            val suffix = if (ports!!.optInt(0) == 80) "" else ":${ports.optInt(0)}"
            "http://$host$suffix"
        }
        tvExposeUrl.text = context.getString(R.string.wgsetup_expose_url, urls.joinToString(", "))
        tvExposeUrl.visibility = if (urls.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- status ----

    private fun refreshStatus() {
        if (closed) return
        io.execute {
            val reply = request("GET", "/api/wireguard", null)
            main.post { if (!closed) renderStatus(reply?.second) }
        }
    }

    fun dismiss() {
        if (::dialog.isInitialized && dialog.isShowing) dialog.dismiss()
    }

    private fun renderStatus(json: JSONObject?) {
        if (json == null) {
            tvStatus.setText(R.string.wgsetup_status_unavailable)
            tvHandshake.visibility = View.GONE
            tvDetail.visibility = View.GONE
            return
        }
        val configured = json.optBoolean("configured", false)
        val running = json.optBoolean("running", false)
        val summary = json.optJSONObject("summary")
        val status = if (running) json.optJSONObject("status") else null
        renderExposeUrl(json, status)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility =
            if (configured) View.VISIBLE else View.GONE

        var handshake: String? = null
        val title = when {
            !configured -> context.getString(R.string.wgsetup_status_none)
            !running -> context.getString(R.string.wgsetup_status_stopped)
            status == null -> context.getString(R.string.wgsetup_status_loading)
            else -> {
                val peer = bestPeer(status)
                val endpoint = peer?.optString("endpoint").orEmpty()
                    .ifEmpty { summary?.optJSONArray("endpoints")?.optString(0).orEmpty() }
                when (status.optString("state")) {
                    "connected" -> {
                        handshake = handshakeText(peer)
                        context.getString(R.string.wgsetup_status_connected, endpoint)
                    }
                    "reloading" -> context.getString(R.string.wgsetup_status_reloading)
                    "error" -> context.getString(
                        R.string.wgsetup_status_error, status.optString("error", "?"))
                    "stopped" -> context.getString(R.string.wgsetup_status_stopped)
                    else -> context.getString(R.string.wgsetup_status_connecting, endpoint)
                }
            }
        }
        tvStatus.text = title
        tvHandshake.text = handshake
        tvHandshake.visibility = if (handshake != null) View.VISIBLE else View.GONE

        val addresses = join(status?.optJSONArray("addresses")) ?: join(summary?.optJSONArray("addresses"))
        val routes = join(status?.optJSONArray("routes")) ?: join(summary?.optJSONArray("routes"))
        val detail = listOfNotNull(
            addresses?.let { context.getString(R.string.wgsetup_detail_address, it) },
            routes?.let { context.getString(R.string.wgsetup_detail_routes, it) }
        ).joinToString("\n")
        tvDetail.text = detail
        tvDetail.visibility = if (configured && detail.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** The peer with the newest handshake, else the first one. */
    private fun bestPeer(status: JSONObject): JSONObject? {
        val peers = status.optJSONArray("peers") ?: return null
        var best: JSONObject? = null
        for (i in 0 until peers.length()) {
            val p = peers.optJSONObject(i) ?: continue
            if (best == null || p.optLong("last_handshake") > best.optLong("last_handshake")) best = p
        }
        return best
    }

    private fun handshakeText(peer: JSONObject?): String {
        val at = peer?.optLong("last_handshake", 0L) ?: 0L
        if (at <= 0L) return context.getString(R.string.wgsetup_handshake_none)
        val ago = (System.currentTimeMillis() / 1000 - at).coerceAtLeast(0)
        return when {
            ago < 120 -> context.getString(R.string.wgsetup_handshake_seconds, ago.toInt())
            ago < 7200 -> context.getString(R.string.wgsetup_handshake_minutes, (ago / 60).toInt())
            else -> context.getString(R.string.wgsetup_handshake_hours, (ago / 3600).toInt())
        }
    }

    private fun join(arr: org.json.JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        return (0 until arr.length()).joinToString(", ") { arr.optString(it) }
    }

    // ---- input ----

    private fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
        if (text.isNullOrBlank()) {
            toast(R.string.wgsetup_clipboard_empty)
            return
        }
        etConfig.setText(text)
        tilConfig.error = null
        // Don't leave the private key on the clipboard.
        if (text.contains("PrivateKey", ignoreCase = true)) clipboard?.clearPrimaryClip()
    }

    private fun startImport() {
        if (WireGuardImport.hasDocumentPicker(context)) {
            launchPicker(arrayOf("*/*"))
            return
        }
        io.execute {
            val files = WireGuardImport.findConfigFiles()
            main.post { if (!closed) showConfigFiles(files) }
        }
    }

    /** In-app list of .conf files; the BYD gallery stays available for QR pictures. */
    private fun showConfigFiles(files: List<java.io.File>) {
        val pickQr = context.getString(R.string.wgsetup_import_qr_picture)
        val builder = MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setTitle(R.string.wgsetup_import_pick_title)
            .setNegativeButton(R.string.action_cancel, null)
        if (files.isEmpty()) {
            builder.setMessage(R.string.wgsetup_import_none_found)
                .setPositiveButton(pickQr) { _, _ -> launchPicker(arrayOf("image/*")) }
        } else {
            val labels = files.map { "${it.name}\n${it.parent}" } + pickQr
            builder.setItems(labels.toTypedArray()) { _, which ->
                if (which < files.size) onFilePicked(Uri.fromFile(files[which]))
                else launchPicker(arrayOf("image/*"))
            }
        }
        builder.show()
    }

    private fun launchPicker(types: Array<String>) {
        try {
            pickFile.launch(types)
        } catch (e: android.content.ActivityNotFoundException) {
            toast(R.string.wgsetup_import_no_picker)
        }
    }

    /** Result of the document picker started from the fragment, or a file from the in-app list. */
    fun onFilePicked(uri: Uri) {
        if (closed) return
        io.execute {
            val result = WireGuardImport.read(context, uri)
            main.post {
                if (closed) return@post
                when (result) {
                    is WireGuardImport.Result.Text -> {
                        etConfig.setText(result.text)
                        tilConfig.error = null
                        toast(R.string.wgsetup_imported)
                    }
                    WireGuardImport.Result.TooLarge -> toast(R.string.wgsetup_import_too_large)
                    WireGuardImport.Result.NoQr -> toast(R.string.wgsetup_import_no_qr)
                    WireGuardImport.Result.Unreadable -> toast(R.string.wgsetup_import_failed)
                }
            }
        }
    }

    private fun showPhoneSetup(root: View) {
        val url = dashboardBaseUrl()?.let { "$it/wireguard.html" }
        val qr = root.findViewById<ImageView>(R.id.wgPhoneQr)
        val label = root.findViewById<TextView>(R.id.wgPhoneUrl)
        val lanNote = root.findViewById<TextView>(R.id.wgPhoneLanNote)
        phoneContainer.visibility = View.VISIBLE
        lanNote.visibility = if (url != null && url.startsWith("http://")) View.VISIBLE else View.GONE
        if (url == null) {
            qr.visibility = View.GONE
            label.setText(R.string.wgsetup_phone_no_url)
            label.setTextColor(androidx.core.content.ContextCompat.getColor(
                label.context, R.color.status_danger))
            return
        }
        qr.visibility = View.VISIBLE
        qr.setImageBitmap(QrCodeGenerator.generate(url, 400))
        label.text = url
    }

    /**
     * Where the phone can reach the car's dashboard: an active tunnel URL if
     * there is one (works from anywhere), else the LAN/hotspot address.
     */
    private fun dashboardBaseUrl(): String? {
        val states = daemonsViewModel.daemonStates.value
        val tunnels = listOf(
            daemonsViewModel.cloudflaredController.tunnelUrl.value to states?.get(DaemonType.CLOUDFLARED_TUNNEL)?.status,
            daemonsViewModel.zrokController.tunnelUrl.value to states?.get(DaemonType.ZROK_TUNNEL)?.status,
            daemonsViewModel.tailscaleController.tunnelUrl.value to states?.get(DaemonType.TAILSCALE_TUNNEL)?.status
        )
        val active = tunnels.filter { (url, status) -> TunnelDisplayPolicy.isActiveUrl(url, status) }
            .map { it.first!!.trim().trimEnd('/') }
        // https first: the config crosses this link.
        (active.firstOrNull { it.startsWith("https://") } ?: active.firstOrNull())?.let { return it }
        return lanAddress()?.let { "http://$it:${CameraDaemon.HTTP_PORT}" }
    }

    /** Private IPv4 of the Wi-Fi / hotspot interface; mobile-data interfaces are skipped. */
    private fun lanAddress(): String? {
        val candidates = mutableListOf<Pair<String, String>>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("ppp") ||
                    name.startsWith("tun") || name.startsWith("tailscale")) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        candidates.add(name to addr.hostAddress.orEmpty())
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        val preferred = candidates.firstOrNull {
            it.first.startsWith("wlan") || it.first.startsWith("ap") || it.first.startsWith("swlan")
        }
        return (preferred ?: candidates.firstOrNull())?.second?.takeIf { it.isNotEmpty() }
    }

    // ---- save / delete ----

    private fun save() {
        val text = etConfig.text?.toString().orEmpty()
        if (text.isBlank()) {
            tilConfig.error = context.getString(R.string.wgsetup_empty)
            return
        }
        tilConfig.error = null
        val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        saveButton.isEnabled = false
        io.execute {
            val body = JSONObject().put("config", text).toString()
            val reply = request("POST", "/api/wireguard/config", body)
            val json = reply?.second
            val ok = json?.optBoolean("success", false) == true
            // Whether the daemon is up decides if it must be started or reloads itself.
            val running = if (ok) request("GET", "/api/wireguard", null)
                ?.second?.optBoolean("running", false) ?: false else false
            main.post {
                saveButton.isEnabled = true
                if (!ok) {
                    val msg = json?.optString("error").orEmpty()
                        .ifEmpty { context.getString(R.string.wgsetup_status_unavailable) }
                    tilConfig.error = msg
                    return@post
                }
                toast(R.string.wgsetup_saved)
                if (!running) {
                    daemonsViewModel.daemonStartupManager?.onDaemonToggled(DaemonType.WIREGUARD_TUNNEL, true)
                    daemonsViewModel.startDaemon(DaemonType.WIREGUARD_TUNNEL)
                }
                daemonsViewModel.refreshDaemonStatus(DaemonType.WIREGUARD_TUNNEL)
                dialog.dismiss()
            }
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setTitle(context.getString(R.string.wgsetup_delete_title))
            .setMessage(context.getString(R.string.wgsetup_delete_message))
            .setPositiveButton(context.getString(R.string.action_delete)) { _, _ -> delete() }
            .setNegativeButton(context.getString(R.string.action_cancel), null)
            .show()
    }

    private fun delete() {
        io.execute {
            val ok = request("DELETE", "/api/wireguard/config", null)?.second
                ?.optBoolean("success", false) == true
            main.post {
                if (!ok) {
                    toast(R.string.wgsetup_delete_failed)
                    return@post
                }
                daemonsViewModel.stopDaemon(DaemonType.WIREGUARD_TUNNEL)
                daemonsViewModel.daemonStartupManager?.onDaemonToggled(DaemonType.WIREGUARD_TUNNEL, false)
                daemonsViewModel.refreshDaemonStatus(DaemonType.WIREGUARD_TUNNEL)
                toast(R.string.wgsetup_deleted)
                if (!closed) dialog.dismiss()
            }
        }
    }

    // ---- plumbing ----

    /** Returns (http status, parsed JSON body or null), or null when the daemon is unreachable. */
    private fun request(method: String, path: String, body: String?): Pair<Int, JSONObject?>? {
        return try {
            val conn = DaemonHttpClient.open(path, method, 3000, 8000)
            try {
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                code to (try { JSONObject(text) } catch (e: Exception) { null })
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val POLL_MS = 5000L

        /**
         * Show the dialog. [pickFile] is the fragment's document picker; its result
         * goes to [WireGuardSettingsDialog.onFilePicked]. [onClosed] fires on dismiss.
         */
        fun show(
            context: Context,
            daemonsViewModel: DaemonsViewModel,
            pickFile: ActivityResultLauncher<Array<String>>,
            onClosed: () -> Unit
        ): WireGuardSettingsDialog? {
            if (context is Activity && (context.isFinishing || context.isDestroyed)) return null
            val d = WireGuardSettingsDialog(context, daemonsViewModel, pickFile, onClosed)
            d.show()
            return d
        }
    }
}
