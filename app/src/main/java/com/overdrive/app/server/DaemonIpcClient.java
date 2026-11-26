package com.overdrive.app.server;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Minimal localhost IPC client for app-process and HTTP proxy callers to send a
 * one-shot command to the daemon's {@link SurveillanceIpcServer} (UID 2000) on
 * 127.0.0.1:19877 and read the single-line JSON reply.
 *
 * The app process can't do privileged/daemon-only work directly (read certain
 * files, run the proxy-aware uploader from the daemon's vantage point), so it
 * delegates over this socket — the same transport UnifiedConfigManager and the
 * Telegram CommandContext use. Synchronous; call OFF the main thread.
 */
public final class DaemonIpcClient {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 19877;
    private static final int CONNECT_TIMEOUT_MS = 1500;

    private DaemonIpcClient() {}

    /**
     * Send {@code request} and return the parsed JSON reply, or null on any
     * failure (daemon down, timeout, malformed reply).
     *
     * @param readTimeoutMs socket read timeout — size to the server handler's
     *                      worst case (e.g. a network upload needs ~30s).
     */
    public static JSONObject send(JSONObject request, int readTimeoutMs) {
        try {
            return sendOrThrow(request, readTimeoutMs);
        } catch (Exception e) {
            return null;
        }
    }

    static JSONObject sendOrThrow(JSONObject request, int readTimeoutMs) throws Exception {
        return exchange(new InetSocketAddress(HOST, PORT), request, readTimeoutMs);
    }

    // Package-private endpoint parameter allows loopback tests on an ephemeral port.
    static JSONObject exchange(InetSocketAddress endpoint, JSONObject request, int readTimeoutMs)
            throws Exception {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(endpoint, CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(readTimeoutMs);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer.println(request.toString());
            String line = reader.readLine();
            if (line == null) return null;
            return new JSONObject(line);
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }
}
