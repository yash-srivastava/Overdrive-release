package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * V60-08 contract: the automation auth-bypass allowlist must NOT contain
 * /api/vehicle/ (vehicle actuators) — MQTT-triggered automations are not an
 * authenticated principal.
 */
public class AutomationAllowlistContractTest {
    @Test
    public void vehicleApiIsNotAutomationReachable() throws Exception {
        java.lang.reflect.Field f = HttpServer.class.getDeclaredField("AUTOMATION_ALLOWED_PREFIXES");
        f.setAccessible(true);
        String[] prefixes = (String[]) f.get(null);
        for (String p : prefixes) {
            assertFalse("automation allowlist must not include vehicle controls: " + p,
                    p.startsWith("/api/vehicle/"));
        }
    }
}
