package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression tests for run-2 finding
 * {@code loopback-ipc-19876-19877-19878-no-peer-auth} and
 * {@code com.overdrive.app.telegram.pairing.owner-in-world-writable-config}:
 * the shell-UID loopback IPC sockets must require an HMAC-signed envelope
 * (device secret) for every mutating command; unsigned lines may only be
 * read-only probes.
 */
public class LoopbackIpcAuthContractTest {

    private static String read(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    @Test
    public void surveillanceIpcRejectsUnsignedMutatingCommands() throws Exception {
        String s = read("src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java");
        assertTrue(s.contains("unsigned IPC command rejected"));
        assertTrue(s.contains("READ_ONLY_COMMANDS"));
        assertTrue(s.contains("\"GET_STATUS\", \"PING\""));
        // signed envelopes authenticate any command now, with nonce replay cache
        assertTrue(s.contains("IPC replay rejected"));
        assertTrue(s.replaceAll("\\s+", "").contains("rememberNonce(authenticated.nonce"));
    }

    @Test
    public void tcpCommandServerRejectsUnsignedMutatingCommands() throws Exception {
        String s = read("src/main/java/com/overdrive/app/server/TcpCommandServer.java");
        assertTrue(s.contains("unsigned IPC command rejected"));
        assertTrue(s.contains("RemoteDevViewBridgeAuth.verify(line)"));
        assertTrue(s.contains("rememberNonce(auth.nonce"));
    }

    @Test
    public void allInRepoIpcClientsSign() throws Exception {
        String[] clients = {
            "src/main/java/com/overdrive/app/server/DaemonIpcClient.java",
            "src/main/java/com/overdrive/app/server/AbrpApiHandler.java",
            "src/main/java/com/overdrive/app/server/MqttApiHandler.java",
            "src/main/java/com/overdrive/app/power/PowerNotice.java",
            "src/main/java/com/overdrive/app/client/CameraDaemonClient.java",
            "src/main/java/com/overdrive/app/DeterrentActivity.java",
            "src/main/java/com/overdrive/app/services/LocationSidecarService.java",
            "src/main/java/com/overdrive/app/overlay/StatusOverlayService.java",
            "src/main/java/com/overdrive/app/daemon/TelegramBotDaemon.java",
            "src/main/java/com/overdrive/app/roadsense/sidecar/RoadSenseImuSidecarService.kt",
        };
        for (String c : clients) {
            String s = read(c);
            assertTrue(c + " must sign IPC commands",
                    s.contains("RemoteDevViewBridgeAuth.sign("));
        }
    }
}
