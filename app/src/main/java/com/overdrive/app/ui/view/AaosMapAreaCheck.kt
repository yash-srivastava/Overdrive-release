package com.overdrive.app.ui.view

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.overdrive.app.R
import com.overdrive.app.camera.dilink5.ClusterTransport
import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONObject
import java.util.concurrent.Executors

/** A parked visual check of the selected factory-map rectangle on the Android 11 HDMI cluster. */
class AaosMapAreaCheck(private val button: View) : DisplayManager.DisplayListener {
    private val context = button.context
    private val supportedVehicle = isCapturedVehicle(context)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var selectedGear: AaosSelectedGearReader? = null
    private var resumed = false
    private var enabled = true
    private var generation = 0L
    private var lastParkSample = 0L
    private var lastMapRequest = 0L
    private var mapDeadline = 0L
    private var ownerConfirmed = false
    private var target: Display? = null
    private var layout = ""
    private var checkingArea: MapArea? = null
    private var window: WindowManager? = null
    private var marker: View? = null
    private var dialog: AlertDialog? = null

    init {
        button.setOnClickListener { selectMap() }
    }

    fun resume() {
        if (resumed) return
        resumed = true
        if (!supportedVehicle) return
        displays?.registerDisplayListener(this, main)
        refresh()
    }

    fun pause() {
        if (!resumed) return
        resumed = false
        if (supportedVehicle) displays?.unregisterDisplayListener(this)
        cancel()
    }

    fun close() {
        pause()
        button.setOnClickListener(null)
        if (!io.isShutdown) {
            io.execute { selectedGear?.close() }
            io.shutdown()
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        refresh()
    }

    override fun onDisplayAdded(displayId: Int) = refresh()
    override fun onDisplayChanged(displayId: Int) = refresh()
    override fun onDisplayRemoved(displayId: Int) {
        if (target?.displayId == displayId) cancel()
        refresh()
    }

    private fun currentTarget(): Display? = if (enabled) {
        displays?.displays?.singleOrNull { isTarget(context, it) }
    } else null

    private fun refresh() {
        val selected = currentTarget()
        if (target != null && target?.displayId != selected?.displayId) cancel()
        button.visibility = if (resumed && selected != null) View.VISIBLE else View.GONE
    }

    private fun selectMap() {
        cancel()
        val selected = currentTarget() ?: return
        if (!Settings.canDrawOverlays(context)) {
            message(R.string.aaos_map_area_permission)
            return
        }
        target = selected
        val stored = preferences(context).all
        val area = selectedArea(stored, scope())
        val key = area.proofKey(scope())
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.aaos_map_area_button)
            .setMessage(context.getString(
                R.string.aaos_map_area_choose,
                context.getString(if (stored["$key.full"] == true) R.string.aaos_map_area_checked else R.string.aaos_map_area_unchecked),
                context.getString(if (stored["$key.small"] == true) R.string.aaos_map_area_checked else R.string.aaos_map_area_unchecked),
                context.getString(area.label)
            ))
            .setPositiveButton(R.string.aaos_map_area_full) { _, _ -> begin("full", area) }
            .setNegativeButton(R.string.aaos_map_area_small) { _, _ -> begin("small", area) }
            .setNeutralButton(R.string.aaos_map_area_change) { _, _ -> chooseArea(area) }
            .show()
    }

    private fun chooseArea(current: MapArea) {
        val areas = MapArea.entries
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.aaos_map_area_change)
            .setSingleChoiceItems(areas.map { context.getString(it.label) }.toTypedArray(), current.ordinal) { choice, which ->
                preferences(context).edit().putString("${scope()}.selected", areas[which].id).apply()
                choice.dismiss()
                selectMap()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun begin(chosen: String, area: MapArea) {
        val selected = target ?: return
        layout = chosen
        checkingArea = area
        // A failed or interrupted re-check withdraws any prior approval for this layout.
        preferences(context).edit().remove("${area.proofKey(scope())}.$layout").apply()
        val token = ++generation
        fun poll(checkMap: Boolean) {
            if (!resumed || generation != token) return
            val beforeMarker = marker == null
            if (checkMap) lastMapRequest = SystemClock.elapsedRealtime()
            io.execute {
                val state = readSample(checkMap, beforeMarker)
                main.post {
                    if (!resumed || generation != token) return@post
                    if (currentTarget()?.displayId != selected.displayId || !Settings.canDrawOverlays(context)) {
                        stopWithMessage(R.string.aaos_map_area_unavailable)
                        return@post
                    }
                    val now = SystemClock.elapsedRealtime()
                    val decision = parkDecision(state.gear, now, lastParkSample, ownerConfirmed, marker != null)
                    if (decision == ParkDecision.STOP) {
                        stopWithMessage(R.string.aaos_map_area_park)
                        return@post
                    }
                    if (checkMap && state.mapOpen != true && (state.mapOpen == false || marker == null)) {
                        stopWithMessage(R.string.aaos_map_area_map_required)
                        return@post
                    }
                    if (decision == ParkDecision.ASK_OWNER) {
                        dialog = MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.aaos_map_area_manual_title)
                            .setMessage(R.string.aaos_map_area_manual_message)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.aaos_map_area_manual_confirm) { _, _ ->
                                if (resumed && generation == token) {
                                    ownerConfirmed = true
                                    poll(true) // Recheck both gear sources and factory map after the user answers.
                                }
                            }.show()
                        return@post
                    }
                    if (decision == ParkDecision.AUTOMATIC) {
                        lastParkSample = requireNotNull(state.gear)
                        main.removeCallbacks(parkExpired)
                        main.postDelayed(parkExpired, lastParkSample + MAX_GEAR_AGE_MS - now)
                    }
                    if (checkMap && state.mapOpen == true) {
                        main.removeCallbacks(mapExpired)
                        mapDeadline = now + MAP_RECHECK_DEADLINE_MS
                        main.postDelayed(mapExpired, MAP_RECHECK_DEADLINE_MS)
                    }
                    if (marker == null && !attach(selected, area.bounds())) {
                        stopWithMessage(R.string.aaos_map_area_unavailable)
                        return@post
                    }
                    // Gear-only requests stay cheap between compositor observations.
                    main.postDelayed({ poll(SystemClock.elapsedRealtime() - lastMapRequest >= MAP_RECHECK_MS) }, 200)
                }
            }
        }
        poll(true)
    }

    private data class Sample(val gear: Long?, val mapOpen: Boolean?)

    private fun readSample(checkMap: Boolean, beforeMarker: Boolean): Sample {
        val data = readDaemonSample(checkMap, beforeMarker)
        val reportedAt = data?.optLong("observedAtElapsedMs", 0L) ?: 0L
        val daemonGear = if (data?.optBoolean("gearKnown", false) == true && reportedAt > 0L) {
            if (data.optBoolean("parked", false)) reportedAt else 0L
        } else null
        if (daemonGear == 0L) return Sample(0L, data?.opt("factoryMapOpen") as? Boolean)
        val standard = try {
            val reader = selectedGear ?: AaosSelectedGearReader(context.applicationContext).also { selectedGear = it }
            reader.readParkSample()
        } catch (_: LinkageError) {
            null
        }
        // Either source can veto Park even when the other source says P.
        return Sample(combineGear(standard, daemonGear), data?.opt("factoryMapOpen") as? Boolean)
    }

    private val parkExpired = Runnable { stopWithMessage(R.string.aaos_map_area_park) }
    private val mapExpired = Runnable { stopWithMessage(R.string.aaos_map_area_map_required) }
    private val finishCheck = Runnable {
        val now = SystemClock.elapsedRealtime()
        if (now >= mapDeadline) {
            stopWithMessage(R.string.aaos_map_area_map_required)
            return@Runnable
        }
        if ((lastParkSample > 0L || !ownerConfirmed) && !parkSampleIsFresh(lastParkSample, now)) {
            stopWithMessage(R.string.aaos_map_area_park)
            return@Runnable
        }
        val shown = marker
        val position = IntArray(2)
        shown?.getLocationOnScreen(position)
        val checkedArea = checkingArea ?: return@Runnable
        val area = checkedArea.bounds()
        if (shown == null || !shown.isAttachedToWindow || !shown.isLaidOut ||
            shown.width != area.width() || shown.height != area.height() ||
            position[0] != area.left || position[1] != area.top ||
            shown.display?.displayId != target?.displayId) {
            stopWithMessage(R.string.aaos_map_area_unavailable)
            return@Runnable
        }
        val checkedDisplay = target ?: return@Runnable
        val checkedLayout = layout
        cancel()
        if (!resumed || currentTarget()?.displayId != checkedDisplay.displayId) return@Runnable
        target = checkedDisplay
        val checks = booleanArrayOf(false, false)
        val checklist = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.aaos_map_area_result)
            .setMultiChoiceItems(arrayOf(
                context.getString(R.string.aaos_map_area_edges),
                context.getString(R.string.aaos_map_area_gauges)
            ), checks) { d, which, checked ->
                checks[which] = checked
                (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checks.all { it }
            }
            .setNegativeButton(R.string.aaos_map_area_unconfirmed, null)
            .setPositiveButton(R.string.aaos_map_area_confirm) { _, _ ->
                if (checks.all { it } && currentTarget()?.displayId == checkedDisplay.displayId &&
                    selectedArea(preferences(context).all, scope()) == checkedArea) {
                    preferences(context).edit().putBoolean("${checkedArea.proofKey(scope())}.$checkedLayout", true).apply()
                }
            }.show()
        checklist.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        dialog = checklist
    }

    private fun attach(display: Display, area: Rect): Boolean {
        return try {
            val wc = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val wm = wc.getSystemService(WindowManager::class.java) ?: return false
            val view = Marker(wc)
            val params = windowParams(area).apply { title = "OverDrive map area check" }
            wm.addView(view, params)
            window = wm
            marker = view
            main.postDelayed(finishCheck, 5_000L)
            true
        } catch (_: RuntimeException) { false }
    }

    private fun stopWithMessage(res: Int) {
        cancel()
        if (resumed) message(res)
    }

    private fun message(res: Int) {
        dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.aaos_map_area_button)
            .setMessage(res).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun cancel() {
        generation++
        lastParkSample = 0L
        lastMapRequest = 0L
        mapDeadline = 0L
        ownerConfirmed = false
        main.removeCallbacksAndMessages(null)
        marker?.let { current ->
            current.visibility = View.GONE
            try { window?.removeViewImmediate(current) } catch (_: IllegalArgumentException) { }
        }
        marker = null
        window = null
        target = null
        checkingArea = null
        dialog?.dismiss()
        dialog = null
    }

    private class Marker(context: Context) : View(context) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            ink.style = Paint.Style.STROKE
            for ((stroke, color) in listOf(6f to Color.BLACK, 3f to Color.YELLOW)) {
                ink.color = color
                ink.strokeWidth = stroke
                canvas.drawRect(4f, 4f, width - 4f, height - 4f, ink)
            }
            ink.style = Paint.Style.FILL
            ink.textSize = 22f
            ink.textAlign = Paint.Align.CENTER
            ink.setShadowLayer(2f, 1f, 1f, Color.BLACK)
            canvas.drawText(context.getString(R.string.aaos_map_area_label), width / 2f, height / 2f, ink)
        }
    }

    companion object {
        private const val MAX_GEAR_AGE_MS = 1_000L
        private const val MAP_RECHECK_MS = 650L
        private const val MAP_RECHECK_DEADLINE_MS = 1_800L
        private fun preferences(context: Context) =
            context.getSharedPreferences("aaos_map_marker", Context.MODE_PRIVATE)
        private fun scope() = "v2.${Build.FINGERPRINT}.local:3"

        internal fun selectedArea(stored: Map<String, *>, scope: String): MapArea =
            MapArea.entries.firstOrNull { it.id == stored["$scope.selected"] } ?: MapArea.CENTER

        internal fun approvedArea(stored: Map<String, *>, scope: String): MapArea? {
            val area = selectedArea(stored, scope)
            val key = area.proofKey(scope)
            return area.takeIf { stored["$key.full"] == true && stored["$key.small"] == true }
        }

        @JvmStatic fun selectedBounds(context: Context): Rect =
            selectedArea(preferences(context).all, scope()).bounds()

        @JvmStatic fun windowParams(area: Rect): WindowManager.LayoutParams {
            require(MapArea.entries.any { it.bounds() == area })
            return WindowManager.LayoutParams(
                area.width(), area.height(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                @Suppress("RtlHardcoded") // Fixed physical HDMI pixels, independent of the head unit's locale.
                gravity = Gravity.TOP or Gravity.LEFT
                x = area.left
                y = area.top
            }
        }

        @JvmStatic fun isTarget(context: Context, display: Display): Boolean {
            if (!isCapturedVehicle(context) || !display.isValid || display.state != Display.STATE_ON || display.displayId <= 0 ||
                display.flags and Display.FLAG_PRESENTATION == 0 || display.flags and Display.FLAG_PRIVATE != 0
            ) return false
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            val (type, uniqueId) = panelIdentity(display)
            return matchesMapPanel(type, uniqueId, size.x, size.y)
        }

        internal fun matchesMapPanel(type: Int?, uniqueId: String?, width: Int, height: Int) =
            width == 1920 && height == 720 && type == DISPLAY_TYPE_EXTERNAL && uniqueId == "local:3"

        /** Hidden Display.TYPE_EXTERNAL. */
        private const val DISPLAY_TYPE_EXTERNAL = 2

        // R's Display#toString() omits type and uniqueId for every uid except 1000, and Display#getUniqueId()
        // is blocked for apps; getDisplayInfo() and these DisplayInfo fields stay open to targetSdk 25.
        private fun panelIdentity(display: Display): Pair<Int?, String?> = try {
            val infoClass = Class.forName("android.view.DisplayInfo")
            val info = infoClass.getConstructor().newInstance()
            if (Display::class.java.getMethod("getDisplayInfo", infoClass).invoke(display, info) != true) null to null
            else infoClass.getField("type").getInt(info) to infoClass.getField("uniqueId").get(info) as? String
        } catch (_: Throwable) {
            null to null
        }

        private fun isCapturedVehicle(context: Context) =
            ClusterTransport.isAaosHdmiFirmware(Build.PRODUCT, Build.VERSION.SDK_INT) &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

        @JvmStatic fun confirmedBounds(context: Context, display: Display): Rect? {
            if (!isTarget(context, display) || !Settings.canDrawOverlays(context)) return null
            return approvedArea(preferences(context).all, scope())?.bounds()
        }

        @JvmStatic fun confirmedFor(context: Context, display: Display): Boolean =
            confirmedBounds(context, display) != null

        internal fun parkSampleIsFresh(observed: Long, now: Long) =
            observed > 0 && now >= observed && now - observed < MAX_GEAR_AGE_MS

        internal fun combineGear(standard: Long?, daemon: Long?): Long? =
            when {
                standard == 0L || daemon == 0L -> 0L
                standard == null -> daemon
                daemon == null -> standard
                else -> maxOf(standard, daemon)
            }

        internal fun parkDecision(gear: Long?, now: Long, priorPark: Long, ownerConfirmed: Boolean, visible: Boolean): ParkDecision =
            when {
                gear == 0L -> ParkDecision.STOP
                gear != null && parkSampleIsFresh(gear, now) -> ParkDecision.AUTOMATIC
                priorPark > 0L || gear != null -> ParkDecision.STOP
                ownerConfirmed -> ParkDecision.MANUAL
                !visible -> ParkDecision.ASK_OWNER
                else -> ParkDecision.STOP
            }

        private fun readDaemonSample(checkMap: Boolean, beforeMarker: Boolean): JSONObject? = try {
            val path = "/api/vehicle/cluster-park-sample" + if (checkMap) "?map=1" else ""
            val readTimeout = when {
                !checkMap -> 350
                beforeMarker -> 3_200 // The daemon bounds the initial compositor observation at 2.5 s.
                else -> 650
            }
            val connection = DaemonHttpClient.open(path, "GET", 350, readTimeout)
            try {
                if (connection.responseCode == 200) connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                else null
            } finally { connection.disconnect() }
        } catch (_: Exception) { null }
    }

    internal enum class MapArea(val id: String, val label: Int, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        CENTER("640x280.center", R.string.aaos_map_area_center, 640, 220, 1280, 500),
        RIGHT("400x256.right.1440.232", R.string.aaos_map_area_right, 1440, 232, 1840, 488);

        fun bounds() = Rect(left, top, right, bottom)
        fun proofKey(scope: String) = "$scope.$id"
    }

    internal enum class ParkDecision { AUTOMATIC, ASK_OWNER, MANUAL, STOP }
}
