package com.overdrive.app.logging;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
}
