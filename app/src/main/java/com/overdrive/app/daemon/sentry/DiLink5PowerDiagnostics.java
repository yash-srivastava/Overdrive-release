package com.overdrive.app.daemon.sentry;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.provider.Settings;

import com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend;
import com.overdrive.app.camera.dilink5.TsAvmCoordinator;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.WakeLockManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Diagnostic and Keep-Alive monitor for BYD DiLink 5.0 (Snapdragon SA8155P / Sealion 7).
 * Periodically records power state, Wi-Fi status, hardware camera streaming, and AVM responsiveness
 * to a persistent log file on storage (/sdcard/Overdrive/sentry_power_test.log).
 */
public class DiLink5PowerDiagnostics {

    private static final String TAG = "DiLink5PowerDiag";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String LOG_PATH = "/sdcard/Overdrive/sentry_power_test.log";

    private static final AtomicBoolean sRunning = new AtomicBoolean(false);
    private static Thread sDiagThread = null;
    private static WakeLockManager sWakeLockManager = null;

    public static synchronized void start(Context context) {
        if (sRunning.get()) return;

        if (context != null) {
            try {
                sWakeLockManager = new WakeLockManager(context);
                sWakeLockManager.acquireAll();
                logger.info("DiLink 5 WakeLock & WifiLock acquired successfully.");
            } catch (Throwable t) {
                logger.warn("Failed to acquire WakeLocks via WakeLockManager: " + t.getMessage());
            }

            try {
                // Apply Wi-Fi Sleep Policy: NEVER sleep (2)
                Settings.Global.putInt(context.getContentResolver(), Settings.Global.WIFI_SLEEP_POLICY, Settings.Global.WIFI_SLEEP_POLICY_NEVER);
                logger.info("Set Settings.Global.WIFI_SLEEP_POLICY = NEVER (2)");
            } catch (Throwable t) {
                logger.warn("Could not write WIFI_SLEEP_POLICY: " + t.getMessage());
            }
        }

        // Whitelist app in Doze mode and enforce ADB TCP/USB persistence via shell commands
        try {
            Runtime.getRuntime().exec(new String[]{"dumpsys", "deviceidle", "whitelist", "+com.overdrive.app"});
            Runtime.getRuntime().exec(new String[]{"setprop", "persist.adb.tcp.port", "5555"});
            Runtime.getRuntime().exec(new String[]{"setprop", "service.adb.tcp.port", "5555"});
            Runtime.getRuntime().exec(new String[]{"setprop", "persist.sys.usb.config", "mtp,adb"});
            Runtime.getRuntime().exec(new String[]{"settings", "put", "global", "adb_enabled", "1"});
            Runtime.getRuntime().exec(new String[]{"settings", "put", "global", "development_settings_enabled", "1"});
        } catch (Throwable ignored) {}

        sRunning.set(true);
        sDiagThread = new Thread(() -> runDiagnosticLoop(context), "DiLink5PowerDiagThread");
        sDiagThread.start();
        logger.info("DiLink 5 Power & Sentry Diagnostics logger started -> " + LOG_PATH);
    }

    public static synchronized void stop() {
        if (!sRunning.get()) return;
        sRunning.set(false);
        if (sDiagThread != null) {
            sDiagThread.interrupt();
            sDiagThread = null;
        }
        if (sWakeLockManager != null) {
            try {
                sWakeLockManager.releaseAll();
            } catch (Throwable ignored) {}
            sWakeLockManager = null;
        }
        logger.info("DiLink 5 Power & Sentry Diagnostics logger stopped.");
    }

    private static void runDiagnosticLoop(Context context) {
        File logFile = new File(LOG_PATH);
        File parent = logFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

        appendLog(logFile, "=================================================================\n");
        appendLog(logFile, "=== DiLink 5 Sentry & Power Diagnostic Session Started: " + sdf.format(new Date()) + " ===\n");
        appendLog(logFile, "=================================================================\n");

        while (sRunning.get()) {
            try {
                String timestamp = sdf.format(new Date());

                // 1. ACC State
                String accAnimStatus = execShell("getprop sys.accanim.status").trim();
                String screenPower = execShell("dumpsys power 2>/dev/null | grep -i 'Display Power' | head -1").trim();
                String isInteractive = execShell("dumpsys power 2>/dev/null | grep -i 'mIsInteractive' | head -1").trim();

                // 2. Wi-Fi Status & Proactive Reconnect
                String wifiIp = "N/A";
                String wifiSsid = "N/A";
                int wifiRssi = 0;
                boolean wifiConnected = false;
                if (context != null) {
                    try {
                        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        if (wm != null) {
                            if (!wm.isWifiEnabled()) {
                                wm.setWifiEnabled(true);
                            }
                            WifiInfo info = wm.getConnectionInfo();
                            if (info != null && info.getNetworkId() != -1) {
                                wifiSsid = info.getSSID();
                                wifiRssi = info.getRssi();
                                int ip = info.getIpAddress();
                                if (ip != 0) {
                                    wifiConnected = true;
                                    wifiIp = String.format(Locale.US, "%d.%d.%d.%d",
                                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                                }
                            }
                            // Proactively reconnect if disconnected
                            if (!wifiConnected) {
                                wm.reconnect();
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                if ("N/A".equals(wifiIp) || "0.0.0.0".equals(wifiIp)) {
                    String ipCmd = execShell("ip addr show wlan0 2>/dev/null | grep 'inet ' | awk '{print $2}'").trim();
                    if (!ipCmd.isEmpty()) {
                        wifiIp = ipCmd;
                        wifiConnected = true;
                    } else {
                        // Shell fallback to ensure Wi-Fi stays awake and reconnects
                        execShell("svc wifi enable 2>/dev/null; cmd wifi reconnect 2>/dev/null");
                    }
                }

                // 3. Hardware Camera Status
                String qcarcamPid = execShell("pgrep -f fast_cam_capture").trim();
                boolean qcarcamRunning = !qcarcamPid.isEmpty();
                boolean backendSupported = DiLink5QCarCamBackend.isSupported();

                // 4. TS AVM Status
                boolean tsAvmAlive = TsAvmCoordinator.isAvmServiceAlive();

                // Format entry
                String entry = String.format(Locale.US,
                        "[%s] ACC: '%s' | Screen: [%s, %s] | Wi-Fi: [Connected=%b, IP=%s, SSID=%s, RSSI=%d] | FastCam: [Running=%b, PID=%s, Supported=%b] | AVM Alive: %b\n",
                        timestamp,
                        accAnimStatus.isEmpty() ? "0 (ON)" : accAnimStatus,
                        screenPower.isEmpty() ? "UNKNOWN" : screenPower,
                        isInteractive.isEmpty() ? "UNKNOWN" : isInteractive,
                        wifiConnected,
                        wifiIp,
                        wifiSsid,
                        wifiRssi,
                        qcarcamRunning,
                        qcarcamPid.isEmpty() ? "NONE" : qcarcamPid,
                        backendSupported,
                        tsAvmAlive
                );

                appendLog(logFile, entry);

                Thread.sleep(2000);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                logger.warn("Diagnostic loop iteration error: " + t.getMessage());
                try { Thread.sleep(3000); } catch (Throwable ignored) {}
            }
        }
    }

    private static synchronized void appendLog(File file, String text) {
        try (FileWriter fw = new FileWriter(file, true)) {
            fw.write(text);
            fw.flush();
        } catch (Throwable ignored) {}
    }

    private static String execShell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
