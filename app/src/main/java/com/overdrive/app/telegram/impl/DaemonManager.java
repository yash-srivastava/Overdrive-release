package com.overdrive.app.telegram.impl;

import com.overdrive.app.telegram.IDaemonManager;
import com.overdrive.app.telegram.model.DaemonInfo;
import com.overdrive.app.telegram.model.DaemonStatus;
import com.overdrive.app.util.DaemonHttpClient;
import com.overdrive.app.util.ScratchPaths;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
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
        //
        // ps+awk+kill, NOT pkill -f. execShell wraps each call in
        // `sh -c "<cmd>"`, and pkill -f matches the calling shell's
        // argv on any literal pattern in the command — SIGKILLing the
        // wrapper. With ps+awk+kill we filter by PID and exclude
        // $MY_PID so the wrapper survives.
        if ("camera".equals(name.toLowerCase())) {
            if (!prepareCameraRestart()) {
                return false;
            }
            boolean stopped = execShell(
                "MY_PID=$$; ps -A -o PID,ARGS | grep -F start_cam_daemon | grep -v grep "
                + "| awk '{print $1}' | while read pid; do "
                + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done"
            ) != null;
            stopped &= execShell(
                    "rm -f " + ScratchPaths.getDir() + "/start_cam_daemon.sh 2>/dev/null") != null;
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            stopped &= execShell(
                "MY_PID=$$; ps -A -o PID,ARGS | grep -F byd_cam_daemon | grep -v grep "
                + "| awk '{print $1}' | while read pid; do "
                + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done"
            ) != null;
            stopped &= execShell("killall -9 byd_cam_daemon 2>/dev/null") != null;
            stopped &= execShell(
                    "rm -f " + ScratchPaths.getDir() + "/camera_daemon.lock 2>/dev/null") != null;
            if (!stopped) {
                abortCameraRestart();
            }
            return stopped;
        }

        // Same ps+awk+kill pattern — entry.className is interpolated
        // raw so the calling shell's argv contains it; pkill -f would
        // self-match.
        execShell(
            "MY_PID=$$; ps -A -o PID,ARGS | grep -F " + entry.className + " | grep -v grep "
            + "| awk '{print $1}' | while read pid; do "
            + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done"
        );
        return true;
    }

    /**
     * Camera stops are SIGKILL-based, so the daemon must explicitly confirm
     * that its active trip journal and camera pipeline are durable first.
     */
    private boolean prepareCameraRestart() {
        HttpURLConnection connection = null;
        try {
            connection = DaemonHttpClient.open(
                    "/api/surveillance/prepare-restart", "POST", 3000, 10000);
            connection.setDoOutput(true);
            try (OutputStream body = connection.getOutputStream()) {
                body.write(new byte[0]);
            }
            int code = connection.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void abortCameraRestart() {
        HttpURLConnection connection = null;
        try {
            connection = DaemonHttpClient.open(
                    "/api/surveillance/abort-restart", "POST", 2000, 2000);
            connection.setDoOutput(true);
            try (OutputStream body = connection.getOutputStream()) {
                body.write(new byte[0]);
            }
            connection.getResponseCode();
        } catch (Exception ignored) {
            // Best effort. The stop method already returns false to its caller.
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
