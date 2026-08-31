package com.overdrive.app.ui.model

import com.overdrive.app.util.ScratchPaths
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
        PresetCommand("Proxy Logs", "cat " + ScratchPaths.getDir() + "/singbox.log | tail -50", "Logs"),
        PresetCommand("Tunnel Logs", "cat " + ScratchPaths.getDir() + "/cloudflared.log | tail -50", "Logs"),
        PresetCommand("Camera Logs", "cat " + ScratchPaths.getDir() + "/byd_cam_daemon.log | tail -50", "Logs"),
        PresetCommand("Sentry Logs", "cat " + ScratchPaths.getDir() + "/sentry_daemon.log | tail -50", "Logs"),
        
        // Control commands.
        //
        // These run via executeShellCommand → `sh -c "<body>"`. pkill -f
        // matches the calling shell's full argv, so a body containing
        // a literal pattern like "sing-box" SIGKILLs the wrapping shell
        // and the command returns exit 137 instead of 0. Even single-
        // command bodies suffer this — the target dies but the user
        // sees an "error" in the console output. Use ps+awk+kill
        // everywhere for consistency with the rest of the codebase.
        PresetCommand(
            "Kill Proxy",
            "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F sing-box | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done",
            "Control"
        ),
        PresetCommand(
            "Kill Tunnel",
            "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F cloudflared | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done",
            "Control"
        ),
        PresetCommand(
            "Kill Camera",
            // ps+awk+kill the watchdog FIRST so it can't respawn the daemon,
            // then sleep briefly, then ps+awk+kill the daemon, then clear
            // the lock. Previously used pkill -f for both kills — the FIRST
            // pkill SIGKILLed the wrapping `sh -c` and every command after
            // (sleep, second pkill, killall, rm lock) was silently dropped,
            // so the user-visible behavior was "watchdog dies, daemon
            // survives, lock leaks" — exactly the broken half of the
            // intended kill cascade.
            "MY_PID=\$\$; " +
                "ps -A -o PID,ARGS | grep -F start_cam_daemon | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done; " +
                "rm -f " + ScratchPaths.getDir() + "/start_cam_daemon.sh; " +
                "sleep 1; " +
                "ps -A -o PID,ARGS | grep -F byd_cam_daemon | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done; " +
                "killall -9 byd_cam_daemon 2>/dev/null; " +
                "rm -f " + ScratchPaths.getDir() + "/camera_daemon.lock",
            "Control"
        ),
        PresetCommand(
            "Kill Sentry",
            // grep -v acc_sentry so this preset doesn't collaterally kill
            // AccSentryDaemon (substring match would catch acc_sentry_daemon too).
            "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F sentry_daemon | grep -v grep " +
                "| grep -v acc_sentry | awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done",
            "Control"
        ),
        
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
