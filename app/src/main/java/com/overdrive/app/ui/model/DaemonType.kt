package com.overdrive.app.ui.model

import android.content.Context
import com.overdrive.app.R
import com.overdrive.app.util.ScratchPaths

/**
 * Types of background daemons managed by the app.
 * Note: Location Sidecar is not included here as it auto-starts silently
 * and is managed by SentryDaemon, not shown in the UI.
 *
 * `displayName` is the stable English identifier — use it for log file
 * paths, telemetry, and other non-user-visible surfaces. UI surfaces
 * MUST use [localizedName] so the row label tracks the picked locale.
 */
enum class DaemonType(
    val displayName: String,
    val processName: String,
    /**
     * Filename (not absolute path) of this daemon's "user stopped it — keep
     * it down" sentinel. Resolved via [ScratchPaths.path] so Sealion keeps
     * `/data/local/tmp` while Shark uses the app-files scratch tree.
     *
     * Filenames are historical and do NOT all match [processName] (camera
     * uses `camera_daemon.disabled`, not `byd_cam_daemon.disabled`); this
     * map is the single source of truth — never re-derive a sentinel name
     * from the process name.
     */
    private val sentinelFileName: String
) {
    CAMERA_DAEMON("Camera Daemon", "byd_cam_daemon", "camera_daemon.disabled"),
    SENTRY_DAEMON("Sentry Daemon", "sentry_daemon", "sentry_daemon.disabled"),
    ACC_SENTRY_DAEMON("ACC Sentry", "acc_sentry_daemon", "acc_sentry_daemon.disabled"),
    SINGBOX_PROXY("Sing-box Proxy", "sing-box", "singbox.disabled"),
    CLOUDFLARED_TUNNEL("Cloudflared Tunnel", "cloudflared", "cloudflared.disabled"),
    ZROK_TUNNEL("Zrok Tunnel", "zrok", "zrok.disabled"),
    TAILSCALE_TUNNEL("Tailscale Tunnel", "tailscaled", "tailscale.disabled"),
    TELEGRAM_DAEMON("Telegram Bot", "telegram_bot_daemon", "telegram_bot_daemon.disabled");

    /** Absolute sentinel path under the resolved scratch directory. */
    val sentinelPath: String
        get() = ScratchPaths.path(sentinelFileName)
}

/**
 * "Vehicle ON only" parked-shutdown marker. Planted on the ACC-off edge in onOnly mode
 * to terminate the whole daemon stack and KEEP it down while parked (zero compute).
 *
 * Deliberately DISTINCT from [DaemonType.sentinelPath] (the user `.disabled` sentinel):
 *  - `.disabled` persists until the user manually starts that daemon.
 *  - `.disabled` also means "the USER stopped this" — reusing it would make a parked
 *    car look permanently user-disabled.
 * This marker is NOT in CORE_DAEMONS, so clearStaleSentinels never touches it. It is
 * AUTHORITATIVE: while it exists, no automatic start may run, and no config read,
 * Activity start, health-check tick, accessibility reconnect or elapsed time may erase
 * it. It ends only when
 *  - acc_sentry_daemon — the parked ACC judge, which the park reaper deliberately spares
 *    — sees a definitive ACC-on and erases it,
 *  - BootReceiver completes a VERIFIED erase on a direction-unambiguous recovery trigger
 *    (com.byd.action.ACC_ON / IGN_ON, or head-unit boot), or
 *  - the user presses Start explicitly (DaemonsViewModel.clearStartBlockers).
 * It is honored by the camera/telegram/zrok watchdog shell scripts (exit instead of
 * respawn — the acc_sentry watchdog only slows its respawn, since the judge must stay
 * alive), by every app-side start chokepoint (startOnBoot, ifNotUserStopped,
 * relaunchDaemon, the keepalive START_STICKY gate, the revival alarm) and by the plain
 * SentryDaemon itself. Written `chmod 666` so the UID-2000 daemon family and the app UID
 * can both read it; contents = epoch millis of park (diagnostic only).
 */
object ParkedShutdown {
    /** Absolute marker path under the resolved scratch directory. */
    @JvmStatic
    fun markerPath(): String = ScratchPaths.path("overdrive_parked_shutdown")

    /**
     * Compatibility field for latest-main callers not yet migrated to [markerPath].
     * ScratchPaths is initialized before daemon startup and daemon entry points sync
     * the inherited scratch environment before loading parked-state logic.
     */
    @JvmField
    val MARKER_PATH: String = markerPath()

    /**
     * Park-END breadcrumb, written (epoch millis, `chmod 666`) by acc_sentry_daemon at
     * the moment it erases [markerPath] on a definitive ACC-on. The app process is kept
     * resident across a park and its process-lifetime `bootStarted` guard is still set
     * from the pre-park session; when the judge ends the park with no app-side trigger
     * in flight (driving-telemetry ACC-on on DiLink 5, or the BYD broadcast losing the
     * race to the HAL edge), this is how the next startOnBoot learns that a rebuild is
     * due. Consumed by epoch value, so a stale breadcrumb can never trigger twice; the
     * park reaper removes it when it plants the next marker.
     */
    @JvmStatic
    fun endedPath(): String = ScratchPaths.path("overdrive_parked_shutdown.ended")

    /** Compatibility field for latest-main callers not yet migrated to [endedPath]. */
    @JvmField
    val ENDED_PATH: String = endedPath()
}

fun DaemonType.localizedName(context: Context): String = context.getString(when (this) {
    DaemonType.CAMERA_DAEMON      -> R.string.daemon_name_camera
    DaemonType.SENTRY_DAEMON      -> R.string.daemon_name_surveillance
    DaemonType.ACC_SENTRY_DAEMON  -> R.string.daemon_name_acc_surveillance
    DaemonType.SINGBOX_PROXY      -> R.string.daemon_name_singbox
    DaemonType.CLOUDFLARED_TUNNEL -> R.string.daemon_name_cloudflared
    DaemonType.ZROK_TUNNEL        -> R.string.daemon_name_zrok
    DaemonType.TAILSCALE_TUNNEL   -> R.string.daemon_name_tailscale
    DaemonType.TELEGRAM_DAEMON    -> R.string.daemon_name_telegram
})
