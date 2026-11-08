package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * V60-11b contract (post-v60.1): the launcher MainActivity must not expose custom
 * start-action intent-filters anymore, and BootReceiver must be DUMP-gated.
 * (v60.1 already DUMP-gates the services; this covers the two remaining holes.)
 */
public class V60LauncherBootGateContractTest {
    @Test
    public void launcherHasNoCustomAction_andBootReceiverIsDumpGated() throws Exception {
        String m = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")));
        int ma = m.indexOf("com.overdrive.app.ui.MainActivity\"");
        String mainBlock = m.substring(ma, m.indexOf("</activity>", ma));
        assertFalse(mainBlock.contains("START_LOCATION_ACTIVITY"));
        int br = m.indexOf("com.overdrive.app.receiver.BootReceiver\"");
        String brBlock = m.substring(br, m.indexOf(">", br));
        assertTrue(brBlock.contains("android.permission.DUMP"));
    }
}
