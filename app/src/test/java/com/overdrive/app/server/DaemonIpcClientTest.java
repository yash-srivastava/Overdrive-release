package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class DaemonIpcClientTest {
    @Test public void requestCopiesFieldsWithoutChangingTheSelectedCommand() throws Exception {
        JSONObject fields = new JSONObject()
                .put("command", "OTHER")
                .put("id", "abcd")
                .put("enabled", true)
                .put("options", new JSONObject().put("interval", 10))
                .put("optional", JSONObject.NULL);
        JSONObject request = DaemonIpcHttpProxy.requestFor("UPDATE_MQTT_CONNECTION", fields);
        assertEquals("UPDATE_MQTT_CONNECTION", request.getString("command"));
        assertEquals("abcd", request.getString("id"));
        assertTrue(request.getBoolean("enabled"));
        assertEquals(10, request.getJSONObject("options").getInt("interval"));
        assertTrue(request.has("optional"));
        assertTrue(request.isNull("optional"));
        assertEquals("OTHER", fields.getString("command"));
        assertEquals(1, DaemonIpcHttpProxy.requestFor("GET_ABRP_STATUS", null).length());
    }

    @Test public void readsOneJsonLineAndClosesTheConnection() throws Exception {
        JSONObject reply = exchangeWithReply("{\"success\":true}\n");
        assertTrue(reply.getBoolean("success"));
    }

    @Test public void endOfStreamReturnsNull() throws Exception {
        assertNull(exchangeWithReply(null));
    }

    @Test public void malformedAndEmptyRepliesAreReportedToTheCaller() throws Exception {
        for (String reply : new String[]{"not JSON\n", "\n"}) {
            try {
                exchangeWithReply(reply);
                fail("Expected invalid JSON to be reported");
            } catch (JSONException expected) {
                // The HTTP proxy logs the failure; the public send method returns null.
            }
        }
    }

    private static JSONObject exchangeWithReply(String reply) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            server.setSoTimeout(3000);
            Future<Void> peer = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    JSONObject received = new JSONObject(reader.readLine());
                    assertEquals("GET_ABRP_STATUS", received.getString("command"));
                    if (reply != null) {
                        PrintWriter writer = new PrintWriter(socket.getOutputStream());
                        writer.print(reply);
                        writer.flush();
                    } else {
                        socket.shutdownOutput();
                    }
                    assertNull(reader.readLine());
                }
                return null;
            });
            try {
                return DaemonIpcClient.exchange(new InetSocketAddress(loopback, server.getLocalPort()),
                        new JSONObject().put("command", "GET_ABRP_STATUS"), 3000);
            } finally {
                peer.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
