package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScreenDeterrentDiLink5GuardTest {

    @Test
    public void appSideCarAwakeAbortHoldsDaemonSuppressionWindow() {
        assertFalse(ScreenDeterrent.isRecentDiLink5AppAwakeAbort(100_000L, 0L));
        assertTrue(ScreenDeterrent.isRecentDiLink5AppAwakeAbort(120_000L, 100_000L));
        assertFalse(ScreenDeterrent.isRecentDiLink5AppAwakeAbort(
                100_000L + ScreenDeterrent.DILINK5_APP_AWAKE_HOLD_MS, 100_000L));
    }
}
