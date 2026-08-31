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
    CAMERA_DAEMON("Camera Daemon", "byd_cam_daemon", ScratchPaths.path("camera_daemon.disabled")),
    SENTRY_DAEMON("Sentry Daemon", "sentry_daemon", ScratchPaths.path("sentry_daemon.disabled")),
    ACC_SENTRY_DAEMON("ACC Sentry", "acc_sentry_daemon", ScratchPaths.path("acc_sentry_daemon.disabled")),
    SINGBOX_PROXY("Sing-box Proxy", "sing-box", ScratchPaths.path("singbox.disabled")),
    CLOUDFLARED_TUNNEL("Cloudflared Tunnel", "cloudflared", ScratchPaths.path("cloudflared.disabled")),
    ZROK_TUNNEL("Zrok Tunnel", "zrok", ScratchPaths.path("zrok.disabled")),
    TAILSCALE_TUNNEL("Tailscale Tunnel", "tailscaled", ScratchPaths.path("tailscale.disabled")),
    TELEGRAM_DAEMON("Telegram Bot", "telegram_bot_daemon", ScratchPaths.path("telegram_bot_daemon.disabled"))
}

/**
 * "Vehicle ON only" parked-shutdown marker. Planted on the ACC-off edge in onOnly mode
 * to terminate the whole daemon stack and KEEP it down while parked (zero compute).
 *
 * Deliberately DISTINCT from [DaemonType.sentinelPath] (the user `.disabled` sentinel):
 *  - `.disabled` persists until the user manually starts that daemon.
 *  - `.disabled` also means "the USER stopped this" — reusing it would make a parked
 *    car look permanently user-disabled.
 * This marker is NOT in CORE_DAEMONS, so clearStaleSentinels never touches it; it is
 * cleared explicitly on the ACC-on edge (or by a max-age fail-safe). It is honored by
 * BOTH the watchdog shell scripts (exit instead of respawn) AND the app-side
 * health-check / START_STICKY / BootReceiver rebuild paths. Written `chmod 666` so the
 * UID-2000 daemon family and the app UID can both read/write it; contents = epoch millis
 * of park (for the stale-age fail-safe).
 */
object ParkedShutdown {
    @JvmField
    val MARKER_PATH = ScratchPaths.path("overdrive_parked_shutdown")
    /** Max age before the marker is treated as stale and force-cleared (fail-safe so a
     *  marker can never permanently suppress an active session). */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
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
