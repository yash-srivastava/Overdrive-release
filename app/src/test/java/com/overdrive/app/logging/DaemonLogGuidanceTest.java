package com.overdrive.app.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;

/** Keeps the About-page daemon guidance complete as new log sources are added. */
public class DaemonLogGuidanceTest {

    @Test
    public void everyAvailableDaemonHasActionableGuidance() {
        assertFalse("At least one daemon log should be available", DaemonLogPaths.keys().isEmpty());

        for (String key : DaemonLogPaths.keys()) {
            DaemonLogPaths.LogDescription description = DaemonLogPaths.descriptionFor(key);
            assertNotNull("Missing diagnostic description for daemon: " + key, description);
            assertFalse("Missing display name for daemon: " + key, description.name.trim().isEmpty());
            assertFalse("Missing component ownership for daemon: " + key, description.component.trim().isEmpty());
            assertFalse("Missing send guidance for daemon: " + key, description.sendWhen.trim().isEmpty());
        }
    }

    @Test
    public void unknownDaemonDoesNotReceiveMisleadingGuidance() {
        assertNotNull(DaemonLogPaths.descriptionFor(" CAMERA "));
        assertTrue(DaemonLogPaths.descriptionFor("not-a-daemon") == null);
        assertTrue(DaemonLogPaths.descriptionFor(null) == null);
    }

    @Test
    public void daemonLookupsIgnoreDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            for (String key : DaemonLogPaths.keys()) {
                String uppercase = " " + key.toUpperCase(Locale.ROOT) + " ";
                String path = DaemonLogPaths.pathFor(key);
                DaemonLogPaths.LogDescription description = DaemonLogPaths.descriptionFor(key);
                assertNotNull(path);
                assertNotNull(description);
                assertEquals(key, path, DaemonLogPaths.pathFor(uppercase));
                assertSame(key, description, DaemonLogPaths.descriptionFor(uppercase));
            }
            assertNull(DaemonLogPaths.pathFor(null));
            assertNull(DaemonLogPaths.pathFor("unknown"));
            assertNull(DaemonLogPaths.descriptionFor(null));
            assertNull(DaemonLogPaths.descriptionFor("unknown"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
