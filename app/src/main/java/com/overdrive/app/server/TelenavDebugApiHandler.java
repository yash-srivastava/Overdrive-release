package com.overdrive.app.server;

import com.overdrive.app.telenav.TelenavIpcServer;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * READ-ONLY spike endpoint for Telenav's exported user-data AIDL. The daemon
 * (uid-2000, synthetic ActivityThread) cannot {@code bindService}, so this handler
 * forwards to {@link TelenavIpcServer} in the app process over
 * {@code 127.0.0.1:19882}, which does the bind and returns the JSON.
 *
 * <p>{@code GET /api/debug/telenav/favorites} — read all favourite buckets + recent.
 */
public final class TelenavDebugApiHandler {

    private TelenavDebugApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path;
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) pathOnly = path.substring(0, qIdx);

        // Accept the stable /api/telenav/* path and the older /api/debug/telenav/*.
        String p = pathOnly;
        if (p.startsWith("/api/debug/telenav/")) p = "/api/telenav/" + p.substring("/api/debug/telenav/".length());

        final JSONObject req;
        if (p.equals("/api/telenav/favorites")) {
            if (!"GET".equals(method)) {
                HttpResponse.sendError(out, 405, "Method Not Allowed");
                return true;
            }
            req = new JSONObject().put("op", "getFavorites");
        } else if (p.equals("/api/telenav/navstate")) {
            if (!"GET".equals(method)) { HttpResponse.sendError(out, 405, "Method Not Allowed"); return true; }
            req = new JSONObject().put("op", "navState");
        } else if (p.equals("/api/telenav/stopnav")) {
            if (!"POST".equals(method)) { HttpResponse.sendError(out, 405, "Method Not Allowed"); return true; }
            req = new JSONObject().put("op", "stopNav");
        } else if (p.equals("/api/telenav/addFavorite") || p.equals("/api/telenav/navigate")) {
            if (!"POST".equals(method)) {
                HttpResponse.sendError(out, 405, "Method Not Allowed");
                return true;
            }
            JSONObject in = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            String op = p.endsWith("/navigate") ? "navigate" : "addFavorite";
            req = new JSONObject()
                    .put("op", op)
                    .put("favoriteType", "Normal")
                    .put("name", in.optString("name", ""))
                    .put("lat", in.getDouble("lat"))
                    .put("lng", in.getDouble("lng"))
                    .put("replace", in.optBoolean("replace", false))
                    .put("formattedAddress", in.optString("formattedAddress", in.optString("name", "")));
        } else {
            HttpResponse.sendError(out, 404, "Unknown telenav endpoint");
            return true;
        }

        // Navigate is a silent no-op unless Telenav is the foreground app (verified live
        // 2026-08-23). This request arrives while the OverDrive app process is backgrounded,
        // so it can't foreground an activity itself (background-activity-launch limits) — but
        // this handler runs in the daemon (UID 2000), which can `am start` regardless. Do it
        // here, before the app process runs startNavigation. Save-to-Favourites persists
        // silently and must NOT steal the screen, so it is deliberately excluded.
        if (p.equals("/api/telenav/navigate")) {
            if (isAccOff()) {
                // Car is off — foregrounding Telenav now is pointless (screen off). Queue
                // the target; DeferredNavManager offers it as a prompt on the next ACC-on.
                double qlat = req.optDouble("lat", Double.NaN);
                double qlng = req.optDouble("lng", Double.NaN);
                try {
                    com.overdrive.app.telenav.TelenavActions.validateCoordinates(qlat, qlng);
                } catch (IllegalArgumentException invalid) {
                    HttpResponse.sendError(out, 400, invalid.getMessage());
                    return true;
                }
                if (!com.overdrive.app.telenav.DeferredNavManager.storePending(
                        req.optString("name", ""), qlat, qlng)) {
                    HttpResponse.sendError(out, 503, "Unable to persist deferred navigation");
                    return true;
                }
                JSONObject queued = new JSONObject();
                queued.put("success", true);
                queued.put("queued", true);
                queued.put("message", "Car is off — navigation will be offered on next start.");
                HttpResponse.sendJson(out, 200, queued.toString());
                return true;
            }
            foregroundTelenav();
        }

        // Every op below binds Telenav, which starts it if it isn't running.
        com.overdrive.app.telenav.DeferredNavManager.noteTelenavUse();
        String resp = forwardToApp(req.toString(), 25_000);
        if (resp == null) {
            JSONObject err = new JSONObject();
            err.put("success", false);
            err.put("error", "app IPC unreachable on 127.0.0.1:" + TelenavIpcServer.PORT
                    + " (is the OverDrive app process running?)");
            HttpResponse.sendJson(out, 200, err.toString());
            return true;
        }
        HttpResponse.sendJson(out, 200, resp);
        return true;
    }

    /**
     * True when the car is off (ACC off). {@code sys.accanim.status} is "0" or empty while
     * ACC is on; non-zero once the shutdown animation runs / the car is off.
     * Unknown → treat as ON (navigate immediately),
     * which is the safe default (never silently swallow a request into the queue).
     */
    private static boolean isAccOff() {
        try {
            Process pr = new ProcessBuilder("getprop", "sys.accanim.status")
                    .redirectErrorStream(true).start();
            String v = new BufferedReader(new InputStreamReader(pr.getInputStream())).readLine();
            pr.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            v = v == null ? "" : v.trim();
            return !(v.isEmpty() || v.equals("0"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Bring Telenav's map to the foreground from the daemon (UID 2000). Uses {@code am
     * start} via {@code sh -c} — the same privileged path {@code ShellAction} uses — because
     * the backgrounded app process is blocked from launching activities. Best effort: a
     * failure here still lets the navigate command through (it just won't be visible).
     */
    private static void foregroundTelenav() {
        com.overdrive.app.telenav.DeferredNavManager.noteTelenavUse();
        try {
            Process pr = new ProcessBuilder("sh", "-c",
                    com.overdrive.app.util.ScratchRoot.remap("am start -n com.telenav.app.arp/com.telenav.arp.module.map.MainActivity"))
                    .redirectErrorStream(true)
                    .start();
            pr.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            Thread.sleep(1000); // let Telenav reach the front before startNavigation binds
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ignore) {
            // Best effort — navigate still proceeds, just not visibly.
        }
    }

    /** One line of JSON to the app-process listener, one line back. */
    private static String forwardToApp(String requestLine, int readTimeoutMs) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", TelenavIpcServer.PORT), 2000);
            socket.setSoTimeout(readTimeoutMs);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer.println(requestLine);
            return reader.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignore) {}
        }
    }
}
