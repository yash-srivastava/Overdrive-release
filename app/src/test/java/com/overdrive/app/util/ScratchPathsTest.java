package com.overdrive.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class ScratchPathsTest {

    @After
    public void tearDown() {
        ScratchPaths.resetForTests();
    }

    @Test
    public void defaultDirIsLegacyBeforeProbe() {
        assertEquals(ScratchPaths.LEGACY_DIR, ScratchPaths.getDir());
        assertEquals("/data/local/tmp/cam_daemon.log",
                ScratchPaths.path("cam_daemon.log"));
        assertEquals("echo ok", ScratchPaths.remapShell("echo ok"));
    }

    @Test
    public void fallbackRemapsLegacyPrefixInPathsAndShell() {
        ScratchPaths.forceFallback();
        String fb = ScratchPaths.getFallbackDir();
        assertFalse(ScratchPaths.usesLegacyDir());
        assertEquals(fb + "/camera_daemon.lock",
                ScratchPaths.path("/data/local/tmp/camera_daemon.lock"));
        assertEquals("mkdir -p " + fb,
                ScratchPaths.remapShell("mkdir -p /data/local/tmp"));
        assertTrue(ScratchPaths.prepareShellCommand("touch /data/local/tmp/x")
                .contains("export OVERDRIVE_SCRATCH='" + fb + "'"));
        assertTrue(ScratchPaths.prepareShellCommand("touch /data/local/tmp/x")
                .contains("touch " + fb + "/x"));
    }

    @Test
    public void readPathsIncludesLegacyAndFallbackWhenNotOnLegacy() {
        ScratchPaths.forceFallback();
        String[] paths = ScratchPaths.readPaths("overdrive_config.json");
        assertEquals(3, paths.length);
        assertEquals(ScratchPaths.path("overdrive_config.json"), paths[0]);
        assertEquals("/data/local/tmp/overdrive_config.json", paths[1]);
        assertEquals(ScratchPaths.getFallbackDir() + "/overdrive_config.json", paths[2]);
    }
}
