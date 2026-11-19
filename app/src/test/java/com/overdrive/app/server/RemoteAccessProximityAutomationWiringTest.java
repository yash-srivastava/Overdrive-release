package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Static daemon wiring contracts; no Android hardware, collector, or actuator is started. */
public class RemoteAccessProximityAutomationWiringTest {
    private static final String HANDLER =
            "app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java";

    @Test public void retainsPinnedHandlerSurface() throws Exception {
        String handler = read(HANDLER);
        assertEquals(30, count(handler, "\nimport "));
        assertEquals(1, count(handler, "RemoteAccessSettings.lockWaitsForKeyAbsence("));
        String phone = extract(handler, "    private static final class PhoneState {");
        assertFalse(phone.contains("automationEvaluator"));
        assertFalse(phone.contains("automationSignal"));
        assertTrue(handler.contains("new java.util.WeakHashMap<>();"));
        assertTrue(handler.contains("private static java.util.List<MultiPhoneProximityPolicy.Observation> automationPhoneDepartureStates(long now)"));
        assertTrue(handler.contains("private static java.util.List<MultiPhoneProximityPolicy.Observation> phoneDepartureStates()"));
    }

    @Test public void separateEvidenceCannotBeRevokedByVehicleIneligibleStatus() throws Exception {
        String handler = read(HANDLER);
        String revoke = extract(handler, "    private static void revokePhoneSignal(PhoneState phone, String reason, boolean nativeProof,");
        assertFalse(revoke.contains("AUTOMATION_PHONES"));
        assertFalse(revoke.contains("PROXIMITY_AUTOMATION"));
        assertTrue(handler.contains("evidence.lastAcceptedElapsedMs, now, staleMs"));
        assertTrue(handler.contains("evidence.discard(\"stale\")"));
        assertTrue(handler.contains("updateProximityAutomationLocked(now);\n        // A paused or revoked signal"));
    }

    @Test public void maintainsReadOnlyQueueAndDrainsBothSavePaths() throws Exception {
        String handler = read(HANDLER);
        String update = extract(handler, "    private static void updateProximityAutomationLocked(long now) throws Exception {");
        for (String forbidden : new String[] {"VehicleCommandRouter.getInstance()", "perform(",
                "recordAction(", "KEY_INSIDE.noteKeyBypass", "revokePhoneSignal("})
            assertFalse(forbidden, update.contains(forbidden));
        String save = extract(handler, "    private static void saveConfig(String body, OutputStream out) throws Exception {");
        assertEquals(2, count(save, "publishProximityAutomation();"));
        assertTrue(handler.contains("com.overdrive.app.automation.Automations.update("));
        assertTrue(handler.contains("waitForKey = motion.power == 0"));
        assertTrue(read("app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java")
                .contains("RemoteAccessApiHandler.refreshAutomationPresence()"));
    }

    private static String extract(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(signature, start >= 0);
        int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
    }

    private static int count(String text, String term) {
        int n = 0;
        for (int i = text.indexOf(term); i >= 0; i = text.indexOf(term, i + term.length())) n++;
        return n;
    }

    private static String read(String relative) throws Exception {
        Path parent = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (parent != null) {
            Path path = parent.resolve(relative);
            if (Files.isRegularFile(path))
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            parent = parent.getParent();
        }
        throw new IllegalArgumentException("Cannot locate " + relative);
    }
}
