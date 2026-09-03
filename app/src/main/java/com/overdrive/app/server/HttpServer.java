package com.overdrive.app.server;

import android.content.res.AssetManager;
import android.util.Base64;

import com.overdrive.app.auth.AuthManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.genai.GenAiConfig;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.monitor.BatteryMonitor;
import com.overdrive.app.surveillance.GpuPipelineConfig;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HTTP Server - serves web UI and WebSocket H.264 streaming.
 * Listens on 0.0.0.0:8080 for tunnel access.
 * 
 * Single-port WebSocket: /ws endpoint upgrades to WebSocket for H.264 streaming
 * This allows Cloudflare tunnel to work (only one port needed).
 * 
 * API handlers are modularized into separate classes:
 * - RecordingsApiHandler: /api/recordings, /video/*
 * - SurveillanceApiHandler: /api/surveillance/*
 * - StreamingApiHandler: /api/stream/*
 * - GpsApiHandler: /api/gps/*
 * - QualitySettingsApiHandler: /api/settings/quality
 */
public class HttpServer {

    private static final String WEB_ROOT = "/data/local/tmp/web";
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running = true;

    // Thread Pool to prevent server clogging (max 32 concurrent connections)
    private final ExecutorService threadPool = Executors.newFixedThreadPool(32);

    public HttpServer(int port) {
        this.port = port;
    }

    /**
     * Extracts web assets from APK to filesystem.
     * Call this during initialization with a valid AssetManager.
     */
    public static void extractWebAssets(AssetManager assetManager) {
        if (assetManager == null) {
            CameraDaemon.log("AssetManager is null, skipping web asset extraction");
            return;
        }
        
        try {
            File webRoot = new File(WEB_ROOT);
            
            // Always delete and recreate to ensure fresh files on app update
            if (webRoot.exists()) {
                deleteRecursive(webRoot);
                CameraDaemon.log("Deleted existing web assets for fresh extraction");
            }
            webRoot.mkdirs();
            
            // Extract web/local and web/shared directories
            extractAssetDir(assetManager, "web/local", new File(WEB_ROOT, "local"));
            extractAssetDir(assetManager, "web/shared", new File(WEB_ROOT, "shared"));
            // Extract i18n catalogs (one JSON per supported locale).
            // The web/i18n directory is created by the NLLB translation pipeline
            // — at minimum web/i18n/en.json must exist for the runtime to load.
            extractAssetDir(assetManager, "web/i18n", new File(WEB_ROOT, "i18n"));
            // Extract server-side i18n catalogs (Messages.java lookup source).
            // Kept distinct from web/i18n so the HTTP /i18n/ route doesn't accidentally
            // expose internal error keys, and the two catalogs can diverge if needed.
            extractAssetDir(assetManager, "server-i18n", new File(WEB_ROOT, "server-i18n"));

            // Extract overlay icons for telemetry overlay
            extractAssetDir(assetManager, "overlay", new File("/data/local/tmp/overlay"));
            
            // Extract BYD cloud crypto tables — re-extract whenever the cache
            // is missing or fails magic/size validation, so a stale or
            // truncated /data/local/tmp file from a prior build can't poison
            // every reader with "Bad magic: expected BGTB".
            try {
                File bydTablesFile = new File(com.overdrive.app.byd.cloud.crypto.BangcleTablesFile.CACHE_PATH);
                if (!com.overdrive.app.byd.cloud.crypto.BangcleTablesFile.isValid(bydTablesFile)) {
                    if (com.overdrive.app.byd.cloud.crypto.BangcleTablesFile.extractFromAssets(assetManager, bydTablesFile)) {
                        CameraDaemon.log("Extracted BYD Bangcle tables to " + bydTablesFile.getAbsolutePath() + " (" + bydTablesFile.length() + " bytes)");
                    } else {
                        CameraDaemon.log("WARN: failed to extract BYD Bangcle tables; cache=" + com.overdrive.app.byd.cloud.crypto.BangcleTablesFile.describeCache());
                    }
                }
            } catch (Exception e) {
                CameraDaemon.log("Could not extract BYD Bangcle tables: " + e.getMessage());
            }

            // Same for the China (CN) WBSK tables — only relevant when a CN
            // account is configured, but pre-extracting is harmless and primes
            // the cache so the first CN login doesn't pay the extract cost.
            try {
                File wbskTablesFile = new File(com.overdrive.app.byd.cloud.crypto.WbskTablesFile.CACHE_PATH);
                if (!com.overdrive.app.byd.cloud.crypto.WbskTablesFile.isValid(wbskTablesFile)) {
                    if (com.overdrive.app.byd.cloud.crypto.WbskTablesFile.extractFromAssets(assetManager, wbskTablesFile)) {
                        CameraDaemon.log("Extracted BYD WBSK tables to " + wbskTablesFile.getAbsolutePath() + " (" + wbskTablesFile.length() + " bytes)");
                    } else {
                        CameraDaemon.log("WARN: failed to extract BYD WBSK tables; cache=" + com.overdrive.app.byd.cloud.crypto.WbskTablesFile.describeCache());
                    }
                }
            } catch (Exception e) {
                CameraDaemon.log("Could not extract BYD WBSK tables: " + e.getMessage());
            }
            
            CameraDaemon.log("Web assets extracted to " + WEB_ROOT);
        } catch (Exception e) {
            CameraDaemon.log("Failed to extract web assets: " + e.getMessage());
        }
    }
    
    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
    
    private static void extractAssetDir(AssetManager assetManager, String assetPath, File destDir) throws Exception {
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        
        String[] files = assetManager.list(assetPath);
        if (files == null || files.length == 0) {
            CameraDaemon.log("No files found in assets/" + assetPath);
            return;
        }
        
        for (String fileName : files) {
            String assetFilePath = assetPath + "/" + fileName;
            File destFile = new File(destDir, fileName);
            
            String[] subFiles = assetManager.list(assetFilePath);
            if (subFiles != null && subFiles.length > 0) {
                extractAssetDir(assetManager, assetFilePath, destFile);
            } else {
                try (InputStream in = assetManager.open(assetFilePath);
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                CameraDaemon.log("Extracted: " + assetFilePath + " -> " + destFile.getAbsolutePath());
            }
        }
    }

    public void start() {
        CameraDaemon.log("HTTP server starting on port " + port);
        
        // Initialize auth system
        AuthManager.initialize();
        CameraDaemon.log("Auth system initialized");
        
        while (running && CameraDaemon.isRunning()) {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try { serverSocket.close(); } catch (Exception e) {}
                }
                
                serverSocket = new ServerSocket(port, 10, InetAddress.getByName("0.0.0.0"));
                serverSocket.setReuseAddress(true);
                CameraDaemon.log("HTTP server listening on 0.0.0.0:" + port);

                while (running && CameraDaemon.isRunning() && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        // (Removed per-accept "HTTP client:" log — it fired for
                        // EVERY request including the blind-spot overlay's 250ms
                        // /api/stream/turn poll, flooding the daemon log. The
                        // per-request line below already records real requests and
                        // now skips the high-frequency polling endpoints.)
                        threadPool.execute(() -> handleClient(client));
                    } catch (java.net.SocketException e) {
                        if (running) {
                            CameraDaemon.log("WARN: HTTP socket error: " + e.getMessage());
                        }
                        break;
                    }
                }
                
                if (running) {
                    CameraDaemon.log("HTTP server restarting...");
                    Thread.sleep(2000);
                }
                
            } catch (java.net.BindException e) {
                CameraDaemon.log("ERROR: HTTP port " + port + " in use, retrying...");
                try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            } catch (Exception e) {
                CameraDaemon.log("ERROR: HTTP server error: " + e.getMessage());
                if (running) {
                    try { Thread.sleep(3000); } catch (InterruptedException ie) {}
                }
            }
        }
        
        CameraDaemon.log("HTTP server stopped");
    }

    public void stop() {
        running = false;
        RemoteCommunicationWebSocket.stopActive("Communication server stopped");
        CabinAudioWebSocket.stopActive("Communication server stopped");
        GenAiVoiceWebSocket.stopActive("GenAI server stopped");
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {}
        threadPool.shutdownNow();
    }

    static String redactQueryToken(String requestLine) {
        if (requestLine == null || requestLine.indexOf("token=") < 0) {
            return requestLine;
        }
        return requestLine.replaceAll(
                "([?&]token=)[^& ]*", "$1<redacted>");
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(15000);
            // Force UTF-8 so the body byte-count loop below is deterministic.
            // The platform default on Android is UTF-8 anyway, but pinning it
            // here removes the implicit dependency on the JVM property.
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
            OutputStream out = new BufferedOutputStream(client.getOutputStream());

            String requestLine = reader.readLine();
            if (requestLine == null) {
                client.close();
                return;
            }
            
            // Skip logging high-frequency polling endpoints to keep the daemon log
            // readable: the blind-spot overlay polls /api/stream/turn every 250ms
            // (safety turn-indicator read) and /api/bs/status during warm, and the
            // UI polls /status — at ~4-8 req/s these drown out everything else.
            // All other requests still log.
            if (!(requestLine.contains("/api/stream/turn")
                    || requestLine.contains("/api/bs/status")
                    || requestLine.startsWith("GET /status"))) {
                CameraDaemon.log("HTTP: " + redactQueryToken(requestLine));
            }

            // Parse headers
            String line;
            int contentLength = 0;
            String websocketKey = null;
            String upgradeHeader = null;
            String websocketProtocolHeader = null;
            String rangeHeader = null;
            // Conditional GET — if the client (Chrome WebView's HTTP cache)
            // sends If-None-Match matching our ETag, we return 304 instead of
            // re-streaming the whole file. Used for cached event recordings.
            String ifNoneMatchHeader = null;
            String cookieHeader = null;
            String authHeader = null;
            // Reverse-proxy fingerprints — used by AuthMiddleware to disable
            // the loopback safety net when a tunnel relayed the request.
            // Cloudflared injects Cf-*, zrok / ngrok injects X-Forwarded-*.
            boolean hasTunnelHeaders = false;
            String forwardedFor = null;
            String hostHeader = null;
            String originHeader = null;
            // Zrok's HTTP backend rewrites Host: to the backend URL (localhost:8080)
            // before forwarding, and stashes the original public hostname in
            // X-Forwarded-Host. Captured here so isPwaOrigin can match it.
            String forwardedHostHeader = null;

            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                } else if (lower.startsWith("sec-websocket-key:")) {
                    websocketKey = line.substring(18).trim();
                } else if (lower.startsWith("upgrade:")) {
                    upgradeHeader = line.substring(8).trim();
                } else if (lower.startsWith("sec-websocket-protocol:")) {
                    websocketProtocolHeader = line.substring(23).trim();
                } else if (lower.startsWith("range:")) {
                    rangeHeader = line.substring(6).trim();
                } else if (lower.startsWith("if-none-match:")) {
                    ifNoneMatchHeader = line.substring(14).trim();
                } else if (lower.startsWith("cookie:")) {
                    cookieHeader = line.substring(7).trim();
                } else if (lower.startsWith("authorization:")) {
                    authHeader = line.substring(14).trim();
                } else if (lower.startsWith("accept-language:")) {
                    // First-touch locale hint. Only used if the user has never
                    // explicitly chosen via the picker (LocaleManager file empty).
                    String al = line.substring(16).trim();
                    if (!al.isEmpty()) {
                        try { LocaleManager.fromAcceptLanguage(al); } catch (Exception ignored) {}
                        // Note: we don't auto-persist Accept-Language; the picker
                        // is the only thing that writes the state file. JS-side
                        // navigator.language detection feeds the picker.
                    }
                } else if (lower.startsWith("host:")) {
                    hostHeader = line.substring(5).trim();
                } else if (lower.startsWith("origin:")) {
                    originHeader = line.substring(7).trim();
                } else if (lower.startsWith("x-forwarded-for:")) {
                    hasTunnelHeaders = true;
                    forwardedFor = line.substring(16).trim();
                } else if (lower.startsWith("x-forwarded-host:")) {
                    hasTunnelHeaders = true;
                    // Header value can be a comma list ("a.example, b.example")
                    // when chained through multiple proxies. The leftmost entry
                    // is the original client-facing host.
                    String v = line.substring(17).trim();
                    int comma = v.indexOf(',');
                    forwardedHostHeader = (comma > 0 ? v.substring(0, comma) : v).trim();
                } else if (lower.startsWith("x-forwarded-proto:")
                        || lower.startsWith("x-real-ip:")
                        || lower.startsWith("forwarded:")
                        || lower.startsWith("cf-connecting-ip:")
                        || lower.startsWith("cf-ray:")
                        || lower.startsWith("cf-visitor:")) {
                    hasTunnelHeaders = true;
                }
            }
            
            // Read POST body if present
            // SOTA: Loop read for large payloads (e.g., base64 image uploads)
            // BufferedReader.read() may return fewer chars than requested in a single call
            // Default hard cap 16 MB — the largest legitimate generic payload is a
            // base64-encoded 8 MB asset (~10.7 MB). Above this we 413 instead of
            // letting an attacker allocate arbitrary heap on the daemon process.
            //
            // EXCEPTION: the audio-library upload accepts short video clips (48 MB max,
            // see AudioApiHandler.MAX_AUDIO_BYTES) as a base64 JSON body (~64 MB) — raise
            // the cap to 72 MB for JUST that one endpoint so a legitimate clip isn't
            // pre-rejected here, while every other endpoint keeps the tight 16 MB cap.
            // The body is still buffered then decoded (peak ~200 MB transient for a
            // 48 MB clip), which is safe on this head unit; a materially larger limit
            // would require a stream-to-disk upload rewrite rather than this bump.
            final boolean isAudioUpload = requestLine.startsWith("POST /api/audio/library")
                    && !requestLine.startsWith("POST /api/audio/library/"); // the /play,/stop subpaths stay tight
            final int MAX_BODY_BYTES = isAudioUpload ? (72 * 1024 * 1024) : (16 * 1024 * 1024);
            String body = null;
            if (contentLength > MAX_BODY_BYTES) {
                HttpResponse.sendError(out, 413, "Payload too large");
                client.close();
                return;
            }
            if (contentLength > 0) {
                // Content-Length is in BYTES per RFC 7230. BufferedReader is a
                // character stream — UTF-8 multi-byte chars (e.g. "₹" = U+20B9
                // = 3 bytes but 1 char) decode 1:1 into Java chars, so reading
                // `contentLength` chars on a body containing non-ASCII would
                // block forever waiting for chars that will never come (the
                // browser already sent the full byte count, but it represents
                // fewer chars). Loop until the UTF-8 byte count of what we've
                // accumulated equals the declared Content-Length.
                //
                // Headers are guaranteed ASCII (HTTP/1.1 spec) so the
                // BufferedReader.readLine() loop above is unaffected.
                int CHUNK = Math.min(contentLength, 4096);
                char[] bodyChars = new char[CHUNK];
                StringBuilder sb = new StringBuilder(contentLength);
                int bytesAccumulated = 0;
                while (bytesAccumulated < contentLength) {
                    int read = reader.read(bodyChars, 0, CHUNK);
                    if (read == -1) break;  // EOF
                    sb.append(bodyChars, 0, read);
                    // Encode just the chars read this iteration to count their
                    // UTF-8 byte length. Cheaper than re-encoding the whole
                    // accumulator each tick.
                    bytesAccumulated += new String(bodyChars, 0, read).getBytes("UTF-8").length;
                }
                body = sb.toString();
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                HttpResponse.sendError(out, 400, "Bad Request");
                client.close();
                return;
            }

            String method = parts[0];
            String path = parts[1];
            
            // Extend timeout for slow BYD cloud API calls (login + verify can take 10-15s)
            if (path.startsWith("/api/bydcloud") || path.startsWith("/api/vehicle/lock") || 
                path.startsWith("/api/vehicle/unlock") || path.startsWith("/api/vehicle/flash")) {
                client.setSoTimeout(60000);
            }
            
            // WebSocket upgrades (check auth first). /ws is the H.264 stream;
            // /ws/communicate is the inbound PCM push-to-talk stream. Query
            // tokens are supported because browser WebSocket clients cannot set
            // arbitrary Authorization headers.
            String wsPathOnly = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
            if ((wsPathOnly.equals("/ws")
                    || wsPathOnly.equals("/ws/communicate")
                    || wsPathOnly.equals("/ws/cabin-audio")
                    || wsPathOnly.equals(GenAiVoiceWebSocket.PATH)
                    || wsPathOnly.equals(GenAiChatWebSocket.PATH)
                    || wsPathOnly.equals(RemoteDevViewWebSocketStream.PATH))
                    && websocketKey != null
                    && "websocket".equalsIgnoreCase(upgradeHeader)) {
                if (GenAiRequestSecurity.isGenAiPath(wsPathOnly)
                        && !GenAiRequestSecurity.isAllowedOrigin(
                                originHeader, hostHeader, forwardedHostHeader)) {
                    HttpResponse.sendError(out, 403, "Forbidden origin");
                    client.close();
                    return;
                }
                // Promote ?token= query param into a synthetic Authorization header
                // so AuthMiddleware's existing Bearer-token path handles it.
                String wsAuthHeader = authHeader;
                if (wsAuthHeader == null && path.contains("?")) {
                    String query = path.substring(path.indexOf("?") + 1);
                    for (String param : query.split("&")) {
                        int eq = param.indexOf('=');
                        if (eq > 0 && "token".equals(param.substring(0, eq))) {
                            wsAuthHeader = "Bearer " + java.net.URLDecoder.decode(
                                param.substring(eq + 1), "UTF-8");
                            break;
                        }
                    }
                }
                boolean wsAuthenticated = wsPathOnly.equals(RemoteDevViewWebSocketStream.PATH)
                        || GenAiRequestSecurity.isGenAiPath(wsPathOnly)
                    ? AuthMiddleware.checkJwtOnly(
                        wsPathOnly, cookieHeader, wsAuthHeader, out)
                    : AuthMiddleware.checkAuth(wsPathOnly, cookieHeader, wsAuthHeader, out,
                        client.getRemoteSocketAddress(), hasTunnelHeaders);
                if (!wsAuthenticated) {
                    client.close();
                    return;
                }
                if (wsPathOnly.equals(RemoteDevViewWebSocketStream.PATH)) {
                    String devViewSession = RemoteDevViewWebSocketStream
                        .sessionFromProtocols(websocketProtocolHeader);
                    if (devViewSession == null
                            || !RemoteDevViewApiHandler.validateStreamSession(devViewSession)) {
                        HttpResponse.sendError(out, 403,
                            "Developer-view session is invalid or expired");
                        client.close();
                        return;
                    }
                    RemoteDevViewWebSocketStream.handle(client, websocketKey, devViewSession);
                } else if (wsPathOnly.equals("/ws/communicate")) {
                    RemoteCommunicationWebSocket.handle(client, websocketKey);
                } else if (wsPathOnly.equals("/ws/cabin-audio")) {
                    CabinAudioWebSocket.handle(client, websocketKey);
                } else if (wsPathOnly.equals(GenAiVoiceWebSocket.PATH)) {
                    GenAiVoiceWebSocket.handle(
                            client, websocketKey,
                            GenAiApiHandler.queryParam(path, "lang"));
                } else if (wsPathOnly.equals(GenAiChatWebSocket.PATH)) {
                    GenAiChatWebSocket.handle(client, websocketKey);
                } else {
                    handleWebSocketUpgrade(client, websocketKey);
                }
                return;
            }

            // Route auth endpoints (all public). The /auth/token endpoint is
            // rate-limited by client identity (real IP via X-Forwarded-For if
            // present, else socket) to slow brute-force attempts via tunnels.
            if (path.startsWith("/auth/")) {
                String identity;
                if (forwardedFor != null && !forwardedFor.isEmpty()) {
                    int comma = forwardedFor.indexOf(',');
                    identity = (comma > 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
                } else {
                    identity = String.valueOf(client.getRemoteSocketAddress());
                }
                AuthApiHandler.handle(method, path, body, out, identity);
                client.close();
                return;
            }
            
            // Serve login page (public) - strip query string for matching
            String pathOnly = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
            if (pathOnly.equals("/login") || pathOnly.equals("/login.html")) {
                if (!serveStaticFile(out, "local/login.html")) {
                    HttpResponse.sendError(out, 404, "login.html not found");
                }
                client.close();
                return;
            }
            
            boolean genAiRequest = GenAiRequestSecurity.isGenAiPath(pathOnly);
            if (genAiRequest && !GenAiRequestSecurity.isAllowedOrigin(
                    originHeader, hostHeader, forwardedHostHeader)) {
                HttpResponse.sendError(out, 403, "Forbidden origin");
                client.close();
                return;
            }

            // Handle CORS preflight (OPTIONS) requests for cross-origin webapp access.
            // Browsers send OPTIONS before POST/PUT/DELETE with Content-Type: application/json.
            // The in-app WebView is same-origin so it skips this, but the external webapp needs it.
            // Must be handled BEFORE auth check — preflight requests don't carry cookies/tokens.
            if (method.equals("OPTIONS")) {
                if (genAiRequest) {
                    HttpResponse.sendCorsPreflightResponse(out, originHeader);
                } else {
                    HttpResponse.sendCorsPreflightResponse(out);
                }
                client.close();
                return;
            }
            
            // Check authentication for all other paths
            boolean remoteDevRequest = pathOnly.equals("/remote-dev-view")
                    || pathOnly.equals("/remote-dev-view.html")
                    || pathOnly.startsWith("/api/dev-view/");
            boolean authenticated = remoteDevRequest || genAiRequest
                ? AuthMiddleware.checkJwtOnly(path, cookieHeader, authHeader, out)
                : AuthMiddleware.checkAuth(path, cookieHeader, authHeader, out,
                    client.getRemoteSocketAddress(), hasTunnelHeaders);
            if (!authenticated) {
                client.close();
                return;
            }

            // Route to modular handlers first
            if (routeToHandlers(method, path, body, rangeHeader, ifNoneMatchHeader, out)) {
                // Handled by a modular handler
            }
            // Static pages
            else if (path.equals("/") || path.equals("/index.html")) {
                if (!serveStaticFile(out, "local/index.html")) {
                    HttpResponse.sendError(out, 404, "index.html not found");
                }
            } else if (path.equals("/assistant.html") || path.equals("/assistant")) {
                if (!serveStaticFile(out, "local/assistant.html")) {
                    HttpResponse.sendError(out, 404, "assistant.html not found");
                }
            } else if (path.equals("/live-view.html") || path.equals("/live-view")) {
                if (!serveStaticFile(out, "local/live-view.html")) {
                    HttpResponse.sendError(out, 404, "live-view.html not found");
                }
            } else if (path.equals("/communicate.html") || path.equals("/communicate")) {
                if (!serveStaticFile(out, "local/communicate.html")) {
                    HttpResponse.sendError(out, 404, "communicate.html not found");
                }
            } else if (path.startsWith("/manifest.json")) {
                if (!serveStaticFile(out, "local/manifest.json")) {
                    HttpResponse.sendError(out, 404, "manifest.json not found");
                }
            } else if (path.startsWith("/sw.js")) {
                if (!serveStaticFile(out, "local/sw.js")) {
                    HttpResponse.sendError(out, 404, "sw.js not found");
                }
            } else if (path.equals("/recording.html") || path.equals("/recording")) {
                if (!serveStaticFile(out, "local/recording.html")) {
                    HttpResponse.sendError(out, 404, "recording.html not found");
                }
            } else if (path.equals("/surveillance.html") || path.equals("/surveillance")) {
                if (!serveStaticFile(out, "local/surveillance.html")) {
                    HttpResponse.sendError(out, 404, "surveillance.html not found");
                }
            } else if (path.equals("/events.html") || path.equals("/events") || 
                       path.startsWith("/events.html?") || path.startsWith("/events?")) {
                if (!serveStaticFile(out, "local/events.html")) {
                    HttpResponse.sendError(out, 404, "events.html not found");
                }
            } else if (path.equals("/performance.html") || path.equals("/performance")) {
                if (!serveStaticFile(out, "local/performance.html")) {
                    HttpResponse.sendError(out, 404, "performance.html not found");
                }
            } else if (path.equals("/remote-dev-view.html") || path.equals("/remote-dev-view")) {
                if (!serveStaticFile(out, "local/remote-dev-view.html")) {
                    HttpResponse.sendError(out, 404, "remote-dev-view.html not found");
                }
            } else if (path.equals("/abrp.html") || path.equals("/abrp")) {
                if (!serveStaticFile(out, "local/abrp.html")) {
                    HttpResponse.sendError(out, 404, "abrp.html not found");
                }
            } else if (path.equals("/mqtt.html") || path.equals("/mqtt")) {
                if (!serveStaticFile(out, "local/mqtt.html")) {
                    HttpResponse.sendError(out, 404, "mqtt.html not found");
                }
            } else if (path.equals("/trips.html") || path.equals("/trips")) {
                if (!serveStaticFile(out, "local/trips.html")) {
                    HttpResponse.sendError(out, 404, "trips.html not found");
                }
            } else if (path.equals("/charging.html") || path.equals("/charging")) {
                if (!serveStaticFile(out, "local/charging.html")) {
                    HttpResponse.sendError(out, 404, "charging.html not found");
                }
            } else if (path.equals("/automations.html") || path.equals("/automations")) {
                if (!serveStaticFile(out, "local/automations.html")) {
                    HttpResponse.sendError(out, 404, "automations.html not found");
                }
            } else if (path.equals("/vehicle-control.html") || path.equals("/vehicle-control")) {
                if (!serveStaticFile(out, "local/vehicle-control.html")) {
                    HttpResponse.sendError(out, 404, "vehicle-control.html not found");
                }
            } else if (path.equals("/telegram.html") || path.equals("/telegram")) {
                if (!serveStaticFile(out, "local/telegram.html")) {
                    HttpResponse.sendError(out, 404, "telegram.html not found");
                }
            } else if (path.equals("/byd-cloud.html") || path.equals("/byd-cloud")) {
                if (!serveStaticFile(out, "local/byd-cloud.html")) {
                    HttpResponse.sendError(out, 404, "byd-cloud.html not found");
                }
            } else if (path.equals("/road-sense.html") || path.equals("/road-sense")) {
                if (!serveStaticFile(out, "local/road-sense.html")) {
                    HttpResponse.sendError(out, 404, "road-sense.html not found");
                }
            } else if (path.equals("/network.html") || path.equals("/network")) {
                if (!serveStaticFile(out, "local/network.html")) {
                    HttpResponse.sendError(out, 404, "network.html not found");
                }
            } else if (path.equals("/notifications.html") || path.equals("/notifications")) {
                if (!serveStaticFile(out, "local/notifications.html")) {
                    HttpResponse.sendError(out, 404, "notifications.html not found");
                }
            } else if (path.equals("/seat-positions.html") || path.equals("/seat-positions")) {
                if (!serveStaticFile(out, "local/seat-positions.html")) {
                    HttpResponse.sendError(out, 404, "seat-positions.html not found");
                }
            } else if (path.equals("/key-mapping.html") || path.equals("/key-mapping")) {
                if (!serveStaticFile(out, "local/key-mapping.html")) {
                    HttpResponse.sendError(out, 404, "key-mapping.html not found");
                }
            } else if (path.equals("/about.html") || path.equals("/about")) {
                if (!serveStaticFile(out, "local/about.html")) {
                    HttpResponse.sendError(out, 404, "about.html not found");
                }
            } else if (path.equals("/credits.json")) {
                if (!serveStaticFile(out, "local/credits.json")) {
                    HttpResponse.sendError(out, 404, "credits.json not found");
                }
            } else if (path.equals("/api/i18n/lang")) {
                // GET → current locale; POST {"lang":"zh-CN"} → persist + echo
                if (method.equals("GET")) {
                    JSONObject resp = new JSONObject();
                    resp.put("lang", LocaleManager.get());
                    JSONObject supported = new JSONObject();
                    for (String s : LocaleManager.SUPPORTED) supported.put(s, true);
                    resp.put("supported", supported);
                    HttpResponse.sendJson(out, resp.toString());
                } else if (method.equals("POST")) {
                    String want;
                    try {
                        want = new JSONObject(body).optString("lang", "");
                    } catch (Exception e) { want = ""; }
                    String resolved = LocaleManager.set(want);
                    HttpResponse.sendJson(out, "{\"lang\":\"" + resolved + "\"}");
                } else {
                    HttpResponse.sendError(out, 405, "Method Not Allowed");
                }
            } else if (path.startsWith("/i18n/")) {
                // Catalog JSON: /i18n/en.json, /i18n/zh-CN.json, …
                // 404s on unsupported tags so the runtime falls back to en.
                String tag = path.substring(6);
                int dot = tag.lastIndexOf('.');
                String base = dot > 0 ? tag.substring(0, dot) : tag;
                if (!LocaleManager.isSupported(base)) {
                    HttpResponse.sendError(out, 404, "Unknown locale");
                } else if (!serveStaticFile(out, "i18n/" + tag, ifNoneMatchHeader)) {
                    HttpResponse.sendError(out, 404, "Catalog missing: " + tag);
                }
            } else if (path.startsWith("/shared/") || path.startsWith("/local/")) {
                // Strip ?query and #fragment so cache-busting versions like
                // ?v=12 resolve to the same file on disk.
                String filePath = path.substring(1);
                int q = filePath.indexOf('?');
                if (q >= 0) filePath = filePath.substring(0, q);
                int h = filePath.indexOf('#');
                if (h >= 0) filePath = filePath.substring(0, h);
                if (!serveStaticFile(out, filePath, ifNoneMatchHeader)) {
                    HttpResponse.sendError(out, 404, "Not Found: " + path);
                }
            } else if (path.startsWith("/h264/")) {
                // Deprecated HTTP streaming
                JSONObject response = new JSONObject();
                response.put("error", "HTTP streaming deprecated. Use WebSocket on port 8887");
                response.put("wsUrl", "ws://" + client.getLocalAddress().getHostAddress() + ":8887");
                HttpResponse.sendJson(out, response.toString());
            } else if (path.startsWith("/view/")) {
                // Legacy view page - redirect
                HttpResponse.sendHtml(out, "<html><head><meta http-equiv='refresh' content='0;url=/'></head></html>");
            } else {
                HttpResponse.sendError(out, 404, "Not Found");
            }
        } catch (Exception e) {
            CameraDaemon.log("HTTP error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception e) {}
        }
    }

    /**
     * Path prefixes an automation {@code ApiAction} is permitted to reach through the auth-free
     * {@link #automationApiRequest} bypass. This is a hard security boundary: automationApiRequest
     * skips {@link AuthMiddleware} entirely, so this allowlist — NOT the curated catalog in
     * Actions.java — is what keeps an ApiAction (existing or a carelessly-added future one) from
     * reaching sensitive surfaces like /api/debug/* (car-property / light / autoservice writes),
     * /api/backup/ (device key material), /api/update/ (APK install), /api/telegram/ (bot token),
     * /api/oem-dashcam/ (shells to pm), /api/logs or /api/keymap. Keep it as tight as the curated
     * automation actions genuinely need.
     */
    private static final String[] AUTOMATION_ALLOWED_PREFIXES = {
        "/api/vehicle/",       // vehicle controls: setting, window, climate, seat
        "/api/recording/mode", // recording mode
        "/api/apps/launch",    // open-app action: launch a user-selected app (NOT /api/apps/list)
        "/api/camview/",       // camera-view show/hide (native lane, shares blind-spot pipeline)
        // Saved seat/mirror position recall. EXACT path, not the /api/positions/ prefix: the
        // sibling endpoints create, overwrite and delete stored positions, which an ApiAction
        // has no business reaching. startsWith() on the query-stripped path matches
        // /api/positions/apply?id=.. and nothing else under /api/positions/.
        "/api/positions/apply",
        "/api/bs/",            // blind-spot: enable/disable/hide/view/geometry/target/tweak
                               // (same native camera lane as /api/camview/ above — the card's
                               // own on/off + view knobs, no new capability class)
    };

    /** Whether an automation-originated request path is inside the allowlist above. */
    private static boolean isAutomationAllowed(String path) {
        if (path == null) return false;
        int q = path.indexOf('?');
        String clean = q >= 0 ? path.substring(0, q) : path;
        if (clean.startsWith("/api/surveillance/")) {
            return clean.equals("/api/surveillance/enable")
                    || clean.equals("/api/surveillance/disable")
                    || clean.equals("/api/surveillance/config")
                    || clean.equals("/api/surveillance/${action}");
        }
        for (String prefix : AUTOMATION_ALLOWED_PREFIXES) {
            if (clean.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Used for automations to have an API action without going through authentication.
     * The request is served in-process (no socket, so this is unreachable from the network) and
     * is gated by {@link #AUTOMATION_ALLOWED_PREFIXES} so the auth bypass can only ever hit the
     * curated automation control surface, never the sensitive /api/debug|backup|update|... paths.
     *
     * @return The HTTP response as a String, or null if denied by the allowlist or on error
     */
    public String automationApiRequest(String method, String path, String body) {
        if (!isAutomationAllowed(path)) {
            CameraDaemon.log("AUTH: automation API request denied (not in allowlist): " + method + " " + path);
            return null;
        }
        OutputStream out = new ByteArrayOutputStream();
        try {
            if (!routeToHandlers(method, path, body, null, null, out)) return null;
        } catch (Exception e) {
            return null;
        }
        return out.toString();
    }

    /**
     * Routes requests to modular API handlers.
     * @return true if handled by a handler
     */
    private boolean routeToHandlers(String method, String path, String body, String rangeHeader,
                                     String ifNoneMatchHeader, OutputStream out) throws Exception {
        // Core camera APIs (kept inline for simplicity)
        if (path.startsWith("/snapshot/")) {
            int camId = Integer.parseInt(path.substring(10));
            sendSnapshot(out, camId);
            return true;
        } else if (path.equals("/status")) {
            sendStatus(out);
            return true;
        } else if (path.startsWith("/api/start/")) {
            int camId = Integer.parseInt(path.substring(11));
            CameraDaemon.startCamera(camId, true, false);
            HttpResponse.sendJson(out, "{\"status\":\"ok\",\"action\":\"start\",\"camera\":" + camId + "}");
            return true;
        } else if (path.startsWith("/api/view/")) {
            int camId = Integer.parseInt(path.substring(10));
            CameraDaemon.startCamera(camId, true, true);
            HttpResponse.sendJson(out, "{\"status\":\"ok\",\"action\":\"view\",\"camera\":" + camId + "}");
            return true;
        } else if (path.startsWith("/api/stop/")) {
            int camId = Integer.parseInt(path.substring(10));
            CameraDaemon.stopCamera(camId);
            HttpResponse.sendJson(out, "{\"status\":\"ok\",\"action\":\"stop\",\"camera\":" + camId + "}");
            return true;
        } else if (path.equals("/api/stopall")) {
            CameraDaemon.stopAllCameras();
            HttpResponse.sendJson(out, "{\"status\":\"ok\",\"action\":\"stopall\"}");
            return true;
        } else if (path.equals("/api/recording/mode")) {
            // Get/Set recording mode
            if (method.equals("GET")) {
                String currentMode = CameraDaemon.getRecordingMode();
                HttpResponse.sendJson(out, "{\"status\":\"ok\",\"mode\":\"" + currentMode + "\"}");
            } else if (method.equals("POST")) {
                JSONObject json = new JSONObject(body);
                String mode = json.optString("mode", "");
                if (!mode.isEmpty()) {
                    CameraDaemon.setRecordingMode(mode);
                    HttpResponse.sendJson(out, "{\"status\":\"ok\",\"mode\":\"" + mode + "\"}");
                } else {
                    HttpResponse.sendJson(out, "{\"status\":\"error\",\"message\":\"No mode specified\"}");
                }
            } else {
                HttpResponse.sendError(out, 405, "Method Not Allowed");
            }
            return true;
        }

        // Launcher aggregation API — stable public /api/launcher/v1/* face over
        // existing internal handlers (summary/traffic/apps/vehicle/appearance).
        // Additive, read-mostly; dispatched here like the other modular handlers.
        // See LauncherApiHandler + docs/LAUNCHER_SPEC.md §2.
        if (path.startsWith("/api/launcher/")) {
            return LauncherApiHandler.handle(method, path, body, out);
        }

        // Authenticated, read-only metadata for the web About page. VIN deliberately never enters
        // this HTTP response; the native About screen is the only surface that can request it.
        if (path.equals("/api/about/vehicle-info")) {
            return AboutApiHandler.handle(method, path, body, out);
        }

        // Recordings API (with Range header support for video seeking) + thumbnails + event timelines
        if (path.startsWith("/api/recordings") || path.startsWith("/video/") ||
            path.startsWith("/thumb/") || path.startsWith("/api/events/")) {
            return RecordingsApiHandler.handleWithRange(method, path, body, rangeHeader, ifNoneMatchHeader, out);
        }
        
        // Surveillance API
        if (path.startsWith("/api/surveillance/safe-locations")) {
            return SafeLocationApiHandler.handle(method, path, body, out);
        }
        if (path.startsWith("/api/surveillance")) {
            return SurveillanceApiHandler.handle(method, path, body, out);
        }
        
        // BYD Cloud API
        if (path.startsWith("/api/bydcloud")) {
            return BydCloudApiHandler.handle(method, path, body, out);
        }
        
        // Streaming API
        if (path.startsWith("/api/stream")) {
            return StreamingApiHandler.handle(method, path, body, out);
        }

        // Blind-spot dedicated-pipeline API (separate from /api/stream so it
        // never shares the live-view stream's view/quality state).
        if (path.startsWith("/api/bs/") || path.startsWith("/api/camview/")) {
            return StreamingApiHandler.handleBlindSpot(method, path, body, out);
        }

        // GPS API
        if (path.startsWith("/api/gps")) {
            return GpsApiHandler.handle(method, path, body, out);
        }
        
        // Quality Settings API (includes storage settings)
        if (path.startsWith("/api/settings/")) {
            return QualitySettingsApiHandler.handle(method, path, body, out);
        }
        
        // ABRP API
        if (path.startsWith("/api/abrp/")) {
            return AbrpApiHandler.handle(method, path, body, out);
        }
        
        // MQTT API
        if (path.startsWith("/api/mqtt/")) {
            return MqttApiHandler.handle(method, path, body, out);
        }

        // Automations API — plus the reusable Action Groups CRUD, which
        // AutomationApiHandler.handle() also implements but lives under the SEPARATE
        // /api/action-groups prefix (hyphen), so it must be routed here explicitly.
        // Without this line those requests fell through to 404 → "failed to save" in the
        // group editor.
        if (path.startsWith("/api/automations") || path.startsWith("/api/action-groups")) {
            return AutomationApiHandler.handle(method, path, body, out);
        }

        // GenAI BYOK API. Authentication is enforced by HttpServer before
        // modular handlers are reached; credentials are never returned.
        if (path.startsWith("/api/genai/")) {
            return GenAiApiHandler.handle(method, path, body, out);
        }

        // Community Automations API (browse / publish / import shared automations)
        if (path.startsWith("/api/community/")) {
            return com.overdrive.app.community.CommunityApiHandler.handle(method, path, body, out);
        }

        // RoadSense API (delete-local / delete-cloud data actions)
        if (path.startsWith("/api/roadsense/")) {
            return RoadSenseApiHandler.handle(method, path, body, out);
        }

        // RoadSense Map routing (BYOK) config API
        if (path.startsWith("/api/navmap/")) {
            return NavMapApiHandler.handle(method, path, body, out);
        }

        // Telegram bot config API (token / pairing PIN / owner / preferences).
        // Reads + writes /data/local/tmp/telegram_config.properties directly
        // — same file the native TelegramSettingsFragment writes via ADB
        // shell. The HTTP daemon runs as UID shell so direct writes work.
        if (path.startsWith("/api/telegram/")) {
            return TelegramApiHandler.handle(method, path, body, out);
        }
        
        // Trip Analytics API
        if (path.startsWith("/api/trips")) {
            com.overdrive.app.trips.TripAnalyticsManager tam = CameraDaemon.getTripAnalyticsManager();
            if (tam != null) {
                com.overdrive.app.trips.TripApiHandler handler = new com.overdrive.app.trips.TripApiHandler(tam);
                org.json.JSONObject result = handler.handleRequest(path, method, null, body);
                if (result != null) {
                    int status = result.optInt("_status", 200);
                    result.remove("_status");
                    if (status == 200) {
                        HttpResponse.sendJson(out, result.toString());
                    } else {
                        HttpResponse.sendError(out, status, result.toString());
                    }
                    return true;
                } else {
                    HttpResponse.sendJsonError(out, "Request failed or path not recognized");
                    return true;
                }
            } else {
                HttpResponse.sendJsonError(out, "Trip analytics not initialized");
                return true;
            }
        }

        // Charging Analytics API
        if (path.startsWith("/api/charging")) {
            com.overdrive.app.charging.ChargingSessionManager csm = CameraDaemon.getChargingSessionManager();
            if (csm != null) {
                com.overdrive.app.charging.ChargingApiHandler handler =
                    new com.overdrive.app.charging.ChargingApiHandler(
                        csm,
                        CameraDaemon::getTripAnalyticsManager);
                org.json.JSONObject result = handler.handleRequest(path, method, null, body);
                if (result != null) {
                    int status = result.optInt("_status", 200);
                    result.remove("_status");
                    if (status == 200) {
                        HttpResponse.sendJson(out, result.toString());
                    } else {
                        HttpResponse.sendError(out, status, result.toString());
                    }
                    return true;
                } else {
                    HttpResponse.sendJsonError(out, "Charging handler returned null");
                    return true;
                }
            } else {
                HttpResponse.sendJsonError(out, "Charging analytics not initialized");
                return true;
            }
        }

        // Audio/video library raw stream — Range-aware so a streaming MediaPlayer
        // (Play Video's VideoView / the audio service) can seek to an MP4's moov atom.
        // Routed here (ahead of the generic /api/audio/ handler) because that handler's
        // signature has no rangeHeader; without 206 support, non-faststart MP4s never
        // prepare() and "Play Video" did nothing.
        if (path.startsWith("/api/audio/library/raw") && method.equals("GET")) {
            return AudioApiHandler.handleRawRanged(path, rangeHeader, out);
        }

        // Audio API (library playback + AVAS speaker)
        if (path.startsWith("/api/audio/")) {
            return AudioApiHandler.handle(method, path, body, out);
        }

        // Dedicated remote voice/message control surface.
        if (path.startsWith("/api/communicate/")) {
            return RemoteCommunicationApiHandler.handle(method, path, body, out);
        }

        // Vehicle Control API
        if (path.startsWith("/api/vehicle")) {
            return VehicleControlApiHandler.handle(method, path, body, out);
        }

        // Tyre pressure thresholds (user-configurable per-axle warn/critical
        // limits). Its own prefix rather than under /api/vehicle/ because it is
        // pure configuration — no BYD SDK actuation — and is read by the
        // notification path as well as the Vehicle Control UI.
        if (path.startsWith("/api/tyres/")) {
            return TyreLimitsApiHandler.handle(method, path, body, out);
        }

        // Key-mapping actuation — physical-key bindings fired by the app-process
        // accessibility service POST here so BYD SDK writes run in the daemon UID.
        if (path.startsWith("/api/keymap")) {
            return KeymapApiHandler.handle(method, path, body, out);
        }

        // User-defined quick-control buttons. Stores the same action payload a key binding
        // does and fires it through KeymapApiHandler.runBoundAction, so a button inherits the
        // physical key's exact privileges (curated catalog, API allowlist, shell gate) rather
        // than adding a second actuation path.
        if (path.startsWith("/api/quick-controls")) {
            return QuickControlsApiHandler.handle(method, path, body, out);
        }

        // Installed-app enumeration + launch — shared by the key-mapping app picker
        // and the automation "open app" action (POST /api/apps/launch is allowlisted
        // for automation ApiActions in AUTOMATION_ALLOWED_PREFIXES).
        if (path.startsWith("/api/apps/")) {
            return AppsApiHandler.handle(method, path, body, out);
        }

        // Autoservice AIDL debug sweep — read-only reach probes for the BYD
        // autoservice surface. Lets the operator hand-pick an `area`/`cmd`
        // pair and observe what the bridge reads, since `service call` from
        // a shell can't write the interface token and always returns INVALID.
        if (path.startsWith("/api/debug/autoservice")) {
            return AutoServiceDebugApiHandler.handle(method, path, body, out);
        }

        // CarPropertyBridge debug — read/write DiCarServer's
        // ICarPropertyService via the local CarServiceProvider, no cloud
        // round-trip. Writes still gate on per-property permissions, so
        // sigperm-protected actuators (BYDAUTO_BODYWORK_SET et al) won't
        // succeed without a platform-signed APK; reads of non-sigperm
        // properties work fine.
        if (path.startsWith("/api/debug/car-property")) {
            return CarPropertyDebugApiHandler.handle(method, path, body, out);
        }

        // Hazard / turn-signal / fog actuation probe — tests whether the BYD
        // light HAL accepts a generic set() from our uid-2000 daemon.
        // Light-only, self-resetting, confirm=YES gated.
        // See LightDebugApiHandler / HazardLightProbe.
        if (path.startsWith("/api/debug/light/")) {
            return LightDebugApiHandler.handle(method, path, body, out);
        }

        // OverDrive-native seat/mirror POSITIONS store (feature: seat positions).
        // list / capture (fired by the long-press a11y hook) / apply / delete.
        // Runs in the daemon (only uid that can read/write BYD geometry).
        if (path.startsWith("/api/positions")) {
            return PositionsApiHandler.handle(method, path, body, out);
        }

        // Bodywork seat/keyfob feature-id READS — read-only probe of the
        // absolute seat geometry (System B) and the keyfob-identity ids via
        // BYDAutoBodyworkDevice.get(int[], Class) through a PermissiveContext,
        // the reach the CarProperty bridge lacks. No writes; the seat never
        // moves. See SeatDebugApiHandler / BodyworkSeatProbe.
        if (path.startsWith("/api/debug/seat/")) {
            return SeatDebugApiHandler.handle(method, path, body, out);
        }

        // Telenav OEM user-data AIDL — READ-ONLY spike: bind TnNaviService and read
        // the favourite buckets + recents to confirm the bind and learn the heart's
        // FavoriteType. No writes. See TelenavDebugApiHandler / TelenavClient.
        if (path.startsWith("/api/debug/telenav/") || path.startsWith("/api/telenav/")) {
            return TelenavDebugApiHandler.handle(method, path, body, out);
        }
        // Radar blind-spot ALERT register probe — read-only. The `blindSpot` automation
        // signal has never been confirmed on a car, and its whole read path logs at DEBUG
        // (stripped by R8 in release), so the values have to come back as JSON.
        // See AdasDebugApiHandler / AdasBlindSpotProbe.
        if (path.startsWith("/api/debug/adas/")) {
            return AdasDebugApiHandler.handle(method, path, body, out);
        }

        // THROWAWAY de-risk spike for the native SurfaceControl blind-spot path:
        // proves whether a non-fullscreen, GL-fed, positioned SurfaceControl layer
        // composites on this firmware. Renders a red 400x300 box at (100,100) for
        // ~6s and returns a capability report. Remove with BsSurfaceControlSpike.
        if (path.startsWith("/api/debug/bs-spike")) {
            org.json.JSONObject report =
                com.overdrive.app.surveillance.BsSurfaceControlSpike.run();
            HttpResponse.sendJson(out, report.toString());
            return true;
        }

        // Performance API
        if (path.startsWith("/api/performance")) {
            return PerformanceApiHandler.handle(method, path, body, out);
        }

        // Explicitly confirmed app-window capture/control. Its HTTP and
        // WebSocket entry points require a real JWT (no loopback fallback).
        if (path.startsWith("/api/dev-view/")) {
            return RemoteDevViewApiHandler.handle(method, path, body, out);
        }

        // Network & Hotspot API (AP on/off, clients, session stats, data limit, proxy)
        if (path.startsWith("/api/hotspot")) {
            return HotspotApiHandler.handle(method, path, body, out);
        }
        
        // External Storage API (SD card and CDR cleanup)
        if (path.startsWith("/api/storage/external")) {
            return ExternalStorageApiHandler.handle(path, method, body, out);
        }

        // 3D Vehicle Models API (download/persist user-selectable GLB models)
        if (path.startsWith("/api/models/")) {
            return ModelsApiHandler.handle(method, path, body, out);
        }

        // App Update API (check, preview, install, progress)
        if (path.startsWith("/api/update/")) {
            return UpdateApiHandler.handle(method, path, body, out);
        }

        // Settings backup / restore (export + import). Auth-gated by
        // AuthMiddleware (non-public path); the handler additionally rejects
        // PUBLIC/tunnel mode because the bundle carries device key material.
        if (path.startsWith("/api/backup/")) {
            return ConfigBackupApiHandler.handle(method, path, body, out);
        }

        // Diagnostics log API (list daemons + upload a daemon log) — braveheart only
        if (path.startsWith("/api/logs")) {
            return LogsApiHandler.handle(method, path, body, out);
        }

        // Notification API — web push notifications
        if (path.startsWith("/api/notifications") || path.startsWith("/api/push")) {
            return NotificationApiHandler.handle(method, path, body, out);
        }

        // OEM Dashcam settings — currently the "Disable Native DVR" toggle
        // for com.byd.cdr (factory dashcam). Lives on its own prefix instead
        // of /api/settings/ because the handler shells out to `pm` rather
        // than only mutating UCM. See OemDashcamApiHandler.
        if (path.startsWith("/api/oem-dashcam/")) {
            return OemDashcamApiHandler.handle(method, path, body, out);
        }

        return false;
    }
    
    private void sendSnapshot(OutputStream out, int viewId) throws Exception {
        com.overdrive.app.surveillance.GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        if (gpuPipeline == null || gpuPipeline.getCamera() == null) {
            HttpResponse.sendError(out, 404, "GPU pipeline not available for view " + viewId);
            return;
        }

        byte[] frame = gpuPipeline.getCamera().getLatestJpegFrame(viewId);
        if (frame == null) {
            HttpResponse.sendError(out, 404, "No frame available for view " + viewId);
            return;
        }

        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " + frame.length + "\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.flush();
        
        int offset = 0;
        while (offset < frame.length) {
            int count = Math.min(frame.length - offset, 1024);
            out.write(frame, offset, count);
            out.flush();
            offset += count;
        }
    }

    private void sendStatus(OutputStream out) throws Exception {
        JSONObject status = new JSONObject();
        status.put("status", "ok");
        status.put("deviceId", CameraDaemon.getDeviceId());
        try {
            status.put(
                "screenshotPrivacyMode",
                com.overdrive.app.config.UnifiedConfigManager.isScreenshotPrivacyModeEnabled()
            );
        } catch (Exception ignored) {
            // A status response must remain available even if unified config
            // is temporarily unreadable during a cross-process replacement.
            status.put("screenshotPrivacyMode", false);
        }
        status.put("genAiDashboardEnabled",
                GenAiConfig.isDashboardPresentationEnabled());

        // Vehicle-data readiness, surfaced explicitly so the web UI can render
        // a "waiting for vehicle…" state instead of silently leaving every
        // field blank when the BYD binders haven't bound yet (cold-boot race
        // — HTTP comes up well before BydDataCollector finishes ~15 binder
        // lookups). On first hit, give the collector a short window to come
        // online; this resolves the most common "tunnel loads, no data"
        // report without forcing the client to retry.
        boolean vehicleReady = waitForVehicleDataReady(1500);
        status.put("vehicleDataReady", vehicleReady);

        // App version — the installed GitHub release label, read from the
        // world-readable version file written by every install (shared across
        // app + daemon UIDs), falling back to the BuildConfig identity
        // (channel + versionName) when nothing's been installed via the updater
        // or the persisted label is stale/malformed. getDisplayVersionFromFile()
        // → getDisplayVersion(null) → persistedGithubVersion (file-first).
        status.put("appVersion", com.overdrive.app.updater.AppUpdater.getDisplayVersionFromFile());
        status.put("recording", TcpCommandServer.getRecordingCameras());
        status.put("viewing", TcpCommandServer.getViewOnlyCameras());
        status.put("active", TcpCommandServer.getActiveCameras());
        status.put("streaming", TcpCommandServer.getStreamingCameras());
        status.put("available", TcpCommandServer.getAvailableCameras());
        status.put("battery", BatteryMonitor.getBatteryInfo());
        boolean currentAcc = AccMonitor.isAccOn();
        if (CameraDaemon.getRecordingModeManager() != null) {
            currentAcc = CameraDaemon.getRecordingModeManager().isAccOn();
        }
        status.put("acc", currentAcc);
        
        // Safe zone status (so UI can show suppressed state)
        com.overdrive.app.surveillance.SafeLocationManager safeMgr =
            com.overdrive.app.surveillance.SafeLocationManager.getInstance();
        status.put("safeZoneSuppressed", CameraDaemon.isSafeZoneSuppressed());
        status.put("inSafeZone", safeMgr.isInSafeZone());
        if (safeMgr.getCurrentZoneName() != null) {
            status.put("safeZoneName", safeMgr.getCurrentZoneName());
        }
        
        // Vehicle data (charging state and power).
        // Each subsection is wrapped individually — one BYD HAL throwing
        // RemoteException must not zero out unrelated fields. Any failure
        // here is logged so customers reporting "blank data" produce evidence
        // we can act on, instead of silent emptiness.
        com.overdrive.app.charging.ChargingApiHandler.LivePublication
            chargingPublication = null;
        status.put("charging", buildIdleChargingStatus());
        try {
            com.overdrive.app.monitor.VehicleDataMonitor vehicleMonitor =
                com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
            chargingPublication =
                com.overdrive.app.charging.ChargingApiHandler
                    .readLivePublication(
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance());
            status.put("charging", chargingPublication.toStatusJson());
            
            com.overdrive.app.monitor.BatterySocData socData = vehicleMonitor.getBatterySoc();
            if (socData != null) {
                JSONObject soc = new JSONObject();
                soc.put("percent", socData.socPercent);
                soc.put("isLow", socData.isLow);
                soc.put("isCritical", socData.isCritical);
                soc.put("status", socData.getStatus());
                status.put("soc", soc);
            }
            
            com.overdrive.app.monitor.DrivingRangeData rangeData = vehicleMonitor.getDrivingRange();
            if (rangeData != null) {
                JSONObject range = new JSONObject();
                range.put("elecRangeKm", rangeData.elecRangeKm);
                range.put("fuelRangeKm", rangeData.fuelRangeKm);
                range.put("totalRangeKm", rangeData.totalRangeKm);
                range.put("isLow", rangeData.isLow);
                range.put("isCritical", rangeData.isCritical);
                range.put("status", rangeData.getStatus());
                // Only emit fuelPercent for PHEVs — BEVs leave fuelPercent NaN
                // upstream (BydDataCollector gates on nominal capacity < 30 kWh),
                // so the web UI's `if (fuelPct > 0)` guard hides the fuel card.
                if (rangeData.hasFuelPercent()) {
                    range.put("fuelPercent", rangeData.fuelPercent);
                }
                // Drivetrain flag — lets clients render the PHEV EV/fuel
                // breakdown even when the petrol leg currently reads 0 km
                // (empty tank ≠ BEV, fuelRangeKm alone can't distinguish).
                range.put("isPhev", vehicleMonitor.isPhev());
                // No "personalized" block: clients fall back to the HAL range
                // fields above. GET /api/trips/range still serves the learned
                // per-leg estimates directly.
                status.put("range", range);
            }

            // Distance unit preference — "km" or "mi". Derived from user setting
            // (TripConfig.distanceUnit) which overrides auto-detection. The web UI
            // uses this to convert km values for display and pick the right label.
            try {
                com.overdrive.app.byd.BydDataCollector collector =
                        com.overdrive.app.byd.BydDataCollector.getInstance();
                status.put("distanceUnit", (collector != null && collector.isMilesMode()) ? "mi" : "km");
            } catch (Exception ignored) {
                status.put("distanceUnit", "km");
            }

            // Tyre pressure display unit — "kpa" | "psi" | "bar". Same delivery
            // pattern as distanceUnit above: BYD.units.pressureMode in core.js
            // picks it up on every poll so all pages render pressure alike.
            try {
                status.put("pressureUnit",
                        com.overdrive.app.config.UnifiedConfigManager.getTyrePressureUnit());
            } catch (Exception ignored) {
                status.put("pressureUnit", "psi");
            }

            // Active UI locale — exposed so the WebView can sync its i18n
            // state with changes made elsewhere (Android settings drawer,
            // another logged-in client). Always one of LocaleManager.SUPPORTED.
            try {
                status.put("locale", LocaleManager.get());
            } catch (Exception ignored) {
                status.put("locale", "en");
            }
        } catch (Exception e) {
            // Vehicle data not available — surface the cause so a customer
            // report includes the proximate failure (binder gone, SDK class
            // missing, etc.) rather than just "page is blank".
            CameraDaemon.log("status: vehicle data block failed: " + e);
            status.put("vehicleDataError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try {
            JSONObject soh = new JSONObject();
            boolean hasSoh = false;
            
            com.overdrive.app.monitor.SocHistoryDatabase socDb = com.overdrive.app.monitor.SocHistoryDatabase.getInstance();
            com.overdrive.app.abrp.SohEstimator sohEst = socDb != null ? socDb.getSohEstimator() : null;
            double canonicalSoh = sohEst != null ? sohEst.getDisplaySoh() : -1;
            if (canonicalSoh > 0) {
                soh.put("percent", Math.round(canonicalSoh * 10) / 10.0);
                soh.put("estimatedCapacityKwh", Math.round(sohEst.getEstimatedCapacityKwh() * 10) / 10.0);
                soh.put("nominalCapacityKwh", sohEst.getNominalCapacityKwh());
                hasSoh = true;
            }
            
            if (hasSoh) status.put("soh", soh);
        } catch (Exception e) {
            // SOH not available
        }
        
        // GPU surveillance status — true when in sentry/surveillance mode or enabled on DiLink 5
        com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        boolean survEnabled = false;
        try {
            survEnabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
        } catch (Throwable ignored) {}
        status.put("gpuSurveillance", (pipeline != null && pipeline.isSurveillanceMode()) || survEnabled);
        
        // Recording mode details (for status overlay)
        try {
            JSONObject recordingStatus = new JSONObject();
            // Always-live 12V reading, no staleness/caching layer (unlike the
            // BatteryMonitor-backed "battery" block below, whose 30s update
            // gate + 60s staleness cutoff cause a fresh/stale flicker on a
            // platform that only has this car_service source). Unconditional
            // (not inside the rmm != null branch below) since it doesn't
            // depend on RecordingModeManager at all. -1 when unavailable
            // (including simply not being on the DiLink5 platform
            // CarSvcTelemetry targets) — the web UI falls back to the
            // "battery" block in that case.
            recordingStatus.put("voltage12v",
                    (double) com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.batteryVoltage12v());
            // Lets the web UI tell "this device is DiLink5" apart from "this
            // device just happens to have no stock SOC/12V reading right
            // now" — see BYD.core.isDiLink5()/isDiLink5Dash() in the web
            // assets, which gate the car_service-exclusive 12V/SOC cascade
            // on this flag rather than falling back whenever stock is null.
            recordingStatus.put("dilink5", com.overdrive.app.byd.DiLink5Platform.isActive());
            // Fallback-only main-battery SOC (see CarSvcTelemetry.socPercent) —
            // the web UI only synthesizes a status.soc object from this when
            // the stock BatterySocMonitor reading is missing on a DiLink5
            // platform. -1 when unavailable.
            recordingStatus.put("carSvcSoc", com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.socPercent());
            com.overdrive.app.recording.RecordingModeManager rmm = CameraDaemon.getRecordingModeManager();
            if (rmm != null) {
                // car_service (dumpsys) gear fallback — no-op everywhere except the
                // DiLink5 platform this was verified on (see CarSvcTelemetry). On
                // platforms where the vendor gearbox HAL is dead, this corrects the
                // upstream gear value RecordingModeManager already trusts before we
                // read it below, so gearToString (and DRIVE_MODE's own gear gate)
                // pick it up with zero further changes. onGearChanged() is
                // idempotent — a no-op when the gear hasn't actually changed.
                int carSvcGear = com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.gearValue();
                if (carSvcGear >= 1) {
                    rmm.onGearChanged(carSvcGear);
                    // Also feed the shared gear dispatcher (CameraDaemon.
                    // onGearChanged), NOT just RecordingModeManager directly
                    // above. That call only updates the displayed gear —
                    // trip start/stop is driven independently by
                    // GearMonitor's own dedicated poll thread (reflection
                    // into the same vendor gearbox HAL that's dead here),
                    // which is what actually calls onGearChanged() and was
                    // never reached by the direct rmm call. Without this,
                    // the dashboard could show the correct DiLink5 gear
                    // while trips silently never started or stopped, since
                    // GearMonitor's own reads never produced anything
                    // valid (see rmm.getGearMonitorRetryFailures(), often
                    // nonzero). Purely additive: does not replace or
                    // disable GearMonitor, and CameraDaemon.onGearChanged
                    // already dedups a same-value gear internally, so
                    // whichever path fires first/each time is harmless.
                    com.overdrive.app.daemon.CameraDaemon.onGearChanged(carSvcGear);
                }
                recordingStatus.put("configuredMode", rmm.getCurrentMode().name());
                recordingStatus.put("isRecording", pipeline != null && pipeline.isRecording());
                recordingStatus.put("pipelineRunning", pipeline != null && pipeline.isRunning());
                recordingStatus.put("gear", com.overdrive.app.recording.RecordingModeManager.gearToString(rmm.getCurrentGear()));
                recordingStatus.put("accOn", rmm.isAccOn());
                // modeActive distinguishes "user picked CONTINUOUS, recording
                // IS happening" from "user picked CONTINUOUS but activation
                // failed silently and is waiting on the 30s resync retry."
                // Without this, the UI can't tell a stuck activation from a
                // normal cold-start delay.
                recordingStatus.put("modeActive", rmm.isModeActive());
                // wedged: modeActive is true (we think we're recording) but the
                // encoder is structurally stuck — nothing is being written. Lets
                // the status overlay / web card paint the RED fault state instead
                // of a false GREEN "recording" during a stuck activation. Always
                // false for PROXIMITY_GUARD and for a healthy/deferred recorder.
                recordingStatus.put("wedged", rmm.isRecordingWedged());
                // Diagnostic: number of consecutive GearMonitor.start() failures
                // from the resync ticker. 0 = healthy; positive = HAL flaky.
                int gmRetries = rmm.getGearMonitorRetryFailures();
                if (gmRetries > 0) {
                    recordingStatus.put("gearMonitorRetryFailures", gmRetries);
                }
            } else {
                recordingStatus.put("configuredMode", "UNKNOWN");
                recordingStatus.put("isRecording", false);
                recordingStatus.put("pipelineRunning", false);
                recordingStatus.put("modeActive", false);
            }
            // Camera-view state, so the status overlay can RECONCILE its floating ✕ rather
            // than relying solely on the daemon's edge broadcast — an `am broadcast` is
            // dropped if the overlay service isn't up (or lacked overlay permission) at
            // that instant, and nothing re-sends it, leaving a rendering view with no
            // on-screen way to close it. Emitted OUTSIDE the rmm branch: the camview lane
            // is owned by the pipeline, not by RecordingModeManager, so gating on rmm
            // would silently disable the self-heal whenever rmm is unavailable — exactly
            // the degraded case where it matters most. Two volatile field reads, no lock.
            if (pipeline != null) {
                recordingStatus.put("camViewActive", pipeline.isCamViewActive());
                recordingStatus.put("camViewTarget", pipeline.getCamViewTargetString());
                // Blind-spot card state, so the overlay can RECONCILE its floating ✕ from
                // the poll when a daemon edge broadcast is dropped — same self-heal the
                // camView fields above provide. "bsCardShowing" is the composite predicate
                // (BS program owns the lane AND the layer is shown), NOT bare visibility:
                // the layer is shared with camview, so gating on visibility alone would
                // reconcile the BS ✕ over a camera view. Target pairs with it so the
                // overlay can suppress the ✕ on the cluster (a display it can't overlay).
                // ✕-selection labels as ONE consistent snapshot (single laneProgram
                // read inside getCloseLabels). Two separate getter calls could
                // straddle an arbiter program change and emit false/false with the
                // blind-spot card still visible — labels that CLAIM coherence while
                // lying, which the overlay would then act on (wrong camera ✕).
                // laneTransitioning is the daemon's honest incoherence signal for the
                // ≤250ms transition window; the overlay skips adopting the ✕ flags
                // from a response carrying it, instead of trusting edge-broadcast
                // timing to win.
                com.overdrive.app.surveillance.GpuSurveillancePipeline.CloseLabels closeLabels =
                        pipeline.getCloseLabels();
                recordingStatus.put("bsCardShowing", closeLabels.bsCardShowing);
                recordingStatus.put("bsCardTarget", pipeline.getBsTargetString());
                recordingStatus.put("laneTransitioning", closeLabels.laneTransitioning);
                // On-screen rect of whichever program owns the lane, so the overlay can
                // place its floating ✕ CLEAR of the card. The card's SurfaceControl layer
                // sits above every app window, so a ✕ that overlaps it is composited
                // underneath and invisible. Absent when no geometry is resolved yet — the
                // overlay then falls back to its fixed corner.
                //
                // HEAD-UNIT ONLY. A cluster rect is in the 1920×720 cluster panel's space,
                // and the overlay can't draw on that display (it suppresses the ✕ there), so
                // publishing one would only leave wrong-space coordinates cached for a later
                // head-unit show to position against. The cluster gate lives INSIDE
                // getLaneGeomRect, keyed to the rect's true owner: a pre-gate on
                // bsTarget here suppressed the rect during a cluster→head-unit camview
                // transition (bsTarget lags camViewTarget until the arbiter tick),
                // feeding the overlay a null that cleared the fresh show-edge rect.
                int[] laneRect = pipeline.getLaneGeomRect();
                if (laneRect != null) {
                    org.json.JSONObject lr = new org.json.JSONObject();
                    lr.put("x", laneRect[0]); lr.put("y", laneRect[1]);
                    lr.put("w", laneRect[2]); lr.put("h", laneRect[3]);
                    recordingStatus.put("laneRect", lr);
                }
            }
            status.put("recordingStatus", recordingStatus);
        } catch (Exception e) {
            // Recording status not available
        }

        // Instant-replay lifecycle (for the status overlay's clip segment).
        // Poll catch-up channel for the daemon's REPLAY_STATE broadcast:
        // {configured, state: idle|recording|saved|failed, stateAgeMs}.
        // Cheap by contract (see ManualClipService.statusJson) and absent on
        // failure so older clients see no shape change.
        try {
            status.put("replay",
                    com.overdrive.app.recording.ManualClipService.getInstance().statusJson());
        } catch (Exception e) {
            // Replay status not available
        }

        // Trip analytics status (for status overlay)
        try {
            JSONObject tripStatus = new JSONObject();
            com.overdrive.app.trips.TripAnalyticsManager tam = CameraDaemon.getTripAnalyticsManager();
            if (tam != null) {
                tripStatus.put("enabled", tam.isEnabled());
                tripStatus.put("tripActive", tam.isTripActive());
                com.overdrive.app.trips.TripRecord activeTrip = tam.getActiveTrip();
                if (activeTrip != null) {
                    tripStatus.put("tripStartTime", activeTrip.startTime);
                    tripStatus.put("tripDurationSec", (System.currentTimeMillis() - activeTrip.startTime) / 1000);
                }
            } else {
                tripStatus.put("enabled", false);
                tripStatus.put("tripActive", false);
            }
            status.put("tripStatus", tripStatus);
        } catch (Exception e) {
            // Trip status not available
        }
        
        // GPS location
        com.overdrive.app.monitor.GpsMonitor gps = com.overdrive.app.monitor.GpsMonitor.getInstance();
        status.put("gps", gps.getLocationJson());
        
        // Network info (WiFi SSID + IP or Mobile Data)
        status.put("network", com.overdrive.app.monitor.NetworkMonitor.getNetworkInfo());

        // Pre-record buffer health — exposes byte-ring stats so the UI can
        // detect a degraded session (post-OOM "no pre-record" mode, key
        // drops under sustained pin-stall, etc.) and warn the user. Without
        // this, all those degraded states are silent.
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline preRecPipeline = CameraDaemon.getGpuPipeline();
            if (preRecPipeline != null) {
                com.overdrive.app.surveillance.HardwareEventRecorderGpu enc = preRecPipeline.getEncoder();
                if (enc != null) {
                    JSONObject preRec = new JSONObject();
                    boolean preRecOn = enc.isPreRecordEnabled();
                    preRec.put("preRecordEnabled", preRecOn);
                    com.overdrive.app.surveillance.H264ByteRingBuffer ring = enc.getPreRecordBuffer();
                    if (ring != null) {
                        preRec.put("currentSeconds", Math.round(ring.getDurationSeconds() * 10) / 10.0);
                        preRec.put("currentMB", Math.round(ring.storedBytes() / (1024.0 * 1024.0) * 10) / 10.0);
                        preRec.put("maxSeconds", ring.getMaxDurationUs() / 1_000_000L);
                        preRec.put("packetCount", ring.size());
                        // Structured drop counters so the UI can warn on
                        // sustained key drops without parsing the free-form
                        // stats string. Negative-pressure indicators only —
                        // healthy steady-state has all three at zero.
                        preRec.put("totalAdds", ring.getTotalAdds());
                        preRec.put("totalEvictions", ring.getTotalEvictions());
                        preRec.put("totalKeyDrops", ring.getTotalKeyDrops());
                        preRec.put("totalPDrops", ring.getTotalPDrops());
                        preRec.put("stats", ring.getStats());
                    } else {
                        // Distinguish stream-only encoders (deliberately no
                        // pre-record) from byte-ring allocation failures.
                        boolean ooMd = enc.isPreRecordAllocFailed();
                        preRec.put("degraded", ooMd);
                        preRec.put("reason", ooMd
                            ? "byte-ring allocation failed at boot — pre-record disabled this session"
                            : "stream-only encoder (no pre-record by design)");
                    }
                    status.put("preRecord", preRec);
                }
            }
        } catch (Exception e) {
            // Pre-record stats are diagnostic; failure here mustn't block /status.
        }

        // Status assembly below the charging block can be substantial. Revalidate immediately before
        // serialization so a stop or taper-close during that work cannot leak the earlier positive.
        if (chargingPublication != null
                && chargingPublication.hasPositivePresentation()
                && !chargingPublication.isStillCurrent()) {
            chargingPublication =
                com.overdrive.app.charging.ChargingApiHandler
                    .readLivePublication(
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance());
            status.put("charging", chargingPublication.toStatusJson());
        }
        HttpResponse.sendJson(out, status.toString());
    }

    static boolean resolveChargingPlugged(
            boolean charging,
            com.overdrive.app.monitor.ChargingStateData.ChargingStatus status,
            int gunState,
            boolean vtolCharging) {
        return com.overdrive.app.charging.ChargingApiHandler.resolvePlugged(
                charging, status, gunState, vtolCharging);
    }

    static boolean resolveChargingActive(
            boolean fusedCharging, boolean taperCharging,
            com.overdrive.app.monitor.ChargingStateData.ChargingStatus status,
            int gunState, boolean vtolCharging) {
        if (gunState == 1 || gunState == 5 || vtolCharging
                || status == com.overdrive.app.monitor.ChargingStateData.ChargingStatus.DISCHARGING) {
            return false;
        }
        if (status == com.overdrive.app.monitor.ChargingStateData.ChargingStatus.READY
                || status == com.overdrive.app.monitor.ChargingStateData.ChargingStatus.FINISHED
                || status == com.overdrive.app.monitor.ChargingStateData.ChargingStatus.TERMINATED
                || status == com.overdrive.app.monitor.ChargingStateData.ChargingStatus.TIMEOUT
                || status == com.overdrive.app.monitor.ChargingStateData.ChargingStatus.ERROR) {
            return status
                    == com.overdrive.app.monitor.ChargingStateData.ChargingStatus.FINISHED
                    && taperCharging;
        }
        return fusedCharging;
    }

    static boolean resolveChargingFull(
            com.overdrive.app.monitor.ChargingStateData.ChargingStatus status,
            boolean taperCharging, int gunState, boolean vtolCharging) {
        if (gunState == 1 || gunState == 5
                || vtolCharging || taperCharging
                || status == com.overdrive.app.monitor.ChargingStateData.ChargingStatus.DISCHARGING) {
            return false;
        }
        return status
                == com.overdrive.app.monitor.ChargingStateData.ChargingStatus.FINISHED;
    }

    static JSONObject buildIdleChargingStatus() {
        JSONObject charging = new JSONObject();
        try {
            charging.put("stateName", "Unavailable");
            charging.put("status", "UNKNOWN");
            charging.put("chargingPowerKW", 0);
            charging.put("isDischarging", false);
            charging.put("isError", false);
            charging.put("isEstimated", false);
            charging.put("powerSource", "none");
            charging.put("powerObservedAtMs", 0);
            charging.put("powerQuality",
                    com.overdrive.app.monitor.ChargingStateData
                            .PowerQuality.UNKNOWN.name());
            charging.put("powerConfidence", 0);
            charging.put("charging", false);
            charging.put("plugged", false);
            charging.put("full", false);
            charging.put("fault", false);
            charging.put("powerKw", 0);
        } catch (Exception ignored) {}
        return charging;
    }

    /**
     * Block briefly until BydDataCollector reports initialized, so the very
     * first /status request after boot doesn't return a shell with every
     * vehicle field omitted. The collector typically finishes inside ~600 ms
     * but cold-boot binders can stretch beyond a second; we cap the wait so
     * a permanently-broken collector still returns a response.
     *
     * @return true if the collector is initialized when this returns,
     *         false if the wait timed out (caller still emits status, just
     *         with vehicle fields absent and vehicleDataReady=false).
     */
    private boolean waitForVehicleDataReady(long maxWaitMs) {
        try {
            com.overdrive.app.byd.BydDataCollector collector =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            if (collector.isInitialized()) {
                return true;
            }
            long deadline = System.currentTimeMillis() + maxWaitMs;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
                if (collector.isInitialized()) {
                    return true;
                }
            }
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Serves static files from WEB_ROOT with streaming for large files.
     */
    private boolean serveStaticFile(OutputStream out, String relativePath) {
        return serveStaticFile(out, relativePath, null);
    }

    private boolean serveStaticFile(OutputStream out, String relativePath, String ifNoneMatch) {
        if (relativePath.contains("..")) {
            return false;
        }

        File file = new File(WEB_ROOT, relativePath);
        if (!file.exists() || !file.isFile()) {
            // Fall back to the persistent models cache for GLBs that were downloaded
            // at runtime. The bundled default (seal.glb) lives in WEB_ROOT; everything
            // else is fetched on demand into ModelsApiHandler.MODELS_DIR.
            if (relativePath.startsWith("shared/models/") && relativePath.endsWith(".glb")) {
                String fileName = relativePath.substring("shared/models/".length());
                File cached = ModelsApiHandler.cachedModelFile(fileName);
                if (cached != null) {
                    file = cached;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        }
        
        try (FileInputStream fis = new FileInputStream(file)) {
            String contentType = getContentType(relativePath);
            
            // HTML pages must always revalidate so the user gets the latest UI logic.
            // The service worker and PWA manifest also need to bypass cache —
            // a stuck-cached SW means the user can't pick up notification fixes
            // without a manual unregister.
            //
            // Other static assets (JS/CSS/fonts/images) ship inside the APK, so they only
            // change on an app update — but they DO change then, and the old policy
            // ("public, max-age=86400", no validator) meant the browser could not find that
            // out for a day, which is what made a freshly-deployed /shared/app-shell.js
            // appear only intermittently.
            //
            // The ETag below only helps EXTERNAL clients. The in-app WebView proxies every
            // 127.0.0.1:8080 request through WebViewFragment.shouldInterceptRequest, which
            // strips If-None-Match (a 304 there returns to WebView as an empty 200 and breaks
            // <video>) — so in-app there is no 304 to soften a short max-age, and every expiry
            // re-downloads the full body. 1h is the compromise: it bounds that re-download to
            // hourly instead of the 5 minutes an aggressive revalidation window cost, while
            // cutting the stale-after-update window from a day to an hour. Use a ?v= bump when
            // an asset change has to land immediately in-app.
            String cacheControl;
            String fileName = new File(relativePath).getName();
            if (relativePath.endsWith(".html")
                    || fileName.equals("sw.js")
                    || fileName.equals("manifest.json")) {
                cacheControl = "no-store, no-cache, must-revalidate, max-age=0";
            } else {
                cacheControl = "public, max-age=3600, must-revalidate";
            }

            // Validator over (mtime, size). Both change when the APK reinstalls an asset,
            // and neither requires reading the file to compute.
            String etag = "\"" + Long.toHexString(file.lastModified())
                    + "-" + Long.toHexString(file.length()) + "\"";
            if (ifNoneMatch != null && etag.equals(ifNoneMatch.trim())) {
                out.write(("HTTP/1.1 304 Not Modified\r\n"
                        + "ETag: " + etag + "\r\n"
                        + "Cache-Control: " + cacheControl + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes());
                out.flush();
                return true;
            }

            StringBuilder headers = new StringBuilder();
            headers.append("HTTP/1.1 200 OK\r\n")
                   .append("Content-Type: ").append(contentType).append("\r\n")
                   .append("Content-Length: ").append(file.length()).append("\r\n")
                   .append("ETag: ").append(etag).append("\r\n")
                   .append("Cache-Control: ").append(cacheControl).append("\r\n");
            if (relativePath.endsWith(".html")) {
                headers.append("Pragma: no-cache\r\n")
                       .append("Expires: 0\r\n");
            }
            headers.append("Connection: close\r\n\r\n");
            out.write(headers.toString().getBytes());
            
            // Stream in 16KB chunks
            byte[] buffer = new byte[16384];
            int count;
            while ((count = fis.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            out.flush();
            
            CameraDaemon.log("Served static: " + relativePath + " (" + file.length() + " bytes)");
            return true;
            
        } catch (Exception e) {
            CameraDaemon.log("Static file error: " + relativePath + " - " + e.getMessage());
            return false;
        }
    }
    
    private String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".wasm")) return "application/wasm";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".glb")) return "model/gltf-binary";
        if (path.endsWith(".gltf")) return "model/gltf+json";
        return "application/octet-stream";
    }

    // ==================== WEBSOCKET STREAMING ====================
    
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    
    /**
     * Handles WebSocket upgrade on /ws path for single-port streaming.
     */
    private void handleWebSocketUpgrade(Socket client, String websocketKey) {
        try {
            CameraDaemon.log("WebSocket upgrade requested");
            
            String acceptKey = computeWebSocketAccept(websocketKey);
            
            OutputStream out = client.getOutputStream();
            String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
            out.write(response.getBytes());
            out.flush();
            
            CameraDaemon.log("WebSocket handshake complete");
            streamH264ToWebSocket(client);
            
        } catch (Exception e) {
            CameraDaemon.log("WebSocket upgrade error: " + e.getMessage());
        }
    }
    
    private String computeWebSocketAccept(String key) throws Exception {
        String concat = key + WS_MAGIC;
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(concat.getBytes("UTF-8"));
        return Base64.encodeToString(hash, Base64.NO_WRAP);
    }

    /**
     * SOTA: Streams H.264 frames over WebSocket with zero-restart attach.
     *
     * Instead of force-restarting the encoder on every client connect (which causes
     * a 700ms gap and corrupt first frames), we:
     * 1. Reuse the existing encoder if streaming is already enabled
     * 2. Request an IDR keyframe via MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME
     * 3. Send cached SPS/PPS immediately so the decoder can initialize
     * 4. Wait for the IDR to arrive before sending P-frames
     *
     * This gives instant stream start with no encoder restart, no frame corruption,
     * and no broken pipe from the client timing out during restart.
     */
    private void streamH264ToWebSocket(Socket client) {
        CameraDaemon.log("Starting H.264 WebSocket stream");
        
        final BlockingQueue<byte[]> frameQueue = new ArrayBlockingQueue<>(60);
        final boolean[] running = {true};
        
        try {
            client.setSoTimeout(0);
            client.setTcpNoDelay(true);
            client.setSendBufferSize(256 * 1024);
            final OutputStream out = new java.io.BufferedOutputStream(
                client.getOutputStream(), 128 * 1024);
            
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline == null) {
                CameraDaemon.log("WS: Pipeline not available");
                sendWebSocketClose(out, 1011, "Pipeline not available");
                return;
            }
            
            // Auto-start pipeline if needed
            if (!pipeline.isRunning()) {
                CameraDaemon.log("WS: Auto-starting pipeline");
                pipeline.start();
                Thread.sleep(500);
            }
            
            GpuPipelineConfig.StreamingQuality q = GpuPipelineConfig.StreamingQuality.fromString(
                StreamingApiHandler.getStreamingQuality());
            
            // SOTA: Reuse existing encoder if streaming is already enabled at same quality.
            // Only restart if not enabled or quality changed.
            boolean needsRestart = !pipeline.isStreamingEnabled();
            
            // Check if quality changed — need restart for new resolution
            if (!needsRestart && pipeline.isStreamingEnabled()) {
                HardwareEventRecorderGpu existingEncoder = pipeline.getStreamEncoder();
                if (existingEncoder != null) {
                    // Compare current encoder resolution with requested quality
                    com.overdrive.app.streaming.GpuStreamScaler scaler = pipeline.getStreamScaler();
                    if (scaler != null) {
                        int currentWidth = scaler.getWidth();
                        int currentHeight = scaler.getHeight();
                        if (currentWidth != q.width || currentHeight != q.height) {
                            CameraDaemon.log("WS: Quality changed (" + currentWidth + "x" + currentHeight + 
                                " → " + q.width + "x" + q.height + ") — restarting encoder");
                            needsRestart = true;
                            pipeline.disableStreaming();
                            Thread.sleep(200);
                        }
                    }
                }
            }
            
            if (needsRestart) {
                CameraDaemon.log("WS: Enabling streaming - " + q.displayName);
                pipeline.enableStreaming(q.width, q.height, q.fps, q.bitrate);
                Thread.sleep(500);
            } else {
                CameraDaemon.log("WS: Reusing existing stream encoder (no restart)");
            }

            // Read intent only after a fresh scaler is ready. A user can
            // select Front/All while this reconnect waits for initialization;
            // a pre-restart DVR snapshot must not override that newer choice.
            int savedViewMode = StreamingApiHandler.getLastDesiredViewMode();
            if (savedViewMode < 0) {
                int scalerView = pipeline.getStreamViewMode();
                savedViewMode = scalerView >= 0 ? scalerView : 0;
            }
            
            // Apply the saved view mode — EXCEPT view 6 (OEM Dashcam), which must wait
            // until the OEM re-route below actually succeeds. Setting uViewMode=6 while
            // uOemActive is still 0 makes the shader fall through its OEM early-return
            // into the AVM mosaic branch — the "DVR view shows the 4-pano mosaic" bug
            // described in the block below. For view 6 we leave the scaler on its
            // previous view until the bind lands, then set it in the routed branch.
            if (savedViewMode > 0 && savedViewMode != 6) {
                pipeline.setStreamViewMode(savedViewMode);
                CameraDaemon.log("WS: View mode " + savedViewMode);
            }

            // View 6 = OEM Dashcam. The HTTP /api/stream/view/6 path attaches
            // the OEM encoder as the WS stream source via
            // attachExternalStreamCallback. But after an idle-shutdown +
            // reconnect (mobile browser is most common: backgrounding the
            // tab kills the WS, idle timer disables streaming, scaler is
            // freshly built on this WS open), the new scaler has NO OEM binding.
            // HISTORICALLY the block above set uViewMode=6 unconditionally, so with
            // uOemActive==0 the shader fell through its OEM early-return into the
            // legacy 4-pano mosaic branch (uApaMode>1.5 catches it via the else-if
            // chain) — symptom: DVR view showed the 4-pano mosaic instead of the OEM
            // dashcam. The view-6 set is deferred until the OEM producer has
            // published its first transform, so the scaler only enters the OEM
            // branch with a valid frame; until then it keeps the prior view.
            // Re-route here so the WS source switches back to the OEM
            // encoder if the user's saved view is 6.
            if (savedViewMode == 6) {
                boolean rerouted = false;
                boolean viewActivated = false;
                if (StreamingApiHandler.getLastDesiredViewMode() == 6) {
                    rerouted = CameraDaemon.routeStreamToOemDashcam();
                    if (StreamingApiHandler.getLastDesiredViewMode() == 6) {
                        viewActivated = rerouted && pipeline.activateOemStreamViewWhenReady();
                    }
                }
                // A newer HTTP selection may have arrived while OEM was
                // binding. Undo the source bind before the first OEM frame
                // can promote this stale reconnect back to view 6.
                if (StreamingApiHandler.getLastDesiredViewMode() != 6) {
                    int latestViewMode = StreamingApiHandler.getLastDesiredViewMode();
                    pipeline.reattachOwnStreamCallback();
                    if (latestViewMode >= 0) {
                        pipeline.setStreamViewMode(latestViewMode);
                    }
                    rerouted = false;
                    viewActivated = false;
                }
                CameraDaemon.log("WS: View 6 OEM re-route "
                    + (rerouted ? (viewActivated ? "ok" : "waiting for first OEM frame")
                        : "skipped (OEM not ready)"));
                if (viewActivated) {
                    CameraDaemon.log("WS: View mode 6 (OEM Dashcam)");
                }
                // OEM not ready (cold-boot first-WS-open race) — kick the
                // lifecycle so it warms up; the next WS reconnect /
                // /api/stream/view/6 poll will catch the route.
                if (!rerouted) {
                    try {
                        com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
                    } catch (Throwable ignored) {}
                }
            }
            
            // Stream callback with congestion control
            final java.util.concurrent.atomic.AtomicBoolean headersReceived =
                new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicBoolean gotKeyframe =
                new java.util.concurrent.atomic.AtomicBoolean(false);
            HardwareEventRecorderGpu.StreamCallback callback = new HardwareEventRecorderGpu.StreamCallback() {
                @Override
                public void onSpsPps(ByteBuffer sps, ByteBuffer pps) {
                    int spsSize = sps.remaining();
                    int ppsSize = pps.remaining();
                    byte[] combined = new byte[spsSize + ppsSize];
                    sps.get(combined, 0, spsSize);
                    pps.get(combined, spsSize, ppsSize);
                    frameQueue.offer(combined);
                    gotKeyframe.set(false);
                    headersReceived.set(true);
                    CameraDaemon.log("WS: Queued SPS/PPS (" + combined.length + " bytes)");
                }
                
                @Override
                public void onH264Packet(ByteBuffer data, android.media.MediaCodec.BufferInfo info) {
                    // Do not open the packet gate on SPS/PPS alone. A
                    // late-joining client must receive headers followed by a
                    // real IDR; otherwise the first P-frame references a
                    // picture it never decoded.
                    if (!headersReceived.get() || !gotKeyframe.get()) {
                        boolean isKeyframe = (info.flags & android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        if (!headersReceived.get() || !isKeyframe) return;
                        gotKeyframe.set(true);
                    }
                    
                    if (frameQueue.remainingCapacity() > 0) {
                        byte[] frame = new byte[info.size];
                        data.position(info.offset);
                        data.get(frame);
                        frameQueue.offer(frame);
                    }
                    // If queue is full, drop frame (congestion control)
                }
            };

            com.overdrive.app.surveillance.GpuSurveillancePipeline
                    .ExternalStreamClientSubscription subscription =
                pipeline.registerExternalStreamClient(callback);
            if (subscription == null) {
                CameraDaemon.log("WS: Stream encoder was retired during subscription");
                sendWebSocketClose(out, 1013, "Stream restarting");
                return;
            }
            HardwareEventRecorderGpu encoder = subscription.getEncoder();
            encoder.requestSyncFrame();
            CameraDaemon.log("WS: Stream callback registered; IDR keyframe requested");
            
            long lastFrameTime = System.currentTimeMillis();
            int frameCount = 0;
            
            try {
                while (running[0] && !client.isClosed()) {
                    byte[] frame = frameQueue.poll(5, TimeUnit.SECONDS);
                    
                    if (frame != null) {
                        try {
                            // Log first few frames for debugging
                            if (frameCount < 5) {
                                CameraDaemon.log("WS: Frame " + frameCount + " size=" + frame.length + " bytes");
                            }
                            sendWebSocketBinaryFrame(out, frame);
                            lastFrameTime = System.currentTimeMillis();
                            frameCount++;
                            
                            if (frameCount % 300 == 0) {
                                CameraDaemon.log("WS: Sent " + frameCount + " frames");
                            }
                        } catch (java.net.SocketException e) {
                            CameraDaemon.log("WS: Client disconnected (" + e.getMessage() + ")");
                            break;
                        } catch (java.io.IOException e) {
                            CameraDaemon.log("WS: Write error (" + e.getMessage() + ")");
                            break;
                        }
                    } else {
                        // No frame for 5 seconds — send ping to keep alive
                        try {
                            out.write(new byte[]{(byte)0x89, 0x00});
                            out.flush();
                        } catch (Exception e) {
                            CameraDaemon.log("WS: Ping failed, client gone");
                            break;
                        }
                        
                        if (System.currentTimeMillis() - lastFrameTime > 60000) {
                            CameraDaemon.log("WS: Idle timeout (60s) - closing");
                            break;
                        }
                    }
                }
            } finally {
                pipeline.unregisterExternalStreamClient(subscription);
            }
            CameraDaemon.log("WS: Stream ended (" + frameCount + " frames sent)");
            
        } catch (Exception e) {
            CameraDaemon.log("WS stream error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception e) {}
        }
    }

    /**
     * SOTA: Send binary data as WebSocket frame(s) with fragmentation for large frames.
     * Frames larger than MAX_WS_FRAME_SIZE are split into continuation frames
     * to prevent TCP buffer overflow on constrained networks (BYD WiFi AP).
     */
    private static final int MAX_WS_FRAME_SIZE = 32768;  // 32KB per WebSocket frame
    
    private void sendWebSocketBinaryFrame(OutputStream out, byte[] data) throws Exception {
        if (data.length <= MAX_WS_FRAME_SIZE) {
            // Small frame — send as single message
            sendWebSocketRawFrame(out, data, 0, data.length, 0x82, true);
        } else {
            // Large frame — fragment into continuation frames
            int offset = 0;
            boolean first = true;
            while (offset < data.length) {
                int chunkSize = Math.min(MAX_WS_FRAME_SIZE, data.length - offset);
                boolean last = (offset + chunkSize >= data.length);
                int opcode = first ? 0x02 : 0x00;  // binary for first, continuation for rest
                sendWebSocketRawFrame(out, data, offset, chunkSize, opcode, last);
                offset += chunkSize;
                first = false;
            }
        }
        out.flush();
    }
    
    private void sendWebSocketRawFrame(OutputStream out, byte[] data, int offset, int len, 
                                        int opcode, boolean fin) throws Exception {
        int firstByte = (fin ? 0x80 : 0x00) | opcode;
        out.write(firstByte);
        
        if (len <= 125) {
            out.write(len);
        } else if (len <= 65535) {
            out.write(126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((len >> (8 * i)) & 0xFF));
            }
        }
        
        out.write(data, offset, len);
    }
    
    private void sendWebSocketClose(OutputStream out, int code, String reason) {
        try {
            byte[] reasonBytes = reason.getBytes("UTF-8");
            int len = 2 + reasonBytes.length;
            
            out.write(0x88);  // FIN + close opcode
            out.write(len);
            out.write((code >> 8) & 0xFF);
            out.write(code & 0xFF);
            out.write(reasonBytes);
            out.flush();
        } catch (Exception e) {
            // Ignore
        }
    }
    
    // ==================== STATIC ACCESSORS (for backward compatibility) ====================
    
    /**
     * Loads persisted settings. Delegates to QualitySettingsApiHandler.
     */
    public static void loadPersistedSettings() {
        QualitySettingsApiHandler.loadPersistedSettings();
    }
    
    // Getters delegate to handlers
    public static String getRecordingQuality() { return QualitySettingsApiHandler.getRecordingQuality(); }
    public static String getStreamingQuality() { return StreamingApiHandler.getStreamingQuality(); }
    public static String getRecordingBitrate() { return QualitySettingsApiHandler.getRecordingBitrate(); }
    public static String getRecordingCodec() { return QualitySettingsApiHandler.getRecordingCodec(); }
    
    // Setters delegate to handlers
    public static void setRecordingQuality(String quality) { QualitySettingsApiHandler.setRecordingQuality(quality); }
    public static void setStreamingQuality(String quality) { StreamingApiHandler.setStreamingQuality(quality); }
    public static void setRecordingBitrate(String bitrate) { QualitySettingsApiHandler.setRecordingBitrate(bitrate); }
    public static void setRecordingCodec(String codec) { QualitySettingsApiHandler.setRecordingCodec(codec); }
    
    // Static setters for IPC server
    public static void setRecordingBitrateStatic(String bitrate) { QualitySettingsApiHandler.setRecordingBitrateStatic(bitrate); }
    public static void setRecordingCodecStatic(String codec) { QualitySettingsApiHandler.setRecordingCodecStatic(codec); }
    public static void persistSettingsStatic() { QualitySettingsApiHandler.persistSettings(); }
}
