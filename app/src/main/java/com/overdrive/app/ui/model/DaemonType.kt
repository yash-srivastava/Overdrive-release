package com.overdrive.app.ui.model

import android.content.Context
import com.overdrive.app.R

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
     * Absolute path of this daemon's "user stopped it — keep it down"
     * sentinel file. This is the ONE durable, cross-UID signal that a stop
     * was user-initiated (as opposed to a crash): it lives in
     * /data/local/tmp, is written `chmod 666` so both the app UID and the
     * UID-2000 daemon family can read it, and is honored by BOTH the
     * watchdog shell scripts (which exit instead of respawning) AND the
     * app-side 30s health-check (which skips relaunch). A crash leaves NO
     * sentinel, so the watchdog / health-check still revives a daemon that
     * died on its own — which is the whole point of the auto-restart.
     *
     * Filenames are historical and do NOT all match [processName] (camera
     * uses `camera_daemon.disabled`, not `byd_cam_daemon.disabled`); this
     * map is the single source of truth — never re-derive a sentinel name
     * from the process name.
     */
    val sentinelPath: String
) {
    CAMERA_DAEMON("Camera Daemon", "byd_cam_daemon", com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/camera_daemon.disabled")),
    SENTRY_DAEMON("Sentry Daemon", "sentry_daemon", com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/sentry_daemon.disabled")),
    ACC_SENTRY_DAEMON("ACC Sentry", "acc_sentry_daemon", com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/acc_sentry_daemon.disabled")),
    SINGBOX_PROXY("Sing-box Proxy", "sing-box", com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/singbox.disabled")),
    CLOUDFLARED_TUNNEL("Cloudflared Tunnel", "cloudflared", com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/cloudflared.disabled")),
    ZROK_TUNNEL("Zrok Tunnel", "zrok", com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/zrok.disabled")),
    TAILSCALE_TUNNEL("Tailscale Tunnel", "tailscaled", com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/tailscale.disabled")),
    TELEGRAM_DAEMON("Telegram Bot", "telegram_bot_daemon", com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/telegram_bot_daemon.disabled"))
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
    @JvmField val MARKER_PATH = com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/overdrive_parked_shutdown")

    /**
     * Park-END breadcrumb, written (epoch millis, `chmod 666`) by acc_sentry_daemon at
     * the moment it erases [MARKER_PATH] on a definitive ACC-on. The app process is kept
     * resident across a park and its process-lifetime `bootStarted` guard is still set
     * from the pre-park session; when the judge ends the park with no app-side trigger
     * in flight (driving-telemetry ACC-on on DiLink 5, or the BYD broadcast losing the
     * race to the HAL edge), this is how the next startOnBoot learns that a rebuild is
     * due. Consumed by epoch value, so a stale breadcrumb can never trigger twice; the
     * park reaper removes it when it plants the next marker.
     */
    @JvmField val ENDED_PATH = com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/overdrive_parked_shutdown.ended")
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
