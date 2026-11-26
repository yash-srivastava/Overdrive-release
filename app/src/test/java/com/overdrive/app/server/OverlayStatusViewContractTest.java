package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class OverlayStatusViewContractTest {
    @Test
    public void onlyExplicitDirectLoopbackStatusGetsReducedView() {
        assertTrue(HttpServer.isOverlayStatusRequest("GET", "/status", "overlay", true, false));
        assertFalse(HttpServer.isOverlayStatusRequest("GET", "/status", null, true, false));
        assertFalse(HttpServer.isOverlayStatusRequest("GET", "/status", "full", true, false));
        assertFalse(HttpServer.isOverlayStatusRequest("GET", "/status", "overlay", false, false));
        assertFalse(HttpServer.isOverlayStatusRequest("GET", "/status", "overlay", true, true));
        assertFalse(HttpServer.isOverlayStatusRequest("POST", "/status", "overlay", true, false));
        assertFalse(HttpServer.isOverlayStatusRequest("GET", "/status?x=1", "overlay", true, false));
        assertFalse(HttpServer.isOverlayStatusRequest("GET", "/api/status", "overlay", true, false));
    }

    @Test
    public void reducedViewUsesSharedLiveStateWithoutDashboardProbes() throws IOException {
        String server = readSource("server/HttpServer.java");
        String overlay = readSource("overlay/StatusOverlayService.java");
        int auth = server.indexOf("if (!authenticated)");
        int dispatch = server.indexOf("if (isOverlayStatusRequest(");
        assertTrue(auth >= 0 && dispatch > auth);
        assertTrue(server.contains("sendOverlayStatus(out);"));
        assertTrue(server.contains("sendStatus(out);"));
        assertTrue(overlay.contains("\"/status\", \"GET\", 2000, 2000)"));
        assertTrue(overlay.contains("conn.setRequestProperty(\"X-OD-Status-View\", \"overlay\");"));

        int slimStart = server.indexOf("private void sendOverlayStatus(");
        int fullStart = server.indexOf("private void sendStatus(");
        int sharedStart = server.indexOf("private void appendOverlayStatus(");
        int sharedEnd = server.indexOf("static boolean resolveChargingPlugged(", sharedStart);
        assertTrue(slimStart >= 0 && fullStart > slimStart && sharedStart > fullStart
                && sharedEnd > sharedStart);
        String slim = server.substring(slimStart, fullStart);
        String full = server.substring(fullStart, sharedStart);
        String shared = server.substring(sharedStart, sharedEnd);
        assertTrue(slim.contains("appendOverlayStatus(status);"));
        assertTrue(full.contains("appendOverlayStatus(status);"));
        assertTrue(slim.contains("HttpResponse.sendJsonNoCors(out, status.toString());"));
        for (String key : new String[]{"recording", "acc"}) {
            assertTrue(slim.contains("status.put(\"" + key + "\","));
            assertTrue(full.contains("status.put(\"" + key + "\","));
        }
        for (String key : new String[]{"recordingStatus", "replay", "tripStatus", "tripBriefing",
                "tripBriefingMoving", "tripBriefingGear", "tripBriefingKmh", "tripBriefingSoon",
                "tripBriefingSync"}) {
            assertTrue(key, shared.contains("status.put(\"" + key + "\","));
        }
        for (String probe : new String[]{"waitForVehicleDataReady(", "readLivePublication(",
                "getBatteryInfo(", "getLocationJson(", "NetworkMonitor.getNetworkInfo(",
                "BluetoothRadioStatusReader.current(", "ring.getStats(", "stablePublicHostState("}) {
            assertTrue(probe, full.contains(probe));
            assertFalse(probe, (slim + shared).contains(probe));
        }
    }

    private static String readSource(String name) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null) {
            Path file = root.resolve("app/src/main/java/com/overdrive/app/" + name);
            if (Files.isRegularFile(file)) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            }
            root = root.getParent();
        }
        throw new IOException("Missing source: " + name);
    }
}
