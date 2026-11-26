package com.overdrive.app.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.overdrive.app.R
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.vehicle.VehicleArt
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Diagnostics "Vehicle setup" card: what the head unit looks like versus what is
 * configured. Display-only; all main-thread state, probes run on [executor].
 */
class VehicleSetupCard(private val fragment: Fragment) {

    private var card: View? = null
    private var dot: View? = null
    private var tvDetected: TextView? = null
    private var tvConfigured: TextView? = null
    private var tvStatus: TextView? = null
    private var ivArt: ImageView? = null
    private var detailsDialog: AlertDialog? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    // A probe runs and renders only while no newer refresh or unbind has happened.
    private val generation = AtomicInteger()
    private var latest: VehicleSetupSnapshot? = null
    private var lastCameraStatus: DaemonStatus? = null
    private var unreachableRetries = 0
    @Volatile private var modelNames: Map<String, String>? = null
    private val refreshRunnable = Runnable { startRefresh() }

    fun bind(root: View) {
        val view = root.findViewById<View>(R.id.cardVehicleSetup) ?: return
        card = view
        dot = view.findViewById(R.id.viewVehicleSetupDot)
        tvDetected = view.findViewById(R.id.tvVehicleSetupDetected)
        tvConfigured = view.findViewById(R.id.tvVehicleSetupConfigured)
        tvStatus = view.findViewById(R.id.tvVehicleSetupStatus)
        ivArt = view.findViewById(R.id.ivVehicleSetupArt)
        view.setOnClickListener { latest?.let(::showDetails) }
    }

    fun unbind() {
        generation.incrementAndGet()
        mainHandler.removeCallbacks(refreshRunnable)
        executor?.shutdownNow()
        executor = null
        detailsDialog?.dismiss()
        detailsDialog = null
        card = null
        dot = null
        tvDetected = null
        tvConfigured = null
        tvStatus = null
        ivArt = null
        latest = null
        lastCameraStatus = null
        unreachableRetries = 0
    }

    /** Coalesces bursts (resume plus the first LiveData emission) into one probe. */
    fun refresh() {
        if (card == null) return
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
    }

    fun onCameraDaemonStatus(status: DaemonStatus?) {
        if (status == lastCameraStatus) return
        lastCameraStatus = status
        unreachableRetries = 0
        // A probe that started before this change must not render or schedule retries.
        generation.incrementAndGet()
        refresh()
    }

    private fun startRefresh() {
        if (card == null) return
        val appContext = fragment.context?.applicationContext ?: return
        val token = generation.incrementAndGet()
        val worker = executor ?: Executors.newSingleThreadExecutor().also { executor = it }
        worker.execute {
            if (token != generation.get()) return@execute
            val snapshot = try { VehicleSetupProbe.collect() } catch (_: Throwable) { null }
            val names = modelNames ?: loadModelNames(appContext)
            mainHandler.post {
                if (token != generation.get() || card == null) return@post
                if (names.isNotEmpty()) modelNames = names
                if (snapshot != null) {
                    latest = snapshot
                    render(snapshot)
                    retryWhileServiceStarts(snapshot)
                }
            }
        }
    }

    /** RUNNING is reported once the process exists, which can precede its HTTP listener. */
    private fun retryWhileServiceStarts(snapshot: VehicleSetupSnapshot) {
        if (snapshot.runningCameraMode != null) {
            unreachableRetries = 0
        } else if (lastCameraStatus == DaemonStatus.RUNNING
            && unreachableRetries < MAX_UNREACHABLE_RETRIES) {
            val delay = minOf(UNREACHABLE_RETRY_MS shl unreachableRetries, MAX_RETRY_DELAY_MS)
            unreachableRetries++
            mainHandler.removeCallbacks(refreshRunnable)
            mainHandler.postDelayed(refreshRunnable, delay)
        }
    }

    private fun render(s: VehicleSetupSnapshot) {
        val ctx = fragment.context ?: return
        val d = s.detection

        var detected = ctx.getString(
            when (d.platform) {
                VehicleSetupDetector.Platform.DILINK100 -> R.string.vehicle_setup_platform_dilink100
                VehicleSetupDetector.Platform.DILINK5 -> R.string.vehicle_setup_platform_dilink5
                VehicleSetupDetector.Platform.LEGACY -> R.string.vehicle_setup_platform_legacy
                VehicleSetupDetector.Platform.UNKNOWN -> R.string.vehicle_setup_platform_unknown
            }
        )
        if (d.confidence == VehicleSetupDetector.Confidence.LOW
            && d.platform != VehicleSetupDetector.Platform.UNKNOWN) {
            detected = ctx.getString(R.string.vehicle_setup_unconfirmed, detected)
        }
        if (d.modelHint != null) {
            detected = "$detected · ${modelName(ctx, d.modelHint)}"
        } else if (d.sharedFirmwareFamily && s.modelId == null) {
            detected = ctx.getString(R.string.vehicle_setup_shared_firmware, detected)
        }
        tvDetected?.text = ctx.getString(R.string.vehicle_setup_detected, detected)

        val configuredMode = s.configuredCameraMode ?: s.appActiveCameraMode
        var mode = modeLabel(ctx, configuredMode)
        val running = s.runningCameraMode
        if (running != null && running != configuredMode) {
            mode = ctx.getString(R.string.vehicle_setup_running, mode, modeLabel(ctx, running))
        }
        val model = s.modelId?.let { modelName(ctx, it) }
            ?: ctx.getString(R.string.vehicle_setup_no_model)
        val storage = ctx.getString(
            when {
                s.storageSplit -> R.string.vehicle_setup_storage_mismatch
                s.alternateStorage -> R.string.vehicle_setup_storage_alternate
                else -> R.string.vehicle_setup_storage_standard
            }
        )
        tvConfigured?.apply {
            text = ctx.getString(R.string.vehicle_setup_configured, "$mode · $model · $storage")
            visibility = View.VISIBLE
        }

        val verdict = VehicleSetupDetector.verdict(
            s.storageSplit, s.serviceReachable, d.recommendedCameraMode, configuredMode, running
        )
        val (statusText, dotRes) = when (verdict) {
            VehicleSetupDetector.Verdict.STORAGE_SPLIT ->
                ctx.getString(R.string.vehicle_setup_storage_split) to R.drawable.status_dot_starting
            VehicleSetupDetector.Verdict.SERVICE_OFFLINE ->
                ctx.getString(R.string.vehicle_setup_service_offline) to R.drawable.status_dot_neutral
            VehicleSetupDetector.Verdict.SUGGEST -> {
                val suggestion =
                    ctx.getString(R.string.vehicle_setup_differs, modeLabel(ctx, d.recommendedCameraMode))
                (if (s.serviceReachable) suggestion
                else suggestion + " · " + ctx.getString(R.string.vehicle_setup_service_offline)) to
                    R.drawable.status_dot_starting
            }
            VehicleSetupDetector.Verdict.MATCH ->
                ctx.getString(R.string.vehicle_setup_match) to R.drawable.status_dot_online
            VehicleSetupDetector.Verdict.NONE -> null to R.drawable.status_dot_neutral
        }
        val storageHint = VehicleSetupDetector.sharkStorageHint(
            d, s.modelId, s.alternateStorage, s.storageSplit, s.serviceReachable
        )
        val setupPending = VehicleSetupJournal.isPending(ctx)
        val shownText = if (!storageHint && !setupPending) statusText else listOfNotNull(
            statusText,
            ctx.getString(R.string.vehicle_setup_shark_storage_hint).takeIf { storageHint },
            ctx.getString(R.string.vehicle_setup_pending_hint).takeIf { setupPending },
        ).joinToString("\n")
        val shownDot = if (storageHint || setupPending) R.drawable.status_dot_starting else dotRes
        tvStatus?.apply {
            text = shownText
            visibility = if (shownText != null) View.VISIBLE else View.GONE
        }
        dot?.setBackgroundResource(shownDot)

        val artId = s.modelId ?: d.modelHint
        ivArt?.apply {
            if (artId != null) {
                setImageResource(VehicleArt.drawableFor(artId))
                visibility = View.VISIBLE
            } else {
                setImageDrawable(null)
                visibility = View.GONE
            }
        }
    }

    private fun showDetails(s: VehicleSetupSnapshot) {
        val activity = fragment.activity ?: return
        if (detailsDialog?.isShowing == true) return
        val report = buildReport(s)
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val text = TextView(activity).apply {
            this.text = report
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(padding, padding / 2, padding, 0)
        }
        detailsDialog = MaterialAlertDialogBuilder(activity, R.style.Theme_Overdrive_M3_Dialog)
            .setTitle(R.string.vehicle_setup_details_title)
            .setView(ScrollView(activity).apply { addView(text) })
            .setPositiveButton(R.string.vehicle_setup_copy) { _, _ -> copy(activity, report) }
            .setNegativeButton(R.string.dialog_close, null)
            .setNeutralButton(R.string.vehicle_setup_run) { _, _ ->
                (activity as? com.overdrive.app.ui.MainActivity)?.startVehicleSetup { refresh() }
            }
            .setOnDismissListener { dismissed -> if (detailsDialog === dismissed) detailsDialog = null }
            .show()
    }

    private fun copy(context: Context, report: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("OverDrive vehicle setup", report))
            Toast.makeText(context, R.string.vehicle_setup_copied, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // Clipboard refused (background restriction); the text stays selectable.
        }
    }

    /** Untranslated on purpose: it is pasted into bug reports. */
    private fun buildReport(s: VehicleSetupSnapshot): String {
        val d = s.detection
        val platform = d.platform.name.lowercase(Locale.ROOT) +
            " (" + d.confidence.name.lowercase(Locale.ROOT) + ")"
        val detectedModel = d.modelHint
            ?: if (d.sharedFirmwareFamily) "(shared firmware family)" else "(not identified)"
        val runningMode = s.runningCameraMode
            ?: if (s.serviceReachable) "(unknown)" else "(service not running)"
        val mapping = when (s.dilink5Mapping) {
            null -> "(not set)"
            "" -> "auto"
            else -> s.dilink5Mapping
        }
        val kwh = s.nominalKwh?.let { String.format(Locale.US, "%.1f", it) } ?: "(not set)"
        val rows = listOf(
            "Head unit" to null,
            "ro.vehicle.type" to (s.vehicleType ?: "(not readable)"),
            "manufacturer" to s.manufacturer,
            "model" to s.model,
            "product" to s.product,
            "device" to s.device,
            "android" to "${s.androidRelease} (API ${s.sdkInt})",
            "libais_test_util.so" to (if (s.di5CameraLibrary) "present" else "absent"),
            "libais_client.so" to (if (s.aisClientVisible) "visible" else "not visible"),
            "bmmcamera.jar" to (if (s.bmmCameraJar) "present" else "absent"),
            "Detection" to null,
            "platform" to platform,
            "camera mode" to (d.recommendedCameraMode ?: "(no suggestion)"),
            "model" to detectedModel,
            "Configured" to null,
            "storage" to s.storageDir + (if (s.alternateStorage) " (alternate)" else ""),
            "alternate marker" to (if (s.altMarkerPresent) "present" else "absent"),
            "camera.cameraMode" to (s.configuredCameraMode ?: "(not set)"),
            "app active mode" to s.appActiveCameraMode,
            "running mode" to runningMode,
            "dilink5CameraMapping" to mapping,
            "vehicle.modelId" to "${s.modelId ?: "(not set)"} (${s.modelSource})",
            "vehicle.nominalKwh" to kwh,
        )
        return rows.joinToString("\n") { (label, value) ->
            if (value == null) "\n$label" else "  " + label.padEnd(REPORT_LABEL_WIDTH) + " " + value
        }.trimStart('\n')
    }

    private fun modeLabel(ctx: Context, mode: String?): String = ctx.getString(
        when (mode) {
            "dilink4" -> R.string.vehicle_setup_mode_dilink4
            "dilink5" -> R.string.vehicle_setup_mode_dilink5
            "camera2" -> R.string.vehicle_setup_mode_camera2
            com.overdrive.app.camera.AvmSurfaceTexturePlatform.MODE -> R.string.camera_mode_option_surfacetexture
            else -> R.string.vehicle_setup_mode_default
        }
    )

    private fun modelName(ctx: Context, id: String): String {
        if (normalize(id) == "seagull") return ctx.getString(R.string.vehicle_model_seagull)
        return modelNames?.get(normalize(id)) ?: id
    }

    private fun loadModelNames(context: Context): Map<String, String> = try {
        val json = context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
        val models = JSONObject(json).optJSONArray("models")
        val names = HashMap<String, String>()
        if (models != null) {
            for (i in 0 until models.length()) {
                val entry = models.optJSONObject(i) ?: continue
                val id = entry.optString("id", "")
                val name = entry.optString("name", "")
                if (id.isNotEmpty() && name.isNotEmpty()) names[normalize(id)] = name
            }
        }
        names
    } catch (_: Throwable) {
        emptyMap()
    }

    private fun normalize(id: String): String =
        id.lowercase(Locale.US).filter(Char::isLetterOrDigit)

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 300L
        const val UNREACHABLE_RETRY_MS = 3_000L
        const val MAX_RETRY_DELAY_MS = 30_000L
        const val MAX_UNREACHABLE_RETRIES = 6
        const val REPORT_LABEL_WIDTH = 20
        const val MANIFEST_ASSET = "web/shared/models/manifest.json"
    }
}
