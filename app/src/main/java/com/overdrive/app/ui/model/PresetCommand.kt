package com.overdrive.app.ui.model

/**
 * A preset ADB command for quick execution.
 */
data class PresetCommand(
    val label: String,
    val command: String,
    val category: String
)

/**
 * Convenience constant for all preset commands.
 */
val PRESET_COMMANDS = PresetCommands.ALL

/**
 * List of preset ADB commands organized by category.
 */
object PresetCommands {
    val ALL = listOf(
        // Status commands
        PresetCommand("Process Status", "ps -ef | grep -E 'daemon|proxy|sing-box|cloudflared'", "Status"),
        PresetCommand("Port Status", "netstat -tlnp | grep -E '8080|8119|8554'", "Status"),
        
        // Log commands
        PresetCommand("Proxy Logs", "cat /data/local/tmp/singbox.log | tail -50", "Logs"),
        PresetCommand("Tunnel Logs", "cat /data/local/tmp/cloudflared.log | tail -50", "Logs"),
        PresetCommand("Camera Logs", "cat /data/local/tmp/byd_cam_daemon.log | tail -50", "Logs"),
        PresetCommand("Sentry Logs", "cat /data/local/tmp/sentry_daemon.log | tail -50", "Logs"),
        
        // Control commands
        PresetCommand("Kill Proxy", "pkill -9 -f sing-box", "Control"),
        PresetCommand("Kill Tunnel", "pkill -9 -f cloudflared", "Control"),
        PresetCommand(
            "Kill Camera",
            // Kill the watchdog script FIRST so it can't respawn the daemon,
            // then sleep briefly, then kill the daemon and clear its lock file.
            "pkill -9 -f start_cam_daemon; " +
                "rm -f /data/local/tmp/start_cam_daemon.sh; " +
                "sleep 1; " +
                "pkill -9 -f byd_cam_daemon; " +
                "killall -9 byd_cam_daemon 2>/dev/null; " +
                "rm -f /data/local/tmp/camera_daemon.lock",
            "Control"
        ),
        PresetCommand("Kill Sentry", "pkill -9 -f sentry_daemon", "Control"),
        
        // System commands
        PresetCommand("Storage", "df -h /data", "System"),
        PresetCommand("Battery", "dumpsys battery", "System"),
        PresetCommand("Network", "ip addr", "System"),
        PresetCommand("Ping Test", "ping -c 3 8.8.8.8", "System"),
        PresetCommand("Proxy Settings", "settings get global http_proxy", "System"),
        PresetCommand("Reset Proxy", "settings put global http_proxy :0", "Control"),
        PresetCommand("ACC Props", "getprop | grep -i acc", "System")
    )
    
    val CATEGORIES = ALL.map { it.category }.distinct()
}
