package com.overdrive.app.server;

import android.util.Log;

import com.overdrive.app.remote.RemoteDevViewBridgeAuth;
import com.overdrive.app.remote.RemoteDevViewBridgeService;
import com.overdrive.app.util.ScratchRoot;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Shell-daemon client for the protected app-process developer-view bridge. */
final class RemoteDevViewBridgeClient {
    private static final String TAG = "RemoteDevViewClient";
    private static final int READ_TIMEOUT_MS = 9_000;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;

    static final class Response {
        final JSONObject metadata;
        final byte[] payload;

        Response(JSONObject metadata, byte[] payload) {
            this.metadata = metadata;
            this.payload = payload;
        }
    }

    Response start(boolean launchActivity) {
        JSONObject request = new JSONObject();
        try { request.put("command", launchActivity ? "launch" : "status"); }
        catch (Exception ignored) {}
        return send(request);
    }

    Response send(JSONObject request) {
        return send(request, 1_000, READ_TIMEOUT_MS, true);
    }

    /**
     * Send an app-process command, starting the exported DUMP-protected bridge
     * service through the shell UID if Android has reclaimed the non-sticky
     * app process. The command channel itself remains HMAC-authenticated.
     */
    Response sendWithServiceStart(
            JSONObject request, int connectTimeoutMs, int readTimeoutMs, boolean logFailures) {
        Response response = send(request, connectTimeoutMs, readTimeoutMs, false);
        if (response != null) return response;
        if (!startBridgeService()) {
            if (logFailures) Log.w(TAG, "Could not start app-process bridge service");
            return null;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            response = send(request, connectTimeoutMs, readTimeoutMs, logFailures);
            if (response != null) return response;
        }
        return null;
    }

    private static boolean startBridgeService() {
        Process process = null;
        try {
            String command = "am startservice --user 0 -n "
                    + "com.overdrive.app/.remote.RemoteDevViewBridgeService";
            process = new ProcessBuilder("sh", "-c", ScratchRoot.remap(command))
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Throwable error) {
            Log.w(TAG, "Bridge service start failed: " + error.getMessage());
            return false;
        } finally {
            try { if (process != null) process.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** Bounded, quiet reads let routine status requests tolerate an unavailable app bridge. */
    Response send(JSONObject request, int connectTimeoutMs, int readTimeoutMs, boolean logFailures) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), RemoteDevViewBridgeService.PORT), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);

            PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println(RemoteDevViewBridgeAuth.sign(request));

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int metadataLength = input.readInt();
            if (metadataLength <= 0 || metadataLength > MAX_METADATA_BYTES) return null;
            byte[] metadataBytes = new byte[metadataLength];
            input.readFully(metadataBytes);
            JSONObject metadata = new JSONObject(new String(metadataBytes, StandardCharsets.UTF_8));
            int payloadLength = metadata.optInt("payloadBytes", 0);
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) return null;
            byte[] payload = new byte[payloadLength];
            if (payloadLength > 0) input.readFully(payload);
            return new Response(metadata, payload);
        } catch (Throwable error) {
            if (logFailures) Log.w(TAG, "App-process bridge request failed", error);
            return null;
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    Response awaitReady() {
        for (int attempt = 0; attempt < 20; attempt++) {
            JSONObject request = new JSONObject();
            try { request.put("command", "status"); } catch (Exception ignored) {}
            Response response = send(request);
            if (response != null) return response;
            try { Thread.sleep(100); } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    void shutdown() {
        try {
            send(new JSONObject().put("command", "stop"));
        } catch (Exception ignored) {}
    }
}
