package com.overdrive.app.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.overdrive.app.daemon.DaemonBootstrap;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.remote.RemoteDevViewBridgeService;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Shell-daemon client for the protected app-process developer-view bridge. */
final class RemoteDevViewBridgeClient {
    private static final int CONNECT_TIMEOUT_MS = 1_500;
    private static final int READ_TIMEOUT_MS = 9_000;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    private static final String SERVICE_CLASS =
        "com.overdrive.app.remote.RemoteDevViewBridgeService";

    static final class Response {
        final JSONObject metadata;
        final byte[] payload;

        Response(JSONObject metadata, byte[] payload) {
            this.metadata = metadata;
            this.payload = payload;
        }
    }

    private final String bridgeSecret = randomHex(32);

    boolean start(boolean launchActivity) {
        try {
            Context daemonContext = DaemonBootstrap.getContext();
            if (daemonContext == null) return false;
            // DaemonBootstrap's package context identifies as com.overdrive.app,
            // but this app_process is uid 2000. ActivityManager rejects that
            // package/uid mismatch before it even checks the component's DUMP
            // permission. Use a real com.android.shell package context so the
            // Binder caller identity and attributed package agree. This also
            // keeps the ephemeral bridge secret out of command-line arguments.
            Context context = daemonContext.createPackageContext(
                "com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            if (!"com.android.shell".equals(context.getPackageName())) return false;
            Intent intent = new Intent(RemoteDevViewBridgeService.ACTION_START);
            intent.setComponent(new ComponentName(DaemonBootstrap.getPackageName(), SERVICE_CLASS));
            intent.putExtra(RemoteDevViewBridgeService.EXTRA_BRIDGE_SECRET, bridgeSecret);
            intent.putExtra(RemoteDevViewBridgeService.EXTRA_LAUNCH_ACTIVITY, launchActivity);
            return context.startService(intent) != null;
        } catch (Throwable error) {
            try {
                CameraDaemon.log("RemoteDevView: bridge start failed: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
            } catch (Throwable ignored) {}
            return false;
        }
    }

    Response send(JSONObject request) {
        Socket socket = null;
        try {
            request.put("secret", bridgeSecret);
            socket = new Socket();
            socket.connect(new InetSocketAddress(
                RemoteDevViewBridgeService.HOST,
                RemoteDevViewBridgeService.PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println(request.toString());

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
        JSONObject request = new JSONObject();
        try { request.put("command", "shutdown"); } catch (Exception ignored) {}
        send(request);
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        new SecureRandom().nextBytes(bytes);
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }
}
