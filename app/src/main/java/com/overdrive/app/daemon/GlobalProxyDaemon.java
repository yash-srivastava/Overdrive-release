package com.overdrive.app.daemon;

import com.overdrive.app.daemon.proxy.Safe;

import java.io.*;

/**
 * Global Proxy Daemon - Runs as standalone shell process via app_process.
 *
 * Features:
 * - VLESS Reality proxy through sing-box
 * - Starts proxy immediately and keeps it running
 * - Survives app death since it runs as independent process
 */
public class GlobalProxyDaemon {

    private static final String TAG = "GlobalProxyDaemon";
    private static final int PROXY_PORT = 8119;

    // ==================== ENCRYPTED CONSTANTS (SOTA Java obfuscation) ====================
    // Decrypted at runtime via Safe.s() - AES-256-CBC with stack-based key reconstruction
    /** /sys/power/wake_lock */
    private static String WAKE_LOCK_PATH() { return Safe.s("kb7HnwNgcQAsfjzzZ2HOBMxdOhkMxwXzhyFBtedHnSE="); }
    /** /sys/power/wake_unlock */
    private static String WAKE_UNLOCK_PATH() { return Safe.s("kb7HnwNgcQAsfjzzZ2HOBFL9LU9wOcz7uvaGd3r+PHU="); }
    /** svc power stayon true */
    private static String SVC_POWER_ON() { return Safe.s("evL2bKzQb67Tf3KRHg1cMVG8PSjiOvOcAsdtUSiirz4="); }
    /** svc power stayon false */
    private static String SVC_POWER_OFF() { return Safe.s("evL2bKzQb67Tf3KRHg1cMaUQ0s15R3JRQ4W151UI/Rs="); }
    /** /data/local/tmp/singbox_config.json */
    private static String SINGBOX_CONFIG() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxzwYi8Dqee5TiGPRAGrXFGxQK19NSN/ULRr1XYqE0nHYW"); }
    /** /data/local/tmp/singbox.log */
    private static String SINGBOX_LOG() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxz3vxHXoriO/4/mUU2N2RxN4="); }
    /** /data/local/tmp/sing-box */
    private static String SINGBOX_BIN() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxz3TnY8grp670bzPyWlLlY9c="); }
    /** /data/local/tmp/global_proxy.log */
    private static String PROXY_LOG() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxz5q9/uqUtW8BShQTy+DvGbRG9DHIjhkEadRt3RoxzdGj"); }
    /** 8.8.8.8 */
    private static String DNS_GOOGLE() { return Safe.s("qmTp78S+Di6fTuBLyeiqxw=="); }
    /** 127.0.0.1 */
    private static String LOCALHOST() { return Safe.s("6e8x7uzAzonqK41m43RhgA=="); }
    /** vless */
    private static String PROTO_VLESS() { return Safe.s("18yIiwvdEyManh8f1OuEaQ=="); }
    /** proxy */
    private static String OUTBOUND_PROXY() { return Safe.s("4fG5lclj+WGG3xwD0zMQEw=="); }
    /** direct */
    private static String OUTBOUND_DIRECT() { return Safe.s("4zoj3R6yChQpby03Brip7A=="); }
    /** xtls-rprx-vision */
    private static String FLOW_XTLS() { return Safe.s("NfZtqyYYxEXIP/DQfXbsCTSplSKMeCc8xGR/BRlNRXs="); }
    /** chrome */
    private static String FINGERPRINT_CHROME() { return Safe.s("Gf88MYMzGZxBFiJtVxW9gg=="); }
    /** sing-box */
    private static String SINGBOX_NAME() { return Safe.s("DctRKb8o3mNJ2B6M4PEoFg=="); }
    /** pgrep -f sing-box */
    private static String PGREP_SINGBOX() { return Safe.s("9ipr8gAWZBFa60ui235T/rrplvZH34LqrU8QEESPJhQ="); }
    /** pkill -9 -f sing-box */
    private static String KILL_SINGBOX() { return Safe.s("nmb9om2QcwiF1T7zMRJPyl8AlCmIEFx3vJQ/XJLYoW4="); }
    /** settings put global http_proxy */
    private static String SETTINGS_HTTP_PROXY() { return Safe.s("kSl507BgPZXbv0JUusGzZtZZFBcjQ2R+d3/C7e8ZtUQuYsjHLD46mEwmaY7YR2cV"); }
    /** settings put global global_http_proxy_host */
    private static String SETTINGS_PROXY_HOST() { return Safe.s("kSl507BgPZXbv0JUusGzZnGKkVWqt0LyYis5GQNSnGHkz6qPd6BcemXYwtnBTDajEUEt+3yL2O7fuOSB90onng=="); }
    /** settings put global global_http_proxy_port */
    private static String SETTINGS_PROXY_PORT() { return Safe.s("kSl507BgPZXbv0JUusGzZnGKkVWqt0LyYis5GQNSnGH6LFCzLqbGFlD2EngMGefa"); }
    /** settings put global global_http_proxy_exclusion_list */
    private static String SETTINGS_PROXY_EXCLUSION() { return Safe.s("kSl507BgPZXbv0JUusGzZnGKkVWqt0LyYis5GQNSnGGOecWapYNZORy+W9AwDB7WjWi7RBAdzApKbsHlHhozKw=="); }
    /** localhost,127.0.0.1,*.local,10.*,192.168.* */
    private static String PROXY_EXCLUSIONS() { return Safe.s("W7U2p2zXmYFyuvgULJIqsoxZjUDWIXt7EPxQTiIZSC2LArvfp3HcABvEZcmXXayC"); }

    // ==================== VLESS SERVER CREDENTIALS (encrypted) ====================
    /** [VLESS server IP] */
    private static String SERVER_IP() { return Safe.s("Z1e89edb4BHkcdq18ZwS4g=="); }
    /** [VLESS UUID] */
    private static String UUID() { return Safe.s("Y4LjDZ/o19unOrs5FoJBf5MjCDMSI/PGZXX9WqDp2Y4JJPBSUh2sYkt1ymAV0v2L"); }
    /** [VLESS short_id] */
    private static String SHORT_ID() { return Safe.s("o/bXp+lFk608hy8OWTfuELQgU4pZxSk/2VDwpg+KW/o="); }
    /** [VLESS public_key] */
    private static String PUBLIC_KEY() { return Safe.s("A37D/HxqBdi7vuJ5cyw8gh2VvwTgbS9cbhEaDv78GqpZznF3x5JiRMDj9WJpQ7Gm"); }
    /** [VLESS SNI] */
    private static String SNI() { return Safe.s("u7674GDA85JNqEffkyiqRg=="); }

    public static void main(String[] args) {
        log("=== Global Proxy Daemon ===");

        try {
            // Acquire WakeLock to keep CPU running when screen is off
            acquireWakeLock();

            enableProxy();

            log("Proxy started. Keeping process alive...");

            // Add shutdown hook to release WakeLock
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                releaseWakeLock();
                log("Shutdown hook executed");
            }));

            while (true) {
                Thread.sleep(60000);
                log("Proxy still running...");
            }
        } catch (Exception e) {
            log("FATAL: " + e.getMessage());
            e.printStackTrace();
            releaseWakeLock();
        }
    }

    // ==================== WakeLock ====================

    private static final String WAKELOCK_TAG = "GlobalProxyDaemon";

    /**
     * Acquire a partial WakeLock using shell commands.
     * This keeps the CPU running even when the screen is off.
     */
    private static void acquireWakeLock() {
        try {
            // Use dumpsys to acquire a wakelock via shell
            // This works for processes running as shell/root
            execShell("echo " + WAKELOCK_TAG + " > " + WAKE_LOCK_PATH());
            log("WakeLock acquired - CPU will stay awake");
        } catch (Exception e) {
            log("WakeLock acquisition via sysfs failed, trying alternative: " + e.getMessage());
            // Alternative: use svc power command
            execShell(SVC_POWER_ON());
            log("WakeLock alternative: svc power stayon true");
        }
    }

    /**
     * Release the WakeLock.
     */
    private static void releaseWakeLock() {
        try {
            execShell("echo " + WAKELOCK_TAG + " > " + WAKE_UNLOCK_PATH());
            log("WakeLock released");
        } catch (Exception e) {
            log("WakeLock release failed: " + e.getMessage());
        }
        // Also reset svc power if we used the alternative
        execShell(SVC_POWER_OFF());
    }

    /**
     * Enable proxy - start sing-box and set system proxy.
     */
    private static void enableProxy() {
        log(">>> ENABLING PROXY <<<");

        try {
            stopSingbox();
            createSingboxConfig();
            startSingbox();
            setupSystemProxy();

            log("Proxy ENABLED - traffic routed through VLESS");
        } catch (Exception e) {
            log("Failed to enable proxy: " + e.getMessage());
        }
    }

    private static void createSingboxConfig() throws IOException {
        // Use /data/local/tmp/ since UID 1000 can't write to /data/system/ (SELinux)
        String configPath = SINGBOX_CONFIG();
        String logPath = SINGBOX_LOG();

        String config = "{\n" +
                "  \"log\": { \"level\": \"warn\", \"timestamp\": true, \"output\": \"" + logPath + "\" },\n" +
                "  \"dns\": {\n" +
                "    \"servers\": [\n" +
                "      { \"tag\": \"google\", \"address\": \"" + DNS_GOOGLE() + "\", \"detour\": \"" + OUTBOUND_PROXY() + "\" }\n" +
                "    ],\n" +
                "    \"final\": \"google\",\n" +
                "    \"strategy\": \"ipv4_only\"\n" +
                "  },\n" +
                "  \"inbounds\": [\n" +
                "    {\n" +
                "      \"type\": \"mixed\",\n" +
                "      \"tag\": \"mixed-in\",\n" +
                "      \"listen\": \"" + LOCALHOST() + "\",\n" +
                "      \"listen_port\": " + PROXY_PORT + ",\n" +
                "      \"sniff\": true\n" +
                "    }\n" +
                "  ],\n" +
                "  \"outbounds\": [\n" +
                "    {\n" +
                "      \"type\": \"" + PROTO_VLESS() + "\",\n" +
                "      \"tag\": \"" + OUTBOUND_PROXY() + "\",\n" +
                "      \"server\": \"" + SERVER_IP() + "\",\n" +
                "      \"server_port\": 443,\n" +
                "      \"uuid\": \"" + UUID() + "\",\n" +
                "      \"flow\": \"" + FLOW_XTLS() + "\",\n" +
                "      \"tls\": {\n" +
                "        \"enabled\": true,\n" +
                "        \"server_name\": \"" + SNI() + "\",\n" +
                "        \"utls\": { \"enabled\": true, \"fingerprint\": \"" + FINGERPRINT_CHROME() + "\" },\n" +
                "        \"reality\": {\n" +
                "          \"enabled\": true,\n" +
                "          \"public_key\": \"" + PUBLIC_KEY() + "\",\n" +
                "          \"short_id\": \"" + SHORT_ID() + "\"\n" +
                "        }\n" +
                "      }\n" +
                "    },\n" +
                "    { \"type\": \"" + OUTBOUND_DIRECT() + "\", \"tag\": \"" + OUTBOUND_DIRECT() + "\" }\n" +
                "  ],\n" +
                "  \"route\": {\n" +
                "    \"rules\": [\n" +
                "      { \"protocol\": \"dns\", \"outbound\": \"" + OUTBOUND_PROXY() + "\" },\n" +
                "      { \"ip_cidr\": [\"127.0.0.0/8\", \"10.0.0.0/8\", \"192.168.0.0/16\", \"172.16.0.0/12\"], \"outbound\": \"" + OUTBOUND_DIRECT() + "\" }\n" +
                "    ]\n" +
                "  }\n" +
                "}";
        
        log("Writing config to " + configPath + "...");
        
        // Ensure directory exists
        File configDir = new File(configPath).getParentFile();
        if (configDir != null && !configDir.exists()) {
            execShell("mkdir -p " + configDir.getAbsolutePath());
        }
        
        // Try shell echo first (more reliable for daemon context)
        try {
            // Use printf instead of echo -e for better compatibility
            // Write to temp file first, then move (atomic)
            String tempPath = configPath + ".tmp";
            execShell("rm -f " + tempPath + " " + configPath);
            
            // Write config using cat with heredoc (most reliable)
            String heredocCmd = "cat > " + tempPath + " << 'SINGBOX_EOF'\n" + config + "\nSINGBOX_EOF";
            execShell(heredocCmd);
            execShell("mv " + tempPath + " " + configPath);
            
            // Verify
            File configFile = new File(configPath);
            if (configFile.exists() && configFile.length() > 100) {
                log("Config written via shell (" + configFile.length() + " bytes)");
                return;
            }
        } catch (Exception e) {
            log("Shell write failed: " + e.getMessage());
        }
        
        // Fallback to FileWriter
        try (FileWriter fw = new FileWriter(configPath)) {
            fw.write(config);
            fw.flush();
            log("Config written via FileWriter");
        } catch (IOException e) {
            log("FileWriter also failed: " + e.getMessage());
            throw e;
        }

        // Final verification
        File configFile = new File(configPath);
        if (configFile.exists() && configFile.length() > 0) {
            log("Config verified: " + configPath + " (" + configFile.length() + " bytes)");
        } else {
            throw new IOException("Config file not written correctly!");
        }
    }

    private static void startSingbox() throws Exception {
        // sing-box binary stays in /data/local/tmp/ - it's world-executable
        // Config and logs also go there since UID 1000 can't write to /data/system/ (SELinux)
        String singboxPath = SINGBOX_BIN();
        String configPath = SINGBOX_CONFIG();
        String logPath = SINGBOX_LOG();

        if (!new File(singboxPath).exists()) {
            throw new IOException(SINGBOX_NAME() + " binary not found at " + singboxPath + "!");
        }

        log("Using " + SINGBOX_NAME() + " at: " + singboxPath);

        // Run sing-box - it will run as the same UID as this daemon (1000)
        // The binary is world-executable so this should work
        String cmd = "nohup " + singboxPath + " run -c " + configPath + " > " + logPath + " 2>&1 &";
        log("Starting " + SINGBOX_NAME() + ": " + cmd);
        execShell(cmd);

        Thread.sleep(1500);

        // Verify sing-box started
        String checkCmd = PGREP_SINGBOX();
        Process check = Runtime.getRuntime().exec(new String[]{"sh", "-c", checkCmd});
        BufferedReader reader = new BufferedReader(new InputStreamReader(check.getInputStream()));
        String pid = reader.readLine();
        reader.close();

        if (pid != null && !pid.isEmpty()) {
            log(SINGBOX_NAME() + " started with PID: " + pid);
        } else {
            log("WARNING: " + SINGBOX_NAME() + " may not have started - check " + logPath);
        }
    }

    private static void stopSingbox() {
        execShell(KILL_SINGBOX());
        try { Thread.sleep(500); } catch (Exception ignored) {}
    }

    private static void setupSystemProxy() {
        // DISABLED: System-wide proxy is no longer needed and is risky.
        // If sing-box dies, stale proxy settings break ALL apps on the device.
        // All OverDrive components use explicit proxy configuration instead:
        // - ABRP/Telegram/Updater: ProxyHelper.getHttpProxy() via OkHttp
        // - MQTT: ProxyHelper.getMqttSocketFactory() via Paho
        // - Zrok/Cloudflared: HTTP_PROXY env var set per-process at launch
        //
        // execShell(SETTINGS_HTTP_PROXY() + PROXY_PORT);
        // execShell(SETTINGS_PROXY_HOST());
        // execShell(SETTINGS_PROXY_PORT() + PROXY_PORT);
        // execShell(SETTINGS_PROXY_EXCLUSION() + PROXY_EXCLUSIONS() + "\"");
        
        // Clear any stale proxy settings from previous versions
        execShell("settings delete global http_proxy 2>/dev/null");
        execShell("settings delete global global_http_proxy_host 2>/dev/null");
        execShell("settings delete global global_http_proxy_port 2>/dev/null");
        execShell("settings delete global global_http_proxy_exclusion_list 2>/dev/null");
        execShell("settings put global http_proxy :0 2>/dev/null");
        
        log("System proxy cleared (sing-box available on localhost:" + PROXY_PORT + " for app-scoped use only)");
    }

    private static void execShell(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd}).waitFor();
        } catch (Exception e) {}
    }

    private static void log(String msg) {
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        String logLine = "[" + timestamp + "] " + TAG + ": " + msg;
        System.out.println(logLine);
        if (com.overdrive.app.logging.DaemonLogConfig.isFileLoggingEnabled(TAG)) {
            try (FileWriter fw = new FileWriter(PROXY_LOG(), true)) {
                fw.write(logLine + "\n");
            } catch (Exception ignored) {}
        }
    }
}
