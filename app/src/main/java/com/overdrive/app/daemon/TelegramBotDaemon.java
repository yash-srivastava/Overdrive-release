package com.overdrive.app.daemon;

import android.os.Looper;

import com.overdrive.app.daemon.telegram.CommandContext;
import com.overdrive.app.daemon.telegram.CommandRouter;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.overdrive.app.daemon.proxy.Safe;

/**
 * Telegram Bot Daemon - runs as shell user (UID 2000) via ADB shell.
 * 
 * Uses long polling (not webhooks) for NAT compatibility behind 4G.
 * Commands are handled by modular handlers in the telegram package.
 * 
 * IPC server on port 19877 accepts notification requests from app process.
 */
public class TelegramBotDaemon {
    
    private static final String TAG = "TelegramBotDaemon";
    private static DaemonLogger logger;
    
    // ==================== ENCRYPTED CONSTANTS (SOTA Java obfuscation) ====================
    // Decrypted at runtime via Safe.s() - AES-256-CBC with stack-based key reconstruction
    /** /data/local/tmp */
    private static String PATH_DATA_LOCAL_TMP() { return Safe.s("vuaMjrmBGBFh07qqnUuL8w=="); }
    /** /data/local/tmp/telegram_config.properties */
    private static String PATH_TELEGRAM_CONFIG() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxzwQSn0P1N0jxHygc8N+4Ft+9mlR8XQ+WvEw0ktanrtNx"); }
    /** /data/local/tmp/tunnel_url.txt */
    private static String PATH_TELEGRAM_URL_FILE() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxz/kVx51CDNRiQ/Mc5+npiPo="); }
    /** https://api.telegram.org/bot */
    private static String TELEGRAM_API_BASE() { return Safe.s("FS7R/5I0wopp0qBqyJXzvDKg6eI9UXmD/Oei3NbaaGQ="); }
    
    private static final int IPC_PORT = 19878;  // Telegram IPC (19877 is used by Surveillance)
    
    // Singleton lock (same pattern as CameraDaemon / AccSentryDaemon)
    private static final String LOCK_FILE = "/data/local/tmp/telegram_bot_daemon.lock";
    private static java.io.RandomAccessFile lockFileHandle;
    private static java.nio.channels.FileLock fileLock;
    
    private static volatile boolean running = true;
    private static final AtomicBoolean polling = new AtomicBoolean(false);
    
    private static String botToken;
    private static long ownerChatId = -1;
    private static boolean videoUploadsEnabled = false;  // Default to OFF - user must enable
    private static OkHttpClient httpClient;
    private static long lastUpdateId = 0;
    
    // Track processed update IDs to prevent duplicate processing
    private static final java.util.Set<Long> processedUpdateIds = 
        java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<Long, Boolean>() {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                return size() > 100; // Keep last 100 update IDs
            }
        });
    
    // Command router for modular command handling
    private static CommandRouter commandRouter;
    
    public static void main(String[] args) {
        int myUid = android.os.Process.myUid();
        int myPid = android.os.Process.myPid();
        
        // Configure DaemonLogger for daemon context (enable stdout for app_process)
        DaemonLogger.configure(DaemonLogger.Config.defaults()
            .withStdoutLog(true)
            .withFileLog(true)
            .withConsoleLog(true));
        
        logger = DaemonLogger.getInstance(TAG, PATH_DATA_LOCAL_TMP());
        
        log("=== Telegram Bot Daemon Starting ===");
        log("UID: " + myUid + " (expected: 2000 shell)");
        log("PID: " + myPid);
        
        // Kill any old instances using pkill -f (same pattern as DaemonLauncher.kt)
        killOldInstances(myPid);
        
        // CRITICAL: Acquire singleton lock - exit if another instance survived
        if (!acquireSingletonLock()) {
            log("ERROR: Another TelegramBotDaemon instance is already running. Exiting.");
            System.exit(1);
            return;
        }
        
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        
        try {
            if (!loadConfig()) {
                log("FATAL: No bot token configured");
                return;
            }
            
            initHttpClient();
            initCommandRouter();
            startIpcServer();  // Start IPC server for app notifications
            startPolling();
            
            log("Daemon running, polling for updates...");
            Looper.loop();
            
        } catch (Exception e) {
            log("FATAL: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // ==================== DUPLICATE INSTANCE CLEANUP ====================
    
    /**
     * Kill old daemon instances using pkill (same approach as DaemonLauncher.kt).
     * Excludes our own PID to avoid killing ourselves.
     * Also cleans up stale lock file left by SIGKILL'd processes.
     */
    private static void killOldInstances(int myPid) {
        try {
            // Find and kill other telegram_bot_daemon processes, excluding our own PID.
            // pkill -9 -f would match our own process too (command line contains the pattern),
            // so we use ps + grep + awk to filter by PID instead.
            String killCmd = "ps -A -o PID,ARGS | grep -F telegram_bot_daemon | grep -v grep | awk '{print $1}' | while read pid; do " +
                    "if [ \"$pid\" != \"" + myPid + "\" ]; then kill -9 $pid 2>/dev/null; fi; done";
            execShell(killCmd);
            Thread.sleep(500);
            
            // Clean up stale lock file (SIGKILL doesn't trigger shutdown hooks)
            new java.io.File(LOCK_FILE).delete();
            
            log("Old instance cleanup complete (my PID: " + myPid + ")");
        } catch (Exception e) {
            log("Error killing old instances: " + e.getMessage());
        }
    }
    
    // ==================== SINGLETON LOCK ====================
    
    /**
     * Acquire a file lock to ensure only one daemon instance runs at a time.
     * Same pattern as CameraDaemon / AccSentryDaemon.
     */
    private static boolean acquireSingletonLock() {
        try {
            java.io.File lockFileObj = new java.io.File(LOCK_FILE);
            lockFileHandle = new java.io.RandomAccessFile(lockFileObj, "rw");
            java.nio.channels.FileChannel channel = lockFileHandle.getChannel();
            
            // Try to acquire exclusive lock (non-blocking)
            fileLock = channel.tryLock();
            
            if (fileLock == null) {
                lockFileHandle.close();
                return false;
            }
            
            // Write our PID to the lock file for debugging
            lockFileHandle.setLength(0);
            lockFileHandle.writeBytes(String.valueOf(android.os.Process.myPid()));
            
            log("Acquired singleton lock (PID: " + android.os.Process.myPid() + ")");
            
            // Register shutdown hook to release lock on process termination
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                releaseSingletonLock();
            }));
            
            return true;
            
        } catch (java.nio.channels.OverlappingFileLockException e) {
            log("Lock already held by this process");
            return false;
        } catch (Exception e) {
            log("Failed to acquire singleton lock: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Release the singleton lock on shutdown.
     */
    private static void releaseSingletonLock() {
        try {
            if (fileLock != null) {
                fileLock.release();
                fileLock = null;
            }
            if (lockFileHandle != null) {
                lockFileHandle.close();
                lockFileHandle = null;
            }
            new java.io.File(LOCK_FILE).delete();
        } catch (Exception e) {
            log("Error releasing singleton lock: " + e.getMessage());
        }
    }
    
    private static void log(String msg) {
        if (logger != null) {
            logger.info(msg);
        }
        // Note: System.out.println is now handled by DaemonLogger when enableStdoutLog is true
    }
    
    // ==================== INITIALIZATION ====================
    
    private static boolean loadConfig() {
        try {
            File configFile = new File(PATH_TELEGRAM_CONFIG());
            if (!configFile.exists()) {
                log("Config file not found: " + PATH_TELEGRAM_CONFIG());
                return false;
            }
            
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
            }
            
            botToken = props.getProperty("bot_token");
            if (botToken == null || botToken.isEmpty()) {
                log("bot_token not set in config");
                return false;
            }
            
            String ownerStr = props.getProperty("owner_chat_id", "-1");
            ownerChatId = Long.parseLong(ownerStr);
            
            // Load video uploads preference (default OFF)
            String videoUploadsStr = props.getProperty("video_uploads", "false");
            videoUploadsEnabled = "true".equalsIgnoreCase(videoUploadsStr);
            
            log("Config loaded: token=***" + botToken.substring(Math.max(0, botToken.length() - 6)));
            log("Owner chat ID: " + (ownerChatId > 0 ? ownerChatId : "not set"));
            log("Video uploads: " + (videoUploadsEnabled ? "enabled" : "disabled"));
            
            return true;
        } catch (Exception e) {
            log("Config load error: " + e.getMessage());
            return false;
        }
    }
    
    private static long lastProxyCheckTime = 0;
    private static boolean lastProxyState = false; // true = proxy was available

    private static void initHttpClient() {
        refreshHttpClient();
    }

    /**
     * Refresh HTTP client with current proxy settings.
     * Called on init and after connection failures to pick up proxy changes.
     */
    private static void refreshHttpClient() {
        lastProxyCheckTime = System.currentTimeMillis();

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);
        
        // Check for global proxy settings
        java.net.Proxy proxy = getGlobalProxy();
        boolean proxyAvailable = (proxy != null);

        if (proxyAvailable) {
            builder.proxy(proxy);
            if (!lastProxyState) {
                log("HTTP client switched to proxy: " + proxy.address());
            }
        } else {
            if (lastProxyState) {
                log("HTTP client switched to direct connection (proxy gone)");
            }
        }

        lastProxyState = proxyAvailable;
        httpClient = builder.build();
    }

    /**
     * Invalidate HTTP client so next request re-checks proxy.
     * Called on connection failures.
     */
    private static void onHttpFailure() {
        // Re-check proxy if last check was more than 10 seconds ago
        long elapsed = System.currentTimeMillis() - lastProxyCheckTime;
        if (elapsed > 10_000) {
            refreshHttpClient();
        }
    }
    
    /**
     * Get global HTTP proxy from Android settings.
     * Reads from: settings get global http_proxy (format: host:port)
     */
    private static java.net.Proxy getGlobalProxy() {
        try {
            String proxyStr = execShell("settings get global http_proxy 2>/dev/null");
            if (proxyStr == null || proxyStr.trim().isEmpty() || proxyStr.trim().equals("null") || proxyStr.trim().equals(":0")) {
                return null;
            }
            
            proxyStr = proxyStr.trim();
            String[] parts = proxyStr.split(":");
            if (parts.length != 2) {
                return null;
            }
            
            String host = parts[0];
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
            
            if (host.isEmpty() || port <= 0) {
                return null;
            }
            
            log("Found global proxy: " + host + ":" + port);
            return new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress(host, port));
        } catch (Exception e) {
            log("Error reading proxy settings: " + e.getMessage());
            return null;
        }
    }
    
    private static void initCommandRouter() {
        commandRouter = new CommandRouter(new CommandContext() {
            @Override
            public boolean sendMessage(long chatId, String text) {
                return TelegramBotDaemon.sendMessage(chatId, text);
            }
            
            @Override
            public boolean sendMessageWithButtons(long chatId, String text, String[][][] buttons) {
                return TelegramBotDaemon.sendMessageWithButtons(chatId, text, buttons);
            }
            
            @Override
            public boolean sendVideo(long chatId, String videoPath, String caption) {
                return TelegramBotDaemon.sendVideo(chatId, videoPath, caption);
            }
            
            @Override
            public JSONObject sendIpcCommand(int port, JSONObject command) {
                return TelegramBotDaemon.sendIpcCommand(port, command);
            }
            
            @Override
            public String execShell(String command) {
                return TelegramBotDaemon.execShell(command);
            }
            
            @Override
            public void log(String message) {
                TelegramBotDaemon.log(message);
            }
        });
    }
    
    // ==================== IPC SERVER ====================
    
    /**
     * Start IPC server to receive notification requests from app process.
     * Listens on localhost:19877 for JSON commands.
     */
    private static void startIpcServer() {
        Thread ipcThread = new Thread(() -> {
            log("IPC server starting on port " + IPC_PORT);
            
            while (running) {
                ServerSocket serverSocket = null;
                try {
                    serverSocket = new ServerSocket(IPC_PORT, 5, InetAddress.getByName("127.0.0.1"));
                    serverSocket.setReuseAddress(true);
                    log("IPC server listening on 127.0.0.1:" + IPC_PORT);
                    
                    while (running) {
                        try {
                            Socket client = serverSocket.accept();
                            handleIpcClient(client);
                        } catch (Exception e) {
                            if (running) {
                                log("IPC accept error: " + e.getMessage());
                            }
                        }
                    }
                } catch (java.net.BindException e) {
                    log("IPC port " + IPC_PORT + " in use, retrying...");
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                } catch (Exception e) {
                    log("IPC server error: " + e.getMessage());
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                } finally {
                    if (serverSocket != null) {
                        try { serverSocket.close(); } catch (Exception ignored) {}
                    }
                }
            }
            
            log("IPC server stopped");
        }, "TelegramIPC");
        ipcThread.setDaemon(true);
        ipcThread.start();
    }
    
    private static void handleIpcClient(Socket client) {
        try {
            client.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            PrintWriter writer = new PrintWriter(client.getOutputStream(), true);
            
            String line = reader.readLine();
            if (line != null) {
                log("IPC received: " + line);
                JSONObject response = processIpcCommand(new JSONObject(line));
                writer.println(response.toString());
            }
        } catch (Exception e) {
            log("IPC client error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }
    
    private static JSONObject processIpcCommand(JSONObject cmd) {
        JSONObject response = new JSONObject();
        try {
            String action = cmd.optString("cmd", "");
            
            switch (action) {
                case "sendMessage":
                    long chatId = cmd.optLong("chatId", ownerChatId);
                    String text = cmd.optString("text", "");
                    if (chatId > 0 && !text.isEmpty()) {
                        boolean ok = sendMessage(chatId, text);
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Missing chatId or text");
                    }
                    break;
                    
                case "sendVideo":
                    // Check if video uploads are enabled
                    if (!videoUploadsEnabled) {
                        log("Video upload skipped - auto-upload disabled in settings");
                        response.put("status", "skipped");
                        response.put("message", "Video uploads disabled");
                        break;
                    }
                    
                    long videoChatId = cmd.optLong("chatId", ownerChatId);
                    String videoPath = cmd.optString("path", "");
                    String caption = cmd.optString("caption", "");
                    if (videoChatId > 0 && !videoPath.isEmpty()) {
                        boolean ok = sendVideo(videoChatId, videoPath, caption);
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Missing chatId or path");
                    }
                    break;
                    
                case "notifyTunnel":
                    String url = cmd.optString("url", "");
                    boolean isNew = cmd.optBoolean("isNew", true);
                    log("IPC notifyTunnel: url=" + url + ", isNew=" + isNew);
                    if (!url.isEmpty() && ownerChatId > 0) {
                        // Save URL to file for /url command
                        saveTunnelUrl(url);
                        
                        String msg = isNew ? "🌐 *Tunnel URL*\n" + url : "🔄 *Tunnel URL Changed*\n" + url;
                        boolean ok = sendMessage(ownerChatId, msg);
                        log("notifyTunnel message sent: " + ok);
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "No URL or owner not set");
                    }
                    break;
                    
                case "notifyMotion":
                    String detection = cmd.optString("detection", "motion");
                    float confidence = (float) cmd.optDouble("confidence", 0);
                    String videoFilename = cmd.optString("videoFilename", "");
                    if (ownerChatId > 0) {
                        StringBuilder msg = new StringBuilder();
                        msg.append("🚨 *Motion Detected*\n");
                        msg.append(detection);
                        if (confidence > 0) msg.append(" (").append(Math.round(confidence * 100)).append("%)");
                        if (!videoFilename.isEmpty()) {
                            msg.append("\n\n📹 Recording: `").append(videoFilename).append("`");
                            msg.append("\n⏳ _Recording in progress..._");
                            msg.append("\n📥 `/download ").append(videoFilename).append("`");
                        }
                        boolean ok = sendMessage(ownerChatId, msg.toString());
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Owner not set");
                    }
                    break;
                    
                case "notifyCritical":
                    String criticalType = cmd.optString("type", "");
                    String details = cmd.optString("details", "");
                    if (ownerChatId > 0) {
                        String msg = "⚠️ *Critical Alert*\n" + criticalType;
                        if (!details.isEmpty()) msg += "\n" + details;
                        boolean ok = sendMessage(ownerChatId, msg);
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Owner not set");
                    }
                    break;
                    
                case "ping":
                    response.put("status", "ok");
                    response.put("ownerChatId", ownerChatId);
                    break;
                    
                default:
                    response.put("status", "error");
                    response.put("message", "Unknown command: " + action);
            }
        } catch (Exception e) {
            try {
                response.put("status", "error");
                response.put("message", e.getMessage());
            } catch (Exception ignored) {}
        }
        return response;
    }
    
    // ==================== LONG POLLING ====================
    
    private static void startPolling() {
        if (polling.getAndSet(true)) {
            log("Already polling");
            return;
        }
        
        Thread pollThread = new Thread(() -> {
            log("Polling thread started");
            
            // Skip old messages by getting latest update ID first
            flushOldUpdates();
            
            // Send greeting to owner if paired
            sendStartupGreeting();
            
            while (running && polling.get()) {
                try {
                    pollUpdates();
                } catch (Exception e) {
                    log("Poll error: " + e.getMessage());
                    onHttpFailure(); // Re-check proxy on connection failure
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
            
            log("Polling thread stopped");
        }, "TelegramPoll");
        pollThread.setDaemon(true);
        pollThread.start();
    }
    
    /**
     * Flush old updates by getting the latest update ID without processing.
     * This prevents the bot from responding to messages sent while it was offline.
     */
    private static void flushOldUpdates() {
        try {
            log("Flushing old updates...");
            
            // Get updates with offset -1 to get only the latest update
            String url = TELEGRAM_API_BASE() + botToken + "/getUpdates?timeout=1&offset=-1";
            
            Request request = new Request.Builder().url(url).get().build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log("Flush HTTP error: " + response.code());
                    return;
                }
                
                String body = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(body);
                
                if (!json.optBoolean("ok", false)) {
                    log("Flush API error: " + json.optString("description"));
                    return;
                }
                
                JSONArray updates = json.optJSONArray("result");
                if (updates != null && updates.length() > 0) {
                    // Get the last update ID and set our offset past it
                    JSONObject lastUpdate = updates.getJSONObject(updates.length() - 1);
                    lastUpdateId = lastUpdate.getLong("update_id");
                    log("Flushed old updates, starting from update_id: " + (lastUpdateId + 1));
                } else {
                    log("No old updates to flush");
                }
            }
        } catch (Exception e) {
            log("Flush error: " + e.getMessage());
        }
    }
    
    /**
     * Send a startup greeting message to the owner.
     */
    private static void sendStartupGreeting() {
        if (ownerChatId <= 0) {
            log("No owner paired, skipping startup greeting");
            return;
        }
        
        try {
            String greeting = "🤖 *Surveillance Bot Online*\n\n" +
                    "Bot daemon started and ready.\n" +
                    "Use /help for available commands.";
            
            String[][][] buttons = {
                {{"📊 Status", "cmd:/status"}, {"🤖 Daemons", "cmd:/daemons"}},
                {{"📹 Events", "cmd:/events"}, {"🌐 Tunnel URL", "cmd:/url"}}
            };
            
            boolean sent = sendMessageWithButtons(ownerChatId, greeting, buttons);
            log("Startup greeting sent: " + sent);
        } catch (Exception e) {
            log("Startup greeting error: " + e.getMessage());
        }
    }
    
    private static void pollUpdates() throws Exception {
        String url = TELEGRAM_API_BASE() + botToken + "/getUpdates?timeout=30&offset=" + (lastUpdateId + 1);
        
        Request request = new Request.Builder().url(url).get().build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log("Poll HTTP error: " + response.code());
                onHttpFailure();
                return;
            }
            
            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(body);
            
            if (!json.optBoolean("ok", false)) {
                log("Poll API error: " + json.optString("description"));
                return;
            }
            
            JSONArray updates = json.optJSONArray("result");
            if (updates == null || updates.length() == 0) return;
            
            for (int i = 0; i < updates.length(); i++) {
                JSONObject update = updates.getJSONObject(i);
                long updateId = update.getLong("update_id");
                
                // Skip if already processed (deduplication)
                if (processedUpdateIds.contains(updateId)) {
                    log("Skipping duplicate update: " + updateId);
                    continue;
                }
                processedUpdateIds.add(updateId);
                
                lastUpdateId = Math.max(lastUpdateId, updateId);
                processUpdate(update);
            }
        }
    }
    
    // ==================== UPDATE PROCESSING ====================
    
    private static void processUpdate(JSONObject update) {
        try {
            // Handle callback queries (button presses)
            JSONObject callbackQuery = update.optJSONObject("callback_query");
            if (callbackQuery != null) {
                processCallbackQuery(callbackQuery);
                return;
            }
            
            JSONObject message = update.optJSONObject("message");
            if (message == null) return;
            
            JSONObject chat = message.optJSONObject("chat");
            if (chat == null) return;
            
            long chatId = chat.getLong("id");
            String text = message.optString("text", "");
            
            JSONObject from = message.optJSONObject("from");
            String username = from != null ? from.optString("username", "") : "";
            String firstName = from != null ? from.optString("first_name", "") : "";
            
            log("Message from " + chatId + " (@" + username + "): " + text);
            
            // Handle /pair command (allowed even without owner)
            if (text.startsWith("/pair ")) {
                handlePairCommand(chatId, username, firstName, text.substring(6).trim());
                return;
            }
            
            // Owner-only commands
            if (ownerChatId <= 0) {
                sendMessage(chatId, "⚠️ No owner paired. Use /pair <PIN> to pair.");
                return;
            }
            
            if (chatId != ownerChatId) {
                log("Ignoring message from non-owner: " + chatId);
                return;
            }
            
            // Route commands via modular handlers
            if (text.startsWith("/")) {
                commandRouter.route(chatId, text);
            }
            
        } catch (Exception e) {
            log("Update processing error: " + e.getMessage());
        }
    }
    
    /**
     * Process callback query from inline keyboard button press.
     */
    private static void processCallbackQuery(JSONObject callbackQuery) {
        try {
            String callbackId = callbackQuery.getString("id");
            String data = callbackQuery.optString("data", "");
            
            JSONObject from = callbackQuery.optJSONObject("from");
            long userId = from != null ? from.getLong("id") : 0;
            
            log("Callback from " + userId + ": " + data);
            
            // Only allow owner
            if (userId != ownerChatId) {
                answerCallbackQuery(callbackId, "⚠️ Not authorized");
                return;
            }
            
            // Handle download callback: "dl:filename.mp4"
            if (data.startsWith("dl:")) {
                String filename = data.substring(3);
                answerCallbackQuery(callbackId, "📥 Downloading...");
                commandRouter.route(ownerChatId, "/download " + filename);
            }
            // Handle events pagination: "ev:hours:page"
            else if (data.startsWith("ev:")) {
                String[] parts = data.substring(3).split(":");
                if (parts.length == 2) {
                    answerCallbackQuery(callbackId, null);
                    commandRouter.route(ownerChatId, "/events " + parts[0] + " " + parts[1]);
                }
            }
            // Handle command shortcut: "cmd:/command"
            else if (data.startsWith("cmd:")) {
                String command = data.substring(4);
                answerCallbackQuery(callbackId, null);
                commandRouter.route(ownerChatId, command);
            }
            // Handle daemon control: "dm:name:action"
            else if (data.startsWith("dm:")) {
                String[] parts = data.substring(3).split(":");
                if (parts.length == 2) {
                    answerCallbackQuery(callbackId, "⏳ " + parts[1] + "ing...");
                    commandRouter.route(ownerChatId, "/daemon " + parts[0] + " " + parts[1]);
                }
            }
            else {
                answerCallbackQuery(callbackId, "Unknown action");
            }
            
        } catch (Exception e) {
            log("Callback processing error: " + e.getMessage());
        }
    }
    
    /**
     * Answer a callback query (acknowledge button press).
     */
    private static void answerCallbackQuery(String callbackId, String text) {
        try {
            String url = TELEGRAM_API_BASE() + botToken + "/answerCallbackQuery";
            
            JSONObject body = new JSONObject();
            body.put("callback_query_id", callbackId);
            if (text != null && !text.isEmpty()) {
                body.put("text", text);
            }
            
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
            
            httpClient.newCall(request).execute().close();
        } catch (Exception e) {
            log("answerCallbackQuery error: " + e.getMessage());
        }
    }
    
    private static void handlePairCommand(long chatId, String username, String firstName, String pin) {
        if (ownerChatId > 0) {
            sendMessage(chatId, "❌ Already paired with another owner.");
            return;
        }
        
        if (pin.length() != 6 || !pin.matches("\\d+")) {
            sendMessage(chatId, "❌ Invalid PIN format. Enter 6-digit PIN from app.");
            return;
        }
        
        // Validate PIN against the one generated by the app UI
        String expectedPin = null;
        long pinExpiry = 0;
        try {
            File configFile = new File(PATH_TELEGRAM_CONFIG());
            if (configFile.exists()) {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    props.load(fis);
                }
                expectedPin = props.getProperty("pair_pin", "");
                pinExpiry = Long.parseLong(props.getProperty("pair_pin_expiry", "0"));
            }
        } catch (Exception e) {
            log("Error reading pair PIN from config: " + e.getMessage());
        }
        
        if (expectedPin == null || expectedPin.isEmpty()) {
            sendMessage(chatId, "❌ No PIN generated. Generate a PIN from the app first.");
            return;
        }
        
        if (System.currentTimeMillis() > pinExpiry) {
            sendMessage(chatId, "❌ PIN expired. Generate a new PIN from the app.");
            // Clear expired PIN from config
            clearPairPinFromConfig();
            return;
        }
        
        if (!pin.equals(expectedPin)) {
            sendMessage(chatId, "❌ Invalid PIN. Check the PIN shown in the app.");
            return;
        }
        
        // PIN valid — pair owner
        ownerChatId = chatId;
        saveOwnerToConfig(chatId, username, firstName);
        clearPairPinFromConfig();
        
        sendMessage(chatId, "✅ Paired successfully!\n\nWelcome, " + firstName + "!\nUse /help to see available commands.");
    }
    
    private static void clearPairPinFromConfig() {
        try {
            File configFile = new File(PATH_TELEGRAM_CONFIG());
            if (!configFile.exists()) return;
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
            }
            props.remove("pair_pin");
            props.remove("pair_pin_expiry");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
                props.store(fos, "Telegram Bot Config");
            }
        } catch (Exception e) {
            log("Error clearing pair PIN: " + e.getMessage());
        }
    }
    
    // ==================== MESSAGING ====================
    
    private static boolean sendMessage(long chatId, String text) {
        try {
            String url = TELEGRAM_API_BASE() + botToken + "/sendMessage";
            
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "Markdown");
            
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log("sendMessage error: " + e.getMessage());
            onHttpFailure();
            return false;
        }
    }
    
    /**
     * Send a message with inline keyboard buttons.
     * @param buttons Array of button rows, each row is array of [text, callbackData] pairs
     */
    public static boolean sendMessageWithButtons(long chatId, String text, String[][][] buttons) {
        try {
            String url = TELEGRAM_API_BASE() + botToken + "/sendMessage";
            
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "Markdown");
            
            // Build inline keyboard
            JSONArray keyboard = new JSONArray();
            for (String[][] row : buttons) {
                JSONArray rowArray = new JSONArray();
                for (String[] button : row) {
                    JSONObject btn = new JSONObject();
                    btn.put("text", button[0]);
                    btn.put("callback_data", button[1]);
                    rowArray.put(btn);
                }
                keyboard.put(rowArray);
            }
            
            JSONObject replyMarkup = new JSONObject();
            replyMarkup.put("inline_keyboard", keyboard);
            body.put("reply_markup", replyMarkup);
            
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log("sendMessageWithButtons error: " + e.getMessage());
            return false;
        }
    }
    
    public static boolean sendVideo(long chatId, String videoPath, String caption) {
        try {
            File videoFile = new File(videoPath);
            if (!videoFile.exists()) {
                log("Video file not found: " + videoPath);
                return false;
            }
            
            String url = TELEGRAM_API_BASE() + botToken + "/sendVideo";
            
            MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", String.valueOf(chatId))
                    .addFormDataPart("supports_streaming", "true")
                    .addFormDataPart("video", videoFile.getName(),
                            RequestBody.create(videoFile, MediaType.parse("video/mp4")));
            
            if (caption != null && !caption.isEmpty()) {
                bodyBuilder.addFormDataPart("caption", caption);
            }
            
            Request request = new Request.Builder()
                    .url(url)
                    .post(bodyBuilder.build())
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log("Video sent: " + videoPath);
                    return true;
                } else {
                    log("sendVideo HTTP error: " + response.code());
                    return false;
                }
            }
        } catch (Exception e) {
            log("sendVideo error: " + e.getMessage());
            return false;
        }
    }
    
    // ==================== UTILITIES ====================
    
    private static JSONObject sendIpcCommand(int port, JSONObject command) {
        Socket socket = null;
        try {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            writer.println(command.toString());
            String response = reader.readLine();
            
            return response != null ? new JSONObject(response) : null;
        } catch (Exception e) {
            log("IPC error (port " + port + "): " + e.getMessage());
            return null;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }
    
    private static String execShell(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append("\n");
                output.append(line);
            }
            reader.close();
            p.waitFor();
            return output.toString();
        } catch (Exception e) {
            log("Shell error: " + e.getMessage());
            return null;
        }
    }
    
    private static void saveOwnerToConfig(long chatId, String username, String firstName) {
        try {
            File configFile = new File(PATH_TELEGRAM_CONFIG());
            Properties props = new Properties();
            
            if (configFile.exists()) {
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    props.load(fis);
                }
            }
            
            props.setProperty("owner_chat_id", String.valueOf(chatId));
            props.setProperty("owner_username", username);
            props.setProperty("owner_first_name", firstName);
            
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
                props.store(fos, "Telegram Bot Config");
            }
            
            log("Owner saved to config: " + chatId);
        } catch (Exception e) {
            log("Save owner error: " + e.getMessage());
        }
    }
    
    private static void saveTunnelUrl(String url) {
        try {
            File urlFile = new File(PATH_TELEGRAM_URL_FILE());
            try (java.io.FileWriter fw = new java.io.FileWriter(urlFile)) {
                fw.write(url);
            }
            log("Tunnel URL saved to file: " + url);
        } catch (Exception e) {
            log("Save tunnel URL error: " + e.getMessage());
        }
    }
    
    // ==================== PUBLIC API FOR NOTIFICATIONS ====================
    
    public static long getOwnerChatId() {
        return ownerChatId;
    }
    
    public static String getBotToken() {
        return botToken;
    }
}
