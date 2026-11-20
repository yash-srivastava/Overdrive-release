package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class RecordingEngineDiLinkIdentityTest {
    @Test
    public void firmwareCoreDeterminesDiLink3RegardlessOfConfiguredCameraMode() {
        assertTrue(RecordingEngine.isDiLink3HeadUnit("DiLink3.0", "DiLink3.0"));
        assertFalse(RecordingEngine.isDiLink3HeadUnit("DiLink4.0", "DiLink4.0"));
        assertFalse(RecordingEngine.isDiLink3HeadUnit("", "unknown"));
        assertFalse(RecordingEngine.isDiLink3HeadUnit("DiLink5.0", "DiLink5.0"));
    }
}
