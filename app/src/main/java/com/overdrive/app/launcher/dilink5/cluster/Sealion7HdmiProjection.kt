package com.overdrive.app.launcher.dilink5.cluster

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.overdrive.app.camera.dilink5.ClusterTransport
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.config.VehicleModelSelection
import com.overdrive.app.ui.view.AaosMapAreaCheck
import org.json.JSONObject

/** Opt-in full HDMI navigation window for the vehicle and firmware measured on-car. */
object Sealion7HdmiProjection {
    const val CONFIG_KEY = "sealion7FullHdmiProjection"
    internal const val WIDTH = 1920
    internal const val HEIGHT = 720
    private const val SUPPORTED_FIRMWARE = "eng.scm.20260603.212028"

    @JvmStatic
    fun isSupported(context: Context): Boolean = findDisplay(context, requireOptIn = false) != null

    @JvmStatic
    fun isEnabled(context: Context): Boolean = findDisplay(context) != null

    @JvmStatic
    fun isTestedFirmware(): Boolean =
        matchesFirmware(Build.PRODUCT, Build.VERSION.SDK_INT, Build.VERSION.INCREMENTAL)

    /** Returns the only eligible display, with the opt-in checked against the durable config. */
    fun findDisplay(context: Context): Display? = findDisplay(context, requireOptIn = true)

    private fun findDisplay(context: Context, requireOptIn: Boolean): Display? = try {
        if (isTestedFirmware()) {
            val config = UnifiedConfigManager.readDurableConfigStrict()
            val vehicle = config.optJSONObject("vehicle")
            val optedIn = isOptedIn(config.optJSONObject("projection"))
            if (isUserSelected(vehicle?.opt("modelId"), vehicle?.opt("modelSource")) &&
                (!requireOptIn || optedIn)) {
                context.getSystemService(DisplayManager::class.java)?.displays
                    ?.singleOrNull { AaosMapAreaCheck.isTarget(context, it) }
            } else null
        } else null
    } catch (_: Exception) {
        null
    }

    internal fun matchesFirmware(product: String?, sdk: Int, incremental: String?): Boolean =
        ClusterTransport.isAaosHdmiFirmware(product, sdk) && incremental == SUPPORTED_FIRMWARE

    internal fun isUserSelected(modelId: Any?, modelSource: Any?): Boolean =
        modelId is String && modelSource is String &&
            VehicleModelSelection.SOURCE_USER == VehicleModelSelection.normalizeSource(modelSource, modelId) &&
            "sealion7".equals(VehicleModelSelection.resolvedModelId(modelId, modelSource), ignoreCase = true)

    @JvmStatic
    fun isOptedIn(projection: JSONObject?): Boolean = projection?.opt(CONFIG_KEY) == true

    internal fun matchesGeometry(
        left: Int, top: Int, width: Int, height: Int, bufferWidth: Int, bufferHeight: Int,
    ): Boolean = left == 0 && top == 0 && width == WIDTH && height == HEIGHT &&
        bufferWidth == WIDTH && bufferHeight == HEIGHT

    fun geometry(appDisplay: DiLink5HostedProjectionBridge.AppDisplayInfo): HostedProjectionGeometry? {
        if (appDisplay.displayId <= 0 || appDisplay.width != WIDTH || appDisplay.height != HEIGHT ||
            appDisplay.densityDpi !in 1..10_000) return null
        return HostedProjectionGeometry(
            WIDTH, HEIGHT, appDisplay.densityDpi, HostedProjectionGeometry.Bounds(0, 0, WIDTH, HEIGHT),
            WIDTH, HEIGHT,
        )
    }
}
