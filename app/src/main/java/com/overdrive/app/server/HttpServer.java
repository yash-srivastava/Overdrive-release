package com.overdrive.app.server;

import android.content.res.AssetManager;
import android.util.Base64;

import com.overdrive.app.auth.AuthManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.monitor.BatteryMonitor;
import com.overdrive.app.surveillance.GpuPipelineConfig;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
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

    /** Path that ZrokLauncher writes the reserved subdomain to (cross-UID readable). */
    private static final String ZROK_UNIQUE_NAME_FILE = "/data/local/tmp/.zrok/unique_name";

    /** Cache the reserved zrok unique name to avoid one disk read per request. */
    private static volatile String cachedZrokUniqueName;
    private static volatile long cachedZrokUniqueNameAt;
    private static final long ZROK_NAME_CACHE_TTL_MS = 60_000L;

    /**
     * Returns true when the request's Host header matches the configured
     * PWA install origin (the user's reserved zrok subdomain). Only that
     * origin gets to download {@code manifest.json} and {@code sw.js}, so
     * PWAs only install against a stable URL — push subscriptions are
     * bound to origin and a rotating URL would break every notification.
     *
     * <p>Reads the unique name from the cross-UID file written by
     * {@code ZrokLauncher} ({@code /data/local/tmp/.zrok/unique_name}).
     * The Kotlin {@code PreferencesManager} would NPE here because it lives
     * in app-private SharedPreferences, which UID 2000 (this daemon) cannot
     * read.
     */
    private static boolean isPwaOrigin(String hostHeader) {
        if (hostHeader == null || hostHeader.isEmpty()) return false;
        // Strip optional :port for comparison
        int colon = hostHeader.indexOf(':');
        String hostOnly = colon > 0 ? hostHeader.substring(0, colon) : hostHeader;

        // Reject loopback / LAN — those are the WebView and shouldn't get a PWA.
        if (hostOnly.equals("127.0.0.1") || hostOnly.equals("localhost")) return false;

        String unique = readZrokUniqueName();
        if (unique == null || unique.isEmpty()) return false;

        // Match exactly the reserved subdomain to avoid letting any random
        // *.share.zrok.io install a PWA against this car.
        String expected = unique + ".share.zrok.io";
        return hostOnly.equalsIgnoreCase(expected);
    }

    private static String readZrokUniqueName() {
        long now = System.currentTimeMillis();
        String c = cachedZrokUniqueName;
        if (c != null && (now - cachedZrokUniqueNameAt) < ZROK_NAME_CACHE_TTL_MS) return c;

        try {
            File f = new File(ZROK_UNIQUE_NAME_FILE);
            if (!f.exists() || f.length() == 0) {
                cachedZrokUniqueName = "";
                cachedZrokUniqueNameAt = now;
                return "";
            }
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[(int) Math.min(f.length(), 256)];
                int n = fis.read(buf);
                String s = (n > 0 ? new String(buf, 0, n, "UTF-8") : "").trim();
                cachedZrokUniqueName = s;
                cachedZrokUniqueNameAt = now;
                return s;
            }
        } catch (Exception e) {
            cachedZrokUniqueName = "";
            cachedZrokUniqueNameAt = now;
            return "";
        }
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
            
            // Extract BYD cloud crypto tables
            try {
                String bydAsset = "byd/bangcle_tables.bin";
                String[] bydFiles = assetManager.list("byd");
                if (bydFiles != null && bydFiles.length > 0) {
                    File bydTablesFile = new File("/data/local/tmp/bangcle_tables.bin");
                    if (!bydTablesFile.exists() || bydTablesFile.length() == 0) {
                        try (InputStream in = assetManager.open(bydAsset);
                             java.io.FileOutputStream fos = new java.io.FileOutputStream(bydTablesFile)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                fos.write(buf, 0, n);
                            }
                        }
                        bydTablesFile.setReadable(true, false);
                        CameraDaemon.log("Extracted BYD Bangcle tables to " + bydTablesFile.getAbsolutePath() + " (" + bydTablesFile.length() + " bytes)");
                    }
                }
            } catch (Exception e) {
                CameraDaemon.log("Could not extract BYD Bangcle tables: " + e.getMessage());
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
                        CameraDaemon.log("HTTP client: " + client.getRemoteSocketAddress());
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
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {}
        threadPool.shutdownNow();
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = new BufferedOutputStream(client.getOutputStream());

            String requestLine = reader.readLine();
            if (requestLine == null) {
                client.close();
                return;
            }
            
            CameraDaemon.log("HTTP: " + requestLine);
            
            // Parse headers
            String line;
            int contentLength = 0;
            String websocketKey = null;
            String upgradeHeader = null;
            String rangeHeader = null;
            String cookieHeader = null;
            String authHeader = null;
            // Reverse-proxy fingerprints — used by AuthMiddleware to disable
            // the loopback safety net when a tunnel relayed the request.
            // Cloudflared injects Cf-*, zrok / ngrok injects X-Forwarded-*.
            boolean hasTunnelHeaders = false;
            String forwardedFor = null;
            String hostHeader = null;
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
                } else if (lower.startsWith("range:")) {
                    rangeHeader = line.substring(6).trim();
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
            String body = null;
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int read = reader.read(bodyChars, totalRead, contentLength - totalRead);
                    if (read == -1) break;  // EOF
                    totalRead += read;
                }
                body = new String(bodyChars, 0, totalRead);
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
            
            // WebSocket upgrade on /ws path (check auth first for non-public paths).
            // Match /ws and /ws?... (query params allow JWT-as-?token= since browser
            // WebSocket clients can't set arbitrary headers — cookies may be dropped
            // through tunnels' SameSite policies).
            String wsPathOnly = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
            if (wsPathOnly.equals("/ws") && websocketKey != null && "websocket".equalsIgnoreCase(upgradeHeader)) {
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
                if (!AuthMiddleware.checkAuth(wsPathOnly, cookieHeader, wsAuthHeader, out,
                        client.getRemoteSocketAddress(), hasTunnelHeaders)) {
                    client.close();
                    return;
                }
                handleWebSocketUpgrade(client, websocketKey);
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
            
            // Handle CORS preflight (OPTIONS) requests for cross-origin webapp access.
            // Browsers send OPTIONS before POST/PUT/DELETE with Content-Type: application/json.
            // The in-app WebView is same-origin so it skips this, but the external webapp needs it.
            // Must be handled BEFORE auth check — preflight requests don't carry cookies/tokens.
            if (method.equals("OPTIONS")) {
                HttpResponse.sendCorsPreflightResponse(out);
                client.close();
                return;
            }
            
            // Check authentication for all other paths
            if (!AuthMiddleware.checkAuth(path, cookieHeader, authHeader, out,
                    client.getRemoteSocketAddress(), hasTunnelHeaders)) {
                client.close();
                return;
            }
            
            // PWA install assets — gated to the configured PWA origin so we
            // don't accidentally bootstrap a service worker on the in-car
            // WebView (Host: 127.0.0.1) or on a tunnel whose URL is not stable
            // (e.g. cloudflared quick tunnels). Subscriptions are origin-bound;
            // installing the PWA from an unstable origin breaks every push.
            // Strip query (?...) and fragment (#...) — browsers sometimes
            // append cache-bust params on the SW fetch.
            String pwaPathOnly = path;
            int pwaQ = pwaPathOnly.indexOf('?');
            if (pwaQ >= 0) pwaPathOnly = pwaPathOnly.substring(0, pwaQ);
            int pwaH = pwaPathOnly.indexOf('#');
            if (pwaH >= 0) pwaPathOnly = pwaPathOnly.substring(0, pwaH);
            if (pwaPathOnly.equals("/manifest.json") || pwaPathOnly.equals("/sw.js")) {
                // Through a tunnel, Host: is rewritten to the backend (localhost:8080).
                // Match against X-Forwarded-Host when present so the reserved
                // zrok subdomain still passes the gate.
                String pwaOriginHost = forwardedHostHeader != null ? forwardedHostHeader : hostHeader;
                if (!isPwaOrigin(pwaOriginHost)) {
                    // Diagnostic: figure out *why* the gate rejected. Three legs:
                    //   (a) no headers at all
                    //   (b) X-Forwarded-Host present but didn't match unique_name
                    //   (c) X-Forwarded-Host missing → fell back to Host: localhost
                    // Logging the leg lets the user fix the right thing instead
                    // of guessing whether zrok's headers, the unique_name file,
                    // or the share domain is wrong.
                    String unique = readZrokUniqueName();
                    CameraDaemon.log("PWA gate REJECT path=" + pwaPathOnly
                            + " xfh=" + forwardedHostHeader
                            + " host=" + hostHeader
                            + " unique=" + (unique != null && !unique.isEmpty() ? unique : "<empty>")
                            + " expected=" + (unique != null && !unique.isEmpty() ? unique + ".share.zrok.io" : "<no-unique>"));
                    HttpResponse.sendError(out, 404, "Not Found");
                    client.close();
                    return;
                }
                String filePath = "local/" + pwaPathOnly.substring(1);
                if (!serveStaticFile(out, filePath)) {
                    HttpResponse.sendError(out, 404, "Not Found");
                }
                client.close();
                return;
            }

            // Notification API — separate handler, all routes auth-gated above
            if (path.startsWith("/api/notifications") || path.startsWith("/api/push")) {
                if (NotificationApiHandler.handle(method, path, body, out)) {
                    client.close();
                    return;
                }
            }

            // Notifications settings page
            if (path.equals("/notifications.html") || path.equals("/notifications")) {
                if (!serveStaticFile(out, "local/notifications.html")) {
                    HttpResponse.sendError(out, 404, "notifications.html not found");
                }
                client.close();
                return;
            }

            // Route to modular handlers first
            if (routeToHandlers(method, path, body, rangeHeader, out)) {
                // Handled by a modular handler
            }
            // Static pages
            else if (path.equals("/") || path.equals("/index.html")) {
                if (!serveStaticFile(out, "local/index.html")) {
                    HttpResponse.sendError(out, 404, "index.html not found");
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
            } else if (path.equals("/vehicle-control.html") || path.equals("/vehicle-control")) {
                if (!serveStaticFile(out, "local/vehicle-control.html")) {
                    HttpResponse.sendError(out, 404, "vehicle-control.html not found");
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
                } else if (!serveStaticFile(out, "i18n/" + tag)) {
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
                if (!serveStaticFile(out, filePath)) {
                    HttpResponse.sendError(out, 404, "Not Found: " + path);
                }
            }
            // Core camera APIs (kept inline for simplicity)
            else if (path.startsWith("/snapshot/")) {
                int camId = Integer.parseInt(path.substring(10));
                sendSnapshot(out, camId);
            } else if (path.equals("/status")) {
                sendStatus(out);
            } else if (path.startsWith("/api/start/")) {
                int camId = Integer.parseInt(path.substring(11));
                CameraDaemon.startCamera(camId, true, false);
                HttpResponse.sendJson(out, "{\"status\":\"ok\",\"action\":\"start\",\"camera\":" + camId + "}");
            } else if (path.startsWith("/api/view/")) {
                int camId = Integer.parseInt(path.substring(10));
                CameraDaemon.startCamera(camId, true, true);
                HttpResponse.sendJson(out, "{\"status\":\"ok\",\"action\":\"view\",\"camera\":" + camId + "}");
            } else if (path.startsWith("/api/stop/")) {
                int camId = Integer.parseInt(path.substring(10));
                CameraDaemon.stopCamera(camId);
                HttpResponse.sendJson(out, "{\"status\":\"ok\",\"action\":\"stop\",\"camera\":" + camId + "}");
            } else if (path.equals("/api/stopall")) {
                CameraDaemon.stopAllCameras();
                HttpResponse.sendJson(out, "{\"status\":\"ok\",\"action\":\"stopall\"}");
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
     * Routes requests to modular API handlers.
     * @return true if handled by a handler
     */
    private boolean routeToHandlers(String method, String path, String body, String rangeHeader, OutputStream out) throws Exception {
        // Recordings API (with Range header support for video seeking) + thumbnails + event timelines
        if (path.startsWith("/api/recordings") || path.startsWith("/video/") || 
            path.startsWith("/thumb/") || path.startsWith("/api/events/")) {
            return RecordingsApiHandler.handleWithRange(method, path, body, rangeHeader, out);
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
                }
            } else {
                HttpResponse.sendJsonError(out, "Trip analytics not initialized");
                return true;
            }
        }
        
        // Audio Test API (AVAS speaker test)
        if (path.startsWith("/api/audio/")) {
            return AudioTestApiHandler.handle(method, path, body, out);
        }

        // Vehicle Control API
        if (path.startsWith("/api/vehicle")) {
            return VehicleControlApiHandler.handle(method, path, body, out);
        }
        
        // Performance API
        if (path.startsWith("/api/performance")) {
            return PerformanceApiHandler.handle(method, path, body, out);
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
        
        // App version — read from persisted version file (written by AppUpdater)
        // Falls back to BuildConfig.VERSION_NAME if file doesn't exist yet
        status.put("appVersion", com.overdrive.app.updater.AppUpdater.getDisplayVersionFromFile());
        status.put("recording", TcpCommandServer.getRecordingCameras());
        status.put("viewing", TcpCommandServer.getViewOnlyCameras());
        status.put("active", TcpCommandServer.getActiveCameras());
        status.put("streaming", TcpCommandServer.getStreamingCameras());
        status.put("available", TcpCommandServer.getAvailableCameras());
        status.put("battery", BatteryMonitor.getBatteryInfo());
        status.put("acc", AccMonitor.isAccOn());
        
        // Safe zone status (so UI can show suppressed state)
        com.overdrive.app.surveillance.SafeLocationManager safeMgr =
            com.overdrive.app.surveillance.SafeLocationManager.getInstance();
        status.put("safeZoneSuppressed", CameraDaemon.isSafeZoneSuppressed());
        status.put("inSafeZone", safeMgr.isInSafeZone());
        if (safeMgr.getCurrentZoneName() != null) {
            status.put("safeZoneName", safeMgr.getCurrentZoneName());
        }
        
        // Vehicle data (charging state and power)
        try {
            com.overdrive.app.monitor.VehicleDataMonitor vehicleMonitor =
                com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
            
            com.overdrive.app.monitor.ChargingStateData chargingState = vehicleMonitor.getChargingState();
            if (chargingState != null) {
                JSONObject charging = new JSONObject();
                charging.put("stateName", chargingState.stateName);
                charging.put("status", chargingState.status.name());
                charging.put("chargingPowerKW", chargingState.chargingPowerKW);
                charging.put("isDischarging", chargingState.isDischarging);
                charging.put("isError", chargingState.isError);
                // Surface the "estimated from SOC rate" flag so the UI can show
                // a "~" prefix on the kW value (core.js already reads this).
                charging.put("isEstimated", chargingState.isEstimated);
                status.put("charging", charging);
            }
            
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

            // Active UI locale — exposed so the WebView can sync its i18n
            // state with changes made elsewhere (Android settings drawer,
            // another logged-in client). Always one of LocaleManager.SUPPORTED.
            try {
                status.put("locale", LocaleManager.get());
            } catch (Exception ignored) {
                status.put("locale", "en");
            }
        } catch (Exception e) {
            // Vehicle data not available
        }
        try {
            JSONObject soh = new JSONObject();
            boolean hasSoh = false;
            
            com.overdrive.app.monitor.SocHistoryDatabase socDb = com.overdrive.app.monitor.SocHistoryDatabase.getInstance();
            com.overdrive.app.abrp.SohEstimator sohEst = socDb != null ? socDb.getSohEstimator() : null;
            if (sohEst != null && sohEst.hasEstimate()) {
                soh.put("percent", Math.round(sohEst.getCurrentSoh() * 10) / 10.0);
                soh.put("estimatedCapacityKwh", Math.round(sohEst.getEstimatedCapacityKwh() * 10) / 10.0);
                soh.put("nominalCapacityKwh", sohEst.getNominalCapacityKwh());
                hasSoh = true;
            } else {
                // Fallback: read from persisted file
                java.io.File sohFile = new java.io.File("/data/local/tmp/abrp_soh_estimate.properties");
                if (sohFile.exists()) {
                    java.util.Properties props = new java.util.Properties();
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(sohFile)) {
                        props.load(fis);
                    }
                    String sohStr = props.getProperty("soh_percent");
                    if (sohStr != null) {
                        double sohVal = Double.parseDouble(sohStr);
                        // Reject out-of-range values (e.g. 101 from bogus BMS sentinels)
                        if (sohVal > 0 && sohVal <= 100) {
                            soh.put("percent", Math.round(sohVal * 10) / 10.0);
                            hasSoh = true;
                        }
                    }
                }
            }
            
            if (hasSoh) status.put("soh", soh);
        } catch (Exception e) {
            // SOH not available
        }
        
        // GPU surveillance status — only true when actually in sentry/surveillance mode,
        // not when pipeline is running for normal recording (CONTINUOUS, PROXIMITY_GUARD)
        com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        status.put("gpuSurveillance", pipeline != null && pipeline.isSurveillanceMode());
        
        // Recording mode details (for status overlay)
        try {
            JSONObject recordingStatus = new JSONObject();
            com.overdrive.app.recording.RecordingModeManager rmm = CameraDaemon.getRecordingModeManager();
            if (rmm != null) {
                recordingStatus.put("configuredMode", rmm.getCurrentMode().name());
                recordingStatus.put("isRecording", pipeline != null && pipeline.isRecording());
                recordingStatus.put("pipelineRunning", pipeline != null && pipeline.isRunning());
                recordingStatus.put("gear", com.overdrive.app.recording.RecordingModeManager.gearToString(rmm.getCurrentGear()));
                recordingStatus.put("accOn", rmm.isAccOn());
            } else {
                recordingStatus.put("configuredMode", "UNKNOWN");
                recordingStatus.put("isRecording", false);
                recordingStatus.put("pipelineRunning", false);
            }
            status.put("recordingStatus", recordingStatus);
        } catch (Exception e) {
            // Recording status not available
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
        
        HttpResponse.sendJson(out, status.toString());
    }

    /**
     * Serves static files from WEB_ROOT with streaming for large files.
     */
    private boolean serveStaticFile(OutputStream out, String relativePath) {
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
            // Other shared static assets (JS/CSS/fonts/images) ship inside the APK
            // and never change without an app update, so we let the browser cache
            // them to avoid re-downloading ~360KB on every page load.
            String cacheControl;
            String fileName = new File(relativePath).getName();
            if (relativePath.endsWith(".html")
                    || fileName.equals("sw.js")
                    || fileName.equals("manifest.json")) {
                cacheControl = "no-store, no-cache, must-revalidate, max-age=0";
            } else {
                cacheControl = "public, max-age=86400";
            }
            
            StringBuilder headers = new StringBuilder();
            headers.append("HTTP/1.1 200 OK\r\n")
                   .append("Content-Type: ").append(contentType).append("\r\n")
                   .append("Content-Length: ").append(file.length()).append("\r\n")
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
            
            int savedViewMode = pipeline.getStreamViewMode();
            if (savedViewMode < 0) savedViewMode = 0;
            
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
            
            if (savedViewMode > 0) {
                pipeline.setStreamViewMode(savedViewMode);
                CameraDaemon.log("WS: View mode " + savedViewMode);
            }
            
            HardwareEventRecorderGpu encoder = pipeline.getStreamEncoder();
            if (encoder == null) {
                CameraDaemon.log("WS: Stream encoder not available");
                sendWebSocketClose(out, 1011, "Encoder not available");
                return;
            }
            
            // SOTA: Send cached SPS/PPS immediately from WebSocketStreamServer
            // so the client decoder can initialize before the first frame arrives.
            com.overdrive.app.streaming.WebSocketStreamServer wsServer = pipeline.getWebSocketServer();
            boolean spsPpsSent = false;
            if (wsServer != null) {
                byte[] cachedSpsPps = wsServer.getCachedSpsPps();
                if (cachedSpsPps != null && cachedSpsPps.length > 0) {
                    try {
                        sendWebSocketBinaryFrame(out, cachedSpsPps);
                        spsPpsSent = true;
                        CameraDaemon.log("WS: Sent cached SPS/PPS (" + cachedSpsPps.length + " bytes)");
                    } catch (Exception e) {
                        CameraDaemon.log("WS: Failed to send cached SPS/PPS: " + e.getMessage());
                    }
                }
            }
            
            // SOTA: Request IDR keyframe so client gets a clean decode start.
            // This is instant — no encoder restart needed.
            encoder.requestSyncFrame();
            CameraDaemon.log("WS: IDR keyframe requested");
            
            // Also request SPS/PPS re-send if we didn't have cached ones
            if (!spsPpsSent) {
                // The encoder will send SPS/PPS before the next IDR via the callback
                CameraDaemon.log("WS: Waiting for SPS/PPS from encoder");
            }
            
            // Stream callback with congestion control
            final boolean[] gotKeyframe = {spsPpsSent};  // Skip waiting if we already sent SPS/PPS
            HardwareEventRecorderGpu.StreamCallback callback = new HardwareEventRecorderGpu.StreamCallback() {
                @Override
                public void onSpsPps(ByteBuffer sps, ByteBuffer pps) {
                    int spsSize = sps.remaining();
                    int ppsSize = pps.remaining();
                    byte[] combined = new byte[spsSize + ppsSize];
                    sps.get(combined, 0, spsSize);
                    pps.get(combined, spsSize, ppsSize);
                    frameQueue.offer(combined);
                    gotKeyframe[0] = true;
                    CameraDaemon.log("WS: Queued SPS/PPS (" + combined.length + " bytes)");
                }
                
                @Override
                public void onH264Packet(ByteBuffer data, android.media.MediaCodec.BufferInfo info) {
                    // SOTA: Drop P-frames until we've sent SPS/PPS + IDR
                    // Sending P-frames before the decoder has SPS/PPS causes decode failure
                    if (!gotKeyframe[0]) {
                        boolean isKeyframe = (info.flags & android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        if (!isKeyframe) return;  // Drop P-frames before first keyframe
                        gotKeyframe[0] = true;
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
            
            encoder.setStreamCallback(callback);
            CameraDaemon.log("WS: Stream callback registered");
            
            if (wsServer != null) {
                wsServer.registerExternalClient();
            }
            
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
                if (wsServer != null) {
                    wsServer.unregisterExternalClient();
                }
            }
            
            encoder.clearStreamCallback();
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
