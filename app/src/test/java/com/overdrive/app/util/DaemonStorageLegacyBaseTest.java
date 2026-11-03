package com.overdrive.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Test;

/** Keeps the legacy path alias and its consumers stable without changing relocation policy. */
public class DaemonStorageLegacyBaseTest {
    @After
    public void clearDecision() {
        DaemonStorage.resetCacheForTesting();
    }

    @Test
    public void legacyBaseUsesTheSameCompileTimeRoot() {
        assertEquals("/data/local/tmp", DaemonStorage.LEGACY_BASE);
        assertSame(ScratchRoot.LEGACY_DIR, DaemonStorage.LEGACY_BASE);
    }

    @Test
    public void legacyConsumersRetainTheirPathsAndCommands() throws Exception {
        pinRelocation(false);
        String path = "/data/local/tmp/camera_stream_mode.txt";
        String command = "cat /data/local/tmp/telegram_bot_config.json";
        assertEquals(ScratchRoot.LEGACY_DIR, DaemonStorage.base());
        assertEquals(path, DaemonStorage.path("camera_stream_mode.txt"));
        assertSame(path, DaemonStorage.rebase(path));
        assertSame(command, DaemonStorage.rebaseCommand(command));
    }

    @Test
    public void relocatedConsumersStillMapOnlyLegacyPathComponents() throws Exception {
        pinRelocation(true);
        assertEquals(DaemonStorage.RELOCATED_BASE, DaemonStorage.base());
        assertEquals(DaemonStorage.RELOCATED_BASE + "/camera_stream_mode.txt",
                DaemonStorage.rebase("/data/local/tmp/camera_stream_mode.txt"));
        assertEquals(DaemonStorage.RELOCATED_BASE,
                DaemonStorage.rebase(ScratchRoot.LEGACY_DIR));
        assertEquals("/data/local/tmpfs/file", DaemonStorage.rebase("/data/local/tmpfs/file"));
        assertEquals("/sdcard/DCIM/BYDCam", DaemonStorage.rebase("/sdcard/DCIM/BYDCam"));
        assertNull(DaemonStorage.rebase(null));
    }

    private static void pinRelocation(boolean value) throws Exception {
        Field decision = DaemonStorage.class.getDeclaredField("relocated");
        decision.setAccessible(true);
        decision.set(null, value);
    }
}
