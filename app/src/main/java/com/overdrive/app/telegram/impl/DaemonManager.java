package com.overdrive.app.telegram.impl;

import com.overdrive.app.telegram.IDaemonManager;
import com.overdrive.app.telegram.model.DaemonInfo;
import com.overdrive.app.telegram.model.DaemonStatus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Daemon manager implementation.
 * Manages daemon processes via shell commands.
 */
public class DaemonManager implements IDaemonManager {
    
    // Daemon registry: name -> class name
    private static final Map<String, DaemonEntry> DAEMONS = new HashMap<>();
    
    static {
        DAEMONS.put("camera", new DaemonEntry("CameraDaemon", "Camera"));
        DAEMONS.put("surveillance", new DaemonEntry("SurveillanceDaemon", "Surveillance"));
        DAEMONS.put("acc", new DaemonEntry("AccSentryDaemon", "ACC Sentry"));
        DAEMONS.put("telegram", new DaemonEntry("TelegramBotDaemon", "Telegram Bot"));
    }
    
    private final String packageName;
    private final String apkPath;
    
    public DaemonManager(String packageName, String apkPath) {
        this.packageName = packageName;
        this.apkPath = apkPath;
    }
    
    @Override
    public List<DaemonInfo> listDaemons() {
        List<DaemonInfo> result = new ArrayList<>();
        for (Map.Entry<String, DaemonEntry> entry : DAEMONS.entrySet()) {
            DaemonStatus status = getDaemonStatus(entry.getKey());
            result.add(new DaemonInfo(entry.getKey(), entry.getValue().displayName, status));
        }
        return result;
    }
    
    @Override
    public boolean startDaemon(String name) {
        DaemonEntry entry = DAEMONS.get(name.toLowerCase());
        if (entry == null) return false;
        
        String className = packageName + ".daemon." + entry.className;
        String cmd = String.format(
                "CLASSPATH=%s app_process / %s &",
                apkPath, className
        );
        
        return execShell(cmd) != null;
    }
    
    @Override
    public boolean stopDaemon(String name) {
        DaemonEntry entry = DAEMONS.get(name.toLowerCase());
        if (entry == null) return false;
        
        // For camera daemon, kill the watchdog script FIRST so it can't
        // respawn the daemon, then sleep briefly, then kill the daemon and
        // clean up the singleton lock file.
        if ("camera".equals(name.toLowerCase())) {
            execShell("pkill -9 -f start_cam_daemon 2>/dev/null");
            execShell("rm -f /data/local/tmp/start_cam_daemon.sh 2>/dev/null");
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            execShell("pkill -9 -f byd_cam_daemon 2>/dev/null");
            execShell("killall -9 byd_cam_daemon 2>/dev/null");
            execShell("rm -f /data/local/tmp/camera_daemon.lock 2>/dev/null");
            return true;
        }
        
        String cmd = "pkill -f " + entry.className;
        execShell(cmd);
        return true;
    }
    
    @Override
    public DaemonStatus getDaemonStatus(String name) {
        DaemonEntry entry = DAEMONS.get(name.toLowerCase());
        if (entry == null) return DaemonStatus.UNKNOWN;
        
        String output = execShell("ps -A | grep " + entry.className);
        if (output != null && !output.isEmpty()) {
            return DaemonStatus.RUNNING;
        }
        return DaemonStatus.STOPPED;
    }
    
    @Override
    public boolean hasDaemon(String name) {
        return DAEMONS.containsKey(name.toLowerCase());
    }
    
    private String execShell(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            process.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            return output.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }
    
    private static class DaemonEntry {
        final String className;
        final String displayName;
        
        DaemonEntry(String className, String displayName) {
            this.className = className;
            this.displayName = displayName;
        }
    }
}
