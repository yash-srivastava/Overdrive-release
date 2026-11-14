package com.overdrive.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Behaviour once a head unit has opted into the alternate root. */
public class ScratchRootAlternateModeTest {

    private static final String ALT = ScratchRoot.ALT_DIR;

    @Before
    public void pinAlternateRoot() {
        ScratchRoot.setResolvedDirForTest(ALT);
    }

    @After
    public void reset() {
        ScratchRoot.setResolvedDirForTest(null);
    }

    @Test
    public void constantsDescribeTheShellOwnedDeDirectory() {
        assertEquals("/data/user_de/0/com.android.shell", ScratchRoot.ALT_PARENT);
        assertEquals("/data/user_de/0/com.android.shell/overdrive", ScratchRoot.ALT_DIR);
        assertEquals(ScratchRoot.ALT_DIR + "/.od_scratch_root", ScratchRoot.ALT_MARKER);
        assertFalse(ScratchRoot.usesLegacyDir());
    }

    @Test
    public void pathMapsRelativeAndLegacyAbsoluteNames() {
        assertEquals(ALT + "/overdrive_config.json",
                ScratchRoot.path("overdrive_config.json"));
        assertEquals(ALT + "/overdrive_config.json",
                ScratchRoot.path("/data/local/tmp/overdrive_config.json"));
        assertEquals(ALT, ScratchRoot.path("/data/local/tmp"));
        assertEquals(ALT, ScratchRoot.path(""));
        // Other absolute roots are never touched.
        assertEquals("/sdcard/DCIM/BYDCam", ScratchRoot.path("/sdcard/DCIM/BYDCam"));
        assertEquals("/data/data/com.overdrive.app/files",
                ScratchRoot.path("/data/data/com.overdrive.app/files"));
        // A sibling that merely shares the prefix is not the legacy root.
        assertEquals("/data/local/tmpfs/x", ScratchRoot.path("/data/local/tmpfs/x"));
    }

    @Test
    public void remapRewritesWholePathComponentsOnly() {
        assertEquals(
                "rm -f " + ALT + "/camera_daemon.disabled 2>/dev/null; "
                        + "nohup sh " + ALT + "/start_cam_daemon.sh > /dev/null 2>&1 &",
                ScratchRoot.remap(
                        "rm -f /data/local/tmp/camera_daemon.disabled 2>/dev/null; "
                        + "nohup sh /data/local/tmp/start_cam_daemon.sh > /dev/null 2>&1 &"));
        // Bare root followed by a delimiter or end of string.
        assertEquals("cd " + ALT + " && ls", ScratchRoot.remap("cd /data/local/tmp && ls"));
        assertEquals("HOME=" + ALT, ScratchRoot.remap("HOME=/data/local/tmp"));
        assertEquals("LD_LIBRARY_PATH=/vendor/lib64:" + ALT,
                ScratchRoot.remap("LD_LIBRARY_PATH=/vendor/lib64:/data/local/tmp"));
        // Quoted and heredoc bodies are plain text to the rewriter.
        assertEquals("LOCK_FILE=\"" + ALT + "/telegram_bot_daemon.lock\"",
                ScratchRoot.remap("LOCK_FILE=\"/data/local/tmp/telegram_bot_daemon.lock\""));
        // Look-alike prefixes are left alone.
        assertEquals("mount /data/local/tmpfs", ScratchRoot.remap("mount /data/local/tmpfs"));
        assertEquals("x /data/local/tmp_old y", ScratchRoot.remap("x /data/local/tmp_old y"));
        assertEquals("", ScratchRoot.remap(""));
        assertEquals(null, ScratchRoot.remap(null));
    }

    @Test
    public void shellPrefixExportsRootAndTmpdirAndSelfHealsTraversal() {
        String prefix = ScratchRoot.shellPrefix();
        assertEquals(
                "export OVERDRIVE_SCRATCH='" + ALT + "'; "
                        + "export TMPDIR='" + ALT + "'; "
                        + "mkdir -p '" + ALT + "' 2>/dev/null; "
                        + "chmod 711 '/data/user_de/0/com.android.shell' '" + ALT + "' 2>/dev/null; ",
                prefix);
        assertTrue(prefix.endsWith("; "));
    }

    @Test
    public void prepareIsPrefixPlusRemap() {
        String command = "cat > /data/local/tmp/.adb_script_1.sh <<'EOF'\n"
                + "rm -f /data/local/tmp/x\nEOF";
        assertEquals(ScratchRoot.shellPrefix() + ScratchRoot.remap(command),
                ScratchRoot.prepare(command));
        assertEquals(ScratchRoot.shellPrefix(), ScratchRoot.prepare(null));
        assertTrue(ScratchRoot.prepare(command).contains(
                "cat > " + ALT + "/.adb_script_1.sh <<'EOF'\nrm -f " + ALT + "/x\nEOF"));
    }

    @Test
    public void applyEnvStampsBothVariables() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/system/bin");
        ScratchRoot.applyEnv(env);
        assertEquals("/system/bin", env.get("PATH"));
        assertEquals(ALT, env.get("OVERDRIVE_SCRATCH"));
        assertEquals(ALT, env.get("TMPDIR"));

        ProcessBuilder builder = new ProcessBuilder("sh", "-c", "true");
        ScratchRoot.applyEnv(builder);
        assertEquals(ALT, builder.environment().get("OVERDRIVE_SCRATCH"));
    }

    @Test
    public void toggleCommandsAreAbsoluteAndSelfVerifying() {
        String enable = ScratchRoot.enableCommand();
        assertEquals(
                "mkdir -p '" + ALT + "' && chmod 711 '/data/user_de/0/com.android.shell' '" + ALT + "'"
                        + " && : > '" + ALT + "/.od_scratch_root'"
                        + " && chmod 644 '" + ALT + "/.od_scratch_root'"
                        + " && echo OD_SCRATCH_ON",
                enable);
        assertEquals("rm -f '" + ALT + "/.od_scratch_root' 2>/dev/null; echo OD_SCRATCH_OFF",
                ScratchRoot.disableCommand());
        assertEquals("OD_SCRATCH_ON", ScratchRoot.ENABLE_OK);
        assertEquals("OD_SCRATCH_OFF", ScratchRoot.DISABLE_OK);
        // Neither depends on the live root: they must work before and after the flip.
        ScratchRoot.setResolvedDirForTest(ScratchRoot.LEGACY_DIR);
        assertEquals(enable, ScratchRoot.enableCommand());
    }

    @Test
    public void shellQuoteEscapesSingleQuotes() {
        assertEquals("'a'\\''b'", ScratchRoot.shellQuote("a'b"));
        assertEquals("''", ScratchRoot.shellQuote(null));
    }

    @Test
    public void counterpartPathMapsBothRootsSymmetrically() {
        ScratchRoot.setResolvedDirForTest(ALT);
        assertEquals(ScratchRoot.LEGACY_DIR + "/acc_sentry_daemon.disabled",
                ScratchRoot.counterpartPath("acc_sentry_daemon.disabled"));
        assertEquals(ScratchRoot.LEGACY_DIR + "/acc_sentry_daemon.disabled",
                ScratchRoot.counterpartPath(ALT + "/acc_sentry_daemon.disabled"));

        ScratchRoot.setResolvedDirForTest(ScratchRoot.LEGACY_DIR);
        assertEquals(ALT + "/acc_sentry_daemon.disabled",
                ScratchRoot.counterpartPath("acc_sentry_daemon.disabled"));
        assertEquals(ALT + "/acc_sentry_daemon.disabled",
                ScratchRoot.counterpartPath(ScratchRoot.LEGACY_DIR + "/acc_sentry_daemon.disabled"));
        assertEquals("/sdcard/DCIM", ScratchRoot.counterpartPath("/sdcard/DCIM"));
    }
}
